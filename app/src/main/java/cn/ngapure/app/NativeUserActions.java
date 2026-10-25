package cn.ngapure.app;

import android.content.Context;
import android.text.Html;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Account-bound native follow, profile and cloud folder operations. No write retries. */
public final class NativeUserActions {
    private final NativeCommunity api;
    private final Context application;
    private final NativeCommunity.Session source;
    private final String host, account, exactCookie;
    public static final class Relationship { public final String uid; public final boolean followed; Relationship(String id,boolean value){uid=id;followed=value;} }
    public static final class FollowedUser { public String uid="",username="",avatar=""; }
    public static final class FollowPage { public final List<FollowedUser> items=new ArrayList<>();public int page;public boolean canTryNext; }
    public static final class Outcome { public final boolean confirmed;public final String message,referenceId;Outcome(String text,String id){confirmed=true;message=text;referenceId=id;} }
    public static final class BlockedUser { public final String uid,name;BlockedUser(String id,String label){uid=id;name=label;} }
    public NativeUserActions(Context context) throws NativeCommunity.CommunityException {
        application=context.getApplicationContext();final NgaStore store=new NgaStore(application);
        source=new NativeCommunity.Session(){public String host(){return store.host();}public String cookie(){return store.cookie();}};
        host=source.host();exactCookie=source.cookie();account=account(exactCookie);
        if(account.isEmpty())throw error(NativeCommunity.Code.LOGIN_REQUIRED);
        api=new NativeCommunity(context,host,account).exactForumFavorites(host,exactCookie);
    }
    /** Fixture injection; the same exact-session guard is used in production. */
    public NativeUserActions(NativeCommunity.Session session,NativeCommunity.Transport transport) {
        if(session==null||transport==null)throw new IllegalArgumentException("缺少网络配置");application=null;source=session;host=session.host();exactCookie=session.cookie();account=account(exactCookie);
        api=new NativeCommunity(new NativeCommunity.Session(){public String host(){return host;}public String cookie(){return current()?exactCookie:"";}},transport);
    }
    public NativeCloudBlocks.Snapshot cloudBlocks() throws Exception {return NativeCloudBlocks.read(this);}
    public Outcome setCloudBlock(String uid,String name,boolean blocked) throws Exception {return NativeCloudBlocks.set(this,uid,name,blocked);}
    public Outcome saveCloudBlocks(NativeCloudBlocks.Snapshot before,String keywords,String authors) throws Exception {return NativeCloudBlocks.save(this,before,keywords,authors);}
    void cacheBlocks(NativeCloudBlocks.Snapshot value) throws Exception {requireCurrent();if(application!=null)NativeCloudBlocks.cache(application,host,account,value);}
    /** Worker-thread startup/foreground refresh. Never changes the cloud list. */
    public static void refreshCloudBlocks(Context context) throws Exception {NativeUserActions api=new NativeUserActions(context);NativeCloudBlocks.Snapshot value=api.cloudBlocks();api.cacheBlocks(value);}
    public static final class UserFilter {
        private final Set<String> local;private final NativeCloudBlocks.Filter cloud;
        private UserFilter(Set<String> ids,NativeCloudBlocks.Filter data){local=ids;cloud=data;}
        /** Compare rules in memory without exporting author names, IDs, or keyword material. */
        public boolean sameAs(UserFilter other){return other!=null&&local.equals(other.local)&&(cloud==null?other.cloud==null:cloud.sameAs(other.cloud));}
        public boolean blocks(String uid,String name,String rawContent){return uid!=null&&local.contains(uid)||cloud!=null&&cloud.blocks(uid,name,rawContent);}
    }
    /** Capture one real session per render; both preference stores use that same host/account. */
    public static UserFilter filter(Context context){return filter(context,new NgaStore(context.getApplicationContext()).captureCheckInSession());}
    static UserFilter filter(Context context,NgaStore.CheckInSession session){
        String uid=account(session.cookie);Set<String> local=new HashSet<>();
        if(uid.isEmpty())return new UserFilter(local,null);
        Context application=context.getApplicationContext();
        NativeUserBlockStore blocks=new NativeUserBlockStore(application.getSharedPreferences("nga_native_user_blocks",Context.MODE_PRIVATE),session.host,uid);
        for(BlockedUser user:blocks.users())local.add(user.uid);
        return new UserFilter(local,NativeCloudBlocks.cached(application,session.host,uid));
    }
    public String accountId(){return account;}
    public boolean current(){return host!=null&&host.equals(source.host())&&exactCookie!=null&&exactCookie.equals(source.cookie())&&!account.isEmpty();}
    public void requireCurrent() throws NativeCommunity.CommunityException {if(!current())throw error(NativeCommunity.Code.LOGIN_REQUIRED);}
    JSONObject request(String lib,String act,Map<String,String> args,boolean mutation) throws Exception {requireCurrent();JSONObject response=api.userActionCall(lib,act,args,mutation,true);requireCurrent();return response;}
    public Relationship relationship(String uid) throws Exception {
        number(uid);Map<String,String> args=params("uid",uid);JSONObject row=object(first(request("ucp","get",args,false)));
        if(!uid.equals(scalar(row.opt("uid"))))throw error(NativeCommunity.Code.INVALID_RESPONSE);
        if(row.has("followed"))return new Relationship(uid,strictFlag(row.opt("followed")));
        // Some official profiles omit an explicit false flag. Prove absence using the complete
        // authenticated following collection instead of inventing false from a missing field.
        for(int page=1;page<=100;page++){FollowPage list=followedUsers(page);for(FollowedUser user:list.items)if(uid.equals(user.uid))return new Relationship(uid,true);if(!list.canTryNext)return new Relationship(uid,false);}
        throw error(NativeCommunity.Code.INVALID_RESPONSE);
    }
    public FollowPage followedUsers(int page) throws Exception {
        if(page<1||page>100000)throw new IllegalArgumentException("无效页码");
        Object rows=first(request("follow_v2","get_follow",params("page",Integer.toString(page)),false));FollowPage result=new FollowPage();result.page=page;
        for(JSONObject row:rows(rows)){FollowedUser user=new FollowedUser();user.uid=scalar(row.opt("uid"));numberResponse(user.uid);if(!(row.opt("username") instanceof String))throw error(NativeCommunity.Code.INVALID_RESPONSE);user.username=plain(row.opt("username"));user.avatar=AvatarSources.normalize(scalar(row.opt("avatar")));result.items.add(user);}
        // Official web UI always offers the next page, ending on an explicitly empty collection.
        result.canTryNext=!result.items.isEmpty()&&page<100000;return result;
    }
    public Outcome setFollow(String uid,boolean desired) throws Exception {
        number(uid);if(uid.equals(account))throw new IllegalArgumentException("不能关注自己");
        Relationship before=relationship(uid);if(before.followed==desired)return new Outcome(desired?"云端已关注此用户":"云端已取消关注",uid);
        NativeCommunity.CommunityException uncertain=null;
        try{request("follow_v2","follow",params("id",uid,"type",desired?"1":"8","raw","3"),true);}catch(NativeCommunity.CommunityException e){if(e.code!=NativeCommunity.Code.UNCONFIRMED)throw e;uncertain=e;}
        try{if(relationship(uid).followed==desired)return new Outcome(desired?"已关注，云端状态已确认":"已取消关注，云端状态已确认",uid);}catch(Exception unavailable){throw error(NativeCommunity.Code.UNCONFIRMED);}
        if(uncertain!=null)throw uncertain;throw error(NativeCommunity.Code.UNCONFIRMED);
    }
    public String signature() throws Exception {
        if(account.isEmpty())throw error(NativeCommunity.Code.LOGIN_REQUIRED);Object value=first(request("set_sign","get",params("uid",account,"raw","3"),false));
        if(!(value instanceof String)&&!(value instanceof Number))throw error(NativeCommunity.Code.INVALID_RESPONSE);return unsecure(String.valueOf(value));
    }
    public Outcome setSignature(String sign) throws Exception {
        bounded(sign,0,30000,"签名不能超过 30000 字");String expected=trailing(sign);String old=signature();if(trailing(old).equals(expected))return new Outcome("签名与云端一致",account);
        try{request("set_sign","set",params("uid",account,"sign",sign,"disable","","raw","3"),true);}catch(NativeCommunity.CommunityException e){if(e.code!=NativeCommunity.Code.UNCONFIRMED)throw e;}
        try{if(trailing(signature()).equals(expected))return new Outcome("签名已保存，云端内容已确认",account);}catch(Exception unavailable){throw error(NativeCommunity.Code.UNCONFIRMED);}throw error(NativeCommunity.Code.UNCONFIRMED);
    }
    public List<NativeCommunity.Folder> folders() throws Exception {
        requireCurrent();List<NativeCommunity.Folder> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(int page=1;page<=100;page++){requireCurrent();NativeCommunity.FolderPage block=api.favoriteFolders(page);requireCurrent();for(NativeCommunity.Folder folder:block.items){if(!seen.add(folder.id))throw error(NativeCommunity.Code.INVALID_RESPONSE);result.add(folder);}if(!block.hasNext)return result;}
        throw error(NativeCommunity.Code.INVALID_RESPONSE);
    }
    public Outcome modifyFolder(String folder,String name,boolean isPublic,boolean isDefault) throws Exception {
        number(folder);bounded(name,1,100,"收藏夹名称需为 1–100 字");NativeCommunity.Folder before=find(folders(),folder);if(before==null)throw error(NativeCommunity.Code.INVALID_RESPONSE);
        int option=(isPublic?1:0)|(isDefault?2:0);
        if(before.name.equals(name)&&before.publicFolder==isPublic&&before.defaultFolder==isDefault)return new Outcome("收藏夹与云端一致",folder);
        try{request("topic_favor_v2","modify_folder",params("folder",folder,"name",before.name.equals(name)?"":name,"opt",Integer.toString(option),"raw","3"),true);}catch(NativeCommunity.CommunityException e){if(e.code!=NativeCommunity.Code.UNCONFIRMED)throw e;}
        try{NativeCommunity.Folder after=find(folders(),folder);if(after!=null&&after.name.equals(name)&&after.publicFolder==isPublic&&after.defaultFolder==isDefault)return new Outcome("收藏夹已保存，云端状态已确认",folder);}catch(Exception unavailable){throw error(NativeCommunity.Code.UNCONFIRMED);}throw error(NativeCommunity.Code.UNCONFIRMED);
    }
    public Outcome deleteFolder(String folder) throws Exception {
        number(folder);if(find(folders(),folder)==null)return new Outcome("云端已删除此收藏夹",folder);
        try{request("topic_favor_v2","del_folder",params("folder",folder,"raw","3"),true);}catch(NativeCommunity.CommunityException e){if(e.code!=NativeCommunity.Code.UNCONFIRMED)throw e;}
        try{if(find(folders(),folder)==null)return new Outcome("收藏夹已删除，云端状态已确认",folder);}catch(Exception unavailable){throw error(NativeCommunity.Code.UNCONFIRMED);}throw error(NativeCommunity.Code.UNCONFIRMED);
    }
    /** Official creation is add(folder=-1) with a post, followed by the requested folder options. */
    public Outcome createFolderWithFavorite(String tid,String pid,String name,boolean isPublic,boolean isDefault) throws Exception {
        number(tid);post(pid);bounded(name,1,100,"收藏夹名称需为 1–100 字");List<NativeCommunity.Folder> before=folders();Set<String> existing=new HashSet<>();for(NativeCommunity.Folder f:before)existing.add(f.id);
        try{request("topic_favor_v2","add",params("folder","-1","tid",tid,"pid",pid==null||pid.isEmpty()?"0":pid),true);}catch(NativeCommunity.CommunityException e){if(e.code!=NativeCommunity.Code.UNCONFIRMED)throw e;}
        List<NativeCommunity.Folder> after;try{after=folders();}catch(Exception e){throw error(NativeCommunity.Code.UNCONFIRMED);}
        NativeCommunity.Folder added=null;for(NativeCommunity.Folder f:after)if(!existing.contains(f.id)){if(added!=null)throw error(NativeCommunity.Code.UNCONFIRMED);added=f;}
        try{if(added==null||!hasFavorite(added.id,tid,pid))throw error(NativeCommunity.Code.UNCONFIRMED);}catch(Exception unavailable){throw error(NativeCommunity.Code.UNCONFIRMED);}
        try{modifyFolder(added.id,name,isPublic,isDefault);}catch(Exception partial){throw new PartialFolderException(added.id);}
        return new Outcome("已新建收藏夹并收藏，云端内容已确认",added.id);
    }
    public static final class PartialFolderException extends IOException {
        private static final long serialVersionUID=1L;public final String folder;
        PartialFolderException(String id){super("云端已新建收藏夹并收藏，但名称或选项尚未确认。请刷新收藏夹核对，勿重复新建。");folder=id;}
    }
    public Outcome addFavorite(String tid,String pid,String folder) throws Exception {
        number(tid);post(pid);number(folder);if(find(folders(),folder)==null)throw new IllegalArgumentException("收藏夹已不存在，请刷新后重选");
        try{request("topic_favor_v2","add",params("folder",folder,"tid",tid,"pid",pid==null||pid.isEmpty()?"0":pid),true);}catch(NativeCommunity.CommunityException e){if(e.code!=NativeCommunity.Code.UNCONFIRMED)throw e;}
        try{if(hasFavorite(folder,tid,pid))return new Outcome("已收藏，云端内容已确认",folder);}catch(Exception unavailable){throw error(NativeCommunity.Code.UNCONFIRMED);}throw error(NativeCommunity.Code.UNCONFIRMED);
    }
    public Outcome removeFavorite(String tid,String pid,String folder,int listPage) throws Exception {
        number(tid);post(pid);number(folder);if(listPage<1||listPage>100000)throw new IllegalArgumentException("无效页码");
        if(!hasFavorite(folder,tid,pid))return new Outcome("当前云端收藏夹已无此条目",folder);
        String locator=tid+(pid==null||pid.isEmpty()||pid.equals("0")?"":"_"+pid);
        try{request("topic_favor_v2","del",params("folder",folder,"tidarray",locator,"page",Integer.toString(listPage)),true);}catch(NativeCommunity.CommunityException failure){if(failure.code!=NativeCommunity.Code.UNCONFIRMED)throw failure;}
        try{if(!hasFavorite(folder,tid,pid))return new Outcome("已从当前收藏夹移除，云端内容已确认",folder);}catch(Exception uncertain){throw error(NativeCommunity.Code.UNCONFIRMED);}throw error(NativeCommunity.Code.UNCONFIRMED);
    }
    private boolean hasFavorite(String folder,String tid,String pid) throws Exception {
        String wanted=pid==null||pid.isEmpty()?"0":pid;
        for(int page=1;page<=100;page++){requireCurrent();NativeCommunity.TopicPage block=api.favorites(folder,page);requireCurrent();for(NativeCommunity.Topic topic:block.items){String locator=topic.pid.isEmpty()?"0":topic.pid;if(tid.equals(topic.tid)&&wanted.equals(locator))return true;}if(!block.hasNext)return false;}
        throw error(NativeCommunity.Code.INVALID_RESPONSE);
    }
    JSONObject avatarPrepare() throws Exception {return request("set_avatar","get",params("uid",account,"edit","1","raw","3"),false);}
    Outcome saveAvatarReference(String value) throws Exception {
        bounded(value,1,16000,"无效头像结果");requireCurrent();
        try{request("set_avatar","set",params("uid",account,"avatar",value,"disable","","raw","3"),true);}catch(NativeCommunity.CommunityException e){if(e.code!=NativeCommunity.Code.UNCONFIRMED)throw e;}
        try{Object after=first(request("set_avatar","get",params("uid",account,"raw","3"),false));if(after instanceof String&&!NativeAvatarUpload.avatarCandidate(value,account).isEmpty()&&NativeAvatarUpload.avatarCandidate(unsecure((String)after),account).equals(NativeAvatarUpload.avatarCandidate(value,account)))return new Outcome("头像已保存，云端内容已确认",account);}catch(Exception unavailable){throw error(NativeCommunity.Code.UNCONFIRMED);}throw error(NativeCommunity.Code.UNCONFIRMED);
    }
    static Object slot(JSONObject root,int index) throws NativeCommunity.CommunityException {Object data=root.opt("data");Object value=data instanceof JSONArray?((JSONArray)data).opt(index):data instanceof JSONObject?((JSONObject)data).opt(Integer.toString(index)):null;if(value==null||value==JSONObject.NULL)throw error(NativeCommunity.Code.INVALID_RESPONSE);return value;}
    static Object first(JSONObject root) throws NativeCommunity.CommunityException {return slot(root,0);}
    static JSONObject object(Object value) throws NativeCommunity.CommunityException {if(!(value instanceof JSONObject))throw error(NativeCommunity.Code.INVALID_RESPONSE);return (JSONObject)value;}
    static String scalar(Object value){return value instanceof String||value instanceof Number?String.valueOf(value):"";}
    static Map<String,String> params(String... pairs){Map<String,String> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put(pairs[i],pairs[i+1]);return result;}
    static NativeCommunity.CommunityException error(NativeCommunity.Code code){return new NativeCommunity.CommunityException(code,0);}
    private static List<JSONObject> rows(Object value) throws NativeCommunity.CommunityException {List<JSONObject> result=new ArrayList<>();if(value instanceof JSONArray){JSONArray list=(JSONArray)value;for(int i=0;i<list.length();i++)result.add(object(list.opt(i)));}else if(value instanceof JSONObject){JSONObject list=(JSONObject)value;List<String> keys=new ArrayList<>();java.util.Iterator<String> it=list.keys();while(it.hasNext()){String key=it.next();if(!key.matches("[0-9]{1,8}"))throw error(NativeCommunity.Code.INVALID_RESPONSE);keys.add(key);}Collections.sort(keys,(a,b)->Integer.compare(Integer.parseInt(a),Integer.parseInt(b)));for(String key:keys)result.add(object(list.opt(key)));}else throw error(NativeCommunity.Code.INVALID_RESPONSE);return result;}
    private static boolean strictFlag(Object value) throws NativeCommunity.CommunityException {if(Boolean.TRUE.equals(value)||"1".equals(value)||Integer.valueOf(1).equals(value))return true;if(Boolean.FALSE.equals(value)||"0".equals(value)||Integer.valueOf(0).equals(value))return false;throw error(NativeCommunity.Code.INVALID_RESPONSE);}
    private static String account(String cookie){if(cookie==null||cookie.length()>65536||cookie.matches("(?s).*[\\r\\n\\x00].*"))return "";String uid="",cid="",access="",token="";for(String part:cookie.split(";")){int i=part.indexOf('=');if(i<1)continue;String key=part.substring(0,i).trim(),value=part.substring(i+1).trim();if(key.equals("ngaPassportUid"))uid=value;if(key.equals("ngaPassportCid"))cid=value;if(key.equals("access_uid"))access=value;if(key.equals("access_token"))token=value;}if(valid(uid)&&!cid.isEmpty())return uid;return valid(access)&&!token.isEmpty()?access:"";}
    private static boolean valid(String uid){return uid!=null&&uid.matches("[1-9][0-9]{0,17}");}
    private static void number(String uid){if(!valid(uid))throw new IllegalArgumentException("无效的 NGA 编号");}
    private static void numberResponse(String uid) throws NativeCommunity.CommunityException {if(!valid(uid))throw error(NativeCommunity.Code.INVALID_RESPONSE);}
    private static void post(String pid){if(pid!=null&&!pid.isEmpty()&&!pid.equals("0"))number(pid);}
    private static void bounded(String text,int min,int max,String label){if(text==null||text.trim().length()<min||text.length()>max||text.indexOf('\0')>=0)throw new IllegalArgumentException(label);}
    private static NativeCommunity.Folder find(List<NativeCommunity.Folder> list,String id){for(NativeCommunity.Folder folder:list)if(folder.id.equals(id))return folder;return null;}
    private static String plain(Object value){return Html.fromHtml(scalar(value),Html.FROM_HTML_MODE_LEGACY).toString().trim();}
    private static String unsecure(String value){return value.replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&#39;","'").replace("&amp;","&");}
    private static String trailing(String value){return value.replaceFirst("\\s+$","");}
    private static NativeUserBlockStore blocks(Context context){NgaStore store=new NgaStore(context.getApplicationContext());String uid=account(store.cookie());if(uid.isEmpty())return null;return new NativeUserBlockStore(context.getApplicationContext().getSharedPreferences("nga_native_user_blocks",Context.MODE_PRIVATE),store.host(),uid);}
    public static List<BlockedUser> blockedUsers(Context context){NativeUserBlockStore store=blocks(context);return store==null?new ArrayList<>():store.users();}
    public static boolean isLocallyBlocked(Context context,String uid){if(!valid(uid))return false;for(BlockedUser user:blockedUsers(context))if(user.uid.equals(uid))return true;return false;}
    /** The API captured when the confirmation opened owns this local write too. */
    public void clearLocalBlockList() throws IOException {
        synchronized(NativeUserActions.class){
            requireCurrent();if(application==null)throw new IOException("缺少本机屏蔽存储环境");
            NativeUserBlockStore store=new NativeUserBlockStore(application.getSharedPreferences("nga_native_user_blocks",Context.MODE_PRIVATE),host,account);
            store.clear(this::current);
        }
    }
    public void setLocalBlock(String uid,String name,boolean blocked) throws IOException {
        synchronized(NativeUserActions.class){
            requireCurrent();number(uid);if(application==null)throw new IOException("缺少本机屏蔽存储环境");
            NativeUserBlockStore store=new NativeUserBlockStore(application.getSharedPreferences("nga_native_user_blocks",Context.MODE_PRIVATE),host,account);
            store.set(uid,name==null?"":name,blocked,this::current);
        }
    }
    public static synchronized void clearLocalBlocks(Context context) throws IOException {new NativeUserActions(context).clearLocalBlockList();}
    public static synchronized void setLocallyBlocked(Context context,String uid,String name,boolean blocked) throws IOException {
        new NativeUserActions(context).setLocalBlock(uid,name,blocked);
    }
}
