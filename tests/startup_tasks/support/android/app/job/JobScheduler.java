package android.app.job;
import android.content.Context;import java.util.concurrent.atomic.AtomicInteger;
public class JobScheduler {public static final int RESULT_SUCCESS=1;public final AtomicInteger schedules=new AtomicInteger(),cancels=new AtomicInteger(),lookups=new AtomicInteger();public void cancel(int id){Context.touch();cancels.incrementAndGet();}public JobInfo getPendingJob(int id){Context.touch();lookups.incrementAndGet();return null;}public int schedule(JobInfo info){Context.touch();schedules.incrementAndGet();return RESULT_SUCCESS;}}
