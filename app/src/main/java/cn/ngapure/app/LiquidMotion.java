package cn.ngapure.app;

import java.io.PrintWriter;
import java.util.Locale;

/** Continuous, clock-driven capsule mechanics. Deliberately independent of Android. */
final class LiquidMotion {
    private static final double HEAD_OMEGA = 112.0;
    private static final double TAIL_OMEGA = 32.0;
    private static final double CAPTURE_OMEGA = 50.0;
    private static final double CLICK_OMEGA = 70.0;
    private static final double HEIGHT_OMEGA = 30.0;
    private static final double BIAS_OMEGA = 34.0;
    private static final double STEP = 1.0 / 240.0;
    private double maximum, baseWidth = .9, gapLimit = .54;
    private double head, tail, headV, tailV, height = 1, heightV;
    private double target, inputV, captureOffset, captureV, captureOmega = CAPTURE_OMEGA;
    private double lastInputTarget;
    private long timeNanos, inputNanos;
    private boolean clockSet, dragging;
    private double visibleTail, gap, extent, bias, biasV, desiredBias, separation, activity;

    void configure(int itemCount, double widthInCells) {
        maximum = Math.max(0, itemCount - 1);
        baseWidth = Math.max(.05, widthInCells);
        gapLimit = baseWidth * .56;
        updateShape();
    }

    void snap(double position, long now) {
        head = tail = target = bound(position);
        lastInputTarget = target;
        headV = tailV = heightV = inputV = captureOffset = captureV = 0;
        height = 1;
        bias = biasV = 0;
        dragging = false;
        timeNanos = inputNanos = now;
        clockSet = true;
        updateShape();
    }

    void rest(long now) {
        boolean held = dragging;
        snap(target, Math.max(timeNanos, now));
        dragging = held;
    }

    /** Changes intent only. State, velocity and clock are untouched. */
    void redirect(double position) {
        double next = bound(position);
        if (!dragging && next == target) return;
        target = next;
        // Retained position/velocity already make a release C1-continuous. A
        // released drag must not run through another capture/easing filter.
        // A tap has no pointer trajectory, so give its remote destination a short
        // intent ramp instead of travelling several cells in its very first frame.
        if (dragging) captureOffset = captureV = 0;
        else { captureOffset = head - target; captureV = headV; captureOmega = CLICK_OMEGA; }
        dragging = false;
        inputV = 0;
    }

    /** The capture offset decays continuously; even an off-lens grab cannot teleport a lobe. */
    void beginDrag(double position, long now) {
        target = lastInputTarget = bound(position);
        captureOffset = head - target;
        captureV = headV;
        captureOmega = CAPTURE_OMEGA;
        inputV = 0;
        inputNanos = now;
        dragging = true;
    }

    /** Preserve the first coalesced MOVE's intent without moving the rendered capsule. */
    void beginDragFrom(double downAnchor, double signedDeltaInCells, long now) {
        beginDrag(downAnchor + signedDeltaInCells, now);
    }

    void dragTo(double position, long now) {
        if (now < inputNanos) return;
        double next = bound(position);
        if (now > inputNanos) {
            double dt = Math.max(.004, (now - inputNanos) * 1e-9);
            double sampledV = Math.max(-18, Math.min(18, (next - lastInputTarget) / dt));
            // A reversal should replace stale prediction within a touch sample,
            // rather than carry the previous direction for another 35 ms.
            double mix = 1 - Math.exp(-dt / .008);
            inputV += (sampledV - inputV) * mix;
        }
        target = lastInputTarget = next;
        inputNanos = Math.max(inputNanos, now);
    }

    /** A replacement finger changes neither the geometric state nor its current drive target. */
    void rebasePointer(long now) { inputNanos = Math.max(inputNanos, now); }

    void advanceTo(long now) {
        if (!clockSet) { timeNanos = now; clockSet = true; return; }
        if (now <= timeNanos) return;
        double elapsed = Math.min(.050, (now - timeNanos) * 1e-9);
        timeNanos = now;
        int steps = Math.max(1, (int) Math.ceil(elapsed / STEP));
        double dt = elapsed / steps;
        for (int i = 0; i < steps; i++) integrate(dt);
        updateShape();
    }

