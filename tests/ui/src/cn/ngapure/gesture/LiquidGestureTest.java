package cn.ngapure.gesture;

import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One-shot legacy UIAutomator test. No app install, account access, or background service. */
@SuppressWarnings("deprecation")
public final class LiquidGestureTest extends com.android.uiautomator.testrunner.UiAutomatorTestCase {
    private static final String TARGET = "cn.ngapure.app";
    private final List<Finger> fingers = new ArrayList<>();
    private UiAutomation automation;
    private long downTime, lastEventTime, runStartTime;
    private int sampleMs, samplePauseMs, markerIndex, sequenceIndex, eventCount;
    private int repeatCount, gapMs, tapHoldMs;
    private float x0, y0, cell;
    private float durationScale;
    private boolean screenshots, states, active;
    private String mode;
    private File output;

    public void testGesture() throws Exception {
        Bundle params = getParams();
        mode = params.getString("mode", "slow_hold");
        require("slow_hold".equals(mode) || "reverse".equals(mode) || "cancel".equals(mode)
                || "multi".equals(mode) || "vertical".equals(mode) || "tap".equals(mode)
                || "rapid_zigzag".equals(mode) || "regrab".equals(mode)
                || "rapid_taps".equals(mode) || "home_reselect".equals(mode)
                || "tracking_ramp".equals(mode) || "tracking_reverse".equals(mode), "Unknown mode");
        x0 = number(params, "x0", Float.NaN);
        y0 = number(params, "y", Float.NaN);
        cell = number(params, "cell", Float.NaN);
        sampleMs = integer(params, "sampleMs", 16, 8, 50);
        samplePauseMs = integer(params, "samplePauseMs", 0, 0, 5000);
        repeatCount = integer(params, "repeatCount", 2, 1, 10);
        gapMs = integer(params, "gapMs", 24, 0, 250);
        tapHoldMs = integer(params, "tapHoldMs", 32, 16, 120);
        durationScale = number(params, "durationScale", 1f);
        require(durationScale >= .25f && durationScale <= 10f, "durationScale must be .25..10");
        require(Float.isFinite(x0) && Float.isFinite(y0) && Float.isFinite(cell) && cell >= 20f,
                "Supply finite x0/y/cell in physical display pixels");
        screenshots = Boolean.parseBoolean(params.getString("screenshots", "false"));
        states = Boolean.parseBoolean(params.getString("states", "false"));
        if (isRapidMode()) require(!screenshots && !states && samplePauseMs == 0,
                "Rapid modes require screenshots=false, states=false and samplePauseMs=0");
        if ("home_reselect".equals(mode)) require(gapMs >= 16 && gapMs <= 24 && durationScale == 1f,
                "home_reselect requires gapMs=16..24 and durationScale=1");
        String destination = params.getString("out", "/data/local/tmp/ngapure-liquid-" + mode);
        require(destination.matches("/data/local/tmp/[A-Za-z0-9_-]+"), "out must be a named /data/local/tmp directory");
        output = new File(destination);
        if (screenshots || states) require(output.isDirectory() || output.mkdirs(), "Cannot create output directory");
        automation = runnerAutomation();
        int width = getUiDevice().getDisplayWidth(), height = getUiDevice().getDisplayHeight();
        require(x0 >= 0 && y0 >= 0 && x0 < width && y0 < height, "Start coordinate is outside display");
        float rightmost = x0 + ("rapid_taps".equals(mode) ? 3f : "rapid_zigzag".equals(mode) ? 2.9f
                : trackingMode() ? 2.8f
                : "regrab".equals(mode) ? 2.65f : "reverse".equals(mode) ? 2f : "multi".equals(mode) ? 1.30f
                : "cancel".equals(mode) ? .8f : "slow_hold".equals(mode) ? 1f : 0f) * cell;
        require(rightmost < width, "Trajectory exits the display; provide the leftmost selected cell center");
        if ("vertical".equals(mode)) require(y0 - cell * .75f >= 0, "Vertical trajectory exits display");
        // Check the foreground before DOWN; never launch, navigate to, or inspect another app.
        require(TARGET.equals(getUiDevice().getCurrentPackageName()), "Target client must already be foreground");
        runStartTime = SystemClock.uptimeMillis();
        System.out.println("DRIVER_READY mode=" + mode + " sampleMs=" + sampleMs
                + " screenshots=" + screenshots + " states=" + states + " target=" + TARGET
                + " uptime=" + runStartTime + " repeatCount=" + repeatCount
                + " gapMs=" + gapMs + " tapHoldMs=" + tapHoldMs);
        try {
            // Rapid taps begin at item 3, avoiding a second tap on an already-selected home.
            if (!"rapid_taps".equals(mode)) down(x0, y0);
            switch (mode) {
                case "slow_hold":
                    move(0, x0 + .6f * cell, y0, 600);
                    sampleHold("SLOW_060_HELD", 250);
                    move(0, x0 + cell, y0, 300);
                    marker("SLOW_100_BEFORE_UP");
                    up();
                    break;
                case "reverse":
                    move(0, x0 + 2f * cell, y0, 300);
                    marker("REVERSE_200_HELD");
                    move(0, x0 + .7f * cell, y0, 180);
                    marker("REVERSE_070_BEFORE_UP");
                    up();
                    break;
                case "cancel":
                    move(0, x0 + .8f * cell, y0, 450);
                    sampleHold("CANCEL_080_HELD", 180);
                    send(MotionEvent.ACTION_CANCEL);
                    active = false;
                    fingers.clear();
                    break;
                case "multi":
                    move(0, x0 + .35f * cell, y0, 200);
                    fingers.add(new Finger(11, x0 + .55f * cell, y0));
                    send(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
                    marker("MULTI_SECOND_DOWN");
                    moveBoth(.3f * cell, 0, 200);
                    // Lift active pointer id 7 at index 0; keep id 11, original downTime.
                    send(MotionEvent.ACTION_POINTER_UP);
                    fingers.remove(0);
                    marker("MULTI_ACTIVE_REPLACED");
                    move(0, x0 + 1.30f * cell, y0, 200);
                    marker("MULTI_BEFORE_UP");
                    up();
                    break;
                case "vertical":
                    move(0, x0, y0 - cell * .75f, 350);
                    marker("VERTICAL_BEFORE_UP");
                    up();
                    break;
                case "tap":
                    pause(80);
                    up();
                    break;
                case "rapid_zigzag":
                    rapidZigzag();
                    break;
                case "regrab":
                    regrab();
                    break;
                case "rapid_taps":
                    rapidTaps();
                    break;
                case "home_reselect":
                    homeReselect();
                    break;
                case "tracking_ramp":
                case "tracking_reverse":
                    trackingReversals();
                    break;
                default: throw new IllegalStateException("Invalid mode");
            }
            marker("RELEASED");
            pause(1000);
            marker("SETTLED");
            System.out.println("DRIVER_DONE mode=" + mode + " active=" + active
                    + " uptime=" + SystemClock.uptimeMillis() + " sequences=" + sequenceIndex
                    + " events=" + eventCount);
        } finally {
            // Failed assertion, screenshot, state capture or reflection must not leave a held touch.
            if (active && !fingers.isEmpty()) {
                try { send(MotionEvent.ACTION_CANCEL); }
                finally { active = false; fingers.clear(); }
            }
        }
    }

    private void down(float x, float y) {
        require(!active, "DOWN cannot replace a held pointer; finish or cancel it first");
        fingers.clear();
        fingers.add(new Finger(7, x, y));
        downTime = lastEventTime = SystemClock.uptimeMillis();
        active = true;
        sequenceIndex++;
        send(MotionEvent.ACTION_DOWN);
        System.out.println("TOUCH_DOWN uptime=" + downTime + " pointerId=7 sequence=" + sequenceIndex
                + " runElapsedMs=" + (downTime - runStartTime) + " x=" + x + " y=" + y);
    }

    private void up() {
        require(active && fingers.size() == 1, "UP needs exactly one active pointer");
        send(MotionEvent.ACTION_UP);
        System.out.println("TOUCH_UP uptime=" + lastEventTime + " downTime=" + downTime
                + " sequence=" + sequenceIndex + " x=" + fingers.get(0).x + " y=" + fingers.get(0).y);
        active = false;
        fingers.clear();
    }

    private void rapidZigzag() throws IOException {
        float[] targets = {2.8f, .2f, 2.65f, .35f, 2.9f, 1.1f, 2.3f};
        long[] durations = {150, 140, 130, 120, 160, 140, 130};
        // No UP or extra DOWN between legs or repeats: every reversal shares downTime/id 7.
        for (int round = 0; round < repeatCount; round++) {
            for (int leg = 0; leg < targets.length; leg++) {
                move(0, x0 + targets[leg] * cell, y0, durations[leg]);
                marker("ZIGZAG_R" + (round + 1) + "_TURN_" + (leg + 1));
            }
        }
        up();
    }

    private void regrab() throws IOException {
        move(0, x0 + 2.2f * cell, y0, 220);
        marker("REGRAB_FIRST_BEFORE_UP");
        up();
        // Each recontact gets its own genuine DOWN/downTime. Do not wait for spring settlement.
        float[] starts = {2.2f, .35f};
        float[] targets = {.35f, 2.65f};
        for (int grab = 0; grab < starts.length; grab++) {
            if (gapMs > 0) pause(gapMs);
            down(x0 + starts[grab] * cell, y0);
            marker("REGRAB_" + (grab + 1) + "_DOWN");
            move(0, x0 + targets[grab] * cell, y0, 180);
            marker("REGRAB_" + (grab + 1) + "_BEFORE_UP");
            up();
        }
    }

    private void rapidTaps() throws IOException {
        int[] targets = {3, 1, 2, 0, 2, 1, 3, 0};
        for (int round = 0; round < repeatCount; round++) {
            for (int tap = 0; tap < targets.length; tap++) {
                if (sequenceIndex > 0 && gapMs > 0) pause(gapMs);
                down(x0 + targets[tap] * cell, y0);
                pause(tapHoldMs);
                up();
                marker("RAPID_TAP_R" + (round + 1) + "_ITEM_" + targets[tap]);
            }
        }
    }

    private boolean isRapidMode() {
        return "rapid_zigzag".equals(mode) || "regrab".equals(mode) || "rapid_taps".equals(mode)
                || "home_reselect".equals(mode) || trackingMode();
    }

    private boolean trackingMode() { return "tracking_ramp".equals(mode) || "tracking_reverse".equals(mode); }

    /** Known public fixture trajectory; sample times let the host measure drawn lag. */
    private void trackingReversals() throws IOException {
        move(0, x0 + .2f * cell, y0, 80);
        long leg = "tracking_ramp".equals(mode) ? 640 : 160;
        for (int turn = 0; turn < 6; turn++) {
            move(0, x0 + ((turn & 1) == 0 ? 2.8f : .2f) * cell, y0, leg);
            marker("TRACKING_TURN_" + (turn + 1));
        }
        up();
    }

    private void homeReselect() throws IOException {
        marker("HOME_RESELECT_CONTACT_1_DOWN");
        pause(32);
        up();
        marker("HOME_RESELECT_CONTACT_1_UP");
        pause(gapMs);
        down(x0, y0);
        marker("HOME_RESELECT_CONTACT_2_DOWN");
        pause(32);
        up();
        marker("HOME_RESELECT_CONTACT_2_UP");
    }

    private void move(int pointerIndex, float x, float y, long durationMs) {
        Finger finger = fingers.get(pointerIndex);
        float startX = finger.x, startY = finger.y;
        long start = SystemClock.uptimeMillis(), duration = scaled(durationMs);
        long end = start + duration;
        do {
            long now = SystemClock.uptimeMillis();
            float progress = Math.min(1f, (now - start) / (float) duration);
            finger.x = startX + (x - startX) * progress;
            finger.y = startY + (y - startY) * progress;
            send(MotionEvent.ACTION_MOVE);
            if (progress >= 1f) break;
            SystemClock.sleep(Math.min(sampleMs, Math.max(1, end - SystemClock.uptimeMillis())));
        } while (true);
    }

    private void moveBoth(float dx, float dy, long durationMs) {
        float[] xs = new float[fingers.size()], ys = new float[fingers.size()];
        for (int i = 0; i < fingers.size(); i++) { xs[i] = fingers.get(i).x; ys[i] = fingers.get(i).y; }
        long start = SystemClock.uptimeMillis(), duration = scaled(durationMs), end = start + duration;
        do {
            float progress = Math.min(1f, (SystemClock.uptimeMillis() - start) / (float) duration);
            for (int i = 0; i < fingers.size(); i++) { fingers.get(i).x = xs[i] + dx * progress; fingers.get(i).y = ys[i] + dy * progress; }
            send(MotionEvent.ACTION_MOVE);
            if (progress >= 1f) break;
            SystemClock.sleep(Math.min(sampleMs, Math.max(1, end - SystemClock.uptimeMillis())));
        } while (true);
    }

    private void send(int action) {
        int count = fingers.size();
        require(count > 0, "No active pointer");
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            Finger finger = fingers.get(i);
            MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
            property.id = finger.id;
            property.toolType = MotionEvent.TOOL_TYPE_FINGER;
            properties[i] = property;
            MotionEvent.PointerCoords coordinate = new MotionEvent.PointerCoords();
            coordinate.x = finger.x; coordinate.y = finger.y;
            int masked = action & MotionEvent.ACTION_MASK;
            int actionIndex = (action & MotionEvent.ACTION_POINTER_INDEX_MASK) >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
            coordinate.pressure = masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL
                    || (masked == MotionEvent.ACTION_POINTER_UP && i == actionIndex) ? 0f : 1f;
            coordinate.size = .08f;
            coordinates[i] = coordinate;
        }
        lastEventTime = Math.max(lastEventTime, SystemClock.uptimeMillis());
        MotionEvent event = MotionEvent.obtain(downTime, lastEventTime, action, count, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            require(automation.injectInputEvent(event, true), "Input injection rejected: " + MotionEvent.actionToString(action));
            eventCount++;
            if (trackingMode() && action == MotionEvent.ACTION_MOVE) {
                System.out.println("TOUCH_SAMPLE uptime=" + lastEventTime + " position="
                        + (fingers.get(0).x - x0) / cell);
            }
        }
        finally { event.recycle(); }
    }

