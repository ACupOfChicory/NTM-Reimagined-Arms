package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    void windowHold() {
        // balance.immune.hold 10 s: a weapon's hit in the window keeps it open 10 s after it, the recovery after that
        Pool p = new Pool(40F, 300, 1200, 200);
        State s = new State(p.size(), 0);
        EuphImmunity.hit(s, 0, 40F, true, p);
        assertEquals(Phase.WINDOW, EuphImmunity.phase(s, 1));
        assertFalse(EuphImmunity.hit(s, 290, 1F, true, p));
        assertEquals(Phase.WINDOW, EuphImmunity.phase(s, 400));
        assertEquals(Phase.RECOVER, EuphImmunity.phase(s, 490));
        // what is not a weapon's (fall, fire) does not keep it
        assertFalse(EuphImmunity.hit(s, 480, 1F, false, p));
        assertEquals(Phase.RECOVER, EuphImmunity.phase(s, 490));
        assertEquals(Phase.POOL, EuphImmunity.phase(s, 490 + 1200));
        // euphemium: melee is a hit of its pool, but does not hold its window
        State m = new State(p.size(), 0);
        EuphImmunity.hit(m, 0, 40F, true, p);
        assertFalse(EuphImmunity.hit(m, 290, 1F, true, false, p));
        assertEquals(Phase.RECOVER, EuphImmunity.phase(m, 301));
        // 0: the window its own 15 s, as X873
        Pool off = new Pool(40F, 300, 1200, 0);
        State o = new State(off.size(), 0);
        EuphImmunity.hit(o, 0, 40F, true, off);
        EuphImmunity.hit(o, 290, 1F, true, off);
        assertEquals(Phase.RECOVER, EuphImmunity.phase(o, 301));
    }

    @Test
    void reloadHold() {
        // balance.immune.reloadHold: a gun whose shot and reload from empty are longer than the hold (Henry: 210 ticks)
        // holds the window for that and a second more; a shorter one (Heavy Revolver: 60) the hold's own 10 s
        Pool p = new Pool(40F, 300, 1200, 200);
        assertEquals(200, EuphImmunity.holdOf(p, 60));
        assertEquals(200, EuphImmunity.holdOf(p, 200));
        assertEquals(210 + EuphImmunity.RELOAD_SLACK, EuphImmunity.holdOf(p, 210));
        assertEquals(0, EuphImmunity.holdOf(new Pool(40F, 300, 1200, 0), 210));
        State s = new State(p.size(), 0);
        EuphImmunity.hit(s, 0, 40F, true, true, p, 210);
        assertFalse(EuphImmunity.hit(s, 290, 1F, true, true, p, 210));
        // the reload's 10.5 s after the last shot: still the window
        assertEquals(Phase.WINDOW, EuphImmunity.phase(s, 290 + 210));
        assertEquals(Phase.RECOVER, EuphImmunity.phase(s, 290 + 230));
        // the same hits of a gun with a short reload: the window ends 10 s after the last
        State r = new State(p.size(), 0);
        EuphImmunity.hit(r, 0, 40F, true, true, p, 60);
        EuphImmunity.hit(r, 290, 1F, true, true, p, 60);
        assertEquals(Phase.RECOVER, EuphImmunity.phase(r, 490));
        // in the pool: no refill over such a gun's reload, then the pool's own rate
        State q = new State(p.size(), 0);
        EuphImmunity.hit(q, 0, 10F, true, true, p, 210);
        assertEquals(30F, EuphImmunity.poolAt(q, 230, p), 1e-3F);
        assertEquals(30F + 40F * 120 / 1200, EuphImmunity.poolAt(q, 350, p), 1e-2F);
        // a short gun's hit: the refill pause as before
        EuphImmunity.hit(q, 400, 0F, true, true, p, 60);
        assertEquals(0, q.pause);
        // the pause goes with the pool to another kit (balance.immune.shared)
        q.pause = 230;
        assertEquals(230, EuphImmunity.carry(q, p, new Pool(45F, 300, 1200, 200), 400).pause);
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
        assertEquals("10", p.getProperty("balance.immune.hold"));
        // balance.immune.refillPause 5 s, as X875 (Henry and Lincoln: balance.immune.reloadHold)
        assertEquals("5", p.getProperty("balance.immune.refillPause"));
        assertEquals(EuphImmunity.REFILL_PAUSE, EuphImmunity.refillPause());
        assertEquals(200, Pool.DEFAULT.hold());
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

    @Test
    void healsDoNotStack() {
        // balance.healStack off: a second super stimpak starts the heal again, the more of the two left
        float[] h = {0F, 0F};
        BalanceKit.addHeal(h, 25F, false);
        for (int i = 0; i < 120; i++) BalanceKit.healStep(h);
        assertEquals(12.5F, h[0], 1e-3F);
        BalanceKit.addHeal(h, 25F, false);
        assertEquals(25F, h[0], 1e-4F);
        assertEquals(BalanceKit.STIMPAK_TICKS, h[1], 0F);
        // two at once from two hands: 25, not 50
        float[] two = {0F, 0F};
        BalanceKit.addHeal(two, 25F, false);
        BalanceKit.addHeal(two, 25F, false);
        assertEquals(25F, two[0], 1e-4F);
        // a weaker one later changes nothing: not the heal left, not its time
        for (int i = 0; i < 100; i++) BalanceKit.healStep(two);
        float[] was = two.clone();
        BalanceKit.addHeal(two, 5F, false);
        assertArrayEquals(was, two, 0F);
        // on: on top, as X884
        float[] old = {0F, 0F};
        BalanceKit.addHeal(old, 25F, true);
        BalanceKit.addHeal(old, 25F, true);
        assertEquals(50F, old[0], 1e-4F);
    }

    @Test
    void poolCarriedToAnotherKit() {
        // DNS (40) half drained, FAU (45) put on: half of 45; the window and the recovery as they were
        Pool dns = new Pool(40F, 300, 1200, 200), fau = new Pool(45F, 300, 1200, 200);
        State s = new State(40F, 0L);
        EuphImmunity.hit(s, 10L, 20F, true, dns);
        State f = EuphImmunity.carry(s, dns, fau, 20L);
        assertEquals(22.5F, EuphImmunity.poolAt(f, 20L, fau), 1e-3F);
        // emptied: the window goes on in the other kit
        EuphImmunity.hit(s, 30L, 25F, true, dns);
        assertEquals(Phase.WINDOW, EuphImmunity.phase(s, 31L));
        State w = EuphImmunity.carry(s, dns, fau, 40L);
        assertEquals(Phase.WINDOW, EuphImmunity.phase(w, 40L));
        assertEquals(s.windowEnd, w.windowEnd);
        assertEquals(0F, EuphImmunity.poolAt(w, 40L, fau), 0F);
        // in the recovery: invulnerable, and full once it is over
        State r = EuphImmunity.carry(s, dns, fau, s.windowEnd + 100L);
        assertEquals(Phase.RECOVER, EuphImmunity.phase(r, s.windowEnd + 100L));
        assertEquals(45F, EuphImmunity.poolAt(r, s.recoverEnd + 1L, fau), 1e-3F);
    }
}
