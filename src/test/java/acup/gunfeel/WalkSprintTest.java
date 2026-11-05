package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WalkSprintTest {
    // the phase goes on with the steps on the ground and stands still in the air, however fast the player flies
    @Test
    void phaseOnlyOnTheGround() {
        float acc = 0F;
        acc = WalkSprint.groundStep(acc, true, 1F, 1.3F);
        assertEquals(0.3F, acc, 1e-6F);
        for (int i = 0; i < 100; i++) acc = WalkSprint.groundStep(acc, false, i, i + 1.5F);
        assertEquals(0.3F, acc, 1e-6F);
        acc = WalkSprint.groundStep(acc, true, 5F, 5.2F);
        assertEquals(0.5F, acc, 1e-6F);
    }

    // a jump of the walk distance (a new world, a respawn) and the first frame are not steps
    @Test
    void noStepFromAJump() {
        assertEquals(1F, WalkSprint.groundStep(1F, true, 0F, 40F));
        assertEquals(1F, WalkSprint.groundStep(1F, true, Float.NaN, 3F));
    }
}
