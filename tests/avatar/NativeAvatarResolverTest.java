package cn.ngapure.app;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deterministic public-avatar parsing/cache/lifecycle boundaries, with no real network. */
public final class NativeAvatarResolverTest {
    private static int checks;
    private NativeAvatarResolverTest() { }
    private static void check(boolean condition, String name) { checks++; if (!condition) throw new AssertionError(name); }
    private static final class Delivery implements Executor {
        final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
        @Override public void execute(Runnable action) { queue.add(action); }
        void one() throws Exception { Runnable action = queue.poll(3, TimeUnit.SECONDS); check(action != null, "asynchronous callback was posted"); action.run(); }
    }
    private static final class Result implements NativeAvatarResolver.Callback {
        String avatar;
        int resolved, failures;
        boolean busy;
        @Override public void resolved(String value) { resolved++; avatar = value; }
        @Override public void failed(boolean value) { failures++; busy = value; }
    }
    private static NativeAvatarResolver resolver(NativeAvatarResolver.Source source, Delivery delivery, long[] now) {
        return new NativeAvatarResolver(source, delivery, () -> now[0], new ThreadPoolExecutor(2, 2, 1L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(24)));
    }
    private static String parse(String raw, String uid) throws IOException {
        return NativeAvatarResolver.parseAvatar(raw.getBytes(StandardCharsets.UTF_8), "application/json;charset=UTF-8", uid);
    }
    private static void parsing() throws Exception {
        for (String uid : new String[]{"1", "70001", "2147483647"}) check(NativeAvatarResolver.eligible(uid), "positive public UID accepted");
        for (String uid : new String[]{"0", "-1", "-70001", "", "01", "+1", " 1", "1.0", "1&uid=2", "2147483648", "99999999999999999", "anonymous", null}) check(!NativeAvatarResolver.eligible(uid), "anonymous or invalid UID rejected");
        check(parse("{\"data\":{\"0\":{\"uid\":70001,\"avatar\":\".a/70001_1.png\"}}}", "70001").endsWith("/70001_1.png"), "official object wrapper returns normalized metadata");
        check(parse("{\"data\":[{\"uid\":\"70001\",\"avatar\":\"42.gif\"}]}", "70001").endsWith("/face/42.gif"), "official array wrapper");
        check(parse("/*$js$*/{\"data\":{\"uid\":\"70001\",\"avatar\":\"42.gif\",\"sign\":\"literal\nnew line\"}};", "70001").endsWith("/face/42.gif"), "official marker and raw quoted controls are data only");
        check(parse("{\"data\":{\"uid\":\"70001\",\"avatar\":{\"0\":\".a/70001_1.png\",\"l\":1}}}", "70001").endsWith("/70001_1.png"), "multi avatar object survives response extraction");
        check(parse("{\"data\":{\"uid\":\"70001\",\"avatar\":\"\"}}", "70001").isEmpty(), "missing public avatar stays empty");
        check(parse("{\"data\":{\"uid\":\"70001\",\"avatar\":\"https://attacker.invalid/a.png\"}}", "70001").isEmpty(), "offsite avatar cannot escape CDN allowlist");
        for (String bad : new String[]{"{\"data\":{\"uid\":\"70002\",\"avatar\":\"42.gif\"}}", "{\"data\":{\"0\":{\"uid\":70002,\"avatar\":\"42.gif\"}}}", "{\"error\":\"denied\",\"data\":{\"uid\":70001}}", "{\"data\":{\"uid\":70001,\"error\":\"denied\"}}", "{\"data\":[]}", "{\"data\":\"login needed\"}", "<script>steal()</script>", "{broken", "{\"data\":{\"uid\":70001}};steal()", "{\"data\":{\"uid\":70001}}{}"}) {
            boolean rejected = false; try { parse(bad, "70001"); } catch (IOException invalid) { rejected = true; }
            check(rejected, "wrong identity/error/malformed/HTML/expression rejected");
        }
        boolean tooLarge = false; try { NativeAvatarResolver.parseAvatar(new byte[128 * 1024 + 1], "application/json", "70001"); } catch (IOException invalid) { tooLarge = true; }
        check(tooLarge, "metadata response is bounded");
    }
    private static void cacheAndIdentity() throws Exception {
        AtomicInteger requests = new AtomicInteger(); Delivery delivery = new Delivery(); long[] now = {100};
        try (NativeAvatarResolver resolver = resolver((uid, cancellation) -> { requests.incrementAndGet(); return uid.equals("70001") ? "42.gif" : ""; }, delivery, now)) {
            Result first = new Result(); resolver.load("70001", first); delivery.one();
            check(first.resolved == 1 && first.avatar.endsWith("/face/42.gif") && requests.get() == 1, "first public lookup resolves real metadata");
            Result cached = new Result(); resolver.load("70001", cached);
            check(cached.resolved == 0, "cache callback is asynchronous"); delivery.one();
            check(cached.avatar.equals(first.avatar) && requests.get() == 1, "same UID reuses cache");
            resolver.remember("70003", ".a/70003_9.png");
            Result knownFromPost = new Result(); resolver.load("70003", knownFromPost); delivery.one();
            check(knownFromPost.avatar.endsWith("/70003_9.png") && requests.get() == 1, "real existing reply metadata populates list cache without a profile request");
            resolver.remember("70003", "https://attacker.invalid/a.png");
            resolver.remember("70003", "");
            Result existingNotErased = new Result(); resolver.load("70003", existingNotErased); delivery.one();
            check(existingNotErased.avatar.equals(knownFromPost.avatar) && requests.get() == 1, "empty or unsafe metadata cannot erase a known real avatar");
            resolver.remember("-70003", "42.gif");
            Result anonymousNotCached = new Result(); resolver.load("-70003", anonymousNotCached); delivery.one();
            check(anonymousNotCached.avatar.isEmpty(), "same-response cache never invents an anonymous author identity");
            Result empty = new Result(); resolver.load("70002", empty); delivery.one();
            check(empty.resolved == 1 && empty.avatar.isEmpty(), "real absent avatar remains absent");
            Result absentCache = new Result(); resolver.load("70002", absentCache); delivery.one();
            check(requests.get() == 2, "negative cache suppresses duplicate public requests");
            now[0] += 120001; resolver.load("70002", new Result()); delivery.one();
            check(requests.get() == 3, "short absent-avatar TTL can discover a new avatar later");
            resolver.load("70001", new Result()); delivery.one(); check(requests.get() == 3, "positive TTL is longer than absent TTL");
            for (String uid : new String[]{"-70001", "0", "anonymous", "1&uid=2", null}) { Result invalid = new Result(); resolver.load(uid, invalid); delivery.one(); check(invalid.avatar.isEmpty(), "invalid identity gets only placeholder"); }
            check(requests.get() == 3, "anonymous/non-positive identities make zero source calls");
            now[0] += 6 * 60 * 60 * 1000L + 1; resolver.load("70001", new Result()); delivery.one();
            check(requests.get() == 4, "positive cache also eventually refreshes");
        }
        Delivery failedDelivery = new Delivery(); AtomicInteger failures = new AtomicInteger();
        try (NativeAvatarResolver resolver = resolver((uid, token) -> { failures.incrementAndGet(); throw new IOException("offline"); }, failedDelivery, now)) {
            Result failed = new Result(); resolver.load("70001", failed); failedDelivery.one(); check(failed.failures == 1 && !failed.busy, "source error remains retryable failure");
            resolver.load("70001", new Result()); failedDelivery.one(); check(failures.get() == 1, "transient-failure cache prevents scroll retry storm");
            now[0] += 10001; resolver.load("70001", new Result()); failedDelivery.one(); check(failures.get() == 2, "transient failure expires quickly");
        }
    }
    private static void dedupeAndCancel() throws Exception {
        Delivery delivery = new Delivery(); long[] now = {1}; CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        NativeAvatarResolver.Source source = (uid, token) -> {
            requests.incrementAndGet(); started.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("test timeout"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("cancelled", interrupted); }
            return "42.gif";
        };
        try (NativeAvatarResolver resolver = resolver(source, delivery, now)) {
            Result a = new Result(), b = new Result(); NativeAvatarResolver.Request cancelled = resolver.load("70001", a);
            check(started.await(3, TimeUnit.SECONDS), "source began on worker"); resolver.load("70001", b); cancelled.cancel(); release.countDown(); delivery.one();
            check(a.resolved == 0 && a.failures == 0 && b.resolved == 1 && requests.get() == 1, "cancelled duplicate does not cancel remaining visible subscriber");
            Result late = new Result(); NativeAvatarResolver.Request queued = resolver.load("70001", late); queued.cancel(); delivery.one();
            check(late.resolved == 0 && late.failures == 0, "cache delivery cancelled before UI callback");
        }
        Delivery separate = new Delivery(); CountDownLatch active = new CountDownLatch(1), stopped = new CountDownLatch(1);
        try (NativeAvatarResolver resolver = resolver((uid, token) -> {
            active.countDown(); try { new CountDownLatch(1).await(); }
            catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); stopped.countDown(); throw new IOException("cancelled", cancelled); }
            return "";
        }, separate, now)) {
            Result abandoned = new Result(); NativeAvatarResolver.Request request = resolver.load("70003", abandoned);
            check(active.await(3, TimeUnit.SECONDS), "last-subscriber task began"); request.cancel();
            check(stopped.await(3, TimeUnit.SECONDS), "last subscriber interrupts its worker");
            check(resolver.state().contains("publicAvatarTasks=0"), "cancelled task removed immediately");
            check(abandoned.resolved == 0 && abandoned.failures == 0, "abandoned avatar cannot update a recycled view");
        }
    }
    private static void existingMetadataWins() throws Exception {
        Delivery delivery = new Delivery(); long[] now = {1}; CountDownLatch started = new CountDownLatch(1), stopped = new CountDownLatch(1);
        try (NativeAvatarResolver resolver = resolver((uid, token) -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); stopped.countDown(); return ""; }
            return "";
        }, delivery, now)) {
            Result waiting = new Result(); resolver.load("70001", waiting);
            check(started.await(3, TimeUnit.SECONDS), "public lookup began before reply metadata arrived");
            resolver.remember("70001", ".a/70001_99.png"); delivery.one();
            check(stopped.await(3, TimeUnit.SECONDS), "real reply metadata cancels redundant public lookup");
            check(waiting.resolved == 1 && waiting.avatar.endsWith("/70001_99.png"), "new same-response metadata immediately satisfies waiting visible views");
            Result cached = new Result(); resolver.load("70001", cached); delivery.one();
            check(cached.avatar.equals(waiting.avatar), "cancelled empty lookup cannot overwrite known real metadata");
        }
    }
    private static void boundsAndClose() throws Exception {
        Delivery delivery = new Delivery(); long[] now = {1}; CountDownLatch release = new CountDownLatch(1);
        try (NativeAvatarResolver resolver = resolver((uid, token) -> { try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); } return "42.gif"; }, delivery, now)) {
            List<NativeAvatarResolver.Request> requests = new ArrayList<>();
            for (int i = 0; i < 24; i++) requests.add(resolver.load(Integer.toString(70001 + i), new Result()));
            Result saturated = new Result(); resolver.load("80001", saturated); delivery.one();
            check(saturated.failures == 1 && saturated.busy, "24 UID work limit reports temporary saturation");
            check(resolver.state().contains("publicAvatarTasks=24"), "queue cannot grow without a bound");
            for (NativeAvatarResolver.Request request : requests) request.cancel();
            check(resolver.state().contains("publicAvatarTasks=0"), "leaving viewport releases all queued work");
            release.countDown(); Result recovery = new Result(); resolver.load("80001", recovery); delivery.one();
            check(recovery.resolved == 1, "saturation does not permanently poison later visible avatars");
        } finally { release.countDown(); }
        Delivery callbacks = new Delivery(); NativeAvatarResolver closed = resolver((uid, token) -> "42.gif", callbacks, now);
        Result pending = new Result(); closed.load("70001", pending);
        Runnable action = callbacks.queue.poll(3, TimeUnit.SECONDS); check(action != null, "callback pending before close"); closed.close(); action.run();
        check(pending.resolved == 0 && pending.failures == 0, "close suppresses queued UI delivery");
        closed.load("70002", new Result()); check(callbacks.queue.isEmpty(), "closed resolver cannot start more work");
        Delivery lruDelivery = new Delivery();
        try (NativeAvatarResolver resolver = resolver((uid, token) -> "42.gif", lruDelivery, now)) {
            for (int i = 0; i < 140; i++) { resolver.load(Integer.toString(90001 + i), new Result()); lruDelivery.one(); }
            check(resolver.state().contains("publicAvatarCache=128"), "metadata cache retains at most 128 identities");
        }
    }
    private static final class FakeConnection extends HttpURLConnection {
        String body = "{\"data\":{\"0\":{\"uid\":70001,\"avatar\":\"42.gif\"}}}";
        static int nextStatus = 200;
        static Runnable nextResponseHook, nextReadHook, nextEofHook;
        final int status;
        final Runnable responseHook, readHook, eofHook;
        boolean disconnected;
        FakeConnection(URL url) {
            super(url); status = nextStatus; responseHook = nextResponseHook; readHook = nextReadHook; eofHook = nextEofHook;
        }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
        @Override public int getResponseCode() { if (responseHook != null) responseHook.run(); return status; }
        @Override public String getContentType() { return "application/json"; }
        @Override public long getContentLengthLong() { return body.length(); }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? "https://img4.nga.cn/avatars/a.png" : null; }
        @Override public ByteArrayInputStream getInputStream() {
            return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)) {
                @Override public synchronized int read(byte[] bytes, int offset, int length) {
                    if (readHook != null) readHook.run();
                    int count = super.read(bytes, offset, length);
                    if (count == -1 && eofHook != null) eofHook.run();
                    return count;
                }
            };
        }
    }
    private static void publicTransport() throws Exception {
        final List<FakeConnection> opened = new ArrayList<>();
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                FakeConnection connection = new FakeConnection(url); opened.add(connection); return connection;
            }
        } : null);
        NativeAvatarResolver.Source source = NativeAvatarResolver.publicSource();
        check(source.avatar("70001", new RequestCancellation.Token()).endsWith("/face/42.gif"), "real public-source implementation uses tested parser");
        FakeConnection request = opened.get(0);
        check(request.getURL().getHost().equals("bbs.nga.cn") && request.getURL().getPath().equals("/nuke.php"), "public source origin and path are fixed");
        check(request.getURL().getQuery().equals("__lib=ucp&__act=get&uid=70001&__output=8"), "only public positive UID enters request");
        check(!request.getInstanceFollowRedirects(), "public metadata redirects are disabled");
        check(request.getRequestProperty("Cookie") == null && request.getRequestProperty("Authorization") == null, "public metadata carries no account credentials");
        check(request.getRequestProperty("Accept-Encoding").equals("identity"), "bounded byte transport has no decompression expansion");
        boolean anonymousRejected = false; try { source.avatar("-70001", new RequestCancellation.Token()); } catch (IOException rejected) { anonymousRejected = true; }
        check(anonymousRejected && opened.size() == 1, "public transport rejects anonymous identity before opening network");
        RequestCancellation.Token cancelled = new RequestCancellation.Token(); cancelled.cancel();
        boolean cancellationRejected = false; try { source.avatar("70001", cancelled); } catch (IOException rejected) { cancellationRejected = true; }
        check(cancellationRejected && opened.size() == 1, "cancelled metadata request never opens a connection");
        for (String host : new String[]{"bbs.nga.cn", "ngabbs.com", "nga.178.com"}) {
            NativeAvatarResolver.Source authenticated = NativeAvatarResolver.sessionSource(host, "ngaPassportUid=70001; ngaPassportCid=fixture-only", () -> true);
            check(authenticated.avatar("70001", new RequestCancellation.Token()).endsWith("/face/42.gif"), "same-session metadata resolves only the returned real avatar");
            FakeConnection scoped = opened.get(opened.size() - 1);
            check(scoped.getURL().getHost().equals(host) && scoped.getURL().getPath().equals("/nuke.php") && scoped.getURL().getPort() == -1, "session origin and path stay fixed on a trusted NGA host");
            check(scoped.getRequestProperty("Cookie").equals("ngaPassportUid=70001; ngaPassportCid=fixture-only"), "immutable session cookie goes only to original NGA metadata origin");
            check(!scoped.getInstanceFollowRedirects() && scoped.getRequestProperty("Authorization") == null && scoped.disconnected, "same-session metadata disables redirects and cleans up its connection");
        }
        int beforeInvalid = opened.size();
        for (String host : new String[]{"img4.nga.cn", "evil.invalid", "bbs.nga.cn.evil.invalid", "https://bbs.nga.cn", "bbs.nga.cn:443", "user@bbs.nga.cn", "bbs.nga.cn/path", "bbs.nga.cn\r\nX: y", "BBS.NGA.CN", "", null}) {
            boolean invalid = false;
            try { NativeAvatarResolver.sessionSource(host, "ngaPassportCid=fixture-only", () -> true); }
            catch (IllegalArgumentException rejected) { invalid = true; }
            check(invalid, "untrusted or noncanonical session host rejected before network");
        }
        StringBuilder excessive = new StringBuilder(); for (int i = 0; i < 65537; i++) excessive.append('x');
        for (String cookie : new String[]{null, "", " ", "a=b\r\nCookie: stolen", "a=b\n", "a=b\u0000", "a=b\t", "a=b\u007f", "a=b\u0080", excessive.toString()}) {
            boolean invalid = false;
            try { NativeAvatarResolver.sessionSource("bbs.nga.cn", cookie, () -> true); }
            catch (IllegalArgumentException rejected) { invalid = true; check(!rejected.getMessage().contains("stolen"), "invalid cookie is never included in errors"); }
            check(invalid, "missing, control, non-ASCII or oversized session cookie rejected");
        }
        boolean missingGuard = false;
        try { NativeAvatarResolver.sessionSource("bbs.nga.cn", "a=b", null); } catch (IllegalArgumentException rejected) { missingGuard = true; }
        check(missingGuard && opened.size() == beforeInvalid, "invalid session configuration makes zero connections");
        boolean expiredBeforeStart = false;
        try { NativeAvatarResolver.sessionSource("bbs.nga.cn", "a=b", () -> false).avatar("70001", new RequestCancellation.Token()); }
        catch (IOException expired) { expiredBeforeStart = true; }
        check(expiredBeforeStart && opened.size() == beforeInvalid, "stale session rejected before opening connection");
        boolean brokenGuard = false;
        try { NativeAvatarResolver.sessionSource("bbs.nga.cn", "a=b", () -> { throw new IllegalStateException("fixture-secret"); }).avatar("70001", new RequestCancellation.Token()); }
        catch (IOException expired) { brokenGuard = true; check(!expired.toString().contains("fixture-secret"), "session errors do not expose captured credentials or guard details"); }
        check(brokenGuard && opened.size() == beforeInvalid, "failed session guard fails closed before network");
        boolean anonymousSession = false;
        try { NativeAvatarResolver.sessionSource("bbs.nga.cn", "a=b", () -> true).avatar("-1", new RequestCancellation.Token()); }
        catch (IOException invalid) { anonymousSession = true; }
        check(anonymousSession && opened.size() == beforeInvalid, "session transport also rejects anonymous authors before network");
        AtomicBoolean current = new AtomicBoolean(true);
        FakeConnection.nextResponseHook = () -> current.set(false);
        boolean changedAfterHeaders = false;
        try { NativeAvatarResolver.sessionSource("bbs.nga.cn", "a=b", current::get).avatar("70001", new RequestCancellation.Token()); }
        catch (IOException expired) { changedAfterHeaders = true; }
        check(changedAfterHeaders && opened.get(opened.size() - 1).disconnected, "session change after response headers rejects body and cleans connection");
        FakeConnection.nextResponseHook = null; current.set(true); FakeConnection.nextReadHook = () -> current.set(false);
        boolean changedDuringRead = false;
        try { NativeAvatarResolver.sessionSource("bbs.nga.cn", "a=b", current::get).avatar("70001", new RequestCancellation.Token()); }
        catch (IOException expired) { changedDuringRead = true; }
        check(changedDuringRead && opened.get(opened.size() - 1).disconnected, "session change while reading rejects stale avatar metadata");
        FakeConnection.nextReadHook = null; current.set(true); FakeConnection.nextEofHook = () -> current.set(false);
        boolean changedAtEnd = false;
        try { NativeAvatarResolver.sessionSource("bbs.nga.cn", "a=b", current::get).avatar("70001", new RequestCancellation.Token()); }
        catch (IOException expired) { changedAtEnd = true; }
        check(changedAtEnd && opened.get(opened.size() - 1).disconnected, "session change at completed body cannot return an old-account avatar");
        FakeConnection.nextEofHook = null; FakeConnection.nextStatus = 302; int beforeRedirect = opened.size();
        boolean redirectRejected = false;
        try { NativeAvatarResolver.sessionSource("bbs.nga.cn", "a=b", () -> true).avatar("70001", new RequestCancellation.Token()); }
        catch (IOException redirect) { redirectRejected = true; }
        check(redirectRejected && opened.size() == beforeRedirect + 1, "redirect is rejected without issuing a second credentialed request");
        check(opened.get(opened.size() - 1).getURL().getHost().equals("bbs.nga.cn") && opened.get(opened.size() - 1).disconnected, "Cookie never follows even a redirect to allowed image CDN");
        FakeConnection.nextStatus = 200;
        RequestCancellation.Token sessionCancellation = new RequestCancellation.Token();
        FakeConnection.nextReadHook = sessionCancellation::cancel;
        boolean sessionCancelled = false;
        try { NativeAvatarResolver.sessionSource("bbs.nga.cn", "a=b", () -> true).avatar("70001", sessionCancellation); }
        catch (IOException cancelledRead) { sessionCancelled = true; }
        check(sessionCancelled && opened.get(opened.size() - 1).disconnected, "session-source socket remains cancellable during body read");
        FakeConnection.nextReadHook = null;

    }
    public static void main(String[] args) throws Exception {
        parsing(); cacheAndIdentity(); dedupeAndCancel(); existingMetadataWins(); boundsAndClose(); publicTransport();
        System.out.println("PASS public avatar resolver: " + checks + " checks; 0 real network requests");
    }
}
