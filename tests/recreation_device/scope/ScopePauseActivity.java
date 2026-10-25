package cn.ngapure.scopelifecycle;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Opaque local cover so the real MainActivity receives an actual stop/start cycle. */
public final class ScopePauseActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);TextView view=new TextView(this);view.setText("隔离会话生命周期测试");view.setBackgroundColor(0xffeaf0f8);setContentView(view);}
}
