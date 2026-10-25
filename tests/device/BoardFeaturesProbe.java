package cn.ngapure.verify;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import cn.ngapure.app.CloudForumFavorites;
import cn.ngapure.app.ForumRoute;
import cn.ngapure.app.NativeLinkTarget;
import cn.ngapure.app.NativeLoginView;
import cn.ngapure.app.NgaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Synthetic cloud parsing/local import/routes only. No cloud read or forum navigation. */
public final class BoardFeaturesProbe extends Instrumentation {
    private static final int EXPECTED_CASES = 10;
    private static final String NORMAL = "987654321001", COLD = "-987654321002", COLLECTION_ID = "987654321003";
    private static final String COLLECTION = "stid:" + COLLECTION_ID, LOCAL = "987654321004", EDIT = "987654321005";
    private static final String LATE = "987654321006", META = "987654321007";
    private static final String ICON = "https://img.nga.178.com/attachments/mon_202610/19/SYNTHETIC_ICON.png";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final JSONObject report = new JSONObject();
    private final JSONArray cases = new JSONArray();
    private Activity activity;
    private NgaStore store;
    private SharedPreferences preferences;
    private Map<String,Object> before;
    private Map<String,String> iconsBefore;
    private AccountSnapshot accountBefore;
    private CloudForumFavorites.Snapshot cloud;
    private Map<String,String[]> entries;
    private long deadline;
    private int assertions;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        deadline = SystemClock.elapsedRealtime() + 60000;
        boolean passed = false, restored = false, iconsRestored = false;
        String failure = "NONE";
        try {
            store = new NgaStore(getTargetContext()); preferences = getTargetContext().getSharedPreferences("nga_local", 0);
            check(!store.autoCheckIn() || (store.loggedIn() && NgaStore.today().equals(store.lastCheckInDay())), "SIGN_IN_GUARD_FAILED");
            check(!NativeLoginView.hasPendingAuthorization(getTargetContext(), store.host()), "AUTHORIZATION_PENDING");
            put(report, "onOpenSignInGuardPassed", true); put(report, "pendingAuthorizationGuardPassed", true);
            before = copy(preferences.getAll()); accountBefore = new AccountSnapshot(store);
            activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName("cn.ngapure.app", "cn.ngapure.app.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            check(activity != null, "ACTIVITY_START_FAILED");
            iconsBefore = onMain(() -> new HashMap<>(icons()));
            onMain(() -> {
                for (String key : new String[]{NORMAL, COLD, COLLECTION, LOCAL, EDIT, LATE, META})
                    check(!store.favoriteForums().contains(key) && !catalogContains(key), "SYNTHETIC_KEY_COLLISION");
                return null;
            });
            cloudSchema(); importPreservesLocal(); missingRows(); repeatedImport(); concurrentEdit(); abaEdit();
            cachedMetadata(); distinctRoutes(); invalidRoutes(); emptyImport();
            boolean same = accountBefore.same(store); put(report, "accountAndSettingsUnchanged", same); check(same, "ACCOUNT_OR_SETTINGS_CHANGED");
            passed = true;
        } catch (CheckFailure failed) { failure = failed.code; }
        catch (Exception | AssertionError unavailable) { failure = "BOARD_FEATURES_PROBE_UNAVAILABLE"; }
        finally {
            try { if (before != null) restored = onMainCleanup(this::restoreFavoritePreferences); }
            catch (Exception unavailable) { restored = false; }
            try {
                if (iconsBefore != null) iconsRestored = onMainCleanup(() -> { Map<String,String> current = icons(); current.clear(); current.putAll(iconsBefore); return current.equals(iconsBefore); });
            } catch (Exception unavailable) { iconsRestored = false; }
            if (!restored || !iconsRestored) { passed = false; failure = "LOCAL_STATE_RESTORE_FAILED"; }
            boolean same = accountBefore != null && accountBefore.same(new NgaStore(getTargetContext()));
            put(report, "accountAndSettingsUnchangedAfterCleanup", same);
            if (!same) { passed = false; failure = "ACCOUNT_OR_SETTINGS_CHANGED_AFTER_CLEANUP"; }
            if (before != null) before.clear(); if (iconsBefore != null) iconsBefore.clear(); if (accountBefore != null) accountBefore.clear();
            cloud = null; entries = null;
        }
        put(report, "schema", 1); put(report, "purpose", "LOCAL_BOARD_FAVORITES_AND_ROUTES");
        put(report, "cases", cases); put(report, "scenarioCount", cases.length()); put(report, "expectedScenarioCount", EXPECTED_CASES);
        put(report, "assertionCount", assertions); put(report, "syntheticEntryCount", 3);
        put(report, "allPreferencesExactlyRestored", restored); put(report, "forumIconCacheRestored", iconsRestored);
        put(report, "realCloudReadPerformed", false); put(report, "realCloudMutationPerformed", false);
        put(report, "realCredentialsSubmitted", false); put(report, "realSmsSent", false);
        put(report, "remoteIconsRequested", false); put(report, "actualForumNavigationPerformed", false);
        put(report, "preferenceValuesExported", false); put(report, "passed", passed && cases.length() == EXPECTED_CASES);
        put(report, "failure", passed ? "NONE" : failure);
        Bundle output = new Bundle(); output.putString("safe_board_features_report", report.toString());
        finish(Activity.RESULT_OK, output);
    }

