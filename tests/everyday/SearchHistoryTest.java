package cn.ngapure.app;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Models Android's memory update before disk commit, including failed rollback commits. */
public final class SearchHistoryTest {
    private static int checks;
    private static final String A=repeat("a",64),B=repeat("b",64),KEY="history:"+A;
    private static void ok(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static void fails(Runnable action,String label){try{action.run();throw new AssertionError(label);}catch(IllegalStateException expected){checks++;}}
    private static String repeat(String value,int count){StringBuilder out=new StringBuilder();for(int i=0;i<count;i++)out.append(value);return out.toString();}
    private static final class Memory implements SharedPreferences {
        final Map<String,Object> values=new HashMap<>(),disk=new HashMap<>();int rejectCount,commits;boolean gateNext;
        CountDownLatch entered,release;
        public Map<String,?> getAll(){return new HashMap<>(values);}
        public String getString(String key,String fallback){return values.containsKey(key)?(String)values.get(key):fallback;}
        public Set<String> getStringSet(String key,Set<String> fallback){return fallback;}
        public int getInt(String key,int fallback){return fallback;}public long getLong(String key,long fallback){return fallback;}
        public float getFloat(String key,float fallback){return fallback;}public boolean getBoolean(String key,boolean fallback){return fallback;}
        public boolean contains(String key){return values.containsKey(key);}
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public Editor edit(){return new Editor(){
            final Map<String,Object> added=new HashMap<>();final Set<String> removed=new HashSet<>();boolean clear;
            public Editor putString(String key,String value){added.put(key,value);return this;}public Editor putStringSet(String key,Set<String> value){added.put(key,value);return this;}
            public Editor putInt(String key,int value){added.put(key,value);return this;}public Editor putLong(String key,long value){added.put(key,value);return this;}
            public Editor putFloat(String key,float value){added.put(key,value);return this;}public Editor putBoolean(String key,boolean value){added.put(key,value);return this;}
            public Editor remove(String key){removed.add(key);return this;}public Editor clear(){clear=true;return this;}
            public boolean commit(){commits++;if(clear)values.clear();for(String key:removed)values.remove(key);values.putAll(added);
                if(gateNext){gateNext=false;entered.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("synthetic timeout");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}}
                if(rejectCount>0){rejectCount--;return false;}disk.clear();disk.putAll(values);return true;}
            public void apply(){commit();}
        };}
        Memory restart(){Memory next=new Memory();next.values.putAll(disk);next.disk.putAll(disk);return next;}
    }
    public static void main(String[] args)throws Exception{
        Memory memory=new Memory();SearchHistory history=new SearchHistory(memory,A);ok(history.get().isEmpty(),"first launch is empty");
        ok(history.recordSubmitted("  明日方舟  ")&&history.get().get(0).equals("明日方舟"),"only submitted normalized keyword saved");
        history.recordSubmitted("NGA");history.recordSubmitted("原神");history.recordSubmitted("nga");
        ok(history.get().equals(java.util.Arrays.asList("nga","原神","明日方舟")),"case-insensitive duplicate moves to front with latest spelling");
        int before=memory.commits;history.recordSubmitted("nga");ok(memory.commits==before,"unchanged top keyword does not rewrite disk");
        for(int i=0;i<15;i++)history.recordSubmitted("讨论 "+i);
        ok(history.get().size()==12&&history.get().get(0).equals("讨论 14")&&history.get().get(11).equals("讨论 3"),"strict twelve keyword retention");
        ok(new SearchHistory(memory.restart(),A).get().equals(history.get()),"successful history survives process reconstruction");
        SearchHistory other=new SearchHistory(memory,B);ok(other.get().isEmpty(),"account/domain digest scope isolation");other.recordSubmitted("另一个账号");
        List<String> snapshot=new ArrayList<>(history.get());ok(history.remove("讨论 9")&&!history.get().contains("讨论 9"),"single keyword deletion");
        ok(!history.remove("不存在")&&!history.remove(null),"missing and invalid deletion are noops");
        history.clear();ok(history.get().isEmpty()&&other.get().equals(java.util.Arrays.asList("另一个账号")),"explicit clear does not touch another account");
        try{other.get().clear();throw new AssertionError("mutable result");}catch(UnsupportedOperationException expected){checks++;}
        String[] rejected={null,""," ","a\nb","a\tb",repeat("中",101),"https://bbs.nga.cn/read.php?tid=123","标题 https://ngabbs.com/read.php?tid=12",
                "HTTP://user:secret@bbs.nga.cn/read.php?tid=12","www.example.com","bbs.nga.cn/read.php?tid=12","cookie: uid=123; cid=secret", "ngaPassportUid=123; ngaPassportCid=secret",
                "{\"password\":\"secret\"}","Authorization: Bearer fake-token","Bearer fake-token","uid=123&cid=secret","密码：示例密码","验证码=123456","\ud800","a\u202eb"};
        int writes=memory.commits;for(String value:rejected)ok(!history.recordSubmitted(value),"invalid or credential-shaped input rejected");
        ok(memory.commits==writes&&history.get().isEmpty(),"invalid input never causes a preference mutation");
        ok(history.recordSubmitted("关于密码的讨论")&&history.recordSubmitted("HTTPS 是什么")&&history.recordSubmitted(repeat("中",100)),"ordinary discussion words and exact keyword boundary allowed");
        String raw=(String)memory.values.get(KEY);ok(!raw.contains("secret")&&!raw.contains("fake-token"),"credential fixtures never appear in stored history");
        for(Object bad:java.util.Arrays.asList(Integer.valueOf(1),"{}","{\"version\":1.0,\"items\":[]}","{\"version\":2,\"items\":[]}","{\"version\":1,\"items\":[null]}","{\"version\":1,\"items\":[1]}","{\"version\":1,\"items\":[\"A\",\"a\"]}","{\"version\":1,\"items\":[\"i\",\"İ\"]}","{\"version\":1,\"items\":[\" padded \"]}","{\"version\":1,\"items\":[\"https://bbs.nga.cn/\"]}","{\"version\":1,\"items\":[\"keyword\"],\"extra\":true}","{\"version\":1,\"items\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\",\"10\",\"11\",\"12\",\"13\"]}",repeat("x",16385))){
            memory.values.put(KEY,bad);fails(history::get,"malformed type/schema/size must surface");int attempts=memory.commits;fails(()->history.recordSubmitted("新关键词"),"corruption cannot be silently overwritten");ok(memory.commits==attempts&&memory.values.get(KEY).equals(bad),"malformed raw bytes retained until clear");history.clear();ok(history.get().isEmpty(),"explicit clear recovers malformed entry");
        }
        history.recordSubmitted("已保存");snapshot=new ArrayList<>(history.get());Map<String,Object> original=new HashMap<>(memory.values);
        memory.rejectCount=2;fails(()->history.recordSubmitted("写盘失败的新词"),"failed save is reported");
        ok(memory.values.equals(original)&&history.get().equals(snapshot),"two disk failures still restore Android live map");
        memory.edit().putString("unrelated","flush").commit();ok(new SearchHistory(memory.restart(),A).get().equals(snapshot),"later unrelated flush cannot persist a failed search entry");
        memory.rejectCount=2;fails(()->history.remove("已保存"),"failed delete is reported");ok(history.get().equals(snapshot),"failed delete retains original history");
        memory.rejectCount=2;fails(history::clear,"failed clear is reported");ok(history.get().equals(snapshot),"failed clear restores original history");
        Memory absent=new Memory();SearchHistory empty=new SearchHistory(absent,A);absent.rejectCount=2;fails(()->empty.recordSubmitted("失败首次保存"),"failed first write");ok(absent.values.isEmpty()&&empty.get().isEmpty(),"failed first write restores absence");
        for(String scope:new String[]{"",repeat("a",63),repeat("g",64),repeat("A",64)}){try{new SearchHistory(memory,scope);throw new AssertionError("invalid scope");}catch(IllegalArgumentException expected){checks++;}}
        crossInstanceRollbackIsSerialized();
        System.out.println("SearchHistory offline checks passed: "+checks);
    }
    private static void crossInstanceRollbackIsSerialized()throws Exception{
        Memory memory=new Memory();SearchHistory first=new SearchHistory(memory,A),second=new SearchHistory(memory,A);first.recordSubmitted("原关键词");
        memory.gateNext=true;memory.rejectCount=2;memory.entered=new CountDownLatch(1);memory.release=new CountDownLatch(1);
        CountDownLatch started=new CountDownLatch(1),finished=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
        Thread failing=new Thread(()->{try{first.recordSubmitted("失败关键词");error.set(new AssertionError("write must fail"));}catch(IllegalStateException expected){}catch(Throwable failure){error.set(failure);}},"HistoryFailingFixture");
        Thread following=new Thread(()->{started.countDown();try{second.recordSubmitted("随后成功关键词");}catch(Throwable failure){error.set(failure);}finally{finished.countDown();}},"HistoryFollowingFixture");
        failing.start();try{ok(memory.entered.await(2,TimeUnit.SECONDS),"first writer staged live map");following.start();ok(started.await(2,TimeUnit.SECONDS),"second instance attempts save");ok(!finished.await(100,TimeUnit.MILLISECONDS),"shared lock excludes writer during rollback");}
        finally{memory.release.countDown();failing.join(3000);if(following.getState()!=Thread.State.NEW)following.join(3000);}
        ok(!failing.isAlive()&&!following.isAlive()&&error.get()==null,"concurrent writers finish without deadlock");
        ok(first.get().equals(java.util.Arrays.asList("随后成功关键词","原关键词")),"successful second writer survives rollback without failed keyword");
    }
}
