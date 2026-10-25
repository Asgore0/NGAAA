package cn.ngapure.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Native account/SMS forms. Only the separately bounded official CAPTCHA is H5. */
public final class NativeCredentialLoginView extends LinearLayout implements AutoCloseable {
    public enum Mode { PASSWORD, SMS }
    private final Activity activity;
    private final NgaStore store;
    private final Runnable onSuccess;
    private final Mode mode;
    private final String host;
    private NativeCredentialAuth auth;
    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int ink, muted, accent, surface, border;
    private final AppPalette palette;
    private final EditText account, secret, imageAnswer, matchPhone;
    private final ImageView captcha;
    private final TextView status;
    private final Button submit, send;
    private final CheckBox agreement;
    private NativeCaptchaDialog challenge;
    private volatile boolean closed, busy, challenged, committed, paused;
    private volatile int generation;
    private volatile int inputRevision;
    private int challengeGeneration;
    private volatile char[] pendingSecret;
    private long resendAfter;
    private final Runnable resendTick = this::updateSend;

    public NativeCredentialLoginView(Activity activity, NgaStore store, Mode mode, Runnable onSuccess) {
        super(activity); this.activity = activity; this.store = store; this.mode = mode;
        this.onSuccess = onSuccess == null ? () -> { } : onSuccess; host = store.host(); auth = new NativeCredentialAuth(host);
        boolean night = "dark".equals(store.theme()) || ("system".equals(store.theme())
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        palette = AppPalette.of(night);
        ink = palette.ink; muted = palette.muted;
        accent = palette.accent; surface = palette.surface; border = palette.border;
        setOrientation(VERTICAL); setSaveEnabled(false); setSaveFromParentEnabled(false);
        setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        TextView title = text(mode == Mode.PASSWORD ? "账号密码登录" : "手机验证码登录", 24, ink); title.setTypeface(null, Typeface.BOLD);
        addView(title, margins(-1, -2, 0, 20));
        TextView note = text(mode == Mode.PASSWORD ? "用户名、邮箱、用户 ID 或绑定手机号均可登录。" : "部分账号还需填写 NGA 用户名或 UID，以指定手机号绑定的账号。点击发送并完成安全验证后才会发送短信。", 14, muted);
        note.setLineSpacing(dp(3), 1); addView(note, margins(-1, -2, 0, 16));
        account = input(mode == Mode.PASSWORD ? "用户名 / 邮箱 / ID / 手机号" : "绑定手机号（国际区号请分隔填写）", mode == Mode.PASSWORD ? InputType.TYPE_CLASS_TEXT : InputType.TYPE_CLASS_PHONE);
        account.setContentDescription(mode == Mode.PASSWORD ? "登录账号" : "绑定手机号");
        addView(account, margins(-1, dp(54), 0, 12));
        matchPhone = input(mode == Mode.PASSWORD ? "NGA 要求的绑定手机号" : "NGA 用户名或 UID（部分账号必填）", mode == Mode.PASSWORD ? InputType.TYPE_CLASS_PHONE : InputType.TYPE_CLASS_TEXT);
        matchPhone.setContentDescription(mode == Mode.PASSWORD ? "补充绑定手机号" : "NGA 用户名或 UID");
        addView(matchPhone, margins(-1, dp(54), 0, 12));
        if (mode == Mode.PASSWORD) matchPhone.setVisibility(GONE);
        secret = input(mode == Mode.PASSWORD ? "密码" : "短信验证码", mode == Mode.PASSWORD ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        secret.setContentDescription(mode == Mode.PASSWORD ? "登录密码" : "短信验证码");
        secret.setImeOptions(EditorInfo.IME_ACTION_DONE);
        addView(secret, margins(-1, dp(54), 0, 12));
        secret.setOnEditorActionListener((view, action, event) -> { if (action == EditorInfo.IME_ACTION_DONE) { login(); return true; } return false; });
        captcha = new ImageView(activity); captcha.setContentDescription("NGA 图形验证码，点击刷新"); captcha.setAdjustViewBounds(true); captcha.setScaleType(ImageView.ScaleType.FIT_CENTER);
        captcha.setBackground(shape(Color.WHITE)); captcha.setOnClickListener(view -> reloadImage());
        addView(captcha, margins(-1, dp(88), 0, 8));
        imageAnswer = input("图片验证码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        addView(imageAnswer, margins(-1, dp(54), 0, 12));
        send = button("发送验证码", false); send.setMinHeight(dp(50)); addView(send, margins(-1, -2, 0, 12));
        send.setOnClickListener(view -> sendSms());
        if (mode == Mode.SMS) { captcha.setVisibility(GONE); imageAnswer.setVisibility(GONE); }
        else send.setVisibility(GONE);
        agreement = new CheckBox(activity); agreement.setText("我已阅读并同意 NGA 用户协议与隐私协议"); agreement.setTextColor(ink); agreement.setTextSize(13); agreement.setMinHeight(dp(48));
        agreement.setButtonTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[0]}, new int[]{accent, muted}));
        agreement.setSaveEnabled(false); addView(agreement, margins(-1, -2, 0, 4));
        NativeAdaptiveActionRow links = new NativeAdaptiveActionRow(activity, 0, dp(48));
        links.addAction(legal("用户协议", "agreement"), 1);
        links.addAction(legal("隐私协议", "privacy"), 1); addView(links);
        submit = button("登录", true); submit.setMinHeight(dp(52)); addView(submit, margins(-1, -2, 0, 16)); submit.setOnClickListener(view -> login());
        status = text("", 14, muted); status.setLineSpacing(dp(3), 1); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(status, margins(-1, -2, 0, 12));
        TextView privacy = text("密码和验证码仅在本次页面内存中使用，离开或退到后台后清除；原账号在新登录验证成功前保持不变。", 12, muted);
        privacy.setLineSpacing(dp(3), 1); addView(privacy);
        if (mode == Mode.SMS) {
            TextWatcher identityChanges = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence value, int start, int before, int count) { }
                @Override public void afterTextChanged(Editable value) { identityChanged(); }
            };
            account.addTextChangedListener(identityChanges); matchPhone.addTextChangedListener(identityChanges);
        }
        refreshControls();
        if (mode == Mode.PASSWORD) reloadImage();
    }
    private void login() {
        if (!available() || busy || challenged) return;
        if (!agreement.isChecked()) { message("请先阅读并同意 NGA 用户协议和隐私协议"); return; }
        String name = account.getText().toString();
        if (name.trim().isEmpty() || secret.length() == 0) { message(mode == Mode.PASSWORD ? "请输入账号和密码" : "请输入手机号和短信验证码"); return; }
        char[] value = new char[secret.length()]; TextUtils.getChars(secret.getText(), 0, value.length, value, 0);
        String image = imageAnswer.getText().toString(), phone = matchPhone.getText().toString();
        int owner = generation, revision = inputRevision; NativeCredentialAuth requestOwner = auth; pendingSecret = value;
        busy = true; message("正在向 NGA 验证登录…");
        execute(() -> {
            try {
                if (!current(owner)) return;
                NativeCredentialAuth.Result result = mode == Mode.PASSWORD ? requestOwner.passwordLogin(name, value, image, phone) : requestOwner.smsLogin(name, value);
                main.post(() -> apply(owner, revision, result, false));
            } catch (IOException error) { failure(owner, revision, readable(error)); }
            catch (RuntimeException error) { failure(owner, revision, "登录检查未完成，请稍后重试"); }
            finally { Arrays.fill(value, '\0'); if (pendingSecret == value) pendingSecret = null; }
        });
    }
    private void apply(int owner, NativeCredentialAuth.Result result) { apply(owner, result, false); }
    private void apply(int owner, NativeCredentialAuth.Result result, boolean smsSend) {
        apply(owner, inputRevision, result, smsSend);
    }
    private void apply(int owner, int revision, NativeCredentialAuth.Result result, boolean smsSend) {
        if (!current(owner)) return; busy = false;
        if (smsSend) syncResendDelay();
        if (revision != inputRevision) { message("输入已变化，本次结果已忽略，请重新操作。"); return; }
        if (result.state == NativeCredentialAuth.State.SUCCESS) {
            if (!host.equals(store.host()) || result.cookieHeader.isEmpty()) { message("登录域名已变化，请重新打开登录页"); return; }
            try { store.setCookie(result.cookieHeader); }
            catch (RuntimeException error) { message("无法安全保存登录信息；原账号保留，请重试"); return; }
            committed = true; clearSecrets(); message("登录成功"); auth.close(); worker.shutdownNow(); onSuccess.run(); return;
        }
        message(result.message);
        if (mode == Mode.PASSWORD && result.state == NativeCredentialAuth.State.MATCH_PHONE) { matchPhone.setVisibility(VISIBLE); matchPhone.requestFocus(); }
        if (mode == Mode.SMS && smsSend && result.state == NativeCredentialAuth.State.ACCOUNT_REQUIRED) {
            secret.getText().clear();
            matchPhone.setVisibility(VISIBLE); matchPhone.requestFocus();
            matchPhone.setError("请填写 NGA 用户名或 UID");
            message("此账号不能仅凭手机号登录。请补充 NGA 用户名或 UID，倒计时结束后再次点击“发送验证码”。");
        }
        if (mode == Mode.PASSWORD) reloadImage(result.message);
        if (result.state == NativeCredentialAuth.State.SMS_SENT) {
            syncResendDelay(); secret.requestFocus();
        }
    }
    private void reloadImage() { reloadImage(null); }
    private void reloadImage(String afterMessage) {
        if (mode != Mode.PASSWORD || !available() || busy || challenged) return;
        busy = true; int owner = generation; NativeCredentialAuth requestOwner = auth;
        imageAnswer.getText().clear(); captcha.setImageDrawable(null);
        message(afterMessage == null ? "正在获取 NGA 图形验证码…" : afterMessage + "\n正在更新图形验证码…");
        execute(() -> {
            try {
                if (!current(owner)) return;
                NativeCredentialAuth.ImageChallenge image = requestOwner.imageChallenge();
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(image.image, 0, image.image.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 4096 || bounds.outHeight > 2048
                        || (long) bounds.outWidth * bounds.outHeight > 4_000_000) throw new IOException("验证码图片尺寸异常，请刷新重试");
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inPreferredConfig = Bitmap.Config.RGB_565;
                options.inSampleSize = Math.max(1, Math.max(bounds.outWidth / 1024, bounds.outHeight / 512));
                Bitmap bitmap = BitmapFactory.decodeByteArray(image.image, 0, image.image.length, options); Arrays.fill(image.image, (byte) 0);
                if (bitmap == null) throw new IOException("验证码图片无法显示，请刷新重试");
                main.post(() -> { if (current(owner)) { busy = false; captcha.setImageBitmap(bitmap); message(afterMessage == null ? "填写图形验证码后登录；点击图片可刷新。" : afterMessage + "\n图形验证码已更新，请重新填写。"); } else bitmap.recycle(); });
            } catch (IOException error) { requestOwner.discardImageChallenge(); failure(owner, readable(error)); }
            catch (RuntimeException error) { requestOwner.discardImageChallenge(); failure(owner, "验证码暂时无法显示，请点击图片重试"); }
        });
    }
    private void sendSms() {
        if (mode != Mode.SMS || !available() || busy || challenged || android.os.SystemClock.uptimeMillis() < resendAfter) return;
        if (!agreement.isChecked()) { message("请先阅读并同意 NGA 用户协议和隐私协议"); return; }
        String phone = account.getText().toString(), name = matchPhone.getText().toString();
        if (phone.trim().isEmpty()) { message("请输入绑定的手机号"); return; }
        syncResendDelay();
        if (android.os.SystemClock.uptimeMillis() < resendAfter) { message("请等待倒计时结束后再发送验证码。"); return; }
        int owner = generation, revision = inputRevision, challengeOwner = ++challengeGeneration; challenged = true; message("请完成官方安全验证，完成后才请求发送短信。");
        try {
            challenge = new NativeCaptchaDialog(activity, new NativeCaptchaDialog.Callback() {
                @Override public void complete(String ticket, String randstr) {
                    if (!consumeChallenge(owner, challengeOwner, revision)) return;
                    // Editing the destination during verification requires a fresh explicit send.
                    if (!phone.equals(account.getText().toString()) || !name.equals(matchPhone.getText().toString())) { message("手机号或账号已变化，请重新点击发送"); return; }
                    NativeCredentialAuth requestOwner = auth; busy = true; message("正在向 NGA 请求短信验证码…");
                    execute(() -> {
                        try { if (!current(owner)) return; NativeCredentialAuth.Result result = requestOwner.sendSms(phone, name, ticket, randstr); main.post(() -> apply(owner, revision, result, true)); }
                        catch (IOException error) { failure(owner, revision, readable(error)); }
                        catch (RuntimeException error) { failure(owner, revision, "短信请求未完成，请稍后重试"); }
                    });
                }
                @Override public void cancelled() { if (consumeChallenge(owner, challengeOwner, revision)) message("安全验证已取消，未请求发送短信。"); }
                @Override public void failed(NativeCaptchaDialog.Failure reason) {
                    if (!consumeChallenge(owner, challengeOwner, revision)) return;
                    message(reason == NativeCaptchaDialog.Failure.TIMEOUT ? "安全验证已超时，请重新点击发送验证码。"
                            : "安全验证未完成，请检查网络后重新点击发送验证码。");
                }
            });
        } catch (RuntimeException error) { challenged = false; message("设备暂时无法打开安全验证，请重试或使用扫码登录。"); }
    }
    private boolean consumeChallenge(int owner, int challengeOwner, int revision) {
        if (!current(owner) || !challenged || challengeOwner != challengeGeneration || revision != inputRevision) return false;
        challenge = null; challenged = false; return true;
    }
    private void identityChanged() {
        inputRevision++; secret.getText().clear();
        if (!available()) return;
        if (challenged) {
            challengeGeneration++; challenged = false;
            NativeCaptchaDialog pending = challenge; challenge = null; if (pending != null) pending.close();
            message("手机号或账号已变化，请重新点击发送验证码。");
        }
    }
    private void syncResendDelay() {
        if (mode != Mode.SMS) return;
        resendAfter = android.os.SystemClock.uptimeMillis() + auth.smsRetryAfterMillis(); updateSend();
    }
    private void updateSend() {
        main.removeCallbacks(resendTick); if (!available() || mode != Mode.SMS) return;
        long seconds = Math.max(0, (resendAfter - android.os.SystemClock.uptimeMillis() + 999) / 1000);
        send.setText(seconds == 0 ? "发送验证码" : seconds + " 秒后可重发");
        refreshControls();
        if (seconds > 0) main.postDelayed(resendTick, 1000);
    }
    private void execute(Runnable action) {
        try { worker.execute(action); }
        catch (RejectedExecutionException error) { busy = false; if (available()) message("本次登录已停止，请重新打开页面"); }
    }
    private void failure(int owner, String readable) { failure(owner, inputRevision, readable); }
    private void failure(int owner, int revision, String readable) { main.post(() -> { if (current(owner)) { busy = false; syncResendDelay(); message(revision == inputRevision ? readable : "输入已变化，本次结果已忽略，请重新操作。"); } }); }
    private boolean available() { return !closed && !paused && !committed && !activity.isFinishing() && !activity.isDestroyed(); }
    private boolean current(int owner) { return available() && owner == generation; }
    private void clearSecrets() {
        char[] pending = pendingSecret; pendingSecret = null; if (pending != null) Arrays.fill(pending, '\0');
        secret.getText().clear(); imageAnswer.getText().clear(); matchPhone.getText().clear(); account.getText().clear();
    }
    @Override public void close() {
        if (closed) return; closed = true; generation++; auth.close(); worker.shutdownNow(); main.removeCallbacksAndMessages(null);
        NativeCaptchaDialog pending = challenge; challenge = null; if (pending != null) pending.close();
        clearSecrets(); captcha.setImageDrawable(null); busy = challenged = false;
    }
    /** Credentials intentionally are not recoverable across backgrounding or recreation. */
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (auth == null || closed || committed) return;
        if (visibility != VISIBLE && !paused) {
            // Cancel before clearing inputs: a late result can never switch accounts.
            paused = true; generation++; auth.close(); worker.shutdownNow(); main.removeCallbacksAndMessages(null);
            NativeCaptchaDialog pending = challenge; challenge = null; if (pending != null) pending.close();
            clearSecrets(); captcha.setImageDrawable(null); busy = challenged = false;
        } else if (visibility == VISIBLE && paused) {
            paused = false; auth = new NativeCredentialAuth(host); worker = Executors.newSingleThreadExecutor();
            if (mode == Mode.PASSWORD) reloadImage(); else { message("为保护账号，后台期间的输入已清除，请重新填写。"); updateSend(); }
        }
    }
    @Override protected void onDetachedFromWindow() { close(); super.onDetachedFromWindow(); }
    /** Operational flags only; never dump form text, ticket, password or account values. */
    public void dumpState(PrintWriter out, String prefix) {
        if (out != null) out.println((prefix == null ? "" : prefix) + "nativeCredential method=" + mode
                + " busy=" + busy + " challengeVisible=" + challenged + " paused=" + paused
                + " closed=" + closed + " committed=" + committed);
    }
    private TextView legal(String label, String file) {
        TextView link = text(label, 13, accent); link.setGravity(Gravity.CENTER); link.setOnClickListener(view -> {
            try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://" + host + "/misc/legal/1/" + file + ".html"))); }
            catch (RuntimeException error) { message("设备未找到可打开协议的浏览器"); }
        }); return link;
    }
    private static String readable(IOException error) { return error.getMessage() == null ? "连接 NGA 失败，请稍后重试" : error.getMessage(); }
    private void message(String value) { if (!status.getText().toString().equals(value)) status.setText(value); refreshControls(); }
    private void refreshControls() {
        boolean editable = available() && !busy && !challenged;
        account.setEnabled(editable); matchPhone.setEnabled(editable); secret.setEnabled(editable);
        imageAnswer.setEnabled(editable); captcha.setEnabled(editable); agreement.setEnabled(editable); submit.setEnabled(editable);
        send.setEnabled(editable && android.os.SystemClock.uptimeMillis() >= resendAfter);
    }
    private TextView text(String value, int sp, int color) { TextView view = new TextView(activity); view.setText(value); view.setTextSize(sp); view.setTextColor(color); return view; }
    private EditText input(String hint, int type) {
        EditText view = new EditText(activity); view.setHint(hint); view.setSingleLine(true); view.setTextSize(16); view.setTextColor(ink); view.setHintTextColor(muted);
        view.setInputType(type); view.setPadding(dp(14), 0, dp(14), 0); view.setBackground(shape(surface));
        view.setSaveEnabled(false); view.setSaveFromParentEnabled(false); view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); return view;
    }
    private Button button(String label, boolean primary) {
        Button view = new Button(activity); view.setText(label); view.setAllCaps(false); view.setTextSize(15); view.setTextColor(primary ? palette.onPrimary : ink);
        if (primary) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(palette.ripple), shape(primary ? palette.primaryFill : surface), null)); view.setMinHeight(dp(48)); return view;
    }
    private GradientDrawable shape(int fill) { GradientDrawable background = new GradientDrawable(); background.setColor(fill); background.setCornerRadius(dp(14)); background.setStroke(dp(1), border); return background; }
    private LayoutParams margins(int width, int height, int top, int bottom) { LayoutParams params = new LayoutParams(width, height); params.topMargin = dp(top); params.bottomMargin = dp(bottom); return params; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
