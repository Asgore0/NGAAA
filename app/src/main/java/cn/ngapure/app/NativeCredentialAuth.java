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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Cipher;

/** Official HTTPS password/SMS protocol. Secrets stay in this short-lived owner.
 * The UI must initiate SMS sending and supply a real Tencent human-verification
 * ticket. Call methods serially off the UI thread; close interrupts networking.
 */
public final class NativeCredentialAuth implements AutoCloseable {
    public enum State { SUCCESS, SMS_SENT, ACCOUNT_REQUIRED, MATCH_PHONE, VERIFY_REQUIRED, ERROR, CANCELLED }
    static final int MAX_BYTES = 1024 * 1024;
    // Public encryption key published by NGA /nuke/account_copy.html, not an app secret.
    private static final String PUBLIC_KEY =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAyKzZWDimCN1OCprqWUhF"
            + "UPhcwxDE62/BFVP6LtQHJu+65dm4YNmDvzitmcfaXW9YbhXnd4oP7j+6vpcgJQ+p"
            + "3ucySo1ZnqO0Bb2JKEtxpCmxe7IYXhFEkJqHpFYBTiAxQz2n2mX4JZy/ehBUSMjz"
            + "gzd0NdG6Ai1C42oCzYltUOjNWZUNHn1nqpElSWHnUWqkdN8+5ISP/ZMKiQdFANkE"
            + "qDGw3/34qyF+E/hVgrGF4/CcWNP/LJCdB6DYtx7VPlQZF0tP1s+q/++rC4rQ2wmV"
            + "l2V8zGh1j7ojZbt62hVjy6byK1E/2XYo97ZtL4KDW7F5jJMvSDRFR7901UR8hCdf"
            + "4wIDAQAB";
    private final String host;
    private final Transport transport;
    private final GrantExchange exchange;
    private final NativeAuth bridge;
    private final Map<String, HttpCookie> cookies = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private volatile boolean closed;
    private volatile HttpURLConnection connection;
    private String captchaId = "", pageId;
    private volatile long lastSmsAt;

