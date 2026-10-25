package cn.ngapure.app;

import android.content.SharedPreferences;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Keeps a failed disk write from exposing a different account through SharedPreferences' live map. */
final class LoginPreferences {
    private static final String COOKIE = "cookies_encrypted";
    private static final String DAY = "check_in_day", MESSAGE = "check_in_message", ATTEMPT = "check_in_attempt";
    private static final String[] ACCOUNT_KEYS = {COOKIE, DAY, MESSAGE, ATTEMPT};
    private static final Map<SharedPreferences, Map<String,Object>> failedWrites = new WeakHashMap<>();
    private LoginPreferences() { }

    static synchronized String cookie(SharedPreferences preferences) { return value(preferences,COOKIE,""); }
    static synchronized String checkInDay(SharedPreferences preferences) { return value(preferences,DAY,""); }
    static synchronized String checkInMessage(SharedPreferences preferences) { return value(preferences,MESSAGE,"尚未签到"); }
    static synchronized String checkInAttempt(SharedPreferences preferences) { return value(preferences,ATTEMPT,"尚未执行"); }
    private static String value(SharedPreferences preferences,String key,String fallback) {
        if(failedWrites.containsKey(preferences)){
            Object saved=failedWrites.get(preferences).get(key);return saved instanceof String?(String)saved:fallback;
        }
        try { return preferences.getString(key,fallback); }
        catch (RuntimeException corrupt) { return fallback; }
    }

    /** Both headers stay in memory only; no identifier or credential enters preferences or diagnostics here. */
    static boolean accountChanged(String previous,String next) {
        String incoming=identity(next);return !incoming.isEmpty()&&!incoming.equals(identity(previous));
    }
    static String identity(String header) {
        if(header==null||header.length()>65536||header.indexOf('\r')>=0||header.indexOf('\n')>=0||header.indexOf('\0')>=0)return "";
        Map<String,String> fields=new LinkedHashMap<>();
        for(String item:header.split(";")){
            int at=item.indexOf('=');if(at<=0)continue;String name=item.substring(0,at).trim(),content=item.substring(at+1).trim();
            if(!name.equals("ngaPassportUid")&&!name.equals("ngaPassportCid")&&!name.equals("access_uid")&&!name.equals("access_token"))continue;
            if(fields.containsKey(name)&&!fields.get(name).equals(content))return "";fields.put(name,content);
        }
        String passport=pairedIdentity(fields,"ngaPassportUid","ngaPassportCid"),access=pairedIdentity(fields,"access_uid","access_token");
        if(!passport.isEmpty()&&!access.isEmpty()&&!passport.equals(access))return "";
        return passport.isEmpty()?access:passport;
    }
    private static String pairedIdentity(Map<String,String> fields,String user,String token) {
        String id=fields.get(user);return id!=null&&id.matches("[1-9][0-9]{0,18}")&&credential(fields.get(token))?id:"";
    }
    private static boolean credential(String value) {
        if(value==null||value.length()<8||value.length()>4096)return false;
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c<33||c>126||c==';'||c==','||c=='\\'||c=='"')return false;}return true;
    }

    static synchronized void replace(SharedPreferences preferences, String encrypted) { update(preferences,encrypted,false,false); }
    static synchronized void replaceAccount(SharedPreferences preferences,String encrypted,String previousCookie,String nextCookie) {
        if(identity(nextCookie).isEmpty())throw new IllegalArgumentException("没有找到有效的 NGA 登录信息");
        update(preferences,encrypted,accountChanged(previousCookie,nextCookie),false);
    }
    static synchronized void clear(SharedPreferences preferences) { update(preferences,"",true,true); }

    static synchronized void recordCheckInAttempt(SharedPreferences preferences,String attempt) {
        preferences.edit().putString(ATTEMPT,attempt).apply();
        if(failedWrites.containsKey(preferences))failedWrites.get(preferences).put(ATTEMPT,attempt);
    }
    static synchronized void saveCheckInResult(SharedPreferences preferences,boolean confirmed,String message,String today) {
        SharedPreferences.Editor edit=preferences.edit().putString(MESSAGE,message);if(confirmed)edit.putString(DAY,today);edit.apply();
        if(failedWrites.containsKey(preferences)){
            Map<String,Object> saved=failedWrites.get(preferences);saved.put(MESSAGE,message);if(confirmed)saved.put(DAY,today);
        }
    }

    static synchronized boolean recordCheckInAttemptForAccount(SharedPreferences preferences,String expected,String currentCookie,String attempt) {
        if(expected==null||expected.isEmpty()||!expected.equals(identity(currentCookie)))return false;
        recordCheckInAttempt(preferences,attempt);return true;
    }
    static synchronized boolean saveCheckInResultForAccount(SharedPreferences preferences,String expected,String currentCookie,boolean confirmed,String message,String today) {
        if(expected==null||!expected.equals(identity(currentCookie)))return false;
        saveCheckInResult(preferences,confirmed,message,today);return true;
    }

    private static void update(SharedPreferences preferences,String encrypted,boolean invalidateCheckIn,boolean clearing) {
        String prior=cookie(preferences);Map<String,Object> before=new LinkedHashMap<>();
        if(failedWrites.containsKey(preferences))before.putAll(failedWrites.get(preferences));
        else {Map<String,?> all=preferences.getAll();for(String key:ACCOUNT_KEYS)if(all.containsKey(key))before.put(key,all.get(key));}
        // A previous failed write may have changed the live map despite commit() returning false.
        if(prior.isEmpty())before.remove(COOKIE);else before.put(COOKIE,prior);
        SharedPreferences.Editor edit=preferences.edit();
        if(encrypted.isEmpty())edit.remove(COOKIE);else edit.putString(COOKIE,encrypted);
        if(invalidateCheckIn)edit.remove(DAY).remove(MESSAGE).remove(ATTEMPT);
        else for(String key:new String[]{DAY,MESSAGE,ATTEMPT}){Object saved=before.get(key);if(saved instanceof String)edit.putString(key,(String)saved);else edit.remove(key);}
        try { if(edit.commit()){failedWrites.remove(preferences);return;} }
        catch(RuntimeException failed){ /* Preserve the previous account snapshot below. */ }
        failedWrites.put(preferences,before);
        try {
            SharedPreferences.Editor restore=preferences.edit();
            for(String key:ACCOUNT_KEYS){Object saved=before.get(key);if(saved instanceof String)restore.putString(key,(String)saved);else restore.remove(key);}
            restore.commit();
        }catch(RuntimeException unavailable){ /* Reads remain pinned to the previous account snapshot. */ }
        // Process-local protection plus best-effort rollback; not a transactional disk guarantee.
        throw new IllegalStateException(clearing?"退出登录未安全保存，原账号仍保留，请重试":"登录信息未安全保存，原账号仍保留，请重试");
    }
}
