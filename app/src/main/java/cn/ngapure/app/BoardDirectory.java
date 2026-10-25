package cn.ngapure.app;

import java.text.Normalizer;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.RandomAccess;
import java.util.Set;

/** Pure board discovery and stable-key grid geometry. No account, view or network state. */
public final class BoardDirectory {
    private static final String ALL = "全部版块", OTHER = "其他";
    private BoardDirectory() { }

    /** Immutable row snapshot; array values obtained from entries are defensive copies. */
    public static final class Row {
        public final String category;
        public final List<String[]> entries;
        private Row(String category, List<String[]> entries) {
            this.category = category;
            this.entries = snapshot(entries);
        }
        public boolean isHeader() { return entries.isEmpty(); }
    }

    /**
     * Searches canonical IDs, names, aliases and categories with token AND semantics.
     * A nonempty query sorts by match strength, then favorites, then first source order.
     * An empty query keeps first source order, regardless of favorite state.
     */
    public static List<String[]> filter(List<String[]> source, String query, String category,
                                        Set<String> favorites) {
        String wanted = normalize(query), group = normalize(category);
        boolean all = group.isEmpty() || group.equals(normalize(ALL));
        List<String[]> valid = canonicalRows(source);
        List<Scored> matches = new ArrayList<>();
        String[] tokens = wanted.isEmpty() ? new String[0] : wanted.split(" ");
        for (int index = 0; index < valid.size(); index++) {
            String[] entry = valid.get(index);
            if (!all && !categoryMatches(entry, group)) continue;
            int rank = wanted.isEmpty() ? 0 : rank(entry, wanted, tokens);
            if (rank < 0) continue;
            matches.add(new Scored(entry, rank, favorites != null && favorites.contains(entry[0]), index));
        }
        if (!wanted.isEmpty()) Collections.sort(matches, Comparator.comparingInt((Scored value) -> value.rank)
                .thenComparingInt(value -> value.favorite ? 0 : 1).thenComparingInt(value -> value.index));
        List<String[]> result = new ArrayList<>(matches.size());
        for (Scored match : matches) result.add(match.entry);
        return snapshot(result);
    }

    /** availableWidthPx is the measured content width, after outer gutters are removed. */
    public static int columns(int availableWidthPx, float density, float fontScale, boolean grid) {
        if (!grid || availableWidthPx <= 0) return 1;
        double safeDensity = Float.isFinite(density) && density > 0 ? density : 1;
        double safeFontScale = Float.isFinite(fontScale) && fontScale > 0 ? fontScale : 1;
        double widthDp = availableWidthPx / safeDensity;
        if (widthDp >= 660 * safeFontScale) return 3;
        return widthDp >= 340 * safeFontScale ? 2 : 1;
    }

