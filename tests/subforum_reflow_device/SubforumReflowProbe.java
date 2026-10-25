package cn.ngapure.subforumreflowprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import cn.ngapure.app.MainActivity;
import cn.ngapure.app.NgaApi;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Measures the unchanged product subforum GridLayout, retaining children across reflows. */
public final class SubforumReflowProbe extends Instrumentation {
    private static final String PACKAGE = "cn.ngapure.subforumreflowprobe";
    private static final int[] WIDTHS = {520, 320, 760, 220, 520, 320, 760, 220};
    private final JSONArray cases = new JSONArray(), exceptions = new JSONArray();
    private final AtomicInteger blocked = new AtomicInteger();
    private MainActivity activity;
    private float font;
    private boolean authorized, expectFixed;
    private int assertions, issues, regressionCount, sequences, expandCollapseClicks;
    private String stage = "guard";

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        authorized = args != null && "true".equals(args.getString("syntheticSubforumReflowOnly"))
                && ("true".equals(args.getString("expectFixed")) || "false".equals(args.getString("expectFixed")));
        expectFixed = args != null && "true".equals(args.getString("expectFixed"));
        start();
    }

    @Override public Activity newActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Activity value = super.newActivity(loader, name, intent);
        if (value instanceof MainActivity) {
            Configuration config = new Configuration(); config.densityDpi = 160; config.fontScale = font;
            value.applyOverrideConfiguration(config);
        }
        return value;
    }

    private static Field field(Object owner, String name) throws Exception {
        Field result = owner.getClass().getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static Object get(Object owner, String name) throws Exception { return field(owner, name).get(owner); }
    private static void set(Object owner, String name, Object value) throws Exception { field(owner, name).set(owner, value); }
    private <T> T main(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work); runOnMainSync(task); return task.get(10, TimeUnit.SECONDS);
    }
    private void check(JSONObject row, String name, boolean value) throws Exception {
        assertions++; row.put(name, value); if (!value) issues++;
    }
    private static Rect bounds(ViewGroup root, View target) {
        Rect result = new Rect(); target.getDrawingRect(result);
        root.offsetDescendantRectToMyCoords(target, result); return result;
    }
    private static boolean inside(ViewGroup root, Rect rect) {
        return rect.left >= 0 && rect.top >= 0 && rect.right <= root.getWidth() && rect.bottom <= root.getHeight();
    }
    private static Throwable cause(Throwable error) {
        Throwable value = error;
        for (int i = 0; i < 12 && value.getCause() != null; i++) value = value.getCause();
        return value;
    }
    private static boolean knownRegression(Throwable error) {
        if (!(error instanceof IllegalArgumentException) || error.getMessage() == null
                || !error.getMessage().contains("columnCount")) return false;
        boolean framework = false, product = false;
        for (StackTraceElement frame : error.getStackTrace()) {
            framework |= frame.getClassName().startsWith("android.widget.GridLayout");
            product |= frame.getClassName().startsWith("cn.ngapure.app.MainActivity$") && "onMeasure".equals(frame.getMethodName());
        }
        return framework && product;
    }

    private List<NgaApi.Forum> forums(int count) {
        List<NgaApi.Forum> values = new ArrayList<>();
        String[] names = {"猫咪交流", "狗狗交流", "异宠交流", "宠物健康", "领养信息"};
        for (int i = 0; i < count; i++) {
            NgaApi.Forum forum = new NgaApi.Forum(); forum.key = String.valueOf(910001 + i); forum.fid = forum.key;
            forum.parentFid = "414"; forum.name = names[i]; forum.category = "子版块"; forum.description = "宠物讨论";
            values.add(forum);
        }
        return values;
    }

    private void measure(LinearLayout list, GridLayout grid, List<NgaApi.Forum> forums, View[] original,
                         int width, String phase) throws Exception {
        stage = "n" + forums.size() + "-f" + font + "-" + phase + "-w" + width;
        list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        list.layout(0, 0, list.getMeasuredWidth(), list.getMeasuredHeight());
        int expectedColumns = width - 24 >= Math.round(352 * Math.max(1f, font)) ? 2 : 1;
        int childWidth = (width - 24) / expectedColumns - 8;
        JSONObject row = new JSONObject().put("case", stage).put("widthDp", width).put("fontScale", font)
                .put("expectedColumns", expectedColumns).put("actualColumns", grid.getColumnCount())
                .put("childCount", grid.getChildCount()).put("gridHeight", grid.getHeight());
        cases.put(row);
        check(row, "realGridHasExpectedColumns", grid.getColumnCount() == expectedColumns);
        check(row, "allExpectedChildrenPresent", grid.getChildCount() == original.length);
        check(row, "gridFitsRequestedWidth", grid.getWidth() == width && inside(list, bounds(list, grid)));
        JSONArray children = new JSONArray(); row.put("children", children);
        for (int i = 0; i < grid.getChildCount(); i++) {
            ViewGroup cell = (ViewGroup) grid.getChildAt(i);
            GridLayout.LayoutParams params = (GridLayout.LayoutParams) cell.getLayoutParams();
            ViewGroup tile = (ViewGroup) cell.findViewWithTag("subforum:" + forums.get(i).key);
            if (tile == null) throw new AssertionError("Actual subforum row required");
            TextView star = tile.findViewWithTag("forum-favorite:" + forums.get(i).key);
            if (star == null) throw new AssertionError("Actual favorite control required");
            Rect rect = bounds(grid, cell), target = bounds(tile, star);
            JSONObject child = new JSONObject().put("index", i).put("bounds", rect.toShortString())
                    .put("favoriteBoundsInTile", target.toShortString()).put("paramsWidth", params.width);
            children.put(child);
            check(child, "sameChildInstanceReflowed", i < original.length && cell == original[i]);
            check(child, "columnAndRowSpecsUseCurrentIndices", params.columnSpec.equals(GridLayout.spec(i % expectedColumns))
                    && params.rowSpec.equals(GridLayout.spec(i / expectedColumns)));
            check(child, "cellWidthAndBoundsValid", params.width == childWidth && cell.getWidth() == childWidth
                    && rect.left >= grid.getPaddingLeft() && rect.right <= grid.getWidth() - grid.getPaddingRight()
                    && rect.top >= grid.getPaddingTop() && rect.bottom <= grid.getHeight() - grid.getPaddingBottom());
            check(child, "boardTargetAtLeast48AndBound", tile.getWidth() >= 48 && tile.getHeight() >= 48
                    && tile.isClickable() && tile.isFocusable() && inside(cell, bounds(cell, tile))
                    && String.valueOf(tile.getContentDescription()).contains(forums.get(i).name));
            check(child, "favoriteTargetAtLeast48AndBound", target.width() >= 48 && target.height() >= 48
                    && inside(tile, target) && star.isClickable()
                    && String.valueOf(star.getContentDescription()).contains(forums.get(i).name));
            boolean separate = true;
            for (int j = i + 1; j < grid.getChildCount(); j++) separate &= !Rect.intersects(rect, bounds(grid, grid.getChildAt(j)));
            check(child, "cellsNeverOverlap", separate);
        }
        if (forums.size() > 2) {
            View toggle = list.getChildAt(2);
            check(row, "expandCollapseTargetAtLeast48AndBound", toggle.getWidth() >= 48 && toggle.getHeight() >= 48
                    && toggle.isClickable() && inside(list, bounds(list, toggle)));
        }
    }

    private static View[] children(GridLayout grid) {
        View[] result = new View[grid.getChildCount()];
        for (int i = 0; i < result.length; i++) result[i] = grid.getChildAt(i);
        return result;
    }

    private void sequence(int count) throws Exception {
        sequences++;
        try {
            main(() -> {
                // Invalidate startup callbacks before installing a synthetic current screen.
                set(activity, "generation", ((Integer) get(activity, "generation")) + 1);
                Class<?> screenType = Class.forName("cn.ngapure.app.MainActivity$Screen");
                Constructor<?> constructor = screenType.getDeclaredConstructor(String.class, String.class, String.class, int.class);
                constructor.setAccessible(true); Object owner = constructor.newInstance("forum", "414", "隔离子版块回归", 1);
                set(owner, "subForumsExpanded", true); set(activity, "current", owner);
                LinearLayout list = new LinearLayout(activity); list.setOrientation(LinearLayout.VERTICAL);
                List<NgaApi.Forum> values = forums(count);
                Method method = MainActivity.class.getDeclaredMethod("subForums", LinearLayout.class, List.class);
                method.setAccessible(true); method.invoke(activity, list, values);
                if (!(list.getChildAt(1) instanceof GridLayout)) throw new AssertionError("Actual product GridLayout required");
                GridLayout grid = (GridLayout) list.getChildAt(1); View[] original = children(grid);
                if (original.length != count) throw new AssertionError("All synthetic subforums must start expanded");
                for (int i = 0; i < WIDTHS.length; i++) measure(list, grid, values, original, WIDTHS[i], "resize" + i);
                if (count > 2) {
                    View toggle = list.getChildAt(2); toggle.performClick(); expandCollapseClicks++;
                    if ((Boolean) get(owner, "subForumsExpanded") || grid.getChildCount() != 2) throw new AssertionError("Real collapse callback must keep first two children");
                    measure(list, grid, values, children(grid), 520, "collapsed-wide");
                    measure(list, grid, values, children(grid), 220, "collapsed-narrow");
                    toggle.performClick(); expandCollapseClicks++;
                    if (!(Boolean) get(owner, "subForumsExpanded") || grid.getChildCount() != count) throw new AssertionError("Real expand callback must restore every child");
                    View[] expanded = children(grid);
                    measure(list, grid, values, expanded, 760, "expanded-wide");
                    measure(list, grid, values, expanded, 320, "expanded-narrow");
                }
                return null;
            });
        } catch (Exception error) {
            Throwable actual = cause(error); boolean known = knownRegression(actual);
            exceptions.put(new JSONObject().put("case", stage).put("type", actual.getClass().getSimpleName())
                    .put("message", String.valueOf(actual.getMessage())).put("knownColumnCountShrinkRegression", known));
            if (known) regressionCount++; else throw error;
        }
    }

    @Override public void onStart() {
        boolean fixedPassed = false, expectationMatched = false; String failure = "NONE";
        try {
            if (!authorized || !PACKAGE.equals(getTargetContext().getPackageName())
                    || getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED)
                throw new IllegalStateException("OWN_UID_NO_INTERNET_AND_EXPLICIT_EXPECTATION_REQUIRED");
            URL.setURLStreamHandlerFactory(protocol -> ("http".equals(protocol) || "https".equals(protocol))
                    ? new URLStreamHandler() { @Override protected URLConnection openConnection(URL url) throws IOException {
                        blocked.incrementAndGet(); throw new IOException("SYNTHETIC_OFFLINE_SUBFORUM_REFLOW");
                    }} : null);
            for (float scale : new float[]{1, 1.3f, 2}) {
                font = scale;
                activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                waitForIdleSync();
                if (!main(() -> Math.abs(activity.getResources().getDisplayMetrics().density - 1) < .001f
                        && Math.abs(activity.getResources().getConfiguration().fontScale - font) < .001f))
                    throw new AssertionError("Local font/density override required");
                sequence(2); sequence(5);
                main(() -> { activity.finish(); return null; }); activity = null; waitForIdleSync();
            }
            fixedPassed = issues == 0 && regressionCount == 0 && sequences == 6 && cases.length() == 60 && expandCollapseClicks == 6;
            expectationMatched = expectFixed ? fixedPassed : issues == 0 && regressionCount == 6 && sequences == 6;
        } catch (Throwable error) {
            Throwable actual = cause(error); failure = stage + ":" + actual.getClass().getSimpleName() + ":" + actual.getMessage();
        } finally {
            if (activity != null) try { main(() -> { activity.finish(); return null; }); } catch (Exception ignored) { }
            JSONObject report = new JSONObject();
            try {
                report.put("passed", fixedPassed).put("expectFixed", expectFixed).put("expectationMatched", expectationMatched)
                        .put("failure", failure).put("assertions", assertions).put("observedFailures", issues)
                        .put("knownRegressionReproduced", regressionCount > 0).put("knownRegressionCount", regressionCount)
                        .put("sequences", sequences).put("cases", cases).put("exceptions", exceptions).put("expandCollapseClicks", expandCollapseClicks)
                        .put("ownUidOnly", true).put("internetPermission", false).put("blockedOfflineAttempts", blocked.get())
                        .put("realNetworkRequests", 0).put("realCommunityWrites", 0).put("favoriteClicks", 0)
                        .put("realAccountAccessed", false).put("productionClassesRewritten", false)
                        .put("measurement", "actualMainActivitySubForumsSynchronousDetachedMeasureAndLayoutOnMainThread")
                        .put("physicalFoldPoseTested", false).put("displaySettingsChanged", false);
                try (FileOutputStream stream = new FileOutputStream(new File(getTargetContext().getFilesDir(), "subforum-reflow-report.json"))) {
                    stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) { }
            Bundle result = new Bundle(); result.putString("safe_subforum_reflow_report", report.toString());
            finish(expectationMatched ? 0 : 1, result);
        }
    }
}
