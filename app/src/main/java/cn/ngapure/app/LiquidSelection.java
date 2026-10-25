package cn.ngapure.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.PrintWriter;
import java.util.Locale;

/**
 * A small glass selection surface, with a separate readable text layer.
 * No screenshots, backdrop blur, network calls or third-party dependencies.
 *
 * User selections notify once on release/click, only if the committed index changed.
 * Programmatic setItems/setSelection never notify. Give this view at least 48 dp
 * in height; WRAP_CONTENT supplies that minimum automatically.
 */
public final class LiquidSelection extends FrameLayout {
    public interface OnSelected { void onSelected(int index); }
    public interface OnReselected { void onReselected(int index); }
    public interface OnInteraction { void onInteraction(float normalizedX, float normalizedY); }
    public interface OnShapeListener {
        void onShape(float leftEdgeNormalized, float rightEdgeNormalized, float heightScale, float activity);
    }

    private final LiquidMotion motion = new LiquidMotion();
    private final LiquidMotion.Trace motionTrace = new LiquidMotion.Trace(1024);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final RectF lens = new RectF();
    private final Path lensPath = new Path();
    private final Matrix fillMatrix = new Matrix(), rimMatrix = new Matrix(), innerMatrix = new Matrix();
    private LinearGradient lensFill, lensRim, lensInner;
    private boolean gradientsDirty = true;
    private final int touchSlop;
    private OnSelected callback;
    private OnReselected reselectedListener;
    private OnInteraction interactionListener;
    private OnShapeListener shapeListener;
    private String[] items = new String[0];
    private String[] descriptions = new String[0];
    private String[] glyphs;
    private int selectedIndex = -1;
    private int targetIndex = -1;
    // Logical item coordinates keep both elastic ends valid through width/RTL changes.
    private float head, tail;
    private float heightScale = 1f, activity;
    private boolean nightMode;
    private boolean motionEnabled = true;
    private boolean customTint;
    private int tintColor = 0xFF7B5F31;
    private boolean framePosted;
    private boolean detached = true, publishingShape, shapePublishPending;
    private long motionGeneration;
    private final Choreographer.FrameCallback frameCallback = this::onFrame;

    private int pointerId = MotionEvent.INVALID_POINTER_ID;
    private boolean tracking, dragging, verticalGesture, multiPointerGesture, attachToFinger;
    private long gestureDownTime = -1;
    private float downX, downY, lastX, downHead;

