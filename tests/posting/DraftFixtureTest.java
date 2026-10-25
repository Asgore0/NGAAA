package cn.ngapure.app;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Collections;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** In-memory SharedPreferences contract checks; no Android process or disk required. */
public final class DraftFixtureTest {
    private static int checks;
    private static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    private static final class MemoryPreferences implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        int commits;
        boolean rejectCommits;
        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
        @Override public String getString(String key, String fallback) { Object v = values.get(key); return v instanceof String ? (String) v : fallback; }
        @Override public Set<String> getStringSet(String key, Set<String> fallback) { return fallback; }
        @Override public int getInt(String key, int fallback) { return fallback; }
        @Override public long getLong(String key, long fallback) { return fallback; }
        @Override public float getFloat(String key, float fallback) { return fallback; }
        @Override public boolean getBoolean(String key, boolean fallback) { return fallback; }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public Editor edit() { return new Editor() {
            final Map<String, Object> additions = new HashMap<>(); final Set<String> removals = new HashSet<>(); boolean clear;
            @Override public Editor putString(String k, String v) { additions.put(k, v); return this; }
            @Override public Editor putStringSet(String k, Set<String> v) { additions.put(k, v); return this; }
            @Override public Editor putInt(String k, int v) { additions.put(k, v); return this; }
            @Override public Editor putLong(String k, long v) { additions.put(k, v); return this; }
            @Override public Editor putFloat(String k, float v) { additions.put(k, v); return this; }
            @Override public Editor putBoolean(String k, boolean v) { additions.put(k, v); return this; }
            @Override public Editor remove(String k) { removals.add(k); return this; }
            @Override public Editor clear() { clear = true; return this; }
            @Override public boolean commit() { commits++; if (rejectCommits) return false; apply(); return true; }
            @Override public void apply() { if (clear) values.clear(); for (String k : removals) values.remove(k); values.putAll(additions); }
        }; }
    }
    public static void main(String[] args) throws Exception {
        MemoryPreferences preferences = new MemoryPreferences(); NativeDrafts drafts = new NativeDrafts(preferences);
        NativeDrafts.Draft first = drafts.save("reply", "7", "456", "789", "中文标题", "[b]正文[/b]\n第二行");
        check(first != null && first.updatedAt > 0, "Timestamped draft");
        NativeDrafts.Draft loaded = drafts.get("reply", "7", "456", "789");
        check(loaded != null && loaded.subject.equals("中文标题") && loaded.content.equals("[b]正文[/b]\n第二行"), "Draft content roundtrip");
        check(drafts.get("quote", "7", "456", "789") == null && drafts.get("reply", "7", "456", "790") == null, "Action and target isolate draft keys");
        JSONObject serialized = new JSONObject(preferences.getString(first.key, ""));
        check(serialized.length() == 9 && !serialized.has("auth") && !serialized.has("cookie") && !serialized.has("attachments_check"), "Only editor text/identity/time and safe recovery state persisted");
        NativeDrafts.Draft newer = drafts.save("reply", "7", "456", "789", "修改标题", "修改正文");
        check(newer.updatedAt > first.updatedAt, "Version monotonically changes even within one millisecond");
        drafts.delete(first);
        check(drafts.get("reply", "7", "456", "789").content.equals("修改正文"), "Late success cannot delete a newer version");
        drafts.delete(newer);
        check(drafts.get("reply", "7", "456", "789") == null, "Exact successful version removed");
        drafts.save("new", "7", "0", "0", "新草稿", "正文");
        check(drafts.get("new", "7", "", "") != null, "Zero IDs normalize to empty IDs");
        check(drafts.save("new", "7", "", "", " ", "\n ") == null && drafts.getAll().isEmpty(), "Empty text clears a draft");
        boolean invalid = false;
        try { drafts.save("reply", "7:456", "789", "", "x", "x"); } catch (IllegalArgumentException expected) { invalid = true; }
        check(invalid, "Identifier separators cannot collide with other keys");
        preferences.values.put("draft:modify:7:1:", "{broken");
        preferences.values.put("draft:reply:7:1:", serialized.toString());
        check(drafts.getAll().size() == 2 && drafts.getAll().get(0).submissionUnconfirmed
                && !drafts.getAll().get(0).recoveryIssue.isEmpty(), "Malformed or mismatched identities remain visibly blocked");
        for (NativeDrafts.Draft damaged : drafts.getAll()) drafts.delete(damaged);
        for (int i = 1; i <= 25; i++) drafts.save("reply", "7", Integer.toString(i), "", "草稿" + i, "正文");
        List<NativeDrafts.Draft> all = drafts.getAll();
        check(all.size() == 20, "Draft retention bounded to 20");
        boolean sorted = true; for (int i = 1; i < all.size(); i++) if (all.get(i - 1).updatedAt < all.get(i).updatedAt) sorted = false;
        check(sorted, "Drafts newest first");
        boolean immutable = false; try { all.add(first); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "Returned draft list immutable");
        String quotes = String.join("", Collections.nCopies(500000, "\""));
        drafts.save("modify", "7", "100", "200", "很多引号", quotes);
        check(drafts.get("modify", "7", "100", "200").content.length() == 500000, "Escaped large draft remains readable at supported limit");
        for (int i = 101; i <= 105; i++) drafts.save("modify", "7", Integer.toString(i), "200", "大草稿", quotes);
        long total = 0L; for (NativeDrafts.Draft d : drafts.getAll()) total += d.subject.length() + (long) d.content.length();
        check(total <= 2000000, "Total retained text bounded");
        attachmentAndPendingRecovery();
        protectedQuotaRecovery();
        corruptStorageRecovery();
        ratingRecovery();
        System.out.println("NativeDrafts offline checks passed: " + checks);
    }
    private static void ratingRecovery() throws Exception {
        MemoryPreferences disk = new MemoryPreferences(); NativeDrafts drafts = new NativeDrafts(disk);
        String scope = String.join("", Collections.nCopies(64, "a"));
        Map<String, String> unfinished = new java.util.LinkedHashMap<>(); unfinished.put("1", "7.50"); unfinished.put("2", "");
        NativeDrafts.Draft first = drafts.save("reply", "7", "456", "", "", "", Collections.emptyList(), false, unfinished, true, scope);
        check(first != null && first.ratingEnabled && first.ratingValues.get("2").isEmpty(), "Incomplete score-only draft survives without body text");
        NativeDrafts.Draft restored = new NativeDrafts(disk).get("reply", "7", "456", "");
        check(restored.ratingScope.equals(scope) && restored.ratingValues.equals(unfinished) && !restored.submissionUnconfirmed, "Score inputs and account scope survive recreation exactly");
        boolean immutable = false; try { restored.ratingValues.put("3", "8"); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "Draft rating map immutable");
        JSONObject serialized = new JSONObject(disk.getString(first.key, ""));
        check(serialized.getJSONObject("topicRating").getJSONObject("values").getString("1").equals("7.50") && !serialized.toString().contains("ngaPassport"), "Public score inputs stored without Cookie or account identity");
        NativeDrafts.Draft oldApi = drafts.save("reply", "7", "456", "", "", "稍后补充正文");
        check(oldApi.ratingValues.equals(unfinished) && oldApi.ratingEnabled && oldApi.ratingScope.equals(scope), "Host's existing public save API cannot drop score draft metadata");
        NativeDrafts.Draft disabled = drafts.save("reply", "7", "456", "", "", "仅评论", Collections.emptyList(), false, unfinished, false, scope);
        check(!disabled.ratingEnabled && disabled.ratingValues.equals(unfinished), "Turning rating off retains unfinished inputs for future explicit editing");
        Map<String, String> complete = new java.util.LinkedHashMap<>(); complete.put("1", "7.5"); complete.put("2", "9");
        int before = disk.commits;
        NativeDrafts.Draft sent = drafts.saveForSubmission("reply", "7", "456", "", "", "确认发送这条评分回复", Collections.emptyList(), complete, true, scope);
        check(disk.commits == before + 1 && sent.submissionUnconfirmed && sent.ratingValues.equals(complete), "Exact confirmed score snapshot commits before POST");
        NativeDrafts afterDeath = new NativeDrafts(disk); NativeDrafts.Draft unknown = afterDeath.get("reply", "7", "456", "");
        check(unknown.submissionUnconfirmed && unknown.ratingEnabled && unknown.ratingValues.equals(complete), "Process death preserves scoring publish guard and exact submitted scores");
        boolean blocked = false;
        try { afterDeath.saveForSubmission("reply", "7", "456", "", "", "不得重发", Collections.emptyList(), unfinished, false, scope); } catch (NativeDrafts.UnconfirmedSubmissionException expected) { blocked = true; }
        check(blocked && afterDeath.get("reply", "7", "456", "").ratingValues.equals(complete), "Reopening cannot overwrite unknown submitted scores or publish duplicate");
        check(afterDeath.rejected(sent), "Exact explicit server rejection clears scoring pending marker");
        NativeDrafts.Draft rejected = afterDeath.get("reply", "7", "456", "");
        check(!rejected.submissionUnconfirmed && rejected.ratingValues.equals(complete) && rejected.ratingEnabled, "Rejected score form remains editable with all dimensions");
        Map<String, String> newerScores = new java.util.LinkedHashMap<>(complete); newerScores.put("2", "8");
        NativeDrafts.Draft newer = afterDeath.save("reply", "7", "456", "", "", rejected.content, Collections.emptyList(), false, newerScores, true, scope);
        afterDeath.delete(rejected);
        check(afterDeath.get("reply", "7", "456", "").ratingValues.equals(newerScores), "Late earlier success cannot delete a newer score edit with same body");
        afterDeath.delete(newer); check(afterDeath.get("reply", "7", "456", "") == null, "Exact successful score version removable");
        boolean invalidScope = false;
        try { drafts.save("reply", "7", "456", "", "", "", Collections.emptyList(), false, unfinished, true, ""); } catch (IllegalArgumentException expected) { invalidScope = true; }
        check(invalidScope && disk.values.isEmpty(), "Missing account scope blocks score save atomically");
        Map<String, String> malicious = new java.util.LinkedHashMap<>(); malicious.put("1~2", "7");
        boolean invalidKey = false; try { drafts.save("reply", "7", "456", "", "", "", Collections.emptyList(), false, malicious, true, scope); } catch (IllegalArgumentException expected) { invalidKey = true; }
        check(invalidKey && disk.values.isEmpty(), "Encoded arbitrary dimension keys rejected before storing");
        NativeDrafts.Draft valid = drafts.save("reply", "7", "456", "", "", "保留原始恢复数据", Collections.emptyList(), false, complete, true, scope);
        String original = disk.getString(valid.key, "");
        Object[] invalid = {"wrong", new JSONObject().put("enabled", "true").put("scope", scope).put("values", new JSONObject(complete)),
                new JSONObject().put("enabled", true).put("scope", "raw-account-id").put("values", new JSONObject(complete)),
                new JSONObject().put("enabled", true).put("scope", scope).put("values", new JSONObject().put("1", 9)),
                new JSONObject().put("enabled", true).put("scope", scope).put("values", new JSONObject().put("1~2", "9"))};
        for (Object corrupt : invalid) {
            String raw = new JSONObject(original).put("topicRating", corrupt).toString(); disk.values.put(valid.key, raw);
            NativeDrafts.Draft damaged = new NativeDrafts(disk).get("reply", "7", "456", "");
            check(damaged.submissionUnconfirmed && !damaged.recoveryIssue.isEmpty() && disk.getString(valid.key, "").equals(raw), "Damaged score metadata retained and blocked, never silently discarded");
        }
    }
    private static void protectedQuotaRecovery() throws Exception {
        MemoryPreferences disk = new MemoryPreferences(); NativeDrafts drafts = new NativeDrafts(disk);
        for (int i = 1; i <= 20; i++) drafts.saveForSubmission("reply", "7", Integer.toString(i), "", "", "未确认的发布 " + i, Collections.emptyList());
        Map<String, Object> before = new HashMap<>(disk.values); int commits = disk.commits;
        boolean blocked = false;
        try { drafts.save("reply", "7", "21", "", "新草稿", "不得挤掉未知发布标记"); } catch (NativeDrafts.CapacityException expected) { blocked = true; }
        check(blocked && disk.values.equals(before), "Twenty unconfirmed markers reject the twenty-first draft without changing storage");
        blocked = false;
        try { drafts.saveForSubmission("reply", "7", "21", "", "新标题", "不得绕过耐久保护提交", Collections.emptyList()); } catch (NativeDrafts.CapacityException expected) { blocked = true; }
        check(blocked && disk.values.equals(before) && disk.commits == commits, "Full protected quota refuses publishing snapshot before commit/request");
        check(new NativeDrafts(disk).getAll().size() == 20 && new NativeDrafts(disk).get("reply", "7", "1", "").submissionUnconfirmed,
                "All twenty pending attempts survive process recreation and quota rejection");
        drafts.save("reply", "7", "1", "", "", "可在原位置保存较小修改", Collections.emptyList(), false);
        check(drafts.get("reply", "7", "1", "").submissionUnconfirmed, "Stale ordinary editor overload cannot clear a newer publishing marker");

        MemoryPreferences mixed = new MemoryPreferences(); NativeDrafts retained = new NativeDrafts(mixed);
        NativeDrafts.Draft pending1 = retained.saveForSubmission("reply", "7", "1", "", "", "最旧未知结果", Collections.emptyList());
        NativeDrafts.Draft pending2 = retained.saveForSubmission("reply", "7", "2", "", "", "另一个未知结果", Collections.emptyList());
        Object raw1 = mixed.values.get(pending1.key), raw2 = mixed.values.get(pending2.key);
        for (int i = 3; i <= 20; i++) retained.save("reply", "7", Integer.toString(i), "", "", "可清理普通草稿" + i);
        String oldestOrdinary = retained.getAll().stream().filter(d -> !d.submissionUnconfirmed)
                .min((a, b) -> Long.compare(a.updatedAt, b.updatedAt)).get().key;
        NativeDrafts.Draft newest = retained.save("reply", "7", "21", "", "", "新快照必须留下");
        check(mixed.values.get(pending1.key).equals(raw1) && mixed.values.get(pending2.key).equals(raw2), "Count eviction leaves old pending markers byte-for-byte intact");
        check(retained.getAll().size() == 20 && !mixed.values.containsKey(oldestOrdinary) && mixed.values.containsKey(newest.key), "Quota evicts oldest ordinary draft while retaining the new snapshot");

        String large = String.join("", Collections.nCopies(500000, "字"));
        MemoryPreferences fullText = new MemoryPreferences(); NativeDrafts text = new NativeDrafts(fullText);
        for (int i = 1; i <= 4; i++) text.saveForSubmission("reply", "7", Integer.toString(i), "", "", large, Collections.emptyList());
        Map<String, Object> fullBefore = new HashMap<>(fullText.values); int textCommits = fullText.commits;
        blocked = false;
        try { text.save("reply", "7", "5", "", "", "一"); } catch (NativeDrafts.CapacityException expected) { blocked = true; }
        check(blocked && fullText.values.equals(fullBefore), "Two million protected characters reject a new draft without pruning pending attempts");
        blocked = false;
        try { text.save("reply", "7", "1", "", "增加标题", large); } catch (NativeDrafts.CapacityException expected) { blocked = true; }
        check(blocked && fullText.values.equals(fullBefore), "Over-quota edit preserves the original pending body and all other records");
        blocked = false;
        try { text.save("reply", "7", "1", "", "", large + "字"); } catch (IllegalArgumentException expected) { blocked = true; }
        check(blocked && fullText.values.equals(fullBefore) && fullText.commits == textCommits, "Per-draft oversized edit preserves exact pending snapshot without commit");
        JSONObject oversizedLegacy = new JSONObject(fullText.getString("draft:reply:7:1:", ""));
        oversizedLegacy.put("tid", "5"); fullText.values.put("draft:reply:7:5:", oversizedLegacy.toString());
        Map<String, Object> legacyBefore = new HashMap<>(fullText.values);
        check(new NativeDrafts(fullText).getAll().size() == 5, "Preexisting over-quota pending drafts remain visible without automatic pruning");
        blocked = false;
        try { text.saveForSubmission("reply", "7", "6", "", "", "不能覆盖老的超额记录", Collections.emptyList()); } catch (NativeDrafts.CapacityException expected) { blocked = true; }
        check(blocked && fullText.values.equals(legacyBefore), "Preexisting protected overflow rejects a new publishing snapshot atomically");

        for (int i = 22; i <= 26; i++) retained.save("reply", "7", Integer.toString(i), "", "", large);
        long chars = 0L; for (NativeDrafts.Draft d : retained.getAll()) chars += d.subject.length() + (long) d.content.length();
        check(chars <= 2000000L && mixed.values.get(pending1.key).equals(raw1) && mixed.values.get(pending2.key).equals(raw2),
                "Character quota may evict multiple ordinary drafts but never protected markers");
        check(retained.get("reply", "7", "26", "").content.equals(large), "Newest large snapshot is never silently discarded by quota cleanup");
    }
    private static void corruptStorageRecovery() throws Exception {
        MemoryPreferences disk = new MemoryPreferences(); NativeDrafts drafts = new NativeDrafts(disk);
        String key = "draft:reply:7:456:";
        disk.values.put(key, "{broken"); disk.values.put("draft:modify:7:1:", Integer.valueOf(12));
        disk.values.put("draft:new:7::", ""); disk.values.put("draft:invalid-key", "{broken-invalid-key");
        Map<String, Object> before = new HashMap<>(disk.values);
        NativeDrafts.Draft damaged = drafts.get("reply", "7", "456", "");
        check(damaged != null && damaged.submissionUnconfirmed && !damaged.recoveryIssue.isEmpty(), "Unparseable raw draft cannot become an absent/new editor target");
        check(drafts.getAll().size() == 4 && drafts.getAll().stream().allMatch(d -> d.submissionUnconfirmed && !d.recoveryIssue.isEmpty()),
                "Malformed, wrong-type, empty and malformed-key records remain visible and protected");
        check(!damaged.recoveryIssue.contains("{broken") && damaged.content.isEmpty(), "Raw corrupt storage is never displayed as editable content or leaked through errors");
        boolean blocked = false;
        try { drafts.save("reply", "7", "456", "", "", ""); } catch (NativeDrafts.RecoveryException expected) { blocked = true; }
        check(blocked && disk.values.equals(before), "Empty ordinary save cannot erase a damaged marker");
        blocked = false;
        try { drafts.save("reply", "7", "456", "", "新正文", "不能覆盖原始恢复记录", Collections.emptyList(), false); } catch (NativeDrafts.RecoveryException expected) { blocked = true; }
        check(blocked && disk.values.equals(before), "Editor-specific save cannot overwrite damaged raw state");
        blocked = false;
        try { drafts.saveForSubmission("reply", "7", "456", "", "", "不能重新提交", Collections.emptyList()); } catch (NativeDrafts.RecoveryException expected) { blocked = true; }
        check(blocked && disk.values.equals(before) && disk.commits == 0, "Damaged marker blocks commit-before-send instead of bypassing it");
        blocked = false;
        try { drafts.saveAfterReview("reply", "7", "456", "", "", "不能用空恢复数据覆盖", Collections.emptyList()); } catch (NativeDrafts.RecoveryException expected) { blocked = true; }
        check(blocked && disk.values.equals(before), "Outcome acknowledgement cannot clear an unparseable recovery record");
        check(!drafts.rejected(damaged) && disk.values.equals(before), "Definite-result callback cannot clear corrupt storage without a matching publish snapshot");
        disk.values.put(key, "{different-broken"); drafts.delete(damaged);
        check(disk.values.get(key).equals("{different-broken"), "Stale explicit deletion does not erase a newer damaged raw version");
        drafts.delete(drafts.get("reply", "7", "456", ""));
        check(!disk.values.containsKey(key) && disk.values.size() == 3, "Explicit deletion may remove only the reviewed damaged raw version");
        for (int i = 1; i <= 20; i++) drafts.save("reply", "8", Integer.toString(i), "", "", "普通草稿");
        check(disk.values.get("draft:invalid-key").equals(before.get("draft:invalid-key")) && disk.values.get("draft:modify:7:1:").equals(Integer.valueOf(12)),
                "Retention pressure cannot silently remove unknown damaged markers");
    }
    private static String fixture(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("tests/posting/fixtures/" + name)), StandardCharsets.UTF_8);
    }
    private static void attachmentAndPendingRecovery() throws Exception {
        MemoryPreferences disk = new MemoryPreferences();
        String key = "draft:quote:7:456:789";
        String quoteFixture = fixture("draft-quote-uploaded.json");
        disk.values.put(key, quoteFixture);
        NativeDrafts recovered = new NativeDrafts(disk);
        NativeDrafts.Draft quote = recovered.get("quote", "7", "456", "789");
        String exact = new JSONObject(quoteFixture).getString("content");
        check(quote != null && quote.content.equals(exact), "Quote draft preserves exact whitespace, entities, metadata and image position");
        check(quote.pendingAttachments.size() == 1 && quote.pendingAttachments.get(0).fileName.equals("原图 空格.png"), "Restored file name and type retained without credentials");
        NativeDrafts.PendingAttachment file = quote.pendingAttachments.get(0);
        check(file.reference().equals("./mon_202610/18/recovery-image.png") && file.referencedBy(exact), "Pending reference is the original public relative path");
        check(file.referencedBy(exact.replace("[img]", "[IMG]")), "Changing presentation tags cannot bypass the attachment recovery gate");
        NativeDrafts.Draft saved = recovered.save("quote", "7", "456", "789", quote.subject, quote.content);
        NativeDrafts.Draft restarted = new NativeDrafts(disk).get("quote", "7", "456", "789");
        check(restarted.content.equals(exact) && restarted.pendingAttachments.size() == 1, "Old public save API keeps exact content and attachment recovery across recreation");
        JSONObject serialized = new JSONObject(disk.getString(saved.key, ""));
        JSONObject metadata = serialized.getJSONArray("pendingAttachments").getJSONObject(0);
        check(metadata.length() == 3 && metadata.has("fileName") && metadata.has("mimeType") && metadata.has("bbcode"), "Only three safe attachment metadata fields serialized");
        check(!serialized.toString().contains("content://") && !serialized.has("auth") && !serialized.has("attachments_check"), "No SAF permission, auth or checksum persisted");
        String replacement = "./mon_202610/18/reselected-image.webp";
        NativeDrafts.PendingAttachment newFile = new NativeDrafts.PendingAttachment("新图.webp", "image/webp", "[img]" + replacement + "[/img]");
        String replaced = file.replaceIn(restarted.content, newFile);
        recovered.save("quote", "7", "456", "789", quote.subject, replaced, Collections.singletonList(newFile), false);
        NativeDrafts.Draft changed = new NativeDrafts(disk).get("quote", "7", "456", "789");
        check(changed.content.equals(exact.replace(file.reference(), replacement)) && changed.content.indexOf(replacement) == exact.indexOf(file.reference()), "Reselection changes only resource at original attachment position");
        String explicitlyRemoved = newFile.removeFrom(changed.content);
        recovered.save("quote", "7", "456", "789", quote.subject, explicitlyRemoved);
        check(recovered.get("quote", "7", "456", "789").content.equals(explicitlyRemoved) && recovered.get("quote", "7", "456", "789").pendingAttachments.isEmpty(), "Explicit reference removal drops metadata without modifying remaining text");
        String[] badRefs = {"[img]https://evil.example/x.png[/img]", "[img]./mon_202610/18/x.png?auth=secret[/img]", "[img]./mon_202610/18/../x.png[/img]", "[img]content://private/image[/img]"};
        for (String ref : badRefs) {
            boolean blocked = false; try { new NativeDrafts.PendingAttachment("x.png", "image/png", ref); } catch (IllegalArgumentException expected) { blocked = true; }
            check(blocked, "Unsafe attachment recovery reference rejected");
        }
        boolean badMime = false; try { new NativeDrafts.PendingAttachment("x.png", "text/html", file.bbcode); } catch (IllegalArgumentException expected) { badMime = true; }
        check(badMime, "Attachment recovery MIME allowlist enforced");
        String boundary = String.join("", Collections.nCopies(500000 - file.reference().length(), "字")) + file.reference();
        boolean overlong = false; try { file.replaceIn(boundary, new NativeDrafts.PendingAttachment("long.png", "image/png", "[img]./mon_202610/18/a-much-longer-image-resource-name.png[/img]")); } catch (IllegalArgumentException expected) { overlong = true; }
        check(overlong && boundary.length() == 500000 && boundary.endsWith(file.reference()), "Overlong replacement fails without truncating or mutating original text");
        String pendingKey = "draft:reply:7:456:";
        disk.values.put(pendingKey, fixture("draft-submission-pending.json"));
        NativeDrafts afterDeath = new NativeDrafts(disk);
        NativeDrafts.Draft uncertain = afterDeath.get("reply", "7", "456", "");
        check(uncertain.submissionUnconfirmed && uncertain.pendingAttachments.size() == 1, "Process recreation retains unconfirmed publishing state and attachment metadata");
        NativeDrafts.Draft edited = afterDeath.save("reply", "7", "456", "", uncertain.subject, uncertain.content + "\n继续编辑");
        check(edited.submissionUnconfirmed && new NativeDrafts(disk).get("reply", "7", "456", "").submissionUnconfirmed, "Editing or ordinary save cannot silently clear unconfirmed result protection");
        check(!afterDeath.rejected(uncertain) && afterDeath.get("reply", "7", "456", "").submissionUnconfirmed, "Late rejection cannot unlock a newer version");
        int beforeCommit = disk.commits;
        NativeDrafts.Draft reviewed = afterDeath.saveAfterReview("reply", "7", "456", "", edited.subject, edited.content, edited.pendingAttachments);
        check(disk.commits == beforeCommit + 1 && !reviewed.submissionUnconfirmed, "Explicit user review clears protection durably");
        NativeDrafts.Draft inFlight = afterDeath.saveForSubmission("reply", "7", "456", "", reviewed.subject, reviewed.content, reviewed.pendingAttachments);
        check(disk.commits == beforeCommit + 2 && new NativeDrafts(disk).get("reply", "7", "456", "").submissionUnconfirmed, "Publishing snapshot is durably unconfirmed before any request starts");
        boolean duplicateBlocked = false;
        try { new NativeDrafts(disk).saveForSubmission("reply", "7", "456", "", "另一个编辑器", "不能绕过现有未确认请求", Collections.emptyList()); }
        catch (NativeDrafts.UnconfirmedSubmissionException expected) { duplicateBlocked = true; }
        check(duplicateBlocked && disk.commits == beforeCommit + 2, "Another editor instance cannot overwrite or bypass an unconfirmed publishing attempt");
        check(afterDeath.get("reply", "7", "456", "").content.equals(inFlight.content), "Blocked duplicate leaves exact publishing recovery content intact");
        check(afterDeath.rejected(inFlight) && !new NativeDrafts(disk).get("reply", "7", "456", "").submissionUnconfirmed, "Definite server rejection durably clears only exact attempt");
        NativeDrafts.Draft accepted = afterDeath.saveForSubmission("reply", "7", "456", "", reviewed.subject, reviewed.content, reviewed.pendingAttachments);
        afterDeath.delete(accepted);
        check(afterDeath.get("reply", "7", "456", "") == null, "Confirmed exact publishing snapshot is deleted");
        disk.rejectCommits = true;
        boolean storageFailure = false;
        try { afterDeath.saveForSubmission("reply", "7", "999", "", "标题", "不可在保存失败后发送", Collections.emptyList()); } catch (IllegalStateException expected) { storageFailure = true; }
        check(storageFailure && afterDeath.get("reply", "7", "999", "") == null, "Failed durable save throws instead of yielding a publishing snapshot");
        disk.rejectCommits = false;
        disk.values.put(key, fixture("draft-corrupt-metadata.json"));
        NativeDrafts.Draft corrupted = new NativeDrafts(disk).get("quote", "7", "456", "789");
        check(corrupted != null && corrupted.content.equals("不应因附件元数据损坏而丢掉的正文") && corrupted.submissionUnconfirmed, "Corrupt recovery metadata preserves text and requires review");
        JSONObject legacy = new JSONObject(quoteFixture); legacy.remove("pendingAttachments"); legacy.remove("submissionUnconfirmed"); disk.values.put(key, legacy.toString());
        NativeDrafts.Draft old = new NativeDrafts(disk).get("quote", "7", "456", "789");
        check(old.content.equals(exact) && old.pendingAttachments.isEmpty() && !old.submissionUnconfirmed, "Legacy seven-field draft remains readable without rewriting content");
    }
}
