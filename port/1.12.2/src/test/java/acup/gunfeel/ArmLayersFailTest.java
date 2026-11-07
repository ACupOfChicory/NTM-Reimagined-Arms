package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

// ArmLayers' count of a layer's failures (26.2's ArmLayersFailTest, the counting part: the port's layers draw through
// GL): a layer that throws ArmLayers.TRIES times in a row with the same armour on is left out, one that draws in
// between starts the count anew, other armour is counted apart
class ArmLayersFailTest {
    private static final Object[] PLAIN = {null, "chest", null, null};
    private static final Object[] OTHER = {null, "other chest", null, null};

    // a stand-in for the player renderer's layer (only its class counts)
    private static final class Throws {
    }

    @AfterEach
    void clear() {
        ArmLayers.leave();
    }

    private static void fail(ArmLayers.Failed key) {
        ArmLayers.failed(key, new IllegalStateException("test"));
    }

    @Test
    void aLayerThrowingInARowIsLeftOut() {
        ArmLayers.Failed key = new ArmLayers.Failed(Throws.class, PLAIN);
        for (int i = 1; i < ArmLayers.TRIES; i++) {
            fail(key);
            assertFalse(ArmLayers.leftOut(key), "after " + i);
        }
        fail(key);
        assertTrue(ArmLayers.leftOut(key));
        assertTrue(ArmLayers.leftOut(new ArmLayers.Failed(Throws.class, PLAIN.clone())), "the same armour, a new array");
        assertFalse(ArmLayers.leftOut(new ArmLayers.Failed(Throws.class, OTHER)), "other armour is tried");
        ArmLayers.leave();
        assertFalse(ArmLayers.leftOut(key), "a new world tries it again");
    }

    @Test
    void aDrawInBetweenStartsTheCountAnew() {
        ArmLayers.Failed key = new ArmLayers.Failed(Throws.class, PLAIN);
        for (int round = 0; round < 3; round++) {
            for (int i = 1; i < ArmLayers.TRIES; i++) fail(key);
            ArmLayers.drew(key);
        }
        assertFalse(ArmLayers.leftOut(key));
        for (int i = 0; i < ArmLayers.TRIES; i++) fail(key);
        assertTrue(ArmLayers.leftOut(key));
    }
}
