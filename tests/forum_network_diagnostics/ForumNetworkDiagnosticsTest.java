package cn.ngapure.app;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Real API, synthetic in-process transport only. These are protocol checks, not a benchmark. */
public final class ForumNetworkDiagnosticsTest {
    private static final ArrayDeque<Exchange> QUEUED = new ArrayDeque<>();
    private static final List<Connection> OPENED = new ArrayList<>();
    private static int assertions;
    private static void check(boolean ok, String label) { assertions++; if (!ok) throw new AssertionError(label); }
    private static final class Exchange {
        final int status; final byte[] bytes; final String location;
        Runnable atHeaders;
        long headersObservedNanos, bodyObservedNanos;
        int bodyCalls, closes, disconnects;
        Exchange(int status, String payload, String location) {
            this.status = status; bytes = payload.getBytes(StandardCharsets.UTF_8); this.location = location;
        }
    }
    private static Exchange response(int status, String payload, String location) {
        Exchange result = new Exchange(status, payload, location); QUEUED.add(result); return result;
    }
    private static String body(String name) {
        return "{\"data\":{\"__F\":{\"fid\":\"650\",\"name\":\"" + name
                + "\"},\"__T\":{\"0\":{\"tid\":\"900001\",\"subject\":\"Synthetic topic\",\"authorid\":\"79811\"}}}}";
    }
    private static void pause() throws IOException {
        try { Thread.sleep(4); } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("Synthetic interruption");
        }
    }
    private static final class Connection extends HttpURLConnection {
        final Exchange exchange;
        Connection(URL url, Exchange exchange) { super(url); this.exchange = exchange; }
        @Override public int getResponseCode() throws IOException {
            long start = System.nanoTime(); pause(); if (exchange.atHeaders != null) exchange.atHeaders.run();
            exchange.headersObservedNanos = System.nanoTime() - start; return exchange.status;
        }
        @Override public String getContentType() { return "application/json; charset=UTF-8"; }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? exchange.location : null; }
        @Override public InputStream getInputStream() throws IOException {
            long start = System.nanoTime(); pause(); exchange.bodyCalls++;
            InputStream input = new ByteArrayInputStream(exchange.bytes) {
                @Override public void close() throws IOException { exchange.closes++; super.close(); }
            };
            exchange.bodyObservedNanos = System.nanoTime() - start; return input;
        }
        @Override public InputStream getErrorStream() {
            try { return getInputStream(); } catch (IOException unavailable) { return null; }
        }
        @Override public void disconnect() { exchange.disconnects++; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { connected = true; }
    }
    private static void durations(NgaApi.ForumPage page, Exchange... exchanges) {
        long headers = 0, body = 0, bytes = 0;
        for (Exchange value : exchanges) { headers += value.headersObservedNanos; body += value.bodyObservedNanos; if (value.bodyCalls != 0) bytes += value.bytes.length; }
        check(page.headersNanos >= headers, "headers duration contains every actual response-header interval");
        check(page.bodyNanos >= body, "body duration contains actual terminal stream acquisition");
        check(page.parsedNanos > 0, "successful parse duration observed");
        check(page.requestNanos >= page.headersNanos + page.bodyNanos + page.parsedNanos, "total covers non-overlapping measured stages");
        check(page.responseBytes == bytes, "byte count equals application-delivered bytes");
        check(page.requestCount == exchanges.length, "request count means application-level HTTP hops");
    }
    private static void defaults() {
        NgaApi.ForumPage local = new NgaApi.ForumPage();
        check(local.requestNanos == 0 && local.headersNanos == 0 && local.bodyNanos == 0 && local.parsedNanos == 0,
                "locally constructed cache page has no claimed network timings");
        check(local.responseBytes == 0 && local.requestCount == 0, "locally constructed cache page has no claimed network counts");
    }
    private static void successAndRedirect() throws Exception {
        TestContext context = new TestContext(); NgaStore store = new NgaStore(context); NgaApi api = new NgaApi(context);
        String accountA = "ngaPassportUid=79811; ngaPassportCid=synthetic-a";
        String accountB = "ngaPassportUid=79812; ngaPassportCid=synthetic-b";
        store.setCookie(accountA);
        Exchange first = response(200, body("First"), "");
        NgaApi.ForumPage page = api.readForum("650", 1, ""); durations(page, first);
        check(page.topics.size() == 1 && "First".equals(page.name), "diagnostics preserve parsed page contents");
        check(first.bodyCalls == 1 && first.closes == 1 && first.disconnects == 1, "success still fully reads closes and disconnects once");
        Connection original = OPENED.get(OPENED.size() - 1);
        check("GET".equals(original.getRequestMethod()) && original.getRequestProperty("Accept-Encoding") == null,
                "forum remains GET and preserves Android transparent gzip default");
        check(original.getConnectTimeout() == 15000 && original.getReadTimeout() == 20000 && !original.getInstanceFollowRedirects(),
                "transport timeouts and manual redirect policy unchanged");
        long firstTotal = page.requestNanos, firstBytes = page.responseBytes;
        Exchange redirect = response(302, "", "https://ngabbs.com/thread.php?fid=650&page=1&__output=8&__inchst=UTF8");
        redirect.atHeaders = () -> store.setCookie(accountB);
        Exchange terminal = response(200, body("Redirected"), "");
        NgaApi.ForumPage redirected = api.readForum("650", 1, ""); durations(redirected, redirect, terminal);
        check(redirect.bodyCalls == 0 && redirect.disconnects == 1 && terminal.closes == 1 && terminal.disconnects == 1,
                "redirect handling is unchanged and counts no unread redirect payload");
        check(accountA.equals(OPENED.get(OPENED.size() - 2).getRequestProperty("Cookie"))
                && accountA.equals(OPENED.get(OPENED.size() - 1).getRequestProperty("Cookie")),
                "all redirect hops retain the originally captured account");
        check(page.requestNanos == firstTotal && page.responseBytes == firstBytes && page.requestCount == 1,
                "later calls cannot overwrite earlier diagnostics");
        Exchange next = response(200, body("New account"), "");
        NgaApi.ForumPage fresh = api.readForum("650", 1, ""); durations(fresh, next);
        check(accountB.equals(OPENED.get(OPENED.size() - 1).getRequestProperty("Cookie")), "new read still captures current account");
        Exchange search = response(200, body("Search"), "");
        check(api.topics("650", 1, "query").size() == 1 && search.bodyCalls == 1 && search.closes == 1 && search.disconnects == 1,
                "non-forum request overload remains behaviorally unchanged");
    }
    private static void failuresAndCancellation() throws Exception {
        NgaApi api = new NgaApi(new TestContext());
        Exchange denied = response(403, "{\"error\":[\"Synthetic forbidden\"]}", "");
        boolean failed = false;
        try { api.readForum("650", 1, ""); } catch (IOException expected) { failed = true; }
        check(failed && denied.closes == 1 && denied.disconnects == 1, "HTTP failure cannot publish a successful timed page");
        Exchange external = response(302, "", "https://example.invalid/thread.php?fid=650");
        int opened = OPENED.size(); failed = false;
        try { api.readForum("650", 1, ""); } catch (IOException expected) { failed = true; }
        check(failed && OPENED.size() == opened + 1 && external.disconnects == 1, "diagnostics cannot bypass redirect allowlist");
        RequestCancellation.Token cancelled = new RequestCancellation.Token(); cancelled.cancel();
        RequestCancellation.Scope scope = RequestCancellation.bind(cancelled); opened = OPENED.size(); failed = false;
        try { api.readForum("650", 1, ""); } catch (InterruptedIOException expected) { failed = true; } finally { scope.close(); }
        check(failed && OPENED.size() == opened, "cancelled read still exits before connection creation");
        RequestCancellation.Token afterHeaders = new RequestCancellation.Token();
        Exchange interrupted = response(200, body("Must not render"), ""); interrupted.atHeaders = afterHeaders::cancel;
        scope = RequestCancellation.bind(afterHeaders); failed = false;
        try { api.readForum("650", 1, ""); } catch (InterruptedIOException expected) { failed = true; } finally { scope.close(); }
        check(failed && interrupted.bodyCalls == 0 && interrupted.disconnects >= 1, "cancellation after headers still prevents body acquisition");
    }
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> ("https".equals(protocol) || "http".equals(protocol)) ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) throws IOException {
                if (!NgaApi.allowed(url) || QUEUED.isEmpty()) throw new IOException("No network outside scripted fixtures");
                Connection result = new Connection(url, QUEUED.remove()); OPENED.add(result); return result;
            }
        } : null);
        defaults(); successAndRedirect(); failuresAndCancellation();
        check(QUEUED.isEmpty(), "all synthetic exchanges consumed exactly once");
        System.out.println("Forum network diagnostics: " + assertions + " assertions PASS; no real network/account access; not a timing benchmark");
    }
}
