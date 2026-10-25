package cn.ngapure.verify;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.os.Bundle;
import cn.ngapure.app.CloudForumFavorites;
import cn.ngapure.app.NativeCommunity;
import cn.ngapure.app.NativeLoginView;
import cn.ngapure.app.NgaStore;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Explicitly authorized, single read of cloud board counts. Never imports or mutates. */
public final class CloudFavoritesReadProbe extends Instrumentation {
    private boolean authorized;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        authorized = args != null && "true".equals(args.getString("readOnlyCloud"));
        start();
    }
    @Override public void onStart() {
        JSONObject report = new JSONObject();
        boolean passed = false, performed = false, unchanged = false;
        int forums = -1, collections = -1, total = -1;
        String error = "NONE", host = "", account = "", cookie = "";
        Map<String,Object> before = null;
        NgaStore store = new NgaStore(getTargetContext());
        SharedPreferences preferences = getTargetContext().getSharedPreferences("nga_local", 0);
        try {
            before = copy(preferences.getAll()); host = store.host(); account = store.accountId(); cookie = store.cookie();
            if (!authorized) throw new ReadFailure("NOT_AUTHORIZED");
            if (!store.loggedIn() || account.isEmpty()) throw new ReadFailure("SESSION_UNAVAILABLE");
            if (!NgaStore.today().equals(store.lastCheckInDay())) throw new ReadFailure("SIGN_IN_GUARD_FAILED");
            if (NativeLoginView.hasPendingAuthorization(getTargetContext(), host)) throw new ReadFailure("AUTHORIZATION_PENDING");
            CloudForumFavorites source = new CloudForumFavorites(new NativeCommunity(getTargetContext(), host, account));
            performed = true;
            CloudForumFavorites.Snapshot result = source.read();
            forums = result.forumCount; collections = result.collectionCount; total = result.entries.size();
            if (forums < 0 || collections < 0 || total != forums + collections) throw new ReadFailure("INVALID_COUNTS");
            passed = true;
        } catch (ReadFailure failed) { error = failed.code; }
        catch (Exception | AssertionError unavailable) { error = "CLOUD_READ_FAILED"; }
        finally {
            unchanged = before != null && before.equals(copy(preferences.getAll()))
                    && host.equals(store.host()) && account.equals(store.accountId()) && cookie.equals(store.cookie());
            if (!unchanged) { passed = false; error = "ACCOUNT_OR_SETTINGS_CHANGED"; }
            if (before != null) before.clear(); host = account = cookie = "";
        }
        put(report, "schema", 1); put(report, "purpose", "READ_ONLY_CLOUD_FORUM_FAVORITES_COUNTS");
        put(report, "readPerformed", performed); put(report, "readOnly", true);
        put(report, "forumCount", forums); put(report, "collectionCount", collections); put(report, "totalCount", total);
        put(report, "accountAndSettingsUnchanged", unchanged); put(report, "realRemoteMutations", 0);
        put(report, "passed", passed); put(report, "error", passed ? "NONE" : error);
        Bundle output = new Bundle(); output.putString("safe_cloud_favorites_read_report", report.toString());
        finish(Activity.RESULT_OK, output);
    }
    private static Map<String,Object> copy(Map<String,?> values) {
        Map<String,Object> result = new HashMap<>();
        for (Map.Entry<String,?> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Set<?>) value = new HashSet<>((Set<?>) value);
            result.put(entry.getKey(), value);
        }
        return result;
    }
    private static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value); } catch (Exception ignored) { /* Counts and fixed enums only. */ }
    }
    private static final class ReadFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final String code; ReadFailure(String code) { this.code = code; }
    }
}
