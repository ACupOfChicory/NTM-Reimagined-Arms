package acup.gunfeel.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import acup.gunfeel.ai.AiPose;
import net.minecraft.util.Mth;

// The body's turn drawn by a spring, the head first, and the feet standing on the floor while the body turns over them.
class BotTurnTest {
    /** frames of a 60 fps client, in ticks */
    static final double FRAME = 20.0 / 60.0;

    @Test
    void springFollowsAStepWithoutGoingPast() {
        float x = 0F, v = 0F, most = 0F;
        for (int i = 0; i < 120; i++) {
            float[] s = BotAnimator.spring(x, v, 90F, BotAnimator.BODY_SPRING_READY, 1F / 60F);
            x = s[0];
            v = s[1];
            most = Math.max(most, x);
        }
        assertEquals(90F, x, 0.01F);
        assertTrue(most <= 90F + 1e-3F, "critically damped: no overshoot, " + most);
    }

    /** the server's turn of the aim, 30 degrees a tick, as the client gets it a tick at a time */
    static float aim(double tick, float to) {
        return aim(tick, to, 1);
    }

    /** the same, sent every `every` ticks */
    static float aim(double tick, float to, int every) {
        return Math.min(to, 30F * every * (float) Math.floor(Math.max(0.0, tick) / every));
    }

    @Test
    void readyBodyLagsATenthOfASecond() {
        // half of a 180 turn drawn at most 0.1 s after the server's half (the server's at 90 degrees: tick 3), the
        // yaw sent every tick (MixinAiTurnSync)
        assertTrue(halfLag(1F, 1) <= 0.1 + 1e-3, "ready " + halfLag(1F, 1));
        // calm, 0.2 s
        double calm = halfLag(0F, 1);
        assertTrue(calm > 0.1 && calm <= 0.21, "calm " + calm);
        // sent every third tick (the game's own): later, not in jerks (sentEveryThirdTickTurnsSmoothly)
        assertTrue(halfLag(1F, 3) <= 0.2, "ready, every third tick " + halfLag(1F, 3));
    }

    private static double halfLag(float ready, int every) {
        BotAnimator a = new BotAnimator();
        float s = a.sent.at(0F, 0.0);
        a.turn(s, s, ready, 0.0);
        for (int i = 1; i < 200; i++) {
            double t = i * FRAME;
            s = a.sent.at(aim(t, 180F, every), t);
            a.turn(s, s, ready, t);
            if (a.bodyYaw >= 90F) return (t - 3.0) / 20.0;
        }
        return Double.NaN;
    }

    @Test
    void sentEveryThirdTickTurnsSmoothly() {
        // the body's turn speed changes a little from frame to frame (no steps of 90 degrees drawn in a few frames)
        for (int every : new int[] { 1, 3 }) {
            BotAnimator a = new BotAnimator();
            float s = a.sent.at(0F, 0.0);
            a.turn(s, s, 1F, 0.0);
            float last = 0F, lastSpeed = 0F, most = 0F;
            for (int i = 1; i < 200; i++) {
                double t = i * FRAME;
                s = a.sent.at(aim(t, 180F, every), t);
                a.turn(s, s, 1F, t);
                float speed = a.bodyYaw - last;
                most = Math.max(most, Math.abs(speed - lastSpeed));
                last = a.bodyYaw;
                lastSpeed = speed;
            }
            // degrees a frame², at 60 fps: the server's 600 deg/s reached in 0.1 s at the most is some 1.7
            assertTrue(most < 3F, "every " + every + ": " + most);
            assertEquals(180F, a.bodyYaw, 0.01F);
        }
    }

    @Test
    void headLeadsTheBody() {
        BotAnimator a = new BotAnimator();
        a.turn(0F, 0F, 1F, 0.0);
        for (int i = 1; i < 60; i++) {
            double t = i * FRAME;
            a.turn(aim(t, 90F), aim(t, 90F), 1F, t);
            assertTrue(a.headYaw >= a.bodyYaw - 1e-3F, "frame " + i + ": head " + a.headYaw + " body " + a.bodyYaw);
        }
    }

