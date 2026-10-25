package cn.ngapure.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Real community call/HTTPS transport with fake connections that cannot open sockets. */
public final class CommunityTransportCancellationTest {
    private static final String COOKIE="ngaPassportUid=70001; ngaPassportCid=fixture-only";
    private static final ArrayDeque<Exchange> RESPONSES=new ArrayDeque<>();
    private static int assertions,scenarios,opened;
    private static void ok(boolean condition,String label){assertions++;if(!condition)throw new AssertionError(label);}
    private static final class Exchange {
        int status=200,reads,writes;
        String body="{\"data\":[{\"act_url\":\"nul\"}]}";
        boolean interruptAtHeaders,interruptInBody,blockRead,failWrite,closed;
        volatile boolean disconnected;
        final CountDownLatch reading=new CountDownLatch(1),release=new CountDownLatch(1);
    }
    private static final class Connection extends HttpURLConnection {
        final Exchange exchange;
        Connection(URL url,Exchange value){super(url);exchange=value;}
        @Override public int getResponseCode(){if(exchange.interruptAtHeaders)Thread.currentThread().interrupt();return exchange.status;}
        @Override public String getContentType(){return "application/json; charset=UTF-8";}
        @Override public InputStream getInputStream(){return stream();}
        @Override public InputStream getErrorStream(){return stream();}
        @Override public OutputStream getOutputStream() throws IOException {
            exchange.writes++;if(exchange.failWrite)throw new IOException("synthetic output failure");return new ByteArrayOutputStream();
        }
        private InputStream stream(){
            final ByteArrayInputStream bytes=new ByteArrayInputStream(exchange.body.getBytes(StandardCharsets.UTF_8));
            return new InputStream(){
                @Override public int read(){throw new AssertionError("buffered read expected");}
                @Override public int read(byte[] buffer,int offset,int length) throws IOException {
                    exchange.reads++;exchange.reading.countDown();
                    if(exchange.blockRead)while(exchange.release.getCount()>0){try{exchange.release.await();}catch(InterruptedException ignored){/* A socket read can ignore worker interruption. */}}
                    if(exchange.blockRead&&exchange.disconnected)throw new IOException("synthetic socket disconnected");
                    if(exchange.interruptInBody)Thread.currentThread().interrupt();
                    return bytes.read(buffer,offset,length);
                }
                @Override public void close(){exchange.closed=true;}
            };
        }
        @Override public void disconnect(){exchange.disconnected=true;exchange.release.countDown();}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){connected=true;}
    }
    private static Exchange response(){Exchange value=new Exchange();RESPONSES.add(value);return value;}
    private static NativeCommunity.Session session(){return new NativeCommunity.Session(){public String host(){return "bbs.nga.cn";}public String cookie(){return COOKIE;}};}
    private static NativeCommunity.Transport transport() throws Exception {
        Constructor<?> constructor=Class.forName("cn.ngapure.app.NativeCommunity$HttpsTransport").getDeclaredConstructor();constructor.setAccessible(true);
        return (NativeCommunity.Transport)constructor.newInstance();
    }
    private static NativeCommunity api() throws Exception{return new NativeCommunity(session(),transport());}
    private interface Action {void run() throws Exception;}
    private static void failure(NativeCommunity.Code code,Action action,String label) throws Exception {
        try{action.run();throw new AssertionError(label+": must fail");}
        catch(NativeCommunity.CommunityException expected){ok(expected.code==code,label);ok(!expected.getMessage().contains("fixture-only"),"safe failure contains no test credential");}
    }
    private static void preInterrupted() throws Exception {
        scenarios++;int before=opened;Thread.currentThread().interrupt();
        try{failure(NativeCommunity.Code.NETWORK,()->api().officialActivity(),"pre-interrupted read is cancelled");ok(Thread.currentThread().isInterrupted(),"read cancellation preserves its interrupt flag");}
        finally{Thread.interrupted();}
        ok(opened==before,"pre-interrupted read never opens a connection");
    }
    private static void preCancelled() throws Exception {
        scenarios++;RequestCancellation.Token token=new RequestCancellation.Token();token.cancel();int before=opened;RequestCancellation.Scope scope=RequestCancellation.bind(token);
        try{failure(NativeCommunity.Code.NETWORK,()->api().officialActivity(),"pre-cancelled page token blocks read");}
        finally{scope.close();}
        ok(opened==before,"pre-cancelled token never opens a connection");ok(!Thread.currentThread().isInterrupted(),"token cancellation does not interrupt its caller");
    }
    private static void cancelledAtHeaders() throws Exception {
        scenarios++;Exchange value=response();value.interruptAtHeaders=true;
        try{failure(NativeCommunity.Code.NETWORK,()->api().officialActivity(),"cancelled headers cannot return parsed data");ok(Thread.currentThread().isInterrupted(),"header cancellation preserves interrupt flag");}
        finally{Thread.interrupted();}
        ok(value.reads==0&&value.disconnected,"header cancellation disconnects before body download");
    }
    private static void cancelledInBody() throws Exception {
        scenarios++;Exchange value=response();value.interruptInBody=true;
        try{failure(NativeCommunity.Code.NETWORK,()->api().officialActivity(),"cancelled body cannot return parsed data");ok(Thread.currentThread().isInterrupted(),"body cancellation preserves interrupt flag");}
        finally{Thread.interrupted();}
        ok(value.reads==1&&value.closed&&value.disconnected,"body cancellation closes resources after its first read");
    }
    private static void cancelledAfterCustomTransport() throws Exception {
        scenarios++;RequestCancellation.Token token=new RequestCancellation.Token();NativeCommunity api=new NativeCommunity(session(),(request,cookie)->{
            token.cancel();return new NativeCommunity.Response(200,"{\"data\":[{\"act_url\":\"nul\"}]}".getBytes(StandardCharsets.UTF_8),"application/json");
        });RequestCancellation.Scope scope=RequestCancellation.bind(token);
        try{failure(NativeCommunity.Code.NETWORK,api::officialActivity,"late injected transport result is discarded after cancellation");}
        finally{scope.close();}
    }
    private static void blockedReadsReleaseWorkers() throws Exception {
        scenarios++;ExecutorService workers=Executors.newFixedThreadPool(2);List<RequestCancellation.Token> tokens=new ArrayList<>();List<Exchange> exchanges=new ArrayList<>();List<Future<Boolean>> old=new ArrayList<>();
        try{
            for(int i=0;i<2;i++){
                RequestCancellation.Token token=new RequestCancellation.Token();tokens.add(token);Exchange value=response();value.blockRead=true;exchanges.add(value);
                old.add(workers.submit(()->{RequestCancellation.Scope scope=RequestCancellation.bind(token);try{api().officialActivity();return false;}finally{scope.close();}}));
                ok(value.reading.await(2,TimeUnit.SECONDS),"old page worker reaches blocking fake body read");
            }
            Exchange latest=response();Future<Boolean> next=workers.submit(()->!api().officialActivity().present);
            long before=System.nanoTime();for(RequestCancellation.Token token:tokens)ok(token.cancel(),"socket disconnect is accepted by bounded background pool");
            ok(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<500,"cancelling old reads never waits for socket I/O on caller");
            ok(next.get(2,TimeUnit.SECONDS),"latest queued page starts after old blocked reads disconnect");
            for(int i=0;i<old.size();i++){
                try{old.get(i).get(2,TimeUnit.SECONDS);throw new AssertionError("old page cannot succeed");}
                catch(ExecutionException expected){ok(expected.getCause() instanceof NativeCommunity.CommunityException&&((NativeCommunity.CommunityException)expected.getCause()).code==NativeCommunity.Code.NETWORK,"old page exits as cancelled read rather than successful data");}
                ok(exchanges.get(i).disconnected&&exchanges.get(i).closed,"cancelled blocked read closes stream and connection");
            }
            ok(latest.closed&&latest.disconnected,"latest successful page closes its own resources");
        }finally{for(Exchange value:exchanges)value.release.countDown();workers.shutdownNow();ok(workers.awaitTermination(3,TimeUnit.SECONDS),"read workers terminate after navigation-style close");}
    }
    private static void readPostIsDisposable() throws Exception {
        scenarios++;Exchange value=response();value.body="{\"data\":[[],0]}";value.interruptAtHeaders=true;
        try{failure(NativeCommunity.Code.NETWORK,()->api().favoriteFolders(1),"read-only folder form POST is cancellable");}
        finally{Thread.interrupted();}
        ok(value.writes==1&&value.reads==0&&value.disconnected,"read-only form closes before downloading cancelled response");
    }
    private static void writeIgnoresPageToken() throws Exception {
        scenarios++;Exchange value=response();value.body="{\"data\":[\"发送成功\"]}";RequestCancellation.Token token=new RequestCancellation.Token();token.cancel();RequestCancellation.Scope scope=RequestCancellation.bind(token);Thread.currentThread().interrupt();
        try{ok(api().sendMessage("70002","fixture subject","fixture body").confirmed,"explicit mutation ignores cancelled disposable-read token");ok(Thread.currentThread().isInterrupted(),"mutation does not clear caller interrupt flag");}
        finally{Thread.interrupted();scope.close();}
        ok(value.writes==1&&value.closed&&value.disconnected,"explicit mutation delivers once and closes resources");
    }
    private static void uncertainWrite(int kind) throws Exception {
        scenarios++;Exchange value=response();if(kind==0)value.failWrite=true;else if(kind==1)value.status=302;else value.body="malformed";
        RequestCancellation.Token token=new RequestCancellation.Token();token.cancel();RequestCancellation.Scope scope=RequestCancellation.bind(token);int before=opened;
        try{failure(NativeCommunity.Code.UNCONFIRMED,()->api().sendMessage("70002","fixture subject","fixture body"),"failed/redirected/malformed write remains delivery-unknown");}
        finally{scope.close();}
        ok(opened==before+1&&value.writes==1&&value.disconnected,"unknown mutation is not retried or redirected");
    }
    private static void mutationGetAlsoIgnoresToken() throws Exception {
        scenarios++;Constructor<NativeCommunity.Request> constructor=NativeCommunity.Request.class.getDeclaredConstructor(String.class,String.class,java.util.Map.class,boolean.class,boolean.class);constructor.setAccessible(true);
        NativeCommunity.Request request=constructor.newInstance("bbs.nga.cn","nuke.php",java.util.Collections.<String,String>emptyMap(),true,false);
        Exchange value=response();RequestCancellation.Token token=new RequestCancellation.Token();token.cancel();RequestCancellation.Scope scope=RequestCancellation.bind(token);
        try{ok(transport().execute(request,COOKIE).status==200,"mutation policy uses semantic flag even for HTTP GET");}
        finally{scope.close();}
        ok(value.reads>0&&value.closed&&value.disconnected,"semantic GET mutation ignores disposable-read token");
    }
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){
            @Override protected URLConnection openConnection(URL url) throws IOException {Exchange value=RESPONSES.poll();if(value==null)throw new IOException("Unscripted network blocked");opened++;return new Connection(url,value);}
        }:null);
        preInterrupted();preCancelled();cancelledAtHeaders();cancelledInBody();cancelledAfterCustomTransport();blockedReadsReleaseWorkers();readPostIsDisposable();writeIgnoresPageToken();uncertainWrite(0);uncertainWrite(1);uncertainWrite(2);mutationGetAlsoIgnoresToken();
        ok(RESPONSES.isEmpty(),"every scripted connection consumed once");ok(!Thread.currentThread().isInterrupted(),"test leaves no interrupt on caller");
        System.out.println("CommunityTransportCancellation: "+assertions+" assertions PASS; "+scenarios+" scenarios; real network requests 0; real community writes 0");
    }
}
