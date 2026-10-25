package cn.ngapure.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Native post.php/attach.php protocol. Blocking methods belong on a worker thread. */
public final class NativePosting {
    private static final Charset GB = Charset.forName("GB18030");
    private static final long MIB = 1024L * 1024;
    private static final int RESPONSE_LIMIT = 2 * 1024 * 1024;
    private final NgaStore store;
    private final Transport transport;

    public enum Kind { NOT_LOGGED_IN, PERMISSION, CHALLENGE_REQUIRED, VALIDATION, SERVER,
        PROTOCOL, NETWORK, OUTCOME_UNKNOWN, SESSION_CHANGED }

    public static final class PostingException extends IOException {
        private static final long serialVersionUID = 1L;
        public final Kind kind;
        public final String serverCode;
        public final boolean uncertain;
        private PostingException(Kind kind, String message, String code, Throwable cause) {
            super(message, cause); this.kind = kind; serverCode = code == null ? "" : code;
            uncertain = kind == Kind.OUTCOME_UNKNOWN;
        }
    }

    public static final class Prepare {
        public final String action, fid, tid, pid, subject, content, forumName, warning;
        public final boolean appendOnly, canUpload;
        public final List<Attachment> existingAttachments;
        /** Complete official type-2 score definition and own-reply scores, if supplied by post.php. */
        public final NativeTopicRating.Spec rating;
        private final NativePosting owner;
        private final String host, fingerprint, auth, uploadUrl, stid;
        private final int topicBits;
        private boolean busy, submitted, unknown;
        private Prepare(NativePosting owner, Snapshot session, JSONObject d, String action,
                        String fid, String tid, String pid, String uploadUrl,
                        List<Attachment> existing) throws PostingException {
            this.owner = owner; host = session.host; fingerprint = session.fingerprint;
            this.action = action; this.fid = fid; this.tid = tid; this.pid = pid;
            subject = editorText(d.optString("subject", ""));
            content = editorText(d.optString("content", ""));
            JSONObject forum = d.optJSONObject("__F");
            forumName = displayText(forum == null ? "" : forum.optString("name", ""));
            warning = message(d.opt("warning"));
            appendOnly = truthy(d.opt("modify_append"));
            auth = d.optString("auth", "");
            if (!auth.isEmpty() && !opaque(auth, 4096)) throw fail(Kind.PROTOCOL, "服务器附件授权格式无效");
            this.uploadUrl = uploadUrl; canUpload = !auth.isEmpty() && !uploadUrl.isEmpty();
            JSONObject st = d.optJSONObject("__ST");
            stid = st == null ? "" : id(st.opt("tid"), false, true);
            JSONObject t = d.optJSONObject("__T");
            topicBits = t == null ? 0 : integer(at(t.opt("topic_misc_var"), "1"));
            existingAttachments = Collections.unmodifiableList(existing);
            try { rating = NativeTopicRating.fromPrepare(d, action, pid); }
            catch (IllegalArgumentException invalid) { throw fail(Kind.PROTOCOL, invalid.getMessage()); }
        }
        /** Official quote UBB, never synthesized from a rendered post. */
        public String getServerQuotedText() { return "quote".equals(action) ? content : ""; }
    }

    public static final class Attachment {
        public final String fileName, mimeType, url, bbcode;
        public final boolean image, existing;
        private final Prepare scope;
        private final String token, check;
        private Attachment(Prepare scope, String name, String mime, Link link, boolean image,
                           boolean existing, String token, String check) {
            this.scope = scope; fileName = name; mimeType = mime; url = link.url;
            this.image = image; this.existing = existing; this.token = token; this.check = check;
            String tag = image ? "img" : "attach";
            bbcode = "[" + tag + "]" + link.reference + "[/" + tag + "]";
        }
    }

    public static final class UploadPolicy {
        public final boolean allowed, image;
        public final long maxBytes;
        public final String fileName, mimeType, reason;
        private UploadPolicy(boolean allowed, boolean image, long max, String name, String mime, String reason) {
            this.allowed = allowed; this.image = image; maxBytes = max;
            fileName = name; mimeType = mime; this.reason = reason;
        }
    }

    public static final class Result {
        public final boolean success = true;
        public final String message, tid, pid, url;
        private Result(String message, String tid, String pid, String url) {
            this.message = message; this.tid = tid; this.pid = pid; this.url = url;
        }
    }

    public NativePosting(NgaStore store) { this(store, new HttpTransport()); }
    // Package-private transport seam keeps fixture tests offline; no alternate endpoints in the UI.
    NativePosting(NgaStore store, Transport transport) {
        if (store == null || transport == null) throw new IllegalArgumentException("store/transport");
        this.store = store; this.transport = transport;
    }

