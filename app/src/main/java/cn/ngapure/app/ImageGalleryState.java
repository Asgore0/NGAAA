package cn.ngapure.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Value-only bounded gallery state. Credentials, pixels and Activity references never enter it. */
public final class ImageGalleryState {
    public static final int MAX_ENCODED_CHARS = 96 * 1024;
    public final List<String> urls;
    public final int index, sourceTotal;
    public final boolean totalKnown, truncated;
    private final float[] viewport;
    private ImageGalleryState(List<String> urls, int index, int sourceTotal, boolean known, boolean truncated, float[] viewport) {
        this.urls = Collections.unmodifiableList(new ArrayList<>(urls)); this.index = index;
        this.sourceTotal = sourceTotal; this.totalKnown = known; this.truncated = truncated;
        this.viewport = validViewport(viewport) ? viewport.clone() : null;
    }
    public static ImageGalleryState create(List<String> urls, int index, int sourceTotal, boolean truncated) {
        return create(urls, index, sourceTotal, true, truncated);
    }
    public static ImageGalleryState create(List<String> urls, int index, int sourceTotal, boolean known, boolean truncated) {
        if (urls == null || urls.isEmpty() || urls.size() > TopicPreviewImages.MAX_GALLERY_IMAGES || index < 0 || index >= urls.size())
            throw new IllegalArgumentException("无效图片集");
        LinkedHashSet<String> verified = new LinkedHashSet<>(); int chars = 0;
        for (String raw : urls) {
            String url = TopicPreviewImages.normalize(raw);
            if (url.isEmpty() || !url.equals(raw) || !verified.add(url) || (chars += url.length()) > TopicPreviewImages.MAX_GALLERY_URL_CHARS)
                throw new IllegalArgumentException("无效图片集");
        }
        if (sourceTotal < urls.size() || sourceTotal > 100000 || (!truncated && (!known || sourceTotal != urls.size())) || (known && sourceTotal > urls.size() && !truncated))
            throw new IllegalArgumentException("无效图片总数");
        return new ImageGalleryState(urls, index, sourceTotal, known, truncated, null);
    }
    public String currentUrl() { return urls.get(index); }
    public float[] viewport() { return viewport == null ? null : viewport.clone(); }
    public ImageGalleryState withIndex(int value) {
        if (value < 0 || value >= urls.size()) throw new IllegalArgumentException("无效图片序号");
        return value == index ? this : new ImageGalleryState(urls, value, sourceTotal, totalKnown, truncated, null);
    }
    public ImageGalleryState withViewport(float[] value) {
        return new ImageGalleryState(urls, index, sourceTotal, totalKnown, truncated, value);
    }
    public String counterLabel() { return (index + 1) + " / " + urls.size(); }
    public String limitLabel() {
        return truncated ? "仅展示前 " + urls.size() + " 张 · 共" + (totalKnown ? " " : "至少 ") + sourceTotal + " 张" : "";
    }
    private static boolean validViewport(float[] value) {
        if (value == null || value.length != 3) return false;
        for (float item : value) if (Float.isNaN(item) || Float.isInfinite(item)) return false;
        return value[0] >= 1 && value[0] <= 6 && value[1] >= 0 && value[1] <= 1 && value[2] >= 0 && value[2] <= 1;
    }
    public String encode() {
        try {
            JSONObject object = new JSONObject(); object.put("v",1).put("index",index).put("total",sourceTotal).put("known",totalKnown).put("limited",truncated);
            JSONArray images = new JSONArray(); for (String url : urls) images.put(url); object.put("urls",images);
            if (viewport != null) { JSONArray view = new JSONArray(); for (float item : viewport) view.put((double)item); object.put("viewport",view); }
            String encoded = object.toString();
            return encoded.length() <= MAX_ENCODED_CHARS ? encoded : "";
        } catch (Exception invalid) { return ""; }
    }
    public static ImageGalleryState decode(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > MAX_ENCODED_CHARS) return null;
        try {
            JSONObject object = new JSONObject(encoded);
            if (!Integer.valueOf(1).equals(object.get("v")) || !(object.get("index") instanceof Integer) || !(object.get("total") instanceof Integer)
                    || !(object.get("known") instanceof Boolean) || !(object.get("limited") instanceof Boolean)) return null;
            JSONArray images = object.getJSONArray("urls"); if (images.length() < 1 || images.length() > TopicPreviewImages.MAX_GALLERY_IMAGES) return null;
            ArrayList<String> urls = new ArrayList<>();
            for (int i = 0; i < images.length(); i++) { Object item = images.get(i); if (!(item instanceof String)) return null; urls.add((String)item); }
            ImageGalleryState result = create(urls, object.getInt("index"), object.getInt("total"), object.getBoolean("known"), object.getBoolean("limited"));
            if (object.has("viewport")) {
                JSONArray view = object.getJSONArray("viewport"); if (view.length() != 3) return null;
                float[] values = new float[3]; for (int i = 0; i < 3; i++) { if (!(view.get(i) instanceof Number)) return null; values[i] = (float)view.getDouble(i); }
                if (!validViewport(values)) return null; result = result.withViewport(values);
            }
            return result;
        } catch (Exception invalid) { return null; }
    }
}
