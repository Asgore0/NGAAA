package cn.ngapure.app;
import android.content.Context;
import java.util.concurrent.atomic.AtomicInteger;
public final class NgaStore {
 public static final class CheckInSession { public final String host,account,cookie; public CheckInSession(String h,String a,String c){host=h;account=a;cookie=c;} }
 static volatile CheckInSession current=new CheckInSession("bbs.nga.cn","123","cookie-a");
 static volatile boolean enabled=true;
 static volatile Runnable afterDayRead;
 static volatile int dayHookCountdown=1;
 static volatile String confirmedDay="",confirmedAccount="",clockDay="synthetic-day";
 static final AtomicInteger captures=new AtomicInteger(),loginReads=new AtomicInteger();
 public NgaStore(Context c){}
 public CheckInSession captureCheckInSession(){captures.incrementAndGet();return current;}
 public boolean autoCheckIn(){return enabled;}
 public boolean loggedIn(){loginReads.incrementAndGet();return !current.account.isEmpty();}
 public String lastCheckInDay(){String value=current.account.equals(confirmedAccount)?confirmedDay:"";Runnable hook=afterDayRead;if(hook!=null&&--dayHookCountdown==0){afterDayRead=null;hook.run();}return value;}
 public static String today(){return clockDay;}
 static void confirmed(){confirmedAccount=current.account;confirmedDay=clockDay;}
 public void saveCheckInResult(boolean ok,String text){}
 public boolean saveCheckInResultForAccount(String account,boolean ok,String text){return true;}
}
