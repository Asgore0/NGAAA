package cn.ngapure.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Layout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** A preference entry whose value uses available width without truncating its text. */
public final class NativePreferenceRow extends ViewGroup {
    private final TextView titleView, descriptionView, valueView;
    private final IconView arrow;
    private boolean inlineValue;
    private int headerHeight, contentHeight;

    public NativePreferenceRow(Context context, boolean dark, String title,
                               String description, String value, Runnable action) {
        super(context);
        if (title == null || title.isEmpty() || action == null) throw new IllegalArgumentException("Preference title and action required");
        int ink = AppPalette.of(dark).ink;
        int muted = AppPalette.of(dark).muted;
        setPadding(dp(16), dp(12), dp(16), dp(12));
        setMinimumHeight(dp(56));
        setBackground(new RippleDrawable(ColorStateList.valueOf(AppPalette.of(dark).ripple),
                null, new ColorDrawable(Color.WHITE)));
        setFocusable(true);
        setOnClickListener(ignored -> action.run());
        if (android.os.Build.VERSION.SDK_INT >= 28) setScreenReaderFocusable(true);
        titleView = text(title, 16, ink); titleView.setTag("preference-title"); addView(titleView);
        valueView = text(value, 13, muted); valueView.setTag("preference-value"); addView(valueView);
        descriptionView = text(description, 12, muted); descriptionView.setTag("preference-description"); addView(descriptionView);
        valueView.setVisibility(value == null || value.isEmpty() ? GONE : VISIBLE);
        descriptionView.setVisibility(description == null || description.isEmpty() ? GONE : VISIBLE);
        arrow = new IconView(context, "arrowRight"); arrow.setColor(muted);
        arrow.setFocusable(false); arrow.setClickable(false);
        arrow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        arrow.setTag("preference-arrow"); addView(arrow);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value == null ? "" : value); view.setTextSize(size); view.setTextColor(color);
        view.setGravity(Gravity.START); view.setIncludeFontPadding(true);
        view.setFocusable(false); view.setClickable(false);
        return view;
    }

    private int naturalWidth(TextView view) {
        return (int) Math.ceil(Layout.getDesiredWidth(view.getText(), view.getPaint()))
                + view.getCompoundPaddingLeft() + view.getCompoundPaddingRight();
    }

    private void measureText(TextView view, int width) {
        view.measure(MeasureSpec.makeMeasureSpec(Math.max(0, width), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int arrowSize = dp(24), arrowGap = dp(8), valueGap = dp(12);
        boolean hasValue = valueView.getVisibility() != GONE;
        int desired = getPaddingLeft() + getPaddingRight() + arrowSize + arrowGap + naturalWidth(titleView);
        if (hasValue) desired += valueGap + naturalWidth(valueView);
        int width = resolveSize(desired, widthSpec);
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight() - arrowSize - arrowGap);
        inlineValue = hasValue && valueView.getText().toString().indexOf('\n') < 0
                && titleView.getText().toString().indexOf('\n') < 0
                && naturalWidth(titleView) + valueGap + naturalWidth(valueView) <= available;
        valueView.setGravity(inlineValue ? Gravity.END : Gravity.START);
        if (inlineValue) {
            int valueWidth = naturalWidth(valueView);
            measureText(valueView, valueWidth);
            measureText(titleView, available - valueGap - valueWidth);
            headerHeight = Math.max(titleView.getMeasuredHeight(), valueView.getMeasuredHeight());
        } else {
            measureText(titleView, available);
            headerHeight = titleView.getMeasuredHeight();
            if (hasValue) { measureText(valueView, available); headerHeight += dp(4) + valueView.getMeasuredHeight(); }
        }
        contentHeight = headerHeight;
        if (descriptionView.getVisibility() != GONE) {
            measureText(descriptionView, available);
            contentHeight += dp(4) + descriptionView.getMeasuredHeight();
        }
        arrow.measure(MeasureSpec.makeMeasureSpec(arrowSize, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(arrowSize, MeasureSpec.EXACTLY));
        int height = Math.max(getMinimumHeight(), getPaddingTop() + getPaddingBottom() + Math.max(contentHeight, arrowSize));
        setMeasuredDimension(width, resolveSize(height, heightSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int width = right - left, height = bottom - top, arrowSize = arrow.getMeasuredWidth();
        int contentLeft = getPaddingLeft() + (rtl ? arrowSize + dp(8) : 0);
        int contentRight = width - getPaddingRight() - (rtl ? 0 : arrowSize + dp(8));
        int y = getPaddingTop() + Math.max(0, (height - getPaddingTop() - getPaddingBottom() - contentHeight) / 2);
        if (inlineValue) {
            int titleY = y + (headerHeight - titleView.getMeasuredHeight()) / 2;
            int valueY = y + (headerHeight - valueView.getMeasuredHeight()) / 2;
            int titleX = rtl ? contentRight - titleView.getMeasuredWidth() : contentLeft;
            int valueX = rtl ? contentLeft : contentRight - valueView.getMeasuredWidth();
            titleView.layout(titleX, titleY, titleX + titleView.getMeasuredWidth(), titleY + titleView.getMeasuredHeight());
            valueView.layout(valueX, valueY, valueX + valueView.getMeasuredWidth(), valueY + valueView.getMeasuredHeight());
        } else {
            titleView.layout(contentLeft, y, contentRight, y + titleView.getMeasuredHeight());
            if (valueView.getVisibility() != GONE) {
                int valueY = y + titleView.getMeasuredHeight() + dp(4);
                valueView.layout(contentLeft, valueY, contentRight, valueY + valueView.getMeasuredHeight());
            }
        }
        if (descriptionView.getVisibility() != GONE) {
            int descriptionY = y + headerHeight + dp(4);
            descriptionView.layout(contentLeft, descriptionY, contentRight, descriptionY + descriptionView.getMeasuredHeight());
        }
        int arrowX = rtl ? getPaddingLeft() : width - getPaddingRight() - arrowSize;
        int arrowY = getPaddingTop() + Math.max(0, (height - getPaddingTop() - getPaddingBottom() - arrowSize) / 2);
        arrow.layout(arrowX, arrowY, arrowX + arrowSize, arrowY + arrowSize);
        arrow.setScaleX(rtl ? -1f : 1f);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
