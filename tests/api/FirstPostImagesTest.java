import cn.ngapure.app.NgaApi;
import cn.ngapure.app.TopicPreviewImages;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/** Synthetic server envelopes verify exact topic/page/original-floor boundaries. No account or HTTP. */
public final class FirstPostImagesTest {
    private static int assertions;
    private static final String A = "https://img.nga.cn/attachments/mon_202610/23/original.jpg";
    private static final String B = "https://img4.nga.cn/attachments/mon_202610/23/reply.png";
    private static void check(boolean pass, String message) { assertions++; if (!pass) throw new AssertionError(message); }
    private static JSONObject row(String pid, int floor, String author, String content) throws Exception {
        return new JSONObject().put("pid", pid).put("lou", floor).put("authorid", author).put("tid", "123").put("content", content);
    }
    private static JSONObject fixture() throws Exception {
        return new JSONObject().put("data", new JSONObject().put("__T", new JSONObject().put("tid", "123").put("authorid", "42").put("avatar", B))
                .put("__PAGE", 1).put("__R", new JSONArray().put(row("0", 0, "42", "[img]" + A + "[/img]"))
                        .put(row("777", 1, "99", "[img]" + B + "[/img]"))));
    }
    @SuppressWarnings("unchecked")
    private static List<String> parse(NgaApi api, JSONObject response) throws Exception {
        Method method = NgaApi.class.getDeclaredMethod("parseFirstPostImages", JSONObject.class, String.class); method.setAccessible(true);
        try { return (List<String>) method.invoke(api, response, "123"); }
        catch (InvocationTargetException failure) { if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause(); throw failure; }
    }
    private static void rejected(NgaApi api, JSONObject response, String message) throws Exception {
        boolean failed = false; try { parse(api, response); } catch (java.io.IOException expected) { failed = true; }
        check(failed, message);
    }
    public static int verify(NgaApi api) throws Exception {
        assertions = 0;
        check(parse(api, fixture()).equals(Arrays.asList(A)), "only original floor image, never reply/avatar image");
        JSONObject response = fixture(); JSONObject data = response.getJSONObject("data"); JSONObject original = data.getJSONArray("__R").getJSONObject(0);
        original.put("content", "no image"); check(parse(api, response).isEmpty(), "image-free original never falls back to reply image");
        original.put("content", "[quote]" + "[img]" + B + "[/img][/quote]"); check(parse(api, response).isEmpty(), "quoted image in original excluded");
        original.put("content", "[crypt][img]" + B + "[/img][/crypt]"); check(parse(api, response).isEmpty(), "encrypted first post excluded");
        original.put("content", "[img]https://img.nga.cn/avatars/something.jpg[/img]"); check(parse(api, response).isEmpty(), "avatar path cannot become post preview");
        response = fixture(); response.getJSONObject("data").getJSONObject("__T").put("tid", "124"); rejected(api, response, "wrong thread metadata rejected");
        for (Object page : new Object[]{2, 0, -1, "1.0", JSONObject.NULL}) { response = fixture(); response.getJSONObject("data").put("__PAGE", page); rejected(api, response, "wrong or ambiguous page rejected"); }
        response = fixture(); response.getJSONObject("data").remove("__PAGE"); rejected(api, response, "missing page marker rejected");
        response = fixture(); response.getJSONObject("data").remove("__T"); rejected(api, response, "missing topic identity rejected");
        response = fixture(); response.getJSONObject("data").remove("__R"); rejected(api, response, "missing post rows rejected");
        response = fixture(); response.getJSONObject("data").put("__R", "[img]" + B + "[/img]"); rejected(api, response, "opaque rows rejected");
        response = fixture(); response.getJSONObject("data").put("__R", new JSONArray().put(row("777", 1, "42", "[img]" + B + "[/img]"))); rejected(api, response, "reply-only page not treated as original");
        response = fixture(); original = response.getJSONObject("data").getJSONArray("__R").getJSONObject(0); original.put("tid", "124"); rejected(api, response, "first floor belongs to requested topic");
        response = fixture(); original = response.getJSONObject("data").getJSONArray("__R").getJSONObject(0); original.put("authorid", "99"); rejected(api, response, "first floor matches topic author");
        response = fixture(); response.getJSONObject("data").getJSONArray("__R").put(row("0", 0, "42", "[img]" + B + "[/img]")); rejected(api, response, "duplicate original markers are ambiguous and rejected");
        response = fixture(); original = response.getJSONObject("data").getJSONArray("__R").getJSONObject(0); original.put("index", 1); rejected(api, response, "contradictory original floor markers rejected");
        response = fixture(); original = response.getJSONObject("data").getJSONArray("__R").getJSONObject(0); original.put("pid", "777"); rejected(api, response, "positive reply PID cannot become original through floor zero");
        response = fixture(); original = response.getJSONObject("data").getJSONArray("__R").getJSONObject(0); original.remove("lou"); original.put("index", 0); original.put("pid", "777"); rejected(api, response, "positive reply PID cannot become original through index zero");
        response = fixture(); original = response.getJSONObject("data").getJSONArray("__R").getJSONObject(0); original.remove("lou"); original.remove("pid"); rejected(api, response, "first row position alone does not identify original");
        response = fixture(); original = response.getJSONObject("data").getJSONArray("__R").getJSONObject(0); original.remove("lou"); check(parse(api, response).equals(Arrays.asList(A)), "explicit pid zero identifies original");
        response = fixture(); original = response.getJSONObject("data").getJSONArray("__R").getJSONObject(0); original.remove("pid"); check(parse(api, response).equals(Arrays.asList(A)), "explicit floor zero identifies original");
        response = fixture(); original = response.getJSONObject("data").getJSONArray("__R").getJSONObject(0); original.remove("pid"); original.remove("lou"); original.put("index", 0); check(parse(api, response).equals(Arrays.asList(A)), "explicit index zero identifies original");
        response = fixture(); data = response.getJSONObject("data"); data.getJSONObject("__T").remove("authorid"); check(parse(api, response).equals(Arrays.asList(A)), "absent optional author metadata preserves explicit original identity");
        response = fixture(); data = response.getJSONObject("data"); JSONArray rows = data.getJSONArray("__R"); data.put("__R", new JSONObject().put("1", rows.getJSONObject(1)).put("0", rows.getJSONObject(0))); check(parse(api, response).equals(Arrays.asList(A)), "numeric-index object rows supported independent of iteration order");
        response = fixture(); original = response.getJSONObject("data").getJSONArray("__R").getJSONObject(0); original.put("content", new JSONObject().put("image", "[img]" + B + "[/img]")); rejected(api, response, "unknown object body cannot become preview content");
        response = fixture(); original = response.getJSONObject("data").getJSONArray("__R").getJSONObject(0); original.remove("content"); check(parse(api, response).isEmpty(), "missing original body has no preview and never uses replies");
        response = fixture().getJSONObject("data"); check(parse(api, response).equals(Arrays.asList(A)), "root native envelope supported");
        response = fixture(); response.put("error", new JSONArray().put("permission denied")); rejected(api, response, "server permission error never becomes a cached no-image result");
        response = fixture(); original=response.getJSONObject("data").getJSONArray("__R").getJSONObject(0);
        StringBuilder images=new StringBuilder();for(int i=0;i<90;i++)images.append("[img]https://img.nga.cn/attachments/mon_202610/23/op_").append(i).append(".jpg[/img]");
        original.put("content",images.toString()).put("recommend","10,12,2");
        List<String> bounded=parse(api,response);check(bounded.size()==64&&bounded.get(63).endsWith("op_63.jpg"),"gallery retains original ordered prefix independently from three-thumbnail list limit");
        TopicPreviewImages metadata=new TopicPreviewImages((scope,tid)->{throw new AssertionError("NO_SECOND_READ_REQUIRED");},Runnable::run);
        String scope=String.join("",java.util.Collections.nCopies(64,"a"));
        try{metadata.seed(scope,"123",bounded);TopicPreviewImages.Result result=metadata.peek(scope,"123");
            check(result.sourceTotal==90&&result.totalKnown&&result.truncated,"actual API original-floor parse carries true full total through bounded URL list");
            check(result.votes().likesKnown&&result.votes().likes==12&&result.votes().dislikesKnown&&result.votes().dislikes==2,"same original-floor read carries independent support and oppose totals");
            response=fixture();original=response.getJSONObject("data").getJSONArray("__R").getJSONObject(0);original.remove("content");original.put("recommend","8,10,2");
            metadata.seed(scope,"124",parse(api,response));result=metadata.peek(scope,"124");
            check(result.urls.isEmpty()&&result.votes().likes==10&&result.votes().dislikes==2,"no-image original floor still reuses counts without another request");
        }finally{metadata.close();}
        System.out.println("FirstPostImagesTest: " + assertions + " assertions PASS"); return assertions;
    }
}
