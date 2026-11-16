package com.billybobs.radiotuner;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Station logos for Android Auto (browse list and Now Playing).
 *
 * Each station gets a square logo, served as
 * content://com.billybobs.radiotuner.artwork/station/<station name>.png
 *
 * In order of preference:
 * - If app/src/main/assets/logos/<slug>.png exists, that image is used (the
 *   station's real logo, on white). The slug is the station name in
 *   lower case with anything that isn't a letter or digit turned into "-",
 *   e.g. "2GB Sydney" -> logos/2gb-sydney.png, "SEN 1170" -> logos/sen-1170.png.
 *   Keyed by name, so a logo survives the station moving on the dial.
 * - Otherwise the station's real logo fetched from its website, once
 *   LogoFetcher has found one (see StationWebsites).
 * - Otherwise a logo tile is drawn: the station name on its group's colour
 *   (Sport, Talk, NSW, ...).
 *
 * Logos carry no frequency: Android Auto already shows the station's real
 * frequency in its title, e.g. "2GB Sydney (873 AM)".
 *
 * The address carries a "v" value that changes when a station's logo source
 * changes (drawn tile -> real logo), so Android Auto fetches the new image
 * instead of reusing the tile it cached.
 *
 * Logos are drawn once and cached in the app's cache folder. The provider is
 * exported (read-only, images only) because Android Auto runs in its own
 * app and has to open these itself.
 */
public class StationArtworkProvider extends ContentProvider {
    private static final String TAG = "StationArtwork";
    public static final String AUTHORITY = "com.billybobs.radiotuner.artwork";
    private static final int SIZE = 320;
    /** Bump when the drawn design changes, so cached tiles are redrawn. */
    private static final int STYLE_VERSION = 5;

    /** One colour per group, in StationLibrary.GROUPS order (wraps if more). */
    private static final int[] GROUP_COLOURS = {
            0xFFFF2D95, // Sport - pink
            0xFF00E5FF, // Talk - cyan
            0xFF4DFFB0, // NSW - green
            0xFF7FB2FF, // Victoria - blue
            0xFFFFB347, // Queensland - orange
            0xFFFF6B6B, // South Australia - red
            0xFFF3D98A, // Western Australia - gold
            0xFF9BE15D, // Tasmania - lime
            0xFFFF8C42, // Northern Territory - ochre
            0xFFC792EA, // Music groups - purple shades
            0xFFB388FF,
            0xFFD1A3FF,
            0xFFA78BFA,
            0xFFE0AAFF,
            0xFFBB86FC,
            0xFFCE93D8,
            0xFF9575CD,
            0xFFBA68C8,
            0xFFFFE066, // Internet only - yellow
    };

    /** Set when the provider starts (before any service), for uriFor(). */
    private static volatile android.content.Context appContext;

    /** The content:// address of a station's logo. */
    public static Uri uriFor(StationLibrary.Station station) {
        // What's drawn: the style and the group colour (for drawn tiles). If
        // either changes, the address changes, so Android Auto fetches the
        // new image instead of reusing its cached one.
        String drawn = STYLE_VERSION + "-" + StationLibrary.groupIndexFor(station.freq);
        String version = "tile" + drawn;
        android.content.Context context = appContext;
        if (context != null) {
            File mine = UserLogos.file(context, station.name);
            if (mine.exists()) {
                version = "user" + mine.lastModified() + "-" + drawn;
            } else if (hasAssetLogo(context, station)) {
                version = "asset" + drawn;
            } else {
                File web = LogoFetcher.logoFile(context, station.name);
                if (web.exists()) version = "web" + web.lastModified() + "-" + drawn;
            }
        }
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("station")
                .appendPath(station.name + ".png")
                .appendQueryParameter("v", version)
                .build();
    }

    @Override
    public boolean onCreate() {
        appContext = getContext().getApplicationContext();
        return true;
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only");
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 2 || !"station".equals(segments.get(0))
                || !segments.get(1).endsWith(".png")) {
            throw new FileNotFoundException(uri.toString());
        }
        String name = segments.get(1).substring(0, segments.get(1).length() - 4);
        StationLibrary.Station station = null;
        for (StationLibrary.Station s : StationLibrary.getStations()) {
            if (s.name.equals(name)) { station = s; break; }
        }
        if (station == null) throw new FileNotFoundException("No station " + name);

        // A real logo fetched from the station's website, unless the app has its own.
        File web = null;
        if (!UserLogos.has(getContext(), station.name) && !hasAssetLogo(getContext(), station)) {
            web = LogoFetcher.logoFile(getContext(), station.name);
            if (!web.exists()) {
                web = null;
                LogoFetcher.request(getContext(), station.name); // look for one in the background
            }
        }

        File file = cachedFileFor(station, web);
        if (!file.exists()) {
            try {
                writeLogo(station, web, file);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Couldn't make a logo for " + name, e);
                throw new FileNotFoundException("Logo failed for " + name);
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return "image/png";
    }

    // Images only: no table of data to query or change.
    @Nullable @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) { return null; }
    @Nullable @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) { return null; }
    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }
    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) { return 0; }

    // ---- making the logo ----

    static String slugFor(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return slug.replaceAll("^-+|-+$", "");
    }

    private File cachedFileFor(StationLibrary.Station s, @Nullable File web) {
        File dir = new File(getContext().getCacheDir(), "artwork");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File mine = UserLogos.file(getContext(), s.name);
        String source = mine.exists() ? "user" + mine.lastModified()
                : hasAssetLogo(getContext(), s) ? "asset"
                : web != null ? "web" + web.lastModified() : "drawn";
        String key = STYLE_VERSION + "|" + s.name + "|" + StationLibrary.groupIndexFor(s.freq)
                + "|" + source;
        return new File(dir, sha1(key) + ".png");
    }

    private static boolean hasAssetLogo(android.content.Context context, StationLibrary.Station s) {
        try (InputStream in = context.getAssets().open("logos/" + slugFor(s.name) + ".png")) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void writeLogo(StationLibrary.Station s, @Nullable File web, File target) throws IOException {
        Bitmap logo = null;
        File mine = UserLogos.file(getContext(), s.name);
        if (mine.exists()) {
            Bitmap picked = BitmapFactory.decodeFile(mine.getPath());
            if (picked != null) logo = composeOnWhite(picked);
        }
        if (logo == null) logo = assetLogo(s);
        if (logo == null && web != null) {
            Bitmap real = BitmapFactory.decodeFile(web.getPath());
            if (real != null) logo = composeOnWhite(real);
        }
        if (logo == null) logo = drawnLogo(s);
        // Write to a temporary file and rename, so a half-written file is never served.
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp" + Thread.currentThread().getId());
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            logo.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        if (!tmp.renameTo(target) && !target.exists()) {
            throw new IOException("Couldn't save " + target);
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
    }

    /** The station's real logo from assets/logos, centred on white, or null. */
    @Nullable
    private Bitmap assetLogo(StationLibrary.Station s) {
        try (InputStream in = getContext().getAssets().open("logos/" + slugFor(s.name) + ".png")) {
            Bitmap src = BitmapFactory.decodeStream(in);
            if (src == null || src.getWidth() == 0 || src.getHeight() == 0) return null;
            return composeOnWhite(src);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * A logo image centred on a square. The square is white (many logos are
     * transparent), unless the logo has its own solid background (say a black
     * or green tile). Then the square takes that colour, so the logo fills the
     * tile with no white frame around it.
     */
    static Bitmap composeOnWhite(Bitmap src) {
        src = trimToSolidTile(src);
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Integer own = solidEdgeColour(src);
        c.drawColor(own != null ? own : Color.WHITE);
        float pad = own != null ? 0f : SIZE * 0.06f;
        float scale = Math.min((SIZE - 2 * pad) / src.getWidth(), (SIZE - 2 * pad) / src.getHeight());
        float w = src.getWidth() * scale, h = src.getHeight() * scale;
        RectF dst = new RectF((SIZE - w) / 2, (SIZE - h) / 2, (SIZE + w) / 2, (SIZE + h) / 2);
        c.drawBitmap(src, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return out;
    }

    /**
     * Some logos are a solid-coloured tile sitting inside a white or
     * transparent margin (SEN is one). If cutting the margin away leaves a tile
     * whose edge is one solid colour, return just the tile, so it can fill the
     * square. Anything else (plain white logos, photos) is returned unchanged.
     */
    private static Bitmap trimToSolidTile(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        if (w < 16 || h < 16 || solidEdgeColour(src) != null) return src;
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = src.getPixel(x, y);
                boolean background = Color.alpha(p) < 40
                        || (Color.red(p) > 235 && Color.green(p) > 235 && Color.blue(p) > 235);
                if (background) continue;
                if (x < x0) x0 = x;
                if (x > x1) x1 = x;
                if (y < y0) y0 = y;
                if (y > y1) y1 = y;
            }
        }
        if (x1 < 0) return src;
        int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
        if (bw >= w * 0.97f && bh >= h * 0.97f) return src;          // no margin to cut
        if ((long) bw * bh < 0.3f * w * h || bw < 16 || bh < 16) return src; // too little left
        Bitmap tile = Bitmap.createBitmap(src, x0, y0, bw, bh);
        return solidEdgeColour(tile) != null ? tile : src;
    }

    /**
     * The logo's own background colour if its whole edge is one solid,
     * opaque, non-white colour; otherwise null (use white). Only the outermost
     * pixels are looked at, so a logo that is mostly white inside is fine.
     */
    @Nullable
    private static Integer solidEdgeColour(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        if (w < 8 || h < 8) return null;
        int[] edge = new int[2 * (w + h)];
        int n = 0;
        for (int x = 0; x < w; x++) { edge[n++] = src.getPixel(x, 0); edge[n++] = src.getPixel(x, h - 1); }
        for (int y = 0; y < h; y++) { edge[n++] = src.getPixel(0, y); edge[n++] = src.getPixel(w - 1, y); }
        int transparent = 0;
        int[] rs = new int[n], gs = new int[n], bs = new int[n];
        for (int k = 0; k < n; k++) {
            int p = edge[k];
            if (Color.alpha(p) < 240) transparent++;
            rs[k] = Color.red(p); gs[k] = Color.green(p); bs[k] = Color.blue(p);
        }
        if (transparent > n / 50) return null; // transparent edge: a plain logo, keep white
        java.util.Arrays.sort(rs); java.util.Arrays.sort(gs); java.util.Arrays.sort(bs);
        int mr = rs[n / 2], mg = gs[n / 2], mb = bs[n / 2]; // the typical edge colour
        if (mr > 235 && mg > 235 && mb > 235) return null; // white edge: nothing to blend
        int off = 0;
        for (int p : edge) {
            if (Math.abs(Color.red(p) - mr) > 36 || Math.abs(Color.green(p) - mg) > 36
                    || Math.abs(Color.blue(p) - mb) > 36) off++;
        }
        if (off > n * 12 / 100) return null; // edge isn't mostly one solid colour
        return Color.rgb(mr, mg, mb);
    }

    /** A drawn tile: the station name on its group's colour. */
    private Bitmap drawnLogo(StationLibrary.Station s) {
        int colour = GROUP_COLOURS[StationLibrary.groupIndexFor(s.freq) % GROUP_COLOURS.length];
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);

        // Background: the group colour, darkening slightly towards the bottom.
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, 0, SIZE, colour, darken(colour, 0.72f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, SIZE, SIZE, bg);

        // Station name: as big as fits, up to three lines, centred.
        Paint name = new Paint(Paint.ANTI_ALIAS_FLAG);
        name.setColor(0xFF111114);
        name.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        name.setTextAlign(Paint.Align.CENTER);
        float maxWidth = SIZE * 0.86f;
        float areaTop = SIZE * 0.08f, areaBottom = SIZE * 0.92f;
        List<String> lines = null;
        float size;
        for (size = SIZE * 0.30f; size >= SIZE * 0.08f; size -= 2f) {
            name.setTextSize(size);
            List<String> candidate = wrap(s.name, name, maxWidth);
            float lineHeight = size * 1.08f;
            if (candidate != null && candidate.size() <= 3
                    && candidate.size() * lineHeight <= areaBottom - areaTop) {
                lines = candidate;
                break;
            }
        }
        if (lines == null) { // very long name: smallest size, cut to three lines
            name.setTextSize(SIZE * 0.08f);
            lines = wrap(s.name, name, Float.MAX_VALUE);
            if (lines == null) lines = new ArrayList<>();
            while (lines.size() > 3) lines.remove(lines.size() - 1);
        }
        float lineHeight = name.getTextSize() * 1.08f;
        Paint.FontMetrics nm = name.getFontMetrics();
        float blockTop = areaTop + ((areaBottom - areaTop) - lines.size() * lineHeight) / 2f;
        for (int i = 0; i < lines.size(); i++) {
            float baseline = blockTop + i * lineHeight + lineHeight / 2f - (nm.ascent + nm.descent) / 2f;
            c.drawText(lines.get(i), SIZE / 2f, baseline, name);
        }
        return out;
    }

    /** Greedy word wrap; null if a single word is wider than maxWidth. */
    @Nullable
    private static List<String> wrap(String text, Paint paint, float maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (paint.measureText(word) > maxWidth) return null;
            String tryLine = line.length() == 0 ? word : line + " " + word;
            if (paint.measureText(tryLine) <= maxWidth) {
                line.setLength(0);
                line.append(tryLine);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    private static int darken(int colour, float factor) {
        return Color.argb(Color.alpha(colour),
                Math.round(Color.red(colour) * factor),
                Math.round(Color.green(colour) * factor),
                Math.round(Color.blue(colour) * factor));
    }

    private static String sha1(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(text.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
