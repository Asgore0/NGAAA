package cn.ngapure.app;

/** Validated page bounds shared by the native page input and its quick choices. */
public final class PageJumpState {
    // Keep the navigation limit even when the server does not report a total.
    private static final int PAGE_LIMIT = 99999;

    public final int currentPage;
    /** Zero means the total is unknown; it must not be presented as PAGE_LIMIT. */
    public final int totalPages;
    public final int maximumPage;

    public PageJumpState(int currentPage, int totalPages) {
        this.currentPage = Math.max(1, Math.min(PAGE_LIMIT, currentPage));
        int boundedTotal = Math.min(PAGE_LIMIT, totalPages);
        this.totalPages = boundedTotal >= this.currentPage ? boundedTotal : 0;
        this.maximumPage = this.totalPages == 0 ? PAGE_LIMIT : this.totalPages;
    }

    /** ASCII decimal input only, with surrounding trim whitespace allowed. */
    public int parse(String text) {
        if (text == null) return 0;
        int start = 0, end = text.length();
        while (start < end && text.charAt(start) <= ' ') start++;
        while (end > start && text.charAt(end - 1) <= ' ') end--;
        if (start == end) return 0;
        int page = 0;
        for (int i = start; i < end; i++) {
            char digit = text.charAt(i);
            if (digit < '0' || digit > '9') return 0;
            int value = digit - '0';
            if (page > (maximumPage - value) / 10) return 0;
            page = page * 10 + value;
            if (page > maximumPage) return 0;
        }
        return page >= 1 ? page : 0;
    }

    /** At most five ascending choices, centered where the page bounds allow. */
    public int[] nearbyPages() {
        int count = Math.min(5, maximumPage);
        int first = Math.max(1, Math.min(currentPage - 2, maximumPage - count + 1));
        int[] choices = new int[count];
        for (int i = 0; i < count; i++) choices[i] = first + i;
        return choices;
    }
}
