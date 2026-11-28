package com.billybobs.radiotuner;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.widget.RemoteViews;

import java.io.InputStream;

/**
 * Home-screen widget: the current station's logo, name and region, with
 * previous / play-pause / next buttons. Tapping the logo or name opens the app.
 * While a song title is known and the phone is playing, the large widget's
 * second line shows it ("♪ Artist – Title"), scrolling if it's too long;
 * otherwise that line is "On air · NSW" / "Paused · NSW" as before.
 *
 * It reads what the app already saves in "RadioPrefs" (last_station_title,
 * last_station_sub, last_station_freq, is_playing); PlaybackService calls
 * refresh() whenever any of that changes.
 */
public class RadioWidgetProvider extends AppWidgetProvider {

    private static volatile String logoKey = "";
    private static volatile Bitmap logoBitmap = null;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        refresh(context);
    }

    @Override
    public void onEnabled(Context context) {
        refresh(context);
    }

    /** Redraws every widget on the home screen from the saved state. */
    public static void refresh(Context context) {
        final Context app = context.getApplicationContext();
        AppWidgetManager manager = AppWidgetManager.getInstance(app);
        int[] ids = manager.getAppWidgetIds(new ComponentName(app, RadioWidgetProvider.class));
        int[] smallIds = manager.getAppWidgetIds(new ComponentName(app, RadioWidgetSmallProvider.class));
        int[] tinyIds = manager.getAppWidgetIds(new ComponentName(app, RadioWidgetTinyProvider.class));
        if (ids.length == 0 && smallIds.length == 0 && tinyIds.length == 0) return;

        SharedPreferences prefs = app.getSharedPreferences("RadioPrefs", Context.MODE_PRIVATE);
        String title = prefs.getString("last_station_title", "");
        String sub = prefs.getString("last_station_sub", "");
        double freq = Double.longBitsToDouble(prefs.getLong("last_station_freq_bits", Double.doubleToLongBits(-1)));
        // Use the station's current dial spot (found by name), in case it has
        // moved since it was saved - the logo is looked up by dial spot.
        StationLibrary.Station saved = StationLibrary.savedPhoneStation(app);
        if (saved != null && saved.name.equals(title)) freq = saved.freq;
        // is_playing can be left over from a killed process, so trust it only while the service lives.
        // The car's player can be the one playing (Android Auto) - asked here,
        // on the main thread, as the player can't be asked from the logo thread.
        final boolean carPlaying = RadioLibraryService.carIsPlaying();
        boolean playing = prefs.getBoolean("is_playing", false) && (PlaybackService.running || carPlaying);
        // The phone player's song, if any (not the car's: Android Auto shows its own).
        final String song = PlaybackService.running && !carPlaying ? PlaybackService.widgetSong : "";

        Bitmap have = logoKey.equals(keyFor(freq)) ? logoBitmap : null;
        draw(app, manager, ids, title, sub, song, freq, playing, have);
        drawSmall(app, manager, smallIds, title, freq, playing, have, R.layout.widget_radio_small, false);
        drawSmall(app, manager, tinyIds, title, freq, playing, have, R.layout.widget_radio_tiny, true);

        // The logo can take a moment (it may be generated), so fetch it off the main thread.
        final String key = keyFor(freq);
        if (freq >= 0 && !logoKey.equals(key)) {
            final String t = title, s = sub;
            final double f = freq;
            new Thread(() -> {
                Bitmap bmp = loadLogo(app, f);
                if (bmp != null) {
                    logoBitmap = bmp;
                    logoKey = key;
                    int[] now = manager.getAppWidgetIds(new ComponentName(app, RadioWidgetProvider.class));
                    int[] nowTiny = manager.getAppWidgetIds(new ComponentName(app, RadioWidgetTinyProvider.class));
                    int[] nowSmall = manager.getAppWidgetIds(new ComponentName(app, RadioWidgetSmallProvider.class));
                    boolean stillPlaying = app.getSharedPreferences("RadioPrefs", Context.MODE_PRIVATE)
                            .getBoolean("is_playing", false) && (PlaybackService.running || carPlaying);
                    draw(app, manager, now, t, s, song, f, stillPlaying, bmp);
                    drawSmall(app, manager, nowSmall, t, f, stillPlaying, bmp, R.layout.widget_radio_small, false);
                    drawSmall(app, manager, nowTiny, t, f, stillPlaying, bmp, R.layout.widget_radio_tiny, true);
                }
            }, "widget-logo").start();
        }
    }

    private static String keyFor(double freq) {
        return String.valueOf(freq);
    }

    private static Bitmap loadLogo(Context context, double freq) {
        try {
            UserStations.ensure(context);
            StationLibrary.Station best = null;
            double bestDist = Double.MAX_VALUE;
            for (StationLibrary.Station s : StationLibrary.getAllStations()) {
                double d = Math.abs(s.freq - freq);
                if (d < bestDist) { bestDist = d; best = s; }
            }
            if (best == null) return null;
            Uri uri = StationArtworkProvider.uriFor(best);
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2; // 320 px logo -> 160 px, plenty for a 52 dp icon and light on memory
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void draw(Context ctx, AppWidgetManager manager, int[] ids, String title, String sub,
                             String song, double freq, boolean playing, Bitmap logo) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_radio);
        boolean haveStation = title != null && !title.isEmpty() && freq >= 0;
        v.setTextViewText(R.id.w_title, haveStation ? title : "Radio Wavehopper");
        String line;
        if (!haveStation) line = "Tap to tune in";
        else if (playing && song != null && !song.isEmpty()) line = "♪ " + song;
        else if (playing) line = (sub == null || sub.isEmpty()) ? "On air" : "On air · " + sub;
        else line = (sub == null || sub.isEmpty()) ? "Paused" : "Paused · " + sub;
        v.setTextViewText(R.id.w_sub, line);
        v.setImageViewResource(R.id.w_play, playing ? R.drawable.ic_widget_pause : R.drawable.ic_widget_play);
        if (logo != null) v.setImageViewBitmap(R.id.w_logo, logo);

        PendingIntent open = PendingIntent.getActivity(ctx, 100, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.w_logo, open);
        v.setOnClickPendingIntent(R.id.w_title, open);
        v.setOnClickPendingIntent(R.id.w_sub, open);

        if (haveStation) {
            v.setOnClickPendingIntent(R.id.w_play, serviceIntent(ctx, 101, PlaybackService.ACTION_TOGGLE, true));
        } else {
            // Nothing played yet: open the app so the person can pick a station.
            v.setOnClickPendingIntent(R.id.w_play, open);
        }
        v.setOnClickPendingIntent(R.id.w_prev, serviceIntent(ctx, 102, PlaybackService.ACTION_WIDGET_PREV, true));
        v.setOnClickPendingIntent(R.id.w_next, serviceIntent(ctx, 103, PlaybackService.ACTION_WIDGET_NEXT, true));

        for (int id : ids) manager.updateAppWidget(id, v);
    }

    private static void drawSmall(Context ctx, AppWidgetManager manager, int[] ids, String title,
                                  double freq, boolean playing, Bitmap logo, int layout, boolean wholeTilePlays) {
        if (ids == null || ids.length == 0) return;
        RemoteViews v = new RemoteViews(ctx.getPackageName(), layout);
        boolean haveStation = title != null && !title.isEmpty() && freq >= 0;
        if (wholeTilePlays) {
            // 1x1: logo edge to edge in a rounded square, with a small play/pause badge.
            v.setImageViewResource(R.id.w_play, playing ? R.drawable.ic_tile_pause : R.drawable.ic_tile_play);
            if (logo != null) v.setImageViewBitmap(R.id.w_logo, roundedSquare(logo));
        } else {
            v.setImageViewResource(R.id.w_play, playing ? R.drawable.ic_widget_pause : R.drawable.ic_widget_play);
            if (logo != null) v.setImageViewBitmap(R.id.w_logo, logo);
        }
        PendingIntent open = PendingIntent.getActivity(ctx, 100, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggle = haveStation
                ? serviceIntent(ctx, 101, PlaybackService.ACTION_TOGGLE, true)
                : open;
        // 1x1: the whole tile plays/pauses. 2x1: the logo opens the app, the button plays/pauses.
        v.setOnClickPendingIntent(R.id.w_logo, wholeTilePlays ? toggle : open);
        v.setOnClickPendingIntent(R.id.w_play, toggle);
        for (int id : ids) manager.updateAppWidget(id, v);
    }

    /** The logo cropped to a square with rounded corners (about 22% of the side), like a launcher icon. */
    private static Bitmap roundedSquare(Bitmap src) {
        final int size = 192;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        int side = Math.min(src.getWidth(), src.getHeight());
        Bitmap square = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        Bitmap scaled = Bitmap.createScaledBitmap(square, size, size, true);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new android.graphics.BitmapShader(scaled,
                android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
        float r = size * 0.22f;
        new android.graphics.Canvas(out).drawRoundRect(new android.graphics.RectF(0, 0, size, size), r, r, paint);
        return out;
    }

    private static PendingIntent serviceIntent(Context ctx, int code, String action, boolean foreground) {
        Intent i = new Intent(ctx, PlaybackService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return foreground
                ? PendingIntent.getForegroundService(ctx, code, i, flags)
                : PendingIntent.getService(ctx, code, i, flags);
    }
}
