package cn.ngapure.app;
import android.os.Handler;
import android.os.Build;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real production loader, simulated Android decode + fully in-memory HTTPS. No real I/O. */
public final class InlineMediaSharedTest {
    static final Map<String,Resource> resources=new ConcurrentHashMap<>();
    static int assertions;
    static class Resource {
        final CountDownLatch release=new CountDownLatch(1), entered=new CountDownLatch(1);
        final AtomicInteger connections=new AtomicInteger(),disconnects=new AtomicInteger();
        final int marker,status;
        Resource(int marker,int status,boolean blocked){this.marker=marker;this.status=status;if(!blocked)release.countDown();}
    }
    static class Results implements NativeImages.DrawableCallback {
        final List<Drawable> drawables=Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger failure=new AtomicInteger(),busy=new AtomicInteger();
        public void loaded(Drawable d){drawables.add(d);}
        public void failed(String s){failure.incrementAndGet();if(s.equals("图片加载繁忙"))busy.incrementAndGet();}
        int count(){return drawables.size()+failure.get();}
    }
    static String setup(String name,int marker,int status,boolean blocked){String u="https://img4.nga.cn/"+name;resources.put(u,new Resource(marker,status,blocked));return u;}
    static Resource resource(String u){return resources.get(u);}
    static void check(boolean yes,String note){assertions++;if(!yes)throw new AssertionError(note);}
    static void finish(Results r,int count)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(r.count()<count&&System.nanoTime()<until){Handler.drain();Thread.sleep(2);}Handler.drain();check(r.count()==count,"result count "+count+" actual "+r.count());}
    static void started(String u)throws Exception {check(resource(u).entered.await(2,TimeUnit.SECONDS),"worker entered");}
    static int listeners(NativeImages images){String state=images.state();return Integer.parseInt(state.split("mediaListeners=")[1].split(" ")[0]);}
    static void empty(NativeImages images)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(listeners(images)!=0&&System.nanoTime()<until){Handler.drain();Thread.sleep(2);}check(listeners(images)==0,"no retained subscribers");}
    public static void main(String[] args)throws Exception {
        URL.setURLStreamHandlerFactory(p->!p.equals("https")?null:new URLStreamHandler(){
            protected URLConnection openConnection(URL u)throws IOException {Resource r=resources.get(u.toString());if(r==null)throw new AssertionError("unexpected transport "+u);r.connections.incrementAndGet();return new HttpURLConnection(u){
                public int getResponseCode()throws IOException{if(getRequestProperty("Cookie")!=null||getRequestProperty("Authorization")!=null)throw new AssertionError("image transport leaked account header");r.entered.countDown();try{r.release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}return r.status;}
                public InputStream getInputStream(){return new ByteArrayInputStream(new byte[]{(byte)r.marker});}
                public long getContentLengthLong(){return 1;}
                public void disconnect(){r.disconnects.incrementAndGet();}public void connect(){}public boolean usingProxy(){return false;}
            };}
        });
        duplicateStatic();duplicateAnimated();independentCancel();lastCancel();lateJoin();lateJoinDuringDecode();closeDrops();decodeKeys();bounds();permanentFailure();queuedCancelAndClose();busyRecovery();uniqueBounds();diskReload();diskAnimations();corruptDiskRecovery();unavailableDisk();cacheManagement();clearDuringMediaFetch();clearDuringAvatarFetch();otherOwnerClearDuringFetch();clearBeforeQueuedFetch();
        System.out.println("PASS shared inline media: 22 scenarios, "+assertions+" checks; no network/account/device; Android decode is a stub");
    }
    static void duplicateStatic()throws Exception {
        Build.VERSION.SDK_INT=27;String u=setup("static30",1,200,true);NativeImages n=new NativeImages();Results r=new Results();
        for(int i=0;i<30;i++)n.loadDrawable(u,false,r);started(u);check(listeners(n)==30,"30 subscribers share bounded job");resource(u).release.countDown();finish(r,30);
        check(resource(u).connections.get()==1,"30 static views fetch once");check(r.failure.get()==0,"no false failure");check(new HashSet<>(r.drawables).size()==30,"static bounds/callback wrappers independent");
        Bitmap first=((android.graphics.drawable.BitmapDrawable)r.drawables.get(0)).getBitmap();for(Drawable d:r.drawables)check(((android.graphics.drawable.BitmapDrawable)d).getBitmap()==first,"static bitmap shared");empty(n);n.close();
    }
    static void duplicateAnimated()throws Exception {
        Build.VERSION.SDK_INT=35;String u=setup("animated30",2,200,true);NativeImages n=new NativeImages();Results r=new Results();for(int i=0;i<30;i++)n.loadDrawable(u,false,r);started(u);resource(u).release.countDown();finish(r,30);
        check(resource(u).connections.get()==1,"animation bytes fetched once");check(new HashSet<>(r.drawables).size()==30,"each animated drawable independent");for(Drawable d:r.drawables)check(d instanceof android.graphics.ImageDecoder.TestAnimation,"native animation kind");empty(n);n.close();
    }
    static void independentCancel()throws Exception {
        String u=setup("cancel-one",2,200,true);NativeImages n=new NativeImages();Results a=new Results(),b=new Results();NativeImages.Request pending=n.loadDrawable(u,false,a);n.loadDrawable(u,false,b);started(u);pending.cancel();resource(u).release.countDown();finish(b,1);check(a.count()==0,"cancelled subscriber not called");check(resource(u).connections.get()==1,"sibling keeps shared fetch");empty(n);n.close();
    }
    static void lastCancel()throws Exception {
        String u=setup("cancel-last",2,200,true);NativeImages n=new NativeImages();Results r=new Results();NativeImages.Request pending=n.loadDrawable(u,false,r);started(u);pending.cancel();Thread.sleep(20);Handler.drain();check(r.count()==0,"last cancellation silent");empty(n);check(n.state().contains("media=0 "),"last listener releases job");n.close();
    }
    static void lateJoin()throws Exception {
        String u=setup("late-join",2,200,true);NativeImages n=new NativeImages();Results r=new Results();n.loadDrawable(u,false,r);started(u);n.loadDrawable(u,false,r);resource(u).release.countDown();finish(r,2);check(resource(u).connections.get()==1,"late subscriber shares response");empty(n);n.close();
    }
    static void lateJoinDuringDecode()throws Exception {
        String u=setup("late-during-decode",2,200,false);NativeImages n=new NativeImages();Results r=new Results();
        CountDownLatch decoding=new CountDownLatch(1),resume=new CountDownLatch(1);AtomicInteger decodes=new AtomicInteger();
        android.graphics.ImageDecoder.beforeDecode=()->{if(decodes.incrementAndGet()==1){decoding.countDown();try{resume.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}};
        try {n.loadDrawable(u,false,r);check(decoding.await(2,TimeUnit.SECONDS),"decode phase reached");n.loadDrawable(u,false,r);resume.countDown();finish(r,2);check(resource(u).connections.get()==1,"decode-phase late join reuses downloaded bytes");check(decodes.get()==2,"animated late join decoded independently");}finally{resume.countDown();android.graphics.ImageDecoder.beforeDecode=null;n.close();}
    }
    static void closeDrops()throws Exception {
        String u=setup("close",2,200,true);NativeImages n=new NativeImages();Results r=new Results();for(int i=0;i<20;i++)n.loadDrawable(u,false,r);started(u);n.close();resource(u).release.countDown();Thread.sleep(20);Handler.drain();check(r.count()==0,"close drops callbacks");check(listeners(n)==0,"close drops subscribers");
    }
    static void decodeKeys()throws Exception {
        String u=setup("keys",1,200,true);NativeImages n=new NativeImages();Results r=new Results();n.loadDrawable(u,false,r);n.loadDrawable(u,true,r);started(u);resource(u).release.countDown();finish(r,2);check(resource(u).connections.get()==2,"full/inline separate decode jobs");empty(n);n.close();
    }
    static void bounds()throws Exception {
        NativeImages n=new NativeImages();Results r=new Results();String a=setup("boundA",2,200,true),b=setup("boundB",2,200,true),c=setup("boundC",2,200,true);
        for(int i=0;i<16;i++){n.loadDrawable(a,false,r);n.loadDrawable(b,false,r);}n.loadDrawable(a,false,r);n.loadDrawable(c,false,r);Handler.drain();check(listeners(n)==32,"global active work bounded32");check(r.busy.get()==2,"perjob/global saturation distinguished busy");n.close();resource(a).release.countDown();resource(b).release.countDown();resource(c).release.countDown();Handler.drain();
    }
    static void queuedCancelAndClose()throws Exception {
        String u=setup("queued-cancel",2,200,false);NativeImages n=new NativeImages();Results r=new Results();NativeImages.Request request=n.loadDrawable(u,false,r);waitPosted(n);request.cancel();Handler.drain();check(r.count()==0,"cancel suppresses queued delivery");empty(n);n.close();
        u=setup("queued-close",2,200,false);n=new NativeImages();r=new Results();n.loadDrawable(u,false,r);waitPosted(n);n.close();Handler.drain();check(r.count()==0,"close suppresses queued delivery");
    }
    static void waitPosted(NativeImages n)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!n.state().contains("media=0 ")&&System.nanoTime()<until)Thread.sleep(2);check(n.state().contains("media=0 ")&&listeners(n)>0,"worker finished with delivery still queued");}
    static void busyRecovery()throws Exception {
        NativeImages n=new NativeImages();Results full=new Results(),retry=new Results();String a=setup("recover-a",2,200,true),b=setup("recover-b",2,200,true),c=setup("recover-c",2,200,false);List<NativeImages.Request> group=new ArrayList<>();
        for(int i=0;i<16;i++){group.add(n.loadDrawable(a,false,full));n.loadDrawable(b,false,full);}n.loadDrawable(c,false,retry);Handler.drain();check(retry.busy.get()==1,"temporary saturation reported");for(NativeImages.Request q:group)q.cancel();n.loadDrawable(c,false,retry);resource(a).release.countDown();resource(b).release.countDown();finish(retry,2);check(retry.drawables.size()==1,"retry after capacity returns succeeds");check(resource(c).connections.get()==1,"busy rejection never fetched");n.close();
    }
    static void uniqueBounds()throws Exception {
        NativeImages n=new NativeImages();Results r=new Results();List<String> urls=new ArrayList<>();for(int i=0;i<25;i++){String u=setup("unique-"+i,2,200,true);urls.add(u);n.loadDrawable(u,false,r);}Handler.drain();check(n.state().contains("media=24 "),"24 unique jobs bounded");check(r.busy.get()==1,"25th unique job temporary busy");n.close();for(String u:urls)resource(u).release.countDown();Handler.drain();check(listeners(n)==0,"close clears bounded jobs");
    }
    static void diskReload()throws Exception {
        String u=setup("disk-static",1,200,false);java.io.File directory=java.nio.file.Files.createTempDirectory("ngapure-loader-disk-").toFile();NativeImages n=new NativeImages();n.setMediaCacheDirectory(directory);Results r=new Results();
        try {n.loadDrawable(u,false,r);finish(r,1);check(n.hasLocalMediaCopy(u),"successful decode and disk write grants retirement");Drawable first=r.drawables.get(0);n.trimMemory(40);n.loadDrawable(u,false,r);finish(r,2);check(resource(u).connections.get()==1,"trimmed bitmap reload uses local original bytes");check(first!=r.drawables.get(1),"local reload has independent drawable wrapper");n.close();n=new NativeImages();n.setMediaCacheDirectory(directory);n.loadDrawable(u,true,r);finish(r,3);check(resource(u).connections.get()==1,"new owner and full decode key reuse original bytes");check(n.hasLocalMediaCopy(u),"verified reopened cache grants retirement");}finally{n.close();delete(directory);}
    }
    static void diskAnimations()throws Exception {
        String u=setup("disk-animation",2,200,false);java.io.File directory=java.nio.file.Files.createTempDirectory("ngapure-loader-animation-").toFile();NativeImages n=new NativeImages();n.setMediaCacheDirectory(directory);Results r=new Results();
        try{n.loadDrawable(u,false,r);finish(r,1);n.loadDrawable(u,false,r);finish(r,2);check(resource(u).connections.get()==1,"sequential animation views reuse local original");check(r.drawables.get(0)!=r.drawables.get(1),"cached encoded animation still decodes distinct mutable instances");}finally{n.close();delete(directory);}
    }
    static void corruptDiskRecovery()throws Exception {
        String u=setup("disk-corrupt",1,200,false);java.io.File directory=java.nio.file.Files.createTempDirectory("ngapure-loader-corrupt-").toFile();NativeImages n=new NativeImages();n.setMediaCacheDirectory(directory);Results r=new Results();
        try {n.loadDrawable(u,false,r);finish(r,1);n.trimMemory(40);java.io.File[] entries=directory.listFiles();check(entries!=null&&entries.length==1,"corruption fixture has one disk entry");try(RandomAccessFile file=new RandomAccessFile(entries[0],"rw")){file.seek(16);file.writeByte(255);}n.loadDrawable(u,false,r);finish(r,2);check(r.failure.get()==0&&resource(u).connections.get()==2,"bad cached decode invalidated and fetched exactly once");check(n.hasLocalMediaCopy(u),"replacement verified bytes releasable");n.trimMemory(40);n.loadDrawable(u,false,r);finish(r,3);check(resource(u).connections.get()==2,"replacement local bytes reusable without repeated retry");}finally{n.close();delete(directory);}
    }
    static void unavailableDisk()throws Exception {
        String u=setup("disk-unavailable",1,200,false);java.io.File directory=java.nio.file.Files.createTempFile("ngapure-loader-file-",".tmp").toFile();NativeImages n=new NativeImages();n.setMediaCacheDirectory(directory);Results r=new Results();
        try {n.loadDrawable(u,false,r);finish(r,1);check(r.failure.get()==0&&!n.hasLocalMediaCopy(u),"storage failure keeps successful image and forbids retirement");n.trimMemory(40);n.loadDrawable(u,false,r);finish(r,2);check(resource(u).connections.get()==2,"unavailable cache falls back to ordinary media transport");}finally{n.close();delete(directory);}
    }
    static void cacheManagement()throws Exception {
        Build.VERSION.SDK_INT=27;String u=setup("managed-cache",1,200,false);java.io.File directory=java.nio.file.Files.createTempDirectory("ngapure-loader-managed-").toFile();NativeImages n=new NativeImages();n.setMediaCacheDirectory(directory);Results r=new Results();
        try {n.loadDrawable(u,false,r);finish(r,1);Bitmap visible=((android.graphics.drawable.BitmapDrawable)r.drawables.get(0)).getBitmap();NativeImages.CacheStats before=n.cacheStats();
            check(before.memoryBytes>0&&before.diskBytes==17&&before.diskEntries==1&&before.diskAccessible,"management exposes actual memory and encoded-file size");
            NativeImages.CacheClearResult result=n.clearCaches();check(result.removedDiskBytes==17&&result.removedDiskEntries==1&&result.failedDiskEntries==0,"management clear reports actual removal");
            check(result.remaining.memoryBytes==0&&result.remaining.diskBytes==0&&!n.hasLocalMediaCopy(u),"management empties LRU disk and retirement metadata");
            check(!visible.isRecycled(),"displayed bitmap never recycled by cache clear");n.loadDrawable(u,false,r);finish(r,2);check(resource(u).connections.get()==2&&n.cacheStats().diskEntries==1,"new work repopulates normally after clear");
        }finally{n.close();delete(directory);}
    }
    static void clearDuringMediaFetch()throws Exception {
        Build.VERSION.SDK_INT=27;String u=setup("clear-active-media",1,200,true);java.io.File directory=java.nio.file.Files.createTempDirectory("ngapure-loader-active-clear-").toFile();NativeImages n=new NativeImages();n.setMediaCacheDirectory(directory);Results r=new Results();
        try{n.loadDrawable(u,false,r);started(u);n.clearCaches();resource(u).release.countDown();finish(r,1);empty(n);NativeImages.CacheStats after=n.cacheStats();check(r.failure.get()==0&&after.memoryBytes==0&&after.diskBytes==0&&!n.hasLocalMediaCopy(u),"pre-clear fetch delivers without refilling cleared caches");n.loadDrawable(u,false,r);finish(r,2);check(resource(u).connections.get()==2&&n.cacheStats().memoryBytes>0&&n.cacheStats().diskEntries==1,"post-clear fetch has fresh generation");}
        finally{resource(u).release.countDown();n.close();delete(directory);}
    }
    static void clearDuringAvatarFetch()throws Exception {
        Build.VERSION.SDK_INT=27;String u=setup("clear-active-avatar",1,200,true);NativeImages n=new NativeImages();AtomicInteger delivered=new AtomicInteger();NativeImages.Callback callback=new NativeImages.Callback(){public void loaded(Bitmap b){check(!b.isRecycled(),"active avatar remains valid");delivered.incrementAndGet();}public void failed(String message){throw new AssertionError(message);}};
        try{n.loadAvatar(u,callback);started(u);n.clearCaches();resource(u).release.countDown();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(delivered.get()<1&&System.nanoTime()<until){Handler.drain();Thread.sleep(2);}check(delivered.get()==1&&n.cacheStats().memoryBytes==0,"pre-clear avatar delivers without refilling LRU");n.loadAvatar(u,callback);until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(delivered.get()<2&&System.nanoTime()<until){Handler.drain();Thread.sleep(2);}check(delivered.get()==2&&resource(u).connections.get()==2&&n.cacheStats().memoryBytes>0,"new avatar request caches normally");}
        finally{resource(u).release.countDown();n.close();}
    }
    static void otherOwnerClearDuringFetch()throws Exception {
        Build.VERSION.SDK_INT=27;String u=setup("clear-other-owner",1,200,true);java.io.File directory=java.nio.file.Files.createTempDirectory("ngapure-loader-other-clear-").toFile();NativeImages n=new NativeImages(),other=new NativeImages();n.setMediaCacheDirectory(directory);other.setMediaCacheDirectory(directory);Results r=new Results();
        try{n.loadDrawable(u,false,r);started(u);other.clearCaches();resource(u).release.countDown();finish(r,1);empty(n);check(n.cacheStats().diskBytes==0&&n.cacheStats().memoryBytes==0&&!n.hasLocalMediaCopy(u),"shared disk generation rejects older other-owner writeback");}
        finally{resource(u).release.countDown();n.close();other.close();delete(directory);}
    }
    static void clearBeforeQueuedFetch()throws Exception {
        Build.VERSION.SDK_INT=27;String a=setup("clear-queue-a",1,200,true),b=setup("clear-queue-b",1,200,true),c=setup("clear-queue-c",1,200,false);java.io.File directory=java.nio.file.Files.createTempDirectory("ngapure-loader-queue-clear-").toFile();NativeImages n=new NativeImages();n.setMediaCacheDirectory(directory);Results r=new Results();
        try{n.loadDrawable(a,false,r);n.loadDrawable(b,false,r);started(a);started(b);n.loadDrawable(c,false,r);check(resource(c).connections.get()==0,"third task queued before clear");n.clearCaches();resource(a).release.countDown();resource(b).release.countDown();finish(r,3);empty(n);check(resource(c).connections.get()==1&&n.cacheStats().memoryBytes==0&&n.cacheStats().diskBytes==0,"pre-clear queued task cannot refill after it starts");}
        finally{resource(a).release.countDown();resource(b).release.countDown();n.close();delete(directory);}
    }
    static void delete(java.io.File file){java.io.File[] children=file.listFiles();if(children!=null)for(java.io.File child:children)delete(child);if(!file.delete()&&file.exists())throw new AssertionError("temporary fixture cleanup");}
    static void permanentFailure()throws Exception {
        String u=setup("404",1,404,false);NativeImages n=new NativeImages();Results r=new Results();n.loadDrawable(u,false,r);finish(r,1);check(r.busy.get()==0&&r.failure.get()==1,"404 not temporary busy");empty(n);n.close();
    }
}
