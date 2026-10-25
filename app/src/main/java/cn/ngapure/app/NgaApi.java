package cn.ngapure.app;

import android.content.Context;
import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small synchronous API layer. Call it from a worker thread. No analytics or advertising. */
public final class NgaApi {
    private final NgaStore store;
    private final Context context;
    private static final Pattern CATALOG_CONTROLS = Pattern.compile("[\\x00-\\x1f\\x7f]");

    public NgaApi(Context context) { this.context = context.getApplicationContext(); store = new NgaStore(context); }

    public static final class Topic {
        public String tid = "", title = "", author = "", authorId = "", time = "", forum = "", fid = "";
        public String image = "", avatar = "";
        public VoteMetrics votes = new VoteMetrics();
        public int replies;
    }
    public static final class Post {
        public String pid = "", author = "", authorId = "", content = "", time = "", avatar = "";
        public VoteMetrics votes = new VoteMetrics();
        public int floor;
    }
    public static final class ThreadPage {
        public String tid = "", title = "", fid = "", authorId = "";
        public int page = 1, totalPages = 1;
        public List<Post> posts = new ArrayList<>();
    }
    public static final class CheckInResult {
        public boolean success, already;
        public String message = "";
    }
    public static final class Forum {
        public String key = "", fid = "", stid = "", parentFid = "", name = "", category = "", description = "", icon = "";
        /** Directory-only display/search metadata. It never changes a destination's canonical key. */
        public String group = "", searchAliases = "";
        public List<String> aliases = Collections.emptyList(), categoryNames = Collections.emptyList(), groupNames = Collections.emptyList();
    }
    public static final class ForumPage {
        public String key = "", fid = "", stid = "", name = "";
        public int page = 1;
        public List<Forum> subForums = new ArrayList<>();
        public List<Topic> topics = new ArrayList<>();
        /** Monotonic durations in nanoseconds; zero for locally constructed/cached page copies.
         * requestNanos covers this API read including parsing, not worker queue/session capture/UI.
         * headersNanos sums getResponseCode waits across application-level redirect hops; it may
         * include DNS/connect/TLS but does not distinguish those stages or platform-internal retries.
         * bodyNanos covers terminal stream acquisition, full read and close. parsedNanos includes
         * decoding/JSON parsing and native ForumPage construction. These are not absolute timestamps.
         */
        public long requestNanos, headersNanos, bodyNanos, parsedNanos;
        /** Bytes delivered to the application (after Android's transparent decompression). */
        public long responseBytes;
        /** Application-level HTTP hops, not the number of underlying socket attempts. */
        public int requestCount;
    }
    /** Per-call numeric diagnostics, used only by the read-only forum API; no identity or payload. */
    private static final class ForumReadTrace {
        final long startedAt = System.nanoTime();
        long headersNanos, bodyNanos, parsedNanos, responseBytes;
        int requestCount;
        void apply(ForumPage page) {
            page.requestNanos = System.nanoTime() - startedAt;
            page.headersNanos = headersNanos; page.bodyNanos = bodyNanos; page.parsedNanos = parsedNanos;
            page.responseBytes = responseBytes; page.requestCount = requestCount;
        }
    }

    /** Public official catalog bundled for first launch without a network connection. */
    public List<Forum> forums() throws Exception {
        JSONObject catalog = parseResponse(readLimited(context.getAssets().open("forums.json")), "application/json; charset=UTF-8");
        return parseForums(catalog);
    }

    private List<Forum> parseForums(JSONObject catalog) {
        List<Forum> result = new ArrayList<>();
        Map<String, Forum> byKey = new LinkedHashMap<>();
        JSONArray categories = catalog.optJSONArray("data");
        if (categories == null) return result;
        for (int i = 0; i < categories.length(); i++) {
            JSONObject category = categories.optJSONObject(i);
            if (category == null) continue;
            JSONArray groups = category.optJSONArray("groups");
            if (groups == null) continue;
            String categoryName = catalogText(category.optString("name", ""), 80);
            if (categoryName.isEmpty()) categoryName = "其他";
            for (int j = 0; j < groups.length(); j++) {
                JSONObject group = groups.optJSONObject(j);
                JSONArray forums = group == null ? null : group.optJSONArray("forums");
                if (forums == null) continue;
                String groupName = catalogText(group.optString("name", ""), 80);
                for (int k = 0; k < forums.length(); k++) {
                    JSONObject entry = forums.optJSONObject(k);
                    if (entry == null) continue;
                    String fid = entry.optString("fid", ""), stid = entry.optString("stid", "");
                    // A collection's fid is only its parent. An invalid declared stid must
                    // never silently turn the collection's display name into a parent-board route.
                    boolean collection = entry.has("stid");
                    if (collection ? !ForumRoute.isCollectionId(stid) : !ForumRoute.isForumId(fid)) continue;
                    ForumRoute route = collection
                            ? ForumRoute.fromParts("", stid, ForumRoute.isForumId(fid) ? fid : "")
                            : ForumRoute.fromKey(fid);
                    String name = catalogText(entry.optString("name", ""), 160);
                    if (name.isEmpty()) name = route.key;
                    String description = catalogText(entry.optString("info", ""), 512);
                    String shortDescription = catalogText(entry.optString("infoS", ""), 512);
                    String longDescription = catalogText(entry.optString("infoL", ""), 512);
                    if (description.isEmpty()) description = shortDescription;
                    if (description.isEmpty()) description = longDescription;
                    String icon = AvatarSources.normalize(entry.optString("icon", ""));
                    Forum forum = byKey.get(route.key);
                    if (forum == null) {
                        forum = new Forum(); forum.key = route.key; forum.fid = route.fid; forum.stid = route.stid;
                        forum.parentFid = route.parentFid; forum.name = name; forum.category = categoryName;
                        forum.group = groupName; forum.description = description; forum.icon = icon;
                        forum.aliases = new ArrayList<>(); forum.categoryNames = new ArrayList<>(); forum.groupNames = new ArrayList<>();
                        byKey.put(route.key, forum); result.add(forum);
                    } else {
                        if (!forum.name.equalsIgnoreCase(name)) addCatalogValue(forum.aliases, name, 16);
                        if (forum.description.isEmpty()) forum.description = description;
                        if (forum.icon.isEmpty()) forum.icon = icon;
                        if (forum.parentFid.isEmpty()) forum.parentFid = route.parentFid;
                    }
                    String shortName = catalogText(entry.optString("nameS", ""), 160);
                    if (!forum.name.equalsIgnoreCase(shortName)) addCatalogValue(forum.aliases, shortName, 16);
                    addCatalogValue(forum.categoryNames, categoryName, 16);
                    addCatalogValue(forum.groupNames, groupName, 32);
                    addCatalogSearch(forum, route.key, route.fid, route.stid, route.parentFid, name, shortName,
                            categoryName, groupName, description, shortDescription, longDescription);
                }
            }
        }
        for (Forum forum : result) {
            forum.aliases = Collections.unmodifiableList(new ArrayList<>(forum.aliases));
            forum.categoryNames = Collections.unmodifiableList(new ArrayList<>(forum.categoryNames));
            forum.groupNames = Collections.unmodifiableList(new ArrayList<>(forum.groupNames));
        }
        return result;
    }

