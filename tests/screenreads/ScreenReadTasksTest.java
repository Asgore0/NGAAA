package cn.ngapure.app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline regressions for fast navigation, cancellation and stale home snapshots. */
public final class ScreenReadTasksTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        ScreenReadTasks reads=new ScreenReadTasks(1);
        CountDownLatch started=new CountDownLatch(1),interrupted=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicBoolean oldQueuedRan=new AtomicBoolean();
        reads.execute(()->{started.countDown();try{release.await();}catch(InterruptedException expected){interrupted.countDown();}});
        check(started.await(2,TimeUnit.SECONDS),"old page starts");
        reads.execute(()->oldQueuedRan.set(true));
        check(reads.queued()==1,"old second read queued");
        reads.cancelAll();release.countDown();
        check(interrupted.await(2,TimeUnit.SECONDS),"leaving page interrupts running read");
        CountDownLatch newest=new CountDownLatch(1);reads.execute(newest::countDown);
        check(newest.await(2,TimeUnit.SECONDS),"new page executes without obsolete queue");
        check(!oldQueuedRan.get(),"obsolete queued page never reads network");
        reads.close();reads.close();check(!reads.execute(()->{throw new AssertionError();}),"closed activity rejects further reads");

        ScreenReadTasks capped=new ScreenReadTasks(1);CountDownLatch blocked=new CountDownLatch(1),go=new CountDownLatch(1);
        capped.execute(()->{blocked.countDown();try{go.await();}catch(InterruptedException expected){Thread.currentThread().interrupt();}});
        check(blocked.await(2,TimeUnit.SECONDS),"queue budget test active");
        int accepted=0;for(int i=0;i<100;i++)if(capped.execute(()->{}))accepted++;
        check(accepted==8&&capped.queued()==8,"repeated actions cannot create unbounded work");
        capped.cancelAll();check(capped.queued()==0,"cancel purges waiting reads");go.countDown();capped.close();

        ScopedCache<List<String>> cache=new ScopedCache<>();long first=cache.revision();
        check(cache.get("A",100,300)==null,"cold cache");
        check(cache.put("A","A",first,100,new ArrayList<>()),"same account publishes snapshot");
        check(cache.get("A",200,300)!=null,"same account reuses fresh snapshot");
        check(cache.get("B",200,300)==null,"other account cannot reuse snapshot");
        check(cache.get("A",400,300)==null,"expired cache rejected");
        check(cache.get("A",99,300)==null,"clock reversal rejected");
        cache.clear();
        check(!cache.put("A","A",first,500,new ArrayList<>()),"late pre-refresh response cannot repopulate cache");
        check(!cache.put("A","B",cache.revision(),500,new ArrayList<>()),"account/host switch during request cannot publish");
        check(cache.get("A",501,300)==null,"failed late writes keep cache empty");
        System.out.println("PASS screen reads and scoped snapshots: "+checks+" checks; network=0");
    }
}
