package cn.ngapure.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** NGA recommendation metadata: a net recommendation value is never a positive-vote count. */
public final class VoteMetrics {
    public boolean likesKnown, dislikesKnown, scoreKnown;
    public int likes, dislikes, score;

    /** Counts are distinct server-provided totals. Unknown totals stay unknown in every UI. */
    public String likesLabel() { return "点赞 " + (likesKnown ? String.valueOf(likes) : "—"); }
    public String dislikesLabel() { return "点踩 " + (dislikesKnown ? String.valueOf(dislikes) : "—"); }
    public String summary() { return likesLabel() + " · " + dislikesLabel(); }
    public boolean countsKnown() { return likesKnown && dislikesKnown; }
    public VoteMetrics copy() {
        VoteMetrics value = new VoteMetrics();
        value.likesKnown = likesKnown; value.dislikesKnown = dislikesKnown; value.scoreKnown = scoreKnown;
        value.likes = likes; value.dislikes = dislikes; value.score = score;
        return value;
    }

    /** A supplemental read may add exact totals, but a net-only row cannot erase them. */
    public VoteMetrics supplementedBy(VoteMetrics incoming) {
        VoteMetrics result = copy();
        if (incoming == null) return result;
        if (incoming.likesKnown) { result.likesKnown = true; result.likes = incoming.likes; }
        if (incoming.dislikesKnown) { result.dislikesKnown = true; result.dislikes = incoming.dislikes; }
        if (incoming.scoreKnown) { result.scoreKnown = true; result.score = incoming.score; }
        return result;
    }

    /** UI-thread state: confirmed flags and a derived session scope, never credentials.
     * A revision invalidates reads started before an acknowledgement, even with zero flags.
     * Counts are not retained here: a write makes that floor's old totals stale. */
    public static final class ConfirmedSelections {
        private static final int LIMIT = 128;
        private final LinkedHashMap<String, Integer> values = new LinkedHashMap<>();
        private String scope = "";
        private long revision;
        public void useScope(String value) {
            String next = value == null ? "" : value;
            if (!scope.equals(next)) { scope = next; values.clear(); revision++; }
        }
        public long revision() { return revision; }
        public int selected(String currentScope, String tid, String pid) {
            useScope(currentScope); Integer value = values.get(tid + ":" + pid);
            return value == null ? 0 : value;
        }
        public void confirmed(String currentScope, String tid, String pid, boolean known, int direction) {
            if (!validId(tid, false) || !validId(pid, true) || direction < -1 || direction > 1)
                throw new IllegalArgumentException("Invalid confirmed reaction");
            useScope(currentScope); revision++;
            if (!known) return;
            String key = tid + ":" + pid; values.remove(key); values.put(key, direction);
            while (values.size() > LIMIT) values.remove(values.keySet().iterator().next());
        }
        public boolean accepts(String currentScope, long readRevision) {
            return scope.equals(currentScope) && revision == readRevision;
        }
        public String[] encode(String currentScope) {
            useScope(currentScope); ArrayList<String> result = new ArrayList<>(); result.add(scope);
            for (Map.Entry<String, Integer> entry : values.entrySet()) {
                String[] route = entry.getKey().split(":");
                result.add(route[0]); result.add(route[1]); result.add(String.valueOf(entry.getValue()));
            }
            return result.toArray(new String[0]);
        }
        public void restore(String currentScope, Object encoded) {
            useScope(currentScope); values.clear(); revision++;
            if (!(encoded instanceof String[])) return;
            String[] parts = (String[]) encoded;
            if (parts.length < 1 || parts.length > 1 + LIMIT * 3 || (parts.length - 1) % 3 != 0 || !scope.equals(parts[0])) return;
            LinkedHashMap<String, Integer> restored = new LinkedHashMap<>();
            for (int i = 1; i < parts.length; i += 3) {
                if (!validId(parts[i], false) || !validId(parts[i + 1], true)
                        || !("-1".equals(parts[i + 2]) || "0".equals(parts[i + 2]) || "1".equals(parts[i + 2]))) return;
                if (restored.put(parts[i] + ":" + parts[i + 1], Integer.valueOf(parts[i + 2])) != null) return;
            }
            values.putAll(restored);
        }
        private static boolean validId(String value, boolean zero) {
            return value != null && (zero && "0".equals(value) || value.matches("[1-9][0-9]{0,17}"));
        }
    }

