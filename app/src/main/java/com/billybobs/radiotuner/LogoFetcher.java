package com.billybobs.radiotuner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches real station logos from the stations' own websites (listed in
 * StationWebsites) on the phone, in the background, and keeps them in the
 * app's cache folder for StationArtworkProvider to serve to Android Auto.
 *
 * For each site it looks, in order of preference, for:
 *   1. the icons the page declares: apple-touch-icon / icon links (largest first)
 *   2. its og:image, but only if it's roughly square (not a wide banner)
 *   3. /apple-touch-icon.png at the site's root
 *   4. Google's public favicon service for the site's domain
 * and takes the first image that decodes and is at least MIN_SIDE pixels.
 *
 * A station with no usable logo is remembered as failed and retried after a
 * week; a found logo is refreshed after a month. Until a real logo arrives
 * (and whenever one can't be found) the drawn tile is used, so nothing ever
 * waits on the network.
 */
final class LogoFetcher {
    private static final String TAG = "LogoFetcher";
    private static final int MIN_SIDE = 96;
    private static final long RETRY_FAILED_MS = TimeUnit.DAYS.toMillis(7);
    private static final long REFRESH_FOUND_MS = TimeUnit.DAYS.toMillis(30);
    private static final int MAX_PAGE_BYTES = 768 * 1024;
    private static final int MAX_IMAGE_BYTES = 3 * 1024 * 1024;

    /** Called (on the fetch thread) whenever a station gets a new real logo. */
    interface Listener { void onLogoReady(String stationName); }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LogoFetcher");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final Set<String> QUEUED = Collections.synchronizedSet(new HashSet<>());
    private static volatile Listener listener;
    private static OkHttpClient client;

    private LogoFetcher() {}

    static void setListener(@Nullable Listener l) { listener = l; }

    /** The cached real logo for a station (may not exist yet). */
    static File logoFile(Context context, String stationName) {
        return new File(dir(context), cacheName(stationName) + ".png");
    }

    /**
     * The station's name plus a short code for where its logo comes from, so
     * changing its website or logo link in StationWebsites makes the app
     * look again straight away instead of keeping the old logo for a month.
     */
    private static String cacheName(String stationName) {
        String source = StationWebsites.forStation(stationName) + "|" + StationWebsites.logoForStation(stationName);
        return StationArtworkProvider.slugFor(stationName) + "-" + Integer.toHexString(source.hashCode());
    }

    /** For the Edit screen: the first usable logo found at a website address, or null. Blocking: run off the main thread. */
    @Nullable
    static Bitmap findLogo(String site) {
        try {
            for (String candidate : candidates(site)) {
                Bitmap b = download(candidate);
                if (b != null) return b;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "findLogo failed for " + site, e);
        }
        return null;
    }

    /** True if a real logo has been fetched for this station. */
    static boolean hasLogo(Context context, String stationName) {
        return logoFile(context, stationName).exists();
    }

    /** Queues a fetch for every listed station that needs one. */
    static void prefetchAll(Context context) {
        UserStations.ensure(context);
        for (StationLibrary.Station s : StationLibrary.getAllStations()) request(context, s.name);
    }

    /** Queues a fetch for one station, if it's listed and due. */
    static void request(Context context, String stationName) {
        final Context app = context.getApplicationContext();
        String site = StationWebsites.forStation(stationName);
        if (site == null || !isDue(app, stationName)) return;
        if (!QUEUED.add(stationName)) return; // already waiting
        EXECUTOR.execute(() -> {
            try {
                fetch(app, stationName, site);
            } finally {
                QUEUED.remove(stationName);
            }
        });
    }

    private static boolean isDue(Context context, String stationName) {
        long now = System.currentTimeMillis();
        File logo = logoFile(context, stationName);
        if (logo.exists()) return now - logo.lastModified() > REFRESH_FOUND_MS;
        File failed = failFile(context, stationName);
        return !failed.exists() || now - failed.lastModified() > RETRY_FAILED_MS;
    }

    private static void fetch(Context context, String stationName, String site) {
        Bitmap found = null;
        try {
            List<String> all = new ArrayList<>();
            String logo = StationWebsites.logoForStation(stationName);
            if (logo != null) all.add(logo); // a listed logo image comes first
            all.addAll(candidates(site));
            for (String candidate : all) {
                found = download(candidate);
                if (found != null) {
                    Log.i(TAG, stationName + ": logo from " + candidate);
                    break;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, stationName + ": lookup failed", e);
        }
        if (found == null) {
            Log.i(TAG, stationName + ": no usable logo at " + site + " - keeping the drawn tile");
            touch(failFile(context, stationName));
            return;
        }
        Bitmap square = StationArtworkProvider.composeOnWhite(found);
        File target = logoFile(context, stationName);
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            square.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            Log.w(TAG, stationName + ": couldn't save logo", e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        if (tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            failFile(context, stationName).delete();
            Listener l = listener;
            if (l != null) l.onLogoReady(stationName);
            // If it's the station on the home-screen widget, redraw the widget with the real logo.
            try {
                String current = context.getSharedPreferences("RadioPrefs", Context.MODE_PRIVATE)
                        .getString("last_station_title", "");
                if (stationName.equals(current)) RadioWidgetProvider.refresh(context);
            } catch (RuntimeException e) {
                Log.w(TAG, "widget refresh after logo failed", e);
            }
        }
    }

    // ---- finding candidate image addresses ----

    private static final Pattern LINK_TAG = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_TAG = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    private static List<String> candidates(String site) {
        List<String> out = new ArrayList<>();
        String html = fetchText(site);
        Uri base = Uri.parse(site);
        if (html != null) {
            // Declared icons, biggest first (apple-touch-icon defaults to 180px).
            List<int[]> sizes = new ArrayList<>();
            List<String> icons = new ArrayList<>();
            Matcher m = LINK_TAG.matcher(html);
            while (m.find()) {
                String tag = m.group();
                String rel = attr(tag, "rel");
                String href = attr(tag, "href");
                if (rel == null || href == null) continue;
                rel = rel.toLowerCase(Locale.ROOT);
                if (!rel.contains("icon") || rel.contains("mask-icon")) continue;
                if (href.toLowerCase(Locale.ROOT).contains(".svg")) continue; // can't decode SVG
                int size = parseSize(attr(tag, "sizes"));
                if (size == 0) size = rel.contains("apple-touch-icon") ? 180 : 32;
                icons.add(resolve(base, href));
                sizes.add(new int[]{size, icons.size() - 1});
            }
            Collections.sort(sizes, (a, b) -> b[0] - a[0]);
            for (int[] s : sizes) if (s[0] >= MIN_SIDE) out.add(icons.get(s[1]));
            // og:image, checked for squareness when downloaded.
            Matcher mm = META_TAG.matcher(html);
            while (mm.find()) {
                String tag = mm.group();
                String prop = attr(tag, "property");
                if (prop == null) prop = attr(tag, "name");
                String content = attr(tag, "content");
                if (prop != null && content != null && prop.equalsIgnoreCase("og:image")) {
                    out.add("og:" + resolve(base, content));
                    break;
                }
            }
        }
        String origin = base.getScheme() + "://" + base.getHost();
        out.add(origin + "/apple-touch-icon.png");
        out.add("https://www.google.com/s2/favicons?sz=256&domain=" + base.getHost());
        return out;
    }

    @Nullable
    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
                Pattern.CASE_INSENSITIVE).matcher(tag);
        if (!m.find()) return null;
        String v = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
        return v == null ? null : v.replace("&amp;", "&").trim();
    }

    private static int parseSize(@Nullable String sizes) {
        if (sizes == null) return 0;
        int best = 0;
        Matcher m = Pattern.compile("(\\d+)\\s*[xX]\\s*(\\d+)").matcher(sizes);
        while (m.find()) best = Math.max(best, Math.min(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        return best;
    }

    private static String resolve(Uri base, String href) {
        if (href.startsWith("//")) return base.getScheme() + ":" + href;
        if (href.startsWith("http://") || href.startsWith("https://")) return href;
        String origin = base.getScheme() + "://" + base.getHost();
        if (href.startsWith("/")) return origin + href;
        String path = base.getPath() == null ? "/" : base.getPath();
        String dir = path.endsWith("/") ? path : path.substring(0, path.lastIndexOf('/') + 1);
        return origin + (dir.isEmpty() ? "/" : dir) + href;
    }

    // ---- network ----

    private static synchronized OkHttpClient client() {
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(12, TimeUnit.SECONDS)
                    .callTimeout(20, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build();
        }
        return client;
    }

    private static Request httpRequest(String url) {
        return new Request.Builder().url(url)
                .header("User-Agent", StreamHeaders.BROWSER_USER_AGENT)
                .header("Accept-Language", "en-AU,en;q=0.8")
                .build();
    }

    @Nullable
    private static String fetchText(String url) {
        try (Response r = client().newCall(httpRequest(url)).execute()) {
            ResponseBody body = r.body();
            if (!r.isSuccessful() || body == null) return null;
            byte[] bytes = readLimited(body.byteStream(), MAX_PAGE_BYTES, true);
            return bytes == null ? null : new String(bytes, "UTF-8");
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Downloads and decodes an image; null if it's unusable. "og:" = must be squarish. */
    @Nullable
    private static Bitmap download(String candidate) {
        boolean mustBeSquare = candidate.startsWith("og:");
        String url = mustBeSquare ? candidate.substring(3) : candidate;
        try (Response r = client().newCall(httpRequest(url)).execute()) {
            ResponseBody body = r.body();
            if (!r.isSuccessful() || body == null) return null;
            byte[] bytes = readLimited(body.byteStream(), MAX_IMAGE_BYTES, false);
            if (bytes == null) return null;
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp == null) return null;
            int w = bmp.getWidth(), h = bmp.getHeight();
            if (Math.min(w, h) < MIN_SIDE) return null;
            if (mustBeSquare && (w > h * 1.35f || h > w * 1.35f)) return null;
            return bmp;
        } catch (IOException | IllegalArgumentException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Reads up to max bytes; null if bigger (unless truncateOk). */
    @Nullable
    private static byte[] readLimited(InputStream in, int max, boolean truncateOk) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int n;
        while ((n = in.read(chunk)) != -1) {
            if (buf.size() + n > max) {
                if (!truncateOk) return null;
                buf.write(chunk, 0, max - buf.size());
                break;
            }
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    // ---- files ----

    private static File dir(Context context) {
        File d = new File(context.getCacheDir(), "artwork/web");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static File failFile(Context context, String stationName) {
        return new File(dir(context), cacheName(stationName) + ".failed");
    }

    private static void touch(File f) {
        try {
            if (!f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.createNewFile();
            }
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
        } catch (IOException ignored) {
        }
    }
}
