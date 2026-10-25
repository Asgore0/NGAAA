package cn.ngapure.app;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/** Frozen public-artwork snapshot and exact-match boundary checks; no network or Android. */
public final class BoardIconCatalogTest {
    private static int checks;
    private BoardIconCatalogTest() { }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Set<String> urls = new HashSet<String>();
        Set<String> assets = new HashSet<String>();
        int forums = 0, collections = 0;
        try (BufferedReader input = Files.newBufferedReader(Paths.get(args[0]), StandardCharsets.UTF_8)) {
            String line;
            while ((line = input.readLine()) != null) {
                String[] entry = line.split("\t");
                check(entry.length == 8, "frozen provenance columns");
                String kind = entry[0], id = entry[1], url = entry[2], asset = entry[3];
                check(urls.add(url), "unique URL");
                check(assets.add(asset), "unique asset");
                check(asset.equals(BoardIconCatalog.assetFor(url)), "exact URL asset mapping " + id);
                if ("forum".equals(kind)) {
                    forums++;
                    check(url.equals(BoardIconCatalog.resolve(id, "", "")), "forum art " + id);
                    check(url.equals(BoardIconCatalog.resolve(id, null, "https://img4.nga.cn/ngabbs/nga_classic/f/7.png")), "app art takes priority");
                    check(asset.equals("forum-icons/fid_" + id + ".png"), "forum safe name");
                } else {
                    check("collection".equals(kind), "known kind");
                    collections++;
                    check(url.equals(BoardIconCatalog.resolve("300", id, "https://img4.nga.cn/ngabbs/nga_classic/f/300.png")), "collection own art");
                    check(asset.equals("forum-icons/stid_" + id + ".png"), "collection safe name");
                }
                String[] changes = {
                    url + "?v=1", url + "#fragment", url + " ", url.replace("https://", "http://"),
                    url.replace("img4.nga.cn", "img4.nga.cn.evil.test"), url.replace("img4.nga.cn", "evil.test@img4.nga.cn"),
                    url.replace(".png", ".jpg"), url.replace(".png", ".PNG"), url.replace("/" + id, "/../" + id),
                    url.replace("/" + id, "/%2e%2e/" + id), url.replace("/" + id, "//" + id),
                    url.replace("/" + id, "/0" + id), url.replace("img4.nga.cn", "IMG4.NGA.CN")
                };
                for (String changed : changes) check(BoardIconCatalog.assetFor(changed).isEmpty(), "reject transformed URL " + id);
            }
        }
        check(forums == 352 && BoardIconCatalog.verifiedForumCount() == 352, "all verified forums");
        check(collections == 184 && BoardIconCatalog.verifiedCollectionCount() == 184, "all verified collections");
        check(BoardIconCatalog.assetFor(null).isEmpty(), "null URL");
        check(BoardIconCatalog.assetFor("").isEmpty(), "empty URL");
        check(BoardIconCatalog.assetFor("file:///sdcard/private.png").isEmpty(), "no local file surface");
        check(BoardIconCatalog.assetFor("asset://forum-icons/fid_7.png").isEmpty(), "no asset URI surface");
        check(BoardIconCatalog.assetFor("https://img4.nga.cn/ngabbs/nga_classic/f/app/510490.png").isEmpty(), "known missing forum has no file");
        check(BoardIconCatalog.resolve("510490", "", "").isEmpty(), "missing official art stays fallback");
        check(BoardIconCatalog.resolve("7", "999999999", "https://img4.nga.cn/ngabbs/nga_classic/f/7.png").isEmpty(), "collection does not borrow parent");
        check(BoardIconCatalog.resolve(null, null, null).isEmpty(), "null metadata");
        check(BoardIconCatalog.resolve("7", "../../7", "").isEmpty(), "no malformed collection");
        check(BoardIconCatalog.resolve("../../7", "", "").isEmpty(), "no malformed forum");
        String legacy = "https://img4.nga.cn/ngabbs/nga_classic/f/10.png";
        check(legacy.equals(BoardIconCatalog.resolve("unknown", "", legacy)), "explicit legacy art retained");
        check(BoardIconCatalog.resolve("unknown", "", "https://evil.test/7.png").isEmpty(), "legacy trusted host");
        check(BoardIconCatalog.resolve("unknown", "", legacy + "?url=file").isEmpty(), "legacy has no query");
        check(BoardIconCatalog.assetFor(legacy).isEmpty(), "legacy does not claim bundled asset");
        System.out.println("Board icon catalog tests passed: " + checks + " checks, 536 original PNGs, 0 network requests");
    }
}
