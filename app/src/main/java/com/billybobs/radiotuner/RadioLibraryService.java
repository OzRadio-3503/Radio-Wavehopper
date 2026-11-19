package com.billybobs.radiotuner;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.CommandButton;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;

/**
 * Exposes the station list to Android Auto / Android Automotive via Media3's
 * MediaLibraryService, so the car's head unit can browse and play stations
 * with its own ExoPlayer instance.
 *
 * Note on architecture: this runs its own independent ExoPlayer, separate
 * from the one PlaybackService owns for the phone screen (station data is
 * shared via StationLibrary, but the two playback engines are not — they
 * don't share playback state, so switching stations from the car won't
 * update the phone UI and vice versa). What they DO share is Android's
 * audio focus system: both players' audio attributes are configured with
 * automatic focus handling (see onCreate), which is what keeps the car and
 * the phone from fighting over the speaker when both happen to start up —
 * and more importantly, what correctly yields to a completely different
 * app (a podcast player, etc.) the same reliable way, since this is
 * ExoPlayer's own built-in, already-proven focus handling rather than a
 * hand-rolled one.
 */
public class RadioLibraryService extends MediaLibraryService {

    private static final String ROOT_ID = "root_id";

    // Extra Now Playing buttons in the car: jump a whole group of stations
    // (Sport, Talk, NSW, ...), alongside the standard skip buttons that step
    // one station. Same as the speakers on the phone screen.
    private static final String CMD_PREV_GROUP = "com.billybobs.radiotuner.PREV_GROUP";
    private static final String CMD_NEXT_GROUP = "com.billybobs.radiotuner.NEXT_GROUP";
    private final SessionCommand prevGroupCommand = new SessionCommand(CMD_PREV_GROUP, android.os.Bundle.EMPTY);
    private final SessionCommand nextGroupCommand = new SessionCommand(CMD_NEXT_GROUP, android.os.Bundle.EMPTY);
    private ImmutableList<CommandButton> groupButtons;
    private boolean logoRefreshPending = false;

    // Three more Now Playing buttons: lock the skip buttons to one band -
    // AM, FM (one button, toggling between the two), or DAB/Internet
    // combined (the car's own split there, coarser than the phone's, since
    // a small screen has less room for it) - or back to every station.
    // Car-only; doesn't touch the phone's own band lock.
    private static final String CMD_BAND_AMFM = "com.billybobs.radiotuner.BAND_AMFM";
    private static final String CMD_BAND_DABNET = "com.billybobs.radiotuner.BAND_DABNET";
    private static final String CMD_BAND_ALL = "com.billybobs.radiotuner.BAND_ALL";
    private final SessionCommand bandAmfmCommand = new SessionCommand(CMD_BAND_AMFM, android.os.Bundle.EMPTY);
    private final SessionCommand bandDabnetCommand = new SessionCommand(CMD_BAND_DABNET, android.os.Bundle.EMPTY);
    private final SessionCommand bandAllCommand = new SessionCommand(CMD_BAND_ALL, android.os.Bundle.EMPTY);
    /** null (no filter / "All"), "am", "fm" or "dabnet". */
    private String carBandFilter = null;

    /**
     * The car filter bucket this station falls into: its own exact "am" or
     * "fm", or "dabnet" for a DAB/Internet station (those two stay combined
     * on the car - see CMD_BAND_DABNET). Unlike the AM/FM button, which
     * toggles between the two real bands one at a time.
     */
    private static String carFilterBandOf(StationLibrary.Station s) {
        String b = StationLibrary.realBand(s);
        return (b.equals("am") || b.equals("fm")) ? b : "dabnet";
    }

    private static final String TAG = "RadioLibraryService";
    private static final int MAX_CONSECUTIVE_STATION_FAILURES = 5;

    // Same stall watchdog as PlaybackService - see its comment for the full
    // story (confirmed via logcat: some StreamTheWorld ad-stitched stations
    // silently stop the native AudioTrack mid-stream and never restart it).
    private static final long STALL_POLL_INTERVAL_MS = 2000L;
    private static final int STALL_POLLS_BEFORE_RECONNECT = 3;
    private static final long STALL_RECONNECT_COOLDOWN_MS = 8000L;
    private static final int STALL_POLLS_BEFORE_FIRST_PLAY = 8;
    private boolean currentStationHasPlayed = false;

    private ExoPlayer player;
    private final LiveResume liveResume = new LiveResume();
    private int lastPlaybackState = Player.STATE_IDLE;
    private MediaLibrarySession mediaLibrarySession;
    private List<StationLibrary.Station> stations;
    private int consecutiveFailures = 0;

    // Live stations never genuinely "end" - see PlaybackService's
    // ENDED-reconnect comment. Here, with REPEAT_MODE_ALL over the whole
    // station list, a stream whose server closes the connection doesn't go
    // silent: ExoPlayer auto-advances to the NEXT station instead. So an
    // automatic (non-user) transition means "the station we were on dropped";
    // we go back to it and reconnect. Capped the same way as the phone.
    private static final int MAX_ENDED_RECONNECTS_PER_WINDOW = 10;
    private static final long ENDED_RECONNECT_WINDOW_MS = 60_000L;
    private int endedReconnectCount = 0;
    private long endedReconnectWindowStartMs = 0;
    private int lastStationIndex = -1;

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

    private static String mediaIdFor(StationLibrary.Station s) {
        return "station_" + s.freq;
    }

    /** Index of the station with this media ID in the ordered {@link #stations} list, or -1. */
    private int indexOfStation(String mediaId) {
        for (int i = 0; i < stations.size(); i++) {
            if (mediaIdFor(stations.get(i)).equals(mediaId)) return i;
        }
        return -1;
    }

