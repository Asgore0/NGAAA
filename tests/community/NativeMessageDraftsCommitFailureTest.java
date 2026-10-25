package cn.ngapure.app;

import android.content.SharedPreferences;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Android-style memory-before-disk failures; no Android storage, UI, account or network. */
public final class NativeMessageDraftsCommitFailureTest {
    private static int checks;
    private static void ok(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static IllegalStateException fails(Runnable action,String label){
        try{action.run();throw new AssertionError(label);}catch(IllegalStateException expected){checks++;return expected;}
    }
    private static final class Memory implements SharedPreferences {
        final Map<String,Object> values=new HashMap<>(),disk=new HashMap<>();boolean rejectNext;int commits;
        public Map<String,?> getAll(){return new HashMap<>(values);}
        public String getString(String key,String fallback){Object value=values.get(key);return value==null?fallback:(String)value;}
        public Set<String> getStringSet(String key,Set<String> fallback){return fallback;}
        public int getInt(String key,int fallback){return fallback;}public long getLong(String key,long fallback){return fallback;}
        public float getFloat(String key,float fallback){return fallback;}public boolean getBoolean(String key,boolean fallback){return fallback;}
        public boolean contains(String key){return values.containsKey(key);}
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public Editor edit(){return new Editor(){
            final Map<String,Object> added=new HashMap<>();final Set<String> removed=new HashSet<>();boolean clear;
            public Editor putString(String key,String value){added.put(key,value);return this;}
            public Editor putStringSet(String key,Set<String> value){added.put(key,value);return this;}
            public Editor putInt(String key,int value){added.put(key,value);return this;}
            public Editor putLong(String key,long value){added.put(key,value);return this;}
            public Editor putFloat(String key,float value){added.put(key,value);return this;}
            public Editor putBoolean(String key,boolean value){added.put(key,value);return this;}
            public Editor remove(String key){removed.add(key);return this;}public Editor clear(){clear=true;return this;}
            public boolean commit(){
                commits++;if(clear)values.clear();for(String key:removed)values.remove(key);values.putAll(added);
                if(rejectNext){rejectNext=false;return false;}
                disk.clear();disk.putAll(values);return true;
            }
            public void apply(){if(!commit())throw new IllegalStateException("Synthetic failed apply");}
        };}
        Memory restart(){Memory result=new Memory();result.values.putAll(disk);result.disk.putAll(disk);return result;}
    }
    private static NativeMessageDrafts drafts(Memory memory){return new NativeMessageDrafts(memory,"bbs.nga.cn","70001");}
    private static NativeMessageDrafts.Draft prepare(NativeMessageDrafts store,String body){return store.prepareSend("","70002","合成标题",body);}
    private static boolean rawPending(Memory memory,String key){return new JSONObject(memory.getString(key,"{}")).optBoolean("pending",false);}
    private static int encodedBytes(NativeMessageDrafts.Draft draft){
        return new JSONObject().put("version",1).put("targetMid",draft.targetMid).put("toUid",draft.toUid)
                .put("subject",draft.subject).put("body",draft.body).put("updatedAt",draft.updatedAt).put("pending",draft.pending)
                .toString().getBytes(StandardCharsets.UTF_8).length;
    }
    private static String text(int count){char[] chars=new char[count];Arrays.fill(chars,'x');return new String(chars);}

    public static void main(String[] args) {
        Memory first=new Memory();NativeMessageDrafts store=drafts(first);first.rejectNext=true;int[] sendPermissions={0};
        IllegalStateException notSent=fails(()->{prepare(store,"未发送的正文");sendPermissions[0]++;},"failed prepare must not return send permission");
        NativeMessageDrafts.Draft editable=store.get("");
        ok(sendPermissions[0]==0&&notSent.getMessage().contains("没有发送"),"failed preparation explicitly reports no send");
        ok(editable!=null&&!editable.pending&&editable.body.equals("未发送的正文")&&rawPending(first,editable.key),"only known-unsent overlay masks Android's changed pending memory");
        ok(!drafts(first).get("").pending,"another editor on the same actual preferences instance stays editable");
        ok(drafts(first.restart()).get("")==null,"restart with no old disk record recovers no invented pending");

        first.rejectNext=true;fails(()->store.saveEdited("","70002","合成标题","失败后继续输入一",true),"a repeated disk failure is still reported");
        ok(store.get("").body.equals("失败后继续输入一")&&!store.get("").pending,"failed edit preserves new input in the known-unsent overlay");
        first.rejectNext=true;fails(()->store.saveEdited("","70002","合成标题","关闭前的最新文字 😀",true),"close flush may fail without losing process-local text");
        ok(drafts(first).get("").body.equals("关闭前的最新文字 😀")&&!drafts(first).get("").pending,"same-process reopening restores latest unsent text");
        NativeMessageDrafts.Draft saved=store.saveEdited("","70002","合成标题","恢复写盘后保存",true);
        NativeMessageDrafts.Draft afterSave=drafts(first.restart()).get("");
        ok(saved!=null&&!saved.pending&&afterSave!=null&&!afterSave.pending&&afterSave.body.equals("恢复写盘后保存"),"successful edit retires overlay and durably preserves editable text");

        Memory previous=new Memory();NativeMessageDrafts previousStore=drafts(previous);
        previousStore.save("","70002","旧标题","磁盘上的旧草稿");previous.rejectNext=true;
        fails(()->prepare(previousStore,"准备失败的新文字"),"prepare failure over an old editable record");
        NativeMessageDrafts.Draft oldDisk=drafts(previous.restart()).get("");
        ok(!previousStore.get("").pending&&previousStore.get("").body.equals("准备失败的新文字"),"current process retains attempted input");
        ok(oldDisk!=null&&!oldDisk.pending&&oldDisk.body.equals("磁盘上的旧草稿"),"restart preserves actual old editable disk state");

        Memory flushed=new Memory();NativeMessageDrafts flushedStore=drafts(flushed);flushed.rejectNext=true;
        fails(()->prepare(flushedStore,"被其他成功提交带到磁盘的 pending"),"prepare before unrelated flush");
        ok(flushed.edit().putString("unrelated-local-setting","synthetic").commit(),"unrelated commit writes the actual shared map");
        ok(!flushedStore.get("").pending,"known no-send evidence remains valid in the originating process");
        NativeMessageDrafts.Draft restartedPending=drafts(flushed.restart()).get("");
        ok(restartedPending!=null&&restartedPending.pending,"restart fails closed only when disk actually contains pending");
        Memory separate=new Memory();NativeMessageDrafts separateStore=drafts(separate);NativeMessageDrafts.Draft separatePending=prepare(separateStore,"另一个存储中的真实 pending");
        ok(separateStore.get("").pending&&separateStore.get("").updatedAt==separatePending.updatedAt,"same scope and mid on a different preferences instance never share overlay");
        ok(new NativeMessageDrafts(flushed,"bbs.nga.cn","70003").get("")==null,"known-unsent overlay remains account-scoped");
        ok(new NativeMessageDrafts(flushed,"ngabbs.com","70001").get("")==null,"known-unsent overlay remains host-scoped");

        Memory retry=new Memory();NativeMessageDrafts retryStore=drafts(retry);retry.rejectNext=true;
        fails(()->prepare(retryStore,"先失败一次"),"first prepare does not send");
        retry.rejectNext=true;fails(()->prepare(retryStore,"再次确认但仍未保存"),"second failed manual preparation also does not send");
        ok(!retryStore.get("").pending&&retryStore.get("").body.equals("再次确认但仍未保存"),"repeated failed prepare retains the latest editable attempt");
        NativeMessageDrafts.Draft permitted=prepare(retryStore,"用户重新确认后才发送");int[] calls={0};
        CommunityMessageDelivery.Result unknown=CommunityMessageDelivery.run(retryStore,permitted,()->{calls[0]++;throw new IOException("Synthetic unknown response");});
        ok(calls[0]==1&&unknown.uncertain&&!unknown.snapshotCleared,"only a newly successful explicit preparation authorizes one send");
        NativeMessageDrafts.Draft locked=retryStore.saveEdited("","70002","改标题","不能覆盖真实未知结果",true);
        ok(locked.pending&&locked.updatedAt==permitted.updatedAt&&locked.body.equals(permitted.body),"successful preparation clears no-send overlay before real unknown delivery");
        ok(drafts(retry).get("").pending&&drafts(retry.restart()).get("").pending,"real unknown stays locked in both current and restarted process");
        fails(()->prepare(retryStore,"不应再次发送"),"real unknown cannot acquire another send permission");

        Memory capped=new Memory();NativeMessageDrafts cappedStore=drafts(capped);
        for(int i=1;i<=20;i++){
            final String mid=Integer.toString(88000+i);capped.rejectNext=true;
            fails(()->cappedStore.prepareSend(mid,"","回复标题","仅未发送的本机文字"),"bounded known-unsent preparation "+i);
        }
        int before=capped.commits;
        fails(()->cappedStore.prepareSend("88021","","回复标题","不允许新增"),"overlay cannot exceed twenty slots");
        ok(capped.commits==before&&cappedStore.get("88021")==null&&capped.values.size()==20,"capacity failure never creates a twenty-first overlay");
        NativeMessageDrafts.Draft held=cappedStore.get("88001");capped.rejectNext=true;
        fails(()->cappedStore.discard(held),"failed local discard cannot lose a known-unsent overlay slot");
        ok(!capped.values.containsKey(held.key)&&cappedStore.get("88001").body.equals(held.body),"overlay survives a failed delete even when Android removed the memory key");
        fails(()->cappedStore.prepareSend("88021","","回复标题","仍不可新增"),"overlay-only slot remains in global capacity accounting");
        ok(cappedStore.discard(cappedStore.get("88001")),"explicit successful discard clears a known-unsent overlay");
        capped.rejectNext=true;fails(()->cappedStore.prepareSend("88021","","回复标题","替换一个已清除的名额"),"one explicit removal admits one replacement failure slot");
        ok(cappedStore.get("88001")==null&&cappedStore.get("88021")!=null&&!cappedStore.get("88021").pending,"discard cannot resurrect its cleared overlay");

        Memory bytes=new Memory();NativeMessageDrafts byteStore=drafts(bytes);bytes.rejectNext=true;
        fails(()->byteStore.prepareSend("99001","","标题","仅在 overlay 的文字"),"byte-budget overlay creation");
        NativeMessageDrafts.Draft byteHeld=byteStore.get("99001");int overlayBytes=encodedBytes(byteHeld);bytes.rejectNext=true;
        fails(()->byteStore.discard(byteHeld),"byte-budget failed deletion leaves only the bounded overlay");
        Memory probeMemory=new Memory();NativeMessageDrafts.Draft probe=drafts(probeMemory).save("99002","","标题","新的文字");
        bytes.values.put("pm:synthetic-padding",text(4*1024*1024-encodedBytes(probe)-overlayBytes+1));
        before=bytes.commits;
        fails(()->byteStore.save("99002","","标题","新的文字"),"four MiB accounting includes overlay bytes even without a preferences key");
        ok(bytes.commits==before&&byteStore.get("99002")==null&&byteStore.get("99001").body.equals(byteHeld.body),"byte-budget refusal neither grows overlay nor loses existing text");
        boolean tooLong=false;try{byteStore.save("99001","","标题",text(30001));}catch(IllegalArgumentException expected){tooLong=true;}
        ok(tooLong&&byteStore.get("99001").body.equals(byteHeld.body),"known-unsent cache cannot bypass per-draft input limits");
        System.out.println("NativeMessageDraftsCommitFailure: "+checks+" memory-before-disk assertions PASS; no storage, UI, network or device");
    }
}
