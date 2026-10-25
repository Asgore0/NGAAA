package cn.ngapure.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device-local, short-lived recovery of an unfinished authorization; never a login proof. */
public final class NativeQrRecoveryStore {
    static final long WINDOW_MILLIS = 10 * 60 * 1000L;
    static final int MAX_STATE_BYTES = 64 * 1024;
    private static final long CLOCK_TOLERANCE_MILLIS = 60 * 1000L;
    private static final int MAX_CLEAR_BYTES = MAX_STATE_BYTES * 6 + 4096;
    private static final int MAX_BLOB_CHARS = 600000;
    private static final String KEY_ALIAS = "cn.ngapure.app.qr-recovery.v1";
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            "bbs.nga.cn", "ngabbs.com", "nga.178.com"));
    // All app components use one process. Serialize ownership and commits across Store instances.
    private static final Object LOCK = new Object();
    private static boolean cleanupBlocked, cleanupKeyInvalidated;
    private final Storage storage;
    private final KeyProvider keys;
    private final TimeSource time;

    public NativeQrRecoveryStore(Context context) {
        this(new PreferencesStorage(context), new AndroidKeyProvider(), new TimeSource() {
            @Override public long wall() { return System.currentTimeMillis(); }
            @Override public long elapsed() { return SystemClock.elapsedRealtime(); }
        });
    }

    NativeQrRecoveryStore(Storage storage, KeyProvider keys, TimeSource time) {
        if (storage == null || keys == null || time == null)
            throw new IllegalArgumentException("缺少扫码恢复存储配置");
        this.storage = storage; this.keys = keys; this.time = time;
    }

    /** Claims a new owner and removes the previous encrypted state in the same commit. */
    public Lease fresh(String host) throws IOException {
        checkHost(host);
        synchronized (LOCK) {
            long wall = time.wall(), elapsed = time.elapsed();
            checkNow(wall, elapsed);
            Lease lease = new Lease(UUID.randomUUID().toString(), host, wall, elapsed);
            try { storage.write(new Record(lease.id, host, "")); }
            catch (IOException failure) { throw invalidateAfterFailure("扫码恢复记录替换失败"); }
            cleanupBlocked = false; cleanupKeyInvalidated = false;
            return lease;
        }
    }

    /** Consumes ownership, not the recovery time window. Invalid snapshots are removed. */
    public SavedAttempt restore(String host) throws IOException {
        checkHost(host);
        synchronized (LOCK) {
            if (cleanupBlocked) throw new CleanupFailure("扫码恢复清理尚未完成", cleanupKeyInvalidated);
            Record record = storage.read();
            if (empty(record)) return null;
            if (record != null && "".equals(record.blob) && validId(record.ownerId)
                    && host.equals(record.host)) return null;
            Snapshot snapshot;
            Timing timing;
            try {
                snapshot = open(record, host);
                validateTime(snapshot, time.wall(), time.elapsed());
            } catch (IOException invalid) {
                clearRecord();
                return null;
            }
            Lease lease = new Lease(UUID.randomUUID().toString(), host,
                    snapshot.createdWall, snapshot.createdElapsed);
            // Keep saved timestamps too: reading a checkpoint is not a new checkpoint.
            storage.write(new Record(lease.id, host, seal(lease.id, host, snapshot)));
            try { timing = validateTime(snapshot, time.wall(), time.elapsed()); }
            catch (IOException invalidTime) {
                clearRecord();
                return null;
            }
            return new SavedAttempt(lease, snapshot.state, timing.age, timing.remaining);
        }
    }

    /** Does not consume ownership, log errors, or return any part of the encrypted state. */
    public boolean hasPending(String host) {
        if (!HOSTS.contains(host)) return false;
        synchronized (LOCK) {
            if (cleanupBlocked) return false;
            try {
                Record record = storage.read();
                if (empty(record) || (record != null && "".equals(record.blob))) return false;
                validateTime(open(record, host), time.wall(), time.elapsed());
                return true;
            } catch (Exception ignored) { return false; }
        }
    }

    public void clear() throws IOException {
        synchronized (LOCK) { clearRecord(); }
    }

    /** Logout/host-change hook; does not load or change the cookie encryption key. */
    public static void clearAll(Context context) throws IOException {
        new NativeQrRecoveryStore(context).clear();
    }

    public final class Lease implements NativeAuth.Recovery {
        final String id, host;
        final long createdWall, createdElapsed;
        private Lease(String id, String host, long createdWall, long createdElapsed) {
            this.id = id; this.host = host;
            this.createdWall = createdWall; this.createdElapsed = createdElapsed;
        }

        @Override public void checkpoint(String state) throws IOException {
            synchronized (LOCK) {
                if (!owns(storage.read(), this)) return;
                if (cleanupBlocked) throw new CleanupFailure("扫码恢复清理尚未完成", cleanupKeyInvalidated);
                checkState(state);
                long wall = time.wall(), elapsed = time.elapsed();
                Snapshot snapshot = new Snapshot(host, createdWall, createdElapsed, wall, elapsed, state);
                try { validateTime(snapshot, wall, elapsed); }
                catch (IOException invalidTime) {
                    clearRecord();
                    throw invalidTime;
                }
                storage.write(new Record(id, host, seal(id, host, snapshot)));
            }
        }

        /** An old attempt can never clear a newer attempt or a restored owner. */
        @Override public void clear() throws IOException {
            synchronized (LOCK) {
                Record record;
                try { record = storage.read(); }
                catch (IOException unreadable) {
                    // Ownership is unknown: do not delete a key that may belong to a newer lease.
                    cleanupBlocked = true; cleanupKeyInvalidated = false;
                    throw new CleanupFailure("无法确认扫码恢复记录归属，已停止本机恢复", false);
                }
                if (owns(record, this)) clearRecord();
            }
        }

        @Override public String toString() { return "NativeQrRecoveryStore.Lease"; }
    }

    static final class SavedAttempt {
        final Lease lease;
        final String state;
        final long ageMillis, remainingMillis;
        SavedAttempt(Lease lease, String state, long ageMillis, long remainingMillis) {
            this.lease = lease; this.state = state;
            this.ageMillis = ageMillis; this.remainingMillis = remainingMillis;
        }
        @Override public String toString() { return "NativeQrRecoveryStore.SavedAttempt"; }
    }

    interface Storage {
        Record read() throws IOException;
        void write(Record record) throws IOException;
    }
    interface KeyProvider {
        SecretKey get() throws Exception;
        default void invalidate() throws Exception { throw new IOException("恢复密钥失效操作不可用"); }
    }
    interface TimeSource { long wall(); long elapsed(); }
    static final class Record {
        static final Record EMPTY = new Record("", "", "");
        final String ownerId, host, blob;
        Record(String ownerId, String host, String blob) {
            this.ownerId = ownerId; this.host = host; this.blob = blob;
        }
        @Override public String toString() { return "NativeQrRecoveryStore.Record"; }
    }

    private static boolean empty(Record record) {
        return record == null || ("".equals(record.ownerId) && "".equals(record.host) && "".equals(record.blob));
    }
    /** The disk write failed; key deletion is a separate fail-closed action, never a transaction claim. */
    public static final class CleanupFailure extends IOException {
        private static final long serialVersionUID = 1L;
        public final boolean keyInvalidated;
        private CleanupFailure(String reason, boolean keyInvalidated) {
            super(reason + (keyInvalidated ? "；本机恢复密钥已作废，请重试"
                    : "，恢复密钥也未能作废；请停止授权并重试"));
            this.keyInvalidated = keyInvalidated;
        }
    }
    private void clearRecord() throws IOException {
        try { storage.write(Record.EMPTY); }
        catch (IOException failure) { throw invalidateAfterFailure("扫码恢复记录清除失败"); }
        cleanupBlocked = false; cleanupKeyInvalidated = false;
    }
    private CleanupFailure invalidateAfterFailure(String reason) {
        boolean invalidated = false;
        try { keys.invalidate(); invalidated = true; } catch (Exception ignored) { }
        cleanupBlocked = true; cleanupKeyInvalidated = invalidated;
        return new CleanupFailure(reason, invalidated);
    }
    private static boolean owns(Record record, Lease lease) {
        return record != null && lease.id.equals(record.ownerId) && lease.host.equals(record.host);
    }
    private static boolean validId(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    private static void checkHost(String host) throws IOException {
        if (!HOSTS.contains(host)) throw new IOException("不支持的扫码恢复域名");
    }
    private static void checkNow(long wall, long elapsed) throws IOException {
        if (wall <= 0 || elapsed < 0) throw new IOException("设备时间无效，请刷新二维码");
    }
    private static void checkState(String state) throws IOException {
        if (state == null || state.length() > MAX_STATE_BYTES
                || !StandardCharsets.UTF_8.newEncoder().canEncode(state)
                || state.getBytes(StandardCharsets.UTF_8).length > MAX_STATE_BYTES)
            throw new IOException("扫码恢复状态无效或超过限制");
    }
    private static byte[] aad(String owner, String host) {
        return ("nga-qr-recovery:1\u0000" + host + "\u0000" + owner).getBytes(StandardCharsets.UTF_8);
    }

    private String seal(String owner, String host, Snapshot snapshot) throws IOException {
        try {
            JSONObject wrapper = new JSONObject();
            wrapper.put("v", 1); wrapper.put("host", host);
            wrapper.put("createdWall", snapshot.createdWall); wrapper.put("createdElapsed", snapshot.createdElapsed);
            wrapper.put("savedWall", snapshot.savedWall); wrapper.put("savedElapsed", snapshot.savedElapsed);
            wrapper.put("state", snapshot.state);
            byte[] clear = wrapper.toString().getBytes(StandardCharsets.UTF_8);
            if (clear.length > MAX_CLEAR_BYTES) throw new IOException("扫码恢复状态超过限制");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get());
            cipher.updateAAD(aad(owner, host));
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != 12) throw new IOException("无法安全保存扫码恢复状态");
            return "1:" + Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(cipher.doFinal(clear));
        } catch (Exception error) { throw new IOException("无法安全保存扫码恢复状态"); }
    }

    private Snapshot open(Record record, String host) throws IOException {
        try {
            if (record == null || !validId(record.ownerId)
                    || !HOSTS.contains(record.host) || !host.equals(record.host)
                    || record.blob == null || record.blob.length() > MAX_BLOB_CHARS) throw new IOException();
            String[] parts = record.blob.split(":", -1);
            if (parts.length != 3 || !"1".equals(parts[0])) throw new IOException();
            byte[] iv = Base64.getDecoder().decode(parts[1]), encrypted = Base64.getDecoder().decode(parts[2]);
            if (iv.length != 12 || encrypted.length < 16 || encrypted.length > MAX_CLEAR_BYTES + 16) throw new IOException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.get(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(record.ownerId, host));
            byte[] clear = cipher.doFinal(encrypted);
            String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(clear)).toString();
            JSONTokener parser = new JSONTokener(text);
            Object parsed = parser.nextValue();
            if (!(parsed instanceof JSONObject) || parser.nextClean() != 0) throw new IOException();
            JSONObject wrapper = (JSONObject) parsed;
            if (wrapper.length() != 7 || number(wrapper, "v") != 1 || !host.equals(wrapper.opt("host"))
                    || !(wrapper.opt("state") instanceof String)) throw new IOException();
            String state = (String) wrapper.opt("state"); checkState(state);
            return new Snapshot(host, number(wrapper, "createdWall"), number(wrapper, "createdElapsed"),
                    number(wrapper, "savedWall"), number(wrapper, "savedElapsed"), state);
        } catch (Exception error) { throw new IOException("扫码恢复记录已失效，请刷新二维码"); }
    }

    private static long number(JSONObject wrapper, String name) throws IOException {
        Object value = wrapper.opt(name);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IOException();
        return ((Number) value).longValue();
    }
    private static Timing validateTime(Snapshot snapshot, long wall, long elapsed) throws IOException {
        checkNow(wall, elapsed);
        long createdWallAge = age(wall, snapshot.createdWall, false);
        long createdElapsedAge = age(elapsed, snapshot.createdElapsed, true);
        long savedWallAge = age(wall, snapshot.savedWall, false);
        long savedElapsedAge = age(elapsed, snapshot.savedElapsed, true);
        if (snapshot.savedWall < snapshot.createdWall || snapshot.savedElapsed < snapshot.createdElapsed
                || Math.abs(createdWallAge - createdElapsedAge) > CLOCK_TOLERANCE_MILLIS
                || Math.abs(savedWallAge - savedElapsedAge) > CLOCK_TOLERANCE_MILLIS)
            throw new IOException("设备时间已变化，请刷新二维码");
        return new Timing(savedElapsedAge, WINDOW_MILLIS - Math.max(createdWallAge, createdElapsedAge));
    }
    private static long age(long now, long then, boolean allowZero) throws IOException {
        if (then < 0 || (!allowZero && then == 0) || now < then || now - then > WINDOW_MILLIS)
            throw new IOException("本地扫码恢复已过期或设备时间已变化，请刷新二维码");
        return now - then;
    }
    private static final class Snapshot {
        final String host, state;
        final long createdWall, createdElapsed, savedWall, savedElapsed;
        Snapshot(String host, long createdWall, long createdElapsed, long savedWall, long savedElapsed, String state) {
            this.host = host; this.createdWall = createdWall; this.createdElapsed = createdElapsed;
            this.savedWall = savedWall; this.savedElapsed = savedElapsed; this.state = state;
        }
    }
    private static final class Timing {
        final long age, remaining;
        Timing(long age, long remaining) { this.age = age; this.remaining = remaining; }
    }

    private static final class PreferencesStorage implements Storage {
        private final SharedPreferences preferences;
        PreferencesStorage(Context context) {
            if (context == null) throw new IllegalArgumentException("缺少扫码恢复存储上下文");
            preferences = context.getApplicationContext().getSharedPreferences("nga_qr_recovery", Context.MODE_PRIVATE);
        }
        @Override public Record read() throws IOException {
            try { return new Record(preferences.getString("owner", ""), preferences.getString("host", ""),
                    preferences.getString("blob", "")); }
            catch (RuntimeException error) { throw new IOException("无法读取扫码恢复记录"); }
        }
        @Override public void write(Record record) throws IOException {
            Record previous = read();
            try {
                if (put(record)) return;
            } catch (RuntimeException ignored) { /* A failing commit may already have changed memory. */ }
            boolean restored = false;
            try { restored = put(previous); } catch (RuntimeException ignored) { }
            if (!restored) try { put(Record.EMPTY); } catch (RuntimeException ignored) { }
            // No claim of rollback durability: the caller must stop this authorization on failure.
            throw new IOException("扫码恢复记录保存失败，已停止本次授权，请重试");
        }
        private boolean put(Record record) {
            SharedPreferences.Editor editor = preferences.edit();
            if (empty(record)) editor.remove("owner").remove("host").remove("blob");
            else editor.putString("owner", record.ownerId).putString("host", record.host).putString("blob", record.blob);
            return editor.commit();
        }
    }
    private static final class AndroidKeyProvider implements KeyProvider {
        @Override public SecretKey get() throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
            if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build());
            return generator.generateKey();
        }
        @Override public void invalidate() throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
        }
    }
}
