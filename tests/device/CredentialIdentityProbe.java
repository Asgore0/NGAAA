package cn.ngapure.verify;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import cn.ngapure.app.NativeCredentialAuth;
import cn.ngapure.app.NativeCredentialLoginView;
import cn.ngapure.app.NativeLoginView;
import cn.ngapure.app.NgaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Local SMS form recovery only. Every invoked auth owner uses a synthetic transport. */
public final class CredentialIdentityProbe extends Instrumentation {
    private static final int EXPECTED_CASES = 10;
    private static final String PHONE = "13800000000", OTHER_PHONE = "13800000001";
    private static final String IDENTITY = "SYNTHETIC_ACCOUNT", OTHER_IDENTITY = "SYNTHETIC_OTHER";
    private static final String CODE = "123456";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final JSONObject report = new JSONObject();
    private final JSONArray cases = new JSONArray();
    private final List<Fixture> fixtures = new ArrayList<>();
    private Activity activity;
    private Root saved;
    private long deadline;
    private int assertions;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        deadline = SystemClock.elapsedRealtime() + 75000;
        boolean passed = false, cleaned = false, restored = false;
        String failure = "NONE";
        Snapshot before = null;
        try {
            NgaStore store = new NgaStore(getTargetContext());
            check(!store.autoCheckIn() || (store.loggedIn() && NgaStore.today().equals(store.lastCheckInDay())), "SIGN_IN_GUARD_FAILED");
            check(!NativeLoginView.hasPendingAuthorization(getTargetContext(), store.host()), "AUTHORIZATION_PENDING");
            put(report, "onOpenSignInGuardPassed", true); put(report, "pendingAuthorizationGuardPassed", true);
            before = new Snapshot(store);
            activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName("cn.ngapure.app", "cn.ngapure.app.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            check(activity != null, "ACTIVITY_START_FAILED");
            saved = onMain(this::replaceRoot);
            initialIdentity();
            accountRequired();
            manualRetry();
            changedPhone();
            changedIdentity();
            smsLoginRoute();
            busyLocks();
            staleRevision();
            closeCleanup();
            backgroundCleanup();
            boolean same = before.same(new NgaStore(getTargetContext()));
            put(report, "accountAndSettingsUnchanged", same); check(same, "ACCOUNT_OR_SETTINGS_CHANGED");
            passed = true;
        } catch (CheckFailure problem) { failure = problem.code; }
        catch (Exception | AssertionError problem) { failure = "CREDENTIAL_IDENTITY_PROBE_UNAVAILABLE"; }
        finally {
            for (Fixture fixture : fixtures) fixture.fake.release.countDown();
            try {
                cleaned = onMainCleanup(() -> {
                    for (Fixture fixture : fixtures) fixture.view.close();
                    if (saved != null) saved.body.removeAllViews();
                    for (Fixture fixture : fixtures) {
                        if (!flag(fixture.view, "closed") || flag(fixture.view, "busy") || flag(fixture.view, "challenged")
                                || !flag(fixture.owner, "closed") || !emptyInputs(fixture)) return false;
                    }
                    return true;
                });
            } catch (Exception problem) { cleaned = false; }
            if (!cleaned) { passed = false; failure = "FINAL_CLEANUP_FAILED"; }
            try {
                if (saved != null) {
                    onMainCleanup(() -> { saved.host.removeAllViews(); saved.host.addView(saved.original, saved.params); return null; });
                    restored = true;
                }
            } catch (Exception problem) { passed = false; failure = "ROOT_RESTORE_FAILED"; }
            if (!restored) passed = false;
            if (before != null) {
                boolean same = before.same(new NgaStore(getTargetContext()));
                put(report, "accountAndSettingsUnchangedAfterCleanup", same);
                if (!same) { passed = false; failure = "ACCOUNT_OR_SETTINGS_CHANGED_AFTER_CLEANUP"; }
                before.clear();
            }
        }
        int calls = 0, loginCalls = 0;
        for (Fixture fixture : fixtures) { calls += fixture.fake.calls.get(); loginCalls += fixture.fake.loginCalls.get(); }
        put(report, "schema", 1); put(report, "purpose", "LOCAL_SMS_ACCOUNT_IDENTITY_FORM");
        put(report, "cases", cases); put(report, "scenarioCount", cases.length()); put(report, "expectedScenarioCount", EXPECTED_CASES);
        put(report, "fixtureCount", fixtures.size()); put(report, "assertionCount", assertions);
        put(report, "transportCallCount", calls); put(report, "syntheticSmsLoginCalls", loginCalls);
        put(report, "originalRootRestored", restored); put(report, "allFixturesClosed", cleaned); put(report, "allFakeOwnersClosed", cleaned);
        put(report, "networkInvokedByFixtures", false); put(report, "realCredentialsSubmitted", false); put(report, "realSmsSent", false);
        put(report, "accountSwitchPerformed", false); put(report, "syntheticSuccessfulGrantCommitted", false);
        put(report, "screenshotsOrAccountTextExported", false);
        put(report, "passed", passed && cases.length() == EXPECTED_CASES); put(report, "failure", passed ? "NONE" : failure);
        Bundle output = new Bundle(); output.putString("safe_credential_identity_report", report.toString());
        finish(Activity.RESULT_OK, output);
    }

