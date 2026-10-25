package cn.ngapure.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Derived UI ownership only. Network requests still capture their actual credentials. */
final class SessionScopeSnapshot {
    final String contentScope, localDataScope;
    final boolean loggedIn;
    private final String host;
    private final boolean cookieAvailable;

    private SessionScopeSnapshot(String host, String cookie, String account) {
        this.host = host;
        contentScope = digest(host, cookie);
        localDataScope = digest(host, account);
        loggedIn = !account.isEmpty();
        cookieAvailable = !cookie.isEmpty();
    }

    static SessionScopeSnapshot derive(String host, String cookie, String account) {
        if (host == null || cookie == null || account == null) throw new IllegalArgumentException("Missing session scope");
        return new SessionScopeSnapshot(host, cookie, account);
    }

    private static String digest(String host, String identity) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest((host + "\0" + identity).getBytes(StandardCharsets.UTF_8));
            char[] digits = "0123456789abcdef".toCharArray(), value = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                value[i * 2] = digits[(bytes[i] & 255) >>> 4];
                value[i * 2 + 1] = digits[bytes[i] & 15];
            }
            return new String(value);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static final class Key {
        final String host, encryptedCookie;
        Key(String host, String encryptedCookie) {
            if (host == null || encryptedCookie == null) throw new IllegalArgumentException("Missing session source");
            this.host = host; this.encryptedCookie = encryptedCookie;
        }
        boolean same(Key other) {
            return other != null && host.equals(other.host) && encryptedCookie.equals(other.encryptedCookie);
        }
    }

    interface Source {
        /** Must read the authoritative source, including a failed durable-write rollback. */
        Key key();
        /** Resolves exactly these captured bytes, with no source/cache monitor held. */
        SessionScopeSnapshot resolve(Key captured);
        default void lookup(boolean hit) { }
        default void retry() { }
    }

    /** One entry per store. It retains encrypted source bytes and derived hashes, never plaintext credentials. */
    static final class Cache {
        private static final int MAX_ENCODED_CHARS = 128 * 1024;
        private Key key;
        private SessionScopeSnapshot snapshot;
        private long epoch, sequence, publishedSequence;

        static final class Ticket {
            final long epoch, sequence;
            Ticket(long epoch, long sequence) { this.epoch = epoch; this.sequence = sequence; }
        }

        synchronized Ticket beginRead() { return new Ticket(epoch, ++sequence); }
        private synchronized long epoch() { return epoch; }

        SessionScopeSnapshot capture(Source source) {
            long startedEpoch = epoch(); Ticket ticket = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                Key before = source.key(); SessionScopeSnapshot hit;
                synchronized (this) {
                    hit = snapshot != null && before.same(key) ? snapshot : null;
                    if (hit == null && ticket == null) {
                        // A source read started before lifecycle clear must not evict a
                        // later publication, or invalidate a newer in-flight ticket.
                        if (startedEpoch == epoch) {
                            ticket = new Ticket(startedEpoch, ++sequence);
                            key = null; snapshot = null;
                        } else ticket = new Ticket(startedEpoch, 0);
                    }
                }
                source.lookup(hit != null);
                if (hit != null) return hit;
                SessionScopeSnapshot resolved = source.resolve(before);
                Key after = source.key();
                if (before.same(after) && before.host.equals(resolved.host)) {
                    seed(ticket, before, after, resolved);
                    return resolved;
                }
                source.retry();
            }
            discard(ticket);
            throw new IllegalStateException("登录状态持续变化，请重试");
        }

        /** A fresh credential read may prime derived ownership without retaining its plaintext. */
        void seed(Key before, Key after, SessionScopeSnapshot resolved) {
            seed(beginRead(), before, after, resolved);
        }
        synchronized void seed(Ticket ticket, Key before, Key after, SessionScopeSnapshot resolved) {
            boolean credentialStateMatches = before.encryptedCookie.isEmpty() == !resolved.cookieAvailable;
            if (!current(ticket)) {
                // A later read may still be pending. A known failed decrypt must not
                // leave an earlier success reusable, but cannot erase a newer success.
                if (ticket != null && ticket.epoch == epoch && !credentialStateMatches
                        && before.same(after) && before.same(key) && publishedSequence <= ticket.sequence) {
                    key = null; snapshot = null;
                }
                return;
            }
            key = null; snapshot = null;
            // Failed Keystore reads must be retried. A host/source change while resolving
            // may return a valid instantaneous snapshot, but must not poison this cache.
            if (before.same(after) && before.host.equals(resolved.host) && credentialStateMatches
                    && before.encryptedCookie.length() <= MAX_ENCODED_CHARS) {
                key = before; snapshot = resolved; publishedSequence = ticket.sequence;
            }
        }

        private boolean current(Ticket ticket) { return ticket != null && ticket.epoch == epoch && ticket.sequence == sequence; }
        synchronized void discard(Ticket ticket) { if (current(ticket)) { key = null; snapshot = null; } }
        synchronized void clear() { epoch++; sequence++; key = null; snapshot = null; }
    }
}
