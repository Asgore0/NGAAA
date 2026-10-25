package cn.ngapure.glassaction;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Button;
import cn.ngapure.app.NativeGlassDialog;
import cn.ngapure.app.NgaStore;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Counters only: no account, native route, community operation or Internet permission. */
public final class GlassActionProbe extends Instrumentation {
    private Activity activity;
    private boolean authorized,observeBaseline;
    private String stage="guard";
    private int assertions;
    private final JSONObject report=new JSONObject();
    private final ArrayList<NativeGlassDialog> dialogs=new ArrayList<>();
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticActionsOnly"));observeBaseline=args!=null&&"true".equals(args.getString("observeBaseline"));start();}
    private <T>T main(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private void check(String label,boolean value)throws Exception{report.put(label,value);assertions++;if(!value)throw new AssertionError(label);}
    private NativeGlassDialog create()throws Exception{return main(()->{NativeGlassDialog dialog=new NativeGlassDialog.Builder(activity,false).setTitle("本地按钮边界").setNegativeButton("取消",null).setNeutralButton("保留编辑",null).setPositiveButton("合成确认",null).create();dialogs.add(dialog);return dialog;});}
    private void observe()throws Exception {
        stage="observeCancelThenLateCustomAction";NativeGlassDialog dialog=create();final int[] calls={0};final boolean[] closing={false},showing={false};final int[] afterPerform={0};main(()->{dialog.show();dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(v->calls[0]++);dialog.getButton(Dialog.BUTTON_NEGATIVE).performClick();closing[0]=dialog.isClosing();showing[0]=dialog.isShowing();dialog.getButton(Dialog.BUTTON_POSITIVE).performClick();afterPerform[0]=calls[0];dialog.getButton(Dialog.BUTTON_POSITIVE).callOnClick();return null;});
        report.put("negativeEnteredClosing",closing[0]).put("negativeStillVisibleDuringExit",showing[0]).put("latePerformClickInvokedCustomListener",afterPerform[0]==1).put("lateCallOnClickInvokedCustomListener",calls[0]==2).put("customListenerInvocations",calls[0]).put("expectedRegressionObserved",closing[0]&&showing[0]&&calls[0]==2).put("completed",true).put("observationOnly",true);
    }
    private void strictButtonChecks(int which,String label)throws Exception {
        stage=label;NativeGlassDialog dialog=create();final int[] original={0},replacement={0};Button button=dialog.getButton(which);
        main(()->{button.setOnClickListener(v->original[0]++);button.performClick();button.callOnClick();return null;});check(label+"BeforeShowRejected",original[0]==0);
        main(()->{dialog.show();button.setEnabled(false);button.performClick();button.callOnClick();return null;});check(label+"DisabledActionsRejected",original[0]==0);
        main(()->{button.setEnabled(true);button.performClick();button.callOnClick();return null;});check(label+"LiveCustomActionsRemainEditable",original[0]==2&&dialog.isShowing()&&!dialog.isClosing());
        main(()->{button.setOnClickListener(v->replacement[0]++);button.performClick();button.callOnClick();return null;});check(label+"ReplacementListenerStaysGuardedAndWorks",replacement[0]==2&&original[0]==2);
        main(()->{button.setEnabled(false);button.performClick();button.callOnClick();return null;});check(label+"ReplacementDisabledActionsRejected",replacement[0]==2);
        main(()->{button.setEnabled(true);button.setOnClickListener(null);button.performClick();button.callOnClick();return null;});check(label+"NullListenerRemovesAction",replacement[0]==2&&original[0]==2);
        main(()->{button.setOnClickListener(v->replacement[0]++);dialog.dismiss();button.performClick();button.callOnClick();return null;});check(label+"DismissedActionsRejected",replacement[0]==2);
    }
    private void awaitHidden(NativeGlassDialog dialog)throws Exception{for(int i=0;i<60;i++){if(main(()->!dialog.isShowing())){waitForIdleSync();return;}SystemClock.sleep(50);}throw new IllegalStateException("DIALOG_EXIT_TIMEOUT");}
    private void strictClosingChecks()throws Exception {
        stage="cancelExit";final int[] positive={0},neutral={0},negative={0};NativeGlassDialog dialog=main(()->{NativeGlassDialog value=new NativeGlassDialog.Builder(activity,false).setTitle("本地取消退场").setNegativeButton("取消",(d,n)->negative[0]++).setNeutralButton("中间",null).setPositiveButton("确认",null).show();dialogs.add(value);return value;});
        final boolean[] entered={false};main(()->{dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(v->positive[0]++);dialog.getButton(Dialog.BUTTON_NEUTRAL).setOnClickListener(v->neutral[0]++);dialog.getButton(Dialog.BUTTON_NEGATIVE).performClick();entered[0]=dialog.isClosing()&&dialog.isShowing();dialog.getButton(Dialog.BUTTON_POSITIVE).performClick();dialog.getButton(Dialog.BUTTON_POSITIVE).callOnClick();dialog.getButton(Dialog.BUTTON_NEUTRAL).performClick();dialog.getButton(Dialog.BUTTON_NEUTRAL).callOnClick();return null;});
        check("negativeAcceptedVisibleExitAnimation",entered[0]);check("cancelExitRejectsLatePositiveBothDispatchPaths",positive[0]==0);check("cancelExitRejectsLateNeutralBothDispatchPaths",neutral[0]==0);awaitHidden(dialog);check("acceptedNegativeFinishCallbackStillRunsOnce",negative[0]==1&&!dialog.isShowing());
        stage="positiveAutoAction";final int[] callback={0};NativeGlassDialog automatic=main(()->{NativeGlassDialog value=new NativeGlassDialog.Builder(activity,false).setTitle("本地默认确认").setPositiveButton("确认",(d,n)->callback[0]++).show();dialogs.add(value);return value;});main(()->{automatic.getButton(Dialog.BUTTON_POSITIVE).performClick();automatic.getButton(Dialog.BUTTON_POSITIVE).callOnClick();automatic.getButton(Dialog.BUTTON_POSITIVE).performClick();return null;});awaitHidden(automatic);check("acceptedDefaultPositiveFinishCallbackStillRunsOnce",callback[0]==1&&!automatic.isShowing());
        stage="neutralAutoAction";final int[] neutralCallback={0};NativeGlassDialog middle=main(()->{NativeGlassDialog value=new NativeGlassDialog.Builder(activity,false).setTitle("本地默认中间操作").setNeutralButton("确认",(d,n)->neutralCallback[0]++).show();dialogs.add(value);return value;});main(()->{middle.getButton(Dialog.BUTTON_NEUTRAL).callOnClick();middle.getButton(Dialog.BUTTON_NEUTRAL).performClick();return null;});awaitHidden(middle);check("acceptedDefaultNeutralFinishCallbackStillRunsOnce",neutralCallback[0]==1&&!middle.isShowing());
    }
    private void strictFinishingOwner()throws Exception {
        stage="finishingOwner";NativeGlassDialog dialog=create();final int[] callbacks={0};main(()->{dialog.show();dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(v->callbacks[0]++);activity.finish();dialog.getButton(Dialog.BUTTON_POSITIVE).performClick();dialog.getButton(Dialog.BUTTON_POSITIVE).callOnClick();return null;});check("finishingOwnerRejectsActionsImmediately",callbacks[0]==0);for(int i=0;i<60&&!activity.isDestroyed();i++)SystemClock.sleep(50);waitForIdleSync();main(()->{dialog.getButton(Dialog.BUTTON_POSITIVE).performClick();dialog.getButton(Dialog.BUTTON_POSITIVE).callOnClick();return null;});check("destroyedOwnerRejectsRetainedButtonActions",callbacks[0]==0&&activity.isDestroyed());
    }
    @Override public void onStart(){try {
        if(!authorized||!"cn.ngapure.glassaction".equals(getTargetContext().getPackageName())||getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("OWN_UID_NO_INTERNET_SYNTHETIC_COUNTERS_REQUIRED");new NgaStore(getTargetContext()).setMotionEnabled(true);activity=startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(),GlassActionActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
        if(observeBaseline)observe();else{strictButtonChecks(Dialog.BUTTON_POSITIVE,"positive");strictButtonChecks(Dialog.BUTTON_NEUTRAL,"neutral");strictButtonChecks(Dialog.BUTTON_NEGATIVE,"negative");strictClosingChecks();strictFinishingOwner();report.put("completed",true).put("passed",true).put("assertions",assertions);}
    }catch(Exception|AssertionError failure){try{report.put("completed",false).put("passed",false).put("failureStage",stage).put("failureType",failure.getClass().getSimpleName()).put("assertions",assertions);}catch(Exception ignored){}}
    finally{try{main(()->{for(NativeGlassDialog dialog:dialogs)dialog.dismiss();if(activity!=null&&!activity.isDestroyed())activity.finish();return null;});report.put("ownUidOnly",true).put("internetPermission",false).put("counterActionsOnly",true).put("realClientStarted",false).put("accountAccessed",false).put("communityWriteActionInvoked",false);}catch(Exception ignored){}Bundle result=new Bundle();result.putString("safe_glass_action_report",report.toString());finish(Activity.RESULT_OK,result);}}
}
