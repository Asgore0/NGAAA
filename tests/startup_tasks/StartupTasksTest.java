package cn.ngapure.app;

import android.content.Context;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual startup queue and both public wrappers; only platform/session/transport boundaries are fake. */
public final class StartupTasksTest {
    private static int checks;private static void check(boolean passed,String name){checks++;if(!passed)throw new AssertionError(name);System.out.println("PASS "+name);}
    private static void await(CountDownLatch signal,String name)throws Exception{check(signal.await(3,TimeUnit.SECONDS),name);}
    private static void block(CountDownLatch release){try{if(!release.await(4,TimeUnit.SECONDS))throw new IllegalStateException("synthetic gate timeout");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}}
    private static final class Manual implements Executor {final ArrayDeque<Runnable> tasks=new ArrayDeque<>();public void execute(Runnable task){tasks.add(task);}void drain(){while(!tasks.isEmpty())tasks.remove().run();}}
    private static StartupTaskQueue startup(Class<?> type)throws Exception{Field field=type.getDeclaredField("STARTUP");field.setAccessible(true);return (StartupTaskQueue)field.get(null);}
    private static ExecutorService network(Class<?> type)throws Exception{Field field=type.getDeclaredField("WORKER");field.setAccessible(true);return (ExecutorService)field.get(null);}
    private static long metric(String value,String name){for(String part:value.split(" "))if(part.startsWith(name+"="))return Long.parseLong(part.substring(name.length()+1));throw new AssertionError("missing metric "+name);}
    private static void queueContract()throws Exception{
        Manual executor=new Manual();StartupTaskQueue queue=new StartupTaskQueue(executor);AtomicInteger last=new AtomicInteger(),runs=new AtomicInteger();
        for(int i=1;i<=1000;i++){final int value=i;queue.submit(()->{runs.incrementAndGet();last.set(value);});}
        check(executor.tasks.size()==1&&runs.get()==0,"1000 queued triggers own one runnable before execution");executor.drain();check(runs.get()==1&&last.get()==1000,"queued burst runs latest work only");check(queue.awaitIdleForTests(10),"queue returns to idle after drain");
        StartupTaskQueue actual=new StartupTaskQueue("SyntheticStartupTest");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);List<Integer> observed=new ArrayList<>();
        actual.submit(()->{entered.countDown();block(release);synchronized(observed){observed.add(1);}});await(entered,"first local task active");
        long before=System.nanoTime();for(int i=2;i<=1001;i++){final int value=i;actual.submit(()->{synchronized(observed){observed.add(value);}});}long nanos=System.nanoTime()-before;
        check(nanos<TimeUnit.MILLISECONDS.toNanos(150),"blocked maintenance never blocks trigger caller");check(!actual.awaitIdleForTests(10),"barrier reports real running work");release.countDown();check(actual.awaitIdleForTests(3000),"active plus latest drains");
        check(observed.size()==2&&observed.get(0)==1&&observed.get(1)==1001,"running task completes then exactly latest trigger");check(metric(actual.diagnostics(),"runs")==2&&metric(actual.diagnostics(),"submitted")==1001,"numeric diagnostics report actual work and submissions");
        AtomicInteger completed=new AtomicInteger();actual.submit(()->{throw new IllegalStateException("synthetic local failure");});check(actual.awaitIdleForTests(3000),"local failure releases queue ownership");actual.submit(completed::incrementAndGet);check(actual.awaitIdleForTests(3000)&&completed.get()==1&&metric(actual.diagnostics(),"failures")==1,"later foreground recovers after local failure");
        AtomicInteger attempts=new AtomicInteger();Manual fallback=new Manual();StartupTaskQueue rejected=new StartupTaskQueue(task->{if(attempts.getAndIncrement()==0)throw new RejectedExecutionException("synthetic unavailable executor");fallback.execute(task);});rejected.submit(completed::incrementAndGet);check(rejected.awaitIdleForTests(10),"executor rejection does not pin scheduled flag");rejected.submit(completed::incrementAndGet);fallback.drain();check(completed.get()==2&&metric(rejected.diagnostics(),"failures")==1,"later trigger retries after executor rejection");
    }
    private static void checkInWrapper()throws Exception{
        Context caller=new Context(),app=new Context();caller.application=app;Context.caller=Thread.currentThread();Context.callerWork.set(0);NgaStore.enabled=false;
        int capturesBefore=NgaStore.captures.get();CountDownLatch captureEntered=new CountDownLatch(1),captureRelease=new CountDownLatch(1);NgaStore.beforeAutoCheck.set(()->{captureEntered.countDown();block(captureRelease);});
        CheckInService.checkOnOpenAsync(caller);await(captureEntered,"check-in preference read runs asynchronously");long began=System.nanoTime();for(int i=0;i<200;i++)CheckInService.checkOnOpenAsync(caller);
        check(System.nanoTime()-began<TimeUnit.MILLISECONDS.toNanos(150)&&Context.callerWork.get()==0,"check-in UI triggers do no scheduler preference or session work");
        caller.application=null;captureRelease.countDown();check(startup(CheckInService.class).awaitIdleForTests(3000),"check-in startup outlives caller Activity reference");network(CheckInService.class).submit(()->{}).get(3,TimeUnit.SECONDS);
        long runCount=metric(CheckInService.startupDiagnostics(),"runs");check(runCount==2&&app.jobs.cancels.get()==runCount,"check-in burst coalesced and each run schedules exactly once");check(NgaApi.attempts.isEmpty(),"disabled auto-check-in sends no attempt");check(NgaStore.captures.get()==capturesBefore,"disabled check-in never captures credentials");
        NgaStore.enabled=true;NgaStore.CheckInSession a=new NgaStore.CheckInSession("bbs.nga.cn","synthetic-cookie-a","123"),b=new NgaStore.CheckInSession("ngabbs.com","synthetic-cookie-b","456");NgaStore.current=a;
        CountDownLatch networkEntered=new CountDownLatch(1),networkRelease=new CountDownLatch(1);network(CheckInService.class).submit(()->{networkEntered.countDown();block(networkRelease);});await(networkEntered,"synthetic check-in HTTP worker occupied");
        CheckInService.checkOnOpenAsync(app);check(startup(CheckInService.class).awaitIdleForTests(3000),"scheduler work independent of blocked check-in HTTP worker");NgaStore.current=b;networkRelease.countDown();network(CheckInService.class).submit(()->{}).get(3,TimeUnit.SECONDS);check(NgaApi.attempts.isEmpty(),"old exact-session task never signs new account");
        CheckInService.checkOnOpenAsync(app);check(startup(CheckInService.class).awaitIdleForTests(3000),"new current-account foreground trigger accepted");network(CheckInService.class).submit(()->{}).get(3,TimeUnit.SECONDS);synchronized(NgaApi.attempts){check(NgaApi.attempts.size()==1&&NgaApi.attempts.get(0)==b,"current account reaches existing immutable request guard");}
        CountDownLatch queuedEntered=new CountDownLatch(1),queuedRelease=new CountDownLatch(1);network(CheckInService.class).submit(()->{queuedEntered.countDown();block(queuedRelease);});await(queuedEntered,"check-in worker paused before queued task");
        CheckInService.checkOnOpenAsync(app);check(startup(CheckInService.class).awaitIdleForTests(3000),"enabled request captured before opt-out");int beforeDisabledDrain=NgaStore.captures.get();NgaStore.enabled=false;CheckInService.checkOnOpen(app);
        queuedRelease.countDown();network(CheckInService.class).submit(()->{}).get(3,TimeUnit.SECONDS);check(NgaStore.captures.get()==beforeDisabledDrain&&NgaApi.attempts.size()==1,"queued request disabled before drain adds neither credentials nor HTTP");
        NgaStore.enabled=true;CheckInService.checkOnOpenAsync(app);check(startup(CheckInService.class).awaitIdleForTests(3000),"check-in may be re-enabled after skipped queue");network(CheckInService.class).submit(()->{}).get(3,TimeUnit.SECONDS);check(NgaApi.attempts.size()==2,"skipped disabled task releases queue ownership");
        Context.caller=null;
    }
    private static void completedCheckInWrapper()throws Exception{
        Context app=new Context(),caller=new Context();caller.application=app;NgaStore.enabled=true;
        NgaStore.current=new NgaStore.CheckInSession("bbs.nga.cn","synthetic-today-cookie","123");NgaStore.clockDay="fixture-day";NgaStore.confirmed();
        int captures=NgaStore.captures.get(),attempts=NgaApi.attempts.size();long runs=metric(CheckInService.startupDiagnostics(),"runs");
        Context.caller=Thread.currentThread();Context.callerWork.set(0);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        NgaStore.beforeDayRead.set(()->{entered.countDown();block(release);});CheckInService.checkOnOpenAsync(caller);await(entered,"confirmed-day preference check occurs off caller thread");
        for(int i=0;i<200;i++)CheckInService.checkOnOpenAsync(caller);
        check(Context.callerWork.get()==0,"confirmed-day async burst leaves scheduler and preferences off UI");
        release.countDown();check(startup(CheckInService.class).awaitIdleForTests(3000),"confirmed-day burst drains without network queue");network(CheckInService.class).submit(()->{}).get(3,TimeUnit.SECONDS);
        check(metric(CheckInService.startupDiagnostics(),"runs")==runs+2&&app.jobs.schedules.get()==2,"confirmed-day coalesced runs both preserve OS scheduling");
        check(NgaStore.captures.get()==captures&&NgaApi.attempts.size()==attempts,"confirmed-day wrappers perform zero new captures and HTTP");
        NgaStore.confirmedDay="";CountDownLatch queued=new CountDownLatch(1),resume=new CountDownLatch(1);
        network(CheckInService.class).submit(()->{queued.countDown();block(resume);});await(queued,"HTTP queue held before a check becomes completed");
        CheckInService.checkOnOpenAsync(app);check(startup(CheckInService.class).awaitIdleForTests(3000),"unfinished day may enqueue existing exact-session work");captures=NgaStore.captures.get();NgaStore.confirmed();resume.countDown();network(CheckInService.class).submit(()->{}).get(3,TimeUnit.SECONDS);
        check(NgaStore.captures.get()==captures&&NgaApi.attempts.size()==attempts,"confirmation while queued avoids recapture and releases network task");
        NgaStore.clockDay="fixture-next-day";CheckInService.checkOnOpenAsync(app);check(startup(CheckInService.class).awaitIdleForTests(3000),"next-day async trigger is accepted");network(CheckInService.class).submit(()->{}).get(3,TimeUnit.SECONDS);
        check(NgaApi.attempts.size()==attempts+1,"following date reaches guarded check-in API");
        NgaStore.confirmed();NgaStore.CheckInSession b=new NgaStore.CheckInSession("bbs.nga.cn","synthetic-new-account-cookie","456");NgaStore.current=b;
        CheckInService.checkOnOpenAsync(app);check(startup(CheckInService.class).awaitIdleForTests(3000),"different-account async trigger is accepted");network(CheckInService.class).submit(()->{}).get(3,TimeUnit.SECONDS);
        check(NgaApi.attempts.size()==attempts+2&&NgaApi.attempts.get(attempts+1)==b,"new account cannot inherit an old-account confirmed day");
        NgaStore.confirmedDay="";NgaStore.confirmedAccount="";NgaStore.clockDay="fixture-day";Context.caller=null;
    }
    private static void notificationsWrapper()throws Exception{
        Context app=new Context(),caller=new Context();caller.application=app;app.getSharedPreferences("nga_unread_notifications",0).edit().putBoolean("enabled",false).putString("activeScope","obsolete-synthetic").apply();
        CountDownLatch networkEntered=new CountDownLatch(1),networkRelease=new CountDownLatch(1);network(NativeNotifications.class).submit(()->{networkEntered.countDown();block(networkRelease);});await(networkEntered,"synthetic notification HTTP worker occupied");
        int capturesBefore=NgaStore.captures.get();Context.caller=Thread.currentThread();Context.callerWork.set(0);CountDownLatch captureEntered=new CountDownLatch(1),captureRelease=new CountDownLatch(1);Context.beforePreferences.set(()->{captureEntered.countDown();block(captureRelease);});
        NativeNotifications.onForegroundAsync(caller);await(captureEntered,"notification preference read runs asynchronously");for(int i=0;i<200;i++)NativeNotifications.onForegroundAsync(caller);
        check(Context.callerWork.get()==0,"notification startup caller does no scheduler preference or credential work");caller.application=null;captureRelease.countDown();check(startup(NativeNotifications.class).awaitIdleForTests(3000),"disabled notification reconciliation does not wait for HTTP worker or Activity");
        check(app.notifications.cancels.get()>0&&app.jobs.cancels.get()==2,"disabled notifications still clear notices and cancel scheduled jobs");check(app.getSharedPreferences("nga_unread_notifications",0).getString("activeScope","").isEmpty(),"disabled notifications remove stale account projection");check(NativeCommunity.network.get()==0&&metric(NativeNotifications.startupDiagnostics(),"runs")==2,"disabled notification burst stays coalesced with zero HTTP");check(NgaStore.captures.get()==capturesBefore,"disabled notifications never capture credentials");
        networkRelease.countDown();network(NativeNotifications.class).submit(()->{}).get(3,TimeUnit.SECONDS);int cancels=app.notifications.cancels.get();NativeNotifications.setEnabled(app,false);check(app.notifications.cancels.get()>cancels,"explicit notification opt-out keeps immediate cleanup semantics");
        String diagnostic=NativeNotifications.startupDiagnostics();check(!diagnostic.contains("cookie")&&!diagnostic.contains("bbs.")&&!diagnostic.contains("ngabbs")&&metric(diagnostic,"lastNs")>=0,"diagnostics contain only numeric stage state");Context.caller=null;
    }
    public static void main(String[] args)throws Exception{try{queueContract();checkInWrapper();completedCheckInWrapper();notificationsWrapper();System.out.println("StartupTasks: "+checks+" assertions passed; real wrappers and coalescer; synthetic Android/session/transport; network=0");}finally{network(CheckInService.class).shutdownNow();network(NativeNotifications.class).shutdownNow();}}
}
