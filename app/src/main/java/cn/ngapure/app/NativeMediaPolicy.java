package cn.ngapure.app;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Direct streaming sources only. No cookies, app headers or browser redirects are attached. */
public final class NativeMediaPolicy {
    private NativeMediaPolicy() { }
    public static String source(String raw) {
        if (raw == null || raw.length() > 8192 || !raw.equals(raw.trim()) || raw.indexOf('\\') >= 0) return null;
        for (int i = 0; i < raw.length(); i++) if (Character.isWhitespace(raw.charAt(i)) || Character.isISOControl(raw.charAt(i))) return null;
        try {
            URI uri = new URI(raw);
            String scheme = uri.getScheme(), host = uri.getHost();
            if (scheme == null || host == null || host.isEmpty() || uri.getRawUserInfo() != null || uri.getRawFragment() != null) return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || uri.getPort() == 0 || uri.getPort() > 65535) return null;
            if (scheme.equals("http") && (host.equalsIgnoreCase("img.nga.cn") || host.equalsIgnoreCase("img4.nga.cn")
                    || host.equalsIgnoreCase("img.nga.178.com") || host.equalsIgnoreCase("img4.nga.178.com")))
                return "https" + raw.substring(raw.indexOf(':'));
            return uri.toASCIIString();
        } catch (URISyntaxException invalid) { return null; }
    }
    public static boolean direct(String url) {
        String safe = source(url); if (safe == null) return false;
        String path; try { path = new URI(safe).getPath().toLowerCase(Locale.ROOT); } catch (URISyntaxException invalid) { return false; }
        return path.matches(".*\\.(mp4|m4v|webm|3gp|mkv|m3u8|mp3|m4a|aac|ogg|oga|opus|wav|flac)$");
    }
    public static boolean audio(String url) {
        String safe = source(url); if (safe == null) return false;
        try { return new URI(safe).getPath().toLowerCase(Locale.ROOT).matches(".*\\.(mp3|m4a|aac|ogg|oga|opus|wav|flac)$"); }
        catch (URISyntaxException invalid) { return false; }
    }
    public static String originLabel(String raw) {
        String safe = source(raw); if (safe == null) return "媒体";
        try { return new URI(safe).getHost(); } catch (URISyntaxException invalid) { return "媒体"; }
    }
}
