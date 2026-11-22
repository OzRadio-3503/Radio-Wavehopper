package com.billybobs.radiotuner;

import java.util.ArrayList;
import java.util.List;

/**
 * The single canonical list of 151 stations (frequency, name, stream URL),
 * shared by every native playback surface — PlaybackService (the phone) and
 * RadioLibraryService (Android Auto) — so there is exactly one place this
 * data lives and they can never drift apart. The page's own JS keeps its own
 * copy for display (the dial, presets, "nearest station" lookups), which
 * must stay in sync with this list by frequency/name for the phone's
 * JS-tells-native-which-frequency-to-play handoff to resolve correctly.
 */
public final class StationLibrary {

    private StationLibrary() {}

    public static class Station {
        public final double freq;
        public final String name;
        public final String real;
        public final String url;
        /**
         * Null for almost every station - ExoPlayer auto-sniffs the format
         * from the URL/content, same as before. Set only for a station whose
         * stream ExoPlayer mis-detects (e.g. an ad-stitched relay that plays
         * fine in a browser/WebView but loses sync partway through in
         * ExoPlayer) - this forces it to commit to one extractor instead of
         * guessing, without touching every other station's behavior.
         */
        public final String mimeType;
        /**
         * True only for the handful of ad-stitched StreamTheWorld relays
         * (Power 100, 3PBS, 2GB so far) where ExoPlayer's native AudioTrack
         * reliably gets stopped partway through and never restarted, no
         * matter the networking stack, engine version, or extractor config
         * - all tried and ruled out. These stations instead play through
         * Android's plain android.media.MediaPlayer in PlaybackService,
         * which is a genuinely different decode path. Every other station
         * is untouched and keeps using ExoPlayer as before.
         */
        public final boolean forceMediaPlayer;

        /**
         * Where the station is: the state for Australian stations (NSW, ACT,
         * VIC, QLD, SA, WA, TAS, NT, or AU for national ones), otherwise the
         * country (NZ, UK, US, ...). Shown after the real frequency.
         * Must match the page's cc values.
         */
        public final String region;

        /** True for any Australian station (a state, ACT or AU). */
        public boolean isAustralian() { return Regions.AUSTRALIAN.contains(region); }

        public Station(double freq, String name, String real, String url) {
            this(freq, name, real, url, null, false);
        }

        public Station(double freq, String name, String real, String url, String mimeType) {
            this(freq, name, real, url, mimeType, false);
        }

        public Station(double freq, String name, String real, String url, String mimeType, boolean forceMediaPlayer) {
            this(freq, name, real, url, mimeType, forceMediaPlayer, Regions.forName(name));
        }

        /** For stations the listener added: the region comes from the Add form, not the name table. */
        public Station(double freq, String name, String real, String url, String mimeType, boolean forceMediaPlayer, String region) {
            this.freq = freq;
            this.name = name;
            this.real = real;
            this.url = url;
            this.mimeType = mimeType;
            this.forceMediaPlayer = forceMediaPlayer;
            this.region = region == null ? "" : region;
        }
    }

