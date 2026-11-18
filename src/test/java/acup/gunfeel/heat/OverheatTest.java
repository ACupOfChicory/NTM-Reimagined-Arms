package acup.gunfeel.heat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import acup.gunfeel.heat.Overheat.Gun;

// The barrel's heat by the real guns' numbers: shots fired at a gun's rate with the barrel cooling between them.
class OverheatTest {
    /** the heat after this many shots of a gun firing one in delay ticks, a shot every delay ticks from h (cooling in the air between) */
    static float fire(float h, Gun gun, int shots, int delay) {
        return fire(h, gun, shots, delay, delay);
    }

    /** the same, a shot every every ticks (the trigger let go between) */
    static float fire(float h, Gun gun, int shots, int delay, int every) {
        for (int i = 0; i < shots; i++) {
            h = Overheat.afterShot(h, gun, 1F, delay);
            h = Overheat.cooled(h, every, Overheat.TAU);
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
        assertTrue(sustained < Overheat.SPREAD_FROM && sustained < Overheat.SMOKE_FROM);
        // from the top to half in some 42 s
        assertEquals(1F, Overheat.cooled(1F, 832, Overheat.TAU) * 2F, 0.01F);
    }

    @Test
    void anAm180PanIsAboutHalfAndThreeInARowTheTopAsMuchWithTheBalancesSlowerOne() {
        int reload = 66;
        for (int[] gun : new int[][] {{177, 1}, {60, 3}}) {
            float one = fire(0F, Gun.AM180, gun[0], gun[1]);
            float two = fire(Overheat.cooled(one, reload, Overheat.TAU), Gun.AM180, gun[0], gun[1]);
            float three = fire(Overheat.cooled(two, reload, Overheat.TAU), Gun.AM180, gun[0], gun[1]);
            String of = gun[0] + " rounds, one in " + gun[1];
            assertEquals(0.45F, one, 0.04F, of);
            assertEquals(0.82F, two, 0.05F, of);
            assertTrue(three > 0.97F, of + ": " + three);
        }
    }

    @Test
    void theEffectsComeOnFromTheirHeats() {
        assertEquals(0F, Overheat.spreadAdd(0.5F, 0.01F));
        assertEquals(0.03F, Overheat.spreadAdd(1F, 0.01F), 1e-6F);
        assertEquals(1F, Overheat.wearMult(0.6F));
        assertEquals(5F, Overheat.wearMult(1F), 1e-6F);
        assertEquals(0F, Overheat.jamChance(Gun.AM180, 0.7F));
        assertEquals(0.1F, Overheat.jamChance(Gun.AM180, 1F), 1e-6F);
        assertEquals(0F, Overheat.jamChance(Gun.M2, 1F), "the M2 does not jam from heat");
        assertEquals(0F, Overheat.cookChance(Gun.M2, 0.9F));
        assertEquals(0.5F, Overheat.cookChance(Gun.M2, 1F), 1e-6F);
        assertEquals(0F, Overheat.cookChance(Gun.AM180, 1F), "an open bolt has no round waiting in the chamber");
    }

    @Test
    void theTickKeptAsItsLowBitsComesBackNextToNow() {
        long now = 5_000_000_000L;
        assertEquals(now - 40, Overheat.unwrap((int) ((now - 40) & 0x7FFFFFFFL), now));
        // a client a tick behind the server: its shot is now
        assertEquals(now, Overheat.unwrap((int) ((now + 2) & 0x7FFFFFFFL), now));
        // just over a 31-bit turn
        long turn = 0x80000000L * 3;
        assertEquals(turn - 5, Overheat.unwrap((int) ((turn - 5) & 0x7FFFFFFFL), turn + 3));
        assertEquals(0L, Overheat.unwrap(0, 0L));
        assertEquals(0F, Overheat.cooled(0F, 100, Overheat.TAU));
        assertEquals(0.5F, Overheat.cooled(0.5F, 0, Overheat.TAU));
    }
}
