package acup.gunfeel.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import acup.gunfeel.ai.AiPose;
import net.minecraft.client.model.geom.ModelPart;

// The bot's clips on the move, falling dead, reloading and thrown back by a hit: they load, they play where they
// should, and no change of speed, heading, death or reload makes a part jump between two frames.
class BotMoveClipsTest {
    static final float[] HOLD = { 1F, 0F, 0F, 0F };

    static BotAnimator.Frame moving(float speed, float heading, float deathSec, BotAnimator.Act act) {
        return new BotAnimator.Frame(0F, 0F, HOLD, act, BotAnimator.NO_ACTS, BotAnimator.NO_HITS, 0F, 0, speed, heading, deathSec);
    }

    /** calm: a stroll, the clips' legs as they are (no tactical walk's bend) */
    static BotAnimator.Frame calm(float speed, float heading) {
        return new BotAnimator.Frame(0F, 0F, HOLD, null, BotAnimator.NO_ACTS, BotAnimator.NO_HITS, 0F, 0, speed, heading, -1F, 0F, 0F, 0F);
    }

    static float[] angles(BotPoser.Parts p) {
        ModelPart[] all = BotPoserTest.all(p);
        float[] out = new float[all.length * 6];
        for (int i = 0; i < all.length; i++) {
            out[i * 6] = all[i].xRot;
            out[i * 6 + 1] = all[i].yRot;
            out[i * 6 + 2] = all[i].zRot;
            out[i * 6 + 3] = all[i].x / 16F;
            out[i * 6 + 4] = all[i].y / 16F;
            out[i * 6 + 5] = all[i].z / 16F;
        }
        return out;
    }

    /** the most any angle (radians) or pivot (blocks) moved */
    static float most(float[] a, float[] b) {
        float m = 0F;
        for (int i = 0; i < a.length; i++) m = Math.max(m, Math.abs(a[i] - b[i]));
        return m;
    }

    /**
     * A jump: a part's angle (or pivot) that changes over a frame and keeps nearly all of that change however finely
     * the frame is cut (halved six times, each time the half that changes it more). A move, however fast, and a kink
     * (a clip's linear keys) shrink with the cut; a jump does not. Returns the worst share left in a 64th of a frame,
     * of a change over 0.03 (near 0: smooth, near 1: a jump); {@link #where} says which part and when.
     */
    static String where = "";
    static final String[] PARTS = { "head", "body", "right_arm", "left_arm", "right_leg", "left_leg", "right_forearm", "left_forearm",
            "right_shin", "left_shin" };

    static float jumpShare(java.util.function.Function<Float, float[]> pose, float from, float to, int frames) {
        return jumpShare(pose, from, to, frames, 6, "xyzXYZ");
    }

    /** as above, each part {@code stride} numbers named by {@code names} */
    static float jumpShare(java.util.function.Function<Float, float[]> pose, float from, float to, int frames, int stride, String names) {
        float worst = 0F;
        where = "";
        for (int i = 0; i < frames; i++) {
            float t0 = from + (to - from) * i / frames, t1 = from + (to - from) * (i + 1) / frames;
            float[] a = pose.apply(t0), b = pose.apply(t1);
            for (int k = 0; k < a.length; k++) {
                float d = Math.abs(b[k] - a[k]);
                if (d <= 0.03F) continue;
                float lo = t0, hi = t1, va = a[k], vb = b[k];
                for (int n = 0; n < 6; n++) {
                    float mid = (lo + hi) / 2F, vm = pose.apply(mid)[k];
                    if (Math.abs(vm - va) >= Math.abs(vb - vm)) {
                        hi = mid;
                        vb = vm;
                    } else {
                        lo = mid;
                        va = vm;
                    }
                }
                float share = Math.abs(vb - va) / d;
                if (share > worst) {
                    worst = share;
                    where = String.format(java.util.Locale.ROOT, "%s %s at %.3f: %.3f to %.3f", PARTS[k / stride], names.charAt(k % stride), t0, a[k], b[k]);
                }
            }
        }
        return worst;
    }

    static float[] posed(BotAnimator.Frame f, float walkSpeed, float walkPos) {
        BotPoser.Parts p = BotPoserTest.parts();
        BotPoser.apply(p, f, 0F, walkSpeed, walkPos);
        return angles(p);
    }

