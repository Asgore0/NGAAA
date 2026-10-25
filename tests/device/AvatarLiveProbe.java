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
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import cn.ngapure.app.AvatarSources;
import cn.ngapure.app.AvatarView;
import cn.ngapure.app.NativeAvatarResolver;
import cn.ngapure.app.NativeImages;
import cn.ngapure.app.NativeLoginView;
import cn.ngapure.app.NgaApi;
import cn.ngapure.app.NgaStore;
import cn.ngapure.app.RequestCancellation;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Read-only verification of the Activity's real positive-author avatar source. A
 * bounded public thread page selects at most three authors whose personal images
 * are already present in public metadata. Fresh native views deliberately receive
 * empty avatar URLs, requiring real author lookup and real anonymous CDN decode.
 * No fake source, local image fixture, profile persistence, screenshot or remote
 * write is used. Reports contain only fixed codes, booleans and counts, never an
 * author ID/name, image URL, session field or exception message.
 */
public final class AvatarLiveProbe extends Instrumentation {
    private final JSONObject report = new JSONObject();
    private final Handler main = new Handler(Looper.getMainLooper());
    private long deadline;
    private Activity activity;
    private Snapshot saved;
    private NativeImages images;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        deadline = SystemClock.elapsedRealtime() + 120000;
        RequestCancellation.Token readToken = new RequestCancellation.Token();
        ExecutorService reader = Executors.newSingleThreadExecutor(action -> {
            Thread worker = new Thread(action, "AvatarLivePublicThread"); worker.setDaemon(true); return worker;
        });
        Settings before = null;
        try {
            put("schema", 1); put("knownMetadataCount", 0); put("selectedPublicAuthors", 0); put("resolvedPersonalAvatars", 0);
            put("defaultNotCounted", true); put("noIDsInOutput", true); put("noScreenshots", true); put("noRemoteWrites", true);
            NgaStore store = new NgaStore(getTargetContext());
            require(!store.autoCheckIn() || (store.loggedIn() && NgaStore.today().equals(store.lastCheckInDay())), "onOpenSignInGuardPassed");
            require(!NativeLoginView.hasPendingAuthorization(getTargetContext(), store.host()), "pendingAuthorizationGuardPassed");
            before = new Settings(store);
            activity = startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.ngapure.app", "cn.ngapure.app.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            settle();
            NativeAvatarResolver.Source source = onMain(() -> {
                NativeImages realImages = (NativeImages) field(activity, "nativeImages");
                Object resolver = field(realImages, "authorAvatarResolver");
                if (resolver == null) throw new CheckFailure("MAIN_AUTHOR_SOURCE_MISSING");
                Object raw = field(resolver, "source");
                if (!(raw instanceof NativeAvatarResolver.Source)) throw new CheckFailure("MAIN_AUTHOR_SOURCE_MISSING");
                return (NativeAvatarResolver.Source) raw;
            });
            put("realMainAuthorSourceUsed", true);
            Future<Selection> request = reader.submit(() -> {
                RequestCancellation.Scope scope = RequestCancellation.bind(readToken);
                try { return select(new NgaApi(getTargetContext())); }
                finally { scope.close(); }
            });
            Selection selected = request.get(remaining(40000), TimeUnit.MILLISECONDS);
            put("knownMetadataCount", selected.knownMetadataCount); put("selectedPublicAuthors", selected.authors.size());
            require(!selected.authors.isEmpty() && selected.authors.size() <= 3, "publicAuthorsWithRealMetadataSelected");
            images = new NativeImages(); images.setAuthorAvatarSource(source);
            require(field(images, "localAvatarSource") == null, "noLocalAvatarMock");
            Object freshResolver = field(images, "authorAvatarResolver");
            require(field(freshResolver, "source") == source, "sameActivitySourceWithoutWrapper");
            require(((Map<?, ?>) field(freshResolver, "cache")).isEmpty()
                    && ((LruCache<?, ?>) field(images, "cache")).size() == 0, "freshMetadataAndImageCaches");
            List<AvatarView> views = onMain(() -> replaceRoot(selected));
            settle();
            require(onMain(() -> allVisible(views)), "allSelectedAuthorViewsVisible");
            long until = SystemClock.elapsedRealtime() + remaining(45000);
            Sample sample;
            do {
                sample = onMain(() -> sample(views, selected));
                publish(sample);
                put("resolvedCountEqualsSelectedAuthors", sample.personal == selected.authors.size());
                if (sample.personal == selected.authors.size()) break;
                if (SystemClock.elapsedRealtime() >= until) throw new CheckFailure("LIVE_PERSONAL_AVATAR_TIMEOUT");
                SystemClock.sleep(100);
            } while (true);
            require(sample.personal == selected.authors.size() && sample.personal > 0, "resolvedCountEqualsSelectedAuthors");
            require(before.same(store), "accountAndSettingsUnchanged");
            put("passed", true); put("failure", "NONE");
        } catch (CheckFailure failed) {
            put("passed", false); put("failure", failed.code);
        } catch (java.util.concurrent.TimeoutException timeout) {
            put("passed", false); put("failure", "LIVE_READ_DEADLINE");
        } catch (Exception | AssertionError unavailable) {
            put("passed", false); put("failure", "AVATAR_LIVE_UNAVAILABLE");
        } finally {
            readToken.cancel(); reader.shutdownNow();
            try {
                if (saved != null) onMain(() -> { saved.host.removeAllViews(); saved.host.addView(saved.original, saved.params); return null; });
                put("originalRootRestored", saved != null);
            } catch (Exception unavailable) { put("originalRootRestored", false); put("passed", false); put("failure", "ROOT_RESTORE_FAILED"); }
            if (images != null) images.close();
            if (before != null) {
                boolean same = before.same(new NgaStore(getTargetContext()));
                put("accountAndSettingsUnchangedAfterRestore", same);
                if (!same) { put("passed", false); put("failure", "SETTINGS_CHANGED"); }
            }
        }
        Bundle output = new Bundle(); output.putString("safe_avatar_live_report", report.toString()); finish(Activity.RESULT_OK, output);
    }
    private static Selection select(NgaApi api) throws Exception {
        NgaApi.ThreadPage page = api.thread("47717100", 2);
        Selection selected = new Selection(); Set<String> seen = new HashSet<>();
        for (NgaApi.Post post : page.posts) {
            if (!NativeAvatarResolver.eligible(post.authorId) || post.author.startsWith("匿名") || !seen.add(post.authorId)) continue;
            String avatar = AvatarSources.normalize(post.avatar);
            if (avatar.isEmpty()) continue;
            selected.knownMetadataCount++;
            // /avatars/ contains uploaded personal images. Generic /face/ and /f/ defaults are excluded.
            if (selected.authors.size() < 3 && personalAvatar(avatar)) selected.authors.add(new Author(post.authorId, avatar));
        }
        return selected;
    }
    private List<AvatarView> replaceRoot(Selection selected) throws Exception {
        saved = new Snapshot(); saved.host = (ViewGroup) activity.findViewById(android.R.id.content);
        saved.original = saved.host.getChildAt(0); saved.params = saved.original.getLayoutParams();
        call(activity, "cancelHomeNavigation"); call(field(activity, "pageReads"), "cancelAll");
        write(activity, "generation", (Integer) field(activity, "generation") + 1);
        LinearLayout column = new LinearLayout(activity); column.setOrientation(LinearLayout.VERTICAL); column.setPadding(dp(24), dp(32), dp(24), dp(24));
        List<AvatarView> views = new ArrayList<>();
        for (Author author : selected.authors) {
            AvatarView view = new AvatarView(activity, 64, images);
            view.bindUser(author.uid, "", "公开作者头像验证");
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(64), dp(64)); params.bottomMargin = dp(24);
            column.addView(view, params); views.add(view);
        }
        saved.host.removeAllViews(); saved.host.addView(column, new ViewGroup.LayoutParams(-1, -1)); return views;
    }
    private Sample sample(List<AvatarView> views, Selection selected) throws Exception {
        Sample value = new Sample();
        Object resolver = field(images, "authorAvatarResolver"); Object resolverLock = field(resolver, "lock");
        Object imageLock = field(images, "lifecycle");
        for (int i = 0; i < views.size(); i++) {
            AvatarView view = views.get(i); Bitmap bitmap = (Bitmap) field(view, "bitmap");
            if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) { value.placeholders++; continue; }
            value.decoded++;
            Author author = selected.authors.get(i); String resolved = "";
            synchronized (resolverLock) {
                Object entry = ((Map<?, ?>) field(resolver, "cache")).get(author.uid);
                if (entry != null) {
                    Object raw = field(entry, "avatar"); if (raw instanceof String) resolved = AvatarSources.normalize((String) raw);
                }
            }
            boolean actualImageBound = false;
            synchronized (imageLock) {
                Object cached = imageCache(images).get("avatar:" + resolved);
                actualImageBound = cached == bitmap;
            }
            if (personalAvatar(resolved) && samePublicImage(author.avatar, resolved) && actualImageBound) value.personal++;
            else value.defaultsOrMismatchesRejected++;
        }
        return value;
    }
    private void publish(Sample sample) {
        put("decodedBitmapCount", sample.decoded); put("resolvedPersonalAvatars", sample.personal);
        put("remainingPlaceholderCount", sample.placeholders); put("defaultsOrMismatchesNotCounted", sample.defaultsOrMismatchesRejected);
    }
    private static boolean personalAvatar(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            String path = new URI(value).getPath();
            if (path == null || !path.startsWith("/avatars/") || path.endsWith("/")) return false;
            String file = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            return !file.startsWith("default") && !file.startsWith("anonymous") && !file.startsWith("noavatar")
                    && !file.matches("0+\\.(?:png|jpe?g|gif|webp)");
        } catch (Exception invalid) { return false; }
    }
    private static boolean samePublicImage(String expected, String actual) {
        try { URI left = new URI(expected), right = new URI(actual); return left.getPath().equals(right.getPath()); }
        catch (Exception invalid) { return false; }
    }
    private static boolean allVisible(List<AvatarView> views) {
        for (AvatarView view : views) { Rect rect = new Rect(); if (!view.isAttachedToWindow() || !view.isShown() || !view.getGlobalVisibleRect(rect) || rect.isEmpty()) return false; }
        return !views.isEmpty();
    }
    private long remaining(long maximum) throws CheckFailure { long value = deadline - SystemClock.elapsedRealtime(); if (value <= 0) throw new CheckFailure("AVATAR_LIVE_DEADLINE"); return Math.min(value, maximum); }
    private void settle() throws Exception { onMain(() -> null); SystemClock.sleep(220); onMain(() -> null); }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    @SuppressWarnings("unchecked") private static LruCache<String, Bitmap> imageCache(NativeImages images) throws Exception { return (LruCache<String, Bitmap>) field(images, "cache"); }
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
    private void put(String key, Object value) { try { report.put(key, value); } catch (Exception ignored) { /* Safe fixed projections only. */ } }
    private static final class CheckFailure extends Exception { private static final long serialVersionUID = 1L; final String code; CheckFailure(String code) { this.code = code; } }
    private static final class Selection { int knownMetadataCount; final List<Author> authors = new ArrayList<>(); }
    private static final class Author { final String uid, avatar; Author(String uid, String avatar) { this.uid = uid; this.avatar = avatar; } }
    private static final class Sample { int decoded, personal, placeholders, defaultsOrMismatchesRejected; }
    private static final class Snapshot { ViewGroup host; View original; ViewGroup.LayoutParams params; }
    private static final class Settings {
        final String account, host, theme, day; final boolean automatic, glass, motion; final int font;
        Settings(NgaStore store) { account = store.accountId(); host = store.host(); theme = store.theme(); day = store.lastCheckInDay(); automatic = store.autoCheckIn(); glass = store.glassEnabled(); motion = store.motionEnabled(); font = store.fontSize(); }
        boolean same(NgaStore store) { return account.equals(store.accountId()) && host.equals(store.host()) && theme.equals(store.theme()) && day.equals(store.lastCheckInDay()) && automatic == store.autoCheckIn() && glass == store.glassEnabled() && motion == store.motionEnabled() && font == store.fontSize(); }
    }
}
