package cn.ngapure.writelifecycle;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.os.SystemClock;
import android.widget.CheckBox;
import android.widget.EditText;
import cn.ngapure.app.MainActivity;
import cn.ngapure.app.NativeLoginView;
import cn.ngapure.app.NgaStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Actual Main.recreate and framework Bundle delivery; isolated UID, synthetic fields, no network. */
public final class LoginMethodLifecycleProbe extends Instrumentation {
    private static final String METHOD="ngapure_auth_method_v1",SCOPE="ngapure_auth_method_scope_v1",NAV="ngapure_navigation_v1";
    private static final String[] MARKERS={"fixture-account-7946","fixture-secret-7946","13300007946","15500007946","794612","AB7946","fixture-recipient-7946"};
    private interface Mutation{void apply(Bundle state);}
    private volatile MainActivity activity;
    private volatile Mutation mutation;
    private volatile boolean savedContainsMethod,savedScopeIsHash,sensitiveStateObserved,credentialAttemptObserved;
    private volatile int savedMethod=-1,saves;
    private boolean authorized;
    private int assertions;
    private String stage="guard";
    private final JSONObject report=new JSONObject();
    private static final AtomicInteger QR_REQUESTS=new AtomicInteger();
    private final Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){
        public void onActivityCreated(Activity value,Bundle state){if(value instanceof MainActivity)activity=(MainActivity)value;}
        @Override public void onActivityPostCreated(Activity value,Bundle state){if(value instanceof MainActivity){activity=(MainActivity)value;try{NativeLoginView login=(NativeLoginView)field(value,"loginView");if(login!=null&&login.selectedMethod()>0&&field(login,"attempt")!=null)credentialAttemptObserved=true;}catch(Exception failure){credentialAttemptObserved=true;}}}
        public void onActivityResumed(Activity value){if(value instanceof MainActivity)activity=(MainActivity)value;}
        public void onActivitySaveInstanceState(Activity value,Bundle state){}
        @Override public void onActivityPostSaveInstanceState(Activity value,Bundle state){if(!(value instanceof MainActivity))return;saves++;Object mode=raw(state,METHOD),scope=raw(state,SCOPE);savedContainsMethod=state.containsKey(METHOD);savedMethod=mode instanceof Integer?(Integer)mode:-1;savedScopeIsHash=scope instanceof String&&((String)scope).matches("[0-9a-f]{64}");sensitiveStateObserved|=containsSyntheticInput(state);Mutation pending=mutation;if(pending!=null)pending.apply(state);}
        public void onActivityStarted(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivityDestroyed(Activity a){}
    };
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticLoginMethodsOnly"));start();}
    private static Object field(Object target,String name)throws Exception{Field member=target.getClass().getDeclaredField(name);member.setAccessible(true);return member.get(target);}
    @SuppressWarnings("deprecation") private static Object raw(Bundle state,String key){return state.get(key);}
    private <T>T main(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private void check(String label,boolean value)throws Exception{report.put(label,value);assertions++;if(!value)throw new AssertionError(label);}
    private static boolean contains(byte[] bytes,byte[] needle){outer:for(int i=0;i+needle.length<=bytes.length;i++){for(int j=0;j<needle.length;j++)if(bytes[i+j]!=needle[j])continue outer;return true;}return false;}
    private static boolean containsSyntheticInput(Bundle state){Parcel parcel=Parcel.obtain();byte[] bytes=null;try{parcel.writeBundle(state);bytes=parcel.marshall();for(String marker:MARKERS)if(contains(bytes,marker.getBytes(StandardCharsets.UTF_8))||contains(bytes,marker.getBytes(StandardCharsets.UTF_16LE)))return true;return false;}finally{if(bytes!=null)Arrays.fill(bytes,(byte)0);parcel.recycle();}}
    private static final class LocalConnection extends HttpURLConnection {
        LocalConnection(URL value){super(value);}
        public void disconnect(){}public boolean usingProxy(){return false;}public void connect()throws IOException{throw new IOException("SYNTHETIC_NETWORK_DISABLED");}
        @Override public int getResponseCode()throws IOException{throw new IOException("SYNTHETIC_NETWORK_DISABLED");}
        @Override public InputStream getInputStream()throws IOException{throw new IOException("SYNTHETIC_NETWORK_DISABLED");}
        @Override public OutputStream getOutputStream(){return new ByteArrayOutputStream(){private boolean counted;@Override public void close(){if(!counted){counted=true;if(new String(buf,0,count,StandardCharsets.UTF_8).contains("__act=qrlogin_gen"))QR_REQUESTS.incrementAndGet();Arrays.fill(buf,(byte)0);reset();}}};}
    }
    private void installLocalTransport(){URL.setURLStreamHandlerFactory(protocol->("https".equals(protocol)||"http".equals(protocol))?new URLStreamHandler(){@Override protected URLConnection openConnection(URL url){return new LocalConnection(url);}}:null);}
    private NativeLoginView login()throws Exception{return main(()->(NativeLoginView)field(activity,"loginView"));}
    private void openLogin()throws Exception{main(()->{Method method=MainActivity.class.getDeclaredMethod("login");method.setAccessible(true);method.invoke(activity);return null;});waitForIdleSync();}
    private void select(int mode)throws Exception{NativeLoginView login=login();if(login==null){openLogin();login=login();}final NativeLoginView selected=login;main(()->{selected.restoreSelectedMethod(mode);return null;});waitForIdleSync();SystemClock.sleep(120);}
    private void fill(int mode)throws Exception{NativeLoginView login=login();main(()->{Object form=field(login,"credentialView");((EditText)field(form,"account")).setText(mode==1?MARKERS[0]:MARKERS[2]);((EditText)field(form,"matchPhone")).setText(mode==1?MARKERS[3]:MARKERS[6]);((EditText)field(form,"imageAnswer")).setText(MARKERS[5]);((EditText)field(form,"secret")).setText(mode==1?MARKERS[1]:MARKERS[4]);((CheckBox)field(form,"agreement")).setChecked(true);return null;});}
    private void recreate(Mutation change)throws Exception{MainActivity previous=activity;int before=saves;mutation=change;main(()->{previous.recreate();return null;});for(int i=0;i<160;i++){MainActivity next=activity;if(next!=null&&next!=previous&&!next.isDestroyed()&&next.hasWindowFocus()){waitForIdleSync();mutation=null;if(saves<=before)throw new AssertionError("ACTUAL_FRAMEWORK_SAVE_REQUIRED");return;}SystemClock.sleep(25);}throw new AssertionError("ACTUAL_MAIN_RECREATE_TIMEOUT");}
    private boolean fieldsEmpty(NativeLoginView login)throws Exception{return main(()->{Object form=field(login,"credentialView");return form!=null&&((EditText)field(form,"account")).length()==0&&((EditText)field(form,"secret")).length()==0&&((EditText)field(form,"matchPhone")).length()==0&&((EditText)field(form,"imageAnswer")).length()==0&&!((CheckBox)field(form,"agreement")).isChecked();});}
    private void preserved(int mode,String label)throws Exception{stage=label;select(mode);fill(mode);NativeLoginView old=login();Object oldForm=main(()->field(old,"credentialView"));int qrBefore=QR_REQUESTS.get();recreate(null);NativeLoginView restored=login();check(label+"MethodSurvivesActualMainRecreation",restored!=null&&restored.selectedMethod()==mode);check(label+"SavesOnlyMethodAndScopedHash",savedContainsMethod&&savedMethod==mode&&savedScopeIsHash);check(label+"RestoresNoCredentialOrAgreementFields",fieldsEmpty(restored));check(label+"OldCredentialFieldsAreCleared",main(()->((EditText)field(oldForm,"account")).length()==0&&((EditText)field(oldForm,"secret")).length()==0));SystemClock.sleep(120);check(label+"StartsNoInterimQrRequest",QR_REQUESTS.get()==qrBefore&&main(()->field(restored,"attempt")==null));check(label+"PendingMethodConsumedOnce",main(()->Integer.valueOf(-1).equals(field(activity,"pendingLoginMethod"))));}
    private void rejected(String label,Mutation change)throws Exception{stage=label;select(2);recreate(change);NativeLoginView restored=login();check(label+"FallsBackToQr",restored!=null&&restored.selectedMethod()==0);check(label+"DoesNotRetainPendingMethod",main(()->Integer.valueOf(-1).equals(field(activity,"pendingLoginMethod"))));}
    private static void homeRoute(Bundle state){Bundle navigation=new Bundle();navigation.putInt("version",1);navigation.putInt("backCount",0);navigation.putStringArray("current",new String[]{"home","","NGA","1","","","","","","0"});state.putBundle(NAV,navigation);}
    private void runCases()throws Exception{
        openLogin();preserved(2,"sms");preserved(1,"password");
        rejected("wrongScope",state->state.putString(SCOPE,"0000000000000000000000000000000000000000000000000000000000000000"));rejected("stringMethod",state->state.putString(METHOD,"2"));rejected("longMethod",state->state.putLong(METHOD,2));rejected("wrongScopeType",state->state.putBoolean(SCOPE,true));rejected("negativeMethod",state->state.putInt(METHOD,-1));rejected("outOfRangeMethod",state->state.putInt(METHOD,3));
        stage="nonAuthRoute";select(2);int qrBefore=QR_REQUESTS.get();recreate(LoginMethodLifecycleProbe::homeRoute);check("nonAuthRouteDoesNotRestoreLoginUi",login()==null);check("nonAuthRouteDoesNotKeepPendingLoginMethod",main(()->Integer.valueOf(-1).equals(field(activity,"pendingLoginMethod"))));SystemClock.sleep(120);check("nonAuthRouteStartsNoAuthorization",QR_REQUESTS.get()==qrBefore);
        stage="nonAuthSaveSchema";recreate(state->{state.putInt(METHOD,2);try{Method scope=MainActivity.class.getDeclaredMethod("contentScope");scope.setAccessible(true);state.putString(SCOPE,(String)scope.invoke(activity));}catch(Exception failure){throw new IllegalStateException("SYNTHETIC_SCOPE_CAPTURE",failure);}});check("normalNonAuthSaveOmitsLoginMethod",!savedContainsMethod);check("injectedMethodOnNonAuthRouteCannotOpenLogin",login()==null);openLogin();check("laterExplicitLoginStartsFreshQrMethod",login()!=null&&login().selectedMethod()==0);
        check("savedFrameworkParcelContainsNoSyntheticCredentials",!sensitiveStateObserved);check("credentialRestorationNeverOwnsQrAttempt",!credentialAttemptObserved);report.put("completed",true).put("passed",true).put("assertions",assertions).put("actualMainRecreations",saves).put("qrGenerationRequestsCounted",QR_REQUESTS.get());
    }
    @Override public void onStart(){Application app=null;try{
        if(!authorized||android.os.Build.VERSION.SDK_INT<29||!"cn.ngapure.writelifecycle".equals(getTargetContext().getPackageName())||getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("OWN_UID_NO_INTERNET_API29_POST_LIFECYCLE_REQUIRED");installLocalTransport();NgaStore store=new NgaStore(getTargetContext());store.setAutoCheckIn(false);store.setMotionEnabled(false);store.clearLogin();getTargetContext().getSharedPreferences("nga_unread_notifications",0).edit().putBoolean("enabled",false).commit();app=(Application)getTargetContext().getApplicationContext();app.registerActivityLifecycleCallbacks(lifecycle);activity=(MainActivity)startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(),MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();runCases();
    }catch(Exception|Error failure){try{report.put("completed",false).put("passed",false).put("failureStage",stage).put("failureType",failure.getClass().getSimpleName()).put("assertions",assertions);}catch(Exception ignored){}
    }finally{try{mutation=null;if(activity!=null&&!activity.isDestroyed())main(()->{activity.finish();return null;});if(app!=null)app.unregisterActivityLifecycleCallbacks(lifecycle);report.put("ownUidOnly",true).put("internetPermission",false).put("realNetworkConnections",0).put("realClientStarted",false).put("realAccountAccessed",false).put("loginOrSmsSubmissionInvoked",false).put("safeMethodStateOnly",true).put("privateInputsExported",false);}catch(Exception ignored){}Bundle result=new Bundle();result.putString("safe_login_method_lifecycle_report",report.toString());finish(Activity.RESULT_OK,result);}}
}