    /** Official js_read postArg accepts recommend as either a net number or "value,support,oppose". */
    public static VoteMetrics from(JSONObject row) { return value(row == null ? null : row.opt("recommend")); }
    /** List scalars may be editorial recommendation flags/weights; only explicit tuples are vote data. */
    public static VoteMetrics topicFrom(JSONObject row) {
        VoteMetrics metrics = from(row);
        return metrics.likesKnown ? metrics : new VoteMetrics();
    }
    private static VoteMetrics value(Object raw) {
        VoteMetrics result = new VoteMetrics();
        if (raw instanceof String && ((String) raw).matches("[0-9]{1,10},[0-9]{1,10},[0-9]{1,10}")) {
            String[] values = ((String) raw).split(",");
            try {
                long up = Long.parseLong(values[1]), down = Long.parseLong(values[2]);
                if (up > Integer.MAX_VALUE || down > Integer.MAX_VALUE) return result;
                result.likes = (int) up; result.dislikes = (int) down;
                result.likesKnown = true; result.dislikesKnown = true;
                result.score = Math.max(0, result.likes - result.dislikes); result.scoreKnown = true;
            } catch (NumberFormatException invalid) { return result; }
        } else if (raw instanceof Number || raw instanceof String) {
            String scalar = String.valueOf(raw);
            if (!scalar.matches("-?[0-9]{1,10}")) return result;
            try { result.score = Integer.parseInt(scalar); result.scoreKnown = true; }
            catch (NumberFormatException invalid) { return result; }
        }
        return result;
    }

    /** Reads scalar positions from server-rendered postArg calls. It never evaluates JavaScript. */
    static Map<String, VoteMetrics> htmlPosts(String html) {
        Map<String, VoteMetrics> result = new HashMap<>();
        Matcher scripts = Pattern.compile("<script\\b[^>]*>(.*?)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        int count = 0;
        while (scripts.find() && count < 200) {
            String script = scripts.group(1);
            int from = 0;
            while (count++ < 200) {
                int marker = script.indexOf("postArg.proc(", from);
                if (marker < 0) break;
                int start = marker + "postArg.proc(".length();
                List<String> args = arguments(script, start);
                from = start;
                if (args.size() < 16) continue;
                String floor = scalar(args.get(0)), pid = scalar(args.get(10)), recommend = scalar(args.get(15));
                if (!floor.matches("[0-9]{1,8}") || !pid.matches("[0-9]{1,18}")) continue;
                VoteMetrics metrics = value(recommend);
                if (metrics.scoreKnown) result.put(floor + ":" + pid, metrics);
            }
        }
        return result;
    }
    private static List<String> arguments(String html, int start) {
        List<String> result = new ArrayList<>();
        int nesting = 0, part = start; char quote = 0; boolean escaped = false;
        for (int i = start; i < html.length() && i - start < 8192; i++) {
            char c = html.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '(' || c == '[' || c == '{') nesting++;
            else if (c == ')' && nesting == 0) { result.add(html.substring(part, i).trim()); return result; }
            else if (c == ')' || c == ']' || c == '}') nesting--;
            else if (c == ',' && nesting == 0) {
                result.add(html.substring(part, i).trim()); part = i + 1;
                if (result.size() > 64) return new ArrayList<>();
            }
            if (nesting < 0) break;
        }
        return new ArrayList<>();
    }
    private static String scalar(String argument) {
        if (argument.length() >= 2 && ((argument.charAt(0) == '\'' && argument.endsWith("'"))
                || (argument.charAt(0) == '"' && argument.endsWith("\"")))) return argument.substring(1, argument.length() - 1);
        return argument;
    }
}
