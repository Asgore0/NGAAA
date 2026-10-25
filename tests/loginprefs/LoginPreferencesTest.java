package cn.ngapure.app;

import android.content.SharedPreferences;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Synthetic ciphertext only. Models Android's live-map mutation before a failed disk commit. */
public final class LoginPreferencesTest {
    private static int checks;
    private static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void invalid(Runnable action){try{action.run();throw new AssertionError("Invalid identity should fail");}catch(IllegalArgumentException expected){checks++;}}
    private static void fails(Runnable action){try{action.run();throw new AssertionError("Write should fail");}catch(IllegalStateException expected){checks++;}}
    private static final class Memory implements SharedPreferences {
        final Map<String,Object> values=new HashMap<>();final ArrayDeque<String> modes=new ArrayDeque<>();int commits;
        public Map<String,?> getAll(){return new HashMap<>(values);}
        public String getString(String key,String fallback){return values.containsKey(key)?(String)values.get(key):fallback;}
        public Set<String> getStringSet(String key,Set<String> fallback){return fallback;}
        public int getInt(String key,int fallback){return fallback;}public long getLong(String key,long fallback){return fallback;}
        public float getFloat(String key,float fallback){return fallback;}public boolean getBoolean(String key,boolean fallback){return fallback;}
        public boolean contains(String key){return values.containsKey(key);}
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public Editor edit(){return new Editor(){
            final Map<String,Object> put=new HashMap<>();final Set<String> remove=new HashSet<>();boolean clear;
            public Editor putString(String key,String value){if(value==null)return remove(key);put.put(key,value);return this;}
            public Editor putStringSet(String key,Set<String> value){put.put(key,value);return this;}
            public Editor putInt(String key,int value){put.put(key,value);return this;}public Editor putLong(String key,long value){put.put(key,value);return this;}
            public Editor putFloat(String key,float value){put.put(key,value);return this;}public Editor putBoolean(String key,boolean value){put.put(key,value);return this;}
            public Editor remove(String key){remove.add(key);put.remove(key);return this;}public Editor clear(){clear=true;return this;}
            public void apply(){if(clear)values.clear();for(String key:remove)values.remove(key);values.putAll(put);}
            public boolean commit(){commits++;String mode=modes.isEmpty()?"success":modes.remove();if(mode.equals("throwBefore"))throw new IllegalStateException("synthetic storage failure");apply();if(mode.equals("throwAfter"))throw new IllegalStateException("synthetic disk failure");return !mode.equals("false");}
        };}
    }
    private static final String A="ngaPassportUid=70001; ngaPassportCid=fixture-token-a",A2="ngaPassportUid=70001; ngaPassportCid=fixture-token-renewed",B="ngaPassportUid=70002; ngaPassportCid=fixture-token-b";
    private static Memory account(){Memory p=new Memory();LoginPreferences.replace(p,"SYNTHETIC_ACCOUNT_A");p.values.put("check_in_day","synthetic-day-a");p.values.put("check_in_message","synthetic-message-a");p.values.put("check_in_attempt","synthetic-attempt-a");p.values.put("theme","dark");p.values.put("auto_check_in",Boolean.TRUE);return p;}
    private static void oldCache(Memory p,String label){
        ok(LoginPreferences.cookie(p).equals("SYNTHETIC_ACCOUNT_A"),label+" retains old encrypted account");
        ok(LoginPreferences.checkInDay(p).equals("synthetic-day-a"),label+" retains old check-in day");
        ok(LoginPreferences.checkInMessage(p).equals("synthetic-message-a"),label+" retains old check-in result");
        ok(LoginPreferences.checkInAttempt(p).equals("synthetic-attempt-a"),label+" retains old attempt record");
    }
    private static void switchedAccounts(){
        ok(LoginPreferences.accountChanged(A,B),"different validated identities invalidate account cache");
        ok(!LoginPreferences.accountChanged(A,A2),"same identity with renewed token preserves daily deduplication");
        String accessA="access_uid=70001; access_token=fixture-access-token";
        ok(!LoginPreferences.accountChanged(A,accessA)&&!LoginPreferences.accountChanged(accessA,A2),"cookie family changes do not change the same account");
        ok(!LoginPreferences.accountChanged(A,A+"; ngaPassportUid=70001"),"duplicate equal identity is unambiguous");
        ok(LoginPreferences.identity("ngaPassportUid=70002; "+accessA).equals(LoginPreferences.identity(A)),"orphan identity cannot shadow a complete authenticated cookie pair");
        ok(LoginPreferences.identity(A+"; access_uid=70002; access_token=fixture-access-token").isEmpty(),"conflicting complete cookie families are invalid");
        Memory same=account();int before=same.commits;LoginPreferences.replaceAccount(same,"SYNTHETIC_RENEWED_A",A,A2);
        ok(same.commits==before+1,"same-account renewal uses one durable commit");
        ok(LoginPreferences.cookie(same).equals("SYNTHETIC_RENEWED_A")&&LoginPreferences.checkInDay(same).equals("synthetic-day-a")&&LoginPreferences.checkInMessage(same).equals("synthetic-message-a")&&LoginPreferences.checkInAttempt(same).equals("synthetic-attempt-a"),"same-account commit preserves all check-in cache");
        Memory changed=account();before=changed.commits;LoginPreferences.replaceAccount(changed,"SYNTHETIC_ACCOUNT_B",A,B);
        ok(changed.commits==before+1,"account replacement and cache invalidation share one commit");
        ok(LoginPreferences.cookie(changed).equals("SYNTHETIC_ACCOUNT_B")&&!changed.contains("check_in_day")&&!changed.contains("check_in_message")&&!changed.contains("check_in_attempt"),"new account cannot inherit another account's success or attempt record");
        ok(changed.getString("theme","").equals("dark")&&Boolean.TRUE.equals(changed.values.get("auto_check_in")),"account switch preserves appearance and auto-check-in preference");
        for(String prior:new String[]{"","broken","ngaPassportUid=70001","ngaPassportUid=0; ngaPassportCid=fixture-token-a","ngaPassportUid=70001; ngaPassportCid=short","ngaPassportUid=70002; "+A}){
            Memory p=account();before=p.commits;LoginPreferences.replaceAccount(p,"SYNTHETIC_ACCOUNT_B",prior,B);
            ok(p.commits==before+1&&!p.contains("check_in_day")&&!p.contains("check_in_message")&&!p.contains("check_in_attempt"),"unrecognized prior session never lends its cache to a valid new session");
        }
        for(String next:new String[]{"","broken","ngaPassportUid=70002","ngaPassportCid=fixture-token-b","ngaPassportUid=0; ngaPassportCid=fixture-token-b","ngaPassportUid=-1; ngaPassportCid=fixture-token-b","ngaPassportUid=70002; ngaPassportCid=short","ngaPassportUid=70002; ngaPassportCid=fixture token","ngaPassportUid=70002; ngaPassportCid=fixture,token",B+"; ngaPassportUid=70001",B+"; access_uid=70001; access_token=fixture-access-token",B+"\r\n"}){
            Memory p=account();before=p.commits;invalid(()->LoginPreferences.replaceAccount(p,"SYNTHETIC_INVALID",A,next));
            ok(p.commits==before,"invalid new session never reaches durable commit");oldCache(p,"invalid incoming account");
        }
        for(String mode:new String[]{"false","throwAfter","throwBefore"}){
            Memory p=account();p.modes.add(mode);fails(()->LoginPreferences.replaceAccount(p,"SYNTHETIC_ACCOUNT_B",A,B));oldCache(p,"failed account switch");
            ok(p.getString("cookies_encrypted","").equals("SYNTHETIC_ACCOUNT_A")&&p.getString("check_in_day","").equals("synthetic-day-a")&&p.getString("check_in_message","").equals("synthetic-message-a")&&p.getString("check_in_attempt","").equals("synthetic-attempt-a"),"successful best-effort rollback restores the entire previous cache");
        }
        Memory failed=account();failed.modes.add("false");failed.modes.add("throwBefore");fails(()->LoginPreferences.replaceAccount(failed,"SYNTHETIC_ACCOUNT_B",A,B));
        ok(failed.getString("cookies_encrypted","").equals("SYNTHETIC_ACCOUNT_B")&&!failed.contains("check_in_day"),"test exercises live-map mutation and rollback failure");oldCache(failed,"rollback failure");
        failed.modes.add("false");failed.modes.add("throwBefore");fails(()->LoginPreferences.replaceAccount(failed,"SYNTHETIC_ACCOUNT_C",A,B));oldCache(failed,"consecutive failed switch");
        LoginPreferences.recordCheckInAttempt(failed,"synthetic-later-attempt-a");LoginPreferences.saveCheckInResult(failed,true,"synthetic-later-success-a","synthetic-later-day-a");
        ok(LoginPreferences.cookie(failed).equals("SYNTHETIC_ACCOUNT_A")&&LoginPreferences.checkInAttempt(failed).equals("synthetic-later-attempt-a")&&LoginPreferences.checkInDay(failed).equals("synthetic-later-day-a")&&LoginPreferences.checkInMessage(failed).equals("synthetic-later-success-a"),"retained account can update its own protected check-in cache after failed replacement");
        LoginPreferences.saveCheckInResult(failed,false,"synthetic-later-network-failure","not-confirmed-day");
        ok(LoginPreferences.checkInDay(failed).equals("synthetic-later-day-a")&&LoginPreferences.checkInMessage(failed).equals("synthetic-later-network-failure"),"unconfirmed retry never invents another completion day");
        before=failed.commits;LoginPreferences.replaceAccount(failed,"SYNTHETIC_RENEWED_A",A,A2);
        ok(failed.commits==before+1&&LoginPreferences.cookie(failed).equals("SYNTHETIC_RENEWED_A")&&LoginPreferences.checkInDay(failed).equals("synthetic-later-day-a")&&LoginPreferences.checkInAttempt(failed).equals("synthetic-later-attempt-a"),"successful same-account retry commits the protected cache before removing overlay");
        Memory absent=new Memory();LoginPreferences.replace(absent,"SYNTHETIC_ACCOUNT_A");absent.modes.add("false");absent.modes.add("throwBefore");fails(()->LoginPreferences.replaceAccount(absent,"SYNTHETIC_ACCOUNT_B",A,B));
        ok(LoginPreferences.checkInDay(absent).isEmpty()&&LoginPreferences.checkInMessage(absent).equals("尚未签到")&&LoginPreferences.checkInAttempt(absent).equals("尚未执行"),"absent prior fields remain absent through failed switch");
        Memory cleared=account();cleared.modes.add("false");cleared.modes.add("throwBefore");fails(()->LoginPreferences.clear(cleared));oldCache(cleared,"failed logout without durable rollback");
        LoginPreferences.clear(cleared);ok(LoginPreferences.cookie(cleared).isEmpty()&&LoginPreferences.checkInDay(cleared).isEmpty()&&!cleared.contains("check_in_attempt"),"explicit successful logout clears every account-specific check-in field");
        Memory scoped=account();LoginPreferences.replaceAccount(scoped,"SYNTHETIC_ACCOUNT_B",A,B);
        ok(!LoginPreferences.saveCheckInResultForAccount(scoped,LoginPreferences.identity(A),B,true,"synthetic-stale-success","synthetic-stale-day"),"old account cannot confirm the replacement account's day");
        ok(!LoginPreferences.recordCheckInAttemptForAccount(scoped,LoginPreferences.identity(A),B,"synthetic-stale-attempt"),"old account cannot assign the replacement account's attempt record");
        ok(!scoped.contains("check_in_day")&&!scoped.contains("check_in_message")&&!scoped.contains("check_in_attempt"),"rejected old-account writes leave replacement cache untouched");
        ok(!LoginPreferences.saveCheckInResultForAccount(scoped,"",B,false,"synthetic-waiting","synthetic-day"),"logged-out observation cannot overwrite a newly logged-in account");
        ok(LoginPreferences.saveCheckInResultForAccount(scoped,LoginPreferences.identity(B),B,true,"synthetic-current-success","synthetic-current-day"),"current account receives its actual completion");
        ok(LoginPreferences.checkInDay(scoped).equals("synthetic-current-day")&&LoginPreferences.checkInMessage(scoped).equals("synthetic-current-success"),"accepted confirmation uses current account state");
        ok(!LoginPreferences.saveCheckInResultForAccount(scoped,null,B,true,"synthetic-invalid","synthetic-invalid-day"),"missing expected identity never writes");
        Memory loggedOut=new Memory();ok(LoginPreferences.saveCheckInResultForAccount(loggedOut,"","",false,"synthetic-waiting","synthetic-day")&&!loggedOut.contains("check_in_day"),"no-session status may be stored only while still logged out");
        ok(!LoginPreferences.recordCheckInAttemptForAccount(loggedOut,"","","synthetic-invalid-attempt"),"no-session state never records an authenticated attempt");
        Memory independent=account();LoginPreferences.replaceAccount(independent,"SYNTHETIC_ACCOUNT_B",A,B);
        ok(LoginPreferences.cookie(absent).equals("SYNTHETIC_ACCOUNT_A")&&LoginPreferences.cookie(independent).equals("SYNTHETIC_ACCOUNT_B"),"failed scope snapshot does not cross preference instances");
    }
    public static void main(String[] args){
        Memory p=new Memory();p.values.put("theme","dark");
        ok(LoginPreferences.cookie(p).isEmpty(),"fresh installation has no account");
        LoginPreferences.replace(p,"SYNTHETIC_ENCRYPTED_A");ok(LoginPreferences.cookie(p).equals("SYNTHETIC_ENCRYPTED_A"),"successful commit exposes new encrypted session");
        for(String mode:new String[]{"false","throwAfter","throwBefore"}){
            p.modes.add(mode);fails(()->LoginPreferences.replace(p,"SYNTHETIC_ENCRYPTED_B"));
            ok(LoginPreferences.cookie(p).equals("SYNTHETIC_ENCRYPTED_A"),"failed replacement preserves previous account: "+mode);
            ok(p.getString("cookies_encrypted","").equals("SYNTHETIC_ENCRYPTED_A"),"best-effort rollback restores previous ciphertext: "+mode);
            ok(p.getString("theme","").equals("dark"),"unrelated settings preserved");
        }
        p.modes.add("false");p.modes.add("throwBefore");fails(()->LoginPreferences.replace(p,"SYNTHETIC_ENCRYPTED_B"));
        ok(p.getString("cookies_encrypted","").equals("SYNTHETIC_ENCRYPTED_B"),"test actually models changed live map and failed rollback");
        ok(LoginPreferences.cookie(p).equals("SYNTHETIC_ENCRYPTED_A"),"fallback hides an uncommitted different account");
        p.modes.add("false");p.modes.add("throwBefore");fails(()->LoginPreferences.replace(p,"SYNTHETIC_ENCRYPTED_C"));
        ok(LoginPreferences.cookie(p).equals("SYNTHETIC_ENCRYPTED_A"),"consecutive failures do not promote a failed account");
        LoginPreferences.replace(p,"SYNTHETIC_ENCRYPTED_D");ok(LoginPreferences.cookie(p).equals("SYNTHETIC_ENCRYPTED_D"),"explicit successful retry replaces fallback");
        p.values.put("check_in_day","synthetic-day");p.values.put("check_in_message","synthetic-message");p.modes.add("false");fails(()->LoginPreferences.clear(p));
        ok(LoginPreferences.cookie(p).equals("SYNTHETIC_ENCRYPTED_D"),"failed logout retains previous account");
        ok(p.getString("check_in_day","").equals("synthetic-day")&&p.getString("check_in_message","").equals("synthetic-message"),"failed logout rollback preserves account-associated check-in fields");
        LoginPreferences.clear(p);ok(LoginPreferences.cookie(p).isEmpty()&&!p.contains("check_in_day")&&!p.contains("check_in_message"),"successful logout removes account and check-in fields");
        ok(p.getString("theme","").equals("dark"),"successful logout preserves unrelated settings");
        Memory empty=new Memory();empty.modes.add("false");empty.modes.add("throwBefore");fails(()->LoginPreferences.replace(empty,"SYNTHETIC_ENCRYPTED_A"));
        ok(LoginPreferences.cookie(empty).isEmpty(),"failed initial login remains logged out even if live map changed");
        Memory another=new Memory();LoginPreferences.replace(another,"OTHER_PREFERENCE_OBJECT");ok(LoginPreferences.cookie(empty).isEmpty()&&LoginPreferences.cookie(another).equals("OTHER_PREFERENCE_OBJECT"),"fallback does not cross preference instances");
        Memory corrupt=new Memory();corrupt.values.put("cookies_encrypted",Boolean.TRUE);ok(LoginPreferences.cookie(corrupt).isEmpty(),"invalid stored type requires login without crashing");
        switchedAccounts();
        System.out.println("LoginPreferences: "+checks+" failure/recovery assertions PASS; no real credentials or account access");
    }
}
