package cn.ngapure.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real ratings transport and screen-read queue; all URL connections are local scripted objects. */
public final class RatingsTransportCancellationTest {
    private static final ConcurrentLinkedQueue<Exchange> RESPONSES=new ConcurrentLinkedQueue<>();
    private static int assertions,scenarios,opened;
    private static void ok(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    private static final class Exchange {
        int status=200,reads,writes;boolean interruptAtHeaders,interruptInBody,blockRead,closed;
        volatile boolean disconnected;
        final CountDownLatch reading=new CountDownLatch(1),release=new CountDownLatch(1);
        String body="{\"code\":0,\"result\":[{\"catid\":\"\",\"page\":1,\"next\":0,\"topics\":[]},[]]}";
    }
    private static final class Connection extends HttpURLConnection {
        final Exchange value;
        Connection(URL url,Exchange value){super(url);this.value=value;}
        @Override public OutputStream getOutputStream(){value.writes++;return new ByteArrayOutputStream();}
        @Override public int getResponseCode(){if(value.interruptAtHeaders)Thread.currentThread().interrupt();return value.status;}
        @Override public InputStream getInputStream(){final ByteArrayInputStream bytes=new ByteArrayInputStream(value.body.getBytes(StandardCharsets.UTF_8));return new InputStream(){
            @Override public int read(){throw new AssertionError("Buffered read expected");}
            @Override public int read(byte[] buffer,int offset,int length) throws IOException {
                value.reads++;value.reading.countDown();if(value.blockRead)while(value.release.getCount()>0){try{value.release.await();}catch(InterruptedException ignored){/* Socket reads need not react to worker interruption. */}}
                if(value.interruptInBody)Thread.currentThread().interrupt();return bytes.read(buffer,offset,length);
            }
            @Override public void close(){value.closed=true;}
        };}
        @Override public void disconnect(){value.disconnected=true;value.release.countDown();}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){connected=true;}
    }
    private interface Work{void run() throws Exception;}
    private static Exchange response(){Exchange value=new Exchange();RESPONSES.add(value);return value;}
    private static NgaRatings api(){return new NgaRatings(new TestContext());}
    private static void cancelled(Work work,String label) throws Exception {
        try{work.run();throw new AssertionError(label+": expected cancellation");}
        catch(InterruptedIOException expected){ok(true,label);}
    }
    private static void beforeRequest(boolean tokenOnly) throws Exception {
        scenarios++;int before=opened;RequestCancellation.Token token=new RequestCancellation.Token();RequestCancellation.Scope scope=RequestCancellation.bind(token);
        if(tokenOnly)token.cancel();else Thread.currentThread().interrupt();
        try{cancelled(()->api().list("",1),"cancelled ratings request fails before network");ok(tokenOnly||Thread.currentThread().isInterrupted(),"thread interruption is preserved");}
        finally{Thread.interrupted();scope.close();}
        ok(opened==before,"pre-cancelled ratings request opens no connection");
    }
    private static void duringResponse(boolean headers) throws Exception {
        scenarios++;Exchange value=response();value.interruptAtHeaders=headers;value.interruptInBody=!headers;
        try{cancelled(()->api().list("",1),"cancelled ratings response never returns data");ok(Thread.currentThread().isInterrupted(),"response cancellation preserves interrupt flag");}
        finally{Thread.interrupted();}
        ok(value.writes==1&&value.disconnected,"cancelled read-only POST is delivered at most once and disconnected");
        ok(headers?value.reads==0:value.reads==1&&value.closed,"cancellation stops at first response boundary and closes opened stream");
    }
    private static void actualPageQueue() throws Exception {
        scenarios++;ScreenReadTasks queue=new ScreenReadTasks(1);Exchange first=response();first.blockRead=true;CountDownLatch oldDone=new CountDownLatch(1),newDone=new CountDownLatch(1);boolean[] oldCancelled={false},newSucceeded={false};
        try{
            ok(queue.execute(()->{try{api().list("",1);}catch(InterruptedIOException expected){oldCancelled[0]=true;}catch(Exception ignored){}finally{oldDone.countDown();}}),"old ratings screen read is queued");
            ok(first.reading.await(2,TimeUnit.SECONDS),"old ratings screen owns blocked body read");long start=System.nanoTime();queue.cancelAll();
            ok(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<500,"leaving ratings screen cancels without caller socket I/O");
            Exchange latest=response();ok(queue.execute(()->{try{newSucceeded[0]=api().list("",1).items.isEmpty();}catch(Exception ignored){}finally{newDone.countDown();}}),"latest ratings screen request is accepted");
            ok(newDone.await(2,TimeUnit.SECONDS)&&newSucceeded[0],"latest request runs after obsolete ratings socket disconnects");
            ok(oldDone.await(2,TimeUnit.SECONDS)&&oldCancelled[0],"old screen exits as cancellation and returns no parsed data");
            ok(first.disconnected&&first.closed&&first.reads==1,"old blocked stream and socket are released");
            ok(latest.disconnected&&latest.closed,"latest successful response releases its resources");
        }finally{first.release.countDown();queue.close();}
    }
    private static void normalAndHttpFailure() throws Exception {
        scenarios++;Exchange normal=response();ok(api().list("",1).items.isEmpty(),"normal explicit empty ratings page remains supported");ok(normal.closed&&normal.disconnected&&normal.writes==1,"normal read-only form closes resources");
        Exchange redirect=response();redirect.status=302;int before=opened;
        try{api().list("",1);throw new AssertionError("Redirect must fail");}catch(IOException expected){ok(expected.getMessage().contains("跳转"),"existing redirect explanation is retained");}
        ok(redirect.disconnected&&opened==before+1&&redirect.reads==0,"redirect remains rejected without follow-up request");
    }
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){
            @Override protected URLConnection openConnection(URL url) throws IOException {Exchange value=RESPONSES.poll();if(value==null)throw new IOException("Unscripted network blocked");opened++;return new Connection(url,value);}
        }:null);
        beforeRequest(false);beforeRequest(true);duringResponse(true);duringResponse(false);actualPageQueue();normalAndHttpFailure();
        ok(RESPONSES.isEmpty(),"every scripted connection consumed once");ok(!Thread.currentThread().isInterrupted(),"test leaves no interrupt flag");
        System.out.println("RatingsTransportCancellation: "+assertions+" assertions PASS; "+scenarios+" scenarios; real network requests 0; real community writes 0");
    }
}
