package cn.ngapure.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Official public ratings reader. Invoke from a worker thread; no voting writes. */
public final class NgaRatings {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final NgaStore store;

    public NgaRatings(Context context) { store = new NgaStore(context.getApplicationContext()); }

    public static final class Category {
        public String id = "", name = "", fid = "";
    }
    public static final class Item {
        public String tid = "", name = "", image = "", score = "";
        public int scoreCount;
        public List<String> tags = new ArrayList<>();
    }
    public static final class Page {
        public List<Item> items = new ArrayList<>();
        public List<Category> categories = new ArrayList<>();
        public boolean hasNext;
        public int page = 1;
    }

    public List<Category> categories() throws Exception { return list("", 1).categories; }

    public Page list(String catid, int page) throws Exception {
        RequestCancellation.check();
        String category = catid == null ? "" : catid.trim();
        if (!category.isEmpty() && !category.matches("[0-9]{1,10}"))
            throw new IOException("评分分类无效");
        if (page < 1 || page > 10000) throw new IOException("评分页码无效");
        String host = store.host();
        if (!NgaStore.HOSTS.contains(host)) throw new IOException("不支持的 NGA 域名");
        URL url = new URL("https", host, "/nuke.php?__lib=vote&__act=get_score_list");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        RequestCancellation.Registration cancellation = null;
        try {
            cancellation = RequestCancellation.track(connection);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(25000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "NgaPure/0.2 Android");
            // Cookies only go to this explicit HTTPS origin. Redirects are never followed.
            String cookie = store.cookie();
            if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
            byte[] body = ("__output=14&__inchst=UTF8&page=" + page + "&catid=" +
                    URLEncoder.encode(category, "UTF-8")).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            RequestCancellation.check();
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(body); }
            int status = connection.getResponseCode();
            RequestCancellation.check();
            if (status >= 300 && status < 400) throw new IOException("评分接口发生跳转，请切换 NGA 域名重试");
            if (status != 200) throw new IOException("评分服务暂时不可用（HTTP " + status + "）");
            if (connection.getContentLengthLong() > MAX_BYTES) throw new IOException("评分数据过大");
            byte[] response;
            try (InputStream in = connection.getInputStream()) { response = readLimited(in); }
            RequestCancellation.check();
            Page parsed = parse(response, category, page);
            RequestCancellation.check();
            return parsed;
        } finally { if (cancellation != null) cancellation.close(); connection.disconnect(); }
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (true) {
            RequestCancellation.check();
            int length = in.read(buffer);
            RequestCancellation.check();
            if (length == -1) break;
            if (out.size() + length > MAX_BYTES) throw new IOException("评分数据过大");
            out.write(buffer, 0, length);
        }
        return out.toByteArray();
    }

    /** Kept separate from transport so captured public fixtures can verify the contract. */
    static Page parse(byte[] response, String requestedCategory, int requestedPage) throws Exception {
        JSONObject root;
        try { root = new JSONObject(new String(response, StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new IOException("评分服务未返回有效数据，请稍后重试"); }
        if (!root.has("code") || root.optInt("code", -1) != 0) {
            String message = clean(root.optString("msg", root.optString("message", "评分请求失败")));
            if (message.isEmpty()) message = "评分请求失败";
            throw new IOException(message);
        }
        JSONArray result = root.optJSONArray("result");
        JSONObject data = result == null ? null : result.optJSONObject(0);
        if (data == null || data.optJSONArray("topics") == null)
            throw new IOException("服务器未返回可识别的评分列表");
        String actual = data.optString("catid", "");
        if (requestedCategory != null && !requestedCategory.isEmpty() && !requestedCategory.equals(actual))
            throw new IOException("该分类使用官方游戏库，请在官方评分页查看");
        Page page = new Page();
        page.page = Math.max(1, data.optInt("page", requestedPage));
        page.hasNext = data.optInt("next", 0) != 0 || data.optBoolean("next", false);
        JSONArray categories = result.optJSONArray(1);
        Set<String> seenCategories = new HashSet<>();
        if (categories != null) for (int i = 0; i < categories.length(); i++) {
            JSONObject row = categories.optJSONObject(i);
            if (row == null) continue;
            String id = row.optString("catid", "");
            if (!id.matches("[0-9]{1,10}") || !seenCategories.add(id)) continue;
            Category entry = new Category();
            entry.id = id; entry.name = clean(row.optString("name", ""));
            entry.fid = row.optString("fid", "");
            if (!entry.fid.matches("-?[0-9]{1,18}")) entry.fid = "";
            if (!entry.name.isEmpty()) page.categories.add(entry);
        }
        JSONArray topics = data.optJSONArray("topics");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < topics.length(); i++) {
            JSONObject row = topics.optJSONObject(i);
            if (row == null) continue;
            String tid = row.optString("tid", "");
            if (!tid.matches("[0-9]{1,18}") || !seen.add(tid)) continue;
            Item item = new Item();
            item.tid = tid; item.name = clean(row.optString("name", ""));
            if (item.name.isEmpty()) continue;
            JSONArray images = row.optJSONArray("image");
            if (images != null && images.length() > 0) item.image = imageUrl(images.optString(0, ""));
            if (!row.isNull("score")) {
                double score = row.optDouble("score", Double.NaN);
                if (!Double.isNaN(score) && !Double.isInfinite(score) && score >= 0 && score <= 10)
                    item.score = String.format(Locale.ROOT, "%.1f", score);
            }
            item.scoreCount = Math.max(0, row.optInt("score_count", 0));
            addTags(item.tags, row.optJSONArray("type"), "name");
            addTags(item.tags, row.optJSONArray("release_info"), "platform");
            page.items.add(item);
        }
        return page;
    }

    private static void addTags(List<String> tags, JSONArray entries, String key) {
        if (entries == null) return;
        for (int i = 0; i < entries.length() && tags.size() < 10; i++) {
            JSONObject entry = entries.optJSONObject(i);
            String text = entry == null ? "" : clean(entry.optString(key, ""));
            if (!text.isEmpty() && !tags.contains(text)) tags.add(text);
        }
    }
    private static String clean(String text) {
        if (text == null) return "";
        String value = text.replaceAll("<[^>]*>", "").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&nbsp;", " ").trim();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
    private static String imageUrl(String value) {
        try {
            if (value.startsWith("//")) value = "https:" + value;
            if (value.startsWith("http://")) value = "https://" + value.substring(7);
            URL url = new URL(value);
            String host = url.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null ||
                    (url.getPort() != -1 && url.getPort() != 443)) return "";
            if (!(host.equals("nga.cn") || host.endsWith(".nga.cn") ||
                    host.equals("178.com") || host.endsWith(".178.com") ||
                    host.equals("ngacn.cc") || host.endsWith(".ngacn.cc"))) return "";
            return url.toString();
        } catch (Exception ignored) { return ""; }
    }
}
