package cn.ngapure.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Executor;

/** Native, responsive glass sheets for explicit post actions. No WebView, browser or implicit writes. */
public final class NativeThreadActionDialog {
    private final Activity activity;
    private final NativeThreadActions actions;
    private final Executor worker;
    private final boolean dark;
    private final int ink, muted, accent, soft, error;
    private final AppPalette palette;
    private NativeGlassDialog active;
    private boolean disposed;

    public NativeThreadActionDialog(Activity activity, NativeThreadActions actions, Executor worker, boolean dark) {
        if (activity == null || actions == null || worker == null) throw new IllegalArgumentException("dependencies");
        this.activity = activity; this.actions = actions; this.worker = worker; this.dark = dark;
        palette = AppPalette.of(dark);
        ink = palette.ink; muted = palette.muted;
        accent = palette.accent; soft = palette.soft; error = palette.error;
    }

    public interface ReactionListener { void onConfirmed(NativeThreadActions.Reaction result); }

    /** Compatibility for callers that only need an invalidation notification. */
    public void showReaction(String tid, String pid, int direction, Runnable changed) {
        showReaction(tid, pid, direction, result -> { if (changed != null) changed.run(); });
    }

    /** Direction is +1 (like) or -1 (dislike); server confirms whether this adds or removes it. */
    public void showReaction(String tid, String pid, int direction, ReactionListener listener) {
        if (direction != 1 && direction != -1) throw new IllegalArgumentException("direction");
        final NativeThreadActions.ReactionDraft draft;
        try { draft = actions.prepareReaction(tid, pid, direction); }
        catch (NativeThreadActions.ActionException failure) { showMessage("暂时无法操作", failure.getMessage(), null); return; }
        boolean up = direction > 0; String name = up ? "点赞" : "点踩";
        LinearLayout content = column();
        TextView description = text("再次选择同一方向会撤销已有操作，最终状态以 NGA 返回结果为准。", 15, muted); content.addView(description);
        TextView status = status(content);
        TextView recovery = text("核对后重新操作", 15, accent); recovery.setTag("reaction-recovery");
        recovery.setMinHeight(dp(48)); recovery.setPadding(0, dp(14), 0, dp(8));
        recovery.setVisibility(View.GONE); content.addView(recovery);
        NativeGlassDialog dialog = replace(new NativeGlassDialog.Builder(activity, dark)
                .setTitle(up ? "点赞这条内容" : "点踩这条内容").setView(content)
                .setNegativeButton("取消", null).setPositiveButton("确认" + name, null).create());
        dialog.setOnShowListener(ignored -> dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                submitReaction(dialog, status, recovery, draft, listener)));
        recovery.setOnClickListener(v -> showReactionRecovery(draft, listener));
        dialog.show();
    }

    private void submitReaction(NativeGlassDialog dialog, TextView status, TextView recovery,
                                NativeThreadActions.ReactionDraft draft, ReactionListener listener) {
        if (!current(dialog)) return;
        Button positive = dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE);
        if (positive == null || !positive.isEnabled()) return;
        Button negative = dialog.getButton(NativeGlassDialog.BUTTON_NEGATIVE);
        positive.setEnabled(false); if (negative != null) negative.setEnabled(false);
        recovery.setVisibility(View.GONE); dialog.setCancelable(false);
        status.setTextColor(muted); status.setText("正在提交，请稍候…");
        worker.execute(() -> {
            try {
                NativeThreadActions.Reaction result = actions.recommend(draft);
                activity.runOnUiThread(() -> {
                    if (!current(dialog)) return;
                    String state = !result.selectionKnown ? "服务器已确认操作，请刷新核对当前状态"
                            : result.selected == 1 ? "当前已点赞" : result.selected == -1 ? "当前已点踩" : "当前未点赞或点踩";
                    showMessage("操作已确认", result.message + "\n" + state, null);
                    if (listener != null) listener.onConfirmed(result);
                });
            } catch (NativeThreadActions.ActionException failure) {
                activity.runOnUiThread(() -> {
                    if (!current(dialog)) return;
                    dialog.setCancelable(true); if (negative != null) negative.setEnabled(true);
                    positive.setEnabled(!failure.uncertain && failure.kind != NativeThreadActions.Kind.SESSION_CHANGED);
                    status.setTextColor(error); status.setText(failure.getMessage());
                    recovery.setVisibility(failure.uncertain ? View.VISIBLE : View.GONE);
                });
            }
        });
    }

    /** Clearing an uncertain local guard is separate from confirming a new server toggle. */
    private void showReactionRecovery(NativeThreadActions.ReactionDraft draft, ReactionListener listener) {
        LinearLayout content = column();
        content.addView(text("上次结果未确认，请先关闭面板核对帖子。确认已核对后，仅解除本机的重复提交保护；不会发送点赞或点踩。下一步仍需单独确认。", 15, muted));
        TextView status = status(content);
        NativeGlassDialog dialog = replace(new NativeGlassDialog.Builder(activity, dark)
                .setTitle("核对上次操作").setView(content).setNegativeButton("返回核对", null)
                .setPositiveButton("已核对，重新操作", null).create());
        dialog.setOnShowListener(ignored -> dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Button positive = dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE);
            if (!current(dialog) || !positive.isEnabled()) return;
            positive.setEnabled(false); dialog.setCancelable(false);
            dialog.getButton(NativeGlassDialog.BUTTON_NEGATIVE).setEnabled(false);
            worker.execute(() -> {
                try {
                    actions.resolveReactionUncertainty(draft);
                    activity.runOnUiThread(() -> { if (current(dialog)) showReaction(draft.tid, draft.pid, draft.direction, listener); });
                } catch (NativeThreadActions.ActionException failure) {
                    activity.runOnUiThread(() -> {
                        if (!current(dialog)) return;
                        dialog.setCancelable(true); dialog.getButton(NativeGlassDialog.BUTTON_NEGATIVE).setEnabled(true);
                        status.setTextColor(error); status.setText(failure.getMessage());
                    });
                }
            });
        }));
        dialog.show();
    }

    public void showReport(String tid, String pid, String fid, String stid, Runnable changed) {
        NativeGlassDialog loading = loading("举报此回复", "正在读取版面的举报理由…");
        runRead(loading, () -> actions.prepareReport(tid, pid, fid, stid), draft -> {
            LinearLayout content = column();
            TextView intro = text("向本版版主说明问题。只有点击“确认提交举报”后才会发送。", 14, muted); content.addView(intro);
            EditText reason = new EditText(activity); reason.setHint("请填写举报理由"); reason.setTextSize(16);
            reason.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            reason.setMinLines(3); reason.setMaxLines(8); reason.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)});
            reason.setSaveEnabled(false); reason.setContentDescription("举报理由");
            if (!draft.reasons.isEmpty()) {
                TextView label = text("常见理由 · 点按填入", 12, muted); label.setPadding(0, dp(18), 0, dp(8)); content.addView(label);
                for (String suggestion : draft.reasons) {
                    TextView chip = text(suggestion, 14, accent); chip.setPadding(dp(14), dp(12), dp(14), dp(12));
                    chip.setMinHeight(dp(48)); chip.setBackground(shape(soft, 15)); chip.setContentDescription("填入举报理由：" + suggestion);
                    chip.setOnClickListener(v -> { String old = reason.getText().toString().trim(); reason.setText(old.isEmpty() ? suggestion : old + " " + suggestion); reason.setSelection(reason.length()); });
                    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.bottomMargin = dp(6); content.addView(chip, cp);
                }
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2); rp.topMargin = dp(12); content.addView(reason, rp);
            TextView status = status(content);
            NativeGlassDialog dialog = replace(new NativeGlassDialog.Builder(activity, dark).setTitle("举报此回复")
                    .setView(content).setNegativeButton("取消", null).setPositiveButton("确认提交举报", null).create());
            dialog.setOnShowListener(ignored -> dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (reason.getText().toString().trim().isEmpty()) { status.setText("请先填写举报理由"); reason.requestFocus(); return; }
                String selected = reason.getText().toString();
                submit(dialog, status, () -> actions.report(draft, selected).message, changed);
            }));
            dialog.show();
        });
    }

    /** The optional reply callback opens the app's native score/reply composer for official type 2. */
    public void showPoll(String tid, Runnable changed, Runnable replyForScore) {
        NativeGlassDialog loading = loading("主题投票", "正在读取投票选项与结果…");
        runRead(loading, () -> actions.readPoll(tid), poll -> poll(poll, changed, replyForScore));
    }

    private void poll(NativeThreadActions.Poll poll, Runnable changed, Runnable replyForScore) {
        if (!poll.present) { showMessage("主题投票", "这篇主题没有投票。", null); return; }
        boolean ordinary = poll.type == 0, bet = poll.type == 1, score = poll.type == 2;
        String name = ordinary ? "主题投票" : bet ? "铜币投注" : score ? "主题评分" : "评分与问答";
        LinearLayout content = column();
        TextView title = text(poll.title, 16, ink); title.setTypeface(null, Typeface.BOLD); title.setLineSpacing(dp(3), 1); content.addView(title);
        String detail = (poll.ended() ? "已结束" : score ? "评分范围 " + poll.min + "–" + poll.max + " 分" : "每组最多选 " + poll.effectiveMaxSelect() + " 项")
                + (poll.participants > 0 ? " · " + poll.participants + " 人参与" : "");
        if (poll.end > 0) detail += "\n截止 " + formatTime(poll.end);
        if (!poll.privilege.isEmpty()) detail += "\n参与权限：" + poll.privilege;
        if ((poll.displayOptions & 1) != 0) detail += "\n提交后可查看结果";
        if ((poll.displayOptions & 2) != 0) detail += "\n结束后可查看结果";
        if (bet) detail += "\n每项 " + poll.min + "–" + poll.max + " 铜币；提交将按规则扣除铜币";
        TextView info = text(detail, 13, muted); info.setPadding(0, dp(8), 0, dp(14)); content.addView(info);
        Map<String, CheckBox> checks = new LinkedHashMap<>(); Map<String, EditText> amounts = new LinkedHashMap<>();
        Map<Integer, Long> totals = new HashMap<>();
        Map<Integer, Boolean> completeResults = new HashMap<>();
        for (NativeThreadActions.PollOption option : poll.options) if (!option.heading) {
            completeResults.put(option.group, (!completeResults.containsKey(option.group) || completeResults.get(option.group)) && option.resultsKnown);
            if (option.resultsKnown) totals.put(option.group, (totals.containsKey(option.group) ? totals.get(option.group) : 0L) + option.votes);
        }
        for (NativeThreadActions.PollOption option : poll.options) {
            if (option.heading) {
                TextView heading = text(option.title.replaceFirst("^===\\s*", ""), 16, ink); heading.setTypeface(null, Typeface.BOLD);
                heading.setPadding(0, dp(18), 0, dp(10)); content.addView(heading); continue;
            }
            LinearLayout card = column(); card.setPadding(dp(13), dp(8), dp(13), dp(12)); card.setBackground(shape(soft, 18));
            TextView label = text(option.title, 15, ink); label.setLineSpacing(dp(3), 1); card.addView(label);
            if ((ordinary || bet) && !poll.ended()) {
                if (ordinary) {
                    CheckBox choose = new CheckBox(activity); choose.setText("选择此项"); choose.setTextSize(14); choose.setTextColor(ink);
                    choose.setButtonTintList(ColorStateList.valueOf(accent)); choose.setMinHeight(dp(48)); choose.setContentDescription("投票选项：" + option.title);
                    checks.put(option.id, choose); card.addView(choose, new LinearLayout.LayoutParams(-1, -2));
                    choose.setOnCheckedChangeListener((button, selected) -> {
                        if (!selected || poll.effectiveMaxSelect() != 1) return;
                        for (NativeThreadActions.PollOption other : poll.options) if (other.group == option.group && !other.id.equals(option.id) && checks.containsKey(other.id)) checks.get(other.id).setChecked(false);
                    });
                } else {
                    EditText amount = new EditText(activity); amount.setTextSize(16); amount.setSingleLine(); amount.setInputType(InputType.TYPE_CLASS_NUMBER);
                    amount.setHint("投注铜币，留空不参与此项"); amount.setSaveEnabled(false); amount.setFilters(new InputFilter[]{new InputFilter.LengthFilter(13)});
                    amount.setMinHeight(dp(48)); amount.setContentDescription("投注铜币：" + option.title); amounts.put(option.id, amount); card.addView(amount, new LinearLayout.LayoutParams(-1, -2));
                }
            }
            String result;
            if (!option.resultsKnown) result = "结果暂未公开";
            else if (score) result = option.votes == 0 ? "暂无评分" : String.format(Locale.CHINA, "%.2f 分 · %d 人评分", option.amount / (double) option.votes, option.votes);
            else result = option.votes + " 票" + (bet ? " · 共 " + option.amount + " 铜币" : "");
            if (poll.winningOptions.contains(option.id)) result += " · 已结算胜出";
            TextView resultText = text(result, 12, muted); resultText.setPadding(0, dp(6), 0, 0); card.addView(resultText);
            if (option.resultsKnown && ordinary && Boolean.TRUE.equals(completeResults.get(option.group)) && totals.containsKey(option.group) && totals.get(option.group) > 0) {
                long total = totals.get(option.group); ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
                progress.setMax(1000); progress.setProgress((int) Math.min(1000, option.votes * 1000 / total)); progress.setProgressTintList(ColorStateList.valueOf(accent));
                progress.setProgressBackgroundTintList(ColorStateList.valueOf(palette.line)); progress.setContentDescription(option.votes + " 票，共 " + total + " 票");
                LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(7)); pp.topMargin = dp(9); card.addView(progress, pp);
            }
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.bottomMargin = dp(9); content.addView(card, cp);
        }
        TextView status = status(content); boolean writable = (ordinary || bet) && !poll.ended();
        if (score) { TextView note = text("官方主题评分会随回复一并提交。", 14, muted); note.setPadding(0, dp(12), 0, dp(6)); content.addView(note); }
        NativeGlassDialog.Builder builder = new NativeGlassDialog.Builder(activity, dark).setTitle(name).setView(content).setNegativeButton("关闭", null);
        if (writable) builder.setPositiveButton(bet ? "确认提交投注" : "确认提交投票", null);
        else if (score && !poll.ended() && replyForScore != null) builder.setPositiveButton("回复并评分", (d, which) -> replyForScore.run());
        NativeGlassDialog dialog = replace(builder.create());
        if (writable) dialog.setOnShowListener(ignored -> dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Map<String, Long> selected = new LinkedHashMap<>();
            if (ordinary) {
                for (Map.Entry<String, CheckBox> entry : checks.entrySet()) if (entry.getValue().isChecked()) selected.put(entry.getKey(), 0L);
            } else for (Map.Entry<String, EditText> entry : amounts.entrySet()) {
                String text = entry.getValue().getText().toString().trim(); if (text.isEmpty()) continue;
                try { selected.put(entry.getKey(), Long.parseLong(text)); }
                catch (NumberFormatException invalid) { status.setText("请输入有效的整数铜币数额"); entry.getValue().requestFocus(); return; }
            }
            if (selected.isEmpty()) { status.setText(ordinary ? "请至少选择一项" : "请填写至少一项投注数额"); return; }
            submit(dialog, status, () -> actions.vote(poll, selected).message, () -> { if (changed != null) changed.run(); });
        }));
        dialog.show();
    }

    /** Dispose during screen replacement or Activity destruction to suppress late UI callbacks. */
    public void dismiss() { disposed = true; if (active != null) { active.dismiss(); active = null; } }
    private NativeGlassDialog replace(NativeGlassDialog dialog) { if (active != null) active.dismiss(); disposed = false; active = dialog; return dialog; }
    private NativeGlassDialog loading(String title, String message) {
        NativeGlassDialog dialog = replace(new NativeGlassDialog.Builder(activity, dark).setTitle(title).setMessage(message).setProgress(true).setNegativeButton("取消", null).create()); dialog.show(); return dialog;
    }
    private boolean current(NativeGlassDialog dialog) { return !disposed && active == dialog && dialog.isShowing() && !activity.isFinishing() && !activity.isDestroyed(); }
    private interface Read<T> { T execute() throws NativeThreadActions.ActionException; }
    private interface Consumer<T> { void accept(T value); }
    private interface Write { String execute() throws NativeThreadActions.ActionException; }
    private <T> void runRead(NativeGlassDialog dialog, Read<T> operation, Consumer<T> success) {
        worker.execute(() -> {
            try { T result = operation.execute(); activity.runOnUiThread(() -> { if (current(dialog)) { dialog.dismiss(); success.accept(result); } }); }
            catch (NativeThreadActions.ActionException failure) { activity.runOnUiThread(() -> { if (current(dialog)) showMessage("暂时无法打开", failure.getMessage(), null); }); }
        });
    }
    private void submit(NativeGlassDialog dialog, TextView status, Write operation, Runnable changed) {
        if (!current(dialog)) return;
        Button positive = dialog.getButton(NativeGlassDialog.BUTTON_POSITIVE); if (positive == null || !positive.isEnabled()) return;
        positive.setEnabled(false); Button negative = dialog.getButton(NativeGlassDialog.BUTTON_NEGATIVE); if (negative != null) negative.setEnabled(false);
        dialog.setCancelable(false); status.setTextColor(muted); status.setText("正在提交，请稍候…");
        worker.execute(() -> {
            try { String result = operation.execute(); activity.runOnUiThread(() -> { if (current(dialog)) showMessage("操作已确认", result, changed); }); }
            catch (NativeThreadActions.ActionException failure) {
                activity.runOnUiThread(() -> {
                    if (!current(dialog)) return; dialog.setCancelable(true); if (negative != null) negative.setEnabled(true);
                    boolean retryAllowed = !failure.uncertain && failure.kind != NativeThreadActions.Kind.SESSION_CHANGED;
                    positive.setEnabled(retryAllowed); status.setTextColor(error); status.setText(failure.getMessage());
                });
            }
        });
    }
    private void showMessage(String title, String text, Runnable changed) {
        NativeGlassDialog dialog = replace(new NativeGlassDialog.Builder(activity, dark).setTitle(title).setMessage(text).setPositiveButton("知道了", null).create());
        dialog.show(); if (changed != null) changed.run();
    }
    private LinearLayout column() { LinearLayout result = new LinearLayout(activity); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private TextView text(String value, int size, int color) { TextView result = new TextView(activity); result.setText(value); result.setTextColor(color); result.setTextSize(size); return result; }
    private TextView status(LinearLayout parent) { TextView result = text("", 13, error); result.setPadding(0, dp(10), 0, 0); result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); parent.addView(result); return result; }
    private GradientDrawable shape(int color, int radius) { GradientDrawable result = new GradientDrawable(); result.setColor(color); result.setCornerRadius(dp(radius)); return result; }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private static String formatTime(long seconds) { SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA); format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai")); return format.format(new Date(seconds * 1000)); }
}
