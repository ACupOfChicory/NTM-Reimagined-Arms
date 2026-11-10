package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// Stage 3 of the balance: the model of NTM's blasts on the ladder (BalanceBlasts), without the game.
class BalanceBlastsTest {
    private static final BalanceBlasts.Boom HE = new BalanceBlasts.Boom("HE", false, 25, 7.5F, 10, 0.1F);
    private static final BalanceBlasts.Boom ML = new BalanceBlasts.Boom("ML", true, 50, 5, 0, 0);

    @Test
    void capAndWindow() {
        // a bare target: HE at 1 block 23.8 raw (NTM: 1 − 0.7/15), one click's cap 12; Missile Launcher on it: one hurt of
        // 50, one click, 12
        assertEquals(25F * (1F - 0.7F / 15F), BalanceBlasts.raw(HE, 1), 1e-4);
        assertEquals(12F, BalanceBlasts.damage(HE, 1, null, 0, 0), 1e-4);
        assertEquals(2, BalanceBlasts.hits(12F));
        assertEquals(50F, BalanceBlasts.raw(ML, -1), 1e-4);
        assertEquals(12F, BalanceBlasts.damage(ML, -1, null, 0, 0), 1e-4);
    }

    @Test
    void ladder() {
        // every set takes something from every blast (no ∞), the higher the set the less
        float last = Float.MAX_VALUE;
        for (String set : new String[] {"steel", "dieselsuit", "t51", "hev", "trenchmaster", "rpa", "fau", "dns"}) {
            var def = BalanceArmor.SETS.get(set);
            float d = BalanceBlasts.damage(HE, 3, set, def.s(), def.tau());
            assertTrue(d > 0F && d <= last, set + " " + d);
            last = d;
        }
        assertEquals(Integer.MAX_VALUE, BalanceBlasts.hits(0F));
    }
}
