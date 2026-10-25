package cn.ngapure.app;

import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** The real delivery and ScreenReadTasks, with no network or platform timing assumptions. */
public final class ForumReadDeliveryTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void await(CountDownLatch signal,String message)throws Exception{check(signal.await(3,TimeUnit.SECONDS),message);}
    private static void waitFor(CountDownLatch signal){try{if(!signal.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Fixture wait timed out");}catch(InterruptedException cancelled){Thread.currentThread().interrupt();}}
    // Model a socket that needs disconnect(), not an interruptible latch that exits first and
    // unregisters the request before the asynchronous disconnect worker can inspect it.
    private static void waitForSocketRelease(CountDownLatch signal){
        boolean interrupted=false;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        try{while(signal.getCount()!=0){long remaining=end-System.nanoTime();if(remaining<=0)throw new IllegalStateException("Fixture socket wait timed out");
            try{if(!signal.await(remaining,TimeUnit.NANOSECONDS))throw new IllegalStateException("Fixture socket wait timed out");}
            catch(InterruptedException cancelled){interrupted=true;}
        }}finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    private static void cleared(ForumReadDelivery<?> value)throws Exception{
        for(String name:new String[]{"value","receiver"}){Field field=ForumReadDelivery.class.getDeclaredField(name);field.setAccessible(true);check(field.get(value)==null,"handoff releases "+name);}
    }
    private static void responseBeforeBinding()throws Exception{
        ForumReadDelivery<String> delivery=new ForumReadDelivery<>();ArrayDeque<Runnable> work=new ArrayDeque<>();AtomicInteger received=new AtomicInteger();
        check(delivery.complete("same page"),"first response accepted");check(!delivery.complete("other page"),"duplicate response ignored");
        delivery.bind(work::add,value->{check(value.equals("same page"),"early response preserved");received.incrementAndGet();});
        check(received.get()==0&&work.size()==1,"early result never runs consumer synchronously in bind");work.remove().run();
        check(received.get()==1,"early result delivered once");cleared(delivery);
        try{delivery.bind(work::add,value->{});throw new AssertionError("second bind accepted");}catch(IllegalStateException expected){checks++;}
    }
    private static void responseAfterBinding()throws Exception{
        ScreenReadTasks reads=new ScreenReadTasks(1);ForumReadDelivery<String> delivery=new ForumReadDelivery<>();
        AtomicInteger extraQueues=new AtomicInteger(),received=new AtomicInteger();AtomicReference<Thread> original=new AtomicReference<>(),consumer=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);
        try{delivery.bind(work->{extraQueues.incrementAndGet();reads.execute(work);},value->{consumer.set(Thread.currentThread());received.incrementAndGet();done.countDown();});
            check(reads.execute(()->{original.set(Thread.currentThread());delivery.complete("page");}),"actual screen read submitted");await(done,"late response delivered");
            check(original.get()==consumer.get()&&consumer.get()!=Thread.currentThread(),"late result continues on original worker");
            check(extraQueues.get()==0&&received.get()==1,"normal response adds no queue hop or duplicate delivery");cleared(delivery);
        }finally{reads.close();}
    }
    private static void cancellationBoundaries()throws Exception{
        for(int phase=0;phase<3;phase++){
            ForumReadDelivery<String> delivery=new ForumReadDelivery<>();ArrayDeque<Runnable> work=new ArrayDeque<>();AtomicInteger received=new AtomicInteger();
            if(phase==0){delivery.cancel();delivery.bind(work::add,value->received.incrementAndGet());check(!delivery.complete("late"),"cancel before read rejects completion");}
            if(phase==1){delivery.complete("early");delivery.cancel();delivery.bind(work::add,value->received.incrementAndGet());}
            if(phase==2){delivery.complete("early");delivery.bind(work::add,value->received.incrementAndGet());check(work.size()==1,"ready result was queued");delivery.cancel();}
            while(!work.isEmpty())work.remove().run();check(received.get()==0&&delivery.cancelled(),"cancellation suppresses ready/pending delivery");cleared(delivery);
        }
    }
    private static void cancellationScopeSurvivesDelivery()throws Exception{
        ScreenReadTasks reads=new ScreenReadTasks(1);ForumReadDelivery<String> delivery=new ForumReadDelivery<>();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1),disconnected=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        HttpURLConnection connection=new HttpURLConnection(new URL("https://synthetic.invalid/")){
            @Override public void disconnect(){disconnected.countDown();}
            @Override public void connect(){}
            @Override public boolean usingProxy(){return false;}
        };
        try{delivery.bind(work->{throw new AssertionError("normal completion must retain original read worker");},value->{
                RequestCancellation.Registration registration=null;
                try{registration=RequestCancellation.track(connection);
                    entered.countDown();waitForSocketRelease(release);
                }catch(Throwable problem){failure.set(problem);}finally{if(registration!=null)registration.close();done.countDown();}
            });
            reads.execute(()->delivery.complete("page"));await(entered,"receiver runs inside real cancellation scope");reads.cancelAll();delivery.cancel();
            await(disconnected,"leaving page disconnects a tracked request within the receiver");release.countDown();await(done,"cancelled delivery worker leaves");
            check(failure.get()==null,"original cancellation registration remains valid");cleared(delivery);
        }finally{release.countDown();reads.close();}
    }
    private static void callbacksOutsideMonitor()throws Exception{
        ForumReadDelivery<String> delivery=new ForumReadDelivery<>();ExecutorService workers=Executors.newFixedThreadPool(2);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try{delivery.bind(Runnable::run,value->{entered.countDown();waitFor(release);});Future<?> complete=workers.submit(()->delivery.complete("page"));await(entered,"consumer intentionally blocked");
            workers.submit(delivery::cancel).get(2,TimeUnit.SECONDS);check(release.getCount()==1,"cancel returns while consumer is still active outside monitor");release.countDown();complete.get(2,TimeUnit.SECONDS);cleared(delivery);
        }finally{release.countDown();workers.shutdownNow();}
    }
    private static void simultaneousBindingAndCompletion()throws Exception{
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try{for(int i=0;i<40;i++){
                ForumReadDelivery<Integer> delivery=new ForumReadDelivery<>();AtomicInteger seen=new AtomicInteger();CountDownLatch start=new CountDownLatch(1);final int value=i;
                Future<?> bind=workers.submit(()->{waitFor(start);delivery.bind(Runnable::run,result->{if(result!=value)throw new AssertionError("wrong response");seen.incrementAndGet();});});
                Future<?> complete=workers.submit(()->{waitFor(start);delivery.complete(value);});start.countDown();bind.get(2,TimeUnit.SECONDS);complete.get(2,TimeUnit.SECONDS);
                check(seen.get()==1,"simultaneous bind/response has one terminal delivery");
            }
        }finally{workers.shutdownNow();}
    }
    public static void main(String[] args)throws Exception{
        responseBeforeBinding();responseAfterBinding();cancellationBoundaries();cancellationScopeSurvivesDelivery();callbacksOutsideMonitor();simultaneousBindingAndCompletion();
        System.out.println("ForumReadDelivery: "+checks+" assertions passed; real ScreenReadTasks cancellation; network=0");
    }
}