    public LiquidSelection(Context context) { this(context, null); }
    public LiquidSelection(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public LiquidSelection(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setWillNotDraw(false);
        setMinimumHeight(dp(48));
        setClipChildren(false);
        setClipToPadding(false);
        setMotionEventSplittingEnabled(false);
        // Non-text shadow layers are hardware supported from API 28. Keep the
        // compatibility bitmap only where needed, rather than uploading it every frame.
        if (android.os.Build.VERSION.SDK_INT < 28) setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setFocusable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setItems(String[] labels, int initialIndex, OnSelected listener) {
        cancelGesture();
        cancelAnimation();
        items = labels == null ? new String[0] : labels.clone();
        callback = listener;
        selectedIndex = items.length == 0 ? -1 : clampIndex(initialIndex);
        targetIndex = selectedIndex;
        descriptions = new String[items.length];
        configureMotion();
        snapGeometry(Math.max(0, selectedIndex));
        rebuildItems();
    }

    /** Optional icon names from IconView: icon + 12 sp label, with a 64 dp minimum height. */
    public void setIcons(String[] glyphNames) {
        glyphs = glyphNames == null ? null : glyphNames.clone();
        setMinimumHeight(dp(preferredHeightDp()));
        rebuildItems();
    }

    /** Give large system text the same centered padding as the default icon dock. */
    public int preferredHeightDp() {
        float scale = Math.max(1f, getResources().getConfiguration().fontScale);
        return glyphs == null ? Math.max(48, Math.round(28 + 20 * scale))
                : Math.max(64, Math.round(46 + 18 * scale));
    }

    /** Coordinates are local to this component, normalized to 0..1. */
    public void setOnInteractionListener(OnInteraction listener) { interactionListener = listener; }

    public void setOnReselectedListener(OnReselected listener) { reselectedListener = listener; }

    public void setOnShapeListener(OnShapeListener listener) {
        shapeListener = listener;
        publishShape();
    }

    /** Updates only this label: keeps the child, accessibility focus and running springs. */
    public void setItemLabel(int index, String label) {
        if (index < 0 || index >= items.length) return;
        items[index] = label == null ? "" : label;
        ItemSlot slot = (ItemSlot) getChildAt(index);
        if (slot != null) {
            slot.label.setText(items[index]);
            if (descriptions[index] == null) slot.setContentDescription(items[index]);
        }
    }

    public void setItemDescription(int index, String description) {
        if (index < 0 || index >= items.length) return;
        descriptions[index] = description;
        View slot = getChildAt(index);
        if (slot != null) slot.setContentDescription(description == null ? items[index] : description);
    }

    private void rebuildItems() {
        removeAllViews();
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            ItemSlot slot = new ItemSlot(getContext(), index);
            slot.setClickable(true);
            slot.setFocusable(true);
            slot.setEnabled(isEnabled());
            slot.setContentDescription(descriptions[i] == null ? items[i] : descriptions[i]);
            slot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            slot.setOnClickListener(v -> commitClick(index, slot.consumeUserActivation()));
            addView(slot, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        }
        updateLabels();
        requestLayout();
        invalidate();
    }

    public void setNightMode(boolean night) {
        nightMode = night;
        gradientsDirty = true;
        if (!customTint) tintColor = night ? 0xFFD2BB88 : 0xFF7B5F31;
        updateLabels();
        invalidate();
    }

    /** Changes the subtle rim tint; text keeps its own high-contrast foreground. */
    public void setTintColor(int color) {
        customTint = true;
        tintColor = color | 0xFF000000;
        gradientsDirty = true;
        updateLabels();
        invalidate();
    }

    /** Programmatic selection, without calling OnSelected. */
    public void setSelection(int index, boolean animate) {
        cancelGesture();
        if (items.length == 0) return;
        settle(clampIndex(index), animate, false);
    }

    public int getSelection() { return selectedIndex; }

    boolean isTrackingTouch() { return tracking; }

    public void setMotionEnabled(boolean enabled) {
        motionEnabled = enabled;
        if (!enabled) {
            cancelGesture();
            cancelAnimation();
            finishSelection(targetIndex);
        }
    }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).setEnabled(enabled);
        if (!enabled) {
            cancelGesture();
            cancelAnimation();
            snapGeometry(Math.max(0, selectedIndex));
        }
        setAlpha(enabled ? 1f : .5f);
        publishShape();
        invalidate();
    }

    private boolean motionAllowed() {
        return motionEnabled && isEnabled() && !detached && isAttachedToWindow() && isShown()
                && getWindowVisibility() == VISIBLE && ValueAnimator.areAnimatorsEnabled();
    }

    private void commitClick(int index, boolean realTap) {
        boolean reselected = index == selectedIndex;
        settle(index, true, true);
        OnReselected listener = reselectedListener;
        if (reselected && realTap && isEnabled() && listener != null) listener.onReselected(index);
    }

    private void settle(int index, boolean animate, boolean notify) {
        if (items.length == 0 || !isEnabled()) return;
        long now = System.nanoTime();
        advanceMotion(now);
        recordTrace(LiquidMotion.Trace.TARGET_BEFORE, now);
        int destination = clampIndex(index);
        motionGeneration++;
        targetIndex = destination;
        boolean changed = selectedIndex != destination;
        selectedIndex = destination;
        motion.redirect(destination); // Preserve geometric state, velocity and the simulation clock.
        syncMotion();
        updateLabels();
        recordTrace(LiquidMotion.Trace.TARGET_AFTER, now);
        if (!animate || !motionAllowed()) finishSelection(destination);
        else if (!motion.active()) finishSelection(destination);
        else requestFrame();
        dispatchSelection(changed, notify, destination);
    }

    private void dispatchSelection(boolean changed, boolean notify, int index) {
        if (!changed) return;
        View slot = getChildAt(index);
        if (slot != null) slot.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
        OnSelected listener = callback;
        if (notify && listener != null) listener.onSelected(index);
    }

    private void configureMotion() {
        float cell = cellWidth();
        motion.configure(items.length, cell > 0 ? baseLensWidth() / cell : .9f);
        syncMotion();
    }

