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
        assertEquals(1.7F, BalanceArmor.pieceBlastPower(4, 25F, 3F), 1e-6F);
        assertEquals(3F, BalanceArmor.pieceBlastPower(7, 50F, 3F), 1e-6F);
        assertEquals(3F, BalanceArmor.pieceBlastPower(8, 50F, 3F), 1e-6F);
        assertEquals(1.5F, BalanceArmor.pieceBlastPower(4, 25F, 1.5F), 1e-6F);
        // a rocket's direct hit: tier 7 its own power, tier 8 (DNS, FAU) the power itself
        assertEquals(2.2F, BalanceArmor.blastPowerFor(7, true, 50F, 3F), 1e-6F);
        assertEquals(3F, BalanceArmor.blastPowerFor(8, true, 50F, 3F), 1e-6F);
        assertEquals(1.7F, BalanceArmor.blastPowerFor(4, true, 50F, 3F), 1e-6F);
        // never more than the power
        assertEquals(2F, BalanceArmor.blastPowerFor(7, true, 50F, 2F), 1e-6F);
        // balanceBlastRocketHighFrom 30: Panzerschreck's 25 on tier 7 with the power itself (18 hits on RPA → 12, as the
        // 40 mm MK108's 25), Stinger's 35 and the Missile Launcher's 50 the rocket's
        assertEquals("30", shipped.getProperty("balanceBlastRocketHighFrom"));
        assertEquals(3F, BalanceArmor.blastPowerFor(7, true, 25F, 3F), 1e-6F);
        assertEquals(2.2F, BalanceArmor.blastPowerFor(7, true, 35F, 3F), 1e-6F);
        BalanceBlasts.Boom panzer = new BalanceBlasts.Boom("ракета Panzerschreck", true, 25, 5, 0, 0), mk108 = new BalanceBlasts.Boom("40 мм HE MK108", true, 25, 5, 0, 0);
        assertEquals(BalanceBlasts.hits(BalanceBlasts.damage(mk108, -1, "rpa", rpa.s(), rpa.tau(), 3F)),
                BalanceBlasts.hits(BalanceBlasts.damage(panzer, -1, "rpa", rpa.s(), rpa.tau(), BalanceArmor.blastPowerFor(7, true, 25F, 3F))));
        // balanceBlastLowFloor 0.83: a gunpowder grenade 1 block off on tier 4, 3 to 4 hits (9 to 11 without), bare 3;
        // the HE grenade's 3 on HEV as it was
        assertEquals("0.83", shipped.getProperty("balanceBlastLowFloor"));
        assertEquals(0.83F, BalanceArmor.blastLowFloor(), 1e-6F);
        var powder = new BalanceBlasts.Boom("граната порох", false, 10, 5, 5, 0);
        assertEquals(3, BalanceBlasts.hits(BalanceBlasts.damage(powder, 1, null, 0, 0)));
        for (String set : new String[] {"steamsuit", "hev", "ajr", "security"}) {
            var def = BalanceArmor.SETS.get(set);
            int with = BalanceBlasts.hits(BalanceBlasts.damage(powder, 1, set, def.s(), def.tau(), 1.7F, 0.83F));
            int without = BalanceBlasts.hits(BalanceBlasts.damage(powder, 1, set, def.s(), def.tau(), 1.7F));
            assertTrue(with >= 3 && with <= 4 && without >= 8, set + ": " + without + " → " + with);
        }
        var hev = BalanceArmor.SETS.get("hev");
        assertEquals(BalanceBlasts.hits(BalanceBlasts.damage(HE, 1, "hev", hev.s(), hev.tau(), 1.7F)),
                BalanceBlasts.hits(BalanceBlasts.damage(HE, 1, "hev", hev.s(), hev.tau(), 1.7F, 0.83F)));
        // the floor is the power's: no more than with no armour
        assertTrue(BalanceBlasts.damage(powder, 1, "steel", 8F, 1F, 1.7F, 0.83F) <= BalanceBlasts.damage(powder, 1, null, 0, 0) + 1e-5F);
        // a blast's far edge (5 raw) gets less of the floor: HEV still takes some off it
        float edge = BalanceBlasts.hit(5F, 0F, 0F, "hev", hev.s(), hev.tau(), 1.7F, 0.83F);
        assertTrue(edge < 4.5F && edge > BalanceBlasts.hit(5F, 0F, 0F, "hev", hev.s(), hev.tau(), 1.7F), "edge " + edge);
        assertEquals(0.83F * 46F, BalanceArmor.lowFloorPower(0.83F, 46F, 9.3F), 1e-4F);
        assertEquals("1.5", shipped.getProperty("balanceElectricPowered"));
        // no armour: the power does nothing
        assertEquals(BalanceBlasts.damage(HE, 3, null, 0, 0), BalanceBlasts.damage(HE, 3, null, 0, 0, 3F), 1e-6F);
    }
}
