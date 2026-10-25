package cn.ngapure.anchorprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class AnchorHostActivity extends Activity {
    LinearLayout content;
    TouchScroll scroll;
    interface LayoutHook { boolean run() throws Exception; }
    final class TouchScroll extends ScrollView {
        LayoutHook beforeAnchorLayout; boolean callingHook;
        TouchScroll(){super(AnchorHostActivity.this);}
        @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){cn.ngapure.app.NativeRichText.onScrollTouch(this,event);return super.dispatchTouchEvent(event);}
        @Override public void requestLayout(){
            if(beforeAnchorLayout!=null&&!callingHook){callingHook=true;try{if(beforeAnchorLayout.run())beforeAnchorLayout=null;}catch(Exception failure){throw new IllegalStateException(failure);}finally{callingHook=false;}}
            super.requestLayout();
        }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,24,24,24);
        TextView title=new TextView(this);title.setText("多帖图片延迟 · 隔离位置验证");title.setTextSize(18);root.addView(title);
        scroll=new TouchScroll();scroll.setFillViewport(true);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }
}