    private void syncMotion() {
        head = motion.head();
        tail = motion.tail();
        heightScale = motion.height();
        activity = motion.activity();
    }

    private void advanceMotion(long now) { motion.advanceTo(now); syncMotion(); }

    private void snapGeometry(float position) {
        motion.snap(position, System.nanoTime());
        syncMotion();
    }

    private void finishSelection(int destination) {
        cancelAnimation();
        if (items.length == 0 || destination < 0) return;
        targetIndex = clampIndex(destination);
        snapGeometry(targetIndex);
        recordTrace(LiquidMotion.Trace.STOP, System.nanoTime());
        publishShape();
        invalidate();
    }

    private void cancelAnimation() {
        motionGeneration++;
        if (framePosted) Choreographer.getInstance().removeFrameCallback(frameCallback);
        framePosted = false;
    }

    private void requestFrame() {
        if (framePosted || !motionAllowed() || items.length == 0) return;
        framePosted = true;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void onFrame(long frameNanos) {
        framePosted = false;
        if (!motionAllowed()) { cancelGesture(); finishSelection(selectedIndex); return; }
        long generation = motionGeneration;
        advanceMotion(frameNanos);
        boolean moving = motion.active();
        if (!moving) {
            if (!dragging) { finishSelection(targetIndex); return; }
            motion.rest(frameNanos);
            syncMotion();
        }
        recordTrace(LiquidMotion.Trace.FRAME, frameNanos);
        publishShape();
        invalidate();
        if (generation != motionGeneration) return;
        if (moving) requestFrame();
    }

    private float baseLensWidth() { return Math.max(1f, cellWidth() - dp(8)); }

    /** Begin a bounded diagnostic capture. No account/content/touch coordinates are recorded. */
    void startMotionTrace() {
        advanceMotion(System.nanoTime());
        motionTrace.start();
        recordTrace(LiquidMotion.Trace.START, System.nanoTime());
    }

    /** Dumping stops capture; string formatting and I/O happen only on this explicit request. */
    void dumpMotionTrace(PrintWriter out, String prefix) { motionTrace.dump(out, prefix == null ? "" : prefix); }

    private void recordTrace(int event, long now) {
        if (!motionTrace.enabled()) return;
        updateGeometry();
        motionTrace.record(event, Math.max(now, motion.timeNanos()), motion, selectedIndex, dragging,
                lens.left / Math.max(1, getWidth()), lens.right / Math.max(1, getWidth()), lens.width(), lens.height());
    }

    private void publishShape() {
        updateGeometry();
        if (publishingShape) { shapePublishPending = true; return; }
        publishingShape = true;
        try {
            OnShapeListener previousListener = null;
            float previousHead = -1, previousTail = -1, previousHeight = -1, previousActivity = -1;
            // Merge a synchronous setter into one final publication, never recurse.
            for (int pass = 0; pass < 2; pass++) {
                updateGeometry();
                OnShapeListener listener = shapeListener;
                if (listener == null || items.length == 0 || getWidth() <= 0) return;
                float headEdge = unit(lens.left / getWidth());
                float tailEdge = unit(lens.right / getWidth());
                if (listener == previousListener && Math.abs(headEdge - previousHead) < .00001f
                        && Math.abs(tailEdge - previousTail) < .00001f
                        && Math.abs(heightScale - previousHeight) < .00001f
                        && Math.abs(activity - previousActivity) < .00001f) break;
                previousListener = listener;
                previousHead = headEdge;
                previousTail = tailEdge;
                previousHeight = heightScale;
                previousActivity = activity;
                shapePublishPending = false;
                listener.onShape(headEdge, tailEdge, heightScale, activity);
                if (!shapePublishPending) break;
            }
        } finally {
            publishingShape = false;
            shapePublishPending = false;
        }
    }

    /** Read-only geometry evidence; contains no account, content or touch coordinates. */
    String motionState() {
        updateGeometry();
        return String.format(Locale.US,
                "head=%.4f tail=%.4f width=%.2f height=%.2f heightScale=%.3f activity=%.3f headV=%.4f tailV=%.4f bias=%.4f animating=%s dragging=%s",
                head, tail, lens.width(), lens.height(), heightScale, activity, motion.headVelocity(),
                motion.tailVelocity(), motion.bias(), framePosted, dragging);
    }

    private void updateLabels() {
        int foreground = readableTint();
        int secondary = nightMode ? 0xFFBFC3B7 : 0xFF656D5D;
        for (int i = 0; i < getChildCount(); i++) {
            ItemSlot slot = (ItemSlot) getChildAt(i);
            slot.setSelected(i == selectedIndex);
            slot.label.setSelected(i == selectedIndex);
            slot.label.setTextColor(i == selectedIndex ? foreground : secondary);
            slot.label.setTypeface(Typeface.DEFAULT, i == selectedIndex ? Typeface.BOLD : Typeface.NORMAL);
            if (slot.glyph != null) slot.glyph.setColor(i == selectedIndex ? foreground : secondary);
        }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int desiredWidth = Math.max(getSuggestedMinimumWidth(), dp(72) * items.length + getPaddingLeft() + getPaddingRight());
        int desiredHeight = Math.max(getSuggestedMinimumHeight(), dp(preferredHeightDp()) + getPaddingTop() + getPaddingBottom());
        int width = resolveSize(desiredWidth, widthSpec);
        int height = resolveSize(desiredHeight, heightSpec);
        setMeasuredDimension(width, height);
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int childHeight = Math.max(0, height - getPaddingTop() - getPaddingBottom());
        for (int i = 0; i < getChildCount(); i++) {
            int left = Math.round(i * available / (float) Math.max(1, items.length));
            int right = Math.round((i + 1) * available / (float) Math.max(1, items.length));
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(right - left, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY));
        }
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        float cell = cellWidth();
        int childBottom = getHeight() - getPaddingBottom();
        for (int i = 0; i < getChildCount(); i++) {
            int slot = isRtl() ? items.length - 1 - i : i;
            int childLeft = getPaddingLeft() + Math.round(slot * cell);
            int childRight = getPaddingLeft() + Math.round((slot + 1) * cell);
            getChildAt(i).layout(childLeft, getPaddingTop(), childRight, childBottom);
        }
        publishShape();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        // Fractional item coordinates survive resizing and rotation without stale pixel targets.
        configureMotion();
        if (dragging) { downX = lastX; downHead = motion.target(); }
        publishShape();
        invalidate();
    }

