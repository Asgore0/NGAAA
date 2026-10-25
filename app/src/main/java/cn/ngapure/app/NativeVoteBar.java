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
    private final TextView like, dislike;
    private final boolean dark;
    private final AppPalette palette;
    private VoteMetrics metrics = new VoteMetrics();
    private int selectedDirection;

    public NativeVoteBar(Context context, boolean dark) {
        super(context);
        this.dark = dark; palette = AppPalette.of(dark);
        setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(dp(48)); setClipChildren(false); setClipToPadding(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        like = target("vote-like"); dislike = target("vote-dislike");
        LayoutParams up = new LayoutParams(0, -2, 1); up.setMarginEnd(dp(4));
        LayoutParams down = new LayoutParams(0, -2, 1); down.setMarginStart(dp(4));
        addView(like, up); addView(dislike, down); counts(null);
    }

    /** Callers own route/account guards and the explicit confirmation sheet. */
    public void bind(VoteMetrics value, Runnable onLike, Runnable onDislike) {
        counts(value); selectedDirection(0);
        like.setOnClickListener(onLike == null ? null : view -> onLike.run());
        dislike.setOnClickListener(onDislike == null ? null : view -> onDislike.run());
        like.setEnabled(onLike != null); dislike.setEnabled(onDislike != null);
        like.setAlpha(onLike == null ? .6f : 1f); dislike.setAlpha(onDislike == null ? .6f : 1f);
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
        like.setContentDescription(metrics.likesLabel() + (selectedDirection == 1 ? "，已点赞，再次确认可撤销" : "，点按确认点赞或撤销"));
        dislike.setContentDescription(metrics.dislikesLabel() + (selectedDirection == -1 ? "，已点踩，再次确认可撤销" : "，点按确认点踩或撤销"));
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
