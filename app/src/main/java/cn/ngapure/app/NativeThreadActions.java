package cn.ngapure.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native official post reactions, reports and thread polls. All network methods are blocking.
 * Protocol evidence: official js_read postScoreAdd, voteDefault/voteBet/vote.submit;
 * js_commonui logPost and __API.vote. No page or server JavaScript is executed. */
public final class NativeThreadActions {
    private static final Charset GB = Charset.forName("GB18030");
    private static final int LIMIT = 4 * 1024 * 1024;
    // raw=3 is also served in this legacy document envelope. Accept only its inert data script.
    private static final Pattern LEGACY_HTML = Pattern.compile("(?is)\\A<html>\\s*<head>\\s*<meta\\s+http-equiv=['\"]Content-Type['\"]\\s+content=['\"]text/html;\\s*charset=(?:GBK|GB18030|UTF-8)['\"]\\s*/?>\\s*</head>\\s*<body>\\s*<script>\\s*(window\\.script_m(?:uti|ulti)_get_var_store\\s*=.*?)\\s*</script>\\s*</body>\\s*</html>\\z");
    private static final Object WRITE_LOCK = new Object();
    private static final Set<String> IN_FLIGHT = new HashSet<>();
    private final SessionSource sessions;
    private final Transport transport;
    private final Journal journal;

    public enum Kind { NOT_LOGGED_IN, PERMISSION, CHALLENGE_REQUIRED, VALIDATION, SERVER,
        PROTOCOL, NETWORK, OUTCOME_UNKNOWN, SESSION_CHANGED, BUSY }
    public static final class ActionException extends IOException {
        private static final long serialVersionUID = 1L;
        public final Kind kind;
        public final boolean uncertain;
        private ActionException(Kind kind, String text, Throwable cause) {
            super(text, cause); this.kind = kind; uncertain = kind == Kind.OUTCOME_UNKNOWN;
        }
    }
    public static final class Reaction {
        public final String message;
        /** Server-confirmed net recommendation change; never treated as a positive-vote count. */
        public final int delta;
        /** Some acknowledgements omit the delta; zero is then only an unused placeholder. */
        public final boolean deltaKnown;
        /** +1 up, -1 down, 0 neither. Derived only from the official response flags. */
        public final int selected;
        /** Zero flags leave the previous selection unchanged, as in the official client. */
        public final boolean selectionKnown;
        private Reaction(String text, int delta, boolean deltaKnown, int flags) {
            message = text; this.delta = delta; selected = (flags & 4) != 0 ? 1 : (flags & 8) != 0 ? -1 : 0;
            this.deltaKnown = deltaKnown;
            selectionKnown = flags != 0;
        }
    }
    /** Confirmation binds a reaction to the account and route which opened its sheet. */
    public static final class ReactionDraft {
        public final String tid, pid;
        public final int direction;
        private final NativeThreadActions owner;
        private final String fingerprint;
        private boolean submitted, inFlight;
        private ReactionDraft(NativeThreadActions owner, Session session, String tid, String pid, int direction) {
            this.owner = owner; fingerprint = session.fingerprint;
            this.tid = tid; this.pid = pid; this.direction = direction;
        }
    }
    public static final class Acknowledgement {
        public final String message;
        private Acknowledgement(String message) { this.message = message; }
    }
    public static final class ReportDraft {
        public final String tid, pid;
        public final List<String> reasons;
        private final NativeThreadActions owner;
        private final String fingerprint;
        private boolean submitted;
        private ReportDraft(NativeThreadActions owner, Session s, String tid, String pid, List<String> reasons) {
            this.owner = owner; fingerprint = s.fingerprint; this.tid = tid; this.pid = pid;
            this.reasons = Collections.unmodifiableList(reasons);
        }
    }
    public static final class PollOption {
        public final String id, title;
        public final int group;
        public final boolean heading, resultsKnown;
        public final long votes, amount;
        private PollOption(String id, String title, int group, boolean heading, boolean known, long votes, long amount) {
            this.id = id; this.title = title; this.group = group; this.heading = heading;
            resultsKnown = known; this.votes = votes; this.amount = amount;
        }
    }
    public static final class Poll {
        public final String tid, title, privilege;
        public final boolean present;
        /** Official types: 0 regular poll, 1 copper bet, 2 score, 3 individual score, 4 questionnaire. */
        public final int type, maxSelect, displayOptions;
        public final long end, min, max, participants;
        public final List<PollOption> options;
        /** Official `done` identifies settled winning bet options, never this user's past votes. */
        public final Set<String> winningOptions;
        private final NativeThreadActions owner;
        private final String fingerprint;
        private final long readAt;
        private boolean submitted;
        private Poll(NativeThreadActions owner, Session s, String tid, String title, Map<String, String> data)
                throws ActionException {
            this.owner = owner; fingerprint = s.fingerprint; readAt = System.currentTimeMillis();
            this.tid = tid; this.title = title; present = !data.isEmpty();
            type = (int) number(data.get("type"), 0, 0, 4, "投票类型");
            maxSelect = (int) number(data.get("max_select"), 1, 0, 500, "最多选项");
            displayOptions = (int) number(data.get("opt"), 0, 0, Integer.MAX_VALUE, "投票显示设置");
            end = number(data.get("end"), 0, 0, 32503680000L, "投票截止时间");
            min = number(data.get("min"), 0, type == 2 ? -1000000000000L : 0, 1000000000000L, "最小数额");
            max = number(data.get("max"), 0, type == 2 ? -1000000000000L : 0, 1000000000000L, "最大数额");
            privilege = clean(data.get("priv"));
            List<String> ids = new ArrayList<>();
            for (String key : data.keySet()) if (key.matches("[1-9][0-9]{0,8}")) ids.add(key);
            Collections.sort(ids, (a, b) -> Long.compare(Long.parseLong(a), Long.parseLong(b)));
            if (ids.size() > 500) throw fail(Kind.PROTOCOL, "投票选项过多");
            List<PollOption> choices = new ArrayList<>(); int group = 0; long people = 0;
            for (String id : ids) {
                String text = clean(data.get(id));
                boolean heading = text.startsWith("===") && Long.parseLong(tid) > 38056407;
                if (heading) group++;
                String count = data.get("_" + id); boolean known = count != null && !count.isEmpty();
                long votes = 0, amount = 0;
                if (known) {
                    String[] values = count.split(",", -1);
                    if (values.length > 3 || values.length < 1) throw fail(Kind.PROTOCOL, "投票统计格式无效");
                    votes = number(values[0], 0, 0, 1000000000000L, "票数");
                    if (values.length > 1) amount = number(values[1], 0, type == 2 ? -1000000000000L : 0, 1000000000000L, "总数额");
                    if (values.length > 2) people = Math.max(people, number(values[2], 0, 0, 1000000000000L, "参与人数"));
                }
                choices.add(new PollOption(id, text, group, heading, known, votes, amount));
            }
            options = Collections.unmodifiableList(choices); participants = people;
            Set<String> done = new HashSet<>(); String previous = data.get("done");
            if (type == 1 && previous != null && !previous.isEmpty() && !"0".equals(previous)) {
                for (String id : previous.split(",")) if (ids.contains(id)) done.add(id);
            }
            winningOptions = Collections.unmodifiableSet(done);
            if (present && options.isEmpty()) throw fail(Kind.PROTOCOL, "服务器没有返回可识别的投票选项");
            if (type == 1 && (min <= 0 || max < min)) throw fail(Kind.PROTOCOL, "服务器未提供有效的投注范围");
        }
        public boolean ended() { return end > 0 && System.currentTimeMillis() / 1000 > end; }
        public int effectiveMaxSelect() { return Math.max(1, maxSelect); }
    }

