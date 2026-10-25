package cn.ngapure.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-owned, serialized favorite writes and foreground cloud reconciliation. No Activity retained. */
public final class ForumFavoritesCoordinator {
    private static ForumFavoritesCoordinator instance;
    private final Context application;
    private final NgaStore store;
    private final ExecutorService executor;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    // Account capture/activation keeps its existing serialized order without holding UI state.
    private final Object captureLock = new Object();
    private final FavoriteForegroundTokens foregroundTokens = new FavoriteForegroundTokens();
    private final StartupTaskQueue foregroundStartup = new StartupTaskQueue("NgaFavoriteStartup");
    // Separate coalescing slots: a foreground trigger must never replace a due retry (or vice versa).
    private final StartupTaskQueue retryStartup = new StartupTaskQueue("NgaFavoriteRetry");
    private final ForumFavoriteAutoPolicy policy = new ForumFavoriteAutoPolicy();
    private final List<WeakReference<Runnable>> observers = new ArrayList<>();
    private boolean notificationPosted, networkRegistered, networkAvailable;
    private String processingScope = "", processingKey = "", statusScope = "";
    private boolean loaded;
    private int cloudCount;
    private long lastSuccessfulAt;
    private Failure failure = Failure.NONE;
    private ConnectivityManager connectivity;
    private final Runnable retry = () -> retryStartup.submit(this::kick);
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { networkChanged(true); }
        @Override public void onLost(Network network) { networkChanged(hasNetwork()); }
    };

    public enum Failure { NONE, LOGIN, CHALLENGE, PERMISSION, RATE_LIMIT, NETWORK, SERVER, INVALID, UNCONFIRMED, INTERNAL }
    public static final class Snapshot {
        public final String scope;
        public final boolean loaded, running;
        public final int cloudCount, pending;
        public final long lastSuccessfulAt;
        public final Failure failure;
        private Snapshot(String scope, boolean loaded, boolean running, int cloudCount, int pending,
                long lastSuccessfulAt, Failure failure) {
            this.scope=scope; this.loaded=loaded; this.running=running; this.cloudCount=cloudCount;
            this.pending=pending; this.lastSuccessfulAt=lastSuccessfulAt; this.failure=failure;
        }
    }

    private ForumFavoritesCoordinator(Context context) {
        application = context.getApplicationContext();
        store = new NgaStore(application);
        executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            }, "NgaForumFavorites");
            thread.setDaemon(true);
            return thread;
        });
    }
    public static synchronized ForumFavoritesCoordinator get(Context context) {
        if (context == null) throw new IllegalArgumentException("缺少应用上下文");
        if (instance == null) instance = new ForumFavoritesCoordinator(context);
        return instance;
    }
    /** Opaque stable account scope. Exact-session identity below additionally guards cookie changes. */
    public static String scope(Context context) {
        return scope(new NgaStore(context.getApplicationContext()).captureCheckInSession());
    }
    private static String scope(NgaStore.CheckInSession session) {
        return session.account.isEmpty() ? "" : digest(session.host + '\0' + session.account);
    }
    private static String identity(NgaStore.CheckInSession session) {
        return session.account.isEmpty() ? "" : digest(session.host + '\0' + session.account + '\0' + session.cookie);
    }
    private static String digest(String value) {
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            char[] hex="0123456789abcdef".toCharArray(), result=new char[bytes.length*2];
            for(int i=0;i<bytes.length;i++){ result[i*2]=hex[(bytes[i]&255)>>>4];result[i*2+1]=hex[bytes[i]&15]; }
            return new String(result);
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable",impossible); }
    }
    /** Kept for bounded diagnostics barriers; callers must never shut down this process queue. */
    public ExecutorService executor() { return executor; }
    public boolean running() { synchronized(lock) { return policy.running(); } }
    public boolean processing(String accountScope,String key) {
        synchronized(lock) { return policy.running()&&processingScope.equals(accountScope)&&processingKey.equals(key); }
    }
    public Snapshot status(String expectedScope) {
        String value=expectedScope==null?"":expectedScope;
        int pending=value.isEmpty()?0:store.pendingFavoriteChanges(value).size();
        synchronized(lock) {
            boolean current=!value.isEmpty()&&value.equals(statusScope);
            return new Snapshot(value,current&&loaded,current&&policy.running(),current?cloudCount:0,pending,
                    current?lastSuccessfulAt:0,current?failure:Failure.NONE);
        }
    }
    /** Every visible Activity owns a distinct token, so an old stop cannot hide a replacement. */
    public void onForeground(Object token) {
        foregroundTokens.foreground(token);
        foregroundStartup.submit(this::prepareForeground);
    }
    private void prepareForeground() {
        List<FavoriteForegroundTokens.Ticket> tickets=foregroundTokens.snapshot();
        if(tickets.isEmpty())return;
        synchronized(captureLock) {
            if(foregroundTokens.current(tickets).isEmpty())return;
            NgaStore.CheckInSession session=captureSession();
            boolean pending=hasPending(session);
            synchronized(lock) {
                List<Object> current=foregroundTokens.current(tickets);
                if(current.isEmpty())return;
                applySessionLocked(session);
                for(Object token:current)policy.foreground(token,identity(session),SystemClock.elapsedRealtime(),pending);
                if(!networkRegistered) registerNetwork();
                kickLocked();
            }
        }
        notifyObservers();
    }
    public void onBackground(Object token) {
        foregroundTokens.background(token);
        synchronized(lock) {
            policy.background(token);
            if(!policy.foreground()) unregisterNetwork();
            armLocked();
        }
    }
    /** Call after durable explicit favorite intent. A timed-out write is always read before retry. */
    public void schedule() {
        synchronized(captureLock) {
            NgaStore.CheckInSession session=captureSession();
            store.activateFavoriteScope(scope(session));
            synchronized(lock) { applySessionLocked(session); policy.explicit(); kickLocked(); }
        }
        notifyObservers();
    }
    /** force=true is a deliberate manual refresh; false observes normal foreground freshness. */
    public void requestRefresh(boolean force) {
        synchronized(captureLock) {
            NgaStore.CheckInSession session=captureSession();
            store.activateFavoriteScope(scope(session));
            boolean pending=hasPending(session);
            synchronized(lock) {
                applySessionLocked(session);
                policy.refresh(SystemClock.elapsedRealtime(),force,pending);
                kickLocked();
            }
        }
        notifyObservers();
    }
    /**
     * Caller owns captureLock, never the state lock. Automatic work must not activate a captured
     * account's global favorite projection: Main may already have activated a newer account.
     * Main onCreate/account navigation and synchronous explicit intent own projection activation;
     * background reads/merges retain their explicit scope and exact-session HTTP guards.
     */
    private NgaStore.CheckInSession captureSession() {
        return store.captureCheckInSession();
    }
    /** Caller owns captureLock then the short state lock, in that order only. */
    private void applySessionLocked(NgaStore.CheckInSession session) {
        String nextScope=scope(session);
        boolean changed=policy.identity(identity(session));
        if(changed || !nextScope.equals(statusScope)) {
            statusScope=nextScope; loaded=false; cloudCount=0; lastSuccessfulAt=0; failure=Failure.NONE;
        }
    }
    private boolean hasPending(NgaStore.CheckInSession session) {
        String value=scope(session);
        return !value.isEmpty()&&!store.pendingFavoriteChanges(value).isEmpty();
    }
    private void kick() {
        synchronized(lock) { if(policy.delay(SystemClock.elapsedRealtime())<0)return; }
        synchronized(captureLock) {
            synchronized(lock) { if(policy.delay(SystemClock.elapsedRealtime())<0)return; }
            NgaStore.CheckInSession session=captureSession();
            synchronized(lock) { applySessionLocked(session);kickLocked(); }
        }
        notifyObservers();
    }
    private void kickLocked() {
        main.removeCallbacks(retry);
        ForumFavoriteAutoPolicy.Ticket ticket=policy.take(SystemClock.elapsedRealtime());
        if(ticket!=null) executor.execute(()->synchronize(ticket));
        else armLocked();
    }
    private void armLocked() {
        main.removeCallbacks(retry);
        long delay=policy.delay(SystemClock.elapsedRealtime());
        if(delay>=0) main.postDelayed(retry,delay);
    }
    private void synchronize(ForumFavoriteAutoPolicy.Ticket ticket) {
        Failure outcome=Failure.NONE;
        boolean stale=false;
        try {
            NgaStore.CheckInSession session=store.captureCheckInSession();
            String accountScope=scope(session);
            if(accountScope.isEmpty()||!ticket.matches(identity(session))) { stale=true; return; }
            Set<String> attempted=new HashSet<>();
            CloudForumFavorites cloud=new CloudForumFavorites(NativeCommunity.exactForumFavorites(application,session));
            while(sameSession(session)) {
                ForumFavoriteSyncState.Change next=null;
                for(ForumFavoriteSyncState.Change change:store.pendingFavoriteChanges(accountScope)) {
                    if(!attempted.contains(change.key+'\0'+change.revision)){next=change;break;}
                }
                if(next==null) break;
                attempted.add(next.key+'\0'+next.revision);
                synchronized(lock){ processingScope=accountScope;processingKey=next.key; }
                notifyObservers();
                CloudForumFavorites.ChangeResult result=cloud.setDesired(next.key,next.desired);
                if(!sameSession(session)){stale=true;return;}
                if(result.confirmed()&&result.snapshot!=null) {
                    store.confirmFavorite(accountScope,next.key,next.revision,next.desired,entries(result.snapshot));
                    observed(accountScope,result.snapshot);
                } else {
                    if(result.snapshot!=null) store.mergeFavoriteForums(accountScope,entries(result.snapshot));
                    outcome=convert(result.failureCode==null?NativeCommunity.Code.UNCONFIRMED:result.failureCode);
                    break;
                }
                synchronized(lock){ processingScope="";processingKey=""; }
                notifyObservers();
            }
            if(!sameSession(session)){stale=true;return;}
            if(outcome==Failure.NONE) {
                CloudForumFavorites.Snapshot snapshot=cloud.read();
                if(!sameSession(session)){stale=true;return;}
                store.mergeFavoriteForums(accountScope,entries(snapshot));
                observed(accountScope,snapshot);
            }
        } catch(NativeCommunity.CommunityException unavailable) { outcome=convert(unavailable.code); }
        catch(RuntimeException unavailable) { outcome=Failure.INTERNAL; }
        finally {
            synchronized(captureLock) {
                NgaStore.CheckInSession current=null;
                try {
                    current=captureSession();
                    stale=stale||!ticket.matches(identity(current));
                } catch(RuntimeException unavailable) {
                    // Even preference failure must release the ticket; durable intent remains saved.
                    outcome=Failure.INTERNAL;
                }
                synchronized(lock) {
                    processingScope="";processingKey="";
                    if(current!=null)applySessionLocked(current);
                    if(!stale) failure=outcome;
                    ForumFavoriteAutoPolicy.Result result=stale?ForumFavoriteAutoPolicy.Result.STALE:
                            outcome==Failure.NONE?ForumFavoriteAutoPolicy.Result.SUCCESS:
                            refused(outcome)?ForumFavoriteAutoPolicy.Result.BLOCKED:ForumFavoriteAutoPolicy.Result.TRANSIENT;
                    policy.complete(ticket,result,SystemClock.elapsedRealtime());
                    kickLocked();
                }
            }
            notifyObservers();
        }
    }
    private void observed(String accountScope,CloudForumFavorites.Snapshot snapshot) {
        synchronized(lock) {
            if(!accountScope.equals(statusScope)) return;
            loaded=true; cloudCount=snapshot.entries.size();lastSuccessfulAt=System.currentTimeMillis();failure=Failure.NONE;
        }
        notifyObservers();
    }
    private boolean sameSession(NgaStore.CheckInSession expected) {
        NgaStore.CheckInSession current=store.captureCheckInSession();
        return expected.host.equals(current.host)&&expected.account.equals(current.account)&&expected.cookie.equals(current.cookie);
    }
    private static boolean refused(Failure value) {
        return value==Failure.LOGIN||value==Failure.CHALLENGE||value==Failure.PERMISSION||value==Failure.RATE_LIMIT;
    }
    private static Failure convert(NativeCommunity.Code code) {
        if(code==null)return Failure.INTERNAL;
        switch(code) {
            case LOGIN_REQUIRED:return Failure.LOGIN;
            case CHALLENGE_REQUIRED:return Failure.CHALLENGE;
            case PERMISSION_DENIED:return Failure.PERMISSION;
            case RATE_LIMIT:return Failure.RATE_LIMIT;
            case NETWORK:return Failure.NETWORK;
            case SERVER:return Failure.SERVER;
            case INVALID_RESPONSE:return Failure.INVALID;
            case UNCONFIRMED:return Failure.UNCONFIRMED;
            default:return Failure.INTERNAL;
        }
    }
    private static Map<String,String[]> entries(CloudForumFavorites.Snapshot snapshot) {
        Map<String,String[]> values=new LinkedHashMap<>();
        for(CloudForumFavorites.Entry entry:snapshot.entries) values.put(entry.key,new String[]{entry.name,entry.icon});
        return values;
    }
    private boolean hasNetwork() {
        if(connectivity==null)return false;
        try {
            Network network=connectivity.getActiveNetwork();
            NetworkCapabilities caps=network==null?null:connectivity.getNetworkCapabilities(network);
            return caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch(RuntimeException unavailable) { return false; }
    }
    private void registerNetwork() {
        try {
            connectivity=(ConnectivityManager)application.getSystemService(Context.CONNECTIVITY_SERVICE);
            if(connectivity==null)return;
            networkAvailable=hasNetwork();
            connectivity.registerDefaultNetworkCallback(networkCallback);networkRegistered=true;
        } catch(RuntimeException unavailable) { networkRegistered=false; }
    }
    private void unregisterNetwork() {
        if(networkRegistered&&connectivity!=null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); }
            catch(RuntimeException unavailable) { /* Callback may already have been removed by the OS. */ }
        }
        networkRegistered=false;
    }
    private void networkChanged(boolean available) {
        synchronized(lock) {
            boolean restored=available&&!networkAvailable;
            networkAvailable=available;
            if(!restored||!policy.foreground())return;
        }
        synchronized(captureLock) {
            NgaStore.CheckInSession session=captureSession();
            synchronized(lock) {
                if(!networkAvailable||!policy.foreground())return;
                applySessionLocked(session);policy.networkAvailable();kickLocked();
            }
        }
        notifyObservers();
    }
    /** Callers keep the observer strongly while visible and remove it when destroyed. */
    public void addObserver(Runnable observer) {
        if(observer==null)return;
        synchronized(lock) {
            for(Iterator<WeakReference<Runnable>> it=observers.iterator();it.hasNext();) {
                Runnable current=it.next().get();
                if(current==null)it.remove();else if(current==observer)return;
            }
            observers.add(new WeakReference<>(observer));
        }
    }
    public void removeObserver(Runnable observer) {
        synchronized(lock) {
            for(Iterator<WeakReference<Runnable>> it=observers.iterator();it.hasNext();) {
                Runnable current=it.next().get();if(current==null||current==observer)it.remove();
            }
        }
    }
    private void notifyObservers() {
        synchronized(lock){if(notificationPosted)return;notificationPosted=true;}
        main.post(()->{
            List<Runnable> current=new ArrayList<>();
            synchronized(lock) {
                notificationPosted=false;
                for(Iterator<WeakReference<Runnable>> it=observers.iterator();it.hasNext();) {
                    Runnable observer=it.next().get();if(observer==null)it.remove();else current.add(observer);
                }
            }
            for(Runnable observer:current)try{observer.run();}catch(RuntimeException staleView){/* A stale UI cannot stop sync. */}
        });
    }
}
