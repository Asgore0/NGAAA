package cn.ngapure.authfixturev195;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import cn.ngapure.app.NativeAuth;
import cn.ngapure.app.NgaStore;
import cn.ngapure.app.RequestCancellation;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual login views and HTTP transports. Own UID, synthetic local identity, zero real sockets. */
public final class AuthLifecycleProbe extends Instrumentation {
    private final ConcurrentLinkedQueue<Gate> queued=new ConcurrentLinkedQueue<>();
    private final List<Gate> gates=new ArrayList<>();
    private final AtomicInteger connections=new AtomicInteger(),unexpected=new AtomicInteger();
    private final JSONArray checks=new JSONArray();
    private AuthFixtureActivity activity;private boolean authorized;private int assertions;private Throwable uiFailure;
    private interface Work{void run()throws Exception;}
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("offlineAuthLifecycle"));start();}
    private void ui(Work work)throws Exception{uiFailure=null;runOnMainSync(()->{try{work.run();}catch(Throwable failure){uiFailure=failure;}});if(uiFailure!=null)throw new IllegalStateException("Fixture UI action failed",uiFailure);}
    private void check(boolean passed,String label)throws Exception{assertions++;checks.put(new JSONObject().put("check",label).put("passed",passed));if(!passed)throw new IllegalStateException(label);}
    private static Object field(Object source,String name)throws Exception{Field f=source.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(source);}
    private byte[] asset(String name)throws IOException{try(InputStream input=getTargetContext().getAssets().open(name);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);return output.toByteArray();}}
    private Gate gate(boolean qr,boolean slow)throws IOException{Gate gate=new Gate(asset(qr?"generate.json":"password-grant.json"),slow);gates.add(gate);queued.add(gate);return gate;}
    private void fakeHttps(){URL.setURLStreamHandlerFactory(protocol->new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{
        Gate gate=queued.poll();boolean known="https".equals(protocol)&&"bbs.nga.cn".equals(url.getHost())&&"/nuke.php".equals(url.getPath());
        if(gate==null||!known){unexpected.incrementAndGet();throw new IOException("Unscripted request blocked without a socket");}connections.incrementAndGet();return new Connection(url,gate);
    }});}
    private void loginClose(boolean qr,boolean slow)throws Exception{
        Gate response=gate(qr,slow);final View[] view={null};ui(()->view[0]=activity.show(qr));Object[] auth={null};char[][] secret={null};
        if(qr)ui(()->auth[0]=field(field(view[0],"attempt"),"auth"));
        else ui(()->{
            auth[0]=field(view[0],"auth");((EditText)field(view[0],"account")).setText("13800000000");((EditText)field(view[0],"secret")).setText("123456");((CheckBox)field(view[0],"agreement")).setChecked(true);
            Method login=view[0].getClass().getDeclaredMethod("login");login.setAccessible(true);login.invoke(view[0]);secret[0]=(char[])field(view[0],"pendingSecret");
        });
        check(response.reading.await(4,TimeUnit.SECONDS),qr?"QrGenerationReachesPendingFakeResponse":"SmsLoginReachesPendingFakeGrantWithoutSendingSms");
        ExecutorService worker=(ExecutorService)field(view[0],"worker");Object session=qr?field(auth[0],"current"):null;
        long start=SystemClock.uptimeMillis();ui(activity::closeCurrent);check(SystemClock.uptimeMillis()-start<500,qr?"QrViewCloseDoesNotBlockMainOnSocket":"CredentialViewCloseDoesNotBlockMainOnSocket");
        long pulse=SystemClock.uptimeMillis();ui(()->{});check(SystemClock.uptimeMillis()-pulse<250,"MainThreadStillRespondsWhileSocketCleanupIsPending");
        check((Boolean)field(view[0],"closed")&&(Boolean)field(auth[0],"closed"),"BothLoginViewAndAuthOwnerAreRevokedSynchronously");
        if(qr)check(session instanceof NativeAuth.QrSession&&((NativeAuth.QrSession)session).isCancelled(),"OldQrAttemptIsCancelledBeforeItsResponseReturns");
        else{boolean zero=secret[0]!=null;for(char c:secret[0])if(c!=0)zero=false;check(zero,"ClosingViewImmediatelyClearsPendingSmsCodeArray");}
        if(slow){check(response.disconnectStarted.await(2,TimeUnit.SECONDS),"SlowSocketDisconnectIsScheduledOffMain");check(!response.disconnectOnMain,"SlowSocketDisconnectNeverRunsOnMain");}
        else check(response.disconnectStarted.getCount()==1,"SaturatedCleanupQueueDoesNotFallBackToMainDisconnect");
        response.releaseDisconnect.countDown();response.releaseRead.countDown();check(worker.awaitTermination(4,TimeUnit.SECONDS),"ClosedLoginWorkerTerminatesAfterFakeResponseRelease");waitForIdleSync();
        check(new NgaStore(activity).accountId().equals("123")&&activity.successes==0,"LateSyntheticGrantCannotReplaceThePreviousSyntheticAccount");
        check(response.streamClosed,"CancelledLoginResponseClosesItsInput");check(!response.disconnectOnMain,"NormalWorkerFinallyCleansSocketOffMain");
        if(!qr)ui(()->check(!((Boolean)field(view[0],"committed"))&&((EditText)field(view[0],"account")).length()==0&&((EditText)field(view[0],"secret")).length()==0,"ClosedCredentialFormNeverCommitsAndClearsItsFields"));
    }
    private ThreadPoolExecutor disconnects()throws Exception{Field f=RequestCancellation.class.getDeclaredField("DISCONNECTS");f.setAccessible(true);return (ThreadPoolExecutor)f.get(null);}
    private void saturatedViews()throws Exception{
        ThreadPoolExecutor pool=disconnects();long until=SystemClock.uptimeMillis()+2000;while((pool.getActiveCount()>0||!pool.getQueue().isEmpty())&&SystemClock.uptimeMillis()<until)SystemClock.sleep(10);
        check(pool.getActiveCount()==0&&pool.getQueue().isEmpty(),"BoundedCleanupPoolStartsIdle");List<Gate> fillers=new ArrayList<>();
        try{
            for(int i=0;i<3;i++){Gate filler=new Gate(new byte[0],true);gates.add(filler);fillers.add(filler);check(RequestCancellation.disconnectAsync(new Connection(new URL("https://bbs.nga.cn/"),filler)),"CleanupWorkerAcceptsSyntheticFiller");check(filler.disconnectStarted.await(2,TimeUnit.SECONDS),"SyntheticFillerOccupiesCleanupWorker");}
            for(int i=0;i<12;i++){Gate filler=new Gate(new byte[0],true);gates.add(filler);fillers.add(filler);check(RequestCancellation.disconnectAsync(new Connection(new URL("https://bbs.nga.cn/"),filler)),"CleanupQueueAcceptsOnlyItsBoundedCapacity");}
            check(pool.getPoolSize()<=3&&pool.getQueue().size()==12,"CleanupIsBoundedToThreeWorkersAndTwelveQueuedSockets");loginClose(false,false);loginClose(true,false);check(pool.getPoolSize()<=3&&pool.getQueue().size()==12,"RepeatedLoginCloseCannotGrowTheSaturatedPoolOrQueue");
        }finally{for(Gate filler:fillers)filler.releaseDisconnect.countDown();}
        until=SystemClock.uptimeMillis()+2000;while((pool.getActiveCount()>0||!pool.getQueue().isEmpty())&&SystemClock.uptimeMillis()<until)SystemClock.sleep(10);check(pool.getActiveCount()==0&&pool.getQueue().isEmpty(),"CleanupPoolDrainsAfterSyntheticSocketsRelease");
    }
    @Override public void onStart(){boolean passed=false;String failure="NONE";try{
        check(authorized,"ExplicitOfflineAuthProbeAuthorization");String[] permissions=getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(),PackageManager.GET_PERMISSIONS).requestedPermissions;boolean noInternet=true;if(permissions!=null)for(String p:permissions)if("android.permission.INTERNET".equals(p))noInternet=false;
        check(noInternet,"HelperHasNoInternetPermission");check(android.os.Process.myUid()==getTargetContext().getApplicationInfo().uid,"HelperUsesOwnUid");fakeHttps();
        activity=(AuthFixtureActivity)startActivitySync(new Intent(getTargetContext(),AuthFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        loginClose(false,true);loginClose(true,true);saturatedViews();check(queued.isEmpty()&&unexpected.get()==0&&connections.get()==4,"OnlyFourScriptedLoginConnectionsNoRealSocketsOrSmsSends");passed=true;
    }catch(Throwable error){failure=error instanceof IllegalStateException&&error.getMessage()!=null?error.getMessage():error.getClass().getSimpleName();}
    finally{for(Gate gate:gates){gate.releaseDisconnect.countDown();gate.releaseRead.countDown();}if(activity!=null)try{ui(activity::closeCurrent);}catch(Exception ignored){}JSONObject result=new JSONObject();try{result.put("schema",1).put("purpose","OWN_UID_FAKE_HTTPS_LOGIN_LIFECYCLE").put("passed",passed).put("failure",failure).put("assertions",assertions).put("checks",checks).put("productionLoginViewsAndHttpTransportUsed",true).put("ownUid",true).put("internetPermission",false).put("fakeConnections",connections.get()).put("realNetworkRequests",0).put("realAccountReads",0).put("realCredentialSubmissions",0).put("realSmsSends",0).put("installedClientCredentialsAccessible",false);}catch(Exception ignored){}Bundle bundle=new Bundle();bundle.putString("safe_auth_lifecycle_report",result.toString());finish(passed?0:1,bundle);}}
    private static final class Gate {
        final byte[] body;final CountDownLatch reading=new CountDownLatch(1),releaseRead=new CountDownLatch(1),disconnectStarted=new CountDownLatch(1),releaseDisconnect=new CountDownLatch(1);
        volatile boolean streamClosed,disconnectOnMain;
        Gate(byte[] body,boolean slow){this.body=body;if(!slow)releaseDisconnect.countDown();}
    }
    private static final class Connection extends HttpURLConnection {
        final Gate gate;Connection(URL url,Gate gate){super(url);this.gate=gate;}
        @Override public int getResponseCode(){return 200;}
        @Override public String getContentType(){return "application/json; charset=UTF-8";}
        @Override public Map<String,List<String>> getHeaderFields(){return Collections.emptyMap();}
        @Override public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        @Override public InputStream getInputStream(){final ByteArrayInputStream bytes=new ByteArrayInputStream(gate.body);return new InputStream(){
            @Override public int read(){throw new AssertionError("Buffered input expected");}
            @Override public int read(byte[] buffer,int offset,int length)throws IOException{gate.reading.countDown();long until=SystemClock.uptimeMillis()+15000;while(gate.releaseRead.getCount()>0&&SystemClock.uptimeMillis()<until){try{gate.releaseRead.await(50,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){/* Prove active revocation after an interrupt-insensitive response. */}}if(gate.releaseRead.getCount()>0)throw new IOException("Synthetic read release timeout");return bytes.read(buffer,offset,length);}
            @Override public void close(){gate.streamClosed=true;}
        };}
        @Override public void disconnect(){if(Looper.myLooper()==Looper.getMainLooper())gate.disconnectOnMain=true;gate.disconnectStarted.countDown();long until=SystemClock.uptimeMillis()+15000;while(gate.releaseDisconnect.getCount()>0&&SystemClock.uptimeMillis()<until){try{gate.releaseDisconnect.await(50,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}}gate.releaseRead.countDown();}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){connected=true;}
    }
}
