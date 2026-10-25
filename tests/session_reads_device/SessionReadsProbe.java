package cn.ngapure.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.Handler;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.KeyPairGenerator;
import java.lang.reflect.Method;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real platform Keystore, own-UID synthetic credentials, unchanged production classes. */
public final class SessionReadsProbe extends Instrumentation {
    private static final String PACKAGE = "cn.ngapure.sessionreadsprobe";
    private static final String COOKIE_KEY = "cookies_encrypted";
    private static final String HOST = "bbs.nga.cn", OTHER_HOST = "ngabbs.com";
    private static final String A = "ngaPassportUid=910001; ngaPassportCid=synthetic-session-only-token-a";
    private static final String A2 = "ngaPassportUid=910001; ngaPassportCid=synthetic-session-only-token-renewed";
    private static final String B = "ngaPassportUid=910002; ngaPassportCid=synthetic-session-only-token-b";
    private static final String[] METRICS = {"readCalls", "decodeCalls", "decodeFailures", "mainDecodeCalls",
            "workerDecodeCalls", "decodeNs", "mainDecodeNs", "workerDecodeNs"};
    private final JSONObject report = new JSONObject();
    private final JSONArray checks = new JSONArray(), cases = new JSONArray();
    private boolean authorized, guarded;
    private int assertions;
    private String stage = "guard";
    private NgaStore store;
    private SharedPreferences preferences;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        authorized = arguments != null && "true".equals(arguments.getString("syntheticCredentialsOnly"));
        start();
    }
    private <T> T main(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); runOnMainSync(task); return task.get(30, TimeUnit.SECONDS);
    }
    private <T> T worker(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); new Thread(task, "session-reads-fixture").start();
        return task.get(30, TimeUnit.SECONDS);
    }
    private void check(String label, boolean value) {
        assertions++; checks.put(label); if (!value) throw new AssertionError(label);
    }
    private byte[] asset(String name) throws Exception {
        try (InputStream in = getTargetContext().getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192]; for (int n; (n = in.read(bytes)) != -1;) out.write(bytes, 0, n); return out.toByteArray();
        }
    }
    private static Map<String, Long> stats() {
        Map<String, Long> values = new LinkedHashMap<>();
        for (String item : NgaStore.sessionReadDiagnostics().split(" ")) {
            String[] pair = item.split("=", 2);
            if (pair.length != 2 || !pair[1].matches("[0-9]+") || values.put(pair[0], Long.valueOf(pair[1])) != null)
                throw new AssertionError("NumericDiagnosticsRequired");
        }
        if (values.size() != METRICS.length) throw new AssertionError("KnownMetricSchemaRequired");
        for (String name : METRICS) if (!values.containsKey(name)) throw new AssertionError("MissingMetric");
        return values;
    }
    private void record(String name, Map<String, Long> before, long reads, long decodes, long failures,
            long mainDecodes, long workerDecodes) throws Exception {
        Map<String, Long> after = stats(); JSONObject delta = new JSONObject();
        for (String metric : METRICS) { long value = after.get(metric) - before.get(metric); check(name + "-Monotonic-" + metric, value >= 0); delta.put(metric, value); }
        check(name + "-CredentialReads", delta.getLong("readCalls") == reads);
        check(name + "-DecodeCalls", delta.getLong("decodeCalls") == decodes);
        check(name + "-DecodeFailures", delta.getLong("decodeFailures") == failures);
        check(name + "-MainDecodes", delta.getLong("mainDecodeCalls") == mainDecodes);
        check(name + "-WorkerDecodes", delta.getLong("workerDecodeCalls") == workerDecodes);
        check(name + "-DurationAccounting", delta.getLong("decodeNs") == delta.getLong("mainDecodeNs") + delta.getLong("workerDecodeNs"));
        check(name + "-MeasuredDecodeDuration", decodes == 0 ? delta.getLong("decodeNs") == 0 : delta.getLong("decodeNs") > 0);
        cases.put(new JSONObject().put("case", name).put("counterDelta", delta));
    }
    private void set(String cookie) throws Exception { main(() -> { store.setCookie(cookie); return null; }); }
    private SessionScopeSnapshot scope() throws Exception { return main(() -> store.captureScopeSnapshot()); }
    private void replaceEncoded(String encoded) {
        check("OwnPreferencesMutationCommitted", preferences.edit().putString(COOKIE_KEY, encoded).commit());
    }
    private void warm(SessionScopeSnapshot expected) throws Exception {
        main(() -> { for (int i = 0; i < 100; i++) {
            check("WarmOwnershipObjectReused", store.captureScopeSnapshot() == expected);
            check("WarmLoginMarker", store.loggedIn() == expected.loggedIn);
        } return null; });
    }
    private void badEncoding(String label, String encoded, SessionScopeSnapshot prior, String good) throws Exception {
        stage = label; replaceEncoded(encoded); Map<String, Long> before = stats();
        SessionScopeSnapshot first = scope(), second = scope();
        check(label + "-FailedStateAnonymous", !first.loggedIn && !second.loggedIn);
        check(label + "-OldSuccessDiscarded", !first.contentScope.equals(prior.contentScope));
        check(label + "-FailedReadNotCached", first != second);
        record(label + "-two-retries", before, 2, 2, 2, 2, 0);
        // This bypasses the UI cache on purpose: an authoritative request read must fail too.
        before = stats(); NgaStore.CheckInSession failed = worker(() -> store.captureCheckInSession());
        check(label + "-FreshRequestHasNoCredentials", failed.cookie.isEmpty() && failed.account.isEmpty());
        record(label + "-fresh-failure", before, 1, 1, 1, 0, 1);
        replaceEncoded(good); before = stats(); SessionScopeSnapshot recovered = scope(); warm(recovered);
        check(label + "-OriginalOwnershipRecovered", recovered.loggedIn && recovered.contentScope.equals(prior.contentScope));
        record(label + "-recovery", before, 1, 1, 0, 1, 0);
    }
    private static void await(CountDownLatch latch) throws Exception {
        if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("FixtureLatchTimeout");
    }
    /** Holds only this helper process's existing production key monitor, with no production hook. */
    private static final class KeyBarrier implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final FutureTask<Void> holder = new FutureTask<>(() -> {
            synchronized (NgaStore.class) { entered.countDown(); await(release); } return null;
        });
        KeyBarrier() throws Exception { new Thread(holder, "fixture-key-monitor").start(); await(entered); }
        void assertHeld() { if (entered.getCount() != 0 || release.getCount() != 1) throw new AssertionError("KeyBarrierNotHeld"); }
        @Override public void close() {
            release.countDown();
            try { holder.get(5, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError("InterruptedKeyBarrierCleanup"); }
            catch (Exception error) { throw new AssertionError("KeyBarrierCleanupFailed"); }
        }
    }
    private <T> T responsiveMain(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); new Handler(Looper.getMainLooper()).post(task);
        return task.get(2, TimeUnit.SECONDS);
    }
    private void existingAliasConcurrent(boolean sameStore) throws Exception {
        stage = sameStore ? "same-store-existing-alias-parallel" : "separate-stores-existing-alias-parallel";
        set(A); main(() -> { store.setHost(HOST); return null; }); SessionScopeSnapshot expected = scope();
        final int count = 4; CountDownLatch ready = new CountDownLatch(count), start = new CountDownLatch(1);
        List<FutureTask<NgaStore.CheckInSession>> reads = new ArrayList<>(); Map<String, Long> before = stats();
        try (KeyBarrier barrier = new KeyBarrier()) {
            barrier.assertHeld();
            for (int i = 0; i < count; i++) {
                NgaStore subject = sameStore ? store : new NgaStore(getTargetContext());
                FutureTask<NgaStore.CheckInSession> task = new FutureTask<>(() -> { ready.countDown(); await(start); return subject.captureCheckInSession(); });
                reads.add(task); new Thread(task, "fixture-existing-key-reader").start();
            }
            await(ready); start.countDown();
            for (FutureTask<NgaStore.CheckInSession> task : reads) {
                NgaStore.CheckInSession result = task.get(5, TimeUnit.SECONDS);
                check(stage + "-ActualReadCompletesWhileClassMonitorHeld", A.equals(result.cookie) && HOST.equals(result.host));
            }
            check(stage + "-UiOwnerRemainsCurrent", responsiveMain(() -> store.captureScopeSnapshot()).contentScope.equals(expected.contentScope));
            check(stage + "-UiLoginRemainsCurrent", responsiveMain(() -> store.loggedIn()));
            FutureTask<Void> source = new FutureTask<>(() -> { synchronized (LoginPreferences.class) { return null; } });
            new Thread(source, "fixture-source-monitor-check").start(); source.get(2, TimeUnit.SECONDS);
            check(stage + "-SourceMonitorStillAvailable", true);
        } finally { start.countDown(); }
        record(stage, before, count, count, 0, 0, count);
        cases.put(new JSONObject().put("case", stage).put("parallelReaders", count).put("existingAliasReadsCompletedWhileClassMonitorHeld", true));
    }
    private void concurrentFirstCreation() throws Exception {
        stage = "concurrent-first-key-creation";
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        keys.deleteEntry("cn.ngapure.app.cookies.v1");
        check("OwnAliasAbsentBeforeConcurrentCreation", !keys.containsAlias("cn.ngapure.app.cookies.v1"));
        final int count = 8; CountDownLatch ready = new CountDownLatch(count), start = new CountDownLatch(1);
        // Invoke the actual private production accessor; no field injection or replacement.
        Method lookup = NgaStore.class.getDeclaredMethod("secretKey"); lookup.setAccessible(true);
        List<FutureTask<SecretKey>> reads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            FutureTask<SecretKey> task = new FutureTask<>(() -> { ready.countDown(); await(start); return (SecretKey) lookup.invoke(null); });
            reads.add(task); new Thread(task, "fixture-first-key-creator").start();
        }
        await(ready); start.countDown(); List<SecretKey> returned = new ArrayList<>();
        for (FutureTask<SecretKey> task : reads) returned.add(task.get(10, TimeUnit.SECONDS));
        SecretKey current = (SecretKey) keys.getKey("cn.ngapure.app.cookies.v1", null);
        byte[] plain = "synthetic-interoperability-check".getBytes(StandardCharsets.UTF_8);
        for (SecretKey key : returned) {
            check("ConcurrentFirstUseReturnsNonExportableKey", key.getEncoded() == null && key.getFormat() == null);
            Cipher encryption = Cipher.getInstance("AES/GCM/NoPadding"); encryption.init(Cipher.ENCRYPT_MODE, key);
            encryption.updateAAD("fixture-only".getBytes(StandardCharsets.UTF_8)); byte[] encrypted = encryption.doFinal(plain);
            Cipher decryption = Cipher.getInstance("AES/GCM/NoPadding"); decryption.init(Cipher.DECRYPT_MODE, current, new GCMParameterSpec(128, encryption.getIV()));
            decryption.updateAAD("fixture-only".getBytes(StandardCharsets.UTF_8));
            check("EveryConcurrentFirstUseKeyWorksWithFinalAuthoritativeAlias", Arrays.equals(plain, decryption.doFinal(encrypted)));
        }
        String oldCipher = preferences.getString(COOKIE_KEY, ""); Map<String, Long> before = stats();
        check("NewlyCreatedKeyRejectsExistingOldCiphertext", worker(() -> store.captureCheckInSession()).cookie.isEmpty());
        check("NewKeyFailureInvalidatesPriorUiSuccess", !scope().loggedIn);
        record(stage + "-old-cipher-fails-twice", before, 2, 2, 2, 1, 1);
        check("FirstCreationFailurePreservesEncryptedPreference", oldCipher.equals(preferences.getString(COOKIE_KEY, "")));
        set(A); check("NewLoginWorksAfterConcurrentCreation", scope().loggedIn);
        cases.put(new JSONObject().put("case", stage).put("concurrentCreators", count).put("productionPrivateAccessorInvoked", true)
                .put("returnedKeysInteroperateWithFinalAlias", true).put("keyBytesExported", false));
    }
    private void aliasReplacementAndNonKey() throws Exception {
        stage = "same-alias-key-replacement"; set(A); SessionScopeSnapshot prior = scope();
        String oldCipher = preferences.getString(COOKIE_KEY, "");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder("cn.ngapure.app.cookies.v1", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build()); generator.generateKey();
        Map<String, Long> before = stats();
        check("SameAliasReplacementRejectsOldCiphertext", worker(() -> store.captureCheckInSession()).cookie.isEmpty());
        SessionScopeSnapshot rejected = scope();
        check("SameAliasReplacementDropsOldUiSuccess", !rejected.loggedIn && !rejected.contentScope.equals(prior.contentScope));
        record(stage + "-two-failed-reads", before, 2, 2, 2, 1, 1);
        check("SameAliasReplacementDoesNotRewriteOldCiphertext", oldCipher.equals(preferences.getString(COOKIE_KEY, "")));
        set(A2); check("NewLoginUsesReplacementAlias", A2.equals(worker(() -> store.captureCheckInSession()).cookie));

        stage = "existing-certificate-only-alias";
        String certificateAlias = "cn.ngapure.sessionreadsprobe.certificate-only";
        KeyPairGenerator pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        pair.initialize(new KeyGenParameterSpec.Builder(certificateAlias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256).build()); pair.generateKeyPair();
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        java.security.cert.Certificate certificate = keys.getCertificate(certificateAlias);
        keys.deleteEntry("cn.ngapure.app.cookies.v1"); keys.setCertificateEntry("cn.ngapure.app.cookies.v1", certificate);
        oldCipher = preferences.getString(COOKIE_KEY, ""); before = stats();
        check("NonKeyAliasRejectsCredentialRead", worker(() -> store.captureCheckInSession()).cookie.isEmpty());
        check("NonKeyAliasFailureNotCached", !scope().loggedIn);
        record(stage + "-two-failed-reads", before, 2, 2, 2, 1, 1);
        boolean loginRejected = false;
        try { set(A); } catch (Exception expected) { loginRejected = true; }
        check("LoginCannotReplaceExistingNonKeyAlias", loginRejected && keys.isCertificateEntry("cn.ngapure.app.cookies.v1"));
        check("RejectedLoginPreservesEncryptedPreference", oldCipher.equals(preferences.getString(COOKIE_KEY, "")));
        keys.deleteEntry("cn.ngapure.app.cookies.v1"); keys.deleteEntry(certificateAlias);
        set(B); check("ExplicitAliasRemovalAllowsNewLoginRecovery", B.equals(worker(() -> store.captureCheckInSession()).cookie));
    }
    private void concurrentCases() throws Exception {
        existingAliasConcurrent(true); existingAliasConcurrent(false);
        concurrentFirstCreation(); aliasReplacementAndNonKey();
    }
    private void runCases() throws Exception {
        stage = "synthetic-setup";
        preferences = getTargetContext().getSharedPreferences("nga_local", Context.MODE_PRIVATE);
        check("FreshOwnPreferences", preferences.edit().clear().commit());
        store = new NgaStore(getTargetContext()); set(A);
        String encoded = preferences.getString(COOKIE_KEY, "");
        check("SyntheticCredentialEncryptedAtRest", !encoded.isEmpty() && !encoded.contains(A) && !encoded.contains("910001"));
        Map<String, Long> before = stats(); stage = "main-prime";
        NgaStore.CheckInSession session = main(() -> { check("CaptureRunsOnMain", Looper.myLooper() == Looper.getMainLooper()); return store.captureCheckInSession(); });
        check("CapturedSyntheticSessionMatches", HOST.equals(session.host) && A.equals(session.cookie) && "910001".equals(session.account));
        SessionScopeSnapshot a = scope(); warm(a);
        record("main-capture-plus-100-ui-reads", before, 1, 1, 0, 1, 0);
        stage = "fresh-worker-reads"; before = stats();
        worker(() -> { check("CaptureRunsOnWorker", Looper.myLooper() != Looper.getMainLooper());
            for (int i = 0; i < 3; i++) { NgaStore.CheckInSession read = store.captureCheckInSession(); check("FreshWorkerUsesActualCredentials", A.equals(read.cookie)); }
            return null; });
        SessionScopeSnapshot fresh = scope(); warm(fresh);
        check("FreshReadKeepsOwnership", fresh.contentScope.equals(a.contentScope));
        record("three-fresh-worker-captures", before, 3, 3, 0, 0, 3);
        stage = "token-renewal"; set(A2); before = stats(); SessionScopeSnapshot a2 = scope(); warm(a2);
        check("SameAccountTokenChangesContentOnly", !a2.contentScope.equals(a.contentScope) && a2.localDataScope.equals(a.localDataScope));
        record("token-renewal", before, 1, 1, 0, 1, 0);
        stage = "account-change"; set(B); before = stats(); SessionScopeSnapshot b = scope(); warm(b);
        check("NewAccountChangesBothOwners", !b.contentScope.equals(a2.contentScope) && !b.localDataScope.equals(a2.localDataScope));
        record("account-change", before, 1, 1, 0, 1, 0);
        stage = "host-change"; main(() -> { store.setHost(OTHER_HOST); return null; }); before = stats(); SessionScopeSnapshot other = scope(); warm(other);
        check("HostChangesBothOwners", !other.contentScope.equals(b.contentScope) && !other.localDataScope.equals(b.localDataScope));
        record("host-change", before, 1, 1, 0, 1, 0);
        stage = "separate-store"; NgaStore separate = new NgaStore(getTargetContext()); before = stats();
        SessionScopeSnapshot separateScope = worker(() -> { NgaStore.CheckInSession s = separate.captureCheckInSession(); check("SeparateStoreReadsCurrentHost", OTHER_HOST.equals(s.host)); return separate.captureScopeSnapshot(); });
        check("SeparateStoreSameDerivedOwner", separateScope.contentScope.equals(other.contentScope));
        record("independent-store-fresh-capture", before, 1, 1, 0, 0, 1);
        String good = preferences.getString(COOKIE_KEY, "");
        badEncoding("bad-base64", "A:A", other, good);
        String[] parts = good.split(":", 2); byte[] bytes = Base64.decode(parts[1], Base64.NO_WRAP); bytes[bytes.length - 1] ^= 1;
        badEncoding("bad-gcm-tag", parts[0] + ":" + Base64.encodeToString(bytes, Base64.NO_WRAP), other, good);
        stage = "background-return"; main(() -> { store.clearScopeSnapshot(); return null; }); before = stats();
        check("ReturnRevalidatesLogin", main(() -> store.loggedIn())); SessionScopeSnapshot returned = scope(); warm(returned);
        check("ReturnRetainsExactOwner", returned.contentScope.equals(other.contentScope));
        record("explicit-background-clear-and-return", before, 1, 1, 0, 1, 0);
        stage = "logout"; main(() -> { store.clearLogin(); return null; }); before = stats(); SessionScopeSnapshot anonymous = scope(); warm(anonymous);
        check("LogoutDropsBothOwners", !anonymous.loggedIn && !anonymous.contentScope.equals(other.contentScope) && !anonymous.localDataScope.equals(other.localDataScope));
        check("OwnCookieRemovedAfterLogout", !preferences.contains(COOKIE_KEY));
        record("logout-anonymous-ui", before, 1, 0, 0, 0, 0);
        before = stats(); check("OtherStoreObservesLogout", worker(() -> !separate.loggedIn()));
        record("other-store-observes-logout", before, 1, 0, 0, 0, 0);
        main(() -> { store.clearLogin(); store.clearScopeSnapshot(); return null; }); before = stats();
        NgaStore.CheckInSession empty = worker(() -> store.captureCheckInSession());
        check("RepeatedLogoutNeverRestoresCredential", empty.cookie.isEmpty() && empty.account.isEmpty()); warm(scope());
        record("repeat-logout-and-fresh-capture", before, 1, 0, 0, 0, 0);
        stage = "login-after-logout"; set(A); before = stats(); SessionScopeSnapshot relogin = scope(); warm(relogin);
        check("NewLoginAfterLogoutRevalidates", relogin.loggedIn && !relogin.contentScope.equals(anonymous.contentScope));
        record("relogin", before, 1, 1, 0, 1, 0);
        stage = "own-keystore-key-loss";
        String savedCiphertext = preferences.getString(COOKIE_KEY, "");
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        check("OwnCookieAliasExistsBeforeDeletion", keys.containsAlias("cn.ngapure.app.cookies.v1"));
        keys.deleteEntry("cn.ngapure.app.cookies.v1");
        check("OwnCookieAliasDeleted", !keys.containsAlias("cn.ngapure.app.cookies.v1"));
        check("KeyLossPreservesEncryptedPreference", savedCiphertext.equals(preferences.getString(COOKIE_KEY, "")));
        before = stats();
        NgaStore.CheckInSession lost = worker(() -> store.captureCheckInSession());
        check("LostKeyCannotRecoverOldCredential", lost.cookie.isEmpty() && lost.account.isEmpty());
        SessionScopeSnapshot lostAgain = scope();
        check("FailedFreshReadDropsOldSuccess", !lostAgain.loggedIn && !lostAgain.contentScope.equals(relogin.contentScope));
        record("deleted-key-old-ciphertext-two-retries", before, 2, 2, 2, 1, 1);
        check("MissingAliasRecreatedWithoutRecoveringOldCiphertext", keys.containsAlias("cn.ngapure.app.cookies.v1"));
        check("FailedReadsDoNotRewriteOldCiphertext", savedCiphertext.equals(preferences.getString(COOKIE_KEY, "")));
        stage = "login-after-key-loss"; set(A2); before = stats(); SessionScopeSnapshot recoveredKey = scope(); warm(recoveredKey);
        check("NewLoginRecoversAfterKeyLoss", recoveredKey.loggedIn
                && recoveredKey.contentScope.equals(SessionScopeSnapshot.derive(OTHER_HOST, A2, "910001").contentScope));
        check("NewLoginReplacesOldCiphertext", !savedCiphertext.equals(preferences.getString(COOKIE_KEY, "")));
        record("new-login-after-key-recreation", before, 1, 1, 0, 1, 0);
    }
    @Override public void onStart() {
        boolean passed = false, cleaned = false; String failure = "NONE";
        try {
            if (!authorized || !PACKAGE.equals(getTargetContext().getPackageName())
                    || getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED)
                throw new IllegalStateException("OwnUidNoInternetRequired");
            check("SeparateUidFromRealClient", getTargetContext().getApplicationInfo().uid
                    != getTargetContext().getPackageManager().getApplicationInfo("cn.ngapure.app", 0).uid);
            guarded = true;
            URL.setURLStreamHandlerFactory(protocol -> ("https".equals(protocol) || "http".equals(protocol)) ? new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) throws IOException { throw new IOException("SyntheticSessionNoNetwork"); }
            } : null);
            report.put("binding", new JSONObject(new String(asset("binding.json"), StandardCharsets.UTF_8)));
            runCases(); concurrentCases(); passed = true;
        } catch (Throwable error) {
            Throwable cause = error; for (int i = 0; i < 10 && cause.getCause() != null; i++) cause = cause.getCause();
            failure = cause.getClass().getSimpleName();
        } finally {
            if (guarded && store != null) try { main(() -> { store.clearLogin(); return null; }); cleaned = !preferences.contains(COOKIE_KEY); }
            catch (Exception ignored) { cleaned = false; }
        }
        passed &= cleaned;
        try {
            report.put("passed", passed).put("failure", failure).put("failureStage", stage).put("assertions", assertions).put("checks", checks).put("cases", cases)
                    .put("ownUidOnly", true).put("internetPermission", false).put("realClientStarted", false).put("realAccountRead", false)
                    .put("realNetworkRequests", 0).put("communityWrites", 0).put("actualAndroidKeystore", true).put("syntheticCredentialsOnly", true)
                    .put("productionClassesRewritten", false).put("productionFieldsInjected", false).put("credentialsExported", false).put("scopeHashesExported", false)
                    .put("productionActivityLifecycleStarted", false).put("ownSyntheticLoginCleared", cleaned)
                    .put("limitations", new JSONArray().put("Synthetic credentials in a separate UID exercise the actual platform Keystore and unchanged production store.")
                            .put("No real account is read, logged in, validated by a server or used to measure authenticated startup/network latency.")
                            .put("Background return is explicit scope invalidation followed by reads, not an Activity lifecycle measurement.")
                            .put("The held class monitor verifies existing-key reads bypass generation serialization. Eight first-use calls invoke the unchanged production private key accessor and validate interoperability with the final alias; no production field is injected. Earlier c1 source-mutation/rollback races remain preserved in their original execution evidence.")
                            .put("Decode durations include key-monitor waiting but exclude source capture/revalidation; numeric source-lock counters measure only instrumented reads, not all writes or every process lock. Neither is app startup latency."));
        } catch (Exception ignored) { }
        Bundle result = new Bundle(); result.putString("safe_session_reads_report", report.toString()); finish(passed ? 0 : 1, result);
    }
}