    /** Station name -> region code (see Station.region). Kept in step with the page's cc values. */
    static final class Regions {
        static final java.util.Set<String> AUSTRALIAN = new java.util.HashSet<>(java.util.Arrays.asList(
                "AU", "NSW", "ACT", "VIC", "QLD", "SA", "WA", "TAS", "NT"));
        private static final java.util.Map<String, String> BY_NAME = new java.util.HashMap<>();
        static {
            BY_NAME.put("talkSPORT", "UK");
            BY_NAME.put("SENQ", "AU");
            BY_NAME.put("Triple M Classic Rock", "VIC");
            BY_NAME.put("Yacht Rock", "AU");
            BY_NAME.put("The Sound Auckland", "NZ");
            BY_NAME.put("SEN 1116", "VIC");
            BY_NAME.put("WNYC", "US");
            BY_NAME.put("2UE 954", "NSW");
            BY_NAME.put("SEN 1170", "NSW");
            BY_NAME.put("Sports Nation NZ", "NZ");
            BY_NAME.put("ABC Sports", "AU");
            BY_NAME.put("BBC Radio 5 Sports Extra", "UK");
            BY_NAME.put("talkSPORT 2", "UK");
            BY_NAME.put("ABC Cricket", "AU");
            BY_NAME.put("Guerilla Cricket", "UK");
            BY_NAME.put("SEN Fanatic", "AU");
            BY_NAME.put("ABC News", "NSW");
            BY_NAME.put("CBC Radio One Vancouver", "CA");
            BY_NAME.put("ABC SYD", "NSW");
            BY_NAME.put("KHVH 830 AM", "US");
            BY_NAME.put("2GB Sydney", "NSW");
            BY_NAME.put("2SM 1269", "NSW");
            BY_NAME.put("RTE Radio 1", "IE");
            BY_NAME.put("Money FM Singapore", "SG");
            BY_NAME.put("Newstalk ZB Auckland", "NZ");
            BY_NAME.put("Talk 702 Johannesburg", "ZA");
            BY_NAME.put("BBC Radio 4", "UK");
            BY_NAME.put("LBC London", "UK");
            BY_NAME.put("RNZ National", "NZ");
            BY_NAME.put("Radio Australia", "AU");
            BY_NAME.put("BBC World Service", "UK");
            BY_NAME.put("SKY News Radio", "AU");
            BY_NAME.put("2WEB", "NSW");
            BY_NAME.put("2RDJ", "NSW");
            BY_NAME.put("2RRR Ryde", "NSW");
            BY_NAME.put("2BRW", "NSW");
            BY_NAME.put("2BLU Katoomba", "NSW");
            BY_NAME.put("2GLF", "NSW");
            BY_NAME.put("Valley FM", "ACT");
            BY_NAME.put("Alive 90.5", "NSW");
            BY_NAME.put("2MCE Bathurst", "NSW");
            BY_NAME.put("2YYY Young", "NSW");
            BY_NAME.put("2MIA Griffith", "NSW");
            BY_NAME.put("2TVR Sounds Of The Mountains", "NSW");
            BY_NAME.put("Coast FM Gosford", "NSW");
            BY_NAME.put("2GHR Holbrook", "NSW");
            BY_NAME.put("Northside Radio", "NSW");
            BY_NAME.put("2SSR", "NSW");
            BY_NAME.put("SWR FM Blacktown", "NSW");
            BY_NAME.put("2HHH", "NSW");
            BY_NAME.put("2MCR", "NSW");
            BY_NAME.put("Yass FM", "NSW");
            BY_NAME.put("Connect FM Bankstown", "NSW");
            BY_NAME.put("Great Lakes FM", "NSW");
            BY_NAME.put("Nim FM Nimbin", "NSW");
            BY_NAME.put("RAM FM Goulburn", "NSW");
            BY_NAME.put("2NUR", "NSW");
            BY_NAME.put("Triple U FM", "NSW");
            BY_NAME.put("2SEA Eden", "NSW");
            BY_NAME.put("2EC", "NSW");
            BY_NAME.put("2NVR Nambucca Heads", "NSW");
            BY_NAME.put("VOX FM", "NSW");
            BY_NAME.put("2AAA Wagga", "NSW");
            BY_NAME.put("Highland FM Mittagong", "NSW");
            BY_NAME.put("2BBB Bellingen", "NSW");
            BY_NAME.put("2EAR Moruya", "NSW");
            BY_NAME.put("2OCW Orange", "NSW");
            BY_NAME.put("3MFM", "VIC");
            BY_NAME.put("Southern FM", "VIC");
            BY_NAME.put("Plenty Valley FM", "VIC");
            BY_NAME.put("WYN FM", "VIC");
            BY_NAME.put("Pulse FM Geelong", "VIC");
            BY_NAME.put("Joy FM Melbourne", "VIC");
            BY_NAME.put("Main FM", "VIC");
            BY_NAME.put("Inner FM", "VIC");
            BY_NAME.put("3MDR", "VIC");
            BY_NAME.put("Casey Radio", "VIC");
            BY_NAME.put("3RPP", "VIC");
            BY_NAME.put("Yarra Valley FM", "VIC");
            BY_NAME.put("Sunbury Radio", "VIC");
            BY_NAME.put("3MGB Mallacoota", "VIC");
            BY_NAME.put("3RRR FM", "VIC");
            BY_NAME.put("3PBS FM", "VIC");
            BY_NAME.put("4KZ", "QLD");
            BY_NAME.put("4AM", "QLD");
            BY_NAME.put("4BRZ", "QLD");
            BY_NAME.put("Goldie FM", "QLD");
            BY_NAME.put("Rebel FM", "QLD");
            BY_NAME.put("Bridge FM", "QLD");
            BY_NAME.put("4RIM Boonah", "QLD");
            BY_NAME.put("Bay FM Redlands", "QLD");
            BY_NAME.put("Power 100", "QLD");
            BY_NAME.put("Logan Radio", "QLD");
            BY_NAME.put("Noosa FM", "QLD");
            BY_NAME.put("4OUR Caboolture", "QLD");
            BY_NAME.put("4ZZZ", "QLD");
            BY_NAME.put("Sunshine FM", "QLD");
            BY_NAME.put("Coast FM Adelaide", "SA");
            BY_NAME.put("Lofty Radio", "SA");
            BY_NAME.put("Fleurieu FM", "SA");
            BY_NAME.put("5PBA FM", "SA");
            BY_NAME.put("Happy FM", "SA");
            BY_NAME.put("5KIX Kangaroo Island", "SA");
            BY_NAME.put("5ZZZ", "SA");
            BY_NAME.put("Three D Radio", "SA");
            BY_NAME.put("Flow FM", "SA");
            BY_NAME.put("5WOW", "SA");
            BY_NAME.put("VCA FM Ellenbrook", "WA");
            BY_NAME.put("Radio Kalamunda", "WA");
            BY_NAME.put("Twin Cities FM", "WA");
            BY_NAME.put("Wave Mandurah", "WA");
            BY_NAME.put("Curtin Radio", "WA");
            BY_NAME.put("Bunbury Community Radio", "WA");
            BY_NAME.put("H FM Armadale", "WA");
            BY_NAME.put("Radio Fremantle", "WA");
            BY_NAME.put("Star FM St Helens", "TAS");
            BY_NAME.put("Huon FM", "TAS");
            BY_NAME.put("Hobart FM", "TAS");
            BY_NAME.put("MV FM", "TAS");
            BY_NAME.put("Tasman FM", "TAS");
            BY_NAME.put("TYGA FM", "TAS");
            BY_NAME.put("City Park Radio", "TAS");
            BY_NAME.put("7DBS Coast FM", "TAS");
            BY_NAME.put("Territory FM", "NT");
            BY_NAME.put("Triple J Hottest", "AU");
            BY_NAME.put("Radio Dunedin", "NZ");
            BY_NAME.put("Brian FM Oamaru", "NZ");
            BY_NAME.put("Radio Hauraki Westport", "NZ");
            BY_NAME.put("The Rock Auckland", "NZ");
            BY_NAME.put("Channel X Queenstown", "NZ");
            BY_NAME.put("NC 1’ere", "NC");
            BY_NAME.put("Gold FM Fiji", "FJ");
            BY_NAME.put("Gold FM Singapore", "SG");
            BY_NAME.put("Magic 828", "ZA");
            BY_NAME.put("LM Radio", "ZA");
            BY_NAME.put("Radio Caroline", "UK");
            BY_NAME.put("BBC Radio 2", "UK");
            BY_NAME.put("FIP Marseille", "FR");
            BY_NAME.put("Rock Antenne", "DE");
            BY_NAME.put("KBRW", "US");
            BY_NAME.put("KOTZ", "US");
            BY_NAME.put("WION", "US");
            BY_NAME.put("KFAI", "US");
            BY_NAME.put("WFMU", "US");
            BY_NAME.put("Pop 91.3", "US");
            BY_NAME.put("WLNG", "US");
            BY_NAME.put("Aircheck Radio", "US");
            BY_NAME.put("Alt Boss Gold", "US");
            BY_NAME.put("Casey Kasem Classic AT40", "US");
            BY_NAME.put("Powerzone", "AU");
            BY_NAME.put("Radio Free Phoenix", "US");
            BY_NAME.put("Retro Hit Radio", "NZ");
            BY_NAME.put("Retro Stream (weekends only)", "NSW");
            BY_NAME.put("Station X", "NSW");
            BY_NAME.put("Forever Classic 60s", "NSW");
            BY_NAME.put("XLFM", "NSW");
            BY_NAME.put("Vintage FM", "NSW");
            BY_NAME.put("Forever Classic 70s", "NSW");
            BY_NAME.put("The Fresh Mix", "AU");
        }
        static String forName(String name) {
            String r = BY_NAME.get(name);
            return r != null ? r : "";
        }
    }

    /**
     * Old-layout frequency -> new frequency, from when the dial was
     * reorganised into groups. Used once to move saved state (Android Auto's
     * last station) to where that station now sits.
     */
    private static final String LAYOUT1_TO_LAYOUT2 = "93.7=87.5,92.9=87.6,90.3=87.7,89.9=87.8,91.7=87.9,94.3=88.0,95.8=88.1,89.7=88.2,95.5=88.3,101.5=88.4,89.4=88.5,92.0=88.6,94.2=88.7,88.2=88.8,93.5=88.9,94.4=89.0,91.5=89.1,93.1=89.2,93.3=89.3,101.6=89.4,101.0=89.5,87.5=89.6,96.0=89.7,96.4=89.8,96.8=89.9,96.6=90.0,96.7=90.1,93.0=90.2,96.2=90.3,100.9=90.4,97.0=90.5,96.9=90.6,96.3=90.7,91.9=90.8,96.5=90.9,96.1=91.0,93.8=91.1,93.9=91.2,94.0=91.3,94.1=91.4,90.5=91.5,90.4=91.6,92.8=91.7,88.9=91.8,93.6=91.9,91.8=92.0,91.0=92.1,91.6=92.2,92.2=92.3,92.4=92.4,90.6=92.5,92.6=92.6,92.3=92.7,90.1=92.8,89.8=92.9,89.5=93.0,90.7=93.1,88.7=93.2,100.8=93.3,97.3=93.4,88.6=93.5,97.9=93.6,97.1=93.7,97.2=93.8,97.5=93.9,97.6=94.0,97.7=94.1,97.8=94.2,98.0=94.3,95.9=94.4,97.4=94.5,90.8=94.6,99.0=94.7,98.2=94.8,98.4=94.9,98.7=95.0,98.6=95.1,98.1=95.2,92.5=95.3,98.3=95.4,98.5=95.5,98.8=95.6,98.9=95.7,101.3=95.8,101.2=95.9,101.4=96.0,99.8=96.1,99.7=96.2,100.0=96.3,99.9=96.4,99.6=96.5,99.1=96.6,99.2=96.7,99.3=96.8,99.5=96.9,99.4=97.0,100.1=97.1,100.6=97.2,100.2=97.3,100.3=97.4,100.4=97.5,100.7=97.6,100.5=97.7,101.1=97.8,95.0=97.9,95.3=98.0,95.1=98.1,95.2=98.2,94.8=98.3,94.6=98.4,94.7=98.5,94.9=98.6,88.5=98.7,88.4=98.8,102.0=98.9,91.4=99.0,90.2=99.1,89.3=99.2,91.2=99.3,88.3=99.4,88.8=99.5,89.2=99.6,89.0=99.7,101.7=99.8,93.2=99.9,93.4=100.0,89.1=100.1,88.0=100.2,90.9=100.3,87.9=100.4,91.1=100.5,94.5=100.6,92.1=100.7,92.7=100.8,95.7=100.9,95.6=101.0,91.3=101.1,102.1=101.2,88.1=101.3,87.8=101.4,87.7=101.5,87.6=101.6,89.6=101.7,90.0=101.8,95.4=101.9,101.8=102.0,101.9=102.1";

