package cn.ngapure.verify;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import cn.ngapure.app.AvatarView;
import cn.ngapure.app.BoardIconCatalog;
import cn.ngapure.app.NativeImages;
import cn.ngapure.app.NativeLoginView;
import cn.ngapure.app.NgaStore;
import cn.ngapure.app.RequestCancellation;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Public bundled board PNG decode and synthetic avatar lifecycle only. Positive UIDs
 * below belong exclusively to local fixtures; their source returns bundled board art,
 * never a real user's photograph or public profile. Isolated image loaders block any
 * network fallback before getResponseCode. Normal app startup is refused if it could
 * resume QR authorization or perform a new sign-in. Only counts and booleans are output.
 */
public final class AvatarProbe extends Instrumentation {
    private static final String FIXTURE_A = "https://img4.nga.cn/ngabbs/nga_classic/f/app/650.png";
    private static final String FIXTURE_B = "https://img4.nga.cn/ngabbs/nga_classic/f/app/722.png";
    private static final String DETACH_UID = "500000901", OLD_UID = "500001001", NEW_UID = "500001002";
    private final JSONObject report = new JSONObject();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<LocalLoader> loaders = new ArrayList<>();
    private final List<CountDownLatch> barriers = new ArrayList<>();
    private long deadline;
    private Activity activity;
    private Snapshot snapshot;
    private ScrollView viewport;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        deadline = SystemClock.elapsedRealtime() + 120000;
        Settings before = null;
        try {
            NgaStore store = new NgaStore(getTargetContext());
            require(!store.autoCheckIn() || (store.loggedIn() && NgaStore.today().equals(store.lastCheckInDay())), "onOpenSignInGuardPassed");
            require(!NativeLoginView.hasPendingAuthorization(getTargetContext(), store.host()), "pendingAuthorizationGuardPassed");
            before = new Settings(store);
            activity = startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.ngapure.app", "cn.ngapure.app.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            settle();
            onMain(() -> { verifyRealForumMappings(); return null; });
            snapshot = onMain(this::replaceRoot);
            allBundledPngs();
            visibilityAndDetach();
            staleRebinding();
            queueRecovery();
            require(totalBlockedAttempts() == 0, "syntheticNetworkFallbackNeverAttempted");
            require(before.same(store), "accountAndSettingsUnchanged");
            put("passed", true); put("failure", "NONE");
        } catch (CheckFailure failed) {
            put("passed", false); put("failure", failed.code);
        } catch (Exception | AssertionError unavailable) {
            put("passed", false); put("failure", "AVATAR_PROBE_UNAVAILABLE");
        } finally {
            for (CountDownLatch barrier : barriers) barrier.countDown();
            try {
                if (snapshot != null) onMain(() -> { snapshot.host.removeAllViews(); snapshot.host.addView(snapshot.original, snapshot.params); return null; });
                put("originalRootRestored", snapshot != null);
            } catch (Exception unavailable) { put("originalRootRestored", false); put("passed", false); put("failure", "ROOT_RESTORE_FAILED"); }
            for (LocalLoader loader : loaders) loader.images.close();
            put("syntheticNetworkRequests", 0);
            put("blockedSyntheticNetworkAttempts", totalBlockedAttempts());
            put("syntheticSourceIsBundledFixture", true);
            if (before != null) {
                boolean same = before.same(new NgaStore(getTargetContext()));
                put("accountAndSettingsUnchangedAfterRestore", same);
                if (!same) { put("passed", false); put("failure", "SETTINGS_CHANGED"); }
            }
        }
        Bundle output = new Bundle(); output.putString("safe_avatar_report", report.toString()); finish(Activity.RESULT_OK, output);
    }
    private void verifyRealForumMappings() throws Exception {
        Object raw = field(activity, "forumIcons");
        require(raw instanceof Map<?, ?>, "realForumIconMapPresent");
        Map<?, ?> mappings = (Map<?, ?>) raw;
        String[] formerlyMissing = {"650", "-7955747", "843", "722"};
        for (String fid : formerlyMissing) {
            String expected = BoardIconCatalog.resolve(fid, "", "");
            require(!expected.isEmpty() && expected.equals(mappings.get(fid)) && !BoardIconCatalog.assetFor(expected).isEmpty(), "formerlyMissingBoardIconsMapped");
        }
        int bundled = 0;
        for (Object value : mappings.values()) if (value instanceof String && !BoardIconCatalog.assetFor((String) value).isEmpty()) bundled++;
        put("realMainBundledForumMappings", bundled);
        require(bundled == 352, "all352MainForumIconsMapped");
        put("formerlyMissingBoardIconCount", formerlyMissing.length);
    }
    private Snapshot replaceRoot() throws Exception {
        Snapshot saved = new Snapshot(); saved.host = (ViewGroup) activity.findViewById(android.R.id.content);
        saved.original = saved.host.getChildAt(0); saved.params = saved.original.getLayoutParams();
        call(activity, "cancelHomeNavigation"); call(field(activity, "pageReads"), "cancelAll");
        write(activity, "generation", (Integer) field(activity, "generation") + 1);
        viewport = new ScrollView(activity); viewport.setFillViewport(false);
        saved.host.removeAllViews(); saved.host.addView(viewport, new ViewGroup.LayoutParams(-1, -1));
        return saved;
    }
    private void allBundledPngs() throws Exception {
        JSONObject manifest;
        try (InputStream input = getTargetContext().getAssets().open("forum-icons/index.json"); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) { require(output.size() + count <= 1048576, "bundledIndexBounded"); output.write(buffer, 0, count); }
            manifest = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
        JSONArray icons = manifest.getJSONArray("icons"); require(icons.length() == 536, "bundledArtworkInventory536");
        LocalLoader loader = localLoader(); AtomicInteger loaded = new AtomicInteger(), failed = new AtomicInteger(), wrongSize = new AtomicInteger(), wrongThread = new AtomicInteger();
        int batches = 0;
        for (int from = 0; from < icons.length(); from += 16) {
            int first = from, end = Math.min(from + 16, icons.length()); CountDownLatch done = new CountDownLatch(end - first); batches++;
            onMain(() -> {
                for (int i = first; i < end; i++) {
                    JSONObject icon = icons.getJSONObject(i); final int width = icon.getInt("width"), height = icon.getInt("height");
                    String url = icon.getString("url");
                    if (!icon.getString("asset").equals(BoardIconCatalog.assetFor(url))) throw new CheckFailure("ASSET_INDEX_MAPPING_MISMATCH");
                    loader.images.loadAvatar(url, new NativeImages.Callback() {
                        @Override public void loaded(Bitmap bitmap) {
                            loaded.incrementAndGet();
                            if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() != width || bitmap.getHeight() != height) wrongSize.incrementAndGet();
                            if (Looper.myLooper() != Looper.getMainLooper()) wrongThread.incrementAndGet(); done.countDown();
                        }
                        @Override public void failed(String message) { failed.incrementAndGet(); done.countDown(); }
                    });
                }
                return null;
            });
            require(done.await(remaining(8000), TimeUnit.MILLISECONDS), "boundedAvatarBatchCompleted");
        }
        put("bundledDecodeBatchLimit", 16); put("bundledDecodeBatches", batches); put("bundledPngDecoded", loaded.get()); put("bundledDecodeFailures", failed.get());
        put("bundledAssetReads", loader.localReads.get());
        require(loaded.get() == 536 && failed.get() == 0 && wrongSize.get() == 0 && wrongThread.get() == 0 && loader.localReads.get() == 536, "all536BundledPngDecodedOnAndroid");
        require(loader.blocked.get() == 0, "bundledDecodeUsedNoNetwork"); loader.images.close();
    }
    private void visibilityAndDetach() throws Exception {
        LocalLoader loader = localLoader(); CountDownLatch release = barrier(), detachedStarted = new CountDownLatch(1), detachedRelease = barrier();
        AtomicInteger calls = new AtomicInteger(), cancellations = new AtomicInteger(); Set<String> called = ConcurrentHashMap.newKeySet();
        loader.images.setAuthorAvatarSource((uid, token) -> {
            calls.incrementAndGet(); called.add(uid);
            if (DETACH_UID.equals(uid)) { detachedStarted.countDown(); awaitSource(detachedRelease, token, cancellations); }
            else awaitSource(release, token, cancellations);
            return FIXTURE_A;
        });
        List<AvatarView> views = new ArrayList<>();
        LinearLayout column = onMain(() -> {
            LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < 40; i++) {
                AvatarView avatar = new AvatarView(activity, 48, loader.images); avatar.bindUser(Integer.toString(500000001 + i), "", "本机合成头像");
                LinearLayout row = new LinearLayout(activity); row.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));
                content.addView(row, new LinearLayout.LayoutParams(-1, dp(140))); views.add(avatar);
            }
            return content;
        });
        require(calls.get() == 0, "detachedAvatarTreeStartsNoLookups");
        show(column); settle(); waitUntil(() -> calls.get() > 0);
        int initialVisible = onMain(() -> visibleCount(views));
        Set<String> initialTasks = resolverTaskIds(loader.images);
        Set<String> visibleIds = onMain(() -> visibleIds(views, 500000001));
        put("initialVisibleSyntheticAvatars", initialVisible); put("initialQueuedPublicMetadataTasks", initialTasks.size());
        require(initialVisible > 0 && initialVisible < 40 && !initialTasks.isEmpty() && visibleIds.containsAll(initialTasks), "onlyVisibleAvatarsQueueMetadata");
        onMain(() -> { viewport.scrollTo(0, column.getHeight()); return null; }); settle();
        waitUntil(() -> cancellations.get() > 0);
        require(onMain(() -> field(views.get(0), "pending") == null && field(views.get(0), "bitmap") == null), "scrollCancelsOffscreenAvatar");
        release.countDown();
        waitUntil(() -> onMain(() -> visibleLoadedCount(views) == visibleCount(views) && visibleCount(views) > 0));
        require(onMain(() -> bitmap(views.get(39)) != null), "newViewportAvatarsLoad");
        put("scrollCancelledSyntheticLookups", cancellations.get());
        AvatarView last = views.get(39);
        onMain(() -> { last.bindUser(DETACH_UID, "", "本机合成离屏检查"); return null; });
        require(detachedStarted.await(remaining(4000), TimeUnit.MILLISECONDS), "detachLookupStarted");
        int beforeDetach = cancellations.get();
        onMain(() -> { viewport.removeAllViews(); return null; });
        waitUntil(() -> cancellations.get() > beforeDetach);
        require(onMain(() -> field(last, "pending") == null && bitmap(last) == null), "detachCancelsPendingAvatar");
        detachedRelease.countDown(); settle();
        require(onMain(() -> bitmap(last) == null), "detachedAvatarReceivesNoLateBitmap");
        require(resolverTaskIds(loader.images).isEmpty(), "detachedAvatarMetadataQueueEmpty");
        require(loader.blocked.get() == 0, "visibilityFixtureUsedNoNetwork"); loader.images.close();
    }
    private void staleRebinding() throws Exception {
        LocalLoader loader = localLoader(); CountDownLatch oldStarted = new CountDownLatch(1), oldRelease = barrier(), oldFinished = new CountDownLatch(1);
        loader.images.setAuthorAvatarSource((uid, token) -> {
            if (OLD_UID.equals(uid)) {
                oldStarted.countDown(); long until = SystemClock.elapsedRealtime() + 8000;
                // Deliberately uncooperative local fixture tests stale-result suppression after cancellation.
                while (oldRelease.getCount() != 0 && SystemClock.elapsedRealtime() < until) {
                    try { oldRelease.await(40, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { /* Continue local bounded fixture only. */ }
                }
                oldFinished.countDown(); return FIXTURE_A;
            }
            if (NEW_UID.equals(uid)) return FIXTURE_B;
            throw new IOException("Unrecognized local fixture identity");
        });
        AvatarView view = onMain(() -> { AvatarView avatar = new AvatarView(activity, 48, loader.images); avatar.bindUser(OLD_UID, "", "本机合成旧头像"); return avatar; });
        show(view); settle(); require(oldStarted.await(remaining(4000), TimeUnit.MILLISECONDS), "oldAvatarLookupStarted");
        onMain(() -> { view.bindUser(NEW_UID, "", "本机合成新头像"); return null; });
        waitUntil(() -> onMain(() -> bitmap(view) != null)); Bitmap replacement = onMain(() -> bitmap(view));
        oldRelease.countDown(); require(oldFinished.await(remaining(4000), TimeUnit.MILLISECONDS), "staleLookupCompletedLocally"); settle();
        require(onMain(() -> bitmap(view) == replacement && "本机合成新头像的头像".contentEquals(view.getContentDescription())), "staleLookupCannotReplaceReboundAvatar");
        require(loader.blocked.get() == 0, "rebindFixtureUsedNoNetwork");
        onMain(() -> { viewport.removeAllViews(); return null; }); loader.images.close();
    }
    private void queueRecovery() throws Exception {
        LocalLoader loader = localLoader(); CountDownLatch release = barrier(); Set<String> lookedUp = ConcurrentHashMap.newKeySet();
        AtomicInteger cancellations = new AtomicInteger();
        loader.images.setAuthorAvatarSource((uid, token) -> { lookedUp.add(uid); awaitSource(release, token, cancellations); return FIXTURE_A; });
        List<AvatarView> views = new ArrayList<>();
        GridLayout grid = onMain(() -> {
            GridLayout content = new GridLayout(activity); content.setColumnCount(4); content.setRowCount(10);
            for (int i = 0; i < 40; i++) {
                AvatarView avatar = new AvatarView(activity, 48, loader.images); avatar.bindUser(Integer.toString(500003001 + i), "", "本机合成队列头像");
                content.addView(avatar, new ViewGroup.LayoutParams(dp(48), dp(48))); views.add(avatar);
            }
            return content;
        });
        show(grid); settle();
        require(onMain(() -> visibleCount(views)) == 40, "fortySyntheticAvatarsVisible");
        waitUntil(() -> resolverTaskIds(loader.images).size() == 24);
        settle(); int busy = onMain(() -> busyRetryCount(views));
        put("metadataTasksAtSaturation", resolverTaskIds(loader.images).size()); put("busyAvatarViewsScheduledForRetry", busy);
        require(resolverTaskIds(loader.images).size() == 24 && busy >= 16, "metadataQueueBoundedAt24AndBusyRetriesScheduled");
        release.countDown();
        waitUntil(() -> onMain(() -> loadedCount(views)) == 40);
        require(lookedUp.size() == 40 && resolverTaskIds(loader.images).isEmpty(), "all40AvatarsRecoverAfterQueueSaturation");
        put("recoveredVisibleSyntheticAvatars", onMain(() -> loadedCount(views)));
        require(loader.blocked.get() == 0, "queueRecoveryFixtureUsedNoNetwork");
        onMain(() -> { viewport.removeAllViews(); return null; }); loader.images.close();
    }
    private LocalLoader localLoader() throws Exception {
        LocalLoader value = new LocalLoader();
        write(value.images, "connections", new NoConnections(value.blocked));
        value.images.setLocalAvatarSource(url -> {
            String asset = BoardIconCatalog.assetFor(url);
            if (asset.isEmpty()) throw new IOException("Unknown local avatar fixture");
            value.localReads.incrementAndGet(); return getTargetContext().getAssets().open(asset);
        });
        loaders.add(value); return value;
    }
    private Set<String> resolverTaskIds(NativeImages images) throws Exception {
        Object resolver = field(images, "authorAvatarResolver");
        if (resolver == null) return Collections.emptySet();
        Object lock = field(resolver, "lock");
        synchronized (lock) {
            Object raw = field(resolver, "tasks"); Set<String> ids = new HashSet<>();
            for (Object key : ((Map<?, ?>) raw).keySet()) if (key instanceof String) ids.add((String) key);
            return ids;
        }
    }
    private void show(View child) throws Exception { onMain(() -> { viewport.removeAllViews(); viewport.scrollTo(0, 0); viewport.addView(child, new ScrollView.LayoutParams(-1, -2)); return null; }); }
    private static void awaitSource(CountDownLatch release, RequestCancellation.Token token, AtomicInteger cancellations) throws IOException {
        try {
            while (!release.await(40, TimeUnit.MILLISECONDS)) if (token.isCancelled()) { cancellations.incrementAndGet(); throw new InterruptedIOException("Local fixture cancelled"); }
            if (token.isCancelled()) { cancellations.incrementAndGet(); throw new InterruptedIOException("Local fixture cancelled"); }
        } catch (InterruptedException interrupted) { cancellations.incrementAndGet(); Thread.currentThread().interrupt(); throw new InterruptedIOException("Local fixture cancelled"); }
    }
    private static int visibleCount(List<AvatarView> views) { int total = 0; for (AvatarView view : views) if (visible(view)) total++; return total; }
    private static int visibleLoadedCount(List<AvatarView> views) throws Exception { int total = 0; for (AvatarView view : views) if (visible(view) && bitmap(view) != null) total++; return total; }
    private static int loadedCount(List<AvatarView> views) throws Exception { int total = 0; for (AvatarView view : views) if (bitmap(view) != null) total++; return total; }
    private static int busyRetryCount(List<AvatarView> views) throws Exception { int total = 0; for (AvatarView view : views) if (bitmap(view) == null && (Long) field(view, "retryAt") > SystemClock.uptimeMillis()) total++; return total; }
    private static boolean visible(View view) { Rect rectangle = new Rect(); return view.isAttachedToWindow() && view.isShown() && view.getGlobalVisibleRect(rectangle) && rectangle.width() > 0 && rectangle.height() > 0; }
    private static Set<String> visibleIds(List<AvatarView> views, int first) { Set<String> ids = new HashSet<>(); for (int i = 0; i < views.size(); i++) if (visible(views.get(i))) ids.add(Integer.toString(first + i)); return ids; }
    private static Bitmap bitmap(AvatarView view) throws Exception { return (Bitmap) field(view, "bitmap"); }
    private CountDownLatch barrier() { CountDownLatch value = new CountDownLatch(1); barriers.add(value); return value; }
    private int totalBlockedAttempts() { int total = 0; for (LocalLoader value : loaders) total += value.blocked.get(); return total; }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private long remaining(long maximum) throws CheckFailure { long value = deadline - SystemClock.elapsedRealtime(); if (value <= 0) throw new CheckFailure("AVATAR_DEADLINE"); return Math.min(value, maximum); }
    private void waitUntil(Callable<Boolean> test) throws Exception { long until = SystemClock.elapsedRealtime() + remaining(10000); while (!test.call()) { if (SystemClock.elapsedRealtime() >= until) throw new CheckFailure("AVATAR_ASYNC_DEADLINE"); SystemClock.sleep(40); } }
    private void settle() throws Exception { onMain(() -> null); SystemClock.sleep(220); onMain(() -> null); }
    private static Object field(Object owner, String name) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
    private static void write(Object owner, String name, Object value) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); }
    private static Object call(Object owner, String name) throws Exception { Method method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(owner); }
    private <T> T onMain(Callable<T> action) throws Exception {
        CountDownLatch ready = new CountDownLatch(1); Object[] value = new Object[1]; Throwable[] failure = new Throwable[1];
        main.post(() -> { try { value[0] = action.call(); } catch (Throwable unavailable) { failure[0] = unavailable; } finally { ready.countDown(); } });
        if (!ready.await(5, TimeUnit.SECONDS)) throw new CheckFailure("MAIN_THREAD_DEADLINE");
        if (failure[0] instanceof CheckFailure) throw (CheckFailure) failure[0];
        if (failure[0] instanceof Exception) throw (Exception) failure[0];
        if (failure[0] != null) throw new CheckFailure("MAIN_THREAD_CHECK_FAILED");
        @SuppressWarnings("unchecked") T typed = (T) value[0]; return typed;
    }
    private void require(boolean condition, String code) throws CheckFailure { put(code, condition); if (!condition) throw new CheckFailure(code); }
    private void put(String key, Object value) { try { report.put(key, value); } catch (Exception ignored) { /* Fixed safe projection only. */ } }
    private static final class CheckFailure extends Exception { private static final long serialVersionUID = 1L; final String code; CheckFailure(String code) { this.code = code; } }
    private static final class LocalLoader { final NativeImages images = new NativeImages(); final AtomicInteger localReads = new AtomicInteger(), blocked = new AtomicInteger(); }
    private static final class NoConnections extends AbstractSet<HttpURLConnection> {
        private final AtomicInteger attempts; NoConnections(AtomicInteger attempts) { this.attempts = attempts; }
        @Override public boolean add(HttpURLConnection connection) { attempts.incrementAndGet(); throw new IllegalStateException("Synthetic avatar network is blocked before connect"); }
        @Override public Iterator<HttpURLConnection> iterator() { return Collections.<HttpURLConnection>emptySet().iterator(); }
        @Override public int size() { return 0; }
    }
    private static final class Snapshot { ViewGroup host; View original; ViewGroup.LayoutParams params; }
    private static final class Settings {
        final String account, host, theme, day; final boolean automatic, glass, motion; final int font;
        Settings(NgaStore store) { account = store.accountId(); host = store.host(); theme = store.theme(); day = store.lastCheckInDay(); automatic = store.autoCheckIn(); glass = store.glassEnabled(); motion = store.motionEnabled(); font = store.fontSize(); }
        boolean same(NgaStore store) { return account.equals(store.accountId()) && host.equals(store.host()) && theme.equals(store.theme()) && day.equals(store.lastCheckInDay()) && automatic == store.autoCheckIn() && glass == store.glassEnabled() && motion == store.motionEnabled() && font == store.fontSize(); }
    }
}
