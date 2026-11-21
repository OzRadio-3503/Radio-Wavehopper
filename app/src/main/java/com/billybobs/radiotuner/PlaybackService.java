package com.billybobs.radiotuner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;
import androidx.annotation.NonNull;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.mp3.Mp3Extractor;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.ResolvingDataSource;

import okhttp3.OkHttpClient;

/**
 * Foreground service that:
 *  1) actually plays the audio on the phone — via its own ExoPlayer — and
 *  2) exposes a real MediaSession so the lock screen / notification shade /
 *     Bluetooth headset buttons get proper play, pause, next and previous
 *     station controls.
 *
 * This service runs the classic, pre-Media3 ExoPlayer (com.google.android.
 * exoplayer:exoplayer-core:2.18.2), not the newer androidx.media3 artifacts
 * RadioLibraryService (Android Auto) uses. That's deliberate: a handful of
 * ad-stitched commercial relays (Power 100, 3PBS, 2GB) play a few seconds of
 * real audio then go permanently silent partway through, every time, in this
 * app specifically - but play fine start-to-finish in a browser/WebView and
 * in another ExoPlayer-based app (RadioDroid) that happens to run this exact
 * older ExoPlayer version. Everything tried at the Media3 level (OkHttp
 * networking, MIME hints, a stall-watchdog auto-reconnect) failed to fix it,
 * so this tests whether something changed in Media3's own audio pipeline -
 * vs. this known-working older version - is the actual cause. Android Auto
 * can't make the same jump: MediaLibraryService/MediaLibrarySession, which
 * Auto's browsing needs, only exists in the newer androidx.media3 artifacts.
 *
 * Why this exists, instead of letting the WebView's own HTML5 <audio>
 * element play the stream (as earlier versions of this app did): nothing
 * this app tried made that element participate correctly in Android's
 * audio focus system. Requesting focus natively ourselves, even carefully
 * de-duplicated, caused the WebView to pause its own playback within a
 * second or two ("connects, plays, then stops") — some competing focus
 * request was already in play that this app's own code couldn't safely
 * coexist with. Removing all native focus code fixed that self-pause, but
 * then exposed the mirror-image problem: with nothing in this app ever
 * entering the focus system, Android had no reason to ever tell it that
 * another app (Pocket Casts, ABC Listen, etc.) had started playing, so the
 * lock screen kept reporting Billy Bob's as active indefinitely.
 *
 * ExoPlayer with handleAudioFocus(true) (below, in onCreate) is a complete,
 * already-proven way around both problems at once — it's the exact same
 * mechanism RadioLibraryService already relies on for the car, where this
 * never came up. The WebView's own <audio> element is no longer used for
 * actual sound at all inside the app (the page's hls.js/dash.js/ICY-reading
 * machinery stays intact and in use only on the plain website); the page is
 * now purely the UI, and tells this service which station to play by
 * frequency. Everything audible happens here.
 */
public class PlaybackService extends Service {

    private static final String TAG = "PlaybackService";

    public static final String ACTION_PLAY = "com.billybobs.radiotuner.action.PLAY";
    public static final String ACTION_PAUSE = "com.billybobs.radiotuner.action.PAUSE";
    public static final String ACTION_NEXT = "com.billybobs.radiotuner.action.NEXT";
    public static final String ACTION_PREV = "com.billybobs.radiotuner.action.PREV";
    public static final String ACTION_TRACK_CHANGED = "com.billybobs.radiotuner.action.TRACK_CHANGED";
    public static final String ACTION_PLAY_STATION = "com.billybobs.radiotuner.action.PLAY_STATION";
    public static final String ACTION_PAUSE_STATION = "com.billybobs.radiotuner.action.PAUSE_STATION";
    public static final String ACTION_TOGGLE = "com.billybobs.radiotuner.action.TOGGLE";
    public static final String ACTION_WIDGET_NEXT = "com.billybobs.radiotuner.action.WIDGET_NEXT";
    public static final String ACTION_WIDGET_PREV = "com.billybobs.radiotuner.action.WIDGET_PREV";
    public static final String ACTION_STOP = "com.billybobs.radiotuner.action.STOP";
    public static final String ACTION_SET_SLEEP_TIMER = "com.billybobs.radiotuner.action.SET_SLEEP_TIMER";
    public static final String EXTRA_MINUTES = "extra_minutes";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_ARTIST = "extra_artist";
    public static final String EXTRA_FREQ = "extra_freq";
    /** Edit screen "test" button: play this one URL without it being a saved station. */
    public static final String EXTRA_TEST_URL = "extra_test_url";

    private static final String CHANNEL_ID = "radio_playback";
    private static final int NOTIF_ID = 1;
    private static final long MAX_WAKE_LOCK_MS = 12 * 60 * 60 * 1000L;

    // Stall watchdog: some relays (confirmed via logcat on StreamTheWorld's
    // ad-stitched stations - Power 100, 3PBS, 2GB) change the audio format
    // mid-stream at the ad splice point. The native AudioTrack gets stopped
    // ("stop(): called with N frames delivered" in logcat) and never
    // restarted, and with no error raised the UI keeps showing "playing".
    // Polling playback position is how we notice: real audio advances
    // position every poll; a genuinely stuck player doesn't. (See
    // checkForStall for why this gates on playWhenReady, not isPlaying.)
    private static final long STALL_POLL_INTERVAL_MS = 2000L;
    private static final int STALL_POLLS_BEFORE_RECONNECT = 3; // ~6s of no progress
    private static final long STALL_RECONNECT_COOLDOWN_MS = 8000L;

    // A live radio stream should never "end". When ExoPlayer reaches
    // STATE_ENDED on one, the server has closed the HTTP connection (the
    // logcat's "AudioTrack stop(): called with N frames delivered" is
    // ExoPlayer draining the track at end-of-stream - a plain stall would be
    // a pause(), not a stop()). Browsers, VLC and MediaPlayer all quietly
    // reconnect when that happens; ExoPlayer doesn't, so we do it here.
    // Capped so a station that genuinely refuses us can't loop forever.
    private static final int MAX_ENDED_RECONNECTS_PER_WINDOW = 10;
    private static final long ENDED_RECONNECT_WINDOW_MS = 60_000L;
    private int endedReconnectCount = 0;
    private long endedReconnectWindowStartMs = 0;

