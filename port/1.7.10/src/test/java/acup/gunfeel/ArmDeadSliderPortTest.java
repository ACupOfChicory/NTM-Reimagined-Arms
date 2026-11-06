package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

// port: 1.7.10 has no arm editor, so ArmDeadSliderTest (the editor's grey sliders) does not run here. This keeps
// the part of it that is the arm's own logic: a setting the editor greys out leaves the pose as it is, and one it
// leaves active moves it.
class ArmDeadSliderPortTest {
    private static float[] get(Matrix4f m) {
        float[] a = new float[16];
        m.get(a);
        return a;
    }

    @Test
    void disabledMagazineTimingHasNoEffect() {
        for (int n = 0; n < 9; n++) assertFalse(MagazineInspect.enabled(-5000F + 4999F * n / 8F));
        for (int n = 0; n < 9; n++) assertTrue(MagazineInspect.enabled(5000F * n / 8F));
        assertNotEquals(MagazineInspect.handTime(700, 0F), MagazineInspect.handTime(700, 5000F));
        assertNotEquals(MagazineInspect.handWeight(1600, 0F), MagazineInspect.handWeight(1600, 5000F));
    }

    @Test
    void releaseMoveIsDeadWithoutABusAndMovesTheArmWithOne() {
        float[] old = Config.armReleaseMove.clone();
        try {
            var rest = new Arms.ArmRest();
            rest.s = 1F;
            rest.local.identity();
            assertEquals(0F, Arms.release("", new double[] {0D, 0D, 180D}), 0F);
            assertEquals(0F, Arms.release("ROLL", null), 0F);
            float progress = Arms.release("ROLL", new double[] {0D, 0D, 180D});
            assertEquals(0.5F, progress, 1e-6F);
            for (int c = 0; c < 3; c++) {
                float[] first = null;
                boolean changed = false;
                for (int n = 0; n < 9; n++) {
                    Config.armReleaseMove[c] = -4F + 8F * n / 8F;
                    float[] now = get(Arms.releasePose(rest, false, progress));
                    if (first == null) first = now;
                    else for (int i = 0; i < 16; i++) changed |= Math.abs(first[i] - now[i]) > 1e-6F;
                }
                assertTrue(changed, "release axis " + c + " moves the arm");
                Config.armReleaseMove[c] = old[c];
            }
        } finally {
            System.arraycopy(old, 0, Config.armReleaseMove, 0, old.length);
        }
    }

    @Test
    void maxThicknessIsDeadUnderAPistolsFullDepthWeight() {
        float base = Config.armBaseRight, max = Config.armMaxScale, length = Config.armLength;
        try {
            Config.armBaseRight = 0F;
            Config.armLength = 0F;
            Vector3f grip = new Vector3f(-0.28F, -0.6F, 1.2F);
            for (float wp : new float[] {1F, 0.5F}) {
                float first = Float.NaN;
                boolean changed = false;
                for (int n = 0; n < 9; n++) {
                    Config.armMaxScale = 0.5F + 3F * n / 8F;
                    var r = new Arms.ArmRest();
                    Arms.restArm(r, true, false, grip, new Vector3f(0F, -1F, 0F), grip, new Vector3f(), Config.armDirRight,
                            Config.armReachRight, 1F, true, wp);
                    if (n == 0) first = r.s;
                    else changed |= Math.abs(first - r.s) > 1e-6F;
                }
                assertEquals(wp < 1F, changed, "pistol depth weight " + wp);
            }
        } finally {
            Config.armBaseRight = base;
            Config.armMaxScale = max;
            Config.armLength = length;
        }
    }
}
