package com.billybobs.radiotuner;

import java.util.HashMap;
import java.util.Map;

/**
 * Each station's website, by station name, used only to find its logo for
 * Android Auto (LogoFetcher reads the site's icon). Keyed by name, so it
 * survives stations moving on the dial.
 *
 * A station that isn't listed here (or whose site has no usable icon) keeps
 * its drawn logo tile. To add or fix one, add/edit its line. To force a
 * specific image instead, put assets/logos/<slug>.png in the app (see
 * StationArtworkProvider) - that always wins.
 *
 * Some sites are a sub-page (e.g. the ABC networks) because the sub-page
 * declares that network's own icon rather than the parent site's.
 */
final class StationWebsites {
    private StationWebsites() {}

    private static final Map<String, String> SITES = new HashMap<>();

    static {
        // ---- Sport ----
        SITES.put("SEN 1170", "https://www.sen.com.au");
        SITES.put("SEN 1116", "https://www.sen.com.au");
        SITES.put("ABC Sports", "https://www.abc.net.au/sport");
        SITES.put("ABC Cricket", "https://www.abc.net.au/sport");
        SITES.put("Sports Nation WLG NZ", "https://sportnation.nz");
        SITES.put("BBC Radio 5 Sports Extra", "https://www.bbc.co.uk/5live");
        SITES.put("Guerilla Cricket", "https://www.guerillacricket.com");
        SITES.put("SEN Fanatic", "https://www.sen.com.au");
        SITES.put("talkSPORT", "https://talksport.com");
        SITES.put("talkSPORT 2", "https://talksport.com");
        // ---- Talk ----
        SITES.put("Radio Australia", "https://www.abc.net.au/pacific");
        SITES.put("2GB Sydney", "https://www.2gb.com");
        SITES.put("WNYC", "https://www.wnyc.org");
        SITES.put("2UE 954", "https://www.2ue.com.au");
        SITES.put("2SM 1269", "https://2sm.com.au");
        SITES.put("ABC News", "https://www.abc.net.au/news");
        SITES.put("ABC SYD", "https://www.abc.net.au/sydney");
        SITES.put("SKY News Radio", "https://www.skynews.com.au");
        SITES.put("Newstalk ZB Auckland", "https://www.newstalkzb.co.nz");
        SITES.put("RNZ National", "https://www.rnz.co.nz");
        SITES.put("LBC London", "https://www.lbc.co.uk");
        SITES.put("BBC World Service", "https://www.bbc.co.uk/worldserviceradio");
        SITES.put("BBC Radio 4", "https://www.bbc.co.uk/radio4");
        SITES.put("RTE Radio 1", "https://www.rte.ie/radio/radio1/");
        SITES.put("CBC Radio One Vancouver", "https://www.cbc.ca/listen");
        SITES.put("Talk 702 Johannesburg", "https://www.702.co.za");
        SITES.put("Money FM Singapore", "https://www.moneyfm893.sg");
        SITES.put("KHVH 830 AM", "https://khvh.iheart.com");
        // ---- NSW (and ACT) ----
        SITES.put("Northside Radio", "https://northsideradio.com.au");
        SITES.put("2RRR Ryde", "https://2rrr.org.au");
        SITES.put("Alive 90.5", "https://www.alive905.com.au");
        SITES.put("Connect FM Bankstown", "https://www.connectfm.au");
        SITES.put("Coast FM Gosford", "https://coastfm.org.au");
        SITES.put("2NUR", "https://www.2nurfm.com.au");
        SITES.put("VOX FM", "https://www.voxfm.org.au");
        SITES.put("Nim FM Nimbin", "https://www.nimfm.org");
        SITES.put("2WEB", "https://www.outbackradio.com.au");
        SITES.put("2MCE Bathurst", "https://www.2mce.org");
        SITES.put("2BRW", "https://braidwoodradio.com.au");
        SITES.put("2MIA Griffith", "https://www.2miafm.org.au");
        SITES.put("2YYY Young", "https://2yyyradio.com");
        SITES.put("2AAA Wagga", "https://www.tripleafm.com.au");
        SITES.put("2GHR Holbrook", "https://2ghr.org.au");
        SITES.put("Highland FM Mittagong", "https://www.highlandfm.org.au");
        SITES.put("Triple U FM", "https://www.tripleu.org.au");
        SITES.put("2EAR Moruya", "https://www.2ear.com.au");
        SITES.put("2SEA Eden", "https://2sea.com.au");
        SITES.put("2EC", "https://2ec.com.au");
        SITES.put("Valley FM", "https://www.valleyfm.com");
        SITES.put("2RDJ", "https://www.radio2rdj.com");
        SITES.put("2BLU Katoomba", "https://rbm.org.au");
        SITES.put("2GLF", "https://893fm.com.au");
        SITES.put("2SSR", "https://www.2ssr.com.au");
        SITES.put("SWR FM Blacktown", "https://swr999.com.au");
        SITES.put("2HHH", "https://www.triplehfm.com.au");
        SITES.put("2MCR", "https://2mcr.org.au");
        SITES.put("Yass FM", "https://yassfm.org");
        SITES.put("2NVR Nambucca Heads", "https://2nvr.org.au");
        SITES.put("2BBB Bellingen", "https://www.2bbb.net.au");
        SITES.put("2OCW Orange", "https://fm1075.com.au");
        SITES.put("2TVR Sounds Of The Mountains", "https://thelocalstation.com.au");
        SITES.put("RAM FM Goulburn", "https://ramfm.com.au");
        SITES.put("Great Lakes FM", "https://greatlakesfm.org.au");
        // ---- Victoria ----
        SITES.put("3RRR FM", "https://www.rrr.org.au");
        SITES.put("3PBS FM", "https://www.pbsfm.org.au");
        SITES.put("Joy FM Melbourne", "https://joy.org.au");
        SITES.put("Southern FM", "https://www.southernfm.com.au");
        SITES.put("Plenty Valley FM", "https://www.pvfm.org.au");
        SITES.put("Inner FM", "https://www.innerfm.org.au");
        SITES.put("3MDR", "https://www.3mdr.com");
        SITES.put("Sunbury Radio", "https://sunburyradio.com.au");
        SITES.put("Yarra Valley FM", "https://www.yarravalleyfm.org.au");
        SITES.put("WYN FM", "https://www.wynfm.org.au");
        SITES.put("Casey Radio", "https://www.caseyradio.org.au");
        SITES.put("3RPP", "https://www.rppfm.com.au");
        SITES.put("Main FM", "https://www.mainfm.net");
        SITES.put("3MFM", "https://southcoastfm.au");
        SITES.put("3MGB Mallacoota", "https://3mgb.org.au");
        SITES.put("Pulse FM Geelong", "https://947thepulse.com");
        // ---- Queensland ----
        SITES.put("4ZZZ", "https://4zzz.org.au");
        SITES.put("Logan Radio", "https://101fm.com.au");
        SITES.put("Bay FM Redlands", "https://www.bayfm.org.au");
        SITES.put("Rebel FM", "https://www.rebelfm.com.au");
        SITES.put("4RIM Boonah", "https://www.rimfm.org");
        SITES.put("Bridge FM", "https://www.997fm.com.au");
        SITES.put("Noosa FM", "https://www.noosafm.org");
        SITES.put("Sunshine FM", "https://sunshinefm.com.au");
        SITES.put("4KZ", "https://www.4kz.com.au");
        SITES.put("4AM", "https://www.4am.com.au");
        SITES.put("4OUR Caboolture", "https://1015fm.com.au");
        SITES.put("4BRZ", "https://breeze.radio");
        SITES.put("Goldie FM", "https://www.941fm.com.au");
        SITES.put("Power 100", "https://www.power100.com.au");
        // ---- South Australia ----
        SITES.put("Three D Radio", "https://www.threedradio.com");
        SITES.put("Coast FM Adelaide", "https://www.coastfm.com.au");
        SITES.put("Lofty Radio", "https://lofty.org.au");
        SITES.put("Happy FM", "https://happyfm.org.au");
        SITES.put("Fleurieu FM", "https://fleurieufm.org.au");
        SITES.put("Flow FM", "https://www.flowfm.com.au");
        SITES.put("5PBA FM", "https://www.pbafm.org.au");
        SITES.put("5KIX Kangaroo Island", "https://5kixfm.org");
        SITES.put("5WOW", "https://www.wowfm.org");
        SITES.put("5ZZZ", "https://5triplez.com.au");
        // ---- Western Australia ----
        SITES.put("Curtin Radio", "https://www.curtinfm.com.au");
        SITES.put("Radio Fremantle", "https://radiofremantle.com.au");
        SITES.put("VCA FM Ellenbrook", "https://885fm.com.au");
        SITES.put("Twin Cities FM", "https://www.twincitiesfm.com.au");
        SITES.put("Radio Kalamunda", "https://www.kcr-fm.org.au");
        SITES.put("H FM Armadale", "https://hfm.radio");
        SITES.put("Wave Mandurah", "https://www.917thewave.com.au");
        SITES.put("Bunbury Community Radio", "https://www.bunburycommunityradio.com");
        // ---- Tasmania ----
        SITES.put("Hobart FM", "https://hobartfm.org.au");
        SITES.put("Huon FM", "https://www.huonfm.com");
        SITES.put("TYGA FM", "https://www.tygafm.org.au");
        SITES.put("MV FM", "https://www.mvfm.com.au");
        SITES.put("Tasman FM", "https://tasmanfm.com.au");
        SITES.put("City Park Radio", "https://www.cityparkradio.com");
        SITES.put("7DBS Coast FM", "https://coastfmtas.au");
        SITES.put("Star FM St Helens", "https://starfm.org.au");
        // ---- Northern Territory ----
        SITES.put("Territory FM", "https://www.territoryfm.com");
        // ---- Music ----
        SITES.put("Triple J Hottest", "https://www.abc.net.au/triplej");
        SITES.put("The Rock Auckland", "https://www.therock.net.nz");
        SITES.put("Radio Hauraki Westport", "https://www.hauraki.co.nz");
        SITES.put("Channel X Queenstown", "https://www.rova.nz/radio/channelx");
        SITES.put("Radio Dunedin", "https://www.radiodunedin.co.nz");
        SITES.put("Brian FM Oamaru", "https://www.brianfm.com");
        SITES.put("NC 1’ere", "https://la1ere.franceinfo.fr/nouvellecaledonie/");
        SITES.put("Gold FM Singapore", "https://www.melisten.sg");
        SITES.put("LM Radio", "https://lmradio.co.za");
        SITES.put("Magic 828", "https://www.magic828.co.za");
        SITES.put("BBC Radio 2", "https://www.bbc.co.uk/radio2");
        SITES.put("Radio Caroline", "https://www.radiocaroline.co.uk");
        SITES.put("FIP Marseille", "https://www.radiofrance.fr/fip");
        SITES.put("Rock Antenne", "https://www.rockantenne.de");
        SITES.put("WFMU", "https://wfmu.org");
        SITES.put("KFAI", "https://www.kfai.org");
        SITES.put("WLNG", "https://www.wlng.com");
        SITES.put("KBRW", "https://www.kbrw.org");
        SITES.put("KOTZ", "https://www.kotz.org");
        // ---- Internet only / more overseas ----
        SITES.put("WION", "https://i1430.com");
        SITES.put("Retro Hit Radio", "https://retrohitradio.co.nz");
        SITES.put("Radio Free Phoenix", "https://radiofreephoenix.com");
        SITES.put("Pop 91.3", "https://live365.com/station/POP-91-3--a25253");
        SITES.put("Powerzone", "https://www.powerzone.me");
        SITES.put("Alt Boss Gold", "https://www.altbossgold.com");
        SITES.put("Aircheck Radio", "https://www.aircheckradio.us");
        SITES.put("Station X", "https://xrn.com.au/station/station-x");
        SITES.put("The Fresh Mix", "https://www.2prfm.com/thefreshmix/");
    }