    @Test
    void theClipsLoad() {
        for (String n : new String[] { BotClips.WALK, BotClips.JOG, BotClips.SPRINT, BotClips.BACK, BotClips.STRAFE_LEFT,
                BotClips.STRAFE_RIGHT, BotClips.RELOAD, BotClips.DEATH, BotClips.HIT_KNOCK })
            assertNotNull(BotClips.get(n), n);
    }

    @Test
    void walkingTheLegsAreTheClipsAndStepWithVanilla() {
        // standing still: vanilla's legs (no clip); walking: the walk clip's legs, the right leg furthest back where
        // vanilla's is (walkPos 0)
        float[] still = posed(calm(0F, 0F), 0F, 0F);
        BotPoser.Parts p = BotPoserTest.parts();
        BotPoser.apply(p, calm(0.1F, 0F), 0F, 0.5F, 0F);
        BotClip walk = BotClips.get(BotClips.WALK);
        float[] r = new float[3];
        walk.rotation("right_leg", BotPoser.phase(BotClips.WALK, walk) * walk.length(), r);
        assertEquals(r[0] * net.minecraft.util.Mth.DEG_TO_RAD, p.rightLeg().xRot, 1e-3);
        assertEquals(0F, still[4 * 6], 1e-6);
        // half a step on, the legs have changed sides
        BotPoser.Parts q = BotPoserTest.parts();
        BotPoser.apply(q, calm(0.1F, 0F), 0F, 0.5F, BotPoser.CYCLE / 2F);
        assertTrue(p.rightLeg().xRot > p.leftLeg().xRot && q.rightLeg().xRot < q.leftLeg().xRot,
                p.rightLeg().xRot + " " + p.leftLeg().xRot + " / " + q.rightLeg().xRot + " " + q.leftLeg().xRot);
    }

    /** frame i of a sweep: speed 0 .. 0.3 and back, heading round both ways, walkPos on by vanilla's (min(4 speed, 1) a tick) */
    static float[] onTheMove(float i) {
        float speed = 0.3F * (0.5F - 0.5F * (float) Math.cos(i * 0.004F));
        float heading = (float) Math.PI * (float) Math.sin(i * 0.0021F);
        // walkPos: the integral of min(4 speed, 1) a tick, a frame a third of a tick (near enough, summed in closed form
        // for the cosine's mean); only its continuity matters here
        float pos = 0.3F * 4F / 3F * (i / 2F - (float) Math.sin(i * 0.004F) / 0.008F);
        return posed(moving(speed, heading, -1F, null), Math.min(1F, speed * 4F), pos);
    }

    @Test
    void noJumpOnTheMove() {
        float share = jumpShare(BotMoveClipsTest::onTheMove, 0F, 3000F, 3000);
        assertTrue(share < 0.25F, "a part jumped on the move: " + share + " of a frame's move in half of it; " + where);
    }

    @Test
    void fallingDeadPlaysTheClipWithNoJump() {
        BotClip death = BotClips.get(BotClips.DEATH);
        float[] alive = posed(moving(0F, 0F, -1F, null), 0F, 0F), last = posed(moving(0F, 0F, 1F, null), 0F, 0F);
        float share = jumpShare(s -> posed(moving(0F, 0F, s, null), 0F, 0F), 0F, 1F, 60);
        assertTrue(share < 0.25F, "a part jumped falling dead: " + share + "; " + where);
        // halfway: the clip's frame, not the pose it had; at its end: settled flat on its back (BotPoser.settle)
        BotPoser.Parts p = BotPoserTest.parts();
        float half = death.length() * BotPoser.SETTLE_FROM;
        BotPoser.apply(p, moving(0F, 0F, half, null), 0F, 0F, 0F);
        float[] r = new float[3];
        death.rotation("body", half, r);
        assertEquals(r[0] * net.minecraft.util.Mth.DEG_TO_RAD, p.body().xRot, 1e-3);
        BotPoser.apply(p, moving(0F, 0F, 1F, null), 0F, 0F, 0F);
        assertEquals(BotPoser.FLAT, p.body().xRot, 0.01);
        assertTrue(most(alive, last) > 0.5F);
    }

