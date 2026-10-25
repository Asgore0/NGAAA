package cn.ngapure.imageprobe;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import cn.ngapure.app.NativeImages;
import cn.ngapure.app.NativeRichText;
import cn.ngapure.app.RequestCancellation;
import org.json.JSONArray;
import org.json.JSONObject;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadPoolExecutor;

/** Exact production loader and rich text, own UID/no Internet, real Android image codecs. */
public final class NativeImagesProbe extends Instrumentation {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final JSONObject report=new JSONObject(),checks=new JSONObject();
    private final JSONArray cases=new JSONArray();
    private final Map<String,Resource> resources=new ConcurrentHashMap<>();
    private final List<NativeImages> loaders=new ArrayList<>();
    private ProbeHostActivity activity;
    private byte[] gif,png;
    private int assertions,soakRounds,retentionSoakSeconds=1200;
    private boolean expectBoundedRetention,expectDecodeBackpressure,expectSourceDimensions,expectReadCancellation,expectBitmapExifRotation;
    private String mode="all";
    private static final class Resource {
        final byte[] bytes;final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),bodyEntered=new CountDownLatch(1),bodyRelease=new CountDownLatch(1);
        volatile boolean blockBody,ignoreHeaderInterrupt;volatile RequestCancellation.Token cancelAfterHeaders;
        final AtomicInteger connections=new AtomicInteger(),active=new AtomicInteger();volatile boolean anonymous=true,redirectsDisabled=true;
        Resource(byte[] bytes,boolean blocked){this.bytes=bytes;if(!blocked)release.countDown();}
    }
    private static final class Results implements NativeImages.DrawableCallback {
        final List<Drawable> values=Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger failures=new AtomicInteger(),busy=new AtomicInteger();volatile boolean mainOnly=true;
        @Override public void loaded(Drawable value){mainOnly&=Looper.myLooper()==Looper.getMainLooper();values.add(value);}
        @Override public void failed(String message){mainOnly&=Looper.myLooper()==Looper.getMainLooper();failures.incrementAndGet();if(message.equals("图片加载繁忙"))busy.incrementAndGet();}
        int count(){return values.size()+failures.get();}
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);if(args!=null){mode=args.getString("mode","all");try{retentionSoakSeconds=Math.max(60,Math.min(1800,Integer.parseInt(args.getString("soakSeconds","1200"))));}catch(NumberFormatException invalid){retentionSoakSeconds=1200;}expectBitmapExifRotation=args.getString("expectBitmapExifRotation","false").equals("true");expectReadCancellation=args.getString("expectReadCancellation","false").equals("true");expectSourceDimensions=args.getString("expectSourceDimensions","false").equals("true");expectDecodeBackpressure=args.getString("expectDecodeBackpressure","false").equals("true");expectBoundedRetention=args.getString("expectBoundedRetention","false").equals("true");try{soakRounds=Math.max(0,Math.min(200,Integer.parseInt(args.getString("soakRounds","0"))));}catch(NumberFormatException invalid){soakRounds=0;}}start();}
    @Override public void onStart(){
        boolean passed=false,clean=true;String failure="NONE";
        try {
            require(mode.equals("all")||mode.equals("soak")||mode.equals("parser")||mode.equals("retention")||mode.equals("retention_soak")||mode.equals("decode_backpressure")||mode.equals("image_layout")||mode.equals("image_exif")||mode.equals("original_read_cancel")||mode.equals("bitmap_exif"),"validProbeMode");
            require(getTargetContext().getPackageName().equals("cn.ngapure.imageprobe"),"targetsHelperPackage");
            require(getTargetContext().checkSelfPermission(Manifest.permission.INTERNET)==PackageManager.PERMISSION_DENIED,"noInternetPermission");
            try {require(getTargetContext().getApplicationInfo().uid!=getTargetContext().getPackageManager().getApplicationInfo("cn.ngapure.app",0).uid,"ownUidDifferentFromMain");}catch(PackageManager.NameNotFoundException absent){put(report,"mainClientInstalled",false);}
            gif=asset("synthetic-two-frame.gif");png=asset("synthetic-static.png");put(report,"binding",new JSONObject(new String(asset("binding.json"),java.nio.charset.StandardCharsets.UTF_8)));
            installTransport();
            activity=(ProbeHostActivity)startActivitySync(new Intent().setClassName("cn.ngapure.imageprobe","cn.ngapure.imageprobe.ProbeHostActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            require(activity!=null,"isolatedActivityStarted");
            if(mode.equals("all")){duplicateGif();staticBitmap();cancelSubscribers();closeQueued();boundedWork();richBusyRecovery();cryptReplacement();}
            if(soakRounds>0)soak();
            if(mode.equals("parser"))parserBenchmark();
            if(mode.equals("retention"))richRetention();
            if(mode.equals("retention_soak"))richRetentionSoak();
            if(mode.equals("decode_backpressure"))nativeDecodeBackpressure();
            if(mode.equals("image_layout"))nativeImageLayout();
            if(mode.equals("image_exif"))nativeExifGeometry();
            if(mode.equals("bitmap_exif"))nativeBitmapExifOrientations();
            if(mode.equals("original_read_cancel"))nativeOriginalReadCancellation();
            for(Resource r:resources.values()){require(r.anonymous,"anonymousTransport"+assertions);require(r.redirectsDisabled,"redirectsDisabled"+assertions);}
            passed=true;
        } catch(Exception|AssertionError problem){failure=problem.getMessage()==null?problem.getClass().getSimpleName():problem.getMessage();put(report,"failureClass",problem.getClass().getSimpleName());}
        finally {
            for(Resource r:resources.values()){r.release.countDown();r.bodyRelease.countDown();}
            try{onMain(()->{for(NativeImages n:loaders)n.close();if(activity!=null){activity.content.removeAllViews();activity.finish();}return null;});}catch(Exception problem){clean=false;}
            put(report,"passed",passed&&clean);put(report,"cleanupPassed",clean);put(report,"failure",failure);put(report,"checks",checks);put(report,"cases",cases);put(report,"assertionCount",assertions);
            put(report,"actualAndroidImageDecoder",mode.equals("all")||mode.equals("retention")||mode.equals("retention_soak")||mode.equals("decode_backpressure")||mode.equals("image_layout")||mode.equals("image_exif")||mode.equals("bitmap_exif")||(mode.equals("soak")&&soakRounds>0));put(report,"parserOnly",mode.equals("parser"));put(report,"syntheticMediaOnly",true);put(report,"mainAccountRead",false);put(report,"mainAppDataCopied",false);put(report,"realClientStarted",false);put(report,"realNetworkRequests",0);put(report,"communityWrites",0);put(report,"mode",mode);put(report,"requestedSoakRounds",soakRounds);
        }
        Bundle output=new Bundle();output.putString("safe_native_images_report",report.toString());finish(Activity.RESULT_OK,output);
    }
    private void installTransport(){
        URL.setURLStreamHandlerFactory(protocol->!protocol.equals("https")?null:new URLStreamHandler(){
            @Override protected URLConnection openConnection(URL url)throws IOException {
                Resource r=resources.get(url.toString());if(r==null)throw new IOException("Non-fixture image rejected");r.connections.incrementAndGet();
                return new HttpURLConnection(url){
                    private final AtomicBoolean connected=new AtomicBoolean();
                    @Override public int getResponseCode()throws IOException {
                        if(connected.compareAndSet(false,true))r.active.incrementAndGet();r.anonymous&=getRequestProperty("Cookie")==null&&getRequestProperty("Authorization")==null;r.redirectsDisabled&=!getInstanceFollowRedirects();r.entered.countDown();
                        boolean interrupted=false;while(true){try{if(!r.release.await(8,TimeUnit.SECONDS))throw new IOException("Fixture release timeout");break;}catch(InterruptedException cancelled){if(!r.ignoreHeaderInterrupt){Thread.currentThread().interrupt();throw new IOException("Cancelled fixture");}interrupted=true;}}if(interrupted)Thread.currentThread().interrupt();if(!connected.get())throw new IOException("Synthetic header socket disconnected");if(r.cancelAfterHeaders!=null)r.cancelAfterHeaders.cancel();return 200;
                    }
                    @Override public InputStream getInputStream(){
                        ByteArrayInputStream bytes=new ByteArrayInputStream(r.bytes);
                        if(!r.blockBody)return bytes;
                        return new InputStream(){@Override public int read()throws IOException{byte[] one=new byte[1];return read(one,0,1)==-1?-1:one[0]&255;}@Override public int read(byte[] target,int at,int length)throws IOException{r.bodyEntered.countDown();boolean interrupted=false;while(true){try{r.bodyRelease.await();break;}catch(InterruptedException ignored){interrupted=true;}}if(interrupted)Thread.currentThread().interrupt();if(!connected.get())throw new IOException("Synthetic body socket disconnected");return bytes.read(target,at,length);}};
                    }
                    @Override public long getContentLengthLong(){return r.bytes.length;}
                    @Override public void connect(){}@Override public void disconnect(){if(connected.compareAndSet(true,false))r.active.decrementAndGet();r.release.countDown();r.bodyRelease.countDown();}@Override public boolean usingProxy(){return false;}
                };
            }
        });
    }
    private NativeImages loader()throws Exception{return onMain(()->{NativeImages n=new NativeImages();
        try{n.getClass().getMethod("setMediaCacheDirectory",java.io.File.class).invoke(n,new java.io.File(activity==null?getTargetContext().getCacheDir():activity.getCacheDir(),"anonymous-media-"+loaders.size()+"-"+SystemClock.elapsedRealtimeNanos()));}catch(NoSuchMethodException baselineWithoutCache){ }
        loaders.add(n);return n;});}
    private String resource(String name,byte[] bytes,boolean blocked){String url="https://img.nga.cn/attachments/native-image-probe/"+name;resources.put(url,new Resource(bytes,blocked));return url;}
    private void duplicateGif()throws Exception {
        NativeImages n=loader();Results result=new Results();String url=resource("thirty.gif",gif,true);
        onMain(()->{for(int i=0;i<30;i++)n.loadDrawable(url,false,result);return null;});
        require(resources.get(url).entered.await(3,TimeUnit.SECONDS),"gifFetchStarted");require(n.state().contains("media=1 ")&&pending(n)==30,"thirtySubscribersOneJob");resources.get(url).release.countDown();await(()->result.count()==30,"thirtyGifDeliveries");
        require(result.failures.get()==0&&resources.get(url).connections.get()==1,"thirtyGifViewsOneFetchNoFailure");require(result.mainOnly,"gifCallbacksMainThread");require(new HashSet<>(result.values).size()==30,"thirtyIndependentDrawableInstances");
        onMain(()->{for(Drawable value:result.values){require(value instanceof AnimatedImageDrawable,"realAnimatedImageDrawable"+assertions);AnimatedImageDrawable animated=(AnimatedImageDrawable)value;animated.start();require(animated.isRunning(),"nativeAnimationStarts"+assertions);animated.stop();require(!animated.isRunning(),"nativeAnimationStops"+assertions);}Drawable first=result.values.get(0),second=result.values.get(1);first.setBounds(0,0,31,43);require(second.getBounds().width()!=31,"independentMutableDrawableBounds");return null;});
        await(()->pending(n)==0,"gifSubscribersReleased");n.close();stage("real_android_30_gif_one_fetch");
    }
    private void staticBitmap()throws Exception {
        NativeImages n=loader();Results r=new Results();String url=resource("static.png",png,true);onMain(()->{for(int i=0;i<30;i++)n.loadDrawable(url,false,r);return null;});resources.get(url).release.countDown();await(()->r.count()==30,"staticDeliveries");
        require(r.failures.get()==0&&resources.get(url).connections.get()==1,"staticOneFetchNoFailure");require(new HashSet<>(r.values).size()==30,"staticDrawableWrappersIndependent");Object bitmap=((BitmapDrawable)r.values.get(0)).getBitmap();for(Drawable d:r.values)require(d instanceof BitmapDrawable&&((BitmapDrawable)d).getBitmap()==bitmap,"staticBitmapShared"+assertions);n.close();stage("static_bitmap_shared_mutable_wrappers_independent");
    }
    private void cancelSubscribers()throws Exception {
        NativeImages n=loader();Results cancelled=new Results(),kept=new Results();String url=resource("cancel-one.gif",gif,true);
        NativeImages.Request request=n.loadDrawable(url,false,cancelled);n.loadDrawable(url,false,kept);require(resources.get(url).entered.await(3,TimeUnit.SECONDS),"cancelFetchStarted");request.cancel();resources.get(url).release.countDown();await(()->kept.count()==1,"otherSubscriberSurvives");require(cancelled.count()==0&&resources.get(url).connections.get()==1,"oneCancellationKeepsSibling");n.close();
        n=loader();Results last=new Results();url=resource("cancel-last.gif",gif,true);request=n.loadDrawable(url,false,last);require(resources.get(url).entered.await(3,TimeUnit.SECONDS),"lastFetchStarted");request.cancel();require(pending(n)==0&&n.state().contains("media=0 "),"lastCancellationDropsJobAndListener");resources.get(url).release.countDown();SystemClock.sleep(80);require(last.count()==0,"lastCancellationNoCallback");n.close();stage("independent_and_last_subscriber_cancellation");
    }
    private void closeQueued()throws Exception {
        NativeImages n=loader();Results r=new Results();String url=resource("queued-close.gif",gif,false);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        main.post(()->{entered.countDown();try{release.await(4,TimeUnit.SECONDS);}catch(InterruptedException cancelled){Thread.currentThread().interrupt();}});
        require(entered.await(2,TimeUnit.SECONDS),"mainQueueGateEntered");
        try {n.loadDrawable(url,false,r);await(()->n.state().contains("media=0 ")&&pending(n)==1,"decodedDeliveryQueued");n.close();}finally{release.countDown();}
        onMain(()->null);require(r.count()==0&&pending(n)==0,"closeSuppressesQueuedCallback");stage("close_before_main_callback");
    }
    private void boundedWork()throws Exception {
        NativeImages n=loader();Results r=new Results();for(int i=0;i<25;i++)n.loadDrawable(resource("unique-"+i+".gif",gif,true),false,r);final Results uniqueResult=r;await(()->uniqueResult.busy.get()==1,"uniqueCapacityBusy");require(n.state().contains("media=24 ")&&pending(n)==24,"uniqueJobsBounded24");n.close();
        n=loader();r=new Results();String a=resource("global-a.gif",gif,true),b=resource("global-b.gif",gif,true);for(int i=0;i<16;i++){n.loadDrawable(a,false,r);n.loadDrawable(b,false,r);}n.loadDrawable(a,false,r);final Results result=r;await(()->result.busy.get()==1,"subscriberCapacityBusy");require(pending(n)==32,"globalPendingSubscribersBounded32");n.close();stage("bounded_unique_and_pending_work");
    }
    /** Android/ART production original-byte GET with in-memory sockets that ignore interrupts. */
    private void nativeOriginalReadCancellation()throws Exception {
        JSONArray cases=new JSONArray();put(report,"nativeOriginalReadCancellation",cases);
        for(int phase=0;phase<4;phase++) {
            RequestCancellation.Token token=new RequestCancellation.Token();String url=resource("original-cancel-"+phase+".png",png,phase==1);Resource source=resources.get(url);if(phase==1)source.ignoreHeaderInterrupt=true;if(phase==0)token.cancel();if(phase==2)source.blockBody=true;if(phase==3)source.cancelAfterHeaders=token;
            AtomicInteger received=new AtomicInteger();AtomicReference<Throwable> failed=new AtomicReference<>();Thread worker=new Thread(()->{RequestCancellation.Scope scope=RequestCancellation.bind(token);try{received.set(NativeImages.bytes(url).length);}catch(Throwable error){failed.set(error);}finally{scope.close();}},"NativeOriginalReadFixture");worker.start();boolean stopped;long cancelTime=SystemClock.elapsedRealtime();
            try {
                if(phase==1||phase==2){require((phase==1?source.entered:source.bodyEntered).await(2,TimeUnit.SECONDS),"nativeOriginalBlockingPhaseEntered"+phase);cancelTime=SystemClock.elapsedRealtime();token.cancel();worker.join(250);stopped=!worker.isAlive()&&failed.get() instanceof IOException&&received.get()==0;}
                else {worker.join(1000);stopped=!worker.isAlive()&&failed.get() instanceof IOException&&received.get()==0&&(phase!=0||source.connections.get()==0);}
                require(stopped==expectReadCancellation,"nativeOriginalTokenObservedAtPhase"+phase);JSONObject item=new JSONObject();put(item,"phase",phase==0?"pre_cancelled":phase==1?"blocked_headers":phase==2?"blocked_body":"cancel_after_headers");put(item,"stoppedByToken",stopped);put(item,"workerStillAliveAt250ms",worker.isAlive());put(item,"transportConnections",source.connections.get());put(item,"receivedBytes",received.get());put(item,"failureClass",failed.get()==null?"NONE":failed.get().getClass().getSimpleName());put(item,"elapsedAfterCancellationMillis",SystemClock.elapsedRealtime()-cancelTime);cases.put(item);
            }finally{source.release.countDown();source.bodyRelease.countDown();worker.join(2000);require(!worker.isAlive(),"nativeOriginalFixtureWorkerCleaned"+phase);}
        }
        put(report,"expectedReadCancellation",expectReadCancellation);put(report,"syntheticSocketIgnoresThreadInterrupt",true);stage("actual_art_anonymous_original_read_token_cancellation");
    }
    private void nativeBitmapExifOrientations()throws Exception {
        byte[] base=asset("synthetic-exif-rotate6.jpg");int[][] corners={{0,1,2,3},{1,0,3,2},{3,2,1,0},{2,3,0,1},{0,2,1,3},{2,0,3,1},{3,1,2,0},{1,3,0,2}};JSONArray metrics=new JSONArray();put(report,"nativeBitmapExifOrientations",metrics);
        for(int orientation=2;orientation<=8;orientation++) {
            byte[] jpeg=jpegVariant(base,orientation);NativeImages n=loader();String url=resource("bitmap-exif-"+orientation+".jpg",jpeg,false);AtomicInteger completed=new AtomicInteger();final android.graphics.Bitmap[] decoded={null};n.load(url,false,new NativeImages.Callback(){@Override public void loaded(android.graphics.Bitmap value){decoded[0]=value;completed.set(1);}@Override public void failed(String message){completed.set(-1);}});await(()->completed.get()!=0,"nativeBitmapExifFinished"+orientation);require(completed.get()==1,"nativeBitmapExifSucceeded"+orientation);android.graphics.Bitmap actual=decoded[0];int[] observed=quadrants(actual);int[] expected=corners[orientation-1];int expectedWidth=orientation>=5?450:800,expectedHeight=orientation>=5?800:450;
            boolean transformed=actual.getWidth()==expectedWidth&&actual.getHeight()==expectedHeight&&java.util.Arrays.equals(observed,expected);if(expectBitmapExifRotation)require(transformed,"nativeBitmapExifOrientationCorrect"+orientation);else require(actual.getWidth()==800&&actual.getHeight()==450&&java.util.Arrays.equals(observed,corners[0]),"baselineBitmapFactoryIgnoresExif"+orientation);
            require((long)actual.getWidth()*actual.getHeight()<=800000,"nativeBitmapExifAreaBudget"+orientation);
            AtomicInteger siblingReady=new AtomicInteger();final android.graphics.Bitmap[] sibling={null};n.load(url,false,new NativeImages.Callback(){@Override public void loaded(android.graphics.Bitmap value){sibling[0]=value;siblingReady.set(1);}@Override public void failed(String message){siblingReady.set(-1);}});await(()->siblingReady.get()!=0,"nativeExifSharedBitmapCacheHit"+orientation);require(siblingReady.get()==1&&sibling[0]==actual&&!actual.isRecycled(),"nativeExifSharedDisplayedBitmapIsNotRecycled"+orientation);
            Results reference=new Results();n.loadDrawable(url,false,reference);await(()->reference.count()==1,"nativeExifReferenceDecoded"+orientation);require(reference.failures.get()==0&&reference.values.get(0) instanceof BitmapDrawable,"nativeExifReferenceStatic"+orientation);android.graphics.Bitmap nativeOriented=((BitmapDrawable)reference.values.get(0)).getBitmap();require(nativeOriented.getWidth()==expectedWidth&&nativeOriented.getHeight()==expectedHeight&&java.util.Arrays.equals(quadrants(nativeOriented),expected),"nativeImageDecoderReferenceCorrect"+orientation);
            n.trimMemory(40);require(!actual.isRecycled()&&java.util.Arrays.equals(quadrants(actual),observed),"nativeExifCacheTrimDoesNotRecycleDisplayedBitmap"+orientation);AtomicInteger redecodedReady=new AtomicInteger();final android.graphics.Bitmap[] redecoded={null};int beforeReload=resources.get(url).connections.get();n.load(url,false,new NativeImages.Callback(){@Override public void loaded(android.graphics.Bitmap value){redecoded[0]=value;redecodedReady.set(1);}@Override public void failed(String message){redecodedReady.set(-1);}});await(()->redecodedReady.get()!=0,"nativeExifLocalBitmapReDecoded"+orientation);require(redecodedReady.get()==1&&redecoded[0]!=actual&&!actual.isRecycled()&&java.util.Arrays.equals(quadrants(redecoded[0]),observed),"nativeExifReDecodeKeepsExistingDisplayedBitmapAlive"+orientation);require(resources.get(url).connections.get()==beforeReload,"nativeExifReDecodeUsesOriginalLocalBytes"+orientation);
            boolean originalBytesIdentical=java.util.Arrays.equals(jpeg,NativeImages.bytes(url));require(originalBytesIdentical,"nativeExifOriginalExportBytesUnchanged"+orientation);JSONObject item=new JSONObject();put(item,"exifOrientation",orientation);put(item,"bitmapWidth",actual.getWidth());put(item,"bitmapHeight",actual.getHeight());put(item,"quadrantsRedGreenBlueYellow",new JSONArray().put(observed[0]).put(observed[1]).put(observed[2]).put(observed[3]));put(item,"expectedQuadrants",new JSONArray().put(expected[0]).put(expected[1]).put(expected[2]).put(expected[3]));put(item,"matchesIntendedOrientation",transformed);put(item,"decodedPixelCount",(long)actual.getWidth()*actual.getHeight());put(item,"originalExportBytesIdentical",originalBytesIdentical);put(item,"nativeImageDecoderReferenceCorrect",true);
            if(expectBitmapExifRotation){int[] logical=(int[])n.getClass().getMethod("sourceDimensions",Drawable.class).invoke(n,new BitmapDrawable(null,actual));require(logical[0]==(orientation>=5?900:1600)&&logical[1]==(orientation>=5?1600:900),"nativeExifLogicalGeometryMatchesPixels"+orientation);put(item,"originalLogicalWidth",logical[0]);put(item,"originalLogicalHeight",logical[1]);}
            metrics.put(item);n.close();
        }
        put(report,"expectBitmapExifRotation",expectBitmapExifRotation);put(report,"jpegExifOrientationsChecked",7);stage("actual_bitmap_factory_exif_two_through_eight_and_original_export");
    }
    @SuppressWarnings("deprecation")
    private byte[] jpegVariant(byte[] source,int orientation)throws Exception {
        java.io.File file=java.io.File.createTempFile("synthetic-exif-",".jpg",getTargetContext().getCacheDir());
        try{try(java.io.FileOutputStream output=new java.io.FileOutputStream(file)){output.write(source);}android.media.ExifInterface exif=new android.media.ExifInterface(file.getAbsolutePath());exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION,Integer.toString(orientation));exif.saveAttributes();return java.nio.file.Files.readAllBytes(file.toPath());}finally{require(file.delete(),"temporaryExifFixtureRemoved"+orientation);}
    }
    private static int[] quadrants(android.graphics.Bitmap bitmap) {
        int[] result=new int[4];int at=0;for(int y:new int[]{bitmap.getHeight()/4,bitmap.getHeight()*3/4})for(int x:new int[]{bitmap.getWidth()/4,bitmap.getWidth()*3/4}){int color=bitmap.getPixel(x,y),r=android.graphics.Color.red(color),g=android.graphics.Color.green(color),b=android.graphics.Color.blue(color);result[at++]=r>g+80&&r>b+80?0:g>r+80&&g>b+80?1:b>r+80&&b>g+80?2:r>120&&g>120&&b<80?3:-1;}return result;
    }
    private void nativeExifGeometry()throws Exception {
        byte[] jpeg=asset("synthetic-exif-rotate6.jpg");android.graphics.BitmapFactory.Options raw=new android.graphics.BitmapFactory.Options();raw.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,raw);require(raw.outWidth==1600&&raw.outHeight==900,"exifFixtureRawHeaderLandscape");
        int[] infoSize={0,0};Drawable direct=android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(jpeg)),(decoder,info,source)->{infoSize[0]=info.getSize().getWidth();infoSize[1]=info.getSize().getHeight();decoder.setTargetSampleSize(2);decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);});require(direct instanceof BitmapDrawable,"exifDirectStaticDrawable");android.graphics.Bitmap directBitmap=((BitmapDrawable)direct).getBitmap();int corner=directBitmap.getPixel(directBitmap.getWidth()/4,directBitmap.getHeight()/4);require(direct.getIntrinsicWidth()==450&&direct.getIntrinsicHeight()==800,"imageDecoderActuallyRotatesExifSix");require(android.graphics.Color.blue(corner)>android.graphics.Color.red(corner)+80,"imageDecoderRotatedCornerMatchesExifSix");
        NativeImages n=loader();String url=resource("geometry-exif-six.jpg",jpeg,false);android.widget.ScrollView scroll=onMain(()->(android.widget.ScrollView)activity.content.getParent());NativeRichText rich=onMain(()->{activity.content.removeAllViews();scroll.scrollTo(0,0);NativeRichText value=new NativeRichText(activity,n,"[img]"+url+"[/img]",false,16,null);activity.content.addView(value,new ViewGroup.LayoutParams(-1,-2));return value;});await(()->onMain(()->images(rich).size()==1&&field(images(rich).get(0),"actual")!=null),"nativeExifInlineLoaded");waitForIdleSync();
        JSONObject geometry=onMain(()->{Object image=images(rich).get(0);Drawable actual=(Drawable)field(image,"actual");JSONObject value=new JSONObject();put(value,"rawHeaderWidth",raw.outWidth);put(value,"rawHeaderHeight",raw.outHeight);put(value,"imageDecoderInfoWidth",infoSize[0]);put(value,"imageDecoderInfoHeight",infoSize[1]);put(value,"imageDecoderOutputWidth",actual.getIntrinsicWidth());put(value,"imageDecoderOutputHeight",actual.getIntrinsicHeight());put(value,"actualNativeSpanWidth",((Drawable)image).getBounds().width());put(value,"actualNativeSpanHeight",((Drawable)image).getBounds().height());if(expectSourceDimensions)require(((Drawable)image).getBounds().width()==900&&((Drawable)image).getBounds().height()==1600,"logicalGeometryUsesOrientedExifDimensions");require(((BitmapDrawable)actual).getBitmap().getWidth()*((BitmapDrawable)actual).getBitmap().getHeight()<=800000,"exifDecodedPixelBudgetUnchanged");return value;});
        final android.graphics.Bitmap[] bitmap={null};AtomicInteger finished=new AtomicInteger();n.load(url,false,new NativeImages.Callback(){@Override public void loaded(android.graphics.Bitmap value){bitmap[0]=value;finished.set(1);}@Override public void failed(String message){finished.set(-1);}});await(()->finished.get()!=0,"nativeExifBitmapFactoryRouteFinished");require(finished.get()==1,"nativeExifBitmapFactoryRouteSucceeded");put(geometry,"bitmapFactoryRouteOutputWidth",bitmap[0].getWidth());put(geometry,"bitmapFactoryRouteOutputHeight",bitmap[0].getHeight());put(geometry,"expectedOrientedOriginalWidth",900);put(geometry,"expectedOrientedOriginalHeight",1600);put(geometry,"expectOriginalLogicalGeometry",expectSourceDimensions);put(report,"nativeExifGeometry",geometry);onMain(()->{activity.content.removeAllViews();return null;});n.close();stage("native_exif_orientation_header_and_logical_geometry");
    }
    /** Quantifies original header geometry versus sampled decoder pixels and actual post bounds. */
    private void nativeImageLayout()throws Exception {
        JSONArray cases=new JSONArray();put(report,"nativeImageLayout",cases);
        for(int[] original:new int[][]{{1080,3000},{2560,1440},{160,90},{1081,3001}}) {
            NativeImages n=loader();byte[] fixture=onMain(()->{android.graphics.Bitmap value=android.graphics.Bitmap.createBitmap(original[0],original[1],android.graphics.Bitmap.Config.ARGB_8888);value.eraseColor(0xff6388aa);android.graphics.Canvas canvas=new android.graphics.Canvas(value);android.graphics.Paint stripe=new android.graphics.Paint();stripe.setColor(0xffc6d7e8);for(int y=24;y<original[1];y+=64)canvas.drawRect(16,y,Math.max(17,original[0]-16),y+12,stripe);ByteArrayOutputStream output=new ByteArrayOutputStream();require(value.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output),"geometryFixturePngEncoded"+assertions);value.recycle();return output.toByteArray();});
            android.graphics.BitmapFactory.Options header=new android.graphics.BitmapFactory.Options();header.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(fixture,0,fixture.length,header);require(header.outWidth==original[0]&&header.outHeight==original[1],"realBitmapFactoryOriginalHeader"+assertions);
            String url=resource("geometry-"+original[0]+"x"+original[1]+".png",fixture,false);android.widget.ScrollView scroll=onMain(()->(android.widget.ScrollView)activity.content.getParent());
            NativeRichText rich=onMain(()->{activity.content.removeAllViews();scroll.scrollTo(0,0);NativeRichText value=new NativeRichText(activity,n,"[img]"+url+"[/img]",false,16,null);activity.content.addView(value,new ViewGroup.LayoutParams(-1,-2));return value;});
            await(()->onMain(()->images(rich).size()==1&&field(images(rich).get(0),"actual")!=null),"nativeGeometryImageLoaded"+original[0]);waitForIdleSync();
            JSONObject item=onMain(()->{Object image=images(rich).get(0);Drawable actual=(Drawable)field(image,"actual");android.widget.TextView owner=(android.widget.TextView)field(image,"owner");int available=owner.getWidth()-owner.getPaddingLeft()-owner.getPaddingRight();float density=rich.getResources().getDisplayMetrics().density;double wantedScale=Math.min(1d,Math.min(Math.min(available,Math.round(560*density))/(double)original[0],Math.round(720*density)/(double)original[1]));int wantedWidth=Math.max(1,(int)Math.round(original[0]*wantedScale)),wantedHeight=Math.max(1,(int)Math.round(original[1]*wantedScale));JSONObject value=new JSONObject();put(value,"originalHeaderWidth",original[0]);put(value,"originalHeaderHeight",original[1]);put(value,"imageDecoderDrawableWidth",actual.getIntrinsicWidth());put(value,"imageDecoderDrawableHeight",actual.getIntrinsicHeight());put(value,"nativeColumnAvailablePixels",available);put(value,"actualSpanWidth",((Drawable)image).getBounds().width());put(value,"actualSpanHeight",((Drawable)image).getBounds().height());put(value,"logicalSourceSpanWidth",wantedWidth);put(value,"logicalSourceSpanHeight",wantedHeight);put(value,"density",density);if(actual instanceof BitmapDrawable){android.graphics.Bitmap bitmap=((BitmapDrawable)actual).getBitmap();put(value,"decodedPixelCount",(long)bitmap.getWidth()*bitmap.getHeight());require((long)bitmap.getWidth()*bitmap.getHeight()<=800000,"inlineActualDecodePixelBudgetUnchanged"+assertions);}if(expectSourceDimensions){require(((Drawable)image).getBounds().width()==wantedWidth&&((Drawable)image).getBounds().height()==wantedHeight,"nativeSpanUsesOriginalLogicalGeometry"+assertions);}else{require(((Drawable)image).getBounds().width()<=actual.getIntrinsicWidth()&&((Drawable)image).getBounds().height()<=actual.getIntrinsicHeight(),"baselineSpanLimitedBySampledDecode"+assertions);}return value;});
            final int[] bitmapPixels={0,0};final AtomicInteger bitmapResults=new AtomicInteger();n.load(url,false,new NativeImages.Callback(){@Override public void loaded(android.graphics.Bitmap bitmap){bitmapPixels[0]=bitmap.getWidth();bitmapPixels[1]=bitmap.getHeight();bitmapResults.incrementAndGet();}@Override public void failed(String message){bitmapResults.set(-100);}});await(()->bitmapResults.get()!=0,"nativeBitmapFactorySampledImageLoaded"+original[0]);require(bitmapResults.get()==1,"nativeBitmapFactoryDecodeSucceeded"+assertions);put(item,"bitmapFactoryDecodedWidth",bitmapPixels[0]);put(item,"bitmapFactoryDecodedHeight",bitmapPixels[1]);put(item,"expectOriginalLogicalGeometry",expectSourceDimensions);
            final Drawable[] cached={null};AtomicInteger cacheResults=new AtomicInteger();n.loadDrawable(url,false,new NativeImages.DrawableCallback(){@Override public void loaded(Drawable drawable){cached[0]=drawable;cacheResults.incrementAndGet();}@Override public void failed(String message){cacheResults.set(-100);}});await(()->cacheResults.get()!=0,"nativeGeometryBitmapCacheHit"+original[0]);require(cacheResults.get()==1,"nativeGeometryBitmapCacheHitSucceeded"+assertions);
            try{java.lang.reflect.Method sourceSize=n.getClass().getMethod("sourceDimensions",Drawable.class);int[] logical=(int[])sourceSize.invoke(n,cached[0]);put(item,"cachedWrapperSourceDimensions",new JSONArray().put(logical[0]).put(logical[1]));if(expectSourceDimensions){require(logical[0]==original[0]&&logical[1]==original[1],"nativeCachedBitmapWrapperKeepsOriginalSourceGeometry"+assertions);logical[0]=1;int[] untouched=(int[])sourceSize.invoke(n,cached[0]);require(untouched[0]==original[0],"nativeSourceGeometryIsDefensiveCopy"+assertions);}}catch(NoSuchMethodException legacy){require(!expectSourceDimensions,"nativeSourceGeometryApiAvailableWhenExpected");put(item,"cachedWrapperSourceDimensionsAvailable",false);}
            int stableHeight=onMain(rich::getHeight);int[] spanSize=onMain(()->{Drawable image=(Drawable)images(rich).get(0);return new int[]{image.getBounds().width(),image.getBounds().height()};});
            onMain(()->{View spacer=new View(activity);activity.content.addView(spacer,new ViewGroup.LayoutParams(-1,scroll.getHeight()*4));return null;});waitForIdleSync();onMain(()->{scroll.scrollTo(0,activity.content.getHeight());return null;});await(()->onMain(()->field(images(rich).get(0),"actual")==null),"nativeGeometryFarImageRetired"+original[0]);
            require(onMain(()->{Drawable image=(Drawable)images(rich).get(0);return image.getBounds().width()==spanSize[0]&&image.getBounds().height()==spanSize[1]&&rich.getHeight()==stableHeight;}),"nativeRetiredSourceGeometryUnchanged"+assertions);n.trimMemory(40);int fetches=resources.get(url).connections.get();onMain(()->{scroll.scrollTo(0,0);return null;});await(()->onMain(()->field(images(rich).get(0),"actual")!=null),"nativeGeometryImageReDecoded"+original[0]);waitForIdleSync();require(resources.get(url).connections.get()==fetches,"nativeGeometryRetirementDoesNotRefetch"+assertions);require(onMain(rich::getHeight)==stableHeight,"nativeReDecodedSourceGeometryUnchanged"+assertions);put(item,"postHeightBeforeAndAfterLocalRedecode",stableHeight);put(item,"retirementLocalRedecodeAdditionalFetches",resources.get(url).connections.get()-fetches);cases.put(item);onMain(()->{activity.content.removeAllViews();return null;});n.close();
        }
        stage("actual_image_decoder_bitmap_factory_and_native_span_geometry");
    }
    /** Pauses only this isolated helper main queue while real Android GIF decoder jobs run. */
    private void nativeDecodeBackpressure()throws Exception {
        NativeImages n=loader();Results result=new Results();byte[] large=asset("synthetic-large-two-frame.gif");String url=resource("native-ready-queue.gif",large,false);CountDownLatch gateEntered=new CountDownLatch(1),release=new CountDownLatch(1);long nativeBefore=android.os.Debug.getNativeHeapAllocatedSize();
        main.post(()->{gateEntered.countDown();try{release.await(4,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
        require(gateEntered.await(2,TimeUnit.SECONDS),"nativeDecodeMainQueueGateEntered");int ready=-1,remaining=-1;long nativeHeld=0;String heldState="";
        try {
            for(int i=0;i<30;i++)n.loadDrawable(url,false,result);
            await(()->queuedReady(n)>0,"nativeDecodeFirstResultActuallyQueued");if(!expectDecodeBackpressure){long end=SystemClock.elapsedRealtime()+2000;while(queuedReady(n)<30&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(10);}else SystemClock.sleep(120);ready=queuedReady(n);remaining=activeSubscribers(n);heldState=n.state();nativeHeld=android.os.Debug.getNativeHeapAllocatedSize();
            if(expectDecodeBackpressure){require(ready==1&&remaining==29,"nativeQueuedAnimationDecodeBoundedToOne");}
            else{require(ready==30&&remaining==0,"baselineNativeThirtyAnimationsDecodedAheadOfMain");}
        }finally{release.countDown();}
        await(()->result.count()==30,"nativeDecodeAllThirtyDeliveredAfterMainReady");require(result.failures.get()==0&&resources.get(url).connections.get()==1,"nativeDecodeBackpressureNoDeliveryOrFetchRegression");
        onMain(()->{for(Drawable drawable:result.values)require(drawable instanceof AnimatedImageDrawable&&drawable.getIntrinsicWidth()==512&&drawable.getIntrinsicHeight()==512,"native512GifDrawable"+assertions);return null;});require(new HashSet<>(result.values).size()==30,"nativeDecodeMutableInstancesRemainIndependent");
        JSONObject metrics=new JSONObject();put(metrics,"expectedBackpressure",expectDecodeBackpressure);put(metrics,"subscribers",30);put(metrics,"decodedAnimationResultsQueuedWhileMainBlocked",ready);put(metrics,"subscribersAwaitingIndependentDecode",remaining);put(metrics,"loaderStateWhileMainBlocked",heldState);put(metrics,"nativeHeapAllocatedBeforeBytes",nativeBefore);put(metrics,"nativeHeapAllocatedWhileMainBlockedBytes",nativeHeld);put(metrics,"nativeHeapDeltaIsObservationNotPixelBudget",true);put(metrics,"actualAndroidGifDecoders",true);put(metrics,"deliveryCount",result.values.size());put(metrics,"transportRequests",resources.get(url).connections.get());put(report,"nativeAnimationDecodeBackpressure",metrics);n.close();stage("native_512_gif_main_queue_decode_backpressure");
        // Closing while the main callback is held must interrupt a waiting decoder worker.
        NativeImages closing=loader();Results cancelled=new Results();String closeUrl=resource("native-close-ack.gif",large,false);CountDownLatch entered=new CountDownLatch(1),closeRelease=new CountDownLatch(1);main.post(()->{entered.countDown();try{closeRelease.await(4,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});require(entered.await(2,TimeUnit.SECONDS),"nativeCloseAcknowledgementGateEntered");
        try{for(int i=0;i<30;i++)closing.loadDrawable(closeUrl,false,cancelled);await(()->queuedReady(closing)>0,"nativeCloseFirstDecodedResultQueued");closing.close();ThreadPoolExecutor pool=(ThreadPoolExecutor)field(closing,"workers");require(pool.awaitTermination(2,TimeUnit.SECONDS),"nativeCloseInterruptsAcknowledgementWait");require(pending(closing)==0&&closing.state().contains("media=0 "),"nativeCloseDropsWaitingDecodeGroup");}finally{closeRelease.countDown();}
        onMain(()->null);require(cancelled.count()==0,"nativeCloseSuppressesAllHeldCallbacks");stage("native_close_interrupts_main_acknowledgement_wait");
    }
    private static int activeSubscribers(NativeImages n)throws Exception {
        Object lock=field(n,"lifecycle");synchronized(lock){int total=0;Map<?,?> jobs=(Map<?,?>)field(n,"mediaTasks");for(Object task:jobs.values())total+=((List<?>)field(task,"listeners")).size();return total;}
    }
    private static int queuedReady(NativeImages n)throws Exception {Object lock=field(n,"lifecycle");synchronized(lock){return ((java.util.Set<?>)field(n,"mediaListeners")).size()-activeSubscribers(n);}}
    private void richBusyRecovery()throws Exception {
        NativeImages n=loader();String url="https://img4.nga.cn/ngabbs/post/smile/smile.gif";Resource source=new Resource(gif,true);resources.put(url,source);StringBuilder content=new StringBuilder();for(int i=0;i<60;i++)content.append("[s:1]");
        NativeRichText rich=onMain(()->{NativeRichText v=new NativeRichText(activity,n,content.toString(),false,16,null);activity.content.removeAllViews();activity.content.addView(v,new ViewGroup.LayoutParams(-1,-2));return v;});
        await(()->onMain(()->images(rich).size()==60&&anyBusy(rich)),"richTextTemporaryBusyObserved");require(onMain(()->noneFailed(rich)),"busyIsNotPermanentImageFailure");source.release.countDown();await(()->onMain(()->allLoaded(rich)),"richTextAllSixtyImagesEventuallyLoaded");
        require(onMain(()->images(rich).size()==60&&noneFailed(rich)),"richTextSixtyNoPermanentPlaceholder");onMain(()->{activity.content.removeAllViews();return null;});await(()->pending(n)==0,"richDetachedRequestsCleared");n.close();put(report,"richSixtyGifFetches",source.connections.get());stage("native_rich_text_busy_retries_recover");
    }
    private void cryptReplacement()throws Exception {
        NativeImages n=loader();String url=resource("crypt.gif",gif,false);resources.put("https://img4.nga.cn/ngabbs/post/smile/smile.gif",new Resource(gif,false));String plain="解密图片[img]"+url+"[/img][img]"+url+"[/img]";String cipher=encode("fixture",plain);
        NativeRichText rich=onMain(()->{NativeRichText v=new NativeRichText(activity,n,"外部[s:1][crypt]"+cipher+"[/crypt]",false,16,null);activity.content.removeAllViews();activity.content.addView(v,new ViewGroup.LayoutParams(-1,-2));return v;});
        await(()->onMain(()->images(rich).size()==1&&allLoaded(rich)),"cryptExternalImageInitiallyLoaded");Object external=onMain(()->images(rich).get(0));List<Object> previous=new ArrayList<>();
        for(int generation=0;generation<6;generation++) {
            onMain(()->{EditText password=findEdit(rich);Button decrypt=findButton(rich,"解密");require(password!=null&&decrypt!=null,"nativeCryptFormPresent"+assertions);password.setText("fixture");decrypt.performClick();return null;});
            await(()->onMain(()->images(rich).size()==3&&allLoaded(rich)),"cryptGenerationImagesLoaded"+generation);
            final List<Object> old=new ArrayList<>(previous);onMain(()->{for(Object image:old){require(field(image,"actual")==null&&field(image,"request")==null,"replacedCryptDrawableReleased"+assertions);require(((Map<?,?>)field(image,"scheduled")).isEmpty(),"replacedCryptAnimationCallbacksCleared"+assertions);require(!((View)field(image,"owner")).isAttachedToWindow(),"replacedCryptOwnerDetached"+assertions);}require(images(rich).get(0)==external&&field(external,"actual")!=null,"outsideCryptImageRetained"+assertions);return null;});
            previous=onMain(()->{List<Object> values=images(rich);return new ArrayList<>(values.subList(1,values.size()));});
        }
        require(onMain(()->images(rich).size()==3),"repeatedCryptRegistrationCountStable");onMain(()->{activity.content.removeAllViews();return null;});n.close();stage("six_native_crypt_replacements_release_old_images_only");
    }
    /** Scrolls a real native long post. Bitmap identity/bytes are observations, not GC guesses. */
    private void richRetention()throws Exception {
        NativeImages n=loader();
        byte[] picture=onMain(()->{android.graphics.Bitmap b=android.graphics.Bitmap.createBitmap(512,512,android.graphics.Bitmap.Config.ARGB_8888);b.eraseColor(0xff7394af);ByteArrayOutputStream out=new ByteArrayOutputStream();require(b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out),"retentionFixtureEncoded");b.recycle();return out.toByteArray();});
        StringBuilder content=new StringBuilder();List<String> urls=new ArrayList<>();final int count=50;
        for(int i=0;i<count;i++){String url=resource("retention-"+i+".png",picture,false);urls.add(url);content.append("图片 ").append(i+1).append("[img]").append(url).append("[/img]\n");}
        NativeRichText rich=onMain(()->{NativeRichText v=new NativeRichText(activity,n,content.toString(),false,16,null);activity.content.removeAllViews();activity.content.addView(v,new ViewGroup.LayoutParams(-1,-2));return v;});
        await(()->onMain(()->images(rich).size()==count&&rich.getHeight()>0),"retentionFiftyNativeSpansLaidOut");
        android.widget.ScrollView scroll=onMain(()->{android.view.ViewParent parent=activity.content.getParent();return (android.widget.ScrollView)parent;});
        JSONArray samples=new JSONArray(),steps=new JSONArray();put(report,"retentionSteps",steps);
        for(int index=0;index<count;index++) {
            final int at=index;
            onMain(()->{Object image=images(rich).get(at);android.widget.TextView owner=(android.widget.TextView)field(image,"owner");android.text.Layout layout=owner.getLayout();int offset=(Integer)field(image,"textOffset");int top=owner.getTop()+owner.getTotalPaddingTop()+layout.getLineTop(layout.getLineForOffset(offset));scroll.scrollTo(0,Math.max(0,top-scroll.getHeight()/4));return null;});
            try{await(()->onMain(()->field(images(rich).get(at),"actual")!=null),"retentionImageLoaded"+index);}
            finally{steps.put(onMain(()->retentionPosition(rich,scroll,at)));}
            waitForIdleSync();
            if(index%10==9){JSONObject sample=onMain(()->retained(rich));put(sample,"imagesVisited",index+1);samples.put(sample);}
        }
        waitForIdleSync(); final int laidOutHeight=onMain(rich::getHeight);
        JSONObject after=onMain(()->retained(rich));put(after,"visitedImages",count);put(after,"expectedBoundedRetention",expectBoundedRetention);put(after,"samples",samples);put(after,"fixtureDecodedBytesEach",512L*512*4);put(after,"compressedFixtureBytesEach",picture.length);put(report,"longPostRetention",after);
        if(expectBoundedRetention){require(after.getInt("uniqueRetainedBitmaps")<=12,"farOffscreenDecodedBitmapsBounded");require(after.getLong("uniqueRetainedBitmapBytes")<=12L*512*512*4,"farOffscreenDecodedPixelBytesBounded");}
        int fetchesBefore=0;JSONArray initialFetches=new JSONArray();boolean eachVisitedFetched=true;
        for(String url:urls){int fetches=resources.get(url).connections.get();initialFetches.put(fetches);fetchesBefore+=fetches;eachVisitedFetched&=fetches>=1;}
        put(report,"retentionInitialFetchesByImage",initialFetches);
        onMain(()->{scroll.scrollTo(0,0);return null;});await(()->onMain(()->field(images(rich).get(0),"actual")!=null),"retentionTopImageRestored");
        onMain(()->{scroll.scrollTo(0,0);return null;});SystemClock.sleep(80);
        int fetchesAfter=0;for(String url:urls)fetchesAfter+=resources.get(url).connections.get();put(report,"retentionFetchesBeforeReturn",fetchesBefore);put(report,"retentionFetchesAfterReturn",fetchesAfter);put(report,"retentionReturnScrollY",onMain(scroll::getScrollY));put(report,"retentionContentHeightBeforeReturn",laidOutHeight);put(report,"retentionContentHeightAfterReturn",onMain(rich::getHeight));
        require(eachVisitedFetched,"eachVisitedLongPostImageHadTransport");require(fetchesAfter==fetchesBefore,"scrollBackDoesNotRedownloadVisitedImages");require(onMain(scroll::getScrollY)==0,"restoredImagePreservesTopReadingPosition");require(onMain(rich::getHeight)==laidOutHeight,"retiredImagesKeepExactMeasuredPostHeight");
        onMain(()->{activity.content.removeAllViews();return null;});n.close();stage("fifty_image_native_scroll_decoded_retention_and_return");
    }
    /** A real wall-clock scroll/decoded-resource run; test duration is explicit in the report. */
    private void richRetentionSoak()throws Exception {
        NativeImages n=loader();byte[] picture=onMain(()->{android.graphics.Bitmap b=android.graphics.Bitmap.createBitmap(512,512,android.graphics.Bitmap.Config.ARGB_8888);b.eraseColor(0xff6e91b4);ByteArrayOutputStream out=new ByteArrayOutputStream();require(b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out),"longSoakFixtureEncoded");b.recycle();return out.toByteArray();});
        List<String> urls=new ArrayList<>();StringBuilder content=new StringBuilder();final int count=50;
        for(int i=0;i<count;i++){String url=resource("wall-clock-"+i+".png",picture,false);urls.add(url);content.append("持续滚动图片 ").append(i+1).append("[img]").append(url).append("[/img]\n");}
        NativeRichText rich=onMain(()->{NativeRichText v=new NativeRichText(activity,n,content.toString(),false,16,null);activity.content.removeAllViews();activity.content.addView(v,new ViewGroup.LayoutParams(-1,-2));return v;});
        await(()->onMain(()->images(rich).size()==count&&rich.getHeight()>0),"longSoakFiftySpansLaidOut");android.widget.ScrollView scroll=onMain(()->(android.widget.ScrollView)activity.content.getParent());
        for(int i=0;i<count;i++){final int at=i;onMain(()->{scrollToImage(rich,scroll,at);return null;});await(()->onMain(()->field(images(rich).get(at),"actual")!=null),"longSoakWarmImageLoaded");waitForIdleSync();}
        await(()->pending(n)==0&&connections(n)==0,"longSoakWarmWorkDrained");final int stableHeight=onMain(rich::getHeight);final int baselineFetches=fetches(urls);
        long started=SystemClock.elapsedRealtime(),until=started+retentionSoakSeconds*1000L,nextSample=started;int steps=0,turns=0,index=count-1,direction=-1,maxRetained=0,maxThreads=0;long maxRetainedBytes=0,maxHeap=0;JSONArray samples=new JSONArray();put(report,"retentionSoakSamples",samples);
        int decodedLimit=onMain(()->(int)Math.ceil((scroll.getHeight()+2*640*rich.getResources().getDisplayMetrics().density)/512d)+3);
        while(SystemClock.elapsedRealtime()<until) {
            final int at=index;long stepStarted=SystemClock.elapsedRealtime();
            onMain(()->{scrollToImage(rich,scroll,at);return null;});await(()->onMain(()->field(images(rich).get(at),"actual")!=null),"longSoakCurrentImageReady");waitForIdleSync();
            require(onMain(rich::getHeight)==stableHeight,"longSoakExactPostHeightStable");require(fetches(urls)==baselineFetches,"longSoakNoRedownloadAfterWarmup");
            JSONObject retained=onMain(()->retained(rich));int live=retained.getInt("uniqueRetainedBitmaps");long pixels=retained.getLong("uniqueRetainedBitmapBytes");maxRetained=Math.max(maxRetained,live);maxRetainedBytes=Math.max(maxRetainedBytes,pixels);require(live<=decodedLimit,"longSoakDecodedPixelWindowBounded");
            String state=n.state();require(pending(n)<=32&&connections(n)<=2,"longSoakQueueAndConnectionsBounded");
            steps++;long now=SystemClock.elapsedRealtime();
            if(now>=nextSample) {
                JSONObject sample=new JSONObject();put(sample,"elapsedMillis",now-started);put(sample,"completedScrollSteps",steps);put(sample,"imageIndex",index);put(sample,"direction",direction);put(sample,"retained",retained);put(sample,"scrollY",onMain(scroll::getScrollY));put(sample,"postHeight",onMain(rich::getHeight));put(sample,"originalFetches",fetches(urls));put(sample,"additionalFetchesAfterWarmup",fetches(urls)-baselineFetches);put(sample,"loaderState",state);put(sample,"trackedConnections",connections(n));put(sample,"transportActive",active(urls));long used=heap();int threads=Thread.getAllStackTraces().size();maxHeap=Math.max(maxHeap,used);maxThreads=Math.max(maxThreads,threads);put(sample,"javaHeapUsedBytes",used);put(sample,"processThreadCount",threads);samples.put(sample);Bundle progress=new Bundle();progress.putString("safe_native_images_soak_progress",sample.toString());sendStatus(1,progress);nextSample=now+5000;
            }
            index+=direction;if(index<0){index=1;direction=1;turns++;}else if(index>=count){index=count-2;direction=-1;turns++;}
            long wait=600-(SystemClock.elapsedRealtime()-stepStarted);if(wait>0)SystemClock.sleep(Math.min(wait,Math.max(0,until-SystemClock.elapsedRealtime())));
        }
        long elapsed=SystemClock.elapsedRealtime()-started;require(elapsed>=retentionSoakSeconds*1000L,"requestedRealWallClockDurationCompleted");await(()->pending(n)==0&&connections(n)==0,"longSoakFinalWorkDrained");JSONObject summary=new JSONObject();put(summary,"requestedDurationSeconds",retentionSoakSeconds);put(summary,"actualDurationMillis",elapsed);put(summary,"completedScrollSteps",steps);put(summary,"directionReversals",turns);put(summary,"uniqueFixtureImages",count);put(summary,"retainedBitmapLimitFromViewport",decodedLimit);put(summary,"maxRetainedBitmaps",maxRetained);put(summary,"maxRetainedBitmapBytes",maxRetainedBytes);put(summary,"maxSampledJavaHeapBytes",maxHeap);put(summary,"maxSampledProcessThreads",maxThreads);put(summary,"originalFetchesAfterWarmup",baselineFetches);put(summary,"additionalFetchesAfterWarmup",fetches(urls)-baselineFetches);put(summary,"stablePostHeight",stableHeight);put(summary,"programmaticNativeScrollNotInjectedFingerGestures",true);put(summary,"heapAndThreadCountsAreObservationsOnly",true);put(summary,"memoryLeakCertificationClaimed",false);put(report,"retentionSoak",summary);
        onMain(()->{activity.content.removeAllViews();return null;});n.close();ThreadPoolExecutor pool=(ThreadPoolExecutor)field(n,"workers");require(pool.awaitTermination(2,TimeUnit.SECONDS),"longSoakWorkerThreadsTerminate");stage("real_wall_clock_native_long_post_back_and_forth");
    }
    private static void scrollToImage(NativeRichText rich,android.widget.ScrollView scroll,int index)throws Exception {
        Object image=images(rich).get(index);android.widget.TextView owner=(android.widget.TextView)field(image,"owner");android.text.Layout layout=owner.getLayout();int offset=(Integer)field(image,"textOffset");int top=owner.getTop()+owner.getTotalPaddingTop()+layout.getLineTop(layout.getLineForOffset(offset));scroll.scrollTo(0,Math.max(0,top-scroll.getHeight()/4));
    }
    private int fetches(List<String> urls){int count=0;for(String url:urls)count+=resources.get(url).connections.get();return count;}
    private int active(List<String> urls){int count=0;for(String url:urls)count+=resources.get(url).active.get();return count;}
    private static JSONObject retentionPosition(NativeRichText rich,android.widget.ScrollView scroll,int at)throws Exception {
        Object image=images(rich).get(at);android.widget.TextView owner=(android.widget.TextView)field(image,"owner");android.text.Layout layout=owner.getLayout();int offset=(Integer)field(image,"textOffset");int line=layout.getLineForOffset(offset);JSONObject item=new JSONObject();put(item,"imageIndex",at);put(item,"scrollY",scroll.getScrollY());put(item,"viewportHeight",scroll.getHeight());put(item,"ownerTop",owner.getTop());put(item,"ownerHeight",owner.getHeight());put(item,"richHeight",rich.getHeight());put(item,"lineTop",layout.getLineTop(line));put(item,"lineBottom",layout.getLineBottom(line));put(item,"spanHeight",((Drawable)image).getBounds().height());put(item,"spanWidth",((Drawable)image).getBounds().width());put(item,"loaded",field(image,"actual")!=null);put(item,"requestPending",field(image,"request")!=null);put(item,"failed",field(image,"failed"));put(item,"busyRetries",field(image,"busyRetries"));java.lang.reflect.Method near=image.getClass().getDeclaredMethod("nearViewport",int.class);near.setAccessible(true);float density=rich.getResources().getDisplayMetrics().density;put(item,"near320",near.invoke(image,Math.round(320*density)));put(item,"near640",near.invoke(image,Math.round(640*density)));put(item,"retained",retained(rich));return item;
    }
    private static JSONObject retained(NativeRichText rich)throws Exception {
        java.util.Set<android.graphics.Bitmap> unique=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<android.graphics.Bitmap,Boolean>());int actual=0;long bytes=0;
        for(Object image:images(rich)){Object drawable=field(image,"actual");if(drawable!=null)actual++;if(drawable instanceof BitmapDrawable){android.graphics.Bitmap bitmap=((BitmapDrawable)drawable).getBitmap();if(unique.add(bitmap))bytes+=bitmap.getAllocationByteCount();}}
        JSONObject item=new JSONObject();put(item,"retainedDrawables",actual);put(item,"uniqueRetainedBitmaps",unique.size());put(item,"uniqueRetainedBitmapBytes",bytes);return item;
    }
    private void parserBenchmark()throws Exception {
        Class<?> parser=Class.forName("cn.ngapure.app.NativeContentParser");java.lang.reflect.Method parse=parser.getDeclaredMethod("parse",String.class);parse.setAccessible(true);JSONArray metrics=new JSONArray();
        for(int count:new int[]{200,800}) {
            StringBuilder html=new StringBuilder();for(int i=0;i<count;i++){html.append("<blockquote>");for(int n=0;n<1024;n++)html.append('a');html.append("</blockquote>");}String value=html.toString();
            for(int warm=0;warm<2;warm++)require(((List<?>)parse.invoke(null,value)).size()==count,"parserWarmCount"+count+"_"+warm);
            long[] times=new long[5];for(int run=0;run<times.length;run++){long before=SystemClock.elapsedRealtimeNanos();List<?> blocks=(List<?>)parse.invoke(null,value);times[run]=SystemClock.elapsedRealtimeNanos()-before;require(blocks.size()==count,"parserMeasuredCount"+count+"_"+run);}
            java.util.Arrays.sort(times);JSONObject item=new JSONObject();put(item,"quoteBlocks",count);put(item,"utf16Chars",value.length());put(item,"samples",times.length);put(item,"medianMillis",times[2]/1000000d);put(item,"maxMillis",times[4]/1000000d);put(item,"absoluteTimingIsNotPassCriterion",true);metrics.put(item);
        }
        put(report,"parserBenchmark",metrics);put(report,"actualAndroidArtParser",true);stage("actual_art_native_content_parser_benchmark");
    }
    private void soak()throws Exception {
        long started=SystemClock.elapsedRealtime(),maxHeap=heap(),startHeap=maxHeap;int maxThreads=Thread.getAllStackTraces().size(),startThreads=maxThreads;
        JSONArray rounds=new JSONArray();NativeImages cryptLoader=loader();String cryptUrl=resource("soak-crypt.gif",gif,false);resources.put("https://img4.nga.cn/ngabbs/post/smile/smile.gif",new Resource(gif,false));String cipher=encode("soak","图片[img]"+cryptUrl+"[/img][img]"+cryptUrl+"[/img]");
        NativeRichText rich=onMain(()->{NativeRichText v=new NativeRichText(activity,cryptLoader,"外部[s:1][crypt]"+cipher+"[/crypt]",false,16,null);activity.content.removeAllViews();activity.content.addView(v,new ViewGroup.LayoutParams(-1,-2));return v;});
        await(()->onMain(()->images(rich).size()==1&&allLoaded(rich)),"soakExternalImageReady");Object outside=onMain(()->images(rich).get(0));List<Object> prior=new ArrayList<>();
        for(int round=0;round<soakRounds;round++) {
            final int index=round;NativeImages n=loader();Results cancelled=new Results(),kept=new Results();String url=resource("soak-"+round+".gif",gif,true);Resource transport=resources.get(url);List<NativeImages.Request> cancel=new ArrayList<>();
            onMain(()->{for(int i=0;i<4;i++)cancel.add(n.loadDrawable(url,false,cancelled));for(int i=0;i<4;i++)n.loadDrawable(url,false,kept);return null;});require(transport.entered.await(3,TimeUnit.SECONDS),"soakTransportEntered"+round);for(NativeImages.Request request:cancel)request.cancel();transport.release.countDown();await(()->kept.count()==4,"soakSurvivingDeliveries"+round);
            await(()->pending(n)==0&&n.state().contains("media=0 ")&&connections(n)==0&&transport.active.get()==0,"soakWorkDrained"+round);require(cancelled.count()==0&&kept.failures.get()==0&&transport.connections.get()==1,"soakCancellationAndCoalescing"+round);
            n.close();ThreadPoolExecutor pool=(ThreadPoolExecutor)field(n,"workers");require(pool.awaitTermination(2,TimeUnit.SECONDS),"soakWorkersTerminated"+round);require(n.state().contains("queue=0 ")&&connections(n)==0,"soakClosedQueueAndConnectionsEmpty"+round);loaders.remove(n);resources.remove(url);
            onMain(()->{findEdit(rich).setText("soak");findButton(rich,"解密").performClick();return null;});await(()->onMain(()->images(rich).size()==3&&allLoaded(rich)),"soakCryptLoaded"+round);final List<Object> old=new ArrayList<>(prior);
            onMain(()->{for(Object image:old)require(field(image,"actual")==null&&field(image,"request")==null&&((Map<?,?>)field(image,"scheduled")).isEmpty(),"soakOldCryptResourcesCleared"+index+"_"+assertions);require(images(rich).get(0)==outside&&field(outside,"actual")!=null,"soakOutsideImageRetained"+index);return null;});
            prior=onMain(()->new ArrayList<>(images(rich).subList(1,3)));await(()->pending(cryptLoader)==0&&connections(cryptLoader)==0,"soakCryptLoaderDrained"+round);
            if(round%10==9)System.gc();long used=heap();int threads=Thread.getAllStackTraces().size();maxHeap=Math.max(maxHeap,used);maxThreads=Math.max(maxThreads,threads);JSONObject item=new JSONObject();put(item,"round",round+1);put(item,"queueAndJobsDrained",true);put(item,"trackedConnections",0);put(item,"syntheticTransportActive",0);put(item,"cancelledCallbacks",cancelled.count());put(item,"survivingCallbacks",kept.count());put(item,"workerPoolTerminated",true);put(item,"cryptRegisteredImages",3);put(item,"heapUsedBytes",used);put(item,"processThreadCount",threads);rounds.put(item);
        }
        onMain(()->{activity.content.removeAllViews();return null;});cryptLoader.close();ThreadPoolExecutor cryptPool=(ThreadPoolExecutor)field(cryptLoader,"workers");require(cryptPool.awaitTermination(2,TimeUnit.SECONDS),"soakCryptWorkersTerminated");require(pending(cryptLoader)==0&&connections(cryptLoader)==0,"soakFinalAllOwnedWorkReleased");loaders.remove(cryptLoader);System.gc();JSONObject metrics=new JSONObject();put(metrics,"completedRounds",rounds.length());put(metrics,"durationMillis",SystemClock.elapsedRealtime()-started);put(metrics,"initialHeapUsedBytes",startHeap);put(metrics,"maxSampledHeapUsedBytes",maxHeap);put(metrics,"finalHeapUsedBytes",heap());put(metrics,"runtimeMaxHeapBytes",Runtime.getRuntime().maxMemory());put(metrics,"initialThreadCount",startThreads);put(metrics,"maxSampledThreadCount",maxThreads);put(metrics,"finalThreadCount",Thread.getAllStackTraces().size());put(metrics,"gcRequestedPeriodically",true);put(metrics,"heapAndThreadCountsAreObservationsOnly",true);put(metrics,"memoryLeakCertificationClaimed",false);put(metrics,"rounds",rounds);put(report,"soak",metrics);stage("sustained_load_cancel_close_and_real_crypt_replacement");
    }
    private static long heap(){Runtime runtime=Runtime.getRuntime();return runtime.totalMemory()-runtime.freeMemory();}
    private static int connections(NativeImages images)throws Exception{return ((java.util.Set<?>)field(images,"connections")).size();}
    @SuppressWarnings("unchecked") private static List<Object> images(NativeRichText rich)throws Exception{return (List<Object>)field(rich,"inlineImages");}
    private static boolean allLoaded(NativeRichText rich)throws Exception{for(Object image:images(rich))if(field(image,"actual")==null)return false;return true;}
    private static boolean anyBusy(NativeRichText rich)throws Exception{for(Object image:images(rich))if((Integer)field(image,"busyRetries")>0)return true;return false;}
    private static boolean noneFailed(NativeRichText rich)throws Exception{for(Object image:images(rich))if(Boolean.TRUE.equals(field(image,"failed")))return false;return true;}
    private static Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static EditText findEdit(View root){if(root instanceof EditText)return (EditText)root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){EditText v=findEdit(group.getChildAt(i));if(v!=null)return v;}}return null;}
    private static Button findButton(View root,String text){if(root instanceof Button&&((Button)root).getText().toString().equals(text))return (Button)root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){Button v=findButton(group.getChildAt(i),text);if(v!=null)return v;}}return null;}
    private static int pending(NativeImages images){return Integer.parseInt(images.state().split("mediaListeners=")[1].split(" ")[0]);}
    private byte[] asset(String name)throws IOException{try(InputStream in=getTargetContext().getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);return out.toByteArray();}}
    private <T>T onMain(Callable<T> action)throws Exception{if(Looper.myLooper()==Looper.getMainLooper())return action.call();CountDownLatch done=new CountDownLatch(1);final Object[] value={null};final Throwable[] error={null};main.post(()->{try{value[0]=action.call();}catch(Throwable t){error[0]=t;}finally{done.countDown();}});if(!done.await(5,TimeUnit.SECONDS))throw new IllegalStateException("MAIN_TIMEOUT");if(error[0]!=null){if(error[0] instanceof Exception)throw (Exception)error[0];if(error[0] instanceof Error)throw (Error)error[0];throw new IllegalStateException("MAIN_FAILURE");}@SuppressWarnings("unchecked")T result=(T)value[0];return result;}
    private void await(Callable<Boolean> condition,String key)throws Exception{long until=SystemClock.elapsedRealtime()+6000;while(SystemClock.elapsedRealtime()<until){if(condition.call()){require(true,key);return;}SystemClock.sleep(20);}require(false,key);}
    private void require(boolean condition,String key){assertions++;put(checks,key,condition);if(!condition)throw new AssertionError(key);}
    private void stage(String name){cases.put(name);}
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}}
    private static String encode(String password,String text){int[] state=new int[256];for(int i=0;i<256;i++)state[i]=i;for(int i=0,j=0;i<256;i++){j=(j+state[i]+password.charAt(i%password.length()))&255;int value=state[i];state[i]=state[j];state[j]=value;}StringBuilder result=new StringBuilder();for(int at=0,i=0,j=0;at<text.length();at++){i=(i+1)&255;j=(j+state[i])&255;int value=state[i];state[i]=state[j];state[j]=value;int cipher=text.charAt(at)^state[(state[i]+state[j])&255];result.append(String.format(java.util.Locale.ROOT,"%%u%04X",cipher));}return result.toString();}
}