    private void cloudSchema() throws Exception {
        cloud = parse("{\"data\":[[{\"fid\":\"" + NORMAL + "\",\"id\":\"" + NORMAL + "\",\"name\":\"<b>合成普通版块</b>\",\"icon\":\"" + ICON + "\"},"
                + "{\"fid\":0,\"id\":\"" + COLLECTION_ID + "\",\"name\":\"合成合集\",\"icon\":\"" + ICON + "\"},"
                + "{\"fid\":\"" + COLD + "\",\"name\":\"合成冷门子版块\",\"icon\":\"" + ICON + "\"}]]}");
        check(cloud.entries.size() == 3 && cloud.forumCount == 2 && cloud.collectionCount == 1, "CLOUD_SCHEMA_TYPED_COUNTS");
        entries = new LinkedHashMap<>();
        for (CloudForumFavorites.Entry row : cloud.entries) entries.put(row.key, new String[]{row.name, row.icon});
        check(entries.containsKey(NORMAL) && entries.containsKey(COLD) && entries.containsKey(COLLECTION), "CLOUD_SCHEMA_DISTINCT_KEYS");
        check("合成普通版块".equals(entries.get(NORMAL)[0]) && ICON.equals(entries.get(NORMAL)[1]), "CLOUD_SCHEMA_READABLE_METADATA");
        put(report, "cloudSchemaParsed", true); finishCase("SYNTHETIC_CLOUD_SCHEMA");
    }
    private void importPreservesLocal() throws Exception {
        onMain(() -> {
            Set<String> original = store.favoriteForums(); store.toggleFavorite(LOCAL);
            int added = store.importFavoriteForums(entries, store.favoriteForumRevision());
            Set<String> expected = new HashSet<>(original); expected.add(LOCAL); expected.addAll(entries.keySet());
            check(added == 3 && expected.equals(store.favoriteForums()), "IMPORT_PRESERVES_EXISTING_AND_LOCAL_ADDITION");
            check("合成普通版块".equals(store.favoriteForumName(NORMAL)) && ICON.equals(store.favoriteForumIcon(NORMAL)), "IMPORT_CACHES_NORMAL_METADATA");
            check("合成合集".equals(store.favoriteForumName(COLLECTION)) && ICON.equals(store.favoriteForumIcon(COLLECTION)), "IMPORT_CACHES_COLLECTION_METADATA"); return null;
        });
        put(report, "localFavoritesPreserved", true); finishCase("IMPORT_PRESERVES_EXISTING_LOCAL_FAVORITES");
    }
    private void missingRows() throws Exception {
        onMain(() -> {
            @SuppressWarnings("unchecked") List<String[]> rows = (List<String[]>) invoke(activity, "favoriteBoardRows");
            Set<String> seen = new HashSet<>(); int found = 0;
            for (String[] row : rows) {
                check(row.length >= 4 && seen.add(row[0]), "FAVORITE_ROWS_HAVE_UNIQUE_STABLE_KEYS");
                if (entries.containsKey(row[0])) {
                    found++; check(entries.get(row[0])[0].equals(row[1]), "MISSING_CATALOG_ROW_USES_CACHED_NAME");
                    check(ICON.equals(icons().get(row[0])), "MISSING_CATALOG_ROW_USES_CACHED_ICON");
                }
            }
            check(found == 3 && rows.size() == store.favoriteForums().size(), "UNKNOWN_AND_COLLECTION_FAVORITES_VISIBLE"); return null;
        });
        put(report, "unknownAndCollectionRowsVisible", true); finishCase("UNKNOWN_AND_COLLECTION_ROWS_VISIBLE");
    }
    private void repeatedImport() throws Exception {
        onMain(() -> {
            Set<String> old = store.favoriteForums(); int added = store.importFavoriteForums(entries, store.favoriteForumRevision());
            check(added == 0 && old.equals(store.favoriteForums()), "REPEATED_IMPORT_NO_DUPLICATES_OR_DELETIONS");
            for (String key : entries.keySet()) check(entries.get(key)[0].equals(store.favoriteForumName(key)) && ICON.equals(store.favoriteForumIcon(key)), "REPEATED_IMPORT_METADATA_RETAINED");
            return null;
        });
        put(report, "repeatedImportIdempotent", true); finishCase("REPEATED_IMPORT_IS_IDEMPOTENT");
    }
    private void concurrentEdit() throws Exception {
        onMain(() -> {
            long revision = store.favoriteForumRevision(); store.toggleFavorite(EDIT); Set<String> current = store.favoriteForums();
            Map<String,String[]> incoming = new LinkedHashMap<>(); incoming.put(LATE, new String[]{"合成迟到版块", ICON});
            int added = store.importFavoriteForums(incoming, revision);
            check(added == -1 && current.equals(store.favoriteForums()) && !store.favoriteForums().contains(LATE), "CONCURRENT_LOCAL_EDIT_REJECTS_OLD_IMPORT");
            check(store.favoriteForumName(LATE).isEmpty() && store.favoriteForumIcon(LATE).isEmpty(), "STALE_IMPORT_NO_METADATA_WRITE"); return null;
        });
        put(report, "concurrentEditRejectsStaleImport", true); finishCase("CONCURRENT_LOCAL_EDIT_REJECTS_STALE_IMPORT");
    }
    private void abaEdit() throws Exception {
        onMain(() -> {
            long revision = store.favoriteForumRevision(); Set<String> original = store.favoriteForums();
            store.toggleFavorite(NORMAL); store.toggleFavorite(NORMAL);
            check(original.equals(store.favoriteForums()) && store.favoriteForumRevision() > revision, "ABA_EDIT_HAS_NEW_REVISION");
            check(store.importFavoriteForums(entries, revision) == -1 && original.equals(store.favoriteForums()), "ABA_EDIT_REJECTS_OLD_IMPORT"); return null;
        });
        put(report, "abaEditRejectsStaleImport", true); finishCase("ABA_LOCAL_EDITS_REJECT_STALE_IMPORT");
    }
    private void cachedMetadata() throws Exception {
        onMain(() -> {
            store.rememberFavoriteForum(META, "合成缓存版块", ICON); store.toggleFavorite(META); store.toggleFavorite(META);
            check(!store.favoriteForums().contains(META) && "合成缓存版块".equals(store.favoriteForumName(META)) && ICON.equals(store.favoriteForumIcon(META)), "UNFAVORITE_PRESERVES_METADATA");
            store.toggleFavorite(META);
            @SuppressWarnings("unchecked") List<String[]> rows = (List<String[]>) invoke(activity, "favoriteBoardRows");
            boolean found = false; for (String[] row : rows) if (META.equals(row[0])) found = "合成缓存版块".equals(row[1]);
            check(found && ICON.equals(icons().get(META)), "REFAVORITE_REUSES_NAME_AND_ICON"); return null;
        });
        put(report, "cachedMetadataRetained", true); finishCase("METADATA_SURVIVES_UNFAVORITE_REFAVORITE");
    }
    private void distinctRoutes() throws Exception {
        ForumRoute normal = ForumRoute.fromKey(NORMAL), cold = ForumRoute.fromKey(COLD).withParent("650"), collection = ForumRoute.fromKey(COLLECTION);
        check(NORMAL.equals(normal.fid) && normal.stid.isEmpty() && ("fid=" + NORMAL).equals(normal.query()), "ORDINARY_ROUTE_USES_FID");
        check(("fid=" + COLD + "&ff=650").equals(cold.query()), "CHILD_ROUTE_PRESERVES_PARENT");
        check(collection.fid.isEmpty() && COLLECTION_ID.equals(collection.stid) && ("stid=" + COLLECTION_ID).equals(collection.query()), "COLLECTION_ROUTE_USES_STID");
        check(collection.url("ngabbs.com", 2).equals("https://ngabbs.com/thread.php?stid=" + COLLECTION_ID + "&page=2"), "COLLECTION_CANONICAL_URL");
        NativeLinkTarget target = NativeLinkTarget.parse("/thread.php?stid=" + COLLECTION_ID + "&page=2");
        check(target.kind == NativeLinkTarget.FORUM && target.page == 2 && target.forumId.isEmpty()
                && COLLECTION_ID.equals(target.collectionId), "COLLECTION_LINK_STAYS_NATIVE");
        check(COLLECTION.equals(ForumRoute.fromParts(target.forumId, target.collectionId, target.parentForumId).key), "COLLECTION_LINK_PRESERVES_STABLE_KEY");
        target = NativeLinkTarget.parse("/thread.php?fid=" + COLD + "&ff=650&page=3");
        check(target.kind == NativeLinkTarget.FORUM && target.page == 3 && COLD.equals(target.forumId) && "650".equals(target.parentForumId), "CHILD_LINK_PRESERVES_PARENT_AND_PAGE");
        put(report, "nativeBoardRoutesCorrect", true); finishCase("ORDINARY_AND_COLLECTION_ROUTES_REMAIN_DISTINCT");
    }
    private void invalidRoutes() throws Exception {
        for (String key : new String[]{"", "0", "-0", "00042", "stid:0", "stid:-1", "stid:abc", "fid=650", "stid:123&fid=456", "9876543210019"})
            check(!ForumRoute.isKey(key), "INVALID_BOARD_ROUTE_REJECTED");
        boolean ambiguous = false;
        try { ForumRoute.fromParts("650", COLLECTION_ID, ""); } catch (IllegalArgumentException expected) { ambiguous = true; }
        check(ambiguous, "FID_AND_STID_CANNOT_BE_AMBIGUOUS");
        put(report, "invalidRoutesRejected", true); finishCase("INVALID_ROUTES_REJECTED_WITHOUT_NAVIGATION");
    }
    private void emptyImport() throws Exception {
        CloudForumFavorites.Snapshot empty = parse("{\"data\":[[]]}");
        check(empty.entries.isEmpty(), "EMPTY_CLOUD_SCHEMA_IS_VALID");
        onMain(() -> {
            Set<String> original = store.favoriteForums(); Map<String,String[]> nothing = new LinkedHashMap<>();
            check(store.importFavoriteForums(nothing, store.favoriteForumRevision()) == 0 && original.equals(store.favoriteForums()), "EMPTY_IMPORT_PRESERVES_LOCAL");
            check("合成普通版块".equals(store.favoriteForumName(NORMAL)) && ICON.equals(store.favoriteForumIcon(NORMAL)), "EMPTY_IMPORT_PRESERVES_METADATA"); return null;
        });
        put(report, "emptyCloudPreservesLocal", true); finishCase("EMPTY_REMOTE_IMPORT_PRESERVES_LOCAL_FAVORITES");
    }