    // Sleep timer (set by holding the RADIO WAVEHOPPER badge on the page). Lives
    // here, not in the page, because Android pauses the page's timers once
    // the screen is off - exactly when a sleep timer needs to fire. The last
    // 15 seconds fade the volume down instead of cutting off abruptly.
    private static final long SLEEP_FADE_MS = 15_000L;
    private static final long SLEEP_FADE_STEP_MS = 500L;
    private final Handler sleepHandler = new Handler(Looper.getMainLooper());
    private boolean sleepFading = false;
    private float sleepFadeVolume = 1f;
    private final Runnable sleepTimerFired = () -> {
        sleepFading = true;
        sleepFadeVolume = 1f;
        sleepHandler.post(this.sleepFadeTick);
    };
    private final Runnable sleepFadeTick = new Runnable() {
        @Override
        public void run() {
            sleepFadeVolume -= (float) SLEEP_FADE_STEP_MS / SLEEP_FADE_MS;
            if (sleepFadeVolume <= 0f) {
                finishSleepTimer();
                return;
            }
            setEngineVolume(sleepFadeVolume);
            sleepHandler.postDelayed(this, SLEEP_FADE_STEP_MS);
        }
    };

    /** True while this service exists - the sleep timer only ever runs in it. */
    static volatile boolean running = false;

    private PowerManager.WakeLock wakeLock;
    // Keeps Wi-Fi at full speed while the screen is off. Without it the
    // phone drops Wi-Fi into power-save once the screen goes dark, and a
    // stream from a distant server (KBRW 680 AM in Alaska, for one) can't
    // keep up: it runs dry, the stall watchdog reconnects, and it drops
    // again. Held and released together with the wake lock, so it covers
    // both ExoPlayer and the MediaPlayer path.
    private WifiManager.WifiLock wifiLock;
    private MediaSession mediaSession;
    private ExoPlayer player;
    private final LiveResume liveResume = new LiveResume();
    private int lastPlaybackState = Player.STATE_IDLE;

