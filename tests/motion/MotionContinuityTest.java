package cn.ngapure.app;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Mathematical trajectory contracts, using the same pure Java model as the Android view. */
public final class MotionContinuityTest {
    private static int checks, scenarios;
    private MotionContinuityTest() { }
    public static void main(String[] args) {
        run("redirect preserves geometric state and velocity", MotionContinuityTest::redirectContinuity);
        run("off-lens capture is continuous and catches the finger", MotionContinuityTest::offLensCapture);
        run("holding DOWN does not freeze a returning spring", MotionContinuityTest::holdDuringReturn);
        run("pointer replacement preserves current drive", MotionContinuityTest::pointerReplacement);
        run("stale batched samples cannot restore a previous intent", MotionContinuityTest::staleInput);
        run("instant UP commits finger intent rather than a lagging lobe", MotionContinuityTest::instantReleaseIntent);
        run("single coalesced first MOVE retains its release intent", MotionContinuityTest::coalescedFirstMove);
        run("rapid reversal preserves state and bounded contour", MotionContinuityTest::rapidReversal);
        run("consecutive tab clicks preserve velocity and settle at the last intent", MotionContinuityTest::rapidClickRedirection);
        run("remote tap has a visible first frame without slowing a drag release", MotionContinuityTest::remoteTap);
        run("resizing during a drag preserves drive and spring state", MotionContinuityTest::resizeDuringDrag);
        run("reversal releases the rear lobe without flattening the liquid body", MotionContinuityTest::reversalBody);
        run("gap saturation has no threshold seam", MotionContinuityTest::smoothSaturation);
        run("outer contour has no zero-crossing channel swap", MotionContinuityTest::zeroCrossing);
        run("60/120 Hz trajectory replay remains consistent", MotionContinuityTest::frameRateReplay);
        run("jitter and a dropped frame remain finite", MotionContinuityTest::jitterAndDrop);
        run("release converges and idle stops", MotionContinuityTest::releaseAndIdle);
        run("held idle can resume without losing drag", MotionContinuityTest::heldIdleResume);
        run("mirrored trajectories have mirrored mechanics", MotionContinuityTest::mirrorSymmetry);
        run("trace is opt-in, bounded and stops on dump", MotionContinuityTest::boundedTrace);
        System.out.println("LiquidMotion: " + scenarios + " scenarios, " + checks + " checks PASS");
    }
    private static void redirectContinuity() {
        LiquidMotion m = model(0);
        m.redirect(3);
        advance(m, 0, 30, 5);
        float[] before = geometry(m);
        m.redirect(0);
        same(before, geometry(m), "target event");
        m.advanceTo(30_100_000L);
        expect(m.head() > before[0], "inertia is retained immediately after reversal");
        expect(Math.abs(m.head() - before[0] - before[3] * .0001) < .0004,
                "first 100 microseconds agree with retained velocity, not a target jump");
    }
    private static void offLensCapture() {
        LiquidMotion m = model(0);
        float[] before = geometry(m);
        m.beginDrag(2.8, 0);
        same(before, geometry(m), "off-lens ownership");
        m.advanceTo(1_000_000L);
        expect(m.head() < .001, "first millisecond does not teleport toward off-lens finger");
        advance(m, 1, 220, 4);
        expect(m.head() > 2.5, "off-lens capture catches finger within 220 ms");
        advance(m, 220, 700, 4);
        expect(Math.abs(m.head() - 2.8) < .001, "off-lens capture reaches finger");
    }
    private static void holdDuringReturn() {
        LiquidMotion m = model(0);
        m.redirect(3);
        advance(m, 0, 15, 5);
        float headAtDown = m.head();
        float[] downBefore = geometry(m);
        // DOWN itself has no model mutation; arbitrary delay continues the existing clock.
        same(downBefore, geometry(m), "DOWN");
        advance(m, 15, 45, 5);
        expect(m.head() > headAtDown + .5, "spring continues while finger is held before horizontal ownership");
        float[] capture = geometry(m);
        m.beginDrag(m.head(), 45_000_000L);
        same(capture, geometry(m), "on-lens capture");
    }
    private static void pointerReplacement() {
        LiquidMotion m = model(0);
        m.beginDrag(0, 0);
        for (int t = 16; t <= 160; t += 16) {
            m.advanceTo(t * 1_000_000L); m.dragTo(t / 160.0, t * 1_000_000L);
        }
        float target = m.target();
        float[] before = geometry(m);
        m.rebasePointer(161_000_000L);
        same(before, geometry(m), "replacement finger");
        expect(m.target() == target, "replacement preserves drive target");
        m.dragTo(target, 161_000_000L);
        same(before, geometry(m), "replacement stationary sample");
    }
    private static void staleInput() {
        LiquidMotion m = model(0); m.beginDrag(0, 0);
        m.dragTo(2, 16_000_000L); m.advanceTo(20_000_000L);
        float[] before = geometry(m);
        m.dragTo(0, 8_000_000L);
        same(before, geometry(m), "stale batched MOVE");
        expect(m.target() == 2, "stale MOVE does not replace the newest finger intent");
        m.dragTo(1, 24_000_000L);
        expect(m.target() == 1, "fresh reversal is still accepted after stale history");
    }
    private static void rapidReversal() {
        LiquidMotion m = model(0);
        m.beginDrag(0, 0);
        for (int t = 4; t <= 2400; t += 4) {
            m.advanceTo(t * 1_000_000L);
            float[] before = geometry(m);
            double target = ((t / 96) & 1) == 0 ? 3 : 0;
            m.dragTo(target, t * 1_000_000L);
            same(before, geometry(m), "rapid target redirect");
            bounded(m);
        }
    }
    private static void rapidClickRedirection() {
        LiquidMotion m = model(0);
        int[] destinations = {3, 1, 2, 0, 2, 1, 3, 0, 3, 2, 0, 1};
        int now = 0;
        for (int destination : destinations) {
            float[] before = geometry(m);
            m.redirect(destination);
            same(before, geometry(m), "consecutive click redirect");
            expect(m.target() == destination, "latest click replaces only the intent");
            advance(m, now, now + 48, 4);
            now += 48;
            bounded(m);
        }
        advance(m, now, now + 1800, 4);
        expect(!m.active(), "rapid clicks end at finite idle");
        expect(Math.abs(m.head() - 1) < .001 && Math.abs(m.tail() - 1) < .001,
                "no earlier click overwrites the final destination");
    }
    private static void remoteTap() {
        LiquidMotion m = model(0); m.redirect(3);
        m.advanceTo(16_000_000L);
        expect(m.center() > .08 && m.center() < .9, "remote tap begins moving without jumping half the dock");
        advance(m, 16, 120, 4);
        expect(m.center() > 2.6, "tap still reaches the final tab promptly");
    }
    private static void resizeDuringDrag() {
        LiquidMotion m = model(0);
        m.beginDrag(0, 0);
        for (int now = 16; now <= 192; now += 16) {
            m.advanceTo(now * 1_000_000L);
            m.dragTo(now / 80.0, now * 1_000_000L);
        }
        float head = m.head(), rawTail = m.rawTail(), headVelocity = m.headVelocity();
        float tailVelocity = m.tailVelocity(), height = m.height(), target = m.target();
        long clock = m.timeNanos();
        // Width changes are expressed in logical cell units, as on an unfolding
        // window. No pixel target or animation clock may survive from old size.
        for (double width : new double[]{.96, .72, .9}) {
            m.configure(4, width);
            expect(m.head() == head && m.rawTail() == rawTail, "resize retains raw spring positions");
            expect(m.headVelocity() == headVelocity && m.tailVelocity() == tailVelocity,
                    "resize retains velocity");
            expect(m.height() == height && m.target() == target && m.timeNanos() == clock,
                    "resize retains current drive, thickness and clock");
            expect(m.dragging(), "resize keeps gesture ownership");
            expect(m.widthScale() < 1.56001 && m.neckScale() >= .905,
                    "resized contour retains bounded liquid body");
        }
        m.rebasePointer(193_000_000L);
        m.dragTo(target - .5, 209_000_000L);
        advance(m, 192, 288, 4);
        expect(m.target() == target - .5, "post-resize finger drive is retained");
        bounded(m);
    }
    private static void reversalBody() {
        LiquidMotion m = model(0);
        m.redirect(3);
        advance(m, 0, 180, 4);
        float rawTail = m.rawTail();
        m.redirect(0);
        boolean changedDirection = false;
        float minimumHeight = 1;
        for (int now = 184; now <= 500; now += 4) {
            m.advanceTo(now * 1_000_000L);
            minimumHeight = Math.min(minimumHeight, m.height());
            changedDirection |= m.tailVelocity() < -.01;
            expect(m.neckScale() >= .905, "waist remains joined during lobe fusion");
            expect(m.widthScale() < 1.56001, "stretch never bridges two full tab widths");
            bounded(m);
        }
        expect(rawTail > .1 && changedDirection, "test covers a moving rear lobe that reverses");
        expect(minimumHeight > .835, "fast reversal retains visible vertical body");
    }
    private static void instantReleaseIntent() {
        LiquidMotion m = model(0); m.beginDrag(0, 0);
        m.dragTo(2.6, 1_000_000L);
        expect(Math.round(m.target()) == 3, "UP chooses latest finger target");
        expect(Math.round(m.head()) == 0, "test exercises a head that has not caught up");
        float[] before = geometry(m); m.redirect(Math.round(m.target()));
        same(before, geometry(m), "instant UP commit");
        expect(m.target() == 3, "committed intent survives return spring");
        m.snap(2.6, 2_000_000L);
        expect(Math.round(m.target()) == Math.round(m.head()), "reduced-motion snap keeps same selection intent");
    }
    private static void coalescedFirstMove() {
        LiquidMotion m = model(0);
        float[] before = geometry(m);
        m.beginDragFrom(0, 2.6, 1_000_000L);
        same(before, geometry(m), "coalesced first MOVE");
        expect(Math.round(m.target()) == 3, "single first MOVE is not discarded at ownership transfer");
        m.redirect(Math.round(m.target()));
        expect(m.target() == 3, "immediate UP after only one MOVE commits destination");
        m.snap(3, 2_000_000L);
        m.beginDragFrom(3, -2.6, 3_000_000L);
        expect(Math.round(m.target()) == 0, "negative/RTL signed first MOVE preserves intent");
    }
    private static void smoothSaturation() {
        double limit = .54, h = 1e-6;
        for (double x : new double[]{-limit, 0, limit}) {
            double center = LiquidMotion.boundedGap(x, limit);
            double slopeLeft = (center - LiquidMotion.boundedGap(x - h, limit)) / h;
            double slopeRight = (LiquidMotion.boundedGap(x + h, limit) - center) / h;
            expect(Math.abs(slopeLeft - slopeRight) < .00001, "gap derivative continuous at " + x);
        }
        double e = limit * .025;
        double left = (LiquidMotion.smoothExtent(0, e) - LiquidMotion.smoothExtent(-h, e)) / h;
        double right = (LiquidMotion.smoothExtent(h, e) - LiquidMotion.smoothExtent(0, e)) / h;
        expect(Math.abs(left - right) < .0001, "extent derivative continuous through crossing");
        expect(Math.abs(LiquidMotion.boundedGap(1e6, limit)) <= limit, "extreme gap remains bounded");
    }
    private static void zeroCrossing() {
        LiquidMotion m = model(0);
        m.redirect(3);
        advance(m, 0, 180, 4);
        m.redirect(0);
        float previousLeft = m.leftCenter(), previousRight = m.rightCenter();
        float previousBias = m.bias(), previousGap = m.head() - m.tail();
        boolean crossed = false;
        for (int t = 181; t <= 500; t++) {
            m.advanceTo(t * 1_000_000L);
            float gap = m.head() - m.tail();
            if (gap * previousGap < 0) crossed = true;
            expect(m.leftCenter() <= m.rightCenter(), "left/right order stable");
            // A direct multi-tab click can travel faster than a finger drag. The
            // bound includes its retained spring velocity and acceleration, while
            // still rejecting a lobe/channel swap at the zero crossing.
            float stepBound = .012f + Math.max(Math.abs(m.headVelocity()), Math.abs(m.tailVelocity())) * .002f;
            expect(Math.abs(m.leftCenter() - previousLeft) < stepBound, "left outer center continuous");
            expect(Math.abs(m.rightCenter() - previousRight) < stepBound, "right outer center continuous");
            expect(Math.abs(m.bias() - previousBias) < .15, "no binary radius/light flip");
            previousLeft = m.leftCenter(); previousRight = m.rightCenter();
            previousBias = m.bias(); previousGap = gap;
        }
        expect(crossed, "trajectory exercises an actual head/tail crossing");
    }
    private static void frameRateReplay() {
        float[][] slow = replay(16), fast = replay(8);
        for (int i = 0; i < slow.length; i++) for (int j = 0; j < slow[i].length; j++) {
            expect(Math.abs(slow[i][j] - fast[i][j]) < .025, "frame partition changes mechanics excessively");
        }
    }
    private static float[][] replay(int frameMs) {
        LiquidMotion m = model(0); m.beginDrag(0, 0);
        float[][] samples = new float[8][];
        for (int t = 1; t <= 1200; t++) {
            if (t % 20 == 0) {
                m.advanceTo(t * 1_000_000L);
                double target = t <= 600 ? t / 200.0 : (1200 - t) / 200.0;
                m.dragTo(target, t * 1_000_000L);
            }
            if (t % frameMs == 0) m.advanceTo(t * 1_000_000L);
            if (t % 150 == 0) { m.advanceTo(t * 1_000_000L); samples[t / 150 - 1] = geometry(m); }
        }
        return samples;
    }
    private static void jitterAndDrop() {
        LiquidMotion m = model(0); m.redirect(3);
        long now = 0;
        int[] jitter = {1, 33, 7, 21, 48, 2, 15, 5000, 5, 18};
        for (int dt : jitter) { now += dt * 1_000_000L; m.advanceTo(now); bounded(m); }
        float[] before = geometry(m);
        m.advanceTo(now - 500_000_000L);
        same(before, geometry(m), "stale input/frame timestamp");
    }
    private static void releaseAndIdle() {
        LiquidMotion m = model(0); m.beginDrag(2.4, 0);
        advance(m, 0, 130, 5);
        float[] before = geometry(m); m.redirect(2);
        same(before, geometry(m), "UP target commit");
        int t;
        for (t = 135; t < 2200 && m.active(); t += 5) { m.advanceTo(t * 1_000_000L); bounded(m); }
        expect(t < 1800, "release reaches finite idle threshold");
        expect(Math.abs(m.head() - 2) < .001 && Math.abs(m.tail() - 2) < .001, "both ends settle to selected item");
        m.rest(t * 1_000_000L);
        expect(!m.active() && m.height() == 1 && m.activity() == 0, "idle needs no frames");
    }
    private static void heldIdleResume() {
        LiquidMotion m = model(0); m.beginDrag(1, 0);
        advance(m, 0, 1800, 5); m.rest(1_800_000_000L);
        expect(m.dragging() && !m.active(), "held idle keeps gesture ownership but no frame work");
        m.dragTo(2, 2_000_000_000L);
        m.advanceTo(2_010_000_000L);
        expect(m.active() && m.head() > 1, "next MOVE restarts a held idle gesture");
    }
    private static void mirrorSymmetry() {
        LiquidMotion a = model(0), b = model(3);
        a.redirect(3); b.redirect(0);
        for (int t = 5; t <= 700; t += 5) {
            a.advanceTo(t * 1_000_000L); b.advanceTo(t * 1_000_000L);
            expect(Math.abs(a.head() + b.head() - 3) < .000001, "head mirror");
            expect(Math.abs(a.tail() + b.tail() - 3) < .000001, "tail mirror");
            expect(Math.abs(a.widthScale() - b.widthScale()) < .000001, "width mirror");
            expect(Math.abs(a.leftRadiusScale(false) - b.leftRadiusScale(true)) < .000001, "radius mirror");
        }
    }
    private static void boundedTrace() {
        LiquidMotion m = model(0); LiquidMotion.Trace trace = new LiquidMotion.Trace(8);
        expect(!trace.enabled(), "trace defaults off"); trace.start();
        for (int i = 1; i <= 20; i++) trace.record(LiquidMotion.Trace.FRAME, i, m, 0, false, .1f, .3f, 90, 52);
        StringWriter buffer = new StringWriter(); trace.dump(new PrintWriter(buffer), "");
        String text = buffer.toString();
        expect(!trace.enabled(), "dump stops trace");
        expect(text.contains("count=8 total=20 dropped=12"), "ring has fixed bound");
        expect(text.contains("data=frame,13,") && text.contains("data=frame,20,"), "ring preserves newest samples chronologically");
        expect(!text.contains("data=frame,12,"), "ring evicts old samples");
        expect(!text.contains("touchX") && !text.contains("cookie"), "diagnostic excludes touch/account fields");
    }
    private static LiquidMotion model(double initial) {
        LiquidMotion m = new LiquidMotion(); m.configure(4, .9); m.snap(initial, 0); return m;
    }
    private static void advance(LiquidMotion m, int fromMs, int toMs, int stepMs) {
        for (int t = fromMs + stepMs; t <= toMs; t += stepMs) m.advanceTo(t * 1_000_000L);
        m.advanceTo(toMs * 1_000_000L);
    }
    private static float[] geometry(LiquidMotion m) {
        return new float[]{m.head(), m.tail(), m.rawTail(), m.headVelocity(), m.tailVelocity(), m.height(),
                m.heightVelocity(), m.activity(), m.leftCenter(), m.rightCenter(), m.bias(),
                m.leftRadiusScale(false), m.rightRadiusScale(false), m.neckScale()};
    }
    private static void same(float[] before, float[] after, String phase) {
        for (int i = 0; i < before.length; i++) expect(before[i] == after[i], phase + " changed state field " + i);
    }
    private static void bounded(LiquidMotion m) {
        for (float value : geometry(m)) expect(Float.isFinite(value), "finite motion state");
        expect(m.head() >= -.000001 && m.head() <= 3.000001, "head inside item range without projection");
        expect(m.tail() >= -.000001 && m.tail() <= 3.000001, "tail inside item range without projection");
        expect(m.widthScale() >= .99999 && m.widthScale() < 1.56001, "visible length bounded");
        expect(m.height() >= .835 && m.height() <= 1.001, "visible height bounded");
    }
    private static void run(String name, Runnable test) { test.run(); scenarios++; System.out.println("PASS " + name); }
    private static void expect(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
