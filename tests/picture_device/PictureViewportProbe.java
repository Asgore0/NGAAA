package cn.ngapure.pictureprobe;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import cn.ngapure.app.NativePictureView;
import org.json.JSONObject;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Real Android matrices/gestures; only generated pixels, own UID and no INTERNET. */
public final class PictureViewportProbe extends Instrumentation {
    private final JSONObject report = new JSONObject(), checks = new JSONObject();
    private PictureHostActivity activity;
    private Bitmap bitmap;
    private int assertions;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        boolean passed = false;
        try {
            require(getTargetContext().checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_DENIED, "noInternetPermission");
            require(getTargetContext().getApplicationInfo().uid != getTargetContext().getPackageManager().getApplicationInfo("cn.ngapure.app", 0).uid, "differentUidFromClient");
            activity = (PictureHostActivity) startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(), PictureHostActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            bitmap = onMain(() -> Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888));
            onMain(() -> { bitmap.eraseColor(0xff7394af); return null; });
            checkPendingState(); checkRestoreAndResize(); checkGestures(); passed = true;
        } catch (Exception | AssertionError failure) { put("failure", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()); }
        finally {
            try { onMain(() -> { if (activity != null) { activity.content.removeAllViews(); activity.finish(); } if (bitmap != null) bitmap.recycle(); return null; }); }
            catch (Exception cleanup) { passed = false; put("cleanupFailed", true); }
        }
        put("passed", passed); put("assertionCount", assertions); put("checks", checks); put("realNetworkRequests", 0); put("mainClientStarted", false); put("mainAccountRead", false); put("syntheticImageOnly", true);
        Bundle output = new Bundle(); output.putString("safe_picture_viewport_report", report.toString()); finish(Activity.RESULT_OK, output);
    }
    private void checkPendingState() throws Exception {
        onMain(() -> {
            NativePictureView view = new NativePictureView(activity);
            require(view.viewportState() == null, "emptyViewportHasNoState");
            float[] initial = {3, .4f, .6f}; view.restoreViewport(initial); initial[0] = 1;
            float[] pending = view.viewportState(); require(close(pending, new float[]{3, .4f, .6f}), "pendingStateSurvivesBeforeImageAndLayout");
            pending[1] = 0; require(close(view.viewportState(), new float[]{3, .4f, .6f}), "pendingStateDoesNotExposeMutableArray");
            view.restoreViewport(new float[]{Float.NaN, .4f, .6f});
            require(close(view.viewportState(), new float[]{3, .4f, .6f}), "invalidStateDoesNotReplaceValidPendingState");
            NativePictureView replacement = new NativePictureView(activity); replacement.restoreViewport(view.viewportState());
            require(close(replacement.viewportState(), new float[]{3, .4f, .6f}), "repeatedRecreationBeforeDecodePreservesState");
            replacement.show(bitmap); replacement.layout(0, 0, 500, 800);
            require(close(replacement.viewportState(), new float[]{3, .4f, .6f}), "pendingStateAppliedAfterImageThenLayout");
            NativePictureView layoutFirst = new NativePictureView(activity); layoutFirst.layout(0, 0, 500, 800); layoutFirst.restoreViewport(new float[]{3, .4f, .6f}); layoutFirst.show(bitmap);
            require(close(layoutFirst.viewportState(), new float[]{3, .4f, .6f}), "pendingStateAppliedAfterLayoutThenImage");
            return null;
        });
    }
    private void checkRestoreAndResize() throws Exception {
        onMain(() -> {
            NativePictureView view = new NativePictureView(activity); view.layout(0, 0, 500, 800); view.show(bitmap); view.restoreViewport(new float[]{3, .4f, .6f});
            float[] captured = view.viewportState();
            NativePictureView recreated = new NativePictureView(activity); recreated.restoreViewport(captured); recreated.layout(0, 0, 900, 600); recreated.show(bitmap);
            require(close(recreated.viewportState(), captured), "differentAspectRecreationKeepsImageCenterAndRelativeZoom");
            recreated.layout(0, 0, 500, 800);
            require(close(recreated.viewportState(), captured), "sameViewFoldResizeKeepsImageCenterAndRelativeZoom");
            float[][] invalid = {null, {}, {1}, {1, .5f}, {1, .5f, .5f, 0}, {0, .5f, .5f}, {6.1f, .5f, .5f}, {3, -.1f, .5f}, {3, 1.1f, .5f}, {3, .5f, Float.POSITIVE_INFINITY}, {3, .5f, Float.NaN}};
            for (int i = 0; i < invalid.length; i++) { recreated.restoreViewport(invalid[i]); require(close(recreated.viewportState(), captured), "malformedViewportRejected" + i); }
            captured[0] = 1; require(Math.abs(recreated.viewportState()[0] - 3) < .001f, "capturedStateDoesNotMutateViewport");
            recreated.restoreViewport(new float[]{6, 0, 0}); require(finiteMatrix(recreated), "maximumZoomAndEdgeCenterClampedToFiniteMatrix");
            recreated.restoreViewport(new float[]{1, 0, 1}); require(close(recreated.viewportState(), new float[]{1, .5f, .5f}), "fittedImageCannotBePannedOffscreen");
            int[][] roundingCases = {{320, 322}, {1080, 278}};
            for (int i = 0; i < roundingCases.length; i++) {
                Bitmap exact = Bitmap.createBitmap(roundingCases[i][1], roundingCases[i][1], Bitmap.Config.ARGB_8888);
                exact.setDensity(Bitmap.DENSITY_NONE);
                NativePictureView edge = new NativePictureView(activity); edge.layout(0, 0, roundingCases[i][0], roundingCases[i][0] * 2); edge.show(exact); edge.restoreViewport(new float[]{6, .5f, .5f});
                require(close(edge.viewportState(), new float[]{6, .5f, .5f}), "maximumZoomFloatRoundingKeepsRestorableState" + i);
                edge.setImageDrawable(null); exact.recycle();
            }
            return null;
        });
    }
    private void checkGestures() throws Exception {
        onMain(() -> {
            NativePictureView view = new NativePictureView(activity); activity.content.addView(view, new FrameLayout.LayoutParams(500, 800)); view.layout(0, 0, 500, 800); view.show(bitmap);
            long now = SystemClock.uptimeMillis();
            touch(view, now, now, MotionEvent.ACTION_DOWN, 250, 400); touch(view, now, now + 30, MotionEvent.ACTION_UP, 250, 400);
            touch(view, now + 100, now + 100, MotionEvent.ACTION_DOWN, 250, 400); touch(view, now + 100, now + 130, MotionEvent.ACTION_UP, 250, 400);
            require(Math.abs(view.viewportState()[0] - 3) < .001f, "actualDoubleTapZoomsThreeTimes");
            float[] before = view.viewportState();
            touch(view, now + 700, now + 700, MotionEvent.ACTION_DOWN, 250, 400); touch(view, now + 700, now + 750, MotionEvent.ACTION_MOVE, 200, 350); touch(view, now + 700, now + 780, MotionEvent.ACTION_UP, 200, 350);
            float[] moved = view.viewportState(); require(moved[1] > before[1] && moved[2] > before[2], "actualDragMovesVisibleImageCenter");
            NativePictureView restored = new NativePictureView(activity); restored.layout(0, 0, 500, 800); restored.show(bitmap); restored.restoreViewport(moved);
            require(close(restored.viewportState(), moved), "gestureResultRestoresWithoutJump");
            require(restored.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null), "accessibilityZoomOutHandled");
            require(restored.viewportState()[0] >= 1 && restored.viewportState()[0] < moved[0], "accessibilityZoomOutStaysInBounds");
            for (int i = 0; i < 50; i++) { restored.restoreViewport(new float[]{1 + i % 6, (i % 11) / 10f, (i % 7) / 6f}); restored.layout(0, 0, i % 2 == 0 ? 500 : 900, i % 2 == 0 ? 800 : 600); require(finiteMatrix(restored), "rapidResizeRestoreFinite" + i); }
            activity.content.removeAllViews(); return null;
        });
    }
    private static void touch(View view, long down, long when, int action, float x, float y) { MotionEvent event = MotionEvent.obtain(down, when, action, x, y, 0); try { view.onTouchEvent(event); } finally { event.recycle(); } }
    private static boolean finiteMatrix(NativePictureView view) { float[] values = new float[9]; Matrix matrix = view.getImageMatrix(); matrix.getValues(values); for (float item : values) if (Float.isNaN(item) || Float.isInfinite(item)) return false; return true; }
    private static boolean close(float[] one, float[] two) { if (one == null || two == null || one.length != two.length) return false; for (int i = 0; i < one.length; i++) if (Math.abs(one[i] - two[i]) > .002f) return false; return true; }
    private <T> T onMain(Callable<T> action) throws Exception { FutureTask<T> task = new FutureTask<>(action); runOnMainSync(task); return task.get(10, TimeUnit.SECONDS); }
    private void require(boolean ok, String key) { assertions++; try { checks.put(key, ok); } catch (Exception ignored) { } if (!ok) throw new AssertionError(key); }
    private void put(String key, Object value) { try { report.put(key, value); } catch (Exception ignored) { } }
}
