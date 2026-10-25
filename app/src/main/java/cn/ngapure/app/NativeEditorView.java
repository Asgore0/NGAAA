package cn.ngapure.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Platform-native editor. No WebView, JavaScript, or automatically retried write requests. */
public final class NativeEditorView extends LinearLayout {
    public interface Listener {
        void published(String tid, String pid);
        void chooseAttachment(NativeEditorView editor);
    }
    private final Activity activity;
    private final NgaStore store;
    private final NativePosting posting;
    private final NativeDrafts drafts;
    private final String action, fid, tid, pid, sessionAtOpen;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "nga-native-editor"));
    private final RequestCancellation.Token preparationReads = new RequestCancellation.Token();
    private final List<NativePosting.Attachment> attachments = new ArrayList<>();
    private final List<NativeDrafts.PendingAttachment> pendingAttachments = new ArrayList<>();
    private final List<TextView> tools = new ArrayList<>();
    private final List<TextView> recoveryActions = new ArrayList<>();
    private final EditText subject, content;
    private final TextView status, count, submit, retry, attachmentInfo, previewButton;
    private final LinearLayout attachmentRecovery;
    private final LinearLayout ratingPanel;
    private final Map<String, EditText> ratingInputs = new LinkedHashMap<>();
    private final Map<String, String> ratingDraftValues = new LinkedHashMap<>();
    private final String ratingScopeAtOpen;
    private final boolean requestedRating;
    private CheckBox ratingToggle;
    private boolean ratingEnabled, ratingInitialized, loadingRatingFields;
    private TextView attachmentButton;
    private final int bg, surface, ink, muted, line, accent;
    private final AppPalette palette;
    private final boolean dark;
    private NativePosting.Prepare prepared;
    private NativeDrafts.Draft submittedDraft;
    private NativeGlassDialog dialog;
    private NativeImages previewImages;
    private volatile boolean closed, completed;
    private boolean busy, submitting, loadingFields, edited, outcomeNeedsReview, replacingUpload;
    private String recoveryIssue = "";
    private NativeDrafts.PendingAttachment replacementTarget;
    private volatile int generation;
    private final Runnable saveDraft = () -> {
        if (!closed && !submitting) {
            if (reconcileAttachments()) { updateAttachmentInfo(); updateControls(); }
            persistDraft(false);
        }
    };

    public NativeEditorView(Activity activity, NgaStore store, String action, String fid, String tid,
                            String pid, Listener listener) {
        this(activity, store, action, fid, tid, pid, listener, false);
    }

    /** requestRating opens the official score fields; values are still part of the final explicit post confirmation. */
    public NativeEditorView(Activity activity, NgaStore store, String action, String fid, String tid,
                            String pid, Listener listener, boolean requestRating) {
        super(activity);
        if (listener == null) throw new IllegalArgumentException("listener");
        this.activity = activity; this.store = store; this.action = action;
        this.fid = safe(fid); this.tid = safe(tid); this.pid = safe(pid); this.listener = listener;
        posting = new NativePosting(store); drafts = new NativeDrafts(activity); sessionAtOpen = sessionFingerprint();
        requestedRating = requestRating; ratingEnabled = requestRating; ratingScopeAtOpen = ratingAccountScope();
        dark = "dark".equals(store.theme()) || "system".equals(store.theme()) &&
                (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        palette = AppPalette.of(dark);
        bg = palette.background; surface = palette.surface;
        ink = palette.ink; muted = palette.muted;
        line = palette.line; accent = palette.accent;
        setOrientation(VERTICAL); setBackgroundColor(bg);
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(true);
        scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout column = new LinearLayout(activity); column.setOrientation(VERTICAL);
        column.setPadding(dp(16), dp(18), dp(16), dp(40)); scroll.addView(column);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        TextView title = text(actionTitle(), 23, ink); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        column.addView(title, spaced(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 0, 12));
        status = text("正在获取发帖权限…", 13, muted); status.setPadding(dp(13), dp(12), dp(13), dp(12));
        status.setBackground(shape(surface, 12)); column.addView(status, spaced(-1, -2, 0, 10));
        retry = button("重新获取权限", this::retryPreparation); retry.setVisibility(GONE);
        column.addView(retry, spaced(-1, dp(46), 0, 12));
        subject = field("new".equals(action) ? "标题" : "标题（可选）", false);
        subject.setFilters(new InputFilter[] {new InputFilter.LengthFilter(1000)});
        column.addView(subject, spaced(-1, -2, 0, 10));
        ratingPanel = new LinearLayout(activity); ratingPanel.setOrientation(VERTICAL); ratingPanel.setVisibility(GONE);
        ratingPanel.setPadding(dp(14), dp(14), dp(14), dp(14)); ratingPanel.setBackground(shape(surface, 18));
        column.addView(ratingPanel, spaced(-1, -2, 0, 12));
        HorizontalScrollView toolbar = new HorizontalScrollView(activity); toolbar.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
        addTool(row, "粗体", () -> wrap("[b]", "[/b]"));
        addTool(row, "斜体", () -> wrap("[i]", "[/i]"));
        addTool(row, "引用", () -> wrap("[quote]", "[/quote]"));
        addTool(row, "链接", this::linkDialog);
        addTool(row, "折叠", () -> wrap("[collapse=折叠内容]", "[/collapse]"));
        addTool(row, "表情", this::smileDialog);
        attachmentButton = addTool(row, "附件", () -> { if (ready()) { replacementTarget = null; listener.chooseAttachment(this); } });
        toolbar.setMinimumHeight(dp(48)); toolbar.addView(row); column.addView(toolbar, spaced(-1, -2, 0, 10));
        content = field("输入正文，支持 NGA 代码…", true); content.setMinLines(12);
        content.setFilters(new InputFilter[] {new InputFilter.LengthFilter(500000)});
        column.addView(content, spaced(-1, -2, 0, 8));
        count = text("0 字符", 12, muted); count.setGravity(Gravity.END);
        column.addView(count, spaced(-1, -2, 0, 10));
        attachmentInfo = text("未发布附件的文件名与引用位置会随草稿保留；恢复后请重新选择文件。", 12, muted);
        column.addView(attachmentInfo, spaced(-1, -2, 0, 14));
        attachmentRecovery = new LinearLayout(activity); attachmentRecovery.setOrientation(VERTICAL);
        column.addView(attachmentRecovery, spaced(-1, -2, 0, 12));
        submit = button("modify".equals(action) ? "确认修改" : "发布", this::confirmSubmit);
        submit.setTextColor(palette.onPrimary); submit.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        submit.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.ripple), shape(palette.primaryFill, 14), null));
        previewButton = button("预览", this::preview);
        NativeAdaptiveActionRow actions = new NativeAdaptiveActionRow(activity, dp(10), dp(50));
        actions.addAction(previewButton, 1); actions.addAction(submit, 2);
        column.addView(actions, spaced(-1, -2, 0, 0));
        NativeDrafts.Draft saved = drafts.get(action, this.fid, this.tid, this.pid);
        loadingFields = true;
        if (saved != null) {
            recoveryIssue = saved.recoveryIssue;
            if (recoveryIssue.isEmpty()) { subject.setText(saved.subject); content.setText(saved.content); edited = true; }
            pendingAttachments.addAll(saved.pendingAttachments); outcomeNeedsReview = saved.submissionUnconfirmed;
            ratingDraftValues.putAll(saved.ratingValues); ratingEnabled = requestedRating || saved.ratingEnabled;
            ratingInitialized = !saved.ratingScope.isEmpty();
            if (!saved.ratingScope.isEmpty() && !saved.ratingScope.equals(ratingScopeAtOpen))
                recoveryIssue = "这份评分草稿属于另一个账号或连接线路，请切回原账号与线路后继续。原选择已保留。";
        }
        loadingFields = false;
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                updateCount();
                if (!loadingFields && !closed) {
                    edited = true;
                    main.removeCallbacks(saveDraft); main.postDelayed(saveDraft, 800);
                }
            }
        };
        subject.addTextChangedListener(watcher); content.addTextChangedListener(watcher);
        updateCount(); updateAttachmentInfo(); updateControls();
        if (!recoveryIssue.isEmpty()) showRecoveryProtection(); else if (outcomeNeedsReview) showOutcomeProtection(); else prepare();
    }

    private void prepare() {
        if (closed || busy || outcomeNeedsReview || !sessionValid()) return;
        retireLiveUploads();
        busy = true; prepared = null; status.setText("正在获取发帖权限…"); retry.setVisibility(GONE); updateControls();
        final int task = ++generation;
        worker.execute(() -> {
            RequestCancellation.Scope scope = RequestCancellation.bind(preparationReads);
            try {
                NativePosting.Prepare p = posting.prepare(action, fid, tid, pid);
                main.post(() -> {
                    if (!active(task)) return;
                    busy = false;
                    if (!sessionValid()) return;
                    prepared = p;
                    if (!ratingInitialized) {
                        if (p.rating != null && !p.rating.existing.isEmpty()) { ratingDraftValues.putAll(p.rating.existing); ratingEnabled = true; }
                        ratingInitialized = true;
                    }
                    rebuildRatingFields();
                    if (!edited) {
                        loadingFields = true; subject.setText(p.subject); content.setText(p.content);
                        content.setSelection(content.length()); loadingFields = false;
                    }
                    String label = p.appendOnly ? "只能追加：提交的内容将加至原文末尾。" : "已获取发帖权限，发布前会再次确认。";
                    if (!p.forumName.isEmpty()) label = p.forumName + " · " + label;
                    if (!p.warning.isEmpty()) label += "\n服务端提示：" + safeError(p.warning);
                    if (edited) label += "\n已保留编辑内容。";
                    status.setText(label); updateAttachmentInfo(); updateControls(); updateCount();
                });
            } catch (Exception e) { main.post(() -> { if (active(task)) { busy = false; showError(e); retry.setVisibility(VISIBLE); updateControls(); } }); }
            finally { scope.close(); }
        });
    }

    private void confirmSubmit() {
        if (!ready() || dialog != null) return;
        reconcileAttachments();
        if (!pendingAttachments.isEmpty()) { status.setText("草稿中有待重新选择的附件，请先恢复或明确移除。"); updateAttachmentInfo(); return; }
        final String title = subject.getText().toString(), body = content.getText().toString();
        if ("new".equals(action) && title.trim().isEmpty()) { subject.setError("请填写标题"); subject.requestFocus(); return; }
        if (body.trim().length() < 3) { content.setError("请至少输入 3 个字符"); content.requestFocus(); return; }
        final List<NativePosting.Attachment> selected = new ArrayList<>(attachments);
        final Map<String, String> scores;
        try {
            Map<String, String> input = selectedRatings();
            if (ratingEnabled && prepared.rating == null) throw new IllegalArgumentException("服务器未提供评分项目，请重新获取权限或关闭参与评分");
            if (prepared.rating != null && !prepared.rating.existing.isEmpty() && !ratingEnabled)
                throw new IllegalArgumentException("已有评分应随原回复保留或修改，不能通过清空删除");
            scores = prepared.rating == null ? Collections.emptyMap() : prepared.rating.validate(input);
            if (ratingEnabled && scores.isEmpty()) throw new IllegalArgumentException("请填写全部评分项目");
        } catch (IllegalArgumentException invalid) { status.setText(invalid.getMessage()); return; }
        String summary = "标题：" + (title.isEmpty() ? "（无）" : title) + "\n\n正文（" + body.codePointCount(0, body.length()) + " 字符）：\n";
        summary += body.length() > 1400 ? body.substring(0, 1400) + "\n…（其余正文保留在编辑器中）" : body;
        summary += "\n\n新附件：" + selected.size() + " 个";
        if (prepared.rating != null) summary += "\n\n" + prepared.rating.summary(scores);
        if (prepared.appendOnly) summary += "\n此次操作仅追加至原文末尾。";
        dialog = new NativeGlassDialog.Builder(activity, dark).setTitle("modify".equals(action) ? "确认保存这次修改？" : "确认发布这份内容？")
                .setMessage(summary).setNegativeButton("返回编辑", null)
                .setPositiveButton("modify".equals(action) ? "保存修改" : "发布", (d, which) -> submit(title, body, selected, scores)).create();
        showDialog();
    }

    private void preview() {
        if (closed || submitting || dialog != null) return;
        final NativeImages images = new NativeImages(); previewImages = images;
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(false);
        LinearLayout column = new LinearLayout(activity); column.setOrientation(VERTICAL);
        column.setPadding(dp(16), dp(10), dp(16), dp(18));
        String title = subject.getText().toString();
        TextView heading = text(title.isEmpty() ? "（无标题）" : title, 20, ink);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD); heading.setTextIsSelectable(true);
        column.addView(heading, spaced(-1, -2, 0, 14));
        if (prepared != null && prepared.rating != null) {
            try { column.addView(text(prepared.rating.summary(selectedRatings()), 14, accent), spaced(-1, -2, 0, 14)); }
            catch (IllegalArgumentException invalid) { column.addView(text("评分尚未填写完整：" + invalid.getMessage(), 13, muted), spaced(-1, -2, 0, 14)); }
        }
        if (!pendingAttachments.isEmpty()) column.addView(text("有 " + pendingAttachments.size() + " 个草稿附件需要重新选择；文字与引用位置均保留。", 13, muted), spaced(-1, -2, 0, 10));
        String body = content.getText().toString();
        if (body.trim().isEmpty()) column.addView(text("正文为空", 15, muted));
        else column.addView(new NativeRichText(activity, images, body, dark, store.fontSize(),
                ignored -> Toast.makeText(activity, "链接可在发布后的帖子中查看", Toast.LENGTH_SHORT).show()), new LayoutParams(-1, -2));
        scroll.addView(column);
        dialog = new NativeGlassDialog.Builder(activity, dark).setTitle("内容预览")
                .setView(scroll).setPositiveButton("返回编辑", null).create();
        showDialog(() -> { images.close(); if (previewImages == images) previewImages = null; });
    }

    private void submit(String title, String body, List<NativePosting.Attachment> selected, Map<String, String> scores) {
        if (!ready()) return;
        final NativePosting.Prepare permission = prepared;
        try { submittedDraft = drafts.saveForSubmission(action, fid, tid, pid, title, body, draftAttachments(),
                ratingEnabled ? scores : currentRatingValues(), ratingEnabled, ratingDraftScope()); }
        catch (NativeDrafts.UnconfirmedSubmissionException anotherAttempt) { outcomeNeedsReview = true; showOutcomeProtection(); return; }
        catch (RuntimeException e) { showStorageError(e, false); return; }
        final NativeDrafts.Draft snapshot = submittedDraft;
        outcomeNeedsReview = true;
        busy = true; submitting = true; status.setText("正在提交，请勿重复操作…"); main.removeCallbacks(saveDraft); updateControls();
        final int task = ++generation;
        worker.execute(() -> {
            try {
                NativePosting.Result result = posting.submit(permission, title, body, selected, false, scores);
                completed = true;
                boolean cleaned;
                try { drafts.delete(snapshot); cleaned = true; } catch (RuntimeException storageFailure) { cleaned = false; }
                final boolean draftCleaned = cleaned;
                main.post(() -> {
                    if (closed || task != generation) {
                        if (!activity.isFinishing() && !activity.isDestroyed()) Toast.makeText(activity, "发帖成功", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    busy = false; submitting = false; attachments.clear(); prepared = null;
                    if (!sessionAtOpen.equals(sessionFingerprint())) {
                        status.setText("原账号的提交已确认；登录状态已变化，请重新打开编辑器。");
                        updateControls(); Toast.makeText(activity, "原账号发帖已确认", Toast.LENGTH_LONG).show(); return;
                    }
                    status.setText(draftCleaned ? "发布成功" : "已发布，但草稿清理失败，请勿重复发布。");
                    if (!draftCleaned) Toast.makeText(activity, "已发布，旧草稿未能清理，请勿重复发布", Toast.LENGTH_LONG).show();
                    updateControls(); listener.published(result.tid, result.pid);
                });
            } catch (Exception e) {
                boolean rejected = e instanceof NativePosting.PostingException && !((NativePosting.PostingException) e).uncertain;
                boolean settled = false;
                if (rejected) try { settled = drafts.rejected(snapshot); } catch (RuntimeException ignored) { }
                final boolean needsReview = !settled;
                main.post(() -> {
                    if (!active(task)) return;
                    busy = false; submitting = false; submittedDraft = null;
                    outcomeNeedsReview = needsReview;
                    showError(e);
                    if (e instanceof NativePosting.PostingException) {
                        NativePosting.PostingException failure = (NativePosting.PostingException) e;
                        if (failure.uncertain || failure.kind == NativePosting.Kind.SESSION_CHANGED || failure.kind == NativePosting.Kind.NOT_LOGGED_IN || failure.kind == NativePosting.Kind.CHALLENGE_REQUIRED) {
                            prepared = null; retry.setText(failure.uncertain ? "先核对发布结果" : "重新获取权限"); retry.setVisibility(VISIBLE);
                        }
                    }
                    if (outcomeNeedsReview) showOutcomeProtection();
                    updateControls();
                });
            }
        });
    }

    /** SAF result entry point. Metadata/read/upload are bounded and run off the UI thread. */
    public void attach(Uri uri) {
        if (uri == null || !ready()) return;
        if (!"content".equalsIgnoreCase(uri.getScheme())) { status.setText("请通过系统文件选择器选择附件"); return; }
        if (replacementTarget == null && draftAttachments().size() >= 32) { status.setText("单份草稿最多保留 32 个新附件，请先移除不用的附件引用。"); return; }
        busy = true; status.setText("正在检查附件…"); updateControls();
        final int task = ++generation;
        final NativePosting.Prepare permission = prepared;
        final NativeDrafts.PendingAttachment replacing = replacementTarget; replacementTarget = null;
        worker.execute(() -> {
            try {
                Metadata metadata = metadata(uri);
                NativePosting.UploadPolicy policy = posting.preflight(permission, metadata.name, metadata.mime, metadata.size < 0 ? 1 : metadata.size);
                if (!policy.allowed) throw new IOException(policy.reason);
                if (replacing != null && replacing.image() != policy.image) throw new IOException("请重新选择同类附件，或先移除旧引用再添加新文件");
                main.post(() -> {
                    if (!active(task)) return;
                    busy = false; updateControls();
                    if (!sessionValid()) return;
                    dialog = new NativeGlassDialog.Builder(activity, dark).setTitle("上传这个附件？")
                            .setMessage((replacing == null ? "" : "替换草稿附件：" + replacing.fileName + "\n\n") + metadata.name + "\n" + (metadata.size < 0 ? "大小未知，将有界读取检查" : readableSize(metadata.size)) + "\n保留原文件，不自动压缩。")
                            .setNegativeButton("取消", (d, which) -> status.setText("已取消附件上传"))
                            .setPositiveButton("上传", (d, which) -> upload(uri, metadata, permission, policy.maxBytes, replacing)).create();
                    showDialog();
                });
            } catch (Exception e) { main.post(() -> { if (active(task)) { busy = false; showError(e); updateControls(); } }); }
        });
    }

    private void upload(Uri uri, Metadata metadata, NativePosting.Prepare permission, long maxBytes, NativeDrafts.PendingAttachment replacing) {
        if (!ready() || permission != prepared) return;
        if (replacing != null && (!pendingAttachments.contains(replacing) || !replacing.referencedBy(content.getText().toString()))) { status.setText("原附件引用已移除，请重新选择要添加的文件。"); return; }
        if (content.length() > 499000) { status.setText("正文接近长度上限，请先留出附件引用所需空间。"); return; }
        if (replacing != null) {
            String body = content.getText().toString(), reference = replacing.reference(); int count = 0, at = 0;
            while ((at = body.indexOf(reference, at)) >= 0) { count++; at += reference.length(); }
            if ((long) body.length() + (long) count * Math.max(0, 300 - reference.length()) > 500000) {
                status.setText("该附件有多处引用，请先减少正文，为替换后的完整地址留出空间。"); return;
            }
        }
        busy = true; replacingUpload = true; status.setText("正在上传附件，请勿重复操作…"); updateControls();
        final int task = ++generation;
        worker.execute(() -> {
            try {
                byte[] bytes = readBounded(uri, maxBytes);
                if (closed || task != generation) return;
                NativePosting.Attachment attachment = posting.upload(permission, metadata.name, metadata.mime, bytes);
                main.post(() -> {
                    if (!active(task)) return;
                    busy = false; replacingUpload = false;
                    if (!sessionValid() || permission != prepared) return;
                    attachments.add(attachment);
                    if (replacing == null) insert("\n" + attachment.bbcode + "\n");
                    else {
                        NativeDrafts.PendingAttachment replacement = draftAttachment(attachment);
                        String body = replacing.replaceIn(content.getText().toString(), replacement);
                        pendingAttachments.remove(replacing); replaceBody(body);
                    }
                    status.setText("附件已上传，发布正文后才完成关联。"); updateAttachmentInfo(); updateControls();
                    persistDraft(false);
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (!active(task)) return;
                    busy = false; replacingUpload = false; showError(e);
                    if (e instanceof NativePosting.PostingException && ((NativePosting.PostingException) e).uncertain) {
                        prepared = null; retry.setText("核对上传结果后重新获取权限"); retry.setVisibility(VISIBLE);
                    }
                    updateControls();
                });
            }
        });
    }

    private Metadata metadata(Uri uri) throws IOException {
        ContentResolver resolver = activity.getContentResolver(); String name = ""; long size = -1;
        try (Cursor cursor = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME), s = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0 && !cursor.isNull(n)) name = cursor.getString(n);
                if (s >= 0 && !cursor.isNull(s)) size = cursor.getLong(s);
            }
        } catch (RuntimeException e) { throw new IOException("无法读取附件信息，请重新选择文件", e); }
        String mime;
        try { mime = resolver.getType(uri); } catch (RuntimeException e) { throw new IOException("无法读取附件类型", e); }
        if (mime == null) mime = "";
        if (name == null || name.isEmpty()) name = "attachment";
        if (name.indexOf('.') < 0) {
            switch (mime.toLowerCase(Locale.ROOT)) {
                case "image/jpeg": name += ".jpg"; break; case "image/png": name += ".png"; break;
                case "image/gif": name += ".gif"; break; case "image/webp": name += ".webp"; break;
                case "application/zip": name += ".zip"; break; case "application/vnd.rar": name += ".rar"; break;
                case "application/x-7z-compressed": name += ".7z"; break; default: break;
            }
        }
        return new Metadata(name, mime, size);
    }
    private byte[] readBounded(Uri uri, long max) throws IOException {
        try (InputStream input = activity.getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("无法打开附件");
            byte[] buffer = new byte[16384]; int n;
            while ((n = input.read(buffer)) != -1) {
                if (closed || Thread.currentThread().isInterrupted()) throw new IOException("附件读取已取消");
                if ((long) output.size() + n > max) throw new IOException("附件超过 " + readableSize(max) + " 上限");
                output.write(buffer, 0, n);
            }
            return output.toByteArray();
        } catch (SecurityException e) { throw new IOException("附件读取权限已失效，请重新选择", e); }
    }
    private static final class Metadata {
        final String name, mime; final long size;
        Metadata(String name, String mime, long size) { this.name = name; this.mime = mime; this.size = size; }
    }

    /** Idempotent. Parent should call before replacing/removing this view. */
    public void saveOnPause() { if (!closed && !completed && !submitting) persistDraft(false); }

    public void close() {
        if (closed) return;
        main.removeCallbacks(saveDraft);
        if (!completed && !submitting) persistDraft(true);
        closed = true; generation++; prepared = null;
        preparationReads.cancel();
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        if (previewImages != null) { previewImages.close(); previewImages = null; }
        attachments.clear();
        // An explicitly confirmed delivery owns its eventual success/rejection/UNKNOWN
        // settlement even after the page closes. Only disposable reads are interrupted.
        if (submitting || replacingUpload) worker.shutdown(); else worker.shutdownNow();
    }
    @Override protected void onDetachedFromWindow() { close(); super.onDetachedFromWindow(); }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus); if (focus && store != null && !closed) sessionValid();
    }
    private boolean active(int task) { return !closed && task == generation && !activity.isFinishing() && !activity.isDestroyed(); }
    private boolean readyWithoutSession() { return !closed && !busy && !completed && !outcomeNeedsReview && prepared != null; }
    private boolean ready() { return readyWithoutSession() && sessionValid(); }
    private boolean sessionValid() {
        if (sessionAtOpen.equals(sessionFingerprint())) return true;
        prepared = null; retireLiveUploads(); busy = false;
        status.setText("登录账号或线路已变化，请退出后重新打开编辑器。草稿仍保留在本机。"); retry.setVisibility(GONE); updateControls();
        return false;
    }
    private String sessionFingerprint() {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((store.host() + "\0" + store.cookie()).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : hash) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString();
        } catch (Exception e) { return "unavailable"; }
    }
    private String ratingAccountScope() {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((store.host() + "\0" + store.accountId()).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : hash) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString();
        } catch (Exception unavailable) { return ""; }
    }
    private Map<String, String> currentRatingValues() {
        for (Map.Entry<String, EditText> entry : ratingInputs.entrySet()) ratingDraftValues.put(entry.getKey(), entry.getValue().getText().toString());
        boolean any = false; for (String value : ratingDraftValues.values()) if (!value.trim().isEmpty()) any = true;
        if (!any && !ratingEnabled) ratingDraftValues.clear();
        return new LinkedHashMap<>(ratingDraftValues);
    }
    private Map<String, String> selectedRatings() {
        if (!ratingEnabled) return Collections.emptyMap();
        Map<String, String> values = new LinkedHashMap<>();
        if (ratingInputs.isEmpty()) values.putAll(ratingDraftValues);
        else for (Map.Entry<String, EditText> item : ratingInputs.entrySet()) values.put(item.getKey(), item.getValue().getText().toString().trim());
        return values;
    }
    private String ratingDraftScope() { return ratingEnabled || !ratingDraftValues.isEmpty() ? ratingScopeAtOpen : ""; }
    private void ratingChanged() {
        if (loadingRatingFields || closed || submitting) return;
        currentRatingValues(); edited = true; main.removeCallbacks(saveDraft); main.postDelayed(saveDraft, 500); updateControls();
    }
    private void rebuildRatingFields() {
        currentRatingValues(); loadingRatingFields = true; ratingInputs.clear(); ratingPanel.removeAllViews(); ratingToggle = null;
        NativeTopicRating.Spec spec = prepared == null ? null : prepared.rating;
        if (spec != null && !spec.existing.isEmpty()) {
            ratingEnabled = true;
            for (Map.Entry<String, String> previous : spec.existing.entrySet()) if (!ratingDraftValues.containsKey(previous.getKey())) ratingDraftValues.put(previous.getKey(), previous.getValue());
        }
        if (spec == null && !ratingEnabled && ratingDraftValues.isEmpty()) { ratingPanel.setVisibility(GONE); loadingRatingFields = false; return; }
        ratingPanel.setVisibility(VISIBLE);
        TextView title = text("主题评分", 18, ink); title.setTypeface(null, Typeface.BOLD); ratingPanel.addView(title, spaced(-1, -2, 0, 8));
        ratingToggle = new CheckBox(activity); ratingToggle.setText("随这条回复提交评分"); ratingToggle.setTextSize(15); ratingToggle.setTextColor(ink);
        ratingToggle.setButtonTintList(ColorStateList.valueOf(accent)); ratingToggle.setMinHeight(dp(48)); ratingToggle.setChecked(ratingEnabled);
        ratingToggle.setContentDescription("随这条回复提交主题评分"); ratingPanel.addView(ratingToggle, new LayoutParams(-1, -2));
        ratingToggle.setOnCheckedChangeListener((button, selected) -> { ratingEnabled = selected; ratingChanged(); });
        if (spec == null) {
            ratingPanel.addView(text("服务器尚未提供可核验的评分项目。请重新获取权限；关闭上方开关后可以只发布文字回复。原评分草稿仍保留。", 13, muted));
            loadingRatingFields = false; return;
        }
        String explanation = "请填写全部项目，范围 " + spec.min.toPlainString() + "–" + spec.max.toPlainString() + " 分。发布前会显示完整评分供确认。";
        if (!spec.existing.isEmpty()) explanation += "\n已载入你这条回复的原评分，修改分数会与回复一起保存。";
        if (spec.ended()) explanation += "\n该主题评分已结束，不能提交新评分。";
        ratingPanel.addView(text(explanation, 13, muted), spaced(-1, -2, 0, 10));
        for (NativeTopicRating.Dimension dimension : spec.dimensions) {
            TextView label = text(dimension.title, 15, ink); label.setPadding(0, dp(10), 0, dp(6)); ratingPanel.addView(label);
            EditText score = field(spec.min.toPlainString() + "–" + spec.max.toPlainString() + " 分", false);
            score.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
            score.setSingleLine(); score.setSaveEnabled(false); score.setMinHeight(dp(52)); score.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
            score.setContentDescription("评分：" + dimension.title); score.setText(ratingDraftValues.containsKey(dimension.id) ? ratingDraftValues.get(dimension.id) : "");
            ratingInputs.put(dimension.id, score); ratingPanel.addView(score, spaced(-1, -2, 0, 4));
            score.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
                @Override public void afterTextChanged(Editable s) { ratingChanged(); }
            });
        }
        loadingRatingFields = false;
    }
    private void updateControls() {
        if (submit == null) return;
        boolean canWrite = !closed && !busy && !completed && !outcomeNeedsReview && prepared != null;
        boolean canPublish = canWrite && pendingAttachments.isEmpty();
        submit.setEnabled(canPublish); submit.setAlpha(canPublish ? 1f : .45f);
        boolean recoveryReady = recoveryIssue.isEmpty();
        previewButton.setEnabled(recoveryReady && !closed && !submitting && !completed); previewButton.setAlpha(recoveryReady && !closed && !submitting && !completed ? 1f : .45f);
        boolean editable = recoveryReady && !submitting && !replacingUpload && !completed && (prepared != null || edited || "new".equals(action) || "reply".equals(action));
        subject.setEnabled(editable); content.setEnabled(editable);
        if (ratingToggle != null) ratingToggle.setEnabled(editable && !busy && (prepared == null || prepared.rating == null || prepared.rating.existing.isEmpty()));
        for (EditText score : ratingInputs.values()) score.setEnabled(editable && !busy && ratingEnabled);
        for (TextView tool : tools) { tool.setEnabled(recoveryReady && !busy && !completed); tool.setAlpha(recoveryReady && !busy && !completed ? 1f : .45f); }
        if (attachmentButton != null) { attachmentButton.setEnabled(canWrite); attachmentButton.setAlpha(canWrite ? 1f : .45f); }
        for (TextView action : recoveryActions) {
            boolean enabled = "choose".equals(action.getTag()) ? canWrite : !closed && !busy && !completed;
            action.setEnabled(enabled); action.setAlpha(enabled ? 1f : .45f);
        }
        retry.setEnabled(!busy && !completed);
    }
    private void updateCount() { if (count != null) { String body = content.getText().toString(); count.setText(body.codePointCount(0, body.length()) + " 字符"); } }
    private void updateAttachmentInfo() {
        StringBuilder text = new StringBuilder();
        if (prepared != null && !prepared.existingAttachments.isEmpty()) text.append("原帖已有附件：").append(prepared.existingAttachments.size()).append(" 个，会保留。\n");
        if (!attachments.isEmpty()) {
            text.append("本次已上传："); for (int i = 0; i < attachments.size(); i++) { if (i > 0) text.append("、"); text.append(attachments.get(i).fileName); } text.append('\n');
        }
        if (!pendingAttachments.isEmpty()) text.append("待恢复附件：").append(pendingAttachments.size()).append(" 个。正文及引用位置已保留。\n");
        text.append("未发布附件的文件名与引用位置会随草稿保留；恢复后请重新选择文件。"); attachmentInfo.setText(text);
        attachmentRecovery.removeAllViews(); recoveryActions.clear();
        for (NativeDrafts.PendingAttachment file : new ArrayList<>(pendingAttachments)) {
            LinearLayout card = new LinearLayout(activity); card.setOrientation(VERTICAL); card.setPadding(dp(12), dp(10), dp(12), dp(10)); card.setBackground(shape(surface, 12));
            card.addView(text(file.fileName + " · 需要重新选择", 14, ink));
            TextView choose = button("重新选择此附件", () -> { if (ready()) { replacementTarget = file; listener.chooseAttachment(this); } });
            choose.setTag("choose"); recoveryActions.add(choose); choose.setEnabled(readyWithoutSession()); card.addView(choose, new LayoutParams(-1, -2));
            TextView remove = button("从正文移除此附件", () -> removePending(file)); remove.setTag("remove"); recoveryActions.add(remove);
            remove.setEnabled(!closed && !busy && !completed); card.addView(remove, new LayoutParams(-1, -2));
            attachmentRecovery.addView(card, spaced(-1, -2, 0, 8));
        }
    }
    private NativeDrafts.Draft persistDraft(boolean notify) {
        if (!recoveryIssue.isEmpty()) return null;
        if (!edited && subject.getText().toString().trim().isEmpty() && content.getText().toString().trim().isEmpty()
                && attachments.isEmpty() && pendingAttachments.isEmpty()) {
            boolean hasScore = false; for (String value : currentRatingValues().values()) if (!value.trim().isEmpty()) hasScore = true;
            if (!hasScore) return null;
        }
        try {
            NativeDrafts.Draft draft = drafts.save(action, fid, tid, pid, subject.getText().toString(), content.getText().toString(), draftAttachments(), outcomeNeedsReview,
                    currentRatingValues(), ratingEnabled, ratingDraftScope());
            if (notify && draft != null && !activity.isFinishing()) Toast.makeText(activity,
                    replacingUpload ? "正文已保存；上传尚未完成，恢复后请重新选择附件"
                            : draft.pendingAttachments.isEmpty() ? "草稿已保存" : "正文及附件位置已保存，恢复后需重新选择文件", Toast.LENGTH_SHORT).show();
            return draft;
        } catch (RuntimeException e) { showStorageError(e, notify); return null; }
    }
    private void retryPreparation() {
        if (closed || busy) return;
        if (!recoveryIssue.isEmpty()) { showRecoveryProtection(); return; }
        if (!outcomeNeedsReview) { prepare(); return; }
        if (dialog != null) return;
        dialog = new NativeGlassDialog.Builder(activity, dark).setTitle("先核对上次发布结果")
                .setMessage("上次请求可能已被服务器接收或仍在处理。请先等待，再返回相关版块或主题，确认是否已经发布。\n\n只有确认未发布后，才继续编辑并重新提交。草稿正文和附件位置已保留。")
                .setNegativeButton("暂不重发", null).setPositiveButton("已确认未发布，继续", (d, which) -> {
                    try {
                        drafts.saveAfterReview(action, fid, tid, pid, subject.getText().toString(), content.getText().toString(), draftAttachments(),
                                currentRatingValues(), ratingEnabled, ratingDraftScope());
                        outcomeNeedsReview = false; prepared = null; retry.setText("重新获取权限");
                        prepare();
                    } catch (RuntimeException storageError) { showStorageError(storageError, false); }
                }).create();
        showDialog();
    }
    private void showOutcomeProtection() {
        if (!recoveryIssue.isEmpty()) { showRecoveryProtection(); return; }
        prepared = null; retireLiveUploads();
        status.setText("上次发布结果未确认。请先查看服务器记录，确认没有发布后再继续。正文和附件位置已保留。");
        retry.setText("先核对发布结果"); retry.setVisibility(VISIBLE); updateAttachmentInfo(); updateControls();
    }
    private void showRecoveryProtection() {
        prepared = null; outcomeNeedsReview = true; main.removeCallbacks(saveDraft);
        status.setText(recoveryIssue + "\n如确需移除，请核对后在草稿列表明确删除这份异常记录。");
        retry.setVisibility(GONE); updateControls();
    }
    private void showStorageError(RuntimeException error, boolean notify) {
        if (closed) return;
        String message;
        if (error instanceof NativeDrafts.RecoveryException) {
            recoveryIssue = error.getMessage(); showRecoveryProtection(); message = recoveryIssue;
        } else {
            message = error instanceof NativeDrafts.CapacityException ? error.getMessage() : "草稿保存失败，未发起新发布请求，请先复制保留正文。";
            status.setText(message);
        }
        if (notify && !activity.isFinishing()) Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }
    private NativeDrafts.PendingAttachment draftAttachment(NativePosting.Attachment attachment) {
        return new NativeDrafts.PendingAttachment(attachment.fileName, attachment.mimeType, attachment.bbcode);
    }
    private List<NativeDrafts.PendingAttachment> draftAttachments() {
        List<NativeDrafts.PendingAttachment> files = new ArrayList<>();
        String body = content.getText().toString();
        for (NativeDrafts.PendingAttachment file : pendingAttachments) if (file.referencedBy(body)) addPending(files, file);
        for (NativePosting.Attachment attachment : attachments) {
            NativeDrafts.PendingAttachment file = draftAttachment(attachment); if (file.referencedBy(body)) addPending(files, file);
        }
        return files;
    }
    private static void addPending(List<NativeDrafts.PendingAttachment> files, NativeDrafts.PendingAttachment candidate) {
        for (NativeDrafts.PendingAttachment file : files) if (file.reference().equals(candidate.reference())) return;
        files.add(candidate);
    }
    private boolean reconcileAttachments() {
        String body = content.getText().toString();
        int before = pendingAttachments.size() + attachments.size();
        pendingAttachments.removeIf(file -> !file.referencedBy(body));
        attachments.removeIf(file -> !draftAttachment(file).referencedBy(body));
        return before != pendingAttachments.size() + attachments.size();
    }
    private void retireLiveUploads() {
        if (attachments.isEmpty()) return;
        String body = content.getText().toString();
        for (NativePosting.Attachment attachment : attachments) {
            NativeDrafts.PendingAttachment file = draftAttachment(attachment);
            if (file.referencedBy(body)) addPending(pendingAttachments, file);
        }
        attachments.clear(); persistDraft(false); updateAttachmentInfo();
    }
    private void replaceBody(String text) {
        int selection = Math.max(0, content.getSelectionStart()); loadingFields = true;
        content.setText(text); content.setSelection(Math.min(selection, content.length())); loadingFields = false;
        edited = true; reconcileAttachments(); updateCount();
    }
    private void removePending(NativeDrafts.PendingAttachment file) {
        if (closed || busy || dialog != null || !pendingAttachments.contains(file)) return;
        dialog = new NativeGlassDialog.Builder(activity, dark).setTitle("移除此附件引用？")
                .setMessage(file.fileName + "\n\n将从正文移除此附件的引用，其余正文保持不变。")
                .setNegativeButton("保留", null).setPositiveButton("移除引用", (d, which) -> {
                    String body = file.removeFrom(content.getText().toString());
                    pendingAttachments.remove(file); replaceBody(body); persistDraft(false); updateAttachmentInfo(); updateControls();
                }).create();
        showDialog();
    }
    private void showError(Exception e) { status.setText(safeError(e.getMessage() == null ? "操作未完成，请稍后重试" : e.getMessage())); }
    private static String safeError(String message) {
        String safe = message.replaceAll("(?i)(?:https?:)?//[^\\s<>]+", "（NGA 地址已隐藏）")
                .replaceAll("(?i)(auth|token|cookie|access_token|ngaPassportCid|attachments_check)\\s*[=:]\\s*[^\\s&]+", "$1=[已隐藏]");
        return safe.length() > 1800 ? safe.substring(0, 1800) : safe;
    }
    private TextView addTool(LinearLayout row, String label, Runnable action) {
        TextView tool = button(label, action); tools.add(tool);
        tool.setSingleLine(); tool.setMinWidth(dp(60));
        LayoutParams params = new LayoutParams(-2, -2); params.setMarginEnd(dp(6)); row.addView(tool, params);
        return tool;
    }
    private void wrap(String before, String after) {
        if (closed || submitting) return;
        Editable text = content.getText(); int start = Math.max(0, content.getSelectionStart()), end = Math.max(start, content.getSelectionEnd());
        if ((long) text.length() + before.length() + after.length() > 500000) { content.setError("正文已接近长度上限，请先减少内容"); return; }
        String selected = text.subSequence(start, end).toString(); text.replace(start, end, before + selected + after);
        content.requestFocus(); content.setSelection(start + before.length(), start + before.length() + selected.length());
    }
    private void insert(String text) {
        Editable body = content.getText(); int start = Math.max(0, content.getSelectionStart()), end = Math.max(start, content.getSelectionEnd());
        if ((long) body.length() - (end - start) + text.length() > 500000) { content.setError("正文长度不足以插入这段内容"); return; }
        body.replace(start, end, text); content.setSelection(Math.min(body.length(), start + text.length()));
    }
    private void linkDialog() {
        if (dialog != null || closed) return;
        EditText url = field("https://…", false); url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout container = new LinearLayout(activity); container.setPadding(dp(18), dp(8), dp(18), 0); container.addView(url, new LayoutParams(-1, -2));
        dialog = new NativeGlassDialog.Builder(activity, dark).setTitle("插入链接").setView(container).setNegativeButton("取消", null).setPositiveButton("插入", null).create();
        showDialog();
        dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String address = url.getText().toString().trim();
            try {
                URL parsed = new URL(address);
                if (!("https".equals(parsed.getProtocol()) || "http".equals(parsed.getProtocol())) || parsed.getHost().isEmpty() || address.matches("(?s).*[\\[\\]\\s].*")) throw new IOException();
                if (content.getSelectionStart() == content.getSelectionEnd()) insert("[url=" + address + "]" + address + "[/url]");
                else wrap("[url=" + address + "]", "[/url]");
                dialog.dismiss();
            } catch (IOException e) { url.setError("请输入有效的 HTTP 或 HTTPS 链接"); }
        });
    }
    private void smileDialog() {
        if (dialog != null || closed) return;
        String[] labels = {"哭笑", "羡慕", "赞同", "闪光", "忧伤", "茶"};
        dialog = new NativeGlassDialog.Builder(activity, dark).setTitle("插入表情").setItems(labels, (d, which) -> insert("[s:ac:" + labels[which] + "]")).create();
        showDialog();
    }
    private void showDialog() { showDialog(null); }
    private void showDialog(Runnable onDismiss) {
        final NativeGlassDialog shown = dialog;
        dialog.setOnDismissListener(d -> { if (dialog == shown) dialog = null; if (onDismiss != null) onDismiss.run(); }); dialog.show();
    }
    private EditText field(String hint, boolean multiline) {
        EditText edit = new EditText(activity); edit.setTextColor(ink); edit.setHintTextColor(muted); edit.setTextSize(multiline ? 16 : 17);
        edit.setHint(hint); edit.setGravity(Gravity.TOP | Gravity.START); edit.setPadding(dp(14), dp(14), dp(14), dp(14));
        edit.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        edit.setSingleLine(!multiline); edit.setBackground(shape(surface, 12)); return edit;
    }
    private TextView text(String label, int sp, int color) { TextView t = new TextView(activity); t.setText(label); t.setTextSize(sp); t.setTextColor(color); t.setLineSpacing(dp(3), 1); return t; }
    private TextView button(String label, Runnable action) {
        TextView button = text(label, 14, ink); button.setGravity(Gravity.CENTER); button.setMinHeight(dp(48)); button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.ripple), shape(surface, 11), null)); button.setOnClickListener(v -> action.run()); return button;
    }
    private GradientDrawable shape(int fill, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radius)); d.setStroke(dp(1), line); return d; }
    private LayoutParams spaced(int width, int height, int top, int bottom) { LayoutParams p = new LayoutParams(width, height); p.topMargin = dp(top); p.bottomMargin = dp(bottom); return p; }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private static String safe(String id) { return id == null || "0".equals(id) ? "" : id; }
    private String actionTitle() { return "new".equals(action) ? "发布主题" : "modify".equals(action) ? "编辑帖子" : requestedRating ? "评分并回复" : "quote".equals(action) ? "引用回复" : "回复主题"; }
    private static String readableSize(long bytes) { return bytes >= 1048576 ? String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0) : String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0); }
}
