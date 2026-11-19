package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// The damage cap's rule (DamageCapRule): a click's cap, the 14-tick window, the head and the sniper's head, the shield.
class DamageCapRuleTest {
    private static final DamageCapRule.Limits L = DamageCapRule.Limits.DEFAULT;
    private static final float HP = 20F;

    private static float hit(DamageCapRule.Ledger l, long now, long click, boolean head, boolean sniper, float damage) {
        return DamageCapRule.take(l, L, now, click, head, sniper, HP, 0F, damage).damage();
    }

    @Test
    void doubleShotIsOneClick() {
        // SPAS-12's secondary: two slugs of 12 each in one tick, 60 % of 20 is 12
        var l = new DamageCapRule.Ledger();
        assertEquals(12F, hit(l, 0, 0, false, false, 12F), 1e-4);
        assertEquals(0F, hit(l, 0, 0, false, false, 12F), 1e-4);
        // the next click, 20 ticks on: the window is past, 12 more
        assertEquals(12F, hit(l, 20, 20, false, false, 12F), 1e-4);
    }

    @Test
    void pelletsOfOneClickAddUpLate() {
        // twenty pellets of 2, some landing a tick or two later (a held client hit): one click, 12 at most
        var l = new DamageCapRule.Ledger();
        float sum = 0;
        for (int i = 0; i < 20; i++) sum += hit(l, i / 8, 0, false, false, 2F);
        assertEquals(12F, sum, 1e-4);
    }

    @Test
    void headCapIsHigher() {
        var l = new DamageCapRule.Ledger();
        assertEquals(16F, hit(l, 0, 0, true, false, 30F), 1e-4);
    }

    @Test
    void sniperHeadKillsBodyTakesTwo() {
        var head = new DamageCapRule.Ledger();
        assertEquals(20F, hit(head, 0, 0, true, true, 40F), 1e-4);
        var body = new DamageCapRule.Ledger();
        float first = hit(body, 0, 0, false, true, 40F);
        assertEquals(12F, first, 1e-4);
        // AMAT's next shot, 25 ticks on: the rest
        assertTrue(first + hit(body, 25, 25, false, true, 40F) >= HP);
    }

    @Test
    void burstIsNoFasterThanTheWindow() {
        // a minigun's bullet every tick, 10 each (more than enough): the player is dead no sooner than 14 ticks on
        var l = new DamageCapRule.Ledger();
        float sum = 0;
        int dead = -1;
        for (int t = 0; t < 40 && dead < 0; t++) {
            sum += hit(l, t, t, false, false, 10F);
            if (sum >= HP - 1e-4) dead = t;
        }
        assertTrue(dead >= 14, "dead at tick " + dead);
        // and in any 14 ticks no more than 80 %
        var w = new DamageCapRule.Ledger();
        float[] per = new float[60];
        for (int t = 0; t < 60; t++) per[t] = hit(w, t, t, false, false, 3F);
        for (int t = 0; t + 14 <= 60; t++) {
            float s = 0;
            for (int i = t; i < t + 14; i++) s += per[i];
            assertTrue(s <= 0.8F * HP + 1e-4, "window at " + t + ": " + s);
        }
    }

    @Test
    void shieldCountsAndOverCapComesBack() {
        var l = new DamageCapRule.Ledger();
        // the shield took 6, 10 left for health: 12 in all
        var cut = DamageCapRule.take(l, L, 0, 0, false, false, HP, 6F, 10F);
        assertEquals(6F, cut.damage(), 1e-4);
        assertEquals(0F, cut.shield(), 1e-4);
        // a full shield that took 15 on its own: 3 of it back, nothing to health
        var l2 = new DamageCapRule.Ledger();
        var cut2 = DamageCapRule.take(l2, L, 0, 0, false, false, HP, 15F, 4F);
        assertEquals(0F, cut2.damage(), 1e-4);
        assertEquals(3F, cut2.shield(), 1e-4);
    }

    @Test
    void ledgerForgets() {
        var l = new DamageCapRule.Ledger();
        hit(l, 0, 0, false, false, 12F);
        assertTrue(!l.idle(10));
        assertTrue(l.idle(DamageCapRule.KEEP_TICKS));
        // a click long gone takes its own cap again (a slow shell's click is never this old)
        assertEquals(12F, hit(l, DamageCapRule.KEEP_TICKS, 0, false, false, 12F), 1e-4);
    }
}
