package cn.ngapure.app;

import java.util.Map;
import org.json.JSONObject;

/** Synthetic metadata only; no account, network, Android view or image decoder access. */
public final class AvatarMetadataTest {
    private static int checks;
    private AvatarMetadataTest() { }
    private static void check(boolean condition, String name) { checks++; if (!condition) throw new AssertionError(name); }
    public static void main(String[] args) throws Exception {
        check(AvatarSources.normalize(".a/1_123.png").equals("https://img.nga.cn/avatars/2002/001/000/000/1_123.png"), "official hexadecimal path, low ID");
        check(AvatarSources.normalize(".a/305419896_123.webp?42").equals("https://img.nga.cn/avatars/2002/678/345/012/305419896_123.webp?42"), "official hexadecimal path, all segments");
        check(AvatarSources.normalize("https://img4.nga.cn/avatars/2002/test.png").equals("https://img4.nga.cn/avatars/2002/test.png"), "img4 official avatar retained");
        check(AvatarSources.normalize("http://img.nga.178.com/a.png").equals("https://img.nga.178.com/a.png"), "legacy official HTTP metadata upgraded");
        check(AvatarSources.normalize("//img8.nga.cn/a.png").equals("https://img8.nga.cn/a.png"), "protocol relative metadata upgraded");
        check(AvatarSources.normalize("42.gif").equals("https://img4.nga.cn/ngabbs/face/42.gif"), "official legacy face filename");
        check(AvatarSources.normalize("{\"t\":0,\"l\":2,\"0\":{\"0\":\".a/1_123.png\",\"cX\":10},\"1\":\"42.gif\"}").endsWith("/1_123.png"), "official multi-avatar object selects real candidate");
        check(AvatarSources.normalize("[\"https://attacker.invalid/a.png\",\"42.gif\"]").endsWith("/face/42.gif"), "unsafe candidate skipped");
        check(AvatarSources.normalize("https://img4.nga.cn/a.png|ignored").equals("https://img4.nga.cn/a.png"), "legacy candidate delimiter");
        for (String invalid : new String[]{"https://img.nga.cn.evil.invalid/a.png", "https://user@img.nga.cn/a.png", "https://img.nga.cn:444/a.png", "https://img.nga.cn/%2e%2e/private", "https://img.nga.cn/a.png#fragment", "javascript:alert(1)", "data:image/png;base64,AAAA", "../x.png", ".a/2147483648_1.png", "\\\\img.nga.cn\\a.png", "https://img.nga.cn/a\n.png", "{broken", "", null})
            check(AvatarSources.normalize(invalid).isEmpty(), "invalid avatar rejected: " + invalid);
        check(AvatarSources.normalize("t2|.a/1_123.png|42.gif").endsWith("/1_123.png"), "official leading multi-avatar type marker is skipped");
        check(AvatarSources.normalize("t2%7C.a/1_123.png%7c42.gif").endsWith("/1_123.png"), "official URL-encoded separators");
        check(AvatarSources.normalize("https://attacker.invalid/a.png|42.gif").endsWith("/face/42.gif"), "invalid earlier avatar no longer hides a later real candidate");
        check(AvatarSources.normalize("{\"0\":{\"0\":\"t2|.a/1_123.png\"}}").endsWith("/1_123.png"), "nested official multi-avatar delimiter");
        for (String host : new String[]{"img1.nga.cn", "img2.nga.cn", "img15.nga.cn", "img10.nga.178.com", "img6.ngacn.cc", "IMG2.NGA.CN"})
            check(AvatarSources.normalize("https://" + host + "/avatars/2002/a.png").endsWith("/a.png"), "official numeric CDN host accepted");
        for (String host : new String[]{"img2.nga.cn.attacker.invalid", "img2x.nga.cn", "img1234.nga.cn", "img.ngaXcn", "img-1.nga.cn", "any.nga.cn"})
            check(!AvatarSources.allowedHost(host), "similar-looking or unbounded CDN host rejected");
        VoteMetrics support = VoteMetrics.from(new JSONObject().put("recommend", "0,15,4"));
        check(support.likesKnown && support.likes == 15 && support.dislikesKnown && support.dislikes == 4 && support.scoreKnown && support.score == 11, "separate support / oppose / net fields");
        VoteMetrics negative = VoteMetrics.from(new JSONObject().put("recommend", "0,2,5"));
        check(negative.likes == 2 && negative.score == 0, "official net display is clamped, support retained");
        check(negative.summary().equals("点赞 2 · 点踩 5") && negative.countsKnown(), "negative net cannot hide either independent reaction count");
        for (Object raw : new Object[]{0, 15, "10"}) {
            VoteMetrics net = VoteMetrics.from(new JSONObject().put("recommend", raw));
            check(net.scoreKnown && !net.likesKnown && !net.dislikesKnown, "numeric recommendation is not a count of likes");
            check(net.summary().equals("点赞 — · 点踩 —") && !net.countsKnown(), "scalar net recommendation is not exposed by reaction controls");
        }
        check(!VoteMetrics.from(new JSONObject().put("replies", 99).put("score", 12).put("score_2", 3)).likesKnown, "replies / unrelated score fields cannot fabricate likes");
        for (Object raw : new Object[]{"0,1", "0,1,2,3", "0,-1,2", "0,2147483648,0", "0,1.5,0", true, JSONObject.NULL})
            check(!VoteMetrics.from(new JSONObject().put("recommend", raw)).likesKnown, "malformed counts remain unknown");
        check(!VoteMetrics.from(new JSONObject()).scoreKnown, "missing recommendation remains unknown");
        VoteMetrics topicNumber = VoteMetrics.topicFrom(new JSONObject().put("recommend", 10));
        check(!topicNumber.likesKnown && !topicNumber.scoreKnown, "topic numeric recommendation is not a proven score");
        check(!VoteMetrics.topicFrom(new JSONObject().put("recommend", "10")).scoreKnown, "topic numeric string recommendation is not a proven score");
        VoteMetrics topicTuple = VoteMetrics.topicFrom(new JSONObject().put("recommend", "0,15,4"));
        check(topicTuple.likesKnown && topicTuple.likes == 15 && topicTuple.dislikes == 4, "topic explicit support/oppose tuple remains known");
        String html = "<script>postArg.proc(1,$('container'),$('subject'),$('content'),$('sign'),$('user'),$('info'),$('button'),null,null,456,0,null,'1',123,'0,15,4','4','',null);</script>";
        Map<String, VoteMetrics> rows = VoteMetrics.htmlPosts(html);
        check(rows.get("1:456").likes == 15 && rows.get("1:456").dislikes == 4, "server HTML scalar argument extraction");
        check(VoteMetrics.htmlPosts(html.replace("'0,15,4'", "stealCredentials()")).isEmpty(), "HTML expressions are never evaluated or accepted as metrics");
        check(VoteMetrics.htmlPosts(html.replace("postArg.proc", "unrelated.proc")).isEmpty(), "unrelated script ignored");
        check(VoteMetrics.htmlPosts(html.replace("<script>", "<pre>").replace("</script>", "</pre>")).isEmpty(), "literal post content cannot supply vote metadata");
        System.out.println("PASS avatar and vote metadata: " + checks + " checks");
    }
}