    private void updateGeometry() {
        track.set(getPaddingLeft(), getPaddingTop(), getWidth() - getPaddingRight(), getHeight() - getPaddingBottom());
        float cell = cellWidth();
        float logicalLeft = motion.leftCenter(), logicalRight = motion.rightCenter();
        float leftCenter = track.left + ((isRtl() ? items.length - 1 - logicalRight : logicalLeft) + .5f) * cell;
        float rightCenter = track.left + ((isRtl() ? items.length - 1 - logicalLeft : logicalRight) + .5f) * cell;
        float halfWidth = baseLensWidth() / 2f;
        float halfHeight = Math.max(0f, track.height() - dp(12)) * heightScale / 2f;
        float cy = track.centerY();
        lens.set(leftCenter - halfWidth, cy - halfHeight, rightCenter + halfWidth, cy + halfHeight);
        lensPath.reset();
        if (lens.width() <= 0 || halfHeight <= 0) return;
        // Radius weights and the neck stay continuous while the lobes cross at reversal.
        float leftRadius = halfHeight * motion.leftRadiusScale(isRtl());
        float rightRadius = halfHeight * motion.rightRadiusScale(isRtl());
        float rx = Math.min(halfHeight, lens.width() / 2f);
        float x0 = lens.left + rx, x1 = lens.right - rx;
        float middle = (x0 + x1) / 2f, span = Math.max(0f, x1 - x0);
        float neck = halfHeight * motion.neckScale();
        float k = .55228475f;
        lensPath.moveTo(x0, cy - leftRadius);
        lensPath.cubicTo(x0 + span * .22f, cy - leftRadius, middle - span * .18f, cy - neck, middle, cy - neck);
        lensPath.cubicTo(middle + span * .18f, cy - neck, x1 - span * .22f, cy - rightRadius, x1, cy - rightRadius);
        lensPath.cubicTo(x1 + rx * k, cy - rightRadius, lens.right, cy - rightRadius * k, lens.right, cy);
        lensPath.cubicTo(lens.right, cy + rightRadius * k, x1 + rx * k, cy + rightRadius, x1, cy + rightRadius);
        lensPath.cubicTo(x1 - span * .22f, cy + rightRadius, middle + span * .18f, cy + neck, middle, cy + neck);
        lensPath.cubicTo(middle - span * .18f, cy + neck, x0 + span * .22f, cy + leftRadius, x0, cy + leftRadius);
        lensPath.cubicTo(x0 - rx * k, cy + leftRadius, lens.left, cy + leftRadius * k, lens.left, cy);
        lensPath.cubicTo(lens.left, cy - leftRadius * k, x0 - rx * k, cy - leftRadius, x0, cy - leftRadius);
        lensPath.close();
    }