    private void integrate(double dt) {
        double captureC = captureV + captureOmega * captureOffset;
        double captureDecay = Math.exp(-captureOmega * dt);
        captureOffset = (captureOffset + captureC * dt) * captureDecay;
        captureV = (captureV - captureOmega * captureC * dt) * captureDecay;
        if (dragging) inputV *= Math.exp(-dt / .045);
        // A small bounded prediction compensates the tracking filter's phase lag. The
        // prediction/offset affect acceleration only, never position or velocity assignments.
        double aim = bound(target + captureOffset + (dragging ? .024 * inputV : 0));
        double error = head - aim;
        double c = headV + HEAD_OMEGA * error;
        double decay = Math.exp(-HEAD_OMEGA * dt);
        head = aim + (error + c * dt) * decay;
        headV = (headV - HEAD_OMEGA * c * dt) * decay;
        // Fixed critical damping + bounded drive keeps the raw lobes inside the item range.
        // Velocity-dependent lag creates visible fluid stretch, without moving a constrained tail.
        // The rear lobe should release from the previous tab during a reversal,
        // rather than remain pinned there throughout a short back-and-forth drag.
        // Its drive is still continuous and critically damped; no velocity reset.
        double tailAim = bound(head - .045 * headV);
        error = tail - tailAim;
        c = tailV + TAIL_OMEGA * error;
        decay = Math.exp(-TAIL_OMEGA * dt);
        tail = tailAim + (error + c * dt) * decay;
        tailV = (tailV - TAIL_OMEGA * c * dt) * decay;
        updateShape();
        error = bias - desiredBias;
        c = biasV + BIAS_OMEGA * error;
        decay = Math.exp(-BIAS_OMEGA * dt);
        bias = desiredBias + (error + c * dt) * decay;
        biasV = (biasV - BIAS_OMEGA * c * dt) * decay;
        double speed = Math.abs(headV) / (Math.abs(headV) + 5);
        // Keep enough vertical body for the two lobes to read as one liquid
        // surface. A long thin waist looked like two outlined pills merging.
        double targetHeight = 1 - .145 * separation - .018 * speed;
        error = height - targetHeight;
        c = heightV + HEIGHT_OMEGA * error;
        decay = Math.exp(-HEIGHT_OMEGA * dt);
        height = targetHeight + (error + c * dt) * decay;
        heightV = (heightV - HEIGHT_OMEGA * c * dt) * decay;
    }

    private void updateShape() {
        // Smooth saturation changes only the visible separation; raw state and velocity
        // are never projected onto a hard length constraint.
        gap = boundedGap(head - tail, gapLimit);
        visibleTail = head - gap;
        extent = smoothExtent(gap, gapLimit * .025);
        separation = extent / gapLimit;
        desiredBias = Math.tanh(gap / (gapLimit * .50));
        double speed = Math.abs(headV) / (Math.abs(headV) + 5);
        activity = Math.min(1, .82 * separation + .30 * speed);
    }

    boolean active() {
        return Math.abs(head - target) > .0006 || Math.abs(tail - target) > .0006
                || Math.abs(headV) > .009 || Math.abs(tailV) > .009
                || Math.abs(height - 1) > .0006 || Math.abs(heightV) > .009
                || Math.abs(bias) > .0006 || Math.abs(biasV) > .009
                || Math.abs(captureOffset) > .0006 || Math.abs(captureV) > .009
                || (dragging && Math.abs(inputV) > .009);
    }

    boolean dragging() { return dragging; }
    long timeNanos() { return timeNanos; }
    float head() { return (float) head; }
    float tail() { return (float) visibleTail; }
    float rawTail() { return (float) tail; }
    float headVelocity() { return (float) headV; }
    float tailVelocity() { return (float) tailV; }
    float heightVelocity() { return (float) heightV; }
    float height() { return (float) height; }
    float activity() { return (float) activity; }
    float bias() { return (float) bias; }
    float separation() { return (float) separation; }
    float target() { return (float) target; }
    // Most stretch trails the finger, but the entire selection should not lag by
    // half its elastic length. Shift the drawn body toward the tracked lobe. The
    // exponential edge taper stays smooth and never moves an end out of the dock.
    private double drawnCenter() {
        double shift = .34 * gap;
        double room = shift >= 0 ? maximum - head : head;
        shift *= -Math.expm1(-Math.max(0, room) / (Math.abs(shift) + 1e-9));
        return (head + visibleTail) * .5 + shift;
    }
    float center() { return (float) drawnCenter(); }
    float leftCenter() { return (float) (drawnCenter() - extent * .5); }
    float rightCenter() { return (float) (drawnCenter() + extent * .5); }
    float widthScale() { return (float) (1 + extent / baseWidth); }
    float neckScale() { return (float) (1 - .095 * separation); }
    float leftRadiusScale(boolean rtl) { return radiusScale(rtl ? -bias : bias); }
    float rightRadiusScale(boolean rtl) { return radiusScale(rtl ? bias : -bias); }
    private float radiusScale(double signedBias) { return (float) (1 - .0325 * separation * (1 + signedBias)); }
    private double bound(double value) { return Math.max(0, Math.min(maximum, value)); }

