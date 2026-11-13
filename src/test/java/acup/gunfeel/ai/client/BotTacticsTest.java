package acup.gunfeel.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import acup.gunfeel.ai.AiPose;
import net.minecraft.client.model.geom.ModelPart;

// What a squad going into a building is drawn with: the gun close beside a doorway (STACK), the free hand at the door
// (DOOR), down on a knee and walking bent low under the crouch's head, the four flinches by turns; all of it with the
// head at the eye the server keeps and no jump coming or going.
class BotTacticsTest {
    static final float[] STACK = { 0F, 0F, 0F, 0F, 1F };

    static BotAnimator.Frame frame(float crouch, float[] arms, BotAnimator.Act act, float speed, float alert) {
        return new BotAnimator.Frame(crouch, 0F, arms, act, BotAnimator.NO_ACTS, BotAnimator.NO_HITS, 0F, 0, speed, 0F, -1F,
                Math.min(1F, speed * 4F), 0F, alert);
    }

    static BotPoser.Parts posed(BotAnimator.Frame f) {
        BotPoser.Parts p = BotPoserTest.parts();
        BotPoser.apply(p, f, 0F, f.walk(), 0F);
        return p;
    }

    /** an arm's hand (its forearm's end) and elbow, model pixels */
    static Vector3f[] arm(ModelPart upper, ModelPart fore) {
        Matrix4f m = BotGaitTest.frame(upper);
        Vector3f elbow = m.transformPosition(new Vector3f(fore.x, fore.y, fore.z));
        Vector3f hand = m.mul(BotGaitTest.frame(fore)).transformPosition(new Vector3f(0F, 6F, 0F));
        return new Vector3f[] { hand, elbow };
    }

    @Test
    void theClipsLoad() {
        for (String n : new String[] { BotClips.KNEEL, BotClips.CROUCH_WALK, BotClips.JUMP, BotClips.HIT_FOLD, BotClips.HIT_BACK,
                BotClips.DOOR })
            assertNotNull(BotClips.get(n), n);
        assertEquals(BotClip.Mode.LOOP, BotClips.get(BotClips.CROUCH_WALK).mode());
    }

    @Test
    void besideADoorwayTheGunIsCloseItsMuzzleUp() {
        for (float crouch : new float[] { 0F, 1F })
            for (float speed : new float[] { 0F, 0.1F }) {
                BotPoser.Parts p = posed(frame(crouch, STACK, null, speed, 1F));
                Vector3f[] right = arm(p.rightArm(), p.rightForearm()), left = arm(p.leftArm(), p.leftForearm());
                // the gun lies along the right forearm: up and forward, raised 30 to 45 degrees over the level
                Vector3f gun = new Vector3f(right[0]).sub(right[1]).normalize();
                double off = Math.toDegrees(Math.acos(-gun.y));
                String at = String.format(Locale.ROOT, "crouch %.0f speed %.2f: the gun %.2f %.2f %.2f, %.0f degrees off straight up", crouch,
                        speed, gun.x, gun.y, gun.z, off);
                assertTrue(off > 45 && off < 60 && gun.z < 0F && Math.abs(gun.x) < 0.35F, at);
                // the right hand close in front of the right shoulder, clear of the face
                Vector3f shoulder = new Vector3f(p.rightArm().x, p.rightArm().y, p.rightArm().z);
                Vector3f h = new Vector3f(right[0]).sub(shoulder);
                String hand = String.format(Locale.ROOT, "crouch %.0f speed %.2f: the right hand %.1f %.1f %.1f from its shoulder", crouch, speed,
                        h.x, h.y, h.z);
                assertTrue(Math.abs(h.x) < 3F && h.y > -4F && h.y < 4F && h.z < -4F && h.z > -9F, hand);
                // the left hand on the gun, about 3 px up it
                Vector3f l = new Vector3f(left[0]).sub(right[0]);
                float along = l.dot(gun), aside = new Vector3f(l).sub(new Vector3f(gun).mul(along)).length();
                assertTrue(along > 1.5F && along < 4.5F && aside < 1.6F,
                        String.format(Locale.ROOT, "crouch %.0f speed %.2f: the left hand %.1f px up the gun, %.1f px off it", crouch, speed, along, aside));
                assertEquals(crouch * 4.2F, p.head().y, 1e-4F, "the head where the stance has it");
            }
    }

