package android.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** The tested Android contract: access-order eviction and sizeOf accounting, including oversized puts. */
public class LruCache<K,V> {
    private final LinkedHashMap<K,V> entries = new LinkedHashMap<>(0, 0.75f, true);
    private final int maximum;
    private int size;
    public LruCache(int maximum) {
        if (maximum <= 0) throw new IllegalArgumentException("maxSize <= 0");
        this.maximum = maximum;
    }
    protected int sizeOf(K key, V value) { return 1; }
    private int checkedSize(K key, V value) {
        int bytes = sizeOf(key, value);
        if (bytes < 0) throw new IllegalStateException("Negative size");
        return bytes;
    }
    public synchronized V get(K key) {
        if (key == null) throw new NullPointerException("key == null");
        return entries.get(key);
    }
    public V put(K key, V value) {
        if (key == null || value == null) throw new NullPointerException("key == null || value == null");
        V previous;
        synchronized (this) {
            size += checkedSize(key, value);
            previous = entries.put(key, value);
            if (previous != null) size -= checkedSize(key, previous);
        }
        trimToSize(maximum);
        return previous;
    }
    public synchronized void trimToSize(int target) {
        while (true) {
            if (size < 0 || (entries.isEmpty() && size != 0)) throw new IllegalStateException("Inconsistent size");
            if (size <= target || entries.isEmpty()) return;
            Map.Entry<K,V> eldest = entries.entrySet().iterator().next();
            size -= checkedSize(eldest.getKey(), eldest.getValue());
            entries.remove(eldest.getKey());
        }
    }
    public void evictAll() { trimToSize(-1); }
    public int maxSize() { return maximum; }
    public synchronized int size() { return size; }
}