    static double boundedGap(double value, double limit) { return limit * Math.tanh(value / limit); }
    static double smoothExtent(double value, double epsilon) {
        return Math.sqrt(value * value + epsilon * epsilon) - epsilon;
    }

    /** Optional fixed-capacity primitive ring; no strings, allocation or I/O on record(). */
    static final class Trace {
        static final int START = 0, FRAME = 1, DOWN_BEFORE = 2, DOWN_AFTER = 3, MOVE = 4;
        static final int TARGET_BEFORE = 5, TARGET_AFTER = 6, RELEASE = 7, POINTER = 8, STOP = 9;
        private static final String[] EVENTS = {"start", "frame", "down-before", "down-after", "move",
                "target-before", "target-after", "release", "pointer", "stop"};
        private static final int STRIDE = 16;
        private final int capacity;
        private long[] times;
        private byte[] events, flags;
        private int[] selected;
        private float[] values;
        private boolean enabled;
        private int count, cursor;
        private long total, lastTime;
        Trace(int size) { capacity = Math.max(8, size); }
        synchronized void start() {
            if (times == null) {
                times = new long[capacity]; events = new byte[capacity]; flags = new byte[capacity];
                selected = new int[capacity]; values = new float[capacity * STRIDE];
            }
            count = cursor = 0; total = lastTime = 0; enabled = true;
        }
        synchronized boolean enabled() { return enabled; }
        synchronized void record(int event, long now, LiquidMotion motion, int selection, boolean drag,
                                 float left, float right, float width, float renderedHeight) {
            if (!enabled) return;
            int slot = cursor, base = slot * STRIDE;
            lastTime = Math.max(lastTime, now);
            times[slot] = lastTime; events[slot] = (byte) event; selected[slot] = selection;
            flags[slot] = (byte) (drag ? 1 : 0);
            values[base] = motion.head(); values[base + 1] = motion.tail();
            values[base + 2] = motion.rawTail(); values[base + 3] = motion.headVelocity();
            values[base + 4] = motion.tailVelocity(); values[base + 5] = motion.height();
            values[base + 6] = motion.heightVelocity(); values[base + 7] = motion.activity();
            values[base + 8] = motion.bias(); values[base + 9] = left; values[base + 10] = right;
            values[base + 11] = width; values[base + 12] = renderedHeight;
            values[base + 13] = motion.neckScale(); values[base + 14] = motion.target();
            values[base + 15] = motion.widthScale();
            cursor = (cursor + 1) % capacity; count = Math.min(capacity, count + 1); total++;
        }
        synchronized void dump(PrintWriter out, String prefix) {
            enabled = false;
            out.println(prefix + "LiquidTrace capacity=" + capacity + " count=" + count + " total=" + total
                    + " dropped=" + (total - count));
            out.println(prefix + "LiquidTrace columns=event,nanos,head,tail,rawTail,headV,tailV,heightScale,heightV,activity,bias,left,right,width,height,neckScale,target,widthScale,selection,dragging");
            int first = (cursor - count + capacity) % capacity;
            for (int i = 0; i < count; i++) {
                int slot = (first + i) % capacity, base = slot * STRIDE;
                int event = events[slot];
                out.print(prefix + "LiquidTrace data=" + EVENTS[event] + "," + times[slot]);
                for (int field = 0; field < STRIDE; field++) out.printf(Locale.US, ",%.6f", values[base + field]);
                out.println("," + selected[slot] + "," + (flags[slot] != 0));
            }
        }
    }
}
