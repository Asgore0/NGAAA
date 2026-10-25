package cn.ngapure.app;

import org.json.JSONObject;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import static cn.ngapure.app.NativeThreadActions.Kind;

/** Every response is a deterministic offline fixture. No network or real account writes. */
public final class ThreadActionsFixtureTest {
    private static int checks;
    private static final class Journal implements NativeThreadActions.Journal {
        final Set<String> pending = new HashSet<>(); boolean writable = true;
        @Override public boolean contains(String key) { return pending.contains(key); }
        @Override public boolean put(String key) { if (!writable) return false; pending.add(key); return true; }
        @Override public boolean remove(String key) { if (!writable) return false; pending.remove(key); return true; }
    }
    private static final class Transport implements NativeThreadActions.Transport {
        final Queue<Object> replies = new ArrayDeque<>(); final List<NativeThreadActions.Request> requests = new ArrayList<>(); Runnable after;
        Transport json(String value) { replies.add(new NativeThreadActions.Response(200, value.getBytes(StandardCharsets.UTF_8), "application/json; charset=UTF-8")); return this; }
        Transport gb(String value) { replies.add(new NativeThreadActions.Response(200, value.getBytes(Charset.forName("GB18030")), "text/javascript; charset=GBK")); return this; }
        Transport response(int status, String value) { replies.add(new NativeThreadActions.Response(status, value.getBytes(StandardCharsets.UTF_8), "text/html")); return this; }
        Transport failure() { replies.add(new IOException("simulated disconnect")); return this; }
        @Override public NativeThreadActions.Response execute(NativeThreadActions.Request request) throws IOException {
            requests.add(request); Object reply = replies.poll(); if (reply == null) throw new AssertionError("Unexpected offline request");
            if (after != null) after.run();
            if (reply instanceof IOException) throw (IOException) reply; return (NativeThreadActions.Response) reply;
        }
    }
    private interface Task { void run() throws Exception; }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static NativeThreadActions.ActionException error(Kind expected, Task task) throws Exception {
        try { task.run(); throw new AssertionError("Expected " + expected); }
        catch (NativeThreadActions.ActionException e) { check(e.kind == expected, "Expected " + expected + ", actual " + e.kind + ": " + e.getMessage()); return e; }
    }
    private static NativeThreadActions api(NgaStore store, Transport t, Journal j) { return new NativeThreadActions(store, t, j); }
    private static Map<String, String> parameters(NativeThreadActions.Request request) throws Exception {
        String raw = request.method.equals("GET") ? request.url.getQuery() : new String(request.body, StandardCharsets.US_ASCII);
        Map<String, String> args = new LinkedHashMap<>();
        for (String part : raw.split("&")) { String[] pair = part.split("=", 2); args.put(URLDecoder.decode(pair[0], "GB18030"), pair.length < 2 ? "" : URLDecoder.decode(pair[1], "GB18030")); }
        return args;
    }
    private static String poll(String raw) throws Exception {
        return new JSONObject().put("data", new JSONObject().put("__T", new JSONObject().put("tid", 45678901).put("subject", "离线测试主题"))
                .put("__R", new JSONObject().put("0", new JSONObject().put("pid", 0).put("lou", 0).put("vote", raw)))).toString();
    }
    private static final String CHOICES = "type~0~max_select~1~end~0~1~选项甲~_1~3,0,4~2~选项乙~_2~1,0,4";

