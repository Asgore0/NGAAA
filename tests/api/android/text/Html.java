package android.text;

/** Test-only text replacement. The Android app uses the platform's Html class. */
public final class Html {
    public static final int FROM_HTML_MODE_LEGACY = 63;
    public static int calls, emptyCalls;
    public static void resetCallCounts() { calls = 0; emptyCalls = 0; }
    public static CharSequence fromHtml(String source, int flags) {
        calls++; if (source.isEmpty()) emptyCalls++;
        return source.replaceAll("<[^>]*>", "").replace("&quot;", "\"")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
    }
}
