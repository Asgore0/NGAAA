package cn.ngapure.app;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Handler;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Source geometry survives bounded decoding, cache wrappers and local re-decode. No network. */
public final class NativeSourceDimensionsTest {
    private static final Map<String,AtomicInteger> fetches=new ConcurrentHashMap<>();private static int assertions;
    private static final class Results implements NativeImages.DrawableCallback {final List<Drawable> values=new ArrayList<>();int failures;@Override public void loaded(Drawable drawable){values.add(drawable);}@Override public void failed(String message){failures++;}}
    private static void check(boolean value,String note){assertions++;if(!value)throw new AssertionError(note);}
    private static void finish(Results r,int count)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(r.values.size()+r.failures<count&&System.nanoTime()<end){Handler.drain();Thread.sleep(2);}Handler.drain();check(r.values.size()==count&&r.failures==0,"successful geometry delivery");}
    public static void main(String[] args)throws Exception {
        URL.setURLStreamHandlerFactory(protocol->!protocol.equals("https")?null:new URLStreamHandler(){@Override protected URLConnection openConnection(URL url){fetches.computeIfAbsent(url.toString(),key->new AtomicInteger()).incrementAndGet();return new HttpURLConnection(url){@Override public int getResponseCode(){if(getRequestProperty("Cookie")!=null||getRequestProperty("Authorization")!=null)throw new AssertionError("anonymous media only");return 200;}@Override public long getContentLengthLong(){return 1;}@Override public InputStream getInputStream(){int marker=Integer.parseInt(url.getPath().substring(url.getPath().lastIndexOf('/')+1));return new ByteArrayInputStream(new byte[]{(byte)marker});}@Override public void connect(){}@Override public void disconnect(){}@Override public boolean usingProxy(){return false;}};}});
        for(int sdk:new int[]{27,35})for(int[] fixture:new int[][]{{4,1080,3000},{5,2560,1440},{6,160,90},{7,1081,3001}})roundTrip(sdk,fixture);
        System.out.println("PASS native source dimensions: 8 geometry/decoder paths, "+assertions+" checks; Android decode is a stub; original dimensions are not decoded pixel budget");
    }
    private static void roundTrip(int sdk,int[] fixture)throws Exception {
        Build.VERSION.SDK_INT=sdk;String url="https://img.nga.cn/attachments/source-"+sdk+"/"+fixture[0];File directory=java.nio.file.Files.createTempDirectory("ngapure-source-size-").toFile();NativeImages n=new NativeImages();n.setMediaCacheDirectory(directory);Results r=new Results();
        try {
            n.loadDrawable(url,false,r);finish(r,1);Drawable first=r.values.get(0);int[] source=n.sourceDimensions(first);check(source[0]==fixture[1]&&source[1]==fixture[2],"logical geometry equals original header before sampling");check((long)first.getIntrinsicWidth()*first.getIntrinsicHeight()<=800000,"decoded area remains bounded");source[0]=1;check(n.sourceDimensions(first)[0]==fixture[1],"source geometry defensive copy");
            n.loadDrawable(url,false,r);finish(r,2);check(first!=r.values.get(1),"cache hit has independent mutable wrapper");check(((BitmapDrawable)first).getBitmap()==((BitmapDrawable)r.values.get(1)).getBitmap(),"static cache shares bitmap only");check(n.sourceDimensions(r.values.get(1))[0]==fixture[1]&&n.sourceDimensions(r.values.get(1))[1]==fixture[2],"new wrapper keeps original source geometry");
            n.trimMemory(40);n.loadDrawable(url,false,r);finish(r,3);check(n.sourceDimensions(r.values.get(2))[0]==fixture[1]&&n.sourceDimensions(r.values.get(2))[1]==fixture[2],"local original re-decode keeps geometry");check(fetches.get(url).get()==1,"original re-decode does not redownload");
            final Bitmap[] bitmap={null};final AtomicInteger delivered=new AtomicInteger();n.load(url,false,new NativeImages.Callback(){@Override public void loaded(Bitmap value){bitmap[0]=value;delivered.set(1);}@Override public void failed(String message){delivered.set(-1);}});long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(delivered.get()==0&&System.nanoTime()<end){Handler.drain();Thread.sleep(2);}check(delivered.get()==1,"BitmapFactory callback route succeeds");int[] raw=n.sourceDimensions(new BitmapDrawable(null,bitmap[0]));check(raw[0]==fixture[1]&&raw[1]==fixture[2],"BitmapFactory captures original before output dimensions change");
            n.close();n=new NativeImages();n.setMediaCacheDirectory(directory);n.loadDrawable(url,false,r);finish(r,4);check(n.sourceDimensions(r.values.get(3))[0]==fixture[1]&&n.sourceDimensions(r.values.get(3))[1]==fixture[2],"fresh owner restores original from disk response");check(fetches.get(url).get()==1,"fresh owner uses same verified original");
            check(n.sourceDimensions(new Drawable())[0]==10,"unknown drawable falls back to intrinsic geometry");check(n.sourceDimensions(null)[0]==0,"missing drawable has no logical dimensions");
        }finally{n.close();delete(directory);}
    }
    private static void delete(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);if(!file.delete()&&file.exists())throw new AssertionError("fixture cleanup");}
}
