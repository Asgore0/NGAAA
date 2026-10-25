package cn.ngapure.autoverify;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.widget.LinearLayout;
import android.widget.TextView;
import cn.ngapure.app.CloudForumFavorites;
import cn.ngapure.app.ForumFavoriteSyncState;
import cn.ngapure.app.ForumFavoritesCoordinator;
import cn.ngapure.app.ForumRoute;
import cn.ngapure.app.NativeCommunity;
import cn.ngapure.app.NativeLoginView;
import cn.ngapure.app.NgaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Explicit opt-in, staged cold-start checks. Only one public board may be changed. */
public final class AutoFavoriteSyncProbe extends Instrumentation {
    private static final String TARGET = "754";
    private static final int MAX_JOURNAL_BYTES = 2 * 1024 * 1024;
    private static final String[] MODES = {"prepare", "check-startup", "online", "offline-queue", "check-retry", "cleanup"};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final JSONObject checks = new JSONObject();
    private final JSONObject report = new JSONObject();
    private Context context;
    private Activity activity;
    private NgaStore store;
    private SharedPreferences preferences;
    private CloudForumFavorites cloud;
    private AtomicFile journal;
    private JSONObject saved;
    private Set<String> baseline;
    private String mode = "", scope = "", host = "", account = "", cookie = "";
    private boolean authorized, originalTarget, journalLoaded;
    private int reads, assertions, starClicks, restoreSubmissions;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        authorized = arguments != null && "true".equals(arguments.getString("realAutomaticFavorites"));
        mode = arguments == null ? "" : arguments.getString("mode", "");
        start();
    }

    @Override public void onStart() {
        boolean passed = false;
        String failure = "NONE";
        try {
            check(authorized, "NOT_AUTHORIZED");
            boolean known = false; for (String candidate : MODES) if (candidate.equals(mode)) known = true;
            check(known, "INVALID_STAGE");
            context = getTargetContext().getApplicationContext();
            store = new NgaStore(context); preferences = context.getSharedPreferences("nga_local", 0);
            host = store.host(); account = store.accountId(); cookie = store.cookie();
            check(store.loggedIn() && !account.isEmpty(), "SESSION_UNAVAILABLE");
            check(!store.autoCheckIn() || NgaStore.today().equals(store.lastCheckInDay()), "SIGN_IN_GUARD_FAILED");
            check(!NativeLoginView.hasPendingAuthorization(context, host), "AUTHORIZATION_PENDING");
            scope = ForumFavoritesCoordinator.scope(context);
            cloud = new CloudForumFavorites(new NativeCommunity(context, host, account));
            journal = new AtomicFile(new File(context.getFilesDir(), "favorite-automatic-restore-v1.json"));
            if ("prepare".equals(mode)) prepare();
            else {
                loadJournal();
                if ("check-startup".equals(mode)) checkStartup();
                else if ("online".equals(mode)) online();
                else if ("offline-queue".equals(mode)) offlineQueue();
                else if ("check-retry".equals(mode)) checkRetry();
                else cleanup();
            }
            check(sessionStable(), "SESSION_CHANGED");
            passed = true;
        } catch (ProbeFailure failed) { failure = failed.code; }
        catch (Exception | AssertionError unavailable) { failure = "AUTO_FAVORITE_PROBE_UNAVAILABLE"; }
        finally {
            put(report, "schema", 1); put(report, "purpose", "AUTOMATIC_FAVORITES_DEVICE_FLOW");
            put(report, "mode", mode); put(report, "explicitlyAuthorized", authorized);
            put(report, "passed", passed); put(report, "failure", passed ? "NONE" : failure);
            put(report, "checks", checks); put(report, "assertionCount", assertions);
            put(report, "independentReadCount", reads); put(report, "productionStarClicks", starClicks);
            put(report, "restoreProductionQueueSubmissions", restoreSubmissions);
            put(report, "manualSyncClicks", 0); put(report, "publicTargetCount", 1);
            put(report, "realCloudReadPerformed", reads > 0);
            put(report, "realCloudMutationRequested", starClicks + restoreSubmissions > 0);
            put(report, "accountSessionUnchanged", store != null && sessionStable());
            put(report, "recoveryJournalRetained", journal != null && journalExists());
            put(report, "privateRecoveryJournalContainsCredentials", false);
            put(report, "fullFavoriteKeysetExported", false); put(report, "rawServerDataExported", false);
            put(report, "realCredentialsSubmitted", false); put(report, "realSmsSent", false);
            if (baseline != null) baseline.clear(); saved = null; host = account = cookie = scope = "";
            Bundle output = new Bundle(); output.putString("safe_auto_favorite_report", report.toString());
            finish(Activity.RESULT_OK, output);
        }
    }

    private void prepare() throws Exception {
        check(!journalExists(), "RECOVERY_JOURNAL_ALREADY_EXISTS");
        check(store.pendingFavoriteChanges(scope).isEmpty(), "EXISTING_PENDING_CHANGES");
        openActivity(); barrier(true);
        CloudForumFavorites.Snapshot first = read(); baseline = new HashSet<>(first.keys());
        check(!baseline.isEmpty(), "CLOUD_BASELINE_EMPTY"); originalTarget = baseline.contains(TARGET);
        check(store.pendingFavoriteChanges(scope).isEmpty(), "EXISTING_PENDING_CHANGES");
        saved = new JSONObject().put("version", 1).put("scope", scope).put("target", TARGET)
                .put("sessionDigest", sessionDigest()).put("unrelatedDigest", unrelatedDigest())
                .put("wasFavorite", originalTarget).put("phase", "PREPARING")
                .put("favoritePreferences", encodePreferences(filtered(preferences.getAll(), true)))
                .put("forumIcons", encodeIcons()).put("baselineKeys", encodeKeys(baseline));
        persistJournal();
        // Establish a complete known cloud projection, then remove only its local copy. No
        // server mutation is requested. The following normal application launch must pull it.
        check(store.mergeFavoriteForums(scope, entries(first)) >= 0, "LOCAL_BASELINE_MERGE_FAILED");
        check(store.mergeFavoriteForums(scope, Collections.emptyMap()) >= 0, "LOCAL_CLEAR_FAILED");
        Set<String> missing = new HashSet<>(baseline); missing.removeAll(store.favoriteForums());
        check(!missing.isEmpty(), "NO_MISSING_CLOUD_ENTRY_TO_TEST");
        saved.put("missingCount", missing.size()).put("phase", "WAIT_STARTUP"); persistJournal();
        check(read().keys().equals(baseline), "UNRELATED_REMOTE_FAVORITE_CHANGED");
        check(unrelatedStable(), "ACCOUNT_OR_SETTINGS_CHANGED");
        flag("localCloudProjectionCleared", true); flag("missingCloudEntriesPositive", true);
        flag("remoteWholeKeysetUnchanged", true); flag("accountAndSettingsUnchanged", true);
        put(report, "missingCloudEntryCount", missing.size());
        put(report, "baselineCloudCount", baseline.size());
    }

    private void checkStartup() throws Exception {
        requirePhase("WAIT_STARTUP");
        // Intentionally inspect preferences BEFORE any Activity is started by this helper.
        // Root must have force-stopped and normally launched the released client beforehand.
        check(activity == null, "HELPER_STARTED_ACTIVITY_EARLY");
        check(store.favoriteForums().containsAll(baseline), "NORMAL_STARTUP_DID_NOT_RESTORE_CLOUD");
        check(store.pendingFavoriteChanges(scope).isEmpty(), "UNEXPECTED_PENDING_AFTER_STARTUP");
        check(read().keys().equals(baseline), "REMOTE_SET_CHANGED_DURING_STARTUP");
        check(unrelatedStable(), "ACCOUNT_OR_SETTINGS_CHANGED");
        saved.put("phase", "STARTUP_VERIFIED"); persistJournal();
        flag("coldStartupCloudPullVerified", true); flag("beforeActivityStartProjectionRecovered", true);
        flag("remoteWholeKeysetUnchanged", true); flag("pendingEmpty", true);
        flag("accountAndSettingsUnchanged", true);
    }

    private void online() throws Exception {
        requirePhase("STARTUP_VERIFIED"); openActivity(); barrier(true);
        check(read().keys().equals(baseline), "REMOTE_SET_CHANGED_BEFORE_ONLINE");
        check(store.favoriteForums().contains(TARGET) == originalTarget, "LOCAL_BASELINE_TARGET_MISMATCH");
        saved.put("phase", "ONLINE_RUNNING"); persistJournal();
        clickFavorite(!originalTarget); barrier(true); verifyDesired(!originalTarget);
        flag(originalTarget ? "automaticRemoveReadback" : "automaticAddReadback", true);
        clickFavorite(originalTarget); barrier(true); verifyDesired(originalTarget);
        flag(originalTarget ? "automaticAddReadback" : "automaticRemoveReadback", true);
        check(read().keys().equals(baseline), "REMOTE_WHOLE_SET_NOT_RESTORED");
        check(unrelatedStable(), "ACCOUNT_OR_SETTINGS_CHANGED");
        saved.put("phase", "ONLINE_VERIFIED"); persistJournal();
        flag("remoteWholeKeysetRestored", true); flag("pendingEmpty", true);
        flag("accountAndSettingsUnchanged", true); flag("onlyProductionFavoriteControlsClicked", true);
    }

    private void offlineQueue() throws Exception {
        requirePhase("ONLINE_VERIFIED");
        // Network is disabled externally, only on the dedicated test emulator. This stage
        // never fabricates transport success or confirms an unobserved server state.
        openActivity();
        check(store.favoriteForums().contains(TARGET) == originalTarget, "LOCAL_BASELINE_TARGET_MISMATCH");
        saved.put("phase", "OFFLINE_QUEUING").put("offlineDesired", !originalTarget); persistJournal();
        clickFavorite(!originalTarget); barrier(false);
        List<ForumFavoriteSyncState.Change> pending = store.pendingFavoriteChanges(scope);
        check(pending.size() == 1 && TARGET.equals(pending.get(0).key)
                && pending.get(0).desired == !originalTarget, "OFFLINE_INTENT_ALREADY_CONFIRMED_OR_MISMATCHED");
        check(store.favoriteForums().contains(TARGET) == !originalTarget, "OFFLINE_LOCAL_INTENT_MISMATCH");
        NgaStore reopened = new NgaStore(context);
        List<ForumFavoriteSyncState.Change> durable = reopened.pendingFavoriteChanges(scope);
        check(durable.size() == 1 && durable.get(0).revision == pending.get(0).revision
                && durable.get(0).desired == pending.get(0).desired, "OFFLINE_INTENT_NOT_PERSISTED");
        check(unrelatedStable(), "ACCOUNT_OR_SETTINGS_CHANGED");
        saved.put("phase", "WAIT_RETRY"); persistJournal();
        flag("pendingIntentPersisted", true); flag("offlineIntentNotYetConfirmed", true);
        flag("accountAndSettingsUnchanged", true); put(report, "pendingCount", 1);
    }

    private void checkRetry() throws Exception {
        requirePhase("WAIT_RETRY");
        check(activity == null, "HELPER_STARTED_ACTIVITY_EARLY");
        // As with startup, these observations precede helper Activity creation. The normal
        // client launch after connectivity restoration must have drained durable intent.
        check(store.pendingFavoriteChanges(scope).isEmpty(), "NORMAL_RESTART_DID_NOT_RETRY_PENDING");
        check(store.favoriteForums().contains(TARGET) == !originalTarget, "RESTART_LOCAL_INTENT_MISMATCH");
        verifyDesired(!originalTarget); check(unrelatedStable(), "ACCOUNT_OR_SETTINGS_CHANGED");
        saved.put("phase", "RETRY_VERIFIED"); persistJournal();
        flag("restartAutomaticallyRetried", true); flag("beforeActivityStartPendingCleared", true);
        flag("independentReadbackMatchesIntent", true); flag("pendingEmpty", true);
        flag("accountAndSettingsUnchanged", true);
    }

    private void cleanup() throws Exception {
        openActivity(); barrier(false);
        for (ForumFavoriteSyncState.Change change : store.pendingFavoriteChanges(scope))
            check(TARGET.equals(change.key), "UNRELATED_PENDING_DURING_RESTORE");
        CloudForumFavorites.Snapshot current = read();
        if (current.keys().contains(TARGET) != originalTarget || !store.pendingFavoriteChanges(scope).isEmpty()) {
            onMain(() -> { invoke(activity, "setForumFavorite", new Class<?>[]{String[].class, boolean.class}, row(), originalTarget); return null; });
            restoreSubmissions++; barrier(true);
        }
        check(store.pendingFavoriteChanges(scope).isEmpty(), "RESTORE_PENDING_NOT_EMPTY");
        check(read().keys().equals(baseline), "REMOTE_RESTORE_UNCONFIRMED");
        check(unrelatedStable(), "ACCOUNT_OR_SETTINGS_CHANGED");
        check(onMain(this::restoreLocal), "LOCAL_RESTORE_FAILED");
        check(unrelatedStable(), "ACCOUNT_OR_SETTINGS_CHANGED");
        journal.delete(); check(!journalExists(), "RECOVERY_JOURNAL_DELETE_FAILED");
        flag("remoteWholeKeysetRestored", true); flag("localFavoritePreferencesRestored", true);
        flag("accountAndSettingsUnchanged", true); flag("recoveryJournalDeleted", true);
    }

    private void openActivity() throws Exception {
        activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                .setClassName("cn.ngapure.app", "cn.ngapure.app.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        check(activity != null, "ACTIVITY_START_FAILED"); guard();
        check(scope.equals(onMain(() -> (String) invoke(activity, "favoriteScope", new Class<?>[0]))), "UI_SCOPE_MISMATCH");
    }

    private void clickFavorite(boolean desired) throws Exception {
        guard(); check(store.favoriteForums().contains(TARGET) != desired, "STAR_CLICK_NOT_A_STATE_CHANGE");
        onMain(() -> {
            TextView star = new TextView(activity); star.setLayoutParams(new LinearLayout.LayoutParams(160, 180));
            invoke(activity, "bindForumFavorite", new Class<?>[]{TextView.class, String[].class, Runnable.class}, star, row(), null);
            check(("forum-favorite:" + TARGET).equals(star.getTag()) && star.isClickable(), "PRODUCTION_STAR_NOT_BOUND");
            check(star.performClick(), "PRODUCTION_STAR_CLICK_FAILED"); return null;
        });
        starClicks++;
    }

    private void verifyDesired(boolean desired) throws Exception {
        guard(); check(store.pendingFavoriteChanges(scope).isEmpty(), "PENDING_CHANGE_UNCONFIRMED");
        check(store.favoriteForums().contains(TARGET) == desired, "LOCAL_TARGET_MISMATCH");
        Set<String> expected = new HashSet<>(baseline);
        if (desired) expected.add(TARGET); else expected.remove(TARGET);
        check(read().keys().equals(expected), "INDEPENDENT_REMOTE_READBACK_MISMATCH");
    }

    private void barrier(boolean requireSuccessfulPull) throws Exception {
        guard(); ForumFavoritesCoordinator coordinator = ForumFavoritesCoordinator.get(context);
        long deadline = SystemClock.elapsedRealtime() + 120000;
        while (SystemClock.elapsedRealtime() < deadline) {
            coordinator.executor().submit(() -> { }).get(Math.max(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
            waitForIdleSync(); onMain(() -> null); guard();
            ForumFavoritesCoordinator.Snapshot state = coordinator.status(scope);
            if (!state.running && !coordinator.running()) {
                if (requireSuccessfulPull) check(state.loaded && state.lastSuccessfulAt > 0, "PRODUCTION_AUTO_PULL_NOT_LOADED");
                return;
            }
            SystemClock.sleep(30);
        }
        throw new ProbeFailure("PRODUCTION_WORKER_DEADLINE");
    }

    private CloudForumFavorites.Snapshot read() throws Exception { guard(); reads++; return cloud.read(); }
    private void guard() throws ProbeFailure {
        check(sessionStable() && scope.equals(ForumFavoritesCoordinator.scope(context)), "SESSION_OR_SCOPE_CHANGED");
        check(activity == null || !activity.isDestroyed(), "ACTIVITY_DESTROYED");
    }
    private boolean sessionStable() {
        return store != null && host.equals(store.host()) && account.equals(store.accountId()) && cookie.equals(store.cookie());
    }
    private void requirePhase(String expected) throws Exception { check(expected.equals(saved.optString("phase")), "STAGE_OUT_OF_ORDER"); }
    private boolean journalExists() { return journal.getBaseFile().exists() || new File(journal.getBaseFile() + ".bak").exists(); }
    private void persistJournal() throws Exception {
        byte[] bytes = saved.toString().getBytes(StandardCharsets.UTF_8);
        check(bytes.length <= MAX_JOURNAL_BYTES, "RECOVERY_JOURNAL_TOO_LARGE");
        FileOutputStream stream = null;
        try { stream = journal.startWrite(); stream.write(bytes); journal.finishWrite(stream); journalLoaded = true; }
        catch (Exception unavailable) { if (stream != null) journal.failWrite(stream); throw unavailable; }
    }
    private void loadJournal() throws Exception {
        check(journalExists(), "RECOVERY_JOURNAL_MISSING");
        byte[] bytes = journal.readFully(); check(bytes.length <= MAX_JOURNAL_BYTES, "RECOVERY_JOURNAL_INVALID");
        saved = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        check(saved.optInt("version") == 1 && scope.equals(saved.optString("scope")) && TARGET.equals(saved.optString("target"))
                && sessionDigest().equals(saved.optString("sessionDigest")) && saved.opt("wasFavorite") instanceof Boolean,
                "RECOVERY_SCOPE_OR_SCHEMA_MISMATCH");
        originalTarget = saved.getBoolean("wasFavorite");
        JSONArray keys = saved.getJSONArray("baselineKeys"); check(keys.length() > 0 && keys.length() <= 2048, "RECOVERY_JOURNAL_INVALID");
        baseline = new HashSet<>();
        for (int i = 0; i < keys.length(); i++) {
            Object key = keys.get(i); check(key instanceof String && ForumRoute.isKey((String) key) && baseline.add((String) key), "RECOVERY_JOURNAL_INVALID");
        }
        check(baseline.contains(TARGET) == originalTarget, "RECOVERY_JOURNAL_INVALID");
        decodePreferences(saved.getJSONObject("favoritePreferences"));
        check(saved.getJSONObject("forumIcons").length() <= 2048, "RECOVERY_JOURNAL_INVALID");
        journalLoaded = true;
    }

    private boolean restoreLocal() throws Exception {
        check(journalLoaded, "RECOVERY_JOURNAL_NOT_LOADED");
        Map<String,Object> before = decodePreferences(saved.getJSONObject("favoritePreferences"));
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) if (key.startsWith("favorite_")) editor.remove(key);
        for (Map.Entry<String,Object> entry : before.entrySet()) writePreference(editor, entry.getKey(), entry.getValue());
        boolean committed = editor.commit();
        @SuppressWarnings("unchecked") Map<String,String> icons = (Map<String,String>) field(activity, "forumIcons");
        JSONObject encoded = saved.getJSONObject("forumIcons"); Map<String,String> restored = new HashMap<>();
        java.util.Iterator<String> keys = encoded.keys();
        while (keys.hasNext()) { String key = keys.next(); Object value = encoded.get(key);
            check(ForumRoute.isKey(key) && value instanceof String && ((String) value).length() <= 4096, "RECOVERY_JOURNAL_INVALID"); restored.put(key, (String) value); }
        icons.clear(); icons.putAll(restored);
        invoke(activity, "refreshFavoriteBindings", new Class<?>[0]);
        return committed && before.equals(filtered(preferences.getAll(), true)) && icons.equals(restored);
    }
    private JSONObject encodeIcons() throws Exception {
        return onMain(() -> { @SuppressWarnings("unchecked") Map<String,String> icons = (Map<String,String>) field(activity, "forumIcons");
            JSONObject result = new JSONObject(); for (Map.Entry<String,String> icon : icons.entrySet()) result.put(icon.getKey(), icon.getValue()); return result; });
    }
    private String sessionDigest() throws Exception { return digest(host + '\0' + account + '\0' + cookie); }
    private String unrelatedDigest() throws Exception { return digest(encodePreferences(filtered(preferences.getAll(), false)).toString()); }
    private boolean unrelatedStable() throws Exception { return saved != null && unrelatedDigest().equals(saved.getString("unrelatedDigest")); }
    private static String digest(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        char[] chars = new char[bytes.length * 2], hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) { chars[i * 2] = hex[(bytes[i] & 255) >>> 4]; chars[i * 2 + 1] = hex[bytes[i] & 15]; } return new String(chars);
    }
    private static Map<String,Object> filtered(Map<String,?> values, boolean favorites) {
        Map<String,Object> result = new java.util.TreeMap<>();
        for (Map.Entry<String,?> entry : values.entrySet()) if (entry.getKey().startsWith("favorite_") == favorites) {
            Object value = entry.getValue(); if (value instanceof Set<?>) value = new HashSet<>((Set<?>) value); result.put(entry.getKey(), value);
        } return result;
    }
    private static JSONObject encodePreferences(Map<String,Object> values) throws Exception {
        JSONObject encoded = new JSONObject();
        for (Map.Entry<String,Object> entry : values.entrySet()) {
            JSONObject row = new JSONObject(); Object value = entry.getValue();
            if (value instanceof String) row.put("type", "string").put("value", value);
            else if (value instanceof Long) row.put("type", "long").put("value", value.toString());
            else if (value instanceof Integer) row.put("type", "int").put("value", value.toString());
            else if (value instanceof Boolean) row.put("type", "boolean").put("value", value);
            else if (value instanceof Float) row.put("type", "float").put("value", value.toString());
            else if (value instanceof Set<?>) {
                List<String> items = new ArrayList<>(); for (Object item : (Set<?>) value) { if (!(item instanceof String)) throw new ProbeFailure("PREFERENCE_TYPE_UNAVAILABLE"); items.add((String) item); }
                Collections.sort(items); JSONArray array = new JSONArray(); for (String item : items) array.put(item);
                row.put("type", "set").put("value", array);
            } else throw new ProbeFailure("PREFERENCE_TYPE_UNAVAILABLE");
            encoded.put(entry.getKey(), row);
        } return encoded;
    }
    private static Map<String,Object> decodePreferences(JSONObject encoded) throws Exception {
        if (encoded.length() > 64) throw new ProbeFailure("RECOVERY_JOURNAL_INVALID");
        Map<String,Object> result = new LinkedHashMap<>(); java.util.Iterator<String> keys = encoded.keys();
        while (keys.hasNext()) {
            String key = keys.next(); if (!key.startsWith("favorite_") || key.length() > 128) throw new ProbeFailure("RECOVERY_JOURNAL_INVALID");
            JSONObject row = encoded.getJSONObject(key); Object value = row.get("value");
            switch (row.getString("type")) {
                case "string": if (!(value instanceof String)) throw new ProbeFailure("RECOVERY_JOURNAL_INVALID"); break;
                case "long": value = Long.valueOf(row.getString("value")); break;
                case "int": value = Integer.valueOf(row.getString("value")); break;
                case "boolean": if (!(value instanceof Boolean)) throw new ProbeFailure("RECOVERY_JOURNAL_INVALID"); break;
                case "float": value = Float.valueOf(row.getString("value")); break;
                case "set":
                    JSONArray array = row.getJSONArray("value"); if (array.length() > 4096) throw new ProbeFailure("RECOVERY_JOURNAL_INVALID");
                    Set<String> set = new HashSet<>(); for (int i = 0; i < array.length(); i++) { Object item = array.get(i); if (!(item instanceof String) || !set.add((String) item)) throw new ProbeFailure("RECOVERY_JOURNAL_INVALID"); set.add((String) item); } value = set; break;
                default: throw new ProbeFailure("RECOVERY_JOURNAL_INVALID");
            }
            result.put(key, value);
        } return result;
    }
    private static void writePreference(SharedPreferences.Editor editor, String key, Object value) throws Exception {
        if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof Set<?>) { Set<String> set = new HashSet<>(); for (Object item : (Set<?>) value) { if (!(item instanceof String)) throw new ProbeFailure("PREFERENCE_TYPE_UNAVAILABLE"); set.add((String) item); } editor.putStringSet(key, set); }
        else throw new ProbeFailure("PREFERENCE_TYPE_UNAVAILABLE");
    }
    private static JSONArray encodeKeys(Set<String> keys) { List<String> sorted = new ArrayList<>(keys); Collections.sort(sorted); JSONArray result = new JSONArray(); for (String key : sorted) result.put(key); return result; }
    private static Map<String,String[]> entries(CloudForumFavorites.Snapshot snapshot) { Map<String,String[]> result = new LinkedHashMap<>(); for (CloudForumFavorites.Entry entry : snapshot.entries) result.put(entry.key, new String[]{entry.name, entry.icon}); return result; }
    private static String[] row() { return new String[]{TARGET, "旅行者指引广场", "子版块", "N"}; }
    private void flag(String name, boolean value) { put(checks, name, value); }
    private void check(boolean value, String code) throws ProbeFailure { assertions++; if (!value) throw new ProbeFailure(code); }
    private <T> T onMain(Callable<T> action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); Object[] values = new Object[1]; Throwable[] errors = new Throwable[1];
        main.post(() -> { try { values[0] = action.call(); } catch (Throwable failure) { errors[0] = failure; } finally { latch.countDown(); } });
        if (!latch.await(10000, TimeUnit.MILLISECONDS)) throw new ProbeFailure("MAIN_THREAD_DEADLINE");
        if (errors[0] instanceof Exception) throw (Exception) errors[0]; if (errors[0] != null) throw new ProbeFailure("MAIN_THREAD_FAILED");
        @SuppressWarnings("unchecked") T value = (T) values[0]; return value;
    }
    private static Object field(Object target, String name) throws Exception { Field value = target.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(target); }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception { Method value = target.getClass().getDeclaredMethod(name, types); value.setAccessible(true); return value.invoke(target, args); }
    private static void put(JSONObject object, String key, Object value) { try { object.put(key, value); } catch (Exception ignored) { /* Safe flags, counters and fixed enums only. */ } }
    private static final class ProbeFailure extends Exception { private static final long serialVersionUID = 1L; final String code; ProbeFailure(String code) { this.code = code; } }
}
