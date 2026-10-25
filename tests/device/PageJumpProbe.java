package cn.ngapure.verify;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import cn.ngapure.app.Motion;
import cn.ngapure.app.NativeLoginView;
import cn.ngapure.app.NativePageJumpDialog;
import cn.ngapure.app.NgaStore;
import cn.ngapure.app.PageJumpState;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Local production controls only; fixture listeners never navigate or request data. */
public final class PageJumpProbe extends Instrumentation {
    private static final int EXPECTED_CASES = 9;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final JSONArray cases = new JSONArray();
    private final JSONObject report = new JSONObject();
    private final List<Fixture> fixtures = new ArrayList<>();
    private Activity activity;
    private long deadline;
    private int assertions;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        deadline = SystemClock.elapsedRealtime() + 60000;
        boolean passed = false, cleaned = true;
        String failure = "NONE";
        Snapshot before = null;
        NgaStore store = new NgaStore(getTargetContext());
        try {
            check(!store.autoCheckIn() || (store.loggedIn() && NgaStore.today().equals(store.lastCheckInDay())), "SIGN_IN_GUARD_FAILED");
            check(!NativeLoginView.hasPendingAuthorization(getTargetContext(), store.host()), "AUTHORIZATION_PENDING");
            put(report, "onOpenSignInGuardPassed", true);
            put(report, "pendingAuthorizationGuardPassed", true);
            before = new Snapshot(store);
            activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName("cn.ngapure.app", "cn.ngapure.app.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            check(activity != null, "ACTIVITY_START_FAILED");
            initialAndStepper();
            invalidInput();
            quickChoices();
            sliderSync();
            duplicateSubmit();
            samePage();
            unknownTotal();
            cancelAndReopen();
            darkMotion();
            boolean unchanged = before.same(store);
            put(report, "accountAndSettingsUnchanged", unchanged);
            check(unchanged, "SETTINGS_CHANGED");
            passed = true;
        } catch (CheckFailure problem) { failure = problem.code; }
        catch (Exception | AssertionError problem) { failure = "PAGE_JUMP_PROBE_UNAVAILABLE"; }
        finally {
            try {
                onMainCleanup(() -> {
                    for (Fixture fixture : fixtures) fixture.widget.close();
                    return null;
                });
                cleaned = onMainCleanup(() -> {
                    for (Fixture fixture : fixtures) if (fixture.dialog.isShowing() || fixture.dismissCount != 1) return false;
                    return true;
                });
            } catch (Exception problem) { cleaned = false; }
            if (!cleaned) { passed = false; failure = "FINAL_CLEANUP_FAILED"; }
            before = null;
        }
        put(report, "schema", 1);
        put(report, "purpose", "LOCAL_NATIVE_PAGE_JUMP_CONTROLS");
        put(report, "cases", cases); put(report, "scenarioCount", cases.length());
        put(report, "expectedScenarioCount", EXPECTED_CASES); put(report, "assertionCount", assertions);
        put(report, "fixtureCount", fixtures.size()); put(report, "allDialogsClosed", cleaned);
        put(report, "fixtureListenersNeverNavigate", true); put(report, "networkInvokedByFixtures", false);
        put(report, "accountEndpointInvokedByProbe", false); put(report, "realCredentialsSubmitted", false);
        put(report, "realSmsSent", false); put(report, "mainPageNavigationTested", false);
        put(report, "screenshotsOrAccountTextExported", false); put(report, "visualReviewClaimed", false);
        put(report, "passed", passed && cases.length() == EXPECTED_CASES);
        put(report, "failure", passed ? "NONE" : failure);
        Bundle result = new Bundle(); result.putString("safe_page_jump_report", report.toString());
        finish(Activity.RESULT_OK, result);
    }

    private void initialAndStepper() throws Exception {
        Fixture fixture = open(false, false, 3, 12);
        onMain(() -> {
            check(fixture.dialog.isShowing() && fixture.value() == 3 && fixture.confirm.isEnabled(), "INITIAL_VALUE");
            check(fixture.state.currentPage == 3 && fixture.state.totalPages == 12 && fixture.state.maximumPage == 12, "INITIAL_BOUNDS");
            fixture.next.performClick(); check(fixture.value() == 4, "NEXT_STEP");
            fixture.previous.performClick(); check(fixture.value() == 3, "PREVIOUS_STEP");
            fixture.input.setText("1"); check(!fixture.previous.isEnabled(), "FIRST_STEP_DISABLED");
            fixture.previous.performClick(); check(fixture.value() == 1, "FIRST_STEP_BOUNDED");
            fixture.input.setText("12"); check(!fixture.next.isEnabled(), "LAST_STEP_DISABLED");
            fixture.next.performClick(); check(fixture.value() == 12, "LAST_STEP_BOUNDED");
            touchTargets(fixture);
            return null;
        });
        finishCase("INITIAL_STEPPER_AND_TARGETS", fixture, false);
    }

