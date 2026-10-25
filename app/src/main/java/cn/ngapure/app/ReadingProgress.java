package cn.ngapure.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Device-local reading geometry. Call save only for a successfully loaded thread page. */
final class ReadingProgress {
    static final int MAX_ENTRIES = 128, MAX_JSON_CHARS = 64 * 1024;
    static final String PREFERENCES = "nga_reading_progress", KEY = "entries_v1";
    private static final long MAX_TIME = 253402300799999L;
    // The application has one process; protect commits and live-map rollback across instances.
    private static final Object LOCK = new Object();
    private final SharedPreferences preferences;
    private final Clock clock;

    interface Clock { long now(); }

    static final class Entry {
        final String tid;
        final int page;
        final long updatedAt;
        final ReaderAnchor anchor;
        private final String scope;
        private Entry(String scope, String tid, int page, long updatedAt, ReaderAnchor anchor) {
            this.scope = scope; this.tid = tid; this.page = page;
            this.updatedAt = updatedAt; this.anchor = anchor;
        }
    }

    ReadingProgress(Context context) {
        this(context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
                System::currentTimeMillis);
    }

    ReadingProgress(SharedPreferences preferences, Clock clock) {
        if (preferences == null || clock == null) throw new IllegalArgumentException("缺少阅读进度存储配置");
        this.preferences = preferences; this.clock = clock;
    }

    Entry get(String scope, String tid) {
        if (!validScope(scope) || !validTid(tid)) return null;
        synchronized (LOCK) {
            List<Entry> entries = read();
            if (entries == null) return null;
            for (Entry entry : entries) if (entry.scope.equals(scope) && entry.tid.equals(tid)) return entry;
            return null;
        }
    }

    /** One bounded read for a whole local-topic list; values and geometry are immutable. */
    Map<String, Entry> snapshot(String scope) {
        if (!validScope(scope)) return Collections.emptyMap();
        synchronized (LOCK) {
            List<Entry> entries = read();
            if (entries == null || entries.isEmpty()) return Collections.emptyMap();
            Map<String, Entry> result = new LinkedHashMap<>();
            for (Entry entry : entries) if (entry.scope.equals(scope)) result.put(entry.tid, entry);
            return Collections.unmodifiableMap(result);
        }
    }

    /** Invalid input or an unsuccessful durable save leaves the previous progress intact. */
    boolean save(String scope, String tid, int page, ReaderAnchor anchor) {
        if (!validScope(scope) || !validTid(tid) || page < 1 || page > NavigationState.MAX_PAGE
                || (anchor != null && !anchor.matches(tid, page))) return false;
        final long now;
        try { now = clock.now(); } catch (RuntimeException unavailable) { return false; }
        if (now < 1 || now > MAX_TIME) return false;
        synchronized (LOCK) {
            List<Entry> entries = read();
            if (entries == null) return false;
            entries.removeIf(entry -> entry.scope.equals(scope) && entry.tid.equals(tid));
            entries.add(0, new Entry(scope, tid, page, now, anchor));
            // Save order, rather than untrusted wall-clock timestamps, controls eviction.
            while (entries.size() > MAX_ENTRIES) entries.remove(entries.size() - 1);
            return write(entries);
        }
    }

    boolean remove(String scope, String tid) {
        if (!validScope(scope) || !validTid(tid)) return false;
        synchronized (LOCK) {
            List<Entry> entries = read();
            if (entries == null) return false;
            return !entries.removeIf(entry -> entry.scope.equals(scope) && entry.tid.equals(tid)) || write(entries);
        }
    }

    /** Clears one account/host scope only; it never changes other accounts' progress. */
    boolean clear(String scope) {
        if (!validScope(scope)) return false;
        synchronized (LOCK) {
            List<Entry> entries = read();
            if (entries == null) return false;
            return !entries.removeIf(entry -> entry.scope.equals(scope)) || write(entries);
        }
    }

    private List<Entry> read() {
        try { return decode(preferences.getAll().get(KEY)); }
        // Failure to inspect storage is not permission to replace it with an empty index.
        catch (RuntimeException unavailable) { return null; }
    }

