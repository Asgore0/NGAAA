package cn.ngapure.verify;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import cn.ngapure.app.GlassPanel;
import cn.ngapure.app.Motion;
import cn.ngapure.app.NativeGlassDialog;
import cn.ngapure.app.NativeLoginView;
import cn.ngapure.app.NgaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Production native dialog controls with local listeners only; no RPC, SMS or login. */
public final class GlassDialogProbe extends Instrumentation {
    private static final int EXPECTED_CASES = 12;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final JSONObject report = new JSONObject();
    private final JSONArray cases = new JSONArray();
    private final List<Fixture> fixtures = new ArrayList<>();
    private Activity activity;
    private long deadline;
    private int assertions;
    private boolean closeAnimationObserved;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        deadline = SystemClock.elapsedRealtime() + 75000;
        boolean passed = false, cleaned = false;
        String failure = "NONE";
        Snapshot before = null;
        NgaStore store = new NgaStore(getTargetContext());
        try {
            check(!store.autoCheckIn() || (store.loggedIn() && NgaStore.today().equals(store.lastCheckInDay())), "SIGN_IN_GUARD_FAILED");
            check(!NativeLoginView.hasPendingAuthorization(getTargetContext(), store.host()), "AUTHORIZATION_PENDING");
            put(report, "onOpenSignInGuardPassed", true); put(report, "pendingAuthorizationGuardPassed", true);
            before = new Snapshot(store);
            activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName("cn.ngapure.app", "cn.ngapure.app.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            check(activity != null, "ACTIVITY_START_FAILED");
            defaultPositive();
            negativeAndNullListener();
            ordinaryList();
            singleChoice();
            replacedButtonListener();
            cancelProtection();
            threeActionsAndInput();
            longPreview();
            replacedDismissListener();
            interruptedClosing();
            darkSolidFallback();
            ownerCallbackCleanup();
            boolean same = before.same(new NgaStore(getTargetContext()));
            put(report, "accountAndSettingsUnchanged", same); check(same, "SETTINGS_CHANGED");
            passed = true;
        } catch (CheckFailure problem) { failure = problem.code; }
        catch (Exception | AssertionError problem) { failure = "GLASS_DIALOG_PROBE_UNAVAILABLE"; }
        finally {
            try {
                cleaned = onMainCleanup(() -> {
                    for (Fixture fixture : fixtures) fixture.dialog.dismiss();
                    for (Fixture fixture : fixtures)
                        if (fixture.dialog.isShowing() || !released(fixture.dialog) || observed(fixture.dialog)) return false;
                    return true;
                });
            } catch (Exception problem) { cleaned = false; }
            if (!cleaned) { passed = false; failure = "FINAL_CLEANUP_FAILED"; }
            if (before != null) {
                boolean same = before.same(new NgaStore(getTargetContext()));
                put(report, "accountAndSettingsUnchangedAfterCleanup", same);
                if (!same) { passed = false; failure = "SETTINGS_CHANGED_AFTER_CLEANUP"; }
                before.clear();
            }
        }
        put(report, "schema", 1); put(report, "purpose", "LOCAL_NATIVE_GLASS_DIALOG_CONTROLS");
        put(report, "cases", cases); put(report, "scenarioCount", cases.length());
        put(report, "expectedScenarioCount", EXPECTED_CASES); put(report, "assertionCount", assertions);
        put(report, "fixtureCount", fixtures.size()); put(report, "allDialogsClosed", cleaned);
        put(report, "allOwnerObserversRemoved", cleaned); put(report, "closeAnimationInterruptionObserved", closeAnimationObserved);
        put(report, "fixtureListenersNeverNavigate", true); put(report, "networkInvokedByFixtures", false);
        put(report, "accountEndpointInvokedByProbe", false); put(report, "realCredentialsSubmitted", false);
        put(report, "realSmsSent", false); put(report, "actualActivityDestroyedByProbe", false);
        put(report, "screenshotsOrAccountTextExported", false); put(report, "visualReviewClaimed", false);
        put(report, "passed", passed && cases.length() == EXPECTED_CASES);
        put(report, "failure", passed ? "NONE" : failure);
        Bundle output = new Bundle(); output.putString("safe_glass_dialog_report", report.toString());
        finish(Activity.RESULT_OK, output);
    }

