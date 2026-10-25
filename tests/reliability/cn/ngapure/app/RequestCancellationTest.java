package cn.ngapure.app;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in scopes and actual NgaApi cancellation, with no real HTTP or accounts. */
public final class RequestCancellationTest {
    private static final Queue<Exchange> RESPONSES=new ConcurrentLinkedQueue<>();
    private static final AtomicInteger OPENED=new AtomicInteger();
    private static int assertions,scenarios;
    private static void ok(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    private static void await(CountDownLatch latch,String label) throws Exception {ok(latch.await(3,TimeUnit.SECONDS),label);}
    private static final class Exchange {
        final boolean held;
        final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),disconnected=new CountDownLatch(1);
        volatile String disconnectThread="";volatile boolean disconnectDaemon,streamClosed;
        Exchange(boolean held){this.held=held;}
    }
    private static final class Connection extends HttpURLConnection {
        final Exchange exchange;
        Connection(URL url,Exchange value){super(url);exchange=value;}
        @Override public int getResponseCode() throws IOException {
            exchange.started.countDown();
            if(exchange.held){
                // A socket that ignores thread interruption models why cancel(true) alone is insufficient.
                boolean interrupted=false;
                while(true){
                    try{if(!exchange.release.await(5,TimeUnit.SECONDS))throw new IOException("Synthetic stuck socket");break;}
                    catch(InterruptedException ignored){interrupted=true;}
                }
                if(interrupted)Thread.currentThread().interrupt();
            }
            return 200;
        }
        @Override public String getContentType(){return "application/json; charset=UTF-8";}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream("{\"data\":[[],{}]}".getBytes(StandardCharsets.UTF_8)){
            @Override public void close() throws IOException {exchange.streamClosed=true;super.close();}
        };}
        @Override public void disconnect(){
            exchange.disconnectThread=Thread.currentThread().getName();exchange.disconnectDaemon=Thread.currentThread().isDaemon();
            exchange.release.countDown();exchange.disconnected.countDown();
        }
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){connected=true;}
    }
    private static Exchange response(boolean held){Exchange value=new Exchange(held);RESPONSES.add(value);return value;}
    private static void scopeRules() throws Exception {
        scenarios++;RequestCancellation.Token outer=new RequestCancellation.Token(),inner=new RequestCancellation.Token();
        RequestCancellation.Scope first=RequestCancellation.bind(outer);
        try{
            outer.cancel();RequestCancellation.Scope nested=RequestCancellation.bind(inner);
            try{RequestCancellation.check();ok(true,"nested scope temporarily owns cancellation checks");}
            finally{nested.close();}
            try{RequestCancellation.check();throw new AssertionError("outer cancellation expected");}
            catch(InterruptedIOException expected){ok(!Thread.currentThread().isInterrupted(),"token cancellation does not invent an interrupt flag");}
        }finally{first.close();}
        RequestCancellation.check();ok(true,"closing scope restores an unscoped worker");
        RequestCancellation.Token cancelled=new RequestCancellation.Token();cancelled.cancel();int before=OPENED.get();
        RequestCancellation.Scope scope=RequestCancellation.bind(cancelled);
        try{new NgaApi(new TestContext()).hotTopics();throw new AssertionError("already cancelled request expected");}
        catch(InterruptedIOException expected){ok(OPENED.get()==before,"cancelled scope does not open a socket");}
        finally{scope.close();}
    }
    private static void releasedRegistration() throws Exception {
        scenarios++;Exchange exchange=new Exchange(false);Connection connection=new Connection(new URL("https://bbs.nga.cn/"),exchange);
        RequestCancellation.Token token=new RequestCancellation.Token();RequestCancellation.Scope scope=RequestCancellation.bind(token);
        try{RequestCancellation.Registration registration=RequestCancellation.track(connection);registration.close();ok(token.cancel(),"closed registration creates no disconnect backlog");}
        finally{scope.close();}
        ok(exchange.disconnected.getCount()==1,"unregistered connection is not retained or disconnected later");
    }
    private static void threeBlockedReaders() throws Exception {
        scenarios++;ExecutorService workers=Executors.newFixedThreadPool(3);
        List<RequestCancellation.Token> tokens=new ArrayList<>();List<Exchange> exchanges=new ArrayList<>();List<Future<Boolean>> old=new ArrayList<>();
        try{
            for(int i=0;i<3;i++){
                RequestCancellation.Token token=new RequestCancellation.Token();tokens.add(token);Exchange exchange=response(true);exchanges.add(exchange);
                old.add(workers.submit(()->{
                    RequestCancellation.Scope scope=RequestCancellation.bind(token);
                    try{new NgaApi(new TestContext()).hotTopics();return false;}
                    finally{scope.close();}
                }));
                await(exchange.started,"old reader owns a real NgaApi connection before cancellation");
            }
            Exchange latest=response(false);Future<Boolean> newest=workers.submit(()->new NgaApi(new TestContext()).hotTopics().isEmpty());
            long start=System.nanoTime();for(RequestCancellation.Token token:tokens)ok(token.cancel(),"active socket disconnect accepted by bounded worker");
            ok(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<500,"cancelling three sockets returns without blocking caller");
            ok(newest.get(2,TimeUnit.SECONDS),"newest screen starts promptly after three blocked sockets are cancelled");
            for(int i=0;i<3;i++){
                try{old.get(i).get(2,TimeUnit.SECONDS);throw new AssertionError("cancelled reader must fail");}
                catch(ExecutionException expected){ok(expected.getCause() instanceof InterruptedIOException,"old readers exit as cancellation rather than parsed success");}
                await(exchanges.get(i).disconnected,"cancelled connection is disconnected");
            }
            ok(latest.streamClosed,"newest successful response closes its stream");
        }finally{for(Exchange exchange:exchanges)exchange.release.countDown();workers.shutdownNow();ok(workers.awaitTermination(3,TimeUnit.SECONDS),"reader workers terminate");}
    }
    private static void helperAndSaturation() throws Exception {
        scenarios++;final CountDownLatch firstThree=new CountDownLatch(3),release=new CountDownLatch(1),done=new CountDownLatch(15);
        final AtomicInteger wrongThread=new AtomicInteger();final Thread caller=Thread.currentThread();
        HttpURLConnection blocker=new HttpURLConnection(new URL("https://bbs.nga.cn/")){
            @Override public void disconnect(){
                if(Thread.currentThread()==caller||!Thread.currentThread().isDaemon())wrongThread.incrementAndGet();
                firstThree.countDown();
                try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("test disconnect release timeout");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                finally{done.countDown();}
            }
            @Override public boolean usingProxy(){return false;}@Override public void connect(){connected=true;}
        };
        try{
            for(int i=0;i<3;i++)ok(RequestCancellation.disconnectAsync(blocker),"independent media disconnect accepted");
            await(firstThree,"three bounded disconnect workers run concurrently");
            for(int i=0;i<12;i++)ok(RequestCancellation.disconnectAsync(blocker),"bounded cancellation queue accepts its documented capacity");
            ok(!RequestCancellation.disconnectAsync(blocker),"queue saturation is reported instead of running disconnect on caller");
            RequestCancellation.Token token=new RequestCancellation.Token();RequestCancellation.Scope scope=RequestCancellation.bind(token);
            RequestCancellation.Registration registration;
            try{registration=RequestCancellation.track(blocker);ok(!token.cancel()&&token.isCancelled(),"saturated token still cancels but explicitly reports deferred disconnect");registration.close();}
            finally{scope.close();}
            ok(wrongThread.get()==0,"all socket disconnect work stays off caller on daemon workers");
        }finally{release.countDown();await(done,"all accepted disconnect tasks drain after release");}
    }
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){
            @Override protected URLConnection openConnection(URL url) throws IOException {
                Exchange value=RESPONSES.poll();if(value==null)throw new IOException("Unscripted network blocked");OPENED.incrementAndGet();return new Connection(url,value);
            }
        }:null);
        scopeRules();releasedRegistration();threeBlockedReaders();helperAndSaturation();
        ok(RESPONSES.isEmpty(),"every scripted fake exchange consumed once");RequestCancellation.check();
        System.out.println("RequestCancellation: "+assertions+" assertions PASS; "+scenarios+" scenarios; real network requests 0");
    }
}
