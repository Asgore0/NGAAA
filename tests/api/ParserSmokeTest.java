import cn.ngapure.app.NgaApi;
import cn.ngapure.app.ForumRoute;
import org.json.JSONObject;
import org.json.JSONArray;
import sun.misc.Unsafe;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public final class ParserSmokeTest {
    static NgaApi api;
    static int assertions;
    static Object call(String method, Class<?>[] types, Object... args) throws Exception {
        Method target = NgaApi.class.getDeclaredMethod(method, types);
        target.setAccessible(true);
        try { return target.invoke(api, args); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw error;
        }
    }
    static JSONObject load(String filename, String contentType) throws Exception {
        return (JSONObject) call("parseResponse", new Class<?>[]{byte[].class, String.class},
                Files.readAllBytes(Paths.get("tests/api/fixtures/" + filename)), contentType);
    }
    static void check(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        api = (NgaApi) ((Unsafe) field.get(null)).allocateInstance(NgaApi.class);
        assertions += FirstPostImagesTest.verify(api);
        assertions += ForumTopicTimeTest.verify(api);
        JSONObject threads = load("topics-gb18030.json", "text/html");
        List<NgaApi.Topic> topics = (List<NgaApi.Topic>) call("parseTopics",
                new Class<?>[]{JSONObject.class, String.class}, threads, "7");
        check(topics.size() > 30, "sanitized real-response GB18030 topic count");
        check(topics.get(0).tid.equals("47552220"), "topic id");
        check(topics.get(0).title.contains("魔兽世界"), "Chinese topic title");
        check(topics.get(0).replies == 43, "reply count");
        JSONObject mislabeled = load("topics-gb18030.json", "application/json;charset=UTF-8");
        check(mislabeled.getJSONObject("data").has("__T"), "mislabeled GBK charset fallback");
        JSONObject shortThread = load("short-thread-gb18030.json", "text/javascript; charset=GBK");
        NgaApi.ThreadPage page = (NgaApi.ThreadPage) call("parseThread",
                new Class<?>[]{JSONObject.class, String.class, int.class}, shortThread, "47738785", 1);
        check(page.posts.size() == 1, "short thread complete");
        check(page.title.equals("腐蚀祭坛设计的挺好"), "thread metadata");
        check(page.posts.get(0).floor == 0, "main floor");
        check(page.authorId.equals("10000"), "main post provides authoritative author when metadata omits it");
        shortThread.getJSONObject("data").getJSONObject("__T").put("authorid","70001");
        NgaApi.ThreadPage later=(NgaApi.ThreadPage)call("parseThread",new Class<?>[]{JSONObject.class,String.class,int.class},shortThread,"47738785",1);
        check(later.authorId.equals("70001"),"topic metadata supplies author independent of first visible reply");
        check(page.posts.get(0).content.contains("地下堡"), "Chinese body");
        boolean rejected = false;
        try { load("truncated-thread-gb18030.json", "text/javascript; charset=GBK"); }
        catch (Exception expected) { rejected = true; }
        check(rejected, "truncated JSON must not create partial posts");
        JSONObject html = load("thread-html-envelope.json", "application/json;charset=UTF-8");
        NgaApi.ThreadPage fallback = (NgaApi.ThreadPage) call("parseHtmlThread",
                new Class<?>[]{JSONObject.class, String.class, int.class}, html, "47738348", 1);
        check(fallback.posts.size() == 20, "all official HTML post rows");
        check(fallback.title.contains("329装等"), "HTML title");
        check(fallback.totalPages == 2 && fallback.page == 1, "HTML pagination");
        check(fallback.fid.equals("7"), "HTML forum id");
        check(fallback.posts.get(0).pid.equals("0"), "HTML main post id");
        check(fallback.posts.get(1).pid.equals("884941405"), "HTML reply id");
        check(fallback.posts.get(19).floor == 19, "HTML floor order");
        check(fallback.posts.get(0).author.equals("样例用户0"), "HTML real author record");
        check(!fallback.posts.get(0).content.contains("ngaAds"), "advertising scripts not in extracted content");
        JSONObject hot = load("official-hot-topics.json", "application/json;charset=UTF-8");
        List<NgaApi.Topic> hotTopics = (List<NgaApi.Topic>) call("parseHotTopics", new Class<?>[]{JSONObject.class}, hot);
        check(hotTopics.size() > 10, "current official feed rows");
        check(hotTopics.get(0).tid.equals("47717100"), "feed actual thread id");
        check(hotTopics.get(0).forum.equals("明日方舟终末地"), "feed forum names");
        JSONObject catalog = (JSONObject) call("parseResponse", new Class<?>[]{byte[].class, String.class}, Files.readAllBytes(Paths.get("app/src/main/assets/forums.json")), "application/json;charset=UTF-8");
        List<NgaApi.Forum> forums = (List<NgaApi.Forum>) call("parseForums", new Class<?>[]{JSONObject.class}, catalog);
        check(forums.size() == 658, "full catalog deduplicates canonical board/collection routes without dropping stid entries");
        check(forums.stream().anyMatch(f -> f.fid.equals("818") && f.name.contains("星穹铁道")), "real game forum");
        check(forums.stream().anyMatch(f -> f.fid.equals("7") && f.icon.equals("https://img4.nga.cn/ngabbs/nga_classic/f/7.png")), "official catalog icon mapping");
        check(forums.stream().anyMatch(f -> f.fid.equals("-7") && f.icon.endsWith("/354.png")), "official forum icon alias, not guessed signed ID path");
        assertions += ForumCatalogTest.verify(catalog, forums);
        JSONObject subforumFixture = load("subforums-public.json", "application/json;charset=UTF-8");
        Class<?>[] forumPageTypes = new Class<?>[]{JSONObject.class, ForumRoute.class, int.class};
        NgaApi.ForumPage nativeForum = (NgaApi.ForumPage) call("parseForumPage", forumPageTypes, subforumFixture, ForumRoute.fromKey("650"), 3);
        check(nativeForum.key.equals("650") && nativeForum.fid.equals("650") && nativeForum.page == 3 && nativeForum.name.equals("原神"), "ordinary board identity and requested page retained");
        check(nativeForum.subForums.size() == 12 && nativeForum.topics.isEmpty(), "all twelve real child boards and collections survive metadata-only fixture");
        NgaApi.Forum ordinaryChild = nativeForum.subForums.stream().filter(f -> f.key.equals("754")).findFirst().get();
        check(ordinaryChild.fid.equals("754") && ordinaryChild.stid.isEmpty() && ordinaryChild.parentFid.equals("650") && ordinaryChild.name.equals("旅行者指引广场"), "child fid is tuple zero, not parent or mirror topic");
        check(ordinaryChild.description.equals("提问专区") && ordinaryChild.icon.equals("https://img4.nga.cn/ngabbs/nga_classic/f/app/754.png"), "official child description and own artwork endpoint retained");
        NgaApi.Forum collectionChild = nativeForum.subForums.stream().filter(f -> f.key.equals("stid:23673143")).findFirst().get();
        check(collectionChild.fid.isEmpty() && collectionChild.stid.equals("23673143") && collectionChild.parentFid.equals("650"), "bit16 collection has separate stable stid identity");
        check(collectionChild.icon.equals("https://img4.nga.cn/proxy/cache_attach/ficon/23673143v.png"), "collection uses its own official artwork rather than parent image");
        check(nativeForum.subForums.stream().anyMatch(f -> f.key.equals("758") && f.fid.equals("758") && f.stid.isEmpty()), "bit32 mirror remains actual child fid rather than mirror topic id");
        JSONObject collectionFixture = load("collection-public.json", "application/json;charset=UTF-8");
        NgaApi.ForumPage nativeCollection = (NgaApi.ForumPage) call("parseForumPage", forumPageTypes, collectionFixture, ForumRoute.fromKey("stid:23673143"), 2);
        check(nativeCollection.key.equals("stid:23673143") && nativeCollection.fid.equals("650") && nativeCollection.stid.equals("23673143") && nativeCollection.name.equals("天命既定"), "collection metadata does not replace stid route with its parent fid");
        check(nativeCollection.subForums.isEmpty(), "collection does not render itself as a recursively opening child");
        boolean mismatchedCollection = false;
        try { call("parseForumPage", forumPageTypes, collectionFixture, ForumRoute.fromKey("stid:26640400"), 1); } catch (Exception expected) { mismatchedCollection = true; }
        check(mismatchedCollection, "mismatched collection metadata is rejected");
        Class<?>[] subforumTypes = new Class<?>[]{Object.class, String.class, String.class};
        JSONArray childRows = new JSONArray("[[754,'child','description',24147312,2606],[23673143,'collection','',23673143,542],[758,'mirror','',24484861,40],[754,'duplicate','',0,0],[0,'bad','',0,0],[-1,'bad collection','',0,16],[793,'','',0,0],[835,'bad flags','',0,'junk'],[650,'current','',0,64]]");
        List<NgaApi.Forum> syntheticChildren = (List<NgaApi.Forum>) call("parseSubForums", subforumTypes, childRows, "650", "650");
        check(syntheticChildren.size() == 3, "array tuples deduplicate and reject invalid ids, flags, names and current route");
        check(syntheticChildren.stream().anyMatch(f -> f.key.equals("758")) && syntheticChildren.stream().noneMatch(f -> f.key.equals("24484861")), "mirror topic tuple three is never used as a destination");
        check(((List<?>) call("parseSubForums", subforumTypes, "", "650", "650")).isEmpty(), "official empty subforum scalar is supported");
        JSONArray oversizedChildren = new JSONArray(); for (int i = 1; i <= 300; i++) oversizedChildren.put(new JSONArray().put(i).put("child").put("").put(0).put(0));
        check(((List<?>) call("parseSubForums", subforumTypes, oversizedChildren, "650", "650")).size() == 256, "subforum work is bounded independently of server response size");
        check(topics.get(0).avatar.isEmpty() && !topics.get(0).votes.likesKnown, "missing public list avatar and support count remain unknown");
        JSONObject metadata = new JSONObject("{\"data\":{\"__T\":{\"0\":{\"tid\":1,\"authorid\":1,\"recommend\":\"0,12,3\"}},\"__U\":{\"1\":{\"avatar\":\".a/1_123.png\"}}}}");
        List<NgaApi.Topic> metadataTopics = (List<NgaApi.Topic>) call("parseTopics", new Class<?>[]{JSONObject.class, String.class}, metadata, "7");
        check(metadataTopics.get(0).avatar.endsWith("/1_123.png") && metadataTopics.get(0).votes.likes == 12, "list uses same-response author table and explicit counts");
        check(metadataTopics.get(0).votes.summary().equals("点赞 12 · 点踩 3") && metadataTopics.get(0).votes.countsKnown(), "visible reaction summary contains both exact independent counts");
        metadata.getJSONObject("data").getJSONObject("__T").getJSONObject("0").put("recommend", 10);
        metadataTopics = (List<NgaApi.Topic>) call("parseTopics", new Class<?>[]{JSONObject.class, String.class}, metadata, "7");
        check(!metadataTopics.get(0).votes.likesKnown && !metadataTopics.get(0).votes.scoreKnown, "topic scalar recommendation is not displayed as likes or score");
        check(metadataTopics.get(0).votes.summary().equals("点赞 — · 点踩 —"), "unknown topic totals never fall back to a net score");
        JSONObject synthetic = new JSONObject("{\"data\":{\"__T\":{\"tid\":1},\"__R\":{\"0\":{\"authorid\":1,\"pid\":0,\"recommend\":\"0,9,2\"}},\"__U\":{\"1\":{\"username\":\"synthetic\",\"avatar\":\"42.gif\"}}}}");
        NgaApi.ThreadPage metadataThread = (NgaApi.ThreadPage) call("parseThread", new Class<?>[]{JSONObject.class, String.class, int.class}, synthetic, "1", 1);
        check(metadataThread.posts.get(0).avatar.endsWith("/face/42.gif") && metadataThread.posts.get(0).votes.likesKnown && metadataThread.posts.get(0).votes.likes == 9, "body uses official author avatar and distinct support count");
        check(metadataThread.posts.get(0).votes.likesLabel().equals("点赞 9") && metadataThread.posts.get(0).votes.dislikesLabel().equals("点踩 2"), "reply labels expose support and oppose separately");
        check(metadataTopics.get(0).authorId.equals("1"), "topic retains official author ID for visible-only public avatar lookup");
        metadata.getJSONObject("data").getJSONObject("__T").getJSONObject("0").put("avatar", "42.gif");
        metadata.getJSONObject("data").getJSONObject("__U").getJSONObject("1").put("avatar", "");
        metadataTopics = (List<NgaApi.Topic>) call("parseTopics", new Class<?>[]{JSONObject.class, String.class}, metadata, "7");
        check(metadataTopics.get(0).avatar.endsWith("/face/42.gif"), "empty list user avatar must not erase a real same-row avatar");
        synthetic.getJSONObject("data").getJSONObject("__R").getJSONObject("0").put("avatar", "42.gif");
        synthetic.getJSONObject("data").getJSONObject("__U").getJSONObject("1").put("avatar", "");
        metadataThread = (NgaApi.ThreadPage) call("parseThread", new Class<?>[]{JSONObject.class, String.class, int.class}, synthetic, "1", 1);
        check(metadataThread.posts.get(0).avatar.endsWith("/face/42.gif"), "empty reply user avatar must not erase a real same-row avatar");
        synthetic.getJSONObject("data").getJSONObject("__U").getJSONObject("1").put("avatar", "t2%7C.a/1_123.png");
        metadataThread = (NgaApi.ThreadPage) call("parseThread", new Class<?>[]{JSONObject.class, String.class, int.class}, synthetic, "1", 1);
        check(metadataThread.posts.get(0).avatar.endsWith("/1_123.png"), "official multi-avatar metadata survives thread author lookup");
        metadata.getJSONObject("data").getJSONObject("__T").getJSONObject("0").put("authorid", "-1");
        metadataTopics = (List<NgaApi.Topic>) call("parseTopics", new Class<?>[]{JSONObject.class, String.class}, metadata, "7");
        check(metadataTopics.get(0).authorId.equals("-1") && !cn.ngapure.app.NativeAvatarResolver.eligible(metadataTopics.get(0).authorId), "negative anonymous author remains ineligible for public lookup");
        metadata.getJSONObject("data").getJSONObject("__T").getJSONObject("0").put("authorid", "1&uid=2");
        metadataTopics = (List<NgaApi.Topic>) call("parseTopics", new Class<?>[]{JSONObject.class, String.class}, metadata, "7");
        check(metadataTopics.get(0).authorId.isEmpty(), "malformed author metadata cannot become a lookup route");
        String htmlMetrics = "<div id='postliststart'></div><table class='postbox'><a id='pid0Anchor'></a><p id='postcontent0'>synthetic</p><a name='l0'></a></table>"
                + "<script>postArg.proc(0,$('container'),$('subject'),$('content'),$('sign'),$('user'),$('info'),$('button'),null,null,0,0,null,'1',123,'0,15,4','4','',null);</script>";
        NgaApi.ThreadPage metricsFallback = (NgaApi.ThreadPage) call("parseHtmlThread", new Class<?>[]{JSONObject.class, String.class, int.class}, new JSONObject().put("template", "read").put("html", htmlMetrics), "1", 1);
        check(metricsFallback.posts.get(0).votes.likesKnown && metricsFallback.posts.get(0).votes.likes == 15 && metricsFallback.posts.get(0).votes.dislikes == 4, "HTML fallback maps explicit recommendation tuple to exact floor and pid");
        JSONObject votesFixture = load("post-votes-html.json", "application/json;charset=UTF-8");
        Class<?>[] voteTypes = new Class<?>[]{JSONObject.class, String.class, int.class};
        Map<String, cn.ngapure.app.VoteMetrics> pageVotes = (Map<String, cn.ngapure.app.VoteMetrics>) call("parsePostVotes", voteTypes, votesFixture, "123", 2);
        check(pageVotes.size() == 1 && pageVotes.get("20:456").likesKnown && pageVotes.get("20:456").likes == 15, "opened page HTML metadata provides exact floor:pid positive count");
        Class<?>[] authorVoteTypes=new Class<?>[]{JSONObject.class,String.class,int.class,String.class};
        JSONObject authorVotes=new JSONObject(votesFixture.toString()).put("htmlBaseUrl","https://bbs.nga.cn/read.php?tid=123&page=2&authorid=70001");
        pageVotes=(Map<String,cn.ngapure.app.VoteMetrics>)call("parsePostVotes",authorVoteTypes,authorVotes,"123",2,"70001");
        check(pageVotes.get("20:456").likes==15,"author-filtered read uses matching server floor identity");
        boolean mismatchedAuthor=false;try{call("parsePostVotes",authorVoteTypes,authorVotes,"123",2,"70002");}catch(Exception expected){mismatchedAuthor=true;}check(mismatchedAuthor,"different author-view metadata cannot update filtered page");
        JSONObject htmlIdentity = new JSONObject(votesFixture.toString()); htmlIdentity.remove("htmlBaseUrl");
        pageVotes = (Map<String, cn.ngapure.app.VoteMetrics>) call("parsePostVotes", voteTypes, htmlIdentity, "123", 2);
        check(pageVotes.containsKey("20:456"), "explicit HTML topic/page identity works without base URL");
        String withoutIdentity = votesFixture.getString("html").replace("var __CURRENT_TID=123;", "").replace("var __PAGE={0:'/read.php?tid=123&page=2',1:3,2:2,3:20};", "");
        JSONObject baseIdentity = new JSONObject(votesFixture.toString()).put("html", withoutIdentity);
        pageVotes = (Map<String, cn.ngapure.app.VoteMetrics>) call("parsePostVotes", voteTypes, baseIdentity, "123", 2);
        check(pageVotes.containsKey("20:456"), "official base URL explicitly binds topic and page");
        JSONObject noCounts = new JSONObject(votesFixture.toString()).put("html", votesFixture.getString("html").replace("postArg.proc", "irrelevant.proc"));
        pageVotes = (Map<String, cn.ngapure.app.VoteMetrics>) call("parsePostVotes", voteTypes, noCounts, "123", 2);
        check(pageVotes.isEmpty(), "a verified page without counts stays unknown");
        List<JSONObject> wrongVotes = new ArrayList<>();
        wrongVotes.add(new JSONObject(votesFixture.toString()).put("template", "thread"));
        wrongVotes.add(new JSONObject(votesFixture.toString()).put("error", "permission denied"));
        wrongVotes.add(new JSONObject(votesFixture.toString()).put("html", votesFixture.getString("html").replace("postliststart", "other-marker")));
        wrongVotes.add(new JSONObject(votesFixture.toString()).put("html", votesFixture.getString("html").replace("__CURRENT_TID=123", "__CURRENT_TID=124")));
        wrongVotes.add(new JSONObject(votesFixture.toString()).put("html", votesFixture.getString("html").replace("2:2,3:20", "2:1,3:20")));
        wrongVotes.add(new JSONObject(votesFixture.toString()).put("htmlBaseUrl", "https://bbs.nga.cn/read.php?tid=124&page=2"));
        wrongVotes.add(new JSONObject(votesFixture.toString()).put("htmlBaseUrl", "https://bbs.nga.cn/read.php?tid=123&page=3"));
        wrongVotes.add(new JSONObject(votesFixture.toString()).put("htmlBaseUrl", "https://attacker.invalid/read.php?tid=123&page=2"));
        wrongVotes.add(new JSONObject(baseIdentity.toString()).put("htmlBaseUrl", "https://bbs.nga.cn/read.php?tid=123"));
        wrongVotes.add(new JSONObject(baseIdentity.toString()).put("htmlBaseUrl", "https://bbs.nga.cn/read.php"));
        for (JSONObject wrong : wrongVotes) {
            boolean refused = false;
            try { call("parsePostVotes", voteTypes, wrong, "123", 2); } catch (Exception expected) { refused = true; }
            check(refused, "wrong topic/page/template/error/origin metadata cannot update opened-page votes");
        }
        Class<?>[] pageDataTypes = new Class<?>[]{String.class};
        Map<String, String> legacyPages = (Map<String, String>) call("legacyPageData", pageDataTypes,
                "var __PAGE={0:'/read.php?tid=123',1:65,2:1,3:20};");
        check(legacyPages.get("1").equals("65") && legacyPages.get("2").equals("1"), "numeric legacy page keys parse without desktop JSON leniency");
        legacyPages = (Map<String, String>) call("legacyPageData", pageDataTypes,
                "var __PAGE={\"0\":'brace }; semicolon ; quote \\' \\\\ unicode \\u4e2d hex \\x41', '1':65, '2':'2',3:20,};");
        check(legacyPages.get("0").equals("brace }; semicolon ; quote ' \\ unicode 中 hex A") && legacyPages.get("2").equals("2"), "quoted keys, escapes, braces and semicolons are tokenized as data without regex rewriting");
        check(call("legacyPageData", pageDataTypes, "var other={0:1};") == null, "missing legacy metadata keeps the existing fallback");
        String[] invalidPaging = {"{}", "{2:1,2:2}", "{2:1,'2':2}", "{02:1}", "{2:1+1}", "{2:(function(){return 1})()}",
                "{2:[1]}", "{2:{0:1}}", "{2:true}", "{2:null}", "{2:1.5}", "{2:1e2}", "{0:'unterminated,2:1}", "{0:'bad\\q',2:1}",
                "{0:'bad\\u00xx',2:1}", "{0:'bad\\x0z',2:1}", "{2:1}||evil()", "{2:1", "{2:1;3:20}"};
        for (String invalid : invalidPaging) {
            boolean refused = false;
            try { call("legacyPageData", pageDataTypes, "var __PAGE=" + invalid + ";"); } catch (Exception expected) { refused = true; }
            check(refused, "malformed or executable pagination values are rejected as data");
        }
        Class<?>[] pageNumberTypes = new Class<?>[]{Map.class, String.class, int.class};
        for (String invalid : new String[]{"0", "-1", "100001", "2147483648", "01"}) {
            java.util.HashMap<String, String> map = new java.util.HashMap<>(); map.put("2", invalid); boolean refused = false;
            try { call("legacyPageNumber", pageNumberTypes, map, "2", 1); } catch (Exception expected) { refused = true; }
            check(refused, "pagination number ranges are validated consistently on all runtimes");
        }
        JSONObject routeCases = load("reply-location-cases.json", "application/json;charset=UTF-8");
        java.net.URL routeOrigin = new java.net.URL(routeCases.getString("origin"));
        Class<?>[] routeTypes = new Class<?>[]{java.net.URL.class, String.class, String.class};
        JSONArray acceptedRoutes = routeCases.getJSONArray("accepted");
        for (int i = 0; i < acceptedRoutes.length(); i++) {
            JSONObject acceptedRoute = acceptedRoutes.getJSONObject(i);
            int actual = (Integer) call("replyPageFromLocation", routeTypes, routeOrigin, "123", acceptedRoute.getString("location"));
            check(actual == acceptedRoute.getInt("page"), "official absolute/relative reply redirect resolves explicit topic page");
        }
        JSONArray refusedRoutes = routeCases.getJSONArray("rejected");
        for (int i = 0; i < refusedRoutes.length(); i++) {
            boolean refused = false;
            try { call("replyPageFromLocation", routeTypes, routeOrigin, "123", refusedRoutes.getString(i)); } catch (Exception expected) { refused = true; }
            check(refused, "unsafe/missing/mismatched/ambiguous reply redirect cannot resolve a page");
        }
        boolean refusedNullRoute = false;
        try { call("replyPageFromLocation", routeTypes, routeOrigin, "123", null); } catch (Exception expected) { refusedNullRoute = true; }
        check(refusedNullRoute, "missing Location is rejected");
        Class<?>[] targetTypes = new Class<?>[]{NgaApi.ThreadPage.class, String.class, String.class, int.class};
        for (int scenario = 0; scenario < 4; scenario++) {
            NgaApi.ThreadPage returned = new NgaApi.ThreadPage(); returned.tid = scenario == 1 ? "124" : "123";
            returned.page = scenario == 2 ? 1 : 2;
            NgaApi.Post target = new NgaApi.Post(); target.pid = scenario == 3 ? "457" : "456"; returned.posts.add(target);
            boolean refused = false;
            try { call("requireReplyTarget", targetTypes, returned, "123", "456", 2); } catch (Exception expected) { refused = true; }
            check(refused == (scenario != 0), "resolved full page must match topic/page and contain target pid");
        }
        check(NgaApi.allowed(new java.net.URL("https://bbs.nga.cn/read.php?tid=1")), "known HTTPS origin");
        check(!NgaApi.allowed(new java.net.URL("https://bbs.nga.cn.evil.example/read.php")), "host suffix rejected");
        check(!NgaApi.allowed(new java.net.URL("http://bbs.nga.cn/read.php")), "HTTP rejected");
        check(!NgaApi.allowed(new java.net.URL("https://bbs.nga.cn:8443/read.php")), "unknown port rejected");
        check(!NgaApi.allowed(new java.net.URL("https://user@bbs.nga.cn/read.php")), "URL userinfo rejected");
        NgaApi.CheckInResult signed = (NgaApi.CheckInResult) call("parseCheckInResult", new Class<?>[]{JSONObject.class},
                new JSONObject("{\"data\":[\"签到成功 (任务进度更新)\",{\"uid\":123,\"continued\":1,\"sum\":5,\"last_time\":1792252800}]}"));
        check(signed.success && !signed.already && signed.message.equals("签到成功"), "check-in success hides internal statistics");
        for (String phrase : new String[]{"今天已签到", "今天已经签到", "今日已签到", "已经签到了", "今天已经签过"}) {
            NgaApi.CheckInResult already = (NgaApi.CheckInResult) call("parseCheckInResult", new Class<?>[]{JSONObject.class},
                    new JSONObject().put("error", phrase));
            check(already.success && already.already && already.message.equals("今天已签到"), "confirmed already phrase: " + phrase);
        }
        for (String phrase : new String[]{"未签到成功", "签到失败，今天已签到状态未更新", "签到不成功", "未能签到成功"}) {
            NgaApi.CheckInResult failed = (NgaApi.CheckInResult) call("parseCheckInResult", new Class<?>[]{JSONObject.class},
                    new JSONObject().put("data", phrase));
            check(!failed.success && !failed.already && failed.message.equals(phrase), "negative confirmation is not success: " + phrase);
        }
        NgaApi.CheckInResult stats = (NgaApi.CheckInResult) call("parseCheckInResult", new Class<?>[]{JSONObject.class},
                new JSONObject("{\"data\":{\"continued\":1,\"sum\":5,\"last_time\":1792252800}}"));
        check(!stats.success && !stats.already, "statistics alone do not confirm check-in");
        NgaApi.CheckInResult error = (NgaApi.CheckInResult) call("parseCheckInResult", new Class<?>[]{JSONObject.class},
                new JSONObject("{\"error\":\"操作失败，请稍后再试\",\"data\":\"签到成功\"}"));
        check(!error.success && error.message.equals("操作失败，请稍后再试"), "server error keeps readable original and overrides data");
        System.out.println("PASS: " + assertions + " assertions; " + topics.size() + " topics, " +
                fallback.posts.size() + " extracted posts, " + hotTopics.size() + " feed topics, " + forums.size() + " forums.");
    }
}
