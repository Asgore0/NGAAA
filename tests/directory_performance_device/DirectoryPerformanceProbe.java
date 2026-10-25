package cn.ngapure.directoryperfprobe;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ListAdapter;
import android.widget.ListView;
import cn.ngapure.app.BoardDirectory;
import cn.ngapure.app.ForumFavoritesCoordinator;
import cn.ngapure.app.MainActivity;
import cn.ngapure.app.NgaStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable product classes, real Android Keystore and real directory binding; helper UID only. */
public final class DirectoryPerformanceProbe extends Instrumentation {
    private static final String PACKAGE="cn.ngapure.directoryperfprobe";
    private static final int FAVORITES=17,WARMUPS=2,SAMPLES=7;
    private static final int[] WIDTHS={320,520,760};
    private static final AtomicInteger BLOCKED_HTTP=new AtomicInteger();
    private final JSONObject report=new JSONObject();
    private final JSONArray bindings=new JSONArray(),entries=new JSONArray(),reflows=new JSONArray();
    private MainActivity activity;
    private boolean authorized;
    private int desiredWidth=520,assertions;
    private String stage="guard";
    private final Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){
        @Override public void onActivityResumed(Activity value){if(value instanceof MainActivity){activity=(MainActivity)value;try{applyWidth();}catch(Exception failure){throw new IllegalStateException("LOCAL_VIEWPORT_SETUP_FAILED",failure);}}}
        @Override public void onActivityCreated(Activity a,Bundle b){}
        @Override public void onActivityStarted(Activity a){}
        @Override public void onActivityPaused(Activity a){}
        @Override public void onActivityStopped(Activity a){}
        @Override public void onActivitySaveInstanceState(Activity a,Bundle b){}
        @Override public void onActivityDestroyed(Activity a){}
    };
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticDirectoryPerformanceOnly"));start();}
    @Override public Activity newActivity(ClassLoader loader,String name,Intent intent)throws InstantiationException,IllegalAccessException,ClassNotFoundException{
        Activity value=super.newActivity(loader,name,intent);
        if(value instanceof MainActivity){Configuration configuration=new Configuration();configuration.densityDpi=160;configuration.fontScale=1;value.applyOverrideConfiguration(configuration);}
        return value;
    }
    private static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static Method method(String name,Class<?>... parameters)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,parameters);m.setAccessible(true);return m;}
    private <T>T main(Callable<T> action)throws Exception{FutureTask<T> future=new FutureTask<>(action);runOnMainSync(future);return future.get(30,TimeUnit.SECONDS);}
    private void check(String name,boolean value)throws Exception{assertions++;if(!value)throw new IllegalStateException(name);}
    private void invokeTab(int tab)throws Exception{main(()->{method("navigateHome",int.class).invoke(activity,tab);applyWidth();return null;});waitForIdleSync();}
    private void applyWidth()throws Exception{
        View root=(View)field(activity,"adaptiveLayout");
        if(activity.getResources().getDisplayMetrics().widthPixels<760)throw new IllegalStateException("PHYSICAL_VIEWPORT_MUST_BE_AT_LEAST_760_PIXELS");
        ViewGroup.LayoutParams params=root.getLayoutParams();
        if(params.width!=desiredWidth){params.width=desiredWidth;root.setLayoutParams(params);root.requestLayout();}
    }
    private static int tileCount(ListAdapter adapter){int count=0;for(int i=0;i<adapter.getCount();i++){Object item=adapter.getItem(i);if(!(item instanceof BoardDirectory.Row))throw new IllegalStateException("REAL_DIRECTORY_ROW_REQUIRED");count+=((BoardDirectory.Row)item).entries.size();}return count;}
    private boolean ready()throws Exception{
        ListView list=(ListView)field(activity,"communityList");View root=(View)field(activity,"adaptiveLayout");
        if(!Integer.valueOf(1).equals(field(activity,"mainTab"))||list==null||root.getWidth()!=desiredWidth||list.getWidth()!=desiredWidth||list.getChildCount()==0||list.getTag()!=null)return false;
        int expected=BoardDirectory.columns(Math.max(1,list.getWidth()-24),1,1,true);
        return Integer.valueOf(expected).equals(field(activity,"communityColumns"))&&method("directoryIdentity").invoke(activity).equals(field(activity,"communityDataIdentity"))&&tileCount(list.getAdapter())==FAVORITES;
    }
    private JSONObject timedReadyAction(String kind,int iteration,boolean warmup,Callable<Void> action)throws Exception{
        final CountDownLatch readyDraw=new CountDownLatch(1);
        final long[] stamps=new long[4];
        final String[] failure={null};
        final ViewTreeObserver.OnPreDrawListener[] registered={null};
        main(()->{
            View decor=activity.getWindow().getDecorView();
            registered[0]=()->{try{if(stamps[0]>0&&ready()){stamps[3]=SystemClock.elapsedRealtimeNanos();decor.getViewTreeObserver().removeOnPreDrawListener(registered[0]);readyDraw.countDown();}}catch(Exception error){failure[0]=error.getClass().getSimpleName();decor.getViewTreeObserver().removeOnPreDrawListener(registered[0]);readyDraw.countDown();}return true;};
            decor.getViewTreeObserver().addOnPreDrawListener(registered[0]);
            long cpu=Debug.threadCpuTimeNanos();stamps[0]=SystemClock.elapsedRealtimeNanos();
            action.call();
            stamps[1]=SystemClock.elapsedRealtimeNanos();stamps[2]=Debug.threadCpuTimeNanos()-cpu;
            decor.invalidate();return null;
        });
        boolean completed=readyDraw.await(30,TimeUnit.SECONDS);
        main(()->{activity.getWindow().getDecorView().getViewTreeObserver().removeOnPreDrawListener(registered[0]);return null;});
        check("DIRECTORY_READY_PREDRAW_TIMEOUT",completed&&failure[0]==null&&stamps[3]>=stamps[0]);
        waitForIdleSync();
        return main(()->{
            check("DIRECTORY_READY_STATE_CHANGED",ready());
            ListView list=(ListView)field(activity,"communityList");
            return new JSONObject().put("kind",kind).put("iteration",iteration).put("warmup",warmup).put("widthDp",desiredWidth)
                .put("columns",field(activity,"communityColumns")).put("tileCount",tileCount(list.getAdapter())).put("visibleRows",list.getChildCount())
                .put("mainActionWallNanos",stamps[1]-stamps[0]).put("mainActionCpuNanos",stamps[2]).put("readyPreDrawElapsedNanos",stamps[3]-stamps[0]);
        });
    }
    @SuppressWarnings("unchecked") private void prepareFavorites()throws Exception{
        List<String[]> catalog=main(()->new ArrayList<>((List<String[]>)field(activity,"catalog")));
        check("PUBLIC_CATALOG_TOO_SMALL",catalog.size()>=FAVORITES);
        NgaStore store=new NgaStore(getTargetContext());String scope=ForumFavoritesCoordinator.scope(getTargetContext());
        check("SYNTHETIC_LOGIN_REQUIRED",store.loggedIn()&&!scope.isEmpty());
        Map<String,String[]> snapshot=new LinkedHashMap<>();
        for(int i=0;i<FAVORITES;i++){String[] row=catalog.get(i);snapshot.put(row[0],new String[]{row[1],""});}
        store.activateFavoriteScope(scope);store.mergeFavoriteForums(scope,snapshot);
        check("EXACTLY_17_SYNTHETIC_FAVORITES_REQUIRED",store.favoriteForums().equals(snapshot.keySet())&&store.pendingFavoriteChanges(scope).isEmpty());
        report.put("publicCatalogCount",catalog.size()).put("syntheticFavoriteCount",store.favoriteForums().size()).put("syntheticLoginThroughRealEncryptedStore",true);
        timedReadyAction("setup",0,true,()->{method("openFavoriteBoards").invoke(activity);applyWidth();return null;});
    }
    private void bindSamples()throws Exception{
        stage="recycledRowBinding";
        for(int width:WIDTHS){
            timedReadyAction("bindWidthSetup",0,true,()->{desiredWidth=width;applyWidth();return null;});
            final List<View> recycled=new ArrayList<>();
            for(int iteration=0;iteration<WARMUPS+SAMPLES;iteration++){
                final int sample=iteration;
                bindings.put(main(()->{
                    ListView list=(ListView)field(activity,"communityList");ListAdapter adapter=list.getAdapter();
                    check("BIND_SAMPLE_REQUIRES_17_REAL_TILES",tileCount(adapter)==FAVORITES);
                    int count=adapter.getCount();while(recycled.size()<count)recycled.add(null);
                    long cpu=Debug.threadCpuTimeNanos(),started=SystemClock.elapsedRealtimeNanos();
                    for(int row=0;row<count;row++)recycled.set(row,adapter.getView(row,recycled.get(row),list));
                    long wall=SystemClock.elapsedRealtimeNanos()-started,cpuNanos=Debug.threadCpuTimeNanos()-cpu;
                    for(int row=0;row<count;row++)check("REAL_ROW_VIEW_MUST_EXIST",recycled.get(row)!=null);
                    return new JSONObject().put("kind","recycledAdapterBind17").put("iteration",sample).put("warmup",sample<WARMUPS)
                        .put("widthDp",width).put("columns",field(activity,"communityColumns")).put("rowsBound",count).put("cardsBound",FAVORITES)
                        .put("mainBindWallNanos",wall).put("mainBindCpuNanos",cpuNanos);
                }));
                waitForIdleSync();
            }
        }
    }
    private void enterSamples()throws Exception{
        stage="repeatedDirectoryEntry";
        desiredWidth=520;main(()->{applyWidth();return null;});waitForIdleSync();
        for(int iteration=0;iteration<WARMUPS+SAMPLES;iteration++){
            invokeTab(3);check("PROFILE_TAB_REQUIRED",main(()->Integer.valueOf(3).equals(field(activity,"mainTab"))));
            entries.put(timedReadyAction("profileToFavoritesDirectory",iteration,iteration<WARMUPS,()->{method("navigateHome",int.class).invoke(activity,1);applyWidth();return null;}));
        }
    }
    private void reflowSamples()throws Exception{
        stage="directoryWidthReflow";
        for(int iteration=0;iteration<WARMUPS+SAMPLES;iteration++)for(int width:WIDTHS){
            check("REFLOW_WIDTH_MUST_CHANGE",desiredWidth!=width);
            reflows.put(timedReadyAction("directoryWidthReflow",iteration,iteration<WARMUPS,()->{desiredWidth=width;applyWidth();return null;}));
        }
    }
    private static JSONObject distribution(JSONArray samples,String metric,int width)throws Exception{
        List<Long> values=new ArrayList<>();
        for(int i=0;i<samples.length();i++){JSONObject row=samples.getJSONObject(i);if(!row.getBoolean("warmup")&&(width==0||row.getInt("widthDp")==width))values.add(row.getLong(metric));}
        Collections.sort(values);if(values.isEmpty())throw new IllegalStateException("MEASURED_SAMPLES_REQUIRED");
        long total=0;for(long value:values)total+=value;
        return new JSONObject().put("metric",metric).put("widthDp",width).put("sampleCount",values.size()).put("minNanos",values.get(0))
            .put("medianNanos",values.get(values.size()/2)).put("p95NearestRankNanos",values.get((int)Math.ceil(values.size()*.95)-1))
            .put("maxNanos",values.get(values.size()-1)).put("meanNanos",total/(double)values.size());
    }
    private JSONObject summaries()throws Exception{
        JSONObject result=new JSONObject();JSONArray bind=new JSONArray(),flow=new JSONArray();
        for(int width:WIDTHS){bind.put(distribution(bindings,"mainBindWallNanos",width));bind.put(distribution(bindings,"mainBindCpuNanos",width));flow.put(distribution(reflows,"readyPreDrawElapsedNanos",width));flow.put(distribution(reflows,"mainActionWallNanos",width));}
        return result.put("recycledBinding",bind).put("directoryEntry",distribution(entries,"readyPreDrawElapsedNanos",520))
            .put("directoryEntryMainAction",distribution(entries,"mainActionWallNanos",520)).put("widthReflow",flow);
    }
    private void blockHttp(){URL.setURLStreamHandlerFactory(protocol->("https".equals(protocol)||"http".equals(protocol))?new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{BLOCKED_HTTP.incrementAndGet();throw new IOException("LOCAL_PERFORMANCE_FIXTURE_NETWORK_DISABLED");}}:null);}
    private JSONObject binding()throws Exception{try(InputStream stream=getTargetContext().getAssets().open("binding.json");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] block=new byte[4096];int count;while((count=stream.read(block))!=-1)bytes.write(block,0,count);return new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));}}
    private static String safeFailureType(Throwable failure){Throwable cause=failure;for(int i=0;i<8&&cause.getCause()!=null;i++)cause=cause.getCause();return cause.getClass().getSimpleName();}
    @Override public void onStart(){Application app=null;try{
        if(!authorized||android.os.Build.VERSION.SDK_INT<29||!PACKAGE.equals(getTargetContext().getPackageName())||getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("OWN_UID_NO_INTERNET_API29_SYNTHETIC_REQUIRED");
        blockHttp();report.put("binding",binding()).put("warmupsPerCase",WARMUPS).put("measuredSamplesPerCase",SAMPLES);
        // Clear only this separate helper UID's fixture preferences. Never access cn.ngapure.app storage.
        check("HELPER_LOCAL_PREFERENCES_RESET",getTargetContext().getSharedPreferences("nga_local",0).edit().clear().commit());
        NgaStore store=new NgaStore(getTargetContext());store.setAutoCheckIn(false);store.setMotionEnabled(false);store.setTheme("light");
        store.setCookie("ngaPassportUid=79801; ngaPassportCid=synthetic-directory-performance");
        getTargetContext().getSharedPreferences("nga_unread_notifications",0).edit().putBoolean("enabled",false).commit();
        getTargetContext().getSharedPreferences("nga_board_directory_ui",0).edit().clear().putBoolean("grid",true).commit();
        app=(Application)getTargetContext().getApplicationContext();app.registerActivityLifecycleCallbacks(lifecycle);
        stage="launch";activity=(MainActivity)startActivitySync(new Intent().setClassName(PACKAGE,MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
        check("LOCAL_DENSITY_AND_FONT_REQUIRED",main(()->activity.getResources().getDisplayMetrics().density==1&&activity.getResources().getConfiguration().fontScale==1));
        report.put("environment",main(()->new JSONObject().put("sdk",android.os.Build.VERSION.SDK_INT).put("displayWidthPx",activity.getResources().getDisplayMetrics().widthPixels).put("displayHeightPx",activity.getResources().getDisplayMetrics().heightPixels).put("density",1).put("fontScale",1).put("motionEnabled",false).put("glassEnabled",store.glassEnabled())));
        stage="syntheticFavoriteSetup";prepareFavorites();bindSamples();enterSamples();reflowSamples();
        check("ALL_FIXED_SAMPLES_COMPLETED",bindings.length()==27&&entries.length()==9&&reflows.length()==27);
        report.put("summaries",summaries()).put("completed",true).put("passed",true);
    }catch(Exception|Error failure){try{report.put("completed",false).put("passed",false).put("failureStage",stage).put("failureType",safeFailureType(failure));}catch(Exception ignored){}
    }finally{
        try{
            if(activity!=null&&!activity.isDestroyed())main(()->{activity.finish();return null;});
            if(app!=null)app.unregisterActivityLifecycleCallbacks(lifecycle);
            report.put("assertions",assertions).put("bindingSamples",bindings).put("entrySamples",entries).put("reflowSamples",reflows)
                .put("ownUidOnly",true).put("internetPermission",false).put("blockedHttpConnectionAttempts",BLOCKED_HTTP.get()).put("realNetworkRequests",0)
                .put("realClientStarted",false).put("realAccountAccessed",false).put("realCommunityWrites",0).put("privateInputsExported",false)
                .put("globalDisplaySettingsChanged",false).put("frameRateBenchmark",false).put("performanceThresholdApplied",false)
                .put("limitations",new JSONArray().put("Motion disabled only in helper preferences; isolates native binding and layout, not interactive animation or production tab motion.")
                    .put("Ready pre-draw is readiness of the first eligible traversal, not GPU presentation or screen refresh completion.")
                    .put("Adapter samples invoke the actual production ListAdapter.getView on the main thread with recycled detached rows; they exclude measure/layout/draw.")
                    .put("Elapsed wall time includes synchronous Keystore/Binder waits; thread CPU time excludes those waits. No Keystore operation count is inferred.")
                    .put("p95 is nearest rank across seven samples per case, so it equals max; raw samples and warmups are retained.")
                    .put("No performance threshold or expected improvement: passed means fixture completed safely with 17 real directory tiles.")
                    .put("Emulator scheduling, JIT, asset cache and competing workloads affect timing. Compare repeated same-device runs; no FPS or physical fold claim."));
            File destination=new File(getTargetContext().getFilesDir(),"directory-performance-report.json");
            try(FileOutputStream stream=new FileOutputStream(destination)){stream.write((report.toString(2)+"\n").getBytes(StandardCharsets.UTF_8));}
        }catch(Exception cleanup){try{report.put("passed",false).put("cleanupErrorType",cleanup.getClass().getSimpleName());}catch(Exception ignored){}}
        Bundle result=new Bundle();result.putString("safe_directory_performance_report",report.toString());finish(Activity.RESULT_OK,result);
    }}
}
