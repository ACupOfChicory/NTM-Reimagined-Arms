package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// SpawnLerp: only an entity not yet ticked whose previous position is off its place is settled; once the renderer
// settles it itself, nothing is left to do.
class SpawnLerpTest {
    @Test
    void justAddedAtTheOrigin() {
        assertTrue(SpawnLerpBase.stale(0, 0, 0, 0, 2000.5, -52, 1994.5));
    }

    @Test
    void alreadySettled() {
        assertFalse(SpawnLerpBase.stale(0, 2000.5, -52, 1994.5, 2000.5, -52, 1994.5));
    }

    @Test
    void tickedEntitiesAreLeftAlone() {
        assertFalse(SpawnLerpBase.stale(1, 0, 0, 0, 2000.5, -52, 1994.5));
        assertFalse(SpawnLerpBase.stale(40, 10, 0, 0, 10.2, 0, 0));
    }
}
