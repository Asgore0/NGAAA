package cn.ngapure.app;

import android.content.Context;
import android.text.Html;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native data only. Call from a worker thread; no logging, WebView or mutation retries. */
public final class NativeCommunity {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Pattern LEGACY_HTML=Pattern.compile("(?is)\\A<html>\\s*<head>\\s*<meta\\s+http-equiv=['\"]Content-Type['\"]\\s+content=['\"]text/html;\\s*charset=(?:GBK|GB18030|UTF-8)['\"]\\s*/?>\\s*</head>\\s*<body>\\s*<script>\\s*(window\\.script_m(?:uti|ulti)_get_var_store\\s*=.*?)\\s*</script>\\s*</body>\\s*</html>\\z");
    private static final Set<String> HOSTS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("bbs.nga.cn", "ngabbs.com", "nga.178.com")));
    private final Session session;
    private final Transport transport;

    public interface Session { String host(); String cookie(); }
    public interface Transport { Response execute(Request request, String cookie) throws IOException; }
    public enum Code { LOGIN_REQUIRED, CHALLENGE_REQUIRED, PERMISSION_DENIED, RATE_LIMIT,
        NETWORK, SERVER, INVALID_RESPONSE, UNCONFIRMED, UNSUPPORTED }
    public static final class CommunityException extends IOException {
        private static final long serialVersionUID = 1L;
        public final Code code;
        public final int httpStatus;
        public CommunityException(Code code, int status) {
            super(errorText(code)); this.code = code; httpStatus = status;
        }
    }
    public static final class Request {
        public final String host, method, path;
        public final Map<String,String> parameters, queryParameters, bodyParameters;
        public final boolean mutation;
        private Request(String host, String path, Map<String,String> parameters, boolean mutation, boolean formPost) {
            this.host = host; this.path = path; this.mutation = mutation;
            method = formPost ? "POST" : "GET";
            this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
            Map<String,String> query=new LinkedHashMap<>(),body=new LinkedHashMap<>();
            if(!formPost)query.putAll(parameters);
            else for(Map.Entry<String,String> entry:parameters.entrySet()) {
                // Official doRequest2 keeps read-only folder routing in the URL and its arguments in the form.
                boolean forumFavoriteWrite=mutation&&"forum_favor2".equals(parameters.get("__lib"))
                        &&"forum_favor".equals(parameters.get("__act"));
                if((!mutation&&(entry.getKey().equals("__lib")||entry.getKey().equals("__act")))
                        ||(forumFavoriteWrite&&entry.getKey().matches("__lib|__act|__output")))query.put(entry.getKey(),entry.getValue());
                else body.put(entry.getKey(),entry.getValue());
            }
            queryParameters=Collections.unmodifiableMap(query);bodyParameters=Collections.unmodifiableMap(body);
        }
        @Override public String toString() { return "NativeCommunity.Request(" + method + " " + path + ")"; }
    }
    public static final class Response {
        public final int status;
        public final byte[] body;
        public final String contentType;
        public Response(int status, byte[] body, String contentType) {
            this.status = status; this.body = body == null ? new byte[0] : body.clone();
            this.contentType = contentType == null ? "" : contentType;
        }
    }
    public static final class Reminder {
        public int type; public String uid="", username="", tid="", pid="", title="", time="", category="";
        public long timestamp; public boolean unread;
    }
    public static final class ReminderPage {
        public List<Reminder> items=new ArrayList<>(); public int unread; public long timeLimit;
    }
    public static final class Conversation {
        public String mid="", subject="", fromUid="", fromName="", lastFromName="", time="";
        public int posts; public long timestamp; public boolean unread;
    }
    public static final class ConversationPage {
        public List<Conversation> items=new ArrayList<>(); public int page=1, nextPage, rowsPerPage=20;
        public boolean hasNext;
    }
    public static final class Participant { public String uid="", username=""; }
    public static final class Message {
        public String id="", mid="", subject="", content="", uid="", username="", avatar="", time="";
        public int floor; public long timestamp;
    }
    public static final class MessagePage {
        public List<Message> items=new ArrayList<>(); public List<Participant> participants=new ArrayList<>();
        public String mid="", subject=""; public int page=1, nextPage, rowsPerPage=20; public boolean hasNext;
    }
    public static final class UserProfile {
        public String uid="", username="", avatar="", signature="", group="", ipLocation="", registeredAt="", reputation="";
        public int posts, verified; public long registeredTimestamp; public boolean muted, nuked;
    }
    public static final class Topic {
        public String tid="", pid="", fid="", title="", author="", authorId="", avatar="", time="";
        public VoteMetrics votes=new VoteMetrics();
        public int replies; public long timestamp;
    }
    public static final class TopicPage {
        public List<Topic> items=new ArrayList<>(); public int page=1, nextPage, rowsPerPage=35, totalPages=1, total;
        public boolean hasNext;
        /** Explicit row-level refusals in an author search do not invalidate other public rows. */
        public int unavailableItems;
    }
    public static final class OfficialActivity {public String url="",icon="";public long start,end;public boolean present;}
    public static final class FollowActivity {
        public String actorUid="",actorName="",actorAvatar="",tid="",pid="",title="",time="";
        public int type;public long timestamp;
    }
    public static final class FollowActivityPage {
        public final List<FollowActivity> items=new ArrayList<>();public int page=1,totalPages=1,nextPage,unavailableItems;public boolean hasNext;
    }
    public static final class Folder { public String id="", name=""; public boolean publicFolder, defaultFolder; public int count; }
    public static final class FolderPage {
        public List<Folder> items=new ArrayList<>(); public int page=1, nextPage; public boolean hasNext;
    }
    public static final class MutationResult { public boolean confirmed; public String message="", referenceId=""; }

    public NativeCommunity(Context context) {
        final NgaStore store=new NgaStore(context.getApplicationContext());
        session=new Session() { public String host(){return store.host();} public String cookie(){return store.cookie();} };
        transport=new HttpsTransport();
    }
    /** A retained editor may never send a newly switched account's credentials. */
    public NativeCommunity(Context context,String expectedHost,String expectedAccount) {
        if(!HOSTS.contains(expectedHost)||!id(expectedAccount,false))throw new IllegalArgumentException("无效账号范围");
        final NgaStore store=new NgaStore(context.getApplicationContext());
        session=new Session() {
            public String host(){return expectedHost;}
            public String cookie(){return scopedCookie(store.host(),expectedHost,expectedAccount,store.cookie());}
        };
        transport=new HttpsTransport();
    }
    public NativeCommunity(Session session, Transport transport) {
        if(session==null||transport==null)throw new IllegalArgumentException("缺少网络配置");
        this.session=session;this.transport=transport;
    }

    /** Exact-session favorite work cannot pick up a refreshed cookie between preflight and write. */
    static NativeCommunity exactForumFavorites(Context context, NgaStore.CheckInSession expected)
            throws CommunityException {
        return new NativeCommunity(context, expected.host, expected.account)
                .exactForumFavorites(expected.host, expected.cookie);
    }
    NativeCommunity exactForumFavorites(String expectedHost, String expectedCookie) throws CommunityException {
        if (expectedHost == null || !HOSTS.contains(expectedHost)) throw new CommunityException(Code.UNSUPPORTED, 0);
        if (expectedCookie == null || !authenticated(expectedCookie)) throw new CommunityException(Code.LOGIN_REQUIRED, 0);
        final Session source = session;
        return new NativeCommunity(new Session() {
            public String host() { return expectedHost; }
            public String cookie() {
                return expectedHost.equals(source.host()) && expectedCookie.equals(source.cookie()) ? expectedCookie : "";
            }
        }, transport);
    }

    public ReminderPage reminders(long since) throws Exception {
        if(since<0||since>253402300799L)throw new IllegalArgumentException("无效时间游标");
        Map<String,String> p=nuke("noti","get_all");p.put("raw","3");p.put("time_limit",Long.toString(since));
        JSONObject response=call("nuke.php",p,false,true);
        ReminderPage result=new ReminderPage();result.timeLimit=since;
        if(emptyReminderPayload(response))return result;
        JSONObject block=object(payload(response));guard(block,200);
        if(!block.has("0")&&!block.has("1"))throw invalid();
        result.unread=Math.max(0,block.optInt("unread",0));
        for(int kind=0;kind<2;kind++) {
            if(!block.has(Integer.toString(kind)))continue;
            for(JSONObject row:rows(block.opt(Integer.toString(kind)))) {
                guard(row,200);Reminder item=new Reminder();item.type=row.optInt("0",0);
                item.uid=scalar(row.opt("1"));item.username=plain(row.opt("2"));
                item.category=kind==0?"reply":"message";
                if(kind==0) {
                    item.tid=scalar(row.opt("6"));item.pid=scalar(row.has("7")?row.opt("7"):row.opt("8"));
                    if(!id(item.tid,false))throw invalid();
                    item.title=plain(row.opt("5"));
                } else item.title="私信提醒";
                item.timestamp=timestamp(row.opt("9"));item.time=time(item.timestamp);
                item.unread=flag(row.opt("unread"));result.timeLimit=Math.max(result.timeLimit,item.timestamp);
                result.items.add(item);
            }
        }
        return result;
    }
    public ConversationPage conversations(int requestedPage) throws Exception {
        page(requestedPage);Map<String,String> p=message("list");p.put("page",Integer.toString(requestedPage));
        JSONObject block=object(payload(call("nuke.php",p,false,true)));guard(block,200);
        requireMessagePage(block);ConversationPage result=new ConversationPage();
        result.page=currentPage(block,requestedPage);result.nextPage=nextPage(block,result.page);
        result.hasNext=result.nextPage>result.page;result.rowsPerPage=perPage(block,20);
        for(JSONObject row:messageRows(block)) {
            guard(row,200);Conversation item=new Conversation();item.mid=scalar(row.opt("mid"));
            if(!id(item.mid,false)||!row.has("subject"))throw invalid();
            item.subject=plain(row.opt("subject"));item.posts=Math.max(0,row.optInt("posts",0));
            item.fromUid=scalar(row.opt("from"));item.fromName=plain(row.opt("from_username"));
            item.lastFromName=plain(row.opt("last_from_username"));
            item.timestamp=timestamp(row.has("last_modify")?row.opt("last_modify"):row.opt("time"));
            item.time=time(item.timestamp);item.unread=flag(row.opt("unread"));result.items.add(item);
        }
        return result;
    }
    /** Server read semantics may also mark this conversation read. */
    public MessagePage conversation(String mid,int requestedPage) throws Exception {
        number(mid,false);page(requestedPage);Map<String,String> p=message("read");p.put("mid",mid);p.put("page",Integer.toString(requestedPage));
        JSONObject block=object(payload(call("nuke.php",p,false,true)));guard(block,200);requireMessagePage(block);
        if(!block.has("allmsgs"))throw invalid();
        MessagePage result=new MessagePage();result.mid=mid;result.page=currentPage(block,requestedPage);
        result.nextPage=nextPage(block,result.page);result.hasNext=result.nextPage>result.page;result.rowsPerPage=perPage(block,20);
        JSONObject users=block.optJSONObject("userInfo");
        if(users!=null)for(String key:keys(users,false)) {
            JSONObject value=users.optJSONObject(key);if(value==null)continue;
            Participant participant=new Participant();participant.uid=key;participant.username=plain(value.opt("username"));result.participants.add(participant);
        }
        if(result.participants.isEmpty()) {
            String[] parts=scalar(block.opt("allUsers")).split("\\t");
            for(int i=0;i+1<parts.length;i+=2)if(id(parts[i],false)) {
                Participant participant=new Participant();participant.uid=parts[i];participant.username=plain(parts[i+1]);result.participants.add(participant);
            }
        }
        for(JSONObject row:rows(block.opt("allmsgs"))) {
            guard(row,200);if(!row.has("content")&&!row.has("subject"))throw invalid();
            Message item=new Message();item.mid=mid;item.id=scalar(row.has("id")?row.opt("id"):row.opt("pid"));
            item.uid=scalar(row.opt("from"));item.subject=plain(row.opt("subject"));
            item.content=scalar(row.has("content")?row.opt("content"):row.opt("subject"));
            JSONObject user=users==null?null:users.optJSONObject(item.uid);
            item.username=plain(user==null?row.opt("from_username"):user.opt("username"));
            item.avatar=avatar(user==null?null:user.opt("avatar"));item.timestamp=timestamp(row.opt("time"));item.time=time(item.timestamp);
            item.floor=(result.page-1)*result.rowsPerPage+result.items.size()+1;result.items.add(item);
            if(result.subject.isEmpty()&&!item.subject.isEmpty())result.subject=item.subject;
        }
        if(result.subject.isEmpty())result.subject=plain(block.opt("subject"));return result;
    }
    public UserProfile user(String uid) throws Exception {
        number(uid,false);Map<String,String> p=nuke("ucp","get");p.put("uid",uid);return profile(call("nuke.php",p,false,false));
    }
    public UserProfile findUser(String username) throws Exception {
        bounded(username,1,100,"请输入用户名");Map<String,String> p=nuke("ucp","get");p.put("username",username.trim());return profile(call("nuke.php",p,false,false));
    }
    public UserProfile self() throws Exception {
        String cookies=safeCookie(session.cookie());if(!authenticated(cookies))throw new CommunityException(Code.LOGIN_REQUIRED,0);
        String uid=cookieValue(cookies,"ngaPassportUid");if(uid.isEmpty())uid=cookieValue(cookies,"access_uid");
        if(!id(uid,false))throw new CommunityException(Code.LOGIN_REQUIRED,0);return user(uid);
    }
    public TopicPage userTopics(String uid,int requestedPage) throws Exception {
        number(uid,false);page(requestedPage);Map<String,String> p=base();p.put("authorid",uid);p.put("page",Integer.toString(requestedPage));
        return topics(call("thread.php",p,false,false),requestedPage,false,true);
    }
    public TopicPage userReplies(String uid,int requestedPage) throws Exception {
        number(uid,false);page(requestedPage);Map<String,String> p=base();p.put("authorid",uid);p.put("searchpost","1");p.put("page",Integer.toString(requestedPage));
        return topics(call("thread.php",p,false,false),requestedPage,true,true);
    }
    public TopicPage favorites(String folder,int requestedPage) throws Exception {
        number(folder,false);page(requestedPage);Map<String,String> p=base();p.put("favor",folder);p.put("page",Integer.toString(requestedPage));
        return topics(call("thread.php",p,false,true),requestedPage);
    }
    /** Official forum_favor2 sync is a read-only form POST, distinct from topic favorites. */
    JSONObject readForumFavorites() throws CommunityException {
        Map<String,String> p=nuke("forum_favor2","forum_favor");p.put("__output","3");p.put("action","sync");
        return call("nuke.php",p,false,true,true);
    }
    /** One official add/del request. Only CloudForumFavorites may interpret its read-back state. */
    void writeForumFavorite(ForumRoute route,boolean desired) throws CommunityException {
        if(route==null)throw new IllegalArgumentException("缺少版块或合集编号");
        Map<String,String> p=nuke("forum_favor2","forum_favor");p.remove("__inchst");p.put("__output","3");p.put("action",desired?"add":"del");
        p.put(route.stid.isEmpty()?"fid":"stid",route.stid.isEmpty()?route.fid:route.stid);
        call("nuke.php",p,true,true,true);
    }
    /** Freeze a multi-request favorite transaction to one host/account without retaining its cookie. */
    NativeCommunity scopedForumFavorites() throws CommunityException {
        final String expectedHost=session.host(),cookies=safeCookie(session.cookie());
        if(expectedHost==null||!HOSTS.contains(expectedHost))throw new CommunityException(Code.UNSUPPORTED,0);
        if(!authenticated(cookies))throw new CommunityException(Code.LOGIN_REQUIRED,0);
        String uid=cookieValue(cookies,"ngaPassportUid");if(!id(uid,false))uid=cookieValue(cookies,"access_uid");
        if(!id(uid,false))throw new CommunityException(Code.LOGIN_REQUIRED,0);
        final String expectedAccount=uid;
        final Session source=session;
        return new NativeCommunity(new Session() {
            public String host(){return expectedHost;}
            public String cookie(){return scopedCookie(source.host(),expectedHost,expectedAccount,source.cookie());}
        },transport);
    }
    /** Official Android home activity entry; never substitutes keyword matches from recommended posts. */
    public OfficialActivity officialActivity() throws Exception {
        JSONObject row=object(payload(call("nuke.php",nuke("nga_index","get_event_app"),false,false)));if(!row.has("act_url"))throw invalid();Object raw=row.opt("act_url");if(!(raw instanceof String))throw invalid();OfficialActivity result=new OfficialActivity();String value=(String)raw;
        result.start=timestamp(row.opt("start"));result.end=timestamp(row.opt("end"));if(value.isEmpty()||value.equals("nul")||value.equals("null"))return result;
        if(value.length()>4096||value.matches("(?s).*[\\s\\p{Cntrl}\\\\].*"))throw invalid();if(value.startsWith("/"))value="https://"+session.host()+value;else if(value.startsWith("http://"))value="https://"+value.substring(7);java.net.URI uri;try{uri=new java.net.URI(value);}catch(Exception bad){throw invalid();}
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getPort()!=-1&&uri.getPort()!=443)throw invalid();result.url=uri.toASCIIString();result.present=true;JSONObject icons=row.optJSONObject("icon");if(icons!=null)for(String density:new String[]{"android_xxxh","android_xxh","android_xh"}){String candidate=avatar(icons.opt(density));if(!candidate.isEmpty()){result.icon=candidate;break;}}return result;
    }
    /** Official user-follow activity; separate from favorite forum navigation. */
    public FollowActivityPage followingActivity(int requestedPage) throws Exception {
        page(requestedPage);Map<String,String> p=nuke("follow_v2","get_push_list");p.put("__output","3");p.put("page",Integer.toString(requestedPage));JSONObject root=call("nuke.php",p,false,true,true);
        Object rawEvents=NativeUserActions.slot(root,0);JSONObject users=object(NativeUserActions.slot(root,1)),topics=object(NativeUserActions.slot(root,4));
        int maximum=strictPageValue(NativeUserActions.slot(root,2),true),current=strictPageValue(NativeUserActions.slot(root,3),false);if(current!=requestedPage)throw invalid();
        FollowActivityPage result=new FollowActivityPage();result.page=current;result.totalPages=Math.max(current,maximum);result.hasNext=current<maximum;result.nextPage=result.hasNext?current+1:0;
        List<Object> events=new ArrayList<>();if(rawEvents instanceof JSONArray){JSONArray a=(JSONArray)rawEvents;for(int i=0;i<a.length();i++)events.add(a.opt(i));}else if(rawEvents instanceof JSONObject){JSONObject a=(JSONObject)rawEvents;for(String key:keys(a,true))events.add(a.opt(key));if(a.length()!=events.size())throw invalid();}else throw invalid();if(events.size()>1000)throw invalid();
        for(Object event:events){if(!(event instanceof JSONObject)&&!(event instanceof JSONArray))throw invalid();if(event instanceof JSONObject&&((JSONObject)event).has("error")&&!((JSONObject)event).isNull("error")){result.unavailableItems++;continue;}int kind=smallInteger(at(event,1));if(kind<1||kind>3){result.unavailableItems++;continue;}
            FollowActivity item=new FollowActivity();item.type=kind;item.actorUid=scalar(at(event,2));item.tid=scalar(at(event,3));item.pid=scalar(at(event,4));if(item.pid.isEmpty())item.pid="0";if(!id(item.actorUid,false)||!id(item.tid,false)||!id(item.pid,true))throw invalid();
            JSONObject user=users.optJSONObject(item.actorUid),topic=topics.optJSONObject(item.tid);if(topic==null||(topic.has("error")&&!topic.isNull("error"))||!topic.has("subject")){result.unavailableItems++;continue;}item.title=plain(topic.opt("subject"));if(user!=null&&!user.has("error")){item.actorName=plain(user.opt("username"));item.actorAvatar=avatar(user.opt("avatar"));}
            item.timestamp=timestamp(at(event,6));item.time=time(item.timestamp);result.items.add(item);
        }return result;
    }
    private static Object at(Object row,int index){return row instanceof JSONArray?((JSONArray)row).opt(index):row instanceof JSONObject?((JSONObject)row).opt(Integer.toString(index)):null;}
    private static int strictPageValue(Object value,boolean zero) throws CommunityException {String text=scalar(value);if(!text.matches(zero?"(?:0|[1-9][0-9]{0,5})":"[1-9][0-9]{0,5}"))throw invalid();int page=Integer.parseInt(text);if(page>100000)throw invalid();return page;}
    private static int smallInteger(Object raw) throws CommunityException {String value=scalar(raw);if(!value.matches("[0-9]{1,3}"))throw invalid();return Integer.parseInt(value);}
    public FolderPage favoriteFolders(int requestedPage) throws Exception {
        page(requestedPage);Map<String,String> p=nuke("topic_favor_v2","list_folder");p.put("page",Integer.toString(requestedPage));
        p.put("__output","3"); // Current official js_commonui doRequest2 uses a read-only POST and output 3.
        JSONObject root=call("nuke.php",p,false,true,true);Object data=root.opt("data"),value=data;
        boolean explicitCollection=false;Object more=null;
        if(data instanceof JSONObject&&((JSONObject)data).has("0")){value=((JSONObject)data).opt("0");more=((JSONObject)data).opt("1");explicitCollection=true;}
        else if(data instanceof JSONArray&&((JSONArray)data).length()>0){value=((JSONArray)data).opt(0);more=((JSONArray)data).opt(1);explicitCollection=true;}
        JSONObject block=value instanceof JSONObject?(JSONObject)value:null;Object entries;
        if(block!=null&&block.has("folders"))entries=block.opt("folders");
        else if(block!=null&&block.has("id")&&block.has("name"))entries=data;
        else if(block!=null&&block.has("0"))entries=block;
        else if(explicitCollection&&(value instanceof JSONArray||(block!=null&&block.length()==0)))entries=value;
        else if(data instanceof JSONArray&&((JSONArray)data).length()==0)entries=data;
        else throw invalid();
        FolderPage result=new FolderPage();result.page=requestedPage;
        if(block!=null){guard(block,200);result.nextPage=nextPage(block,result.page);result.hasNext=result.nextPage>result.page;}
        if(more!=null&&more!=JSONObject.NULL) {
            if(!(more instanceof Boolean)&&!(more instanceof Number)&&!"0".equals(more)&&!"1".equals(more)&&!"false".equals(more)&&!"true".equals(more))throw invalid();
            result.hasNext=flag(more);if(result.hasNext&&requestedPage==100000)throw invalid();
            result.nextPage=result.hasNext?requestedPage+1:0;
        }
        for(JSONObject row:rows(entries)) {
            guard(row,200);Folder item=new Folder();item.id=scalar(row.has("folder")?row.opt("folder"):row.opt("id"));
            if(!id(item.id,false)||!row.has("name"))throw invalid();item.name=plain(row.opt("name"));
            item.publicFolder=(row.optInt("type",row.optInt("opt",0))&1)!=0;item.defaultFolder=flag(row.opt("default"));
            item.count=Math.max(0,row.optInt("count",0));result.items.add(item);
        }
        return result;
    }
    public MutationResult addFavorite(String tid,String pid,String folder) throws Exception {
        number(tid,false);number(folder,false);if(pid!=null&&!pid.isEmpty())number(pid,true);
        Map<String,String> p=nuke("topic_favor_v2","add");p.put("action","add");p.put("tid",tid);p.put("folder",folder);
        if(pid!=null&&!pid.isEmpty())p.put("pid",pid);return confirm(call("nuke.php",p,true,true),"favorite");
    }
    /** Compatibility entry always refers to the default folder; production passes its selected folder. */
    public MutationResult removeFavorite(String tid,String pid,int listPage) throws Exception {return removeFavorite(tid,pid,"1",listPage);}
    public MutationResult removeFavorite(String tid,String pid,String folder,int listPage) throws Exception {
        NativeUserActions.Outcome outcome=new NativeUserActions(session,transport).removeFavorite(tid,pid,folder,listPage);
        MutationResult result=new MutationResult();result.confirmed=outcome.confirmed;result.message=outcome.message;result.referenceId=outcome.referenceId;return result;
    }
    /** Only call in response to an explicit send action; never called by constructors or reads. */
    public MutationResult sendMessage(String toUidCsv,String subject,String content) throws Exception {
        bounded(toUidCsv,1,400,"请输入收件人编号");String[] recipients=toUidCsv.split(",",-1);
        if(recipients.length>10)throw new IllegalArgumentException("收件人过多");for(String uid:recipients)number(uid,false);
        bounded(subject,1,200,"请输入私信标题");bounded(content,1,30000,"请输入私信正文");
        Map<String,String> p=message("new");p.put("to",toUidCsv);p.put("subject",subject);p.put("content",content);
        return confirm(call("nuke.php",p,true,true),"message");
    }
    public MutationResult replyMessage(String mid,String subject,String content) throws Exception {
        number(mid,false);bounded(subject,0,200,"私信标题过长");bounded(content,1,30000,"请输入私信正文");
        Map<String,String> p=message("reply");p.put("mid",mid);p.put("subject",subject);p.put("content",content);
        return confirm(call("nuke.php",p,true,true),"message");
    }

    /** Fixed native account operations; callers freeze the session before constructing an editor. */
    JSONObject userActionCall(String lib,String act,Map<String,String> arguments,boolean mutation,boolean formPost) throws CommunityException {
        boolean read=("ucp".equals(lib)&&("get".equals(act)||"get_block_word".equals(act)))
                ||("follow_v2".equals(lib)&&"get_follow".equals(act))
                ||(("set_sign".equals(lib)||"set_avatar".equals(lib))&&"get".equals(act));
        boolean write=("ucp".equals(lib)&&"set_block_word".equals(act))
                ||("follow_v2".equals(lib)&&"follow".equals(act))
                ||(("set_sign".equals(lib)||"set_avatar".equals(lib))&&"set".equals(act))
                ||("topic_favor_v2".equals(lib)&&("modify_folder".equals(act)||"del_folder".equals(act)||"add".equals(act)||"del".equals(act)));
        if((mutation&&!write)||(!mutation&&!read)||!formPost||arguments==null)throw new IllegalArgumentException("不支持的原生账号操作");
        Map<String,String> parameters=nuke(lib,act);parameters.put("__output","3");
        for(Map.Entry<String,String> pair:arguments.entrySet()) {
            String key=pair.getKey(),value=pair.getValue();
            if(key==null||value==null||key.startsWith("__")||!key.matches("uid|id|type|raw|page|sign|disable|edit|avatar|folder|name|opt|tid|pid|data|tidarray"))throw new IllegalArgumentException("无效原生操作参数");
            parameters.put(key,value);
        }
        return call("nuke.php",parameters,mutation,true,true);
    }
    /** The signed official avatar upload has a separate origin but uses the same bounded decoder. */
    static JSONObject decodeUserActionResponse(Response response) throws CommunityException {return decode(response);}

    private JSONObject call(String path,Map<String,String> parameters,boolean mutation,boolean login) throws CommunityException {
        return call(path,parameters,mutation,login,mutation);
    }
    private JSONObject call(String path,Map<String,String> parameters,boolean mutation,boolean login,boolean formPost) throws CommunityException {
        String host=session.host();if(host==null||!HOSTS.contains(host))throw new CommunityException(Code.UNSUPPORTED,0);
        String cookies=safeCookie(session.cookie());if(login&&!authenticated(cookies))throw new CommunityException(Code.LOGIN_REQUIRED,0);
        Response response;
        try {
            if(!mutation)RequestCancellation.check();
            response=transport.execute(new Request(host,path,parameters,mutation,formPost),cookies);
            if(!mutation)RequestCancellation.check();
        }
        catch(CommunityException known){throw known;}
        catch(IOException uncertain){throw new CommunityException(mutation?Code.UNCONFIRMED:Code.NETWORK,0);}
        if(response==null)throw invalid();
        if(mutation&&response.status>=300&&response.status<400)throw new CommunityException(Code.UNCONFIRMED,response.status);
        try {JSONObject root=decode(response);guard(root,response.status);return root;}
        catch(CommunityException failure){if(mutation&&failure.code==Code.INVALID_RESPONSE)throw new CommunityException(Code.UNCONFIRMED,response.status);throw failure;}
    }
    private static JSONObject decode(Response response) throws CommunityException {
        if(response.body.length>MAX_BYTES)throw invalid();
        String type=response.contentType.toLowerCase(Locale.ROOT),raw;
        if(type.contains("gbk")||type.contains("gb2312")||type.contains("gb18030"))raw=new String(response.body,Charset.forName("GB18030"));
        else try { raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body)).toString(); }
        catch(CharacterCodingException invalidUtf8){raw=new String(response.body,Charset.forName("GB18030"));}
        raw=raw.trim();if(raw.startsWith("\uFEFF"))raw=raw.substring(1).trim();
        if(response.status==429)throw new CommunityException(Code.RATE_LIMIT,response.status);
        if(raw.startsWith("<")) {
            Matcher legacy=LEGACY_HTML.matcher(raw);
            if(legacy.matches())raw=legacy.group(1).trim(); // Parse its JSON assignment; never execute the script.
            else {Code code=classify(raw);if(code==Code.SERVER)code=Code.CHALLENGE_REQUIRED;throw new CommunityException(code,response.status);}
        }
        if(response.status>=300&&response.status<400)throw new CommunityException(Code.CHALLENGE_REQUIRED,response.status);
        if(response.status==401)throw new CommunityException(Code.LOGIN_REQUIRED,response.status);
        raw=raw.replaceFirst("^/\\*\\$js\\$\\*/\\s*","");
        raw=raw.replaceFirst("^window\\.script_m(?:uti|ulti)_get_var_store\\s*=\\s*","");
        raw=raw.replaceFirst("^/\\*\\$js\\$\\*/\\s*","");
        if(!raw.startsWith("{"))throw new CommunityException(response.status==403?Code.PERMISSION_DENIED:response.status>=500?Code.SERVER:Code.INVALID_RESPONSE,response.status);
        try {
            int end=rootEnd(raw);String suffix=raw.substring(end).trim();
            if(suffix.startsWith(";"))suffix=suffix.substring(1).trim();
            suffix=suffix.replaceFirst("^/\\*\\$js\\$\\*/\\s*","");
            if(!suffix.isEmpty()&&!suffix.startsWith("/*error fill content"))throw invalid();
            Object value=new JSONTokener(quotedControls(raw.substring(0,end))).nextValue();
            if(!(value instanceof JSONObject))throw invalid();JSONObject root=(JSONObject)value;
            guard(root,response.status);
            if(response.status>=400)throw new CommunityException(response.status==403?Code.PERMISSION_DENIED:Code.SERVER,response.status);
            return root;
        } catch(CommunityException known){throw known;}
        catch(Exception malformed){throw new CommunityException(Code.INVALID_RESPONSE,response.status);}
    }
    private static UserProfile profile(JSONObject root) throws Exception {
        JSONObject row=object(payload(root));guard(row,200);UserProfile result=new UserProfile();result.uid=scalar(row.opt("uid"));
        if(!id(result.uid,false)||!row.has("username"))throw invalid();result.username=plain(row.opt("username"));
        result.avatar=avatar(row.opt("avatar"));result.signature=plain(row.opt("sign"));result.group=plain(row.opt("group"));
        result.ipLocation=plain(row.opt("ipLoc"));result.posts=Math.max(0,row.optInt("posts",0));result.verified=row.optInt("verified",row.optInt("yz",0));
        result.nuked=result.verified==-1;result.muted=result.verified< -1||timestamp(row.opt("muteTime"))>0;
        result.registeredTimestamp=timestamp(row.opt("regdate"));result.registeredAt=time(result.registeredTimestamp);
        JSONObject reputation=row.optJSONObject("reputation");
        if(reputation!=null)for(JSONObject pair:rows(reputation)) {
            String name=plain(pair.opt("0")),amount=plain(pair.opt("1"));
            if(!name.isEmpty())result.reputation+=(result.reputation.isEmpty()?"":"\n")+name+" "+amount;
        }
        return result;
    }
    private static TopicPage topics(JSONObject root,int requestedPage) throws Exception {
        return topics(root,requestedPage,false);
    }
    private static TopicPage topics(JSONObject root,int requestedPage,boolean authorReplies) throws Exception {
        return topics(root,requestedPage,authorReplies,false);
    }
    private static TopicPage topics(JSONObject root,int requestedPage,boolean authorReplies,boolean authorSearch) throws Exception {
        Object data=root.opt("data");JSONObject block=data instanceof JSONObject?(JSONObject)data:object(payload(root));guard(block,200);
        if(!block.has("__T"))throw invalid();TopicPage result=new TopicPage();result.page=block.optInt("__PAGE",requestedPage);page(result.page);
        JSONObject users=block.optJSONObject("__U");
        result.rowsPerPage=Math.max(1,Math.min(200,block.optInt("__T__ROWS_PAGE",35)));result.total=block.optInt("__ROWS",-1);
        List<JSONObject> sourceRows=rows(block.opt("__T"));
        for(JSONObject row:sourceRows) {
            JSONObject deniedReply=authorReplies?row.optJSONObject("__P"):null;
            if(authorSearch&&((row.has("error")&&!row.isNull("error"))||(deniedReply!=null&&deniedReply.has("error")&&!deniedReply.isNull("error")))) {
                result.unavailableItems++;continue;
            }
            guard(row,200);Topic item=new Topic();item.tid=scalar(row.opt("tid"));
            if(!id(item.tid,false)||!row.has("subject"))throw invalid();
            Object rawPid=row.opt("pid");item.pid=scalar(rawPid);
            if((rawPid!=null&&rawPid!=JSONObject.NULL&&!(rawPid instanceof String)&&!(rawPid instanceof Number))||(!item.pid.isEmpty()&&!id(item.pid,true)))throw invalid();
            JSONObject reply=null;
            // Official fastPostLoader uses __P.pid/postdate for author-reply results.
            // This envelope is not a bookmark locator and must not affect cloud favorites.
            if(authorReplies&&row.has("__P")) {
                reply=row.optJSONObject("__P");if(reply==null)throw invalid();guard(reply,200);
                Object replyPid=reply.opt("pid");String locator=scalar(replyPid);
                if(!(replyPid instanceof String)&&!(replyPid instanceof Number))throw invalid();
                if(!id(locator,false)||(!item.pid.isEmpty()&&!item.pid.equals("0")&&!item.pid.equals(locator)))throw invalid();
                item.pid=locator;
            }
            item.fid=scalar(row.opt("fid"));item.title=plain(row.opt("subject"));
            item.author=plain(row.opt("author"));item.authorId=scalar(row.opt("authorid"));item.replies=Math.max(0,row.optInt("replies",0));
            JSONObject user=users==null?null:users.optJSONObject(item.authorId);item.avatar=avatar(user==null?null:user.opt("avatar"));if(item.avatar.isEmpty())item.avatar=avatar(row.opt("avatar"));
            item.votes=VoteMetrics.topicFrom(row);
            item.timestamp=timestamp(reply!=null?reply.opt("postdate"):row.has("lastpost")?row.opt("lastpost"):row.opt("postdate"));item.time=time(item.timestamp);result.items.add(item);
        }
        if(result.total>=0){result.totalPages=Math.max(result.page,(int)Math.min(100000L,((long)result.total+result.rowsPerPage-1)/result.rowsPerPage));result.hasNext=result.page<result.totalPages;}
        else {result.hasNext=sourceRows.size()>=result.rowsPerPage;result.totalPages=result.page+(result.hasNext?1:0);}
        result.nextPage=result.hasNext?result.page+1:0;return result;
    }
    private static MutationResult confirm(JSONObject root,String kind) throws Exception {
        Object value=payload(root);if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value;guard(object,200);value=object.has("message")?object.opt("message"):object.opt("0");
        }
        String ack=value instanceof String?plain(value):"";
        String words="message".equals(kind)?"发送完毕|发送成功|操作成功":"remove".equals(kind)?"删除成功|取消收藏成功|操作成功":"收藏成功|添加成功|操作成功|已收藏|已经收藏";
        if(!ack.matches("(?s)^(?:"+words+")(?:[\\s.。!！…，,:：]|$).*")||ack.matches("(?s).*(?:失败|未成功|未完成|不能|错误).*") )throw new CommunityException(Code.UNCONFIRMED,200);
        MutationResult result=new MutationResult();result.confirmed=true;result.message="message".equals(kind)?"服务器已确认发送":"服务器已确认收藏操作";return result;
    }
    /** Official no-message response: an explicit first data slot containing exactly an empty string. */
    private static boolean emptyReminderPayload(JSONObject root) throws CommunityException {
        guard(root,200);Object data=root.opt("data"),first;
        if(data instanceof JSONObject){JSONObject envelope=(JSONObject)data;guard(envelope,200);if(!envelope.has("0"))return false;first=envelope.opt("0");}
        else if(data instanceof JSONArray){JSONArray envelope=(JSONArray)data;if(envelope.length()==0)return false;first=envelope.opt(0);}
        else return false;
        return first instanceof String&&((String)first).isEmpty();
    }
    private static Object payload(JSONObject root) throws Exception {
        guard(root,200);if(!root.has("data")||root.isNull("data"))throw invalid();Object value=root.opt("data");
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;if(array.length()==0)throw invalid();value=array.opt(0);}
        else if(value instanceof JSONObject&&((JSONObject)value).has("0"))value=((JSONObject)value).opt("0");
        if(value instanceof JSONObject)guard((JSONObject)value,200);if(value==null||value==JSONObject.NULL)throw invalid();return value;
    }
    private static JSONObject object(Object value) throws CommunityException {if(!(value instanceof JSONObject))throw invalid();return (JSONObject)value;}
    private static List<JSONObject> rows(Object value) throws CommunityException {
        List<JSONObject> result=new ArrayList<>();
        if(value instanceof JSONArray) {
            JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)result.add(object(array.opt(i)));
        } else if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value;guard(object,200);
            List<String> numeric=keys(object,true);if(numeric.isEmpty()&&object.length()>0)throw invalid();
            for(String key:numeric)result.add(object(object.opt(key)));
        } else throw invalid();return result;
    }
    private static List<JSONObject> messageRows(JSONObject block) throws CommunityException {
        if(!keys(block,true).isEmpty())return rows(block);
        for(String key:keys(block,false))if(!key.matches("currentPage|nextPage|rowsPerPage|totalRows|unread"))throw invalid();
        return new ArrayList<>();
    }
    private static List<String> keys(JSONObject object,boolean numeric) {
        List<String> result=new ArrayList<>();Iterator<String> iterator=object.keys();
        while(iterator.hasNext()){String key=iterator.next();if(!numeric||key.matches("[0-9]{1,8}"))result.add(key);}
        if(numeric)Collections.sort(result,(a,b)->Integer.compare(Integer.parseInt(a),Integer.parseInt(b)));else Collections.sort(result);return result;
    }
    private static void requireMessagePage(JSONObject value) throws CommunityException {
        if(!value.has("currentPage")||(!value.has("nextPage")&&!value.has("rowsPerPage")))throw invalid();
    }
    private static int currentPage(JSONObject value,int fallback) {
        int result=value.optInt("currentPage",fallback);page(result);return result;
    }
    private static int nextPage(JSONObject value,int current) {
        int result=value.optInt("nextPage",0);return result>current&&result<=100000?result:0;
    }
    private static int perPage(JSONObject value,int fallback){return Math.max(1,Math.min(200,value.optInt("rowsPerPage",fallback)));}
    private static void guard(JSONObject object,int status) throws CommunityException {
        if(object.has("error")&&!object.isNull("error"))throw new CommunityException(classify(errorWords(object.opt("error"))),status);
    }
    private static String errorWords(Object value) {
        if(value instanceof String||value instanceof Number)return String.valueOf(value);
        StringBuilder result=new StringBuilder();
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length()&&result.length()<4096;i++)result.append(errorWords(a.opt(i))).append(' ');}
        else if(value instanceof JSONObject){JSONObject o=(JSONObject)value;for(String key:keys(o,false))if(key.matches("[0-9]+|message|msg"))result.append(errorWords(o.opt(key))).append(' ');}
        return result.length()>4096?result.substring(0,4096):result.toString();
    }
    private static Code classify(String text) {
        String s=text.toLowerCase(Locale.ROOT);
        if(s.matches("(?s).*(?:验证码|人机|安全验证|challenge|captcha|cloudflare|访问验证).*"))return Code.CHALLENGE_REQUIRED;
        if(s.matches("(?s).*(?:未登录|请.*登录|必须登录|重新登录|login|not.?logged).*"))return Code.LOGIN_REQUIRED;
        if(s.matches("(?s).*(?:频繁|冷却|24小时|次数|超过|too many|rate.limit).*"))return Code.RATE_LIMIT;
        if(s.matches("(?s).*(?:权限|无权|禁止|permission|forbidden|不能发送).*"))return Code.PERMISSION_DENIED;
        return Code.SERVER;
    }
    private static String errorText(Code code) {
        switch(code){case LOGIN_REQUIRED:return "请先登录 NGA，或重新验证已过期的会话";case CHALLENGE_REQUIRED:return "服务器要求验证码或安全验证，原生请求尚未完成";
            case PERMISSION_DENIED:return "服务器拒绝访问，请检查账号权限";case RATE_LIMIT:return "操作过于频繁或额度已用完，请稍后重试";
            case NETWORK:return "网络连接失败，请稍后重试";case UNCONFIRMED:return "服务器尚未明确确认操作结果，请核对后再决定是否重试";
            case UNSUPPORTED:return "当前接口或线路不受支持";case INVALID_RESPONSE:return "服务器返回了无法识别的数据，未将其当作空列表或成功";
            default:return "服务器返回错误，操作未完成";}
    }
    private static CommunityException invalid(){return new CommunityException(Code.INVALID_RESPONSE,0);}
    private static Map<String,String> base(){Map<String,String> p=new LinkedHashMap<>();p.put("__output","8");p.put("__inchst","UTF8");return p;}
    private static Map<String,String> nuke(String lib,String act){Map<String,String> p=base();p.put("__lib",lib);p.put("__act",act);return p;}
    private static Map<String,String> message(String action){Map<String,String> p=nuke("message","message");p.put("act",action);p.put("lite","js");p.put("noprefix","");return p;}
    private static String scalar(Object value){return value instanceof String||value instanceof Number?String.valueOf(value):"";}
    private static boolean flag(Object value){return Boolean.TRUE.equals(value)||(value instanceof Number&&((Number)value).intValue()!=0)||"1".equals(value)||"true".equals(value);}
    private static String plain(Object value){return Html.fromHtml(scalar(value),Html.FROM_HTML_MODE_LEGACY).toString().trim();}
    private static boolean id(String value,boolean zero){return value!=null&&value.matches(zero?"(?:0|[1-9][0-9]{0,17})":"[1-9][0-9]{0,17}");}
    private static void number(String value,boolean zero){if(!id(value,zero))throw new IllegalArgumentException("无效的 NGA 编号");}
    private static void page(int value){if(value<1||value>100000)throw new IllegalArgumentException("无效页码");}
    private static void bounded(String value,int min,int max,String message){if(value==null||value.trim().length()<min||value.length()>max||value.indexOf('\0')>=0)throw new IllegalArgumentException(message);}
    private static long timestamp(Object value){try{long n=Long.parseLong(scalar(value));return n>0&&n<=253402300799L?n:0;}catch(NumberFormatException bad){return 0;}}
    private static String time(long seconds){if(seconds==0)return "";SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.SIMPLIFIED_CHINESE);f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));return f.format(new java.util.Date(seconds*1000));}
    private static String avatar(Object value){return AvatarSources.normalize(value instanceof JSONObject||value instanceof JSONArray?value.toString():scalar(value));}
    private static String safeCookie(String value) throws CommunityException {
        if(value==null)return "";if(value.length()>65536||value.indexOf('\r')>=0||value.indexOf('\n')>=0||value.indexOf('\0')>=0)throw new CommunityException(Code.LOGIN_REQUIRED,0);return value;
    }
    private static String cookieValue(String cookies,String name){for(String part:cookies.split(";")){int at=part.indexOf('=');if(at>0&&part.substring(0,at).trim().equals(name))return part.substring(at+1).trim();}return "";}
    static String scopedCookie(String actualHost,String expectedHost,String expectedAccount,String cookies){
        if(cookies==null||!expectedHost.equals(actualHost))return "";
        String uid=cookieValue(cookies,"ngaPassportUid");if(!id(uid,false))uid=cookieValue(cookies,"access_uid");
        return expectedAccount.equals(uid)?cookies:"";
    }
    private static boolean authenticated(String cookie){return (!cookieValue(cookie,"ngaPassportUid").isEmpty()&&!cookieValue(cookie,"ngaPassportCid").isEmpty())||(!cookieValue(cookie,"access_uid").isEmpty()&&!cookieValue(cookie,"access_token").isEmpty());}
    private static int rootEnd(String raw) throws CommunityException {
        int depth=0;boolean string=false,escaped=false;
        for(int i=0;i<raw.length();i++){char c=raw.charAt(i);if(string){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')string=false;}
            else if(c=='"')string=true;else if(c=='{'||c=='['){if(++depth>128)throw invalid();}else if(c=='}'||c==']'){if(--depth==0)return i+1;if(depth<0)throw invalid();}}
        throw invalid();
    }
    private static String quotedControls(String raw){
        StringBuilder out=new StringBuilder(raw.length());boolean string=false,escaped=false;
        for(int i=0;i<raw.length();i++){char c=raw.charAt(i);if(string&&!escaped&&c<32){if(c=='\t')out.append("\\t");else if(c=='\n')out.append("\\n");else if(c=='\r')out.append("\\r");else out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));continue;}
            out.append(c);if(string){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')string=false;}else if(c=='"')string=true;}
        return out.toString();
    }
    private static String form(Map<String,String> values) throws IOException {
        StringBuilder out=new StringBuilder();for(Map.Entry<String,String> pair:values.entrySet()){if(out.length()>0)out.append('&');out.append(URLEncoder.encode(pair.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(pair.getValue(),"UTF-8"));}return out.toString();
    }
    private static final class HttpsTransport implements Transport {
        @Override public Response execute(Request request,String cookie) throws IOException {
            if(!request.mutation)RequestCancellation.check();
            String query=form(request.queryParameters);URL url=new URL("https://"+request.host+"/"+request.path+(query.isEmpty()?"":"?"+query));
            HttpURLConnection c=(HttpURLConnection)url.openConnection();RequestCancellation.Registration cancellation=null;
            try {
                // Explicit writes keep their delivery/read-back lifetime; only disposable reads bind sockets.
                if(!request.mutation)cancellation=RequestCancellation.track(c);
                c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(20000);
                c.setRequestMethod(request.method);c.setRequestProperty("User-Agent","NGA_WP_JW");c.setRequestProperty("Accept","application/json,text/plain;q=0.9");c.setRequestProperty("Referer","https://"+request.host+"/");
                if(!cookie.isEmpty())c.setRequestProperty("Cookie",cookie);
                if("POST".equals(request.method)){if(!request.mutation)RequestCancellation.check();byte[] bytes=form(request.bodyParameters).getBytes(StandardCharsets.UTF_8);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
                int status=c.getResponseCode();if(!request.mutation)RequestCancellation.check();InputStream stream=status>=400?c.getErrorStream():status>=300&&status<400?null:c.getInputStream();
                byte[] bytes=new byte[0];if(stream!=null)try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[8192];while(true){if(!request.mutation)RequestCancellation.check();int n=in.read(buffer);if(!request.mutation)RequestCancellation.check();if(n==-1)break;if(out.size()+n>MAX_BYTES)throw invalid();out.write(buffer,0,n);}bytes=out.toByteArray();
                }
                return new Response(status,bytes,c.getContentType());
            } finally {if(cancellation!=null)cancellation.close();c.disconnect();}
        }
    }
}
