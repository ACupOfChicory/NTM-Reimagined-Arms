package acup.gunfeel.ai.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import net.minecraft.client.model.geom.ModelPart;

// Falling dead: on the ground all the way down (nothing under it, nothing holding it up), and at the end flat on its
// back, the trunk, the arms and the legs as low as the ground lets them, in one piece: standing, crouched or on the run.
class BotDeathTest {
    static Matrix4f frame(ModelPart p) {
        return new Matrix4f().translate(p.x, p.y, p.z).rotateZYX(p.zRot, p.yRot, p.xRot);
    }

    /** a box's lowest and highest point over the ground, pixels */
    static float[] box(Matrix4f m, float x0, float y0, float z0, float w, float h, float d) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            Vector3f v = m.transformPosition(new Vector3f(x0 + (i & 1) * w, y0 + (i >> 1 & 1) * h, z0 + (i >> 2 & 1) * d));
            lo = Math.min(lo, BotPoser.GROUND - v.y);
            hi = Math.max(hi, BotPoser.GROUND - v.y);
        }
        return new float[] { lo, hi };
    }

    /** head, trunk, the arms' and legs' halves: each one's lowest and highest point */
    static float[][] boxes(BotPoser.Parts p) {
        return new float[][] { box(frame(p.head()), -4, -8, -4, 8, 8, 8), box(frame(p.body()), -4, 0, -2, 8, 12, 4),
                box(frame(p.rightArm()), -3, -2, -2, 4, 6, 4), box(frame(p.rightArm()).mul(frame(p.rightForearm())), -2, 0, -2, 4, 6, 4),
                box(frame(p.leftArm()), -1, -2, -2, 4, 6, 4), box(frame(p.leftArm()).mul(frame(p.leftForearm())), -2, 0, -2, 4, 6, 4),
                box(frame(p.rightLeg()), -2, 0, -2, 4, 6, 4), box(frame(p.rightLeg()).mul(frame(p.rightShin())), -2, 0, -2, 4, 6, 4),
                box(frame(p.leftLeg()), -2, 0, -2, 4, 6, 4), box(frame(p.leftLeg()).mul(frame(p.leftShin())), -2, 0, -2, 4, 6, 4) };
    }

    static final String[] NAMES = { "head", "trunk", "right arm", "right forearm", "left arm", "left forearm", "right thigh", "right shin",
            "left thigh", "left shin" };

    /** standing, crouched, and on the run (speed, walk, walkPos) */
    static final float[][] HOW = { { 0F, 0F, 0F, 0F }, { 1F, 0F, 0F, 0F }, { 0F, 0.33F, 1F, 3F } };

    static BotPoser.Parts dead(float[] how, float sec) {
        BotPoser.Parts p = BotPoserTest.parts();
        BotPoser.apply(p, new BotAnimator.Frame(how[0], 0F, BotMoveClipsTest.HOLD, null, BotAnimator.NO_ACTS, BotAnimator.NO_HITS, 0F, 0, how[1], 0F,
                sec), 0F, how[2], how[3]);
        return p;
    }

    /** a point of a part in the model */
    static Vector3f at(Matrix4f m, float x, float y, float z) {
        return m.transformPosition(new Vector3f(x, y, z));
    }

    @Test
    void onTheGroundAllTheWayDown() {
        for (float[] how : HOW)
            for (float sec = 0F; sec <= 2F; sec += 0.025F) {
                float[][] b = boxes(dead(how, sec));
                float low = Float.MAX_VALUE;
                for (float[] x : b) low = Math.min(low, x[0]);
                // the first ticks the fall comes in over the pose it had (BotPoser.DEATH_IN), on the ground as that is
                float most = sec * 20F < BotPoser.DEATH_IN ? 0.5F : 0.01F;
                assertTrue(Math.abs(low) < most, String.format(Locale.ROOT, "crouch %.0f speed %.2f at %.3f s: its lowest point %.2f px over the ground", how[0], how[1], sec, low));
            }
    }

    @Test
    void atTheEndFlatOnItsBack() {
        for (float[] how : HOW) {
            BotPoser.Parts p = dead(how, 2F);
            float[][] b = boxes(p);
            StringBuilder at = new StringBuilder("crouch " + how[0] + " speed " + how[1] + ":");
            for (int i = 0; i < b.length; i++) at.append(String.format(Locale.ROOT, " %s %.1f..%.1f", NAMES[i], b[i][0], b[i][1]));
            // the trunk lies on its back: 4 px thick, on the ground
            assertTrue(b[1][0] < 0.5F && b[1][1] < 4.6F, at.toString());
            // the arms and the legs on the ground beside it, none up in the air
            for (int i = 2; i < b.length; i++) assertTrue(b[i][0] < 0.5F && b[i][1] < 6F, NAMES[i] + ": " + at);
            // the head on the back of the skull, face up: 8 px turned HEAD_ASIDE is 10 high
            assertTrue(b[0][0] < 0.5F && b[0][1] < 10.5F, at.toString());
            // the knees straight
            assertTrue(Math.abs(p.rightShin().xRot) < 1e-3F && Math.abs(p.leftShin().xRot) < 1e-3F, at + "; the knees " + p.rightShin().xRot + " " + p.leftShin().xRot);
            // in one piece: the head at the neck (lifted as the head is thicker than the trunk), the thighs at the hips, the
            // shoulders at the trunk's top
            Matrix4f trunk = frame(p.body());
            Vector3f neck = at(trunk, 0, 0, 0), head = at(frame(p.head()), 0, 0, 0);
            assertTrue(neck.distance(head) < 3.5F, at + "; the head " + neck.distance(head) + " px from the neck");
            for (ModelPart leg : new ModelPart[] { p.rightLeg(), p.leftLeg() }) {
                Vector3f hip = at(trunk, leg.x < 0 ? -1.9F : 1.9F, 12, 0);
                assertTrue(hip.distance(leg.x, leg.y, leg.z) < 1F, at + "; a thigh " + hip.distance(leg.x, leg.y, leg.z) + " px from the hip");
            }
            for (ModelPart arm : new ModelPart[] { p.rightArm(), p.leftArm() }) {
                Vector3f shoulder = at(trunk, arm.x < 0 ? -5F : 5F, 2, 0);
                assertTrue(shoulder.distance(arm.x, arm.y, arm.z) < 1F, at + "; an arm " + shoulder.distance(arm.x, arm.y, arm.z) + " px from the shoulder");
            }
            assertTrue(Math.abs(p.body().xRot - BotPoser.FLAT) < 0.01F && Math.abs(p.body().zRot) < 0.01F, at + "; the trunk turned " + p.body().xRot + " " + p.body().zRot);
        }
    }
}
