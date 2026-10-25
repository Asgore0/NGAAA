package cn.ngapure.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Test-only Keystore boundary: synthetic headers stand in for encrypted values.
 * Every account replacement, identity check and guarded cache write uses real LoginPreferences.
 */
public final class NgaStore {
    public static final Set<String> HOSTS=Collections.unmodifiableSet(new HashSet<>(Arrays.asList("bbs.nga.cn","ngabbs.com","nga.178.com")));
    private final TestContext context;
    private final SharedPreferences preferences;
    public NgaStore(Context context){this.context=(TestContext)context.getApplicationContext();preferences=this.context.preferences;}
    public String host(){return context.host;}
    public synchronized String cookie(){return LoginPreferences.cookie(preferences);}
    public boolean loggedIn(){return !LoginPreferences.identity(cookie()).isEmpty();}
    public String accountId(){return LoginPreferences.identity(cookie());}
    public synchronized void setCookie(String header){synchronized(LoginPreferences.class){LoginPreferences.replaceAccount(preferences,header,cookie(),header);}}
    public void clearLogin(){LoginPreferences.clear(preferences);}
    public String lastCheckInDay(){return LoginPreferences.checkInDay(preferences);}
    public String checkInMessage(){return LoginPreferences.checkInMessage(preferences);}
    public String lastCheckInAttempt(){return LoginPreferences.checkInAttempt(preferences);}
    public void saveCheckInResult(boolean confirmed,String message){LoginPreferences.saveCheckInResult(preferences,confirmed,message,today());}
    public static String today(){return "2099-02-03";}

    static final class CheckInSession {
        final String host,cookie,account;
        CheckInSession(String host,String cookie,String account){this.host=host;this.cookie=cookie;this.account=account;}
    }
    synchronized CheckInSession captureCheckInSession(){
        synchronized(LoginPreferences.class){
            String header=cookie();CheckInSession session=new CheckInSession(host(),header,LoginPreferences.identity(header));
            Runnable hook=context.afterCapture;context.afterCapture=null;if(hook!=null)hook.run();return session;
        }
    }
    public synchronized boolean recordCheckInAttemptForAccount(String expected,String source){
        synchronized(LoginPreferences.class){
            boolean result=LoginPreferences.recordCheckInAttemptForAccount(preferences,expected,cookie(),source);
            Runnable hook=context.afterRecord;context.afterRecord=null;if(hook!=null)hook.run();return result;
        }
    }
    public synchronized boolean saveCheckInResultForAccount(String expected,boolean confirmed,String message){
        synchronized(LoginPreferences.class){return LoginPreferences.saveCheckInResultForAccount(preferences,expected,cookie(),confirmed,message,today());}
    }
}
