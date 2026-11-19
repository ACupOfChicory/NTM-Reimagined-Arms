package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// Stage 2 of the balance: the kit's numbers without the game (the shield, the super stimpak's heal, inserts); the
// shipped keys are BalanceKitTest's (26.2)
class BalanceKitBaseTest {
    private static final BalanceKitBase.Shield S = BalanceKitBase.Shield.DEFAULT;

    @Test
    void shield() {
        // the mod alone: 10 (NTM 25)
        assertEquals(10F, BalanceKitBase.maxShield(0F, 25F, S), 1e-4F);
        // one flask: NTM's count 5 -> 2
        assertEquals(2F, BalanceKitBase.maxShield(5F, 0F, S), 1e-4F);
        // 3 flasks and the mod: 6 + 10 = 16, the cap
        assertEquals(16F, BalanceKitBase.maxShield(15F, 25F, S), 1e-4F);
        // 2 flasks and the mod: 14
        assertEquals(14F, BalanceKitBase.maxShield(10F, 25F, S), 1e-4F);
        // NTM's 100 of flasks and the mod: the cap 16
        assertEquals(16F, BalanceKitBase.maxShield(100F, 25F, S), 1e-4F);
        // flasks stop at 8 (NTM's count 40): they alone are at the cap
        assertEquals(40F, BalanceKitBase.flaskLimit(S), 1e-4F);
        assertEquals(16F, BalanceKitBase.maxShield(BalanceKitBase.flaskLimit(S), 0F, S), 1e-4F);
        // NTM's own numbers through the same rule
        BalanceKitBase.Shield ntm = new BalanceKitBase.Shield(25F, 5F, 1000F, 60, 0.005F);
        assertEquals(125F, BalanceKitBase.maxShield(100F, 25F, ntm), 1e-4F);
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
        for (float ntm : new float[] {0.9F, 0.85F, 0.8F, 0.75F}) assertEquals(0.9F, BalanceKitBase.insertBullets(ntm, 0.9F), 1e-6F);
        assertEquals(0.95F, BalanceKitBase.insertBullets(0.95F, 0.9F), 1e-6F);
        assertEquals(1F, BalanceKitBase.insertBullets(1F, 0.9F), 1e-6F);
    }

    @Test
    void superStimpak() {
        // 25 HP over 240 ticks, evenly, nothing left after
        float[] h = {25F, BalanceKitBase.STIMPAK_TICKS};
        float healed = 0F, first = BalanceKitBase.healStep(h);
        healed += first;
        int ticks = 1;
        while (h[1] > 0F) {
            healed += BalanceKitBase.healStep(h);
            ticks++;
        }
        assertEquals(25F, healed, 1e-3F);
        assertEquals(240, ticks);
        assertEquals(25F / 240F, first, 1e-5F);
        assertEquals(0F, BalanceKitBase.healStep(h), 0F);
    }

    @Test
    void healsDoNotStack() {
        // balance.healStack off: a second super stimpak starts the heal again, the more of the two left
        float[] h = {0F, 0F};
        BalanceKitBase.addHeal(h, 25F, false);
        for (int i = 0; i < 120; i++) BalanceKitBase.healStep(h);
        assertEquals(12.5F, h[0], 1e-3F);
        BalanceKitBase.addHeal(h, 25F, false);
        assertEquals(25F, h[0], 1e-4F);
        assertEquals(BalanceKitBase.STIMPAK_TICKS, h[1], 0F);
        // two at once from two hands: 25, not 50
        float[] two = {0F, 0F};
        BalanceKitBase.addHeal(two, 25F, false);
        BalanceKitBase.addHeal(two, 25F, false);
        assertEquals(25F, two[0], 1e-4F);
        // a weaker one later changes nothing: not the heal left, not its time
        for (int i = 0; i < 100; i++) BalanceKitBase.healStep(two);
        float[] was = two.clone();
        BalanceKitBase.addHeal(two, 5F, false);
        assertArrayEquals(was, two, 0F);
        // on: on top, as X884
        float[] old = {0F, 0F};
        BalanceKitBase.addHeal(old, 25F, true);
        BalanceKitBase.addHeal(old, 25F, true);
        assertEquals(50F, old[0], 1e-4F);
    }
}
