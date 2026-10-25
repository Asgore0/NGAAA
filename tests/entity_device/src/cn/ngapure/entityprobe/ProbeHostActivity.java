package cn.ngapure.entityprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;

public final class ProbeHostActivity extends Activity {
    LinearLayout content;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);content.setPadding(32,32,32,32);
        scroll.addView(content);setContentView(scroll);
    }
}
