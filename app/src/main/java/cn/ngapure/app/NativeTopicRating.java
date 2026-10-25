package cn.ngapure.app;

import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Official type-2 topic scores travel with a normal post.php reply as topic_vote.
 * js_postfunc_v2 genFormFromApiData/o_topicVote preserves all score dimensions on modification. */
public final class NativeTopicRating {
    private NativeTopicRating() { }
    public static final class Dimension {
        public final String id, title;
        private Dimension(String id, String title) { this.id = id; this.title = title; }
    }
    public static final class Spec {
        public final BigDecimal min, max;
        public final long end;
        public final List<Dimension> dimensions;
        public final Map<String, String> existing;
        private Spec(Map<String, String> topic, Map<String, String> previous) {
            min = decimal(topic.get("min"), "评分下限"); max = decimal(topic.get("max"), "评分上限");
            if (min.compareTo(max) > 0 || min.abs().compareTo(new BigDecimal("1000000")) > 0 || max.abs().compareTo(new BigDecimal("1000000")) > 0)
                throw new IllegalArgumentException("服务器评分范围无效");
            String time = topic.get("end");
            try { end = time == null || time.isEmpty() ? 0 : Long.parseLong(time); if (end < 0 || end > 32503680000L) throw new NumberFormatException(); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("评分截止时间无效"); }
            List<String> ids = new ArrayList<>(); for (String key : topic.keySet()) if (key.matches("[1-9][0-9]{0,8}")) ids.add(key);
            Collections.sort(ids, (a, b) -> Long.compare(Long.parseLong(a), Long.parseLong(b)));
            if (ids.isEmpty() || ids.size() > 100) throw new IllegalArgumentException("服务器评分项目无效");
            List<Dimension> items = new ArrayList<>();
            for (String id : ids) { String label = text(topic.get(id)); if (label.isEmpty() || label.length() > 1000) throw new IllegalArgumentException("评分项目名称无效"); items.add(new Dimension(id, label)); }
            dimensions = Collections.unmodifiableList(items);
            Map<String, String> scores = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : previous.entrySet()) if (entry.getKey().matches("[1-9][0-9]{0,8}")) scores.put(entry.getKey(), entry.getValue());
            existing = scores.isEmpty() ? Collections.emptyMap() : validate(scores);
        }
        /** Empty means replying without a rating. Partial scores are rejected, never silently dropped. */
        public Map<String, String> validate(Map<String, String> scores) {
            if (scores == null || scores.isEmpty()) return Collections.emptyMap();
            if (scores.size() != dimensions.size()) throw new IllegalArgumentException("请填写所有评分项目，或关闭参与评分");
            Map<String, String> canonical = new LinkedHashMap<>();
            for (Dimension dimension : dimensions) {
                BigDecimal score = decimal(scores.get(dimension.id), dimension.title);
                if (score.compareTo(min) < 0 || score.compareTo(max) > 0) throw new IllegalArgumentException(dimension.title + "应在 " + min.toPlainString() + " 至 " + max.toPlainString() + " 分之间");
                canonical.put(dimension.id, score.stripTrailingZeros().toPlainString());
            }
            return Collections.unmodifiableMap(canonical);
        }
        public String encode(Map<String, String> scores) {
            Map<String, String> values = validate(scores); StringBuilder out = new StringBuilder();
            for (Map.Entry<String, String> entry : values.entrySet()) { if (out.length() > 0) out.append('~'); out.append(entry.getKey()).append('~').append(entry.getValue()); }
            return out.toString();
        }
        public String summary(Map<String, String> scores) {
            Map<String, String> values = validate(scores); if (values.isEmpty()) return "不提交主题评分";
            StringBuilder out = new StringBuilder("随这条回复一并提交评分：");
            for (Dimension item : dimensions) out.append('\n').append(item.title).append("：").append(values.get(item.id)).append(" 分");
            return out.toString();
        }
        public boolean ended() { return end > 0 && System.currentTimeMillis() / 1000 > end; }
    }
    static Spec fromPrepare(JSONObject data, String action, String pid) {
        boolean reply = "reply".equals(action) || "quote".equals(action) || "modify".equals(action) && pid != null && !pid.isEmpty() && !"0".equals(pid);
        if (!reply) return null;
        JSONObject topic = data.optJSONObject("__T"), post = data.optJSONObject("__P");
        Map<String, String> definitions = decode(topic == null ? null : at(topic.opt("post_misc_var"), "vote"));
        if (!"2".equals(definitions.get("type"))) return null;
        Map<String, String> previous = decode(post == null ? null : at(post.opt("post_misc_var"), "vote"));
        if (!previous.isEmpty() && !"3".equals(previous.get("type"))) throw new IllegalArgumentException("该回复包含无法安全保留的投票设置");
        return new Spec(definitions, previous);
    }
    static Map<String, String> decode(Object raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw == JSONObject.NULL || "".equals(raw) || "0".equals(String.valueOf(raw))) return result;
        if (raw instanceof JSONObject) {
            JSONObject object = (JSONObject) raw; if (object.length() > 1000) throw new IllegalArgumentException("评分字段过多");
            Iterator<String> keys = object.keys(); while (keys.hasNext()) {
                String key = keys.next(); Object value = object.opt(key);
                if (!(value instanceof Number || value instanceof String)) {
                    // Live type-2 post.php metadata includes count:OBJECT and other read-only stats.
                    // Only score dimensions/range/type are submitted, never these opaque statistics.
                    if (key.matches("[1-9][0-9]{0,8}") || key.equals("type") || key.equals("min") || key.equals("max") || key.equals("end"))
                        throw new IllegalArgumentException("评分字段无效");
                    continue;
                }
                result.put(key, String.valueOf(value));
            }
        } else if (raw instanceof String && ((String) raw).length() <= 200000) {
            String[] parts = ((String) raw).split("~", -1);
            if (parts.length % 2 != 0 || parts.length > 2000) throw new IllegalArgumentException("评分字段无效");
            for (int i = 0; i < parts.length; i += 2) if (result.put(parts[i], parts[i + 1]) != null) throw new IllegalArgumentException("评分字段重复");
        } else throw new IllegalArgumentException("评分字段无效");
        return result;
    }
    private static Object at(Object raw, String key) { return raw instanceof JSONObject ? ((JSONObject) raw).opt(key) : null; }
    private static BigDecimal decimal(String raw, String label) {
        if (raw == null || !raw.trim().matches("-?[0-9]{1,12}(?:\\.[0-9]{1,6})?")) throw new IllegalArgumentException(label + "请输入有效分数，最多 6 位小数");
        return new BigDecimal(raw.trim());
    }
    private static String text(String raw) { return raw == null ? "" : raw.replaceAll("(?is)<[^>]*>", "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").trim(); }
}