    private void initialIdentity() throws Exception {
        Fixture f = open(false);
        onMain(() -> {
            check(f.identity.getVisibility() == View.VISIBLE && f.identity.isShown(), "IDENTITY_INITIALLY_VISIBLE");
            check("NGA 用户名或 UID".contentEquals(f.identity.getContentDescription()), "IDENTITY_ACCESSIBILITY_LABEL");
            check("绑定手机号".contentEquals(f.phone.getContentDescription()), "PHONE_ACCESSIBILITY_LABEL");
            check("短信验证码".contentEquals(f.code.getContentDescription()), "CODE_ACCESSIBILITY_LABEL");
            check(f.phone.isEnabled() && f.identity.isEnabled() && f.code.isEnabled(), "IDLE_INPUTS_ENABLED");
            check(f.fake.calls.get() == 0, "INITIAL_FORM_NO_TRANSPORT"); return null;
        });
        put(report, "identityInputInitiallyVisible", true); finishCase("INITIAL_IDENTITY_VISIBLE_AND_LABELLED", f);
    }

    private void accountRequired() throws Exception {
        Fixture f = open(false);
        onMain(() -> {
            f.phone.setText(PHONE); f.code.setText(CODE);
            apply(f, result("ACCOUNT_REQUIRED"), true);
            check(PHONE.contentEquals(f.phone.getText()), "REQUIRED_ACCOUNT_RETAINS_PHONE");
            check(f.identity.isShown() && f.identity.hasFocus() && f.identity.getError() != null, "REQUIRED_ACCOUNT_FOCUSED_INLINE_ERROR");
            check(f.code.length() == 0, "REQUIRED_ACCOUNT_CLEARS_OLD_CODE");
            check(!flag(f.view, "busy") && f.phone.isEnabled() && f.identity.isEnabled(), "REQUIRED_ACCOUNT_RELEASES_FORM");
            check(!containsSynthetic(f.status.getText()) && !containsSynthetic(f.identity.getError()), "ERROR_NO_FORM_VALUE_ECHO");
            check(f.fake.calls.get() == 0, "REQUIRED_ACCOUNT_NO_SEND"); return null;
        });
        put(report, "accountRequiredRecovery", true); finishCase("ACCOUNT_REQUIRED_RECOVERY_WITH_INLINE_IDENTITY_ERROR", f);
    }

