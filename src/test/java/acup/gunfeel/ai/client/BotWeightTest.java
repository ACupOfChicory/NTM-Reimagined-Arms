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
}