    private void defaultPositive() throws Exception {
        Fixture f = open(false, false, builder -> builder.setTitle("本机交互测试").setMessage("只执行本地回调")
                .setPositiveButton("继续", (d, which) -> fCallback(d, which)));
        onMain(() -> {
            Button button = f.dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE);
            check(button != null && button.isEnabled(), "POSITIVE_BUTTON_PRESENT");
            button.performClick(); button.performClick();
            check(f.actionCount == 1 && f.which == NativeGlassDialog.BUTTON_POSITIVE && f.actionAfterDismiss, "DEFAULT_POSITIVE_ONCE_AFTER_DISMISS");
            return null;
        });
        finishCase("DEFAULT_POSITIVE_DISMISSES_BEFORE_ONE_ACTION", f);
    }

    private void negativeAndNullListener() throws Exception {
        Fixture negative = open(false, false, builder -> builder.setTitle("取消操作").setNegativeButton("取消", this::fCallback));
        onMain(() -> { negative.dialog.getButton(NativeGlassDialog.BUTTON_NEGATIVE).performClick(); return null; });
        check(onMain(() -> negative.actionCount == 1 && negative.which == NativeGlassDialog.BUTTON_NEGATIVE
                && negative.actionAfterDismiss), "NEGATIVE_ONCE_AFTER_DISMISS");
        Fixture empty = open(false, false, builder -> builder.setTitle("提示").setPositiveButton("知道了", null));
        onMain(() -> { empty.dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE).performClick(); return null; });
        check(onMain(() -> !empty.dialog.isShowing() && empty.actionCount == 0), "NULL_LISTENER_STILL_CLOSES");
        finishCase("NEGATIVE_AND_NULL_LISTENER", negative, empty);
    }

    private void ordinaryList() throws Exception {
        Fixture f = open(false, false, builder -> builder.setTitle("选择本地选项")
                .setItems(new CharSequence[]{"选项甲", "选项乙", "选项丙"}, this::fCallback));
        onMain(() -> {
            View choice = contentChoice(f.dialog, "选项乙");
            check(choice != null, "LIST_CHOICE_PRESENT"); choice.performClick(); choice.performClick();
            check(f.actionCount == 1 && f.which == 1 && f.actionAfterDismiss, "LIST_ONCE_AFTER_DISMISS");
            return null;
        });
        finishCase("ORDINARY_LIST_DISMISSES_BEFORE_ACTION", f);
    }

    private void singleChoice() throws Exception {
        Fixture f = open(false, false, builder -> builder.setTitle("单项选择")
                .setSingleChoiceItems(new CharSequence[]{"浅色", "深色", "跟随"}, 0, this::fCallback)
                .setNegativeButton("关闭", null));
        onMain(() -> {
            check(checkedRadios(f.dialog.getWindow().getDecorView()) == 1, "SINGLE_INITIAL_RADIO");
            View selected = contentChoice(f.dialog, "深色"); check(selected != null, "SINGLE_CHOICE_PRESENT"); selected.performClick();
            check(f.dialog.isShowing() && f.actionCount == 1 && f.which == 1 && !f.actionAfterDismiss, "SINGLE_CHOICE_REMAINS_OPEN");
            check(selected.isSelected() && checkedRadios(f.dialog.getWindow().getDecorView()) == 1, "SINGLE_RADIO_UPDATED_EXACTLY_ONE");
            f.dialog.getButton(NativeGlassDialog.BUTTON_NEGATIVE).performClick(); return null;
        });
        finishCase("SINGLE_CHOICE_REMAINS_OPEN_AND_UPDATES_RADIO", f);
    }

    private void replacedButtonListener() throws Exception {
        EditText input = onMain(() -> { EditText value = new EditText(activity); value.setSingleLine(); return value; });
        Fixture f = open(false, false, builder -> builder.setTitle("本地输入").setView(input).setPositiveButton("插入", null));
        onMain(() -> {
            f.dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                f.actionCount++;
                if (input.length() > 0) f.dialog.dismiss();
            });
            f.dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE).performClick();
            check(f.dialog.isShowing() && f.actionCount == 1, "OVERRIDE_VALIDATION_RETAINS_DIALOG");
            input.setText("local fixture"); f.dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE).performClick();
            check(!f.dialog.isShowing() && f.actionCount == 2, "OVERRIDE_EXPLICIT_DISMISS"); return null;
        });
        finishCase("POST_SHOW_BUTTON_LISTENER_REPLACES_AUTO_DISMISS", f);
    }

    private void cancelProtection() throws Exception {
        Fixture f = open(false, false, builder -> builder.setTitle("正在处理").setMessage("只测试关闭保护")
                .setCancelable(false).setNegativeButton("返回", null));
        onMain(() -> {
            View close = (View) field(f.dialog, "closeButton");
            check(!close.isEnabled(), "LOCKED_HEADER_DISABLED"); close.performClick();
            f.dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
            f.dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
            MotionEvent outside = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_OUTSIDE, -20, -20, 0);
            try { f.dialog.onTouchEvent(outside); } finally { outside.recycle(); }
            check(f.dialog.isShowing() && f.dismissCount == 0 && f.cancelCount == 0, "LOCKED_BACK_OUTSIDE_HEADER_RETAINED");
            f.dialog.setCancelable(true); check(close.isEnabled(), "UNLOCKED_HEADER_ENABLED"); close.performClick();
            return null;
        });
        awaitClosed(f);
        check(onMain(() -> f.cancelCount == 1 && f.actionCount == 0), "HEADER_CANCEL_NO_ACTION");
        finishCase("NONCANCELABLE_HEADER_BACK_AND_OUTSIDE", f);
    }

    private void threeActionsAndInput() throws Exception {
        EditText input = onMain(() -> { EditText value = new EditText(activity); value.setHint("本机测试字段"); value.setSingleLine(); return value; });
        Fixture f = open(false, false, builder -> builder.setTitle("搜索本地样式").setView(input)
                .setNegativeButton("取消", null).setNeutralButton("查找用户", this::fCallback).setPositiveButton("搜索帖子", this::fCallback));
        onMain(() -> {
            for (int which : new int[]{NativeGlassDialog.BUTTON_POSITIVE, NativeGlassDialog.BUTTON_NEGATIVE, NativeGlassDialog.BUTTON_NEUTRAL}) {
                Button b = f.dialog.getButton(which);
                check(b != null && b.isShown() && b.getWidth() > 0 && b.getHeight() >= dp(48), "THREE_BUTTON_TARGETS");
                check(b.getTop() >= 0 && b.getBottom() <= ((View) b.getParent()).getHeight(), "THREE_BUTTON_UNCLIPPED");
            }
            input.setText("fixture"); check(input.length() == 7 && input.getBackground() != null && input.getMinHeight() >= dp(48), "NATIVE_INPUT_REMAINS_EDITABLE");
            f.dialog.getButton(NativeGlassDialog.BUTTON_NEUTRAL).performClick();
            check(f.actionCount == 1 && f.which == NativeGlassDialog.BUTTON_NEUTRAL && f.actionAfterDismiss, "NEUTRAL_ACTION_CORRECT");
            return null;
        });
        finishCase("THREE_ACTIONS_AND_NATIVE_INPUT", f);
    }

    private void longPreview() throws Exception {
        ScrollView preview = onMain(() -> {
            ScrollView scroll = new ScrollView(activity); TextView text = new TextView(activity);
            StringBuilder rows = new StringBuilder(); for (int i = 0; i < 180; i++) rows.append("本机预览内容与原生滚动容器\n");
            text.setText(rows); text.setTextSize(16); scroll.addView(text); return scroll;
        });
        Fixture f = open(false, false, builder -> builder.setTitle("内容预览").setView(preview).setPositiveButton("返回编辑", null));
        onMain(() -> {
            LinearLayout body = (LinearLayout) field(f.dialog, "body");
            check(body.getParent() instanceof ScrollView, "LONG_BODY_NATIVE_SCROLL");
            ScrollView outer = (ScrollView) body.getParent();
            View shell=(View)field(f.dialog,"shell"),outerShell=(View)field(f.dialog,"outerScroll");Button confirm=f.dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE);android.graphics.Rect confirmBounds=new android.graphics.Rect();boolean visible=confirm.getGlobalVisibleRect(confirmBounds);
            put(report,"longPreviewGeometry",new JSONObject().put("bodyHeight",body.getHeight()).put("bodyScrollHeight",outer.getHeight()).put("previewHeight",preview.getHeight()).put("previewTextHeight",preview.getChildAt(0).getHeight()).put("shellHeight",shell.getHeight()).put("outerShellHeight",outerShell.getHeight()).put("confirmHeight",confirm.getHeight()).put("confirmVisible",visible).put("confirmVisibleHeight",confirmBounds.height()).put("innerCanScroll",preview.canScrollVertically(1)||preview.canScrollVertically(-1)).put("outerCanScroll",outer.canScrollVertically(1)||outer.canScrollVertically(-1)));
            check(visible && confirmBounds.height() == confirm.getHeight() && confirmBounds.width() == confirm.getWidth()
                    && confirm.getHeight() >= dp(48), "LONG_PREVIEW_FIXED_ACTION_INITIALLY_FULLY_VISIBLE");
            check(shell.getHeight() == outerShell.getHeight() && shell.getHeight() > 0
                    && !outerShell.canScrollVertically(1) && !outerShell.canScrollVertically(-1), "NORMAL_SHELL_BOUNDED_WITHOUT_OUTER_SCROLL");
            check(body.getHeight() > outer.getHeight() && outer.getHeight() > dp(40), "LONG_BODY_BOUNDED_TO_WINDOW");
            outer.scrollTo(0, body.getHeight()); check(outer.getScrollY() > 0, "LONG_PREVIEW_CAN_SCROLL");
            Button done = f.dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE);
            android.graphics.Rect afterScroll = new android.graphics.Rect();
            check(done.getGlobalVisibleRect(afterScroll) && afterScroll.equals(confirmBounds), "LONG_PREVIEW_FIXED_ACTION_REMAINS_PINNED");
            done.performClick(); return null;
        });
        finishCase("LONG_NATIVE_PREVIEW_SCROLL_AND_FIXED_ACTION", f);
    }

    private void replacedDismissListener() throws Exception {
        Fixture f = open(false, false, builder -> builder.setTitle("生命周期").setPositiveButton("关闭", null));
        onMain(() -> {
            f.dialog.setOnDismissListener(ignored -> { f.clientReplacementCount++; f.releasedDuringClient = released(f.dialog); });
            f.dialog.dismiss(); f.dialog.dismiss();
            check(f.clientReplacementCount == 1 && f.dismissCount == 0 && f.releasedDuringClient, "REPLACED_CLIENT_DISMISS_ONCE_AFTER_RELEASE");
            check(released(f.dialog) && !observed(f.dialog) && backdropReleased(f.dialog), "REPLACED_LISTENER_CANNOT_SKIP_RELEASE");
            return null;
        });
        finishCase("REPLACED_CLIENT_DISMISS_PRESERVES_INTERNAL_RELEASE", f);
    }

    private void interruptedClosing() throws Exception {
        Fixture f = open(false, true, builder -> builder.setTitle("关闭动画").setPositiveButton("继续", this::fCallback));
        onMain(() -> {
            boolean animators = Motion.enabled(activity);
            f.dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE).performClick();
            closeAnimationObserved = animators && f.dialog.isShowing() && (Boolean) field(f.dialog, "autoClosing");
            if (animators) check(closeAnimationObserved && f.actionCount == 0, "CLOSE_ANIMATION_STARTED_BEFORE_ACTION");
            f.dialog.dismiss(); return null;
        });
        SystemClock.sleep(380);
        onMain(() -> {
            check(!f.dialog.isShowing() && released(f.dialog), "INTERRUPTED_DIALOG_CLOSED");
            check(f.actionCount == (closeAnimationObserved ? 0 : 1), "PROGRAM_DISMISS_CANCELS_PENDING_ACTION"); return null;
        });
        finishCase("PROGRAM_DISMISS_INTERRUPTS_PENDING_CLOSE_ACTION", f);
    }

    private void darkSolidFallback() throws Exception {
        Fixture dark = open(true, false, builder -> builder.setGlassEnabled(false).setTitle("深色原生提示")
                .setMessage("本地文字").setPositiveButton("知道了", null));
        Fixture light;
        onMain(() -> {
            GlassPanel material = (GlassPanel) field(dark.dialog, "material");
            TextView heading = (TextView) field(dark.dialog, "heading");
            check(!material.isGlassEnabled() && (Boolean) field(material, "night"), "DARK_GLASS_DISABLED_FALLBACK");
            int surface = (Integer) field(material, "surfaceColor");
            check(Color.alpha(surface) == 255 && contrast(heading.getCurrentTextColor(), surface) >= 7, "DARK_FALLBACK_OPAQUE_READABLE");
            dark.dialog.dismiss(); return null;
        });
        light = open(false, false, builder -> builder.setGlassEnabled(false).setTitle("浅色原生提示").setPositiveButton("知道了", null));
        onMain(() -> {
            GlassPanel material = (GlassPanel) field(light.dialog, "material");
            TextView heading = (TextView) field(light.dialog, "heading");
            check(!material.isGlassEnabled() && !(Boolean) field(material, "night"), "LIGHT_GLASS_DISABLED_FALLBACK");
            int surface = (Integer) field(material, "surfaceColor");
            check(Color.alpha(surface) == 255 && contrast(heading.getCurrentTextColor(), surface) >= 7, "LIGHT_FALLBACK_OPAQUE_READABLE");
            return null;
        });
        finishCase("DARK_AND_LIGHT_OPAQUE_GLASS_FALLBACK", dark, light);
    }

    private void ownerCallbackCleanup() throws Exception {
        Fixture f = open(false, false, builder -> builder.setTitle("所属窗口清理").setPositiveButton("继续", this::fCallback));
        onMain(() -> {
            check(observed(f.dialog), "OWNER_CALLBACK_REGISTERED");
            Application.ActivityLifecycleCallbacks callback = (Application.ActivityLifecycleCallbacks) field(f.dialog, "owner");
            callback.onActivityDestroyed(null);
            check(f.dialog.isShowing() && observed(f.dialog), "UNRELATED_OWNER_IGNORED");
            callback.onActivityDestroyed(activity);
            check(!f.dialog.isShowing() && released(f.dialog) && !observed(f.dialog)
                    && f.dismissCount == 1 && f.actionCount == 0 && backdropReleased(f.dialog), "OWNER_DESTROY_CALLBACK_RELEASES_WITHOUT_ACTION");
            return null;
        });
        finishCase("OWNER_DESTROY_CALLBACK_CLEANUP_WITHOUT_ACTIVITY_FINISH", f);
    }

    private Fixture open(boolean dark, boolean motion, Configure configure) throws Exception {
        Fixture f = onMain(() -> {
            Fixture value = new Fixture();
            NativeGlassDialog.Builder builder = new NativeGlassDialog.Builder(activity, dark).setMotionEnabled(motion);
            value.dialog = configure.apply(builder).create(); fixtures.add(value);
            value.dialog.setOnDismissListener(ignored -> value.dismissCount++);
            value.dialog.setOnCancelListener(ignored -> value.cancelCount++);
            value.dialog.show(); return value;
        });
        long until = SystemClock.elapsedRealtime() + remaining(3500);
        while (SystemClock.elapsedRealtime() < until) {
            if (onMain(() -> f.dialog.isShowing() && f.dialog.getWindow().getDecorView().getWidth() > 0
                    && ((View) field(f.dialog, "footer")).getHeight() > 0)) break;
            SystemClock.sleep(30);
        }
        onMain(() -> {
            check(f.dialog.isShowing() && !released(f.dialog) && observed(f.dialog), "DIALOG_OPEN_AND_OBSERVED");
            check(f.dialog.getWindow().getDecorView().getWidth() > 0, "DIALOG_LAID_OUT"); return null;
        });
        return f;
    }

    private void fCallback(android.content.DialogInterface dialog, int which) {
        for (Fixture fixture : fixtures) if (fixture.dialog == dialog) {
            fixture.actionCount++; fixture.which = which; fixture.actionAfterDismiss = !fixture.dialog.isShowing(); return;
        }
        throw new IllegalStateException("UNOWNED_LOCAL_CALLBACK");
    }

    private void awaitClosed(Fixture fixture) throws Exception {
        long until = SystemClock.elapsedRealtime() + remaining(3000);
        while (onMain(() -> fixture.dialog.isShowing()) && SystemClock.elapsedRealtime() < until) SystemClock.sleep(20);
        check(onMain(() -> !fixture.dialog.isShowing()), "DIALOG_CLOSE_DEADLINE");
    }

    private void finishCase(String name, Fixture... values) throws Exception {
        onMain(() -> { for (Fixture fixture : values) fixture.dialog.dismiss(); return null; });
        int actions = 0, dismissals = 0;
        for (Fixture f : values) {
            check(onMain(() -> !f.dialog.isShowing() && released(f.dialog) && !observed(f.dialog)), "CASE_RELEASE_CLEANUP");
            actions += f.actionCount; dismissals += f.dismissCount + f.clientReplacementCount;
        }
        JSONObject one = new JSONObject(); put(one, "scenario", name); put(one, "passed", true);
        put(one, "fixtureCount", values.length); put(one, "actionCount", actions); put(one, "dismissCount", dismissals);
        put(one, "dialogsClosed", true); put(one, "ownerObserversRemoved", true); cases.put(one);
    }

    private static View contentChoice(NativeGlassDialog dialog, String label) throws Exception {
        ViewGroup body = (ViewGroup) field(dialog, "body");
        for (int i = 0; i < body.getChildCount(); i++) {
            View candidate = body.getChildAt(i);
            if (candidate.getContentDescription() != null && label.contentEquals(candidate.getContentDescription())) return candidate;
        }
        return null;
    }
    private static int checkedRadios(View view) {
        int count = view instanceof RadioButton && ((RadioButton) view).isChecked() ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) count += checkedRadios(group.getChildAt(i));
        }
        return count;
    }
    private static boolean backdropReleased(NativeGlassDialog dialog) throws Exception {
        GlassPanel panel = (GlassPanel) field(dialog, "material");
        return field(field(panel, "backdrop"), "pixels") == null;
    }
    private static boolean released(NativeGlassDialog dialog) { return flag(dialog, "released"); }
    private static boolean observed(NativeGlassDialog dialog) { return flag(dialog, "observed"); }
    private static boolean flag(Object target, String name) {
        try { return (Boolean) field(target, name); } catch (Exception problem) { throw new IllegalStateException("DIALOG_STATE_UNAVAILABLE"); }
    }
    private static Object field(Object target, String name) throws Exception {
        Field value = target.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(target);
    }
    private static double contrast(int first, int second) {
        double a = luminance(first), b = luminance(second); return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }
    private static double luminance(int value) { return .2126 * channel(Color.red(value)) + .7152 * channel(Color.green(value)) + .0722 * channel(Color.blue(value)); }
    private static double channel(int value) { double n = value / 255.0; return n <= .04045 ? n / 12.92 : Math.pow((n + .055) / 1.055, 2.4); }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private void check(boolean value, String code) throws CheckFailure { assertions++; if (!value) throw new CheckFailure(code); }
    private long remaining(long maximum) throws CheckFailure {
        long left = deadline - SystemClock.elapsedRealtime(); if (left <= 0) throw new CheckFailure("PROBE_DEADLINE"); return Math.min(left, maximum);
    }
    private <T> T onMain(Callable<T> action) throws Exception { return onMainWithin(action, remaining(5000)); }
    private <T> T onMainCleanup(Callable<T> action) throws Exception { return onMainWithin(action, 5000); }
    private <T> T onMainWithin(Callable<T> action, long timeout) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); Object[] values = new Object[1]; Throwable[] errors = new Throwable[1];
        main.post(() -> { try { values[0] = action.call(); } catch (Throwable error) { errors[0] = error; } finally { latch.countDown(); } });
        if (!latch.await(timeout, TimeUnit.MILLISECONDS)) throw new CheckFailure("MAIN_THREAD_DEADLINE");
        if (errors[0] instanceof Exception) throw (Exception) errors[0];
        if (errors[0] != null) throw new CheckFailure("MAIN_THREAD_CHECK_FAILED");
        @SuppressWarnings("unchecked") T result = (T) values[0]; return result;
    }
    private static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value); } catch (Exception ignored) { /* Only fixed enums, booleans and counters. */ }
    }
    private interface Configure { NativeGlassDialog.Builder apply(NativeGlassDialog.Builder builder); }
    private static final class Fixture {
        NativeGlassDialog dialog; int actionCount, dismissCount, cancelCount, which, clientReplacementCount;
        boolean actionAfterDismiss, releasedDuringClient;
    }
    /** These values are compared only in memory and cleared; never serialized. */
    private static final class Snapshot {
        String cookie, account, host, theme, day, attempt;
        final int font; final boolean automatic, glass, motion;
        Snapshot(NgaStore store) {
            cookie = store.cookie(); account = store.accountId(); host = store.host(); theme = store.theme();
            day = store.lastCheckInDay(); attempt = store.lastCheckInAttempt(); font = store.fontSize();
            automatic = store.autoCheckIn(); glass = store.glassEnabled(); motion = store.motionEnabled();
        }
        boolean same(NgaStore store) {
            return cookie.equals(store.cookie()) && account.equals(store.accountId()) && host.equals(store.host())
                    && theme.equals(store.theme()) && day.equals(store.lastCheckInDay()) && attempt.equals(store.lastCheckInAttempt())
                    && font == store.fontSize() && automatic == store.autoCheckIn() && glass == store.glassEnabled() && motion == store.motionEnabled();
        }
        void clear() { cookie = account = host = theme = day = attempt = ""; }
    }
    private static final class CheckFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final String code; CheckFailure(String code) { this.code = code; }
    }
}