    /**
     * A voice/search request that named no station at all - just the app
     * itself ("play Radio Wavehopper"), or nothing ("play" with an empty
     * search query). Resolved the same way onPlaybackResumption resolves a
     * bare resume, instead of matching nothing and silently doing nothing.
     */
    private static final java.util.Set<String> APP_NAME_QUERIES = new java.util.HashSet<>(java.util.Arrays.asList(
            "", "radiowavehopper", "billybobsradiotuner", "billybobsradio", "billybobradiotuner", "billybobradio"));

    /**
     * Index of the best name/frequency match for a free-text query, or -1
     * only if even the app-name/empty fallback above doesn't apply. This is
     * what lets a voice command ("Ok Google, tune to 2BRW" / "...play ABC
     * News on Radio Wavehopper") actually resolve to a station: Assistant
     * and Android Auto hand voice requests to onSetMediaItems as a single
     * MediaItem with no mediaId at all, just this raw spoken text in its
     * RequestMetadata.searchQuery, so matching only by mediaId (as tapping
     * a station from the browse list does) would silently fail to resolve
     * it and never actually start playing anything.
     */
    private int indexOfQuery(String query) {
        List<Integer> matches = searchStations(query);
        if (!matches.isEmpty()) return matches.get(0);
        if (APP_NAME_QUERIES.contains(normalise(query == null ? "" : query))) {
            return combinedLastStationIndex();
        }
        return -1;
    }

