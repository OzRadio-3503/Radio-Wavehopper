package com.billybobs.radiotuner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Logos the listener chose for a station (upload or fetched from a website).
 * Kept only in the app's private storage on this phone (filesDir/userlogos),
 * never uploaded or shared. A logo here beats every other logo source.
 * Keyed by station name (the same slug the bundled logos use).
 */
final class UserLogos {
    private static final int MAX_INPUT_BYTES = 12 * 1024 * 1024;

    private UserLogos() {}

    static File file(Context context, String stationName) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "userlogos");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, StationArtworkProvider.slugFor(stationName) + ".png");
    }

    static boolean has(Context context, String stationName) {
        return file(context, stationName).exists();
    }

    static void remove(Context context, String stationName) {
        //noinspection ResultOfMethodCallIgnored
        file(context, stationName).delete();
    }

    static void rename(Context context, String oldName, String newName) {
        File from = file(context, oldName), to = file(context, newName);
        if (from.equals(to) || !from.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        to.delete();
        //noinspection ResultOfMethodCallIgnored
        from.renameTo(to);
    }

    /** Square, on white, sized for Android Auto: what gets stored and previewed. */
    static Bitmap prepare(Bitmap src) {
        return StationArtworkProvider.composeOnWhite(src);
    }

    static String toDataUrl(Bitmap bmp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    /** Saves a data: URL (from the Edit screen) as the station's logo. */
    static boolean saveDataUrl(Context context, String stationName, String dataUrl) {
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0 || !dataUrl.startsWith("data:image/")) return false;
            byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp == null || bmp.getWidth() == 0 || bmp.getHeight() == 0) return false;
            Bitmap ready = prepare(bmp);
            File target = file(context, stationName);
            File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                ready.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            if (!tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                if (!tmp.renameTo(target)) return false;
            }
            return true;
        } catch (Throwable t) {
            android.util.Log.w("UserLogos", "save failed", t);
            return false;
        }
    }

    /** The stored logo as a data: URL, or "" if none. */
    static String getDataUrl(Context context, String stationName) {
        File f = file(context, stationName);
        if (!f.exists()) return "";
        Bitmap bmp = BitmapFactory.decodeFile(f.getPath());
        return bmp == null ? "" : toDataUrl(bmp);
    }

    /** Reads a picked image (content URI), scaled down while decoding. Null if it isn't an image. */
    @Nullable
    static Bitmap decodePicked(Context context, Uri uri) {
        try {
            byte[] bytes;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[16 * 1024];
                int n, total = 0;
                while ((n = in.read(chunk)) > 0) {
                    total += n;
                    if (total > MAX_INPUT_BYTES) return null;
                    buf.write(chunk, 0, n);
                }
                bytes = buf.toByteArray();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 640) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
