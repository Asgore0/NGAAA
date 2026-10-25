package cn.ngapure.app;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/** Fixed glass material. Page pixels are never sampled, stored or refreshed. */
public final class GlassPanel extends FrameLayout {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private float tintOpacity = -1f;
    private final Backdrop backdrop;
    private final Material material;
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final boolean lowRam;
    private boolean glassEnabled, night;
    private boolean lensingEnabled = true, lensFailed;
    private boolean localLensBound;
    private float radius, lightX = .24f, lightY = .08f;
    private float targetLightX = .24f, targetLightY = .08f;
    private boolean opticsDirty = true, lightFramePosted, motionEnabled = true;
    private long lightFrameTime, lightTargetTime;
    private float lensHead, lensTail, lensSquash = 1f, lensActivity;
    private int surfaceColor = 0xFFF5F4EF;
    private int materialWidth, materialHeight;
    private boolean materialNight, materialFailed;
    private Api33 optics;
    private final Runnable refreshRequest = this::refreshBackdrop;
    private final Runnable lightStep = this::advanceLight;

    public GlassPanel(Context context) {
        super(context);
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        lowRam = am != null && am.isLowRamDevice();
        radius = dp(20);
        setWillNotDraw(false);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        backdrop = new Backdrop(context);
        backdrop.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        backdrop.setVisibility(INVISIBLE);
        addView(backdrop, new LayoutParams(-1, -1));
        material = new Material(context);
        material.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        material.setVisibility(INVISIBLE);
        addView(material, new LayoutParams(-1, -1));
    }

    /** Disabled by default so the official-style layout retains its opaque surface. */
    public void setGlassEnabled(boolean enabled) {
        if (glassEnabled == enabled) return;
        glassEnabled = enabled;
        if (enabled) refreshBackdrop();
        else { stopLight(true); clearBackdrop(); }
        invalidate();
    }

    public boolean isGlassEnabled() { return glassEnabled; }

    /** Controls only interactive light easing; shape motion is supplied by the caller. */
    public void setMotionEnabled(boolean enabled) {
        motionEnabled = enabled;
        if (!enabled) stopLight(true);
    }

    /** Larger menus use a denser veil while keeping their own background optics. */
    public void setTintOpacity(float opacity) {
        if (Float.isNaN(opacity) || Float.isInfinite(opacity)) return;
        tintOpacity = Math.max(0f, Math.min(1f, opacity));
        material.invalidate();
    }

    // Non-sensitive adb diagnostics for verifying the active device rendering path.
    String renderingPath() {
        if (!glassEnabled || backdrop.pixels == null) return "solid";
        if (Build.VERSION.SDK_INT >= 33 && lensingEnabled && optics != null && !lensFailed) return "agsl";
        return "material";
    }
    long snapshotCount() { return 0; }

    public void setNightMode(boolean value) {
        boolean changed = night != value;
        night = value;
        surfaceColor = value ? 0xFF232622 : 0xFFF5F4EF;
        material.lightingChanged(); if (changed) refreshBackdrop(); invalidate();
    }

    /** Opaque fallback color; fixed glass uses its own neutral theme palette. */
    public void setSurfaceColor(int color) {
        surfaceColor = color | 0xFF000000;
        material.lightingChanged(); invalidate();
    }

    public void setCornerRadiusDp(float value) {
        radius = dp(Math.max(0, value));
        invalidateOutline(); material.lightingChanged(); invalidate();
        requestOpticsUpdate();
    }

    /** Optical edge bending on API 33+; the fixed material remains when disabled. */
    public void setLensingEnabled(boolean enabled) {
        if (lensingEnabled == enabled) return;
        lensingEnabled = enabled;
        requestOpticsUpdate();
    }

