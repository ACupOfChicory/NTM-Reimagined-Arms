package acup.gunfeel.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import acup.gunfeel.ai.AiPose;
import acup.gunfeel.ai.AiPoseGeometry;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.world.phys.Vec3;

// The bot player: the clips load, a leaned model puts its parts where the server puts the eye and the muzzle, and no
// change of stance, arms, action or lean makes a part jump between two frames.
class BotPoserTest {
    static ModelPart part(float x, float y, float z) {
        ModelPart p = new ModelPart(List.of(), Map.of());
        p.setInitialPose(PartPose.offset(x, y, z));
        p.resetPose();
        return p;
    }

    /** BotModel's ten parts at their default pivots */
    static BotPoser.Parts parts() {
        return new BotPoser.Parts(part(0, 0, 0), part(0, 0, 0), part(-5, 2, 0), part(5, 2, 0), part(-1.9F, 12, 0),
                part(1.9F, 12, 0), part(-1, 4, 0), part(1, 4, 0), part(0, 6, 0), part(0, 6, 0));
    }

    static ModelPart[] all(BotPoser.Parts p) {
        return new ModelPart[] { p.head(), p.body(), p.rightArm(), p.leftArm(), p.rightLeg(), p.leftLeg(),
                p.rightForearm(), p.leftForearm(), p.rightShin(), p.leftShin() };
    }

    static BotAnimator.Frame still(float lean) {
        return new BotAnimator.Frame(0F, lean, new float[] { 1F, 0F, 0F, 0F }, null, BotAnimator.NO_ACTS, BotAnimator.NO_HITS, 0F, 0);
    }

    static AiPose pose(byte stance, int lean, int prevLean, int leanTick, byte arms, byte action, int actionTick,
            int actionTicks) {
        return new AiPose(stance, (byte) lean, (byte) prevLean, leanTick, arms, action, actionTick, (short) actionTicks);
    }

    /** a point given in a part's own pixels, where the model puts it (model pixels) */
    static Vector3f inModel(ModelPart p, Vector3f local) {
        return new Matrix4f().translate(p.x, p.y, p.z).rotateZYX(p.zRot, p.yRot, p.xRot).transformPosition(new Vector3f(local));
    }

    /** a model point (pixels) as LivingEntityRenderer puts it in the world (as AiPoseGeometryTest) */
    static Vector3f render(Vector3f modelPx, float yBodyRot) {
        return new Matrix4f().rotateY((float) Math.toRadians(180F - yBodyRot)).scale(-1F, -1F, 1F).translate(0F, -1.501F, 0F)
                .transformPosition(new Vector3f(modelPx).div(16F));
    }

    @Test
    void clipsLoad() {
        for (String n : new String[] { BotClips.IDLE, BotClips.HIT_CHEST, BotClips.HIT_HEAD, BotClips.THROW, BotClips.WAVE })
            assertNotNull(BotClips.get(n), n);
        assertEquals(BotClip.Mode.LOOP, BotClips.get(BotClips.IDLE).mode());
        assertEquals(BotClip.Mode.ONCE, BotClips.get(BotClips.THROW).mode());
        BotClip idle = BotClips.get(BotClips.IDLE);
        // a loop wraps, a clip played once stops at its end
        assertEquals(idle.local(0.3F), idle.local(0.3F + idle.length()), 1e-4);
        BotClip t = BotClips.get(BotClips.THROW);
        assertEquals(t.length(), t.local(99F), 1e-6);
    }

    @Test
    void trackInterpolatesBetweenKeys() {
        BotClip.Track tr = new BotClip.Track(new float[] { 0F, 1F }, new float[] { 0, 10, -4, 2, 20, 4 });
        float[] o = new float[3];
        tr.sample(0.25F, o);
        assertEquals(0.5F, o[0], 1e-6);
        assertEquals(12.5F, o[1], 1e-6);
        assertEquals(-2F, o[2], 1e-6);
        tr.sample(5F, o);
        assertEquals(20F, o[1], 1e-6);
    }

