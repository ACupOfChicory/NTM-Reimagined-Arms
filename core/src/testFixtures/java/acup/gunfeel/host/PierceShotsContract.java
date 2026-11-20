package acup.gunfeel.host;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import acup.gunfeel.Materials;

/** The path's points stay opaque and retain the version's distance arithmetic. */
public abstract class PierceShotsContract {
    protected abstract PierceShots shots();
    protected abstract Object point(double x, double y, double z);
    protected abstract Object hit(Object point);

    @Test
    void locationIsTheOriginalPoint() {
        Object p = point(1, 2, 3);
        assertSame(p, shots().location(hit(p)));
    }

    @Test
    void distanceUsesTheSameDoubleOperations() {
        double[][] values = {
                {0, 0, 0, 3, 4, 0}, {1.23, -2.81, 9.77, -0.345, 7.91, -1.11},
                {-0.0, 0, -0.0, 0, -0.0, 0}, {1e150, -1e150, 1e-150, 1, -2, 3},
                {Double.POSITIVE_INFINITY, 0, 1, 0, 0, 0}, {Double.NaN, 1, 2, 3, 4, 5}
        };
        for (double[] v : values) {
            double dx = v[3] - v[0], dy = v[4] - v[1], dz = v[5] - v[2];
            double expected = Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(
                    shots().distance(point(v[0], v[1], v[2]), point(v[3], v[4], v[5]))));
        }
    }

    @Test
    void missingThrowsForEveryOperation() {
        PierceShots p = PierceShots.MISSING;
        assertThrows(UnsupportedOperationException.class, () -> p.location(null));
        assertThrows(UnsupportedOperationException.class, () -> p.distance(null, null));
        assertThrows(UnsupportedOperationException.class, () -> p.level(null));
        assertThrows(UnsupportedOperationException.class, () -> p.removed(null));
        assertThrows(UnsupportedOperationException.class, () -> p.water(null, null));
        assertThrows(UnsupportedOperationException.class, () -> p.damage(null));
        assertThrows(UnsupportedOperationException.class, () -> p.damage(null, 1F));
        assertThrows(UnsupportedOperationException.class, () -> p.pierced(null, null, null, 1F, null, new Materials.Penetration()));
        assertThrows(UnsupportedOperationException.class, () -> p.stopAt(null, null));
        assertThrows(UnsupportedOperationException.class, () -> p.id(null));
        assertThrows(UnsupportedOperationException.class, () -> p.logLater(0, 1, null));
        assertThrows(UnsupportedOperationException.class, () -> p.logBlock(null, null, null, 1F));
    }
}
