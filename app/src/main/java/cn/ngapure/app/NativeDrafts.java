package cn.ngapure.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Private editor recovery data. Upload/session credentials and file permissions are never persisted. */
public final class NativeDrafts {
    private static final Object LOCK = new Object();
    private static final int MAX_DRAFTS = 20;
    private static final long MAX_TOTAL_CHARS = 2000000L;
    private final SharedPreferences preferences;
    static final class UnconfirmedSubmissionException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        UnconfirmedSubmissionException() { super("已有一次发布结果未确认，请先核对服务器记录"); }
    }
    static final class CapacityException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        CapacityException() { super("草稿空间已满，这次修改未保存。请先核对未确认的发布结果，或手动删除不再需要的草稿；原有草稿均已保留。"); }
    }
    static final class RecoveryException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        RecoveryException() { super("草稿恢复数据已损坏，无法确认上次发布结果。原始数据已保留，未准备或发送新请求；请先核对服务器记录。"); }
    }

    public static final class Draft {
        public final String action, fid, tid, pid, subject, content, key;
        public final long updatedAt;
        public final List<PendingAttachment> pendingAttachments;
        /** Unpublished score inputs, including incomplete fields. No account identifiers or credentials. */
        public final Map<String, String> ratingValues;
        public final boolean ratingEnabled;
        /** SHA256 of host and account ID; protects score drafts when another account opens this target. */
        public final String ratingScope;
        /** Set durably before a publishing request, retained until explicit rejection/success or user review. */
        public final boolean submissionUnconfirmed;
        /** Nonempty means original storage is retained unchanged and cannot safely be edited or republished. */
        public final String recoveryIssue;
        private final Object recoveryValue;
        private final long retainedChars;
        private Draft(String action, String fid, String tid, String pid, String subject, String content, long time,
                      List<PendingAttachment> pending, boolean unconfirmed) {
            this(action, fid, tid, pid, subject, content, time, pending, unconfirmed, Collections.emptyMap(), false, "");
        }
        private Draft(String action, String fid, String tid, String pid, String subject, String content, long time,
                      List<PendingAttachment> pending, boolean unconfirmed, Map<String, String> ratingValues,
                      boolean ratingEnabled, String ratingScope) {
            this.action = action; this.fid = fid; this.tid = tid; this.pid = pid;
            this.subject = subject; this.content = content; updatedAt = time;
            pendingAttachments = Collections.unmodifiableList(new ArrayList<>(pending));
            this.ratingValues = checkedRatings(ratingValues); this.ratingEnabled = ratingEnabled;
            this.ratingScope = checkedScope(ratingScope, ratingEnabled || !this.ratingValues.isEmpty());
            submissionUnconfirmed = unconfirmed;
            key = key(action, fid, tid, pid);
            recoveryIssue = ""; recoveryValue = null;
            long scoreChars = 0; for (Map.Entry<String, String> score : this.ratingValues.entrySet()) scoreChars += score.getKey().length() + score.getValue().length();
            retainedChars = subject.length() + (long) content.length() + scoreChars;
        }
        private Draft(String storedKey, Object raw) {
            String[] ids = storedKey.split(":", -1);
            boolean valid = false;
            if (ids.length == 5) try { valid = key(ids[1], ids[2], ids[3], ids[4]).equals(storedKey); }
            catch (IllegalArgumentException ignored) { }
            action = valid ? ids[1] : "new"; fid = valid ? ids[2] : "";
            tid = valid ? ids[3] : ""; pid = valid ? ids[4] : ""; key = storedKey;
            subject = "草稿恢复异常"; content = ""; updatedAt = Long.MAX_VALUE;
            pendingAttachments = Collections.emptyList(); submissionUnconfirmed = true;
            ratingValues = Collections.emptyMap(); ratingEnabled = false; ratingScope = "";
            recoveryIssue = new RecoveryException().getMessage();
            recoveryValue = raw instanceof java.util.Set ? new java.util.HashSet<>((java.util.Set<?>) raw) : raw;
            retainedChars = raw instanceof String ? ((String) raw).length() : 1L;
        }
    }

    /** Public file metadata/reference only. A restored item must be selected and uploaded again. */
    public static final class PendingAttachment {
        public final String fileName, mimeType, bbcode;
        public PendingAttachment(String name, String mime, String reference) {
            if (name == null || name.isEmpty() || name.length() > 240 || mime == null || mime.length() > 100
                    || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                    || !mime.matches("image/(?:jpeg|png|gif|webp)|application/(?:zip|vnd\\.rar|x-7z-compressed)")
                    || reference == null || !reference.matches("\\[(img|attach)]\\./mon_[0-9]{6}/[0-9]{1,10}/[A-Za-z0-9_-][A-Za-z0-9_.-]{0,240}\\[/\\1]"))
                throw new IllegalArgumentException("草稿附件信息无效");
            String path = reference.substring(reference.indexOf(']') + 1, reference.lastIndexOf('['));
            if (path.contains("..") || controls(name) || controls(mime) || reference.startsWith("[img]") != mime.startsWith("image/")) throw new IllegalArgumentException("草稿附件信息无效");
            fileName = name; mimeType = mime; bbcode = reference;
        }
        public String reference() { return bbcode.substring(bbcode.indexOf(']') + 1, bbcode.lastIndexOf('[')); }
        public boolean referencedBy(String body) { return body != null && body.contains(reference()); }
        public boolean image() { return bbcode.startsWith("[img]"); }
        public String replaceIn(String body, PendingAttachment replacement) {
            if (body == null || replacement == null || image() != replacement.image()) throw new IllegalArgumentException("附件类型不匹配");
            String changed = body.replace(reference(), replacement.reference());
            if (changed.length() > 500000) throw new IllegalArgumentException("替换附件后正文超过长度上限，请先减少内容");
            return changed;
        }
        public String removeFrom(String body) { return body.replace(bbcode, "").replace(reference(), ""); }
    }

    public NativeDrafts(Context context) {
        this(context.getApplicationContext().getSharedPreferences("nga_native_drafts", Context.MODE_PRIVATE));
    }
    NativeDrafts(SharedPreferences preferences) { this.preferences = preferences; }

    public Draft get(String action, String fid, String tid, String pid) {
        synchronized (LOCK) {
            String k = key(action, fid, tid, pid); Map<String, ?> stored = preferences.getAll();
            return stored.containsKey(k) ? readStored(k, stored.get(k)) : null;
        }
    }

    /** Returns the saved version, or null if both text fields are empty. */
    public Draft save(String action, String fid, String tid, String pid, String subject, String content) {
        synchronized (LOCK) {
            Draft old = get(action, fid, tid, pid);
            return save(action, fid, tid, pid, subject, content, old == null ? Collections.emptyList() : old.pendingAttachments,
                    old != null && old.submissionUnconfirmed, false);
        }
    }
    // Editor-specific overloads preserve the public six-argument API used by the host.
    Draft save(String action, String fid, String tid, String pid, String subject, String content,
               List<PendingAttachment> pending, boolean unconfirmed) {
        return save(action, fid, tid, pid, subject, content, pending, unconfirmed, false);
    }
    Draft save(String action, String fid, String tid, String pid, String subject, String content,
               List<PendingAttachment> pending, boolean unconfirmed, Map<String, String> scores,
               boolean scoreEnabled, String scoreScope) {
        return saveRating(action, fid, tid, pid, subject, content, pending, unconfirmed, false, scores, scoreEnabled, scoreScope);
    }
    Draft saveForSubmission(String action, String fid, String tid, String pid, String subject, String content,
                            List<PendingAttachment> pending) {
        synchronized (LOCK) {
            Draft old = get(action, fid, tid, pid);
            requireRecoverable(old);
            if (old != null && old.submissionUnconfirmed) throw new UnconfirmedSubmissionException();
            return save(action, fid, tid, pid, subject, content, pending, true, true);
        }
    }
    Draft saveForSubmission(String action, String fid, String tid, String pid, String subject, String content,
                            List<PendingAttachment> pending, Map<String, String> scores, boolean enabled, String scope) {
        synchronized (LOCK) {
            Draft old = get(action, fid, tid, pid); requireRecoverable(old);
            if (old != null && old.submissionUnconfirmed) throw new UnconfirmedSubmissionException();
            return saveRating(action, fid, tid, pid, subject, content, pending, true, true, scores, enabled, scope);
        }
    }
    Draft saveAfterReview(String action, String fid, String tid, String pid, String subject, String content,
                          List<PendingAttachment> pending) {
        return save(action, fid, tid, pid, subject, content, pending, false, true);
    }
    Draft saveAfterReview(String action, String fid, String tid, String pid, String subject, String content,
                          List<PendingAttachment> pending, Map<String, String> scores, boolean enabled, String scope) {
        return saveRating(action, fid, tid, pid, subject, content, pending, false, true, scores, enabled, scope);
    }
    boolean rejected(Draft expected) {
        if (expected == null) return false;
        synchronized (LOCK) {
            Draft actual = get(expected.action, expected.fid, expected.tid, expected.pid);
            if (!sameVersion(actual, expected) || !actual.recoveryIssue.isEmpty()) return false;
            saveRating(actual.action, actual.fid, actual.tid, actual.pid, actual.subject, actual.content,
                    actual.pendingAttachments, false, true, actual.ratingValues, actual.ratingEnabled, actual.ratingScope);
            return true;
        }
    }
    private Draft save(String action, String fid, String tid, String pid, String subject, String content,
                       List<PendingAttachment> pending, boolean unconfirmed, boolean durable) {
        synchronized (LOCK) {
            Draft old = get(action, fid, tid, pid);
            return saveRating(action, fid, tid, pid, subject, content, pending, unconfirmed, durable,
                    old == null ? Collections.emptyMap() : old.ratingValues,
                    old != null && old.ratingEnabled, old == null ? "" : old.ratingScope);
        }
    }
    private Draft saveRating(String action, String fid, String tid, String pid, String subject, String content,
                       List<PendingAttachment> pending, boolean unconfirmed, boolean durable,
                       Map<String, String> scores, boolean scoreEnabled, String scoreScope) {
        String k = key(action, fid, tid, pid);
        String title = subject == null ? "" : subject, body = content == null ? "" : content;
        if (title.length() > 1000 || body.length() > 500000) throw new IllegalArgumentException("草稿内容过长");
        synchronized (LOCK) {
            Draft old = get(action, fid, tid, pid);
            requireRecoverable(old);
            // A stale editor may not clear a marker created by another instance via an ordinary save.
            if (!durable && old != null && old.submissionUnconfirmed) unconfirmed = true;
            List<PendingAttachment> retained = pendingForBody(pending, body);
            Map<String, String> rating = checkedRatings(scores); String ratingScope = checkedScope(scoreScope, scoreEnabled || !rating.isEmpty());
            if (!unconfirmed && title.trim().isEmpty() && body.trim().isEmpty() && retained.isEmpty() && rating.isEmpty() && !scoreEnabled) {
                finish(preferences.edit().remove(k), durable); return null;
            }
            long now = System.currentTimeMillis();
            if (old != null && old.updatedAt >= now && old.updatedAt < Long.MAX_VALUE) now = old.updatedAt + 1;
            Draft draft = new Draft(action, safe(fid), safe(tid), safe(pid), title, body, now, retained, unconfirmed, rating, scoreEnabled, ratingScope);
            JSONObject json = new JSONObject();
            try {
                json.put("action", action).put("fid", draft.fid).put("tid", draft.tid).put("pid", draft.pid)
                        .put("subject", title).put("content", body).put("updatedAt", now)
                        .put("submissionUnconfirmed", unconfirmed);
                JSONArray files = new JSONArray();
                for (PendingAttachment file : retained) files.put(new JSONObject().put("fileName", file.fileName)
                        .put("mimeType", file.mimeType).put("bbcode", file.bbcode));
                json.put("pendingAttachments", files);
                if (scoreEnabled || !rating.isEmpty()) json.put("topicRating", new JSONObject().put("enabled", scoreEnabled)
                        .put("scope", ratingScope).put("values", new JSONObject(rating)));
            } catch (Exception e) { throw new IllegalStateException("无法保存草稿", e); }
            List<Draft> drafts = new ArrayList<>(getAll()); drafts.removeIf(d -> d.key.equals(k)); drafts.add(0, draft);
            long total = 0L; for (Draft d : drafts) total += d.retainedChars;
            long count = drafts.size();
            List<Draft> evicted = new ArrayList<>();
            Collections.sort(drafts, (a, b) -> Long.compare(a.updatedAt, b.updatedAt));
            for (Draft d : drafts) {
                if (count <= MAX_DRAFTS && total <= MAX_TOTAL_CHARS) break;
                if (d.key.equals(k) || d.submissionUnconfirmed || !d.recoveryIssue.isEmpty()) continue;
                evicted.add(d); count--; total -= d.retainedChars;
            }
            // Decide the entire transaction first. Failure cannot erase markers, earlier text, or the new snapshot.
            if (count > MAX_DRAFTS || total > MAX_TOTAL_CHARS) throw new CapacityException();
            SharedPreferences.Editor editor = preferences.edit().putString(k, json.toString());
            for (Draft d : evicted) editor.remove(d.key);
            finish(editor, durable); return draft;
        }
    }

    public List<Draft> getAll() {
        synchronized (LOCK) {
            List<Draft> result = new ArrayList<>();
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                if (entry.getKey().startsWith("draft:")) result.add(readStored(entry.getKey(), entry.getValue()));
            }
            Collections.sort(result, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
            return Collections.unmodifiableList(result);
        }
    }

    /** A late successful request cannot delete a newer draft saved by another editor instance. */
    public void delete(Draft expected) {
        if (expected == null) return;
        synchronized (LOCK) {
            Map<String, ?> stored = preferences.getAll();
            Draft actual = stored.containsKey(expected.key) ? readStored(expected.key, stored.get(expected.key)) : null;
            if (sameVersion(actual, expected))
                preferences.edit().remove(expected.key).apply();
        }
    }
    public void delete(String action, String fid, String tid, String pid) {
        synchronized (LOCK) { preferences.edit().remove(key(action, fid, tid, pid)).apply(); }
    }
    private static Draft readStored(String storedKey, Object raw) {
        Draft draft = raw instanceof String ? read(storedKey, (String) raw) : null;
        return draft == null ? new Draft(storedKey, raw) : draft;
    }
    private static void requireRecoverable(Draft draft) {
        if (draft != null && !draft.recoveryIssue.isEmpty()) throw new RecoveryException();
    }
    private static Draft read(String storedKey, String text) {
        if (text == null || text.isEmpty() || text.length() > 4000000) return null;
        try {
            JSONObject json = new JSONObject(text);
            List<PendingAttachment> pending = new ArrayList<>();
            JSONArray files = json.optJSONArray("pendingAttachments");
            boolean unknown = json.optBoolean("submissionUnconfirmed", false);
            if (json.has("pendingAttachments") && !json.isNull("pendingAttachments") && files == null) unknown = true;
            if (json.has("submissionUnconfirmed") && !(json.opt("submissionUnconfirmed") instanceof Boolean)) unknown = true;
            if (files != null && files.length() > 32) unknown = true;
            if (files != null) for (int i = 0; i < files.length() && i < 32; i++) {
                try {
                    JSONObject file = files.getJSONObject(i);
                    pending.add(new PendingAttachment(file.getString("fileName"), file.getString("mimeType"), file.getString("bbcode")));
                } catch (Exception corruptedMetadata) { unknown = true; }
            }
            Map<String, String> ratings = new LinkedHashMap<>(); boolean scoreEnabled = false; String scope = "";
            if (json.has("topicRating")) {
                JSONObject score = json.optJSONObject("topicRating");
                if (score == null || !(score.opt("enabled") instanceof Boolean) || !(score.opt("scope") instanceof String)) return null;
                JSONObject values = score.optJSONObject("values"); if (values == null || values.length() > 100) return null;
                java.util.Iterator<String> keys = values.keys(); while (keys.hasNext()) { String id = keys.next(); Object value = values.opt(id); if (!(value instanceof String)) return null; ratings.put(id, (String) value); }
                scoreEnabled = score.getBoolean("enabled"); scope = score.getString("scope");
            }
            Draft d = new Draft(json.getString("action"), json.optString("fid"), json.optString("tid"), json.optString("pid"),
                    json.getString("subject"), json.getString("content"), json.getLong("updatedAt"), pending, unknown, ratings, scoreEnabled, scope);
            if (!d.key.equals(storedKey) || d.subject.length() > 1000 || d.content.length() > 500000 || d.updatedAt <= 0) return null;
            return d;
        } catch (Exception malformed) { return null; }
    }
    private static String key(String action, String fid, String tid, String pid) {
        if (!"new".equals(action) && !"reply".equals(action) && !"quote".equals(action) && !"modify".equals(action)) throw new IllegalArgumentException("草稿操作无效");
        return "draft:" + action + ":" + safe(fid) + ":" + safe(tid) + ":" + safe(pid);
    }
    private static String safe(String id) {
        if (id == null || id.isEmpty() || "0".equals(id)) return "";
        if (!id.matches("-?[1-9][0-9]{0,17}")) throw new IllegalArgumentException("草稿编号无效");
        return id;
    }
    private static List<PendingAttachment> pendingForBody(List<PendingAttachment> pending, String body) {
        List<PendingAttachment> result = new ArrayList<>();
        if (pending != null) for (PendingAttachment file : pending) {
            if (file == null || !file.referencedBy(body)) continue;
            boolean duplicate = false; for (PendingAttachment existing : result) if (existing.reference().equals(file.reference())) duplicate = true;
            if (!duplicate) result.add(file);
            if (result.size() > 32) throw new IllegalArgumentException("草稿附件数量过多");
        }
        return result;
    }
    private static boolean sameVersion(Draft actual, Draft expected) {
        return actual != null && expected != null && actual.key.equals(expected.key) && actual.updatedAt == expected.updatedAt
                && actual.subject.equals(expected.subject) && actual.content.equals(expected.content)
                && actual.ratingEnabled == expected.ratingEnabled && actual.ratingScope.equals(expected.ratingScope) && actual.ratingValues.equals(expected.ratingValues)
                && actual.recoveryIssue.equals(expected.recoveryIssue) && Objects.equals(actual.recoveryValue, expected.recoveryValue);
    }
    private static Map<String, String> checkedRatings(Map<String, String> input) {
        Map<String, String> out = new LinkedHashMap<>();
        if (input != null) for (Map.Entry<String, String> item : input.entrySet()) {
            String id = item.getKey(), value = item.getValue();
            if (id == null || !id.matches("[1-9][0-9]{0,8}") || value == null || value.length() > 64 || controls(value) || out.size() >= 100)
                throw new IllegalArgumentException("评分草稿字段无效");
            out.put(id, value);
        }
        return Collections.unmodifiableMap(out);
    }
    private static String checkedScope(String scope, boolean required) {
        String value = scope == null ? "" : scope;
        if (!value.matches("(?:[0-9a-f]{64})?") || required && value.isEmpty()) throw new IllegalArgumentException("评分草稿账号范围无效");
        return required ? value : "";
    }
    private void finish(SharedPreferences.Editor editor, boolean durable) {
        if (!durable) { editor.apply(); return; }
        Map<String, ?> before = preferences.getAll();
        if (!editor.commit()) {
            // Android changes its live map before a failed disk write. Restore the complete draft
            // transaction as well: no send permission was returned, so this must not invent a
            // publishing marker or keep quota evictions from the rejected transaction.
            SharedPreferences.Editor rollback = preferences.edit();
            for (String key : preferences.getAll().keySet())
                if (key.startsWith("draft:") && !before.containsKey(key)) rollback.remove(key);
            for (Map.Entry<String, ?> entry : before.entrySet()) {
                String key = entry.getKey(); Object value = entry.getValue();
                if (!key.startsWith("draft:")) continue;
                if (value instanceof String) rollback.putString(key, (String) value);
                else if (value instanceof Integer) rollback.putInt(key, (Integer) value);
                else if (value instanceof Long) rollback.putLong(key, (Long) value);
                else if (value instanceof Float) rollback.putFloat(key, (Float) value);
                else if (value instanceof Boolean) rollback.putBoolean(key, (Boolean) value);
                else if (value instanceof java.util.Set) rollback.putStringSet(key, stringSet(value));
                else rollback.remove(key);
            }
            // A second false disk result still restores Android's live map. The original call
            // continues to fail; only a later successful, explicit submission may send.
            rollback.commit();
            throw new IllegalStateException("草稿恢复状态未保存，未发送请求");
        }
    }
    @SuppressWarnings("unchecked")
    private static java.util.Set<String> stringSet(Object value) {
        return new java.util.HashSet<>((java.util.Set<String>) value);
    }
    private static boolean controls(String text) { for (int i = 0; i < text.length(); i++) if (text.charAt(i) < 32 || text.charAt(i) == 127) return true; return false; }
}
