package cn.ngapure.cacheprobe;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.system.Os;
import cn.ngapure.app.NativeImages;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact immutable production loader, actual Android codecs, own files/UID, no INTERNET. */
public final class CacheManagementProbe extends Instrumentation {
    private static final int COLOR=Color.rgb(37,119,203),WIDTH=32,HEIGHT=24;
    private final JSONObject report=new JSONObject(),checks=new JSONObject();
    private final JSONArray cases=new JSONArray();
    private final List<NativeImages> loaders=new ArrayList<>();
    private final Map<String,Resource> resources=new ConcurrentHashMap<>();
    private final AtomicInteger rejectedNonFixtureRequests=new AtomicInteger();
    private CacheHostActivity activity;
    private File root;
    private byte[] png;
    private boolean authorized;
    private int assertions;
    private String stage="guard";

    private static final class Resource {
        final byte[] bytes;
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicInteger connections=new AtomicInteger();
        volatile boolean anonymous=true;
        Resource(byte[] bytes,boolean blocked){this.bytes=bytes;if(!blocked)release.countDown();}
    }
    private static final class Results implements NativeImages.DrawableCallback {
        final List<Drawable> values=Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger failures=new AtomicInteger();
        volatile boolean mainOnly=true;
        @Override public void loaded(Drawable value){mainOnly&=Looper.myLooper()==Looper.getMainLooper();values.add(value);}
        @Override public void failed(String message){mainOnly&=Looper.myLooper()==Looper.getMainLooper();failures.incrementAndGet();}
        int count(){return values.size()+failures.get();}
    }
    private static final class Avatars implements NativeImages.Callback {
        final List<Bitmap> values=Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger failures=new AtomicInteger();volatile boolean mainOnly=true;
        @Override public void loaded(Bitmap value){mainOnly&=Looper.myLooper()==Looper.getMainLooper();values.add(value);}
        @Override public void failed(String message){mainOnly&=Looper.myLooper()==Looper.getMainLooper();failures.incrementAndGet();}
        int count(){return values.size()+failures.get();}
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticCacheOnly"));start();}
    private <T>T main(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private void check(String key,boolean value)throws Exception{checks.put(key,value);assertions++;if(!value)throw new AssertionError(key);}
    private void await(Callable<Boolean> condition,String key)throws Exception{long until=SystemClock.elapsedRealtime()+5000;while(!condition.call()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(20);check(key,condition.call());}
    private byte[] asset(String name)throws Exception{try(InputStream input=getContext().getAssets().open(name);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] data=new byte[4096];int count;while((count=input.read(data))!=-1)output.write(data,0,count);return output.toByteArray();}}
    private NativeImages loader(File directory)throws Exception{return main(()->{NativeImages value=new NativeImages();value.setMediaCacheDirectory(directory);loaders.add(value);return value;});}
    private File directory(String name)throws Exception{File value=new File(root,name);check("createDirectory"+name,value.mkdir());return value;}
    private String resource(String name,boolean blocked){String url="https://img4.nga.cn/attachments/cache-management-fixture/"+name;resources.put(url,new Resource(png,blocked));return url;}
    private Bitmap bitmap(Results result,int index)throws Exception{Drawable value=result.values.get(index);check("actualBitmapDrawable"+assertions,value instanceof BitmapDrawable);Bitmap bitmap=((BitmapDrawable)value).getBitmap();check("actualPngPixelAndDimensions"+assertions,bitmap.getWidth()==WIDTH&&bitmap.getHeight()==HEIGHT&&bitmap.getPixel(4,4)==COLOR&&!bitmap.isRecycled());return bitmap;}
    private void load(NativeImages images,String url,Results result)throws Exception{main(()->{images.loadDrawable(url,false,result);return null;});}
    private void settled(NativeImages images,String key)throws Exception{await(()->images.state().contains("media=0 ")&&images.state().contains("mediaListeners=0 "),key);}
    private void caseDone(String name)throws Exception{cases.put(name);}

    @Override public void onStart(){
        boolean passed=false,clean=true;String failure="NONE";
        try{
            check("explicitSyntheticCacheOnly",authorized);
            check("targetsOwnHelperPackage",getTargetContext().getPackageName().equals("cn.ngapure.cacheprobe"));
            check("noInternetPermission",getTargetContext().checkSelfPermission(Manifest.permission.INTERNET)==PackageManager.PERMISSION_DENIED);
            try{check("uidDiffersFromRealClient",getTargetContext().getApplicationInfo().uid!=getTargetContext().getPackageManager().getApplicationInfo("cn.ngapure.app",0).uid);}catch(PackageManager.NameNotFoundException absent){report.put("realClientInstalled",false);}
            JSONObject binding=new JSONObject(new String(asset("binding.json"),StandardCharsets.UTF_8));report.put("binding",binding);
            check("immutableCandidateBinding",binding.getString("productJarSha256").matches("[0-9a-f]{64}")&&binding.getString("productApkSha256").matches("[0-9a-f]{64}")&&!binding.getBoolean("productionClassesRewritten"));
            root=Files.createTempDirectory(getTargetContext().getCacheDir().toPath(),"cache-management-fixture-").toFile();
            Bitmap original=Bitmap.createBitmap(WIDTH,HEIGHT,Bitmap.Config.ARGB_8888);original.eraseColor(COLOR);ByteArrayOutputStream encoded=new ByteArrayOutputStream();check("actualAndroidPngEncoder",original.compress(Bitmap.CompressFormat.PNG,100,encoded));png=encoded.toByteArray();original.recycle();
            installTransport();
            activity=(CacheHostActivity)startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(),CacheHostActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));check("ownActivityStarted",activity!=null);
            selectiveClearAndVisibleBitmap();queuedAndBlockedMedia();blockedAvatar();sharedOwnerGeneration();readOnlyFailureAndRetry();linkedRootRejected();
            for(Resource value:resources.values())check("anonymousLocalTransport"+assertions,value.anonymous);
            check("noNonFixtureTransportAttempt",rejectedNonFixtureRequests.get()==0);
            passed=true;
        }catch(Exception|AssertionError problem){failure=problem.getMessage()==null?problem.getClass().getSimpleName():problem.getMessage();try{report.put("failureClass",problem.getClass().getSimpleName());}catch(Exception ignored){ }}
        finally{
            for(Resource resource:resources.values())resource.release.countDown();
            try{main(()->{for(NativeImages value:loaders)value.close();if(activity!=null){activity.image.setImageDrawable(null);activity.finish();}return null;});if(root!=null)deleteOwnFixture(root);}catch(Exception|AssertionError problem){clean=false;}
            try{report.put("passed",passed&&clean).put("cleanupPassed",clean).put("failure",failure).put("stage",stage).put("checks",checks).put("cases",cases).put("assertionCount",assertions).put("actualAndroidPngCodec",true).put("androidDecoderStub",false).put("syntheticMediaOnly",true).put("mainAccountRead",false).put("mainAppDataCopied",false).put("realNetworkRequests",0).put("communityWrites",0).put("rejectedNonFixtureRequests",rejectedNonFixtureRequests.get());}catch(Exception ignored){ }
        }
        Bundle result=new Bundle();result.putString("safe_cache_management_report",report.toString());finish(Activity.RESULT_OK,result);
    }
    private void selectiveClearAndVisibleBitmap()throws Exception{
        stage="selectiveClearAndVisibleBitmap";File directory=directory("selective");NativeImages images=loader(directory);Results result=new Results();String url=resource("visible.png",false);load(images,url,result);await(()->result.count()==1,"visiblePngDelivered");settled(images,"visibleMediaSettled");Bitmap visible=bitmap(result,0);
        main(()->{activity.image.setImageDrawable(result.values.get(0));return null;});waitForIdleSync();check("bitmapAttachedToRealImageView",main(()->activity.image.isAttachedToWindow()&&activity.image.getDrawable()==result.values.get(0)));
        NativeImages.CacheStats first=images.cacheStats();check("actualCacheSizes",first.diskAccessible&&first.diskEntries==1&&first.diskBytes==png.length+16L&&first.memoryBytes>=visible.getAllocationByteCount());
        File unknown=new File(directory,"local-state.json"),nested=new File(directory,"m-"+repeat('f',64)+".bin"),pending=new File(directory,".pending-crashed.tmp"),outside=new File(root,"outside-preserved.bin"),link=new File(directory,"m-"+repeat('e',64)+".bin");
        Files.write(unknown.toPath(),new byte[]{1,2,3});check("cacheShapedNestedDirectoryCreated",nested.mkdir());Files.write(new File(nested,"keep.txt").toPath(),new byte[]{4,5});Files.write(pending.toPath(),new byte[]{6,7,8,9,10});Files.write(outside.toPath(),new byte[]{11,12,13,14});Files.createSymbolicLink(link.toPath(),outside.toPath());
        NativeImages.CacheStats before=images.cacheStats();check("countOnlyOwnedRegularFiles",before.diskEntries==2&&before.diskBytes==png.length+21L);
        NativeImages.CacheClearResult clear=images.clearCaches();check("clearReportsActualDeletedBytes",clear.removedDiskEntries==2&&clear.removedDiskBytes==png.length+21L&&clear.failedDiskEntries==0);
        check("clearEmptiesMemoryAndDisk",clear.remaining.diskAccessible&&clear.remaining.memoryBytes==0&&clear.remaining.diskEntries==0&&clear.remaining.diskBytes==0&&!images.hasLocalMediaCopy(url));
        check("unrelatedFileAndDirectoryPreserved",unknown.length()==3&&new File(nested,"keep.txt").length()==2);check("symbolicLinkAndTargetPreserved",Files.isSymbolicLink(link.toPath())&&outside.length()==4);
        check("visibleBitmapNotRecycled",!visible.isRecycled()&&visible.getPixel(4,4)==COLOR);check("imageViewRetainsSameDisplayedDrawable",main(()->activity.image.getDrawable()==result.values.get(0)));
        load(images,url,result);await(()->result.count()==2,"freshPngAfterClearDelivered");settled(images,"freshMediaSettled");check("newWorkCachesAgain",resources.get(url).connections.get()==2&&images.cacheStats().diskEntries==1&&images.cacheStats().memoryBytes>0);check("oldDisplayedBitmapStillValidAfterReload",!visible.isRecycled()&&visible.getPixel(4,4)==COLOR);caseDone(stage);
    }
    private void queuedAndBlockedMedia()throws Exception{
        stage="queuedAndBlockedMedia";NativeImages images=loader(directory("queued"));Results result=new Results();String a=resource("queued-a.png",true),b=resource("queued-b.png",true),c=resource("queued-c.png",false);
        load(images,a,result);load(images,b,result);check("twoMediaWorkersBlocked",resources.get(a).entered.await(3,TimeUnit.SECONDS)&&resources.get(b).entered.await(3,TimeUnit.SECONDS));load(images,c,result);check("thirdMediaTaskQueuedBeforeClear",resources.get(c).connections.get()==0&&images.state().contains("queue=1 "));
        NativeImages.CacheClearResult clear=images.clearCaches();check("blockedClearInitiallyEmpty",clear.remaining.memoryBytes==0&&clear.remaining.diskBytes==0);
        resources.get(a).release.countDown();resources.get(b).release.countDown();await(()->result.count()==3,"oldRunningAndQueuedTasksStillDeliver");settled(images,"oldTasksSettled");check("oldTasksAllSucceedOnMain",result.failures.get()==0&&result.mainOnly&&resources.get(c).connections.get()==1);for(int i=0;i<3;i++)bitmap(result,i);
        NativeImages.CacheStats after=images.cacheStats();check("noPreClearMediaTaskRepopulates",after.memoryBytes==0&&after.diskBytes==0&&after.diskEntries==0&&!images.hasLocalMediaCopy(a)&&!images.hasLocalMediaCopy(b)&&!images.hasLocalMediaCopy(c));
        load(images,c,result);await(()->result.count()==4,"freshQueuedUrlDelivered");settled(images,"freshQueuedUrlSettled");check("freshTaskCanCacheAfterOldJobsFinish",resources.get(c).connections.get()==2&&images.cacheStats().diskEntries==1&&images.cacheStats().memoryBytes>0);caseDone(stage);
    }
    private void blockedAvatar()throws Exception{
        stage="blockedAvatar";NativeImages images=loader(directory("avatar"));Avatars result=new Avatars();String url=resource("avatar.png",true);main(()->{images.loadAvatar(url,result);return null;});check("avatarBlockedBeforeClear",resources.get(url).entered.await(3,TimeUnit.SECONDS));images.clearCaches();resources.get(url).release.countDown();await(()->result.count()==1,"oldAvatarStillDelivers");check("oldAvatarDoesNotRepopulateLru",result.failures.get()==0&&result.mainOnly&&images.cacheStats().memoryBytes==0&&!result.values.get(0).isRecycled());
        main(()->{images.loadAvatar(url,result);return null;});await(()->result.count()==2,"freshAvatarDelivered");check("freshAvatarCachesNormally",result.failures.get()==0&&resources.get(url).connections.get()==2&&images.cacheStats().memoryBytes>0);caseDone(stage);
    }
    private void sharedOwnerGeneration()throws Exception{
        stage="sharedOwnerGeneration";File directory=directory("shared");NativeImages first=loader(directory),second=loader(directory);String url=resource("shared.png",true);Results result=new Results();load(first,url,result);check("otherOwnerTaskBlocked",resources.get(url).entered.await(3,TimeUnit.SECONDS));second.clearCaches();resources.get(url).release.countDown();await(()->result.count()==1,"otherOwnerOldResultDelivered");settled(first,"otherOwnerOldResultSettled");check("sharedClearBlocksOldWriteback",first.cacheStats().memoryBytes==0&&first.cacheStats().diskEntries==0&&second.cacheStats().diskEntries==0&&!first.hasLocalMediaCopy(url)&&!second.hasLocalMediaCopy(url));
        load(first,url,result);await(()->result.count()==2,"sharedFreshResultDelivered");settled(first,"sharedFreshResultSettled");check("freshDiskMetadataVisibleToOtherOwner",first.hasLocalMediaCopy(url)&&second.hasLocalMediaCopy(url)&&second.cacheStats().diskEntries==1);second.clearCaches();check("otherOwnerClearRevokesBothMetadataSets",!first.hasLocalMediaCopy(url)&&!second.hasLocalMediaCopy(url)&&first.cacheStats().diskEntries==0);caseDone(stage);
    }
    private void readOnlyFailureAndRetry()throws Exception{
        stage="readOnlyFailureAndRetry";File directory=directory("readonly");NativeImages images=loader(directory);Results result=new Results();String url=resource("readonly.png",false);load(images,url,result);await(()->result.count()==1,"readonlyFixturePngDelivered");settled(images,"readonlyFixtureSettled");
        try{Os.chmod(directory.getAbsolutePath(),0500);NativeImages.CacheClearResult clear=images.clearCaches();check("failedDeletionReported",clear.failedDiskEntries==1&&clear.removedDiskEntries==0&&clear.removedDiskBytes==0);check("failedDeletionRemainingBytesAccurate",clear.remaining.diskAccessible&&clear.remaining.diskEntries==1&&clear.remaining.diskBytes==png.length+16L&&clear.remaining.memoryBytes==0);check("failedDeletionRevokesRetirementMetadata",!images.hasLocalMediaCopy(url));}finally{Os.chmod(directory.getAbsolutePath(),0700);}
        NativeImages.CacheClearResult retry=images.clearCaches();check("clearRetryAfterPermissionRestored",retry.failedDiskEntries==0&&retry.removedDiskEntries==1&&retry.remaining.diskEntries==0);caseDone(stage);
    }
    private void linkedRootRejected()throws Exception{
        stage="linkedRootRejected";File target=directory("linked-target"),marker=new File(target,"m-"+repeat('c',64)+".bin"),link=new File(root,"linked-cache");Files.write(marker.toPath(),new byte[]{1,2,3,4,5,6});Files.createSymbolicLink(link.toPath(),target.toPath());NativeImages images=loader(link);check("linkedRootStatsUnavailable",!images.cacheStats().diskAccessible);NativeImages.CacheClearResult clear=images.clearCaches();check("linkedRootNotTraversedDuringClear",!clear.remaining.diskAccessible&&clear.removedDiskEntries==0&&Files.isSymbolicLink(link.toPath())&&marker.length()==6);caseDone(stage);
    }
    private static String repeat(char value,int count){StringBuilder text=new StringBuilder(count);for(int i=0;i<count;i++)text.append(value);return text.toString();}
    private static void deleteOwnFixture(File file)throws IOException{if(!Files.isSymbolicLink(file.toPath())&&Files.isDirectory(file.toPath(),LinkOption.NOFOLLOW_LINKS)){File[] children=file.listFiles();if(children==null)throw new IOException("Cannot enumerate own fixture");for(File child:children)deleteOwnFixture(child);}Files.deleteIfExists(file.toPath());}
    private void installTransport(){URL.setURLStreamHandlerFactory(protocol->("https".equals(protocol)||"http".equals(protocol))?new URLStreamHandler(){
        @Override protected URLConnection openConnection(URL url)throws IOException{
            Resource source=resources.get(url.toString());if(source==null){rejectedNonFixtureRequests.incrementAndGet();throw new IOException("Only generated in-memory PNG fixtures are allowed");}source.connections.incrementAndGet();
            return new HttpURLConnection(url){
                @Override public int getResponseCode()throws IOException{source.anonymous&=getRequestProperty("Cookie")==null&&getRequestProperty("Authorization")==null;source.entered.countDown();try{if(!source.release.await(10,TimeUnit.SECONDS))throw new IOException("Local PNG gate timed out");}catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new IOException("Local PNG gate cancelled",cancelled);}return 200;}
                @Override public InputStream getInputStream(){return new ByteArrayInputStream(source.bytes);}
                @Override public long getContentLengthLong(){return source.bytes.length;}
                @Override public void connect(){}
                @Override public void disconnect(){source.release.countDown();}
                @Override public boolean usingProxy(){return false;}
            };
        }
    }:null);}
}
