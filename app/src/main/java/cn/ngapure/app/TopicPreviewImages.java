package cn.ngapure.app;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.AbstractList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** First-post attachment metadata only. No cookies, image bytes or disk state are retained here. */
public final class TopicPreviewImages implements AutoCloseable {
    public interface Loader { List<String> load(String scope, String tid) throws Exception; }
    public interface Callback { void complete(Result result); }
    public interface Request { void cancel(); }
    interface Clock { long now(); }
    public static final class Result {
        public final List<String> urls;
        public final boolean failed;
        /** Distinct permitted own-post images, before the browsing limit. */
        public final int sourceTotal;
        public final boolean totalKnown, truncated;
        private final VoteMetrics votes;
        private Result(List<String> urls, boolean failed) {
            Snapshot found = clean(urls); this.urls = found; this.failed = failed;
            sourceTotal = found.sourceTotal; totalKnown = found.totalKnown; truncated = found.truncated; votes = found.votes.copy();
        }
        /** Fresh copy on every read: one observer cannot alter another cached result. */
        public VoteMetrics votes() { return votes.copy(); }
    }
    public static final int MAX_GALLERY_IMAGES = 64, MAX_GALLERY_URL_CHARS = 64 * 1024;
    private static final int MAX_CONTENT = 256 * 1024, MAX_CACHE = 96, MAX_SUBSCRIBERS = 32;
    /** Immutable bounded URLs retain extraction completeness without a magic URL sentinel. */
    private static final class Snapshot extends AbstractList<String> {
        private final List<String> values;
        final int sourceTotal;
        final boolean totalKnown, truncated;
        final VoteMetrics votes;
        Snapshot(List<String> values, int total, boolean known, boolean truncated) {
            this(values,total,known,truncated,null);
        }
        Snapshot(List<String> values, int total, boolean known, boolean truncated, VoteMetrics votes) {
            this.values = Collections.unmodifiableList(new ArrayList<>(values));
            this.sourceTotal = total; this.totalKnown = known; this.truncated = truncated; this.votes = votes == null ? new VoteMetrics() : votes.copy();
        }
        @Override public String get(int index) { return values.get(index); }
        @Override public int size() { return values.size(); }
    }
    private static final long POSITIVE_TTL = 30 * 60 * 1000L, EMPTY_TTL = 5 * 60 * 1000L, ERROR_TTL = 30 * 1000L;
    private static final Pattern TAG = Pattern.compile("(/?)([a-zA-Z][a-zA-Z0-9-]*)(?:[= \\t][^\\r\\n]*)?");
    private static final Pattern SOURCE = Pattern.compile("<img\\b[^>]*\\bsrc=\"([^\"]+)\"[^>]*>");
    private static final Pattern RELATIVE = Pattern.compile("\\./mon_([0-9]{4})([0-9]{2})/([0-9]{2})/[A-Za-z0-9_-][A-Za-z0-9._-]*\\.(?i:jpe?g|png|gif|webp|bmp|avif)");
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private final Loader loader;
    private final Executor callbacks;
    private final Clock clock;
    private final ThreadPoolExecutor workers;
    private final Map<String, Flight> flights = new LinkedHashMap<>();
    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>(16, .75f, true);
    private boolean closed;
    private long callbackEpoch;

