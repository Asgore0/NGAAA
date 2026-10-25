package cn.ngapure.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Real process-scoped queue, finite local work, no Android, account, disk or network. */
public final class NativeUserWritesTest {
    private static int assertions;
    private static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception {
        Executor writes=NativeUserWrites.executor();check(writes==NativeUserWrites.executor(),"same process queue across owners");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),completed=new CountDownLatch(3);
        AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger(),rejectedWork=new AtomicInteger();AtomicBoolean interrupted=new AtomicBoolean();
        List<Integer> order=Collections.synchronizedList(new ArrayList<Integer>());
        writes.execute(()->{int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);entered.countDown();try{release.await(5,TimeUnit.SECONDS);order.add(1);}catch(InterruptedException stop){interrupted.set(true);Thread.currentThread().interrupt();}finally{active.decrementAndGet();completed.countDown();}});
        check(entered.await(2,TimeUnit.SECONDS),"confirmed work starts");
        for(int i=2;i<=3;i++){final int item=i;writes.execute(()->{int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);order.add(item);active.decrementAndGet();completed.countDown();});}
        boolean refused=false;try{writes.execute(rejectedWork::incrementAndGet);}catch(RejectedExecutionException full){refused=true;}
        check(refused,"third waiting task rejected explicitly");check(rejectedWork.get()==0,"rejection runs no work");
        release.countDown();check(completed.await(3,TimeUnit.SECONDS),"all accepted tasks complete");
        check(maximum.get()==1,"single writer");check(order.equals(java.util.Arrays.asList(1,2,3)),"accepted waiting tasks preserve order");check(!interrupted.get(),"owner-independent queue never interrupts confirmed work");
        Thread.sleep(30);check(order.size()==3&&rejectedWork.get()==0,"no automatic retry or replay");
        CountDownLatch next=new CountDownLatch(1);writes.execute(next::countDown);check(next.await(2,TimeUnit.SECONDS),"queue accepts fresh explicit confirmation after capacity returns");
        System.out.println("NativeUserWrites: "+assertions+" process queue assertions PASS; no UI, credentials, storage or network");
    }
}
