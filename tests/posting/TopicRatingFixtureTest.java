package cn.ngapure.app;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import static cn.ngapure.app.NativePosting.Kind;

/** Synthetic official score/reply contracts; no real posts or ratings and no network. */
public final class TopicRatingFixtureTest {
    private static int checks;
    private static final class Transport implements NativePosting.Transport {
        final Queue<Object> responses = new ArrayDeque<>(); final List<NativePosting.Request> requests = new ArrayList<>();
        Transport json(String text) { responses.add(new NativePosting.Response(200, text.getBytes(StandardCharsets.UTF_8), "application/json; charset=UTF-8")); return this; }
        Transport failure() { responses.add(new IOException("simulated disconnect")); return this; }
        @Override public NativePosting.Response execute(NativePosting.Request request) throws IOException {
            requests.add(request); Object response = responses.poll(); if (response == null) throw new AssertionError("Unexpected offline request");
            if (response instanceof IOException) throw (IOException) response; return (NativePosting.Response) response;
        }
    }
    private interface Task { void run() throws Exception; }
    private static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    private static void error(Kind kind, Task action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + kind); }
        catch (NativePosting.PostingException e) { check(e.kind == kind, "Expected " + kind + ", got " + e.kind + ": " + e.getMessage()); }
    }
    private static void invalid(Task action, String label) throws Exception { try { action.run(); throw new AssertionError(label); } catch (IllegalArgumentException expected) { check(true, label); } }
    private static JSONObject definition() throws Exception { return new JSONObject().put("type", 2).put("min", 0).put("max", 10).put("1", "画面").put("2", "玩法").put("end", 0)
            .put("count", new JSONObject().put("1", new JSONObject().put("total", 70).put("users", 10)))
            .put("opaque_statistics", new org.json.JSONArray().put(1).put(2)); }
    private static String prepare(String action, Object definition, Object previous) throws Exception {
        JSONObject topic = new JSONObject().put("tid", 456).put("authorid", 321).put("post_misc_var", new JSONObject().put("vote", definition));
        JSONObject data = new JSONObject().put("action", action).put("fid", 7).put("tid", 456).put("pid", action.equals("modify") ? 789 : 0)
                .put("subject", "标题").put("content", "原评分回复内容").put("__CU", new JSONObject().put("uid", 123)).put("__F", new JSONObject().put("name", "评分版面")).put("__T", topic);
        if (previous != null) data.put("__P", new JSONObject().put("authorid", 123).put("post_misc_var", new JSONObject().put("vote", previous)));
        else if (action.equals("modify")) data.put("__P", new JSONObject().put("authorid", 123));
        return new JSONObject().put("data", data).toString();
    }
    private static final String SUCCESS = "{\"data\":{\"__MESSAGE\":{\"0\":0,\"1\":\"发帖完毕\",\"6\":\"/read.php?tid=456#pid789\"}}}";
    private static Map<String, String> scores(String one, String two) { Map<String, String> values = new LinkedHashMap<>(); values.put("2", two); values.put("1", one); return values; }
    private static Map<String, String> body(NativePosting.Request request) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); request.write(bytes); Map<String, String> values = new LinkedHashMap<>();
        for (String part : new String(bytes.toByteArray(), StandardCharsets.US_ASCII).split("&")) { String[] pair = part.split("=", 2); values.put(URLDecoder.decode(pair[0], "GB18030"), pair.length < 2 ? "" : URLDecoder.decode(pair[1], "GB18030")); }
        return values;
    }
    private static void nativeReply() throws Exception {
        Transport t = new Transport().json(prepare("reply", definition(), null)).json(SUCCESS); NativePosting api = new NativePosting(new NgaStore(), t);
        NativePosting.Prepare p = api.prepare("reply", "7", "456", "");
        check(p.rating != null && p.rating.dimensions.size() == 2 && p.rating.min.toPlainString().equals("0") && p.rating.max.toPlainString().equals("10"), "Official type2 metadata preserved");
        check(p.rating.existing.isEmpty() && p.rating.summary(Collections.emptyMap()).equals("不提交主题评分"), "New reply has no invented previous score");
        check(p.rating.dimensions.size() == 2 && !p.rating.encode(scores("7", "9")).contains("count"), "Live count:OBJECT metadata is tolerated and never sent as a rating dimension");
        check(p.rating.encode(scores("7.50", "9")).equals("1~7.5~2~9"), "Real numeric option IDs, stable canonical scEn encoding");
        check(p.rating.summary(scores("7.5", "9")).contains("画面：7.5 分") && p.rating.summary(scores("7.5", "9")).contains("玩法：9 分"), "Explicit confirmation includes every score");
        api.submit(p, "评分标题", "经过体验后的评价", Collections.emptyList(), false, scores("7.50", "9"));
        Map<String, String> form = body(t.requests.get(1));
        check(form.get("topic_vote").equals("1~7.5~2~9") && form.get("action").equals("reply") && form.get("step").equals("2"), "Score submitted through official normal reply, not a fake vote endpoint");
        check(form.get("post_content").equals("经过体验后的评价") && form.get("tid").equals("456"), "Rating and text target frozen in same request");
        check(t.requests.size() == 2 && t.requests.get(0).method.equals("GET") && t.requests.get(1).method.equals("POST"), "Only explicit submit writes");
        error(Kind.VALIDATION, () -> api.submit(p, "", "重复提交评分", Collections.emptyList(), false, scores("8", "8")));
        check(t.requests.size() == 2, "Confirmed score token cannot be sent twice");
        Transport noScore = new Transport().json(prepare("reply", definition(), null)).json(SUCCESS); NativePosting plain = new NativePosting(new NgaStore(), noScore);
        plain.submit(plain.prepare("reply", "7", "456", ""), "", "仅回复文字不评分", Collections.emptyList());
        check(!body(noScore.requests.get(1)).containsKey("topic_vote"), "Ordinary reply does not silently rate a topic");
    }
    private static void validation() throws Exception {
        Transport t = new Transport().json(prepare("reply", definition(), null)); NativePosting a = new NativePosting(new NgaStore(), t); NativePosting.Prepare p = a.prepare("reply", "7", "456", "");
        String[] invalid = {"", "-1", "11", "1e1", "NaN", "1~2", "1&step=2", "2.1234567", "999999999999999999999"};
        for (String score : invalid) error(Kind.VALIDATION, () -> a.submit(p, "", "评分必须完整且合法", Collections.emptyList(), false, scores(score, "9")));
        Map<String, String> partial = new LinkedHashMap<>(); partial.put("1", "7");
        error(Kind.VALIDATION, () -> a.submit(p, "", "不许默默丢弃半份评分", Collections.emptyList(), false, partial));
        Map<String, String> foreign = new LinkedHashMap<>(); foreign.put("1", "7"); foreign.put("3", "8");
        error(Kind.VALIDATION, () -> a.submit(p, "", "拒绝另一个主题的评分项目", Collections.emptyList(), false, foreign));
        check(t.requests.size() == 1, "Every invalid score rejected before dispatch");
        Transport ordinary = new Transport().json(prepare("reply", new JSONObject().put("type", 0).put("1", "普通投票"), null)); NativePosting oa = new NativePosting(new NgaStore(), ordinary); NativePosting.Prepare op = oa.prepare("reply", "7", "456", "");
        check(op.rating == null, "Normal poll never becomes score inputs"); error(Kind.VALIDATION, () -> oa.submit(op, "", "不能评分普通投票", Collections.emptyList(), false, scores("3", "4")));
        Transport expired = new Transport().json(prepare("reply", definition().put("end", 1), null)); NativePosting ea = new NativePosting(new NgaStore(), expired); NativePosting.Prepare ep = ea.prepare("reply", "7", "456", "");
        check(ep.rating.ended(), "Ended score preserved in read state"); error(Kind.VALIDATION, () -> ea.submit(ep, "", "已过期不能打分", Collections.emptyList(), false, scores("3", "4")));
        String[] malformed = {"type~2~min~0~max~10~1~A~1~B", "type~2~min~0~max~10~1", "type~2~min~10~max~0~1~A", "type~2~min~0~max~NaN~1~A", "type~2~min~0~max~10"};
        for (String raw : malformed) {
            NativePosting bad = new NativePosting(new NgaStore(), new Transport().json(prepare("reply", raw, null)));
            error(Kind.PROTOCOL, () -> bad.prepare("reply", "7", "456", ""));
        }
        invalid(() -> NativeTopicRating.decode("1~A~1~B"), "Duplicate dimension key rejected");
        invalid(() -> NativeTopicRating.decode("1~A~2"), "Odd tilde sequence rejected");
    }
    private static void editExisting() throws Exception {
        String previous = "type~3~1~6.5~2~8";
        Transport t = new Transport().json(prepare("modify", definition(), previous)).json(SUCCESS); NativePosting api = new NativePosting(new NgaStore(), t); NativePosting.Prepare p = api.prepare("modify", "7", "456", "789");
        check(p.rating != null && p.rating.existing.get("1").equals("6.5") && p.rating.existing.get("2").equals("8"), "Own previous type3 rating available to edit");
        error(Kind.VALIDATION, () -> api.submit(p, "标题", "不允许清空分数伪装删除", Collections.emptyList(), false, Collections.emptyMap()));
        api.submit(p, "标题", "仅编辑正文时必须保留原评分", Collections.emptyList());
        check(body(t.requests.get(1)).get("topic_vote").equals("1~6.5~2~8"), "Legacy editor API preserves every existing score rather than dropping it");
        Transport change = new Transport().json(prepare("modify", definition(), previous)).json(SUCCESS); NativePosting ca = new NativePosting(new NgaStore(), change);
        ca.submit(ca.prepare("modify", "7", "456", "789"), "标题", "更新自己的真实评分", Collections.emptyList(), false, scores("8", "9"));
        check(body(change.requests.get(1)).get("topic_vote").equals("1~8~2~9") && body(change.requests.get(1)).get("action").equals("modify"), "Edit rating uses server-authorized own reply modification");
        String other = prepare("modify", definition(), previous).replace("\"authorid\":123", "\"authorid\":999");
        error(Kind.PERMISSION, () -> new NativePosting(new NgaStore(), new Transport().json(other)).prepare("modify", "7", "456", "789"));
        String main = prepare("modify", definition(), previous).replace("\"pid\":789", "\"pid\":0").replace("\"authorid\":321", "\"authorid\":123");
        error(Kind.PERMISSION, () -> new NativePosting(new NgaStore(), new Transport().json(main)).prepare("modify", "7", "456", ""));
        String incompatible = prepare("modify", definition(), "type~0~1~另一个普通投票");
        error(Kind.PROTOCOL, () -> new NativePosting(new NgaStore(), new Transport().json(incompatible)).prepare("modify", "7", "456", "789"));
        String partial = prepare("modify", definition(), "type~3~1~8");
        error(Kind.PROTOCOL, () -> new NativePosting(new NgaStore(), new Transport().json(partial)).prepare("modify", "7", "456", "789"));
    }
    private static void unknownAndSession() throws Exception {
        Transport t = new Transport().json(prepare("reply", definition(), null)).failure(); NgaStore store = new NgaStore(); NativePosting api = new NativePosting(store, t); NativePosting.Prepare p = api.prepare("reply", "7", "456", "");
        error(Kind.OUTCOME_UNKNOWN, () -> api.submit(p, "", "断线不确定评分结果", Collections.emptyList(), false, scores("7", "8")));
        error(Kind.OUTCOME_UNKNOWN, () -> api.submit(p, "", "不能自动重试发出重复评分", Collections.emptyList(), false, scores("7", "8")));
        check(t.requests.size() == 2, "Ambiguous rating reply never retries its prepared request");
        Transport scoped = new Transport().json(prepare("reply", definition(), null)); NativePosting sa = new NativePosting(store, scoped); NativePosting.Prepare sp = sa.prepare("reply", "7", "456", ""); store.testCookie += ";changed=yes";
        error(Kind.SESSION_CHANGED, () -> sa.submit(sp, "", "不能跨账号发送评分", Collections.emptyList(), false, scores("7", "8")));
        check(scoped.requests.size() == 1, "Session changed blocks score before POST");
        JSONObject negative = definition().put("min", -5).put("max", 5); Transport negativeT = new Transport().json(prepare("reply", negative, null)).json(SUCCESS); NativePosting na = new NativePosting(new NgaStore(), negativeT);
        NativePosting.Prepare np = na.prepare("reply", "7", "456", ""); check(np.rating.encode(scores("-2.5", "0")).equals("1~-2.5~2~0"), "Server-provided negative score range supported");
        na.submit(np, "", "按服务端范围打分", Collections.emptyList(), false, scores("-2.5", "0"));
        check(body(negativeT.requests.get(1)).get("topic_vote").equals("1~-2.5~2~0"), "Zero and negative scores retained exactly");
    }
    public static void main(String[] args) throws Exception {
        nativeReply(); validation(); editExisting(); unknownAndSession();
        System.out.println("Native topic ratings: " + checks + " offline assertions passed; no ratings/replies submitted.");
    }
}
