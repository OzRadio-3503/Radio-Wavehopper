package com.billybobs.radiotuner;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tidies the song text that streams send (Icecast/Shoutcast "StreamTitle",
 * HLS ID3 artist/title tags) before the phone or the car shows it. Stations
 * send all sorts: web-page codes like "&amp;", the same song twice, programme
 * codes, automation messages, their own name or slogan. Shared by the phone
 * (PlaybackService) and Android Auto (RadioLibraryService), so both show the
 * same thing. Plain Java, no Android, so it can be tested on its own.
 */
final class SongText {
    private SongText() {}

    private static final Pattern ATTR_TEXT = Pattern.compile("text=\"([^\"]*)\"");
    private static final Pattern ATTR_ARTIST = Pattern.compile("artist=\"([^\"]*)\"");
    private static final Pattern NUM_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]{1,6});");
    private static final Pattern ISO_TIME = Pattern.compile("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d");
    private static final Pattern AUTOMATION = Pattern.compile("(?i)\\basset (start|stop)\\b");

    /**
     * From an Icecast/Shoutcast StreamTitle (usually "Artist - Title").
     * Returns {artist, title} (artist may be ""), or null to show nothing new.
     */
    static String[] fromIcy(String raw, String stationName) {
        if (raw == null) return null;
        String t = decode(raw).trim();
        // iHeart-style: text="Song" artist="Artist" song_spot="..." ...
        if (t.contains("text=\"")) {
            Matcher mt = ATTR_TEXT.matcher(t), ma = ATTR_ARTIST.matcher(t);
            String text = mt.find() ? mt.group(1).trim() : "";
            String artist = ma.find() ? ma.group(1).trim() : "";
            if (artist.isEmpty()) return null;   // a programme code, not a song
            return tidy(artist, text, stationName);
        }
        return tidy("", t, stationName);
    }

    /** From separate artist and title tags (HLS ID3 TPE1/TIT2). */
    static String[] fromTags(String artist, String title, String stationName) {
        return tidy(decode(artist == null ? "" : artist), decode(title == null ? "" : title), stationName);
    }

    private static String[] tidy(String artist, String title, String stationName) {
        artist = squash(artist);
        title = squash(title);
        if (title.isEmpty()) { title = artist; artist = ""; }
        if (title.isEmpty()) return null;
        // The same text as both artist and title ("Jimi Hendrix - Manic
        // Depression" twice): keep one.
        if (artist.equalsIgnoreCase(title)) artist = "";
        // Everything as one "A - B - ..." line, so repeats can be spotted.
        String full = artist.isEmpty() ? title : artist + " - " + title;
        String[] parts = full.split(" - ", -1);
        // Sent twice in a row ("Like A Prayer - Madonna - Like A Prayer -
        // Madonna"): keep the first half.
        if (parts.length >= 4 && parts.length % 2 == 0) {
            int half = parts.length / 2;
            boolean twice = true;
            for (int i = 0; i < half && twice; i++) twice = parts[i].trim().equalsIgnoreCase(parts[i + half].trim());
            if (twice) {
                String[] p = new String[half];
                System.arraycopy(parts, 0, p, 0, half);
                parts = p;
            }
        }
        // Back to artist + title: the first part is the artist, the rest the title.
        if (parts.length >= 2) {
            artist = parts[0].trim();
            StringBuilder b = new StringBuilder();
            for (int i = 1; i < parts.length; i++) { if (i > 1) b.append(" - "); b.append(parts[i].trim()); }
            title = b.toString();
        } else {
            artist = "";
            title = parts[0].trim();
        }
        // A leading or trailing dash left over from an empty artist or title.
        artist = stripDashes(artist);
        title = stripDashes(title);
        if (title.isEmpty()) { title = artist; artist = ""; }
        if (title.isEmpty()) return null;
        String all = (artist + " " + title).trim();
        // Not songs: automation messages, timestamps, leftover tag code.
        if (AUTOMATION.matcher(all).find() || ISO_TIME.matcher(all).find()
                || all.contains("song_spot=") || all.contains("MediaBaseId=")) return null;
        // The station's own name or slogan ("WLNG-FM Sag Harbor", "ROCK ANTENNE - ...").
        if (stationName != null && !stationName.trim().isEmpty()) {
            String name = stationName.trim().toLowerCase(java.util.Locale.ROOT);
            if (artist.toLowerCase(java.util.Locale.ROOT).equals(name)) return null;
            if (artist.isEmpty() && title.toLowerCase(java.util.Locale.ROOT).startsWith(name)) return null;
        }
        return new String[]{titleCase(artist), titleCase(title)};
    }

    /**
     * Some stations send everything in capitals ("BOB SEGER - FEEL LIKE A
     * NUMBER"), which looks bigger than normal text next to it. Text that's
     * all capitals becomes Title Case; anything with lower-case letters is
     * left exactly as sent. Short all-capital words that are usually
     * initials or numerals (ABBA, AC/DC, INXS, UB40, II) stay as they are.
     */
    static String titleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        boolean hasUpper = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLowerCase(c)) return s;   // mixed case: leave it
            if (Character.isUpperCase(c)) hasUpper = true;
        }
        if (!hasUpper) return s;
        StringBuilder out = new StringBuilder(s.length());
        String[] words = s.split(" ", -1);
        for (int w = 0; w < words.length; w++) {
            if (w > 0) out.append(' ');
            out.append(titleWord(words[w]));
        }
        return out.toString();
    }

    private static final java.util.Set<String> KEEP_CAPS = new java.util.HashSet<>(java.util.Arrays.asList(
            "ABBA", "AC/DC", "INXS", "UB40", "UFO", "ELO", "REM", "R.E.M.", "KISS", "ZZ", "TLC", "NSYNC",
            "DJ", "MC", "UK", "USA", "NYC", "TV", "FM", "AM", "OK", "II", "III", "IV", "VI", "B-52'S"));

    private static String titleWord(String w) {
        if (w.isEmpty() || KEEP_CAPS.contains(w)) return w;
        // Initials like T.N.T. or U.S.A. stay in capitals.
        if (w.matches("[(\\[\"']*([A-Z]\\.)+[)\\]\"',]*")) return w;
        // Keep any brackets or quotes in front, capitalise the first letter.
        StringBuilder b = new StringBuilder(w.length());
        boolean first = true;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (Character.isLetter(c)) {
                b.append(first ? Character.toUpperCase(c) : Character.toLowerCase(c));
                first = false;
            } else {
                b.append(c);
                // After a hyphen or slash, start a new word ("Rock-N-Roll").
                if (c == '-' || c == '/') first = true;
            }
        }
        return b.toString();
    }

    private static String stripDashes(String s) {
        s = s.trim();
        while (s.startsWith("-") || s.startsWith("\u2013")) s = s.substring(1).trim();
        while (s.endsWith("-") || s.endsWith("\u2013")) s = s.substring(0, s.length() - 1).trim();
        return s;
    }

    private static String squash(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    /** Web-page codes stations send in place of characters: &amp; &quot; &#39; and friends. */
    static String decode(String s) {
        if (s == null || s.indexOf('&') < 0) return s == null ? "" : s;
        // Twice, for the odd station that codes it twice (&amp;amp;).
        for (int pass = 0; pass < 2 && s.indexOf('&') >= 0; pass++) {
            Matcher m = NUM_ENTITY.matcher(s);
            StringBuffer out = new StringBuffer();
            while (m.find()) {
                String rep;
                try {
                    int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                    rep = (cp > 0 && Character.isValidCodePoint(cp)) ? new String(Character.toChars(cp)) : m.group();
                } catch (NumberFormatException e) {
                    rep = m.group();
                }
                m.appendReplacement(out, Matcher.quoteReplacement(rep));
            }
            m.appendTail(out);
            s = out.toString()
                    .replace("&quot;", "\"").replace("&apos;", "'")
                    .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
                    .replace("&amp;", "&");
        }
        return s;
    }
}
