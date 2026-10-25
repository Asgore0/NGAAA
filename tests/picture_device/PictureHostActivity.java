package cn.ngapure.pictureprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;

public final class PictureHostActivity extends Activity {
    FrameLayout content;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); content = new FrameLayout(this); setContentView(content);
    }
}
