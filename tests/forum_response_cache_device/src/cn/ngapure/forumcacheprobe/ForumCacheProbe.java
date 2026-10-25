package cn.ngapure.forumcacheprobe;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Process;
import android.os.StrictMode;
import android.os.SystemClock;
import cn.ngapure.app.ForumResponseCache;
import cn.ngapure.app.NgaApi;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKey;
import org.json.JSONArray;
import org.json.JSONObject;

/** Own UID, no INTERNET, unmodified production JAR, real Android Keystore and disk files. */
public final class ForumCacheProbe extends Instrumentation {
    private static final String SCOPE="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OTHER="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private final JSONObject report=new JSONObject();private final JSONArray checks=new JSONArray();
    private String mode="seed";private int assertions;
    @Override public void onCreate(Bundle args){super.onCreate(args);if(args!=null)mode=args.getString("mode","seed");start();}
    @Override public void onStart(){boolean passed=false;String failure="NONE";
        try{
            require(getTargetContext().checkSelfPermission(Manifest.permission.INTERNET)==PackageManager.PERMISSION_DENIED,"NoInternetPermission");
            require(getTargetContext().getApplicationInfo().uid!=getTargetContext().getPackageManager().getApplicationInfo("cn.ngapure.app",0).uid,"OwnUidSeparateFromRealClient");
            report.put("binding",new JSONObject(new String(asset("binding.json"),StandardCharsets.UTF_8)));
            if(mode.equals("seed"))seed();else if(mode.equals("read"))read();else throw new IllegalArgumentException("mode must be seed or read");
            passed=true;
        }catch(Exception|AssertionError issue){failure=issue.getClass().getSimpleName()+":"+issue.getMessage();}
        try{report.put("passed",passed).put("failure",failure).put("mode",mode).put("pid",Process.myPid()).put("assertionCount",assertions).put("checks",checks)
                .put("realNetworkRequests",0).put("realAccountRead",false).put("communityWrites",0).put("productionClassesRewritten",false);
            Files.write(new File(getTargetContext().getFilesDir(),"cache-"+mode+"-report.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
        }catch(Exception ignored){ }
        Bundle result=new Bundle();result.putString("safe_cache_report",report.toString());finish(Activity.RESULT_OK,result);
    }
    private ForumResponseCache cache(){return new ForumResponseCache(getTargetContext());}
    private File directory(){return new File(getTargetContext().getCacheDir(),"forum-response-v1");}
    private File state(){return new File(getTargetContext().getFilesDir(),"synthetic-cache-seed.json");}
    private static NgaApi.ForumPage page(String key){NgaApi.ForumPage p=new NgaApi.ForumPage();p.key=key;p.fid=key;p.name="Own UID synthetic board";
        NgaApi.Topic t=new NgaApi.Topic();t.tid="456";t.title="Private synthetic title 😅";t.author="Synthetic author";t.authorId="789";t.replies=9;t.votes.likesKnown=true;t.votes.likes=12;t.votes.dislikesKnown=true;t.votes.dislikes=3;p.topics.add(t);
        NgaApi.Forum child=new NgaApi.Forum();child.key="321";child.fid="321";child.parentFid=key;child.name="Synthetic child";p.subForums.add(child);return p;}
    private void seed()throws Exception{
        final AtomicInteger violations=new AtomicInteger();ForumResponseCache current=onMain(()->{StrictMode.ThreadPolicy previous=StrictMode.getThreadPolicy();try{
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyListener(Runnable::run,v->violations.incrementAndGet()).build());
            ForumResponseCache result=cache();result.capture();result.trimMemory();result.invalidate();return result;
        }finally{StrictMode.setThreadPolicy(previous);}});
        require(violations.get()==0,"ConstructorCaptureTrimInvalidateHaveNoMainThreadDiskIO");require(current.awaitIdleForTests(10000),"InitialInvalidationDrained");
        boolean rejected=onMain(()->{try{current.load(SCOPE,"123","",1);return false;}catch(IllegalStateException expected){return true;}});require(rejected,"SynchronousLoadRejectsMainThread");
        NgaApi.ForumPage fixture=page("123");long began=SystemClock.elapsedRealtime();onMain(()->{current.storeAsync(SCOPE,"123","",fixture,current.capture());return null;});require(current.awaitIdleForTests(10000),"AsyncWriteDrained");
        ForumResponseCache.Entry memory=current.load(SCOPE,"123","",1);require(memory!=null&&memory.source.equals("memory"),"RealMemoryHit");
        fixture.topics.get(0).title="modified after completed snapshot";memory.page.topics.get(0).votes.likes=999;memory.page.subForums.clear();
        memory=current.load(SCOPE,"123","",1);require(memory.page.topics.get(0).title.equals("Private synthetic title 😅")&&memory.page.topics.get(0).votes.likes==12&&memory.page.subForums.size()==1,"DeepCopyOnInputAndOutput");
        current.trimMemory();ForumResponseCache.Entry disk=current.load(SCOPE,"123","",1);require(disk!=null&&disk.source.equals("disk")&&disk.ageMs>=memory.ageMs,"ActualAndroidEncryptedDiskHit");
        require(current.load(OTHER,"123","",1)==null&&current.load(SCOPE,"123","999",1)==null&&current.load(SCOPE,"124","",1)==null&&current.load(SCOPE,"123","",2)==null,"ExactScopeParentRouteAndFirstPageOnly");
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);java.security.Key key=store.getKey("ngaaa_forum_response_v1",null);require(key instanceof SecretKey&&key.getEncoded()==null,"AndroidKeystoreNonExportableKey");
        File[] files=data();require(files.length==1,"OneEncryptedEntry");byte[] bytes=Files.readAllBytes(files[0].toPath());String raw=new String(bytes,StandardCharsets.ISO_8859_1);require(!raw.contains("Private synthetic")&&!raw.contains("Synthetic author")&&!raw.contains(SCOPE),"DiskHasNoFixturePlaintextOrScope");
        bytes[bytes.length-1]^=1;Files.write(files[0].toPath(),bytes);current.trimMemory();require(current.load(SCOPE,"123","",1)==null,"ActualGCMRejectsTampering");
        ForumResponseCache sibling=cache();ForumResponseCache.Ticket stale=current.capture();onMain(()->{sibling.invalidate();return null;});require(!current.putIfCurrent(SCOPE,"123","",page("123"),stale),"SharedInstanceEpochRejectsOldTicket");require(current.awaitIdleForTests(10000),"InvalidationDrained");current.trimMemory();require(current.load(SCOPE,"123","",1)==null,"InvalidationRetiresDisk");
        // Last operation reseeds. Root must force-stop this helper and invoke read within 30s.
        require(current.putIfCurrent(SCOPE,"123","",page("123"),current.capture()),"RestartFixtureWritten");
        JSONObject seed=new JSONObject().put("pid",Process.myPid()).put("writtenElapsedMs",SystemClock.elapsedRealtime()).put("productJarSha256",report.getJSONObject("binding").getString("productJarSha256"));
        Files.write(state().toPath(),seed.toString().getBytes(StandardCharsets.UTF_8));report.put("seed",seed).put("phaseElapsedMs",SystemClock.elapsedRealtime()-began);
    }
    private void read()throws Exception{
        JSONObject seed=new JSONObject(new String(Files.readAllBytes(state().toPath()),StandardCharsets.UTF_8));require(seed.getInt("pid")!=Process.myPid(),"RealProcessPidChanged");
        require(seed.getString("productJarSha256").equals(report.getJSONObject("binding").getString("productJarSha256")),"SameFrozenProductionArtifactAcrossRestart");
        ForumResponseCache current=cache();ForumResponseCache.Entry entry=current.load(SCOPE,"123","",1);require(entry!=null&&entry.source.equals("disk"),"ActualProcessRestartReadsEncryptedDisk");
        require(entry.page.topics.get(0).title.equals("Private synthetic title 😅")&&entry.page.topics.get(0).votes.likes==12&&entry.page.subForums.size()==1,"RestartRetainsCanonicalFields");
        require(entry.ageMs>=0&&entry.ageMs<30000,"RestartRespectsOriginalThirtySecondTTL");report.put("restartAgeMs",entry.ageMs).put("seed",seed);
        ForumResponseCache.Ticket old=current.capture();onMain(()->{for(int i=0;i<100;i++)current.invalidate();return null;});require(!current.putIfCurrent(SCOPE,"123","",page("123"),old),"RapidInvalidationRejectsOldResult");require(current.awaitIdleForTests(10000),"RapidInvalidationDrained");current.trimMemory();require(current.load(SCOPE,"123","",1)==null&&data().length==0,"FinalOwnUidCacheCleaned");Files.deleteIfExists(state().toPath());
    }
    private File[] data(){File[] files=directory().listFiles((d,n)->n.endsWith(".bin"));return files==null?new File[0]:files;}
    private void require(boolean passed,String name){assertions++;checks.put(name);if(!passed)throw new AssertionError(name);}
    private <T>T onMain(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get();}
    private byte[] asset(String name)throws Exception{try(InputStream input=getTargetContext().getAssets().open(name);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];for(int count;(count=input.read(buffer))!=-1;)output.write(buffer,0,count);return output.toByteArray();}}
}
