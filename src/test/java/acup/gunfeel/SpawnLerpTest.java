package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// SpawnLerp: only an entity not yet ticked whose previous position is off its place is settled; once the renderer
// settles it itself, nothing is left to do.
class SpawnLerpTest {
    @Test
    void justAddedAtTheOrigin() {
        assertTrue(SpawnLerp.stale(0, 0, 0, 0, 2000.5, -52, 1994.5));
    }

    @Test
    void alreadySettled() {
        assertFalse(SpawnLerp.stale(0, 2000.5, -52, 1994.5, 2000.5, -52, 1994.5));
    }

    @Test
    void tickedEntitiesAreLeftAlone() {
        assertFalse(SpawnLerp.stale(1, 0, 0, 0, 2000.5, -52, 1994.5));
        assertFalse(SpawnLerp.stale(40, 10, 0, 0, 10.2, 0, 0));
    }
}