    /** Read-only permission/editor preparation; no post or attachment is created. */
    public Prepare prepare(String action, String fid, String tid, String pid) throws PostingException {
        if (!"new".equals(action) && !"reply".equals(action) && !"quote".equals(action) && !"modify".equals(action))
            throw fail(Kind.VALIDATION, "不支持的发帖操作");
        fid = id(fid, true, true); tid = id(tid, false, true); pid = id(pid, false, true);
        if ("new".equals(action) ? fid.isEmpty() : tid.isEmpty() && pid.isEmpty())
            throw fail(Kind.VALIDATION, "缺少版面或帖子编号");
        Snapshot s = snapshot();
        Map<String, String> args = base(action, fid, tid, pid);
        args.put("__output", "8");
        JSONObject root = exchange(request(s, "GET", "/post.php?" + form(args), null), false);
        checkErrors(root);
        JSONObject d = root.optJSONObject("data");
        if (d == null || d.has("__MESSAGE")) throw serverMessage(d == null ? null : d.opt("__MESSAGE"));
        if (!action.equals(d.optString("action", ""))) throw fail(Kind.PROTOCOL, "服务器返回了不同的发帖操作");
        String actualFid = d.has("fid") ? id(d.opt("fid"), true, false) : fid;
        String actualTid = d.has("tid") ? id(d.opt("tid"), false, true) : tid;
        String actualPid = d.has("pid") ? id(d.opt("pid"), false, true) : pid;
        if (actualFid.isEmpty() || !tid.isEmpty() && !tid.equals(actualTid) || !pid.isEmpty() && !pid.equals(actualPid))
            throw fail(Kind.PROTOCOL, "服务器返回的目标帖子不匹配");
        JSONObject current = d.optJSONObject("__CU");
        String currentUid = current == null ? "" : id(current.opt("uid"), false, true);
        if (!currentUid.isEmpty() && !s.uid.isEmpty() && !s.uid.equals(currentUid))
            throw fail(Kind.SESSION_CHANGED, "登录账号已变化，请重新打开编辑器");
        if ("modify".equals(action)) {
            JSONObject target = actualPid.isEmpty() ? d.optJSONObject("__T") : d.optJSONObject("__P");
            String author = target == null ? "" : id(target.opt("authorid"), false, true);
            if (currentUid.isEmpty() || author.isEmpty() || !currentUid.equals(author))
                throw fail(Kind.PERMISSION, "只能编辑当前账号自己的帖子；服务器未提供可核验的作者权限");
            if (hasVote(target) || hasVote(d.optJSONObject("__T"))) {
                NativeTopicRating.Spec rating;
                try { rating = NativeTopicRating.fromPrepare(d, action, actualPid); }
                catch (IllegalArgumentException invalid) { throw fail(Kind.PROTOCOL, invalid.getMessage()); }
                if (actualPid.isEmpty() || "0".equals(actualPid) || rating == null)
                    throw fail(Kind.PERMISSION, "该帖含主题投票设置，不能在修改正文时丢失原投票；评分回复可单独编辑");
            }
        }
        List<Attachment> existing = existingAttachments(d.opt("attachs"));
        String upload = uploadEndpoint(d.optString("attach_url", "https://img8.nga.cn/attach.php"));
        Prepare p = new Prepare(this, s, d, action, actualFid, actualTid, actualPid, upload, existing);
        verify(p); // A logout/account switch during the GET must not authorize a stale editor.
        return p;
    }

    public Result submit(Prepare p, String subject, String content, List<Attachment> attachments) throws PostingException {
        return submit(p, subject, content, attachments, false);
    }

    public Result submit(Prepare p, String subject, String content, List<Attachment> attachments,
                         boolean anonymous) throws PostingException {
        return submit(p, subject, content, attachments, anonymous, null);
    }

