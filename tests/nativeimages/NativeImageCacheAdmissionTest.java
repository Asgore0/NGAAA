package cn.ngapure.app;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.util.LruCache;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Loader integration with controlled codecs; the byte-accounted LRU has real eviction semantics. */
public final class NativeImageCacheAdmissionTest {
    private static final int LIMIT = 8 * 1024 * 1024;
    private static final Map<String,Integer> markers = new HashMap<>();
    private static final Map<String,AtomicInteger> fetches = new ConcurrentHashMap<>();
    private static int assertions;
    private static void check(boolean condition, String label) {
        assertions++; if (!condition) throw new AssertionError(label);
    }
    private static final class Result implements NativeImages.Callback, NativeImages.DrawableCallback {
        Bitmap bitmap; int completed;
        @Override public void loaded(Bitmap value) { bitmap = value; completed++; }
        @Override public void loaded(Drawable value) { loaded(((BitmapDrawable) value).getBitmap()); }
        @Override public void failed(String value) { throw new AssertionError(value); }
    }
    private static void await(Result result, int expected) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (result.completed < expected && System.nanoTime() < end) { Handler.drain(); Thread.sleep(2); }
        check(result.completed == expected, "asynchronous callback completed");
    }
    private static void drainTasks(NativeImages images) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!images.state().contains("media=0 ") && System.nanoTime() < end) Thread.sleep(2);
        check(images.state().contains("media=0 "), "shared task exits before testing completed-cache reuse");
    }
    private static String url(String key, int marker) {
        String address = "https://img.nga.cn/attachments/cache-admission/" + key;
        markers.put(address, marker); fetches.put(address, new AtomicInteger()); return address;
    }
    private static void stubEvictionContract() {
        LruCache<String,Integer> lru = new LruCache<String,Integer>(8) {
            @Override protected int sizeOf(String key, Integer value) { return value; }
        };
        lru.put("a", 3); lru.put("b", 3); check(lru.size() == 6, "weighted cache size");
        check(lru.get("a") == 3, "access refreshes recency"); lru.put("c", 3);
        check(lru.get("b") == null && lru.get("a") != null && lru.get("c") != null, "least recently accessed evicted");
        lru.put("a", 2); check(lru.size() == 5, "replacement subtracts old allocation");
        lru.trimToSize(2); check(lru.get("c") == null && lru.get("a") == 2, "explicit trim evicts by access order");
        lru.put("too-large", 9); check(lru.size() == 0 && lru.get("a") == null, "raw Android oversized put flushes older entries and itself");
        lru.put("zero", 0); lru.evictAll(); check(lru.get("zero") == null, "evictAll also removes zero-size entries");
    }
    private static void hotEntriesSurviveFullImage(int sdk, boolean drawableMode) throws Exception {
        Build.VERSION.SDK_INT = sdk;
        String suffix = sdk + "-" + drawableMode;
        String avatar = url(suffix + "-avatar", 1), inline = url(suffix + "-inline", 6);
        String full = url(suffix + "-full", 5), small = url(suffix + "-small-full", 6);
        NativeImages images = new NativeImages(); Result a = new Result(), i = new Result(), large = new Result(), fitting = new Result();
        try {
            images.loadAvatar(avatar, a); await(a, 1); Bitmap avatarPixels = a.bitmap;
            images.loadDrawable(inline, false, i); await(i, 1); Bitmap inlinePixels = i.bitmap;
            long hotBytes = images.cacheStats().memoryBytes;
            if (drawableMode) images.loadDrawable(full, true, large); else images.load(full, true, large);
            await(large, 1); Bitmap fullPixels = large.bitmap;
            check(fullPixels.getAllocationByteCount() > LIMIT && !fullPixels.isRecycled(), "oversized full image still delivered valid");
            check(images.cacheStats().memoryBytes == hotBytes, "oversized admission preserves exact hot-cache bytes");
            images.loadAvatar(avatar, a); images.loadDrawable(inline, false, i); await(a, 2); await(i, 2);
            check(a.bitmap == avatarPixels && i.bitmap == inlinePixels, "return uses the same already decoded avatar and inline pixels");
            check(fetches.get(avatar).get() == 1 && fetches.get(inline).get() == 1, "return incurs no extra transport");
            drainTasks(images);
            if (drawableMode) images.loadDrawable(full, true, large); else images.load(full, true, large);
            await(large, 2);
            check(large.bitmap != fullPixels && fetches.get(full).get() == 2, "uncacheable full image was not retained");
            images.loadDrawable(small, true, fitting); await(fitting, 1); Bitmap smallPixels = fitting.bitmap;
            images.loadDrawable(small, true, fitting); await(fitting, 2);
            check(fitting.bitmap == smallPixels && fetches.get(small).get() == 1, "fitting full image still caches normally");
            NativeImages.Request cancelled = images.loadDrawable(inline, false, i); cancelled.cancel(); Handler.drain();
            check(i.completed == 2, "cancelled cached callback remains suppressed");
            images.trimMemory(40); check(!fullPixels.isRecycled() && !inlinePixels.isRecycled(), "trimming never recycles displayed pixels");
        } finally { images.close(); }
    }
    @SuppressWarnings("unchecked")
    private static void admissionBoundaries() throws Exception {
        NativeImages images = new NativeImages();
        Field field = NativeImages.class.getDeclaredField("cache"); field.setAccessible(true);
        LruCache<String,Bitmap> cache = (LruCache<String,Bitmap>) field.get(images);
        Method admit = NativeImages.class.getDeclaredMethod("cacheBitmap", String.class, Bitmap.class); admit.setAccessible(true);
        try {
            Bitmap hot = new Bitmap(10, 10); admit.invoke(images, "hot", hot);
            Bitmap recycled = new Bitmap(10, 10); recycled.recycle();
            admit.invoke(images, "null", null); admit.invoke(images, "recycled", recycled);
            admit.invoke(images, "zero", new Bitmap(0, 0));
            check(cache.size() == hot.getAllocationByteCount() && cache.get("hot") == hot, "null, recycled and empty allocations do not disturb cache");
            Bitmap reserved = new Bitmap(1, 1) { @Override public int getAllocationByteCount() { return LIMIT + 4; } };
            admit.invoke(images, "reserved", reserved);
            check(cache.get("reserved") == null && cache.get("hot") == hot, "admission uses allocation rather than logical dimensions");
            Bitmap exact = new Bitmap(2048, 1024); admit.invoke(images, "exact", exact);
            check(cache.size() == LIMIT && cache.get("exact") == exact, "allocation equal to entire budget is cacheable");
        } finally { images.close(); }
    }
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> !"https".equals(protocol) ? null : new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                String address = url.toString(); Integer marker = markers.get(address);
                if (marker == null) throw new AssertionError("Unexpected synthetic request");
                fetches.get(address).incrementAndGet();
                return new HttpURLConnection(url) {
                    @Override public int getResponseCode() { return 200; }
                    @Override public long getContentLengthLong() { return 1; }
                    @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[]{marker.byteValue()}); }
                    @Override public void connect() { }
                    @Override public void disconnect() { }
                    @Override public boolean usingProxy() { return false; }
                };
            }
        });
        stubEvictionContract();
        hotEntriesSurviveFullImage(27, false); hotEntriesSurviveFullImage(27, true); hotEntriesSurviveFullImage(35, true);
        admissionBoundaries();
        System.out.println("PASS image cache admission: " + assertions + " checks; real production loader, weighted LRU model, controlled codecs, no network/account");
    }
}
