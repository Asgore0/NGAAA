package cn.ngapure.app;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Host-side storage checks; preferences model Android's live-map-before-disk commit. */
public final class ReadingProgressTest {
    private static final String A = repeat('a', 64), B = repeat('b', 64), HOST_B = repeat('c', 64);
    private static int checks;
    private static void ok(boolean value, String description) {
        checks++; if (!value) throw new AssertionError(description);
    }
    private static String repeat(char c, int count) { StringBuilder b = new StringBuilder(); while (count-- > 0) b.append(c); return b.toString(); }
    private static final class Time implements ReadingProgress.Clock {
        long value = 1792710000000L;
        public long now() { return value++; }
    }
    private static final class Memory implements SharedPreferences {
        final Map<String,Object> live = new HashMap<>(), disk = new HashMap<>();
        int failures, commits, reads;
        boolean throwNext, gateNext, throwNextRead;
        CountDownLatch entered, release;
        public Map<String,?> getAll() { reads++; if (throwNextRead) { throwNextRead=false; throw new IllegalStateException("Synthetic unavailable preferences"); } return new HashMap<>(live); }
        public String getString(String k,String f) { return live.containsKey(k) ? (String) live.get(k) : f; }
        public Set<String> getStringSet(String k,Set<String> f) { return f; }
        public int getInt(String k,int f) { return f; }
        public long getLong(String k,long f) { return f; }
        public float getFloat(String k,float f) { return f; }
        public boolean getBoolean(String k,boolean f) { return f; }
        public boolean contains(String k) { return live.containsKey(k); }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        public Editor edit() { return new Editor() {
            final Map<String,Object> additions = new HashMap<>(); final Set<String> removals = new HashSet<>(); boolean clear;
            public Editor putString(String k,String v) { additions.put(k,v); return this; }
            public Editor putStringSet(String k,Set<String> v) { additions.put(k,new HashSet<>(v)); return this; }
            public Editor putInt(String k,int v) { additions.put(k,v); return this; }
            public Editor putLong(String k,long v) { additions.put(k,v); return this; }
            public Editor putFloat(String k,float v) { additions.put(k,v); return this; }
            public Editor putBoolean(String k,boolean v) { additions.put(k,v); return this; }
            public Editor remove(String k) { removals.add(k); return this; }
            public Editor clear() { clear = true; return this; }
            private void mutate() { if (clear) live.clear(); for (String key : removals) live.remove(key); live.putAll(additions); }
            public boolean commit() {
                commits++; mutate();
                if (gateNext) {
                    gateNext = false; entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic gate timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
                if (throwNext) { throwNext = false; throw new IllegalStateException("Synthetic disk exception after live-map mutation"); }
                if (failures > 0) { failures--; return false; }
                disk.clear(); disk.putAll(live); return true;
            }
            public void apply() { mutate(); disk.clear(); disk.putAll(live); }
        }; }
        Memory restart() { Memory next = new Memory(); next.live.putAll(disk); next.disk.putAll(disk); return next; }
    }
    private static JSONArray row(String scope,String tid,Object page,Object timestamp,Object geometry) throws Exception {
        return new JSONArray().put(scope).put(tid).put(page).put(timestamp).put(geometry);
    }
    private static String document(JSONArray records) throws Exception {
        return new JSONObject().put("v",1).put("entries",records).toString();
    }
    private static void coreAndIsolation() throws Exception {
        Memory memory = new Memory(); Time time = new Time(); ReadingProgress store = new ReadingProgress(memory,time);
        ok(store.get(A,"7") == null,"missing progress is absent");
        ReaderAnchor anchor = ReaderAnchor.create("7",4,62,3210,1040,5099);
        long savedAt = time.value;
        ok(store.save(A,"7",4,anchor),"loaded page and immutable anchor save durably");
        ReadingProgress.Entry saved = store.get(A,"7");
        ok(saved != null && saved.tid.equals("7") && saved.page == 4 && saved.updatedAt == savedAt,"restored identity, page and original save time");
        ok(saved.anchor != null && saved.anchor.floor == 62 && saved.anchor.fraction == 3210 && saved.anchor.columnWidth == 1040 && saved.anchor.scrollY == 5099,"all fold-aware geometry retained");
        ok(new ReadingProgress(memory.restart(),time).get(A,"7").anchor.targetY(100,200) == 164,"cold process reconstruction retains relative floor geometry");
        int readsBefore = memory.commits; store.get(A,"7");
        ok(memory.commits == readsBefore && store.get(A,"7").updatedAt == savedAt,"reads do not rewrite recency or saved time");
        ok(store.get(B,"7") == null && store.get(HOST_B,"7") == null,"account and host scopes cannot see each other");
        ok(store.save(B,"7",2,null) && store.save(HOST_B,"7",3,null),"same tid may have independent account and host positions");
        ok(store.remove(B,"7") && store.get(B,"7") == null && store.get(A,"7").page == 4 && store.get(HOST_B,"7").page == 3,"single remove is scoped");
        ok(store.save(A,"8",1,null) && store.clear(A),"clear one scope");
        ok(store.get(A,"7") == null && store.get(A,"8") == null && store.get(HOST_B,"7").page == 3,"clear cannot delete another host/account");
        int before = memory.commits;
        ok(store.remove(A,"99") && store.clear(A) && memory.commits == before,"idempotent absent removal and empty clear require no write");
        String persisted = (String) memory.live.get(ReadingProgress.KEY);
        ok(!persisted.contains("cookie") && !persisted.contains("title") && !persisted.contains("content") && !persisted.contains("http"),"schema stores only scope hash, IDs, numeric geometry and time");
    }
    private static void invalidInputs() throws Exception {
        Memory memory = new Memory(); Time time = new Time(); ReadingProgress store = new ReadingProgress(memory,time);
        store.save(A,"1",2,null); Object original = memory.live.get(ReadingProgress.KEY); int before = memory.commits;
        for (String scope : new String[]{null,"", "a",repeat('A',64),repeat('a',65),"cookie=private",repeat('a',63)+"\n"}) {
            ok(!store.save(scope,"1",3,null) && store.get(scope,"1") == null && !store.remove(scope,"1") && !store.clear(scope),"invalid or raw credential scope rejected");
        }
        for (String tid : new String[]{null,"","0","01","-1","1x",repeat('1',19),"1\n"})
            ok(!store.save(A,tid,3,null) && store.get(A,tid) == null && !store.remove(A,tid),"invalid thread ID rejected");
        for (int page : new int[]{-1,0,100000,Integer.MAX_VALUE}) ok(!store.save(A,"1",page,null),"invalid page rejected");
        ok(!store.save(A,"1",2,ReaderAnchor.create("2",2,0,0,400,0)),"anchor cannot name another thread");
        ok(!store.save(A,"1",2,ReaderAnchor.create("1",3,0,0,400,0)),"anchor cannot name another page");
        time.value = 0; ok(!store.save(A,"1",2,null),"nonpositive timestamp rejected");
        time.value = Long.MAX_VALUE; ok(!store.save(A,"1",2,null),"overflow timestamp rejected");
        ok(original.equals(memory.live.get(ReadingProgress.KEY)) && before == memory.commits,"all rejected inputs leave existing progress byte-identical");
        ok(store.get(A,"1").page == 2,"invalid input cannot advance a previously loaded page");
    }
    private static void immutableSnapshot() throws Exception {
        Memory memory = new Memory(); Time time = new Time(); ReadingProgress store = new ReadingProgress(memory,time);
        store.save(A,"1",2,ReaderAnchor.create("1",2,20,1000,800,200));store.save(B,"1",4,null);store.save(A,"2",3,null);
        int before = memory.reads;
        Map<String,ReadingProgress.Entry> snapshot = store.snapshot(A);
        ok(memory.reads == before+1,"whole-topic snapshot inspects Android preferences exactly once");
        ok(snapshot.size()==2 && snapshot.get("1").page==2 && snapshot.get("2").page==3,"snapshot returns all and only requested-scope entries");
        ok(new java.util.ArrayList<>(snapshot.keySet()).equals(java.util.Arrays.asList("2","1")),"snapshot preserves bounded latest-save-first order");
        ok(snapshot.get("1").anchor!=null && snapshot.get("1").anchor.floor==20,"snapshot retains immutable geometry values");
        try{snapshot.clear();throw new AssertionError("Snapshot allowed mutation");}catch(UnsupportedOperationException expected){checks++;}
        try{snapshot.put("3",snapshot.get("1"));throw new AssertionError("Snapshot allowed insertion");}catch(UnsupportedOperationException expected){checks++;}
        try{snapshot.entrySet().iterator().next().setValue(snapshot.get("1"));throw new AssertionError("Snapshot allowed entry replacement");}catch(UnsupportedOperationException expected){checks++;}
        store.save(A,"1",8,null);store.remove(A,"2");
        ok(snapshot.get("1").page==2 && snapshot.get("2").page==3 && store.snapshot(A).get("1").page==8,"later writes do not mutate an earlier list render snapshot");
        before=memory.reads;ok(store.snapshot("not-a-scope").isEmpty() && memory.reads==before,"invalid scope snapshot does not inspect storage");
        memory.throwNextRead=true;int commits=memory.commits;ok(store.snapshot(A).isEmpty() && memory.commits==commits,"unavailable storage snapshot is empty without clearing data");
        ok(store.snapshot(B).size()==1 && store.snapshot(B).get("1").page==4,"snapshot isolation survives other-scope removals");
        ok(store.snapshot(HOST_B).isEmpty(),"unknown valid identity snapshot is empty");
    }
    private static void malformedStorage() throws Exception {
        ok(ReadingProgress.decode(null).isEmpty() && ReadingProgress.decode(Integer.valueOf(3)).isEmpty() && ReadingProgress.decode(new HashSet<String>()).isEmpty(),"malicious preference types are ignored");
        for (String raw : new String[]{"", "{}", "[]", "null", "{", "{\"v\":2,\"entries\":[]}", "{\"v\":\"1\",\"entries\":[]}", "{\"v\":1.0,\"entries\":[]}", "{\"v\":true,\"entries\":[]}", "{\"v\":1,\"entries\":[],\"extra\":1}", "{\"v\":1,\"entries\":{}}", "{\"v\":1,\"entries\":[]} false", repeat('[',20000)+repeat(']',20000), repeat(' ',ReadingProgress.MAX_JSON_CHARS+1)})
            ok(ReadingProgress.decode(raw).isEmpty(),"invalid schema, trailing data, huge or deeply nested JSON rejected");
        for (String raw : new String[]{"{'v':1,'entries':[]}", "{v:1,entries:[]}", "{\"v\":1;\"entries\":[]}", "/* comment */{\"v\":1,\"entries\":[]}", "{\"v\":1,\"entries\":[]}#comment", "{\"v\":1,\"entries\":['\""+repeat('[',10000)+"']}", "{\"v\":1,\"entries\":[]}\u00a0"})
            ok(ReadingProgress.decode(raw).isEmpty(),"nonstandard quotes, comments and misleading nesting syntax rejected before Android parser");
        JSONArray valid = row(A,"1",Integer.valueOf(2),Long.valueOf(100),JSONObject.NULL);
        JSONArray records = new JSONArray().put(valid);
        records.put(row(B,"2","2",Long.valueOf(100),JSONObject.NULL));
        records.put(row(B,"2",Double.valueOf(2.5),Long.valueOf(100),JSONObject.NULL));
        records.put(row(B,"2",Boolean.TRUE,Long.valueOf(100),JSONObject.NULL));
        records.put(row(B,"2",Integer.valueOf(2),"100",JSONObject.NULL));
        records.put(row(B,"2",Integer.valueOf(2),Double.valueOf(100.5),JSONObject.NULL));
        records.put(row(B,"2",Integer.valueOf(2),Long.valueOf(-1),JSONObject.NULL));
        records.put(row(B,"2",Integer.valueOf(2),Long.valueOf(Long.MAX_VALUE),JSONObject.NULL));
        records.put(row(B,"2",Integer.valueOf(2),Long.valueOf(100),new JSONArray().put(0).put(0).put(0).put(0)));
        records.put(row(B,"2",Integer.valueOf(2),Long.valueOf(100),new JSONArray().put(0).put(10001).put(400).put(0)));
        records.put(row(B,"2",Integer.valueOf(2),Long.valueOf(100),new JSONArray().put(0).put(0).put(400).put(NavigationState.MAX_SCROLL+1)));
        records.put(row(B,"2",Integer.valueOf(2),Long.valueOf(100),new JSONArray().put(0).put(0).put(400)));
        records.put(row(B,"2",Integer.valueOf(2),Long.valueOf(100),"raw body"));
        records.put(row("cookie=secret","2",Integer.valueOf(2),Long.valueOf(100),JSONObject.NULL));
        records.put(row(B,"0",Integer.valueOf(2),Long.valueOf(100),JSONObject.NULL));
        records.put(new JSONArray()); records.put("not a row");
        List<ReadingProgress.Entry> parsed = ReadingProgress.decode(document(records));
        ok(parsed.size() == 1 && parsed.get(0).tid.equals("1"),"invalid individual rows are skipped while valid progress survives");
        ok(ReadingProgress.decode(document(new JSONArray().put(valid)).replace(",2,100,",",2.0,100,")).isEmpty(),"integral-looking floating JSON page is not coerced");
        JSONArray duplicate = new JSONArray().put(valid).put(row(A,"1",Integer.valueOf(9),Long.valueOf(999),JSONObject.NULL)).put(row(B,"1",Integer.valueOf(4),Long.valueOf(100),JSONObject.NULL));
        parsed = ReadingProgress.decode(document(duplicate));
        ok(parsed.size() == 2 && parsed.get(0).page == 2 && parsed.get(1).page == 4,"duplicate identity keeps first record; other scopes remain independent");
        JSONArray excessive = new JSONArray(); for (int i=1; i<=129; i++) excessive.put(row(A,Integer.toString(i),Integer.valueOf(1),Long.valueOf(i),JSONObject.NULL));
        ok(ReadingProgress.decode(document(excessive)).isEmpty(),"oversized record count rejected before iteration");
        Memory wrongType = new Memory(); wrongType.live.put(ReadingProgress.KEY,Boolean.TRUE); wrongType.disk.putAll(wrongType.live);
        ReadingProgress store = new ReadingProgress(wrongType,new Time());
        ok(store.get(A,"1") == null && store.save(A,"1",1,null) && store.get(A,"1") != null,"a successful valid save can recover a corrupt local preference");
        ReaderAnchor upper = ReaderAnchor.create(repeat('9',18),99999,2000000,10000,100000,NavigationState.MAX_SCROLL);
        ok(store.save(A,repeat('9',18),99999,upper) && store.get(A,repeat('9',18)).anchor.floor == 2000000,"schema upper bounds round trip without integer overflow");
    }
    private static void boundedCapacity() throws Exception {
        Memory memory = new Memory(); Time time = new Time(); ReadingProgress store = new ReadingProgress(memory,time);
        for (int i=1; i<=128; i++) if (!store.save(i%2==0?A:B,Integer.toString(i),i,null)) throw new AssertionError("quota fixture save");
        ok(ReadingProgress.decode(memory.live.get(ReadingProgress.KEY)).size() == 128,"128-entry global cap");
        store.get(B,"1");
        ok(store.save(A,"129",1,null) && store.get(B,"1") == null && store.get(A,"2") != null,"reading an old entry does not prevent save-order eviction");
        ok(store.save(A,"2",8,null) && ReadingProgress.decode(memory.live.get(ReadingProgress.KEY)).size() == 128,"updating an identity does not consume a second slot");
        time.value = 1; store.save(B,"130",1,null);
        ok(store.get(B,"3") == null && store.get(A,"2").page == 8,"wall-clock rollback does not evict the freshly updated record");
        String encoded = (String) memory.live.get(ReadingProgress.KEY);
        ok(encoded.length() < ReadingProgress.MAX_JSON_CHARS && ReadingProgress.decode(encoded).size() == 128,"full index remains bounded and decodable");
        ok(new ReadingProgress(memory.restart(),time).get(A,"2").page == 8,"quota and recency survive cold reconstruction");
    }
    private static void failureRollback() throws Exception {
        Memory memory = new Memory(); Time time = new Time(); ReadingProgress store = new ReadingProgress(memory,time);
        store.save(A,"1",3,null); store.save(B,"2",4,null); memory.live.put("unrelated","keep"); memory.disk.put("unrelated","keep");
        Map<String,Object> previous = new HashMap<>(memory.live);
        int before = memory.commits; memory.throwNextRead=true;
        ok(!store.save(A,"1",9,null) && previous.equals(memory.live) && memory.commits==before,"unavailable read cannot authorize replacing the index with an empty one");
        memory.throwNextRead=true; ok(!store.remove(A,"1") && previous.equals(memory.live),"unavailable read cannot authorize removal");
        memory.throwNextRead=true; ok(!store.clear(A) && previous.equals(memory.live),"unavailable read cannot authorize scoped clear");
        memory.throwNextRead=true; ok(store.get(A,"1")==null && previous.equals(memory.live),"unavailable read is absent to callers and does not mutate progress");
        memory.failures=2;
        ok(!store.save(A,"1",9,null),"disk failure reports unsuccessful save");
        ok(memory.live.equals(previous) && new ReadingProgress(memory.restart(),time).get(A,"1").page == 3,"two failed commits restore live map and retain disk progress");
        memory.edit().putString("unrelated","later flush").commit();
        ok(new ReadingProgress(memory.restart(),time).get(A,"1").page == 3,"a later unrelated flush cannot persist failed page advancement");
        memory.failures=1; ok(!store.remove(A,"1") && store.get(A,"1").page == 3,"failed single deletion restores old progress");
        memory.failures=2; ok(!store.clear(B) && store.get(B,"2").page == 4,"failed scoped clear restores the removed account record");
        memory.throwNext=true; ok(!store.save(A,"1",10,null) && store.get(A,"1").page == 3,"exception after live-map mutation restores exact prior index");
        ok(store.save(A,"1",10,null) && new ReadingProgress(memory.restart(),time).get(A,"1").page == 10,"subsequent successful save is durable");
        Memory empty = new Memory(); ReadingProgress emptyStore = new ReadingProgress(empty,time); empty.failures=2;
        ok(!emptyStore.save(A,"1",1,null) && !empty.live.containsKey(ReadingProgress.KEY),"failed first save removes its phantom live-map record");
        Memory wrong = new Memory(); wrong.live.put(ReadingProgress.KEY,Integer.valueOf(42)); wrong.disk.putAll(wrong.live); wrong.failures=2;
        ok(!new ReadingProgress(wrong,time).save(A,"1",1,null) && Integer.valueOf(42).equals(wrong.live.get(ReadingProgress.KEY)),"rollback preserves a previous malformed primitive type exactly");
    }
    private static void concurrentInstances() throws Exception {
        Memory memory = new Memory(); Time time = new Time(); ReadingProgress first = new ReadingProgress(memory,time), second = new ReadingProgress(memory,time);
        first.save(A,"1",2,null); memory.failures=2; memory.gateNext=true; memory.entered=new CountDownLatch(1); memory.release=new CountDownLatch(1);
        CountDownLatch secondStarted=new CountDownLatch(1), secondDone=new CountDownLatch(1); AtomicReference<Throwable> error=new AtomicReference<>();
        Thread failing = new Thread(()->{try{if(first.save(A,"1",9,null))throw new AssertionError("Failed transaction succeeded");}catch(Throwable e){error.set(e);}},"ReadingFailure");
        Thread succeeding = new Thread(()->{secondStarted.countDown();try{if(!second.save(B,"2",4,null))throw new AssertionError("Later transaction failed");}catch(Throwable e){error.set(e);}finally{secondDone.countDown();}},"ReadingSuccess");
        failing.start();
        try {
            ok(memory.entered.await(2,TimeUnit.SECONDS),"failing commit reaches staged live-map mutation"); succeeding.start();
            ok(secondStarted.await(2,TimeUnit.SECONDS) && !secondDone.await(100,TimeUnit.MILLISECONDS),"other instances wait for rollback before reading or saving");
        } finally { memory.release.countDown(); failing.join(3000); if(succeeding.getState()!=Thread.State.NEW)succeeding.join(3000); }
        ok(!failing.isAlive() && !succeeding.isAlive() && error.get()==null,"concurrent rollback completes without hidden exceptions or deadlock");
        ok(first.get(A,"1").page==2 && first.get(B,"2").page==4,"failed earlier writer cannot erase later account progress");
        ok(new ReadingProgress(memory.restart(),time).get(B,"2").page==4,"serialized later write is durable");
    }
    public static void main(String[] args) throws Exception {
        coreAndIsolation(); invalidInputs(); immutableSnapshot(); malformedStorage(); boundedCapacity(); failureRollback(); concurrentInstances();
        System.out.println("ReadingProgress host checks passed: " + checks);
    }
}
