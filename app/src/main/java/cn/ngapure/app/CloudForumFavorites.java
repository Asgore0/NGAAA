package cn.ngapure.app;

import android.content.Context;
import android.text.Html;
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

/** Explicit board/collection favorites only; a write is confirmed by a separate bounded read. */
public final class CloudForumFavorites {
    private static final int MAX_ENTRIES = 2048;
    private final NativeCommunity api;

    /** Capture identity at the explicit UI action, before its worker can be queued. */
    public CloudForumFavorites(Context context) {
        this(context, new NgaStore(context.getApplicationContext()).captureCheckInSession());
    }
    private CloudForumFavorites(Context context, NgaStore.CheckInSession captured) {
        // A page may become anonymous just before the tap. Report this from read()
        // instead of creating an importer that could later pick up another account.
        api = captured.account.isEmpty() ? null : new NativeCommunity(context, captured.host, captured.account);
    }
    /** Capture host/account at the user action; the caller must recheck them before local commit. */
    public CloudForumFavorites(Context context, String expectedHost, String expectedAccount) {
        this(new NativeCommunity(context, expectedHost, expectedAccount));
    }
    public CloudForumFavorites(NativeCommunity api) {
        if (api == null) throw new IllegalArgumentException("缺少云收藏读取服务");
        this.api = api;
    }

    public static final class Entry {
        public final String type, key, fid, stid, id, name, icon;
        private Entry(String type, String key, String fid, String stid, String id, String name, String icon) {
            this.type = type; this.key = key; this.fid = fid; this.stid = stid;
            this.id = id; this.name = name; this.icon = icon;
        }
        public boolean isCollection() { return "collection".equals(type); }
    }
    public static final class Snapshot {
        public final List<Entry> entries;
        public final int forumCount, collectionCount;
        private final Set<String> keys;
        private Snapshot(Map<String, Entry> values) {
            entries = Collections.unmodifiableList(new ArrayList<>(values.values()));
            keys = Collections.unmodifiableSet(new LinkedHashSet<>(values.keySet()));
            int forums = 0;
            for (Entry entry : entries) if (!entry.isCollection()) forums++;
            forumCount = forums; collectionCount = entries.size() - forums;
        }
        public Set<String> keys() { return keys; }
    }

    public enum ChangeStatus { ALREADY_MATCHED, CONFIRMED, REJECTED, UNCONFIRMED }
    public static final class ChangeResult {
        public final String key;
        public final boolean desired;
        public final ChangeStatus status;
        /** Null when a post-write read could not be completed; never a fabricated empty list. */
        public final Snapshot snapshot;
        /** Optional typed cause. No response text, account ID or credentials are retained. */
        public final NativeCommunity.Code failureCode;
        private ChangeResult(String key, boolean desired, ChangeStatus status, Snapshot snapshot,
                NativeCommunity.Code failureCode) {
            this.key=key;this.desired=desired;this.status=status;this.snapshot=snapshot;this.failureCode=failureCode;
        }
        public boolean confirmed() { return status==ChangeStatus.ALREADY_MATCHED||status==ChangeStatus.CONFIRMED; }
    }

    /** Worker thread only; construction and parsing never send a request automatically. */
    public Snapshot read() throws NativeCommunity.CommunityException {
        if (api == null) throw new NativeCommunity.CommunityException(NativeCommunity.Code.LOGIN_REQUIRED, 0);
        return parse(api.readForumFavorites());
    }

    /**
     * Worker thread, explicit user action only. Reads once before a possible single write, then
     * once after it. Repeating this method first observes the desired state and never blindly
     * repeats a timed-out add/del. The UI serializes calls and rechecks its account before commit.
     * Preflight read failures throw without writing; every post-write uncertainty is a typed result.
     */
    public ChangeResult setDesired(String key, boolean desired) throws NativeCommunity.CommunityException {
        ForumRoute route=ForumRoute.fromKey(key);
        if(api==null)throw new NativeCommunity.CommunityException(NativeCommunity.Code.LOGIN_REQUIRED,0);
        NativeCommunity scoped=api.scopedForumFavorites();
        Snapshot before=parse(scoped.readForumFavorites());
        if(before.keys().contains(route.key)==desired)
            return new ChangeResult(route.key,desired,ChangeStatus.ALREADY_MATCHED,before,null);
        NativeCommunity.Code writeFailure=null;
        try { scoped.writeForumFavorite(route,desired); }
        catch(NativeCommunity.CommunityException failure) {
            writeFailure=failure.code;
            if(definiteRefusal(failure.code))
                return new ChangeResult(route.key,desired,ChangeStatus.REJECTED,null,failure.code);
        }
        try {
            Snapshot after=parse(scoped.readForumFavorites());
            if(after.keys().contains(route.key)==desired)
                return new ChangeResult(route.key,desired,ChangeStatus.CONFIRMED,after,null);
            return new ChangeResult(route.key,desired,ChangeStatus.UNCONFIRMED,after,
                    writeFailure==null?NativeCommunity.Code.UNCONFIRMED:writeFailure);
        } catch(NativeCommunity.CommunityException failure) {
            return new ChangeResult(route.key,desired,ChangeStatus.UNCONFIRMED,null,failure.code);
        }
    }
    private static boolean definiteRefusal(NativeCommunity.Code code) {
        return code==NativeCommunity.Code.LOGIN_REQUIRED||code==NativeCommunity.Code.CHALLENGE_REQUIRED
                ||code==NativeCommunity.Code.PERMISSION_DENIED||code==NativeCommunity.Code.RATE_LIMIT;
    }

