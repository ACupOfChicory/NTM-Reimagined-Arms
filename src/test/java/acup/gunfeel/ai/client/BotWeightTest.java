package acup.gunfeel.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The body's weight drawn over the clips (anim-feel stage 2): no part of it goes over in a frame */
class BotWeightTest {
    static final double FRAME = 1.0 / 3.0;

    @Test
    void theGaitGoesOverInAFewFramesNotOne() {
        // a sprint slowing to a walk in a third of a second, as the bot stand's run turning round does: the speed's
        // own gait goes from a sprint to a jog within a frame or two (a band of 0.015), the gait drawn over several
        BotAnimator a = new BotAnimator();
        float most = 0F, mostOwn = 0F, last = Float.NaN, lastOwn = Float.NaN;
        for (int i = 0; i < 60 * 3; i++) {
            double t = i * FRAME;
            double s = t < 20 ? 0.335 : t < 27 ? 0.335 - (t - 20) / 7 * 0.145 : 0.19;
            a.move(0.0, s, 0F, t);
            float own = BotPoser.gaitOf(a.speed);
            if (!Float.isNaN(last)) {
                most = Math.max(most, Math.abs(a.gait - last));
                mostOwn = Math.max(mostOwn, Math.abs(own - lastOwn));
            }
            last = a.gait;
            lastOwn = own;
        }
        assertTrue(mostOwn > 0.3F, "the speed's gait went over in a frame by " + mostOwn);
        assertTrue(most < 0.12F, "the gait drawn went over by " + most + " in a frame");
        // and comes to the walk's
        assertEquals(BotPoser.gaitOf(a.speed), a.gait, 0.01F);
    }

    @Test
    void theGaitsSharesAreTheSpeeds() {
        for (float speed = 0F; speed < 0.4F; speed += 0.005F) {
            float[] w = BotPoser.gaitWeights(speed, 0.3F), g = BotPoser.gaitShares(BotPoser.gaitOf(speed), 0.3F);
            for (int i = 0; i < w.length; i++) assertEquals(w[i], g[i], 1e-6F, "at " + speed);
        }
    }

    @Test
    void aHitsPushTurnsTheTrunkAboutTheNeck() {
        // the trunk bent 0.1 rad back by a push: the head where it was, the legs' roots where the body's bottom went
        BotPoser.Parts p = BotPoserTest.parts();
        float headX = p.head().x, headY = p.head().y, headZ = p.head().z, legY = p.rightLeg().y, legZ = p.rightLeg().z;
        BotPoser.kick(p, new float[] { 0.1F, 0F, 0F, 0F, 0F, 0F }, 1F);
        assertEquals(0.1F, p.body().xRot, 1e-6F);
        assertEquals(headX, p.head().x, 0F);
        assertEquals(headY, p.head().y, 0F);
        assertEquals(headZ, p.head().z, 0F);
        assertEquals(legZ + 12F * (float) Math.sin(0.1), p.rightLeg().z, 1e-4F);
        assertEquals(legY + 12F * ((float) Math.cos(0.1) - 1F), p.rightLeg().y, 1e-4F);
        assertEquals(p.rightLeg().z, p.leftLeg().z, 1e-6F);
        // none, or a weight of nought: nothing moves
        BotPoser.Parts q = BotPoserTest.parts();
        BotPoser.kick(q, null, 1F);
        BotPoser.kick(q, new float[] { 0.1F, 0.1F, 0.1F, 0F, 0F, 0F }, 0F);
        assertEquals(0F, q.body().xRot, 0F);
        assertEquals(12F, q.rightLeg().y, 0F);
    }

    /** a run straight ahead (+z, yaw 0) at {@code s} blocks a tick for {@code ticks}, frame by frame from {@code t} */
    static double run(BotAnimator a, double t, double ticks, double s, float[] most) {
        for (double end = t + ticks; t < end; t += FRAME) {
            a.move(0.0, s, 0F, t);
            most[0] = Math.max(most[0], a.tiltX);
            most[1] = Math.min(most[1], a.tiltX);
        }
        return t;
    }