    public NativeThreadActions(Context context) {
        Context app = context.getApplicationContext(); sessions = new StoreSessionSource(new NgaStore(app)); transport = new HttpTransport();
        journal = new PersistentJournal(app.getSharedPreferences("nga_thread_action_outcomes_v1", Context.MODE_PRIVATE));
    }
    NativeThreadActions(NgaStore store, Transport transport, Journal journal) {
        this(new StoreSessionSource(store), transport, journal);
    }
    NativeThreadActions(SessionSource sessions, Transport transport, Journal journal) {
        if (sessions == null || transport == null || journal == null) throw new IllegalArgumentException("dependencies");
        this.sessions = sessions; this.transport = transport; this.journal = journal;
    }

    /** Local validation only, binding one explicit tap to its current account and route. */
    public ReactionDraft prepareReaction(String tid, String pid, int direction) throws ActionException {
        tid = id(tid, false); pid = id(pid, true);
        if (direction != 1 && direction != -1) throw fail(Kind.VALIDATION, "请选择点赞或点踩");
        return new ReactionDraft(this, session(true), tid, pid, direction);
    }
    /** Compatibility entry point for callers which already own an explicit confirmation. */
    public Reaction recommend(String tid, String pid, int direction) throws ActionException {
        return recommend(prepareReaction(tid, pid, direction));
    }
    /** Local inspection only; does not retry an uncertain toggle. */
    public boolean hasUnconfirmedReaction(ReactionDraft draft) throws ActionException {
        String key = reactionKey(draft);
        synchronized (WRITE_LOCK) { return journal.contains(key); }
    }
    /** Only call after the user explicitly confirms they have checked the earlier outcome.
     * Clears this post's local guard, sends nothing, and still requires a new confirmation to submit. */
    public void resolveReactionUncertainty(ReactionDraft draft) throws ActionException {
        String key = reactionKey(draft);
        synchronized (WRITE_LOCK) {
            if (IN_FLIGHT.contains(key)) throw fail(Kind.BUSY, "操作仍在提交，请等待完成后再核对");
            if (journal.contains(key) && !journal.remove(key)) throw fail(Kind.VALIDATION, "设备无法保存核对状态，请稍后重试");
        }
    }
    private String reactionKey(ReactionDraft draft) throws ActionException {
        if (draft == null || draft.owner != this) throw fail(Kind.VALIDATION, "请重新点击点赞或点踩");
        Session s = session(true); verifyFingerprint(s, draft.fingerprint);
        return key(s, "reaction", draft.tid, draft.pid);
    }
    /** Clicking the same direction again invokes the official toggle, including removing a previous reaction. */
    public Reaction recommend(ReactionDraft draft) throws ActionException {
        if (draft == null || draft.owner != this) throw fail(Kind.VALIDATION, "请重新点击点赞或点踩");
        synchronized (draft) {
            if (draft.submitted) throw fail(Kind.VALIDATION, "请重新点击点赞或点踩");
            if (draft.inFlight) throw fail(Kind.BUSY, "操作正在提交，请稍候");
            draft.inFlight = true;
        }
        try { return sendReaction(draft); }
        finally { synchronized (draft) { draft.inFlight = false; } }
    }
    private Reaction sendReaction(ReactionDraft draft) throws ActionException {
        Session s = session(true); verifyFingerprint(s, draft.fingerprint);
        Map<String, String> args = api("topic_recommend", "add");
        // Explicit native JSON output avoids raw=3's default HTML/JavaScript transport wrapper.
        args.put("__output", "8");
        args.put("tid", draft.tid); args.put("pid", draft.pid); args.put("value", String.valueOf(draft.direction));
        String key = key(s, "reaction", draft.tid, draft.pid); begin(key);
        try {
            JSONObject root = exchange(s, "/nuke.php", args, true);
            int flags = exactInt(at(root.opt("data"), "2"), 0, 15, "点赞状态");
            if ((flags & 12) == 12) throw unknown("服务器返回了冲突的点赞状态，请先刷新帖子核对", null);
            Object rawDelta = at(root.opt("data"), "1");
            boolean deltaKnown = rawDelta != null && rawDelta != JSONObject.NULL && !"".equals(rawDelta);
            if (!deltaKnown && flags == 0) throw unknown("服务器未返回可核验的操作状态，请先核对记录", null);
            // The official postScoreAdd uses data[1]|0. State flags confirm a toggle independently
            // of its optional net delta, which must never be used to invent like/dislike totals.
            int delta = deltaKnown ? exactInt(rawDelta, -100000, 100000, "推荐值变化") : 0;
            Object rawMessage = at(root.opt("data"), "0");
            if (rawMessage != null && rawMessage != JSONObject.NULL && !(rawMessage instanceof String))
                throw unknown("服务器返回的操作提示格式无效，请先核对记录", null);
            String message = rawMessage instanceof String ? clean((String) rawMessage) : "";
            if (message.isEmpty()) message = (flags & 4) != 0 ? "已点赞" : (flags & 8) != 0 ? "已点踩" : flags != 0 ? "已撤销操作" : "操作已确认";
            verify(s); finish(key); synchronized (draft) { draft.submitted = true; } return new Reaction(message, delta, deltaKnown, flags);
        } catch (ActionException e) { rejected(key, e); throw e; }
        finally { release(key); }
    }

