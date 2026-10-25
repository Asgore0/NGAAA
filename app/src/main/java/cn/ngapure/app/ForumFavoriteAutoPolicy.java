package cn.ngapure.app;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Deterministic scheduling only; identity is an opaque exact-session digest, never credentials. */
public final class ForumFavoriteAutoPolicy {
    public enum Result { SUCCESS, TRANSIENT, BLOCKED, STALE }
    public static final long FRESH_FOR_MS = 60_000L;
    private static final long[] RETRY_MS = {3_000L, 10_000L, 30_000L, 60_000L};
    private final Set<Object> foreground = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    private String identity = "";
    private boolean requested, explicit, running, loaded, blocked;
    private long lastSuccess, retryAt = -1;
    private int retries;

    public static final class Ticket {
        private final String identity;
        private Ticket(String identity) { this.identity = identity; }
        public boolean matches(String candidate) { return identity.equals(candidate); }
    }

    /** A changed cookie, account or host invalidates freshness and the previous refusal. */
    public boolean identity(String value) {
        if (value == null) value = "";
        if (identity.equals(value)) return false;
        identity = value; loaded = false; blocked = false; lastSuccess = 0;
        requested = false; explicit = false; retries = 0; retryAt = -1;
        if (!value.isEmpty() && !foreground.isEmpty()) requested = true;
        return true;
    }
    public void foreground(Object token, String value, long now, boolean pending) {
        if (token == null) throw new IllegalArgumentException("Missing foreground token");
        boolean wasEmpty = foreground.isEmpty();
        boolean added = foreground.add(token);
        boolean changed = identity(value);
        if (wasEmpty && added) { retries = 0; retryAt = -1; }
        if (changed || wasEmpty && added) refresh(now, false, pending);
    }
    public void background(Object token) {
        foreground.remove(token);
        if (foreground.isEmpty()) {
            retryAt = -1;
            if (!explicit) requested = false;
        }
    }
    public boolean foreground() { return !foreground.isEmpty(); }
    public boolean running() { return running; }
    public boolean loaded() { return loaded; }

    /** A deliberate tap/retry is immediate even if the Activity pauses before the worker starts. */
    public void explicit() {
        if (identity.isEmpty()) return;
        requested = true; explicit = true; blocked = false; retries = 0; retryAt = -1;
    }
    public void refresh(long now, boolean force, boolean pending) {
        if (force) { explicit(); return; }
        if (identity.isEmpty() || foreground.isEmpty() || blocked || retries >= RETRY_MS.length) return;
        if (pending || !loaded || now < lastSuccess || now - lastSuccess >= FRESH_FOR_MS) {
            requested = true;
            retryAt = -1;
        }
    }
    /** Called only on an actual unavailable-to-available transition, not every capability callback. */
    public void networkAvailable() {
        if (identity.isEmpty() || foreground.isEmpty() || blocked) return;
        retries = 0; retryAt = -1; requested = true;
    }
    public Ticket take(long now) {
        if (running || identity.isEmpty()) return null;
        if (!requested && retryAt >= 0 && now >= retryAt && !foreground.isEmpty()) {
            requested = true; retryAt = -1;
        }
        if (!requested) return null;
        requested = false; explicit = false; running = true;
        return new Ticket(identity);
    }
    public void complete(Ticket ticket, Result result, long now) {
        if (!running || ticket == null) throw new IllegalStateException("No active synchronization");
        running = false;
        if (!ticket.matches(identity) || result == Result.STALE) return;
        if (result == Result.SUCCESS) {
            loaded = true; lastSuccess = now; blocked = false; retries = 0; retryAt = -1;
        } else if (result == Result.BLOCKED) {
            blocked = true; retryAt = -1;
        } else if (!foreground.isEmpty() && !requested && retries < RETRY_MS.length) {
            retryAt = now + RETRY_MS[retries++];
        }
    }
    /** -1 means no timer. A request arriving during a read/write survives complete(). */
    public long delay(long now) {
        if (running) return -1;
        if (requested && !identity.isEmpty()) return 0;
        if (retryAt < 0 || foreground.isEmpty() || identity.isEmpty()) return -1;
        return Math.max(0, retryAt - now);
    }
}
