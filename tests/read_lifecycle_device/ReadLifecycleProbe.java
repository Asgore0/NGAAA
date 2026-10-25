package cn.ngapure.readcancelfixturev194;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import cn.ngapure.app.NativeCommunity;
import cn.ngapure.app.NgaRatings;
import cn.ngapure.app.RequestCancellation;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Executes production views/transport; every HTTPS request is a local scripted object. */
public final class ReadLifecycleProbe extends Instrumentation {
    private final ConcurrentLinkedQueue<Gate> queued=new ConcurrentLinkedQueue<>();
    private final List<Gate> gates=new ArrayList<>();
    private final AtomicInteger unexpectedRequests=new AtomicInteger(),connections=new AtomicInteger();
    private final JSONArray checks=new JSONArray();
    private ReadFixtureActivity activity;private boolean authorized;private int assertions;private Throwable uiFailure;
    private interface Work{void run() throws Exception;}
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("offlineCommunityCancellation"));start();}
    private void ui(Work work) throws Exception{
        uiFailure=null;runOnMainSync(()->{try{work.run();}catch(Throwable failure){uiFailure=failure;}});
        if(uiFailure!=null)throw new IllegalStateException("Fixture UI action failed",uiFailure);
    }
    private void check(boolean value,String label) throws Exception{assertions++;checks.put(new JSONObject().put("check",label).put("passed",value));if(!value)throw new IllegalStateException(label);}
    private static Object field(Object source,String name) throws Exception{Field f=source.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(source);}
    private Gate gate(int kind){Gate gate=new Gate(kind);gates.add(gate);queued.add(gate);return gate;}
    private void installFakeHttps() {
        URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){
            @Override protected URLConnection openConnection(URL url) throws IOException{
                Gate gate=queued.poll();String query=url.getQuery()==null?"":url.getQuery();
                boolean known="bbs.nga.cn".equals(url.getHost())&&"/nuke.php".equals(url.getPath())&&(
                    query.contains("__lib=ucp")&&query.contains("__act=get")
                    ||query.contains("__lib=follow_v2")&&query.contains("__act=get_push_list")
                    ||query.contains("__lib=nga_index")&&query.contains("__act=get_event_app"));
                known=known||"bbs.nga.cn".equals(url.getHost())&&"/nuke.php".equals(url.getPath())&&query.contains("__lib=vote")&&query.contains("__act=get_score_list");
                if(gate==null||!known){unexpectedRequests.incrementAndGet();throw new IOException("Unscripted request blocked without opening a socket");}
                connections.incrementAndGet();return new FakeConnection(url,gate);
            }
        }:new URLStreamHandler(){@Override protected URLConnection openConnection(URL url) throws IOException{unexpectedRequests.incrementAndGet();throw new IOException("All other protocols blocked");}});
    }
    private void viewClose(int kind,String label) throws Exception{
        Gate gate=gate(kind);final View[] view={null};ui(()->view[0]=activity.show(kind));
        check(gate.reading.await(3,TimeUnit.SECONDS),label+"ReadReachedBlockingFakeHttps");
        RequestCancellation.Token token=(RequestCancellation.Token)field(view[0],"reads");ExecutorService worker=(ExecutorService)field(view[0],"io");
        check(!token.isCancelled()&&!worker.isShutdown(),label+"ReadScopeIsActiveBeforeClose");
        long start=SystemClock.uptimeMillis();ui(activity::closeCurrent);long duration=SystemClock.uptimeMillis()-start;
        check(token.isCancelled(),label+"CloseCancelsItsOwnReadToken");check(duration<500,label+"CloseDoesNotBlockOnSocketIo");
        check(gate.disconnected.await(2,TimeUnit.SECONDS),label+"CloseActivelyDisconnectsBlockedConnection");
        check(worker.awaitTermination(2,TimeUnit.SECONDS),label+"BlockedWorkerExitsAfterClose");
        check(gate.streamClosed&&gate.reads==1,label+"CancelledBodyStopsAfterFirstReadAndClosesStream");
        check(!gate.disconnectOnMain,label+"SocketDisconnectRunsOffMainThread");
        waitForIdleSync();ui(()->check(!contains(view[0],"SHOULD_NOT_RENDER"),label+"ClosedViewRejectsLateResponse"));
        ui(activity::closeCurrent);check(token.isCancelled(),label+"RepeatedCloseStaysCancelled");
    }
    private void deliveryOutlivesClose() throws Exception{
        Gate gate=gate(0);final View[] view={null};ui(()->view[0]=activity.show(0));check(gate.reading.await(3,TimeUnit.SECONDS),"DeliveryCaseHasDisposableReadInFlight");
        Class<?> workType=Class.forName("cn.ngapure.app.NativeCommunityView$Work"),successType=Class.forName("cn.ngapure.app.NativeCommunityView$Success"),failureType=Class.forName("cn.ngapure.app.NativeCommunityView$Failure");
        CountDownLatch begun=new CountDownLatch(1),release=new CountDownLatch(1),ended=new CountDownLatch(1);boolean[] finished={false},wasInterrupted={false};
        Object work=Proxy.newProxyInstance(workType.getClassLoader(),new Class<?>[]{workType},(proxy,method,args)->{
            if(!method.getName().equals("run"))return null;begun.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("Synthetic delivery gate timeout");}catch(InterruptedException stopped){wasInterrupted[0]=true;Thread.currentThread().interrupt();throw new IOException("Synthetic delivery interrupted");}
            RequestCancellation.check();finished[0]=true;ended.countDown();return "synthetic-only-no-request";
        });
        Object success=Proxy.newProxyInstance(successType.getClassLoader(),new Class<?>[]{successType},(proxy,method,args)->null),failure=Proxy.newProxyInstance(failureType.getClassLoader(),new Class<?>[]{failureType},(proxy,method,args)->null);
        Method writeTask=view[0].getClass().getDeclaredMethod("writeTask",workType,successType,failureType);writeTask.setAccessible(true);
        try{
            ui(()->{Field writing=view[0].getClass().getDeclaredField("writing");writing.setAccessible(true);writing.setBoolean(view[0],true);writeTask.invoke(view[0],work,success,failure);});
            check(begun.await(2,TimeUnit.SECONDS),"SyntheticDeliveryTaskStartsOnSecondWorker");
            ExecutorService worker=(ExecutorService)field(view[0],"io");ui(activity::closeCurrent);
            check(gate.disconnected.await(2,TimeUnit.SECONDS),"CloseCancelsDisposableReadAlongsideDelivery");
            check(worker.isShutdown()&&!worker.isTerminated(),"CloseAllowsExplicitDeliveryTaskToFinish");
            release.countDown();check(ended.await(2,TimeUnit.SECONDS),"DeliveryReadBackScopeRemainsUncancelledAfterClose");
            check(worker.awaitTermination(2,TimeUnit.SECONDS)&&finished[0]&&!wasInterrupted[0],"DeliveryWorkerFinishesWithoutPageInterrupt");
        }finally{release.countDown();}
    }
    private void ratingsQueueCancellation() throws Exception{
        Class<?> type=Class.forName("cn.ngapure.app.ScreenReadTasks");java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructor(int.class);constructor.setAccessible(true);Object queue=constructor.newInstance(1);
        Method execute=type.getDeclaredMethod("execute",Runnable.class),cancel=type.getDeclaredMethod("cancelAll"),close=type.getDeclaredMethod("close");execute.setAccessible(true);cancel.setAccessible(true);close.setAccessible(true);
        Gate old=gate(3);CountDownLatch done=new CountDownLatch(1),latestDone=new CountDownLatch(1);boolean[] cancelled={false},latestSuccess={false};
        try{
            Runnable read=()->{try{new NgaRatings(activity).list("",1);}catch(InterruptedIOException expected){cancelled[0]=true;}catch(Exception ignored){}finally{done.countDown();}};
            check(Boolean.TRUE.equals(execute.invoke(queue,read)),"RatingsPageReadAcceptedByProductionQueue");check(old.reading.await(3,TimeUnit.SECONDS),"RatingsReadReachesBlockingFakeHttps");
            long start=SystemClock.uptimeMillis();ui(()->cancel.invoke(queue));check(SystemClock.uptimeMillis()-start<500,"LeavingRatingsDoesNotBlockMainOnSocketIo");
            check(old.disconnected.await(2,TimeUnit.SECONDS),"LeavingRatingsActivelyDisconnectsItsSocket");check(done.await(2,TimeUnit.SECONDS)&&cancelled[0],"RatingsWorkerExitsAsCancellationWithoutData");
            check(old.streamClosed&&old.reads==1&&!old.disconnectOnMain,"CancelledRatingsStreamClosesOffMainAfterFirstRead");
            Gate latest=gate(3);latest.release.countDown();Runnable next=()->{try{latestSuccess[0]=new NgaRatings(activity).list("",1).items.isEmpty();}catch(Exception ignored){}finally{latestDone.countDown();}};
            check(Boolean.TRUE.equals(execute.invoke(queue,next)),"NewestRatingsRequestIsAcceptedAfterNavigation");check(latestDone.await(2,TimeUnit.SECONDS)&&latestSuccess[0],"NewestRatingsRequestRunsAfterCancelledWorkerReleases");
            check(latest.streamClosed&&latest.disconnected.getCount()==0,"LatestRatingsResponseClosesItsResources");
        }finally{old.release.countDown();close.invoke(queue);}
    }
    @Override public void onStart(){boolean passed=false;String failure="NONE";try{
        check(authorized,"ExplicitOfflineProbeAuthorization");String[] permissions=getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(),PackageManager.GET_PERMISSIONS).requestedPermissions;boolean noInternet=true;if(permissions!=null)for(String p:permissions)if("android.permission.INTERNET".equals(p))noInternet=false;
        check(noInternet,"HelperHasNoInternetPermission");check(android.os.Process.myUid()==getTargetContext().getApplicationInfo().uid,"HelperUsesItsOwnUid");
        installFakeHttps();activity=(ReadFixtureActivity)startActivitySync(new Intent(getTargetContext(),ReadFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        viewClose(0,"Community");viewClose(1,"Following");viewClose(2,"Activities");deliveryOutlivesClose();ratingsQueueCancellation();
        check(unexpectedRequests.get()==0&&queued.isEmpty(),"OnlySixScriptedReadRequestsWereMade");check(connections.get()==6,"SixFakeHttpsConnectionsNoRealSockets");passed=true;
    }catch(Throwable error){failure=error instanceof IllegalStateException&&error.getMessage()!=null?error.getMessage():error.getClass().getSimpleName();}
    finally{for(Gate gate:gates)gate.release.countDown();if(activity!=null)try{ui(activity::closeCurrent);}catch(Exception ignored){}JSONObject result=new JSONObject();try{result.put("schema",1).put("purpose","OWN_UID_FAKE_HTTPS_COMMUNITY_READ_LIFECYCLE").put("passed",passed).put("failure",failure).put("assertions",assertions).put("checks",checks).put("productionViewsAndHttpsTransportUsed",true).put("ownUid",true).put("internetPermission",false).put("fakeConnections",connections.get()).put("realNetworkRequests",0).put("realAccountReads",0).put("realCommunityWrites",0).put("syntheticDeliveryTaskOnly",true);}catch(Exception ignored){}Bundle bundle=new Bundle();bundle.putString("safe_read_lifecycle_report",result.toString());finish(passed?0:1,bundle);}}
    private static boolean contains(View view,String marker){if(view instanceof TextView&&((TextView)view).getText().toString().contains(marker))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(contains(group.getChildAt(i),marker))return true;}return false;}
    private static final class Gate {
        final int kind;final CountDownLatch reading=new CountDownLatch(1),release=new CountDownLatch(1),disconnected=new CountDownLatch(1);volatile boolean streamClosed,disconnectOnMain;int reads;
        Gate(int kind){this.kind=kind;}
        String body(){return kind==0?"{\"data\":[{\"uid\":70001,\"username\":\"SHOULD_NOT_RENDER\"}]}":kind==1?"{\"data\":[[],{},0,1,{}]}":kind==2?"{\"data\":[{\"act_url\":\"nul\"}]}":"{\"code\":0,\"result\":[{\"catid\":\"\",\"page\":1,\"next\":0,\"topics\":[]},[]]}";}
    }
    private static final class FakeConnection extends HttpURLConnection {
        final Gate gate;
        FakeConnection(URL url,Gate gate){super(url);this.gate=gate;}
        @Override public int getResponseCode(){return 200;}
        @Override public String getContentType(){return "application/json; charset=UTF-8";}
        @Override public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        @Override public InputStream getInputStream(){final ByteArrayInputStream bytes=new ByteArrayInputStream(gate.body().getBytes(StandardCharsets.UTF_8));return new InputStream(){
            @Override public int read(){throw new AssertionError("Buffered input expected");}
            @Override public int read(byte[] buffer,int offset,int length) throws IOException{
                gate.reads++;gate.reading.countDown();long limit=SystemClock.uptimeMillis()+10000;
                while(gate.release.getCount()>0&&SystemClock.uptimeMillis()<limit){try{gate.release.await(100,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){/* Prove active socket disconnection rather than interruption. */}}
                if(gate.release.getCount()>0)throw new IOException("Synthetic gate timeout");return bytes.read(buffer,offset,length);
            }
            @Override public void close(){gate.streamClosed=true;}
        };}
        @Override public void disconnect(){if(Looper.myLooper()==Looper.getMainLooper())gate.disconnectOnMain=true;gate.disconnected.countDown();gate.release.countDown();}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){connected=true;}
    }
}
