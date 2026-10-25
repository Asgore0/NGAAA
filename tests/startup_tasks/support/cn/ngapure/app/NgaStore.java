package cn.ngapure.app;import android.content.Context;import java.util.concurrent.atomic.AtomicReference;import java.util.concurrent.atomic.AtomicInteger;
public final class NgaStore {
 static final class CheckInSession {final String host,cookie,account;CheckInSession(String host,String cookie,String account){this.host=host;this.cookie=cookie;this.account=account;}}
 static volatile CheckInSession current=new CheckInSession("bbs.nga.cn","synthetic-cookie-a","123");static volatile boolean enabled;static volatile String confirmedDay="",confirmedAccount="",clockDay="fixture-day";
 static final AtomicReference<Runnable> beforeCapture=new AtomicReference<>(),beforeAutoCheck=new AtomicReference<>(),beforeDayRead=new AtomicReference<>();static final AtomicInteger captures=new AtomicInteger();
 public NgaStore(Context context){}CheckInSession captureCheckInSession(){Context.touch();captures.incrementAndGet();Runnable hook=beforeCapture.getAndSet(null);if(hook!=null)hook.run();return current;}
 boolean autoCheckIn(){Context.touch();Runnable hook=beforeAutoCheck.getAndSet(null);if(hook!=null)hook.run();return enabled;}boolean loggedIn(){Context.touch();return !current.account.isEmpty();}String lastCheckInDay(){Context.touch();String value=current.account.equals(confirmedAccount)?confirmedDay:"";Runnable hook=beforeDayRead.getAndSet(null);if(hook!=null)hook.run();return value;}static String today(){return clockDay;}static void confirmed(){confirmedAccount=current.account;confirmedDay=clockDay;}
 void saveCheckInResult(boolean success,String message){}boolean saveCheckInResultForAccount(String account,boolean success,String message){return true;}
}
