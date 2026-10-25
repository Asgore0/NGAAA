package cn.ngapure.app;

import java.util.Arrays;

/** Pure page input and boundary contracts; no Android, account, device or I/O. */
public final class PageJumpStateTest {
    private static int checks;

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static void choices(int current, int total, int... expected) {
        PageJumpState state = new PageJumpState(current, total);
        ok(Arrays.equals(state.nearbyPages(), expected), "nearby pages for " + current + "/" + total);
    }

    private static void checkBounds(int current, int total) {
        PageJumpState state = new PageJumpState(current, total);
        int[] pages = state.nearbyPages();
        ok(pages.length >= 1 && pages.length <= 5, "bounded number of quick choices");
        boolean containsCurrent = false;
        for (int i = 0; i < pages.length; i++) {
            ok(pages[i] >= 1 && pages[i] <= state.maximumPage, "quick choices respect page bounds");
            ok(i == 0 || pages[i] == pages[i - 1] + 1, "quick choices are ordered and adjacent");
            containsCurrent |= pages[i] == state.currentPage;
            ok(state.parse(Integer.toString(pages[i])) == pages[i], "displayed quick choice can be submitted");
        }
        ok(containsCurrent, "quick choices include current page");
        if (state.currentPage >= 3 && state.currentPage <= state.maximumPage - 2) {
            ok(pages[2] == state.currentPage, "quick choices center current page when both sides fit");
        }
    }

    public static void main(String[] args) {
        PageJumpState known = new PageJumpState(12, 30);
        ok(known.currentPage == 12 && known.totalPages == 30 && known.maximumPage == 30, "known total preserved");
        for (String input : new String[]{"1", "12", "30", "00012", " 12 ", "\t\r\n12\n"}) {
            int expected = input.trim().equals("1") ? 1 : input.trim().equals("30") ? 30 : 12;
            ok(known.parse(input) == expected, "valid decimal with allowed surrounding whitespace");
        }
        for (String input : new String[]{null, "", " ", "\n\t", "0", "000", "-1", "+1", "31", "99999", "2147483647", "2147483648", "999999999999999999999999999999", "a", "1a", "1.0", "1 2", "1\n2", "1e1", "0x10", "１２", "١٢", "\u00a012", "12\u00a0", "\u200312", "1/30"}) {
            ok(known.parse(input) == 0, "invalid input rejected without coercion or overflow");
        }
        StringBuilder leadingZeros = new StringBuilder();
        for (int i = 0; i < 10000; i++) leadingZeros.append('0');
        leadingZeros.append("12");
        ok(known.parse(leadingZeros.toString()) == 12, "large leading-zero input remains valid");
        StringBuilder oversized = new StringBuilder();
        for (int i = 0; i < 10000; i++) oversized.append('9');
        ok(known.parse(oversized.toString()) == 0, "very large value rejected without overflow");
        PageJumpState unknown = new PageJumpState(12, 0);
        ok(unknown.totalPages == 0 && unknown.maximumPage == 99999, "unknown total remains distinct from navigation limit");
        ok(unknown.parse("99999") == 99999 && unknown.parse("100000") == 0, "unknown total still bounded");
        ok(new PageJumpState(12, 11).totalPages == 0, "stale total below current becomes unknown");
        ok(new PageJumpState(12, -1).totalPages == 0, "negative total becomes unknown");
        PageJumpState low = new PageJumpState(Integer.MIN_VALUE, 0);
        ok(low.currentPage == 1 && low.totalPages == 0, "current page clamps at first page");
        PageJumpState high = new PageJumpState(Integer.MAX_VALUE, Integer.MAX_VALUE);
        ok(high.currentPage == 99999 && high.totalPages == 99999 && high.maximumPage == 99999, "known bounds share navigation ceiling");
        PageJumpState missingHigh = new PageJumpState(Integer.MAX_VALUE, 99998);
        ok(missingHigh.currentPage == 99999 && missingHigh.totalPages == 0, "capped current still recognizes stale total");
        ok(new PageJumpState(1, 1).parse("1") == 1 && new PageJumpState(1, 1).parse("2") == 0, "single-page input bounds");

        choices(1, 1, 1);
        choices(1, 2, 1, 2);
        choices(3, 3, 1, 2, 3);
        choices(3, 4, 1, 2, 3, 4);
        choices(1, 30, 1, 2, 3, 4, 5);
        choices(2, 30, 1, 2, 3, 4, 5);
        choices(12, 30, 10, 11, 12, 13, 14);
        choices(29, 30, 26, 27, 28, 29, 30);
        choices(30, 30, 26, 27, 28, 29, 30);
        choices(1, 0, 1, 2, 3, 4, 5);
        choices(12, 0, 10, 11, 12, 13, 14);
        choices(99999, 0, 99995, 99996, 99997, 99998, 99999);
        choices(99999, 100000, 99995, 99996, 99997, 99998, 99999);
        int[] first = known.nearbyPages();
        first[0] = -1;
        ok(known.nearbyPages()[0] == 10, "choices have no shared mutable storage");
        for (int total = 1; total <= 12; total++) {
            for (int current = 1; current <= total; current++) checkBounds(current, total);
        }
        for (int total : new int[]{0, -1, 30, 99999, Integer.MAX_VALUE}) {
            for (int current : new int[]{Integer.MIN_VALUE, 0, 1, 2, 3, 12, 29, 30, 99997, 99998, 99999, Integer.MAX_VALUE}) {
                checkBounds(current, total);
            }
        }
        System.out.println("PageJumpState: " + checks + " input, bounds and quick-choice assertions PASS; no Android, network or device");
    }
}
