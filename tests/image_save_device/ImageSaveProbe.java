package cn.ngapure.imagesavefixturev195;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import cn.ngapure.app.MainActivity;
import cn.ngapure.app.NativeImageExport;
import cn.ngapure.app.NgaStore;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Callable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Production MainActivity 702 and image save API; generated valid BMP, private in-memory pipe sinks. */
public final class ImageSaveProbe extends Instrumentation {
    private static final String IMAGE_URL="https://img.nga.cn/attachments/mon_202610/22/synthetic-save.bmp";
    private final JSONArray checks=new JSONArray();private final JSONObject observations=new JSONObject();
    private final AtomicInteger blockedNetworkAttempts=new AtomicInteger();
    private volatile MainActivity mainActivity;private boolean authorized,observeBefore,observePrepareBeforeOnly;private int assertions;private byte[] original;
    private final Map<String,OriginalGate> originalReads=new ConcurrentHashMap<>();
    private ActivityMonitor pickerMonitor;
    private final Application.ActivityLifecycleCallbacks callbacks=new Application.ActivityLifecycleCallbacks(){
        public void onActivityResumed(Activity activity){if(activity instanceof MainActivity)mainActivity=(MainActivity)activity;}
        public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
    };
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticImageSavesOnly"));observeBefore=args!=null&&"true".equals(args.getString("observeBefore"));observePrepareBeforeOnly=args!=null&&"true".equals(args.getString("observePrepareBeforeOnly"));start();}
    private static Object field(Object owner,String name)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private static void field(Object owner,String name,Object value)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);}
    private <T>T ui(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private void check(boolean passed,String label)throws Exception{assertions++;checks.put(new JSONObject().put("check",label).put("passed",passed));if(!passed)throw new IllegalStateException(label);}
    private static byte[] bmp(){int width=1024,height=1024,header=54,pixels=width*height*3;byte[] bytes=new byte[header+pixels];ByteBuffer buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);buffer.put((byte)'B').put((byte)'M').putInt(bytes.length).putInt(0).putInt(header).putInt(40).putInt(width).putInt(height).putShort((short)1).putShort((short)24).putInt(0).putInt(pixels).putInt(2835).putInt(2835).putInt(0).putInt(0);for(int i=header;i<bytes.length;i++)bytes[i]=(byte)(i*31+7);return bytes;}
    private static final class OriginalGate {
        final String url;final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),closed=new CountDownLatch(1),cancelDisconnected=new CountDownLatch(1);
        final AtomicInteger originals=new AtomicInteger(),previews=new AtomicInteger(),disconnects=new AtomicInteger();volatile Thread worker;volatile boolean disconnectOnMain;
        OriginalGate(String id){url="https://img.nga.cn/attachments/mon_202610/22/synthetic-prepare-"+id+".bmp";}
    }
    private final class FakeImageRead extends HttpURLConnection {
        final OriginalGate gate;final boolean originalRead;
        FakeImageRead(URL url,OriginalGate gate){super(url);this.gate=gate;boolean bytes=false;for(StackTraceElement at:Thread.currentThread().getStackTrace())if("cn.ngapure.app.NativeImages".equals(at.getClassName())&&"bytes".equals(at.getMethodName()))bytes=true;originalRead=bytes;if(bytes){gate.originals.incrementAndGet();gate.worker=Thread.currentThread();}else gate.previews.incrementAndGet();}
        @Override public void connect(){}@Override public boolean usingProxy(){return false;}
        @Override public void disconnect(){if(originalRead){gate.disconnects.incrementAndGet();gate.disconnectOnMain|=android.os.Looper.myLooper()==android.os.Looper.getMainLooper();if(gate.release.getCount()>0)gate.cancelDisconnected.countDown();}}
        @Override public int getResponseCode(){return 200;}@Override public long getContentLengthLong(){return originalRead?1:original.length;}
        @Override public InputStream getInputStream(){if(!originalRead)return new ByteArrayInputStream(original);return new InputStream(){
            @Override public int read()throws IOException{return read(new byte[1],0,1);}
            @Override public int read(byte[] bytes,int start,int count)throws IOException{gate.entered.countDown();boolean interrupted=false;long until=SystemClock.uptimeMillis()+25000;while(gate.release.getCount()>0&&SystemClock.uptimeMillis()<until)try{gate.release.await(100,TimeUnit.MILLISECONDS);}catch(InterruptedException cancelled){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();throw new IOException("Controlled late original-image failure");}
            @Override public void close(){gate.closed.countDown();}
        };}
    }
    private void fakeNetwork(){URL.setURLStreamHandlerFactory(protocol->new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{OriginalGate gate=originalReads.get(url.toString());if(gate!=null)return new FakeImageRead(url,gate);blockedNetworkAttempts.incrementAndGet();throw new IOException("Helper has no network; scripted image reads only");}});}
    private void go(String kind,String id,String title)throws Exception{Class<?> screen=Class.forName("cn.ngapure.app.MainActivity$Screen");Constructor<?> constructor=screen.getDeclaredConstructor(String.class,String.class,String.class,int.class);constructor.setAccessible(true);Object next=constructor.newInstance(kind,id,title,1);Method go=MainActivity.class.getDeclaredMethod("go",screen,boolean.class);go.setAccessible(true);go.invoke(mainActivity,next,false);}
    private void prepare(String url)throws Exception{Method prepare=MainActivity.class.getDeclaredMethod("prepareImageExport",String.class);prepare.setAccessible(true);prepare.invoke(mainActivity,url);}
    private void awaitPreview()throws Exception{long until=SystemClock.uptimeMillis()+5000;while(!ui(()->(Boolean)field(mainActivity,"imageReady"))&&SystemClock.uptimeMillis()<until)SystemClock.sleep(10);check(ui(()->(Boolean)field(mainActivity,"imageReady")),"RealImagePageLoadsItsSyntheticPreviewBeforeOriginalPreparation");}
    private void awaitPreparationCallback(OriginalGate gate)throws Exception{
        check(gate.closed.await(4,TimeUnit.SECONDS),"ReleasedOriginalReadClosesItsInput");long until=SystemClock.uptimeMillis()+4000;boolean executing=true;
        while(executing&&SystemClock.uptimeMillis()<until){executing=false;Thread worker=gate.worker;if(worker!=null)for(StackTraceElement at:worker.getStackTrace())if(("cn.ngapure.app.MainActivity".equals(at.getClassName())&&at.getMethodName().startsWith("lambda$prepareImageExport$"))||("cn.ngapure.app.NativeImages".equals(at.getClassName())&&"bytes".equals(at.getMethodName())))executing=true;if(executing)SystemClock.sleep(10);}
        check(!executing,"ReleasedOriginalReadFinishesBeforeCheckingItsLateMainCallback");waitForIdleSync();
    }
    private void preparationCancellation()throws Exception{
        OriginalGate a=new OriginalGate("a"),b=new OriginalGate("b");originalReads.put(a.url,a);originalReads.put(b.url,b);pickerMonitor=addMonitor(new IntentFilter(Intent.ACTION_CREATE_DOCUMENT),null,true);
        try{
            ui(()->{go("image",a.url,"合成图片 A");return null;});awaitPreview();ui(()->{prepare(a.url);return null;});check(a.entered.await(4,TimeUnit.SECONDS),"ActualMainOriginalPreparationAWaitsOnScriptedRead");check(ui(()->(Boolean)field(mainActivity,"preparingExport")),"OriginalPreparationAOwnsItsPreparingState");
            ui(()->{go("image",b.url,"合成图片 B");return null;});boolean navigationReset=ui(()->!(Boolean)field(mainActivity,"preparingExport"));observations.put("prepareNavigationToBResetsPreparing",navigationReset);if(!observePrepareBeforeOnly)check(navigationReset,"NavigatingToBResetsOldPreparationState");else if(!navigationReset){observations.put("baselineFixtureResetRequiredToIsolateLateCallback",true);ui(()->{field(mainActivity,"preparingExport",false);return null;});}awaitPreview();ui(()->{prepare(b.url);return null;});check(b.entered.await(4,TimeUnit.SECONDS),"ActualMainOriginalPreparationBStartsWithoutWaitingForA");check(ui(()->(Boolean)field(mainActivity,"preparingExport")),"OriginalPreparationBHasItsOwnPreparingState");
            boolean aCancelled=a.cancelDisconnected.await(1,TimeUnit.SECONDS);observations.put("prepareAActivelyDisconnectedOnNavigation",aCancelled);if(!observePrepareBeforeOnly)check(aCancelled&&!a.disconnectOnMain,"LeavingAActivelyDisconnectsOriginalReadOffMain");
            a.release.countDown();awaitPreparationCallback(a);boolean bStillPreparing=ui(()->(Boolean)field(mainActivity,"preparingExport"));observations.put("prepareBLateAFailurePreservesPreparing",bStillPreparing);if(!observePrepareBeforeOnly)check(bStillPreparing,"LateAFailureCannotClearBPreparingState");
            check(ui(()->field(mainActivity,"savingImage")==null&&field(mainActivity,"preparedExport")==null),"LateOriginalFailureNeverProducesPickerState");if(!observePrepareBeforeOnly)check(ui(()->{android.widget.TextView button=(android.widget.TextView)field(mainActivity,"imageSaveButton");return button!=null&&!button.isEnabled()&&"下载原图…".contentEquals(button.getText());}),"LateACallbackCannotReenableBSaveButton");
            ui(()->{go("settings","","设置");return null;});boolean bCancelled=b.cancelDisconnected.await(1,TimeUnit.SECONDS);observations.put("prepareBActivelyDisconnectedOnLeaving",bCancelled);if(!observePrepareBeforeOnly)check(bCancelled&&!b.disconnectOnMain,"LeavingBActivelyDisconnectsOriginalReadOffMain");b.release.countDown();awaitPreparationCallback(b);
            check(ui(()->!(Boolean)field(mainActivity,"preparingExport")&&field(mainActivity,"savingImage")==null&&field(mainActivity,"preparedExport")==null&&field(mainActivity,"imageSaveButton")==null),"LeavingBRemovesPreparationAndItsViewReferences");check(a.originals.get()==1&&b.originals.get()==1,"EachExplicitOriginalPreparationPerformsOneReadWithoutRetry");check(pickerMonitor.getHits()==0,"OriginalPreparationRaceNeverOpensSystemFilePicker");observations.put("scriptedOriginalPreparationReads",a.originals.get()+b.originals.get()).put("scriptedImagePreviewReads",a.previews.get()+b.previews.get()).put("systemPickerRequests",pickerMonitor.getHits());
        }finally{a.release.countDown();b.release.countDown();originalReads.clear();if(pickerMonitor!=null){removeMonitor(pickerMonitor);pickerMonitor=null;}}
    }
    private ThreadPoolExecutor imageQueue()throws Exception{
        Class<?> saves=Class.forName("cn.ngapure.app.NativeImageSaves");for(Field field:saves.getDeclaredFields())if(Modifier.isStatic(field.getModifiers())&&ThreadPoolExecutor.class.isAssignableFrom(field.getType())){field.setAccessible(true);return (ThreadPoolExecutor)field.get(null);}throw new IllegalStateException("No bounded image save executor found");
    }
    private void idleQueue()throws Exception{ThreadPoolExecutor queue=imageQueue();long until=SystemClock.uptimeMillis()+3000;while((queue.getActiveCount()>0||!queue.getQueue().isEmpty())&&SystemClock.uptimeMillis()<until)SystemClock.sleep(10);check(queue.getActiveCount()==0&&queue.getQueue().isEmpty(),"ConfirmedSaveQueueIsIdleBetweenScenarios");}
    private void mainResultAndRecreation()throws Exception{
        NativeImageExport.Image image=NativeImageExport.inspect(original);ImageSaveProvider.Gate gate=ImageSaveProvider.register("recreate",original);MainActivity old=mainActivity;
        ui(()->{field(old,"savingImage",IMAGE_URL);field(old,"savingImageFormat","bmp");field(old,"preparedExport",image);Method result=MainActivity.class.getDeclaredMethod("onActivityResult",int.class,int.class,Intent.class);result.setAccessible(true);result.invoke(old,702,Activity.RESULT_OK,new Intent().setData(ImageSaveProvider.uri("recreate")));return null;});
        check(gate.firstChunk.await(5,TimeUnit.SECONDS),"ActualMain702WritesFirstBytesToPrivateSlowProvider");check(!gate.openOnMain,"ActualMain702NeverOpensTheFileOnMainThread");
        check(ui(()->field(old,"savingImage")==null&&field(old,"preparedExport")==null),"Main702ConsumesItsPickerStateBeforeAsyncSaving");
        ui(()->{old.recreate();return null;});long until=SystemClock.uptimeMillis()+7000;while((mainActivity==old||!old.isDestroyed())&&SystemClock.uptimeMillis()<until)SystemClock.sleep(20);waitForIdleSync();
        check(mainActivity!=old&&old.isDestroyed(),"RealMainActivityRecreationCompletesWhileOutputIsBlocked");
        observations.put("oldMainIoShutdown",((java.util.concurrent.ExecutorService)field(old,"io")).isShutdown());observations.put("providerOpenThreadInterruptedAfterRecreate",gate.openThread!=null&&gate.openThread.isInterrupted());
        gate.release.countDown();check(gate.finished.await(6,TimeUnit.SECONDS),"SlowProviderReceiverTerminatesAfterExplicitRelease");observations.put("recreateReceivedBytes",gate.received).put("recreateExpectedBytes",gate.expectedBytes).put("recreateExactBytes",gate.exact).put("recreateProviderError",gate.error);
        check(gate.opens.get()==1&&gate.receiverClosed,"ConfirmedMainSaveUsesOneProviderOpenAndClosesReceiver");
        if(!observeBefore)check(gate.exact,"ConfirmedMainSaveSurvivesRecreationWithExactOriginalBytes");
    }
    private boolean save(String id,NativeImageExport.Image image)throws Exception{Class<?> saves=Class.forName("cn.ngapure.app.NativeImageSaves");Method save=saves.getDeclaredMethod("save",android.content.Context.class,Uri.class,String.class,String.class,NativeImageExport.Image.class);return (Boolean)save.invoke(null,getTargetContext().getApplicationContext(),ImageSaveProvider.uri(id),IMAGE_URL,"bmp",image);}
    private void boundedQueue()throws Exception{
        idleQueue();NativeImageExport.Image image=NativeImageExport.inspect(original);ImageSaveProvider.Gate active=ImageSaveProvider.register("active",original),queuedA=ImageSaveProvider.register("queued-a",original),queuedB=ImageSaveProvider.register("queued-b",original),rejected=ImageSaveProvider.register("rejected",original);
        try{
            check(save("active",image),"ConfirmedSaveQueueAcceptsItsActiveWrite");check(active.firstChunk.await(4,TimeUnit.SECONDS),"ActiveSaveBlocksOnlyOnPrivateProviderPipe");
            check(save("queued-a",image)&&save("queued-b",image),"ConfirmedSaveQueueAcceptsAtMostTwoPendingWrites");long start=SystemClock.uptimeMillis();boolean accepted=save("rejected",image);check(!accepted&&SystemClock.uptimeMillis()-start<250,"FullSaveQueueRejectsPromptlyWithoutCallerThreadWriting");
            ThreadPoolExecutor queue=imageQueue();check(queue.getMaximumPoolSize()==1&&queue.getPoolSize()<=1&&queue.getQueue().size()==2,"ConfirmedSaveQueueStaysAtOneWorkerAndTwoQueuedTasks");check(rejected.opens.get()==0&&queuedA.opens.get()==0&&queuedB.opens.get()==0,"QueuedAndRejectedSavesNeverOpenProviderOnCallerThread");
            active.release.countDown();check(active.finished.await(5,TimeUnit.SECONDS)&&active.exact,"ActiveQueuedTestSavePreservesExactOriginalBytes");check(queuedA.firstChunk.await(4,TimeUnit.SECONDS),"FirstQueuedSaveStartsAfterActiveSaveFinishes");check(queuedB.opens.get()==0,"SecondQueuedSaveDoesNotBypassTheSingleWorker");
            queuedA.release.countDown();check(queuedA.finished.await(5,TimeUnit.SECONDS)&&queuedA.exact,"FirstQueuedSavePreservesExactOriginalBytes");check(queuedB.firstChunk.await(4,TimeUnit.SECONDS),"SecondQueuedSaveEventuallyStarts");queuedB.release.countDown();check(queuedB.finished.await(5,TimeUnit.SECONDS)&&queuedB.exact,"SecondQueuedSavePreservesExactOriginalBytes");idleQueue();
            check(rejected.opens.get()==0,"RejectedSaveIsNeverRetriedImplicitly");check(!active.openOnMain&&!queuedA.openOnMain&&!queuedB.openOnMain,"AllAcceptedFilesOpenOffTheMainThread");observations.put("boundedQueueAccepted",3).put("boundedQueueRejected",1).put("boundedQueueAllAcceptedExactBytes",true);
        }finally{active.release.countDown();queuedA.release.countDown();queuedB.release.countDown();rejected.release.countDown();}
    }
    @Override public void onStart(){Application application=null;boolean passed=false;String failure="NONE";try{
        check(authorized,"ExplicitSyntheticLocalImageSaveAuthorization");String[] permissions=getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(),PackageManager.GET_PERMISSIONS).requestedPermissions;boolean noInternet=true;if(permissions!=null)for(String p:permissions)if("android.permission.INTERNET".equals(p))noInternet=false;
        check(noInternet,"HelperHasNoInternetPermission");check(android.os.Process.myUid()==getTargetContext().getApplicationInfo().uid,"HelperUsesOwnUidAndPrivateProvider");fakeNetwork();
        NgaStore store=new NgaStore(getTargetContext());store.setAutoCheckIn(false);store.setMotionEnabled(false);store.setGlassEnabled(false);getTargetContext().getSharedPreferences("nga_unread_notifications",0).edit().putBoolean("enabled",false).commit();
        application=(Application)getTargetContext().getApplicationContext();application.registerActivityLifecycleCallbacks(callbacks);mainActivity=(MainActivity)startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(),MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();original=bmp();
        if(observePrepareBeforeOnly)preparationCancellation();else{mainResultAndRecreation();if(!observeBefore){boundedQueue();preparationCancellation();}}passed=true;
    }catch(Throwable error){failure=error instanceof IllegalStateException&&error.getMessage()!=null?error.getMessage():error.getClass().getSimpleName();}
    finally{for(OriginalGate gate:originalReads.values())gate.release.countDown();for(ImageSaveProvider.Gate gate:ImageSaveProvider.GATES.values())gate.release.countDown();if(mainActivity!=null&&!mainActivity.isDestroyed())try{ui(()->{mainActivity.finish();return null;});}catch(Exception ignored){}if(application!=null)application.unregisterActivityLifecycleCallbacks(callbacks);JSONObject report=new JSONObject();try{report.put("schema",1).put("purpose",observePrepareBeforeOnly?"OBSERVE_REAL_MAIN_ORIGINAL_PREPARE_RACE_BASELINE":observeBefore?"OBSERVE_REAL_MAIN_702_RECREATE_BASELINE":"OWN_UID_REAL_MAIN_702_BOUNDED_IMAGE_SAVE_AND_PREPARATION_LIFECYCLE").put("passed",passed).put("failure",failure).put("assertions",assertions).put("checks",checks).put("observations",observations).put("observationalBaseline",observeBefore||observePrepareBeforeOnly).put("productionMain702Used",!observePrepareBeforeOnly).put("productionMainPreparationUsed",!observeBefore).put("ownUid",true).put("internetPermission",false).put("realNetworkRequests",0).put("blockedHelperStartupNetworkAttempts",blockedNetworkAttempts.get()).put("realUserFilesWritten",0).put("remoteProviderAccesses",0).put("generatedBmpBytes",original==null?0:original.length).put("installedClientStarted",false).put("installedClientCredentialsAccessible",false);}catch(Exception ignored){}Bundle result=new Bundle();result.putString("safe_image_save_report",report.toString());finish(passed?0:1,result);}}
}
