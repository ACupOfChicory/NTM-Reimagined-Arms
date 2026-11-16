package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/** How many of NTM's casings are drawn: the nearest casingMax of those in view, by squared distance. */
class CasingCullTest {
    @Test
    void theLimitKeepsTheNearestMax() {
        Random r = new Random(7);
        double[] d2 = new double[300];
        for (int i = 0; i < d2.length; i++) d2[i] = r.nextDouble() * 2000;
        for (int max : new int[] {1, 5, 96, 299}) {
            double limit = CasingCull.nearest(d2, d2.length, max);
            int kept = 0;
            double farthestKept = 0, nearestLeft = Double.MAX_VALUE;
            for (double d : d2) {
                if (d <= limit) {
                    kept++;
                    farthestKept = Math.max(farthestKept, d);
                } else nearestLeft = Math.min(nearestLeft, d);
            }
            assertEquals(max, kept, "max " + max);
            assertTrue(farthestKept < nearestLeft, "max " + max + ": a nearer one left out");
        }
    }

    @Test
    void noLimitBelowZeroOrWhenThereAreFewer() {
        double[] d2 = {4, 1, 9};
        assertEquals(Double.MAX_VALUE, CasingCull.nearest(d2, 3, -1));
        assertEquals(Double.MAX_VALUE, CasingCull.nearest(d2, 3, 3));
        assertEquals(Double.MAX_VALUE, CasingCull.nearest(d2, 3, 96));
        assertEquals(1, CasingCull.nearest(d2, 3, 1));
        assertTrue(CasingCull.nearest(d2, 3, 0) < 0, "0: none drawn");
    }

    @Test
    void onlyTheFirstNCountWhenTheArrayIsLonger() {
        double[] d2 = {9, 1, 4, 0.5, 0.25};
        assertEquals(4, CasingCull.nearest(d2, 3, 2));
    }
}
