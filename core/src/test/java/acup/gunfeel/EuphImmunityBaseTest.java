package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import acup.gunfeel.EuphImmunityBase.Kit;
import acup.gunfeel.EuphImmunityBase.Phase;
import acup.gunfeel.EuphImmunityBase.Pool;
import acup.gunfeel.EuphImmunityBase.State;

// The pool of euphemium, DNS and FAU without the game: the round of pool, window and recovery, the hold, the refill,
// the state carried to another kit, the kits' numbers by the keys' defaults and the sets their pool drains by.
class EuphImmunityBaseTest {
    private static final Pool P = Pool.DEFAULT;

    @Test
    void poolWindowRecover() {
        State s = new State(P.size(), 0);
        assertEquals(Phase.POOL, EuphImmunityBase.phase(s, 0));
        // all but 1 taken (the pool 150): still invulnerable
        assertTrue(EuphImmunityBase.hit(s, 10, P.size() - 1F, true, true, P, 0));
        assertEquals(1F, EuphImmunityBase.poolAt(s, 10, P), 1e-3F);
        // other damage takes nothing
        assertTrue(EuphImmunityBase.hit(s, 11, 500F, false, false, P, 0));
        assertEquals(1F, EuphImmunityBase.poolAt(s, 11, P), 1e-3F);
        // the last of it: this hit is still called off, the window from the next tick
        assertTrue(EuphImmunityBase.hit(s, 12, 5F, true, true, P, 0));
        // another pellet of the same click: called off as well
        assertEquals(Phase.POOL, EuphImmunityBase.phase(s, 12));
        assertTrue(EuphImmunityBase.hit(s, 12, 5F, true, true, P, 0));
        assertEquals(Phase.WINDOW, EuphImmunityBase.phase(s, 13));
        assertFalse(EuphImmunityBase.hit(s, 13, 5F, true, true, P, 0));
        assertFalse(EuphImmunityBase.hit(s, 13, 5F, false, false, P, 0));
        // 15 s of it
        assertEquals(Phase.WINDOW, EuphImmunityBase.phase(s, 12 + 300));
        assertEquals(Phase.RECOVER, EuphImmunityBase.phase(s, 13 + 300));
        // the recovery: invulnerable, nothing taken, the pool filling over 60 s
        assertTrue(EuphImmunityBase.hit(s, 13 + 300 + 600, 5000F, true, true, P, 0));
        assertEquals(P.size() / 2F, EuphImmunityBase.poolAt(s, 13 + 300 + 600, P), 1F);
        assertEquals(Phase.POOL, EuphImmunityBase.phase(s, 13 + 300 + 1200));
        assertEquals(P.size(), EuphImmunityBase.poolAt(s, 13 + 300 + 1200, P), 1e-3F);
        // and again
        assertTrue(EuphImmunityBase.hit(s, 13 + 300 + 1200, 100F, true, true, P, 0));
        assertEquals(P.size() - 100F, EuphImmunityBase.poolAt(s, 13 + 300 + 1200, P), 1e-3F);
    }

    @Test
    void windowHold() {
        // balance.immune.hold 10 s: a weapon's hit in the window keeps it open 10 s after it, the recovery after that
        Pool p = new Pool(40F, 300, 1200, 200);
        State s = new State(p.size(), 0);
        EuphImmunityBase.hit(s, 0, 40F, true, true, p, 0);
        assertEquals(Phase.WINDOW, EuphImmunityBase.phase(s, 1));
        assertFalse(EuphImmunityBase.hit(s, 290, 1F, true, true, p, 0));
        assertEquals(Phase.WINDOW, EuphImmunityBase.phase(s, 400));
        assertEquals(Phase.RECOVER, EuphImmunityBase.phase(s, 490));
        // what is not a weapon's (fall, fire) does not keep it
        assertFalse(EuphImmunityBase.hit(s, 480, 1F, false, false, p, 0));
        assertEquals(Phase.RECOVER, EuphImmunityBase.phase(s, 490));
        assertEquals(Phase.POOL, EuphImmunityBase.phase(s, 490 + 1200));
        // euphemium: melee is a hit of its pool, but does not hold its window
        State m = new State(p.size(), 0);
        EuphImmunityBase.hit(m, 0, 40F, true, true, p, 0);
        assertFalse(EuphImmunityBase.hit(m, 290, 1F, true, false, p, 0));
        assertEquals(Phase.RECOVER, EuphImmunityBase.phase(m, 301));
        // 0: the window its own 15 s, as X873
        Pool off = new Pool(40F, 300, 1200, 0);
        State o = new State(off.size(), 0);
        EuphImmunityBase.hit(o, 0, 40F, true, true, off, 0);
        EuphImmunityBase.hit(o, 290, 1F, true, true, off, 0);
        assertEquals(Phase.RECOVER, EuphImmunityBase.phase(o, 301));
    }