    @Test
    void leanedModelMeetsTheServersEye() {
        Vector3f eye = new Vector3f(0, -4, -4); // the face's middle, head pixels
        for (float frac : new float[] { -1F, -0.5F, 0.7F, 1F })
            for (float yaw : new float[] { 0F, 90F, -135F }) {
                BotPoser.Parts up = parts(), lean = parts();
                BotPoser.apply(up, still(0F), 0F, 0F, 0F);
                BotPoser.apply(lean, still(frac), 0F, 0F, 0F);
                Vector3f m = inModel(lean.head(), eye), u = inModel(up.head(), eye);
                Vector3f world = render(m, yaw), upright = render(u, 0F);
                Vec3 s = AiPoseGeometry.leanPoint(-upright.x, upright.y, upright.z, AiPose.STAND, frac, yaw);
                String at = "frac " + frac + " yaw " + yaw;
                assertEquals(s.x, world.x, 1e-4, at);
                assertEquals(s.y, world.y, 1e-4, at);
                assertEquals(s.z, world.z, 1e-4, at);
                // the legs only step aside
                assertEquals(up.rightLeg().y, lean.rightLeg().y, 1e-5);
                assertEquals(0F, lean.rightLeg().zRot, 1e-6);
            }
    }

    @Test
    void blindMuzzleIsTheDrawnHand() {
        for (byte stance : new byte[] { AiPose.STAND, AiPose.CROUCH })
            for (float frac : new float[] { 0F, 0.6F, -1F })
                for (float yaw : new float[] { 0F, 90F, -135F })
                    for (boolean left : new boolean[] { false, true }) {
                        BotPoser.Parts p = parts();
                        BotPoser.apply(p, new BotAnimator.Frame(stance == AiPose.CROUCH ? 1F : 0F, frac,
                                new float[] { 0F, 0F, 1F, 0F }, null, BotAnimator.NO_ACTS, BotAnimator.NO_HITS, 0F, 0), 0F, 0F, 0F);
                        // the grip as ItemInHandLayer puts it after BotAnimation.hand: the arm, the forearm turned
                        // about the elbow, X -90, Y 180, (±1, 2, -10)/16
                        ModelPart arm = left ? p.leftArm() : p.rightArm(), fore = left ? p.leftForearm() : p.rightForearm();
                        Vector3f grip = new Matrix4f().translate(fore.x, fore.y, fore.z)
                                .rotateZYX(fore.zRot, fore.yRot, fore.xRot).translate(-fore.x, -fore.y, -fore.z)
                                .rotateX((float) Math.toRadians(-90)).rotateY((float) Math.PI)
                                .transformPosition(new Vector3f(left ? -1F : 1F, 2F, -10F));
                        Vector3f world = render(inModel(arm, grip), yaw);
                        Vec3 s = AiPoseGeometry.blindMuzzle(stance, frac, yaw, left);
                        String at = "stance " + stance + " frac " + frac + " yaw " + yaw + " left " + left;
                        assertEquals(s.x, world.x, 1e-4, at);
                        assertEquals(s.y, world.y, 1e-4, at);
                        assertEquals(s.z, world.z, 1e-4, at);
                    }
        // crouched behind cover: the gun over a wall a crouching mob's height (1.5), well above its eye (1.27)
        double up = AiPoseGeometry.blindMuzzle(AiPose.CROUCH, 0F, 0F, false).y;
        System.out.println("blind muzzle crouching " + up);
        assertTrue(up > 1.75 && up < 1.85, "crouching " + up);
    }

    /** the largest move of any part between two frames, radians or pixels/8 (a pixel counted like 1/8 rad) */
    static float jump(BotPoser.Parts a, BotPoser.Parts b) {
        float m = 0F;
        ModelPart[] pa = all(a), pb = all(b);
        for (int i = 0; i < pa.length; i++) {
            // the turn between the two, whatever Euler angles say it
            org.joml.Quaternionf qa = new org.joml.Quaternionf().rotationZYX(pa[i].zRot, pa[i].yRot, pa[i].xRot);
            org.joml.Quaternionf qb = new org.joml.Quaternionf().rotationZYX(pb[i].zRot, pb[i].yRot, pb[i].xRot);
            float d = Math.min(1F, Math.abs(qa.dot(qb)));
            m = Math.max(m, 2F * (float) Math.acos(d));
            m = Math.max(m, Math.abs(pa[i].x - pb[i].x) / 8F);
            m = Math.max(m, Math.abs(pa[i].y - pb[i].y) / 8F);
            m = Math.max(m, Math.abs(pa[i].z - pb[i].z) / 8F);
        }
        return m;
    }

