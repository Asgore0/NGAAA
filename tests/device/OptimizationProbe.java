package cn.ngapure.verify;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import cn.ngapure.app.NativeLoginView;
import cn.ngapure.app.NgaApi;
import cn.ngapure.app.NgaStore;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public reading/recreation only. Never outputs URLs, IDs, text or credentials.
 * Refuses to launch/recreate the Activity if its normal on-open hook could sign in,
 * or if a pending QR authorization would be disturbed. No data or account reset.
 */
public final class OptimizationProbe extends Instrumentation {
    private static final long LIMIT_MILLIS = 60000;
    private static final String MAIN_ACTIVITY = "cn.ngapure.app.MainActivity";
    private final AtomicBoolean finished = new AtomicBoolean();
    private final JSONObject report = defaults();
    private final Handler main = new Handler(Looper.getMainLooper());
    private long deadline;
    private String stage = "NOT_STARTED";
    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        volatile Activity resumed;
        @Override public void onActivityResumed(Activity activity) { if (MAIN_ACTIVITY.equals(activity.getClass().getName())) resumed = activity; }
        @Override public void onActivityDestroyed(Activity activity) { if (resumed == activity) resumed = null; }
        @Override public void onActivityCreated(Activity activity, Bundle state) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    }

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        deadline = SystemClock.elapsedRealtime() + LIMIT_MILLIS;
        Thread watchdog = new Thread(() -> { SystemClock.sleep(LIMIT_MILLIS); complete("DEADLINE_EXCEEDED"); }, "optimization-probe-deadline");
        watchdog.setDaemon(true); watchdog.start();
        ExecutorService reads = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "optimization-probe-public-read"); t.setDaemon(true); return t; });
        Application app = null; Lifecycle lifecycle = new Lifecycle(); boolean registered = false;
        try {
            NgaStore store = new NgaStore(getTargetContext());
            guard(store); put("onOpenSignInGuardPassed", true);
            if (NativeLoginView.hasPendingAuthorization(getTargetContext(), store.host())) throw new ProbeFailure("PENDING_QR_AUTHORIZATION");
            put("pendingAuthorizationGuardPassed", true);
            setStage("SELECT_PUBLIC_TOPIC");
            Future<Selection> source = reads.submit(() -> select(new NgaApi(getTargetContext())));
            Selection selected = source.get(remaining(), TimeUnit.MILLISECONDS);
            if (selected == null) throw new ProbeFailure("NO_ELIGIBLE_PUBLIC_THREAD");
            put("eligibleTopicFound", true); put("expectedPage", selected.page);
            app = (Application) getTargetContext().getApplicationContext();
            final Application targetApp = app;
            onMain(() -> { targetApp.registerActivityLifecycleCallbacks(lifecycle); return null; }); registered = true;
            setStage("HOME_BASELINE");
            Intent launch = new Intent(Intent.ACTION_MAIN).setClassName("cn.ngapure.app", MAIN_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            guard(store);
            onMain(() -> { getTargetContext().startActivity(launch); return null; });
            Activity initial = awaitActivity(lifecycle, null); put("activityStarted", true);
            Sample home = awaitPage(initial, null, false, null);
            Sample homeCustom = customScroll(initial, null, false, home);
            final int homeOffset = homeCustom.y; put("homeExpectedScrollY", homeOffset);

            setStage("PENDING_TAB_SAVE");
            boolean[] pendingSave = onMain(() -> {
                int rendered = (Integer) field(initial, "mainTab");
                Field pending = initial.getClass().getDeclaredField("pendingHomeTab"); pending.setAccessible(true);
                int previousPending = pending.getInt(initial);
                try {
                    // Simulate the quiet window without scheduling navigation or opening another tab.
                    pending.setInt(initial, (rendered + 1) % 4);
                    Bundle saved = new Bundle(); callActivityOnSaveInstanceState(initial, saved);
                    Bundle navigation = saved.getBundle("ngapure_navigation_v1");
                    String[] current = navigation == null ? null : navigation.getStringArray("current");
                    boolean sameOffset = current != null && current.length == 10 && "home".equals(current[0])
                            && Integer.toString(homeOffset).equals(current[9]) && homeCustom.scroll.getScrollY() == homeOffset;
                    return new boolean[]{saved.getInt("mainTab", -1) == rendered, sameOffset};
                } finally { pending.setInt(initial, previousPending); }
            });
            put("pendingTabSavePreservedRenderedTab", pendingSave[0]); put("pendingTabSavePreservedOffset", pendingSave[1]);
            if (!pendingSave[0] || !pendingSave[1]) throw new ProbeFailure("PENDING_TAB_SAVE_MISMATCH");

            setStage("ORDINARY_THREAD");
            onMain(() -> { initial.startActivity(viewIntent(store.host(), selected, false)); return null; });
            Sample ordinary = awaitPage(initial, selected, false, null);
            Sample ordinaryCustom = customScroll(initial, selected, false, ordinary);
            put("ordinaryExpectedScrollY", ordinaryCustom.y); put("ordinaryBaselinePage", ordinaryCustom.page);
            setStage("ORDINARY_RECREATE");
            guard(store); onMain(() -> { initial.recreate(); return null; });
            Activity ordinaryReplacement = awaitActivity(lifecycle, initial);
            Sample ordinaryRestored = awaitPage(ordinaryReplacement, selected, false, ordinaryCustom.y);
            boolean ordinaryPass = ordinaryReplacement != initial && ordinaryRestored.scroll != ordinaryCustom.scroll;
            put("ordinaryFreshActivity", ordinaryReplacement != initial); put("ordinaryFreshScroll", ordinaryRestored.scroll != ordinaryCustom.scroll);
            put("ordinaryActualScrollY", ordinaryRestored.y); put("ordinaryActualPage", ordinaryRestored.page); put("ordinaryOffsetRestored", ordinaryPass);
            setStage("ORDINARY_BACK_HOME");
            onMain(() -> { back(ordinaryReplacement); return null; });
            awaitPage(ordinaryReplacement, null, false, homeOffset); put("ordinaryBackHomeRestored", true);

            setStage("PID_THREAD");
            onMain(() -> { ordinaryReplacement.startActivity(viewIntent(store.host(), selected, true)); return null; });
            Sample located = awaitPage(ordinaryReplacement, selected, true, null);
            put("pidInitialLocationVerified", located.located);
            Sample pidCustom = customScroll(ordinaryReplacement, selected, true, located);
            put("pidExpectedScrollY", pidCustom.y); put("pidBaselinePage", pidCustom.page);
            put("pidDeliberatelyAwayFromTarget", Math.abs(pidCustom.y - pidCustom.targetY) > pidCustom.tolerance * 2);
            setStage("PID_RECREATE");
            guard(store); onMain(() -> { ordinaryReplacement.recreate(); return null; });
            Activity pidReplacement = awaitActivity(lifecycle, ordinaryReplacement);
            Sample pidRestored = awaitPage(pidReplacement, selected, true, pidCustom.y);
            boolean pidPass = pidReplacement != ordinaryReplacement && pidRestored.scroll != pidCustom.scroll;
            put("pidFreshActivity", pidReplacement != ordinaryReplacement); put("pidFreshScroll", pidRestored.scroll != pidCustom.scroll);
            put("pidActualScrollY", pidRestored.y); put("pidActualPage", pidRestored.page); put("pidCustomOffsetRestored", pidPass);
            setStage("MEMORY_TRIM");
            onMain(() -> { trim(pidReplacement); return null; }); put("trimMemoryInvoked", true);
            awaitPage(pidReplacement, selected, true, pidCustom.y); put("trimPreservedReadingGeometry", true);
            setStage("PID_BACK_HOME");
            onMain(() -> { back(pidReplacement); return null; });
            awaitPage(pidReplacement, null, false, homeOffset); put("pidBackHomeRestored", true);
            put("passed", ordinaryPass && pidPass); setStage("COMPLETE"); complete("NONE");
        } catch (ProbeFailure failure) { complete(failure.code); }
        catch (java.util.concurrent.TimeoutException timeout) { complete("DEADLINE_EXCEEDED"); }
        catch (Exception unavailable) { complete("READ_OR_UI_UNAVAILABLE"); }
        finally {
            reads.shutdownNow();
            if (registered && app != null) app.unregisterActivityLifecycleCallbacks(lifecycle);
        }
    }
    private static void guard(NgaStore store) throws ProbeFailure {
        if (store.autoCheckIn() && (!store.loggedIn() || !NgaStore.today().equals(store.lastCheckInDay())))
            throw new ProbeFailure("ON_OPEN_SIGN_IN_NOT_READ_ONLY");
    }
    private static Selection select(NgaApi api) throws Exception {
        List<NgaApi.Topic> topics = api.topics("414", 1, "");
        for (NgaApi.Topic topic : topics) {
            if (topic.replies < 30 || !positive(topic.tid)) continue;
            NgaApi.ThreadPage page = api.thread(topic.tid, 2);
            if (page.page != 2 || page.posts.size() < 2) return null;
            for (NgaApi.Post post : page.posts) if (positive(post.pid)) return new Selection(topic.tid, post.pid, post.floor, page.page);
            return null;
        }
        return null;
    }
    private static boolean positive(String value) { return value != null && value.matches("[1-9][0-9]{0,17}"); }
    private static Intent viewIntent(String host, Selection selected, boolean withPid) {
        Uri.Builder address = new Uri.Builder().scheme("https").authority(host).path("/read.php")
                .appendQueryParameter("tid", selected.tid).appendQueryParameter("page", Integer.toString(selected.page));
        if (withPid) address.appendQueryParameter("pid", selected.pid);
        return new Intent(Intent.ACTION_VIEW, address.build()).setClassName("cn.ngapure.app", MAIN_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    private Activity awaitActivity(Lifecycle lifecycle, Activity previous) throws Exception {
        while (remaining() > 0) {
            Activity value = lifecycle.resumed;
            if (value != null && value != previous && onMain(() -> !value.isFinishing() && !value.isDestroyed())) return value;
            SystemClock.sleep(Math.min(50, remaining()));
        }
        throw new ProbeFailure("ACTIVITY_RECREATION_TIMEOUT");
    }
    private Sample awaitPage(Activity activity, Selection selected, boolean withPid, Integer expectedOffset) throws Exception {
        Sample previous = null; int stable = 0;
        while (remaining() > 0) {
            Sample value = onMain(() -> sample(activity, selected, withPid));
            put("lastScrollY", value.y); put("lastPage", value.page); put("lastContentHeight", value.height); put("lastViewportHeight", value.viewport);
            boolean offset = expectedOffset == null ? (!withPid || value.located) : Math.abs(value.y - expectedOffset) <= value.tolerance;
            if (value.ready && offset && previous != null && value.scroll == previous.scroll
                    && value.height == previous.height && value.viewport == previous.viewport && Math.abs(value.y - previous.y) <= value.tolerance) {
                if (++stable >= 3) return value;
            } else stable = 0;
            previous = value; SystemClock.sleep(Math.min(150, remaining()));
        }
        throw new ProbeFailure("PAGE_RESTORATION_TIMEOUT");
    }
    private Sample customScroll(Activity activity, Selection selected, boolean withPid, Sample baseline) throws Exception {
        int target = onMain(() -> {
            Sample current = sample(activity, selected, withPid);
            if (!current.ready || current.maximum < current.tolerance * 8) throw new ProbeFailure("PAGE_NOT_SCROLLABLE");
            int candidate = Math.min(current.maximum, Math.max(current.tolerance * 8, Math.round(480 * activity.getResources().getDisplayMetrics().density)));
            if (withPid && Math.abs(candidate - current.targetY) <= current.tolerance * 4)
                candidate = current.targetY < current.maximum / 2 ? current.maximum : Math.max(current.tolerance * 8, current.maximum / 4);
            if (candidate <= 0 || (withPid && Math.abs(candidate - current.targetY) <= current.tolerance * 2))
                throw new ProbeFailure("CUSTOM_OFFSET_NOT_DISTINCT");
            current.scroll.scrollTo(0, candidate); return current.scroll.getScrollY();
        });
        Sample result = awaitPage(activity, selected, withPid, target);
        if (result.scroll != baseline.scroll) throw new ProbeFailure("UNEXPECTED_SCROLL_REPLACEMENT");
        return result;
    }
    private static Sample sample(Activity activity, Selection selected, boolean withPid) throws Exception {
        Sample result = new Sample(); result.tolerance = Math.max(4, Math.round(12 * activity.getResources().getDisplayMetrics().density));
        if (activity.isFinishing() || activity.isDestroyed()) return result;
        Object screen = field(activity, "current"); if (screen == null) return result;
        Object raw = field(activity, "currentScroll"); if (!(raw instanceof ScrollView)) return result;
        result.scroll = (ScrollView) raw; result.y = result.scroll.getScrollY();
        if (result.scroll.getHeight() <= 0 || result.scroll.getChildCount() != 1) return result;
        View content = result.scroll.getChildAt(0); result.height = content.getHeight();
        result.viewport = result.scroll.getHeight() - result.scroll.getPaddingTop() - result.scroll.getPaddingBottom();
        result.maximum = Math.max(0, result.height - result.viewport);
        if (result.height <= 0 || result.viewport <= 0) return result;
        if (selected == null) {
            if (!"home".equals(field(screen, "kind")) || !Integer.valueOf(0).equals(field(activity, "mainTab"))) return result;
            // Fixed UI loading marker only; arbitrary post text never enters the report.
            result.ready = !containsText(content, "正在加载社区内容…") && result.maximum > result.tolerance * 8;
            return result;
        }
        if (!"thread".equals(field(screen, "kind")) || !selected.tid.equals(field(screen, "id"))) return result;
        String expectedPid = withPid ? selected.pid : "";
        if (!expectedPid.equals(field(screen, "pid"))) return result;
        Object page = field(screen, "page"); if (page instanceof Integer) result.page = (Integer) page;
        Object posts = field(activity, "nativePostCount");
        result.ready = result.page == selected.page && posts instanceof Integer && (Integer) posts >= 2;
        View pageControl = findDescription(activity.getWindow().getDecorView(), "跳转页码");
        result.ready &= pageControl instanceof TextView && ((TextView) pageControl).getText().toString().startsWith(selected.page + " / ");
        if (withPid) {
            View row = findFloorRow(content, selected.floor == 0 ? "楼主" : "#" + selected.floor);
            if (row == null || row.getHeight() <= 0) { result.ready = false; return result; }
            result.targetY = Math.min(row.getTop(), result.maximum);
            result.located = Math.abs(result.y - result.targetY) <= result.tolerance;
        }
        return result;
    }
    private static boolean containsText(View view, String expected) {
        if (view instanceof TextView && expected.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (containsText(((ViewGroup) view).getChildAt(i), expected)) return true;
        return false;
    }
    private static View findDescription(View view, String expected) {
        if (expected.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = findDescription(((ViewGroup) view).getChildAt(i), expected); if (found != null) return found;
        }
        return null;
    }
    private static View findFloorRow(View content, String expected) {
        if (!(content instanceof ViewGroup)) return null;
        ViewGroup rows = (ViewGroup) content;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i); if (!(row instanceof ViewGroup)) continue;
            ViewGroup meta = (ViewGroup) row;
            // Keep returning the complete production card for reply scroll geometry.
            // Only its first metadata row may identify a floor; body text is ignored.
            if ("post-card".equals(row.getTag())) {
                if (meta.getChildCount() == 0 || !(meta.getChildAt(0) instanceof ViewGroup)) continue;
                meta = (ViewGroup) meta.getChildAt(0);
            }
            if (meta.getChildCount() == 0) continue;
            View label = meta.getChildAt(meta.getChildCount() - 1);
            if (label instanceof TextView && expected.contentEquals(((TextView) label).getText())) return row;
        }
        return null;
    }
    @SuppressWarnings("deprecation") private static void back(Activity activity) { activity.onBackPressed(); }
    @SuppressWarnings("deprecation") private static void trim(Activity activity) { activity.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW); }
    private <T> T onMain(Callable<T> action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); final Object[] result = new Object[1]; final String[] failure = {null};
        main.post(() -> {
            try { if (finished.get()) failure[0] = "DEADLINE_EXCEEDED"; else result[0] = action.call(); }
            catch (ProbeFailure known) { failure[0] = known.code; }
            catch (Exception unavailable) { failure[0] = "UI_SAMPLE_UNAVAILABLE"; }
            finally { latch.countDown(); }
        });
        if (!latch.await(remaining(), TimeUnit.MILLISECONDS)) throw new ProbeFailure("MAIN_THREAD_TIMEOUT");
        if (failure[0] != null) throw new ProbeFailure(failure[0]);
        @SuppressWarnings("unchecked") T typed = (T) result[0]; return typed;
    }
    private long remaining() throws ProbeFailure {
        long value = deadline - SystemClock.elapsedRealtime();
        if (value <= 0 || finished.get()) throw new ProbeFailure("DEADLINE_EXCEEDED"); return value;
    }
    private static Object field(Object owner, String name) throws Exception { Field value = owner.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(owner); }
    private void setStage(String value) { stage = value; put("stage", value); }
    private void put(String key, Object value) { synchronized (report) { if (!finished.get()) try { report.put(key, value); } catch (Exception ignored) { } } }
    private void complete(String error) {
        if (!finished.compareAndSet(false, true)) return;
        synchronized (report) {
            try { report.put("stage", stage); report.put("errorType", error); } catch (Exception ignored) { }
            Bundle result = new Bundle(); result.putString("safe_optimization_report", report.toString()); finish(Activity.RESULT_OK, result);
        }
    }
    private static JSONObject defaults() {
        JSONObject value = new JSONObject();
        try {
            value.put("schema", 1); value.put("limitMillis", LIMIT_MILLIS); value.put("stage", "NOT_STARTED"); value.put("errorType", "NOT_COMPLETED");
            for (String key : new String[]{"onOpenSignInGuardPassed", "pendingAuthorizationGuardPassed", "eligibleTopicFound", "activityStarted",
                    "pendingTabSavePreservedRenderedTab", "pendingTabSavePreservedOffset",
                    "ordinaryFreshActivity", "ordinaryFreshScroll", "ordinaryOffsetRestored", "ordinaryBackHomeRestored",
                    "pidInitialLocationVerified", "pidDeliberatelyAwayFromTarget", "pidFreshActivity", "pidFreshScroll", "pidCustomOffsetRestored",
                    "trimMemoryInvoked", "trimPreservedReadingGeometry", "pidBackHomeRestored", "passed"}) value.put(key, false);
            for (String key : new String[]{"expectedPage", "homeExpectedScrollY", "ordinaryExpectedScrollY", "ordinaryActualScrollY", "ordinaryBaselinePage", "ordinaryActualPage",
                    "pidExpectedScrollY", "pidActualScrollY", "pidBaselinePage", "pidActualPage", "lastScrollY", "lastPage", "lastContentHeight", "lastViewportHeight"}) value.put(key, 0);
        } catch (Exception unavailable) { throw new IllegalStateException("Safe optimization schema unavailable"); }
        return value;
    }
    private static final class Selection {
        final String tid, pid; final int floor, page;
        Selection(String tid, String pid, int floor, int page) { this.tid = tid; this.pid = pid; this.floor = floor; this.page = page; }
    }
    private static final class Sample {
        ScrollView scroll; int y, page, height, viewport, maximum, targetY, tolerance; boolean ready, located;
    }
    private static final class ProbeFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final String code; ProbeFailure(String code) { this.code = code; }
    }
}
