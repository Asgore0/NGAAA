package cn.ngapure.imageprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
public final class ProbeHostActivity extends Activity {
    LinearLayout root,content;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,24,24,24);
        TextView title=new TextView(this);title.setText("原生图片 · 本机隔离验证");title.setTextSize(20);root.addView(title);
        ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }
}