    @Test
    void atADoorTheFreeHandGoesOutAndTheGunStays() {
        BotPoser.Parts none = posed(frame(0F, BotMoveClipsTest.HOLD, null, 0F, 1F));
        float reach = 0F;
        for (int i = 0; i <= 10; i++) {
            BotPoser.Parts p = posed(frame(0F, BotMoveClipsTest.HOLD, new BotAnimator.Act(AiPose.DOOR, i / 10F, 1F, i), 0F, 1F));
            Vector3f hand = arm(p.leftArm(), p.leftForearm())[0];
            reach = Math.max(reach, -hand.z);
            assertEquals(none.rightArm().xRot, p.rightArm().xRot, 1e-6F, "the gun hand");
            assertEquals(none.rightForearm().xRot, p.rightForearm().xRot, 1e-6F);
            assertEquals(0F, new Vector3f(p.head().x, p.head().y, p.head().z).length(), 1e-6F, "the head");
            assertEquals(none.body().xRot, p.body().xRot, 1e-6F);
            assertEquals(none.rightLeg().xRot, p.rightLeg().xRot, 1e-6F);
        }
        assertTrue(reach > 7F, "the left hand " + reach + " px out in front");
    }

    @Test
    void hitsFlinchFourWaysByTurns() {
        BotAnimator a = new BotAnimator();
        List<String> seen = new ArrayList<>();
        int tick = 100;
        a.step(AiPose.IDLE, tick++, 0F, 0, false, tick, 1);
        for (int n = 0; n < 5; n++) {
            BotAnimator.Frame f = a.step(AiPose.IDLE, tick, 0F, 10, false, tick, 1);
            tick++;
            seen.add(f.hits()[f.hits().length - 1].clip());
            for (int h = 9; h >= 0; h--, tick++) a.step(AiPose.IDLE, tick, 0F, h, false, tick, 1);
            for (int rest = 0; rest < 10; rest++, tick++) a.step(AiPose.IDLE, tick, 0F, 0, false, tick, 1);
        }
        assertEquals(4, seen.subList(0, 4).stream().distinct().count(), "four hits: " + seen);
        assertTrue(seen.contains(BotClips.HIT_FOLD) && seen.contains(BotClips.HIT_BACK) && seen.contains(BotClips.HIT_HEAD)
                && seen.contains(BotClips.HIT_CHEST), seen.toString());
        assertEquals(seen.get(0), seen.get(4), "and round again");
        // each of them throws the head no farther than the chest's flinch always did (3.6 px, for a third of a second)
        for (String clip : seen.subList(0, 4))
            for (float sec = 0F; sec <= 0.6F; sec += 0.02F) {
                BotPoser.Parts p = BotPoserTest.parts();
                BotPoser.apply(p, new BotAnimator.Frame(0F, 0F, BotMoveClipsTest.HOLD, null, BotAnimator.NO_ACTS,
                        new BotAnimator.Hit[] { new BotAnimator.Hit(clip, sec, 1F) }, 0F, 0, 0F, 0F, -1F, 0F, 0F, 1F), 0F, 0F, 0F);
                float d = new Vector3f(p.head().x, p.head().y, p.head().z).length();
                assertTrue(d <= 3.6F, clip + " at " + sec + " s: the head " + d + " px off");
            }
    }

    @Test
    void crouchedTheHeadIsAtTheCrouchsEyeWhateverItDoes() {
        // vanilla's crouch: the head 4.2 px down, nothing else of it moved; at a stand, creeping, with any arms, at
        // any point of the step and of going down
        for (float[] arms : new float[][] { BotMoveClipsTest.HOLD, { 0F, 1F, 0F, 0F }, { 0F, 0F, 1F, 0F }, STACK })
            for (float speed : new float[] { 0F, 0.03F, 0.08F, 0.19F })
                for (float c : new float[] { 0.3F, 0.7F, 1F })
                    for (int i = 0; i < 16; i++) {
                        BotPoser.Parts p = BotPoserTest.parts();
                        BotPoser.apply(p, frame(c, arms, null, speed, 1F), 0F, Math.min(1F, speed * 4F), BotPoser.CYCLE * i / 16F);
                        float want = 4.2F * BotAnimator.smooth(c);
                        float d = new Vector3f(p.head().x, p.head().y - want, p.head().z).length();
                        assertTrue(d <= 1e-4F, String.format(Locale.ROOT, "crouch %.1f speed %.2f: the head %.2f px off", c, speed, d));
                    }
    }