    private static final String[] NUMBER_WORDS = {
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"};

    /** A spoken/typed real frequency, with an optional band: "97.3fm", "873am", "97.3". */
    private static final java.util.regex.Pattern REAL_FREQ_QUERY =
            java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)?)(fm|am|dab)?$");

    /**
     * Lower-cases, turns spoken numbers into digits and drops spaces and
     * punctuation, so voice input like "two g b" or "triple j" matches
     * "2GB" / "Triple J Hottest", and "power 100 f.m." matches "Power 100".
     */
    private static String normalise(String text) {
        String s = " " + text.toLowerCase(java.util.Locale.ROOT) + " ";
        // "tune to"/"tune in to"/"listen to" as well as "play" - name
        // matching already tolerated these via substring containment, but
        // the real-frequency match below needs the exact remaining text,
        // so strip the same filler words plain "play" was getting.
        s = s.replaceAll("^\\s*(play|tune( in)? to|listen to)\\s+", " ")
                .replaceAll("\\s+on (billy bob'?s( radio)?|radio wavehopper)\\s*$", " ");
        for (int n = 0; n < NUMBER_WORDS.length; n++) {
            s = s.replaceAll("(?<=[^a-z])" + NUMBER_WORDS[n] + "(?=[^a-z])", String.valueOf(n));
        }
        return s.replaceAll("[^a-z0-9.]", "");
    }

    /**
     * Station indices matching a typed or spoken query, best matches first:
     * a real-frequency match ("tune to 97.3 FM", "873 AM"), then an exact
     * name/dial-frequency match, then a partial one. Real-frequency matches
     * are checked against the station's actual broadcast label (see
     * StationLibrary.realBand/realFreqValue), not its position on our dial,
     * since that's what a listener means by "97.3 FM" - several stations
     * can share one real frequency across different states, so (as with
     * everything else here) the first one in dial order wins. A bare
     * number with no AM/FM/DAB word tries FM first, then AM, since that's
     * how most real frequencies people say are meant.
     */
    private List<Integer> searchStations(String query) {
        List<Integer> result = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        if (query == null) return result;
        String q = normalise(query);
        if (q.isEmpty()) return result;

        java.util.regex.Matcher rm = REAL_FREQ_QUERY.matcher(q);
        if (rm.matches()) {
            double num = Double.parseDouble(rm.group(1));
            String wantBand = rm.group(2);
            String[] tryBands = wantBand != null ? new String[]{wantBand} : new String[]{"fm", "am"};
            for (String band : tryBands) {
                for (int i = 0; i < stations.size(); i++) {
                    StationLibrary.Station s = stations.get(i);
                    if (!StationLibrary.realBand(s).equals(band)) continue;
                    Double v = StationLibrary.realFreqValue(s);
                    if (v != null && Math.abs(v - num) < 0.05 && seen.add(i)) result.add(i);
                }
            }
        }

        List<Integer> partial = new ArrayList<>();
        for (int i = 0; i < stations.size(); i++) {
            StationLibrary.Station s = stations.get(i);
            String name = normalise(s.name);
            // "88.7", "88.70", "8870" and "887" all find 88.7; "88.65" finds 88.65.
            String freq = String.format(java.util.Locale.ROOT, "%.2f", s.freq);
            String shortFreq = freq.endsWith("0") ? freq.substring(0, freq.length() - 1) : freq;
            if (name.equals(q) || freq.equals(q) || shortFreq.equals(q)
                    || freq.replace(".", "").equals(q) || shortFreq.replace(".", "").equals(q)) {
                if (seen.add(i)) result.add(i);
            } else if (name.contains(q) || (name.length() >= 3 && q.contains(name)) || freq.startsWith(q)) {
                if (seen.add(i)) partial.add(i);
            }
        }
        result.addAll(partial);
        return result;
    }

    private List<MediaItem> fullPlaylist() {
        List<MediaItem> items = new ArrayList<>();
        for (StationLibrary.Station s : stations) items.add(buildMediaItem(s));
        return items;
    }

    private static final String PREFS = "RadioAutoPrefs";
    private static final String KEY_LAST_STATION = "last_station_id";
    private static final String KEY_LAST_STATION_AT = "last_station_id_at";

    private static final String KEY_LAYOUT = "station_layout";

    /**
     * The dial was reorganised, and the saved station is stored by its old
     * frequency ("station_88.0"). Move it once to the station's new
     * frequency, so the car still resumes the same station.
     */
    private void migrateSavedStation() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int layout = prefs.getInt(KEY_LAYOUT, 1);
        if (layout >= 8) return;
        android.content.SharedPreferences.Editor edit = prefs.edit().putInt(KEY_LAYOUT, 8);
        String id = prefs.getString(KEY_LAST_STATION, null);
        if (id != null && id.startsWith("station_")) {
            String freq = id.substring("station_".length());
            if (layout < 2) {
                String moved = StationLibrary.layout2FreqFor(freq);
                if (moved != null) freq = moved;
            }
            if (layout < 3) {
                String moved = StationLibrary.layout3FreqFor(freq);
                if (moved != null) freq = moved;
            }
            if (layout < 4) {
                String moved = StationLibrary.layout4FreqFor(freq);
                if (moved != null) freq = moved;
            }
            if (layout < 5) {
                String moved = StationLibrary.layout5FreqFor(freq);
                if (moved != null) freq = moved;
            }
            if (layout < 6) {
                String moved = StationLibrary.layout6FreqFor(freq);
                if (moved != null) freq = moved;
            }
            if (layout < 7) {
                String moved = StationLibrary.layout7FreqFor(freq);
                if (moved != null) freq = moved;
            }
            String moved = StationLibrary.layout8FreqFor(freq);
            if (moved != null) freq = moved;
            edit.putString(KEY_LAST_STATION, "station_" + freq);
        }
        edit.apply();
    }

    /**
     * Android Auto taking over playback cancels any sleep timer set on the
     * phone. The timer lives in PlaybackService (the phone's player), which
     * the car never uses, so left alone it would carry on in the background
     * and later stop the phone's radio unexpectedly.
     */
    private void cancelPhoneSleepTimer() {
        android.content.SharedPreferences prefs = getSharedPreferences("RadioPrefs", MODE_PRIVATE);
        if (prefs.getLong("sleep_end_at", 0L) <= 0L) return;
        prefs.edit().putLong("sleep_end_at", 0L).apply();
        if (PlaybackService.running) {
            try {
                Intent cancel = new Intent(this, PlaybackService.class)
                        .setAction(PlaybackService.ACTION_SET_SLEEP_TIMER)
                        .putExtra(PlaybackService.EXTRA_MINUTES, 0);
                startService(cancel);
            } catch (RuntimeException e) {
                Log.w(TAG, "Couldn't cancel the phone's sleep timer", e);
            }
        }
        if (MainActivity.instance != null) {
            MainActivity.instance.runJs("window.NativeControls && window.NativeControls.onSleepTimerCancelled"
                    + " && window.NativeControls.onSleepTimerCancelled();");
        }
        Log.i(TAG, "Android Auto started playing - cancelled the phone's sleep timer.");
    }

    private int savedStationIndex() {
        String id = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_STATION, null);
        return id != null ? indexOfStation(id) : -1;
    }

    /** Index of the station nearest this dial frequency, or -1 if there are none. */
    private int nearestStationIndex(double freq) {
        if (stations == null || stations.isEmpty()) return -1;
        int best = 0;
        double bestDiff = Math.abs(stations.get(0).freq - freq);
        for (int i = 1; i < stations.size(); i++) {
            double diff = Math.abs(stations.get(i).freq - freq);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    /**
     * Whichever was played more recently: the station last played in the
     * car, or the one last played on the phone - so getting in the car
     * after listening on the phone resumes that same station, instead of
     * whatever the car happened to have on last. Falls back to the car's
     * own last station when the phone has never saved one (or is older).
     */
    private int combinedLastStationIndex() {
        android.content.SharedPreferences carPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long carAt = carPrefs.getLong(KEY_LAST_STATION_AT, 0L);
        int carIdx = savedStationIndex();

        android.content.SharedPreferences phonePrefs = getSharedPreferences("RadioPrefs", MODE_PRIVATE);
        long phoneAt = phonePrefs.getLong("last_station_at", 0L);
        double phoneFreq = Double.longBitsToDouble(
                phonePrefs.getLong("last_station_freq_bits", Double.doubleToLongBits(-1)));

        if (phoneAt > carAt && phoneFreq >= 0) {
            int idx = nearestStationIndex(phoneFreq);
            if (idx >= 0) return idx;
        }
        return carIdx;
    }

    /**
     * Reconnects the current station without player.stop(). stop() puts the
     * player into IDLE, which Android Auto reads as "playback ended": it
     * closes the Now Playing screen and drops back to the station list, even
     * though the station then comes in a moment later in the background.
     * Seeking to the station's live position opens a fresh connection while
     * staying in the playing/buffering state the car expects.
     */
    private void reconnectCurrentStation() {
        player.seekToDefaultPosition(player.getCurrentMediaItemIndex());
        player.prepare();
        player.play();
        lastKnownPositionMs = -1;
        stallPollCount = 0;
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
        if (player.getMediaItemCount() == 0) return;
        Log.i(TAG, "Resumed after a pause - jumping back to live");
        player.seekToDefaultPosition(player.getCurrentMediaItemIndex());
        if (player.getPlaybackState() == Player.STATE_IDLE) player.prepare();
        lastKnownPositionMs = -1;
        stallPollCount = 0;
    }

    /**
     * Jumps to the first station of the next group (+1), or back (-1): to
     * the start of the current group, or the previous group if already at
     * its start. Wraps round at either end. Keeps the full playlist (so the normal
     * skip buttons still step one station) and never calls stop(), so the
     * car stays on Now Playing.
     */
    private void jumpGroup(int direction) {
        if (stations == null || stations.isEmpty()) return;
        int current = player.getMediaItemCount() == stations.size()
                ? player.getCurrentMediaItemIndex() : savedStationIndex();
        if (current < 0) current = 0;
        int groups = StationLibrary.GROUPS.length;
        int here = StationLibrary.groupIndexFor(stations.get(current).freq);
        // Back works like "previous track": from partway through a group, go
        // to its first station; from the first station, the previous group.
        boolean atStart = StationLibrary.firstStationOfGroup(stations, here) == current;
        int group = (direction < 0 && !atStart) ? here : (here + direction + groups) % groups;
        int target = StationLibrary.firstStationOfGroup(stations, group);
        if (target < 0) return;
        Log.i(TAG, "Group jump to " + StationLibrary.GROUPS[group][1] + " (" + stations.get(target).name + ")");
        if (player.getMediaItemCount() == stations.size()) {
            player.seekToDefaultPosition(target);
        } else {
            player.setMediaItems(fullPlaylist(), target, 0L);
        }
        player.prepare();
        player.play();
    }

    /**
     * With a band filter on, the group-jump buttons and the car's fast-
     * forward/rewind controls all do the same thing instead: jump to the
     * first station of the current filter (AM/FM, or DAB/Internet), in
     * dial order. Pressing it again lands on the same station - there's
     * nowhere else to go, which is the "always back to the start" behaviour
     * that was asked for.
     */
    private void jumpBandStart() {
        if (carBandFilter == null || stations == null) return;
        for (int i = 0; i < stations.size(); i++) {
            if (!carFilterBandOf(stations.get(i)).equals(carBandFilter)) continue;
            Log.i(TAG, "Band lock: jumped to the start of " + carBandFilter + " (" + stations.get(i).name + ")");
            if (player.getMediaItemCount() == stations.size()) {
                player.seekToDefaultPosition(i);
            } else {
                player.setMediaItems(fullPlaylist(), i, 0L);
            }
            player.prepare();
            player.play();
            return;
        }
    }

    /**
     * Steps to the next (direction +1) or previous (-1) station whose car
     * band matches the current filter, wrapping past either end of the
     * whole dial. Returns false - meaning "step normally, no filter is on"
     * - when there's no band filter active.
     */
    private boolean stepWithinBand(int direction) {
        if (carBandFilter == null || stations == null || stations.isEmpty()) return false;
        int current = player.getCurrentMediaItemIndex();
        if (current < 0 || current >= stations.size()) return false;
        int n = stations.size();
        for (int step = 1; step <= n; step++) {
            int idx = ((current + direction * step) % n + n) % n;
            if (carFilterBandOf(stations.get(idx)).equals(carBandFilter)) {
                player.seekToDefaultPosition(idx);
                player.prepare();
                player.play();
                return true;
            }
        }
        return false; // nothing on the dial matches the filter - shouldn't happen
    }

    /**
     * The Now Playing screen's custom buttons: the two group-jump buttons,
     * then the band filter buttons. DAB/Internet and All disable themselves
     * while already active (the same idea as the phone's band lock - you
     * can't press the mode you're already in). AM/FM is different: it's a
     * toggle, always pressable, that flips between AM only and FM only one
     * tap at a time (never a combined AM+FM state). Its label always reads
     * "AM/FM" - only the icon changes, to the AM-lit or FM-lit variant, so
     * which side is active is shown without the button relabelling itself.
     * NOTE: unverified on a real head unit - some car UIs recolour custom
     * button icons with a single accent tint, which could flatten out the
     * two-colour "lit side" look these icons are drawn with.
     */
    private ImmutableList<CommandButton> customLayout() {
        int amFmIcon = "am".equals(carBandFilter) ? R.drawable.ic_band_amfm_am
                : "fm".equals(carBandFilter) ? R.drawable.ic_band_amfm_fm
                : R.drawable.ic_band_amfm_neutral;
        return ImmutableList.<CommandButton>builder()
                .addAll(groupButtons)
                .add(new CommandButton.Builder()
                        .setDisplayName("AM/FM")
                        .setIconResId(amFmIcon)
                        .setSessionCommand(bandAmfmCommand)
                        .build())
                .add(new CommandButton.Builder()
                        .setDisplayName("DAB/Internet")
                        .setIconResId(R.drawable.ic_band_dabnet)
                        .setSessionCommand(bandDabnetCommand)
                        .setEnabled(!"dabnet".equals(carBandFilter))
                        .build())
                .add(new CommandButton.Builder()
                        .setDisplayName("All stations")
                        .setIconResId(R.drawable.ic_band_all)
                        .setSessionCommand(bandAllCommand)
                        .setEnabled(carBandFilter != null)
                        .build())
                .build();
    }

    private void refreshCustomLayout() {
        DiagLog.log("carUI refreshCustomLayout: session=" + (mediaLibrarySession != null)
                + " carBandFilter=" + carBandFilter);
        if (mediaLibrarySession != null) mediaLibrarySession.setCustomLayout(customLayout());
    }

    private static MediaItem buildMediaItem(StationLibrary.Station s) {
        // e.g. "1269 AM · 2SM 1269 · NSW": real frequency first, so Now
        // Playing shows it the same prominent way the phone's dial does,
        // then the name, then the state or country.
        String title = s.real + " \u00b7 " + s.name + (s.region.isEmpty() ? "" : " \u00b7 " + s.region);
        MediaItem.Builder builder = new MediaItem.Builder()
                .setMediaId(mediaIdFor(s))
                .setUri(s.url)
                .setRequestMetadata(new MediaItem.RequestMetadata.Builder()
                        .setMediaUri(Uri.parse(s.url))
                        .build())
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(title)
                        // Second line in the car: the station's group (Sport,
                        // Talk, NSW (and ACT), ...), so you always know where
                        // you are on the dial.
                        .setArtist(StationLibrary.GROUPS[StationLibrary.groupIndexFor(s.freq)][1])
                        .setSubtitle(StationLibrary.GROUPS[StationLibrary.groupIndexFor(s.freq)][1])
                        .setAlbumTitle("Radio Wavehopper")
                        // Station logo - see StationArtworkProvider.
                        .setArtworkUri(StationArtworkProvider.uriFor(s))
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build());
        // Same mis-detection workaround as PlaybackService - null for every
        // station except the handful that need a pinned MIME type.
        if (s.mimeType != null) builder.setMimeType(s.mimeType);
        return builder.build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        DiagLog.init(this); // in case the car binds here before PlaybackService has started
        UserStations.ensure(this);
        initializeStations();
        migrateSavedStation();

        // Same fix as PlaybackService (the phone) - several of these
        // relay/aggregator stations reject ExoPlayer's default generic
        // User-Agent with ERROR_CODE_IO_BAD_HTTP_STATUS, and some are
        // redirect chains that hop between http and https.
        // Same OkHttp-based client as PlaybackService uses now - see the
        // comment there for why: the default HttpURLConnection-based client
        // is the one concrete difference we've found between the ad-stitched
        // StreamTheWorld stations working (browser, WebView, RadioDroid -
        // all OkHttp or an equivalent modern client) and not working (us).
        OkHttpClient okHttpClient = new OkHttpClient.Builder().build();
        // Per-host User-Agent - see StreamHeaders. No setUserAgent() on the
        // factory, because that would silently override it.
        OkHttpDataSource.Factory httpDataSourceFactory = new OkHttpDataSource.Factory(okHttpClient);
        ResolvingDataSource.Factory userAgentFactory = new ResolvingDataSource.Factory(
                httpDataSourceFactory,
                dataSpec -> dataSpec.withAdditionalHeaders(java.util.Collections.singletonMap(
                        "User-Agent", StreamHeaders.userAgentFor(dataSpec.uri))));
        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this, userAgentFactory);

        // No metadata renderers: many streams send "now playing" tags every
        // few seconds (HLS ID3 tags on SEN/ABC/BBC, Icecast StreamTitle).
        // Each one changed the session's metadata, and Android Auto redrew
        // Now Playing - the background "flashed" every few seconds. The car
        // shows only the station, its real frequency, group and logo, which
        // never change while a station plays.
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this) {
            @Override
            protected void buildMetadataRenderers(
                    @NonNull android.content.Context context,
                    @NonNull MetadataOutput output,
                    @NonNull Looper outputLooper,
                    int extensionRendererMode,
                    @NonNull ArrayList<Renderer> out) {
                // Deliberately none - see above.
            }
        };
        player = new ExoPlayer.Builder(this, renderersFactory)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
                .build();
        // Hold the CPU and Wi-Fi awake while playing, so streams from distant
        // servers don't drop out when the phone's screen is off (Wi-Fi
        // power-save otherwise starves them). Media3 releases both when paused.
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        // Let ExoPlayer request/abandon Android audio focus automatically.
        // This is what keeps the car's playback and the phone WebView's
        // <audio> playback from fighting over the speaker — see the class
        // Javadoc above for the full picture.
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

        // Wrap around in both directions, same as the phone UI's prev/next
        // arrows — with REPEAT_MODE_ALL, ExoPlayer always reports a next and
        // a previous item available, so Android Auto's skip-next/skip-previous
        // transport buttons (the car's "up"/"down" controls) never grey out,
        // even at the first or last station in the list.
        player.setRepeatMode(Player.REPEAT_MODE_ALL);

        // Give this native engine the same resilience the phone/web player
        // already has: a station that errors out (dead relay, dropped
        // connection, bad stream) doesn't just sit there silent — it logs
        // *why* (visible in Logcat under this tag) and moves on to the next
        // station, same as a skip-next tap. Capped at 5 in a row so a bad
        // run of stations can't spin forever; it resets back to 0 the
        // moment something actually starts playing.
        player.addListener(new Player.Listener() {
            // Back to live after a pause - see LiveResume. Seeking (not
            // stop()) keeps the car on Now Playing, as in reconnectCurrentStation.
            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                if (liveResume.onPlayWhenReadyChanged(playWhenReady)) jumpToLive();
            }

            @Override
            public void onPlaybackSuppressionReasonChanged(int suppressionReason) {
                if (liveResume.onSuppressionChanged(
                        suppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
                        player.getPlayWhenReady())) {
                    jumpToLive();
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                boolean wasIdle = lastPlaybackState == Player.STATE_IDLE;
                lastPlaybackState = playbackState;
                if (liveResume.onStateChanged(playbackState == Player.STATE_IDLE, wasIdle,
                        player.getPlayWhenReady())) {
                    jumpToLive();
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                // Fell behind a live HLS station's window (see PlaybackService):
                // jump back to live on the same station rather than skipping
                // to the next one.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
                        && behindLiveRecoveryAllowed()) {
                    Log.w(TAG, "Fell behind the live window - jumping back to live");
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                MediaItem current = player.getCurrentMediaItem();
                String stationId = current != null ? current.mediaId : "unknown";
                Log.e(TAG, "Playback error on " + stationId + ": "
                        + error.getErrorCodeName() + " - " + error.getMessage(), error);

                consecutiveFailures++;
                if (consecutiveFailures <= MAX_CONSECUTIVE_STATION_FAILURES) {
                    player.seekToNextMediaItem();
                    player.prepare();
                    player.play();
                } else {
                    Log.e(TAG, MAX_CONSECUTIVE_STATION_FAILURES
                            + " stations in a row failed to play; stopping auto-skip.");
                }
            }

            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                        && lastStationIndex >= 0
                        && lastStationIndex < player.getMediaItemCount()) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - endedReconnectWindowStartMs > ENDED_RECONNECT_WINDOW_MS) {
                        endedReconnectWindowStartMs = now;
                        endedReconnectCount = 0;
                    }
                    if (++endedReconnectCount <= MAX_ENDED_RECONNECTS_PER_WINDOW) {
                        Log.w(TAG, "Live stream at index " + lastStationIndex
                                + " ended (server closed the connection) - reconnecting "
                                + "instead of auto-advancing.");
                        player.seekToDefaultPosition(lastStationIndex);
                        player.prepare();
                        player.play();
                        return; // still on lastStationIndex (and still the saved station)
                    }
                    Log.e(TAG, "Station at index " + lastStationIndex
                            + " keeps ending immediately; letting it advance.");
                }
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    // A user/controller pick or skip - fresh budget.
                    endedReconnectCount = 0;
                    endedReconnectWindowStartMs = android.os.SystemClock.elapsedRealtime();
                }
                liveResume.reset();
                lastStationIndex = player.getCurrentMediaItemIndex();
                // New station: the watchdog starts fresh and gives it time to
                // tune in before treating buffering as a stall.
                lastKnownPositionMs = -1;
                stallPollCount = 0;
                currentStationHasPlayed = false;
                // With a filter on, follow wherever you just went (e.g. a
                // station picked from browse, or by voice) instead of letting
                // the old filter immediately skip away from it on the next
                // next/previous press.
                if (carBandFilter != null && lastStationIndex >= 0 && lastStationIndex < stations.size()) {
                    String newBand = carFilterBandOf(stations.get(lastStationIndex));
                    if (!newBand.equals(carBandFilter)) {
                        carBandFilter = newBand;
                        refreshCustomLayout();
                    }
                }
                // Remember it for the next time the car connects.
                if (mediaItem != null && !mediaItem.mediaId.isEmpty()) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_LAST_STATION, mediaItem.mediaId)
                            .putLong(KEY_LAST_STATION_AT, System.currentTimeMillis())
                            .apply();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    cancelPhoneSleepTimer();
                    consecutiveFailures = 0;
                    currentStationHasPlayed = true;
                    lastKnownPositionMs = -1;
                    stallPollCount = 0;
                    stallWatchdogHandler.removeCallbacks(stallWatchdogTick);
                    stallWatchdogHandler.postDelayed(stallWatchdogTick, STALL_POLL_INTERVAL_MS);
                } else if (!player.getPlayWhenReady()) {
                    // Only a real pause/stop turns the watchdog off. A dead
                    // stream (BUFFERING/ENDED with playWhenReady still true)
                    // is exactly what it's there to catch.
                    stallWatchdogHandler.removeCallbacks(stallWatchdogTick);
                }
            }
        });

        groupButtons = ImmutableList.of(
                new CommandButton.Builder()
                        .setDisplayName("Previous group")
                        .setIconResId(R.drawable.ic_group_prev)
                        .setSessionCommand(prevGroupCommand)
                        .build(),
                new CommandButton.Builder()
                        .setDisplayName("Next group")
                        .setIconResId(R.drawable.ic_group_next)
                        .setSessionCommand(nextGroupCommand)
                        .build());

        MediaLibrarySession.Callback callback = new MediaLibrarySession.Callback() {
            // Allow the two group commands on top of the usual ones.
            @NonNull
            @Override
            public MediaSession.ConnectionResult onConnect(
                    @NonNull MediaSession session,
                    @NonNull MediaSession.ControllerInfo controller) {
                MediaSession.ConnectionResult defaults =
                        MediaLibrarySession.Callback.super.onConnect(session, controller);
                SessionCommands commands = defaults.availableSessionCommands.buildUpon()
                        .add(prevGroupCommand)
                        .add(nextGroupCommand)
                        .add(bandAmfmCommand)
                        .add(bandDabnetCommand)
                        .add(bandAllCommand)
                        .build();
                DiagLog.log("carUI onConnect: controller=" + controller.getPackageName()
                        + " trusted=" + controller.isTrusted()
                        + " legacy=" + controller.getControllerVersion());
                try {
                    return MediaSession.ConnectionResult.accept(commands, defaults.availablePlayerCommands);
                } catch (Throwable t) {
                    DiagLog.log("carUI onConnect THREW: " + android.util.Log.getStackTraceString(t));
                    throw t;
                }
            }

            @Override
            public void onPostConnect(
                    @NonNull MediaSession session,
                    @NonNull MediaSession.ControllerInfo controller) {
                DiagLog.log("carUI onPostConnect ENTERED: controller=" + controller.getPackageName());
                try {
                    ImmutableList<CommandButton> layout = customLayout();
                    DiagLog.log("carUI onPostConnect: controller=" + controller.getPackageName()
                            + " customLayout size=" + layout.size());
                    session.setCustomLayout(controller, layout);
                } catch (Throwable t) {
                    DiagLog.log("carUI onPostConnect THREW: " + android.util.Log.getStackTraceString(t));
                }
            }

            @Override
            public void onDisconnected(
                    @NonNull MediaSession session,
                    @NonNull MediaSession.ControllerInfo controller) {
                DiagLog.log("carUI onDisconnected: controller=" + controller.getPackageName()
                        + " trusted=" + controller.isTrusted());
            }

            @NonNull
            @Override
            public ListenableFuture<SessionResult> onCustomCommand(
                    @NonNull MediaSession session,
                    @NonNull MediaSession.ControllerInfo controller,
                    @NonNull SessionCommand customCommand,
                    @NonNull android.os.Bundle args) {
                if (CMD_NEXT_GROUP.equals(customCommand.customAction)) {
                    if (carBandFilter != null) jumpBandStart(); else jumpGroup(1);
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                }
                if (CMD_PREV_GROUP.equals(customCommand.customAction)) {
                    if (carBandFilter != null) jumpBandStart(); else jumpGroup(-1);
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                }
                if (CMD_BAND_AMFM.equals(customCommand.customAction)) {
                    // A toggle, not a pick: AM <-> FM one tap at a time.
                    // From anything else (All, DAB/Internet, or the very
                    // first press) it lands on AM.
                    carBandFilter = "am".equals(carBandFilter) ? "fm" : "am";
                    refreshCustomLayout();
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                }
                if (CMD_BAND_DABNET.equals(customCommand.customAction)) {
                    carBandFilter = "dabnet";
                    refreshCustomLayout();
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                }
                if (CMD_BAND_ALL.equals(customCommand.customAction)) {
                    carBandFilter = null;
                    refreshCustomLayout();
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                }
                return MediaLibrarySession.Callback.super.onCustomCommand(session, controller, customCommand, args);
            }

            @NonNull
            @Override
            public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(
                    @NonNull MediaLibrarySession session,
                    @NonNull MediaSession.ControllerInfo browser,
                    @Nullable LibraryParams params) {
                DiagLog.log("carUI onGetLibraryRoot: controller=" + browser.getPackageName());
                MediaItem rootItem = new MediaItem.Builder()
                        .setMediaId(ROOT_ID)
                        .setMediaMetadata(new MediaMetadata.Builder()
                                .setTitle("Radio Stations")
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .build())
                        .build();
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params));
            }

            @NonNull
            @Override
            public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(
                    @NonNull MediaLibrarySession session,
                    @NonNull MediaSession.ControllerInfo browser,
                    @NonNull String parentId,
                    int page,
                    int pageSize,
                    @Nullable LibraryParams params) {
                List<MediaItem> stationItems = new ArrayList<>();
                for (StationLibrary.Station s : stations) {
                    stationItems.add(buildMediaItem(s));
                }
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(stationItems), params));
            }

            @NonNull
            @Override
            public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
                    @NonNull MediaLibrarySession session,
                    @NonNull MediaSession.ControllerInfo browser,
                    @NonNull String mediaId) {
                for (StationLibrary.Station s : stations) {
                    if (mediaId.equals(mediaIdFor(s))) {
                        return Futures.immediateFuture(LibraryResult.ofItem(buildMediaItem(s), null));
                    }
                }
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE));
            }

            @NonNull
            @Override
            public ListenableFuture<LibraryResult<Void>> onSearch(
                    @NonNull MediaLibrarySession session,
                    @NonNull MediaSession.ControllerInfo browser,
                    @NonNull String query,
                    @Nullable LibraryParams params) {
                // The car waits for this notification before it fetches
                // results. Without it, search (typed or voice) shows a
                // spinner forever. Notify even for zero results, so the car
                // can show "no results" instead of spinning.
                session.notifySearchResultChanged(browser, query, searchStations(query).size(), params);
                return Futures.immediateFuture(LibraryResult.ofVoid());
            }

            // When Android Auto (or any controller) asks to play one station —
            // selecting it from the browse list, or via search — this swaps
            // that single request out for the FULL ordered station playlist,
            // started at the selected station's position. That's what lets
            // the car's native skip-previous/skip-next transport buttons step
            // one station up or down, the same way the phone UI's ‹ › arrows
            // do, instead of only ever being able to play one isolated
            // station with no way to move to an adjacent one.
            @NonNull
            @Override
            public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onSetMediaItems(
                    @NonNull MediaSession mediaSession,
                    @NonNull MediaSession.ControllerInfo controller,
                    @NonNull List<MediaItem> mediaItems,
                    int startIndex,
                    long startPositionMs) {
                if (mediaItems.size() == 1) {
                    MediaItem requested = mediaItems.get(0);
                    int idx = indexOfStation(requested.mediaId);
                    if (idx < 0) {
                        // No mediaId match - this is very likely a voice
                        // request ("tune to 2BRW"), which arrives with no
                        // mediaId at all, just this free-text search query.
                        String query = requested.requestMetadata != null
                                ? requested.requestMetadata.searchQuery : null;
                        idx = indexOfQuery(query);
                    }
                    if (idx >= 0) {
                        return Futures.immediateFuture(
                                new MediaSession.MediaItemsWithStartPosition(fullPlaylist(), idx, 0L));
                    }
                }
                return Futures.immediateFuture(
                        new MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs));
            }

            // Android asks this when the car (or the system media controls)
            // wants to resume "whatever was playing last" - e.g. getting
            // back in the car and starting the engine. Without it, the car
            // had nothing to resume and forgot the station.
            @NonNull
            @Override
            public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onPlaybackResumption(
                    @NonNull MediaSession mediaSession,
                    @NonNull MediaSession.ControllerInfo controller) {
                int idx = Math.max(0, combinedLastStationIndex());
                return Futures.immediateFuture(
                        new MediaSession.MediaItemsWithStartPosition(fullPlaylist(), idx, 0L));
            }

            // Some Assistant / Android Auto versions route a voice "play X"
            // request through onAddMediaItems instead of (or before)
            // onSetMediaItems. Same resolution logic, so a voice request
            // lands correctly whichever path the car/phone combo uses.
            @NonNull
            @Override
            public ListenableFuture<List<MediaItem>> onAddMediaItems(
                    @NonNull MediaSession mediaSession,
                    @NonNull MediaSession.ControllerInfo controller,
                    @NonNull List<MediaItem> mediaItems) {
                List<MediaItem> resolved = new ArrayList<>();
                for (MediaItem item : mediaItems) {
                    int idx = indexOfStation(item.mediaId);
                    if (idx < 0) {
                        String query = item.requestMetadata != null
                                ? item.requestMetadata.searchQuery : null;
                        idx = indexOfQuery(query);
                    }
                    resolved.add(idx >= 0 ? buildMediaItem(stations.get(idx)) : item);
                }
                return Futures.immediateFuture(resolved);
            }

            @NonNull
            @Override
            public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetSearchResult(
                    @NonNull MediaLibrarySession session,
                    @NonNull MediaSession.ControllerInfo browser,
                    @NonNull String query,
                    int page,
                    int pageSize,
                    @Nullable LibraryParams params) {
                List<Integer> matches = searchStations(query);
                int from = Math.max(0, page) * Math.max(1, pageSize);
                int to = Math.min(matches.size(), from + Math.max(1, pageSize));
                List<MediaItem> results = new ArrayList<>();
                for (int i = from; i < to; i++) results.add(buildMediaItem(stations.get(matches.get(i))));
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(results), params));
            }
        };

        // The session sees the player through this wrapper so that holding
        // the tune button on the steering wheel jumps groups. Many cars send
        // "fast forward" / "rewind" on a long press (instead of next/previous):
        // fast forward = next group, rewind = back (start of this group, then
        // the previous one), the same as the on-screen group buttons. Live
        // radio can't really be fast-forwarded, so those commands are always
        // offered, and every command the car sends is logged to show what
        // this particular car does.
        Player sessionPlayer = new ForwardingPlayer(player) {
            @Override
            public void seekForward() {
                if (carBandFilter != null) {
                    Log.i(TAG, "Car sent FAST FORWARD - band locked, jumping to the start of the band");
                    jumpBandStart();
                    return;
                }
                Log.i(TAG, "Car sent FAST FORWARD - jumping to the next group");
                jumpGroup(1);
            }

            @Override
            public void seekBack() {
                if (carBandFilter != null) {
                    Log.i(TAG, "Car sent REWIND - band locked, jumping to the start of the band");
                    jumpBandStart();
                    return;
                }
                Log.i(TAG, "Car sent REWIND - jumping back a group");
                jumpGroup(-1);
            }

            // Single-station next/previous: stepped by real band while a
            // filter is on (AM/FM, or DAB/Internet), station by station,
            // wrapping past either end of the whole dial - normal stepping
            // otherwise.
            @Override
            public void seekToNext() {
                Log.i(TAG, "Car sent NEXT");
                if (!stepWithinBand(1)) super.seekToNext();
            }

            @Override
            public void seekToNextMediaItem() {
                Log.i(TAG, "Car sent NEXT (item)");
                if (!stepWithinBand(1)) super.seekToNextMediaItem();
            }

            @Override
            public void seekToPrevious() {
                Log.i(TAG, "Car sent PREVIOUS");
                if (!stepWithinBand(-1)) super.seekToPrevious();
            }

            @Override
            public void seekToPreviousMediaItem() {
                Log.i(TAG, "Car sent PREVIOUS (item)");
                if (!stepWithinBand(-1)) super.seekToPreviousMediaItem();
            }

            // Always the station's own fixed details (name, real frequency,
            // group, logo), never anything the stream sends - so Now Playing
            // stays still instead of redrawing.
            @NonNull
            @Override
            public MediaMetadata getMediaMetadata() {
                MediaItem item = getCurrentMediaItem();
                return item != null ? item.mediaMetadata : super.getMediaMetadata();
            }

            @Override
            public boolean isCommandAvailable(int command) {
                if (command == Player.COMMAND_SEEK_FORWARD || command == Player.COMMAND_SEEK_BACK) return true;
                return super.isCommandAvailable(command);
            }

            @Override
            public Player.Commands getAvailableCommands() {
                return super.getAvailableCommands().buildUpon()
                        .add(Player.COMMAND_SEEK_FORWARD)
                        .add(Player.COMMAND_SEEK_BACK)
                        .build();
            }
        };
        mediaLibrarySession = new MediaLibrarySession.Builder(this, sessionPlayer, callback).build();
        // Also the session-wide layout: Android Auto reads its extra Now
        // Playing buttons from this (as the session's custom actions).
        mediaLibrarySession.setCustomLayout(customLayout());

        // Real station logos: look them up from the stations' websites in the
        // background. Each time some arrive, tell the car the station list
        // changed (at most every few seconds) so it shows them - the items'
        // logo addresses change from the drawn tile to the real logo.
        LogoFetcher.setListener(stationName -> stallWatchdogHandler.post(() -> {
            if (logoRefreshPending) return;
            logoRefreshPending = true;
            stallWatchdogHandler.postDelayed(() -> {
                logoRefreshPending = false;
                if (mediaLibrarySession != null) {
                    mediaLibrarySession.notifyChildrenChanged(ROOT_ID, stations.size(), null);
                }
            }, 4000);
        }));
        LogoFetcher.prefetchAll(this);

        // Show the last station on the car's player as soon as it connects,
        // ready to play (not auto-playing, and not connecting until asked).
        int savedIdx = combinedLastStationIndex();
        if (savedIdx >= 0) {
            player.setMediaItems(fullPlaylist(), savedIdx, 0L);
        }
    }

    private void initializeStations() {
        // Canonical station data now lives in StationLibrary, shared with
        // PlaybackService (the phone), so both surfaces play from exactly
        // the same 147-station list and can never drift apart.
        stations = StationLibrary.getStations();
    }

    private void checkForStall() {
        // playWhenReady, not isPlaying() - see PlaybackService.checkForStall.
        if (player == null || !player.getPlayWhenReady()) return;
        if (player.getPlaybackSuppressionReason() != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
            stallPollCount = 0; // phone call / nav prompt, not a stall
            return;
        }

        long position = player.getCurrentPosition();
        if (lastKnownPositionMs >= 0 && position - lastKnownPositionMs < 300) {
            stallPollCount++;
        } else {
            stallPollCount = 0;
        }
        lastKnownPositionMs = position;

        // A station that hasn't started yet is tuning in, not stalled: give
        // it ~16s instead of ~6s before reconnecting.
        int pollsNeeded = currentStationHasPlayed
                ? STALL_POLLS_BEFORE_RECONNECT : STALL_POLLS_BEFORE_FIRST_PLAY;
        if (stallPollCount >= pollsNeeded) {
            stallPollCount = 0;
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastStallReconnectAtMs > STALL_RECONNECT_COOLDOWN_MS) {
                lastStallReconnectAtMs = now;
                MediaItem current = player.getCurrentMediaItem();
                String stationId = current != null ? current.mediaId : "unknown";
                Log.w(TAG, "Detected a stalled stream on " + stationId
                        + " (no position progress while \"playing\") - reconnecting.");
                reconnectCurrentStation();
            }
        }
    }

    @Nullable
    @Override
    public MediaLibrarySession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return mediaLibrarySession;
    }

    @Override
    public void onDestroy() {
        stallWatchdogHandler.removeCallbacks(stallWatchdogTick);
        LogoFetcher.setListener(null);
        if (mediaLibrarySession != null) {
            mediaLibrarySession.getPlayer().release();
            mediaLibrarySession.release();
            mediaLibrarySession = null;
        }
        super.onDestroy();
    }
}
