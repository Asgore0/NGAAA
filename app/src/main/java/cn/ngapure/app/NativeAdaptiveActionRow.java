package cn.ngapure.app;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;

/** Preserve weighted actions when their labels fit; stack them in a narrow measured pane. */
final class NativeAdaptiveActionRow extends LinearLayout {
    private final int gap, minimumHeight;
    private final ArrayList<Float> weights = new ArrayList<>();
    NativeAdaptiveActionRow(Context context, int gap, int minimumHeight) {
        super(context); this.gap = gap; this.minimumHeight = minimumHeight;
    }
    void addAction(TextView action, float weight) {
        weights.add(weight); action.setMinHeight(minimumHeight); action.setMinimumHeight(minimumHeight);
        addView(action, new LayoutParams(0, LayoutParams.WRAP_CONTENT, weight));
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int count = getChildCount();
        int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight() - gap * Math.max(0, count - 1);
        float total = 0; for (float weight : weights) total += weight;
        boolean stack = false;
        for (int i = 0; i < count; i++) {
            TextView action = (TextView) getChildAt(i);
            float wanted = action.getPaint().measureText(action.getText().toString()) + action.getCompoundPaddingLeft() + action.getCompoundPaddingRight();
            if (wanted > available * weights.get(i) / Math.max(1, total)) stack = true;
        }
        setOrientation(stack ? VERTICAL : HORIZONTAL);
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i); LayoutParams params = (LayoutParams) child.getLayoutParams();
            params.width = stack ? LayoutParams.MATCH_PARENT : 0;
            params.height = LayoutParams.WRAP_CONTENT; params.weight = stack ? 0 : weights.get(i);
            params.leftMargin = !stack && i > 0 ? gap : 0; params.topMargin = stack && i > 0 ? gap : 0;
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
