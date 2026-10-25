package cn.ngapure.app;

import android.content.SharedPreferences;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Actual production journal and recommendation workflow, simulated Android disk failures. */
public final class JournalCommitFailureTest {
    private static int checks;
    private static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static final class Memory implements SharedPreferences {
        final Map<String,Object> values=new HashMap<>(),disk=new HashMap<>();int rejectCount;
        public Map<String,?> getAll(){return new HashMap<>(values);}
        public String getString(String k,String fallback){Object v=values.get(k);return v==null?fallback:(String)v;}
        public Set<String> getStringSet(String k,Set<String> fallback){return fallback;}
        public int getInt(String k,int fallback){return fallback;}
        public long getLong(String k,long fallback){return fallback;}
        public float getFloat(String k,float fallback){return fallback;}
        public boolean getBoolean(String k,boolean fallback){Object v=values.get(k);return v==null?fallback:(Boolean)v;}
        public boolean contains(String k){return values.containsKey(k);}
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
        public Editor edit(){return new Editor(){
            final Map<String,Object> added=new HashMap<>();final Set<String> removed=new HashSet<>();boolean clear;
            public Editor putString(String k,String v){added.put(k,v);return this;}
            public Editor putStringSet(String k,Set<String> v){added.put(k,v);return this;}
            public Editor putInt(String k,int v){added.put(k,v);return this;}
            public Editor putLong(String k,long v){added.put(k,v);return this;}
            public Editor putFloat(String k,float v){added.put(k,v);return this;}
            public Editor putBoolean(String k,boolean v){added.put(k,v);return this;}
            public Editor remove(String k){removed.add(k);return this;}
            public Editor clear(){clear=true;return this;}
            private void memory(){if(clear)values.clear();for(String k:removed)values.remove(k);values.putAll(added);}
            public boolean commit(){memory();if(rejectCount>0){rejectCount--;return false;}disk.clear();disk.putAll(values);return true;}
            public void apply(){memory();disk.clear();disk.putAll(values);}
        };}
        Memory restart(){Memory result=new Memory();result.values.putAll(disk);result.disk.putAll(disk);return result;}
    }
    private static NativeThreadActions.Journal journal(Memory memory)throws Exception{
        Class<?> type=Class.forName("cn.ngapure.app.NativeThreadActions$PersistentJournal");Constructor<?> constructor=type.getDeclaredConstructor(SharedPreferences.class);constructor.setAccessible(true);return (NativeThreadActions.Journal)constructor.newInstance(memory);
    }
    private static final class Transport implements NativeThreadActions.Transport {
        int requests;boolean unknown;Runnable after;
        @Override public NativeThreadActions.Response execute(NativeThreadActions.Request request)throws IOException{
            requests++;if(after!=null)after.run();if(unknown)throw new IOException("Synthetic unknown response, no socket");
            return new NativeThreadActions.Response(200,"{\"data\":[\"Synthetic acknowledged\",1,4]}".getBytes(StandardCharsets.UTF_8),"application/json; charset=UTF-8");
        }
    }
    private interface Work{void run()throws Exception;}
    private static void fails(NativeThreadActions.Kind kind,Work work,String label)throws Exception{
        try{work.run();throw new AssertionError(label);}catch(NativeThreadActions.ActionException error){ok(error.kind==kind,label+": "+error.kind);}
    }
    public static void main(String[] args)throws Exception{
        Memory raw=new Memory();NativeThreadActions.Journal real=journal(raw);raw.rejectCount=2;
        ok(!real.put("synthetic-a"),"failed preparation reports no saved permission");
        ok(!real.contains("synthetic-a"),"failed journal put cannot invent an unknown prior request in Android's live map");
        raw.edit().putString("unrelated","flush").commit();
        ok(!journal(raw.restart()).contains("synthetic-a"),"later successful flush cannot persist the unstarted action marker");
        ok(real.put("synthetic-a")&&real.contains("synthetic-a"),"later successful explicit preparation stores a genuine marker");
        Map<String,Object> before=new HashMap<>(raw.values);raw.rejectCount=2;
        ok(!real.remove("synthetic-a")&&raw.values.equals(before),"failed settlement preserves exact pending key and unrelated fields");
        ok(real.remove("synthetic-a")&&!real.contains("synthetic-a"),"successful settlement can retire its exact marker");

        Memory blocked=new Memory();Transport notSent=new Transport();NativeThreadActions api=new NativeThreadActions(new NgaStore(),notSent,journal(blocked));blocked.rejectCount=2;
        fails(NativeThreadActions.Kind.VALIDATION,()->api.recommend("91001","92001",1),"failed journal save rejects recommendation before dispatch");
        ok(notSent.requests==0&&blocked.values.isEmpty(),"no synthetic transport request and no invented pending entry");
        ok(api.recommend("91001","92001",1).selected==1&&notSent.requests==1&&blocked.values.isEmpty(),"new explicit successful attempt dispatches exactly once and settles");

        Memory uncertain=new Memory();Transport lost=new Transport();lost.unknown=true;NativeThreadActions lostApi=new NativeThreadActions(new NgaStore(),lost,journal(uncertain));
        fails(NativeThreadActions.Kind.OUTCOME_UNKNOWN,()->lostApi.recommend("91002","92002",1),"actual dispatched unknown outcome retains its marker");
        ok(lost.requests==1&&uncertain.values.size()==1,"one unknown dispatch has one persisted recovery guard");
        fails(NativeThreadActions.Kind.OUTCOME_UNKNOWN,()->new NativeThreadActions(new NgaStore(),lost,journal(uncertain)).recommend("91002","92002",1),"replacement adapter cannot repeat a genuine unknown request");
        ok(lost.requests==1,"genuine unknown remains blocked without a second transport call");

        Memory settlement=new Memory();Transport acknowledged=new Transport();acknowledged.after=()->settlement.rejectCount=2;NativeThreadActions confirmed=new NativeThreadActions(new NgaStore(),acknowledged,journal(settlement));
        fails(NativeThreadActions.Kind.OUTCOME_UNKNOWN,()->confirmed.recommend("91003","92003",1),"failed local acknowledgement cleanup stays guarded");
        ok(acknowledged.requests==1&&settlement.values.size()==1,"failed cleanup cannot silently unlock a reaction toggle that the UI reported uncertain");
        fails(NativeThreadActions.Kind.OUTCOME_UNKNOWN,()->confirmed.recommend("91003","92003",1),"same uncertain reaction cannot inadvertently toggle back");
        ok(acknowledged.requests==1,"failed cleanup never triggers a hidden duplicate request");

        Memory types=new Memory();types.values.put("synthetic-corrupt",Long.valueOf(42));types.rejectCount=2;
        ok(!journal(types).remove("synthetic-corrupt")&&Long.valueOf(42).equals(types.values.get("synthetic-corrupt")),"failed settlement preserves an unusual old marker value rather than erasing evidence");
        System.out.println("Thread action journal Android-style commit failure checks passed: "+checks);
    }
}