    private void sampleHold(String name, long holdMs) throws IOException {
        long deadline = SystemClock.uptimeMillis() + scaled(holdMs);
        marker(name);
        long remaining = deadline - SystemClock.uptimeMillis();
        if (remaining > 0) SystemClock.sleep(remaining);
    }

    private void marker(String name) throws IOException {
        int index = ++markerIndex;
        long now = SystemClock.uptimeMillis();
        String base = String.format(Locale.US, "%02d-%s", index, name);
        System.out.println("GESTURE_MARKER name=" + name + " elapsedMs=" + (now - downTime)
                + " uptime=" + now + " downTime=" + downTime + " held=" + active
                + " pointerIds=" + pointerIds() + " runElapsedMs=" + (now - runStartTime)
                + " sequence=" + sequenceIndex + " events=" + eventCount
                + " x=" + (fingers.isEmpty() ? "none" : Float.toString(fingers.get(0).x))
                + " y=" + (fingers.isEmpty() ? "none" : Float.toString(fingers.get(0).y)));
        System.out.flush();
        if (screenshots) {
            Bitmap bitmap = automation.takeScreenshot();
            require(bitmap != null, "Screenshot returned null");
            File image = new File(output, base + ".png");
            try (FileOutputStream stream = new FileOutputStream(image)) {
                require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream), "Screenshot encoding failed");
            } finally { bitmap.recycle(); }
            System.out.println("SCREENSHOT file=" + image.getAbsolutePath() + " held=" + active);
        }
        if (states) {
            String state = motionState();
            try (FileOutputStream stream = new FileOutputStream(new File(output, base + ".txt"))) {
                stream.write(state.getBytes(StandardCharsets.UTF_8));
            }
            System.out.print(state);
        }
        if (active && samplePauseMs > 0) SystemClock.sleep(samplePauseMs);
    }

    private String motionState() throws IOException {
        StringBuilder result = new StringBuilder();
        // Fixed component and fixed command; save only the geometry diagnostic, no account/UI text.
        try (ParcelFileDescriptor descriptor = automation.executeShellCommand(
                "dumpsys activity cn.ngapure.app/.MainActivity --ui-state");
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     new ParcelFileDescriptor.AutoCloseInputStream(descriptor), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                int marker = line.indexOf("NgaPureMotion ");
                if (marker >= 0) result.append(line.substring(marker)).append('\n');
            }
        }
        if (result.length() == 0) result.append("MOTION_STATE_UNAVAILABLE\n");
        return result.toString();
    }

    private String pointerIds() {
        StringBuilder result = new StringBuilder();
        for (Finger finger : fingers) { if (result.length() > 0) result.append(','); result.append(finger.id); }
        return result.length() == 0 ? "none" : result.toString();
    }

    private UiAutomation runnerAutomation() throws Exception {
        Object bridge = invokeNoArg(getUiDevice(), "getAutomatorBridge");
        try { return (UiAutomation) invokeNoArg(bridge, "getUiAutomation"); }
        catch (NoSuchMethodException missingMethod) {
            for (Class<?> type = bridge.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (!UiAutomation.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    UiAutomation found = (UiAutomation) field.get(bridge);
                    if (found != null) return found;
                }
            }
            throw missingMethod;
        }
    }

    private static Object invokeNoArg(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            Method method;
            try { method = type.getDeclaredMethod(name); }
            catch (NoSuchMethodException absent) { continue; }
            method.setAccessible(true);
            return method.invoke(target);
        }
        throw new NoSuchMethodException(name);
    }

    private void pause(long milliseconds) { SystemClock.sleep(scaled(milliseconds)); }
    private long scaled(long milliseconds) { return Math.max(1L, Math.round(milliseconds * durationScale)); }
    private static float number(Bundle params, String key, float fallback) {
        String value = params.getString(key);
        return value == null ? fallback : Float.parseFloat(value);
    }
    private static int integer(Bundle params, String key, int fallback, int minimum, int maximum) {
        String value = params.getString(key);
        int result = value == null ? fallback : Integer.parseInt(value);
        require(result >= minimum && result <= maximum, key + " outside allowed range");
        return result;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static final class Finger {
        final int id;
        float x, y;
        Finger(int pointerId, float pointerX, float pointerY) { id = pointerId; x = pointerX; y = pointerY; }
    }
}
