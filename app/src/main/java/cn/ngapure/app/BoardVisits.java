package cn.ngapure.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONTokener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Device-local successfully opened boards, isolated by the caller's host/account digest. */
public final class BoardVisits {
    public static final int MAX_SCOPES=6,MAX_ENTRIES=30,MAX_NAME_CHARS=120,MAX_ICON_CHARS=1024;
    static final String PREFERENCES="nga_native_board_visits",KEY="visits_v1";
    static final int MAX_JSON_CHARS=384*1024;
    private static final long MAX_TIME=253402300799999L;
    private static final Object LOCK=new Object();
    private final SharedPreferences preferences;
    private final Clock clock;

    interface Clock { long now(); }
    public static final class Entry {
        public final String key,name,parentFid,icon;
        public final long updatedAt;
        private Entry(String key,String name,String parent,String icon,long time){this.key=key;this.name=name;parentFid=parent;this.icon=icon;updatedAt=time;}
    }
    private static final class Scope {
        final String value;final List<Entry> entries;
        Scope(String value,List<Entry> entries){this.value=value;this.entries=entries;}
    }
    private static final class State {
        final Object previous;final boolean existed;final List<Scope> scopes;
        State(Object previous,boolean existed,List<Scope> scopes){this.previous=previous;this.existed=existed;this.scopes=scopes;}
    }
    public BoardVisits(Context context){this(preferences(context),System::currentTimeMillis);}
    BoardVisits(SharedPreferences preferences,Clock clock){
        if(preferences==null||clock==null)throw new IllegalArgumentException("缺少最近访问存储配置");
        this.preferences=preferences;this.clock=clock;
    }
    private static SharedPreferences preferences(Context context){
        if(context==null)throw new IllegalArgumentException("最近访问环境无效");
        return context.getApplicationContext().getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE);
    }
    public List<Entry> get(String scope){
        requireScope(scope);
        synchronized(LOCK){for(Scope value:read().scopes)if(value.value.equals(scope))return Collections.unmodifiableList(new ArrayList<>(value.entries));return Collections.emptyList();}
    }
    /** Caller must opt in to history recording and invoke only after a board response succeeds. */
    public boolean record(String scope,String key,String name,String parentFid,String icon){
        requireScope(scope);String parent=parentFid==null?"":parentFid;
        if(!validRoute(key,parent)||!validName(name))return false;
        String normalizedName=name.trim(),normalizedIcon=normalizeIcon(icon);
        final long now;
        try{now=clock.now();}catch(RuntimeException unavailable){throw new IllegalStateException("最近访问时间无法读取，请重试");}
        if(now<1||now>MAX_TIME)return false;
        synchronized(LOCK){
            State state=read();List<Entry> entries=new ArrayList<>();
            for(int i=0;i<state.scopes.size();i++)if(state.scopes.get(i).value.equals(scope)){entries.addAll(state.scopes.remove(i).entries);break;}
            entries.removeIf(entry->entry.key.equals(key));entries.add(0,new Entry(key,normalizedName,parent,normalizedIcon,now));
            while(entries.size()>MAX_ENTRIES)entries.remove(entries.size()-1);
            state.scopes.add(0,new Scope(scope,entries));while(state.scopes.size()>MAX_SCOPES)state.scopes.remove(state.scopes.size()-1);
            write(state);return true;
        }
    }
    /** Removes only this scope. An unreadable shared index is preserved rather than erased. */
    public void clear(String scope){
        requireScope(scope);
        synchronized(LOCK){State state=read();if(state.scopes.removeIf(value->value.value.equals(scope)))write(state);}
    }
    private static void requireScope(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("最近访问账号范围无效");}
    private static boolean validRoute(String key,String parent){
        if(key==null||key.length()>23||parent==null||parent.length()>13||!parent.isEmpty()&&!ForumRoute.isForumId(parent))return false;
        try{ForumRoute route=ForumRoute.fromKey(key);return route.key.equals(key)&&(parent.isEmpty()||!parent.equals(route.fid));}
        catch(IllegalArgumentException invalid){return false;}
    }
    private static boolean validName(String name){
        if(name==null||name.length()>MAX_NAME_CHARS)return false;
        String value=name.trim();if(value.isEmpty())return false;
        for(int i=0;i<name.length();i++){
            char c=name.charAt(i);if(Character.isISOControl(c)||Character.getType(c)==Character.FORMAT)return false;
            if(Character.isHighSurrogate(c)){if(i+1>=name.length()||!Character.isLowSurrogate(name.charAt(++i)))return false;}
            else if(Character.isLowSurrogate(c))return false;
        }
        return true;
    }
    private static String normalizeIcon(String value){
        if(value==null||value.length()>2048)return "";
        // Board artwork is one address, never an avatar metadata document to recursively parse.
        String source=value.trim();if(source.startsWith("[")||source.startsWith("{"))return "";
        String icon=AvatarSources.normalize(source);return icon.length()<=MAX_ICON_CHARS?icon:"";
    }
    private State read(){
        try{
            Map<String,?> all=preferences.getAll();Object raw=all.get(KEY);List<Scope> scopes=new ArrayList<>();
            if(!all.containsKey(KEY))return new State(null,false,scopes);
            if(!(raw instanceof String)||((String)raw).isEmpty()||((String)raw).length()>MAX_JSON_CHARS||!boundedJson((String)raw))throw corrupt();
            JSONTokener parser=new JSONTokener((String)raw);Object decoded=parser.nextValue();
            if(!(decoded instanceof JSONArray)||parser.nextClean()!=0)throw corrupt();JSONArray root=(JSONArray)decoded;
            if(root.length()!=2||!(root.opt(0) instanceof Integer)||((Integer)root.opt(0))!=1||!(root.opt(1) instanceof JSONArray))throw corrupt();
            JSONArray groups=(JSONArray)root.opt(1);if(groups.length()>MAX_SCOPES)throw corrupt();Set<String> seenScopes=new HashSet<>();
            for(int i=0;i<groups.length();i++){
                Object value=groups.opt(i);if(!(value instanceof JSONArray))throw corrupt();JSONArray group=(JSONArray)value;
                if(group.length()!=2||!(group.opt(0) instanceof String)||!(group.opt(1) instanceof JSONArray))throw corrupt();String scope=(String)group.opt(0);
                requireScope(scope);if(!seenScopes.add(scope))throw corrupt();JSONArray rows=(JSONArray)group.opt(1);if(rows.length()<1||rows.length()>MAX_ENTRIES)throw corrupt();
                List<Entry> entries=new ArrayList<>();Set<String> keys=new HashSet<>();
                for(int j=0;j<rows.length();j++){
                    Object item=rows.opt(j);if(!(item instanceof JSONArray))throw corrupt();JSONArray row=(JSONArray)item;
                    if(row.length()!=5||!(row.opt(0) instanceof String)||!(row.opt(1) instanceof String)||!(row.opt(2) instanceof String)||!(row.opt(3) instanceof String))throw corrupt();
                    String key=(String)row.opt(0),name=(String)row.opt(1),parent=(String)row.opt(2),icon=(String)row.opt(3);Object time=row.opt(4);
                    if(!validRoute(key,parent)||!validName(name)||!name.equals(name.trim())||icon.length()>MAX_ICON_CHARS||!icon.equals(normalizeIcon(icon))||!keys.add(key)
                            ||!(time instanceof Integer||time instanceof Long))throw corrupt();long updatedAt=((Number)time).longValue();if(updatedAt<1||updatedAt>MAX_TIME)throw corrupt();
                    entries.add(new Entry(key,name,parent,icon,updatedAt));
                }
                scopes.add(new Scope(scope,entries));
            }
            return new State(raw,true,scopes);
        }catch(IllegalStateException unavailable){throw unavailable;}
        catch(Exception malformed){throw corrupt();}
    }
    private void write(State state){
        final String encoded;
        try{
            if(state.scopes.isEmpty())encoded=null;
            else{
                JSONArray groups=new JSONArray();
                for(Scope scope:state.scopes){JSONArray entries=new JSONArray();for(Entry entry:scope.entries)entries.put(new JSONArray().put(entry.key).put(entry.name).put(entry.parentFid).put(entry.icon).put(entry.updatedAt));groups.put(new JSONArray().put(scope.value).put(entries));}
                encoded=new JSONArray().put(1).put(groups).toString();if(encoded==null||encoded.length()>MAX_JSON_CHARS)throw new IllegalStateException("最近访问内容过大，无法保存");
            }
        }catch(IllegalStateException tooLarge){throw tooLarge;}
        catch(Exception unavailable){throw new IllegalStateException("最近访问无法保存，请重试");}
        try{SharedPreferences.Editor editor=preferences.edit();if(encoded==null)editor.remove(KEY);else editor.putString(KEY,encoded);if(editor.commit())return;}
        catch(RuntimeException failed){restore(state);throw saveFailure();}
        restore(state);throw saveFailure();
    }
    @SuppressWarnings("unchecked")
    private void restore(State state){
        try{
            SharedPreferences.Editor editor=preferences.edit();Object previous=state.previous;
            if(!state.existed)editor.remove(KEY);
            else if(previous instanceof String)editor.putString(KEY,(String)previous);
            else if(previous instanceof Integer)editor.putInt(KEY,(Integer)previous);
            else if(previous instanceof Long)editor.putLong(KEY,(Long)previous);
            else if(previous instanceof Float)editor.putFloat(KEY,(Float)previous);
            else if(previous instanceof Boolean)editor.putBoolean(KEY,(Boolean)previous);
            else if(previous instanceof Set)editor.putStringSet(KEY,new HashSet<>((Set<String>)previous));
            else editor.remove(KEY);
            // Android restores its live map even if this second disk commit also fails.
            editor.commit();
        }catch(RuntimeException unavailable){/* Never report the failed new visit as successfully saved. */}
    }
    private static IllegalStateException corrupt(){return new IllegalStateException("最近访问无法读取，原有记录已保留");}
    private static IllegalStateException saveFailure(){return new IllegalStateException("最近访问保存失败，请重试");}
    /** The array-only schema permits no comments, unquoted values, objects or deep nesting. */
    private static boolean boundedJson(String value){
        int depth=0;boolean string=false,escaped=false;
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(string){
                if(escaped){escaped=false;if(c=='u'){if(i+4>=value.length())return false;for(int j=1;j<=4;j++)if(Character.digit(value.charAt(i+j),16)<0)return false;i+=4;}else if("\"\\/bfnrt".indexOf(c)<0)return false;}
                else if(c=='\\')escaped=true;else if(c=='"')string=false;else if(c<0x20)return false;
            }else if(c=='"')string=true;
            else if(c=='['){if(++depth>5)return false;}
            else if(c==']'){if(--depth<0)return false;}
            else if(c==','||c==' '||c=='\n'||c=='\r'||c=='\t'){/* JSON separators. */}
            else{
                int start=i;while(i<value.length()&&"[],\" \t\n\r".indexOf(value.charAt(i))<0){if(i-start>=32)return false;i++;}
                String token=value.substring(start,i);i--;if(!token.matches("-?(?:0|[1-9][0-9]*)"))return false;
            }
        }
        return depth==0&&!string&&!escaped;
    }
}
