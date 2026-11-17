package acup.gunfeel.ammo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

// The new rounds' numbers (owner 2026-11-17): the rubber slug's floor, push, swapped keys and an AI bot's swing; the
// Duplex rear bullet's angle; the nuclear .50's blast (the Fat Mine's times 0.8) and its wear on the AMAT rifles.
class NewAmmoRulesTest {
    // ---------------------------------------------------------------- rubber

    @Test
    void rubberNeverTakesAnyoneBelowFour() {
        assertEquals(6F, RubberRule.floored(6F, 20F), 1e-6F);
        // 20 HP bare: 14, 8, then 4, and nothing more after it
        float hp = 20F;
        int hits = 0;
        while (hits < 20) {
            float d = RubberRule.floored(RubberRule.DAMAGE, hp);
            if (d <= 0F) break;
            hp -= d;
            hits++;
        }
        assertEquals(4F, hp, 1e-6F);
        assertEquals(3, hits);
        assertEquals(1F, RubberRule.floored(6F, 5F), 1e-6F);
        assertEquals(0F, RubberRule.floored(6F, 4F), 1e-6F);
        assertEquals(0F, RubberRule.floored(6F, 2F), 1e-6F);
        // a chicken (4 HP): untouched; a head shot of 10.5 still stops at 4
        assertEquals(0F, RubberRule.floored(10.5F, 4F), 1e-6F);
        assertEquals(16F, RubberRule.floored(100F, 20F), 1e-6F);
    }

    @Test
    void rubberPushesLessFromAfar() {
        assertEquals(RubberRule.KNOCK_NEAR, RubberRule.knock(2), 1e-6F);
        assertEquals(RubberRule.KNOCK_NEAR, RubberRule.knock(10), 1e-6F);
        assertEquals((RubberRule.KNOCK_NEAR + RubberRule.KNOCK_FAR) / 2, RubberRule.knock(17.5), 1e-6F);
        assertEquals(RubberRule.KNOCK_FAR, RubberRule.knock(25), 1e-6F);
        assertEquals(RubberRule.KNOCK_FAR, RubberRule.knock(80), 1e-6F);
    }

    @Test
    void rubberSwapsTheActionsNotSprint() {
        // forward, backward, left, right, jump, shift
        assertArrayEquals(new boolean[] {false, true, false, false, false, false}, RubberRule.swapped(true, false, false, false, false, false));
        assertArrayEquals(new boolean[] {false, false, false, true, false, false}, RubberRule.swapped(false, false, true, false, false, false));
        assertArrayEquals(new boolean[] {false, false, false, false, false, true}, RubberRule.swapped(false, false, false, false, true, false));
        assertArrayEquals(new boolean[] {false, false, false, false, true, false}, RubberRule.swapped(false, false, false, false, false, true));
        assertArrayEquals(new boolean[] {true, true, true, true, true, true}, RubberRule.swapped(true, true, true, true, true, true));
    }

    @Test
    void aBotsAimSwingsForTheConfusionOnlyAndSmoothly() {
        assertArrayEquals(new float[] {0F, 0F}, RubberRule.drift(-1, 1F));
        assertArrayEquals(new float[] {0F, 0F}, RubberRule.drift(RubberRule.CONFUSE_TICKS, 1F));
        float most = 0F, prevYaw = 0F, maxStep = 0F;
        for (int t = 0; t <= RubberRule.CONFUSE_TICKS; t++) {
            float[] d = RubberRule.drift(t, 2.1F);
            most = Math.max(most, Math.abs(d[0]));
            maxStep = Math.max(maxStep, Math.abs(d[0] - prevYaw));
            prevYaw = d[0];
            assertTrue(Math.abs(d[1]) <= RubberRule.PITCH_A + 1e-4F);
        }
        assertTrue(most > 3F, "it swings: " + most);
        assertTrue(most <= RubberRule.YAW_A + RubberRule.YAW_B + 1e-4F, "no more than its amplitudes: " + most);
        // no snap: under 1.5 degrees a tick (30 a second) at any point, the ends' easing included
        assertTrue(maxStep < 1.5F, "a step of " + maxStep);
    }

