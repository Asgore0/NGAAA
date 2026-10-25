package cn.ngapure.app;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Public NGA avatar metadata only. Never derives an image from an account identifier alone. */
public final class AvatarSources {
    private static final Pattern SHORT = Pattern.compile("\\.a/([0-9]{1,10})_([0-9]{1,16})\\.(jpg|jpeg|png|gif|webp\\.jpg|webp)(\\?[0-9]{1,16})?", Pattern.CASE_INSENSITIVE);
    private AvatarSources() { }

    /** Selects one real candidate from official string / multi-avatar metadata, without networking. */
    public static String normalize(String raw) {
        if (raw == null || raw.length() > 16384) return "";
        String value = raw.trim();
        if (value.startsWith("{") || value.startsWith("[")) {
            try { return candidate(new JSONTokener(value).nextValue(), 0); }
            catch (Exception invalid) { return ""; }
        }
        return delimited(value);
    }
    private static String candidate(Object value, int depth) {
        if (depth > 3) return "";
        if (value instanceof String) return delimited((String) value);
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (int i = 0; i < 8; i++) {
                String result = candidate(object.opt(Integer.toString(i)), depth + 1);
                if (!result.isEmpty()) return result;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < Math.min(8, array.length()); i++) {
                String result = candidate(array.opt(i), depth + 1);
                if (!result.isEmpty()) return result;
            }
        }
        return "";
    }
    private static String delimited(String value) {
        // Official toArray() accepts both literal and URL-encoded pipe separators and a t2 marker.
        String[] values = value.split("(?i)[|]|%7c", 9);
        for (int i = 0; i < Math.min(8, values.length); i++) {
            String result = single(values[i]);
            if (!result.isEmpty()) return result;
        }
        return "";
    }
    /** Only the official image host family. A suffix match would admit attacker-controlled hosts. */
    public static boolean allowedHost(String host) {
        return host != null && host.toLowerCase(Locale.ROOT).matches("img[0-9]{0,3}\\.(?:nga\\.cn|nga\\.178\\.com|ngacn\\.cc)");
    }
    private static String single(String value) {
        value = value.trim().replace("&amp;", "&");
        if (value.isEmpty() || value.length() > 2048 || value.matches("(?s).*[\\s\\p{Cntrl}\\\\].*")) return "";
        Matcher shortForm = SHORT.matcher(value);
        if (shortForm.matches()) {
            try {
                long uid = Long.parseLong(shortForm.group(1));
                if (uid <= 0 || uid > Integer.MAX_VALUE) return "";
                String hex = String.format(Locale.ROOT, "%09x", uid);
                value = "https://img.nga.cn/avatars/2002/" + hex.substring(6, 9) + "/" + hex.substring(3, 6)
                        + "/" + hex.substring(0, 3) + "/" + shortForm.group(1) + "_" + shortForm.group(2)
                        + "." + shortForm.group(3) + (shortForm.group(4) == null ? "" : shortForm.group(4));
            } catch (NumberFormatException invalid) { return ""; }
        } else if (value.startsWith("//")) value = "https:" + value;
        else if (value.startsWith("http://")) value = "https://" + value.substring(7);
        else if (!value.startsWith("https://")) {
            // Official commonui.avatarUrl resolves legacy filenames under /ngabbs/face/.
            if (!value.matches("[A-Za-z0-9_-]{1,128}\\.(?i:jpg|jpeg|png|gif|webp)")) return "";
            value = "https://img4.nga.cn/ngabbs/face/" + value;
        }
        try {
            URI uri = new URI(value);
            String path = uri.getPath();
            if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getHost() == null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || path == null || path.contains("\\") || path.matches("(?:^|.*/)\\.\\.(?:/.*|$)")) return "";
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (!allowedHost(host)) return "";
            return uri.toASCIIString();
        } catch (Exception invalid) { return ""; }
    }
}
