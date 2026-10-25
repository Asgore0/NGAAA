package cn.ngapure.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Real NgaApi + real LoginPreferences. The URL handler cannot open any real connection. */
public final class CheckInAccountRaceTest {
    private static final String A="ngaPassportUid=70001; ngaPassportCid=fixture-token-a";
    private static final String A2="access_uid=70001; access_token=fixture-renewed-token-a";
    private static final String B="ngaPassportUid=70002; ngaPassportCid=fixture-token-b";
    private static final Queue<Exchange> RESPONSES=new ConcurrentLinkedQueue<>();
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static int checks,scenarios,opened;
    private static Path fixtures;
    private static void ok(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static void await(CountDownLatch latch,String label) throws Exception {ok(latch.await(5,TimeUnit.SECONDS),label);}
    private static final class Exchange {
        final int status;final byte[] bytes;final IOException failure;final boolean held;
        final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        final Map<String,String> headers=new HashMap<>();final ByteArrayOutputStream body=new ByteArrayOutputStream();
        volatile URL url;volatile String method;volatile boolean disconnected;
        Exchange(String fixture,int status,boolean held,boolean broken) throws IOException {
            this.status=status;this.held=held;bytes=Files.readAllBytes(fixtures.resolve(fixture));
            failure=broken?new IOException("Synthetic interrupted response"):null;
        }
    }
    private static final class Connection extends HttpURLConnection {
        private final Exchange exchange;
        Connection(URL url,Exchange exchange){super(url);this.exchange=exchange;exchange.url=url;}
        @Override public void setRequestProperty(String name,String value){exchange.headers.put(name,value);}
        @Override public String getRequestProperty(String name){return exchange.headers.get(name);}
        @Override public OutputStream getOutputStream(){return exchange.body;}
        @Override public int getResponseCode() throws IOException {
            exchange.method=getRequestMethod();exchange.started.countDown();
            if(exchange.held)try{if(!exchange.release.await(5,TimeUnit.SECONDS))throw new IOException("Synthetic response timeout");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Synthetic interruption",interrupted);}
            if(exchange.failure!=null)throw exchange.failure;return exchange.status;
        }
        @Override public String getContentType(){return "application/json; charset=UTF-8";}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(exchange.bytes);}
        @Override public InputStream getErrorStream(){return new ByteArrayInputStream(exchange.bytes);}
        @Override public String getHeaderField(String name){return "Location".equals(name)?"https://ngabbs.com/nuke.php":null;}
        @Override public void disconnect(){exchange.disconnected=true;}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){connected=true;}
    }
    private static Exchange response(String fixture,int status,boolean held,boolean broken) throws IOException {
        Exchange exchange=new Exchange(fixture,status,held,broken);RESPONSES.add(exchange);return exchange;
    }
    private static TestContext account(String header){TestContext context=new TestContext();new NgaStore(context).setCookie(header);return context;}
    private static void seedB(NgaStore store){
        ok(store.recordCheckInAttemptForAccount("70002","B-only-attempt"),"B can record its own attempt");
        ok(store.saveCheckInResultForAccount("70002",false,"B-only-message"),"B can record its own incomplete result");
    }
    private static void preservedB(NgaStore store){
        ok(store.accountId().equals("70002")&&store.cookie().equals(B),"late A never changes B credentials");
        ok(store.lastCheckInDay().isEmpty(),"A never marks B complete today");
        ok(store.checkInMessage().equals("B-only-message"),"A never overwrites B result message");
        ok(store.lastCheckInAttempt().equals("B-only-attempt"),"A never overwrites B attempt record");
    }
    private static void request(Exchange exchange,String header,String host){
        ok(exchange.url!=null&&exchange.url.getHost().equals(host)&&exchange.url.getPath().equals("/nuke.php"),"HTTP uses captured host and endpoint");
        ok(exchange.url.getQuery().contains("__lib=check_in&__act=check_in")&&exchange.url.getQuery().contains("__output=11"),"real API check-in query is exercised");
        ok(exchange.headers.get("Cookie").equals(header),"HTTP uses the captured cookie, not a later account or token");
        ok("POST".equals(exchange.method)&&exchange.body.size()==0,"exactly the real empty-body POST is exercised");
        ok(exchange.disconnected,"real API closes the fake connection");
    }
    private static void expectedFailure(Future<NgaApi.CheckInResult> future) throws Exception {
        try{future.get(5,TimeUnit.SECONDS);throw new AssertionError("Synthetic failure should propagate");}
        catch(ExecutionException error){ok(error.getCause() instanceof IOException,"real API propagates transport/HTTP failure");}
    }
    private static void noSession() throws Exception {
        scenarios++;TestContext context=new TestContext();int before=opened;
        NgaApi.CheckInResult result=new NgaApi(context).checkIn("no-session");
        ok(!result.success&&!result.already&&opened==before,"no session cannot open a connection");
        ok(new NgaStore(context).lastCheckInAttempt().equals("尚未执行"),"no session cannot invent an attempt");
    }
    private static void ordinary(String fixture,boolean success,boolean already) throws Exception {
        scenarios++;TestContext context=account(A);NgaStore store=new NgaStore(context);Exchange exchange=response(fixture,200,false,false);
        NgaApi.CheckInResult result=new NgaApi(context).checkIn("ordinary-attempt");
        ok(result.success==success&&result.already==already,"real parser classifies explicit success/already/rejection/statistics");
        ok(store.lastCheckInDay().equals(success?NgaStore.today():""),"only server-confirmed completion sets the day");
        ok(store.lastCheckInAttempt().equals("ordinary-attempt")&&store.checkInMessage().equals(result.message),"request owner records its attempt and result");
        request(exchange,A,"bbs.nga.cn");
    }
    private static void changedWhileWaiting(boolean failure,int status) throws Exception {
        scenarios++;TestContext context=account(A);NgaStore store=new NgaStore(context);Exchange exchange=response(status>=400?"http-error.json":"success.json",status,true,failure);
        Future<NgaApi.CheckInResult> future=WORKER.submit(()->new NgaApi(context).checkIn("A-before-switch"));
        await(exchange.started,"A request actually waits before account replacement");
        try{store.setCookie(B);seedB(store);}finally{exchange.release.countDown();}
        if(failure||status>=400)expectedFailure(future);
        else {NgaApi.CheckInResult result=future.get(5,TimeUnit.SECONDS);ok(!result.success&&!result.already,"late A confirmation cannot report current-account completion");}
        request(exchange,A,"bbs.nga.cn");preservedB(store);
        Exchange next=response("success.json",200,false,false);NgaApi.CheckInResult b=new NgaApi(context).checkIn("B-after-A-finished");
        ok(b.success&&store.lastCheckInDay().equals(NgaStore.today()),"subsequent B request can complete B's own day");request(next,B,"bbs.nga.cn");
    }
    private static void renewedWhileWaiting() throws Exception {
        scenarios++;TestContext context=account(A);NgaStore store=new NgaStore(context);Exchange exchange=response("already.json",200,true,false);
        Future<NgaApi.CheckInResult> future=WORKER.submit(()->new NgaApi(context).checkIn("same-account"));await(exchange.started,"A request waits for same-UID renewal");
        try{store.setCookie(A2);}finally{exchange.release.countDown();}
        NgaApi.CheckInResult result=future.get(5,TimeUnit.SECONDS);
        ok(result.success&&result.already&&store.cookie().equals(A2),"same UID token and cookie-family renewal accepts its own result");
        ok(store.lastCheckInDay().equals(NgaStore.today())&&store.lastCheckInAttempt().equals("same-account"),"same UID retains attempt and marks its own completion");request(exchange,A,"bbs.nga.cn");
    }
    private static void logoutWhileWaiting() throws Exception {
        scenarios++;TestContext context=account(A);NgaStore store=new NgaStore(context);Exchange exchange=response("success.json",200,true,false);
        Future<NgaApi.CheckInResult> future=WORKER.submit(()->new NgaApi(context).checkIn("before-logout"));await(exchange.started,"request waits before logout");
        try{store.clearLogin();}finally{exchange.release.countDown();}
        ok(!future.get(5,TimeUnit.SECONDS).success,"late confirmation cannot finish a logged-out account");
        ok(!store.loggedIn()&&store.lastCheckInDay().isEmpty()&&store.checkInMessage().equals("尚未签到")&&store.lastCheckInAttempt().equals("尚未执行"),"logout stays clear after an old response");request(exchange,A,"bbs.nga.cn");
    }
    private static void beforeAttemptChanges() throws Exception {
        scenarios++;TestContext context=account(A);NgaStore store=new NgaStore(context);int before=opened;
        context.afterCapture=()->store.setCookie(B);
        NgaApi.CheckInResult result=new NgaApi(context).checkIn("stale-attempt");
        ok(!result.success&&opened==before,"account mismatch before attempt recording never sends HTTP");
        ok(store.accountId().equals("70002")&&store.lastCheckInDay().isEmpty()&&store.lastCheckInAttempt().equals("尚未执行"),"rejected stale attempt does not alter B cache");
    }
    private static void betweenAttemptAndHttp() throws Exception {
        scenarios++;TestContext context=account(A);NgaStore store=new NgaStore(context);
        context.afterRecord=()->{store.setCookie(B);context.host="ngabbs.com";seedB(store);};
        Exchange exchange=response("success.json",200,false,false);NgaApi.CheckInResult result=new NgaApi(context).checkIn("captured-A");
        ok(!result.success,"an account switch immediately before connection cannot transfer its result");
        request(exchange,A,"bbs.nga.cn");preservedB(store);
    }
    private static void errorForCurrentAccount() throws Exception {
        scenarios++;TestContext context=account(A);NgaStore store=new NgaStore(context);Exchange exchange=response("http-error.json",503,false,false);
        try{new NgaApi(context).checkIn("current-failure");throw new AssertionError("HTTP error should propagate");}catch(IOException expected){ok(true,"current-account HTTP error propagates");}
        ok(store.lastCheckInDay().isEmpty()&&store.checkInMessage().contains("合成HTTP故障"),"current-account exception records an incomplete result only");
        ok(store.lastCheckInAttempt().equals("current-failure"),"current-account failure retains attempt record");request(exchange,A,"bbs.nga.cn");
    }
    private static void defaultEntryPoint() throws Exception {
        scenarios++;TestContext context=account(A);NgaStore store=new NgaStore(context);store.recordCheckInAttemptForAccount("70001","pre-existing-attempt");
        Exchange exchange=response("success.json",200,false,false);NgaApi.CheckInResult result=new NgaApi(context).checkIn();
        ok(result.success&&store.lastCheckInAttempt().equals("pre-existing-attempt"),"default entry point delegates without replacing a caller's attempt label");request(exchange,A,"bbs.nga.cn");
    }
    private static void expectedEntryStale(int kind) throws Exception {
        scenarios++;TestContext context=account(A);NgaStore store=new NgaStore(context);NgaStore.CheckInSession expected=store.captureCheckInSession();int before=opened;
        if(kind==0)store.setCookie(B);else if(kind==1)context.host="ngabbs.com";else store.setCookie(A2);
        String previousMessage=store.checkInMessage(),previousAttempt=store.lastCheckInAttempt();
        NgaApi.CheckInResult result=new NgaApi(context).checkIn("queued-stale",expected);
        ok(!result.success&&opened==before,"stale expected account/host/cookie is rejected before a connection");
        ok(store.checkInMessage().equals(previousMessage)&&store.lastCheckInAttempt().equals(previousAttempt),"stale expected session cannot write a new scope attempt or result");
    }
    private static void expectedValidationBoundary(boolean sameAccount) throws Exception {
        scenarios++;TestContext context=account(A);NgaStore store=new NgaStore(context);NgaStore.CheckInSession expected=store.captureCheckInSession();int before=opened;
        context.afterCapture=()->{store.setCookie(sameAccount?A2:B);context.host="ngabbs.com";};
        Exchange exchange=sameAccount?response("already.json",200,false,false):null;
        NgaApi.CheckInResult result=new NgaApi(context).checkIn("expected-boundary",expected);
        if(sameAccount){ok(result.success&&result.already,"confirmed original UID result keeps its existing stable-account completion semantics");request(exchange,A,"bbs.nga.cn");ok(store.cookie().equals(A2)&&context.host.equals("ngabbs.com"),"late old-session request never replaces renewed credentials or host");}
        else{ok(!result.success&&opened==before,"a different account immediately after validation cannot inherit the queued request");ok(store.cookie().equals(B)&&store.lastCheckInAttempt().equals("尚未执行"),"new account remains untouched by the rejected original request");}
    }
    private static void expectedAfterRecordBoundary() throws Exception {
        scenarios++;TestContext context=account(A);NgaStore store=new NgaStore(context);NgaStore.CheckInSession expected=store.captureCheckInSession();
        context.afterRecord=()->{store.setCookie(B);context.host="ngabbs.com";seedB(store);};Exchange exchange=response("success.json",200,false,false);
        NgaApi.CheckInResult result=new NgaApi(context).checkIn("immutable-foreground-A",expected);
        ok(!result.success,"a new account after attempt recording cannot receive original-session success");request(exchange,A,"bbs.nga.cn");preservedB(store);
    }
    public static void main(String[] args) throws Exception {
        fixtures=Paths.get(args.length==0?"tests/api/checkin/fixtures":args[0]);
        URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){
            @Override protected URLConnection openConnection(URL url) throws IOException {
                Exchange exchange=RESPONSES.poll();if(exchange==null)throw new IOException("Unscripted HTTPS blocked; real network is never used");
                opened++;return new Connection(url,exchange);
            }
        }:null);
        try {
            noSession();ordinary("success.json",true,false);ordinary("already.json",true,true);ordinary("rejected.json",false,false);ordinary("statistics.json",false,false);
            changedWhileWaiting(false,200);changedWhileWaiting(true,200);changedWhileWaiting(false,503);
            renewedWhileWaiting();logoutWhileWaiting();beforeAttemptChanges();betweenAttemptAndHttp();errorForCurrentAccount();defaultEntryPoint();expectedEntryStale(0);expectedEntryStale(1);expectedEntryStale(2);expectedValidationBoundary(false);expectedValidationBoundary(true);expectedAfterRecordBoundary();
            ok(RESPONSES.isEmpty(),"every scripted response was consumed exactly once");
            System.out.println("CheckInAccountRace: "+checks+" account-scope assertions PASS; "+scenarios+" scenarios; real network requests 0");
        } finally {WORKER.shutdownNow();}
    }
}