    private void manualRetry() throws Exception {
        Fixture f = open(false);
        onMain(() -> { f.phone.setText(PHONE); apply(f, result("ACCOUNT_REQUIRED"), true); f.identity.setText(IDENTITY); return null; });
        SystemClock.sleep(180);
        onMain(() -> {
            check(f.fake.calls.get() == 0 && !flag(f.view, "busy") && !flag(f.view, "challenged"), "EDIT_IDENTITY_NO_AUTOMATIC_SEND_OR_CHALLENGE");
            check(!flag(f.view, "committed"), "EDIT_IDENTITY_NO_COMMIT"); return null;
        });
        put(report, "manualRetryOnly", true); finishCase("FILLING_IDENTITY_REQUIRES_NEW_EXPLICIT_SEND", f);
    }

    private void changedPhone() throws Exception {
        Fixture f = open(false);
        onMain(() -> {
            f.phone.setText(PHONE); f.identity.setText(IDENTITY); f.code.setText(CODE);
            int revision = integer(f.view, "inputRevision"); f.phone.setText(OTHER_PHONE);
            check(f.code.length() == 0 && integer(f.view, "inputRevision") > revision, "PHONE_CHANGE_INVALIDATES_CODE_AND_REVISION");
            check(IDENTITY.contentEquals(f.identity.getText()) && f.fake.calls.get() == 0, "PHONE_CHANGE_PRESERVES_IDENTITY_WITHOUT_SEND"); return null;
        });
        finishCase("CHANGING_PHONE_INVALIDATES_OLD_CODE", f);
    }

    private void changedIdentity() throws Exception {
        Fixture f = open(false);
        onMain(() -> {
            f.phone.setText(PHONE); f.identity.setText(IDENTITY); f.code.setText(CODE);
            int revision = integer(f.view, "inputRevision"); f.identity.setText(OTHER_IDENTITY);
            check(f.code.length() == 0 && integer(f.view, "inputRevision") > revision, "IDENTITY_CHANGE_INVALIDATES_CODE_AND_REVISION");
            check(PHONE.contentEquals(f.phone.getText()) && f.fake.calls.get() == 0, "IDENTITY_CHANGE_PRESERVES_PHONE_WITHOUT_SEND"); return null;
        });
        put(report, "targetChangeClearsCode", true); finishCase("CHANGING_IDENTITY_INVALIDATES_OLD_CODE", f);
    }

    private void smsLoginRoute() throws Exception {
        Fixture f = open(false);
        onMain(() -> { fillLogin(f); invoke(f.view, "login"); return null; });
        waitIdle(f);
        onMain(() -> {
            check(f.fake.calls.get() == 1 && f.fake.loginCalls.get() == 1 && f.fake.phoneRoute, "SMSLOGIN_USES_PHONE_NAME_AND_TYPE");
            check(f.fake.smsSendCalls.get() == 0 && f.fake.exchangeCalls.get() == 0, "SMSLOGIN_NO_SEND_OR_GRANT_EXCHANGE");
            check(!flag(f.view, "committed") && !containsSynthetic(f.status.getText()), "SYNTHETIC_REJECTION_NO_COMMIT_OR_ECHO"); return null;
        });
        put(report, "smsLoginPhoneRoute", true); finishCase("SMSLOGIN_RETAINS_OFFICIAL_PHONE_ROUTE", f);
    }

