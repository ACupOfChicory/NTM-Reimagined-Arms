package acup.gunfeel.heat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import acup.gunfeel.heat.OverheatBase.Gun;

// The barrel's heat by the real guns' numbers: shots fired at a gun's rate with the barrel cooling between them.
class OverheatTest {
    /** the heat after this many shots of a gun firing one in delay ticks, a shot every delay ticks from h (cooling in the air between) */
    static float fire(float h, Gun gun, int shots, int delay) {
        return fire(h, gun, shots, delay, delay);
    }

    /** the same, a shot every every ticks (the trigger let go between) */
    static float fire(float h, Gun gun, int shots, int delay, int every) {
        for (int i = 0; i < shots; i++) {
            h = OverheatBase.afterShot(h, gun, 1F, delay);
            h = OverheatBase.cooled(h, every, OverheatBase.TAU);
        }
        return h;
    }

    @Test
    void anM2BurstOfSomeHundredEightyIsAtTheTopAndItsSustainedRateStaysCool() {
        float h = fire(0F, Gun.M2, 180, 2);
        assertTrue(h > 0.97F, "180 rounds in one burst: " + h);
        assertTrue(fire(0F, Gun.M2, 120, 2) < 0.8F, "120 rounds: not yet");
        // the sustained rate, 40 a minute (one in 30 ticks), ten minutes of it
        float sustained = fire(0F, Gun.M2, 400, 2, 30);
        assertEquals(0.26F, sustained, 0.03F);
        assertTrue(sustained < OverheatBase.SPREAD_FROM && sustained < OverheatBase.SMOKE_FROM);
        // from the top to half in some 42 s
        assertEquals(1F, OverheatBase.cooled(1F, 832, OverheatBase.TAU) * 2F, 0.01F);
    }

    @Test
    void anAm180PanIsAboutHalfAndThreeInARowTheTopAsMuchWithTheBalancesSlowerOne() {
        int reload = 66;
        for (int[] gun : new int[][] {{177, 1}, {60, 3}}) {
            float one = fire(0F, Gun.AM180, gun[0], gun[1]);
            float two = fire(OverheatBase.cooled(one, reload, OverheatBase.TAU), Gun.AM180, gun[0], gun[1]);
            float three = fire(OverheatBase.cooled(two, reload, OverheatBase.TAU), Gun.AM180, gun[0], gun[1]);
            String of = gun[0] + " rounds, one in " + gun[1];
            assertEquals(0.45F, one, 0.04F, of);
            assertEquals(0.82F, two, 0.05F, of);
            assertTrue(three > 0.97F, of + ": " + three);
        }
    }

    @Test
    void theEffectsComeOnFromTheirHeats() {
        assertEquals(0F, OverheatBase.spreadAdd(0.5F, 0.01F));
        assertEquals(0.03F, OverheatBase.spreadAdd(1F, 0.01F), 1e-6F);
        assertEquals(1F, OverheatBase.wearMult(0.6F));
        assertEquals(5F, OverheatBase.wearMult(1F), 1e-6F);
        assertEquals(0F, OverheatBase.jamChance(Gun.AM180, 0.7F));
        assertEquals(0.1F, OverheatBase.jamChance(Gun.AM180, 1F), 1e-6F);
        assertEquals(0F, OverheatBase.jamChance(Gun.M2, 1F), "the M2 does not jam from heat");
        assertEquals(0F, OverheatBase.cookChance(Gun.M2, 0.9F));
        assertEquals(0.5F, OverheatBase.cookChance(Gun.M2, 1F), 1e-6F);
        assertEquals(0F, OverheatBase.cookChance(Gun.AM180, 1F), "an open bolt has no round waiting in the chamber");
    }

    @Test
    void theTickKeptAsItsLowBitsComesBackNextToNow() {
        long now = 5_000_000_000L;
        assertEquals(now - 40, OverheatBase.unwrap((int) ((now - 40) & 0x7FFFFFFFL), now));
        // a client a tick behind the server: its shot is now
        assertEquals(now, OverheatBase.unwrap((int) ((now + 2) & 0x7FFFFFFFL), now));
        // just over a 31-bit turn
        long turn = 0x80000000L * 3;
        assertEquals(turn - 5, OverheatBase.unwrap((int) ((turn - 5) & 0x7FFFFFFFL), turn + 3));
        assertEquals(0L, OverheatBase.unwrap(0, 0L));
        assertEquals(0F, OverheatBase.cooled(0F, 100, OverheatBase.TAU));
        assertEquals(0.5F, OverheatBase.cooled(0.5F, 0, OverheatBase.TAU));
    }
}
