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
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Exercises real NgaApi through scripted URL connections. No real URL can be opened. */
public final class TransportLifecycleTest {
    private static final String A="ngaPassportUid=70001; ngaPassportCid=fixture-token-a";
    private static final String B="ngaPassportUid=70002; ngaPassportCid=fixture-token-b";
    private static final ArrayDeque<Exchange> RESPONSES=new ArrayDeque<>();
    private static int assertions,scenarios,opened;
    private static void ok(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
    private static final class Exchange {
        int status=200,reads;String requested="";String location="https://ngabbs.com/nuke.php";
        String body="{\"data\":[[],{}]}";
        boolean closed,disconnected,failWrite,interruptBeforeBody,interruptDuringBody,oversized;
        Runnable afterHeaders;
        final Map<String,String> headers=new HashMap<>();
    }
    private static final class Connection extends HttpURLConnection {
        final Exchange exchange;
        Connection(URL url,Exchange value){super(url);exchange=value;exchange.requested=url.toString();}
        @Override public void setRequestProperty(String name,String value){exchange.headers.put(name,value);}
        @Override public OutputStream getOutputStream() throws IOException {
            if(exchange.failWrite)throw new IOException("synthetic output failure");
            return new ByteArrayOutputStream();
        }
        @Override public int getResponseCode(){if(exchange.afterHeaders!=null)exchange.afterHeaders.run();return exchange.status;}
        @Override public String getHeaderField(String name){return "Location".equals(name)?exchange.location:null;}
        @Override public String getContentType(){return "application/json; charset=UTF-8";}
        @Override public InputStream getInputStream(){return stream();}
        @Override public InputStream getErrorStream(){return stream();}
        private InputStream stream(){
            if(exchange.interruptBeforeBody)Thread.currentThread().interrupt();
            final ByteArrayInputStream bytes=new ByteArrayInputStream(exchange.body.getBytes(StandardCharsets.UTF_8));
            return new InputStream(){
                @Override public int read(){throw new AssertionError("buffered input expected");}
                @Override public int read(byte[] buffer,int offset,int length){
                    exchange.reads++;
                    if(exchange.interruptDuringBody)Thread.currentThread().interrupt();
                    if(exchange.oversized){java.util.Arrays.fill(buffer,offset,offset+length,(byte)' ');return length;}
                    return bytes.read(buffer,offset,length);
                }
                @Override public void close(){exchange.closed=true;}
            };
        }
        @Override public void disconnect(){exchange.disconnected=true;}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){connected=true;}
    }
    private static Exchange response(){Exchange value=new Exchange();RESPONSES.add(value);return value;}
    private static TestContext account(String cookie){TestContext context=new TestContext();if(!cookie.isEmpty())new NgaStore(context).setCookie(cookie);return context;}
    private static void interruptFailure(NgaApi api,boolean thread) throws Exception {
        try {
            if(thread)api.thread("123",1);else api.hotTopics();
            throw new AssertionError("cancelled request must fail");
        } catch(InterruptedIOException expected){
            ok(Thread.currentThread().isInterrupted(),"cancellation preserves the interrupt flag");
        } finally {Thread.interrupted();}
    }
    private static void redirectedIdentity(boolean loggedIn) throws Exception {
        scenarios++;TestContext context=account(loggedIn?A:"");NgaStore store=new NgaStore(context);
        Exchange first=response();first.status=302;first.afterHeaders=()->store.setCookie(B);
        Exchange second=response();ok(new NgaApi(context).hotTopics().isEmpty(),"valid redirected feed parses");
        if(loggedIn)ok(A.equals(first.headers.get("Cookie"))&&A.equals(second.headers.get("Cookie")),"redirect hops retain the starting account");
        else ok(!first.headers.containsKey("Cookie")&&!second.headers.containsKey("Cookie"),"anonymous request does not acquire a later login halfway through");
        ok(store.cookie().equals(B),"transport never overwrites a new login");
        ok(first.disconnected&&second.disconnected&&second.closed,"all redirect and successful response resources close");
    }
    private static void preCancelled(boolean locator) throws Exception {
        scenarios++;int before=opened;Thread.currentThread().interrupt();
        try {
            if(locator){try{new NgaApi(account(A)).thread("123",1,"456");throw new AssertionError("locator cancellation expected");}
                catch(InterruptedIOException expected){ok(Thread.currentThread().isInterrupted(),"reply locator preserves interrupt flag");}}
            else interruptFailure(new NgaApi(account(A)),false);
        }finally{Thread.interrupted();}
        ok(opened==before,"pre-cancelled request never opens a connection");
    }
    private static void cancelledHeaders() throws Exception {
        scenarios++;Exchange first=response();first.status=302;first.afterHeaders=()->Thread.currentThread().interrupt();int before=opened;
        interruptFailure(new NgaApi(account(A)),false);
        ok(opened==before+1&&first.disconnected,"cancelled redirect does not open its next hop");
        ok(first.reads==0,"cancelled headers do not begin a body download");
    }
    private static void cancelledBody(boolean before,boolean malformedThread) throws Exception {
        scenarios++;Exchange value=response();value.interruptBeforeBody=before;value.interruptDuringBody=!before;
        if(malformedThread)value.body="truncated non-JSON";
        int count=opened;interruptFailure(new NgaApi(account(A)),malformedThread);
        ok(value.closed&&value.disconnected,"cancelled body closes stream and connection");
        ok(value.reads==(before?0:1),"cancelled download stops at the first cancellation boundary");
        ok(opened==count+1,"cancelled malformed thread cannot trigger HTML fallback");
    }
    private static void writeFailure() throws Exception {
        scenarios++;TestContext context=account(A);Exchange value=response();value.failWrite=true;
        try{new NgaApi(context).checkIn("synthetic-output");throw new AssertionError("write failure expected");}
        catch(IOException expected){ok(expected.getMessage().contains("synthetic output"),"POST output failure propagates unchanged");}
        ok(value.disconnected,"POST output failure always disconnects");
        ok(new NgaStore(context).lastCheckInDay().isEmpty(),"failed POST never confirms today's check-in");
    }
    private static void invalidRedirect() throws Exception {
        scenarios++;Exchange value=response();value.status=302;value.location="https://attacker.invalid/read.php";int before=opened;
        try{new NgaApi(account(A)).hotTopics();throw new AssertionError("unsafe redirect expected");}
        catch(IOException expected){ok(true,"unsafe redirect still fails");}
        ok(value.disconnected&&opened==before+1,"unsafe redirect does not open an external connection");
    }
    private static void bodyLimit() throws Exception {
        scenarios++;Exchange value=response();value.oversized=true;
        try{new NgaApi(account(A)).hotTopics();throw new AssertionError("body limit expected");}
        catch(IOException expected){ok(expected.getMessage().contains("响应过大"),"response keeps the existing 8 MiB limit");}
        ok(value.closed&&value.disconnected,"oversized body releases stream and connection");
        ok(value.reads==1025,"limit is enforced before an unbounded download");
    }
    private static String previewScope(String host,String cookie) throws Exception {
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest((host+"\0"+cookie).getBytes(StandardCharsets.UTF_8));
        StringBuilder value=new StringBuilder(64);for(byte b:digest)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();
    }
    private static void previewIdentity() throws Exception {
        scenarios++;TestContext context=account(A);NgaStore store=new NgaStore(context);NgaApi api=new NgaApi(context);
        String scope=previewScope(context.host,A);int before=opened;
        try{api.firstPostImages("123",previewScope(context.host,B));throw new AssertionError("stale identity expected");}
        catch(InterruptedIOException expected){ok(opened==before,"preview rejects stale account before opening a connection");}
        for(String tid:new String[]{"0","01","123&other=1","1234567890123456789"}){
            try{api.firstPostImages(tid,scope);throw new AssertionError("invalid preview ID expected");}
            catch(IllegalArgumentException expected){ok(opened==before,"invalid preview ID does not open a connection");}
        }
        String body="{\"data\":{\"__T\":{\"tid\":123,\"authorid\":42},\"__PAGE\":1,\"__R\":[{\"pid\":0,\"lou\":0,\"authorid\":42,\"content\":\"[img]https://img.nga.cn/attachments/a.jpg[/img]\"}]}}";
        context.afterCapture=()->{store.setCookie(B);context.host="nga.178.com";};
        Exchange value=response();value.body=body;
        ok(api.firstPostImages("123",scope).equals(java.util.Collections.singletonList("https://img.nga.cn/attachments/a.jpg")),"preview parses exact original under captured identity");
        ok(value.requested.equals("https://bbs.nga.cn/read.php?tid=123&page=1&__output=8"),"preview makes only the native first-page request on captured host");
        ok(A.equals(value.headers.get("Cookie")),"preview holds captured cookie even when account changes after capture");
        ok(value.closed&&value.disconnected&&opened==before+1,"preview read closes resources after exactly one request");
        ok(store.cookie().equals(B)&&context.host.equals("nga.178.com"),"preview never writes account or domain state");
        before=opened;
        try{api.firstPostImages("123",scope);throw new AssertionError("old preview scope expected");}
        catch(InterruptedIOException expected){ok(opened==before,"queued old-scope preview cannot read new account");}
    }
    private static void previewNoHtmlFallback() throws Exception {
        scenarios++;TestContext context=account(A);int before=opened;Exchange malformed=response();malformed.body="truncated non-JSON";
        try{new NgaApi(context).firstPostImages("123",previewScope(context.host,A));throw new AssertionError("truncated preview expected");}
        catch(IOException expected){ok(opened==before+1,"preview truncation never triggers second HTML request");}
        ok(malformed.closed&&malformed.disconnected,"malformed preview releases response and connection");
        before=opened;Thread.currentThread().interrupt();
        try{new NgaApi(context).firstPostImages("123",previewScope(context.host,A));throw new AssertionError("cancelled preview expected");}
        catch(InterruptedIOException expected){ok(opened==before&&Thread.currentThread().isInterrupted(),"pre-cancelled preview opens no socket and preserves interrupt");}
        finally{Thread.interrupted();}
    }
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){
            @Override protected URLConnection openConnection(URL url) throws IOException {
                Exchange value=RESPONSES.poll();if(value==null)throw new IOException("Unscripted network blocked");opened++;return new Connection(url,value);
            }
        }:null);
        redirectedIdentity(true);redirectedIdentity(false);preCancelled(false);preCancelled(true);cancelledHeaders();
        cancelledBody(true,false);cancelledBody(false,false);cancelledBody(false,true);writeFailure();invalidRedirect();bodyLimit();previewIdentity();previewNoHtmlFallback();
        ok(RESPONSES.isEmpty(),"all scripted responses consumed exactly once");
        ok(!Thread.currentThread().isInterrupted(),"test restores thread state after cancellation cases");
        System.out.println("TransportLifecycle: "+assertions+" assertions PASS; "+scenarios+" scenarios; real network requests 0");
    }
}