    private static void reactions() throws Exception {
        NgaStore s = new NgaStore(); Journal j = new Journal();
        Transport t = new Transport().json("{\"data\":[\"点赞成功\",1,4]}").gb("/*$js$*/window.script_muti_get_var_store={\"data\":[\"撤销点赞\",-1,1]};");
        NativeThreadActions a = api(s, t, j); NativeThreadActions.Reaction up = a.recommend("123", "0", 1);
        check(up.selected == 1 && up.delta == 1 && up.message.equals("点赞成功"), "Reaction exact server state");
        check(j.pending.isEmpty(), "Known result clears write journal");
        NativeThreadActions.Request request = t.requests.get(0);
        check(request.url.toString().equals("https://bbs.nga.cn/nuke.php") && request.method.equals("POST"), "Official reaction endpoint");
        Map<String, String> body = parameters(request);
        check(body.get("__lib").equals("topic_recommend") && body.get("__act").equals("add") && body.get("value").equals("1") && body.get("pid").equals("0"), "Official reaction fields");
        check(request.headers.get("Cookie").equals(s.testCookie) && request.headers.get("Origin").equals("https://bbs.nga.cn"), "Frozen official session headers");
        NativeThreadActions.Reaction removed = a.recommend("123", "0", 1);
        check(removed.selected == 0 && removed.delta == -1, "Repeat reaction uses official toggle response");
        Transport down = new Transport().json("{\"data\":{\"0\":\"反对成功\",\"1\":-1,\"2\":8}}");
        check(api(s, down, new Journal()).recommend("123", "6", -1).selected == -1, "Object indexed negative response");
        error(Kind.VALIDATION, () -> a.recommend("1&value=-1", "0", 1));
        error(Kind.VALIDATION, () -> a.recommend("1", "-1", 1));
        error(Kind.VALIDATION, () -> a.recommend("1", "0", 0));
        check(t.requests.size() == 2, "Invalid inputs send no request");
        s.testCookie = "ngaPassportUid=3\nX-Evil: yes"; error(Kind.VALIDATION, () -> a.recommend("1", "0", 1));
        s.testCookie = ""; error(Kind.NOT_LOGGED_IN, () -> a.recommend("1", "0", 1));
        s.testCookie = "a=b"; s.testHost = "bbs.nga.cn.evil.example"; error(Kind.VALIDATION, () -> a.recommend("1", "0", 1));
    }
    private static void reactionConfirmationScope() throws Exception {
        NgaStore store = new NgaStore(); Journal journal = new Journal();
        Transport transport = new Transport().json("{\"data\":[\"已点踩\",-1,8]}");
        NativeThreadActions api = api(store, transport, journal);
        NativeThreadActions.ReactionDraft draft = api.prepareReaction("200", "0", -1);
        check(transport.requests.isEmpty() && journal.pending.isEmpty(), "Opening reaction confirmation neither writes nor reads network");
        check(draft.tid.equals("200") && draft.pid.equals("0") && draft.direction == -1, "Confirmation binds exact topic head and direction");
        String previous = store.testCookie; store.testCookie = "ngaPassportUid=456; ngaPassportCid=another-fixture";
        error(Kind.SESSION_CHANGED, () -> api.recommend(draft));
        check(transport.requests.isEmpty() && journal.pending.isEmpty(), "Account switch before queued submission cannot mutate replacement account");
        store.testCookie = previous;
        check(api.recommend(draft).selected == -1 && transport.requests.size() == 1, "Same-account explicit confirmation sends exact down vote once");
        error(Kind.VALIDATION, () -> api.recommend(draft));
        check(transport.requests.size() == 1, "Consumed confirmation cannot toggle a successful operation back");
        error(Kind.VALIDATION, () -> api(new NgaStore(), new Transport(), new Journal()).recommend(api.prepareReaction("200", "1", 1)));
        error(Kind.VALIDATION, () -> api.recommend((NativeThreadActions.ReactionDraft) null));
        NativeThreadActions.ReactionDraft routeBound = api.prepareReaction("201", "2", 1);
        store.testHost = "ngabbs.com";
        error(Kind.SESSION_CHANGED, () -> api.recommend(routeBound));
        check(transport.requests.size() == 1, "NGA route changes before confirmation do not send to either host");
        Transport duplicate = new Transport().json("{\"data\":[\"已点赞\",1,4]}");
        NativeThreadActions single = api(new NgaStore(), duplicate, new Journal());
        NativeThreadActions.ReactionDraft once = single.prepareReaction("202", "0", 1);
        duplicate.after = () -> {
            try { error(Kind.BUSY, () -> single.recommend(once)); }
            catch (Exception failure) { throw new AssertionError(failure); }
        };
        single.recommend(once);
        check(duplicate.requests.size() == 1, "Same confirmation cannot dispatch a concurrent second request");
    }
    private static void unknownResults() throws Exception {
        String[] malformed = {"{}", "{\"data\":[\"success\"]}", "{\"data\":[\"success\",1,12]}", "{\"data\":[\"success\",1,99]}", "{\"data\":[\"success\",1.5,4]}", "{\"data\":[\"success\",1,4]}; alert('unsafe')", "<html>captcha challenge</html>"};
        for (String response : malformed) {
            Journal j = new Journal(); Transport t = new Transport().json(response); NativeThreadActions a = api(new NgaStore(), t, j);
            check(error(Kind.OUTCOME_UNKNOWN, () -> a.recommend("2", "3", 1)).uncertain, "Unknown explicitly surfaced");
            check(j.pending.size() == 1, "Unknown outcome durable guard");
            error(Kind.OUTCOME_UNKNOWN, () -> api(new NgaStore(), t, j).recommend("2", "3", 1));
            error(Kind.OUTCOME_UNKNOWN, () -> a.recommend("2", "3", -1));
            check(t.requests.size() == 1, "Neither same nor opposite direction retried after unknown");
        }
        for (int status : new int[]{301, 403, 500}) {
            Journal j = new Journal(); Transport t = new Transport().response(status, "bad response"); NativeThreadActions a = api(new NgaStore(), t, j);
            error(Kind.OUTCOME_UNKNOWN, () -> a.recommend("2", "4", 1)); check(j.pending.size() == 1, "Ambiguous HTTP result retained");
        }
        Journal disconnected = new Journal(); NativeThreadActions a = api(new NgaStore(), new Transport().failure(), disconnected);
        error(Kind.OUTCOME_UNKNOWN, () -> a.recommend("2", "5", 1)); check(disconnected.pending.size() == 1, "Disconnect does not retry or clear");
        Journal failSave = new Journal(); failSave.writable = false; Transport notSent = new Transport();
        error(Kind.VALIDATION, () -> api(new NgaStore(), notSent, failSave).recommend("2", "6", 1)); check(notSent.requests.isEmpty(), "Write journal must commit before sending");
        Journal failConfirm = new Journal(); Transport confirmed = new Transport().json("{\"data\":[\"已点赞\",1,4]}"); confirmed.after = () -> failConfirm.writable = false;
        error(Kind.OUTCOME_UNKNOWN, () -> api(new NgaStore(), confirmed, failConfirm).recommend("2", "7", 1)); check(failConfirm.pending.size() == 1, "Failed confirmation commit stays uncertain");
    }
    private static void errorsAndScope() throws Exception {
        String[] messages = {"你没有登录", "没有操作权限", "验证码验证", "其他服务器错误"};
        Kind[] kinds = {Kind.NOT_LOGGED_IN, Kind.PERMISSION, Kind.CHALLENGE_REQUIRED, Kind.SERVER};
        for (int i = 0; i < messages.length; i++) {
            Journal j = new Journal(); Transport t = new Transport().json(new JSONObject().put("error", new org.json.JSONArray().put(messages[i])).toString());
            NativeThreadActions a = api(new NgaStore(), t, j); error(kinds[i], () -> a.recommend("4", "0", 1));
            check(j.pending.isEmpty(), "Explicit rejection clears pending guard");
        }
        NgaStore changed = new NgaStore(); Journal j = new Journal(); Transport t = new Transport().json("{\"data\":[\"成功\",1,4]}");
        String old = changed.testCookie; t.after = () -> changed.testCookie = "ngaPassportUid=456; ngaPassportCid=other";
        NativeThreadActions a = api(changed, t, j); error(Kind.SESSION_CHANGED, () -> a.recommend("3", "0", 1)); check(j.pending.size() == 1, "Account changed after dispatch retains original unknown guard");
        changed.testCookie = old; error(Kind.OUTCOME_UNKNOWN, () -> a.recommend("3", "0", 1)); check(t.requests.size() == 1, "Returning original account cannot duplicate stale action");
    }
    private static void reports() throws Exception {
        NgaStore s = new NgaStore(); Journal j = new Journal();
        Transport t = new Transport().json("{\"data\":[{\"common\":\"不友善\\t广告\",\"board\":\"剧透\\t广告\"}]}").json("{\"data\":[\"举报已提交\"]}");
        NativeThreadActions a = api(s, t, j); NativeThreadActions.ReportDraft draft = a.prepareReport("123", "789", "-7", "0");
        check(draft.reasons.size() == 3 && draft.reasons.contains("广告"), "Official reasons deduplicated");
        check(t.requests.get(0).method.equals("GET") && parameters(t.requests.get(0)).get("__act").equals("get_report_predef"), "Opening report only reads");
        check(j.pending.isEmpty(), "No journal/write before explicit report");
        error(Kind.VALIDATION, () -> a.report(draft, "  "));
        error(Kind.VALIDATION, () -> a.report(draft, "x\0y"));
        check(t.requests.size() == 1, "Empty or malformed reasons never submitted");
        check(a.report(draft, "广告 <tag> 不应以 HTML 执行").message.equals("举报已提交"), "Official report acknowledgement");
        Map<String, String> body = parameters(t.requests.get(1));
        check(body.get("__lib").equals("log_post") && body.get("__act").equals("report") && body.get("tid").equals("123") && body.get("pid").equals("789") && body.get("info").contains("<tag>"), "Report exact form with GB18030 text encoding");
        error(Kind.VALIDATION, () -> a.report(draft, "duplicate")); check(t.requests.size() == 2, "Confirmed report token cannot be reused");
        Transport switched = new Transport().json("{\"data\":[{}]}"); NativeThreadActions owner = api(s, switched, new Journal());
        NativeThreadActions.ReportDraft stale = owner.prepareReport("1", "0", "7", ""); s.testHost = "ngabbs.com";
        error(Kind.SESSION_CHANGED, () -> owner.report(stale, "理由")); check(switched.requests.size() == 1, "Report draft pinned to origin");
        error(Kind.VALIDATION, () -> a.report(stale, "foreign owner"));
    }
    private static void polls() throws Exception {
        NgaStore s = new NgaStore(); Journal j = new Journal();
        Transport t = new Transport().json(poll(CHOICES)).json("{\"data\":[\"投票成功\"]}"); NativeThreadActions a = api(s, t, j);
        NativeThreadActions.Poll p = a.readPoll("45678901");
        check(p.present && p.type == 0 && p.options.size() == 2 && p.options.get(0).title.equals("选项甲") && p.participants == 4, "Official tilde poll data");
        check(p.options.get(0).votes == 3 && p.options.get(0).resultsKnown, "Exact results displayed");
        check(t.requests.get(0).method.equals("GET") && parameters(t.requests.get(0)).get("__output").equals("8"), "Poll fetch read-only native endpoint");
        error(Kind.VALIDATION, () -> a.vote(p, Arrays.asList("1", "2")));
        error(Kind.VALIDATION, () -> a.vote(p, Arrays.asList("99")));
        error(Kind.VALIDATION, () -> a.vote(p, Arrays.asList("1", "1")));
        check(t.requests.size() == 1, "Invalid selections no write");
        check(a.vote(p, Arrays.asList("2")).message.equals("投票成功"), "Poll confirmed");
        Map<String, String> body = parameters(t.requests.get(1)); check(body.get("__lib").equals("vote") && body.get("__act").equals("vote") && body.get("voteid").equals("2"), "Official vote payload");
        error(Kind.VALIDATION, () -> a.vote(p, Arrays.asList("1"))); check(j.pending.isEmpty(), "Submitted poll token closed");
        Transport hidden = new Transport().json(poll("type~0~max_select~0~opt~3~1~选项一~2~选项二"));
        NativeThreadActions.Poll privateResults = api(s, hidden, new Journal()).readPoll("45678901");
        check(!privateResults.options.get(0).resultsKnown && privateResults.effectiveMaxSelect() == 1 && privateResults.displayOptions == 3, "Hidden results stay unknown rather than made-up zeros");
        NativeThreadActions.Poll ordinaryDone = api(s, new Transport().json(poll(CHOICES + "~done~1")), new Journal()).readPoll("45678901");
        check(ordinaryDone.winningOptions.isEmpty(), "Done must never be represented as the current user's vote");
        Transport empty = new Transport().json(poll("")); check(!api(s, empty, new Journal()).readPoll("45678901").present, "No poll distinct from failed poll");
        String[] invalid = {"type~9~1~选项", "type~0~1~A~1~B", "type~0~1", "type~0~1~A~_1~-1,0,0", "type~0~max_select~-2~1~A", "type~1~min~3~max~2~1~A"};
        for (String raw : invalid) {
            NativeThreadActions bad = api(s, new Transport().json(poll(raw)), new Journal()); error(Kind.PROTOCOL, () -> bad.readPoll("45678901"));
        }
        Transport wrong = new Transport().json(poll(CHOICES).replace("45678901", "45678902"));
        error(Kind.PROTOCOL, () -> api(s, wrong, new Journal()).readPoll("45678901"));
        Transport script = new Transport().json(poll(CHOICES) + ";evil()"); error(Kind.PROTOCOL, () -> api(s, script, new Journal()).readPoll("45678901"));
        Transport unavailable = new Transport().failure(); error(Kind.NETWORK, () -> api(s, unavailable, new Journal()).readPoll("45678901"));
    }
    private static void groupedBetAndScope() throws Exception {
        NgaStore s = new NgaStore(); Journal j = new Journal();
        String grouped = "type~0~max_select~1~1~===组一~2~A~3~B~4~===组二~5~C~6~D";
        Transport t = new Transport().json(poll(grouped)).json("{\"data\":[\"成功\"]}"); NativeThreadActions a = api(s, t, j); NativeThreadActions.Poll p = a.readPoll("45678901");
        check(p.options.get(0).heading && p.options.get(1).group != p.options.get(4).group, "Official grouped poll headings");
        error(Kind.VALIDATION, () -> a.vote(p, Arrays.asList("1")));
        error(Kind.VALIDATION, () -> a.vote(p, Arrays.asList("2", "3")));
        a.vote(p, Arrays.asList("2", "5")); check(parameters(t.requests.get(1)).get("voteid").equals("2,5"), "Per-group rather than global max choices");
        String bet = "type~1~max_select~1~min~2~max~10~1~甲~_1~1,3,2~2~乙~_2~1,2,2";
        Transport bt = new Transport().json(poll(bet)).json("{\"data\":[\"投注已提交\"]}"); NativeThreadActions ba = api(s, bt, new Journal()); NativeThreadActions.Poll bp = ba.readPoll("45678901");
        Map<String, Long> amount = new LinkedHashMap<>(); amount.put("1", 1L); error(Kind.VALIDATION, () -> ba.vote(bp, amount));
        NativeThreadActions.Poll settled = api(s, new Transport().json(poll(bet + "~done~2")), new Journal()).readPoll("45678901");
        check(settled.winningOptions.contains("2") && !settled.winningOptions.contains("1"), "Done is explicitly settled bet winner");
        amount.put("1", 11L); error(Kind.VALIDATION, () -> ba.vote(bp, amount)); amount.put("1", 2L); ba.vote(bp, amount);
        check(parameters(bt.requests.get(1)).get("voteid").equals("1,2"), "Copper bet exact ID amount pairs");
        Transport ended = new Transport().json(poll("type~0~end~1~1~A")); NativeThreadActions ea = api(s, ended, new Journal()); NativeThreadActions.Poll ep = ea.readPoll("45678901");
        check(ep.ended(), "Server end enforced"); error(Kind.VALIDATION, () -> ea.vote(ep, Arrays.asList("1")));
        Transport st = new Transport().json(poll(CHOICES)); NativeThreadActions sa = api(s, st, new Journal()); NativeThreadActions.Poll sp = sa.readPoll("45678901");
        s.testCookie += ";changed=yes"; error(Kind.SESSION_CHANGED, () -> sa.vote(sp, Arrays.asList("1"))); check(st.requests.size() == 1, "Poll choice cannot cross login changes");
        error(Kind.VALIDATION, () -> a.vote(sp, Arrays.asList("1")));
        s.testCookie = ""; Transport anon = new Transport().json(poll(CHOICES)); NativeThreadActions aa = api(s, anon, new Journal()); NativeThreadActions.Poll ap = aa.readPoll("45678901");
        check(ap.present, "Anonymous may inspect poll"); error(Kind.NOT_LOGGED_IN, () -> aa.vote(ap, Arrays.asList("1"))); check(anon.requests.size() == 1, "Anonymous cannot vote");
    }
    private static void writeGuardsAndDuplicateTap() throws Exception {
        NgaStore s = new NgaStore(); Journal j = new Journal();
        Transport t = new Transport().json(poll(CHOICES)).failure().json(poll(CHOICES)); NativeThreadActions a = api(s, t, j);
        NativeThreadActions.Poll p = a.readPoll("45678901"); error(Kind.OUTCOME_UNKNOWN, () -> a.vote(p, Arrays.asList("1")));
        NativeThreadActions reopened = api(s, t, j); NativeThreadActions.Poll later = reopened.readPoll("45678901");
        error(Kind.OUTCOME_UNKNOWN, () -> reopened.vote(later, Arrays.asList("2")));
        check(t.requests.size() == 3 && j.pending.size() == 1, "Reopening poll cannot resend ambiguous participation");
        Journal reportGuard = new Journal(); Transport reports = new Transport().json("{\"data\":[{}]}").failure().json("{\"data\":[{}]}");
        NativeThreadActions ra = api(s, reports, reportGuard); NativeThreadActions.ReportDraft draft = ra.prepareReport("1", "2", "7", "0");
        error(Kind.OUTCOME_UNKNOWN, () -> ra.report(draft, "离线举报说明")); NativeThreadActions.ReportDraft second = ra.prepareReport("1", "2", "7", "0");
        error(Kind.OUTCOME_UNKNOWN, () -> ra.report(second, "重新打开也不重复举报"));
        check(reports.requests.size() == 3, "Report guard spans reopened sheets");
        Journal guard = new Journal(); Transport duplicate = new Transport().json("{\"data\":[\"成功\",1,4]}");
        NativeThreadActions[] current = new NativeThreadActions[]{api(s, duplicate, guard)};
        duplicate.after = () -> {
            try { error(Kind.BUSY, () -> current[0].recommend("8", "0", 1)); }
            catch (Exception failure) { throw new AssertionError(failure); }
        };
        current[0].recommend("8", "0", 1); check(duplicate.requests.size() == 1 && guard.pending.isEmpty(), "Duplicate in-flight tap cannot dispatch another mutation");
    }
    public static void main(String[] args) throws Exception {
        reactions(); reactionConfirmationScope(); unknownResults(); errorsAndScope(); reports(); polls(); groupedBetAndScope(); writeGuardsAndDuplicateTap();
        System.out.println("Native thread actions: " + checks + " offline assertions passed; no sockets opened, no real reactions/reports/votes submitted.");
    }
}