    private void busyLocks() throws Exception {
        Fixture f = open(true);
        onMain(() -> { fillLogin(f); invoke(f.view, "login"); return null; });
        check(f.fake.entered.await(remaining(4000), TimeUnit.MILLISECONDS), "FAKE_REQUEST_NOT_ENTERED");
        final char[][] pending = new char[1][];
        onMain(() -> {
            check(flag(f.view, "busy"), "REQUEST_BUSY");
            check(!f.phone.isEnabled() && !f.identity.isEnabled() && !f.code.isEnabled() && !f.agreement.isEnabled(), "BUSY_INPUTS_LOCKED");
            check(!f.submit.isEnabled() && !f.send.isEnabled(), "BUSY_ACTIONS_LOCKED");
            pending[0] = (char[]) field(f.view, "pendingSecret"); check(pending[0] != null, "REQUEST_SECRET_OWNED");
            invoke(f.view, "login"); check(f.fake.calls.get() == 1, "BUSY_REPEAT_LOGIN_IGNORED"); return null;
        });
        f.fake.release.countDown(); waitIdle(f);
        onMain(() -> {
            check(f.phone.isEnabled() && f.identity.isEnabled() && f.code.isEnabled() && f.agreement.isEnabled(), "COMPLETION_UNLOCKS_INPUTS");
            check(f.submit.isEnabled() && f.send.isEnabled(), "COMPLETION_UNLOCKS_ACTIONS");
            check(zero(pending[0]) && field(f.view, "pendingSecret") == null, "REQUEST_SECRET_ARRAY_CLEARED");
            int owner = integer(f.view, "generation"), revision = integer(f.view, "inputRevision");
            int challengeOwner = integer(f.view, "challengeGeneration") + 1;
            write(f.view, "challengeGeneration", challengeOwner); write(f.view, "challenged", true);
            invoke(f.view, "message", new Class<?>[]{String.class}, "本机合成验证状态");
            check(!f.phone.isEnabled() && !f.identity.isEnabled() && !f.code.isEnabled() && !f.send.isEnabled(), "CHALLENGE_INPUTS_LOCKED");
            f.identity.setText(OTHER_IDENTITY);
            check(!flag(f.view, "challenged") && f.identity.isEnabled() && f.code.length() == 0
                    && integer(f.view, "challengeGeneration") > challengeOwner, "PROGRAMMATIC_EDIT_INVALIDATES_CHALLENGE");
            Object consumed = invoke(f.view, "consumeChallenge", new Class<?>[]{int.class, int.class, int.class}, owner, challengeOwner, revision);
            check(Boolean.FALSE.equals(consumed) && f.fake.calls.get() == 1, "INVALIDATED_CHALLENGE_CALLBACK_CANNOT_SEND"); return null;
        });
        pending[0] = null; put(report, "busyInputsLocked", true); finishCase("BUSY_REQUEST_LOCKS_AND_RELEASES_FORM", f);
    }

    private void staleRevision() throws Exception {
        Fixture f = open(false);
        onMain(() -> {
            f.phone.setText(PHONE); f.identity.setText(IDENTITY); f.code.setText(CODE);
            int revision = integer(f.view, "inputRevision"), owner = integer(f.view, "generation");
            write(f.view, "busy", true); invoke(f.view, "message", new Class<?>[]{String.class}, "本机合成状态");
            // Programmatic edits intentionally bypass the normal disabled-widget protection.
            f.phone.setText(OTHER_PHONE); f.phone.setText(PHONE);
            check(integer(f.view, "inputRevision") > revision && f.code.length() == 0, "ABA_INPUT_CHANGE_HAS_NEW_REVISION");
            // An empty synthetic SUCCESS grant cannot persist any cookie even if this check regresses.
            invoke(f.view, "apply", new Class<?>[]{int.class, int.class, NativeCredentialAuth.Result.class, boolean.class},
                    owner, revision, result("SUCCESS"), false);
            check(!flag(f.view, "committed") && !flag(f.view, "busy"), "STALE_SUCCESS_NOT_COMMITTED_AND_BUSY_RELEASED");
            check(f.status.getText().toString().contains("输入已变化") && f.phone.isEnabled() && f.identity.isEnabled(), "STALE_SUCCESS_REPORTS_CHANGE_AND_UNLOCKS");
            check(f.fake.calls.get() == 0 && f.fake.exchangeCalls.get() == 0, "STALE_RESULT_NO_NETWORK_OR_EXCHANGE"); return null;
        });
        put(report, "staleResultIgnored", true); finishCase("ABA_INPUT_REVISION_REJECTS_LATE_SYNTHETIC_RESULT", f);
    }