    private void invalidInput() throws Exception {
        Fixture fixture = open(false, false, 3, 12);
        onMain(() -> {
            for (String invalid : new String[]{"0", "13", ""}) {
                fixture.input.setText(invalid);
                check(!fixture.confirm.isEnabled(), "INVALID_CONFIRM_DISABLED");
                fixture.input.onEditorAction(EditorInfo.IME_ACTION_GO);
                fixture.confirm.performClick();
                check(fixture.dialog.isShowing() && fixture.selectionCount == 0, "INVALID_WINDOW_RETAINED");
            }
            fixture.input.setText("4"); check(fixture.confirm.isEnabled(), "VALID_INPUT_RECOVERS");
            return null;
        });
        put(report, "invalidInputKeepsDialogOpen", true);
        finishCase("INVALID_INPUT_RETAINED", fixture, false);
    }

    private void quickChoices() throws Exception {
        Fixture fixture = open(false, false, 3, 12);
        onMain(() -> {
            check(fixture.chips.length == 5, "QUICK_CHOICE_COUNT");
            for (int i = 0; i < fixture.chips.length; i++) {
                fixture.chips[i].performClick();
                check(fixture.value() == fixture.choices[i] && fixture.chips[i].isSelected(), "QUICK_CHOICE_SELECTED");
                check(fixture.slider.getProgress() == fixture.choices[i] - 1, "QUICK_CHOICE_SLIDER_SYNC");
            }
            return null;
        });
        put(report, "quickChoicesAndInputSynchronized", true);
        finishCase("QUICK_CHOICE_SYNC", fixture, false);
    }

    private void sliderSync() throws Exception {
        Fixture fixture = open(false, false, 3, 12);
        onMain(() -> {
            fixture.input.setText("6");
            check(fixture.slider.getProgress() == 5, "INPUT_TO_SLIDER");
            check(fixture.slider.getWidth() > 48 && fixture.slider.getHeight() > 0, "SLIDER_LAYOUT_READY");
            long now = SystemClock.uptimeMillis();
            float x = fixture.slider.getWidth() * .82f, y = fixture.slider.getHeight() * .5f;
            dispatchTouch(fixture.slider, now, now, MotionEvent.ACTION_DOWN, x, y);
            dispatchTouch(fixture.slider, now, now + 16, MotionEvent.ACTION_MOVE, x + 1, y);
            dispatchTouch(fixture.slider, now, now + 32, MotionEvent.ACTION_UP, x + 1, y);
            check(fixture.slider.getProgress() != 5, "USER_SLIDER_PROGRESS_CHANGED");
            check(fixture.value() == fixture.slider.getProgress() + 1, "USER_SLIDER_TO_INPUT");
            return null;
        });
        put(report, "realSeekBarTouchAndInputSynchronized", true);
        finishCase("SEEK_BAR_USER_TOUCH_SYNC", fixture, false);
    }

    private void duplicateSubmit() throws Exception {
        Fixture fixture = open(false, false, 3, 12);
        onMain(() -> {
            fixture.input.setText("7");
            fixture.input.onEditorAction(EditorInfo.IME_ACTION_GO);
            fixture.confirm.performClick();
            return null;
        });
        awaitSelection(fixture, 7);
        put(report, "imeAndConfirmCallbackExactlyOnce", true);
        put(report, "selectionCallbackAfterDismiss", true);
        finishCase("IME_CONFIRM_DUPLICATE", fixture, true);
    }

    private void samePage() throws Exception {
        Fixture fixture = open(false, false, 3, 12);
        onMain(() -> { fixture.confirm.performClick(); return null; });
        awaitSelection(fixture, 3);
        put(report, "currentPageSelectionPreserved", true);
        finishCase("CURRENT_PAGE_LOCAL_CALLBACK", fixture, true);
    }