    public TopicPreviewImages(Loader loader, Executor callbacks) {
        this(loader, callbacks, new Clock() { @Override public long now() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); } });
    }
    TopicPreviewImages(Loader loader, Executor callbacks, Clock clock) {
        if (loader == null || callbacks == null || clock == null) throw new IllegalArgumentException("缺少首帖图片加载器");
        this.loader = loader; this.callbacks = callbacks; this.clock = clock;
        workers = new ThreadPoolExecutor(2, 2, 15, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(12), new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) { Thread thread = new Thread(runnable, "NgaTopicPreview-" + THREAD_IDS.incrementAndGet()); thread.setDaemon(true); return thread; }
        });
        workers.allowCoreThreadTimeOut(true);
    }
    private static String key(String scope, String tid) {
        if (scope == null || !scope.matches("[0-9a-f]{64}") || tid == null || !tid.matches("[1-9][0-9]{0,17}"))
            throw new IllegalArgumentException("无效的首帖图片请求");
        return scope + ":" + tid;
    }
    /** Null means unknown. An empty, successful result is a cached image-free first post. */
    public synchronized Result peek(String scope, String tid) {
        String key = key(scope, tid); if (closed) return null;
        Entry entry = cache.get(key); if (entry == null) return null;
        if (clock.now() >= entry.until) { cache.remove(key); return null; }
        return entry.result;
    }
    /** Call only for settled, visible rows. Duplicate requests share one bounded metadata read. */
    public Request request(String scope, String tid, Callback callback) {
        if (callback == null) throw new IllegalArgumentException("缺少首帖图片回调");
        final String key = key(scope, tid); final Subscriber subscriber;
        synchronized (this) {
            subscriber = new Subscriber(callback);
            if (closed) { subscriber.cancelled = true; return subscriber; }
            Result hit = peek(scope, tid);
            if (hit != null) { deliver(subscriber, hit); return subscriber; }
            Flight flight = flights.get(key);
            if (flight != null && flight.subscribers.size() >= MAX_SUBSCRIBERS) { deliver(subscriber, new Result(Collections.<String>emptyList(), true)); return subscriber; }
            if (flight == null) {
                flight = new Flight(key, scope, tid); flights.put(key, flight);
                subscriber.flight = flight; flight.subscribers.add(subscriber);
                try { workers.execute(flight.task); }
                catch (RejectedExecutionException full) { flights.remove(key); flight.subscribers.clear(); subscriber.flight = null; deliver(subscriber, new Result(Collections.<String>emptyList(), true)); }
            } else { subscriber.flight = flight; flight.subscribers.add(subscriber); }
        }
        return subscriber;
    }
    /** Reuse an already verified first-page read, without a second HTTP request on return. */
    public synchronized void seed(String scope, String tid, List<String> urls) {
        String key = key(scope, tid); if (closed) return;
        Flight old = flights.remove(key); Result result = new Result(urls, false); put(key, result);
        if (old != null) {
            old.token.cancel(); old.task.cancel(true); workers.remove(old.task);
            List<Subscriber> waiting = new ArrayList<>(old.subscribers); old.subscribers.clear();
            for (Subscriber subscriber : waiting) { subscriber.flight = null; deliver(subscriber, result); }
        }
    }
    private void put(String key, Result result) {
        long ttl = result.failed ? ERROR_TTL : result.urls.isEmpty() ? EMPTY_TTL : POSITIVE_TTL;
        cache.put(key, new Entry(result, clock.now() + ttl));
        while (cache.size() > MAX_CACHE) { Iterator<String> oldest = cache.keySet().iterator(); oldest.next(); oldest.remove(); }
    }
    private void deliver(final Subscriber subscriber, final Result result) {
        try { callbacks.execute(new Runnable() { @Override public void run() {
            synchronized (TopicPreviewImages.this) {
                if (closed || subscriber.epoch != callbackEpoch || subscriber.cancelled || subscriber.delivered) return;
                subscriber.delivered = true;
            }
            try { subscriber.callback.complete(result); } catch (RuntimeException retiredView) { /* A retired UI observer cannot stop other deliveries. */ }
        } }); } catch (RejectedExecutionException unavailable) { subscriber.cancelled = true; }
    }
    /** Cancels page-owned reads and callbacks; verified metadata remains reusable in this instance. */
    public synchronized void cancelAll() {
        callbackEpoch++;
        for (Flight flight : flights.values()) {
            for (Subscriber subscriber : flight.subscribers) subscriber.cancelled = true;
            flight.subscribers.clear(); flight.token.cancel(); flight.task.cancel(true); workers.remove(flight.task);
        }
        flights.clear();
    }
    /** Explicit refresh forgets this identity's metadata while retaining other identities' cache. */
    public synchronized void invalidate(String scope) {
        key(scope, "1"); if (closed) return;
        cancelAll(); Iterator<String> entries = cache.keySet().iterator();
        while (entries.hasNext()) if (entries.next().startsWith(scope + ":")) entries.remove();
    }
    @Override public synchronized void close() { if (closed) return; cancelAll(); closed = true; cache.clear(); workers.shutdownNow(); }
    private final class Subscriber implements Request {
        final Callback callback;
        final long epoch;
        volatile boolean cancelled;
        boolean delivered;
        Flight flight;
        Subscriber(Callback callback) { this.callback = callback; synchronized (TopicPreviewImages.this) { epoch = callbackEpoch; } }
        @Override public void cancel() { synchronized (TopicPreviewImages.this) {
            cancelled = true; Flight owner = flight; flight = null;
            if (owner != null) {
                owner.subscribers.remove(this);
                if (owner.subscribers.isEmpty() && flights.get(owner.key) == owner) {
                    flights.remove(owner.key); owner.token.cancel(); owner.task.cancel(true); workers.remove(owner.task);
                }
            }
        } }
    }
    private final class Flight {
        final String key, scope, tid;
        final List<Subscriber> subscribers = new ArrayList<>();
        final RequestCancellation.Token token = new RequestCancellation.Token();
        final FutureTask<Void> task;
        Flight(String key, String scope, String tid) {
            this.key = key; this.scope = scope; this.tid = tid;
            task = new FutureTask<Void>(new Runnable() { @Override public void run() { read(); } }, null);
        }
        void read() {
            Result result;
            RequestCancellation.Scope binding = RequestCancellation.bind(token);
            try { RequestCancellation.check(); result = new Result(loader.load(scope, tid), false); RequestCancellation.check(); }
            catch (Exception unavailable) { result = new Result(Collections.<String>emptyList(), true); }
            catch (OutOfMemoryError unavailable) { result = new Result(Collections.<String>emptyList(), true); }
            finally { binding.close(); }
            synchronized (TopicPreviewImages.this) {
                if (closed || token.isCancelled() || task.isCancelled() || flights.get(key) != this) return;
                flights.remove(key); put(key, result);
                List<Subscriber> waiting = new ArrayList<>(subscribers); subscribers.clear();
                for (Subscriber subscriber : waiting) { subscriber.flight = null; deliver(subscriber, result); }
            }
        }
    }
    private static final class Entry { final Result result; final long until; Entry(Result result, long until) { this.result = result; this.until = until; } }
    /** Adds only the verified original floor's independently provided counts; no extra HTTP read. */
    public static List<String> withVotes(List<String> urls, VoteMetrics votes) {
        Snapshot found = clean(urls);
        return new Snapshot(found,found.sourceTotal,found.totalKnown,found.truncated,votes);
    }
    private static Snapshot clean(List<String> urls) {
        if (urls instanceof Snapshot) return (Snapshot) urls;
        LinkedHashSet<String> found = new LinkedHashSet<>();
        ArrayList<String> retained = new ArrayList<>(); int inspected = 0, chars = 0;
        boolean complete = true, retaining = true;
        if (urls != null) for (String raw : urls) {
            if (++inspected > 4096) { complete = false; break; }
            String value = normalize(raw);
            if (!value.isEmpty() && found.add(value)) {
                if (retaining && retained.size() < MAX_GALLERY_IMAGES && chars + value.length() <= MAX_GALLERY_URL_CHARS) { retained.add(value); chars += value.length(); }
                else retaining = false;
            }
        }
        return new Snapshot(retained, found.size(), complete, !complete || found.size() > retained.size());
    }
    /** Uses the same allowlisted renderer as the native reader, excluding quoted/protected content. */
    public static List<String> extract(String firstPostContent) {
        if (firstPostContent == null || firstPostContent.isEmpty() || firstPostContent.length() > MAX_CONTENT) return Collections.emptyList();
        StringBuilder own = new StringBuilder(firstPostContent.length()); ArrayDeque<String> excluded = new ArrayDeque<>();
        int from = 0, cursor = 0, nextEnd = -1;
        while (cursor < firstPostContent.length()) {
            if (firstPostContent.charAt(cursor) != '[') { cursor++; continue; }
            int end = nextEnd > cursor ? nextEnd : firstPostContent.indexOf(']', cursor + 1); nextEnd = end;
            if (end < 0) break;
            if (end - cursor > 4096) { cursor++; continue; }
            Matcher tag = TAG.matcher(firstPostContent.substring(cursor + 1, end));
            if (!tag.matches()) { cursor++; continue; }
            if (excluded.isEmpty()) own.append(firstPostContent, from, cursor);
            String name = tag.group(2).toLowerCase(Locale.ROOT); boolean closing = !tag.group(1).isEmpty();
            String top = excluded.peek(); boolean opaque = "code".equals(top) || "crypt".equals(top);
            if (opaque) { if (closing && top.equals(name)) excluded.pop(); }
            else if (excluded(name)) {
                if (closing) { if (!excluded.isEmpty() && excluded.peek().equals(name)) excluded.pop(); }
                else { if (excluded.size() >= 64) return Collections.emptyList(); excluded.push(name); }
            } else if (excluded.isEmpty()) own.append(firstPostContent, cursor, end + 1);
            cursor = end + 1; from = cursor;
        }
        if (excluded.isEmpty()) own.append(firstPostContent, from, firstPostContent.length());
        String rendered = PostRenderer.html(own.toString(), false, 16); Matcher sources = SOURCE.matcher(rendered);
        LinkedHashSet<String> urls = new LinkedHashSet<>(); ArrayList<String> retained = new ArrayList<>(); int chars = 0; boolean retaining = true;
        while (sources.find()) {
            String url = normalize(sources.group(1));
            if (!url.isEmpty() && urls.add(url)) {
                if (retaining && retained.size() < MAX_GALLERY_IMAGES && chars + url.length() <= MAX_GALLERY_URL_CHARS) { retained.add(url); chars += url.length(); }
                else retaining = false;
            }
        }
        return new Snapshot(retained, urls.size(), true, urls.size() > retained.size());
    }
    private static boolean excluded(String tag) { return tag.equals("quote") || tag.matches("lessernuke[0-9]?") || tag.equals("crypt") || tag.equals("code") || tag.equals("hide") || tag.equals("hidden") || tag.equals("password") || tag.equals("purchase") || tag.equals("collapse") || tag.equals("spoiler"); }
    /** Trusted attachment images only; never avatars, smiles, editorial covers or arbitrary URLs. */
    public static String normalize(String raw) {
        if (raw == null || raw.length() > 2048) return "";
        String value = raw.trim(); if (value.isEmpty()) return "";
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i)) || Character.isWhitespace(value.charAt(i))) return "";
        if (value.startsWith("./")) {
            Matcher relative = RELATIVE.matcher(value); if (!relative.matches()) return "";
            int year = Integer.parseInt(relative.group(1)), month = Integer.parseInt(relative.group(2)), day = Integer.parseInt(relative.group(3));
            if (!validDate(year, month, day)) return "";
            value = "https://img.nga.cn/attachments/" + value.substring(2);
        } else if (value.startsWith("//")) value = "https:" + value;
        try {
            URI uri = new URI(value); String host = uri.getHost(); if (host == null) return ""; host = host.toLowerCase(Locale.ROOT);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443 || uri.getRawQuery() != null || uri.getRawFragment() != null) return "";
            if (!(host.equals("img.nga.cn") || host.equals("img4.nga.cn") || host.equals("img.nga.178.com") || host.equals("img4.nga.178.com"))) return "";
            String path = uri.getRawPath(); if (path == null || !path.matches("/attachments/[A-Za-z0-9_./-]+\\.(?i:jpe?g|png|gif|webp|bmp|avif)")) return "";
            if (path.indexOf("//") >= 0) return "";
            for (String segment : path.substring(1).split("/")) if (segment.equals(".") || segment.equals("..") || segment.isEmpty()) return "";
            return canonical(host, path);
        } catch (java.net.URISyntaxException invalid) { return ""; }
    }
    private static String canonical(String host, String path) { return "https://" + host + path; }
    private static boolean validDate(int year, int month, int day) {
        if (year < 1 || month < 1 || month > 12 || day < 1) return false;
        int[] days = {31, year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        return day <= days[month - 1];
    }
}