    /** Groups by first-seen primary category; a header never consumes a tile's identity. */
    public static List<Row> pack(List<String[]> entries, int columns, boolean grouped) {
        List<String[]> valid = canonicalRows(entries);
        if (valid.isEmpty()) return Collections.emptyList();
        int count = Math.max(1, columns);
        List<Row> result = new ArrayList<>();
        if (!grouped) appendRows(result, "", valid, count);
        else {
            Map<String, List<String[]>> groups = new LinkedHashMap<>();
            Map<String, String> labels = new LinkedHashMap<>();
            for (String[] entry : valid) {
                String key = normalize(entry[2]);
                List<String[]> group = groups.get(key);
                if (group == null) { group = new ArrayList<>(); groups.put(key, group); labels.put(key, entry[2]); }
                group.add(entry);
            }
            for (Map.Entry<String, List<String[]>> group : groups.entrySet()) {
                String label = labels.get(group.getKey());
                result.add(new Row(label, Collections.emptyList()));
                appendRows(result, label, group.getValue(), count);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** Returns the adapter tile-row index, including preceding headers, never a tile column. */
    public static int rowForKey(List<Row> rows, String key) {
        if (rows == null || !ForumRoute.isKey(key)) return -1;
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (row == null || row.isHeader()) continue;
            for (String[] entry : row.entries) if (key.equals(entry[0])) return index;
        }
        return -1;
    }

    private static void appendRows(List<Row> target, String category, List<String[]> entries, int columns) {
        for (int start = 0; start < entries.size();) {
            int end = (int) Math.min(entries.size(), (long) start + columns);
            target.add(new Row(category, entries.subList(start, end))); start = end;
        }
    }

    private static final class Scored {
        final String[] entry;
        final int rank, index;
        final boolean favorite;
        Scored(String[] entry, int rank, boolean favorite, int index) {
            this.entry = entry; this.rank = rank; this.favorite = favorite; this.index = index;
        }
    }

    /** Six strength tiers; all tokens must be present before any ranking is considered. */
    private static int rank(String[] entry, String query, String[] tokens) {
        String id = normalize(entry[0]), number = id.startsWith("stid:") ? id.substring(5) : id;
        String name = normalize(entry[1]), aliases = normalize(entry[3] + " " + entry[5]);
        String categories = normalize(entry[2] + " " + entry[6]);
        String core = id + " " + number + " " + name + " " + aliases;
        String all = core + " " + categories;
        for (String token : tokens) if (!all.contains(token)) return -1;
        if (id.equals(query) || number.equals(query)) return 0;
        if (name.equals(query)) return 1;
        if (name.startsWith(query)) return 2;
        boolean inName = true, inCore = true;
        for (String token : tokens) { inName &= name.contains(token); inCore &= core.contains(token); }
        if (inName) return 3;
        return inCore ? 4 : 5;
    }

    private static boolean categoryMatches(String[] entry, String category) {
        if (normalize(entry[2]).equals(category)) return true;
        for (String value : entry[6].split("\n")) if (normalize(value).equals(category)) return true;
        return false;
    }

    /** First identity wins display order; aliases/category memberships from duplicates survive. */
    private static List<String[]> canonicalRows(List<String[]> source) {
        Map<String, String[]> unique = new LinkedHashMap<>();
        if (source != null) for (String[] raw : source) {
            if (raw == null || raw.length < 4 || !ForumRoute.isKey(raw[0])) continue;
            String name = whitespace(raw[1]);
            if (name.isEmpty()) continue;
            String category = whitespace(raw[2]); if (category.isEmpty()) category = OTHER;
            String parent = raw.length > 4 && ForumRoute.isForumId(raw[4]) ? raw[4] : "";
            String aliases = raw.length > 5 ? raw[5] : "";
            String categories = raw.length > 6 ? raw[6] : "";
            String[] row = {raw[0], name, category, whitespace(raw[3]), parent,
                    lines(aliases), lines(category, categories)};
            String[] previous = unique.get(row[0]);
            if (previous == null) unique.put(row[0], row);
            else {
                if (previous[4].isEmpty()) previous[4] = row[4];
                previous[5] = lines(previous[5], row[5], row[3],
                        normalize(previous[1]).equals(normalize(row[1])) ? "" : row[1]);
                if (previous[3].isEmpty()) previous[3] = row[3];
                previous[6] = lines(previous[6], row[6]);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static String lines(String... values) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String value : values) if (value != null) for (String line : value.split("\n")) {
            String display = whitespace(line); if (!display.isEmpty()) unique.putIfAbsent(normalize(display), display);
        }
        return String.join("\n", unique.values());
    }

    private static String normalize(String value) {
        return value == null ? "" : whitespace(Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
    }
    private static String whitespace(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(); boolean pending = false;
        for (int index = 0; index < value.length();) {
            int code = value.codePointAt(index); index += Character.charCount(code);
            if (Character.isWhitespace(code) || Character.isSpaceChar(code)) { pending = result.length() > 0; continue; }
            if (pending) result.append(' '); pending = false; result.appendCodePoint(code);
        }
        return result.toString();
    }

    private static List<String[]> snapshot(List<String[]> entries) {
        if (entries.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new EntrySnapshot(entries));
    }
    private static final class EntrySnapshot extends AbstractList<String[]> implements RandomAccess {
        private final String[][] entries;
        EntrySnapshot(List<String[]> source) {
            entries = new String[source.size()][];
            for (int index = 0; index < source.size(); index++) entries[index] = source.get(index).clone();
        }
        @Override public String[] get(int index) { return entries[index].clone(); }
        @Override public int size() { return entries.length; }
    }
}
