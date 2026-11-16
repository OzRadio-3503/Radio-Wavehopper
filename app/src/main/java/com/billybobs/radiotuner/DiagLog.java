package com.billybobs.radiotuner;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Small always-on diagnostic log of playback events (station changes, buffering,
 * errors, screen on/off, Doze, network). Capped in size; the newest part is kept.
 * Exported from the page by long-pressing the DIGITAL TUNING label.
 */
final class DiagLog {
    private static final long MAX_BYTES = 300_000L;
    private static final long KEEP_BYTES = 200_000L;
    private static File file;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private DiagLog() {}

    static synchronized void init(Context ctx) {
        if (file == null) file = new File(ctx.getApplicationContext().getFilesDir(), "diag.log");
    }

    static synchronized void log(String msg) {
        android.util.Log.i("BBDiag", msg);
        if (file == null) return;
        try {
            if (file.length() > MAX_BYTES) trim();
            try (FileWriter w = new FileWriter(file, true)) {
                w.write(FMT.format(new Date()) + "  " + msg + "\n");
            }
        } catch (Throwable ignored) { }
    }

    private static void trim() throws IOException {
        byte[] all = new byte[(int) file.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int off = 0, n;
            while (off < all.length && (n = in.read(all, off, all.length - off)) > 0) off += n;
        }
        int start = (int) Math.max(0, all.length - KEEP_BYTES);
        while (start < all.length && all[start] != '\n') start++;
        start++;
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            if (start < all.length) out.write(all, start, all.length - start);
        }
    }

    /** Builds a shareable text file (header + log) in the cache dir. */
    static synchronized File export(Context ctx) throws IOException {
        File out = new File(ctx.getCacheDir(), "shared");
        //noinspection ResultOfMethodCallIgnored
        out.mkdirs();
        File f = new File(out, "radiowavehopper-log.txt");
        try (FileWriter w = new FileWriter(f, false)) {
            w.write("Radio Wavehopper diagnostic log\n");
            w.write("Device: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android "
                    + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n");
            try {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                w.write("Ignoring battery optimisations: "
                        + pm.isIgnoringBatteryOptimizations(ctx.getPackageName()) + "\n");
                w.write("Power save mode: " + pm.isPowerSaveMode() + "\n");
            } catch (Throwable ignored) { }
            w.write("Exported: " + FMT.format(new Date()) + "\n\n");
            if (file != null && file.exists()) {
                try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                    byte[] buf = new byte[8192];
                    int n;
                    StringBuilder sb = new StringBuilder();
                    while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                    w.write(sb.toString());
                }
            } else {
                w.write("(no log yet)\n");
            }
        }
        return f;
    }
}