    // ---------------------------------------------------------------- Duplex

    @Test
    void duplexRearBulletGoesTheSameWayAFewMilliradiansOff() {
        Random r = new Random(7);
        double[] front = {0.3, -0.2, 0.93};
        double sum = 0, sumSq = 0, most = 0;
        int n = 20000;
        for (int i = 0; i < n; i++) {
            double[] rear = DuplexRule.rear(front[0], front[1], front[2], r::nextGaussian);
            double fl = Math.sqrt(front[0] * front[0] + front[1] * front[1] + front[2] * front[2]);
            double rl = Math.sqrt(rear[0] * rear[0] + rear[1] * rear[1] + rear[2] * rear[2]);
            assertEquals(fl, rl, 1e-9, "as fast");
            double a = DuplexRule.angle(front, rear);
            sum += a;
            sumSq += a * a;
            most = Math.max(most, a);
        }
        // the angle of two normal offsets of sigma each (Rayleigh), drawn again past the cut: its mean and mean square
        // within the cut, by sums over it
        double s = DuplexRule.SIGMA, c = DuplexRule.CUT, m1 = 0, m2 = 0, mass = 0;
        int steps = 100000;
        for (int i = 0; i < steps; i++) {
            double x = (i + 0.5) * c / steps, p = x / (s * s) * Math.exp(-x * x / (2 * s * s)) * c / steps;
            mass += p;
            m1 += x * p;
            m2 += x * x * p;
        }
        assertEquals(m1 / mass, sum / n, 0.00005);
        assertEquals(Math.sqrt(m2 / mass), Math.sqrt(sumSq / n), 0.00005);
        // 3 mrad each way, as the owner chose: about 3.45 mrad off on average
        assertTrue(sum / n > 0.0033 && sum / n < 0.0036, "" + sum / n);
        assertTrue(most <= DuplexRule.CUT + 1e-9, "cut: " + most);
    }

    @Test
    void duplexStraightUpStillWorks() {
        double[] rear = DuplexRule.rear(0, 1, 0, new Random(1)::nextGaussian);
        double a = DuplexRule.angle(new double[] {0, 1, 0}, rear);
        assertTrue(a > 0 && a <= DuplexRule.CUT, "" + a);
    }

    // ---------------------------------------------------------------- nuclear .50

    @Test
    void nuclearBlastIsTheFatMinesTimesPointEight() {
        assertEquals(8F, NuclearRule.size(), 1e-6F);
        assertEquals(1.2F, NuclearRule.rad(), 1e-6F);
        // NTM's mines' nuke_damage, 100 by default
        assertEquals(80F, NuclearRule.damage(100.0), 1e-4F);
    }

    @Test
    void nuclearWearsTheRiflesOutInTheirShots() {
        // NTM 1.0.3's durability: AMAT 350, Subtlety 1000, Penance 5000
        check("gun_amat", 350F, 3);
        check("gun_amat_subtlety", 1000F, 6);
        check("gun_amat_penance", 5000F, 10);
        assertEquals(0, NuclearRule.shots("gun_m2"));
        assertEquals(0, NuclearRule.shots("gun_g3"));
        // a used rifle: a fixed share a shot, whatever it had
        assertEquals(350F, NuclearRule.wear(300F, 350F, 3), 1e-3F);
        assertEquals(100F + 350F / 3, NuclearRule.wear(100F, 350F, 3), 1e-3F);
    }

    private static void check(String gun, float durability, int shots) {
        assertEquals(shots, NuclearRule.shots(gun));
        float w = 0F;
        for (int i = 1; i < shots; i++) {
            w = NuclearRule.wear(w, durability, shots);
            assertTrue(w < durability, gun + " worn out after " + i);
        }
        w = NuclearRule.wear(w, durability, shots);
        assertEquals(durability, w, 0F, gun + " worn out after " + shots);
    }
}
