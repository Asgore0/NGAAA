package cn.ngapure.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Optional positive-author avatar metadata. Session cookies stay on trusted NGA origins; no profile storage or UID-derived image URLs. */
public final class NativeAvatarResolver implements AutoCloseable {
    public interface Source { String avatar(String uid, RequestCancellation.Token cancellation) throws IOException; }
    public interface Callback { void resolved(String avatar); void failed(boolean busy); }
    public interface Request { void cancel(); }
    interface Clock { long millis(); }
    private static final long PRESENT_TTL = 6 * 60 * 60 * 1000L, EMPTY_TTL = 2 * 60 * 1000L;
    private final Object lock = new Object();
    private final Source source;
    private final Executor delivery;
    private final Clock clock;
    private final ThreadPoolExecutor workers;
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(128, .75f, true);
    private boolean closed;

    public NativeAvatarResolver(Source source, Executor delivery) {
        this(source, delivery, () -> System.nanoTime() / 1000000L,
                new ThreadPoolExecutor(2, 2, 10L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(24), new ThreadFactory() {
                    private int id;
                    @Override public synchronized Thread newThread(Runnable action) {
                        Thread thread = new Thread(action, "NgaPublicAvatar-" + ++id); thread.setDaemon(true); return thread;
                    }
                }));
        workers.allowCoreThreadTimeOut(true);
    }
    NativeAvatarResolver(Source source, Executor delivery, Clock clock, ThreadPoolExecutor workers) {
        if (source == null || delivery == null || clock == null || workers == null) throw new IllegalArgumentException("缺少头像解析器依赖");
        this.source = source; this.delivery = delivery; this.clock = clock; this.workers = workers;
    }
    public static boolean eligible(String uid) {
        if (uid == null || !uid.matches("[1-9][0-9]{0,9}")) return false;
        try { return Long.parseLong(uid) <= Integer.MAX_VALUE; } catch (NumberFormatException invalid) { return false; }
    }
    /** Reuses real same-response metadata already shown by the forum API, without a profile request. */
    public void remember(String uid, String avatar) {
        if (!eligible(uid)) return;
        String normalized = AvatarSources.normalize(avatar);
        if (normalized.isEmpty()) return;
        Task cancelled;
        synchronized (lock) {
            if (closed) return;
            cache.put(uid, new Cached(normalized, clock.millis() + PRESENT_TTL));
            while (cache.size() > 128) cache.remove(cache.keySet().iterator().next());
            cancelled = tasks.remove(uid);
            if (cancelled != null) {
                if (cancelled.future != null) { cancelled.future.cancel(true); if (cancelled.future instanceof Runnable) workers.remove((Runnable) cancelled.future); }
                for (Listener listener : cancelled.listeners) deliver(listener, normalized, false);
                cancelled.listeners.clear();
            }
        }
        if (cancelled != null) cancelled.cancellation.cancel();
    }

