package cn.ngapure.verify;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import cn.ngapure.app.NgaApi;
import cn.ngapure.app.NgaStore;
import cn.ngapure.app.NativeCommunity;
import cn.ngapure.app.NativePosting;
import cn.ngapure.app.NativeDrafts;
import org.json.JSONObject;
import org.json.JSONArray;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;

/** Live reads, with an explicit opt-in daily check-in. Never publishes, uploads or sends messages. */
public final class NativeVerify extends Instrumentation {
    private final JSONObject report=new JSONObject();
    private boolean checkInRequested;
    private interface Check { String run()throws Exception; }
    @Override public void onCreate(Bundle args){super.onCreate(args);checkInRequested=args!=null&&"true".equals(args.getString("checkIn"));start();}
    private void check(String name,Check check){try{report.put(name,"OK "+check.run());}catch(Exception e){try{String code=e instanceof NativeCommunity.CommunityException?((NativeCommunity.CommunityException)e).code.name():e instanceof NativePosting.PostingException?((NativePosting.PostingException)e).kind.name():e.getClass().getSimpleName();report.put(name,"FAIL "+code+": "+e.getMessage());}catch(Exception ignored){}}}
    @Override public void onStart(){
        Context ctx=getTargetContext();NgaStore store=new NgaStore(ctx);NgaApi api=new NgaApi(ctx);NativeCommunity community=diagnosticCommunity(store);NativePosting posting=new NativePosting(store);
        check("session",()->"loggedIn="+store.loggedIn());
        check("recommendations",()->"count="+api.hotTopics().size());
        check("topics",()->"count="+api.topics("414",1,"").size());
        final String[] tid={""};
        check("nativeReader",()->{List<NgaApi.Topic> topics=api.topics("414",1,"");if(topics.isEmpty())throw new Exception("no public topic");tid[0]=topics.get(0).tid;NgaApi.ThreadPage page=api.thread(tid[0],1);int withAuthor=0,withText=0;for(NgaApi.Post p:page.posts){if(!p.authorId.isEmpty())withAuthor++;if(!p.content.isEmpty())withText++;}return "posts="+page.posts.size()+" authorIds="+withAuthor+" contents="+withText;});
        check("nativeSearch",()->"count="+api.searchTopics("游戏","414",1).size());
        check("selfProfile",()->{NativeCommunity.UserProfile p=community.self();return "uidPresent="+!p.uid.isEmpty()+" namePresent="+!p.username.isEmpty();});
        check("inbox",()->{NativeCommunity.ConversationPage p=community.conversations(1);return "items="+p.items.size()+" hasNext="+p.hasNext;});
        check("previouslyReadConversation",()->{for(NativeCommunity.Conversation item:community.conversations(1).items){if(!item.unread){NativeCommunity.MessagePage page=community.conversation(item.mid,1);int nonempty=0;for(NativeCommunity.Message message:page.items)if(!message.content.isEmpty())nonempty++;return "messages="+page.items.size()+" nonemptyBodies="+nonempty+" hasNext="+page.hasNext;}}return "skipped: no previously read conversation";});
        check("reminders",()->"items="+community.reminders(0).items.size());
        check("favoriteFolders",()->"items="+community.favoriteFolders(1).items.size());
        check("cloudFavorites",()->"items="+community.favorites("1",1).items.size());
        if(checkInRequested)check("checkInLive",()->{store.recordCheckInAttempt("0.9.1 原生验收");NgaApi.CheckInResult result=api.checkIn();return "success="+result.success+" already="+result.already;});
        check("prepareNewReadonly",()->{NativePosting.Prepare p=posting.prepare("new","414","","");return "action="+p.action+" upload="+p.canUpload;});
        check("prepareReplyReadonly",()->{NativePosting.Prepare p=posting.prepare("reply","414",tid[0],"");return "action="+p.action+" upload="+p.canUpload;});
        check("prepareQuoteReadonly",()->{NativePosting.Prepare p=posting.prepare("quote","414",tid[0],"0");return "action="+p.action+" serverQuotePresent="+!p.getServerQuotedText().isEmpty();});
        check("draftPersistence",()->{NativeDrafts drafts=new NativeDrafts(ctx);NativeDrafts.Draft saved=drafts.save("reply","414","999999999999","","Native smoke test","LOCAL ONLY — not sent");NativeDrafts.Draft read=drafts.get("reply","414","999999999999","");if(read==null||!saved.content.equals(read.content))throw new Exception("draft roundtrip failed");drafts.delete(saved);if(drafts.get("reply","414","999999999999","")!=null)throw new Exception("draft delete failed");return "save/read/delete";});
        Bundle result=new Bundle();result.putString("native_readonly_report",report.toString());finish(Activity.RESULT_OK,result);
    }
    private NativeCommunity diagnosticCommunity(NgaStore store){
        try{
            Class<?> type=Class.forName("cn.ngapure.app.NativeCommunity$HttpsTransport");Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);NativeCommunity.Transport network=(NativeCommunity.Transport)constructor.newInstance();
            Method decode=NativeCommunity.class.getDeclaredMethod("decode",NativeCommunity.Response.class);decode.setAccessible(true);
            return new NativeCommunity(new NativeCommunity.Session(){public String host(){return store.host();}public String cookie(){return store.cookie();}},(request,cookie)->{
                NativeCommunity.Response response=network.execute(request,cookie);
                String lib=request.parameters.get("__lib");
                if("message".equals(lib)||"topic_favor_v2".equals(lib)||"noti".equals(lib))try{report.put("shape_"+lib+"_"+request.parameters.get("__act"),shape(decode.invoke(null,response),0));}catch(Exception ignored){try{report.put("shape_"+lib,"decode failed; HTTP "+response.status);}catch(Exception unavailable){}}
                return response;
            });
        }catch(Exception unavailable){return new NativeCommunity(getTargetContext());}
    }
    /** Field names and types only. Numeric/account-derived keys and every scalar value are removed. */
    private static Object shape(Object value,int depth)throws Exception{
        if(depth>6)return "depth-limit";
        if(value instanceof JSONObject){JSONObject result=new JSONObject();Iterator<String> keys=((JSONObject)value).keys();int count=0;while(keys.hasNext()&&count++<40){String key=keys.next();String name=key.matches("[A-Za-z_][A-Za-z_0-9]{0,40}")?key:"#";result.put(name,shape(((JSONObject)value).opt(key),depth+1));}return result;}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;JSONObject result=new JSONObject();result.put("length",array.length());if(array.length()>0)result.put("first",shape(array.opt(0),depth+1));return result;}
        return value==null||value==JSONObject.NULL?"null":value instanceof Number?"number":value instanceof Boolean?"boolean":"string";
    }
}
