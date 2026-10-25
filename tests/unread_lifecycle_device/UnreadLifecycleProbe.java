package cn.ngapure.unreadfixturev195;

import android.app.Instrumentation;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import cn.ngapure.app.NativeNotifications;
import cn.ngapure.app.UnreadNotificationService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Android itself invokes the production service. Every response is in-memory and unread counts are zero. */
public final class UnreadLifecycleProbe extends Instrumentation {
    private static final int JOB=12092;
    private final ConcurrentLinkedQueue<Gate> queued=new ConcurrentLinkedQueue<>();
    private final List<Gate> gates=new ArrayList<>();
    private final AtomicInteger connections=new AtomicInteger(),unexpected=new AtomicInteger();
    private final JSONArray checks=new JSONArray();
    private UnreadFixtureActivity activity;private JobScheduler jobs;private boolean authorized,observeBusy;private int assertions;private Boolean busyReturnedSuccess;
    private void check(boolean passed,String label)throws Exception{assertions++;checks.put(new JSONObject().put("check",label).put("passed",passed));if(!passed)throw new IllegalStateException(label);}
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("offlineUnreadLifecycle"));observeBusy=args!=null&&"true".equals(args.getString("observeBusyDeferralOnly"));start();}
    private static AtomicBoolean checking()throws Exception{Field f=NativeNotifications.class.getDeclaredField("CHECKING");f.setAccessible(true);return (AtomicBoolean)f.get(null);}
    private Gate gate(boolean conversation,boolean blocked){String body=conversation?"{\"data\":[{\"currentPage\":1,\"nextPage\":0,\"rowsPerPage\":20}]}":"{\"data\":[\"\"]}";Gate gate=new Gate(body,conversation,blocked);gates.add(gate);queued.add(gate);return gate;}
    private void fakeHttps(){URL.setURLStreamHandlerFactory(protocol->new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{
        Gate gate=queued.poll();String query=url.getQuery()==null?"":url.getQuery();boolean known=gate!=null&&"https".equals(protocol)&&"bbs.nga.cn".equals(url.getHost())&&"/nuke.php".equals(url.getPath())&&(gate.conversation?query.contains("__lib=message")&&query.contains("act=list"):query.contains("__lib=noti")&&query.contains("__act=get_all"));
        if(!known){unexpected.incrementAndGet();throw new IOException("Unscripted request blocked without a socket");}connections.incrementAndGet();return new Connection(url,gate);
    }});}
    private void observeBusy()throws Exception{
        AtomicBoolean owner=checking();check(owner.compareAndSet(false,true),"NoOtherFixtureReadIsInFlightBeforeBusyObservation");
        try{Method method=NativeNotifications.class.getDeclaredMethod("check",android.content.Context.class,AtomicBoolean.class);method.setAccessible(true);busyReturnedSuccess=(Boolean)method.invoke(null,activity,new AtomicBoolean());}
        finally{owner.set(false);}
        check(connections.get()==0,"BusyReadObservationNeverStartsARequest");
        if(!observeBusy)check(!busyReturnedSuccess,"EligibleReadDeferredByAnotherOwnerRequestsASystemRetry");
    }
    private int schedule(){return jobs.schedule(new JobInfo.Builder(JOB,new ComponentName(activity,UnreadNotificationService.class)).setOverrideDeadline(0).build());}
    private void jobLifecycle()throws Exception{
        jobs=activity.getSystemService(JobScheduler.class);check(jobs!=null,"OwnUidJobSchedulerIsAvailable");jobs.cancel(JOB);
        Gate old=gate(false,true);check(schedule()==JobScheduler.RESULT_SUCCESS,"AndroidAcceptsFirstOwnUidOneShotJob");check(old.reading.await(12,TimeUnit.SECONDS),"ActualJobServiceReachesBlockedProductionHttpsRead");check(checking().get(),"ActualBackgroundReadOwnsGlobalNotificationReadGate");
        long start=SystemClock.uptimeMillis();runOnMainSync(()->jobs.cancel(JOB));check(SystemClock.uptimeMillis()-start<500,"CancelingOwnJobDoesNotBlockMainOnSocketIo");
        check(old.disconnected.await(3,TimeUnit.SECONDS),"SystemOnStopJobActivelyDisconnectsTheCanceledRead");check(!old.disconnectOnMain,"StoppedServiceDisconnectRunsOffMainThread");
        long until=SystemClock.uptimeMillis()+3000;while(checking().get()&&SystemClock.uptimeMillis()<until)SystemClock.sleep(10);check(!checking().get()&&old.streamClosed,"StoppedReadReleasesGlobalOwnerAndInput");
        Gate latest=gate(false,false),messages=gate(true,false);check(schedule()==JobScheduler.RESULT_SUCCESS,"AndroidAcceptsReplacementOwnUidOneShotJob");check(latest.reading.await(12,TimeUnit.SECONDS),"ReplacementJobStartsItsOwnReminderRead");check(messages.reading.await(4,TimeUnit.SECONDS),"ReplacementJobCompletesTheActualZeroUnreadConversationRead");
        until=SystemClock.uptimeMillis()+5000;while((NativeNotifications.lastCheckedAt(activity)==0||jobs.getPendingJob(JOB)!=null||checking().get())&&SystemClock.uptimeMillis()<until)SystemClock.sleep(20);
        check(NativeNotifications.lastCheckedAt(activity)>0&&"最近检查成功".equals(NativeNotifications.status(activity)),"ReplacementJobCommitsItsOwnSuccessfulZeroUnreadBaseline");
        check(jobs.getPendingJob(JOB)==null&&!checking().get(),"SuccessfulOneShotJobFinishesWithoutAnExtraRetry");check(latest.streamClosed&&messages.streamClosed,"SuccessfulReplacementClosesBothReadStreams");
        NotificationManager notifications=activity.getSystemService(NotificationManager.class);check(notifications!=null&&notifications.getActiveNotifications().length==0,"ZeroUnreadFixtureNeverPublishesASystemNotification");check(queued.isEmpty()&&connections.get()==3&&unexpected.get()==0,"OnlyThreeScriptedReadConnectionsNoRealSockets");
    }
    @Override public void onStart(){boolean passed=false;String failure="NONE";try{
        check(authorized,"ExplicitOfflineUnreadProbeAuthorization");String[] permissions=getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(),PackageManager.GET_PERMISSIONS).requestedPermissions;boolean noInternet=true;if(permissions!=null)for(String permission:permissions)if("android.permission.INTERNET".equals(permission))noInternet=false;
        check(noInternet,"HelperHasNoInternetPermission");check(android.os.Process.myUid()==getTargetContext().getApplicationInfo().uid,"HelperUsesOwnUid");fakeHttps();
        activity=(UnreadFixtureActivity)startActivitySync(new Intent(getTargetContext(),UnreadFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();activity.getSharedPreferences("nga_unread_notifications",0).edit().clear().putBoolean("enabled",true).commit();
        check(NativeNotifications.enabled(activity)&&NativeNotifications.canNotify(activity),"OnlyHelperNotificationPermissionIsGrantedForProductionEligibility");observeBusy();if(!observeBusy)jobLifecycle();passed=true;
    }catch(Throwable error){failure=error instanceof IllegalStateException&&error.getMessage()!=null?error.getMessage():error.getClass().getSimpleName();}
    finally{for(Gate gate:gates)gate.release.countDown();if(jobs!=null)jobs.cancel(JOB);try{checking().set(false);}catch(Exception ignored){}JSONObject result=new JSONObject();try{result.put("schema",1).put("purpose",observeBusy?"OWN_UID_OBSERVE_BUSY_NOTIFICATION_READ_DEFERRAL":"OWN_UID_REAL_JOB_SERVICE_LIFECYCLE_FAKE_HTTPS").put("passed",passed).put("failure",failure).put("assertions",assertions).put("checks",checks).put("productionJobServiceAndNotificationAdapterUsed",true).put("busyEligibleReadReturnedSuccess",busyReturnedSuccess==null?JSONObject.NULL:busyReturnedSuccess).put("ownUid",true).put("internetPermission",false).put("helperNotificationPermissionOnly",true).put("fakeConnections",connections.get()).put("realNetworkRequests",0).put("realAccountReads",0).put("realCommunityWrites",0).put("installedClientSettingsChanged",false);}catch(Exception ignored){}Bundle bundle=new Bundle();bundle.putString("safe_unread_lifecycle_report",result.toString());finish(passed?0:1,bundle);}}
    private static final class Gate {
        final byte[] body;final boolean conversation;final CountDownLatch reading=new CountDownLatch(1),release=new CountDownLatch(1),disconnected=new CountDownLatch(1);volatile boolean streamClosed,disconnectOnMain;
        Gate(String body,boolean conversation,boolean blocked){this.body=body.getBytes(StandardCharsets.UTF_8);this.conversation=conversation;if(!blocked)release.countDown();}
    }
    private static final class Connection extends HttpURLConnection {
        final Gate gate;Connection(URL url,Gate gate){super(url);this.gate=gate;}
        @Override public int getResponseCode(){return 200;}
        @Override public String getContentType(){return "application/json; charset=UTF-8";}
        @Override public InputStream getInputStream(){final ByteArrayInputStream bytes=new ByteArrayInputStream(gate.body);return new InputStream(){@Override public int read(){throw new AssertionError("Buffered read expected");}@Override public int read(byte[] buffer,int offset,int length)throws IOException{gate.reading.countDown();long until=SystemClock.uptimeMillis()+15000;while(gate.release.getCount()>0&&SystemClock.uptimeMillis()<until){try{gate.release.await(50,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){/* Active cancellation must close the socket. */}}if(gate.release.getCount()>0)throw new IOException("Synthetic read release timeout");return bytes.read(buffer,offset,length);}@Override public void close(){gate.streamClosed=true;}};}
        @Override public void disconnect(){if(Looper.myLooper()==Looper.getMainLooper())gate.disconnectOnMain=true;gate.disconnected.countDown();gate.release.countDown();}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){connected=true;}
    }
}
