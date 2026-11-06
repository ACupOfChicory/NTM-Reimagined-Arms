package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

// port: 26.2's HiddenSideTest (03ee2bd) checks that a face in a side its neighbour hides stops no bullet: two iron bars
// side by side are one grate, the ends of their bars at the seam are not there. 1.7.10 has no such faces to hide: its
// bars' ends carry a cullface and the port's shape table has none in a side the pane joins, while their top and
// bottom edges (0.001 inside the block, no cullface) are drawn even between two bars stacked, so 26.2's rule, every
// face in a hidden side passed, would let a bullet through a drawn edge here. This holds the table to that: no face
// in a joined side (but a face of no area), and rays across the seam of two joined bars of each kind meet nothing at it.
class HiddenSideTest {
    // BlockRay's sides of the joins north, east, south, west (id#meta/NESW)
    private static final int[] JOIN_SIDE = {BlockRay.NORTH, BlockRay.EAST, BlockRay.SOUTH, BlockRay.WEST};

    private static int width(long word) {
        return (int) (word >>> 32 & 0x7fffffffL);
    }

    // 26.2's ModelSurface.inSide: the face at `at` lies in one of the block's sides in `sides` (bit BlockRay's side)
    static boolean inSide(long[] shape, int at, int sides) {
        final int[][] side = {{BlockRay.WEST, BlockRay.EAST}, {BlockRay.DOWN, BlockRay.UP}, {BlockRay.NORTH, BlockRay.SOUTH}};
        for (int axis = 0; axis < 3; axis++) {
            double a = Double.longBitsToDouble(shape[at + axis]), b = Double.longBitsToDouble(shape[at + 3 + axis]),
                    c = Double.longBitsToDouble(shape[at + 6 + axis]);
            if (Math.max(a, Math.max(b, c)) - Math.min(a, Math.min(b, c)) > 1e-6) continue;
            if (Math.abs(a - 1) < 1e-6 && (sides >> side[axis][1] & 1) != 0) return true;
            if (Math.abs(a) < 1e-6 && (sides >> side[axis][0] & 1) != 0) return true;
        }
        return false;
    }

    // a face of no area: 1.7.10's flat sheets (hbm:fence_metal) end in a quad of no thickness at the side they join,
    // which no ray meets
    static boolean flat(long[] shape, int at) {
        double[] v = new double[9];
        for (int k = 0; k < 9; k++) v[k] = Double.longBitsToDouble(shape[at + k]);
        double ux = v[3] - v[0], uy = v[4] - v[1], uz = v[5] - v[2], wx = v[6] - v[0], wy = v[7] - v[1], wz = v[8] - v[2];
        double cx = uy * wz - uz * wy, cy = uz * wx - ux * wz, cz = ux * wy - uy * wx;
        return cx * cx + cy * cy + cz * cz < 1e-12;
    }

    @Test
    void noFaceLiesInASideAPaneJoins() {
        int panes = 0;
        for (Map.Entry<String, long[]> e : ModelCells.table().entrySet()) {
            String key = e.getKey();
            int slash = key.lastIndexOf('/');
            long[] s = e.getValue();
            if (slash < 0 || key.length() - slash != 5 || !ModelSurface.present(s)) continue;
            int joined = 0;
            for (int k = 0; k < 4; k++) if (key.charAt(slash + 1 + k) == '1') joined |= 1 << JOIN_SIDE[k];
            if (joined == 0) continue;
            panes++;
            int at = 66;
            for (int i = 0; i < s[65]; i++) {
                assertFalse(inSide(s, at, joined) && !flat(s, at), key + ": face " + i + " lies in a joined side");
                at += 16 + (width(s[at + 15]) * (int) s[at + 15] + 63) / 64;
            }
        }
        assertTrue(panes >= 15, "joined panes with faces: " + panes);
    }

    // panes and bars of each kind at x = 0 and x = 1, joined: rays across the seam meet nothing at it, and the bars
    // where they are drawn
    @Test
    void barsPairHasNoWallAtTheSeam() {
        Map<String, long[]> t = ModelCells.table();
        int kinds = 0;
        for (Map.Entry<String, long[]> e : t.entrySet()) {
            String key = e.getKey();
            if (!key.endsWith("/0100") || !ModelSurface.present(e.getValue())) continue;
            long[] west = t.get(key.substring(0, key.length() - 4) + "0001");
            if (west == null || !ModelSurface.present(west)) continue;
            kinds++;
            seam(key, e.getValue(), west);
        }
        assertTrue(kinds >= 2, "kinds of panes and bars: " + kinds);
        assertNotNull(t.get("minecraft:iron_bars#0/0100"));
    }

    private static void seam(String id, long[] a, long[] b) {
        java.util.Random random = new java.util.Random(3L);
        int seam = 0, met = 0;
        for (int i = 0; i < 20000; i++) {
            // through a point near the seam, along the bars more or less
            double ax = 1 + (random.nextDouble() - .5) * .2, ay = random.nextDouble(), az = .5 + (random.nextDouble() - .5) * .2;
            double wx = random.nextBoolean() ? 1 : -1, wy = (random.nextDouble() - .5) * .6, wz = (random.nextDouble() - .5) * .6;
            double l = Math.sqrt(wx * wx + wy * wy + wz * wz);
            wx /= l;
            wy /= l;
            wz /= l;
            double fx = ax - wx * 3, fy = ay - wy * 3, fz = az - wz * 3, tx = ax + wx * 3, ty = ay + wy * 3, tz = az + wz * 3;
            BlockRay.Hit first = null;
            for (BlockRay.Hit h : new BlockRay.Hit[] {ModelSurface.hit(a, 0, 0, 0, fx, fy, fz, tx, ty, tz, false),
                    ModelSurface.hit(b, 1, 0, 0, fx, fy, fz, tx, ty, tz, false)})
                if (h != null && (first == null || h.distanceSq(fx, fy, fz) < first.distanceSq(fx, fy, fz))) first = h;
            if (first == null) continue;
            met++;
            if (Math.abs(first.ax - 1) < 1e-6 && (first.face == BlockRay.EAST || first.face == BlockRay.WEST)) seam++;
        }
        assertEquals(0, seam, id + ": stopped at the seam");
        assertTrue(met > 1000, id + ": the bars are still met: " + met);
    }
}
