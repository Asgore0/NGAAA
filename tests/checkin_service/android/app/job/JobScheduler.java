package android.app.job;
import java.util.concurrent.atomic.AtomicInteger;
public class JobScheduler { public static final int RESULT_SUCCESS=1; public final AtomicInteger schedules=new AtomicInteger(),cancels=new AtomicInteger(); public void cancel(int id){cancels.incrementAndGet();} public JobInfo getPendingJob(int id){return null;} public int schedule(JobInfo info){schedules.incrementAndGet();return RESULT_SUCCESS;} }
