package cn.ngapure.app;

import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real production store; Android preferences are modeled with live-map-before-disk commits. */
public final class BoardVisitsTest {
    private static final String A=repeat('a',64),B=repeat('b',64),C=repeat('c',64);
    private static int checks;
    private static String repeat(char value,int count){StringBuilder text=new StringBuilder(count);for(int i=0;i<count;i++)text.append(value);return text.toString();}
    private static void ok(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static void fails(Runnable action,String label){try{action.run();throw new AssertionError(label);}catch(IllegalStateException expected){checks++;}}
    private static final class Time implements BoardVisits.Clock {long value=1792800000000L;public long now(){return value++;}}
    private static final class Memory implements SharedPreferences {
        final Map<String,Object> live=new HashMap<>(),disk=new HashMap<>();int failures,commits,reads;boolean failRead,throwNext,gateNext;CountDownLatch entered,release;
        public Map<String,?> getAll(){reads++;if(failRead){failRead=false;throw new IllegalStateException("Synthetic unavailable preferences");}return new HashMap<>(live);}
        public String getString(String key,String fallback){return live.containsKey(key)?(String)live.get(key):fallback;}
        public Set<String> getStringSet(String key,Set<String> fallback){return fallback;}
        public int getInt(String key,int fallback){return fallback;}public long getLong(String key,long fallback){return fallback;}
        public float getFloat(String key,float fallback){return fallback;}public boolean getBoolean(String key,boolean fallback){return fallback;}
        public boolean contains(String key){return live.containsKey(key);}
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public Editor edit(){return new Editor(){
            final Map<String,Object> added=new HashMap<>();final Set<String> removed=new HashSet<>();boolean clear;
            public Editor putString(String key,String value){added.put(key,value);return this;}public Editor putStringSet(String key,Set<String> value){added.put(key,new HashSet<>(value));return this;}
            public Editor putInt(String key,int value){added.put(key,value);return this;}public Editor putLong(String key,long value){added.put(key,value);return this;}
            public Editor putFloat(String key,float value){added.put(key,value);return this;}public Editor putBoolean(String key,boolean value){added.put(key,value);return this;}
            public Editor remove(String key){removed.add(key);return this;}public Editor clear(){clear=true;return this;}
            public boolean commit(){commits++;if(clear)live.clear();for(String key:removed)live.remove(key);live.putAll(added);
                if(gateNext){gateNext=false;entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Synthetic gate timeout");}catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new IllegalStateException(cancelled);}}
                if(throwNext){throwNext=false;throw new IllegalStateException("Synthetic commit exception after mutation");}
                if(failures>0){failures--;return false;}disk.clear();disk.putAll(live);return true;}
            public void apply(){commit();}
        };}
        Memory restart(){Memory next=new Memory();next.live.putAll(disk);next.disk.putAll(disk);return next;}
    }
    private static JSONArray entry(Object key,Object name,Object parent,Object icon,Object time){return new JSONArray().put(key).put(name).put(parent).put(icon).put(time);}
    private static JSONArray scope(Object value,JSONArray entries){return new JSONArray().put(value).put(entries);}
    private static String document(JSONArray scopes){return new JSONArray().put(1).put(scopes).toString();}
    public static void main(String[] args)throws Exception{
        coreAndIsolation();boundsAndScopeLru();invalidInputs();corruptionAndReadFailure();commitRollback();concurrentRollback();
        System.out.println("BoardVisits offline checks passed: "+checks+"; synthetic preferences only, no network/account/device");
    }
    private static void coreAndIsolation()throws Exception{
        Memory memory=new Memory();Time time=new Time();BoardVisits visits=new BoardVisits(memory,time);
        ok(visits.get(A).isEmpty()&&memory.live.isEmpty(),"initial reads create no stored index");
        long now=time.value;ok(visits.record(A,"-7","  议事厅  ","","http://img4.nga.cn/ngabbs/nga_classic/f/app/-7.png"),"negative board records after caller success");
        BoardVisits.Entry first=visits.get(A).get(0);ok(first.key.equals("-7")&&first.name.equals("议事厅")&&first.parentFid.isEmpty()&&first.icon.startsWith("https://")&&first.updatedAt==now,"canonical identity trimmed label normalized artwork and time");
        ok(visits.record(A,"123","子版块","-7","")&&visits.record(A,"stid:123","专题合集","-7","//img4.nga.cn/proxy/cache_attach/ficon/123v.png"),"board and collection retain distinct canonical identities");
        ok(keys(visits.get(A)).equals(java.util.Arrays.asList("stid:123","123","-7")),"new visits are first");
        visits.record(A,"123","更新后的子版块","-7","");List<BoardVisits.Entry> snapshot=visits.get(A);
        ok(keys(snapshot).equals(java.util.Arrays.asList("123","stid:123","-7"))&&snapshot.get(0).name.equals("更新后的子版块"),"duplicate moves to front and refreshes metadata");
        try{snapshot.clear();throw new AssertionError("mutable snapshot");}catch(UnsupportedOperationException expected){checks++;}
        int before=memory.commits;long saved=visits.get(A).get(0).updatedAt;visits.get(A);ok(memory.commits==before&&visits.get(A).get(0).updatedAt==saved,"reading is not a new visit");
        ok(visits.get(B).isEmpty()&&visits.get(C).isEmpty(),"host-account digests isolate recent boards");
        visits.record(B,"123","另一个账号的版块","","");visits.record(C,"123","另一个域名的版块","","");
        ok(new BoardVisits(memory.restart(),time).get(A).get(0).name.equals("更新后的子版块"),"durable data survives reconstruction");
        visits.clear(B);ok(visits.get(B).isEmpty()&&visits.get(A).size()==3&&visits.get(C).size()==1,"clear removes only one scope");
        before=memory.commits;visits.clear(B);ok(memory.commits==before,"clearing absent scope does not rewrite index");
        ok(snapshot.size()==3&&snapshot.get(0).key.equals("123"),"prior immutable snapshot remains independent");
        visits.clear(A);visits.clear(C);ok(!memory.live.containsKey(BoardVisits.KEY),"clearing last scope removes index key");
    }
    private static List<String> keys(List<BoardVisits.Entry> entries){List<String> keys=new ArrayList<>();for(BoardVisits.Entry entry:entries)keys.add(entry.key);return keys;}
    private static void boundsAndScopeLru()throws Exception{
        Memory memory=new Memory();Time time=new Time();BoardVisits visits=new BoardVisits(memory,time);
        for(int i=1;i<=34;i++)visits.record(A,Integer.toString(i),"版块 "+i,"","");
        ok(visits.get(A).size()==30&&visits.get(A).get(0).key.equals("34")&&visits.get(A).get(29).key.equals("5"),"per-scope bound evicts oldest visit only");
        visits.record(A,"5","再次访问","","");ok(visits.get(A).get(0).key.equals("5")&&visits.get(A).size()==30,"revisit does not grow entries");
        for(char c='b';c<='f';c++)visits.record(repeat(c,64),"1","Scope "+c,"","");
        visits.get(A);visits.record(repeat('7',64),"1","新范围","","");
        ok(visits.get(A).isEmpty()&&visits.get(B).size()==1&&visits.get(repeat('7',64)).size()==1,"seventh written scope evicts oldest even if it was read");
        visits.record(B,"2","刷新范围","","");visits.record(repeat('8',64),"3","第八范围","","");
        ok(visits.get(C).isEmpty()&&visits.get(B).size()==2,"successful revisit refreshes scope LRU");
        time.value=10;visits.record(B,"9","时钟回拨","","");ok(visits.get(B).get(0).key.equals("9"),"save order controls recency despite clock rollback");
        String name=repeat('\u2028',120),icon="https://img4.nga.cn/"+repeat('a',990)+".png";
        Memory max=new Memory();BoardVisits full=new BoardVisits(max,new Time());
        for(char c='a';c<='f';c++)for(int i=1;i<=30;i++)ok(full.record(repeat(c,64),Integer.toString(i),name,"",icon),"all bounded metadata records fit JSON ceiling");
        ok(((String)max.live.get(BoardVisits.KEY)).length()<=BoardVisits.MAX_JSON_CHARS&&full.get(A).size()==30,"maximum six-by-thirty serialized document remains bounded and readable");
    }
    private static void invalidInputs()throws Exception{
        Memory memory=new Memory();Time time=new Time();BoardVisits visits=new BoardVisits(memory,time);visits.record(A,"1","原版块","","");Object before=memory.live.get(BoardVisits.KEY);int writes=memory.commits;
        for(String scope:new String[]{null,"",repeat('a',63),repeat('a',65),repeat('A',64),repeat('g',64),"cookie=synthetic"}){
            try{visits.get(scope);throw new AssertionError("invalid get scope");}catch(IllegalArgumentException expected){checks++;}
            try{visits.record(scope,"2","不会保存","","");throw new AssertionError("invalid record scope");}catch(IllegalArgumentException expected){checks++;}
            try{visits.clear(scope);throw new AssertionError("invalid clear scope");}catch(IllegalArgumentException expected){checks++;}
        }
        for(String key:new String[]{null,"","0","01","-0","-01","1\n"," 1","1 ","fid=1","stid:0","stid:-1","stid:01","stid:1234567890123456789","1234567890123","-1234567890123","https://bbs.nga.cn/thread.php?fid=1"})ok(!visits.record(A,key,"无效编号","",""),"noncanonical route rejected");
        for(String parent:new String[]{"0","01","-0","stid:2"," 2","2\n",repeat('9',13),"1"})ok(!visits.record(A,"1","无效父版块",parent,""),"invalid or self parent rejected");
        for(String name:new String[]{null,""," ",repeat('中',121),"a\nb","a\tb","a\u202eb","\ud800","\udc00"})ok(!visits.record(A,"2",name,"",""),"invalid display label rejected");
        time.value=0;ok(!visits.record(A,"2","时钟无效","",""),"nonpositive time rejected");time.value=Long.MAX_VALUE;ok(!visits.record(A,"2","时钟溢出","",""),"overflow time rejected");
        ok(memory.commits==writes&&memory.live.get(BoardVisits.KEY).equals(before),"all invalid inputs preserve old bytes without writes");
        time.value=1792800000000L;
        for(String icon:new String[]{"https://evil.invalid/a.png","https://img4.nga.cn.evil.invalid/a.png","https://user:password@img4.nga.cn/a.png","https://img4.nga.cn/a.png#secret","https://img4.nga.cn/../a.png","https://img4.nga.cn:444/a.png",repeat('a',2049),repeat('[',900)+repeat(']',900)}){
            ok(visits.record(A,"2","图片缺失保留文字","",icon)&&visits.get(A).get(0).icon.isEmpty(),"untrusted icon removed while valid route remains usable");
        }
        ok(visits.record(A,"stid:999999999999999999","边界合集","-999999999999","")&&visits.record(A,"-999999999999","边界负版块",null,""),"maximum route widths and optional null parent are accepted");
    }
    private static void corruptionAndReadFailure()throws Exception{
        Memory memory=new Memory();Time time=new Time();BoardVisits visits=new BoardVisits(memory,time);String valid=document(new JSONArray().put(scope(A,new JSONArray().put(entry("1","原版块","","",1)))));
        List<Object> corrupt=new ArrayList<>();corrupt.add(Integer.valueOf(1));corrupt.add("");corrupt.add("{}");corrupt.add("[1.0,[]]");corrupt.add("[2,[]]");corrupt.add(valid+"[]");corrupt.add("/*comment*/"+valid);corrupt.add(valid.replace("\"原版块\"","'原版块'"));corrupt.add(repeat('[',6)+repeat(']',6));corrupt.add(repeat('x',BoardVisits.MAX_JSON_CHARS+1));
        corrupt.add(document(new JSONArray().put(scope(A,new JSONArray().put(entry("01","坏编号","","",1))))));
        corrupt.add(document(new JSONArray().put(scope(A,new JSONArray().put(entry("1","原版块","stid:2","",1))))));
        corrupt.add(document(new JSONArray().put(scope(A,new JSONArray().put(entry("1"," padded ","","",1))))));
        corrupt.add(document(new JSONArray().put(scope(A,new JSONArray().put(entry("1","原版块","","https://evil.invalid/x.png",1))))));
        corrupt.add(valid.replace(",1]",",1.0]"));
        corrupt.add(document(new JSONArray().put(scope(A,new JSONArray().put(entry("1","原版块","","","1"))))));
        corrupt.add(document(new JSONArray().put(scope(A,new JSONArray().put(entry("1","原版块","","",0))))));
        corrupt.add(document(new JSONArray().put(scope(A,new JSONArray().put(entry("1","原版块","","",Long.MAX_VALUE))))));
        corrupt.add(document(new JSONArray().put(scope(A,new JSONArray().put(entry("1","原版块","","",1)).put(entry("1","重复","","",2))))));
        corrupt.add(document(new JSONArray().put(scope(A,new JSONArray().put(entry("1","原版块","","",1)))).put(scope(A,new JSONArray().put(entry("2","重复范围","","",2))))));
        corrupt.add(document(new JSONArray().put(scope(A,new JSONArray()))));
        JSONArray rows=new JSONArray();for(int i=1;i<=31;i++)rows.put(entry(Integer.toString(i),"过多条目","","",i));corrupt.add(document(new JSONArray().put(scope(A,rows))));
        JSONArray scopes=new JSONArray();for(char c='a';c<='g';c++)scopes.put(scope(repeat(c,64),new JSONArray().put(entry("1","过多范围","","",1))));corrupt.add(document(scopes));
        int corruptionIndex=0;for(Object bad:corrupt){memory.live.put(BoardVisits.KEY,bad);int before=memory.commits;fails(()->visits.get(A),"corrupt shared index read surfaces failure at fixture "+corruptionIndex++);fails(()->visits.record(A,"2","新访问","",""),"corrupt index is never replaced by new list");fails(()->visits.clear(A),"corrupt index cannot erase other scopes");ok(memory.commits==before&&bad.equals(memory.live.get(BoardVisits.KEY)),"corrupt bytes preserved without any editor commit");}
        memory.live.put(BoardVisits.KEY,valid);memory.disk.put(BoardVisits.KEY,valid);int before=memory.commits;
        memory.failRead=true;fails(()->visits.record(A,"2","读失败","",""),"unavailable read prevents any save");ok(memory.commits==before&&memory.live.get(BoardVisits.KEY).equals(valid),"read failure never grants permission to overwrite with empty");
        memory.failRead=true;fails(()->visits.clear(A),"unavailable clear preserves stored index");ok(visits.get(A).size()==1,"one later successful read recovers old data");
    }
    private static void commitRollback()throws Exception{
        Memory memory=new Memory();Time time=new Time();BoardVisits visits=new BoardVisits(memory,time);visits.record(A,"1","已保存","","");visits.record(B,"2","另一个范围","","");Map<String,Object> before=new HashMap<>(memory.live);
        memory.failures=2;fails(()->visits.record(A,"3","失败新访问","",""),"failed durable save throws");ok(memory.live.equals(before)&&visits.get(A).get(0).key.equals("1")&&visits.get(B).get(0).key.equals("2"),"double commit failure restores complete shared live index");
        memory.edit().putString("unrelated","later flush").commit();ok(new BoardVisits(memory.restart(),time).get(A).get(0).key.equals("1"),"later unrelated write cannot persist failed visit");
        memory.failures=2;fails(()->visits.clear(A),"failed scoped clear throws");ok(visits.get(A).size()==1&&visits.get(B).size()==1,"failed clear restores both scopes");
        memory.throwNext=true;fails(()->visits.record(A,"4","抛错访问","",""),"exception after Android live mutation throws");ok(visits.get(A).get(0).key.equals("1"),"exception rollback restores old index");
        Memory absent=new Memory();BoardVisits empty=new BoardVisits(absent,time);absent.failures=2;fails(()->empty.record(A,"1","首次失败","",""),"failed first write throws");ok(absent.live.isEmpty()&&empty.get(A).isEmpty(),"failed initial write restores key absence");
        Memory full=new Memory();BoardVisits six=new BoardVisits(full,time);for(char c='a';c<='f';c++)six.record(repeat(c,64),"1","完整范围","","");String raw=(String)full.live.get(BoardVisits.KEY);full.failures=2;fails(()->six.record(repeat('7',64),"2","失败的第七范围","",""),"failed scope eviction throws");ok(raw.equals(full.live.get(BoardVisits.KEY))&&six.get(A).size()==1&&six.get(repeat('7',64)).isEmpty(),"rollback also restores would-be evicted scope");
    }
    private static void concurrentRollback()throws Exception{
        Memory memory=new Memory();Time time=new Time();BoardVisits first=new BoardVisits(memory,time),second=new BoardVisits(memory,time);first.record(A,"1","原访问","","");memory.failures=2;memory.gateNext=true;memory.entered=new CountDownLatch(1);memory.release=new CountDownLatch(1);CountDownLatch attempted=new CountDownLatch(1),done=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
        Thread failing=new Thread(()->{try{first.record(A,"2","失败访问","","");error.set(new AssertionError("first write must fail"));}catch(IllegalStateException expected){}catch(Throwable failure){error.set(failure);}},"BoardVisitsFailingFixture");
        Thread following=new Thread(()->{attempted.countDown();try{second.record(B,"3","随后成功访问","","");}catch(Throwable failure){error.set(failure);}finally{done.countDown();}},"BoardVisitsFollowingFixture");
        failing.start();try{ok(memory.entered.await(2,TimeUnit.SECONDS),"first instance has staged its live write");following.start();ok(attempted.await(2,TimeUnit.SECONDS),"second instance attempts save");ok(!done.await(100,TimeUnit.MILLISECONDS),"static lock excludes second instance during rollback");}finally{memory.release.countDown();failing.join(3000);if(following.getState()!=Thread.State.NEW)following.join(3000);}
        ok(!failing.isAlive()&&!following.isAlive()&&error.get()==null,"serialized multi-instance rollback finishes");ok(first.get(A).get(0).key.equals("1")&&first.get(B).get(0).key.equals("3"),"successful different-scope save survives first rollback without failed visit");
    }
}
