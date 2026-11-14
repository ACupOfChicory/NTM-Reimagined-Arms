package acup.gunfeel.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.util.Mth;

// The legs on the move: the lower foot is on the ground, the clips' legs pass each other where vanilla's do,
// a stop ends its step, a dash aside steps aside from its start, and a crouched walk does not swing the thighs up.
class BotLegsTest {
    /** speed, heading: a walk, a jog, a sprint, back, both sides, and the diagonals */
    static final float[][] GAITS = { { 0.15F, 0F }, { 0.24F, 0F }, { 0.33F, 0F }, { 0.15F, Mth.PI }, { 0.15F, Mth.PI / 2F }, { 0.15F, -Mth.PI / 2F },
            { 0.15F, Mth.PI / 4F }, { 0.15F, Mth.PI * 0.75F }, { 0.24F, -Mth.PI * 0.75F } };

    static final float JOG = BotPoser.JOG_FROM;

    static BotAnimator.Frame frame(float crouch, float speed, float heading) {
        return new BotAnimator.Frame(crouch, 0F, BotMoveClipsTest.HOLD, null, BotAnimator.NO_ACTS, BotAnimator.NO_HITS, 0F, 0, speed, heading, -1F);
    }

    /** posed as the game does: vanilla's swing on the legs, then the poser */
    static BotPoser.Parts posed(float crouch, float speed, float heading, float walk, float walkPos) {
        BotPoser.Parts p = BotPoserTest.parts();
        float swing = Mth.cos(walkPos * 0.6662F) * 1.4F * walk;
        p.rightLeg().xRot = swing;
        p.leftLeg().xRot = -swing;
        BotPoser.apply(p, frame(crouch, speed, heading), 0F, walk, walkPos);
        return p;
    }

    @Test
    void theClipsLegsPassWhereVanillasDo() {
        for (float[] g : GAITS) {
            BotPoser.Parts p = posed(0F, g[0], g[1], 1F, BotPoser.CYCLE / 4F);
            float apart = Math.abs(p.rightLeg().xRot - p.leftLeg().xRot) * Mth.RAD_TO_DEG;
            assertTrue(apart < 3F, "speed " + g[0] + " heading " + g[1] + ": the thighs " + apart + " deg apart");
            // and it is the pass with the right leg going forward (its xRot down through the left's), not the other
            BotPoser.Parts before = posed(0F, g[0], g[1], 1F, BotPoser.CYCLE * 0.2F), after = posed(0F, g[0], g[1], 1F, BotPoser.CYCLE * 0.3F);
            assertTrue(before.rightLeg().xRot > before.leftLeg().xRot && after.rightLeg().xRot < after.leftLeg().xRot,
                    "speed " + g[0] + " heading " + g[1] + ": the right leg not going forward through the pass");
        }
    }

    @Test
    void theLowerFootIsOnTheGround() {
        // through a step: the lower foot's height and its shin's slant at most and on average. A walk ahead: on the
        // ground all through; a jog's and a sprint's strides and a strafe's legs turned aside (their clips' hips stay
        // up, and a knee bends only one way) lift it a little at times
        for (float[] g : GAITS) {
            float most = 0F, mean = 0F, slant = 0F, slantMean = 0F;
            int n = 80;
            for (int i = 0; i < n; i++) {
                BotPoser.Parts p = posed(0F, g[0], g[1], Math.min(1F, g[0] * 4F), BotPoser.CYCLE * i / n);
                float[] r = BotLegsReplayTest.foot(p.rightLeg(), p.rightShin()), l = BotLegsReplayTest.foot(p.leftLeg(), p.leftShin());
                float[] low = r[0] <= l[0] ? r : l;
                most = Math.max(most, low[0]);
                mean += low[0] / n;
                slant = Math.max(slant, low[1]);
                slantMean += low[1] / n;
            }
            String at = "speed " + g[0] + " heading " + g[1] + ": the lower foot up " + most + " px at most, " + mean + " on average; its shin "
                    + slant + " deg at most, " + slantMean + " on average";
            assertTrue(most <= 3F && mean <= 1.6F && slantMean <= 15F, at);
            if (g[0] < JOG && g[1] == 0F) assertTrue(most <= 1F && slant <= 25F, at);
        }
    }

