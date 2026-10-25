package cn.ngapure.verify;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.WebView;
import cn.ngapure.app.NativeCaptchaDialog;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Anonymous official widget loading only. Never invokes SMS or authentication.
 * Reads only this widget's local fixed #hint states and structural booleans/counts.
 * No DOM, resource URL, ticket, random value, cookie, account or callback body leaves it.
 */
public final class CaptchaProbe extends Instrumentation {
    private static final long LOAD_MILLIS = 15000;
    private static final String SCRIPT = "(function(){var h=document.getElementById('hint');var t=h?h.textContent:'';"
            + "var m={'正在加载官方安全验证…':'LOADING','请完成人机验证':'READY','安全验证暂时不可用，请关闭后重试':'UNAVAILABLE','安全验证加载失败，请检查网络后重试':'LOAD_FAILED'};"
            + "return JSON.stringify({hintState:m[t]||(h?'OTHER':'MISSING'),"
            + "tencentCaptchaType:typeof window.TencentCaptcha==='function'?'FUNCTION':(typeof window.TencentCaptcha==='undefined'?'UNDEFINED':'OTHER'),"
            + "documentState:document.readyState==='complete'?'COMPLETE':(document.readyState==='interactive'?'INTERACTIVE':(document.readyState==='loading'?'LOADING':'OTHER')),"
            + "iframeCount:Math.min(32,document.querySelectorAll('iframe').length),"
            + "knownUiNode:!!document.querySelector('#tcaptcha_transform_dy,#tcaptcha_transform,#tcaptcha_popup,#tcaptcha_container')});})()";
    private final AtomicBoolean completed = new AtomicBoolean(), cancelled = new AtomicBoolean();
    private volatile String widgetFailure = "NONE";
    private NativeCaptchaDialog widget;
    private WebView web;
    private Dialog dialog;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        JSONObject report = new JSONObject(); boolean activityStarted = false, shown = false, sampled = false;
        boolean closed = false, detached = false, destroyed = false; String error = "NONE";
        JSONObject sample = emptySample(), widgetLoad = emptyWidgetState();
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN).setClassName("cn.ngapure.app", "cn.ngapure.app.MainActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            Activity activity = startActivitySync(intent); activityStarted = activity != null;
            if (activity == null) throw new ProbeFailure("ACTIVITY_START_FAILED");
            final AtomicBoolean opened = new AtomicBoolean();
            runOnMainSync(() -> {
                try {
                    widget = new NativeCaptchaDialog(activity, new NativeCaptchaDialog.Callback() {
                        @Override public void complete(String ticket, String randstr) { completed.set(true); }
                        @Override public void cancelled() { cancelled.set(true); }
                        @Override public void failed(NativeCaptchaDialog.Failure reason) { widgetFailure = reason.name(); }
                    });
                    web = (WebView) field(widget, "web"); dialog = (Dialog) field(widget, "dialog");
                    opened.set(dialog != null && dialog.isShowing() && web != null);
                } catch (Exception ignored) { /* Fixed error enum is emitted below. */ }
            });
            shown = opened.get(); if (!shown) throw new ProbeFailure("DIALOG_OPEN_FAILED");
            SystemClock.sleep(LOAD_MILLIS);
            CountDownLatch available = new CountDownLatch(1); final JSONObject[] projection = {null};
            runOnMainSync(() -> {
                if (completed.get() || cancelled.get() || !"NONE".equals(widgetFailure) || web == null) { available.countDown(); return; }
                try {
                    web.evaluateJavascript(SCRIPT, value -> {
                        try { projection[0] = project(value); } catch (Exception ignored) { /* Never retain unprojected text. */ }
                        finally { available.countDown(); }
                    });
                } catch (RuntimeException ignored) { available.countDown(); }
            });
            if (!available.await(8, TimeUnit.SECONDS)) error = "DOM_SAMPLE_TIMEOUT";
            else if (projection[0] == null) error = "NONE".equals(widgetFailure) ? "DOM_SAMPLE_UNAVAILABLE" : "WIDGET_" + widgetFailure;
            else { sample = projection[0]; sampled = true; }
            final JSONObject[] loadProjection = {null};
            runOnMainSync(() -> { try { loadProjection[0] = projectWidgetState(widget.safeDiagnostics()); } catch (Exception ignored) { /* No raw diagnostics output. */ } });
            if (loadProjection[0] != null) widgetLoad = loadProjection[0];
        } catch (ProbeFailure failure) { error = failure.code; }
        catch (Exception ignored) { error = activityStarted ? "PROBE_UNAVAILABLE" : "ACTIVITY_START_FAILED"; }
        finally {
            final boolean[] cleanup = {false, false, false};
            try {
                runOnMainSync(() -> {
                    try {
                        if (widget != null) widget.close();
                        cleanup[0] = widget != null && Boolean.TRUE.equals(field(widget, "finished")) && (dialog == null || !dialog.isShowing());
                        cleanup[1] = web != null && web.getParent() == null;
                        // NativeCaptchaDialog invokes either callback only after
                        // web.destroy() has returned; this uses that explicit close
                        // contract, not hidden Chromium state or an account read.
                        cleanup[2] = cleanup[0] && cleanup[1] && (cancelled.get() || completed.get() || !"NONE".equals(widgetFailure));
                    } catch (Exception ignored) { /* Report boolean failure only. */ }
                });
            } catch (RuntimeException ignored) { /* Report fixed cleanup status only. */ }
            closed = cleanup[0]; detached = cleanup[1]; destroyed = cleanup[2];
            if (widget != null && !destroyed && "NONE".equals(error)) error = "CLEANUP_FAILED";
        }
        try {
            report.put("schema", 1); report.put("purpose", "ANONYMOUS_WIDGET_LOAD_ONLY"); report.put("waitMillis", LOAD_MILLIS);
            report.put("activityStarted", activityStarted); report.put("dialogShown", shown); report.put("sampleAvailable", sampled);
            report.put("sample", sample); report.put("widgetLoad", widgetLoad); report.put("completeCallback", completed.get()); report.put("cancelledCallback", cancelled.get());
            report.put("failureCallback", !"NONE".equals(widgetFailure)); report.put("failureType", widgetFailure);
            report.put("dialogClosed", closed); report.put("webViewDetached", detached); report.put("webViewDestroyed", destroyed);
            report.put("destroyEvidence", "CLOSE_SEQUENCE_CALLBACK_AFTER_DESTROY"); report.put("smsOrAuthInvokedByProbe", false); report.put("errorType", error);
        } catch (Exception ignored) { throw new IllegalStateException("Safe CAPTCHA probe report unavailable"); }
        Bundle result = new Bundle(); result.putString("safe_captcha_report", report.toString()); finish(Activity.RESULT_OK, result);
    }
    private static JSONObject project(String encoded) throws Exception {
        Object scalar = new JSONTokener(encoded).nextValue(); if (!(scalar instanceof String) || ((String) scalar).length() > 512) return null;
        JSONTokener parser = new JSONTokener((String) scalar); Object decoded = parser.nextValue();
        if (!(decoded instanceof JSONObject) || parser.nextClean() != 0) return null;
        JSONObject input = (JSONObject) decoded;
        String hint = input.optString("hintState"), type = input.optString("tencentCaptchaType"), document = input.optString("documentState");
        if (!java.util.Arrays.asList("LOADING", "READY", "UNAVAILABLE", "LOAD_FAILED", "OTHER", "MISSING").contains(hint)
                || !java.util.Arrays.asList("FUNCTION", "UNDEFINED", "OTHER").contains(type)
                || !java.util.Arrays.asList("COMPLETE", "INTERACTIVE", "LOADING", "OTHER").contains(document)
                || !(input.opt("iframeCount") instanceof Number) || !(input.opt("knownUiNode") instanceof Boolean)) return null;
        double count = ((Number) input.opt("iframeCount")).doubleValue();
        if (!Double.isFinite(count) || count != Math.rint(count) || count < 0 || count > 32) return null;
        JSONObject safe = new JSONObject(); safe.put("hintState", hint); safe.put("tencentCaptchaType", type);
        safe.put("documentState", document);
        safe.put("iframeCount", (int) count); safe.put("knownUiNode", input.getBoolean("knownUiNode")); return safe;
    }
    private static JSONObject emptySample() {
        JSONObject result = new JSONObject();
        try { result.put("hintState", "NOT_SAMPLED"); result.put("tencentCaptchaType", "NOT_SAMPLED"); result.put("documentState", "NOT_SAMPLED"); result.put("iframeCount", 0); result.put("knownUiNode", false); }
        catch (Exception ignored) { throw new IllegalStateException("Safe CAPTCHA defaults unavailable"); }
        return result;
    }
    private static JSONObject projectWidgetState(String encoded) throws Exception {
        JSONObject input = new JSONObject(encoded);
        if (!(input.opt("localDocumentServed") instanceof Boolean) || !(input.opt("localPageFinished") instanceof Boolean)
                || !(input.opt("blockedRequests") instanceof Number)) return null;
        java.util.List<String> failures = java.util.Arrays.asList("NONE", "DNS", "TIMEOUT", "TLS", "CONNECT", "NETWORK", "HTTP_4XX", "HTTP_5XX", "HTTP_OTHER");
        String main = input.optString("mainFrameFailure"), resource = input.optString("resourceFailure");
        double count = ((Number) input.opt("blockedRequests")).doubleValue();
        if (!failures.contains(main) || !failures.contains(resource) || !Double.isFinite(count) || count != Math.rint(count) || count < 0 || count > 999) return null;
        JSONObject safe = new JSONObject(); safe.put("localDocumentServed", input.getBoolean("localDocumentServed")); safe.put("localPageFinished", input.getBoolean("localPageFinished"));
        safe.put("mainFrameFailure", main); safe.put("resourceFailure", resource); safe.put("blockedRequests", (int) count); return safe;
    }
    private static JSONObject emptyWidgetState() {
        JSONObject safe = new JSONObject();
        try { safe.put("localDocumentServed", false); safe.put("localPageFinished", false); safe.put("mainFrameFailure", "NOT_SAMPLED"); safe.put("resourceFailure", "NOT_SAMPLED"); safe.put("blockedRequests", 0); }
        catch (Exception ignored) { throw new IllegalStateException("Safe widget load defaults unavailable"); }
        return safe;
    }
    private static Object field(Object owner, String name) throws Exception { Field value = owner.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(owner); }
    private static final class ProbeFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final String code; ProbeFailure(String code) { this.code = code; }
    }
}