    public NativeCredentialAuth(String host) {
        this(host, null, null);
    }
    NativeCredentialAuth(String inputHost, Transport transport, GrantExchange exchange) {
        String normalized = inputHost == null ? "" : inputHost.toLowerCase(Locale.ROOT);
        if (!Arrays.asList("bbs.nga.cn", "ngabbs.com", "nga.178.com").contains(normalized))
            throw new IllegalArgumentException("不支持的 NGA 登录域名");
        host = normalized;
        this.transport = transport == null ? this::network : transport;
        bridge = exchange == null ? new NativeAuth(host) : null;
        this.exchange = exchange == null ? (uid, token) -> {
            NativeAuth.PollResult verified = bridge.completeCredentialGrant(uid, token);
            return verified.state == NativeAuth.State.SUCCESS
                    ? new Result(State.SUCCESS, "登录成功", verified.cookieHeader)
                    : new Result(State.ERROR, "NGA 未确认本次登录，请重试", "");
        } : exchange;
        pageId = "P" + nonce();
    }
    public static final class Result {
        public final State state;
        public final String message;
        /** Sensitive; commit only while this exact UI request is still current. */
        public final String cookieHeader;
        Result(State state, String message, String cookie) {
            this.state = state; this.message = message;
            cookieHeader = state == State.SUCCESS ? cookie : "";
        }
        @Override public String toString() { return "NativeCredentialAuth.Result{state=" + state + "}"; }
    }
    public static final class ImageChallenge {
        public final byte[] image;
        ImageChallenge(byte[] image) { this.image = image; }
        @Override public String toString() { return "NativeCredentialAuth.ImageChallenge"; }
    }
    /** Refreshing invalidates the previous image, just as NGA's public login UI does. */
    public ImageChallenge imageChallenge() throws IOException {
        active(); captchaId = "";
        String requestedId = "login" + nonce();
        Response response = request("GET", "/login_check_code.php?id=" + requestedId + "&from=login", Collections.emptyMap());
        if (!response.type.toLowerCase(Locale.ROOT).startsWith("image/") || response.body.length == 0
                || response.body.length > 256 * 1024 || !imageSignature(response.body))
            throw new IOException("NGA 图形验证码暂时不可用，请刷新重试");
        // A failed refresh must leave neither the old image nor the newly requested,
        // undisplayed image eligible for password submission.
        active(); captchaId = requestedId;
        return new ImageChallenge(response.body);
    }
    /** The native decoder may reject an image after transport validation. */
    void discardImageChallenge() { captchaId = ""; }
    /** Consumes and clears the supplied password array even when validation fails. */
    public Result passwordLogin(String account, char[] password, String answer, String matchPhone) throws IOException {
        try {
            active(); String name = input(account, 128, "请输入账号");
            if (captchaId.isEmpty() || answer == null || !answer.trim().matches("[A-Za-z0-9]{4,8}"))
                return result(State.VERIFY_REQUIRED, "请填写图片中的验证码");
            String encrypted = encrypt(password);
            Map<String, String> fields = base("login");
            fields.put("name", name); fields.put("type", accountType(name)); fields.put("password", encrypted);
            fields.put("rid", captchaId); fields.put("captcha", answer.trim()); fields.put("prid", pageId);
            if (matchPhone != null && !matchPhone.trim().isEmpty()) fields.put("MATCH_PHONE", input(matchPhone, 40, "请输入绑定手机号"));
            return authenticate(fields, name, answer, matchPhone);
        } finally { captchaId = ""; if (password != null) Arrays.fill(password, '\0'); }
    }
    /** A real successful Tencent callback is required. This method never runs automatically. */
    public Result sendSms(String phone, String account, String ticket, String randstr) throws IOException {
        active(); String number = phone(phone);
        if (!validTicket(ticket, randstr)) return result(State.VERIFY_REQUIRED, "请先完成官方安全验证");
        long now = System.nanoTime() / 1_000_000L;
        if (lastSmsAt != 0 && now - lastSmsAt < 15000) return result(State.ERROR, "请稍后再发送验证码");
        Map<String, String> fields = base("smsloginsendcode");
        String name = account == null ? "" : account.trim();
        if (name.length() > 128 || controls(name)) throw new IOException("账号格式无效");
        fields.put("name", name); fields.put("type", name.matches("[0-9]+") ? "id" : "");
        fields.put("checkto", number); fields.put("checktype", "phone");
        fields.put("rid", "smsloginsendcode_QQCAPTCHA_" + randstr); fields.put("captcha", ticket); fields.put("prid", pageId);
        lastSmsAt = now;
        Envelope envelope = envelope(request("POST", "/nuke.php", fields));
        active();
        if (envelope.code != 0) {
            if (name.isEmpty() && needsSmsAccount(envelope.message))
                return result(State.ACCOUNT_REQUIRED, "此账号不能仅凭手机号登录，请补充 NGA 用户名或 UID 后再次点击发送验证码。");
            return error(envelope, number, name, ticket, randstr);
        }
        if (!(at(envelope.result, 0) instanceof String) || ((String) at(envelope.result, 0)).trim().isEmpty())
            throw new IOException("NGA 未明确确认验证码发送，请稍后再试");
        return result(State.SMS_SENT, "验证码已发送，请查看手机短信");
    }
    /** Local duplicate-send throttle, including rejected/uncertain requests. Not a server rule. */
    public long smsRetryAfterMillis() {
        return lastSmsAt == 0 ? 0 : Math.max(0, 15000 - (System.nanoTime() / 1_000_000L - lastSmsAt));
    }
    private static boolean needsSmsAccount(String message) {
        String plain = message.replaceAll("<[^>]*>", "").replaceAll("[\\s\\u00a0\\u3000]+", "").toLowerCase(Locale.ROOT);
        return plain.startsWith("用户id错误") || plain.startsWith("用户名错误")
                || (plain.contains("手机号") && (plain.contains("不能仅凭") || plain.contains("不能仅用") || plain.contains("不能只"))
                && (plain.contains("用户名") || plain.contains("用户id") || plain.contains("uid")));
    }
    /** Consumes and clears the supplied SMS code; never sends a new message. */
    public Result smsLogin(String phone, char[] code) throws IOException {
        try {
            active(); String number = phone(phone);
            Map<String, String> fields = base("smslogin");
            fields.put("name", number); fields.put("type", "phone"); fields.put("password", encrypt(code));
            return authenticate(fields, number);
        } finally { if (code != null) Arrays.fill(code, '\0'); }
    }
    private Result authenticate(Map<String, String> fields, String... redactions) throws IOException {
        Envelope envelope = envelope(request("POST", "/nuke.php", fields));
        active();
        if (envelope.code != 0) {
            String[] safeRedactions = Arrays.copyOf(redactions, redactions.length + 1);
            safeRedactions[redactions.length] = fields.get("password");
            return error(envelope, safeRedactions);
        }
        Object item = at(envelope.result, 3);
        if (!(item instanceof JSONObject)) throw new IOException("NGA 未返回完整登录授权，请重试");
        JSONObject grant = (JSONObject) item;
        String uid = scalar(grant.opt("uid")), token = scalar(grant.opt("token"));
        if (!uid.matches("[1-9][0-9]{0,18}") || token.length() < 8 || token.length() > 4096
                || !token.matches("[!-~&&[^;,\\\"]]+")) throw new IOException("NGA 未返回可验证的登录授权");
        Result verified = exchange.redeem(uid, token); active();
        if (verified == null || verified.state != State.SUCCESS || verified.cookieHeader.isEmpty())
            return result(State.ERROR, "NGA 尚未确认登录状态，请重试");
        return verified;
    }
    private Result error(Envelope envelope, String... redactions) {
        String message = envelope.message;
        for (String value : redactions) if (value != null && !value.isEmpty()) message = message.replace(value, "[隐藏]");
        message = message.replaceAll("(?i)(?:uid|token|cid|cookie|password|captcha)\\s*[:=]\\s*[^\\s,，;；]+", "[隐藏]")
                .replaceAll("<[^>]*>", "").replaceAll("[\\r\\n\\x00-\\x1f]", " ");
        if (message.length() > 180) message = message.substring(0, 180);
        if ("MATCH_PHONE".equals(envelope.marker)) return result(State.MATCH_PHONE, "NGA 要求安全验证，请补充此账号绑定的手机号");
        if (message.contains("验证码") || message.contains("安全验证") || message.contains("风控") || message.contains("滑块"))
            return result(State.VERIFY_REQUIRED, message.isEmpty() ? "NGA 要求重新完成安全验证" : message);
        return result(State.ERROR, message.isEmpty() ? "NGA 未完成登录，请检查输入后重试" : message);
    }
    private Map<String, String> base(String action) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("__lib", "login"); result.put("__output", "14"); result.put("__inchst", "UTF-8");
        result.put("__act", action); result.put("app_id", "5004"); result.put("device", "");
        result.put("trackid", ""); result.put("__ngaClientChecksum", ""); return result;
    }
    private Response request(String method, String path, Map<String, String> fields) throws IOException {
        active(); Response response;
        try { response = transport.request(method, new URL("https", host, path), fields, cookieHeader()); }
        catch (IOException error) { active(); throw new IOException("连接 NGA 登录服务失败，请检查网络后重试"); }
        active();
        if (response == null || response.status != 200) throw new IOException("NGA 登录服务暂时不可用，请稍后重试");
        if (response.body == null || response.body.length > MAX_BYTES) throw new IOException("NGA 登录响应过大或为空");
        acceptCookies(response.headers); return response;
    }
    private Envelope envelope(Response response) throws IOException {
        String text = new String(response.body, response.type.toLowerCase(Locale.ROOT).contains("gb") ? Charset.forName("GB18030") : StandardCharsets.UTF_8).trim();
        if (text.startsWith("\uFEFF")) text = text.substring(1).trim();
        if (text.startsWith("<")) throw new IOException("NGA 要求额外安全验证；请稍后重试或使用扫码登录");
        try {
            JSONTokener reader = new JSONTokener(text); Object decoded = reader.nextValue();
            if (!(decoded instanceof JSONObject) || reader.nextClean() != 0) throw new IOException("Invalid envelope");
            JSONObject root = (JSONObject) decoded; Object code = root.opt("code");
            if (!(code instanceof Number)) throw new IOException("Missing code");
            double number = ((Number) code).doubleValue();
            if (!Double.isFinite(number) || number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw new IOException("Invalid code");
            String marker = ""; Object errors = root.opt("error");
            if ("MATCH_PHONE".equals(at(errors, 1)) || "MATCH_PHONE".equals(at(root.opt("result"), 1))
                    || "MATCH_PHONE".equals(root.optString("msg"))) marker = "MATCH_PHONE";
            String message = root.optString("msg", "").trim();
            if (message.isEmpty()) message = root.optString("message", "").trim();
            if (message.isEmpty() && at(errors, 0) instanceof String) message = (String) at(errors, 0);
            return new Envelope((int) number, root.opt("result"), message, marker);
        } catch (Exception error) { throw new IOException("NGA 登录服务返回了无法识别的数据"); }
    }
    static String encrypt(char[] secret) throws IOException {
        byte[] bytes = null;
        try {
            if (secret == null || secret.length == 0) throw new IOException("请输入密码或验证码");
            bytes = new String(secret).trim().getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > 245) throw new IOException("密码或验证码长度无效");
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY))));
            return Base64.getEncoder().encodeToString(cipher.doFinal(bytes));
        } catch (GeneralSecurityException error) { throw new IOException("设备无法安全加密登录信息"); }
        finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }
    static boolean validTicket(String ticket, String randstr) {
        return ticket != null && ticket.length() >= 8 && ticket.length() <= 8192 && !ticket.startsWith("terror_") && !ticket.startsWith("trerror_")
                && ticket.matches("[!-~&&[^\\\"\\\\]]+") && randstr != null && randstr.matches("[A-Za-z0-9_-]{1,512}");
    }
    static String accountType(String name) {
        if (name.matches("[0-9]+")) return name.length() > 9 ? "phone" : "id";
        if (name.contains("@")) return "mail";
        return name.matches("[0-9]+[- ][0-9]+") ? "phone" : "";
    }
    private static String phone(String value) throws IOException {
        String number = input(value, 40, "请输入绑定手机号");
        if (!number.matches("[+]?[0-9][0-9 -]{4,38}[0-9]")) throw new IOException("手机号格式无效");
        if ((number.startsWith("0") || number.startsWith("+")) && number.matches("[+]?[0-9]+")) throw new IOException("请用空格或短横线分隔国家区号与手机号");
        return number;
    }
    private static String input(String value, int maximum, String missing) throws IOException {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) throw new IOException(missing);
        if (text.length() > maximum || controls(text)) throw new IOException("登录输入格式无效"); return text;
    }
    private static boolean controls(String value) { return value.matches("(?s).*[\\x00-\\x1f\\x7f].*"); }
    private String nonce() { byte[] bytes = new byte[12]; random.nextBytes(bytes); StringBuilder value = new StringBuilder(); for (byte b : bytes) value.append((b & 255) % 10); return value.toString(); }
    private static Result result(State state, String message) { return new Result(state, message, ""); }
    private static Object at(Object value, int index) {
        if (value instanceof JSONArray) return ((JSONArray) value).opt(index);
        if (value instanceof JSONObject) return ((JSONObject) value).opt(String.valueOf(index)); return null;
    }
    private static String scalar(Object value) { return value instanceof String || value instanceof Number ? String.valueOf(value) : ""; }
    private static boolean imageSignature(byte[] b) {
        return b.length >= 8 && ((b[0] == (byte) 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G')
                || (b[0] == (byte) 0xff && b[1] == (byte) 0xd8) || (b[0] == 'G' && b[1] == 'I' && b[2] == 'F'));
    }
    private void active() throws IOException { if (closed) throw new IOException("本次登录已取消"); }
    @Override public void close() {
        closed = true; HttpURLConnection pending = connection; connection = null;
        if (pending != null) RequestCancellation.disconnectAsync(pending);
        if (bridge != null) bridge.close(); synchronized (cookies) { cookies.clear(); }
        captchaId = pageId = "";
    }
    private void acceptCookies(Map<String, List<String>> headers) {
        if (headers == null) return;
        synchronized (cookies) {
            if (closed) return;
            for (Map.Entry<String, List<String>> row : headers.entrySet()) {
                if (!"Set-Cookie".equalsIgnoreCase(row.getKey()) || row.getValue() == null) continue;
                for (String field : row.getValue()) try {
                    for (HttpCookie cookie : HttpCookie.parse(field)) {
                        String domain = cookie.getDomain();
                        if (domain != null && !(domain.startsWith(".") ? domain.substring(1) : domain).equalsIgnoreCase(host)) continue;
                        if (!cookie.getName().matches("[A-Za-z0-9_]{1,80}") || cookie.getValue().length() > 8192 || controls(cookie.getValue()) || cookie.getValue().contains(";")) continue;
                        if (cookie.hasExpired() || cookie.getMaxAge() == 0) cookies.remove(cookie.getName());
                        else if (cookies.size() < 32 || cookies.containsKey(cookie.getName())) cookies.put(cookie.getName(), cookie);
                    }
                } catch (IllegalArgumentException ignored) { /* Invalid server cookie is never echoed. */ }
            }
        }
    }
    private String cookieHeader() {
        StringBuilder value = new StringBuilder(); synchronized (cookies) {
            for (HttpCookie cookie : cookies.values()) if (!cookie.hasExpired()) { if (value.length() > 0) value.append("; "); value.append(cookie.getName()).append('=').append(cookie.getValue()); }
        } return value.toString();
    }
    private Response network(String method, URL url, Map<String, String> fields, String cookie) throws IOException {
        active(); HttpURLConnection pending = (HttpURLConnection) url.openConnection(); connection = pending;
        try {
            active(); pending.setInstanceFollowRedirects(false); pending.setConnectTimeout(15000); pending.setReadTimeout(20000);
            pending.setRequestMethod(method); pending.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NgaPure/0.10 native-auth");
            pending.setRequestProperty("Accept-Encoding", "identity"); pending.setRequestProperty("Origin", "https://" + host);
            pending.setRequestProperty("Referer", "https://" + host + "/nuke/account_copy.html?login");
            if (!cookie.isEmpty()) pending.setRequestProperty("Cookie", cookie);
            if ("POST".equals(method)) {
                StringBuilder form = new StringBuilder();
                for (Map.Entry<String, String> field : fields.entrySet()) { if (form.length() > 0) form.append('&'); form.append(URLEncoder.encode(field.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(field.getValue(), "UTF-8")); }
                byte[] bytes = form.toString().getBytes(StandardCharsets.UTF_8); pending.setDoOutput(true);
                pending.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8"); pending.setFixedLengthStreamingMode(bytes.length);
                try { active(); try (OutputStream output = pending.getOutputStream()) { output.write(bytes); } } finally { Arrays.fill(bytes, (byte) 0); }
            }
            int status = pending.getResponseCode();
            active();
            if (status != 200) return new Response(status, new byte[0], "", Collections.emptyMap());
            if (pending.getContentLengthLong() > MAX_BYTES) throw new IOException("Response too large");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = pending.getInputStream()) {
                byte[] buffer = new byte[4096];
                while (true) {
                    active(); int count = input.read(buffer); active(); if (count == -1) break;
                    if (bytes.size() + count > MAX_BYTES) throw new IOException("Response too large"); bytes.write(buffer, 0, count);
                }
            }
            return new Response(status, bytes.toByteArray(), pending.getContentType(), pending.getHeaderFields());
        } finally { if (connection == pending) connection = null; pending.disconnect(); }
    }
    interface Transport { Response request(String method, URL url, Map<String, String> fields, String cookie) throws IOException; }
    interface GrantExchange { Result redeem(String uid, String token) throws IOException; }
    static final class Response {
        final int status; final byte[] body; final String type; final Map<String, List<String>> headers;
        Response(int status, byte[] body, String type, Map<String, List<String>> headers) { this.status = status; this.body = body; this.type = type == null ? "" : type; this.headers = headers; }
    }
    private static final class Envelope {
        final int code; final Object result; final String message, marker;
        Envelope(int code, Object result, String message, String marker) { this.code = code; this.result = result; this.message = message; this.marker = marker; }
    }
}