    @Test
    void reloadHold() {
        // the reload's hold: a gun whose shot and reload from empty are longer than the hold (Henry: 210 ticks)
        // holds the window for that and a second more; a shorter one (Heavy Revolver: 60) the hold's own 10 s
        Pool p = new Pool(40F, 300, 1200, 200);
        assertEquals(200, EuphImmunityBase.holdOf(p, 60));
        assertEquals(200, EuphImmunityBase.holdOf(p, 200));
        assertEquals(210 + EuphImmunityBase.RELOAD_SLACK, EuphImmunityBase.holdOf(p, 210));
        assertEquals(0, EuphImmunityBase.holdOf(new Pool(40F, 300, 1200, 0), 210));
        State s = new State(p.size(), 0);
        EuphImmunityBase.hit(s, 0, 40F, true, true, p, 210);
        assertFalse(EuphImmunityBase.hit(s, 290, 1F, true, true, p, 210));
        // the reload's 10.5 s after the last shot: still the window
        assertEquals(Phase.WINDOW, EuphImmunityBase.phase(s, 290 + 210));
        assertEquals(Phase.RECOVER, EuphImmunityBase.phase(s, 290 + 230));
        // the same hits of a gun with a short reload: the window ends 10 s after the last
        State r = new State(p.size(), 0);
        EuphImmunityBase.hit(r, 0, 40F, true, true, p, 60);
        EuphImmunityBase.hit(r, 290, 1F, true, true, p, 60);
        assertEquals(Phase.RECOVER, EuphImmunityBase.phase(r, 490));
        // in the pool: no refill over such a gun's reload, then the pool's own rate
        State q = new State(p.size(), 0);
        EuphImmunityBase.hit(q, 0, 10F, true, true, p, 210);
        assertEquals(30F, EuphImmunityBase.poolAt(q, 230, p), 1e-3F);
        assertEquals(30F + 40F * 120 / 1200, EuphImmunityBase.poolAt(q, 350, p), 1e-2F);
        // a short gun's hit: the refill pause as before
        EuphImmunityBase.hit(q, 400, 0F, true, true, p, 60);
        assertEquals(0, q.pause);
        // the pause goes with the pool to another kit (balance.immune.shared)
        q.pause = 230;
        assertEquals(230, EuphImmunityBase.carry(q, p, new Pool(45F, 300, 1200, 200), 400).pause);
    }

    @Test
    void poolRefillsAfterAPause() {
        State s = new State(P.size(), 0);
        float half = P.size() / 2F;
        EuphImmunityBase.hit(s, 0, half, true, true, P, 0);
        // under fire nothing comes back
        assertEquals(half, EuphImmunityBase.poolAt(s, EuphImmunityBase.REFILL_PAUSE, P), 1e-3F);
        // after the pause: the whole pool over 60 s, half of it in 30 s
        assertEquals(half + P.size() * 200 / 1200, EuphImmunityBase.poolAt(s, EuphImmunityBase.REFILL_PAUSE + 200, P), 1e-2F);
        assertEquals(P.size(), EuphImmunityBase.poolAt(s, EuphImmunityBase.REFILL_PAUSE + 600, P), 1e-3F);
    }

