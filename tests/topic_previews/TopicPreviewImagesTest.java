package cn.ngapure.app;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class TopicPreviewImagesTest {
    private static int assertions;
    private static final String SCOPE = String.join("", Collections.nCopies(64, "a"));
    private static final String OTHER = String.join("", Collections.nCopies(64, "b"));
    private static final String A = "https://img.nga.cn/attachments/mon_202610/23/a.jpg";
    private static final String B = "https://img4.nga.cn/attachments/mon_202610/23/b.png";
    private static final String C = "https://img.nga.178.com/attachments/mon_202610/23/c.gif";
    private static final Executor DIRECT = new Executor() { @Override public void execute(Runnable task) { task.run(); } };
    private static void check(boolean okay, String message) { assertions++; if (!okay) throw new AssertionError(message); }
    private static void waitFor(CountDownLatch latch, String message) throws Exception { check(latch.await(5, TimeUnit.SECONDS), message); }
    private static String image(String url) { return "[img]" + url + "[/img]"; }
    private static void same(List<String> got, String... expected) { check(got.equals(Arrays.asList(expected)), "expected " + Arrays.toString(expected) + ", got " + got); }
    private static void extraction() {
        same(TopicPreviewImages.extract(null)); same(TopicPreviewImages.extract("hello [s:ac:哭笑]"));
        same(TopicPreviewImages.extract(image("./mon_202610/23/a.jpg")), A);
        same(TopicPreviewImages.extract("[album=相册]" + image(A) + "[img2]" + B + "[/img][/album]"), A, B);
        same(TopicPreviewImages.extract(image(A) + image(A) + image(B) + image(C) + image("https://img.nga.cn/attachments/d.webp")), A, B, C, "https://img.nga.cn/attachments/d.webp");
        same(TopicPreviewImages.extract("[quote]" + image(A) + "[/quote]" + image(B)), B);
        same(TopicPreviewImages.extract("[quote=other]" + image(A) + "[quote]" + image(C) + "[/quote][/quote]" + image(B)), B);
        for (String tag : Arrays.asList("quote", "QUOTE=某人", "lessernuke", "lessernuke2", "code", "crypt", "hide", "hidden", "password", "purchase", "collapse", "spoiler")) {
            String closer = tag.split("=")[0];
            same(TopicPreviewImages.extract("[" + tag + "]" + image(A) + "[/" + closer + "]" + image(B)), B);
            same(TopicPreviewImages.extract("[" + tag + "]" + image(A)));
        }
        same(TopicPreviewImages.extract("[code][quote]" + image(A) + "[/code]" + image(B)), B);
        same(TopicPreviewImages.extract("[crypt][quote]" + image(A) + "[/crypt]" + image(B)), B);
        same(TopicPreviewImages.extract("[collapse=剧透]" + image(A) + "[/collapse]"));
        same(TopicPreviewImages.extract("[collapse=外层]" + image(A) + "[collapse]" + image(C) + "[/collapse][/collapse]" + image(B)), B);
        same(TopicPreviewImages.extract("[spoiler=剧透]" + image(B) + "[/spoiler]" + image(A)), A);
        same(TopicPreviewImages.extract("[code]" + image(A) + "[/code]" + image(B)), B);
        same(TopicPreviewImages.extract("<img src=\"" + A + "\">"));
        same(TopicPreviewImages.extract("[img-1]" + A + "[/img]"));
        same(TopicPreviewImages.extract("[img]https://img4.nga.cn/ngabbs/post/smile/a2_02.png[/img]"));
        same(TopicPreviewImages.extract("[img]https://img.nga.cn/avatars/a.jpg[/img]"));
        same(TopicPreviewImages.extract("<br>" + image(A)), A);
        same(TopicPreviewImages.extract(String.join("", Collections.nCopies(256 * 1024 + 1, "x")) + image(A)));
        same(TopicPreviewImages.extract(String.join("", Collections.nCopies(30000, "[quote="))));
        List<String> immutable = TopicPreviewImages.extract(image(A)); boolean rejected = false;
        try { immutable.add(B); } catch (UnsupportedOperationException expected) { rejected = true; }
        check(rejected, "extracted URL snapshot immutable");
        check(TopicPreviewImages.normalize("http://IMG.NGA.CN:443/attachments/a.jpg").equals("https://img.nga.cn/attachments/a.jpg"), "old HTTP attachment upgraded and canonicalized");
        check(TopicPreviewImages.normalize("//img4.nga.cn/attachments/a.WEBP").equals("https://img4.nga.cn/attachments/a.WEBP"), "protocol-relative image supported");
        check(!TopicPreviewImages.normalize("./mon_202402/29/a.png").isEmpty(), "leap date allowed");
        String[] unsafe = {"", "./mon_202302/29/a.png", "./mon_202613/01/a.png", "./mon_202610/00/a.png", "./mon_000010/01/a.png", "https://img.nga.cn.evil.test/attachments/a.jpg", "https://img.nga.cn@evil.test/attachments/a.jpg", "https://evil.test/attachments/a.jpg", "https://img.nga.cn/attachments/../a.jpg", "https://img.nga.cn/attachments/./a.jpg", "https://img.nga.cn/attachments/%2e%2e/a.jpg", "https://img.nga.cn/attachments//a.jpg", "https://img.nga.cn/attachments/a.jpg?token=x", "https://img.nga.cn/attachments/a.jpg#x", "https://img.nga.cn:80/attachments/a.jpg", "http://img.nga.cn:80/attachments/a.jpg", "https://user@img.nga.cn/attachments/a.jpg", "file:///attachments/a.jpg", "javascript:alert(1)", "https://img.nga.cn/avatars/a.jpg", "https://img.nga.cn/attachments/a.svg", "https://img.nga.cn/attachments/a.jpg.exe", "https://img.nga.cn/attachments/a\n.jpg", "https://img.nga.cn/attachments/a b.jpg"};
        for (String bad : unsafe) { check(TopicPreviewImages.normalize(bad).isEmpty(), "unsafe image rejected: " + bad); same(TopicPreviewImages.extract(image(bad))); }
    }
    private static void completeGalleryMetadata() throws Exception {
        StringBuilder first = new StringBuilder();
        for (int i = 0; i < 90; i++) first.append(image("https://img.nga.cn/attachments/mon_202610/23/p" + i + ".jpg"));
        first.append(image(A)).append("[quote]").append(image(B)).append("[/quote]");
        List<String> extracted = TopicPreviewImages.extract(first.toString());
        check(extracted.size() == 64, "gallery retained metadata bounded to 64 original attachments");
        TopicPreviewImages metadata = new TopicPreviewImages((scope,tid)->extracted,DIRECT);
        try {
            TopicPreviewImages.Result result = awaitResult(metadata,SCOPE,"93");
            check(result.sourceTotal == 91 && result.totalKnown && result.truncated, "original distinct count survives extraction and cache despite bound");
            metadata.seed(SCOPE,"94",extracted); result=metadata.peek(SCOPE,"94");
            check(result.sourceTotal == 91 && result.truncated, "seeding preserves bounded extraction completeness metadata");
            check(result.urls.get(63).endsWith("p63.jpg"), "retained original attachment order preserved");
            metadata.seed(SCOPE,"95",Arrays.asList(A,B,C)); result=metadata.peek(SCOPE,"95");
            check(result.sourceTotal == 3 && result.totalKnown && !result.truncated, "ordinary complete gallery exact total");
            metadata.seed(SCOPE,"96",Arrays.asList(A,A,B,"https://evil.test/a.png")); result=metadata.peek(SCOPE,"96");
            check(result.sourceTotal == 2 && result.urls.size() == 2, "source total counts only unique permitted own-post images");
            VoteMetrics counts = new VoteMetrics(); counts.likesKnown=true; counts.likes=9; counts.dislikesKnown=true; counts.dislikes=2;
            metadata.seed(SCOPE,"97",TopicPreviewImages.withVotes(extracted,counts)); counts.likes=90;
            result=metadata.peek(SCOPE,"97");
            check(result.sourceTotal==91&&result.truncated&&result.votes().likes==9&&result.votes().dislikes==2,"original floor votes reuse preserves gallery completeness and copies provider data");
            result.votes().likes=900;
            check(metadata.peek(SCOPE,"97").votes().likes==9,"each observer receives independent vote metadata");
        } finally { metadata.close(); }
    }
    private static TopicPreviewImages.Result awaitResult(TopicPreviewImages manager, String scope, String tid) throws Exception {
        CountDownLatch completed = new CountDownLatch(1); AtomicReference<TopicPreviewImages.Result> got = new AtomicReference<>();
        manager.request(scope, tid, result -> { got.set(result); completed.countDown(); }); waitFor(completed, "preview result delivered"); return got.get();
    }
    private static void caching() throws Exception {
        AtomicInteger reads = new AtomicInteger(); final long[] now = {100};
        TopicPreviewImages manager = new TopicPreviewImages((scope, tid) -> { reads.incrementAndGet(); return tid.equals("2") ? Collections.<String>emptyList() : Arrays.asList(A, A, B, "https://evil.test/x.png"); }, DIRECT, () -> now[0]);
        try {
            TopicPreviewImages.Result first = awaitResult(manager, SCOPE, "1"); same(first.urls, A, B); check(!first.failed, "valid successful metadata");
            check(manager.peek(SCOPE, "1") == first, "memory cache hit same immutable snapshot");
            awaitResult(manager, SCOPE, "1"); check(reads.get() == 1, "cached result does not re-read thread");
            awaitResult(manager, OTHER, "1"); check(reads.get() == 2, "account and host scope do not share metadata");
            now[0] += 30 * 60 * 1000L - 1; check(manager.peek(SCOPE, "1") == first, "positive TTL exact lower boundary");
            now[0]++; check(manager.peek(SCOPE, "1") == null, "positive metadata expires at 30 minutes");
            TopicPreviewImages.Result empty = awaitResult(manager, SCOPE, "2"); check(empty.urls.isEmpty() && !empty.failed, "successful no-image metadata cached distinctly");
            now[0] += 5 * 60 * 1000L; check(manager.peek(SCOPE, "2") == null, "no-image metadata expires at 5 minutes");
            manager.seed(SCOPE, "3", Arrays.asList(A, B)); same(manager.peek(SCOPE, "3").urls, A, B); check(reads.get() == 3, "already-read first post seeds cache without network");
            manager.cancelAll(); same(manager.peek(SCOPE, "3").urls, A, B);
            manager.seed(OTHER, "9", Collections.singletonList(B)); manager.invalidate(SCOPE);
            check(manager.peek(SCOPE, "3") == null, "explicit refresh drops current identity metadata");
            same(manager.peek(OTHER, "9").urls, B);
            manager.seed(SCOPE, "3", Arrays.asList(A, B));
            for (int i = 1000; i < 1096; i++) manager.seed(SCOPE, String.valueOf(i), Collections.singletonList(A));
            check(manager.peek(SCOPE, "3") == null, "cache bounded to 96 entries");
            check(manager.peek(SCOPE, "1000") != null, "oldest current entry may be touched");
            manager.seed(SCOPE, "1096", Collections.singletonList(B)); check(manager.peek(SCOPE, "1001") == null && manager.peek(SCOPE, "1000") != null, "least recently used cache eviction");
            String[] scopes = {null, "", "account", SCOPE.toUpperCase(java.util.Locale.ROOT), SCOPE + "a"};
            for (String scope : scopes) { boolean rejected = false; try { manager.peek(scope, "1"); } catch (IllegalArgumentException expected) { rejected = true; } check(rejected, "raw or invalid identity never used as cache key"); }
            for (String tid : new String[]{null, "", "0", "-1", "1&x", "01", "1234567890123456789"}) { boolean rejected = false; try { manager.request(SCOPE, tid, r -> { }); } catch (IllegalArgumentException expected) { rejected = true; } check(rejected, "invalid topic ID rejected"); }
        } finally { manager.close(); }
        check(manager.peek(SCOPE, "1000") == null, "close clears metadata");
        AtomicInteger fails = new AtomicInteger(); final long[] errorTime = {0};
        TopicPreviewImages broken = new TopicPreviewImages((scope, tid) -> { fails.incrementAndGet(); throw new java.io.IOException("fixture"); }, DIRECT, () -> errorTime[0]);
        try { check(awaitResult(broken, SCOPE, "1").failed, "network failure represented safely"); awaitResult(broken, SCOPE, "1"); check(fails.get() == 1, "short failure cache suppresses repeated reads"); errorTime[0] = 30000; awaitResult(broken, SCOPE, "1"); check(fails.get() == 2, "failed metadata may retry after 30 seconds"); } finally { broken.close(); }
    }
    private static void sharedCancellation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), completed = new CountDownLatch(1); AtomicInteger calls = new AtomicInteger(), retired = new AtomicInteger();
        TopicPreviewImages manager = new TopicPreviewImages((scope, tid) -> { calls.incrementAndGet(); entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("fixture timed out"); return Collections.singletonList(A); }, DIRECT);
        try {
            TopicPreviewImages.Request first = manager.request(SCOPE, "1", r -> retired.incrementAndGet()); waitFor(entered, "shared request started");
            manager.request(SCOPE, "1", r -> completed.countDown()); first.cancel(); release.countDown(); waitFor(completed, "other visible row still receives shared result"); check(calls.get() == 1 && retired.get() == 0, "one row cancellation neither misdelivers nor cancels another subscriber");
        } finally { release.countDown(); manager.close(); }
        ConcurrentLinkedQueue<Runnable> queued = new ConcurrentLinkedQueue<>(); TopicPreviewImages delayed = new TopicPreviewImages((scope, tid) -> Collections.singletonList(A), queued::add);
        try {
            delayed.seed(SCOPE, "1", Collections.singletonList(A)); AtomicInteger callbacks = new AtomicInteger();
            TopicPreviewImages.Request cached = delayed.request(SCOPE, "1", r -> callbacks.incrementAndGet()); cached.cancel(); Runnable callback = queued.poll(); check(callback != null, "cached callback scheduled"); callback.run(); check(callbacks.get() == 0, "recycled row suppresses queued cached callback");
            delayed.request(SCOPE, "1", r -> callbacks.incrementAndGet()); delayed.cancelAll(); callback = queued.poll(); check(callback != null, "page-owned cached callback scheduled"); callback.run(); check(callbacks.get() == 0, "page cancellation suppresses all queued callbacks including cache hits");
        } finally { delayed.close(); }
    }
    private static void backpressure() throws Exception {
        AtomicInteger reads = new AtomicInteger(), active = new AtomicInteger(), maximum = new AtomicInteger(), failed = new AtomicInteger(); CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1), interrupted = new CountDownLatch(2);
        TopicPreviewImages manager = new TopicPreviewImages((scope, tid) -> { reads.incrementAndGet(); int n = active.incrementAndGet(); maximum.accumulateAndGet(n, Math::max); entered.countDown(); try { release.await(5, TimeUnit.SECONDS); return Collections.singletonList(A); } catch (InterruptedException stopped) { interrupted.countDown(); throw stopped; } finally { active.decrementAndGet(); } }, DIRECT);
        try {
            for (int i = 1; i <= 20; i++) manager.request(SCOPE, String.valueOf(i), r -> { if (r.failed) failed.incrementAndGet(); });
            waitFor(entered, "both bounded workers started"); check(reads.get() == 2 && maximum.get() == 2, "only two in-flight metadata reads"); check(failed.get() == 6, "only twelve queued requests; excess completes without network or silent permanent wait");
            manager.cancelAll(); waitFor(interrupted, "cancelAll interrupts both metadata workers"); release.countDown(); check(manager.peek(SCOPE, "1") == null, "cancelled work never populates cache");
        } finally { release.countDown(); manager.close(); }
        check(reads.get() == 2, "cancelled queued requests never start network");
    }
    private static void seedWins() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1), complete = new CountDownLatch(1); AtomicReference<TopicPreviewImages.Result> delivered = new AtomicReference<>();
        TopicPreviewImages manager = new TopicPreviewImages((scope, tid) -> { entered.countDown(); try { finish.await(5, TimeUnit.SECONDS); } catch (InterruptedException expected) { /* Fixture deliberately finishes late. */ } return Collections.singletonList(A); }, DIRECT);
        try { manager.request(SCOPE, "1", result -> { delivered.set(result); complete.countDown(); }); waitFor(entered, "old read started"); manager.seed(SCOPE, "1", Collections.singletonList(B)); finish.countDown(); waitFor(complete, "seed delivered to existing subscriber"); same(delivered.get().urls, B); same(manager.peek(SCOPE, "1").urls, B); } finally { finish.countDown(); manager.close(); }
    }
    /** A stuck socket ignores interrupts; only the bounded asynchronous disconnect may release it. */
    private static void awaitSocketDisconnect(CountDownLatch latch) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); boolean interrupted = false;
        try {
            while (latch.getCount() != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new java.io.IOException("Synthetic socket was not actively disconnected before the original five-second deadline");
                try { if (!latch.await(remaining, TimeUnit.NANOSECONDS)) throw new java.io.IOException("Synthetic socket disconnect deadline"); }
                catch (InterruptedException cancellation) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private static void socketCancellation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), disconnected = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicReference<Thread> disconnectThread = new AtomicReference<>(); AtomicInteger callbacks = new AtomicInteger();
        Thread caller = Thread.currentThread();
        HttpURLConnection connection = new HttpURLConnection(new URL("https://bbs.nga.cn/read.php?tid=1")) {
            @Override public void disconnect() { disconnectThread.set(Thread.currentThread()); disconnected.countDown(); }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() { }
        };
        TopicPreviewImages manager = new TopicPreviewImages((scope, tid) -> {
            RequestCancellation.Registration registration = RequestCancellation.track(connection);
            try { entered.countDown(); awaitSocketDisconnect(disconnected); RequestCancellation.check(); return Collections.singletonList(A); }
            finally { registration.close(); finished.countDown(); }
        }, DIRECT);
        try {
            TopicPreviewImages.Request request = manager.request(SCOPE, "1", r -> callbacks.incrementAndGet());
            waitFor(entered, "tracked request started"); request.cancel();
            waitFor(disconnected, "last subscriber actively disconnects tracked HTTP socket");
            Thread cleanup = disconnectThread.get();
            check(cleanup != null && cleanup != caller && cleanup.getName().startsWith("NgaReadCancel-"), "stuck socket is released by the actual bounded cancellation worker");
            check(cleanup.isDaemon(), "active socket disconnect does not retain process lifetime");
            waitFor(finished, "actively disconnected metadata worker completes cleanup");
            check(manager.peek(SCOPE, "1") == null && callbacks.get() == 0, "actively disconnected request neither seeds cache nor calls retired view");
        } finally { manager.close(); }
    }
    /** An interruptible response models NgaApi's own finally cleanup even if async cleanup is removed. */
    private static void interruptibleSocketFinally() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), disconnected = new CountDownLatch(1), finished = new CountDownLatch(1), held = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        HttpURLConnection connection = new HttpURLConnection(new URL("https://bbs.nga.cn/read.php?tid=1")) {
            @Override public void disconnect() { disconnected.countDown(); }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() { }
        };
        TopicPreviewImages manager = new TopicPreviewImages((scope, tid) -> {
            RequestCancellation.Registration registration = RequestCancellation.track(connection);
            try { entered.countDown(); held.await(5, TimeUnit.SECONDS); RequestCancellation.check(); return Collections.singletonList(A); }
            finally { registration.close(); connection.disconnect(); finished.countDown(); }
        }, DIRECT);
        try {
            TopicPreviewImages.Request request = manager.request(SCOPE, "1", r -> callbacks.incrementAndGet());
            waitFor(entered, "interruptible tracked response started"); request.cancel();
            waitFor(disconnected, "interruptible response always disconnects in its real-API-style finally");
            waitFor(finished, "interruptible metadata worker releases registration");
            check(manager.peek(SCOPE, "1") == null && callbacks.get() == 0, "interruptible cancelled response neither seeds cache nor calls retired view");
        } finally { held.countDown(); manager.close(); }
    }
    public static void main(String[] args) throws Exception { extraction(); completeGalleryMetadata(); caching(); sharedCancellation(); backpressure(); seedWins(); socketCancellation(); interruptibleSocketFinally(); System.out.println("TopicPreviewImagesTest: " + assertions + " assertions PASS"); }
}
