package acup.gunfeel;

// port: copy of 26.2 src/test/java/acup/gunfeel/ModelHitTurnTest.java at 646faaa (ModelHits.Turn, X478). NTM CE
// draws the chopper, the bag, the worm, the drone and the UFO by a plain Render; the port takes the chopper's and the
// bag's pose (ModelHitClient.beginOther), the others' turns are tested as 26.2's all the same. The port takes a YAW
// or YAW_BACK pose at the drawn yRot and turns it by yRot alone (ModelHits.Turn): one case differs from 26.2's.

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

    // the numbers the stand measured: a pose kept 1.5 s while the server turned the mob by 40 degrees
    @Test void keptPoseTurnsTheWayItsRendererTurnsTheMob() {
        assertEquals(ModelHits.Turn.WORM, ModelHits.turn(com.hbm.entity.mob.botprime.EntityBOTPrimeHead.class));
        assertEquals(ModelHits.Turn.WORM, ModelHits.turn(com.hbm.entity.mob.botprime.EntityBOTPrimeBody.class));
        assertEquals(ModelHits.Turn.YAW_BACK, ModelHits.turn(com.hbm.entity.mob.EntityPlasticBag.class));
        assertEquals(ModelHits.Turn.FIXED, ModelHits.turn(com.hbm.entity.mob.EntityFBIDrone.class));
        assertEquals(ModelHits.Turn.SPIN, ModelHits.turn(com.hbm.entity.mob.EntityUFO.class));
        // the worm's head: body 90 to 130, yRot set back to 0 by its motion; drawn by yRot, it has not turned
        assertEquals(0, ModelHits.turnAngle(ModelHits.Turn.WORM, false, 130, 0, 100, 61.65F, 90, 0, 70), 1e-9);
        // a worm's piece that turned with yRot from 90 to 130: the model turns the other way
        assertEquals(Math.toRadians(-40), ModelHits.turnAngle(ModelHits.Turn.WORM, false, 90, 130, 100, 90, 90, 90, 70), 1e-6);
        assertEquals(Math.toRadians(-40), ModelHits.turnAngle(ModelHits.Turn.WORM, true, 90, 130, 100, 90, 90, 90, 70), 1e-6);
        // the bag in water, its yRot on its body, drawn at 239.45, now at 130: the model turned by +109.45 (the stand: +110)
        assertEquals(Math.toRadians(109.45), ModelHits.turnAngle(ModelHits.Turn.YAW_BACK, false, 130, 130, 100, 239.45F, 90, 90, 70), 1e-5);
        // out of water the body stays: yRot +40, body -30 (the stand): the model turned by -40
        assertEquals(Math.toRadians(-40), ModelHits.turnAngle(ModelHits.Turn.YAW_BACK, false, 60, 130, 100, 90, 90, 90, 70), 1e-6);
        assertEquals(Math.toRadians(-40), ModelHits.turnAngle(ModelHits.Turn.YAW_BACK, true, 60, 130, 100, 90, 90, 90, 70), 1e-6);
        assertEquals(0, ModelHits.turnAngle(ModelHits.Turn.FIXED, false, 130, 130, 100, 239.45F, 90, 90, 70), 1e-9);
        // the UFO spins 5 degrees a tick: 30 ticks later the model is 150 degrees on
        assertEquals(Math.toRadians(-150), ModelHits.turnAngle(ModelHits.Turn.SPIN, false, 130, 130, 100, 90, 90, 90, 70), 1e-6);
        assertEquals(0, ModelHits.turnAngle(ModelHits.Turn.SPIN, true, 130, 130, 142, 90, 90, 90, 70), 1e-9);
    }

    // RenderHunterChopper turns the model by yRot (YP(yRot - 90) under XP(180)); aimAt turns yRot 10 degrees a tick and
    // the body follows yRot only while the chopper moves
    @Test void keptChopperPoseTurnsByItsYRot() {
        assertEquals(ModelHits.Turn.YAW, ModelHits.turn(com.hbm.entity.mob.EntityHunterChopper.class));
        // hanging in place: the body stays at 90, yRot 90 to 130; the model turned by +40, the body rule says 0
        assertEquals(Math.toRadians(40), ModelHits.turnAngle(ModelHits.Turn.YAW, false, 90, 130, 100, 90, 90, 90, 70), 1e-6);
        assertEquals(Math.toRadians(40), ModelHits.turnAngle(ModelHits.Turn.YAW, true, 90, 130, 100, 90, 90, 90, 70), 1e-6);
        // port: the pose is drawn at yRot 90 (its yaw), the server's yRot was 110 when it came in, now 130: on the
        // server by the drawn yRot (+40), on the client by its own yRot then (+20). 26.2 reads the yaw as the client's
        // body and gives +20 on the server
        assertEquals(Math.toRadians(40), ModelHits.turnAngle(ModelHits.Turn.YAW, false, 90, 130, 100, 90, 90, 110, 70), 1e-6);
        assertEquals(Math.toRadians(20), ModelHits.turnAngle(ModelHits.Turn.YAW, true, 90, 130, 100, 90, 90, 110, 70), 1e-6);
        // the client's body 45 off the yRot the pose was drawn at (a mob that stands): the body plays no part
        assertEquals(Math.toRadians(40), ModelHits.turnAngle(ModelHits.Turn.YAW, false, 45, 130, 100, 90, 45, 90, 70), 1e-6);
        assertEquals(Math.toRadians(-40), ModelHits.turnAngle(ModelHits.Turn.YAW_BACK, false, 45, 130, 100, 90, 45, 90, 70), 1e-6);
        // the stand: yRot +40, body -30
        assertEquals(Math.toRadians(40), ModelHits.turnAngle(ModelHits.Turn.YAW, false, 60, 130, 100, 90, 90, 90, 70), 1e-6);
        // flying, yRot on its body: the body rule, drawn at 239.5 on the client
        assertEquals(ModelHits.turnAngle(false, 130, 239.5F, 90),
                ModelHits.turnAngle(ModelHits.Turn.YAW, false, 130, 130, 100, 239.5F, 90, 90, 70), 1e-6);
    }

    // the mobs whose body rule was right before stay on it: vanilla's, glyphids, Mask Man, the crabs, the ghost
    @Test void mobsTurnedByTheirBodyKeepTheBodyRule() {
        for(var type:java.util.List.<Class<?>>of(net.minecraft.entity.EntityLivingBase.class,com.hbm.entity.mob.glyphid.EntityGlyphid.class,
                com.hbm.entity.mob.EntityMaskMan.class,com.hbm.entity.mob.EntityTeslaCrab.class,
                com.hbm.entity.mob.EntityTaintCrab.class,com.hbm.entity.mob.EntityGhost.class,com.hbm.entity.mob.EntityBlockSpider.class,
                com.hbm.entity.mob.EntityCyberCrab.class,com.hbm.entity.mob.EntityDummy.class,com.hbm.entity.mob.EntityPigeon.class))
            assertEquals(ModelHits.Turn.BODY, ModelHits.turn(type), type.getSimpleName());
        assertEquals(ModelHits.turnAngle(false, 130, 239.5F, 90),
                ModelHits.turnAngle(ModelHits.Turn.BODY, false, 130, 130, 100, 239.5F, 90, 90, 70), 1e-12);
        assertEquals(ModelHits.turnAngle(true, 130, 239.5F, 90),
                ModelHits.turnAngle(ModelHits.Turn.BODY, true, 130, 130, 100, 239.5F, 90, 90, 70), 1e-12);
    }
}