    // Fallback decode path for the handful of stations where ExoPlayer's
    // native AudioTrack reliably dies partway through (Station.forceMediaPlayer)
    // - see that field's comment for the full story. Only ever active for
    // those specific stations; every other station keeps using `player` above.
    private MediaPlayer legacyPlayer;
    private boolean usingLegacyPlayer = false;
    private boolean legacyPreparing = false;
    private AudioManager audioManager;
    private AudioFocusRequest legacyFocusRequest;
    private final AudioManager.OnAudioFocusChangeListener legacyFocusListener = focusChange -> {
        if ((focusChange == AudioManager.AUDIOFOCUS_LOSS
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                && usingLegacyPlayer && legacyPlayer != null && legacyPlayer.isPlaying()) {
            // Mirrors ExoPlayer's handleAudioFocus(true) elsewhere in this
            // app: pause and stay paused rather than trying to auto-resume,
            // which keeps the lock-screen/notification state simple and
            // avoids any risk of fighting another app for the speaker.
            legacyPlayer.pause();
            onLegacyPlayingChanged(false);
        }
    };

    private CushionLoadControl loadControl;

    /** How much audio a flagged station buffers before it first starts (normal is 2.5 s). */
    private static final long CUSHION_US = 5_000_000L;

    /**
     * Standard ExoPlayer buffering, except that a flagged station waits until about
     * 5 seconds are buffered before it first starts (the normal wait is 2.5 s), so a
     * brief network drop - the phone's Wi-Fi napping with the screen off - runs off
     * the cushion instead of going silent. Costs a slightly slower start, nothing once playing.
     */
    private static final class CushionLoadControl extends DefaultLoadControl {
        volatile boolean bigCushion = false;

        @Override
        public boolean shouldStartPlayback(long bufferedDurationUs, float playbackSpeed,
                                           boolean rebuffering, long targetLiveOffsetUs) {
            if (bigCushion && bufferedDurationUs < CUSHION_US) return false;
            return super.shouldStartPlayback(bufferedDurationUs, playbackSpeed, rebuffering, targetLiveOffsetUs);
        }
    }

    /** Stations that get the start-up cushion. */
    private static boolean wantsBigCushion(String stationName) {
        return "Station X".equals(stationName);
    }

    private String currentStationName = "Radio Wavehopper";
    private String currentArtist = "";
    private String currentTrack = "";
    private String currentStationUrl;
    private String currentStationMimeType;

    private final Handler stallWatchdogHandler = new Handler(Looper.getMainLooper());
    private long lastKnownPositionMs = -1;
    private int stallPollCount = 0;
    private long lastStallReconnectAtMs = 0;
    private final Runnable stallWatchdogTick = new Runnable() {
        @Override
        public void run() {
            checkForStall();
            stallWatchdogHandler.postDelayed(this, STALL_POLL_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        UserStations.ensure(this);
        running = true;
        DiagLog.init(this);
        DiagLog.log("=== service created ===");
        registerDiagReceivers();

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Radio playback", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Playback controls for Radio Wavehopper");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        // A lot of these internet-radio relays (live365 included) block
        // requests whose User-Agent doesn't look like a real browser or
        // player, as a basic anti-hotlinking measure - ExoPlayer's default
        // HTTP client sends a generic library UA, which several stations
        // reject outright with ERROR_CODE_IO_BAD_HTTP_STATUS (confirmed on
        // device: Pop 91.3 fails this way, even though the page's own
        // fetch() for ICY metadata succeeds against the exact same URL,
        // since that one goes out with the WebView's normal browser UA).
        //
        // The underlying HTTP client itself matters too: a handful of
        // commercial ad-stitched stations (StreamTheWorld-hosted relays like
        // Power 100, 3PBS, 2GB) play a few seconds of real audio then go
        // silent with no error, every time, right around where the ad splice
        // happens - but play fine start-to-finish in a browser/WebView AND
        // in another ExoPlayer-based radio app (RadioDroid) that uses OkHttp
        // instead of the default HttpURLConnection-based client, on the
        // classic (pre-Media3) ExoPlayer this service now also runs. OkHttp
        // here matches that combination exactly.
        OkHttpClient okHttpClient = new OkHttpClient.Builder().build();
        // No setUserAgent() on the factory: it would override the per-request
        // User-Agent below. StreamHeaders decides it per host instead.
        OkHttpDataSource.Factory httpDataSourceFactory = new OkHttpDataSource.Factory(okHttpClient);
        ResolvingDataSource.Factory userAgentFactory = new ResolvingDataSource.Factory(
                httpDataSourceFactory,
                dataSpec -> dataSpec.withAdditionalHeaders(java.util.Collections.singletonMap(
                        "User-Agent", StreamHeaders.userAgentFor(dataSpec.uri))));
        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this, userAgentFactory);

        // Ad-stitching platforms like StreamTheWorld commonly splice a fresh
        // ID3 metadata block into the raw MP3 stream right at the ad-to-live
        // handoff (that's how they push "now playing"/ad-break info into the
        // audio bitstream itself). A browser's decoder just skips non-audio
        // data like that and keeps going; ExoPlayer's MP3 extractor parses
        // ID3 blocks, and one appearing mid-stream - rather than only at the
        // very start, where it's normally expected - is a plausible trigger
        // for the extractor to treat it as a discontinuity it can't resolve
        // cleanly, silently stalling the AudioTrack instead of raising an
        // error. Telling it to ignore ID3 metadata entirely means it only
        // ever sees audio frames, so a mid-stream block like that can't
        // confuse it. (Affects the MP3 extractor only - this app's other
        // stations use formats this flag doesn't touch.)
        DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory()
                .setMp3ExtractorFlags(Mp3Extractor.FLAG_DISABLE_ID3_METADATA);

        loadControl = new CushionLoadControl();
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
                .setLoadControl(loadControl)
                .build();
        // This is the whole fix: let ExoPlayer request and abandon Android
        // audio focus for us. It correctly becomes the active session when
        // we start playing, and correctly pauses itself (setting
        // playWhenReady to false) when some other app takes real focus —
        // which is exactly what onIsPlayingChanged below reacts to, to drop
        // the lock screen to Paused. This is the identical mechanism
        // RadioLibraryService already uses for Android Auto, where it has
        // always worked reliably.
        player.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                /* handleAudioFocus= */ true);
        // Pause when the audio output goes away - Bluetooth or the car
        // disconnecting, headphones unplugged - instead of carrying on out of
        // the phone's speaker (Android's "audio becoming noisy" event).
        // Pressing play afterwards comes back live (see LiveResume).
        player.setHandleAudioBecomingNoisy(true);

        player.addListener(new Player.Listener() {
            // Back to live after a pause - see LiveResume.
            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                DiagLog.log("playWhenReady=" + playWhenReady + " reason=" + reason);
                if (liveResume.onPlayWhenReadyChanged(playWhenReady)) jumpToLive();
            }

            @Override
            public void onPlaybackSuppressionReasonChanged(int suppressionReason) {
                DiagLog.log("suppression reason=" + suppressionReason + " (focus/other app)");
                if (liveResume.onSuppressionChanged(
                        suppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
                        player.getPlayWhenReady())) {
                    jumpToLive();
                }
                if (suppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                    // Silenced by another app (focus taken): don't leave the widget showing "playing".
                    getSharedPreferences("RadioPrefs", MODE_PRIVATE).edit()
                            .putBoolean("is_playing", false).apply();
                    RadioWidgetProvider.refresh(PlaybackService.this);
                }
            }

            @Override
            public void onMediaItemTransition(@androidx.annotation.Nullable MediaItem mediaItem, int reason) {
                liveResume.reset();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                DiagLog.log("isPlaying=" + isPlaying + " " + diagSnapshot());
                if (isPlaying) {
                    acquireWakeLock();
                    getSharedPreferences("RadioPrefs", MODE_PRIVATE).edit()
                            .putBoolean("is_playing", true)
                            .apply();
                    updateState(true);
                    sendJs("onNativePlaying");
                    startStallWatchdog();
                } else if (!player.getPlayWhenReady()
                        && player.getPlaybackState() != Player.STATE_BUFFERING) {
                    // playWhenReady is false here either because we (or the
                    // lock screen) explicitly paused, or because ExoPlayer's
                    // own focus handling just set it false in response to a
                    // real AUDIOFOCUS_LOSS (another app took over). Either
                    // way, we really have stopped, so reflect that properly
                    // — unlike a transient rebuffer, where playWhenReady
                    // stays true and we deliberately don't flicker the UI.
                    releaseWakeLock();
                    getSharedPreferences("RadioPrefs", MODE_PRIVATE).edit()
                            .putBoolean("is_playing", false)
                            .apply();
                    updateState(false);
                    sendJs("onNativePaused");
                    stopStallWatchdog();
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                DiagLog.log("state=" + diagStateName(playbackState) + " " + diagSnapshot());
                boolean wasIdle = lastPlaybackState == Player.STATE_IDLE;
                lastPlaybackState = playbackState;
                if (liveResume.onStateChanged(playbackState == Player.STATE_IDLE, wasIdle,
                        player.getPlayWhenReady())) {
                    jumpToLive();
                    return;
                }
                if (playbackState != Player.STATE_ENDED
                        || usingLegacyPlayer
                        || !player.getPlayWhenReady()
                        || currentStationUrl == null) {
                    return;
                }
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - endedReconnectWindowStartMs > ENDED_RECONNECT_WINDOW_MS) {
                    endedReconnectWindowStartMs = now;
                    endedReconnectCount = 0;
                }
                if (++endedReconnectCount > MAX_ENDED_RECONNECTS_PER_WINDOW) {
                    android.util.Log.e(TAG, "Live stream keeps ending immediately; giving up: "
                            + currentStationUrl);
                    releaseWakeLock();
                    updateState(false);
                    sendJsWithArg("onNativeError", "STREAM_ENDED_REPEATEDLY");
                    stopStallWatchdog();
                    return;
                }
                android.util.Log.w(TAG, "Live stream ended (server closed the connection) - "
                        + "reconnecting: " + currentStationUrl);
                DiagLog.log("STREAM ENDED by server, reconnecting");
                reconnectExoPlayer();
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                DiagLog.log("ERROR " + error.getErrorCodeName() + " - " + error.getMessage()
                        + " cause=" + error.getCause());
                // A live HLS station (SEN, ABC, BBC...) only keeps the last
                // stretch of audio on its server. If playback falls behind
                // that (a long stall, slow network, phone asleep), ExoPlayer
                // gives up with BEHIND_LIVE_WINDOW. The fix is simply to jump
                // back to the live point - the standard recovery.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
                        && behindLiveRecoveryAllowed()) {
                    android.util.Log.w(TAG, "Fell behind the live window - jumping back to live");
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                android.util.Log.e(TAG, "Playback error: "
                        + error.getErrorCodeName() + " - " + error.getMessage(), error);
                releaseWakeLock();
                updateState(false);
                sendJsWithArg("onNativeError", error.getErrorCodeName());
                stopStallWatchdog();
            }
        });

