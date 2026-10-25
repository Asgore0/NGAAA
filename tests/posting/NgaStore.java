package cn.ngapure.app;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Offline test double. Never compile this file into the APK. */
public final class NgaStore {
    public static final Set<String> HOSTS = new HashSet<>(Arrays.asList("bbs.nga.cn", "ngabbs.com", "nga.178.com"));
    String testHost = "bbs.nga.cn";
    String testCookie = "ngaPassportUid=123; ngaPassportCid=offline-fixture-only";
    public String host() { return testHost; }
    public String cookie() { return testCookie; }
    public boolean loggedIn() { return !testCookie.isEmpty(); }
}
