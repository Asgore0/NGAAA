package cn.ngapure.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * OEM WindowManager Extensions v1 bridge, with ordinary measured-window fallback.
 * Uses the same device-side listener contract as Jetpack, without a Kotlin runtime.
 * Place the activity's entire root inside this container so toolbar and dock avoid the hinge together.
 */
public final class FoldAdaptiveLayout extends FrameLayout {
    private final Activity activity;
    private final List<Feature> features = new ArrayList<>();
    private Consumer<Object> observer;
    private int subscriptionGeneration,layoutUpdateCount;
    private Object component;
    private Method remove;
    private boolean observing;
    private Rect safe = new Rect();
    private int windowWidth,windowHeight;
    private final int[] location = new int[2];
    private static final class Feature {
        final Rect bounds; final boolean separating;
        Feature(Rect rect, boolean split) { bounds = rect; separating = split; }
    }
    public FoldAdaptiveLayout(Activity context) { super(context); activity = context; }
    /** Visible activity rectangle intersected with the root's current hinge-safe pane. */
    public static Rect visibleSafeBounds(Activity owner) {
        Rect visible=new Rect(); owner.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
        FoldAdaptiveLayout adaptive=find(owner.getWindow().getDecorView());
        if(adaptive!=null&&adaptive.getWidth()>0&&adaptive.getHeight()>0) { Rect pane=adaptive.safeBoundsInWindow(); int[] onScreen=new int[2],inWindow=new int[2];View decor=owner.getWindow().getDecorView();decor.getLocationOnScreen(onScreen);decor.getLocationInWindow(inWindow);pane.offset(onScreen[0]-inWindow[0],onScreen[1]-inWindow[1]); if(!pane.isEmpty()) { Rect combined=new Rect(visible); if(combined.intersect(pane))return combined; } }
        return visible;
    }
    private static FoldAdaptiveLayout find(View view) {
        if(view instanceof FoldAdaptiveLayout)return (FoldAdaptiveLayout)view;
        if(view instanceof android.view.ViewGroup) { android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){FoldAdaptiveLayout found=find(group.getChildAt(i));if(found!=null)return found;} }
        return null;
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); connect(); }
    @Override protected void onDetachedFromWindow() { disconnect(); super.onDetachedFromWindow(); }
    private void connect() {
        if (observing) return;
        final int token=++subscriptionGeneration;
        observer=info->post(()->{if(token==subscriptionGeneration)accept(info);});
        try {
            ClassLoader loader = activity.getClassLoader();
            Class<?> provider = Class.forName("androidx.window.extensions.WindowExtensionsProvider", false, loader);
            Object extensions = provider.getMethod("getWindowExtensions").invoke(null);
            Class<?> contract = Class.forName("androidx.window.extensions.WindowExtensions", false, loader);
            component = contract.getMethod("getWindowLayoutComponent").invoke(extensions);
            if (component == null) return;
            Class<?> layout = Class.forName("androidx.window.extensions.layout.WindowLayoutComponent", false, loader);
            remove = layout.getMethod("removeWindowLayoutInfoListener", Consumer.class);
            layout.getMethod("addWindowLayoutInfoListener", Activity.class, Consumer.class).invoke(component, activity, observer);
            observing = true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) { component = null; remove = null; features.clear(); }
    }
    private void disconnect() {
        if (observing && component != null && remove != null) try { remove.invoke(component, observer); } catch (ReflectiveOperationException | RuntimeException ignored) { }
        subscriptionGeneration++; observing = false; component = null; remove = null; observer=null; features.clear();
    }
    private void accept(Object info) {
        if (!isAttachedToWindow() || info == null) return;
        List<Feature> next = new ArrayList<>();
        try {
            ClassLoader loader = activity.getClassLoader();
            Class<?> infoType = Class.forName("androidx.window.extensions.layout.WindowLayoutInfo", false, loader);
            Class<?> fold = Class.forName("androidx.window.extensions.layout.FoldingFeature", false, loader);
            Object raw = infoType.getMethod("getDisplayFeatures").invoke(info);
            if (raw instanceof List<?>) for (Object value : (List<?>) raw) {
                if (!fold.isInstance(value)) continue;
                Object bounds = fold.getMethod("getBounds").invoke(value), type = fold.getMethod("getType").invoke(value), state = fold.getMethod("getState").invoke(value);
                if (!(bounds instanceof Rect) || !(type instanceof Integer) || !(state instanceof Integer)) continue;
                // Public Extensions constants: TYPE_HINGE = 2, STATE_HALF_OPENED = 2.
                next.add(new Feature(new Rect((Rect) bounds), ((Integer) type) == 2 || ((Integer) state) == 2));
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException malformed) { next.clear(); }
        features.clear(); features.addAll(next); layoutUpdateCount++; requestLayout();
    }
    /** Window coordinates, useful for keeping a native prompt on the same safe pane. */
    public Rect safeBoundsInWindow() { getLocationInWindow(location); Rect result = new Rect(safe); result.offset(location[0], location[1]); return result; }
    /** Maps full-window system/IME insets onto the selected pane's real outer edges. */
    public int[] insetsForPane(int left,int top,int right,int bottom) {return FoldGeometry.paneInsets(windowWidth,windowHeight,safe.left,safe.top,safe.right,safe.bottom,left,top,right,bottom);}
    public boolean isExtensionAvailable() { return observing; }
    public int layoutUpdateCount() { return layoutUpdateCount; }
    public boolean hasSeparatingHinge() { for (Feature value : features) if (value.separating) return true; return false; }
    private void updateSafe(int width, int height) {
        Rect previous=new Rect(safe);windowWidth=width;windowHeight=height;
        safe.set(0, 0, width, height); getLocationInWindow(location);
        for (Feature feature : features) {
            Rect bounds = new Rect(feature.bounds); bounds.offset(-location[0], -location[1]);
            // Root may begin below status-bar insets while extension bounds span the whole window.
            if (bounds.top <= 0 && bounds.bottom >= height) { bounds.top = 0; bounds.bottom = height; }
            if (bounds.left <= 0 && bounds.right >= width) { bounds.left = 0; bounds.right = width; }
            int[] pane = FoldGeometry.safePane(width, height, bounds.left, bounds.top, bounds.right, bounds.bottom, feature.separating);
            Rect candidate = new Rect(pane[0], pane[1], pane[2], pane[3]);
            if (candidate.width() > 0 && candidate.height() > 0 && (long) candidate.width() * candidate.height() < (long) safe.width() * safe.height()) safe.set(candidate);
        }
        if(!safe.equals(previous))post(this::requestApplyInsets);
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec); updateSafe(width, height);
        int[] padding=insetsForPane(getPaddingLeft(),getPaddingTop(),getPaddingRight(),getPaddingBottom());
        int usableWidth = Math.max(0, safe.width() - padding[0] - padding[2]);
        int usableHeight = Math.max(0, safe.height() - padding[1] - padding[3]);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i); if (child.getVisibility() == GONE) continue;
            LayoutParams p = (LayoutParams) child.getLayoutParams();
            child.measure(getChildMeasureSpec(MeasureSpec.makeMeasureSpec(usableWidth, MeasureSpec.EXACTLY), p.leftMargin + p.rightMargin, p.width),
                    getChildMeasureSpec(MeasureSpec.makeMeasureSpec(usableHeight, MeasureSpec.EXACTLY), p.topMargin + p.bottomMargin, p.height));
        }
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        updateSafe(right - left, bottom - top);
        int[] padding=insetsForPane(getPaddingLeft(),getPaddingTop(),getPaddingRight(),getPaddingBottom());
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i); if (child.getVisibility() == GONE) continue;
            LayoutParams p = (LayoutParams) child.getLayoutParams();
            int x = safe.left + padding[0] + p.leftMargin, y = safe.top + padding[1] + p.topMargin;
            child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
        }
    }
}
