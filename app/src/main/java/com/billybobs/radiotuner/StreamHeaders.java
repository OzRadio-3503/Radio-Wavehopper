package com.billybobs.radiotuner;

import android.net.Uri;

/**
 * Picks the User-Agent each stream request goes out with. Shared by both
 * playback services (phone and Android Auto).
 *
 * Most relays get a browser-like User-Agent: several (live365 - Pop 91.3 -
 * among them) reject ExoPlayer's generic library User-Agent outright.
 *
 * StreamTheWorld/Triton is the exception. With the browser User-Agent, every
 * connection to one of their stations (Power 100, 2GB, Newstalk ZB, 101.1,
 * ...) delivered almost exactly 2 seconds of audio and was then closed by the
 * server - 89,856 and 88,064 frames on two different stations at two
 * different bitrates, the same every reconnect. Triton's docs ask players to
 * send a User-Agent that identifies the app; RadioDroid (which plays these
 * stations fine) does exactly that, and it was the one remaining difference
 * between its requests and ours. An earlier per-station test of this never
 * actually took effect: the data source factory's own setUserAgent() is
 * applied after per-request headers and silently overrode it. So the
 * factories no longer set a User-Agent at all - this class is the only place
 * one comes from.
 */
public final class StreamHeaders {

    private StreamHeaders() {}

    public static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    public static final String APP_USER_AGENT =
            "BillyBobsRadioTuner/1.0 (Linux; Android "
                    + android.os.Build.VERSION.RELEASE + ")";

    public static String userAgentFor(Uri uri) {
        String host = uri != null ? uri.getHost() : null;
        if (host != null && (host.endsWith("streamtheworld.com") || host.endsWith("tritondigital.com"))) {
            return APP_USER_AGENT;
        }
        return BROWSER_USER_AGENT;
    }
}