    @Test
    void startingOffTheChestGoesAheadThenBackOverTheFeet() {
        BotAnimator a = new BotAnimator();
        float[] most = { 0F, 0F };
        double t = run(a, 0.0, 20.0, 0.0, most);
        assertEquals(0F, a.tiltX, 0F, "stood still: no lean");
        assertEquals(0F, a.tiltZ, 0F);
        t = run(a, t, 40.0, 0.19, most);
        assertTrue(most[0] > 0.2F, "the chest ahead starting off: " + most[0]);
        assertTrue(most[0] <= BotAnimator.LEAN_AHEAD_MOST, "no further than people's: " + most[0]);
        assertEquals(0F, a.tiltX, 0.02F, "at a steady run back over the feet");
        assertEquals(0F, a.tiltZ, 1e-4F, "straight ahead: no lean aside");
    }

    @Test
    void atAWalkItLeansAsLittleAsPeopleDo() {
        // people starting off at a walk lean some 5 degrees ahead, pulling up 4 back: the bot at its walk (0.1) no
        // more than the run's most by the square of its share of the run's pace
        BotAnimator a = new BotAnimator();
        float[] most = { 0F, 0F };
        double t = run(a, 0.0, 20.0, 0.0, most);
        t = run(a, t, 40.0, 0.1, most);
        t = run(a, t, 40.0, 0.0, most);
        float share = 0.1F / BotAnimator.LEAN_PACE;
        assertTrue(most[0] > 0.03F && most[0] <= BotAnimator.LEAN_AHEAD_MOST * share * share + 1e-4F, "ahead " + most[0]);
        // back: the slow spring it settles on stood still may take it a little past (people: 13 % of the way)
        assertTrue(most[1] < -0.01F && most[1] >= -BotAnimator.LEAN_BACK_MOST * share * share * 1.15F, "back " + most[1]);
    }

    @Test
    void slowingAsTheGameSlowsItItLeansBackAsPeopleDo() {
        // the server's run slowing to a stand: each tick half the last's (0.198, 0.108, 0.059 ..), the speed drawn
        // falling over half a second: back by people's 6.6 degrees or so (3.1 .. 12.6), not by a degree
        BotAnimator a = new BotAnimator();
        float[] most = { 0F, 0F };
        double t = run(a, 0.0, 20.0, 0.0, most);
        t = run(a, t, 60.0, 0.198, most);
        most[0] = most[1] = 0F;
        float s = 0.198F;
        for (int tick = 0; tick < 40; tick++) {
            s = s > 0.005F ? s * 0.55F : 0F;
            t = run(a, t, 1.0, s, most);
        }
        assertTrue(most[1] < -0.054F && most[1] >= -BotAnimator.LEAN_BACK_MOST, "back " + most[1]);
    }

    @Test
    void aServerFallingBehindDoesNotRockIt() {
        // a steady run whose steps come as a server falling behind sends them (the bot stand under load): two in a tick
        // and none in the next, now and then a short one; on average the run's pace. It leans no more than 5 degrees
        BotAnimator a = new BotAnimator();
        float[] most = { 0F, 0F };
        double t = run(a, 0.0, 20.0, 0.0, most);
        t = run(a, t, 40.0, 0.19, most);
        most[0] = most[1] = 0F;
        float[] steps = { 0.38F, 0F, 0.38F, 0F, 0.23F, 0.135F, 0F, 0.38F, 0.19F, 0.19F, 0.305F, 0F, 0.265F, 0.38F, 0F, 0.14F };
        for (int tick = 0; tick < 120; tick++) t = run(a, t, 1.0, steps[tick % steps.length], most);
        assertTrue(most[0] < 0.087F && most[1] > -0.087F, "rocked from " + most[1] + " to " + most[0]);
    }

