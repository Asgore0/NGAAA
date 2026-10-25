package cn.ngapure.imagesavefixturev195;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Non-exported, same-UID pipe provider. Bytes go to a digest in memory, never to a user file. */
public final class ImageSaveProvider extends ContentProvider {
    static final Map<String,Gate> GATES=new ConcurrentHashMap<>();
    static Gate register(String id,byte[] expected)throws Exception{Gate gate=new Gate(expected);GATES.put(id,gate);return gate;}
    static Uri uri(String id){return Uri.parse("content://cn.ngapure.imagesavefixturev195.sink/sink/"+id);}
    static final class Gate {
        final int expectedBytes;final byte[] expectedDigest;
        final CountDownLatch opened=new CountDownLatch(1),firstChunk=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
        final AtomicInteger opens=new AtomicInteger();volatile long received;volatile boolean exact,openOnMain,receiverClosed;volatile String error="NONE";volatile Thread openThread;
        Gate(byte[] expected)throws Exception{expectedBytes=expected.length;expectedDigest=MessageDigest.getInstance("SHA-256").digest(expected);}
    }
    @Override public boolean onCreate(){return true;}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        final Gate gate=GATES.get(uri.getLastPathSegment());if(gate==null||!("w".equals(mode)||"wt".equals(mode)))throw new FileNotFoundException("Only registered synthetic sinks are supported");
        gate.openThread=Thread.currentThread();gate.openOnMain=Looper.myLooper()==Looper.getMainLooper();gate.opens.incrementAndGet();gate.opened.countDown();
        try{
            final ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createReliablePipe();Thread receiver=new Thread(()->{
                try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])){
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[4096];int count=input.read(buffer);
                    if(count>0){gate.received+=count;digest.update(buffer,0,count);}gate.firstChunk.countDown();
                    if(!gate.release.await(30,TimeUnit.SECONDS))throw new IOException("Synthetic slow provider release timeout");
                    while((count=input.read(buffer))!=-1){gate.received+=count;digest.update(buffer,0,count);}
                    gate.exact=gate.received==gate.expectedBytes&&Arrays.equals(digest.digest(),gate.expectedDigest);
                }catch(Exception failure){gate.error=failure.getClass().getSimpleName();}
                finally{gate.receiverClosed=true;gate.finished.countDown();}
            },"SyntheticImageSink");receiver.setDaemon(true);receiver.start();return pipe[1];
        }catch(IOException unavailable){throw new FileNotFoundException("Synthetic pipe unavailable");}
    }
    @Override public String getType(Uri uri){return "image/bmp";}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] arguments,String order){return null;}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("No insertion");}
    @Override public int delete(Uri uri,String selection,String[] arguments){throw new UnsupportedOperationException("No deletion");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] arguments){throw new UnsupportedOperationException("No update");}
}
