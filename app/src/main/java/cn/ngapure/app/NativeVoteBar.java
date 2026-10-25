package cn.ngapure.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Two independent, native reaction targets. Binding and count refreshes never send a request. */
public final class NativeVoteBar extends LinearLayout {
    private final TextView like, dislike, status, recovery;
    private final boolean dark;
    private final AppPalette palette;
    private VoteMetrics metrics = new VoteMetrics();
    private int selectedDirection;
    private boolean directBusy, directUncertain;
    private Runnable onLike, onDislike;

    public NativeVoteBar(Context context, boolean dark) {
        super(context);
        this.dark = dark; palette = AppPalette.of(dark);
        setOrientation(VERTICAL); setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(dp(48)); setClipChildren(false); setClipToPadding(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        like = target("vote-like"); dislike = target("vote-dislike");
        LayoutParams up = new LayoutParams(0, -2, 1); up.setMarginEnd(dp(4));
        LayoutParams down = new LayoutParams(0, -2, 1); down.setMarginStart(dp(4));
        LinearLayout choices = new LinearLayout(context); choices.setOrientation(HORIZONTAL);
        choices.addView(like, up); choices.addView(dislike, down); addView(choices, new LayoutParams(-1, -2));
        status = new TextView(context); status.setTag("vote-status"); status.setTextSize(11);
        status.setTextColor(palette.muted); status.setPadding(dp(4), 0, dp(4), dp(4));
        status.setMaxLines(4); status.setEllipsize(TextUtils.TruncateAt.END);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); status.setVisibility(GONE);
        addView(status, new LayoutParams(-1, -2));
        recovery = target("vote-recovery"); recovery.setText("已核对，解除保护"); recovery.setSingleLine(false);
        recovery.setVisibility(GONE); addView(recovery, new LayoutParams(-1, -2)); counts(null);
    }

    /** Callers own route/account guards. A direct reaction never needs a confirmation sheet. */
    public void bind(VoteMetrics value, Runnable onLike, Runnable onDislike) {
        bind(value, onLike, onDislike, 0);
    }
    public void bind(VoteMetrics value, Runnable onLike, Runnable onDislike, int confirmedDirection) {
        counts(value); selectedDirection(confirmedDirection);
        this.onLike = onLike; this.onDislike = onDislike;
        like.setOnClickListener(view -> { if (!directBusy && !directUncertain && this.onLike != null) this.onLike.run(); });
        dislike.setOnClickListener(view -> { if (!directBusy && !directUncertain && this.onDislike != null) this.onDislike.run(); });
        directState(false, false, "", null);
    }

    /** Apply the actual acknowledgement. Net deltas cannot give separate totals. */
    public void confirmedReaction(NativeThreadActions.Reaction result) {
        if (result == null) return;
        confirmedSelection(result.selectionKnown, result.selected);
    }
    public void confirmedSelection(boolean known, int direction) { counts(null); if (known) selectedDirection(direction); }

    /** Both directions share the same per-floor gate, including programmatic/accessibility clicks. */
    public void directState(boolean busy, boolean uncertain, String message, Runnable resolveOnly) {
        directBusy = busy; directUncertain = uncertain;
        like.setEnabled(onLike != null && !busy && !uncertain); dislike.setEnabled(onDislike != null && !busy && !uncertain);
        like.setAlpha(like.isEnabled() ? 1f : .55f); dislike.setAlpha(dislike.isEnabled() ? 1f : .55f);
        String value = message == null ? "" : message;
        if (uncertain) value = (value.isEmpty() ? "上次结果未确认" : value) + "\n请先核对帖子；不会自动重试。";
        status.setText(value); status.setVisibility(value.isEmpty() ? GONE : VISIBLE);
        recovery.setVisibility(uncertain ? VISIBLE : GONE); recovery.setEnabled(!busy && resolveOnly != null);
        recovery.setOnClickListener(view -> { if (!directBusy && directUncertain && resolveOnly != null) resolveOnly.run(); });
    }

    /** Uses exact support/oppose totals only. A recommendation delta cannot update these totals. */
    public void counts(VoteMetrics value) {
        metrics = value == null ? new VoteMetrics() : value.copy();
        like.setText(metrics.likesLabel()); dislike.setText(metrics.dislikesLabel());
        descriptions();
    }

    /** Optional immediate server-confirmed flags, never inferred from counts or the tapped direction. */
    public void selectedDirection(int value) {
        if (value < -1 || value > 1) throw new IllegalArgumentException("direction");
        selectedDirection = value;
        like.setSelected(value == 1); dislike.setSelected(value == -1);
        like.setBackground(background(value == 1)); dislike.setBackground(background(value == -1));
        like.setTextColor(tint(value == 1)); dislike.setTextColor(tint(value == -1));
        descriptions();
    }

    private void descriptions() {
        like.setContentDescription(metrics.likesLabel() + (selectedDirection == 1 ? "，已点赞，再次点按可撤销" : "，点按点赞或撤销"));
        dislike.setContentDescription(metrics.dislikesLabel() + (selectedDirection == -1 ? "，已点踩，再次点按可撤销" : "，点按点踩或撤销"));
    }

    private TextView target(String tag) {
        TextView result = new TextView(getContext()) {
            @Override public CharSequence getAccessibilityClassName() { return android.widget.Button.class.getName(); }
        }; result.setTag(tag);
        result.setTextColor(tint(false)); result.setTextSize(12);
        result.setSingleLine(); result.setEllipsize(TextUtils.TruncateAt.END);
        result.setGravity(Gravity.CENTER); result.setMinHeight(dp(48)); result.setMinWidth(dp(48));
        result.setPadding(dp(8), dp(4), dp(8), dp(4)); result.setBackground(background(false));
        result.setFocusable(true); result.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return result;
    }

    /** Keep the touch target generous while sizing metadata from the actual labels. */
    public int preferredWidth() {
        float longest = Math.max(like.getPaint().measureText(like.getText().toString()),
                dislike.getPaint().measureText(dislike.getText().toString()));
        return 2 * Math.max(dp(48), (int)Math.ceil(longest) + dp(16)) + dp(8);
    }
    private int tint(boolean selected) {
        return selected ? palette.accent : palette.muted;
    }
    private RippleDrawable background(boolean selected) {
        GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(dp(10));
        shape.setColor(selected ? palette.selectionFill : ((palette.muted & 0x00ffffff) | (dark ? 0x16000000 : 0x08000000)));
        if (selected) shape.setStroke(Math.max(1, dp(.65f)), palette.selectionStroke);
        GradientDrawable mask = new GradientDrawable(); mask.setCornerRadius(dp(10)); mask.setColor(0xffffffff);
        return new RippleDrawable(ColorStateList.valueOf(palette.ripple),
                new InsetDrawable(shape, dp(2), dp(7), dp(2), dp(7)),
                new InsetDrawable(mask, dp(2), dp(7), dp(2), dp(7)));
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
