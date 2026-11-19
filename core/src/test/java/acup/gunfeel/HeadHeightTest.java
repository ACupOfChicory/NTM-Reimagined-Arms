package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

// NTM's headshot by height (HeadHeight) against 88cb5fe's BulletServer.head, its last two lines word for word (the
// creature's getters as numbers): every case the same, odd numbers too
class HeadHeightTest {
    // BulletServer.head at 88cb5fe, after its guards and the model's parts: living.getBbHeight() and getEyeHeight()
    // are floats, getY() and at.y doubles
    private static boolean old(double atY, double getY, float getBbHeight, float getEyeHeight) {
        double head = getBbHeight - getEyeHeight;
        return atY > getY + getBbHeight - head * 2.0;
    }

    private static final double[] ODD = {0, -0.0, 1, -1, 64, -64, 1e-9, 1e300, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.MIN_VALUE, Double.MAX_VALUE};
    private static final float[] ODD_F = {0F, -0F, 1.8F, 1.62F, 0.25F, 2.9F, 1e-7F, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            Float.MIN_VALUE, Float.MAX_VALUE};

    @Test
    void sameAsBefore() {
        SplittableRandom r = new SplittableRandom(1922);
        int heads = 0, n = 1_000_000;
        for (int i = 0; i < n; i++) {
            double y = r.nextInt(8) == 0 ? ODD[r.nextInt(ODD.length)] : r.nextDouble(-64, 320);
            float h = r.nextInt(8) == 0 ? ODD_F[r.nextInt(ODD_F.length)] : (float) r.nextDouble(0.1, 3.5);
            // eyes mostly under the top, now and then over it (a pose) or odd
            float eye = r.nextInt(8) == 0 ? ODD_F[r.nextInt(ODD_F.length)] : (float) (h * r.nextDouble(0.5, 1.1));
            // the hit on the box, near the line most of the time
            double line = y + h - (h - eye) * 2.0;
            double at = r.nextInt(8) == 0 ? ODD[r.nextInt(ODD.length)] : r.nextBoolean() ? (r.nextBoolean() ? Math.nextUp(line) : Math.nextDown(line)) : y + r.nextDouble() * h;
            if (r.nextInt(16) == 0) at = line;
            boolean want = old(at, y, h, eye);
            assertEquals(want, HeadHeight.byHeight(at, y, h, eye), at + " " + y + " " + h + " " + eye);
            if (want) heads++;
        }
        assertTrue(heads > n / 10 && heads < n * 9 / 10, "heads " + heads);
    }

    @Test
    void theHeadsLine() {
        // a zombie (1.95 tall, eyes at 1.74): the head 0.21, the line two heads under the top
        float h = 1.95F, eye = 1.74F;
        double line = 64.0 + h - (double) (h - eye) * 2.0;
        assertFalse(HeadHeight.byHeight(line, 64.0, h, eye));
        assertTrue(HeadHeight.byHeight(Math.nextUp(line), 64.0, h, eye));
        // the float's head, not the doubles': 1.95F - 1.74F taken as floats
        assertEquals((double) (1.95F - 1.74F), h - eye);
        assertFalse(HeadHeight.byHeight(Double.NaN, 64, h, eye));
    }
}
