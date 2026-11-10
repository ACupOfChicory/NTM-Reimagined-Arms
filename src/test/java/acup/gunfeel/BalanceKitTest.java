package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import acup.gunfeel.EuphImmunity.Phase;
import acup.gunfeel.EuphImmunity.Pool;
import acup.gunfeel.EuphImmunity.State;

// Stage 2 of the balance: the kit (shield, medicine, inserts) and euphemium's pool, without the game.
class BalanceKitTest {
    private static final BalanceKit.Shield S = BalanceKit.Shield.DEFAULT;

    @Test
    void shield() {
        // the mod alone: 10 (NTM 25)
        assertEquals(10F, BalanceKit.maxShield(0F, 25F, S), 1e-4F);
        // one flask: NTM's count 5 -> 2
        assertEquals(2F, BalanceKit.maxShield(5F, 0F, S), 1e-4F);
        // 3 flasks and the mod: 6 + 10 = 16, the cap
        assertEquals(16F, BalanceKit.maxShield(15F, 25F, S), 1e-4F);
        // 2 flasks and the mod: 14
        assertEquals(14F, BalanceKit.maxShield(10F, 25F, S), 1e-4F);
        // NTM's 100 of flasks and the mod: the cap 16
        assertEquals(16F, BalanceKit.maxShield(100F, 25F, S), 1e-4F);
        // flasks stop at 8 (NTM's count 40): they alone are at the cap
        assertEquals(40F, BalanceKit.flaskLimit(S), 1e-4F);
        assertEquals(16F, BalanceKit.maxShield(BalanceKit.flaskLimit(S), 0F, S), 1e-4F);
        // NTM's own numbers through the same rule
        BalanceKit.Shield ntm = new BalanceKit.Shield(25F, 5F, 1000F, 60, 0.005F);
        assertEquals(125F, BalanceKit.maxShield(100F, 25F, ntm), 1e-4F);
    }

    @Test
    void shieldComesBack() {
        // after the pause: +rate·t a tick, 10 HP after ~5 s (100 ticks: 0.002·(1+..+100) = 10.1)
        float shield = 0F;
        for (int t = 1; t <= 100; t++) shield += S.rate() * t;
        assertEquals(10.1F, shield, 0.01F);
    }

    @Test
    void inserts() {
        // kevlar 0.9, SAPI 0.85, ESAPI 0.8, XSAPI 0.75, DU 0.85, ghiorsium 0.75 -> 0.9; steel 0.95 and none stay
        for (float ntm : new float[] {0.9F, 0.85F, 0.8F, 0.75F}) assertEquals(0.9F, BalanceKit.insertBullets(ntm, 0.9F), 1e-6F);
        assertEquals(0.95F, BalanceKit.insertBullets(0.95F, 0.9F), 1e-6F);
        assertEquals(1F, BalanceKit.insertBullets(1F, 0.9F), 1e-6F);
    }

    @Test
    void superStimpak() {
        // 25 HP over 240 ticks, evenly, nothing left after
        float[] h = {25F, BalanceKit.STIMPAK_TICKS};
        float healed = 0F, first = BalanceKit.healStep(h);
        healed += first;
        int ticks = 1;
        while (h[1] > 0F) {
            healed += BalanceKit.healStep(h);
            ticks++;
        }
        assertEquals(25F, healed, 1e-3F);
        assertEquals(240, ticks);
        assertEquals(25F / 240F, first, 1e-5F);
        assertEquals(0F, BalanceKit.healStep(h), 0F);
    }

    private static final Pool P = Pool.DEFAULT;

    @Test
    void poolWindowRecover() {
        State s = new State(P.size(), 0);
        assertEquals(Phase.POOL, EuphImmunity.phase(s, 0));
        // all but 1 taken (the pool 150): still invulnerable
        assertTrue(EuphImmunity.hit(s, 10, P.size() - 1F, true, P));
        assertEquals(1F, EuphImmunity.poolAt(s, 10, P), 1e-3F);
        // other damage takes nothing
        assertTrue(EuphImmunity.hit(s, 11, 500F, false, P));
        assertEquals(1F, EuphImmunity.poolAt(s, 11, P), 1e-3F);
        // the last of it: this hit is still called off, the window from the next tick
        assertTrue(EuphImmunity.hit(s, 12, 5F, true, P));
        // another pellet of the same click: called off as well
        assertEquals(Phase.POOL, EuphImmunity.phase(s, 12));
        assertTrue(EuphImmunity.hit(s, 12, 5F, true, P));
        assertEquals(Phase.WINDOW, EuphImmunity.phase(s, 13));
        assertFalse(EuphImmunity.hit(s, 13, 5F, true, P));
        assertFalse(EuphImmunity.hit(s, 13, 5F, false, P));
        // 15 s of it
        assertEquals(Phase.WINDOW, EuphImmunity.phase(s, 12 + 300));
        assertEquals(Phase.RECOVER, EuphImmunity.phase(s, 13 + 300));
        // the recovery: invulnerable, nothing taken, the pool filling over 60 s
        assertTrue(EuphImmunity.hit(s, 13 + 300 + 600, 5000F, true, P));
        assertEquals(P.size() / 2F, EuphImmunity.poolAt(s, 13 + 300 + 600, P), 1F);
        assertEquals(Phase.POOL, EuphImmunity.phase(s, 13 + 300 + 1200));
        assertEquals(P.size(), EuphImmunity.poolAt(s, 13 + 300 + 1200, P), 1e-3F);
        // and again
        assertTrue(EuphImmunity.hit(s, 13 + 300 + 1200, 100F, true, P));
        assertEquals(P.size() - 100F, EuphImmunity.poolAt(s, 13 + 300 + 1200, P), 1e-3F);
    }

