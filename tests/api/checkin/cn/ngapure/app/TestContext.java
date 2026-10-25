package cn.ngapure.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** One isolated synthetic preferences instance, shared by the API's and test's store facades. */
final class TestContext extends Context {
    final Memory preferences=new Memory();
    volatile String host="bbs.nga.cn";
    Runnable afterCapture,afterRecord;

    private static final class Memory implements SharedPreferences {
        final Map<String,Object> values=new HashMap<>();
        public synchronized Map<String,?> getAll(){return new HashMap<>(values);}
        public synchronized String getString(String key,String fallback){Object value=values.get(key);return value==null?fallback:(String)value;}
        public Set<String> getStringSet(String key,Set<String> fallback){return fallback;}
        public int getInt(String key,int fallback){return fallback;}public long getLong(String key,long fallback){return fallback;}
        public float getFloat(String key,float fallback){return fallback;}public boolean getBoolean(String key,boolean fallback){return fallback;}
        public synchronized boolean contains(String key){return values.containsKey(key);}
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public Editor edit(){return new Editor(){
            final Map<String,Object> added=new HashMap<>();final Set<String> removed=new HashSet<>();boolean clear;
            public Editor putString(String key,String value){if(value==null)return remove(key);added.put(key,value);removed.remove(key);return this;}
            public Editor putStringSet(String key,Set<String> value){added.put(key,value);return this;}
            public Editor putInt(String key,int value){added.put(key,value);return this;}
            public Editor putLong(String key,long value){added.put(key,value);return this;}
            public Editor putFloat(String key,float value){added.put(key,value);return this;}
            public Editor putBoolean(String key,boolean value){added.put(key,value);return this;}
            public Editor remove(String key){removed.add(key);added.remove(key);return this;}public Editor clear(){clear=true;return this;}
            public boolean commit(){apply();return true;}
            public void apply(){synchronized(Memory.this){if(clear)values.clear();for(String key:removed)values.remove(key);values.putAll(added);}}
        };}
    }
}
