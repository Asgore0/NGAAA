package cn.ngapure.preferencerowprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import cn.ngapure.app.NativePreferenceRow;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real Android layouts from the immutable product JAR, in an isolated no-INTERNET UID. */
public final class PreferenceRowProbe extends Instrumentation {
    private static final String PACKAGE = "cn.ngapure.preferencerowprobe";
    private final JSONArray cases = new JSONArray(), screenshots = new JSONArray();
    private final AtomicInteger clicks = new AtomicInteger();
    private FixtureActivity activity;
    private float font = 1;
    private int assertions;
    private boolean authorized;
    private String stage = "guard";

    public static final class FixtureActivity extends Activity {
        FrameLayout root;
        @Override public void onCreate(Bundle state) {
            super.onCreate(state); root = new FrameLayout(this); root.setBackgroundColor(0xffdfe3e9); setContentView(root);
        }
    }

    @Override public Activity newActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Activity value = super.newActivity(loader, name, intent);
        if (value instanceof FixtureActivity) {
            Configuration config = new Configuration(); config.densityDpi = 160; config.fontScale = font;
            value.applyOverrideConfiguration(config);
        }
        return value;
    }

    @Override public void onCreate(Bundle args) { super.onCreate(args); authorized = args != null && "true".equals(args.getString("syntheticPreferenceRowsOnly")); start(); }
    private <T> T main(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work); runOnMainSync(task); return task.get(10, TimeUnit.SECONDS);
    }
    private void check(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    private static TextView text(NativePreferenceRow row, String tag) { return (TextView) row.findViewWithTag(tag); }
    private static boolean fits(TextView text) {
        if (text.getVisibility() == View.GONE) return true;
        Layout layout = text.getLayout();
        if (layout == null || layout.getHeight() > text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom()) return false;
        for (int i = 0; i < layout.getLineCount(); i++) {
            if (layout.getEllipsisCount(i) != 0 || layout.getLineWidth(i) > text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight() + 1) return false;
        }
        return true;
    }
    private static Rect bounds(View view) { return new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom()); }

    private void sample(int width, boolean dark, String name, String title, String description, String value, boolean rtl) throws Exception {
        stage = name + "-w" + width + "-f" + font + (dark ? "-dark" : "-light") + (rtl ? "-rtl" : "");
        NativePreferenceRow row = main(() -> {
            activity.root.removeAllViews(); FrameLayout group = new FrameLayout(activity);
            group.setBackgroundColor(dark ? 0xff1c2838 : Color.WHITE);
            FrameLayout.LayoutParams pane = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            pane.topMargin = 24; activity.root.addView(group, pane);
            NativePreferenceRow result = new NativePreferenceRow(activity, dark, title, description, value, clicks::incrementAndGet);
            result.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            group.addView(result, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)); return result;
        });
        waitForIdleSync(); SystemClock.sleep(30);
        main(() -> {
            TextView heading = text(row, "preference-title"), detail = text(row, "preference-description"), trailing = text(row, "preference-value");
            View arrow = row.findViewWithTag("preference-arrow");
            check(row.getWidth() == width && row.getHeight() >= 56, "row width/minimum target");
            check(fits(heading) && fits(detail) && fits(trailing), "all text visible without ellipsis");
            check(heading.getText().toString().equals(title) && detail.getText().toString().equals(description == null ? "" : description)
                    && trailing.getText().toString().equals(value == null ? "" : value), "text preserved exactly");
            check(arrow.getWidth() == 24 && arrow.getHeight() == 24 && !arrow.isFocusable() && !arrow.isClickable()
                    && arrow.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_NO, "decorative arrow is not a separate target");
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i); if (child.getVisibility() == View.GONE) continue;
                check(child.getLeft() >= row.getPaddingLeft() && child.getTop() >= row.getPaddingTop()
                        && child.getRight() <= row.getWidth() - row.getPaddingRight() && child.getBottom() <= row.getHeight() - row.getPaddingBottom(), "child inside padded bounds");
                for (int j = i + 1; j < row.getChildCount(); j++) {
                    View other = row.getChildAt(j); if (other.getVisibility() != View.GONE) check(!Rect.intersects(bounds(child), bounds(other)), "children do not overlap");
                }
            }
            boolean stacked = trailing.getVisibility() != View.GONE && trailing.getTop() >= heading.getBottom();
            if ("short-value".equals(name) && width == 720) check(!stacked, "short value shares a wide row");
            if ("long-value".equals(name)) check(stacked && trailing.getLineCount() > 1, "long value wraps below the title");
            if ("description".equals(name)) check(detail.getLineCount() > 1, "long description wraps completely");
            if (trailing.getVisibility() != View.GONE && !stacked) check(rtl ? trailing.getRight() <= heading.getLeft() : trailing.getLeft() >= heading.getRight(), "inline value occupies trailing edge");
            check(row.getBackground() instanceof RippleDrawable && ((RippleDrawable) row.getBackground()).getNumberOfLayers() == 1, "ripple has only a mask and no solid background");
            int before = clicks.get(); check(row.performClick() && clicks.get() == before + 1, "entire row invokes the original action exactly once");
            cases.put(new JSONObject().put("case", stage).put("widthDp", width).put("fontScale", font).put("dark", dark).put("rtl", rtl)
                    .put("height", row.getHeight()).put("valueStacked", stacked).put("titleLines", heading.getLineCount())
                    .put("valueLines", trailing.getVisibility() == View.GONE ? 0 : trailing.getLineCount())
                    .put("descriptionLines", detail.getVisibility() == View.GONE ? 0 : detail.getLineCount()).put("passed", true));
            return null;
        });
        if (!rtl && ((width == 220 && font == 2 && !dark && "long-value".equals(name))
                || (width == 320 && font == 1.3f && dark && "description".equals(name))
                || (width == 720 && font == 2 && dark && "short-value".equals(name)))) {
            Bitmap screenshot = getUiAutomation().takeScreenshot();
            if (screenshot == null) throw new AssertionError("screenshot required");
            File output = new File(activity.getFilesDir(), stage + ".png");
            try (FileOutputStream stream = new FileOutputStream(output)) { screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream); }
            finally { screenshot.recycle(); }
            screenshots.put(output.getName());
        }
    }

    @Override public void onStart() {
        boolean passed = false; String failure = "NONE";
        try {
            check(authorized && PACKAGE.equals(getTargetContext().getPackageName()) && getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED, "isolated synthetic UID without Internet");
            String longValue = "这是一段用于验证设置当前值自然换行的完整文字，内容必须全部可见且不发生省略。";
            longValue = longValue + longValue + longValue;
            String longDescription = "这是设置说明，完整解释当前功能与本机记录的关系。内容较长时应该根据实际可用宽度自然换行，并保留全部文字，不能被固定高度裁切。";
            for (float scale : new float[]{1, 1.3f, 2}) {
                font = scale; activity = (FixtureActivity) startActivitySync(new Intent().setClassName(PACKAGE, FixtureActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); waitForIdleSync();
                for (int width : new int[]{220, 320, 720}) {
                    for (boolean dark : new boolean[]{false, true}) {
                        sample(width, dark, "title-only", "浏览历史", null, null, false);
                        sample(width, dark, "short-value", "正文字号", null, "16 sp", false);
                        sample(width, dark, "long-value", "主题外观", "完整值保留在标题下方。", longValue, false);
                        sample(width, dark, "description", "媒体缓存", longDescription, null, false);
                        sample(width, dark, "long-title", "这是一个很长但仍需要完整显示的设置标题", "说明继续自然换行。", "已开启", false);
                    }
                    sample(width, true, "short-value", "正文字号", null, "16 sp", true);
                }
                main(() -> { activity.finish(); return null; }); waitForIdleSync(); activity = null;
            }
            passed = true;
        } catch (Exception | Error error) { failure = error.getClass().getSimpleName() + ":" + stage + ":" + error.getMessage(); }
        finally {
            if (activity != null) try { main(() -> { activity.finish(); return null; }); } catch (Exception ignored) { }
            JSONObject report = new JSONObject();
            try {
                report.put("passed", passed).put("failure", failure).put("assertions", assertions).put("cases", cases).put("screenshots", screenshots)
                        .put("ownUidOnly", true).put("internetPermission", false).put("realNetworkRequests", 0).put("realCommunityWrites", 0)
                        .put("productionClassesRewritten", false).put("displaySettingsChanged", false).put("physicalFoldPoseTested", false);
                try (FileOutputStream stream = new FileOutputStream(new File(getTargetContext().getFilesDir(), "preference-row-report.json"))) { stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
            } catch (Exception ignored) { }
            Bundle result = new Bundle(); result.putString("safe_preference_row_report", report.toString()); finish(passed ? 0 : 1, result);
        }
    }
}