    /** the lowest point of a leg's two boxes over the ground, px (below it: under the ground) */
    static float lowest(net.minecraft.client.model.geom.ModelPart thigh, net.minecraft.client.model.geom.ModelPart shin) {
        return Math.min(BotDeathTest.box(BotDeathTest.frame(thigh), -2, 0, -2, 4, 6, 4)[0],
                BotDeathTest.box(BotDeathTest.frame(thigh).mul(BotDeathTest.frame(shin)), -2, 0, -2, 4, 6, 4)[0]);
    }

    /** the knee over the ground, px */
    static float knee(net.minecraft.client.model.geom.ModelPart thigh) {
        return BotPoser.GROUND - BotDeathTest.at(BotDeathTest.frame(thigh), 0F, 6F, 0F).y;
    }

    @Test
    void crouchedOnTheMoveItWalksBentLowWithAFootDown() {
        // the clip's walk bent low, its legs fitted under the head: through the step a foot is on the ground (within a
        // pixel: the clip's own passing of the feet) and none under it, the knees bend, and the head, the trunk's top and the shoulders are where vanilla's crouch has them
        float bent = 0F;
        for (float speed : new float[] { 0.08F, 0.19F, 0.25F })
            for (int i = 0; i < 40; i++) {
                BotPoser.Parts p = posed(1F, speed, 0F, 1F, BotPoser.CYCLE * i / 40F);
                float r = lowest(p.rightLeg(), p.rightShin()), l = lowest(p.leftLeg(), p.leftShin());
                String at = "speed " + speed + " at " + i / 40F + ": the legs' lowest points " + r + " and " + l + " px over the ground";
                assertTrue(Math.min(r, l) <= 1F && Math.min(r, l) >= -0.1F, at);
                bent = Math.max(bent, Math.max(p.rightShin().xRot, p.leftShin().xRot));
                assertEquals(4.2F, p.head().y, 1e-4F);
                assertEquals(0F, p.head().z, 1e-4F);
                assertEquals(3.2F, p.body().y, 1e-4F);
                assertEquals(2F + acup.gunfeel.ai.AiPoseGeometry.CROUCH_ARM_DROP_PX, p.rightArm().y, 1e-4F);
                // the hips: where the trunk ends, within a pixel and a half (the clip's pelvis tips and sways)
                org.joml.Vector3f hips = BotDeathTest.at(BotDeathTest.frame(p.body()), 0F, 12F, 0F);
                float off = (float) Math.hypot((p.rightLeg().y + p.leftLeg().y) / 2F - hips.y, (p.rightLeg().z + p.leftLeg().z) / 2F - hips.z);
                assertTrue(off <= 1.5F, "speed " + speed + " at " + i / 40F + ": the legs " + off + " px off the trunk's end");
            }
        assertTrue(bent > 1.5F, "the knees bend " + bent + " rad at most");
    }

    /**
     * Through a step crouched at this heading, the share of ticks the foot on the ground (the lower, the same foot as
     * the tick before, moving) goes with the way the mob goes, not against it: a foot on the ground goes back from the
     * way it walks, so with it the legs walk one way and the mob goes the other.
     */
    static float footWithTheWay(float heading) {
        return footWithTheWay(1F, 0.12F, heading);
    }

    static float footWithTheWay(float crouch, float speed, float heading) {
        // the way it goes in the model: ahead is -z, its right -x
        float mx = -Mth.sin(heading), mz = -Mth.cos(heading);
        int n = 120, with = 0, counted = 0;
        org.joml.Vector3f was = null;
        boolean wasRight = false;
        for (int i = 0; i <= n; i++) {
            BotPoser.Parts p = posed(crouch, speed, heading, Math.min(1F, speed * 4F), BotPoser.CYCLE * i / n);
            float r = lowest(p.rightLeg(), p.rightShin()), l = lowest(p.leftLeg(), p.leftShin());
            boolean right = r <= l;
            org.joml.Vector3f foot = right ? BotLegsReplayTest.footAt(p.rightLeg(), p.rightShin()) : BotLegsReplayTest.footAt(p.leftLeg(), p.leftShin());
            if (was != null && right == wasRight) {
                float vx = foot.x - was.x, vz = foot.z - was.z, v = (float) Math.hypot(vx, vz);
                if (v > 0.05F) {
                    counted++;
                    if ((vx * mx + vz * mz) / v > 0.5F) with++;
                }
            }
            was = foot;
            wasRight = right;
        }
        assertTrue(counted > n / 3, "heading " + heading + ": the foot on the ground moved in " + counted + " of " + n + " ticks");
        return with / (float) counted;
    }

