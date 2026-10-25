package cn.ngapure.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;

/** Circular real avatar. Visible views subscribe; users have a labeled default and boards an initial. */
public final class AvatarView extends View implements ViewTreeObserver.OnScrollChangedListener,
        ViewTreeObserver.OnGlobalLayoutListener {
    private static final int[] COLORS = {0xFF735A4A, 0xFF546C64, 0xFF5C617C, 0xFF7B5864, 0xFF54717D, 0xFF777044};
    private final NativeImages loader;
    private final Drawable defaultAvatar;
    private final int preferredSize;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint.FontMetrics textMetrics = new Paint.FontMetrics();
    private final RectF bounds = new RectF();
    private final Rect visibleBounds = new Rect();
    private final Matrix transform = new Matrix();
    private NativeImages.Request pending;
    private String url = "", authorId = "", label = "?", initial = "?";
    private Bitmap bitmap;
    private BitmapShader bitmapShader;
    private int generation, transientFailures;
    private long retryAt;
    private boolean wasVisible, userAvatar, retryScheduled;
    private long scheduledRetryAt;
    private final Runnable retry = () -> { retryScheduled = false; scheduledRetryAt = 0; syncVisibility(); };

    @android.annotation.SuppressLint("DiscouragedApi")
    public AvatarView(Context context, int sizeDp, NativeImages loader) {
        super(context);
        if (loader == null) throw new IllegalArgumentException("缺少共享图片加载器");
        this.loader = loader;
        preferredSize = Math.max(16, sizeDp);
        int defaultId = getResources().getIdentifier("avatar_default", "drawable", context.getPackageName());
        defaultAvatar = defaultId == 0 ? null : context.getDrawable(defaultId);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    public void bind(String url, String label) { bindInternal("", url, label, false); }
    public void bindUser(String authorId, String url, String label) { bindInternal(authorId, url, label, true); }
    private void bindInternal(String authorId, String url, String label, boolean userAvatar) {
        cancel(); generation++;
        this.userAvatar = userAvatar;
        this.url = AvatarSources.normalize(url);
        this.label = label == null || label.trim().isEmpty() ? "?" : label.trim();
        this.authorId = userAvatar && NativeAvatarResolver.eligible(authorId) && !this.label.startsWith("匿名") ? authorId : "";
        if (!this.url.isEmpty() && !this.authorId.isEmpty()) loader.rememberUserAvatar(this.authorId, this.url);
        initial = this.label.substring(0, this.label.offsetByCodePoints(0, 1));
        bitmap = null; bitmapShader = null; retryAt = 0; transientFailures = 0; wasVisible = false;
        setContentDescription(this.label + (userAvatar ? "，默认头像" : "，头像占位")); invalidate();
        if (isAttachedToWindow()) scheduleRetry(0);
    }
    private void scheduleRetry(long at) {
        if (retryScheduled && scheduledRetryAt == at) return;
        if (retryScheduled) removeCallbacks(retry);
        retryScheduled = true; scheduledRetryAt = at;
        postDelayed(retry, Math.max(0, at - SystemClock.uptimeMillis()));
    }
    private void cancel() {
        if (retryScheduled) removeCallbacks(retry);
        retryScheduled = false; scheduledRetryAt = 0;
        if (pending != null) { pending.cancel(); pending = null; }
    }
    private boolean visible() {
        return isAttachedToWindow() && getWindowVisibility() == VISIBLE && isShown()
                && getWidth() > 0 && getHeight() > 0 && getGlobalVisibleRect(visibleBounds)
                && visibleBounds.width() > 0 && visibleBounds.height() > 0;
    }
    private void syncVisibility() {
        // Static avatars retain their pixels until rebound; no viewport work remains.
        if (bitmap != null || url == null || (url.isEmpty() && authorId.isEmpty())) return;
        boolean current = visible();
        if (!current) {
            if (wasVisible) { generation++; cancel(); transientFailures = 0; retryAt = 0; }
            wasVisible = false; return;
        }
        wasVisible = true;
        if (pending != null) return;
        long delay = retryAt - SystemClock.uptimeMillis();
        if (delay > 0) { scheduleRetry(retryAt); return; }
        load();
    }
    private void load() {
        final int token = generation;
        final WeakReference<AvatarView> target = new WeakReference<>(this);
        NativeImages.Callback callback = new NativeImages.Callback() {
            @Override public void loaded(Bitmap value) {
                AvatarView view = target.get();
                if (view == null || view.generation != token || !view.isAttachedToWindow()) return;
                view.pending = null; view.bitmap = value;
                view.bitmapShader = new BitmapShader(value, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                view.setContentDescription(view.label + "的头像"); view.invalidate();
            }
            @Override public void failed(String message) {
                AvatarView view = target.get();
                if (view == null || view.generation != token || !view.isAttachedToWindow()) return;
                view.pending = null;
                view.setContentDescription(view.label + (view.userAvatar ? "，默认头像" : "，头像占位")); view.invalidate();
                // Bounded worker saturation is temporary, not a permanent missing-avatar verdict.
                long delay = "头像加载繁忙".equals(message) ? 180L
                        : "未设置公开头像".equals(message) ? 120000L
                        : ++view.transientFailures <= 2 ? 1800L : 30000L;
                view.retryAt = SystemClock.uptimeMillis() + delay;
                if (view.visible()) view.scheduleRetry(view.retryAt);
            }
        };
        pending = url.isEmpty() ? loader.loadUserAvatar(authorId, callback) : loader.loadAvatar(url, callback);
    }
    @Override public void onScrollChanged() { syncVisibility(); }
    @Override public void onGlobalLayout() { syncVisibility(); }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnScrollChangedListener(this);
        getViewTreeObserver().addOnGlobalLayoutListener(this);
        scheduleRetry(0);
    }
    @Override protected void onDetachedFromWindow() {
        ViewTreeObserver observer = getViewTreeObserver();
        if (observer.isAlive()) { observer.removeOnScrollChangedListener(this); observer.removeOnGlobalLayoutListener(this); }
        generation++; cancel(); wasVisible = false; super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); syncVisibility(); }
    @Override public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible) { scheduleRetry(0); return; }
        if (wasVisible) { generation++; cancel(); transientFailures = 0; retryAt = 0; wasVisible = false; }
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int size = Math.round(preferredSize * getResources().getDisplayMetrics().density);
        setMeasuredDimension(resolveSize(size, widthSpec), resolveSize(size, heightSpec));
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float diameter = Math.min(getWidth(), getHeight());
        float left = (getWidth() - diameter) / 2f, top = (getHeight() - diameter) / 2f;
        bounds.set(left, top, left + diameter, top + diameter);
        if (bitmap != null && !bitmap.isRecycled() && bitmapShader != null) {
            float scale = Math.max(diameter / bitmap.getWidth(), diameter / bitmap.getHeight());
            transform.setScale(scale, scale);
            transform.postTranslate(left + (diameter - bitmap.getWidth() * scale) / 2f, top + (diameter - bitmap.getHeight() * scale) / 2f);
            bitmapShader.setLocalMatrix(transform); paint.setShader(bitmapShader);
            canvas.drawOval(bounds, paint); paint.setShader(null);
        } else if (userAvatar && defaultAvatar != null) {
            paint.setColor(0xFFECEFF3); canvas.drawOval(bounds, paint);
            int inset = Math.round(diameter * .07f);
            defaultAvatar.setBounds(Math.round(left) + inset, Math.round(top) + inset,
                    Math.round(left + diameter) - inset, Math.round(top + diameter) - inset);
            defaultAvatar.draw(canvas);
        } else {
            paint.setColor(COLORS[Math.floorMod(label.hashCode(), COLORS.length)]);
            canvas.drawOval(bounds, paint);
            paint.setColor(0xFFF3EBDD); paint.setTextAlign(Paint.Align.CENTER); paint.setFakeBoldText(true);
            paint.setTextSize(diameter * .42f);
            paint.getFontMetrics(textMetrics);
            canvas.drawText(initial, bounds.centerX(), bounds.centerY() - (textMetrics.ascent + textMetrics.descent) / 2f, paint);
            paint.setFakeBoldText(false);
        }
    }
}