    /** Scores are included only in this explicitly confirmed post submission. null preserves own old scores. */
    public Result submit(Prepare p, String subject, String content, List<Attachment> attachments,
                         boolean anonymous, Map<String, String> ratingScores) throws PostingException {
        Snapshot s = verify(p);
        if (subject == null) subject = "";
        if (content == null) content = "";
        if (content.trim().length() < 3) throw fail(Kind.VALIDATION, "内容过短，请至少输入 3 个字符");
        if ("new".equals(p.action) && subject.trim().isEmpty()) throw fail(Kind.VALIDATION, "请填写标题");
        if (subject.length() > 1000 || content.length() > 500000 || subject.indexOf('\0') >= 0 || content.indexOf('\0') >= 0)
            throw fail(Kind.VALIDATION, "标题或内容过长，或含无效字符");
        if (anonymous && "modify".equals(p.action)) throw fail(Kind.VALIDATION, "编辑时不能改变匿名状态");
        Map<String, String> selectedRating = ratingScores;
        if (selectedRating == null) selectedRating = p.rating == null ? Collections.emptyMap() : p.rating.existing;
        if (p.rating == null && !selectedRating.isEmpty()) throw fail(Kind.VALIDATION, "服务器未提供可核验的主题评分项目");
        String topicVote = "";
        if (p.rating != null) {
            if ("modify".equals(p.action) && !p.rating.existing.isEmpty() && selectedRating.isEmpty())
                throw fail(Kind.VALIDATION, "已有评分只能随原回复修改，不能通过清空字段删除");
            if (!"modify".equals(p.action) && !selectedRating.isEmpty() && p.rating.ended()) throw fail(Kind.VALIDATION, "主题评分已结束");
            try { topicVote = p.rating.encode(selectedRating); }
            catch (IllegalArgumentException invalid) { throw fail(Kind.VALIDATION, invalid.getMessage()); }
        }
        StringBuilder tokens = new StringBuilder(), checks = new StringBuilder();
        Set<Attachment> seen = new HashSet<>();
        if (attachments != null) for (Attachment a : attachments) {
            if (a == null || a.existing || a.scope != p || !opaque(a.token, 65536) || !opaque(a.check, 65536))
                throw fail(Kind.VALIDATION, "附件不属于本次编辑，请重新选择并上传");
            if (seen.add(a)) { tokens.append(a.token).append('\t'); checks.append(a.check).append('\t'); }
        }
        Map<String, String> args = base(p.action, p.fid, p.tid, p.pid);
        args.put("step", "2"); args.put("__output", "8"); args.put("__inchst", "GB18030");
        args.put("post_subject", subject); args.put("post_content", content); args.put("nojump", "1");
        if (!p.stid.isEmpty()) args.put("stid", p.stid);
        if (anonymous) args.put("anony", "1");
        if (p.appendOnly) args.put("modify_append", "1");
        if (p.topicBits != 0) args.put("tpic_misc_bit1", Integer.toString(p.topicBits));
        if (!topicVote.isEmpty()) args.put("topic_vote", topicVote);
        if ("modify".equals(p.action)) {
            if (p.subject.equals(subject)) args.put("subject_not_modify", "1");
            if (!p.appendOnly && p.content.equals(content)) args.put("content_not_modify", "1");
        }
        if (tokens.length() > 0) { args.put("attachments", tokens.toString()); args.put("attachments_check", checks.toString()); }
        byte[] body = form(args).getBytes(StandardCharsets.US_ASCII);
        begin(p);
        try {
            JSONObject root = exchange(request(s, "POST", "/post.php", body), true);
            checkErrors(root);
            JSONObject data = root.optJSONObject("data");
            Object m = data == null ? null : data.opt("__MESSAGE");
            Object code = at(m, "0");
            if (!(code instanceof Number) || ((Number) code).doubleValue() != 0) {
                if (explicitRejection(code)) throw serverMessage(m);
                throw unknown("服务器没有明确确认发帖结果，请先检查帖子再重试", null);
            }
            String url = scalar(at(m, "6"));
            Result result = successfulResult(p, displayText(scalar(at(m, "1"))), url);
            synchronized (p) { p.submitted = true; }
            return result;
        } catch (PostingException e) {
            if (e.uncertain) synchronized (p) { p.unknown = true; }
            throw e;
        } finally { synchronized (p) { p.busy = false; } }
    }

    /** Conservative original-file limits from the official uploader; server permissions still apply. */
    public UploadPolicy preflight(Prepare p, String filename, String mime, long bytes) {
        String name = filename == null ? "" : filename;
        String type = mime == null ? "" : mime.toLowerCase(Locale.ROOT).trim();
        String ext = extension(name), canonical = mimeFor(ext);
        boolean image = canonical.startsWith("image/");
        long max = "gif".equals(ext) ? 8 * MIB : 4 * MIB;
        if (p != null && ("200".equals(p.fid) || "274".equals(p.fid) || "663".equals(p.fid)) && isArchive(ext)) max = 12 * MIB;
        String reason = "";
        try { verify(p); } catch (PostingException e) { reason = e.getMessage(); }
        if (reason.isEmpty() && !p.canUpload) reason = "服务器未提供有效附件授权或受支持的 NGA 上传地址";
        if (reason.isEmpty() && (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || controls(name) || name.getBytes(StandardCharsets.UTF_8).length > 240))
            reason = "附件名称无效或过长";
        if (reason.isEmpty() && canonical.isEmpty()) reason = "暂支持 JPG、PNG、GIF、WebP、ZIP、RAR、7Z 附件";
        if (reason.isEmpty() && !type.isEmpty() && !type.equals("application/octet-stream") && !type.equals(canonical)
                && !("image/jpg".equals(type) && "image/jpeg".equals(canonical))
                && !(isArchive(ext) && (type.equals("application/x-zip-compressed") || type.equals("application/x-rar-compressed"))))
            reason = "附件扩展名与文件类型不一致";
        if (reason.isEmpty() && bytes < 0) reason = "请先读取有大小上限的附件以确定文件大小";
        if (reason.isEmpty() && (bytes == 0 || bytes > max)) reason = "附件为空或超过 " + (max / MIB) + " MiB 上限";
        return new UploadPolicy(reason.isEmpty(), image, max, name, canonical, reason);
    }