    @Test
    void ticksBegunAgainStartOverNotStandStill() {
        // the mob sent anew (its ticks from nought): where it is now, not held till its old ticks come back
        BotAnimator a = new BotAnimator();
        a.turn(0F, 0F, 1F, 500.0);
        a.turn(0F, 0F, 1F, 501.0);
        a.feet(0.0, 0.0, 1F, 500.0);
        a.feet(0.0, 0.0, 1F, 501.0);
        assertTrue(a.feetOut.w > 0F);
        a.turn(120F, 120F, 1F, 2.0);
        assertEquals(120F, a.bodyYaw, 1e-4F);
        a.feet(5.0, 5.0, 1F, 2.0);
        assertEquals(0F, a.feetOut.w, 0F);
        a.turn(130F, 130F, 1F, 3.0);
        assertTrue(a.bodyYaw > 120F, "turning on: " + a.bodyYaw);
    }

    @Test
    void aLookBackWithinATickChangesNothing() {
        // a second look in a frame at an earlier part of the tick (a shadow, the hitbox taken): as it was
        BotAnimator a = new BotAnimator();
        a.turn(0F, 0F, 1F, 10.0);
        a.feet(0.0, 0.0, 1F, 10.0);
        a.feet(0.0, 0.0, 1F, 10.2);
        a.turn(60F, 60F, 1F, 10.5);
        a.feet(0.0, 0.0, 1F, 10.5);
        float body = a.bodyYaw, w = a.feetOut.w;
        a.turn(60F, 60F, 1F, 10.3);
        a.feet(0.0, 0.0, 1F, 10.3);
        assertEquals(body, a.bodyYaw, 0F);
        assertEquals(w, a.feetOut.w, 0F);
        assertTrue(w > 0F);
        // and the sent yaw's line is not begun again
        BotAnimator.Sent sent = new BotAnimator.Sent();
        sent.at(0F, 10.0);
        float now = sent.at(30F, 11.0);
        assertEquals(now, sent.at(30F, 10.9), 1e-4F);
    }

    @Test
    void feetLetGoOverAFewTicks() {
        // standing, then fallen dead (the weight asked for at once nought): the legs go to the pose over FEET_FADE,
        // eased in and out
        BotAnimator a = new BotAnimator();
        a.turn(0F, 0F, 1F, 0.0);
        for (int i = 0; i <= 30; i++) a.feet(0.0, 0.0, 1F, i * FRAME);
        assertEquals(1F, a.feetOut.w, 1e-6F);
        a.feet(0.0, 0.0, 0F, 30 * FRAME + FRAME);
        assertEquals(BotAnimator.smooth(1F - (float) FRAME / BotAnimator.FEET_FADE), a.feetOut.w, 1e-4F);
        for (int i = 2; i <= 13; i++) a.feet(0.0, 0.0, 0F, 30 * FRAME + i * FRAME);
        assertEquals(0F, a.feetOut.w, 0F);
    }

    @Test
    void kneeComesInByTheFeetsWeight() {
        // a straight leg, the feet barely standing: its knee as the pose has it, not the soft reach's ten degrees
        BotPoser.Parts p = BotPoserTest.parts();
        BotAnimator.Feet feet = new BotAnimator.Feet();
        feet.w = 0.01F;
        float[] at = { -1.9F, 0F, 0F, 0F, 1.9F, 0F, 0F, 0F };
        System.arraycopy(at, 0, feet.at, 0, 8);
        BotPoser.lock(p, feet);
        assertTrue(p.rightShin().xRot < 0.01F, "knee " + p.rightShin().xRot);
        feet.w = 1F;
        p = BotPoserTest.parts();
        BotPoser.lock(p, feet);
        assertEquals(BotPoser.kneeFor(12F), p.rightShin().xRot, 1e-4F);
    }

