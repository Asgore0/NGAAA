package cn.ngapure.app;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** In-memory synthetic fixtures and real JCE AES-GCM only; no Android account or network access. */
public final class NativeQrRecoveryStoreTest {
    private static final String HOST = "bbs.nga.cn";
    private static final String STATE = "{\"fixture\":\"synthetic-only\",\"qrkey\":\"fixture-qr-key-001\","
            + "\"hiddenkey\":\"fixture-hidden-key-002\",\"cookie\":\"fixture-cookie-value-003\"}";
    private static int scenarios, checks;
    private NativeQrRecoveryStoreTest() { }

    public static void main(String[] args) throws Exception {
        scenario("real AES-GCM opaque checkpoint", NativeQrRecoveryStoreTest::encryption);
        scenario("random IV per checkpoint", NativeQrRecoveryStoreTest::randomIv);
        scenario("ownership across store instances", NativeQrRecoveryStoreTest::ownership);
        scenario("restore invalidates former owner", NativeQrRecoveryStoreTest::restoredOwner);
        scenario("unconditional and conditional clear", NativeQrRecoveryStoreTest::clearing);
        scenario("restore and checkpoint never extend creation window", NativeQrRecoveryStoreTest::window);
        scenario("slow recovery commit does not extend lifetime", NativeQrRecoveryStoreTest::slowRestore);
        scenario("local expiry discards snapshot", NativeQrRecoveryStoreTest::expiry);
        scenario("host allowlist and mismatch", NativeQrRecoveryStoreTest::hosts);
        scenario("wall and elapsed rollback", NativeQrRecoveryStoreTest::rollback);
        scenario("clock divergence and conservative remaining time", NativeQrRecoveryStoreTest::clockSkew);
        scenario("elapsed reboot discontinuity", NativeQrRecoveryStoreTest::reboot);
        scenario("ciphertext and AAD corruption", NativeQrRecoveryStoreTest::corruption);
        scenario("decrypted metadata validation", NativeQrRecoveryStoreTest::metadata);
        scenario("UTF-8 state size and malformed text", NativeQrRecoveryStoreTest::stateLimits);
        scenario("storage failures are never success", NativeQrRecoveryStoreTest::storageErrors);
        scenario("failed clear revokes key and rejects stale disk ciphertext", NativeQrRecoveryStoreTest::failedClear);
        scenario("failed invalidation fences recovery until explicit durable action", NativeQrRecoveryStoreTest::failedInvalidation);
        scenario("key errors fail closed", NativeQrRecoveryStoreTest::keyErrors);
        scenario("pending query is non-consuming", NativeQrRecoveryStoreTest::pending);
        scenario("empty claim and invalid clock boundaries", NativeQrRecoveryStoreTest::boundaries);
        System.out.println("PASS QR authorization recovery store: " + scenarios + " scenarios, " + checks + " checks");
    }

    private static void encryption() throws Exception {
        Harness h = new Harness();
        check(!h.store.hasPending(HOST), "empty store has no pending state");
        NativeQrRecoveryStore.Lease lease = h.store.fresh(HOST);
        check(h.key.calls == 0 && !h.store.hasPending(HOST), "claim does not create or expose a key/state");
        lease.checkpoint(STATE);
        check(h.storage.record.blob.startsWith("1:"), "versioned encrypted record");
        for (String secret : Arrays.asList(STATE, "fixture-qr-key-001", "fixture-hidden-key-002", "fixture-cookie-value-003"))
            check(!h.storage.record.blob.contains(secret), "no fixture secret is stored in plaintext");
        check(h.store.hasPending(HOST), "encrypted checkpoint is valid");
        h.time.advance(12000);
        NativeQrRecoveryStore.SavedAttempt restored = h.store.restore(HOST);
        check(restored != null && STATE.equals(restored.state), "real GCM round trip preserves opaque state");
        check(restored.ageMillis == 12000 && restored.remainingMillis == 588000, "elapsed checkpoint age returned");
        check(!restored.lease.id.equals(lease.id), "restoring claims a new owner");
        check(!lease.toString().contains(lease.id) && !restored.toString().contains(STATE)
                && !h.storage.record.toString().contains(h.storage.record.blob), "model strings do not disclose state");
    }

