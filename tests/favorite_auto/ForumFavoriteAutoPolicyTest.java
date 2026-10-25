package cn.ngapure.app;

/** Fake-clock scheduling checks. No Android, network, credentials or production preference writes. */
public final class ForumFavoriteAutoPolicyTest {
    private static int assertions, scenarios;
    private static final String A="opaque-exact-session-a", B="opaque-exact-session-b";
    private static void check(boolean value,String message) {
        assertions++;if(!value)throw new AssertionError(message);
    }
    private static void done(){scenarios++;}
    private static ForumFavoriteAutoPolicy ready(Object token,long now) {
        ForumFavoriteAutoPolicy policy=new ForumFavoriteAutoPolicy();
        policy.foreground(token,A,now,false);
        ForumFavoriteAutoPolicy.Ticket ticket=policy.take(now);
        check(ticket!=null&&ticket.matches(A),"cold startup immediately requests authenticated pull");
        policy.complete(ticket,ForumFavoriteAutoPolicy.Result.SUCCESS,now);
        return policy;
    }
    public static void main(String[] args) {
        coldAndPassive();freshResume();expiryBoundary();pendingResume();replacementTokens();
        accountChange();anonymous();requestDuringRead();requestDuringWriteFailure();retrySequence();
        backgroundStopsRetry();resumeRestartsTransientBudget();refusalsStopLoop();networkRestores();
        identityRefreshUnblocks();oldFailureCannotBlockNewIdentity();forceRefresh();explicitWhileBackground();
        newProcessFreshness();clockRollback();duplicateForeground();noParallelTickets();
        System.out.println("Favorite auto tests passed: "+scenarios+" scenarios, "+assertions+" assertions");
    }
    private static void coldAndPassive(){
        ForumFavoriteAutoPolicy p=new ForumFavoriteAutoPolicy();check(p.take(100)==null,"constructor passive");
        Object token=new Object();p.foreground(token,A,100,false);check(p.delay(100)==0,"cold foreground due");
        ForumFavoriteAutoPolicy.Ticket t=p.take(100);check(t!=null&&p.running(),"one active pull");
        p.complete(t,ForumFavoriteAutoPolicy.Result.SUCCESS,150);check(p.loaded()&&!p.running()&&p.delay(150)==-1,"success becomes fresh");done();
    }
    private static void freshResume(){Object t=new Object();ForumFavoriteAutoPolicy p=ready(t,100);p.background(t);p.foreground(t,A,200,false);check(p.take(200)==null,"fresh resume throttled");done();}
    private static void expiryBoundary(){Object t=new Object();ForumFavoriteAutoPolicy p=ready(t,100);p.background(t);p.foreground(t,A,60099,false);check(p.take(60099)==null,"before expiry fresh");p.background(t);p.foreground(t,A,60100,false);check(p.take(60100)!=null,"expiry requests pull");done();}
    private static void pendingResume(){Object t=new Object();ForumFavoriteAutoPolicy p=ready(t,100);p.background(t);p.foreground(t,A,101,true);check(p.take(101)!=null,"pending bypasses freshness");done();}
    private static void replacementTokens(){Object old=new Object(),next=new Object();ForumFavoriteAutoPolicy p=ready(old,100);p.foreground(next,A,101,false);p.background(old);check(p.foreground(),"old stop cannot hide new Activity");p.explicit();ForumFavoriteAutoPolicy.Ticket ticket=p.take(102);p.complete(ticket,ForumFavoriteAutoPolicy.Result.TRANSIENT,103);check(p.delay(103)==3000,"replacement remains eligible for retry");p.background(next);check(!p.foreground()&&p.delay(103)==-1,"last stop cancels timer");done();}
    private static void accountChange(){Object t=new Object();ForumFavoriteAutoPolicy p=ready(t,100);check(p.identity(B),"account change detected");check(!p.loaded(),"old freshness cleared");ForumFavoriteAutoPolicy.Ticket next=p.take(101);check(next!=null&&next.matches(B)&&!next.matches(A),"new identity pulls even while fresh");done();}
    private static void anonymous(){Object t=new Object();ForumFavoriteAutoPolicy p=new ForumFavoriteAutoPolicy();p.foreground(t,"",100,true);p.explicit();p.networkAvailable();p.refresh(100,true,true);check(p.take(100)==null&&p.delay(100)==-1,"anonymous never schedules network");done();}
    private static void requestDuringRead(){Object token=new Object();ForumFavoriteAutoPolicy p=new ForumFavoriteAutoPolicy();p.foreground(token,A,100,false);ForumFavoriteAutoPolicy.Ticket first=p.take(100);p.explicit();check(p.take(100)==null,"request during read cannot run in parallel");p.complete(first,ForumFavoriteAutoPolicy.Result.SUCCESS,101);check(p.delay(101)==0&&p.take(101)!=null,"queued tap survives successful pull");done();}
    private static void requestDuringWriteFailure(){Object token=new Object();ForumFavoriteAutoPolicy p=new ForumFavoriteAutoPolicy();p.foreground(token,A,100,true);ForumFavoriteAutoPolicy.Ticket first=p.take(100);p.explicit();p.complete(first,ForumFavoriteAutoPolicy.Result.TRANSIENT,101);check(p.delay(101)==0,"new intent bypasses older failure backoff");check(p.take(101)!=null,"new intent retained");done();}
    private static void retrySequence(){Object token=new Object();ForumFavoriteAutoPolicy p=new ForumFavoriteAutoPolicy();p.foreground(token,A,100,true);long now=100;long[] expected={3000,10000,30000,60000};for(long delay:expected){ForumFavoriteAutoPolicy.Ticket t=p.take(now);check(t!=null,"attempt available");p.complete(t,ForumFavoriteAutoPolicy.Result.TRANSIENT,now);check(p.delay(now)==delay,"bounded retry interval");check(p.take(now+delay-1)==null,"cannot run before deadline");now+=delay;}ForumFavoriteAutoPolicy.Ticket last=p.take(now);check(last!=null,"last retry due");p.complete(last,ForumFavoriteAutoPolicy.Result.TRANSIENT,now);check(p.delay(now)==-1&&p.take(now+100000)==null,"retry budget exhausted without loop");done();}
    private static void backgroundStopsRetry(){Object token=new Object();ForumFavoriteAutoPolicy p=new ForumFavoriteAutoPolicy();p.foreground(token,A,100,false);ForumFavoriteAutoPolicy.Ticket t=p.take(100);p.background(token);p.complete(t,ForumFavoriteAutoPolicy.Result.TRANSIENT,101);check(p.delay(101)==-1,"completion in background has no timer");done();}
    private static void resumeRestartsTransientBudget(){Object token=new Object();ForumFavoriteAutoPolicy p=new ForumFavoriteAutoPolicy();p.foreground(token,A,100,false);long now=100;for(long delay:new long[]{3000,10000,30000,60000}){ForumFavoriteAutoPolicy.Ticket t=p.take(now);p.complete(t,ForumFavoriteAutoPolicy.Result.TRANSIENT,now);now+=delay;}p.complete(p.take(now),ForumFavoriteAutoPolicy.Result.TRANSIENT,now);p.background(token);p.foreground(token,A,now+1,true);ForumFavoriteAutoPolicy.Ticket resumed=p.take(now+1);check(resumed!=null,"genuine foreground return can retry pending after exhausted offline session");p.complete(resumed,ForumFavoriteAutoPolicy.Result.TRANSIENT,now+1);check(p.delay(now+1)==3000,"new foreground receives bounded retry budget");done();}
    private static void refusalsStopLoop(){Object token=new Object();ForumFavoriteAutoPolicy p=new ForumFavoriteAutoPolicy();p.foreground(token,A,100,true);p.complete(p.take(100),ForumFavoriteAutoPolicy.Result.BLOCKED,100);p.networkAvailable();p.refresh(1000,false,true);p.background(token);p.foreground(token,A,100000,true);check(p.delay(100000)==-1&&p.take(100000)==null,"security refusal blocks automatic replay, network and resume");done();}
    private static void networkRestores(){Object token=new Object();ForumFavoriteAutoPolicy p=ready(token,100);p.networkAvailable();check(p.take(101)!=null,"network restoration bypasses freshness");done();}
    private static void identityRefreshUnblocks(){Object token=new Object();ForumFavoriteAutoPolicy p=new ForumFavoriteAutoPolicy();p.foreground(token,A,100,false);p.complete(p.take(100),ForumFavoriteAutoPolicy.Result.BLOCKED,100);p.identity(B);check(p.take(101)!=null,"new authenticated session can recover refusal");done();}
    private static void oldFailureCannotBlockNewIdentity(){Object token=new Object();ForumFavoriteAutoPolicy p=new ForumFavoriteAutoPolicy();p.foreground(token,A,100,false);ForumFavoriteAutoPolicy.Ticket old=p.take(100);p.identity(B);p.complete(old,ForumFavoriteAutoPolicy.Result.BLOCKED,101);ForumFavoriteAutoPolicy.Ticket next=p.take(101);check(next!=null&&next.matches(B),"old refusal cannot block new account");p.complete(next,ForumFavoriteAutoPolicy.Result.SUCCESS,102);check(p.loaded(),"new account succeeds");done();}
    private static void forceRefresh(){Object token=new Object();ForumFavoriteAutoPolicy p=ready(token,100);p.refresh(101,true,false);check(p.take(101)!=null,"manual immediate refresh bypasses freshness");done();}
    private static void explicitWhileBackground(){Object token=new Object();ForumFavoriteAutoPolicy p=ready(token,100);p.explicit();p.background(token);check(p.take(101)!=null,"persisted deliberate tap survives immediate stop");done();}
    private static void newProcessFreshness(){Object token=new Object();ForumFavoriteAutoPolicy old=ready(token,100);check(old.loaded(),"first process fresh");ForumFavoriteAutoPolicy restarted=new ForumFavoriteAutoPolicy();restarted.foreground(new Object(),A,101,false);check(restarted.take(101)!=null,"new process always pulls cloud independently of local content");done();}
    private static void clockRollback(){Object token=new Object();ForumFavoriteAutoPolicy p=ready(token,100);p.background(token);p.foreground(token,A,50,false);check(p.take(50)!=null,"elapsed clock rollback invalidates freshness");done();}
    private static void duplicateForeground(){Object token=new Object();ForumFavoriteAutoPolicy p=ready(token,100);p.foreground(token,A,70000,false);check(p.take(70000)==null,"duplicate token callback does not schedule another foreground");p.background(token);check(!p.foreground(),"duplicate callback does not retain token twice");done();}
    private static void noParallelTickets(){Object token=new Object();ForumFavoriteAutoPolicy p=new ForumFavoriteAutoPolicy();p.foreground(token,A,100,false);ForumFavoriteAutoPolicy.Ticket current=p.take(100);for(int i=0;i<10;i++){p.explicit();check(p.take(100)==null,"serialized ticket");}p.complete(current,ForumFavoriteAutoPolicy.Result.SUCCESS,101);ForumFavoriteAutoPolicy.Ticket latest=p.take(101);check(latest!=null,"coalesced latest work survives");p.complete(latest,ForumFavoriteAutoPolicy.Result.SUCCESS,102);check(p.take(102)==null,"coalesced work does not replay ten writes");done();}
}
