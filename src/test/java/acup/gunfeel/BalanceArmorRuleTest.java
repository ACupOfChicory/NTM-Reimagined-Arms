package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// The formula against the balance model's own numbers (variants.det_pellet with fit4's S, worked out by its script).
class BalanceArmorRuleTest {
    private static final BalanceArmorRule.Tune T = BalanceArmorRule.Tune.DEFAULT;

    private static float c(float d, float neg, float pen, double s, double tau) {
        return BalanceArmorRule.full(new BalanceArmorRule.Hit(d, neg, pen, true), s, tau, T, 1.0);
    }

    @Test
    void matchesTheModel() {
        assertEquals(8.850, c(11.25F, 2F, 0.1F, 8, 1.0), 0.002);       // steel, .357 Express
        assertEquals(9.557, c(15F, 3F, 0.1F, 13, 1.0), 0.002);         // diesel, .44 Express
        assertEquals(0.497, c(2F, 5F, 0.2F, 18, 2.0), 0.002);          // T-51b, a flechette
        assertEquals(3.594, c(18.75F, 0F, -0.25F, 26, 2.1), 0.002);    // HEV, .22 JHP
        assertEquals(8.429, c(26.25F, 0F, 0F, 22, 3.0), 0.002);        // Security, 7.62 HE
        assertEquals(2.738, c(25F, 0F, 0F, 63 * 0.75, 4.0), 0.002);    // BJ, the laser pistol (EN)
        assertEquals(4.890, c(45F, 15F, 0.25F, 90, 5.5), 0.002);       // RPA, 7.62 DU
        assertEquals(5.751, c(75F, 30F, 0.35F, 148, 6.5), 0.002);      // FAU, .50 SM
    }

    @Test
    void tiers() {
        assertEquals(0.25, BalanceArmorRule.b(1), 1e-9);
        assertEquals(0.165, BalanceArmorRule.b(2.3), 1e-9);
        assertEquals(0.0425, BalanceArmorRule.b(6.5), 1e-9);
        assertEquals(0.025, BalanceArmorRule.b(9), 1e-9);
        assertEquals(0.06, BalanceArmorRule.d(1), 1e-9);
        assertEquals(0.17, BalanceArmorRule.d(6.5), 1e-9);
        assertEquals(0.20, BalanceArmorRule.d(8.5), 1e-9);
    }

    @Test
    void neverNothingNeverAll() {
        // a pellet against the strongest set keeps b of the squeezed damage
        float weak = c(1F, 0F, 0F, 280, 8.5);
        assertTrue(weak >= 0.025F * 1F - 1e-6F && weak > 0F, "weak " + weak);
        // the biggest hit on the weakest set: at most (1 − d) of 14
        float strong = c(1000F, 0F, 0F, 8, 1.0);
        assertTrue(strong <= 14F * 0.94F + 1e-4F && strong > 13F, "strong " + strong);
        // a blast past the raw limit is not squeezed
        float raw = BalanceArmorRule.full(new BalanceArmorRule.Hit(100F, 0F, 0F, false), 8, 1.0, T, 1.0);
        assertEquals(100F * 0.94F, raw, 0.5F);
        assertEquals(0F, c(0F, 5F, 0F, 8, 1.0));
    }

    @Test
    void floor() {
        // the gun table's floor: 1 is the formula's own
        var ap = new BalanceArmorRule.Hit(3.23F, 17.5F, 0.15F, true);
        // a weak hit on CMB is all floor: the floor times it (the Ma Deuce's AP, its mult x0.345)
        float one = BalanceArmorRule.full(ap, 159, 6.0, T, 1.0), three = BalanceArmorRule.full(ap, 159, 6.0, T, 3.0);
        assertEquals(3.0, three / one, 0.15);
        // a soft round's floor below 1 takes it down the same way
        var jhp = new BalanceArmorRule.Hit(7.65F, 0F, -0.25F, true);
        assertEquals(0.66, BalanceArmorRule.full(jhp, 159, 6.0, T, 0.66) / BalanceArmorRule.full(jhp, 159, 6.0, T, 1.0), 0.02);
        // never more than 1 − d of the squeezed hit, never past the formula's top
        assertTrue(BalanceArmorRule.full(ap, 159, 6.0, T, 1000.0) <= 3.23F * (1 - BalanceArmorRule.d(6.0)) + 1e-5F);
        // a strong hit on a weak set: the floor hardly matters
        var big = new BalanceArmorRule.Hit(40F, 0F, 0F, true);
        assertEquals(BalanceArmorRule.full(big, 8, 1.0, T, 1.0), BalanceArmorRule.full(big, 8, 1.0, T, 2.0), 0.05);
    }

    @Test
    void flesh() {
        assertEquals(5.0, BalanceArmorRule.flesh(5, 8, 14), 1e-9);
        assertEquals(8.0, BalanceArmorRule.flesh(8, 8, 14), 1e-9);
        assertTrue(BalanceArmorRule.flesh(1e6, 8, 14) <= 14.0);
        assertEquals(8 + 6 * (1 - Math.exp(-1)), BalanceArmorRule.flesh(14, 8, 14), 1e-9);
    }

    @Test
    void piecesBlend() {
        float n = Float.NaN;
        // the whole set: its result
        assertEquals(4F, BalanceArmorRule.blend(new float[] {4F, 4F, 4F, 4F}, 10F), 1e-5);
        // nothing from the table: NTM's
        assertEquals(10F, BalanceArmorRule.blend(new float[] {n, n, n, n}, 10F), 1e-5);
        // the chestplate alone: 8/20 of it, 12/20 NTM's
        assertEquals(0.4F * 4F + 0.6F * 10F, BalanceArmorRule.blend(new float[] {n, 4F, n, n}, 10F), 1e-5);
        // two sets mixed
        assertEquals(0.15F * 2F + 0.4F * 4F + 0.3F * 4F + 0.15F * 2F, BalanceArmorRule.blend(new float[] {2F, 4F, 4F, 2F}, 10F), 1e-5);
    }

    @Test
    void shotsOnly() {
        // NTM's rounds and beams: the formula's
        for (String t : new String[] {"physical", "laser", "plasma", "microwave", "subatomic", "electric", "tau", "revolver_bullet", "shrapnel"})
            assertTrue(BalanceArmorRule.shotType("hbm", t, false), t);
        // the Tesla Cannon's blast names its shooter: still a shot
        assertTrue(BalanceArmorRule.shotType("hbm", "electric", true));
        // a gun's own blow, Lag's suicide: physical from the player, NTM's numbers
        assertFalse(BalanceArmorRule.shotType("hbm", "physical", true));
        // melee, a mob's blow, vanilla's arrow, NTM's fire, flamethrower, electricity, spikes: NTM's numbers
        assertFalse(BalanceArmorRule.shotType("minecraft", "player_attack", true));
        assertFalse(BalanceArmorRule.shotType("minecraft", "mob_attack", true));
        assertFalse(BalanceArmorRule.shotType("minecraft", "arrow", false));
        for (String t : new String[] {"fire", "flamethrower", "electricity", "electrified", "spikes", "acid_player", "suicide", "rubble"})
            assertFalse(BalanceArmorRule.shotType("hbm", t, false), t);
    }
}
