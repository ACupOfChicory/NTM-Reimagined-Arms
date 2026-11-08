package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

// A slide and a roll: how long, how far, and only on the way it runs.
class AiDashTest {
    @Test
    void itLastsItsTicksAndNoLonger() {
        AiDash d = new AiDash();
        assertEquals(AiPose.NONE, d.kind(0));
        d.begin(AiPose.SLIDE, 100);
        assertEquals(AiPose.SLIDE, d.kind(100));
        assertEquals(AiPose.SLIDE, d.kind(100 + AiDash.TICKS - 1));
        assertEquals(AiPose.NONE, d.kind(100 + AiDash.TICKS));
        assertFalse(d.on(100 + AiDash.TICKS));
        assertEquals(1.0, d.share(100 + AiDash.TICKS), 0);
        d.begin(AiPose.ROLL, 300);
        assertEquals(AiDash.ROLL_STEP, d.share(305), 0);
        assertEquals(1, d.slides);
        assertEquals(1, d.rolls);
    }

    @Test
    void aSlideFromARunGoesAboutTwoAndAHalfBlocks() {
        // the AI's run, 0.32 a tick; its step a share of that (AiMover.slow: the share is of the step)
        AiDash d = new AiDash();
        d.begin(AiPose.SLIDE, 0);
        double gone = 0;
        for (int t = 0; t < AiDash.TICKS; t++) gone += 0.32 * d.share(t);
        assertTrue(gone > 2.0 && gone < 3.0, "gone " + gone);
        // dying away, never back up
        for (int t = 1; t < AiDash.TICKS; t++) assertTrue(d.share(t) < d.share(t - 1));
        // the slide starts within SLIDE_FAR of the cover: it gets there
        assertTrue(gone >= AiDash.SLIDE_NEAR);
    }

    @Test
    void itSlidesOnlyTheWayItRuns() {
        assertTrue(AiDash.heading(new Vec3(0.3, 0, 0), 2, 0.5));
        assertFalse(AiDash.heading(new Vec3(0.3, 0, 0), 0, 2));
        assertFalse(AiDash.heading(new Vec3(0.3, 0, 0), -2, 0));
        assertFalse(AiDash.heading(Vec3.ZERO, 2, 0));
    }
}