    /** Uploads only after the UI has explicitly chosen a file. Never compresses or retries. */
    public Attachment upload(Prepare p, String filename, String mime, byte[] bytes) throws PostingException {
        Snapshot s = verify(p);
        UploadPolicy policy = preflight(p, filename, mime, bytes == null ? 0 : bytes.length);
        if (!policy.allowed) throw fail(Kind.VALIDATION, policy.reason);
        if (!signature(extension(filename), bytes)) throw fail(Kind.VALIDATION, "附件内容与文件类型不符");
        String boundary = "NGA_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("func", "upload"); fields.put("v2", "1"); fields.put("origin_domain", s.host);
        fields.put("__output", "1"); fields.put("auth", p.auth); fields.put("fid", p.fid);
        fields.put("attachment_file1_url_utf8_name", utf8Filename(filename));
        fields.put("attachment_file1_auto_size", "0");
        ByteArrayOutputStream prefix = new ByteArrayOutputStream();
        try {
            for (Map.Entry<String, String> f : fields.entrySet()) {
                writeAscii(prefix, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n" + f.getValue() + "\r\n");
            }
            writeAscii(prefix, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"attachment_file1\"; filename=\"upload." + extension(filename) + "\"\r\nContent-Type: " + policy.mimeType + "\r\n\r\n");
        } catch (IOException impossible) { throw fail(Kind.PROTOCOL, "无法创建附件请求"); }
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        Request request = request(s, "POST", p.uploadUrl, null);
        request.parts = new byte[][] {prefix.toByteArray(), bytes, suffix};
        request.headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        begin(p);
        try {
            JSONObject root = exchange(request, true);
            checkErrors(root);
            JSONObject d = root.optJSONObject("data");
            if (d != null && d.has("__MESSAGE")) throw serverMessage(d.opt("__MESSAGE"));
            String token = d == null ? "" : d.optString("attachments", "");
            String check = d == null ? "" : d.optString("attachments_check", "");
            if (!opaque(token, 65536) || !opaque(check, 65536)) throw unknown("服务器未返回完整附件凭证，请先检查上传结果再重试", null);
            Link link;
            try { link = attachmentLink(d.optString("url", "")); }
            catch (PostingException invalid) { throw unknown("服务器返回了无法核验的附件地址，请检查上传结果", invalid); }
            boolean image = (integer(d.opt("isImg")) & 1) != 0;
            if (image != policy.image) throw unknown("服务器返回的附件类型不匹配，请检查上传结果", null);
            return new Attachment(p, filename, policy.mimeType, link, image, false, token, check);
        } catch (PostingException e) {
            if (e.uncertain) synchronized (p) { p.unknown = true; }
            throw e;
        } finally { synchronized (p) { p.busy = false; } }
    }

    private Snapshot snapshot() throws PostingException {
        String host = store.host(), cookie = store.cookie();
        if (!NgaStore.HOSTS.contains(host) || controls(cookie)) throw fail(Kind.VALIDATION, "无效的 NGA 会话");
        if (cookie.isEmpty() || !store.loggedIn()) throw fail(Kind.NOT_LOGGED_IN, "请先登录 NGA 后再发帖");
        return new Snapshot(host, cookie);
    }
    private Snapshot verify(Prepare p) throws PostingException {
        if (p == null || p.owner != this) throw fail(Kind.VALIDATION, "请先获取发帖权限");
        Snapshot s;
        try { s = snapshot(); } catch (PostingException e) { throw fail(Kind.SESSION_CHANGED, "登录状态已变化，请重新打开编辑器"); }
        if (!p.fingerprint.equals(s.fingerprint)) throw fail(Kind.SESSION_CHANGED, "登录账号或线路已变化，请重新打开编辑器");
        synchronized (p) {
            if (p.submitted) throw fail(Kind.VALIDATION, "这份内容已提交，请刷新帖子后重新编辑");
            if (p.unknown) throw unknown("上次提交结果尚未确认，请先检查服务器记录并重新打开编辑器", null);
        }
        return s;
    }
    private void begin(Prepare p) throws PostingException {
        verify(p);
        synchronized (p) {
            if (p.busy) throw fail(Kind.VALIDATION, "已有上传或提交请求正在处理");
            p.busy = true;
        }
    }
    private static Map<String, String> base(String action, String fid, String tid, String pid) {
        Map<String, String> out = new LinkedHashMap<>(); out.put("action", action);
        if (!fid.isEmpty()) out.put("fid", fid);
        if (!tid.isEmpty()) out.put("tid", tid);
        if (!pid.isEmpty()) out.put("pid", pid);
        return out;
    }
    private static Request request(Snapshot s, String method, String path, byte[] body) throws PostingException {
        try {
            URL url = new URL(path.startsWith("https://") ? path : "https://" + s.host + path);
            boolean forum = url.getHost().equals(s.host) && "/post.php".equals(url.getPath());
            boolean upload = "POST".equals(method) && url.toString().equals("https://img8.nga.cn/attach.php");
            if ((!forum && !upload) || !"https".equals(url.getProtocol()) || url.getUserInfo() != null || url.getPort() != -1 || url.getRef() != null)
                throw fail(Kind.PROTOCOL, "已阻止非 NGA 发帖/上传地址");
            Request r = new Request(url, method);
            r.headers.put("User-Agent", "NGA_WP_JW"); r.headers.put("Accept", "application/json,text/javascript,text/plain;q=0.9");
            r.headers.put("Referer", "https://" + s.host + "/"); r.headers.put("Origin", "https://" + s.host);
            r.headers.put("Cookie", s.cookie);
            r.headers.put("Content-Type", "application/x-www-form-urlencoded; charset=GB18030");
            r.parts = body == null ? new byte[0][] : new byte[][] {body};
            return r;
        } catch (IOException e) {
            if (e instanceof PostingException) throw (PostingException) e;
            throw fail(Kind.PROTOCOL, "NGA 请求地址无效");
        }
    }
    private JSONObject exchange(Request request, boolean write) throws PostingException {
        // Explicit protocol intent, rather than HTTP method: only permission preparation is
        // disposable. Submission and attachment delivery must retain uncertain-outcome handling.
        request.readOnly = !write;
        Response response;
        try {
            if (!write) RequestCancellation.check();
            response = transport.execute(request);
            if (!write) RequestCancellation.check();
        }
        catch (IOException failure) {
            if (write) throw unknown("请求中断，服务器可能已收到内容；请先检查结果再重试", failure);
            throw new PostingException(Kind.NETWORK, "无法连接 NGA，请稍后重新获取发帖权限", "", failure);
        }
        if (response.status >= 300 && response.status < 400) {
            if (write) throw unknown("提交发生跳转，结果尚未确认；请先检查帖子或附件", null);
            throw fail(Kind.NETWORK, "NGA 发帖接口发生跳转，请切换线路后重试");
        }
        JSONObject root;
        try { root = parse(response.bytes, response.contentType); }
        catch (Exception malformed) {
            String text = new String(response.bytes, GB).toLowerCase(Locale.ROOT);
            if (challenge(text)) throw fail(Kind.CHALLENGE_REQUIRED, "NGA 要求安全验证或验证码，当前操作未获确认");
            if (response.status == 401) throw fail(Kind.NOT_LOGGED_IN, "登录已失效，请重新登录");
            if (response.status == 403) throw fail(Kind.PERMISSION, "NGA 拒绝此次请求（HTTP 403）");
            if (write) throw unknown("服务器返回了无法识别的提交结果，请先检查是否已成功", malformed);
            throw new PostingException(Kind.PROTOCOL, "服务器未返回原生发帖数据（HTTP " + response.status + "）", "", malformed);
        }
        checkErrors(root);
        if (response.status < 200 || response.status >= 300) {
            if (write) throw unknown("服务器返回 HTTP " + response.status + "，请先核对提交结果", null);
            throw fail(Kind.NETWORK, "NGA 服务器返回 HTTP " + response.status);
        }
        return root;
    }

    /** Parses legacy envelopes as data; never evaluates server JavaScript. */
    private static JSONObject parse(byte[] bytes, String contentType) throws Exception {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT), raw;
        if (type.contains("gbk") || type.contains("gb2312") || type.contains("gb18030")) raw = new String(bytes, GB);
        else {
            try { raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
            catch (CharacterCodingException notUtf8) { raw = new String(bytes, GB); }
        }
        raw = raw.trim(); if (raw.startsWith("\uFEFF")) raw = raw.substring(1).trim();
        if (raw.startsWith("/*$js$*/")) raw = raw.substring(8).trim();
        raw = raw.replaceFirst("^window\\.script_m(?:uti|ulti)_get_var_store\\s*=\\s*", "");
        if (!raw.startsWith("{")) throw new IOException("JSON required");
        JSONTokener tokener = new JSONTokener(raw); Object decoded = tokener.nextValue();
        StringBuilder tail = new StringBuilder(); char c;
        while ((c = tokener.next()) != 0) tail.append(c);
        String suffix = tail.toString().trim();
        if (!suffix.matches(";?\\s*(?:/\\*error fill content[\\s\\S]*\\*/\\s*)?")) throw new IOException("trailing script");
        if (!(decoded instanceof JSONObject)) throw new IOException("object required");
        return (JSONObject) decoded;
    }
    private static void checkErrors(JSONObject root) throws PostingException {
        Object rawError = root.opt("error");
        String error = rawError instanceof Number && ((Number) rawError).doubleValue() == 0
                || Boolean.FALSE.equals(rawError) ? "" : message(rawError);
        int uploadCode = integer(root.opt("error_code"));
        if (!error.isEmpty()) throw classified(error, scalar(at(root.opt("error"), "0")));
        if (uploadCode != 0) {
            String[] errors = {"", "附件上传关闭", "附件上传版面错误", "附件授权超时，请重新打开编辑器", "文件上传错误", "无上传文件", "文件类型错误", "附件名过长", "附件说明过长", "附件过大", "无法创建临时文件"};
            String text = uploadCode < errors.length && uploadCode > 0 ? errors[uploadCode]
                    : uploadCode == 13 ? "发帖数超过 5 方可上传附件" : "NGA 附件上传错误 " + uploadCode;
            throw new PostingException(Kind.SERVER, text, Integer.toString(uploadCode), null);
        }
    }
    private static boolean explicitRejection(Object code) {
        return code instanceof Number && ((Number) code).doubleValue() != 0
                || code instanceof String && !((String) code).trim().isEmpty() && !"0".equals(code);
    }
    private static PostingException serverMessage(Object m) {
        String text = message(at(m, "1"));
        if (text.isEmpty()) text = message(m);
        return classified(text.isEmpty() ? "服务器未授予该操作权限" : text, scalar(at(m, "0")));
    }
    private static PostingException classified(String message, String code) {
        String low = message.toLowerCase(Locale.ROOT);
        Kind kind = challenge(low) ? Kind.CHALLENGE_REQUIRED
                : low.contains("登录") || low.contains("not logged") || low.contains("2048:") ? Kind.NOT_LOGGED_IN
                : low.contains("权限") || low.contains("禁言") || low.contains("锁定") || low.contains("无权") || low.contains("禁止") || low.contains("不可") ? Kind.PERMISSION : Kind.SERVER;
        return new PostingException(kind, displayText(message), code, null);
    }
    private static boolean challenge(String low) {
        return low.contains("验证码") || low.contains("安全验证") || low.contains("人机验证") || low.contains("验证手机")
                || low.contains("captcha") || low.contains("challenge") || low.contains("need send sms code");
    }
    private static Result successfulResult(Prepare p, String message, String location) throws PostingException {
        try {
            URL u = new URL(new URL("https://" + p.host + "/"), location);
            if (location.isEmpty() || !p.host.equalsIgnoreCase(u.getHost()) || !("https".equals(u.getProtocol()) || "http".equals(u.getProtocol()))
                    || u.getPort() != -1 || u.getUserInfo() != null || !"/read.php".equals(u.getPath())) throw new IOException("invalid location");
            String tid = queryId(u.getQuery(), "tid"), pid = queryId(u.getQuery(), "pid");
            if (pid.isEmpty() && u.getRef() != null && u.getRef().matches("pid[0-9]{1,18}")) pid = u.getRef().substring(3);
            if (tid.isEmpty()) tid = p.tid;
            if (tid.isEmpty() && pid.isEmpty() || !p.tid.isEmpty() && !p.tid.equals(tid)) throw new IOException("wrong topic");
            String url = "https://" + p.host + u.getFile() + (u.getRef() == null ? "" : "#" + u.getRef());
            return new Result(message.isEmpty() ? "发帖完毕" : message, tid, pid, url);
        } catch (IOException invalid) { throw unknown("服务器确认信息不完整，请先核对帖子是否已发布", invalid); }
    }
    private static String queryId(String query, String name) throws IOException {
        String value = "";
        if (query != null) for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair[0].equals(name)) {
                if (!value.isEmpty() || pair.length != 2 || !pair[1].matches("[1-9][0-9]{0,17}")) throw new IOException("invalid id");
                value = pair[1];
            }
        }
        return value;
    }
    private static String uploadEndpoint(String raw) {
        try {
            if (raw.startsWith("//")) raw = "https:" + raw;
            if (raw.startsWith("http://img8.nga.cn/")) raw = "https://" + raw.substring(7);
            URL u = new URL(raw);
            if ("https".equals(u.getProtocol()) && "img8.nga.cn".equals(u.getHost()) && u.getUserInfo() == null
                    && (u.getPort() == -1 || u.getPort() == 443) && "/attach.php".equals(u.getPath()) && u.getQuery() == null && u.getRef() == null)
                return "https://img8.nga.cn/attach.php";
        } catch (IOException ignored) { }
        return "";
    }
    private static List<Attachment> existingAttachments(Object rows) throws PostingException {
        List<Attachment> result = new ArrayList<>();
        for (JSONObject row : objects(rows)) {
            Link link = attachmentLink(row.optString("attachurl", ""));
            boolean image = "img".equals(row.optString("type", ""));
            String name = row.optString("url_utf8_org_name", "");
            try { name = URLDecoder.decode(name.replace("+", "%2B"), "UTF-8"); } catch (Exception ignored) { name = ""; }
            if (name.isEmpty()) name = link.url.substring(link.url.lastIndexOf('/') + 1);
            result.add(new Attachment(null, name, mimeFor(extension(name)), link, image, true, "", ""));
        }
        return result;
    }
    private static final class Link {
        final String url, reference;
        Link(String url, String reference) { this.url = url; this.reference = reference; }
    }
    private static Link attachmentLink(String raw) throws PostingException {
        String relative = raw;
        if (relative.startsWith("./")) relative = relative.substring(2);
        if (relative.startsWith("/attachments/")) relative = relative.substring(1);
        if (relative.startsWith("https://") || relative.startsWith("http://") || relative.startsWith("//")) {
            try {
                URL u = new URL(relative.startsWith("//") ? "https:" + relative : relative);
                if (!"img.nga.cn".equals(u.getHost()) && !"img8.nga.cn".equals(u.getHost())) throw new IOException();
                if (u.getUserInfo() != null || u.getPort() != -1 || u.getQuery() != null || u.getRef() != null) throw new IOException();
                relative = u.getPath().replaceFirst("^/", "");
            } catch (IOException e) { throw fail(Kind.PROTOCOL, "附件地址不属于受支持的 NGA 图片服务器"); }
        }
        if (relative.startsWith("attachments/")) relative = relative.substring(12);
        if (!relative.matches("mon_[0-9]{6}/[0-9]{1,10}/[A-Za-z0-9_-][A-Za-z0-9_.-]{0,240}") || relative.contains(".."))
            throw fail(Kind.PROTOCOL, "NGA 附件地址格式无效");
        return new Link("https://img.nga.cn/attachments/" + relative, "./" + relative);
    }
    private static boolean hasVote(JSONObject object) {
        if (object == null) return false;
        Object vote = at(object.opt("post_misc_var"), "vote");
        return vote != null && vote != JSONObject.NULL && !"".equals(vote) && !"0".equals(String.valueOf(vote));
    }
    private static String form(Map<String, String> args) throws PostingException {
        StringBuilder out = new StringBuilder();
        try {
            for (Map.Entry<String, String> e : args.entrySet()) {
                if (out.length() > 0) out.append('&');
                out.append(URLEncoder.encode(e.getKey(), "GB18030")).append('=').append(URLEncoder.encode(e.getValue(), "GB18030"));
            }
        } catch (IOException e) { throw fail(Kind.PROTOCOL, "设备不支持 NGA 编码"); }
        return out.toString();
    }
    private static String utf8Filename(String name) throws PostingException {
        try { return URLEncoder.encode(name, "UTF-8").replace("+", "%20").replace("*", "%2A"); }
        catch (IOException e) { throw fail(Kind.PROTOCOL, "设备不支持文件名编码"); }
    }
    private static String id(Object raw, boolean signed, boolean optional) throws PostingException {
        String text = scalar(raw);
        if (optional && (text.isEmpty() || "0".equals(text))) return "";
        if (!text.matches(signed ? "-?[1-9][0-9]{0,17}" : "[1-9][0-9]{0,17}")) throw fail(Kind.PROTOCOL, "NGA 编号无效");
        return text;
    }
    private static Object at(Object object, String key) {
        if (object instanceof JSONObject) return ((JSONObject) object).opt(key);
        if (object instanceof JSONArray && key.matches("[0-9]{1,8}")) return ((JSONArray) object).opt(Integer.parseInt(key));
        return null;
    }
    private static List<JSONObject> objects(Object object) {
        List<JSONObject> result = new ArrayList<>();
        if (object instanceof JSONArray) {
            JSONArray a = (JSONArray) object; for (int i = 0; i < a.length(); i++) { JSONObject row = a.optJSONObject(i); if (row != null) result.add(row); }
        } else if (object instanceof JSONObject) {
            JSONObject o = (JSONObject) object; Iterator<String> keys = o.keys();
            while (keys.hasNext()) { JSONObject row = o.optJSONObject(keys.next()); if (row != null) result.add(row); }
        }
        return result;
    }
    private static String message(Object object) {
        if (object == null || object == JSONObject.NULL) return "";
        if (object instanceof JSONObject || object instanceof JSONArray) {
            StringBuilder out = new StringBuilder();
            if (object instanceof JSONArray) {
                JSONArray a = (JSONArray) object; for (int i = 0; i < a.length() && i < 100; i++) append(out, message(a.opt(i)));
            } else {
                JSONObject o = (JSONObject) object; Iterator<String> keys = o.keys(); int n = 0;
                while (keys.hasNext() && n++ < 100) append(out, message(o.opt(keys.next())));
            }
            return out.length() > 2000 ? out.substring(0, 2000) : out.toString();
        }
        return displayText(String.valueOf(object));
    }
    private static void append(StringBuilder s, String value) { if (!value.isEmpty()) { if (s.length() > 0) s.append('\n'); s.append(value); } }
    private static String scalar(Object o) { return o == null || o == JSONObject.NULL ? "" : o instanceof String || o instanceof Number ? String.valueOf(o) : ""; }
    private static int integer(Object o) { try { return Integer.parseInt(scalar(o)); } catch (RuntimeException e) { return 0; } }
    private static boolean truthy(Object o) { return Boolean.TRUE.equals(o) || integer(o) != 0; }
    // Exact entity subset used by official ubbcode.unSecureText; do not parse HTML or UBB.
    private static String editorText(String s) {
        return s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&#92;", "\\")
                .replace("&#36;", "$").replace("&amp;", "&");
    }
    private static String displayText(String s) { return editorText(s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]{0,1000}>", "")).trim(); }
    private static boolean controls(String s) { for (int i = 0; i < s.length(); i++) if (s.charAt(i) < 32 || s.charAt(i) == 127) return true; return false; }
    private static boolean opaque(String s, int max) { return s != null && !s.isEmpty() && s.length() <= max && !controls(s); }
    private static String extension(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT); }
    private static boolean isArchive(String ext) { return "zip".equals(ext) || "rar".equals(ext) || "7z".equals(ext); }
    private static String mimeFor(String ext) {
        switch (ext) { case "jpg": case "jpeg": return "image/jpeg"; case "png": return "image/png";
            case "gif": return "image/gif"; case "webp": return "image/webp"; case "zip": return "application/zip";
            case "rar": return "application/vnd.rar"; case "7z": return "application/x-7z-compressed"; default: return ""; }
    }
    private static boolean signature(String ext, byte[] b) {
        switch (ext) {
            case "jpg": case "jpeg": return starts(b, 0, 0xff, 0xd8, 0xff);
            case "png": return starts(b, 0, 137, 80, 78, 71, 13, 10, 26, 10);
            case "gif": return starts(b, 0, 71, 73, 70, 56, 55, 97) || starts(b, 0, 71, 73, 70, 56, 57, 97);
            case "webp": return starts(b, 0, 82, 73, 70, 70) && starts(b, 8, 87, 69, 66, 80);
            case "zip": return starts(b, 0, 80, 75, 3, 4) || starts(b, 0, 80, 75, 5, 6) || starts(b, 0, 80, 75, 7, 8);
            case "rar": return starts(b, 0, 82, 97, 114, 33, 26, 7, 0) || starts(b, 0, 82, 97, 114, 33, 26, 7, 1, 0);
            case "7z": return starts(b, 0, 55, 122, 188, 175, 39, 28);
            default: return false;
        }
    }
    private static boolean starts(byte[] b, int offset, int... expected) { if (b == null || b.length < offset + expected.length) return false; for (int i = 0; i < expected.length; i++) if ((b[offset + i] & 255) != expected[i]) return false; return true; }
    private static PostingException fail(Kind kind, String message) { return new PostingException(kind, message, "", null); }
    private static PostingException unknown(String message, Throwable cause) { return new PostingException(Kind.OUTCOME_UNKNOWN, message, "", cause); }
    private static void writeAscii(OutputStream stream, String value) throws IOException { stream.write(value.getBytes(StandardCharsets.US_ASCII)); }

    private static final class Snapshot {
        final String host, cookie, fingerprint, uid;
        Snapshot(String host, String cookie) throws PostingException {
            this.host = host; this.cookie = cookie; String user = "";
            for (String part : cookie.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && ("ngaPassportUid".equals(kv[0]) || "access_uid".equals(kv[0])) && kv[1].matches("[1-9][0-9]{0,17}")) user = kv[1];
            }
            uid = user;
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest((host + "\0" + cookie).getBytes(StandardCharsets.UTF_8));
                StringBuilder f = new StringBuilder(); for (byte v : digest) f.append(String.format(Locale.ROOT, "%02x", v & 255)); fingerprint = f.toString();
            } catch (Exception e) { throw fail(Kind.PROTOCOL, "设备无法绑定 NGA 会话"); }
        }
    }
    interface Transport { Response execute(Request request) throws IOException; }
    static final class Request {
        final URL url; final String method; final Map<String, String> headers = new LinkedHashMap<>();
        boolean readOnly;
        byte[][] parts = new byte[0][];
        Request(URL url, String method) { this.url = url; this.method = method; }
        long length() { long length = 0; for (byte[] part : parts) length += part.length; return length; }
        void write(OutputStream stream) throws IOException { for (byte[] part : parts) stream.write(part); }
    }
    static final class Response {
        final int status; final byte[] bytes; final String contentType;
        Response(int status, byte[] bytes, String contentType) { this.status = status; this.bytes = bytes; this.contentType = contentType; }
    }
    private static final class HttpTransport implements Transport {
        @Override public Response execute(Request request) throws IOException {
            if (request.readOnly) RequestCancellation.check();
            HttpURLConnection connection = (HttpURLConnection) request.url.openConnection();
            RequestCancellation.Registration cancellation = null;
            try {
                if (request.readOnly) cancellation = RequestCancellation.track(connection);
                connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(false); connection.setUseCaches(false); connection.setRequestMethod(request.method);
                for (Map.Entry<String, String> h : request.headers.entrySet()) connection.setRequestProperty(h.getKey(), h.getValue());
                if ("POST".equals(request.method)) {
                    connection.setDoOutput(true); connection.setFixedLengthStreamingMode(request.length());
                    try (OutputStream stream = connection.getOutputStream()) { request.write(stream); }
                }
                int status = connection.getResponseCode();
                if (request.readOnly) RequestCancellation.check();
                InputStream source = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                try (InputStream stream = source; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    if (stream != null) while (true) {
                        if (request.readOnly) RequestCancellation.check();
                        int count = stream.read(buffer);
                        if (request.readOnly) RequestCancellation.check();
                        if (count == -1) break;
                        if (bytes.size() + count > RESPONSE_LIMIT) throw new IOException("NGA response too large");
                        bytes.write(buffer, 0, count);
                    }
                    return new Response(status, bytes.toByteArray(), connection.getContentType());
                }
            } finally { if (cancellation != null) cancellation.close(); connection.disconnect(); }
        }
    }
}
