package cn.ngapure.app;

import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Explicitly local author filtering, separate for each NGA host and account. */
final class NativeUserBlockStore {
    interface ScopeGuard {boolean current();}
    private final SharedPreferences prefs;private final String account,key;
    NativeUserBlockStore(SharedPreferences prefs,String host,String account){if(prefs==null||!NgaStore.HOSTS.contains(host)||account==null||!account.matches("[1-9][0-9]{0,17}"))throw new IllegalArgumentException("无效本机屏蔽账号范围");this.prefs=prefs;this.account=account;key=key(host,account);}
    List<NativeUserActions.BlockedUser> users(){List<NativeUserActions.BlockedUser> result=new ArrayList<>();try{JSONObject data=read();java.util.Iterator<String> iterator=data.keys();while(iterator.hasNext()){String uid=iterator.next();if(!uid.matches("[1-9][0-9]{0,17}")||!(data.opt(uid) instanceof String))throw new IOException("本机屏蔽列表损坏");result.add(new NativeUserActions.BlockedUser(uid,data.getString(uid)));}Collections.sort(result,(a,b)->a.uid.compareTo(b.uid));}catch(Exception invalid){result.clear();}return result;}
    synchronized void set(String uid,String name,boolean blocked,ScopeGuard guard) throws IOException {
        if(uid==null||!uid.matches("[1-9][0-9]{0,17}")||uid.equals(account))throw new IllegalArgumentException("不能屏蔽自己或无效用户");if(name==null||name.length()>100||name.indexOf('\0')>=0)throw new IllegalArgumentException("用户名过长或无效");
        JSONObject data=read();try{if(blocked){if(data.length()>=2000&&!data.has(uid))throw new IOException("本机屏蔽列表已达 2000 人");data.put(uid,name.trim().isEmpty()?"UID "+uid:name);}else data.remove(uid);}catch(org.json.JSONException impossible){throw new IOException("无法保存本机屏蔽列表");}
        if(guard==null||!guard.current())throw new IOException("账号已变化，请重新操作");if(!prefs.edit().putString(key,data.toString()).commit())throw new IOException("本机屏蔽列表保存失败");
    }
    synchronized void clear(ScopeGuard guard) throws IOException {if(guard==null||!guard.current())throw new IOException("账号已变化，请重新操作");if(!prefs.edit().remove(key).commit())throw new IOException("本机屏蔽列表清理失败");}
    private JSONObject read() throws IOException {try{String raw=prefs.getString(key,"{}");if(raw==null||raw.length()>512*1024)throw new IOException("本机屏蔽列表过大");return new JSONObject(raw);}catch(IOException known){throw known;}catch(Exception invalid){throw new IOException("本机屏蔽列表损坏，请先清理列表");}}
    static String key(String host,String account){try{MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] bytes=digest.digest((host+"\n"+account).getBytes(StandardCharsets.UTF_8));StringBuilder key=new StringBuilder("scope_");for(byte b:bytes)key.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return key.toString();}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