    @Test
    void reloadingPlaysTheClipWithNoJump() {
        float[] held = posed(moving(0F, 0F, -1F, null), 0F, 0F);
        int len = 40;
        java.util.function.Function<Float, float[]> at = tick -> posed(moving(0F, 0F, -1F, new BotAnimator.Act(AiPose.RELOAD, tick / len,
                BotAnimator.smooth(Math.min(tick / BotAnimator.ACTION_FADE, (len - tick) / BotAnimator.ACTION_FADE)), tick)), 0F, 0F);
        float share = jumpShare(at, 0F, len, len * 3);
        assertTrue(share < 0.25F, "a part jumped reloading: " + share + "; " + where);
        assertTrue(most(at.apply(len / 2F), held) > 0.5F, "the reload moves the arms");
    }

    @Test
    void aHitWhileThrownFastIsTheKnockback() {
        BotAnimator a = new BotAnimator();
        a.step(AiPose.IDLE, 100, 0F, 0, false, 100, 1);
        a.push = 0.5F;
        BotAnimator.Frame f = a.step(AiPose.IDLE, 101, 0F, 10, false, 101, 1);
        a.push = 0F;
        BotAnimator.Frame g = a.step(AiPose.IDLE, 120, 0F, 9, false, 120, 1);
        BotAnimator.Frame h = a.step(AiPose.IDLE, 121, 0F, 10, false, 121, 1);
        assertEquals(BotClips.HIT_KNOCK, f.hits()[f.hits().length - 1].clip());
        assertTrue(h.hits().length > 0 && !h.hits()[h.hits().length - 1].clip().equals(BotClips.HIT_KNOCK));
    }

    @Test
    void aPushComingJustAfterTheHitIsTheKnockbackWithNoJump() {
        // the hit at 100, its push half a tick later: the flinch begun gives way to the knockback, smoothly
        BotAnimator a = new BotAnimator();
        float worst = stepped(a, t -> t >= 100 ? Math.max(0, 10 - (t - 100)) : 0, t -> false, t -> 0F, 95, 100);
        a.push = 0F;
        BotAnimator.Frame f = a.step(AiPose.IDLE, 100, 0.2F, 10, false, 100.2F, 1);
        a.push = 1F;
        BotAnimator.Frame g = a.step(AiPose.IDLE, 100, 0.7F, 10, false, 100.7F, 1);
        assertTrue(!f.hits()[f.hits().length - 1].clip().equals(BotClips.HIT_KNOCK));
        assertTrue(java.util.Arrays.stream(g.hits()).anyMatch(h -> h.clip().equals(BotClips.HIT_KNOCK)), "the push makes it the knockback");
        // run through from the hit with the push half a tick late
        BotAnimator b = new BotAnimator();
        worst = Math.max(worst, stepped(b, t -> t >= 100 ? Math.max(0, 10 - (t - 100)) : 0, t -> false, t -> t >= 101 ? 1F : 0F, 95, 130));
        assertTrue(worst < 0.02F, "a part jumped: " + worst + " rad; " + where);
        // a push long after the hit is no knockback
        BotAnimator c = new BotAnimator();
        stepped(c, t -> t >= 100 ? Math.max(0, 10 - (t - 100)) : 0, t -> false, t -> t >= 104 ? 1F : 0F, 95, 106);
        c.push = 1F;
        BotAnimator.Frame h = c.step(AiPose.IDLE, 106, 0F, 4, false, 106F, 1);
        assertTrue(java.util.Arrays.stream(h.hits()).noneMatch(x -> x.clip().equals(BotClips.HIT_KNOCK)));
    }

