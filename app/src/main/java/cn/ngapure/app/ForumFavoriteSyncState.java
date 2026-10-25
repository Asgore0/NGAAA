package cn.ngapure.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Durable, account-scoped favorite intent. Contains opaque account scopes, never credentials. */
public final class ForumFavoriteSyncState {
    public static final class Change {
        public final String key, name, icon;
        public final boolean desired;
        public final long revision;
        Change(String key, String name, String icon, boolean desired, long revision) {
            this.key = key; this.name = bounded(name, 160); this.icon = bounded(icon, 2048);
            this.desired = desired; this.revision = revision;
        }
        @Override public String toString() { return "ForumFavoriteSyncState.Change"; }
    }

    private final Set<String> legacy = new LinkedHashSet<>();
    private final Map<String, Set<String>> remote = new LinkedHashMap<>();
    private final Map<String, Map<String, Change>> pending = new LinkedHashMap<>();
    private boolean initialized;
    private String active = "";
    private long sequence;

    public static boolean validKey(String key) {
        return key != null && key.matches("(?:-?[1-9][0-9]{0,11}|stid:[1-9][0-9]{0,17})");
    }
    public static boolean validScope(String scope) {
        return scope != null && scope.matches("[A-Za-z0-9._:-]{1,256}");
    }
    private static String bounded(String value, int length) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), length));
    }
    private static Set<String> clean(Set<String> keys) {
        Set<String> result = new LinkedHashSet<>();
        if (keys != null) for (String key : keys) if (validKey(key)) result.add(key);
        return result;
    }

    /** Switching accounts exposes only legacy local favorites and this account's own projection. */
    public Set<String> activate(String scope, Set<String> existingLocal) {
        if (scope == null || (!scope.isEmpty() && !validScope(scope))) throw new IllegalArgumentException("Invalid favorite scope");
        absorbLocalOnly(existingLocal);
        active = scope;
        return projection();
    }
    private void absorbLocalOnly(Set<String> existingLocal) {
        Set<String> local = clean(existingLocal);
        if (!initialized) { legacy.addAll(local); initialized = true; return; }
        // A legacy caller may still make a device-only edit. Do not upload it implicitly.
        Set<String> managed = new LinkedHashSet<>();
        for (Set<String> keys : remote.values()) managed.addAll(keys);
        for (Map<String, Change> rows : pending.values()) managed.addAll(rows.keySet());
        for (String key : local) if (!managed.contains(key)) legacy.add(key);
        for (Iterator<String> iterator = legacy.iterator(); iterator.hasNext();) {
            String key = iterator.next();
            if (!local.contains(key) && !managed.contains(key)) iterator.remove();
        }
    }
    public boolean active(String scope) { return validScope(scope) && scope.equals(active); }
    public String activeScope() { return active; }
    public boolean initialized() { return initialized; }
    public long queue(String scope, String key, String name, String icon, boolean desired,
                      Set<String> existingLocal) {
        if (!validScope(scope) || !validKey(key)) throw new IllegalArgumentException("Invalid favorite key or scope");
        activate(scope, existingLocal);
        if (sequence == Long.MAX_VALUE) throw new IllegalStateException("Favorite revision exhausted");
        long revision = ++sequence;
        Map<String, Change> rows = pending.get(scope);
        if (rows == null) { rows = new LinkedHashMap<>(); pending.put(scope, rows); }
        rows.put(key, new Change(key, name, icon, desired, revision));
        if (!desired) legacy.remove(key);
        return revision;
    }
    public List<Change> pending(String scope) {
        Map<String, Change> rows = pending.get(scope);
        return rows == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(rows.values()));
    }
    public Set<String> projection() {
        Set<String> keys = new LinkedHashSet<>(legacy);
        Set<String> known = remote.get(active);
        if (known != null) keys.addAll(known);
        for (Change change : pending(active)) {
            if (change.desired) keys.add(change.key); else keys.remove(change.key);
        }
        return keys;
    }
    /** A full remote snapshot replaces only this account's previously observed cloud keys. */
    public boolean merge(String scope, Set<String> snapshot, Set<String> existingLocal) {
        if (!active(scope) || snapshot == null) return false;
        for (String key : snapshot) if (!validKey(key)) return false;
        absorbLocalOnly(existingLocal);
        remote.put(scope, clean(snapshot));
        // Once observed in cloud, a formerly local-only key follows later cloud removals.
        legacy.removeAll(snapshot);
        return true;
    }
    /** Clear exactly the acknowledged intent; an older callback cannot consume a newer toggle. */
    public boolean confirm(String scope, String key, long revision, boolean desired,
                           Set<String> snapshot, Set<String> existingLocal) {
        if (!active(scope) || snapshot == null || snapshot.contains(key) != desired) return false;
        Map<String, Change> rows = pending.get(scope);
        Change change = rows == null ? null : rows.get(key);
        if (change == null || change.revision != revision || change.desired != desired) return false;
        if (!merge(scope, snapshot, existingLocal)) return false;
        rows.remove(key);
        if (rows.isEmpty()) pending.remove(scope);
        return true;
    }

    public String encode() {
        try {
            JSONObject state = new JSONObject().put("version", 1).put("initialized", initialized)
                    .put("active", active).put("sequence", sequence).put("legacy", array(legacy));
            JSONObject remoteRows = new JSONObject(), pendingRows = new JSONObject();
            for (Map.Entry<String, Set<String>> row : remote.entrySet()) remoteRows.put(row.getKey(), array(row.getValue()));
            for (Map.Entry<String, Map<String, Change>> group : pending.entrySet()) {
                JSONArray rows = new JSONArray();
                for (Change change : group.getValue().values()) rows.put(new JSONObject()
                        .put("key", change.key).put("name", change.name).put("icon", change.icon)
                        .put("desired", change.desired).put("revision", change.revision));
                pendingRows.put(group.getKey(), rows);
            }
            return state.put("remote", remoteRows).put("pending", pendingRows).toString();
        } catch (Exception impossible) { throw new IllegalStateException("Cannot encode favorite state", impossible); }
    }
    private static JSONArray array(Set<String> keys) {
        JSONArray result = new JSONArray(); for (String key : keys) result.put(key); return result;
    }
    private static Set<String> keys(JSONArray array) {
        Set<String> result = new LinkedHashSet<>();
        if (array != null) for (int i = 0; i < array.length(); i++) {
            Object key = array.opt(i); if (key instanceof String && validKey((String) key)) result.add((String) key);
        }
        return result;
    }
    public static ForumFavoriteSyncState decode(String encoded) {
        ForumFavoriteSyncState result = new ForumFavoriteSyncState();
        try {
            JSONObject state = new JSONObject(encoded);
            if (state.optInt("version", 0) != 1) return result;
            result.initialized = state.optBoolean("initialized", false);
            String active = state.optString("active", "");
            if (validScope(active)) result.active = active;
            result.sequence = Math.max(0, state.optLong("sequence", 0));
            result.legacy.addAll(keys(state.optJSONArray("legacy")));
            JSONObject remoteRows = state.optJSONObject("remote");
            if (remoteRows != null) for (Iterator<String> it = remoteRows.keys(); it.hasNext();) {
                String scope = it.next(); if (validScope(scope) && remoteRows.optJSONArray(scope) != null)
                    result.remote.put(scope, keys(remoteRows.optJSONArray(scope)));
            }
            JSONObject pendingRows = state.optJSONObject("pending");
            if (pendingRows != null) for (Iterator<String> it = pendingRows.keys(); it.hasNext();) {
                String scope = it.next(); JSONArray rows = pendingRows.optJSONArray(scope);
                if (!validScope(scope) || rows == null) continue;
                Map<String, Change> changes = new LinkedHashMap<>();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null || !(row.opt("key") instanceof String)
                            || !(row.opt("desired") instanceof Boolean)
                            || (!(row.opt("revision") instanceof Integer) && !(row.opt("revision") instanceof Long))) continue;
                    String key = row.optString("key"); long revision = row.optLong("revision", 0);
                    if (!validKey(key) || revision <= 0) continue;
                    Change prior = changes.get(key);
                    if (prior == null || prior.revision < revision) changes.put(key, new Change(key,
                            row.optString("name", ""), row.optString("icon", ""), row.optBoolean("desired"), revision));
                    result.sequence = Math.max(result.sequence, revision);
                }
                if (!changes.isEmpty()) result.pending.put(scope, changes);
            }
        } catch (Exception unavailable) { return new ForumFavoriteSyncState(); }
        return result;
    }
}
