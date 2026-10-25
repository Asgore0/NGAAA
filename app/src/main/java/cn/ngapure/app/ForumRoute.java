package cn.ngapure.app;

/** An ordinary board and a topic collection have separate stable identities. No I/O. */
public final class ForumRoute {
    public final String key, fid, stid, parentFid;
    private ForumRoute(String fid, String stid, String parentFid) {
        this.fid = fid; this.stid = stid; this.parentFid = parentFid;
        key = stid.isEmpty() ? fid : "stid:" + stid;
    }
    public static ForumRoute fromKey(String key) {
        if (key != null && key.startsWith("stid:")) return fromParts("", key.substring(5), "");
        return fromParts(key, "", "");
    }
    public static ForumRoute fromParts(String fid, String stid, String parentFid) {
        String f = fid == null ? "" : fid, s = stid == null ? "" : stid, p = parentFid == null ? "" : parentFid;
        if (f.isEmpty() == s.isEmpty() || !f.isEmpty() && !isForumId(f)
                || !s.isEmpty() && !isCollectionId(s) || !p.isEmpty() && !isForumId(p))
            throw new IllegalArgumentException("无效的 NGA 版块或合集编号");
        return new ForumRoute(f, s, p);
    }
    public ForumRoute withParent(String parent) { return fromParts(fid, stid, parent); }
    public static boolean isForumId(String value) { return value != null && value.matches("-?[1-9][0-9]{0,11}"); }
    public static boolean isCollectionId(String value) { return value != null && value.matches("[1-9][0-9]{0,17}"); }
    public static boolean isKey(String value) {
        try { fromKey(value); return true; } catch (IllegalArgumentException invalid) { return false; }
    }
    /** ff is the official ordinary-child-board backlink, not a selected-topic filter. */
    public String query() { return stid.isEmpty() ? "fid=" + fid + (parentFid.isEmpty() ? "" : "&ff=" + parentFid) : "stid=" + stid; }
    /** Official public board/collection artwork; NativeImages uses a labeled fallback on a missing image. */
    public String iconUrl() { return stid.isEmpty() ? "https://img4.nga.cn/ngabbs/nga_classic/f/app/" + fid + ".png"
            : "https://img4.nga.cn/proxy/cache_attach/ficon/" + stid + "v.png"; }
    public String url(String host, int page) {
        if (!("bbs.nga.cn".equals(host) || "ngabbs.com".equals(host) || "nga.178.com".equals(host)))
            throw new IllegalArgumentException("无效的 NGA 域名");
        return "https://" + host + "/thread.php?" + query() + "&page=" + Math.max(1, Math.min(99999, page));
    }
}
