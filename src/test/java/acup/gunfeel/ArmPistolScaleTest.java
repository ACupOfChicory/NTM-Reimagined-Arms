package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void gripDistanceSetsEqualVisibleThicknessAndBlendsAtHalfWeight() {
        ConfigState old = ConfigState.capture();
        try {
            Config.armScale = 0.8F;
            Config.armBaseRight = 1.70F;
            Config.armBaseLeft = 3.02F;
            Config.armLength = 0F;
            Vector3f[] points = {
                    new Vector3f(0F, -0.6F, 0.8F), new Vector3f(0F, -1.023F, 1.364F),
                    new Vector3f(0F, -2.1F, 2.8F), new Vector3f(0F, -3.6F, 4.8F)};
            float ratio = -1F;
            for (Vector3f grip : points) {
                Arms.ArmRest r = rest(true, grip, 1F, true, 1F);
                Arms.ArmRest l = rest(false, grip, 1F, true, 1F);
                float now = r.s / grip.length();
                if (ratio < 0F) ratio = now;
                assertEquals(ratio, now, 1e-5F);
                assertEquals(now, l.s / grip.length(), 1e-5F);
                assertEquals(0.8F * 1.70F / 1.58F, now, 1e-5F);
            }
            Vector3f grip = points[2];
            float oldThickness = 1.70F;
            float expected = (oldThickness + Arms.pistolThickness(oldThickness, grip, 1F)) / 2F * Config.armScale;
            assertEquals(expected, rest(true, grip, 1F, true, 0.5F).s, 1e-6F);
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