    @Test
    void modelAndWorldAgree() {
        // the renderer's own transform (BotPoserTest.render) and toWorld put a model point at the same place
        for (float yaw : new float[] { 0F, 90F, -135F, 37F }) {
            Vector3f w = BotPoserTest.render(new Vector3f(3F, 24F, -2F), yaw);
            double[] o = BotAnimator.toWorld(3F, -2F, yaw);
            assertEquals(w.x, o[0], 1e-5, "x at " + yaw);
            assertEquals(w.z, o[1], 1e-5, "z at " + yaw);
            float[] m = BotAnimator.toModel(o[0], o[1], yaw);
            assertEquals(3F, m[0], 1e-4);
            assertEquals(-2F, m[1], 1e-4);
        }
    }

    @Test
    void legReachesItsFoot() {
        BotPoser.Parts p = BotPoserTest.parts();
        BotAnimator.Feet feet = new BotAnimator.Feet();
        feet.w = 1F;
        // the right foot 2 px ahead and 1 px out, 0.3 px up (a straight leg reaches no further), turned 30 degrees; the
        // left lifted 1.5 px where it is
        float[] at = { -2.9F, -2F, 0.3F, 30F * Mth.DEG_TO_RAD, 1.9F, 0F, 1.5F, 0F };
        System.arraycopy(at, 0, feet.at, 0, 8);
        BotPoser.lock(p, feet);
        Vector3f r = BotPoser.ankle(p.rightLeg(), p.rightShin(), new Vector3f()), l = BotPoser.ankle(p.leftLeg(), p.leftShin(), new Vector3f());
        assertEquals(-2.9F, r.x, 0.02F);
        assertEquals(-2F, r.z, 0.02F);
        assertEquals(23.7F, r.y, 0.02F);
        assertEquals(1.9F, l.x, 0.02F);
        assertEquals(22.5F, l.y, 0.02F);
        assertEquals(0F, l.z, 0.02F);
        // the right shin faces 30 degrees from the body: its front (-z) turned about the model's y, which the renderer
        // makes a yaw 30 degrees more in the world
        Vector3f front = new Vector3f(0F, 0F, -1F);
        new org.joml.Matrix4f().rotateZYX(p.rightLeg().zRot, p.rightLeg().yRot, p.rightLeg().xRot)
                .rotateZYX(p.rightShin().zRot, p.rightShin().yRot, p.rightShin().xRot).transformDirection(front);
        float turned = (float) Math.toDegrees(Math.atan2(-front.x, -front.z));
        assertEquals(30F, turned, 3F, "the shin's front, model yaw");
        // where the pose had them, for the next step
        assertEquals(-1.9F, feet.neutral[0], 1e-4);
        assertEquals(24F, feet.neutral[1], 1e-4);
    }

    @Test
    void halfTurnFeetTurnSmoothlyAndNoFurtherThanThePivot() {
        BotAnimator a = new BotAnimator();
        a.turn(170F, 170F, 1F, -5.0);
        a.feet(0.0, 0.0, 1F, -5.0);
        a.feet(0.0, 0.0, 1F, 0.0);
        float[] last = { 0F, 0F };
        for (int i = 1; i < 120; i++) {
            double t = i * FRAME;
            // over +-180: from 170 to -10 the short way round (-180)
            float to = Mth.wrapDegrees(170F - aim(t, 180F));
            a.turn(to, to, 1F, t);
            a.feet(0.0, 0.0, 1F, t);
            for (int k = 0; k < 2; k++) {
                float twist = a.feetOut.at[k * 4 + 3] * Mth.RAD_TO_DEG;
                assertTrue(Math.abs(twist) <= BotAnimator.PIVOT + 1e-3F, "frame " + i + " foot " + k + ": " + twist);
                // a frame's change: the body's turn (some 15 degrees a frame at most) and a step's (quicker in a turn
                // this quick, QUICKEST), no jump
                assertTrue(Math.abs(twist - last[k]) < 30F, "frame " + i + " foot " + k + ": " + last[k] + " -> " + twist);
                last[k] = twist;
            }
        }
        assertEquals(-10F, Mth.wrapDegrees(a.bodyYaw), 0.01F);
    }

