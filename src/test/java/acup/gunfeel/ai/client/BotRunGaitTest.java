package acup.gunfeel.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.Config;

// An AI mob's legs on the march (0.27 blocks a tick, a jog) and the run (0.32, a sprint): each its own clip whole
// over its speed going up and down, and the player's choice of legs (Config.botRunGait), vanilla's by default and at
// a walk.
class BotRunGaitTest {
    @AfterEach
    void back() {
        Config.botRunGait = "vanilla";
    }

    @Test
    void aPacesSpeedGoingUpAndDownKeepsItsClip() {
        // a march, 0.27 a tick: the jog's clip
        for (float speed = 0.255F; speed <= 0.289F; speed += 0.002F)
            assertEquals(1F, BotPoser.gaitWeights(speed, 0F)[1], 1e-6F, "jog's share at " + speed);
        // a run, 0.32 a tick: the sprint's
        for (float speed = 0.305F; speed <= 0.36F; speed += 0.005F)
            assertEquals(1F, BotPoser.gaitWeights(speed, 0F)[2], 1e-6F, "sprint's share at " + speed);
    }

    @Test
    void aNameItDoesNotKnowIsVanilla() {
        Config.botRunGait = "faster";
        assertEquals(1F, BotPoser.tempo(0.32F));
        assertEquals(1F, BotPoser.stride(0.32F));
    }

    /** walkPos gone in a tick at this step, after 40 ticks of it */
    static float pace(float step) {
        BotAnimator a = new BotAnimator();
        for (int tick = 0; tick < 40; tick++) a.move(0, step, 0F, tick);
        float was = a.walkPos;
        a.move(0, step, 0F, 40);
        return a.walkPos - was;
    }

    @Test
    void tempoSlowsTheRunsStepsOnly() {
        float walk = pace(0.19F), run = pace(0.32F);
        Config.botRunGait = "tempo";
        assertEquals(walk, pace(0.19F), 1e-6F, "a walk as vanilla's");
        assertEquals(run * BotPoser.TEMPO_RUN, pace(0.32F), 1e-4F, "a run's steps");
    }

    /** the legs from the clips alone (gait), all of the way: right thigh, right shin, left thigh, left shin */
    static float[] gaitLegs(float speed, float walkPos) {
        BotPoser.Parts p = BotPoserTest.parts();
        BotPoser.gait(p, speed, 0F, walkPos, 1F, new float[3]);
        return new float[] { p.rightLeg().xRot, p.rightShin().xRot, p.leftLeg().xRot, p.leftShin().xRot };
    }

    @Test
    void strideSwingsTheThighsWiderOnTheRunOnly() {
        float middle = BotPoser.STRIDE_MIDDLE * net.minecraft.util.Mth.DEG_TO_RAD;
        for (float pos = 0F; pos < BotPoser.CYCLE; pos += BotPoser.CYCLE / 16F) {
            float[] run = gaitLegs(0.32F, pos), walk = gaitLegs(0.19F, pos);
            Config.botRunGait = "stride";
            float[] wide = gaitLegs(0.32F, pos), walkWide = gaitLegs(0.19F, pos);
            Config.botRunGait = "vanilla";
            for (int b = 0; b < 4; b++) {
                float want = b % 2 == 0 ? middle + (run[b] - middle) * BotPoser.STRIDE_RUN : run[b];
                assertEquals(want, wide[b], 1e-4F, "leg " + b + " on the run at " + pos);
                assertEquals(walk[b], walkWide[b], 1e-6F, "leg " + b + " at a walk at " + pos);
            }
        }
    }

    @Test
    void vanillaChangesNothing() {
        for (float speed = 0F; speed < 0.5F; speed += 0.01F) {
            assertEquals(1F, BotPoser.tempo(speed));
            assertEquals(1F, BotPoser.stride(speed));
        }
    }
}
