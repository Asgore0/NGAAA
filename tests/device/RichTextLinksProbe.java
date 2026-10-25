package cn.ngapure.verify;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;
import cn.ngapure.app.BrowserLinks;
import cn.ngapure.app.NativeImages;
import cn.ngapure.app.NativeLinkTarget;
import cn.ngapure.app.NativeLoginView;
import cn.ngapure.app.NativeRichText;
import cn.ngapure.app.NgaStore;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local production-renderer compatibility and browser handoff checks. No fixture
 * image or author lookup is created. Only the public event link from the reported
 * screenshot is clicked; an Instrumentation monitor blocks its browser launch.
 * Account/session values stay in memory for equality checks and are never reported.
 */
public final class RichTextLinksProbe extends Instrumentation {
    private static final String EVENT = "https://bbs.nga.cn/misc/event/20261015zmd/index.html";
    private static final String COMPLEX_EVENT = EVENT + "?from=android&label=%E6%B5%8B%E8%AF%95&x=1%262#rules";
    private static final String CODE = "    kept indent\n\t[列表] [b]原文[/b]\n"
            + "String path = \"[jumpurl]https://bbs.nga.cn/[/jumpurl]\";\n";
    private static final String SOURCE = "普通区开头 [攻略] [终末地] [customtag]普通文字[/customtag]\n"
            + "[jumpurl]" + EVENT + "[/jumpurl]\n"
            + "[jumpurl][url]" + EVENT + "[/url][/jumpurl]\n"
            + "[jumpurl=" + EVENT + "]活动入口[/jumpurl]\n"
            + "[url=" + EVENT + "][b]嵌套外链[/b][url=https://www.nga.cn/]内层文字[/url][/url]\n"
            + "[list][*]项目甲[*]项目乙[/list]\n"
            + "[list=a][*]条目甲[*]条目乙[/list]\n"
            + "[table][tr][td33]表格甲[/td][td colspan2 width50]表格乙[/td][/tr]"
            + "[tr][td]表格丙[/td][td]表格丁[/td][/tr][/table]\n"
            + "[font=monospace]等宽体[/font] 平方[sup]上标内容[/sup] 水[sub]下标内容[/sub]\n"
            + "[quote]引用正文[quote]内层引用正文[/quote]引用尾部[/quote]\n"
            + "[collapse=测试折叠]展开后正文 [b]折叠粗体[/b][/collapse]\n"
            + "[tid=47717100]公开主题[/tid] [fid=650]公开版块[/fid]\n"
            + "[media=audio]" + EVENT + "[/media]\n"
            + "[iframe=500,640]" + EVENT + "[/iframe]\n"
            + "[img-1][url]" + EVENT + "[/url][/img]\n"
            + "[code=java]" + CODE + "[/code]普通区结尾";

    private final JSONObject rich = new JSONObject(), links = new JSONObject();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger callbacks = new AtomicInteger();
    private final AtomicReference<String> callbackUrl = new AtomicReference<>();
    private Activity activity;
    private Snapshot snapshot;
    private NativeImages loader;
    private NativeRichText content;
    private long deadline;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        deadline = SystemClock.elapsedRealtime() + 120000;
        Settings before = null;
        boolean complete = false;
        String failure = "NONE";
        try {
            NgaStore store = new NgaStore(getTargetContext());
            common(!store.autoCheckIn() || (store.loggedIn() && NgaStore.today().equals(store.lastCheckInDay())),
                    "onOpenSignInGuardPassed");
            common(!NativeLoginView.hasPendingAuthorization(getTargetContext(), store.host()),
                    "pendingAuthorizationGuardPassed");
            before = new Settings(store);
            activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName("cn.ngapure.app", "cn.ngapure.app.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            settle();
            loader = onMain(NativeImages::new);
            onMain(() -> { loader.close(); return null; });
            snapshot = onMain(this::replaceRoot);
            renderChecks();
            routeChecks();
            browserHandoff();
            common(before.same(store), "accountAndSettingsUnchanged");
            complete = true;
        } catch (CheckFailure failed) {
            failure = failed.code;
        } catch (Exception | AssertionError unavailable) {
            failure = "RICH_TEXT_LINKS_PROBE_UNAVAILABLE";
        } finally {
            try { dismissConfirmation(); }
            catch (Exception unavailable) { complete = false; failure = "DIALOG_RESTORE_FAILED"; }
            boolean restored = false;
            try {
                if (snapshot != null) {
                    onMainCleanup(() -> {
                        snapshot.host.removeAllViews(); snapshot.host.addView(snapshot.original, snapshot.params);
                        return null;
                    });
                    restored = true;
                }
            } catch (Exception unavailable) { complete = false; failure = "ROOT_RESTORE_FAILED"; }
            putBoth("originalRootRestored", restored);
            if (!restored) complete = false;
            if (loader != null) loader.close();
            if (before != null) {
                boolean same = before.same(new NgaStore(getTargetContext()));
                putBoth("accountAndSettingsUnchangedAfterRestore", same);
                if (!same) { complete = false; failure = "ACCOUNT_OR_SETTINGS_CHANGED"; }
                before.clear();
            }
            putBoth("syntheticRemoteRequests", 0);
            putBoth("syntheticImageOrAuthorRequests", 0);
            putBoth("realCredentialsSubmitted", false);
            putBoth("realSmsSent", false);
            putBoth("passed", complete);
            putBoth("failure", complete ? "NONE" : failure);
        }
        Bundle output = new Bundle();
        output.putString("safe_richtext_report", rich.toString());
        output.putString("safe_links_report", links.toString());
        finish(Activity.RESULT_OK, output);
    }