    private static void randomIv() throws Exception {
        Harness h = ready(); NativeQrRecoveryStore.Record first = h.storage.record;
        h.lease.checkpoint(STATE); NativeQrRecoveryStore.Record second = h.storage.record;
        check(first.ownerId.equals(second.ownerId), "checkpoint keeps owner");
        check(!first.blob.equals(second.blob), "identical plaintext and timestamps have randomized encryption");
        check(!first.blob.split(":")[1].equals(second.blob.split(":")[1]), "provider generates a fresh IV");
    }

    private static void ownership() throws Exception {
        Harness h = ready(); NativeQrRecoveryStore other = h.anotherStore();
        NativeQrRecoveryStore.Lease next = other.fresh(HOST); next.checkpoint("new synthetic state");
        NativeQrRecoveryStore.Record current = h.storage.record; int writes = h.storage.writes;
        h.lease.checkpoint("stale synthetic state"); h.lease.clear();
        check(h.storage.record == current && h.storage.writes == writes, "old owner cannot write or clear new owner");
        NativeQrRecoveryStore.SavedAttempt saved = h.store.restore(HOST);
        check(saved != null && "new synthetic state".equals(saved.state), "instances share one current owner");
        next.clear(); check(h.storage.record.ownerId.equals(saved.lease.id), "former owner cannot clear restored owner");
    }

    private static void restoredOwner() throws Exception {
        Harness h = ready(); NativeQrRecoveryStore.SavedAttempt saved = h.store.restore(HOST);
        check(saved != null && saved.lease.createdWall == h.lease.createdWall
                && saved.lease.createdElapsed == h.lease.createdElapsed, "creation clocks survive ownership rotation");
        NativeQrRecoveryStore.Record current = h.storage.record;
        h.lease.clear(); h.lease.checkpoint("obsolete synthetic fixture");
        check(h.storage.record == current, "restore is a CAS boundary for old lease");
        saved.lease.checkpoint("updated synthetic fixture");
        NativeQrRecoveryStore.SavedAttempt again = h.store.restore(HOST);
        check(again != null && "updated synthetic fixture".equals(again.state), "new lease can checkpoint");
    }

    private static void clearing() throws Exception {
        Harness h = ready(); h.store.clear();
        check(h.storage.record == NativeQrRecoveryStore.Record.EMPTY && !h.store.hasPending(HOST), "store clear removes checkpoint");
        h.lease.checkpoint(STATE); check(h.store.restore(HOST) == null, "cleared lease cannot resurrect a checkpoint");
        NativeQrRecoveryStore.Lease next = h.store.fresh(HOST); next.checkpoint(STATE); next.clear();
        check(!h.store.hasPending(HOST), "current lease can clear its own checkpoint");
        next.clear(); check(h.store.restore(HOST) == null, "conditional clear is idempotent");
    }

    private static void window() throws Exception {
        Harness h = new Harness(); NativeQrRecoveryStore.Lease first = h.store.fresh(HOST);
        h.time.advance(120000); first.checkpoint(STATE); h.time.advance(180000);
        NativeQrRecoveryStore.SavedAttempt a = h.store.restore(HOST);
        check(a != null && a.ageMillis == 180000 && a.remainingMillis == 300000, "window uses creation, age uses checkpoint");
        h.time.advance(60000); NativeQrRecoveryStore.SavedAttempt b = h.store.restore(HOST);
        check(b != null && b.ageMillis == 240000 && b.remainingMillis == 240000, "repeat restore does not reset saved time");
        check(b.lease.createdWall == first.createdWall && b.lease.createdElapsed == first.createdElapsed, "repeat restore preserves creation");
        b.lease.checkpoint(STATE); NativeQrRecoveryStore.SavedAttempt c = h.store.restore(HOST);
        check(c != null && c.ageMillis == 0 && c.remainingMillis == 240000, "new checkpoint resets only checkpoint age");
    }

