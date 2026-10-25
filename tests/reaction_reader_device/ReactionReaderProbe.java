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

/** Exact MainActivity direct-tap reader callbacks and parser; synthetic own-UID data only. */
@SuppressWarnings("deprecation")
public final class ReactionReaderProbe extends Instrumentation {
    private static final String PACKAGE = "cn.ngapure.reactionreaderprobe";
    private static final String TID = "190000002";
    private static final String COOKIE = "ngaPassportUid=190000001; ngaPassportCid=synthetic-reader-fixture-only";
    private final JSONArray cases = new JSONArray();
    private final ConcurrentLinkedQueue<ReadReply> reads = new ConcurrentLinkedQueue<>();
    private final List<ReadReply> allReads = new ArrayList<>();
    private final AtomicInteger blocked = new AtomicInteger(), served = new AtomicInteger(), countReadAttempts = new AtomicInteger();
    private MainActivity activity;
    private NgaStore store;
    private boolean authorized, demoMode;
    private int demoHoldMs = 700;
    private TextView demoStage;
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
        void complete() { Runnable task = pending.poll(); if (task == null) throw new AssertionError("ONE_DIRECT_ACTION_TASK_REQUIRED"); task.run(); }
    }
    private final class CallbackGate implements Executor {
        final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
        volatile boolean hold;
        @Override public void execute(Runnable task) { if (hold) pending.add(task); else activity.runOnUiThread(task); }
        void release() {
            hold = false; Runnable task;
            while ((task = pending.poll()) != null) activity.runOnUiThread(task);
        }
    }
    private static final class ActionReply {
        final int direction; final String json;
        ActionReply(int direction, String json) { this.direction = direction; this.json = json; }
    }
    private static final class Transport implements NativeThreadActions.Transport {
        final String pid;
        final ConcurrentLinkedQueue<ActionReply> replies = new ConcurrentLinkedQueue<>();
        final List<Integer> directions = new ArrayList<>();
        int writes;
        Transport(String pid) { this.pid = pid; }
        void reply(int direction, String json) { replies.add(new ActionReply(direction, json)); }
        @Override public NativeThreadActions.Response execute(NativeThreadActions.Request request) throws IOException {
            String body = URLDecoder.decode(new String(request.body, StandardCharsets.UTF_8), "UTF-8");
            ActionReply reply = replies.poll();
            if (reply == null || !"POST".equals(request.method) || !"/nuke.php".equals(request.url.getPath())
                    || !parameter(body, "tid", TID) || !parameter(body, "pid", pid)
                    || !parameter(body, "__output", "8") || !parameter(body, "value", String.valueOf(reply.direction)))
                throw new IOException("SYNTHETIC_DIRECT_REACTION_ROUTE_REQUIRED");
            writes++; directions.add(reply.direction);
            if (reply.json == null) throw new IOException("SYNTHETIC_UNKNOWN_OUTCOME_NO_SOCKET");
            return new NativeThreadActions.Response(200, reply.json.getBytes(java.nio.charset.Charset.forName("GB18030")), "application/json; charset=GBK");
        }
    }
    private static final class Fixture {
        int floor, generation, scrollY; String pid; Object screen;
        final Worker worker = new Worker();
        Transport transport; NativeReactionController controller; CallbackGate callbacks;
        NgaApi.Post post; NgaApi.ThreadPage page; NativeVoteBar bar;
        LinearLayout column; ScrollView scroll; View card;
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
        super.onCreate(args); authorized = args != null && "true".equals(args.getString("syntheticReaderReactionsOnly"));
        demoMode = args != null && "true".equals(args.getString("demoMode"));
        if (args != null) try { demoHoldMs = Math.max(300, Math.min(2000, Integer.parseInt(args.getString("demoHoldMs", "700")))); } catch (NumberFormatException ignored) { }
        start();
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
    private void demo(String value) throws Exception {
        main(() -> { if (demoStage != null) demoStage.setText(value); return null; });
        waitForIdleSync(); if (demoMode) SystemClock.sleep(demoHoldMs);
    }
    private boolean noReactionDialog() throws Exception { return field(activity, "threadSheets") == null; }
    private static View target(Fixture fixture, int direction) { return fixture.bar.findViewWithTag(direction > 0 ? "vote-like" : "vote-dislike"); }
    private static TextView status(Fixture fixture) { return fixture.bar.findViewWithTag("vote-status"); }
    private boolean selected(Fixture fixture, int direction) {
        return target(fixture, 1).isSelected() == (direction == 1) && target(fixture, -1).isSelected() == (direction == -1);
    }
    private void unchanged(JSONObject row, String prefix, Fixture fixture) throws Exception {
        check(row, prefix + "NoReactionDialogAllocated", main(this::noReactionDialog));
        check(row, prefix + "ScreenAndViewsPreserved", main(() -> field(activity, "current") == fixture.screen
                && (Integer) field(activity, "generation") == fixture.generation && field(activity, "readerThreadPage") == fixture.page
                && field(activity, "currentScroll") == fixture.scroll && fixture.card.getParent() == fixture.column
                && ((java.util.Map<?, ?>) field(activity, "readerVoteViews")).get(fixture.floor + ":" + fixture.pid) == fixture.bar));
        check(row, prefix + "ScrollPreserved", main(() -> fixture.scroll.getScrollY() == fixture.scrollY));
    }
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
    private Fixture fixture(int floor, String pid) throws Exception {
        Fixture f = new Fixture(); f.floor = floor; f.pid = pid; f.transport = new Transport(pid);
        NativeThreadActions actions = new NativeThreadActions(new Session(), f.transport, new Journal());
        f.callbacks = new CallbackGate(); f.controller = new NativeReactionController(f.worker, f.callbacks);
        f.post = new NgaApi.Post(); f.post.floor = floor; f.post.pid = pid; f.post.author = "合成验证用户";
        f.post.time = "离线数据"; f.post.content = "本页只验证直接点赞与撤销。\n点击按钮不会操作任何真实账号。\n合成帖子，非真实社区内容。";
        f.post.votes = VoteMetrics.from(new JSONObject().put("recommend", "0,7,2"));
        f.page = new NgaApi.ThreadPage(); f.page.tid = TID; f.page.posts.add(f.post);
        Class<?> type = Class.forName("cn.ngapure.app.MainActivity$Screen");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, String.class, String.class, int.class); constructor.setAccessible(true);
        f.screen = constructor.newInstance("thread", TID, "合成阅读页面", 1);
        main(() -> {
            ((ScreenReadTasks) field(activity, "pageReads")).cancelAll();
            invoke(activity, "closeReactionSubscription", new Class<?>[]{});
            NativeThreadActionDialog previous = (NativeThreadActionDialog) field(activity, "threadSheets"); if (previous != null) previous.dismiss();
            set(activity, "threadSheets", null); set(activity, "reactionController", f.controller);
            set(activity, "current", f.screen); set(activity, "generation", ((Integer) field(activity, "generation")) + 1);
            LinearLayout root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(0xfff4f5f7);
            root.setPadding(0,Math.round(56 * activity.getResources().getDisplayMetrics().density),0,0);
            int inset = Math.round(14 * activity.getResources().getDisplayMetrics().density);
            TextView banner = new TextView(activity); banner.setText("离线合成验证 · 不操作真实账号"); banner.setTextColor(0xff9a5118); banner.setTextSize(15); banner.setPadding(inset,inset,inset,inset); root.addView(banner);
            demoStage = new TextView(activity); demoStage.setTextSize(16); demoStage.setTextColor(0xff20242b); demoStage.setPadding(inset,0,inset,inset); root.addView(demoStage);
            f.column = new LinearLayout(activity); f.column.setOrientation(LinearLayout.VERTICAL);
            View leading = new View(activity); f.column.addView(leading, new LinearLayout.LayoutParams(-1,160));
            f.scroll = new ScrollView(activity); f.scroll.addView(f.column); root.addView(f.scroll, new LinearLayout.LayoutParams(-1,0,1));
            set(activity, "root", root); set(activity, "body", new FrameLayout(activity)); set(activity, "currentScroll", f.scroll);
            set(activity, "readerThreadPage", f.page); ((java.util.Map<?, ?>) field(activity, "readerVoteViews")).clear();
            ((java.util.Map<?, ?>) field(activity, "readerVoteDataVersions")).clear();
            ((java.util.Map<?, ?>) field(activity, "readerVoteRefreshVersions")).clear();
            set(activity, "threadActions", actions); set(activity, "threadLoaded", true); activity.setContentView(root);
            f.bar = (NativeVoteBar) invoke(activity, "postRow", new Class<?>[]{LinearLayout.class, NgaApi.Post.class}, f.column, f.post);
            f.card = f.column.getChildAt(1);
            View trailing = new View(activity); f.column.addView(trailing,new LinearLayout.LayoutParams(-1,activity.getResources().getDisplayMetrics().heightPixels));
            invoke(activity, "listenForReaderReactions", new Class<?>[]{});
            return null;
        });
        await(() -> main(() -> f.scroll.getHeight() > 0 && f.column.getHeight() > f.scroll.getHeight() + 160));
        main(() -> { f.scroll.scrollTo(0,160); return null; }); waitForIdleSync();
        f.scrollY = main(f.scroll::getScrollY); f.generation = main(() -> (Integer) field(activity,"generation"));
        return f;
    }
    private void acknowledged(JSONObject row, String prefix, String label, Fixture f, int direction, String response,
                              int expectedSelection, int likes, int dislikes, boolean checkBusyTaps) throws Exception {
        demo(label); int writes = f.transport.writes;
        f.transport.reply(direction,response); ReadReply fresh = enqueue(f.floor,f.pid,"0,"+likes+","+dislikes,false);
        main(() -> { target(f,direction).performClick(); return null; }); waitForIdleSync();
        check(row,prefix+"SingleTapQueuesOneWriteWithoutDialog", f.worker.pending.size()==1 && f.transport.writes==writes && main(this::noReactionDialog));
        check(row,prefix+"BothDirectionsBusyInline", main(() -> !target(f,1).isEnabled() && !target(f,-1).isEnabled() && status(f).getVisibility()==View.VISIBLE));
        if(checkBusyTaps){
            main(() -> {target(f,direction).performClick();target(f,-direction).performClick();target(f,direction).performClick();return null;});
            check(row,prefix+"DoubleAndOppositeTapsDoNotQueueMore", f.worker.pending.size()==1 && f.transport.writes==writes);
        }
        f.worker.complete(); waitForIdleSync();
        check(row,prefix+"ExactlyOneWireToggle",f.transport.writes==writes+1);
        check(row,prefix+"SupplementaryReadFromMainCallback",fresh.entered.await(10,TimeUnit.SECONDS));
        await(() -> main(() -> f.post.votes.likesKnown && f.post.votes.dislikesKnown && f.post.votes.likes==likes && f.post.votes.dislikes==dislikes));
        check(row,prefix+"ServerSelectionAndExactLabels",main(() -> selected(f,expectedSelection)
                && ("点赞 "+likes).contentEquals(((TextView)target(f,1)).getText()) && ("点踩 "+dislikes).contentEquals(((TextView)target(f,-1)).getText())));
        check(row,prefix+"ReadyForNextIndependentTap",main(() -> target(f,1).isEnabled() && target(f,-1).isEnabled()));
        unchanged(row,prefix,f); demo(label+" · 已返回状态");
    }
    private void scenario(int floor, String pid) throws Exception {
        stage = floor == 0 ? "original-post" : "reply";
        String name = floor == 0 ? "合成首帖" : "合成回复";
        JSONObject row = new JSONObject().put("case",stage); cases.put(row); Fixture f=fixture(floor,pid);
        demo(name+" · 直接点按，不弹确认框");
        check(row,"NonzeroScrollPositionEstablished",f.scrollY==160);
        check(row,"SyntheticMarkerVisible",main(() -> contains((View)field(activity,"root"),"离线合成验证")));
        ReadReply old=enqueue(floor,pid,"9",true);
        main(() -> {startRead(f.screen,f.page,f.generation);return null;});
        check(row,"OldReadEnteredBeforeDirectTap",old.entered.await(10,TimeUnit.SECONDS));
        acknowledged(row,"Like",name+" · 点赞",f,1,"{\"data\":[\"合成已点赞\",1,4]}",1,8,2,true);
        old.release.countDown(); drainReads();
        check(row,"OldRevisionReadCannotClearSelectionOrTotals",main(() -> selected(f,1)&&f.post.votes.likesKnown&&f.post.votes.dislikesKnown&&f.post.votes.likes==8&&f.post.votes.dislikes==2));
        ReadReply legacy=enqueue(floor,pid,"10",false);
        main(() -> {startRead(f.screen,f.page,f.generation);return null;});
        check(row,"SameRevisionNetOnlyReadRan",legacy.entered.await(10,TimeUnit.SECONDS));drainReads();
        check(row,"SameRevisionNetOnlyKeepsIndependentTotals",main(() -> f.post.votes.likesKnown&&f.post.votes.dislikesKnown&&f.post.votes.likes==8&&f.post.votes.dislikes==2));
        acknowledged(row,"UndoLike",name+" · 再次点按，撤销点赞",f,1,"{\"data\":{\"0\":\"\",\"2\":1}}",0,7,2,false);
        acknowledged(row,"LikeAgain",name+" · 再次点赞",f,1,"{\"data\":[\"合成已点赞\",1,4]}",1,8,2,false);
        acknowledged(row,"Dislike",name+" · 改为点踩",f,-1,"{\"data\":[\"合成已点踩\",-2,8]}",-1,7,3,false);
        acknowledged(row,"UndoDislike",name+" · 再次点按，撤销点踩",f,-1,"{\"data\":{\"0\":\"\",\"2\":2}}",0,7,2,false);
        demo(name+" · 已知失败在本楼层提示");
        int beforeFailure=f.transport.writes;f.transport.reply(1,"{\"error\":\"合成拒绝：权限不足\"}");
        main(() -> {target(f,1).performClick();return null;});check(row,"FailureTapQueuedOnce",f.worker.pending.size()==1);f.worker.complete();waitForIdleSync();
        check(row,"KnownFailureInlineAndNotRetried",main(() -> status(f).getVisibility()==View.VISIBLE&&!status(f).getText().toString().isEmpty()&&target(f,1).isEnabled()&&selected(f,0))&&f.transport.writes==beforeFailure+1&&f.worker.pending.isEmpty());
        unchanged(row,"KnownFailure",f);demo(name+" · 失败不改变阅读位置");
        int beforeUnknown=f.transport.writes;f.transport.reply(1,null);
        main(() -> {target(f,1).performClick();return null;});f.worker.complete();waitForIdleSync();
        check(row,"UnknownOutcomeInlineProtection",main(() -> status(f).getVisibility()==View.VISIBLE&&status(f).getText().toString().contains("不会自动重试")
                &&!target(f,1).isEnabled()&&!target(f,-1).isEnabled()&&f.bar.findViewWithTag("vote-recovery").getVisibility()==View.VISIBLE));
        main(() -> {target(f,1).performClick();target(f,-1).performClick();return null;});waitForIdleSync();
        check(row,"UnknownOutcomeNeverAutomaticallyResubmits",f.transport.writes==beforeUnknown+1&&f.worker.pending.isEmpty());
        unchanged(row,"UnknownOutcome",f);demo(name+" · 未确认结果：先核对，再解除保护");
        int beforeResolve=f.transport.writes;
        main(() -> {f.bar.findViewWithTag("vote-recovery").performClick();return null;});
        check(row,"RecoveryOnlyQueuesLocalResolution",f.worker.pending.size()==1&&f.transport.writes==beforeResolve);
        f.worker.complete();waitForIdleSync();
        check(row,"RecoveryDoesNotSendNewToggle",f.transport.writes==beforeResolve&&f.worker.pending.isEmpty());
        check(row,"RecoveryReenablesSeparateNextTap",main(() -> target(f,1).isEnabled()&&target(f,-1).isEnabled()&&f.bar.findViewWithTag("vote-recovery").getVisibility()==View.GONE));
        unchanged(row,"Recovery",f);demo(name+" · 已解除保护，尚未重新提交");
        acknowledged(row,"AfterRecovery",name+" · 用户再次独立点按",f,1,"{\"data\":[\"合成已点赞\",1,4]}",1,8,2,false);
        check(row,"ExpectedWireDirectionsAndNoPendingWork",f.transport.directions.equals(java.util.Arrays.asList(1,1,1,-1,-1,1,1,1))&&f.transport.replies.isEmpty()&&f.worker.pending.isEmpty());
        row.put("syntheticWriteCount",f.transport.writes).put("wireDirections",new JSONArray(f.transport.directions)).put("scrollYBefore",f.scrollY).put("scrollYAfter",main(f.scroll::getScrollY));
    }
    private void lateAcknowledgementBinding() throws Exception {
        stage = "late-ack-before-row-bind"; JSONObject row = new JSONObject().put("case",stage); cases.put(row);
        Fixture f = fixture(9,"190000099"); demo("合成时序验证 · 回调前先重新绑定楼层");
        String scope = main(() -> (String)invoke(activity,"contentScope",new Class<?>[]{}));
        int before = countReadAttempts.get();
        f.callbacks.hold = true; f.transport.reply(1,"{\"data\":[\"合成已点赞\",1,4]}");
        main(() -> { target(f,1).performClick(); return null; });
        check(row,"DirectTapQueuesBeforeHeldCompletion",f.worker.pending.size()==1);
        f.worker.complete(); waitForIdleSync();
        NativeReactionController.Snapshot confirmed = f.controller.snapshot(scope,TID,f.pid);
        check(row,"ServerAcknowledgementPrecedesMainCallback",confirmed!=null && confirmed.acknowledgedVersion>0 && confirmed.selectionKnown && confirmed.selected==1 && !f.callbacks.pending.isEmpty());
        check(row,"OldPageStillHasPreRequestCountsBeforeBind",main(() -> f.post.votes.likesKnown&&f.post.votes.likes==7&&f.post.votes.dislikes==2)&&countReadAttempts.get()==before);
        ReadReply fresh = enqueue(f.floor,f.pid,"0,8,2",true);
        main(() -> {
            int index=f.column.indexOfChild(f.card); f.column.removeView(f.card);
            f.bar=(NativeVoteBar)invoke(activity,"postRow",new Class<?>[]{LinearLayout.class,NgaApi.Post.class},f.column,f.post);
            View rebound=f.column.getChildAt(f.column.getChildCount()-1);f.column.removeView(rebound);f.column.addView(rebound,index);f.card=rebound;
            return null;
        });
        check(row,"RebindImmediatelyDiscardsOldIndependentTotals",main(() -> !f.post.votes.likesKnown&&!f.post.votes.dislikesKnown
                &&"点赞 —".contentEquals(((TextView)target(f,1)).getText())&&"点踩 —".contentEquals(((TextView)target(f,-1)).getText())));
        check(row,"RebindUsesAlreadyConfirmedSelection",main(() -> selected(f,1)));
        main(() -> {invoke(activity,"listenForReaderReactions",new Class<?>[]{});invoke(activity,"listenForReaderReactions",new Class<?>[]{});return null;});
        check(row,"ReaderRebindSchedulesOneSupplementaryRead",fresh.entered.await(10,TimeUnit.SECONDS)&&countReadAttempts.get()==before+1);
        demo("合成时序验证 · 旧计数已隐藏，正在补读");
        fresh.release.countDown();drainReads();
        await(() -> main(() -> f.post.votes.likesKnown&&f.post.votes.dislikesKnown&&f.post.votes.likes==8&&f.post.votes.dislikes==2));
        check(row,"FreshTotalsRecordedAtAcknowledgementRevision",main(() -> {
            Object version=((java.util.Map<?, ?>)field(activity,"readerVoteDataVersions")).get(f.floor+":"+f.pid);
            return version instanceof Long && (Long)version>=confirmed.acknowledgedVersion;
        }));
        int afterFresh=countReadAttempts.get();
        f.callbacks.release();waitForIdleSync();
        main(() -> {invoke(activity,"listenForReaderReactions",new Class<?>[]{});return null;});drainReads();
        check(row,"DelayedCallbackAndResubscribeDoNotReadAgain",countReadAttempts.get()==afterFresh&&afterFresh==before+1);
        check(row,"DelayedCallbackCannotClearFreshCounts",main(() -> selected(f,1)&&f.post.votes.likesKnown&&f.post.votes.dislikesKnown&&f.post.votes.likes==8&&f.post.votes.dislikes==2));
        check(row,"OnlyOriginalTapWrote",f.transport.writes==1&&f.worker.pending.isEmpty()&&f.callbacks.pending.isEmpty());
        unchanged(row,"LateAcknowledgement",f);demo("合成时序验证 · 新计数保留，未重复请求");
        row.put("syntheticWriteCount",f.transport.writes).put("countReadAttempts",countReadAttempts.get()-before);
    }
    private void acknowledgementReadOrder(boolean callbackFirst) throws Exception {
        stage = callbackFirst ? "ack-callback-before-count-read" : "count-read-before-ack-callback";
        JSONObject row = new JSONObject().put("case",stage); cases.put(row);
        Fixture f = fixture(callbackFirst?10:11,callbackFirst?"190000100":"190000101");
        demo(callbackFirst ? "合成时序验证 · 先显示操作状态，再显示补读计数" : "合成时序验证 · 补读先完成，操作状态稍后到达");
        String scope = main(() -> (String)invoke(activity,"contentScope",new Class<?>[]{}));
        int before = countReadAttempts.get();
        f.callbacks.hold=true; f.transport.reply(1,"{\"data\":[\"合成已点赞\",1,4]}");
        main(() -> {target(f,1).performClick();return null;});
        check(row,"SingleTapQueuedWithCallbacksHeld",f.worker.pending.size()==1);
        f.worker.complete();waitForIdleSync();
        NativeReactionController.Snapshot confirmed=f.controller.snapshot(scope,TID,f.pid);
        check(row,"WorkerAcknowledgedBeforeMainCallback",confirmed!=null&&confirmed.selectionKnown&&confirmed.selected==1&&confirmed.acknowledgedVersion>0&&!f.callbacks.pending.isEmpty());
        check(row,"MainStillHasOldCountsBeforeDirectRead",main(() -> f.post.votes.likesKnown&&f.post.votes.likes==7&&f.post.votes.dislikes==2)&&countReadAttempts.get()==before);
        ReadReply fresh=enqueue(f.floor,f.pid,"0,8,2",true);
        // Deliberately do not rebind postRow or resubscribe before this read: its controller
        // version covers the ACK while Main's old selection revision has not seen it yet.
        main(() -> {startRead(f.screen,f.page,f.generation);return null;});
        check(row,"DirectCountReadEnteredAfterWorkerAck",fresh.entered.await(10,TimeUnit.SECONDS));
        check(row,"ReadVersionAlreadyCoversAck",main(() -> {
            Object version=((java.util.Map<?, ?>)field(activity,"readerVoteRefreshVersions")).get(f.floor+":"+f.pid);
            return version instanceof Long&&(Long)version>=confirmed.acknowledgedVersion;
        }));
        if(callbackFirst){
            f.callbacks.release();waitForIdleSync();
            check(row,"AckCallbackShowsSelectionWhileReadPending",main(() -> selected(f,1)&&!f.post.votes.likesKnown&&!f.post.votes.dislikesKnown));
            check(row,"AckDoesNotStartAnotherAlreadyCoveredRead",countReadAttempts.get()==before+1);
            fresh.release.countDown();drainReads();
            await(() -> main(() -> f.post.votes.likesKnown&&f.post.votes.dislikesKnown&&f.post.votes.likes==8&&f.post.votes.dislikes==2));
            check(row,"CountReadSurvivesInterveningSelectionChange",main(() -> f.post.votes.likes==8&&f.post.votes.dislikes==2&&selected(f,1)));
        }else{
            fresh.release.countDown();drainReads();
            await(() -> main(() -> f.post.votes.likesKnown&&f.post.votes.dislikesKnown&&f.post.votes.likes==8&&f.post.votes.dislikes==2));
            check(row,"FreshCountsArriveWhileAckCallbackStillHeld",!f.callbacks.pending.isEmpty()&&main(() -> f.post.votes.likes==8&&f.post.votes.dislikes==2));
            f.callbacks.release();waitForIdleSync();
            check(row,"LateAckDoesNotClearAlreadyFreshTotals",main(() -> selected(f,1)&&f.post.votes.likesKnown&&f.post.votes.dislikesKnown&&f.post.votes.likes==8&&f.post.votes.dislikes==2));
        }
        main(() -> {invoke(activity,"listenForReaderReactions",new Class<?>[]{});invoke(activity,"listenForReaderReactions",new Class<?>[]{});return null;});drainReads();
        check(row,"ExactlyOneSupplementaryReadWithoutLoop",countReadAttempts.get()==before+1);
        check(row,"FinalExactLabelsAndSelection",main(() -> selected(f,1)&&"点赞 8".contentEquals(((TextView)target(f,1)).getText())&&"点踩 2".contentEquals(((TextView)target(f,-1)).getText())));
        check(row,"OneWriteNoAutomaticRetry",f.transport.writes==1&&f.worker.pending.isEmpty()&&f.callbacks.pending.isEmpty());
        unchanged(row,"CallbackReadOrder",f);demo("合成时序验证 · 最终计数正确，没有重复读写");
        row.put("syntheticWriteCount",f.transport.writes).put("countReadAttempts",countReadAttempts.get()-before).put("callbackBeforeReadCompletion",callbackFirst);
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
                        countReadAttempts.incrementAndGet();
                        ReadReply reply = reads.poll(); if (reply == null) throw new IOException("NO_SYNTHETIC_COUNT_REPLY_QUEUED");
                        served.incrementAndGet(); return new Connection(url, reply);
                    }
                    blocked.incrementAndGet(); throw new IOException("SYNTHETIC_READER_NETWORK_BLOCKED");
                }
            } : null);
            store = new NgaStore(context); store.setAutoCheckIn(false); store.setMotionEnabled(false); store.setTopicPreviewsEnabled(false); store.setCookie(COOKIE);
            activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); waitForIdleSync();
            SystemClock.sleep(150); waitForIdleSync();
            scenario(0, "0"); scenario(7, "190000007"); lateAcknowledgementBinding();
            acknowledgementReadOrder(true); acknowledgementReadOrder(false);
            passed = issues == 0 && cases.length() == 5 && served.get() == 19 && countReadAttempts.get() == 19;
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
                        .put("syntheticPostVoteReads", served.get()).put("countReadAttempts",countReadAttempts.get()).put("otherNetworkAttemptsBlocked", blocked.get())
                        .put("helperRevision",4).put("directTapMode",true).put("demoMode",demoMode).put("demoHoldMs",demoHoldMs)
                        .put("demoTopPaddingDp",56).put("reactionResponseEncoding","GB18030").put("syntheticMarkerAlwaysVisible",true);
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
