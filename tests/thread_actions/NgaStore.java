package cn.ngapure.app;
import android.content.Context;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
/** Offline-only store, never bundled in the app. */
public final class NgaStore {
    public static final Set<String> HOSTS = new HashSet<>(Arrays.asList("bbs.nga.cn", "ngabbs.com", "nga.178.com"));
    String testHost = "bbs.nga.cn", testCookie = "ngaPassportUid=123; ngaPassportCid=offline-fixture-only";
    public NgaStore() { }
    public NgaStore(Context context) { }
    public String host() { return testHost; }
    public String cookie() { return testCookie; }
    public boolean loggedIn() { return !testCookie.isEmpty(); }
}
