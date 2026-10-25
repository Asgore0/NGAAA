package cn.ngapure.app;

import java.util.Arrays;
import java.util.Locale;

/** Measures what is drawn relative to a moving finger, not just event continuity. */
public final class MotionResponsivenessTest {
    private static int checks;
    private static boolean measureOnly;
    private MotionResponsivenessTest() { }

    public static void main(String[] args) {
        measureOnly = args.length > 0 && "--measure-only".equals(args[0]);
        for (int inputMs : new int[]{8, 16}) for (int frameMs : new int[]{8, 16, 33}) {
            Metrics smooth = tracking(frameMs, inputMs, false);
            Metrics rapid = tracking(frameMs, inputMs, true);
            int settle = release(frameMs, inputMs);
            System.out.printf(Locale.US,
                    "RESPONSIVENESS inputMs=%d frameMs=%d smoothMean=%.6f smoothP95=%.6f rapidMean=%.6f rapidP95=%.6f reversalMs=%d releaseSettlingMs=%d%n",
                    inputMs, frameMs, smooth.mean(), smooth.p95(), rapid.mean(), rapid.p95(), rapid.reversalMs, settle);
            expect(smooth.mean() < .12, "slow drag drawn center remains near finger at " + frameMs + " ms");
            expect(rapid.mean() < .30, "rapid reversal drawn center mean error at " + frameMs + " ms");
            expect(rapid.p95() < .52, "rapid reversal drawn center p95 error at " + frameMs + " ms");
            expect(rapid.reversalMs <= Math.max(48, frameMs * 2),
                    "reversal stops travelling away within two low-rate frames at " + frameMs + " ms");
            expect(settle <= 450, "released contour fuses and rests within 450 ms at " + frameMs + " ms");
        }
        hold(8); hold(16);
        System.out.println("MotionResponsiveness: 12 drag profiles + 6 releases + 2 holds, " + checks + " checks "
                + (measureOnly ? "MEASURED" : "PASS"));
    }

    private static Metrics tracking(int frameMs, int inputMs, boolean rapid) {
        LiquidMotion m = model(.2);
        m.beginDrag(.2, 0);
        Metrics metrics = new Metrics();
        int legMs = rapid ? 160 : 640;
        int duration = legMs * 6;
        int previousLeg = 0, reversalStart = -1;
        float previousCenter = m.center();
        for (int now = 1; now <= duration; now++) {
            int leg = Math.min(5, now / legMs);
            double progress = (now - leg * legMs) / (double) legMs;
            double target = (leg & 1) == 0 ? .2 + 2.6 * progress : 2.8 - 2.6 * progress;
            if (now % inputMs == 0 || now == duration) {
                m.advanceTo(now * 1_000_000L);
                m.dragTo(target, now * 1_000_000L);
            }
            if (leg != previousLeg) { reversalStart = now; previousLeg = leg; }
            if (now % frameMs != 0) continue;
            m.advanceTo(now * 1_000_000L);
            float center = m.center();
            if (now >= 96) metrics.add(Math.abs(center - target));
            if (reversalStart >= 0) {
                double sign = (leg & 1) == 0 ? 1 : -1;
                if ((center - previousCenter) * sign >= 0) {
                    metrics.reversalMs = Math.max(metrics.reversalMs, now - reversalStart);
                    reversalStart = -1;
                }
            }
            expect(Float.isFinite(center) && m.leftCenter() >= -.00001 && m.rightCenter() <= 3.00001,
                    "drawn contour centers stay finite and inside the dock");
            previousCenter = center;
        }
        return metrics;
    }

    private static int release(int frameMs, int inputMs) {
        LiquidMotion m = model(.2);
        m.beginDrag(.2, 0);
        for (int now = inputMs; now <= 160; now += inputMs) {
            m.advanceTo(now * 1_000_000L); m.dragTo(.2 + 2.4 * now / 160.0, now * 1_000_000L);
        }
        float center = m.center(), velocity = m.headVelocity();
        m.redirect(3);
        expect(m.center() == center && m.headVelocity() == velocity, "release retains geometry and velocity");
        int settled = -1;
        for (int elapsed = frameMs; elapsed <= 1000; elapsed += frameMs) {
            m.advanceTo((160L + elapsed) * 1_000_000L);
            boolean within = Math.abs(m.center() - 3) < .012 && m.widthScale() < 1.018
                    && Math.abs(m.height() - 1) < .008 && Math.abs(m.headVelocity()) < .12
                    && Math.abs(m.tailVelocity()) < .12;
            if (within && settled < 0) settled = elapsed;
            else if (!within) settled = -1;
        }
        return settled < 0 ? 1001 : settled;
    }

    private static void hold(int inputMs) {
        LiquidMotion m = model(.2); m.beginDrag(.2, 0);
        for (int now = inputMs; now <= 96; now += inputMs) {
            m.advanceTo(now * 1_000_000L); m.dragTo(.2 + 1.5 * now / 96.0, now * 1_000_000L);
        }
        float overshoot = 0;
        for (int now = 100; now <= 400; now += 4) {
            m.advanceTo(now * 1_000_000L); overshoot = Math.max(overshoot, m.center() - 1.7f);
        }
        System.out.printf(Locale.US, "HELD_STOP inputMs=%d overshootCells=%.6f finalError=%.6f%n",
                inputMs, overshoot, Math.abs(m.center() - 1.7));
        expect(overshoot < .18, "stationary finger has bounded fluid overshoot at " + inputMs + " ms input");
        expect(Math.abs(m.center() - 1.7) < .01, "stationary finger retires prediction without a new MOVE");
    }

    private static LiquidMotion model(double position) {
        LiquidMotion m = new LiquidMotion(); m.configure(4, .9); m.snap(position, 0); return m;
    }
    private static void expect(boolean condition, String message) {
        checks++; if (!condition && !measureOnly) throw new AssertionError(message);
    }
    private static final class Metrics {
        private final double[] errors = new double[2000];
        private int count, reversalMs;
        private double sum;
        void add(double value) { errors[count++] = value; sum += value; }
        double mean() { return sum / Math.max(1, count); }
        double p95() { Arrays.sort(errors, 0, count); return errors[Math.max(0, (int) Math.ceil(count * .95) - 1)]; }
    }
}
