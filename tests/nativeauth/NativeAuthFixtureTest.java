package cn.ngapure.app;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Synthetic server conversations only; never connects to a server or reads a user session. */
public final class NativeAuthFixtureTest {
    private static final String KEY = "fixture-key-0001", HIDDEN = "fixture-hidden-0002";
    private static final String GRANT = "fixture-grant-value-abc", SESSION = "fixture-session-value-xyz";
    private static int checks, scenarios;
    private static Path fixtures;
    private NativeAuthFixtureTest() { }

    public static void main(String[] args) throws Exception {
        fixtures = Paths.get(args[0]);
        scenario("generation and redacted models", NativeAuthFixtureTest::generation);
        scenario("waiting and rate limit", NativeAuthFixtureTest::waiting);
        scenario("automatic pause and manual continuation", NativeAuthFixtureTest::pause);
        scenario("explicit expiration", NativeAuthFixtureTest::expiration);
        scenario("additional verification", NativeAuthFixtureTest::verification);
        scenario("confirmed passport session", () -> success(false));
        scenario("confirmed access session", () -> success(true));
        scenario("no fabricated cookies", NativeAuthFixtureTest::missingCookies);
        scenario("mismatched session rejected", NativeAuthFixtureTest::mismatch);
        scenario("invalid session rejected", NativeAuthFixtureTest::invalidSession);
        scenario("network retry preserves redeemed grant", NativeAuthFixtureTest::retryVerify);
        scenario("same origin cookies only", NativeAuthFixtureTest::sameOrigin);
        scenario("redirect blocked", NativeAuthFixtureTest::redirect);
        scenario("cancel discards in-flight result", NativeAuthFixtureTest::cancellation);
        scenario("refresh invalidates old attempt", NativeAuthFixtureTest::refresh);
        scenario("foreign attempt rejected", NativeAuthFixtureTest::ownership);
        scenario("malformed envelopes fail closed", NativeAuthFixtureTest::malformed);
        scenario("server message redaction", NativeAuthFixtureTest::redaction);
        scenario("incomplete grant rejected", NativeAuthFixtureTest::incompleteGrant);
        scenario("header size bounded", NativeAuthFixtureTest::largeCookies);
        scenario("validation respects cookie rotation and removal", NativeAuthFixtureTest::finalCookies);
        scenario("bounded metadata diagnostics exclude secrets", NativeAuthFixtureTest::diagnostics);
        scenario("QR recovery keeps original automatic deadline", NativeAuthFixtureTest::restoreQr);
        scenario("grant checkpoint precedes exchange", NativeAuthFixtureTest::restoreGrant);
        scenario("exchanged checkpoint retries verification only", NativeAuthFixtureTest::restoreExchanged);
        scenario("verified result retained until account commit", NativeAuthFixtureTest::restoreSuccess);
        scenario("suspend lets in-flight grant checkpoint", NativeAuthFixtureTest::suspendInFlight);
        scenario("explicit terminal states erase recovery", NativeAuthFixtureTest::clearRecovery);
        scenario("checkpoint failure never exposes an unprotected QR", NativeAuthFixtureTest::checkpointFailure);
        scenario("foreign or incomplete recovery rejected", NativeAuthFixtureTest::invalidRecovery);
        scenario("recovery cleanup failure is observable", NativeAuthFixtureTest::cleanupFailure);
        scenario("encrypted store and protocol cold recovery integrate", NativeAuthFixtureTest::encryptedIntegration);
        System.out.println("PASS native authorization protocol: " + scenarios + " scenarios, " + checks + " checks");
    }

    private static void generation() throws Exception {
        Harness h = new Harness(); NativeAuth.QrSession s = h.begin();
        check(s.qrText().equals("https://bbs.nga.cn/nuke.php?__lib=login&__act=qrlogin_ui&qrkey=" + KEY), "official QR payload");
        check(!s.qrText().contains(HIDDEN), "hidden key excluded from QR");
        check(s.host().equals("bbs.nga.cn") && s.autoPollRemainingMillis() == 90000, "fixed host and deadline");
        check(!s.toString().contains(KEY), "model string redacted");
        check(h.wire.calls.get(0).form.contains("__output=14"), "JSON mode requested");
        check(h.wire.calls.get(0).cookie.isEmpty(), "no existing cookie read");
        h.auth.close(); check(s.isCancelled() && s.qrText().isEmpty(), "close clears attempt");
        expectIo(h.auth::begin); check(h.wire.calls.size() == 1, "closed owner cannot start a late request");
        expectIllegal(() -> new NativeAuth("https://bbs.nga.cn"));
        expectIllegal(() -> new NativeAuth("attacker.invalid"));
        for (String host : Arrays.asList("bbs.nga.cn", "ngabbs.com", "nga.178.com")) {
            Wire wire = new Wire(); wire.add(fixture("generate"));
            NativeAuth auth = new NativeAuth(host, wire, new TestClock());
            check(auth.begin().host().equals(host), "known host supported"); auth.close();
        }
    }