    /**
     * A specific logo image to use first, for stations whose website's own
     * icons are small or missing. Each one was checked against the
     * station's own site. If it fails, the website's icons are tried as usual.
     */
    private static final Map<String, String> LOGOS = new HashMap<>();

    static {
        // ABC Sport logo (abc.net.au/sport only offers the ABC News icon).
        LOGOS.put("ABC Sports", "https://static-media.streema.com/media/cache/d2/ce/d2ce5cb283cabcd8622c5bc58e71465a.png");
        LOGOS.put("ABC Cricket", "https://static-media.streema.com/media/cache/d2/ce/d2ce5cb283cabcd8622c5bc58e71465a.png");
        LOGOS.put("2TVR Sounds Of The Mountains", "https://thelocalstation.com.au/wp-content/uploads/2023/08/Sounds-Of-The-Mountains_Logo.png");
        LOGOS.put("Goldie FM", "https://static.wixstatic.com/media/d5dcc1_cf9af055dcf1470d9f0b26285ff1f93b~mv2.jpg");
        LOGOS.put("Yass FM", "https://www.yassfm.org/wp-content/uploads/cropped-yass-fm-logo.jpg");
        LOGOS.put("Power 100", "https://www.power100.com.au/wp-content/uploads/sites/19/2022/10/cropped-NQL_TSV_P10_512x512-favicon.png");
        LOGOS.put("4BRZ", "https://breeze.radio/wp-content/uploads/2023/01/The-Breeze-Logo-Stacked-500x500-1.png");
        // From the CBAA's Community Radio DAB+ directory (logos supplied by the stations).
        LOGOS.put("3RRR FM", "https://higherlogicdownload.s3.amazonaws.com/CBAA/f91521e2-cb7a-4f99-8ca0-552342c290f7/UploadedImages/Photos/DAB_Logos/RRR_Logo.jpg");
        LOGOS.put("3PBS FM", "https://higherlogicdownload.s3.amazonaws.com/CBAA/f91521e2-cb7a-4f99-8ca0-552342c290f7/UploadedImages/Photos/DAB_Logos/PBS_Logo.gif");
        LOGOS.put("Joy FM Melbourne", "https://higherlogicdownload.s3.amazonaws.com/CBAA/f91521e2-cb7a-4f99-8ca0-552342c290f7/UploadedImages/Photos/DAB_Logos/Joy_Media.png");
        LOGOS.put("4ZZZ", "https://higherlogicdownload.s3.amazonaws.com/CBAA/f91521e2-cb7a-4f99-8ca0-552342c290f7/UploadedImages/Photos/DAB_Logos/4ZZZ_Logo_JPG.jpg");
        LOGOS.put("Three D Radio", "https://higherlogicdownload.s3.amazonaws.com/CBAA/f91521e2-cb7a-4f99-8ca0-552342c290f7/UploadedImages/Photos/DAB_Logos/Three_D_Radio_Logo.png");
        LOGOS.put("Curtin Radio", "https://higherlogicdownload.s3.amazonaws.com/CBAA/f91521e2-cb7a-4f99-8ca0-552342c290f7/UploadedImages/Photos/DAB_Logos/Curtin_Logo.jpg");
        LOGOS.put("Hobart FM", "https://higherlogicdownload.s3.amazonaws.com/CBAA/f91521e2-cb7a-4f99-8ca0-552342c290f7/UploadedImages/Photos/DAB_Logos/Hobart_FM_Logo.jpg");
        LOGOS.put("Territory FM", "https://higherlogicdownload.s3.amazonaws.com/CBAA/f91521e2-cb7a-4f99-8ca0-552342c290f7/UploadedImages/Photos/DAB_Logos/TFM_Logo.png");
        // 2WEB: from media.info's logo archive (in use since Oct 2016).
        LOGOS.put("2WEB", "https://media.info/l/o/6/6409.1476504049.png");
        LOGOS.put("Tasman FM", "https://tasmanfm.com.au/wp-content/uploads/2025/04/cropped-Tasman-FM-Logo.jpg");
        LOGOS.put("Pop 91.3", "https://image.live365.com/download/5b1c3164-2a0a-413f-b970-5dfb6dc32e0c.png/400/image.webp");
        LOGOS.put("Powerzone", "https://www.powerzone.me/wp-content/uploads/2020/05/cropped-PZlogo-270x270.png");
        LOGOS.put("Aircheck Radio", "https://www.aircheckradio.us/brand/aircheck-radio-wordmark.png");
    }

    /** The station's own logo image, if one is listed. */
    static String logoForStation(String name) {
        return LOGOS.get(name);
    }

    /** The station's website, or null if none is listed. */
    static String forStation(String name) {
        return SITES.get(name);
    }
}
