package cn.ngapure.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real NgaStore with an isolated in-memory SharedPreferences. No credentials/Keystore/network. */
public final class LocalTopicStoreTest {
    private static int assertions;
    private static void ok(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    private static final class Memory implements SharedPreferences {
        final Map<String,Object> values=new HashMap<>();
        public Map<String,?> getAll(){return new HashMap<>(values);}
        public String getString(String key,String fallback){Object value=values.get(key);return value instanceof String?(String)value:fallback;}
        @SuppressWarnings("unchecked") public Set<String> getStringSet(String key,Set<String> fallback){Object value=values.get(key);return value instanceof Set?new HashSet<>((Set<String>)value):fallback;}
        public int getInt(String key,int fallback){return fallback;}public long getLong(String key,long fallback){return fallback;}
        public float getFloat(String key,float fallback){return fallback;}public boolean getBoolean(String key,boolean fallback){return fallback;}
        public boolean contains(String key){return values.containsKey(key);}
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public Editor edit(){return new Editor(){
            final Map<String,Object> added=new HashMap<>();final Set<String> removed=new HashSet<>();boolean clear;
            public Editor putString(String key,String value){if(value==null)return remove(key);added.put(key,value);return this;}
            public Editor putStringSet(String key,Set<String> value){added.put(key,new HashSet<>(value));return this;}
            public Editor putInt(String key,int value){added.put(key,value);return this;}public Editor putLong(String key,long value){added.put(key,value);return this;}
            public Editor putFloat(String key,float value){added.put(key,value);return this;}public Editor putBoolean(String key,boolean value){added.put(key,value);return this;}
            public Editor remove(String key){removed.add(key);added.remove(key);return this;}public Editor clear(){clear=true;return this;}
            public boolean commit(){apply();return true;}
            public void apply(){if(clear)values.clear();for(String key:removed)values.remove(key);values.putAll(added);}
        };}
    }
    private static final class LocalContext extends Context {
        final Memory memory=new Memory();
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            if(!"nga_local".equals(name))throw new AssertionError("Only topic settings may be accessed");return memory;
        }
    }
    private static NgaApi.Topic topic(String id,String recommendation) throws Exception {
        NgaApi.Topic topic=new NgaApi.Topic();topic.tid=id;topic.title="合成主题 "+id;topic.author="合成用户";
        topic.fid="7";topic.forum="合成版块";topic.time="10-19 00:00";topic.replies=21;
        topic.authorId="12345";topic.avatar="42.gif";if(recommendation!=null)topic.votes=VoteMetrics.from(new JSONObject().put("recommend",recommendation));return topic;
    }
    private static void identical(NgaApi.Topic expected,NgaApi.Topic actual){
        ok(expected.tid.equals(actual.tid)&&expected.title.equals(actual.title)&&expected.author.equals(actual.author),"topic identity and text survive a store recreation");
        ok(expected.fid.equals(actual.fid)&&expected.forum.equals(actual.forum)&&expected.time.equals(actual.time)&&expected.replies==actual.replies,"forum, time and reply metadata survive");
        ok(expected.authorId.equals(actual.authorId),"public author ID survives local history and saved-topic restoration");
        ok(actual.avatar.equals(AvatarSources.normalize(expected.avatar)),"avatar is preserved and normalized from official metadata");
        ok(expected.votes.likesKnown==actual.votes.likesKnown&&expected.votes.dislikesKnown==actual.votes.dislikesKnown&&expected.votes.scoreKnown==actual.votes.scoreKnown,"known/unknown counts preserve their original meaning");
        ok(expected.votes.likes==actual.votes.likes&&expected.votes.dislikes==actual.votes.dislikes&&expected.votes.score==actual.votes.score,"exact support, oppose and net values survive");
    }
    private static void roundTrip() throws Exception {
        LocalContext context=new LocalContext();NgaStore store=new NgaStore(context);NgaApi.Topic value=topic("123","0,9,2");
        store.recordHistory(value);identical(value,new NgaStore(context).history().get(0));
        store.toggleTopic(value);identical(value,new NgaStore(context).savedTopics().get(0));
        JSONObject encoded=new JSONArray(context.memory.getString("saved_topics","[]")).getJSONObject(0);
        ok(encoded.getJSONObject("votes").getBoolean("likes_known")&&encoded.getJSONObject("votes").getInt("likes")==9,"serialized snapshot explicitly distinguishes known support counts");
        store.toggleTopic(value);ok(store.savedTopics().isEmpty(),"existing save/remove behavior is preserved");
        value.votes=VoteMetrics.from(new JSONObject().put("recommend","0,12,5"));store.recordHistory(value);
        ok(store.history().size()==1&&store.history().get(0).votes.likes==12,"history deduplication keeps the latest metadata");
    }
    private static void allMetricKinds() throws Exception {
        for(String recommendation:new String[]{null,"0,0,0","-3","0","0,2147483647,2"}){
            LocalContext context=new LocalContext();NgaApi.Topic value=topic("123",recommendation);new NgaStore(context).recordHistory(value);
            identical(value,new NgaStore(context).history().get(0));
        }
        LocalContext context=new LocalContext();NgaApi.Topic value=topic("123",null);value.votes=null;new NgaStore(context).recordHistory(value);
        ok(!new NgaStore(context).history().get(0).votes.likesKnown,"null metric metadata restores as unknown");
        value.votes=new VoteMetrics();value.votes.likesKnown=true;value.votes.likes=-1;new NgaStore(context).recordHistory(value);
        ok(!new NgaStore(context).history().get(0).votes.likesKnown,"invalid negative support count cannot become a known local count");
    }
    private static void oldAndCorruptRecords() throws Exception {
        LocalContext context=new LocalContext();NgaStore store=new NgaStore(context);
        JSONObject old=new JSONObject().put("tid","123").put("title","旧版主题").put("replies",2);
        context.memory.values.put("history",new JSONArray().put(old).toString());NgaApi.Topic value=store.history().get(0);
        ok(value.title.equals("旧版主题")&&value.replies==2,"older snapshots remain readable");
        ok(value.avatar.isEmpty()&&!value.votes.likesKnown&&!value.votes.dislikesKnown&&!value.votes.scoreKnown,"old missing metadata stays absent instead of displaying fake zeros");
        for(Object badCount:new Object[]{-1,2147483648L,1.5,"9",JSONObject.NULL}){
            JSONObject corrupt=new JSONObject(old.toString()).put("avatar","https://attacker.invalid/avatar.png")
                    .put("votes",new JSONObject().put("likes_known",true).put("likes",badCount));
            context.memory.values.put("history",new JSONArray().put(corrupt).toString());value=store.history().get(0);
            ok(value.avatar.isEmpty()&&!value.votes.likesKnown,"untrusted avatar or malformed stored count does not become visible metadata");
        }
        for(Object marker:new Object[]{false,"true",1,JSONObject.NULL}){
            JSONObject corrupt=new JSONObject(old.toString()).put("votes",new JSONObject().put("likes_known",marker).put("likes",9));
            context.memory.values.put("history",new JSONArray().put(corrupt).toString());
            ok(!store.history().get(0).votes.likesKnown,"only an explicit boolean known flag can restore a vote count");
        }
        JSONObject hidden=new JSONObject(old.toString()).put("votes",new JSONObject().put("likes",9).put("score",8));
        context.memory.values.put("history",new JSONArray().put(hidden).toString());value=store.history().get(0);
        ok(!value.votes.likesKnown&&!value.votes.scoreKnown,"stray numbers without known flags stay unknown");
        context.memory.values.put("history","not JSON");ok(store.history().isEmpty(),"malformed local snapshots keep existing empty recovery behavior");
    }
    public static void main(String[] args) throws Exception {
        roundTrip();allMetricKinds();oldAndCorruptRecords();
        System.out.println("LocalTopicStore: "+assertions+" assertions PASS; real network requests 0; credentials/Keystore accesses 0");
    }
}
