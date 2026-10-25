package cn.ngapure.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Local submitted keyword history. The caller supplies the current host/account content digest. */
public final class SearchHistory {
    public static final int MAX_ITEMS=12,MAX_KEYWORD=100;
    private static final int MAX_STORE_CHARS=16384;
    private static final Object LOCK=new Object();
    private static final Pattern URL=Pattern.compile("(?i)(?:[a-z][a-z0-9+.-]*://|www\\.|(?:bbs\\.nga\\.cn|ngabbs\\.com|nga\\.178\\.com)(?:[/?#:]|$))");
    private static final Pattern CREDENTIAL=Pattern.compile("(?i)(?:(?:^|[^a-z0-9_])(?:cookie|authorization|access[_ -]?token|refresh[_ -]?token|auth[_ -]?token|ngapassport(?:uid|cid)?|uid|cid|password|passwd|pwd|用户名|账号|密码|验证码)[\\s\"']*[:：=]|\\bbearer\\s+[a-z0-9._~+/-]+=*)");
    private final SharedPreferences preferences;
    private final String key;

    public SearchHistory(Context context,String contentScope){this(preferences(context),contentScope);}
    SearchHistory(SharedPreferences preferences,String contentScope){
        if(preferences==null||contentScope==null||!contentScope.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("搜索历史账号范围无效");
        this.preferences=preferences;key="history:"+contentScope;
    }
    private static SharedPreferences preferences(Context context){
        if(context==null)throw new IllegalArgumentException("搜索历史环境无效");
        return context.getApplicationContext().getSharedPreferences("nga_native_search_history",Context.MODE_PRIVATE);
    }
    public List<String> get(){synchronized(LOCK){return Collections.unmodifiableList(read());}}
    /** Call only after a user submits a keyword search; URL/navigation input is never recorded. */
    public boolean recordSubmitted(String keyword){
        String value=validKeyword(keyword);if(value==null)return false;
        synchronized(LOCK){List<String> values=read();if(!values.isEmpty()&&values.get(0).equals(value))return true;
            values.removeIf(old->old.equalsIgnoreCase(value));values.add(0,value);
            while(values.size()>MAX_ITEMS)values.remove(values.size()-1);persist(values);return true;}
    }
    public boolean remove(String keyword){
        String value=validKeyword(keyword);if(value==null)return false;
        synchronized(LOCK){List<String> values=read();if(!values.removeIf(old->old.equalsIgnoreCase(value)))return false;persist(values);return true;}
    }
    /** Explicit clear is also available for an unreadable entry; other scopes remain untouched. */
    public void clear(){synchronized(LOCK){write(null);}}
    public static boolean acceptsKeyword(String keyword){return validKeyword(keyword)!=null;}

    private static String validKeyword(String keyword){
        if(keyword==null||keyword.length()>MAX_KEYWORD+32)return null;
        for(int i=0;i<keyword.length();i++){
            char c=keyword.charAt(i);
            if(Character.isISOControl(c)||Character.getType(c)==Character.FORMAT)return null;
            if(Character.isHighSurrogate(c)){if(i+1>=keyword.length()||!Character.isLowSurrogate(keyword.charAt(++i)))return null;}
            else if(Character.isLowSurrogate(c))return null;
        }
        String value=keyword.trim();
        if(value.isEmpty()||value.length()>MAX_KEYWORD||URL.matcher(value).find()||CREDENTIAL.matcher(value).find())return null;
        return value;
    }
    private List<String> read(){
        try{
            Object raw=preferences.getAll().get(key);List<String> result=new ArrayList<>();if(raw==null)return result;
            if(!(raw instanceof String)||((String)raw).length()>MAX_STORE_CHARS)throw corrupt();
            JSONObject data=new JSONObject((String)raw);Object version=data.opt("version");Object items=data.opt("items");
            if(data.length()!=2||!(version instanceof Integer)||((Integer)version)!=1||!(items instanceof JSONArray))throw corrupt();
            JSONArray values=(JSONArray)items;if(values.length()>MAX_ITEMS)throw corrupt();
            for(int i=0;i<values.length();i++){
                Object item=values.opt(i);if(!(item instanceof String))throw corrupt();String value=(String)item;
                if(!value.equals(validKeyword(value)))throw corrupt();for(String old:result)if(old.equalsIgnoreCase(value))throw corrupt();result.add(value);
            }
            return result;
        }catch(IllegalStateException failure){throw failure;}
        catch(Exception invalid){throw corrupt();}
    }
    private void persist(List<String> values){
        try{JSONObject data=new JSONObject();data.put("version",1);JSONArray items=new JSONArray();for(String value:values)items.put(value);data.put("items",items);write(data.toString());}
        catch(org.json.JSONException invalid){throw new IllegalStateException("搜索历史无法保存");}
    }
    private void write(String raw){
        Map<String,?> before=preferences.getAll();boolean existed=before.containsKey(key);Object previous=before.get(key);
        try{
            SharedPreferences.Editor editor=preferences.edit();if(raw==null)editor.remove(key);else editor.putString(key,raw);
            if(editor.commit())return;
        }catch(RuntimeException failed){restore(existed,previous);throw new IllegalStateException("搜索历史保存失败，请重试");}
        restore(existed,previous);throw new IllegalStateException("搜索历史保存失败，请重试");
    }
    private void restore(boolean existed,Object previous){
        try{
            SharedPreferences.Editor editor=preferences.edit();
            if(!existed)editor.remove(key);
            else if(previous instanceof String)editor.putString(key,(String)previous);
            else if(previous instanceof Integer)editor.putInt(key,(Integer)previous);
            else if(previous instanceof Long)editor.putLong(key,(Long)previous);
            else if(previous instanceof Float)editor.putFloat(key,(Float)previous);
            else if(previous instanceof Boolean)editor.putBoolean(key,(Boolean)previous);
            else if(previous instanceof Set<?>){Set<String> values=new HashSet<>();for(Object value:(Set<?>)previous)if(value instanceof String)values.add((String)value);editor.putStringSet(key,values);}
            else editor.remove(key);
            // Android updates its live map even if this recovery disk write also fails.
            editor.commit();
        }catch(RuntimeException unavailable){/* The original failure still reaches the caller; no new value is reported as saved. */}
    }
    private static IllegalStateException corrupt(){return new IllegalStateException("搜索历史无法读取，可清空后重新使用");}
}
