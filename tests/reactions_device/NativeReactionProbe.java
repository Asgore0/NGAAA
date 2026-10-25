package cn.ngapure.app;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import cn.ngapure.voteprobe.ReactionHostActivity;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Exact production buttons, confirmation sheets and state adapter; all transport is synthetic. */
public final class NativeReactionProbe extends Instrumentation {
    private final JSONObject report = new JSONObject(), checks = new JSONObject();
    private final List<NativeThreadActionDialog> owners = new ArrayList<>();
    private Activity activity;
    private int assertionCount;
    private String stage = "guard";
    private boolean authorized, demoMode;

    private static final class Session implements NativeThreadActions.SessionSource {
        String cookie = "ngaPassportUid=190000001; ngaPassportCid=synthetic-reaction-fixture-only";
        @Override public String host() { return "bbs.nga.cn"; }
        @Override public String cookie() { return cookie; }
        @Override public boolean loggedIn() { return !cookie.isEmpty(); }
    }
    private static final class Journal implements NativeThreadActions.Journal {
        final Set<String> pending = new HashSet<>();
        @Override public boolean contains(String value) { return pending.contains(value); }
        @Override public boolean put(String value) { return pending.add(value); }
        @Override public boolean remove(String value) { return pending.remove(value); }
    }
    private static final class Transport implements NativeThreadActions.Transport {
        final Queue<Object> replies = new ArrayDeque<>();
        final List<NativeThreadActions.Request> requests = new ArrayList<>();
        Transport result(String json) { replies.add(new NativeThreadActions.Response(200, json.getBytes(StandardCharsets.UTF_8), json.startsWith("<html>") ? "text/html; charset=UTF-8" : "application/json; charset=UTF-8")); return this; }
        Transport disconnect() { replies.add(new IOException("synthetic disconnect; no socket exists")); return this; }
        @Override public NativeThreadActions.Response execute(NativeThreadActions.Request request) throws IOException {
            requests.add(request); Object result = replies.poll();
            if (result == null) throw new AssertionError("SYNTHETIC_RESPONSE_REQUIRED");
            if (result instanceof IOException) throw (IOException) result;
            return (NativeThreadActions.Response) result;
        }
    }
    private static final class Worker implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable value) { tasks.add(value); }
        void complete() { Runnable next = tasks.poll(); if (next == null) throw new AssertionError("ONE_QUEUED_TASK_REQUIRED"); next.run(); }
    }
    private static final class Fixture {
        final Session session = new Session(); final Journal journal = new Journal();
        final Worker worker = new Worker(); final Transport transport;
        final NativeThreadActionDialog owner; final NativeVoteBar bar;
        final AtomicInteger changed = new AtomicInteger();
        final VoteMetrics.ConfirmedSelections selections = new VoteMetrics.ConfirmedSelections();
        final NativeThreadActionDialog.ReactionListener listener;
        Fixture(Activity activity, boolean dark, Transport transport) {
            this.transport = transport;
            NativeThreadActions actions = new NativeThreadActions(session, transport, journal);
            owner = new NativeThreadActionDialog(activity, actions, worker, dark);
            bar = new NativeVoteBar(activity, dark);
            listener = result -> {
                selections.confirmed("synthetic-scope", "190000002", "0", result.selectionKnown, result.selected);
                bar.confirmedReaction(result); changed.incrementAndGet();
            };
            bar.bind(new VoteMetrics(), () -> owner.showReaction("190000002", "0", 1, listener),
                    () -> owner.showReaction("190000002", "0", -1, listener));
        }
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); authorized = args != null && "true".equals(args.getString("syntheticReactionsOnly")); demoMode = args != null && "true".equals(args.getString("demoMode")); start(); }
    private <T> T main(Callable<T> action) throws Exception { FutureTask<T> task = new FutureTask<>(action); runOnMainSync(task); return task.get(10, TimeUnit.SECONDS); }
    private void check(String name, boolean value) throws Exception { assertionCount++; checks.put(name, value); if (!value) throw new AssertionError(name); }
    private static NativeGlassDialog active(Fixture fixture) throws Exception { Field field = NativeThreadActionDialog.class.getDeclaredField("active"); field.setAccessible(true); return (NativeGlassDialog) field.get(fixture.owner); }
    private static TextView target(Fixture value, boolean up) { return value.bar.findViewWithTag(up ? "vote-like" : "vote-dislike"); }
    private Fixture prepare(boolean dark, Transport transport) throws Exception {
        return main(() -> {
            Fixture fixture = new Fixture(activity, dark, transport); owners.add(fixture.owner);
            LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(16), dp(16), dp(16), dp(16));
            TextView banner = new TextView(activity); banner.setText("本机离线点赞回归 · 合成帖子");
            banner.setTextSize(18); banner.setPadding(0, dp(16), 0, dp(24)); content.addView(banner);
            content.addView(fixture.bar, new LinearLayout.LayoutParams(-1, -2));
            activity.setContentView(content); return fixture;
        });
    }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private static boolean text(View root, String needle) {
        if (root instanceof TextView && ((TextView) root).getText().toString().contains(needle)) return true;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) if (text(((ViewGroup) root).getChildAt(i), needle)) return true;
        return false;
    }
    private static Map<String, String> args(NativeThreadActions.Request request) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        for (String item : new String(request.body, StandardCharsets.US_ASCII).split("&")) {
            String[] pair = item.split("=", 2); values.put(URLDecoder.decode(pair[0], "GB18030"), URLDecoder.decode(pair.length == 2 ? pair[1] : "", "GB18030"));
        }
        return values;
    }
    private void cancel(Fixture fixture) throws Exception { main(() -> { fixture.owner.dismiss(); return null; }); }
    /** Dialog delivers OnShow as a main-loop message; a user click necessarily follows that turn. */
    private void open(Fixture fixture, boolean up) throws Exception {
        main(() -> { target(fixture, up).performClick(); return null; });
        waitForIdleSync(); demoPause();
    }
    private void demoPause() throws Exception { if (demoMode) { waitForIdleSync(); Thread.sleep(1000); } }

    private static String envelope(String json) {
        return "<html><head><meta http-equiv='Content-Type' content='text/html; charset=UTF-8'></head><body><script>window.script_muti_get_var_store=" + json + "</script></body></html>";
    }
    private void buttonsAndConfirm() throws Exception {
        stage = "buttonsAndConfirm";
        Fixture fixture = prepare(false, new Transport().result(envelope("{\"data\":[\"合成已点赞\",1,4]}"))
                .result(envelope("{\"data\":[\"合成未更新选择\",0,0]}"))
                .result(envelope("{\"data\":[\"合成撤销点赞\",-1,1]}")));
        check("unknownCountsHaveTwoIndependentLabels", main(() -> target(fixture, true).getText().toString().equals("点赞 —") && target(fixture, false).getText().toString().equals("点踩 —")));
        main(() -> { fixture.bar.counts(VoteMetrics.from(new JSONObject().put("recommend", 15))); return null; });
        check("scalarRecommendationNeverBecomesVisibleNetOrCount", main(() -> target(fixture, true).getText().toString().equals("点赞 —") && target(fixture, false).getText().toString().equals("点踩 —")));
        main(() -> { fixture.bar.counts(VoteMetrics.from(new JSONObject().put("recommend", "0,8,3"))); return null; });
        check("knownCountsDisplayBothDirections", main(() -> target(fixture, true).getText().toString().equals("点赞 8") && target(fixture, false).getText().toString().equals("点踩 3")));
        check("reactionTargetsExposeNativeButtonAccessibilityRole", main(() -> target(fixture, true).getAccessibilityClassName().equals(Button.class.getName()) && target(fixture, false).getAccessibilityClassName().equals(Button.class.getName())));
        open(fixture, true);
        check("openingLikeShowsNativeConfirmation", main(() -> active(fixture).isShowing() && text(active(fixture).getWindow().getDecorView(), "点赞这条内容")));
        check("openingSheetDoesNotDispatchOrQueue", fixture.transport.requests.isEmpty() && fixture.worker.tasks.isEmpty() && fixture.journal.pending.isEmpty());
        main(() -> { active(fixture).getButton(DialogInterface.BUTTON_NEGATIVE).performClick(); return null; });
        check("cancelingLikeNeverWrites", fixture.transport.requests.isEmpty() && fixture.worker.tasks.isEmpty());
        open(fixture, false);
        check("dislikeIsExplicitEntryWithConsistentTitle", main(() -> text(active(fixture).getWindow().getDecorView(), "点踩这条内容") && active(fixture).getButton(DialogInterface.BUTTON_POSITIVE).getText().toString().equals("确认点踩")));
        main(() -> { active(fixture).getButton(DialogInterface.BUTTON_NEGATIVE).performClick(); return null; });
        open(fixture, true);
        main(() -> { active(fixture).getButton(DialogInterface.BUTTON_POSITIVE).performClick(); active(fixture).getButton(DialogInterface.BUTTON_POSITIVE).performClick(); return null; });
        report.put("doubleTapDiagnostic", new JSONObject().put("queuedTasks", fixture.worker.tasks.size()).put("syntheticRequests", fixture.transport.requests.size()).put("dialogShowing", main(() -> active(fixture).isShowing())).put("dialogClosing", main(() -> active(fixture).isClosing())));
        check("confirmedDoubleTapQueuesExactlyOneMutation", fixture.worker.tasks.size() == 1 && fixture.transport.requests.isEmpty());
        check("bothConfirmationButtonsDisabledWhileSending", main(() -> !active(fixture).getButton(DialogInterface.BUTTON_POSITIVE).isEnabled() && !active(fixture).getButton(DialogInterface.BUTTON_NEGATIVE).isEnabled()));
        main(() -> { fixture.worker.complete(); return null; });
        check("serverAcknowledgementDeliveredOnce", fixture.transport.requests.size() == 1 && fixture.changed.get() == 1 && fixture.journal.pending.isEmpty());
        Map<String, String> sent = args(fixture.transport.requests.get(0));
        check("officialLikeFieldsAndTopicHeadAreExact", sent.get("__lib").equals("topic_recommend") && sent.get("__act").equals("add") && sent.get("value").equals("1") && sent.get("tid").equals("190000002") && sent.get("pid").equals("0"));
        check("acknowledgementInvalidatesTotalsWithoutGuessingFromDelta", main(() -> target(fixture, true).getText().toString().equals("点赞 —") && target(fixture, false).getText().toString().equals("点踩 —")));
        demoPause();
        check("productionListenerSelectsOriginalBarAndKeepsConfirmationVisible", main(() -> target(fixture, true).isSelected() && !target(fixture, false).isSelected() && active(fixture).isShowing() && text(active(fixture).getWindow().getDecorView(), "当前已点赞")));
        main(() -> { fixture.bar.counts(VoteMetrics.from(new JSONObject().put("recommend", "0,9,3"))); return null; });
        check("readonlyTupleRefreshRetainsAcknowledgedSelection", main(() -> target(fixture, true).getText().toString().equals("点赞 9") && target(fixture, false).getText().toString().equals("点踩 3") && target(fixture, true).isSelected()));
        long beforeZero = fixture.selections.revision();
        open(fixture, true);
        main(() -> { active(fixture).getButton(DialogInterface.BUTTON_POSITIVE).performClick(); fixture.worker.complete(); return null; });
        check("zeroFlagsPreservePreviousSelectionButInvalidateOldReads", main(() -> target(fixture, true).isSelected() && fixture.selections.selected("synthetic-scope", "190000002", "0") == 1 && !fixture.selections.accepts("synthetic-scope", beforeZero) && text(active(fixture).getWindow().getDecorView(), "请刷新核对当前状态")));
        open(fixture, true);
        main(() -> { active(fixture).getButton(DialogInterface.BUTTON_POSITIVE).performClick(); fixture.worker.complete(); return null; });
        demoPause();
        check("htmlRemovalAcknowledgementClearsSelectionThroughRealListener", main(() -> fixture.changed.get() == 3 && !target(fixture, true).isSelected() && !target(fixture, false).isSelected() && fixture.selections.selected("synthetic-scope", "190000002", "0") == 0 && text(active(fixture).getWindow().getDecorView(), "当前未点赞或点踩")));
        cancel(fixture);
    }
    private void dislikeAndSessionSwitch() throws Exception {
        stage = "dislikeAndSessionSwitch";
        Fixture down = prepare(true, new Transport().result("{\"data\":{\"0\":\"合成已点踩\",\"1\":-1,\"2\":8}}"));
        open(down, false);
        main(() -> { active(down).getButton(DialogInterface.BUTTON_POSITIVE).performClick(); down.worker.complete(); return null; });
        check("darkDislikeConfirmationUsesOfficialNegativeDirection", down.transport.requests.size() == 1 && args(down.transport.requests.get(0)).get("value").equals("-1"));
        check("darkDislikeAcknowledgementUsesConsistentWording", main(() -> text(active(down).getWindow().getDecorView(), "当前已点踩")));
        cancel(down);
        Fixture switched = prepare(false, new Transport());
        open(switched, true);
        main(() -> { active(switched).getButton(DialogInterface.BUTTON_POSITIVE).performClick(); switched.session.cookie = "ngaPassportUid=190000003; ngaPassportCid=different-synthetic-user"; switched.worker.complete(); return null; });
        check("queuedConfirmationCannotWriteReplacementAccount", switched.transport.requests.isEmpty() && switched.journal.pending.isEmpty() && switched.changed.get() == 0);
        check("sessionSwitchHasVisibleErrorAndNoRetryButton", main(() -> text(active(switched).getWindow().getDecorView(), "账号或线路已变化") && !active(switched).getButton(DialogInterface.BUTTON_POSITIVE).isEnabled() && active(switched).getButton(DialogInterface.BUTTON_NEGATIVE).isEnabled()));
        cancel(switched);
    }
    private void unknownAndRetiredUi() throws Exception {
        stage = "unknownAndRetiredUi";
        Fixture unknown = prepare(false, new Transport().disconnect().result(envelope("{\"data\":[\"合成已点踩\",-1,8]}")));
        open(unknown, true);
        main(() -> { active(unknown).getButton(DialogInterface.BUTTON_POSITIVE).performClick(); unknown.worker.complete(); return null; });
        demoPause();
        check("unknownOutcomeIsDurablyGuarded", unknown.transport.requests.size() == 1 && unknown.journal.pending.size() == 1 && unknown.changed.get() == 0);
        check("unknownOutcomeDisablesRetryAndExplainsVerification", main(() -> !active(unknown).getButton(DialogInterface.BUTTON_POSITIVE).isEnabled() && text(active(unknown).getWindow().getDecorView(), "不会自动重试")));
        main(() -> { active(unknown).getButton(DialogInterface.BUTTON_POSITIVE).performClick(); return null; });
        check("disabledUnknownRetryDoesNotQueueAnotherRequest", unknown.worker.tasks.isEmpty() && unknown.transport.requests.size() == 1);
        main(() -> { unknown.owner.showReaction("190000002", "0", -1, unknown.listener); return null; });
        waitForIdleSync();
        main(() -> { active(unknown).getButton(DialogInterface.BUTTON_POSITIVE).performClick(); unknown.worker.complete(); return null; });
        check("reopenedOppositeDirectionCannotRepeatUnknownWrite", unknown.transport.requests.size() == 1 && unknown.journal.pending.size() == 1 && unknown.changed.get() == 0);
        check("reopenedUnknownSheetAlsoDisablesSubmit", main(() -> !active(unknown).getButton(DialogInterface.BUTTON_POSITIVE).isEnabled()));
        main(() -> { active(unknown).getWindow().getDecorView().findViewWithTag("reaction-recovery").performClick(); return null; });
        waitForIdleSync();
        demoPause();
        check("recoveryExplainsLocalOnlyAndStillNeedsSeparateConfirmation", main(() -> text(active(unknown).getWindow().getDecorView(), "不会发送点赞或点踩")));
        main(() -> { active(unknown).getButton(DialogInterface.BUTTON_POSITIVE).performClick(); return null; });
        check("recoveryQueuesOnlyLocalGuardResolution", unknown.worker.tasks.size() == 1 && unknown.transport.requests.size() == 1);
        main(() -> { unknown.worker.complete(); return null; });
        waitForIdleSync();
        demoPause();
        check("resolvedGuardReopensConfirmationWithoutSending", unknown.journal.pending.isEmpty() && unknown.worker.tasks.isEmpty() && unknown.transport.requests.size() == 1 && unknown.changed.get() == 0);
        check("newConfirmationIsExplicitAfterRecovery", main(() -> active(unknown).getButton(DialogInterface.BUTTON_POSITIVE).getText().toString().equals("确认点踩")));
        main(() -> { active(unknown).getButton(DialogInterface.BUTTON_POSITIVE).performClick(); unknown.worker.complete(); return null; });
        demoPause();
        check("onlySecondExplicitConfirmationCanToggleAndUpdateBar", main(() -> unknown.transport.requests.size() == 2 && unknown.changed.get() == 1 && target(unknown, false).isSelected() && !target(unknown, true).isSelected()));
        cancel(unknown);
        Fixture retired = prepare(false, new Transport().result("{\"data\":[\"合成已点赞\",1,4]}"));
        open(retired, true);
        main(() -> { active(retired).getButton(DialogInterface.BUTTON_POSITIVE).performClick(); retired.owner.dismiss(); retired.worker.complete(); return null; });
        check("alreadyConfirmedSyntheticWriteSettlesAfterUiCloses", retired.transport.requests.size() == 1 && retired.journal.pending.isEmpty());
        check("retiredUiCannotDisplaySuccessOrDeliverCallback", main(() -> active(retired) == null) && retired.changed.get() == 0);
    }
    private void responsiveTargets() throws Exception {
        stage = "responsiveTargets";
        main(() -> {
            for (boolean dark : new boolean[]{false, true}) for (float font : new float[]{1f, 1.3f}) {
                Configuration configuration = new Configuration(activity.getResources().getConfiguration()); configuration.fontScale = font;
                NativeVoteBar bar = new NativeVoteBar(activity.createConfigurationContext(configuration), dark);
                VoteMetrics totals = VoteMetrics.from(new JSONObject().put("recommend", "0,2147483647,2147483647"));
                bar.bind(totals, () -> { }, () -> { });
                for (int width : new int[]{200, 320, 640}) {
                    int px = Math.round(width * bar.getResources().getDisplayMetrics().density);
                    bar.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    bar.layout(0, 0, px, bar.getMeasuredHeight());
                    TextView up = bar.findViewWithTag("vote-like"), down = bar.findViewWithTag("vote-dislike");
                    int min = Math.round(48 * bar.getResources().getDisplayMetrics().density);
                    check("independentTouchBounds_" + dark + "_" + font + "_" + width, up.getWidth() >= min && down.getWidth() >= min && up.getHeight() >= min && down.getHeight() >= min && up.getRight() <= down.getLeft() && down.getRight() <= px);
                }
                totals.likes = 1;
                check("boundCountsAreDefensiveCopies_" + dark + "_" + font, bar.findViewWithTag("vote-like").getContentDescription().toString().contains("2147483647"));
                bar.selectedDirection(-1); bar.bind(new VoteMetrics(), () -> { }, () -> { });
                check("reusedBarNeverCarriesAnotherPostSelection_" + dark + "_" + font, !bar.findViewWithTag("vote-like").isSelected() && !bar.findViewWithTag("vote-dislike").isSelected());
            }
            return null;
        });
    }
    @Override public void onStart() {
        boolean passed = false;
        try {
            if (!authorized) throw new IllegalStateException("SYNTHETIC_REACTIONS_ONLY_REQUIRED");
            check("helperHasNoInternetPermission", getTargetContext().checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_DENIED);
            check("helperUidDiffersFromClient", getTargetContext().getApplicationInfo().uid != getTargetContext().getPackageManager().getApplicationInfo("cn.ngapure.app", 0).uid);
            activity = startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(), ReactionHostActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            buttonsAndConfirm(); dislikeAndSessionSwitch(); unknownAndRetiredUi(); responsiveTargets(); passed = true;
        } catch (Exception | AssertionError failure) {
            try { report.put("failureStage", stage).put("failureType", failure.getClass().getSimpleName()).put("failureMessage", failure.getMessage()); } catch (Exception ignored) { }
        } finally {
            try { main(() -> { for (NativeThreadActionDialog owner : owners) owner.dismiss(); if (activity != null) activity.finish(); return null; }); }
            catch (Exception failure) { passed = false; }
            try {
                report.put("passed", passed).put("assertions", assertionCount).put("checks", checks).put("fixtureRevision", 3).put("demoMode", demoMode).put("dialogOnShowMessageDrainedBeforeUserConfirmation", true).put("helperOwnUid", true)
                        .put("internetPermission", false).put("syntheticTransportOnly", true).put("realNetworkRequests", 0)
                        .put("realAccountAccessed", false).put("mainClientStarted", false).put("realCommunityMutationCount", 0);
            } catch (Exception ignored) { }
            Bundle result = new Bundle(); result.putString("safe_reaction_report", report.toString()); finish(Activity.RESULT_OK, result);
        }
    }
}
