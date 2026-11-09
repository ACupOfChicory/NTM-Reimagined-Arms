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

    @Test
    void crouchedItStepsWithTheThighsLowAndAFootDown() {
        for (int i = 0; i < 40; i++) {
            float pos = BotPoser.CYCLE * i / 40F;
            BotPoser.Parts p = posed(1F, 0.25F, 0F, 1F, pos);
            assertTrue(Math.abs(p.rightLeg().xRot) <= BotPoser.SWING_MOST + 1e-4F && Math.abs(p.leftLeg().xRot) <= BotPoser.SWING_MOST + 1e-4F,
                    "at " + i / 40F + ": the thighs " + p.rightLeg().xRot + " " + p.leftLeg().xRot);
            float[] r = BotLegsReplayTest.foot(p.rightLeg(), p.rightShin()), l = BotLegsReplayTest.foot(p.leftLeg(), p.leftShin());
            assertTrue(Math.min(r[0], l[0]) <= 2F, "at " + i / 40F + ": the lower foot " + Math.min(r[0], l[0]) + " px up");
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
        // still, whatever the phase it stopped at: both shins as the thighs (no bend left by a step)
        for (int i = 0; i < 8; i++) {
            BotPoser.Parts p = posed(0F, 0F, 0F, 0F, BotPoser.CYCLE * i / 8F);
            assertEquals(0F, p.rightShin().xRot, 1e-3F, "at " + i / 8F);
            assertEquals(0F, p.leftShin().xRot, 1e-3F, "at " + i / 8F);
        }
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
