package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

// Rng, GunClock and Trace's numbers: what makes two runs of a scenario comparable (Trace, tools/trace_diff.py)
class DeterminismTest {
    @AfterEach
    void real() {
        GunClock.use(null);
    }

    private static double[] draws(Random r, int n) {
        double[] d = new double[n];
        for (int i = 0; i < n; i++) d[i] = r.nextDouble();
        return d;
    }

    @Test
    void theSameSeedDrawsTheSame() {
        Rng.seed(71294);
        Rng.announce(500);
        Rng.announce(501);
        double[] shot = draws(Rng.shot(501), 8), camera = draws(Rng.camera(), 8), model = draws(Rng.model(), 8);
        long spread = Rng.spread();
        Rng.seed(71294);
        Rng.announce(500);
        Rng.announce(501);
        assertEquals(java.util.Arrays.toString(shot), java.util.Arrays.toString(draws(Rng.shot(501), 8)));
        assertEquals(java.util.Arrays.toString(camera), java.util.Arrays.toString(draws(Rng.camera(), 8)));
        assertEquals(java.util.Arrays.toString(model), java.util.Arrays.toString(draws(Rng.model(), 8)));
        assertEquals(spread, Rng.spread());
    }

    // a shot's marks draw by its place among the announced shots, not by its id or the order the marks are made in
    @Test
    void aShotDrawsByItsPlaceNotByTurns() {
        Rng.seed(1);
        Rng.announce(7);
        Rng.announce(9);
        double second = Rng.shot(9).nextDouble();
        double first = Rng.shot(7).nextDouble();
        Rng.seed(1);
        Rng.announce(40);
        Rng.announce(41);
        assertEquals(first, Rng.shot(40).nextDouble());
        assertEquals(second, Rng.shot(41).nextDouble());
        assertNotEquals(first, second);
    }

    @Test
    void anotherSeedDrawsOtherwise() {
        Rng.seed(1);
        double a = Rng.shot(-1).nextDouble();
        Rng.seed(2);
        assertNotEquals(a, Rng.shot(-1).nextDouble());
    }

    // the distributions the old Math.random gave: uniform on [0, 1), each stream its own
    @Test
    void drawsStayUniform() {
        Rng.seed(3);
        int[] bins = new int[10];
        Random r = Rng.shot(-1);
        for (int i = 0; i < 100_000; i++) {
            double d = r.nextDouble();
            assertTrue(d >= 0 && d < 1);
            bins[(int) (d * 10)]++;
        }
        for (int b : bins) assertTrue(Math.abs(b - 10_000) < 500, "bin " + b);
        assertNotEquals(Rng.camera().nextLong(), Rng.model().nextLong());
    }

    @Test
    void clockTakesItsSource() {
        assertTrue(GunClock.real());
        long[] t = {5_000_000_123L};
        GunClock.use(() -> t[0]);
        assertEquals(5_000_000_123L, GunClock.nanos());
        assertEquals(5_000L, GunClock.millis());
        t[0] = -1L;
        assertEquals(-1L, GunClock.millis()); // floor, never 0 for a time before the start
        GunClock.use(null);
        assertTrue(GunClock.real());
        long before = System.nanoTime(), now = GunClock.nanos();
        assertTrue(now >= before && now - before < 1_000_000_000L);
    }

    // numbers in a trace read back to the same float and double
    @Test
    void traceNumbersReadBackExactly() {
        Random r = new Random(5);
        for (int i = 0; i < 10_000; i++) {
            float f = Float.intBitsToFloat(r.nextInt());
            double d = Double.longBitsToDouble(r.nextLong());
            if (!Float.isFinite(f) || !Double.isFinite(d)) continue;
            assertEquals(Float.floatToIntBits(f), Float.floatToIntBits(Float.parseFloat(Trace.num(f))), Trace.num(f));
            assertEquals(Double.doubleToLongBits(d), Double.doubleToLongBits(Double.parseDouble(Trace.num(d))), Trace.num(d));
        }
        assertEquals("0.100000001", Trace.num(0.1F));
        assertEquals("1.0000000000000000,2.0000000000000000,3.0000000000000000", Trace.vec(1.0, 2.0, 3.0));
    }
}
