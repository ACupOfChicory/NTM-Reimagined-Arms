package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

// The port's ray walk (BlockRay, 26.2's traverseBlocks and VoxelShape.clip) against brute force: every cell the
// segment crosses, in order, with no 200-cell limit as 1.7.10's World.func_147447_a has.
class BlockRayTest {
    // the cells a walk visits, all of them
    static List<int[]> visited(double fx, double fy, double fz, double tx, double ty, double tz) {
        final List<int[]> out = new ArrayList<int[]>();
        BlockRay.walk(fx, fy, fz, tx, ty, tz, (x, y, z) -> {
            out.add(new int[] {x, y, z});
            return null;
        });
        return out;
    }

    // where the segment enters the unit cell (0..1 along it), or NaN when it misses or only grazes it (slab test)
    static double enter(double fx, double fy, double fz, double dx, double dy, double dz, int x, int y, int z) {
        double lo = 0, hi = 1;
        double[] f = {fx, fy, fz}, d = {dx, dy, dz};
        int[] c = {x, y, z};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-15) {
                if (f[i] < c[i] || f[i] > c[i] + 1) return Double.NaN;
                continue;
            }
            double a = (c[i] - f[i]) / d[i], b = (c[i] + 1 - f[i]) / d[i];
            lo = Math.max(lo, Math.min(a, b));
            hi = Math.min(hi, Math.max(a, b));
        }
        return lo < hi - 1e-6 ? lo : Double.NaN;
    }

    @Test
    void walkVisitsTheCellsOfTheSegmentInOrder() {
        Random r = new Random(7);
        for (int n = 0; n < 2000; n++) {
            double fx = r.nextDouble() * 20 - 10, fy = r.nextDouble() * 20 - 10, fz = r.nextDouble() * 20 - 10;
            double tx = r.nextDouble() * 20 - 10, ty = r.nextDouble() * 20 - 10, tz = r.nextDouble() * 20 - 10;
            List<int[]> got = visited(fx, fy, fz, tx, ty, tz);
            // consecutive cells differ by one step along one axis
            for (int i = 1; i < got.size(); i++) {
                int[] a = got.get(i - 1), b = got.get(i);
                assertEquals(1, Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]));
            }
            int[] first = got.get(0), last = got.get(got.size() - 1);
            assertEquals(BlockRay.floor(fx), first[0]);
            assertEquals(BlockRay.floor(fy), first[1]);
            assertEquals(BlockRay.floor(fz), first[2]);
            assertEquals(BlockRay.floor(tx), last[0]);
            assertEquals(BlockRay.floor(ty), last[1]);
            assertEquals(BlockRay.floor(tz), last[2]);
            // the cells the segment really crosses come at a rising parameter
            double dx = tx - fx, dy = ty - fy, dz = tz - fz;
            double prev = -1;
            for (int[] c : got) {
                double t = enter(fx, fy, fz, dx, dy, dz, c[0], c[1], c[2]);
                if (Double.isNaN(t)) continue; // an edge the walk steps through
                assertTrue(t >= prev - 1e-6, "order");
                prev = t;
            }
            // and none is left out
            int crossed = 0;
            for (int x = Math.min(first[0], last[0]) - 1; x <= Math.max(first[0], last[0]) + 1; x++)
                for (int y = Math.min(first[1], last[1]) - 1; y <= Math.max(first[1], last[1]) + 1; y++)
                    for (int z = Math.min(first[2], last[2]) - 1; z <= Math.max(first[2], last[2]) + 1; z++) {
                        if (Double.isNaN(enter(fx, fy, fz, dx, dy, dz, x, y, z))) continue;
                        crossed++;
                        boolean seen = false;
                        for (int[] c : got) seen |= c[0] == x && c[1] == y && c[2] == z;
                        assertTrue(seen, "missed cell " + x + "," + y + "," + z);
                    }
            assertTrue(crossed > 0);
        }
    }

    @Test
    void noLimitOfTwoHundredCells() {
        // 1.7.10's func_147447_a gives up after 200 cells; 26.2 and the port walk all of them
        List<int[]> got = visited(.5, .5, .5, .5, .5, 300.5);
        assertEquals(301, got.size());
        BlockRay.Hit h = BlockRay.walk(.5, .5, .5, .5, .5, 300.5,
                (x, y, z) -> z == 250 ? BlockRay.clip(new double[] {0, 0, 250, 1, 1, 251}, 1, .5, .5, .5, .5, .5, 300.5, x, y, z) : null);
        assertNotNull(h);
        assertEquals(250, h.z);
        assertEquals(BlockRay.NORTH, h.face);
        assertEquals(250.0, h.az, 1e-9);
    }

    @Test
    void clipFacesAndInside() {
        double[] cube = {0, 0, 0, 1, 1, 1};
        BlockRay.Hit h = BlockRay.clip(cube, 1, .5, 2, .5, .5, -1, .5, 0, 0, 0);
        assertEquals(BlockRay.UP, h.face);
        assertEquals(1.0, h.ay, 1e-12);
        assertFalse(h.inside);
        assertEquals(BlockRay.WEST, BlockRay.clip(cube, 1, -1, .5, .5, 2, .5, .5, 0, 0, 0).face);
        assertEquals(BlockRay.SOUTH, BlockRay.clip(cube, 1, .5, .5, 2, .5, .5, -1, 0, 0, 0).face);
        // a path starting inside: 26.2 hits at once, 0.001 along it, on the face it came in through
        h = BlockRay.clip(cube, 1, .5, .5, .5, .5, .5, 3, 0, 0, 0);
        assertTrue(h.inside);
        assertEquals(BlockRay.NORTH, h.face);
        assertEquals(.5 + 2.5 * 0.001, h.az, 1e-12);
        // a thin panel (NTM's steel wall, meta 2): met at its own side, missed before it
        double[] panel = {0, 0, 14 / 16.0, 1, 1, 1};
        assertEquals(14 / 16.0, BlockRay.clip(panel, 1, .5, .5, -1, .5, .5, 2, 0, 0, 0).az, 1e-12);
        assertNull(BlockRay.clip(panel, 1, .5, .5, -1, .5, .5, .5, 0, 0, 0));
        // two boxes: the nearer one
        double[] two = {0, 0, .5, 1, 1, 1, 0, 0, 0, 1, 1, .25};
        assertEquals(0.0, BlockRay.clip(two, 2, .5, .5, -1, .5, .5, 2, 0, 0, 0).az, 1e-12);
    }

    @Test
    void facesAreSideHit() {
        // 1.7.10's sideHit numbers: 0 down, 1 up, 2 north (-z), 3 south (+z), 4 west (-x), 5 east (+x)
        int[][] steps = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        for (int f = 0; f < 6; f++) {
            assertEquals(steps[f][0], BlockRay.stepX(f));
            assertEquals(steps[f][1], BlockRay.stepY(f));
            assertEquals(steps[f][2], BlockRay.stepZ(f));
            assertEquals(f, BlockRay.nearest(steps[f][0], steps[f][1], steps[f][2]));
            assertEquals(f ^ 1, BlockRay.opposite(f));
        }
    }
}
