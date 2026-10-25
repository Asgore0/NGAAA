package cn.ngapure.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Production login network/close paths, synthetic HTTPS only; no account, SMS or CAPTCHA solving. */
public final class AuthNetworkCancellationTest {
    private static final ConcurrentLinkedQueue<Gate> RESPONSES=new ConcurrentLinkedQueue<>();
    private static final List<Gate> GATES=new ArrayList<>();
    private static final byte[] IMAGE={71,73,70,56,57,97,0,0};
    private static int checks,scenarios,opened;
    private static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static byte[] fixture(String directory,String name)throws IOException{return Files.readAllBytes(Paths.get("tests",directory,"fixtures",name));}
    private static final class Gate {
        final byte[] body;final boolean image;
        final CountDownLatch reading=new CountDownLatch(1),releaseRead=new CountDownLatch(1),disconnectStarted=new CountDownLatch(1),releaseDisconnect=new CountDownLatch(1);
        volatile Thread disconnectThread;volatile boolean streamClosed;Runnable afterFirstRead;int reads,consumed;
        Gate(byte[] body,boolean image,boolean blockedRead,boolean slowDisconnect){this.body=body;this.image=image;if(!blockedRead)releaseRead.countDown();if(!slowDisconnect)releaseDisconnect.countDown();}
    }
    private static Gate next(byte[] body,boolean image,boolean blockedRead,boolean slowDisconnect){Gate gate=new Gate(body,image,blockedRead,slowDisconnect);GATES.add(gate);RESPONSES.add(gate);return gate;}
    private static void await(CountDownLatch latch,String label)throws Exception{ok(latch.await(2,TimeUnit.SECONDS),label);}
    private static void uninterruptible(CountDownLatch latch)throws IOException{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(latch.getCount()>0&&System.nanoTime()<until){try{latch.await(50,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){/* A socket operation need not honor thread interruption. */}}
        if(latch.getCount()>0)throw new IOException("Synthetic I/O gate timeout");
    }
    private static final class Connection extends HttpURLConnection {
        final Gate gate;
        Connection(URL url,Gate gate){super(url);this.gate=gate;}
        @Override public int getResponseCode(){return 200;}
        @Override public String getContentType(){return gate.image?"image/gif":"application/json; charset=UTF-8";}
        @Override public Map<String,List<String>> getHeaderFields(){return Collections.emptyMap();}
        @Override public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        @Override public InputStream getInputStream(){final ByteArrayInputStream bytes=new ByteArrayInputStream(gate.body);return new InputStream(){
            @Override public int read(){throw new AssertionError("Buffered read expected");}
            @Override public int read(byte[] buffer,int offset,int length)throws IOException{gate.reads++;gate.reading.countDown();uninterruptible(gate.releaseRead);if(gate.reads==1&&gate.afterFirstRead!=null)gate.afterFirstRead.run();int n=bytes.read(buffer,offset,length);if(n>0)gate.consumed+=n;return n;}
            @Override public void close(){gate.streamClosed=true;}
        };}
        @Override public void disconnect(){gate.disconnectThread=Thread.currentThread();gate.disconnectStarted.countDown();try{uninterruptible(gate.releaseDisconnect);}catch(IOException timeout){throw new IllegalStateException(timeout);}gate.releaseRead.countDown();}
        @Override public void connect(){connected=true;}
        @Override public boolean usingProxy(){return false;}
    }
    private static void promptClose(Runnable close,Gate gate)throws Exception{
        Thread caller=Thread.currentThread();long before=System.nanoTime();close.run();ok(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<250,"Login close returns without waiting for socket I/O");
        ok(gate.disconnectThread!=caller,"Login callback thread never performs socket disconnect");
    }
    private static void credentialLateGrant(boolean slow)throws Exception{
        scenarios++;AtomicInteger exchanges=new AtomicInteger();NativeCredentialAuth auth=new NativeCredentialAuth("bbs.nga.cn",null,(uid,token)->{exchanges.incrementAndGet();return new NativeCredentialAuth.Result(NativeCredentialAuth.State.SUCCESS,"synthetic","ngaPassportUid=12345; ngaPassportCid=synthetic-only");});
        Gate image=next(IMAGE,true,false,false);ok(auth.imageChallenge().image.length==IMAGE.length,"Initial image challenge uses production network");ok(image.streamClosed,"Image challenge input closes normally");
        Gate grant=next(fixture("credentialauth","password-grant.json"),false,true,slow);AtomicReference<NativeCredentialAuth.Result> result=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();char[] secret="synthetic_password".toCharArray();
        Thread network=new Thread(()->{try{result.set(auth.passwordLogin("synthetic-actor",secret,"ABC123",""));}catch(Exception error){failure.set(error);}},"SyntheticCredentialNetwork");
        network.start();await(grant.reading,"Password grant response is pending before explicit cancellation");promptClose(auth::close,grant);
        if(slow){await(grant.disconnectStarted,"Slow password socket cleanup starts off callback thread");ok(network.isAlive(),"Slow cleanup has not silently completed the grant");}
        grant.releaseDisconnect.countDown();grant.releaseRead.countDown();network.join(2000);
        ok(!network.isAlive()&&failure.get() instanceof IOException&&result.get()==null,"Canceled password response cannot return a login cookie");ok(exchanges.get()==0,"Canceled password response never redeems a late grant");
        boolean zero=true;for(char c:secret)if(c!=0)zero=false;ok(zero,"Canceled password array is cleared");ok(grant.streamClosed,"Canceled password input closes");
        promptClose(auth::close,grant);int before=opened;try{auth.imageChallenge();throw new AssertionError("Closed auth must reject");}catch(IOException expected){ok(opened==before,"Closed credentials cannot start another request");}
    }
    private static void qrLateGrant(boolean slow)throws Exception{
        scenarios++;AtomicLong clock=new AtomicLong(1000);java.lang.reflect.Constructor<?> transport=Class.forName("cn.ngapure.app.NativeAuth$NetworkTransport").getDeclaredConstructor();transport.setAccessible(true);NativeAuth auth=new NativeAuth("bbs.nga.cn",(NativeAuth.Transport)transport.newInstance(),clock::get);Gate generation=next(fixture("nativeauth","generate.json"),false,false,false);NativeAuth.QrSession session=auth.begin();ok(!session.qrText().isEmpty()&&generation.streamClosed,"QR generation completes through production network");
        clock.addAndGet(3000);Gate approved=next(fixture("nativeauth","approved.json"),false,true,slow);AtomicReference<NativeAuth.PollResult> result=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread network=new Thread(()->{try{result.set(auth.poll(session,true));}catch(Exception error){failure.set(error);}},"SyntheticQrNetwork");network.start();await(approved.reading,"QR grant response is pending before explicit cancellation");promptClose(auth::close,approved);
        ok(session.isCancelled()&&session.qrText().isEmpty(),"QR memory is revoked synchronously before socket cleanup");
        if(slow)await(approved.disconnectStarted,"Slow QR socket cleanup starts off callback thread");
        approved.releaseDisconnect.countDown();approved.releaseRead.countDown();network.join(2000);
        ok(!network.isAlive(),"Canceled QR network finishes after cleanup release");ok(result.get()==null||result.get().state!=NativeAuth.State.SUCCESS,"Canceled QR response cannot return success");
        ok(result.get()==null||result.get().cookieHeader.isEmpty(),"Canceled QR response exposes no login cookie");ok(failure.get()!=null||result.get()!=null,"Canceled QR request has a terminal result");ok(approved.streamClosed,"Canceled QR input closes");promptClose(auth::close,approved);
    }
    private static byte[] padded(byte[] prefix){byte[] body=new byte[128*4096];java.util.Arrays.fill(body,(byte)' ');System.arraycopy(prefix,0,body,0,prefix.length);return body;}
    private static void canceledBufferedBody(boolean credential)throws Exception{
        scenarios++;Gate gate=next(padded(credential?IMAGE:fixture("nativeauth","generate.json")),credential,false,false);
        if(credential){NativeCredentialAuth client=new NativeCredentialAuth("bbs.nga.cn");gate.afterFirstRead=client::close;try{client.imageChallenge();throw new AssertionError("Canceled image must not return");}catch(IOException expected){ok(true,"Canceled image returns a terminal failure");}}
        else{NativeAuth client=new NativeAuth("bbs.nga.cn");gate.afterFirstRead=client::close;try{client.begin();throw new AssertionError("Canceled QR must not return");}catch(IOException expected){ok(true,"Canceled QR generation returns a terminal failure");}}
        ok(gate.reads==1&&gate.consumed==4096,"Client stops after the first canceled body chunk, even if disconnect leaves buffered bytes available");ok(gate.streamClosed,"Canceled buffered input closes immediately");
    }
    private static void suspendedQrKeepsCheckpoint()throws Exception{
        scenarios++;AtomicReference<String> checkpoint=new AtomicReference<>();AtomicInteger cleared=new AtomicInteger();AtomicLong clock=new AtomicLong(1000);
        java.lang.reflect.Constructor<?> transport=Class.forName("cn.ngapure.app.NativeAuth$NetworkTransport").getDeclaredConstructor();transport.setAccessible(true);
        NativeAuth.Recovery recovery=new NativeAuth.Recovery(){public void checkpoint(String state){checkpoint.set(state);}public void clear(){cleared.incrementAndGet();checkpoint.set(null);}};
        NativeAuth client=new NativeAuth("bbs.nga.cn",(NativeAuth.Transport)transport.newInstance(),clock::get,recovery);
        Gate gate=next(padded(fixture("nativeauth","generate.json")),false,false,false);gate.afterFirstRead=client::suspend;
        NativeAuth.QrSession session=client.begin();ok(gate.reads>1&&gate.consumed==gate.body.length,"Suspension permits the finite in-flight QR response to finish");
        ok(checkpoint.get()!=null&&!checkpoint.get().isEmpty()&&cleared.get()==0,"Suspension preserves the original authorization checkpoint");
        ok(session.isCancelled()&&session.qrText().isEmpty(),"Suspended old owner clears its memory after checkpointing");ok(gate.streamClosed,"Suspended request closes its input");
        client.close();ok(checkpoint.get()==null&&cleared.get()==1,"Later explicit abandonment clears the retained checkpoint");
    }
    private static ThreadPoolExecutor disconnects()throws Exception{Field f=RequestCancellation.class.getDeclaredField("DISCONNECTS");f.setAccessible(true);return (ThreadPoolExecutor)f.get(null);}
    private static void saturatedCleanup()throws Exception{
        scenarios++;ThreadPoolExecutor pool=disconnects();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while((pool.getActiveCount()>0||!pool.getQueue().isEmpty())&&System.nanoTime()<until)Thread.yield();ok(pool.getActiveCount()==0&&pool.getQueue().isEmpty(),"Cleanup pool begins idle");
        List<Gate> fillers=new ArrayList<>();
        try{
            for(int i=0;i<3;i++){Gate g=new Gate(new byte[0],false,false,true);GATES.add(g);fillers.add(g);ok(RequestCancellation.disconnectAsync(new Connection(new URL("https://bbs.nga.cn/"),g)),"Bounded cleanup worker accepts initial filler");await(g.disconnectStarted,"Cleanup filler occupies one worker");}
            for(int i=0;i<12;i++){Gate g=new Gate(new byte[0],false,false,true);GATES.add(g);fillers.add(g);ok(RequestCancellation.disconnectAsync(new Connection(new URL("https://bbs.nga.cn/"),g)),"Bounded cleanup queue accepts its documented capacity");}
            ok(pool.getPoolSize()<=3&&pool.getQueue().size()==12,"Cleanup is bounded to three workers and twelve queued connections");
            // Neither client may fall back to synchronous disconnect when the shared queue is full.
            credentialLateGrant(false);qrLateGrant(false);
            ok(pool.getPoolSize()<=3&&pool.getQueue().size()==12,"Saturated auth close neither grows threads nor queues nor steals the caller thread");
        }finally{for(Gate gate:fillers)gate.releaseDisconnect.countDown();}
        until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while((pool.getActiveCount()>0||!pool.getQueue().isEmpty())&&System.nanoTime()<until)Thread.yield();ok(pool.getActiveCount()==0&&pool.getQueue().isEmpty(),"Bounded cleanup queue drains after synthetic sockets release");
    }
    public static void main(String[] args)throws Exception{
        URL.setURLStreamHandlerFactory(protocol->new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{
            if(!"https".equals(protocol)||!"bbs.nga.cn".equals(url.getHost()))throw new IOException("Unscripted endpoint blocked");Gate gate=RESPONSES.poll();if(gate==null)throw new IOException("Unscripted request blocked");opened++;return new Connection(url,gate);
        }});
        try{credentialLateGrant(true);qrLateGrant(true);saturatedCleanup();canceledBufferedBody(true);canceledBufferedBody(false);suspendedQrKeepsCheckpoint();ok(RESPONSES.isEmpty(),"Every synthetic response consumed; no blind grant exchange or verification retry");}
        finally{for(Gate gate:GATES){gate.releaseRead.countDown();gate.releaseDisconnect.countDown();}}
        System.out.println("AuthNetworkCancellationTest: "+scenarios+" scenarios, "+checks+" checks passed; real network requests/SMS/credential submissions: 0");
    }
}