    /** the largest move of any part a frame through a squad's way into a house, at perTick frames a tick */
    static float maxMove(int perTick) {
        BotAnimator an = new BotAnimator();
        float max = 0F;
        BotPoser.Parts prev = null;
        for (int frame = 0; frame < perTick * 110; frame++) {
            double now = 100 + frame / (double) perTick;
            long tick = (long) Math.floor(now);
            float partial = (float) (now - tick);
            AiPose p;
            double step;
            boolean off = false;
            double dy = 0;
            if (now < 110) { p = BotPoserTest.pose(AiPose.STAND, 0, 0, 0, AiPose.HOLD, AiPose.NONE, 0, 0); step = 0.2; } // up to the house
            else if (now < 125) { p = BotPoserTest.pose(AiPose.STAND, 0, 0, 0, AiPose.STACK, AiPose.NONE, 0, 0); step = 0; } // beside the door
            else if (now < 135) { p = BotPoserTest.pose(AiPose.STAND, 0, 0, 0, AiPose.STACK, AiPose.DOOR, 125, 10); step = 0; }
            else if (now < 145) { p = BotPoserTest.pose(AiPose.CROUCH, 0, 0, 0, AiPose.HOLD, AiPose.NONE, 0, 0); step = 0.08; } // in, bent low
            else if (now < 160) { p = BotPoserTest.pose(AiPose.CROUCH, 0, 0, 0, AiPose.AIM, AiPose.NONE, 0, 0); step = 0; } // down on a knee
            else if (now < 170) { p = BotPoserTest.pose(AiPose.CROUCH, 0, 0, 0, AiPose.STACK, AiPose.NONE, 0, 0); step = 0.08; }
            else if (now < 180) { p = BotPoserTest.pose(AiPose.STAND, 0, 0, 0, AiPose.HOLD, AiPose.NONE, 0, 0); step = 0; } // up, standing in a fight
            else if (now < 192) { p = BotPoserTest.pose(AiPose.STAND, 0, 0, 0, AiPose.HOLD, AiPose.NONE, 0, 0); step = 0.2; off = true; dy = now < 186 ? 0.2 : -0.2; } // a jump
            else { p = BotPoserTest.pose(AiPose.STAND, 0, 0, 0, AiPose.AIM, AiPose.NONE, 0, 0); step = 0; }
            p = p.withAlert(AiPose.FIGHT);
            // as BotAnimator.frame has them: the step since the last tick, every frame
            an.move(0, step, 0F, now);
            an.fly(off, dy, now);
            BotAnimator.Frame f = an.step(p, tick, partial, 0, false, (float) now, 0);
            f = new BotAnimator.Frame(f.crouch(), f.lean(), f.arms(), f.act(), f.fading(), f.hits(), f.idleSec(), f.entity(), an.speed,
                    an.heading, -1F, an.walk, an.walkPos, f.alert(), an.air);
            BotPoser.Parts parts = BotPoserTest.parts();
            BotPoser.apply(parts, f, 0F, an.walk, an.walkPos);
            if (prev != null) max = Math.max(max, BotPoserTest.jump(prev, parts));
            prev = parts;
        }
        return max;
    }

    @Test
    void theWayIntoAHouseHasNoJump() {
        float at60 = maxMove(3), at240 = maxMove(12);
        System.out.println("bot tactics: max move a frame at 60 fps " + at60 + ", at 240 fps " + at240);
        assertTrue(at240 < at60 / 4F * 1.3F, "a jump: " + at60 + " at 60 fps, " + at240 + " at 240 fps");
        // the fastest: the walk coming in from a stand in a tick, a shin bending 1.2 rad
        assertTrue(at60 < 0.75F, "at 60 fps " + at60);
    }
}