    @Test
    void crouchedTheFootOnTheGroundGoesAgainstTheWayItGoes() {
        // ahead, back, and back on a slant: the foot on the ground goes back from the way it walks (it was played on
        // going back too: the legs walked ahead while it went back)
        for (float heading : new float[] { 0F, Mth.PI, Mth.PI * 0.8F, -Mth.PI * 0.8F }) {
            float with = footWithTheWay(heading);
            assertTrue(with < 0.1F, "heading " + heading + ": the foot on the ground goes with the way it goes " + with * 100F + "% of the ticks");
        }
        BotPoser.crouchBack = false;
        try {
            float with = footWithTheWay(Mth.PI);
            assertTrue(with > 0.6F, "before: going back the foot on the ground went with it " + with * 100F + "% of the ticks");
        } finally {
            BotPoser.crouchBack = true;
        }
    }

    @Test
    void crouchedGoingAsideTheLegsStillStep() {
        // played on and played backwards half and half (their steps at odds) the thighs would hardly swing: going aside
        // and on a slant they step as wide as going ahead or back
        float ahead = swing(0F);
        for (float heading : new float[] { Mth.PI / 2F, -Mth.PI / 2F, Mth.PI * 0.6F, Mth.PI * 0.65F, -Mth.PI * 0.65F, Mth.PI })
            assertTrue(swing(heading) > 0.8F * ahead, "heading " + heading + ": the thighs swing " + swing(heading) + " deg, going ahead " + ahead);
    }

