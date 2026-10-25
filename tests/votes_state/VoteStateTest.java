package cn.ngapure.app;

import java.util.Arrays;
import java.util.Map;
import org.json.JSONObject;

/** Behavioural read/write ordering, ownership and visible-count regressions; no Android/network. */
public final class VoteStateTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
    private static VoteMetrics metrics(Object value) throws Exception {
        return VoteMetrics.from(new JSONObject().put("recommend", value));
    }
    public static void main(String[] args) throws Exception {
        VoteMetrics exact = metrics("0,9,2"), scalar = metrics(7);
        VoteMetrics merged = exact.supplementedBy(scalar);
        check(merged.likes == 9 && merged.dislikes == 2 && merged.countsKnown(), "supplemental net value cannot downgrade known totals");
        check(merged != exact && merged != scalar, "supplement owns its mutable output");
        check(!scalar.countsKnown() && scalar.score == 7, "net-only source is unchanged");
        check(!new VoteMetrics().supplementedBy(scalar).countsKnown(), "unknown totals never become the net value");
        check(exact.supplementedBy(null).countsKnown(), "missing floor preserves supplied exact totals");
        check(exact.supplementedBy(metrics("0,12,4")).likes == 12, "new exact counts replace old exact counts");
        check(exact.supplementedBy(metrics("0,0,0")).dislikes == 0, "explicit zero is usable data");
        VoteMetrics one = new VoteMetrics(); one.dislikesKnown = true; one.dislikes = 5;
        check(exact.supplementedBy(one).likes == 9 && exact.supplementedBy(one).dislikes == 5, "independent totals merge independently");
        String script = "<script>postArg.proc(0,null,null,null,null,null,null,null,null,null,0,0,null,1,123,7);"
                + "postArg.proc(1,null,null,null,null,null,null,null,null,null,456,0,null,1,123,'0,8,3');</script>";
        Map<String, VoteMetrics> read = VoteMetrics.htmlPosts(script);
        check(read.size() == 2 && !read.get("0:0").countsKnown(), "actual supplemental parser admits score-only rows");
        check(exact.supplementedBy(read.get("0:0")).countsKnown(), "mixed page cannot erase one floor while filling another");
        check(new VoteMetrics().supplementedBy(read.get("1:456")).dislikes == 3, "missing independent counts can be filled");

        VoteMetrics.ConfirmedSelections state = new VoteMetrics.ConfirmedSelections();
        state.useScope("account-A-host-1-token-1"); long before = state.revision();
        check(state.accepts("account-A-host-1-token-1", before), "initial page read is live");
        state.confirmed("account-A-host-1-token-1", "123", "0", true, 1);
        check(!state.accepts("account-A-host-1-token-1", before), "late pre-like response is retired");
        check(state.selected("account-A-host-1-token-1", "123", "0") == 1, "confirmed topic head selection retained");
        check(state.selected("account-A-host-1-token-1", "124", "0") == 0, "different topic heads do not share pid zero");
        check(state.selected("account-A-host-1-token-1", "123", "456") == 0, "different reply remains unselected");
        long afterLike = state.revision();
        state.confirmed("account-A-host-1-token-1", "123", "456", true, -1);
        check(!state.accepts("account-A-host-1-token-1", afterLike), "a newer floor write retires a whole-page old read");
        check(state.selected("account-A-host-1-token-1", "123", "0") == 1 && state.selected("account-A-host-1-token-1", "123", "456") == -1, "independent floor selections survive");
        long afterDown = state.revision();
        state.confirmed("account-A-host-1-token-1", "123", "0", false, 0);
        check(state.selected("account-A-host-1-token-1", "123", "0") == 1, "zero official flags do not claim removal");
        check(!state.accepts("account-A-host-1-token-1", afterDown), "zero flags still invalidate old counts");
        state.confirmed("account-A-host-1-token-1", "123", "0", true, 0);
        check(state.selected("account-A-host-1-token-1", "123", "0") == 0, "confirmed removal clears selection");
        String[] saved = state.encode("account-A-host-1-token-1");
        VoteMetrics.ConfirmedSelections restored = new VoteMetrics.ConfirmedSelections();
        restored.restore("account-A-host-1-token-1", saved);
        check(restored.selected("account-A-host-1-token-1", "123", "456") == -1, "same-session Activity recreation keeps confirmed state");
        check(Arrays.equals(saved, restored.encode("account-A-host-1-token-1")), "restore keeps known removals as well as selections");
        saved[2] = "999";
        check(restored.selected("account-A-host-1-token-1", "123", "456") == -1, "saved array is not retained by reference");
        for (String other : new String[]{"account-B-host-1-token-1", "account-A-host-2-token-1", "account-A-host-1-token-2", "logged-out"}) {
            restored.restore(other, state.encode("account-A-host-1-token-1"));
            check(restored.selected(other, "123", "456") == 0, "scope mismatch cannot restore selection: " + other);
        }
        state.useScope("new-account");state.useScope("account-A-host-1-token-1");
        check(state.selected("account-A-host-1-token-1", "123", "456") == 0, "account round trip cannot revive cleared entries");
        Object[] bad = {null, "bad", new String[0], new String[]{"scope","1"},
                new String[]{"scope","0","0","1"}, new String[]{"scope","1","-1","1"},
                new String[]{"scope","1","0","2"}, new String[]{"scope","1","0",null},
                new String[]{"scope","1","0","1","1","0","-1"},
                new String[]{"scope","9999999999999999999","0","1"}};
        for (Object value : bad) {
            restored.confirmed("scope", "1", "0", true, 1);restored.restore("scope",value);
            check(restored.selected("scope", "1", "0") == 0, "malformed saved state is rejected atomically");
        }
        for (int i = 1; i <= 200; i++) restored.confirmed("bounded", String.valueOf(i), "0", true, 1);
        check(restored.encode("bounded").length == 1 + 128 * 3, "saved state remains bounded");
        check(restored.selected("bounded", "1", "0") == 0 && restored.selected("bounded", "200", "0") == 1, "bound retains newest confirmed entries");
        long beforeEviction = restored.revision();restored.confirmed("bounded", "201", "0", true, -1);
        check(!restored.accepts("bounded", beforeEviction), "eviction cannot make old read revision current again");
        check(!restored.accepts("other-scope", restored.revision()), "revision alone never authorizes another account");
        check(!new VoteMetrics().supplementedBy(metrics(-2)).countsKnown(), "negative net delta cannot reconstruct totals after mutation");
        System.out.println("Vote state checks passed: " + assertions);
    }
}