    private void prepareGradients() {
        if (!gradientsDirty) return;
        gradientsDirty = false;
        lensFill = new LinearGradient(0, 0, 0, 1,
                nightMode ? new int[]{0x56585E50, 0x3D2B3026} : new int[]{0x59FFFFFF, 0x35FFFFFF}, null, Shader.TileMode.CLAMP);
        lensRim = new LinearGradient(0, 0, 1, 1,
                nightMode ? new int[]{0xC3F2F1E6, 0x21FFFFFF} : new int[]{0xF4FFFFFF, 0x46FFFFFF}, null, Shader.TileMode.CLAMP);
        lensInner = new LinearGradient(0, 0, 1, 1,
                new int[]{0x24FFFFFF, withAlpha(tintColor, nightMode ? 51 : 24)}, null, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        updateGeometry();
        if (track.width() <= 0 || track.height() <= 0 || items.length == 0) return;
        paint.setShader(null);
        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(nightMode ? 0x181C2018 : 0x16E9EADF);
        canvas.drawRoundRect(track, track.height() / 2f, track.height() / 2f, paint);
        if (lens.isEmpty()) return;
        prepareGradients();
        paint.setShadowLayer(dp(6), 0, dpFloat(1.5f), nightMode ? 0x33000000 : 0x181B2216);
        // Neutral translucent glass leaves the parent panel's local refraction visible.
        fillMatrix.setScale(1, lens.height());fillMatrix.postTranslate(0, lens.top);
        lensFill.setLocalMatrix(fillMatrix);paint.setShader(lensFill);
        canvas.drawPath(lensPath, paint);
        paint.clearShadowLayer();
        // Bias is itself damped, so the light cannot flip sides when the two
        // spring channels cross during a quick reversal.
        float lightX = motion.center() + motion.bias() * .15f;
        float lightSlot = isRtl() ? items.length - 1 - lightX : lightX;
        float lightCenter = track.left + (lightSlot + .5f) * cellWidth();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dpFloat(1f));
        rimMatrix.setScale(lens.width() * .9f, lens.height());rimMatrix.postTranslate(lightCenter - lens.width() * .45f, lens.top);
        lensRim.setLocalMatrix(rimMatrix);paint.setShader(lensRim);
        canvas.drawPath(lensPath, paint);
        int save = canvas.save();
        canvas.scale(Math.max(0f, (lens.width() - dpFloat(2.4f)) / lens.width()),
                Math.max(0f, (lens.height() - dpFloat(2.4f)) / lens.height()), lens.centerX(), lens.centerY());
        paint.setStrokeWidth(dpFloat(.55f));
        innerMatrix.setScale(lens.width(), lens.height());innerMatrix.postTranslate(lens.left, lens.top);
        lensInner.setLocalMatrix(innerMatrix);paint.setShader(lensInner);
        canvas.drawPath(lensPath, paint);
        canvas.restoreToCount(save);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        OnInteraction listener = interactionListener;
        if (listener != null && isEnabled()) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                int pointer = event.findPointerIndex(pointerId);
                if (pointer < 0) pointer = 0;
                listener.onInteraction(unit(event.getX(pointer) / Math.max(1, getWidth())),
                        unit(event.getY(pointer) / Math.max(1, getHeight())));
            } else if (action == MotionEvent.ACTION_CANCEL) listener.onInteraction(.5f, .5f);
        }
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!isEnabled() || items.length == 0) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginGesture(event);
                return false; // Each label retains its own click and accessibility action.
            case MotionEvent.ACTION_MOVE:
                if (tracking && !verticalGesture) resolveDirection(event);
                if (dragging) updateDragPosition(event);
                return dragging;
            case MotionEvent.ACTION_POINTER_DOWN:
                multiPointerGesture = true;
                for (int i = 0; i < getChildCount(); i++) ((ItemSlot) getChildAt(i)).disqualifyTap();
                return dragging;
            case MotionEvent.ACTION_POINTER_UP:
                replacePointer(event);
                return dragging;
            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    cancelGesture();
                    // A following child click cancels this return animation at the same visual position.
                    settle(selectedIndex, true, false);
                }
                return dragging;
            case MotionEvent.ACTION_CANCEL:
                cancelGesture();
                settle(selectedIndex, true, false);
                return false;
            default: return dragging;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || items.length == 0) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginGesture(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) return false;
                resolveDirection(event);
                if (verticalGesture) return false;
                if (dragging) updateDragPosition(event);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                multiPointerGesture = true;
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                replacePointer(event);
                return true;
            case MotionEvent.ACTION_UP:
                if (!tracking) return false;
                boolean wasDrag = dragging;
                boolean wasVertical = verticalGesture;
                if (wasDrag) updateDragPosition(event);
                // Commit the tab under the released finger. The elastic grab offset
                // and a lagging lobe must never choose its neighbour instead.
                int nearest = indexAt(event.getX());
                boolean realTap = !wasDrag && !wasVertical && !multiPointerGesture
                        && Math.abs(event.getX() - downX) <= touchSlop && Math.abs(event.getY() - downY) <= touchSlop;
                recordTrace(LiquidMotion.Trace.RELEASE, event.getEventTime() * 1_000_000L);
                cancelGesture();
                if (!wasVertical) {
                    if (!wasDrag) performClick();
                    if (wasDrag) settle(nearest, true, true);
                    else commitClick(nearest, realTap);
                } else settle(selectedIndex, true, false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelGesture();
                settle(selectedIndex, true, false);
                return true;
            default: return tracking;
        }
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private void beginGesture(MotionEvent event) {
        if (tracking && gestureDownTime == event.getDownTime()) return;
        long now = event.getEventTime() * 1_000_000L;
        advanceMotion(now);
        recordTrace(LiquidMotion.Trace.DOWN_BEFORE, now);
        // DOWN only arbitrates this gesture. An existing return spring keeps its clock
        // and velocity until horizontal ownership has actually been established.
        tracking = true;
        dragging = false;
        verticalGesture = false;
        multiPointerGesture = false;
        gestureDownTime = event.getDownTime();
        pointerId = event.getPointerId(0);
        downX = lastX = event.getX(0);
        downY = event.getY(0);
        downHead = head;
        updateGeometry();
        attachToFinger = !lens.contains(downX, downY);
        recordTrace(LiquidMotion.Trace.DOWN_AFTER, now);
    }

    private void beginHorizontalDrag(MotionEvent event, int pointer) {
        long now = event.getEventTime() * 1_000_000L;
        advanceMotion(now);
        float x = event.getX(pointer);
        float delta = cellWidth() > 0 ? (x - downX) / cellWidth() : 0;
        recordTrace(LiquidMotion.Trace.TARGET_BEFORE, now);
        if (attachToFinger) motion.beginDrag(positionAt(x), now);
        else motion.beginDragFrom(head, isRtl() ? -delta : delta, now);
        downX = lastX = x;
        downHead = motion.target();
        syncMotion();
        recordTrace(LiquidMotion.Trace.TARGET_AFTER, now);
        requestFrame();
    }

    private void resolveDirection(MotionEvent event) {
        if (!tracking || verticalGesture) return;
        int pointer = event.findPointerIndex(pointerId);
        if (pointer < 0) return;
        float dx = event.getX(pointer) - downX;
        float dy = event.getY(pointer) - downY;
        if (!dragging && Math.abs(dy) > touchSlop && Math.abs(dy) >= Math.abs(dx)) {
            verticalGesture = true;
            for (int i = 0; i < getChildCount(); i++) {
                ((ItemSlot) getChildAt(i)).cancelTouchClick();
            }
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        } else if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
            dragging = true;
            beginHorizontalDrag(event, pointer);
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        }
    }

    private void updateDragPosition(MotionEvent event) {
        int pointer = event.findPointerIndex(pointerId);
        if (pointer < 0 || cellWidth() <= 0) return;
        long now = event.getEventTime() * 1_000_000L;
        float x = event.getX(pointer);
        // Android batches moves on busy frames. Replaying their actual times gives
        // the reversal estimator the new direction before the next display frame.
        // The model ignores already-consumed samples, including intercept/touch
        // dispatch of the same MotionEvent. No event or point objects are allocated.
        if (motionAllowed()) {
            for (int i = 0; i < event.getHistorySize(); i++) {
                dragSample(event.getHistoricalX(pointer, i), event.getHistoricalEventTime(i) * 1_000_000L);
            }
        }
        dragSample(x, now);
        lastX = x;
        syncMotion();
        recordTrace(LiquidMotion.Trace.MOVE, now);
        requestFrame();
        publishShape();
        invalidate();
    }

    private void dragSample(float x, long now) {
        advanceMotion(now);
        float delta = (x - downX) / cellWidth();
        float desired = clampPosition(downHead + (isRtl() ? -delta : delta));
        if (!motionAllowed()) motion.snap(desired, now);
        else motion.dragTo(desired, now);
    }

    private void replacePointer(MotionEvent event) {
        int removed = event.getActionIndex();
        if (event.getPointerId(removed) != pointerId) return;
        int replacement = removed == 0 ? 1 : 0;
        if (replacement >= event.getPointerCount()) return;
        long now = event.getEventTime() * 1_000_000L;
        advanceMotion(now);
        pointerId = event.getPointerId(replacement);
        downX = lastX = event.getX(replacement);
        downY = event.getY(replacement);
        downHead = dragging ? motion.target() : head;
        motion.rebasePointer(now);
        recordTrace(LiquidMotion.Trace.POINTER, now);
    }

    private void cancelGesture() {
        tracking = false;
        dragging = false;
        verticalGesture = false;
        multiPointerGesture = false;
        pointerId = MotionEvent.INVALID_POINTER_ID;
        gestureDownTime = -1;
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
    }

    private float positionAt(float x) {
        if (cellWidth() <= 0) return head;
        float position = (x - getPaddingLeft()) / cellWidth() - .5f;
        return clampPosition(isRtl() ? items.length - 1 - position : position);
    }

    private int indexAt(float x) {
        if (cellWidth() <= 0) return Math.max(0, selectedIndex);
        int slot = clampIndex((int) Math.floor((x - getPaddingLeft()) / cellWidth()));
        return isRtl() ? items.length - 1 - slot : slot;
    }
    private int clampIndex(int index) { return Math.max(0, Math.min(items.length - 1, index)); }
    private float clampPosition(float value) { return Math.max(0, Math.min(items.length - 1, value)); }
    private float cellWidth() { return Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight()) / (float) Math.max(1, items.length); }
    private boolean isRtl() { return getLayoutDirection() == View.LAYOUT_DIRECTION_RTL; }
    private int dp(float value) { return Math.round(dpFloat(value)); }
    private float dpFloat(float value) { return value * getResources().getDisplayMetrics().density; }
    private static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }
    private static float unit(float value) { return Math.max(0, Math.min(1, value)); }

    private int readableTint() {
        int color = tintColor;
        int background = nightMode ? 0xFF454D3E : 0xFFFAF9F2;
        int ink = nightMode ? 0xFFF0EFE5 : 0xFF292F23;
        for (int i = 0; i < 8 && contrast(color, background) < 4.5f; i++) {
            color = Color.rgb((Color.red(color) * 3 + Color.red(ink)) / 4,
                    (Color.green(color) * 3 + Color.green(ink)) / 4,
                    (Color.blue(color) * 3 + Color.blue(ink)) / 4);
        }
        return color;
    }

    private static float contrast(int a, int b) {
        float x = Color.luminance(a), y = Color.luminance(b);
        return (Math.max(x, y) + .05f) / (Math.min(x, y) + .05f);
    }

    @Override protected void onDetachedFromWindow() {
        detached = true;
        cancelGesture();
        cancelAnimation();
        snapGeometry(Math.max(0, selectedIndex));
        publishShape();
        super.onDetachedFromWindow();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        detached = false;
        publishShape();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) {
            cancelGesture();
            cancelAnimation();
            snapGeometry(Math.max(0, selectedIndex));
        }
        publishShape();
    }

    private final class ItemSlot extends FrameLayout {
        private final int index;
        private boolean ignoreTouchUntilNextDown;
        private boolean touchTapEligible, realTapPending;
        private boolean explicitUserActivation;
        private float touchDownX, touchDownY;
        final TextView label;
        final IconView glyph;

        ItemSlot(Context context, int itemIndex) {
            super(context);
            index = itemIndex;
            label = new TextView(context);
            label.setText(items[index] == null ? "" : items[index]);
            label.setTextSize(glyphs == null ? 14 : 12);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setIncludeFontPadding(false);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            label.setFocusable(false);
            label.setClickable(false);
            if (glyphs == null) glyph = null;
            else {
                String name = index < glyphs.length && glyphs[index] != null ? glyphs[index] : "";
                glyph = new IconView(context, name);
                glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                glyph.setFocusable(false);
                glyph.setClickable(false);
                addView(glyph);
            }
            addView(label);
        }

        void disqualifyTap() { touchTapEligible = realTapPending = false; }
        boolean consumeUserActivation() {
            boolean user = realTapPending || explicitUserActivation;
            realTapPending = false;
            return user;
        }

        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action != AccessibilityNodeInfo.ACTION_CLICK) return super.performAccessibilityAction(action, arguments);
            boolean previous = explicitUserActivation;
            explicitUserActivation = isEnabled();
            try { return super.performAccessibilityAction(action, arguments); }
            finally { explicitUserActivation = previous; }
        }

        @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
            boolean confirm = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_SPACE;
            if (!confirm || event.isCanceled()) return super.onKeyUp(keyCode, event);
            boolean previous = explicitUserActivation;
            explicitUserActivation = isEnabled();
            try { return super.onKeyUp(keyCode, event); }
            finally { explicitUserActivation = previous; }
        }

        void cancelTouchClick() {
            disqualifyTap();
            ignoreTouchUntilNextDown = true;
            setPressed(false);
            cancelLongPress();
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                ignoreTouchUntilNextDown = false;
                touchTapEligible = true;
                realTapPending = false;
                touchDownX = event.getX();
                touchDownY = event.getY();
            } else if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) disqualifyTap();
            else if (action == MotionEvent.ACTION_MOVE && (Math.abs(event.getX() - touchDownX) > touchSlop
                    || Math.abs(event.getY() - touchDownY) > touchSlop)) disqualifyTap();
            else if (action == MotionEvent.ACTION_UP) {
                realTapPending = touchTapEligible && event.getX() >= 0 && event.getX() < getWidth()
                        && event.getY() >= 0 && event.getY() < getHeight();
                touchTapEligible = false;
            }
            if (ignoreTouchUntilNextDown) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.onTouchEvent(cancel);
                cancel.recycle();
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                    ignoreTouchUntilNextDown = false;
                return true;
            }
            return super.onTouchEvent(event);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(width, height);
            label.measure(MeasureSpec.makeMeasureSpec(Math.max(0, width - dp(12)), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(glyph == null ? height : labelHeight(), MeasureSpec.EXACTLY));
            if (glyph != null) glyph.measure(MeasureSpec.makeMeasureSpec(dp(23), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(23), MeasureSpec.EXACTLY));
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = getWidth(), height = getHeight();
            if (glyph == null) label.layout(dp(6), 0, Math.max(dp(6), width - dp(6)), height);
            else {
                int textHeight = labelHeight();
                int iconTop = Math.max(0, (height - dp(26) - textHeight) / 2);
                int iconLeft = (width - dp(23)) / 2;
                glyph.layout(iconLeft, iconTop, iconLeft + dp(23), iconTop + dp(23));
                int textTop = iconTop + dp(26);
                label.layout(dp(6), textTop, Math.max(dp(6), width - dp(6)), textTop + textHeight);
            }
        }

        private int labelHeight() {
            return Math.max(dp(18), label.getLineHeight());
        }

        @SuppressWarnings("deprecation") // API 26-compatible collection metadata.
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.RadioButton");
            info.setCheckable(true);
            info.setChecked(index == selectedIndex);
            info.setSelected(index == selectedIndex);
            info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(0, 1, index, 1, false, index == selectedIndex));
        }
    }
}