    @Test
    void reloadingOutOfBlindFireHasNoJump() {
        // BotStand's script: blind fire, then aiming as the reload begins (20), then crouched holding it (21)
        BotAnimator a = new BotAnimator();
        float worst = 0F;
        float[] last = null;
        for (int i = 1000; i < 4000; i++) {
            int tick = i / 100;
            float partial = (i % 100) / 100F;
            AiPose p = tick < 20 ? new AiPose(AiPose.STAND, (byte) 100, (byte) 0, 11, AiPose.BLIND, AiPose.NONE, 0, (short) 0)
                    : tick < 21 ? new AiPose(AiPose.STAND, (byte) -100, (byte) 100, 20, AiPose.AIM, AiPose.RELOAD, 20, (short) 30)
                    : new AiPose(AiPose.CROUCH, (byte) -100, (byte) 100, 20, AiPose.HOLD, AiPose.RELOAD, 20, (short) 30);
            BotAnimator.Frame f = a.step(p, tick, partial, 0, false, tick + partial, 1);
            float[] now = posed(f, 0F, 0F);
            if (last != null) for (int k = 0; k < now.length / 6; k++) {
                float d = turnBetween(last, now, k);
                if (d > worst) {
                    worst = d;
                    where = String.format(java.util.Locale.ROOT, "%s at tick %.2f: %.3f %.3f %.3f to %.3f %.3f %.3f", PARTS[k], tick + partial, last[k * 6],
                            last[k * 6 + 1], last[k * 6 + 2], now[k * 6], now[k * 6 + 1], now[k * 6 + 2]);
                }
            }
            last = now;
        }
        assertTrue(worst < 0.02F, "a part jumped: " + worst + " rad; " + where);
    }

    @Test
    void fromASprintStraightIntoWalkingBackHasNoJump() {
        // BotStand's script: a sprint ahead (0.33 a tick), then at once back (0.10 a tick); vanilla's walk as the game
        // keeps it (its speed follows 4 times the step by 0.4 a tick, its position goes on by that speed)
        BotAnimator a = new BotAnimator();
        float worst = 0F, walkSpeed = 0F, walkPos = 0F, prevSpeed = 0F, prevPos = 0F;
        float[] last = null;
        for (int i = 8000; i < 11000; i++) {
            int tick = i / 100;
            float partial = (i % 100) / 100F;
            // facing -z: ahead is -z; the step since the last tick, the same on every frame of the tick
            a.move(0, tick < 95 ? -0.33F : 0.10F, 180F, tick + partial);
            if (i % 100 == 0) {
                float step = tick < 95 ? 0.33F : 0.10F;
                prevSpeed = walkSpeed;
                prevPos = walkPos;
                walkSpeed += (Math.min(step * 4F, 1F) - walkSpeed) * 0.4F;
                walkPos += walkSpeed;
            }
            BotAnimator.Frame f = a.step(AiPose.IDLE, tick, partial, 0, false, tick + partial, 1);
            float ws = prevSpeed + (walkSpeed - prevSpeed) * partial, wp = prevPos + (walkPos - prevPos) * partial;
            float[] now = posed(new BotAnimator.Frame(f.crouch(), f.lean(), f.arms(), f.act(), f.fading(), f.hits(), f.idleSec(), 1, a.speed, a.heading, -1F), ws, wp);
            if (last != null) for (int k = 0; k < now.length / 6; k++) {
                float d = turnBetween(last, now, k);
                if (d > worst) {
                    worst = d;
                    where = String.format(java.util.Locale.ROOT, "%s at tick %.2f (speed %.3f heading %.2f): %.3f %.3f %.3f to %.3f %.3f %.3f", PARTS[k], tick + partial,
                            a.speed, a.heading, last[k * 6], last[k * 6 + 1], last[k * 6 + 2], now[k * 6], now[k * 6 + 1], now[k * 6 + 2]);
                }
            }
            last = now;
        }
        // the sprint's own shin swings 2.5 radians a tick at full speed (a frame of 0.01 tick: 0.025); a jump is more
        assertTrue(worst < 0.04F, "a part jumped: " + worst + " rad; " + where);
    }

    @Test
    void itsWalkKeepsVanillasPace() {
        // a step of 0.1 a tick for 40 ticks, frames 0.05 tick apart: vanilla's swing speed (0.4) and about its place
        BotAnimator a = new BotAnimator();
        float vSpeed = 0F, at30 = 0F;
        for (int tick = 0; tick < 40; tick++) {
            for (int i = 0; i < 20; i++) a.move(0, 0.1, 0F, tick + i / 20.0);
            vSpeed += (Math.min(0.1F * 4F, 1F) - vSpeed) * 0.4F;
            if (tick == 30) at30 = a.walkPos;
        }
        assertEquals(vSpeed, a.walk, 0.01F);
        // its legs go round as fast as vanilla's: its place goes on by vanilla's speed a tick
        assertEquals(vSpeed, (a.walkPos - at30) / 9F, 0.005F, "its swing's pace");
    }