    @Test
    void poolRefillsAfterAPause() {
        State s = new State(P.size(), 0);
        float half = P.size() / 2F;
        EuphImmunity.hit(s, 0, half, true, P);
        // under fire nothing comes back
        assertEquals(half, EuphImmunity.poolAt(s, EuphImmunity.REFILL_PAUSE, P), 1e-3F);
        // after the pause: the whole pool over 60 s, half of it in 30 s
        assertEquals(half + P.size() * 200 / 1200, EuphImmunity.poolAt(s, EuphImmunity.REFILL_PAUSE + 200, P), 1e-2F);
        assertEquals(P.size(), EuphImmunity.poolAt(s, EuphImmunity.REFILL_PAUSE + 600, P), 1e-3F);
    }

    @Test
    void poolDrainOfADnsClassSet() {
        // AMAT SM (75, its ammo's neg and pen 0 here) on S 186, tier 7.5: what the pool loses of one hit
        float d = BalanceArmorRule.full(new BalanceArmorRule.Hit(75F, 0F, 0F, true), EuphImmunity.POOL_S, EuphImmunity.POOL_TAU, BalanceArmorRule.Tune.DEFAULT);
        assertTrue(d > 1F && d < 14F, "AMAT on the pool: " + d);
    }

    @Test
    void shippedKeys() throws IOException {
        Properties p = SpreadModelTest.shipped();
        assertEquals("true", p.getProperty("balance.shield"));
        assertEquals("10", p.getProperty("balance.shield.mod"));
        assertEquals("2", p.getProperty("balance.shield.flask"));
        assertEquals("16", p.getProperty("balance.shield.cap"));
        assertEquals("100", p.getProperty("balance.shield.delay"));
        assertEquals("0.002", p.getProperty("balance.shield.rate"));
        assertEquals("true", p.getProperty("balance.medx"));
        assertEquals("true", p.getProperty("balance.superStimpak"));
        assertEquals("true", p.getProperty("balance.inserts"));
        assertEquals("0.9", p.getProperty("balance.inserts.bullets"));
        assertEquals("true", p.getProperty("balance.vanilla0"));
        assertEquals("pool", p.getProperty("balance.immune.mode"));
        assertEquals("150", p.getProperty("balance.immune.pool"));
        assertEquals("15", p.getProperty("balance.immune.window"));
        assertEquals("60", p.getProperty("balance.immune.recover"));
        assertEquals("pool", p.getProperty("balance.immune.dns.mode"));
        assertEquals("40", p.getProperty("balance.immune.dns.pool"));
        assertEquals("15", p.getProperty("balance.immune.dns.window"));
        assertEquals("60", p.getProperty("balance.immune.dns.recover"));
        assertEquals("pool", p.getProperty("balance.immune.fau.mode"));
        assertEquals("45", p.getProperty("balance.immune.fau.pool"));
        assertEquals("15", p.getProperty("balance.immune.fau.window"));
        assertEquals("60", p.getProperty("balance.immune.fau.recover"));
        assertEquals(null, p.getProperty("balance.immune.dns"), "the old DNS switch is balance.immune.dns.mode now");
        // the kits' defaults are the shipped keys' and their drain sets the table's
        assertEquals(40F, EuphImmunity.Kit.DNS.size);
        assertEquals(45F, EuphImmunity.Kit.FAU.size);
        assertEquals(186F, BalanceArmor.SETS.get(EuphImmunity.Kit.DNS.set).s());
        assertEquals(168F, BalanceArmor.SETS.get(EuphImmunity.Kit.FAU.set).s());
    }

    @Test
    void fauPoolOfItsOwnSet() {
        // FAU's pool takes what FAU (S 168) would let through: more than the DNS-class pool of euphemium takes
        var hit = new BalanceArmorRule.Hit(75F, 0F, 0F, true);
        float fau = BalanceArmorRule.full(hit, 168F, 7F, BalanceArmorRule.Tune.DEFAULT), dns = BalanceArmorRule.full(hit, 186F, 7.5F, BalanceArmorRule.Tune.DEFAULT);
        assertTrue(fau > dns, "FAU " + fau + " DNS " + dns);
    }
}
