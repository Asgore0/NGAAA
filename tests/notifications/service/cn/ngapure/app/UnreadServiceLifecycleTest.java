package cn.ngapure.app;

import android.app.job.JobParameters;
import android.os.Handler;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Real service/HTTP adapter; synthetic framework callbacks and notification delivery. No sockets. */
public final class UnreadServiceLifecycleTest {
    private static final ConcurrentLinkedQueue<Gate> RESPONSES=new ConcurrentLinkedQueue<>();
    private static final List<Gate> GATES=new ArrayList<>();
    private static int checks,scenarios,opened;
    private static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static final class Gate {
        final CountDownLatch reading=new CountDownLatch(1),release=new CountDownLatch(1),disconnected=new CountDownLatch(1);
        boolean fail;volatile boolean streamClosed;volatile Thread disconnectThread;
    }
    private static Gate next(boolean blocked){Gate gate=new Gate();GATES.add(gate);RESPONSES.add(gate);if(!blocked)gate.release.countDown();return gate;}
    private static final class Connection extends HttpURLConnection {
        private final Gate gate;
        Connection(URL url,Gate gate){super(url);this.gate=gate;}
        @Override public int getResponseCode()throws IOException{if(gate.fail)throw new IOException("Synthetic read failure");return 200;}
        @Override public String getContentType(){return "application/json; charset=UTF-8";}
        @Override public InputStream getInputStream(){final ByteArrayInputStream body=new ByteArrayInputStream("{\"data\":[{\"act_url\":\"nul\"}]}".getBytes(StandardCharsets.UTF_8));return new InputStream(){
            @Override public int read(){throw new AssertionError("Buffered read expected");}
            @Override public int read(byte[] data,int offset,int length)throws IOException{
                gate.reading.countDown();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(gate.release.getCount()>0&&System.nanoTime()<end){try{gate.release.await(50,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){/* A socket can ignore thread interruption. */}}
                if(gate.release.getCount()>0)throw new IOException("Synthetic read release timeout");return body.read(data,offset,length);
            }
            @Override public void close(){gate.streamClosed=true;}
        };}
        @Override public void disconnect(){gate.disconnectThread=Thread.currentThread();gate.disconnected.countDown();gate.release.countDown();}
        @Override public void connect(){connected=true;}
        @Override public boolean usingProxy(){return false;}
    }
    private static UnreadNotificationService service(){NativeNotifications.enabled=true;NativeNotifications.permission=true;Handler.drain();return new UnreadNotificationService();}
    private static ExecutorService worker(UnreadNotificationService service)throws Exception{Field f=UnreadNotificationService.class.getDeclaredField("worker");f.setAccessible(true);return (ExecutorService)f.get(service);}
    private static void idle(UnreadNotificationService service)throws Exception{worker(service).submit(()->{}).get(2,TimeUnit.SECONDS);}
    private static void destroy(UnreadNotificationService service)throws Exception{service.onDestroy();ok(worker(service).awaitTermination(2,TimeUnit.SECONDS),"Service worker terminates after destruction");Handler.drain();}
    private static void started(Gate gate)throws Exception{ok(gate.reading.await(2,TimeUnit.SECONDS),"Production read reaches its synthetic blocked socket");}
    private static void canceled(Gate gate)throws Exception{ok(gate.disconnected.await(2,TimeUnit.SECONDS),"Cancellation actively disconnects its read");ok(gate.disconnectThread!=Thread.currentThread(),"Cancellation does not perform socket I/O on callback thread");}
    private static void stopThenReplacement()throws Exception{
        scenarios++;UnreadNotificationService service=service();Gate old=next(true),fresh=next(true);JobParameters a=new JobParameters(12092),b=new JobParameters(12092);
        try{ok(service.onStartJob(a),"First job accepted");started(old);ok(service.onStopJob(new JobParameters(12092)),"Stop requests retry while enabled");canceled(old);
            ok(service.onStartJob(b),"Replacement job accepted");started(fresh);ok(old.streamClosed,"Old stopped stream is closed before replacement");fresh.release.countDown();idle(service);Handler.drain();
            ok(service.completed.size()==1&&service.completed.get(0)==b,"Only current replacement can call jobFinished");ok(!service.retry.get(0),"Successful replacement does not request retry");ok(!service.onStopJob(a),"Stop after completed job cannot recreate a retry");
        }finally{old.release.countDown();fresh.release.countDown();destroy(service);}
    }
    private static void supersededStart()throws Exception{
        scenarios++;UnreadNotificationService service=service();Gate old=next(true),fresh=next(true);JobParameters a=new JobParameters(12092),b=new JobParameters(12093);
        try{ok(service.onStartJob(a),"Initial job accepted");started(old);ok(service.onStartJob(b),"New start supersedes initial owner");canceled(old);started(fresh);
            ok(!service.onStopJob(new JobParameters(12092)),"Late stop for a different job ID is ignored");ok(fresh.disconnected.getCount()==1,"Late foreign stop leaves current read open");
            fresh.release.countDown();idle(service);Handler.drain();ok(service.completed.size()==1&&service.completed.get(0)==b,"Superseded owner cannot finish the newer job");
        }finally{old.release.countDown();fresh.release.countDown();destroy(service);}
    }
    private static void destroyedRead()throws Exception{
        scenarios++;UnreadNotificationService service=service();Gate gate=next(true);
        ok(service.onStartJob(new JobParameters(12092)),"Destruction case starts a job");started(gate);destroy(service);canceled(gate);
        ok(gate.streamClosed&&service.completed.isEmpty(),"Destroyed read closes without late jobFinished");int before=opened;ok(!service.onStartJob(new JobParameters(12092))&&opened==before,"Destroyed service refuses a new job without a request");
    }
    private static void disabledOrRevoked()throws Exception{
        scenarios++;UnreadNotificationService service=service();int before=opened;
        try{NativeNotifications.enabled=false;ok(!service.onStartJob(new JobParameters(12092))&&opened==before,"Disabled notifications never start a read");
            NativeNotifications.enabled=true;NativeNotifications.permission=false;ok(!service.onStartJob(new JobParameters(12092))&&opened==before,"Missing permission never starts a read");
            NativeNotifications.permission=true;Gate gate=next(true);ok(service.onStartJob(new JobParameters(12092)),"Authorized job starts");started(gate);NativeNotifications.permission=false;
            ok(!service.onStopJob(new JobParameters(12092)),"Revoked permission does not reschedule");canceled(gate);idle(service);Handler.drain();ok(service.completed.isEmpty(),"Revoked stopped owner cannot finish a job");
        }finally{destroy(service);}
    }
    private static void readFailureRetries()throws Exception{
        scenarios++;UnreadNotificationService service=service();Gate gate=next(false);gate.fail=true;JobParameters parameters=new JobParameters(12092);
        try{ok(service.onStartJob(parameters),"Failing read job accepted");idle(service);Handler.drain();ok(service.completed.size()==1&&service.completed.get(0)==parameters&&service.retry.get(0),"Current failed read requests one system retry");ok(gate.disconnected.getCount()==0,"Failed read still closes its connection");}
        finally{destroy(service);}
    }
    private static void queuedCompletionIsFenced()throws Exception{
        scenarios++;UnreadNotificationService service=service();Gate old=next(false),fresh=next(true);JobParameters a=new JobParameters(12092),b=new JobParameters(12092);
        try{ok(service.onStartJob(a),"Queued-completion first job accepted");idle(service);ok(!Handler.QUEUE.isEmpty(),"First completion is queued before main callback");
            ok(service.onStartJob(b),"Replacement begins before old completion delivery");started(fresh);Handler.drain();ok(service.completed.isEmpty(),"Already queued stale completion is ignored");
            fresh.release.countDown();idle(service);Handler.drain();ok(service.completed.size()==1&&service.completed.get(0)==b,"Only replacement completion is delivered");ok(old.streamClosed,"Completed previous stream stays closed");}
        finally{fresh.release.countDown();destroy(service);}
    }
    private static void executorRejection()throws Exception{
        scenarios++;UnreadNotificationService service=service();worker(service).shutdownNow();int before=opened;
        try{ok(!service.onStartJob(new JobParameters(12092)),"Stopped executor rejects job safely");ok(opened==before&&service.completed.isEmpty(),"Rejected job has no request or false completion");ok(!service.onStopJob(new JobParameters(12092)),"Rejected owner releases active job identity");}
        finally{destroy(service);}
    }
    public static void main(String[] args)throws Exception{
        URL.setURLStreamHandlerFactory(protocol->new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{
            if(!"https".equals(protocol)||!"bbs.nga.cn".equals(url.getHost())||!"/nuke.php".equals(url.getPath()))throw new IOException("Unscripted endpoint blocked");Gate gate=RESPONSES.poll();if(gate==null)throw new IOException("Unscripted request blocked");opened++;return new Connection(url,gate);
        }});
        try{stopThenReplacement();supersededStart();destroyedRead();disabledOrRevoked();readFailureRetries();queuedCompletionIsFenced();executorRejection();ok(RESPONSES.isEmpty(),"All scripted reads consumed");}
        finally{for(Gate gate:GATES)gate.release.countDown();}
        System.out.println("UnreadServiceLifecycleTest: "+scenarios+" scenarios, "+checks+" checks passed; real network requests: 0; notification delivery stubbed");
    }
}
