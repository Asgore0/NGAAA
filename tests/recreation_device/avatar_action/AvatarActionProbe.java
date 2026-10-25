package cn.ngapure.avataraction;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import cn.ngapure.app.NativeAvatarCropView;
import cn.ngapure.app.NativeCommunity;
import cn.ngapure.app.NativeGlassDialog;
import cn.ngapure.app.NativeUserActionDialogs;
import cn.ngapure.app.NativeUserActions;
import cn.ngapure.app.NgaStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Unmodified production dialog in a separate UID, with local pixels and a throwing transport. */
public final class AvatarActionProbe extends Instrumentation {
    private Activity activity;
    private boolean authorized,observeBaseline;
    private String stage="guard";
    private int assertions,calls;
    private final JSONObject report=new JSONObject();
    private final ArrayList<NativeUserActionDialogs> owners=new ArrayList<>();
    private static final class Queue implements java.util.concurrent.Executor {
        final ArrayList<Runnable> tasks=new ArrayList<>();
        public void execute(Runnable task){tasks.add(task);}
    }
    private static final class Session implements NativeCommunity.Session {
        String value="ngaPassportUid=70001; ngaPassportCid=synthetic-only";
        public String host(){return "bbs.nga.cn";}public String cookie(){return value;}
    }
    private static final class Fixture {
        final Queue queue;final Session session;final NativeUserActionDialogs owner;
        Fixture(Queue queue,Session session,NativeUserActionDialogs owner){this.queue=queue;this.session=session;this.owner=owner;}
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticAvatarOnly"));observeBaseline=args!=null&&"true".equals(args.getString("observeBaseline"));start();}
    private static Object field(Object target,String name)throws Exception{Field member=target.getClass().getDeclaredField(name);member.setAccessible(true);return member.get(target);}
    private <T>T main(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private void check(String name,boolean value)throws Exception{report.put(name,value);assertions++;if(!value)throw new AssertionError(name);}
    private Fixture prepare()throws Exception {
        Queue queue=new Queue();Session session=new Session();NativeUserActions api=new NativeUserActions(session,(request,cookie)->{calls++;throw new java.io.IOException("synthetic transport blocks before image service");});
        NativeUserActionDialogs owner=main(()->new NativeUserActionDialogs(activity,false,queue,new NativeUserActionDialogs.Host(){public void changed(){}public void openUser(String uid,String name){}public void chooseAvatar(NativeUserActionDialogs.AvatarSelection selection){throw new AssertionError("NO_SYSTEM_PICKER");}}));owners.add(owner);
        main(()->{Method edit=NativeUserActionDialogs.class.getDeclaredMethod("editAvatar",NativeUserActions.class,Uri.class);edit.setAccessible(true);edit.invoke(owner,api,Uri.parse("content://cn.ngapure.avataraction.fixture/avatar.png"));return null;});
        if(queue.tasks.size()!=1)throw new IllegalStateException("LOCAL_DECODE_TASK_REQUIRED");queue.tasks.get(0).run();waitForIdleSync();
        if(main(()->field(owner,"avatarCrop"))==null)throw new IllegalStateException("LOCAL_CROP_REQUIRED");return new Fixture(queue,session,owner);
    }
    private NativeGlassDialog dialog(Fixture value)throws Exception{return main(()->(NativeGlassDialog)field(value.owner,"avatarDialog"));}
    private boolean cancelThenLateConfirm(Fixture value)throws Exception {
        NativeGlassDialog dialog=dialog(value);
        return main(()->{dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();boolean closing=dialog.isClosing()&&dialog.isShowing();dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();report.put("negativeEnteredVisibleExitAnimation",closing);return value.queue.tasks.size()>1;});
    }
    private void observe()throws Exception {
        stage="observeCancelLateConfirmation";Fixture value=prepare();boolean queued=cancelThenLateConfirm(value);main(()->{value.owner.close();return null;});
        report.put("cancelThenLateConfirmationQueuedUpload",queued).put("expectedRegressionObserved",queued).put("queuedUploadTasksExecuted",0).put("transportInvocations",calls).put("observationOnly",true).put("completed",true);
    }
    private void strictChecks()throws Exception {
        stage="cancelLateConfirmation";Fixture cancel=prepare();NativeAvatarCropView canceledCrop=main(()->(NativeAvatarCropView)field(cancel.owner,"avatarCrop"));Bitmap canceledSource=main(()->(Bitmap)field(canceledCrop,"image"));check("cancelThenLateConfirmationCannotQueueUpload",!cancelThenLateConfirm(cancel));check("canceledActionDoesNotContactTransport",calls==0);SystemClock.sleep(300);check("cancelFinishesWithoutReopeningPrompt",main(()->field(cancel.owner,"avatarDialog")==null));check("cancelRecyclesPreviewSource",canceledSource.isRecycled());
        stage="closedLateConfirmation";Fixture closed=prepare();NativeGlassDialog old=dialog(closed);main(()->{old.dismiss();old.getButton(DialogInterface.BUTTON_POSITIVE).performClick();return null;});check("closedConfirmationCannotQueueUpload",closed.queue.tasks.size()==1);check("closedConfirmationCannotOpenErrorDialog",main(()->((java.util.List<?>)field(closed.owner,"dialogs")).isEmpty()));
        stage="ownerClosedLateConfirmation";Fixture retired=prepare();NativeGlassDialog retiredDialog=dialog(retired);main(()->{retired.owner.close();retiredDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();return null;});check("retiredOwnerRejectsLateConfirmation",retired.queue.tasks.size()==1);
        stage="sessionChangedLateConfirmation";Fixture stale=prepare();NativeGlassDialog staleDialog=dialog(stale);main(()->{stale.session.value="ngaPassportUid=70002; ngaPassportCid=synthetic-changed";staleDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();return null;});check("changedSessionCannotQueueUpload",stale.queue.tasks.size()==1);check("changedSessionDoesNotContactTransport",calls==0);main(()->{stale.owner.close();return null;});
        stage="explicitSyntheticConfirmation";Fixture valid=prepare();NativeGlassDialog positive=dialog(valid);main(()->{positive.getButton(DialogInterface.BUTTON_POSITIVE).performClick();positive.getButton(DialogInterface.BUTTON_POSITIVE).performClick();return null;});check("explicitConfirmationQueuesExactlyOnce",valid.queue.tasks.size()==2);check("confirmedActionDisablesDuplicateButton",main(()->!positive.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled()));check("confirmedActionIsNotRestorable",main(()->valid.owner.saveAvatarPreview(repeatDigest())==null));
        valid.queue.tasks.get(1).run();waitForIdleSync();check("syntheticTransportStopsBeforeAnyImageService",calls==1);report.put("queuedUploadTasksExecuted",1).put("transportInvocations",calls).put("completed",true).put("passed",true).put("assertions",assertions);
    }
    private static String repeatDigest(){StringBuilder out=new StringBuilder();for(int i=0;i<64;i++)out.append('a');return out.toString();}
    @Override public void onStart(){try {
        if(!authorized||!"cn.ngapure.avataraction".equals(getTargetContext().getPackageName())||getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("OWN_UID_NO_INTERNET_SYNTHETIC_ONLY_REQUIRED");
        new NgaStore(getTargetContext()).setMotionEnabled(true);activity=startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(),AvatarActionActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();if(observeBaseline)observe();else strictChecks();
    }catch(Exception|AssertionError failure){try{report.put("completed",false).put("passed",false).put("assertions",assertions).put("failureStage",stage).put("failureType",failure.getClass().getSimpleName());}catch(Exception ignored){}}
    finally{try{main(()->{for(NativeUserActionDialogs owner:owners)owner.close();if(activity!=null)activity.finish();return null;});report.put("ownUidOnly",true).put("internetPermission",false).put("generatedPixelsOnly",true).put("realClientStarted",false).put("realAvatarConfirmationClicked",false).put("syntheticAvatarConfirmationClicked",true).put("imageServiceRequestExecuted",false).put("credentialsExported",false);}catch(Exception ignored){}Bundle result=new Bundle();result.putString("safe_avatar_action_report",report.toString());finish(Activity.RESULT_OK,result);}}
}
