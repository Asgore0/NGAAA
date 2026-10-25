package cn.ngapure.unreadfixturev195;
import android.app.Activity;import android.os.Bundle;import android.widget.TextView;import cn.ngapure.app.NgaStore;
/** Synthetic state only in this separate UID. */
public final class UnreadFixtureActivity extends Activity {
 @Override public void onCreate(Bundle state){super.onCreate(state);TextView label=new TextView(this);label.setText("Isolated fake HTTPS unread service verification");setContentView(label);new NgaStore(this).setCookie("ngaPassportUid=123; ngaPassportCid=synthetic-unread-only");}
}