    private void unknownTotal() throws Exception {
        Fixture fixture = open(false, false, 3, 0);
        onMain(() -> {
            check(fixture.state.totalPages == 0 && fixture.state.maximumPage == 99999, "UNKNOWN_TOTAL_BOUNDS");
            check(fixture.slider.getParent() == null, "UNKNOWN_SLIDER_NOT_SHOWN");
            check(!containsText(fixture.dialog.getWindow().getDecorView(), "共 99999")
                    && !containsText(fixture.dialog.getWindow().getDecorView(), "末页"), "UNKNOWN_NO_FAKE_TOTAL");
            fixture.input.setText("99999"); check(fixture.confirm.isEnabled() && !fixture.next.isEnabled(), "UNKNOWN_MAX_ACCEPTED");
            fixture.input.setText("100000"); check(!fixture.confirm.isEnabled() && fixture.dialog.isShowing(), "UNKNOWN_OVERFLOW_REJECTED");
            fixture.input.setText("4"); check(fixture.confirm.isEnabled(), "UNKNOWN_VALID_RECOVERS");
            return null;
        });
        put(report, "unknownTotalNotPresentedAsNavigationLimit", true);
        finishCase("UNKNOWN_TOTAL_BOUNDS", fixture, false);
    }

    private void cancelAndReopen() throws Exception {
        Fixture cancelled = open(false, false, 3, 12);
        onMain(() -> { cancelled.input.setText("8"); cancelled.dialog.cancel(); return null; });
        check(onMain(() -> !cancelled.dialog.isShowing() && cancelled.selectionCount == 0 && cancelled.dismissCount == 1), "CANCEL_HAS_NO_SELECTION");
        Fixture reopened = open(false, false, 3, 12);
        check(onMain(() -> reopened.dialog.isShowing() && reopened.value() == 3), "REOPEN_INITIAL_STATE");
        put(report, "cancelDoesNotNavigate", true); put(report, "reopenStartsAtCurrentPage", true);
        finishCase("CANCEL_AND_REOPEN", reopened, false);
    }

    private void darkMotion() throws Exception {
        Fixture fixture = open(true, true, 3, 12);
        onMain(() -> {
            int ink = fixture.input.getCurrentTextColor();
            check(contrast(ink, 0xff223e62) >= 4.5 && contrast(ink, 0xff1d2e46) >= 4.5, "DARK_INPUT_CONTRAST");
            check(contrast(fixture.confirm.getCurrentTextColor(), 0xff2869b9) >= 4.5, "DARK_CONFIRM_CONTRAST");
            touchTargets(fixture);
            put(report, "animationRequestedScenarioRun", true);
            put(report, "systemAnimatorsEnabled", Motion.enabled(activity));
            fixture.input.setText("9"); fixture.input.onEditorAction(EditorInfo.IME_ACTION_GO);
            fixture.confirm.performClick();
            return null;
        });
        awaitSelection(fixture, 9);
        put(report, "darkTextContrastChecked", true); put(report, "motionScenarioCompletesOnceAfterDismiss", true);
        finishCase("DARK_AND_MOTION_LIFECYCLE", fixture, true);
    }

    private Fixture open(boolean dark, boolean motion, int current, int total) throws Exception {
        Fixture fixture = onMain(() -> {
            Fixture created = new Fixture();
            View backdrop = activity.findViewById(android.R.id.content);
            created.widget = new NativePageJumpDialog(activity, backdrop, dark, true, motion, current, total,
                    new NativePageJumpDialog.Listener() {
                        @Override public void onPageSelected(int page) {
                            created.selectionCount++; created.selectedPage = page;
                            // Dialog closes synchronously; Android dispatches its
                            // OnDismiss listener on a later main-loop message.
                            created.selectedAfterDismiss = !created.dialog.isShowing();
                            created.selection.countDown();
                        }
                        @Override public void onDismiss(NativePageJumpDialog source) {
                            if (source == created.widget) created.dismissCount++;
                        }
                    });
            created.dialog = (Dialog) field(created.widget, "dialog");
            created.input = (EditText) field(created.widget, "input");
            created.confirm = (TextView) field(created.widget, "confirm");
            created.previous = (TextView) field(created.widget, "previous");
            created.next = (TextView) field(created.widget, "next");
            created.slider = (SeekBar) field(created.widget, "slider");
            created.state = (PageJumpState) field(created.widget, "state");
            created.chips = (TextView[]) field(created.widget, "chips");
            created.choices = (int[]) field(created.widget, "choices");
            fixtures.add(created); created.widget.show();
            return created;
        });
        long layoutDeadline = SystemClock.elapsedRealtime() + remaining(3000);
        boolean laidOut = false;
        while (SystemClock.elapsedRealtime() < layoutDeadline) {
            laidOut = onMain(() -> fixture.dialog.isShowing() && fixture.input.getWidth() > 0
                    && fixture.confirm.getHeight() > 0);
            if (laidOut) break;
            SystemClock.sleep(Math.min(32, remaining(32)));
        }
        check(laidOut, "DIALOG_LAYOUT_READY");
        return fixture;
    }

