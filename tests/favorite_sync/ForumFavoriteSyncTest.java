package cn.ngapure.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Synthetic account scopes and in-memory preferences; never opens Keystore, files, sockets or accounts. */
public final class ForumFavoriteSyncTest {
    private static int scenarios, assertions;
    private static final String A = "synthetic-account-a", B = "synthetic-account-b", HOST = "synthetic-host-b";
    private ForumFavoriteSyncTest() { }
    private static void ok(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    private static void scenario(Runnable action) { action.run(); scenarios++; }
    private static Set<String> keys(String... keys) { return new LinkedHashSet<>(Arrays.asList(keys)); }
    private static Map<String, String[]> remote(String... keys) {
        Map<String, String[]> result = new LinkedHashMap<>();
        for (String key : keys) result.put(key, new String[] {"远端 " + key, "https://img4.nga.cn/synthetic.png"});
        return result;
    }
    private static LocalContext context(String... legacy) {
        LocalContext context = new LocalContext(); context.memory.values.put("favorite_forums", keys(legacy)); return context;
    }
    private static void rejected(Runnable action) {
        try { action.run(); throw new AssertionError("Invalid intent was accepted"); }
        catch (IllegalArgumentException expected) { assertions++; }
    }
    public static void main(String[] args) {
        scenario(() -> {
            LocalContext context = context("7", "650"); NgaStore store = new NgaStore(context);
            store.activateFavoriteScope(A);
            ok(store.favoriteForums().equals(keys("7", "650")), "legacy favorites remain available");
            ok(store.pendingFavoriteChanges(A).isEmpty(), "migration never uploads legacy favorites implicitly");
            int commits = context.memory.commits; long revision = store.favoriteForumRevision();
            store.activateFavoriteScope(A); store.pendingFavoriteChanges(A);
            ok(context.memory.commits == commits && store.favoriteForumRevision() == revision, "same scope activation and reads do not write preferences");
        });
        scenario(() -> {
            LocalContext context = context("7"); NgaStore store = new NgaStore(context);
            long revision = store.queueFavorite(A, "stid:23673143", "合成合集", "https://img4.nga.cn/local.png", true);
            ok(revision > 0 && store.favoriteForums().equals(keys("7", "stid:23673143")), "explicit add appears locally before the network");
            NgaStore recreated = new NgaStore(context); ForumFavoriteSyncState.Change pending = recreated.pendingFavoriteChanges(A).get(0);
            ok(pending.desired && pending.revision == revision && pending.key.equals("stid:23673143"), "add intent survives Store recreation");
            ok(pending.name.equals("合成合集") && recreated.favoriteForumIcon(pending.key).endsWith("local.png"), "queued metadata and independent cache survive");
            try { recreated.pendingFavoriteChanges(A).clear(); throw new AssertionError("Mutable pending list"); }
            catch (UnsupportedOperationException expected) { assertions++; }
        });
        scenario(() -> {
            NgaStore store = new NgaStore(context("650")); store.activateFavoriteScope(A);
            long revision = store.queueFavorite(A, "650", "水区", "icon", false);
            ok(!store.favoriteForums().contains("650") && !store.pendingFavoriteChanges(A).get(0).desired, "offline removal persists as a tombstone");
            store.mergeFavoriteForums(A, remote("650", "7"));
            ok(!store.favoriteForums().contains("650") && store.favoriteForums().contains("7"), "download cannot resurrect pending removal");
            ok(!store.confirmFavorite(A, "650", revision, false, remote("650")), "remote still favorited does not acknowledge a removal");
            ok(store.pendingFavoriteChanges(A).size() == 1, "unconfirmed removal remains retryable");
            ok(store.confirmFavorite(A, "650", revision, false, remote("7")), "observed cloud absence acknowledges removal");
            store.mergeFavoriteForums(A, remote("7"));
            ok(store.pendingFavoriteChanges(A).isEmpty() && !store.favoriteForums().contains("650"), "confirmed removal stays absent after another download");
            ok(store.favoriteForumName("650").equals("水区"), "removing a favorite retains useful metadata");
        });
        scenario(() -> {
            NgaStore store = new NgaStore(context()); long revision = store.queueFavorite(A, "650", "本地名称", "local-icon", true);
            store.mergeFavoriteForums(A, remote("7"));
            ok(store.favoriteForums().equals(keys("7", "650")), "download cannot drop pending addition");
            ok(store.favoriteForumName("650").equals("本地名称"), "unrelated cloud download preserves queued name");
            ok(!store.confirmFavorite(A, "650", revision, true, remote("7")), "missing remote entry does not acknowledge an add");
            ok(store.confirmFavorite(A, "650", revision, true, remote("7", "650")), "independent remote presence acknowledges add");
            ok(store.pendingFavoriteChanges(A).isEmpty() && store.favoriteForums().contains("650"), "only confirmed intent is cleared");
        });
        scenario(() -> {
            NgaStore store = new NgaStore(context()); long first = store.queueFavorite(A, "650", "a", "", true);
            long second = store.queueFavorite(A, "650", "b", "", false);
            long third = store.queueFavorite(A, "650", "c", "", true);
            ok(first < second && second < third && store.pendingFavoriteChanges(A).size() == 1, "rapid changes coalesce with monotonically increasing revisions");
            ok(!store.confirmFavorite(A, "650", first, true, remote("650")), "A-to-B-to-A old add response cannot consume latest add");
            ok(!store.confirmFavorite(A, "650", second, false, remote()), "old removal cannot undo new addition");
            ok(store.pendingFavoriteChanges(A).get(0).revision == third && store.favoriteForums().contains("650"), "latest A intent and visible state survive old replies");
            ok(!store.confirmFavorite(A, "650", third, false, remote()), "same revision with wrong desired bit is rejected");
            ok(store.confirmFavorite(A, "650", third, true, remote("650")), "latest exact intent can finish normally");
        });
        scenario(() -> {
            LocalContext context = context("7"); NgaStore store = new NgaStore(context);
            store.activateFavoriteScope(A); store.mergeFavoriteForums(A, remote("650"));
            long revision = store.queueFavorite(A, "stid:42", "A 合集", "", true);
            store.activateFavoriteScope(B);
            ok(store.favoriteForums().equals(keys("7")), "account switch removes previous account cloud and pending projection");
            ok(store.pendingFavoriteChanges(B).isEmpty() && store.pendingFavoriteChanges(A).size() == 1, "accounts have isolated queues");
            ok(!store.confirmFavorite(A, "stid:42", revision, true, remote("650", "stid:42")), "inactive account callback cannot write local state");
            ok(store.mergeFavoriteForums(A, remote("999")) == -1 && store.favoriteForums().equals(keys("7")), "inactive account download is rejected atomically");
            store.mergeFavoriteForums(B, remote("123")); store.activateFavoriteScope(A);
            ok(store.favoriteForums().equals(keys("7", "650", "stid:42")), "returning to account restores its own cached cloud and latest intent");
            ok(store.pendingFavoriteChanges(A).get(0).revision == revision, "account switching does not lose retry revision");
        });
        scenario(() -> {
            NgaStore store = new NgaStore(context("7")); long revision = store.queueFavorite(A, "650", "合成", "", true);
            store.activateFavoriteScope(HOST);
            ok(store.favoriteForums().equals(keys("7")) && store.pendingFavoriteChanges(HOST).isEmpty(), "host change receives independent projection and queue");
            ok(!store.confirmFavorite(A, "650", revision, true, remote("650")), "old host response cannot acknowledge on a new host");
            store.activateFavoriteScope(A);
            ok(store.favoriteForums().contains("650") && store.pendingFavoriteChanges(A).get(0).revision == revision, "same stable account and host can retry after returning");
        });
        scenario(() -> {
            NgaStore store = new NgaStore(context("7")); long revision = store.queueFavorite(A, "650", "合成", "", true);
            store.activateFavoriteScope("");
            ok(store.favoriteForums().equals(keys("7")) && store.pendingFavoriteChanges(A).size() == 1, "logout hides account projection while preserving pending intent");
            rejected(() -> store.queueFavorite("", "650", "", "", false));
            ok(!store.confirmFavorite(A, "650", revision, true, remote("650")), "logout blocks late account callback");
            store.activateFavoriteScope(A);
            ok(store.pendingFavoriteChanges(A).get(0).revision == revision && store.favoriteForums().contains("650"), "same account login can resume without depending on cookie identity");
        });
        scenario(() -> {
            NgaStore store = new NgaStore(context("7", "650")); store.activateFavoriteScope(A);
            store.mergeFavoriteForums(A, remote("650", "stid:42")); store.mergeFavoriteForums(A, remote("stid:42"));
            ok(store.favoriteForums().equals(keys("7", "stid:42")), "cloud cancellation removes previously observed cloud entry while retaining never-confirmed legacy entry");
            store.mergeFavoriteForums(A, remote());
            ok(store.favoriteForums().equals(keys("7")), "empty cloud snapshot removes account cloud entries only");
        });
        scenario(() -> {
            NgaStore store = new NgaStore(context()); store.queueFavorite(A, "650", "本地名称", "local-icon", true);
            store.mergeFavoriteForums(A, remote("650"));
            ok(store.favoriteForumName("650").equals("本地名称") && store.favoriteForumIcon("650").equals("local-icon"), "pending metadata wins over stale cloud snapshot");
            store.queueFavorite(A, "650", "", "", false);
            ok(store.favoriteForumName("650").equals("本地名称") && store.favoriteForumIcon("650").equals("local-icon"), "empty optional metadata does not erase cache");
            store.queueFavorite(A, "7", String.join("", Collections.nCopies(200, "名")), String.join("", Collections.nCopies(2200, "x")), true);
            ok(store.favoriteForumName("7").length() == 160 && store.favoriteForumIcon("7").length() == 2048, "metadata is bounded durably");
        });
        scenario(() -> {
            NgaStore store = new NgaStore(context()); long fid = store.queueFavorite(A, "42", "版块", "", true);
            long stid = store.queueFavorite(A, "stid:42", "合集", "", true);
            store.confirmFavorite(A, "42", fid, true, remote("42"));
            ok(store.favoriteForums().equals(keys("42", "stid:42")) && store.pendingFavoriteChanges(A).get(0).revision == stid, "equal numeric forum and collection identities cannot collide");
            store.queueFavorite(A, "-7", "负数版块", "", true);
            ok(store.favoriteForums().contains("-7"), "official negative forum identifiers remain supported");
        });
        scenario(() -> {
            LocalContext context = context("7"); NgaStore first = new NgaStore(context), second = new NgaStore(context);
            long one = first.queueFavorite(A, "650", "", "", true), two = second.queueFavorite(A, "650", "", "", false);
            ok(two > one && first.pendingFavoriteChanges(A).get(0).revision == two, "multiple Store instances share persisted sequence and latest intent");
            ok(!first.confirmFavorite(A, "650", one, true, remote("650")), "different Store instance cannot acknowledge stale revision");
        });
        scenario(() -> {
            LocalContext context = context("7"); NgaStore store = new NgaStore(context); store.activateFavoriteScope(A);
            String original = context.memory.getString("favorite_forum_sync_v1", ""); context.memory.failCommit = true;
            try { store.queueFavorite(A, "650", "", "", true); throw new AssertionError("Failed commit accepted"); }
            catch (IllegalStateException expected) { assertions++; }
            ok(store.pendingFavoriteChanges(A).isEmpty() && store.favoriteForums().equals(keys("7")), "failed durable write cannot advertise a queued upload");
            ok(context.memory.getString("favorite_forum_sync_v1", "").equals(original), "failed commit retains last persisted state");
        });
        scenario(() -> {
            LocalContext context = context("7"); NgaStore store = new NgaStore(context); store.activateFavoriteScope(A);
            String original = context.memory.getString("favorite_forum_sync_v1", ""); long revision = store.favoriteForumRevision();
            context.memory.failCommit = true; context.memory.failedCommitChangesLiveMap = true;
            try { store.queueFavorite(A, "650", "未落盘", "", true); throw new AssertionError("Failed live-map commit accepted"); }
            catch (IllegalStateException expected) { assertions++; }
            NgaStore reopened = new NgaStore(context);
            ok(reopened.pendingFavoriteChanges(A).isEmpty() && reopened.favoriteForums().equals(keys("7")), "failed Android-style commit rolls live pending and membership back");
            ok(reopened.favoriteForumRevision() == revision && reopened.favoriteForumName("650").isEmpty(), "failed live-map commit rolls revision and metadata back");
            ok(context.memory.getString("favorite_forum_sync_v1", "").equals(original), "failed live-map commit restores serialized source for later workers");
        });
        scenario(() -> {
            NgaStore store = new NgaStore(context("7")); store.activateFavoriteScope(A); long revision = store.favoriteForumRevision();
            for (String key : Arrays.asList("0", "01", "stid:0", "stid:-7", "bad", "650&action=del")) rejected(() -> store.queueFavorite(A, key, "", "", true));
            rejected(() -> store.queueFavorite("bad scope", "650", "", "", true));
            Map<String, String[]> invalid = remote("650"); invalid.put("bad", new String[] {"", ""});
            ok(store.mergeFavoriteForums(A, invalid) == -1 && store.favoriteForumRevision() == revision, "invalid remote snapshot is atomic and does not change preferences");
            ok(store.favoriteForums().equals(keys("7")) && store.pendingFavoriteChanges(A).isEmpty(), "invalid inputs do not create state");
        });
        scenario(() -> {
            ForumFavoriteSyncState state = new ForumFavoriteSyncState(); state.activate(A, keys("7"));
            long revision = state.queue(A, "650", "合成", "", true, state.projection());
            state = ForumFavoriteSyncState.decode(state.encode());
            ok(state.pending(A).get(0).revision == revision && state.projection().equals(keys("7", "650")), "JSON round trip retains active projection and intent");
            ok(ForumFavoriteSyncState.decode("{bad").pending(A).isEmpty(), "corrupt record cannot manufacture a mutation");
            String malformed = "{\"version\":1,\"initialized\":true,\"active\":\"" + A + "\",\"pending\":{\"" + A + "\":[{\"key\":\"650\",\"desired\":\"true\",\"revision\":1},{\"key\":\"7\",\"desired\":true,\"revision\":1.5}]}}";
            ok(ForumFavoriteSyncState.decode(malformed).pending(A).isEmpty(), "malformed booleans and fractional revisions cannot become intents");
        });
        scenario(() -> {
            LocalContext context = context(); NgaStore store = new NgaStore(context); long previous = 0;
            for (int i = 0; i < 400; i++) {
                long old = store.queueFavorite(A, "650", "", "", true);
                store.queueFavorite(A, "650", "", "", false);
                long latest = store.queueFavorite(A, "650", "", "", true);
                ok(old > previous && latest > old, "ABA sequences never reuse revision");
                ok(!new NgaStore(context).confirmFavorite(A, "650", old, true, remote("650")), "recreated Store rejects old ABA callback");
                ok(store.pendingFavoriteChanges(A).get(0).revision == latest, "stress retains only newest ABA intent");
                previous = latest;
            }
        });
        System.out.println("ForumFavoriteSyncTest: " + scenarios + " scenarios / " + assertions + " assertions passed; network=0, accountAccess=0, remoteWrites=0");
    }

    private static final class LocalContext extends Context {
        final Memory memory = new Memory();
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            if (!"nga_local".equals(name)) throw new AssertionError("Account preferences must not be opened");
            return memory;
        }
    }
    private static final class Memory implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>(); int commits; boolean failCommit, failedCommitChangesLiveMap;
        public Map<String, ?> getAll() { return new HashMap<>(values); }
        public String getString(String key, String fallback) { Object value = values.get(key); return value instanceof String ? (String) value : fallback; }
        @SuppressWarnings("unchecked") public Set<String> getStringSet(String key, Set<String> fallback) {
            Object value = values.get(key); return value instanceof Set ? new HashSet<>((Set<String>) value) : new HashSet<>(fallback);
        }
        public int getInt(String key, int fallback) { Object value = values.get(key); return value instanceof Integer ? (Integer) value : fallback; }
        public long getLong(String key, long fallback) { Object value = values.get(key); return value instanceof Long ? (Long) value : fallback; }
        public float getFloat(String key, float fallback) { return fallback; }
        public boolean getBoolean(String key, boolean fallback) { Object value = values.get(key); return value instanceof Boolean ? (Boolean) value : fallback; }
        public boolean contains(String key) { return values.containsKey(key); }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        public Editor edit() { return new Editor() {
            final Map<String, Object> added = new HashMap<>(); final Set<String> removed = new HashSet<>(); boolean clear;
            public Editor putString(String key, String value) { if (value == null) return remove(key); added.put(key, value); return this; }
            public Editor putStringSet(String key, Set<String> value) { added.put(key, new HashSet<>(value)); return this; }
            public Editor putInt(String key, int value) { added.put(key, value); return this; }
            public Editor putLong(String key, long value) { added.put(key, value); return this; }
            public Editor putFloat(String key, float value) { added.put(key, value); return this; }
            public Editor putBoolean(String key, boolean value) { added.put(key, value); return this; }
            public Editor remove(String key) { removed.add(key); added.remove(key); return this; }
            public Editor clear() { clear = true; return this; }
            public boolean commit() { commits++; if (failCommit) { if (failedCommitChangesLiveMap) apply(); return false; } apply(); return true; }
            public void apply() { if (clear) values.clear(); for (String key : removed) values.remove(key); values.putAll(added); }
        }; }
    }
}
