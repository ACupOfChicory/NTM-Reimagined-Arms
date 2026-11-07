package acup.gunfeel.gl54.frag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import acup.gunfeel.gl54.GrenadeKind;

class FragTest {
    private static final double[][] FLIGHTS = {{0, 0, 1}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0.3, -0.7, 0.64}, {-5, 0.2, 2}};

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    @Test
    void conesFragmentsStayInsideTheConeAndRingOnesInsideTheBand() {
        for (FragSpec spec : new FragSpec[] {FragSpec.AIRBURST, FragSpec.AP, FragSpec.INCENDIARY})
            for (double[] f : FLIGHTS) {
                double[] out = new double[3];
                for (long seed = 0; seed < 300; seed++)
                    for (int i = 0; i < spec.rays(); i++) {
                        FragPattern.direction(spec, seed, i, f[0], f[1], f[2], out);
                        assertEquals(1.0, Math.sqrt(dot(out, out)), 1e-9);
                        double angle = FragPattern.angle(out, f[0], f[1], f[2]);
                        if (i < spec.cone()) assertTrue(angle <= spec.coneDegrees() / 2 + 1e-6, spec.kind() + " cone ray at " + angle);
                        else assertTrue(Math.abs(angle - 90) <= FragSpec.RING_DEGREES + 1e-6, spec.kind() + " ring ray at " + angle);
                    }
            }
    }

    @Test
    void thereAreAtMostEightyRaysAndTheNumbersAreTheDesigns() {
        assertEquals(80, FragSpec.AIRBURST.rays());
        assertEquals(40, FragSpec.AP.rays());
        assertEquals(40, FragSpec.INCENDIARY.rays());
        for (GrenadeKind k : GrenadeKind.values()) {
            FragSpec s = FragSpec.of(k);
            if (s != null) assertTrue(s.rays() <= FragSpec.MAX_RAYS);
        }
        assertEquals(65.0, FragSpec.AIRBURST.coneDegrees());
        assertEquals(40, FragSpec.AIRBURST.cone());
        assertEquals(1.25F, FragSpec.AIRBURST.damage());
        assertEquals(30.0, FragSpec.AP.coneDegrees());
        assertEquals(20, FragSpec.AP.cone());
        assertEquals(20, FragSpec.AP.ring());
        assertEquals(0.75F, FragSpec.INCENDIARY.damage());
        assertEquals(65.0, FragSpec.INCENDIARY.coneDegrees());
        assertEquals(6.0, FragSpec.RANGE);
        assertEquals(15.0, FragSpec.RING_DEGREES);
    }

    @Test
    void onlyThreeKindsAreFragments() {
        assertNotNull(FragSpec.of(GrenadeKind.AIRBURST));
        assertNotNull(FragSpec.of(GrenadeKind.AP));
        assertNotNull(FragSpec.of(GrenadeKind.INCENDIARY));
        for (GrenadeKind k : new GrenadeKind[] {GrenadeKind.HE, GrenadeKind.SMOKE, GrenadeKind.CHLORINE, GrenadeKind.PHOSGENE, GrenadeKind.MUSTARD, GrenadeKind.ACID})
            assertNull(FragSpec.of(k), k.toString());
        assertNull(FragSpec.of(null));
    }

    @Test
    void apPiercesArmourAndAirburstAndIncendiaryDoNot() {
        assertEquals(4F, FragSpec.AP.threshold());
        assertEquals(0.1F, FragSpec.AP.piercing());
        assertTrue(FragSpec.AP.armourPiercing());
        assertFalse(FragSpec.AIRBURST.armourPiercing());
        assertEquals(0F, FragSpec.AIRBURST.threshold());
        assertEquals(0F, FragSpec.AIRBURST.piercing());
        assertFalse(FragSpec.INCENDIARY.armourPiercing());
    }

    @Test
    void incendiaryFragmentsLightForFourSecondsAndTheOthersDoNot() {
        assertEquals(80, FragSpec.INCENDIARY.igniteTicks());
        assertEquals(0, FragSpec.AIRBURST.igniteTicks());
        assertEquals(0, FragSpec.AP.igniteTicks());
    }

    // the cone is even by solid angle: the cosine of the angle off the flight is even between cos(half) and 1, the turn round it is even
    @Test
    void theConeIsEvenBySolidAngle() {
        FragSpec spec = FragSpec.AIRBURST;
        int bins = 8;
        int[] cos = new int[bins], turn = new int[bins];
        double[] out = new double[3], f = {0, 0, 1};
        double lo = Math.cos(Math.toRadians(spec.coneDegrees() / 2));
        int total = 0;
        for (long seed = 1; seed <= 2000; seed++)
            for (int i = 0; i < spec.cone(); i++) {
                FragPattern.direction(spec, seed * 7919, i, f[0], f[1], f[2], out);
                double c = out[2];
                cos[Math.min(bins - 1, (int) ((c - lo) / (1 - lo) * bins))]++;
                double a = Math.atan2(out[1], out[0]) + Math.PI;
                turn[Math.min(bins - 1, (int) (a / (2 * Math.PI) * bins))]++;
                total++;
            }
        for (int b = 0; b < bins; b++) {
            assertEquals(total / (double) bins, cos[b], total / (double) bins * 0.05, "cos bin " + b);
            assertEquals(total / (double) bins, turn[b], total / (double) bins * 0.05, "turn bin " + b);
        }
    }

    // the ring is even by solid angle: the height across the flight is even in sin(latitude)
    @Test
    void theRingIsEvenBySolidAngle() {
        FragSpec spec = FragSpec.AIRBURST;
        int bins = 8;
        int[] height = new int[bins], turn = new int[bins];
        double[] out = new double[3], f = {0, 0, 1};
        double top = Math.sin(Math.toRadians(FragSpec.RING_DEGREES));
        int total = 0;
        for (long seed = 1; seed <= 2000; seed++)
            for (int i = spec.cone(); i < spec.rays(); i++) {
                FragPattern.direction(spec, seed * 104729, i, f[0], f[1], f[2], out);
                height[Math.min(bins - 1, (int) ((out[2] + top) / (2 * top) * bins))]++;
                double a = Math.atan2(out[1], out[0]) + Math.PI;
                turn[Math.min(bins - 1, (int) (a / (2 * Math.PI) * bins))]++;
                total++;
            }
        for (int b = 0; b < bins; b++) {
            assertEquals(total / (double) bins, height[b], total / (double) bins * 0.05, "height bin " + b);
            assertEquals(total / (double) bins, turn[b], total / (double) bins * 0.05, "turn bin " + b);
        }
    }

    // the client and the server make the rays from the same numbers: the seed and the direction as the packet carries it
    @Test
    void theSeedMakesTheSameRaysEveryTime() {
        double[] a = new double[3], b = new double[3];
        double fx = (float) 0.31234567891, fy = (float) -0.5123456, fz = (float) 0.80123456;
        for (FragSpec spec : new FragSpec[] {FragSpec.AIRBURST, FragSpec.AP, FragSpec.INCENDIARY})
            for (int i = 0; i < spec.rays(); i++) {
                FragPattern.direction(spec, 0x1234_5678_9ABCL, i, fx, fy, fz, a);
                FragPattern.direction(spec, 0x1234_5678_9ABCL, i, fx, fy, fz, b);
                assertArrayEquals(a, b);
            }
        FragPattern.direction(FragSpec.AIRBURST, 1, 5, 0, 0, 1, a);
        FragPattern.direction(FragSpec.AIRBURST, 2, 5, 0, 0, 1, b);
        assertFalse(java.util.Arrays.equals(a, b));
    }

    @Test
    void aBurstsRaysDifferFromEachOther() {
        double[] a = new double[3], b = new double[3];
        FragPattern.direction(FragSpec.AIRBURST, 42, 3, 0, 0, 1, a);
        FragPattern.direction(FragSpec.AIRBURST, 42, 4, 0, 0, 1, b);
        assertFalse(java.util.Arrays.equals(a, b));
    }

    @Test
    void thePerFlightBasisWorksForAStraightUpAndDownFlight() {
        double[] out = new double[3];
        for (double[] f : new double[][] {{0, 1, 0}, {0, -1, 0}, {1e-9, 1, 0}}) {
            FragPattern.direction(FragSpec.AIRBURST, 5, 0, f[0], f[1], f[2], out);
            assertEquals(1.0, Math.sqrt(dot(out, out)), 1e-9);
            assertFalse(Double.isNaN(out[0] + out[1] + out[2]));
        }
    }

    @Test
    void groundFireRulesAreTheIncendiarysNumbers() {
        assertEquals(2.5F, FragFireRules.radius());
        assertEquals(100, FragFireRules.TICKS);
        assertEquals(2F, FragFireRules.HEIGHT);
        assertTrue(FragFireRules.ignites(true, 1));
        assertTrue(FragFireRules.ignites(true, 6));
        assertFalse(FragFireRules.ignites(true, 0));
        assertFalse(FragFireRules.ignites(false, 3));
        assertFalse(FragFireRules.ignites(false, 0));
    }

    @Test
    void theBoxTestFindsABoxOnTheRayWithinReach() {
        double[] box = {-0.3, 1, 2, 0.3, 3, 2.6};
        assertTrue(FragBurst.crosses(0, 2, 0, 0, 0, 1, box, 0, 6));
        assertFalse(FragBurst.crosses(0, 2, 0, 0, 0, 1, box, 0, 1.5));
        assertFalse(FragBurst.crosses(0, 2, 0, 0, 0, -1, box, 0, 6));
        assertFalse(FragBurst.crosses(1, 2, 0, 0, 0, 1, box, 0, 6));
        assertTrue(FragBurst.crosses(0, 2.2, 2.3, 1, 0, 0, box, 0, 6));
        assertTrue(FragBurst.crosses(0, 0, 2.3, 0, 1, 0, box, 0, 6));
    }
}
