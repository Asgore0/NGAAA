package cn.ngapure.app;

import android.content.SharedPreferences;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Runs the real delivery and persistence code without a View, callback, account or request. */
public final class CommunityMessageDeliveryTest {
    private static int checks;
    private static void ok(boolean condition,String label) {
        checks++;if(!condition)throw new AssertionError(label);
    }
    private static final class Memory implements SharedPreferences {
        final Map<String,Object> values=new HashMap<>(),disk=new HashMap<>();
        boolean rejectNext;int commits;
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
            private void update(Map<String,Object> target){if(clear)target.clear();for(String key:removed)target.remove(key);target.putAll(added);}
            public boolean commit(){
                commits++;update(values); // Android may update memory even when the disk commit fails.
                if(rejectNext){rejectNext=false;return false;}update(disk);return true;
            }
            public void apply(){if(!commit())throw new IllegalStateException("Test disk failure");}
        };}
        Memory reopenFromDisk(){Memory reopened=new Memory();reopened.values.putAll(disk);reopened.disk.putAll(disk);return reopened;}
    }
    private static NativeMessageDrafts store(Memory memory){return new NativeMessageDrafts(memory,"bbs.nga.cn","70001");}
    private static NativeMessageDrafts.Draft pending(NativeMessageDrafts drafts){return drafts.prepareSend("","70002","合成标题","合成正文");}
    private static NativeCommunity.MutationResult ack(){NativeCommunity.MutationResult result=new NativeCommunity.MutationResult();result.confirmed=true;return result;}
    private static void retained(Memory memory,NativeMessageDrafts drafts,NativeMessageDrafts.Draft expected,String label){
        NativeMessageDrafts.Draft actual=drafts.get(expected.targetMid),durable=store(memory.reopenFromDisk()).get(expected.targetMid);
        ok(actual!=null&&actual.pending&&actual.updatedAt==expected.updatedAt,label+" in memory");
        ok(durable!=null&&durable.pending&&durable.updatedAt==expected.updatedAt,label+" on disk");
    }
    public static void main(String[] args) {
        Memory successMemory=new Memory();NativeMessageDrafts successDrafts=store(successMemory);
        NativeMessageDrafts.Draft successPending=pending(successDrafts);int[] sends={0};
        NativeCommunity.MutationResult successAck=ack();
        CommunityMessageDelivery.Result success=CommunityMessageDelivery.run(successDrafts,successPending,()->{sends[0]++;return successAck;});
        ok(sends[0]==1,"one explicit send and no retry");
        ok(success.acknowledgement==successAck&&success.error==null&&success.snapshotCleared&&!success.uncertain,"only explicit acknowledgement produces clean success");
        ok(successDrafts.get("")==null&&store(successMemory.reopenFromDisk()).get("")==null,"success durably retires snapshot without any UI callback");

        for(NativeCommunity.Code code:new NativeCommunity.Code[]{NativeCommunity.Code.LOGIN_REQUIRED,NativeCommunity.Code.CHALLENGE_REQUIRED,
                NativeCommunity.Code.PERMISSION_DENIED,NativeCommunity.Code.RATE_LIMIT,NativeCommunity.Code.UNSUPPORTED}) {
            Memory memory=new Memory();NativeMessageDrafts drafts=store(memory);NativeMessageDrafts.Draft attempt=pending(drafts);
            NativeCommunity.CommunityException error=new NativeCommunity.CommunityException(code,403);
            CommunityMessageDelivery.Result result=CommunityMessageDelivery.run(drafts,attempt,()->{throw error;});
            ok(result.acknowledgement==null&&result.error==error&&result.snapshotCleared&&!result.uncertain,"explicit rejection retires pending: "+code);
            NativeMessageDrafts.Draft restored=store(memory.reopenFromDisk()).get("");
            ok(restored!=null&&!restored.pending&&restored.body.equals(attempt.body),"rejection retains editable body on disk without UI: "+code);
        }
        Memory inputMemory=new Memory();NativeMessageDrafts inputDrafts=store(inputMemory);NativeMessageDrafts.Draft inputPending=pending(inputDrafts);
        IllegalArgumentException inputError=new IllegalArgumentException("Synthetic invalid input");
        CommunityMessageDelivery.Result input=CommunityMessageDelivery.run(inputDrafts,inputPending,()->{throw inputError;});
        ok(input.error==inputError&&!input.uncertain&&input.snapshotCleared&&!store(inputMemory.reopenFromDisk()).get("").pending,"local input rejection is durable before returning");

        for(NativeCommunity.Code code:new NativeCommunity.Code[]{NativeCommunity.Code.UNCONFIRMED,NativeCommunity.Code.NETWORK,
                NativeCommunity.Code.SERVER,NativeCommunity.Code.INVALID_RESPONSE}) {
            Memory memory=new Memory();NativeMessageDrafts drafts=store(memory);NativeMessageDrafts.Draft attempt=pending(drafts);int before=memory.commits;
            NativeCommunity.CommunityException error=new NativeCommunity.CommunityException(code,500);
            CommunityMessageDelivery.Result result=CommunityMessageDelivery.run(drafts,attempt,()->{throw error;});
            ok(result.acknowledgement==null&&result.error==error&&!result.snapshotCleared&&result.uncertain&&memory.commits==before,"unknown result does not alter snapshot: "+code);
            retained(memory,drafts,attempt,"unknown retained: "+code);
        }
        for(Exception error:new Exception[]{new IOException("Synthetic interrupted response"),new IllegalStateException("Synthetic unexpected worker error")}) {
            Memory memory=new Memory();NativeMessageDrafts drafts=store(memory);NativeMessageDrafts.Draft attempt=pending(drafts);
            CommunityMessageDelivery.Result result=CommunityMessageDelivery.run(drafts,attempt,()->{throw error;});
            ok(result.error==error&&result.acknowledgement==null&&!result.snapshotCleared&&result.uncertain,"unexpected failure stays uncertain");
            retained(memory,drafts,attempt,"unexpected failure retained");
        }
        for(boolean missing:new boolean[]{false,true}) {
            Memory memory=new Memory();NativeMessageDrafts drafts=store(memory);NativeMessageDrafts.Draft attempt=pending(drafts);
            CommunityMessageDelivery.Result result=CommunityMessageDelivery.run(drafts,attempt,()->missing?null:new NativeCommunity.MutationResult());
            ok(result.acknowledgement==null&&result.error instanceof NativeCommunity.CommunityException&&result.uncertain&&!result.snapshotCleared,"missing or false acknowledgement cannot retire pending");
            retained(memory,drafts,attempt,"invalid acknowledgement retained");
        }

        for(boolean accepted:new boolean[]{false,true}) {
            Memory memory=new Memory();NativeMessageDrafts drafts=store(memory);NativeMessageDrafts.Draft attempt=pending(drafts);NativeMessageDrafts.Draft[] newer={null};
            CommunityMessageDelivery.Result result=CommunityMessageDelivery.run(drafts,attempt,()->{
                if(!drafts.rejected(attempt))throw new AssertionError("Synthetic attempt transition failed");
                newer[0]=drafts.prepareSend("","70003","另一个合成标题","新快照正文");
                if(accepted)return ack();throw new NativeCommunity.CommunityException(NativeCommunity.Code.PERMISSION_DENIED,403);
            });
            ok(result.uncertain&&!result.snapshotCleared&&(result.acknowledgement!=null)==accepted,"late known result cannot clear a different pending snapshot");
            retained(memory,drafts,newer[0],"newer pending survives late result");
        }

        for(boolean accepted:new boolean[]{false,true}) {
            Memory memory=new Memory();NativeMessageDrafts drafts=store(memory);NativeMessageDrafts.Draft attempt=pending(drafts);
            CommunityMessageDelivery.Result result=CommunityMessageDelivery.run(drafts,attempt,()->{
                memory.rejectNext=true;if(accepted)return ack();throw new NativeCommunity.CommunityException(NativeCommunity.Code.PERMISSION_DENIED,403);
            });
            ok(result.uncertain&&!result.snapshotCleared&&result.error instanceof IllegalStateException,"failed cleanup commit returns a conservative lock");
            ok((result.acknowledgement!=null)==accepted,"failed local cleanup never discards a real server acknowledgement");
            NativeMessageDrafts.Draft durable=store(memory.reopenFromDisk()).get("");
            ok(durable!=null&&durable.pending&&durable.updatedAt==attempt.updatedAt,"failed cleanup keeps durable recovery snapshot for process reconstruction");
        }

        IOException fallbackError=new IOException("Synthetic task failure");CommunityMessageDelivery.Result fallback=CommunityMessageDelivery.unknown(fallbackError);
        ok(fallback.acknowledgement==null&&fallback.error==fallbackError&&!fallback.snapshotCleared&&fallback.uncertain,"UI fallback never treats an unexpected task error as rejection");
        ok(CommunityMessageDelivery.unknown(null).error!=null,"null fallback failure still has a safe unknown result");
        Memory invalidMemory=new Memory();NativeMessageDrafts invalidDrafts=store(invalidMemory);NativeMessageDrafts.Draft editing=invalidDrafts.save("","70002","标题","正文");
        int[] invalidSends={0};CommunityMessageDelivery.Result invalid=CommunityMessageDelivery.run(invalidDrafts,editing,()->{invalidSends[0]++;return ack();});
        ok(invalidSends[0]==0&&invalid.uncertain&&!invalid.snapshotCleared,"an editable snapshot does not authorize a send");
        System.out.println("CommunityMessageDelivery: "+checks+" lifecycle-independent delivery assertions PASS; no UI, network or device");
    }
}