    @Test
    void aHitOnTheRunOrWithAHitsOwnKnockbackIsAFlinch() {
        // running at 0.33 a tick (sent its motion, 0.18), then a hit's own knockback (0.4): neither throws it back
        for (float push : new float[] { 0.18F, 0.4F }) {
            BotAnimator a = new BotAnimator();
            for (int tick = 90; tick < 100; tick++) a.move(0, 0.33, 0F, tick);
            a.step(AiPose.IDLE, 100, 0F, 0, false, 100, 1);
            a.push = push;
            BotAnimator.Frame f = a.step(AiPose.IDLE, 101, 0F, 10, false, 101, 1);
            BotAnimator.Frame g = a.step(AiPose.IDLE, 102, 0F, 9, false, 102, 1);
            assertTrue(java.util.Arrays.stream(f.hits()).noneMatch(h -> h.clip().equals(BotClips.HIT_KNOCK)), "push " + push);
            assertTrue(java.util.Arrays.stream(g.hits()).noneMatch(h -> h.clip().equals(BotClips.HIT_KNOCK)), "push " + push);
        }
    }

    @Test
    void aTeleportIsNoStep() {
        BotAnimator a = new BotAnimator();
        for (int tick = 0; tick < 10; tick++) a.move(0, 0.2, 0F, tick);
        a.move(0, 30, 0F, 10);
        assertEquals(0F, a.speed, 1e-6F);
        assertEquals(0F, a.walk, 1e-6F);
    }

    @Test
    void reloadingCrouchedKeepsTheHeadAndBodyWhereTheCrouchPutsThem() {
        BotAnimator.Frame crouched = new BotAnimator.Frame(1F, 0F, HOLD, null, BotAnimator.NO_ACTS, BotAnimator.NO_HITS, 0F, 0, 0F, 0F, -1F);
        BotAnimator.Frame reloading = new BotAnimator.Frame(1F, 0F, HOLD, new BotAnimator.Act(AiPose.RELOAD, 0.5F, 1F, 15F), BotAnimator.NO_ACTS,
                BotAnimator.NO_HITS, 0F, 0, 0F, 0F, -1F);
        float[] a = posed(crouched, 0F, 0F), b = posed(reloading, 0F, 0F);
        // head (0) and body (1): pivots unmoved, the body's tilt kept
        for (int k : new int[] { 3, 4, 5, 6, 7, 8, 9, 10, 11 }) assertEquals(a[k], b[k], 1e-5F, "part value " + k);
        assertTrue(most(a, b) > 0.3F, "the reload moves the arms");
    }

    @Test
    void theHeadingIsFromWhereItFaces() {
        BotAnimator a = new BotAnimator();
        // facing +z (yaw 0), stepping toward -x: its right
        a.move(-0.1, 0, 0F, 0);
        assertEquals(Math.PI / 2, a.heading, 1e-3);
        // facing +x (yaw -90), stepping back toward -x
        BotAnimator b = new BotAnimator();
        b.move(-0.1, 0, -90F, 0);
        assertEquals(Math.PI, Math.abs(b.heading), 1e-3);
    }

    /** the angle between part k's turns in two poses (angles()), radians: as the stand measures it */
    static float turnBetween(float[] a, float[] b, int k) {
        org.joml.Quaternionf qa = new org.joml.Quaternionf().rotationZYX(a[k * 6 + 2], a[k * 6 + 1], a[k * 6]);
        org.joml.Quaternionf qb = new org.joml.Quaternionf().rotationZYX(b[k * 6 + 2], b[k * 6 + 1], b[k * 6]);
        return 2F * (float) Math.acos(Math.min(1F, Math.abs(qa.dot(qb))));
    }

