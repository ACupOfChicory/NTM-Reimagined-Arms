package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

// ArmLayers' count of a layer's failures (26.2's ArmLayersFailTest, the counting part: the port's layers draw through
// GL): a layer that throws in ArmLayers.TRIES frames in a row with the same armour on is left out, a frame it draws in
// starts the count anew, a frame counts once for both arms, other armour is counted apart
class ArmLayersFailTest {
    private static final Object[] PLAIN = {null, "chest", null, null};
    private static final Object[] OTHER = {null, "other chest", null, null};

    // a stand-in for the player renderer's layer (only its class counts)
    private static final class Throws {
    }

    @AfterEach
    void clear() {
        ArmLayers.leave();
        ArmLayers.newFrame();
    }

    private static void fail(ArmLayers.Failed key) {
        ArmLayers.failed(key, new IllegalStateException("test"));
    }

    // one arm's layer as ArmLayers.run has it: skipped when left out, else drawn; false: left out, or it threw
    private static boolean layer(ArmLayers.Failed key, boolean throwsNow, int[] calls) {
        if (ArmLayers.leftOut(key)) return false;
        calls[0]++;
        if (!throwsNow) return true;
        fail(key);
        return false;
    }

    @Test
    void aLayerThrowingInFramesInARowIsLeftOut() {
        ArmLayers.Failed key = new ArmLayers.Failed(Throws.class, PLAIN);
        for (int i = 1; i < ArmLayers.TRIES; i++) {
            ArmLayers.newFrame();
            fail(key);
            assertFalse(ArmLayers.leftOut(key), "after " + i);
        }
        ArmLayers.newFrame();
        fail(key);
        assertTrue(ArmLayers.leftOut(key));
        assertTrue(ArmLayers.leftOut(new ArmLayers.Failed(Throws.class, PLAIN.clone())), "the same armour, a new array");
        assertFalse(ArmLayers.leftOut(new ArmLayers.Failed(Throws.class, OTHER)), "other armour is tried");
        ArmLayers.leave();
        ArmLayers.newFrame();
        assertFalse(ArmLayers.leftOut(key), "a new world tries it again");
    }

    @Test
    void aFrameItDrawsInStartsTheCountAnew() {
        ArmLayers.Failed key = new ArmLayers.Failed(Throws.class, PLAIN);
        int[] calls = {0};
        for (int round = 0; round < 3; round++) {
            for (int i = 1; i < ArmLayers.TRIES; i++) {
                ArmLayers.newFrame();
                layer(key, true, calls);
            }
            ArmLayers.newFrame();
            assertTrue(layer(key, false, calls));
        }
        assertFalse(ArmLayers.leftOut(key));
        for (int i = 0; i < ArmLayers.TRIES; i++) {
            ArmLayers.newFrame();
            layer(key, true, calls);
        }
        assertTrue(ArmLayers.leftOut(key));
    }

    @Test
    void aFrameCountsOnceForBothArms() {
        // both arms throw in each frame: left out after TRIES frames, not after TRIES calls (1.5 frames)
        ArmLayers.Failed key = new ArmLayers.Failed(Throws.class, PLAIN);
        int[] calls = {0};
        for (int frame = 0; frame < ArmLayers.TRIES + 2; frame++) {
            ArmLayers.newFrame();
            for (int arm = 0; arm < 2; arm++) layer(key, true, calls);
        }
        // the last of the TRIES frames: left out after its first arm
        assertEquals(2 * ArmLayers.TRIES - 1, calls[0]);
    }

    @Test
    void aDrawOnTheOtherArmInTheSameFrameKeepsTheCount() {
        // draws on one arm and throws on the other, every frame: the frames count all the same
        ArmLayers.Failed key = new ArmLayers.Failed(Throws.class, PLAIN);
        int[] calls = {0};
        for (int frame = 0; frame < ArmLayers.TRIES + 2; frame++) {
            ArmLayers.newFrame();
            for (int arm = 0; arm < 2; arm++) layer(key, arm == 1, calls);
        }
        assertEquals(2 * ArmLayers.TRIES, calls[0]);
    }

    @Test
    void aWorldLeftWhileALayerFailsStartsAnew() {
        // leave() comes from the network thread between the count's read and its write: the count does not come back
        ArmLayers.Failed key = new ArmLayers.Failed(Throws.class, PLAIN);
        int[] calls = {0};
        for (int frame = 0; frame < ArmLayers.TRIES - 1; frame++) {
            ArmLayers.newFrame();
            layer(key, true, calls);
        }
        ArmLayers.newFrame();
        assertFalse(ArmLayers.leftOut(key));
        calls[0]++;
        ArmLayers.leave();
        fail(key);
        // the next world: tried in every frame again until TRIES failures of its own
        for (int frame = 0; frame < ArmLayers.TRIES - 1; frame++) {
            ArmLayers.newFrame();
            layer(key, true, calls);
        }
        assertEquals(2 * ArmLayers.TRIES - 1, calls[0]);
    }
}