    private static void expiry() throws Exception {
        Harness h = ready(); h.time.advance(NativeQrRecoveryStore.WINDOW_MILLIS + 1);
        check(!h.store.hasPending(HOST), "expired pending query returns false");
        check(h.store.restore(HOST) == null && h.storage.record == NativeQrRecoveryStore.Record.EMPTY, "expired restore clears record");
        Harness recent = ready(); recent.time.advance(590000); recent.lease.checkpoint(STATE); recent.time.advance(10001);
        check(recent.store.restore(HOST) == null, "recent checkpoint cannot extend ten-minute creation window");
        Harness active = ready(); active.time.advance(600001);
        expectIo(() -> active.lease.checkpoint(STATE));
        check(active.storage.record == NativeQrRecoveryStore.Record.EMPTY, "expired current lease cannot refresh its window");
    }

    private static void slowRestore() throws Exception {
        Harness h = ready(); h.storage.afterWrite = () -> h.time.advance(5000);
        NativeQrRecoveryStore.SavedAttempt saved = h.store.restore(HOST);
        check(saved != null && saved.ageMillis == 5000 && saved.remainingMillis == 595000,
                "returned elapsed age includes recovery encryption/commit time");
        Harness expiring = ready(); expiring.time.advance(599000);
        expiring.storage.afterWrite = () -> expiring.time.advance(2000);
        check(expiring.store.restore(HOST) == null, "restore crossing lifetime boundary returns no attempt");
        check(expiring.storage.record == NativeQrRecoveryStore.Record.EMPTY, "expired during recovery is cleared");
    }

    private static void hosts() throws Exception {
        for (String host : Arrays.asList(HOST, "ngabbs.com", "nga.178.com")) {
            Harness h = new Harness(); h.store.fresh(host).checkpoint(STATE);
            NativeQrRecoveryStore.SavedAttempt saved = h.store.restore(host);
            check(saved != null && host.equals(saved.lease.host), "all three exact official hosts supported");
        }
        Harness h = ready(); check(!h.store.hasPending("ngabbs.com"), "wrong-host presence does not expose state");
        check(h.store.restore("ngabbs.com") == null && h.storage.record == NativeQrRecoveryStore.Record.EMPTY,
                "wrong-host restore clears pending only");
        for (String host : Arrays.asList("evil.invalid", "https://bbs.nga.cn", "BBS.NGA.CN", "bbs.nga.cn\n")) {
            expectIo(() -> h.store.fresh(host)); expectIo(() -> h.store.restore(host));
            check(!h.store.hasPending(host), "invalid host presence fails closed");
        }
    }

    private static void rollback() throws Exception {
        Harness wall = ready(); wall.time.wall--;
        check(wall.store.restore(HOST) == null, "wall clock earlier than saved/creation rejected");
        Harness elapsed = ready(); elapsed.time.elapsed--;
        check(elapsed.store.restore(HOST) == null, "elapsed clock rollback rejected");
        Harness negative = ready(); negative.time.elapsed = -1;
        check(negative.store.restore(HOST) == null, "negative elapsed rejected");
    }

    private static void clockSkew() throws Exception {
        Harness h = ready(); h.time.advance(1000); h.time.wall += 60001;
        check(h.store.restore(HOST) == null, "clock age divergence over one minute rejected");
        Harness tolerated = ready(); tolerated.time.advance(1000); tolerated.time.wall += 60000;
        NativeQrRecoveryStore.SavedAttempt saved = tolerated.store.restore(HOST);
        check(saved != null && saved.ageMillis == 1000 && saved.remainingMillis == 539000,
                "one-minute tolerance does not expand remaining time");
        Harness checkpoint = ready(); checkpoint.time.advance(1000); checkpoint.time.wall += 60001;
        expectIo(() -> checkpoint.lease.checkpoint(STATE));
        check(!checkpoint.store.hasPending(HOST), "bad-clock checkpoint cannot mask creation clock divergence");
    }

