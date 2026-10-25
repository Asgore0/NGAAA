package cn.ngapure.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Synthetic values only. Real Keystore integration is tested by the own-UID device probe. */
public final class SessionScopeSnapshotTest {
    private static int assertions;
    private static final String HOST = "bbs.nga.cn";
    private static final String A = "ngaPassportUid=101; ngaPassportCid=synthetic-token-a";
    private static final String A2 = "ngaPassportUid=101; ngaPassportCid=synthetic-token-renewed";
    private static final String B = "ngaPassportUid=202; ngaPassportCid=synthetic-token-b";
    private static void ok(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    private static String legacy(String host, String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest((host + "\0" + value).getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte part : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", part & 255));
        return result.toString();
    }
    private static final class Source implements SessionScopeSnapshot.Source {
        String host = HOST, encoded = "synthetic-encrypted-a", cookie = A, account = "101", resolvedHost;
        int reads; Runnable afterResolve, afterKey; boolean fail;
        public SessionScopeSnapshot.Key key() {
            SessionScopeSnapshot.Key value = new SessionScopeSnapshot.Key(host, encoded);
            Runnable hook = afterKey; afterKey = null; if (hook != null) hook.run(); return value;
        }
        public SessionScopeSnapshot resolve(SessionScopeSnapshot.Key captured) {
            reads++;
            if (fail) throw new IllegalStateException("Synthetic resolve failure");
            SessionScopeSnapshot value = SessionScopeSnapshot.derive(resolvedHost == null ? captured.host : resolvedHost, cookie, account);
            Runnable hook = afterResolve; afterResolve = null; if (hook != null) hook.run();
            return value;
        }
    }
    private static void stableAndChanges() throws Exception {
        SessionScopeSnapshot.Cache cache = new SessionScopeSnapshot.Cache(); Source source = new Source();
        SessionScopeSnapshot a = cache.capture(source);
        ok(a.contentScope.equals(legacy(HOST, A)) && a.localDataScope.equals(legacy(HOST, "101")), "Both scope digests preserve the exact legacy contract");
        ok(a.loggedIn, "Authenticated snapshot has the login marker");
        boolean same = true;
        for (int i = 0; i < 1000; i++) same &= cache.capture(source) == a;
        ok(same, "A thousand warm lookups share only the immutable derived snapshot");
        ok(source.reads == 1, "A thousand stable ownership checks resolve credentials once");
        source.encoded = "synthetic-encrypted-a-renewed"; source.cookie = A2;
        SessionScopeSnapshot renewed = cache.capture(source);
        ok(!renewed.contentScope.equals(a.contentScope) && renewed.localDataScope.equals(a.localDataScope), "Same account refreshed token invalidates content but preserves local scope");
        source.encoded = "synthetic-encrypted-b"; source.cookie = B; source.account = "202";
        SessionScopeSnapshot b = cache.capture(source);
        ok(!b.contentScope.equals(a.contentScope) && !b.localDataScope.equals(a.localDataScope), "New account invalidates both scopes");
        source.host = "ngabbs.com";
        SessionScopeSnapshot host = cache.capture(source);
        ok(!host.contentScope.equals(b.contentScope) && !host.localDataScope.equals(b.localDataScope), "Changing host invalidates both scopes synchronously");
        source.host = HOST; source.encoded = "synthetic-encrypted-a"; source.cookie = A; source.account = "101";
        ok(cache.capture(source).contentScope.equals(a.contentScope) && source.reads == 5, "A to B to A resolves the returning source again");
        source.encoded = ""; source.cookie = ""; source.account = "";
        SessionScopeSnapshot anonymous = cache.capture(source);
        ok(!anonymous.loggedIn && anonymous.contentScope.equals(anonymous.localDataScope), "Logout yields anonymous ownership");
        int reads = source.reads; ok(cache.capture(source) == anonymous && source.reads == reads, "Anonymous lookup is cacheable");
        cache.clear(); ok(cache.capture(source) != anonymous && source.reads == reads + 1, "Lifecycle clear forces source resolution");
    }
    private static void failuresAndRaces() {
        SessionScopeSnapshot.Cache cache = new SessionScopeSnapshot.Cache(); Source source = new Source();
        source.cookie = ""; source.account = "";
        SessionScopeSnapshot failed = cache.capture(source); cache.capture(source);
        ok(!failed.loggedIn && source.reads == 2, "Nonempty ciphertext with failed decryption is never cached");
        source.cookie = A; source.account = "101";
        SessionScopeSnapshot recovered = cache.capture(source);
        ok(recovered.loggedIn && source.reads == 3, "The identical encrypted source can recover without a preference event");
        source.encoded = "synthetic-new-source"; source.afterResolve = () -> source.host = "ngabbs.com";
        SessionScopeSnapshot raced = cache.capture(source);
        SessionScopeSnapshot after = cache.capture(source);
        ok(raced == after && source.reads == 5 && raced.contentScope.equals(SessionScopeSnapshot.derive("ngabbs.com", A, "101").contentScope), "Host change during a miss retries before returning current ownership");
        cache.clear(); source.host = HOST; source.resolvedHost = "nga.178.com";
        for (int i = 0; i < 2; i++) try { cache.capture(source); throw new AssertionError("Unstable resolution accepted"); }
        catch (IllegalStateException expected) { ok("登录状态持续变化，请重试".equals(expected.getMessage()), "Unstable resolution fails explicitly without credentials"); }
        ok(source.reads == 11, "Resolved host differing from authoritative source exhausts bounded retries without caching");
        source.resolvedHost = null; source.encoded = "";
        cache.capture(source); cache.capture(source);
        ok(source.reads == 13, "A resolver claiming credentials with an empty authoritative source cannot poison the anonymous cache");
        source.encoded = "synthetic-recovery"; cache.capture(source);
        source.encoded = "synthetic-exception"; source.fail = true;
        try { cache.capture(source); throw new AssertionError("Resolver exception hidden"); }
        catch (IllegalStateException expected) { ok(true, "Exceptional resolution does not reuse previous ownership"); }
        source.fail = false; source.encoded = "synthetic-recovery";
        int reads = source.reads; cache.capture(source);
        ok(source.reads == reads + 1, "A failed miss dropped the previous key rather than retaining stale cache state");
    }
    private static void authorityAndBounds() {
        SessionScopeSnapshot.Cache cache = new SessionScopeSnapshot.Cache(); Source source = new Source();
        SessionScopeSnapshot a = cache.capture(source);
        // LoginPreferences' failed-write overlay continues to supply A even when Android's raw map has B.
        ok(cache.capture(source) == a && source.reads == 1, "Unchanged authoritative rollback source preserves old ownership");
        source.encoded = "synthetic-successful-b"; source.cookie = B; source.account = "202";
        ok(!cache.capture(source).contentScope.equals(a.contentScope), "A later successful authoritative update invalidates immediately");
        char[] oversized = new char[128 * 1024 + 1]; java.util.Arrays.fill(oversized, 'x'); source.encoded = new String(oversized);
        int reads = source.reads; cache.capture(source); cache.capture(source);
        ok(source.reads == reads + 2, "Unexpected oversized encrypted source is not retained");
    }
    private static void freshCredentialPriming() throws Exception {
        SessionScopeSnapshot.Cache cache = new SessionScopeSnapshot.Cache(); Source source = new Source();
        SessionScopeSnapshot.Key before = source.key();
        SessionScopeSnapshot freshlyRead = SessionScopeSnapshot.derive(HOST, A, "101");
        cache.seed(before, source.key(), freshlyRead);
        ok(cache.capture(source) == freshlyRead && source.reads == 0,
                "A fresh exact credential read primes UI ownership without a second credential resolution");
        ok(freshlyRead.contentScope.equals(legacy(HOST, A)) && freshlyRead.localDataScope.equals(legacy(HOST, "101")),
                "Primed ownership preserves both legacy digests");
        source.encoded = "synthetic-encrypted-a-renewed"; source.cookie = A2;
        SessionScopeSnapshot renewed = cache.capture(source);
        ok(source.reads == 1 && !renewed.contentScope.equals(freshlyRead.contentScope)
                && renewed.localDataScope.equals(freshlyRead.localDataScope),
                "Token renewal invalidates a primed content owner while keeping the same account owner");
        before = source.key(); source.host = "ngabbs.com";
        cache.seed(before, source.key(), renewed);
        int reads = source.reads; SessionScopeSnapshot moved = cache.capture(source);
        ok(source.reads == reads + 1 && !moved.contentScope.equals(renewed.contentScope),
                "Host changes during a fresh credential read cannot prime the new host with an old snapshot");
        before = source.key(); source.encoded = "synthetic-encrypted-b"; source.cookie = B; source.account = "202";
        cache.seed(before, source.key(), moved);
        reads = source.reads; SessionScopeSnapshot b = cache.capture(source);
        ok(source.reads == reads + 1 && !b.localDataScope.equals(moved.localDataScope),
                "Account replacement during a read cannot prime old ownership under the new source");
        // A failed durable write still presents B through LoginPreferences' authoritative overlay.
        cache.seed(source.key(), source.key(), b);
        ok(cache.capture(source) == b && source.reads == reads + 1,
                "A failed-write overlay remains authoritative when priming ownership");
        SessionScopeSnapshot failed = SessionScopeSnapshot.derive(source.host, "", "");
        cache.seed(source.key(), source.key(), failed);
        source.cookie = ""; source.account = ""; reads = source.reads;
        ok(!cache.capture(source).loggedIn && !cache.capture(source).loggedIn && source.reads == reads + 2,
                "A failed fresh Keystore read drops prior success and is retried for unchanged ciphertext");
        source.cookie = B; source.account = "202";
        ok(cache.capture(source).loggedIn && source.reads == reads + 3,
                "The same source recovers after a failed priming attempt without a preference event");
        source.encoded = ""; source.cookie = ""; source.account = "";
        SessionScopeSnapshot anonymous = SessionScopeSnapshot.derive(source.host, "", "");
        cache.seed(source.key(), source.key(), anonymous); reads = source.reads;
        ok(cache.capture(source) == anonymous && !anonymous.loggedIn && source.reads == reads,
                "A verified empty authoritative source primes anonymous ownership without credential work");
        cache.clear(); cache.capture(source);
        ok(source.reads == reads + 1, "Background lifecycle clearing also clears fresh-read priming");
        source.encoded = "synthetic-encrypted-b"; source.cookie = B; source.account = "202";
        before = source.key();
        cache.seed(before, before, SessionScopeSnapshot.derive("nga.178.com", B, "202"));
        reads = source.reads; cache.capture(source);
        ok(source.reads == reads + 1, "A resolved host that differs from the authoritative source never primes");
        char[] oversized = new char[128 * 1024 + 1]; java.util.Arrays.fill(oversized, 'x'); source.encoded = new String(oversized);
        cache.seed(source.key(), source.key(), SessionScopeSnapshot.derive(source.host, B, "202"));
        reads = source.reads; cache.capture(source); cache.capture(source);
        ok(source.reads == reads + 2, "Fresh reads retain neither oversized encrypted source nor its derived ownership");
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Concurrency latch timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError("Unexpected interrupt"); }
    }
    private static void concurrentReadsAndClear() throws Exception {
        SessionScopeSnapshot.Cache cache = new SessionScopeSnapshot.Cache(); Source source = new Source();
        SessionScopeSnapshot warm = cache.capture(source);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FutureTask<Void> background = new FutureTask<>(() -> {
            SessionScopeSnapshot.Cache.Ticket ticket = cache.beginRead(); SessionScopeSnapshot.Key key = source.key();
            entered.countDown(); await(release); cache.seed(ticket, key, key, SessionScopeSnapshot.derive(HOST, A, "101")); return null;
        });
        new Thread(background, "synthetic-fresh-credential-read").start(); await(entered);
        FutureTask<SessionScopeSnapshot> hit = new FutureTask<>(() -> cache.capture(source)); new Thread(hit, "synthetic-ui-hit").start();
        try { ok(hit.get(2, TimeUnit.SECONDS) == warm, "A blocked fresh read cannot block a warm ownership lookup"); }
        finally { release.countDown(); }
        background.get(5, TimeUnit.SECONDS);

        cache.clear(); CountDownLatch resolving = new CountDownLatch(1), finish = new CountDownLatch(1);
        source.afterResolve = () -> { resolving.countDown(); await(finish); };
        FutureTask<SessionScopeSnapshot> miss = new FutureTask<>(() -> cache.capture(source)); new Thread(miss, "synthetic-scope-miss").start(); await(resolving);
        FutureTask<Void> clearing = new FutureTask<>(() -> { cache.clear(); return null; }); new Thread(clearing, "synthetic-background-clear").start();
        try { clearing.get(2, TimeUnit.SECONDS); ok(true, "Lifecycle clear does not wait for blocked scope resolution"); }
        finally { finish.countDown(); }
        miss.get(5, TimeUnit.SECONDS); int reads = source.reads; cache.capture(source);
        ok(source.reads == reads + 1, "A completion from before clear cannot repopulate the ownership cache");

        SessionScopeSnapshot.Key key = source.key(); SessionScopeSnapshot.Cache.Ticket older = cache.beginRead();
        SessionScopeSnapshot.Cache.Ticket newer = cache.beginRead(); SessionScopeSnapshot newest = SessionScopeSnapshot.derive(HOST, A, "101");
        cache.seed(newer, key, key, newest); cache.seed(older, key, key, SessionScopeSnapshot.derive(HOST, "", ""));
        ok(cache.capture(source) == newest, "An older failed credential read cannot erase a newer successful ownership read");
        older = cache.beginRead(); newer = cache.beginRead();
        cache.seed(older, key, key, SessionScopeSnapshot.derive(HOST, "", ""));
        reads = source.reads; cache.capture(source);
        ok(source.reads == reads + 1, "A known failed read drops an earlier success even when a later read is still pending");
        cache.clear(); older = cache.beginRead(); cache.clear(); cache.seed(older, key, key, newest);
        reads = source.reads; cache.capture(source);
        ok(source.reads == reads + 1, "A fresh-read seed ticket also respects lifecycle invalidation");

        cache.clear(); CountDownLatch captured = new CountDownLatch(1), sourceRelease = new CountDownLatch(1);
        source.afterKey = () -> { captured.countDown(); await(sourceRelease); };
        FutureTask<SessionScopeSnapshot> oldSource = new FutureTask<>(() -> cache.capture(source));
        new Thread(oldSource, "synthetic-key-before-clear").start(); await(captured);
        cache.clear(); source.encoded = "synthetic-new-after-clear"; source.cookie = B; source.account = "202";
        SessionScopeSnapshot afterClear = cache.capture(source);
        sourceRelease.countDown(); oldSource.get(5, TimeUnit.SECONDS);
        ok(cache.capture(source) == afterClear, "A source captured before clear cannot evict a later published snapshot");
        reads = source.reads; cache.capture(source);
        ok(source.reads == reads, "Old-epoch retry does not invalidate the current cache ticket");
    }
    public static void main(String[] args) throws Exception {
        stableAndChanges(); failuresAndRaces(); authorityAndBounds(); freshCredentialPriming(); concurrentReadsAndClear();
        System.out.println("SessionScopeSnapshot: " + assertions + " assertions PASS; synthetic source; no Android/network/credentials");
    }
}
