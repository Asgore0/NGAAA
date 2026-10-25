package cn.ngapure.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real notification control flow; only OS/session storage and read-only transport are synthetic. */
public final class NotificationFastPathTest {
    private static int checks;
    private static final NgaStore.CheckInSession A=new NgaStore.CheckInSession("bbs.nga.cn","synthetic-cookie-a","123");
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);System.out.println("PASS "+name);}
    private static SharedPreferences prefs(Context app){return app.getSharedPreferences("nga_unread_notifications",0);}
    private static Object field(String name)throws Exception{Field f=NativeNotifications.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    private static void await(CountDownLatch latch,String name)throws Exception{check(latch.await(3,TimeUnit.SECONDS),name);}
    private static void block(CountDownLatch latch){try{if(!latch.await(4,TimeUnit.SECONDS))throw new IllegalStateException("synthetic wait expired");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    private static Context enabled(){Context app=new Context();NgaStore.current=A;NativeNotifications.setEnabled(app,true);return app;}
    private static void noCredentialDisabledCleanup()throws Exception{
        Context app=enabled();check(!((String)field("activeSession")).isEmpty(),"enabled scheduler records exact-session owner");
        prefs(app).edit().putBoolean("enabled",false).apply();int before=NgaStore.captures.get(),network=NativeCommunity.network.get(),cancels=app.jobs.cancels.get();long generation=(Long)field("generation");
        NativeNotifications.schedule(app);NativeNotifications.onForeground(app);check(NativeNotifications.check(app,new AtomicBoolean()),"late disabled job finishes without retry");
        check(NgaStore.captures.get()==before,"disabled scheduler foreground and job never read credentials");
        check(NativeCommunity.network.get()==network,"disabled scheduler foreground and job send no HTTP");
        check(app.jobs.cancels.get()>cancels&&app.notifications.cancels.get()>0,"disabled paths cancel job and visible notice");
        check((Long)field("generation")>generation&&((String)field("activeSession")).isEmpty()&&((String)field("foregroundScope")).isEmpty(),"disabled cleanup invalidates worker generation and in-memory owners");
        check(prefs(app).getString("activeScope","missing").isEmpty(),"disabled cleanup removes persisted active account projection");
    }
    private static void noCredentialPermissionCleanup()throws Exception{
        Context app=enabled();app.permission=-1;int before=NgaStore.captures.get(),network=NativeCommunity.network.get();
        NativeNotifications.schedule(app);NativeNotifications.onForeground(app);check(NativeNotifications.check(app,new AtomicBoolean()),"permission-denied job finishes without retry");
        check(NgaStore.captures.get()==before&&NativeCommunity.network.get()==network,"permission-denied paths avoid credential capture and HTTP");
        check(prefs(app).getString("activeScope","missing").isEmpty()&&((String)field("activeSession")).isEmpty(),"permission revocation clears old owner without account read");
        app.permission=0;app.notifications.enabled=false;NativeNotifications.schedule(app);
        check(NgaStore.captures.get()==before,"OS notification switch off also avoids credentials");
    }
    private static void noCredentialAlreadyCanceled()throws Exception{
        Context app=enabled();int before=NgaStore.captures.get(),network=NativeCommunity.network.get();
        check(NativeNotifications.check(app,new AtomicBoolean(true)),"already-canceled job finishes immediately");
        check(NgaStore.captures.get()==before&&NativeCommunity.network.get()==network,"canceled job does not evaluate session guard");
        Thread.currentThread().interrupt();try{check(NativeNotifications.check(app,new AtomicBoolean()),"interrupted job finishes immediately");}finally{Thread.interrupted();}
        check(NgaStore.captures.get()==before&&NativeCommunity.network.get()==network,"interrupted job avoids credentials and HTTP");
    }
    private static void rejectedLateRead(String kind)throws Exception{
        Context app=enabled();AtomicBoolean canceled=new AtomicBoolean();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        NativeCommunity.beforeReminderReturn.set(()->{entered.countDown();block(release);});int conversations=NativeCommunity.conversationReads.get();
        Future<Boolean> result=WORKER.submit(()->NativeNotifications.check(app,canceled));
        try{
            await(entered,kind+" read is in flight");
            if(kind.equals("disable-reenable")){NativeNotifications.setEnabled(app,false);NativeNotifications.setEnabled(app,true);}
            else if(kind.equals("revoke-regrant")){app.permission=-1;NativeNotifications.onForeground(app);app.permission=0;NativeNotifications.schedule(app);}
            else if(kind.equals("cancel"))NativeNotifications.cancel(canceled);
            else if(kind.equals("account-change"))NgaStore.current=new NgaStore.CheckInSession(A.host,"synthetic-cookie-b","456");
            else if(kind.equals("host-change"))NgaStore.current=new NgaStore.CheckInSession("ngabbs.com",A.cookie,A.account);
            else if(kind.equals("cookie-change"))NgaStore.current=new NgaStore.CheckInSession(A.host,"synthetic-renewed-cookie",A.account);
            else throw new AssertionError("Unknown scenario");
            int beforeRelease=NgaStore.captures.get();release.countDown();check(result.get(3,TimeUnit.SECONDS),kind+" late result rejected without retry");
            check(NativeCommunity.conversationReads.get()==conversations,kind+" cannot start the next HTTP read");
            check(!prefs(app).contains(UnreadNotificationPolicy.scope(A.host,A.account)+".checkedAt"),kind+" cannot commit old unread baseline");
            boolean cheap=kind.equals("disable-reenable")||kind.equals("revoke-regrant")||kind.equals("cancel");
            check(cheap?NgaStore.captures.get()==beforeRelease:NgaStore.captures.get()>beforeRelease,kind+" preserves cheap rejection or necessary exact-session validation");
            check(!((AtomicBoolean)field("CHECKING")).get(),kind+" releases poll ownership");
            check(NativeNotifications.check(app,new AtomicBoolean()),kind+" allows a fresh authorized poll afterward");
            check(NativeCommunity.conversationReads.get()==conversations+1,kind+" fresh poll reaches both read endpoints");
        }finally{release.countDown();}
    }
    public static void main(String[] args)throws Exception{
        try{
            noCredentialDisabledCleanup();noCredentialPermissionCleanup();noCredentialAlreadyCanceled();
            for(String scenario:new String[]{"disable-reenable","revoke-regrant","cancel","account-change","host-change","cookie-change"})rejectedLateRead(scenario);
            System.out.println("NotificationFastPath: "+checks+" assertions passed; real notification lifecycle, synthetic OS/session/transport; real network=0");
        }finally{WORKER.shutdownNow();}
    }
}