    @Test
    void poolDrainOfADnsClassSet() {
        // AMAT SM (75, its ammo's neg and pen 0 here) on S 186, tier 7.5: what the pool loses of one hit
        float d = BalanceArmorRule.full(new BalanceArmorRule.Hit(75F, 0F, 0F, true), EuphImmunityBase.POOL_S, EuphImmunityBase.POOL_TAU, BalanceArmorRule.Tune.DEFAULT, 1.0);
        assertTrue(d > 1F && d < 14F, "AMAT on the pool: " + d);
    }

    @Test
    void fauPoolOfItsOwnSet() {
        // FAU's pool takes what FAU (S 168) would let through: more than the DNS-class pool of euphemium takes
        var hit = new BalanceArmorRule.Hit(75F, 0F, 0F, true);
        float fau = BalanceArmorRule.full(hit, 168F, 7F, BalanceArmorRule.Tune.DEFAULT, 1.0), dns = BalanceArmorRule.full(hit, 186F, 7.5F, BalanceArmorRule.Tune.DEFAULT, 1.0);
        assertTrue(fau > dns, "FAU " + fau + " DNS " + dns);
    }

    @Test
    void poolCarriedToAnotherKit() {
        // DNS (40) half drained, FAU (45) put on: half of 45; the window and the recovery as they were
        Pool dns = new Pool(40F, 300, 1200, 200), fau = new Pool(45F, 300, 1200, 200);
        State s = new State(40F, 0L);
        EuphImmunityBase.hit(s, 10L, 20F, true, true, dns, 0);
        State f = EuphImmunityBase.carry(s, dns, fau, 20L);
        assertEquals(22.5F, EuphImmunityBase.poolAt(f, 20L, fau), 1e-3F);
        // emptied: the window goes on in the other kit
        EuphImmunityBase.hit(s, 30L, 25F, true, true, dns, 0);
        assertEquals(Phase.WINDOW, EuphImmunityBase.phase(s, 31L));
        State w = EuphImmunityBase.carry(s, dns, fau, 40L);
        assertEquals(Phase.WINDOW, EuphImmunityBase.phase(w, 40L));
        assertEquals(s.windowEnd, w.windowEnd);
        assertEquals(0F, EuphImmunityBase.poolAt(w, 40L, fau), 0F);
        // in the recovery: invulnerable, and full once it is over
        State r = EuphImmunityBase.carry(s, dns, fau, s.windowEnd + 100L);
        assertEquals(Phase.RECOVER, EuphImmunityBase.phase(r, s.windowEnd + 100L));
        assertEquals(45F, EuphImmunityBase.poolAt(r, s.recoverEnd + 1L, fau), 1e-3F);
    }

    @Test
    void kitsByDefault() {
        assertEquals(45F, Kit.DNS.size);
        assertEquals(40F, Kit.FAU.size);
        assertEquals(150F, Kit.EUPHEMIUM.size);
        for (Kit k : Kit.values()) assertEquals("pool", k.mode(), k.name());
        // the keys' defaults: balance.final.pools (the kits' own sizes) and the pool's own times
        Pool dns = EuphImmunityBase.pool(Kit.DNS);
        assertEquals(45F, dns.size());
        assertEquals(P.window(), dns.window());
        assertEquals(P.recover(), dns.recover());
        assertEquals(P.hold(), dns.hold());
        assertEquals(150F, EuphImmunityBase.pool(Kit.EUPHEMIUM).size());
        assertEquals(EuphImmunityBase.REFILL_PAUSE, EuphImmunityBase.refillPause());
    }

    @Test
    void drainSets() {
        // euphemium's pool counts by a DNS-class set, DNS and FAU by their own
        assertArrayEquals(new float[] {EuphImmunityBase.POOL_S, EuphImmunityBase.POOL_TAU}, EuphImmunityBase.drainSet(Kit.EUPHEMIUM));
        assertArrayEquals(new float[] {186F, 7.5F}, EuphImmunityBase.drainSet(Kit.DNS));
        assertArrayEquals(new float[] {168F, 7F}, EuphImmunityBase.drainSet(Kit.FAU));
    }
}
