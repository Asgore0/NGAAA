package cn.ngapure.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import io.nayuki.qrcodegen.QrCode;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Native QR authorization with an encrypted, host-bound ten-minute client recovery window.
 * close() abandons authorization; suspend() preserves it for Activity/process recreation.
 */
public final class NativeLoginView extends FrameLayout implements AutoCloseable {
    // New UI waits off-thread for the prior owner to checkpoint an in-flight grant.
    // Explicit refresh/close cancels that request; no UI thread waits on this lock.
    private static final Object AUTH_WORK_LOCK = new Object();
    private final Activity activity;
    private final NgaStore store;
    private final Runnable onSuccess;
    private final NativeQrRecoveryStore recoveryStore;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final int ink, muted, accent, background, surface, border;
    private final AppPalette palette;
    private final ImageView qrImage;
    private final TextView qrPlaceholder, status, countdown;
    private final ProgressBar spinner;
    private final Button checkButton, refreshButton;
    private final LinearLayout qrContent, credentialContainer;
    private LinearLayout qrCard;
    private FrameLayout qrFrame;
    private final Button[] methodButtons = new Button[3];
    private NativeCredentialLoginView credentialView;
    private int loginMode;
    private volatile Attempt attempt;
    private volatile Attempt stoppedAttempt;
    private volatile boolean closed;
    private volatile boolean completed, qrReady, saveAttempted, saveSucceeded;
    private volatile NativeAuth.State lastState = NativeAuth.State.WAITING;
    private volatile boolean foreground = true;
    private volatile String recoveryState = "NONE";

