package cn.ngapure.verify;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import cn.ngapure.app.AvatarSources;
import cn.ngapure.app.AvatarView;
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

/** Public board/thread GETs and real VIEW navigation only. No account or remote writes.
 * Output is a fixed projection: no URLs, identifiers, text, credentials or exception messages.
 * A cold MainActivity also runs its normal on-open hook, so this probe refuses to launch it
 * unless automatic sign-in is disabled or its signed-in account is already confirmed today.
 */
public final class ReaderProbe extends Instrumentation {
    private static final long LIMIT_MILLIS = 45000;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final JSONObject report = defaults();
    private final Handler main = new Handler(Looper.getMainLooper());
    private long deadline, lastProgressAt;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        deadline = SystemClock.elapsedRealtime() + LIMIT_MILLIS;
        Thread watchdog = new Thread(() -> {
            SystemClock.sleep(LIMIT_MILLIS);
            complete("DEADLINE_EXCEEDED");
        }, "reader-probe-deadline");
        watchdog.setDaemon(true); watchdog.start();
        ExecutorService reads = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "reader-probe-public-read"); thread.setDaemon(true); return thread;
        });
        ActivityMonitor monitor = null;
        try {
            NgaStore store = new NgaStore(getTargetContext());
            if (store.autoCheckIn() && (!store.loggedIn() || !NgaStore.today().equals(store.lastCheckInDay())))
                throw new ProbeFailure("ON_OPEN_SIGN_IN_NOT_READ_ONLY");
            put("onOpenSignInGuardPassed", true);
            final NgaApi api = new NgaApi(getTargetContext());
            Future<Selection> source = reads.submit(() -> select(api));
            Selection selected = source.get(remaining(), TimeUnit.MILLISECONDS);
            if (selected == null) throw new ProbeFailure("NO_ELIGIBLE_PUBLIC_REPLY");
            put("eligibleTopicFound", true); put("positiveReplySelected", true);
            put("expectedPage", selected.page); put("loadedAvatarUrlCount", selected.avatarUrls);
            put("explicitLikesCount", selected.explicitLikes); put("scoreOnlyCount", selected.scoreOnly);
            monitor = addMonitor("cn.ngapure.app.MainActivity", null, false);
            Intent open = viewIntent(store.host(), selected);
            onMain(() -> { getTargetContext().startActivity(open); return null; });
            Activity activity = monitor.waitForActivityWithTimeout(remaining());
            if (activity == null) throw new ProbeFailure("ACTIVITY_START_TIMEOUT");
            put("activityStarted", true);
            Sample first = awaitLocated(activity, selected, null);
            put("actualPage", first.actualPage); put("pageMatches", first.pageMatches);
            put("pageControlMatches", first.pageControlMatches); put("previousEnabled", first.previousEnabled);
            put("selectedFloorLocated", first.located); put("boundAvatarUrlCount", first.boundAvatars);
            put("decodedAvatarBitmapCount", first.decodedAvatars);
            if (!first.accepted()) throw new ProbeFailure("FIRST_REPLY_LOCATION_UNCONFIRMED");
            final ScrollView oldScroll = first.scroll;
            boolean zeroed = onMain(() -> { oldScroll.scrollTo(0, 0); return oldScroll.getScrollY() == 0; });
            put("oldScrollResetToZero", zeroed); put("repeatAttempted", true);
            // A second explicit VIEW, without page, must override the saved zero offset.
            onMain(() -> { activity.startActivity(viewIntent(store.host(), selected)); return null; });
            Sample repeated = awaitLocated(activity, selected, oldScroll);
            put("repeatActualPage", repeated.actualPage); put("repeatPageMatches", repeated.pageMatches);
            put("repeatPageControlMatches", repeated.pageControlMatches);
            put("repeatPreviousEnabled", repeated.previousEnabled); put("repeatSelectedFloorLocated", repeated.located);
            put("freshScrollContainer", repeated.scroll != null && repeated.scroll != oldScroll);
            put("oldOffsetNotReused", zeroed && repeated.located && repeated.scrollY > 0 && repeated.scroll != oldScroll);
            put("passed", first.accepted() && repeated.accepted() && zeroed && repeated.scrollY > 0 && repeated.scroll != oldScroll);
            complete("NONE");
        } catch (ProbeFailure failure) { complete(failure.code); }
        catch (java.util.concurrent.TimeoutException timeout) { complete("DEADLINE_EXCEEDED"); }
        catch (Exception unavailable) { complete("READ_OR_UI_UNAVAILABLE"); }
        finally { reads.shutdownNow(); if (monitor != null) removeMonitor(monitor); }
    }

    private static Selection select(NgaApi api) throws Exception {
        List<NgaApi.Topic> topics = api.topics("414", 1, "");
        for (NgaApi.Topic topic : topics) {
            if (topic.replies < 30 || !positive(topic.tid)) continue;
            // Bounded to the first eligible public topic; no private/message endpoint or retry crawl.
            NgaApi.ThreadPage page = api.thread(topic.tid, 2);
            if (page.page < 2) return null;
            for (NgaApi.Post post : page.posts) if (positive(post.pid)) {
                Selection result = new Selection(topic.tid, post.pid, post.floor, page.page);
                for (NgaApi.Post item : page.posts) {
                    if (!AvatarSources.normalize(item.avatar).isEmpty()) result.avatarUrls++;
                    if (item.votes != null && item.votes.likesKnown) result.explicitLikes++;
                    else if (item.votes != null && item.votes.scoreKnown) result.scoreOnly++;
                }
                return result;
            }
            return null;
        }
        return null;
    }
    private static boolean positive(String value) { return value != null && value.matches("[1-9][0-9]{0,17}"); }
    private static Intent viewIntent(String host, Selection selected) {
        Uri uri = new Uri.Builder().scheme("https").authority(host).path("/read.php")
                .appendQueryParameter("tid", selected.tid).appendQueryParameter("pid", selected.pid).build();
        return new Intent(Intent.ACTION_VIEW, uri).setClassName("cn.ngapure.app", "cn.ngapure.app.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    private Sample awaitLocated(Activity activity, Selection selected, ScrollView previous) throws Exception {
        Sample latest = new Sample(); int stable = 0;
        while (!finished.get() && remaining() > 0) {
            latest = onMain(() -> sample(activity, selected, previous));
            publishSample(latest, previous != null);
            if (latest.accepted()) { if (++stable >= 2) return latest; } else stable = 0;
            SystemClock.sleep(Math.min(100, remaining()));
        }
        return latest;
    }
    private static Sample sample(Activity activity, Selection selected, ScrollView previous) throws Exception {
        Sample result = new Sample(); if (activity.isFinishing() || activity.isDestroyed()) return result;
        Object screen = field(activity, "current");
        if (screen != null) {
            result.screenKind = safeKind(field(screen, "kind"));
            result.idMatches = selected.tid.equals(field(screen, "id"));
            result.pidMatches = selected.pid.equals(field(screen, "pid"));
            Object actual = field(screen, "page");
            if (actual instanceof Integer) result.actualPage = (Integer) actual;
            result.pageMatches = result.actualPage == selected.page;
        }
        View root = activity.getWindow().getDecorView();
        View prior = findDescription(root, "上一页"); result.previousEnabled = prior != null && prior.isEnabled();
        View page = findDescription(root, "跳转页码");
        if (page instanceof TextView) {
            String label = ((TextView) page).getText().toString();
            result.pageControlMatches = label.matches("[0-9]{1,5} / [0-9]{1,5}")
                    && label.startsWith(selected.page + " / ");
        }
        Object rawScroll = field(activity, "currentScroll");
        result.scrollPresent = rawScroll instanceof ScrollView;
        if (!result.scrollPresent) return result;
        result.scroll = (ScrollView) rawScroll; result.scrollY = result.scroll.getScrollY();
        result.freshScroll = previous == null || result.scroll != previous;
        if (!"thread".equals(result.screenKind) || !result.idMatches || !result.pidMatches || !result.freshScroll) return result;
        if (result.scroll.getHeight() <= 0 || result.scroll.getChildCount() != 1) return result;
        View content = result.scroll.getChildAt(0);
        countAvatars(content, result);
        View row = findFloorRow(content, selected.floor == 0 ? "楼主" : "#" + selected.floor);
        result.floorRowPresent = row != null;
        if (row == null || row.getHeight() <= 0) return result;
        int viewport = result.scroll.getHeight() - result.scroll.getPaddingTop() - result.scroll.getPaddingBottom();
        int expected = Math.min(row.getTop(), Math.max(0, content.getHeight() - viewport));
        result.expectedScrollY = expected;
        int tolerance = Math.max(4, Math.round(12 * activity.getResources().getDisplayMetrics().density));
        result.located = expected > tolerance && Math.abs(result.scrollY - expected) <= tolerance
                && row.getTop() + row.getHeight() > result.scrollY && row.getTop() < result.scrollY + viewport;
        return result;
    }
    /** Only these literals may leave the probe; unknown production kinds never become output. */
    private static String safeKind(Object raw) {
        if (raw == null) return "NONE";
        for (String allowed : new String[]{"home", "forum", "thread", "settings", "profile", "saved", "history",
                "image", "search", "drafts", "auth", "editor", "inbox", "conversation", "reminders", "self", "user", "serverfav"})
            if (allowed.equals(raw)) return allowed;
        return "OTHER";
    }
    private static String sampleKey(boolean repeat, String name) {
        return repeat ? "repeat" + Character.toUpperCase(name.charAt(0)) + name.substring(1) : name;
    }
    /** Persist every completed sample before another wait, so deadline/errors keep the latest evidence. */
    private void publishSample(Sample value, boolean repeat) {
        JSONObject progress = new JSONObject(); long now = SystemClock.elapsedRealtime();
        synchronized (report) {
            if (finished.get()) return;
            try {
                progress.put("stage", repeat ? "REPEAT" : "FIRST");
                progress.put("actualPage", value.actualPage); progress.put("pageMatches", value.pageMatches);
                progress.put("pageControlMatches", value.pageControlMatches); progress.put("previousEnabled", value.previousEnabled);
                progress.put("selectedFloorLocated", value.located); progress.put("screenKind", value.screenKind);
                progress.put("idMatches", value.idMatches); progress.put("pidMatches", value.pidMatches);
                progress.put("scrollPresent", value.scrollPresent); progress.put("scrollY", value.scrollY);
                progress.put("floorRowPresent", value.floorRowPresent); progress.put("expectedScrollY", value.expectedScrollY);
                progress.put("boundAvatarUrlCount", value.boundAvatars); progress.put("decodedAvatarBitmapCount", value.decodedAvatars);
                String[] names = {"actualPage", "pageMatches", "pageControlMatches", "previousEnabled", "selectedFloorLocated",
                        "screenKind", "idMatches", "pidMatches", "scrollPresent", "scrollY", "floorRowPresent", "expectedScrollY",
                        "boundAvatarUrlCount", "decodedAvatarBitmapCount"};
                for (String name : names) report.put(sampleKey(repeat, name), progress.get(name));
                report.put("sampleStage", repeat ? "REPEAT" : "FIRST");
                String counter = sampleKey(repeat, "samples"); report.put(counter, report.optInt(counter, 0) + 1);
                if (repeat) report.put("freshScrollContainer", value.scrollPresent && value.freshScroll);
            } catch (Exception ignored) { return; }
        }
        // Optional live status is bounded to once per second. It has the same fixed projection.
        if (!finished.get() && (lastProgressAt == 0 || now - lastProgressAt >= 1000)) {
            lastProgressAt = now; Bundle status = new Bundle(); status.putString("safe_reader_progress", progress.toString());
            sendStatus(0, status);
        }
    }
    private static void countAvatars(View view, Sample sample) throws Exception {
        if (view instanceof AvatarView) {
            Object url = field(view, "url"), bitmap = field(view, "bitmap");
            if (url instanceof String && !((String) url).isEmpty()) sample.boundAvatars++;
            if (bitmap instanceof Bitmap && !((Bitmap) bitmap).isRecycled()) sample.decodedAvatars++;
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            countAvatars(((ViewGroup) view).getChildAt(i), sample);
    }
    private static View findDescription(View view, String expected) {
        if (expected.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View match = findDescription(((ViewGroup) view).getChildAt(i), expected); if (match != null) return match;
        }
        return null;
    }
    private static View findFloorRow(View content, String expected) {
        if (!(content instanceof ViewGroup)) return null;
        ViewGroup rows = (ViewGroup) content;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            if (!(row instanceof ViewGroup)) continue;
            ViewGroup meta = (ViewGroup) row;
            // A wrapped card is the production scroll anchor. Inspect only its first
            // metadata row, never a matching floor-looking string in the post body.
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
    private <T> T onMain(Callable<T> task) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); final Object[] result = new Object[1]; final boolean[] failed = {false};
        main.post(() -> { try { if (!finished.get()) result[0] = task.call(); else failed[0] = true; }
            catch (Exception unavailable) { failed[0] = true; } finally { latch.countDown(); } });
        if (!latch.await(remaining(), TimeUnit.MILLISECONDS)) throw new ProbeFailure("MAIN_THREAD_TIMEOUT");
        if (failed[0]) throw new ProbeFailure("UI_SAMPLE_UNAVAILABLE");
        @SuppressWarnings("unchecked") T typed = (T) result[0]; return typed;
    }
    private long remaining() throws ProbeFailure {
        long value = deadline - SystemClock.elapsedRealtime();
        if (value <= 0 || finished.get()) throw new ProbeFailure("DEADLINE_EXCEEDED"); return value;
    }
    private static Object field(Object owner, String name) throws Exception {
        Field value = owner.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(owner);
    }
    private void put(String key, Object value) { synchronized (report) { if (!finished.get()) try { report.put(key, value); } catch (Exception ignored) { } } }
    private void complete(String error) {
        if (!finished.compareAndSet(false, true)) return;
        synchronized (report) {
            try { report.put("errorType", error); } catch (Exception ignored) { }
            Bundle result = new Bundle(); result.putString("safe_reader_report", report.toString()); finish(Activity.RESULT_OK, result);
        }
    }
    private static JSONObject defaults() {
        JSONObject result = new JSONObject();
        try {
            result.put("schema", 2); result.put("limitMillis", LIMIT_MILLIS); result.put("errorType", "NOT_COMPLETED");
            for (String name : new String[]{"onOpenSignInGuardPassed", "eligibleTopicFound", "positiveReplySelected", "activityStarted",
                    "pageMatches", "pageControlMatches", "previousEnabled", "selectedFloorLocated", "oldScrollResetToZero",
                    "repeatAttempted", "repeatPageMatches", "repeatPageControlMatches", "repeatPreviousEnabled",
                    "repeatSelectedFloorLocated", "freshScrollContainer", "oldOffsetNotReused", "passed"}) result.put(name, false);
            for (String name : new String[]{"expectedPage", "actualPage", "repeatActualPage", "loadedAvatarUrlCount",
                    "boundAvatarUrlCount", "decodedAvatarBitmapCount", "explicitLikesCount", "scoreOnlyCount"}) result.put(name, 0);
            result.put("sampleStage", "NOT_SAMPLED");
            for (boolean repeat : new boolean[]{false, true}) {
                result.put(sampleKey(repeat, "screenKind"), "NONE");
                for (String name : new String[]{"idMatches", "pidMatches", "scrollPresent", "floorRowPresent"})
                    result.put(sampleKey(repeat, name), false);
                for (String name : new String[]{"scrollY", "expectedScrollY", "samples", "boundAvatarUrlCount", "decodedAvatarBitmapCount"})
                    result.put(sampleKey(repeat, name), 0);
            }
        } catch (Exception ignored) { throw new IllegalStateException("Safe reader schema unavailable"); }
        return result;
    }
    private static final class Selection {
        final String tid, pid; final int floor, page; int avatarUrls, explicitLikes, scoreOnly;
        Selection(String tid, String pid, int floor, int page) { this.tid = tid; this.pid = pid; this.floor = floor; this.page = page; }
    }
    private static final class Sample {
        ScrollView scroll; String screenKind = "NONE"; int actualPage, scrollY, expectedScrollY, boundAvatars, decodedAvatars;
        boolean pageMatches, pageControlMatches, previousEnabled, located, idMatches, pidMatches, scrollPresent, freshScroll, floorRowPresent;
        boolean accepted() { return "thread".equals(screenKind) && idMatches && pidMatches && freshScroll
                && pageMatches && pageControlMatches && previousEnabled && located; }
    }
    private static final class ProbeFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final String code; ProbeFailure(String code) { this.code = code; }
    }
}
