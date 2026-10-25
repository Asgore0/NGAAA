package cn.ngapure.verify;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import cn.ngapure.app.ForumFavoriteSyncState;
import cn.ngapure.app.NativeLoginView;
import cn.ngapure.app.NgaApi;
import cn.ngapure.app.NgaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Synthetic local pending state and native binding only. Never clicks a mutation control. */
public final class FavoriteSyncProbe extends Instrumentation {
    private static final String KEY = "987654321011", COLLECTION = "stid:987654321012";
    private static final String SCOPE_A = "synthetic-favorite-device-a", SCOPE_B = "synthetic-favorite-device-b";
    private static final int EXPECTED = 12;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final JSONObject report = new JSONObject();
    private final JSONArray cases = new JSONArray();
    private Activity activity;
    private NgaStore store;
    private FavoriteProbeState before;
    private int assertions;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        boolean passed = false, restored = false, unchanged = false; String failure = "NONE";
        try {
            store = new NgaStore(getTargetContext());
            check(!store.autoCheckIn() || store.loggedIn() && NgaStore.today().equals(store.lastCheckInDay()), "SIGN_IN_GUARD_FAILED");
            check(!NativeLoginView.hasPendingAuthorization(getTargetContext(), store.host()), "AUTHORIZATION_PENDING");
            check(store.pendingFavoriteChanges(FavoriteProbeState.scope(store)).isEmpty(), "EXISTING_PENDING_CHANGES");
            activity = startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.ngapure.app", "cn.ngapure.app.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            check(activity != null, "ACTIVITY_START_FAILED");
            before = onMain(() -> new FavoriteProbeState(activity, getTargetContext().getSharedPreferences("nga_local", 0)));
            check(!store.favoriteForums().contains(KEY) && !store.favoriteForums().contains(COLLECTION), "SYNTHETIC_KEY_COLLISION");
            onMain(() -> { positive(); negative(); stale(); confirmOnlyObserved(); scopes(); mergePending(); metadata(); durableRetry(); board(); subForum(); binding(); pendingBinding(); return null; });
            unchanged = before.unrelatedUnchanged(); check(unchanged, "ACCOUNT_OR_SETTINGS_CHANGED"); passed = true;
        } catch (ProbeFailure failed) { failure = failed.code; }
        catch (Exception | AssertionError unavailable) { failure = "FAVORITE_SYNC_PROBE_UNAVAILABLE"; }
        finally {
            if (before != null) {
                try { restored = onMain(before::restore); } catch (Exception unavailable) { restored = false; }
                unchanged = before.unrelatedUnchanged(); before.clear();
                if (!restored || !unchanged) { passed = false; failure = "LOCAL_STATE_RESTORE_FAILED"; }
            }
        }
        put(report, "schema", 1); put(report, "purpose", "SYNTHETIC_FAVORITE_INTENT_AND_NATIVE_BINDINGS");
        put(report, "cases", cases); put(report, "scenarioCount", cases.length()); put(report, "expectedScenarioCount", EXPECTED); put(report, "assertionCount", assertions);
        put(report, "favoritePreferencesAndIconsExactlyRestored", restored); put(report, "accountAndSettingsUnchanged", unchanged);
        put(report, "realCloudReadPerformed", false); put(report, "realCloudMutationPerformed", false); put(report, "favoriteControlsClicked", false);
        put(report, "realCredentialsSubmitted", false); put(report, "realSmsSent", false); put(report, "preferenceValuesExported", false);
        put(report, "passed", passed && restored && cases.length() == EXPECTED); put(report, "failure", passed ? "NONE" : failure);
        Bundle output = new Bundle(); output.putString("safe_favorite_sync_report", report.toString()); finish(Activity.RESULT_OK, output);
    }
    private void positive() throws Exception {
        store.activateFavoriteScope(SCOPE_A); long revision = store.queueFavorite(SCOPE_A, KEY, "合成子版块", "", true);
        check(store.favoriteForums().contains(KEY), "POSITIVE_INTENT_VISIBLE");
        ForumFavoriteSyncState.Change pending = only(SCOPE_A); check(pending.desired && pending.revision == revision && KEY.equals(pending.key), "POSITIVE_INTENT_PENDING");
        NgaStore reopened = new NgaStore(getTargetContext()); check(reopened.favoriteForums().contains(KEY) && reopened.pendingFavoriteChanges(SCOPE_A).get(0).revision == revision, "POSITIVE_INTENT_DURABLE");
        done("POSITIVE_INTENT_DURABLE", "positiveIntentDurable");
    }
    private void negative() throws Exception {
        long revision = store.queueFavorite(SCOPE_A, KEY, "合成子版块", "", false); ForumFavoriteSyncState.Change pending = only(SCOPE_A);
        check(!store.favoriteForums().contains(KEY) && !pending.desired && pending.revision == revision, "NEGATIVE_INTENT_TOMBSTONE");
        check(!new NgaStore(getTargetContext()).favoriteForums().contains(KEY), "NEGATIVE_INTENT_DURABLE");
        done("NEGATIVE_INTENT_DURABLE", "negativeIntentDurable");
    }
    private void stale() throws Exception {
        long old = store.queueFavorite(SCOPE_A, KEY, "合成子版块", "", true);
        long middle = store.queueFavorite(SCOPE_A, KEY, "合成子版块", "", false);
        long latest = store.queueFavorite(SCOPE_A, KEY, "合成子版块", "", true);
        check(old < middle && middle < latest, "ABA_REVISIONS_INCREASE");
        check(!store.confirmFavorite(SCOPE_A, KEY, old, true, rows(KEY)), "STALE_MATCHING_STATE_CANNOT_CONFIRM_NEW_INTENT");
        check(!store.confirmFavorite(SCOPE_A, KEY, middle, false, rows()), "STALE_NEGATIVE_CANNOT_CONFIRM_NEW_INTENT");
        check(only(SCOPE_A).revision == latest && store.favoriteForums().contains(KEY), "NEWEST_INTENT_RETAINED");
        done("RAPID_ABA_REJECTS_STALE_ACKNOWLEDGEMENT", "staleAcknowledgementRejected");
    }
    private void confirmOnlyObserved() throws Exception {
        ForumFavoriteSyncState.Change pending = only(SCOPE_A);
        check(!store.confirmFavorite(SCOPE_A, KEY, pending.revision, true, rows()), "MISMATCHING_REMOTE_MEMBERSHIP_NOT_SUCCESS");
        check(only(SCOPE_A).revision == pending.revision, "UNCONFIRMED_INTENT_REMAINS");
        check(store.confirmFavorite(SCOPE_A, KEY, pending.revision, true, rows(KEY)), "CURRENT_MATCHING_READBACK_CONFIRMS");
        check(store.pendingFavoriteChanges(SCOPE_A).isEmpty() && store.favoriteForums().contains(KEY), "CONFIRM_RETAINS_REMOTE_PROJECTION");
        done("ONLY_OBSERVED_MEMBERSHIP_CONFIRMS", "unconfirmedIntentRetained");
    }
    private void scopes() throws Exception {
        long revision = store.queueFavorite(SCOPE_A, COLLECTION, "合成合集", "", true);
        store.activateFavoriteScope(SCOPE_B);
        check(!store.favoriteForums().contains(COLLECTION) && store.pendingFavoriteChanges(SCOPE_B).isEmpty(), "SECOND_SCOPE_NOT_INHERIT_PENDING");
        check(!store.confirmFavorite(SCOPE_A, COLLECTION, revision, true, rows(COLLECTION)), "INACTIVE_SCOPE_CALLBACK_REJECTED");
        store.queueFavorite(SCOPE_B, COLLECTION, "合成合集 B", "", false); check(!only(SCOPE_B).desired, "SECOND_SCOPE_INDEPENDENT_INTENT");
        store.activateFavoriteScope(SCOPE_A); check(store.favoriteForums().contains(COLLECTION) && only(SCOPE_A).desired, "FIRST_SCOPE_RESTORES_OWN_PENDING");
        done("ACCOUNT_SCOPES_REMAIN_ISOLATED", "scopeIsolation");
    }
    private void mergePending() throws Exception {
        store.queueFavorite(SCOPE_A, KEY, "合成子版块", "", false);
        check(store.mergeFavoriteForums(SCOPE_A, rows(KEY, COLLECTION)) >= 0, "REMOTE_MERGE_ACCEPTED");
        check(!store.favoriteForums().contains(KEY) && store.favoriteForums().contains(COLLECTION), "PENDING_REMOVAL_NOT_RESURRECTED");
        check(store.pendingFavoriteChanges(SCOPE_A).size() == 2, "REMOTE_READ_DOES_NOT_ACKNOWLEDGE_PENDING");
        done("IMPORT_PRESERVES_PENDING_DESIRED_STATE", "importPreservesPending");
    }
    private void metadata() throws Exception {
        store.queueFavorite(SCOPE_A, COLLECTION, "合成独立合集名称", "", false);
        check("合成独立合集名称".equals(store.favoriteForumName(COLLECTION)), "INTENT_METADATA_CACHED");
        store.queueFavorite(SCOPE_A, COLLECTION, "合成独立合集名称", "", true);
        boolean found = false; for (ForumFavoriteSyncState.Change row : store.pendingFavoriteChanges(SCOPE_A)) if (COLLECTION.equals(row.key)) found = "合成独立合集名称".equals(row.name);
        check(found, "LATEST_PENDING_METADATA_PRESERVED"); done("METADATA_SURVIVES_REMOVE_AND_READD", "metadataRetained");
    }
    private void durableRetry() throws Exception {
        List<ForumFavoriteSyncState.Change> pending = store.pendingFavoriteChanges(SCOPE_A); NgaStore reopened = new NgaStore(getTargetContext());
        check(pending.size() == reopened.pendingFavoriteChanges(SCOPE_A).size(), "RETRY_PENDING_COUNT_DURABLE");
        for (int i = 0; i < pending.size(); i++) { ForumFavoriteSyncState.Change row = reopened.pendingFavoriteChanges(SCOPE_A).get(i); check(pending.get(i).key.equals(row.key) && pending.get(i).desired == row.desired && pending.get(i).revision == row.revision, "RETRY_LATEST_INTENT_DURABLE"); }
        done("UNCONFIRMED_PENDING_CAN_BE_REOPENED", "pendingRetryDurable");
    }
    private void board() throws Exception {
        activateUiScope(); LinearLayout parent = new LinearLayout(activity);
        FavoriteProbeState.invoke(activity, "boardRow", new Class<?>[]{LinearLayout.class, String[].class}, parent, row(KEY));
        TextView star = favorite(parent, KEY); verifyControl(star, KEY); check(!star.isSelected(), "DIRECTORY_CONTROL_INITIAL_STATE");
        done("DIRECTORY_STAR_BINDING", "directoryFavoriteControl");
    }
    private void subForum() throws Exception {
        NgaApi.Forum forum = new NgaApi.Forum(); forum.key = COLLECTION; forum.stid = COLLECTION.substring(5); forum.name = "合成合集"; forum.category = "合集";
        LinearLayout parent = new LinearLayout(activity);
        FavoriteProbeState.invoke(activity, "subForumRow", new Class<?>[]{LinearLayout.class, NgaApi.Forum.class}, parent, forum);
        TextView star = favorite(parent, COLLECTION); verifyControl(star, COLLECTION); check(!star.isSelected(), "CHILD_CONTROL_INITIAL_STATE");
        done("CHILD_AND_COLLECTION_STAR_BINDING", "childFavoriteControl");
    }
    private void binding() throws Exception {
        store.toggleFavorite(KEY); TextView star = bound(KEY); check(star.isSelected() && star.getContentDescription().toString().contains("取消收藏"), "SHARED_CONTROL_LOCAL_SELECTED_STATE");
        store.toggleFavorite(KEY); star = bound(KEY); check(!star.isSelected() && star.getContentDescription().toString().contains("收藏"), "SHARED_CONTROL_LOCAL_UNSELECTED_STATE");
        done("TOOLBAR_USES_SHARED_NATIVE_BINDING", "sharedFavoriteBinding");
    }
    private void pendingBinding() throws Exception {
        String actual = FavoriteProbeState.scope(store); store.queueFavorite(actual, KEY, "合成子版块", "", true);
        TextView star = bound(KEY); String description = star.getContentDescription().toString();
        check(star.isSelected() && (description.contains("待同步") || description.contains("正在同步")), "PENDING_CONTROL_IS_NOT_FAKE_CLOUD_SUCCESS");
        store.queueFavorite(actual, KEY, "合成子版块", "", false); star = bound(KEY);
        check(!star.isSelected() && star.getContentDescription().toString().contains("同步"), "PENDING_REMOVAL_VISIBLE");
        done("PENDING_DESCRIPTION_AND_SELECTED_STATE", "pendingStateVisible");
    }
    private void activateUiScope() throws Exception { store.activateFavoriteScope(FavoriteProbeState.scope(store)); }
    private TextView bound(String key) throws Exception {
        TextView star = new TextView(activity); star.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        FavoriteProbeState.invoke(activity, "bindForumFavorite", new Class<?>[]{TextView.class, String[].class, Runnable.class}, star, row(key), null);
        verifyControl(star, key); return star;
    }
    private void verifyControl(TextView star, String key) throws Exception {
        check(star != null && ("forum-favorite:" + key).equals(star.getTag()), "NATIVE_FAVORITE_CONTROL_TAG");
        check(star.getLayoutParams() != null && star.getLayoutParams().width >= dp(48) && star.getLayoutParams().height >= dp(48), "NATIVE_FAVORITE_TOUCH_TARGET");
        check(star.isClickable() && star.getContentDescription() != null && star.getContentDescription().toString().contains("收藏"), "NATIVE_FAVORITE_ACCESSIBILITY");
    }
    private TextView favorite(View view, String key) throws ProbeFailure {
        if (("forum-favorite:" + key).equals(view.getTag()) && view instanceof TextView) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { TextView found = favoriteOrNull(((ViewGroup) view).getChildAt(i), key); if (found != null) return found; }
        throw new ProbeFailure("NATIVE_FAVORITE_CONTROL_MISSING");
    }
    private TextView favoriteOrNull(View view, String key) {
        if (("forum-favorite:" + key).equals(view.getTag()) && view instanceof TextView) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { TextView found = favoriteOrNull(((ViewGroup) view).getChildAt(i), key); if (found != null) return found; } return null;
    }
    private ForumFavoriteSyncState.Change only(String scope) throws Exception { List<ForumFavoriteSyncState.Change> values = store.pendingFavoriteChanges(scope); check(values.size() == 1, "EXPECTED_SINGLE_PENDING_INTENT"); return values.get(0); }
    private static String[] row(String key) { return new String[]{key, key.startsWith("stid:") ? "合成合集" : "合成子版块", "子版块", "N"}; }
    private static Map<String,String[]> rows(String... keys) { Map<String,String[]> values = new LinkedHashMap<>(); for (String key : keys) values.put(key, new String[]{"合成名称", ""}); return values; }
    private int dp(int value) { return (int) (value * getTargetContext().getResources().getDisplayMetrics().density + .5f); }
    private void done(String scenario, String flag) { JSONObject row = new JSONObject(); put(row, "scenario", scenario); put(row, "passed", true); cases.put(row); put(report, flag, true); }
    private void check(boolean value, String code) throws ProbeFailure { assertions++; if (!value) throw new ProbeFailure(code); }
    private <T> T onMain(Callable<T> action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); Object[] values = new Object[1]; Throwable[] errors = new Throwable[1];
        main.post(() -> { try { values[0] = action.call(); } catch (Throwable error) { errors[0] = error; } finally { latch.countDown(); } });
        if (!latch.await(10000, TimeUnit.MILLISECONDS)) throw new ProbeFailure("MAIN_THREAD_DEADLINE");
        if (errors[0] instanceof Exception) throw (Exception) errors[0]; if (errors[0] != null) throw new ProbeFailure("MAIN_THREAD_FAILED");
        @SuppressWarnings("unchecked") T value = (T) values[0]; return value;
    }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value); } catch (Exception ignored) { /* Fixed enums, counters and flags only. */ } }
    private static final class ProbeFailure extends Exception { private static final long serialVersionUID = 1L; final String code; ProbeFailure(String code) { this.code = code; } }
}