    private void closeCleanup() throws Exception {
        Fixture f = open(false);
        onMain(() -> {
            fillLogin(f); int owner = integer(f.view, "generation"); f.view.close();
            check(flag(f.view, "closed") && flag(f.owner, "closed") && emptyInputs(f), "CLOSE_CLEARS_FORM_AND_OWNER");
            check(integer(f.view, "generation") > owner && field(f.view, "pendingSecret") == null, "CLOSE_INVALIDATES_GENERATION");
            String previous = f.status.getText().toString();
            invoke(f.view, "apply", new Class<?>[]{int.class, NativeCredentialAuth.Result.class, boolean.class}, owner, result("ACCOUNT_REQUIRED"), true);
            check(previous.contentEquals(f.status.getText()) && emptyInputs(f) && !flag(f.view, "committed"), "CLOSED_OWNER_IGNORES_LATE_RESULT"); return null;
        });
        finishCase("CLOSE_CLEARS_INPUTS_AND_IGNORES_LATE_RESULT", f);
    }

    private void backgroundCleanup() throws Exception {
        Fixture f = open(false);
        onMain(() -> {
            fillLogin(f); int owner = integer(f.view, "generation");
            invoke(f.view, "onWindowVisibilityChanged", new Class<?>[]{int.class}, View.INVISIBLE);
            check(flag(f.view, "paused") && flag(f.owner, "closed") && emptyInputs(f), "BACKGROUND_CLEARS_FORM_AND_OWNER");
            String previous = f.status.getText().toString();
            invoke(f.view, "apply", new Class<?>[]{int.class, NativeCredentialAuth.Result.class, boolean.class}, owner, result("ACCOUNT_REQUIRED"), true);
            check(previous.contentEquals(f.status.getText()) && emptyInputs(f) && !flag(f.view, "busy"), "BACKGROUND_IGNORES_LATE_RESULT"); return null;
        });
        put(report, "closeAndBackgroundCleanup", true); finishCase("BACKGROUND_CLEARS_INPUTS_AND_IGNORES_LATE_RESULT", f);
    }

    private Root replaceRoot() throws Exception {
        Root value = new Root(); value.host = (ViewGroup) activity.findViewById(android.R.id.content);
        value.original = value.host.getChildAt(0); value.params = value.original.getLayoutParams();
        invoke(activity, "cancelHomeNavigation"); invoke(field(activity, "pageReads"), "cancelAll");
        write(activity, "generation", integer(activity, "generation") + 1);
        value.body = new ScrollView(activity); value.body.setFillViewport(false);
        value.host.removeAllViews(); value.host.addView(value.body, new ViewGroup.LayoutParams(-1, -1)); return value;
    }

    private Fixture open(boolean block) throws Exception {
        Fixture f = onMain(() -> {
            Fixture value = new Fixture(); value.fake = new Fake(block);
            value.view = new NativeCredentialLoginView(activity, new NgaStore(getTargetContext()), NativeCredentialLoginView.Mode.SMS, () -> value.successes++);
            NativeCredentialAuth original = (NativeCredentialAuth) field(value.view, "auth"); original.close();
            value.owner = value.fake.create(); write(value.view, "auth", value.owner);
            value.phone = (EditText) field(value.view, "account"); value.identity = (EditText) field(value.view, "matchPhone");
            value.code = (EditText) field(value.view, "secret"); value.status = (TextView) field(value.view, "status");
            value.agreement = (CheckBox) field(value.view, "agreement"); value.submit = (Button) field(value.view, "submit"); value.send = (Button) field(value.view, "send");
            value.view.setPadding(24, 24, 24, 24); fixtures.add(value);
            saved.body.removeAllViews(); saved.body.addView(value.view, new ScrollView.LayoutParams(-1, -2)); return value;
        });
        long until = SystemClock.elapsedRealtime() + remaining(2500);
        while (!onMain(() -> f.view.isShown() && f.view.getWidth() > 0) && SystemClock.elapsedRealtime() < until) SystemClock.sleep(25);
        check(onMain(() -> f.view.isShown() && f.view.getWidth() > 0 && !flag(f.view, "closed") && !flag(f.view, "paused")), "FORM_NOT_ATTACHED");
        return f;
    }

