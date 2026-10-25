package cn.ngapure.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;

/** Native static/animated image viewport with anchored pinch, pan and clamped edges. */
public final class NativePictureView extends ImageView {
    private final Matrix transform = new Matrix();
    private final ScaleGestureDetector scale;
    private final GestureDetector gesture;
    private float minimum = 1, zoom = 1, lastX, lastY;
    private int imageWidth, imageHeight, activePointer = MotionEvent.INVALID_POINTER_ID;
    private float[] pendingViewport;

    public NativePictureView(Context context) {
        super(context); setScaleType(ScaleType.MATRIX);
        setContentDescription("图片，双指缩放，双击放大或还原");
        scale = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                if (imageWidth <= 0) return false;
                changeZoom(zoom * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY()); return true;
            }
        });
        gesture = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onDoubleTap(MotionEvent event) {
                if (imageWidth > 0) changeZoom(zoom > minimum * 1.5f ? minimum : minimum * 3, event.getX(), event.getY());
                return true;
            }
        });
    }
    public void show(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) throw new IllegalArgumentException("无效图片");
        show(new BitmapDrawable(getResources(), bitmap));
    }
    /** Keeps AnimatedImageDrawable intact; ImageView continues to own its scheduling callback. */
    public void show(Drawable drawable) {
        if (drawable == null || drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0)
            throw new IllegalArgumentException("无效图片");
        stopAnimation(); imageWidth = drawable.getIntrinsicWidth(); imageHeight = drawable.getIntrinsicHeight();
        setImageDrawable(drawable); fit(); applyPendingViewport(); updateAnimation();
    }
    /** Small value-only state; no Drawable, Bitmap or old Activity is retained. */
    public float[] viewportState() {
        if (pendingViewport != null) return pendingViewport.clone();
        if (imageWidth <= 0 || imageHeight <= 0 || getWidth() <= 0 || getHeight() <= 0 || minimum <= 0) return null;
        Matrix inverse = new Matrix();
        if (!transform.invert(inverse)) return null;
        float[] center = {getWidth() / 2f, getHeight() / 2f}; inverse.mapPoints(center);
        float[] value = {Math.max(1, Math.min(6, zoom / minimum)), Math.max(0, Math.min(1, center[0] / imageWidth)),
                Math.max(0, Math.min(1, center[1] / imageHeight))};
        return validViewport(value) ? value : null;
    }
    /** May precede either asynchronous image delivery or first layout. */
    public void restoreViewport(float[] value) {
        if (!validViewport(value)) return;
        pendingViewport = value.clone(); applyPendingViewport();
    }
    private static boolean validViewport(float[] value) {
        if (value == null || value.length != 3) return false;
        for (float item : value) if (Float.isNaN(item) || Float.isInfinite(item)) return false;
        return value[0] >= 1 && value[0] <= 6 && value[1] >= 0 && value[1] <= 1 && value[2] >= 0 && value[2] <= 1;
    }
    private void applyPendingViewport() {
        if (pendingViewport == null || imageWidth <= 0 || imageHeight <= 0 || getWidth() <= 0 || getHeight() <= 0) return;
        float[] value = pendingViewport; pendingViewport = null;
        minimum = Math.min(getWidth() / (float) imageWidth, getHeight() / (float) imageHeight);
        zoom = minimum * value[0]; transform.reset(); transform.postScale(zoom, zoom);
        transform.postTranslate(getWidth() / 2f - value[1] * imageWidth * zoom,
                getHeight() / 2f - value[2] * imageHeight * zoom); clamp();
    }
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (imageWidth <= 0 || width <= 0 || height <= 0) return;
        if (oldWidth <= 0 || oldHeight <= 0) { fit(); applyPendingViewport(); return; }
        Matrix inverse = new Matrix();
        if (!transform.invert(inverse)) { fit(); return; }
        float[] center = {oldWidth / 2f, oldHeight / 2f}; inverse.mapPoints(center);
        float relative = minimum > 0 ? zoom / minimum : 1;
        minimum = Math.min(width / (float) imageWidth, height / (float) imageHeight);
        zoom = Math.max(minimum, Math.min(minimum * 6, minimum * relative));
        transform.reset(); transform.postScale(zoom, zoom);
        transform.postTranslate(width / 2f - center[0] * zoom, height / 2f - center[1] * zoom); clamp(); applyPendingViewport();
    }
    private void fit() {
        if (imageWidth <= 0 || getWidth() <= 0 || getHeight() <= 0) return;
        minimum = Math.min(getWidth() / (float) imageWidth, getHeight() / (float) imageHeight); zoom = minimum;
        transform.reset(); transform.postScale(zoom, zoom);
        transform.postTranslate((getWidth() - imageWidth * zoom) / 2, (getHeight() - imageHeight * zoom) / 2);
        setImageMatrix(transform);
    }
    private void changeZoom(float requested, float focusX, float focusY) {
        if (Float.isNaN(requested) || Float.isInfinite(requested) || zoom <= 0) return;
        float next = Math.max(minimum, Math.min(minimum * 6, requested));
        float factor = next / zoom; zoom = next; transform.postScale(factor, factor, focusX, focusY); clamp();
    }
    private void clamp() {
        if (imageWidth <= 0) return;
        RectF rect = new RectF(0, 0, imageWidth, imageHeight); transform.mapRect(rect);
        float dx = rect.width() < getWidth() ? (getWidth() - rect.width()) / 2 - rect.left
                : rect.left > 0 ? -rect.left : rect.right < getWidth() ? getWidth() - rect.right : 0;
        float dy = rect.height() < getHeight() ? (getHeight() - rect.height()) / 2 - rect.top
                : rect.top > 0 ? -rect.top : rect.bottom < getHeight() ? getHeight() - rect.bottom : 0;
        transform.postTranslate(dx, dy); setImageMatrix(transform);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (imageWidth <= 0) return super.onTouchEvent(event);
        scale.onTouchEvent(event); gesture.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            activePointer = event.getPointerId(0); lastX = event.getX(); lastY = event.getY();
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1 && !scale.isInProgress()) {
            int index = event.findPointerIndex(activePointer);
            if (index >= 0) {
                float x = event.getX(index), y = event.getY(index);
                transform.postTranslate(x - lastX, y - lastY); clamp(); lastX = x; lastY = y;
            }
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            int lifted = event.getActionIndex(), next = lifted == 0 ? 1 : 0;
            int index = event.getPointerId(lifted) == activePointer ? next : event.findPointerIndex(activePointer);
            if (index >= 0 && index < event.getPointerCount()) {
                activePointer = event.getPointerId(index); lastX = event.getX(index); lastY = event.getY(index);
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            activePointer = MotionEvent.INVALID_POINTER_ID;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            if (action == MotionEvent.ACTION_UP) performClick();
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (imageWidth <= 0) return;
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "放大图片"));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, "缩小图片"));
    }
    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (imageWidth > 0 && (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            changeZoom(action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? zoom * 2 : zoom / 2, getWidth() / 2f, getHeight() / 2f); return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }
    private void updateAnimation() {
        Drawable drawable = getDrawable();
        if (drawable instanceof Animatable) {
            if (isAttachedToWindow() && isShown() && getWindowVisibility() == View.VISIBLE) ((Animatable) drawable).start();
            else ((Animatable) drawable).stop();
        }
    }
    private void stopAnimation() { if (getDrawable() instanceof Animatable) ((Animatable) getDrawable()).stop(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); updateAnimation(); }
    @Override protected void onDetachedFromWindow() { stopAnimation(); super.onDetachedFromWindow(); }
    @Override public void onVisibilityAggregated(boolean visible) { super.onVisibilityAggregated(visible); updateAnimation(); }
}
