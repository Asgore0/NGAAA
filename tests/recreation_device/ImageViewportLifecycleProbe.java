package cn.ngapure.recreationprobe;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import cn.ngapure.app.MainActivity;
import cn.ngapure.app.NativeImages;
import cn.ngapure.app.NativePictureView;
import cn.ngapure.app.NgaStore;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Real MainActivity saved-state routing, generated images, and a closed media loader: no image network. */
public final class ImageViewportLifecycleProbe extends Instrumentation {
    private volatile Activity activity;
    private boolean authorized;
    private volatile boolean mediaDisabledBeforeCreate=true;
    private volatile int preCreateMediaClosures;
    private final JSONObject report=new JSONObject();
    private final ArrayList<Bitmap> fixtures=new ArrayList<>();
    private int assertions;
    private String stage="guard";
    private final Application.ActivityLifecycleCallbacks callbacks=new Application.ActivityLifecycleCallbacks(){
        @Override public void onActivityPreCreated(Activity value,Bundle state){if(value instanceof MainActivity)try{((NativeImages)field(value,"nativeImages")).close();preCreateMediaClosures++;}catch(Exception failure){mediaDisabledBeforeCreate=false;}}
        public void onActivityResumed(Activity value){if(value instanceof MainActivity)activity=value;}
        public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
    };
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("generatedImageOnly"));start();}
    private static Object field(Object target,String name)throws Exception{Field member=target.getClass().getDeclaredField(name);member.setAccessible(true);return member.get(target);}
    private <T>T main(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private void check(String name,boolean value)throws Exception{report.put(name,value);assertions++;if(!value)throw new AssertionError(name);}
    private void screen(String kind,String id)throws Exception{main(()->{Class<?> type=Class.forName("cn.ngapure.app.MainActivity$Screen");Constructor<?> constructor=type.getDeclaredConstructor(String.class,String.class,String.class,int.class);constructor.setAccessible(true);Object screen=constructor.newInstance(kind,id,"合成图片测试",1);Method go=MainActivity.class.getDeclaredMethod("go",type,boolean.class);go.setAccessible(true);go.invoke(activity,screen,false);return null;});waitForIdleSync();}
    private NativePictureView picture()throws Exception{return main(()->(NativePictureView)field(activity,"pictureView"));}
    private Bitmap injectFixture()throws Exception{return main(()->{Bitmap bitmap=Bitmap.createBitmap(600,1600,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff479de1);fixtures.add(bitmap);NativePictureView picture=(NativePictureView)field(activity,"pictureView");picture.show(bitmap);return bitmap;});}
    private void recreate()throws Exception{Activity previous=activity;main(()->{activity.recreate();return null;});for(int i=0;i<120;i++){if(activity!=null&&activity!=previous&&!activity.isDestroyed()){waitForIdleSync();SystemClock.sleep(150);return;}SystemClock.sleep(100);}throw new IllegalStateException("RECREATE_TIMEOUT");}
    private static boolean same(float[] first,float[] second){if(first==null||second==null||first.length!=3||second.length!=3)return false;for(int i=0;i<3;i++)if(Math.abs(first[i]-second[i])>.0001f)return false;return true;}
    @SuppressWarnings("deprecation") @Override public void onStart(){Application app=null;NgaStore store=null;String account="",host="",cookie="",day="";try{
        if(!authorized||android.os.Build.VERSION.SDK_INT<29)throw new IllegalStateException("EXPLICIT_GENERATED_IMAGE_ONLY_API29_REQUIRED");store=new NgaStore(getTargetContext());account=store.accountId();host=store.host();cookie=store.cookie();day=store.lastCheckInDay();if(!store.loggedIn()||!NgaStore.today().equals(day))throw new IllegalStateException("SIGNED_IN_TODAY_GUARD");app=(Application)getTargetContext().getApplicationContext();app.registerActivityLifecycleCallbacks(callbacks);
        activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.ngapure.app","cn.ngapure.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
        check("initialMediaLoaderClosedBeforeOpeningAnySyntheticUrl",mediaDisabledBeforeCreate&&preCreateMediaClosures==1);
        stage="initialImage";String first="https://img.nga.cn/attachments/ngapure-fixture-a.png",second="https://img.nga.cn/attachments/ngapure-fixture-b.png";screen("image",first);injectFixture();NativePictureView initial=picture();main(()->{initial.restoreViewport(new float[]{2.5f,.4f,.6f});return null;});float[] expected=main(initial::viewportState);check("generatedImageViewportHasZoomAndPan",expected!=null&&expected[0]>2&&Math.abs(expected[1]-.4f)<.001f&&Math.abs(expected[2]-.6f)<.001f);
        Bundle snapshot=main(()->{Bundle saved=new Bundle();((MainActivity)activity).onSaveInstanceState(saved);return saved;});Object savedViewport=snapshot.get("ngapure_image_viewport_v1");check("mainSavesOnlyThreeViewportFloats",savedViewport instanceof float[]&&((float[])savedViewport).length==3);check("mainBindsViewportToExactImageUrl",first.equals(snapshot.getString("ngapure_image_viewport_url_v1")));
        stage="pendingRecreate";recreate();NativePictureView pending=picture();check("recreatedViewportExistsBeforeDrawable",pending!=null&&same(expected,main(pending::viewportState)));recreate();NativePictureView twice=picture();check("secondRecreateRetainsStillPendingViewport",twice!=null&&same(expected,main(twice::viewportState)));injectFixture();check("mainRecreateAppliesZoomAndPanWhenDrawableArrives",same(expected,main(twice::viewportState)));
        stage="differentIdentity";screen("image",second);injectFixture();NativePictureView other=picture();check("differentImageDoesNotInheritOldZoomAndPan",same(new float[]{1,.5f,.5f},main(other::viewportState)));screen("home","");check("leavingImageClearsViewAndUrlReferences",main(()->field(activity,"pictureView")==null&&field(activity,"pictureUrl")==null));check("mediaLoaderDisabledBeforeEveryOnCreate",mediaDisabledBeforeCreate&&preCreateMediaClosures==3);
        report.put("completed",true).put("passed",true).put("assertions",assertions);
    }catch(Exception|AssertionError failure){try{report.put("completed",false).put("passed",false).put("failureStage",stage).put("assertions",assertions).put("failureType",failure.getClass().getSimpleName());}catch(Exception ignored){} }
    finally{try{if(activity!=null&&!activity.isDestroyed()){screen("home","");main(()->{activity.finish();return null;});waitForIdleSync();}if(app!=null)app.unregisterActivityLifecycleCallbacks(callbacks);for(Bitmap fixture:fixtures)if(!fixture.isRecycled())fixture.recycle();report.put("accountAndSessionUnchanged",store!=null&&account.equals(store.accountId())&&host.equals(store.host())&&cookie.equals(store.cookie())&&day.equals(store.lastCheckInDay())).put("imageNetworkRequestsPermitted",false).put("generatedImagesOnly",true).put("realImageDataExported",false).put("credentialsExported",false).put("communityWriteActionInvoked",false);}catch(Exception ignored){}Bundle output=new Bundle();output.putString("safe_image_viewport_report",report.toString());finish(Activity.RESULT_OK,output);}}
}