    /** Read-only official reasons. Merely opening the report sheet never files a report. */
    public ReportDraft prepareReport(String tid, String pid, String fid, String stid) throws ActionException {
        tid = id(tid, false); pid = id(pid, true); fid = signedId(fid); stid = stid == null || stid.isEmpty() ? "0" : id(stid, true);
        Session s = session(true); Map<String, String> args = api("log_post", "get_report_predef");
        args.put("fid", fid); args.put("stid", stid);
        JSONObject response = exchange(s, "/nuke.php", args, false);
        Object raw = at(response.opt("data"), "0");
        if (!(raw instanceof JSONObject)) throw fail(Kind.PROTOCOL, "服务器没有返回可识别的举报理由");
        JSONObject groups = (JSONObject) raw; List<String> reasons = new ArrayList<>(); Iterator<String> keys = groups.keys();
        while (keys.hasNext()) {
            Object item = groups.opt(keys.next()); if (!(item instanceof String)) continue;
            for (String part : ((String) item).split("\t")) {
                String reason = clean(part); if (!reason.isEmpty() && !reasons.contains(reason)) reasons.add(reason);
                if (reasons.size() > 100) throw fail(Kind.PROTOCOL, "举报理由过多");
            }
        }
        verify(s); return new ReportDraft(this, s, tid, pid, reasons);
    }
    /** A fresh explicit confirmation is required by the caller; no automatic retry occurs. */
    public Acknowledgement report(ReportDraft draft, String reason) throws ActionException {
        if (draft == null || draft.owner != this || draft.submitted) throw fail(Kind.VALIDATION, "请重新打开举报面板");
        Session s = session(true); verifyFingerprint(s, draft.fingerprint);
        if (reason == null || reason.trim().isEmpty() || reason.length() > 1000 || controls(reason))
            throw fail(Kind.VALIDATION, "请填写 1 至 1000 字的举报理由");
        Map<String, String> args = api("log_post", "report");
        args.put("tid", draft.tid); args.put("pid", draft.pid); args.put("info", reason.trim());
        String key = key(s, "report", draft.tid, draft.pid); begin(key);
        try {
            JSONObject root = exchange(s, "/nuke.php", args, true); String text = acknowledged(root);
            verify(s); finish(key); draft.submitted = true; return new Acknowledgement(text);
        } catch (ActionException e) { rejected(key, e); throw e; }
        finally { release(key); }
    }