    /** a run of frames 0.01 tick apart through the animator: the most a part moved between two, and where */
    static float stepped(BotAnimator a, java.util.function.IntUnaryOperator hurtAt, java.util.function.IntPredicate deadAt,
            java.util.function.IntFunction<Float> pushAt, int from, int to) {
        float worst = 0F;
        float[] last = null;
        where = "";
        for (int i = from * 100; i < to * 100; i++) {
            int tick = i / 100;
            float partial = (i % 100) / 100F;
            a.push = pushAt.apply(tick);
            boolean dead = deadAt.test(tick);
            BotAnimator.Frame f = a.step(AiPose.IDLE, tick, partial, hurtAt.applyAsInt(tick), dead, tick + partial, 1);
            int dt = 0;
            while (dead && deadAt.test(tick - dt - 1)) dt++;
            float death = dead ? (dt + partial) / 20F : -1F;
            float[] now = posed(new BotAnimator.Frame(f.crouch(), f.lean(), f.arms(), f.act(), f.fading(), f.hits(), f.idleSec(), 1, 0F, 0F, death), 0F, 0F);
            if (last != null) for (int k = 0; k < now.length / 6; k++) {
                float d = turnBetween(last, now, k);
                if (d > worst) {
                    worst = d;
                    where = String.format(java.util.Locale.ROOT, "%s at tick %.2f: %.3f %.3f %.3f to %.3f %.3f %.3f", PARTS[k], tick + partial, last[k * 6], last[k * 6 + 1],
                            last[k * 6 + 2], now[k * 6], now[k * 6 + 1], now[k * 6 + 2]);
                }
            }
            last = now;
        }
        return worst;
    }

    @Test
    void thrownBackThenHitAgainAndFallingDeadHasNoJump() {
        // thrown back at 100, hit again at 125 (still moving fast), dead from 126
        BotAnimator a = new BotAnimator();
        float worst = stepped(a, t -> t >= 125 ? Math.max(0, 10 - (t - 125)) : t >= 100 ? Math.max(0, 10 - (t - 100)) : 0, t -> t >= 126,
                t -> t >= 100 ? 1F : 0F, 90, 160);
        // a frame 0.01 tick long: the stand's bar (2 radians a tick) is 0.02 of a radian
        assertTrue(worst < 0.02F, "a part jumped: " + worst + " rad; " + where);
    }

    static float[] lying(float swim, float speed, float walkPos, float[] arms) {
        BotPoser.Parts p = BotPoserTest.parts();
        BotPoser.apply(p, new BotAnimator.Frame(0F, 0F, arms, null, BotAnimator.NO_ACTS, BotAnimator.NO_HITS, 0F, 0, speed, 0F, -1F), swim,
                Math.min(1F, speed * 4F), walkPos);
        return angles(p);
    }

    @Test
    void crawlingTheLegsAreDrawnUpInTurnAlongTheGroundAndTheHeadStays() {
        // AiProne.CRAWL_SPEED of the AI's walk (0.19): a quarter of a cycle on, the right leg is drawn up, three quarters
        // on the left one; both in the ground's plane (lying, model x-y: no turn about x), the shin along the body
        float cycle = 4F * BotPoser.CRAWL_STRIDE;
        float[] still = lying(1F, 0F, 0F, HOLD), right = lying(1F, 0.057F, cycle / 4F, HOLD), left = lying(1F, 0.057F, cycle * 3F / 4F, HOLD);
        int rl = 4 * 6, ll = 5 * 6, rs = 8 * 6, ls = 9 * 6;
        assertEquals(0.08F + BotPoser.CRAWL_HIP, right[rl + 2], 1e-3);
        assertEquals(-0.08F, right[ll + 2], 1e-3);
        assertEquals(-(0.08F + BotPoser.CRAWL_HIP), left[ll + 2], 1e-3);
        assertEquals(0F, right[rl], 1e-6);
        assertEquals(0.08F, right[rl + 2] + right[rs + 2], 1e-3);
        assertEquals(-0.08F, left[ll + 2] + left[ls + 2], 1e-3);
        // the head, the body and every pivot where they lie still: the eye the server keeps
        for (int k = 0; k < 6; k++) {
            assertEquals(still[k], right[k], 1e-6, "head " + k);
            assertEquals(still[6 + k], right[6 + k], 1e-6, "body " + k);
        }
        for (int part = 0; part < 10; part++)
            for (int k = 3; k < 6; k++) assertEquals(still[part * 6 + k], right[part * 6 + k], 1e-6, PARTS[part] + " pivot");
        // the arms on the gun swing aside with the pull; with no gun they stay where they lie
        assertEquals(still[2 * 6 + 2] + BotPoser.CRAWL_ARMS, right[2 * 6 + 2], 1e-3);
        float[] free = { 0F, 0F, 0F, 1F };
        assertEquals(lying(1F, 0F, 0F, free)[2 * 6 + 2], lying(1F, 0.057F, cycle / 4F, free)[2 * 6 + 2], 1e-6);
        // standing it does not crawl
        assertEquals(0F, BotPoser.crawlWeight(0F, 0.057F));
    }

