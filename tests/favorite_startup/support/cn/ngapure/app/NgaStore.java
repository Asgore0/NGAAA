package cn.ngapure.app;
import android.content.Context;import java.util.ArrayList;import java.util.List;import java.util.Map;import java.util.concurrent.atomic.AtomicInteger;import java.util.concurrent.atomic.AtomicReference;
public final class NgaStore {
 static final class CheckInSession {final String host,cookie,account;CheckInSession(String host,String cookie,String account){this.host=host;this.cookie=cookie;this.account=account;}}
 static final CheckInSession A=new CheckInSession("bbs.nga.cn","synthetic-cookie-a","123");static volatile CheckInSession current=A;
 static final AtomicReference<Runnable> beforeCapture=new AtomicReference<>(),afterCapture=new AtomicReference<>(),beforePending=new AtomicReference<>(),beforeActivate=new AtomicReference<>();
 static final AtomicInteger captures=new AtomicInteger(),mainCaptures=new AtomicInteger(),activations=new AtomicInteger(),merges=new AtomicInteger(),confirmed=new AtomicInteger();static volatile Thread mainThread;
 static final List<ForumFavoriteSyncState.Change> pending=new ArrayList<>();
 static volatile String activeScope="",lastMergedScope="";
 public NgaStore(Context context){}
 CheckInSession captureCheckInSession(){captures.incrementAndGet();if(Thread.currentThread()==mainThread)mainCaptures.incrementAndGet();run(beforeCapture);CheckInSession captured=current;run(afterCapture);return captured;}
 void activateFavoriteScope(String scope){run(beforeActivate);activeScope=scope;activations.incrementAndGet();}
 List<ForumFavoriteSyncState.Change> pendingFavoriteChanges(String scope){run(beforePending);synchronized(pending){return new ArrayList<>(pending);}}
 void confirmFavorite(String scope,String key,long revision,boolean desired,Map<String,String[]> values){confirmed.incrementAndGet();synchronized(pending){pending.removeIf(change->change.key.equals(key)&&change.revision==revision);}}
 void mergeFavoriteForums(String scope,Map<String,String[]> values){lastMergedScope=scope;merges.incrementAndGet();}
 private static void run(AtomicReference<Runnable> hooks){Runnable hook=hooks.getAndSet(null);if(hook!=null)hook.run();}
 static void reset(){current=A;captures.set(0);mainCaptures.set(0);activations.set(0);merges.set(0);confirmed.set(0);beforeCapture.set(null);afterCapture.set(null);beforePending.set(null);beforeActivate.set(null);synchronized(pending){pending.clear();}mainThread=Thread.currentThread();activeScope="";lastMergedScope="";}
}
