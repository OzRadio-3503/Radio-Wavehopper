package com.billybobs.radiotuner;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Stations the listener added themselves (Edit screen).
 *
 * The page owns the format: it stores one JSON object under "data", e.g.
 * {"added":[{"freq":90.05,"name":"My Station","real":"101.7 FM","cc":"NSW","url":"https://..."}]}
 * and this class reads out what the native side needs (the added list) so the
 * phone player, widgets and Android Auto see the same stations as the page.
 * Stored on the phone only (SharedPreferences) - nothing is uploaded.
 */
public final class UserStations {
    private static final String PREFS = "UserStations";
    private static final String KEY = "data";

    private static volatile List<StationLibrary.Station> added = new ArrayList<>();
    private static volatile boolean loaded = false;
    /** Built-in stations the listener removed, keyed by dial frequency "90.08". */
    private static volatile java.util.Set<String> hidden = new java.util.HashSet<>();
    /** Built-in stations the listener edited: dial key -> {name, real, cc, url}. */
    private static volatile java.util.Map<String, JSONObject> edited = new java.util.HashMap<>();

    /** Name of the built-in each saved removal/edit was made on: dial key -> built-in's own name. */
    private static volatile java.util.Map<String, String> names = new java.util.HashMap<>();
    /** Built-ins that moved in an update, so older saved entries still know who they belong to. */
    private static final java.util.Map<String, String> LEGACY_KEY_NAMES = new java.util.HashMap<>();
    static { LEGACY_KEY_NAMES.put("90.08", "Station X"); }

    private UserStations() {}

    /** Load once per process (safe to call from anywhere, repeatedly). */
    public static void ensure(Context context) {
        if (loaded) return;
        synchronized (UserStations.class) {
            if (loaded) return;
            parse(raw(context));
            loaded = true;
        }
    }

    public static List<StationLibrary.Station> added() {
        return added;
    }

    /** The saved JSON for the page ("" if nothing saved yet). */
    public static String raw(Context context) {
        SharedPreferences p = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.getString(KEY, "");
    }

    /** Save the page's JSON and refresh what the player sees. Returns false if it isn't valid JSON. */
    public static boolean save(Context context, String json) {
        try {
            new JSONObject(json); // validate
        } catch (Throwable t) {
            return false;
        }
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, json).apply();
        synchronized (UserStations.class) {
            parse(json);
            loaded = true;
        }
        return true;
    }

    static String key(double freq) {
        return String.format(java.util.Locale.US, "%.2f", freq);
    }

    /** Applies the listener's removals and edits to the built-in list (in place). */
    static void applyOverrides(List<StationLibrary.Station> list) {
        java.util.Set<String> h = hidden;
        java.util.Map<String, JSONObject> e = edited;
        if (h.isEmpty() && e.isEmpty()) return;
        for (int i = list.size() - 1; i >= 0; i--) {
            StationLibrary.Station st = list.get(i);
            String k = key(st.freq);
            // A saved removal or edit only applies to the station it was made on. If an
            // update has put a different station on that key, leave the new one alone.
            String expected = names.get(k);
            if (expected == null) expected = LEGACY_KEY_NAMES.get(k);
            if (expected != null && !expected.equals(st.name)) continue;
            if (h.contains(k)) { list.remove(i); continue; }
            JSONObject o = e.get(k);
            if (o == null) continue;
            String url = o.optString("url", st.url);
            boolean sameUrl = url.equals(st.url);
            double newFreq = o.has("freq") ? o.optDouble("freq", st.freq) : st.freq;
            if (!(newFreq > 87.4 && newFreq < 102.2)) newFreq = st.freq;
            list.set(i, new StationLibrary.Station(newFreq, o.optString("name", st.name),
                    o.optString("real", st.real), url,
                    sameUrl ? st.mimeType : null, sameUrl && st.forceMediaPlayer,
                    o.has("cc") ? o.optString("cc", "") : st.region));
        }
    }

    private static void parse(String json) {
        List<StationLibrary.Station> out = new ArrayList<>();
        java.util.Set<String> hid = new java.util.HashSet<>();
        java.util.Map<String, JSONObject> edt = new java.util.HashMap<>();
        java.util.Map<String, String> nm = new java.util.HashMap<>();
        try {
            if (json != null && !json.isEmpty()) {
                JSONObject root = new JSONObject(json);
                JSONArray hArr = root.optJSONArray("hidden");
                if (hArr != null) for (int i = 0; i < hArr.length(); i++) hid.add(hArr.optString(i));
                JSONObject nObj = root.optJSONObject("names");
                if (nObj != null) {
                    java.util.Iterator<String> nit = nObj.keys();
                    while (nit.hasNext()) { String k = nit.next(); String v = nObj.optString(k, ""); if (!v.isEmpty()) nm.put(k, v); }
                }
                JSONObject eObj = root.optJSONObject("edited");
                if (eObj != null) {
                    java.util.Iterator<String> it = eObj.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        JSONObject v = eObj.optJSONObject(k);
                        String u = v == null ? "" : v.optString("url", "");
                        if (v != null && (u.isEmpty() || u.startsWith("http://") || u.startsWith("https://"))) edt.put(k, v);
                    }
                }
                JSONArray arr = root.optJSONArray("added");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        String url = o.optString("url", "");
                        String name = o.optString("name", "");
                        // Only plain web streams; the page checks too.
                        if (name.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) continue;
                        out.add(new StationLibrary.Station(o.getDouble("freq"), name,
                                o.optString("real", "Internet only"), url, null, false,
                                o.optString("cc", "")));
                    }
                }
            }
        } catch (Throwable t) {
            android.util.Log.e("UserStations", "bad saved stations, ignoring", t);
            out = new ArrayList<>();
            hid = new java.util.HashSet<>();
            edt = new java.util.HashMap<>();
            nm = new java.util.HashMap<>();
        }
        added = out;
        names = nm;
        hidden = hid;
        edited = edt;
    }
}