    @Test
    void crawlingComesAndGoesWithNoJump() {
        // lying down on the move, crawling on, stopping, getting up: walkPos on by the crawl's own (4 speed a tick)
        java.util.function.Function<Float, float[]> at = t -> {
            float swim = BotAnimator.smooth(t / 10F) * (1F - BotAnimator.smooth((t - 150F) / 10F));
            float speed = 0.057F * (1F - BotAnimator.smooth((t - 100F) / 20F));
            return lying(swim, speed, 0.228F * Math.min(t, 110F), HOLD);
        };
        float share = jumpShare(at, 0F, 170F, 170 * 3);
        assertTrue(share < 0.25F, "a part jumped crawling: " + share + "; " + where);
    }

    /** an agent crouched (as the server keeps it sliding or rolling) through the dash's ticks from 100, posed at now */
    static float[] dashed(byte kind, float now) {
        BotAnimator a = new BotAnimator();
        AiPose crouch = new AiPose(AiPose.CROUCH, (byte) 0, (byte) 0, 0, AiPose.HOLD, AiPose.NONE, 0, (short) 0);
        AiPose pose = new AiPose(AiPose.CROUCH, (byte) 0, (byte) 0, 0, AiPose.HOLD, kind, 100, (short) acup.gunfeel.ai.AiDash.TICKS);
        // crouched from 90 (settled by 100), then a fifth of a tick a frame up to now
        BotAnimator.Frame f = null;
        for (float t = 90F; t <= now + 1e-4F; t += 0.2F)
            f = a.step(t >= 100F - 1e-4F ? pose : crouch, (long) (t + 1e-4F), (t + 1e-4F) % 1F, 0, false, t, 1);
        return posed(f, 0F, 0F);
    }

    @Test
    void slidingOrRollingTheHeadLeavesTheEyeOnlyForTheActionsTicks() {
        // pose-contract §4: crouched, the head where the crouch puts it (the eye the server keeps) but for the action's
        // ticks; from the first tick after, back within 3 cm (0.03 blocks); a dash's clips load
        for (byte kind : new byte[] { AiPose.SLIDE, AiPose.ROLL }) {
            assertNotNull(BotClips.get(kind == AiPose.SLIDE ? BotClips.SLIDE : BotClips.ROLL));
            float[] crouched = dashed(AiPose.NONE, 120F), mid = dashed(kind, 108F);
            float[] after = dashed(kind, 100F + acup.gunfeel.ai.AiDash.TICKS);
            float off = Math.max(Math.abs(mid[4] - crouched[4]), Math.abs(mid[5] - crouched[5]));
            assertTrue(off > 0.25F, kind + ": the head goes with the clip: " + off);
            for (int k = 3; k < 6; k++) assertEquals(crouched[k], after[k], 0.03F, kind + ": the head after, " + "XYZ".charAt(k - 3));
        }
    }

    /**
     * each part's turn as its matrix (9 numbers) and its pivot (blocks): a turn near a quarter turn about Y has angles
     * that swing far for a small turn (two sets of angles for one turn), so a turn's jump is told by its matrix
     */
    static float[] turns(BotPoser.Parts p) {
        ModelPart[] all = BotPoserTest.all(p);
        float[] out = new float[all.length * 12];
        org.joml.Matrix3f m = new org.joml.Matrix3f();
        for (int i = 0; i < all.length; i++) {
            m.rotationZYX(all[i].zRot, all[i].yRot, all[i].xRot);
            for (int c = 0; c < 3; c++)
                for (int r = 0; r < 3; r++) out[i * 12 + c * 3 + r] = m.get(c, r);
            out[i * 12 + 9] = all[i].x / 16F;
            out[i * 12 + 10] = all[i].y / 16F;
            out[i * 12 + 11] = all[i].z / 16F;
        }
        return out;
    }

