package cn.ngapure.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Exact-session filter fixtures; Android Keystore timings live in the separate own-UID probe. */
public final class NativeUserFilterTest {
    private static final String HOST="bbs.nga.cn",OTHER="ngabbs.com";
    private static int checks;
    private static void ok(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    private static final class Memory implements SharedPreferences {
        final Map<String,Object> values=new HashMap<>();int cookieReads,writes;
        public Map<String,?> getAll(){return new HashMap<>(values);}
        public String getString(String key,String fallback){if(key.equals("cookies_encrypted"))cookieReads++;Object value=values.get(key);return value instanceof String?(String)value:fallback;}
        public Set<String> getStringSet(String key,Set<String> fallback){return fallback;}
        public int getInt(String key,int fallback){return fallback;}public long getLong(String key,long fallback){return fallback;}
        public float getFloat(String key,float fallback){return fallback;}public boolean getBoolean(String key,boolean fallback){return fallback;}
        public boolean contains(String key){return values.containsKey(key);}
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public Editor edit(){writes++;throw new AssertionError("FILTER_MUST_NOT_WRITE");}
    }
    private static final class LocalContext extends Context {
        final Map<String,Memory> stores=new HashMap<>();Runnable afterLocalRead;
        public SharedPreferences getSharedPreferences(String name,int mode){
            if(name.equals("nga_native_user_blocks")&&afterLocalRead!=null){Runnable action=afterLocalRead;afterLocalRead=null;action.run();}
            return memory(name);
        }
        Memory memory(String name){return stores.computeIfAbsent(name,key->new Memory());}
    }
    private static NgaStore.CheckInSession session(String host,String uid){return new NgaStore.CheckInSession(host,uid.isEmpty()?"":"ngaPassportUid="+uid+"; ngaPassportCid=synthetic-filter-only",uid);}
    private static void local(LocalContext context,String host,String uid,String blocked)throws Exception{
        context.memory("nga_native_user_blocks").values.put(NativeUserBlockStore.key(host,uid),blocked.isEmpty()?"{}":new JSONObject().put(blocked,"synthetic user").toString());
    }
    private static void cloud(LocalContext context,String host,String uid,String blocked,String keyword)throws Exception{
        context.memory("nga_native_cloud_blocks").values.put(NativeUserBlockStore.key(host,uid),new JSONObject().put("schema",1).put("document","1\n"+keyword+"\n"+blocked).toString());
    }
    private static NativeUserActions.UserFilter capture(LocalContext context,String host,String uid){return NativeUserActions.filter(context,session(host,uid));}
    private static long diagnostic(String name){
        for(String part:NgaStore.sessionReadDiagnostics().split(" "))if(part.startsWith(name+"="))return Long.parseLong(part.substring(name.length()+1));
        throw new AssertionError("Missing numeric session diagnostic");
    }
    public static void main(String[] args)throws Exception{
        LocalContext context=new LocalContext();local(context,HOST,"70001","71001");cloud(context,HOST,"70001","72001","alpha");
        local(context,HOST,"70002","71002");cloud(context,HOST,"70002","72002","beta");
        local(context,OTHER,"70001","71003");cloud(context,OTHER,"70001","72003","gamma");
        NativeUserActions.UserFilter a=capture(context,HOST,"70001");
        ok(a.blocks("71001","","safe")&&a.blocks("72001","","safe")&&a.blocks("79999","","contains alpha"),"local and cloud rules merged");
        ok(!a.blocks("71002","","safe")&&!a.blocks("72002","","safe")&&!a.blocks("79999","","beta"),"other account rules excluded");
        ok(!a.blocks("71003","","safe")&&!a.blocks("72003","","safe"),"other host rules excluded");
        NativeUserActions.UserFilter b=capture(context,HOST,"70002");
        ok(b.blocks("71002","","safe")&&b.blocks("72002","","safe")&&!b.blocks("71001","","safe"),"next capture sees account switch");
        NativeUserActions.UserFilter other=capture(context,OTHER,"70001");
        ok(other.blocks("71003","","safe")&&other.blocks("72003","","safe")&&!other.blocks("71001","","safe"),"next capture sees host switch");
        context.afterLocalRead=()->context.memory("nga_local").values.put("host",OTHER);
        NativeUserActions.UserFilter pinned=capture(context,HOST,"70001");
        ok(pinned.blocks("71001","","safe")&&pinned.blocks("72001","","safe")&&!pinned.blocks("72003","","safe"),"both stores use captured host despite intervening preference mutation");
        local(context,HOST,"70001","");cloud(context,HOST,"70001","72009","delta");
        NativeUserActions.UserFilter changed=capture(context,HOST,"70001");
        ok(!changed.blocks("71001","","safe")&&!changed.blocks("72001","","safe")&&!changed.blocks("79999","","alpha"),"removed rules disappear next capture");
        ok(changed.blocks("72009","","safe")&&changed.blocks("79999","","delta"),"new cloud rules appear next capture");
        ok(a.blocks("71001","","safe")&&a.blocks("72001","","safe"),"previous render owns independent immutable rule sets");
        ok(!changed.sameAs(a)&&changed.sameAs(capture(context,HOST,"70001")),"sameAs reflects fresh rule contents");
        String key=NativeUserBlockStore.key(HOST,"70001");context.memory("nga_native_user_blocks").values.put(key,"{broken");
        ok(capture(context,HOST,"70001").blocks("72009","","safe"),"damaged local rules keep valid cloud rules");
        context.memory("nga_native_cloud_blocks").values.put(key,"{broken");
        ok(!capture(context,HOST,"70001").blocks("72009","","safe"),"damaged caches never hide unrelated content");
        NativeUserActions.UserFilter anonymous=capture(context,HOST,"");
        ok(!anonymous.blocks("71002","","beta"),"logout has no retained account rules");
        LocalContext direct=new LocalContext();long reads=diagnostic("readCalls"),decodes=diagnostic("decodeCalls");NativeUserActions.filter(direct);
        ok(diagnostic("readCalls")==reads+1,"public filter captures credentials only once despite priming ownership");
        ok(diagnostic("decodeCalls")==decodes,"anonymous filter performs no credential decryption");
        ok(direct.stores.size()==1,"anonymous capture avoids unrelated block preference stores");
        for(Memory memory:context.stores.values())ok(memory.writes==0,"filter does not persist preferences");
        System.out.println("NativeUserFilterTest: "+checks+" checks passed; synthetic in-memory preferences only.");
    }
}
