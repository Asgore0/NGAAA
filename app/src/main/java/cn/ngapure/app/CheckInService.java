package cn.ngapure.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Best-effort daily task; Android chooses the exact execution time. */
public final class CheckInService extends JobService {
    private static final int JOB_ID = 26018;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final StartupTaskQueue STARTUP = new StartupTaskQueue("NgaCheckInStartup");
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final Object OPEN_LOCK = new Object();
    private static NgaStore.CheckInSession latestOpenSession;
    private static boolean openScheduled;
    private Future<?> work;
    private volatile JobParameters activeJob;

    public static void schedule(Context context) {
        Context app = context.getApplicationContext();
        JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        if (!new NgaStore(app).autoCheckIn()) { scheduler.cancel(JOB_ID); return; }
        if (scheduler.getPendingJob(JOB_ID) != null) return;
        JobInfo task = new JobInfo.Builder(JOB_ID, new ComponentName(app, CheckInService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true)
                .setPeriodic(24L * 60 * 60 * 1000, 6L * 60 * 60 * 1000)
                .setPersisted(true)
                .build();
        if (scheduler.schedule(task) != JobScheduler.RESULT_SUCCESS)
            new NgaStore(app).saveCheckInResult(false, "系统未能安排后台签到；打开应用时仍会尝试补签");
    }

    /** Startup caller returns before scheduler/session/Keystore work; page lifetime never cancels it. */
    public static void checkOnOpenAsync(Context context) {
        Context app=context.getApplicationContext();
        STARTUP.submit(()->checkOnOpen(app));
    }
    public static String startupDiagnostics(){return STARTUP.diagnostics();}

    /** Captures the exact session on its caller; UI startup uses checkOnOpenAsync instead. */
    public static void checkOnOpen(Context context) {
        Context app = context.getApplicationContext();
        schedule(app);
        NgaStore store = new NgaStore(app);
        if (!store.autoCheckIn() || alreadyConfirmedToday(store)) return;
        synchronized (OPEN_LOCK) {
            latestOpenSession = store.captureCheckInSession();
            if (openScheduled) return;
            openScheduled = true;
            enqueueOpen(app);
        }
    }

    /** One current foreground task plus its latest requested exact session, never a request backlog. */
    private static void enqueueOpen(Context app) {
        try { WORKER.execute(() -> drainOpen(app)); }
        catch (RejectedExecutionException unavailable) { latestOpenSession = null; openScheduled = false; }
    }

    private static void drainOpen(Context app) {
        final NgaStore.CheckInSession requested;
        synchronized (OPEN_LOCK) { requested = latestOpenSession; latestOpenSession = null; }
        try {
            NgaStore store = new NgaStore(app);
            if (!store.autoCheckIn() || alreadyConfirmedToday(store)) return;
            // A queued old account must not silently become a request for another account.
            if (sameSession(requested, store.captureCheckInSession())) perform(app, "打开应用补签", requested);
        } finally {
            synchronized (OPEN_LOCK) {
                if (latestOpenSession != null && !sameSession(latestOpenSession, requested)) enqueueOpen(app);
                else { latestOpenSession = null; openScheduled = false; }
            }
        }
    }

    private static boolean sameSession(NgaStore.CheckInSession a, NgaStore.CheckInSession b) {
        return a != null && b != null && a.host.equals(b.host) && a.account.equals(b.account) && a.cookie.equals(b.cookie);
    }

    private static void perform(Context context, String source) { perform(context, source, null); }

    private static boolean alreadyConfirmedToday(NgaStore store) {
        // LoginPreferences supplies the authoritative account's day, including failed-write rollback.
        // Read the day before the clock: crossing midnight during the read must not skip the new day.
        String confirmedDay = store.lastCheckInDay();
        return NgaStore.today().equals(confirmedDay);
    }

    private static void perform(Context context, String source, NgaStore.CheckInSession expected) {
        NgaStore store = new NgaStore(context);
        if (!store.autoCheckIn() || alreadyConfirmedToday(store)) return;
        if (!store.loggedIn()) { store.saveCheckInResultForAccount("", false, "等待登录，自动签到尚未执行"); return; }
        if (!RUNNING.compareAndSet(false, true)) return;
        try {
            new NgaApi(context).checkIn(source, expected);
        } catch (Exception error) {
            // NgaApi already saved any failure only to that request's still-current account.
        } finally { RUNNING.set(false); }
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        activeJob = parameters;
        work = WORKER.submit(() -> {
            perform(getApplicationContext(), "后台定时签到");
            if (activeJob == parameters) {
                activeJob = null;
                jobFinished(parameters, false);
            }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        if (activeJob == parameters) {
            activeJob = null;
            if (work != null) work.cancel(true);
        }
        return true;
    }
}
