package cn.ngapure.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Verified public NGA board artwork, captured on 2026-10-19.
 *
 * The official Android app uses the 180px /f/app/ endpoint, which covers many
 * boards absent from the legacy website's small-icon whitelist. An exact URL
 * maps to one bundled, unchanged PNG; no caller-controlled path is opened.
 * SHA-256 and source evidence are stored in assets/forum-icons/index.json.
 */
public final class BoardIconCatalog {
    private static final String FORUM_BASE = "https://img4.nga.cn/ngabbs/nga_classic/f/app/";
    private static final String COLLECTION_BASE = "https://img4.nga.cn/proxy/cache_attach/ficon/";
    private static final Set<String> FORUM_IDS = ids(
            "-10308342,-103330,-1459709,-149110,-1513130,-15219445,-152678,-1534666,-187628,-195362,-202020"
            + "," + "-2068947,-2081117,-21175563,-235147,-2371813,-2671,-343809,-34587507,-349066,-353371,-362960,-373173"
            + "," + "-37550969,-38122457,-38213667,-39223361,-40530437,-40639972,-40743354,-41232751,-41374941,-444012"
            + "," + "-447601,-452227,-4567100,-46468,-47218,-4760591,-51095,-547859,-576177,-5944654,-5951001,-60157311"
            + "," + "-60204499,-60252908,-60374520,-608808,-61285727,-6194253,-7,-7202235,-7678526,-7861121,-7955747"
            + "," + "-81981,-8725919,10,102,124,181,182,183,184,185,186,187,188,189,191,200,213,218,230,240,254,255,264"
            + "," + "272,300,306,310,318,320,321,323,327,332,334,335,353,388,390,406,411,414,416,420,422,425,426,427,428"
            + "," + "431,432,435,436,441,442,443,444,447,452,453,454,455,459,463,469,477,480,481,482,485,486,489,490,492"
            + "," + "494,495,498,510343,510344,510348,510349,510353,510354,510357,510363,510368,510371,510373,510374"
            + "," + "510379,510381,510382,510384,510385,510389,510390,510397,510406,510407,510412,510414,510416,510417"
            + "," + "510419,510421,510422,510423,510424,510431,510432,510438,510442,510443,510445,510460,510462,510468"
            + "," + "510471,510472,510480,510481,510482,510484,510485,510486,510487,510488,510489,510494,510497,510500"
            + "," + "510501,510502,510503,510504,510508,510521,510522,510523,510558,510559,510560,510566,510569,513,514"
            + "," + "515,516,519,523,524,536,537,538,540,549,550,551,552,555,556,557,558,559,560,561,563,564,568,569,571"
            + "," + "575,591,593,595,598,599,600,601,603,604,605,607,609,611,614,615,616,617,618,622,623,624,625,626,627"
            + "," + "628,629,630,631,632,633,634,636,638,639,640,641,642,643,644,647,650,659,660,661,662,664,678,679,680"
            + "," + "681,685,689,691,692,693,695,696,7,701,705,707,708,709,710,711,714,716,717,722,723,724,726,732,750,751"
            + "," + "753,755,757,759,760,764,765,767,769,770,771,772,773,779,782,784,794,795,796,803,808,812,814,816,817"
            + "," + "818,822,823,830,831,834,836,839,840,841,842,843,844,846,847,848,851,852,853,854,855,859,860,861,862");
    private static final Set<String> COLLECTION_IDS = ids(
            "10050613,10126611,10140946,10436564,10457312,10990054,11105519,11200121,11291877,11489391,11597730"
            + "," + "11734486,12067303,12290633,12367942,12419343,12466411,12679759,12776235,12858335,12882700,12990470"
            + "," + "13043110,13086244,13196199,13225011,13309303,13361173,13401426,13699614,13702155,13737106,13899987"
            + "," + "14073850,14274222,14355027,14906309,15173226,15521348,16265595,17233763,17340805,17612007,17648142"
            + "," + "17798179,18061643,18079631,18133076,18343564,18739125,18828824,18828908,18855745,19317848,19456658"
            + "," + "19537132,19539190,20017472,20348331,20391982,20440715,20466883,20566468,20640743,21051903,21185253"
            + "," + "21429935,21891994,21991744,22088087,22090017,22176633,22293254,22356402,22450886,22495125,22597494"
            + "," + "22670766,22742346,22743480,22833687,22944341,23350704,23426173,23426622,23439862,23465764,23502906"
            + "," + "23588623,23726924,23791095,23904632,23904706,24102639,24128991,24301323,24454152,24903007,25296980"
            + "," + "25327031,25565083,25589310,26135608,26144509,26149723,26150366,26243707,26396824,26690192,26984305"
            + "," + "26991223,27334967,27408532,27437508,27506413,27524441,27613171,27789543,27809443,27881729,27921038"
            + "," + "28088764,28229506,28340492,28343565,28436135,28842347,28937182,28967840,29147527,29182350,29657842"
            + "," + "29684154,29789762,30022469,30081562,30149683,30179445,30326649,30610206,31190666,31576766,31579227"
            + "," + "32028468,32343742,33949415,34032251,34042379,34456121,34857785,35216726,35369501,35401737,35513266"
            + "," + "35514668,35809083,35809291,35935748,36016012,36339244,36963255,38528199,38632827,38854824,38880815"
            + "," + "38888482,39509883,39640915,39695787,39818214,40681784,40972321,41131245,46019748,47598591,8918060"
            + "," + "8961703,8977716,9079209,9481457,9605923,9821244,9823651,9957698");

    private BoardIconCatalog() { }

    private static Set<String> ids(String source) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(source.split(","))));
    }

    /**
     * Selects the board's own verified art. A collection never borrows its parent
     * board's image when its own art is unavailable. Unknown boards retain an
     * explicitly supplied, trusted legacy icon or the caller's labeled fallback.
     */
    public static String resolve(String fid, String stid, String legacyIcon) {
        if (stid != null && !stid.isEmpty()) {
            return COLLECTION_IDS.contains(stid) ? COLLECTION_BASE + stid + "v.png" : "";
        }
        if (FORUM_IDS.contains(fid)) return FORUM_BASE + fid + ".png";
        return legacy(legacyIcon);
    }

    /** Exact lookup only: query strings, aliases, encoded paths and unknown URLs do not match. */
    public static String assetFor(String url) {
        if (url == null || url.length() > 256) return "";
        if (url.startsWith(FORUM_BASE) && url.endsWith(".png")) {
            String fid = url.substring(FORUM_BASE.length(), url.length() - 4);
            if (FORUM_IDS.contains(fid)) return "forum-icons/fid_" + fid + ".png";
        } else if (url.startsWith(COLLECTION_BASE) && url.endsWith("v.png")) {
            String stid = url.substring(COLLECTION_BASE.length(), url.length() - 5);
            if (COLLECTION_IDS.contains(stid)) return "forum-icons/stid_" + stid + ".png";
        }
        return "";
    }

    public static int verifiedForumCount() { return FORUM_IDS.size(); }
    public static int verifiedCollectionCount() { return COLLECTION_IDS.size(); }

    private static String legacy(String value) {
        if (value == null || value.length() > 256) return "";
        // Only the existing official website's explicit, plain PNG mappings are retained.
        return value.matches("https://img[48]?\\.nga\\.cn/ngabbs/nga_classic/f/(?:-?[0-9]{1,18}[A-Za-z0-9_]*|s[0-9]{1,18})\\.png")
                ? value : "";
    }
}