    /** Reads the first post's server-provided vote field from read.php; no synthetic poll options. */
    public Poll readPoll(String tid) throws ActionException {
        tid = id(tid, false); Session s = session(false); Map<String, String> args = new LinkedHashMap<>();
        args.put("tid", tid); args.put("page", "1"); args.put("__output", "8");
        JSONObject response = exchange(s, "/read.php", args, false);
        JSONObject data = response.optJSONObject("data"); if (data == null) data = response;
        JSONObject topic = data.optJSONObject("__T"); Object rows = data.opt("__R");
        if (topic == null || !tid.equals(String.valueOf(topic.opt("tid")))
                || !(rows instanceof JSONArray || rows instanceof JSONObject)) throw fail(Kind.PROTOCOL, "投票主题数据不匹配");
        Object raw = null;
        for (JSONObject row : objects(rows)) {
            String pid = row.optString("pid", "0");
            if ("0".equals(pid) || row.optInt("lou", -1) == 0 || row.optInt("index", -1) == 0) { raw = row.opt("vote"); break; }
        }
        if (raw == null || raw == JSONObject.NULL || String.valueOf(raw).isEmpty() || "0".equals(String.valueOf(raw))) {
            Object misc = topic.opt("post_misc_var"); raw = at(misc, "vote");
        }
        Poll result = new Poll(this, s, tid, clean(topic.optString("subject", "投票")), decodePoll(raw));
        verify(s); return result;
    }

