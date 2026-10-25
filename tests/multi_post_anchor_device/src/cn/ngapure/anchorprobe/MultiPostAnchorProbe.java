package cn.ngapure.anchorprobe;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import cn.ngapure.app.NativeImages;
import cn.ngapure.app.NativeRichText;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Production classes unchanged; source and binary binding travels in the APK. */
public final class MultiPostAnchorProbe extends Instrumentation {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final JSONObject report=new JSONObject(),checks=new JSONObject();
    private final JSONArray cases=new JSONArray();
    private final Map<String,Resource> resources=new ConcurrentHashMap<>();
    private final List<NativeImages> loaders=new ArrayList<>();
    private AnchorHostActivity activity;
    private int assertions;
    private boolean edgeOnly,expectEdgeStable=true;
    private static final class Resource {
        final byte[] bytes;
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicInteger fetches=new AtomicInteger(),disconnects=new AtomicInteger();
        Resource(byte[] value,boolean delayed){bytes=value;if(!delayed)release.countDown();}
    }
    private static final class Scene {
        NativeImages images;
        NativeRichText first,target;
        Object delayedImage;
        TextView delayedOwner,readingOwner;
        String url;
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);if(args!=null){edgeOnly=args.getString("edgeOnly","false").equals("true");expectEdgeStable=args.getString("expectEdgeStable","true").equals("true");}start();}
    @Override public void onStart() {
        boolean passed=false,cleanup=true;
        try {
            require(getTargetContext().getPackageName().equals("cn.ngapure.anchorprobe"),"helperTargetOnly");
            require(getTargetContext().checkSelfPermission(Manifest.permission.INTERNET)==PackageManager.PERMISSION_DENIED,"noInternetPermission");
            try{require(getTargetContext().getApplicationInfo().uid!=getTargetContext().getPackageManager().getApplicationInfo("cn.ngapure.app",0).uid,"uidSeparateFromClient");}catch(PackageManager.NameNotFoundException absent){put(report,"mainInstalled",false);}
            put(report,"binding",new JSONObject(new String(asset("binding.json"),java.nio.charset.StandardCharsets.UTF_8)));
            installTransport();
            activity=(AnchorHostActivity)startActivitySync(new Intent().setClassName("cn.ngapure.anchorprobe","cn.ngapure.anchorprobe.AnchorHostActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            require(activity!=null,"isolatedNativeActivityStarted");
            JSONArray values=new JSONArray();
            put(report,"multiPostAnchorCases",values);
            if(edgeOnly)values.put(runCase("owner_bottom_padding_only_visible",false,-1));
            else {
            values.put(runCase("above_owner_inside_prefetch",false,64));
            values.put(runCase("far_above_cancellation_control",false,400));
            values.put(runCase("same_owner_visible_anchor_control",true,64));
            values.put(runCase("touch_down_up_between_capture_and_layout",false,64,"touch"));
            values.put(runCase("new_scroll_between_capture_and_layout",false,64,"scroll"));
            values.put(runCase("new_page_detaches_owners",false,64,"detach"));
            values.put(runCase("pointer_already_down_at_decode",false,64,"pressed"));
            values.put(runCase("owner_bottom_padding_only_visible",false,-1));
            }
            put(report,"multiPostAnchorCases",values);
            put(report,"defectReproduced",!values.getJSONObject(0).getBoolean("readingLineStable"));
            passed=true;
        } catch(Exception|AssertionError problem) {
            put(report,"failure",problem.getMessage()==null?problem.getClass().getSimpleName():problem.getMessage());
            put(report,"failureClass",problem.getClass().getSimpleName());
        } finally {
            for(Resource value:resources.values())value.release.countDown();
            try{onMain(()->{for(NativeImages images:loaders)images.close();if(activity!=null){activity.content.removeAllViews();activity.finish();}return null;});}catch(Exception problem){cleanup=false;}
            put(report,"passed",passed&&cleanup);put(report,"cleanupPassed",cleanup);put(report,"assertionCount",assertions);
            put(report,"checks",checks);put(report,"cases",cases);put(report,"realAndroidNativeLayout",true);
            put(report,"syntheticMediaOnly",true);put(report,"mainAccountRead",false);put(report,"realNetworkRequests",0);
            put(report,"communityWrites",0);put(report,"productionSourcesChanged",false);put(report,"programmaticScrollNotInjectedFinger",true);
        }
        Bundle output=new Bundle();output.putString("safe_native_multi_post_anchor_report",report.toString());finish(Activity.RESULT_OK,output);
    }
    private JSONObject runCase(String name,boolean ownVisible,int aboveDp)throws Exception {return runCase(name,ownVisible,aboveDp,"");}
    private JSONObject runCase(String name,boolean ownVisible,int aboveDp,String action)throws Exception {
        Scene scene=setup(name,ownVisible);
        final boolean[] actionFired={false};final int[] requestedScroll={-1};
        Resource delayed=resources.get(scene.url);
        require(delayed.entered.await(4,TimeUnit.SECONDS),name+"DelayedImageRequestEntered");
        if(!ownVisible)await(()->onMain(()->loaded(scene.target)),name+"SecondCardImageReadyBeforeReading");
        onMain(()->{
            int[] ownerPosition=new int[2],viewportPosition=new int[2];
            scene.delayedOwner.getLocationInWindow(ownerPosition);activity.scroll.getLocationInWindow(viewportPosition);
            int bottom=ownerPosition[1]-viewportPosition[1]+activity.scroll.getScrollY()+scene.delayedOwner.getHeight();
            if(ownVisible){Layout layout=scene.delayedOwner.getLayout();int offset=(Integer)field(scene.delayedImage,"textOffset");int line=layout.getLineForOffset(offset);bottom=ownerPosition[1]-viewportPosition[1]+activity.scroll.getScrollY()+scene.delayedOwner.getTotalPaddingTop()+layout.getLineBottom(line);}
            activity.scroll.scrollTo(0,bottom+dp(aboveDp));return null;
        });
        SystemClock.sleep(160); // Includes actual scroll/global-layout visibility cancellation callbacks.
        final int readingOffset=onMain(()->readingOffset(scene.readingOwner));
        JSONObject before=onMain(()->snapshot(scene,readingOffset));
        put(report,name+"Before",before);
        require(before.getBoolean("readingLineOnScreen"),name+"ReadingLineActuallyVisibleBeforeResponse");
        if(ownVisible){require(before.getBoolean("delayedOwnerVisible"),name+"ExistingOwnerVisibleControl");}
        else if(aboveDp<0){require(before.getBoolean("delayedOwnerVisible"),name+"OnlyBottomPaddingActuallyVisible");require(before.getInt("delayedTextViewportOffset")>=before.getInt("delayedTextLayoutHeight"),name+"ActualTextLayoutEntirelyAboveViewport");}
        else require(!before.getBoolean("delayedOwnerVisible"),name+"DelayedOwnerActuallyAboveViewport");
        if(aboveDp<320) {
            require(before.getBoolean("imageNearPrefetch320"),name+"RequestStillInsidePrefetchMargin");
            require(before.getBoolean("requestPending")&&!before.getBoolean("actualLoaded"),name+"OriginalFirstDecodeStillPending");
        } else await(()->onMain(()->field(scene.delayedImage,"request")==null),name+"FarRequestWasCancelled");
        String beforeScreenshot=screenshot(name+"-before");
        if(!action.isEmpty())onMain(()->{
            if(action.equals("pressed")){touch(android.view.MotionEvent.ACTION_DOWN);actionFired[0]=true;requestedScroll[0]=activity.scroll.getScrollY();}
            else activity.scroll.beforeAnchorLayout=()->{
                if(field(scene.delayedOwner,"pendingAnchor")==null)return false;
                actionFired[0]=true;activity.scroll.beforeAnchorLayout=null;
                if(action.equals("touch")){touch(android.view.MotionEvent.ACTION_DOWN);touch(android.view.MotionEvent.ACTION_UP);}
                if(action.equals("scroll"))activity.scroll.scrollBy(0,dp(24));
                if(action.equals("detach")){activity.content.removeAllViews();TextView page=new TextView(activity);page.setText("全新页面 · 旧阅读锚点必须放弃");activity.content.addView(page,new LinearLayout.LayoutParams(-1,dp(4000)));activity.scroll.scrollTo(0,dp(100));}
                requestedScroll[0]=activity.scroll.getScrollY();return true;
            };
            return null;
        });
        delayed.release.countDown();
        if(action.equals("detach"))await(()->onMain(()->actionFired[0]&&!scene.first.isAttachedToWindow()),name+"OldOwnersActuallyDetached");
        else if(aboveDp<320)await(()->onMain(()->field(scene.delayedImage,"actual")!=null),name+"DelayedImageActuallyDecoded");
        else SystemClock.sleep(160);
        // Two layout/observer turns are allowed; collect a settled snapshot, not the callback's old layout.
        SystemClock.sleep(320);onMain(()->null);
        JSONObject after=onMain(()->snapshot(scene,readingOffset));
        put(report,name+"After",after);
        String afterScreenshot=screenshot(name+"-after");
        int drift=after.getInt("readingLineBaselineScreenY")-before.getInt("readingLineBaselineScreenY");
        boolean stable=Math.abs(drift)<=1;
        if(aboveDp>=320){require(stable,name+"CancelledFarRequestDoesNotMoveReadingLine");require(!after.getBoolean("actualLoaded"),name+"CancelledImageHasNoStaleDelivery");}
        if(ownVisible)require(stable,name+"ExistingVisibleOwnerAnchorRetainsReadingLine");
        if(action.isEmpty()&&aboveDp<320&&(aboveDp>=0||expectEdgeStable))require(stable,name+"CrossOwnerReadingLineRetained");
        if(!action.isEmpty()){
            require(actionFired[0],name+"ActionActuallyExecutedAtAnchorBoundary");
            require(after.getInt("scrollY")==requestedScroll[0],name+"NoForcedRestoreAfterUserOrPageChange");
            if(action.equals("detach"))require(onMain(()->!scene.first.isAttachedToWindow()&&!scene.readingOwner.isAttachedToWindow()),name+"OldPageAndReadingOwnerDetached");
            if(action.equals("pressed"))onMain(()->{touch(android.view.MotionEvent.ACTION_UP);return null;});
        }
        JSONObject result=new JSONObject();put(result,"case",name);put(result,"before",before);put(result,"after",after);
        put(result,"readingLineBaselineDriftPixels",drift);put(result,"readingLineStable",stable);
        put(result,"imageHeightGrowthPixels",after.getInt("imageSpanHeight")-before.getInt("imageSpanHeight"));
        put(result,"scrollYChangePixels",after.getInt("scrollY")-before.getInt("scrollY"));
        put(result,"interruptAction",action);put(result,"interruptActionExecuted",actionFired[0]);put(result,"explicitRequestedScrollY",requestedScroll[0]);
        put(result,"transportRequests",delayed.fetches.get());put(result,"transportDisconnects",delayed.disconnects.get());
        put(result,"beforeScreenshotHelperPrivatePath",beforeScreenshot);put(result,"afterScreenshotHelperPrivatePath",afterScreenshot);
        onMain(()->{scene.images.close();activity.content.removeAllViews();return null;});
        cases.put(name);return result;
    }
    private Scene setup(String name,boolean ownVisible)throws Exception {
        byte[] large=png(1600,2000),small=png(160,90);
        return onMain(()->{
            Scene scene=new Scene();activity.content.removeAllViews();activity.scroll.scrollTo(0,0);
            scene.images=new NativeImages();scene.images.setMediaCacheDirectory(new File(activity.getCacheDir(),"synthetic-"+name+"-"+SystemClock.elapsedRealtimeNanos()));loaders.add(scene.images);
            scene.url=resource(name+"-delayed.png",large,true);
            for(int i=0;i<5;i++) {
                LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(12),0,dp(12),0);
                LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(0,0,0,dp(10));activity.content.addView(card,params);
                TextView title=new TextView(activity);title.setText("独立帖子 #"+i);title.setTextSize(15);title.setTextColor(Color.BLACK);card.addView(title,new LinearLayout.LayoutParams(-1,dp(56)));
                String image=i==0?scene.url:resource(name+"-small-"+i+".png",small,false);
                StringBuilder text=new StringBuilder("[img]").append(image).append("[/img]");
                if(i>0||ownVisible)for(int line=0;line<90;line++)text.append("\n卡片").append(i).append(" 正在阅读位置 ").append(line).append(" · 不应被上方延迟图片挤动");
                NativeRichText rich=new NativeRichText(activity,scene.images,text.toString(),false,16,url->{});
                rich.setPadding(dp(4),dp(2),dp(4),dp(8));card.addView(rich,new LinearLayout.LayoutParams(-1,-2));
                TextView footer=new TextView(activity);footer.setText("净赞 1    回复    更多");card.addView(footer,new LinearLayout.LayoutParams(-1,dp(48)));
                if(i==0){scene.first=rich;scene.delayedImage=images(rich).get(0);scene.delayedOwner=(TextView)field(scene.delayedImage,"owner");}
                if(i==1&&!ownVisible){scene.target=rich;scene.readingOwner=(TextView)field(images(rich).get(0),"owner");}
            }
            if(ownVisible){scene.target=scene.first;scene.readingOwner=scene.delayedOwner;}
            return scene;
        });
    }
    private void touch(int action){long now=SystemClock.uptimeMillis();android.view.MotionEvent event=android.view.MotionEvent.obtain(now,now,action,activity.scroll.getWidth()/2f,activity.scroll.getHeight()/2f,0);try{activity.scroll.dispatchTouchEvent(event);}finally{event.recycle();}}
    private int readingOffset(TextView owner)throws Exception {
        Layout layout=owner.getLayout();require(layout!=null,"NativeReadingLayoutPresent"+assertions);
        int[] text=new int[2],viewport=new int[2];owner.getLocationInWindow(text);activity.scroll.getLocationInWindow(viewport);
        int vertical=viewport[1]+Math.min(dp(140),activity.scroll.getHeight()/3)-text[1]-owner.getTotalPaddingTop()+owner.getScrollY();
        return layout.getLineStart(layout.getLineForVertical(Math.max(0,vertical)));
    }
    private JSONObject snapshot(Scene scene,int offset)throws Exception {
        JSONObject value=new JSONObject();TextView owner=scene.readingOwner;Layout layout=owner.getLayout();int line=layout.getLineForOffset(Math.min(offset,owner.length()));
        int[] text=new int[2],viewport=new int[2];owner.getLocationInWindow(text);activity.scroll.getLocationInWindow(viewport);
        int baseline=text[1]+owner.getTotalPaddingTop()-owner.getScrollY()+layout.getLineBaseline(line);
        Rect visible=new Rect();boolean ownerVisible=scene.delayedOwner.getLocalVisibleRect(visible);
        java.lang.reflect.Method near=scene.delayedImage.getClass().getDeclaredMethod("nearViewport",int.class);near.setAccessible(true);
        put(value,"readingCharOffset",offset);put(value,"readingLineBaselineScreenY",baseline);
        put(value,"readingLineOnScreen",baseline>=viewport[1]&&baseline<viewport[1]+activity.scroll.getHeight());
        put(value,"readingTextExcerpt",owner.getText().subSequence(offset,Math.min(owner.length(),offset+42)).toString());
        put(value,"scrollY",activity.scroll.getScrollY());put(value,"viewportScreenY",viewport[1]);put(value,"viewportHeight",activity.scroll.getHeight());
        int[] sourcePosition=new int[2];scene.delayedOwner.getLocationInWindow(sourcePosition);
        put(value,"delayedTextViewportOffset",viewport[1]-sourcePosition[1]-scene.delayedOwner.getTotalPaddingTop()+scene.delayedOwner.getScrollY());
        put(value,"delayedTextLayoutHeight",scene.delayedOwner.getLayout().getHeight());
        put(value,"columnHeight",activity.content.getHeight());put(value,"delayedOwnerVisible",ownerVisible);
        put(value,"imageNearPrefetch320",near.invoke(scene.delayedImage,dp(320)));
        put(value,"requestPending",field(scene.delayedImage,"request")!=null);put(value,"actualLoaded",field(scene.delayedImage,"actual")!=null);
        put(value,"imageSpanHeight",((Drawable)scene.delayedImage).getBounds().height());put(value,"imageOwnerHeight",scene.delayedOwner.getHeight());
        put(value,"loaderState",scene.images.state());return value;
    }
    private String screenshot(String name)throws IOException {
        Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)return "UNAVAILABLE";
        File directory=new File(getTargetContext().getFilesDir(),"synthetic-anchor-screenshots");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Synthetic screenshot directory unavailable");
        File output=new File(directory,name+".png");try(FileOutputStream stream=new FileOutputStream(output)){bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);}finally{bitmap.recycle();}return output.getAbsolutePath();
    }
    private byte[] png(int width,int height)throws IOException {
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);new Canvas(bitmap).drawColor(Color.rgb(58,119,174));
        try(ByteArrayOutputStream output=new ByteArrayOutputStream()){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,output))throw new IOException("Synthetic PNG encode failed");return output.toByteArray();}finally{bitmap.recycle();}
    }
    private byte[] asset(String name)throws IOException {try(InputStream input=getContext().getAssets().open(name);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);return output.toByteArray();}}
    private String resource(String name,byte[] bytes,boolean delayed){String url="https://img.nga.cn/attachments/isolated-anchor-probe/"+name;resources.put(url,new Resource(bytes,delayed));return url;}
    private void installTransport(){URL.setURLStreamHandlerFactory(protocol->!protocol.equals("https")?null:new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{
        Resource resource=resources.get(url.toString());if(resource==null)throw new IOException("Only synthetic fixture URL allowed");resource.fetches.incrementAndGet();
        return new HttpURLConnection(url){private volatile boolean disconnected;
            @Override public int getResponseCode()throws IOException{if(getRequestProperty("Cookie")!=null||getRequestProperty("Authorization")!=null)throw new IOException("Anonymous fixture only");resource.entered.countDown();try{if(!resource.release.await(20,TimeUnit.SECONDS))throw new IOException("Delayed synthetic fixture timed out");}catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new IOException("Cancelled synthetic request",cancelled);}if(disconnected)throw new IOException("Disconnected synthetic request");return 200;}
            @Override public long getContentLengthLong(){return resource.bytes.length;}@Override public InputStream getInputStream(){return new ByteArrayInputStream(resource.bytes);}
            @Override public void disconnect(){disconnected=true;resource.disconnects.incrementAndGet();resource.release.countDown();}@Override public void connect(){}@Override public boolean usingProxy(){return false;}
        };}});}
    @SuppressWarnings("unchecked") private static List<Object> images(NativeRichText rich)throws Exception{return (List<Object>)field(rich,"inlineImages");}
    private static boolean loaded(NativeRichText rich)throws Exception{for(Object image:images(rich))if(field(image,"actual")==null)return false;return true;}
    private static Object field(Object owner,String name)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private int dp(int value){return Math.round(value*getTargetContext().getResources().getDisplayMetrics().density);}
    private void require(boolean value,String name){assertions++;put(checks,name,value);if(!value)throw new AssertionError(name);}
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(org.json.JSONException invalid){throw new IllegalArgumentException(invalid);}}
    private void await(Callable<Boolean> condition,String name)throws Exception{long end=SystemClock.elapsedRealtime()+6000;while(SystemClock.elapsedRealtime()<end){if(condition.call()){require(true,name);return;}SystemClock.sleep(20);}require(false,name);}
    private <T>T onMain(Callable<T> action)throws Exception{if(Looper.myLooper()==Looper.getMainLooper())return action.call();CountDownLatch done=new CountDownLatch(1);final Object[] result={null};final Throwable[] failure={null};main.post(()->{try{result[0]=action.call();}catch(Throwable problem){failure[0]=problem;}finally{done.countDown();}});if(!done.await(6,TimeUnit.SECONDS))throw new IOException("Main fixture timeout");if(failure[0]!=null){if(failure[0] instanceof Exception)throw (Exception)failure[0];if(failure[0] instanceof Error)throw (Error)failure[0];throw new IllegalStateException(failure[0]);}@SuppressWarnings("unchecked")T value=(T)result[0];return value;}
}
