package cn.ngapure.verify;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import cn.ngapure.app.NativeCaptchaDialog;
import cn.ngapure.app.NativeCredentialLoginView;
import cn.ngapure.app.NativeLoginView;
import cn.ngapure.app.NgaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Offline synthetic lifecycle checks against the production fixed document and
 * production JavaScript bridge. The real SDK is never loaded, and these fixture
 * callbacks are not real verification or authentication. All WebView network
 * loads are disabled; only the exact fixed page and exact SDK URL receive local
 * responses. No SMS, credential, account endpoint or remote CAPTCHA is invoked.
 * Reports contain only fixed enums, structural booleans and bounded counters.
 */
public final class CaptchaLifecycleProbe extends Instrumentation {
    private static final String SDK = "https://turing.captcha.qcloud.com/TCaptcha.js";
    private static final String TICKET = "SyntheticFixtureTicket_2026";
    private static final String RANDOM = "SyntheticFixtureRandom";
    private static final String LATE = "{\"ret\":0,\"ticket\":\"" + TICKET + "\",\"randstr\":\"" + RANDOM + "\"}";
    private enum Scenario { SUCCESS, SDK_CANCEL, TERROR, TRERROR, ERROR_CODE, MALFORMED_TICKET,
        SCRIPT_LOAD_ERROR, EMPTY_SDK, CONSTRUCTOR_ERROR, DUPLICATE, MANUAL_CLOSE, DIALOG_DISMISS, TIMEOUT, CLEANUP_EXCEPTION, REOPEN }
    private enum Outcome { NONE, SUCCESS, CANCELLED, FAILED_LOAD, FAILED_SDK, FAILED_INVALID, FAILED_TIMEOUT }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final JSONArray cases = new JSONArray();
    private final JSONObject report = new JSONObject();
    private Activity activity;
    private Fixture active;
    private long deadline;
    private String localPage, originalHtml;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        deadline = SystemClock.elapsedRealtime() + 110000;
        boolean passed = false, cleanup = true;
        String failure = "NONE";
        try {
            NgaStore store = new NgaStore(getTargetContext());
            require(!store.autoCheckIn() || (store.loggedIn() && NgaStore.today().equals(store.lastCheckInDay())), "SIGN_IN_GUARD_FAILED");
            require(!NativeLoginView.hasPendingAuthorization(getTargetContext(), store.host()), "AUTHORIZATION_PENDING");
            put(report, "onOpenSignInGuardPassed", true);
            localPage = (String) field(null, NativeCaptchaDialog.class, "LOCAL_PAGE");
            originalHtml = (String) field(null, NativeCaptchaDialog.class, "HTML");
            require(originalHtml.contains("NativeCaptcha.complete") && originalHtml.contains("TCaptcha.js"), "PRODUCTION_HTML_UNAVAILABLE");
            activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName("cn.ngapure.app", "cn.ngapure.app.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            require(activity != null, "ACTIVITY_START_FAILED");
            for (Scenario scenario : Scenario.values()) run(scenario);
            ownerChecks();
            passed = true;
        } catch (ProbeFailure problem) { failure = problem.code; }
        catch (Exception | AssertionError problem) { failure = "LIFECYCLE_PROBE_UNAVAILABLE"; }
        finally {
            try { onMainCleanup(() -> { if (active != null && active.widget != null) active.widget.close(); return null; }); }
            catch (Exception problem) { cleanup = false; passed = false; failure = "FINAL_CLEANUP_FAILED"; }
            localPage = null; originalHtml = null;
        }
        put(report, "schema", 1); put(report, "purpose", "OFFLINE_SYNTHETIC_PRODUCTION_CAPTCHA_LIFECYCLE");
        put(report, "cases", cases); put(report, "scenarioCount", cases.length());
        put(report, "expectedScenarioCount", Scenario.values().length); put(report, "cleanupComplete", cleanup);
        boolean allClosed = cases.length() == Scenario.values().length, allOnce = allClosed, allLocal = allClosed;
        boolean successClosed = allClosed, failureClosed = allClosed, cleanupFaultPassed = false; int successes = 0, failures = 0;
        for (int i = 0; i < cases.length(); i++) {
            JSONObject one = cases.optJSONObject(i);
            boolean closed = one != null && one.optBoolean("finished") && one.optBoolean("dialogClosed") && one.optBoolean("webViewDetached");
            allClosed &= closed;
            allOnce &= one != null && one.optInt("callbackCount") == 1 && one.optBoolean("callbackAfterClosedAndDetached") && one.optBoolean("lateAndDuplicateCallbacksIgnored");
            allLocal &= one != null && one.optBoolean("networkLoadsDisabled") && one.optInt("localDocumentsServed") > 0 && one.optInt("localScriptsServed") > 0;
            String outcome = one == null ? "NONE" : one.optString("outcome");
            if ("SUCCESS".equals(outcome)) { successes++; successClosed &= closed; }
            if (outcome.startsWith("FAILED_")) { failures++; failureClosed &= closed; }
            if (one != null && Scenario.CLEANUP_EXCEPTION.name().equals(one.optString("scenario"))) {
                cleanupFaultPassed = one.optBoolean("cleanupExceptionInjected") && one.optBoolean("passed") && closed;
            }
        }
        put(report, "autodismissOnSuccess", successClosed && successes > 0);
        put(report, "autodismissOnFailure", failureClosed && failures > 0);
        put(report, "allDialogsClosedAndDetached", allClosed); put(report, "callbackExactlyOnce", allOnce);
        put(report, "cleanupExceptionCannotStrandDialog", cleanupFaultPassed);
        put(report, "productionFixedHtmlUsed", allLocal); put(report, "productionJavascriptBridgeUsed", allOnce);
        put(report, "sdkReplacedWithLocalFixture", allLocal); put(report, "externalWebViewNetworkLoadsDisabled", allLocal);
        put(report, "noSyntheticNetwork", allLocal); put(report, "noSmsOrAuthByFixtures", true);
        put(report, "realCaptchaCompleted", false); put(report, "realCredentialsSubmitted", false);
        put(report, "realSmsSent", false); put(report, "accountEndpointInvokedByProbe", false);
        put(report, "timeoutTriggeredWithoutWaiting", true); put(report, "passed", passed); put(report, "failure", passed ? "NONE" : failure);
        Bundle result = new Bundle(); result.putString("safe_captcha_lifecycle_report", report.toString());
        finish(Activity.RESULT_OK, result);
    }