    public Request load(String uid, Callback callback) {
        if (callback == null) throw new IllegalArgumentException("缺少头像回调");
        Listener listener = new Listener(callback);
        synchronized (lock) {
            if (closed) { listener.active = false; return listener; }
            if (!eligible(uid)) { deliver(listener, "", false); return listener; }
            Cached cached = cache.get(uid);
            if (cached != null && cached.expires > clock.millis()) { deliver(listener, cached.avatar, false); return listener; }
            if (cached != null) cache.remove(uid);
            Task task = tasks.get(uid);
            if (task == null) {
                if (tasks.size() >= 24) { deliver(listener, null, true); return listener; }
                task = new Task(uid); tasks.put(uid, task);
            }
            if (task.listeners.size() >= 32) { deliver(listener, null, true); return listener; }
            task.listeners.add(listener); listener.task = task;
            if (task.future == null) {
                try { task.future = workers.submit(task); }
                catch (RejectedExecutionException busy) { tasks.remove(uid); task.listeners.clear(); listener.task = null; deliver(listener, null, true); }
            }
            return listener;
        }
    }
    private void deliver(Listener listener, String avatar, boolean busy) {
        delivery.execute(() -> {
            synchronized (lock) { if (closed || !listener.active) return; listener.active = false; listener.task = null; }
            if (avatar == null) listener.callback.failed(busy); else listener.callback.resolved(avatar);
        });
    }
    private final class Listener implements Request {
        final Callback callback;
        boolean active = true;
        Task task;
        Listener(Callback callback) { this.callback = callback; }
        @Override public void cancel() {
            Task cancelled = null;
            synchronized (lock) {
                active = false;
                if (task != null) {
                    task.listeners.remove(this);
                    if (task.listeners.isEmpty() && tasks.remove(task.uid, task)) {
                        cancelled = task;
                        if (task.future != null) { task.future.cancel(true); if (task.future instanceof Runnable) workers.remove((Runnable) task.future); }
                    }
                    task = null;
                }
            }
            if (cancelled != null) cancelled.cancellation.cancel();
        }
    }
    private final class Task implements Runnable {
        final String uid;
        final RequestCancellation.Token cancellation = new RequestCancellation.Token();
        final List<Listener> listeners = new ArrayList<>();
        Future<?> future;
        Task(String uid) { this.uid = uid; }
        @Override public void run() {
            String avatar = null;
            try { avatar = AvatarSources.normalize(source.avatar(uid, cancellation)); }
            catch (IOException | RuntimeException failure) { /* A failed public lookup does not invent an avatar. */ }
            synchronized (lock) {
                if (closed || tasks.get(uid) != this || cancellation.isCancelled()) return;
                tasks.remove(uid);
                cache.put(uid, new Cached(avatar, clock.millis() + (avatar == null ? 10000L : avatar.isEmpty() ? EMPTY_TTL : PRESENT_TTL)));
                while (cache.size() > 128) cache.remove(cache.keySet().iterator().next());
                for (Listener listener : listeners) deliver(listener, avatar, false);
                listeners.clear();
            }
        }
    }
    private static final class Cached {
        final String avatar;
        final long expires;
        Cached(String avatar, long expires) { this.avatar = avatar; this.expires = expires; }
    }
    public String state() { synchronized (lock) { return "publicAvatarTasks=" + tasks.size() + " publicAvatarCache=" + cache.size(); } }
    @Override public void close() {
        List<Task> cancelled;
        synchronized (lock) {
            if (closed) return; closed = true; cancelled = new ArrayList<>(tasks.values());
            for (Task task : cancelled) for (Listener listener : task.listeners) listener.active = false;
            tasks.clear(); cache.clear();
        }
        workers.shutdownNow();
        for (Task task : cancelled) task.cancellation.cancel();
    }

    /** Explicit opt-in for logged-out public metadata. No cookies, redirects or profile persistence. */
    public static Source publicSource() { return fixedSource("bbs.nga.cn", "", () -> true); }

