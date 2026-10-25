package cn.ngapure.app;
import android.content.Context;import java.util.concurrent.atomic.AtomicBoolean;
/** Notification delivery is replaced; the worker still exercises the real read adapter/HTTPS transport. */
public final class NativeNotifications {
 static volatile boolean enabled=true,permission=true;
 public static boolean enabled(Context context){return enabled;}public static boolean canNotify(Context context){return permission;}
 static void cancel(AtomicBoolean owner){if(owner!=null)owner.set(true);}
 static boolean check(Context context,AtomicBoolean canceled){
  try{java.lang.reflect.Constructor<?> constructor=Class.forName("cn.ngapure.app.NativeCommunity$HttpsTransport").getDeclaredConstructor();constructor.setAccessible(true);
   NativeCommunity.Session session=new NativeCommunity.Session(){public String host(){return "bbs.nga.cn";}public String cookie(){return "";}};
   new NativeCommunity(session,(NativeCommunity.Transport)constructor.newInstance()).officialActivity();return !canceled.get();
  }catch(Exception unavailable){return false;}
 }
}