    @Test
    void aSprintFromAServerFallingBehindDoesNotRockIt() {
        // a sprint's steps none in a tick and two in the next (late, then caught up: 0, 0.66): no put, no start
        BotAnimator a = new BotAnimator();
        float[] most = { 0F, 0F };
        double t = run(a, 0.0, 20.0, 0.0, most);
        t = run(a, t, 40.0, 0.33, most);
        most[0] = most[1] = 0F;
        for (int tick = 0; tick < 120; tick++) t = run(a, t, 1.0, tick % 2 == 0 ? 0.0 : 0.66, most);
        assertTrue(most[0] < 0.087F && most[1] > -0.087F, "rocked from " + most[1] + " to " + most[0]);
    }

    @Test
    void firstSeenRunningItIsNotStartingOff() {
        // out of sight a while and back running: it goes on as it goes, no start's lean
        BotAnimator a = new BotAnimator();
        float[] most = { 0F, 0F };
        double t = run(a, 0.0, 20.0, 0.0, most);
        t = run(a, t + 100.0, 40.0, 0.2, most);
        assertEquals(0F, most[0], 0F);
        assertEquals(0F, most[1], 0F);
    }

    @Test
    void comeToRestItIsUprightAndLeavesTheFrameAlone() {
        BotAnimator a = new BotAnimator();
        float[] most = { 0F, 0F };
        double t = run(a, 0.0, 20.0, 0.0, most);
        t = run(a, t, 40.0, 0.19, most);
        t = run(a, t, 200.0, 0.0, most);
        assertEquals(0F, a.tiltX, 0F);
        assertEquals(0F, a.tiltZ, 0F);
    }

    @Test
    void putTwoBlocksOffItDoesNotLean() {
        // the client takes a mob put two blocks off in three steps of 0.67: no run to lean into
        BotAnimator a = new BotAnimator();
        float[] most = { 0F, 0F };
        double t = run(a, 0.0, 20.0, 0.0, most);
        t = run(a, t, 3.0, 0.67, most);
        t = run(a, t, 20.0, 0.0, most);
        assertEquals(0F, most[0], 0F);
        assertEquals(0F, most[1], 0F);
    }

    @Test
    void pullingUpItLeansBackThenSettlesOverALittle() {
        BotAnimator a = new BotAnimator();
        float[] most = { 0F, 0F };
        double t = run(a, 0.0, 20.0, 0.0, most);
        t = run(a, t, 60.0, 0.19, most);
        most[0] = most[1] = 0F;
        t = run(a, t, 10.0, 0.0, most);
        assertTrue(most[1] < -0.1F, "back pulling up: " + most[1]);
        assertTrue(most[1] >= -BotAnimator.LEAN_BACK_MOST, "no further than people's: " + most[1]);
        // stood still it goes back over a little past upright, then comes to it
        float over = 0F;
        for (double end = t + 60.0; t < end; t += FRAME) {
            a.move(0.0, 0.0, 0F, t);
            over = Math.max(over, a.tiltX);
        }
        assertTrue(over > 0.005F && over < 0.3F * -most[1], "settling over by " + over + " of " + most[1]);
        assertEquals(0F, a.tiltX, 0.01F, "settled");
    }

    @Test
    void inATurnItLeansIn() {
        for (float way : new float[] { 1F, -1F }) {
            // running round at 90 degrees a second, the body facing the way it goes
            BotAnimator a = new BotAnimator();
            float yaw = 0F, aside = 0F;
            for (double t = 0.0; t < 40.0; t += FRAME) {
                if (t > 10.0) yaw += way * 90F / 20F * (float) FRAME;
                double r = Math.toRadians(yaw);
                a.move(-Math.sin(r) * 0.19, Math.cos(r) * 0.19, yaw, t);
                if (t > 30.0) aside = a.tiltZ;
            }
            // the centre of the turn: where the way it goes turns to, at its side
            double turnTo = Math.toRadians(yaw + way * 90F);
            float[] centre = BotAnimator.toModel(-Math.sin(turnTo), Math.cos(turnTo), yaw);
            assertTrue(Math.signum(aside) == Math.signum(centre[0]), "leant to " + aside + ", the centre at " + centre[0]);
            assertTrue(Math.abs(aside) > 0.05F && Math.abs(aside) <= BotAnimator.LEAN_SIDE_MOST, "aside " + aside);
        }
    }
}
