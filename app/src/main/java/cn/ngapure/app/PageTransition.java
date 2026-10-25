package cn.ngapure.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.widget.ImageView;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import java.util.Locale;

/** A short page handoff that owns and releases the outgoing view, including interrupted navigation. */
final class PageTransition {
    private ValueAnimator animator;
    private FrameLayout host;
    private View incoming, outgoing, shield;
    private boolean returning;
    private float progress;
    private final View.OnAttachStateChangeListener attach = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { finish(); }
    };
    private final View.OnLayoutChangeListener layout = (v,l,t,r,b,ol,ot,or,ob) -> {
        if (or > ol && ob > ot && (r-l != or-ol || b-t != ob-ot)) finish();
    };

    /** Transient bounded pixels only; never writes a screenshot or reattaches a closed page's live views. */
    static View capture(View source) {
        if(source==null||source.getWidth()<1||source.getHeight()<1)return null;
        Bitmap pixels=null;
        try{
            float scale=Math.min(1f,Math.min(1600f/Math.max(source.getWidth(),source.getHeight()),(float)Math.sqrt(2000000d/((double)source.getWidth()*source.getHeight()))));
            pixels=Bitmap.createBitmap(Math.max(1,Math.round(source.getWidth()*scale)),Math.max(1,Math.round(source.getHeight()*scale)),Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(pixels);canvas.scale(scale,scale);source.draw(canvas);return new Snapshot(source,pixels);
        }catch(RuntimeException|OutOfMemoryError unavailable){if(pixels!=null)pixels.recycle();return null;}
    }
    private static final class Snapshot extends ImageView {
        private Bitmap pixels;
        Snapshot(View source,Bitmap bitmap){super(source.getContext());pixels=bitmap;setScaleType(ScaleType.FIT_XY);setImageBitmap(bitmap);}
        void release(){setImageDrawable(null);if(pixels!=null){pixels.recycle();pixels=null;}}
    }

    void start(FrameLayout container, View next, View previous, boolean back) {
        finish();
        if (previous == null) return;
        if(!Motion.enabled(container.getContext())){if(previous instanceof Snapshot)((Snapshot)previous).release();return;}
        host=container;incoming=next;outgoing=previous;returning=back;progress=0;
        if(previous.getParent() instanceof ViewGroup)((ViewGroup)previous.getParent()).removeView(previous);
        previous.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        container.addView(previous,new FrameLayout.LayoutParams(-1,-1));
        if(!back)next.bringToFront();
        shield=new View(container.getContext());shield.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        shield.setOnTouchListener((v,e)->true);container.addView(shield,new FrameLayout.LayoutParams(-1,-1));
        container.addOnAttachStateChangeListener(attach);container.addOnLayoutChangeListener(layout);
        float density=container.getResources().getDisplayMetrics().density;
        float distance=Math.min(64*density,Math.max(28*density,previous.getWidth()*.10f));
        animator=ValueAnimator.ofFloat(0,1);animator.setDuration(back?230:270);
        animator.setInterpolator(new PathInterpolator(.2f,.8f,.2f,1f));
        animator.addUpdateListener(value->{
            if(incoming==null||outgoing==null)return;progress=(float)value.getAnimatedValue();
            if(returning){outgoing.setTranslationX(distance*progress);outgoing.setAlpha(1-progress);incoming.setTranslationX(-distance*.25f*(1-progress));incoming.setAlpha(.85f+.15f*progress);}
            else{incoming.setTranslationX(distance*(1-progress));incoming.setAlpha(.35f+.65f*progress);outgoing.setTranslationX(-distance*.25f*progress);outgoing.setAlpha(1-.35f*progress);}
            // The returned page is already visible. Release only its late touch guard;
            // Android cancels an in-flight shield gesture instead of forwarding its UP.
            if(returning&&progress>=.85f&&shield!=null&&host!=null){host.removeView(shield);shield=null;}
        });
        animator.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator value){finish();}});
        animator.start();
    }

    void finish() {
        ValueAnimator old=animator;animator=null;
        if(old!=null){old.removeAllUpdateListeners();old.removeAllListeners();old.cancel();}
        if(incoming!=null){incoming.animate().cancel();incoming.setTranslationX(0);incoming.setAlpha(1);}
        if(host!=null){host.removeOnAttachStateChangeListener(attach);host.removeOnLayoutChangeListener(layout);if(outgoing!=null&&outgoing.getParent()==host)host.removeView(outgoing);if(shield!=null)host.removeView(shield);}
        if(outgoing instanceof Snapshot)((Snapshot)outgoing).release();
        incoming=null;outgoing=null;shield=null;host=null;progress=1;
    }
    String state(){return String.format(Locale.ROOT,"running=%s direction=%s progress=%.3f",animator!=null,returning?"back":"forward",progress);}
}
