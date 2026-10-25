package cn.ngapure.app;

import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Holds the main callback queue to measure decoded animation work pending actual view demand. */
public final class DecodeBackpressureTest {
    private static final AtomicInteger decodes=new AtomicInteger(),fetches=new AtomicInteger(),failures=new AtomicInteger();
    private static final List<Drawable> delivered=new ArrayList<>();
    private static volatile CountDownLatch transportGate=new CountDownLatch(1);
    private static int boundaryCases;
    private static final class Results implements NativeImages.DrawableCallback {
        final List<Drawable> values=new ArrayList<>();int failures;
        @Override public void loaded(Drawable value){values.add(value);}@Override public void failed(String message){failures++;}int count(){return values.size()+failures;}
    }
    private static NativeImages fresh(){decodes.set(0);fetches.set(0);transportGate=new CountDownLatch(1);ImageDecoder.beforeDecode=decodes::incrementAndGet;return new NativeImages();}
    private static List<NativeImages.Request> subscribe(NativeImages n,int count,String suffix,Results result){List<NativeImages.Request> requests=new ArrayList<>();for(int i=0;i<count;i++)requests.add(n.loadDrawable("https://img.nga.cn/attachments/"+suffix,false,result));return requests;}
    private static void ready(int count)throws Exception{transportGate.countDown();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(decodes.get()<count&&System.nanoTime()<end)Thread.sleep(2);Thread.sleep(50);if(decodes.get()!=count)throw new AssertionError("decoded ahead of main: expected "+count+" got "+decodes.get());}
    private static void finish(Results result,int count)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(result.count()<count&&System.nanoTime()<end){Handler.drain();Thread.sleep(2);}Handler.drain();if(result.count()!=count)throw new AssertionError("delivery count "+count+" got "+result.count());}
    private static java.util.concurrent.ThreadPoolExecutor pool(NativeImages n)throws Exception{java.lang.reflect.Field field=NativeImages.class.getDeclaredField("workers");field.setAccessible(true);return(java.util.concurrent.ThreadPoolExecutor)field.get(n);}
    private static void cancelOneQueued()throws Exception{NativeImages n=fresh();Results r=new Results();try{List<NativeImages.Request> q=subscribe(n,30,"cancel-one.gif",r);ready(1);q.get(0).cancel();finish(r,29);if(r.failures!=0||fetches.get()!=1)throw new AssertionError("cancelling acknowledged subscriber must unblock siblings");boundaryCases++;}finally{n.close();}}
    private static void cancelWholeGroup()throws Exception{NativeImages n=fresh();Results r=new Results();try{List<NativeImages.Request> q=subscribe(n,30,"cancel-all.gif",r);ready(1);for(NativeImages.Request request:q)request.cancel();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(pool(n).getActiveCount()>0&&System.nanoTime()<end)Thread.sleep(2);Handler.drain();if(pool(n).getActiveCount()!=0||r.count()!=0||!n.state().contains("mediaListeners=0 "))throw new AssertionError("last cancellation must interrupt acknowledgement wait");boundaryCases++;}finally{n.close();}}
    private static void closeWaiting()throws Exception{NativeImages n=fresh();Results r=new Results();subscribe(n,30,"close.gif",r);ready(1);n.close();if(!pool(n).awaitTermination(2,TimeUnit.SECONDS))throw new AssertionError("close cannot leave worker blocked for main acknowledgement");Handler.drain();if(r.count()!=0)throw new AssertionError("close callbacks suppressed");boundaryCases++;}
    private static void twoGroups()throws Exception{NativeImages n=fresh();Results r=new Results();try{subscribe(n,15,"group-a.gif",r);subscribe(n,15,"group-b.gif",r);ready(2);finish(r,30);if(fetches.get()!=2||r.failures!=0)throw new AssertionError("two bounded workers progress");boundaryCases++;}finally{n.close();}}
    private static void staticGroup()throws Exception{NativeImages n=fresh();Results r=new Results();try{subscribe(n,30,"static.png",r);ready(1);if(!n.state().contains("media=0 "))throw new AssertionError("static shared bitmap path must not wait for callback acknowledgement");finish(r,30);if(fetches.get()!=1||r.failures!=0)throw new AssertionError("static deliveries unchanged");boundaryCases++;}finally{n.close();}}
    private static void staticNonBitmapGroup()throws Exception{NativeImages n=fresh();Results r=new Results();try{subscribe(n,30,"nonbitmap.png",r);ready(30);if(!n.state().contains("media=0 "))throw new AssertionError("non-Bitmap static drawable path must not wait");finish(r,30);if(fetches.get()!=1||r.failures!=0)throw new AssertionError("non-Bitmap static deliveries unchanged");boundaryCases++;}finally{n.close();}}
    private static void lateJoinWhileWaiting()throws Exception{NativeImages n=fresh();Results r=new Results();try{subscribe(n,10,"late.gif",r);ready(1);subscribe(n,1,"late.gif",r);finish(r,11);if(fetches.get()!=1||r.failures!=0)throw new AssertionError("late subscriber reuses bytes during acknowledgement wait");boundaryCases++;}finally{n.close();}}
    private static void failureAfterAcknowledgement()throws Exception{NativeImages n=fresh();Results r=new Results();ImageDecoder.beforeDecode=()->{if(decodes.incrementAndGet()==2)throw new IllegalArgumentException("Synthetic decoder failure");};try{subscribe(n,30,"decode-failure.gif",r);ready(1);finish(r,30);if(r.values.size()!=1||r.failures!=29||!n.state().contains("mediaListeners=0 "))throw new AssertionError("decode failure releases all waiting subscribers");boundaryCases++;}finally{n.close();}}
    public static void main(String[] args)throws Exception {
        boolean expectBounded=args.length==0||Boolean.parseBoolean(args[0]);Build.VERSION.SDK_INT=35;
        URL.setURLStreamHandlerFactory(protocol->!protocol.equals("https")?null:new URLStreamHandler(){
            @Override protected URLConnection openConnection(URL url){fetches.incrementAndGet();return new HttpURLConnection(url){
                @Override public int getResponseCode()throws java.io.IOException{try{transportGate.await();}catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new java.io.IOException(cancelled);}return 200;}@Override public long getContentLengthLong(){return 1;}@Override public InputStream getInputStream(){return new ByteArrayInputStream(new byte[]{(byte)(url.getPath().contains("nonbitmap")?3:url.getPath().contains("static")?1:2)});}
                @Override public void disconnect(){}@Override public void connect(){}@Override public boolean usingProxy(){return false;}
            };}
        });
        ImageDecoder.beforeDecode=decodes::incrementAndGet;NativeImages images=new NativeImages();
        try {
            for(int i=0;i<30;i++)images.loadDrawable("https://img.nga.cn/attachments/demand.gif",false,new NativeImages.DrawableCallback(){@Override public void loaded(Drawable value){delivered.add(value);}@Override public void failed(String message){failures.incrementAndGet();}});
            transportGate.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(decodes.get()==0&&System.nanoTime()<deadline)Thread.sleep(2);Thread.sleep(80);int beforeDrain=decodes.get();
            if(expectBounded&&beforeDrain!=1)throw new AssertionError("Expected one animation decoded before its main callback is acknowledged, got "+beforeDrain);
            if(!expectBounded&&beforeDrain!=30)throw new AssertionError("Expected baseline eager batch of 30, got "+beforeDrain);
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(delivered.size()+failures.get()<30&&System.nanoTime()<deadline){Handler.drain();Thread.sleep(2);}Handler.drain();
            if(delivered.size()!=30||failures.get()!=0||fetches.get()!=1)throw new AssertionError("Delivery/fetch regression");
            int initialFetches=fetches.get();
            if(expectBounded){cancelOneQueued();cancelWholeGroup();closeWaiting();twoGroups();staticGroup();staticNonBitmapGroup();lateJoinWhileWaiting();failureAfterAcknowledgement();}
            System.out.println("{\"passed\":true,\"expectedBackpressure\":"+expectBounded+",\"subscribers\":30,\"decodesBeforeMainDrain\":"+beforeDrain+",\"delivered\":"+delivered.size()+",\"fetches\":"+initialFetches+",\"threadAndCancellationBoundaryCases\":"+boundaryCases+",\"androidDecoderStub\":true}");
        }finally{images.close();ImageDecoder.beforeDecode=null;}
    }
}
