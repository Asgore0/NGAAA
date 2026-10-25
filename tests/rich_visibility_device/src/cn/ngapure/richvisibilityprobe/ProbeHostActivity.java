package cn.ngapure.richvisibilityprobe;

import android.app.Activity;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;

public final class ProbeHostActivity extends Activity {
    LinearLayout content;
    CountingScroll scroll;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);scroll=new CountingScroll(this);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);setContentView(scroll);
    }
    /** getLocalVisibleRect is final; its real implementation dispatches through this overload. */
    public static final class CountingScroll extends ScrollView {
        int visibleReads,locationReads;
        CountingScroll(Context context){super(context);}
        @Override public boolean getGlobalVisibleRect(Rect bounds,Point offset) {
            visibleReads++;return super.getGlobalVisibleRect(bounds,offset);
        }
        @Override public void getLocationInWindow(int[] location) {
            locationReads++;super.getLocationInWindow(location);
        }
        void resetCounts(){visibleReads=locationReads=0;}
    }
}
