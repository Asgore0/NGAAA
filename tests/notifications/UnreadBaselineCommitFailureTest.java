package cn.ngapure.app;

import android.content.SharedPreferences;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Actual baseline transaction under Android-style memory-before-disk failures. No notifications. */
public final class UnreadBaselineCommitFailureTest {
    private static int checks;
    private static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static final class Memory implements SharedPreferences {
        final Map<String,Object> values=new HashMap<>(),disk=new HashMap<>();int rejectCount;
        public Map<String,?> getAll(){return new HashMap<>(values);}
        public String getString(String k,String fallback){Object v=values.get(k);return v==null?fallback:(String)v;}
        public Set<String> getStringSet(String k,Set<String> fallback){return fallback;}
        public int getInt(String k,int fallback){Object v=values.get(k);return v==null?fallback:(Integer)v;}
        public long getLong(String k,long fallback){Object v=values.get(k);return v==null?fallback:(Long)v;}
        public float getFloat(String k,float fallback){return fallback;}
        public boolean getBoolean(String k,boolean fallback){return fallback;}
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
    private static UnreadNotificationPolicy.Snapshot state(int count){
        String token=UnreadNotificationPolicy.reminderEvent(1,"reply","70001","91001","92001",1760000000L);
        return new UnreadNotificationPolicy.Snapshot(count,0,count==0?Collections.emptySet():Collections.singleton(token),Collections.emptySet());
    }
    private static UnreadNotificationPolicy.Snapshot read(Memory memory,String scope){return UnreadNotificationPolicy.decode(memory.getString(scope+".snapshot",""));}
    public static void main(String[] args){
        String scope=UnreadNotificationPolicy.scope("bbs.nga.cn","123"),other=UnreadNotificationPolicy.scope("bbs.nga.cn","456");
        UnreadNotificationPolicy.Snapshot empty=state(0),unread=state(1);Memory memory=new Memory();
        ok(NativeNotifications.saveBaseline(memory,scope,empty,1000),"initial zero observation commits");
        memory.edit().putString(other+".snapshot","other-scope-untouched").putString("unrelated","unchanged").commit();
        Map<String,Object> before=new HashMap<>(memory.values);memory.rejectCount=2;
        ok(UnreadNotificationPolicy.hasNew(read(memory,scope),unread),"first unread event is eligible before saving");
        ok(!NativeNotifications.saveBaseline(memory,scope,unread,2000),"failed commit defers alert and requests retry");
        ok(memory.values.equals(before),"both failed writes restore exact prior snapshot, status, timestamp and other keys");
        ok(UnreadNotificationPolicy.hasNew(read(memory,scope),unread),"same event remains new after a failed baseline commit");
        memory.edit().putString("unrelated","successful-flush").commit();
        ok(read(memory.restart(),scope).reminders==0,"later unrelated write cannot durably advance a rejected observation");
        ok(NativeNotifications.saveBaseline(memory,scope,unread,3000),"later successful observation can be acknowledged");
        ok(!UnreadNotificationPolicy.hasNew(read(memory,scope),unread),"acknowledged identical event is no longer new");
        ok(memory.getLong(scope+".checkedAt",0)==3000,"only successful observation updates checked time");
        before=new HashMap<>(memory.values);memory.rejectCount=1;
        ok(!NativeNotifications.saveBaseline(memory,scope,empty,4000)&&memory.values.equals(before),"failed zero-unread observation cannot retire a previous active event");
        ok(NativeNotifications.saveBaseline(memory,scope,empty,5000)&&read(memory,scope).reminders==0,"successful zero-unread observation retires the old event");

        Memory first=new Memory();first.rejectCount=2;
        ok(!NativeNotifications.saveBaseline(first,scope,unread,1000)&&first.values.isEmpty(),"first failed observation restores absent keys rather than creating a fake baseline");
        ok(UnreadNotificationPolicy.hasNew(read(first,scope),unread),"new event remains eligible with no prior baseline");

        Memory migration=new Memory();migration.edit().putInt(scope+".reminders",1).putInt(scope+".conversations",2)
                .putString(scope+".status","old-status").putLong(scope+".checkedAt",6000).putBoolean("enabled",true).commit();
        before=new HashMap<>(migration.values);migration.rejectCount=2;
        ok(!NativeNotifications.saveBaseline(migration,scope,unread,7000)&&migration.values.equals(before),"failed migration preserves both legacy counts and all old recovery fields");
        ok(NativeNotifications.saveBaseline(migration,scope,unread,8000),"migration succeeds after a new actual commit");
        ok(!migration.contains(scope+".reminders")&&!migration.contains(scope+".conversations")&&read(migration,scope).reminders==1,"only successful migration replaces old counters with a snapshot");
        ok(Boolean.TRUE.equals(migration.values.get("enabled")),"account baseline transaction never changes opt-in setting");

        Memory types=new Memory();Set<String> held=new HashSet<>();held.add("corrupt-but-preserved");
        types.values.put(scope+".snapshot",held);types.values.put(scope+".status",Float.valueOf(1.5f));types.values.put(scope+".checkedAt",Boolean.FALSE);types.values.put(scope+".reminders",Long.valueOf(42));
        before=new HashMap<>(types.values);types.rejectCount=2;
        ok(!NativeNotifications.saveBaseline(types,scope,unread,9000)&&types.values.equals(before),"failed transaction does not silently delete unusual preexisting recovery value types");
        System.out.println("Unread baseline Android-style commit failure checks passed: "+checks);
    }
}