    private void run(Scenario scenario) throws Exception {
        Fixture fixture = new Fixture(scenario); active = fixture;
        onMain(() -> {
            fixture.cookiesBefore = CookieManager.getInstance().acceptCookie();
            fixture.web = scenario == Scenario.CLEANUP_EXCEPTION ? new FaultWebView(activity) : new WebView(activity);
            fixture.web.getSettings().setBlockNetworkLoads(true);
            NativeCaptchaDialog.Callback callback = new NativeCaptchaDialog.Callback() {
                @Override public void complete(String ticket, String random) {
                    fixture.syntheticSuccessValues = TICKET.equals(ticket) && RANDOM.equals(random);
                    fixture.record(Outcome.SUCCESS);
                }
                @Override public void cancelled() { fixture.record(Outcome.CANCELLED); }
                @Override public void failed(NativeCaptchaDialog.Failure reason) {
                    fixture.record(reason == NativeCaptchaDialog.Failure.LOAD_FAILED ? Outcome.FAILED_LOAD
                            : reason == NativeCaptchaDialog.Failure.SDK_ERROR ? Outcome.FAILED_SDK
                            : reason == NativeCaptchaDialog.Failure.TIMEOUT ? Outcome.FAILED_TIMEOUT : Outcome.FAILED_INVALID);
                }
            };
            Constructor<NativeCaptchaDialog> constructor = NativeCaptchaDialog.class.getDeclaredConstructor(
                    Activity.class, NativeCaptchaDialog.Callback.class, WebView.class);
            constructor.setAccessible(true);
            fixture.widget = constructor.newInstance(activity, callback, fixture.web);
            require(fixture.web == field(fixture.widget, NativeCaptchaDialog.class, "web"), "OWNED_WEBVIEW_MISMATCH");
            fixture.dialog = (Dialog) field(fixture.widget, NativeCaptchaDialog.class, "dialog");
            // The network kill switch was set before constructor loadUrl. Reload
            // the production fixed document with an entirely local SDK client.
            fixture.web.getSettings().setBlockNetworkLoads(true);
            fixture.web.stopLoading();
            fixture.web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return !localPage.equals(request.getUrl().toString());
                }
                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    String url = request.getUrl().toString();
                    if ("GET".equals(request.getMethod()) && request.isForMainFrame() && localPage.equals(url)) {
                        fixture.documents.incrementAndGet(); return response("text/html", 200, originalHtml);
                    }
                    if ("GET".equals(request.getMethod()) && !request.isForMainFrame() && SDK.equals(url)) {
                        fixture.scripts.incrementAndGet();
                        return scenario == Scenario.SCRIPT_LOAD_ERROR ? response("text/plain", 403, "")
                                : response("application/javascript", 200, sdk(scenario));
                    }
                    fixture.blocked.incrementAndGet(); return response("text/plain", 403, "");
                }
            });
            fixture.opened = fixture.dialog.isShowing() && fixture.web.getParent() != null;
            fixture.networkDisabled = fixture.web.getSettings().getBlockNetworkLoads();
            fixture.web.loadUrl(localPage); return null;
        });
        require(fixture.opened && fixture.networkDisabled, "FIXTURE_OPEN_FAILED");
        if (scenario == Scenario.MANUAL_CLOSE || scenario == Scenario.DIALOG_DISMISS || scenario == Scenario.TIMEOUT || scenario == Scenario.CLEANUP_EXCEPTION) {
            waitReady(fixture);
            onMain(() -> {
                if (scenario == Scenario.MANUAL_CLOSE) {
                    Button button = findCancel((ViewGroup) fixture.dialog.getWindow().getDecorView());
                    require(button != null && button.performClick(), "CANCEL_CONTROL_UNAVAILABLE");
                    fixture.widget.new Bridge().complete(LATE); // A result racing the cancel tap must stay cancelled.
                } else if (scenario == Scenario.DIALOG_DISMISS) fixture.dialog.dismiss();
                else if (scenario == Scenario.TIMEOUT) ((Runnable) field(fixture.widget, NativeCaptchaDialog.class, "timeout")).run();
                else {
                    ((FaultWebView) fixture.web).failStop = true;
                    fixture.web.evaluateJavascript("window.__fixtureRelease()", null);
                }
                return null;
            });
        }
        require(fixture.callback.await(remaining(15000), TimeUnit.MILLISECONDS), "CALLBACK_DEADLINE");
        // Exercise a second genuine Bridge invocation after cleanup. This has no
        // network effect and must not produce another callback or reopen anything.
        onMain(() -> { fixture.widget.new Bridge().complete(LATE); fixture.widget.close(); return null; });
        onMain(() -> null); SystemClock.sleep(Math.min(100, remaining(100))); onMain(() -> null);
        boolean finished = onMain(() -> Boolean.TRUE.equals(field(fixture.widget, NativeCaptchaDialog.class, "finished")));
        boolean closed = onMain(() -> !fixture.dialog.isShowing());
        boolean detached = onMain(() -> fixture.web.getParent() == null);
        boolean cookies = onMain(() -> CookieManager.getInstance().acceptCookie() == fixture.cookiesBefore);
        boolean injected = scenario == Scenario.CLEANUP_EXCEPTION && ((FaultWebView) fixture.web).stopFailures.get() > 0;
        Outcome expected = expected(scenario);
        boolean success = fixture.count.get() == 1 && fixture.outcome == expected && fixture.callbackAfterCleanup
                && finished && closed && detached && cookies && fixture.documents.get() > 0 && fixture.scripts.get() > 0
                && (scenario != Scenario.CLEANUP_EXCEPTION || injected)
                && (expected != Outcome.SUCCESS || fixture.syntheticSuccessValues);
        JSONObject one = new JSONObject(); put(one, "scenario", scenario.name()); put(one, "expected", expected.name());
        put(one, "outcome", fixture.outcome.name()); put(one, "callbackCount", Math.min(999, fixture.count.get()));
        put(one, "callbackAfterClosedAndDetached", fixture.callbackAfterCleanup); put(one, "finished", finished);
        put(one, "dialogClosed", closed); put(one, "webViewDetached", detached); put(one, "cookiePolicyRestored", cookies);
        put(one, "localDocumentsServed", Math.min(999, fixture.documents.get())); put(one, "localScriptsServed", Math.min(999, fixture.scripts.get()));
        put(one, "unexpectedRequestsBlocked", Math.min(999, fixture.blocked.get())); put(one, "networkLoadsDisabled", fixture.networkDisabled);
        put(one, "cleanupExceptionInjected", injected);
        put(one, "lateAndDuplicateCallbacksIgnored", fixture.count.get() == 1); put(one, "passed", success); cases.put(one);
        require(success, "SCENARIO_ASSERTION_FAILED"); active = null;
    }

    private void waitReady(Fixture fixture) throws Exception {
        long end = SystemClock.elapsedRealtime() + remaining(12000);
        while (SystemClock.elapsedRealtime() < end) {
            CountDownLatch latch = new CountDownLatch(1); boolean[] ready = {false};
            onMain(() -> { fixture.web.evaluateJavascript("window.__fixtureReady===true", value -> { ready[0] = "true".equals(value); latch.countDown(); }); return null; });
            require(latch.await(remaining(2000), TimeUnit.MILLISECONDS), "SCRIPT_READY_DEADLINE");
            if (ready[0]) return;
            SystemClock.sleep(Math.min(80, remaining(80)));
        }
        throw new ProbeFailure("SCRIPT_NOT_READY");
    }

    private void ownerChecks() throws Exception {
        onMain(() -> {
            // SMS mode construction does not load an image, send SMS, authenticate
            // or attach a form. Only the private consume-once state guard is used.
            NativeCredentialLoginView form = new NativeCredentialLoginView(activity, new NgaStore(getTargetContext()),
                    NativeCredentialLoginView.Mode.SMS, null);
            try {
                write(form, "paused", false); write(form, "generation", 3); write(form, "inputRevision", 5); write(form, "challengeGeneration", 4); write(form, "challenged", true);
                Method consume = form.getClass().getDeclaredMethod("consumeChallenge", int.class, int.class, int.class); consume.setAccessible(true);
                boolean oldOwner = Boolean.FALSE.equals(consume.invoke(form, 2, 4, 5)) && Boolean.TRUE.equals(field(form, form.getClass(), "challenged"));
                boolean oldChallenge = Boolean.FALSE.equals(consume.invoke(form, 3, 3, 5)) && Boolean.TRUE.equals(field(form, form.getClass(), "challenged"))
                        && Boolean.FALSE.equals(consume.invoke(form, 3, 4, 4)) && Boolean.TRUE.equals(field(form, form.getClass(), "challenged"));
                boolean once = Boolean.TRUE.equals(consume.invoke(form, 3, 4, 5)) && Boolean.FALSE.equals(field(form, form.getClass(), "challenged"))
                        && Boolean.FALSE.equals(consume.invoke(form, 3, 4, 5));
                write(form, "challenged", true); form.close();
                boolean closed = Boolean.FALSE.equals(consume.invoke(form, 3, 4, 5));
                put(report, "staleFormOwnerRejected", oldOwner); put(report, "staleChallengeOwnerRejected", oldChallenge);
                put(report, "formChallengeConsumedOnce", once); put(report, "closedFormCallbackRejected", closed);
                put(report, "staleChallengeIgnored", oldOwner && oldChallenge && once && closed);
                require(oldOwner && oldChallenge && once && closed, "FORM_OWNER_GUARD_FAILED");
            } finally { form.close(); }
            return null;
        });
    }

    private static String sdk(Scenario scenario) {
        if (scenario == Scenario.EMPTY_SDK) return "";
        if (scenario == Scenario.CONSTRUCTOR_ERROR) return "window.TencentCaptcha=function(){throw new Error('SYNTHETIC');};";
        String result = "{ret:0,ticket:'" + TICKET + "',randstr:'" + RANDOM + "'}";
        if (scenario == Scenario.SDK_CANCEL) result = "{ret:2,ticket:'',randstr:''}";
        else if (scenario == Scenario.TERROR) result = "{ret:0,ticket:'terror_synthetic',randstr:'Synthetic'}";
        else if (scenario == Scenario.TRERROR) result = "{ret:0,ticket:'trerror_synthetic',randstr:'Synthetic'}";
        else if (scenario == Scenario.ERROR_CODE) result = "{ret:0,ticket:'" + TICKET + "',randstr:'" + RANDOM + "',errorCode:1004}";
        else if (scenario == Scenario.MALFORMED_TICKET) result = "{ret:0,ticket:'invalid ticket',randstr:'Synthetic'}";
        boolean waits = scenario == Scenario.MANUAL_CLOSE || scenario == Scenario.DIALOG_DISMISS || scenario == Scenario.TIMEOUT || scenario == Scenario.CLEANUP_EXCEPTION;
        return "window.__fixtureReady=false;window.TencentCaptcha=function(id,callback,options){this.show=function(){"
                + "window.__fixtureReady=true;if(options&&options.ready)options.ready();"
                + "window.__fixtureRelease=function(){callback(" + result + ");};"
                + (waits ? "" : "setTimeout(function(){callback(" + result + ");"
                    + (scenario == Scenario.DUPLICATE ? "callback(" + result + ");callback({ret:2});" : "") + "},120);") + "};};";
    }

    private static Outcome expected(Scenario scenario) {
        switch (scenario) {
            case SUCCESS: case DUPLICATE: case CLEANUP_EXCEPTION: case REOPEN: return Outcome.SUCCESS;
            case SDK_CANCEL: case MANUAL_CLOSE: case DIALOG_DISMISS: return Outcome.CANCELLED;
            case SCRIPT_LOAD_ERROR: return Outcome.FAILED_LOAD;
            case MALFORMED_TICKET: return Outcome.FAILED_INVALID;
            case TIMEOUT: return Outcome.FAILED_TIMEOUT;
            default: return Outcome.FAILED_SDK;
        }
    }

    private static WebResourceResponse response(String mime, int status, String body) {
        return new WebResourceResponse(mime, "UTF-8", status, status == 200 ? "OK" : "Blocked",
                Collections.singletonMap("Cache-Control", "no-store"), new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
    private static Button findCancel(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof Button && "取消".contentEquals(((Button) child).getText())) return (Button) child;
            if (child instanceof ViewGroup) { Button found = findCancel((ViewGroup) child); if (found != null) return found; }
        }
        return null;
    }
    private static Object field(Object owner, Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void write(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private long remaining(long maximum) throws ProbeFailure {
        long left = deadline - SystemClock.elapsedRealtime(); if (left <= 0) throw new ProbeFailure("PROBE_DEADLINE"); return Math.min(left, maximum);
    }
    private <T> T onMain(Callable<T> action) throws Exception { return onMainWithin(action, remaining(5000)); }
    private <T> T onMainCleanup(Callable<T> action) throws Exception { return onMainWithin(action, 5000); }
    private <T> T onMainWithin(Callable<T> action, long maximum) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); Object[] value = new Object[1]; Throwable[] error = new Throwable[1];
        main.post(() -> { try { value[0] = action.call(); } catch (Throwable problem) { error[0] = problem; } finally { latch.countDown(); } });
        if (!latch.await(maximum, TimeUnit.MILLISECONDS)) throw new ProbeFailure("MAIN_THREAD_DEADLINE");
        if (error[0] instanceof Exception) throw (Exception) error[0];
        if (error[0] != null) throw new ProbeFailure("MAIN_THREAD_CHECK_FAILED");
        @SuppressWarnings("unchecked") T result = (T) value[0]; return result;
    }
    private static void require(boolean value, String failure) throws ProbeFailure { if (!value) throw new ProbeFailure(failure); }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value); } catch (Exception ignored) { /* Fixed safe projection. */ } }

    private static final class Fixture {
        final Scenario scenario;
        final CountDownLatch callback = new CountDownLatch(1);
        final AtomicInteger count = new AtomicInteger(), documents = new AtomicInteger(), scripts = new AtomicInteger(), blocked = new AtomicInteger();
        NativeCaptchaDialog widget; Dialog dialog; WebView web;
        volatile Outcome outcome = Outcome.NONE;
        boolean opened, networkDisabled, cookiesBefore;
        volatile boolean callbackAfterCleanup, syntheticSuccessValues;
        Fixture(Scenario scenario) { this.scenario = scenario; }
        void record(Outcome value) {
            outcome = value; count.incrementAndGet();
            try { callbackAfterCleanup = !dialog.isShowing() && web.getParent() == null
                    && Boolean.TRUE.equals(field(widget, NativeCaptchaDialog.class, "finished")); }
            catch (Exception problem) { callbackAfterCleanup = false; }
            finally { callback.countDown(); }
        }
    }
    private static final class FaultWebView extends WebView {
        volatile boolean failStop;
        final AtomicInteger stopFailures = new AtomicInteger();
        FaultWebView(Context context) { super(context); }
        @Override public void stopLoading() {
            if (failStop) { stopFailures.incrementAndGet(); throw new IllegalStateException("SYNTHETIC_CLEANUP_FAILURE"); }
            super.stopLoading();
        }
    }
    private static final class ProbeFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final String code; ProbeFailure(String code) { this.code = code; }
    }
}
