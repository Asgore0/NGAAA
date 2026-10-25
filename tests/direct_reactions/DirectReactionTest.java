package cn.ngapure.app;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic worker/UI queues, no Android, credentials, sleeps or live server writes. */
public final class DirectReactionTest {
    private static int assertions;
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static final class Tasks implements Executor {
        final Queue<Runnable> values = new ArrayDeque<>();
        public void execute(Runnable value) { values.add(value); }
        void next() { Runnable value = values.poll(); if (value == null) throw new AssertionError("missing task"); value.run(); }
        void drain() { while (!values.isEmpty()) next(); }
    }
    private static NativeReactionController.Result up() { return NativeReactionController.Result.acknowledged(true, 1, "已点赞"); }
    public static void main(String[] args) {
        Tasks worker = new Tasks(), ui = new Tasks();
        NativeReactionController c = new NativeReactionController(worker, ui);
        List<NativeReactionController.Snapshot> seen = new ArrayList<>();
        NativeReactionController.Subscription first = c.observe("A", (tid,pid,state) -> seen.add(state));
        AtomicInteger calls = new AtomicInteger();
        NativeReactionController.Operation like = resolve -> { calls.incrementAndGet(); return up(); };
        check(c.submit("A", "10", "0", false, like), "first direct tap accepted");
        check(c.snapshot("A", "10", "0").busy && calls.get() == 0, "busy is synchronous and work asynchronous");
        check(!c.submit("A", "10", "0", false, like), "repeat tap cannot enqueue");
        check(!c.submit("A", "10", "0", false, resolve -> NativeReactionController.Result.acknowledged(true,-1,"已点踩")), "opposite direction shares the same gate");
        check(worker.values.size() == 1, "one worker per floor");
        first.close(); worker.next(); ui.drain();
        check(calls.get() == 1 && seen.isEmpty(), "confirmed write completes after page closes without stale callback");
        NativeReactionController.Snapshot acknowledged = c.snapshot("A", "10", "0");
        check(!acknowledged.busy && acknowledged.selectionKnown && acknowledged.selected == 1, "recreated page can read the acknowledged selection");
        check(acknowledged.acknowledgedVersion > 0, "acknowledgement has an ordered revision");
        c.observe("A", (tid,pid,state) -> seen.add(state));
        check(c.snapshot("A", "10", "0").acknowledgedVersion == acknowledged.acknowledgedVersion, "resubscription does not fabricate another completion");
        long stable = c.revision();
        for (int i = 0; i < 4; i++) { NativeReactionController.Subscription s = c.observe("A", (tid,pid,state) -> {}); s.close(); }
        check(c.revision() == stable && worker.values.isEmpty(), "observer lifecycle does not trigger counts or toggle retries");
        check(c.submit("A", "10", "0", false, resolve -> NativeReactionController.Result.acknowledged(true,0,"已撤销")), "second independent tap may remove like");
        worker.next(); ui.drain();
        check(seen.size() == 1 && !seen.get(0).busy && seen.get(0).selected == 0, "queued old busy notification cannot overwrite final result");
        check(c.submit("A", "10", "0", false, like), "next independent tap may add like again"); worker.next(); ui.drain();
        long beforeZero = c.snapshot("A", "10", "0").acknowledgedVersion;
        check(c.submit("A", "10", "0", false, resolve -> NativeReactionController.Result.acknowledged(false,0,"状态未更新")), "zero-flag response accepted"); worker.next(); ui.drain();
        check(c.snapshot("A", "10", "0").selected == 1 && c.snapshot("A", "10", "0").selectionKnown, "zero flags preserve previously acknowledged selection");
        check(c.snapshot("A", "10", "0").acknowledgedVersion > beforeZero, "zero flags still invalidate older counts");

        NativeReactionController.Operation unknown = resolve -> { calls.incrementAndGet(); throw new NativeReactionController.Failure("服务器返回格式无法确认",true); };
        check(c.submit("A", "10", "22", false, unknown), "independent reply can submit");worker.next();ui.drain();
        NativeReactionController.Snapshot uncertain = c.snapshot("A", "10", "22");
        check(uncertain.uncertain && !uncertain.busy && uncertain.message.contains("返回格式"), "unknown retains concrete failure and releases busy");
        int beforeUnknownRetry = calls.get();
        check(!c.submit("A", "10", "22", false, like), "uncertain toggle cannot retry");
        check(calls.get() == beforeUnknownRetry && worker.values.isEmpty(), "no implicit retransmission");
        AtomicInteger resolutions = new AtomicInteger();
        check(c.submit("A", "10", "22", true, resolve -> { check(resolve,"recovery is explicitly local-only");resolutions.incrementAndGet();return NativeReactionController.Result.resolved(); }), "explicit inline recovery accepted");
        check(!c.submit("A", "10", "22", false, like), "toggle remains locked during recovery");worker.next();ui.drain();
        check(resolutions.get() == 1 && calls.get() == beforeUnknownRetry && worker.values.isEmpty(), "resolution sends nothing and queues no followup");
        check(!c.snapshot("A", "10", "22").uncertain && c.snapshot("A", "10", "22").acknowledgedVersion == 0, "local resolution is not a server acknowledgement");
        check(c.submit("A", "10", "22", false, like), "a later separate tap is required");worker.next();ui.drain();
        check(calls.get() == beforeUnknownRetry + 1, "only subsequent direct tap sends");
        check(!c.submit("A", "10", "99", true, like), "cannot resolve an unrelated floor");

        int notifications = seen.size();
        check(c.submit("A", "11", "0", false, like), "another route may have work");
        c.retireScope("A");
        check(c.snapshot("A", "10", "0") == null, "session change clears prior visible state");
        check(c.snapshot("A", "11", "0") == null, "retired pending state is not exposed to new windows");
        check(!c.submit("A", "11", "0", false, like), "scope retirement does not unlock a running toggle");
        check(c.submit("B", "11", "0", false, like), "another account is independently routed");
        worker.next();ui.drain();
        check(c.snapshot("A", "11", "0") == null && seen.size() == notifications, "late old-account result cannot revive cleared state or callback");worker.next();ui.drain();
        check(c.snapshot("B", "11", "0").selected == 1 && seen.size() == notifications, "new account result does not notify old account subscription");

        Tasks abaWorker = new Tasks(), abaUi = new Tasks();
        NativeReactionController aba = new NativeReactionController(abaWorker, abaUi);
        NativeReactionController.Subscription oldA = aba.observe("A", (tid,pid,state)->{});
        aba.submit("A","15","0",false,like);abaWorker.next();abaUi.drain();
        aba.submit("A","15","0",false,resolve->NativeReactionController.Result.acknowledged(true,0,"已撤销"));
        oldA.close();aba.retireScope("A");
        check(aba.snapshot("A","15","0")==null,"leaving account hides old selected state");
        List<NativeReactionController.Snapshot> returned = new ArrayList<>();
        aba.observe("A",(tid,pid,state)->returned.add(state));
        check(aba.snapshot("A","15","0").busy&&!aba.snapshot("A","15","0").selectionKnown,"exact account round trip restores only in-flight visibility");
        check(!aba.submit("A","15","0",false,like)&&abaWorker.values.size()==1,"returning account still cannot double submit");
        abaWorker.next();abaUi.drain();
        check(returned.size()==1&&!returned.get(0).busy&&returned.get(0).selected==0&&returned.get(0).selectionKnown,"returned account receives eventual confirmed result once");
        aba.submit("A","16","0",false,like);aba.retireScope("A");
        int pendingBeforeActivate=abaWorker.values.size();aba.activateScope("A");
        check(aba.snapshot("A","16","0").busy&&abaWorker.values.size()==pendingBeforeActivate,"entering reader restores exact in-flight scope without dispatch");
        abaWorker.next();abaUi.drain();
        check(aba.snapshot("A","16","0").selected==1,"completion before reader observer attaches is retained");
        long completedBeforeObserve=aba.snapshot("A","16","0").acknowledgedVersion;
        aba.observe("A",(tid,pid,state)->{});
        check(aba.snapshot("A","16","0").acknowledgedVersion==completedBeforeObserve&&abaWorker.values.isEmpty(),"late reader subscription sees same completion without replay");

        AtomicInteger sentAfterSwitch = new AtomicInteger();
        String[] currentSession = {"B"};
        check(c.submit("B", "12", "0", false, resolve -> {
            if (!"B".equals(currentSession[0])) throw new NativeReactionController.Failure("账号或线路已变化",false);
            sentAfterSwitch.incrementAndGet(); return up();
        }), "queued session-sensitive operation accepted");currentSession[0]="C";worker.next();ui.drain();
        check(!c.snapshot("B", "12", "0").busy && !c.snapshot("B", "12", "0").uncertain && sentAfterSwitch.get() == 0, "worker session rejection makes no request and leaves no busy state");
        NativeReactionController rejected = new NativeReactionController(task -> { throw new RejectedExecutionException(); }, Runnable::run);
        check(!rejected.submit("A", "10", "0", false, like), "full worker reports no accepted task");
        check(!rejected.snapshot("A", "10", "0").busy && !rejected.snapshot("A", "10", "0").uncertain, "worker rejection cannot strand busy or fabricate unknown");
        check(!rejected.submit("A", "10", "0", false, like) && !rejected.snapshot("A", "10", "0").busy, "rejection permits a fresh tap without stuck gate");

        NativeReactionController bounded = new NativeReactionController(Runnable::run,Runnable::run);
        for(int i=1;i<=300;i++)bounded.submit("bounded",String.valueOf(i),"0",false,resolve -> up());
        check(bounded.snapshot("bounded","1","0")==null && bounded.snapshot("bounded","300","0").selected==1,"bounded idle history retains recent results");
        check(bounded.submit("bounded","400","0",false,resolve->{throw new IllegalStateException();}),"unexpected transport runtime failure settles accepted task");
        check(!bounded.snapshot("bounded","400","0").busy&&bounded.snapshot("bounded","400","0").uncertain,"unexpected failure is fail-closed rather than automatic retry");
        check(!bounded.submit("bounded","400","0",false,like),"unexpected result cannot retransmit");
        check(bounded.submit("bounded","400","0",true,resolve->{throw new NativeReactionController.Failure("本机保存失败",false);}),"failed recovery is handled separately");
        check(!bounded.snapshot("bounded","400","0").busy&&bounded.snapshot("bounded","400","0").uncertain,"failed recovery retains protection");
        System.out.println("Direct reaction checks passed: " + assertions);
    }
}
