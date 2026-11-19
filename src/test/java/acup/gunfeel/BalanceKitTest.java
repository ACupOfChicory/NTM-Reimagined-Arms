package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import acup.gunfeel.EuphImmunityBase.Pool;
// Stage 2 of the balance: the kit (shield, medicine, inserts) without the game; euphemium's pool is EuphImmunityBaseTest's (core).
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
        // balance.immune.refillPause 5 s, as X875 (Henry and Lincoln: their reload's hold)
        assertEquals("5", p.getProperty("balance.immune.refillPause"));
        assertEquals(EuphImmunity.REFILL_PAUSE, EuphImmunity.refillPause());
        assertEquals(200, Pool.DEFAULT.hold());
        assertEquals("pool", p.getProperty("balance.immune.dns.mode"));
        assertEquals("45", p.getProperty("balance.immune.dns.pool"));
        assertEquals("15", p.getProperty("balance.immune.dns.window"));
        assertEquals("60", p.getProperty("balance.immune.dns.recover"));
        assertEquals("pool", p.getProperty("balance.immune.fau.mode"));
        assertEquals("40", p.getProperty("balance.immune.fau.pool"));
        assertEquals("15", p.getProperty("balance.immune.fau.window"));
        assertEquals("60", p.getProperty("balance.immune.fau.recover"));
        assertEquals(null, p.getProperty("balance.immune.dns"), "the old DNS switch is balance.immune.dns.mode now");
        // the kits' defaults are the shipped keys' and their drain sets the table's
        assertEquals(45F, EuphImmunity.Kit.DNS.size);
        assertEquals(40F, EuphImmunity.Kit.FAU.size);
        assertEquals(186F, BalanceArmor.SETS.get(EuphImmunity.Kit.DNS.set).s());
        assertEquals(168F, BalanceArmor.SETS.get(EuphImmunity.Kit.FAU.set).s());
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
}