    /** Frame.dashed as the animator has it: 1 through the dash (in within DASH_IN ticks, under its clip), going after */
    static float after(float tick, int len) {
        return tick < len ? Math.min(1F, tick / BotAnimator.DASH_IN) : Math.max(0F, 1F - (tick - len) / BotAnimator.DASH_OUT);
    }

    @Test
    void slidingOrRollingHasNoJump() {
        for (byte kind : new byte[] { AiPose.SLIDE, AiPose.ROLL }) {
            int len = acup.gunfeel.ai.AiDash.TICKS;
            java.util.function.Function<Float, float[]> at = tick -> {
                float in = Math.min(1F, tick / BotAnimator.DASH_IN), out = Math.min(1F, (len - tick) / BotAnimator.ACTION_FADE);
                BotAnimator.Act act = tick < len ? new BotAnimator.Act(kind, tick / len, BotAnimator.smooth(Math.min(in, out)), tick) : null;
                BotPoser.Parts p = BotPoserTest.parts();
                BotPoser.apply(p, new BotAnimator.Frame(1F, 0F, HOLD, act, BotAnimator.NO_ACTS, BotAnimator.NO_HITS, 0F, 0, 0.2F, 0F, -1F, 0F, 0F, 1F,
                        0F, after(tick, len)), 0F, 0F, 0F);
                return turns(p);
            };
            float share = jumpShare(at, 0F, len + 6, (len + 6) * 3, 12, "abcdefghiXYZ");
            assertTrue(share < 0.25F, kind + ": a part jumped: " + share + "; " + where);
        }
    }

    @Test
    void slidingOrRollingOverVanillasWalkingLegsHasNoJump() {
        // the stand's case: the run's legs (vanilla's swing, as setupAnim leaves them, at 0.19 a tick) under the
        // slide fading in and out; a slide's leg raised high is near half a turn from a leg swinging back
        for (byte kind : new byte[] { AiPose.SLIDE, AiPose.ROLL }) for (int phase = 0; phase < 8; phase++) {
            int len = acup.gunfeel.ai.AiDash.TICKS;
            float start = phase * BotPoser.CYCLE / 8F;
            java.util.function.Function<Float, float[]> at = tick -> {
                float in = Math.min(1F, tick / BotAnimator.DASH_IN), out = Math.min(1F, (len - tick) / BotAnimator.ACTION_FADE);
                BotAnimator.Act act = tick < len ? new BotAnimator.Act(kind, tick / len, BotAnimator.smooth(Math.min(in, out)), tick) : null;
                BotPoser.Parts p = BotPoserTest.parts();
                // HumanoidModel.setupAnim (26.2, javap): xRot cos(pos 0.6662) 1.4 speed, yRot and zRot +-0.005
                float walk = 0.76F, pos = start + walk * tick;
                p.rightLeg().setRotation(net.minecraft.util.Mth.cos(pos * 0.6662F) * 1.4F * walk, 0.005F, 0.005F);
                p.leftLeg().setRotation(net.minecraft.util.Mth.cos(pos * 0.6662F + (float) Math.PI) * 1.4F * walk, -0.005F, -0.005F);
                BotPoser.apply(p, new BotAnimator.Frame(1F, 0F, HOLD, act, BotAnimator.NO_ACTS, BotAnimator.NO_HITS, 0F, 0, 0.19F, 0F, -1F, 0F, 0F, 1F,
                        0F, after(tick, len)), 0F, walk, pos);
                return turns(p);
            };
            float share = jumpShare(at, 0F, len + 6, (len + 6) * 6, 12, "abcdefghiXYZ");
            assertTrue(share < 0.25F, kind + " phase " + phase + ": a part jumped over walking legs: " + share + "; " + where);
        }
    }
}
