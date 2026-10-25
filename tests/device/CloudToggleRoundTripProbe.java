package cn.ngapure.verify;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import cn.ngapure.app.CloudForumFavorites;
import cn.ngapure.app.ForumFavoriteSyncState;
import cn.ngapure.app.ForumFavoritesCoordinator;
import cn.ngapure.app.NativeCommunity;
import cn.ngapure.app.NativeLoginView;
import cn.ngapure.app.NgaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Explicit opt-in only: production UI queue, two public targets, and verified restoration. */
public final class CloudToggleRoundTripProbe extends Instrumentation {
    private static final String[] KEYS = {"754", "stid:23673143"};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final JSONObject report = new JSONObject();
    private final JSONArray cases = new JSONArray();
    private boolean authorized;
    private Activity activity;
    private NgaStore store;
    private FavoriteProbeState local;
    private CloudForumFavorites cloud;
    private String scope = "", host = "", account = "", cookie = "";
    private boolean[] original;
    private Set<String> baseline;
    private AtomicFile journal;
    private int reads, submitted, assertions;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args); authorized = args != null && "true".equals(args.getString("realCloudToggle")); start();
    }
    @Override public void onStart() {
        boolean passed = false, restored = false, localRestored = false, stable = false, recovered = false;
        String failure = "NONE";
        try {
            check(authorized, "NOT_AUTHORIZED");
            store = new NgaStore(getTargetContext()); host = store.host(); account = store.accountId(); cookie = store.cookie();
            check(store.loggedIn() && !account.isEmpty(), "SESSION_UNAVAILABLE");
            check(NgaStore.today().equals(store.lastCheckInDay()), "SIGN_IN_GUARD_FAILED");
            check(!NativeLoginView.hasPendingAuthorization(getTargetContext(), host), "AUTHORIZATION_PENDING");
            scope = FavoriteProbeState.scope(store);
            journal = new AtomicFile(new File(getTargetContext().getFilesDir(), "favorite-roundtrip-restore.json"));
            if (journal.getBaseFile().exists()) original = readJournal();
            for (ForumFavoriteSyncState.Change pending : store.pendingFavoriteChanges(scope))
                check(original != null && (KEYS[0].equals(pending.key) || KEYS[1].equals(pending.key)), "EXISTING_PENDING_CHANGES");
            activity = startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.ngapure.app", "cn.ngapure.app.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            check(activity != null, "ACTIVITY_START_FAILED");
            check(scope.equals(onMain(() -> (String) FavoriteProbeState.invoke(activity, "favoriteScope", new Class<?>[0]))), "UI_SCOPE_MISMATCH");
            barrier(); guard();
            cloud = new CloudForumFavorites(new NativeCommunity(getTargetContext(), host, account));
            if (original != null) {
                restoreRemote(); recovered = true; original = null;
            }
            // A recovered run starts its new local rollback snapshot only after stale test intent is drained.
            local = onMain(() -> new FavoriteProbeState(activity, getTargetContext().getSharedPreferences("nga_local", 0)));
            CloudForumFavorites.Snapshot start = read(); baseline = new HashSet<>(start.keys()); original = new boolean[KEYS.length];
            for (int i = 0; i < KEYS.length; i++) original[i] = baseline.contains(KEYS[i]);
            writeJournal();
            for (int i = 0; i < KEYS.length; i++) {
                setAndVerify(KEYS[i], !original[i]);
                casePassed(KEYS[i], "INVERTED_AND_READ_BACK");
                setAndVerify(KEYS[i], original[i]);
                casePassed(KEYS[i], "ORIGINAL_AND_READ_BACK");
            }
            restored = read().keys().equals(baseline); check(restored, "REMOTE_SET_CHANGED");
            check(local.unrelatedUnchanged(), "ACCOUNT_OR_SETTINGS_CHANGED");
            passed = true;
        } catch (ProbeFailure failed) { failure = failed.code; }
        catch (Exception | AssertionError unavailable) { failure = "CLOUD_ROUND_TRIP_UNAVAILABLE"; }
        finally {
            if (authorized && cloud != null && original != null) {
                try { restoreRemote(); restored = baseline == null || read().keys().equals(baseline); }
                catch (Exception | AssertionError unavailable) { restored = false; }
            }
            if (local != null && restored) {
                try { barrier(); localRestored = onMain(local::restore); }
                catch (Exception unavailable) { localRestored = false; }
            }
            stable = store != null && host.equals(store.host()) && account.equals(store.accountId()) && cookie.equals(store.cookie());
            if (local != null && (!restored || !localRestored || !stable)) { passed = false; failure = !restored ? "REMOTE_RESTORE_UNCONFIRMED" : !localRestored ? "LOCAL_RESTORE_FAILED" : "ACCOUNT_CHANGED"; }
            if (passed && restored && localRestored && journal != null) journal.delete();
            if (local != null) local.clear(); if (baseline != null) baseline.clear(); host = account = cookie = scope = "";
        }
        put(report, "schema", 1); put(report, "purpose", "EXPLICIT_CLOUD_FORUM_FAVORITES_ROUND_TRIP");
        put(report, "explicitlyAuthorized", authorized); put(report, "cases", cases); put(report, "scenarioCount", cases.length());
        put(report, "assertionCount", assertions); put(report, "independentReadCount", reads); put(report, "productionQueueSubmissions", submitted);
        put(report, "publicTargetCount", 2); put(report, "journalRecovered", recovered);
        put(report, "remoteTargetStatesRestored", restored); put(report, "remoteWholeKeysetRestored", restored && baseline != null);
        put(report, "localFavoritePreferencesAndIconsRestored", localRestored); put(report, "accountSessionUnchanged", stable);
        put(report, "recoveryJournalRetained", journal != null && journal.getBaseFile().exists());
        put(report, "realCloudReadPerformed", reads > 0); put(report, "realCloudMutationRequested", submitted > 0);
        put(report, "realCredentialsSubmitted", false); put(report, "realSmsSent", false); put(report, "rawServerDataExported", false);
        put(report, "passed", passed && restored && localRestored && stable && cases.length() == 4); put(report, "failure", passed ? "NONE" : failure);
        Bundle output = new Bundle(); output.putString("safe_cloud_toggle_roundtrip_report", report.toString()); finish(Activity.RESULT_OK, output);
    }
    private void guard() throws Exception {
        check(authorized && store != null && host.equals(store.host()) && account.equals(store.accountId()) && cookie.equals(store.cookie())
                && scope.equals(FavoriteProbeState.scope(store)), "SESSION_OR_ROUTE_CHANGED");
    }
    private CloudForumFavorites.Snapshot read() throws Exception { guard(); reads++; return cloud.read(); }
    private void setAndVerify(String key, boolean desired) throws Exception {
        guard();
        String[] row = {key, key.startsWith("stid:") ? "公开合集测试" : "公开子版块测试", "子版块", "N"};
        onMain(() -> { FavoriteProbeState.invoke(activity, "setForumFavorite", new Class<?>[]{String[].class, boolean.class}, row, desired); return null; });
        submitted++; barrier(); guard();
        check(store.pendingFavoriteChanges(scope).isEmpty(), "PENDING_CHANGE_UNCONFIRMED");
        check(store.favoriteForums().contains(key) == desired, "LOCAL_INTENT_MISMATCH");
        check(read().keys().contains(key) == desired, "INDEPENDENT_READBACK_MISMATCH");
    }
    private void restoreRemote() throws Exception {
        guard();
        // Read before every restore: no inverse toggle, and no write for an already restored target.
        for (int i = 0; i < KEYS.length; i++) {
            boolean actual = read().keys().contains(KEYS[i]);
            if (actual != original[i] || !store.pendingFavoriteChanges(scope).isEmpty()) setAndVerify(KEYS[i], original[i]);
        }
        barrier(); CloudForumFavorites.Snapshot verified = read();
        for (int i = 0; i < KEYS.length; i++) check(verified.keys().contains(KEYS[i]) == original[i], "TARGET_RESTORE_UNCONFIRMED");
        check(store.pendingFavoriteChanges(scope).isEmpty(), "RESTORE_PENDING_NOT_EMPTY");
    }
    private void barrier() throws Exception {
        guard();
        ExecutorService writes = (ExecutorService) FavoriteProbeState.field(activity, "favoriteWrites");
        long deadline = SystemClock.elapsedRealtime() + 100000;
        while (SystemClock.elapsedRealtime() < deadline) {
            writes.submit(() -> { }).get(Math.max(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
            waitForIdleSync(); onMain(() -> null); guard();
            // A schedule racing the drain may append another task behind the first barrier.
            // Wait for the production coordinator and its main-thread observer to both settle.
            if (!ForumFavoritesCoordinator.get(getTargetContext()).running()
                    && !((Boolean) FavoriteProbeState.field(activity, "favoriteSyncRunning"))) return;
            SystemClock.sleep(25);
        }
        throw new ProbeFailure("WRITE_WORKER_STILL_RUNNING");
    }
    private void writeJournal() throws Exception {
        JSONObject root = new JSONObject().put("version", 1).put("scope", scope); JSONArray rows = new JSONArray();
        for (int i = 0; i < KEYS.length; i++) rows.put(new JSONObject().put("key", KEYS[i]).put("wasFavorite", original[i]));
        byte[] bytes = root.put("targets", rows).toString().getBytes(StandardCharsets.UTF_8); FileOutputStream stream = null;
        try { stream = journal.startWrite(); stream.write(bytes); journal.finishWrite(stream); }
        catch (Exception unavailable) { if (stream != null) journal.failWrite(stream); throw unavailable; }
    }
    private boolean[] readJournal() throws Exception {
        byte[] bytes = journal.readFully(); check(bytes.length <= 2048, "RECOVERY_JOURNAL_INVALID");
        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        check(root.optInt("version") == 1 && scope.equals(root.optString("scope")), "RECOVERY_SCOPE_MISMATCH");
        JSONArray rows = root.optJSONArray("targets"); check(rows != null && rows.length() == KEYS.length, "RECOVERY_JOURNAL_INVALID");
        boolean[] values = new boolean[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) { JSONObject row = rows.optJSONObject(i); check(row != null && KEYS[i].equals(row.optString("key")) && row.opt("wasFavorite") instanceof Boolean, "RECOVERY_JOURNAL_INVALID"); values[i] = row.getBoolean("wasFavorite"); }
        return values;
    }
    private void casePassed(String key, String outcome) { JSONObject row = new JSONObject(); put(row, "publicKey", key); put(row, "outcome", outcome); put(row, "passed", true); cases.put(row); }
    private void check(boolean value, String code) throws ProbeFailure { assertions++; if (!value) throw new ProbeFailure(code); }
    private <T> T onMain(Callable<T> action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); Object[] values = new Object[1]; Throwable[] errors = new Throwable[1];
        main.post(() -> { try { values[0] = action.call(); } catch (Throwable error) { errors[0] = error; } finally { latch.countDown(); } });
        if (!latch.await(5000, TimeUnit.MILLISECONDS)) throw new ProbeFailure("MAIN_THREAD_DEADLINE");
        if (errors[0] instanceof Exception) throw (Exception) errors[0]; if (errors[0] != null) throw new ProbeFailure("MAIN_THREAD_FAILED");
        @SuppressWarnings("unchecked") T value = (T) values[0]; return value;
    }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value); } catch (Exception ignored) { /* Fixed enums, public targets, counters and flags only. */ } }
    private static final class ProbeFailure extends Exception { private static final long serialVersionUID = 1L; final String code; ProbeFailure(String code) { this.code = code; } }
}
