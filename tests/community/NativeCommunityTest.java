package cn.ngapure.app;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import org.json.JSONObject;

/** Synthetic data and an in-memory transport only. Never connects to an NGA account. */
public final class NativeCommunityTest {
    private static int checks;
    private static Path fixtures;
    private static final class TestSession implements NativeCommunity.Session {
        String host="bbs.nga.cn",cookie="ngaPassportUid=70001; ngaPassportCid=fixture-only";
        public String host(){return host;} public String cookie(){return cookie;}
    }
    private static final class Fake implements NativeCommunity.Transport {
        final Deque<NativeCommunity.Response> responses=new ArrayDeque<>();
        NativeCommunity.Request last;int calls;boolean fail;
        Fake fixture(String name) throws Exception {responses.add(new NativeCommunity.Response(200,Files.readAllBytes(fixtures.resolve(name)),"application/json; charset=UTF-8"));return this;}
        Fake json(String body){responses.add(new NativeCommunity.Response(200,body.getBytes(StandardCharsets.UTF_8),"application/json"));return this;}
        Fake raw(int status,String body,String charset){responses.add(new NativeCommunity.Response(status,body.getBytes(Charset.forName(charset)),"application/json; charset="+charset));return this;}
        @Override public NativeCommunity.Response execute(NativeCommunity.Request request,String cookie) throws IOException {
            calls++;last=request;if(fail)throw new IOException("synthetic interrupted transport");
            if(responses.isEmpty())throw new AssertionError("Unexpected fixture request");return responses.removeFirst();
        }
    }
    private interface Action {void run() throws Exception;}
    private static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void code(NativeCommunity.Code expected,Action action,String label) throws Exception {
        try {action.run();throw new AssertionError(label+": expected error");}
        catch(NativeCommunity.CommunityException error){ok(error.code==expected,label);ok(!error.getMessage().contains("fixture-only"),label+" safe exception");}
    }
    private static void invalid(Action action,String label) throws Exception {
        try {action.run();throw new AssertionError(label+": expected validation");}
        catch(IllegalArgumentException expected){checks++;}
    }
    public static void main(String[] args) throws Exception {
        fixtures=Paths.get(args[0]);TestSession session=new TestSession();Fake fake=new Fake();NativeCommunity api=new NativeCommunity(session,fake);
        ok(session.cookie.equals(NativeCommunity.scopedCookie("bbs.nga.cn","bbs.nga.cn","70001",session.cookie)),"same scoped account can retain its credentials");
        ok(NativeCommunity.scopedCookie("bbs.nga.cn","bbs.nga.cn","70002",session.cookie).isEmpty(),"switched account cannot be used by old view");
        ok(NativeCommunity.scopedCookie("ngabbs.com","bbs.nga.cn","70001",session.cookie).isEmpty(),"switched route cannot be used by old view");
        ok(NativeCommunity.scopedCookie("bbs.nga.cn","bbs.nga.cn","70001","access_uid=70001; access_token=fixture").contains("access_uid"),"scoped access session supported");
        ok(NativeCommunity.scopedCookie("bbs.nga.cn","bbs.nga.cn","70001",null).isEmpty(),"missing scoped credentials rejected");
        fake.json("{\"data\":{\"0\":{\"act_url\":\"nul\",\"icon\":{\"android_xxxh\":\"nul\"},\"start\":0,\"end\":1}}}");ok(!api.officialActivity().present,"official nul activity entry is an explicit empty state, not recommended keyword matches");
        fake.json("{\"data\":[{\"act_url\":\"/misc/event/fixture/index.html\",\"icon\":{\"android_xxxh\":\"https://img4.nga.cn/attachments/event.png\"},\"start\":1760000000,\"end\":1761000000}]}");NativeCommunity.OfficialActivity event=api.officialActivity();ok(event.present&&event.url.equals("https://bbs.nga.cn/misc/event/fixture/index.html")&&!event.icon.isEmpty(),"official native activity entry preserves safe destination and real icon");ok("nga_index".equals(fake.last.parameters.get("__lib"))&&"get_event_app".equals(fake.last.parameters.get("__act"))&&!fake.last.mutation,"official Android activity directory protocol");
        fake.json("{\"data\":[{\"act_url\":\"javascript:alert(1)\"}]}");code(NativeCommunity.Code.INVALID_RESPONSE,api::officialActivity,"non-HTTPS event destination rejected");
        String feed="{\"data\":[{\"0\":[101,1,70002,91001,0,0,1760000000],\"1\":[102,2,70002,91001,92001,0,1760000010],\"2\":[103,3,70003,91002,92002,70002,1760000020]},{\"70002\":{\"username\":\"fixture actor\",\"avatar\":\".a/70002_0.jpg\"},\"70003\":{\"username\":\"fixture favorer\"}},2,1,{\"91001\":{\"subject\":\"fixture topic A\"},\"91002\":{\"subject\":\"fixture topic B\"}}]}";
        fake.json(feed);NativeCommunity.FollowActivityPage following=api.followingActivity(1);ok(following.items.size()==3&&following.hasNext&&following.nextPage==2&&following.totalPages==2,"official user-follow activity paginates independently of favorite forums");ok(following.items.get(1).pid.equals("92001")&&following.items.get(2).actorUid.equals("70003")&&following.items.get(2).pid.equals("92002"),"reply and favorite events retain exact post locator and acting user");ok(!following.items.get(0).actorAvatar.isEmpty()&&following.items.get(0).time.startsWith("2025"),"following activity retains real public avatar metadata and timestamp");ok("follow_v2".equals(fake.last.parameters.get("__lib"))&&"get_push_list".equals(fake.last.parameters.get("__act"))&&!fake.last.mutation&&"POST".equals(fake.last.method),"following feed uses official authenticated read-only form");
        fake.json("{\"data\":[[],{},0,1,{}]}");following=api.followingActivity(1);ok(following.items.isEmpty()&&!following.hasNext,"explicit empty follow activity is not an error");
        fake.json(feed.replace("2,1,{\"91001\"","2,2,{\"91001\""));code(NativeCommunity.Code.INVALID_RESPONSE,()->api.followingActivity(1),"mismatched activity current page is rejected");
        fake.fixture("reminders.json");NativeCommunity.ReminderPage reminders=api.reminders(1759999999L);
        ok(reminders.items.size()==2&&reminders.unread==2,"reminder kinds and aggregate count");
        ok("reply".equals(reminders.items.get(0).category)&&"message".equals(reminders.items.get(1).category),"reply versus PM signal");
        ok(reminders.timeLimit==1760000000L&&!reminders.items.get(0).unread,"cursor and no invented per-item unread");
        ok("测试主题 & 引用".equals(reminders.items.get(0).title),"title text decoding");
        ok("noti".equals(fake.last.parameters.get("__lib"))&&"get_all".equals(fake.last.parameters.get("__act")),"reminder protocol");
        fake.fixture("conversations.json");NativeCommunity.ConversationPage list=api.conversations(2);
        ok(list.items.size()==2&&"88001".equals(list.items.get(0).mid),"sparse numeric list sorted");
        ok(list.page==2&&list.nextPage==3&&list.hasNext&&list.rowsPerPage==20,"conversation real paging");
        ok(list.items.get(0).unread&&list.items.get(0).timestamp==1760000000L,"conversation metadata");
        ok("list".equals(fake.last.parameters.get("act"))&&!fake.last.parameters.containsKey("action"),"legacy PM uses act");
        fake.fixture("conversations-last-page.json");NativeCommunity.ConversationPage last=api.conversations(1);
        ok(last.items.size()==1&&!last.hasNext&&last.nextPage==0,"verified PM last page omits nextPage");
        fake.json("{\"data\":{\"0\":{\"0\":{\"mid\":88001,\"subject\":\"合成标题\",\"unread\":1},\"currentPage\":1,\"rowsPerPage\":20}}}");ok(api.conversations(1).items.get(0).unread,"explicit numeric unread supported without interpreting undocumented bits");
        fake.json("{\"data\":{\"0\":{\"currentPage\":1,\"rowsPerPage\":20}}}");ok(api.conversations(1).items.isEmpty(),"known explicit last page metadata permits no rows");
        fake.fixture("messages.json");NativeCommunity.MessagePage messages=api.conversation("88001",2);
        ok(messages.items.size()==2&&messages.participants.size()==2,"messages and participants");
        ok(messages.page==2&&!messages.hasNext&&messages.items.get(0).floor==21,"conversation body paging");
        ok(messages.items.get(0).content.startsWith("[b]")&&messages.items.get(0).content.contains("\n"),"BBCode body remains data");
        ok(!messages.items.get(0).avatar.isEmpty()&&messages.items.get(1).avatar.isEmpty(),"avatar host validation");
        ok("read".equals(fake.last.parameters.get("act"))&&"88001".equals(fake.last.parameters.get("mid")),"read protocol");
        fake.fixture("profile.json");NativeCommunity.UserProfile profile=api.user("70001");
        ok(profile.posts==314&&profile.muted&&!profile.nuked,"public profile metadata");
        ok(profile.registeredTimestamp==1500000000L&&!profile.registeredAt.isEmpty(),"registration time");
        ok(profile.reputation.contains("10")&&profile.signature.contains("[b]"),"profile text and reputation");
        fake.fixture("profile.json");api.findUser("合成用户");ok(fake.last.parameters.containsKey("username")&&!fake.last.parameters.containsKey("uid"),"name lookup");
        fake.fixture("profile.json");api.self();ok("70001".equals(fake.last.parameters.get("uid")),"self uses existing session in memory");
        fake.fixture("topics.json");NativeCommunity.TopicPage topics=api.userTopics("70001",2);
        ok(topics.items.size()==2&&topics.totalPages==3&&topics.hasNext&&topics.nextPage==3,"user topic pagination");
        ok(!fake.last.parameters.containsKey("searchpost")&&fake.last.parameters.containsKey("authorid")&&fake.last.path.equals("thread.php"),"author topic protocol excludes replies flag");
        fake.fixture("topics.json");api.userReplies("70001",2);ok("1".equals(fake.last.parameters.get("searchpost")),"author reply search is explicit");
        // Public anonymous author searches return this official GBK refusal envelope.
        // Its login requirement must remain actionable, never a generic server error or an empty list.
        String authorLoginRequired="{\"error\":{\"0\":\"2048:你必须登录\",\"1\":\"\",\"2\":\"\"},\"data\":{\"__MESSAGE\":{\"0\":2048,\"1\":\"你必须登录\",\"3\":200}}}";
        fake.raw(200,authorLoginRequired,"GB18030");code(NativeCommunity.Code.LOGIN_REQUIRED,()->api.userTopics("70001",1),"official author-topic login refusal is classified precisely");
        fake.raw(200,authorLoginRequired,"GB18030");code(NativeCommunity.Code.LOGIN_REQUIRED,()->api.userReplies("70001",1),"official author-reply login refusal is classified precisely");
        fake.json("{\"error\":[\"合成作者查询拒绝\"],\"data\":{\"__T\":[]}}");code(NativeCommunity.Code.SERVER,()->api.userTopics("70001",1),"unrecognized author refusal remains a server error rather than successful emptiness");
        String authorReplyPage="{\"data\":{\"__T\":[{\"tid\":91001,\"subject\":\"合成主题\",\"lastpost\":1750000000,\"__P\":{\"pid\":92005,\"postdate\":1760000000}}]}}";
        fake.json(authorReplyPage);topics=api.userReplies("70001",1);
        ok(topics.items.get(0).pid.equals("92005")&&topics.items.get(0).timestamp==1760000000L,"official nested author reply retains its exact post locator and reply time");
        fake.json(authorReplyPage);topics=api.favorites("1",1);
        ok(topics.items.get(0).pid.isEmpty()&&topics.items.get(0).timestamp==1750000000L,"author-reply envelope cannot manufacture a cloud bookmark post locator or time");
        fake.json(authorReplyPage);topics=api.userTopics("70001",1);
        ok(topics.items.get(0).pid.isEmpty()&&topics.items.get(0).timestamp==1750000000L,"ordinary author topics retain their topic-only interpretation");
        for(String badReply:new String[]{"null","[]","true","{}","{\"pid\":true}","{\"pid\":0}","{\"pid\":-1}","{\"pid\":\"1.5\"}","{\"pid\":\"1&tid=2\"}"}){
            fake.json("{\"data\":{\"__T\":[{\"tid\":91001,\"subject\":\"合成主题\",\"__P\":"+badReply+"}]}}");
            code(NativeCommunity.Code.INVALID_RESPONSE,()->api.userReplies("70001",1),"unverified nested author reply locator is rejected");
        }
        for(String topPid:new String[]{"0","92005"}){
            fake.json(authorReplyPage.replace("\"lastpost\":1750000000","\"pid\":"+topPid+",\"lastpost\":1750000000"));
            ok(api.userReplies("70001",1).items.get(0).pid.equals("92005"),"compatible topic or identical top-level locator retains verified nested reply");
        }
        fake.json(authorReplyPage.replace("\"lastpost\":1750000000","\"pid\":92006,\"lastpost\":1750000000"));
        code(NativeCommunity.Code.INVALID_RESPONSE,()->api.userReplies("70001",1),"conflicting reply locators fail closed rather than navigating to another floor");
        fake.json(authorReplyPage.replace("\"lastpost\":1750000000","\"pid\":92006,\"lastpost\":1750000000"));
        ok(api.favorites("1",1).items.get(0).pid.equals("92006"),"cloud favorites retain their own explicit top-level locator despite nested author metadata");
        fake.json(authorReplyPage.replace(",\"postdate\":1760000000",""));
        ok(api.userReplies("70001",1).items.get(0).timestamp==0L,"unknown reply date does not inherit an unrelated topic update time");
        fake.json(authorReplyPage.replace("\"pid\":92005","\"error\":[\"你必须登录\"],\"pid\":92005"));
        ok(api.userReplies("70001",1).unavailableItems==1,"one restricted author reply is omitted without failing the whole author page");
        fake.json("{\"data\":{\"__T\":[{\"error\":[\"访问条件不足\"]},{\"tid\":91001,\"subject\":\"可见主题\"}],\"__T__ROWS_PAGE\":2}}");
        topics=api.userTopics("70001",1);ok(topics.items.size()==1&&topics.unavailableItems==1&&topics.hasNext,"restricted author-topic row preserves other rows and source-page pagination");
        fake.json("{\"data\":{\"__T\":[{\"tid\":91001,\"subject\":\"受限回复\",\"__P\":{\"error\":[\"权限不足\"]}},{\"tid\":91002,\"subject\":\"公开回复\",\"__P\":{\"pid\":92002}}],\"__ROWS\":2}}");
        topics=api.userReplies("70001",1);ok(topics.items.size()==1&&topics.items.get(0).pid.equals("92002")&&topics.unavailableItems==1,"restricted nested reply does not hide a visible reply");
        fake.json("{\"data\":{\"__T\":[{\"error\":[\"权限不足\"]}]}}");
        code(NativeCommunity.Code.PERMISSION_DENIED,()->api.favorites("1",1),"favorites retain strict errors rather than silently discarding cloud entries");
        fake.fixture("topics.json");topics=api.favorites("1",2);ok("1".equals(fake.last.parameters.get("favor"))&&"92003".equals(topics.items.get(1).pid),"server favorite posts retain pid");
        fake.fixture("topics-person-metrics.json");topics=api.favorites("1",1);
        ok(topics.items.get(0).avatar.equals("https://img4.nga.cn/attachments/fixture-person.png"),"topic author avatar comes from response user table and normalizes protocol-relative source");
        ok(topics.items.get(1).avatar.equals("https://img8.nga.cn/attachments/fixture-person-b.png"),"explicit row avatar remains usable without another profile request");
        ok(topics.items.get(2).avatar.isEmpty(),"absent avatar stays unknown without a fabricated portrait");
        ok(topics.items.get(0).votes.likesKnown&&topics.items.get(0).votes.likes==7&&topics.items.get(0).votes.dislikesKnown&&topics.items.get(0).votes.dislikes==5,"explicit up/down counts are parsed without confusing net score");
        ok(!topics.items.get(1).votes.likesKnown&&!topics.items.get(1).votes.dislikesKnown&&!topics.items.get(1).votes.scoreKnown,"topic scalar recommendation remains unknown rather than an unproven net score");
        ok(!topics.items.get(2).votes.likesKnown&&!topics.items.get(2).votes.scoreKnown,"absent metrics remain unknown rather than zero");
        for(String badPid:new String[]{"-1","1.5","not-a-post","1&tid=2"}){
            JSONObject entry=new JSONObject().put("tid","91001").put("pid",badPid).put("subject","合成收藏");fake.json(new JSONObject().put("data",new JSONObject().put("__T",new org.json.JSONArray().put(entry))).toString());
            code(NativeCommunity.Code.INVALID_RESPONSE,()->api.favorites("1",1),"unverified bookmark post locator is rejected");
        }
        fake.json("{\"data\":{\"__T\":[{\"tid\":91001,\"pid\":true,\"subject\":\"合成收藏\"}]}}");code(NativeCommunity.Code.INVALID_RESPONSE,()->api.favorites("1",1),"non-scalar bookmark locator is never ignored as a main-topic bookmark");
        fake.json("{\"data\":{\"__T\":[{\"tid\":91001,\"pid\":0,\"subject\":\"合成主题收藏\"}]}}");ok(api.favorites("1",1).items.get(0).pid.equals("0"),"official numeric zero remains a main-topic bookmark");
        fake.fixture("folders.json");NativeCommunity.FolderPage folders=api.favoriteFolders(1);
        ok(folders.items.size()==2&&folders.hasNext&&folders.items.get(1).publicFolder,"explicit folder container");
        ok("POST".equals(fake.last.method)&&!fake.last.mutation,"official folder lookup is a read-only POST");
        ok("3".equals(fake.last.bodyParameters.get("__output"))&&"1".equals(fake.last.bodyParameters.get("page")),"official output3 and requested page in form");
        ok("topic_favor_v2".equals(fake.last.queryParameters.get("__lib"))&&"list_folder".equals(fake.last.queryParameters.get("__act"))&&fake.last.queryParameters.size()==2,"official read routing remains in query");
        ok(!fake.last.bodyParameters.containsKey("__lib")&&!fake.last.bodyParameters.containsKey("__act"),"routing is not duplicated into form");
        fake.fixture("folders-official.json");folders=api.favoriteFolders(2);
        ok(folders.items.size()==2&&folders.page==2&&folders.nextPage==3&&folders.hasNext,"official data0 collection and data1 more flag");
        ok(folders.items.get(0).defaultFolder&&!folders.items.get(0).publicFolder,"default bit is not public bit");
        ok(folders.items.get(1).publicFolder&&!folders.items.get(1).defaultFolder,"official type bit1 and default field");
        fake.json("{\"data\":[[{\"id\":71001,\"name\":\"合成收藏夹\",\"type\":0,\"default\":true}],0]}");folders=api.favoriteFolders(1);
        ok(folders.items.size()==1&&!folders.hasNext&&folders.nextPage==0,"official array envelope last page");
        fake.json("window.script_muti_get_var_store={\"data\":[{},0]};");ok(api.favoriteFolders(1).items.isEmpty(),"explicit official output3 empty collection");
        fake.json("{\"data\":[[],false]}");ok(!api.favoriteFolders(1).hasNext,"explicit official empty array last page");
        fake.fixture("folders-output3.html");ok(api.favoriteFolders(1).items.isEmpty(),"exact official output3 HTML shell is parsed, never executed");
        fake.json("<html><head><meta http-equiv='Content-Type' content='text/html; charset=GBK'></head><body><script>window.script_muti_get_var_store={\"data\":[[],0]};alert(1)</script></body></html>");
        code(NativeCommunity.Code.INVALID_RESPONSE,()->api.favoriteFolders(1),"output3 trailing executable code is rejected");
        fake.json("<html><body><script>window.script_muti_get_var_store={\"data\":[[],0]}</script><script>alert(1)</script></body></html>");
        code(NativeCommunity.Code.CHALLENGE_REQUIRED,()->api.favoriteFolders(1),"arbitrary HTML cannot impersonate official single-script shell");
        fake.json("{\"error\":[\"合成服务器拒绝\"],\"data\":[[],0]}");code(NativeCommunity.Code.SERVER,()->api.favoriteFolders(1),"folder SERVER never becomes empty");
        fake.json("{\"data\":[[],{\"unexpected\":true}]}");code(NativeCommunity.Code.INVALID_RESPONSE,()->api.favoriteFolders(1),"unknown folder pagination is not ignored");
        fake.fixture("empty-conversations.json");ok(api.conversations(1).items.isEmpty(),"explicit empty conversation page");
        fake.fixture("empty-topics.json");ok(api.favorites("1",1).items.isEmpty(),"explicit empty favorites page");
        fake.json("{\"data\":{\"0\":{\"0\":[],\"1\":[],\"unread\":0}}}");ok(api.reminders(0).items.isEmpty(),"explicit empty reminders");
        fake.fixture("empty-reminders-official.json");reminders=api.reminders(1759999999L);
        ok(reminders.items.isEmpty()&&reminders.unread==0,"verified official empty-string reminder slot is empty");
        ok(reminders.timeLimit==1759999999L,"empty reminders retain the supplied cursor without inventing message times");
        fake.json("{\"data\":[\"\"],\"time\":1760000000}");ok(api.reminders(0).items.isEmpty(),"explicit array first-slot empty string follows the same envelope");
        fake.responses.add(new NativeCommunity.Response(200,"{\"data\":{\"0\":\"\"},\"time\":1760000000}".getBytes(Charset.forName("GB18030")),"text/javascript; charset=GBK"));
        ok(api.reminders(0).items.isEmpty(),"verified response content type and GBK decoding retain exact empty slot");
        fake.json("window.script_muti_get_var_store={\"data\":{\"0\":\"\"},\"time\":1760000000};");
        ok(api.reminders(0).items.isEmpty(),"known legacy assignment wrapper retains empty reminder slot");
        for(String broken:new String[]{"{}","{\"data\":null}","{\"data\":[]}","{\"data\":\"\"}","{\"data\":{}}","{\"data\":{\"unread\":0}}","{\"data\":{\"0\":null}}","{\"data\":{\"0\":0}}","{\"data\":{\"0\":false}}","{\"data\":{\"0\":true}}","{\"data\":{\"0\":[]}}","{\"data\":{\"0\":{}}}"}){
            fake.json(broken);code(NativeCommunity.Code.INVALID_RESPONSE,()->api.reminders(0),"missing or unverified reminder emptiness remains invalid");
        }
        for(String nonempty:new String[]{" ","0","null","not empty","<script>untrusted</script>"}){
            JSONObject envelope=new JSONObject();envelope.put("0",nonempty);fake.json(new JSONObject().put("data",envelope).toString());
            code(NativeCommunity.Code.INVALID_RESPONSE,()->api.reminders(0),"nonempty first-slot text is not silently an empty reminder list");
        }
        for(String error:new String[]{"{\"error\":[],\"data\":{\"0\":\"\"}}","{\"data\":{\"error\":[\"合成拒绝\"],\"0\":\"\"}}"}){
            fake.json(error);code(NativeCommunity.Code.SERVER,()->api.reminders(0),"errors take precedence over verified empty slot");
        }
        fake.json("{\"error\":[\"请先登录\"],\"data\":{\"0\":\"\"}}");code(NativeCommunity.Code.LOGIN_REQUIRED,()->api.reminders(0),"login refusal cannot become empty reminders");
        fake.raw(500,"{\"data\":{\"0\":\"\"}}","UTF-8");code(NativeCommunity.Code.SERVER,()->api.reminders(0),"HTTP failure cannot become empty reminders");
        fake.raw(429,"{\"data\":{\"0\":\"\"}}","UTF-8");code(NativeCommunity.Code.RATE_LIMIT,()->api.reminders(0),"rate limit cannot become empty reminders");
        fake.json("{\"data\":{\"0\":{\"2\":[]}}}");code(NativeCommunity.Code.INVALID_RESPONSE,()->api.reminders(0),"system-only category remains outside this minimal empty-response change");
        fake.json("{\"data\":{\"0\":{\"0\":[null]}}}");code(NativeCommunity.Code.INVALID_RESPONSE,()->api.reminders(0),"row holes are not generalized into empty lists");
        fake.fixture("empty-reminders-official.json");code(NativeCommunity.Code.INVALID_RESPONSE,()->api.conversations(1),"reminder empty-string exception does not apply to conversation pages");

        fake.json("{\"data\":[]}");ok(api.favoriteFolders(1).items.isEmpty(),"explicit empty folder array");
        fake.fixture("error-login.json");code(NativeCommunity.Code.LOGIN_REQUIRED,()->api.conversations(1),"error array overrides data");
        fake.fixture("error-permission.json");code(NativeCommunity.Code.PERMISSION_DENIED,()->api.conversation("88001",1),"permission error object");
        fake.fixture("error-rate.json");code(NativeCommunity.Code.RATE_LIMIT,()->api.sendMessage("70002","合成标题","合成正文"),"rate limit is not success");
        fake.fixture("challenge.html");code(NativeCommunity.Code.CHALLENGE_REQUIRED,()->api.conversations(1),"HTML challenge native state");
        for(String broken:new String[]{"{}","{\"data\":[]}","{\"data\":{\"0\":{\"unexpected\":true}}}","{\"error\":[]}"}) {
            fake.json(broken);code(broken.contains("error")?NativeCommunity.Code.SERVER:NativeCommunity.Code.INVALID_RESPONSE,()->api.conversations(1),"missing or error envelope rejected");
        }
        fake.json("{\"data\":{\"__T\":{\"unexpected\":true}}}");code(NativeCommunity.Code.INVALID_RESPONSE,()->api.favorites("1",1),"unknown rows are not empty");
        fake.json("{\"data\":{\"0\":{\"0\":{\"unexpected\":true},\"unread\":0}}}");code(NativeCommunity.Code.INVALID_RESPONSE,()->api.reminders(0),"unknown reminder rows are not empty");
        fake.json("{\"data\":[{\"currentPage\":1,\"nextPage\":0,\"failure\":\"not a page\"}]}");code(NativeCommunity.Code.INVALID_RESPONSE,()->api.conversations(1),"unrecognized empty page metadata rejected");
        fake.json("{\"data\":{\"0\":{\"currentPage\":1,\"nextPage\":0}}}");code(NativeCommunity.Code.INVALID_RESPONSE,()->api.conversation("88001",1),"missing allmsgs rejected");
        fake.json("{\"data\":{\"0\":{\"uid\":\"70001\"}}}");code(NativeCommunity.Code.INVALID_RESPONSE,()->api.user("70001"),"partial profile rejected");
        fake.json("{\"data\":{\"0\":{\"unknown\":[]}}}");code(NativeCommunity.Code.INVALID_RESPONSE,()->api.favoriteFolders(1),"unknown folders rejected");
        String raw="window.script_muti_get_var_store={\"data\":{\"0\":{\"currentPage\":1,\"nextPage\":0,\"allUsers\":\"70001\tfixture-a\t70002\tfixture-b\",\"allmsgs\":{}}}};/*error fill content padding*/";
        fake.json(raw);ok(api.conversation("88001",1).participants.size()==2,"raw tab is preserved as participant delimiter");
        fake.raw(200,"/*$js$*/{\"data\":{\"0\":{\"uid\":\"70001\",\"username\":\"合成中文\"}}}","GB18030");ok(!api.user("70001").username.isEmpty(),"GB18030 response");
        fake.json("window.script_multi_get_var_store=/*$js$*/{\"data\":[{\"currentPage\":1,\"nextPage\":0,\"rowsPerPage\":20}]}/*$js$*/");ok(api.conversations(1).items.isEmpty(),"legacy markers outside JSON only");
        fake.responses.add(new NativeCommunity.Response(200,"{\"data\":{\"0\":{\"uid\":\"70001\",\"username\":\"合成中文\"}}}".getBytes(Charset.forName("GB18030")),"application/json; charset=UTF-8"));
        ok("合成中文".equals(api.user("70001").username),"malformed UTF8 falls back to GB18030");
        fake.json("{\"data\":{\"0\":\"发送完毕 ...\"}}");ok(api.sendMessage("70002","合成标题 & +","合成正文\n😀").confirmed,"explicit send acknowledgement only");
        ok(fake.last.mutation&&fake.last.method.equals("POST")&&fake.last.path.equals("nuke.php"),"private text uses POST body");
        ok(fake.last.parameters.get("content").contains("😀")&&"UTF8".equals(fake.last.parameters.get("__inchst")),"Unicode retained in request data");
        ok(!fake.last.toString().contains("content")&&!fake.last.toString().contains("合成"),"request string redacts personal parameters");
        fake.json("{\"data\":[\"操作成功\"]}");ok(api.replyMessage("88001","","离线回复").confirmed,"explicit reply acknowledgement");
        ok("reply".equals(fake.last.parameters.get("act"))&&!fake.last.parameters.containsKey("to"),"reply protocol");
        fake.json("{\"data\":{\"0\":\"操作成功\"}}");ok(api.addFavorite("91001","0","1").confirmed,"server add favorite acknowledgement");
        ok("topic_favor_v2".equals(fake.last.parameters.get("__lib"))&&"1".equals(fake.last.parameters.get("folder")),"folder add protocol");
        fake.json("{\"data\":{\"__T\":[{\"tid\":91001,\"pid\":92001,\"subject\":\"fixture\"}],\"__ROWS\":1}}").json("{\"data\":{\"0\":\"操作成功\"}}").json("{\"data\":{\"__T\":[],\"__ROWS\":0}}");int removalStart=fake.calls;ok(api.removeFavorite("91001","92001",2).confirmed,"default-folder removal independently confirmed");
        ok(fake.calls==removalStart+3&&"1".equals(fake.last.parameters.get("favor")),"legacy removal is explicitly default-folder scoped and reads back the same folder");
        for(String unknown:new String[]{"{\"data\":{}}","{\"data\":{\"0\":\"未操作成功\"}}","{\"data\":{\"0\":\"服务器收到请求\"}}","not-json"}) {
            fake.json(unknown);code(NativeCommunity.Code.UNCONFIRMED,()->api.replyMessage("88001","","离线回复"),"unknown acknowledgement never success");
        }
        fake.raw(302,"","UTF-8");code(NativeCommunity.Code.UNCONFIRMED,()->api.replyMessage("88001","","离线回复"),"POST redirect is delivery-uncertain");
        fake.fail=true;int before=fake.calls;code(NativeCommunity.Code.UNCONFIRMED,()->api.sendMessage("70002","合成标题","合成正文"),"interrupted send uncertain");ok(fake.calls==before+1,"no automatic mutation retry");
        code(NativeCommunity.Code.NETWORK,()->api.conversations(1),"read network error");fake.fail=false;
        fake.fail=true;before=fake.calls;code(NativeCommunity.Code.NETWORK,()->api.favoriteFolders(1),"read-only POST network failure is not delivery uncertainty");
        ok(fake.calls==before+1,"read-only POST has no automatic retry");fake.fail=false;
        session.cookie="";before=fake.calls;code(NativeCommunity.Code.LOGIN_REQUIRED,()->api.conversations(1),"missing auth preflight");ok(fake.calls==before,"no private request without session");
        code(NativeCommunity.Code.LOGIN_REQUIRED,api::self,"self auth preflight");session.cookie="ngaPassportUid=70001; ngaPassportCid=fixture-only";
        session.host="example.invalid";code(NativeCommunity.Code.UNSUPPORTED,()->api.user("70001"),"host whitelist");session.host="bbs.nga.cn";
        session.cookie="bad\r\nHeader: value";code(NativeCommunity.Code.LOGIN_REQUIRED,()->api.user("70001"),"cookie header injection blocked");session.cookie="ngaPassportUid=70001; ngaPassportCid=fixture-only";
        invalid(()->api.user("-1"),"anonymous id cannot be a recipient profile");invalid(()->api.conversations(0),"invalid page");invalid(()->api.reminders(-1),"invalid cursor");
        invalid(()->api.sendMessage("70002,","title","body"),"trailing recipient rejected");invalid(()->api.sendMessage("70002"," ","body"),"empty subject");invalid(()->api.replyMessage("88001","","\0"),"NUL body");
        fake.json("{\"data\":[]}evil()");code(NativeCommunity.Code.INVALID_RESPONSE,()->api.conversations(1),"trailing script is not executed");
        fake.raw(429,"{}","UTF-8");code(NativeCommunity.Code.RATE_LIMIT,()->api.conversations(1),"HTTP 429");
        fake.raw(401,"{}","UTF-8");code(NativeCommunity.Code.LOGIN_REQUIRED,()->api.conversations(1),"HTTP 401");
        fake.raw(403,"{}","UTF-8");code(NativeCommunity.Code.PERMISSION_DENIED,()->api.conversations(1),"HTTP 403");
        fake.responses.add(new NativeCommunity.Response(200,new byte[8*1024*1024+1],"application/json"));code(NativeCommunity.Code.INVALID_RESPONSE,()->api.conversations(1),"response cap");
        ok(fake.responses.isEmpty(),"all fixture responses consumed");
        System.out.println("NativeCommunity: "+checks+" offline assertions PASS; zero real network requests");
    }
}
