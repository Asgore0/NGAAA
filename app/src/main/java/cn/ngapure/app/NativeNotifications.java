package cn.ngapure.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opt-in system notifications via read-only periodic polling. This is not NGA server real-time push. */
public final class NativeNotifications {
    private static final int JOB=12092,NOTICE=12093;
    private static final String CHANNEL="nga_unread",PREFS="nga_unread_notifications";
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static final StartupTaskQueue STARTUP=new StartupTaskQueue("NgaUnreadStartup");
    private static final AtomicBoolean CHECKING=new AtomicBoolean();
    private static final Object LOCK=new Object();
    private static long generation,lastForegroundCheck;
    private static volatile Boolean enabledOverride;
    private static String activeSession="",foregroundScope="";
    private NativeNotifications() { }
    public static boolean enabled(Context context){Boolean pinned=enabledOverride;if(pinned!=null)return pinned;try{return prefs(context).getBoolean("enabled",false);}catch(RuntimeException corrupt){return false;}}
    public static void setEnabled(Context context,boolean enabled){
        synchronized(LOCK){
            boolean before=enabled(context),saved;
            try{saved=prefs(context).edit().putBoolean("enabled",enabled).commit();}catch(RuntimeException failed){saved=false;}
            generation++;lastForegroundCheck=0;foregroundScope="";
            if(!saved){
                // SharedPreferences may change its live map before reporting a failed disk commit.
                // Never opt in because of that failure; a requested opt-out wins in this process.
                enabledOverride=enabled?before:false;
                try{prefs(context).edit().putBoolean("enabled",enabledOverride).commit();}catch(RuntimeException ignored){ }
                if(!enabledOverride){cancelNotice(context);JobScheduler scheduler=context.getSystemService(JobScheduler.class);if(scheduler!=null)scheduler.cancel(JOB);}
                throw new IllegalStateException(enabled?"无法保存提醒设置，仍保留之前的选择":"已停止本次提醒，但设置未保存，请重试");
            }
            enabledOverride=null;if(!enabled)cancelNotice(context);
        }
        schedule(context);
    }
    public static boolean permissionGranted(Context context){
        return Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
    }
    public static boolean canNotify(Context context){
        try{NotificationManager manager=context.getSystemService(NotificationManager.class);
            if(!permissionGranted(context)||manager==null||!manager.areNotificationsEnabled())return false;
            NotificationChannel channel=manager.getNotificationChannel(CHANNEL);return channel==null||channel.getImportance()!=NotificationManager.IMPORTANCE_NONE;
        }catch(RuntimeException unavailable){return false;}
    }
    /** Call after login/logout/host changes too, so an old account's pending notice disappears immediately. */
    public static void schedule(Context context){
        scheduleActive(context.getApplicationContext());
    }
    /** False means foreground polling is already disabled; no second cleanup/capture is needed. */
    private static boolean scheduleActive(Context app){
        synchronized(LOCK){
            if(stopIfUnavailable(app))return false;
            NgaStore store=new NgaStore(app);
            reconcile(app,store.captureCheckInSession());
            if(stopIfUnavailable(app))return false;
            JobScheduler scheduler=app.getSystemService(JobScheduler.class);
            if(!store.loggedIn()){if(scheduler!=null)scheduler.cancel(JOB);cancelNotice(app);return false;}
            if(scheduler==null)return true;
            JobInfo job=new JobInfo.Builder(JOB,new ComponentName(app,UnreadNotificationService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(15*60*1000L).build();scheduler.schedule(job);
            return true;
        }
    }
    /** Startup-only deferral; explicit logout/opt-out still uses the immediate APIs above. */
    public static void onForegroundAsync(Context context){
        Context app=context.getApplicationContext();
        STARTUP.submit(()->{if(scheduleActive(app))onForeground(app);});
    }
    public static String startupDiagnostics(){return STARTUP.diagnostics();}
    public static void onForeground(Context context){
        Context app=context.getApplicationContext();NgaStore store=new NgaStore(app);
        synchronized(LOCK){
            if(stopIfUnavailable(app))return;
            NgaStore.CheckInSession current=store.captureCheckInSession();reconcile(app,current);
            if(stopIfUnavailable(app))return;
            if(current.account.isEmpty()){cancelNotice(app);return;}
            long now=android.os.SystemClock.elapsedRealtime();String scope=UnreadNotificationPolicy.scope(current.host,current.account);
            if(scope.equals(foregroundScope)&&lastForegroundCheck>0&&now-lastForegroundCheck<60000)return;
            lastForegroundCheck=now;foregroundScope=scope;
        }
        WORKER.execute(()->{try{check(app,new AtomicBoolean());}catch(RuntimeException unavailable){/* A service retry or next foreground entry can recover unavailable local storage. */}});
    }
    /** Returns false on a retryable read failure; never marks read, sends messages or mutates community state. */
    static boolean check(Context context,AtomicBoolean canceled){
        Context app=context.getApplicationContext();NgaStore store=new NgaStore(app);final NgaStore.CheckInSession expected;final long ownerGeneration;
        synchronized(LOCK){
            if(stopIfUnavailable(app)||canceled.get()||Thread.currentThread().isInterrupted())return true;
            expected=store.captureCheckInSession();reconcile(app,expected);ownerGeneration=generation;
            if(!valid(app,store,expected,canceled,ownerGeneration)||expected.account.isEmpty())return true;
            // A different owner may be stopping or belong to a previous session. This poll
            // has not completed: let JobService request a later retry without releasing it.
            if(!CHECKING.compareAndSet(false,true))return false;
        }
        try {
            NativeCommunity api=NativeCommunity.exactForumFavorites(app,expected);
            NativeCommunity.ReminderPage reminders=api.reminders(0);
            synchronized(LOCK){if(!valid(app,store,expected,canceled,ownerGeneration))return true;}
            NativeCommunity.ConversationPage messages=api.conversations(1);
            UnreadNotificationPolicy.Snapshot next=snapshot(reminders,messages);
            synchronized(LOCK){
                if(!valid(app,store,expected,canceled,ownerGeneration))return true;
                String key=UnreadNotificationPolicy.scope(expected.host,expected.account);SharedPreferences data=prefs(app);
                UnreadNotificationPolicy.Snapshot previous=UnreadNotificationPolicy.decode(data.getString(key+".snapshot",""));
                // Migration preserves count baseline while enriching event identity on the first successful observation.
                if(previous==null&&data.contains(key+".reminders"))previous=new UnreadNotificationPolicy.Snapshot(data.getInt(key+".reminders",0),data.getInt(key+".conversations",0),java.util.Collections.emptySet(),java.util.Collections.emptySet());
                boolean newUnread=UnreadNotificationPolicy.hasNew(previous,next);
                // Commit baseline before publishing; a disk failure must not create repeated alerts every poll.
                boolean saved=saveBaseline(data,key,next,System.currentTimeMillis());
                if(!saved)return false;
                if(!valid(app,store,expected,canceled,ownerGeneration))return true;
                if(newUnread)notifyUnread(app,next.reminders,next.conversations,expected.host);
                else if(next.reminders==0&&next.conversations==0)cancelNotice(app);
            }
            return true;
        }catch(Exception unavailable){
            synchronized(LOCK){if(valid(app,store,expected,canceled,ownerGeneration))prefs(app).edit().putString(UnreadNotificationPolicy.scope(expected.host,expected.account)+".status","暂时无法读取未读提醒；登录、网络或账号权限可能需要处理").apply();}
            synchronized(LOCK){return !valid(app,store,expected,canceled,ownerGeneration);}
        }finally{CHECKING.set(false);}
    }
    static UnreadNotificationPolicy.Snapshot snapshot(NativeCommunity.ReminderPage reminders,NativeCommunity.ConversationPage messages){
        int reminderCount=UnreadNotificationPolicy.bounded(reminders.unread),conversationCount=0;
        List<String> r=new ArrayList<>(),m=new ArrayList<>();boolean explicitReminderFlags=false;
        for(NativeCommunity.Reminder item:reminders.items)if(item.unread)explicitReminderFlags=true;
        // raw=3 legacy responses omit per-row unread flags; their aggregate unread count governs the returned event list.
        if(reminderCount>0)for(NativeCommunity.Reminder item:reminders.items)if((item.unread||!explicitReminderFlags)&&r.size()<200)
            r.add(UnreadNotificationPolicy.reminderEvent(item.type,item.category,item.uid,item.tid,item.pid,item.timestamp));
        for(NativeCommunity.Conversation item:messages.items)if(item.unread){conversationCount++;if(m.size()<200)m.add(UnreadNotificationPolicy.conversationEvent(item.mid,item.posts,item.timestamp));}
        return new UnreadNotificationPolicy.Snapshot(reminderCount,conversationCount,r,m);
    }
    /** Caller owns LOCK. A failed write must leave this observation eligible for a later alert. */
    static boolean saveBaseline(SharedPreferences data,String key,UnreadNotificationPolicy.Snapshot next,long checkedAt){
        Map<String,?> before=data.getAll();
        if(data.edit().putString(key+".snapshot",UnreadNotificationPolicy.encode(next))
                .remove(key+".reminders").remove(key+".conversations").putString(key+".status","最近检查成功")
                .putLong(key+".checkedAt",checkedAt).commit())return true;
        // SharedPreferences updates memory even when commit() reports a disk failure. No alert
        // was published, so advancing that live baseline would silently swallow the retry.
        SharedPreferences.Editor rollback=data.edit();
        for(String suffix:new String[]{".snapshot",".reminders",".conversations",".status",".checkedAt"}){
            String field=key+suffix;Object value=before.get(field);
            if(value instanceof String)rollback.putString(field,(String)value);
            else if(value instanceof Integer)rollback.putInt(field,(Integer)value);
            else if(value instanceof Long)rollback.putLong(field,(Long)value);
            else if(value instanceof Float)rollback.putFloat(field,(Float)value);
            else if(value instanceof Boolean)rollback.putBoolean(field,(Boolean)value);
            else if(value instanceof java.util.Set)rollback.putStringSet(field,stringSet(value));
            else rollback.remove(field);
        }
        // Even another failed disk write restores the in-process map; still report a retry.
        rollback.commit();return false;
    }
    @SuppressWarnings("unchecked")
    private static java.util.Set<String> stringSet(Object value){return new java.util.HashSet<>((java.util.Set<String>)value);}
    /** Job cancellation and final delivery share LOCK, preventing publication after onStopJob returns. */
    static void cancel(AtomicBoolean owner){if(owner!=null)synchronized(LOCK){owner.set(true);}}
    private static boolean valid(Context app,NgaStore store,NgaStore.CheckInSession expected,AtomicBoolean canceled,long ownerGeneration){
        // Java evaluates all method arguments eagerly: reject before the credential read.
        if(ownerGeneration!=generation||canceled.get()||Thread.currentThread().isInterrupted()||!enabled(app)||!canNotify(app))return false;
        return same(store,expected);
    }
    /** Caller owns LOCK. Opt-out/revocation invalidates old workers without reading an account. */
    private static boolean stopIfUnavailable(Context app){
        if(enabled(app)&&canNotify(app))return false;
        generation++;activeSession="";lastForegroundCheck=0;foregroundScope="";
        SharedPreferences data=prefs(app);
        if(!readString(data,"activeScope","").isEmpty())data.edit().putString("activeScope","").apply();
        JobScheduler scheduler=app.getSystemService(JobScheduler.class);if(scheduler!=null)scheduler.cancel(JOB);
        cancelNotice(app);return true;
    }
    private static boolean same(NgaStore store,NgaStore.CheckInSession expected){
        NgaStore.CheckInSession current=store.captureCheckInSession();return !expected.account.isEmpty()&&expected.host.equals(current.host)&&expected.account.equals(current.account)&&expected.cookie.equals(current.cookie);
    }
    private static void reconcile(Context app,NgaStore.CheckInSession current){
        String scope=current.account.isEmpty()?"":UnreadNotificationPolicy.scope(current.host,current.account);
        // Session digest lives in process memory only. Stored snapshots never contain a cookie or its digest.
        String session=scope.isEmpty()?"":UnreadNotificationPolicy.scope(scope,current.cookie);
        SharedPreferences data=prefs(app);
        if(!session.equals(activeSession)){generation++;activeSession=session;lastForegroundCheck=0;foregroundScope="";cancelNotice(app);}
        if(!scope.equals(readString(data,"activeScope",""))){generation++;cancelNotice(app);data.edit().putString("activeScope",scope).apply();}
    }
    public static String status(Context context){
        if(!enabled(context))return "未开启后台提醒";
        NgaStore.CheckInSession session=new NgaStore(context).captureCheckInSession();if(session.account.isEmpty())return "登录后可定期检查未读提醒";
        if(!canNotify(context))return "请在系统设置开启通知权限";
        return readString(prefs(context),UnreadNotificationPolicy.scope(session.host,session.account)+".status","尚未检查；由系统定期安排");
    }
    public static long lastCheckedAt(Context context){NgaStore.CheckInSession session=new NgaStore(context).captureCheckInSession();if(session.account.isEmpty())return 0;try{return prefs(context).getLong(UnreadNotificationPolicy.scope(session.host,session.account)+".checkedAt",0);}catch(RuntimeException corrupt){return 0;}}
    private static void notifyUnread(Context app,int reminders,int messages,String host){
        NotificationManager manager=app.getSystemService(NotificationManager.class);if(manager==null)return;
        NotificationChannel channel=new NotificationChannel(CHANNEL,"NGA 未读提醒",NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("后台定期检查回复提醒和未读私信会话；时间由系统安排，并非官方实时推送");channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);manager.createNotificationChannel(channel);
        String route=messages>0?"/nuke.php?__lib=message&__act=message&act=list":"/nuke.php?__lib=noti&__act=get_all";
        Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse("https://"+host+route),app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open=PendingIntent.getActivity(app,NOTICE,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(app,CHANNEL).setSmallIcon(cn.ngapure.app.R.drawable.ic_notification)
            .setContentTitle("NGA 有新的未读提醒").setContentText("回复提醒 "+reminders+" 条 · 私信当前页 "+messages+" 个未读会话")
            .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_SOCIAL).build();manager.notify(NOTICE,notification);
    }
    static void cancelNotice(Context context){NotificationManager manager=context.getSystemService(NotificationManager.class);if(manager!=null)manager.cancel(NOTICE);}
    private static String readString(SharedPreferences data,String key,String fallback){try{return data.getString(key,fallback);}catch(RuntimeException corrupt){return fallback;}}
    private static SharedPreferences prefs(Context context){return context.getApplicationContext().getSharedPreferences(PREFS,0);}
}
