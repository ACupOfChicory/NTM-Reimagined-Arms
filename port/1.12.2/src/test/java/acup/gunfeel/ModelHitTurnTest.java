package acup.gunfeel;

// port: copy of 26.2 src/test/java/acup/gunfeel/ModelHitTurnTest.java at 028a2e2, before X473. 26.2's cases of
// ModelHits.Turn (X473: the worm, the plastic bag, the FBI drone and the UFO turned as NTM's renderer turns them) are
// not the port's: NTM CE draws those four with a plain Render, not RenderLivingBase, so the port never takes their pose
// (mobs stand: 0 cubes, "not RenderLivingBase") and there is no kept pose to turn.

import static org.junit.jupiter.api.Assertions.*;
import acup.gunfeel.phys.Vec3; // port: the port's Vec3
import org.junit.jupiter.api.Test;

class ModelHitTurnTest {
    @Test void renderedLimbRemainsAtItsVisiblePositionDuringAbruptTurn() {
        // Реальный случай из modeldebug: раньше клиент терял модель на этом повороте.
        double angle = ModelHits.turnAngle(true, 3.75F, 163.59375F, 3.75F);
        var limb = new ModelBox("arm", .6, 1, -.1, .2, 0, 0, 0, .5, 0, 0, 0, .2);
        var from = new Vec3(.7, 1.25, -2).yRot((float) angle);
        var to = new Vec3(.7, 1.25, 2).yRot((float) angle);
        assertTrue(Math.abs(angle) <= Math.PI / 3, "Нарисованная поза не должна переключаться на общую коробку");
        assertTrue(limb.clip(from.x, from.y, from.z, to.x, to.y, to.z) >= 0,
                "Луч через видимую руку должен попадать в ту же руку");
        assertEquals(0, angle);
    }

    @Test void serverCompensatesFromRenderedYawAcrossWrapBoundary() {
        assertEquals(Math.toRadians(2), ModelHits.turnAngle(false, 1, 359, 0), 1e-9);
        assertEquals(Math.toRadians(-2), ModelHits.turnAngle(false, 359, 1, 0), 1e-9);
        assertEquals(Math.toRadians(45), ModelHits.turnAngle(false, 90, 45, 0), 1e-9);
    }

    @Test void retainedClientPoseFollowsRootWithoutRepeatingInterpolationCorrection() {
        assertEquals(Math.toRadians(20), ModelHits.turnAngle(true, 23.75F, 163.59375F, 3.75F), 1e-9);
        assertEquals(Math.toRadians(2), ModelHits.turnAngle(true, 1, 270, 359), 1e-9);
    }
}
