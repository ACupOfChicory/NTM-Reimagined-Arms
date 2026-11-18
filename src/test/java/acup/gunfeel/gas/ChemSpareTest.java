package acup.gunfeel.gas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import acup.gunfeel.smoke.SmokeClouds;

// A chemical thrower's cloud leaves its own thrower alone until 3 s after the last of the thrower's gas is out of it.
class ChemSpareTest {
    // what ChemClouds passes: the feed is taken STIR_AHEAD ticks on and comes out over the gas's release
    private static final int OUT_IN = SmokeClouds.STIR_AHEAD + GasKind.CHLORINE.smoke().release();

    @Test
    void theThrowerIsSparedTillThreeSecondsAfterTheGasIsOut() {
        ChemSpare s = new ChemSpare();
        s.let(7, 42, 1000, OUT_IN);
        long last = 1000 + OUT_IN + ChemSpare.SPARE - 1;
        assertEquals(42, s.spares(7, 1000));
        assertEquals(42, s.spares(7, last));
        assertEquals(-1, s.spares(7, last + 1), "then the cloud does to the thrower what it does to anyone");
        assertEquals(60, ChemSpare.SPARE, "3 s");
        assertEquals(-1, s.spares(8, 1000), "another cloud spares no one");
    }

    @Test
    void feedingTheCloudAgainKeepsTheThrowerSpared() {
        ChemSpare s = new ChemSpare();
        s.let(7, 42, 1000, OUT_IN);
        // the stream goes on: its next gathering feeds the same cloud half a second later
        s.let(7, 42, 1010, OUT_IN);
        assertEquals(42, s.spares(7, 1010 + OUT_IN + ChemSpare.SPARE - 1));
        assertEquals(-1, s.spares(7, 1010 + OUT_IN + ChemSpare.SPARE));
    }

    @Test
    void expiredCloudsAreForgotten() {
        ChemSpare s = new ChemSpare();
        s.let(7, 42, 1000, OUT_IN);
        s.let(9, 43, 1100, OUT_IN);
        s.expire(1000 + OUT_IN + ChemSpare.SPARE);
        assertEquals(-1, s.spares(7, 1000));
        assertEquals(43, s.spares(9, 1100));
        s.expire(1100 + OUT_IN + ChemSpare.SPARE);
        assertTrue(s.isEmpty());
    }
}
