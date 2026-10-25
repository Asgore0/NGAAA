package cn.ngapure.app;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exact original-image transport with sockets that ignore interrupts until disconnect. */
public final class NativeImageReadCancellationTest {
    private static final Map<String,Fixture> fixtures=new ConcurrentHashMap<>();
    private static int assertions;
    private static final class Fixture {
        final int blockedPhase;final RequestCancellation.Token cancelAtHeaders;
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicInteger opens=new AtomicInteger(),headers=new AtomicInteger(),bodyReads=new AtomicInteger(),disconnects=new AtomicInteger();
        Fixture(int phase,RequestCancellation.Token cancelAtHeaders){blockedPhase=phase;this.cancelAtHeaders=cancelAtHeaders;}
        void block()throws IOException{entered.countDown();boolean interrupted=false;while(true){try{release.await();break;}catch(InterruptedException ignored){interrupted=true;}}if(interrupted)Thread.currentThread().interrupt();if(disconnects.get()>0)throw new IOException("Synthetic socket disconnected");}
    }
    private static String fixture(String name,int phase,RequestCancellation.Token cancel){String url="https://img.nga.cn/attachments/cancel-"+name+".png";fixtures.put(url,new Fixture(phase,cancel));return url;}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        boolean expectHook=args.length==0||Boolean.parseBoolean(args[0]);URL.setURLStreamHandlerFactory(protocol->!protocol.equals("https")?null:new URLStreamHandler(){
            @Override protected URLConnection openConnection(URL url)throws IOException{Fixture f=fixtures.get(url.toString());if(f==null)throw new IOException("Unexpected fixture origin");f.opens.incrementAndGet();return new HttpURLConnection(url){
                @Override public int getResponseCode()throws IOException{f.headers.incrementAndGet();if(getRequestProperty("Cookie")!=null||getRequestProperty("Authorization")!=null)throw new AssertionError("Anonymous image headers");if(f.blockedPhase==1)f.block();if(f.cancelAtHeaders!=null)f.cancelAtHeaders.cancel();return 200;}
                @Override public InputStream getInputStream()throws IOException{if(f.blockedPhase==3)f.block();return new ByteArrayInputStream(new byte[]{1}){@Override public synchronized int read(byte[] value,int at,int len){f.bodyReads.incrementAndGet();if(f.blockedPhase==2)try{f.block();}catch(IOException closed){return -1;}return super.read(value,at,len);}};}
                @Override public long getContentLengthLong(){return 1;}@Override public void disconnect(){f.disconnects.incrementAndGet();f.release.countDown();}@Override public void connect(){}@Override public boolean usingProxy(){return false;}
            };}
        });
        boolean pre=preCancelled(expectHook),headers=blocked(expectHook,1),body=blocked(expectHook,2),stream=blocked(expectHook,3),after=afterHeaders(expectHook);completedRegistration();
        System.out.println("{\"passed\":true,\"expectedCancellationHook\":"+expectHook+",\"preCancelledMadeNoRequest\":"+pre+",\"blockedHeadersStoppedByToken\":"+headers+",\"blockedBodyStoppedByToken\":"+body+",\"blockedInputStreamStoppedByToken\":"+stream+",\"cancelAfterHeadersStoppedBeforeBody\":"+after+",\"assertionCount\":"+assertions+",\"realNetworkRequests\":0,\"fakeSocketIgnoresInterrupt\":true}");
    }
    private static boolean preCancelled(boolean expect)throws Exception {
        RequestCancellation.Token token=new RequestCancellation.Token();token.cancel();String url=fixture("before-connect",0,null);AtomicReference<Throwable> failed=new AtomicReference<>();int[] received={0};Thread thread=start(token,url,failed,received);thread.join(1000);check(!thread.isAlive(),"pre-cancelled worker finished");boolean stopped=fixtures.get(url).opens.get()==0&&fixtures.get(url).headers.get()==0&&failed.get() instanceof IOException&&received[0]==0;check(stopped==expect,"pre-cancelled token observed before connecting");return stopped;
    }
    private static boolean blocked(boolean expect,int phase)throws Exception {
        RequestCancellation.Token token=new RequestCancellation.Token();String url=fixture("blocked-"+phase,phase,null);Fixture f=fixtures.get(url);AtomicReference<Throwable> failed=new AtomicReference<>();int[] received={0};Thread thread=start(token,url,failed,received);check(f.entered.await(2,TimeUnit.SECONDS),"blocking fixture entered");token.cancel();thread.join(250);boolean stopped=!thread.isAlive()&&failed.get() instanceof IOException&&received[0]==0;
        try{check(stopped==expect,"token alone disconnects blocking phase "+phase);}finally{f.release.countDown();thread.join(2000);check(!thread.isAlive(),"blocking fixture cleaned up");}return stopped;
    }
    private static boolean afterHeaders(boolean expect)throws Exception {
        RequestCancellation.Token token=new RequestCancellation.Token();String url=fixture("after-headers",0,token);AtomicReference<Throwable> failed=new AtomicReference<>();int[] received={0};Thread thread=start(token,url,failed,received);thread.join(1000);check(!thread.isAlive(),"after-headers worker finished");boolean stopped=fixtures.get(url).bodyReads.get()==0&&failed.get() instanceof IOException&&received[0]==0;check(stopped==expect,"cancellation after headers checked before reading body");return stopped;
    }
    private static void completedRegistration()throws Exception {
        RequestCancellation.Token token=new RequestCancellation.Token();String url=fixture("completed",0,null);AtomicReference<Throwable> failed=new AtomicReference<>();int[] received={0};Thread thread=start(token,url,failed,received);thread.join(1000);check(!thread.isAlive()&&failed.get()==null&&received[0]==1,"scoped successful read unchanged");Fixture f=fixtures.get(url);check(f.disconnects.get()==1,"successful finally disconnects once");token.cancel();Thread.sleep(50);check(f.disconnects.get()==1,"completed scope registration removed before token cancellation");
    }
    private static Thread start(RequestCancellation.Token token,String url,AtomicReference<Throwable> failed,int[] received){Thread thread=new Thread(()->{RequestCancellation.Scope scope=RequestCancellation.bind(token);try{received[0]=NativeImages.bytes(url).length;}catch(Throwable error){failed.set(error);}finally{scope.close();}},"OriginalImageReadFixture");thread.start();return thread;}
}
