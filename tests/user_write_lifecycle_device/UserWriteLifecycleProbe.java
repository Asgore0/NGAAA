package cn.ngapure.writelifecycle;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import cn.ngapure.app.MainActivity;
import cn.ngapure.app.NativeGlassDialog;
import cn.ngapure.app.NativeUserActionDialogs;
import cn.ngapure.app.NativeUserActions;
import cn.ngapure.app.NativeUserWrites;
import cn.ngapure.app.NgaStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Exact production Main and queue in an isolated UID; only synthetic local counters and preferences. */
public final class UserWriteLifecycleProbe extends Instrumentation {
    private boolean authorized;
    private int assertions;
    private String stage="guard";
    private final JSONObject report=new JSONObject();
    private final List<MainActivity> activities=new ArrayList<>();
    private final List<NativeUserActionDialogs> extraOwners=new ArrayList<>();
    private final List<CountDownLatch> releases=new ArrayList<>();
    private static final class Queue implements Executor {final List<Runnable> pending=new ArrayList<>();public void execute(Runnable task){pending.add(task);}void complete(){if(pending.isEmpty())throw new AssertionError("LOCAL_QUEUED_WORK_REQUIRED");pending.remove(0).run();}}
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticConfirmedWritesOnly"));start();}
    private <T>T main(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private void check(String label,boolean value)throws Exception{report.put(label,value);assertions++;if(!value)throw new AssertionError(label);}
    private static Object field(Object target,String name)throws Exception{Field member=target.getClass().getDeclaredField(name);member.setAccessible(true);return member.get(target);}
    private MainActivity launch()throws Exception{MainActivity value=(MainActivity)startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(),MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));activities.add(value);waitForIdleSync();return value;}
    private void destroy(MainActivity value)throws Exception{main(()->{value.finish();return null;});for(int i=0;i<80&&!value.isDestroyed();i++)SystemClock.sleep(25);if(!value.isDestroyed())throw new AssertionError("ACTUAL_MAIN_DESTROY_REQUIRED");waitForIdleSync();}
    private NativeUserActionDialogs sheets(MainActivity value)throws Exception{return main(()->{Method method=MainActivity.class.getDeclaredMethod("userSheets");method.setAccessible(true);return (NativeUserActionDialogs)method.invoke(value);});}
    private static TextView textContaining(View view,String label){if(view instanceof TextView&&((TextView)view).getText().toString().contains(label))return (TextView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView found=textContaining(group.getChildAt(i),label);if(found!=null)return found;}}return null;}
    private NativeGlassDialog confirm(MainActivity activity,NativeUserActionDialogs owner,Callable<Object> action,AtomicInteger delivered)throws Exception{return main(()->{
        Class<?> workType=Class.forName("cn.ngapure.app.NativeUserActionDialogs$Work"),doneType=Class.forName("cn.ngapure.app.NativeUserActionDialogs$Done");
        Object work=Proxy.newProxyInstance(workType.getClassLoader(),new Class<?>[]{workType},(proxy,method,args)->action.call());Object done=Proxy.newProxyInstance(doneType.getClassLoader(),new Class<?>[]{doneType},(proxy,method,args)->{delivered.incrementAndGet();return null;});
        Method show=NativeUserActionDialogs.class.getDeclaredMethod("show",NativeGlassDialog.Builder.class);show.setAccessible(true);NativeGlassDialog dialog=(NativeGlassDialog)show.invoke(owner,new NativeGlassDialog.Builder(activity,false).setTitle("仅合成本机计数").setNegativeButton("取消",null).setPositiveButton("合成确认",null));
        Method write=NativeUserActionDialogs.class.getDeclaredMethod("write",NativeGlassDialog.class,workType,doneType);write.setAccessible(true);dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{try{write.invoke(owner,dialog,work,done);}catch(Exception error){throw new IllegalStateException("LOCAL_WRITE_INVOCATION",error);}});dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();return dialog;
    });}
    private void activeAndQueuedSurvive()throws Exception {
        stage="activeConfirmedWriteDestroy";MainActivity activity=launch();NativeUserActionDialogs owner=sheets(activity);Executor writes=NativeUserWrites.executor();
        check("mainUsesDedicatedConfirmedWriteQueue",main(()->field(owner,"confirmedWriteExecutor")==writes&&field(owner,"executor")==field(activity,"io")&&field(owner,"executor")!=writes));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),completed=new CountDownLatch(1);releases.add(release);AtomicBoolean interrupted=new AtomicBoolean();AtomicInteger calls=new AtomicInteger(),delivered=new AtomicInteger();
        NativeGlassDialog active=confirm(activity,owner,()->{calls.incrementAndGet();entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new java.io.IOException("LOCAL_RELEASE_TIMEOUT");return "local result";}catch(InterruptedException stop){interrupted.set(true);throw new java.io.IOException("LOCAL_WRITE_INTERRUPTED");}finally{completed.countDown();}},delivered);
        check("activeNativeConfirmationAccepted",main(()->!active.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled()));check("activeConfirmedWorkStarted",entered.await(2,TimeUnit.SECONDS));destroy(activity);check("destroyDoesNotInterruptActiveConfirmedWrite",!completed.await(200,TimeUnit.MILLISECONDS)&&!interrupted.get());release.countDown();check("activeWriteCompletesAfterDestroy",completed.await(2,TimeUnit.SECONDS)&&calls.get()==1&&!interrupted.get());waitForIdleSync();check("destroyedOwnerReceivesNoActiveWriteResult",delivered.get()==0);
        stage="queueCapacityAndDestroy";MainActivity queuedActivity=launch();NativeUserActionDialogs queuedOwner=sheets(queuedActivity);CountDownLatch occupied=new CountDownLatch(1),unblock=new CountDownLatch(1);releases.add(unblock);
        writes.execute(()->{occupied.countDown();try{unblock.await(10,TimeUnit.SECONDS);}catch(InterruptedException stop){Thread.currentThread().interrupt();}});check("singleWriteWorkerOccupiedBySyntheticTask",occupied.await(2,TimeUnit.SECONDS));
        AtomicInteger queuedCalls=new AtomicInteger(),queuedResults=new AtomicInteger(),rejectedCalls=new AtomicInteger();CountDownLatch queuedDone=new CountDownLatch(2);
        Callable<Object> queuedWork=()->{queuedCalls.incrementAndGet();queuedDone.countDown();return "queued local result";};NativeGlassDialog first=confirm(queuedActivity,queuedOwner,queuedWork,queuedResults),second=confirm(queuedActivity,queuedOwner,queuedWork,queuedResults);check("twoWaitingNativeConfirmationsAccepted",main(()->!first.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled()&&!second.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled()));check("acceptedWritesWaitWithoutRunning",queuedCalls.get()==0);
        NativeGlassDialog rejected=confirm(queuedActivity,queuedOwner,()->{rejectedCalls.incrementAndGet();return "must not run";},new AtomicInteger());check("fullQueueRestoresConfirmationButtons",main(()->rejected.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled()&&rejected.getButton(DialogInterface.BUTTON_NEGATIVE).isEnabled()&&"合成确认".contentEquals(rejected.getButton(DialogInterface.BUTTON_POSITIVE).getText())));check("fullQueueRestoresCancelability",main(()->Boolean.TRUE.equals(field(rejected,"cancelAllowed"))));
        check("fullQueueClearlyStatesNothingWasSent",main(()->{for(Object value:(List<?>)field(queuedOwner,"dialogs")){NativeGlassDialog dialog=(NativeGlassDialog)value;if(textContaining(dialog.getWindow().getDecorView(),"本次未发出请求")!=null)return true;}return false;}));check("fullQueueRunsNoRejectedWork",rejectedCalls.get()==0);
        destroy(queuedActivity);unblock.countDown();check("bothAcceptedQueuedWritesCompleteAfterDestroy",queuedDone.await(3,TimeUnit.SECONDS)&&queuedCalls.get()==2);waitForIdleSync();check("destroyedOwnerReceivesNoQueuedWriteResults",queuedResults.get()==0);SystemClock.sleep(100);check("rejectedTaskNeverAutomaticallyRetries",rejectedCalls.get()==0);
        stage="restartNoReplay";MainActivity restarted=launch();SystemClock.sleep(150);check("activityRestartDoesNotReplayCompletedOrRejectedWrites",calls.get()==1&&queuedCalls.get()==2&&rejectedCalls.get()==0);destroy(restarted);
    }
    private NativeUserActionDialogs standalone(MainActivity activity,Executor read,Executor write){NativeUserActionDialogs owner=new NativeUserActionDialogs(activity,false,read,write,new NativeUserActionDialogs.Host(){public void changed(){}public void openUser(String uid,String name){}public void chooseAvatar(NativeUserActionDialogs.AvatarSelection selection){throw new AssertionError("NO_PICKER");}});extraOwners.add(owner);return owner;}
    private void scopeGuards()throws Exception {
        stage="changedScopeResultUi";MainActivity activity=launch();NgaStore store=new NgaStore(getTargetContext());NativeUserActionDialogs owner=main(()->standalone(activity,Runnable::run,NativeUserWrites.executor()));CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),completed=new CountDownLatch(1);releases.add(release);AtomicInteger presented=new AtomicInteger(),calls=new AtomicInteger();
        NativeGlassDialog dialog=confirm(activity,owner,()->{calls.incrementAndGet();entered.countDown();release.await(5,TimeUnit.SECONDS);completed.countDown();return "old scope local result";},presented);check("scopeTestConfirmedWorkStarted",entered.await(2,TimeUnit.SECONDS));main(()->{store.setCookie("ngaPassportUid=70002; ngaPassportCid=synthetic-b");return null;});release.countDown();check("changedScopeStillLetsStartedWorkFinish",completed.await(2,TimeUnit.SECONDS)&&calls.get()==1);for(int i=0;i<60&&main(()->dialog.isShowing());i++)SystemClock.sleep(25);waitForIdleSync();check("changedScopeSuppressesOldWriteResultUi",presented.get()==0);check("changedScopeClosesOldConfirmation",main(()->!dialog.isShowing()));
        stage="localClearBoundToConfirmingAccount";Queue queue=new Queue();NativeUserActionDialogs local=main(()->standalone(activity,queue,queue));
        main(()->{store.setCookie("ngaPassportUid=70001; ngaPassportCid=synthetic-a");NativeUserActions.setLocallyBlocked(activity,"70009","Synthetic A marker",true);store.setCookie("ngaPassportUid=70002; ngaPassportCid=synthetic-b");NativeUserActions.setLocallyBlocked(activity,"70008","Synthetic B marker",true);store.setCookie("ngaPassportUid=70001; ngaPassportCid=synthetic-a");Method clear=NativeUserActionDialogs.class.getDeclaredMethod("clearBlocks");clear.setAccessible(true);clear.invoke(local);List<?> values=(List<?>)field(local,"dialogs");NativeGlassDialog confirm=(NativeGlassDialog)values.get(values.size()-1);confirm.getButton(DialogInterface.BUTTON_POSITIVE).performClick();return null;});check("localClearQueuesUnderConfirmingAccountA",queue.pending.size()==1&&"70001".equals(store.accountId()));main(()->{store.setCookie("ngaPassportUid=70002; ngaPassportCid=synthetic-b");queue.complete();return null;});check("accountSwitchPreservesBLocalBlocks",NativeUserActions.isLocallyBlocked(getTargetContext(),"70008"));main(()->{store.setCookie("ngaPassportUid=70001; ngaPassportCid=synthetic-a");return null;});check("staleClearDoesNotModifyAAfterSwitch",NativeUserActions.isLocallyBlocked(getTargetContext(),"70009"));main(()->{local.close();owner.close();store.clearLogin();return null;});destroy(activity);
    }
    @Override public void onStart(){try {
        if(!authorized||!"cn.ngapure.writelifecycle".equals(getTargetContext().getPackageName())||getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("OWN_UID_NO_INTERNET_SYNTHETIC_REQUIRED");
        NgaStore store=new NgaStore(getTargetContext());store.setAutoCheckIn(false);store.setMotionEnabled(false);store.clearLogin();getTargetContext().getSharedPreferences("nga_unread_notifications",0).edit().putBoolean("enabled",false).commit();activeAndQueuedSurvive();scopeGuards();report.put("completed",true).put("passed",true).put("assertions",assertions);
    }catch(Exception|AssertionError failure){try{report.put("completed",false).put("passed",false).put("failureStage",stage).put("failureType",failure.getClass().getSimpleName()).put("assertions",assertions);}catch(Exception ignored){}
    }finally{try{for(CountDownLatch release:releases)release.countDown();main(()->{for(NativeUserActionDialogs owner:extraOwners)owner.close();return null;});for(MainActivity activity:activities)if(!activity.isDestroyed())destroy(activity);report.put("ownUidOnly",true).put("internetPermission",false).put("realAccountAccessed",false).put("realClientStarted",false).put("realCommunityMutationCount",0).put("confirmedWriteCasesAreLocalCounters",true).put("localScopeCasesUseOnlyFixturePreferences",true).put("tasksPersistedOrReplayedAcrossProcessDeath",false);}catch(Exception ignored){}Bundle result=new Bundle();result.putString("safe_user_write_lifecycle_report",report.toString());finish(Activity.RESULT_OK,result);}}
}