    private Snapshot replaceRoot() throws Exception {
        Snapshot saved = new Snapshot();
        saved.host = (ViewGroup) activity.findViewById(android.R.id.content);
        saved.original = saved.host.getChildAt(0); saved.params = saved.original.getLayoutParams();
        call(activity, "cancelHomeNavigation");
        call(field(activity, "pageReads"), "cancelAll");
        write(activity, "generation", (Integer) field(activity, "generation") + 1);
        ScrollView viewport = new ScrollView(activity); viewport.setFillViewport(false);
        content = new NativeRichText(activity, loader, SOURCE, false, 16, url -> {
            callbacks.incrementAndGet(); callbackUrl.set(url);
            try {
                Method handle = activity.getClass().getDeclaredMethod("handleLink", String.class);
                handle.setAccessible(true); handle.invoke(activity, url);
            } catch (Exception unavailable) { throw new IllegalStateException("MAIN_LINK_CALLBACK_FAILED"); }
        });
        viewport.addView(content, new ScrollView.LayoutParams(-1, -2));
        saved.host.removeAllViews(); saved.host.addView(viewport, new ViewGroup.LayoutParams(-1, -1));
        return saved;
    }

    private void renderChecks() throws Exception {
        settle();
        TextView code = onMain(() -> find(content, CODE));
        require(rich, code != null && onMain(() -> CODE.contentEquals(code.getText())), "codeLanguageAndWhitespaceRetained");
        require(rich, onMain(() -> code.getParent() instanceof HorizontalScrollView), "codeUsesNativeHorizontalContainer");
        String ordinary = onMain(() -> ordinaryText(content, code));
        require(rich, ordinary.contains("[攻略]") && ordinary.contains("[终末地]")
                && ordinary.contains("[customtag]普通文字[/customtag]"), "unknownBracketsPreserved");
        String lowered = ordinary.toLowerCase(Locale.ROOT);
        boolean clean = true;
        for (String tag : new String[]{"jumpurl", "url", "code", "list", "table", "tr", "td", "font", "sup", "sub",
                "quote", "collapse", "tid", "fid", "media", "iframe", "img"}) {
            if (lowered.contains("[" + tag + "]") || lowered.contains("[" + tag + "=")
                    || lowered.contains("[/" + tag + "]")) clean = false;
        }
        require(rich, clean && !lowered.contains("[*]") && !lowered.contains("[td33]")
                && !lowered.contains("[td colspan") && !lowered.contains("[img-1]"), "noRawKnownTagsOutsideCode");
        require(rich, ordinary.contains(EVENT) && ordinary.contains("活动入口"), "jumpUrlVariantsReadable");
        require(links, onMain(() -> {
            String[] variants = {"[jumpurl]" + EVENT + "[/jumpurl]",
                    "[jumpurl][url]" + EVENT + "[/url][/jumpurl]",
                    "[jumpurl=" + EVENT + "]活动入口[/jumpurl]"};
            for (String source : variants) {
                NativeRichText variant = new NativeRichText(activity, loader, source, false, 16, null);
                List<Link> rendered = spanLinks(variant);
                if (rendered.size() != 1 || !EVENT.equals(rendered.get(0).url)) return false;
            }
            return true;
        }), "allThreeJumpUrlFormsProduceOneExactLink");
        require(rich, ordinary.contains("• 项目甲") && ordinary.contains("• 项目乙")
                && ordinary.contains("a. 条目甲") && ordinary.contains("b. 条目乙"), "unorderedAndOrderedListsReadable");
        int first = ordinary.indexOf("表格甲"), second = ordinary.indexOf("表格乙"), third = ordinary.indexOf("表格丙"), fourth = ordinary.indexOf("表格丁");
        require(rich, first >= 0 && second > first && third > second && fourth > third, "tableCellTextOrderRetained");
        onMain(() -> { nativeTableChecks(); return null; });
        require(rich, onMain(() -> hasSpan(content, "等宽体", TypefaceSpan.class)
                && hasSpan(content, "上标内容", SuperscriptSpan.class)
                && hasSpan(content, "下标内容", SubscriptSpan.class)), "nativeFontAndScriptSpansApplied");
        require(rich, ordinary.contains("引用正文") && ordinary.contains("内层引用正文") && ordinary.contains("引用尾部"),
                "nestedQuoteContentRetained");
        require(rich, onMain(() -> find(content, "展开后正文") == null), "collapseRemainsLazy");
        onMain(() -> { TextView header = find(content, "测试折叠"); if (header == null || !header.performClick()) throw new CheckFailure("COLLAPSE_HEADER_MISSING"); return null; });
        settle();
        require(rich, onMain(() -> find(content, "展开后正文") != null && find(content, "折叠粗体") != null
                && find(content, "展开后正文").isShown()), "collapseExpandsReadableContent");
        onMain(() -> { find(content, "测试折叠").performClick(); return null; });
        settle();
        require(rich, onMain(() -> !find(content, "展开后正文").isShown()), "collapseHidesExpandedContent");
        onMain(() -> { nativeAudioChecks(); return null; });
        require(rich, ordinary.contains("嵌入页面：") && ordinary.contains("二维码内容："),
                "embeddedPageAndQrUseReadableTextFallback");
        require(rich, onMain(() -> ((List<?>) field(content, "inlineImages")).isEmpty()
                && Boolean.TRUE.equals(field(loader, "closed")) && ((Set<?>) field(loader, "connections")).isEmpty()),
                "fixtureHasNoImagesAndClosedTransport");
        List<Link> spans = onMain(() -> spanLinks(content));
        require(links, !spans.isEmpty() && allSingleSpanRanges(spans), "linksHaveSingleClickableRanges");
        Link event = labeled(spans, "活动入口"), nested = labeled(spans, "嵌套外链内层文字");
        require(links, event != null && EVENT.equals(event.url), "jumpUrlProducesExactClickableDestination");
        require(links, nested != null && EVENT.equals(nested.url), "nestedLinksKeepOnlyOuterDestination");
        Link topic = labeled(spans, "公开主题"), forum = labeled(spans, "公开版块");
        require(links, topic != null && NativeLinkTarget.parse(topic.url).kind == NativeLinkTarget.TOPIC
                && forum != null && NativeLinkTarget.parse(forum.url).kind == NativeLinkTarget.FORUM,
                "nativeReferenceSpansRetainRouteMeaning");
        put(rich, "nativeTextViewsChecked", onMain(() -> textViews(content).size()));
        put(links, "clickableSpansChecked", spans.size());
    }

