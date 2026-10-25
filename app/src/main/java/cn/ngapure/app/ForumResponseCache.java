package cn.ngapure.app;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Short-lived, exact-session first-page data. All file/Keystore work runs off the UI thread. */
public final class ForumResponseCache {
    public static final long TTL_MS=30000;
    static final int MAX_ENTRIES=6,MAX_ENTRY_BYTES=256*1024,MAX_TOTAL_BYTES=1024*1024;
    static final String DIRECTORY="forum-response-v1",KEY_ALIAS="ngaaa_forum_response_v1";
    private static final Map<String,Shared> SHARED=new HashMap<>();
    interface Directory { File get(); }
    interface Keys { SecretKey get()throws Exception; }
    interface Clock { long elapsed();long wall();String boot(); }
    public static final class Ticket {
        private final Shared owner;private final long epoch;
        private Ticket(Shared owner,long epoch){this.owner=owner;this.epoch=epoch;}
    }
    public static final class Entry {
        public final NgaApi.ForumPage page;
        public final String source;
        public final long ageMs;
        private Entry(NgaApi.ForumPage page,String source,long age){this.page=page;this.source=source;ageMs=age;}
    }
    private static final class Value {
        final byte[] json;final long elapsed,wall;final String boot;
        Value(byte[] json,long elapsed,long wall,String boot){this.json=json;this.elapsed=elapsed;this.wall=wall;this.boot=boot;}
    }
    private static final class Shared {
        final Object state=new Object(),disk=new Object();
        final Directory directory;final Keys keys;final Clock clock;final Executor maintenance,writes;
        final LinkedHashMap<String,Value> memory=new LinkedHashMap<>(8,.75f,true);
        int bytes;long epoch;boolean pending,maintenanceScheduled;String generation;
        Shared(Directory directory,Keys keys,Clock clock,Executor maintenance,Executor writes){this.directory=directory;this.keys=keys;this.clock=clock;this.maintenance=maintenance;this.writes=writes;}
        void forget(){memory.clear();bytes=0;}
        void put(String key,Value value){Value old=memory.put(key,value);if(old!=null)bytes-=old.json.length;bytes+=value.json.length;while(memory.size()>MAX_ENTRIES||bytes>MAX_TOTAL_BYTES){String first=memory.keySet().iterator().next();bytes-=memory.remove(first).json.length;}}
    }
    private final Shared shared;
    private final boolean androidThreads;

