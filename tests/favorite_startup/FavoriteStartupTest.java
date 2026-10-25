package cn.ngapure.app;

import android.content.Context;
import android.net.Network;
import android.os.Handler;
import android.os.SystemClock;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Actual coordinator, token registry, startup coalescer and auto policy with deterministic boundaries. */
public final class FavoriteStartupTest {
 private static int checks;private static final ExecutorService UI=Executors.newSingleThreadExecutor(),EVENT=Executors.newSingleThreadExecutor();
 private static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);System.out.println("PASS "+text);}
 private static void await(CountDownLatch latch,String text)throws Exception{check(latch.await(3,TimeUnit.SECONDS),text);}
 private static void block(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new IllegalStateException("fixture wait expired");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
 private static void ui(Runnable work)throws Exception{UI.submit(()->{NgaStore.mainThread=Thread.currentThread();work.run();}).get(3,TimeUnit.SECONDS);}
 private static ForumFavoritesCoordinator create(Context app)throws Exception{
  NgaStore.reset();CloudForumFavorites.reset();Handler.clear();SystemClock.reset();Constructor<ForumFavoritesCoordinator> c=ForumFavoritesCoordinator.class.getDeclaredConstructor(Context.class);c.setAccessible(true);return c.newInstance(app);
 }
 private static StartupTaskQueue startup(ForumFavoritesCoordinator c)throws Exception{Field f=ForumFavoritesCoordinator.class.getDeclaredField("foregroundStartup");f.setAccessible(true);return (StartupTaskQueue)f.get(c);}
 private static StartupTaskQueue retry(ForumFavoritesCoordinator c)throws Exception{Field f=ForumFavoritesCoordinator.class.getDeclaredField("retryStartup");f.setAccessible(true);return (StartupTaskQueue)f.get(c);}
 private static String scope()throws Exception{Method m=ForumFavoritesCoordinator.class.getDeclaredMethod("scope",NgaStore.CheckInSession.class);m.setAccessible(true);return (String)m.invoke(null,NgaStore.current);}
 private static void idle(ForumFavoritesCoordinator c)throws Exception{
  check(startup(c).awaitIdleForTests(3000),"startup queue drained");
  check(retry(c).awaitIdleForTests(3000),"automatic retry queue drained");
  for(int i=0;i<12;i++){c.executor().submit(()->{}).get(3,TimeUnit.SECONDS);if(!c.running()){Handler.drain();return;}}
  throw new AssertionError("coordinator did not drain");
 }
 private static void close(ForumFavoritesCoordinator c,Object... tokens)throws Exception{for(Object token:tokens)c.onBackground(token);idle(c);c.executor().shutdownNow();Handler.clear();}
 private static void stopDuringCapture(boolean pending)throws Exception{
  Context app=new Context();ForumFavoritesCoordinator c=create(app);Object token=new Object();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  Runnable stall=()->{entered.countDown();block(release);};if(pending)NgaStore.beforePending.set(stall);else NgaStore.beforeCapture.set(stall);
  try{ui(()->c.onForeground(token));await(entered,pending?"pending read stalled off state lock":"session capture stalled off UI");ui(()->c.onBackground(token));
   check(release.getCount()==1,"onBackground returns while preparation remains blocked");check(!c.running(),"state lock remains readable while preparation is blocked");
   release.countDown();idle(c);check(CloudForumFavorites.reads.get()==0&&CloudForumFavorites.writes.get()==0,"stopped-before-prepare intent starts no HTTP");
   check(app.connectivity.registrations.get()==0,"stopped preparation cannot register network callback");check(NgaStore.mainCaptures.get()==0,"foreground and background callbacks read no credentials");
  }finally{release.countDown();close(c,token);}
 }
 private static void coalescedActivities()throws Exception{
  Context app=new Context();ForumFavoritesCoordinator c=create(app);Object a=new Object(),b=new Object();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  NgaStore.beforeCapture.set(()->{entered.countDown();block(release);});
  try{ui(()->c.onForeground(a));await(entered,"first activity capture blocked");ui(()->{for(int i=0;i<1000;i++)c.onForeground(a);c.onForeground(b);c.onBackground(a);});
   check(release.getCount()==1,"foreground burst and old activity stop return before crypto");release.countDown();idle(c);
   check(CloudForumFavorites.reads.get()==1,"coalesced batch preserves replacement activity without duplicate HTTP");check(c.status(scope()).loaded,"replacement activity receives loaded cloud state");
   check(app.connectivity.registrations.get()==1&&app.connectivity.unregistrations.get()==0,"old stop cannot unregister replacement callback");
   check(app.connectivity.registeredOn!=NgaStore.mainThread&&NgaStore.mainCaptures.get()==0,"network registration and capture run on preparation worker");
   check(startup(c).diagnostics().contains("runs=2 "),"burst occupies one active and one latest preparation");ui(()->c.onBackground(b));check(app.connectivity.unregistrations.get()==1,"last foreground owner unregisters callback");
  }finally{release.countDown();close(c,a,b);}
 }
 private static void sameTokenResume()throws Exception{
  Context app=new Context();ForumFavoritesCoordinator c=create(app);Object token=new Object();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  NgaStore.beforeCapture.set(()->{entered.countDown();block(release);});
  try{ui(()->c.onForeground(token));await(entered,"old generation capture blocked");ui(()->{c.onBackground(token);c.onForeground(token);});release.countDown();idle(c);
   check(CloudForumFavorites.reads.get()==1&&app.connectivity.registrations.get()==1,"only resumed generation starts the foreground read");
  }finally{release.countDown();close(c,token);}
 }
 private static void captureFailureRecovery()throws Exception{
  Context app=new Context();ForumFavoritesCoordinator c=create(app);Object token=new Object();
  try{NgaStore.beforeCapture.set(()->{throw new IllegalStateException("synthetic unavailable credential storage");});ui(()->c.onForeground(token));idle(c);check(CloudForumFavorites.reads.get()==0,"failed capture sends no HTTP");
   ui(()->c.onForeground(token));idle(c);check(CloudForumFavorites.reads.get()==1&&c.status(scope()).loaded,"later foreground trigger recovers without pinned queue");
  }finally{close(c,token);}
 }
 private static void queuedAccountSwitch()throws Exception{
  Context app=new Context();ForumFavoritesCoordinator c=create(app);Object token=new Object();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  c.executor().submit(()->{entered.countDown();block(release);});
  try{await(entered,"HTTP worker held before queued account task");ui(()->c.onForeground(token));check(startup(c).awaitIdleForTests(3000),"preparation independent of busy HTTP worker");
   NgaStore.CheckInSession b=new NgaStore.CheckInSession("ngabbs.com","synthetic-cookie-b","456");NgaStore.current=b;release.countDown();idle(c);
   check(CloudForumFavorites.reads.get()==1&&CloudForumFavorites.lastSession==b,"old exact-session ticket is rejected before a new current-account read");check(NgaStore.merges.get()==1,"only current-account snapshot merges");
  }finally{release.countDown();close(c,token);}
 }
 private static void oldAutomaticCaptureCannotActivate()throws Exception{
  Context app=new Context();ForumFavoritesCoordinator c=create(app);Object token=new Object();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  NgaStore.afterCapture.set(()->{entered.countDown();block(release);});
  try{ui(()->c.onForeground(token));await(entered,"old automatic session A captured before activation boundary");
   NgaStore.CheckInSession b=new NgaStore.CheckInSession("bbs.nga.cn","synthetic-cookie-b","456");NgaStore.current=b;String bScope=scope();ui(()->new NgaStore(app).activateFavoriteScope(bScope));
   check(NgaStore.activeScope.equals(bScope),"Main account transition activates B while old automatic A is held");release.countDown();idle(c);
   check(NgaStore.activations.get()==1&&NgaStore.activeScope.equals(bScope),"old automatic capture and its finally never reactivate A over B");
   check(CloudForumFavorites.reads.get()==1&&CloudForumFavorites.lastSession==b&&CloudForumFavorites.writes.get()==0,"old automatic account ticket performs no HTTP or write under A");
   check(NgaStore.merges.get()==1&&NgaStore.lastMergedScope.equals(bScope),"only fresh B response merges into B scope");
  }finally{release.countDown();close(c,token);}
 }
 private static void inFlightAccountSwitch()throws Exception{
  Context app=new Context();ForumFavoritesCoordinator c=create(app);Object token=new Object();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  CloudForumFavorites.beforeReadReturn.set(()->{entered.countDown();block(release);});
  try{ui(()->c.onForeground(token));await(entered,"old account response held in flight");NgaStore.CheckInSession b=new NgaStore.CheckInSession("bbs.nga.cn","synthetic-cookie-b","456");NgaStore.current=b;ui(()->c.onForeground(token));
   check(startup(c).awaitIdleForTests(3000),"new account preparation completes while old HTTP is held");release.countDown();idle(c);
   check(CloudForumFavorites.reads.get()==2&&CloudForumFavorites.lastSession==b,"new account gets its own request after old response");check(NgaStore.merges.get()==1,"late old-account response cannot merge into current state");
  }finally{release.countDown();close(c,token);}
 }
 private static void backgroundDuringFinally()throws Exception{
  Context app=new Context();ForumFavoritesCoordinator c=create(app);Object token=new Object();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  CloudForumFavorites.beforeReadReturn.set(()->NgaStore.beforeCapture.set(()->NgaStore.beforeCapture.set(()->{entered.countDown();block(release);}))); 
  try{ui(()->c.onForeground(token));await(entered,"HTTP finally credential capture held");ui(()->c.onBackground(token));check(release.getCount()==1,"onBackground never waits for HTTP-finally crypto");check(c.running(),"running metadata readable until finally completes");release.countDown();idle(c);
   check(app.connectivity.unregistrations.get()==1&&!c.running(),"finally releases ownership after foreground stopped");
  }finally{release.countDown();close(c,token);}
 }
 private static void backgroundDuringNetworkRestore()throws Exception{
  Context app=new Context();ForumFavoritesCoordinator c=create(app);Object token=new Object();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);Future<?> restore=null;
  try{ui(()->c.onForeground(token));idle(c);app.connectivity.available=false;app.connectivity.callback.onLost(new Network());
   NgaStore.beforeCapture.set(()->{entered.countDown();block(release);});app.connectivity.available=true;restore=EVENT.submit(()->app.connectivity.callback.onAvailable(new Network()));await(entered,"network-restoration capture held");
   ui(()->c.onBackground(token));check(release.getCount()==1,"network-restoration crypto never blocks onBackground");release.countDown();restore.get(3,TimeUnit.SECONDS);idle(c);
   check(CloudForumFavorites.reads.get()==1,"late restoration cannot start automatic HTTP after stop");
  }finally{release.countDown();if(restore!=null)restore.get(3,TimeUnit.SECONDS);close(c,token);}
 }
 private static void explicitMutationSurvivesBackground()throws Exception{
  Context app=new Context();ForumFavoritesCoordinator c=create(app);Object token=new Object();
  try{synchronized(NgaStore.pending){NgaStore.pending.add(new ForumFavoriteSyncState.Change("f:99",true,1));}c.schedule();c.onBackground(token);idle(c);
   check(CloudForumFavorites.writes.get()==1&&NgaStore.confirmed.get()==1,"explicit durable favorite intent still writes while backgrounded");check(c.status(scope()).pending==0,"confirmed mutation clears pending intent");
   int reads=CloudForumFavorites.reads.get();c.requestRefresh(true);idle(c);check(CloudForumFavorites.reads.get()==reads+1,"explicit refresh keeps immediate background semantics");
   check(NgaStore.activations.get()==2&&NgaStore.activeScope.equals(scope()),"explicit schedule and refresh retain synchronous projection activation");
  }finally{close(c,token);}
 }
 private static void automaticRetry(boolean stop)throws Exception{
  Context app=new Context();ForumFavoritesCoordinator c=create(app);Object token=new Object();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  try{CloudForumFavorites.beforeReadReturn.set(()->{throw new IllegalStateException("synthetic transient failure");});ui(()->c.onForeground(token));idle(c);
   check(CloudForumFavorites.reads.get()==1&&!c.status(scope()).loaded,"transient response leaves a scheduled retry");SystemClock.advance(4000);
   NgaStore.beforeCapture.set(()->{entered.countDown();block(release);});ui(Handler::drainDelayed);await(entered,"due retry captures on its own worker");
   check(release.getCount()==1&&NgaStore.mainCaptures.get()==0,"main timer returns without waiting for retry credentials");
   if(stop){ui(()->c.onBackground(token));check(release.getCount()==1,"onBackground also returns while retry capture is blocked");}
   else{ui(()->c.onForeground(token));check(release.getCount()==1,"new foreground intent is not blocked by retry preparation");}
   release.countDown();idle(c);
   check(CloudForumFavorites.reads.get()==(stop?1:2),stop?"stopped automatic retry starts no late HTTP":"due retry and new foreground intent coalesce to one recovery HTTP");
   check(NgaStore.mainCaptures.get()==0,"automatic retries perform no UI credential reads");if(!stop)check(c.status(scope()).loaded,"retry success restores loaded state");
  }finally{release.countDown();close(c,token);}
 }
 public static void main(String[] args)throws Exception{
  try{stopDuringCapture(false);stopDuringCapture(true);coalescedActivities();sameTokenResume();captureFailureRecovery();queuedAccountSwitch();oldAutomaticCaptureCannotActivate();inFlightAccountSwitch();backgroundDuringFinally();backgroundDuringNetworkRestore();explicitMutationSurvivesBackground();automaticRetry(true);automaticRetry(false);
   System.out.println("FavoriteStartup: "+checks+" assertions passed; production coordinator/policy/coalescer; synthetic storage/network; real network=0");
  }finally{UI.shutdownNow();EVENT.shutdownNow();}
 }
}
