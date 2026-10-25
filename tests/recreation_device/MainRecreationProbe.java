package cn.ngapure.recreationprobe;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.ListView;
import android.widget.TextView;
import cn.ngapure.app.NativeGlassDialog;
import cn.ngapure.app.NativeCommunity;
import cn.ngapure.app.NativeUserActions;
import cn.ngapure.app.NativeUserActionDialogs;
import cn.ngapure.app.MainActivity;
import cn.ngapure.app.NgaStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.List;
import org.json.JSONObject;

/** Main lifecycle probe. Only public synthetic filters and local UI state; never opens a write action. */
public final class MainRecreationProbe extends Instrumentation {
    private volatile Activity activity;
    private boolean authorized;
    private boolean testAvatar;
    private final JSONObject report=new JSONObject();
    private String stage="guard";
    private final Application.ActivityLifecycleCallbacks callbacks=new Application.ActivityLifecycleCallbacks(){
        public void onActivityResumed(Activity value){if(value instanceof MainActivity)activity=value;}
        public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
    };
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("recreationOnly"));testAvatar=args!=null&&"true".equals(args.getString("testAvatar"));start();}
    private static Object field(Object target,String name)throws Exception{Field value=target.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(target);}
    private static void set(Object target,String name,Object value)throws Exception{Field member=target.getClass().getDeclaredField(name);member.setAccessible(true);member.set(target,value);}
    private <T>T main(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private void renderHome(int tab,String rating,int page,String category,String query)throws Exception{
        main(()->{set(activity,"mainTab",tab);set(activity,"ratingCategory",rating);set(activity,"ratingPage",page);set(activity,"communityCategory",category);set(activity,"communityQuery",query);set(activity,"communityPosition",0);set(activity,"communityOffset",0);Object current=field(activity,"current");Method go=MainActivity.class.getDeclaredMethod("go",current.getClass(),boolean.class);go.setAccessible(true);go.invoke(activity,current,false);return null;});waitForIdleSync();SystemClock.sleep(300);
    }
    private void recreate()throws Exception{Activity previous=activity;main(()->{activity.recreate();return null;});for(int i=0;i<120;i++){Activity current=activity;if(current!=null&&current!=previous&&!current.isDestroyed()){waitForIdleSync();SystemClock.sleep(250);return;}SystemClock.sleep(100);}throw new IllegalStateException("RECREATE_TIMEOUT");}
    private int[] listAnchor()throws Exception{return main(()->{ListView list=(ListView)field(activity,"communityList");View first=list.getChildAt(0);if(first==null)throw new IllegalStateException("NO_FIRST_ROW");return new int[]{list.getFirstVisiblePosition(),first.getTop()-list.getListPaddingTop()};});}
    private void armAvatar(boolean sameScope)throws Exception{main(()->{Method scopeMethod=MainActivity.class.getDeclaredMethod("contentScope");scopeMethod.setAccessible(true);String scope=(String)scopeMethod.invoke(activity);if(!sameScope)scope=(scope.charAt(0)=='0'?"1":"0")+scope.substring(1);Class<?> state=Class.forName("cn.ngapure.app.AvatarPickerState",true,activity.getClassLoader());Method begin=state.getDeclaredMethod("begin",String.class);begin.setAccessible(true);set(activity,"avatarPicker",begin.invoke(null,scope));return null;});}
    private void deliverAvatar(int result,Uri uri)throws Exception{main(()->{Method receive=MainActivity.class.getDeclaredMethod("onActivityResult",int.class,int.class,Intent.class);receive.setAccessible(true);receive.invoke(activity,703,result,uri==null?null:new Intent().setData(uri));return null;});waitForIdleSync();}
    private static boolean containsText(View view,String target){if(view instanceof TextView&&target.contentEquals(((TextView)view).getText()))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(containsText(group.getChildAt(i),target))return true;}return false;}
    @SuppressWarnings("unchecked") private NativeGlassDialog crop()throws Exception{return main(()->{Object sheets=field(activity,"userSheets");if(sheets==null)return null;for(NativeGlassDialog dialog:(List<NativeGlassDialog>)field(sheets,"dialogs"))if(dialog.isShowing()&&containsText(dialog.getWindow().getDecorView(),"裁剪头像"))return dialog;return null;});}
    private int readCount(){Bundle value=getTargetContext().getContentResolver().call(Uri.parse("content://cn.ngapure.recreationprobe.fixture"),"readCount",null,null);return value==null?-1:value.getInt("reads",-1);}
    private boolean avatarChecks()throws Exception{
        stage="avatarRestore";renderHome(0,"2",1,"收藏版块","");armAvatar(true);recreate();boolean restored=main(()->field(activity,"avatarPicker")!=null);report.put("avatarPurposeRestored",restored);
        Uri fixture=Uri.parse("content://cn.ngapure.recreationprobe.fixture/avatar.png");int beforeReads=readCount();deliverAvatar(Activity.RESULT_OK,fixture);NativeGlassDialog crop=null;for(int i=0;i<100;i++){crop=crop();if(crop!=null)break;SystemClock.sleep(100);}boolean appeared=crop!=null;report.put("avatarNativeCropOpenedAfterRecreate",appeared);if(!appeared)return false;
        boolean consumed=main(()->field(activity,"avatarPicker")==null);report.put("avatarResultConsumed",consumed);int afterReads=readCount();deliverAvatar(Activity.RESULT_OK,fixture);SystemClock.sleep(300);boolean once=readCount()==afterReads&&crop()!=null;report.put("avatarDuplicateResultDidNotDecodeAgain",once);
        NativeGlassDialog finalCrop=crop;main(()->{finalCrop.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();return null;});SystemClock.sleep(200);boolean canceled=crop()==null;report.put("avatarCropCanceledWithoutUpload",canceled);
        stage="avatarScopeReject";armAvatar(false);recreate();int rejectedReads=readCount();deliverAvatar(Activity.RESULT_OK,fixture);SystemClock.sleep(350);boolean rejected=readCount()==rejectedReads&&crop()==null&&main(()->field(activity,"avatarPicker")==null);report.put("avatarDifferentScopeRejectedBeforeDecode",rejected);
        stage="avatarCancel";armAvatar(true);deliverAvatar(Activity.RESULT_CANCELED,null);boolean cancellation=main(()->field(activity,"avatarPicker")==null)&&readCount()==rejectedReads;report.put("avatarPickerCancelClearsPurpose",cancellation);
        stage="avatarInvalidUri";armAvatar(true);deliverAvatar(Activity.RESULT_OK,Uri.parse("content:invalid"));boolean invalid=main(()->field(activity,"avatarPicker")==null)&&readCount()==rejectedReads&&crop()==null;report.put("avatarInvalidContentUriRejected",invalid);
        boolean launchGuard=avatarLaunchGuard();report.put("avatarFixtureDecodedLocally",afterReads>beforeReads).put("finalAvatarUploadButtonClicked",false);return restored&&appeared&&consumed&&once&&canceled&&rejected&&cancellation&&invalid&&afterReads>beforeReads&&launchGuard;
    }
    private boolean avatarLaunchGuard()throws Exception{
        stage="avatarLaunchGuard";boolean passed=true;
        for(int variant=0;variant<3;variant++){
            final class Session implements NativeCommunity.Session{String cookie="ngaPassportUid=70001; ngaPassportCid=fixture-only";public String host(){return "bbs.nga.cn";}public String cookie(){return cookie;}}
            Session session=new Session();final int[] network={0},pickers={0};NativeUserActions api=new NativeUserActions(session,(request,cookie)->{network[0]++;throw new java.io.IOException("fixture forbids network");});
            if(variant==1)session.cookie="ngaPassportUid=70002; ngaPassportCid=other-fixture";if(variant==2)session.cookie="ngaPassportUid=70001; ngaPassportCid=refreshed-fixture";
            NativeUserActionDialogs dialogs=main(()->new NativeUserActionDialogs(activity,false,Runnable::run,new NativeUserActionDialogs.Host(){public void changed(){}public void openUser(String uid,String name){}public void chooseAvatar(NativeUserActionDialogs.AvatarSelection selection){pickers[0]++;}}));
            main(()->{Method choose=NativeUserActionDialogs.class.getDeclaredMethod("chooseAvatar",NativeUserActions.class);choose.setAccessible(true);choose.invoke(dialogs,api);return null;});
            boolean accepted=pickers[0]==(variant==0?1:0)&&network[0]==0;passed&=accepted;report.put(new String[]{"avatarCurrentDialogLaunchesOnePicker","avatarOldAccountDialogCannotLaunchPicker","avatarOldCookieDialogCannotLaunchPicker"}[variant],accepted);main(()->{dialogs.close();return null;});
        }
        report.put("avatarLaunchGuardsUsedSyntheticSessionsOnly",true);return passed;
    }
    private boolean restoreCancellationChecks()throws Exception{
        stage="boardRestoreCancellation";int[] before=listAnchor();ListView list=main(()->(ListView)field(activity,"communityList"));
        main(()->{Method restore=MainActivity.class.getDeclaredMethod("restoreCatalogPosition",ListView.class,int.class,int.class,int.class);restore.setAccessible(true);restore.invoke(activity,list,40,-19,(Integer)field(activity,"generation"));long time=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,20,20,0),cancel=MotionEvent.obtain(time,time+1,MotionEvent.ACTION_CANCEL,20,20,0);try{list.dispatchTouchEvent(down);list.dispatchTouchEvent(cancel);}finally{down.recycle();cancel.recycle();}return null;});waitForIdleSync();SystemClock.sleep(200);int[] after=listAnchor();boolean listenerReleased=main(()->list.getTag()==null);boolean touch=before[0]==after[0]&&before[1]==after[1]&&listenerReleased;report.put("boardPendingRestoreCanceledByTouch",touch).put("boardTouchPositionBefore",before[0]).put("boardTouchPositionAfter",after[0]).put("boardTouchOffsetBefore",before[1]).put("boardTouchOffsetAfter",after[1]).put("boardTouchPendingMarkerReleased",listenerReleased);
        main(()->{Method restore=MainActivity.class.getDeclaredMethod("restoreCatalogPosition",ListView.class,int.class,int.class,int.class);restore.setAccessible(true);restore.invoke(activity,list,41,-17,(Integer)field(activity,"generation"));set(activity,"mainTab",0);Object current=field(activity,"current");Method go=MainActivity.class.getDeclaredMethod("go",current.getClass(),boolean.class);go.setAccessible(true);go.invoke(activity,current,false);return null;});waitForIdleSync();boolean detached=main(()->!list.isAttachedToWindow()&&list.getTag()==null);report.put("boardPendingRestoreReleasedOnPageExit",detached);return touch&&detached;
    }
    @Override public void onStart(){Application app=null;NgaStore store=null;String account="",host="",cookie="",day="";boolean completed=false;try{
        if(!authorized)throw new IllegalStateException("EXPLICIT_RECREATION_ONLY_REQUIRED");store=new NgaStore(getTargetContext());account=store.accountId();host=store.host();cookie=store.cookie();day=store.lastCheckInDay();
        if(!store.loggedIn()||!NgaStore.today().equals(day))throw new IllegalStateException("SIGNED_IN_TODAY_GUARD");app=(Application)getTargetContext().getApplicationContext();app.registerActivityLifecycleCallbacks(callbacks);
        activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.ngapure.app","cn.ngapure.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
        stage="rating";renderHome(2,"4",2,"收藏版块","");recreate();boolean rating=main(()->"4".equals(field(activity,"ratingCategory"))&&Integer.valueOf(2).equals(field(activity,"ratingPage")));report.put("ratingCategoryAndPageRetained",rating);
        stage="boardFilter";renderHome(1,"4",2,"全部版块","原神");recreate();boolean filter=main(()->"全部版块".equals(field(activity,"communityCategory"))&&"原神".equals(field(activity,"communityQuery")));report.put("publicSyntheticBoardFilterRetained",filter);
        stage="boardAnchor";renderHome(1,"4",2,"全部版块","");main(()->{ListView list=(ListView)field(activity,"communityList");int count=list.getCount();if(count<40)throw new IllegalStateException("CATALOG_TOO_SMALL");list.setSelectionFromTop(27,-29);return null;});waitForIdleSync();SystemClock.sleep(400);int[] before=listAnchor();recreate();int[] after=listAnchor();boolean anchor=before[0]==after[0]&&Math.abs(before[1]-after[1])<=1;report.put("boardAnchorRetained",anchor).put("boardRowPositionBefore",before[0]).put("boardRowPositionAfter",after[0]).put("boardOffsetBefore",before[1]).put("boardOffsetAfter",after[1]);
        boolean restoreCancellation=!testAvatar||restoreCancellationChecks(),avatar=!testAvatar||avatarChecks();report.put("passed",rating&&filter&&anchor&&restoreCancellation&&avatar).put("avatarChecksRequested",testAvatar);completed=true;
    }catch(Exception|AssertionError error){try{report.put("passed",false).put("failureStage",stage).put("failureType",error.getClass().getSimpleName());}catch(Exception ignored){} }
    finally{try{if(activity!=null&&!activity.isDestroyed())renderHome(0,"2",1,"收藏版块","");if(app!=null)app.unregisterActivityLifecycleCallbacks(callbacks);report.put("completed",completed).put("accountAndSessionUnchanged",store!=null&&account.equals(store.accountId())&&host.equals(store.host())&&cookie.equals(store.cookie())&&day.equals(store.lastCheckInDay())).put("communityWriteActionInvoked",false).put("credentialsExported",false).put("userTextExported",false).put("networkPermissionDeclaredByHelper",false);}catch(Exception ignored){}Bundle output=new Bundle();output.putString("safe_recreation_report",report.toString());finish(Activity.RESULT_OK,output);}
    }
}
