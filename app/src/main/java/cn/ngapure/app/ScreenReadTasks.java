package cn.ngapure.app;

import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Read-only page work. Leaving a screen cancels its queued and running reads.
 * Writes use a separate executor so navigation never retries or cancels a user submission.
 */
final class ScreenReadTasks implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final ArrayList<FutureTask<Void>> tasks=new ArrayList<>();
    private boolean closed;

    ScreenReadTasks(){this(3);}
    ScreenReadTasks(int workers){
        executor=new ThreadPoolExecutor(workers,workers,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),r->{
            Thread thread=new Thread(r,"nga-screen-read");thread.setDaemon(true);return thread;
        });
    }
    synchronized boolean execute(Runnable read){
        if(closed)return false;
        tasks.removeIf(FutureTask::isDone);
        RequestCancellation.Token cancellation=new RequestCancellation.Token();
        FutureTask<Void> task=new FutureTask<Void>(()->{
            RequestCancellation.Scope scope=RequestCancellation.bind(cancellation);
            try{read.run();}finally{scope.close();}
        },null){
            @Override public boolean cancel(boolean interrupt){cancellation.cancel();return super.cancel(interrupt);}
        };
        // A page issues only a few independent reads. Never grow an unbounded backlog.
        if(executor.getQueue().remainingCapacity()==0)return false;
        tasks.add(task);executor.execute(task);return true;
    }
    synchronized void cancelAll(){
        for(FutureTask<Void> task:tasks)task.cancel(true);
        tasks.clear();executor.purge();
    }
    synchronized int queued(){return executor.getQueue().size();}
    @Override public synchronized void close(){if(closed)return;closed=true;cancelAll();executor.shutdownNow();}
}