    static List<Entry> decode(Object raw) {
        List<Entry> entries = new ArrayList<>();
        if (!(raw instanceof String)) return entries;
        String value = (String) raw;
        if (value.isEmpty() || value.length() > MAX_JSON_CHARS || !boundedNesting(value)) return entries;
        try {
            JSONTokener parser = new JSONTokener(value);
            Object decoded = parser.nextValue();
            if (!(decoded instanceof JSONObject) || parser.nextClean() != 0) return entries;
            JSONObject root = (JSONObject) decoded;
            if (root.length() != 2 || integer(root.opt("v"), 1, 1) != 1) return entries;
            Object records = root.opt("entries");
            if (!(records instanceof JSONArray)) return entries;
            JSONArray array = (JSONArray) records;
            if (array.length() > MAX_ENTRIES) return entries;
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                if (!(item instanceof JSONArray)) continue;
                JSONArray row = (JSONArray) item;
                if (row.length() != 5 || !(row.opt(0) instanceof String) || !(row.opt(1) instanceof String)) continue;
                String scope = (String) row.opt(0), tid = (String) row.opt(1);
                int page = integer(row.opt(2), 1, NavigationState.MAX_PAGE);
                long updatedAt = whole(row.opt(3), 1, MAX_TIME);
                if (!validScope(scope) || !validTid(tid) || page < 1 || updatedAt < 1) continue;
                ReaderAnchor anchor = null;
                Object geometry = row.opt(4);
                if (geometry != JSONObject.NULL) {
                    if (!(geometry instanceof JSONArray)) continue;
                    JSONArray numbers = (JSONArray) geometry;
                    if (numbers.length() != 4) continue;
                    anchor = ReaderAnchor.create(tid, page, integer(numbers.opt(0), 0, 2000000),
                            integer(numbers.opt(1), 0, 10000), integer(numbers.opt(2), 1, 100000),
                            integer(numbers.opt(3), 0, NavigationState.MAX_SCROLL));
                    if (anchor == null) continue;
                }
                if (seen.add(scope + ":" + tid)) entries.add(new Entry(scope, tid, page, updatedAt, anchor));
            }
        } catch (Exception malformed) { entries.clear(); }
        return entries;
    }

    private static boolean validScope(String scope) { return scope != null && scope.matches("[a-f0-9]{64}"); }
    private static boolean validTid(String tid) { return tid != null && tid.matches("[1-9][0-9]{0,17}"); }
    private static int integer(Object value, int minimum, int maximum) {
        return (int) whole(value, minimum, maximum);
    }
    private static long whole(Object value, long minimum, long maximum) {
        // No string coercion, floats, decimals, BigInteger overflow, booleans or null.
        if (!(value instanceof Integer || value instanceof Long)) return -1;
        long number = ((Number) value).longValue();
        return number >= minimum && number <= maximum ? number : -1;
    }

    /** Reject nesting and Android JSON's nonstandard quote/comment syntax before parsing. */
    private static boolean boundedNesting(String value) {
        int depth = 0; boolean string = false, escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (string) {
                if (escaped) {
                    escaped = false;
                    if (c == 'u') {
                        if (i + 4 >= value.length()) return false;
                        for (int j = 1; j <= 4; j++) if (Character.digit(value.charAt(i + j), 16) < 0) return false;
                        i += 4;
                    } else if ("\"\\/bfnrt".indexOf(c) < 0) return false;
                }
                else if (c == '\\') escaped = true;
                else if (c == '"') string = false;
                else if (c < 0x20) return false;
            } else if (c == '"') string = true;
            else if (c == '[' || c == '{') { if (++depth > 4) return false; }
            else if (c == ']' || c == '}') { if (--depth < 0) return false; }
            else if (c == ',' || c == ':' || c == ' ' || c == '\t' || c == '\n' || c == '\r') { /* JSON separators. */ }
            else {
                int start = i;
                while (i < value.length() && "{}[],:\" \t\n\r".indexOf(value.charAt(i)) < 0) {
                    if (i - start >= 32) return false;
                    i++;
                }
                String token = value.substring(start, i); i--;
                if (!("true".equals(token) || "false".equals(token) || "null".equals(token)
                        || token.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))) return false;
            }
        }
        return depth == 0 && !string && !escaped;
    }

    private boolean write(List<Entry> entries) {
        final String encoded;
        try {
            JSONArray records = new JSONArray();
            for (Entry entry : entries) {
                JSONArray row = new JSONArray();
                row.put(entry.scope); row.put(entry.tid); row.put(entry.page); row.put(entry.updatedAt);
                if (entry.anchor == null) row.put(JSONObject.NULL);
                else {
                    JSONArray geometry = new JSONArray();
                    geometry.put(entry.anchor.floor); geometry.put(entry.anchor.fraction);
                    geometry.put(entry.anchor.columnWidth); geometry.put(entry.anchor.scrollY); row.put(geometry);
                }
                records.put(row);
            }
            JSONObject root = new JSONObject(); root.put("v", 1); root.put("entries", records);
            encoded = root.toString();
            if (encoded == null || encoded.length() > MAX_JSON_CHARS) return false;
        } catch (Exception unavailable) { return false; }
        final Object previous;
        try { previous = preferences.getAll().get(KEY); }
        catch (RuntimeException unavailable) { return false; }
        try { if (preferences.edit().putString(KEY, encoded).commit()) return true; }
        catch (RuntimeException unavailable) { /* Restore Android's already mutated live map too. */ }
        restore(previous);
        return false;
    }

    @SuppressWarnings("unchecked")
    private void restore(Object previous) {
        try {
            SharedPreferences.Editor editor = preferences.edit();
            if (previous instanceof String) editor.putString(KEY, (String) previous);
            else if (previous instanceof Integer) editor.putInt(KEY, (Integer) previous);
            else if (previous instanceof Long) editor.putLong(KEY, (Long) previous);
            else if (previous instanceof Float) editor.putFloat(KEY, (Float) previous);
            else if (previous instanceof Boolean) editor.putBoolean(KEY, (Boolean) previous);
            else if (previous instanceof Set) editor.putStringSet(KEY, new HashSet<>((Set<String>) previous));
            else editor.remove(KEY);
            // A repeated disk failure still restores the live map on Android.
            editor.commit();
        } catch (RuntimeException unavailable) { /* Local geometry must not crash navigation. */ }
    }
}
