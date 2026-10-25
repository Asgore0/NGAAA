package cn.ngapure.app;

import java.util.Arrays;

/** Width-change reading geometry and saved-state bounds, without Android or I/O. */
public final class ReaderAnchorTest {
    private static int checks;
    private static void ok(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static ReaderAnchor anchor() { return ReaderAnchor.create("91001", 2, 24, 4000, 900, 3456); }
    private static void rejectedField(int field, String value, String label) {
        String[] saved = anchor().encode(); saved[field] = value; ok(ReaderAnchor.decode(saved) == null, label);
    }
    public static void main(String[] args) {
        ReaderAnchor source = anchor(); ok(source != null, "valid reading anchor");
        ReaderAnchor restored = ReaderAnchor.decode(source.encode());
        ok(restored != null && restored.tid.equals(source.tid) && restored.page == 2 && restored.floor == 24
                && restored.fraction == 4000 && restored.columnWidth == 900 && restored.scrollY == 3456,
                "reconstruction retains exact bounded route and geometry");
        ok(restored.matches("91001", 2), "same thread and page accept anchor");
        ok(!restored.matches("91002", 2), "different thread refuses anchor");
        ok(!restored.matches("91001", 1), "different page refuses anchor");
        ok(!restored.matches(null, 2) && !restored.matches("", 2), "absent thread refuses anchor");
        ok(restored.targetY(1000, 500) == 1200, "fraction locates content within its new card geometry");
        ok(restored.targetY(1700, 250) == 1800, "narrow-to-wide reflow uses new card top and height");
        ok(restored.targetY(350, 1000) == 750, "wide-to-narrow reflow uses new card top and height");
        ok(restored.targetY(1000, 501) == 1200, "fraction geometry rounds down by at most one pixel");
        ok(restored.targetY(1000, 0) == 1000 && restored.targetY(1000, -5) == 1000, "empty card cannot generate negative geometry");
        ok(restored.targetY(-20, 100) == 40, "negative top is clamped before adding card fraction");
        ok(restored.targetY(Integer.MAX_VALUE, Integer.MAX_VALUE) == NavigationState.MAX_SCROLL,
                "top and height arithmetic never wraps into negative scroll");
        ok(restored.targetY(9_999_990, 100) == NavigationState.MAX_SCROLL, "large resulting offset is bounded");
        ReaderAnchor start = ReaderAnchor.create("1", 1, 0, 0, 1, 0);
        ok(start != null && start.targetY(333, 789) == 333, "first floor and beginning of card remain valid");
        ReaderAnchor end = ReaderAnchor.create("999999999999999999", 99999, 2_000_000, 10000, 100000, NavigationState.MAX_SCROLL);
        ok(end != null && end.targetY(333, 789) == 1122, "all inclusive upper limits remain bounded and usable");
        ok(end.targetY(Integer.MAX_VALUE, Integer.MAX_VALUE) == NavigationState.MAX_SCROLL, "full fraction uses long arithmetic before offset clamp");
        String[] exported = source.encode();
        ok(exported.length == 6 && Arrays.equals(exported, new String[]{"91001", "2", "24", "4000", "900", "3456"}),
                "fixed schema stores route and geometry only");
        exported[0] = "999"; exported[3] = "0";
        ok(source.matches("91001", 2) && source.fraction == 4000, "encoded array cannot mutate captured anchor");
        ok(source.encode() != source.encode(), "separate saved-state arrays cannot alias each other");
        ok(ReaderAnchor.decode(null) == null && ReaderAnchor.decode(new Object()) == null
                && ReaderAnchor.decode(new int[]{1, 2, 3, 4, 5, 6}) == null, "wrong saved-state types rejected");
        ok(ReaderAnchor.decode(new String[0]) == null && ReaderAnchor.decode(new String[5]) == null
                && ReaderAnchor.decode(new String[7]) == null, "wrong schema length rejected");
        for (int field = 0; field < 6; field++) rejectedField(field, null, "null field rejected");
        for (String id : new String[]{"", "0", "-1", "01", "1&pid=2", "1\n", "9999999999999999999", "synthetic-token"})
            rejectedField(0, id, "invalid thread identity rejected");
        for (String page : new String[]{"0", "-1", "100000", "2147483648", "1.5", "", "1\n"})
            rejectedField(1, page, "invalid page rejected");
        for (String floor : new String[]{"-1", "2000001", "2147483648", "not-a-floor"})
            rejectedField(2, floor, "invalid floor rejected");
        for (String fraction : new String[]{"-1", "10001", "2147483648", "0.4"})
            rejectedField(3, fraction, "invalid relative position rejected");
        for (String width : new String[]{"0", "-1", "100001", "2147483648", "NaN"})
            rejectedField(4, width, "invalid saved width rejected");
        for (String offset : new String[]{"-1", "10000001", "2147483648", "-2147483649", "NaN"})
            rejectedField(5, offset, "invalid absolute offset rejected");
        for (int fraction = 0; fraction <= 10000; fraction += 1250) {
            ReaderAnchor value = ReaderAnchor.create("91001", 2, 24, fraction, 900, 3456);
            int previous = -1;
            for (int height : new int[]{0, 1, 17, 300, 900, 3000}) {
                int offset = value.targetY(120, height);
                ok(offset >= 120 && offset <= 120 + height && offset >= previous,
                        "relative geometry stays in card and grows monotonically during reflow");
                previous = offset;
            }
        }
        System.out.println("ReaderAnchor: " + checks + " saved-state, wrong-route and reflow geometry assertions PASS; no Android, network or device");
    }
}
