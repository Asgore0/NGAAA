package cn.ngapure.unreadcommitfixturev196;

import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.system.Os;
import cn.ngapure.app.NativeNotifications;
import cn.ngapure.app.NgaStore;
import cn.ngapure.app.UnreadNotificationPolicy;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual production read/persistence/notifications, fake HTTPS and isolated own-UID app storage. */
public final class UnreadCommitProbe extends Instrumentation {
    private static final String EMPTY="{\"data\":[\"\"]}";
    private static final String NEW="{\"data\":[{\"unread\":1,\"0\":[{\"0\":1,\"1\":\"70001\",\"2\":\"Synthetic only\",\"5\":\"Synthetic unread event\",\"6\":\"91001\",\"7\":\"92001\",\"9\":1760000000}]}]}";
    private static final String MESSAGES="{\"data\":[{\"currentPage\":1,\"nextPage\":0,\"rowsPerPage\":20}]}";
    private final Queue<Response> responses=new ArrayDeque<>();private final JSONArray checks=new JSONArray();
    private boolean authorized,notificationObserved;private int assertions,connections,unexpected;private File directory;
    private long notificationArrivalWaitMs=-1;private boolean cleanupNotificationsCleared;
    private void check(boolean value,String name)throws Exception{assertions++;checks.put(new JSONObject().put("check",name).put("passed",value));if(!value)throw new IllegalStateException(name);}
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("offlineUnreadCommit"));start();}
    private void fakeHttps(){URL.setURLStreamHandlerFactory(protocol->new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{
        Response next=responses.poll();String query=url.getQuery()==null?"":url.getQuery();boolean known=next!=null&&"https".equals(protocol)&&"bbs.nga.cn".equals(url.getHost())&&"/nuke.php".equals(url.getPath())&&(next.message?query.contains("__lib=message")&&query.contains("act=list"):query.contains("__lib=noti")&&query.contains("__act=get_all"));
        if(!known){unexpected++;throw new IOException("Unscripted connection blocked before opening a socket");}connections++;return new Connection(url,next.bytes);
    }});}
    private boolean poll(Context context,boolean unread)throws Exception{
        responses.add(new Response(unread?NEW:EMPTY,false));responses.add(new Response(MESSAGES,true));
        Method method=NativeNotifications.class.getDeclaredMethod("check",Context.class,AtomicBoolean.class);method.setAccessible(true);
        try{return (Boolean)method.invoke(null,context,new AtomicBoolean());}
        catch(InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;throw error;}
    }
    /** Posting/canceling enters the system service queue; a Binder return is not a UI visibility barrier. */
    private static boolean awaitEmpty(NotificationManager manager,long timeout){
        long until=SystemClock.uptimeMillis()+timeout;
        do{if(manager.getActiveNotifications().length==0)return true;SystemClock.sleep(20);}while(SystemClock.uptimeMillis()<until);
        return manager.getActiveNotifications().length==0;
    }
    private static boolean remainsEmpty(NotificationManager manager,long duration){
        long until=SystemClock.uptimeMillis()+duration;
        do{if(manager.getActiveNotifications().length!=0)return false;SystemClock.sleep(20);}while(SystemClock.uptimeMillis()<until);
        return manager.getActiveNotifications().length==0;
    }
    private static StatusBarNotification awaitOwnNotification(NotificationManager manager,String ownPackage,long timeout){
        long until=SystemClock.uptimeMillis()+timeout;
        do{
            StatusBarNotification[] active=manager.getActiveNotifications();
            if(active.length==1&&ownPackage.equals(active[0].getPackageName())&&active[0].getId()==12093)return active[0];
            if(active.length>0)throw new IllegalStateException("OnlyTheExpectedOwnSyntheticNotificationMayAppear");
            SystemClock.sleep(20);
        }while(SystemClock.uptimeMillis()<until);
        return null;
    }
    private static boolean remainsSameNotification(NotificationManager manager,StatusBarNotification expected,long duration){
        long until=SystemClock.uptimeMillis()+duration;
        do{
            StatusBarNotification[] active=manager.getActiveNotifications();
            if(active.length!=1||!active[0].getKey().equals(expected.getKey())||active[0].getPostTime()!=expected.getPostTime())return false;
            SystemClock.sleep(20);
        }while(SystemClock.uptimeMillis()<until);
        StatusBarNotification[] active=manager.getActiveNotifications();
        return active.length==1&&active[0].getKey().equals(expected.getKey())&&active[0].getPostTime()==expected.getPostTime();
    }
    @Override public void onStart(){boolean passed=false,cleanup=false;String failure="NONE";NotificationManager manager=null;SharedPreferences preferences=null;try{
        check(authorized,"ExplicitOfflineUnreadCommitAuthorization");Context context=getTargetContext();
        check(android.os.Process.myUid()==context.getApplicationInfo().uid,"HelperUsesItsOwnUid");
        String[] permissions=context.getPackageManager().getPackageInfo(context.getPackageName(),PackageManager.GET_PERMISSIONS).requestedPermissions;boolean noInternet=true;
        if(permissions!=null)for(String permission:permissions)if("android.permission.INTERNET".equals(permission))noInternet=false;
        check(noInternet,"NoInternetPermission");fakeHttps();
        new NgaStore(context).setCookie("ngaPassportUid=123; ngaPassportCid=synthetic-unread-commit-only");
        preferences=context.getSharedPreferences("nga_unread_notifications",Context.MODE_PRIVATE);
        check(preferences.edit().clear().putBoolean("enabled",true).commit(),"OnlyOwnSyntheticNotificationSettingsEnabled");
        check(NativeNotifications.canNotify(context),"OnlyHelperHasNotificationPermission");
        manager=context.getSystemService(NotificationManager.class);check(manager!=null,"OwnNotificationManagerAvailable");manager.cancelAll();
        check(awaitEmpty(manager,5000),"AnyPreviousOwnSyntheticNotificationHasActuallyCleared");
        directory=new File(context.getApplicationInfo().dataDir,"shared_prefs");
        check(poll(context,false),"InitialZeroUnreadReadCommitsRealBaseline");
        check(remainsEmpty(manager,200),"ZeroUnreadDoesNotCreateNotification");
        String scope=UnreadNotificationPolicy.scope("bbs.nga.cn","123");String snapshot=preferences.getString(scope+".snapshot","");long checkedAt=preferences.getLong(scope+".checkedAt",0);
        check(!snapshot.isEmpty()&&checkedAt>0,"InitialBaselineHasSnapshotAndTimestamp");
        Os.chmod(directory.getAbsolutePath(),0500);
        try{
            check(!poll(context,true),"DiskFailureDefersNewUnreadObservationForRetry");
            check(remainsEmpty(manager,400),"UncommittedNewUnreadDoesNotPublishEarly");
            check(snapshot.equals(preferences.getString(scope+".snapshot",""))&&checkedAt==preferences.getLong(scope+".checkedAt",0),"FailedBaselineCommitCannotAdvanceLiveSnapshotOrTimestamp");
        }finally{Os.chmod(directory.getAbsolutePath(),0700);}
        check(poll(context,true),"SameNewEventCompletesAfterDiskRecovers");
        long waitStarted=SystemClock.uptimeMillis();StatusBarNotification active=awaitOwnNotification(manager,context.getPackageName(),5000);notificationArrivalWaitMs=SystemClock.uptimeMillis()-waitStarted;
        check(active!=null,"RecoveredReadActuallyPublishesThePreviouslyDeferredUnreadEvent");notificationObserved=true;
        check(active.getNotification().extras.getCharSequence("android.text","").toString().contains("回复提醒 1 条"),"SyntheticNotificationContainsCorrectUnreadCount");
        SystemClock.sleep(40); // A mistaken second post must not share the first post's millisecond.
        check(poll(context,true),"UnchangedUnreadEventCanBeObservedAgain");
        check(remainsSameNotification(manager,active,1000),"SameAcknowledgedEventDoesNotRepublishNotification");
        check(poll(context,false)&&awaitEmpty(manager,5000),"SuccessfulZeroUnreadClearsOwnNotification");
        check(responses.isEmpty()&&unexpected==0&&connections==10,"ExactlyTenScriptedReadConnectionsAndNoSocket");passed=true;
    }catch(Throwable error){failure=error instanceof IllegalStateException&&error.getMessage()!=null?error.getMessage():error.getClass().getSimpleName();}
    finally{
        try{if(directory!=null)Os.chmod(directory.getAbsolutePath(),0700);if(manager!=null){manager.cancelAll();manager.deleteNotificationChannel("nga_unread");cleanupNotificationsCleared=awaitEmpty(manager,5000);}else cleanupNotificationsCleared=true;cleanup=(preferences==null||preferences.edit().clear().commit())&&cleanupNotificationsCleared;}catch(Exception ignored){cleanup=false;}
        JSONObject report=new JSONObject();try{report.put("schema",2).put("purpose","OWN_UID_REAL_PREFERENCES_UNREAD_BASELINE_FAILURE_FAKE_HTTPS").put("passed",passed&&cleanup).put("failure",failure).put("assertions",assertions).put("checks",checks).put("cleanupPassed",cleanup).put("cleanupNotificationsCleared",cleanupNotificationsCleared).put("ownUid",true).put("internetPermission",false).put("realAccountReads",0).put("realCommunityWrites",0).put("realNetworkRequests",0).put("fakeConnections",connections).put("productionNotificationsAndPersistenceUsed",true).put("onlyOwnSyntheticNotificationPublished",true).put("ownSyntheticNotificationObserved",notificationObserved).put("notificationArrivalWaitMs",notificationArrivalWaitMs).put("notificationVisibilityTimeoutMs",5000).put("unchangedNotificationObservationMs",1000);}catch(Exception ignored){}
        Bundle result=new Bundle();result.putString("safe_unread_commit_report",report.toString());finish(passed&&cleanup?0:1,result);
    }}
    private static final class Response{final byte[] bytes;final boolean message;Response(String body,boolean message){bytes=body.getBytes(StandardCharsets.UTF_8);this.message=message;}}
    private static final class Connection extends HttpURLConnection{
        final byte[] body;Connection(URL url,byte[] body){super(url);this.body=body;}
        @Override public int getResponseCode(){return 200;}@Override public String getContentType(){return "application/json; charset=UTF-8";}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(body);}
        @Override public void disconnect(){}@Override public boolean usingProxy(){return false;}@Override public void connect(){connected=true;}
    }
}
