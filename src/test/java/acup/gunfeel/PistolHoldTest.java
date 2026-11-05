package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PistolHoldTest {
    @Test
    void curveStartsAndEndsWhereNtmDoes() {
        assertEquals(0F, PistolHold.curve(0F), 1e-6F);
        assertEquals(1F, PistolHold.curve(1F), 1e-6F);
        assertEquals(0F, PistolHold.curve(-0.5F), 1e-6F);
        assertEquals(1F, PistolHold.curve(1.5F), 1e-6F);
    }

    @Test
    void curveNeverGoesBack() {
        float last = 0F;
        for (int i = 1; i <= 200; i++) {
            float v = PistolHold.curve(i / 200F);
            assertTrue(v >= last, "at " + i);
            last = v;
        }
    }

    // TaCZ's aiming of a pistol (aim_time 0.13 s, through its smoothing) at the times NTM's linear progress reaches
    // these values (0.2 s in all): in our curve it is within 0.06
    @Test
    void curveFollowsTaczPistolAiming() {
        float[][] at = {{0.15F, 0.11F}, {0.30F, 0.31F}, {0.46F, 0.53F}, {0.61F, 0.76F}, {0.76F, 0.92F}, {0.92F, 0.98F}};
        for (float[] a : at) assertEquals(a[1], PistolHold.curve(a[0]), 0.06F, "at " + a[0]);
    }

    @Test
    void hipPlaceIsWithinTaczPistols() {
        // TaCZ's six pistols, in the harmonic mean of the ends' depths: right 0.07..0.29, lower 0.08..0.24, closer 0..0.21
        assertTrue(PistolHold.RIGHT >= 0.07F && PistolHold.RIGHT <= 0.29F);
        assertTrue(-PistolHold.UP >= 0.08F && -PistolHold.UP <= 0.24F);
        assertTrue(PistolHold.BACK >= 0F && PistolHold.BACK <= 0.21F);
    }
}
