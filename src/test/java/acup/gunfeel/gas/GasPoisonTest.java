package acup.gunfeel.gas;

import static acup.gunfeel.smoke.SmokeGrid.OX;
import static acup.gunfeel.smoke.SmokeGrid.OY;
import static acup.gunfeel.smoke.SmokeGrid.OZ;
import static acup.gunfeel.smoke.SmokeGrid.SX;
import static acup.gunfeel.smoke.SmokeGrid.SZ;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import acup.gunfeel.smoke.SmokeGrid;

/**
 * How long each gas has to lie for the ground to turn (GasPoison.THRESHOLD): the server's sum of one grenade's cloud and
 * of grenades fired one after another at one place, worked out the way the server does it.
 */
class GasPoisonTest {
    private static final SmokeGrid.Mask FIELD = (x, y, z) -> y < OY;

    // the sums of the biome cells (4 by 4 columns) after grenades let out every `gap` ticks at one place
    private static float most(GasKind kind, int grenades, int gap) {
        Map<Long, Float> sums = new HashMap<>();
        Map<Long, Long> at = new HashMap<>();
        java.util.List<SmokeGrid> live = new java.util.ArrayList<>();
        int end = grenades * gap + kind.smoke().maxAge();
        float best = 0F;
        for (int t = 0; t <= end; t++) {
            if (t % gap == 0 && t / gap < grenades) {
                SmokeGrid g = new SmokeGrid(kind.smoke(), 11L + t, 1F, 0F, 1F);
                g.scan(FIELD);
                g.place();
                live.add(g);
            }
            for (SmokeGrid g : live) g.step();
            if (t % GasPoison.EVERY != 0) continue;
            for (SmokeGrid g : live) for (int i : g.floorCells(GasPoison.LEAST)) {
                int x = i % SX, z = i / SX % SZ;
                long k = GasPoison.key(x >> 2, z >> 2);
                float before = GasPoison.decayed(sums.getOrDefault(k, 0F), t - at.getOrDefault(k, (long) t));
                float now = before + Math.min(1F, g.density(i)) * GasPoison.weight(kind) * GasPoison.EVERY / 16F;
                sums.put(k, now);
                at.put(k, (long) t);
                best = Math.max(best, now);
            }
        }
        return best;
    }

    // magazines of 8 fired a second apart, a reload between them taken as no time
    private static float magazines(GasKind kind, int n) {
        return most(kind, 8 * n, 20);
    }

    @Test
    void oneGrenadeIsFarFromIt() {
        for (GasKind k : GasKind.values()) {
            float one = most(k, 1, 1);
            System.out.println(k.key + ": one grenade's most " + one + " of " + GasPoison.THRESHOLD);
            assertTrue(one < GasPoison.THRESHOLD * 0.2F, k.key + ", one grenade: " + one);
        }
    }

    @Test
    void mustardTurnsItWithAMagazine() {
        assertTrue(most(GasKind.MUSTARD, 4, 20) < GasPoison.THRESHOLD, "half a magazine");
        assertTrue(magazines(GasKind.MUSTARD, 1) >= GasPoison.THRESHOLD);
    }

    @Test
    void chlorineAndAcidWithTwo() {
        for (GasKind k : new GasKind[] {GasKind.CHLORINE, GasKind.ACID}) {
            float one = magazines(k, 1), two = magazines(k, 2);
            System.out.println(k.key + ": one magazine " + one + ", two " + two);
            assertTrue(one < GasPoison.THRESHOLD, k.key + ", one: " + one);
            assertTrue(two >= GasPoison.THRESHOLD, k.key + ", two: " + two);
        }
    }

    @Test
    void phosgeneWithThree() {
        float two = magazines(GasKind.PHOSGENE, 2), three = magazines(GasKind.PHOSGENE, 3);
        System.out.println("phosgene: two magazines " + two + ", three " + three);
        assertTrue(two < GasPoison.THRESHOLD, "two: " + two);
        assertTrue(three >= GasPoison.THRESHOLD, "three: " + three);
    }

    @Test
    void grenadesNowAndThenNeverAddUp() {
        // one every 5 minutes for ever: the sum halves every 10 minutes, so it settles far under the threshold
        for (GasKind k : GasKind.values()) {
            float slow = most(k, 12, 6000);
            System.out.println(k.key + ": one every 5 min " + slow);
            assertTrue(slow < GasPoison.THRESHOLD * 0.6F, k.key + ", slow: " + slow);
        }
    }
}
