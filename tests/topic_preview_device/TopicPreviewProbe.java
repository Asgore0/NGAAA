package cn.ngapure.previewprobe;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import cn.ngapure.app.NativeImages;
import cn.ngapure.app.TopicPreviewImages;
import cn.ngapure.app.TopicPreviewView;
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
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.json.JSONArray;

/** Final, unmodified product classes in an independent no-INTERNET Android UID. */
@SuppressWarnings("deprecation")
public final class TopicPreviewProbe extends Instrumentation {
    private static final String PACKAGE="cn.ngapure.previewprobe",SCOPE=repeat('a',64);
    private static final String A="https://img.nga.cn/attachments/mon_202610/23/fixture_a.png",B="https://img.nga.cn/attachments/mon_202610/23/fixture_b.png",C="https://img.nga.cn/attachments/mon_202610/23/fixture_c.png",D="https://img.nga.cn/attachments/mon_202610/23/fixture_d.png";
    private static final Map<String,MediaPlan> MEDIA=Collections.synchronizedMap(new HashMap<String,MediaPlan>());
    private static final AtomicInteger TRANSPORT_CALLS=new AtomicInteger(),UNEXPECTED_URLS=new AtomicInteger();
    private static byte[] png;
    private final JSONObject report=new JSONObject();
    private final ArrayList<Fixture> fixtures=new ArrayList<>();
    private FixtureActivity activity;
    private int assertions;
    private boolean authorized;
    private String stage="guard";
    public static final class FixtureActivity extends Activity {
        FrameLayout root;
        @Override public void onCreate(Bundle state){super.onCreate(state);root=new FrameLayout(this);root.setBackgroundColor(0xfff4f6fa);setContentView(root);}
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticPreviewOnly"));start();}
    @Override public Activity newActivity(ClassLoader loader,String name,Intent intent)throws InstantiationException,IllegalAccessException,ClassNotFoundException{
        Activity value=super.newActivity(loader,name,intent);if(value instanceof FixtureActivity){Configuration local=new Configuration();local.densityDpi=160;local.fontScale=1.5f;value.applyOverrideConfiguration(local);}return value;
    }
    private static String repeat(char c,int n){char[] value=new char[n];Arrays.fill(value,c);return new String(value);}
    private <T>T main(Callable<T> work)throws Exception{FutureTask<T> task=new FutureTask<>(work);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private interface Condition { boolean ready()throws Exception; }
    private void await(String label,Condition ready)throws Exception{for(int i=0;i<160;i++){if(ready.ready()){waitForIdleSync();return;}SystemClock.sleep(25);}throw new IllegalStateException(label);}
    private void awaitMetadataTtl(String label,Condition ready)throws Exception{for(int i=0;i<360;i++){if(ready.ready()){waitForIdleSync();return;}SystemClock.sleep(100);}throw new IllegalStateException(label);}
    private void check(String label,boolean value)throws Exception{report.put(label,value);assertions++;if(!value)throw new AssertionError(label);}
    private static Object field(Object owner,String name)throws Exception{Field value=owner.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(owner);}
    @SuppressWarnings("unchecked") private static List<ImageView> pictures(TopicPreviewView view)throws Exception{return (List<ImageView>)field(view,"pictures");}
    private static final class Plan {
        final List<String> urls;final boolean held;volatile boolean fail;final AtomicInteger calls=new AtomicInteger(),interrupts=new AtomicInteger();final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
        Plan(List<String> urls,boolean held,boolean fail){this.urls=urls;this.held=held;this.fail=fail;}
        List<String> load()throws Exception{calls.incrementAndGet();started.countDown();try{if(held)release.await();if(fail)throw new IOException("SYNTHETIC_METADATA_FAILURE");return urls;}catch(InterruptedException cancelled){interrupts.incrementAndGet();throw cancelled;}finally{finished.countDown();}}
    }
    private static final class CallbackQueue implements Executor {
        final Handler main=new Handler(Looper.getMainLooper());final ArrayDeque<Runnable> pending=new ArrayDeque<>();boolean held;
        CallbackQueue(boolean held){this.held=held;}
        @Override public synchronized void execute(Runnable work){if(held)pending.add(work);else main.post(work);}
        synchronized int count(){return pending.size();}
        synchronized void release(){held=false;while(!pending.isEmpty())main.post(pending.remove());}
    }
    private static final class MediaPlan {
        final boolean held;int response=HttpURLConnection.HTTP_OK;final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);final AtomicInteger calls=new AtomicInteger(),disconnects=new AtomicInteger(),interrupts=new AtomicInteger();
        MediaPlan(boolean held){this.held=held;}
    }
    private static final class LocalConnection extends HttpURLConnection {
        final MediaPlan plan;volatile boolean disconnected;
        LocalConnection(URL url,MediaPlan plan){super(url);this.plan=plan;}
        @Override public void connect(){connected=true;}
        @Override public boolean usingProxy(){return false;}
        @Override public void disconnect(){if(!disconnected){disconnected=true;plan.disconnects.incrementAndGet();}}
        @Override public int getResponseCode(){return plan.response;}
        @Override public long getContentLengthLong(){return png.length;}
        @Override public InputStream getInputStream()throws IOException{plan.started.countDown();if(plan.held)try{while(!plan.release.await(25,TimeUnit.MILLISECONDS))if(disconnected)throw new IOException("SYNTHETIC_MEDIA_DISCONNECTED");}catch(InterruptedException cancelled){plan.interrupts.incrementAndGet();throw new IOException("SYNTHETIC_MEDIA_INTERRUPTED",cancelled);}if(disconnected)throw new IOException("SYNTHETIC_MEDIA_DISCONNECTED");return new ByteArrayInputStream(png);}
    }
    private void transport()throws Exception{
        Bitmap image=Bitmap.createBitmap(48,32,Bitmap.Config.ARGB_8888);image.eraseColor(0xff4d9cf1);ByteArrayOutputStream bytes=new ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.PNG,100,bytes);png=bytes.toByteArray();image.recycle();
        URL.setURLStreamHandlerFactory(protocol->("http".equals(protocol)||"https".equals(protocol))?new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{TRANSPORT_CALLS.incrementAndGet();MediaPlan plan=MEDIA.get(url.toExternalForm());if(plan==null){UNEXPECTED_URLS.incrementAndGet();throw new IOException("UNEXPECTED_SYNTHETIC_IMAGE_URL");}plan.calls.incrementAndGet();return new LocalConnection(url,plan);}}:null);
    }
    private final class Fixture implements AutoCloseable {
        final Plan plan;final CallbackQueue callbacks;final TopicPreviewImages metadata;final NativeImages images=new NativeImages();final AtomicBoolean current=new AtomicBoolean(true);final ArrayList<String> opened=new ArrayList<>();final AtomicInteger parentClicks=new AtomicInteger(),currentChecks=new AtomicInteger();
        final String tid;ScrollView scroll;LinearLayout body,row;TopicPreviewView view;
        Fixture(String tid,Plan plan,boolean heldCallbacks){this.tid=tid;this.plan=plan;callbacks=new CallbackQueue(heldCallbacks);metadata=new TopicPreviewImages((scope,id)->{if(!SCOPE.equals(scope)||!tid.equals(id))throw new IOException("UNEXPECTED_METADATA_IDENTITY");return plan.load();},callbacks);}
        void attach(int width,int topSpacer)throws Exception{main(()->{activity.root.removeAllViews();scroll=new ScrollView(activity);scroll.setFillViewport(false);scroll.setClipToPadding(true);body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);scroll.addView(body,new ScrollView.LayoutParams(-1,-2));if(topSpacer>0){View spacer=new View(activity);body.addView(spacer,new LinearLayout.LayoutParams(-1,topSpacer));}row=new LinearLayout(activity);row.setOrientation(LinearLayout.VERTICAL);row.setOnClickListener(v->parentClicks.incrementAndGet());TextView title=new TextView(activity);title.setText("合成楼主图片帖子");title.setTextColor(0xff182432);title.setTextSize(15);title.setGravity(Gravity.CENTER_VERTICAL);row.addView(title,new LinearLayout.LayoutParams(-1,90));view=new TopicPreviewView(activity,row,SCOPE,tid,"合成帖子",images,metadata,()->{currentChecks.incrementAndGet();return current.get();},opened::add,0xff6b7789,Color.WHITE,0xffe4eaf1);row.addView(view,new LinearLayout.LayoutParams(-1,-2));body.addView(row,new LinearLayout.LayoutParams(-1,-2));View below=new View(activity);body.addView(below,new LinearLayout.LayoutParams(-1,1600));FrameLayout.LayoutParams box=new FrameLayout.LayoutParams(width,520,Gravity.TOP|Gravity.CENTER_HORIZONTAL);activity.root.addView(scroll,box);return null;});waitForIdleSync();}
        void offscreen()throws Exception{main(()->{scroll.scrollTo(0,1200);return null;});waitForIdleSync();}
        void back()throws Exception{main(()->{scroll.scrollTo(0,0);return null;});waitForIdleSync();}
        @Override public void close(){try{main(()->{if(view!=null)view.close();return null;});}catch(Exception ignored){}metadata.close();images.close();plan.release.countDown();callbacks.release();}
    }
    private Fixture fixture(String tid,List<String> urls,boolean held,boolean failed,boolean queued)throws Exception{Fixture result=new Fixture(tid,new Plan(urls,held,failed),queued);fixtures.add(result);return result;}
    private void waitKnown(Fixture f)throws Exception{await("PREVIEW_RESULT_TIMEOUT",()->main(()->(Boolean)field(f.view,"known")));}
    private void waitPixels(Fixture f,int count)throws Exception{await("PREVIEW_PIXELS_TIMEOUT",()->main(()->{List<ImageView> items=pictures(f.view);if(items.size()!=count)return false;for(ImageView item:items)if(item.getDrawable()==null)return false;return true;}));}
    private boolean emptyPixels(Fixture f)throws Exception{return main(()->{for(ImageView item:pictures(f.view))if(item.getDrawable()!=null)return false;return true;});}
    private int mediaRequests(Fixture f)throws Exception{return main(()->{int count=0;for(Object item:(List<?>)field(f.view,"media"))if(item!=null)count++;return count;});}
    private static int galleryChildCount(TopicPreviewView view)throws Exception{Object gallery=field(view,"gallery");return gallery==null?0:((ViewGroup)gallery).getChildCount();}
    private void visibilityAndIdle()throws Exception{
        stage="visibilityAndIdle";Fixture hidden=fixture("81001",Collections.emptyList(),false,false,false);hidden.attach(320,1100);SystemClock.sleep(430);
        check("unresolvedPreviewDoesNotAllocateUnusedVisualChildren",main(()->hidden.view.getChildCount()==0));
        check("offscreenUnknownRowDoesNotRequestMetadata",hidden.plan.calls.get()==0);
        check("offscreenUnknownPreviewOccupiesZeroHeight",main(()->hidden.view.getVisibility()==View.GONE&&hidden.view.getMeasuredHeight()==0));hidden.close();
        Fixture idle=fixture("81002",Collections.emptyList(),false,false,false);idle.attach(320,0);for(int i=0;i<6;i++){SystemClock.sleep(75);final int y=i%2==0?3:0;main(()->{idle.scroll.scrollTo(0,y);return null;});}
        check("continuousVisibleScrollDefersFirstPostRead",idle.plan.calls.get()==0);waitKnown(idle);
        check("settledVisibleRowRequestsMetadataOnce",idle.plan.calls.get()==1);
        check("confirmedImageFreePreviewHasNoUnusedVisualChildren",main(()->idle.view.getChildCount()==0));
        check("imageFreeResultRemainsGoneWithZeroHeight",main(()->idle.view.getVisibility()==View.GONE&&idle.view.getMeasuredHeight()==0));idle.close();
        Fixture failed=fixture("81003",Collections.emptyList(),false,true,false);failed.attach(320,0);await("FAILED_METADATA_NOT_OBSERVED",()->main(()->(Integer)field(failed.view,"metadataFailures")==1));
        check("failedMetadataDoesNotLeaveEmptyPreviewSpace",main(()->failed.view.getVisibility()==View.GONE&&failed.view.getMeasuredHeight()==0));
        check("failedMetadataIsNotMarkedAsKnownImageFreePost",!main(()->(Boolean)field(failed.view,"known")));
        failed.close();check("closingMetadataFailureCancelsTtlRetry",!main(()->(Boolean)field(failed.view,"scheduled")));
    }
    private void dispatchScrolls(Fixture f)throws Exception{main(()->{android.view.ViewTreeObserver.OnScrollChangedListener listener=(android.view.ViewTreeObserver.OnScrollChangedListener)field(f.view,"scrollListener");for(int i=0;i<120;i++)listener.onScrollChanged();return null;});}
    private void completedRowsHaveNoDiscoveryWork()throws Exception{
        stage="completedRowsHaveNoDiscoveryWork";
        Fixture empty=fixture("81020",Collections.emptyList(),false,false,false);empty.attach(320,0);waitKnown(empty);SystemClock.sleep(350);waitForIdleSync();
        int checks=empty.currentChecks.get();dispatchScrolls(empty);check("knownEmptyRowsDoNotQueueDiscoveryDuringScroll",!main(()->(Boolean)field(empty.view,"scheduled")));
        SystemClock.sleep(380);waitForIdleSync();check("knownEmptyRowsDoNotRevalidateAfterScrollSettles",empty.currentChecks.get()==checks&&empty.plan.calls.get()==1);empty.close();
        for(String url:Arrays.asList(A,B,C))MEDIA.put(url,new MediaPlan(false));Fixture ready=fixture("81021",Arrays.asList(A,B,C),false,false,false);ready.attach(320,0);waitPixels(ready,3);SystemClock.sleep(350);waitForIdleSync();
        checks=ready.currentChecks.get();int transports=TRANSPORT_CALLS.get();dispatchScrolls(ready);check("loadedRowsDoNotQueueDiscoveryDuringScroll",!main(()->(Boolean)field(ready.view,"scheduled")));
        SystemClock.sleep(380);waitForIdleSync();check("loadedRowsDoNotRevalidateOrFetchAfterScrollSettles",ready.currentChecks.get()==checks&&TRANSPORT_CALLS.get()==transports);
        ready.offscreen();long retiredAt=SystemClock.uptimeMillis();
        report.put("loadedRowPixelsImmediatelyAfterScroll",main(()->ready.view.loadedImageCount()));
        // waitForIdleSync can precede traversal's ViewTreeObserver scroll dispatch.
        // Observe the real cancellation; never invoke pause or the listener to force it.
        await("COMPLETED_ROW_OFFSCREEN_PIXELS_NOT_RELEASED",()->emptyPixels(ready));
        report.put("loadedRowOffscreenRetirementWaitMillis",SystemClock.uptimeMillis()-retiredAt);
        check("demandAwareSchedulingStillReleasesOffscreenPixels",emptyPixels(ready));ready.back();waitPixels(ready,3);ready.close();
    }
    private List<NativeImages.Request> occupyMedia(Fixture f,String suffix)throws Exception{
        String blocker="https://img.nga.cn/attachments/mon_202610/23/busy_"+suffix+".png";MEDIA.put(blocker,new MediaPlan(true));
        List<NativeImages.Request> pending=new ArrayList<>();main(()->{for(int i=0;i<32;i++)pending.add(f.images.load(blocker,false,new NativeImages.Callback(){public void loaded(Bitmap ignored){}public void failed(String ignored){}}));return null;});
        await("MEDIA_CAPACITY_NOT_HELD",()->MEDIA.get(blocker).started.getCount()==0);return pending;
    }
    private void releaseMedia(List<NativeImages.Request> pending)throws Exception{main(()->{for(NativeImages.Request request:pending)request.cancel();return null;});}
    private void waitThumbnailState(Fixture f)throws Exception{await("THUMBNAIL_STATE_NOT_CREATED",()->main(()->
        activity.hasWindowFocus()&&(Boolean)field(f.view,"known")&&pictures(f.view).size()==1
        &&((List<?>)field(f.view,"mediaBusyFailures")).size()==1&&((List<?>)field(f.view,"mediaFailed")).size()==1));}
    private int busyFailures(Fixture f)throws Exception{return main(()->{List<?> values=(List<?>)field(f.view,"mediaBusyFailures");return values.isEmpty()?-1:(Integer)values.get(0);});}
    private boolean thumbnailFailed(Fixture f)throws Exception{return main(()->{List<?> values=(List<?>)field(f.view,"mediaFailed");return !values.isEmpty()&&(Boolean)values.get(0);});}
    private void boundedBusyRecovery()throws Exception{
        stage="boundedBusyRecovery";String url="https://img.nga.cn/attachments/mon_202610/23/busy_target.png";MEDIA.put(url,new MediaPlan(false));Fixture recover=fixture("81022",Arrays.asList(url),false,false,false);
        List<NativeImages.Request> held=occupyMedia(recover,"recovery");try{recover.attach(320,0);waitThumbnailState(recover);await("TEMPORARY_BUSY_NOT_OBSERVED",()->busyFailures(recover)>0);
            check("temporaryBusyDoesNotPermanentlyFailThumbnail",!thumbnailFailed(recover));releaseMedia(held);waitPixels(recover,1);
            check("thumbnailRetriesAfterActualLoaderCapacityReturns",MEDIA.get(url).calls.get()==1&&main(()->recover.view.loadedImageCount()==1));
        }finally{releaseMedia(held);recover.close();}
        stage="boundedBusyExhaustion";Fixture exhausted=fixture("81023",Arrays.asList(url),false,false,false);held=occupyMedia(exhausted,"exhaustion");try{exhausted.attach(320,0);waitThumbnailState(exhausted);await("BUSY_RETRY_BOUND_NOT_REACHED",()->thumbnailFailed(exhausted));
            check("persistentBusyStopsAfterThreeAutomaticRetries",busyFailures(exhausted)==3&&!main(()->(Boolean)field(exhausted.view,"scheduled")));
            int count=TRANSPORT_CALLS.get();dispatchScrolls(exhausted);SystemClock.sleep(380);waitForIdleSync();check("exhaustedBusyDoesNotRearmOnScroll",!main(()->(Boolean)field(exhausted.view,"scheduled"))&&TRANSPORT_CALLS.get()==count);
        }finally{releaseMedia(held);exhausted.close();}
        stage="busyRetryCancellation";Fixture away=fixture("81024",Arrays.asList(url),false,false,false);held=occupyMedia(away,"offscreen");try{away.attach(320,0);waitThumbnailState(away);await("CANCEL_BUSY_NOT_OBSERVED",()->busyFailures(away)>0);away.offscreen();
            await("OFFSCREEN_BUSY_RETRY_NOT_CANCELLED",()->!main(()->(Boolean)field(away.view,"scheduled"))&&mediaRequests(away)==0);
            check("offscreenCancelsQueuedBusyRetry",!main(()->(Boolean)field(away.view,"scheduled"))&&mediaRequests(away)==0);releaseMedia(held);SystemClock.sleep(450);waitForIdleSync();check("capacityRecoveryCannotReviveOffscreenThumbnail",emptyPixels(away));away.back();waitPixels(away,1);
        }finally{releaseMedia(held);away.close();}
        stage="realMediaFailureIsBounded";String broken="https://img.nga.cn/attachments/mon_202610/23/not_found.png";MediaPlan missing=new MediaPlan(false);missing.response=HttpURLConnection.HTTP_NOT_FOUND;MEDIA.put(broken,missing);Fixture failure=fixture("81025",Arrays.asList(broken),false,false,false);failure.attach(320,0);waitThumbnailState(failure);await("REAL_MEDIA_FAILURE_NOT_OBSERVED",()->thumbnailFailed(failure));dispatchScrolls(failure);SystemClock.sleep(400);waitForIdleSync();
        check("realThumbnailFailureDoesNotAutomaticallyLoop",missing.calls.get()==1&&!main(()->(Boolean)field(failure.view,"scheduled")));failure.close();
    }
    private void metadataTtlRecoveryAndLimit()throws Exception{
        stage="metadataTtlRecoveryAndLimit";
        // Let the real service error cache expire; no private clock or retry deadline is changed.
        Fixture recover=fixture("81026",Arrays.asList(A),false,true,false);recover.attach(320,0);await("TTL_FIRST_FAILURE_REQUIRED",()->main(()->(Integer)field(recover.view,"metadataFailures")==1));
        recover.plan.fail=false;
        TopicPreviewImages.Result cachedFailure=recover.metadata.peek(SCOPE,recover.tid);
        check("metadataFailureWaitsForServiceTtl",main(()->(Long)field(recover.view,"metadataRetryAt")-SystemClock.uptimeMillis()>29000)&&cachedFailure!=null&&cachedFailure.failed);
        awaitMetadataTtl("TTL_RECOVERY_TIMEOUT",()->main(()->(Boolean)field(recover.view,"known")));waitPixels(recover,1);
        check("sameVisibleRowRecoversAfterActualThirtySecondErrorTtl",recover.plan.calls.get()==2&&main(()->recover.view.loadedImageCount()==1));recover.close();
        // A cached failure counts as the first attempt. The second failure has no retry timer.
        Fixture limited=fixture("81027",Collections.emptyList(),false,true,false);CountDownLatch cached=new CountDownLatch(1);limited.metadata.request(SCOPE,limited.tid,result->cached.countDown());
        if(!cached.await(4,TimeUnit.SECONDS))throw new IllegalStateException("CACHED_FAILURE_REQUIRED");limited.attach(320,0);
        await("CACHED_FAILURE_TTL_NOT_SCHEDULED",()->main(()->(Boolean)field(limited.view,"scheduled")));
        check("cachedFailureIsNotMarkedKnownAndArmsTtlWait",!main(()->(Boolean)field(limited.view,"known"))&&main(()->(Boolean)field(limited.view,"scheduled")));
        awaitMetadataTtl("TTL_RETRY_LIMIT_TIMEOUT",()->main(()->(Integer)field(limited.view,"metadataFailures")==2));dispatchScrolls(limited);SystemClock.sleep(380);waitForIdleSync();
        check("secondRealMetadataFailureStopsAutomaticRetries",limited.plan.calls.get()==2&&!main(()->(Boolean)field(limited.view,"known"))&&!main(()->(Boolean)field(limited.view,"scheduled")));limited.close();
        report.put("metadataRetryUsesActualErrorTtl",true).put("mediaBusyAutomaticRetryLimit",3);
    }
    private void galleryAndPixels()throws Exception{
        stage="galleryAndPixels";for(String url:Arrays.asList(A,B,C,D))MEDIA.put(url,new MediaPlan(false));Fixture f=fixture("81004",Arrays.asList(A,B,C,D),false,false,false);f.attach(320,0);waitPixels(f,3);
        check("actualNativePreviewLimitsFourSourceImagesToThree",main(()->((ViewGroup)field(f.view,"gallery")).getChildCount()==3&&pictures(f.view).size()==3));
        check("imageBadgeReportsWholeSourceTotalRatherThanThumbnailCount",main(()->"4 张".contentEquals(((TextView)field(f.view,"label")).getText())));
        check("onlyThreeAnonymousThumbnailTransportsRun",MEDIA.get(A).calls.get()==1&&MEDIA.get(B).calls.get()==1&&MEDIA.get(C).calls.get()==1&&MEDIA.get(D).calls.get()==0);
        main(()->{ViewGroup gallery=(ViewGroup)field(f.view,"gallery");for(int i=0;i<gallery.getChildCount();i++)gallery.getChildAt(i).performClick();return null;});
        check("eachThumbnailOpensItsOriginalAttachmentUrl",f.opened.equals(Arrays.asList(A,B,C)));
        check("thumbnailClickDoesNotOpenParentTopic",f.parentClicks.get()==0);
        stage="nativePopupFocus";int beforePopup=TRANSPORT_CALLS.get();Dialog popup=main(()->{Dialog dialog=new Dialog(activity);TextView content=new TextView(activity);content.setText("合成原生弹窗");content.setPadding(40,40,40,40);dialog.setContentView(content);dialog.show();return dialog;});
        try{await("NATIVE_POPUP_FOCUS_REQUIRED",()->main(()->!activity.hasWindowFocus()));check("nativePopupFocusLossRetainsAlreadyDisplayedPixels",main(()->f.view.loadedImageCount()==3));}
        finally{main(()->{popup.dismiss();return null;});}
        await("ACTIVITY_FOCUS_RETURN_REQUIRED",()->main(()->activity.hasWindowFocus()));SystemClock.sleep(350);waitForIdleSync();
        check("popupDismissDoesNotFlashOrRefetchLoadedThumbnails",main(()->f.view.loadedImageCount()==3)&&TRANSPORT_CALLS.get()==beforePopup);
        stage="focusReturnRechecksRetainedPixels";
        Dialog movedPopup=main(()->{Dialog dialog=new Dialog(activity);TextView content=new TextView(activity);content.setText("合成失焦滚动");dialog.setContentView(content);dialog.show();return dialog;});
        try{await("MOVED_POPUP_FOCUS_REQUIRED",()->main(()->!activity.hasWindowFocus()));f.offscreen();
            check("popupRetainsPixelsUntilUnderlyingViewportCanBeRechecked",main(()->f.view.loadedImageCount()==3));
        }finally{main(()->{movedPopup.dismiss();return null;});}
        await("MOVED_POPUP_FOCUS_RETURN_REQUIRED",()->main(()->activity.hasWindowFocus()));await("FOCUS_RETURN_OFFSCREEN_PIXELS_NOT_RELEASED",()->emptyPixels(f));
        check("focusReturnReleasesPixelsMovedOffscreenWhileUnfocused",emptyPixels(f)&&mediaRequests(f)==0&&!main(()->(Boolean)field(f.view,"scheduled")));
        f.back();waitPixels(f,3);check("returnAfterPopupOffscreenRetirementStillUsesDecodedCache",TRANSPORT_CALLS.get()==beforePopup);
        stage="galleryAndPixels";
        f.offscreen();await("OFFSCREEN_PIXELS_NOT_RELEASED",()->emptyPixels(f));
        check("actualScrollOffscreenReleasesImageViewBitmaps",emptyPixels(f));check("offscreenRowHasNoRetainedActiveMediaRequests",mediaRequests(f)==0);
        int transports=TRANSPORT_CALLS.get();f.back();waitPixels(f,3);
        check("visibleReturnUsesDecodedCacheWithoutNewHttp",TRANSPORT_CALLS.get()==transports&&f.plan.calls.get()==1);
        main(()->{f.current.set(false);((ViewGroup)field(f.view,"gallery")).getChildAt(0).performClick();return null;});
        check("retiredAccountCannotOpenOldThumbnail",f.opened.size()==3);
        main(()->{activity.root.removeAllViews();return null;});waitForIdleSync();
        check("actualDetachReleasesPreviouslyVisibleBitmapReferences",emptyPixels(f)&&mediaRequests(f)==0&&!main(()->f.view.isAttachedToWindow()));f.close();
        check("closingPreviewDropsAllViewBitmapReferences",emptyPixels(f)&&mediaRequests(f)==0);
        int closedTransports=TRANSPORT_CALLS.get();main(()->{f.current.set(true);activity.root.addView(f.scroll,new FrameLayout.LayoutParams(320,520,Gravity.TOP|Gravity.CENTER_HORIZONTAL));f.row.requestLayout();f.scroll.scrollTo(0,3);return null;});SystemClock.sleep(380);waitForIdleSync();
        check("closedPreviewDoesNotResumeAfterLayoutOrScroll",TRANSPORT_CALLS.get()==closedTransports&&emptyPixels(f));
    }
    private void metadataCancellation()throws Exception{
        stage="metadataCancellation";Fixture f=fixture("81005",Arrays.asList(A),true,false,false);f.attach(320,0);await("METADATA_NOT_STARTED",()->f.plan.started.getCount()==0);f.offscreen();await("METADATA_NOT_INTERRUPTED",()->f.plan.interrupts.get()>0);
        check("scrollingMetadataOffscreenCancelsActualWorker",f.plan.interrupts.get()>0);
        f.plan.release.countDown();SystemClock.sleep(120);waitForIdleSync();
        check("cancelledMetadataCannotPopulateOffscreenRow",main(()->f.view.getVisibility()==View.GONE&&galleryChildCount(f.view)==0));f.close();
        Fixture detach=fixture("81006",Arrays.asList(A),true,false,false);detach.attach(320,0);await("DETACH_METADATA_NOT_STARTED",()->detach.plan.started.getCount()==0);main(()->{activity.root.removeAllViews();return null;});await("DETACH_METADATA_NOT_INTERRUPTED",()->detach.plan.interrupts.get()>0);
        check("actualWindowDetachCancelsMetadataWorker",detach.plan.interrupts.get()>0&&!main(()->detach.view.isAttachedToWindow()));detach.close();
    }
    private void mediaCancellationAndLateCallbacks()throws Exception{
        stage="mediaCancellation";String slow="https://img.nga.cn/attachments/mon_202610/23/fixture_slow.png";MediaPlan plan=new MediaPlan(true);MEDIA.put(slow,plan);Fixture f=fixture("81007",Arrays.asList(slow),false,false,false);f.attach(320,0);await("HELD_MEDIA_NOT_STARTED",()->plan.started.getCount()==0);f.offscreen();await("HELD_MEDIA_NOT_CANCELLED",()->plan.disconnects.get()>0||plan.interrupts.get()>0);
        check("offscreenMediaDisconnectsOrInterruptsActualTransport",plan.disconnects.get()>0||plan.interrupts.get()>0);check("offscreenMediaRequestHandleReleased",mediaRequests(f)==0);plan.release.countDown();SystemClock.sleep(200);waitForIdleSync();
        check("lateCancelledMediaCannotRestorePixels",emptyPixels(f));f.close();
        stage="accountMediaGenerationGuard";String late="https://img.nga.cn/attachments/mon_202610/23/fixture_late.png";MediaPlan latePlan=new MediaPlan(true);MEDIA.put(late,latePlan);Fixture account=fixture("81008",Arrays.asList(late),false,false,false);account.attach(320,0);await("ACCOUNT_MEDIA_NOT_STARTED",()->latePlan.started.getCount()==0);main(()->{account.current.set(false);return null;});latePlan.release.countDown();await("ACCOUNT_MEDIA_NOT_COMPLETED",()->mediaRequests(account)==0);waitForIdleSync();
        check("lateImageCompletionHonorsRetiredAccountGeneration",emptyPixels(account));account.close();
        stage="queuedMetadataGenerationGuard";Fixture queued=fixture("81009",Arrays.asList(A),false,false,true);queued.attach(320,0);await("QUEUED_METADATA_REQUIRED",()->queued.callbacks.count()>0);main(()->{queued.current.set(false);return null;});queued.callbacks.release();waitForIdleSync();
        check("queuedMetadataHonorsRetiredAccountGeneration",main(()->queued.view.getVisibility()==View.GONE&&galleryChildCount(queued.view)==0));queued.close();
        Fixture closed=fixture("81010",Arrays.asList(A),false,false,true);closed.attach(320,0);await("CLOSED_CALLBACK_QUEUE_REQUIRED",()->closed.callbacks.count()>0);main(()->{closed.view.close();return null;});closed.callbacks.release();waitForIdleSync();
        check("closedViewCannotBeRevivedByQueuedMetadata",main(()->closed.view.getVisibility()==View.GONE&&galleryChildCount(closed.view)==0));closed.close();
    }
    private void narrowLayout()throws Exception{
        stage="narrowLargeText";Fixture f=fixture("81011",Arrays.asList(A,B,C),false,false,false);f.attach(220,0);waitPixels(f,3);
        check("fixtureUsesActualLocalLargeFontConfiguration",main(()->Math.abs(activity.getResources().getConfiguration().fontScale-1.5f)<.001f&&Math.abs(activity.getResources().getDisplayMetrics().density-1)<.001f));
        check("threeThumbnailsFitActual220DpMeasuredRow",main(()->{ViewGroup gallery=(ViewGroup)field(f.view,"gallery");if(f.view.getWidth()!=220||gallery.getWidth()>220)return false;for(int i=0;i<gallery.getChildCount();i++){View tile=gallery.getChildAt(i);if(tile.getLeft()<0||tile.getRight()>gallery.getWidth()||tile.getWidth()<48||tile.getHeight()<48)return false;}return true;}));
        check("previewBadgeLargeTextFitsInsideFirstImage",main(()->badgeFits(f)));
        check("previewCountDoesNotOccupyASeparateRow",main(()->{ViewGroup gallery=(ViewGroup)field(f.view,"gallery");TextView label=(TextView)field(f.view,"label");return f.view.getChildCount()==1&&f.view.getChildAt(0)==gallery&&label.getParent()==gallery.getChildAt(0)&&f.view.getHeight()==gallery.getHeight()+f.view.getPaddingTop()+f.view.getPaddingBottom();}));f.close();
        stage="incompleteLargeTotalBadge";ArrayList<String> many=new ArrayList<>(Arrays.asList(A,B,C));for(int i=3;i<4097;i++)many.add("https://img.nga.cn/attachments/mon_202610/23/fixture_total_"+i+".png");Fixture incomplete=fixture("81012",many,false,false,false);incomplete.attach(220,0);waitPixels(incomplete,3);
        check("incompleteImageTotalKeepsExactLowerBoundAndPlusMarker",main(()->"4096+ 张".contentEquals(((TextView)field(incomplete.view,"label")).getText())));
        report.put("largeLowerBoundBadgeMetrics",main(()->badgeMetrics(incomplete)));
        check("largeLowerBoundBadgeFitsNarrowLargeTextImage",main(()->badgeFits(incomplete)));
        check("thumbnailAccessibilityPreservesIndexTitleAndWholeCount",main(()->{View first=((ViewGroup)field(incomplete.view,"gallery")).getChildAt(0);String description=String.valueOf(first.getContentDescription());return description.contains("预览楼主第 1 张图片：合成帖子")&&description.contains("至少 4096 张");}));incomplete.close();
    }
    private static boolean badgeFits(Fixture fixture)throws Exception{
        TextView badge=(TextView)field(fixture.view,"label");ViewGroup gallery=(ViewGroup)field(fixture.view,"gallery");View tile=gallery.getChildAt(0);if(badge.getParent()!=tile||!badge.isShown()||badge.isClickable()||badge.getLeft()<0||badge.getRight()>tile.getWidth()||badge.getTop()<0||badge.getBottom()>tile.getHeight()||badge.getLayout()==null)return false;
        int lines=badge.getLayout().getLineCount(),width=badge.getWidth()-badge.getCompoundPaddingLeft()-badge.getCompoundPaddingRight();if(lines<1||lines>2||badge.getLayout().getLineBottom(lines-1)>badge.getHeight()-badge.getCompoundPaddingTop()-badge.getCompoundPaddingBottom())return false;for(int i=0;i<lines;i++)if(badge.getLayout().getLineWidth(i)>width||badge.getLayout().getEllipsisCount(i)>0)return false;return true;
    }
    private static JSONObject badgeMetrics(Fixture fixture)throws Exception{
        TextView badge=(TextView)field(fixture.view,"label");ViewGroup gallery=(ViewGroup)field(fixture.view,"gallery");View tile=gallery.getChildAt(0);JSONObject result=new JSONObject().put("labelWidth",badge.getWidth()).put("labelHeight",badge.getHeight()).put("left",badge.getLeft()).put("top",badge.getTop()).put("right",badge.getRight()).put("bottom",badge.getBottom()).put("tileWidth",tile.getWidth()).put("tileHeight",tile.getHeight()).put("textSize",badge.getTextSize()).put("text",badge.getText()).put("contentWidth",badge.getWidth()-badge.getCompoundPaddingLeft()-badge.getCompoundPaddingRight()).put("contentHeight",badge.getHeight()-badge.getCompoundPaddingTop()-badge.getCompoundPaddingBottom()).put("shown",badge.isShown()).put("clickable",badge.isClickable()).put("isFirstTileChild",badge.getParent()==tile);JSONArray lines=new JSONArray();if(badge.getLayout()!=null)for(int i=0;i<badge.getLayout().getLineCount();i++)lines.put(new JSONObject().put("width",badge.getLayout().getLineWidth(i)).put("bottom",badge.getLayout().getLineBottom(i)).put("ellipsis",badge.getLayout().getEllipsisCount(i)));return result.put("lines",lines);
    }
    private static String digest(byte[] bytes)throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder();for(byte value:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}
    private String saveIcon(Drawable icon,String name)throws Exception{Bitmap pixels=Bitmap.createBitmap(384,384,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(pixels);icon.setBounds(0,0,384,384);icon.draw(canvas);ByteArrayOutputStream bytes=new ByteArrayOutputStream();pixels.compress(Bitmap.CompressFormat.PNG,100,bytes);pixels.recycle();try(FileOutputStream file=new FileOutputStream(new File(activity.getFilesDir(),name))){file.write(bytes.toByteArray());}return digest(bytes.toByteArray());}
    private void publicIcon()throws Exception{
        stage="installedPublicIcon";ApplicationInfo app=activity.getPackageManager().getApplicationInfo("cn.ngapure.app",0);Drawable icon=app.loadIcon(activity.getPackageManager());
        check("installedProductPublicIconIsNativeAdaptive",icon instanceof AdaptiveIconDrawable);AdaptiveIconDrawable adaptive=(AdaptiveIconDrawable)icon;
        check("installedAdaptiveIconHasBackgroundAndForeground",adaptive.getBackground()!=null&&adaptive.getForeground()!=null);
        report.put("nativeIconPngSha256",main(()->saveIcon(adaptive,"icon-native.png")));
        if(android.os.Build.VERSION.SDK_INT>=33){Drawable mono=adaptive.getMonochrome();check("installedProductSuppliesApi33MonochromeLayer",mono!=null);report.put("nativeMonochromePngSha256",main(()->{mono.setTint(0xff82b5ff);return saveIcon(mono,"icon-monochrome.png");}));}
        report.put("iconSource","INSTALLED_PUBLIC_APPLICATION_INFO_ONLY").put("nativeIconRenderFile","files/icon-native.png").put("monochromeRenderIsLayerNotLauncherTheme",true);
    }
    private static String safeFailure(Throwable error){Throwable cause=error;for(int i=0;i<6&&cause.getCause()!=null;i++)cause=cause.getCause();String message=cause.getMessage();return message!=null&&message.matches("[A-Z0-9_]{1,100}")?message:cause.getClass().getSimpleName();}
    @Override public void onStart(){try{
        if(!authorized||!PACKAGE.equals(getTargetContext().getPackageName())||getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("OWN_UID_NO_INTERNET_SYNTHETIC_PREVIEW_REQUIRED");
        transport();activity=(FixtureActivity)startActivitySync(new Intent().setClassName(PACKAGE,FixtureActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();await("FIXTURE_WINDOW_FOCUS_REQUIRED",()->main(()->activity.hasWindowFocus()));
        visibilityAndIdle();completedRowsHaveNoDiscoveryWork();boundedBusyRecovery();metadataTtlRecoveryAndLimit();galleryAndPixels();metadataCancellation();mediaCancellationAndLateCallbacks();narrowLayout();publicIcon();check("allImageTransportUrlsWereExplicitSyntheticPlans",UNEXPECTED_URLS.get()==0);
        report.put("completed",true).put("passed",true).put("assertions",assertions);
    }catch(Exception|Error failure){try{report.put("completed",false).put("passed",false).put("failureStage",stage).put("failureType",failure.getClass().getSimpleName()).put("failureMessage",safeFailure(failure)).put("assertions",assertions);if(failure instanceof AssertionError)report.put("failedAssertion",failure.getMessage());}catch(Exception ignored){}
    }finally{for(Fixture f:fixtures)f.close();for(MediaPlan plan:MEDIA.values())plan.release.countDown();try{if(activity!=null)main(()->{activity.finish();return null;});report.put("ownUidOnly",true).put("internetPermission",false).put("realNetworkRequests",0).put("syntheticMediaConnectionAttempts",TRANSPORT_CALLS.get()).put("unexpectedImageUrls",UNEXPECTED_URLS.get()).put("realAccountAccessed",false).put("realClientStarted",false).put("realCommunityWrites",0).put("productionClassesRewritten",false).put("globalDisplaySettingsChanged",false).put("localFontScale",1.5).put("localDensityDpi",160).put("physicalFoldPoseTested",false).put("privateInputsExported",false);}catch(Exception ignored){}Bundle result=new Bundle();result.putString("safe_topic_preview_report",report.toString());finish(Activity.RESULT_OK,result);}}
}
