package cn.ngapure.app;

import org.json.JSONObject;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Synthetic protocol checks. No sockets, account access, SMS or CAPTCHA solving. */
public final class CredentialAuthTest {
    private static int checks, scenarios;
    private static Path fixtures;
    private CredentialAuthTest() { }
    public static void main(String[] args) throws Exception {
        fixtures = Paths.get(args[0]);
        scenario(() -> {
            Field field = NativeCredentialAuth.class.getDeclaredField("PUBLIC_KEY"); field.setAccessible(true);
            String key = (String) field.get(null); JSONObject source = new JSONObject(file("official-protocol-source.json"));
            check(hex(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(key))).equals(source.getString("publicKeyDerSha256")), "official public key matches freshly inspected source");
            String a = NativeCredentialAuth.encrypt(" synthetic password ".toCharArray()), b = NativeCredentialAuth.encrypt("synthetic password".toCharArray());
            check(Base64.getDecoder().decode(a).length == 256, "RSA2048 ciphertext"); check(!a.equals(b), "PKCS1 randomized padding");
            expectFailure(() -> NativeCredentialAuth.encrypt(new char[0]));
            expectFailure(() -> NativeCredentialAuth.encrypt(new char[246]));
        });
        scenario(() -> {
            for (String host : Arrays.asList("http://bbs.nga.cn", "evil.example", "bbs.nga.cn.evil.example", "bbs.nga.cn:443", "bbs.nga.cn@evil.example")) {
                boolean rejected = false; try { new NativeCredentialAuth(host); } catch (IllegalArgumentException expected) { rejected = true; } check(rejected, "strict login host");
            }
            new NativeCredentialAuth("NGA.178.COM").close(); check(true, "known host normalization");
        });
        scenario(() -> {
            String[][] samples = {{"玩家", ""}, {"name@example.test", "mail"}, {"123456", "id"}, {"13800000000", "phone"}, {"86-13800000000", "phone"}};
            for (String[] sample : samples) check(NativeCredentialAuth.accountType(sample[0]).equals(sample[1]), "official account type inference");
        });
        scenario(() -> {
            Fake f = new Fake(); f.image(); f.json("password-grant.json"); Bridge b = new Bridge();
            NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, b);
            check(auth.imageChallenge().image.length == 8, "captcha bytes only in memory");
            char[] secret = "SYNTHETIC_password".toCharArray(); NativeCredentialAuth.Result result = auth.passwordLogin("user@example.test", secret, "ABC123", "");
            check(result.state == NativeCredentialAuth.State.SUCCESS, "grant exchange result"); check(b.calls == 1, "verified bridge required"); check(zero(secret), "password array cleared");
            Call get = f.calls.get(0), post = f.calls.get(1);
            check(get.method.equals("GET") && get.url.getHost().equals("bbs.nga.cn") && get.url.getPath().equals("/login_check_code.php"), "same host captcha");
            check(get.url.getQuery().matches("id=login[0-9]+&from=login"), "official captcha ID/from");
            check(post.fields.get("rid").equals(get.url.getQuery().split("[=&]")[1]), "captcha bound to shown image");
            check("login".equals(post.fields.get("__act")) && "14".equals(post.fields.get("__output")), "JSON password endpoint");
            check("mail".equals(post.fields.get("type")) && "5004".equals(post.fields.get("app_id")), "official browser adapter values");
            check(post.fields.get("prid").startsWith("P") && "ABC123".equals(post.fields.get("captcha")), "captcha page binding");
            check(Base64.getDecoder().decode(post.fields.get("password")).length == 256, "only ciphertext transmitted");
            check(!post.cookie.contains("ngaPassport"), "no old account cookie imported");
            check(!result.toString().contains(result.cookieHeader), "sensitive result not stringified");
            auth.close();
        });
        scenario(() -> {
            Fake f = new Fake(); Bridge b = new Bridge(); NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, b);
            char[] secret = "secret".toCharArray(); check(auth.passwordLogin("user", secret, "ABC123", "").state == NativeCredentialAuth.State.VERIFY_REQUIRED, "no login without image challenge");
            check(f.calls.isEmpty() && b.calls == 0 && zero(secret), "missing challenge sends no credentials"); auth.close();
        });
        scenario(() -> {
            Fake f = new Fake(); f.image(); f.json("match-phone.json"); NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, new Bridge()); auth.imageChallenge();
            check(auth.passwordLogin("user", "secret".toCharArray(), "ABC123", "").state == NativeCredentialAuth.State.MATCH_PHONE, "official explicit MATCH_PHONE challenge");
            check(auth.passwordLogin("user", "secret".toCharArray(), "ABC123", "").state == NativeCredentialAuth.State.VERIFY_REQUIRED && f.calls.size() == 2, "image is single submission"); auth.close();
        });
        scenario(() -> {
            Fake f = new Fake(); f.image(); f.json("rejected.json"); NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, new Bridge()); auth.imageChallenge();
            check(auth.passwordLogin("user", "secret".toCharArray(), "ABC123", "13800000000").state == NativeCredentialAuth.State.ERROR, "password rejection not success");
            check("13800000000".equals(f.calls.get(1).fields.get("MATCH_PHONE")), "explicit extra input only after user supplies"); auth.close();
        });
        scenario(() -> {
            Fake f = new Fake(); f.json("sms-sent.json"); NativeCredentialAuth auth = new NativeCredentialAuth("ngabbs.com", f, new Bridge());
            check(auth.sendSms("13800000000", "", "SYNTHETIC_ticket_valid", "SYNTHETIC_rand").state == NativeCredentialAuth.State.SMS_SENT, "SMS sent only explicit request");
            Call call = f.calls.get(0); check("smsloginsendcode".equals(call.fields.get("__act")), "official SMS send action");
            check("phone".equals(call.fields.get("checktype")) && "13800000000".equals(call.fields.get("checkto")), "official SMS destination fields");
            check("smsloginsendcode_QQCAPTCHA_SYNTHETIC_rand".equals(call.fields.get("rid")), "official ticket/random binding");
            check("SYNTHETIC_ticket_valid".equals(call.fields.get("captcha")) && !call.fields.containsKey("password"), "ticket sent to NGA without password");
            check(auth.sendSms("13800000000", "", "SYNTHETIC_ticket_valid", "SYNTHETIC_rand").state == NativeCredentialAuth.State.ERROR && f.calls.size() == 1, "15 second resend guard"); auth.close();
        });
        scenario(() -> {
            for (String ticket : Arrays.asList("", "terror_invalid", "trerror_invalid", "ticket\ninvalid", "short")) {
                Fake f = new Fake(); Bridge b = new Bridge(); NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, b);
                check(auth.sendSms("13800000000", "", ticket, "rand").state == NativeCredentialAuth.State.VERIFY_REQUIRED && f.calls.isEmpty(), "invalid ticket cannot send SMS");
                check(b.calls == 0, "invalid ticket never redeems a grant"); auth.close();
            }
            check(!NativeCredentialAuth.validTicket("SYNTHETIC_ticket", "evil&phone=1"), "ticket random no field injection");
        });
        scenario(() -> {
            for (String name : Arrays.asList("123456", "synthetic-user")) {
                Fake f = new Fake(); f.json("sms-sent.json"); NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, new Bridge());
                check(auth.sendSms("13800000000", name, "SYNTHETIC_ticket", "rand").state == NativeCredentialAuth.State.SMS_SENT, "explicit account supplied to SMS resend");
                check(name.equals(f.calls.get(0).fields.get("name")) && (name.matches("[0-9]+") ? "id" : "").equals(f.calls.get(0).fields.get("type")), "SMS account ambiguity resolved in send action only"); auth.close();
            }
        });
        scenario(() -> {
            String[] messages = {"用户id错误", "用户ID错误，请输入账号", "用户名错误", " <b>用户 ID 错误</b> ",
                    "此账号不能仅凭手机号登录 需要输入 用户名或用户ID 后方可继续",
                    "此账号不能仅用手机号登录，请填写 UID", "不能只用手机号登录，请补充用户名"};
            for (String message : messages) for (int source = 0; source < 4; source++) {
                JSONObject response = new JSONObject().put("code", -1);
                if (source == 0) response.put("msg", message);
                else if (source == 1) response.put("message", message);
                else if (source == 2) response.put("msg", "").put("error", new org.json.JSONArray().put(message));
                else response.put("msg", " ").put("message", "").put("error", new JSONObject().put("0", message));
                Fake f = new Fake(); f.raw(response.toString()); Bridge b = new Bridge();
                NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, b);
                NativeCredentialAuth.Result result = auth.sendSms("13800000000", "", "SYNTHETIC_ticket", "rand");
                check(result.state == NativeCredentialAuth.State.ACCOUNT_REQUIRED, "official identity refusal offers explicit account completion");
                check(result.cookieHeader.isEmpty() && b.calls == 0 && f.calls.size() == 1, "identity requirement neither authenticates nor automatically retries");
                check("".equals(f.calls.get(0).fields.get("name")) && "".equals(f.calls.get(0).fields.get("type")), "unspecified SMS account remains unspecified");
                check(auth.smsRetryAfterMillis() > 0 && auth.smsRetryAfterMillis() <= 15000, "identity refusal retains local duplicate-send cooldown");
                auth.close();
            }
        });
        scenario(() -> {
            String[] messages = {"手机号格式错误", "该手机号尚未绑定账号", "请输入用户名或用户 ID", "此账号不能仅凭手机号登录", "验证码错误，请重试"};
            for (String message : messages) {
                Fake f = new Fake(); f.raw(new JSONObject().put("code", -1).put("msg", message).toString());
                NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, new Bridge());
                NativeCredentialAuth.Result result = auth.sendSms("13800000000", "", "SYNTHETIC_ticket_valid", "rand");
                check(result.state == (message.contains("验证码") ? NativeCredentialAuth.State.VERIFY_REQUIRED : NativeCredentialAuth.State.ERROR), "unrelated failures do not become identity requirements");
                check(f.calls.size() == 1, "generic rejection never retries SMS"); auth.close();
            }
            Fake f = new Fake(); f.raw("{\"code\":-1,\"msg\":\"服务暂不可用\",\"message\":\"用户id错误\",\"error\":[\"用户名错误\"]}");
            NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, new Bridge());
            check(auth.sendSms("13800000000", "", "SYNTHETIC_ticket", "rand").state == NativeCredentialAuth.State.ERROR, "primary nonempty error message takes precedence over fallback identity text"); auth.close();
            Fake alternate = new Fake(); alternate.raw("{\"code\":-1,\"msg\":\" \",\"message\":\"服务暂不可用\",\"error\":{\"0\":\"用户名错误\"}}");
            NativeCredentialAuth alternateAuth = new NativeCredentialAuth("bbs.nga.cn", alternate, new Bridge());
            check(alternateAuth.sendSms("13800000000", "", "SYNTHETIC_ticket", "rand").state == NativeCredentialAuth.State.ERROR, "secondary nonempty message precedes numeric-key fallback"); alternateAuth.close();
        });
        scenario(() -> {
            for (String name : Arrays.asList(" 123456 ", " synthetic-user ")) {
                Fake f = new Fake(); f.raw("{\"code\":-1,\"error\":[\"用户ID错误\"]}"); Bridge b = new Bridge();
                NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, b);
                check(auth.sendSms("13800000000", name, "SYNTHETIC_ticket", "rand").state == NativeCredentialAuth.State.ERROR, "rejected supplied account is not another missing-account prompt");
                Call call = f.calls.get(0);
                check(name.trim().equals(call.fields.get("name")) && (name.trim().matches("[0-9]+") ? "id" : "").equals(call.fields.get("type")), "supplemental identity uses precise trimmed name and official type");
                check("13800000000".equals(call.fields.get("checkto")) && "phone".equals(call.fields.get("checktype")), "supplemental account preserves SMS destination");
                check(f.calls.size() == 1 && b.calls == 0, "wrong supplied identity does not retry or exchange a grant"); auth.close();
            }
        });
        scenario(() -> {
            for (String message : Arrays.asList("用户id错误", "用户名错误", "此账号不能仅凭手机号登录，需要输入用户名或用户ID后方可继续")) {
                Fake f = new Fake(); f.image(); f.raw(new JSONObject().put("code", -1).put("error", new org.json.JSONArray().put(message)).toString());
                Bridge b = new Bridge(); NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, b); auth.imageChallenge();
                char[] password = "SYNTHETIC_password".toCharArray();
                check(auth.passwordLogin("synthetic-user", password, "ABC123", "").state == NativeCredentialAuth.State.ERROR, "password refusal does not enter SMS identity completion");
                check(zero(password) && f.calls.size() == 2 && b.calls == 0, "password refusal consumes secret without another request"); auth.close();
            }
        });
        scenario(() -> {
            for (String message : Arrays.asList("用户id错误", "用户名错误", "此账号不能仅凭手机号登录，需要输入用户名或用户ID后方可继续")) {
                Fake f = new Fake(); f.raw(new JSONObject().put("code", -1).put("error", new JSONObject().put("0", message)).toString());
                NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, new Bridge()); char[] code = "123456".toCharArray();
                check(auth.smsLogin("13800000000", code).state == NativeCredentialAuth.State.ERROR, "SMS login refusal is not a request to resend automatically");
                check(zero(code) && f.calls.size() == 1 && "smslogin".equals(f.calls.get(0).fields.get("__act")), "rejected code is consumed and never sends SMS");
                check("13800000000".equals(f.calls.get(0).fields.get("name")) && "phone".equals(f.calls.get(0).fields.get("type")), "SMS login preserves official phone-only identity shape"); auth.close();
            }
        });
        scenario(() -> {
            for (String response : Arrays.asList("{\"code\":0}", "{\"code\":0,\"result\":[]}", "{\"code\":0,\"result\":[\" \" ]}",
                    "{\"code\":0,\"result\":[true]}", "{\"code\":0,\"result\":[0]}", "{\"code\":0,\"result\":{\"0\":null}}",
                    "{\"code\":0,\"result\":[],\"error\":[\"用户id错误\"]}")) {
                Fake f = new Fake(); f.raw(response); Bridge b = new Bridge(); NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, b);
                expectFailure(() -> auth.sendSms("13800000000", "", "SYNTHETIC_ticket", "rand"));
                check(f.calls.size() == 1 && b.calls == 0, "code zero without explicit SMS acknowledgement is uncertain, not successful");
                check(auth.smsRetryAfterMillis() > 0, "uncertain SMS acknowledgement retains local cooldown"); auth.close();
            }
        });
        scenario(() -> {
            for (String name : Arrays.asList("123456", "synthetic-user")) {
                Fake f = new Fake(); f.raw("{\"code\":0,\"result\":[\"SYNTHETIC SMS accepted\"]}");
                f.raw("{\"code\":0,\"result\":[null,null,null,{\"uid\":12345,\"token\":\"SYNTHETIC_grant_token_12345\"}]}");
                Bridge b = new Bridge(); NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, b);
                check(auth.sendSms("13800000000", name, "SYNTHETIC_ticket", "rand").state == NativeCredentialAuth.State.SMS_SENT, "explicit identity resolves only the send request");
                check(name.equals(f.calls.get(0).fields.get("name")) && (name.matches("[0-9]+") ? "id" : "").equals(f.calls.get(0).fields.get("type")), "selected account is carried exactly in SMS sending");
                char[] code = "123456".toCharArray(); check(auth.smsLogin("13800000000", code).state == NativeCredentialAuth.State.SUCCESS, "supplemental account does not change verified SMS grant exchange");
                Call login = f.calls.get(1);
                check("smslogin".equals(login.fields.get("__act")) && "13800000000".equals(login.fields.get("name")) && "phone".equals(login.fields.get("type")), "final SMS login still uses phone and type phone");
                check(!login.fields.containsKey("checkto") && !login.fields.containsKey("captcha") && zero(code) && b.calls == 1 && f.calls.size() == 2, "SMS submission adds no send or CAPTCHA operation"); auth.close();
            }
        });
        scenario(() -> {
            for (String response : Arrays.asList("{\"code\":0,\"result\":[\"SYNTHETIC SMS accepted\"]}",
                    "{\"code\":-1,\"msg\":\"用户id错误\"}", "{\"code\":-1,\"msg\":\"服务暂不可用\"}",
                    "{\"code\":-1,\"msg\":\"验证码错误\"}", "{\"code\":0,\"result\":[]}", "not JSON")) {
                Fake f = new Fake(); f.raw(response); Bridge b = new Bridge(); NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, b);
                check(auth.smsRetryAfterMillis() == 0, "fresh owner has no local SMS cooldown");
                try { auth.sendSms("13800000000", "", "SYNTHETIC_ticket", "rand"); }
                catch (IOException expected) { /* Uncertain/malformed replies are still locally throttled. */ }
                check(auth.smsRetryAfterMillis() > 0 && auth.smsRetryAfterMillis() <= 15000, "all attempted SMS replies start a bounded local cooldown");
                check(auth.sendSms("13800000000", "synthetic-user", "SYNTHETIC_new_ticket", "newrand").state == NativeCredentialAuth.State.ERROR, "explicit immediate retry is throttled even after identity completion");
                check(f.calls.size() == 1 && b.calls == 0, "retry during cooldown performs no extra transport or authentication"); auth.close();
            }
        });
        scenario(() -> {
            for (boolean unavailable : new boolean[]{false, true}) {
                Fake f = new Fake();
                if (unavailable) f.queue.add(new NativeCredentialAuth.Response(503, new byte[0], "", Collections.emptyMap()));
                NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, new Bridge());
                expectFailure(() -> auth.sendSms("13800000000", "", "SYNTHETIC_ticket", "rand"));
                check(auth.smsRetryAfterMillis() > 0, "unavailable or uncertain transport is locally throttled");
                check(auth.sendSms("13800000000", "synthetic-user", "SYNTHETIC_new_ticket", "newrand").state == NativeCredentialAuth.State.ERROR && f.calls.size() == 1, "transport failure does not automatically or immediately resend"); auth.close();
            }
        });
        scenario(() -> {
            Fake f = new Fake(); f.raw("{\"code\":-1,\"msg\":\"此账号不能仅凭手机号登录，需要输入用户名或用户ID后方可继续\"}");
            f.raw("{\"code\":0,\"result\":[\"SYNTHETIC SMS accepted\"]}");
            NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, new Bridge());
            check(auth.sendSms("13800000000", "", "SYNTHETIC_ticket", "rand").state == NativeCredentialAuth.State.ACCOUNT_REQUIRED, "phone-only refusal is retained until an explicit corrected send");
            Field last = NativeCredentialAuth.class.getDeclaredField("lastSmsAt"); last.setAccessible(true);
            last.setLong(auth, System.nanoTime() / 1_000_000L - 15001);
            check(auth.smsRetryAfterMillis() == 0 && f.calls.size() == 1, "expiring local cooldown alone never sends another SMS");
            check(auth.sendSms("13800000000", "123456", "SYNTHETIC_new_ticket", "newrand").state == NativeCredentialAuth.State.SMS_SENT, "corrected identity is sent only by a fresh explicit call after cooldown");
            Call retry = f.calls.get(1);
            check("123456".equals(retry.fields.get("name")) && "id".equals(retry.fields.get("type")) && "13800000000".equals(retry.fields.get("checkto")) && "phone".equals(retry.fields.get("checktype")), "corrected retry preserves exact official identity and destination fields");
            check("SYNTHETIC_new_ticket".equals(retry.fields.get("captcha")) && "smsloginsendcode_QQCAPTCHA_newrand".equals(retry.fields.get("rid")) && f.calls.size() == 2, "explicit retry uses newly supplied verification values with no internal retry"); auth.close();
        });
        scenario(() -> {
            for (String phone : Arrays.asList("", "not-phone", "+8613800000000", "013800000000")) {
                Fake f = new Fake(); NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, new Bridge());
                expectFailure(() -> auth.sendSms(phone, "", "SYNTHETIC_ticket", "rand")); check(f.calls.isEmpty(), "invalid phone never sends"); auth.close();
            }
        });
        scenario(() -> {
            Fake f = new Fake(); f.json("password-grant.json"); Bridge b = new Bridge(); NativeCredentialAuth auth = new NativeCredentialAuth("nga.178.com", f, b);
            char[] code = "123456".toCharArray(); check(auth.smsLogin("13800000000", code).state == NativeCredentialAuth.State.SUCCESS, "SMS verification uses fresh grant exchange");
            check(zero(code) && "smslogin".equals(f.calls.get(0).fields.get("__act")), "SMS code cleared/no send operation");
            check("phone".equals(f.calls.get(0).fields.get("type")) && Base64.getDecoder().decode(f.calls.get(0).fields.get("password")).length == 256, "SMS code RSA same official key"); auth.close();
        });
        scenario(() -> {
            Fake f = new Fake(); f.raw("{\"code\":0,\"result\":[]}");
            NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, new Bridge());
            expectFailure(() -> auth.sendSms("13800000000", "", "SYNTHETIC_ticket", "rand")); auth.close();
        });
        scenario(() -> {
            for (String input : Arrays.asList("{\"code\":\"0\",\"result\":[]}", "{\"code\":true}", "[]", "{\"code\":0} trailing", "<html>challenge</html>")) {
                Fake f = new Fake(); f.raw(input); Bridge b = new Bridge(); NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, b); char[] code = "123456".toCharArray();
                expectFailure(() -> auth.smsLogin("13800000000", code)); check(b.calls == 0 && zero(code), "malformed response cannot authenticate"); auth.close();
            }
        });
        scenario(() -> {
            for (int status : new int[]{302, 403, 503}) {
                Fake f = new Fake(); f.queue.add(new NativeCredentialAuth.Response(status, new byte[0], "", Collections.emptyMap())); Bridge b = new Bridge();
                NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, b); char[] code = "123456".toCharArray();
                expectFailure(() -> auth.smsLogin("13800000000", code)); check(b.calls == 0 && f.calls.size() == 1 && zero(code), "no redirects/retries/error success"); auth.close();
            }
        });
        scenario(() -> {
            Fake f = new Fake(); f.json("malformed-grant.json"); Bridge b = new Bridge(); NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, b);
            expectFailure(() -> auth.smsLogin("13800000000", "123456".toCharArray())); check(b.calls == 0, "code0 alone is not login"); auth.close();
        });
        scenario(() -> {
            Fake f = new Fake(); f.json("password-grant.json"); NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, (uid, token) -> new NativeCredentialAuth.Result(NativeCredentialAuth.State.ERROR, "unverified", ""));
            check(auth.smsLogin("13800000000", "123456".toCharArray()).state == NativeCredentialAuth.State.ERROR, "grant without verified session rejected"); auth.close();
        });
        scenario(() -> {
            Fake f = new Fake(); NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, new Bridge()); auth.close();
            char[] code = "123456".toCharArray(); expectFailure(() -> auth.smsLogin("13800000000", code)); check(f.calls.isEmpty() && zero(code), "cancelled attempt cannot send or authenticate");
        });
        scenario(() -> {
            Fake f = new Fake(); Map<String, List<String>> headers = new LinkedHashMap<>();
            headers.put("Set-Cookie", Arrays.asList("challenge=synthetic; Domain=bbs.nga.cn; Secure; Path=/", "foreign=do_not_send; Domain=evil.example; Path=/"));
            f.queue.add(new NativeCredentialAuth.Response(200, png(), "image/png", headers)); f.json("rejected.json");
            NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, new Bridge()); auth.imageChallenge(); auth.passwordLogin("user", "secret".toCharArray(), "ABC123", "");
            check(f.calls.get(1).cookie.contains("challenge=synthetic") && !f.calls.get(1).cookie.contains("foreign"), "only new same-origin cookies"); auth.close();
        });
        scenario(() -> {
            Fake f = new Fake(); f.queue.add(new NativeCredentialAuth.Response(200, "<html>not image</html>".getBytes(StandardCharsets.UTF_8), "image/png", Collections.emptyMap()));
            NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, new Bridge()); expectFailure(auth::imageChallenge); auth.close();
            Fake large = new Fake(); large.queue.add(new NativeCredentialAuth.Response(200, new byte[NativeCredentialAuth.MAX_BYTES + 1], "application/json", Collections.emptyMap()));
            NativeCredentialAuth oversized = new NativeCredentialAuth("bbs.nga.cn", large, new Bridge()); expectFailure(() -> oversized.smsLogin("13800000000", "123456".toCharArray())); oversized.close();
        });
        scenario(() -> {
            for (NativeCredentialAuth.Response response : Arrays.asList(
                    new NativeCredentialAuth.Response(503, new byte[0], "", Collections.emptyMap()),
                    new NativeCredentialAuth.Response(200, png(), "text/plain", Collections.emptyMap()),
                    new NativeCredentialAuth.Response(200, "<html>not image</html>".getBytes(StandardCharsets.UTF_8), "image/png", Collections.emptyMap()))) {
                Fake f = new Fake(); f.image(); f.queue.add(response); Bridge b = new Bridge();
                NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, b);
                auth.imageChallenge(); expectFailure(auth::imageChallenge);
                char[] secret = "SYNTHETIC_password".toCharArray();
                check(auth.passwordLogin("user", secret, "ABC123", "").state == NativeCredentialAuth.State.VERIFY_REQUIRED,
                        "failed refresh cannot use previous or undisplayed image");
                check(f.calls.size() == 2 && b.calls == 0 && zero(secret), "failed image sends no password or grant request");
                auth.close();
            }
        });
        scenario(() -> {
            Fake f = new Fake(); f.image();
            f.queue.add(new NativeCredentialAuth.Response(200, png(), "text/html", Collections.emptyMap()));
            f.image(); f.json("password-grant.json"); Bridge b = new Bridge();
            NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, b);
            auth.imageChallenge(); expectFailure(auth::imageChallenge); auth.imageChallenge();
            check(auth.passwordLogin("user", "SYNTHETIC_password".toCharArray(), "ABC123", "").state == NativeCredentialAuth.State.SUCCESS,
                    "valid retry after failed image restores login");
            String freshId = f.calls.get(2).url.getQuery().split("[=&]")[1];
            check(freshId.equals(f.calls.get(3).fields.get("rid")) && b.calls == 1, "only successfully fetched replacement image binds submission");
            auth.close();
        });
        scenario(() -> {
            int[] calls = {0}; Bridge b = new Bridge();
            NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", (method, url, fields, cookie) -> {
                calls[0]++; throw new IOException("Synthetic connection loss");
            }, b);
            expectFailure(auth::imageChallenge);
            char[] secret = "SYNTHETIC_password".toCharArray();
            check(auth.passwordLogin("user", secret, "ABC123", "").state == NativeCredentialAuth.State.VERIFY_REQUIRED,
                    "transport failure cannot authorize undisplayed challenge");
            check(calls[0] == 1 && b.calls == 0 && zero(secret), "transport failure leaves password local");
            auth.close();
        });
        scenario(() -> {
            Fake f = new Fake(); f.image(); Bridge b = new Bridge();
            NativeCredentialAuth auth = new NativeCredentialAuth("bbs.nga.cn", f, b);
            auth.imageChallenge(); auth.discardImageChallenge();
            char[] secret = "SYNTHETIC_password".toCharArray();
            check(auth.passwordLogin("user", secret, "ABC123", "").state == NativeCredentialAuth.State.VERIFY_REQUIRED,
                    "native decode failure discards challenge");
            check(f.calls.size() == 1 && b.calls == 0 && zero(secret), "undecodable image sends no password");
            auth.close();
        });
        scenario(() -> {
            for (String url : Arrays.asList("https://turing.captcha.qcloud.com/TCaptcha.js", "https://turing.captcha.gtimg.com/1/a.js", "https://cloudcache.tencentcs.com/a.js")) check(NativeCaptchaDialog.allowedUrl(url), "verified official captcha resource");
            for (String url : Arrays.asList("https://bbs.nga.cn/nuke.php", "https://evil.example", "https://turing.captcha.qcloud.com.evil.example/x", "http://turing.captcha.qcloud.com/x", "file:///etc/passwd", "content://x", "https://user@turing.captcha.qcloud.com/", "https://turing.captcha.qcloud.com:8443/x", "javascript:alert(1)")) check(!NativeCaptchaDialog.allowedUrl(url), "captcha resource isolation");
        });
        scenario(() -> {
            String entry = "https://turing.captcha.qcloud.com/ngapure-local-captcha-v1.html";
            check(NativeCaptchaDialog.localDocument(entry), "fixed local main document served in memory");
            for (String value : Arrays.asList("data:text/html,anything", entry + "?untrusted=1", entry + "#fragment", "https://turing.captcha.qcloud.com/TCaptcha.js", "https://evil.example/ngapure-local-captcha-v1.html"))
                check(!NativeCaptchaDialog.localDocument(value), "only exact local document may bypass main-frame networking");
        });
        scenario(() -> {
            String ticket = "SYNTHETIC_ticket_valid", random = "SYNTHETIC_rand";
            for (boolean zeroError : new boolean[]{false, true}) {
                JSONObject callback = captchaCallback(0, ticket, random);
                if (zeroError) callback.put("errorCode", 0);
                NativeCaptchaDialog.Result result = NativeCaptchaDialog.parseResult(callback.toString());
                check(result.failure == null && ticket.equals(result.ticket) && random.equals(result.randstr), "valid callback preserves the verification result");
            }
            NativeCaptchaDialog.Result cancelled = NativeCaptchaDialog.parseResult(captchaCallback(2, "", "").toString());
            check(cancelled.failure == null && cancelled.ticket == null && cancelled.randstr == null, "SDK close is cancellation, not successful verification");
        });
        scenario(() -> {
            for (String prefix : Arrays.asList("terror_", "trerror_")) {
                captchaFailure(captchaCallback(0, prefix + "SYNTHETIC_error", "SYNTHETIC_rand").toString(), NativeCaptchaDialog.Failure.SDK_ERROR, "fallback error ticket is a terminal failure");
                captchaFailure(captchaCallback(0, prefix + "SYNTHETIC_error", "@SYNTHETIC_rand").toString(), NativeCaptchaDialog.Failure.SDK_ERROR, "SDK error random value cannot hide the fallback failure");
            }
            for (int code : new int[]{1001, 1002, 1003, 1045}) {
                JSONObject callback = captchaCallback(0, "SYNTHETIC_ticket_valid", "SYNTHETIC_rand").put("errorCode", code);
                captchaFailure(callback.toString(), code == 1001 ? NativeCaptchaDialog.Failure.LOAD_FAILED : NativeCaptchaDialog.Failure.SDK_ERROR, "nonzero SDK error cannot become success despite a valid-shaped ticket");
            }
            captchaFailure(captchaCallback(2, "", "").put("errorCode", 1002).toString(), NativeCaptchaDialog.Failure.SDK_ERROR, "SDK failure takes precedence over cancellation");
            for (int ret : new int[]{-1, 1, 3, 999})
                captchaFailure(captchaCallback(ret, "SYNTHETIC_ticket_valid", "SYNTHETIC_rand").toString(), NativeCaptchaDialog.Failure.SDK_ERROR, "unknown SDK result is a terminal failure");
        });
        scenario(() -> {
            for (String value : Arrays.asList(null, "", "null", "[]", "{}", "not JSON", "<html>error</html>",
                    "{\"ret\":0,\"ticket\":\"SYNTHETIC_ticket_valid\"}",
                    "{\"ret\":0,\"ticket\":\"SYNTHETIC_ticket_valid\",\"randstr\":\"rand\"} trailing",
                    "{\"ret\":\"0\",\"ticket\":\"SYNTHETIC_ticket_valid\",\"randstr\":\"rand\"}",
                    "{\"ret\":false,\"ticket\":\"SYNTHETIC_ticket_valid\",\"randstr\":\"rand\"}",
                    "{\"ret\":null,\"ticket\":\"SYNTHETIC_ticket_valid\",\"randstr\":\"rand\"}",
                    "{\"ret\":0.5,\"ticket\":\"SYNTHETIC_ticket_valid\",\"randstr\":\"rand\"}",
                    "{\"ret\":1e400,\"ticket\":\"SYNTHETIC_ticket_valid\",\"randstr\":\"rand\"}",
                    "{\"ret\":0,\"ticket\":true,\"randstr\":\"rand\"}",
                    "{\"ret\":0,\"ticket\":\"SYNTHETIC_ticket_valid\",\"randstr\":12}",
                    "{\"ret\":0,\"ticket\":null,\"randstr\":\"rand\"}",
                    "{\"ret\":0,\"ticket\":\"\",\"randstr\":\"\"}",
                    captchaCallback(0, "SYNTHETIC_ticket_valid", "rand").put("unknown", 0).toString(),
                    captchaCallback(0, "SYNTHETIC_ticket_valid", "rand").put("errorCode", 0).put("unknown", 0).toString()))
                captchaFailure(value, NativeCaptchaDialog.Failure.INVALID_RESULT, "malformed or ambiguous callback cannot become success");
            for (Object code : Arrays.asList("0", true, JSONObject.NULL, 0.5, "1001"))
                captchaFailure(captchaCallback(0, "SYNTHETIC_ticket_valid", "rand").put("errorCode", code).toString(), NativeCaptchaDialog.Failure.INVALID_RESULT, "malformed error code is a terminal invalid result");
        });
        scenario(() -> {
            for (String ticket : Arrays.asList("short", "ticket\ninjected", "ticket\rinjected", "ticket\"injected", "ticket\\injected", "ticket 中文"))
                captchaFailure(captchaCallback(0, ticket, "rand").toString(), NativeCaptchaDialog.Failure.INVALID_RESULT, "untrusted ticket cannot authorize verification");
            for (String random : Arrays.asList("", "@SYNTHETIC_rand", "rand&phone=1", "rand\ninjected", "rand/../x"))
                captchaFailure(captchaCallback(0, "SYNTHETIC_ticket_valid", random).toString(), NativeCaptchaDialog.Failure.INVALID_RESULT, "untrusted random value cannot authorize verification");
            String maximumTicket = repeated('t', 8192), maximumRandom = repeated('r', 512);
            NativeCaptchaDialog.Result boundary = NativeCaptchaDialog.parseResult(captchaCallback(0, maximumTicket, maximumRandom).toString());
            check(boundary.failure == null && maximumTicket.equals(boundary.ticket) && maximumRandom.equals(boundary.randstr), "bounded callback remains accepted at the documented input limits");
            captchaFailure(captchaCallback(0, maximumTicket + "t", "rand").toString(), NativeCaptchaDialog.Failure.INVALID_RESULT, "oversized ticket is rejected");
            captchaFailure(captchaCallback(0, "SYNTHETIC_ticket_valid", maximumRandom + "r").toString(), NativeCaptchaDialog.Failure.INVALID_RESULT, "oversized random value is rejected");
            captchaFailure(repeated(' ', 12001) + captchaCallback(0, "SYNTHETIC_ticket_valid", "rand"), NativeCaptchaDialog.Failure.INVALID_RESULT, "oversized callback body is rejected before parsing");
        });
        scenario(() -> bridge(false, false));
        scenario(() -> bridge(true, false));
        scenario(() -> bridge(false, true));
        System.out.println("PASS credential authorization: " + scenarios + " scenarios / " + checks + " assertions; network=0 sms=0 accountAccess=false");
    }
    private static void bridge(boolean wrongCookie, boolean unverified) throws Exception {
        List<String> actions = new ArrayList<>();
        NativeAuth auth = new NativeAuth("bbs.nga.cn", (url, form, cookie, session) -> {
            actions.add(url.toString());
            if (actions.size() == 1) {
                check(url.getQuery().contains("login_set_cookie_quick"), "credential grant uses verified official exchange");
                check(cookie.isEmpty(), "exchange begins without stored account");
                Map<String, List<String>> headers = new LinkedHashMap<>(); headers.put("Set-Cookie", Arrays.asList("ngaPassportUid=" + (wrongCookie ? "99999" : "12345") + "; Path=/; Secure", "ngaPassportCid=SYNTHETIC_new_cookie_12345; Path=/; Secure"));
                return new NativeAuth.Response(200, "{\"code\":0,\"result\":[\"success\"]}".getBytes(StandardCharsets.UTF_8), "application/json", headers);
            }
            check(form.contains("__act=iflogin"), "iflogin verification after exchange");
            return new NativeAuth.Response(200, (unverified ? "{\"code\":0,\"result\":[false]}" : "{\"code\":0,\"result\":[{\"uid\":12345}]}").getBytes(StandardCharsets.UTF_8), "application/json", Collections.emptyMap());
        }, () -> 10000);
        if (wrongCookie || unverified) expectFailure(() -> auth.completeCredentialGrant("12345", "SYNTHETIC_grant_token_12345"));
        else check(auth.completeCredentialGrant("12345", "SYNTHETIC_grant_token_12345").state == NativeAuth.State.SUCCESS, "success only after matching session verification");
        check(actions.size() == (wrongCookie ? 1 : 2), "bounded exchange/verify only, no QR polling"); auth.close();
    }
    private interface Action { void run() throws Exception; }
    private static void scenario(Action action) throws Exception { action.run(); scenarios++; }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static JSONObject captchaCallback(int ret, String ticket, String random) {
        return new JSONObject().put("ret", ret).put("ticket", ticket).put("randstr", random);
    }
    private static void captchaFailure(String value, NativeCaptchaDialog.Failure expected, String label) {
        NativeCaptchaDialog.Result result = NativeCaptchaDialog.parseResult(value);
        check(result.failure == expected, label);
        check(result.ticket == null && result.randstr == null, "failed callback retains no verification values");
    }
    private static String repeated(char value, int count) { char[] out = new char[count]; Arrays.fill(out, value); return new String(out); }
    private static void expectFailure(Action action) throws Exception { boolean rejected = false; try { action.run(); } catch (IOException expected) { rejected = true; } check(rejected, "expected protocol failure"); }
    private static boolean zero(char[] value) { for (char c : value) if (c != 0) return false; return true; }
    private static String file(String name) throws IOException { return new String(Files.readAllBytes(fixtures.resolve(name)), StandardCharsets.UTF_8); }
    private static byte[] png() { return new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}; }
    private static String hex(byte[] value) { StringBuilder out = new StringBuilder(); for (byte b : value) out.append(String.format("%02x", b & 255)); return out.toString(); }
    private static final class Call {
        final String method, cookie; final URL url; final Map<String, String> fields;
        Call(String method, URL url, Map<String, String> fields, String cookie) { this.method = method; this.url = url; this.fields = new LinkedHashMap<>(fields); this.cookie = cookie; }
    }
    private static final class Fake implements NativeCredentialAuth.Transport {
        final ArrayDeque<NativeCredentialAuth.Response> queue = new ArrayDeque<>(); final List<Call> calls = new ArrayList<>();
        void image() { queue.add(new NativeCredentialAuth.Response(200, png(), "image/png", Collections.emptyMap())); }
        void json(String fixture) throws IOException { raw(file(fixture)); }
        void raw(String value) { queue.add(new NativeCredentialAuth.Response(200, value.getBytes(StandardCharsets.UTF_8), "application/json", Collections.emptyMap())); }
        @Override public NativeCredentialAuth.Response request(String method, URL url, Map<String, String> fields, String cookie) throws IOException { calls.add(new Call(method, url, fields, cookie)); if (queue.isEmpty()) throw new IOException("synthetic transport exhausted"); return queue.removeFirst(); }
    }
    private static final class Bridge implements NativeCredentialAuth.GrantExchange {
        int calls;
        @Override public NativeCredentialAuth.Result redeem(String uid, String token) {
            calls++; check("12345".equals(uid) && token.equals("SYNTHETIC_grant_token_12345"), "only returned grant redeemed");
            return new NativeCredentialAuth.Result(NativeCredentialAuth.State.SUCCESS, "登录成功", "ngaPassportUid=12345; ngaPassportCid=SYNTHETIC_new_cookie");
        }
    }
}