    /**
     * The largest move of any part from one frame to the next through a script of changes, some halfway through
     * others, at {@code perTick} frames a tick.
     */
    static float maxMove(int perTick) {
        BotAnimator an = new BotAnimator();
        float max = 0F;
        BotPoser.Parts prev = null;
        int hurt = 0;
        long lastTick = -1;
        for (int frame = 0; frame < perTick * 70; frame++) {
            double now = 100 + frame / (double) perTick;
            // the server sets the clock back a fifth of a tick once
            if (now >= 125 && now < 125 + 1.0 / perTick) now -= 0.2;
            long tick = (long) Math.floor(now);
            float partial = (float) (now - tick);
            AiPose p;
            if (now < 110) p = pose(AiPose.STAND, 0, 0, 0, AiPose.HOLD, AiPose.NONE, 0, 0);
            else if (now < 111.5) p = pose(AiPose.CROUCH, 0, 0, 0, AiPose.AIM, AiPose.NONE, 0, 0);
            else if (now < 120) p = pose(AiPose.STAND, 100, 0, 111, AiPose.BLIND, AiPose.NONE, 0, 0); // halfway back up
            else if (now < 121) p = pose(AiPose.STAND, -100, 100, 120, AiPose.AIM, AiPose.RELOAD, 120, 30); // lean flips
            else if (now < 140) p = pose(AiPose.CROUCH, -100, 100, 120, AiPose.HOLD, AiPose.RELOAD, 120, 30);
            else if (now < 142) p = pose(AiPose.CROUCH, 0, -100, 140, AiPose.HOLD, AiPose.THROW, 140, 26); // cuts the reload short
            else if (now < 150) p = pose(AiPose.PRONE, 100, -50, 142, AiPose.HOLD, AiPose.SIGNAL_HALT, 142, 20); // cuts the throw, the reload still going
            else p = pose(AiPose.STAND, 100, -50, 142, AiPose.HOLD, AiPose.THROW, 150, 26);
            if (tick != lastTick) {
                // a hit, another before its flinch is over, and death in the middle of a third
                hurt = tick == 130 || tick == 132 || tick == 160 ? 10 : Math.max(0, hurt - 1);
                lastTick = tick;
            }
            BotAnimator.Frame f = an.step(p, tick, partial, hurt, now >= 161, (float) now, 0);
            if (now >= 142 && now < 145) assertTrue(f.fading().length >= 1, "the throw fades at " + now);
            float sum = 0F;
            for (float w : f.arms()) {
                assertTrue(w >= -1e-6F && w <= 1F + 1e-6F, "arms weight " + w);
                sum += w;
            }
            assertEquals(1F, sum, 1e-5, "arms weights sum");
            BotPoser.Parts parts = parts();
            // lying from 142 to 150: the swim amount comes and goes over 5 ticks as vanilla's does
            float swim = (float) (now < 142 ? 0 : now < 150 ? Math.min(1, (now - 142) / 5) : Math.max(0, 1 - (now - 150) / 5));
            BotPoser.apply(parts, f, swim, 0F, 0F);
            if (prev != null) {
                float j = jump(prev, parts);
                max = Math.max(max, j);
            }
            prev = parts;
        }
        return max;
    }

    @Test
    void changesComeWithoutJumps() {
        // at 60 fps (3 frames a tick) and 4 times as often: a smooth motion moves a quarter as far a frame, a jump
        // as far whatever the frame rate
        float at60 = maxMove(3), at240 = maxMove(12);
        System.out.println("bot pose: max move a frame at 60 fps " + at60 + ", at 240 fps " + at240);
        assertTrue(at240 < at60 / 4F * 1.3F, "a jump: " + at60 + " at 60 fps, " + at240 + " at 240 fps");
        // the fastest is UAL's OverhandThrow at its own pace: under 60 degrees a frame at 60 fps
        assertTrue(at60 < 1.05F, "at 60 fps " + at60);
    }
}