    /**
     * Keep current local favorites and add cloud entries absent when the read began.
     * A favorite removed locally during the read is not silently resurrected.
     * The caller owns account/navigation/revision checks and the eventual local commit.
     */
    public static Set<String> merge(Set<String> start, Set<String> current, Snapshot remote) {
        if (start == null || current == null || remote == null) throw new IllegalArgumentException("缺少收藏合并快照");
        Set<String> result = new LinkedHashSet<>(current);
        for (String key : remote.keys()) if (!start.contains(key)) result.add(key);
        return Collections.unmodifiableSet(result);
    }

    static Snapshot parse(JSONObject root) throws NativeCommunity.CommunityException {
        if (root == null || root.has("error") && !root.isNull("error")) throw invalid();
        if (root.has("code")) {
            Object code = root.opt("code");
            if (!(code instanceof Number) || ((Number) code).doubleValue() != 0) throw invalid();
        }
        Object data = root.opt("data"), rows = at(data, 0);
        if (!(rows instanceof JSONArray) && !(rows instanceof JSONObject)) throw invalid();
        Map<String, Entry> entries = new LinkedHashMap<>();
        if (rows instanceof JSONArray) {
            JSONArray array = (JSONArray) rows;
            if (array.length() > MAX_ENTRIES) throw invalid();
            for (int i = 0; i < array.length(); i++) add(entries, array.opt(i));
        } else {
            JSONObject object = (JSONObject) rows;
            if (object.length() > MAX_ENTRIES) throw invalid();
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) add(entries, object.opt(keys.next()));
        }
        return new Snapshot(entries);
    }
    private static void add(Map<String, Entry> entries, Object value) throws NativeCommunity.CommunityException {
        if (!(value instanceof JSONObject)) throw invalid();
        JSONObject row = (JSONObject) value;
        if (row.has("error") && !row.isNull("error")) throw invalid();
        for (String field : new String[]{"fid", "id"}) {
            Object raw = row.opt(field);
            if (raw != null && raw != JSONObject.NULL && !(raw instanceof String) && !(raw instanceof Number)) throw invalid();
        }
        String fid = scalar(row.opt("fid")), id = scalar(row.opt("id"));
        String type, key, stid;
        if (!fid.isEmpty() && !"0".equals(fid)) {
            if (!validForum(fid)) throw invalid();
            type = "forum"; key = fid; stid = "";
        } else {
            if (!validPositive(id)) throw invalid();
            type = "collection"; stid = id; key = "stid:" + stid;
        }
        Object rawName = row.opt("name");
        if (rawName != null && rawName != JSONObject.NULL && !(rawName instanceof String)) throw invalid();
        String name = Html.fromHtml(rawName instanceof String ? (String) rawName : "", Html.FROM_HTML_MODE_LEGACY)
                .toString().replaceAll("[\\x00-\\x1f\\x7f]+", " ").trim();
        if (name.length() > 512) throw invalid();
        if (name.isEmpty()) name = ("collection".equals(type) ? "合集 " : "版块 ") + (stid.isEmpty() ? fid : stid);
        String icon = AvatarSources.normalize(scalar(row.opt("icon")));
        if (!entries.containsKey(key)) entries.put(key, new Entry(type, key, fid, stid, id, name, icon));
    }
    private static Object at(Object value, int index) {
        if (value instanceof JSONArray) return ((JSONArray) value).opt(index);
        if (value instanceof JSONObject) return ((JSONObject) value).opt(Integer.toString(index));
        return null;
    }
    private static String scalar(Object value) { return value instanceof String || value instanceof Number ? String.valueOf(value) : ""; }
    private static boolean validPositive(String id) { return ForumRoute.isCollectionId(id); }
    private static boolean validForum(String fid) { return ForumRoute.isForumId(fid); }
    private static NativeCommunity.CommunityException invalid() {
        return new NativeCommunity.CommunityException(NativeCommunity.Code.INVALID_RESPONSE, 0);
    }
}
