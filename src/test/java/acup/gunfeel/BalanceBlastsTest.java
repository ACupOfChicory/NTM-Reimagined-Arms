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

    @Test
    void blastPower() throws java.io.IOException {
        // the power 3 (shipped): never weaker than X858, never more than with no armour; DNS against a direct rocket of
        // the Missile Launcher, its window after the pool (32 → 5 hits, the model's table)
        var shipped = SpreadModelTest.shipped();
        assertEquals("3", shipped.getProperty("balanceBlastPower"));
        assertEquals("1.7", shipped.getProperty("balanceBlastPowerLow"));
        assertEquals("2.2", shipped.getProperty("balanceBlastPowerRocketHigh"));
        for (String set : new String[] {"steel", "hev", "bj", "rpa", "fau", "dns"}) {
            var def = BalanceArmor.SETS.get(set);
            for (float r : new float[] {-1F, 1F, 3F}) {
                float now = BalanceBlasts.damage(ML, r, set, def.s(), def.tau()), after = BalanceBlasts.damage(ML, r, set, def.s(), def.tau(), 3F);
                assertTrue(after >= now && after <= BalanceBlasts.damage(ML, r, null, 0, 0, 3F) + 1e-4F, set + " " + r + ": " + now + " → " + after);
            }
        }
        var dns = BalanceArmor.SETS.get("dns");
        assertEquals(32, BalanceBlasts.hits(BalanceBlasts.damage(ML, -1, "dns", dns.s(), dns.tau())));
        assertEquals(5, BalanceBlasts.hits(BalanceBlasts.damage(ML, -1, "dns", dns.s(), dns.tau(), 3F)));
        // RPA: a rocket's direct hit at balanceBlastPowerRocketHigh, 6 hits
        var rpa = BalanceArmor.SETS.get("rpa");
        assertEquals(6, BalanceBlasts.hits(BalanceBlasts.damage(ML, -1, "rpa", rpa.s(), rpa.tau(), 2.2F)));
        // by tier: up to 4 the low power, a rocket's direct hit on tier 7 the rocket's, the rest (and DNS) the power
        assertEquals(1.7F, BalanceArmor.pieceBlastPower(4, 3F), 1e-6F);
        assertEquals(3F, BalanceArmor.pieceBlastPower(7, 3F), 1e-6F);
        assertEquals(3F, BalanceArmor.pieceBlastPower(8, 3F), 1e-6F);
        assertEquals(1.5F, BalanceArmor.pieceBlastPower(4, 1.5F), 1e-6F);
        // a rocket's direct hit: tier 7 its own power, tier 8 (DNS, FAU) the power itself
        assertEquals(2.2F, BalanceArmor.blastPowerFor(7, true, 3F), 1e-6F);
        assertEquals(3F, BalanceArmor.blastPowerFor(8, true, 3F), 1e-6F);
        assertEquals(1.7F, BalanceArmor.blastPowerFor(4, true, 3F), 1e-6F);
        // never more than the power
        assertEquals(2F, BalanceArmor.blastPowerFor(7, true, 2F), 1e-6F);
        assertEquals("1.5", shipped.getProperty("balanceElectricPowered"));
        // no armour: the power does nothing
        assertEquals(BalanceBlasts.damage(HE, 3, null, 0, 0), BalanceBlasts.damage(HE, 3, null, 0, 0, 3F), 1e-6F);
    }
}