    /** crouched on the move at this heading: how far the right thigh swings through a step, degrees */
    static float swing(float heading) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < 60; i++) {
            float x = posed(1F, 0.12F, heading, 1F, BotPoser.CYCLE * i / 60F).rightLeg().xRot * Mth.RAD_TO_DEG;
            lo = Math.min(lo, x);
            hi = Math.max(hi, x);
        }
        return hi - lo;
    }

    @Test
    void crouchedAtAStandItIsDownOnAKnee() {
        for (float walkPos : new float[] { 0F, 2F, 5F }) {
            BotPoser.Parts p = posed(1F, 0F, 0F, 0F, walkPos);
            float r = lowest(p.rightLeg(), p.rightShin()), l = lowest(p.leftLeg(), p.leftShin());
            assertEquals(0F, r, 0.05F, "the right leg on the ground");
            assertEquals(0F, l, 0.05F, "the left leg on the ground");
            assertTrue(knee(p.rightLeg()) < 3.5F, "the right knee " + knee(p.rightLeg()) + " px up: down");
            assertTrue(knee(p.leftLeg()) > 5.5F, "the left knee " + knee(p.leftLeg()) + " px up: its foot ahead under it");
            org.joml.Vector3f rf = BotLegsReplayTest.footAt(p.rightLeg(), p.rightShin()), lf = BotLegsReplayTest.footAt(p.leftLeg(), p.leftShin());
            assertTrue(lf.z < -1.5F && rf.z > 6F, "the left foot ahead at " + lf.z + ", the right back at " + rf.z);
            // nothing of the legs further ahead than the face (4 px), so it kneels behind what it stands behind
            float ahead = Math.min(BotDeathTest.at(BotDeathTest.frame(p.leftLeg()), 0F, 6F, -2F).z, lf.z - 2F);
            assertTrue(ahead >= -5.5F, "the left leg " + -ahead + " px ahead");
            assertEquals(4.2F, p.head().y, 1e-4F);
            assertEquals(0F, p.head().z, 1e-4F);
            // the trunk nearly upright, its end at the hips
            assertEquals(Math.toRadians(10), p.body().xRot, 0.01);
            org.joml.Vector3f hips = BotDeathTest.at(BotDeathTest.frame(p.body()), 0F, 12F, 0F);
            assertEquals(hips.y, p.rightLeg().y, 0.1F);
            assertEquals(hips.z, p.rightLeg().z, 0.15F);
        }
    }

    @Test
    void crouchedStartingOrStoppingNoLegGoesIntoTheGround() {
        // between down on a knee and the walk bent low (0.01 to 0.05 blocks a tick) the two are mixed, and a mix of two
        // poses that each stand on the ground does not: the legs are lifted to it
        for (float speed = 0.01F; speed <= 0.0501F; speed += 0.004F)
            for (int i = 0; i < 16; i++) {
                BotPoser.Parts p = posed(1F, speed, 0F, Math.min(1F, speed * 4F), BotPoser.CYCLE * i / 16F);
                float r = lowest(p.rightLeg(), p.rightShin()), l = lowest(p.leftLeg(), p.leftShin());
                assertTrue(Math.min(r, l) >= -0.05F && Math.min(r, l) <= 1.5F,
                        "speed " + speed + " at " + i / 16F + ": the legs' lowest points " + r + " and " + l + " px over the ground");
                assertEquals(4.2F, p.head().y, 1e-4F);
            }
    }

    @Test
    void theCrouchComesAndGoesWithTheFeetNearTheGround() {
        // standing up or going down, at a stand and walking: the legs' lowest point never far off the ground, never
        // deep under it (4 ticks of it)
        for (float speed : new float[] { 0F, 0.19F })
            for (float c = 0F; c <= 1F; c += 0.05F)
                for (int i = 0; i < 8; i++) {
                    BotPoser.Parts p = posed(c, speed, 0F, Math.min(1F, speed * 4F), BotPoser.CYCLE * i / 8F);
                    float low = Math.min(lowest(p.rightLeg(), p.rightShin()), lowest(p.leftLeg(), p.leftShin()));
                    assertTrue(low <= 1.6F && low >= -1.6F, "crouch " + c + " speed " + speed + " at " + i / 8F + ": the lower leg " + low + " px over the ground");
                }
    }

    @Test
    void aStopEndsItsStep() {
        // from every point of a step: a run, then it stops; the legs end where they pass each other and stay there
        for (int k = 0; k < 12; k++) {
            BotAnimator a = new BotAnimator();
            int t = 0;
            for (; t < 20 + k; t++) a.move(0, 0.2, 0F, t);
            float was = 0F;
            for (int n = 0; n < 30; n++, t++) {
                was = a.walkPos;
                a.move(0, 0, 0F, t);
            }
            assertEquals(0F, Mth.cos(a.walkPos * 0.6662F), 0.05F, "stopped after " + (20 + k) + " ticks");
            assertEquals(was, a.walkPos, 1e-3F, "still going after " + (20 + k) + " ticks");
        }
    }

    @Test
    void aTickWithNoStepInARunKeepsItsPace() {
        BotAnimator a = new BotAnimator(), b = new BotAnimator();
        int t = 0;
        for (; t < 30; t++) {
            a.move(0, 0.2, 0F, t);
            b.move(0, 0.2, 0F, t);
        }
        // a late packet: no step this tick, two the next
        a.move(0, 0, 0F, t);
        b.move(0, 0.2, 0F, t++);
        a.move(0, 0.4, 0F, t);
        b.move(0, 0.2, 0F, t++);
        assertEquals(b.walkPos, a.walkPos, 0.5F);
    }

    @Test
    void standingStillBothFeetStand() {
        // still and calm, whatever the phase it stopped at: both shins as the thighs (no bend left by a step), the
        // feet together
        for (int i = 0; i < 8; i++) {
            BotPoser.Parts p = BotPoserTest.parts();
            BotPoser.apply(p, BotMoveClipsTest.calm(0F, 0F), 0F, 0F, BotPoser.CYCLE * i / 8F);
            assertEquals(0F, p.rightShin().xRot, 1e-3F, "at " + i / 8F);
            assertEquals(0F, p.leftShin().xRot, 1e-3F, "at " + i / 8F);
            assertEquals(0F, p.rightLeg().xRot, 1e-3F, "at " + i / 8F);
        }
    }

    @Test
    void inAFightItStandsWithAFootAheadAndAFootBack() {
        for (int i = 0; i < 8; i++) {
            BotPoser.Parts p = posed(0F, 0F, 0F, 0F, BotPoser.CYCLE * i / 8F);
            org.joml.Vector3f rf = BotLegsReplayTest.footAt(p.rightLeg(), p.rightShin()), lf = BotLegsReplayTest.footAt(p.leftLeg(), p.leftShin());
            assertTrue(rf.z - lf.z > 4F, "the feet " + (rf.z - lf.z) + " px apart, the left ahead");
            float r = lowest(p.rightLeg(), p.rightShin()), l = lowest(p.leftLeg(), p.leftShin());
            assertTrue(Math.abs(r) <= 0.4F && Math.abs(l) <= 0.4F, "the legs' lowest points " + r + " and " + l + " px over the ground");
            assertEquals(0F, new org.joml.Vector3f(p.head().x, p.head().y, p.head().z).length(), 1e-4F, "the head where it was");
        }
        // walking, crouched or calm: no stance
        assertEquals(0F, posed(1F, 0F, 0F, 0F, 0F).rightLeg().xRot, 0.05F);
        BotPoser.Parts walking = posed(0F, 0.15F, 0F, 0.6F, BotPoser.CYCLE / 4F);
        assertTrue(Math.abs(walking.rightLeg().xRot - walking.leftLeg().xRot) < 0.06F, "walking, the legs pass each other as the clips have them");
    }

    @Test
    void inTheAirTheLegsAreTheJumps() {
        BotClip jump = BotClips.get(BotClips.JUMP);
        float[] want = new float[3];
        BotAnimator.Frame f = new BotAnimator.Frame(0F, 0F, BotMoveClipsTest.HOLD, null, BotAnimator.NO_ACTS, BotAnimator.NO_HITS, 0F, 0, 0.1F, 0F, -1F,
                0.4F, 0F, 1F, 1F);
        BotPoser.Parts p = BotPoserTest.parts();
        BotPoser.apply(p, f, 0F, 0.4F, 0F);
        for (String bone : BotPoser.LEGS) {
            jump.rotation(bone, 0F, want);
            assertEquals(want[0] * Mth.DEG_TO_RAD, p.get(bone).xRot, 1e-3F, bone);
        }
        assertTrue(p.leftShin().xRot > 0.5F, "a knee drawn up: the left shin bent " + p.leftShin().xRot);
        // stepping down a slab or a stair (4 ticks off the ground, never going up) the legs keep their step
        BotAnimator a = new BotAnimator();
        a.fly(false, 0, 0);
        for (int t = 1; t <= 4; t++) a.fly(true, -0.1, t);
        assertEquals(0F, a.air, 1e-6F);
        a.fly(false, 0, 5);
        // a jump (going up) brings them in at once, and they stay in over its top and on its way down
        a.fly(true, 0.42, 6);
        assertTrue(a.air > 0F, "the first tick of a jump: " + a.air);
        a.fly(true, 0.33, 7);
        a.fly(true, 0.25, 8);
        a.fly(true, 0.0005, 9);
        for (int t = 10; t <= 13; t++) a.fly(true, -0.2, t);
        assertEquals(1F, a.air, 1e-6F);
        a.fly(false, 0, 14);
        assertTrue(a.air < 1F && a.air > 0F, "landing, the legs come back over a few ticks: " + a.air);
        for (int t = 15; t <= 18; t++) a.fly(false, 0, t);
        assertEquals(0F, a.air, 1e-6F);
        // a fall off a ledge: from the fifth tick of it
        for (int t = 19; t <= 22; t++) a.fly(true, -0.3, t);
        assertEquals(0F, a.air, 1e-6F);
        a.fly(true, -0.5, 23);
        assertTrue(a.air > 0F, "a fall of a block and more: " + a.air);
        // a mob the client has just begun to see, standing still: the game has it off the ground till its first move
        // comes; it stands
        BotAnimator seen = new BotAnimator();
        for (int t = 0; t <= 60; t++) {
            seen.fly(true, 0, t);
            assertEquals(0F, seen.air, 1e-6F, "standing still, said to be off the ground, at tick " + t);
        }
        // and one first seen falling is falling
        BotAnimator falling = new BotAnimator();
        falling.fly(true, -0.4, 0);
        assertEquals(1F, falling.air, 1e-6F);
    }

    @Test
    void aDashAsideStepsAsideFromItsStart() {
        BotAnimator a = new BotAnimator();
        int t = 0;
        for (; t < 20; t++) a.move(0, 0.2, 0F, t);
        for (; t < 50; t++) a.move(0, 0, 0F, t);
        // facing +z, stepping to -x: its right, at once
        a.move(-0.2, 0, 0F, t++);
        assertEquals(Mth.PI / 2F, a.heading, 0.2F);
        // and on the move, a turn from ahead to aside within a few ticks
        BotAnimator b = new BotAnimator();
        t = 0;
        for (; t < 20; t++) b.move(0, 0.2, 0F, t);
        for (int n = 0; n < 4; n++) b.move(-0.2, 0, 0F, t++);
        assertEquals(Mth.PI / 2F, b.heading, 0.3F);
    }
}