    public NativeLoginView(Activity activity, NgaStore store, Runnable onSuccess) {
        this(activity, store, onSuccess, 0);
    }
    /** Restores a UI method only. No form, password, SMS value or canceled QR attempt is restored. */
    public NativeLoginView(Activity activity, NgaStore store, Runnable onSuccess, int restoredMethod) {
        super(activity);
        if (store == null) throw new IllegalArgumentException("缺少登录存储");
        this.activity = activity; this.store = store;
        recoveryStore = new NativeQrRecoveryStore(activity);
        this.onSuccess = onSuccess == null ? () -> { } : onSuccess;
        boolean night = "dark".equals(store.theme()) || ("system".equals(store.theme())
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        palette = AppPalette.of(night);
        background = palette.background; surface = palette.surface;
        ink = palette.ink; muted = palette.muted;
        accent = palette.accent; border = palette.border;
        setBackgroundColor(background);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true); scroll.setClipToPadding(false);
        addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout content = new LinearLayout(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int bounded = Math.min(View.MeasureSpec.getSize(widthSpec), dp(560));
                if (qrCard != null) {
                    int cardWidth = Math.min(dp(360), Math.max(0, bounded - getPaddingLeft() - getPaddingRight()));
                    qrCard.getLayoutParams().width = cardWidth;
                    qrFrame.getLayoutParams().height = Math.min(dp(244), Math.max(0, cardWidth - dp(28)));
                }
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(bounded, View.MeasureSpec.EXACTLY), heightSpec);
            }
        };
        content.setOrientation(LinearLayout.VERTICAL); content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(24), dp(28), dp(24), dp(32));
        ScrollView.LayoutParams contentParams = new ScrollView.LayoutParams(-1, -2);
        contentParams.gravity = Gravity.CENTER_HORIZONTAL;
        scroll.addView(content, contentParams);
        NativeAdaptiveActionRow methods = new NativeAdaptiveActionRow(activity, dp(4), dp(48));
        String[] labels = {"扫码", "账号密码", "手机验证码"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i; Button method = actionButton(labels[i], false); methodButtons[i] = method;
            method.setTextSize(14); method.setPadding(dp(4), 0, dp(4), 0);
            methods.addAction(method, 1); method.setOnClickListener(view -> selectMethod(index));
        }
        content.addView(methods, margins(-1, -2, 0, 0, 0, 24));
        qrContent = new LinearLayout(activity); qrContent.setOrientation(LinearLayout.VERTICAL); qrContent.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(qrContent, width(-1, -2));
        credentialContainer = new LinearLayout(activity); credentialContainer.setOrientation(LinearLayout.VERTICAL); credentialContainer.setVisibility(GONE);
        content.addView(credentialContainer, width(-1, -2));
        TextView title = text("扫码登录 NGA", 25, ink);
        title.setTypeface(null, Typeface.BOLD); title.setGravity(Gravity.CENTER);
        qrContent.addView(title, width(-1, -2));
        TextView subtitle = text("使用已登录的 NGA 官方 APP 扫一扫\n手机确认授权后，本页自动登录；\n未跳转可点“确认登录”", 15, muted);
        subtitle.setGravity(Gravity.CENTER); subtitle.setLineSpacing(dp(4), 1f);
        qrContent.addView(subtitle, margins(-1, -2, 0, 12, 0, 24));

        LinearLayout card = new LinearLayout(activity); qrCard = card;
        card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(14), dp(18), dp(14), dp(18));
        card.setBackground(shape(surface, 22, border));
        qrContent.addView(card, width(dp(360), -2));
        qrFrame = new FrameLayout(activity);
        qrFrame.setBackground(shape(Color.WHITE, 12, AppPalette.of(false).line));
        card.addView(qrFrame, width(-1, dp(244)));
        qrImage = new ImageView(activity); qrImage.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        qrImage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        qrImage.setPadding(dp(8), dp(8), dp(8), dp(8));
        qrFrame.addView(qrImage, new FrameLayout.LayoutParams(-1, -1));
        qrPlaceholder = text("正在获取官方授权二维码", 15, AppPalette.of(false).muted);
        qrPlaceholder.setGravity(Gravity.CENTER); qrPlaceholder.setPadding(dp(16), dp(24), dp(16), 0);
        qrFrame.addView(qrPlaceholder, new FrameLayout.LayoutParams(-1, -1));
        spinner = new ProgressBar(activity);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(AppPalette.of(false).accent));
        FrameLayout.LayoutParams progress = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER);
        progress.topMargin = -dp(48); qrFrame.addView(spinner, progress);
        TextView qrNote = text("二维码仅供本次授权，请勿转发给他人", 12, muted);
        qrNote.setGravity(Gravity.CENTER); card.addView(qrNote, margins(-1, -2, 0, 12, 0, 0));

        status = text("正在连接 NGA…", 17, ink); status.setGravity(Gravity.CENTER);
        status.setLineSpacing(dp(4), 1f); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        qrContent.addView(status, margins(-1, -2, 0, 22, 0, 0));
        countdown = text("", 13, muted); countdown.setGravity(Gravity.CENTER);
        qrContent.addView(countdown, margins(-1, -2, 0, 8, 0, 18));

        NativeAdaptiveActionRow buttons = new NativeAdaptiveActionRow(activity, dp(12), dp(50));
        checkButton = actionButton("确认登录", true);
        refreshButton = actionButton("刷新二维码", false);
        buttons.addAction(checkButton, 1); buttons.addAction(refreshButton, 1);
        qrContent.addView(buttons, width(-1, -2));
        checkButton.setOnClickListener(v -> {
            Attempt current = attempt;
            if (current != null && current.session != null && !current.busy && !current.terminal) {
                current.autoPaused = false; requestPoll(current, true);
            }
        });
        refreshButton.setOnClickListener(v -> refresh());
        TextView note = text(store.loggedIn() ? "当前账号会保留，新的授权确认后才会切换账号。" : "首次使用需要先在 NGA 官方 APP 登录。", 13, muted);
        note.setGravity(Gravity.CENTER); note.setLineSpacing(dp(3), 1f);
        qrContent.addView(note, margins(-1, -2, 0, 18, 0, 0));
        TextView recoveryNote = text("本机仅加密保留 10 分钟授权恢复记录；\n这不是服务器有效期，二维码可能提前失效。", 12, muted);
        recoveryNote.setGravity(Gravity.CENTER); recoveryNote.setLineSpacing(dp(3), 1f);
        qrContent.addView(recoveryNote, margins(-1, -2, 0, 12, 0, 0));
        updateMethodButtons();
        if(restoredMethod==1||restoredMethod==2)selectMethod(restoredMethod);else start(true);
    }

    /** The only restorable login UI value: 0=QR, 1=password, 2=SMS. */
    public int selectedMethod() { return loginMode; }
    /** Prefer the four-argument constructor for recreation, so no interim QR request is started. */
    public void restoreSelectedMethod(int method) { if(method>=0&&method<methodButtons.length)selectMethod(method); }

    private void selectMethod(int index) {
        if (!usable() || completed || index == loginMode) return;
        cancelAttempt(attempt); attempt = null; clearQr();
        if (credentialView != null) { credentialView.close(); credentialView = null; }
        credentialContainer.removeAllViews(); loginMode = index;
        qrContent.setVisibility(index == 0 ? VISIBLE : GONE);
        credentialContainer.setVisibility(index == 0 ? GONE : VISIBLE); updateMethodButtons();
        if (index == 0) start(false);
        else {
            credentialView = new NativeCredentialLoginView(activity, store,
                    index == 1 ? NativeCredentialLoginView.Mode.PASSWORD : NativeCredentialLoginView.Mode.SMS, () -> {
                if (!usable() || completed) return;
                completed = true; saveAttempted = saveSucceeded = true;
                cancelAttempt(attempt); worker.shutdownNow(); onSuccess.run();
            });
            credentialContainer.addView(credentialView, width(-1, -2));
        }
    }
    private void updateMethodButtons() {
        for (int i = 0; i < methodButtons.length; i++) {
            methodButtons[i].setSelected(i == loginMode);
            methodButtons[i].setTypeface(null, i == loginMode ? Typeface.BOLD : Typeface.NORMAL);
            methodButtons[i].setTextColor(i == loginMode ? accent : muted);
        }
    }

    private void refresh() { start(false); }
    private void start(boolean allowRecovery) {
        if (!usable() || completed) return;
        cancelAttempt(attempt); clearQr();
        lastState = NativeAuth.State.WAITING; saveAttempted = false; saveSucceeded = false;
        Attempt next = new Attempt(store.host()); attempt = next;
        next.busy = true; spinner.setVisibility(View.VISIBLE);
        qrPlaceholder.setText("正在获取官方授权二维码"); qrPlaceholder.setVisibility(View.VISIBLE);
        message("正在连接 NGA…"); countdown.setText(""); updateButtons(next);
        execute(next, () -> {
            try {
                if (!active(next)) return;
                NativeQrRecoveryStore.SavedAttempt saved;
                synchronized (next) {
                    if (!active(next)) return;
                    saved = allowRecovery ? recoveryStore.restore(next.host) : null;
                    next.lease = saved == null ? recoveryStore.fresh(next.host) : saved.lease;
                    next.restored = saved != null;
                }
                NativeAuth.QrSession session = saved == null ? next.auth.begin() : next.auth.restore(saved.state, saved.ageMillis);
                next.session = session;
                if (!active(next)) { releaseAuth(next); return; }
                Bitmap bitmap = qrBitmap(session.qrText());
                main.post(() -> {
                    if (!active(next)) { releaseAuth(next); return; }
                    next.busy = false;
                    BitmapDrawable drawable = new BitmapDrawable(getResources(), bitmap);
                    drawable.setFilterBitmap(false); drawable.setAntiAlias(false);
                    qrImage.setImageDrawable(drawable); qrReady = true; qrPlaceholder.setVisibility(View.GONE); spinner.setVisibility(View.GONE);
                    recoveryState = next.restored ? "RESTORED" : "FRESH";
                    message(next.restored ? "已恢复本次二维码授权；手机确认后可点“确认登录”" : "等待扫码并在 NGA 官方 APP 中确认授权");
                    next.autoPaused = session.autoPollRemainingMillis() == 0;
                    if (next.autoPaused) {
                        lastState = NativeAuth.State.PAUSED;
                        countdown.setText("已恢复原授权，自动检查已暂停；可点“确认登录”");
                    } else { schedulePoll(next, 3000); tick(next); }
                    updateButtons(next);
                });
            } catch (IOException error) {
                if (next.restored && next.session == null && !next.preserveRecovery) next.auth.close();
                recoveryState = next.restored ? "INVALID" : "ERROR";
                fail(next, safeError(error));
            }
            catch (RuntimeException error) { releaseAuth(next); next.session = null; fail(next, "二维码生成失败，请刷新后重试"); }
            finally { if (!active(next)) releaseAuth(next); }
        });
    }

    private void requestPoll(Attempt current, boolean manual) {
        if (!active(current) || !foreground || loginMode != 0 || current.session == null || current.busy || current.terminal) return;
        main.removeCallbacks(current.pollTask); current.busy = true; updateButtons(current);
        if (manual) message("正在检查官方授权…");
        execute(current, () -> {
            try {
                if (!active(current)) return;
                NativeAuth.PollResult result = current.auth.poll(current.session, manual);
                main.post(() -> apply(current, result));
            } catch (IOException error) { fail(current, safeError(error)); }
            catch (RuntimeException error) { fail(current, "暂时无法检查授权，请重试或刷新二维码"); }
            finally { if (!active(current)) releaseAuth(current); }
        });
    }

    private void apply(Attempt current, NativeAuth.PollResult result) {
        if (!active(current)) return;
        current.busy = false;
        lastState = result.state;
        if (result.state == NativeAuth.State.SUCCESS) {
            // This main-thread commit cannot race another UI refresh or screen navigation.
            if (result.cookieHeader.isEmpty() || !store.host().equals(current.session.host())) {
                failOnMain(current, "登录域名已变化，请刷新二维码重试"); return;
            }
            saveAttempted = true;
            try { store.setCookie(result.cookieHeader); saveSucceeded = true; }
            catch (RuntimeException error) { failOnMain(current, "无法安全保存登录信息，请重试或检查设备安全设置"); return; }
            completed = true; current.terminal = true; current.autoPaused = true;
            main.removeCallbacks(current.pollTask); main.removeCallbacks(current.tickTask);
            message("登录成功"); countdown.setText(""); clearQr(); updateButtons(current);
            releaseAuth(current); worker.shutdownNow();
            if (usable()) onSuccess.run();
            return;
        }
        message(result.message);
        switch (result.state) {
            case WAITING:
                if (!current.autoPaused) { schedulePoll(current, Math.max(3000, result.retryAfterMillis)); tick(current); }
                break;
            case PAUSED:
                current.autoPaused = true; countdown.setText("自动检查已暂停，手机授权后可点“确认登录”");
                break;
            case EXPIRED:
            case VERIFY_REQUIRED:
            case CANCELLED:
                current.terminal = true; current.autoPaused = true; releaseAuth(current); clearQr();
                qrPlaceholder.setText(result.state == NativeAuth.State.EXPIRED ? "二维码已过期，请刷新"
                        : result.state == NativeAuth.State.CANCELLED ? "本次登录已取消，请刷新" : "请完成官方验证后刷新二维码");
                qrPlaceholder.setVisibility(View.VISIBLE); countdown.setText("请刷新二维码重新授权");
                break;
            default:
                current.autoPaused = true; countdown.setText("自动检查已暂停，可点“确认登录”重试或刷新二维码");
                break;
        }
        updateButtons(current);
    }

    private void schedulePoll(Attempt current, long delay) {
        main.removeCallbacks(current.pollTask);
        if (active(current) && foreground && !current.autoPaused && !current.terminal) main.postDelayed(current.pollTask, delay);
    }
    private void tick(Attempt current) {
        if (!active(current) || !foreground || current.terminal || current.autoPaused || completed) return;
        if (!current.autoPaused && current.session != null) {
            long seconds = (current.session.autoPollRemainingMillis() + 999) / 1000;
            countdown.setText(seconds > 0 ? "自动检查中 · " + seconds + " 秒后暂停" : "正在完成最后一次自动检查…");
        }
        main.removeCallbacks(current.tickTask);
        main.postDelayed(current.tickTask, 1000);
    }
    private void fail(Attempt current, String message) { main.post(() -> failOnMain(current, message)); }
    private void failOnMain(Attempt current, String readable) {
        if (!active(current)) return;
        lastState = NativeAuth.State.ERROR;
        current.busy = false; current.autoPaused = true; main.removeCallbacks(current.pollTask);
        spinner.setVisibility(View.GONE); message(readable);
        if (current.session == null) {
            qrPlaceholder.setText("暂时无法获取二维码，请刷新重试"); qrPlaceholder.setVisibility(View.VISIBLE);
            countdown.setText("已有账号的登录状态不受影响");
        } else countdown.setText("自动检查已暂停，可点“确认登录”重试或刷新二维码");
        updateButtons(current);
    }
    private void updateButtons(Attempt current) {
        // Polling is background work, not a visual button state. Keep the same
        // enabled paint while an attempt is available; the click guard above
        // still rejects duplicate requests while busy.
        checkButton.setEnabled(active(current) && !completed && current.session != null && !current.terminal);
        refreshButton.setEnabled(usable() && !completed);
        checkButton.setAlpha(1f);
        refreshButton.setAlpha(1f);
    }
    private void execute(Attempt current, Runnable task) {
        try { worker.execute(() -> { synchronized (AUTH_WORK_LOCK) { task.run(); } }); }
        catch (RejectedExecutionException error) { if (active(current)) failOnMain(current, "登录检查已停止，请重新打开登录页面"); }
    }
    private boolean usable() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }
    private boolean active(Attempt current) { return current != null && attempt == current && !current.cancelled && usable(); }
    private void cancelAttempt(Attempt current) {
        if (current == null) return;
        synchronized (current) { current.cancelled = true; }
        main.removeCallbacks(current.pollTask); main.removeCallbacks(current.tickTask); releaseAuth(current);
    }
    private void releaseAuth(Attempt current) {
        if (current.preserveRecovery) current.auth.suspend(); else current.auth.close();
        if (current.auth.recoveryCleanupFailed() && !current.cleanupReported) {
            current.cleanupReported = true; recoveryState = "CLEANUP_ERROR";
            activity.runOnUiThread(() -> Toast.makeText(activity,
                    "本次授权恢复记录清理失败，请重新打开登录页并刷新重试", Toast.LENGTH_LONG).show());
        }
    }
    @Override public void close() {
        stop(false);
    }
    /** Idempotently detaches this UI; finite in-flight work checkpoints before its memory clears.
     * Reopening restores the same encrypted attempt without extending the original time windows.
     * Use for configuration recreation or background destruction, never explicit cancel/refresh/logout.
     */
    public void suspend() {
        stop(true);
    }
    private void stop(boolean preserve) {
        if (closed) {
            // A later explicit exit may abandon a previously suspended owner; CAS
            // still prevents it from clearing a newer view's restored authorization.
            Attempt previous = stoppedAttempt;
            if (!preserve && previous != null && previous.preserveRecovery) {
                previous.preserveRecovery = false; releaseAuth(previous);
            }
            return;
        }
        Attempt current = attempt;
        stoppedAttempt = current;
        if (current != null) current.preserveRecovery = preserve;
        closed = true; cancelAttempt(current); attempt = null;
        if (credentialView != null) { credentialView.close(); credentialView = null; }
        main.removeCallbacksAndMessages(null);
        if (preserve) worker.shutdown(); else worker.shutdownNow();
        if (Looper.myLooper() == Looper.getMainLooper()) clearQr(); else main.post(this::clearQr);
    }
    @Override protected void onDetachedFromWindow() {
        // Explicit navigation closes first. A detach caused by Activity recreation must preserve the checkpoint.
        if (!closed) suspend();
        super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        foreground = visibility == View.VISIBLE;
        Attempt current = attempt;
        if (current == null || main == null) return;
        if (!foreground) { main.removeCallbacks(current.pollTask); main.removeCallbacks(current.tickTask); }
        else if (active(current) && current.session != null && !current.busy && !current.terminal && !current.autoPaused) {
            schedulePoll(current, 3000); tick(current);
        }
    }

    /** Only a safe presence predicate for routing after process recreation; never exposes QR state. */
    public static boolean hasPendingAuthorization(Context context, String host) {
        return new NativeQrRecoveryStore(context).hasPending(host);
    }

    /** Safe operational metadata only. Does not include QR pixels/text, messages or stored session values. */
    public void dumpState(PrintWriter out, String prefix) {
        if (out == null) return;
        String lead = prefix == null ? "" : prefix;
        Attempt current = attempt;
        out.println(lead + "nativeLogin closed=" + closed + " completed=" + completed + " state=" + lastState
                + " attemptPresent=" + (current != null) + " busy=" + (current != null && current.busy)
                + " terminal=" + (current != null && current.terminal) + " autoPaused=" + (current != null && current.autoPaused)
                + " qrReady=" + qrReady + " saveAttempted=" + saveAttempted + " saveSucceeded=" + saveSucceeded
                + " recovery=" + recoveryState + " foreground=" + foreground + " method=" + (loginMode == 0 ? "QR" : loginMode == 1 ? "PASSWORD" : "SMS"));
        if (current != null) out.println(lead + "nativeAuth " + current.auth.safeDiagnostics());
        if (credentialView != null) credentialView.dumpState(out, lead);
    }

    private Bitmap qrBitmap(String payload) {
        QrCode code = QrCode.encodeText(payload, QrCode.Ecc.MEDIUM);
        int quiet = 4, modules = code.size + quiet * 2;
        int desired = Math.min(1536, dp(236)), scale = Math.max(1, desired / modules), size = modules * scale;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++)
            pixels[y * size + x] = code.getModule(x / scale - quiet, y / scale - quiet) ? Color.BLACK : Color.WHITE;
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
    }
    private void clearQr() { qrImage.setImageDrawable(null); qrReady = false; spinner.setVisibility(View.GONE); }
    private void message(String value) { if (!status.getText().toString().equals(value)) status.setText(value); }
    private static String safeError(IOException error) {
        String value = error.getMessage(); return value == null || value.isEmpty() ? "连接 NGA 失败，请检查网络后重试" : value;
    }
    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(activity); view.setText(value); view.setTextColor(color); view.setTextSize(sp);
        view.setIncludeFontPadding(false); return view;
    }
    private Button actionButton(String value, boolean primary) {
        Button button = new Button(activity); button.setText(value); button.setTextSize(15); button.setAllCaps(false);
        button.setTextColor(primary ? palette.onPrimary : ink); button.setMinimumHeight(dp(48)); button.setMinHeight(dp(48));
        if (primary) button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setPadding(dp(12), 0, dp(12), 0);
        int fill = primary ? palette.primaryFill : surface;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.ripple), shape(fill, 14, primary ? fill : border), null));
        return button;
    }
    private GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(dp(radius)); drawable.setStroke(dp(1), stroke); return drawable;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams width(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private LinearLayout.LayoutParams margins(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = width(width, height); params.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return params;
    }
    private final class Attempt {
        final String host;
        final NativeAuth auth;
        volatile NativeQrRecoveryStore.Lease lease;
        volatile NativeAuth.QrSession session;
        volatile boolean cancelled;
        volatile boolean busy, terminal, autoPaused, preserveRecovery, restored, cleanupReported;
        final Runnable pollTask = () -> requestPoll(this, false);
        final Runnable tickTask = () -> tick(this);
        Attempt(String host) {
            this.host = host;
            auth = new NativeAuth(host, new NativeAuth.Recovery() {
                @Override public void checkpoint(String state) throws IOException {
                    NativeQrRecoveryStore.Lease current = lease;
                    if (current == null || (cancelled && !preserveRecovery)) throw new IOException("本次授权检查已停止");
                    current.checkpoint(state);
                }
                @Override public void clear() throws IOException {
                    NativeQrRecoveryStore.Lease current = lease;
                    // Suspension itself does not clear. A real terminal server
                    // result must clear even when its old UI has detached.
                    if (current != null) current.clear();
                }
            });
        }
    }
}
