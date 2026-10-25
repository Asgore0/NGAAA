package cn.ngapure.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** The production posting adapter and HTTP transport run against in-memory connections only. */
public final class PostingCancellationTest {
    private static final ConcurrentLinkedQueue<Gate> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger OPENED = new AtomicInteger();
    private static int checks, scenarios;
    private interface Work { void run() throws Exception; }
    private static void ok(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static byte[] fixture(String name) throws IOException { return Files.readAllBytes(Paths.get("tests/posting/fixtures", name)); }
    private static final class Gate {
        final byte[] body;
        final CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1), disconnected = new CountDownLatch(1);
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        boolean block, interruptHeaders, interruptBody, fail, failConfiguration;
        volatile boolean streamClosed;
        int reads;
        Gate(String file) throws IOException { body = fixture(file); }
    }
    private static Gate next(String file) throws IOException { Gate gate = new Gate(file); QUEUE.add(gate); return gate; }
    private static final class Connection extends HttpURLConnection {
        final Gate gate;
        Connection(URL url, Gate gate) { super(url); this.gate = gate; }
        @Override public void setRequestProperty(String key, String value) { if (gate.failConfiguration) throw new IllegalArgumentException("Synthetic configuration failure"); }
        @Override public int getResponseCode() throws IOException { if (gate.interruptHeaders) Thread.currentThread().interrupt(); if (gate.fail) throw new IOException("Synthetic response lost"); return 200; }
        @Override public String getContentType() { return "application/json; charset=UTF-8"; }
        @Override public OutputStream getOutputStream() { return gate.sent; }
        @Override public InputStream getInputStream() {
            final ByteArrayInputStream bytes = new ByteArrayInputStream(gate.body);
            return new InputStream() {
                @Override public int read() { throw new AssertionError("Buffered read required"); }
                @Override public int read(byte[] data, int offset, int length) throws IOException {
                    gate.reads++; gate.reading.countDown();
                    if (gate.block) {
                        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (gate.release.getCount() > 0 && System.nanoTime() < end) {
                            try { gate.release.await(50, TimeUnit.MILLISECONDS); }
                            catch (InterruptedException ignored) { /* A socket read need not honor interrupts. */ }
                        }
                        if (gate.release.getCount() > 0) throw new IOException("Fixture release timeout");
                    }
                    if (gate.interruptBody) Thread.currentThread().interrupt();
                    return bytes.read(data, offset, length);
                }
                @Override public void close() { gate.streamClosed = true; }
            };
        }
        @Override public void disconnect() { gate.disconnected.countDown(); gate.release.countDown(); }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { connected = true; }
    }
    private static NativePosting api() { return new NativePosting(new NgaStore()); }
    private static NativePosting.Prepare prepare(NativePosting api) throws Exception { return api.prepare("new", "7", "", ""); }
    private static NativePosting.PostingException failure(NativePosting.Kind kind, Work work) throws Exception {
        try { work.run(); throw new AssertionError("Failure expected: " + kind); }
        catch (NativePosting.PostingException error) { ok(error.kind == kind, "Typed " + kind + " failure"); return error; }
    }
    private static void earlyCancellation() throws Exception {
        scenarios++; int before = OPENED.get();
        Thread.currentThread().interrupt();
        try {
            NativePosting.PostingException error = failure(NativePosting.Kind.NETWORK, () -> prepare(api()));
            ok(error.getCause() instanceof InterruptedIOException, "Pre-existing interrupt is cancellation");
            ok(Thread.currentThread().isInterrupted(), "Pre-existing interrupt is preserved");
        } finally { Thread.interrupted(); }
        ok(OPENED.get() == before, "Pre-interrupted permission read never opens HTTPS");
        RequestCancellation.Token token = new RequestCancellation.Token(); token.cancel();
        RequestCancellation.Scope scope = RequestCancellation.bind(token);
        try { failure(NativePosting.Kind.NETWORK, () -> prepare(api())); }
        finally { scope.close(); }
        ok(OPENED.get() == before, "Canceled page token never opens HTTPS");
        Gate fresh = next("prepare-new.json"); ok(prepare(api()).canUpload, "Leaving canceled scope restores unrelated preparation");
        ok(fresh.disconnected.getCount() == 0 && fresh.streamClosed, "Unrelated successful preparation closes resources");
    }
    private static void cancellationDuringRead(boolean headers) throws Exception {
        scenarios++; Gate gate = next("prepare-new.json"); gate.interruptHeaders = headers; gate.interruptBody = !headers;
        try {
            failure(NativePosting.Kind.NETWORK, () -> prepare(api()));
            ok(Thread.currentThread().isInterrupted(), "Transport preserves header/body interrupt");
        } finally { Thread.interrupted(); }
        ok(gate.reads == (headers ? 0 : 1), "Interrupted preparation stops at its next read boundary");
        ok(gate.disconnected.getCount() == 0, "Interrupted preparation disconnects");
        if (!headers) ok(gate.streamClosed, "Interrupted body closes its input stream");
    }
    private static void blockedPermission() throws Exception {
        scenarios++; Gate gate = next("prepare-new.json"); gate.block = true;
        RequestCancellation.Token token = new RequestCancellation.Token(); AtomicReference<Throwable> error = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            RequestCancellation.Scope scope = RequestCancellation.bind(token);
            try { prepare(api()); error.set(new AssertionError("Canceled permission must not return")); }
            catch (Exception expected) { error.set(expected); }
            finally { scope.close(); }
        });
        thread.start(); ok(gate.reading.await(2, TimeUnit.SECONDS), "Permission reaches an interrupt-insensitive socket read");
        long start = System.nanoTime(); token.cancel();
        ok(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 250, "Page cancellation does not wait for socket I/O");
        ok(gate.disconnected.await(2, TimeUnit.SECONDS), "Page cancellation actively disconnects the socket");
        thread.join(2000); ok(!thread.isAlive(), "Canceled permission worker exits without manual release");
        ok(error.get() instanceof NativePosting.PostingException && ((NativePosting.PostingException) error.get()).kind == NativePosting.Kind.NETWORK, "Canceled socket cannot authorize an editor");
        ok(gate.reads == 1 && gate.streamClosed, "Canceled permission closes its first body read");
    }
    private static NativePosting.Prepare livePermission(NativePosting posting) throws Exception { next("prepare-new.json"); return prepare(posting); }
    private static void deliverySurvivesCanceledPage(boolean upload) throws Exception {
        scenarios++; NativePosting posting = api(); NativePosting.Prepare permission = livePermission(posting);
        Gate gate = next(upload ? "upload-success.json" : "submit-success.json"); gate.block = true;
        RequestCancellation.Token token = new RequestCancellation.Token(); AtomicReference<Throwable> failure = new AtomicReference<>(); AtomicReference<Object> result = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            RequestCancellation.Scope scope = RequestCancellation.bind(token);
            try {
                if (upload) result.set(posting.upload(permission, "fixture.png", "image/png", new byte[]{(byte)137,80,78,71,13,10,26,10,0,1,2,3}));
                else result.set(posting.submit(permission, "fixture subject", "fixture content", Collections.emptyList()));
            } catch (Exception error) { failure.set(error); }
            finally { scope.close(); }
        });
        thread.start(); ok(gate.reading.await(2, TimeUnit.SECONDS), "Confirmed delivery reaches response read");
        token.cancel(); ok(!gate.disconnected.await(100, TimeUnit.MILLISECONDS), "Disposable page token cannot disconnect a confirmed delivery");
        ok(thread.isAlive() && gate.sent.size() > 0, "Delivery remains pending after request body was sent");
        gate.release.countDown(); thread.join(2000);
        ok(!thread.isAlive() && result.get() != null && failure.get() == null, "Confirmed delivery response settles successfully after page cancellation");
        ok(gate.streamClosed && gate.disconnected.getCount() == 0, "Delivery still closes its own transport");
    }
    private static void unknownDelivery() throws Exception {
        scenarios++; NativePosting posting = api(); NativePosting.Prepare permission = livePermission(posting);
        Gate gate = next("submit-success.json"); gate.fail = true;
        RequestCancellation.Token token = new RequestCancellation.Token(); token.cancel(); RequestCancellation.Scope scope = RequestCancellation.bind(token);
        try {
            NativePosting.PostingException error = failure(NativePosting.Kind.OUTCOME_UNKNOWN, () -> posting.submit(permission, "fixture subject", "fixture content", Collections.emptyList()));
            ok(error.uncertain && gate.sent.size() > 0, "Lost response remains UNKNOWN after a confirmed request body");
            int before = OPENED.get(); failure(NativePosting.Kind.OUTCOME_UNKNOWN, () -> posting.submit(permission, "fixture subject", "fixture content", Collections.emptyList()));
            ok(OPENED.get() == before, "UNKNOWN delivery is never retried automatically or with the same authorization");
        } finally { scope.close(); }
        ok(gate.disconnected.getCount() == 0, "Failed delivery disconnects");
    }
    private static void configurationFailureCloses() throws Exception {
        scenarios++; Gate gate = next("prepare-new.json"); gate.failConfiguration = true;
        try { prepare(api()); throw new AssertionError("Synthetic configuration failure expected"); }
        catch (IllegalArgumentException expected) { ok(gate.disconnected.getCount() == 0, "Configuration failure cannot leak the opened connection"); }
    }
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) throws IOException {
                if (!"https".equals(protocol) || !("bbs.nga.cn".equals(url.getHost()) || "img8.nga.cn".equals(url.getHost()))) throw new IOException("Unscripted endpoint blocked");
                Gate gate = QUEUE.poll(); if (gate == null) throw new IOException("Unscripted connection blocked");
                OPENED.incrementAndGet(); return new Connection(url, gate);
            }
        });
        earlyCancellation(); cancellationDuringRead(true); cancellationDuringRead(false); blockedPermission();
        deliverySurvivesCanceledPage(false); deliverySurvivesCanceledPage(true); unknownDelivery(); configurationFailureCloses();
        ok(QUEUE.isEmpty(), "All expected fake connections consumed");
        System.out.println("PostingCancellationTest: " + scenarios + " scenarios, " + checks + " checks passed; real network requests: 0");
    }
}
