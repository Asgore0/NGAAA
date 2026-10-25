package cn.ngapure.app;

import android.content.SharedPreferences;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Models Android's live-map-before-disk commit, including repeated disk failures. No network. */
public final class DraftCommitFailureTest {
    private static int checks;
    private static void ok(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void fails(Runnable action, String label) {
        try { action.run(); throw new AssertionError(label); }
        catch (IllegalStateException expected) { checks++; }
    }
    private static final class Memory implements SharedPreferences {
        final Map<String,Object> values = new HashMap<>(), disk = new HashMap<>();
        int rejectCount, commits;
        boolean gateNextCommit;CountDownLatch entered,release;
        public Map<String,?> getAll() { return new HashMap<>(values); }
        public String getString(String k,String fallback) { Object v=values.get(k); return v==null?fallback:(String)v; }
        public Set<String> getStringSet(String k,Set<String> fallback) { return fallback; }
        public int getInt(String k,int fallback) { return fallback; }
        public long getLong(String k,long fallback) { return fallback; }
        public float getFloat(String k,float fallback) { return fallback; }
        public boolean getBoolean(String k,boolean fallback) { return fallback; }
        public boolean contains(String k) { return values.containsKey(k); }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        public Editor edit() { return new Editor() {
            final Map<String,Object> added=new HashMap<>(); final Set<String> removed=new HashSet<>(); boolean clear;
            public Editor putString(String k,String v) { added.put(k,v); return this; }
            public Editor putStringSet(String k,Set<String> v) { added.put(k,v); return this; }
            public Editor putInt(String k,int v) { added.put(k,v); return this; }
            public Editor putLong(String k,long v) { added.put(k,v); return this; }
            public Editor putFloat(String k,float v) { added.put(k,v); return this; }
            public Editor putBoolean(String k,boolean v) { added.put(k,v); return this; }
            public Editor remove(String k) { removed.add(k); return this; }
            public Editor clear() { clear=true; return this; }
            private void memory() { if(clear)values.clear(); for(String k:removed)values.remove(k); values.putAll(added); }
            public boolean commit() { commits++; memory();
                if(gateNextCommit){gateNextCommit=false;entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Synthetic commit release timeout");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException("Synthetic commit interrupted",error);}}
                if(rejectCount>0){rejectCount--;return false;} disk.clear();disk.putAll(values);return true; }
            public void apply() { memory(); disk.clear();disk.putAll(values); }
        }; }
        Memory restart() { Memory next=new Memory();next.values.putAll(disk);next.disk.putAll(disk);return next; }
    }
    private static NativeDrafts.Draft submit(NativeDrafts store,String tid,String body) {
        return store.saveForSubmission("reply","7",tid,"","",body,Collections.emptyList());
    }
    public static void main(String[] args) throws Exception {
        Memory first=new Memory();NativeDrafts store=new NativeDrafts(first);int[] permissions={0};
        first.rejectCount=2;
        fails(()->{submit(store,"1","保存失败、尚未请求");permissions[0]++;},"failed disk commit must not authorize sending");
        ok(permissions[0]==0,"no publishing permission was returned");
        ok(store.get("reply","7","1","")==null,"failed preparation must not invent an unknown-delivery marker in Android's live map");
        ok(new NativeDrafts(first).get("reply","7","1","")==null,"same-process reopening sees the old recoverable state");
        first.edit().putString("unrelated","flush").commit();
        ok(new NativeDrafts(first.restart()).get("reply","7","1","")==null,"a later unrelated preference flush cannot persist an invented marker");
        NativeDrafts.Draft genuine=submit(store,"1","用户再次确认并保存成功");
        ok(genuine.submissionUnconfirmed&&store.get("reply","7","1","").submissionUnconfirmed,"only a newly successful explicit save authorizes a pending submission");
        fails(()->submit(new NativeDrafts(first),"1","不重复发送"),"genuine unknown delivery stays blocked");

        Memory saved=new Memory();NativeDrafts savedStore=new NativeDrafts(saved);
        savedStore.save("reply","7","2","","原标题","原草稿内容");
        Map<String,Object> old=new HashMap<>(saved.values);saved.rejectCount=1;
        fails(()->submit(savedStore,"2","准备失败的新内容"),"failed prepare over an existing editable draft");
        ok(saved.values.equals(old),"failed submit restores all old draft bytes and timestamp");
        ok(savedStore.get("reply","7","2","").content.equals("原草稿内容")&&!savedStore.get("reply","7","2","").submissionUnconfirmed,"old editable draft is not overwritten by a failed preparation");
        ok(new NativeDrafts(saved.restart()).get("reply","7","2","").content.equals("原草稿内容"),"old editable recovery also survives disk reconstruction");

        Memory quota=new Memory();NativeDrafts full=new NativeDrafts(quota);
        for(int i=1;i<=20;i++)full.save("reply","7",Integer.toString(i),"","标题 "+i,"原有普通草稿 "+i);
        quota.values.put("non-draft-string","keep");quota.disk.put("non-draft-string","keep");
        Map<String,Object> quotaBefore=new HashMap<>(quota.values);quota.rejectCount=2;
        fails(()->submit(full,"21","失败的第二十一个快照"),"failed quota transaction");
        ok(quota.values.equals(quotaBefore),"failed quota eviction restores every old ordinary draft and no new marker");
        ok(full.getAll().size()==20&&full.get("reply","7","21","")==null,"failed transaction neither consumes a slot nor loses an old draft");
        ok(new NativeDrafts(quota.restart()).getAll().size()==20,"disk state retains all old drafts after two failed commits");
        NativeDrafts.Draft accepted=submit(full,"21","下一次显式确认成功");
        ok(full.getAll().size()==20&&accepted.submissionUnconfirmed,"a later successful confirmation applies quota atomically");

        Memory pending=new Memory();NativeDrafts pendingStore=new NativeDrafts(pending);NativeDrafts.Draft sent=submit(pendingStore,"3","真实待确认的快照");
        Map<String,Object> pendingBefore=new HashMap<>(pending.values);pending.rejectCount=2;
        fails(()->pendingStore.rejected(sent),"failed clearing of an actual pending result");
        ok(pending.values.equals(pendingBefore)&&pendingStore.get("reply","7","3","").submissionUnconfirmed,"failed settlement restores the real pending marker rather than permitting another send");
        fails(()->submit(pendingStore,"3","仍不得重复发布"),"pending snapshot remains protected after failed settlement");
        pending.rejectCount=1;
        fails(()->pendingStore.saveAfterReview("reply","7","3","","","用户核对后尝试恢复",Collections.emptyList()),"failed explicit reviewed recovery is not silently accepted");
        ok(pending.values.equals(pendingBefore),"failed review recovery retains exact pending snapshot");
        ok(pendingStore.rejected(sent)&&!pendingStore.get("reply","7","3","").submissionUnconfirmed,"subsequent successful definite rejection unlocks only the exact original attempt");

        Memory clearing=new Memory();NativeDrafts clearStore=new NativeDrafts(clearing);clearStore.save("reply","7","4","","草稿标题","原正文");
        Map<String,Object> clearBefore=new HashMap<>(clearing.values);clearing.rejectCount=2;
        fails(()->clearStore.saveAfterReview("reply","7","4","","","",Collections.emptyList()),"failed durable empty clear");
        ok(clearing.values.equals(clearBefore)&&clearStore.get("reply","7","4","")!=null,"failed clear restores the original draft");
        sharedLockPreventsRollbackOfAnotherEditor();
        System.out.println("NativeDrafts Android-style commit failure checks passed: "+checks);
    }
    private static void sharedLockPreventsRollbackOfAnotherEditor()throws Exception{
        Memory memory=new Memory();NativeDrafts first=new NativeDrafts(memory),second=new NativeDrafts(memory);
        NativeDrafts.Draft old=first.save("reply","7","100","","Saved title","Old editable draft");
        memory.rejectCount=2;memory.gateNextCommit=true;memory.entered=new CountDownLatch(1);memory.release=new CountDownLatch(1);
        CountDownLatch secondStarted=new CountDownLatch(1),secondFinished=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
        Thread failing=new Thread(()->{try{submit(first,"100","Unsent interrupted transaction");error.set(new AssertionError("Failed transaction returned permission"));}catch(IllegalStateException expected){}catch(Throwable failure){error.set(failure);}},"SyntheticDraftCommitA");
        Thread following=new Thread(()->{secondStarted.countDown();try{second.save("reply","7","200","","New title","Other editor committed content");}catch(Throwable failure){error.set(failure);}finally{secondFinished.countDown();}},"SyntheticDraftCommitB");
        failing.start();try{
            ok(memory.entered.await(2,TimeUnit.SECONDS),"first editor reaches a staged live-map mutation before failing disk commit");following.start();
            ok(secondStarted.await(2,TimeUnit.SECONDS),"replacement editor attempts a concurrent save");
            ok(!secondFinished.await(100,TimeUnit.MILLISECONDS),"static draft lock keeps another instance outside the failing transaction and rollback");
        }finally{memory.release.countDown();failing.join(3000);if(following.getState()!=Thread.State.NEW)following.join(3000);}
        ok(!failing.isAlive()&&!following.isAlive()&&error.get()==null,"both instances finish without a deadlock or hidden error");
        NativeDrafts.Draft restored=first.get("reply","7","100","");
        ok(restored!=null&&restored.updatedAt==old.updatedAt&&!restored.submissionUnconfirmed,"failed first writer restores exactly its original editable version");
        ok(first.get("reply","7","200","")!=null&&second.get("reply","7","200","").content.equals("Other editor committed content"),"later successful writer survives the complete draft rollback");
    }
}
