package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

// ArmLayers.layer (26.2's ArmLayersFailTest, X570/X572): a chestplate that throws on the arm with the same armour on
// every frame is left out after ArmLayers.TRIES frames of failures in a row; one that draws in between is kept. port: one layer, the
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
        for (int frame = 0; frame < 10; frame++) {
            ArmLayers.newFrame();
            assertFalse(ArmLayers.layer(PLAIN, String.class, throwing(calls)));
        }
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
            ArmLayers.newFrame();
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
            ArmLayers.newFrame();
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
        for (int frame = 0; frame < ArmLayers.TRIES; frame++) {
            ArmLayers.newFrame();
            ArmLayers.layer(worn, String.class, throwing(calls));
        }
        // the caller's array changed after: the count stays with what was worn then
        worn[2] = "other chest";
        assertTrue(ArmLayers.leftOut(PLAIN));
        assertFalse(ArmLayers.leftOut(worn));
    }

    @Test
    void aFrameCountsOnceForBothArms() {
        // both arms throw in each frame: left out after TRIES frames, not after TRIES calls (1.5 frames)
        int[] calls = {0};
        for (int frame = 0; frame < ArmLayers.TRIES + 2; frame++) {
            ArmLayers.newFrame();
            for (int arm = 0; arm < 2; arm++) ArmLayers.layer(PLAIN, String.class, throwing(calls));
        }
        // the last of the TRIES frames: left out after its first arm
        assertEquals(2 * ArmLayers.TRIES - 1, calls[0]);
    }

    @Test
    void aDrawOnTheOtherArmInTheSameFrameKeepsTheCount() {
        // draws on one arm and throws on the other, every frame: the frames count all the same
        int[] calls = {0};
        for (int frame = 0; frame < ArmLayers.TRIES + 2; frame++) {
            ArmLayers.newFrame();
            for (int arm = 0; arm < 2; arm++) ArmLayers.layer(PLAIN, String.class, () -> {
                if (++calls[0] % 2 == 0) throw new IllegalStateException("fails on one arm");
            });
        }
        assertEquals(2 * ArmLayers.TRIES, calls[0]);
    }

    @Test
    void aWorldLeftWhileTheChestplateFailsStartsAnew() {
        // leave() comes from the network thread between the count's read and its write: the count does not come back
        int[] calls = {0};
        for (int frame = 0; frame < ArmLayers.TRIES - 1; frame++) {
            ArmLayers.newFrame();
            ArmLayers.layer(PLAIN, String.class, throwing(calls));
        }
        ArmLayers.newFrame();
        ArmLayers.layer(PLAIN, String.class, () -> {
            calls[0]++;
            ArmLayers.leave();
            throw new IllegalStateException("fails as the world is left");
        });
        // the next world: tried in every frame again until TRIES failures of its own
        for (int frame = 0; frame < ArmLayers.TRIES - 1; frame++) {
            ArmLayers.newFrame();
            ArmLayers.layer(PLAIN, String.class, throwing(calls));
        }
        assertEquals(2 * ArmLayers.TRIES - 1, calls[0]);
        assertFalse(ArmLayers.leftOut(PLAIN));
    }
}
