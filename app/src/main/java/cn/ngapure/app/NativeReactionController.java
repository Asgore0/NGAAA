package cn.ngapure.app;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Process-local direct toggles. No credentials, automatic retries or restart replay.
 * The transport still owns its durable unknown-outcome guard and authoritative session checks. */
public final class NativeReactionController {
    public interface Operation { Result execute(boolean resolveOnly) throws Failure; }
    public interface Observer { void changed(String tid, String pid, Snapshot state); }
    public static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        public final boolean uncertain;
        public Failure(String message, boolean uncertain) { super(message); this.uncertain = uncertain; }
    }
    public static final class Result {
        final boolean acknowledged, selectionKnown;
        final int selected;
        final String message;
        private Result(boolean ack, boolean known, int selected, String message) {
            acknowledged = ack; selectionKnown = known; this.selected = selected; this.message = message;
        }
        public static Result acknowledged(boolean known, int selected, String message) {
            if (selected < -1 || selected > 1) throw new IllegalArgumentException("direction");
            return new Result(true, known, selected, message);
        }
        public static Result resolved() { return new Result(false, false, 0, "已解除保护，再次点按才会提交"); }
    }
    public static final class Snapshot {
        public final long version, acknowledgedVersion;
        public final boolean busy, uncertain, selectionKnown;
        public final int selected;
        public final String message;
        private Snapshot(Entry entry) {
            version = entry.version; acknowledgedVersion = entry.acknowledgedVersion;
            busy = entry.busy; uncertain = entry.uncertain; selectionKnown = entry.selectionKnown;
            selected = entry.selected; message = entry.message;
        }
    }
    private static final class Entry {
        final String scope, tid, pid;
        long version, acknowledgedVersion;
        boolean busy, uncertain, selectionKnown, retired;
        int selected;
        String message = "";
        Entry(String scope, String tid, String pid) { this.scope = scope; this.tid = tid; this.pid = pid; }
    }
    public final class Subscription implements AutoCloseable {
        final String scope; final Observer observer; boolean closed;
        private Subscription(String scope, Observer observer) { this.scope = scope; this.observer = observer; }
        @Override public void close() { synchronized (NativeReactionController.this) { closed = true; observers.remove(this); } }
    }
    private final Executor worker, callbacks;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final List<Subscription> observers = new ArrayList<>();
    private long sequence;
    public NativeReactionController(Executor worker, Executor callbacks) {
        if (worker == null || callbacks == null) throw new IllegalArgumentException("executors");
        this.worker = worker; this.callbacks = callbacks;
    }
    public synchronized Subscription observe(String scope, Observer observer) {
        if (scope == null || observer == null) throw new IllegalArgumentException("observer");
        activateScope(scope);
        Subscription value = new Subscription(scope, observer); observers.add(value); return value;
    }
    public synchronized void activateScope(String scope) {
        // Returning to the exact session may watch its still-running operation again.
        // Retirement already discarded old selection; this does not send/retry anything.
        for (Entry entry : entries.values()) if (entry.busy && entry.scope.equals(scope)) entry.retired = false;
    }
    public synchronized Snapshot snapshot(String scope, String tid, String pid) {
        Entry value = entries.get(key(scope, tid, pid));
        return value == null || value.retired ? null : new Snapshot(value);
    }
    public synchronized long revision() { return sequence; }
    /** Account/host/token changes retire state, but cannot unlock an already running toggle. */
    public synchronized void retireScope(String scope) {
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next(); if (!entry.scope.equals(scope)) continue;
            entry.retired = true; entry.selectionKnown = false; entry.selected = 0; entry.acknowledgedVersion = 0;
            if (!entry.busy) iterator.remove();
        }
    }
    /** false means no work was accepted (already in flight or awaiting explicit outcome review). */
    public boolean submit(String scope, String tid, String pid, boolean resolveOnly, Operation operation) {
        if (operation == null) throw new IllegalArgumentException("operation");
        final Entry entry; final long version;
        synchronized (this) {
            String key = key(scope, tid, pid); Entry existing = entries.get(key);
            if (existing != null && (existing.busy || existing.uncertain != resolveOnly)) return false;
            if (existing == null && resolveOnly) return false;
            if (existing == null) { existing = new Entry(scope, tid, pid); entries.put(key, existing); }
            entry = existing; entry.busy = true; entry.message = resolveOnly ? "正在解除本机保护…" : "正在提交…";
            version = entry.version = ++sequence; trim();
        }
        publish(entry);
        try {
            worker.execute(() -> {
                try {
                    Result result = operation.execute(resolveOnly);
                    if (result == null || result.acknowledged == resolveOnly) throw new Failure("返回状态无法确认，请先核对帖子", !resolveOnly);
                    finish(entry, version, result, null);
                } catch (Failure failure) { finish(entry, version, null, failure); }
                catch (RuntimeException failure) { finish(entry, version, null, new Failure("操作结果无法确认，请先核对帖子", !resolveOnly)); }
            });
        } catch (RejectedExecutionException full) {
            finish(entry, version, null, new Failure("操作队列繁忙，请稍后再次点按", false));
            return false;
        }
        return true;
    }
    private void finish(Entry entry, long version, Result result, Failure failure) {
        synchronized (this) {
            if (entry.version != version || !entry.busy) return;
            entry.busy = false; entry.version = ++sequence;
            if (entry.retired) { entries.remove(key(entry.scope, entry.tid, entry.pid)); return; }
            if (failure != null) {
                entry.uncertain |= failure.uncertain; entry.message = failure.getMessage();
            } else {
                entry.uncertain = false; entry.message = result.message == null ? "操作已确认" : result.message;
                if (result.acknowledged) {
                    entry.acknowledgedVersion = entry.version;
                    if (result.selectionKnown) { entry.selectionKnown = true; entry.selected = result.selected; }
                }
            }
        }
        publish(entry);
    }
    private void publish(Entry entry) {
        final Snapshot snapshot; final List<Subscription> targets;
        synchronized (this) { snapshot = new Snapshot(entry); targets = new ArrayList<>(observers); }
        for (Subscription target : targets) {
            if (!target.scope.equals(entry.scope)) continue;
            callbacks.execute(() -> {
                synchronized (NativeReactionController.this) {
                    if (target.closed || entry.retired || entry.version != snapshot.version) return;
                }
                target.observer.changed(entry.tid, entry.pid, snapshot);
            });
        }
    }
    private void trim() {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (entries.size() > 256 && iterator.hasNext()) if (!iterator.next().getValue().busy) iterator.remove();
    }
    private static String key(String scope, String tid, String pid) {
        if (scope == null || scope.isEmpty() || tid == null || !tid.matches("[1-9][0-9]{0,17}")
                || pid == null || !pid.matches("0|[1-9][0-9]{0,17}")) throw new IllegalArgumentException("reaction route");
        return scope + "\n" + tid + ":" + pid;
    }
}
