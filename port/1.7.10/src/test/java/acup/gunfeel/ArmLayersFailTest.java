package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

// ArmLayers.layer (26.2's ArmLayersFailTest, X570): a chestplate that throws on the arm with the same armour on every
// frame is left out after ArmLayers.TRIES failures in a row; one that draws in between is kept. port: one layer, the
// chestplate; the GL matrix stack is put back by draw (no GL here)
class ArmLayersFailTest {
    private static final Object[] PLAIN = {null, null, "chest", null};
    private static final Object[] OTHER = {null, null, "other chest", null};

    @AfterEach
    void clear() {
        ArmLayers.leave();
    }

    private static Runnable throwing(int[] calls) {
        return () -> {
            calls[0]++;
            throw new IllegalStateException("fails every frame");
        };
    }

    @Test
    void failingWithTheSameArmourIsLeftOutAfterTries() {
        int[] calls = {0};
        for (int frame = 0; frame < 10; frame++) assertFalse(ArmLayers.layer(PLAIN, String.class, throwing(calls)));
        assertEquals(ArmLayers.TRIES, calls[0]);
        assertTrue(ArmLayers.leftOut(PLAIN));
        // other armour on: tried anew
        assertFalse(ArmLayers.leftOut(OTHER));
        assertTrue(ArmLayers.layer(OTHER, String.class, () -> calls[0]++));
        assertEquals(ArmLayers.TRIES + 1, calls[0]);
        // a new world: tried anew
        ArmLayers.leave();
        assertFalse(ArmLayers.leftOut(PLAIN));
        assertTrue(ArmLayers.layer(PLAIN, String.class, () -> calls[0]++));
        assertEquals(ArmLayers.TRIES + 2, calls[0]);
    }

    @Test
    void failingNowAndThenIsKept() {
        // failures with draws between them: never TRIES in a row
        int[] calls = {0};
        for (int frame = 0; frame < 3 * ArmLayers.TRIES; frame++) {
            ArmLayers.layer(PLAIN, String.class, () -> {
                if (++calls[0] % ArmLayers.TRIES != 0) throw new IllegalStateException("now and then");
            });
        }
        assertEquals(3 * ArmLayers.TRIES, calls[0]);
        assertFalse(ArmLayers.leftOut(PLAIN));
    }

    @Test
    void failingOnlyAtFirstIsKept() {
        int[] calls = {0};
        for (int frame = 0; frame < 10; frame++) {
            ArmLayers.layer(PLAIN, String.class, () -> {
                if (++calls[0] < ArmLayers.TRIES) throw new IllegalStateException("before the player's data came");
            });
        }
        assertEquals(10, calls[0]);
    }

    @Test
    void theKeyIsACopyOfTheArmourWorn() {
        Object[] worn = PLAIN.clone();
        int[] calls = {0};
        for (int frame = 0; frame < ArmLayers.TRIES; frame++) ArmLayers.layer(worn, String.class, throwing(calls));
        // the caller's array changed after: the count stays with what was worn then
        worn[2] = "other chest";
        assertTrue(ArmLayers.leftOut(PLAIN));
        assertFalse(ArmLayers.leftOut(worn));
    }
}
