package cn.ngapure.app;

import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Cancellation is opt-in per worker scope. Unscoped writes and background jobs stay independent. */
public final class RequestCancellation {
    private static final ThreadLocal<Token> CURRENT = new ThreadLocal<>();
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    // At most three screen-read workers can own sockets. Cancellation never runs socket I/O on UI.
    private static final ThreadPoolExecutor DISCONNECTS = new ThreadPoolExecutor(3, 3, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(12), new ThreadFactory() {
                @Override public Thread newThread(Runnable action) {
                    Thread thread = new Thread(action, "NgaReadCancel-" + THREAD_ID.incrementAndGet());
                    thread.setDaemon(true); return thread;
                }
            });
    static { DISCONNECTS.allowCoreThreadTimeOut(true); }

    private RequestCancellation() { }

    /** Returns false only if bounded workers are saturated; caller's normal finally still owns cleanup. */
    public static boolean disconnectAsync(final HttpURLConnection connection) {
        if (connection == null) return true;
        try {
            DISCONNECTS.execute(new Runnable() {
                @Override public void run() { disconnectSafely(connection); }
            });
            return true;
        } catch (RejectedExecutionException saturated) { return false; }
    }
    private static void disconnectSafely(HttpURLConnection connection) {
        try { connection.disconnect(); }
        catch (RuntimeException ignored) { /* Original network worker still owns its finally cleanup. */ }
    }

    public static final class Token {
        private final Set<Registration> active = new HashSet<>();
        private volatile boolean cancelled;
        private volatile boolean deferredDisconnect;

        public boolean isCancelled() { return cancelled; }
        /**
         * Immediately signals cancellation, then disconnects active sockets on bounded workers.
         * False means a saturated disconnect executor deferred some cleanup to the normal worker
         * finally/socket timeout. The cancellation flag is still set; no new request can start.
         */
        public boolean cancel() {
            ArrayList<Registration> connections;
            synchronized (this) {
                if (cancelled) return !deferredDisconnect;
                cancelled = true; connections = new ArrayList<>(active);
            }
            for (Registration connection : connections) {
                if (!connection.disconnectAsync()) deferredDisconnect = true;
            }
            return !deferredDisconnect;
        }
        private synchronized Registration register(HttpURLConnection connection) throws InterruptedIOException {
            if (cancelled) throw cancelled();
            Registration registration = new Registration(this, connection); active.add(registration); return registration;
        }
        private synchronized void remove(Registration registration) { active.remove(registration); }
    }

    public static Scope bind(Token token) {
        if (token == null) throw new IllegalArgumentException("缺少请求取消标记");
        Scope scope = new Scope(CURRENT.get(), Thread.currentThread()); CURRENT.set(token); return scope;
    }
    public static final class Scope implements AutoCloseable {
        private final Token previous;
        private final Thread owner;
        private boolean closed;
        private Scope(Token previous, Thread owner) { this.previous = previous; this.owner = owner; }
        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner) throw new IllegalStateException("请求作用域必须在原线程关闭");
            closed = true;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    /** Hook immediately after openConnection, before any operation that can connect. */
    public static Registration track(HttpURLConnection connection) throws InterruptedIOException {
        check(); Token token = CURRENT.get();
        return token == null ? Registration.NONE : token.register(connection);
    }
    public static final class Registration implements AutoCloseable {
        private static final Registration NONE = new Registration(null, null);
        private final Token token;
        private final HttpURLConnection connection;
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private volatile boolean closed;
        private final Runnable disconnect = new Runnable() {
            @Override public void run() {
                if (closed) return;
                disconnectSafely(connection);
            }
        };
        private Registration(Token token, HttpURLConnection connection) { this.token = token; this.connection = connection; }
        private boolean disconnectAsync() {
            if (closed || !scheduled.compareAndSet(false, true)) return true;
            try { DISCONNECTS.execute(disconnect); return true; }
            catch (RejectedExecutionException saturated) { scheduled.set(false); return false; }
        }
        @Override public void close() {
            if (token == null || closed) return;
            closed = true; token.remove(this); DISCONNECTS.remove(disconnect);
        }
    }

    /** Preserves a pre-existing interrupt flag; token cancellation need not interrupt its caller. */
    public static void check() throws InterruptedIOException {
        Token token = CURRENT.get();
        if (Thread.currentThread().isInterrupted() || (token != null && token.isCancelled())) throw cancelled();
    }
    private static InterruptedIOException cancelled() { return new InterruptedIOException("请求已取消"); }
}