    private static String catalogText(String value, int maximum) {
        if (value == null || value.isEmpty()) return "";
        // Only literal text without HTML syntax or normalization-sensitive characters
        // can bypass Android Html. Whitespace, entities, controls and supplementary
        // characters retain the exact existing parser/cleanup path.
        String clean = catalogLiteral(value) ? value : CATALOG_CONTROLS.matcher(text(value)).replaceAll(" ").trim();
        if (clean.length() <= maximum) return clean;
        int end = Character.isHighSurrogate(clean.charAt(maximum - 1)) ? maximum - 1 : maximum;
        return clean.substring(0, end);
    }
    private static boolean catalogLiteral(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= ' ' || c == '<' || c == '&' || (c >= 0x7f && c <= 0x9f)
                    || Character.isSurrogate(c) || Character.isWhitespace(c) || Character.isSpaceChar(c)
                    || Character.getType(c) == Character.FORMAT || c >= 0xfffe) return false;
        }
        return true;
    }
    private static void addCatalogValue(List<String> values, String value, int maximum) {
        if (value.isEmpty() || values.size() >= maximum) return;
        for (String existing : values) if (existing.equalsIgnoreCase(value)) return;
        values.add(value);
    }
    private static void addCatalogSearch(Forum forum, String... values) {
        for (String value : values) {
            if (value.isEmpty() || ("\n" + forum.searchAliases + "\n").toLowerCase(Locale.ROOT)
                    .contains("\n" + value.toLowerCase(Locale.ROOT) + "\n")) continue;
            if (forum.searchAliases.length() + value.length() + 1 > 4096) continue;
            forum.searchAliases += (forum.searchAliases.isEmpty() ? "" : "\n") + value;
        }
    }

    /** Current official editorial topic feed, rather than the obsolete app_api home feed. */
    public List<Topic> hotTopics() throws Exception {
        JSONObject root = request("nuke.php", "__lib=app_inter&__act=recmd_topic&__output=11", false);
        throwServerError(root);
        return parseHotTopics(root);
    }

    private List<Topic> parseHotTopics(JSONObject root) throws Exception {
        JSONArray data = root.optJSONArray("data");
        if (data == null || data.optJSONArray(0) == null) throw new IOException("服务器未返回官方热帖列表");
        JSONObject names = data.optJSONObject(1);
        List<Topic> result = new ArrayList<>();
        for (JSONObject entry : objects(data.optJSONArray(0))) {
            JSONObject row = entry.optJSONObject("topic");
            if (row == null) continue;
            // The feed may also carry commercial cards. Only expose genuine forum topics.
            if (entry.optBoolean("is_market", false)) continue;
            Topic topic = parseTopic(row, row.optString("fid", ""), "");
            topic.image = entry.optString("thread_icon", "");
            if (!topic.tid.matches("[0-9]+")) continue;
            if (names != null) topic.forum = text(names.optString(topic.fid, ""));
            result.add(topic);
        }
        return result;
    }

    public List<Topic> topics(String fid, int page, String search) throws Exception {
        if (search == null || search.trim().isEmpty()) return readForum(fid, page, "").topics;
        requireNumber(fid, true);
        JSONObject root = request("thread.php", "fid=" + encode(fid) + "&page=" + page(page)
                + "&__output=8&__inchst=UTF8&key=" + encode(search.trim()), false);
        throwServerError(root);
        return parseTopics(root, fid);
    }

    /** A single official response supplies the native topic list and its child boards/collections. */
    public ForumPage readForum(String key, int requestedPage, String parentFid) throws Exception {
        return readForum(key, requestedPage, parentFid, store.captureCheckInSession());
    }

    /** The response identity is fixed before scheduling the read, including every redirect. */
    ForumPage readForum(String key, int requestedPage, String parentFid, NgaStore.CheckInSession session) throws Exception {
        if (session == null) throw new IllegalArgumentException("Forum session required");
        ForumRoute route = ForumRoute.fromKey(key).withParent(parentFid);
        ForumReadTrace trace = new ForumReadTrace();
        JSONObject root = request("thread.php", route.query() + "&page=" + page(requestedPage)
                + "&__output=8&__inchst=UTF8", false, session.host, session.cookie, trace);
        long parsingStarted = System.nanoTime();
        throwServerError(root);
        ForumPage result = parseForumPage(root, route, requestedPage);
        trace.parsedNanos += System.nanoTime() - parsingStarted;
        trace.apply(result);
        return result;
    }

    private ForumPage parseForumPage(JSONObject root, ForumRoute route, int requestedPage) throws Exception {
        JSONObject data = root.optJSONObject("data"); if (data == null) data = root;
        JSONObject meta = data.optJSONObject("__F");
        ForumPage result = new ForumPage(); result.key = route.key; result.stid = route.stid;
        result.page = page(requestedPage); result.fid = meta == null ? route.fid : meta.optString("fid", route.fid);
        if (!ForumRoute.isForumId(result.fid)) result.fid = route.fid;
        result.name = meta == null ? "" : text(meta.optString(route.stid.isEmpty() ? "name" : "set_topic_subject", ""));
        if (meta != null && !route.stid.isEmpty()) {
            String returned = meta.optString("set_topic_tid", "");
            if (!returned.isEmpty() && !returned.equals(route.stid)) throw new IOException("服务器返回的合集与请求不一致");
        }
        result.topics = parseTopics(root, result.fid);
        if (meta != null) result.subForums = parseSubForums(meta.opt("sub_forums"), result.fid, route.key);
        return result;
    }

    /** Official numeric-index tuples: [id,name,description,mirrorTopic,bitmask]; bit 16 is a collection. */
    private List<Forum> parseSubForums(Object rows, String parentFid, String currentKey) {
        List<Object> candidates = new ArrayList<>();
        if (rows instanceof JSONObject) {
            JSONObject object = (JSONObject) rows; Iterator<String> keys = object.keys();
            while (keys.hasNext() && candidates.size() < 256) candidates.add(object.opt(keys.next()));
        } else if (rows instanceof JSONArray) {
            JSONArray array = (JSONArray) rows;
            for (int i = 0; i < array.length() && i < 256; i++) candidates.add(array.opt(i));
        }
        List<Forum> result = new ArrayList<>(); HashSet<String> seen = new HashSet<>();
        for (Object row : candidates) {
            String id = tuple(row, 0), name = text(tuple(row, 1)), flags = tuple(row, 4);
            if (name.isEmpty() || name.length() > 200 || !flags.matches("[0-9]{1,5}")) continue;
            int bits = Integer.parseInt(flags); if (bits > 65535) continue;
            boolean collection = (bits & 16) != 0;
            if (collection ? !ForumRoute.isCollectionId(id) : !ForumRoute.isForumId(id)) continue;
            ForumRoute route = ForumRoute.fromParts(collection ? "" : id, collection ? id : "",
                    ForumRoute.isForumId(parentFid) ? parentFid : "");
            if (route.key.equals(currentKey) || !seen.add(route.key)) continue;
            Forum forum = new Forum(); forum.key = route.key; forum.fid = route.fid; forum.stid = route.stid;
            forum.parentFid = route.parentFid; forum.name = name; forum.description = text(tuple(row, 2));
            if (forum.description.length() > 400) forum.description = forum.description.substring(0, 400);
            forum.category = collection ? "主题合集" : "子版块";
            forum.icon = route.iconUrl();
            result.add(forum);
        }
        return result;
    }
    private static String tuple(Object row, int index) {
        Object value = row instanceof JSONArray ? ((JSONArray) row).opt(index)
                : row instanceof JSONObject ? ((JSONObject) row).opt(String.valueOf(index)) : null;
        return value instanceof String || value instanceof Number ? String.valueOf(value) : "";
    }

    public List<Topic> searchTopics(String query, String fid, int page) throws Exception {
        String term=query==null?"":query.trim();
        if(term.isEmpty()||term.length()>100)throw new IOException("搜索词请保持在1至100字以内");
        String parameters="key="+encode(term)+"&searchpost=1&__inchst=UTF8&__output=8&page="+page(page);
        if(fid!=null&&!fid.isEmpty()){requireNumber(fid,true);parameters+="&fid="+encode(fid);}
        try {JSONObject root=request("thread.php",parameters,false);throwServerError(root);
            return parseTopics(root,fid==null?"":fid);
        } catch(IOException error) {
            String message=error.getMessage();
            if(message!=null&&message.toLowerCase(Locale.ROOT).matches("(?s).*\\bservice\\s+error\\b.*"))throw new IOException("搜索服务暂时不可用，请稍后重试",error);
            throw error;
        }
    }

    private List<Topic> parseTopics(JSONObject root, String fid) throws Exception {
        JSONObject data = root.optJSONObject("data");
        if (data == null) data = root;
        Object rows = data.opt("__T");
        if (!(rows instanceof JSONObject) && !(rows instanceof JSONArray))
            throw new IOException("服务器未返回可识别的主题列表，请刷新或切换连接域名");
        List<Topic> result = new ArrayList<>();
        String forum = "";
        JSONObject meta = data.optJSONObject("__F");
        if (meta != null) forum = text(meta.optString("name"));
        TopicTimeFormat times = new TopicTimeFormat();
        for (JSONObject row : objects(rows)) {
            Topic topic = parseTopic(row, fid, forum, times);
            JSONObject users = data.optJSONObject("__U");
            JSONObject user = users == null ? null : users.optJSONObject(row.optString("authorid", ""));
            topic.avatar = authorAvatar(row, user);
            if (!topic.tid.matches("[0-9]+")) continue;
            result.add(topic);
        }
        return result;
    }

    private static String authorAvatar(JSONObject row, JSONObject user) {
        String avatar = AvatarSources.normalize(user == null ? "" : user.optString("avatar", ""));
        return avatar.isEmpty() ? AvatarSources.normalize(row.optString("avatar", "")) : avatar;
    }

    private Topic parseTopic(JSONObject row, String fid, String forum) {
        return parseTopic(row, fid, forum, null);
    }

    private Topic parseTopic(JSONObject row, String fid, String forum, TopicTimeFormat times) {
        Topic topic = new Topic();
        topic.tid = row.optString("tid", ""); topic.fid = row.optString("fid", fid);
        topic.title = text(row.optString("subject", "无标题"));
        topic.author = text(row.optString("author", "匿名用户"));
        String authorId = row.optString("authorid", "");
        topic.authorId = authorId.matches("-?[0-9]{1,18}") ? authorId : "";
        topic.replies = Math.max(0, row.optInt("replies"));
        topic.time = time(row.opt("lastpost") == null ? row.opt("postdate") : row.opt("lastpost"), times);
        topic.forum = forum;
        topic.avatar = AvatarSources.normalize(row.optString("avatar", ""));
        topic.votes = VoteMetrics.topicFrom(row);
        return topic;
    }

    /** One bounded native metadata read for a settled visible row; never searches replies or HTML. */
    public List<String> firstPostImages(String tid, String expectedScope) throws Exception {
        if (tid == null || !tid.matches("[1-9][0-9]{0,17}") || expectedScope == null || !expectedScope.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("无效的首帖图片请求");
        NgaStore.CheckInSession session = store.captureCheckInSession();
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest((session.host + "\0" + session.cookie).getBytes(StandardCharsets.UTF_8));
        StringBuilder actual = new StringBuilder(64);
        for (byte value : digest) actual.append(String.format(Locale.ROOT, "%02x", value & 255));
        if (!expectedScope.equals(actual.toString())) throw new java.io.InterruptedIOException("首帖图片请求所属账号已切换");
        JSONObject response = request("read.php", "tid=" + encode(tid) + "&page=1&__output=8", false, session.host, session.cookie);
        return parseFirstPostImages(response, tid);
    }

    private List<String> parseFirstPostImages(JSONObject response, String tid) throws Exception {
        throwServerError(response);
        JSONObject data = response.optJSONObject("data"); if (data == null) data = response;
        JSONObject topic = data.optJSONObject("__T"); Object rows = data.opt("__R");
        if (topic == null || !tid.equals(topic.optString("tid", "")) || !"1".equals(data.optString("__PAGE", ""))
                || !(rows instanceof JSONObject || rows instanceof JSONArray))
            throw new IOException("首帖图片数据与主题不匹配");
        String author = topic.optString("authorid", ""); JSONObject original = null;
        for (JSONObject row : objects(rows)) {
            boolean explicitOriginal = "0".equals(row.optString("lou", "")) || "0".equals(row.optString("index", "")) || "0".equals(row.optString("pid", ""));
            if (!explicitOriginal) continue;
            if (row.has("pid") && !"0".equals(row.optString("pid", ""))) continue;
            if (row.has("lou") && !"0".equals(row.optString("lou", "")) || row.has("index") && !"0".equals(row.optString("index", ""))) continue;
            if (row.has("tid") && !tid.equals(row.optString("tid", ""))) throw new IOException("首帖图片楼层所属主题不匹配");
            if (!author.isEmpty() && !author.equals(row.optString("authorid", ""))) throw new IOException("首帖图片作者不匹配");
            if (original != null) throw new IOException("首帖图片楼层标识重复");
            original = row;
        }
        if (original == null) throw new IOException("服务器未返回明确的楼主首帖");
        Object content = original.opt("content");
        if (content == null || content == JSONObject.NULL) return TopicPreviewImages.withVotes(Collections.<String>emptyList(), VoteMetrics.from(original));
        if (!(content instanceof String)) throw new IOException("首帖正文类型无法识别");
        return TopicPreviewImages.withVotes(TopicPreviewImages.extract((String) content), VoteMetrics.from(original));
    }

    public ThreadPage thread(String tid, int requestedPage) throws Exception {
        return thread(tid,requestedPage,"");
    }

    public ThreadPage thread(String tid, int requestedPage, String pid) throws Exception {
        return thread(tid, requestedPage, pid, "");
    }

    public ThreadPage thread(String tid, int requestedPage, String pid, String authorId) throws Exception {
        requireNumber(tid, false);
        if(authorId==null||!authorId.isEmpty()&&!authorId.matches("[1-9][0-9]{0,17}"))throw new IllegalArgumentException("无效的 NGA 作者编号");
        if (pid != null && !pid.isEmpty() && !"0".equals(pid)) {
            if(!authorId.isEmpty())throw new IllegalArgumentException("请先退出只看楼主，再定位回复");
            if (!pid.matches("[1-9][0-9]{0,17}")) throw new IllegalArgumentException("无效的 NGA 楼层编号");
            int resolvedPage = resolveReplyPage(tid, pid);
            ThreadPage resolved = thread(tid, resolvedPage, "");
            requireReplyTarget(resolved, tid, pid, resolvedPage);
            return resolved;
        }
        String locator="&page="+page(requestedPage)+(authorId.isEmpty()?"":"&authorid="+encode(authorId));
        JSONObject root;
        try {
            root = request("read.php", "tid=" + encode(tid) + locator + "&__output=8", false);
        } catch (InvalidResponseException truncated) {
            // Some legacy JSON pages are truncated by NGA. One official HTML-data fallback;
            // permission/authentication errors are never retried through this path.
            JSONObject html = request("read.php", "tid=" + encode(tid) + locator + "&__output=17", false);
            return parseHtmlThread(html, tid, requestedPage);
        }
        throwServerError(root);
        return parseThread(root, tid, requestedPage);
    }

    /** The official pid&to endpoint redirects to a topic page but drops JSON output parameters. */
    private int resolveReplyPage(String tid, String pid) throws Exception {
        throwIfInterrupted();
        final String host = store.host(), cookie = store.cookie();
        URL origin = new URL("https://" + host + "/read.php?pid=" + encode(pid) + "&to");
        if (!allowed(origin)) throw new IOException("楼层跳转来源不受支持");
        HttpURLConnection connection = (HttpURLConnection) origin.openConnection();
        RequestCancellation.Registration cancellation = null;
        try {
            cancellation = RequestCancellation.track(connection);
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
            connection.setRequestProperty("User-Agent", "NGA_WP_JW");
            connection.setRequestProperty("Referer", "https://" + origin.getHost() + "/");
            if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
            int status = connection.getResponseCode();
            throwIfInterrupted();
            if (status < 300 || status >= 400) throw new IOException("服务器未提供主题内楼层跳转");
            return replyPageFromLocation(origin, tid, connection.getHeaderField("Location"));
        } finally {
            if (cancellation != null) cancellation.close();
            connection.disconnect();
        }
    }

    /** Header metadata only. No redirect request, HTML read, script execution or credential forwarding. */
    private static int replyPageFromLocation(URL origin, String tid, String location) throws IOException {
        if (!allowed(origin) || !"/read.php".equals(origin.getPath()) || location == null || location.isEmpty()
                || location.length() > 4096 || location.matches("(?s).*[\\x00-\\x20\\x7f].*"))
            throw new IOException("楼层跳转地址无法识别");
        URL target = new URL(origin, location);
        if (!allowed(target) || !"/read.php".equals(target.getPath())) throw new IOException("楼层跳转来源不受支持");
        String query = target.getQuery(); String targetTid = "", targetPage = "";
        if (query == null) throw new IOException("楼层跳转缺少主题或页码");
        for (String parameter : query.split("&", -1)) {
            if (parameter.startsWith("tid=")) {
                if (!targetTid.isEmpty()) throw new IOException("楼层跳转主题重复");
                targetTid = parameter.substring(4);
                if (!targetTid.matches("[0-9]{1,18}")) throw new IOException("楼层跳转主题无法识别");
            } else if (parameter.startsWith("page=")) {
                if (!targetPage.isEmpty()) throw new IOException("楼层跳转页码重复");
                targetPage = parameter.substring(5);
                if (!targetPage.matches("[1-9][0-9]{0,5}")) throw new IOException("楼层跳转页码无法识别");
            }
        }
        if (!tid.equals(targetTid) || targetPage.isEmpty()) throw new IOException("楼层跳转主题或页码不匹配");
        int resolved = Integer.parseInt(targetPage);
        if (resolved > 100000) throw new IOException("楼层跳转页码超出范围");
        return resolved;
    }

    private static void requireReplyTarget(ThreadPage response, String tid, String pid, int expectedPage) throws IOException {
        boolean found = false;
        for (Post post : response.posts) if (pid.equals(post.pid)) found = true;
        if (!tid.equals(response.tid) || response.page != expectedPage || !found)
            throw new IOException("服务器返回的主题页未包含目标楼层");
    }

    /** One optional request for the already opened page; never called per row or by topic lists. */
    public Map<String, VoteMetrics> postVotes(String tid, int requestedPage) throws Exception {
        return postVotes(tid,requestedPage,"");
    }
    public Map<String, VoteMetrics> postVotes(String tid, int requestedPage,String authorId) throws Exception {
        requireNumber(tid, false);
        if(authorId==null||!authorId.isEmpty()&&!authorId.matches("[1-9][0-9]{0,17}"))throw new IllegalArgumentException("无效的 NGA 作者编号");
        int resolvedPage = page(requestedPage);
        JSONObject response = request("read.php", "tid=" + encode(tid) + "&page=" + resolvedPage + (authorId.isEmpty()?"":"&authorid="+encode(authorId))+"&__output=17", false);
        return parsePostVotes(response, tid, resolvedPage,authorId);
    }

    private Map<String, VoteMetrics> parsePostVotes(JSONObject response, String tid, int requestedPage) throws Exception {
        return parsePostVotes(response,tid,requestedPage,"");
    }
    private Map<String, VoteMetrics> parsePostVotes(JSONObject response, String tid, int requestedPage,String authorId) throws Exception {
        throwServerError(response);
        String html = response.optString("html", "");
        if (!"read".equals(response.optString("template")) || !html.contains("postliststart"))
            throw new IOException("服务器未返回可识别的点赞数据");
        String htmlTid = capture(html, "\\b__CURRENT_TID\\s*=\\s*([0-9]+)");
        if (!htmlTid.isEmpty() && !tid.equals(htmlTid)) throw new IOException("点赞数据主题不匹配");
        String baseTid = "", basePage = "";
        String base = response.optString("htmlBaseUrl", "");
        if (!base.isEmpty()) {
            URL url = new URL(base);
            if (!allowed(url) || !"/read.php".equals(url.getPath()) || url.getRef() != null)
                throw new IOException("点赞数据来源不匹配");
            String query = url.getQuery() == null ? "" : url.getQuery();
            baseTid = capture(query, "(?:^|&)tid=([0-9]+)(?:&|$)");
            basePage = capture(query, "(?:^|&)page=([0-9]+)(?:&|$)");
            String baseAuthor=capture(query,"(?:^|&)authorid=([0-9]+)(?:&|$)");
            if(!baseAuthor.isEmpty()&&!authorId.equals(baseAuthor))throw new IOException("点赞数据作者筛选不匹配");
            if (!baseTid.isEmpty() && !tid.equals(baseTid)) throw new IOException("点赞数据主题不匹配");
            if (!basePage.isEmpty() && !Integer.toString(requestedPage).equals(basePage))
                throw new IOException("点赞数据页码不匹配");
        }
        if (htmlTid.isEmpty() && baseTid.isEmpty()) throw new IOException("点赞数据缺少主题标识");
        Map<String, String> paging = legacyPageData(html);
        String actualPage = "";
        if (paging != null) {
            actualPage = Integer.toString(legacyPageNumber(paging, "2", 0));
            if ("0".equals(actualPage)) throw new IOException("点赞数据页码无法识别");
        } else if (!baseTid.isEmpty()) actualPage = basePage.isEmpty() ? "1" : basePage;
        if (!Integer.toString(requestedPage).equals(actualPage)) throw new IOException("点赞数据页码不匹配");
        return VoteMetrics.htmlPosts(html);
    }

    private ThreadPage parseThread(JSONObject root, String tid, int requestedPage) throws Exception {
        JSONObject data = root.optJSONObject("data");
        if (data == null) data = root;
        JSONObject meta = data.optJSONObject("__T");
        Object rows = data.opt("__R");
        if (meta == null || (!(rows instanceof JSONObject) && !(rows instanceof JSONArray)))
            throw new IOException("服务器未返回可识别的帖子内容，请刷新或切换连接域名");
        ThreadPage result = new ThreadPage();
        result.tid = meta.optString("tid", tid);
        result.title = text(meta.optString("subject", "帖子详情"));
        result.fid = meta.optString("fid", "");
        String topicAuthor=meta.optString("authorid", "");result.authorId=topicAuthor.matches("[1-9][0-9]{0,17}")?topicAuthor:"";
        result.page = Math.max(1, data.optInt("__PAGE", requestedPage));
        int perPage = Math.max(1, data.optInt("__R__ROWS_PAGE", 20));
        result.totalPages = Math.max(result.page,
                (int) Math.ceil(Math.max(1, data.optInt("__ROWS", 1)) / (double) perPage));
        JSONObject users = data.optJSONObject("__U");
        int offset = (result.page - 1) * perPage;
        for (JSONObject row : objects(rows)) {
            Post post = new Post();
            post.pid = row.optString("pid", "0");
            String uid = row.optString("authorid", "");
            post.authorId = uid;
            JSONObject user = users == null ? null : users.optJSONObject(uid);
            post.author = text(user == null ? row.optString("author", uid) : user.optString("username", uid));
            if (post.author.isEmpty()) post.author = "匿名用户";
            post.avatar = authorAvatar(row, user);
            post.votes = VoteMetrics.from(row);
            post.content = row.optString("content", row.optString("subject", ""));
            post.time = time(row.opt("postdate"));
            post.floor = row.optInt("lou", row.optInt("index", offset + result.posts.size()));
            result.posts.add(post);
        }
        if(result.authorId.isEmpty())for(Post post:result.posts)if(post.floor==0&&post.authorId.matches("[1-9][0-9]{0,17}")){result.authorId=post.authorId;break;}
        return result;
    }

    /** Extracts server-rendered post data only. Scripts, ads and layout are never executed. */
    private ThreadPage parseHtmlThread(JSONObject envelope, String tid, int requestedPage) throws Exception {
        throwServerError(envelope);
        String html = envelope.optString("html", "");
        if (!"read".equals(envelope.optString("template")) || !html.contains("postliststart"))
            throw new IOException("服务器未返回可识别的帖子内容，请刷新或切换连接域名");
        ThreadPage result = new ThreadPage(); result.tid = tid; result.page = page(requestedPage);
        result.title = text(capture(html, "<h1\\b[^>]*id=['\"]currentTopicName['\"][^>]*>(.*?)</h1>"));
        if (result.title.isEmpty()) result.title = text(envelope.optString("htmlTitle", "帖子详情"));
        result.fid = capture(html, "__CURRENT_FID\\s*=\\s*(?:parseInt\\(['\"])?(-?[0-9]+)");
        Map<String, String> paging = legacyPageData(html);
        if (paging != null) {
            result.totalPages = legacyPageNumber(paging, "1", 1);
            result.page = legacyPageNumber(paging, "2", result.page);
            result.totalPages = Math.max(result.totalPages, result.page);
        }
        JSONObject users = null;
        int marker = html.indexOf("commonui.userInfo.setAll(");
        if (marker >= 0) {
            int start = html.indexOf('{', marker);
            if (start >= 0) {
                try {
                    Object value = new JSONTokener(html.substring(start)).nextValue();
                    if (value instanceof JSONObject) users = (JSONObject) value;
                } catch (org.json.JSONException invalidUsers) {
                    throw new IOException("服务器用户资料无法识别，请刷新或切换连接域名", invalidUsers);
                }
            }
        }
        Map<String, VoteMetrics> htmlVotes = VoteMetrics.htmlPosts(html);
        Matcher tables = Pattern.compile("<table\\b[^>]*class=['\"][^'\"]*postbox[^'\"]*['\"][^>]*>(.*?)</table>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(html);
        while (tables.find()) {
            String table = tables.group(1);
            String content = capture(table, "<(p|span)\\b[^>]*id=['\"]postcontent[0-9]+['\"][^>]*>(.*?)</\\1>", 2);
            if (content.isEmpty() && !table.contains("class='postcontent")) continue;
            Post post = new Post();
            post.content = content;
            post.pid = capture(table, "id=['\"]pid([0-9]+)Anchor['\"]");
            String uid = capture(table, "href=['\"][^'\"]*uid=(-?[0-9]+)[^'\"]*['\"][^>]*id=['\"]postauthor[0-9]+['\"]");
            post.authorId = uid;
            JSONObject user = users == null ? null : users.optJSONObject(uid);
            post.author = user == null ? (uid.isEmpty() ? "匿名用户" : "UID:" + uid) : text(user.optString("username", "UID:" + uid));
            post.avatar = AvatarSources.normalize(user == null ? "" : user.optString("avatar", ""));
            post.time = text(capture(table, "<span\\b[^>]*id=['\"]postdate[0-9]+['\"][^>]*>(.*?)</span>"));
            String floor = capture(table, "name=['\"]l([0-9]+)['\"]");
            post.floor = floor.isEmpty() ? (result.page - 1) * 20 + result.posts.size() : Integer.parseInt(floor);
            VoteMetrics votes = htmlVotes.get(post.floor + ":" + post.pid);
            if (votes != null) post.votes = votes;
            result.posts.add(post);
        }
        if (result.posts.isEmpty()) throw new IOException("服务器未返回可识别的楼层，请刷新或切换连接域名");
        for(Post post:result.posts)if(post.floor==0&&post.authorId.matches("[1-9][0-9]{0,17}")){result.authorId=post.authorId;break;}
        return result;
    }

    /** Official __PAGE is a JavaScript scalar object, not JSON: Android rejects its numeric keys. */
    private static Map<String, String> legacyPageData(String html) throws IOException {
        Matcher marker = Pattern.compile("\\bvar\\s+__PAGE\\s*=\\s*").matcher(html);
        if (!marker.find()) return null;
        return new LegacyPageReader(html, marker.end()).read();
    }
    private static int legacyPageNumber(Map<String, String> paging, String key, int fallback) throws IOException {
        String value = paging.get(key);
        if (value == null) return fallback;
        if (!value.matches("[1-9][0-9]{0,5}")) throw new IOException("服务器分页数据无法识别");
        int number = Integer.parseInt(value);
        if (number > 100000) throw new IOException("服务器分页数据超出范围");
        return number;
    }
    /** Bounded data-only tokenizer. No JS expressions, eval, key rewriting, or JSON implementation quirks. */
    private static final class LegacyPageReader {
        private final String source;
        private final int limit;
        private int offset;
        LegacyPageReader(String source, int offset) {
            this.source = source; this.offset = offset; limit = Math.min(source.length(), offset + 8192);
        }
        Map<String, String> read() throws IOException {
            Map<String, String> result = new java.util.HashMap<>();
            expect('{'); space();
            if (peek() == '}') throw invalid();
            while (true) {
                space(); char first = peek();
                String key = first == '\'' || first == '"' ? quoted() : number(false);
                if (!key.matches("0|[1-9][0-9]{0,5}") || result.containsKey(key) || result.size() >= 16) throw invalid();
                space(); expect(':'); space(); first = peek();
                String value = first == '\'' || first == '"' ? quoted() : number(true);
                result.put(key, value); space();
                char delimiter = peek(); offset++;
                if (delimiter == '}') break;
                if (delimiter != ',') throw invalid();
                space(); if (peek() == '}') { offset++; break; }
            }
            space();
            if (offset < limit && peek() != ';' && peek() != ',') throw invalid();
            return result;
        }
        private String number(boolean signed) throws IOException {
            int start = offset;
            if (signed && peek() == '-') offset++;
            int digits = offset;
            while (offset < limit && source.charAt(offset) >= '0' && source.charAt(offset) <= '9') offset++;
            if (offset == digits || offset - digits > 18) throw invalid();
            return source.substring(start, offset);
        }
        private String quoted() throws IOException {
            char quote = peek(); offset++; StringBuilder result = new StringBuilder();
            while (offset < limit) {
                char value = source.charAt(offset++);
                if (value == quote) return result.toString();
                if (value < 0x20 || result.length() >= 4096) throw invalid();
                if (value == '\\') {
                    value = peek(); offset++;
                    switch (value) {
                        case '\'': case '"': case '\\': case '/': break;
                        case 'b': value = '\b'; break;
                        case 'f': value = '\f'; break;
                        case 'n': value = '\n'; break;
                        case 'r': value = '\r'; break;
                        case 't': value = '\t'; break;
                        case 'u': value = hexadecimal(4); break;
                        case 'x': value = hexadecimal(2); break;
                        default: throw invalid();
                    }
                }
                result.append(value);
            }
            throw invalid();
        }
        private char hexadecimal(int count) throws IOException {
            int value = 0;
            for (int i = 0; i < count; i++) {
                char digit = peek(); offset++; int decoded = Character.digit(digit, 16);
                if (decoded < 0 || digit > 127) throw invalid();
                value = value * 16 + decoded;
            }
            return (char) value;
        }
        private void space() {
            while (offset < limit) {
                char value = source.charAt(offset);
                if (value != ' ' && value != '\t' && value != '\r' && value != '\n') break;
                offset++;
            }
        }
        private char peek() throws IOException { if (offset >= limit) throw invalid(); return source.charAt(offset); }
        private void expect(char value) throws IOException { if (peek() != value) throw invalid(); offset++; }
        private IOException invalid() { return new IOException("服务器分页数据无法识别"); }
    }

    private static String capture(String value, String pattern) { return capture(value, pattern, 1); }
    private static String capture(String value, String pattern, int group) {
        Matcher matcher = Pattern.compile(pattern, Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(value);
        return matcher.find() ? matcher.group(group) : "";
    }

    private static final class InvalidResponseException extends IOException {
        private static final long serialVersionUID=1L;
        InvalidResponseException(String message) { super(message); }
    }

    public CheckInResult checkIn() throws Exception { return checkIn(null); }
    /** One immutable request account; a late response cannot complete a different account's day. */
    public CheckInResult checkIn(String attemptSource) throws Exception { return checkIn(attemptSource,null); }
    /** A foreground queue ticket cannot become a request for a newly selected session. */
    CheckInResult checkIn(String attemptSource,NgaStore.CheckInSession expected) throws Exception {
        NgaStore.CheckInSession current=store.captureCheckInSession();
        if(expected!=null&&(!expected.host.equals(current.host)||!expected.account.equals(current.account)||!expected.cookie.equals(current.cookie)))
            return result(false,false,"签到会话已变化，请在当前账号重试");
        NgaStore.CheckInSession session=expected==null?current:expected;
        if(session.account.isEmpty())return result(false,false,"请先登录 NGA 后再签到");
        if(attemptSource!=null&&!store.recordCheckInAttemptForAccount(session.account,attemptSource))return result(false,false,"签到账号已变化，请在当前账号重试");
        try {
            JSONObject root=request("nuke.php","__lib=check_in&__act=check_in&__output=11&__inchst=UTF8",true,session.host,session.cookie);
            CheckInResult result=parseCheckInResult(root);
            if(!store.saveCheckInResultForAccount(session.account,result.success,result.message))return result(false,false,"签到账号已变化，本次结果未写入当前账号");
            return result;
        }catch(Exception error){
            String detail=error.getMessage();
            store.saveCheckInResultForAccount(session.account,false,"签到未完成："+(detail==null||detail.isEmpty()?"网络请求失败，请稍后重试":detail));
            throw error;
        }
    }

    private CheckInResult parseCheckInResult(JSONObject root) {
        String error = message(root.opt("error"));
        String serverMessage = error.isEmpty() ? message(root.opt("data")) : error;
        boolean negative = serverMessage.contains("未签到") || serverMessage.contains("失败") ||
                serverMessage.contains("不成功") || serverMessage.contains("未能签到") ||
                serverMessage.contains("无法签到") || serverMessage.contains("没有签到");
        boolean already = !negative && (serverMessage.contains("今天已签到") ||
                serverMessage.contains("今天已经签到") || serverMessage.contains("今日已签到") ||
                serverMessage.contains("今天已经签过") || serverMessage.contains("已经签到了"));
        boolean success = already || (!negative && error.isEmpty() && serverMessage.contains("签到成功"));
        // Only explicit server confirmation succeeds. Statistics are internal response data,
        // and do not belong in the user's success message or prove today's check-in.
        if (success) return result(true, already, already ? "今天已签到" : "签到成功");
        if (serverMessage.isEmpty()) serverMessage = "服务器未明确确认签到成功，请稍后重试";
        return result(false, false, serverMessage);
    }

    public CheckInResult checkInStatus() throws Exception {
        if (!store.loggedIn()) return result(false, false, "请先登录 NGA");
        JSONObject root = request("nuke.php", "__lib=check_in&__act=get_stat&__output=11", false);
        throwServerError(root);
        // Statistics are not proof of a successful check-in today.
        return result(false, false, message(root.opt("data")));
    }

    private CheckInResult result(boolean success, boolean already, String message) {
        CheckInResult result = new CheckInResult();
        result.success = success; result.already = already; result.message = message;
        return result;
    }

    private JSONObject request(String path, String query, boolean post) throws Exception { return request(path,query,post,null,null); }
    private JSONObject request(String path,String query,boolean post,String fixedHost,String fixedCookie) throws Exception {
        return request(path, query, post, fixedHost, fixedCookie, null);
    }
    private JSONObject request(String path,String query,boolean post,String fixedHost,String fixedCookie,ForumReadTrace trace) throws Exception {
        throwIfInterrupted();
        URL next = new URL("https://" + (fixedHost==null?store.host():fixedHost) + "/" + path + "?" + query);
        // One request, including its redirects, keeps the identity it started with.
        // Re-reading the cookie on each hop could silently switch accounts in flight.
        final String cookie = fixedCookie==null?store.cookie():fixedCookie;
        for (int redirects = 0; redirects <= 3; redirects++) {
            throwIfInterrupted();
            if (!allowed(next)) throw new IOException("已阻止非 NGA 站点的网络跳转");
            HttpURLConnection connection = (HttpURLConnection) next.openConnection();
            RequestCancellation.Registration cancellation = null;
            try {
                cancellation = RequestCancellation.track(connection);
                connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "NGA_WP_JW");
                connection.setRequestProperty("Accept", "application/json,text/plain;q=0.9");
                connection.setRequestProperty("Referer", "https://" + next.getHost() + "/");
                if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
                if (post) {
                    throwIfInterrupted();
                    connection.setRequestMethod("POST"); connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                    connection.setFixedLengthStreamingMode(0);
                    connection.getOutputStream().close();
                }
                long headersStarted = trace == null ? 0 : System.nanoTime();
                if (trace != null) trace.requestCount++;
                int status = connection.getResponseCode();
                if (trace != null) trace.headersNanos += System.nanoTime() - headersStarted;
                throwIfInterrupted();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirects == 3) throw new IOException("NGA 服务器跳转次数过多");
                    URL target = new URL(next, location);
                    if (!allowed(target)) throw new IOException("已阻止非 NGA 站点的网络跳转");
                    if (post) throw new IOException("签到请求发生跳转，请切换 NGA 线路后重试");
                    next = target;
                    continue;
                }
                long bodyStarted = trace == null ? 0 : System.nanoTime();
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                byte[] bytes = readLimited(stream);
                if (trace != null) { trace.bodyNanos += System.nanoTime() - bodyStarted; trace.responseBytes += bytes.length; }
                throwIfInterrupted();
                JSONObject parsed;
                long parsingStarted = trace == null ? 0 : System.nanoTime();
                try { parsed = parseResponse(bytes, connection.getContentType()); }
                catch (Exception malformed) {
                    if (status >= 400) throw new IOException("NGA 服务器返回 HTTP " + status + "，请重新扫码登录或切换线路后重试");
                    throw new InvalidResponseException("服务器返回了网页或无法识别的数据，请稍后重试或切换连接域名");
                }
                if (trace != null) trace.parsedNanos += System.nanoTime() - parsingStarted;
                throwIfInterrupted();
                if (status >= 400) {
                    String error = message(parsed.opt("error"));
                    throw new IOException(error.isEmpty() ? "NGA 服务器返回 HTTP " + status : error);
                }
                return parsed;
            } finally {
                if (cancellation != null) cancellation.close();
                connection.disconnect();
            }
        }
        throw new IOException("NGA 服务器跳转失败");
    }

    public static boolean allowed(URL url) {
        return "https".equalsIgnoreCase(url.getProtocol()) && NgaStore.HOSTS.contains(url.getHost().toLowerCase(Locale.ROOT)) &&
                (url.getPort() == -1 || url.getPort() == 443) && url.getUserInfo() == null;
    }

    private byte[] readLimited(InputStream source) throws IOException {
        if (source == null) return new byte[0];
        try (InputStream input = source; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            while (true) {
                throwIfInterrupted();
                int count = input.read(buffer);
                throwIfInterrupted();
                if (count == -1) break;
                if (output.size() + count > 8 * 1024 * 1024) throw new IOException("服务器响应过大");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    /** Cooperative cancellation preserves the flag for the worker's lifecycle owner. */
    private static void throwIfInterrupted() throws java.io.InterruptedIOException {
        RequestCancellation.check();
    }

    /** Parses data only; never evaluates legacy window.script assignments. */
    private JSONObject parseResponse(byte[] bytes, String contentType) throws Exception {
        String raw;
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("gbk") || type.contains("gb2312") || type.contains("gb18030")) {
            raw = new String(bytes, Charset.forName("GB18030"));
        } else {
            try {
                raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException invalidUtf8) { raw = new String(bytes, Charset.forName("GB18030")); }
        }
        raw = raw.trim();
        if (raw.startsWith("\uFEFF")) raw = raw.substring(1).trim();
        raw = raw.replace("/*$js$*/", "").trim();
        if (raw.startsWith("window.script_muti_get_var_store") || raw.startsWith("window.script_multi_get_var_store")) {
            int equals = raw.indexOf('=');
            if (equals < 0) throw new IOException("无效的响应前缀");
            raw = raw.substring(equals + 1).trim();
        }
        int endMarker = raw.indexOf("/*error fill content");
        if (endMarker >= 0) raw = raw.substring(0, endMarker).trim();
        if (!raw.startsWith("{")) throw new IOException("不是 JSON 响应");
        JSONTokener tokener = new JSONTokener(raw);
        Object decoded = tokener.nextValue();
        if (!(decoded instanceof JSONObject)) throw new IOException("不是 JSON 对象");
        return (JSONObject) decoded;
    }

    private void throwServerError(JSONObject object) throws IOException {
        String error = message(object.opt("error"));
        if (!error.isEmpty()) throw new IOException(error);
    }
    private static List<JSONObject> objects(Object values) {
        List<JSONObject> result = new ArrayList<>();
        if (values instanceof JSONArray) {
            JSONArray array = (JSONArray) values;
            for (int i = 0; i < array.length(); i++) { JSONObject object = array.optJSONObject(i); if (object != null) result.add(object); }
        } else if (values instanceof JSONObject) {
            JSONObject object = (JSONObject) values;
            List<String> keys = new ArrayList<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) { String key = iterator.next(); if (key.matches("[0-9]{1,8}")) keys.add(key); }
            Collections.sort(keys, (a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)));
            for (String key : keys) { JSONObject row = object.optJSONObject(key); if (row != null) result.add(row); }
        }
        return result;
    }
    private String message(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof String || value instanceof Number) return text(String.valueOf(value));
        StringBuilder output = new StringBuilder();
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length() && output.length() < 2000; i++) appendMessage(output, message(array.opt(i)));
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext() && output.length() < 2000) {
                String key = keys.next();
                Object item = object.opt(key);
                if (key.matches("[0-9]+") || key.equals("message") || key.equals("msg")) appendMessage(output, message(item));
                else if (item instanceof String || item instanceof Number) appendMessage(output, key + ": " + item);
            }
        }
        return output.length() > 2000 ? output.substring(0, 2000) : output.toString();
    }
    private void appendMessage(StringBuilder builder, String value) {
        if (value.isEmpty()) return;
        if (builder.length() > 0) builder.append('\n');
        builder.append(value);
    }
    private static String encode(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
    private static int page(int page) { return Math.max(1, Math.min(100000, page)); }
    private static void requireNumber(String value, boolean signed) {
        if (value == null || !value.matches(signed ? "-?[0-9]{1,18}" : "[0-9]{1,18}")) throw new IllegalArgumentException("无效的 NGA 编号");
    }
    private static String text(String raw) {
        return Html.fromHtml(raw == null ? "" : raw, Html.FROM_HTML_MODE_LEGACY).toString().trim();
    }
    private static String time(Object value) {
        return time(value, null);
    }
    private static String time(Object value, TopicTimeFormat times) {
        if (value == null || value == JSONObject.NULL) return "";
        try {
            long seconds = Long.parseLong(String.valueOf(value));
            if (seconds <= 0 || seconds > 253402300799L) return "";
            SimpleDateFormat format = times == null ? newTimeFormat() : times.get();
            return format.format(new Date(seconds * 1000L));
        } catch (NumberFormatException ignored) { return text(String.valueOf(value)); }
    }
    private static SimpleDateFormat newTimeFormat() {
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return format;
    }
    /** Owned by one parseTopics call only: SimpleDateFormat must never cross worker threads. */
    private static final class TopicTimeFormat {
        private SimpleDateFormat format;
        SimpleDateFormat get() {
            if (format == null) format = newTimeFormat();
            return format;
        }
    }
}
