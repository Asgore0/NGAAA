package cn.ngapure.verify;

import android.app.Instrumentation;
import android.os.Bundle;
import cn.ngapure.app.NgaApi;
import cn.ngapure.app.RequestCancellation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Runs on Android's actual org.json, then reads one public homepage-feature thread.
 * No Activity launch, check-in, authorization, SMS, message, or other remote write.
 * Output contains only booleans/counts and exception class; no thread/user content or credentials.
 */
public final class PaginationProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        final JSONObject report = new JSONObject();
        final RequestCancellation.Token token = new RequestCancellation.Token();
        ExecutorService worker = Executors.newSingleThreadExecutor(action -> {
            Thread thread = new Thread(action, "pagination-public-read"); thread.setDaemon(true); return thread;
        });
        try {
            Future<Boolean> result = worker.submit(() -> {
                RequestCancellation.Scope scope = RequestCancellation.bind(token);
                try { run(report); return true; }
                finally { scope.close(); }
            });
            result.get(60, TimeUnit.SECONDS);
            report.put("passed", true); report.put("failure", "NONE");
        } catch (Exception failure) {
            put(report, "passed", false); put(report, "failure", failure.getClass().getSimpleName());
        } finally {
            token.cancel(); worker.shutdownNow();
            Bundle output = new Bundle(); output.putString("native_pagination_report", report.toString()); finish(0, output);
        }
    }
    private void run(JSONObject report) throws Exception {
        boolean incompatible = false;
        try { new JSONTokener("{0:'/read.php?tid=123',1:65,2:1,3:20}").nextValue(); }
        catch (org.json.JSONException expected) { incompatible = true; }
        require(incompatible, "actualAndroidJsonRejectsNumericKeys", report);
        NgaApi api = new NgaApi(getTargetContext());
        String html = "<div id='postliststart'></div><script>var __PAGE={0:'/read.php?tid=123;brace}\\\'quoted',1:65,2:1,3:20};"
                + "var __CURRENT_TID=123;commonui.userInfo.setAll({\"1\":{\"username\":\"synthetic\"}});</script>"
                + "<table class='postbox'><a id='pid0Anchor'></a><a href='/nuke.php?uid=1' id='postauthor0'>synthetic</a><p id='postcontent0'>synthetic</p><a name='l0'></a></table>";
        JSONObject envelope = new JSONObject().put("template", "read").put("html", html);
        NgaApi.ThreadPage page = (NgaApi.ThreadPage) invoke(api, "parseHtmlThread",
                new Class<?>[]{JSONObject.class, String.class, int.class}, envelope, "123", 1);
        require(page.totalPages == 65 && page.page == 1 && page.posts.size() == 1, "numericHtmlPagination", report);
        require(page.posts.get(0).author.equals("synthetic"), "quotedUserTableStillParses", report);
        String votesHtml = "<div id='postliststart'></div><script>var __CURRENT_TID=123;var __PAGE={0:'/read.php?tid=123',1:65,2:1,3:20};"
                + "postArg.proc(0,$('container'),$('subject'),$('content'),$('sign'),$('user'),$('info'),$('button'),null,null,0,0,null,'1',123,'0,15,4','4','',null);</script>";
        @SuppressWarnings("unchecked") Map<String, cn.ngapure.app.VoteMetrics> votes = (Map<String, cn.ngapure.app.VoteMetrics>) invoke(api,
                "parsePostVotes", new Class<?>[]{JSONObject.class, String.class, int.class},
                new JSONObject().put("template", "read").put("html", votesHtml), "123", 1);
        require(votes.containsKey("0:0") && votes.get("0:0").likesKnown && votes.get("0:0").likes == 15, "numericVotePagination", report);
        boolean rejected = false;
        try { invoke(api, "legacyPageData", new Class<?>[]{String.class}, "var __PAGE={2:(function(){return 1})()};"); }
        catch (java.io.IOException expected) { rejected = true; }
        require(rejected, "executableValueRejected", report);
        NgaApi.ThreadPage publicPage = api.thread("47717100", 1);
        require(publicPage.page == 1 && !publicPage.posts.isEmpty(), "homepageFeatureRead", report);
        report.put("publicPostCount", publicPage.posts.size()); report.put("publicPage", publicPage.page);
        report.put("publicTotalPages", publicPage.totalPages); report.put("remoteWritesPerformed", false);
    }
    private static Object invoke(NgaApi api, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = NgaApi.class.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(api, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            throw failure;
        }
    }
    private static void require(boolean value, String key, JSONObject report) throws Exception {
        report.put(key, value); if (!value) throw new java.io.IOException("Synthetic probe condition failed");
    }
    private static void put(JSONObject report, String key, Object value) {
        try { report.put(key, value); } catch (org.json.JSONException ignored) { }
    }
}