    @Test
    void kneeBendsOneWayAndSmoothly() {
        // a pose's knee bent back a little (a clip's) does not turn the reaching knee back: it bends forward
        BotPoser.Parts p = BotPoserTest.parts();
        p.rightShin().xRot = -0.02F;
        BotAnimator.Feet feet = new BotAnimator.Feet();
        feet.w = 1F;
        float[] at = { -1.9F, 0F, 2F, 0F, 1.9F, 0F, 0F, 0F };
        System.arraycopy(at, 0, feet.at, 0, 8);
        BotPoser.lock(p, feet);
        assertTrue(p.rightShin().xRot > 0F, "knee " + p.rightShin().xRot);
        // near a straight leg a little more reach changes the knee by a bounded amount
        float last = BotPoser.kneeFor(11F);
        for (float r = 11.01F; r <= 12.5F; r += 0.01F) {
            float k = BotPoser.kneeFor(r);
            assertTrue(k <= last && last - k < 0.02F, "at " + r + ": " + last + " -> " + k);
            last = k;
        }
    }

    @Test
    void feetStandWhileTheBodyTurnsAndStepToIt() {
        BotAnimator a = new BotAnimator();
        a.turn(0F, 0F, 1F, -5.0);
        // a first look puts nothing down; bearing the weight (over FEET_FADE ticks) both feet where the pose has them
        a.feet(0.0, 0.0, 1F, -5.0);
        a.feet(0.0, 0.0, 1F, 0.0);
        double[][] r0 = { BotAnimator.toWorld(a.feetOut.at[0], a.feetOut.at[1], a.bodyYaw),
                BotAnimator.toWorld(a.feetOut.at[4], a.feetOut.at[5], a.bodyYaw) };
        int steps = 0;
        boolean stepping = false;
        double most = 0.0;
        for (int i = 1; i < 120; i++) {
            double t = i * FRAME;
            a.turn(aim(t, 90F), aim(t, 90F), 1F, t);
            a.feet(0.0, 0.0, 1F, t);
            boolean up = a.feetOut.at[2] > 0F || a.feetOut.at[6] > 0F;
            if (up && !stepping) steps++;
            stepping = up;
            if (steps == 0) {
                // before the first step both feet stay where they stood
                for (int k = 0; k < 2; k++) {
                    double[] r = BotAnimator.toWorld(a.feetOut.at[k * 4], a.feetOut.at[k * 4 + 1], a.bodyYaw);
                    most = Math.max(most, Math.hypot(r[0] - r0[k][0], r[1] - r0[k][1]));
                }
            }
        }
        assertTrue(most < 1e-4, "the standing foot moved " + most);
        assertTrue(steps >= 2 && steps <= 3, "steps " + steps);
        // all done: both feet where the pose has them, facing the body's way
        for (int k = 0; k < 2; k++) {
            assertEquals(a.feetOut.neutral[k * 3], a.feetOut.at[k * 4], 0.05F);
            assertEquals(a.feetOut.neutral[k * 3 + 2], a.feetOut.at[k * 4 + 1], 0.05F);
            assertEquals(0F, a.feetOut.at[k * 4 + 3], 0.01F);
        }
    }

    @Test
    void aFootLeftFarBehindIsDrawnAlong() {
        // standing, then the body going off faster than the steps (a push, a slide): no foot stands farther than DRAG
        // from where the pose has it, where the leg would reach it only stretched
        BotAnimator a = new BotAnimator();
        a.turn(0F, 0F, 1F, -5.0);
        a.feet(0.0, 0.0, 1F, -5.0);
        a.feet(0.0, 0.0, 1F, 0.0);
        double most = 0.0;
        for (int i = 1; i < 60; i++) {
            double t = i * FRAME, x = 0.02 * i;
            a.turn(0F, 0F, 1F, t);
            a.feet(x, 0.0, 1F, t);
            for (int k = 0; k < 2; k++)
                most = Math.max(most, Math.hypot(a.feetOut.at[k * 4] - a.feetOut.neutral[k * 3], a.feetOut.at[k * 4 + 1] - a.feetOut.neutral[k * 3 + 2]));
        }
        assertTrue(most <= BotAnimator.DRAG + 1e-3, "a foot " + most + " px out");
        assertTrue(most > BotAnimator.DRAG - 0.5, "the feet stood, " + most + " px out at most");
    }

