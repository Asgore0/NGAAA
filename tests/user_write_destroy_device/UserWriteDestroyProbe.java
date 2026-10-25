package cn.ngapure.writedestroy;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import cn.ngapure.app.MainActivity;
import cn.ngapure.app.NativeCommunity;
import cn.ngapure.app.NativeGlassDialog;
import cn.ngapure.app.NativeUserActionDialogs;
import cn.ngapure.app.NativeUserActions;
import cn.ngapure.app.NgaStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Actual production Main.onDestroy, isolated UID, generated local work and no Internet permission. */
public final class UserWriteDestroyProbe extends Instrumentation {
    private boolean authorized;
    private int assertions;
    private String stage="guard";
    private final JSONObject report=new JSONObject();
    private final List<MainActivity> activities=new ArrayList<>();
    private final List<CountDownLatch> releases=new ArrayList<>();
    private static final class Queue implements java.util.concurrent.Executor {final List<Runnable> pending=new ArrayList<>();public void execute(Runnable task){pending.add(task);}void complete(){if(pending.isEmpty())throw new AssertionError("LOCAL_QUEUED_WORK_REQUIRED");pending.remove(0).run();}}
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticConfirmedWritesOnly"));start();}
    private <T>T main(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private void check(String label,boolean value)throws Exception{report.put(label,value);assertions++;if(!value)throw new AssertionError(label);}
    private static Object field(Object target,String name)throws Exception{Field member=target.getClass().getDeclaredField(name);member.setAccessible(true);return member.get(target);}
    private MainActivity launch()throws Exception {
        MainActivity value=(MainActivity)startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(),MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));activities.add(value);waitForIdleSync();return value;
    }
    private void destroy(MainActivity value)throws Exception{main(()->{value.finish();return null;});for(int i=0;i<80&&!value.isDestroyed();i++)SystemClock.sleep(25);if(!value.isDestroyed())throw new AssertionError("ACTUAL_MAIN_DESTROY_REQUIRED");waitForIdleSync();}
    private NativeUserActionDialogs sheets(MainActivity value)throws Exception{return main(()->{Method method=MainActivity.class.getDeclaredMethod("userSheets");method.setAccessible(true);return (NativeUserActionDialogs)method.invoke(value);});}
    private NativeGlassDialog confirm(MainActivity activity,NativeUserActionDialogs owner,Callable<Object> action,AtomicInteger delivered)throws Exception{return main(()->{
        Class<?> workType=Class.forName("cn.ngapure.app.NativeUserActionDialogs$Work"),doneType=Class.forName("cn.ngapure.app.NativeUserActionDialogs$Done");
        Object work=Proxy.newProxyInstance(workType.getClassLoader(),new Class<?>[]{workType},(proxy,method,args)->action.call());
        Object done=Proxy.newProxyInstance(doneType.getClassLoader(),new Class<?>[]{doneType},(proxy,method,args)->{delivered.incrementAndGet();return null;});
        Method show=NativeUserActionDialogs.class.getDeclaredMethod("show",NativeGlassDialog.Builder.class);show.setAccessible(true);
        NativeGlassDialog dialog=(NativeGlassDialog)show.invoke(owner,new NativeGlassDialog.Builder(activity,false).setTitle("仅合成本机计数").setMessage("不调用社区接口，不访问账号").setNegativeButton("取消",null).setPositiveButton("合成确认",null));
        Method write=NativeUserActionDialogs.class.getDeclaredMethod("write",NativeGlassDialog.class,workType,doneType);write.setAccessible(true);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{try{write.invoke(owner,dialog,work,done);}catch(Exception error){throw new IllegalStateException("LOCAL_WRITE_INVOCATION",error);}});
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();return dialog;
    });}
    private void activeTeardown()throws Exception {
        stage="activeConfirmedWriteDestroy";MainActivity activity=launch();NativeUserActionDialogs owner=sheets(activity);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),exited=new CountDownLatch(1);releases.add(release);
        AtomicBoolean interrupted=new AtomicBoolean();AtomicInteger completed=new AtomicInteger(),delivered=new AtomicInteger();
        NativeGlassDialog dialog=confirm(activity,owner,()->{entered.countDown();try{if(!release.await(15,TimeUnit.SECONDS))throw new java.io.IOException("SYNTHETIC_RELEASE_TIMEOUT");completed.incrementAndGet();return "local completion";}catch(InterruptedException stop){interrupted.set(true);throw new java.io.IOException("SYNTHETIC_TEARDOWN_INTERRUPT");}finally{exited.countDown();}},delivered);
        check("nativePositiveConfirmationAccepted",main(()->!dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled()));check("confirmedLocalWorkStartedOnMainIo",entered.await(2,TimeUnit.SECONDS));
        ThreadPoolExecutor executor=main(()->(ThreadPoolExecutor)field(activity,"io"));check("userSheetsUsesMainIo",main(()->field(owner,"executor")==executor));
        destroy(activity);boolean ended=exited.await(2,TimeUnit.SECONDS);release.countDown();exited.await(2,TimeUnit.SECONDS);
        report.put("actualMainDestroyed",activity.isDestroyed()).put("mainIoShutdownOnDestroy",executor.isShutdown()).put("runningConfirmedWriteInterrupted",interrupted.get()).put("runningConfirmedWriteStoppedBeforeCompletion",ended&&completed.get()==0).put("retiredUiDidNotReceiveCompletion",delivered.get()==0);
    }
    private void queuedTeardown()throws Exception {
        stage="queuedConfirmedWriteDestroy";MainActivity activity=launch();NativeUserActionDialogs owner=sheets(activity);ThreadPoolExecutor executor=main(()->(ThreadPoolExecutor)field(activity,"io"));
        CountDownLatch occupied=new CountDownLatch(3),release=new CountDownLatch(1),exited=new CountDownLatch(3);releases.add(release);
        for(int i=0;i<3;i++)executor.execute(()->{occupied.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException stop){/* Synthetic worker records shutdown through its exit latch. */}finally{exited.countDown();}});
        check("allThreeMainIoWorkersOccupiedByLocalCounters",occupied.await(3,TimeUnit.SECONDS));AtomicInteger started=new AtomicInteger(),delivered=new AtomicInteger();NativeGlassDialog dialog=confirm(activity,owner,()->{started.incrementAndGet();return "queued local counter";},delivered);
        check("queuedNativePositiveConfirmationAccepted",main(()->!dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled()));check("confirmedLocalTaskQueuedBeforeDestroy",executor.getQueue().size()>=1&&started.get()==0);
        destroy(activity);boolean workersEnded=exited.await(2,TimeUnit.SECONDS);release.countDown();SystemClock.sleep(150);
        report.put("queuedMainDestroyed",activity.isDestroyed()).put("queuedConfirmedWriteDiscarded",started.get()==0&&executor.getQueue().isEmpty()).put("syntheticBlockersInterruptedDuringDestroy",workersEnded).put("queuedWriteUiResultNotPresented",delivered.get()==0);
        stage="restartDoesNotReplay";MainActivity restarted=launch();SystemClock.sleep(300);report.put("restartDidNotReplayDiscardedLocalTask",started.get()==0);destroy(restarted);
    }
    private void sessionAndNoRetry()throws Exception {
        stage="exactSessionAndNoRetry";final String[] cookie={"ngaPassportUid=70001; ngaPassportCid=synthetic-a"};AtomicInteger reads=new AtomicInteger(),mutations=new AtomicInteger();NativeCommunity.Session session=new NativeCommunity.Session(){public String host(){return "bbs.nga.cn";}public String cookie(){return cookie[0];}};
        NativeUserActions api=new NativeUserActions(session,(request,header)->{if(request.mutation){mutations.incrementAndGet();throw new java.io.IOException("synthetic result unknown");}reads.incrementAndGet();return new NativeCommunity.Response(200,"{\"data\":[\"old synthetic signature\"]}".getBytes(StandardCharsets.UTF_8),"application/json; charset=UTF-8");});
        boolean uncertain=false;try{api.setSignature("new synthetic signature");}catch(NativeCommunity.CommunityException expected){uncertain=expected.code==NativeCommunity.Code.UNCONFIRMED;}
        check("syntheticUnknownWriteRemainsUnconfirmed",uncertain);check("unknownWriteHasOneMutationAttemptAndReadback",mutations.get()==1&&reads.get()==2);SystemClock.sleep(150);check("unknownWriteDoesNotAutomaticallyRetry",mutations.get()==1);
        cookie[0]="ngaPassportUid=70002; ngaPassportCid=synthetic-b";int before=mutations.get()+reads.get();boolean refused=false;try{api.setSignature("different account must not receive this");}catch(NativeCommunity.CommunityException expected){refused=expected.code==NativeCommunity.Code.LOGIN_REQUIRED;}
        check("changedExactSessionRefusesNewWorkBeforeTransport",refused&&before==mutations.get()+reads.get());report.put("syntheticTransportMutationCalls",mutations.get());
    }
    private void localClearScopeObservation()throws Exception {
        stage="localClearConfirmationScope";MainActivity activity=launch();NgaStore store=new NgaStore(getTargetContext());Queue queue=new Queue();
        NativeUserActionDialogs owner=main(()->new NativeUserActionDialogs(activity,false,queue,new NativeUserActionDialogs.Host(){public void changed(){}public void openUser(String uid,String name){}public void chooseAvatar(NativeUserActionDialogs.AvatarSelection selection){throw new AssertionError("NO_PICKER");}}));
        main(()->{
            store.setCookie("ngaPassportUid=70001; ngaPassportCid=synthetic-a");NativeUserActions.setLocallyBlocked(activity,"70009","Synthetic A marker",true);
            store.setCookie("ngaPassportUid=70002; ngaPassportCid=synthetic-b");NativeUserActions.setLocallyBlocked(activity,"70008","Synthetic B marker",true);
            store.setCookie("ngaPassportUid=70001; ngaPassportCid=synthetic-a");
            Method clear=NativeUserActionDialogs.class.getDeclaredMethod("clearBlocks");clear.setAccessible(true);clear.invoke(owner);
            List<?> dialogs=(List<?>)field(owner,"dialogs");NativeGlassDialog dialog=(NativeGlassDialog)dialogs.get(dialogs.size()-1);dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();return null;
        });
        check("localClearWasConfirmedUnderSyntheticAccountA",queue.pending.size()==1&&"70001".equals(store.accountId()));
        main(()->{store.setCookie("ngaPassportUid=70002; ngaPassportCid=synthetic-b");queue.complete();return null;});
        boolean changedB=!NativeUserActions.isLocallyBlocked(getTargetContext(),"70008");
        main(()->{store.setCookie("ngaPassportUid=70001; ngaPassportCid=synthetic-a");return null;});
        boolean retainedA=NativeUserActions.isLocallyBlocked(getTargetContext(),"70009");
        report.put("aConfirmedLocalClearErasedSyntheticB",changedB).put("aConfirmedLocalClearLeftSyntheticAUnchanged",retainedA).put("localScopeRegressionObserved",changedB&&retainedA);
        main(()->{owner.close();store.clearLogin();return null;});destroy(activity);
    }
    @Override public void onStart(){try {
        if(!authorized||!"cn.ngapure.writedestroy".equals(getTargetContext().getPackageName())||getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("OWN_UID_NO_INTERNET_SYNTHETIC_REQUIRED");
        NgaStore store=new NgaStore(getTargetContext());store.setAutoCheckIn(false);store.setMotionEnabled(false);store.clearLogin();getTargetContext().getSharedPreferences("nga_unread_notifications",0).edit().putBoolean("enabled",false).commit();
        activeTeardown();queuedTeardown();sessionAndNoRetry();localClearScopeObservation();report.put("expectedRegressionObserved",report.optBoolean("runningConfirmedWriteInterrupted")&&report.optBoolean("queuedConfirmedWriteDiscarded")).put("completed",true).put("assertions",assertions).put("observationOnly",true);
    }catch(Exception|AssertionError failure){try{report.put("completed",false).put("failureStage",stage).put("failureType",failure.getClass().getSimpleName()).put("assertions",assertions);}catch(Exception ignored){}
    }finally{try{for(CountDownLatch release:releases)release.countDown();for(MainActivity activity:activities)if(!activity.isDestroyed())destroy(activity);report.put("ownUidOnly",true).put("internetPermission",false).put("realAccountAccessed",false).put("realClientStarted",false).put("realCommunityMutationCount",0).put("confirmedWriteCasesAreLocalCounters",true);}catch(Exception ignored){}Bundle result=new Bundle();result.putString("safe_user_write_destroy_report",report.toString());finish(Activity.RESULT_OK,result);}}
}
