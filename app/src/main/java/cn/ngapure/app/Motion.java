package cn.ngapure.app;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

/** Small native animations; no perpetual shimmer or content-wide rubber motion. */
public final class Motion {
    private Motion() { }
    private static final TimeInterpolator EASE = new PathInterpolator(.2f, .0f, .0f, 1f);

    public static boolean enabled(Context context) {
        return ValueAnimator.areAnimatorsEnabled(); // API 26, app's minimum.
    }

    /** Installs optional pressure feedback without consuming taps or scrolls. */
    public static void press(View view) {
        view.setOnTouchListener((v, e) -> {
            if (!enabled(v.getContext())) {
                v.animate().cancel(); v.setScaleX(1); v.setScaleY(1); return false;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().cancel();
                    v.animate().scaleX(.99f).scaleY(.99f).setDuration(75)
                            .setInterpolator(EASE).start(); break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_OUTSIDE:
                    release(v); break;
                case MotionEvent.ACTION_MOVE:
                    if (e.getX() < 0 || e.getY() < 0 || e.getX() > v.getWidth() || e.getY() > v.getHeight())
                        release(v);
                    break;
                default: break;
            }
            return false;
        });
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }
            @Override public void onViewDetachedFromWindow(View v) {
                v.animate().cancel(); v.setScaleX(1); v.setScaleY(1);
            }
        });
    }

    private static void release(View v) {
        v.animate().cancel();
        v.animate().scaleX(1).scaleY(1).setDuration(160).setInterpolator(EASE).start();
    }

    /** Optional light fade, preserving the content's position. */
    public static void enter(View view) {
        view.animate().cancel();
        if (!enabled(view.getContext())) {
            view.setAlpha(1); view.setTranslationY(0); return;
        }
        view.setTranslationY(0); view.setAlpha(.92f);
        view.animate().alpha(1).setDuration(120).setInterpolator(EASE).start();
    }

    /** Drawer movement. A negative distance enters/exits from the left. */
    public static void slide(View view, boolean opening, float distanceDp, Runnable end) {
        view.animate().cancel();
        float distance = dp(view, distanceDp);
        if (!enabled(view.getContext())) {
            view.setTranslationX(opening ? 0 : distance);
            if (end != null) end.run(); return;
        }
        if (opening) view.setTranslationX(distance);
        view.animate().translationX(opening ? 0 : distance)
                .setDuration(220).setInterpolator(EASE)
                .withEndAction(end).start();
    }

    /** Selection indicator movement in pixels; caller owns the indicator layout. */
    public static void moveX(View view, float xPixels, Runnable end) {
        view.animate().cancel();
        if (!enabled(view.getContext())) {
            view.setTranslationX(xPixels); if (end != null) end.run(); return;
        }
        view.animate().translationX(xPixels).setDuration(180).setInterpolator(EASE)
                .withEndAction(end).start();
    }

    private static float dp(View view, float n) {
        return n * view.getResources().getDisplayMetrics().density;
    }
}
