package cn.ngapure.verify;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import cn.ngapure.app.AvatarView;
import cn.ngapure.app.LiquidSelection;
import cn.ngapure.app.NativeAvatarResolver;
import cn.ngapure.app.NativeLoginView;
import cn.ngapure.app.NativeRichText;
import cn.ngapure.app.NgaApi;
import cn.ngapure.app.NgaStore;
import org.json.JSONObject;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Production dock and production post renderer, measured on a real Android window.
 * Reader data is fixed, local, image-free synthetic text; action callbacks are never
 * activated except a local collapse toggle. No account setting or remote state is
 * written. Normal MainActivity startup is refused when it could submit sign-in or
 * resume a pending QR authorization. The report contains geometry and booleans only.
 *
 * Run independently after resizing the dedicated emulator, for example with
 * -e expected_width_dp 360 or 840. This probe never changes display or font settings.
 */
public final class AdaptiveUiProbe extends Instrumentation {
    private static final long LIMIT_MILLIS = 35000;
    // Satisfies the production author-link branch, but is deliberately outside
    // the avatar resolver's signed-32-bit public-UID domain. Never click it.
    private static final String SYNTHETIC_AUTHOR_ID = "99999999999999999";
    private final JSONObject report = new JSONObject();
    private final Handler main = new Handler(Looper.getMainLooper());
    private long deadline;
    private int expectedWidth;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        if (args != null) {
            String requested = args.getString("expected_width_dp", "");
            if ("360".equals(requested) || "840".equals(requested)) expectedWidth = Integer.parseInt(requested);
        }
        start();
    }
    @Override public void onStart() {
        deadline = SystemClock.elapsedRealtime() + LIMIT_MILLIS;
        try {
            put("schema", 1); put("expectedWidthDp", expectedWidth); put("syntheticRemoteRequests", 0);
            NgaStore store = new NgaStore(getTargetContext());
            require(!store.autoCheckIn() || (store.loggedIn() && NgaStore.today().equals(store.lastCheckInDay())), "onOpenSignInGuardPassed");
            require(!NativeLoginView.hasPendingAuthorization(getTargetContext(), store.host()), "pendingAuthorizationGuardPassed");
            String account = store.accountId(), host = store.host(), theme = store.theme(), day = store.lastCheckInDay();
            boolean automatic = store.autoCheckIn(), glass = store.glassEnabled(), motion = store.motionEnabled();
            int font = store.fontSize();
            Activity activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName("cn.ngapure.app", "cn.ngapure.app.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            settle();
            onMain(() -> { dock(activity); return null; });
            reader(activity);
            require(account.equals(store.accountId()) && host.equals(store.host()) && theme.equals(store.theme())
                    && day.equals(store.lastCheckInDay()) && automatic == store.autoCheckIn()
                    && glass == store.glassEnabled() && motion == store.motionEnabled() && font == store.fontSize(), "accountAndSettingsUnchanged");
            put("passed", true); put("failure", "NONE");
        } catch (CheckFailure failed) {
            put("passed", false); put("failure", failed.code);
        } catch (Exception unavailable) {
            put("passed", false); put("failure", "ADAPTIVE_UI_UNAVAILABLE");
        }
        Bundle output = new Bundle(); output.putString("safe_adaptive_ui_report", report.toString()); finish(Activity.RESULT_OK, output);
    }
    private void dock(Activity activity) throws Exception {
        View root = (View) field(activity, "root");
        View panel = (View) field(activity, "navigationGlass");
        LiquidSelection selection = (LiquidSelection) field(activity, "primarySelection");
        require(root != null && panel != null && selection != null && panel.isShown(), "realHomeDockPresent");
        float density = activity.getResources().getDisplayMetrics().density;
        int usable = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        put("windowWidthDp", Math.round(root.getWidth() / density));
        put("usableWidthDp", Math.round(usable / density));
        put("fontScalePercent", Math.round(activity.getResources().getConfiguration().fontScale * 100));
        require(expectedWidth == 0 || Math.abs(root.getWidth() / density - expectedWidth) <= 8, "requestedWindowWidthVerified");
        require("bottom-navigation".equals(panel.getTag()) && tagged(root, "bottom-navigation").size() == 1, "singleDockRow");
        int expected = Math.min(dp(activity, 480), usable - dp(activity, 24));
        put("dockWidthDp", Math.round(panel.getWidth() / density));
        require(panel.getWidth() > 0 && Math.abs(panel.getWidth() - expected) <= 2, "dockWidthCappedAt480Dp");
        Rect window = bounds(root), dock = bounds(panel);
        int usableLeft = window.left + root.getPaddingLeft(), usableRight = window.right - root.getPaddingRight();
        require(Math.abs(dock.left + dock.right - usableLeft - usableRight) <= 3, "dockCenteredInUsableWindow");
        require(dock.left >= usableLeft && dock.right <= usableRight, "dockInsideUsableWindow");
        require(selection.getChildCount() == 4, "fourNavigationTargets");
        for (int i = 0; i < selection.getChildCount(); i++) require(targetSize(selection.getChildAt(i), activity), "dockTargetsAtLeast48Dp");
        require(horizontalChildrenDoNotOverlap(selection), "dockTargetsDoNotOverlap");
        require(horizontalTreeFits(panel), "dockDescendantsNotClipped");
    }
    private void reader(Activity activity) throws Exception {
        Snapshot saved = onMain(() -> {
            Snapshot value = new Snapshot();
            value.host = (ViewGroup) activity.findViewById(android.R.id.content);
            value.original = value.host.getChildAt(0); value.originalParams = value.original.getLayoutParams();
            value.body = field(activity, "body"); value.current = field(activity, "current");
            value.scroll = field(activity, "currentScroll"); value.scrollKey = field(activity, "currentScrollKey");
            call(activity, "cancelHomeNavigation");
            call(field(activity, "pageReads"), "cancelAll");
            write(activity, "generation", (Integer) field(activity, "generation") + 1);
            View root = (View) field(activity, "root");
            FrameLayout local = new FrameLayout(activity);
            local.setPadding(root.getPaddingLeft(), root.getPaddingTop(), root.getPaddingRight(), root.getPaddingBottom());
            Class<?> screenType = Class.forName("cn.ngapure.app.MainActivity$Screen");
            Constructor<?> constructor = screenType.getDeclaredConstructor(String.class, String.class, String.class, int.class);
            constructor.setAccessible(true);
            // Never passed to NgaApi, history, preferences, intents or remote callbacks.
            Object syntheticScreen = constructor.newInstance("thread", "99999999999999999", "本机排版检查", 1);
            try {
                write(activity, "current", syntheticScreen); write(activity, "body", local);
                value.host.removeAllViews(); value.host.addView(local, new ViewGroup.LayoutParams(-1, -1));
                require(SYNTHETIC_AUTHOR_ID.matches("[1-9][0-9]*") && !NativeAvatarResolver.eligible(SYNTHETIC_AUTHOR_ID),
                        "syntheticAuthorLinkOutsideAvatarLookupRange");
                value.avatarResolver = field(field(activity, "nativeImages"), "authorAvatarResolver");
                require(value.avatarResolver != null && field(value.avatarResolver, "source") instanceof NativeAvatarResolver.Source,
                        "syntheticAvatarSourceObserved");
                value.avatarSource = (NativeAvatarResolver.Source) field(value.avatarResolver, "source");
                // Observe the actual owner source without enabling any new source.
                // An ineligible fixture UID is blocked before delegation even if
                // avatar binding regresses; existing normal work retains its source.
                write(value.avatarResolver, "source", (NativeAvatarResolver.Source) (uid, cancellation) -> {
                    if (!NativeAvatarResolver.eligible(uid)) {
                        value.syntheticAvatarLookups.incrementAndGet();
                        throw new IOException("Local adaptive fixture must not look up an author");
                    }
                    return value.avatarSource.avatar(uid, cancellation);
                });
                value.column = (LinearLayout) call(activity, "scrollBody");
                for (int i = 0; i < 2; i++) {
                    NgaApi.Post post = new NgaApi.Post(); post.floor = i; post.authorId = SYNTHETIC_AUTHOR_ID;
                    post.author = "本机合成排版作者与足够长的用户名检查"; post.time = "2026-10-19 12:34";
                    post.content = "这是一段只存在于本机的合成帖子，用于检查窄屏和展开窗口中的正文换行、作者信息和操作区域。\n\n"
                            + "[quote]引用内容保持清晰的层级，中文 English 12345 混排不应产生水平裁切。[/quote]\n"
                            + "[code]for (int index = 0; index < 123456789; index++) { nativeReader.render(localSyntheticText); }[/code]\n"
                            + "[collapse=合成折叠段落]展开后的本机测试内容应该增加卡片高度，同时保持操作按钮位置正确。[/collapse]\n"
                            + "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
                    call(activity, "postRow", new Class<?>[]{LinearLayout.class, NgaApi.Post.class}, value.column, post);
                }
            } catch (Exception unavailable) {
                restore(activity, value); throw unavailable;
            }
            return value;
        });
        try {
            settle();
            onMain(() -> { readerGeometry(activity, saved, false); return null; });
            boolean expanded = onMain(() -> {
                View collapse = findCollapse(saved.column);
                if (collapse == null) return false;
                return collapse.performClick();
            });
            require(expanded, "syntheticCollapseToggleWorks");
            settle();
            onMain(() -> { readerGeometry(activity, saved, true); return null; });
            require(onMain(() -> saved.column.getParent() instanceof ScrollView), "readerRemainsVerticallyScrollable");
        } finally {
            onMain(() -> { restore(activity, saved); return null; });
        }
    }
    private static void restore(Activity activity, Snapshot saved) throws Exception {
        saved.host.removeAllViews();
        if (saved.avatarResolver != null && saved.avatarSource != null) write(saved.avatarResolver, "source", saved.avatarSource);
        saved.host.addView(saved.original, saved.originalParams);
        write(activity, "body", saved.body); write(activity, "current", saved.current);
        write(activity, "currentScroll", saved.scroll); write(activity, "currentScrollKey", saved.scrollKey);
    }
    private void readerGeometry(Activity activity, Snapshot saved, boolean expanded) throws Exception {
        LinearLayout column = saved.column;
        String suffix = expanded ? "AfterExpand" : "";
        require("reader-column".equals(column.getTag()), "productionReaderColumnTagged");
        float density = activity.getResources().getDisplayMetrics().density;
        put("readerColumnWidthDp", Math.round(column.getWidth() / density));
        require(column.getWidth() > 0 && column.getWidth() <= dp(activity, 680) + 1, "readerColumnCappedAt680Dp" + suffix);
        ScrollView scroll = (ScrollView) column.getParent();
        Rect viewport = bounds(scroll), content = bounds(column);
        int left = viewport.left + scroll.getPaddingLeft(), right = viewport.right - scroll.getPaddingRight();
        require(Math.abs(content.left + content.right - left - right) <= 3, "readerColumnCentered" + suffix);
        List<View> cards = tagged(column, "post-card");
        require(cards.size() == 2, "syntheticPostCardCountVerified");
        for (View card : cards) {
            require(card.getWidth() > 0 && card.getWidth() <= column.getWidth(), "postCardsInsideColumn" + suffix);
            require(horizontalTreeFits(card), "postDescendantsNotClipped" + suffix);
            verifyCodeScrollers(card, suffix);
            require(textLayoutsFit(card), "postTextWrapsInsideContent" + suffix);
            List<View> authors = tagged(card, "post-author"), actions = tagged(card, "post-actions");
            require(authors.size() == 1 && authors.get(0).isClickable() && targetSize(authors.get(0), activity), "authorTargetAtLeast48Dp" + suffix);
            require(actions.size() == 1 && actions.get(0).getHeight() >= dp(activity, 48), "postActionsAtLeast48Dp" + suffix);
            require(horizontalChildrenDoNotOverlap((ViewGroup) actions.get(0)), "postActionsDoNotOverlap" + suffix);
            ViewGroup row = (ViewGroup) actions.get(0); int clickable = 0;
            for (int i = 0; i < row.getChildCount(); i++) if (row.getChildAt(i).isClickable()) {
                clickable++; require(targetSize(row.getChildAt(i), activity), "postActionTargetsAtLeast48Dp" + suffix);
            }
            require(clickable >= 2, "postActionTargetsPresent" + suffix);
            List<View> rich = classViews(card, NativeRichText.class);
            require(rich.size() == 1, "nativePostBodyPresent" + suffix);
            View body = rich.get(0); Rect cardBounds = bounds(card), bodyBounds = bounds(body);
            require(bodyBounds.left + body.getPaddingLeft() - cardBounds.left >= dp(activity, 16) - 1
                    && cardBounds.right - bodyBounds.right + body.getPaddingRight() >= dp(activity, 16) - 1,
                    "postBodyHasReadableInsets" + suffix);
        }
        List<View> avatars = classViews(column, AvatarView.class);
        require(avatars.size() == 2, "syntheticAuthorAvatarCountVerified" + suffix);
        for (View avatar : avatars) {
            require("".equals(field(avatar, "authorId")) && "".equals(field(avatar, "url"))
                    && field(avatar, "pending") == null && field(avatar, "bitmap") == null,
                    "syntheticAvatarHasNoLookupIdentityOrPendingWork" + suffix);
        }
        put("syntheticAvatarSourceCalls", saved.syntheticAvatarLookups.get());
        require(saved.syntheticAvatarLookups.get() == 0, "syntheticAvatarSourceNeverInvoked" + suffix);
    }
    private static boolean targetSize(View view, Activity activity) {
        return view.getWidth() + 1 >= dp(activity, 48) && view.getHeight() + 1 >= dp(activity, 48);
    }
    private static boolean horizontalTreeFits(View view) {
        if (!(view instanceof ViewGroup)) return true;
        ViewGroup group = (ViewGroup) view;
        if (group instanceof HorizontalScrollView) {
            // A code viewport deliberately owns one wider text child. Its viewport
            // was checked against the surrounding card by the caller's parent loop.
            // Do not waive clipping for arbitrary scroll trees or overflowing panels.
            if (group.getChildCount() != 1 || !group.getClipChildren() || !group.getClipToPadding()
                    || !(group.getChildAt(0) instanceof TextView)) return false;
            View child = group.getChildAt(0);
            return child.getLeft() >= -1 && child.getWidth() >= group.getWidth() - group.getPaddingLeft() - group.getPaddingRight()
                    && horizontalTreeFits(child);
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i); if (child.getVisibility() == View.GONE) continue;
            if (child.getLeft() < -1 || child.getRight() > group.getWidth() + 1 || !horizontalTreeFits(child)) return false;
        }
        return true;
    }
    private void verifyCodeScrollers(View card, String suffix) throws Exception {
        List<View> horizontal = classViews(card, HorizontalScrollView.class);
        require(horizontal.size() == 1, "syntheticCodeViewportPresent" + suffix);
        HorizontalScrollView viewport = (HorizontalScrollView) horizontal.get(0);
        require(viewport.getChildCount() == 1 && viewport.getChildAt(0) instanceof TextView
                && viewport.getClipChildren() && viewport.getClipToPadding(), "codeViewportClipsItsWideChild" + suffix);
        View child = viewport.getChildAt(0);
        int maximum = Math.max(0, child.getWidth() - (viewport.getWidth() - viewport.getPaddingLeft() - viewport.getPaddingRight()));
        int original = viewport.getScrollX();
        require(maximum > 0, "longCodeHasHorizontalRange" + suffix);
        try {
            viewport.scrollTo(maximum, 0);
            require(viewport.getScrollX() == maximum && viewport.canScrollHorizontally(-1) && !viewport.canScrollHorizontally(1),
                    "longCodeEndCanBeReached" + suffix);
            viewport.scrollTo(0, 0);
            require(viewport.getScrollX() == 0 && viewport.canScrollHorizontally(1) && !viewport.canScrollHorizontally(-1),
                    "longCodeStartCanBeReached" + suffix);
        } finally { viewport.scrollTo(original, 0); }
    }
    private static boolean horizontalChildrenDoNotOverlap(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View a = group.getChildAt(i); if (a.getVisibility() != View.VISIBLE) continue;
            for (int j = i + 1; j < group.getChildCount(); j++) {
                View b = group.getChildAt(j); if (b.getVisibility() != View.VISIBLE) continue;
                if (Math.min(a.getRight(), b.getRight()) - Math.max(a.getLeft(), b.getLeft()) > 1) return false;
            }
        }
        return true;
    }
    private static boolean textLayoutsFit(View view) {
        if (view.getVisibility() == View.GONE) return true;
        if (view instanceof TextView) {
            TextView text = (TextView) view; Layout layout = text.getLayout();
            if (layout != null) {
                int room = text.getWidth() - text.getTotalPaddingLeft() - text.getTotalPaddingRight();
                for (int i = 0; i < layout.getLineCount(); i++) if (layout.getLineRight(i) > room + 2 || layout.getLineLeft(i) < -2) return false;
            }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (!textLayoutsFit(((ViewGroup) view).getChildAt(i))) return false;
        return true;
    }
    private static List<View> tagged(View root, String tag) {
        List<View> result = new ArrayList<>(); collect(root, tag, null, result); return result;
    }
    private static List<View> classViews(View root, Class<?> type) {
        List<View> result = new ArrayList<>(); collect(root, null, type, result); return result;
    }
    private static void collect(View view, String tag, Class<?> type, List<View> result) {
        if ((tag != null && tag.equals(view.getTag())) || (type != null && type.isInstance(view))) result.add(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), tag, type, result);
    }
    private static View findCollapse(View view) {
        if (view instanceof TextView && ((TextView) view).getText().toString().startsWith("▸  合成折叠段落")) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = findCollapse(((ViewGroup) view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private static Rect bounds(View view) { int[] location = new int[2]; view.getLocationInWindow(location); return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight()); }
    private static int dp(Activity activity, int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private static Object field(Object owner, String name) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
    private static void write(Object owner, String name, Object value) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); }
    private static Object call(Object owner, String name) throws Exception { return call(owner, name, new Class<?>[0]); }
    private static Object call(Object owner, String name, Class<?>[] parameters, Object... values) throws Exception { Method method = owner.getClass().getDeclaredMethod(name, parameters); method.setAccessible(true); return method.invoke(owner, values); }
    private <T> T onMain(Callable<T> action) throws Exception {
        CountDownLatch ready = new CountDownLatch(1); Object[] result = new Object[1]; Exception[] failure = new Exception[1];
        main.post(() -> { try { result[0] = action.call(); } catch (Exception unavailable) { failure[0] = unavailable; } finally { ready.countDown(); } });
        if (!ready.await(Math.max(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS)) throw new CheckFailure("MAIN_THREAD_DEADLINE");
        if (failure[0] != null) throw failure[0];
        @SuppressWarnings("unchecked") T typed = (T) result[0]; return typed;
    }
    private void settle() throws Exception { onMain(() -> null); SystemClock.sleep(220); onMain(() -> null); }
    private void require(boolean condition, String key) throws CheckFailure { put(key, condition); if (!condition) throw new CheckFailure(key); }
    private void put(String key, Object value) { try { report.put(key, value); } catch (Exception ignored) { } }
    private static final class CheckFailure extends Exception { private static final long serialVersionUID = 1L; final String code; CheckFailure(String code) { this.code = code; } }
    private static final class Snapshot {
        ViewGroup host; View original; ViewGroup.LayoutParams originalParams;
        Object body, current, scroll, scrollKey; LinearLayout column;
        Object avatarResolver; NativeAvatarResolver.Source avatarSource;
        final AtomicInteger syntheticAvatarLookups = new AtomicInteger();
    }
}
