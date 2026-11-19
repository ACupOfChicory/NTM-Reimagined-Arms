package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// the frame's "a pistol is placed" flag Arms keeps for the arms' size (armPistolScale); the scale itself: core's
// ArmPistolScaleTest
class ArmPistolHoldFrameTest {
    @Test
    void newGunFrameClearsThePistolPlacementFlag() {
        Arms.pistolHoldFrame(true, true);
        assertTrue(Arms.pistolHoldFrame());
        Arms.resetPistolHoldFrame();
        assertFalse(Arms.pistolHoldFrame());
    }

    @Test
    void aLaterAimingCallInTheFrameKeepsWhatThePlacingCallSet() {
        // the heavy revolver: NTM's placing call in setupFirstPerson, then its kick in renderFirstPerson
        Arms.resetPistolHoldFrame();
        Arms.pistolHoldFrame(true, true);
        Arms.pistolHoldFrame(false, false);
        assertTrue(Arms.pistolHoldFrame());
        // a rifle's placing call clears it
        Arms.pistolHoldFrame(true, false);
        assertFalse(Arms.pistolHoldFrame());
        Arms.pistolHoldFrame(false, true);
        assertFalse(Arms.pistolHoldFrame());
    }
}