    /** Construction and ticket/epoch operations never open files or Android Keystore. */
    public ForumResponseCache(Context context) {
        Context app=context.getApplicationContext();
        Clock clock=new Clock(){
            public long elapsed(){return SystemClock.elapsedRealtime();}
            public long wall(){return System.currentTimeMillis();}
            public String boot(){
                try{int count=Settings.Global.getInt(app.getContentResolver(),Settings.Global.BOOT_COUNT,-1);if(count>=0)return "count:"+count;}catch(RuntimeException ignored){ }
                try{String id=new String(read(new File("/proc/sys/kernel/random/boot_id"),128),StandardCharsets.US_ASCII).trim();return id.matches("[a-f0-9-]{36}")?"id:"+id:"";}catch(Exception unavailable){return "";}
            }
        };
        synchronized(SHARED){
            String id=app.getPackageName()+":"+DIRECTORY;
            Shared value=SHARED.get(id);
            if(value==null){value=new Shared(()->new File(app.getCacheDir(),DIRECTORY),ForumResponseCache::androidKey,clock,
                    Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"nga-forum-cache-invalidate");thread.setDaemon(true);return thread;}),
                    new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(6),task->{Thread thread=new Thread(task,"nga-forum-cache-write");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.DiscardOldestPolicy()));SHARED.put(id,value);}
            shared=value;
        }
        androidThreads=true;
    }
    /** Test seam uses the same encryption/storage implementation and no Android methods. */
    ForumResponseCache(String identity,File directory,Keys keys,Clock clock,Executor maintenance) {
        synchronized(SHARED){Shared value=SHARED.get(identity);if(value==null){value=new Shared(()->directory,keys,clock,maintenance,maintenance);SHARED.put(identity,value);}shared=value;}
        androidThreads=false;
    }
    public Ticket capture(){synchronized(shared.state){return new Ticket(shared,shared.epoch);}}
    /** Releases memory without extending the lifetime or retiring valid disk data. */
    public void trimMemory(){synchronized(shared.state){shared.forget();}}
    /** Caller publishes an immutable page; serialization/encryption/storage never run on the UI. */
    public void storeAsync(String scope,String route,String parent,NgaApi.ForumPage page,Ticket ticket){
        final long elapsed=shared.clock.elapsed(),wall=shared.clock.wall();
        if(!current(ticket,false))return;
        try{shared.writes.execute(()->put(scope,route,parent,page,ticket,elapsed,wall));}catch(RuntimeException unavailable){/* Bounded best-effort cache. */}
    }
    /** Device-fixture barrier only; the application never waits for cache writes. */
    public boolean awaitIdleForTests(long timeoutMs)throws InterruptedException{
        worker();if(timeoutMs<=0)return false;CountDownLatch done=new CountDownLatch(2);
        shared.writes.execute(done::countDown);shared.maintenance.execute(done::countDown);
        return done.await(timeoutMs,TimeUnit.MILLISECONDS);
    }

    /** UI-safe invalidation; instances in this process share one epoch and disk barrier. */
    public void invalidate() {
        synchronized(shared.state){++shared.epoch;shared.forget();shared.pending=true;if(shared.maintenanceScheduled)return;shared.maintenanceScheduled=true;}
        try{shared.maintenance.execute(()->{
            synchronized(shared.disk){
                try{
                    File directory=directory();String next=UUID.randomUUID().toString().replace("-","");
                    writeAtomic(new File(directory,"generation"),next.getBytes(StandardCharsets.US_ASCII));
                    shared.generation=next;prune(directory,next,null);
                    // Pending blocks all disk reads/writes throughout this rotation, including
                    // newer epochs. One successful rotation therefore covers every coalesced clear.
                    synchronized(shared.state){shared.pending=false;shared.maintenanceScheduled=false;}
                }catch(Exception unavailable){synchronized(shared.state){shared.maintenanceScheduled=false;}/* Disk remains disabled until a later invalidation retries. */}
            }
        });}catch(RuntimeException unavailable){synchronized(shared.state){shared.maintenanceScheduled=false;}/* Pending remains true; memory is already invalidated. */}
    }

    /** Fresh hits issue no network request. A miss includes every storage/key/clock failure. */
    public Entry load(String scope,String route,String parent,int page) {
        String key=key(scope,route,parent,page);if(key==null)return null;worker();Ticket ticket=capture();
        long elapsed=shared.clock.elapsed();Value cached;
        synchronized(shared.state){cached=shared.memory.get(key);if(cached!=null&&age(cached.elapsed,elapsed)<0){shared.memory.remove(key);shared.bytes-=cached.json.length;cached=null;}}
        if(cached!=null){Entry found=entry(cached,"memory",elapsed-cached.elapsed,route);return current(ticket,false)?found:null;}
        if(!current(ticket,true))return null;
        final byte[] envelope;
        synchronized(shared.disk){
            if(!current(ticket,true))return null;
            try{
                // A genuinely cold miss must not mkdir/fsync/prune before its network read.
                File directory=shared.directory.get();if(!directory.isDirectory())return null;
                File marker=new File(directory,"generation");if(!marker.isFile())return null;
                String generation=new String(read(marker,64),StandardCharsets.US_ASCII);if(!generation.matches("[a-f0-9]{32}"))return null;
                File file=new File(directory,generation+"-"+key+".bin");
                if(!file.isFile())return null;
                envelope=read(file,MAX_ENTRY_BYTES);
            }catch(Exception unavailable){return null;}
        }
        try{Value value=decrypt(key,envelope);
                long wall=shared.clock.wall(),now=shared.clock.elapsed();String boot=shared.clock.boot();
                if(age(value.wall,wall)<0||age(value.elapsed,now)<0||boot.isEmpty()||!boot.equals(value.boot))return null;
                Entry found=entry(value,"disk",now-value.elapsed,route);if(found==null)return null;
                synchronized(shared.state){if(!currentLocked(ticket)||shared.pending)return null;shared.put(key,value);}
                return found;
        }catch(Exception unavailable){return null;}
    }

    /** The ticket must precede the API read; refresh/logout invalidation makes old results ineligible. */
    public boolean putIfCurrent(String scope,String route,String parent,NgaApi.ForumPage page,Ticket ticket) {
        return put(scope,route,parent,page,ticket,shared.clock.elapsed(),shared.clock.wall());
    }
    private boolean put(String scope,String route,String parent,NgaApi.ForumPage page,Ticket ticket,long loadedElapsed,long loadedWall) {
        String key=key(scope,route,parent,page==null?0:page.page);if(key==null||!route.equals(page.key)||!current(ticket,false))return false;worker();
        final Value value;
        try{
            byte[] json=encodePage(page).toString().getBytes(StandardCharsets.UTF_8);if(json.length>MAX_ENTRY_BYTES-4096)return false;
            value=new Value(json,loadedElapsed,loadedWall,shared.clock.boot());if(age(value.elapsed,shared.clock.elapsed())<0||age(value.wall,shared.clock.wall())<0)return false;
            synchronized(shared.state){if(!currentLocked(ticket))return false;shared.put(key,value);if(shared.pending)return true;}
        }catch(Exception malformed){return false;}
        if(!current(ticket,true)||value.boot.isEmpty())return current(ticket,false);
        try{byte[] encrypted=encrypt(key,value);if(encrypted.length>MAX_ENTRY_BYTES)return false;
            synchronized(shared.disk){
                if(!current(ticket,true))return false;
                File directory=directory();String generation=generation(directory);
                File file=new File(directory,generation+"-"+key+".bin");writeAtomic(file,encrypted);
                // invalidate never waits on this disk lock. Its queued rotation retires any raced write.
                prune(directory,generation,file);
            }
        }catch(Exception unavailable){/* A storage/Keystore failure does not discard a valid memory copy. */}
        return current(ticket,false);
    }
    private boolean current(Ticket ticket,boolean disk){synchronized(shared.state){return currentLocked(ticket)&&(!disk||!shared.pending);}}
    private boolean currentLocked(Ticket ticket){return ticket!=null&&ticket.owner==shared&&ticket.epoch==shared.epoch;}
    private void worker(){if(androidThreads&&Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Forum cache storage requires a worker thread");}
    private static long age(long at,long now){return at>=0&&now>=at&&now-at<TTL_MS?now-at:-1;}
    private static Entry entry(Value value,String source,long age,String route){try{NgaApi.ForumPage page=decodePage(new JSONObject(new String(value.json,StandardCharsets.UTF_8)));return route.equals(page.key)&&page.page==1?new Entry(page,source,age):null;}catch(Exception malformed){return null;}}
    private File directory()throws IOException{File directory=shared.directory.get();if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cache directory unavailable");return directory;}
    private String generation(File directory)throws IOException{
        if(shared.generation!=null)return shared.generation;
        File marker=new File(directory,"generation");String value="";
        if(marker.isFile())try{value=new String(read(marker,64),StandardCharsets.US_ASCII);}catch(IOException ignored){ }
        if(!value.matches("[a-f0-9]{32}")){value=UUID.randomUUID().toString().replace("-","");writeAtomic(marker,value.getBytes(StandardCharsets.US_ASCII));}
        shared.generation=value;prune(directory,value,null);return value;
    }
    private static String key(String scope,String route,String parent,int page){
        if(page!=1||scope==null||!scope.matches("[a-f0-9]{64}")||parent==null)return null;
        try{ForumRoute parsed=ForumRoute.fromKey(route).withParent(parent);return digest(scope+"\n"+parsed.key+"\n"+parsed.parentFid+"\n1");}catch(Exception invalid){return null;}
    }
    private static String digest(String text)throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder(64);for(byte item:hash)result.append(String.format(java.util.Locale.ROOT,"%02x",item&255));return result.toString();}
    private byte[] encrypt(String key,Value value)throws Exception{
        JSONObject payload=new JSONObject().put("version",1).put("elapsed",value.elapsed).put("wall",value.wall).put("boot",value.boot)
                .put("page",new JSONObject(new String(value.json,StandardCharsets.UTF_8)));
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,shared.keys.get());cipher.updateAAD(key.getBytes(StandardCharsets.US_ASCII));
        byte[] iv=cipher.getIV(),encrypted=cipher.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));if(iv.length!=12)throw new IOException("Unexpected cache nonce");
        byte[] result=new byte[1+iv.length+encrypted.length];result[0]=1;System.arraycopy(iv,0,result,1,iv.length);System.arraycopy(encrypted,0,result,13,encrypted.length);return result;
    }
    private Value decrypt(String key,byte[] envelope)throws Exception{
        if(envelope.length<30||envelope[0]!=1)throw new IOException("Invalid cache envelope");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,shared.keys.get(),new GCMParameterSpec(128,Arrays.copyOfRange(envelope,1,13)));
        cipher.updateAAD(key.getBytes(StandardCharsets.US_ASCII));JSONObject payload=new JSONObject(new String(cipher.doFinal(envelope,13,envelope.length-13),StandardCharsets.UTF_8));
        if(payload.getInt("version")!=1)throw new IOException("Invalid cache version");
        JSONObject page=payload.getJSONObject("page");decodePage(page);
        return new Value(page.toString().getBytes(StandardCharsets.UTF_8),payload.getLong("elapsed"),payload.getLong("wall"),payload.getString("boot"));
    }
    private static SecretKey androidKey()throws Exception{
        synchronized(ForumResponseCache.class){KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);java.security.Key key=store.getKey(KEY_ALIAS,null);if(key instanceof SecretKey)return (SecretKey)key;
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());return generator.generateKey();}
    }
    private static byte[] read(File file,int limit)throws IOException{if(file.length()>limit)throw new IOException("Cache file too large");try(FileInputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];for(int n;(n=input.read(buffer))!=-1;){if(output.size()+n>limit)throw new IOException("Cache file too large");output.write(buffer,0,n);}return output.toByteArray();}}
    private static void writeAtomic(File target,byte[] bytes)throws IOException{
        File temporary=new File(target.getParentFile(),"tmp-"+UUID.randomUUID());
        try{try(FileOutputStream output=new FileOutputStream(temporary)){output.write(bytes);output.getFD().sync();}Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally{if(temporary.exists())temporary.delete();}
    }
    private static void prune(File directory,String generation,File keep)throws IOException{
        File[] values=directory.listFiles();if(values==null)throw new IOException("Cache inventory unavailable");List<File> entries=new ArrayList<>();long bytes=0;
        for(File file:values){String name=file.getName();if(name.equals("generation"))continue;
            if(!name.matches(generation+"-[a-f0-9]{64}\\.bin")||file.length()>MAX_ENTRY_BYTES){Files.deleteIfExists(file.toPath());continue;}
            entries.add(file);bytes+=file.length();}
        entries.sort(Comparator.comparingLong(File::lastModified));
        int count=entries.size();
        for(File file:entries){if(count<=MAX_ENTRIES&&bytes<=MAX_TOTAL_BYTES)break;if(file.equals(keep))continue;long size=file.length();Files.deleteIfExists(file.toPath());bytes-=size;count--;}
    }
    private static String text(JSONObject object,String name)throws Exception{Object value=object.get(name);if(!(value instanceof String)||((String)value).length()>MAX_ENTRY_BYTES)throw new IOException("Invalid cache text");return (String)value;}
    private static JSONObject encodePage(NgaApi.ForumPage source)throws Exception{
        if(source.page!=1||source.topics==null||source.subForums==null||source.topics.size()>128||source.subForums.size()>64)throw new IOException("Invalid cache page");
        JSONObject result=new JSONObject().put("key",source.key).put("fid",source.fid).put("stid",source.stid).put("name",source.name).put("page",1);JSONArray topics=new JSONArray(),forums=new JSONArray();
        for(NgaApi.Topic t:source.topics){if(t==null)throw new IOException("Invalid cache topic");VoteMetrics v=t.votes==null?new VoteMetrics():t.votes;
            topics.put(new JSONObject().put("tid",t.tid).put("title",t.title).put("author",t.author).put("authorId",t.authorId).put("time",t.time).put("forum",t.forum).put("fid",t.fid).put("image",t.image).put("avatar",t.avatar).put("replies",t.replies)
                    .put("likesKnown",v.likesKnown).put("dislikesKnown",v.dislikesKnown).put("scoreKnown",v.scoreKnown).put("likes",v.likes).put("dislikes",v.dislikes).put("score",v.score));}
        for(NgaApi.Forum f:source.subForums){if(f==null)throw new IOException("Invalid cached child board");forums.put(new JSONObject().put("key",f.key).put("fid",f.fid).put("stid",f.stid).put("parent",f.parentFid).put("name",f.name).put("category",f.category).put("description",f.description).put("icon",f.icon));}
        result.put("topics",topics).put("forums",forums);decodePage(result);return result;
    }
    private static NgaApi.ForumPage decodePage(JSONObject source)throws Exception{
        NgaApi.ForumPage page=new NgaApi.ForumPage();page.key=text(source,"key");page.fid=text(source,"fid");page.stid=text(source,"stid");page.name=text(source,"name");page.page=source.getInt("page");ForumRoute.fromKey(page.key);
        JSONArray topics=source.getJSONArray("topics"),forums=source.getJSONArray("forums");if(page.page!=1||topics.length()>128||forums.length()>64)throw new IOException("Invalid cached collection");
        for(int i=0;i<topics.length();i++){JSONObject r=topics.getJSONObject(i);NgaApi.Topic t=new NgaApi.Topic();t.tid=text(r,"tid");t.title=text(r,"title");t.author=text(r,"author");t.authorId=text(r,"authorId");t.time=text(r,"time");t.forum=text(r,"forum");t.fid=text(r,"fid");t.image=text(r,"image");t.avatar=text(r,"avatar");t.replies=r.getInt("replies");t.votes.likesKnown=r.getBoolean("likesKnown");t.votes.dislikesKnown=r.getBoolean("dislikesKnown");t.votes.scoreKnown=r.getBoolean("scoreKnown");t.votes.likes=r.getInt("likes");t.votes.dislikes=r.getInt("dislikes");t.votes.score=r.getInt("score");page.topics.add(t);}
        for(int i=0;i<forums.length();i++){JSONObject r=forums.getJSONObject(i);NgaApi.Forum f=new NgaApi.Forum();f.key=text(r,"key");ForumRoute.fromKey(f.key);f.fid=text(r,"fid");f.stid=text(r,"stid");f.parentFid=text(r,"parent");f.name=text(r,"name");f.category=text(r,"category");f.description=text(r,"description");f.icon=text(r,"icon");page.subForums.add(f);}
        return page;
    }
}
