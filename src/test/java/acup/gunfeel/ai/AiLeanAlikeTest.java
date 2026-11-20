package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import acup.gunfeel.Vecs;
import net.minecraft.world.phys.Vec3;

class AiLeanAlikeTest {
    @Test
    void facadesMatchFormerBitsWithoutComparison() {
        assertNull(AiLean.alike);
        for (byte stance : new byte[] {AiPose.STAND, AiPose.CROUCH, AiPose.PRONE})
            for (float frac : new float[] {-1F, -0.37F, -0F, 0F, 0.29F, 1F})
                for (float yaw : new float[] {-135F, -0F, 0F, 37F, 90F})
                    for (double height : new double[] {0.4, 1.501 - 12.0 / 16.0, 1.27, 1.74}) {
                        assertEquals(Vecs.of(AiPoseGeometryOld.leanPoint(0.29, height, -0.13, stance, frac, yaw)),
                                Vecs.of(AiPoseGeometry.leanPoint(0.29, height, -0.13, stance, frac, yaw)));
                        assertEquals(Vecs.of(AiPoseGeometryOld.eyeOffset(stance, frac, yaw, height)),
                                Vecs.of(AiPoseGeometry.eyeOffset(stance, frac, yaw, height)));
                        assertEquals(Vecs.of(AiPoseGeometryOld.toWorld(0.29, height, -0.13, yaw)),
                                Vecs.of(AiPoseGeometry.toWorld(0.29, height, -0.13, yaw)));
                    }
    }

    @Test
    void facadeChecksEveryPureAnswer() {
        assertNull(AiLean.alike);
        AiLeanAlike alike = new AiLeanAlike();
        AiLean.alike = alike;
        try {
            for (byte stance : new byte[] {AiPose.STAND, AiPose.CROUCH, AiPose.PRONE})
                for (boolean aim : new boolean[] {false, true})
                    for (float frac : new float[] {-1F, -0.37F, -0F, 0F, 0.29F, 1F})
                        for (float yaw : new float[] {-135F, -0F, 0F, 37F, 90F}) {
                            assertEquals(AiLeanOld.boxes(stance, aim, frac, yaw), AiLean.boxes(stance, aim, frac, yaw));
                            AiPose p = new AiPose(stance, (byte) 100, (byte) -37, 10, AiPose.AIM, AiPose.NONE, 0, (short) 0);
                            AiPoseGeometry.leanFrac(p, 12, 0.37F);
                            AiPoseGeometry.leanPoint(0.29, 1.27, -0.13, stance, frac, yaw);
                            AiPoseGeometry.eyeOffset(stance, frac, yaw, 1.74);
                        }
            assertEquals(720, alike.asked);
            assertEquals(0, alike.differ);
        } finally {
            AiLean.alike = null;
        }
    }

    @Test
    void comparisonFindsChangedFieldsAndSignedZero() {
        AiLeanAlike alike = new AiLeanAlike();
        var boxes = new java.util.ArrayList<>(AiLeanOld.boxes(AiPose.STAND, false, 1F, 37F));
        boxes.set(0, boxes.get(0).withMesh(1));
        alike.boxes(AiPose.STAND, false, 1F, 37F, boxes);
        alike.leanFrac(AiPose.IDLE, 0, 0F, -0F);
        Vec3 eye = AiPoseGeometryOld.eyeOffset(AiPose.STAND, 0F, 0F, 1.74);
        alike.eyeOffset(AiPose.STAND, 0F, 0F, 1.74, new Vec3(-eye.x, eye.y, eye.z));
        alike.leanPoint(0, 1.74, 0, AiPose.STAND, 0F, 0F, new Vec3(eye.x, eye.y, -eye.z));
        assertEquals(4, alike.asked);
        assertEquals(4, alike.differ);
    }
}