    private static void reboot() throws Exception {
        Harness h = ready(); h.time.wall += 10000; h.time.elapsed = 10;
        check(h.store.restore(HOST) == null && h.storage.record == NativeQrRecoveryStore.Record.EMPTY,
                "reset elapsed clock invalidates old encrypted state");
    }

    private static void corruption() throws Exception {
        for (int kind = 0; kind < 8; kind++) {
            Harness h = ready(); NativeQrRecoveryStore.Record original = h.storage.record;
            String owner = original.ownerId, host = original.host, blob = original.blob;
            switch (kind) {
                case 0: blob = "2:" + blob.substring(2); break;
                case 1: blob = "1:invalid-base64:" + blob.split(":")[2]; break;
                case 2: {
                    String[] parts = blob.split(":"); byte[] bytes = Base64.getDecoder().decode(parts[2]); bytes[0] ^= 1;
                    blob = parts[0] + ":" + parts[1] + ":" + Base64.getEncoder().encodeToString(bytes); break;
                }
                case 3: owner = UUID.randomUUID().toString(); break;
                case 4: host = "ngabbs.com"; break;
                case 5: blob = null; break;
                case 6: blob = "1:AA==:AA=="; break;
                default: owner = "not-a-valid-owner"; break;
            }
            h.storage.record = new NativeQrRecoveryStore.Record(owner, host, blob);
            check(h.store.restore(host) == null, "corrupt cipher, owner or host does not restore");
            check(h.storage.record == NativeQrRecoveryStore.Record.EMPTY, "corrupt record removed");
        }
    }

    private static void metadata() throws Exception {
        for (int kind = 0; kind < 9; kind++) {
            Harness h = ready(); JSONObject wrapper = wrapper(h, STATE);
            switch (kind) {
                case 0: wrapper.put("v", 2); break;
                case 1: wrapper.put("createdWall", Long.toString(h.time.wall)); break;
                case 2: wrapper.put("state", 1); break;
                case 3: wrapper.put("extra", true); break;
                case 4: wrapper.put("host", "ngabbs.com"); break;
                case 5: wrapper.put("savedWall", h.time.wall + 1); break;
                case 6: wrapper.put("savedElapsed", h.time.elapsed + 1); break;
                case 7: wrapper.put("savedWall", h.time.wall - 1); break;
                default: wrapper.put("createdElapsed", -1); break;
            }
            h.storage.record = forged(h, wrapper.toString());
            check(h.store.restore(HOST) == null, "authenticated but invalid wrapper rejected");
            check(h.storage.record == NativeQrRecoveryStore.Record.EMPTY, "invalid decrypted metadata removed");
        }
        Harness trailing = ready(); trailing.storage.record = forged(trailing, wrapper(trailing, STATE) + "x");
        check(trailing.store.restore(HOST) == null, "trailing text is not accepted as wrapper");
    }

    private static void stateLimits() throws Exception {
        Harness h = ready(); String limit = repeat('x', NativeQrRecoveryStore.MAX_STATE_BYTES);
        h.lease.checkpoint(limit); NativeQrRecoveryStore.SavedAttempt saved = h.store.restore(HOST);
        check(saved != null && saved.state.equals(limit), "exact UTF-8 byte limit supported");
        NativeQrRecoveryStore.Record original = h.storage.record;
        expectIo(() -> saved.lease.checkpoint(limit + "x"));
        expectIo(() -> saved.lease.checkpoint(repeat('中', 22000)));
        expectIo(() -> saved.lease.checkpoint(null));
        expectIo(() -> saved.lease.checkpoint("\uD800"));
        check(h.storage.record == original, "invalid state never replaces previous checkpoint");
        saved.lease.checkpoint(repeat('中', 20000));
        check(h.store.restore(HOST) != null, "valid multibyte UTF-8 state retained");
    }

