package cn.ngapure.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact MainActivity reader callback, real dialogs and parser; synthetic own-UID data only. */
@SuppressWarnings("deprecation")
public final class ReactionReaderProbe extends Instrumentation {
    private static final String PACKAGE = "cn.ngapure.reactionreaderprobe";
    private static final String TID = "190000002";
    private static final String COOKIE = "ngaPassportUid=190000001; ngaPassportCid=synthetic-reader-fixture-only";
    private final JSONArray cases = new JSONArray();
    private final ConcurrentLinkedQueue<ReadReply> reads = new ConcurrentLinkedQueue<>();
    private final List<ReadReply> allReads = new ArrayList<>();
    private final AtomicInteger blocked = new AtomicInteger(), served = new AtomicInteger();
    private MainActivity activity;
    private NgaStore store;
    private boolean authorized;
    private int assertions, issues;
    private String stage = "guard";
    private static final class Session implements NativeThreadActions.SessionSource {
        @Override public String host() { return "bbs.nga.cn"; }
        @Override public String cookie() { return COOKIE; }
        @Override public boolean loggedIn() { return true; }
    }
    private static final class Journal implements NativeThreadActions.Journal {
        private final Set<String> pending = new HashSet<>();
        @Override public boolean contains(String key) { return pending.contains(key); }
        @Override public boolean put(String key) { return pending.add(key); }
        @Override public boolean remove(String key) { return pending.remove(key); }
    }
    private static final class Worker implements Executor {
        final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
        @Override public void execute(Runnable value) { pending.add(value); }
        void complete() { Runnable task = pending.poll(); if (task == null) throw new AssertionError("ONE_CONFIRMATION_TASK_REQUIRED"); task.run(); }
    }
    private static final class Transport implements NativeThreadActions.Transport {
        final String pid;
        int writes;
        Transport(String pid) { this.pid = pid; }
        @Override public NativeThreadActions.Response execute(NativeThreadActions.Request request) throws IOException {
            String body = URLDecoder.decode(new String(request.body, StandardCharsets.UTF_8), "UTF-8");
            if (!"POST".equals(request.method) || !"/nuke.php".equals(request.url.getPath())
                    || !parameter(body, "tid", TID) || !parameter(body, "pid", pid)
                    || !parameter(body, "value", "1")) throw new IOException("SYNTHETIC_REACTION_ROUTE_REQUIRED");
            writes++;
            return new NativeThreadActions.Response(200, "{\"data\":[\"合成已点赞\",1,4]}".getBytes(StandardCharsets.UTF_8), "application/json; charset=UTF-8");
        }
    }
    private static final class ReadReply {
        final byte[] bytes;
        final CountDownLatch entered = new CountDownLatch(1), release, disconnected = new CountDownLatch(1);
        ReadReply(int floor, String pid, String value, boolean blocked) throws Exception {
            release = new CountDownLatch(blocked ? 1 : 0);
            String html = "<div id='postliststart'></div><script>var __CURRENT_TID=" + TID
                    + ";postArg.proc(" + floor + ",0,0,0,0,0,0,0,0,0," + pid + ",0,0,0,0,'" + value + "');</script>";
            bytes = new JSONObject().put("template", "read").put("html", html)
                    .put("htmlBaseUrl", "https://bbs.nga.cn/read.php?tid=" + TID + "&page=1")
                    .toString().getBytes(StandardCharsets.UTF_8);
        }
    }
    private final class Connection extends HttpURLConnection {
        private final ReadReply reply;
        Connection(URL url, ReadReply reply) { super(url); this.reply = reply; }
        @Override public void connect() { connected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { reply.disconnected.countDown(); }
        @Override public int getResponseCode() throws IOException {
            reply.entered.countDown();
            try { if (!reply.release.await(10, TimeUnit.SECONDS)) throw new IOException("SYNTHETIC_READ_RELEASE_TIMEOUT"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("SYNTHETIC_READ_INTERRUPTED", interrupted); }
            return 200;
        }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(reply.bytes); }
        @Override public String getContentType() { return "application/json; charset=UTF-8"; }
        @Override public String getContentEncoding() { return null; }
        @Override public int getContentLength() { return reply.bytes.length; }
        @Override public String getHeaderField(String name) { return "Content-Type".equalsIgnoreCase(name) ? getContentType() : null; }
    }
    private static boolean parameter(String query, String key, String value) {
        if (query == null) return false;
        for (String part : query.split("&")) if (part.equals(key + "=" + value)) return true;
        return false;
    }
    @Override public void onCreate(Bundle args) {
        super.onCreate(args); authorized = args != null && "true".equals(args.getString("syntheticReaderReactionsOnly")); start();
    }
    private static Object field(Object target, String name) throws Exception {
        Field value = target.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field member = target.getClass().getDeclaredField(name); member.setAccessible(true); member.set(target, value);
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            if (failure.getCause() instanceof Error) throw (Error) failure.getCause();
            throw failure;
        }
    }
    private <T> T main(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work); runOnMainSync(task); return task.get(10, TimeUnit.SECONDS);
    }
    private void check(JSONObject row, String label, boolean pass) throws Exception {
        assertions++; row.put(label, pass); if (!pass) issues++;
    }
    private void await(Callable<Boolean> condition) throws Exception {
        long end = SystemClock.elapsedRealtime() + 10000;
        while (!condition.call()) {
            if (SystemClock.elapsedRealtime() >= end) throw new AssertionError("CONDITION_TIMEOUT_" + stage);
            SystemClock.sleep(15);
        }
        waitForIdleSync();
    }
    private ReadReply enqueue(int floor, String pid, String value, boolean gate) throws Exception {
        ReadReply reply = new ReadReply(floor, pid, value, gate); allReads.add(reply); reads.add(reply); return reply;
    }
    private static boolean contains(View view, String target) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(target)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (contains(((ViewGroup) view).getChildAt(i), target)) return true;
        return false;
    }
    private NativeGlassDialog active(NativeThreadActionDialog owner) throws Exception { return (NativeGlassDialog) field(owner, "active"); }
    private void startRead(Object screen, NgaApi.ThreadPage page, int generation) throws Exception {
        invoke(activity, "loadPostVotes", new Class<?>[]{screen.getClass(), NgaApi.ThreadPage.class, java.util.Map.class, int.class},
                screen, page, field(activity, "readerVoteViews"), generation);
    }
    private void drainReads() throws Exception {
        ScreenReadTasks pageReads = (ScreenReadTasks) field(activity, "pageReads");
        List<?> tasks;
        synchronized (pageReads) { tasks = new ArrayList<>((List<?>) field(pageReads, "tasks")); }
        for (Object task : tasks) ((Future<?>) task).get(10, TimeUnit.SECONDS);
        waitForIdleSync();
    }
    private void scenario(int floor, String pid) throws Exception {
        stage = floor == 0 ? "original-post" : "reply";
        JSONObject row = new JSONObject().put("case", stage); cases.put(row);
        Worker worker = new Worker(); Transport transport = new Transport(pid);
        NativeThreadActions actions = new NativeThreadActions(new Session(), transport, new Journal());
        NativeThreadActionDialog sheets = new NativeThreadActionDialog(activity, actions, worker, false);
        NgaApi.Post post = new NgaApi.Post(); post.floor = floor; post.pid = pid; post.author = "合成用户";
        post.content = "合成阅读位置验证。\n"; for (int i = 0; i < 7; i++) post.content += post.content;
        post.votes = VoteMetrics.from(new JSONObject().put("recommend", "0,7,2"));
        NgaApi.ThreadPage page = new NgaApi.ThreadPage(); page.tid = TID; page.posts.add(post);
        Class<?> type = Class.forName("cn.ngapure.app.MainActivity$Screen");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, String.class, String.class, int.class); constructor.setAccessible(true);
        Object screen = constructor.newInstance("thread", TID, "合成阅读页面", 1);
        LinearLayout column = main(() -> { LinearLayout value = new LinearLayout(activity); value.setOrientation(LinearLayout.VERTICAL); return value; });
        ScrollView scroll = main(() -> { ScrollView value = new ScrollView(activity); value.addView(column); return value; });
        LinearLayout root = main(() -> { LinearLayout value = new LinearLayout(activity); value.setOrientation(LinearLayout.VERTICAL); value.addView(scroll, new LinearLayout.LayoutParams(-1, -1)); return value; });
        NativeVoteBar bar = main(() -> {
            ((ScreenReadTasks) field(activity, "pageReads")).cancelAll();
            NativeThreadActionDialog previous = (NativeThreadActionDialog) field(activity, "threadSheets"); if (previous != null) previous.dismiss();
            set(activity, "current", screen); set(activity, "generation", ((Integer) field(activity, "generation")) + 1);
            set(activity, "root", root); set(activity, "body", new FrameLayout(activity)); set(activity, "currentScroll", scroll);
            set(activity, "readerThreadPage", page); ((java.util.Map<?, ?>) field(activity, "readerVoteViews")).clear();
            set(activity, "threadSheets", sheets); set(activity, "threadActions", actions); set(activity, "threadLoaded", true);
            activity.setContentView(root);
            return (NativeVoteBar) invoke(activity, "postRow", new Class<?>[]{LinearLayout.class, NgaApi.Post.class}, column, post);
        });
        await(() -> main(() -> scroll.getHeight() > 0 && column.getHeight() > scroll.getHeight() + 160));
        main(() -> { scroll.scrollTo(0, 160); return null; }); waitForIdleSync();
        int beforeY = main(scroll::getScrollY), generation = main(() -> (Integer) field(activity, "generation"));
        View card = main(() -> column.getChildAt(0));
        check(row, "realReaderRowHasInitialIndependentCounts", main(() -> "点赞 7".contentEquals(((TextView) bar.findViewWithTag("vote-like")).getText()) && "点踩 2".contentEquals(((TextView) bar.findViewWithTag("vote-dislike")).getText())));
        check(row, "nonzeroScrollPositionEstablished", beforeY == 160);
        ReadReply old = enqueue(floor, pid, "9", true);
        main(() -> { startRead(screen, page, generation); return null; });
        check(row, "oldReadEnteredBeforeConfirmation", old.entered.await(10, TimeUnit.SECONDS));
        main(() -> { bar.findViewWithTag("vote-like").performClick(); return null; }); waitForIdleSync();
        check(row, "actualNativeConfirmationShown", main(() -> active(sheets).isShowing() && contains(active(sheets).getWindow().getDecorView(), "点赞这条内容")));
        check(row, "openingSheetDoesNotWrite", transport.writes == 0 && worker.pending.isEmpty());
        ReadReply fresh = enqueue(floor, pid, "0,12,3", false);
        main(() -> { active(sheets).getButton(NativeGlassDialog.BUTTON_POSITIVE).performClick(); return null; });
        check(row, "oneControlledConfirmationQueued", worker.pending.size() == 1 && transport.writes == 0);
        worker.complete(); waitForIdleSync();
        check(row, "oneSyntheticWriteForExactPost", transport.writes == 1);
        check(row, "supplementaryReadStartedFromRealCallback", fresh.entered.await(10, TimeUnit.SECONDS));
        await(() -> main(() -> post.votes.likesKnown && post.votes.dislikesKnown && post.votes.likes == 12 && post.votes.dislikes == 3));
        NativeGlassDialog success = main(() -> active(sheets));
        check(row, "successDialogSurvivesCallback", main(() -> success.isShowing() && contains(success.getWindow().getDecorView(), "操作已确认")));
        check(row, "serverConfirmedLikeSelectedAfterCounts", main(() -> bar.findViewWithTag("vote-like").isSelected() && !bar.findViewWithTag("vote-dislike").isSelected()));
        check(row, "exactSupplementaryLabels", main(() -> "点赞 12".contentEquals(((TextView) bar.findViewWithTag("vote-like")).getText()) && "点踩 3".contentEquals(((TextView) bar.findViewWithTag("vote-dislike")).getText())));
        old.release.countDown(); drainReads();
        check(row, "oldRevisionNetOnlyReadCannotDowngradeCounts", main(() -> post.votes.likesKnown && post.votes.dislikesKnown && post.votes.likes == 12 && post.votes.dislikes == 3));
        ReadReply legacy = enqueue(floor, pid, "10", false);
        main(() -> { startRead(screen, page, generation); return null; });
        check(row, "sameRevisionLegacyReadActuallyRan", legacy.entered.await(10, TimeUnit.SECONDS)); drainReads();
        check(row, "sameRevisionNetOnlyReadCannotDowngradeCounts", main(() -> post.votes.likesKnown && post.votes.dislikesKnown && post.votes.likes == 12 && post.votes.dislikes == 3));
        check(row, "screenGenerationAndRowIdentityUnchanged", main(() -> field(activity, "current") == screen && (Integer) field(activity, "generation") == generation && field(activity, "readerThreadPage") == page && field(activity, "currentScroll") == scroll && column.getChildAt(0) == card && ((java.util.Map<?, ?>) field(activity, "readerVoteViews")).get(floor + ":" + pid) == bar));
        check(row, "scrollAndSelectedStateRemainUnchanged", main(() -> scroll.getScrollY() == beforeY && bar.findViewWithTag("vote-like").isSelected()));
        check(row, "sameSuccessDialogStillVisible", main(() -> active(sheets) == success && success.isShowing()));
        check(row, "noExtraWriteFromCountReads", transport.writes == 1 && worker.pending.isEmpty());
        row.put("syntheticWriteCount", transport.writes).put("scrollYBefore", beforeY).put("scrollYAfter", main(scroll::getScrollY));
        main(() -> { sheets.dismiss(); return null; });
    }
    @Override public void onStart() {
        boolean passed = false; String failure = "NONE";
        try {
            Context context = getTargetContext();
            if (!authorized || !PACKAGE.equals(context.getPackageName()) || context.checkSelfPermission(android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED)
                throw new IllegalStateException("OWN_UID_NO_INTERNET_SYNTHETIC_READER_REQUIRED");
            URL.setURLStreamHandlerFactory(protocol -> ("http".equals(protocol) || "https".equals(protocol)) ? new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) throws IOException {
                    if ("https".equals(url.getProtocol()) && "bbs.nga.cn".equals(url.getHost()) && "/read.php".equals(url.getPath())
                            && parameter(url.getQuery(), "tid", TID) && parameter(url.getQuery(), "page", "1") && parameter(url.getQuery(), "__output", "17")) {
                        ReadReply reply = reads.poll(); if (reply == null) throw new IOException("NO_SYNTHETIC_COUNT_REPLY_QUEUED");
                        served.incrementAndGet(); return new Connection(url, reply);
                    }
                    blocked.incrementAndGet(); throw new IOException("SYNTHETIC_READER_NETWORK_BLOCKED");
                }
            } : null);
            store = new NgaStore(context); store.setAutoCheckIn(false); store.setMotionEnabled(false); store.setTopicPreviewsEnabled(false); store.setCookie(COOKIE);
            activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); waitForIdleSync();
            SystemClock.sleep(150); waitForIdleSync();
            scenario(0, "0"); scenario(7, "190000007");
            passed = issues == 0 && cases.length() == 2 && served.get() == 6;
        } catch (Throwable error) {
            Throwable cause = error; for (int i = 0; i < 8 && cause.getCause() != null; i++) cause = cause.getCause();
            failure = stage + ":" + cause.getClass().getSimpleName() + ":" + String.valueOf(cause.getMessage());
        } finally {
            for (ReadReply reply : allReads) reply.release.countDown();
            if (activity != null) try { main(() -> { activity.finish(); return null; }); } catch (Exception ignored) { }
            if (store != null) try { store.clearLogin(); } catch (Exception ignored) { }
            JSONObject report = new JSONObject();
            try {
                report.put("passed", passed).put("failure", failure).put("assertions", assertions).put("observedFailures", issues).put("cases", cases)
                        .put("ownUid", true).put("internetPermission", false).put("realNetworkRequests", 0).put("realCommunityWrites", 0)
                        .put("realAccountAccessed", false).put("productionClassesRewritten", false).put("realMainActivityCallback", true)
                        .put("syntheticPostVoteReads", served.get()).put("otherNetworkAttemptsBlocked", blocked.get());
                try (InputStream input = getTargetContext().getAssets().open("binding.json")) {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
                    while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
                    report.put("binding", new JSONObject(bytes.toString("UTF-8")));
                }
                try (FileOutputStream output = new FileOutputStream(new File(getTargetContext().getFilesDir(), "reaction-reader-report.json"))) { output.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
            } catch (Exception ignored) { }
            Bundle result = new Bundle(); result.putString("safe_reaction_reader_report", report.toString()); finish(passed ? 0 : 1, result);
        }
    }
}