    /** New frequency (e.g. "100.2") for an old-layout one (e.g. "88.0"), or null. */
    public static String layout2FreqFor(String oldFreq) {
        for (String pair : LAYOUT1_TO_LAYOUT2.split(",")) {
            int eq = pair.indexOf('=');
            if (pair.substring(0, eq).equals(oldFreq)) return pair.substring(eq + 1);
        }
        return null;
    }

    /** Layout 2 -> 3: Guerilla Cricket added at 88.0, stations shifted up, RNZ Concert removed. */
    private static final String LAYOUT2_TO_LAYOUT3 = "88.4=88.1,88.0=88.2,88.1=88.3,88.2=88.4,88.3=88.5,88.5=88.6,88.6=88.7,88.7=88.8,88.8=88.9,88.9=89.0,89.0=89.1,89.1=89.2,89.2=89.3,89.3=89.4,89.4=89.5,89.5=89.6,89.6=89.7,89.7=89.8,89.8=89.9,89.9=90.0,90.0=90.1,90.1=90.2,90.2=90.3,90.3=90.4,90.4=90.5,90.5=90.6,90.6=90.7,90.7=90.8,90.8=90.9,90.9=91.0,91.0=91.1,91.1=91.2,91.2=91.3,91.3=91.4,91.4=91.5,91.5=91.6,91.6=91.7,91.7=91.8,91.8=91.9,91.9=92.0,92.0=92.1,92.1=92.2,92.2=92.3,92.3=92.4,92.4=92.5,92.5=92.6,92.6=92.7,92.7=92.8,92.8=92.9,92.9=93.0,93.0=93.1,93.1=93.2,93.2=93.3,93.3=93.4,93.4=93.5,93.5=93.6,93.6=93.7,93.7=93.8,93.8=93.9,93.9=94.0,94.0=94.1,94.1=94.2,94.2=94.3,94.3=94.4,94.4=94.5,94.5=94.6,94.6=94.7,94.7=94.8,94.8=94.9,94.9=95.0,95.0=95.1,95.1=95.2,95.2=95.3,95.3=95.4,95.4=95.5,95.5=95.6,95.6=95.7,95.7=95.8,95.8=95.9,95.9=96.0,96.0=96.1,96.1=96.2,96.2=96.3,96.3=96.4,96.4=96.5,96.5=96.6,96.6=96.7,96.7=96.8,96.8=96.9,96.9=97.0,97.0=97.1,97.1=97.2,97.2=97.3,97.3=97.4,97.4=97.5,97.5=97.6,97.6=97.7,97.7=97.8,97.8=97.9,97.9=98.0,98.0=98.1,98.1=98.2,98.2=98.3,98.3=98.4,98.4=98.5,98.5=98.6,98.6=98.7,98.7=98.8,98.8=98.9,98.9=99.0";

    /** New frequency for a layout-2 one, or null if that station didn't move. */
    public static String layout3FreqFor(String layout2Freq) {
        for (String pair : LAYOUT2_TO_LAYOUT3.split(",")) {
            int eq = pair.indexOf('=');
            if (pair.substring(0, eq).equals(layout2Freq)) return pair.substring(eq + 1);
        }
        return null;
    }

    /** Layout 3 -> 4: SEN Fanatic added at 88.1, China Radio International removed. */
    private static final String LAYOUT3_TO_LAYOUT4 = "88.1=88.2,88.2=88.3,88.3=88.4,88.4=88.5,88.5=88.6,88.6=88.7,88.7=88.8,88.8=88.9,88.9=89.0,89.0=89.1,89.1=89.2,89.2=89.3,89.3=89.4,89.4=89.5,89.5=89.6";

    /** New frequency for a layout-3 one, or null if that station didn't move. */
    public static String layout4FreqFor(String layout3Freq) {
        for (String pair : LAYOUT3_TO_LAYOUT4.split(",")) {
            int eq = pair.indexOf('=');
            if (pair.substring(0, eq).equals(layout3Freq)) return pair.substring(eq + 1);
        }
        return null;
    }

    /** Layout 4 -> 5: talkSPORT and talkSPORT 2 added at 88.2/88.3, World Radio Network and Double J removed. */
    private static final String LAYOUT4_TO_LAYOUT5 = "88.2=88.4,88.3=88.5,88.4=88.6,88.5=88.7,88.6=88.8,88.7=88.9,88.8=89.0,88.9=89.1,89.0=89.2,89.1=89.3,89.2=89.4,89.3=89.5,89.4=89.6,89.5=89.7,89.6=89.8,89.7=89.9,89.8=90.0,89.9=90.1,90.0=90.2,90.1=90.3,90.2=90.4,90.3=90.5,90.4=90.6,90.5=90.7,90.6=90.8,90.7=90.9,90.8=91.0,90.9=91.1,91.0=91.2,91.1=91.3,91.2=91.4,91.3=91.5,91.4=91.6,91.5=91.7,91.6=91.8,91.7=91.9,91.8=92.0,91.9=92.1,92.0=92.2,92.1=92.3,92.2=92.4,92.3=92.5,92.4=92.6,92.5=92.7,92.6=92.8,92.7=92.9,92.8=93.0,92.9=93.1,93.0=93.2,93.1=93.3,93.2=93.4,93.3=93.5,93.4=93.6,93.5=93.7,93.6=93.8,93.7=93.9,93.8=94.0,93.9=94.1,94.0=94.2,94.1=94.3,94.2=94.4,94.3=94.5,94.4=94.6,94.5=94.7,94.6=94.8,94.7=94.9,94.8=95.0,94.9=95.1,95.0=95.2,95.1=95.3,95.2=95.4,95.3=95.5,95.4=95.6,95.5=95.7,95.6=95.8,95.7=95.9,95.8=96.0,95.9=96.1,96.0=96.2,96.1=96.3,96.2=96.4,96.3=96.5,96.4=96.6,96.5=96.7,96.6=96.8,96.7=96.9,96.8=97.0,96.9=97.1,97.0=97.2,97.1=97.3,97.2=97.4,97.3=97.5,97.4=97.6,97.5=97.7,97.6=97.8,97.7=97.9,97.8=98.0,97.9=98.1,98.0=98.2,98.1=98.3,98.2=98.4,98.3=98.5,98.4=98.6,98.5=98.7,98.6=98.8,98.7=98.9,98.8=99.0,98.9=99.1,99.1=99.2,99.2=99.3,99.3=99.4,99.4=99.5,99.5=99.6,99.6=99.7,99.7=99.8,99.8=99.9,99.9=100.0,100.0=100.1,100.1=100.2,100.2=100.3,100.3=100.4,100.4=100.5,100.5=100.6,100.6=100.7,100.7=100.8,100.8=100.9,100.9=101.0,101.0=101.1,101.1=101.2,101.2=101.3,101.3=101.4,101.4=101.5,101.5=101.6,101.6=101.7,101.7=101.8,101.8=101.9";

