package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class ArmPistolScaleTest {
    private record ConfigState(float baseRight, float baseLeft, float scale, float scaleRight, float scaleLeft,
            float length, float max, float pistolHold, boolean enabled) {
        static ConfigState capture() {
            return new ConfigState(Config.armBaseRight, Config.armBaseLeft, Config.armScale, Config.armScaleRight,
                    Config.armScaleLeft, Config.armLength, Config.armMaxScale, Config.pistolHold, Config.armPistolScale);
        }

        void restore() {
            Config.armBaseRight = baseRight;
            Config.armBaseLeft = baseLeft;
            Config.armScale = scale;
            Config.armScaleRight = scaleRight;
            Config.armScaleLeft = scaleLeft;
            Config.armLength = length;
            Config.armMaxScale = max;
            Config.pistolHold = pistolHold;
            Config.armPistolScale = enabled;
        }
    }

    private static Arms.ArmRest rest(boolean right, Vector3f grip, float holdScale, boolean rule, float wp) {
        var result = new Arms.ArmRest();
        Arms.restArm(result, right, false, grip, new Vector3f(0F, -1F, 0F), grip, new Vector3f(),
                right ? Config.armDirRight : Config.armDirLeft, right ? Config.armReachRight : Config.armReachLeft,
                holdScale, rule, wp);
        return result;
    }

    private static void sameBits(Arms.ArmRest a, Arms.ArmRest b) {
        assertEquals(a.valid, b.valid);
        assertEquals(Float.floatToIntBits(a.s), Float.floatToIntBits(b.s));
        assertEquals(Float.floatToIntBits(a.length), Float.floatToIntBits(b.length));
        assertEquals(a.crop, b.crop);
        float[] ah = {a.hand.x, a.hand.y, a.hand.z}, bh = {b.hand.x, b.hand.y, b.hand.z};
        float[] as = {a.shoulder.x, a.shoulder.y, a.shoulder.z}, bs = {b.shoulder.x, b.shoulder.y, b.shoulder.z};
        for (int i = 0; i < 3; i++) {
            assertEquals(Float.floatToIntBits(ah[i]), Float.floatToIntBits(bh[i]), "hand[" + i + "]");
            assertEquals(Float.floatToIntBits(as[i]), Float.floatToIntBits(bs[i]), "shoulder[" + i + "]");
        }
        float[] am = new float[16], bm = new float[16];
        a.local.get(am);
        b.local.get(bm);
        for (int i = 0; i < am.length; i++) assertEquals(Float.floatToIntBits(am[i]), Float.floatToIntBits(bm[i]), "local[" + i + "]");
    }

    @Test
    void disabledAndZeroWeightKeepTheOldPoseBitForBit() {
        ConfigState old = ConfigState.capture();
        try {
            Config.armScale = 0.8F;
            Config.armLength = 1F;
            for (boolean right : new boolean[] {true, false}) for (float base : new float[] {0F, right ? 1.70F : 3.02F}) {
                if (right) Config.armBaseRight = base;
                else Config.armBaseLeft = base;
                Vector3f grip = new Vector3f(-0.2F, -1.7F, 2.1F);
                var before = rest(right, grip, 0.9F, false, 1F);
                Config.armPistolScale = false;
                sameBits(before, rest(right, grip, 0.9F, Config.armPistolScale, 1F));
                Config.armPistolScale = true;
                sameBits(before, rest(right, grip, 0.9F, true, 0F));
            }
        } finally {
            old.restore();
        }
    }

    // the hands of the seven pistols of the 26.2 arms trace (the hand's end, camera frame), right then left: from the
    // hip and aimed, the star_f also with a grip of its own (the right hand nearer and higher)
    private static final float[][] HANDS = {
            {-0.130F, -1.848F, 1.908F, -0.333F, -2.226F, 2.172F}, {0.179F, -1.486F, 3.194F, -0.091F, -1.820F, 3.443F},
            {-0.147F, -1.812F, 1.878F, -0.341F, -2.027F, 1.999F}, {0.118F, -1.023F, 2.080F, -0.059F, -1.245F, 2.206F},
            {-0.149F, -1.754F, 2.107F, -0.351F, -1.828F, 2.154F}, {0.140F, -0.937F, 2.559F, -0.066F, -1.005F, 2.606F},
            {-0.061F, -2.048F, 2.374F, -0.306F, -2.656F, 2.840F}, {0.272F, -2.018F, 4.913F, -0.138F, -2.522F, 5.341F},
            {-0.096F, -1.078F, 1.233F, -0.220F, -1.239F, 1.335F}, {0.103F, -0.836F, 1.845F, -0.051F, -0.977F, 1.941F},
            {-0.085F, -1.272F, 1.134F, -0.211F, -1.393F, 1.248F}, {0.102F, -0.902F, 1.793F, -0.050F, -1.007F, 1.901F},
            {-0.168F, -1.369F, 2.021F, -0.333F, -2.226F, 2.172F}, {0.148F, -1.024F, 3.305F, -0.091F, -1.820F, 3.443F}};

    private static Arms.ArmRest rest(boolean right, boolean slim, Vector3f grip, float wp) {
        var result = new Arms.ArmRest();
        Arms.restArm(result, right, slim, grip, new Vector3f(0F, -1F, 0F), grip, new Vector3f(),
                right ? Config.armDirRight : Config.armDirLeft, right ? Config.armReachRight : Config.armReachLeft,
                1F, true, wp);
        return result;
    }

    // the angle (deg) the drawn arm's slice 2 px back from its end (the middle of what is drawn when less is) takes
    // across the arm as the eye sees it: the slice's corners projected on the plane square to the line of sight,
    // measured across the arm's projected axis
    private static double silhouette(Arms.ArmRest rest, boolean right, boolean slim) {
        Matrix4f m = rest.local;
        float cx = (right ? -1F : 1F) * (slim ? 5.5F : 6F) / 16F, hx = (slim ? 1.5F : 2F) / 16F;
        float y = (12F - Math.min(2F, rest.crop / 2F)) / 16F;
        Vector3f c = m.transformPosition(cx, y, 0F, new Vector3f());
        Vector3f d = new Vector3f(c).normalize();
        Vector3f e1 = new Vector3f(d).cross(0F, 1F, 0F).normalize();
        Vector3f e2 = new Vector3f(e1).cross(d);
        java.util.function.Function<Vector3f, double[]> g = q -> {
            double k = q.dot(d);
            return new double[] {q.dot(e1) / k, q.dot(e2) / k};
        };
        double[] p0 = g.apply(c), p1 = g.apply(m.transformPosition(cx, y + 0.5F / 16F, 0F, new Vector3f()));
        double ax = p1[0] - p0[0], ay = p1[1] - p0[1], l = Math.hypot(ax, ay), nx = -ay / l, ny = ax / l;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int a = -1; a <= 1; a += 2) for (int b = -1; b <= 1; b += 2) {
            double[] q = g.apply(m.transformPosition(cx + a * hx, y, b * 2F / 16F, new Vector3f()));
            double w = nx * q[0] + ny * q[1];
            lo = Math.min(lo, w);
            hi = Math.max(hi, w);
        }
        return 2 * Math.toDegrees(Math.atan((hi - lo) / 2));
    }

    @Test
    void bothHandsOfEveryPistolShowTheSameWidthFromTheHipAndAimed() {
        ConfigState old = ConfigState.capture();
        try {
            // armLength 0.1: only the last pixel of the arm is drawn, and that is what is measured
            for (float length : new float[] {1F, 0.1F}) for (boolean slim : new boolean[] {false, true}) {
                Config.armLength = length;
                double lo = Double.MAX_VALUE, hi = 0;
                for (float[] h : HANDS) {
                    Arms.ArmRest r = rest(true, slim, new Vector3f(h[0], h[1], h[2]), 1F);
                    Arms.ArmRest l = rest(false, slim, new Vector3f(h[3], h[4], h[5]), 1F);
                    if (length < 1F) assertEquals(1, r.crop);
                    double sr = silhouette(r, true, slim), sl = silhouette(l, false, slim);
                    assertEquals(1.0, sr / sl, 0.015, "R/L " + java.util.Arrays.toString(h) + " slim " + slim + " length " + length);
                    lo = Math.min(lo, Math.min(sr, sl));
                    hi = Math.max(hi, Math.max(sr, sl));
                }
                // the same at every depth, near the X472 mean (16.2 deg for the wide arm, 14.2 for the slim one)
                assertTrue(hi / lo < 1.02, "spread " + lo + ".." + hi + " slim " + slim);
                assertEquals(slim ? 14.2 : 16.2, (lo + hi) / 2, 0.4, "mean width, slim " + slim);
            }
        } finally {
            old.restore();
        }
    }

    @Test
    void theWidthIsTheGoalAndHalfWeightIsHalfWay() {
        ConfigState old = ConfigState.capture();
        try {
            for (float[] h : HANDS) for (int i = 0; i < 2; i++) for (boolean slim : new boolean[] {false, true}) {
                boolean right = i == 0;
                Vector3f grip = new Vector3f(h[3 * i], h[3 * i + 1], h[3 * i + 2]);
                Arms.ArmRest full = rest(right, slim, grip, 1F);
                // the goal: a box 1.12 x its depth thick (x armScale) seen as wide as its mean over its roll
                double goal = 1.12 * 4 / Math.PI * ((slim ? 1.5 : 2) + 2) / 16 * Config.armScale;
                assertEquals(goal, 2 * Math.tan(Math.toRadians(silhouette(full, right, slim)) / 2), 2e-4 * goal, "width");
                float none = rest(right, slim, grip, 0F).s, half = rest(right, slim, grip, 0.5F).s;
                assertEquals((none + full.s) / 2F, half, 1e-5F * full.s, "half weight");
            }
        } finally {
            old.restore();
        }
    }

    @Test
    void anArmThatCanNotReachTheWidthStopsAtTwiceTheDepthRule() {
        ConfigState old = ConfigState.capture();
        try {
            // a hand at the eye's height a block ahead: still the same width
            Arms.ArmRest near = rest(true, false, new Vector3f(0.2F, 0F, 1F), 1F);
            assertEquals(16.23, silhouette(near, true, false), 0.05);
            // thickness x5 and arms twice as long: the slice runs away faster than the arm widens
            Config.armScale = 5F;
            Config.armLength = 2F;
            Vector3f grip = new Vector3f(-0.333F, -2.226F, 2.172F);
            Arms.ArmRest far = rest(false, false, grip, 1F);
            assertTrue(Float.isFinite(far.s));
            assertEquals(2F * 1.12F * grip.length() * Config.armScale, far.s, 1e-4F * far.s);
        } finally {
            old.restore();
        }
    }

    @Test
    void newGunFrameClearsThePistolPlacementFlag() {
        Arms.pistolHoldFrame(true, true);
        assertTrue(Arms.pistolHoldFrame());
        Arms.resetPistolHoldFrame();
        assertFalse(Arms.pistolHoldFrame());
    }

    @Test
    void aLaterAimingCallInTheFrameKeepsWhatThePlacingCallSet() {
        // the heavy revolver: NTM's placing call in setupFirstPerson, then its kick in renderFirstPerson
        Arms.resetPistolHoldFrame();
        Arms.pistolHoldFrame(true, true);
        Arms.pistolHoldFrame(false, false);
        assertTrue(Arms.pistolHoldFrame());
        // a rifle's placing call clears it
        Arms.pistolHoldFrame(true, false);
        assertFalse(Arms.pistolHoldFrame());
        Arms.pistolHoldFrame(false, true);
        assertFalse(Arms.pistolHoldFrame());
    }
}
