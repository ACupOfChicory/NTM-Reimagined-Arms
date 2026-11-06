package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class ArmAimScaleTest {
    private static final String GUN = "hbm:gun_greasegun";

    private static Arms.ArmRest rest(boolean right, float scale) {
        var rest = new Arms.ArmRest();
        float[] dir = right ? Config.armDirRight : Config.armDirLeft;
        Arms.restArm(rest, right, false, new Vector3f(0.2F, -0.3F, 0.8F), new Vector3f(0F, -1F, 0F),
                new Vector3f(0.2F, -0.3F, 0.8F), new Vector3f(), dir,
                right ? Config.armReachRight : Config.armReachLeft, scale);
        return rest;
    }

    @Test
    void aimThicknessBlendsIndependentlyAndKeepsTheHipValueWhenAimIsUnset() {
        Map<String, String> opened = Grips.snapshot();
        try {
            Grips.put(GUN, "armScaleRight", "0.8", false);
            Grips.put(GUN, "armScaleLeft", "1.2", false);
            Grips.put(GUN, "armAimScaleRight", "0", false);
            Grips.put(GUN, "armAimScaleLeft", "0", false);
            Config.select(GUN);
            for (float w : new float[] {0F, 0.5F, 1F}) {
                assertEquals(Config.armScaleRight, Arms.holdScale(true, w), 0F);
                assertEquals(Config.armScaleLeft, Arms.holdScale(false, w), 0F);
            }

            Grips.put(GUN, "armAimScaleRight", "1.8", false);
            Grips.put(GUN, "armAimScaleLeft", "0.4", false);
            Config.select(GUN);
            assertEquals(0.8F, Arms.holdScale(true, 0F), 0F);
            assertEquals(1.8F, Arms.holdScale(true, 1F), 0F);
            assertEquals(1.3F, Arms.holdScale(true, 0.5F), 1e-6F);
            assertEquals(1.2F, Arms.holdScale(false, 0F), 0F);
            assertEquals(0.4F, Arms.holdScale(false, 1F), 0F);
            assertEquals(0.8F, Arms.holdScale(false, 0.5F), 1e-6F);
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    @Test
    void zeroAimScalePreservesTheOldRestPoseBitForBit() {
        Map<String, String> opened = Grips.snapshot();
        try {
            Grips.put(GUN, "armBaseRight", "1.7", false);
            Grips.put(GUN, "armScale", "1.1", false);
            Grips.put(GUN, "armScaleRight", "1.25", false);
            Grips.put(GUN, "armAimScaleRight", "0", false);
            Config.select(GUN);
            for (float w : new float[] {0F, 0.5F, 1F}) {
                var oldPose = rest(true, Config.armScaleRight);
                var newPose = rest(true, Arms.holdScale(true, w));
                assertEquals(oldPose.s, newPose.s, 0F);
                assertEquals(oldPose.length, newPose.length, 0F);
                assertEquals(oldPose.crop, newPose.crop);
                assertEquals(oldPose.hand, newPose.hand);
                assertEquals(oldPose.shoulder, newPose.shoulder);
                float[] oldMatrix = new float[16], newMatrix = new float[16];
                oldPose.local.get(oldMatrix);
                newPose.local.get(newMatrix);
                assertArrayEquals(oldMatrix, newMatrix, 0F);
            }
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    @Test
    void fixedBaseArmDoublesInThicknessAtTheSightWhenAimScaleDoublesHipScale() {
        Map<String, String> opened = Grips.snapshot();
        try {
            Grips.put(GUN, "armBaseRight", "1.7", false);
            Grips.put(GUN, "armScaleRight", "0.75", false);
            Grips.put(GUN, "armAimScaleRight", "1.5", false);
            Config.select(GUN);
            float hip = rest(true, Arms.holdScale(true, 0F)).s;
            float aimed = rest(true, Arms.holdScale(true, 1F)).s;
            assertEquals(hip * 2F, aimed, 0F);
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    @Test
    void aimThicknessSettingsAreStoredAndReadPerGunThroughGrips() {
        Map<String, String> opened = Grips.snapshot();
        try {
            Grips.put(GUN, "armAimScaleRight", "1.75", false);
            Grips.put(GUN, "armAimScaleLeft", "0.65", false);
            assertEquals("1.75", Grips.own(GUN, "armAimScaleRight"));
            assertEquals("0.65", Grips.own(GUN, "armAimScaleLeft"));
            Config.select(GUN);
            assertEquals(1.75F, Config.armAimScaleRight, 0F);
            assertEquals(0.65F, Config.armAimScaleLeft, 0F);
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }
}
