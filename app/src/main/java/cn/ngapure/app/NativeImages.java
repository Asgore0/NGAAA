package cn.ngapure.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.ImageDecoder;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Animatable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.WeakHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

/** Bounded anonymous media transport. Never reads or forwards the account Cookie. */
public final class NativeImages implements AutoCloseable {
    public interface Callback { void loaded(Bitmap bitmap); void failed(String message); }
    public interface DrawableCallback { void loaded(Drawable drawable); void failed(String message); }
    public interface Request { void cancel(); }
    public interface LocalAvatarSource { InputStream open(String normalizedUrl) throws IOException; }
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(48));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lifecycle = new Object();
    private final Set<HttpURLConnection> connections = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, AvatarTask> avatarTasks = new HashMap<>();
    private static final int MAX_MEDIA_JOBS = 24, MAX_MEDIA_LISTENERS = 32, MAX_MEDIA_LISTENERS_PER_JOB = 32;
    private final Map<String, MediaTask> mediaTasks = new HashMap<>();
    private final Set<MediaListener> mediaListeners = new HashSet<>();
    private final Map<Bitmap,int[]> bitmapSourceDimensions = new WeakHashMap<>();
    private final Map<Drawable,int[]> drawableSourceDimensions = new WeakHashMap<>();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    /** Called with lifecycle held. An uncacheable full image must not evict the hot thumbnails. */
    private void cacheBitmap(String key, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        int bytes = bitmap.getAllocationByteCount();
        if (bytes > 0 && bytes <= cache.maxSize()) cache.put(key, bitmap);
    }
    private volatile boolean closed;
    private volatile LocalAvatarSource localAvatarSource;
    private volatile NativeMediaCache mediaCache;
    private NativeAvatarResolver authorAvatarResolver;
    private long cacheGeneration;

    public static final class CacheStats {
        public final long memoryBytes,diskBytes;
        public final int diskEntries;
        public final boolean diskAccessible;
        private CacheStats(long memory,NativeMediaCache.Stats disk){memoryBytes=memory;diskBytes=disk.bytes;diskEntries=disk.entries;diskAccessible=disk.accessible;}
    }
    public static final class CacheClearResult {
        public final long removedDiskBytes;
        public final int removedDiskEntries,failedDiskEntries;
        public final CacheStats remaining;
        private CacheClearResult(NativeMediaCache.ClearResult disk,CacheStats remaining){removedDiskBytes=disk.removedBytes;removedDiskEntries=disk.removedEntries;failedDiskEntries=disk.failedEntries;this.remaining=remaining;}
    }

    /** Worker-only: counts disposable media, excluding credentials, drafts and local collections. */
    public CacheStats cacheStats() {
        NativeMediaCache disk=mediaCache;
        NativeMediaCache.Stats stored=disk==null?new NativeMediaCache.Stats(0,0,true):disk.stats();
        synchronized(lifecycle){return new CacheStats(cache.size(),stored);}
    }
    /** Worker-only: existing views retain their valid bitmaps; older tasks cannot refill this cache. */
    public CacheClearResult clearCaches() {
        final NativeMediaCache disk;
        synchronized(lifecycle){cacheGeneration++;cache.evictAll();disk=mediaCache;}
        NativeMediaCache.ClearResult cleared=disk==null?new NativeMediaCache.ClearResult(0,0,0,new NativeMediaCache.Stats(0,0,true)):disk.clear();
        synchronized(lifecycle){
            // Also drop any fresh task that completed during the filesystem operation.
            cache.evictAll();
            return new CacheClearResult(cleared,new CacheStats(cache.size(),cleared.remaining));
        }
    }

    /** Optional app-private cache; disk work stays on media workers. Caller supplies its own cacheDir. */
    public void setMediaCacheDirectory(File directory) {
        if (directory == null) return;
        synchronized (lifecycle) { if (!closed && mediaCache == null) mediaCache = new NativeMediaCache(directory); }
    }
    /** O(1) metadata only: no disk access on the scrolling thread. */
    public boolean hasLocalMediaCopy(String url) {
        NativeMediaCache disk = mediaCache; return !closed && disk != null && disk.available(url);
    }

    /** Original logical geometry, independent from bounded decoded pixels; callers own the copy. */
    public int[] sourceDimensions(Drawable drawable) {
        if (drawable == null) return new int[]{0,0};
        synchronized (lifecycle) {
            int[] original = drawable instanceof BitmapDrawable
                    ? bitmapSourceDimensions.get(((BitmapDrawable) drawable).getBitmap())
                    : drawableSourceDimensions.get(drawable);
            if (original != null) return original.clone();
        }
        return new int[]{Math.max(0,drawable.getIntrinsicWidth()),Math.max(0,drawable.getIntrinsicHeight())};
    }

    /** Exact URL-to-bundled-asset mapping supplied by the Activity; lookup and read run on workers. */
    public void setLocalAvatarSource(LocalAvatarSource source) { localAvatarSource = source; }
    /** Public author lookup is explicitly enabled by the real Activity, never by synthetic tests. */
    public void setAuthorAvatarSource(NativeAvatarResolver.Source source) {
        NativeAvatarResolver old;
        synchronized (lifecycle) {
            old = authorAvatarResolver;
            authorAvatarResolver = closed || source == null ? null : new NativeAvatarResolver(source, action -> main.post(action));
        }
        if (old != null) old.close();
    }
    public void rememberUserAvatar(String uid, String avatar) {
        synchronized (lifecycle) { if (!closed && authorAvatarResolver != null) authorAvatarResolver.remember(uid, avatar); }
    }
    public Request loadUserAvatar(String uid, Callback callback) {
        if (callback == null) throw new IllegalArgumentException("缺少图片回调");
        UserAvatarRequest request = new UserAvatarRequest(callback);
        synchronized (lifecycle) {
            if (closed) { request.active = false; return request; }
            if (authorAvatarResolver == null || !NativeAvatarResolver.eligible(uid)) {
                main.post(() -> request.fail("未设置公开头像")); return request;
            }
            request.lookup = authorAvatarResolver.load(uid, new NativeAvatarResolver.Callback() {
                @Override public void resolved(String avatar) {
                    if (!request.active || closed) return;
                    if (avatar.isEmpty()) { request.fail("未设置公开头像"); return; }
                    request.image = loadAvatar(avatar, new Callback() {
                        @Override public void loaded(Bitmap bitmap) {
                            if (!request.active || closed) return;
                            request.active = false; callback.loaded(bitmap);
                        }
                        @Override public void failed(String message) { request.fail(message); }
                    });
                }
                @Override public void failed(boolean busy) { request.fail(busy ? "头像加载繁忙" : "头像不可用"); }
            });
        }
        return request;
    }
    private final class UserAvatarRequest implements Request {
        final Callback callback;
        volatile boolean active = true;
        NativeAvatarResolver.Request lookup;
        Request image;
        UserAvatarRequest(Callback callback) { this.callback = callback; }
        void fail(String message) { if (!closed && active) { active = false; callback.failed(message); } }
        @Override public void cancel() {
            active = false;
            if (lookup != null) lookup.cancel();
            if (image != null) image.cancel();
        }
    }

    /** Shared, deduplicated avatar work: at most 24 queued/running URLs and 32 listeners per URL. */
    public Request loadAvatar(String url, Callback callback) {
        if (callback == null) throw new IllegalArgumentException("缺少图片回调");
        AvatarListener listener = new AvatarListener(callback);
        if (!allowed(url)) { main.post(() -> { if (!closed && listener.active) callback.failed("头像不可用"); }); return listener; }
        String key = "avatar:" + url;
        synchronized (lifecycle) {
            if (closed) { listener.active = false; return listener; }
            Bitmap cached = cache.get(key);
            if (cached != null && !cached.isRecycled()) {
                main.post(() -> { if (!closed && listener.active) callback.loaded(cached); }); return listener;
            }
            AvatarTask task = avatarTasks.get(url);
            if (task == null) {
                if (avatarTasks.size() >= 24) {
                    main.post(() -> { if (!closed && listener.active) callback.failed("头像加载繁忙"); }); return listener;
                }
                task = new AvatarTask(url); avatarTasks.put(url, task);
            }
            if (task.listeners.size() >= 32) {
                main.post(() -> { if (!closed && listener.active) callback.failed("头像不可用"); }); return listener;
            }
            listener.task = task; task.listeners.add(listener);
            if (task.future == null) {
                try { task.future = workers.submit(task); }
                catch (RejectedExecutionException rejected) { avatarTasks.remove(url); listener.task = null; main.post(() -> { if (!closed && listener.active) callback.failed("头像不可用"); }); }
            }
        }
        return listener;
    }
    private final class AvatarListener implements Request {
        final Callback callback; volatile boolean active = true; AvatarTask task;
        AvatarListener(Callback callback) { this.callback = callback; }
        @Override public void cancel() {
            HttpURLConnection connection = null;
            synchronized (lifecycle) {
                active = false;
                if (task == null) return;
                task.listeners.remove(this);
                if (task.listeners.isEmpty()) {
                    avatarTasks.remove(task.url, task); connection = task.connection;
                    if (task.future != null) { task.future.cancel(true); if (task.future instanceof Runnable) workers.remove((Runnable) task.future); }
                }
                task = null;
            }
            if (connection != null) RequestCancellation.disconnectAsync(connection);
        }
    }
    private final class AvatarTask implements Runnable {
        final String url; final List<AvatarListener> listeners = new ArrayList<>(); Future<?> future;
        final long cacheEpoch;
        HttpURLConnection connection;
        AvatarTask(String url) { this.url = url;cacheEpoch=cacheGeneration; }
        @Override public void run() {
            Bitmap result = null;
            try {
                synchronized (lifecycle) { if (closed || listeners.isEmpty()) return; }
                LocalAvatarSource local = localAvatarSource;
                if (local != null) {
                    try (InputStream input = local.open(url)) {
                        if (input != null) result = decodeAvatar(readBytes(input, NativeImages.this, 2 * 1024 * 1024));
                    } catch (IOException unavailableLocalCopy) { /* Verified official URL may still load from the CDN. */ }
                }
                if (result == null) result = decodeAvatar(read(url, NativeImages.this, 2 * 1024 * 1024, null, this));
            } catch (Exception | OutOfMemoryError unavailable) { /* A real image failure retains the labeled placeholder. */ }
            final Bitmap bitmap = result;
            final List<AvatarListener> delivered;
            synchronized (lifecycle) {
                if (closed || avatarTasks.get(url) != this) return;
                avatarTasks.remove(url); delivered = new ArrayList<>(listeners); listeners.clear();
                if (cacheEpoch==cacheGeneration) cacheBitmap("avatar:" + url, bitmap);
            }
            main.post(() -> {
                if (closed) return;
                for (AvatarListener listener : delivered) {
                    listener.task = null;
                    if (!listener.active) continue;
                    if (bitmap == null) listener.callback.failed("头像不可用"); else listener.callback.loaded(bitmap);
                }
            });
        }
    }

    private static Bitmap decodeAvatar(byte[] bytes) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("头像不可用");
        int sample = 1;
        while (((long) options.outWidth + sample - 1) / sample > 256 || ((long) options.outHeight + sample - 1) / sample > 256) sample *= 2;
        options.inJustDecodeBounds = false; options.inSampleSize = sample; options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new IOException("头像不可用");
        return bitmap;
    }
    private static byte[] readBytes(InputStream input, NativeImages owner, int byteLimit) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384]; int count;
            while ((count = input.read(buffer)) != -1) {
                cancelled(owner);
                if (output.size() + count > byteLimit) throw new IOException("图片超过大小限制");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    public static boolean allowed(String value) {
        try {
            URL url = new URL(value); String host = url.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(url.getProtocol()) && url.getUserInfo() == null
                    && (url.getPort() == -1 || url.getPort() == 443)
                    && AvatarSources.allowedHost(host) && url.getRef() == null;
        } catch (Exception ignored) { return false; }
    }

    /** All callbacks, including cache hits, are delivered asynchronously on the main thread. */
    public Request load(String url, boolean full, Callback callback) {
        if (callback == null) throw new IllegalArgumentException("缺少图片回调");
        return loadInternal(url, full, false, new ResultCallback() {
            @Override public void loaded(Bitmap bitmap, Drawable drawable) { callback.loaded(bitmap); }
            @Override public void failed(boolean busy) { callback.failed(busy ? "图片加载繁忙" : "图片加载失败，点击重试"); }
        });
    }
    /** API28+ keeps animated GIF/WebP as native Drawables; API26/27 safely show the first frame. */
    public Request loadDrawable(String url, boolean full, DrawableCallback callback) {
        if (callback == null) throw new IllegalArgumentException("缺少图片回调");
        return loadInternal(url, full, true, new ResultCallback() {
            @Override public void loaded(Bitmap bitmap, Drawable drawable) { callback.loaded(drawable); }
            @Override public void failed(boolean busy) { callback.failed(busy ? "图片加载繁忙" : "图片加载失败，点击重试"); }
        });
    }
    private Request loadInternal(String url, boolean full, boolean drawableMode, ResultCallback callback) {
        MediaListener listener = new MediaListener(callback);
        String key = (full ? "full:" : "inline:") + (drawableMode ? "drawable:" : "bitmap:") + url;
        synchronized (lifecycle) {
            if (closed) { listener.active = false; return listener; }
            Bitmap cached = cache.get(key);
            if (cached != null && !cached.isRecycled()) {
                listener.complete(cached, new BitmapDrawable(null, cached), false); return listener;
            }
            if (!allowed(url)) { listener.complete(null, null, false); return listener; }
            MediaTask task = mediaTasks.get(key);
            if (mediaListeners.size() >= MAX_MEDIA_LISTENERS
                    || (task == null && mediaTasks.size() >= MAX_MEDIA_JOBS)
                    || (task != null && task.listeners.size() >= MAX_MEDIA_LISTENERS_PER_JOB)) {
                listener.complete(null, null, true); return listener;
            }
            if (task == null) {
                task = new MediaTask(url, key, full, drawableMode); mediaTasks.put(key, task);
            }
            listener.task = task; task.listeners.add(listener); mediaListeners.add(listener);
            if (task.future == null) {
                try { task.future = workers.submit(task); }
                catch (RejectedExecutionException rejected) { task.failAll(true); }
            }
        }
        return listener;
    }
    /** Cancelling one view never cancels another subscriber to the same image. */
    private final class MediaListener implements Request {
        final ResultCallback callback;
        volatile boolean active = true;
        MediaTask task;
        MediaListener(ResultCallback callback) { this.callback = callback; }
        @Override public void cancel() {
            HttpURLConnection pending = null;
            synchronized (lifecycle) {
                active = false; mediaListeners.remove(this);
                MediaTask current = task; task = null;
                if (current != null) {
                    current.listeners.remove(this);
                    if (current.listeners.isEmpty() && mediaTasks.remove(current.key, current)) {
                        current.active = false; pending = current.connection;
                        if (current.future != null) {
                            current.future.cancel(true);
                            if (current.future instanceof Runnable) workers.remove((Runnable) current.future);
                        }
                    }
                }
            }
            if (pending != null) RequestCancellation.disconnectAsync(pending);
        }
        void complete(Bitmap bitmap, Drawable drawable, boolean busy) { complete(bitmap, drawable, busy, null); }
        void complete(Bitmap bitmap, Drawable drawable, boolean busy, CountDownLatch acknowledged) {
            main.post(() -> {
                try {
                    synchronized (lifecycle) {
                        mediaListeners.remove(this);
                        if (closed || !active) return;
                        active = false; task = null;
                    }
                    if (drawable == null) callback.failed(busy); else callback.loaded(bitmap, drawable);
                } finally { if (acknowledged != null) acknowledged.countDown(); }
            });
        }
    }
    /** One bounded fetch per decode key; animated subscribers receive independent Drawables. */
    private final class MediaTask implements Runnable {
        final String url, key;
        final boolean full, drawableMode;
        final long cacheEpoch,diskEpoch;
        final NativeMediaCache disk;
        final List<MediaListener> listeners = new ArrayList<>();
        volatile boolean active = true;
        Future<?> future;
        HttpURLConnection connection;
        MediaTask(String url, String key, boolean full, boolean drawableMode) {
            this.url = url; this.key = key; this.full = full; this.drawableMode = drawableMode;
            cacheEpoch=cacheGeneration;disk=mediaCache;diskEpoch=disk==null?0:disk.generation();
        }
        @Override public void run() {
            try {
                if (closed || !active) return;
                byte[] received = disk == null ? null : disk.read(url);
                boolean local = received != null;
                if (!local) received = read(url, NativeImages.this, MAX_BYTES, this);
                if (closed || !active) return;
                Drawable first;
                try { first = decode(received); }
                catch (IOException | RuntimeException corrupt) {
                    if (!local) throw corrupt;
                    disk.remove(url,diskEpoch); received = read(url, NativeImages.this, MAX_BYTES, this);
                    if (closed || !active) return;
                    first = decode(received); local = false;
                }
                if (closed || !active) return;
                // Store verified compressed bytes before a view may release its offscreen decode.
                if (!local && disk != null) disk.put(url, received,diskEpoch);
                Bitmap bitmap = first instanceof BitmapDrawable ? ((BitmapDrawable) first).getBitmap() : null;
                synchronized (lifecycle) {
                    if (closed || !active) return;
                    if (cacheEpoch==cacheGeneration&&(disk==null||disk.isCurrent(diskEpoch))) cacheBitmap(key, bitmap);
                }
                // Keep the job and immutable bytes alive while delivering this bounded group.
                // Late subscribers can join the same response, but mutable animations never share.
                while (true) {
                    MediaListener listener;
                    synchronized (lifecycle) {
                        if (closed || !active) return;
                        if (listeners.isEmpty()) { mediaTasks.remove(key, this); active = false; return; }
                        listener = listeners.get(0);
                    }
                    Drawable drawable = bitmap != null ? new BitmapDrawable(null, bitmap)
                            : first != null ? first : decode(received);
                    first = null;
                    synchronized (lifecycle) {
                        if (closed || !active) return;
                        listeners.remove(listener); listener.task = null;
                    }
                    CountDownLatch acknowledged = bitmap == null && drawable instanceof Animatable ? new CountDownLatch(1) : null;
                    listener.complete(bitmap, drawable, false, acknowledged);
                    if (acknowledged != null) {
                        boolean more;
                        synchronized (lifecycle) { more = active && !closed && !listeners.isEmpty(); }
                        // Main only signals; it never waits. Close/last-cancel interrupt this wait.
                        if (more) acknowledged.await();
                    }
                }
            } catch (Exception | OutOfMemoryError unavailable) { failAll(false); }
        }
        private Drawable decode(byte[] received) throws IOException {
            Drawable drawable; Bitmap bitmap; int[] original = new int[2];
            if (drawableMode && Build.VERSION.SDK_INT >= 28) {
                drawable = Api28.decode(received, full, original);
                bitmap = drawable instanceof BitmapDrawable ? ((BitmapDrawable) drawable).getBitmap() : null;
            } else { bitmap = decodeBitmap(received, full, original); drawable = new BitmapDrawable(null, bitmap); }
            if (bitmap != null && !NativeImageSizing.withinBudget(bitmap.getWidth(), bitmap.getHeight(), full))
                throw new IOException("图片解码尺寸超出限制");
            synchronized (lifecycle) {
                if (!closed) {
                    if (bitmap != null) bitmapSourceDimensions.put(bitmap, original);
                    else drawableSourceDimensions.put(drawable, original);
                }
            }
            return drawable;
        }
        void failAll(boolean busy) {
            final List<MediaListener> delivered;
            synchronized (lifecycle) {
                if (closed || !active) return;
                active = false; mediaTasks.remove(key, this);
                delivered = new ArrayList<>(listeners); listeners.clear();
                for (MediaListener listener : delivered) listener.task = null;
            }
            for (MediaListener listener : delivered) listener.complete(null, null, busy);
        }
    }
    private static Bitmap decodeBitmap(byte[] bytes, boolean full, int[] original) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        original[0]=options.outWidth; original[1]=options.outHeight;
        int sample;
        try { sample = NativeImageSizing.sample(options.outWidth, options.outHeight, full); }
        catch (IllegalArgumentException error) { throw new IOException("无法识别的图片格式"); }
        options.inJustDecodeBounds = false; options.inSampleSize = sample;
        options.inScaled = false; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new IOException("无法解码图片");
        return orientJpeg(bitmap, bytes, original);
    }
    /** This bitmap has not reached the cache, metadata maps or a callback yet. */
    private static Bitmap orientJpeg(Bitmap bitmap, byte[] bytes, int[] original) {
        int orientation=jpegOrientation(bytes);
        float[] values;
        switch(orientation) {
            case 2: values=new float[]{-1,0,0,0,1,0,0,0,1}; break;
            case 3: values=new float[]{-1,0,0,0,-1,0,0,0,1}; break;
            case 4: values=new float[]{1,0,0,0,-1,0,0,0,1}; break;
            case 5: values=new float[]{0,1,0,1,0,0,0,0,1}; break;
            case 6: values=new float[]{0,-1,0,1,0,0,0,0,1}; break;
            case 7: values=new float[]{0,-1,0,-1,0,0,0,0,1}; break;
            case 8: values=new float[]{0,1,0,-1,0,0,0,0,1}; break;
            default: return bitmap;
        }
        Matrix transform=new Matrix(); transform.setValues(values);
        Bitmap oriented=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),transform,true);
        if(orientation>=5) { int width=original[0]; original[0]=original[1]; original[1]=width; }
        // Only the private unpublished decoder allocation can be recycled here.
        if(oriented!=bitmap)bitmap.recycle();
        return oriented;
    }
    @SuppressWarnings("deprecation") // Platform ExifInterface supports the API26 baseline; no extra runtime dependency.
    private static int jpegOrientation(byte[] bytes) {
        if(bytes.length<2||(bytes[0]&255)!=255||(bytes[1]&255)!=216)return 1;
        try {
            android.media.ExifInterface exif=new android.media.ExifInterface(new ByteArrayInputStream(bytes));
            return exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,android.media.ExifInterface.ORIENTATION_NORMAL);
        } catch(IOException|RuntimeException unavailableMetadata) { return 1; }
    }
    /** Original bytes for an explicit user save. No decoding or account access. */
    public static byte[] bytes(String url) throws IOException { return read(url, null); }
    private static byte[] read(String url, NativeImages owner) throws IOException {
        return read(url, owner, MAX_BYTES);
    }
    private static byte[] read(String url, NativeImages owner, int byteLimit) throws IOException {
        return read(url, owner, byteLimit, null);
    }
    private static byte[] read(String url, NativeImages owner, int byteLimit, NativeImages.MediaTask task) throws IOException {
        return read(url, owner, byteLimit, task, null);
    }
    private static byte[] read(String url, NativeImages owner, int byteLimit, NativeImages.MediaTask task, NativeImages.AvatarTask avatarTask) throws IOException {
        String current = url;
        for (int redirects = 0; redirects <= 3; redirects++) {
            cancelled(owner);
            if (!allowed(current)) throw new IOException("不支持的图片来源");
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            RequestCancellation.Registration cancellation = null;
            try {
                cancellation = RequestCancellation.track(connection);
                if (owner != null) synchronized (owner.lifecycle) {
                    cancelled(owner);
                    if (task != null) {
                        if (!task.active) throw new IOException("图片加载已取消");
                        task.connection = connection;
                    }
                    if (avatarTask != null) {
                        if (owner.avatarTasks.get(avatarTask.url) != avatarTask || avatarTask.listeners.isEmpty()) throw new IOException("头像加载已取消");
                        avatarTask.connection = connection;
                    }
                    owner.connections.add(connection);
                }
                connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
                connection.setRequestProperty("User-Agent", "NgaPure/0.10.0 Android"); connection.setRequestProperty("Accept-Encoding", "identity");
                cancelled(owner);
                int status = connection.getResponseCode();
                cancelled(owner);
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("无效图片跳转");
                    current = new URL(new URL(current), location).toString(); continue;
                }
                if (status != 200) throw new IOException("图片服务器返回 " + status);
                if (connection.getContentLengthLong() > byteLimit) throw new IOException("图片超过大小限制");
                cancelled(owner);
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16384];
                    while (true) {
                        cancelled(owner); int count = input.read(buffer); cancelled(owner);
                        if (count == -1) break;
                        if (output.size() + count > byteLimit) throw new IOException("图片超过大小限制");
                        output.write(buffer, 0, count);
                    }
                    return output.toByteArray();
                }
            } finally {
                if (cancellation != null) cancellation.close();
                if (owner != null) synchronized (owner.lifecycle) {
                    owner.connections.remove(connection);
                    if (task != null && task.connection == connection) task.connection = null;
                    if (avatarTask != null && avatarTask.connection == connection) avatarTask.connection = null;
                }
                connection.disconnect();
            }
        }
        throw new IOException("图片跳转过多");
    }
    private static void cancelled(NativeImages owner) throws IOException {
        RequestCancellation.check();
        if (owner != null && owner.closed) throw new IOException("图片加载已取消");
    }
    /** Drops only loader-owned references; bitmaps already displayed by views remain valid. */
    public void trimMemory(int level) {
        synchronized (lifecycle) {
            if (level >= 40) cache.evictAll();
            else if (level >= 10) cache.trimToSize(cache.maxSize() / 2);
        }
    }
    /** Fixed counters only; media addresses, account data and content never enter diagnostics. */
    public String state() {
        synchronized (lifecycle) {
            return "queue=" + workers.getQueue().size() + " running=" + workers.getActiveCount()
                    + " media=" + mediaTasks.size() + " mediaListeners=" + mediaListeners.size() + " avatars=" + avatarTasks.size()
                    + " cacheBytes=" + cache.size() + " closed=" + closed
                    + (authorAvatarResolver == null ? "" : " " + authorAvatarResolver.state());
        }
    }
    @Override public void close() {
        synchronized (lifecycle) {
            if (closed) return; closed = true; cache.evictAll();
            bitmapSourceDimensions.clear(); drawableSourceDimensions.clear();
            for (AvatarTask task : avatarTasks.values()) for (AvatarListener listener : task.listeners) listener.active = false;
            avatarTasks.clear();
            for (MediaTask task : mediaTasks.values()) task.active = false;
            for (MediaListener listener : mediaListeners) listener.active = false;
            mediaTasks.clear(); mediaListeners.clear();
        }
        if (authorAvatarResolver != null) authorAvatarResolver.close();
        main.removeCallbacksAndMessages(null); workers.shutdownNow();
        for (HttpURLConnection connection : connections) RequestCancellation.disconnectAsync(connection);
        connections.clear();
    }
    private interface ResultCallback { void loaded(Bitmap bitmap, Drawable drawable); void failed(boolean busy); }
    @android.annotation.TargetApi(28)
    private static final class Api28 {
        private Api28() { }
        static Drawable decode(byte[] bytes, boolean full, int[] original) throws IOException {
            return ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), (decoder, info, source) -> {
                original[0]=info.getSize().getWidth(); original[1]=info.getSize().getHeight();
                decoder.setTargetSampleSize(NativeImageSizing.sample(original[0], original[1], full));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }
    }
}

/** Pure Java decoder sizing, separate from Android for reproducible boundary checks. */
final class NativeImageSizing {
    private static final int MAX_EDGE = 8192;
    private NativeImageSizing() { }
    static int sample(int width, int height, boolean full) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid image dimensions");
        long budget = full ? 4000000L : 800000L; int sample = 1;
        while (true) {
            long w = ((long) width + sample - 1) / sample, h = ((long) height + sample - 1) / sample;
            if (w <= MAX_EDGE && h <= MAX_EDGE && w * h <= budget) return sample;
            if (sample >= 1 << 30) throw new IllegalArgumentException("Image dimensions too large"); sample *= 2;
        }
    }
    static boolean withinBudget(int width, int height, boolean full) {
        return width > 0 && height > 0 && width <= MAX_EDGE && height <= MAX_EDGE
                && (long) width * height <= (full ? 4000000L : 800000L);
    }
}
