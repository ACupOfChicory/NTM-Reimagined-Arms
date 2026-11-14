package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BiFunction;

import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class WalkSprintTest {
    // the phase goes on with the steps on the ground and stands still in the air, however fast the player flies
    @Test
    void phaseOnlyOnTheGround() {
        float acc = 0F;
        acc = WalkSprint.groundStep(acc, true, 1F, 1.3F);
        assertEquals(0.3F, acc, 1e-6F);
        for (int i = 0; i < 100; i++) acc = WalkSprint.groundStep(acc, false, i, i + 1.5F);
        assertEquals(0.3F, acc, 1e-6F);
        acc = WalkSprint.groundStep(acc, true, 5F, 5.2F);
        assertEquals(0.5F, acc, 1e-6F);
    }

    // a jump of the walk distance (a new world, a respawn) and the first frame are not steps
    @Test
    void noStepFromAJump() {
        assertEquals(1F, WalkSprint.groundStep(1F, true, 0F, 40F));
        assertEquals(1F, WalkSprint.groundStep(1F, true, Float.NaN, 3F));
    }

    // ---- the curves against notes/measure.md (local): per signal rx, ry, rz (degrees), tx, ty, tz (in L), each the
    // two guns of the class: {mean, A1, u1, A2, u2}

    static final float[][][] WALK_P = {
            {{0.01F, 0.15F, 0.23F, 0.02F, 0.42F}, {0.01F, 0.15F, 0.23F, 0.02F, 0.42F}},
            {{0.12F, 1.98F, 0.74F, 0.15F, 0.42F}, {0.12F, 1.98F, 0.74F, 0.15F, 0.42F}},
            {{8.91F, 3.14F, 0.60F, 0.18F, 0.18F}, {8.95F, 3.14F, 0.61F, 0.18F, 0.17F}},
            {{-0.0207F, 0.0125F, 0.21F, 0.0007F, 0.23F}, {-0.0251F, 0.0129F, 0.19F, 0.0005F, 0.24F}},
            {{-0.0589F, 0.0015F, 0.16F, 0.0140F, 0.27F}, {-0.0479F, 0.0017F, 0.15F, 0.0112F, 0.28F}},
            {{0.0345F, 0.0001F, 0.75F, 0.0001F, 0.25F}, {0.0276F, 0.0001F, 0.75F, 0.0000F, 0.25F}}};
    static final float[][][] WALK_R = {
            {{0.02F, 0.04F, 0.23F, 0.01F, 0.44F}, {0.03F, 0.04F, 0.23F, 0.01F, 0.44F}},
            {{0.12F, 2.00F, 0.74F, 0.15F, 0.42F}, {0.12F, 2.00F, 0.74F, 0.15F, 0.42F}},
            {{2.26F, 0.98F, 0.63F, 0.05F, 0.13F}, {2.27F, 0.98F, 0.64F, 0.05F, 0.13F}},
            {{-0.0037F, 0.0064F, 0.24F, 0.0005F, 0.19F}, {-0.0068F, 0.0090F, 0.22F, 0.0005F, 0.19F}},
            {{-0.0059F, 0.0002F, 0.19F, 0.0049F, 0.28F}, {-0.0068F, 0.0004F, 0.18F, 0.0056F, 0.28F}},
            {{0.0152F, 0.0000F, 0.04F, 0.0001F, 0.20F}, {0.0174F, 0.0000F, 0.78F, 0.0001F, 0.21F}}};
    static final float[][][] SPRINT_P = {
            {{29.78F, 3.99F, 0.71F, 2.87F, 0.18F}, {29.79F, 3.99F, 0.72F, 2.87F, 0.18F}},
            {{28.80F, 8.06F, 0.00F, 1.30F, 0.21F}, {28.80F, 8.05F, 0.01F, 1.29F, 0.22F}},
            {{13.52F, 4.40F, 0.67F, 2.32F, 0.17F}, {13.56F, 4.40F, 0.68F, 2.32F, 0.17F}},
            {{-0.1348F, 0.1714F, 0.61F, 0.0034F, 0.44F}, {-0.1173F, 0.1332F, 0.61F, 0.0041F, 0.44F}},
            {{-0.1807F, 0.0023F, 1.00F, 0.0670F, 0.19F}, {-0.1495F, 0.0035F, 0.13F, 0.0521F, 0.20F}},
            {{0.0463F, 0.0138F, 0.88F, 0.0084F, 0.18F}, {0.0689F, 0.0139F, 0.85F, 0.0097F, 0.19F}}};
    static final float[][][] SPRINT_R = {
            {{20.30F, 3.69F, 0.67F, 2.12F, 0.19F}, {20.31F, 3.69F, 0.67F, 2.12F, 0.19F}},
            {{51.37F, 7.67F, 0.02F, 1.16F, 0.23F}, {51.37F, 7.67F, 0.02F, 1.16F, 0.23F}},
            {{14.50F, 3.92F, 0.65F, 1.54F, 0.18F}, {14.51F, 3.92F, 0.65F, 1.54F, 0.18F}},
            {{-0.0949F, 0.0558F, 0.61F, 0.0067F, 0.16F}, {-0.1306F, 0.0615F, 0.61F, 0.0070F, 0.16F}},
            {{-0.0969F, 0.0035F, 0.91F, 0.0328F, 0.20F}, {-0.1120F, 0.0041F, 0.98F, 0.0364F, 0.20F}},
            {{0.0524F, 0.0133F, 0.99F, 0.0038F, 0.21F}, {0.0964F, 0.0158F, 0.94F, 0.0075F, 0.20F}}};

    /** Our three angles back to the rotation vector, through the matrix turn makes of them: rx, ry, rz in degrees. */
    static Vector3f vector(WalkSprint.Pose p) {
        Matrix3f m = ours(p);
        double angle = Math.acos(Math.clamp((m.m00 + m.m11 + m.m22 - 1.0) / 2.0, -1.0, 1.0));
        if (angle < 1e-9) return new Vector3f();
        double k = Math.toDegrees(angle) / (2.0 * Math.sin(angle));
        // JOML's mCR: column C, row R; the axis from the antisymmetric part
        return new Vector3f((float) ((m.m12 - m.m21) * k), (float) ((m.m20 - m.m02) * k), (float) ((m.m01 - m.m10) * k));
    }

    /** The matrix of our angles as turn applies them: roll, then yaw, then pitch, outermost first. */
    static Matrix3f ours(WalkSprint.Pose p) {
        return new Matrix3f().rotateZ((float) Math.toRadians(p.roll())).rotateY((float) Math.toRadians(p.yaw()))
                .rotateX((float) Math.toRadians(p.pitch()));
    }

    static Matrix3f measured(float rx, float ry, float rz) {
        float a = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        return new Matrix3f().rotation((float) Math.toRadians(a), rx / a, ry / a, rz / a);
    }

    static float signal(WalkSprint.Pose p, int k) {
        if (k < 3) return vector(p).get(k);
        return k == 3 ? p.x() : k == 4 ? p.y() : p.z();
    }

    /** mean, A1, u1, A2, u2 of a signal over the cycle, by integrating over u. */
    static double[] harmonics(java.util.function.DoubleUnaryOperator f) {
        int n = 720;
        double m = 0, c1 = 0, s1 = 0, c2 = 0, s2 = 0;
        for (int i = 0; i < n; i++) {
            double u = (i + 0.5) / n, v = f.applyAsDouble(u);
            m += v / n;
            c1 += 2 * v * Math.cos(2 * Math.PI * u) / n;
            s1 += 2 * v * Math.sin(2 * Math.PI * u) / n;
            c2 += 2 * v * Math.cos(4 * Math.PI * u) / n;
            s2 += 2 * v * Math.sin(4 * Math.PI * u) / n;
        }
        double u1 = Math.atan2(s1, c1) / (2 * Math.PI), u2 = Math.atan2(s2, c2) / (4 * Math.PI);
        return new double[] {m, Math.hypot(c1, s1), u1 - Math.floor(u1), Math.hypot(c2, s2), u2 - 0.5 * Math.floor(u2 / 0.5)};
    }

    /** The phase difference on a circle of the period. */
    static double phaseOff(double a, double b, double period) {
        double d = Math.abs(a - b) % period;
        return Math.min(d, period - d);
    }

    // each of mean, A1, u1, A2, u2 within the two measured guns' spread around their middle, and a small margin;
    // a phase only where its harmonic is not lost in the noise
    static void matches(String what, float[][][] meas, BiFunction<Float, Integer, Float> curve) {
        String[] names = {"rx", "ry", "rz", "tx", "ty", "tz"};
        for (int k = 0; k < 6; k++) {
            int kk = k;
            double[] h = harmonics(u -> curve.apply((float) u, kk));
            float[] a = meas[k][0], b = meas[k][1];
            boolean turn = k < 3;
            double tol = turn ? 0.03 : 0.0006, noise = turn ? 0.1 : 0.002;
            String at = what + " " + names[k];
            assertEquals((a[0] + b[0]) / 2, h[0], Math.abs(a[0] - b[0]) / 2 + tol, at + " mean");
            assertEquals((a[1] + b[1]) / 2, h[1], Math.abs(a[1] - b[1]) / 2 + tol, at + " A1");
            assertEquals((a[3] + b[3]) / 2, h[3], Math.abs(a[3] - b[3]) / 2 + tol, at + " A2");
            if (Math.min(a[1], b[1]) >= noise)
                assertTrue(phaseOff(h[2], a[2], 1) <= phaseOff(a[2], b[2], 1) + 0.01 && phaseOff(h[2], b[2], 1) <= phaseOff(a[2], b[2], 1) + 0.01,
                        at + " u1 " + h[2]);
            if (Math.min(a[3], b[3]) >= noise)
                assertTrue(phaseOff(h[4], a[4], 0.5) <= phaseOff(a[4], b[4], 0.5) + 0.01 && phaseOff(h[4], b[4], 0.5) <= phaseOff(a[4], b[4], 0.5) + 0.01,
                        at + " u2 " + h[4]);
        }
    }

    @Test
    void walkFromTheHipIsAsMeasured() {
        matches("walk pistol", WALK_P, (u, k) -> signal(WalkSprint.walk(u, 0F, true, 1F), k));
        matches("walk long", WALK_R, (u, k) -> signal(WalkSprint.walk(u, 0F, false, 1F), k));
    }

    @Test
    void sprintIsAsMeasured() {
        matches("sprint pistol", SPRINT_P, (u, k) -> signal(WalkSprint.sprint(u, 0F, true, 1F), k));
        matches("sprint long", SPRINT_R, (u, k) -> signal(WalkSprint.sprint(u, 0F, false, 1F), k));
    }

    // the run's big turns: the matrix of our three angles is the matrix of the measured rotation vector (at every
    // phase), which taking the vector's parts as the angles would miss by degrees
    @Test
    void sprintTurnIsTheMeasuredMatrix() {
        for (boolean pistol : new boolean[] {true, false}) {
            float[][][] m = pistol ? SPRINT_P : SPRINT_R;
            for (int i = 0; i < 50; i++) {
                float u = i / 50F;
                float[] r = new float[3];
                for (int k = 0; k < 3; k++) {
                    float[] a = m[k][0], b = m[k][1];
                    // the middle of the two guns, which the curve takes
                    for (float[] g : new float[][] {a, b})
                        r[k] += 0.5F * (g[0] + g[1] * (float) Math.cos(2 * Math.PI * (u - g[2])) + g[3] * (float) Math.cos(4 * Math.PI * (u - g[4])));
                }
                WalkSprint.Pose p = WalkSprint.sprint(u, 0F, pistol, 1F);
                Matrix3f want = measured(r[0], r[1], r[2]), got = ours(p);
                assertTrue(want.equals(got, 2e-3F), "pistol " + pistol + " u " + u + ": " + got + " != " + want);
            }
            WalkSprint.Pose mean = WalkSprint.fromVector(m[0][0][0], m[1][0][0], m[2][0][0], 0F, 0F, 0F);
            Matrix3f want = measured(m[0][0][0], m[1][0][0], m[2][0][0]);
            assertTrue(want.equals(ours(mean), 1e-4F));
            WalkSprint.Pose naive = new WalkSprint.Pose(m[0][0][0], m[1][0][0], m[2][0][0], 0F, 0F, 0F);
            assertTrue(!want.equals(ours(naive), 0.03F), "the parts as the angles differ by degrees");
        }
    }

    // walking while aimed hardly moves the gun: the measured yaw ±0.13°, the rest under 0.1°
    @Test
    void walkWhileAimedIsAlmostStill() {
        for (boolean pistol : new boolean[] {true, false}) {
            for (int k = 0; k < 6; k++) {
                int kk = k;
                double[] aimed = harmonics(u -> signal(WalkSprint.walk((float) u, 1F, pistol, 1F), kk));
                double[] hip = harmonics(u -> signal(WalkSprint.walk((float) u, 0F, pistol, 1F), kk));
                double limit = k < 3 ? (k == 1 ? 0.15 : 0.1) : 0.003;
                assertTrue(aimed[1] <= limit && aimed[3] <= limit, "pistol " + pistol + " " + kk + ": " + aimed[1] + ", " + aimed[3]);
                assertTrue(Math.abs(aimed[0]) <= (k < 3 ? 0.1 : 0.008), "pistol " + pistol + " mean " + kk + ": " + aimed[0]);
                if (k == 1) assertTrue(hip[1] > 10 * aimed[1]);
            }
        }
    }

    // flying with the sprint held: the measured flying pose, without the swing
    @Test
    void flyingIsTheStillSprintPose() {
        WalkSprint.Pose a = WalkSprint.sprint(0.1F, 1F, true, 1F), b = WalkSprint.sprint(0.6F, 1F, true, 1F);
        assertEquals(a.pitch(), b.pitch(), 1e-3F);
        assertEquals(a.yaw(), b.yaw(), 1e-3F);
        assertEquals(a.roll(), b.roll(), 1e-3F);
        assertEquals(a.y(), b.y(), 1e-5F);
        Vector3f r = vector(a);
        assertEquals(30.8F, r.x, 0.05F);
        assertEquals(27.7F, r.y, 0.05F);
        assertEquals(15.95F, r.z, 0.1F);
        assertEquals(-0.0365F, a.y(), 0.001F);
    }

    /** Seconds from the key until the eased weight is 10, 50 and 90 % in (on) or out, the state a half tick late. */
    static float[] times(boolean on, float in, float out) {
        return times(on, in, out, 0F);
    }

    /** The same with the weight held back by late seconds more on the way in. */
    static float[] times(boolean on, float in, float out, float late) {
        float dt = 1F / 30F, delay = 0.025F + (on ? late : 0F), w = on ? 0F : 1F, last = WalkSprint.ease(w);
        float[] at = new float[3], level = on ? new float[] {0.1F, 0.5F, 0.9F} : new float[] {0.9F, 0.5F, 0.1F};
        int next = 0;
        for (float t = 0F; next < 3 && t < 2F; t += dt) {
            float s = t + dt;
            float step = Math.max(0F, s - Math.max(t, delay));
            w = WalkSprint.approach(w, on, step, in, out);
            float e = WalkSprint.ease(w);
            while (next < 3 && (on ? e >= level[next] : e <= level[next])) {
                at[next] = t + dt * (level[next] - last) / (e - last);
                next++;
            }
            last = e;
        }
        return at;
    }

    static void times(String what, float[] got, float... want) {
        for (int i = 0; i < 3; i++) assertEquals(want[i], got[i], 0.05F, what + " " + (i == 0 ? 10 : i == 1 ? 50 : 90) + " %");
    }

    // in and out by the measured 10 / 50 / 90 % times, the mean over the signals that do not swing too much (the
    // measurements give them ±0.05 s)
    @Test
    void weightsComeAndGoAsMeasured() {
        times("walk in", times(true, WalkSprint.WALK_IN, WalkSprint.WALK_OUT), 0.05F, 0.10F, 0.175F);
        times("walk out", times(false, WalkSprint.WALK_IN, WalkSprint.WALK_OUT), 0.094F, 0.147F, 0.245F);
        times("sprint in", times(true, WalkSprint.SPRINT_IN, WalkSprint.SPRINT_OUT, WalkSprint.SPRINT_DELAY), 0.159F, 0.26F, 0.40F);
        times("sprint out", times(false, WalkSprint.SPRINT_IN, WalkSprint.SPRINT_OUT), 0.06F, 0.145F, 0.245F);
    }

    /**
     * The jump's sway as tacz.JumpingSway makes it (-2 by the progress, which falls to 0 over the sway's time, read by
     * the frame at 30 fps) through tacz.SecondOrderDynamics (a step of 0.05 every 6 ms on its thread): the peak.
     */
    static float swayPeak(float swayTime) {
        double f = 0.28, z = 1, r = 0.65;
        double k1 = z / (Math.PI * f), k2 = 1 / ((2 * Math.PI * f) * (2 * Math.PI * f)), k3 = r * z / (2 * Math.PI * f);
        double py = 0, px = 0, pyd = 0, target = -2, progress = 1, peak = 0;
        double frame = 1.0 / 30, step = 0.0061, nextFrame = 0, nextStep = 0;
        while (nextFrame < 1.0) {
            if (nextFrame <= nextStep) {
                if (nextFrame > 0) progress = Math.max(0, progress - frame / swayTime);
                target = -2 * progress;
                peak = Math.min(peak, py + 0.05 * pyd);
                nextFrame += frame;
            } else {
                double t = 0.05, xd = (target - px) / t, y = py + t * pyd;
                pyd = pyd + t * (px + k3 * xd - py - k1 * pyd) / k2;
                px = target;
                py = y;
                nextStep += step;
            }
        }
        return (float) peak;
    }

    // the gun's middle dips at a take-off and a landing as measured (in L, the middle of each class)
    @Test
    void jumpDipsAsMeasured() {
        float takeOff = swayPeak(0.3F), landing = swayPeak(0.15F);
        assertEquals(-0.106F, takeOff * WalkSprint.JUMP_PISTOL, 0.006F);
        assertEquals(-0.085F, landing * WalkSprint.JUMP_PISTOL, 0.006F);
        assertEquals(-0.032F, takeOff * WalkSprint.JUMP_LONG, 0.002F);
        assertEquals(-0.025F, landing * WalkSprint.JUMP_LONG, 0.002F);
    }

    // coming in, every part of the run's turn goes in at the pace of the weight (the vector is scaled, not our angles):
    // the measured long gun's pitch comes in with its yaw
    @Test
    void sprintWeightScalesTheMeasuredVector() {
        for (boolean pistol : new boolean[] {true, false}) {
            Vector3f full = vector(WalkSprint.sprint(0.3F, 0F, pistol, 1F));
            for (float w : new float[] {0.1F, 0.5F, 0.9F}) {
                Vector3f part = vector(WalkSprint.sprint(0.3F, 0F, pistol, w));
                for (int k = 0; k < 3; k++) assertEquals(w * full.get(k), part.get(k), 0.02F, "pistol " + pistol + " w " + w + " " + k);
                assertEquals(w * WalkSprint.sprint(0.3F, 0F, pistol, 1F).y(), WalkSprint.sprint(0.3F, 0F, pistol, w).y(), 1e-5F);
            }
        }
    }

    @Test
    void phaseCountsTwoStepsACycle() {
        assertEquals(0F, WalkSprint.phase(0F), 1e-6F);
        assertEquals(0.25F, WalkSprint.phase(0.5F), 1e-6F);
        assertEquals(0.75F, WalkSprint.phase(3.5F), 1e-6F);
    }

    @Test
    void longGunsAreMeasured() {
        // as the stand measured them (length, middle): a G3, the Panzerschreck, the Stinger; were left out over 10 and 5
        assertTrue(WalkSprint.measurable(9.375F, new Vector3f(0.59F, -0.87F, -2.5F)));
        assertTrue(WalkSprint.measurable(16.72F, new Vector3f(-1.32F, -2.9F, 2.6F)));
        assertTrue(WalkSprint.measurable(30.42F, new Vector3f(-1.8F, -9.5F, -1.2F)));
        // a broken matrix is not
        assertFalse(WalkSprint.measurable(Float.NaN, new Vector3f()));
        assertFalse(WalkSprint.measurable(0F, new Vector3f()));
        assertFalse(WalkSprint.measurable(5F, new Vector3f(Float.POSITIVE_INFINITY, 0F, 0F)));
        assertFalse(WalkSprint.measurable(1e4F, new Vector3f()));
        assertFalse(WalkSprint.measurable(5F, new Vector3f(0F, 0F, 80F)));
    }
}
