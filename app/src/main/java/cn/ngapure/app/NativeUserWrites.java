package cn.ngapure.app;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Confirmed user actions outlive windows, with one worker and at most two waiting tasks.
 * Only live process memory is used. There is no persistence, restart replay or automatic retry.
 */
public final class NativeUserWrites {
    private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(0,1,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(2),action->{
                Thread thread=new Thread(action,"nga-confirmed-user-write");thread.setDaemon(true);return thread;
            },new ThreadPoolExecutor.AbortPolicy());
    // Do not expose pool lifecycle controls to an Activity.
    private static final Executor EXECUTOR=action->WORKER.execute(action);
    private NativeUserWrites() { }
    /** RejectedExecutionException means no work was accepted; the UI must request fresh confirmation. */
    public static Executor executor(){return EXECUTOR;}
}
