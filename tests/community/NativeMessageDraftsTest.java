package cn.ngapure.app;

import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Real persistence methods with in-memory SharedPreferences; no account, request, view or device. */
public final class NativeMessageDraftsTest {
    private static int checks;
    private static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void fails(Runnable action,String label){try{action.run();throw new AssertionError(label);}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
    private static final class Memory implements SharedPreferences {
        final Map<String,Object> values=new HashMap<>();boolean reject;int commits;
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
            public Editor putInt(String key,int value){added.put(key,value);return this;}public Editor putLong(String key,long value){added.put(key,value);return this;}
            public Editor putFloat(String key,float value){added.put(key,value);return this;}public Editor putBoolean(String key,boolean value){added.put(key,value);return this;}
            public Editor remove(String key){removed.add(key);return this;}public Editor clear(){clear=true;return this;}
            public boolean commit(){commits++;if(reject)return false;apply();return true;}
            public void apply(){if(clear)values.clear();for(String key:removed)values.remove(key);values.putAll(added);}
        };}
    }
    public static void main(String[] args) throws Exception {
        Memory memory=new Memory();NativeMessageDrafts drafts=new NativeMessageDrafts(memory,"bbs.nga.cn","70001");
        NativeMessageDrafts.Draft edit=drafts.save("","70002","合成标题","[b]合成正文[/b]\n😀");
        ok(edit!=null&&!edit.pending&&edit.updatedAt>0,"timestamped editable snapshot");
        NativeMessageDrafts restored=new NativeMessageDrafts(memory,"bbs.nga.cn","70001");
        ok(restored.get("").body.equals(edit.body)&&restored.get("").toUid.equals("70002"),"process recreation restores exact Unicode and recipient");
        ok(new NativeMessageDrafts(memory,"ngabbs.com","70001").get("")==null,"host scope isolation");
        NativeMessageDrafts other=new NativeMessageDrafts(memory,"bbs.nga.cn","70003");ok(other.get("")==null,"account scope isolation");
        String serialized=memory.getString(edit.key,"");JSONObject object=new JSONObject(serialized);
        ok(!edit.key.contains("70001")&&!serialized.contains("70001")&&object.length()==7,"account identity hashed; only declared recovery fields persist");
        ok(!serialized.contains("cookie")&&!serialized.contains("token")&&!serialized.contains("auth"),"no session fields persisted");
        drafts.save("88001","","回复标题","合成回复");ok(drafts.get("")!=null&&drafts.get("88001").targetMid.equals("88001"),"new and target conversation isolated");
        int before=memory.commits;NativeMessageDrafts.Draft pending=drafts.prepareSend("","70002","合成标题","已确认内容");
        ok(memory.commits==before+1&&pending.pending,"synchronous pending commit occurs before returning send permission");
        NativeMessageDrafts.Draft untouched=drafts.save("","70099","改过标题","不应覆盖");
        ok(untouched.pending&&untouched.body.equals("已确认内容")&&untouched.updatedAt==pending.updatedAt,"editing cannot overwrite unknown sent snapshot");
        fails(()->drafts.prepareSend("","70002","再次发送","重复内容"),"process-independent double send blocked");
        ok(new NativeMessageDrafts(memory,"bbs.nga.cn","70001").get("").pending,"in-flight or unknown request restores locked after process death");
        CommunityWritePolicy recoveredGate=new CommunityWritePolicy();recoveredGate.restoreUncertain();
        ok(recoveredGate.uncertain()&&!recoveredGate.canSubmit()&&!recoveredGate.begin(),"durable pending translates into real policy lock");
        ok(!other.confirmed(pending)&&!other.rejected(pending)&&drafts.get("").pending,"another account cannot clear pending snapshot");
        ok(drafts.rejected(pending)&&!drafts.get("").pending,"explicit rejection durably clears only pending bit");
        ok(drafts.get("").body.equals("已确认内容"),"rejection retains recovery text");
        NativeMessageDrafts.Draft next=drafts.prepareSend("","70002","修正标题","修正正文");
        ok(next.updatedAt>pending.updatedAt,"snapshot version monotonically advances");
        ok(!drafts.confirmed(pending)&&!drafts.rejected(pending)&&drafts.get("").pending,"late result cannot alter newer pending attempt");
        ok(drafts.confirmed(next)&&drafts.get("")==null&&drafts.get("88001")!=null,"only exact acknowledged attempt removed");
        drafts.save("","70002","x","y");drafts.save("","","","");ok(drafts.get("")==null,"empty editable fields remove ordinary draft");
        Memory failing=new Memory();NativeMessageDrafts failed=new NativeMessageDrafts(failing,"bbs.nga.cn","70001");failing.reject=true;
        fails(()->failed.prepareSend("","70002","标题","正文"),"failed commit never returns a sendable snapshot");
        ok(failing.commits==1&&failing.values.isEmpty(),"commit failure leaves caller without durable send authorization");
        failing.reject=false;NativeMessageDrafts.Draft uncertain=failed.prepareSend("","70002","标题","正文");failing.reject=true;
        fails(()->failed.confirmed(uncertain),"failed cleanup keeps conservative pending state");
        ok(failed.get("").pending,"pending survives failed cleanup");
        Memory corrupt=new Memory();NativeMessageDrafts broken=new NativeMessageDrafts(corrupt,"bbs.nga.cn","70001");NativeMessageDrafts.Draft seed=broken.save("","70002","标题","正文");
        corrupt.values.put(seed.key,"{broken");fails(()->broken.get(""),"corruption fails closed");
        fails(()->broken.save("","70002","title","body"),"corrupt state cannot be overwritten into an unlocked draft");
        corrupt.values.put(seed.key,serialized.replace("\"pending\":false","\"pending\":\"false\""));fails(()->broken.get(""),"nonboolean lock is rejected");
        corrupt.values.put(seed.key,Boolean.TRUE);fails(()->broken.get(""),"wrong preference type fails closed without crashing caller");
        Memory full=new Memory();NativeMessageDrafts capped=new NativeMessageDrafts(full,"bbs.nga.cn","70001");
        for(int i=1;i<=20;i++)capped.prepareSend(Integer.toString(88000+i),"","标题","正文");
        fails(()->capped.save("","70002","新标题","新正文"),"full store rejects new draft without evicting pending records");
        ok(full.values.size()==20&&capped.get("88001").pending&&capped.get("88020").pending,"all twenty pending snapshots retained");
        NativeMessageDrafts anotherAccount=new NativeMessageDrafts(full,"bbs.nga.cn","70003");fails(()->anotherAccount.save("","70002","title","body"),"global count cap cannot be bypassed by account scope");
        Memory bytes=new Memory();StringBuilder large=new StringBuilder();while(large.length()<4*1024*1024)large.append('x');bytes.values.put("pm:other:held",large.toString());
        NativeMessageDrafts byteCapped=new NativeMessageDrafts(bytes,"bbs.nga.cn","70001");fails(()->byteCapped.save("","70002","title","body"),"global UTF8 bytes cap rejects additional text");ok(bytes.values.size()==1,"byte cap keeps existing entries unchanged");
        fails(()->drafts.save("bad:id","","",""),"key separator injection rejected");
        fails(()->new NativeMessageDrafts(memory,"example.org","70001"),"scope host whitelist");
        fails(()->new NativeMessageDrafts(memory,"bbs.nga.cn",""),"anonymous draft scope rejected");
        Memory clean=new Memory();NativeMessageDrafts edits=new NativeMessageDrafts(clean,"bbs.nga.cn","70001");
        for(int i=1;i<=20;i++)ok(edits.saveEdited(Integer.toString(90000+i),"","服务端预填标题","",false)==null,"unchanged reply cancellation does not create a draft");
        ok(clean.values.isEmpty(),"opening and cancelling twenty replies cannot exhaust draft capacity");
        NativeMessageDrafts.Draft written=edits.saveEdited("90001","","服务端预填标题","用户已输入",true);
        ok(written!=null&&edits.saveEdited("90001","",written.subject,written.body,false)!=null,"restored content retained even without a new edit");
        NativeMessageDrafts.Draft oldEditable=edits.get("90001"),newEditable=edits.save("90001","","修正标题","更新正文");
        ok(!edits.discard(oldEditable)&&edits.get("90001").body.equals("更新正文"),"stale local deletion cannot discard a newer editable draft");
        ok(edits.discard(newEditable)&&edits.get("90001")==null,"explicit exact editable snapshot deletion");
        NativeMessageDrafts.Draft held=edits.prepareSend("","70002","待核对","可能已送达");
        ok(!new NativeMessageDrafts(clean,"bbs.nga.cn","70003").discard(held)&&edits.get("").pending,"another account cannot locally clear held snapshot");
        ok(edits.discard(held)&&edits.get("")==null,"explicit local discard may retire unknown snapshot without claiming delivery");
        NativeMessageDrafts.Draft fresh=edits.prepareSend("","70004","全新人工确认","新正文");
        ok(fresh.pending&&!edits.discard(held)&&edits.get("").body.equals("新正文"),"old discard cannot affect a separately created attempt");
        ok(!edits.discard(null),"null snapshot cannot discard anything");
        NativeMessageDrafts.Draft identical=fresh;
        for(int i=0;i<20;i++){
            ok(edits.discard(identical),"explicit retirement of identical-input attempt");
            NativeMessageDrafts.Draft replacement=edits.prepareSend("",identical.toUid,identical.subject,identical.body);
            ok(replacement.updatedAt>identical.updatedAt&&!edits.discard(identical),"version cannot repeat after same-millisecond delete and recreate");identical=replacement;
        }
        NativeMessageDrafts.Draft capacitySlot=capped.get("88001");ok(capped.discard(capacitySlot),"explicit removal releases one capacity slot");
        ok(capped.save("","70002","新草稿","正文")!=null&&full.values.size()==20,"new draft admitted only after explicit removal");
        System.out.println("NativeMessageDrafts: "+checks+" persistence assertions PASS; no network or device");
    }
}