    public Acknowledgement vote(Poll poll, List<String> selectedIds) throws ActionException {
        Map<String, Long> selections = new LinkedHashMap<>();
        if (selectedIds != null) for (String id : selectedIds) {
            if (selections.put(id, 0L) != null) throw fail(Kind.VALIDATION, "不能重复选择同一选项");
        }
        return vote(poll, selections);
    }
    /** For type 0 use selected IDs with value 0; for type 1 values are explicit copper amounts. */
    public Acknowledgement vote(Poll poll, Map<String, Long> selections) throws ActionException {
        if (poll == null || poll.owner != this || !poll.present || poll.submitted) throw fail(Kind.VALIDATION, "请重新打开投票面板");
        Session s = session(true); verifyFingerprint(s, poll.fingerprint);
        if (System.currentTimeMillis() - poll.readAt > 5 * 60 * 1000) throw fail(Kind.VALIDATION, "投票信息已过期，请重新打开");
        if (poll.type != 0 && poll.type != 1) throw fail(Kind.VALIDATION, "该主题通过回复提交评分或问答，请使用原生回复编辑器");
        if (poll.ended()) throw fail(Kind.VALIDATION, "投票已结束");
        if (selections == null || selections.isEmpty() || selections.size() > poll.options.size()) throw fail(Kind.VALIDATION, "请先选择投票选项");
        Map<Integer, Integer> groups = new HashMap<>(); StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, Long> entry : selections.entrySet()) {
            PollOption found = null;
            for (PollOption option : poll.options) if (option.id.equals(entry.getKey()) && !option.heading) found = option;
            if (found == null) throw fail(Kind.VALIDATION, "投票选项不属于当前主题");
            int count = groups.containsKey(found.group) ? groups.get(found.group) + 1 : 1;
            if (count > poll.effectiveMaxSelect()) throw fail(Kind.VALIDATION, "每组最多选择 " + poll.effectiveMaxSelect() + " 项");
            groups.put(found.group, count);
            if (encoded.length() > 0) encoded.append(','); encoded.append(found.id);
            if (poll.type == 1) {
                Long value = entry.getValue();
                if (value == null || value < poll.min || value > poll.max) throw fail(Kind.VALIDATION, "投注数额应在 " + poll.min + " 至 " + poll.max + " 铜币之间");
                encoded.append(',').append(value);
            } else if (entry.getValue() == null || entry.getValue() != 0) throw fail(Kind.VALIDATION, "普通投票不接受投注数额");
        }
        Map<String, String> args = api("vote", "vote"); args.put("tid", poll.tid); args.put("voteid", encoded.toString());
        String key = key(s, "vote", poll.tid, "0"); begin(key);
        try {
            JSONObject response = exchange(s, "/nuke.php", args, true); String message = acknowledged(response);
            verify(s); finish(key); poll.submitted = true; return new Acknowledgement(message);
        } catch (ActionException e) { rejected(key, e); throw e; }
        finally { release(key); }
    }

    private Session session(boolean login) throws ActionException {
        String host = sessions.host(), cookie = sessions.cookie();
        if (!NgaStore.HOSTS.contains(host) || headerControls(cookie)) throw fail(Kind.VALIDATION, "无效的 NGA 会话");
        if (login && (!sessions.loggedIn() || cookie.isEmpty())) throw fail(Kind.NOT_LOGGED_IN, "请先登录 NGA");
        return new Session(host, cookie);
    }
    private void verify(Session expected) throws ActionException { verifyFingerprint(session(false), expected.fingerprint); }
    private static void verifyFingerprint(Session actual, String fingerprint) throws ActionException {
        if (!actual.fingerprint.equals(fingerprint)) throw fail(Kind.SESSION_CHANGED, "登录账号或线路已变化，请重新打开面板；刚才的写入结果需回原账号核对");
    }
    private String key(Session s, String action, String tid, String pid) throws ActionException { return digest(s.fingerprint + "\0" + action + "\0" + tid + "\0" + pid); }
    private void begin(String key) throws ActionException {
        synchronized (WRITE_LOCK) {
            if (IN_FLIGHT.contains(key)) throw fail(Kind.BUSY, "操作正在提交，请稍候");
            if (journal.contains(key)) throw unknown("此操作上次结果尚未确认，为避免重复提交已暂停重试；请先在原账号核对记录", null);
            if (!journal.put(key)) throw fail(Kind.VALIDATION, "设备无法保存提交状态，尚未发送请求");
            IN_FLIGHT.add(key);
        }
    }
    private void finish(String key) throws ActionException {
        synchronized (WRITE_LOCK) { if (!journal.remove(key)) throw unknown("服务器已返回结果，但设备无法保存确认状态；请先核对记录", null); }
    }
    private void rejected(String key, ActionException failure) {
        // A session switch after dispatch cannot prove that the old account's write did not happen.
        if (!failure.uncertain && failure.kind != Kind.SESSION_CHANGED) synchronized (WRITE_LOCK) { journal.remove(key); }
    }
    private static void release(String key) { synchronized (WRITE_LOCK) { IN_FLIGHT.remove(key); } }

    private JSONObject exchange(Session s, String path, Map<String, String> args, boolean write) throws ActionException {
        verify(s); Request request;
        try {
            String form = form(args); URL url = new URL("https", s.host, path + (write ? "" : "?" + form));
            request = new Request(url, write ? "POST" : "GET", write ? form.getBytes(StandardCharsets.US_ASCII) : new byte[0]);
            request.headers.put("Cookie", s.cookie); request.headers.put("User-Agent", "NGA_WP_JW");
            request.headers.put("Accept", "application/json,text/javascript,text/plain;q=0.9");
            request.headers.put("Origin", "https://" + s.host); request.headers.put("Referer", "https://" + s.host + "/");
            request.headers.put("Content-Type", "application/x-www-form-urlencoded; charset=GB18030");
        } catch (IOException e) { throw fail(Kind.PROTOCOL, "无法构造 NGA 请求"); }
        Response response;
        try { response = transport.execute(request); }
        catch (IOException failure) {
            if (write) throw unknown("连接中断，服务器可能已收到操作；请先核对，客户端不会自动重试", failure);
            throw new ActionException(Kind.NETWORK, "无法连接 NGA，请稍后重试", failure);
        }
        if (response.status >= 300 && response.status < 400) {
            if (write) throw unknown("提交发生跳转，结果尚未确认，请先核对记录", null);
            throw fail(Kind.NETWORK, "NGA 接口发生跳转，请切换线路后重试");
        }
        JSONObject root;
        try { root = parse(response); }
        catch (Exception malformed) {
            String text = new String(response.bytes, GB).toLowerCase(Locale.ROOT);
            if (challenge(text)) {
                if (write) throw unknown("NGA 返回安全验证页面，本次提交未获确认；请完成验证并先核对操作结果", malformed);
                throw fail(Kind.CHALLENGE_REQUIRED, "NGA 要求安全验证，请完成验证后重新打开操作面板");
            }
            if (write) throw unknown("服务器没有返回可识别的确认结果，请先核对记录", malformed);
            throw new ActionException(Kind.PROTOCOL, "服务器未返回可识别的原生数据", malformed);
        }
        Object error = root.opt("error");
        if (error != null && error != JSONObject.NULL && !Boolean.FALSE.equals(error)
                && !(error instanceof Number && ((Number) error).doubleValue() == 0)) {
            String text = message(error); if (!text.isEmpty()) throw classified(text);
        }
        if (root.has("code") && root.optInt("code", -1) != 0) throw classified(root.optString("msg", root.optString("message", "NGA 拒绝此次操作")));
        if (response.status < 200 || response.status >= 300) {
            if (write) throw unknown("服务器返回 HTTP " + response.status + "，请先核对操作结果", null);
            throw fail(Kind.NETWORK, "NGA 服务器返回 HTTP " + response.status);
        }
        verify(s); return root;
    }
    private static String acknowledged(JSONObject root) throws ActionException {
        Object raw = at(root.opt("data"), "0");
        if (!(raw instanceof String) || clean((String) raw).isEmpty()) throw unknown("服务器没有明确确认操作结果，请先核对记录", null);
        return clean((String) raw);
    }
    static Map<String, String> decodePoll(Object raw) throws ActionException {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw == JSONObject.NULL || "".equals(raw) || "0".equals(String.valueOf(raw))) return result;
        if (raw instanceof JSONObject) {
            JSONObject obj = (JSONObject) raw; Iterator<String> keys = obj.keys();
            while (keys.hasNext()) { String key = keys.next(); Object value = obj.opt(key);
                if (!(value instanceof Number || value instanceof String)) {
                    if (key.matches("_?[1-9][0-9]{0,8}|type|opt|max_select|min|max|end|priv|done")) throw fail(Kind.PROTOCOL, "投票字段格式无效");
                    continue;
                }
                result.put(key, String.valueOf(value)); }
        } else if (raw instanceof String && ((String) raw).length() <= 200000) {
            String[] parts = ((String) raw).split("~", -1);
            if (parts.length % 2 != 0 || parts.length > 3000) throw fail(Kind.PROTOCOL, "投票字段格式无效");
            for (int i = 0; i < parts.length; i += 2) if (result.put(parts[i], parts[i + 1]) != null) throw fail(Kind.PROTOCOL, "投票字段重复");
        } else throw fail(Kind.PROTOCOL, "投票字段格式无效");
        return result;
    }
    private static JSONObject parse(Response response) throws Exception {
        if (response.bytes == null || response.bytes.length > LIMIT) throw new IOException("response length");
        String type = response.contentType == null ? "" : response.contentType.toLowerCase(Locale.ROOT); String raw;
        if (type.contains("gbk") || type.contains("gb2312") || type.contains("gb18030")) raw = new String(response.bytes, GB);
        else try { raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.bytes)).toString(); }
        catch (CharacterCodingException invalid) { raw = new String(response.bytes, GB); }
        raw = raw.trim(); if (raw.startsWith("\uFEFF")) raw = raw.substring(1).trim();
        if (raw.startsWith("<")) {
            Matcher envelope = LEGACY_HTML.matcher(raw);
            if (!envelope.matches()) throw new IOException("unrecognized HTML envelope");
            raw = envelope.group(1).trim();
        }
        if (raw.startsWith("/*$js$*/")) raw = raw.substring(8).trim();
        raw = raw.replaceFirst("^window\\.script_m(?:uti|ulti)_get_var_store\\s*=\\s*", "");
        if (!raw.startsWith("{")) throw new IOException("object required");
        JSONTokener tokener = new JSONTokener(raw); Object decoded = tokener.nextValue(); StringBuilder tail = new StringBuilder(); char c;
        while ((c = tokener.next()) != 0) tail.append(c);
        if (!(decoded instanceof JSONObject) || !tail.toString().trim().matches(";?\\s*(?:/\\*error fill content(?:(?!\\*/)[\\s\\S])*\\*/\\s*)?")) throw new IOException("trailing script");
        return (JSONObject) decoded;
    }
    private static Map<String, String> api(String lib, String action) {
        Map<String, String> args = new LinkedHashMap<>(); args.put("__lib", lib); args.put("__act", action); args.put("raw", "3"); return args;
    }
    private static String form(Map<String, String> args) throws IOException {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : args.entrySet()) {
            if (out.length() > 0) out.append('&'); out.append(URLEncoder.encode(e.getKey(), "GB18030")).append('=').append(URLEncoder.encode(e.getValue(), "GB18030"));
        }
        return out.toString();
    }
    private static String id(String value, boolean zero) throws ActionException {
        if (value == null || !value.matches(zero ? "(?:0|[1-9][0-9]{0,17})" : "[1-9][0-9]{0,17}")) throw fail(Kind.VALIDATION, "帖子或主题编号无效");
        return value;
    }
    private static String signedId(String value) throws ActionException {
        if (value == null || !value.matches("-?[1-9][0-9]{0,17}")) throw fail(Kind.VALIDATION, "版块编号无效"); return value;
    }
    private static long number(String value, long fallback, long min, long max, String label) throws ActionException {
        if (value == null || value.isEmpty()) return fallback;
        try { if (!value.matches("-?[0-9]{1,18}")) throw new NumberFormatException(); long parsed = Long.parseLong(value);
            if (parsed < min || parsed > max) throw new NumberFormatException(); return parsed;
        } catch (NumberFormatException error) { throw fail(Kind.PROTOCOL, label + "格式无效"); }
    }
    private static int exactInt(Object raw, int min, int max, String label) throws ActionException {
        if (!(raw instanceof String || raw instanceof Number)) throw unknown("服务器未返回可核验的" + label, null);
        if (String.valueOf(raw).isEmpty()) throw unknown("服务器未返回可核验的" + label, null);
        try { return (int) number(String.valueOf(raw), Long.MIN_VALUE, min, max, label); }
        catch (ActionException invalid) { throw unknown("服务器返回的" + label + "无效，请先刷新核对", invalid); }
    }
    private static Object at(Object object, String key) {
        if (object instanceof JSONObject) return ((JSONObject) object).opt(key);
        if (object instanceof JSONArray && key.matches("[0-9]{1,4}")) return ((JSONArray) object).opt(Integer.parseInt(key)); return null;
    }
    private static List<JSONObject> objects(Object raw) {
        List<JSONObject> result = new ArrayList<>();
        if (raw instanceof JSONArray) { JSONArray array = (JSONArray) raw; for (int i = 0; i < array.length(); i++) if (array.optJSONObject(i) != null) result.add(array.optJSONObject(i)); }
        else if (raw instanceof JSONObject) { JSONObject obj = (JSONObject) raw; Iterator<String> keys = obj.keys(); while (keys.hasNext()) { JSONObject item = obj.optJSONObject(keys.next()); if (item != null) result.add(item); } }
        return result;
    }
    private static String message(Object raw) {
        if (raw == null || raw == JSONObject.NULL) return "";
        if (raw instanceof String || raw instanceof Number) return clean(String.valueOf(raw));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 20; i++) { Object part = at(raw, String.valueOf(i)); if (part == null) break; if (part instanceof String || part instanceof Number) { if (out.length() > 0) out.append(' '); out.append(clean(String.valueOf(part))); } }
        return out.toString();
    }
    static String clean(String raw) {
        if (raw == null) return ""; String text = raw.replaceAll("(?is)<[^>]*>", "").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").trim();
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }
    private static boolean controls(String value) { if (value == null) return true; for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 && value.charAt(i) != '\n' && value.charAt(i) != '\t' || value.charAt(i) == 127) return true; return false; }
    private static boolean headerControls(String value) { if (value == null) return true; for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) == 127) return true; return false; }
    private static boolean challenge(String text) { return text.contains("验证码") || text.contains("安全验证") || text.contains("人机验证") || text.contains("验证手机") || text.contains("captcha") || text.contains("challenge"); }
    private static ActionException classified(String text) {
        String low = text.toLowerCase(Locale.ROOT); Kind kind = challenge(low) ? Kind.CHALLENGE_REQUIRED
                : low.contains("登录") || low.contains("not logged") || low.contains("2048:") ? Kind.NOT_LOGGED_IN
                : low.contains("权限") || low.contains("禁言") || low.contains("禁止") || low.contains("无权") || low.contains("不能") ? Kind.PERMISSION : Kind.SERVER;
        return fail(kind, clean(text));
    }
    private static String digest(String value) throws ActionException {
        try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString(); }
        catch (Exception e) { throw fail(Kind.PROTOCOL, "设备无法绑定 NGA 会话"); }
    }
    private static ActionException fail(Kind kind, String text) { return new ActionException(kind, text, null); }
    private static ActionException unknown(String text, Throwable cause) { return new ActionException(Kind.OUTCOME_UNKNOWN, text, cause); }
    private static final class Session {
        final String host, cookie, fingerprint;
        Session(String host, String cookie) throws ActionException { this.host = host; this.cookie = cookie; fingerprint = digest(host + "\0" + cookie); }
    }
    interface Journal { boolean contains(String key); boolean put(String key); boolean remove(String key); }
    interface SessionSource { String host(); String cookie(); boolean loggedIn(); }
    private static final class StoreSessionSource implements SessionSource {
        private final NgaStore store;
        StoreSessionSource(NgaStore store) { if (store == null) throw new IllegalArgumentException("store"); this.store = store; }
        @Override public String host() { return store.host(); }
        @Override public String cookie() { return store.cookie(); }
        @Override public boolean loggedIn() { return store.loggedIn(); }
    }
    private static final class PersistentJournal implements Journal {
        private final SharedPreferences preferences;
        PersistentJournal(SharedPreferences p) { preferences = p; }
        @Override public boolean contains(String key) { return preferences.contains(key); }
        @Override public boolean put(String key) { return save(key, true); }
        @Override public boolean remove(String key) { return save(key, false); }
        /** All production callers hold WRITE_LOCK, including the failed-commit rollback. */
        private boolean save(String key, boolean pending) {
            Object before = preferences.getAll().get(key);
            SharedPreferences.Editor editor = preferences.edit();
            if (pending) editor.putBoolean(key, true); else editor.remove(key);
            if (editor.commit()) return true;
            // Android changed its live map even though this transaction was not acknowledged.
            // An unsent action must remain retryable; a failed acknowledgement must stay guarded.
            SharedPreferences.Editor rollback = preferences.edit();
            if (before instanceof Boolean) rollback.putBoolean(key, (Boolean) before);
            else if (before instanceof String) rollback.putString(key, (String) before);
            else if (before instanceof Integer) rollback.putInt(key, (Integer) before);
            else if (before instanceof Long) rollback.putLong(key, (Long) before);
            else if (before instanceof Float) rollback.putFloat(key, (Float) before);
            else if (before instanceof java.util.Set) rollback.putStringSet(key, strings(before));
            else rollback.remove(key);
            rollback.commit(); return false;
        }
        @SuppressWarnings("unchecked")
        private static Set<String> strings(Object value) { return new HashSet<>((Set<String>) value); }
    }
    interface Transport { Response execute(Request request) throws IOException; }
    static final class Request {
        final URL url; final String method; final byte[] body; final Map<String, String> headers = new LinkedHashMap<>();
        Request(URL url, String method, byte[] body) { this.url = url; this.method = method; this.body = body; }
    }
    static final class Response {
        final int status; final byte[] bytes; final String contentType;
        Response(int status, byte[] bytes, String type) { this.status = status; this.bytes = bytes; contentType = type; }
    }
    private static final class HttpTransport implements Transport {
        @Override public Response execute(Request request) throws IOException {
            HttpURLConnection c = (HttpURLConnection) request.url.openConnection(); c.setConnectTimeout(15000); c.setReadTimeout(25000);
            c.setInstanceFollowRedirects(false); c.setUseCaches(false); c.setRequestMethod(request.method);
            for (Map.Entry<String, String> h : request.headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            try {
                if ("POST".equals(request.method)) { c.setDoOutput(true); c.setFixedLengthStreamingMode(request.body.length); try (OutputStream out = c.getOutputStream()) { out.write(request.body); } }
                int status = c.getResponseCode(); if (c.getContentLengthLong() > LIMIT) throw new IOException("NGA response too large");
                try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int read;
                    if (in != null) while ((read = in.read(buffer)) != -1) { if (out.size() + read > LIMIT) throw new IOException("NGA response too large"); out.write(buffer, 0, read); }
                    return new Response(status, out.toByteArray(), c.getContentType());
                }
            } finally { c.disconnect(); }
        }
    }
}