    private static void storageErrors() throws Exception {
        Harness h = ready(); NativeQrRecoveryStore.Record previous = h.storage.record;
        h.storage.failWrite = true; expectIo(() -> h.store.fresh(HOST));
        check(h.storage.record == previous, "failed fresh never returns a lease or destroys fixture state");
        expectIo(() -> h.lease.checkpoint("new synthetic fixture"));
        expectIo(() -> h.store.restore(HOST)); expectIo(h.store::clear);
        check(h.storage.record == previous, "failed commit is not reported as success");
        h.storage.failWrite = false; h.storage.failRead = true;
        check(!h.store.hasPending(HOST), "read failure gives safe false presence");
        expectIo(() -> h.store.restore(HOST)); expectIo(() -> h.lease.checkpoint(STATE));
        h.storage.failRead = false;
        expectIo(() -> h.store.restore(HOST));
        h.store.clear();
        Harness readOnly = ready(); readOnly.storage.failRead = true;
        expectIo(() -> readOnly.store.restore(HOST)); readOnly.storage.failRead = false;
        check(readOnly.store.restore(HOST) != null, "plain read errors did not overwrite encrypted fixture");
        Harness invalid = ready(); invalid.storage.record = new NativeQrRecoveryStore.Record("invalid", HOST, "damaged");
        invalid.storage.failWrite = true; expectIo(() -> invalid.store.restore(HOST));
        check(!invalid.store.hasPending(HOST), "failed cleanup never turns damaged state into pending");
        invalid.storage.failWrite = false; invalid.store.clear();
    }

    private static void failedClear() throws Exception {
        Harness h = ready(); NativeQrRecoveryStore.Record oldDisk = h.storage.record;
        int reads = h.key.calls; h.storage.failWrite = true;
        IOException error = expectIo(h.lease::clear);
        check(error instanceof NativeQrRecoveryStore.CleanupFailure
                && ((NativeQrRecoveryStore.CleanupFailure) error).keyInvalidated, "failed clear exposes independent successful key revocation");
        check(h.key.invalidations == 1 && h.key.calls == reads, "revocation never reads or creates key material");
        check(!h.store.hasPending(HOST), "failed disk clear fences pending query");
        expectIo(() -> h.anotherStore().restore(HOST));
        h.storage.failWrite = false; h.store.clear();
        // Simulate stale disk surviving a prior process, independently of the cleared process fence.
        h.storage.record = oldDisk;
        check(h.anotherStore().restore(HOST) == null, "old disk ciphertext cannot authenticate with replacement QR key");
        Harness stale = ready(); NativeQrRecoveryStore.Lease newer = stale.store.fresh(HOST); newer.checkpoint(STATE);
        stale.storage.failWrite = true; stale.lease.clear();
        check(stale.key.invalidations == 0, "stale lease never revokes newer owner's key");
        stale.storage.failWrite = false; stale.store.clear();
        Harness refresh = ready(); refresh.storage.failWrite = true;
        IOException replacing = expectIo(() -> refresh.store.fresh(HOST));
        check(replacing instanceof NativeQrRecoveryStore.CleanupFailure
                && ((NativeQrRecoveryStore.CleanupFailure) replacing).keyInvalidated, "failed explicit refresh revokes old QR key");
        refresh.storage.failWrite = false; refresh.store.fresh(HOST);
        check(!refresh.store.hasPending(HOST), "durable fresh claim resets failure fence without old state");
    }

