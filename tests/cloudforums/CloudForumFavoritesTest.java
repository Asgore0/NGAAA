package cn.ngapure.app;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic read/write protocol fixtures only. No socket, account access or real remote writes. */
public final class CloudForumFavoritesTest {
    private static int scenarios, checks;
    private static final String ROWS = "[{\"fid\":650,\"id\":650,\"name\":\"普通 &amp; 版块\",\"icon\":\"//img4.nga.cn/attachments/synthetic.png\"},"
            + "{\"fid\":0,\"id\":23673143,\"name\":\"合成合集\"},{\"fid\":-7,\"id\":-7,\"name\":\"合成负数版块\"}]";
    private CloudForumFavoritesTest() { }
    public static void main(String[] args) throws Exception {
        scenario(() -> {
            Session session = new Session(); Fake fake = new Fake(); fake.json("{\"data\":[" + ROWS + "]}");
            CloudForumFavorites api = new CloudForumFavorites(new NativeCommunity(session, fake));
            check(fake.calls == 0, "constructor never reads automatically");
            CloudForumFavorites.Snapshot snapshot = api.read(); NativeCommunity.Request request = fake.last;
            check(snapshot.entries.size() == 3 && snapshot.forumCount == 2 && snapshot.collectionCount == 1, "board and collection counts remain distinct");
            check(fake.calls == 1 && request.method.equals("POST") && request.path.equals("nuke.php") && !request.mutation, "official sync is one read-only POST");
            check(request.queryParameters.size() == 2 && "forum_favor2".equals(request.queryParameters.get("__lib")) && "forum_favor".equals(request.queryParameters.get("__act")), "official sync routing stays in query");
            check("sync".equals(request.bodyParameters.get("action")) && "3".equals(request.bodyParameters.get("__output")), "official sync action and output stay in form");
            check(!request.parameters.containsKey("uid") && !request.parameters.containsKey("fid") && !request.parameters.containsKey("stid"), "read does not invent account or mutation arguments");
            check(!request.parameters.containsValue("add") && !request.parameters.containsValue("del"), "cloud import cannot upload or delete favorites");
        });
        scenario(() -> {
            CloudForumFavorites.Snapshot snapshot = parse("{\"data\":[" + ROWS + "]}");
            CloudForumFavorites.Entry forum = snapshot.entries.get(0), collection = snapshot.entries.get(1), negative = snapshot.entries.get(2);
            check("forum".equals(forum.type) && "650".equals(forum.key) && "650".equals(forum.fid) && forum.stid.isEmpty(), "ordinary board keeps original fid identity");
            check("普通 & 版块".equals(forum.name) && "https://img4.nga.cn/attachments/synthetic.png".equals(forum.icon), "board metadata is decoded and avatar source constrained");
            check(collection.isCollection() && "stid:23673143".equals(collection.key) && "23673143".equals(collection.stid) && "0".equals(collection.fid), "collection keeps stid identity without masquerading as fid");
            check("23673143".equals(collection.id) && "合成合集".equals(collection.name), "collection metadata retained");
            check("-7".equals(negative.key) && !negative.isCollection(), "negative forum fid stays supported");
        });
        scenario(() -> {
            CloudForumFavorites.Snapshot snapshot = parse("{\"data\":{\"0\":{\"8\":{\"fid\":\"650\",\"name\":\"先出现\"},\"17\":{\"id\":\"23673143\",\"name\":\"合集\"},\"23\":{\"fid\":650,\"name\":\"重复\"}}}}");
            check(snapshot.keys().equals(set("650", "stid:23673143")) && snapshot.entries.size() == 2, "sparse numeric objects are parsed and deduplicated by stable identity");
            check(snapshot.forumCount == 1 && snapshot.collectionCount == 1, "deduplicated metadata has consistent counts");
        });
        scenario(() -> {
            for (String body : Arrays.asList("{\"data\":[[]]}", "{\"data\":[{}]}", "{\"data\":{\"0\":[]}}", "{\"data\":{\"0\":{}}}")) {
                CloudForumFavorites.Snapshot snapshot = parse(body);
                check(snapshot.entries.isEmpty() && snapshot.keys().isEmpty(), "explicit empty cloud collection is accepted");
                check(CloudForumFavorites.merge(set("650"), set("650"), snapshot).equals(set("650")), "empty cloud favorites never erase local favorites");
            }
        });
        scenario(() -> {
            for (String body : Arrays.asList("{}", "{\"data\":[]}", "{\"data\":[null]}", "{\"data\":[\"not rows\"]}",
                    "{\"data\":[[null]]}", "{\"data\":[[42]]}", "{\"data\":[[{\"fid\":0,\"id\":0}]]}",
                    "{\"data\":[[{\"fid\":\"bad\",\"id\":23673143}]]}", "{\"data\":[[{\"fid\":\"0650\"}]]}",
                    "{\"data\":[[{\"fid\":\"1234567890123\"}]]}",
                    "{\"data\":[[{\"fid\":true,\"id\":23673143}]]}", "{\"data\":[[{\"fid\":{},\"id\":23673143}]]}",
                    "{\"data\":[[{\"fid\":0,\"id\":true}]]}",
                    "{\"data\":[[{\"fid\":650,\"name\":true}]]}", "{\"code\":-1,\"data\":[[]]}",
                    "{\"code\":\"0\",\"data\":[[]]}", "{\"data\":[[{\"fid\":650,\"error\":[\"rejected\"]}]]}")) {
                error(NativeCommunity.Code.INVALID_RESPONSE, () -> parse(body), "unknown or partial shapes are not empty success");
            }
        });
        scenario(() -> {
            CloudForumFavorites.Snapshot snapshot = parse("{\"data\":[[{\"fid\":650,\"name\":\"\",\"icon\":\"https://evil.example/portrait.png\"},{\"fid\":0,\"id\":123}]]}");
            check("版块 650".equals(snapshot.entries.get(0).name) && snapshot.entries.get(0).icon.isEmpty(), "missing name has explicit ID fallback and foreign icon is discarded");
            check("合集 123".equals(snapshot.entries.get(1).name), "missing collection name retains correct type");
            JSONArray rows = new JSONArray(); for (int i = 0; i < 2049; i++) rows.put(new JSONObject().put("fid", i + 1));
            error(NativeCommunity.Code.INVALID_RESPONSE, () -> CloudForumFavorites.parse(new JSONObject().put("data", new JSONArray().put(rows))), "oversized favorites response fails atomically");
        });
        scenario(() -> {
            String[] messages = {"尚未登录", "安全验证", "操作频繁", "无权访问", "服务错误"};
            NativeCommunity.Code[] codes = {NativeCommunity.Code.LOGIN_REQUIRED, NativeCommunity.Code.CHALLENGE_REQUIRED, NativeCommunity.Code.RATE_LIMIT, NativeCommunity.Code.PERMISSION_DENIED, NativeCommunity.Code.SERVER};
            for (int i = 0; i < messages.length; i++) {
                Fake fake = new Fake(); fake.json(new JSONObject().put("error", new JSONArray().put(messages[i])).toString());
                CloudForumFavorites api = new CloudForumFavorites(new NativeCommunity(new Session(), fake));
                error(codes[i], api::read, "existing login/challenge/server refusal remains visible");
                check(fake.calls == 1, "server rejection does not retry read or write");
            }
        });
        scenario(() -> {
            int[] statuses = {401, 403, 429, 500, 302};
            NativeCommunity.Code[] codes = {NativeCommunity.Code.LOGIN_REQUIRED, NativeCommunity.Code.PERMISSION_DENIED, NativeCommunity.Code.RATE_LIMIT, NativeCommunity.Code.SERVER, NativeCommunity.Code.CHALLENGE_REQUIRED};
            for (int i = 0; i < statuses.length; i++) {
                Fake fake = new Fake(); fake.raw(statuses[i], "{}", "UTF-8");
                CloudForumFavorites api = new CloudForumFavorites(new NativeCommunity(new Session(), fake));
                error(codes[i], api::read, "HTTP refusal is not empty cloud collection"); check(fake.calls == 1, "HTTP refusal is bounded");
            }
        });
        scenario(() -> {
            String wrapper = "<html><head><meta http-equiv='Content-Type' content='text/html; charset=GBK'></head><body><script>window.script_muti_get_var_store={\"data\":[" + ROWS + "]};</script></body></html>";
            Fake fake = new Fake(); fake.raw(200, wrapper, "GB18030");
            check(new CloudForumFavorites(new NativeCommunity(new Session(), fake)).read().entries.size() == 3, "official legacy wrapper parses as data without script execution");
            Fake injected = new Fake(); injected.raw(200, wrapper.replace("</script>", ";window.injected=true;</script>"), "GB18030");
            error(NativeCommunity.Code.INVALID_RESPONSE, new CloudForumFavorites(new NativeCommunity(new Session(), injected))::read, "appended script cannot be interpreted as data");
        });
        scenario(() -> {
            Fake fake = new Fake(); fake.raw(200, "{\"data\":[[{\"fid\":650,\"name\":\"合成中文版块\"}]]}", "GB18030");
            check("合成中文版块".equals(new CloudForumFavorites(new NativeCommunity(new Session(), fake)).read().entries.get(0).name), "official legacy charset retains metadata");
        });
        scenario(() -> {
            CloudForumFavorites.Snapshot remote = parse("{\"data\":[" + ROWS + "]}"); Set<String> local = set("123", "650");
            Set<String> merged = CloudForumFavorites.merge(local, local, remote);
            check(merged.equals(set("123", "650", "stid:23673143", "-7")), "explicit import unions remote with existing local favorites");
            check(local.equals(set("123", "650")), "merge never mutates local input snapshot");
            check(CloudForumFavorites.merge(merged, merged, remote).equals(merged), "repeated import is idempotent");
        });
        scenario(() -> {
            CloudForumFavorites.Snapshot remote = parse("{\"data\":[" + ROWS + "]}");
            Set<String> merged = CloudForumFavorites.merge(set("650", "123"), set("123", "999"), remote);
            check(!merged.contains("650"), "favorite removed during read is not silently restored");
            check(merged.contains("999") && merged.contains("123") && merged.contains("stid:23673143"), "concurrent local additions and unrelated favorites survive merge");
        });
        scenario(() -> {
            CloudForumFavorites.Snapshot remote = parse("{\"data\":[[{\"fid\":23673143,\"name\":\"版块\"},{\"fid\":0,\"id\":23673143,\"name\":\"合集\"}]]}");
            check(remote.keys().equals(set("23673143", "stid:23673143")), "same numeric forum and collection IDs cannot collide");
            check(CloudForumFavorites.merge(Collections.emptySet(), Collections.emptySet(), remote).size() == 2, "import retains both stable identities");
            immutable(() -> remote.entries.clear()); immutable(() -> remote.keys().clear());
            immutable(() -> CloudForumFavorites.merge(Collections.emptySet(), Collections.emptySet(), remote).clear());
        });
        scenario(() -> {
            for (String cookie : Arrays.asList("", "only=unrelated", "ngaPassportUid=70001", "ngaPassportUid=70001; ngaPassportCid=")) {
                Session session = new Session(); session.cookie = cookie; Fake fake = new Fake();
                error(NativeCommunity.Code.LOGIN_REQUIRED, new CloudForumFavorites(new NativeCommunity(session, fake))::read, "cloud board favorites require existing complete session");
                check(fake.calls == 0, "missing login never invokes transport");
            }
        });
        scenario(() -> {
            Session session = new Session(); session.host = "evil.example"; Fake fake = new Fake();
            error(NativeCommunity.Code.UNSUPPORTED, new CloudForumFavorites(new NativeCommunity(session, fake))::read, "host allowlist applies before credential transport");
            check(fake.calls == 0, "unsupported host never receives synthetic cookie");
        });
        scenario(() -> {
            Fake fake = new Fake(); fake.fail = true;
            error(NativeCommunity.Code.NETWORK, new CloudForumFavorites(new NativeCommunity(new Session(), fake))::read, "read-only POST failure remains network failure, not unknown write delivery");
            check(fake.calls == 1 && !fake.last.mutation, "uncertain network neither retries nor writes");
        });
        scenario(() -> {
            Session actual = new Session(); NativeCommunity.Session scoped = new NativeCommunity.Session() {
                public String host() { return "bbs.nga.cn"; }
                public String cookie() { return NativeCommunity.scopedCookie(actual.host, "bbs.nga.cn", "70001", actual.cookie); }
            };
            Fake fake = new Fake(); CloudForumFavorites api = new CloudForumFavorites(new NativeCommunity(scoped, fake));
            actual.cookie = "ngaPassportUid=70002; ngaPassportCid=synthetic-other";
            error(NativeCommunity.Code.LOGIN_REQUIRED, api::read, "retained importer cannot use newly switched account");
            actual.cookie = "ngaPassportUid=70001; ngaPassportCid=synthetic-only"; actual.host = "ngabbs.com";
            error(NativeCommunity.Code.LOGIN_REQUIRED, api::read, "retained importer cannot use newly switched route");
            check(fake.calls == 0, "account and route changes are rejected before transport");
        });
        scenario(() -> {
            Fake fake=new Fake();fake.json("{\"data\":[[]]}");fake.json("{\"data\":[\"操作成功\"]}");fake.json("{\"data\":["+ROWS+"]}");
            CloudForumFavorites.ChangeResult result=new CloudForumFavorites(new NativeCommunity(new Session(),fake)).setDesired("650",true);
            check(result.confirmed()&&result.status==CloudForumFavorites.ChangeStatus.CONFIRMED&&result.desired&&"650".equals(result.key),"add confirms only after read-back membership matches");
            check(result.snapshot!=null&&result.snapshot.keys().contains("650")&&result.failureCode==null,"confirmed result carries verified cloud snapshot");
            check(fake.calls==3&&mutations(fake)==1,"bounded pre-read, one add, and one read-back");
            NativeCommunity.Request write=fake.history.get(1);
            check(write.method.equals("POST")&&write.mutation&&write.path.equals("nuke.php"),"board addition uses a mutation POST");
            check(write.queryParameters.size()==3&&"forum_favor2".equals(write.queryParameters.get("__lib"))&&"forum_favor".equals(write.queryParameters.get("__act"))&&"3".equals(write.queryParameters.get("__output")),"official write routing and output remain in URL query");
            check(write.bodyParameters.size()==2&&"add".equals(write.bodyParameters.get("action"))&&"650".equals(write.bodyParameters.get("fid"))&&!write.bodyParameters.containsKey("stid"),"ordinary child board sends exactly its own fid and action, no stid");
            check(!write.parameters.containsKey("uid")&&!write.parameters.containsKey("name")&&!write.parameters.containsKey("cookie"),"write has no invented account, label, or credential parameters");
        });
        scenario(() -> {
            for(String key:Arrays.asList("650","-7","stid:23673143"))for(boolean desired:Arrays.asList(true,false)) {
                Fake fake=new Fake();String present=present(key);
                fake.json(desired?"{\"data\":[[]]}":present);fake.json("{}");fake.json(desired?present:"{\"data\":[[]]}");
                CloudForumFavorites.ChangeResult result=new CloudForumFavorites(new NativeCommunity(new Session(),fake)).setDesired(key,desired);
                check(result.confirmed()&&result.status==CloudForumFavorites.ChangeStatus.CONFIRMED&&result.desired==desired,"forum, negative forum, and collection add/delete all verify desired state");
                NativeCommunity.Request write=fake.history.get(1);boolean collection=key.startsWith("stid:");
                check((desired?"add":"del").equals(write.bodyParameters.get("action")),"desired boolean selects official action");
                check((collection?key.substring(5):key).equals(write.bodyParameters.get(collection?"stid":"fid"))&&!write.bodyParameters.containsKey(collection?"fid":"stid"),"forum and collection wire identities are mutually exclusive");
                check(fake.calls==3&&mutations(fake)==1,"each desired-state change is bounded to one mutation");
            }
        });
        scenario(() -> {
            for(boolean desired:Arrays.asList(true,false)) {
                Fake fake=new Fake();fake.json(desired?present("650"):"{\"data\":[[]]}");
                CloudForumFavorites.ChangeResult result=new CloudForumFavorites(new NativeCommunity(new Session(),fake)).setDesired("650",desired);
                check(result.confirmed()&&result.status==CloudForumFavorites.ChangeStatus.ALREADY_MATCHED&&result.snapshot!=null,"existing desired state is an explicit idempotent success");
                check(fake.calls==1&&mutations(fake)==0,"retry or existing membership never duplicates add or delete");
            }
        });
        scenario(() -> {
            Fake fake=new Fake();fake.json("{\"data\":[[{\"fid\":23673143,\"name\":\"同数字普通版块\"}]]}");fake.json("{\"data\":[\"收藏成功\"]}");
            fake.json("{\"data\":[[{\"fid\":23673143},{\"id\":23673143}]]}");
            CloudForumFavorites.ChangeResult result=new CloudForumFavorites(new NativeCommunity(new Session(),fake)).setDesired("stid:23673143",true);
            check(result.confirmed()&&fake.calls==3&&mutations(fake)==1,"same numeric forum does not satisfy collection preflight");
            check(result.snapshot.keys().equals(set("23673143","stid:23673143")),"read-back preserves both remote identities");
        });
        scenario(() -> {
            for(String writeBody:Arrays.asList("{}","{\"data\":[\"操作成功\"]}","{\"code\":0}","not-json","{\"data\":[[]]}")) {
                Fake fake=new Fake();fake.json("{\"data\":[[]]}");fake.json(writeBody);fake.json("{\"data\":[[]]}");
                CloudForumFavorites.ChangeResult result=new CloudForumFavorites(new NativeCommunity(new Session(),fake)).setDesired("650",true);
                check(!result.confirmed()&&result.status==CloudForumFavorites.ChangeStatus.UNCONFIRMED,"HTTP 200, generic acknowledgement and arbitrary data never independently confirm add");
                check(result.snapshot!=null&&!result.snapshot.keys().contains("650")&&result.failureCode==NativeCommunity.Code.UNCONFIRMED,"unmatched read-back retains explicit uncertainty");
                check(fake.calls==3&&mutations(fake)==1,"unmatched acknowledgement has no automatic mutation retry");
            }
        });
        scenario(() -> {
            for(int mode=0;mode<4;mode++) {
                Fake fake=new Fake();fake.json("{\"data\":[[]]}");
                if(mode==0)fake.network();else if(mode==1)fake.raw(302,"","UTF-8");else if(mode==2)fake.json("malformed");else fake.raw(500,"{}","UTF-8");
                fake.json(present("650"));
                CloudForumFavorites.ChangeResult result=new CloudForumFavorites(new NativeCommunity(new Session(),fake)).setDesired("650",true);
                check(result.confirmed()&&result.status==CloudForumFavorites.ChangeStatus.CONFIRMED,"ambiguous transport or response is confirmed only by separate matching read-back");
                check(fake.calls==3&&mutations(fake)==1,"ambiguous delivery permits a read, never a second write");
            }
        });
        scenario(() -> {
            Fake fake=new Fake();fake.json(present("650"));fake.network();fake.json(present("650"));
            CloudForumFavorites.ChangeResult result=new CloudForumFavorites(new NativeCommunity(new Session(),fake)).setDesired("650",false);
            check(!result.confirmed()&&result.status==CloudForumFavorites.ChangeStatus.UNCONFIRMED&&result.failureCode==NativeCommunity.Code.UNCONFIRMED,"interrupted delete with unchanged server state stays uncertain");
            check(result.snapshot!=null&&result.snapshot.keys().contains("650")&&fake.calls==3&&mutations(fake)==1,"delete uncertainty retains actual read snapshot and does not retry");
        });
        scenario(() -> {
            Fake fake=new Fake();fake.json("{\"data\":[[]]}");fake.json("{\"data\":[\"操作成功\"]}");fake.network();
            CloudForumFavorites.ChangeResult result=new CloudForumFavorites(new NativeCommunity(new Session(),fake)).setDesired("650",true);
            check(!result.confirmed()&&result.status==CloudForumFavorites.ChangeStatus.UNCONFIRMED&&result.snapshot==null&&result.failureCode==NativeCommunity.Code.NETWORK,"read-back network failure remains typed unknown, not successful 200");
            check(fake.calls==3&&mutations(fake)==1,"failed verification never retries mutation or read");
        });
        scenario(() -> {
            for(String readBack:Arrays.asList("{}","{\"data\":[null]}","{\"data\":[[{\"fid\":true}]]}","{\"data\":[[{\"fid\":650}]],\"code\":-1}")) {
                Fake fake=new Fake();fake.json("{\"data\":[[]]}");fake.json("{}");fake.json(readBack);
                CloudForumFavorites.ChangeResult result=new CloudForumFavorites(new NativeCommunity(new Session(),fake)).setDesired("650",true);
                check(!result.confirmed()&&result.status==CloudForumFavorites.ChangeStatus.UNCONFIRMED&&result.snapshot==null&&result.failureCode==NativeCommunity.Code.INVALID_RESPONSE,"partial or unknown verification data cannot confirm or fabricate cloud state");
                check(fake.calls==3&&mutations(fake)==1,"malformed verification is bounded");
            }
        });
        scenario(() -> {
            int[] statuses={401,403,429,200};String[] bodies={"{}","{}","{}","{\"error\":[\"安全验证\"]}"};
            NativeCommunity.Code[] codes={NativeCommunity.Code.LOGIN_REQUIRED,NativeCommunity.Code.PERMISSION_DENIED,NativeCommunity.Code.RATE_LIMIT,NativeCommunity.Code.CHALLENGE_REQUIRED};
            for(int i=0;i<statuses.length;i++) {
                Fake fake=new Fake();fake.json("{\"data\":[[]]}");fake.raw(statuses[i],bodies[i],"UTF-8");
                CloudForumFavorites.ChangeResult result=new CloudForumFavorites(new NativeCommunity(new Session(),fake)).setDesired("650",true);
                check(!result.confirmed()&&result.status==CloudForumFavorites.ChangeStatus.REJECTED&&result.failureCode==codes[i]&&result.snapshot==null,"explicit mutation refusal is typed and cannot be success");
                check(fake.calls==2&&mutations(fake)==1,"definite refusal neither retries nor bypasses authentication/challenge");
            }
        });
        scenario(() -> {
            for(String before:Arrays.asList("{}","{\"error\":[\"尚未登录\"]}","{\"data\":[null]}")) {
                Fake fake=new Fake();fake.json(before);
                try {new CloudForumFavorites(new NativeCommunity(new Session(),fake)).setDesired("650",true);throw new AssertionError("preflight error");}
                catch(NativeCommunity.CommunityException expected){check(fake.calls==1&&mutations(fake)==0,"unreadable cloud baseline fails before every write");}
            }
        });
        scenario(() -> {
            for(String key:Arrays.asList(null,"","0","0650","-0","650&action=del","stid:0","stid:-7","stid:23673143&fid=650","stid:1234567890123456789","https://bbs.nga.cn/thread.php?fid=650")) {
                Fake fake=new Fake();
                try {new CloudForumFavorites(new NativeCommunity(new Session(),fake)).setDesired(key,true);throw new AssertionError("invalid key");}
                catch(IllegalArgumentException expected){check(fake.calls==0,"invalid identity cannot reach network");}
            }
        });
        scenario(() -> {
            for(boolean hostSwitch:Arrays.asList(true,false)) {
                Session session=new Session();Fake fake=new Fake();fake.json("{\"data\":[[]]}");fake.afterCall=1;
                fake.afterResponse=()->{if(hostSwitch)session.host="ngabbs.com";else session.cookie="ngaPassportUid=70002; ngaPassportCid=synthetic-other";};
                CloudForumFavorites.ChangeResult result=new CloudForumFavorites(new NativeCommunity(session,fake)).setDesired("650",true);
                check(!result.confirmed()&&result.status==CloudForumFavorites.ChangeStatus.REJECTED&&result.failureCode==NativeCommunity.Code.LOGIN_REQUIRED,"scope switch after baseline is rejected before mutation");
                check(fake.calls==1&&mutations(fake)==0,"host/account switch cannot send another account's credentials or write");
            }
        });
        scenario(() -> {
            for(boolean hostSwitch:Arrays.asList(true,false)) {
                Session session=new Session();Fake fake=new Fake();fake.json("{\"data\":[[]]}");fake.json("{\"data\":[\"操作成功\"]}");fake.afterCall=2;
                fake.afterResponse=()->{if(hostSwitch)session.host="ngabbs.com";else session.cookie="ngaPassportUid=70002; ngaPassportCid=synthetic-other";};
                CloudForumFavorites.ChangeResult result=new CloudForumFavorites(new NativeCommunity(session,fake)).setDesired("650",true);
                check(!result.confirmed()&&result.status==CloudForumFavorites.ChangeStatus.UNCONFIRMED&&result.failureCode==NativeCommunity.Code.LOGIN_REQUIRED&&result.snapshot==null,"scope switch after write never commits a different account's read-back");
                check(fake.calls==2&&mutations(fake)==1,"post-write scope change cannot send read or retry to other account");
            }
        });
        scenario(() -> {
            Fake fake=new Fake();fake.json("{\"data\":[[]]}");fake.network();fake.network();fake.json(present("650"));
            CloudForumFavorites api=new CloudForumFavorites(new NativeCommunity(new Session(),fake));
            CloudForumFavorites.ChangeResult first=api.setDesired("650",true),retry=api.setDesired("650",true);
            check(first.status==CloudForumFavorites.ChangeStatus.UNCONFIRMED&&!first.confirmed(),"lost write and verification preserve unknown state");
            check(retry.status==CloudForumFavorites.ChangeStatus.ALREADY_MATCHED&&retry.confirmed(),"user retry checks server state before deciding whether a write is necessary");
            check(fake.calls==4&&mutations(fake)==1,"user retry of an already delivered add performs no duplicate mutation");
        });
        scenario(() -> {
            for(String cookie:Arrays.asList("","ngaPassportUid=70001","ngaPassportUid=bad; ngaPassportCid=synthetic-only")) {
                Session session=new Session();session.cookie=cookie;Fake fake=new Fake();
                error(NativeCommunity.Code.LOGIN_REQUIRED,()->new CloudForumFavorites(new NativeCommunity(session,fake)).setDesired("650",true),"change requires a complete valid account scope");
                check(fake.calls==0,"unauthenticated change never sends a read or write");
            }
            Session session=new Session();session.host="evil.example";Fake fake=new Fake();
            error(NativeCommunity.Code.UNSUPPORTED,()->new CloudForumFavorites(new NativeCommunity(session,fake)).setDesired("650",true),"change never broadens official allowlist");
            check(fake.calls==0,"unsupported change host gets no credentials");
        });
        scenario(() -> {
            Session session=new Session();Fake fake=new Fake();fake.json("{\"data\":[[]]}");fake.json("{\"data\":[\"操作成功\"]}");fake.json(present("754"));
            NativeCommunity exact=new NativeCommunity(session,fake).exactForumFavorites(session.host,session.cookie);
            check(new CloudForumFavorites(exact).setDesired("754",true).confirmed(),"unchanged exact session performs normal independently confirmed write");
            check(fake.calls==3&&mutations(fake)==1,"exact-session guard preserves bounded protocol");
        });
        scenario(() -> {
            Session session=new Session();Fake fake=new Fake();fake.json("{\"data\":[[]]}");fake.afterCall=1;
            NativeCommunity exact=new NativeCommunity(session,fake).exactForumFavorites(session.host,session.cookie);
            fake.afterResponse=()->session.cookie="ngaPassportUid=70001; ngaPassportCid=synthetic-refreshed";
            CloudForumFavorites.ChangeResult result=new CloudForumFavorites(exact).setDesired("754",true);
            check(!result.confirmed()&&result.status==CloudForumFavorites.ChangeStatus.REJECTED&&result.failureCode==NativeCommunity.Code.LOGIN_REQUIRED,"same-account cookie refresh after preflight blocks old worker mutation");
            check(fake.calls==1&&mutations(fake)==0,"new same-account credentials never enter old preflight write");
        });
        scenario(() -> {
            Session session=new Session();Fake fake=new Fake();fake.json("{\"data\":[[]]}");fake.json("{\"data\":[\"操作成功\"]}");fake.afterCall=2;
            NativeCommunity exact=new NativeCommunity(session,fake).exactForumFavorites(session.host,session.cookie);
            fake.afterResponse=()->session.cookie="ngaPassportUid=70001; ngaPassportCid=synthetic-refreshed";
            CloudForumFavorites.ChangeResult result=new CloudForumFavorites(exact).setDesired("754",true);
            check(!result.confirmed()&&result.status==CloudForumFavorites.ChangeStatus.UNCONFIRMED&&result.failureCode==NativeCommunity.Code.LOGIN_REQUIRED,"same-account cookie refresh after write never acknowledges old work from new session");
            check(fake.calls==2&&mutations(fake)==1,"exact session never reads or retries with refreshed credentials");
        });
        scenario(() -> {
            for(boolean hostSwitch:Arrays.asList(true,false)) {
                Session session=new Session();Fake fake=new Fake();NativeCommunity exact=new NativeCommunity(session,fake).exactForumFavorites(session.host,session.cookie);
                if(hostSwitch)session.host="ngabbs.com";else session.cookie="ngaPassportUid=70002; ngaPassportCid=synthetic-other";
                error(NativeCommunity.Code.LOGIN_REQUIRED,new CloudForumFavorites(exact)::read,"exact session rejects host/account switch before read");
                check(fake.calls==0,"switched session never reaches transport");
            }
        });
        System.out.println("PASS cloud forum favorites: " + scenarios + " scenarios / " + checks + " assertions; network=0 remoteWrites=0 accountAccess=false");
    }
    private static CloudForumFavorites.Snapshot parse(String body) throws Exception { return CloudForumFavorites.parse(new JSONObject(body)); }
    private static String present(String key) { return "{\"data\":[[{\""+(key.startsWith("stid:")?"id":"fid")+"\":\""+(key.startsWith("stid:")?key.substring(5):key)+"\"}]]}"; }
    private static int mutations(Fake fake) {int count=0;for(NativeCommunity.Request request:fake.history)if(request.mutation)count++;return count;}
    private static Set<String> set(String... values) { return new LinkedHashSet<>(Arrays.asList(values)); }
    private interface Action { void run() throws Exception; }
    private static void scenario(Action action) throws Exception { action.run(); scenarios++; }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void immutable(Action action) throws Exception {
        try { action.run(); throw new AssertionError("mutable result"); }
        catch (UnsupportedOperationException expected) { checks++; }
    }
    private static void error(NativeCommunity.Code code, Action action, String label) throws Exception {
        try { action.run(); throw new AssertionError(label); }
        catch (NativeCommunity.CommunityException expected) { check(expected.code == code, label); }
    }
    private static final class Session implements NativeCommunity.Session {
        String host = "bbs.nga.cn", cookie = "ngaPassportUid=70001; ngaPassportCid=synthetic-only";
        public String host() { return host; }
        public String cookie() { return cookie; }
    }
    private static final class Fake implements NativeCommunity.Transport {
        final Deque<Object> responses = new ArrayDeque<>();
        final List<NativeCommunity.Request> history=new ArrayList<>();
        int afterCall;Runnable afterResponse;
        NativeCommunity.Request last; int calls; boolean fail;
        void network(){responses.add(new IOException("synthetic-only queued failure"));}
        void json(String body) { raw(200, body, "UTF-8"); }
        void raw(int status, String body, String charset) { responses.add(new NativeCommunity.Response(status, body.getBytes(Charset.forName(charset)), "application/json; charset=" + charset)); }
        @Override public NativeCommunity.Response execute(NativeCommunity.Request request, String cookie) throws IOException {
            calls++; last = request;history.add(request);
            if (fail) throw new IOException("synthetic-only transport failure");
            if (responses.isEmpty()) throw new AssertionError("unexpected fixture request");
            Object next=responses.removeFirst();if(next instanceof IOException)throw (IOException)next;
            if(calls==afterCall&&afterResponse!=null)afterResponse.run();
            return (NativeCommunity.Response)next;
        }
    }
}
