package cn.ngapure.readcancelfixturev194;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import cn.ngapure.app.NativeActivitiesView;
import cn.ngapure.app.NativeCommunity;
import cn.ngapure.app.NativeCommunityView;
import cn.ngapure.app.NativeFollowingView;
import cn.ngapure.app.NgaStore;
import java.lang.reflect.Constructor;
import java.util.function.BooleanSupplier;

/** Empty own-UID host; ordinary production constructors use fixture-local preferences only. */
public final class ReadFixtureActivity extends Activity {
    public FrameLayout holder;
    public View current;
    @Override public void onCreate(Bundle state){super.onCreate(state);holder=new FrameLayout(this);setContentView(holder);}
    public View show(int kind) throws Exception{
        closeCurrent();holder.removeAllViews();NgaStore store=new NgaStore(this);
        if(store.loggedIn())throw new IllegalStateException("Fixture-local login must be absent");
        if(kind==0)current=new NativeCommunityView(this,store,"user","70001",1,new NativeCommunityView.Listener(){
            public void navigate(String kind,String id,String title,int page){}public void topic(String tid,String title){}public void user(String uid){}public void login(){}
        });
        else if(kind==1){
            Constructor<?> transportType=Class.forName("cn.ngapure.app.NativeCommunity$HttpsTransport").getDeclaredConstructor();transportType.setAccessible(true);
            NativeCommunity api=new NativeCommunity(new NativeCommunity.Session(){public String host(){return "bbs.nga.cn";}public String cookie(){return "ngaPassportUid=70001; ngaPassportCid=synthetic-lifecycle-only";}},(NativeCommunity.Transport)transportType.newInstance());
            Constructor<NativeFollowingView> constructor=NativeFollowingView.class.getDeclaredConstructor(Activity.class,NativeCommunity.class,int.class,boolean.class,NativeFollowingView.Listener.class,BooleanSupplier.class);constructor.setAccessible(true);
            current=constructor.newInstance(this,api,1,false,new NativeFollowingView.Listener(){
                public void page(int page){}public void user(String uid){}public void topic(String tid,String pid,String title){}public void login(){}
            },(BooleanSupplier)()->true);
        }
        else current=new NativeActivitiesView(this,store,url->{});
        holder.addView(current,new FrameLayout.LayoutParams(-1,-1));return current;
    }
    public void closeCurrent(){
        if(current instanceof NativeCommunityView)((NativeCommunityView)current).close();
        if(current instanceof NativeFollowingView)((NativeFollowingView)current).close();
        if(current instanceof NativeActivitiesView)((NativeActivitiesView)current).close();
    }
    @Override public void onDestroy(){closeCurrent();super.onDestroy();}
}
