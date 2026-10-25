package cn.ngapure.richvisibilityprobe;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import cn.ngapure.app.NativeImages;
import cn.ngapure.app.NativeRichText;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Exact production classes, real Android views/decoder, and an in-process anonymous transport. */
public final class RichVisibilityProbe extends Instrumentation {
    private static final String SMILE="https://img4.nga.cn/ngabbs/post/smile/smile.gif";
    private final JSONObject report=new JSONObject();
    private final JSONArray checks=new JSONArray();
    private final Map<String,Resource> resources=new ConcurrentHashMap<>();
    private final AtomicInteger unexpected=new AtomicInteger();
    private ProbeHostActivity activity;
    private NativeImages loader;
    private byte[] png;
    private boolean expectShared;
    private int assertions;
    private static final class Resource {
        final byte[] bytes;
        final CountDownLatch gate;
        final AtomicInteger requests=new AtomicInteger();
        Resource(byte[] bytes,boolean blocked){this.bytes=bytes;gate=new CountDownLatch(blocked?1:0);}
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);expectShared=args!=null&&"true".equals(args.getString("expectShared"));start();}
    @Override public void onStart() {
        boolean passed=false,clean=true;String failure="NONE";
        try {
            require(getTargetContext().checkSelfPermission(Manifest.permission.INTERNET)==PackageManager.PERMISSION_DENIED,"NoInternetPermission");
            require(getTargetContext().getApplicationInfo().uid!=getTargetContext().getPackageManager().getApplicationInfo("cn.ngapure.app",0).uid,"SeparateUid");
            report.put("binding",new JSONObject(new String(asset("binding.json"),java.nio.charset.StandardCharsets.UTF_8)));
            Bitmap bitmap=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff7196b0);
            ByteArrayOutputStream output=new ByteArrayOutputStream();require(bitmap.compress(Bitmap.CompressFormat.PNG,100,output),"SyntheticPngEncoded");bitmap.recycle();png=output.toByteArray();
            installTransport();activity=(ProbeHostActivity)startActivitySync(new Intent().setClassName("cn.ngapure.richvisibilityprobe","cn.ngapure.richvisibilityprobe.ProbeHostActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            viewportWork();retryLifecycle();require(unexpected.get()==0,"OnlyRegisteredSyntheticUrls");passed=true;
        } catch(Exception|AssertionError issue) { failure=issue.getClass().getSimpleName()+":"+issue.getMessage(); }
        finally {
            for(Resource resource:resources.values())resource.gate.countDown();
            try { onMain(()->{if(activity!=null){activity.content.removeAllViews();activity.finish();}if(loader!=null)loader.close();return null;}); }
            catch(Exception issue){clean=false;}
            try { report.put("passed",passed&&clean).put("cleanupPassed",clean).put("failure",failure).put("assertionCount",assertions)
                    .put("checks",checks).put("expectShared",expectShared).put("productionClassesRewritten",false)
                    .put("mainClientStarted",false).put("mainClientDataRead",false).put("realNetworkRequests",0).put("communityWrites",0)
                    .put("unexpectedMediaUrls",unexpected.get()); }
            catch(Exception ignored){ }
        }
        Bundle result=new Bundle();result.putString("safe_rich_visibility_report",report.toString());finish(Activity.RESULT_OK,result);
    }
    private void viewportWork()throws Exception {
        loader=new NativeImages();String url="https://img4.nga.cn/attachments/visibility-long.png";resources.put(url,new Resource(png,false));
        StringBuilder raw=new StringBuilder();for(int i=0;i<50;i++)raw.append("图片 ").append(i+1).append("[img]").append(url).append("[/img]\n");
        NativeRichText rich=onMain(()->{NativeRichText value=new NativeRichText(activity,loader,raw.toString(),false,16,null);activity.content.addView(value,new ViewGroup.LayoutParams(-1,-2));return value;});
        await(()->onMain(()->rich.getHeight()>0&&spans(rich).size()==50&&field(spans(rich).get(0),"actual")!=null),"FiftyNativeImageSpansWithRealDecodedPixels");
        waitForIdleSync();SystemClock.sleep(150);waitForIdleSync();
        JSONObject measured=onMain(()->{
            List<?> spans=spans(rich);HashSet<Object> owners=new HashSet<>();for(Object image:spans)owners.add(field(image,"owner"));
            Method sync=method(rich,"syncImageVisibility");for(int i=0;i<20;i++)sync.invoke(rich);
            int iterations=500;activity.scroll.resetCounts();long started=Debug.threadCpuTimeNanos();
            for(int i=0;i<iterations;i++)sync.invoke(rich);
            long cpu=Debug.threadCpuTimeNanos()-started;int samples=activity.scroll.visibleReads,locations=activity.scroll.locationReads;
            require(owners.size()==1,"AllFiftySpansShareOneMeasuredNativeOwner");
            require(expectShared?samples==iterations*owners.size():samples>=iterations*spans.size(),expectShared?"OneViewportSamplePerOwnerAndPass":"BaselineRepeatedViewportSamplesPerSpan");
            require(locations==samples,"EveryCountedVisibleSampleHasMatchingViewportLocationRead");
            if(expectShared)require(((Long)field(rich,"activeVisibilityPass"))==0L,"VisibilityPassEndsInFinally");
            activity.scroll.resetCounts();Object first=spans.get(0);Method near=method(first,"nearViewport",int.class);
            for(int i=0;i<3;i++)require((Boolean)near.invoke(first,0),"DirectVisiblePredicate"+i);
            int directReads=activity.scroll.visibleReads;require(directReads==3,"AnimationPredicatesSampleFreshOutsideBatch");
            return new JSONObject().put("images",spans.size()).put("owners",owners.size()).put("iterations",iterations)
                    .put("viewportVisibleRectSamples",samples).put("viewportLocationSamples",locations).put("mainThreadCpuNanos",cpu)
                    .put("cpuIsObservationNotFrameRate",true).put("directPredicateSamples",directReads);
        });report.put("longPostWork",measured);
        onMain(()->{Object first=spans(rich).get(0);activity.scroll.scrollTo(0,rich.getHeight());require(!(Boolean)method(first,"nearViewport",int.class).invoke(first,0),"NextIndependentPredicateSeesNewScrollImmediately");return null;});
        onMain(()->{activity.content.removeAllViews();activity.scroll.scrollTo(0,0);loader.close();return null;});
    }
    private void retryLifecycle()throws Exception {
        loader=new NativeImages();String fillerUrl="https://img4.nga.cn/attachments/visibility-blocked.png";
        Resource filler=new Resource(png,true);resources.put(fillerUrl,filler);resources.put(SMILE,new Resource(png,false));
        List<NativeImages.Request> fillers=new ArrayList<>();
        NativeImages.DrawableCallback sink=new NativeImages.DrawableCallback(){@Override public void loaded(Drawable value){ }@Override public void failed(String message){ }};
        onMain(()->{for(int i=0;i<32;i++)fillers.add(loader.loadDrawable(fillerUrl,false,sink));return null;});
        NativeRichText rich=onMain(()->{NativeRichText value=new NativeRichText(activity,loader,"[s:1]",false,16,null);activity.content.addView(value,new ViewGroup.LayoutParams(-1,-2));View spacer=new View(activity);activity.content.addView(spacer,new ViewGroup.LayoutParams(-1,activity.scroll.getHeight()*4));return value;});
        await(()->onMain(()->spans(rich).size()==1&&(Integer)field(spans(rich).get(0),"busyRetries")>=3),"RealLoaderSaturationSchedulesBusyRetry");
        Object image=onMain(()->spans(rich).get(0));View owner=(View)onMain(()->field(image,"owner"));Runnable retry=(Runnable)onMain(()->field(image,"retryLoad"));Handler handler=onMain(owner::getHandler);
        JSONObject retryMetrics=onMain(()->{
            long deadline=(Long)field(image,"busyRetryAt");int attempts=(Integer)field(image,"busyRetries");
            require(deadline>SystemClock.uptimeMillis()+100,"RetryDeadlineStillFutureBeforeRepeatedVisibility");
            require(handler.hasCallbacks(retry)&&queuedRetryCount(handler,retry)==1,"ExactlyOneRealRetryMessageBeforeScrollBurst");
            Method sync=method(rich,"syncImageVisibility");for(int i=0;i<200;i++)sync.invoke(rich);
            require((Long)field(image,"busyRetryAt")==deadline,"ScrollBurstDoesNotMoveOriginalRetryDeadline");
            require(handler.hasCallbacks(retry)&&queuedRetryCount(handler,retry)==1,"ExactlyOneRealRetryMessageAfterScrollBurst");
            if(expectShared){require((Boolean)field(image,"retryScheduled"),"CandidateTracksPendingRetry");require((Long)field(image,"scheduledRetryAt")==deadline,"CandidateRetainsSameScheduledDeadline");}
            return new JSONObject().put("visibilityCallsBeforeDeadline",200).put("deadlineUptimeMillis",deadline).put("busyAttemptsBeforeDeadline",attempts)
                    .put("pendingRetryMessagesBeforeAndAfter",1).put("candidateScheduledStateChecked",expectShared);
        });report.put("retryLifecycle",retryMetrics);
        int attempts=retryMetrics.getInt("busyAttemptsBeforeDeadline");long deadline=retryMetrics.getLong("deadlineUptimeMillis");
        await(()->onMain(()->(Integer)field(image,"busyRetries")>attempts),"OriginalDeadlineActuallyRetriesWhileStillBusy");
        long lateness=SystemClock.uptimeMillis()-deadline;retryMetrics.put("observedRetryLatenessMillis",lateness);require(lateness>=0&&lateness<1000,"RetryFiresNearOriginalDeadline");
        onMain(()->{activity.scroll.scrollTo(0,activity.content.getHeight());method(rich,"syncImageVisibility").invoke(rich);
            require(!handler.hasCallbacks(retry)&&queuedRetryCount(handler,retry)==0,"OutsidePrefetchClearsActualRetryMessage");
            require((Long)field(image,"busyRetryAt")==0L,"OutsidePrefetchClearsBusyDeadline");if(expectShared)require(!(Boolean)field(image,"retryScheduled"),"OutsidePrefetchClearsScheduledState");activity.scroll.scrollTo(0,0);method(rich,"syncImageVisibility").invoke(rich);return null;});
        await(()->onMain(()->handler.hasCallbacks(retry)),"ReturnToViewportSchedulesRetryAgain");
        onMain(()->{activity.content.removeView(rich);require(!handler.hasCallbacks(retry)&&queuedRetryCount(handler,retry)==0,"DetachClearsActualRetryMessage");
            if(expectShared)require(!(Boolean)field(image,"retryScheduled"),"DetachClearsScheduledState");
            for(NativeImages.Request request:fillers)request.cancel();filler.gate.countDown();activity.content.addView(rich,0,new ViewGroup.LayoutParams(-1,-2));return null;});
        await(()->onMain(()->field(image,"actual")!=null),"ReattachedImageRecoversAfterCapacityAvailable");
        onMain(()->{require(!handler.hasCallbacks(retry),"SuccessfulImageClearsRetryMessage");require((Long)field(image,"busyRetryAt")==0L,"SuccessfulImageClearsBusyDeadline");activity.content.removeAllViews();loader.close();return null;});
        retryMetrics.put("imageTransportRequestsAfterRecovery",resources.get(SMILE).requests.get());
    }
    private static int queuedRetryCount(Handler handler,Runnable retry) {
        AtomicInteger count=new AtomicInteger();String callback="callback="+retry.getClass().getName();
        handler.dump(line->{if(line.contains(callback))count.incrementAndGet();},"");return count.get();
    }
    private void installTransport() {
        URL.setURLStreamHandlerFactory(protocol->protocol.equals("https")?new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException {
            Resource resource=resources.get(url.toString());if(resource==null){unexpected.incrementAndGet();throw new IOException("Unregistered synthetic URL");}
            resource.requests.incrementAndGet();return new HttpURLConnection(url){volatile boolean stopped;
                @Override public void connect(){ }@Override public boolean usingProxy(){return false;}@Override public void disconnect(){stopped=true;resource.gate.countDown();}
                @Override public int getResponseCode()throws IOException {
                    if(getRequestProperty("Cookie")!=null||getRequestProperty("Authorization")!=null)throw new IOException("Anonymous transport required");
                    try{if(!resource.gate.await(20,TimeUnit.SECONDS))throw new IOException("Synthetic gate timed out");}catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new IOException("Cancelled",cancelled);}
                    if(stopped)throw new IOException("Cancelled");return HTTP_OK;
                }
                @Override public long getContentLengthLong(){return resource.bytes.length;}
                @Override public InputStream getInputStream(){return new ByteArrayInputStream(resource.bytes);}
            };
        }}:null);
    }
    private static List<?> spans(NativeRichText rich)throws Exception{return (List<?>)field(rich,"inlineImages");}
    private static Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static Method method(Object object,String name,Class<?>...parameters)throws Exception{Method method=object.getClass().getDeclaredMethod(name,parameters);method.setAccessible(true);return method;}
    private <T>T onMain(Callable<T> work)throws Exception{FutureTask<T> task=new FutureTask<>(work);runOnMainSync(task);return task.get();}
    private void await(Callable<Boolean> ready,String check)throws Exception{long end=SystemClock.uptimeMillis()+12000;while(SystemClock.uptimeMillis()<end){if(ready.call()){require(true,check);return;}SystemClock.sleep(10);}require(false,check);}
    private void require(boolean value,String check){assertions++;checks.put(check);if(!value)throw new AssertionError(check);}
    private byte[] asset(String name)throws IOException{try(InputStream input=getTargetContext().getAssets().open(name);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];for(int n;(n=input.read(buffer))!=-1;)output.write(buffer,0,n);return output.toByteArray();}}
}
