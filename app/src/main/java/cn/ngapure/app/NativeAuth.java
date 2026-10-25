package cn.ngapure.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Official QR authorization over HTTPS/JSON. Call begin/poll on a worker thread.
 * Owns no UI or scheduler and never reads or changes an existing NgaStore login.
 */
public final class NativeAuth implements AutoCloseable {
    public enum State { WAITING, SUCCESS, PAUSED, EXPIRED, CANCELLED, VERIFY_REQUIRED, ERROR }
    private static final Set<String> HOSTS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("bbs.nga.cn", "ngabbs.com", "nga.178.com")));
    private static final int MAX_BYTES = 1024 * 1024;
    private static final long INTERVAL = 3000, AUTO_WINDOW = 90000;
    private final String host;
    private final Transport transport;
    private final Clock clock;
    private final Recovery recovery;
    private volatile QrSession current;
    private volatile boolean closed;
    private volatile boolean suspended;
    private volatile boolean recoveryCleanupFailed;
    private final Object diagnosticLock = new Object();
    private QrSession diagnosticOwner;
    private SafeData diagnostic = new SafeData();
    private enum Stage { IDLE, GENERATE, POLL, EXCHANGE, VERIFY }
    private static final String[] COOKIE_NAMES = {"ngaPassportUid", "ngaPassportCid", "ngaPassportUrlencodedUname", "access_uid", "access_token"};

    public NativeAuth(String host) { this(host, null); }

    NativeAuth(String host, Recovery recovery) {
        this(host, new NetworkTransport(), () -> System.nanoTime() / 1_000_000L, recovery);
    }

    NativeAuth(String host, Transport transport, Clock clock) {
        this(host, transport, clock, null);
    }

    NativeAuth(String host, Transport transport, Clock clock, Recovery recovery) {
        if (host == null || !HOSTS.contains(host.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("不支持的 NGA 登录域名");
        if (transport == null || clock == null) throw new IllegalArgumentException("缺少登录网络配置");
        this.host = host.toLowerCase(Locale.ROOT);
        this.transport = transport;
        this.clock = clock;
        this.recovery = recovery;
    }

    public static final class QrSession {
        private final NativeAuth owner;
        private final String host;
        private final Clock clock;
        private final long deadline;
        private final Map<String, HttpCookie> cookies = new LinkedHashMap<>();
        private volatile String qrText = "";
        private volatile boolean cancelled;
        private State state = State.WAITING;
        private String message = "等待使用 NGA APP 扫码并授权";
        private String qrkey = "", hiddenkey = "", grantUid = "", grantToken = "", completedCookie = "";
        private long nextPoll;
        private boolean busy, exchanged;
        private volatile HttpURLConnection connection;
        private QrSession(NativeAuth owner, String host, Clock clock) {
            this(owner, host, clock, AUTO_WINDOW);
        }
        private QrSession(NativeAuth owner, String host, Clock clock, long automaticRemaining) {
            this.owner = owner; this.host = host; this.clock = clock;
            deadline = clock.now() + Math.max(0, Math.min(AUTO_WINDOW, automaticRemaining));
            nextPoll = clock.now() + INTERVAL;
        }
        public String qrText() { return qrText; }
        public String host() { return host; }
        public long autoPollRemainingMillis() { return Math.max(0, deadline - clock.now()); }
        public boolean isCancelled() { return cancelled; }
        @Override public String toString() { return "NativeAuth.QrSession{cancelled=" + cancelled + "}"; }
    }

    public static final class PollResult {
        public final State state;
        public final String message;
        /** Sensitive: only hand to NgaStore.setCookie after checking this attempt is still current. */
        public final String cookieHeader;
        public final long retryAfterMillis;
        private PollResult(State state, String message, String cookie, long retry) {
            this.state = state; this.message = message;
            cookieHeader = state == State.SUCCESS ? cookie : "";
            retryAfterMillis = Math.max(0, retry);
        }
        @Override public String toString() { return "NativeAuth.PollResult{state=" + state + "}"; }
    }

    /** Starts a fresh anonymous attempt. Replacing an attempt never clears a stored login. */
    public QrSession begin() throws IOException {
        QrSession session = new QrSession(this, host, clock), previous;
        synchronized (this) {
            if (closed || suspended) throw new IOException("登录检查已关闭，请重新打开登录页面");
            previous = current; current = session;
        }
        session.busy = true;
        if (previous != null) cancelMemory(previous);
        resetDiagnostic(session);
        try {
            requestDiagnostic(session, Stage.GENERATE);
            Envelope response = post(session, "/nuke.php", fields("__act", "qrlogin_gen"));
            if (response.code != 0) throw businessException(session, response.message);
            String pair = stringAt(response.result, 0);
            String[] keys = pair.split(",", -1);
            if (keys.length != 2 || !key(keys[0]) || !key(keys[1]))
                throw new ProtocolException(State.ERROR, "NGA 未返回可识别的二维码会话，请刷新重试");
            synchronized (session) {
                active(session);
                session.qrkey = keys[0]; session.hiddenkey = keys[1];
                session.qrText = "https://" + host + "/nuke.php?__lib=login&__act=qrlogin_ui&qrkey=" + encode(keys[0]);
                checkpoint(session);
            }
            return session;
        } catch (IOException error) {
            String message = safeMessage(session, error.getMessage());
            stateDiagnostic(session, State.ERROR);
            cancelMemory(session);
            if (!suspended) clearRecovery();
            throw new IOException(message);
        } finally {
            synchronized (session) { session.busy = false; }
            finishSuspension(session);
        }
    }

    /** Redeems only a fresh grant returned by the official password/SMS endpoint.
     * No existing account cookie is read or used as authorization. This owner must
     * not have a QR recovery store; its caller owns the credential attempt.
     */
    PollResult completeCredentialGrant(String uid, String token) throws IOException {
        if (recovery != null || uid == null || !uid.matches("[1-9][0-9]{0,18}") || !secretValue(token))
            throw new IOException("NGA 未返回完整登录授权");
        QrSession session = new QrSession(this, host, clock);
        synchronized (this) {
            if (closed || suspended) throw new IOException("登录请求已取消");
            if (current != null) cancelMemory(current);
            current = session;
        }
        session.grantUid = uid; session.grantToken = token; session.busy = true;
        resetDiagnostic(session);
        try { return finish(session); }
        finally { synchronized (session) { session.busy = false; } }
    }

    /** Automatic calls stop after 90 seconds; manual calls can still check the same server attempt. */
    public PollResult poll(QrSession session, boolean manual) throws IOException {
        owned(session);
        synchronized (session) {
            if (suspended) return result(State.CANCELLED, "本次授权已交接恢复", "", 0);
            if (session.cancelled || current != session) return result(State.CANCELLED, "已取消登录", "", 0);
            if (session.state == State.SUCCESS) return result(State.SUCCESS, "登录已确认", session.completedCookie, 0);
            if (session.state == State.EXPIRED || session.state == State.VERIFY_REQUIRED)
                return result(session.state, session.message, "", 0);
            if (!manual && session.autoPollRemainingMillis() == 0)
                return update(session, State.PAUSED, "自动检查已暂停，可手动检查授权或刷新二维码", "", 0);
            long remaining = session.nextPoll - clock.now();
            if (session.busy || remaining > 0)
                return result(session.state == State.PAUSED ? State.PAUSED : State.WAITING,
                        session.busy ? "正在检查授权…" : "等待使用 NGA APP 扫码并授权", "", Math.max(remaining, INTERVAL));
            session.busy = true;
            session.nextPoll = clock.now() + INTERVAL;
        }
        try {
            if (session.grantUid.isEmpty()) {
                requestDiagnostic(session, Stage.POLL);
                Map<String, String> body = fields("__act", "login", "v2", "1", "app_id", "5004", "device", "",
                        "qrkey", session.qrkey, "hiddenkey", session.hiddenkey);
                Envelope response = post(session, "/nuke.php", body);
                if (response.code != 0) return serverState(session, response.message);
                JSONObject grant = objectAt(response.result, 3);
                String uid = grant == null ? "" : scalar(grant.opt("uid"));
                String token = grant == null ? "" : scalar(grant.opt("token"));
                if (!uid.matches("[1-9][0-9]{0,18}") || !secretValue(token))
                    throw new ProtocolException(State.ERROR, "NGA 未返回完整授权结果，请刷新二维码重试");
                synchronized (session) {
                    active(session); session.grantUid = uid; session.grantToken = token;
                }
                synchronized (diagnosticLock) { if (diagnosticOwner == session) diagnostic.grantReceived = true; }
            }
            // Includes retry after a failed commit: no exchange before this grant is durable.
            checkpoint(session);
            return finish(session);
        } catch (ProtocolException error) {
            synchronized (session) {
                if (session.cancelled || current != session) return result(State.CANCELLED, "已取消登录", "", 0);
                return update(session, error.state, safeMessage(session, error.getMessage()), "", 0);
            }
        } catch (IOException error) {
            if (session.cancelled || current != session) return result(State.CANCELLED, "已取消登录", "", 0);
            throw new IOException(safeMessage(session, error.getMessage()));
        } finally {
            synchronized (session) { session.busy = false; }
            finishSuspension(session);
        }
    }

    /** Explicitly abandons this attempt and its recovery data, including any in-flight request. */
    public void cancel(QrSession session) {
        if (session == null) return;
        owned(session);
        cancelMemory(session);
        clearRecovery();
    }
    private void cancelMemory(QrSession session) {
        HttpURLConnection pending;
        synchronized (session) {
            session.cancelled = true; session.state = State.CANCELLED;
            session.qrText = ""; session.qrkey = session.hiddenkey = session.grantUid = session.grantToken = "";
            session.completedCookie = ""; session.cookies.clear();
            session.exchanged = false;
            pending = session.connection; session.connection = null;
        }
        synchronized (diagnosticLock) {
            if (diagnosticOwner == session) {
                diagnostic.cancelled = true;
                if (diagnostic.state == State.WAITING || diagnostic.state == State.PAUSED) diagnostic.state = State.CANCELLED;
            }
        }
        if (pending != null) RequestCancellation.disconnectAsync(pending);
        synchronized (this) { if (current == session) current = null; }
    }
    public void cancel() { QrSession session = current; if (session != null) cancel(session); else clearRecovery(); }
    @Override public void close() {
        QrSession session;
        synchronized (this) { closed = true; session = current; }
        if (session != null) cancelMemory(session);
        clearRecovery();
    }
    /** Rejects new work. An already-running request may checkpoint its result before memory is cleared. */
    public void suspend() {
        QrSession session;
        synchronized (this) { suspended = true; session = current; }
        if (session != null) finishSuspension(session);
    }
    private void finishSuspension(QrSession session) {
        synchronized (session) { if (!suspended || session.busy) return; }
        synchronized (this) { closed = true; }
        cancelMemory(session);
    }

    /** Rehydrates only an encrypted same-host attempt; the server must still confirm it.
     * encodedState is sensitive and must never be logged, placed in a Bundle, or written unencrypted.
     */
    QrSession restore(String encodedState, long checkpointAgeMillis) throws IOException {
        if (encodedState == null || encodedState.length() > 65536 || checkpointAgeMillis < 0)
            throw new IOException("本次授权恢复记录无效，请刷新二维码");
        try {
            JSONTokener parser = new JSONTokener(encodedState);
            Object decoded = parser.nextValue();
            if (!(decoded instanceof JSONObject) || parser.nextClean() != 0) throw new IOException("Invalid record");
            JSONObject data = (JSONObject) decoded;
            if (data.optInt("v", -1) != 1 || !host.equals(data.optString("host"))) throw new IOException("Invalid host");
            String qrkey = data.optString("qrkey"), hiddenkey = data.optString("hiddenkey");
            String uid = data.optString("grantUid"), token = data.optString("grantToken");
            if (!key(qrkey) || !key(hiddenkey) || (uid.isEmpty() != token.isEmpty())
                    || (!uid.isEmpty() && (!uid.matches("[1-9][0-9]{0,18}") || !secretValue(token))))
                throw new IOException("Invalid authorization pair");
            Object time = data.opt("automaticRemaining"), exchanged = data.opt("exchanged");
            if (!(time instanceof Number) || !(exchanged instanceof Boolean)) throw new IOException("Invalid progress");
            double remaining = ((Number) time).doubleValue();
            if (!Double.isFinite(remaining) || remaining != Math.rint(remaining) || remaining < 0 || remaining > AUTO_WINDOW)
                throw new IOException("Invalid automatic window");
            QrSession session = new QrSession(this, host, clock, Math.max(0, (long) remaining - checkpointAgeMillis));
            session.qrkey = qrkey; session.hiddenkey = hiddenkey;
            session.qrText = "https://" + host + "/nuke.php?__lib=login&__act=qrlogin_ui&qrkey=" + encode(qrkey);
            session.grantUid = uid; session.grantToken = token;
            JSONArray cookies = data.optJSONArray("cookies");
            if (cookies == null || cookies.length() > 64) throw new IOException("Invalid attempt cookies");
            for (int i = 0; i < cookies.length(); i++) {
                JSONObject item = cookies.optJSONObject(i);
                if (item == null) throw new IOException("Invalid cookie record");
                String name = item.optString("name"), value = item.optString("value");
                Object rawAge = item.opt("maxAge");
                if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}") || value.length() > 8192
                        || value.matches(".*[\\r\\n\\x00-\\x1f;].*") || !(rawAge instanceof Number))
                    throw new IOException("Invalid cookie values");
                double maxAgeValue = ((Number) rawAge).doubleValue();
                if (!Double.isFinite(maxAgeValue) || maxAgeValue != Math.rint(maxAgeValue) || maxAgeValue < -1)
                    throw new IOException("Invalid cookie lifetime");
                long maxAge = ((Number) rawAge).longValue();
                if (maxAge > 0) maxAge = Math.max(0, maxAge - (checkpointAgeMillis + 999) / 1000);
                if (maxAge == 0) continue;
                HttpCookie cookie = new HttpCookie(name, value);
                cookie.setDomain(host); cookie.setPath("/"); cookie.setSecure(true); cookie.setMaxAge(maxAge);
                session.cookies.put(name, cookie);
            }
            session.exchanged = (Boolean) exchanged;
            if (session.exchanged && !matchingGrantCookies(session)) throw new IOException("Incomplete exchange");
            if (session.autoPollRemainingMillis() == 0) session.state = State.PAUSED;
            QrSession previous;
            synchronized (this) {
                if (closed || suspended) throw new IOException("Closed authorization");
                previous = current; current = session;
            }
            if (previous != null) cancelMemory(previous);
            resetDiagnostic(session); cookiesDiagnostic(session); stateDiagnostic(session, session.state);
            return session;
        } catch (Exception error) {
            throw new IOException("无法恢复本次授权，请刷新二维码重新授权");
        }
    }

    private boolean matchingGrantCookies(QrSession session) {
        if (session.grantUid.isEmpty() || session.grantToken.isEmpty()) return false;
        Map<String, String> values = loginCookies(session);
        return (session.grantUid.equals(values.get("ngaPassportUid")) && secretValue(values.get("ngaPassportCid")))
                || (session.grantUid.equals(values.get("access_uid")) && secretValue(values.get("access_token")));
    }

    private void checkpoint(QrSession session) throws IOException {
        if (recovery == null || session.qrkey.isEmpty() || session.hiddenkey.isEmpty()) return;
        synchronized (session) {
            active(session);
            try {
                JSONObject data = new JSONObject();
                data.put("v", 1); data.put("host", host); data.put("qrkey", session.qrkey); data.put("hiddenkey", session.hiddenkey);
                data.put("grantUid", session.grantUid); data.put("grantToken", session.grantToken);
                data.put("exchanged", session.exchanged); data.put("automaticRemaining", session.autoPollRemainingMillis());
                JSONArray cookies = new JSONArray();
                for (HttpCookie cookie : session.cookies.values()) {
                    if (cookie.hasExpired()) continue;
                    JSONObject item = new JSONObject(); item.put("name", cookie.getName()); item.put("value", cookie.getValue());
                    item.put("maxAge", cookie.getMaxAge()); cookies.put(item);
                }
                data.put("cookies", cookies); String encoded = data.toString();
                if (encoded.length() > 65536) throw new IOException("Authorization record too large");
                recovery.checkpoint(encoded);
            } catch (Exception error) {
                throw new IOException("无法安全保存本次授权进度，请检查设备存储后重试");
            }
        }
    }

    private void clearRecovery() {
        if (recovery != null) try {
            recovery.clear(); recoveryCleanupFailed = false;
        } catch (IOException ignored) {
            recoveryCleanupFailed = true;
        }
    }
    boolean recoveryCleanupFailed() { return recoveryCleanupFailed; }

    private PollResult finish(QrSession session) throws IOException {
        // Follow the public official browser bridge: redeem the returned grant on this origin.
        if (!session.exchanged) {
            requestDiagnostic(session, Stage.EXCHANGE);
            Map<String, String> body = new LinkedHashMap<>();
            body.put("uid", session.grantUid); body.put("cid", session.grantToken);
            Envelope exchange = post(session,
                    "/nuke.php?__lib=login&__act=login_set_cookie_quick&__output=14", body);
            if (exchange.code != 0) return serverState(session, exchange.message);
        }
        Map<String, String> loginCookies = loginCookies(session);
        boolean passport = loginCookies.containsKey("ngaPassportUid") && loginCookies.containsKey("ngaPassportCid");
        String uidName = passport ? "ngaPassportUid" : "access_uid";
        String tokenName = passport ? "ngaPassportCid" : "access_token";
        if (!loginCookies.containsKey(uidName) || !loginCookies.containsKey(tokenName))
            throw new ProtocolException(State.ERROR, "NGA 尚未返回论坛会话，请手动检查或刷新二维码");
        String uid = loginCookies.get(uidName), token = loginCookies.get(tokenName);
        if (!uid.equals(session.grantUid) || !secretValue(token))
            throw new ProtocolException(State.ERROR, "NGA 返回的会话与本次授权不一致，请刷新二维码");
        synchronized (session) { active(session); session.exchanged = true; checkpoint(session); }
        synchronized (diagnosticLock) { if (diagnosticOwner == session) diagnostic.exchangeValidated = true; }
        requestDiagnostic(session, Stage.VERIFY);
        Envelope verify = post(session, "/nuke.php", fields("__act", "iflogin", "app_id", "5004", "device", "", "__ngaClientChecksum", "",
                uidName, uid, tokenName, token));
        if (verify.code != 0) return serverState(session, verify.message);
        boolean confirmed = verified(at(verify.result, 0), uid);
        synchronized (diagnosticLock) { if (diagnosticOwner == session) diagnostic.verificationRecognized = confirmed; }
        if (!confirmed)
            throw new ProtocolException(State.ERROR, "NGA 尚未确认会话有效，请手动检查或刷新二维码");
        // Honor a server deletion/rotation in the verification response instead of saving an old snapshot.
        loginCookies = loginCookies(session);
        passport = loginCookies.containsKey("ngaPassportUid") && loginCookies.containsKey("ngaPassportCid");
        uidName = passport ? "ngaPassportUid" : "access_uid";
        tokenName = passport ? "ngaPassportCid" : "access_token";
        if (!uid.equals(loginCookies.get(uidName)) || !secretValue(loginCookies.get(tokenName)))
            throw new ProtocolException(State.ERROR, "NGA 验证后未保留本次授权会话，请手动检查或刷新二维码");
        StringBuilder saved = new StringBuilder();
        for (Map.Entry<String, String> entry : loginCookies.entrySet()) {
            if (saved.length() > 0) saved.append("; ");
            saved.append(entry.getKey()).append('=').append(entry.getValue());
        }
        synchronized (session) {
            active(session);
            session.qrkey = session.hiddenkey = session.grantUid = session.grantToken = "";
            return update(session, State.SUCCESS, "登录已确认", saved.toString(), 0);
        }
    }

    private Envelope post(QrSession session, String path, Map<String, String> body) throws IOException {
        active(session);
        URL url = new URL("https", host, path);
        Response response;
        try { response = transport.post(url, encodeForm(body), cookieHeader(session), session); }
        catch (ProtocolException error) { throw error; }
        catch (IOException error) { stateDiagnostic(session, State.ERROR); throw new IOException("连接 NGA 登录服务失败，请检查网络后重试"); }
        active(session);
        responseDiagnostic(session, response);
        if (response.status >= 300 && response.status < 400)
            throw new ProtocolException(State.ERROR, "NGA 登录接口发生跳转，请切换域名后重新生成二维码");
        if (response.status != 200)
            throw new ProtocolException(response.status == 403 ? State.VERIFY_REQUIRED : State.ERROR,
                    "NGA 登录服务暂时不可用（HTTP " + response.status + "）");
        if (response.body == null || response.body.length > MAX_BYTES)
            throw new ProtocolException(State.ERROR, "NGA 登录响应过大或为空");
        acceptCookies(session, response.headers);
        cookiesDiagnostic(session);
        Envelope parsed = parse(session, response.body, response.contentType);
        synchronized (session) {
            active(session);
            // A verified exchange response and its new-origin cookies form one checkpoint.
            if (path.contains("__act=login_set_cookie_quick") && parsed.code == 0 && matchingGrantCookies(session))
                session.exchanged = true;
            // A successful grant envelope is checkpointed by poll immediately after
            // validating/storing that grant, rather than saving its previous empty phase.
            if (!("login".equals(body.get("__act")) && parsed.code == 0)) checkpoint(session);
        }
        return parsed;
    }

    private Envelope parse(QrSession session, byte[] bytes, String contentType) throws IOException {
        String text;
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("gbk") || type.contains("gb2312") || type.contains("gb18030"))
            text = new String(bytes, Charset.forName("GB18030"));
        else {
            try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
            catch (CharacterCodingException error) { throw new ProtocolException(State.ERROR, "NGA 登录数据编码无效"); }
        }
        text = text.trim();
        if (text.startsWith("\uFEFF")) text = text.substring(1).trim();
        if (text.startsWith("<"))
            throw new ProtocolException(State.VERIFY_REQUIRED, "NGA 要求额外安全验证，请在官方 APP 完成验证后刷新二维码");
        if (!text.startsWith("{")) throw new ProtocolException(State.ERROR, "NGA 登录服务未返回 JSON 数据");
        try {
            JSONTokener parser = new JSONTokener(text);
            Object value = parser.nextValue();
            if (!(value instanceof JSONObject) || parser.nextClean() != 0)
                throw new IOException("Unexpected JSON envelope");
            JSONObject root = (JSONObject) value;
            jsonDiagnostic(session, root);
            Object code = root.opt("code");
            if (!(code instanceof Number)) throw new IOException("Missing status");
            double status = ((Number) code).doubleValue();
            if (Double.isNaN(status) || Double.isInfinite(status) || status != Math.rint(status)
                    || status < Integer.MIN_VALUE || status > Integer.MAX_VALUE) throw new IOException("Invalid status");
            return new Envelope((int) status, root.opt("result"), root.optString("msg", root.optString("message", "")));
        } catch (Exception error) { throw new ProtocolException(State.ERROR, "NGA 登录服务返回了无法识别的数据"); }
    }

    private PollResult serverState(QrSession session, String raw) {
        String message = safeMessage(session, raw);
        synchronized (session) {
            if (session.cancelled || current != session) return result(State.CANCELLED, "已取消登录", "", 0);
            State state = classify(message);
            if (state == State.WAITING) {
                state = session.autoPollRemainingMillis() == 0 ? State.PAUSED : State.WAITING;
                message = state == State.PAUSED ? "尚未授权，自动检查已暂停，可手动检查或刷新二维码" : "等待使用 NGA APP 扫码并授权";
            }
            if (message.isEmpty()) message = "NGA 未完成授权，请刷新二维码重试";
            return update(session, state, message, "", state == State.WAITING ? INTERVAL : 0);
        }
    }
    private static State classify(String message) {
        if ((message.contains("二维码") || message.contains("登录许可") || message.contains("登录请求"))
                && (message.contains("过期") || message.contains("失效"))) return State.EXPIRED;
        if (message.contains("验证码") || message.contains("安全验证") || message.contains("安全检查")
                || message.contains("滑块") || message.contains("风控") || message.contains("手机验证")) return State.VERIFY_REQUIRED;
        return message.contains("未找到登录许可") ? State.WAITING : State.ERROR;
    }
    private ProtocolException businessException(QrSession session, String raw) {
        String message = safeMessage(session, raw);
        return new ProtocolException(classify(message), message.isEmpty() ? "NGA 未能生成二维码，请稍后重试" : message);
    }
    private static PollResult update(QrSession session, State state, String message, String cookie, long retry) {
        session.state = state; session.message = message;
        session.owner.stateDiagnostic(session, state);
        if (state == State.SUCCESS) session.completedCookie = cookie;
        if (state == State.EXPIRED || state == State.CANCELLED || state == State.VERIFY_REQUIRED) session.owner.clearRecovery();
        return result(state, message, cookie, retry);
    }
    private static PollResult result(State state, String message, String cookie, long retry) {
        return new PollResult(state, message, cookie, retry);
    }
    private void owned(QrSession session) {
        if (session == null || session.owner != this) throw new IllegalArgumentException("无效的二维码会话");
    }
    private void active(QrSession session) throws IOException {
        owned(session);
        if (closed || session.cancelled || current != session) throw new IOException("登录请求已取消");
    }

    private static boolean verified(Object value, String expectedUid) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).longValue() > 0;
        if (value instanceof JSONObject) return expectedUid.equals(scalar(((JSONObject) value).opt("uid")));
        if (value instanceof String) {
            String text = ((String) value).trim();
            return text.equals("1") || text.equalsIgnoreCase("true") || text.equalsIgnoreCase("ok")
                    || text.equals("登录信息有效") || text.equals("登陆信息有效") || text.equals("登录成功");
        }
        return false;
    }
    private static Object at(Object container, int index) {
        if (container instanceof JSONArray) return ((JSONArray) container).opt(index);
        if (container instanceof JSONObject) return ((JSONObject) container).opt(String.valueOf(index));
        return null;
    }
    private static JSONObject objectAt(Object container, int index) {
        Object value = at(container, index); return value instanceof JSONObject ? (JSONObject) value : null;
    }
    private static String stringAt(Object container, int index) { return scalar(at(container, index)); }
    private static String scalar(Object value) { return value instanceof String || value instanceof Number ? String.valueOf(value) : ""; }
    private static boolean key(String value) { return value != null && value.matches("[A-Za-z0-9._~-]{8,2048}"); }
    private static boolean secretValue(String value) { return value != null && value.length() >= 8 && value.length() <= 4096 && value.matches("[!-~&&[^;,\\\"]]+"); }
    private static Map<String, String> fields(String... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("__lib", "login"); result.put("__output", "14"); result.put("__inchst", "UTF-8");
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]);
        return result;
    }
    private static String encodeForm(Map<String, String> values) throws IOException {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> field : values.entrySet()) {
            if (result.length() > 0) result.append('&');
            result.append(encode(field.getKey())).append('=').append(encode(field.getValue()));
        }
        return result.toString();
    }
    private static String encode(String value) throws IOException { return URLEncoder.encode(value, "UTF-8"); }

    private static void acceptCookies(QrSession session, Map<String, List<String>> headers) {
        if (headers == null) return;
        synchronized (session) {
            if (session.cancelled || session.owner.current != session) return;
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                if (header.getKey() == null || !header.getKey().equalsIgnoreCase("Set-Cookie") || header.getValue() == null) continue;
                for (String field : header.getValue()) {
                    if (field == null) continue;
                    List<HttpCookie> decoded;
                    try { decoded = HttpCookie.parse(field); } catch (IllegalArgumentException ignored) { continue; }
                    for (HttpCookie cookie : decoded) {
                        String domain = cookie.getDomain();
                        if (domain != null) {
                            domain = domain.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
                            if (domain.indexOf('.') < 0 || !(session.host.equals(domain) || session.host.endsWith("." + domain))) continue;
                        }
                        String path = cookie.getPath();
                        if (path != null && !("/".equals(path) || "/nuke.php".equals(path))) continue;
                        if (cookie.getValue() == null || cookie.getValue().length() > 8192
                                || cookie.getValue().matches(".*[\\r\\n\\x00-\\x1f;].*")) continue;
                        if (cookie.hasExpired() || cookie.getMaxAge() == 0) session.cookies.remove(cookie.getName());
                        else if (session.cookies.size() < 64 || session.cookies.containsKey(cookie.getName())) session.cookies.put(cookie.getName(), cookie);
                    }
                }
            }
        }
    }
    private static String cookieHeader(QrSession session) throws IOException {
        StringBuilder result = new StringBuilder();
        synchronized (session) {
            for (HttpCookie cookie : session.cookies.values()) {
                if (cookie.hasExpired()) continue;
                if (result.length() > 0) result.append("; ");
                result.append(cookie.getName()).append('=').append(cookie.getValue());
                if (result.length() > 65536)
                    throw new ProtocolException(State.ERROR, "NGA 登录 Cookie 超出限制，请刷新二维码重试");
            }
        }
        return result.toString();
    }
    private static Map<String, String> loginCookies(QrSession session) {
        Map<String, String> result = new LinkedHashMap<>();
        synchronized (session) {
            for (String name : Arrays.asList("ngaPassportUid", "ngaPassportCid", "ngaPassportUrlencodedUname", "access_uid", "access_token")) {
                HttpCookie cookie = session.cookies.get(name);
                if (cookie != null && !cookie.hasExpired() && !cookie.getValue().isEmpty()) result.put(name, cookie.getValue());
            }
        }
        return result;
    }
    private static String safeMessage(QrSession session, String raw) {
        if (raw == null) return "登录服务连接失败，请稍后重试";
        String value = raw.replaceAll("<[^>]{0,1000}>", "").replaceAll("[\\r\\n\\x00-\\x1f]", " ");
        synchronized (session) {
            for (String secret : Arrays.asList(session.qrkey, session.hiddenkey, session.grantUid, session.grantToken))
                if (secret != null && !secret.isEmpty()) value = value.replace(secret, "[隐藏]");
            for (HttpCookie cookie : session.cookies.values())
                if (!cookie.getValue().isEmpty()) value = value.replace(cookie.getValue(), "[隐藏]");
        }
        value = value.replaceAll("(?i)(?:uid|token|cid|qrkey|hiddenkey|cookie)\\s*[:=]\\s*[^\\s,，;；]+", "[隐藏]")
                .replaceAll("[A-Za-z0-9_./+=-]{20,}", "[隐藏]").replaceAll("[0-9]{5,}", "[隐藏]").trim();
        return value.length() > 240 ? value.substring(0, 240) : value;
    }

    /** Bounded metadata only: no request URL/body, response text, IDs, QR keys or cookie/token values. */
    public String safeDiagnostics() {
        synchronized (diagnosticLock) {
            SafeData d = diagnostic;
            return "stage=" + d.stage + " state=" + d.state + " closed=" + closed + " cancelled=" + d.cancelled
                    + " http=" + d.http + " content=" + d.content + " bytes=" + d.bytes + " body=" + d.body
                    + " keys=" + (d.keys.isEmpty() ? "none" : d.keys) + " codeType=" + d.codeType
                    + " codeValid=" + d.codeValid + " code=" + (d.codeValid ? Integer.toString(d.code) : "absent")
                    + " resultType=" + d.resultType + " result0Type=" + d.result0Type + " result3Type=" + d.result3Type
                    + " grantUidPresent=" + d.grantUidPresent + " grantUidType=" + d.grantUidType
                    + " grantTokenPresent=" + d.grantTokenPresent + " grantTokenType=" + d.grantTokenType
                    + " grantReceived=" + d.grantReceived + " exchangeValidated=" + d.exchangeValidated
                    + " verificationRecognized=" + d.verificationRecognized + " setCookieFields=" + d.setCookieFields
                    + " receivedNames=" + cookieNames(d.receivedMask) + " acceptedNames=" + cookieNames(d.acceptedMask)
                    + " passportPairAtResponse=" + d.passportPair + " accessPairAtResponse=" + d.accessPair;
        }
    }
    private void resetDiagnostic(QrSession session) {
        synchronized (diagnosticLock) { diagnosticOwner = session; diagnostic = new SafeData(); }
    }
    private void requestDiagnostic(QrSession session, Stage stage) {
        synchronized (diagnosticLock) {
            if (diagnosticOwner != session || session.cancelled || current != session) return;
            SafeData previous = diagnostic, next = new SafeData();
            next.stage = stage; next.state = previous.state; next.grantReceived = previous.grantReceived;
            next.exchangeValidated = previous.exchangeValidated; next.acceptedMask = previous.acceptedMask;
            next.passportPair = previous.passportPair; next.accessPair = previous.accessPair;
            diagnostic = next;
        }
    }
    private void stateDiagnostic(QrSession session, State state) {
        synchronized (diagnosticLock) { if (diagnosticOwner == session) diagnostic.state = state; }
    }
    private void responseDiagnostic(QrSession session, Response response) {
        synchronized (diagnosticLock) {
            if (diagnosticOwner != session || session.cancelled || current != session) return;
            SafeData d = diagnostic; d.http = response.status; d.bytes = response.body == null ? 0 : response.body.length;
            String type = response.contentType == null ? "" : response.contentType.toLowerCase(Locale.ROOT);
            d.content = type.contains("json") ? "json" : type.contains("html") ? "html" : type.startsWith("text/") ? "text" : "other";
            byte[] bytes = response.body; int start = 0;
            if (bytes != null && bytes.length >= 3 && (bytes[0] & 255) == 239 && (bytes[1] & 255) == 187 && (bytes[2] & 255) == 191) start = 3;
            while (bytes != null && start < bytes.length && (bytes[start] == 32 || bytes[start] == 9 || bytes[start] == 10 || bytes[start] == 13)) start++;
            d.body = bytes == null || start == bytes.length ? "EMPTY" : bytes[start] == '<' ? "HTML"
                    : bytes[start] == '{' ? "JSON_LIKE" : bytes[start] == '[' ? "ARRAY_LIKE" : "OTHER";
            if (response.headers != null) for (Map.Entry<String, List<String>> header : response.headers.entrySet()) {
                if (header.getKey() == null || !header.getKey().equalsIgnoreCase("Set-Cookie") || header.getValue() == null) continue;
                d.setCookieFields += header.getValue().size();
                for (String field : header.getValue()) {
                    if (field == null) continue;
                    int equals = field.indexOf('=');
                    if (equals <= 0 || equals > 64) continue;
                    String name = field.substring(0, equals).trim();
                    for (int i = 0; i < COOKIE_NAMES.length; i++) if (COOKIE_NAMES[i].equals(name)) d.receivedMask |= 1 << i;
                }
            }
        }
    }
    private void jsonDiagnostic(QrSession session, JSONObject root) {
        synchronized (diagnosticLock) {
            if (diagnosticOwner != session || session.cancelled || current != session) return;
            SafeData d = diagnostic; d.body = "JSON"; StringBuilder keys = new StringBuilder();
            for (String name : Arrays.asList("code", "result", "data", "error", "msg", "message")) if (root.has(name)) {
                if (keys.length() > 0) keys.append('|'); keys.append(name).append(':').append(typeOf(root.opt(name)));
            }
            d.keys = keys.toString(); Object status = root.opt("code"); d.codeType = typeOf(status);
            if (status instanceof Number) {
                double number = ((Number) status).doubleValue();
                d.codeValid = !Double.isInfinite(number) && !Double.isNaN(number) && number == Math.rint(number)
                        && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE;
                if (d.codeValid) d.code = (int) number;
            }
            Object result = root.opt("result"), zero = at(result, 0), three = at(result, 3);
            d.resultType = typeOf(result); d.result0Type = typeOf(zero); d.result3Type = typeOf(three);
            if (three instanceof JSONObject) {
                Object uid = ((JSONObject) three).opt("uid"), token = ((JSONObject) three).opt("token");
                d.grantUidType = typeOf(uid); d.grantTokenType = typeOf(token);
                d.grantUidPresent = uid != null && uid != JSONObject.NULL; d.grantTokenPresent = token != null && token != JSONObject.NULL;
            }
        }
    }
    private void cookiesDiagnostic(QrSession session) {
        Map<String, String> values = loginCookies(session); int mask = 0;
        for (int i = 0; i < COOKIE_NAMES.length; i++) if (values.containsKey(COOKIE_NAMES[i])) mask |= 1 << i;
        synchronized (diagnosticLock) {
            if (diagnosticOwner != session || session.cancelled || current != session) return;
            diagnostic.acceptedMask = mask; diagnostic.passportPair = (mask & 3) == 3; diagnostic.accessPair = (mask & 24) == 24;
        }
    }
    private static String typeOf(Object value) {
        if (value == null) return "absent";
        if (value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) return "object";
        if (value instanceof JSONArray) return "array";
        if (value instanceof String) return "string";
        if (value instanceof Number) return "number";
        if (value instanceof Boolean) return "boolean";
        return "other";
    }
    private static String cookieNames(int mask) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < COOKIE_NAMES.length; i++) if ((mask & 1 << i) != 0) {
            if (names.length() > 0) names.append(','); names.append(COOKIE_NAMES[i]);
        }
        return names.length() == 0 ? "none" : names.toString();
    }
    private static final class SafeData {
        Stage stage = Stage.IDLE;
        State state = State.WAITING;
        int http, bytes, code, setCookieFields, receivedMask, acceptedMask;
        String content = "none", body = "none", keys = "", codeType = "absent", resultType = "absent", result0Type = "absent", result3Type = "absent";
        String grantUidType = "absent", grantTokenType = "absent";
        boolean codeValid, grantUidPresent, grantTokenPresent, grantReceived, exchangeValidated, verificationRecognized, passportPair, accessPair, cancelled;
    }

    /** Implementations synchronously encrypt/commit and fence an attempt; never use an existing login cookie. */
    interface Recovery { void checkpoint(String state) throws IOException; void clear() throws IOException; }
    interface Clock { long now(); }
    interface Transport { Response post(URL url, String form, String cookie, QrSession session) throws IOException; }
    static final class Response {
        final int status; final byte[] body; final String contentType; final Map<String, List<String>> headers;
        Response(int status, byte[] body, String contentType, Map<String, List<String>> headers) {
            this.status = status; this.body = body; this.contentType = contentType; this.headers = headers;
        }
    }
    private static final class Envelope {
        final int code; final Object result; final String message;
        Envelope(int code, Object result, String message) { this.code = code; this.result = result; this.message = message; }
    }
    private static final class ProtocolException extends IOException {
        private static final long serialVersionUID = 1L;
        final State state;
        ProtocolException(State state, String message) { super(message); this.state = state; }
    }
    private static final class NetworkTransport implements Transport {
        @Override public Response post(URL url, String form, String cookie, QrSession session) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            synchronized (session) {
                if (session.owner.closed || session.cancelled || session.owner.current != session) { connection.disconnect(); throw new IOException("已取消"); }
                session.connection = connection;
            }
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("User-Agent", "NGA_WP_JW");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                connection.setRequestProperty("Origin", "https://" + session.host);
                connection.setRequestProperty("Referer", "https://" + session.host + "/nuke/account_copy.html?qrlogin");
                if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
                byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                session.owner.active(session);
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
                int status = connection.getResponseCode();
                session.owner.active(session);
                if (status >= 300 && status < 400) return new Response(status, new byte[0], "", Collections.emptyMap());
                if (connection.getContentLengthLong() > MAX_BYTES) throw new IOException("响应过大");
                InputStream source = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                ByteArrayOutputStream received = new ByteArrayOutputStream();
                if (source != null) try (InputStream input = source) {
                    byte[] buffer = new byte[4096];
                    while (true) {
                        session.owner.active(session);
                        int count = input.read(buffer);
                        session.owner.active(session);
                        if (count == -1) break;
                        if (received.size() + count > MAX_BYTES) throw new IOException("响应过大");
                        received.write(buffer, 0, count);
                    }
                }
                return new Response(status, received.toByteArray(), connection.getContentType(), connection.getHeaderFields());
            } finally {
                synchronized (session) { if (session.connection == connection) session.connection = null; }
                connection.disconnect();
            }
        }
    }
}
