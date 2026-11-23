package com.billybobs.radiotuner;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.core.app.ActivityCompat;

public class MainActivity extends Activity {

    /** Lets PlaybackService push JS commands back into the page (lock-screen controls). */
    public static MainActivity instance;

    private WebView webView;
    private AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // The page's volume slider is the phone's media volume (the same one
    // the volume buttons change), so it has to follow changes made anywhere
    // else: hardware buttons, the system volume panel, Bluetooth headsets.
    // Android has no direct "volume changed" callback for apps, but the
    // change is written to system settings, which this observer watches.
    private final ContentObserver volumeObserver = new ContentObserver(mainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            pushVolumeToPage();
        }
    };
    private static final int NOTIF_PERMISSION_REQUEST = 1001;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UserStations.ensure(this);
        // Fetch station logos in the background (widgets and Android Auto use them), not only when the car connects.
        LogoFetcher.prefetchAll(this);
        instance = this;

        // Route the volume buttons to media volume, not ringer volume.
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        // Android 13+ requires this to actually show the playback notification;
        // without it the foreground service still runs, it just won't be visible.
        if (Build.VERSION.SDK_INT >= 33 &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIF_PERMISSION_REQUEST);
        }

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        // Lets the page's own console.log/warn/error calls show up in Logcat
        // (tag "RadioTunerJS"), so a failed stream connection on a real device
        // can actually be diagnosed instead of just showing a generic
        // "couldn't play" message with no detail behind it.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d("RadioTunerJS", cm.message() + " [" + cm.sourceId() + ":" + cm.lineNumber() + "]");
                return true;
            }
        });

        // Enables chrome://inspect on a desktop Chrome browser (connect the
        // phone over USB with Developer Options > USB debugging on) to see
        // this WebView's real Network tab — actual HTTP status codes,
        // TLS/DNS failures, blocked requests, etc. Only in debug builds.
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webView.addJavascriptInterface(new PlaybackBridge(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");
    }

    /** Runs a JS snippet in the page, e.g. a command forwarded from a notification/lock-screen tap. */
    public void runJs(String js) {
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    /** Media volume as 0..1 (current step / max steps). */
    private float mediaVolume() {
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max > 0 ? (float) audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / max : 0f;
    }

    private void pushVolumeToPage() {
        runJs("window.NativeControls && window.NativeControls.onVolumeChanged && "
                + "window.NativeControls.onVolumeChanged(" + mediaVolume() + ");");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The player sends "playing", song titles etc. to MainActivity.instance.
        // Make sure that's the screen actually showing, or the page is left
        // saying "Connecting…" while the radio plays.
        instance = this;
        getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver);
        pushVolumeToPage(); // it may have changed while the app was in the background
        // The station may have changed while the app was in the background -
        // most often in Android Auto, or from the widget. Move the dial to it
        // (without playing) so the app doesn't show a station from earlier.
        String name = RadioLibraryService.carStationName();
        if (name.isEmpty()) {
            RadioLibraryService.handBackToPhone();
            name = getSharedPreferences("RadioPrefs", MODE_PRIVATE).getString("last_station_title", "");
        }
        if (!name.isEmpty()) {
            runJs("window.NativeControls && window.NativeControls.onAppResume && "
                    + "window.NativeControls.onAppResume(" + org.json.JSONObject.quote(name) + ");");
        }
    }

    @Override
    protected void onPause() {
        getContentResolver().unregisterContentObserver(volumeObserver);
        super.onPause();
    }

    // Backup for phones that don't report volume changes to the observer:
    // after a volume-button press, re-read the volume and update the slider.
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean handled = super.dispatchKeyEvent(event);
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP
                && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            mainHandler.postDelayed(this::pushVolumeToPage, 150);
        }
        return handled;
    }

    /** Called from the page's own JS to actually drive playback natively. */
    private class PlaybackBridge {
        /** Hidden: long-press the title in the page. Shares the diagnostic log as a text file. */
        @JavascriptInterface
        public void shareLog() {
            runOnUiThread(() -> {
                try {
                    java.io.File f = DiagLog.export(MainActivity.this);
                    Uri uri = androidx.core.content.FileProvider.getUriForFile(
                            MainActivity.this, "com.billybobs.radiotuner.fileprovider", f);
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_SUBJECT, "Radio Wavehopper log");
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Share radio log"));
                } catch (Throwable t) {
                    Log.e("MainActivity", "shareLog failed", t);
                }
            });
        }

        /**
         * The page asking us to play a station by frequency — this is now
         * the only way audio actually starts on the phone. PlaybackService
         * looks the frequency up in the shared StationLibrary itself (not
         * trusting a URL from JS) and plays it through its own ExoPlayer.
         */
        @JavascriptInterface
        public void playStation(double freq, String name) {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, PlaybackService.class);
                i.setAction(PlaybackService.ACTION_PLAY_STATION);
                i.putExtra(PlaybackService.EXTRA_FREQ, freq);
                i.putExtra(PlaybackService.EXTRA_TITLE, name);
                startForegroundService(i);
            });
        }

        /** The saved user stations as the page's JSON ("" if none). */
        @JavascriptInterface
        public String getUserStations() {
            return UserStations.raw(MainActivity.this);
        }

        /** Save the page's user-station JSON; the phone player and widgets pick it up straight away. */
        @JavascriptInterface
        public boolean saveUserStations(String json) {
            return UserStations.save(MainActivity.this, json);
        }

        /** Edit screen: share the backup file (the page builds the JSON) through the share sheet. */
        @JavascriptInterface
        public void shareBackup(String json) {
            runOnUiThread(() -> {
                try {
                    java.io.File dir = new java.io.File(getCacheDir(), "shared");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    java.io.File f = new java.io.File(dir, "radiowavehopper-backup.json");
                    try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                            new java.io.FileOutputStream(f), java.nio.charset.StandardCharsets.UTF_8)) {
                        w.write(json);
                    }
                    Uri uri = androidx.core.content.FileProvider.getUriForFile(
                            MainActivity.this, "com.billybobs.radiotuner.fileprovider", f);
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("application/json");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_SUBJECT, "Radio Wavehopper backup");
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Save or send backup"));
                } catch (Throwable t) {
                    Log.e("MainActivity", "shareBackup failed", t);
                    runJs("window.__whBackupError&&window.__whBackupError('Could not create the backup file.')");
                }
            });
        }

        /** Edit screen: save the backup straight to the phone (Files / Downloads) with the system save dialog. */
        @JavascriptInterface
        public void saveBackup(String json) {
            pendingBackupJson = json;
            runOnUiThread(() -> {
                try {
                    Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    save.setType("application/json");
                    save.addCategory(Intent.CATEGORY_OPENABLE);
                    save.putExtra(Intent.EXTRA_TITLE, "radiowavehopper-backup.json");
                    startActivityForResult(save, REQ_SAVE_BACKUP);
                } catch (Throwable t) {
                    pendingBackupJson = null;
                    runJs("window.__whBackupDone&&window.__whBackupDone(false,'Could not open the save dialog.')");
                }
            });
        }

        /** Edit screen: choose a backup file. The text comes back through __whImportResult. */
        @JavascriptInterface
        public void pickBackup() {
            runOnUiThread(() -> {
                try {
                    Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                    pick.setType("*/*");
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(pick, "Choose a backup file"), REQ_PICK_BACKUP);
                } catch (Throwable t) {
                    runJs("window.__whImportResult&&window.__whImportResult(false,'Could not open the file picker.')");
                }
            });
        }

        /** Edit screen: choose a logo image from the phone. The result comes back to the page via __whLogoResult. */
        @JavascriptInterface
        public void pickLogo() {
            runOnUiThread(() -> {
                try {
                    Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                    pick.setType("image/*");
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(pick, "Choose a logo"), REQ_PICK_LOGO);
                } catch (Throwable t) {
                    runJs("window.__whLogoResult&&window.__whLogoResult(false,'Could not open the picker.')");
                }
            });
        }

        /** Edit screen: look for a logo on a station's website. Result via __whLogoResult. */
        @JavascriptInterface
        public void fetchLogo(String site) {
            final String s0 = site == null ? "" : site.trim();
            new Thread(() -> {
                String js;
                try {
                    String s = s0.matches("(?i)^https?://.*") ? s0 : "https://" + s0;
                    android.graphics.Bitmap b = s0.isEmpty() || s0.contains(" ") ? null : LogoFetcher.findLogo(s);
                    js = b == null
                            ? "window.__whLogoResult&&window.__whLogoResult(false,'No logo found at that address. Try another page, or upload one.')"
                            : "window.__whLogoResult&&window.__whLogoResult(true,'" + UserLogos.toDataUrl(UserLogos.prepare(b)) + "')";
                } catch (Throwable t) {
                    js = "window.__whLogoResult&&window.__whLogoResult(false,'Could not fetch a logo.')";
                }
                runJs(js);
            }, "FetchLogo").start();
        }

        @JavascriptInterface
        public boolean saveUserLogo(String name, String dataUrl) {
            return UserLogos.saveDataUrl(MainActivity.this, name, dataUrl);
        }

        @JavascriptInterface
        public String getUserLogo(String name) {
            return UserLogos.getDataUrl(MainActivity.this, name);
        }

        /** Shared with Android Auto: the last station tuned in each band. */
        @JavascriptInterface
        public String getBandMemory() {
            return BandMemory.toJson(MainActivity.this);
        }

        @JavascriptInterface
        public void rememberBand(String band, String name) {
            BandMemory.remember(MainActivity.this, band, name);
        }

        @JavascriptInterface
        public void renameBandStation(String oldName, String newName) {
            BandMemory.rename(MainActivity.this, oldName, newName);
        }

        @JavascriptInterface
        public void clearUserLogo(String name) {
            UserLogos.remove(MainActivity.this, name);
        }

        @JavascriptInterface
        public void renameUserLogo(String oldName, String newName) {
            UserLogos.rename(MainActivity.this, oldName, newName);
        }

        /** Edit screen: try a streaming URL before adding it. Pause with pauseStation(). */
        @JavascriptInterface
        public void testStream(String url, String name) {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, PlaybackService.class);
                i.setAction(PlaybackService.ACTION_PLAY_STATION);
                i.putExtra(PlaybackService.EXTRA_FREQ, -1.0);
                i.putExtra(PlaybackService.EXTRA_TITLE, name);
                i.putExtra(PlaybackService.EXTRA_TEST_URL, url);
                startForegroundService(i);
            });
        }

        /** The page's own pause button (not a lock-screen tap, which goes through MediaSession.Callback directly). */
        @JavascriptInterface
        public void pauseStation() {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, PlaybackService.class);
                i.setAction(PlaybackService.ACTION_PAUSE_STATION);
                startForegroundService(i);
            });
        }

        @JavascriptInterface
        public void onTrackChanged(String artist, String title) {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, PlaybackService.class);
                i.setAction(PlaybackService.ACTION_TRACK_CHANGED);
                i.putExtra(PlaybackService.EXTRA_ARTIST, artist);
                i.putExtra(PlaybackService.EXTRA_TITLE, title);
                startForegroundService(i);
            });
        }

        @JavascriptInterface
        public void identifySong() {
            runOnUiThread(() -> {
                try {
                    Intent intent = getPackageManager().getLaunchIntentForPackage("com.shazam.android");
                    if (intent == null) {
                        intent = getPackageManager().getLaunchIntentForPackage("com.shazam.encore.android");
                    }
                    if (intent == null) {
                        intent = new Intent(Intent.ACTION_VIEW, Uri.parse("shazam://"));
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    try {
                        Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.shazam.com"));
                        webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(webIntent);
                    } catch (Exception ex) {
                        // ignore
                    }
                }
            });
        }

        /** The phone's media volume as 0..1, for the page's slider. */
        @JavascriptInterface
        public float getVolume() {
            return mediaVolume();
        }

        /** The page's slider setting the phone's media volume (0..1). */
        @JavascriptInterface
        public void setVolume(float level) {
            runOnUiThread(() -> {
                int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int step = Math.round(Math.max(0f, Math.min(1f, level)) * max);
                if (step != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0);
                }
            });
        }

        /** Sleep timer from the page: minutes until playback stops (0 = off). */
        @JavascriptInterface
        public void setSleepTimer(int minutes) {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, PlaybackService.class);
                i.setAction(PlaybackService.ACTION_SET_SLEEP_TIMER);
                i.putExtra(PlaybackService.EXTRA_MINUTES, minutes);
                // The app is on screen (the user is holding the badge), so a
                // plain start is allowed and doesn't need a notification.
                startService(i);
            });
        }

        /** When the sleep timer ends (epoch ms), or 0 if none - for reopening the page. */
        @JavascriptInterface
        public long getSleepTimerEnd() {
            return getSharedPreferences("RadioPrefs", MODE_PRIVATE).getLong("sleep_end_at", 0L);
        }

        @JavascriptInterface
        public String getSavedStation() {
            // With Android Auto connected, open on what the car is playing.
            String car = RadioLibraryService.carStationName();
            if (!car.isEmpty()) return car;
            RadioLibraryService.handBackToPhone(); // left the car: phone shows the car's last station
            return getSharedPreferences("RadioPrefs", MODE_PRIVATE).getString("last_station_title", "");
        }

        @JavascriptInterface
        public boolean isPlaying() {
            // With Android Auto connected, the car's player is the one that
            // counts. (The page re-plays a "playing" station when it opens;
            // that's handed to the car, which leaves it alone if it's
            // already on it - so opening the app never restarts or changes
            // what the car is playing, and never starts a second player.)
            if (RadioLibraryService.carConnected()) return RadioLibraryService.carIsPlaying();
            return getSharedPreferences("RadioPrefs", MODE_PRIVATE).getBoolean("is_playing", false);
        }
    }

    private static final int REQ_PICK_LOGO = 4711;
    private static final int REQ_PICK_BACKUP = 4712;
    private static final int REQ_SAVE_BACKUP = 4713;
    private volatile String pendingBackupJson;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SAVE_BACKUP) {
            final String json = pendingBackupJson;
            pendingBackupJson = null;
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null || json == null) {
                runJs("window.__whBackupDone&&window.__whBackupDone(false,'')");
                return;
            }
            final Uri dest = data.getData();
            new Thread(() -> {
                String js;
                try (java.io.OutputStream out = getContentResolver().openOutputStream(dest, "wt")) {
                    if (out == null) throw new java.io.IOException("no stream");
                    out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    js = "window.__whBackupDone&&window.__whBackupDone(true,'Backup saved.')";
                } catch (Throwable t) {
                    js = "window.__whBackupDone&&window.__whBackupDone(false,'Could not save the backup there.')";
                }
                runJs(js);
            }, "SaveBackup").start();
            return;
        }
        if (requestCode == REQ_PICK_BACKUP) {
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                runJs("window.__whImportResult&&window.__whImportResult(false,'')");
                return;
            }
            final Uri backupUri = data.getData();
            new Thread(() -> {
                String js;
                try (java.io.InputStream in = getContentResolver().openInputStream(backupUri)) {
                    if (in == null) throw new java.io.IOException("no stream");
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[32 * 1024];
                    int n, total = 0;
                    while ((n = in.read(chunk)) > 0) {
                        total += n;
                        if (total > 25 * 1024 * 1024) throw new java.io.IOException("too big");
                        buf.write(chunk, 0, n);
                    }
                    String text = new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                    js = "window.__whImportResult&&window.__whImportResult(true," + org.json.JSONObject.quote(text) + ")";
                } catch (Throwable t) {
                    js = "window.__whImportResult&&window.__whImportResult(false,'That file could not be read.')";
                }
                runJs(js);
            }, "PickBackup").start();
            return;
        }
        if (requestCode != REQ_PICK_LOGO) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            runJs("window.__whLogoResult&&window.__whLogoResult(false,'')");
            return;
        }
        final Uri uri = data.getData();
        new Thread(() -> {
            android.graphics.Bitmap b = UserLogos.decodePicked(MainActivity.this, uri);
            runJs(b == null
                    ? "window.__whLogoResult&&window.__whLogoResult(false,'That file could not be read as an image.')"
                    : "window.__whLogoResult&&window.__whLogoResult(true,'" + UserLogos.toDataUrl(UserLogos.prepare(b)) + "')");
        }, "PickLogo").start();
    }

    @Override
    public void onBackPressed() {
        // The Edit screen is a layer over the radio: Back closes it first.
        webView.evaluateJavascript("(window.__whEditOpen&&window.__whEditOpen())?(window.__whEditClose(),'1'):'0'", value -> {
            if (value != null && value.contains("1")) return;
            if (webView.canGoBack()) {
                webView.goBack();
            } else {
                super.onBackPressed();
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