    /** New frequency for a layout-4 one, or null if that station didn't move. */
    public static String layout5FreqFor(String layout4Freq) {
        for (String pair : LAYOUT4_TO_LAYOUT5.split(",")) {
            int eq = pair.indexOf('=');
            if (pair.substring(0, eq).equals(layout4Freq)) return pair.substring(eq + 1);
        }
        return null;
    }

    /** Layout 5 -> 6: SKY News Radio moved to 88.9 to group with Australian talk/news. */
    private static final String LAYOUT5_TO_LAYOUT6 = "89.7=88.9,88.9=89.0,89.0=89.1,89.1=89.2,89.2=89.3,89.3=89.4,89.4=89.5,89.5=89.6,89.6=89.7";

    /** New frequency for a layout-5 one, or null if that station didn't move. */
    public static String layout6FreqFor(String layout5Freq) {
        for (String pair : LAYOUT5_TO_LAYOUT6.split(",")) {
            int eq = pair.indexOf('=');
            if (pair.substring(0, eq).equals(layout5Freq)) return pair.substring(eq + 1);
        }
        return null;
    }

    /** Layout 6 -> 7: Stations reordered within sections by real frequency (AM/FM/DAB/Internet). */
    private static final String LAYOUT6_TO_LAYOUT7 = "87.5=87.6,87.6=87.8,87.7=88.1,87.8=87.7,88.0=88.2,88.1=88.3,88.2=87.5,88.3=88.0,88.4=89.7,88.5=88.8,88.6=88.9,88.7=88.4,88.8=88.6,88.9=89.9,89.0=89.2,89.1=89.6,89.2=89.5,89.3=89.8,89.5=89.0,89.6=88.5,89.7=89.3,89.8=89.1,89.9=88.7,90.0=91.4,90.1=90.2,90.2=91.7,90.3=90.1,90.4=91.5,90.5=91.6,90.6=90.7,90.7=92.0,90.8=90.5,90.9=91.8,91.0=91.2,91.1=92.4,91.2=92.8,91.3=90.4,91.4=92.2,91.5=93.1,91.6=92.7,91.7=92.1,91.8=90.0,91.9=90.8,92.0=93.3,92.1=90.3,92.2=91.0,92.3=90.9,92.4=91.9,92.5=92.9,92.6=91.1,92.7=91.3,92.8=93.0,92.9=92.3,93.0=92.5,93.1=93.2,93.2=92.6,93.3=90.6,93.4=94.8,93.5=94.9,93.6=93.9,93.7=93.5,93.8=93.6,93.9=94.1,94.0=94.2,94.1=94.6,94.2=94.5,94.3=93.7,94.4=94.3,94.5=94.4,94.6=93.8,94.7=94.0,94.8=93.4,94.9=94.7,95.0=96.2,95.1=95.9,95.2=95.7,95.3=95.2,95.4=96.1,95.5=95.3,95.6=95.4,95.7=95.6,95.8=95.5,95.9=96.0,96.0=96.3,96.1=95.8,96.2=95.0,96.3=95.1,96.4=97.1,96.5=96.4,96.6=96.7,96.7=97.3,96.8=97.0,96.9=96.5,97.0=96.8,97.1=96.6,97.3=96.9,97.4=97.8,97.5=98.1,97.6=97.4,97.7=97.6,97.8=97.5,97.9=98.0,98.0=97.7,98.1=97.9,98.2=98.4,98.4=98.6,98.5=98.7,98.6=98.8,98.7=98.5,98.8=98.9,98.9=98.2,99.2=99.5,99.3=99.4,99.4=99.6,99.5=99.2,99.6=99.3,99.7=99.8,99.8=99.7,100.0=100.1,100.1=100.0,100.2=100.3,100.3=100.2,100.6=101.0,100.7=100.9,100.8=101.1,100.9=100.8,101.0=100.6,101.1=100.7,101.2=101.5,101.3=101.4,101.4=101.8,101.5=101.3,101.7=101.2,101.8=102.0,101.9=101.7,102.0=102.1,102.1=101.9";

    /** New frequency for a layout-6 one, or null if that station didn't move. */
    public static String layout7FreqFor(String layout6Freq) {
        for (String pair : LAYOUT6_TO_LAYOUT7.split(",")) {
            int eq = pair.indexOf('=');
            if (pair.substring(0, eq).equals(layout6Freq)) return pair.substring(eq + 1);
        }
        return null;
    }

    /** Layout 7 -> 8: Pop 91.3 moved from Internet only into USA (as 91.3 FM). */
    private static final String LAYOUT7_TO_LAYOUT8 = "101.1=101.2,101.5=101.1,101.2=101.3,101.3=101.4,101.4=101.5";

    /** New frequency for a layout-7 one, or null if that station didn't move. */
    public static String layout8FreqFor(String layout7Freq) {
        for (String pair : LAYOUT7_TO_LAYOUT8.split(",")) {
            int eq = pair.indexOf('=');
            if (pair.substring(0, eq).equals(layout7Freq)) return pair.substring(eq + 1);
        }
        return null;
    }

    /**
     * The blocks of the dial, in order: {first station's frequency, name}.
     * Must match the "// ---- Group ----" headings in getStations() below and
     * STATION_GROUPS in index.html. Used by Android Auto's group buttons and
     * to colour the generated station logos.
     */
    public static final String[][] GROUPS = {
        {"87.5", "Sport"},
        {"88.4", "Talk"},
        {"90.0", "NSW (and ACT) \u2013 music & community"},
        {"93.4", "Victoria \u2013 music & community"},
        {"95.0", "Queensland \u2013 music & community"},
        {"96.4", "South Australia \u2013 music & community"},
        {"97.4", "Western Australia \u2013 music & community"},
        {"98.2", "Tasmania \u2013 music & community"},
        {"99.0", "Northern Territory \u2013 music & community"},
        {"99.1", "Australia (national)"},
        {"99.2", "New Zealand"},
        {"99.7", "Pacific"},
        {"99.9", "Singapore"},
        {"100.0", "South Africa"},
        {"100.2", "UK"},
        {"100.4", "France"},
        {"100.5", "Germany"},
        {"100.6", "USA"},
        {"101.3", "Internet only"}
    };

    /** Index into GROUPS of the group a station (by dial frequency) belongs to. */
    public static int groupIndexFor(double freq) {
        int g = 0;
        for (int i = 0; i < GROUPS.length; i++) {
            if (Double.parseDouble(GROUPS[i][0]) <= freq + 1e-9) g = i;
        }
        return g;
    }

    /**
     * Index into the station list of the first station in GROUPS[group], or -1
     * if none is showing there. (Not only one on the group's exact start: that
     * one may be hidden by a list or a hidden category.)
     */
    public static int firstStationOfGroup(List<Station> stations, int group) {
        double start = Double.parseDouble(GROUPS[group][0]);
        double end = group + 1 < GROUPS.length ? Double.parseDouble(GROUPS[group + 1][0]) : Double.MAX_VALUE;
        for (int i = 0; i < stations.size(); i++) {
            double f = stations.get(i).freq;
            if (f >= start - 1e-6 && f < end - 1e-6) return i;
        }
        return -1;
    }

    private static final java.util.regex.Pattern REAL_FREQ_PATTERN =
            java.util.regex.Pattern.compile("^([\\d.]+) (FM|AM)$");