    private void finishCase(String scenario, Fixture f) throws Exception {
        onMain(() -> { f.view.close(); saved.body.removeAllViews(); check(emptyInputs(f) && flag(f.owner, "closed") && f.successes == 0, "CASE_CLEANUP"); return null; });
        JSONObject one = new JSONObject(); put(one, "scenario", scenario); put(one, "passed", true); put(one, "fixtureCount", 1); cases.put(one);
    }
    private void fillLogin(Fixture f) { f.phone.setText(PHONE); f.identity.setText(IDENTITY); f.code.setText(CODE); f.agreement.setChecked(true); }
    private void apply(Fixture f, NativeCredentialAuth.Result value, boolean sending) throws Exception {
        invoke(f.view, "apply", new Class<?>[]{int.class, NativeCredentialAuth.Result.class, boolean.class}, integer(f.view, "generation"), value, sending);
    }
    private void waitIdle(Fixture f) throws Exception {
        long until = SystemClock.elapsedRealtime() + remaining(4500);
        while (onMain(() -> flag(f.view, "busy") || f.fake.calls.get() == 0 || field(f.view, "pendingSecret") != null) && SystemClock.elapsedRealtime() < until) SystemClock.sleep(20);
        check(onMain(() -> !flag(f.view, "busy") && f.fake.calls.get() > 0 && field(f.view, "pendingSecret") == null), "FAKE_COMPLETION_DEADLINE");
    }
    private static boolean emptyInputs(Fixture f) { return f.phone.length() == 0 && f.identity.length() == 0 && f.code.length() == 0; }
    private static boolean containsSynthetic(CharSequence text) { String value = text == null ? "" : text.toString(); return value.contains(PHONE) || value.contains(IDENTITY) || value.contains(CODE); }
    private static boolean zero(char[] values) { if (values == null) return false; for (char value : values) if (value != '\0') return false; return true; }
    private static NativeCredentialAuth.Result result(String state) throws Exception {
        Class<?> type = Class.forName("cn.ngapure.app.NativeCredentialAuth$State"); Object selected = null;
        for (Object candidate : type.getEnumConstants()) if (state.equals(candidate.toString())) selected = candidate;
        if (selected == null) throw new CheckFailure("AUTH_STATE_NOT_AVAILABLE");
        Constructor<?> factory = NativeCredentialAuth.Result.class.getDeclaredConstructor(type, String.class, String.class); factory.setAccessible(true);
        return (NativeCredentialAuth.Result) factory.newInstance(selected, "本机合成账号提示", "");
    }
    private static Object field(Object target, String name) throws Exception { Field value = target.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(target); }
    private static void write(Object target, String name, Object value) throws Exception { Field slot = target.getClass().getDeclaredField(name); slot.setAccessible(true); slot.set(target, value); }
    private static boolean flag(Object target, String name) throws Exception { return (Boolean) field(target, name); }
    private static int integer(Object target, String name) throws Exception { return (Integer) field(target, name); }
    private static Object invoke(Object target, String name) throws Exception { return invoke(target, name, new Class<?>[0]); }
    private static Object invoke(Object target, String name, Class<?>[] signature, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, signature); method.setAccessible(true); return method.invoke(target, args);
    }
    private void check(boolean condition, String failure) throws CheckFailure { assertions++; if (!condition) throw new CheckFailure(failure); }
    private long remaining(long maximum) throws CheckFailure { long left = deadline - SystemClock.elapsedRealtime(); if (left <= 0) throw new CheckFailure("PROBE_DEADLINE"); return Math.min(left, maximum); }
    private <T> T onMain(Callable<T> action) throws Exception { return onMainWithin(action, remaining(5000)); }
    private <T> T onMainCleanup(Callable<T> action) throws Exception { return onMainWithin(action, 5000); }
    private <T> T onMainWithin(Callable<T> action, long timeout) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); Object[] values = new Object[1]; Throwable[] errors = new Throwable[1];
        main.post(() -> { try { values[0] = action.call(); } catch (Throwable problem) { errors[0] = problem; } finally { latch.countDown(); } });
        if (!latch.await(timeout, TimeUnit.MILLISECONDS)) throw new CheckFailure("MAIN_THREAD_DEADLINE");
        if (errors[0] instanceof Exception) throw (Exception) errors[0];
        if (errors[0] != null) throw new CheckFailure("MAIN_THREAD_CHECK_FAILED");
        @SuppressWarnings("unchecked") T answer = (T) values[0]; return answer;
    }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value); } catch (Exception ignored) { /* Fixed enums, flags and counters only. */ } }

    private static final class Fixture {
        NativeCredentialLoginView view; NativeCredentialAuth owner; Fake fake;
        EditText phone, identity, code; TextView status; CheckBox agreement; Button submit, send; int successes;
    }
    private static final class Root { ViewGroup host; View original; ViewGroup.LayoutParams params; ScrollView body; }
    private static final class Fake {
        final AtomicInteger calls = new AtomicInteger(), loginCalls = new AtomicInteger(), smsSendCalls = new AtomicInteger(), exchangeCalls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final boolean block; volatile boolean phoneRoute;
        Fake(boolean block) { this.block = block; }
        NativeCredentialAuth create() throws Exception {
            Class<?> transport = Class.forName("cn.ngapure.app.NativeCredentialAuth$Transport");
            Class<?> exchange = Class.forName("cn.ngapure.app.NativeCredentialAuth$GrantExchange");
            Object localTransport = Proxy.newProxyInstance(transport.getClassLoader(), new Class<?>[]{transport}, (proxy, method, args) -> {
                if (!"request".equals(method.getName())) throw new IllegalStateException("LOCAL_TRANSPORT_METHOD");
                calls.incrementAndGet();
                @SuppressWarnings("unchecked") Map<String, String> fields = (Map<String, String>) args[2];
                String action = fields.get("__act");
                if ("smslogin".equals(action)) { loginCalls.incrementAndGet(); phoneRoute = PHONE.equals(fields.get("name")) && "phone".equals(fields.get("type")); }
                if ("smsloginsendcode".equals(action)) smsSendCalls.incrementAndGet();
                entered.countDown();
                if (block && !release.await(6, TimeUnit.SECONDS)) throw new IllegalStateException("LOCAL_TRANSPORT_DEADLINE");
                Class<?> response = Class.forName("cn.ngapure.app.NativeCredentialAuth$Response");
                Constructor<?> factory = response.getDeclaredConstructor(int.class, byte[].class, String.class, Map.class); factory.setAccessible(true);
                return factory.newInstance(200, "{\"code\":1,\"msg\":\"本机合成拒绝\"}".getBytes(StandardCharsets.UTF_8), "application/json", Collections.emptyMap());
            });
            Object localExchange = Proxy.newProxyInstance(exchange.getClassLoader(), new Class<?>[]{exchange}, (proxy, method, args) -> { exchangeCalls.incrementAndGet(); return result("ERROR"); });
            Constructor<NativeCredentialAuth> factory = NativeCredentialAuth.class.getDeclaredConstructor(String.class, transport, exchange); factory.setAccessible(true);
            return factory.newInstance("bbs.nga.cn", localTransport, localExchange);
        }
    }
    /** These values stay in memory for equality checks and are never serialized. */
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