    private void nativeTableChecks() throws CheckFailure {
        TextView[] labels = {find(content, "表格甲"), find(content, "表格乙"), find(content, "表格丙"), find(content, "表格丁")};
        require(rich, labels[0] != null && labels[1] != null && labels[2] != null && labels[3] != null,
                "allNativeTableCellLabelsPresent");
        ViewGroup grid = tableGrid(labels[0]);
        require(rich, grid != null && tableGrid(labels[1]) == grid && tableGrid(labels[2]) == grid
                && tableGrid(labels[3]) == grid && grid.getChildCount() == 4, "tableCellsShareNativeTableGrid");
        require(rich, grid.getParent() instanceof HorizontalScrollView
                && ((HorizontalScrollView) grid.getParent()).getChildAt(0) == grid
                && grid.getWidth() > 0 && grid.getHeight() > 0, "tableUsesNativeHorizontalContainer");
        View[] cells = new View[4];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = childIn(grid, labels[i]);
            require(rich, cells[i] != null && cells[i].getWidth() > 0 && cells[i].getHeight() > 0
                    && labels[i].getWidth() > 0 && labels[i].getHeight() > 0, "tableCell" + i + "HasLaidOutContent");
            for (int j = 0; j < i; j++) require(rich, cells[i] != cells[j], "tableCells" + j + "And" + i + "AreDistinct");
        }
        View a = cells[0], b = cells[1], c = cells[2], d = cells[3];
        require(rich, a.getTop() == b.getTop() && a.getBottom() == b.getBottom() && a.getRight() <= b.getLeft()
                && c.getTop() == d.getTop() && c.getBottom() == d.getBottom() && c.getRight() <= d.getLeft()
                && Math.max(a.getBottom(), b.getBottom()) <= Math.min(c.getTop(), d.getTop()),
                "tableCellsAndRowsRemainSeparated");
        // Fixture columns are [甲, 乙, 乙] then [丙, 丁, empty]; the span must not invent a gap.
        require(rich, a.getLeft() == 0 && a.getLeft() == c.getLeft() && b.getLeft() == d.getLeft()
                && a.getRight() == b.getLeft() && c.getRight() == d.getLeft()
                && a.getWidth() == c.getWidth() && a.getWidth() == d.getWidth()
                && b.getWidth() == 2 * a.getWidth() && b.getRight() == grid.getWidth()
                && grid.getWidth() == 3 * a.getWidth() && d.getRight() < b.getRight(), "tableColspanRetainsTwoColumnWidth");
    }

    private static ViewGroup tableGrid(View label) {
        for (View child = label; child != null && child.getParent() instanceof View; child = (View) child.getParent()) {
            View parent = (View) child.getParent();
            if (parent instanceof ViewGroup && "cn.ngapure.app.NativeRichText$TableGrid".equals(parent.getClass().getName()))
                return (ViewGroup) parent;
        }
        return null;
    }

    private static View childIn(ViewGroup parent, View descendant) {
        View child = descendant;
        while (child != null && child.getParent() instanceof View && child.getParent() != parent)
            child = (View) child.getParent();
        return child != null && child.getParent() == parent ? child : null;
    }

    private void nativeAudioChecks() throws CheckFailure {
        TextView heading = find(content, "♫  音频"), play = find(content, "播放音频");
        require(rich, heading != null && play instanceof Button && play.getParent() instanceof ViewGroup
                && heading.getParent() == play.getParent(), "audioUsesNativeCardAndButton");
        ViewGroup card = (ViewGroup) play.getParent();
        TextView host = find(card, "bbs.nga.cn");
        require(rich, host != null && "bbs.nga.cn".contentEquals(host.getText()) && host.getParent() == card
                && card.indexOfChild(heading) < card.indexOfChild(host) && card.indexOfChild(host) < card.indexOfChild(play)
                && heading.getBottom() <= host.getTop() && host.getBottom() <= play.getTop(),
                "audioCardRetainsReadableSourceAndSeparateControls");
        require(rich, play.isAttachedToWindow() && play.isShown() && play.isEnabled() && play.isClickable()
                && play.hasOnClickListeners() && play.getWidth() > 0 && play.getHeight() > 0
                && play.getContentDescription() != null && "在原生播放器播放音频".contentEquals(play.getContentDescription()),
                "audioNativeActionRemainsReachableWithoutPlayback");
    }

    private void routeChecks() throws Exception {
        int count = 0;
        count += classify(EVENT, NativeLinkTarget.BROWSER);
        count += classify("/read.php?tid=47717100&page=2", NativeLinkTarget.TOPIC);
        count += classify("/thread.php?fid=650&page=2", NativeLinkTarget.FORUM);
        count += classify("/thread.php?stid=26135608", NativeLinkTarget.FORUM);
        NativeLinkTarget collection = NativeLinkTarget.parse("/thread.php?stid=26135608");
        require(links, "26135608".equals(collection.collectionId) && collection.forumId.isEmpty() && collection.page == 1,
                "nativeCollectionRouteRetainsStidWithoutInventedParent");
        count += classify("/read.php?tid=47717100&authorid=1", NativeLinkTarget.TOPIC);
        NativeLinkTarget author = NativeLinkTarget.parse("/read.php?tid=47717100&authorid=1");
        require(links, "47717100".equals(author.topicId) && "1".equals(author.authorId) && author.page == 1,
                "nativeAuthorFilterRetainsTopicAndAuthor");
        count += classify("/nuke.php?__lib=topic_favor&__act=add&tid=47717100", NativeLinkTarget.BLOCKED);
        count += classify("javascript:alert(1)", NativeLinkTarget.INVALID);
        NativeLinkTarget messages = NativeLinkTarget.parse("/nuke.php?__lib=message&__act=list&page=3");
        require(links, messages.kind == NativeLinkTarget.MESSAGES && messages.page == 3,
                "messageListRouteRetainsRequestedPage");
        count++;
        Method attachment = activity.getClass().getDeclaredMethod("isAttachment", Uri.class);
        attachment.setAccessible(true);
        require(links, Boolean.TRUE.equals(attachment.invoke(null, Uri.parse("https://img.nga.cn/attachments/foo.webp"))),
                "officialImageAttachmentUsesPictureViewer");
        require(links, Boolean.FALSE.equals(attachment.invoke(null, Uri.parse("https://img.nga.cn/attachments/foo.mp4"))),
                "videoAttachmentAvoidsPictureViewer");
        require(links, Boolean.FALSE.equals(attachment.invoke(null, Uri.parse("https://img.nga.cn/attachments/foo.pdf"))),
                "documentAttachmentAvoidsPictureViewer");
        require(links, COMPLEX_EVENT.equals(NativeLinkTarget.parse(COMPLEX_EVENT).url), "activityQueryAndAnchorRetained");
        Intent constructed = BrowserLinks.intent(COMPLEX_EVENT);
        require(links, safeBrowserIntent(constructed, COMPLEX_EVENT), "browserIntentPreservesQueryAndAnchor");
        boolean rejected = false;
        try { BrowserLinks.intent("javascript:alert(1)"); }
        catch (IllegalArgumentException expected) { rejected = true; }
        require(links, rejected, "browserIntentRejectsNonWebSchemes");
        put(links, "androidRouteCasesChecked", count);
    }

    private int classify(String url, int kind) throws CheckFailure {
        if (NativeLinkTarget.parse(url).kind != kind) throw new CheckFailure("ANDROID_ROUTE_CLASSIFICATION_FAILED");
        return 1;
    }

    private void browserHandoff() throws Exception {
        BrowserMonitor monitor = new BrowserMonitor(); addMonitor(monitor);
        try {
            Link event = onMain(() -> labeled(spanLinks(content), "活动入口"));
            require(links, event != null && callbacks.get() == 0, "realActivityCallbackStartsUninvoked");
            onMain(() -> { event.span.onClick(event.owner); return null; });
            settle();
            require(links, callbacks.get() == 1 && EVENT.equals(callbackUrl.get()), "spanInvokesRealMainHandleLink");
            AccessibilityNodeInfo confirm = awaitNode("浏览器打开", 5000);
            require(links, confirm != null && node("打开 NGA 页面") != null && node(EVENT) != null,
                    "activityLinkShowsRealBrowserConfirmation");
            require(links, monitor.intercepts.get() == 0, "confirmationDoesNotLaunchBeforeApproval");
            require(links, confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK), "realBrowserConfirmationButtonClicked");
            require(links, monitor.intercepted.await(remaining(5000), TimeUnit.MILLISECONDS), "browserLaunchInterceptedAndBlocked");
            require(links, monitor.intercepts.get() == 1 && safeBrowserIntent(monitor.captured.get(), EVENT),
                    "browserHandoffActionUrlCategorySelectorAndExtrasSafe");
            require(links, callbacks.get() == 1, "browserHandoffDoesNotReenterNativeLinkCallback");
            put(links, "blockedBrowserLaunches", monitor.intercepts.get());
            put(links, "externalBrowserPageRequested", false);
        } finally { removeMonitor(monitor); }
    }

    private static boolean safeBrowserIntent(Intent intent, String url) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || !url.equals(intent.getDataString())
                || intent.getData() == null || intent.getData().getUserInfo() != null || intent.getComponent() != null
                || intent.getPackage() != null || intent.getClipData() != null
                || intent.getExtras() != null && !intent.getExtras().isEmpty()
                || intent.getCategories() == null || intent.getCategories().size() != 1
                || !intent.hasCategory(Intent.CATEGORY_BROWSABLE)
                || (intent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)) != 0) return false;
        Intent selector = intent.getSelector();
        return selector != null && Intent.ACTION_MAIN.equals(selector.getAction())
                && selector.hasCategory(Intent.CATEGORY_APP_BROWSER) && selector.getCategories().size() == 1
                && selector.getData() == null && selector.getComponent() == null && selector.getPackage() == null
                && selector.getSelector() == null && selector.getClipData() == null
                && (selector.getExtras() == null || selector.getExtras().isEmpty());
    }

    private AccessibilityNodeInfo awaitNode(String text, long timeout) throws Exception {
        long until = SystemClock.elapsedRealtime() + remaining(timeout);
        AccessibilityNodeInfo found;
        while ((found = node(text)) == null && SystemClock.elapsedRealtime() < until) SystemClock.sleep(60);
        return found;
    }

    private AccessibilityNodeInfo node(String text) {
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        return findNode(root, text, 0, new int[]{0});
    }

    private static AccessibilityNodeInfo findNode(AccessibilityNodeInfo root, String text, int depth, int[] seen) {
        if (root == null || depth > 48 || ++seen[0] > 1024) return null;
        if (root.getText() != null && text.contentEquals(root.getText())) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo found = findNode(root.getChild(i), text, depth + 1, seen);
            if (found != null) return found;
        }
        return null;
    }

    private void dismissConfirmation() throws Exception {
        if (activity == null) return;
        AccessibilityNodeInfo positive = node("浏览器打开");
        if (positive != null) {
            AccessibilityNodeInfo cancel = node("取消");
            if (cancel == null || !cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new CheckFailure("DIALOG_DISMISS_FAILED");
            onMainCleanup(() -> null); SystemClock.sleep(80);
        }
    }

    private static List<TextView> textViews(View root) {
        List<TextView> result = new ArrayList<>(); collect(root, result); return result;
    }

    private static void collect(View root, List<TextView> out) {
        if (root instanceof TextView) out.add((TextView) root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }

    private static TextView find(View root, String marker) {
        for (TextView view : textViews(root)) if (view.getText().toString().contains(marker)) return view;
        return null;
    }

    private static String ordinaryText(View root, TextView code) {
        StringBuilder text = new StringBuilder();
        for (TextView view : textViews(root)) if (view != code) text.append(view.getText()).append('\n');
        return text.toString();
    }

    private static <T> boolean hasSpan(View root, String marker, Class<T> type) {
        for (TextView view : textViews(root)) {
            CharSequence value = view.getText(); int at = value.toString().indexOf(marker);
            if (at >= 0 && value instanceof Spanned && ((Spanned) value).getSpans(at, at + marker.length(), type).length > 0) return true;
        }
        return false;
    }

    private static List<Link> spanLinks(View root) {
        List<Link> result = new ArrayList<>();
        for (TextView view : textViews(root)) if (view.getText() instanceof Spanned) {
            Spanned text = (Spanned) view.getText();
            for (ClickableSpan span : text.getSpans(0, text.length(), ClickableSpan.class)) {
                int start = text.getSpanStart(span), end = text.getSpanEnd(span);
                result.add(new Link(view, span, start, end, text.subSequence(start, end).toString(),
                        span instanceof URLSpan ? ((URLSpan) span).getURL() : ""));
            }
        }
        return result;
    }

    private static boolean allSingleSpanRanges(List<Link> spans) {
        for (int i = 0; i < spans.size(); i++) {
            Link a = spans.get(i);
            if (a.start < 0 || a.end <= a.start || a.url.isEmpty()) return false;
            for (int j = i + 1; j < spans.size(); j++) {
                Link b = spans.get(j);
                if (a.owner == b.owner && a.start < b.end && b.start < a.end) return false;
            }
        }
        return true;
    }

    private static Link labeled(List<Link> spans, String label) {
        Link found = null;
        for (Link span : spans) if (label.equals(span.label)) {
            if (found != null) return null;
            found = span;
        }
        return found;
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void write(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static Object call(Object owner, String name) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(owner);
    }
    private long remaining(long maximum) throws CheckFailure {
        long left = deadline - SystemClock.elapsedRealtime();
        if (left <= 0) throw new CheckFailure("RICH_TEXT_LINKS_DEADLINE");
        return Math.min(left, maximum);
    }
    private void settle() throws Exception { onMain(() -> null); SystemClock.sleep(Math.min(160, remaining(160))); onMain(() -> null); }
    private <T> T onMain(Callable<T> action) throws Exception {
        return onMainWithin(action, remaining(5000));
    }
    private <T> T onMainCleanup(Callable<T> action) throws Exception {
        return onMainWithin(action, 5000);
    }
    private <T> T onMainWithin(Callable<T> action, long timeout) throws Exception {
        CountDownLatch ready = new CountDownLatch(1); Object[] value = new Object[1]; Throwable[] failure = new Throwable[1];
        main.post(() -> { try { value[0] = action.call(); } catch (Throwable unavailable) { failure[0] = unavailable; } finally { ready.countDown(); } });
        if (!ready.await(timeout, TimeUnit.MILLISECONDS)) throw new CheckFailure("MAIN_THREAD_DEADLINE");
        if (failure[0] instanceof CheckFailure) throw (CheckFailure) failure[0];
        if (failure[0] instanceof Exception) throw (Exception) failure[0];
        if (failure[0] != null) throw new CheckFailure("MAIN_THREAD_CHECK_FAILED");
        @SuppressWarnings("unchecked") T typed = (T) value[0]; return typed;
    }
    private void common(boolean condition, String key) throws CheckFailure { putBoth(key, condition); if (!condition) throw new CheckFailure(key); }
    private static void require(JSONObject target, boolean condition, String key) throws CheckFailure { put(target, key, condition); if (!condition) throw new CheckFailure(key); }
    private void putBoth(String key, Object value) { put(rich, key, value); put(links, key, value); }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value); } catch (Exception ignored) { /* Fixed safe projection only. */ } }

    private static final class Link {
        final TextView owner; final ClickableSpan span; final int start, end; final String label, url;
        Link(TextView owner, ClickableSpan span, int start, int end, String label, String url) {
            this.owner = owner; this.span = span; this.start = start; this.end = end; this.label = label; this.url = url;
        }
    }
    private static final class BrowserMonitor extends ActivityMonitor {
        final AtomicInteger intercepts = new AtomicInteger();
        final CountDownLatch intercepted = new CountDownLatch(1);
        final AtomicReference<Intent> captured = new AtomicReference<>();
        @Override public ActivityResult onStartActivity(Intent intent) {
            if (!Intent.ACTION_VIEW.equals(intent.getAction())) return null;
            captured.set(new Intent(intent)); intercepts.incrementAndGet(); intercepted.countDown();
            return new ActivityResult(Activity.RESULT_CANCELED, null);
        }
    }
    private static final class Snapshot { ViewGroup host; View original; ViewGroup.LayoutParams params; }
    private static final class Settings {
        private String account, session;
        private final String host, theme, day, attempt, message;
        private final boolean automatic, glass, motion; private final int font;
        Settings(NgaStore store) {
            account = store.accountId(); session = store.cookie(); host = store.host(); theme = store.theme();
            day = store.lastCheckInDay(); attempt = store.lastCheckInAttempt(); message = store.checkInMessage();
            automatic = store.autoCheckIn(); glass = store.glassEnabled(); motion = store.motionEnabled(); font = store.fontSize();
        }
        boolean same(NgaStore store) {
            return account.equals(store.accountId()) && session.equals(store.cookie()) && host.equals(store.host())
                    && theme.equals(store.theme()) && day.equals(store.lastCheckInDay()) && attempt.equals(store.lastCheckInAttempt())
                    && message.equals(store.checkInMessage()) && automatic == store.autoCheckIn() && glass == store.glassEnabled()
                    && motion == store.motionEnabled() && font == store.fontSize();
        }
        void clear() { account = ""; session = ""; }
    }
    private static final class CheckFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final String code; CheckFailure(String code) { this.code = code; }
    }
}
