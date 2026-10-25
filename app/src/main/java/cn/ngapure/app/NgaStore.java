package cn.ngapure.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Local settings. Login cookies are encrypted by a device-bound Android Keystore key. */
public final class NgaStore {
    private static final String KEY_ALIAS = "cn.ngapure.app.cookies.v1";
    private static final Object FAVORITE_LOCK = new Object();
    private static final String FAVORITE_SYNC = "favorite_forum_sync_v1";
    private static final AtomicLong COOKIE_READ_CALLS = new AtomicLong();
    private static final AtomicLong COOKIE_DECODE_CALLS = new AtomicLong();
    private static final AtomicLong COOKIE_DECODE_FAILURES = new AtomicLong();
    private static final AtomicLong COOKIE_MAIN_DECODE_CALLS = new AtomicLong();
    private static final AtomicLong COOKIE_WORKER_DECODE_CALLS = new AtomicLong();
    private static final AtomicLong COOKIE_DECODE_NANOS = new AtomicLong();
    private static final AtomicLong COOKIE_MAIN_DECODE_NANOS = new AtomicLong();
    private static final AtomicLong COOKIE_WORKER_DECODE_NANOS = new AtomicLong();
    private static final SessionLockCounters SESSION_GLOBAL = new SessionLockCounters();
    private static final SessionLockCounters SESSION_MAIN = new SessionLockCounters();
    private static final SessionLockCounters SESSION_WORKER = new SessionLockCounters();
    private static final AtomicLong SCOPE_HITS = new AtomicLong(), SCOPE_MISSES = new AtomicLong(), SESSION_RETRIES = new AtomicLong();
    private static final class SessionLockCounters {
        final AtomicLong calls = new AtomicLong(), wait = new AtomicLong(), hold = new AtomicLong(), maximum = new AtomicLong();
        void record(long waited, long held) {
            calls.incrementAndGet(); wait.addAndGet(waited); hold.addAndGet(held);
            long old; do { old = maximum.get(); if (old >= waited) break; } while (!maximum.compareAndSet(old, waited));
        }
        String fields(String prefix) { return prefix + "AcquireCalls=" + calls.get() + " " + prefix + "WaitNs=" + wait.get()
                + " " + prefix + "HoldNs=" + hold.get() + " " + prefix + "MaxWaitNs=" + maximum.get(); }
    }
    public static final Set<String> HOSTS = java.util.Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("bbs.nga.cn", "ngabbs.com", "nga.178.com")));
    private final SharedPreferences preferences;
    private final Context application;
    private final SessionScopeSnapshot.Cache scopeCache = new SessionScopeSnapshot.Cache();
    private final SessionScopeSnapshot.Source scopeSource = new SessionScopeSnapshot.Source() {
        public SessionScopeSnapshot.Key key() {
            return readScopeKey();
        }
        public SessionScopeSnapshot resolve(SessionScopeSnapshot.Key captured) {
            String header = decodeCookie(captured.encryptedCookie);
            return SessionScopeSnapshot.derive(captured.host, header, LoginPreferences.identity(header));
        }
        public void lookup(boolean hit) { (hit ? SCOPE_HITS : SCOPE_MISSES).incrementAndGet(); }
        public void retry() { SESSION_RETRIES.incrementAndGet(); }
    };

    public NgaStore(Context context) {
        application = context.getApplicationContext();
        preferences = application.getSharedPreferences("nga_local", Context.MODE_PRIVATE);
    }

    public String host() {
        String value = preferences.getString("host", "bbs.nga.cn");
        return HOSTS.contains(value) ? value : "bbs.nga.cn";
    }

    public void setHost(String value) {
        if (!HOSTS.contains(value)) throw new IllegalArgumentException("不支持的 NGA 域名");
        if (value.equals(host())) return;
        clearPendingAuthorization();
        synchronized (LoginPreferences.class) { preferences.edit().putString("host", value).apply(); }
    }

    public String theme() { return preferences.getString("theme", "light"); }
    public void setTheme(String value) {
        if (!"light".equals(value) && !"dark".equals(value) && !"system".equals(value)) return;
        preferences.edit().putString("theme", value).apply();
    }
    public int fontSize() { return preferences.getInt("font_size", 16); }
    public void setFontSize(int value) { preferences.edit().putInt("font_size", Math.max(12, Math.min(26, value))).apply(); }
    public boolean glassEnabled() { return preferences.getBoolean("liquid_design", true); }
    public void setGlassEnabled(boolean enabled) { preferences.edit().putBoolean("liquid_design", enabled).apply(); }
    public boolean topicPreviewsEnabled() { return preferences.getBoolean("topic_previews", true); }
    public void setTopicPreviewsEnabled(boolean enabled) { preferences.edit().putBoolean("topic_previews", enabled).apply(); }
    public boolean motionEnabled() { return preferences.getBoolean("motion_effect", true); }
    public void setMotionEnabled(boolean enabled) { preferences.edit().putBoolean("motion_effect", enabled).apply(); }
    public String lastCheckInAttempt() { return LoginPreferences.checkInAttempt(preferences); }
    public void recordCheckInAttempt(String source) { LoginPreferences.recordCheckInAttempt(preferences, formatCheckInAttempt(source)); }
    private static String formatCheckInAttempt(String source) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.SIMPLIFIED_CHINESE);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return source + " · " + format.format(new Date());
    }
    static final class CheckInSession {
        final String host,cookie,account;
        CheckInSession(String host,String cookie,String account){this.host=host;this.cookie=cookie;this.account=account;}
        @Override public String toString(){return "NgaStore.CheckInSession";}
    }
    CheckInSession captureCheckInSession() {
        SessionScopeSnapshot.Cache.Ticket ticket = scopeCache.beginRead();
        for (int attempt = 0; attempt < 3; attempt++) {
            SessionScopeSnapshot.Key before = readScopeKey();
            String header = decodeCookie(before.encryptedCookie);
            CheckInSession session = new CheckInSession(before.host, header, LoginPreferences.identity(header));
            SessionScopeSnapshot derived = SessionScopeSnapshot.derive(session.host, session.cookie, session.account);
            SessionScopeSnapshot.Key after = readScopeKey();
            if (before.same(after)) {
                scopeCache.seed(ticket, before, after, derived);
                return session;
            }
            SESSION_RETRIES.incrementAndGet();
        }
        scopeCache.discard(ticket);
        throw new IllegalStateException("登录状态持续变化，请重试");
    }
    SessionScopeSnapshot captureScopeSnapshot() { return scopeCache.capture(scopeSource); }
    void clearScopeSnapshot() { scopeCache.clear(); }
    public synchronized boolean recordCheckInAttemptForAccount(String expected,String source) {
        synchronized(LoginPreferences.class){return LoginPreferences.recordCheckInAttemptForAccount(preferences,expected,cookie(),formatCheckInAttempt(source));}
    }
    public synchronized boolean saveCheckInResultForAccount(String expected,boolean confirmed,String message) {
        synchronized(LoginPreferences.class){return LoginPreferences.saveCheckInResultForAccount(preferences,expected,cookie(),confirmed,message,today());}
    }
    public boolean autoCheckIn() { return preferences.getBoolean("auto_check_in", false); }
    public void setAutoCheckIn(boolean value) { preferences.edit().putBoolean("auto_check_in", value).apply(); }

    public String cookie() {
        for (int attempt = 0; attempt < 3; attempt++) {
            SessionScopeSnapshot.Key before = readScopeKey();
            String header = decodeCookie(before.encryptedCookie);
            if (before.same(readScopeKey())) return header;
            SESSION_RETRIES.incrementAndGet();
        }
        throw new IllegalStateException("登录状态持续变化，请重试");
    }

    /** Only authoritative source bytes are copied under the account lock; crypto runs after release. */
    private SessionScopeSnapshot.Key readScopeKey() {
        boolean main = mainThread(); long waiting = System.nanoTime(), acquired = 0, held = 0;
        try {
            synchronized (LoginPreferences.class) {
                acquired = System.nanoTime();
                try { return new SessionScopeSnapshot.Key(host(), LoginPreferences.cookie(preferences)); }
                finally { held = System.nanoTime() - acquired; }
            }
        } finally {
            if (acquired != 0) { long wait = acquired - waiting; SESSION_GLOBAL.record(wait, held); (main ? SESSION_MAIN : SESSION_WORKER).record(wait, held); }
        }
    }
    private static boolean mainThread() {
        try { android.os.Looper main = android.os.Looper.getMainLooper(); return main != null && main == android.os.Looper.myLooper(); }
        catch (RuntimeException unavailable) { return false; } // Diagnostics must not prevent a credential read.
    }
    private String decodeCookie(String encoded) {
        COOKIE_READ_CALLS.incrementAndGet();
        if (encoded.isEmpty()) return "";
        boolean main = mainThread();
        COOKIE_DECODE_CALLS.incrementAndGet();
        (main ? COOKIE_MAIN_DECODE_CALLS : COOKIE_WORKER_DECODE_CALLS).incrementAndGet();
        long started = System.nanoTime();
        boolean decoded = false;
        try {
            String[] parts = encoded.split(":", 2);
            if (parts.length != 2) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            cipher.updateAAD(KEY_ALIAS.getBytes(StandardCharsets.UTF_8));
            String header = new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
            decoded = true;
            return header;
        } catch (Exception ignored) {
            // A device restore may not restore its Keystore key. Require login again.
            return "";
        } finally {
            long elapsed = System.nanoTime() - started;
            COOKIE_DECODE_NANOS.addAndGet(elapsed);
            (main ? COOKIE_MAIN_DECODE_NANOS : COOKIE_WORKER_DECODE_NANOS).addAndGet(elapsed);
            if (!decoded) COOKIE_DECODE_FAILURES.incrementAndGet();
        }
    }

    /** Process counters only. Decode includes Keystore/Base64 and its key-monitor wait, not source capture/revalidation. */
    public static String sessionReadDiagnostics() {
        return "readCalls=" + COOKIE_READ_CALLS.get() + " decodeCalls=" + COOKIE_DECODE_CALLS.get()
                + " decodeFailures=" + COOKIE_DECODE_FAILURES.get()
                + " mainDecodeCalls=" + COOKIE_MAIN_DECODE_CALLS.get() + " workerDecodeCalls=" + COOKIE_WORKER_DECODE_CALLS.get()
                + " decodeNs=" + COOKIE_DECODE_NANOS.get()
                + " mainDecodeNs=" + COOKIE_MAIN_DECODE_NANOS.get() + " workerDecodeNs=" + COOKIE_WORKER_DECODE_NANOS.get();
    }
    /** Source capture/revalidation only, not all account writes or every monitor in the process. */
    public static String sessionLockDiagnostics() {
        return "schema=1 " + SESSION_GLOBAL.fields("global") + " " + SESSION_MAIN.fields("main") + " " + SESSION_WORKER.fields("worker")
                + " scopeHits=" + SCOPE_HITS.get() + " scopeMisses=" + SCOPE_MISSES.get() + " retries=" + SESSION_RETRIES.get();
    }

    public synchronized void setCookie(String value) {
        if (value == null || value.trim().isEmpty()) { clearLogin(); return; }
        if (value.length() > 65536 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("无效 Cookie");
        StringBuilder clean = new StringBuilder();
        Set<String> allowed = new HashSet<>(Arrays.asList("ngaPassportUid", "ngaPassportCid", "ngaPassportUrlencodedUname", "access_uid", "access_token"));
        for (String item : value.split(";")) {
            int equals = item.indexOf('=');
            if (equals <= 0) continue;
            String name = item.substring(0, equals).trim();
            String content = item.substring(equals + 1).trim();
            if (!allowed.contains(name) || content.isEmpty()) continue;
            if (clean.length() > 0) clean.append("; ");
            clean.append(name).append('=').append(content);
        }
        if (LoginPreferences.identity(clean.toString()).isEmpty()) throw new IllegalArgumentException("没有找到有效的 NGA 登录信息");
        synchronized (LoginPreferences.class) {
            String previousCookie=cookie();
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, secretKey());
                cipher.updateAAD(KEY_ALIAS.getBytes(StandardCharsets.UTF_8));
                String encrypted = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
                        Base64.encodeToString(cipher.doFinal(clean.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
                LoginPreferences.replaceAccount(preferences, encrypted, previousCookie, clean.toString());
                clearScopeSnapshot();
            } catch (Exception error) { throw new IllegalStateException("无法安全保存登录信息", error); }
        }
    }

    private static SecretKey secretKey() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        // Read the authoritative alias every time; no key handle or key bytes are cached.
        // Existing-key reads do not queue behind each other on this process monitor.
        java.security.Key key = keys.getKey(KEY_ALIAS, null);
        if (key != null) return (SecretKey) key;
        synchronized (NgaStore.class) {
            // Only first creation is serialized. Another caller may have created it
            // since the first lookup; an error or a non-key alias must not replace it.
            key = keys.getKey(KEY_ALIAS, null);
            if (key != null) return (SecretKey) key;
            if (keys.containsAlias(KEY_ALIAS)) throw new java.security.KeyStoreException("Cookie key unavailable");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build());
            return generator.generateKey();
        }
    }

    public boolean loggedIn() {
        return captureScopeSnapshot().loggedIn;
    }
    /** Used only for native own-post controls, never exported to diagnostic output. */
    public String accountId() { return LoginPreferences.identity(cookie()); }
    public void clearLogin() {
        clearPendingAuthorization();
        LoginPreferences.clear(preferences);
        clearScopeSnapshot();
    }

    private void clearPendingAuthorization() {
        try { NativeQrRecoveryStore.clearAll(application); }
        catch (java.io.IOException unavailable) { throw new IllegalStateException("无法清除待确认登录，请稍后重试", unavailable); }
    }

    public Set<String> favoriteForums() {
        return new HashSet<>(preferences.getStringSet("favorite_forums", new HashSet<>()));
    }
    public synchronized void toggleFavorite(String fid) {
        synchronized (FAVORITE_LOCK) {
        Set<String> values = favoriteForums();
        if (!values.remove(fid)) values.add(fid);
        preferences.edit().putStringSet("favorite_forums", values)
                .putLong("favorite_forum_revision", favoriteForumRevision() + 1).apply();
        }
    }
    public long favoriteForumRevision() { return preferences.getLong("favorite_forum_revision", 0); }
    private static boolean forumKey(String value) { return ForumFavoriteSyncState.validKey(value); }
    private JSONObject forumMetadata() {
        try { return new JSONObject(preferences.getString("favorite_forum_metadata", "{}")); }
        catch (Exception unavailable) { return new JSONObject(); }
    }
    public String favoriteForumName(String key) { JSONObject row = forumMetadata().optJSONObject(key); return row == null ? "" : row.optString("name", ""); }
    public String favoriteForumIcon(String key) { JSONObject row = forumMetadata().optJSONObject(key); return row == null ? "" : row.optString("icon", ""); }
    /** Independent immutable names/icons, decoded once for a complete favorite render pass. */
    public static final class FavoriteForumMetadata {
        public final String name, icon;
        private FavoriteForumMetadata(String name, String icon) { this.name = name; this.icon = icon; }
    }
    public java.util.Map<String, FavoriteForumMetadata> favoriteForumMetadataSnapshot() {
        JSONObject metadata = forumMetadata();
        java.util.Map<String, FavoriteForumMetadata> result = new java.util.HashMap<>();
        java.util.Iterator<String> keys = metadata.keys();
        while (keys.hasNext()) {
            String key = keys.next(); JSONObject row = metadata.optJSONObject(key);
            if (row != null) result.put(key, new FavoriteForumMetadata(row.optString("name", ""), row.optString("icon", "")));
        }
        return java.util.Collections.unmodifiableMap(result);
    }
    public synchronized void rememberFavoriteForum(String key, String name, String icon) {
        synchronized (FAVORITE_LOCK) {
        if (!forumKey(key)) return;
        try {
            JSONObject metadata = forumMetadata(), row = new JSONObject();
            row.put("name", name == null ? "" : name.substring(0, Math.min(name.length(), 160)));
            row.put("icon", icon == null ? "" : icon.substring(0, Math.min(icon.length(), 2048)));
            metadata.put(key, row); preferences.edit().putString("favorite_forum_metadata", metadata.toString()).apply();
        } catch (Exception unavailable) { /* The readable key remains available when optional metadata cannot be saved. */ }
        }
    }
    /** Import only into this device. A concurrent local edit invalidates the whole result. */
    public synchronized int importFavoriteForums(java.util.Map<String, String[]> entries, long expectedRevision) {
        synchronized (FAVORITE_LOCK) {
        if (favoriteForumRevision() != expectedRevision) return -1;
        Set<String> values = favoriteForums(); JSONObject metadata = forumMetadata(); int added = 0;
        try {
            for (java.util.Map.Entry<String, String[]> entry : entries.entrySet()) {
                if (!forumKey(entry.getKey()) || entry.getValue() == null || entry.getValue().length < 2) continue;
                if (values.add(entry.getKey())) added++;
                JSONObject row = new JSONObject(); row.put("name", entry.getValue()[0]); row.put("icon", entry.getValue()[1]); metadata.put(entry.getKey(), row);
            }
            preferences.edit().putStringSet("favorite_forums", values).putString("favorite_forum_metadata", metadata.toString())
                    .putLong("favorite_forum_revision", expectedRevision + 1).apply();
            return added;
        } catch (Exception unavailable) { return -1; }
        }
    }

    private ForumFavoriteSyncState favoriteSyncState() {
        return ForumFavoriteSyncState.decode(preferences.getString(FAVORITE_SYNC, "{}"));
    }
    /** Bind remote projection to a stable hash of host and account ID, without implicitly uploading legacy favorites. */
    public void activateFavoriteScope(String scope) {
        synchronized (FAVORITE_LOCK) {
            ForumFavoriteSyncState state = favoriteSyncState();
            if (state.initialized() && state.activeScope().equals(scope)) return;
            state.activate(scope, favoriteForums());
            persistFavoriteSync(state, favoriteSnapshotMetadata(state, java.util.Collections.emptyMap()));
        }
    }
    /** Persist the latest explicit desired state before starting a network request. */
    public long queueFavorite(String scope, String key, String name, String icon, boolean desired) {
        synchronized (FAVORITE_LOCK) {
            ForumFavoriteSyncState state = favoriteSyncState();
            long revision = state.queue(scope, key, name, icon, desired, favoriteForums());
            JSONObject metadata = forumMetadata();
            favoriteMetadata(metadata, key, name, icon);
            persistFavoriteSync(state, metadata);
            return revision;
        }
    }
    public List<ForumFavoriteSyncState.Change> pendingFavoriteChanges(String scope) {
        synchronized (FAVORITE_LOCK) { return favoriteSyncState().pending(scope); }
    }
    /** Only a matching, independently read remote state can acknowledge the still-current pending revision. */
    public boolean confirmFavorite(String scope, String key, long revision, boolean desired,
                                   java.util.Map<String, String[]> serverSnapshot) {
        synchronized (FAVORITE_LOCK) {
            if (!validFavoriteSnapshot(serverSnapshot)) return false;
            ForumFavoriteSyncState state = favoriteSyncState();
            if (!state.confirm(scope, key, revision, desired, serverSnapshot.keySet(), favoriteForums())) return false;
            JSONObject metadata = favoriteSnapshotMetadata(state, serverSnapshot);
            persistFavoriteSync(state, metadata);
            return true;
        }
    }
    /** Download replaces this account's known cloud keys, while preserving local-only and queued intent. */
    public int mergeFavoriteForums(String scope, java.util.Map<String, String[]> serverSnapshot) {
        synchronized (FAVORITE_LOCK) {
            if (!validFavoriteSnapshot(serverSnapshot)) return -1;
            ForumFavoriteSyncState state = favoriteSyncState(); Set<String> before = favoriteForums();
            if (!state.merge(scope, serverSnapshot.keySet(), before)) return -1;
            JSONObject metadata = favoriteSnapshotMetadata(state, serverSnapshot);
            Set<String> after = state.projection(); int added = 0;
            for (String key : after) if (!before.contains(key)) added++;
            persistFavoriteSync(state, metadata);
            return added;
        }
    }
    private static boolean validFavoriteSnapshot(java.util.Map<String, String[]> entries) {
        if (entries == null || entries.size() > 2048) return false;
        for (java.util.Map.Entry<String, String[]> row : entries.entrySet()) {
            if (!forumKey(row.getKey()) || row.getValue() == null || row.getValue().length < 2) return false;
        }
        return true;
    }
    private JSONObject favoriteSnapshotMetadata(ForumFavoriteSyncState state, java.util.Map<String, String[]> entries) {
        JSONObject metadata = forumMetadata();
        for (java.util.Map.Entry<String, String[]> row : entries.entrySet())
            favoriteMetadata(metadata, row.getKey(), row.getValue()[0], row.getValue()[1]);
        // A late cloud name/icon must not erase the latest locally queued metadata.
        for (ForumFavoriteSyncState.Change change : state.pending(state.activeScope()))
            favoriteMetadata(metadata, change.key, change.name, change.icon);
        return metadata;
    }
    private static void favoriteMetadata(JSONObject metadata, String key, String name, String icon) {
        try {
            JSONObject row = metadata.optJSONObject(key);
            if (row == null) row = new JSONObject();
            if (name != null && !name.isEmpty()) row.put("name", name.substring(0, Math.min(name.length(), 160)));
            if (icon != null && !icon.isEmpty()) row.put("icon", icon.substring(0, Math.min(icon.length(), 2048)));
            metadata.put(key, row);
        } catch (Exception unavailable) { throw new IllegalStateException("Cannot save favorite metadata", unavailable); }
    }
    private void persistFavoriteSync(ForumFavoriteSyncState state, JSONObject metadata) {
        java.util.Map<String, ?> before = preferences.getAll();
        if (!preferences.edit().putString(FAVORITE_SYNC, state.encode()).putStringSet("favorite_forums", state.projection())
                .putString("favorite_forum_metadata", metadata.toString())
                .putLong("favorite_forum_revision", favoriteForumRevision() + 1).commit()) {
            // Android updates the live preference map even when its disk commit fails.
            // Roll it back too, so a later retry worker cannot send an unpersisted intent.
            restoreFavoritePreferences(before);
            throw new IllegalStateException("无法保存收藏同步状态，请重试");
        }
    }
    @SuppressWarnings("unchecked")
    private void restoreFavoritePreferences(java.util.Map<String, ?> before) {
        SharedPreferences.Editor rollback = preferences.edit();
        for (String key : new String[]{FAVORITE_SYNC, "favorite_forums", "favorite_forum_metadata", "favorite_forum_revision"}) {
            Object value = before.get(key);
            if (value instanceof String) rollback.putString(key, (String) value);
            else if (value instanceof Set) rollback.putStringSet(key, new HashSet<>((Set<String>) value));
            else if (value instanceof Long) rollback.putLong(key, (Long) value);
            else rollback.remove(key);
        }
        // A second disk failure still updates the Android live map to the last known state.
        rollback.commit();
    }

    public boolean historyEnabled() { return preferences.getBoolean("history_enabled", true); }
    public void setHistoryEnabled(boolean enabled) { preferences.edit().putBoolean("history_enabled", enabled).apply(); }
    public List<NgaApi.Topic> history() { return readTopics("history"); }
    public void recordHistory(NgaApi.Topic topic) { if (historyEnabled()) saveFirst("history", topic, 100); }
    public synchronized void removeHistory(String tid) { removeLocalTopic("history", tid, 100); }
    public synchronized void clearHistory() { preferences.edit().remove("history").apply(); }
    public synchronized void removeSavedTopic(String tid) { removeLocalTopic("saved_topics", tid, 300); }
    public synchronized void clearSavedTopics() { preferences.edit().remove("saved_topics").apply(); }
    private void removeLocalTopic(String key, String tid, int limit) {
        List<NgaApi.Topic> topics=readTopics(key);topics.removeIf(t -> t.tid.equals(tid));writeTopics(key,topics,limit);
    }
    public List<NgaApi.Topic> savedTopics() { return readTopics("saved_topics"); }
    public boolean isTopicSaved(String tid) {
        for (NgaApi.Topic topic : savedTopics()) if (topic.tid.equals(tid)) return true;
        return false;
    }
    public synchronized void toggleTopic(NgaApi.Topic topic) {
        List<NgaApi.Topic> topics = savedTopics();
        boolean removed = topics.removeIf(t -> t.tid.equals(topic.tid));
        if (!removed) topics.add(0, topic);
        writeTopics("saved_topics", topics, 300);
    }
    private synchronized void saveFirst(String key, NgaApi.Topic topic, int limit) {
        List<NgaApi.Topic> topics = readTopics(key);
        topics.removeIf(t -> t.tid.equals(topic.tid));
        topics.add(0, topic);
        writeTopics(key, topics, limit);
    }
    private List<NgaApi.Topic> readTopics(String key) {
        List<NgaApi.Topic> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(key, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject value = array.optJSONObject(i);
                if (value == null) continue;
                NgaApi.Topic topic = new NgaApi.Topic();
                topic.tid = value.optString("tid"); topic.title = value.optString("title");
                topic.author = value.optString("author"); topic.fid = value.optString("fid");
                topic.forum = value.optString("forum"); topic.time = value.optString("time");
                topic.replies = Math.max(0, value.optInt("replies"));
                topic.avatar = AvatarSources.normalize(value.optString("avatar", ""));
                String authorId = value.optString("authorId", "");
                topic.authorId = authorId.matches("[1-9][0-9]{0,9}") ? authorId : "";
                topic.votes = readStoredVotes(value.optJSONObject("votes"));
                if (topic.tid.matches("[0-9]+")) result.add(topic);
            }
        } catch (Exception ignored) { }
        return result;
    }
    private void writeTopics(String key, List<NgaApi.Topic> topics, int limit) {
        JSONArray array = new JSONArray();
        for (int i = 0; i < Math.min(limit, topics.size()); i++) {
            NgaApi.Topic topic = topics.get(i);
            try {
                JSONObject value = new JSONObject();
                value.put("tid", topic.tid); value.put("title", topic.title); value.put("author", topic.author);
                value.put("fid", topic.fid); value.put("forum", topic.forum); value.put("time", topic.time);
                value.put("replies", topic.replies);
                value.put("avatar", AvatarSources.normalize(topic.avatar));
                value.put("authorId", topic.authorId != null && topic.authorId.matches("[1-9][0-9]{0,9}") ? topic.authorId : "");
                value.put("votes", writeStoredVotes(topic.votes));
                array.put(value);
            } catch (Exception ignored) { }
        }
        preferences.edit().putString(key, array.toString()).apply();
    }
    /** Local snapshots retain whether a count was known; old records never invent zero likes. */
    private static VoteMetrics readStoredVotes(JSONObject value) {
        VoteMetrics result = new VoteMetrics();
        if (value == null) return result;
        Integer likes = storedVote(value, "likes", false);
        Integer dislikes = storedVote(value, "dislikes", false);
        Integer score = storedVote(value, "score", true);
        if (Boolean.TRUE.equals(value.opt("likes_known")) && likes != null) {
            result.likesKnown = true; result.likes = likes;
        }
        if (Boolean.TRUE.equals(value.opt("dislikes_known")) && dislikes != null) {
            result.dislikesKnown = true; result.dislikes = dislikes;
        }
        if (Boolean.TRUE.equals(value.opt("score_known")) && score != null) {
            result.scoreKnown = true; result.score = score;
        }
        return result;
    }
    private static Integer storedVote(JSONObject value, String key, boolean signed) {
        Object raw = value.opt(key);
        if (!(raw instanceof Number)) return null;
        String number = String.valueOf(raw);
        if (!number.matches(signed ? "-?[0-9]{1,10}" : "[0-9]{1,10}")) return null;
        try { return Integer.valueOf(number); }
        catch (NumberFormatException invalid) { return null; }
    }
    private static JSONObject writeStoredVotes(VoteMetrics votes) throws org.json.JSONException {
        JSONObject value = new JSONObject();
        if (votes == null) return value;
        if (votes.likesKnown && votes.likes >= 0) {
            value.put("likes_known", true); value.put("likes", votes.likes);
        }
        if (votes.dislikesKnown && votes.dislikes >= 0) {
            value.put("dislikes_known", true); value.put("dislikes", votes.dislikes);
        }
        if (votes.scoreKnown) {
            value.put("score_known", true); value.put("score", votes.score);
        }
        return value;
    }
    public String checkInMessage() { String value=LoginPreferences.checkInMessage(preferences); return value.startsWith("签到成功")?"签到成功":value; }
    public String lastCheckInDay() { return LoginPreferences.checkInDay(preferences); }
    public void saveCheckInResult(boolean confirmed, String message) {
        LoginPreferences.saveCheckInResult(preferences, confirmed, message, today());
    }
    public static String today() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(new Date());
    }
}