    /**
     * Synchronize the selected capsule's optics without taking a new snapshot.
     * Head/tail are its two horizontal OUTER edges, normalized to panel width;
     * their order is normalized so reversing direction cannot swap the optics.
     * Squash scales the rest
     * height (panel height minus 12 dp), activity is 0..1. Children stay sharp.
     * Until this is called, only the existing outer-panel lens is rendered.
     */
    public void setLensMotion(float headNormalized, float tailNormalized, float squash, float activity) {
        if (!finite(headNormalized) || !finite(tailNormalized) || !finite(squash) || !finite(activity)) return;
        float first = Math.max(0, Math.min(1, headNormalized));
        float second = Math.max(0, Math.min(1, tailNormalized));
        float head = Math.min(first, second), tail = Math.max(first, second);
        float thickness = Math.max(.75f, Math.min(1.15f, squash));
        float moving = Math.max(0, Math.min(1, activity));
        if (localLensBound && Math.abs(head - lensHead) < .0005f && Math.abs(tail - lensTail) < .0005f
                && Math.abs(thickness - lensSquash) < .001f && Math.abs(moving - lensActivity) < .002f) return;
        localLensBound = true; lensHead = head; lensTail = tail;
        lensSquash = thickness; lensActivity = moving;
        // Lower APIs retain their material fallback; no effect churn or CPU
        // bitmap work is necessary for a capability they cannot display.
        if (Build.VERSION.SDK_INT >= 33 && lensingEnabled && !lensFailed) requestOpticsUpdate();
    }

    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }

    /** Light position in panel-local normalized 0..1 coordinates. No new snapshot. */
    public void setInteractiveLight(float normalizedX, float normalizedY) {
        if (Float.isNaN(normalizedX) || Float.isNaN(normalizedY)
                || Float.isInfinite(normalizedX) || Float.isInfinite(normalizedY)) return;
        float x = Math.max(0, Math.min(1, normalizedX)), y = Math.max(0, Math.min(1, normalizedY));
        if (Math.abs(x - targetLightX) < .002f && Math.abs(y - targetLightY) < .002f) return;
        targetLightX = x; targetLightY = y; lightTargetTime = SystemClock.uptimeMillis();
        if (!canEaseLight()) { stopLight(true); return; }
        requestLightFrame();
    }

    private boolean canEaseLight() {
        return motionEnabled && glassEnabled && isAttachedToWindow() && isShown()
                && getWindowVisibility() == VISIBLE && android.animation.ValueAnimator.areAnimatorsEnabled();
    }

    private void requestLightFrame() {
        if (lightFramePosted) return;
        lightFramePosted = true; postOnAnimation(lightStep);
    }

    private void advanceLight() {
        lightFramePosted = false;
        if (!canEaseLight()) { stopLight(true); return; }
        long now = SystemClock.uptimeMillis();
        float dt = lightFrameTime == 0 ? 16f : Math.max(1f, Math.min(40f, now - lightFrameTime));
        lightFrameTime = now;
        float blend = 1f - (float) Math.exp(-dt / 24f);
        lightX += (targetLightX - lightX) * blend; lightY += (targetLightY - lightY) * blend;
        boolean finished = Math.abs(lightX - targetLightX) < .0008f
                && Math.abs(lightY - targetLightY) < .0008f;
        if (finished || now - lightTargetTime >= 240) {
            lightX = targetLightX; lightY = targetLightY; lightFrameTime = 0;
        }
        material.lightingChanged(); requestOpticsUpdate();
        if (!finished && lightFrameTime != 0) requestLightFrame();
    }

    private void stopLight(boolean settle) {
        if (lightStep != null) removeCallbacks(lightStep);
        lightFramePosted = false; lightFrameTime = 0;
        if (settle) { lightX = targetLightX; lightY = targetLightY; }
        if (material != null) { material.lightingChanged(); requestOpticsUpdate(); }
    }

    private void requestOpticsUpdate() {
        opticsDirty = true;
        if (isAttachedToWindow() && isShown()) invalidate();
    }

    /** Compatibility entry points: fixed material never observes or draws a page. */
    public void setScrollTracking(boolean ignored) { }
    public void setBlurSource(View ignored) { }

    /** Ensures a theme/geometry texture; content changes cannot replace it. */
    public void refreshBackdrop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.removeCallbacks(refreshRequest); MAIN.post(refreshRequest); return;
        }
        ensureMaterial();
    }

    /** Releases only generated material resources, without retaining page data. */
    public void clearBackdrop() {
        MAIN.removeCallbacks(refreshRequest);
        backdrop.clear(); materialWidth = 0; materialHeight = 0; materialFailed = false;
        backdrop.setVisibility(INVISIBLE); material.setVisibility(INVISIBLE);
        optics = null; opticsDirty = true;
        invalidate();
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        // Combine light and spring updates once before drawing; the fixed
        // texture and shader instance are retained across frames.
        if (opticsDirty) { opticsDirty = false; updateBackdropShader(); }
        super.dispatchDraw(canvas);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Generate before the first visible draw, never after a scroll settles.
        if (glassEnabled) ensureMaterial();
        basePaint.setColor(surfaceColor);
        bounds.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(bounds, radius, radius, basePaint);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        invalidateOutline(); requestOpticsUpdate();
        if (w != oldw || h != oldh) refreshBackdrop();
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // Material spans the entire panel, even if caller adds content padding.
        backdrop.layout(0, 0, getWidth(), getHeight());
        material.layout(0, 0, getWidth(), getHeight());
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshBackdrop();
    }

    @Override protected void onDetachedFromWindow() {
        stopLight(true); clearBackdrop();
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (backdrop != null) {
            if (!isShown()) { stopLight(true); clearBackdrop(); }
            else refreshBackdrop();
        }
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (backdrop != null) {
            if (visibility != VISIBLE) { stopLight(true); clearBackdrop(); }
            else refreshBackdrop();
        }
    }

    private float dp(float n) { return n * getResources().getDisplayMetrics().density; }

    private void ensureMaterial() {
        if (!glassEnabled || !isAttachedToWindow() || !isShown() || getWindowVisibility() != VISIBLE
                || getWidth() <= 0 || getHeight() <= 0) return;
        int width = getWidth(), height = getHeight();
        if ((backdrop.pixels != null || materialFailed) && materialWidth == width && materialHeight == height && materialNight == night) return;
        // A bounded, soft neutral texture is independent of posts, avatars and
        // scrolling. Only theme or actual panel geometry may rebuild it.
        int limit = lowRam ? 96 : 192;
        float scale = Math.min(.25f, limit / (float) Math.max(width, height));
        int w = Math.max(1, (int) Math.ceil(width * scale));
        int h = Math.max(1, (int) Math.ceil(height * scale));
        Bitmap pixels = null;
        try {
            pixels = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(pixels);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            int[] palette = night ? new int[]{0xFF272B25, 0xFF383D33, 0xFF232720}
                    : new int[]{0xFFEAEADE, 0xFFFCFBF6, 0xFFE2E4D8};
            paint.setShader(new LinearGradient(0, 0, w, h, palette, new float[]{0, .54f, 1}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(new RadialGradient(w * .24f, 0, Math.max(w, h) * .9f,
                    new int[]{night ? 0x0CFFFFFF : 0x36FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            backdrop.setTexture(pixels, scale, 0);
            materialWidth = width; materialHeight = height; materialNight = night;
            backdrop.setVisibility(VISIBLE); material.setVisibility(VISIBLE);
            requestOpticsUpdate(); material.invalidate();
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            if (pixels != null && backdrop.pixels != pixels && !pixels.isRecycled()) pixels.recycle();
            clearBackdrop();
            // Do not retry allocation every frame after a memory failure.
            materialWidth = width; materialHeight = height; materialNight = night; materialFailed = true;
        }
    }

    private void updateBackdropShader() {
        Shader image = backdrop.image;
        if (!glassEnabled || image == null) { backdrop.paint.setShader(null); return; }
        Shader result = image;
        // Refraction bends the generated texture, never the page. The shader
        // and input remain stable while shape/light parameters change.
        if (Build.VERSION.SDK_INT >= 33 && !lowRam && isHardwareAccelerated() && lensingEnabled && !lensFailed) {
            try {
                if (optics == null) optics = new Api33();
                result = optics.apply(image, dp(7), dp(18), radius, lightX, lightY, getWidth(), getHeight(),
                        localLensBound && Math.abs(lensHead - lensTail) > .001f,
                        lensHead, lensTail, lensSquash, lensActivity, dp(6));
            } catch (RuntimeException unsupported) { lensFailed = true; optics = null; }
        }
        backdrop.paint.setShader(result);
        backdrop.invalidate();
    }

    private final class Backdrop extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix imageMatrix = new Matrix();
        private Bitmap pixels;
        private BitmapShader image;
        Backdrop(Context context) { super(context); }
        void setTexture(Bitmap value, float scale, int pad) {
            pixels = value;
            imageMatrix.setScale(1f / scale, 1f / scale); imageMatrix.postTranslate(-pad, -pad);
            image = new BitmapShader(value, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            image.setLocalMatrix(imageMatrix);
            if (Build.VERSION.SDK_INT >= 33) Api33.linear(image);
            paint.setShader(image); imagePaint.setShader(image);
            invalidate();
        }
        void clear() { paint.setShader(null); imagePaint.setShader(null); pixels = null; image = null; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            // Page-transition snapshots use a software Canvas. Keep that path
            // cheap and compatible rather than executing the optical shader.
            if (pixels != null) canvas.drawRect(0, 0, getWidth(), getHeight(), canvas.isHardwareAccelerated() ? paint : imagePaint);
        }
    }

    private final class Material extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private Shader sheen, glint, brightEdge, darkEdge;
        private final Matrix axisMatrix = new Matrix(), glintMatrix = new Matrix();
        private boolean lightingDirty = true;
        private boolean paletteReady, paletteNight;
        private int lightingWidth, lightingHeight;
        Material(Context context) { super(context); }
        void lightingChanged() { lightingDirty = true; invalidate(); }

        private void prepareLighting() {
            int w = getWidth(), h = getHeight();
            if (!lightingDirty && w == lightingWidth && h == lightingHeight) return;
            lightingDirty = false; lightingWidth = w; lightingHeight = h;
            if (!paletteReady || paletteNight != night) {
                paletteReady = true; paletteNight = night;
                // Unit shaders are cached by theme. Per-frame light changes
                // move reusable matrices, never allocate replacement gradients.
                sheen = new LinearGradient(0, 0, 1, 0,
                    new int[]{night ? 0x18FFFFFF : 0x30FFFFFF, 0x02FFFFFF,
                            night ? 0x08000000 : 0x0B000000},
                    new float[]{0, .55f, 1}, Shader.TileMode.CLAMP);
                glint = new RadialGradient(0, 0, 1,
                    new int[]{night ? 0x20FFFFFF : 0x38FFFFFF, 0x05FFFFFF, 0x00FFFFFF},
                    new float[]{0, .48f, 1}, Shader.TileMode.CLAMP);
                brightEdge = new LinearGradient(0, 0, 1, 0,
                    new int[]{night ? 0xA3FFFFFF : 0xC6FFFFFF, 0x21FFFFFF,
                            night ? 0x49FFFFFF : 0x78FFFFFF},
                    new float[]{0, .52f, 1}, Shader.TileMode.CLAMP);
                darkEdge = new LinearGradient(0, 0, 1, 0,
                    new int[]{0x00000000, 0x04000000, night ? 0x35000000 : 0x1D000000},
                    new float[]{0, .45f, 1}, Shader.TileMode.CLAMP);
            }
            // Keep the rim's axis pointing down/right. Pointer motion can bend
            // it gently, but cannot shrink it to zero or flip it at the center.
            float dx = .64f + (lightX - .5f) * .14f;
            float dy = .78f + (lightY - .5f) * .10f;
            float length = (float) Math.sqrt(dx * dx + dy * dy);
            dx /= length; dy /= length;
            float span = Math.max(1, (float) Math.hypot(w, h));
            axisMatrix.setScale(span, 1);
            axisMatrix.postRotate((float) Math.toDegrees(Math.atan2(dy, dx)));
            axisMatrix.postTranslate(w * .5f - dx * span * .5f, h * .5f - dy * span * .5f);
            sheen.setLocalMatrix(axisMatrix); brightEdge.setLocalMatrix(axisMatrix); darkEdge.setLocalMatrix(axisMatrix);
            float glowRadius = Math.max(1, Math.max(w, h) * .72f);
            glintMatrix.setScale(glowRadius, glowRadius);
            glintMatrix.postTranslate(w * lightX, h * lightY);
            glint.setLocalMatrix(glintMatrix);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), edge = dp(.75f);
            if (w <= 0 || h <= 0) return;
            prepareLighting();
            rect.set(0, 0, w, h);
            // Neutral clear glass: tint is independent of the opaque fallback
            // color, so an old caller's cream background cannot yellow it.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((Math.round(255f * (tintOpacity < 0 ? (night ? .4f : .26f) : tintOpacity)) << 24) | (night ? 0x00191C17 : 0x00FFFFFF));
            c.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(sheen); c.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(glint); c.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(null);

            // Soft inner shadow uses narrow nested rings, not an outer shadow
            // or a blurred parent layer, so it never softens child text.
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.1f));
            for (int i = 0; i < 4; i++) {
                float inset = dp(.9f + i * .72f);
                if (2 * inset >= Math.min(w, h)) break;
                rect.set(inset, inset, w - inset, h - inset);
                int alpha = Math.max(1, (night ? 11 : 7) - i * 2);
                paint.setColor(alpha << 24);
                c.drawRoundRect(rect, Math.max(0, radius - inset), Math.max(0, radius - inset), paint);
            }

            rect.set(edge, edge, w - edge, h - edge);
            paint.setStrokeWidth(edge);
            paint.setColor(0xFFFFFFFF); paint.setShader(brightEdge);
            c.drawRoundRect(rect, Math.max(0, radius - edge), Math.max(0, radius - edge), paint);
            rect.inset(dp(1.1f), dp(1.1f));
            paint.setStrokeWidth(dp(.65f)); paint.setShader(darkEdge);
            c.drawRoundRect(rect, Math.max(0, radius - dp(1.85f)), Math.max(0, radius - dp(1.85f)), paint);
            paint.setShader(null); paint.setStyle(Paint.Style.FILL);
        }
    }

    /** New graphics references stay behind an API guard for Android 8-12. */
    @TargetApi(33)
    private static final class Api33 {
        // The input BitmapShader already maps the fixed material texture into
        // panel-local pixels. Only refraction runs here; there is no enlarged
        // offscreen layer or RenderEffect chain to rebuild during a gesture.
        private static final String LENS = "uniform shader content; uniform float2 size; " +
                "uniform float strength; uniform float band; uniform float corner; uniform float2 light; " +
                "uniform float4 capsule; uniform float localEnabled; uniform float inset; " +
                // One signed-distance evaluation also supplies its analytic
                // normal. sign(0) keeps symmetry axes stable; the clamped
                // denominator and explicit inside branch avoid zero division.
                "float3 roundedBox(float2 p,float2 center,float2 h,float r) { " +
                "float2 v=p-center; float2 q=abs(v)-(h-float2(r)); " +
                "float2 outside=max(q,float2(0)); float len=length(outside); " +
                "float d=len+min(max(q.x,q.y),0.0)-r; " +
                "float2 normal=outside/max(len,.0001); " +
                "if(len<=.0001) { " +
                "if(q.x>q.y) normal=float2(1,0); " +
                "else if(q.y>q.x) normal=float2(0,1); " +
                "else normal=float2(.70710678); } " +
                "return float3(d,normal*sign(v)); } " +
                "half4 main(float2 p) { float2 h=max(size*.5,float2(1)); " +
                "float3 outer=roundedBox(p,h,h,min(corner,min(h.x,h.y))); " +
                "float edge=1.0-smoothstep(0.0,band,max(-outer.x,0.0)); " +
                "float2 n=outer.yz; " +
                "float2 l=(light*size-p)/max(length(light*size-p),1.0); " +
                "float incidence=.82+.18*max(dot(n,l),0.0); " +
                "float2 localN=float2(0); float localWeight=0.0; float2 localShift=float2(0); " +
                // The local capsule stays symmetric when direction reverses.
                "if(localEnabled>.5) { float span=max(abs(capsule.x-capsule.y)*size.x,1.0); " +
                "float halfH=max(1.0,min((size.y*.5-inset)*capsule.z,size.y*.5-1.0)); " +
                "float2 center=float2((capsule.x+capsule.y)*size.x*.5,size.y*.5); " +
                "float3 local=roundedBox(p,center,float2(span*.5,halfH),min(halfH,span*.5)); " +
                "float inside=1.0-smoothstep(-.75,.75,local.x); " +
                "float localBand=max(1.0,min(band*.55,size.y*.22)); " +
                "float localEdge=1.0-smoothstep(0.0,localBand,max(-local.x,0.0)); " +
                "localN=local.yz; localWeight=inside*(.12+.88*localEdge*localEdge); " +
                "localShift=localN*strength*(.65+.42*capsule.w)*localWeight+(p-center)*.018*inside; } " +
                "float2 q=clamp(p-n*strength*edge*edge*incidence-localShift,float2(.5),size-float2(.5)); " +
                "float spread=min(strength*.055,.65)*edge; " +
                "float2 dispersion=n*spread+localN*(.24*localWeight); " +
                // Interior pixels need one sample. The two extra chromatic
                // samples only matter at a visible refractive rim (> .12 px).
                "half4 mid=content.eval(q); if(dot(dispersion,dispersion)<.0144) return mid; " +
                "half r=content.eval(q-dispersion).r; " +
                "half b=content.eval(q+dispersion).b; return half4(r,mid.g,b,mid.a); }";
        private final android.graphics.RuntimeShader lens = new android.graphics.RuntimeShader(LENS);
        private android.graphics.Shader boundContent;
        private float lastStrength, lastBand, lastCorner, lastX, lastY;
        private float lastHead, lastTail, lastSquash, lastActivity, lastInset;
        private boolean initialized, lastLocalEnabled;
        private int width, height;

        Api33() {}

        static void linear(android.graphics.BitmapShader shader) {
            shader.setFilterMode(android.graphics.BitmapShader.FILTER_MODE_LINEAR);
        }

        android.graphics.Shader apply(android.graphics.Shader content,
                   float strength, float band, float corner, float x, float y, int w, int h,
                   boolean localEnabled, float head, float tail, float squash, float activity, float inset) {
            w = Math.max(1, w); h = Math.max(1, h);
            float effectiveBand = Math.max(1, Math.min(band, Math.min(w, h) * .42f));
            if (content != boundContent) {
                lens.setInputShader("content", content);
                boundContent = content;
            }
            if (!initialized || width != w || height != h) {
                lens.setFloatUniform("size", w, h);
                width = w; height = h;
            }
            if (!initialized || lastStrength != strength) {
                lens.setFloatUniform("strength", strength);
                lastStrength = strength;
            }
            if (!initialized || lastBand != effectiveBand) {
                lens.setFloatUniform("band", effectiveBand);
                lastBand = effectiveBand;
            }
            if (!initialized || lastCorner != corner) {
                lens.setFloatUniform("corner", corner);
                lastCorner = corner;
            }
            if (!initialized || lastX != x || lastY != y) {
                lens.setFloatUniform("light", x, y);
                lastX = x; lastY = y;
            }
            if (!initialized || lastHead != head || lastTail != tail
                    || lastSquash != squash || lastActivity != activity) {
                lens.setFloatUniform("capsule", head, tail, squash, activity);
                lastHead = head; lastTail = tail; lastSquash = squash; lastActivity = activity;
            }
            if (!initialized || lastLocalEnabled != localEnabled) {
                lens.setFloatUniform("localEnabled", localEnabled ? 1f : 0f);
                lastLocalEnabled = localEnabled;
            }
            if (!initialized || lastInset != inset) {
                lens.setFloatUniform("inset", inset);
                lastInset = inset;
            }
            initialized = true;
            return lens;
        }
    }
}
