package cn.ngapure.verify;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import cn.ngapure.app.NgaApi;
import cn.ngapure.app.VoteMetrics;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded public GET comparison, plus one production-session public metadata read.
 * The three route variants use an explicit empty Cookie override. No UI, SMS or remote writes.
 * No ID, URL, body, title, author, exception message or response text is emitted.
 */
public final class ReplyRouteProbe extends Instrumentation {
    private static final long LIMIT_MILLIS = 90000;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final JSONObject report = defaults();
    private NgaApi api;
    private Method request, topics, thread, serverError;
    private String host = "ngabbs.com";
    private boolean headersOnly;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        if (args != null) { host = args.getString("host", "ngabbs.com"); headersOnly = "true".equals(args.getString("headersOnly")); }
        start();
    }
    @Override public void onStart() {
        Thread watchdog = new Thread(() -> { SystemClock.sleep(LIMIT_MILLIS); complete("DEADLINE_EXCEEDED"); }, "reply-route-probe-deadline");
        watchdog.setDaemon(true); watchdog.start();
        try {
            if (!("bbs.nga.cn".equals(host) || "ngabbs.com".equals(host) || "nga.178.com".equals(host)))
                throw new ProbeFailure("HOST_NOT_ALLOWED");
            api = new NgaApi(getTargetContext());
            request = method("request", String.class, String.class, boolean.class, String.class, String.class);
            topics = method("parseTopics", JSONObject.class, String.class);
            thread = method("parseThread", JSONObject.class, String.class, int.class);
            serverError = method("throwServerError", JSONObject.class);
            Selection selected = select();
            if (selected == null) throw new ProbeFailure("NO_ELIGIBLE_PUBLIC_REPLY");
            put("selectionFound", true); put("selectionPage", selected.page);
            put("selectionPosts", selected.posts); put("selectionTargetFloor", selected.floor);
            put("pidToHeaders", headerOnly(selected));
            if (headersOnly) { complete("NONE"); return; }
            JSONObject votes = emptyVotes();
            try {
                Map<String, VoteMetrics> values = api.postVotes(selected.tid, 2);
                int explicit = 0, scalar = 0;
                for (VoteMetrics value : values.values()) {
                    if (value.likesKnown) explicit++; else if (value.scoreKnown) scalar++;
                }
                votes.put("available", true); votes.put("mapSize", values.size());
                votes.put("likesKnown", explicit); votes.put("scoreOnly", scalar);
            } catch (Exception unavailable) { putIn(votes, "errorType", metadataError(unavailable)); }
            put("postVotes", votes);
            String[] names = {"PID_TO_EMPTY_OUTPUT8", "PID_TO_ONE_OUTPUT8", "TID_PID_TO_ONE_OUTPUT8"};
            String[] queries = {"pid=" + selected.pid + "&to&__output=8",
                    "pid=" + selected.pid + "&to=1&__output=8",
                    "tid=" + selected.tid + "&pid=" + selected.pid + "&to=1&__output=8"};
            for (int i = 0; i < names.length && !finished.get(); i++) {
                JSONObject row = emptyRoute();
                try {
                    JSONObject response = get("read.php", queries[i]);
                    invoke(serverError, response);
                    NgaApi.ThreadPage page = (NgaApi.ThreadPage) invoke(thread, response, selected.tid, 1);
                    JSONObject data = response.optJSONObject("data"); if (data == null) data = response;
                    row.put("available", true); row.put("page", page.page); row.put("posts", page.posts.size());
                    row.put("topicMatches", selected.tid.equals(page.tid));
                    boolean found = false;
                    for (NgaApi.Post post : page.posts) if (selected.pid.equals(post.pid)) found = true;
                    row.put("targetPidFound", found);
                    Object rawPage = data.opt("__PAGE");
                    row.put("pageMetaKind", rawPage instanceof JSONObject ? "OBJECT" : rawPage instanceof Number || rawPage instanceof String ? "SCALAR" : rawPage == null || rawPage == JSONObject.NULL ? "MISSING" : "OTHER");
                    row.put("declaredPage", integer(rawPage instanceof JSONObject ? ((JSONObject) rawPage).opt("2") : rawPage, 0));
                    row.put("rowFrom", integer(data.opt("__R__FROM"), -1));
                    int perPage = integer(data.opt("__R__ROWS_PAGE"), 20);
                    row.put("rowsPerPage", Math.max(1, perPage));
                    Object rawRows = data.opt("__R");
                    JSONObject first = rawRows instanceof JSONArray ? ((JSONArray) rawRows).optJSONObject(0)
                            : rawRows instanceof JSONObject ? ((JSONObject) rawRows).optJSONObject("0") : null;
                    int firstLou = first == null ? -1 : integer(first.opt("lou"), -1);
                    row.put("firstExplicitLou", firstLou);
                    row.put("explicitLouDerivedPage", firstLou >= 0 ? firstLou / Math.max(1, perPage) + 1 : 0);
                } catch (Exception unavailable) { row = emptyRoute(); putIn(row, "errorType", classify(unavailable)); }
                put(names[i], row);
                put("completedVariants", i + 1);
            }
            complete("NONE");
        } catch (ProbeFailure failure) { complete(failure.code); }
        catch (Exception unavailable) { complete(classify(unavailable)); }
    }

    @SuppressWarnings("unchecked")
    private Selection select() throws Exception {
        JSONObject catalog = get("thread.php", "fid=414&page=1&__output=8&__inchst=UTF8");
        put("preparationRequests", 1); invoke(serverError, catalog);
        List<NgaApi.Topic> rows = (List<NgaApi.Topic>) invoke(topics, catalog, "414");
        for (NgaApi.Topic topic : rows) {
            if (topic.replies < 30 || !positive(topic.tid)) continue;
            // Only the first eligible public topic; no retry crawl or private endpoints.
            JSONObject response = get("read.php", "tid=" + topic.tid + "&page=2&__output=8");
            put("preparationRequests", 2); invoke(serverError, response);
            NgaApi.ThreadPage page = (NgaApi.ThreadPage) invoke(thread, response, topic.tid, 2);
            if (!topic.tid.equals(page.tid) || page.page < 2) return null;
            for (NgaApi.Post post : page.posts) if (positive(post.pid))
                return new Selection(topic.tid, post.pid, page.page, page.posts.size(), post.floor);
            return null;
        }
        return null;
    }
    private JSONObject get(String path, String query) throws Exception {
        if (finished.get()) throw new ProbeFailure("DEADLINE_EXCEEDED");
        // fixedCookie="" bypasses store.cookie(), including after a redirect.
        return (JSONObject) invoke(request, path, query, false, host, "");
    }
    private static Method method(String name, Class<?>... types) throws NoSuchMethodException {
        Method value = NgaApi.class.getDeclaredMethod(name, types); value.setAccessible(true); return value;
    }
    private Object invoke(Method target, Object... values) throws Exception {
        try { return target.invoke(api, values); }
        catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new ProbeFailure("PARSER_UNAVAILABLE");
        }
    }
    private static int integer(Object value, int fallback) {
        if (!(value instanceof Number || value instanceof String)) return fallback;
        String text = String.valueOf(value);
        if (!text.matches("[0-9]{1,7}")) return fallback;
        try { return Integer.parseInt(text); } catch (NumberFormatException invalid) { return fallback; }
    }
    private static boolean positive(String value) { return value != null && value.matches("[1-9][0-9]{0,17}"); }
    private JSONObject headerOnly(Selection selected) {
        JSONObject value = emptyHeaders(); HttpURLConnection connection = null;
        try {
            URL source = new URL("https://" + host + "/read.php?pid=" + selected.pid + "&to");
            connection = (HttpURLConnection) source.openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
            connection.setRequestMethod("GET"); connection.setRequestProperty("Cookie", "");
            connection.setRequestProperty("User-Agent", "NGA_WP_JW");
            connection.setRequestProperty("Referer", "https://" + host + "/");
            int status = connection.getResponseCode();
            value.put("status", status >= 100 && status <= 599 ? status : 0);
            String type = connection.getContentType(); type = type == null ? "" : type.toLowerCase(Locale.ROOT);
            value.put("contentType", type.contains("json") ? "JSON" : type.contains("html") ? "HTML" : "OTHER");
            String location = connection.getHeaderField("Location");
            boolean present = location != null && !location.isEmpty(); value.put("redirectPresent", present);
            if (present && location.length() <= 4096) {
                URL target = new URL(source, location);
                boolean allowed = NgaApi.allowed(target);
                value.put("locationAllowed", allowed);
                value.put("pathRead", allowed && "/read.php".equals(target.getPath()));
                if (allowed) {
                    String query = target.getQuery() == null ? "" : target.getQuery();
                    value.put("locationTidMatches", selected.tid.equals(queryNumber(query, "tid")));
                    value.put("resolvedPage", integer(queryNumber(query, "page"), 0));
                    value.put("locationCarriesOutput", Pattern.compile("(?:^|&)__output(?:=|&|$)").matcher(query).find());
                }
            }
            // Deliberately never call getInputStream/getErrorStream or read a response body.
        } catch (Exception unavailable) { putIn(value, "errorType", classify(unavailable)); }
        finally { if (connection != null) connection.disconnect(); }
        return value;
    }
    private static String queryNumber(String query, String name) {
        Matcher matcher = Pattern.compile("(?:^|&)" + name + "=([0-9]{1,18})(?:&|$)").matcher(query);
        return matcher.find() ? matcher.group(1) : "";
    }
    private static JSONObject emptyHeaders() {
        JSONObject value = new JSONObject();
        putIn(value, "status", 0); putIn(value, "redirectPresent", false);
        putIn(value, "locationAllowed", false); putIn(value, "pathRead", false);
        putIn(value, "locationTidMatches", false); putIn(value, "resolvedPage", 0);
        putIn(value, "locationCarriesOutput", false); putIn(value, "contentType", "OTHER");
        putIn(value, "errorType", "NONE"); return value;
    }
    private static String classify(Exception failure) {
        if (failure instanceof ProbeFailure) return ((ProbeFailure) failure).code;
        if (failure instanceof SocketTimeoutException) return "NETWORK_TIMEOUT";
        if (failure instanceof UnknownHostException) return "NETWORK_DNS";
        if (failure instanceof javax.net.ssl.SSLException) return "NETWORK_TLS";
        if (failure instanceof org.json.JSONException) return "INVALID_RESPONSE";
        if (failure instanceof ReflectiveOperationException) return "PARSER_UNAVAILABLE";
        if (failure instanceof java.io.IOException) return "HTTP_OR_RESPONSE_UNAVAILABLE";
        return "PROBE_UNAVAILABLE";
    }
    private static String metadataError(Exception failure) {
        // Only keyword-to-enum mapping. The message itself never leaves this method.
        String message = failure.getMessage();
        if (message != null) {
            if (message.contains("主题")) return "TOPIC_IDENTITY_REJECTED";
            if (message.contains("页码")) return "PAGE_IDENTITY_REJECTED";
            if (message.contains("来源")) return "ORIGIN_REJECTED";
            if (message.contains("template") || message.contains("模板") || message.contains("点赞数据")) return "TEMPLATE_OR_MARKER_REJECTED";
            if (message.contains("HTTP")) return "HTTP_UNAVAILABLE";
            if (message.contains("网页") || message.contains("JSON")) return "HTML_OR_JSON_UNAVAILABLE";
        }
        return classify(failure);
    }
    private static JSONObject emptyVotes() {
        JSONObject row = new JSONObject();
        putIn(row, "available", false); putIn(row, "mapSize", 0); putIn(row, "likesKnown", 0);
        putIn(row, "scoreOnly", 0); putIn(row, "errorType", "NONE"); return row;
    }
    private static JSONObject emptyRoute() {
        JSONObject row = new JSONObject();
        putIn(row, "available", false); putIn(row, "page", 0); putIn(row, "posts", 0);
        putIn(row, "topicMatches", false); putIn(row, "targetPidFound", false);
        putIn(row, "pageMetaKind", "MISSING"); putIn(row, "declaredPage", 0);
        putIn(row, "rowFrom", -1); putIn(row, "rowsPerPage", 0);
        putIn(row, "firstExplicitLou", -1); putIn(row, "explicitLouDerivedPage", 0);
        putIn(row, "errorType", "NONE"); return row;
    }
    private static JSONObject defaults() {
        JSONObject value = new JSONObject();
        putIn(value, "schema", 1); putIn(value, "purpose", "ANONYMOUS_PUBLIC_REPLY_ROUTE_COMPARISON");
        putIn(value, "routeCookieForwarded", false); putIn(value, "postVotesUsesProductionPublicRead", true);
        putIn(value, "remoteWrites", false); putIn(value, "postVotes", emptyVotes());
        putIn(value, "pidToHeaders", emptyHeaders());
        putIn(value, "selectionFound", false); putIn(value, "selectionPage", 0);
        putIn(value, "selectionPosts", 0); putIn(value, "selectionTargetFloor", -1);
        putIn(value, "preparationRequests", 0); putIn(value, "completedVariants", 0);
        putIn(value, "PID_TO_EMPTY_OUTPUT8", emptyRoute()); putIn(value, "PID_TO_ONE_OUTPUT8", emptyRoute());
        putIn(value, "TID_PID_TO_ONE_OUTPUT8", emptyRoute()); putIn(value, "errorType", "NONE"); return value;
    }
    private void put(String key, Object value) { synchronized (report) { if (!finished.get()) putIn(report, key, value); } }
    private static void putIn(JSONObject object, String key, Object value) { try { object.put(key, value); } catch (Exception ignored) { } }
    private void complete(String error) {
        if (!finished.compareAndSet(false, true)) return;
        Bundle result = new Bundle();
        synchronized (report) { putIn(report, "errorType", error); result.putString("safe_reply_routes", report.toString()); }
        finish(Activity.RESULT_OK, result);
    }
    private static final class Selection {
        final String tid, pid; final int page, posts, floor;
        Selection(String tid, String pid, int page, int posts, int floor) { this.tid = tid; this.pid = pid; this.page = page; this.posts = posts; this.floor = floor; }
    }
    private static final class ProbeFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final String code;
        ProbeFailure(String code) { this.code = code; }
    }
}