    /**
     * The fastest any part moves (radians a tick) through the bot stand's script of changes (BotStand.jumps) standing
     * on its feet, at {@code perTick} frames a tick
     */
    static float standingScript(int perTick) {
        BotAnimator an = new BotAnimator();
        BotPoser.Parts prev = null;
        float max = 0F;
        int base = 100;
        for (int frame = 0; frame < perTick * 95; frame++) {
            double now = base + frame / (double) perTick;
            long tick = (long) Math.floor(now), t = tick - base;
            float partial = (float) (now - tick);
            AiPose p;
            if (t < 10) p = fight(AiPose.STAND, 0, 0, 0, AiPose.HOLD, AiPose.NONE, 0, 0);
            else if (t < 11) p = fight(AiPose.CROUCH, 0, 0, 0, AiPose.AIM, AiPose.NONE, 0, 0);
            else if (t < 20) p = fight(AiPose.STAND, 100, 0, base + 11, AiPose.BLIND, AiPose.NONE, 0, 0);
            else if (t < 21) p = fight(AiPose.STAND, -100, 100, base + 20, AiPose.AIM, AiPose.RELOAD, base + 20, 30);
            else if (t < 40) p = fight(AiPose.CROUCH, -100, 100, base + 20, AiPose.HOLD, AiPose.RELOAD, base + 20, 30);
            else if (t < 50) p = fight(AiPose.CROUCH, 0, -100, base + 40, AiPose.HOLD, AiPose.SIGNAL_POINT, base + 40, 20);
            else if (t < 70) p = fight(AiPose.STAND, 0, 0, base + 40, AiPose.AIM, AiPose.SHOT, base + 50, 0);
            else p = fight(AiPose.STAND, 0, 0, base + 40, AiPose.NO_GUN, AiPose.SIGNAL_HALT, base + 70, 20);
            BotAnimator.Frame f = an.step(p, tick, partial, 0, false, (float) now, 0);
            an.turn(0F, 0F, 1F, now);
            // as frame() weighs them: the feet stand as far as it is up from a crouch
            an.feet(0.0, 0.0, 1F - BotAnimator.smooth(f.crouch()), now);
            BotPoser.Parts parts = BotPoserTest.parts();
            BotPoser.apply(parts, f, 0F, 0F, 0F);
            BotPoser.lock(parts, an.feetOut.snapshot());
            if (prev != null) max = Math.max(max, BotPoserTest.jump(prev, parts) * perTick);
            prev = parts;
        }
        return max;
    }

    private static AiPose fight(byte stance, int lean, int prevLean, int leanTick, byte arms, byte action, int actionTick,
            int actionTicks) {
        return new AiPose(stance, (byte) lean, (byte) prevLean, leanTick, arms, action, actionTick, (short) actionTicks, AiPose.FIGHT);
    }

    @Test
    void upFromACrouchTheKneesDoNotSnap() {
        // a crouch's feet put down where they were stood 5 px out of the standing leg's reach, and its next step bent
        // the knee 2 radians a tick (the stand's own check fails at 2); the script's fastest part without the feet, the
        // arms, is about 1
        float at12 = standingScript(12), at48 = standingScript(48);
        System.out.println("standing script: fastest part at 12 frames a tick " + at12 + ", at 48 " + at48);
        assertTrue(at48 < 1.5F, "at 48 frames a tick " + at48);
        assertTrue(at48 < at12 * 1.3F, "a snap: " + at12 + " at 12 frames a tick, " + at48 + " at 48");
    }
}
