package cn.ngapure.recreationprobe;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import cn.ngapure.app.MainActivity;
import cn.ngapure.app.NativeGlassDialog;
import cn.ngapure.app.NativeUserActionDialogs;
import cn.ngapure.app.NativeAvatarCropView;
import cn.ngapure.app.NativeCommunity;
import cn.ngapure.app.NativeUserActions;
import cn.ngapure.app.NgaStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Local generated image only. Opens, recreates and cancels a crop; never clicks upload. */
public final class AvatarCropLifecycleProbe extends Instrumentation {
    private volatile Activity activity;
    private boolean authorized;
    private boolean observeCancelBaseline;
    private String stage="guard";
    private int assertions;
    private final JSONObject report=new JSONObject();
    private final Application.ActivityLifecycleCallbacks callbacks=new Application.ActivityLifecycleCallbacks(){
        public void onActivityResumed(Activity value){if(value instanceof MainActivity)activity=value;}
        public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
    };
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("localCropOnly"));observeCancelBaseline=args!=null&&"true".equals(args.getString("observeCancelBaseline"));start();}
    private static Object field(Object target,String name)throws Exception{Field member=target.getClass().getDeclaredField(name);member.setAccessible(true);return member.get(target);}
    private <T>T main(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private void check(String name,boolean value)throws Exception{report.put(name,value);assertions++;if(!value)throw new AssertionError(name);}
    private static boolean containsText(View view,String target){if(view instanceof TextView&&target.contentEquals(((TextView)view).getText()))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(containsText(group.getChildAt(i),target))return true;}return false;}
    @SuppressWarnings("unchecked") private NativeGlassDialog crop()throws Exception{return main(()->{Object sheets=field(activity,"userSheets");if(sheets==null)return null;for(NativeGlassDialog dialog:(List<NativeGlassDialog>)field(sheets,"dialogs"))if(dialog.isShowing()&&containsText(dialog.getWindow().getDecorView(),"裁剪头像"))return dialog;return null;});}
    private NativeGlassDialog awaitCrop(int rounds)throws Exception{for(int i=0;i<rounds;i++){NativeGlassDialog value=crop();if(value!=null)return value;SystemClock.sleep(100);}return null;}
    private NativeUserActionDialogs sheets()throws Exception{return main(()->(NativeUserActionDialogs)field(activity,"userSheets"));}
    private String scope()throws Exception{return main(()->{Method method=MainActivity.class.getDeclaredMethod("contentScope");method.setAccessible(true);return (String)method.invoke(activity);});}
    private NativeAvatarCropView cropView()throws Exception{return main(()->(NativeAvatarCropView)field(field(activity,"userSheets"),"avatarCrop"));}
    private int readCount(){Bundle value=getTargetContext().getContentResolver().call(Uri.parse("content://cn.ngapure.recreationprobe.fixture"),"readCount",null,null);return value==null?-1:value.getInt("reads",-1);}
    private static String digest(char value){StringBuilder out=new StringBuilder();for(int i=0;i<64;i++)out.append(value);return out.toString();}
    private static boolean transformEquals(float[] a,float[] b){if(a==null||b==null||a.length!=3||b.length!=3)return false;for(int i=0;i<3;i++)if(Math.abs(a[i]-b[i])>.00001f)return false;return true;}
    private static boolean samePixels(Bitmap a,Bitmap b){return a!=null&&b!=null&&a.sameAs(b);}
    private void cancelAndScopeChecks(String[] saved,String currentScope)throws Exception{
        stage="cancel";NativeGlassDialog active=crop();NativeAvatarCropView view=cropView();Bitmap source=main(()->(Bitmap)field(view,"image"));main(()->{active.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();return null;});
        NativeUserActionDialogs currentSheets=sheets();for(int i=0;i<40;i++){if(main(()->!active.isShowing()&&field(currentSheets,"avatarDialog")!=active&&field(currentSheets,"avatarCrop")==null))break;SystemClock.sleep(50);}
        check("cropCanceledWithoutUpload",main(()->!active.isShowing())&&crop()==null);check("cancelRecyclesPreviewBitmap",source.isRecycled());check("cancelClearsRestorablePreview",main(()->currentSheets.saveAvatarPreview(currentScope)==null));
        recreate();SystemClock.sleep(350);check("canceledPreviewNotReopenedAfterRecreate",crop()==null);
        stage="scopeGuards";int beforeReads=readCount();String[] other=saved.clone();other[1]=other[1].equals(digest('a'))?digest('b'):digest('a');
        NativeUserActionDialogs dialogs=main(()->{Method method=MainActivity.class.getDeclaredMethod("userSheets");method.setAccessible(true);return (NativeUserActionDialogs)method.invoke(activity);});
        main(()->{dialogs.restoreAvatarPreview(other,currentScope,true);dialogs.restoreAvatarPreview(saved,currentScope,false);dialogs.restoreAvatarPreview(new String[]{"avatarPreview",currentScope,"file:///private/image.png","1","0","0"},currentScope,true);return null;});SystemClock.sleep(200);
        check("changedSessionLogoutAndInvalidUriRejectedBeforeProviderRead",readCount()==beforeReads&&crop()==null);
    }
    private void cancelAndImmediatelyRecreate()throws Exception{
        stage="cancelThenImmediateRecreate";NativeUserActionDialogs dialogs=sheets();main(()->{dialogs.resumeAvatarSelection(Uri.parse("content://cn.ngapure.recreationprobe.fixture/avatar.png"));return null;});NativeGlassDialog active=awaitCrop(100);check("secondLocalCropOpenedForImmediateCancel",active!=null);NativeAvatarCropView view=cropView();Bitmap source=main(()->(Bitmap)field(view,"image"));String currentScope=scope();Activity previous=activity;final boolean[] dropped={false};
        main(()->{active.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();dropped[0]=dialogs.saveAvatarPreview(currentScope)==null;activity.recreate();return null;});
        for(int i=0;i<120;i++){if(activity!=null&&activity!=previous&&!activity.isDestroyed())break;SystemClock.sleep(50);}waitForIdleSync();SystemClock.sleep(450);
        check("cancelImmediatelyDropsPreviewBeforeExitAnimationEnds",dropped[0]);check("recreateDuringCancelAnimationCannotResurrectCrop",activity!=previous&&crop()==null);check("cancelRecreateRecyclesOldPreviewBitmap",source.isRecycled());
    }
    private void resizedGeometryChecks()throws Exception{
        stage="geometry";main(()->{
            Bitmap fixture=Bitmap.createBitmap(240,160,Bitmap.Config.ARGB_8888);for(int y=0;y<160;y++)for(int x=0;x<240;x++)fixture.setPixel(x,y,0xff000000|((x*255/239)<<16)|((y*255/159)<<8)|(x+y)%256);
            NativeAvatarCropView view=new NativeAvatarCropView(activity,fixture);view.layout(0,0,400,400);view.restoreTransform(new float[]{2.5f,.125f,-.125f});float[] before=view.saveTransform();Bitmap first=view.crop();view.layout(0,0,900,640);float[] after=view.saveTransform();Bitmap second=view.crop();
            try{check("resizingKeepsNormalizedCropTransform",transformEquals(before,after));check("resizingKeepsExactCropPixels",samePixels(first,second));view.restoreTransform(new float[]{Float.NaN,0,0});check("nonFiniteTransformCannotCorruptPreview",transformEquals(after,view.saveTransform()));}
            finally{first.recycle();second.recycle();view.releaseImage();}check("releasedPreviewBitmapRecycled",fixture.isRecycled());Bitmap canvasTarget=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);try{view.draw(new android.graphics.Canvas(canvasTarget));check("lateDrawAfterReleaseIsHarmless",true);}finally{canvasTarget.recycle();}return null;
        });
    }
    private static final class Queue implements java.util.concurrent.Executor{final ArrayList<Runnable> tasks=new ArrayList<>();public void execute(Runnable task){tasks.add(task);}}
    private static final class Session implements NativeCommunity.Session{public String host(){return "bbs.nga.cn";}public String cookie(){return "ngaPassportUid=70001; ngaPassportCid=synthetic-only";}}
    private NativeUserActionDialogs fixtureDialogs(Queue queue)throws Exception{return main(()->new NativeUserActionDialogs(activity,false,queue,new NativeUserActionDialogs.Host(){public void changed(){}public void openUser(String id,String name){}public void chooseAvatar(NativeUserActionDialogs.AvatarSelection selection){throw new AssertionError("NO_SYSTEM_PICKER_IN_FIXTURE");}}));}
    private void openFixture(NativeUserActionDialogs dialogs,NativeUserActions api)throws Exception{main(()->{Method method=NativeUserActionDialogs.class.getDeclaredMethod("editAvatar",NativeUserActions.class,Uri.class);method.setAccessible(true);method.invoke(dialogs,api,Uri.parse("content://cn.ngapure.recreationprobe.fixture/avatar.png"));return null;});}
    @SuppressWarnings("unchecked") private static NativeGlassDialog fixtureCrop(NativeUserActionDialogs dialogs)throws Exception{for(NativeGlassDialog value:(List<NativeGlassDialog>)field(dialogs,"dialogs"))if(value.isShowing()&&containsText(value.getWindow().getDecorView(),"裁剪头像"))return value;return null;}
    private void localTaskChecks()throws Exception{
        stage="localTaskCancellation";int beforeReads=readCount();final int[] calls={0};NativeUserActions api=new NativeUserActions(new Session(),(request,cookie)->{calls[0]++;throw new java.io.IOException("synthetic transport forbids network");});Queue queue=new Queue();NativeUserActionDialogs dialogs=fixtureDialogs(queue);openFixture(dialogs,api);
        check("preparingPreviewCanBeSavedWithoutBitmap",main(()->dialogs.saveAvatarPreview(digest('a'))!=null));check("singleLocalDecodeTaskQueued",queue.tasks.size()==1);main(()->{dialogs.close();return null;});queue.tasks.get(0).run();waitForIdleSync();
        check("cancelBeforeDecodeDoesNotReadProvider",readCount()==beforeReads);check("closedPreviewCannotRestore",main(()->dialogs.saveAvatarPreview(digest('a'))==null));
        stage="lateDecode";Queue late=new Queue();NativeUserActionDialogs retired=fixtureDialogs(late);openFixture(retired,api);final boolean[] joined={false};main(()->{Thread worker=new Thread(late.tasks.get(0),"avatar-local-fixture");worker.start();worker.join(2500);joined[0]=!worker.isAlive();retired.close();return null;});waitForIdleSync();
        check("localDecodeCompletedWhileUiDeliveryWasQueued",joined[0]&&readCount()==beforeReads+1);check("retiredLocalCompletionDoesNotOpenCrop",main(()->fixtureCrop(retired)==null&&field(retired,"avatarCrop")==null));
        stage="cancelDuringExitAnimation";Queue cancelQueue=new Queue();NativeUserActionDialogs canceling=fixtureDialogs(cancelQueue);openFixture(canceling,api);final boolean[] canceledState={false},cropReopened={false};main(()->{NativeGlassDialog progress=(NativeGlassDialog)field(canceling,"avatarDialog");progress.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();canceledState[0]=canceling.saveAvatarPreview(digest('a'))==null;cancelQueue.tasks.get(0).run();cropReopened[0]=fixtureCrop(canceling)!=null;return null;});SystemClock.sleep(300);boolean finallyClosed=main(()->fixtureCrop(canceling)==null);main(()->{canceling.close();return null;});check("cancelingAnimationImmediatelyDropsRestorablePreview",canceledState[0]);check("decodeCompletingInsideCancelAnimationCannotOpenCrop",!cropReopened[0]&&finallyClosed);
        stage="syntheticSubmitGuard";Queue submit=new Queue();NativeUserActionDialogs synthetic=fixtureDialogs(submit);openFixture(synthetic,api);submit.tasks.get(0).run();waitForIdleSync();NativeGlassDialog preview=main(()->fixtureCrop(synthetic));check("syntheticPreviewUsesLocalFixture",preview!=null);
        main(()->{preview.getButton(DialogInterface.BUTTON_POSITIVE).performClick();return null;});check("explicitSyntheticSubmitQueuesOnce",submit.tasks.size()==2);check("submittingPreviewIsNeverRestorable",main(()->synthetic.saveAvatarPreview(digest('a'))==null));check("submitDisablesDuplicateConfirmation",main(()->!preview.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled()));
        submit.tasks.get(1).run();waitForIdleSync();main(()->{synthetic.close();return null;});check("syntheticTransportStopsBeforeAnyImageServiceRequest",calls[0]==1);report.put("syntheticUploadConfirmationClicked",true).put("syntheticTransportInvocations",calls[0]);
    }
    private void observeCancelRace()throws Exception{
        stage="observeCancelRaceBaseline";final int[] calls={0};NativeUserActions api=new NativeUserActions(new Session(),(request,cookie)->{calls[0]++;throw new java.io.IOException("synthetic transport forbids network");});Queue queue=new Queue();NativeUserActionDialogs canceling=fixtureDialogs(queue);openFixture(canceling,api);final boolean[] dropped={false},reopened={false};
        main(()->{NativeGlassDialog progress=(NativeGlassDialog)field(canceling,"avatarDialog");progress.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();dropped[0]=canceling.saveAvatarPreview(digest('a'))==null;queue.tasks.get(0).run();reopened[0]=fixtureCrop(canceling)!=null;return null;});SystemClock.sleep(300);boolean closed=main(()->fixtureCrop(canceling)==null);main(()->{canceling.close();return null;});
        report.put("observedBaselineOnly",true).put("cancelingAnimationImmediatelyDropsRestorablePreview",dropped[0]).put("decodeInsideCancelAnimationReopenedCrop",reopened[0]).put("cropClosedAfterCancelAnimation",closed).put("syntheticTransportInvocations",calls[0]).put("expectedRegressionObserved",!dropped[0]&&reopened[0]).put("completed",true).put("passed",dropped[0]&&!reopened[0]&&closed&&calls[0]==0);
    }
    private void recreate()throws Exception{Activity previous=activity;main(()->{activity.recreate();return null;});for(int i=0;i<100;i++){if(activity!=null&&activity!=previous&&!activity.isDestroyed()){waitForIdleSync();return;}SystemClock.sleep(100);}throw new IllegalStateException("RECREATE_TIMEOUT");}
    @Override public void onStart(){Application app=null;NgaStore store=null;String account="",host="",cookie="",day="";try{
        if(!authorized)throw new IllegalStateException("EXPLICIT_LOCAL_CROP_ONLY_REQUIRED");store=new NgaStore(getTargetContext());account=store.accountId();host=store.host();cookie=store.cookie();day=store.lastCheckInDay();if(!store.loggedIn()||!NgaStore.today().equals(day))throw new IllegalStateException("SIGNED_IN_TODAY_GUARD");app=(Application)getTargetContext().getApplicationContext();app.registerActivityLifecycleCallbacks(callbacks);
        activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.ngapure.app","cn.ngapure.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
        if(observeCancelBaseline){observeCancelRace();return;}
        main(()->{Method sheets=MainActivity.class.getDeclaredMethod("userSheets");sheets.setAccessible(true);((NativeUserActionDialogs)sheets.invoke(activity)).resumeAvatarSelection(Uri.parse("content://cn.ngapure.recreationprobe.fixture/avatar.png"));return null;});
        stage="initialCrop";NativeGlassDialog before=awaitCrop(100);check("localNativeCropOpened",before!=null);NativeAvatarCropView initial=cropView();main(()->{initial.restoreTransform(new float[]{2.5f,.125f,-.125f});return null;});waitForIdleSync();float[] oldTransform=main(initial::saveTransform);Bitmap oldOutput=main(initial::crop),oldSource=main(()->(Bitmap)field(initial,"image"));String currentScope=scope();NativeUserActionDialogs currentSheets=sheets();String[] saved=main(()->currentSheets.saveAvatarPreview(currentScope));check("previewSnapshotContainsSixBoundedStringsOnly",saved!=null&&saved.length==6);
        stage="recreation";recreate();NativeGlassDialog after=awaitCrop(100);check("nativeCropVisibleAfterRecreate",after!=null);check("cropWasRestoredInNewActivity",before!=after);NativeAvatarCropView restored=cropView();check("cropTransformRetainedAfterRecreate",transformEquals(oldTransform,main(restored::saveTransform)));Bitmap newOutput=main(restored::crop);try{check("cropPixelsRetainedAfterRecreate",samePixels(oldOutput,newOutput));}finally{oldOutput.recycle();newOutput.recycle();}check("oldActivityPreviewBitmapRecycled",oldSource.isRecycled());
        cancelAndScopeChecks(saved,currentScope);cancelAndImmediatelyRecreate();resizedGeometryChecks();localTaskChecks();report.put("completed",true).put("passed",true).put("assertions",assertions);
    }catch(Exception|AssertionError failure){try{report.put("completed",false).put("passed",false).put("failureStage",stage).put("assertions",assertions).put("failureType",failure.getClass().getSimpleName());}catch(Exception ignored){} }
    finally{try{if(app!=null)app.unregisterActivityLifecycleCallbacks(callbacks);report.put("accountAndSessionUnchanged",store!=null&&account.equals(store.accountId())&&host.equals(store.host())&&cookie.equals(store.cookie())&&day.equals(store.lastCheckInDay())).put("finalAvatarUploadButtonClicked",false).put("credentialsExported",false).put("userImageExported",false).put("networkPermissionDeclaredByHelper",false);}catch(Exception ignored){}Bundle output=new Bundle();output.putString("safe_crop_lifecycle_report",report.toString());finish(Activity.RESULT_OK,output);}}
}
