package cn.ngapure.verify;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import cn.ngapure.app.NativeImages;
import cn.ngapure.app.NativeRichText;
import cn.ngapure.app.NgaStore;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic local media only. Worker barriers prevent all test URL requests from reaching the network.
 * No account text, media address, pixels or exception details are included in the fixed boolean report.
 */
public final class MediaProbe extends Instrumentation {
    private final JSONObject result=new JSONObject();
    private static final String URL="https://img.nga.cn/attachments/media-probe.png";
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        try { queueAndCache(); viewport(); result.put("passed",true); result.put("failure","NONE"); }
        catch(Exception|AssertionError failed) { put("passed",false); put("failure","MEDIA_CHECK_FAILED"); }
        Bundle output=new Bundle(); output.putString("media_probe",result.toString()); finish(0,output);
    }
    private void queueAndCache() throws Exception {
        NativeImages loader=new NativeImages();
        ThreadPoolExecutor workers=(ThreadPoolExecutor)field(loader,"workers");
        CountDownLatch started=new CountDownLatch(2),release=new CountDownLatch(1);
        AtomicInteger callbacks=new AtomicInteger();
        NativeImages.DrawableCallback callback=new NativeImages.DrawableCallback() {
            @Override public void loaded(Drawable drawable) { callbacks.incrementAndGet(); }
            @Override public void failed(String message) { callbacks.incrementAndGet(); }
        };
        try {
            for(int i=0;i<2;i++)workers.execute(()->{ started.countDown(); try { release.await(); }catch(InterruptedException ignored) { Thread.currentThread().interrupt(); } });
            require(started.await(3,TimeUnit.SECONDS),"workersStarted");
            onMain(()->{
                List<NativeImages.Request> pending=new ArrayList<>();
                for(int i=0;i<40;i++)pending.add(loader.loadDrawable(URL+"?synthetic="+i,false,callback));
                require(((Set<?>)field(loader,"mediaTasks")).size()==24&&workers.getQueue().size()==24,"mediaQueueBounded");
                for(NativeImages.Request request:pending)request.cancel();
                require(((Set<?>)field(loader,"mediaTasks")).isEmpty()&&workers.getQueue().isEmpty(),"cancelRemovesQueuedWork");
                return null;
            });
            waitForIdleSync(); require(callbacks.get()==0,"cancelSuppressesRejectedAndQueuedCallbacks");
            onMain(()->{
                LruCache<String,Bitmap> cache=cache(loader); Bitmap bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);
                cache.put("inline:drawable:"+URL,bitmap);
                loader.loadDrawable(URL,false,callback).cancel();
                loader.trimMemory(40);
                require(cache.size()==0&&!bitmap.isRecycled(),"trimKeepsDisplayedBitmapValid");
                return null;
            });
            waitForIdleSync(); require(callbacks.get()==0,"cancelSuppressesCachedCallback");
            put("syntheticMediaNetworkRequests",0);
        } finally { release.countDown(); loader.close(); }
    }
    @SuppressWarnings("unchecked") private static LruCache<String,Bitmap> cache(NativeImages loader) throws Exception { return (LruCache<String,Bitmap>)field(loader,"cache"); }
    private void viewport() throws Exception {
        NgaStore store=new NgaStore(getTargetContext());
        if(store.autoCheckIn()&&(!store.loggedIn()||!NgaStore.today().equals(store.lastCheckInDay())))throw new IllegalStateException();
        require(true,"onOpenSignInGuardPassed");
        Activity activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.ngapure.app","cn.ngapure.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        NativeImages loader=new NativeImages(); loader.close(); // No image request can leave this synthetic tree.
        ViewGroup content=onMain(()->(ViewGroup)activity.findViewById(android.R.id.content));
        View original=onMain(()->content.getChildAt(0));
        ViewGroup.LayoutParams originalParams=onMain(original::getLayoutParams);
        ScrollView scroll=onMain(()->new ScrollView(activity));
        try {
            StringBuilder source=new StringBuilder();
            for(int i=0;i<80;i++)source.append("[img]").append(URL).append("[/img]\n");
            NativeRichText rich=onMain(()->new NativeRichText(activity,loader,source.toString(),false,16,null));
            require(onMain(()->requests(rich))==0,"detachedTreeStartsNoRequests");
            onMain(()->{content.removeAllViews();content.addView(scroll,new ViewGroup.LayoutParams(-1,-1));scroll.addView(rich);return null;});
            settle();
            int initial=onMain(()->requests(rich));
            require(initial>0&&initial<40,"loadsOnlyNearViewport");
            Object first=onMain(()->media(rich).get(0));
            onMain(()->{scroll.scrollTo(0,scroll.getHeight()*3);return null;}); settle();
            require(onMain(()->field(first,"request")==null&&requests(rich)>0&&requests(rich)<40),"scrollCancelsOldViewportRequests");
            onMain(()->{scroll.removeAllViews();return null;});
            require(onMain(()->requests(rich))==0,"detachCancelsAllRequests");

            NativeRichText animated=onMain(()->new NativeRichText(activity,loader,source.toString(),false,16,null));
            List<TestDrawable> animations=new ArrayList<>();
            onMain(()->{
                for(Object image:media(animated)) {TestDrawable drawable=new TestDrawable(320,160);animations.add(drawable);loaded(image,drawable);}
                scroll.addView(animated);scroll.scrollTo(0,0);return null;
            }); settle();
            require(onMain(()->running(animations)>0&&running(animations)<40),"onlyVisibleInlineAnimationsRun");
            onMain(()->{scroll.scrollTo(0,animated.getHeight());return null;}); settle();
            require(onMain(()->!animations.get(0).isRunning()&&running(animations)>0&&running(animations)<40),"scrollStopsOffscreenAnimations");
            onMain(()->{scroll.removeAllViews();return null;});
            require(onMain(()->running(animations)==0),"detachStopsAllAnimations");

            StringBuilder anchorSource=new StringBuilder("[img]"+URL+"[/img]\n");
            for(int i=0;i<120;i++)anchorSource.append("本机合成阅读锚点 ").append(i).append("\n");
            anchorSource.append("[img]").append(URL).append("[/img]");
            NativeRichText anchor=onMain(()->new NativeRichText(activity,loader,anchorSource.toString(),false,16,null));
            onMain(()->{for(Object image:media(anchor))loaded(image,new TestDrawable(320,80));scroll.addView(anchor);scroll.scrollTo(0,0);return null;});settle();
            onMain(()->{scroll.scrollTo(0,450);return null;});settle();
            TextView text=onMain(()->(TextView)field(media(anchor).get(0),"owner"));
            int offset=onMain(()->{
                Layout layout=text.getLayout();int vertical=scroll.getScrollY()-anchor.getTop()-text.getTop()-text.getTotalPaddingTop();
                return layout.getLineStart(layout.getLineForVertical(Math.max(0,vertical)));
            });
            int before=onMain(()->lineY(scroll,anchor,text,offset));
            onMain(()->{loaded(media(anchor).get(0),new TestDrawable(320,500));return null;});settle();
            int after=onMain(()->lineY(scroll,anchor,text,offset));
            require(Math.abs(before-after)<=2,"imageResizePreservesVisibleTextAnchor");
            put("anchorDifferencePixels",Math.abs(before-after));
        } finally {
            onMain(()->{content.removeAllViews();content.addView(original,originalParams);return null;});loader.close();
        }
    }
    private static int lineY(ScrollView scroll,NativeRichText rich,TextView text,int offset) {
        Layout layout=text.getLayout();return rich.getTop()+text.getTop()+text.getTotalPaddingTop()+layout.getLineTop(layout.getLineForOffset(offset))-scroll.getScrollY();
    }
    private void settle() { waitForIdleSync();SystemClock.sleep(160);waitForIdleSync(); }
    private static int running(List<TestDrawable> drawables) {int count=0;for(TestDrawable drawable:drawables)if(drawable.isRunning())count++;return count;}
    private static List<?> media(NativeRichText rich) throws Exception {return (List<?>)field(rich,"inlineImages");}
    private static int requests(NativeRichText rich) throws Exception {int count=0;for(Object image:media(rich))if(field(image,"request")!=null)count++;return count;}
    private static void loaded(Object image,Drawable drawable) throws Exception {Method method=image.getClass().getDeclaredMethod("loaded",Drawable.class);method.setAccessible(true);method.invoke(image,drawable);}
    private static Object field(Object target,String name) throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private <T>T onMain(Callable<T> action) throws Exception {FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get(5,TimeUnit.SECONDS);}
    private void require(boolean condition,String key) {put(key,condition);if(!condition)throw new AssertionError();}
    private void put(String key,Object value) {try {result.put(key,value);}catch(Exception ignored) {/* Fixed local projections only. */}}
    @SuppressWarnings("deprecation") private static final class TestDrawable extends Drawable implements Animatable {
        private final int width,height;private boolean running;
        TestDrawable(int width,int height) {this.width=width;this.height=height;}
        @Override public int getIntrinsicWidth(){return width;}
        @Override public int getIntrinsicHeight(){return height;}
        @Override public void draw(Canvas canvas){ }
        @Override public void setAlpha(int alpha){ }
        @Override public void setColorFilter(ColorFilter filter){ }
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
        @Override public void start(){running=true;}
        @Override public void stop(){running=false;}
        @Override public boolean isRunning(){return running;}
    }
}
