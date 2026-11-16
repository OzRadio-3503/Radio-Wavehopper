package com.billybobs.radiotuner;

import android.os.SystemClock;

/**
 * Makes "play" after a pause go back to live radio instead of carrying on
 * from where it was paused.
 *
 * While paused, ExoPlayer keeps the audio it had already downloaded (up to
 * about 50 seconds) and, on resume, plays that first - so after a pause you
 * hear old audio, then possibly a gap when the server has long since dropped
 * the idle connection. The same happens when a phone call or navigation
 * prompt silences the radio for a while (playback suppression).
 *
 * This only decides WHEN to jump; each service does the jump on its own
 * player (the phone and the car use different ExoPlayer libraries). Jumping
 * means seekToDefaultPosition: for a live HLS stream that is the live edge,
 * and for a plain Icecast/Shoutcast stream (not seekable) ExoPlayer opens a
 * fresh connection, which is live too.
 */
final class LiveResume {
    /** Pauses shorter than this resume instantly from the buffer. */
    static final long JUMP_AFTER_MS = 5_000;

    /** When the radio last went quiet (pause or suppression); 0 = it hasn't. */
    private long quietSinceMs = 0;

    /** Call from onPlayWhenReadyChanged. True = jump to live now. */
    boolean onPlayWhenReadyChanged(boolean playWhenReady) {
        if (!playWhenReady) {
            markQuiet();
            return false;
        }
        return takeJump();
    }

    /** Call from onPlaybackSuppressionReasonChanged. True = jump to live now. */
    boolean onSuppressionChanged(boolean suppressed, boolean playWhenReady) {
        if (suppressed) {
            markQuiet();
            return false;
        }
        return playWhenReady && takeJump();
    }

    /**
     * Call from onPlaybackStateChanged. Android Auto may stop() the player
     * (IDLE) when the car switches off, rather than pausing it, and then
     * prepare() it again when the car comes back - so going IDLE counts as
     * going quiet, and leaving IDLE counts as resuming. True = jump to live now.
     */
    boolean onStateChanged(boolean idle, boolean wasIdle, boolean playWhenReady) {
        if (idle) {
            markQuiet();
            return false;
        }
        return wasIdle && playWhenReady && takeJump();
    }

    /** A new station starts with a fresh connection anyway. */
    void reset() {
        quietSinceMs = 0;
    }

    private void markQuiet() {
        if (quietSinceMs == 0) quietSinceMs = SystemClock.elapsedRealtime();
    }

    private boolean takeJump() {
        long since = quietSinceMs;
        quietSinceMs = 0;
        return since != 0 && SystemClock.elapsedRealtime() - since >= JUMP_AFTER_MS;
    }
}
