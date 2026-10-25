package cn.ngapure.imageprobe;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import cn.ngapure.app.NativeMediaPlayer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Exact product player, local generated silence and foreground Activity. No network/account. */
public final class NativeMediaPlayerProbe extends Instrumentation {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final JSONObject report=new JSONObject(),checks=new JSONObject();
    private ProbeHostActivity activity;
    private NativeMediaPlayer dialog;
    private File fixture;
    private boolean expectFixed;
    private int assertions;
    @Override public void onCreate(Bundle args){super.onCreate(args);expectFixed=args!=null&&args.getString("expectFixed","false").equals("true");start();}
    @Override public void onStart(){
        boolean passed=false,clean=true;String failure="NONE";
        try {
            require(getTargetContext().getPackageName().equals("cn.ngapure.imageprobe"),"targetsHelperPackage");
            require(getTargetContext().checkSelfPermission(Manifest.permission.INTERNET)==PackageManager.PERMISSION_DENIED,"noInternetPermission");
            try{require(getTargetContext().getApplicationInfo().uid!=getTargetContext().getPackageManager().getApplicationInfo("cn.ngapure.app",0).uid,"ownUidDifferentFromMain");}catch(PackageManager.NameNotFoundException absent){put(report,"mainClientInstalled",false);}
            try(InputStream input=getTargetContext().getAssets().open("binding.json");ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] bytes=new byte[8192];int count;while((count=input.read(bytes))!=-1)output.write(bytes,0,count);put(report,"binding",new JSONObject(new String(output.toByteArray(),java.nio.charset.StandardCharsets.UTF_8)));}
            fixture=new File(getTargetContext().getCacheDir(),"synthetic-silence.wav");writeSilence(fixture);
            activity=(ProbeHostActivity)startActivitySync(new Intent().setClassName("cn.ngapure.imageprobe","cn.ngapure.imageprobe.ProbeHostActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            Constructor<NativeMediaPlayer> constructor=NativeMediaPlayer.class.getDeclaredConstructor(Activity.class,String.class,boolean.class,boolean.class);constructor.setAccessible(true);
            dialog=onMain(()->{NativeMediaPlayer value=constructor.newInstance(activity,fixture.getAbsolutePath(),true,false);value.show();return value;});
            onMain(()->{invoke("toggle");return null;});await(()->onMain(this::playing),"localSilentWavActuallyPlaying");
            onMain(()->{focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);return null;});require(!onMain(this::playing),"firstTransientLossActuallyPauses");
            boolean afterFirst=onMain(()->(Boolean)field(dialog,"resumeOnFocus"));put(report,"resumeIntentAfterFirstLoss",afterFirst);require(afterFirst,"firstTransientLossPreservesResumeIntent");
            onMain(()->{focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);return null;});boolean afterRepeated=onMain(()->(Boolean)field(dialog,"resumeOnFocus"));put(report,"resumeIntentAfterRepeatedLoss",afterRepeated);
            onMain(()->{focus(AudioManager.AUDIOFOCUS_GAIN);return null;});SystemClock.sleep(80);boolean resumed=onMain(this::playing);put(report,"playingAfterRegain",resumed);
            if(expectFixed){require(afterRepeated,"repeatedTransientLossKeepsResumeIntent");require(resumed,"regainedFocusResumesPreviouslyPlayingAudio");}
            else {require(!afterRepeated&&!resumed,"baselineRepeatedTransientLossBugReproduced");}
            // Explicit pause or permanent focus loss must continue to suppress automatic restart.
            if(!onMain(this::playing)){onMain(()->{invoke("toggle");return null;});await(()->onMain(this::playing),"secondLocalPlaybackStarted");}
            onMain(()->{focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);invoke("pause",boolean.class,false);focus(AudioManager.AUDIOFOCUS_GAIN);return null;});
            require(!onMain(this::playing)&&!onMain(()->(Boolean)field(dialog,"resumeOnFocus")),"explicitPauseSuppressesResume");
            onMain(()->{invoke("toggle");return null;});await(()->onMain(this::playing),"thirdLocalPlaybackStarted");
            onMain(()->{focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);focus(AudioManager.AUDIOFOCUS_LOSS);focus(AudioManager.AUDIOFOCUS_GAIN);return null;});
            require(!onMain(this::playing)&&!onMain(()->(Boolean)field(dialog,"resumeOnFocus")),"permanentLossSuppressesResume");
            onMain(()->{invoke("toggle");return null;});await(()->onMain(this::playing),"fourthLocalPlaybackStarted");
            android.app.Application.ActivityLifecycleCallbacks lifecycle=(android.app.Application.ActivityLifecycleCallbacks)field(dialog,"lifecycle");
            onMain(()->{focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);lifecycle.onActivityPaused(activity);focus(AudioManager.AUDIOFOCUS_GAIN);return null;});
            require(!onMain(this::playing)&&onMain(()->Boolean.TRUE.equals(field(dialog,"background")))&&!onMain(()->(Boolean)field(dialog,"resumeOnFocus")),"backgroundPauseSuppressesFocusResume");
            onMain(()->{lifecycle.onActivityStopped(activity);return null;});require(onMain(()->field(dialog,"player")==null&&!Boolean.TRUE.equals(field(dialog,"focusHeld"))),"backgroundStopReleasesDecoderAndFocus");
            onMain(()->{lifecycle.onActivityResumed(activity);focus(AudioManager.AUDIOFOCUS_GAIN);return null;});require(onMain(()->field(dialog,"player")==null&&!Boolean.TRUE.equals(field(dialog,"background"))),"foregroundReturnRequiresExplicitPlay");
            onMain(()->{dialog.dismiss();return null;});require(onMain(()->Boolean.TRUE.equals(field(dialog,"released"))&&field(dialog,"player")==null&&!Boolean.TRUE.equals(field(dialog,"focusHeld"))),"dismissReleasesPlayerAndAudioFocus");
            passed=true;
        }catch(Exception|AssertionError error){failure=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();put(report,"failureClass",error.getClass().getSimpleName());}
        finally{try{onMain(()->{if(dialog!=null)dialog.dismiss();if(activity!=null)activity.finish();return null;});if(fixture!=null&&fixture.exists())clean=fixture.delete();}catch(Exception error){clean=false;}}
        put(report,"passed",passed&&clean);put(report,"cleanupPassed",clean);put(report,"failure",failure);put(report,"checks",checks);put(report,"assertionCount",assertions);put(report,"expectFixed",expectFixed);put(report,"actualAndroidMediaPlayer",true);put(report,"syntheticSilentWavOnly",true);put(report,"audioFocusNotificationsSimulatedOnMainThread",true);put(report,"realNetworkRequests",0);put(report,"mainAccountRead",false);put(report,"mainAppDataCopied",false);put(report,"communityWrites",0);Bundle out=new Bundle();out.putString("safe_native_media_player_report",report.toString());finish(Activity.RESULT_OK,out);
    }
    private void focus(int event)throws Exception{invoke("focusChanged",int.class,event);}
    private boolean playing()throws Exception{MediaPlayer player=(MediaPlayer)field(dialog,"player");return player!=null&&player.isPlaying();}
    private Object invoke(String name)throws Exception{Method m=dialog.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(dialog);}
    private Object invoke(String name,Class<?> type,Object value)throws Exception{Method m=dialog.getClass().getDeclaredMethod(name,type);m.setAccessible(true);return m.invoke(dialog,value);}
    private static Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static void writeSilence(File file)throws Exception{final int sampleRate=8000,bytes=sampleRate*2*45;try(FileOutputStream out=new FileOutputStream(file)){out.write(new byte[]{'R','I','F','F'});little(out,36+bytes,4);out.write(new byte[]{'W','A','V','E','f','m','t',' '});little(out,16,4);little(out,1,2);little(out,1,2);little(out,sampleRate,4);little(out,sampleRate*2,4);little(out,2,2);little(out,16,2);out.write(new byte[]{'d','a','t','a'});little(out,bytes,4);byte[] silent=new byte[8192];for(int at=0;at<bytes;at+=silent.length)out.write(silent,0,Math.min(silent.length,bytes-at));}}
    private static void little(FileOutputStream out,int value,int size)throws Exception{for(int i=0;i<size;i++)out.write((value>>>(8*i))&255);}
    private <T>T onMain(Callable<T> action)throws Exception{CountDownLatch done=new CountDownLatch(1);Object[] result={null};Throwable[] failed={null};main.post(()->{try{result[0]=action.call();}catch(Throwable error){failed[0]=error;}finally{done.countDown();}});if(!done.await(5,TimeUnit.SECONDS))throw new IllegalStateException("MAIN_TIMEOUT");if(failed[0]!=null){if(failed[0] instanceof Exception)throw(Exception)failed[0];if(failed[0] instanceof Error)throw(Error)failed[0];throw new IllegalStateException("MAIN_FAILURE");}@SuppressWarnings("unchecked")T value=(T)result[0];return value;}
    private void await(Callable<Boolean> condition,String key)throws Exception{long until=SystemClock.elapsedRealtime()+6000;while(SystemClock.elapsedRealtime()<until){if(condition.call()){require(true,key);return;}SystemClock.sleep(20);}require(false,key);}
    private void require(boolean condition,String key){assertions++;put(checks,key,condition);if(!condition)throw new AssertionError(key);}
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}}
}
