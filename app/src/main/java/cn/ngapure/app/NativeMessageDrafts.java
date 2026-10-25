package cn.ngapure.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Private, account-scoped PM recovery. No account Cookie/token or raw account identity is stored. */
public final class NativeMessageDrafts {
    private static final Object LOCK=new Object();
    private static long lastVersion;
    private static final int MAX_DRAFTS=20,MAX_STORE_BYTES=4*1024*1024;
    /** Only attempts whose preparation failed before any request could be sent. Never persisted. */
    private static final Map<SharedPreferences,Map<String,Draft>> UNSENT=new IdentityHashMap<>();
    private final SharedPreferences preferences;
    private final String prefix;
    public static final class Draft {
        public final String key,targetMid,toUid,subject,body;
        public final long updatedAt;
        public final boolean pending;
        private Draft(String key,String mid,String to,String subject,String body,long time,boolean pending) {
            this.key=key;targetMid=mid;toUid=to;this.subject=subject;this.body=body;updatedAt=time;this.pending=pending;
        }
    }
    private static final class CommitFailure extends IllegalStateException {
        private static final long serialVersionUID=1L;
        final Draft attempted;
        CommitFailure(Draft attempted){super("私信草稿未安全保存，本次没有发送；可继续编辑后重新确认");this.attempted=attempted;}
    }
    public NativeMessageDrafts(Context context,String host,String accountId) {
        this(context.getApplicationContext().getSharedPreferences("nga_native_message_drafts",Context.MODE_PRIVATE),host,accountId);
    }
    NativeMessageDrafts(SharedPreferences preferences,String host,String accountId) {
        if(preferences==null||!("bbs.nga.cn".equals(host)||"ngabbs.com".equals(host)||"nga.178.com".equals(host))
                ||accountId==null||!accountId.matches("[1-9][0-9]{0,17}"))throw new IllegalArgumentException("请先登录 NGA");
        this.preferences=preferences;
        try {StringBuilder hex=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest((host+"\n"+accountId).getBytes(StandardCharsets.UTF_8)))hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));prefix="pm:"+hex+":";}
        catch(Exception unavailable){throw new IllegalStateException("无法隔离私信草稿",unavailable);}
    }
    public Draft get(String mid) {synchronized(LOCK){String key=key(mid);
        Map<String,Draft> unsent=UNSENT.get(preferences);if(unsent!=null&&unsent.containsKey(key))return unsent.get(key);
        try{return read(key,preferences.getString(key,""));}
        catch(IllegalStateException known){throw known;}
        catch(RuntimeException corrupt){throw new IllegalStateException("私信草稿无法读取，已阻止覆盖或再次发送");}
    }}
    /** Ordinary edits cannot replace or unlock a possibly delivered snapshot. */
    public Draft save(String mid,String to,String subject,String body) {
        synchronized(LOCK){validate(to,subject,body);Draft old=get(mid);if(old!=null&&old.pending)return old;
            if(to.trim().isEmpty()&&subject.trim().isEmpty()&&body.trim().isEmpty()){remove(key(mid));return null;}
            return persist(mid,to,subject,body,false,old);}
    }
    /** Unchanged server-prefilled fields are not a user draft; existing recovery data remains eligible. */
    Draft saveEdited(String mid,String to,String subject,String body,boolean dirty) {
        synchronized(LOCK){if(!dirty&&get(mid)==null)return null;return save(mid,to,subject,body);}
    }
    /** A successful synchronous disk commit is mandatory before the caller may send any request. */
    public Draft prepareSend(String mid,String to,String subject,String body) {
        synchronized(LOCK){validate(to,subject,body);String target=normalMid(mid);Draft old=get(target);
            if(old!=null&&old.pending)throw new IllegalStateException("上次私信结果未确认，请先核对");
            if(body.trim().isEmpty()||(target.isEmpty()&&(subject.trim().isEmpty()||!to.matches("[1-9][0-9]{0,17}(?:,[1-9][0-9]{0,17}){0,9}"))))throw new IllegalArgumentException("请检查收件人、标题和正文");
            try{return persist(target,to,subject,body,true,old);}
            catch(CommitFailure notSent){
                // commit(false) may already have changed SharedPreferences' memory map to pending.
                // The caller received no send permission, so only this process may keep editing.
                Map<String,Draft> unsent=UNSENT.get(preferences);
                if(unsent==null){unsent=new HashMap<>();UNSENT.put(preferences,unsent);}
                unsent.put(notSent.attempted.key,editable(notSent.attempted));throw notSent;
            }}
    }
    public boolean confirmed(Draft expected) {
        synchronized(LOCK){if(!same(getExpected(expected),expected))return false;remove(expected.key);return true;}
    }
    public boolean rejected(Draft expected) {
        synchronized(LOCK){Draft actual=getExpected(expected);if(!same(actual,expected))return false;
            persist(actual.targetMid,actual.toUid,actual.subject,actual.body,false,actual);return true;}
    }
    /** Explicit user-approved local discard; never interprets delivery or touches the server. */
    public boolean discard(Draft expected) {
        synchronized(LOCK){Draft actual=getExpected(expected);if(!sameVersion(actual,expected))return false;remove(expected.key);return true;}
    }
    private Draft getExpected(Draft expected){return expected==null||!expected.key.startsWith(prefix)?null:get(expected.targetMid);}
    private static boolean same(Draft a,Draft b){return sameVersion(a,b)&&a.pending&&b.pending;}
    private static boolean sameVersion(Draft a,Draft b){return a!=null&&b!=null&&a.key.equals(b.key)&&a.updatedAt==b.updatedAt&&a.pending==b.pending&&a.toUid.equals(b.toUid)&&a.subject.equals(b.subject)&&a.body.equals(b.body);}
    private Draft persist(String mid,String to,String subject,String body,boolean pending,Draft old) {
        String key=key(mid);long previous=Math.max(lastVersion,old==null?0:old.updatedAt);
        if(previous==Long.MAX_VALUE)throw new IllegalStateException("私信草稿版本无效");
        long now=Math.max(System.currentTimeMillis(),previous+1);lastVersion=now;
        Draft result=new Draft(key,normalMid(mid),to,subject,body,now,pending);String encoded=encode(result);
        // A false pending commit may need an editable overlay, whose JSON boolean is one byte longer.
        int count=0;long bytes=encoded.getBytes(StandardCharsets.UTF_8).length+(pending?1:0);
        Map<String,?> stored=preferences.getAll();Map<String,Draft> unsent=UNSENT.get(preferences);
        Set<String> keys=new HashSet<>(stored.keySet());if(unsent!=null)keys.addAll(unsent.keySet());
        for(String other:keys)if(other.startsWith("pm:")) {
            count++;if(other.equals(key))continue;
            Draft local=unsent==null?null:unsent.get(other);Object value=local==null?stored.get(other):encode(local);
            if(value instanceof String)bytes+=((String)value).getBytes(StandardCharsets.UTF_8).length;
        }
        if((!keys.contains(key)&&count>=MAX_DRAFTS)||bytes>MAX_STORE_BYTES)throw new IllegalStateException("本机私信草稿已满，请先处理已有草稿");
        if(!preferences.edit().putString(key,encoded).commit()){
            if(!pending&&unsent!=null&&unsent.containsKey(key))unsent.put(key,result);
            throw new CommitFailure(result);
        }
        clearUnsent(key);
        return result;
    }
    private static Draft editable(Draft value){return new Draft(value.key,value.targetMid,value.toUid,value.subject,value.body,value.updatedAt,false);}
    private static String encode(Draft value){
        try{return new JSONObject().put("version",1).put("targetMid",value.targetMid).put("toUid",value.toUid).put("subject",value.subject).put("body",value.body).put("updatedAt",value.updatedAt).put("pending",value.pending).toString();}
        catch(Exception malformed){throw new IllegalStateException("无法保存私信草稿",malformed);}
    }
    private void clearUnsent(String key){Map<String,Draft> unsent=UNSENT.get(preferences);if(unsent!=null){unsent.remove(key);if(unsent.isEmpty())UNSENT.remove(preferences);}}
    private void remove(String key){if(!preferences.edit().remove(key).commit())throw new IllegalStateException("无法安全更新私信草稿");clearUnsent(key);}
    private Draft read(String key,String raw) {
        if(raw==null||raw.isEmpty())return null;
        try {JSONObject j=new JSONObject(raw);
            if(j.getInt("version")!=1||!(j.get("targetMid") instanceof String)||!(j.get("toUid") instanceof String)||!(j.get("subject") instanceof String)||!(j.get("body") instanceof String)||!(j.get("pending") instanceof Boolean))throw new IllegalArgumentException();
            String mid=j.getString("targetMid"),to=j.getString("toUid"),subject=j.getString("subject"),body=j.getString("body");validate(to,subject,body);
            long time=j.getLong("updatedAt");if(!key.equals(key(mid))||time<=0||time==Long.MAX_VALUE)throw new IllegalArgumentException();
            return new Draft(key,mid,to,subject,body,time,j.getBoolean("pending"));
        }catch(Exception corrupt){throw new IllegalStateException("私信草稿无法读取，已阻止覆盖或再次发送");}
    }
    private String key(String mid){String value=normalMid(mid);return prefix+(value.isEmpty()?"new":value);}
    private static String normalMid(String value){if(value==null||value.isEmpty())return "";if(!value.matches("[1-9][0-9]{0,17}"))throw new IllegalArgumentException("无效私信会话");return value;}
    private static void validate(String to,String subject,String body){if(to==null||subject==null||body==null||to.length()>400||subject.length()>200||body.length()>30000||to.indexOf('\0')>=0||subject.indexOf('\0')>=0||body.indexOf('\0')>=0)throw new IllegalArgumentException("私信草稿内容过长或格式错误");}
}