    private static void failedInvalidation() throws Exception {
        Harness h = ready(); h.storage.failWrite = true; h.key.failInvalidate = true;
        IOException error = expectIo(h.store::clear);
        check(error instanceof NativeQrRecoveryStore.CleanupFailure
                && !((NativeQrRecoveryStore.CleanupFailure) error).keyInvalidated, "failed key deletion does not claim permanent revocation");
        check(!h.store.hasPending(HOST), "dual storage/key failure fences current process");
        expectIo(() -> h.anotherStore().restore(HOST)); expectIo(() -> h.lease.checkpoint(STATE));
        h.storage.failWrite = false;
        h.store.clear(); check(!h.store.hasPending(HOST), "successful explicit clear removes failure fence");
        NativeQrRecoveryStore.Lease fresh = h.store.fresh(HOST); fresh.checkpoint(STATE);
        check(h.store.restore(HOST) != null, "new durable claim may continue after explicit recovery");
        Harness unreadable = ready(); unreadable.storage.failRead = true;
        IOException unknownOwner = expectIo(unreadable.lease::clear);
        check(unknownOwner instanceof NativeQrRecoveryStore.CleanupFailure
                && !((NativeQrRecoveryStore.CleanupFailure) unknownOwner).keyInvalidated,
                "unknown-owner clear exposes conservative failure flag");
        check(unreadable.key.invalidations == 0, "unknown-owner clear never revokes another possible lease key");
        check(!unreadable.store.hasPending(HOST), "unknown-owner cleanup failure fences recovery");
        unreadable.storage.failRead = false; expectIo(() -> unreadable.store.restore(HOST));
        unreadable.store.clear();
    }

    private static void keyErrors() throws Exception {
        Harness h = ready(); h.key.fail = true;
        check(!h.store.hasPending(HOST), "unavailable key gives no pending claim");
        check(h.store.restore(HOST) == null && h.storage.record == NativeQrRecoveryStore.Record.EMPTY,
                "undecryptable record discarded");
        Harness wrong = ready(); wrong.key.changed = true;
        check(wrong.store.restore(HOST) == null, "replacement key cannot authenticate previous checkpoint");
        Harness writing = new Harness(); NativeQrRecoveryStore.Lease lease = writing.store.fresh(HOST); writing.key.fail = true;
        IOException error = expectIo(() -> lease.checkpoint(STATE));
        check(error.getMessage() != null && !error.getMessage().contains(STATE), "key failure message contains no state");
        check(!writing.store.hasPending(HOST), "failed encryption never creates plaintext fallback");
    }

    private static void pending() throws Exception {
        Harness h = ready(); NativeQrRecoveryStore.Record record = h.storage.record; int writes = h.storage.writes;
        check(h.store.hasPending(HOST) && h.store.hasPending(HOST), "pending may be queried repeatedly");
        check(record == h.storage.record && writes == h.storage.writes, "pending query does not rotate or rewrite owner");
        check(!h.store.hasPending(null) && !h.store.hasPending("invalid"), "invalid presence host safe");
        h.lease.checkpoint(""); NativeQrRecoveryStore.SavedAttempt saved = h.store.restore(HOST);
        check(saved != null && saved.state.isEmpty(), "store treats state as opaque even when empty");
    }

    private static void boundaries() throws Exception {
        Harness claim = new Harness(); NativeQrRecoveryStore.Lease lease = claim.store.fresh(HOST);
        NativeQrRecoveryStore.Record record = claim.storage.record;
        check(claim.store.restore(HOST) == null && claim.storage.record == record, "unfinished claim is not consumed by restore");
        lease.checkpoint(STATE); check(claim.store.hasPending(HOST), "claim can checkpoint after an empty query");
        Harness wrong = new Harness(); wrong.store.fresh(HOST);
        check(wrong.store.restore("ngabbs.com") == null && wrong.storage.record == NativeQrRecoveryStore.Record.EMPTY,
                "wrong-host empty claim removed");
        Harness zero = new Harness(); zero.time.wall = 0; expectIo(() -> zero.store.fresh(HOST));
        zero.time.wall = 1; zero.time.elapsed = -1; expectIo(() -> zero.store.fresh(HOST));
        Harness exact = ready(); exact.time.advance(NativeQrRecoveryStore.WINDOW_MILLIS);
        NativeQrRecoveryStore.SavedAttempt saved = exact.store.restore(HOST);
        check(saved != null && saved.remainingMillis == 0, "inclusive ten-minute boundary cannot extend retention");
    }

