package com.billybobs.radiotuner;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * The last station tuned in each real band - AM, FM, DAB and Internet -
 * shared by the phone app and Android Auto, so picking a band on either
 * one goes back to the same station. Each slot also notes when it was
 * last played: the car's combined DAB/Internet band opens on whichever of
 * the two was played more recently.
 *
 * Stations are kept by name, which the phone page and the car's station
 * list share.
 */
final class BandMemory {
    private static final String PREFS = "BandMemory";

    private BandMemory() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** band: "am", "fm", "dab" or "net" (as StationLibrary.realBand gives). */
    static void remember(Context ctx, String band, String name) {
        if (band == null || name == null || name.isEmpty()) return;
        prefs(ctx).edit()
                .putString("name_" + band, name)
                .putLong("at_" + band, System.currentTimeMillis())
                .apply();
    }

    /** A station was renamed in the editor: keep remembering it (time unchanged). */
    static void rename(Context ctx, String oldName, String newName) {
        if (oldName == null || newName == null || oldName.equals(newName)) return;
        SharedPreferences p = prefs(ctx);
        SharedPreferences.Editor e = p.edit();
        for (String band : new String[]{"am", "fm", "dab", "net"}) {
            if (oldName.equals(p.getString("name_" + band, null))) e.putString("name_" + band, newName);
        }
        e.apply();
    }

    static String name(Context ctx, String band) {
        return prefs(ctx).getString("name_" + band, null);
    }

    static long at(Context ctx, String band) {
        return prefs(ctx).getLong("at_" + band, 0L);
    }

    /** {"am":"2GB Sydney","fm":"Triple M",...} - only the bands that have one. */
    static String toJson(Context ctx) {
        JSONObject o = new JSONObject();
        for (String band : new String[]{"am", "fm", "dab", "net"}) {
            String n = name(ctx, band);
            if (n != null) {
                try { o.put(band, n); } catch (Exception ignored) { }
            }
        }
        return o.toString();
    }
}
