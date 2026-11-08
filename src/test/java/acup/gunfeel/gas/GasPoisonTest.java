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
 * How long the mustard has to lie for the ground to turn (GasPoison.THRESHOLD): the server's sum of one grenade's cloud
 * and of grenades fired one after another at one place, worked out the way the server does it.
 */
class GasPoisonTest {
    private static final SmokeGrid.Mask FIELD = (x, y, z) -> y < OY;

    // the sums of the biome cells (4 by 4 columns) after grenades let out every `gap` ticks at one place
    private static float most(int grenades, int gap) {
        Map<Long, Float> sums = new HashMap<>();
        Map<Long, Long> at = new HashMap<>();
        java.util.List<SmokeGrid> live = new java.util.ArrayList<>();
        int end = grenades * gap + GasKind.MUSTARD.smoke().maxAge();
        float best = 0F;
        for (int t = 0; t <= end; t++) {
            if (t % gap == 0 && t / gap < grenades) {
                SmokeGrid g = new SmokeGrid(GasKind.MUSTARD.smoke(), 11L + t, 1F, 0F, 1F);
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
                float now = before + Math.min(1F, g.density(i)) * GasPoison.EVERY / 16F;
                sums.put(k, now);
                at.put(k, (long) t);
                best = Math.max(best, now);
            }
        }
        return best;
    }

    @Test
    void oneGrenadeIsFarFromIt() {
        float one = most(1, 1);
        System.out.println("mustard: one grenade's most " + one + " of " + GasPoison.THRESHOLD);
        assertTrue(one < GasPoison.THRESHOLD * 0.2F, "one grenade: " + one);
    }

    @Test
    void aMagazineIsNotEnoughButTwoAre() {
        // a magazine of 8 fired a second apart, and two of them with a reload between
        float eight = most(8, 20), sixteen = most(16, 20);
        System.out.println("mustard: 8 grenades " + eight + ", 16 grenades " + sixteen + " of " + GasPoison.THRESHOLD);
        assertTrue(eight < GasPoison.THRESHOLD, "8: " + eight);
        assertTrue(sixteen >= GasPoison.THRESHOLD, "16: " + sixteen);
    }

    @Test
    void grenadesNowAndThenNeverAddUp() {
        // one every 5 minutes for ever: the sum halves every 10 minutes, so it settles far under the threshold
        float slow = most(12, 6000);
        System.out.println("mustard: one every 5 min " + slow);
        assertTrue(slow < GasPoison.THRESHOLD * 0.5F, "slow: " + slow);
    }
}