    private static void restoreQr() throws Exception {
        MemoryRecovery saved = new MemoryRecovery(); Harness first = new Harness(saved);
        NativeAuth.QrSession original = first.begin(); String checkpoint = saved.state;
        first.auth.suspend();
        check(original.isCancelled() && original.qrText().isEmpty(), "suspend clears old in-memory keys");
        check(saved.clears == 0 && !saved.state.isEmpty(), "suspend retains encrypted-store checkpoint");
        Harness second = new Harness(new MemoryRecovery());
        NativeAuth.QrSession recovered = second.auth.restore(checkpoint, 10000);
        check(recovered.qrText().contains(KEY) && !recovered.qrText().contains(HIDDEN), "same visible QR restored");
        check(recovered.autoPollRemainingMillis() == 80000 && second.wire.calls.isEmpty(), "restore does not regenerate or extend automatic window");
        Harness late = new Harness(); NativeAuth.QrSession paused = late.auth.restore(checkpoint, 100000);
        check(paused.autoPollRemainingMillis() == 0 && late.auth.poll(paused, false).state == NativeAuth.State.PAUSED,
                "long interruption restores a paused attempt rather than claiming server expiry");
        late.wire.add(fixture("pending")); late.time.advance(3000);
        check(late.auth.poll(paused, true).state == NativeAuth.State.PAUSED && late.wire.calls.size() == 1,
                "manual check still uses recovered original keys");
        check(late.wire.calls.get(0).form.contains("hiddenkey=" + HIDDEN), "recovered hidden key stays in official POST body");
    }
    private static void restoreGrant() throws Exception {
        MemoryRecovery saved = new MemoryRecovery(); Harness first = new Harness(saved);
        NativeAuth.QrSession original = first.begin(); first.wire.add(fixture("approved")); first.wire.addFailure(); first.time.advance(3000);
        expectIo(() -> first.auth.poll(original, false));
        JSONObject snapshot = new JSONObject(saved.state);
        check(snapshot.getString("grantToken").equals(GRANT) && !snapshot.getBoolean("exchanged"), "actual grant saved before exchange request fails");
        first.auth.suspend(); Harness second = new Harness(new MemoryRecovery());
        NativeAuth.QrSession recovered = second.auth.restore(saved.state, 0);
        second.wire.add(withCookies(fixture("exchange"), sessionCookies(false, "123"))); second.wire.add(fixture("valid")); second.time.advance(3000);
        check(second.auth.poll(recovered, true).state == NativeAuth.State.SUCCESS, "grant recovery still requires actual exchange and verification");
        check(second.wire.calls.size() == 2 && second.wire.calls.get(0).url.getQuery().contains("login_set_cookie_quick"),
                "recovered grant avoids a second QR grant poll");
        check(second.wire.calls.get(0).cookie.isEmpty(), "old stored account never injected into recovered grant");
    }
    private static void restoreExchanged() throws Exception {
        MemoryRecovery saved = new MemoryRecovery(); Harness first = new Harness(saved);
        NativeAuth.QrSession original = first.begin(); queueGrant(first, false); first.wire.addFailure(); first.time.advance(3000);
        expectIo(() -> first.auth.poll(original, false));
        check(new JSONObject(saved.state).getBoolean("exchanged"), "exchange cookie and progress synchronously checkpointed before failed verification");
        first.auth.suspend(); Harness second = new Harness(new MemoryRecovery());
        NativeAuth.QrSession recovered = second.auth.restore(saved.state, 1000);
        second.wire.add(fixture("valid")); second.time.advance(3000);
        NativeAuth.PollResult result = second.auth.poll(recovered, true);
        check(result.state == NativeAuth.State.SUCCESS && result.cookieHeader.contains(SESSION), "recovered new session verified by server");
        check(second.wire.calls.size() == 1 && second.wire.calls.get(0).form.contains("__act=iflogin"), "redeemed grant not exchanged twice after restore");
    }
    private static void restoreSuccess() throws Exception {
        MemoryRecovery saved = new MemoryRecovery(); Harness first = new Harness(saved);
        NativeAuth.QrSession original = first.begin(); queueGrant(first, false); first.wire.add(fixture("valid")); first.time.advance(3000);
        check(first.auth.poll(original, false).state == NativeAuth.State.SUCCESS && saved.clears == 0,
                "server success keeps recovery until account storage succeeds");
        first.auth.suspend(); Harness second = new Harness(new MemoryRecovery());
        NativeAuth.QrSession recovered = second.auth.restore(saved.state, 0);
        second.wire.add(response("{\"code\":0,\"result\":[false]}")); second.time.advance(3000);
        check(second.auth.poll(recovered, true).state == NativeAuth.State.ERROR,
                "a persisted previous success cannot bypass a new server confirmation");
        second.auth.close(); check(((MemoryRecovery) second.recovery).clears > 0, "explicit close after account commit clears checkpoint");
    }
    private static void suspendInFlight() throws Exception {
        MemoryRecovery saved = new MemoryRecovery(); Harness h = new Harness(saved);
        NativeAuth.QrSession original = h.begin(); queueGrant(h, false); h.wire.add(fixture("valid")); h.time.advance(3000);
        h.wire.beforeResponse = h.auth::suspend;
        check(h.auth.poll(original, false).state == NativeAuth.State.SUCCESS,
                "an already-running grant is allowed to finish and checkpoint after suspension");
        check(saved.clears == 0 && new JSONObject(saved.state).getBoolean("exchanged"), "suspended worker never erases newly obtained grant");
        check(original.qrText().isEmpty() && original.isCancelled(), "memory cleared after finite in-flight work settles");
        int requests = h.wire.calls.size(); h.auth.poll(original, true);
        check(h.wire.calls.size() == requests, "suspended owner cannot issue new network work");
        expectIo(h.auth::begin);
    }
    private static void clearRecovery() throws Exception {
        for (String terminal : Arrays.asList("expired", "verification")) {
            MemoryRecovery saved = new MemoryRecovery(); Harness h = new Harness(saved);
            NativeAuth.QrSession original = h.begin(); h.wire.add(fixture(terminal)); h.time.advance(3000);
            h.auth.poll(original, false);
            check(saved.clears > 0 && saved.state.isEmpty(), "explicit server terminal erases checkpoint");
            MemoryRecovery suspended = new MemoryRecovery(); Harness inFlight = new Harness(suspended);
            NativeAuth.QrSession pending = inFlight.begin(); inFlight.wire.add(fixture(terminal)); inFlight.time.advance(3000);
            inFlight.wire.beforeResponse = inFlight.auth::suspend; inFlight.auth.poll(pending, false);
            check(suspended.clears > 0 && suspended.state.isEmpty(), "in-flight terminal clears even after UI suspension");
        }
        MemoryRecovery saved = new MemoryRecovery(); Harness h = new Harness(saved); NativeAuth.QrSession original = h.begin();
        h.auth.cancel(original); check(saved.clears > 0 && saved.state.isEmpty(), "explicit cancellation erases recovery");
    }
    private static void cleanupFailure() throws Exception {
        MemoryRecovery saved = new MemoryRecovery(); Harness h = new Harness(saved); h.begin();
        saved.clearFail = true; h.auth.close();
        check(h.auth.recoveryCleanupFailed(), "clear failure is available to the UI and not treated as cleanup success");
        saved.clearFail = false; h.auth.close();
        check(!h.auth.recoveryCleanupFailed() && saved.state.isEmpty(), "a successful cleanup retry clears the diagnostic flag");
    }
    private static void encryptedIntegration() throws Exception {
        EncryptedMemory memory = new EncryptedMemory();
        NativeQrRecoveryStore encrypted = new NativeQrRecoveryStore(memory, memory, memory);
        Harness first = new Harness(encrypted.fresh("bbs.nga.cn")); NativeAuth.QrSession original = first.begin();
        check(encrypted.hasPending("bbs.nga.cn") && !memory.record.blob.contains(KEY), "actual protocol checkpoint is encrypted before QR display");
        first.auth.suspend(); memory.advance(95000);
        NativeQrRecoveryStore.SavedAttempt saved = encrypted.restore("bbs.nga.cn");
        check(saved != null && saved.ageMillis == 95000, "cold restore decrypts the actual protocol snapshot");
        Harness second = new Harness(saved.lease); NativeAuth.QrSession restored = second.auth.restore(saved.state, saved.ageMillis);
        check(restored.qrText().contains(KEY) && restored.autoPollRemainingMillis() == 0, "original QR and exhausted automatic window survive cold recovery");
        check(second.auth.poll(restored, false).state == NativeAuth.State.PAUSED && second.wire.calls.isEmpty(), "recovery cannot restart ninety seconds of automatic checks");
        queueGrant(second, false); second.wire.add(fixture("valid")); second.time.advance(3000);
        NativeAuth.PollResult result = second.auth.poll(restored, true);
        check(result.state == NativeAuth.State.SUCCESS && result.cookieHeader.contains(SESSION), "manual recovered authorization still needs real synthetic grant, exchange and verification");
        check(!memory.record.blob.contains(GRANT) && !memory.record.blob.contains(SESSION), "grant and exchanged cookies remain encrypted");
        check(encrypted.hasPending("bbs.nga.cn"), "successful protocol result retains snapshot until account save acknowledgement");
        second.auth.close(); check(!encrypted.hasPending("bbs.nga.cn"), "account-save acknowledgement removes actual encrypted checkpoint");
        check(original.isCancelled(), "old owner memory remains cancelled after handoff");
    }
    private static void checkpointFailure() throws Exception {
        MemoryRecovery saved = new MemoryRecovery(); saved.fail = true; Harness h = new Harness(saved);
        expectIo(h::begin); check(saved.state.isEmpty(), "cannot display generated QR when durable encryption fails");
        MemoryRecovery progress = new MemoryRecovery(); Harness retry = new Harness(progress); NativeAuth.QrSession session = retry.begin();
        retry.wire.add(fixture("approved")); retry.time.advance(3000); progress.fail = true;
        expectIo(() -> retry.auth.poll(session, false));
        check(retry.wire.calls.size() == 2, "grant checkpoint failure stops exchange work");
        progress.fail = false; queueGrantAfterCheckpointFailure(retry); retry.time.advance(3000);
        check(retry.auth.poll(session, true).state == NativeAuth.State.SUCCESS, "persist failure can be retried without replacing old account");
    }
    private static void queueGrantAfterCheckpointFailure(Harness h) throws IOException {
        // The actual grant remains in memory; retry its commit before sending the exchange.
        h.wire.add(withCookies(fixture("exchange"), sessionCookies(false, "123"))); h.wire.add(fixture("valid"));
    }
    private static void invalidRecovery() throws Exception {
        MemoryRecovery saved = new MemoryRecovery(); Harness h = new Harness(saved); h.begin();
        String good = saved.state;
        for (String bad : Arrays.asList("{}", good + " trailing", new JSONObject(good).put("host", "ngabbs.com").toString(),
                new JSONObject(good).put("automaticRemaining", 90001).toString(),
                new JSONObject(good).put("exchanged", true).toString(),
                new JSONObject(good).put("grantUid", "123").toString())) {
            Harness restored = new Harness(); expectIo(() -> restored.auth.restore(bad, 0));
            check(restored.wire.calls.isEmpty(), "invalid snapshots never contact a server");
        }
        Harness restored = new Harness(); expectIo(() -> restored.auth.restore(good, -1));
    }
    private static void waiting() throws Exception {
        Harness h = new Harness(); NativeAuth.QrSession s = h.begin();
        check(h.auth.poll(s, false).state == NativeAuth.State.WAITING && h.wire.calls.size() == 1, "initial delay avoids rapid polling");
        h.wire.add(fixture("pending")); h.time.advance(3000);
        NativeAuth.PollResult result = h.auth.poll(s, false);
        check(result.state == NativeAuth.State.WAITING && result.cookieHeader.isEmpty(), "pending is not success");
        check(result.retryAfterMillis == 3000, "pending retry interval");
        Call call = h.wire.calls.get(1);
        check(call.form.contains("hiddenkey=" + HIDDEN) && call.form.contains("v2=1") && call.form.contains("app_id=5004"), "official poll fields");
        check(!call.url.toString().contains(KEY) && !call.url.toString().contains(HIDDEN), "private keys stay in POST body");
        h.auth.poll(s, true); check(h.wire.calls.size() == 2, "manual respects rate limit");
    }
    private static void pause() throws Exception {
        Harness h = new Harness(); NativeAuth.QrSession s = h.begin(); h.time.advance(90000);
        NativeAuth.PollResult paused = h.auth.poll(s, false);
        check(paused.state == NativeAuth.State.PAUSED && h.wire.calls.size() == 1, "90 seconds pauses without request");
        check(!paused.message.contains("过期"), "local pause does not claim expiration");
        h.wire.add(fixture("pending"));
        check(h.auth.poll(s, true).state == NativeAuth.State.PAUSED && h.wire.calls.size() == 2, "manual still checks same attempt");
    }
    private static void expiration() throws Exception {
        Harness h = new Harness(); NativeAuth.QrSession s = h.begin(); h.wire.add(fixture("expired")); h.time.advance(3000);
        check(h.auth.poll(s, false).state == NativeAuth.State.EXPIRED, "server expiration recognized");
        h.time.advance(3000); h.auth.poll(s, true); check(h.wire.calls.size() == 2, "expired attempt stops requests");
    }
    private static void verification() throws Exception {
        Harness h = new Harness(); NativeAuth.QrSession s = h.begin(); h.wire.add(fixture("verification")); h.time.advance(3000);
        check(h.auth.poll(s, false).state == NativeAuth.State.VERIFY_REQUIRED, "server security check is explicit");
        h.time.advance(3000); h.auth.poll(s, true); check(h.wire.calls.size() == 2, "verification stops automatic bypass attempts");
        Harness html = new Harness(); NativeAuth.QrSession t = html.begin(); html.wire.add(response("<html>security challenge</html>")); html.time.advance(3000);
        check(html.auth.poll(t, false).state == NativeAuth.State.VERIFY_REQUIRED, "HTML challenge never executed");
    }
    private static void success(boolean access) throws Exception {
        Harness h = new Harness(); NativeAuth.QrSession s = h.begin();
        h.wire.add(fixture("approved")); h.wire.add(withCookies(fixture("exchange"), sessionCookies(access, "123")));
        h.wire.add(fixture("valid")); h.time.advance(3000);
        NativeAuth.PollResult result = h.auth.poll(s, false);
        check(result.state == NativeAuth.State.SUCCESS, "authorized cookie verified");
        String uid = access ? "access_uid" : "ngaPassportUid", token = access ? "access_token" : "ngaPassportCid";
        check(result.cookieHeader.contains(uid + "=123") && result.cookieHeader.contains(token + "=" + SESSION), "real exchanged cookie returned");
        check(!result.cookieHeader.contains(GRANT) && !result.cookieHeader.contains("unrelated="), "grant and unrelated cookie excluded");
        check(!result.toString().contains(SESSION), "result string redacted");
        check(h.wire.calls.size() == 4, "generation grant exchange validation all required");
        Call exchange = h.wire.calls.get(2), validate = h.wire.calls.get(3);
        check(exchange.url.getQuery().contains("login_set_cookie_quick") && exchange.form.contains("cid=" + GRANT), "official grant exchange");
        check(validate.form.contains("__act=iflogin") && validate.form.contains(token + "=" + SESSION), "server validation uses exchanged credentials");
        check(validate.cookie.contains(token + "=" + SESSION), "server cookies stay in origin jar");
        check(h.auth.poll(s, false).cookieHeader.equals(result.cookieHeader) && h.wire.calls.size() == 4, "confirmed result reusable without new requests");
    }
    private static void missingCookies() throws Exception {
        Harness h = new Harness(); NativeAuth.QrSession s = h.begin(); h.wire.add(fixture("approved")); h.wire.add(fixture("exchange")); h.time.advance(3000);
        NativeAuth.PollResult result = h.auth.poll(s, false);
        check(result.state == NativeAuth.State.ERROR && result.cookieHeader.isEmpty(), "grant alone never becomes cookie");
        check(h.wire.calls.size() == 3, "no invalid iflogin sent");
    }
    private static void mismatch() throws Exception {
        Harness h = new Harness(); NativeAuth.QrSession s = h.begin(); h.wire.add(fixture("approved"));
        h.wire.add(withCookies(fixture("exchange"), sessionCookies(false, "456"))); h.time.advance(3000);
        check(h.auth.poll(s, false).state == NativeAuth.State.ERROR && h.wire.calls.size() == 3, "unrelated identity not accepted");
    }
    private static void invalidSession() throws Exception {
        for (String invalid : Arrays.asList("0", "false", "\"未确认登录信息有效\"", "null", "{}")) {
            Harness h = prepared(); NativeAuth.QrSession s = h.begin(); queueGrant(h, false);
            h.wire.add(response("{\"code\":0,\"result\":[" + invalid + "]}")); h.time.advance(3000);
            NativeAuth.PollResult result = h.auth.poll(s, false);
            check(result.state == NativeAuth.State.ERROR && result.cookieHeader.isEmpty(), "validation must explicitly confirm session");
        }
        Harness h = prepared(); NativeAuth.QrSession s = h.begin(); queueGrant(h, false);
        h.wire.add(response("{\"code\":1,\"msg\":\"会话无效\"}")); h.time.advance(3000);
        check(h.auth.poll(s, false).state == NativeAuth.State.ERROR, "business error overrides data");
    }
    private static void retryVerify() throws Exception {
        Harness h = new Harness(); NativeAuth.QrSession s = h.begin(); queueGrant(h, false); h.wire.addFailure(); h.time.advance(3000);
        expectIo(() -> h.auth.poll(s, false));
        h.wire.add(fixture("valid")); h.time.advance(3000);
        check(h.auth.poll(s, true).state == NativeAuth.State.SUCCESS, "verification can retry after transient network error");
        check(h.wire.calls.size() == 5 && h.wire.calls.get(4).form.contains("__act=iflogin"), "redeemed grant not consumed twice");
    }
    private static void sameOrigin() throws Exception {
        Harness h = new Harness(); NativeAuth.QrSession s = h.begin(); h.wire.add(fixture("approved"));
        h.wire.add(withCookies(fixture("exchange"), Arrays.asList(
                "ngaPassportUid=123; Domain=attacker.invalid; Path=/", "ngaPassportCid=" + SESSION + "; Domain=attacker.invalid; Path=/",
                "private_path=ignored; Path=/private", "expired=ignored; Path=/; Max-Age=0")));
        h.time.advance(3000); check(h.auth.poll(s, false).state == NativeAuth.State.ERROR, "cross origin cookies cannot authorize");
        for (Call call : h.wire.calls) check(call.url.getProtocol().equals("https") && call.url.getHost().equals("bbs.nga.cn") && call.cookie.isEmpty(), "requests pinned to origin");
        Harness valid = new Harness(); NativeAuth.QrSession t = valid.begin(); valid.wire.add(fixture("approved"));
        valid.wire.add(withCookies(fixture("exchange"), Arrays.asList("ngaPassportUid=123; Domain=.nga.cn; Path=/; Secure", "ngaPassportCid=" + SESSION + "; Domain=.nga.cn; Path=/; Secure", "unrelated=value; Path=/")));
        valid.wire.add(fixture("valid")); valid.time.advance(3000);
        NativeAuth.PollResult result = valid.auth.poll(t, false); check(result.state == NativeAuth.State.SUCCESS && !result.cookieHeader.contains("unrelated"), "parent domain cookie accepted only for pinned origin");
    }
    private static void redirect() throws Exception {
        Harness h = new Harness(); NativeAuth.QrSession s = h.begin();
        h.wire.add(new NativeAuth.Response(302, new byte[0], "", Collections.singletonMap("Location", Collections.singletonList("https://attacker.invalid/")))); h.time.advance(3000);
        NativeAuth.PollResult result = h.auth.poll(s, false);
        check(result.state == NativeAuth.State.ERROR && result.cookieHeader.isEmpty() && h.wire.calls.size() == 2, "redirect rejected without credential forwarding");
    }
    private static void cancellation() throws Exception {
        Harness h = new Harness(); NativeAuth.QrSession s = h.begin();
        h.wire.beforeResponse = () -> h.auth.cancel(s); h.wire.add(fixture("approved")); h.time.advance(3000);
        check(h.auth.poll(s, false).state == NativeAuth.State.CANCELLED, "cancelled in-flight approval discarded");
        check(s.isCancelled() && s.qrText().isEmpty() && h.wire.calls.size() == 2, "cancel clears attempt without exchange");
        h.auth.cancel(s); check(h.auth.poll(s, true).cookieHeader.isEmpty(), "cancel idempotent and no saved session");
    }
    private static void refresh() throws Exception {
        Harness h = new Harness(); NativeAuth.QrSession first = h.begin(); h.wire.add(fixture("generate")); NativeAuth.QrSession next = h.auth.begin();
        check(first.isCancelled() && !next.isCancelled(), "new attempt replaces old atomically");
        check(h.auth.poll(first, true).state == NativeAuth.State.CANCELLED && h.wire.calls.size() == 2, "old result cannot overwrite new attempt");
        h.auth.cancel(first); check(!next.isCancelled(), "old cancel leaves new attempt active");
    }
    private static void ownership() throws Exception {
        Harness first = new Harness(), second = new Harness(); NativeAuth.QrSession s = first.begin(); second.begin();
        expectIllegal(() -> second.auth.poll(s, true)); expectIllegal(() -> second.auth.cancel(s));
    }
    private static void malformed() throws Exception {
        for (String text : Arrays.asList("callback({\"code\":0});", "{\"code\":0,\"result\":[]} trailing", "{\"result\":[]}", "{\"code\":0.1,\"result\":[]}", "{\"code\":2147483648,\"result\":[]}", "{", "[]")) {
            Wire wire = new Wire(); wire.add(response(text)); NativeAuth auth = new NativeAuth("bbs.nga.cn", wire, new TestClock());
            expectIo(auth::begin); check(wire.calls.size() == 1, "invalid envelope does not proceed");
        }
        for (byte[] bytes : Arrays.asList(new byte[]{(byte) 0xc3, (byte) 0x28}, new byte[1024 * 1024 + 1])) {
            Wire wire = new Wire(); wire.add(new NativeAuth.Response(200, bytes, "application/json;charset=UTF-8", Collections.emptyMap()));
            expectIo(() -> new NativeAuth("bbs.nga.cn", wire, new TestClock()).begin());
        }
        Wire wire = new Wire(); wire.add(response("\ufeff" + read("generate")));
        NativeAuth auth = new NativeAuth("bbs.nga.cn", wire, new TestClock()); check(!auth.begin().qrText().isEmpty(), "UTF-8 BOM accepted");
    }
    private static void redaction() throws Exception {
        Harness h = new Harness(); NativeAuth.QrSession s = h.begin();
        h.wire.add(response("{\"code\":1,\"msg\":\"错误 qrkey=" + KEY + " hiddenkey=" + HIDDEN + " uid=123 token=private-value <b>重试</b>\"}")); h.time.advance(3000);
        NativeAuth.PollResult result = h.auth.poll(s, false);
        check(result.state == NativeAuth.State.ERROR && !result.message.contains(KEY) && !result.message.contains(HIDDEN) && !result.message.contains("123") && !result.message.contains("private-value"), "server diagnostic credentials redacted");
        check(result.message.contains("重试") && !result.message.contains("<b>"), "readable message retained without markup");
    }
    private static void incompleteGrant() throws Exception {
        for (String result : Arrays.asList("[]", "[1]", "[0,0,0,{\"uid\":\"123\"}]", "[0,0,0,{\"uid\":\"0\",\"token\":\"fixture-grant\"}]")) {
            Harness h = new Harness(); NativeAuth.QrSession s = h.begin(); h.wire.add(response("{\"code\":0,\"result\":" + result + "}")); h.time.advance(3000);
            check(h.auth.poll(s, false).state == NativeAuth.State.ERROR && h.wire.calls.size() == 2, "missing grant does not imply success");
        }
    }
    private static void largeCookies() throws Exception {
        Harness h = new Harness(); NativeAuth.QrSession s = h.begin(); List<String> cookies = new ArrayList<>();
        char[] fill = new char[8192]; Arrays.fill(fill, 'x');
        for (int i = 0; i < 9; i++) cookies.add("fixture" + i + "=" + new String(fill) + "; Path=/");
        h.wire.add(withCookies(fixture("pending"), cookies)); h.time.advance(3000); h.auth.poll(s, false); h.time.advance(3000);
        NativeAuth.PollResult result = h.auth.poll(s, false);
        check(result.state == NativeAuth.State.ERROR && h.wire.calls.size() == 2, "oversized cookie header never transmitted");
    }
    private static void finalCookies() throws Exception {
        Harness rotated = new Harness(); NativeAuth.QrSession first = rotated.begin(); queueGrant(rotated, false);
        rotated.wire.add(withCookies(fixture("valid"), Collections.singletonList("ngaPassportCid=fixture-rotated-session; Path=/; Secure")));
        rotated.time.advance(3000); NativeAuth.PollResult success = rotated.auth.poll(first, false);
        check(success.state == NativeAuth.State.SUCCESS && success.cookieHeader.contains("fixture-rotated-session") && !success.cookieHeader.contains(SESSION), "latest server issued session saved");
        Harness deleted = new Harness(); NativeAuth.QrSession second = deleted.begin(); queueGrant(deleted, false);
        deleted.wire.add(withCookies(fixture("valid"), Collections.singletonList("ngaPassportCid=; Path=/; Max-Age=0")));
        deleted.time.advance(3000); NativeAuth.PollResult rejected = deleted.auth.poll(second, false);
        check(rejected.state == NativeAuth.State.ERROR && rejected.cookieHeader.isEmpty(), "deleted session never restored from stale snapshot");
    }
    private static void diagnostics() throws Exception {
        Harness pending = new Harness(); check(pending.auth.safeDiagnostics().contains("stage=IDLE"), "new owner diagnostics have no attempted request");
        NativeAuth.QrSession first = pending.begin(); String generated = pending.auth.safeDiagnostics();
        check(generated.contains("stage=GENERATE") && generated.contains("http=200") && generated.contains("resultType=array"), "generation format metadata captured");
        noSecrets(generated);
        pending.wire.add(fixture("pending")); pending.time.advance(3000); pending.auth.poll(first, false);
        String wait = pending.auth.safeDiagnostics();
        check(wait.contains("stage=POLL") && wait.contains("state=WAITING") && wait.contains("code=1") && wait.contains("msg:string"), "waiting status and known type captured");
        check(!wait.contains("未找到登录许可"), "diagnostics do not copy even readable server text"); noSecrets(wait);
        pending.auth.close(); check(pending.auth.safeDiagnostics().contains("closed=true") && pending.auth.safeDiagnostics().contains("cancelled=true"), "cancellation separately visible");
        Harness success = new Harness(); NativeAuth.QrSession second = success.begin(); queueGrant(success, false); success.wire.add(fixture("valid")); success.time.advance(3000);
        check(success.auth.poll(second, false).state == NativeAuth.State.SUCCESS, "diagnostics do not change positive authorization");
        String done = success.auth.safeDiagnostics();
        check(done.contains("stage=VERIFY") && done.contains("state=SUCCESS") && done.contains("grantReceived=true") && done.contains("exchangeValidated=true") && done.contains("verificationRecognized=true"), "positive stages confirmed only with protocol evidence");
        check(done.contains("passportPairAtResponse=true") && done.contains("acceptedNames=ngaPassportUid,ngaPassportCid"), "only known cookie names and pair flags exposed"); noSecrets(done);
        success.auth.close(); check(success.auth.safeDiagnostics().contains("state=SUCCESS"), "close preserves last terminal protocol diagnosis"); noSecrets(success.auth.safeDiagnostics());
        Harness invalid = new Harness(); NativeAuth.QrSession third = invalid.begin();
        invalid.wire.add(response("{\"code\":0,\"result\":[0,0,0,{\"uid\":123,\"token\":null}]}")); invalid.time.advance(3000);
        check(invalid.auth.poll(third, false).state == NativeAuth.State.ERROR, "incomplete typed grant fails closed");
        String types = invalid.auth.safeDiagnostics();
        check(types.contains("grantUidPresent=true") && types.contains("grantUidType=number") && types.contains("grantTokenPresent=false") && types.contains("grantTokenType=null") && types.contains("grantReceived=false"), "grant type and presence recorded without values"); noSecrets(types);
        Harness arbitrary = new Harness(); NativeAuth.QrSession fourth = arbitrary.begin();
        arbitrary.wire.add(response("{\"code\":1,\"msg\":\"" + KEY + " " + HIDDEN + " " + SESSION + "\",\"123\":\"" + GRANT + "\",\"result\":{\"123\":\"" + SESSION + "\"}}")); arbitrary.time.advance(3000);
        arbitrary.auth.poll(fourth, false); String metadata = arbitrary.auth.safeDiagnostics();
        check(metadata.contains("resultType=object") && !metadata.contains("123:"), "dynamic object keys never enumerated"); noSecrets(metadata);
        check(metadata.length() < 1600, "diagnostic snapshot fixed bounded size");
        Harness positioned = new Harness(); NativeAuth.QrSession fifth = positioned.begin();
        positioned.wire.add(response("{\"code\":1,\"result\":[\"" + KEY + "\",0,0,{\"uid\":\"0\",\"token\":\"" + GRANT + "\"}],\"msg\":\"" + HIDDEN + "\",\"message\":\"" + SESSION + "\"}"));
        positioned.time.advance(3000); positioned.auth.poll(fifth, false); String placed = positioned.auth.safeDiagnostics();
        check(placed.contains("result0Type=string") && placed.contains("result3Type=object") && placed.contains("grantTokenType=string") && placed.contains("message:string"), "all credential carrying positions reduced to types"); noSecrets(placed);
    }
    private static void noSecrets(String value) {
        check(!value.contains(KEY) && !value.contains(HIDDEN) && !value.contains(GRANT) && !value.contains(SESSION), "diagnostic excludes all synthetic credentials");
        check(!value.contains("uid=123") && !value.contains("ngaPassportUid=123") && !value.contains("https://") && !value.contains("/nuke.php"), "diagnostic excludes credential values and request URLs");
    }
    private static Harness prepared() { return new Harness(); }
    private static void queueGrant(Harness h, boolean access) throws Exception { h.wire.add(fixture("approved")); h.wire.add(withCookies(fixture("exchange"), sessionCookies(access, "123"))); }
    private static List<String> sessionCookies(boolean access, String uid) {
        return Arrays.asList((access ? "access_uid=" : "ngaPassportUid=") + uid + "; Path=/; Secure; HttpOnly",
                (access ? "access_token=" : "ngaPassportCid=") + SESSION + "; Path=/; Secure; HttpOnly", "unrelated=value; Path=/");
    }
    private static NativeAuth.Response fixture(String name) throws IOException { return response(read(name)); }
    private static String read(String name) throws IOException { return new String(Files.readAllBytes(fixtures.resolve(name + ".json")), StandardCharsets.UTF_8); }
    private static NativeAuth.Response response(String text) { return new NativeAuth.Response(200, text.getBytes(StandardCharsets.UTF_8), "text/json;charset=UTF-8", Collections.emptyMap()); }
    private static NativeAuth.Response withCookies(NativeAuth.Response response, List<String> cookies) {
        Map<String, List<String>> headers = new LinkedHashMap<>(); headers.put("Set-Cookie", cookies);
        return new NativeAuth.Response(response.status, response.body, response.contentType, headers);
    }
    private static void scenario(String name, Checked action) throws Exception {
        try { action.run(); scenarios++; } catch (Throwable error) { throw new AssertionError("Scenario failed: " + name, error); }
    }
    private static void check(boolean condition, String name) { checks++; if (!condition) throw new AssertionError(name); }
    private static void expectIo(Checked action) throws Exception { try { action.run(); } catch (IOException expected) { checks++; return; } throw new AssertionError("Expected safe IOException"); }
    private static void expectIllegal(Checked action) throws Exception { try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; } throw new AssertionError("Expected invalid argument rejection"); }
    private interface Checked { void run() throws Exception; }
    private static final class TestClock implements NativeAuth.Clock { long value; public long now() { return value; } void advance(long elapsed) { value += elapsed; } }
    private static final class Harness {
        final TestClock time = new TestClock(); final Wire wire = new Wire(); final NativeAuth auth;
        final NativeAuth.Recovery recovery;
        Harness() { this(null); }
        Harness(NativeAuth.Recovery recovery) { this.recovery = recovery; auth = new NativeAuth("bbs.nga.cn", wire, time, recovery); }
        NativeAuth.QrSession begin() throws Exception { wire.responses.addFirst(fixture("generate")); return auth.begin(); }
    }
    private static final class MemoryRecovery implements NativeAuth.Recovery {
        String state = ""; int clears; boolean fail, clearFail;
        @Override public void checkpoint(String value) throws IOException { if (fail) throw new IOException("fixture disk failure"); state = value; }
        @Override public void clear() throws IOException {
            clears++; if (clearFail) throw new IOException("fixture cleanup failure"); state = "";
        }
    }
    private static final class EncryptedMemory implements NativeQrRecoveryStore.Storage,
            NativeQrRecoveryStore.KeyProvider, NativeQrRecoveryStore.TimeSource {
        NativeQrRecoveryStore.Record record = NativeQrRecoveryStore.Record.EMPTY;
        long wall = 1792300000000L, elapsed = 100000;
        private final SecretKey key = new SecretKeySpec(new byte[16], "AES");
        @Override public NativeQrRecoveryStore.Record read() { return record; }
        @Override public void write(NativeQrRecoveryStore.Record value) { record = value; }
        @Override public SecretKey get() { return key; }
        @Override public long wall() { return wall; }
        @Override public long elapsed() { return elapsed; }
        void advance(long millis) { wall += millis; elapsed += millis; }
    }
    private static final class Call {
        final URL url; final String form, cookie;
        Call(URL url, String form, String cookie) { this.url = url; this.form = form; this.cookie = cookie; }
    }
    private static final class Wire implements NativeAuth.Transport {
        final ArrayDeque<NativeAuth.Response> responses = new ArrayDeque<>(); final List<Call> calls = new ArrayList<>();
        Runnable beforeResponse; boolean fail;
        void add(NativeAuth.Response response) { responses.addLast(response); }
        void addFailure() { fail = true; }
        @Override public NativeAuth.Response post(URL url, String form, String cookie, NativeAuth.QrSession session) throws IOException {
            calls.add(new Call(url, form, cookie));
            if (beforeResponse != null) { Runnable callback = beforeResponse; beforeResponse = null; callback.run(); }
            if (responses.isEmpty() && fail) { fail = false; throw new IOException("fixture simulated network failure"); }
            if (responses.isEmpty()) throw new AssertionError("Unexpected protocol request");
            return responses.removeFirst();
        }
    }
}
