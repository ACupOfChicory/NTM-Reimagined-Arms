package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

// The server's lean (leanPoint: the eye, the muzzle) and the client's (the model's parts turned in setupAnim) put a
// point of the body in the same place, for any turn of the body, lean and stance.
class AiPoseGeometryTest {
    static AiPose pose(int prev, int lean, int tick) {
        return new AiPose(AiPose.STAND, (byte) lean, (byte) prev, tick, AiPose.HOLD, AiPose.NONE, 0, (short) 0);
    }

    @Test
    void leanGoesFromPreviousToTargetInFourTicks() {
        AiPose s = pose(0, 100, 10);
        assertEquals(0F, AiPoseGeometry.leanFrac(s, 10, 0F), 1e-6);
        assertEquals(0.5F, AiPoseGeometry.leanFrac(s, 12, 0F), 1e-6);
        assertEquals(0.625F, AiPoseGeometry.leanFrac(s, 12, 0.5F), 1e-6);
        assertEquals(1F, AiPoseGeometry.leanFrac(s, 14, 0F), 1e-6);
        assertEquals(1F, AiPoseGeometry.leanFrac(s, 400, 0F), 1e-6);
        // a change halfway: from where it was, not from the old target
        AiPose back = pose(50, -100, 20);
        assertEquals(0.5F, AiPoseGeometry.leanFrac(back, 20, 0F), 1e-6);
        assertEquals(-1F, AiPoseGeometry.leanFrac(back, 24, 0F), 1e-6);
    }

    @Test
    void noLeanLeavesThePointWhereItIs() {
        Vec3 e = AiPoseGeometry.eyeOffset(AiPose.STAND, 0F, 37F, 1.74);
        assertEquals(0, e.x, 1e-9);
        assertEquals(1.74, e.y, 1e-9);
        assertEquals(0, e.z, 1e-9);
    }

    @Test
    void rightIsTheMobsRight() {
        // facing south (+Z, yaw 0) the mob's right is west (−X)
        Vec3 e = AiPoseGeometry.eyeOffset(AiPose.STAND, 1F, 0F, 1.74);
        assertTrue(e.x < -0.6, "eye out to the right: " + e);
        assertTrue(e.y < 1.74 && e.y > 1.5, "a little lower: " + e);
        assertEquals(0, e.z, 1e-9);
        // eye out by the step plus the tip of 0.99 blocks above the hips by 25°
        assertEquals(-(0.25 + Math.sin(Math.toRadians(25)) * (1.74 - 0.751)), e.x, 1e-6);
    }

    @Test
    void legsOnlyStepAside() {
        Vec3 knee = AiPoseGeometry.leanPoint(0.1, 0.4, 0.0, AiPose.STAND, -1F, 90F);
        Vec3 still = AiPoseGeometry.leanPoint(0.1 - 0.25, 0.4, 0.0, AiPose.STAND, 0F, 90F);
        assertEquals(still.x, knee.x, 1e-9);
        assertEquals(still.y, knee.y, 1e-9);
        assertEquals(still.z, knee.z, 1e-9);
    }

    /** a model point (pixels: x the model's left, y down, z back) as LivingEntityRenderer puts it in the world */
    static Vector3f render(Vector3f modelPx, float yBodyRot) {
        Matrix4f m = new Matrix4f().rotateY((float) Math.toRadians(180F - yBodyRot)).scale(-1F, -1F, 1F)
                .translate(0F, -1.501F, 0F);
        return m.transformPosition(new Vector3f(modelPx).div(16F));
    }

    @Test
    void serverAndModelAgree() {
        float[] yaws = { 0F, 37F, 90F, -135F, 180F };
        float[] fracs = { -1F, -0.4F, 0.3F, 1F };
        byte[] stances = { AiPose.STAND, AiPose.CROUCH };
        Vector3f[] points = { new Vector3f(0, -4, 0), new Vector3f(-5, 2, 0), new Vector3f(5, 10, -2),
                new Vector3f(0, 6, 1), new Vector3f(2, 16, 0) };
        for (float yaw : yaws)
            for (float frac : fracs)
                for (byte stance : stances)
                    for (Vector3f p : points) {
                        // the client: shift along model X, then turn the parts above the hips around model Z
                        float hipY = AiPoseGeometry.modelHipY(stance), hipZ = AiPoseGeometry.modelHipZ(stance);
                        Vector3f q = new Vector3f(p);
                        if (q.y < hipY) {
                            new Matrix4f().translate(0, hipY, hipZ).rotateZ(AiPoseGeometry.modelRollRad(frac))
                                    .translate(0, -hipY, -hipZ).transformPosition(q);
                        }
                        q.x += AiPoseGeometry.modelShiftPx(frac);
                        Vector3f world = render(q, yaw);
                        // the server: the same point of the upright body in the mob's frame
                        Vector3f upright = render(p, 0F);
                        // at yaw 0: right = −X, forward = +Z
                        Vec3 s = AiPoseGeometry.leanPoint(-upright.x, upright.y, upright.z, stance, frac, yaw);
                        String at = "yaw " + yaw + " frac " + frac + " stance " + stance + " p " + p;
                        assertEquals(world.x, s.x, 1e-4, at);
                        assertEquals(world.y, s.y, 1e-4, at);
                        assertEquals(world.z, s.z, 1e-4, at);
                    }
    }
}