    private void touchTargets(Fixture fixture) throws Exception {
        int minimum = Math.round(48 * activity.getResources().getDisplayMetrics().density);
        check(fixture.previous.getWidth() >= minimum && fixture.previous.getHeight() >= minimum, "PREVIOUS_TOUCH_SIZE");
        check(fixture.next.getWidth() >= minimum && fixture.next.getHeight() >= minimum, "NEXT_TOUCH_SIZE");
        check(fixture.confirm.getHeight() >= minimum, "CONFIRM_TOUCH_SIZE");
        for (TextView chip : fixture.chips) check(chip.getHeight() >= minimum, "QUICK_CHOICE_TOUCH_HEIGHT");
        put(report, "primaryTouchTargetsAtLeast48DpHigh", true);
    }

    private void awaitSelection(Fixture fixture, int page) throws Exception {
        check(fixture.selection.await(remaining(4000), TimeUnit.MILLISECONDS), "SELECTION_CALLBACK_DEADLINE");
        onMain(() -> {
            fixture.confirm.performClick();
            check(fixture.selectionCount == 1 && fixture.selectedPage == page, "SELECTION_EXACTLY_ONCE");
            check(fixture.dismissCount == 1 && !fixture.dialog.isShowing() && fixture.selectedAfterDismiss, "DISMISS_BEFORE_SELECTION");
            return null;
        });
    }

    private void finishCase(String name, Fixture fixture, boolean selected) throws Exception {
        onMain(() -> { fixture.widget.close(); fixture.widget.close(); return null; });
        check(onMain(() -> !fixture.dialog.isShowing() && fixture.dismissCount == 1
                && fixture.selectionCount == (selected ? 1 : 0)), "CASE_CLEANUP");
        JSONObject one = new JSONObject(); put(one, "scenario", name); put(one, "passed", true);
        put(one, "selectionCount", fixture.selectionCount); put(one, "dismissCount", fixture.dismissCount);
        put(one, "dialogClosed", true); cases.put(one);
    }

    private static void dispatchTouch(View target, long down, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
        try { target.dispatchTouchEvent(event); } finally { event.recycle(); }
    }
    private static boolean containsText(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (containsText(group.getChildAt(i), text)) return true;
        }
        return false;
    }
    private static double contrast(int first, int second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }
    private static double luminance(int color) {
        return .2126 * channel(Color.red(color)) + .7152 * channel(Color.green(color)) + .0722 * channel(Color.blue(color));
    }
    private static double channel(int value) {
        double normalized = value / 255.0;
        return normalized <= .04045 ? normalized / 12.92 : Math.pow((normalized + .055) / 1.055, 2.4);
    }
    private static Object field(Object target, String name) throws Exception {
        Field value = target.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(target);
    }
    private void check(boolean value, String code) throws CheckFailure { assertions++; if (!value) throw new CheckFailure(code); }
    private long remaining(long maximum) throws CheckFailure {
        long left = deadline - SystemClock.elapsedRealtime();
        if (left <= 0) throw new CheckFailure("PROBE_DEADLINE");
        return Math.min(left, maximum);
    }
    private <T> T onMain(Callable<T> action) throws Exception { return onMainWithin(action, remaining(5000)); }
    private <T> T onMainCleanup(Callable<T> action) throws Exception { return onMainWithin(action, 5000); }
    private <T> T onMainWithin(Callable<T> action, long maximum) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); Object[] value = new Object[1]; Throwable[] error = new Throwable[1];
        main.post(() -> { try { value[0] = action.call(); } catch (Throwable problem) { error[0] = problem; } finally { latch.countDown(); } });
        if (!latch.await(maximum, TimeUnit.MILLISECONDS)) throw new CheckFailure("MAIN_THREAD_DEADLINE");
        if (error[0] instanceof Exception) throw (Exception) error[0];
        if (error[0] != null) throw new CheckFailure("MAIN_THREAD_CHECK_FAILED");
        @SuppressWarnings("unchecked") T result = (T) value[0]; return result;
    }
    private static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value); } catch (Exception ignored) { /* Fixed booleans, counters and scenario enums only. */ }
    }

    private static final class Fixture {
        final CountDownLatch selection = new CountDownLatch(1);
        NativePageJumpDialog widget; Dialog dialog; EditText input; TextView confirm, previous, next;
        TextView[] chips; int[] choices; SeekBar slider; PageJumpState state;
        int selectionCount, dismissCount, selectedPage;
        boolean selectedAfterDismiss;
        int value() { return state.parse(input.getText().toString()); }
    }
    /** Session values are used only for equality, never included in a report. */
    private static final class Snapshot {
        final String cookie, account, host, theme, day, attempt;
        final int font;
        final boolean automatic, glass, motion;
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
    }
    private static final class CheckFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final String code; CheckFailure(String code) { this.code = code; }
    }
}
