package cn.ngapure.app;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One running local-maintenance task plus the latest trigger, independent of HTTP workers. */
final class StartupTaskQueue {
    private final Object lock=new Object();
    private final Executor executor;
    private Runnable pending;
    private boolean scheduled;
    private long submissions,runs,failures,lastWorkNanos,maxWorkNanos;

    StartupTaskQueue(String name){this(new ThreadPoolExecutor(0,1,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(1),task->{Thread thread=new Thread(task,name);thread.setDaemon(true);thread.setPriority(Thread.MIN_PRIORITY);return thread;},new ThreadPoolExecutor.AbortPolicy()));}
    /** Tests inject only the executor; the same coalescing/lock/drain implementation runs. */
    StartupTaskQueue(Executor executor){if(executor==null)throw new IllegalArgumentException("Missing executor");this.executor=executor;}

    void submit(Runnable task){
        if(task==null)return;
        synchronized(lock){submissions++;pending=task;if(scheduled)return;scheduled=true;}
        try{executor.execute(this::drain);}
        catch(RuntimeException unavailable){synchronized(lock){scheduled=false;pending=null;failures++;lock.notifyAll();}}
    }
    private void drain(){
        for(;;){
            final Runnable task;
            synchronized(lock){task=pending;pending=null;if(task==null){scheduled=false;lock.notifyAll();return;}}
            long started=System.nanoTime();
            try{task.run();}catch(RuntimeException unavailable){synchronized(lock){failures++;}}
            finally{synchronized(lock){runs++;lastWorkNanos=Math.max(0,System.nanoTime()-started);maxWorkNanos=Math.max(maxWorkNanos,lastWorkNanos);}}
        }
    }
    /** Numeric state only; never includes captured application/session/task data. */
    String diagnostics(){synchronized(lock){return "submitted="+submissions+" runs="+runs+" failures="+failures+" running="+scheduled+" pending="+(pending!=null)+" lastNs="+lastWorkNanos+" maxNs="+maxWorkNanos;}}
    boolean awaitIdleForTests(long timeoutMs)throws InterruptedException{
        if(timeoutMs<=0)return false;long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized(lock){while(scheduled){long remaining=deadline-System.nanoTime();if(remaining<=0)return false;TimeUnit.NANOSECONDS.timedWait(lock,remaining);}return true;}
    }
}