    private boolean restoreFavoritePreferences() throws Exception {
        Map<String,Object> current = copy(preferences.getAll()); Set<String> keys = new HashSet<>(before.keySet()); keys.addAll(current.keySet());
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : keys) if (key.startsWith("favorite_forum")) {
            if (!before.containsKey(key)) editor.remove(key); else putPreference(editor, key, before.get(key));
        }
        return editor.commit() && before.equals(copy(preferences.getAll()));
    }
    private boolean catalogContains(String key) throws Exception {
        @SuppressWarnings("unchecked") List<String[]> rows = (List<String[]>) field(activity, "catalog");
        for (String[] row : rows) if (key.equals(row[0])) return true; return false;
    }
    private Map<String,String> icons() throws Exception {
        @SuppressWarnings("unchecked") Map<String,String> result = (Map<String,String>) field(activity, "forumIcons"); return result;
    }
    private static CloudForumFavorites.Snapshot parse(String source) throws Exception {
        Method parser = CloudForumFavorites.class.getDeclaredMethod("parse", JSONObject.class); parser.setAccessible(true);
        return (CloudForumFavorites.Snapshot) parser.invoke(null, new JSONObject(source));
    }
    private static Object field(Object target, String name) throws Exception { Field value = target.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(target); }
    private static Object invoke(Object target, String name) throws Exception { Method action = target.getClass().getDeclaredMethod(name); action.setAccessible(true); return action.invoke(target); }
    private void finishCase(String scenario) { JSONObject value = new JSONObject(); put(value, "scenario", scenario); put(value, "passed", true); cases.put(value); }
    private void check(boolean condition, String failure) throws CheckFailure { assertions++; if (!condition) throw new CheckFailure(failure); }
    private long remaining(long maximum) throws CheckFailure { long left = deadline - SystemClock.elapsedRealtime(); if (left <= 0) throw new CheckFailure("PROBE_DEADLINE"); return Math.min(left, maximum); }
    private <T> T onMain(Callable<T> action) throws Exception { return onMainWithin(action, remaining(5000)); }
    private <T> T onMainCleanup(Callable<T> action) throws Exception { return onMainWithin(action, 5000); }
    private <T> T onMainWithin(Callable<T> action, long timeout) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); Object[] values = new Object[1]; Throwable[] errors = new Throwable[1];
        main.post(() -> { try { values[0] = action.call(); } catch (Throwable problem) { errors[0] = problem; } finally { latch.countDown(); } });
        if (!latch.await(timeout, TimeUnit.MILLISECONDS)) throw new CheckFailure("MAIN_THREAD_DEADLINE");
        if (errors[0] instanceof Exception) throw (Exception) errors[0];
        if (errors[0] != null) throw new CheckFailure("MAIN_THREAD_CHECK_FAILED");
        @SuppressWarnings("unchecked") T result = (T) values[0]; return result;
    }
    private static Map<String,Object> copy(Map<String,?> values) {
        Map<String,Object> result = new HashMap<>(); for (Map.Entry<String,?> entry : values.entrySet()) {
            Object value = entry.getValue(); if (value instanceof Set<?>) value = new HashSet<>((Set<?>) value); result.put(entry.getKey(), value);
        } return result;
    }
    private static void putPreference(SharedPreferences.Editor editor, String key, Object value) throws CheckFailure {
        if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Set<?>) {
            Set<String> safe = new HashSet<>(); for (Object item : (Set<?>) value) { if (!(item instanceof String)) throw new CheckFailure("PREFERENCE_TYPE_UNAVAILABLE"); safe.add((String) item); }
            editor.putStringSet(key, safe);
        } else throw new CheckFailure("PREFERENCE_TYPE_UNAVAILABLE");
    }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value); } catch (Exception ignored) { /* Fixed enums, counters and flags only. */ } }
    private static final class AccountSnapshot {
        String cookie, account, host, theme, day, attempt; final int font; final boolean automatic, glass, motion;
        AccountSnapshot(NgaStore store) {
            cookie = store.cookie(); account = store.accountId(); host = store.host(); theme = store.theme(); day = store.lastCheckInDay(); attempt = store.lastCheckInAttempt();
            font = store.fontSize(); automatic = store.autoCheckIn(); glass = store.glassEnabled(); motion = store.motionEnabled();
        }
        boolean same(NgaStore store) {
            return cookie.equals(store.cookie()) && account.equals(store.accountId()) && host.equals(store.host()) && theme.equals(store.theme())
                    && day.equals(store.lastCheckInDay()) && attempt.equals(store.lastCheckInAttempt()) && font == store.fontSize()
                    && automatic == store.autoCheckIn() && glass == store.glassEnabled() && motion == store.motionEnabled();
        }
        void clear() { cookie = account = host = theme = day = attempt = ""; }
    }
    private static final class CheckFailure extends Exception {
        private static final long serialVersionUID = 1L; final String code; CheckFailure(String code) { this.code = code; }
    }
}
