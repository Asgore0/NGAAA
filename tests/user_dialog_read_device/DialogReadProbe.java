package cn.ngapure.dialogread;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import cn.ngapure.app.NativeGlassDialog;
import cn.ngapure.app.NativeUserActionDialogs;
import cn.ngapure.app.NgaStore;
import cn.ngapure.app.RequestCancellation;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** No transport, credentials, account data, Internet permission or real community mutation. */
public final class DialogReadProbe extends Instrumentation {
    private Activity activity;
    private boolean authorized,observeBaseline;
    private int assertions;
    private String stage="guard";
    private final JSONObject report=new JSONObject();
    private final List<NativeUserActionDialogs> owners=new ArrayList<>();
    private final List<Thread> workers=new ArrayList<>();
    private static final class Queue implements Executor {final List<Runnable> tasks=new ArrayList<>();@Override public void execute(Runnable task){tasks.add(task);}void complete(){if(tasks.isEmpty())throw new AssertionError("QUEUED_LOCAL_TASK_REQUIRED");tasks.remove(0).run();}}
    private static final class Fixture {final Queue queue;final NativeUserActionDialogs owner;Fixture(Queue q,NativeUserActionDialogs o){queue=q;owner=o;}}
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticDialogReadsOnly"));observeBaseline=args!=null&&"true".equals(args.getString("observeBaseline"));start();}
    private <T>T main(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private void check(String label,boolean value)throws Exception{report.put(label,value);assertions++;if(!value)throw new AssertionError(label);}
    private Fixture prepare()throws Exception{return main(()->{Queue queue=new Queue();NativeUserActionDialogs owner=new NativeUserActionDialogs(activity,false,queue,new NativeUserActionDialogs.Host(){public void changed(){}public void openUser(String uid,String name){throw new AssertionError("NO_REAL_USER_ROUTING");}public void chooseAvatar(NativeUserActionDialogs.AvatarSelection selection){throw new AssertionError("NO_REAL_PICKER");}});owners.add(owner);return new Fixture(queue,owner);});}
    private static Object field(Object target,String name)throws Exception{Field member=target.getClass().getDeclaredField(name);member.setAccessible(true);return member.get(target);}
    private NativeGlassDialog progress(Fixture value)throws Exception{List<?> dialogs=(List<?>)field(value.owner,"dialogs");return (NativeGlassDialog)dialogs.get(dialogs.size()-1);}
    private void read(Fixture value,Callable<Object> work,AtomicInteger delivered)throws Exception {
        Class<?> workType=Class.forName("cn.ngapure.app.NativeUserActionDialogs$Work"),doneType=Class.forName("cn.ngapure.app.NativeUserActionDialogs$Done");
        Object workProxy=Proxy.newProxyInstance(workType.getClassLoader(),new Class<?>[]{workType},(proxy,method,args)->work.call());
        Object doneProxy=Proxy.newProxyInstance(doneType.getClassLoader(),new Class<?>[]{doneType},(proxy,method,args)->{delivered.incrementAndGet();return null;});
        Method read=NativeUserActionDialogs.class.getDeclaredMethod("read",String.class,workType,doneType);read.setAccessible(true);read.invoke(value.owner,"本地受控读取",workProxy,doneProxy);
    }
    private void write(Fixture value,NativeGlassDialog dialog,AtomicInteger executed,AtomicInteger delivered)throws Exception {
        Class<?> workType=Class.forName("cn.ngapure.app.NativeUserActionDialogs$Work"),doneType=Class.forName("cn.ngapure.app.NativeUserActionDialogs$Done");
        Object workProxy=Proxy.newProxyInstance(workType.getClassLoader(),new Class<?>[]{workType},(proxy,method,args)->{executed.incrementAndGet();return "local counter";});
        Object doneProxy=Proxy.newProxyInstance(doneType.getClassLoader(),new Class<?>[]{doneType},(proxy,method,args)->{delivered.incrementAndGet();return null;});
        Method write=NativeUserActionDialogs.class.getDeclaredMethod("write",NativeGlassDialog.class,workType,doneType);write.setAccessible(true);write.invoke(value.owner,dialog,workProxy,doneProxy);
    }
    private boolean canceledQueuedRead(boolean closeOwner)throws Exception {
        Fixture value=prepare();AtomicInteger executed=new AtomicInteger(),delivered=new AtomicInteger();
        main(()->{read(value,()->{executed.incrementAndGet();return "local fixture";},delivered);if(closeOwner)value.owner.close();else progress(value).getButton(DialogInterface.BUTTON_NEGATIVE).performClick();value.queue.complete();return null;});
        return executed.get()>0;
    }
    private void observe()throws Exception {stage="observeQueuedReadAfterCancel";boolean canceledReadRan=canceledQueuedRead(false);stage="observeQueuedReadAfterOwnerClose";boolean retiredReadRan=canceledQueuedRead(true);report.put("cancelledQueuedReadStillExecuted",canceledReadRan).put("closedOwnerQueuedReadStillExecuted",retiredReadRan).put("expectedRegressionObserved",canceledReadRan&&retiredReadRan).put("completed",true).put("observationOnly",true);}
    private void strict()throws Exception {
        stage="queuedCancel";check("cancelledQueuedReadNeverExecutes",!canceledQueuedRead(false));stage="queuedOwnerClose";check("closedOwnerQueuedReadNeverExecutes",!canceledQueuedRead(true));
        stage="liveRead";Fixture live=prepare();AtomicInteger liveRan=new AtomicInteger(),liveDelivered=new AtomicInteger();main(()->{read(live,()->{liveRan.incrementAndGet();RequestCancellation.check();return "local fixture";},liveDelivered);live.queue.complete();return null;});check("liveReadStillCompletesExactlyOnce",liveRan.get()==1&&liveDelivered.get()==1);check("completedReadClosesItsProgressWindow",main(()->((List<?>)field(live.owner,"dialogs")).isEmpty()));
        stage="runningCancel";Fixture running=prepare();CountDownLatch entered=new CountDownLatch(1),exited=new CountDownLatch(1);AtomicInteger canceled=new AtomicInteger(),runningDelivered=new AtomicInteger();final java.util.concurrent.atomic.AtomicBoolean stop=new java.util.concurrent.atomic.AtomicBoolean();main(()->{read(running,()->{entered.countDown();try{long until=SystemClock.uptimeMillis()+5000;while(SystemClock.uptimeMillis()<until&&!stop.get()){RequestCancellation.check();SystemClock.sleep(5);}return "local read";}catch(java.io.InterruptedIOException canceledRead){canceled.incrementAndGet();throw canceledRead;}finally{exited.countDown();}},runningDelivered);return null;});Thread worker=new Thread(()->running.queue.complete(),"SyntheticDialogRead");workers.add(worker);worker.start();check("syntheticReadEntered",entered.await(2,TimeUnit.SECONDS));long before=SystemClock.uptimeMillis();main(()->{progress(running).getButton(DialogInterface.BUTTON_NEGATIVE).performClick();return null;});check("cancelDoesNotWaitForWorkerOnUi",SystemClock.uptimeMillis()-before<500);boolean ended=exited.await(2,TimeUnit.SECONDS);stop.set(true);worker.join(2000);check("runningReadObservesCancellation",ended&&canceled.get()==1);waitForIdleSync();check("canceledRunningReadHasNoUiDelivery",runningDelivered.get()==0);check("canceledRunningReadHasNoErrorPrompt",main(()->((List<?>)field(running.owner,"dialogs")).isEmpty()));
        stage="readAfterClose";Fixture retired=prepare();AtomicInteger retiredRan=new AtomicInteger(),retiredDelivered=new AtomicInteger();main(()->{retired.owner.close();read(retired,()->{retiredRan.incrementAndGet();return "late";},retiredDelivered);return null;});check("closedOwnerDoesNotQueueNewRead",retired.queue.tasks.isEmpty());
        stage="confirmedCounterWriteSurvives";Fixture write=prepare();AtomicInteger written=new AtomicInteger(),writeDelivered=new AtomicInteger();NativeGlassDialog confirm=main(()->{Method show=NativeUserActionDialogs.class.getDeclaredMethod("show",NativeGlassDialog.Builder.class);show.setAccessible(true);return (NativeGlassDialog)show.invoke(write.owner,new NativeGlassDialog.Builder(activity,false).setTitle("仅合成本地计数").setPositiveButton("合成确认",null));});main(()->{write(write,confirm,written,writeDelivered);write.owner.close();write.queue.complete();return null;});check("confirmedLocalCounterWriteOutlivesClosedUi",written.get()==1);check("retiredUiRejectsWriteResultPresentation",writeDelivered.get()==0);
        report.put("completed",true).put("passed",true).put("assertions",assertions);
    }
    @Override public void onStart(){try {
        if(!authorized||!"cn.ngapure.dialogread".equals(getTargetContext().getPackageName())||getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("OWN_UID_NO_INTERNET_SYNTHETIC_REQUIRED");
        activity=startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(),DialogReadActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));new NgaStore(getTargetContext()).setMotionEnabled(true);waitForIdleSync();if(observeBaseline)observe();else strict();
    }catch(Exception|AssertionError failure){try{report.put("completed",false).put("passed",false).put("failureStage",stage).put("failureType",failure.getClass().getSimpleName()).put("assertions",assertions);}catch(Exception ignored){}
    }finally{try{main(()->{for(NativeUserActionDialogs owner:owners)owner.close();if(activity!=null)activity.finish();return null;});for(Thread worker:workers){worker.interrupt();worker.join(1000);}report.put("ownUidOnly",true).put("internetPermission",false).put("noNetworkTransport",true).put("realAccountAccessed",false).put("realClientStarted",false).put("communityMutationCount",0).put("writeCaseIsLocalCounterOnly",true);}catch(Exception ignored){}Bundle result=new Bundle();result.putString("safe_dialog_read_report",report.toString());finish(Activity.RESULT_OK,result);}}
}