    /**
     * The station's real band - "am", "fm", "dab" or "net" (internet-only) -
     * read from its {@link Station#real} label. Mirrors realBandFor() in the
     * page's own JS (index.html), so Android Auto's band filter agrees with
     * the phone's band lock about what counts as what.
     */
    public static String realBand(Station s) {
        if (s.real != null) {
            java.util.regex.Matcher m = REAL_FREQ_PATTERN.matcher(s.real);
            if (m.matches()) return m.group(2).equals("FM") ? "fm" : "am";
            if (s.real.equals("DAB only")) return "dab";
        }
        return "net";
    }

    /**
     * The real frequency value matching {@link #realBand}, or null for a
     * DAB/Internet station (no single comparable number). Used for voice
     * tuning by real frequency ("tune to 97.3 FM" / "873 AM").
     */
    public static Double realFreqValue(Station s) {
        if (s.real == null) return null;
        java.util.regex.Matcher m = REAL_FREQ_PATTERN.matcher(s.real);
        return m.matches() ? Double.valueOf(m.group(1)) : null;
    }

    /**
     * The phone's last station, found by its saved name first and only then
     * by its saved dial spot. A station can move on the dial (e.g. when one of
     * the listener's stations takes a block's starting spot), so the spot
     * alone could point at its neighbour. Null if nothing has been played.
     */
    public static Station savedPhoneStation(android.content.Context ctx) {
        android.content.SharedPreferences p =
                ctx.getSharedPreferences("RadioPrefs", android.content.Context.MODE_PRIVATE);
        String title = p.getString("last_station_title", "");
        double freq = Double.longBitsToDouble(p.getLong("last_station_freq_bits", Double.doubleToLongBits(-1)));
        UserStations.ensure(ctx);
        List<Station> all = getStations();
        if (title != null && !title.isEmpty()) {
            for (Station s : all) if (title.equals(s.name)) return s;
        }
        if (freq < 0) return null;
        Station best = null;
        double bestDist = Double.MAX_VALUE;
        for (Station s : all) {
            double d = Math.abs(s.freq - freq);
            if (d < bestDist) { bestDist = d; best = s; }
        }
        return best;
    }

    /**
     * The stations that show, in dial order: built-ins plus the listener's
     * own - or, when the phone's list is on, only that list's stations. Same rules as the page; if nothing would
     * be left, everything shows rather than nothing.
     */
    public static List<Station> getStations() {
        return showing(getAllStations(), UserStations.phoneShowing());
    }

    /** The stations Android Auto shows: as getStations(), but with the car's own list choice. */
    public static List<Station> getCarStations() {
        return showing(getAllStations(), UserStations.carShowing());
    }

    private static List<Station> showing(List<Station> all, UserStations.Showing list) {
        // All stations, or only your list's (My phone stations / My car
        // stations). Categories are no longer ticked or hidden: settings saved
        // by an older version that still carry them are simply ignored.
        if (list == null) return all;
        List<Station> keep = new java.util.ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>(list.stations);
        for (Station s : all) if (names.contains(s.name)) keep.add(s);
        return keep.isEmpty() ? all : keep;
    }

    /** Every station, hidden ones included, in dial order. */
    public static List<Station> getAllStations() {
        List<Station> stations = getBuiltInStations();
        UserStations.applyOverrides(stations);
        addUserStations(stations, UserStations.added());
        java.util.Collections.sort(stations, (a, b) -> Double.compare(a.freq, b.freq));
        return stations;
    }

    private static String normUrl(String u) {
        String s = u == null ? "" : u.trim().toLowerCase(java.util.Locale.ROOT);
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        s = s.replaceFirst("^https?://", "");
        return s.replaceAll("/+$", "");
    }

    /**
     * Adds the listener's own stations to the built-in list. The page tidies the saved
     * list when it loads (drops a station the app now ships, moves one that a built-in
     * now sits on). This is the same check for the moments before the page has run after
     * an update - Android Auto or a widget - and it never changes what is saved: a
     * duplicate is skipped, and a clash nudges the station up to the next free hundredth.
     */
    private static void addUserStations(List<Station> stations, List<Station> mine) {
        java.util.Set<String> urls = new java.util.HashSet<>();
        java.util.Set<Long> used = new java.util.HashSet<>();
        for (Station s : stations) { urls.add(normUrl(s.url)); used.add(Math.round(s.freq * 100)); }
        for (Station u : mine) {
            if (urls.contains(normUrl(u.url))) continue;
            long k = Math.round(u.freq * 100);
            // A listener's station may hold a block's starting spot (the page
            // puts one there when it comes first in frequency order, and moves
            // the block's built-in first station up); it only moves if taken.
            Station put = u;
            if (used.contains(k)) {
                long n = k + 1;
                while (n < 10210 && used.contains(n)) n++;
                if (n < 10210) put = new Station(n / 100.0, u.name, u.real, u.url, null, false, u.region);
                k = n;
            }
            used.add(k);
            stations.add(put);
        }
    }

