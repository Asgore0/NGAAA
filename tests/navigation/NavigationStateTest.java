package cn.ngapure.app;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The production navigation codec, without Android, views, accounts or I/O. */
public final class NavigationStateTest {
    private static int checks;
    private static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static NavigationState.Entry route(String kind,String id,int page){return NavigationState.entry(kind,id,"合成标题",page,"","","","","",345);}
    private static NavigationState.Entry roundtrip(NavigationState.Entry entry){ok(entry!=null,"valid input route");NavigationState.Entry result=NavigationState.decode(NavigationState.encode(entry));ok(result!=null,"encoded route decodes");return result;}
    private static void rejected(String[] row,String label){ok(NavigationState.decode(row)==null,label);}
    public static void main(String[] args){
        NavigationState.Entry home=route("home","",1),forum=route("forum","-547859",3),thread=NavigationState.entry("thread","91001","合成帖子",12,"","-547859","","92001","",1290);
        NavigationState.Entry collection=roundtrip(NavigationState.entry("forum","stid:23673143","天命既定",2,"","","","","650",128));
        ok(collection.id.equals("stid:23673143")&&collection.extra.equals("650")&&collection.page==2,"collection stid, parent backlink and page survive reconstruction");
        NavigationState.Entry child=roundtrip(NavigationState.entry("forum","754","旅行者指引广场",4,"","","","","650",256));
        ok(child.id.equals("754")&&child.extra.equals("650")&&child.page==4,"ordinary child board and parent backlink survive reconstruction");
        for(String invalid:new String[]{"0","01","stid:0","stid:-1","stid:1&fid=2"})ok(route("forum",invalid,1)==null,"invalid board or collection snapshot rejected");
        ok(NavigationState.entry("forum","754","child",1,"","","","","0",0)==null,"invalid parent backlink cannot be saved");
        NavigationState.Entry authorView=roundtrip(NavigationState.entry("thread","91001","只看楼主",4,"","7","","","70001",200));
        ok(authorView.extra.equals("70001")&&authorView.page==4,"author filter survives process recreation with page");
        for(String invalidAuthor:new String[]{"0","-1","1&pid=2","9999999999999999999"})ok(NavigationState.entry("thread","91001","x",1,"","","","",invalidAuthor,0)==null,"invalid author filter cannot be restored");
        ok(NavigationState.entry("thread","91001","x",1,"","","","92001","70001",0)==null,"post locator cannot silently drop author filter");
        NavigationState.Entry image=route("image","https://img.nga.cn/attachments/mon_202610/test.png",1);
        NavigationState.Snapshot restored=NavigationState.decode(NavigationState.encode(image),Arrays.asList(NavigationState.encode(thread),NavigationState.encode(forum),NavigationState.encode(home)));
        ok(restored!=null&&restored.current.kind.equals("image")&&restored.back.size()==3,"forum to thread to image reconstruction");
        ArrayDeque<NavigationState.Entry> stack=new ArrayDeque<>(restored.back);
        ok(stack.pop().kind.equals("thread")&&stack.pop().id.equals("-547859")&&stack.pop().kind.equals("home")&&stack.isEmpty(),"nearest-first return order remains complete");
        ok(restored.back.get(0).page==12&&restored.back.get(0).pid.equals("92001")&&restored.back.get(0).fid.equals("-547859")&&restored.back.get(0).scrollY==1290,"thread locator and offset retained");
        NavigationState.Entry search=roundtrip(NavigationState.entry("search","合成搜索","搜索",4,"","","","","-547859",321));
        ok(search.extra.equals("-547859")&&search.page==4&&search.id.equals("合成搜索"),"forum-scoped search survives reconstruction");
        for(String action:new String[]{"new","reply","quote","modify"}){
            NavigationState.Entry editor=roundtrip(NavigationState.entry("editor","","原生编辑器",1,action,"-547859",action.equals("new")?"":"91001",action.equals("new")?"":"0","",0));
            ok(editor.action.equals(action)&&editor.fid.equals("-547859")&&editor.tid.equals(action.equals("new")?"":"91001"),"editor operation and target survive");
        }
        for(String kind:new String[]{"home","settings","profile","saved","history","drafts","auth","reminders"})ok(roundtrip(route(kind,"",1)).kind.equals(kind),"known empty-id route");
        for(String kind:new String[]{"inbox","following","self","selfreplies"})ok(roundtrip(route(kind,"",7)).page==7,"community paging with empty route ID");
        for(String kind:new String[]{"conversation","user","userreplies","serverfav"})ok(roundtrip(route(kind,"70001",3)).id.equals("70001"),"known identified community route");
        NavigationState.Entry replies=route("userreplies","70001",3),ownReplies=route("selfreplies","",2);
        NavigationState.Snapshot profileReturn=NavigationState.decode(NavigationState.encode(thread),Arrays.asList(NavigationState.encode(replies),NavigationState.encode(ownReplies),NavigationState.encode(route("user","70001",1))));
        ok(profileReturn.back.get(0).kind.equals("userreplies")&&profileReturn.back.get(0).page==3&&profileReturn.back.get(0).scrollY==345,"back from an exact reply retains public reply section, page and reading offset");
        ok(profileReturn.back.get(1).kind.equals("selfreplies")&&profileReturn.back.get(1).page==2&&profileReturn.back.get(2).kind.equals("user"),"own reply and authored topic history remain distinct after reconstruction");
        for(String invalidUid:new String[]{"","0","-1","1&pid=2"})ok(route("userreplies",invalidUid,1)==null,"public replies require a valid user identity");
        ok(route("selfreplies","70001",1)==null,"own reply route cannot retain another user identity");
        NavigationState.Entry privateRoute=roundtrip(NavigationState.entry("conversation","88001","合成私密主题",1,"","","","","",0));
        ok(!String.join("|",NavigationState.encode(privateRoute)).contains("合成私密主题")&&privateRoute.title.equals("私信会话"),"unneeded private subject omitted");
        NavigationState.Snapshot explicitAuth=NavigationState.decode(NavigationState.encode(route("auth","",1)),Collections.singletonList(NavigationState.encode(forum)));
        ok(NavigationState.restoreOrResumeAuthorization(explicitAuth,false)==explicitAuth&&explicitAuth.current.kind.equals("auth"),"explicit account-switch screen survives before any checkpoint exists");
        ok(NavigationState.restoreOrResumeAuthorization(explicitAuth,true)==explicitAuth&&explicitAuth.back.get(0).kind.equals("forum"),"recovered authorization preserves its real predecessor");
        NavigationState.Snapshot coldAuth=NavigationState.restoreOrResumeAuthorization(null,true);
        ok(coldAuth.current.kind.equals("auth")&&coldAuth.back.size()==1&&coldAuth.back.get(0).kind.equals("home"),"cold pending authorization returns to home after completion");
        ok(NavigationState.restoreOrResumeAuthorization(null,false)==null,"cold ordinary launch remains eligible for intent routing");
        ok(NavigationState.restoreOrResumeAuthorization(restored,true)==restored,"saved explicit route takes precedence over stale recovery presence");
        NavigationState.Entry unloaded=roundtrip(NavigationState.entry("thread","91001","合成帖子",12,"","-547859","","92001","",-1));
        ok(unloaded.scrollY==-1,"unloaded frame preserves absent reading offset");
        ok(NavigationState.locatePost(unloaded.pid,null),"first load uses requested post locator");
        ok(!NavigationState.locatePost(unloaded.pid,0),"saved exact top position takes precedence over locator");
        ok(!NavigationState.locatePost(unloaded.pid,1290),"saved reading position takes precedence over locator");
        ok(!NavigationState.locatePost("",null),"ordinary topic does not synthesize a locator");
        ok(NavigationState.encode(route("auth","",1)).length==10,"fixed schema contains identities only");
        ok(NavigationState.entry("auth","synthetic-qr-key","二维码",1,"","","","","",0)==null,"authorization key cannot occupy route ID");
        ok(NavigationState.entry("auth","","授权",1,"","","","","synthetic-token",0)==null,"authorization payload cannot occupy extra");
        List<Object> oversized=new ArrayList<>();for(int i=1;i<=100;i++)oversized.add(NavigationState.encode(route("thread",Integer.toString(i),1)));
        NavigationState.Snapshot capped=NavigationState.decode(NavigationState.encode(home),oversized);
        ok(capped.back.size()==32&&capped.back.get(0).id.equals("1")&&capped.back.get(31).id.equals("32"),"bounded nearest thirty-two frames only");
        boolean immutable=false;try{capped.back.clear();}catch(UnsupportedOperationException expected){immutable=true;}ok(immutable,"restored stack is immutable");
        NavigationState.Snapshot filtered=NavigationState.decode(NavigationState.encode(thread),Arrays.asList("not an array",null,new String[]{"too short"},NavigationState.encode(forum)));
        ok(filtered.back.size()==1&&filtered.back.get(0).kind.equals("forum"),"malformed previous frames filtered without losing valid current");
        ok(NavigationState.decode(new Object(),Collections.emptyList())==null,"invalid current rejects snapshot");
        String[] valid=NavigationState.encode(thread);
        for(int field:new int[]{0,1,2,3,4,5,6,7,8,9}){String[] bad=valid.clone();bad[field]=null;rejected(bad,"null field rejected");}
        for(String page:new String[]{"0","-1","100000","999999999999999999999","1.5","garbage"}){String[] bad=valid.clone();bad[3]=page;rejected(bad,"invalid page rejected");}
        for(String kind:new String[]{"web","browser","script","unknown"}){String[] bad=valid.clone();bad[0]=kind;rejected(bad,"unknown kind rejected");}
        for(String id:new String[]{"0","-1","1&pid=2","9999999999999999999"}){String[] bad=valid.clone();bad[1]=id;rejected(bad,"invalid topic ID rejected");}
        String[] bad=valid.clone();bad[2]="title\ncontrol";rejected(bad,"control title rejected on read");
        bad=valid.clone();bad[4]="reply";rejected(bad,"editor action cannot attach to reader");
        bad=valid.clone();bad[6]="99999";rejected(bad,"conflicting topic locator rejected");
        bad=valid.clone();bad[8]="7";rejected(bad,"extra cannot carry unrelated data");
        bad=valid.clone();bad[9]="-2";rejected(bad,"invalid negative scroll rejected");
        bad=valid.clone();bad[9]="10000001";rejected(bad,"oversized scroll rejected");
        ok(NavigationState.entry("editor","","x",1,"new","","","","",0)==null,"new editor needs valid forum");
        ok(NavigationState.entry("editor","","x",1,"delete","7","91001","","",0)==null,"unknown editor operation rejected");
        ok(NavigationState.entry("editor","","x",1,"reply","7","","","",0)==null,"reply needs topic");
        for(String host:new String[]{"img.nga.cn","img4.nga.cn","img8.nga.cn","img.nga.178.com","img4.nga.178.com","img.ngacn.cc"})ok(NavigationState.imageUrl("https://"+host+"/attachments/test.png"),"allowed media host");
        for(String url:new String[]{"http://img.nga.cn/attachments/test.png","https://img.nga.cn.evil.org/attachments/test.png","https://user:pass@img.nga.cn/attachments/test.png","https://img.nga.cn:8443/attachments/test.png","https://bbs.nga.cn/attachments/test.png","https://img.nga.cn/nuke.php","https://img.nga.cn/attachments/../secret","https://img.nga.cn/attachments/%2e%2e/secret","https://img.nga.cn/attachments/..","file:///attachments/test.png","data:image/png;base64,AAAA"})ok(!NavigationState.imageUrl(url),"unsupported image route rejected");
        ok(NavigationState.tab(-1,0)==0&&NavigationState.tab(99,1)==1&&NavigationState.tab(3,0)==3,"home selections bounded");
        ok(NavigationState.offset(-10)==0&&NavigationState.offset(Integer.MAX_VALUE)==NavigationState.MAX_SCROLL,"capture offset bounded");
        StringBuilder title=new StringBuilder();while(title.length()<300)title.append('x');ok(NavigationState.title(title.toString()).length()==200&&NavigationState.title("a\nb").equals("a b"),"capture titles bounded and control-free");
        System.out.println("NavigationState: "+checks+" reconstruction and validation assertions PASS; no Android, network or device");
    }
}
