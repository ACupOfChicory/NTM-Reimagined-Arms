package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// The smoke on a fall back: where between the bot and the threat it aims the cloud.
class AiSmokeTest {
    @Test
    void halfTheWayWithinItsBounds() {
        assertEquals(6, AiSmoke.aim(12), 1e-9);
        assertEquals(AiSmoke.MIN, AiSmoke.aim(AiSmoke.NEAR), 1e-9);
        assertEquals(AiSmoke.MAX, AiSmoke.aim(40), 1e-9);
    }

    @Test
    void theCloudStaysShortOfTheThreat() {
        // nearest the threat it may aim: the cloud's fan (3.5 blocks on) and its width short of it
        for (double d = AiSmoke.NEAR; d <= 40; d += 0.5) assertTrue(d - AiSmoke.aim(d) >= AiSmoke.NEAR - AiSmoke.MIN, "at " + d);
    }
}
