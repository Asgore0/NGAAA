package cn.ngapure.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.view.WindowInsets;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;

/** Positions floating native surfaces inside a real hinge pane and above the focused window's IME. */
public final class NativeDialogGeometry {
    private NativeDialogGeometry() { }
    /**
     * ScrollView deliberately measures children with an unconstrained height, even when their
     * LayoutParams contain a fixed height. Keep the normal sheet's header and footer pinned by
     * applying that explicit bound here. A compact sheet uses WRAP_CONTENT and can scroll as a
     * whole, without reparenting its focused input when the keyboard or hinge pane changes.
     */
    static final class SheetShell extends LinearLayout {
        SheetShell(Context context) { super(context); setOrientation(VERTICAL); }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            ViewGroup.LayoutParams params = getLayoutParams();
            if (params != null && params.height >= 0)
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(params.height, MeasureSpec.EXACTLY);
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
    public static Rect available(Activity owner,Window window) {
        Rect safe=FoldAdaptiveLayout.visibleSafeBounds(owner);
        // A floating window can retain its last IME-clipped visible frame after hide.
        // Explicit hidden insets are authoritative; retain the owner's current hinge pane.
        if(Build.VERSION.SDK_INT>=30){WindowInsets insets=window.getDecorView().getRootWindowInsets();
            if(insets!=null&&!insets.isVisible(WindowInsets.Type.ime()))return safe;}
        Rect focused=new Rect();window.getDecorView().getWindowVisibleDisplayFrame(focused);
        if(focused.width()>0&&focused.height()>0){Rect intersection=new Rect(safe);if(intersection.intersect(focused))safe=intersection;}
        return safe;
    }
    public static Rect sheet(Rect safe,int capWidth,int wantedHeight,int margin) {
        int[] frame=FoldGeometry.sheet(safe.left,safe.top,safe.right,safe.bottom,capWidth,wantedHeight,margin);
        return new Rect(frame[0],frame[1],frame[0]+frame[2],frame[1]+frame[3]);
    }
    public static void place(Activity owner,Window window,Rect target) {
        Rect containing=new Rect();owner.getWindow().getDecorView().getWindowVisibleDisplayFrame(containing);
        int gravity=Gravity.TOP|Gravity.LEFT,x=target.left-containing.left,y=target.top-containing.top;
        WindowManager.LayoutParams params=window.getAttributes();
        if(params.width==target.width()&&params.height==target.height()&&params.x==x&&params.y==y&&params.gravity==gravity)return;
        params.gravity=gravity;params.x=x;params.y=y;params.width=target.width();params.height=target.height();params.horizontalMargin=0;params.verticalMargin=0;
        window.setAttributes(params);
    }
}
