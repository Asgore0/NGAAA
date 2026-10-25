package cn.ngapure.authfixturev195;
import android.app.Activity;import android.os.Bundle;import android.view.View;import android.widget.FrameLayout;import cn.ngapure.app.NativeCredentialLoginView;import cn.ngapure.app.NativeLoginView;import cn.ngapure.app.NgaStore;
/** Own app UID/preferences/keystore. The installed client account is inaccessible. */
public final class AuthFixtureActivity extends Activity {
 public FrameLayout holder;public View current;public int successes;
 @Override public void onCreate(Bundle state){super.onCreate(state);holder=new FrameLayout(this);setContentView(holder);new NgaStore(this).setCookie("ngaPassportUid=123; ngaPassportCid=synthetic-previous-login-only");}
 public View show(boolean qr){closeCurrent();NgaStore store=new NgaStore(this);current=qr?new NativeLoginView(this,store,()->successes++):new NativeCredentialLoginView(this,store,NativeCredentialLoginView.Mode.SMS,()->successes++);holder.addView(current,new FrameLayout.LayoutParams(-1,-1));return current;}
 public void closeCurrent(){if(current instanceof NativeCredentialLoginView)((NativeCredentialLoginView)current).close();if(current instanceof NativeLoginView)((NativeLoginView)current).close();holder.removeAllViews();}
 @Override public void onDestroy(){closeCurrent();super.onDestroy();}
}