    public static List<Station> getBuiltInStations() {
        List<Station> stations = new ArrayList<>();
        // ---- Sport ----
        stations.add(new Station(87.5, "SENQ", "693 AM", "https://d2dgwrtikgx7lx.cloudfront.net/playlist.m3u8"));
        stations.add(new Station(87.52, "talkSPORT", "1089 AM", "http://23163.live.streamtheworld.com/NOVA_TALKSPORT_1.mp3?dist=talksport"));
        stations.add(new Station(87.55, "SEN 1116", "1116 AM", "http://13.54.221.214:8000/sen.mp3"));
        stations.add(new Station(87.6, "SEN 1170", "1170 AM", "https://d2kkpj3tysba3i.cloudfront.net/v1/master/fbb316a042e17a2e683126d94749c2d69866fc2c/SEN_Sydney/raw/sen-syd/EncoderUnit_3.m3u8?playerParams.sitepage=https%3A%2F%2Fsen.com.au%2F&playerParams.domain=sen.com.au&playerParams.cb=cachebusterPlaceholder&playerParams.playerId=SEN-Web", "application/x-mpegURL"));
        stations.add(new Station(87.7, "Sports Nation NZ", "1476 AM", "https://sen.streamguys1.com/SENZ-AAC/playlist.m3u8"));
        stations.add(new Station(87.8, "ABC Sports", "DAB only", "https://mediaserviceslive.akamaized.net/hls/live/2038320/grandstand/master.m3u8"));
        stations.add(new Station(87.9, "BBC Radio 5 Sports Extra", "DAB only", "https://a.files.bbci.co.uk/ms6/live/3441A116-B12E-4D2F-ACA8-C1984642FA4B/audio/simulcast/hls/uk/audio_syndication_low_sbr_v1/cfs/bbc_radio_five_live_sports_extra.m3u8"));
        stations.add(new Station(88.0, "talkSPORT 2", "DAB only", "https://talksport.live.stream.broadcasting.news/stream2"));
        stations.add(new Station(88.1, "ABC Cricket", "Internet only", "https://mediaserviceslive.akamaized.net/hls/live/2118991/sportitinerant/v0-221.m3u8"));
        stations.add(new Station(88.2, "Guerilla Cricket", "Internet only", "https://s3.voscast.com:11173/1"));
        stations.add(new Station(88.3, "SEN Fanatic", "Internet only", "https://d36bzojkpuw7nh.cloudfront.net/playlist.m3u8"));
        // ---- Talk ----
        stations.add(new Station(88.4, "ABC News", "630 AM", "https://mediaserviceslive.akamaized.net/hls/live/2038311/newsradio/master.m3u8"));
        stations.add(new Station(88.5, "CBC Radio One Vancouver", "690 AM", "https://cbcradiolive.akamaized.net/hls/live/2041050/ES_R1PVC/master.m3u8"));
        stations.add(new Station(88.6, "ABC SYD", "702 AM", "https://mediaserviceslive.akamaized.net/hls/live/2038258/localsydney/v0-221.m3u8"));
        stations.add(new Station(88.65, "WNYC", "820 AM", "https://fm939.wnyc.org/wnycfm"));
        stations.add(new Station(88.7, "KHVH 830 AM", "830 AM", "https://stream.revma.ihrhls.com/zc4748"));
        stations.add(new Station(88.8, "2GB Sydney", "873 AM", "https://23153.live.streamtheworld.com/2GB.mp3"));
        stations.add(new Station(88.9, "2SM 1269", "1269 AM", "https://cast4.asurahosting.com/proxy/2sm1269/stream"));
        stations.add(new Station(89.0, "RTE Radio 1", "88.5 FM", "https://25633.live.streamtheworld.com/RTE_1_INT.mp3"));
        stations.add(new Station(89.1, "Money FM Singapore", "89.3 FM", "https://playerservices.streamtheworld.com/api/livestream-redirect/MONEY_893AAC.aac"));
        stations.add(new Station(89.2, "Newstalk ZB Auckland", "89.4 FM", "https://playerservices.streamtheworld.com/api/livestream-redirect/NZME_01AAC_SC"));
        stations.add(new Station(89.3, "Talk 702 Johannesburg", "92.7 FM", "https://22733.live.streamtheworld.com/FM702AAC_SC"));
        stations.add(new Station(89.4, "BBC Radio 4", "93.5 FM", "http://as-hls-ww-live.akamaized.net/pool_55057080/live/ww/bbc_radio_fourfm/bbc_radio_fourfm.isml/bbc_radio_fourfm-audio%3d96000.norewind.m3u8"));
        stations.add(new Station(89.5, "LBC London", "97.3 FM", "https://media-ssl.musicradio.com/LBCLondon"));
        stations.add(new Station(89.6, "RNZ National", "101.4 FM", "https://radionz.streamguys1.com/national/national/national/national-mainstream/chunks.m3u8"));
        stations.add(new Station(89.7, "Radio Australia", "101.9 FM", "https://mediaserviceslive.akamaized.net/hls/live/2109446/ramulti/v0-221.m3u8"));
        stations.add(new Station(89.8, "BBC World Service", "DAB only", "https://stream.live.vc.bbcmedia.co.uk/bbc_world_service"));
        stations.add(new Station(89.9, "SKY News Radio", "DAB only", "https://playerservices.streamtheworld.com/api/livestream-redirect/NOVA_SKYNEWSAAC.aac"));
        // ---- NSW (and ACT) ----
        stations.add(new Station(90.0, "2WEB", "585 AM", "https://s1.viastreaming.net:7230/stream"));
        stations.add(new Station(90.05, "2UE 954", "954 AM", "https://sa46.scastream.com.au/live/2ue_128.stream/playlist.m3u8?dist=listnr-web", "application/x-mpegURL"));
        stations.add(new Station(90.07, "Forever Classic 70s", "88.0 FM", "https://playerservices.streamtheworld.com/api/livestream-redirect/70S_MUSIC_GOULBURNAAC.aac"));
        stations.add(new Station(90.1, "2RDJ", "88.1 FM", "http://104.128.238.80:8000/radio%202rdj"));
        stations.add(new Station(90.2, "2RRR Ryde", "88.5 FM", "https://28803.live.streamtheworld.com/2RRRAAC.aac"));
        stations.add(new Station(90.25, "Vintage FM", "88.7 FM", "https://stream.radio.co/sd60e90ab1/listen"));
        stations.add(new Station(90.3, "2BRW", "88.9 FM", "https://audio-edge-d34v9.syd.o.radiomast.io/ba864be6-11d1-4e13-aa74-f8e8781f64f6#.mp3"));
        stations.add(new Station(90.4, "2BLU Katoomba", "89.1 FM", "https://23193.live.streamtheworld.com/2BLU.mp3"));
        stations.add(new Station(90.5, "2GLF", "89.3 FM", "https://23093.live.streamtheworld.com/2GLFAAC.aac"));
        stations.add(new Station(90.6, "Valley FM", "89.5 FM", "https://22283.live.streamtheworld.com/1VFMAAC.aac"));
        stations.add(new Station(90.7, "Alive 90.5", "90.5 FM", "https://23093.live.streamtheworld.com/2CCRAAC.aac"));
        stations.add(new Station(90.8, "2MCE Bathurst", "92.3 FM", "https://stream.2mce.org/2MCE"));
        stations.add(new Station(90.9, "2YYY Young", "92.3 FM", "https://streaming.2yyyradio.com/radio/8000/radio.mp3"));
        stations.add(new Station(90.95, "2EC", "94.5 FM", "https://playerservices.streamtheworld.com/api/livestream-redirect/BEG_2ECAAC.aac"));
        stations.add(new Station(91.0, "2MIA Griffith", "95.1 FM", "https://28853.live.streamtheworld.com/2MIA.mp3"));
        stations.add(new Station(91.05, "XLFM", "96.1 FM", "https://playerservices.streamtheworld.com/api/livestream-redirect/xlfmaac.aac"));
        stations.add(new Station(91.1, "2TVR Sounds Of The Mountains", "96.3 FM", "https://stream.mjm-webhosting.com.au:8342/;?type=http"));
        stations.add(new Station(91.2, "Coast FM Gosford", "96.3 FM", "https://28863.live.streamtheworld.com/2CCCAAC.aac"));
        stations.add(new Station(91.3, "2GHR Holbrook", "96.7 FM", "https://stream.2ghr.org.au:8000/2ghr"));
        stations.add(new Station(91.35, "Forever Classic 60s", "97.5 FM", "https://28863.live.streamtheworld.com/FOREVER_CLASSIC_60S_CANBERRAAAC.aac"));
        stations.add(new Station(91.4, "Northside Radio", "99.3 FM", "https://28793.live.streamtheworld.com/2NSBAAC.aac"));
        stations.add(new Station(91.5, "2SSR", "99.7 FM", "https://radio12.shoutcast.net.au:8000/stream"));
        stations.add(new Station(91.6, "SWR FM Blacktown", "99.9 FM", "https://23193.live.streamtheworld.com/SWRFMAAC.aac"));
        stations.add(new Station(91.7, "2HHH", "100.1 FM", "https://23203.live.streamtheworld.com/2HHHAAC.aac"));
        stations.add(new Station(91.8, "2MCR", "100.3 FM", "https://cast4.my-control-panel.com/proxy/tim1/som"));
        stations.add(new Station(91.9, "Yass FM", "100.3 FM", "https://28863.live.streamtheworld.com/2YAS.mp3"));
        stations.add(new Station(92.0, "Connect FM Bankstown", "100.9 FM", "https://cast5.asurahosting.com/proxy/jim1/stream"));
        stations.add(new Station(92.1, "Great Lakes FM", "101.5 FM", "https://28853.live.streamtheworld.com/2GLAAAC.aac"));
        stations.add(new Station(92.2, "Nim FM Nimbin", "102.3 FM", "https://uk5.internet-radio.com/proxy/nimfm?mp=/stream"));
        stations.add(new Station(92.3, "RAM FM Goulburn", "103.3 FM", "https://23203.live.streamtheworld.com/2GCRAAC.aac"));
        stations.add(new Station(92.4, "2NUR", "103.7 FM", "https://playerservices.streamtheworld.com/api/livestream-redirect/2NURFM.mp3"));
        stations.add(new Station(92.5, "Triple U FM", "104.5 FM", "https://stream1.tripleu.org.au/live"));
        stations.add(new Station(92.6, "2SEA Eden", "104.7 FM", "https://23163.live.streamtheworld.com/2SEA_EDENAAC.aac"));
        stations.add(new Station(92.7, "2NVR Nambucca Heads", "105.9 FM", "https://s14.myradiostream.com:19092/stream"));
        stations.add(new Station(92.8, "VOX FM", "106.9 FM", "http://s10.myradiostream.com:15272/stream"));
        stations.add(new Station(92.9, "2AAA Wagga", "107.1 FM", "https://stream.tripleafm.com.au//?"));
        stations.add(new Station(93.0, "Highland FM Mittagong", "107.1 FM", "https://23163.live.streamtheworld.com/HIGHLAND_FM.mp3"));
        stations.add(new Station(93.1, "2BBB Bellingen", "107.3 FM", "https://23163.live.streamtheworld.com/2BBBAAC.aac"));
        stations.add(new Station(93.2, "2EAR Moruya", "107.5 FM", "https://23193.live.streamtheworld.com/2EARAAC.aac"));
        stations.add(new Station(93.3, "2OCW Orange", "107.5 FM", "https://23093.live.streamtheworld.com/2OCWAAC.aac"));
        // ---- Victoria ----
        stations.add(new Station(93.4, "3MFM", "88.1 FM", "http://listen.southcoastfm.au:881/aac"));
        stations.add(new Station(93.5, "Southern FM", "88.3 FM", "https://stream.swifthosting.com.au/;stream.mp3"));
        stations.add(new Station(93.6, "Plenty Valley FM", "88.6 FM", "https://28803.live.streamtheworld.com/3PVRAAC.aac"));
        stations.add(new Station(93.7, "WYN FM", "88.9 FM", "https://streamer5.rightclickitservices.com:19740/aac"));
        stations.add(new Station(93.8, "Pulse FM Geelong", "94.7 FM", "https://23193.live.streamtheworld.com/3PLSAAC.aac"));
        stations.add(new Station(93.9, "Joy FM Melbourne", "94.9 FM", "https://stream.joy.org.au/proxy/joy949?mp=/live"));
        stations.add(new Station(94.0, "Main FM", "94.9 FM", "http://s1.communitybroadcastnetwork.com.au:8000/MAINFM/stream"));
        stations.add(new Station(94.1, "Inner FM", "96.5 FM", "https://28793.live.streamtheworld.com/3NRAAC.aac"));
        stations.add(new Station(94.2, "3MDR", "97.1 FM", "https://23163.live.streamtheworld.com/3MDRAAC.aac"));
        stations.add(new Station(94.3, "Casey Radio", "97.7 FM", "https://23193.live.streamtheworld.com/3SERAAC.aac"));
        stations.add(new Station(94.4, "3RPP", "98.7 FM", "https://streaming.radio.co/sfbd79db12/listen"));
        stations.add(new Station(94.5, "Yarra Valley FM", "99.1 FM", "https://live.yarravalleyfm.org.au:8443/stream"));
        stations.add(new Station(94.6, "Sunbury Radio", "99.3 FM", "https://23203.live.streamtheworld.com/3NRGAAC.aac"));
        stations.add(new Station(94.7, "3MGB Mallacoota", "101.7 FM", "https://23163.live.streamtheworld.com/3MGBAAC.aac"));
        stations.add(new Station(94.8, "3RRR FM", "102.7 FM", "https://realtime.rrr.org.au/p1h"));
        stations.add(new Station(94.9, "3PBS FM", "106.7 FM", "https://playerservices.streamtheworld.com/api/livestream-redirect/3PBS_FMAACHIGH.aac"));
        // ---- Queensland ----
        stations.add(new Station(95.0, "4KZ", "531 AM", "https://stream03.syd.hostgeek.com.au/4kz/stream"));
        stations.add(new Station(95.1, "4AM", "558 AM", "https://stream03.syd.hostgeek.com.au/4am/stream"));
        stations.add(new Station(95.2, "4BRZ", "92.1 FM", "https://s8.yesstreaming.net:17065/BrzLgn"));
        stations.add(new Station(95.3, "Goldie FM", "94.1 FM", "https://14533.live.streamtheworld.com/941FMAAC.aac"));
        stations.add(new Station(95.4, "Rebel FM", "99.4 FM", "https://s8.yesstreaming.net:17099/RblGC"));
        stations.add(new Station(95.5, "Bridge FM", "99.7 FM", "https://28793.live.streamtheworld.com/997BRIDGEFMAAC.aac"));
        stations.add(new Station(95.6, "4RIM Boonah", "100.1 FM", "https://streaming.rimfm.org/rimfmlive.mp3"));
        stations.add(new Station(95.7, "Bay FM Redlands", "100.3 FM", "https://28853.live.streamtheworld.com/4BAYAAC.aac"));
        stations.add(new Station(95.8, "Power 100", "100.7 FM", "https://17853.live.streamtheworld.com/TOW_POWER100.mp3"));
        stations.add(new Station(95.9, "Logan Radio", "101.1 FM", "https://28853.live.streamtheworld.com/4CBLAAC.aac"));
        stations.add(new Station(96.0, "Noosa FM", "101.3 FM", "https://23153.live.streamtheworld.com/4NSA_ICECAST.mp3"));
        stations.add(new Station(96.1, "4OUR Caboolture", "101.5 FM", "https://stream.lava.net.au/au_qld_4our_caboolture1015"));
        stations.add(new Station(96.2, "4ZZZ", "102.1 FM", "https://iheart.4zzz.org.au/4zzz"));
        stations.add(new Station(96.3, "Sunshine FM", "104.9 FM", "https://23163.live.streamtheworld.com/4SFM_SC"));
        // ---- South Australia ----
        stations.add(new Station(96.4, "Coast FM Adelaide", "88.7 FM", "http://streaming1.coastfm.com.au:8000/broadwavehigh.mp3"));
        stations.add(new Station(96.5, "Lofty Radio", "88.9 FM", "https://stream04.sigile.net/lofty-48-aac"));
        stations.add(new Station(96.6, "Fleurieu FM", "89.3 FM", "https://stream.sigile.net/fleurieufm-128-mp3"));
        stations.add(new Station(96.7, "5PBA FM", "89.7 FM", "https://23093.live.streamtheworld.com/5PBAAAC.aac"));
        stations.add(new Station(96.8, "Happy FM", "90.1 FM", "https://stream04.sigile.net/happyfm-128-mp3"));
        stations.add(new Station(96.9, "5KIX Kangaroo Island", "90.7 FM", "https://23093.live.streamtheworld.com/5KIXAAC.aac"));
        stations.add(new Station(97.0, "5ZZZ", "91.1 FM", "https://usa7.fastcast4u.com/proxy/triplez?mp=/1"));
        stations.add(new Station(97.1, "Three D Radio", "93.7 FM", "https://sounds.threedradio.com/stream"));
        stations.add(new Station(97.2, "Flow FM", "99.5 FM", "https://uksoutha.streaming.broadcast.radio/flow-fm"));
        stations.add(new Station(97.3, "5WOW", "100.5 FM", "https://stream04.sigile.net/wowfm-64-aac"));
        // ---- Western Australia ----
        stations.add(new Station(97.4, "VCA FM Ellenbrook", "88.5 FM", "https://23093.live.streamtheworld.com/ELLENBROOK_RADIOAAC.aac"));
        stations.add(new Station(97.5, "Radio Kalamunda", "88.9 FM", "https://radio12.shoutcast.net.au:2020/stream/kalamunda"));
        stations.add(new Station(97.6, "Twin Cities FM", "89.7 FM", "https://ice8.securenetsystems.net/897FM"));
        stations.add(new Station(97.7, "Wave Mandurah", "91.7 FM", "https://sa47.scastream.com.au/live/6mm_128.stream/playlist.m3u8"));
        stations.add(new Station(97.8, "Curtin Radio", "100.1 FM", "https://28853.live.streamtheworld.com/6NRAAC.aac"));
        stations.add(new Station(97.9, "Bunbury Community Radio", "103.7 FM", "https://23203.live.streamtheworld.com/6BCRAAC.aac"));
        stations.add(new Station(98.0, "H FM Armadale", "107.3 FM", "https://stream.hfm.radio:8443/;?type=http"));
        stations.add(new Station(98.1, "Radio Fremantle", "107.9 FM", "https://23193.live.streamtheworld.com/6CCRAAC.aac"));
        // ---- Tasmania ----
        stations.add(new Station(98.2, "Star FM St Helens", "93.7 FM", "https://s6.myradiostream.com:11056/;stream.mp3"));
        stations.add(new Station(98.3, "Huon FM", "95.3 FM", "https://stream3.rcast.net/72445"));
        stations.add(new Station(98.4, "Hobart FM", "96.1 FM", "https://radio12.shoutcast.net.au:2020/stream/8032"));
        stations.add(new Station(98.5, "MV FM", "96.9 FM", "https://23093.live.streamtheworld.com/MVFMAAC.aac"));
        stations.add(new Station(98.6, "Tasman FM", "97.7 FM", "https://stream-285.surfernetwork.com/kehh7k7co2yvv"));
        stations.add(new Station(98.7, "TYGA FM", "98.9 FM", "https://radio12.shoutcast.net.au:2020/stream/8012"));
        stations.add(new Station(98.8, "City Park Radio", "103.7 FM", "https://14533.live.streamtheworld.com/7LTNAAC.aac"));
        stations.add(new Station(98.9, "7DBS Coast FM", "106.1 FM", "https://streaming.coastfmtas.au:8000/coastfm.aac"));
        // ---- Northern Territory ----
        stations.add(new Station(99.0, "Territory FM", "104.1 FM", "https://streaming.territoryfm.com:8443/TerritoryFM"));
        // ---- Australia (national) ----
        stations.add(new Station(99.1, "Triple M Classic Rock", "DAB only", "https://sa47.scastream.com.au/live/2classicrock_128.stream/playlist.m3u8"));
        stations.add(new Station(99.12, "Triple J Hottest", "DAB only", "https://mediaserviceslive.akamaized.net/hls/live/2109455/triplejhottest/v0-221.m3u8"));
        stations.add(new Station(99.15, "Yacht Rock", "Internet only", "http://sa47.scastream.com.au/live/7yachtrock_128.stream/playlist.m3u8"));
        // ---- New Zealand ----
        stations.add(new Station(99.2, "Radio Dunedin", "1305 AM", "https://stream.radioaotearoa.com/hls/RadioDunedin.m3u8"));
        stations.add(new Station(99.3, "Brian FM Oamaru", "88.8 FM", "https://das-edge53-sa48-futuri-dal03.cdnstream.com/7654_128k.aac"));
        stations.add(new Station(99.4, "Radio Hauraki Westport", "89.3 FM", "https://playerservices.streamtheworld.com/api/livestream-redirect/NZME_04AAC_SC.aac"));
        stations.add(new Station(99.5, "The Rock Auckland", "90.2 FM", "https://mediaworks.streamguys1.com/rock_net_icy"));
        stations.add(new Station(99.6, "Channel X Queenstown", "91.2 FM", "https://mediaworks.streamguys1.com/chx_net_icy"));
        stations.add(new Station(99.65, "The Sound Auckland", "93.8 FM", "https://digitalstreams.mediaworks.nz/sound_net/playlist.m3u8"));
        // ---- Pacific ----
        stations.add(new Station(99.7, "NC 1’ere", "89.0 FM", "https://nouvelle-caledonie.ice.infomaniak.ch/nouvelle-caledonie-128.mp3"));
        stations.add(new Station(99.8, "Gold FM Fiji", "100.2 FM", "https://icecast1.sere.plus/Gold"));
        // ---- Singapore ----
        stations.add(new Station(99.9, "Gold FM Singapore", "90.5 FM", "https://28393.live.streamtheworld.com/GOLD905_PREM.aac"));
        // ---- South Africa ----
        stations.add(new Station(100.0, "Magic 828", "828 AM", "http://cast.fabrik.fm:8106/"));
        stations.add(new Station(100.1, "LM Radio", "87.8 FM", "https://ukwestb.streaming.broadcast.radio/lmradio"));
        // ---- UK ----
        stations.add(new Station(100.2, "Radio Caroline", "648 AM", "https://stream.radiocaroline.net/rc128/stream.mp3"));
        stations.add(new Station(100.3, "BBC Radio 2", "89.1 FM", "https://a.files.bbci.co.uk/ms6/live/3441A116-B12E-4D2F-ACA8-C1984642FA4B/audio/simulcast/hls/nonuk/audio_syndication_low_sbr_v1/cfs/bbc_radio_two.m3u8"));
        // ---- France ----
        stations.add(new Station(100.4, "FIP Marseille", "90.9 FM", "https://icecast.radiofrance.fr/fip-midfi.mp3"));
        // ---- Germany ----
        stations.add(new Station(100.5, "Rock Antenne", "DAB only", "https://mp3channels.webradio.antenne.de/rockantenne"));
        // ---- USA ----
        stations.add(new Station(100.6, "KBRW", "680 AM", "https://tektite.streamguys1.com:5345/live"));
        stations.add(new Station(100.7, "KOTZ", "720 AM", "https://17643.live.streamtheworld.com/KOTZAM.mp3"));
        stations.add(new Station(100.8, "WION", "1430 AM", "http://das-edge11-live365-dal03.cdnstream.com/a83993"));
        stations.add(new Station(100.9, "KFAI", "90.3 FM", "https://kfai.broadcasttool.stream/kfai-1"));
        stations.add(new Station(101.0, "WFMU", "91.1 FM", "http://stream0.wfmu.org/freeform-high.aac"));
        stations.add(new Station(101.1, "Pop 91.3", "91.3 FM", "http://streaming.live365.com/a25253"));
        stations.add(new Station(101.2, "WLNG", "92.1 FM", "http://cast6.asurahosting.com/proxy/barkoutl/stream"));
        // ---- Internet only ----
        stations.add(new Station(101.3, "Aircheck Radio", "Internet only", "http://sonic.radiostream.ca/8034/stream"));
        stations.add(new Station(101.4, "Alt Boss Gold", "Internet only", "http://ais-sa3.cdnstream1.com/2713_128.mp3"));
        stations.add(new Station(101.5, "Casey Kasem Classic AT40", "Internet only", "https://stream.revma.ihrhls.com/zc6545"));
        stations.add(new Station(101.6, "Powerzone", "Internet only", "https://listen2.streamaudio.co:8090/stream1.mp3"));
        stations.add(new Station(101.7, "Radio Free Phoenix", "Internet only", "https://s6.reliastream.com/proxy/radiofre?mp=/stream"));
        stations.add(new Station(101.8, "Retro Hit Radio", "Internet only", "https://riphel02.radioca.st/stream"));
        stations.add(new Station(101.9, "Retro Stream (weekends only)", "Internet only", "http://retrostream.duckdns.org:8000/retro_stream_aac"));
        stations.add(new Station(102.0, "Station X", "Internet only", "https://us01.xrn.com.au/listen/stx/StxSd"));
        stations.add(new Station(102.1, "The Fresh Mix", "Internet only", "https://streaming.starterfm.com/listen/freshmix/mix192"));
        return stations;
    }
}