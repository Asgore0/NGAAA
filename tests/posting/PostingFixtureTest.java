package cn.ngapure.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static cn.ngapure.app.NativePosting.Kind;

/** All requests terminate in FakeTransport; this test never opens a socket. */
public final class PostingFixtureTest {
    private static int checks;
    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0, 1, 2, 3};
    private static final class FakeTransport implements NativePosting.Transport {
        final Queue<Object> replies = new ArrayDeque<>();
        final List<NativePosting.Request> requests = new ArrayList<>();
        FakeTransport add(String fixture) throws IOException { replies.add(response(fixture)); return this; }
        FakeTransport json(String text) { replies.add(new NativePosting.Response(200, text.getBytes(StandardCharsets.UTF_8), "application/json; charset=UTF-8")); return this; }
        FakeTransport raw(String text, int status) { replies.add(new NativePosting.Response(status, text.getBytes(StandardCharsets.UTF_8), "text/html; charset=UTF-8")); return this; }
        FakeTransport failure() { replies.add(new IOException("offline simulated timeout")); return this; }
        @Override public NativePosting.Response execute(NativePosting.Request request) throws IOException {
            requests.add(request);
            Object reply = replies.poll();
            if (reply == null) throw new AssertionError("Unexpected offline request");
            if (reply instanceof IOException) throw (IOException) reply;
            return (NativePosting.Response) reply;
        }
    }
    private interface Throwing { void run() throws Exception; }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static NativePosting.PostingException error(Kind kind, Throwing action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + kind); }
        catch (NativePosting.PostingException e) { check(e.kind == kind, "Expected " + kind + ", got " + e.kind + ": " + e.getMessage()); return e; }
    }
    private static String fixture(String name) throws IOException { return new String(Files.readAllBytes(Paths.get("tests/posting/fixtures/" + name)), StandardCharsets.UTF_8); }
    private static NativePosting.Response response(String name) throws IOException {
        boolean gb = name.contains("gb18030");
        return new NativePosting.Response(200, Files.readAllBytes(Paths.get("tests/posting/fixtures/" + name)), gb ? "text/javascript; charset=GBK" : "application/json; charset=UTF-8");
    }
    private static String dataVariant(String file, String key, Object value) throws Exception {
        JSONObject root = new JSONObject(fixture(file)); root.getJSONObject("data").put(key, value); return root.toString();
    }
    private static NativePosting.Prepare newPrepare(NativePosting api) throws Exception { return api.prepare("new", "7", "", ""); }
    private static Map<String, String> body(NativePosting.Request request) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream(); request.write(output);
        String form = new String(output.toByteArray(), StandardCharsets.US_ASCII);
        for (String pair : form.split("&")) {
            String[] kv = pair.split("=", 2); map.put(URLDecoder.decode(kv[0], "GB18030"), kv.length == 1 ? "" : URLDecoder.decode(kv[1], "GB18030"));
        }
        return map;
    }
    private static String multipart(NativePosting.Request r) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); r.write(output);
        check(output.size() == r.length(), "Multipart exact streaming length");
        return new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
    }
    private static void preparation() throws Exception {
        FakeTransport t = new FakeTransport().add("prepare-new.json");
        NativePosting api = new NativePosting(new NgaStore(), t);
        NativePosting.Prepare p = newPrepare(api);
        check(p.action.equals("new") && p.fid.equals("7") && p.tid.isEmpty() && p.pid.isEmpty(), "Prepared IDs");
        check(p.subject.isEmpty() && p.content.isEmpty() && p.getServerQuotedText().isEmpty(), "Empty new editor");
        check(p.forumName.equals("测试版面") && p.canUpload && !p.appendOnly, "Permission and uploader metadata");
        check(t.requests.size() == 1 && t.requests.get(0).method.equals("GET"), "Prepare performs GET only");
        check(t.requests.get(0).url.toString().equals("https://bbs.nga.cn/post.php?action=new&fid=7&__output=8"), "Known official prepare endpoint");
        check(t.requests.get(0).headers.get("Cookie").contains("offline-fixture-only"), "Existing session used");
        check(t.requests.get(0).length() == 0, "Prepare has no write body");
        error(Kind.VALIDATION, () -> api.prepare("delete", "7", "", ""));
        error(Kind.VALIDATION, () -> api.prepare("new", "", "", ""));
        error(Kind.PROTOCOL, () -> api.prepare("reply", "7", "1&step=2", ""));
        error(Kind.VALIDATION, () -> api.prepare("reply", "7", "", ""));
        FakeTransport anonymous = new FakeTransport().add("prepare-anonymous-gb18030.json");
        NativePosting anon = new NativePosting(new NgaStore(), anonymous);
        NativePosting.PostingException e = error(Kind.NOT_LOGGED_IN, () -> newPrepare(anon));
        check(e.getMessage().contains("你没有登录"), "Real anonymous GB18030 error fixture");
        NgaStore loggedOut = new NgaStore(); loggedOut.testCookie = "";
        error(Kind.NOT_LOGGED_IN, () -> newPrepare(new NativePosting(loggedOut, new FakeTransport())));
        NativePosting mismatch = new NativePosting(new NgaStore(), new FakeTransport().json(dataVariant("prepare-new.json", "action", "modify")));
        error(Kind.PROTOCOL, () -> newPrepare(mismatch));
        NativePosting wrongId = new NativePosting(new NgaStore(), new FakeTransport().json(dataVariant("prepare-quote.json", "pid", 790)));
        error(Kind.PROTOCOL, () -> wrongId.prepare("quote", "7", "456", "789"));
        NativePosting wrongUser = new NativePosting(new NgaStore(), new FakeTransport().json(dataVariant("prepare-new.json", "__CU", new JSONObject().put("uid", 999))));
        error(Kind.SESSION_CHANGED, () -> newPrepare(wrongUser));
        String[] unsafeEndpoints = {"https://evil.example/attach.php", "https://img8.nga.cn.evil.example/attach.php", "https://img8.nga.cn@evil.example/attach.php", "https://img8.nga.cn/attach.php?redirect=evil", "https://img8.nga.cn:444/attach.php", "file:///attach.php"};
        for (String endpoint : unsafeEndpoints) {
            NativePosting bad = new NativePosting(new NgaStore(), new FakeTransport().json(dataVariant("prepare-new.json", "attach_url", endpoint)));
            NativePosting.Prepare badPrepare = newPrepare(bad);
            check(!badPrepare.canUpload && !bad.preflight(badPrepare, "x.png", "image/png", 12).allowed, "Untrusted uploader denied");
        }
        NativePosting secureUpgrade = new NativePosting(new NgaStore(), new FakeTransport().json(dataVariant("prepare-new.json", "attach_url", "http://img8.nga.cn/attach.php")));
        check(newPrepare(secureUpgrade).canUpload, "Official legacy upload URL upgrades to HTTPS");
    }
    private static void quoteAndEdit() throws Exception {
        NativePosting quote = new NativePosting(new NgaStore(), new FakeTransport().add("prepare-quote.json"));
        NativePosting.Prepare q = quote.prepare("quote", "7", "456", "789");
        check(q.content.equals(q.getServerQuotedText()) && q.content.contains("[uid=321]他人[/uid]"), "Official quote preserves original author metadata");
        check(q.content.contains("原文 <内容> & \\ $"), "Official limited entity unescape");
        FakeTransport editTransport = new FakeTransport().add("prepare-modify.json").add("submit-success.json");
        NativePosting edit = new NativePosting(new NgaStore(), editTransport);
        NativePosting.Prepare p = edit.prepare("modify", "7", "456", "789");
        check(p.getServerQuotedText().isEmpty() && p.content.contains("&lt; < \" ' \\ $"), "Edit does not double-decode entities");
        check(p.existingAttachments.size() == 1 && p.existingAttachments.get(0).existing, "Existing attachments exposed separately");
        check(p.existingAttachments.get(0).fileName.equals("旧图+.png"), "UTF-8 original filename preserves plus");
        check(p.existingAttachments.get(0).bbcode.equals("[img]./mon_202610/18/fixture-image.png[/img]"), "Existing image UBB");
        error(Kind.VALIDATION, () -> edit.submit(p, p.subject, "修改正文", p.existingAttachments));
        error(Kind.VALIDATION, () -> edit.submit(p, p.subject, "修改正文", null, true));
        edit.submit(p, p.subject, "修改正文", null);
        Map<String, String> form = body(editTransport.requests.get(1));
        check(form.get("subject_not_modify").equals("1") && !form.containsKey("content_not_modify"), "Edit unchanged subject flag");
        check(form.get("tpic_misc_bit1").equals("1073741824") && !form.containsKey("attachments"), "Preserve topic flags without reattaching existing files");
        String append = dataVariant("prepare-modify.json", "modify_append", 1);
        FakeTransport appendTransport = new FakeTransport().json(append).add("submit-success.json");
        NativePosting appendApi = new NativePosting(new NgaStore(), appendTransport);
        NativePosting.Prepare ap = appendApi.prepare("modify", "7", "456", "789");
        check(ap.appendOnly, "Append restriction exposed");
        appendApi.submit(ap, ap.subject, "只追加的正文", null);
        check(body(appendTransport.requests.get(1)).get("modify_append").equals("1"), "Append-only cannot overwrite original");
        JSONObject other = new JSONObject(fixture("prepare-modify.json"));
        other.getJSONObject("data").put("if_moderator", 1).getJSONObject("__P").put("authorid", 999);
        NativePosting otherApi = new NativePosting(new NgaStore(), new FakeTransport().json(other.toString()));
        error(Kind.PERMISSION, () -> otherApi.prepare("modify", "7", "456", "789"));
        other.getJSONObject("data").getJSONObject("__P").remove("authorid");
        NativePosting missingAuthor = new NativePosting(new NgaStore(), new FakeTransport().json(other.toString()));
        error(Kind.PERMISSION, () -> missingAuthor.prepare("modify", "7", "456", "789"));
        JSONObject poll = new JSONObject(fixture("prepare-modify.json"));
        poll.getJSONObject("data").getJSONObject("__P").put("post_misc_var", new JSONObject().put("vote", new JSONObject().put("type", 1)));
        NativePosting pollApi = new NativePosting(new NgaStore(), new FakeTransport().json(poll.toString()));
        error(Kind.PERMISSION, () -> pollApi.prepare("modify", "7", "456", "789"));
        JSONObject first = new JSONObject(fixture("prepare-modify.json"));
        first.getJSONObject("data").put("pid", 0).getJSONObject("__T").put("authorid", 123);
        NativePosting firstApi = new NativePosting(new NgaStore(), new FakeTransport().json(first.toString()));
        check(firstApi.prepare("modify", "7", "456", "").pid.isEmpty(), "First-post edit checks topic author");
    }
    private static void submitAndEncoding() throws Exception {
        FakeTransport transport = new FakeTransport().add("prepare-new.json").add("submit-success.json");
        NgaStore store = new NgaStore(); NativePosting api = new NativePosting(store, transport);
        NativePosting.Prepare p = newPrepare(api);
        error(Kind.VALIDATION, () -> api.submit(p, "", "正文内容", null));
        error(Kind.VALIDATION, () -> api.submit(p, "标题", "短", null));
        NativePosting.Result r = api.submit(p, "中文 & + % = 标题", "[b]正文[/b]\n第二行 &=+% 😀", null, true);
        check(r.success && r.tid.equals("456") && r.pid.equals("789"), "Explicit success supplies real target IDs");
        check(r.url.equals("https://bbs.nga.cn/read.php?tid=456#pid789") && r.message.equals("发帖完毕"), "Verified native navigation result");
        NativePosting.Request request = transport.requests.get(1);
        check(request.method.equals("POST") && request.url.toString().equals("https://bbs.nga.cn/post.php"), "Submission endpoint and method");
        Map<String, String> form = body(request);
        check(form.get("post_subject").equals("中文 & + % = 标题") && form.get("post_content").endsWith("&=+% 😀"), "GB18030 and reserved characters roundtrip without loss");
        check(form.get("step").equals("2") && form.get("anony").equals("1") && form.get("__output").equals("8"), "Real submission parameters");
        check(form.get("__inchst").equals("GB18030") && request.headers.get("Content-Type").contains("GB18030"), "Explicit NGA input encoding");
        error(Kind.VALIDATION, () -> api.submit(p, "标题", "再次提交", null));
        check(transport.requests.size() == 2, "Successful prepare cannot be submitted twice");
        FakeTransport denied = new FakeTransport().add("prepare-new.json").add("submit-permission.json").add("submit-success.json");
        NativePosting deniedApi = new NativePosting(new NgaStore(), denied); NativePosting.Prepare dp = newPrepare(deniedApi);
        NativePosting.PostingException noPermission = error(Kind.PERMISSION, () -> deniedApi.submit(dp, "标题", "测试内容", null));
        check(!noPermission.uncertain && noPermission.getMessage().contains("没有回复权限"), "Explicit denial is never success");
        check(deniedApi.submit(dp, "修改后的标题", "修改后的内容", null).success, "Definite rejection releases lock for user correction");
        FakeTransport challenge = new FakeTransport().add("prepare-new.json").add("submit-challenge.json");
        NativePosting challengeApi = new NativePosting(new NgaStore(), challenge); NativePosting.Prepare cp = newPrepare(challengeApi);
        error(Kind.CHALLENGE_REQUIRED, () -> challengeApi.submit(cp, "标题", "测试内容", null));
        check(challenge.requests.size() == 2, "Challenge is reported without bypass or retry");
        String[] unconfirmed = {"{}", "{\"data\":{}}", "{\"data\":{\"__MESSAGE\":[]}}", "{\"data\":{\"__MESSAGE\":[\"0\",\"发帖完毕\",0,0,0,0,\"/read.php?tid=456\"]}}", "{\"data\":{\"__MESSAGE\":[false,\"发帖完毕\"]}}"};
        for (String response : unconfirmed) {
            FakeTransport bad = new FakeTransport().add("prepare-new.json").json(response);
            NativePosting badApi = new NativePosting(new NgaStore(), bad); NativePosting.Prepare bp = newPrepare(badApi);
            check(error(Kind.OUTCOME_UNKNOWN, () -> badApi.submit(bp, "标题", "测试内容", null)).uncertain, "Only numeric zero confirms success");
            error(Kind.OUTCOME_UNKNOWN, () -> badApi.submit(bp, "标题", "测试内容", null));
            check(bad.requests.size() == 2, "Unconfirmed result cannot be blindly repeated");
        }
        String[] badLocations = {"https://evil.example/read.php?tid=456", "https://bbs.nga.cn@evil.example/read.php?tid=456", "/post.php?tid=456", "/read.php", "/read.php?tid=456&tid=999", "javascript:alert(1)"};
        for (String location : badLocations) {
            JSONObject success = new JSONObject(fixture("submit-success.json")); success.getJSONObject("data").getJSONObject("__MESSAGE").put("6", location);
            FakeTransport t = new FakeTransport().add("prepare-new.json").json(success.toString());
            NativePosting a = new NativePosting(new NgaStore(), t); NativePosting.Prepare prep = newPrepare(a);
            error(Kind.OUTCOME_UNKNOWN, () -> a.submit(prep, "标题", "测试内容", null));
        }
        FakeTransport timeout = new FakeTransport().add("prepare-new.json").failure();
        NativePosting timeoutApi = new NativePosting(new NgaStore(), timeout); NativePosting.Prepare tp = newPrepare(timeoutApi);
        error(Kind.OUTCOME_UNKNOWN, () -> timeoutApi.submit(tp, "标题", "测试内容", null));
        check(timeout.requests.size() == 2, "Write timeout never retries");
        FakeTransport redirect = new FakeTransport().add("prepare-new.json").raw("", 302);
        NativePosting redirectApi = new NativePosting(new NgaStore(), redirect); NativePosting.Prepare rp = newPrepare(redirectApi);
        error(Kind.OUTCOME_UNKNOWN, () -> redirectApi.submit(rp, "标题", "测试内容", null));
        check(redirect.requests.size() == 2, "Write redirect never follows");
    }
    private static void uploads() throws Exception {
        FakeTransport transport = new FakeTransport().add("prepare-new.json").add("upload-success.json").add("submit-success.json");
        NativePosting api = new NativePosting(new NgaStore(), transport); NativePosting.Prepare p = newPrepare(api);
        check(api.preflight(p, "图+空 格.png", "image/png", PNG.length).allowed, "Image preflight");
        check(!api.preflight(p, "x.png", "image/jpeg", 12).allowed, "MIME mismatch denied");
        check(!api.preflight(p, "../x.png", "image/png", 12).allowed && !api.preflight(p, "x\r\n.png", "image/png", 12).allowed, "Filename traversal/header controls denied");
        check(!api.preflight(p, "x.html", "text/html", 12).allowed, "Unknown active file type denied");
        check(!api.preflight(p, "x.png", "image/png", -1).allowed && !api.preflight(p, "x.png", "image/png", 0).allowed, "Unknown and empty file length denied");
        check(api.preflight(p, "x.gif", "image/gif", 8L * 1024 * 1024).allowed, "Official GIF 8 MiB ceiling");
        check(!api.preflight(p, "x.png", "image/png", 4L * 1024 * 1024 + 1).allowed, "Official original-image 4 MiB ceiling");
        check(api.preflight(p, "x.jpg", "image/jpg", 12).allowed, "SAF image/jpg alias");
        error(Kind.VALIDATION, () -> api.upload(p, "x.png", "image/png", new byte[] {1, 2, 3, 4}));
        check(transport.requests.size() == 1, "Invalid file rejected before network");
        NativePosting.Attachment attachment = api.upload(p, "图+空 格.png", "image/png", PNG);
        check(attachment.image && !attachment.existing && attachment.fileName.equals("图+空 格.png"), "Successful upload result");
        check(attachment.url.equals("https://img.nga.cn/attachments/mon_202610/18/fixture-image.png"), "Canonical official image URL");
        check(attachment.bbcode.equals("[img]./mon_202610/18/fixture-image.png[/img]"), "Official relative UBB");
        NativePosting.Request request = transport.requests.get(1);
        check(request.url.toString().equals("https://img8.nga.cn/attach.php") && request.method.equals("POST"), "Fixed official upload authority");
        check(request.headers.get("Cookie").contains("offline-fixture-only") && request.headers.get("Origin").equals("https://bbs.nga.cn"), "Official withCredentials behavior on trusted NGA upload endpoint");
        String multi = multipart(request);
        check(multi.contains("name=\"auth\"\r\n\r\nfixture-upload-auth-not-a-real-credential") && multi.contains("name=\"func\"\r\n\r\nupload"), "Scoped upload authorization and action");
        check(multi.contains("name=\"fid\"\r\n\r\n7") && multi.contains("name=\"v2\"\r\n\r\n1") && multi.contains("name=\"attachment_file1_auto_size\"\r\n\r\n0"), "Uploader v2 original-file parameters");
        check(multi.contains("%E5%9B%BE%2B%E7%A9%BA%20%E6%A0%BC.png") && multi.contains("filename=\"upload.png\""), "UTF-8 original filename safely separated from ASCII MIME headers");
        check(request.parts[1] == PNG && request.parts.length == 3, "Streaming upload does not duplicate or recompress file bytes");
        api.submit(p, "含附件的标题", "正文 " + attachment.bbcode, Arrays.asList(attachment, attachment));
        Map<String, String> form = body(transport.requests.get(2));
        check(form.get("attachments").equals("fixture-serialized-attachment\t") && form.get("attachments_check").equals("fixture-checksum\t"), "Matching tab-delimited attachment tokens with duplicate suppression");
        FakeTransport foreignScopeT = new FakeTransport().add("prepare-new.json");
        NativePosting foreignScope = new NativePosting(new NgaStore(), foreignScopeT); NativePosting.Prepare fp = newPrepare(foreignScope);
        error(Kind.VALIDATION, () -> foreignScope.submit(fp, "标题", "测试内容", Collections.singletonList(attachment)));
        check(foreignScopeT.requests.size() == 1, "Attachments cannot cross prepare/session scope");
        for (String key : new String[] {"attachments", "attachments_check", "url", "isImg"}) {
            JSONObject response = new JSONObject(fixture("upload-success.json"));
            if (key.equals("isImg")) response.getJSONObject("data").put(key, 0);
            else if (key.equals("url")) response.getJSONObject("data").put(key, "https://evil.example/x.png");
            else response.getJSONObject("data").put(key, "bad\ttoken");
            FakeTransport bad = new FakeTransport().add("prepare-new.json").json(response.toString());
            NativePosting a = new NativePosting(new NgaStore(), bad); NativePosting.Prepare prep = newPrepare(a);
            error(Kind.OUTCOME_UNKNOWN, () -> a.upload(prep, "x.png", "image/png", PNG));
        }
        FakeTransport uploadError = new FakeTransport().add("prepare-new.json").json("{\"error_code\":13}");
        NativePosting errorApi = new NativePosting(new NgaStore(), uploadError); NativePosting.Prepare ep = newPrepare(errorApi);
        check(error(Kind.SERVER, () -> errorApi.upload(ep, "x.png", "image/png", PNG)).getMessage().contains("超过 5"), "Official uploader error_code mapped");
        JSONObject archivePrepare = new JSONObject(fixture("prepare-new.json")); archivePrepare.getJSONObject("data").put("fid", 200);
        NativePosting archiveApi = new NativePosting(new NgaStore(), new FakeTransport().json(archivePrepare.toString())); NativePosting.Prepare ap = archiveApi.prepare("new", "200", "", "");
        check(archiveApi.preflight(ap, "x.zip", "application/zip", 12L * 1024 * 1024).allowed, "Official special-forum archive 12 MiB limit");
        check(!archiveApi.preflight(ap, "x.png", "image/png", 12L * 1024 * 1024).allowed, "Archive limit does not loosen image limit");
        check(!new NativePosting(new NgaStore(), new FakeTransport()).preflight(null, "x.png", "image/png", 12).allowed, "Preflight requires a prepared session");
    }
    private static void sessionsAndEnvelopes() throws Exception {
        NgaStore store = new NgaStore(); FakeTransport t = new FakeTransport().add("prepare-new.json");
        NativePosting api = new NativePosting(store, t); NativePosting.Prepare p = newPrepare(api);
        store.testCookie = "ngaPassportUid=999; ngaPassportCid=changed-fixture";
        error(Kind.SESSION_CHANGED, () -> api.submit(p, "标题", "测试内容", null));
        check(t.requests.size() == 1, "Changed login blocked before a write");
        store.testCookie = "ngaPassportUid=123; ngaPassportCid=offline-fixture-only"; store.testHost = "ngabbs.com";
        error(Kind.SESSION_CHANGED, () -> api.upload(p, "x.png", "image/png", PNG));
        check(t.requests.size() == 1, "Changed host blocked before token use");
        String raw = fixture("prepare-new.json");
        String[] envelopes = {"\uFEFF" + raw, "/*$js$*/window.script_muti_get_var_store=" + raw + ";", "window.script_multi_get_var_store = " + raw + "; /*error fill content padding */"};
        for (String envelope : envelopes) {
            NativePosting a = new NativePosting(new NgaStore(), new FakeTransport().json(envelope));
            check(newPrepare(a).canUpload, "Legacy envelope parsed as data");
        }
        for (String script : new String[] {raw + ";alert(1)", "evilwindow.script_muti_get_var_store=" + raw, "window.script_muti_get_var_store=" + raw + "; location='https://evil.example'"}) {
            NativePosting a = new NativePosting(new NgaStore(), new FakeTransport().json(script));
            error(Kind.PROTOCOL, () -> newPrepare(a));
        }
        NativePosting blocked = new NativePosting(new NgaStore(), new FakeTransport().raw("<html>captcha security challenge</html>", 403));
        error(Kind.CHALLENGE_REQUIRED, () -> newPrepare(blocked));
        NativePosting forbidden = new NativePosting(new NgaStore(), new FakeTransport().raw("<html>forbidden</html>", 403));
        error(Kind.PERMISSION, () -> newPrepare(forbidden));
        NativePosting offline = new NativePosting(new NgaStore(), new FakeTransport().failure());
        error(Kind.NETWORK, () -> newPrepare(offline));
        NativePosting redirect = new NativePosting(new NgaStore(), new FakeTransport().raw("", 302));
        error(Kind.NETWORK, () -> newPrepare(redirect));
        NativePosting malformed = new NativePosting(new NgaStore(), new FakeTransport().raw("<html>not API data</html>", 200));
        error(Kind.PROTOCOL, () -> newPrepare(malformed));
    }
    public static void main(String[] args) throws Exception {
        preparation(); quoteAndEdit(); submitAndEncoding(); uploads(); sessionsAndEnvelopes();
        System.out.println("NativePosting offline fixture checks passed: " + checks);
    }
}
