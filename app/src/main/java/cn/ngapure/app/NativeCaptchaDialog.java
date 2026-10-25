package cn.ngapure.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** The only embedded web surface: Tencent's official human-verification widget.
 * It receives no phone, password, forum cookie, account or QR authorization data.
 * Forum pages and all login forms remain native. No browser login is performed.
 */
public final class NativeCaptchaDialog implements AutoCloseable {
    public enum Failure { LOAD_FAILED, SDK_ERROR, INVALID_RESULT, TIMEOUT }
    public interface Callback {
        void complete(String ticket, String randstr);
        void cancelled();
        default void failed(Failure reason) { cancelled(); }
    }
    private static final String LOCAL_PAGE = "https://turing.captcha.qcloud.com/ngapure-local-captcha-v1.html";
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            "turing.captcha.qcloud.com", "ca.turing.captcha.qcloud.com",
            "turing.captcha.gtimg.com", "global.turing.captcha.gtimg.com",
            "cloudcache.tencentcs.com", "rce.tencentrio.com"));
    // The business ID and callback shape come from NGA's public account_copy.html.
    private static final String HTML = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; script-src 'unsafe-inline' https://turing.captcha.qcloud.com https://turing.captcha.gtimg.com https://cloudcache.tencentcs.com; frame-src https://turing.captcha.qcloud.com; connect-src https://turing.captcha.qcloud.com https://turing.captcha.gtimg.com https://rce.tencentrio.com; img-src data: https://turing.captcha.qcloud.com https://turing.captcha.gtimg.com; style-src 'unsafe-inline'\">"
            + "<style>body{margin:0;background:#fff;color:#253044;font:15px sans-serif;text-align:center}#hint{padding:24px 12px}</style></head>"
            + "<body><div id=hint>正在加载官方安全验证…</div><script>"
            + "var reported=false;function report(r){if(reported)return;reported=true;r=r||{};var v={ret:r.ret,ticket:r.ticket||'',randstr:r.randstr||''};if(typeof r.errorCode!=='undefined')v.errorCode=r.errorCode;NativeCaptcha.complete(JSON.stringify(v));}"
            + "function loadFailed(){report({ret:-1,errorCode:1001});}"
            + "function begin(){try{var c=new TencentCaptcha('191458585',report,{ready:function(){document.getElementById('hint').textContent='请完成人机验证';}});c.show();}catch(e){report({ret:-1,errorCode:1005});}}"
            + "</script><script src='https://turing.captcha.qcloud.com/TCaptcha.js' onload='begin()' onerror='loadFailed()'></script></body></html>";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Dialog dialog;
    private final WebView web;
    private final Callback callback;
    private final boolean previousCookies;
    private boolean finished;
    private volatile boolean localDocumentServed, localPageFinished;
    private volatile String mainFrameFailure = "NONE", resourceFailure = "NONE";
    private final AtomicInteger blockedRequests = new AtomicInteger();
    private final Runnable timeout = () -> finish(Result.failure(Failure.TIMEOUT));

    public NativeCaptchaDialog(Activity activity, Callback callback) {
        this(activity, callback, null);
    }
    /** An owned WebView may have its transport restricted before the initial load. */
    @SuppressLint("SetJavaScriptEnabled") // Only this isolated official CAPTCHA requires JS; no account data enters it.
    NativeCaptchaDialog(Activity activity, Callback callback, WebView ownedWeb) {
        if (callback == null) throw new IllegalArgumentException("缺少验证回调");
        if (ownedWeb != null && (ownedWeb.getContext() != activity || ownedWeb.getParent() != null)) throw new IllegalArgumentException("验证视图已被使用");
        this.callback = callback;
        web = ownedWeb == null ? new WebView(activity) : ownedWeb;
        WebSettings settings = web.getSettings(); settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE); settings.setGeolocationEnabled(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        web.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        CookieManager cookies = CookieManager.getInstance(); previousCookies = cookies.acceptCookie();
        cookies.setAcceptCookie(false); cookies.setAcceptThirdPartyCookies(web, false);
        web.addJavascriptInterface(new Bridge(), "NativeCaptcha");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request.isForMainFrame() ? !localDocument(request.getUrl().toString()) : !allowed(request.getUrl());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (request.isForMainFrame() && localDocument(url) && "GET".equals(request.getMethod())) {
                    localDocumentServed = true;
                    return new WebResourceResponse("text/html", "UTF-8", 200, "OK", java.util.Collections.singletonMap("Cache-Control", "no-store"),
                            new ByteArrayInputStream(HTML.getBytes(StandardCharsets.UTF_8)));
                }
                if (!request.isForMainFrame() && (allowed(request.getUrl()) || imageData(url))) return null;
                blockedRequests.updateAndGet(count -> Math.min(999, count + 1)); return blocked();
            }
            @Override public void onPageFinished(WebView view, String url) { if (localDocument(url)) localPageFinished = true; }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                String state = error.getErrorCode() == ERROR_HOST_LOOKUP ? "DNS" : error.getErrorCode() == ERROR_TIMEOUT ? "TIMEOUT"
                        : error.getErrorCode() == ERROR_FAILED_SSL_HANDSHAKE ? "TLS" : error.getErrorCode() == ERROR_CONNECT ? "CONNECT" : "NETWORK";
                if (request.isForMainFrame()) mainFrameFailure = state; else resourceFailure = state;
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                String state = response.getStatusCode() >= 500 ? "HTTP_5XX" : response.getStatusCode() >= 400 ? "HTTP_4XX" : "HTTP_OTHER";
                if (request.isForMainFrame()) mainFrameFailure = state; else resourceFailure = state;
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) { resourceFailure = "TLS"; handler.cancel(); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean userGesture, android.os.Message resultMsg) { return false; }
        });
        LinearLayout canvas = new LinearLayout(activity); canvas.setOrientation(LinearLayout.VERTICAL);
        canvas.addView(web, new LinearLayout.LayoutParams(-1, dp(activity, 390)));
        NgaStore store = new NgaStore(activity);
        dialog = new NativeGlassDialog.Builder(activity, NativeGlassDialog.darkMode(activity, store))
                .setTitle("安全验证 · 腾讯官方控件")
                .setMessage("完成后才会向 NGA 请求发送短信。本控件不接收手机号或密码。")
                .setView(canvas).setContentPaddingDp(0).setNegativeButton("取消", (ignored, which) -> close()).create();
        dialog.setOnCancelListener(ignored -> close());
        dialog.setOnDismissListener(ignored -> { if (!finished) finish(Result.cancelled()); });
        dialog.show();
        ((NativeGlassDialog) dialog).getButton(NativeGlassDialog.BUTTON_NEGATIVE).setOnClickListener(v -> close());
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
        // Serve only this fixed in-memory document. A synthetic data: main-frame
        // load must never pass through (or be rejected by) the network allowlist.
        web.loadUrl(LOCAL_PAGE);
        main.postDelayed(timeout, 120000);
    }
    static boolean allowed(Uri uri) {
        return uri != null && allowedUrl(uri.toString());
    }
    static boolean allowedUrl(String value) {
        try {
            java.net.URI uri = new java.net.URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443) && HOSTS.contains(uri.getHost());
        } catch (Exception ignored) { return false; }
    }
    static boolean localDocument(String value) { return LOCAL_PAGE.equals(value); }
    private static boolean imageData(String value) {
        return value.length() <= 256 * 1024 && (value.startsWith("data:image/png;") || value.startsWith("data:image/jpeg;")
                || value.startsWith("data:image/gif;") || value.startsWith("data:image/webp;"));
    }
    /** Fixed operational metadata only, never URLs, DOM or verification values. */
    public String safeDiagnostics() {
        try {
            JSONObject state = new JSONObject(); state.put("localDocumentServed", localDocumentServed); state.put("localPageFinished", localPageFinished);
            state.put("mainFrameFailure", mainFrameFailure); state.put("resourceFailure", resourceFailure); state.put("blockedRequests", blockedRequests.get()); return state.toString();
        } catch (Exception ignored) { return "{}"; }
    }
    private static WebResourceResponse blocked() { return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0])); }
    static final class Result {
        final String ticket, randstr;
        final Failure failure;
        private Result(String ticket, String randstr, Failure failure) { this.ticket = ticket; this.randstr = randstr; this.failure = failure; }
        static Result cancelled() { return new Result(null, null, null); }
        static Result failure(Failure reason) { return new Result(null, null, reason); }
    }
    /** Parse only the projected callback; SDK error tickets are never verification success. */
    static Result parseResult(String value) {
        if (value == null || value.length() > 12000) return Result.failure(Failure.INVALID_RESULT);
        try {
            JSONTokener reader = new JSONTokener(value); Object decoded = reader.nextValue();
            if (!(decoded instanceof JSONObject) || reader.nextClean() != 0) return Result.failure(Failure.INVALID_RESULT);
            JSONObject result = (JSONObject) decoded;
            if ((result.length() != 3 && !(result.length() == 4 && result.has("errorCode")))
                    || !(result.opt("ret") instanceof Number)) return Result.failure(Failure.INVALID_RESULT);
            double ret = ((Number) result.opt("ret")).doubleValue();
            if (!Double.isFinite(ret) || ret != Math.rint(ret)) return Result.failure(Failure.INVALID_RESULT);
            if (result.has("errorCode")) {
                Object code = result.opt("errorCode");
                if (!(code instanceof Number)) return Result.failure(Failure.INVALID_RESULT);
                double error = ((Number) code).doubleValue();
                if (!Double.isFinite(error) || error != Math.rint(error)) return Result.failure(Failure.INVALID_RESULT);
                if (error != 0) return Result.failure(error == 1001 ? Failure.LOAD_FAILED : Failure.SDK_ERROR);
            }
            if (ret == 2) return Result.cancelled();
            if (ret != 0) return Result.failure(Failure.SDK_ERROR);
            if (result.opt("ticket") instanceof String && result.opt("randstr") instanceof String) {
                String ticket = result.getString("ticket"), randstr = result.getString("randstr");
                if (ticket.startsWith("terror_") || ticket.startsWith("trerror_")) return Result.failure(Failure.SDK_ERROR);
                if (NativeCredentialAuth.validTicket(ticket, randstr)) return new Result(ticket, randstr, null);
            }
        } catch (Exception ignored) { /* Never retain or echo callback values. */ }
        return Result.failure(Failure.INVALID_RESULT);
    }
    public final class Bridge {
        @JavascriptInterface public void complete(String value) {
            Result result = parseResult(value);
            main.post(() -> finish(result));
        }
    }
    private void finish(Result result) {
        if (finished) return; finished = true; main.removeCallbacksAndMessages(null);
        // Dismiss first: a WebView cleanup exception must never strand the modal.
        dialog.setOnDismissListener(null); dialog.setOnCancelListener(null);
        try { if (dialog.isShowing()) dialog.dismiss(); } catch (RuntimeException ignored) { /* Activity may already be destroyed. */ }
        try { web.stopLoading(); } catch (RuntimeException ignored) { }
        try { web.removeJavascriptInterface("NativeCaptcha"); } catch (RuntimeException ignored) { }
        try { web.loadUrl("about:blank"); } catch (RuntimeException ignored) { }
        try { web.clearHistory(); web.clearCache(true); } catch (RuntimeException ignored) { }
        try { if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web); } catch (RuntimeException ignored) { }
        try { web.destroy(); } catch (RuntimeException ignored) { }
        try { CookieManager.getInstance().setAcceptCookie(previousCookies); } catch (RuntimeException ignored) { }
        if (result.failure != null) callback.failed(result.failure);
        else if (result.ticket == null) callback.cancelled(); else callback.complete(result.ticket, result.randstr);
    }
    @Override public void close() { if (Looper.myLooper() == Looper.getMainLooper()) finish(Result.cancelled()); else main.post(() -> finish(Result.cancelled())); }
    private static int dp(Activity activity, int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