        mediaSession = new MediaSession(this, "RadioWavehopper");
        mediaSession.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { doPlay(); }
            @Override public void onPause() { doPause(); }
            @Override public void onSkipToNext() { doNext(); }
            @Override public void onSkipToPrevious() { doPrev(); }
        });
    }

    /** Resumes the currently-loaded station, if any. */
    private void doPlay() {
        if (sleepFading) {
            cancelSleepTimer();
            saveSleepEnd(0);
        }
        if (usingLegacyPlayer) {
            if (legacyPlayer != null && requestLegacyAudioFocus()) {
                legacyPlayer.start();
                onLegacyPlayingChanged(true);
            }
        } else if (player.getMediaItemCount() > 0) {
            player.play();
        }
    }

    private void doPause() {
        if (usingLegacyPlayer) {
            if (legacyPlayer != null && legacyPlayer.isPlaying()) {
                legacyPlayer.pause();
            }
            abandonLegacyAudioFocus();
            onLegacyPlayingChanged(false);
        } else {
            player.pause();
        }
    }

    /**
     * Next/previous station is still the page's own call — it owns the
     * dial-stepping logic (which frequency comes next) — so this just
     * forwards to JS, same as before, and JS tells us which station to
     * actually play via the playStation bridge call.
     */
    private void doNext() { sendJs("next"); }
    private void doPrev() { sendJs("prev"); }

    /**
     * Starts a station on the plain MediaPlayer fallback path (see
     * Station.forceMediaPlayer). Tears down any previous legacy playback
     * first, same as ExoPlayer's setMediaItem does implicitly for its path.
     */
    private void playWithLegacyMediaPlayer(String url) {
        stopLegacyPlaybackForEngineSwitch();
        usingLegacyPlayer = true;
        legacyPlayer = new MediaPlayer();
        legacyPlayer.setAudioAttributes(new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        legacyPlayer.setOnPreparedListener(mp -> {
            legacyPreparing = false;
            if (requestLegacyAudioFocus()) {
                mp.start();
                onLegacyPlayingChanged(true);
            }
        });
        legacyPlayer.setOnErrorListener((mp, what, extra) -> {
            legacyPreparing = false;
            android.util.Log.e(TAG, "Legacy MediaPlayer error on " + url + ": what=" + what + " extra=" + extra);
            onLegacyPlayingChanged(false);
            sendJsWithArg("onNativeError", "MEDIA_PLAYER_ERROR_" + what);
            return true; // we've handled it - don't let the framework also fire onCompletion
        });
        legacyPlayer.setOnCompletionListener(mp -> onLegacyPlayingChanged(false));
        try {
            legacyPlayer.setDataSource(url);
            legacyPreparing = true;
            legacyPlayer.prepareAsync();
        } catch (java.io.IOException e) {
            android.util.Log.e(TAG, "Legacy MediaPlayer setDataSource failed for " + url, e);
            onLegacyPlayingChanged(false);
        }
    }

    /** True if this station is already connecting or playing on its engine. */
    private boolean isAlreadyStarting(StationLibrary.Station station) {
        if (station.forceMediaPlayer) {
            return usingLegacyPlayer && legacyPlayer != null
                    && (legacyPreparing || legacyPlayer.isPlaying());
        }
        int state = player.getPlaybackState();
        return !usingLegacyPlayer && player.getPlayWhenReady()
                && (state == Player.STATE_BUFFERING || state == Player.STATE_READY);
    }

    /** Tears down the legacy player, if any, without touching ExoPlayer. */
    private void stopLegacyPlaybackForEngineSwitch() {
        stopStallWatchdog();
        if (legacyPlayer != null) {
            legacyPlayer.setOnPreparedListener(null);
            legacyPlayer.setOnErrorListener(null);
            legacyPlayer.setOnCompletionListener(null);
            // stop() isn't valid while still preparing (logcat: "stop called
            // in state 4"); release() is valid in any state and stops anyway.
            if (!legacyPreparing) {
                try {
                    legacyPlayer.stop();
                } catch (IllegalStateException ignored) {
                    // Already stopped - nothing to clean up here.
                }
            }
            legacyPlayer.release();
            legacyPlayer = null;
        }
        legacyPreparing = false;
        abandonLegacyAudioFocus();
        usingLegacyPlayer = false;
    }

    private boolean requestLegacyAudioFocus() {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        }
        android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        legacyFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(legacyFocusListener)
                .build();
        int result = audioManager.requestAudioFocus(legacyFocusRequest);
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonLegacyAudioFocus() {
        if (audioManager != null && legacyFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(legacyFocusRequest);
            legacyFocusRequest = null;
        }
    }

    /** Mirrors the ExoPlayer listener's onIsPlayingChanged handling, for the legacy path. */
    private void onLegacyPlayingChanged(boolean isPlaying) {
        if (isPlaying) {
            acquireWakeLock();
            getSharedPreferences("RadioPrefs", MODE_PRIVATE).edit()
                    .putBoolean("is_playing", true)
                    .apply();
            updateState(true);
            sendJs("onNativePlaying");
            startStallWatchdog();
        } else {
            releaseWakeLock();
            getSharedPreferences("RadioPrefs", MODE_PRIVATE).edit()
                    .putBoolean("is_playing", false)
                    .apply();
            updateState(false);
            sendJs("onNativePaused");
            stopStallWatchdog();
        }
    }

    private void setSleepTimer(int minutes) {
        cancelSleepTimer();
        if (minutes <= 0) {
            saveSleepEnd(0);
            return;
        }
        long total = minutes * 60_000L;
        sleepHandler.postDelayed(sleepTimerFired, Math.max(0, total - SLEEP_FADE_MS));
        saveSleepEnd(System.currentTimeMillis() + total);
    }

    /** Stops any pending timer or fade in progress, restoring full volume. */
    private void cancelSleepTimer() {
        sleepHandler.removeCallbacks(sleepTimerFired);
        sleepHandler.removeCallbacks(sleepFadeTick);
        if (sleepFading) {
            sleepFading = false;
            setEngineVolume(1f);
        }
    }

    private void finishSleepTimer() {
        sleepFading = false;
        doPause();
        setEngineVolume(1f); // ready for the next time play is pressed
        saveSleepEnd(0);
        if (MainActivity.instance != null) {
            MainActivity.instance.runJs(
                    "window.NativeControls && window.NativeControls.onSleepTimerFinished && "
                            + "window.NativeControls.onSleepTimerFinished();");
        }
    }

    /** When the timer ends (epoch ms, 0 = off), so a reopened page can show it. */
    private void saveSleepEnd(long endAtMs) {
        getSharedPreferences("RadioPrefs", MODE_PRIVATE).edit()
                .putLong("sleep_end_at", endAtMs).apply();
    }

    private void setEngineVolume(float volume) {
        if (player != null) player.setVolume(volume);
        if (legacyPlayer != null) legacyPlayer.setVolume(volume, volume);
    }

    private void startStallWatchdog() {
        lastKnownPositionMs = -1;
        stallPollCount = 0;
        stallWatchdogHandler.removeCallbacks(stallWatchdogTick);
        stallWatchdogHandler.postDelayed(stallWatchdogTick, STALL_POLL_INTERVAL_MS);
    }

    private void stopStallWatchdog() {
        stallWatchdogHandler.removeCallbacks(stallWatchdogTick);
        lastKnownPositionMs = -1;
        stallPollCount = 0;
    }

    private void checkForStall() {
        long position;
        if (usingLegacyPlayer) {
            if (legacyPlayer == null || !legacyPlayer.isPlaying()) return;
            position = legacyPlayer.getCurrentPosition();
        } else {
            // Gate on playWhenReady ("the listener wants audio"), NOT
            // isPlaying(): when a stream dies, ExoPlayer drops to BUFFERING or
            // ENDED, isPlaying() goes false and playWhenReady stays true. The
            // old isPlaying() guard made this watchdog bail out at exactly
            // the moment it was needed, which is why it never fired.
            if (player == null || !player.getPlayWhenReady()) return;
            // A phone call / nav prompt (transient focus loss) also holds
            // playback with playWhenReady still true - that's not a stall,
            // and reconnecting mid-call would be wrong.
            if (player.getPlaybackSuppressionReason() != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                stallPollCount = 0;
                return;
            }
            position = player.getCurrentPosition();
        }

        if (lastKnownPositionMs >= 0 && position - lastKnownPositionMs < 300) {
            // Less than 300ms of progress over a ~2s poll interval while
            // the active engine insists it's playing - this is the
            // AudioTrack-got-stopped-and-never-restarted scenario confirmed
            // in logcat on the ad-stitched stations, not a normal transient
            // hiccup. (Kept on the MediaPlayer path too, as a safety net, in
            // case the same server-side splice confuses that decoder too.)
            stallPollCount++;
        } else {
            stallPollCount = 0;
        }
        lastKnownPositionMs = position;

        if (stallPollCount >= STALL_POLLS_BEFORE_RECONNECT) {
            stallPollCount = 0;
            long now = android.os.SystemClock.elapsedRealtime();
            if (currentStationUrl != null && now - lastStallReconnectAtMs > STALL_RECONNECT_COOLDOWN_MS) {
                lastStallReconnectAtMs = now;
                android.util.Log.w(TAG, "Detected a stalled stream (no position progress while "
                        + "\"playing\") - reconnecting: " + currentStationUrl);
                DiagLog.log("STALL watchdog reconnect " + diagSnapshot());
                if (usingLegacyPlayer) {
                    playWithLegacyMediaPlayer(currentStationUrl);
                } else {
                    reconnectExoPlayer();
                }
                lastKnownPositionMs = -1;
            }
        }
    }

    private long behindLiveWindowStartMs = 0;
    private int behindLiveCount = 0;

    /** At most 5 behind-live-window recoveries a minute, so it can't loop forever. */
    private boolean behindLiveRecoveryAllowed() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - behindLiveWindowStartMs > 60_000) {
            behindLiveWindowStartMs = now;
            behindLiveCount = 0;
        }
        return ++behindLiveCount <= 5;
    }

    /** Drops the audio held over from a pause and goes back to live. */
    private void jumpToLive() {
        if (usingLegacyPlayer || currentStationUrl == null) return;
        android.util.Log.i(TAG, "Resumed after a pause - reconnecting to live");
        DiagLog.log("resume-to-live reconnect");
        // A brand-new connection, not just a seek: a plain Icecast stream left
        // paused can come back with a long backlog of old audio queued up.
        // setMediaItem keeps playWhenReady, so it carries on playing.
        MediaItem.Builder builder = new MediaItem.Builder().setUri(currentStationUrl);
        if (currentStationMimeType != null) builder.setMimeType(currentStationMimeType);
        player.setMediaItem(builder.build());
        player.prepare();
        lastKnownPositionMs = -1;
        stallPollCount = 0;
    }

    /** Fresh connection to the current station on ExoPlayer. */
    private void reconnectExoPlayer() {
        MediaItem.Builder builder = new MediaItem.Builder().setUri(currentStationUrl);
        if (currentStationMimeType != null) builder.setMimeType(currentStationMimeType);
        player.setMediaItem(builder.build());
        player.prepare();
        player.play();
        lastKnownPositionMs = -1;
        stallPollCount = 0;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null && intent.getAction() != null) ? intent.getAction() : ACTION_PLAY_STATION;

        // While Android Auto is connected, the car's player (RadioLibraryService)
        // does the playing, so the car screen and its buttons control what you
        // hear. The phone's play/pause/widget buttons are handed to it.
        if (!RadioLibraryService.carConnected()) {
            // Left the car with its player still loaded: stop it and carry its
            // station over to the phone, so there's never two players or a
            // mismatched widget/app.
            RadioLibraryService.handBackToPhone();
        } else {
            switch (action) {
                case ACTION_PLAY:
                    if (RadioLibraryService.resumeFromPhone()) { releasePhoneForCar(true); return START_STICKY; }
                    break;
                case ACTION_TOGGLE:
                    if (RadioLibraryService.carIsPlaying() ? RadioLibraryService.pauseFromPhone()
                            : RadioLibraryService.resumeFromPhone()) {
                        releasePhoneForCar(RadioLibraryService.carIsPlaying());
                        return START_STICKY;
                    }
                    break;
                case ACTION_PAUSE:
                case ACTION_PAUSE_STATION:
                    if (RadioLibraryService.pauseFromPhone()) { releasePhoneForCar(false); return START_STICKY; }
                    break;
                default:
                    break;
            }
        }

        switch (action) {
            // Notification action buttons (these arrive as a real Intent,
            // unlike the lock screen / Bluetooth transport controls, which
            // call straight into the MediaSession.Callback above).
            case ACTION_PLAY:
                ensureForeground(); // the home-screen widget starts us as a foreground service
                if (!hasLoadedStation() && playSavedStation()) return START_STICKY;
                doPlay();
                return START_STICKY;
            case ACTION_PAUSE:
                doPause();
                RadioWidgetProvider.refresh(this); // also fixes a widget left showing "playing"
                return START_STICKY;
            case ACTION_TOGGLE:
                // Widget play/pause button: decided here from what the player is really doing,
                // not from what the widget last showed (another app may have taken the audio).
                ensureForeground();
                if (isReallyPlaying()) {
                    doPause();
                    RadioWidgetProvider.refresh(this);
                } else if (!hasLoadedStation()) {
                    if (!playSavedStation()) { stopForeground(true); stopSelf(); }
                } else {
                    resumeFromScratch();
                }
                return START_STICKY;
            case ACTION_WIDGET_NEXT: ensureForeground(); widgetStep(1);  return START_STICKY;
            case ACTION_WIDGET_PREV: ensureForeground(); widgetStep(-1); return START_STICKY;
            case ACTION_NEXT:  doNext();  return START_STICKY;
            case ACTION_PREV:  doPrev();  return START_STICKY;

            case ACTION_TRACK_CHANGED:
                currentArtist = intent.getStringExtra(EXTRA_ARTIST);
                currentTrack = intent.getStringExtra(EXTRA_TITLE);
                updateState(usingLegacyPlayer
                        ? (legacyPlayer != null && legacyPlayer.isPlaying())
                        : player.isPlaying());
                return START_STICKY;

            // The page telling us which station to actually play.
            case ACTION_PLAY_STATION: {
                double freq = intent.getDoubleExtra(EXTRA_FREQ, -1);
                String title = intent.getStringExtra(EXTRA_TITLE);
                String testUrl = intent.getStringExtra(EXTRA_TEST_URL);
                StationLibrary.Station station;
                if (testUrl != null && (testUrl.startsWith("http://") || testUrl.startsWith("https://"))) {
                    station = new StationLibrary.Station(freq, title == null || title.isEmpty() ? "Test stream" : title,
                            "Test", testUrl, null, false, "");
                } else {
                    station = null;
                    if (title != null && !title.isEmpty()) {   // by name first: dial spots can move
                        for (StationLibrary.Station c : StationLibrary.getAllStations()) {
                            if (title.equals(c.name)) { station = c; break; }
                        }
                    }
                    if (station == null) station = findNearestStation(freq);
                }
                if (testUrl == null && station != null
                        && RadioLibraryService.playFromPhone(station.name, station.freq)) {
                    // Android Auto is connected: the car's player plays it.
                    // (Testing a stream from the station editor still plays
                    // on the phone.)
                    currentStationName = station.name;
                    releasePhoneForCar(true);
                    return START_STICKY;
                }
                if (station != null && station.url.equals(currentStationUrl) && isAlreadyStarting(station)) {
                    // The page asked for the station that's already
                    // connecting/playing (logcat showed this happening).
                    // Opening a second connection is wasteful, and Triton
                    // explicitly asks players to avoid "double play".
                    return START_STICKY;
                }
                if (station != null) {
                    DiagLog.log("PLAY " + station.name + " " + station.url);
                    currentStationName = (title != null && !title.isEmpty()) ? title : station.name;
                    currentArtist = "";
                    currentTrack = "";
                    currentStationUrl = station.url;
                    currentStationMimeType = station.mimeType;
                    loadControl.bigCushion = wantsBigCushion(station.name);
                    endedReconnectCount = 0;
                    endedReconnectWindowStartMs = android.os.SystemClock.elapsedRealtime();
                    if (station.forceMediaPlayer) {
                        player.stop();
                        playWithLegacyMediaPlayer(station.url);
                    } else {
                        stopLegacyPlaybackForEngineSwitch();
                        MediaItem.Builder mediaItemBuilder = new MediaItem.Builder().setUri(station.url);
                        if (station.mimeType != null) mediaItemBuilder.setMimeType(station.mimeType);
                        try {
                            player.setMediaItem(mediaItemBuilder.build());
                            player.prepare();
                            player.play();
                        } catch (RuntimeException e) {
                            // One unplayable station must never take the whole
                            // app down - report it like any playback error.
                            android.util.Log.e(TAG, "Couldn't start " + station.url, e);
                            releaseWakeLock();
                            updateState(false);
                            sendJsWithArg("onNativeError", "UNSUPPORTED_STREAM");
                            return START_STICKY;
                        }
                    }
                    acquireWakeLock();
                    // A test stream must not become the "last station" the app reopens on.
                    if (testUrl == null) getSharedPreferences("RadioPrefs", MODE_PRIVATE).edit()
                            .putString("last_station_title", currentStationName)
                            .putString("last_station_sub", station.real
                                    + (station.region.isEmpty() ? "" : " \u00b7 " + station.region))
                            .putLong("last_station_freq_bits", Double.doubleToLongBits(station.freq))
                            // When this was played - so Android Auto can tell whether
                            // the phone or the car was played more recently, and
                            // resume whichever is newer. See RadioLibraryService.
                            .putLong("last_station_at", System.currentTimeMillis())
                            .putBoolean("is_playing", true)
                            .apply();
                    // Also the last station in its band, shared with Android
                    // Auto's band button - covers the widget and notification
                    // too, when the app's page isn't open.
                    if (testUrl == null) {
                        BandMemory.remember(this, StationLibrary.realBand(station), station.name);
                    }
                    updateState(true);
                }
                return START_STICKY;
            }

            case ACTION_SET_SLEEP_TIMER:
                setSleepTimer(intent.getIntExtra(EXTRA_MINUTES, 0));
                return START_STICKY;

            case ACTION_PAUSE_STATION:
                doPause();
                return START_STICKY;

            case ACTION_STOP:
                releaseWakeLock();
                stopStallWatchdog();
                stopLegacyPlaybackForEngineSwitch();
                player.stop();
                if (mediaSession != null) {
                    mediaSession.setActive(false);
                }
                stopForeground(true);
                stopSelf();
                getSharedPreferences("RadioPrefs", MODE_PRIVATE).edit()
                        .putBoolean("is_playing", false)
                        .apply();
                RadioWidgetProvider.refresh(this);
                return START_NOT_STICKY;

            default:
                return START_STICKY;
        }
    }

    /** The nearest station to this frequency — same tolerance approach as the page's own dial. */
    private StationLibrary.Station findNearestStation(double freq) {
        StationLibrary.Station best = null;
        double bestDist = Double.MAX_VALUE;
        for (StationLibrary.Station s : StationLibrary.getAllStations()) {
            double d = Math.abs(s.freq - freq);
            if (d < bestDist) {
                bestDist = d;
                best = s;
            }
        }
        return best;
    }

    /**
     * A play/pause was handed to the car's player (Android Auto connected):
     * make sure the phone's own player isn't also playing, step its media
     * session aside so Android Auto shows the car's, and tell the page what
     * the car is now doing. Android requires a service started with
     * startForegroundService to call startForeground, even if it then has
     * nothing to show - so it does, then removes the notification.
     */
    private void releasePhoneForCar(boolean carPlaying) {
        startForeground(NOTIF_ID, buildNotification(false));
        releaseWakeLock();
        stopStallWatchdog();
        stopLegacyPlaybackForEngineSwitch();
        player.setPlayWhenReady(false);
        player.stop();
        if (mediaSession != null) mediaSession.setActive(false);
        getSharedPreferences("RadioPrefs", MODE_PRIVATE).edit().putBoolean("is_playing", false).apply();
        stopForeground(true);
        RadioWidgetProvider.refresh(this);
        // After the phone player's own "stopped" callbacks (which can put its
        // notification back up and tell the page "paused"): clear those, so
        // the page ends up showing the car's state, not the phone's.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!isReallyPlaying()) {
                if (mediaSession != null) mediaSession.setActive(false);
                stopForeground(true);
            }
            sendJs(carPlaying ? "onNativePlaying" : "onNativePaused");
        }, 400);
    }

    /** Forwards a no-argument command to the page's window.NativeControls bridge. */
    private void sendJs(String command) {
        if (MainActivity.instance != null) {
            MainActivity.instance.runJs(
                    "window.NativeControls && window.NativeControls." + command + "();");
        } else if ("next".equals(command) || "prev".equals(command)) {
            // Next/previous need the page to work out which station comes
            // next, so open it. Status updates (playing/paused) never should:
            // a sleep timer pausing at 2am mustn't pop the app onto the screen.
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        }
    }

    /** Forwards a single string-argument command, safely JSON-quoted. */
    private void sendJsWithArg(String command, String arg) {
        if (MainActivity.instance != null) {
            String jsonArg = org.json.JSONObject.quote(arg != null ? arg : "");
            MainActivity.instance.runJs(
                    "window.NativeControls && window.NativeControls." + command + "(" + jsonArg + ");");
        }
    }

    private void updateState(boolean playing) {
        if (mediaSession != null) {
            mediaSession.setActive(playing);

            android.media.MediaMetadata.Builder metadataBuilder = new android.media.MediaMetadata.Builder()
                    .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, (currentTrack != null && !currentTrack.isEmpty()) ? currentTrack : currentStationName)
                    .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, (currentArtist != null && !currentArtist.isEmpty()) ? currentArtist : currentStationName)
                    .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, currentStationName);
            mediaSession.setMetadata(metadataBuilder.build());

            mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build());
        }
        startForeground(NOTIF_ID, buildNotification(playing));
        RadioWidgetProvider.refresh(this);
    }

    // ---- Home-screen widget support ----

    private boolean isReallyPlaying() {
        return usingLegacyPlayer
                ? (legacyPlayer != null && legacyPlayer.isPlaying())
                : (player != null && player.isPlaying());
    }

    /** The widget starts us with startForegroundService, which must be followed by startForeground. */
    private void ensureForeground() {
        startForeground(NOTIF_ID, buildNotification(isReallyPlaying()));
    }

    /**
     * Gets the current station playing again whatever state the player is in:
     * paused, silenced by another app (playWhenReady still true but not playing),
     * stalled, or dropped after an error. A fresh connection also asks for audio focus again.
     */
    private void resumeFromScratch() {
        if (sleepFading) {
            cancelSleepTimer();
            saveSleepEnd(0);
        }
        if (usingLegacyPlayer) {
            playWithLegacyMediaPlayer(currentStationUrl);
            return;
        }
        player.pause();
        reconnectExoPlayer();
        acquireWakeLock();
    }

    private boolean hasLoadedStation() {
        return currentStationUrl != null;
    }

    /** After the process was killed: replay the station the person last had on. */
    private boolean playSavedStation() {
        StationLibrary.Station s = StationLibrary.savedPhoneStation(this);
        if (s == null) return false;
        playStationNow(s);
        return true;
    }

    private void playStationNow(StationLibrary.Station s) {
        Intent i = new Intent(this, PlaybackService.class).setAction(ACTION_PLAY_STATION);
        i.putExtra(EXTRA_FREQ, s.freq);
        i.putExtra(EXTRA_TITLE, s.name);
        onStartCommand(i, 0, 0);
    }

    /**
     * Widget previous/next. With the app open, the page decides (Band Lock, AU
     * only, ...). With the app closed there is no page, so step through the
     * stations in their normal dial order, wrapping at the ends.
     */
    private void widgetStep(int dir) {
        if (MainActivity.instance != null) {
            sendJs(dir > 0 ? "next" : "prev");
            return;
        }
        java.util.List<StationLibrary.Station> all = StationLibrary.getStations();
        int idx = -1;
        for (int k = 0; k < all.size(); k++) {
            if (all.get(k).url.equals(currentStationUrl)) { idx = k; break; }
        }
        if (idx < 0) {
            StationLibrary.Station near = StationLibrary.savedPhoneStation(this);
            if (near != null) {
                for (int k = 0; k < all.size(); k++) {
                    if (Math.abs(all.get(k).freq - near.freq) < 0.0001) { idx = k; break; }
                }
            }
        }
        int target = idx < 0 ? 0 : (idx + dir + all.size()) % all.size();
        playStationNow(all.get(target));
    }

    private Notification buildNotification(boolean playing) {
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Action playPause = playing
                ? notifAction(android.R.drawable.ic_media_pause, "Pause", ACTION_PAUSE)
                : notifAction(android.R.drawable.ic_media_play, "Play", ACTION_PLAY);

        String titleText = (currentTrack != null && !currentTrack.isEmpty()) ? currentTrack : currentStationName;
        String contentText = (currentArtist != null && !currentArtist.isEmpty())
                ? currentArtist + " — " + currentStationName
                : (playing ? "On air — Radio Wavehopper" : "Paused — Radio Wavehopper");

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(titleText)
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(contentIntent)
                .setOngoing(playing)
                .addAction(notifAction(R.drawable.ic_skip_previous, "Previous", ACTION_PREV))
                .addAction(playPause)
                .addAction(notifAction(R.drawable.ic_skip_next, "Next", ACTION_NEXT))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    private Notification.Action notifAction(int icon, String title, String action) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        PendingIntent pi = PendingIntent.getService(
                this, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build();
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadioWavehopper:playback");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(MAX_WAKE_LOCK_MS);
        }
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null) {
                    int mode = Build.VERSION.SDK_INT >= 29
                            ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                            : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                    wifiLock = wm.createWifiLock(mode, "RadioWavehopper:playback");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (RuntimeException e) {
            android.util.Log.w(TAG, "Couldn't take the Wi-Fi lock", e);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            try { wifiLock.release(); } catch (RuntimeException ignored) {}
        }
    }

    // ---- Diagnostic log helpers (see DiagLog) ----
    private final Handler diagHandler = new Handler(Looper.getMainLooper());
    private final Runnable diagHeartbeat = new Runnable() {
        @Override public void run() {
            if (player != null && player.getPlayWhenReady()) {
                DiagLog.log("heartbeat " + diagSnapshot());
            }
            diagHandler.postDelayed(this, 15_000L);
        }
    };
    private android.content.BroadcastReceiver diagReceiver;
    private android.net.ConnectivityManager.NetworkCallback diagNetCallback;

    private static String diagStateName(int s) {
        switch (s) {
            case Player.STATE_IDLE: return "IDLE";
            case Player.STATE_BUFFERING: return "BUFFERING";
            case Player.STATE_READY: return "READY";
            case Player.STATE_ENDED: return "ENDED";
            default: return String.valueOf(s);
        }
    }

    private String diagSnapshot() {
        try {
            StringBuilder sb = new StringBuilder();
            if (player != null) {
                sb.append("[exo ").append(diagStateName(player.getPlaybackState()))
                  .append(" buf=").append(player.getTotalBufferedDuration()).append("ms")
                  .append(" pwr=").append(player.getPlayWhenReady()).append("] ");
            }
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            sb.append("screen=").append(pm.isInteractive() ? "on" : "off")
              .append(" doze=").append(pm.isDeviceIdleMode())
              .append(" saver=").append(pm.isPowerSaveMode());
            return sb.toString();
        } catch (Throwable t) {
            return "(snapshot failed)";
        }
    }

    private void registerDiagReceivers() {
        try {
            diagReceiver = new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String a = i.getAction();
                    if (a == null) return;
                    if (a.equals(Intent.ACTION_SCREEN_OFF)) DiagLog.log("SCREEN OFF");
                    else if (a.equals(Intent.ACTION_SCREEN_ON)) DiagLog.log("SCREEN ON");
                    else if (a.equals(Intent.ACTION_USER_PRESENT)) DiagLog.log("UNLOCKED");
                    else DiagLog.log("DOZE mode changed: " + diagSnapshot());
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(diagReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(diagReceiver, f);
            }
        } catch (Throwable t) {
            diagReceiver = null;
        }
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            diagNetCallback = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network n) {
                    DiagLog.log("NETWORK available " + diagNetName(cm, n));
                }
                @Override public void onLost(android.net.Network n) {
                    DiagLog.log("NETWORK lost");
                }
            };
            cm.registerDefaultNetworkCallback(diagNetCallback);
        } catch (Throwable t) {
            diagNetCallback = null;
        }
        diagHandler.postDelayed(diagHeartbeat, 15_000L);
    }

    private static String diagNetName(android.net.ConnectivityManager cm, android.net.Network n) {
        try {
            android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c == null) return "?";
            if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
            return "other";
        } catch (Throwable t) {
            return "?";
        }
    }

    private void unregisterDiagReceivers() {
        diagHandler.removeCallbacks(diagHeartbeat);
        try { if (diagReceiver != null) unregisterReceiver(diagReceiver); } catch (Throwable ignored) { }
        try {
            if (diagNetCallback != null) {
                ((android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE))
                        .unregisterNetworkCallback(diagNetCallback);
            }
        } catch (Throwable ignored) { }
        diagReceiver = null;
        diagNetCallback = null;
    }

    @Override
    public void onDestroy() {
        running = false;
        DiagLog.log("=== service destroyed ===");
        unregisterDiagReceivers();
        releaseWakeLock();
        cancelSleepTimer();
        stopStallWatchdog();
        stopLegacyPlaybackForEngineSwitch();
        if (player != null) {
            player.release();
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // stopSelf() below triggers onDestroy() right after this, which is
        // where the player/session actually get released — doing it here
        // too would release the same ExoPlayer instance twice.
        releaseWakeLock();
        if (mediaSession != null) {
            mediaSession.setActive(false);
        }
        stopForeground(true);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