    /**
     * Same-origin read-only metadata using an immutable Activity session snapshot. Cookies never
     * reach an image source or redirect. The owner must replace/close its resolver on scope changes.
     */
    public static Source sessionSource(String host, String cookie, BooleanSupplier stillCurrent) {
        if (!("bbs.nga.cn".equals(host) || "ngabbs.com".equals(host) || "nga.178.com".equals(host)))
            throw new IllegalArgumentException("不支持的 NGA 头像资料来源");
        if (cookie == null || cookie.trim().isEmpty() || cookie.length() > 65536 || stillCurrent == null)
            throw new IllegalArgumentException("缺少有效头像会话范围");
        for (int i = 0; i < cookie.length(); i++) {
            char c = cookie.charAt(i);
            if (c < 32 || c >= 127) throw new IllegalArgumentException("无效头像会话格式");
        }
        return fixedSource(host, cookie, stillCurrent);
    }
    private static void checkCurrent(BooleanSupplier stillCurrent) throws IOException {
        RequestCancellation.check();
        boolean current;
        try { current = stillCurrent.getAsBoolean(); }
        catch (RuntimeException invalidScope) { throw new IOException("头像会话已变化"); }
        if (!current) throw new IOException("头像会话已变化");
    }
    private static Source fixedSource(String host, String cookie, BooleanSupplier stillCurrent) {
        return (uid, cancellation) -> {
            if (!eligible(uid)) throw new IOException("不是公开作者编号");
            RequestCancellation.Scope scope = RequestCancellation.bind(cancellation);
            HttpURLConnection connection = null;
            RequestCancellation.Registration registration = null;
            try {
                checkCurrent(stillCurrent);
                URL url = new URL("https://" + host + "/nuke.php?__lib=ucp&__act=get&uid=" + uid + "&__output=8");
                connection = (HttpURLConnection) url.openConnection(); registration = RequestCancellation.track(connection);
                connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10000); connection.setReadTimeout(12000);
                connection.setRequestProperty("User-Agent", "NGA_WP_JW");
                connection.setRequestProperty("Accept-Encoding", "identity");
                if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
                checkCurrent(stillCurrent);
                if (connection.getResponseCode() != 200) throw new IOException("公开头像资料暂不可用");
                checkCurrent(stillCurrent);
                if (connection.getContentLengthLong() > 128 * 1024) throw new IOException("头像资料超出限制");
                byte[] bytes;
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkCurrent(stillCurrent);
                        if (output.size() + count > 128 * 1024) throw new IOException("头像资料超出限制");
                        output.write(buffer, 0, count);
                    }
                    bytes = output.toByteArray();
                }
                checkCurrent(stillCurrent);
                String avatar = parseAvatar(bytes, connection.getContentType(), uid);
                checkCurrent(stillCurrent);
                return avatar;
            } finally {
                if (registration != null) registration.close();
                if (connection != null) connection.disconnect();
                scope.close();
            }
        };
    }
    static String parseAvatar(byte[] bytes, String contentType, String uid) throws IOException {
        if (!eligible(uid) || bytes == null || bytes.length > 128 * 1024) throw new IOException("公开头像资料无效");
        String type = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        String raw = new String(bytes, type.contains("gbk") || type.contains("gb2312") || type.contains("gb18030")
                ? Charset.forName("GB18030") : StandardCharsets.UTF_8).trim();
        if (raw.startsWith("\uFEFF")) raw = raw.substring(1).trim();
        if (raw.startsWith("/*$js$*/")) raw = raw.substring(8).trim();
        if (!raw.startsWith("{")) throw new IOException("公开头像资料不是数据");
        try {
            JSONTokener tokener = new JSONTokener(quotedControls(raw)); Object decoded = tokener.nextValue();
            if (!(decoded instanceof JSONObject)) throw new IOException("公开头像资料无效");
            char suffix = tokener.nextClean();
            if (suffix != 0 && suffix != ';') throw new IOException("公开头像资料尾部无效");
            if (suffix == ';' && tokener.nextClean() != 0) throw new IOException("公开头像资料尾部无效");
            JSONObject root = (JSONObject) decoded;
            if (root.has("error") || !root.has("data")) throw new IOException("公开头像资料返回错误");
            Object data = root.opt("data");
            if (data instanceof JSONObject && ((JSONObject) data).has("0")) data = ((JSONObject) data).opt("0");
            else if (data instanceof JSONArray) data = ((JSONArray) data).opt(0);
            if (!(data instanceof JSONObject)) throw new IOException("公开头像资料缺少用户");
            JSONObject user = (JSONObject) data;
            if (user.has("error") || !uid.equals(user.optString("uid", ""))) throw new IOException("公开头像资料用户不匹配");
            return AvatarSources.normalize(user.optString("avatar", ""));
        } catch (org.json.JSONException invalid) { throw new IOException("公开头像资料无法解析", invalid); }
    }
    private static String quotedControls(String raw) {
        StringBuilder escaped = new StringBuilder(raw.length()); boolean quoted = false, slash = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quoted && c < 32) {
                escaped.append("\\u00").append(Character.forDigit((c >>> 4) & 15, 16)).append(Character.forDigit(c & 15, 16));
                slash = false; continue;
            }
            escaped.append(c);
            if (slash) { slash = false; continue; }
            if (quoted && c == '\\') slash = true; else if (c == '"') quoted = !quoted;
        }
        return escaped.toString();
    }
}
