package cn.ngapure.cacheprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Own-UID screen displaying a locally generated fixture bitmap only. */
public final class CacheHostActivity extends Activity {
    ImageView image;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,24,24,24);
        TextView title=new TextView(this);title.setText("原生媒体缓存 · 本机隔离验证");title.setTextSize(20);root.addView(title);
        image=new ImageView(this);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);root.addView(image,new LinearLayout.LayoutParams(-1,320));setContentView(root);
    }
}
