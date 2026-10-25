package cn.ngapure.app;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opt-in periodic reads. Stopping/superseding a job revokes its final notification callback. */
public final class UnreadNotificationService extends JobService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private AtomicBoolean canceled;
    private Future<?> task;
    private RequestCancellation.Token reads;
    private JobParameters activeJob;
    private boolean destroyed;
    @Override public boolean onStartJob(JobParameters parameters){
        stopCurrent();
        if(destroyed||!NativeNotifications.enabled(this)||!NativeNotifications.canNotify(this))return false;
        final AtomicBoolean owner=new AtomicBoolean();final RequestCancellation.Token token=new RequestCancellation.Token();
        canceled=owner;reads=token;activeJob=parameters;
        try{task=worker.submit(()->{
            boolean readSucceeded;RequestCancellation.Scope scope=RequestCancellation.bind(token);
            try{readSucceeded=NativeNotifications.check(getApplicationContext(),owner);}catch(RuntimeException unavailable){readSucceeded=false;}finally{scope.close();}
            final boolean success=readSucceeded;
            main.post(()->{if(!owner.get()&&canceled==owner&&reads==token){task=null;canceled=null;reads=null;activeJob=null;jobFinished(parameters,!success);}});
        });return true;}
        catch(RejectedExecutionException stopped){if(canceled==owner)stopCurrent();return false;}
    }
    @Override public boolean onStopJob(JobParameters parameters){
        // Binder may parcel a new JobParameters instance for stop, so compare its public job ID.
        if(activeJob==null||activeJob.getJobId()!=parameters.getJobId())return false;
        stopCurrent();return NativeNotifications.enabled(this)&&NativeNotifications.canNotify(this);
    }
    private void stopCurrent(){
        NativeNotifications.cancel(canceled);if(reads!=null)reads.cancel();if(task!=null)task.cancel(true);
        task=null;canceled=null;reads=null;activeJob=null;
    }
    @Override public void onDestroy(){destroyed=true;stopCurrent();main.removeCallbacksAndMessages(null);worker.shutdownNow();super.onDestroy();}
}