    private static JSONObject wrapper(Harness h, String state) throws Exception {
        JSONObject wrapper = new JSONObject(); wrapper.put("v", 1); wrapper.put("host", HOST);
        wrapper.put("createdWall", h.lease.createdWall); wrapper.put("createdElapsed", h.lease.createdElapsed);
        wrapper.put("savedWall", h.time.wall); wrapper.put("savedElapsed", h.time.elapsed); wrapper.put("state", state);
        return wrapper;
    }
    private static NativeQrRecoveryStore.Record forged(Harness h, String clear) throws Exception {
        NativeQrRecoveryStore.Record record = h.storage.record;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, h.key.get());
        cipher.updateAAD(("nga-qr-recovery:1\u0000" + record.host + "\u0000" + record.ownerId).getBytes(StandardCharsets.UTF_8));
        String blob = "1:" + Base64.getEncoder().encodeToString(cipher.getIV()) + ":"
                + Base64.getEncoder().encodeToString(cipher.doFinal(clear.getBytes(StandardCharsets.UTF_8)));
        return new NativeQrRecoveryStore.Record(record.ownerId, record.host, blob);
    }
    private static String repeat(char character, int count) {
        char[] text = new char[count]; Arrays.fill(text, character); return new String(text);
    }
    private static Harness ready() throws Exception {
        Harness h = new Harness(); h.lease = h.store.fresh(HOST); h.lease.checkpoint(STATE); return h;
    }
    private static void scenario(String label, Action action) throws Exception {
        scenarios++;
        try { action.run(); } catch (AssertionError failure) { throw new AssertionError(label + ": " + failure.getMessage(), failure); }
    }
    private static void check(boolean value, String label) {
        checks++; if (!value) throw new AssertionError(label);
    }
    private static IOException expectIo(Action action) throws Exception {
        checks++;
        try { action.run(); } catch (IOException expected) { return expected; }
        throw new AssertionError("Expected readable IOException");
    }
    private interface Action { void run() throws Exception; }
    private static final class Harness {
        final MemoryStorage storage = new MemoryStorage();
        final FixtureKey key = new FixtureKey();
        final TestTime time = new TestTime();
        final NativeQrRecoveryStore store = new NativeQrRecoveryStore(storage, key, time);
        NativeQrRecoveryStore.Lease lease;
        NativeQrRecoveryStore anotherStore() { return new NativeQrRecoveryStore(storage, key, time); }
    }
    private static final class MemoryStorage implements NativeQrRecoveryStore.Storage {
        NativeQrRecoveryStore.Record record = NativeQrRecoveryStore.Record.EMPTY;
        boolean failRead, failWrite;
        int writes;
        Runnable afterWrite;
        @Override public NativeQrRecoveryStore.Record read() throws IOException {
            if (failRead) throw new IOException("合成存储读取失败"); return record;
        }
        @Override public void write(NativeQrRecoveryStore.Record value) throws IOException {
            if (failWrite) throw new IOException("合成存储写入失败"); record = value; writes++;
            if (afterWrite != null) afterWrite.run();
        }
    }
    private static final class FixtureKey implements NativeQrRecoveryStore.KeyProvider {
        boolean fail, changed, failInvalidate;
        int calls, invalidations, version;
        @Override public SecretKey get() throws Exception {
            calls++; if (fail) throw new IOException("合成密钥失败");
            byte[] bytes = new byte[16]; Arrays.fill(bytes, (byte) (37 + version + (changed ? 54 : 0)));
            return new SecretKeySpec(bytes, "AES");
        }
        @Override public void invalidate() throws Exception {
            invalidations++;
            if (failInvalidate) throw new IOException("合成密钥作废失败");
            version++; changed = false;
        }
    }
    private static final class TestTime implements NativeQrRecoveryStore.TimeSource {
        long wall = 1700000000000L, elapsed = 100000L;
        @Override public long wall() { return wall; }
        @Override public long elapsed() { return elapsed; }
        void advance(long duration) { wall += duration; elapsed += duration; }
    }
}
