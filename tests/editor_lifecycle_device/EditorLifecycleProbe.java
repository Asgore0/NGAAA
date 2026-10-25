package cn.ngapure.editorfixturev195;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.EditText;
import cn.ngapure.app.NativeDrafts;
import cn.ngapure.app.NativeEditorView;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Production editor/posting classes, isolated app data, every HTTPS request is an in-memory object. */
public final class EditorLifecycleProbe extends Instrumentation {
    private final ConcurrentLinkedQueue<Gate> queued=new ConcurrentLinkedQueue<>();
    private final List<Gate> gates=new ArrayList<>();
    private final AtomicInteger connections=new AtomicInteger(),unexpected=new AtomicInteger();
    private final JSONArray checks=new JSONArray();
    private EditorFixtureActivity activity;private boolean authorized;private int assertions;private Throwable uiFailure;
    private interface Work{void run()throws Exception;}
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("offlineEditorLifecycle"));start();}
    private void ui(Work work)throws Exception{uiFailure=null;runOnMainSync(()->{try{work.run();}catch(Throwable failure){uiFailure=failure;}});if(uiFailure!=null)throw new IllegalStateException("Fixture UI action failed",uiFailure);}
    private void check(boolean passed,String label)throws Exception{assertions++;checks.put(new JSONObject().put("check",label).put("passed",passed));if(!passed)throw new IllegalStateException(label);}
    private static Object field(Object source,String name)throws Exception{Field f=source.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(source);}
    private byte[] asset(String name)throws IOException{try(InputStream input=getTargetContext().getAssets().open(name);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);return output.toByteArray();}}
    private Gate gate(String fixture,boolean blocked,boolean lost)throws IOException{Gate gate=new Gate(fixture,asset(fixture),blocked,lost);gates.add(gate);queued.add(gate);return gate;}
    private void fakeHttps(){URL.setURLStreamHandlerFactory(protocol->new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{
        Gate gate=queued.poll();boolean known="https".equals(protocol)&&"bbs.nga.cn".equals(url.getHost())&&"/post.php".equals(url.getPath());
        if(gate==null||!known){unexpected.incrementAndGet();throw new IOException("Unscripted request blocked without a socket");}connections.incrementAndGet();return new Connection(url,gate);
    }});}
    private NativeEditorView show()throws Exception{final NativeEditorView[] view={null};ui(()->{activity.getSharedPreferences("nga_native_drafts",0).edit().clear().commit();view[0]=activity.show();});return view[0];}
    private void waitPrepared(NativeEditorView view)throws Exception{long until=SystemClock.uptimeMillis()+3000;boolean[] ready={false};while(SystemClock.uptimeMillis()<until){ui(()->ready[0]=field(view,"prepared")!=null&&!((Boolean)field(view,"busy")));if(ready[0])break;SystemClock.sleep(20);}check(ready[0],"ProductionEditorReceivesSyntheticPreparation");}
    private void canceledPreparation()throws Exception{
        Gate read=gate("prepare-new.json",true,false);NativeEditorView view=show();check(read.reading.await(3,TimeUnit.SECONDS),"PreparationReachesInterruptInsensitiveFakeSocket");
        RequestCancellation.Token token=(RequestCancellation.Token)field(view,"preparationReads");ExecutorService worker=(ExecutorService)field(view,"worker");
        long start=SystemClock.uptimeMillis();ui(activity::closeCurrent);check(SystemClock.uptimeMillis()-start<500,"ClosingEditorDoesNotWaitForSocketIo");check(token.isCancelled(),"EditorCloseRevokesOnlyItsPreparationToken");
        check(read.disconnected.await(2,TimeUnit.SECONDS),"EditorCloseActivelyDisconnectsPreparation");check(worker.awaitTermination(2,TimeUnit.SECONDS),"CanceledPreparationWorkerTerminates");
        check(read.streamClosed&&read.reads==1,"CanceledPreparationClosesItsFirstBodyRead");check(!read.disconnectOnMain,"PreparationSocketDisconnectRunsOffMainThread");waitForIdleSync();check(field(view,"prepared")==null,"LatePreparationCannotAuthorizeClosedEditor");
        ui(view::close);check(token.isCancelled(),"RepeatedCloseIsIdempotent");
    }
    private void confirmedDelivery(boolean lost)throws Exception{
        Gate permission=gate("prepare-new.json",false,false);NativeEditorView view=show();waitPrepared(view);check(permission.streamClosed,"PreparedEditorClosesPermissionSocket");
        Gate delivery=gate("submit-success.json",true,lost);Method submit=view.getClass().getDeclaredMethod("submit",String.class,String.class,List.class,Map.class);submit.setAccessible(true);
        ui(()->{((EditText)field(view,"subject")).setText("Synthetic fixture subject");((EditText)field(view,"content")).setText("Synthetic lifecycle body, never sent to a socket.");submit.invoke(view,"Synthetic fixture subject","Synthetic lifecycle body, never sent to a socket.",Collections.emptyList(),Collections.emptyMap());});
        check(delivery.reading.await(3,TimeUnit.SECONDS),"ExplicitSyntheticDeliveryReachesPendingResponse");
        NativeDrafts drafts=new NativeDrafts(activity);NativeDrafts.Draft marked=drafts.get("new","7","","");check(marked!=null&&marked.submissionUnconfirmed,"UnconfirmedDraftIsDurableBeforeDeliveryCompletes");
        ExecutorService worker=(ExecutorService)field(view,"worker");ui(activity::closeCurrent);check(worker.isShutdown()&&!worker.isTerminated(),"EditorCloseAllowsConfirmedDeliveryToSettle");
        check(!delivery.disconnected.await(150,TimeUnit.MILLISECONDS),"DisposablePageCloseDoesNotCancelSubmittedRequest");check(delivery.interrupts==0,"EditorCloseDoesNotInterruptConfirmedDelivery");
        delivery.release.countDown();check(worker.awaitTermination(3,TimeUnit.SECONDS),"ConfirmedDeliveryWorkerSettlesAfterPageClose");waitForIdleSync();
        NativeDrafts.Draft after=drafts.get("new","7","","");
        check(lost?after!=null&&after.submissionUnconfirmed:after==null,lost?"LostResponseRetainsUnconfirmedDraftAndCannotBlindlyRetry":"ConfirmedSuccessClearsOnlyItsSubmissionDraft");
        check(delivery.sent.size()>0&&delivery.disconnected.getCount()==0,"SubmittedBodyUsesOnlyFakeConnectionAndClosesIt");check(activity.published==0,"ClosedEditorNeverNavigatesFromALateDeliveryCallback");
    }
    @Override public void onStart(){boolean passed=false;String failure="NONE";try{
        check(authorized,"ExplicitOfflineEditorProbeAuthorization");String[] permissions=getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(),PackageManager.GET_PERMISSIONS).requestedPermissions;boolean noInternet=true;if(permissions!=null)for(String p:permissions)if("android.permission.INTERNET".equals(p))noInternet=false;
        check(noInternet,"HelperHasNoInternetPermission");check(android.os.Process.myUid()==getTargetContext().getApplicationInfo().uid,"HelperUsesOwnUid");fakeHttps();
        activity=(EditorFixtureActivity)startActivitySync(new Intent(getTargetContext(),EditorFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        canceledPreparation();confirmedDelivery(false);confirmedDelivery(true);check(queued.isEmpty()&&unexpected.get()==0&&connections.get()==5,"OnlyFiveScriptedHttpsConnectionsAndNoRealSockets");passed=true;
    }catch(Throwable error){failure=error instanceof IllegalStateException&&error.getMessage()!=null?error.getMessage():error.getClass().getSimpleName();}
    finally{for(Gate gate:gates)gate.release.countDown();if(activity!=null)try{ui(activity::closeCurrent);}catch(Exception ignored){}JSONObject result=new JSONObject();try{result.put("schema",1).put("purpose","OWN_UID_FAKE_HTTPS_EDITOR_LIFECYCLE").put("passed",passed).put("failure",failure).put("assertions",assertions).put("checks",checks).put("productionEditorAndPostingTransportUsed",true).put("ownUid",true).put("internetPermission",false).put("fakeConnections",connections.get()).put("realNetworkRequests",0).put("realAccountReads",0).put("realCommunityWrites",0).put("syntheticSubmissionBodyOnly",true);}catch(Exception ignored){}Bundle bundle=new Bundle();bundle.putString("safe_editor_lifecycle_report",result.toString());finish(passed?0:1,bundle);}}
    private static final class Gate {
        final String fixture;final byte[] body;final boolean lost;
        final CountDownLatch reading=new CountDownLatch(1),release=new CountDownLatch(1),disconnected=new CountDownLatch(1);final ByteArrayOutputStream sent=new ByteArrayOutputStream();
        volatile boolean streamClosed,disconnectOnMain;volatile int reads,interrupts;
        Gate(String fixture,byte[] body,boolean blocked,boolean lost){this.fixture=fixture;this.body=body;this.lost=lost;if(!blocked)release.countDown();}
    }
    private static final class Connection extends HttpURLConnection {
        final Gate gate;Connection(URL url,Gate gate){super(url);this.gate=gate;}
        @Override public int getResponseCode(){return 200;}
        @Override public String getContentType(){return "application/json; charset=UTF-8";}
        @Override public OutputStream getOutputStream(){return gate.sent;}
        @Override public InputStream getInputStream(){final ByteArrayInputStream bytes=new ByteArrayInputStream(gate.body);return new InputStream(){
            @Override public int read(){throw new AssertionError("Buffered input expected");}
            @Override public int read(byte[] buffer,int offset,int length)throws IOException{gate.reads++;gate.reading.countDown();long until=SystemClock.uptimeMillis()+10000;while(gate.release.getCount()>0&&SystemClock.uptimeMillis()<until){try{gate.release.await(50,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){gate.interrupts++;}}if(gate.release.getCount()>0)throw new IOException("Synthetic release timeout");if(gate.lost)throw new IOException("Synthetic lost response after a submitted request");return bytes.read(buffer,offset,length);}
            @Override public void close(){gate.streamClosed=true;}
        };}
        @Override public void disconnect(){if(Looper.myLooper()==Looper.getMainLooper())gate.disconnectOnMain=true;gate.disconnected.countDown();gate.release.countDown();}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){connected=true;}
    }
}
