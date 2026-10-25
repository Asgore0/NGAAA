package cn.ngapure.scopelifecycle;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import cn.ngapure.app.MainActivity;
import cn.ngapure.app.NgaStore;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Separate UID and no INTERNET. Only synthetic credentials in this helper's own private storage. */
public final class ScopeLifecycleProbe extends Instrumentation {
    private volatile MainActivity mainActivity;
    private volatile Activity cover;
    private volatile boolean mainStopped;
    private boolean authorized;
    private final JSONObject report=new JSONObject();
    private String stage="guard";
    private int assertions;
    private final Application.ActivityLifecycleCallbacks callbacks=new Application.ActivityLifecycleCallbacks(){
        public void onActivityResumed(Activity value){if(value instanceof MainActivity){mainActivity=(MainActivity)value;mainStopped=false;}else if(value instanceof ScopePauseActivity)cover=value;}
        public void onActivityStopped(Activity value){if(value instanceof MainActivity)mainStopped=true;}
        public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityPaused(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
    };
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticSessionsOnly"));start();}
    private static Object field(Object target,String name)throws Exception{Field member=target.getClass().getDeclaredField(name);member.setAccessible(true);return member.get(target);}
    private <T>T main(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private void check(String name,boolean value)throws Exception{report.put(name,value);assertions++;if(!value)throw new AssertionError(name);}
    private String scope()throws Exception{return main(()->{Method method=MainActivity.class.getDeclaredMethod("contentScope");method.setAccessible(true);return (String)method.invoke(mainActivity);});}
    private void openPrivateRoute()throws Exception{main(()->{Class<?> type=Class.forName("cn.ngapure.app.MainActivity$Screen");Constructor<?> constructor=type.getDeclaredConstructor(String.class,String.class,String.class,int.class);constructor.setAccessible(true);Object screen=constructor.newInstance("serverfav","1","合成账号收藏",1);Method go=MainActivity.class.getDeclaredMethod("go",type,boolean.class);go.setAccessible(true);go.invoke(mainActivity,screen,false);return null;});waitForIdleSync();SystemClock.sleep(250);}
    @SuppressWarnings("unchecked") private void insertSyntheticOldContent(String marker)throws Exception{main(()->{ViewGroup body=(ViewGroup)field(mainActivity,"body");TextView old=new TextView(mainActivity);old.setText("合成旧账号内容");old.setTag(marker);body.addView(old);((Map<String,Integer>)field(mainActivity,"readingPositions")).put(marker,123);return null;});}
    private void stopBehindCover()throws Exception{mainStopped=false;cover=startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(),ScopePauseActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));for(int i=0;i<60&&!mainStopped;i++)SystemClock.sleep(50);if(!mainStopped)throw new IllegalStateException("MAIN_DID_NOT_STOP");}
    private void returnToMain()throws Exception{Activity overlay=cover;main(()->{overlay.finish();return null;});for(int i=0;i<60;i++){if(!mainStopped&&mainActivity.hasWindowFocus()){waitForIdleSync();SystemClock.sleep(150);return;}SystemClock.sleep(50);}throw new IllegalStateException("MAIN_DID_NOT_RESUME");}
    private void cycle(String label,NgaStore store,Runnable change)throws Exception{
        stage=label;openPrivateRoute();String marker="scope-fixture-"+label;insertSyntheticOldContent(marker);String beforeScope=scope();int beforeGeneration=main(()->(Integer)field(mainActivity,"generation"));stopBehindCover();change.run();String nextScope=scope();check(label+"SyntheticSessionIdentityChanged",!beforeScope.equals(nextScope));returnToMain();
        check(label+"MainRebuiltOnForeground",main(()->(Integer)field(mainActivity,"generation")>beforeGeneration));check(label+"ActiveScopeMatchesCurrentSession",main(()->nextScope.equals(field(mainActivity,"activeContentScope"))));check(label+"OldContentRemovedBeforeResume",main(()->mainActivity.getWindow().getDecorView().findViewWithTag(marker)==null));check(label+"OldReadingPositionCleared",main(()->!((Map<?,?>)field(mainActivity,"readingPositions")).containsKey(marker)));check(label+"HelperRemainsCurrentSyntheticSession",!store.autoCheckIn());
    }
    private void foregroundChanges(NgaStore ownStore)throws Exception{
        stage="multiResumeEquivalent";String marker="foreground-scope-fixture";insertSyntheticOldContent(marker);int before=main(()->(Integer)field(mainActivity,"generation"));
        main(()->{ownStore.setHost("bbs.nga.cn");ownStore.setCookie("ngaPassportUid=70003; ngaPassportCid=synthetic-c");return null;});waitForIdleSync();SystemClock.sleep(200);String expected=scope();
        check("visibleInstanceObservesSessionChangeWithoutNewLifecycle",main(()->expected.equals(field(mainActivity,"activeContentScope"))));check("sameTurnHostAndCookieChangesCoalesceIntoOneRebuild",main(()->(Integer)field(mainActivity,"generation")==before+1));check("visibleOldAccountContentRemovedWithoutLosingFocus",main(()->mainActivity.getWindow().getDecorView().findViewWithTag(marker)==null));
        stage="irrelevantPreferences";int stable=main(()->(Integer)field(mainActivity,"generation"));main(()->{ownStore.setTheme("dark");ownStore.setFontSize(22);ownStore.recordCheckInAttempt("synthetic-only");return null;});waitForIdleSync();SystemClock.sleep(150);check("themeFontAndCheckInPreferencesDoNotRebuildContent",main(()->(Integer)field(mainActivity,"generation")==stable));
        stage="finalScopeWins";String retained="same-turn-final-scope-fixture";insertSyntheticOldContent(retained);main(()->{ownStore.setCookie("ngaPassportUid=70004; ngaPassportCid=synthetic-transient");ownStore.setCookie("ngaPassportUid=70003; ngaPassportCid=synthetic-c");return null;});waitForIdleSync();SystemClock.sleep(150);
        check("sameTurnTransientSessionDoesNotTriggerIntermediateRead",main(()->(Integer)field(mainActivity,"generation")==stable));check("sameTurnFinalScopePreservesCurrentContent",main(()->mainActivity.getWindow().getDecorView().findViewWithTag(retained)!=null));check("sameTurnFinalScopeRemainsCurrent",main(()->expected.equals(field(mainActivity,"activeContentScope"))));
        stage="failedCredentialCommit";NgaStore failing=new NgaStore(getTargetContext());Field preferences=NgaStore.class.getDeclaredField("preferences");preferences.setAccessible(true);preferences.set(failing,new FailOncePreferences((android.content.SharedPreferences)preferences.get(failing)));final boolean[] refused={false};main(()->{try{failing.setCookie("ngaPassportUid=70006; ngaPassportCid=synthetic-failed-commit");}catch(IllegalStateException expectedFailure){refused[0]=true;}return null;});waitForIdleSync();SystemClock.sleep(150);
        check("failedSyntheticCredentialCommitWasRefused",refused[0]);check("failedCommitRollbackRetainsActualSession",expected.equals(scope()));check("failedCommitDoesNotRebuildFromTransientLiveMap",main(()->(Integer)field(mainActivity,"generation")==stable));check("failedCommitDoesNotDiscardCurrentContent",main(()->mainActivity.getWindow().getDecorView().findViewWithTag(retained)!=null));
    }
    /** Exercises real LoginPreferences rollback while only this helper's private map is affected. */
    private static final class FailOncePreferences implements android.content.SharedPreferences {
        private final android.content.SharedPreferences delegate;private boolean fail=true;
        FailOncePreferences(android.content.SharedPreferences value){delegate=value;}
        public Map<String,?> getAll(){return delegate.getAll();}public String getString(String k,String d){return delegate.getString(k,d);}public java.util.Set<String> getStringSet(String k,java.util.Set<String> d){return delegate.getStringSet(k,d);}public int getInt(String k,int d){return delegate.getInt(k,d);}public long getLong(String k,long d){return delegate.getLong(k,d);}public float getFloat(String k,float d){return delegate.getFloat(k,d);}public boolean getBoolean(String k,boolean d){return delegate.getBoolean(k,d);}public boolean contains(String k){return delegate.contains(k);}
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){delegate.registerOnSharedPreferenceChangeListener(listener);}public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){delegate.unregisterOnSharedPreferenceChangeListener(listener);}
        public Editor edit(){Editor edit=delegate.edit();return new Editor(){
            public Editor putString(String k,String v){edit.putString(k,v);return this;}public Editor putStringSet(String k,java.util.Set<String> v){edit.putStringSet(k,v);return this;}public Editor putInt(String k,int v){edit.putInt(k,v);return this;}public Editor putLong(String k,long v){edit.putLong(k,v);return this;}public Editor putFloat(String k,float v){edit.putFloat(k,v);return this;}public Editor putBoolean(String k,boolean v){edit.putBoolean(k,v);return this;}public Editor remove(String k){edit.remove(k);return this;}public Editor clear(){edit.clear();return this;}public void apply(){edit.apply();}
            public boolean commit(){boolean actual=edit.commit();if(fail){fail=false;return false;}return actual;}
        };}
    }
    private void destroyedCleanup(NgaStore store)throws Exception{
        stage="destroyCleanup";MainActivity retired=mainActivity;int generation=main(()->(Integer)field(retired,"generation"));main(()->{retired.finish();return null;});for(int i=0;i<80&&!retired.isDestroyed();i++)SystemClock.sleep(50);waitForIdleSync();check("destroyedActivityUnregistersSessionPreferences",main(()->retired.isDestroyed()&&field(retired,"sessionPreferences")==null));
        store.setCookie("ngaPassportUid=70005; ngaPassportCid=synthetic-after-destroy");waitForIdleSync();SystemClock.sleep(150);check("destroyedActivityReceivesNoSessionRebuild",main(()->(Integer)field(retired,"generation")==generation+1));
        if(android.os.Build.VERSION.SDK_INT>=29)check("destroyedActivityHasNoRetainedSessionTask",main(()->!((android.os.Handler)field(retired,"navigationHandler")).hasCallbacks((Runnable)field(retired,"sessionContentTask"))));
    }
    @Override public void onStart(){Application app=null;NgaStore store=null;try{
        if(!authorized||!"cn.ngapure.scopelifecycle".equals(getTargetContext().getPackageName())||getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("OWN_UID_SYNTHETIC_NO_INTERNET_REQUIRED");
        store=new NgaStore(getTargetContext());store.setAutoCheckIn(false);store.setMotionEnabled(false);store.setCookie("ngaPassportUid=70001; ngaPassportCid=synthetic-a");getTargetContext().getSharedPreferences("nga_unread_notifications",0).edit().putBoolean("enabled",false).commit();app=(Application)getTargetContext().getApplicationContext();app.registerActivityLifecycleCallbacks(callbacks);
        mainActivity=(MainActivity)startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(),MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();final NgaStore ownStore=store;
        cycle("account",store,()->ownStore.setCookie("ngaPassportUid=70002; ngaPassportCid=synthetic-b"));cycle("cookieRefresh",store,()->ownStore.setCookie("ngaPassportUid=70002; ngaPassportCid=synthetic-b-refresh"));cycle("host",store,()->ownStore.setHost("ngabbs.com"));cycle("logout",store,ownStore::clearLogin);
        stage="sameScope";String marker="same-scope-fixture";insertSyntheticOldContent(marker);int before=main(()->(Integer)field(mainActivity,"generation"));stopBehindCover();returnToMain();check("unchangedScopeDoesNotRebuildPage",main(()->(Integer)field(mainActivity,"generation")==before));check("unchangedScopePreservesCurrentContent",main(()->mainActivity.getWindow().getDecorView().findViewWithTag(marker)!=null));
        foregroundChanges(ownStore);
        destroyedCleanup(ownStore);
        report.put("completed",true).put("passed",true).put("assertions",assertions);
    }catch(Exception|AssertionError failure){try{report.put("completed",false).put("passed",false).put("failureStage",stage).put("assertions",assertions).put("failureType",failure.getClass().getSimpleName());}catch(Exception ignored){} }
    finally{try{if(cover!=null&&!cover.isDestroyed())main(()->{cover.finish();return null;});if(mainActivity!=null&&!mainActivity.isDestroyed())main(()->{mainActivity.finish();return null;});if(app!=null)app.unregisterActivityLifecycleCallbacks(callbacks);report.put("ownUidOnly",true).put("internetPermission",false).put("syntheticSessionsOnly",true).put("realClientStarted",false).put("realAccountChanged",false).put("credentialsExported",false).put("privateContentExported",false).put("communityWriteActionInvoked",false);}catch(Exception ignored){}Bundle result=new Bundle();result.putString("safe_scope_lifecycle_report",report.toString());finish(Activity.RESULT_OK,result);}}
}
