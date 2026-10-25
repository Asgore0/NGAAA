package cn.ngapure.galleryprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import cn.ngapure.app.ImageGalleryState;
import cn.ngapure.app.NativeImageGalleryView;
import cn.ngapure.app.NativeImages;
import cn.ngapure.app.NativePictureView;
import cn.ngapure.app.TopicPreviewImages;
import cn.ngapure.app.TopicPreviewView;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Exact product classes; independent no-INTERNET UID, explicit synthetic image connections only. */
@SuppressWarnings("deprecation")
public final class ImageGalleryProbe extends Instrumentation {
    private static final String PACKAGE="cn.ngapure.galleryprobe", PREFIX="https://img.nga.cn/attachments/mon_202610/23/gallery_";
    private static final String A=PREFIX+"a.png",B=PREFIX+"b.png",C=PREFIX+"c.png",D=PREFIX+"d.png",SLOW=PREFIX+"slow.png";
    private static final Map<String,byte[]> PIXELS=new HashMap<>();
    private static final AtomicInteger TRANSPORTS=new AtomicInteger(),UNEXPECTED=new AtomicInteger(),DISCONNECTS=new AtomicInteger(),SLOW_READS=new AtomicInteger();
    private static volatile boolean releaseSlow;
    private final JSONObject report=new JSONObject();
    private FixtureActivity activity;
    private NativeImages images;
    private NativeImageGalleryView gallery;
    private TopicPreviewImages previews;
    private TopicPreviewView preview;
    private final AtomicBoolean current=new AtomicBoolean(true);
    private final ArrayList<ImageGalleryState> changes=new ArrayList<>();
    private int assertions;
    private final AtomicInteger metadataReads=new AtomicInteger();
    private String stage="guard";
    private boolean authorized;
    public static final class FixtureActivity extends Activity {
        FrameLayout root;
        @Override public void onCreate(Bundle state){super.onCreate(state);root=new FrameLayout(this);root.setBackgroundColor(0xfff4f6fa);setContentView(root);}
    }
    @Override public Activity newActivity(ClassLoader loader,String name,Intent intent)throws InstantiationException,IllegalAccessException,ClassNotFoundException{Activity item=super.newActivity(loader,name,intent);if(item instanceof FixtureActivity){Configuration local=new Configuration();local.densityDpi=160;local.fontScale=1.3f;item.applyOverrideConfiguration(local);}return item;}
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticGalleryOnly"));start();}
    private <T>T main(Callable<T> work)throws Exception{FutureTask<T> task=new FutureTask<>(work);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private interface Condition{boolean ready()throws Exception;}
    private void await(String label,Condition value)throws Exception{for(int i=0;i<200;i++){if(value.ready()){waitForIdleSync();return;}SystemClock.sleep(25);}throw new IllegalStateException(label);}
    private void check(String name,boolean value)throws Exception{report.put(name,value);assertions++;if(!value)throw new AssertionError(name);}
    private static Object field(Object object,String name)throws Exception{Field member=object.getClass().getDeclaredField(name);member.setAccessible(true);return member.get(object);}
    private void transport()throws Exception{
        int[] colors={0xff4d9cf1,0xffed6c86,0xff86ae62,0xffc29be8,0xffeead61};String[] urls={A,B,C,D,SLOW};
        for(int i=0;i<urls.length;i++){Bitmap image=Bitmap.createBitmap(80,100,Bitmap.Config.ARGB_8888);image.eraseColor(colors[i]);ByteArrayOutputStream bytes=new ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.PNG,100,bytes);PIXELS.put(urls[i],bytes.toByteArray());image.recycle();}
        URL.setURLStreamHandlerFactory(protocol->("http".equals(protocol)||"https".equals(protocol))?new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{
            TRANSPORTS.incrementAndGet();byte[] bytes=PIXELS.get(url.toExternalForm());if(bytes==null){UNEXPECTED.incrementAndGet();throw new IOException("UNEXPECTED_SYNTHETIC_URL");}
            return new HttpURLConnection(url){volatile boolean stopped;@Override public void connect(){connected=true;}@Override public boolean usingProxy(){return false;}@Override public void disconnect(){if(!stopped){stopped=true;DISCONNECTS.incrementAndGet();}}@Override public int getResponseCode(){return HTTP_OK;}@Override public long getContentLengthLong(){return bytes.length;}@Override public InputStream getInputStream()throws IOException{if(url.toExternalForm().equals(SLOW))SLOW_READS.incrementAndGet();if(url.toExternalForm().equals(SLOW))while(!releaseSlow&&!stopped){try{Thread.sleep(15);}catch(InterruptedException cancelled){throw new IOException("SYNTHETIC_CANCELLED",cancelled);}}if(stopped)throw new IOException("SYNTHETIC_DISCONNECTED");return new ByteArrayInputStream(bytes);}};
        }}:null);
    }
    private NativeImageGalleryView mount(ImageGalleryState state,int width)throws Exception{
        return main(()->{if(gallery!=null)gallery.close();activity.root.removeAllViews();NativeImageGalleryView view=new NativeImageGalleryView(activity,images,state,current::get,(value,ready)->changes.add(value),0xff182432,0xff6b7789,0xffe2e9f0);FrameLayout.LayoutParams box=new FrameLayout.LayoutParams(width,700,Gravity.TOP|Gravity.CENTER_HORIZONTAL);activity.root.addView(view,box);gallery=view;return view;});
    }
    private void ready()throws Exception{await("GALLERY_IMAGE_READY",()->main(()->gallery.ready()));}
    private int index()throws Exception{return main(()->gallery.snapshot().index);}
    private boolean stableGeometry()throws Exception{return ((View)field(gallery,"shown")).getTranslationX()==0f&&((Float)field(gallery,"offset"))==0f&&field(gallery,"settling")==null;}
    private String label(View root,String description){if(description.equals(root.getContentDescription()))return root instanceof TextView?((TextView)root).getText().toString():"";if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){String value=label(group.getChildAt(i),description);if(!value.isEmpty())return value;}}return "";}
    private void event(long down,long time,int action,float x,float y)throws Exception{main(()->{MotionEvent motion=MotionEvent.obtain(down,time,action,x,y,0);try{gallery.dispatchTouchEvent(motion);}finally{motion.recycle();}return null;});}
    private void swipe(float from,float to,int duration)throws Exception{
        long down=SystemClock.uptimeMillis();event(down,down,MotionEvent.ACTION_DOWN,from,340);
        for(int i=1;i<=8;i++){SystemClock.sleep(duration/8);event(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,from+(to-from)*i/8,340);}
        event(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,to,340);
    }
    private void previewEntry()throws Exception{
        stage="previewEntry";String scope=String.join("",Collections.nCopies(64,"a"));
        previews=new TopicPreviewImages((s,t)->{metadataReads.incrementAndGet();return Arrays.asList(A,B,C,D);},work->activity.runOnUiThread(work));
        AtomicReference<ImageGalleryState> opened=new AtomicReference<>();AtomicInteger oldUrlCalls=new AtomicInteger(),parentClicks=new AtomicInteger();
        main(()->{activity.root.removeAllViews();android.widget.LinearLayout row=new android.widget.LinearLayout(activity);row.setOrientation(android.widget.LinearLayout.VERTICAL);row.setOnClickListener(v->parentClicks.incrementAndGet());TextView title=new TextView(activity);title.setText("合成首帖图片");title.setTextSize(16);title.setGravity(Gravity.CENTER_VERTICAL);row.addView(title,new android.widget.LinearLayout.LayoutParams(-1,72));preview=new TopicPreviewView(activity,row,scope,"123","合成首帖图片",images,previews,current::get,url->oldUrlCalls.incrementAndGet(),0xff6b7789,Color.WHITE,0xffe2e9f0,opened::set);row.addView(preview,new android.widget.LinearLayout.LayoutParams(-1,-2));activity.root.addView(row,new FrameLayout.LayoutParams(400,-2));return null;});
        // A real topic always has a visible title before its initially GONE preview.
        // A WRAP_CONTENT row containing only that GONE child measures to height zero
        // and correctly cannot trigger viewport-gated production discovery.
        await("PREVIEW_OWN_ROW_VISIBLE",()->main(()->{View owner=(View)preview.getParent();return owner.getHeight()>=72&&owner.getGlobalVisibleRect(new android.graphics.Rect());}));
        await("PREVIEW_THREE_IMAGES",()->main(()->preview.loadedImageCount()==3));
        main(()->{ViewGroup tiles=(ViewGroup)field(preview,"gallery");tiles.getChildAt(2).performClick();return null;});
        check("thirdThumbnailOpensAllFourOriginalImagesAtThirdIndex",opened.get()!=null&&opened.get().urls.equals(Arrays.asList(A,B,C,D))&&opened.get().index==2&&opened.get().sourceTotal==4);
        check("previewStillDownloadsAtMostThreeThumbnails",main(()->preview.imageCount()==3)&&oldUrlCalls.get()==0&&parentClicks.get()==0);
        main(()->{preview.close();return null;});previews.close();
    }
    private void paging()throws Exception{
        stage="paging";mount(ImageGalleryState.create(Arrays.asList(A,B,C,D),2,4,false),400);ready();
        check("opensClickedOriginalRatherThanFirstImage",index()==2&&main(()->gallery.currentUrl().equals(C)));
        check("counterShowsCurrentOrdinalAndWholeOriginalTotal",main(()->"3 / 4".equals(((TextView)field(gallery,"counter")).getText().toString())));
        swipe(340,50,180);await("SWIPE_NEXT",()->index()==3);ready();
        check("actualFitHorizontalSwipeAdvancesToFourthOriginal",index()==3&&main(()->gallery.currentUrl().equals(D)));
        swipe(340,50,150);SystemClock.sleep(350);check("lastImageRubberBandNeverWrapsOrChangesIndex",index()==3&&main(()->gallery.getWidth()==400&&stableGeometry()));
        swipe(50,340,180);await("SWIPE_PREVIOUS",()->index()==2);ready();check("reverseSwipeReturnsPreviousOriginal",index()==2&&main(()->gallery.currentUrl().equals(C)));
        main(()->{gallery.previous();return null;});ready();check("nativePreviousControlUsesSameGalleryState",index()==1&&main(()->gallery.currentUrl().equals(B)));
        main(()->{gallery.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null);return null;});ready();check("galleryAccessibilityNextUsesSameOriginalOrder",index()==2);
    }
    private void zoomAndReversal()throws Exception{
        stage="zoomAndReversal";main(()->{gallery.currentPicture().restoreViewport(new float[]{3,.5f,.5f});return null;});
        swipe(330,60,160);SystemClock.sleep(320);
        check("zoomedSingleFingerPansImageWithoutTurningPage",index()==2&&main(()->gallery.currentPicture().viewportState()[0]>2.9f));
        main(()->{gallery.currentPicture().restoreViewport(new float[]{1,.5f,.5f});return null;});
        long down=SystemClock.uptimeMillis();event(down,down,MotionEvent.ACTION_DOWN,340,340);event(down,down+35,MotionEvent.ACTION_MOVE,180,340);event(down,down+60,MotionEvent.ACTION_UP,160,340);SystemClock.sleep(30);
        long reverse=SystemClock.uptimeMillis();event(reverse,reverse,MotionEvent.ACTION_DOWN,180,340);event(reverse,reverse+30,MotionEvent.ACTION_MOVE,330,340);event(reverse,reverse+60,MotionEvent.ACTION_UP,350,340);SystemClock.sleep(450);ready();
        check("fastReverseDuringReleaseAlwaysKeepsValidOriginalIdentity",index()>=0&&index()<4&&main(()->gallery.currentUrl().equals(gallery.snapshot().urls.get(gallery.snapshot().index))&&stableGeometry()));
        main(()->{gallery.previous();return null;});ready();
        int stable=index();down=SystemClock.uptimeMillis();event(down,down,MotionEvent.ACTION_DOWN,340,340);event(down,down+40,MotionEvent.ACTION_MOVE,210,340);event(down,down+70,MotionEvent.ACTION_CANCEL,200,340);SystemClock.sleep(350);
        check("cancelledHorizontalGestureReturnsWithoutChangingPage",index()==stable&&main(()->stableGeometry()));
        down=SystemClock.uptimeMillis();event(down,down,MotionEvent.ACTION_DOWN,340,340);event(down,down+35,MotionEvent.ACTION_MOVE,180,340);event(down,down+60,MotionEvent.ACTION_UP,160,340);SystemClock.sleep(25);long tap=SystemClock.uptimeMillis();event(tap,tap,MotionEvent.ACTION_DOWN,200,340);event(tap,tap+50,MotionEvent.ACTION_UP,200,340);SystemClock.sleep(400);
        check("tapDuringSettleCannotLeaveGalleryPartlyTranslated",main(()->stableGeometry()));
    }
    private void restoredAndLimited()throws Exception{
        stage="restoredAndLimited";main(()->{gallery.next();gallery.currentPicture().restoreViewport(new float[]{2.5f,.35f,.6f});return null;});ready();
        main(()->{gallery.currentPicture().restoreViewport(new float[]{2.5f,.35f,.6f});return null;});ImageGalleryState snapshot=main(()->gallery.snapshot());int selected=snapshot.index;
        mount(ImageGalleryState.decode(snapshot.encode()),760);ready();
        check("valueOnlyRecreationRetainsCurrentImageAndZoom",index()==selected&&main(()->Math.abs(gallery.currentPicture().viewportState()[0]-2.5f)<.01f));
        main(()->{ViewGroup.LayoutParams layout=gallery.getLayoutParams();layout.width=220;gallery.setLayoutParams(layout);return null;});await("NARROW_LAYOUT",()->main(()->gallery.getWidth()==220));
        check("narrowLargeTextCounterAndControlsRemainInsideActualBounds",main(()->{ViewGroup nav=(ViewGroup)((View)field(gallery,"counter")).getParent();return nav.getWidth()<=220&&nav.getChildAt(0).getWidth()>=48&&nav.getChildAt(2).getWidth()>=48;}));
        check("viewportSurvivesActualWideToNarrowMeasuredReflow",main(()->Math.abs(gallery.currentPicture().viewportState()[0]-2.5f)<.01f));
        mount(ImageGalleryState.create(Arrays.asList(A,B,C,D),0,90,true),400);ready();
        check("boundedGalleryExplicitlyExplainsWithheldImagesAndActualSourceTotal",main(()->((TextView)field(gallery,"counter")).getText().toString().equals("1 / 4")&&((TextView)field(gallery,"limit")).getText().toString().contains("90")&&((TextView)field(gallery,"limit")).getVisibility()==View.VISIBLE));
    }
    private void cancellation()throws Exception{
        stage="cancellation";releaseSlow=false;int disconnected=DISCONNECTS.get();mount(ImageGalleryState.create(Arrays.asList(A,SLOW,C),0,3,false),400);ready();
        main(()->{gallery.next();return null;});await("SLOW_REQUEST_STARTED",()->SLOW_READS.get()>0&&main(()->gallery.currentUrl().equals(SLOW)&&!gallery.ready()));
        main(()->{gallery.next();return null;});ready();releaseSlow=true;SystemClock.sleep(180);waitForIdleSync();
        check("cancelledPreviousPageCompletionCannotReplaceCurrentImage",index()==2&&main(()->gallery.currentUrl().equals(C)&&gallery.ready()));
        check("leavingImageCancelsItsAnonymousMediaHandle",DISCONNECTS.get()>disconnected);
        main(()->{gallery.previous();current.set(false);return null;});releaseSlow=true;SystemClock.sleep(100);waitForIdleSync();
        int before=changes.size();main(()->{gallery.next();return null;});check("retiredAccountGenerationCannotNavigateGalleryOrNotifyHost",changes.size()==before&&main(()->gallery.currentUrl().equals(SLOW)));
        NativePictureView picture=main(()->gallery.currentPicture());main(()->{gallery.close();return null;});
        check("closeDropsCurrentImageDrawableAndRequest",main(()->picture.getDrawable()==null&&!gallery.ready()));
    }
    private static String safe(Throwable failure){Throwable cause=failure;for(int i=0;i<6&&cause.getCause()!=null;i++)cause=cause.getCause();String message=cause.getMessage();return message!=null&&message.matches("[A-Z0-9_]{1,100}")?message:cause.getClass().getSimpleName();}
    @Override public void onStart(){try{
        if(!authorized||!PACKAGE.equals(getTargetContext().getPackageName())||getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("OWN_UID_NO_INTERNET_SYNTHETIC_GALLERY_REQUIRED");
        transport();activity=(FixtureActivity)startActivitySync(new Intent().setClassName(PACKAGE,FixtureActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();await("WINDOW_FOCUS_REQUIRED",()->main(()->activity.hasWindowFocus()));images=new NativeImages();
        previewEntry();paging();zoomAndReversal();restoredAndLimited();cancellation();check("everyTransportHadExplicitSyntheticImagePlan",UNEXPECTED.get()==0);report.put("completed",true).put("passed",true).put("assertions",assertions);
    }catch(Exception|Error failure){try{report.put("completed",false).put("passed",false).put("failureStage",stage).put("failureType",failure.getClass().getSimpleName()).put("failureMessage",safe(failure)).put("assertions",assertions);if(failure instanceof AssertionError)report.put("failedAssertion",failure.getMessage());if(preview!=null)report.put("previewFixtureState",main(()->{View owner=(View)preview.getParent();JSONObject values=new JSONObject().put("metadataReads",metadataReads.get()).put("viewAttached",preview.isAttachedToWindow()).put("windowFocus",preview.hasWindowFocus()).put("previewVisibility",preview.getVisibility()).put("loadedImages",preview.loadedImageCount()).put("known",field(preview,"known")).put("scheduled",field(preview,"scheduled"));if(owner!=null)values.put("rowWidth",owner.getWidth()).put("rowHeight",owner.getHeight()).put("rowShown",owner.isShown()).put("rowVisibleRect",owner.getGlobalVisibleRect(new android.graphics.Rect()));return values;}));}catch(Exception ignored){}
    }finally{releaseSlow=true;try{if(activity!=null)main(()->{if(gallery!=null)gallery.close();if(preview!=null)preview.close();activity.finish();return null;});if(previews!=null)previews.close();if(images!=null)images.close();report.put("ownUidOnly",true).put("internetPermission",false).put("realNetworkRequests",0).put("syntheticMediaConnectionAttempts",TRANSPORTS.get()).put("unexpectedImageUrls",UNEXPECTED.get()).put("realAccountAccessed",false).put("realClientStarted",false).put("realCommunityWrites",0).put("productionClassesRewritten",false).put("globalDisplaySettingsChanged",false).put("localFontScale",1.3).put("localDensityDpi",160).put("measuredWidthsDp",new org.json.JSONArray(Arrays.asList(400,760,220))).put("physicalFoldPoseTested",false).put("privateInputsExported",false);}catch(Exception ignored){}Bundle result=new Bundle();result.putString("safe_image_gallery_report",report.toString());finish(Activity.RESULT_OK,result);}}
}
