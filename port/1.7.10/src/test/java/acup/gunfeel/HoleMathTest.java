package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

// The flat hole's geometry without a world: 26.2's Tracers numbers on 1.7.10's side numbers.
class HoleMathTest {
    static final double[] ORIGIN = {0, 0, 0};

    // faces 0..5 are down, up, north, south, west, east (1.7.10 sides = 26.2 Direction order)
    @Test
    void facesAreTheSidesOf1710() {
        int[][] normals = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        for (int f = 0; f < 6; f++) {
            int[] o = HoleMath.offset(f);
            assertArrayEquals(normals[f], o, "face " + f);
            assertEquals(f, HoleMath.face(HoleMath.axis(f), HoleMath.step(f)));
            int[] ax = HoleMath.axes(f);
            assertTrue(ax[0] != HoleMath.axis(f) && ax[1] != HoleMath.axis(f) && ax[0] != ax[1]);
        }
        // 26.2: X -> {z, y}, Y -> {x, z}, Z -> {x, y}
        assertArrayEquals(new int[] {2, 1}, HoleMath.axes(5));
        assertArrayEquals(new int[] {0, 2}, HoleMath.axes(1));
        assertArrayEquals(new int[] {0, 1}, HoleMath.axes(3));
    }

    // the eight turns of a hole move the square's corners onto its corners, each turn a different way
    @Test
    void turnsAreTheSquaresSymmetries() {
        float[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        Set<String> seen = new HashSet<>();
        for (int turn = 0; turn < 8; turn++) {
            StringBuilder b = new StringBuilder();
            for (float[] c : corners) {
                float[] t = HoleMath.turned(turn, c[0], c[1]);
                assertTrue((t[0] == 0F || t[0] == 1F) && (t[1] == 0F || t[1] == 1F), "turn " + turn);
                b.append(t[0]).append(',').append(t[1]).append(' ');
            }
            assertTrue(seen.add(b.toString()), "two turns alike: " + turn);
        }
        assertArrayEquals(new float[] {0.25F, 0.75F}, HoleMath.turned(0, 0.25F, 0.75F));
    }

    // a scratch at any angle keeps its square's corners on the texture
    @Test
    void scratchesStayOnTheirTexture() {
        for (int deg = 0; deg < 360; deg += 7) {
            for (float[] c : new float[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}}) {
                float[] t = HoleMath.turned(HoleMath.SCRATCH_TURN + deg, c[0], c[1]);
                assertTrue(t[0] >= -1e-6 && t[0] <= 1 + 1e-6 && t[1] >= -1e-6 && t[1] <= 1 + 1e-6, deg + "°");
            }
        }
        // along the face's u: no turn; along its v: a quarter
        assertEquals(HoleMath.SCRATCH_TURN, HoleMath.scratchTurn(new double[] {1, 0, 0}, 1));
        assertEquals(HoleMath.SCRATCH_TURN + 90, HoleMath.scratchTurn(new double[] {0, 0, 1}, 1));
        assertEquals(HoleMath.SCRATCH_TURN + 180, HoleMath.scratchTurn(new double[] {0, 0, -1}, 5));
    }

    // the variant rides in the turn: every corner of the square lands in that variant's cell
    @Test
    void variantsPickTheirCell() {
        for (int variant = 0; variant < Decals.VARIANTS; variant += 5) {
            int turn = HoleMath.withVariant(3, (variant + 0.5) / Decals.VARIANTS);
            assertEquals(3, turn & HoleMath.TURN_MASK);
            assertEquals(variant, turn >>> HoleMath.VARIANT_SHIFT);
            int cu = variant % Decals.GRID, cv = variant / Decals.GRID;
            for (float a = 0; a <= 1F; a += 0.25F) {
                float[] uv = HoleMath.holeUv(turn, a, 1F - a);
                float u = uv[0], v = 1F - uv[1];
                assertTrue(u > (float) cu / Decals.GRID && u < (cu + 1F) / Decals.GRID, "u " + u + " variant " + variant);
                assertTrue(v > (float) cv / Decals.GRID && v < (cv + 1F) / Decals.GRID, "v " + v + " variant " + variant);
            }
        }
    }

    // a one-sided hole is wound counter-clockwise seen from outside its face, on every face, lifted off it by 0.002
    @Test
    void oneSidedHolesFaceOut() {
        for (int f = 0; f < 6; f++) {
            double[] pos = {0.5, 0.5, 0.5};
            pos[HoleMath.axis(f)] = HoleMath.step(f) > 0 ? 1 : 0;
            double[] rect = {0, 1, 0, 1};
            float[][] q = HoleMath.quad(pos, f, Double.NaN, true, 0, 0.14F, rect, ORIGIN);
            assertNotNull(q);
            int[] n = HoleMath.offset(f);
            double[] e1 = sub(q[1], q[0]), e2 = sub(q[2], q[0]);
            double[] c = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]};
            assertTrue(c[0] * n[0] + c[1] * n[1] + c[2] * n[2] > 0, "face " + f + " wound inwards");
            for (float[] v : q) {
                assertEquals(pos[HoleMath.axis(f)] + 0.002 * HoleMath.step(f), v[HoleMath.axis(f)], 1e-6, "face " + f);
                assertEquals(n[0], v[5], 0F);
                assertEquals(n[1], v[6], 0F);
                assertEquals(n[2], v[7], 0F);
            }
        }
    }

    // a two-sided hole (iron bars) seen from behind lies behind its plane, its normal turned to the camera
    @Test
    void twoSidedHolesTurnToTheCamera() {
        double[] pos = {0.5, 0.5, 0.5};
        float[][] front = HoleMath.quad(pos, 3, 0.5, false, 0, 0.1F, new double[] {0, 1, 0, 1}, new double[] {0.5, 0.5, 3});
        float[][] back = HoleMath.quad(pos, 3, 0.5, false, 0, 0.1F, new double[] {0, 1, 0, 1}, new double[] {0.5, 0.5, -3});
        assertEquals(0.502 - 3, front[0][2], 1e-6);
        assertEquals(1F, front[0][7], 0F);
        assertEquals(0.498 + 3, back[0][2], 1e-6);
        assertEquals(-1F, back[0][7], 0F);
    }

    // the square cut to its clip: a hole on the corner of a face keeps only what lies on it, its texture cut likewise
    @Test
    void holesAreCutToTheFace() {
        double[] pos = {0.95, 1, 0.5};
        float[][] q = HoleMath.quad(pos, 1, Double.NaN, true, 0, 0.1F, new double[] {0, 1, 0, 1}, ORIGIN);
        double maxX = Double.NEGATIVE_INFINITY;
        float maxU = 0, minU = 1;
        for (float[] v : q) {
            maxX = Math.max(maxX, v[0]);
            maxU = Math.max(maxU, v[3]);
            minU = Math.min(minU, v[3]);
        }
        assertEquals(1.0, maxX, 1e-6);
        // 0.15 of 0.2 shown: three quarters of the cell's width (cell 0 of 8: 0..1/8)
        assertEquals(0.75 / Decals.GRID, maxU - minU, 2e-3);
        assertNull(HoleMath.quad(pos, 1, Double.NaN, true, 0, 0.1F, new double[] {0, 0.5, 0, 1}, ORIGIN));
    }

    // the mask's shown cells become runs, row by row; no mask = the whole rect
    @Test
    void cellsRunAlongRows() {
        double[] rect = {0, 1, 0, 1};
        assertSame(rect, HoleMath.cells(rect, null).get(0));
        boolean[] solid = {true, true, false, true, //
                false, false, false, false, //
                true, true, true, true};
        List<double[]> runs = HoleMath.cells(rect, new HoleMath.Mask(4, 4, 4, 3, solid, Double.NaN));
        assertEquals(3, runs.size());
        assertArrayEquals(new double[] {4 / 16.0, 6 / 16.0, 4 / 16.0, 5 / 16.0}, runs.get(0), 1e-12);
        assertArrayEquals(new double[] {7 / 16.0, 8 / 16.0, 4 / 16.0, 5 / 16.0}, runs.get(1), 1e-12);
        assertArrayEquals(new double[] {4 / 16.0, 8 / 16.0, 6 / 16.0, 7 / 16.0}, runs.get(2), 1e-12);
        // cut to the rect
        assertEquals(0.25, HoleMath.cells(new double[] {0.25, 1, 0, 1}, new HoleMath.Mask(0, 0, 8, 1, new boolean[] {true, true, true, true, true, true, true, false}, Double.NaN)).get(0)[0]);
    }

    // a fake block at (0, 0, 0): pixels shown where `shown` says, on the plane `depth` of the block
    static HoleMath.Pixels pixels(boolean hitbox, double depth, java.util.function.BiPredicate<Double, Double> shown) {
        return new HoleMath.Pixels() {
            @Override
            public boolean hitboxOnly(int x, int y, int z) {
                return hitbox && x == 0 && y == 0 && z == 0;
            }

            @Override
            public double shows(int x, int y, int z, int face, double lu, double lv) {
                if (x != 0 || y != 0 || z != 0) return Double.NaN;
                return shown.test(lu, lv) ? depth : Double.NaN;
            }
        };
    }

    @Test
    void maskFollowsThePixels() {
        double[] hit = {0.5, 1, 0.5};
        int[] block = {0, 0, 0};
        double[] rect = {0, 1, 0, 1};
        // every pixel shown: no mask, the hole on the face
        HoleMath.Mask all = HoleMath.mask(hit, 1, block, 0.14F, rect, pixels(false, 1, (u, v) -> true));
        assertNull(all.solid);
        assertEquals(1.0, all.plane);
        // none: no hole
        assertSame(HoleMath.NO_PIXELS, HoleMath.mask(hit, 1, block, 0.14F, rect, pixels(false, 1, (u, v) -> false)));
        // half the block (x < 0.5) see-through: the cells left of the hit are cut away
        HoleMath.Mask half = HoleMath.mask(hit, 1, block, 0.14F, rect, pixels(false, 1, (u, v) -> u > 0.5));
        assertNotNull(half.solid);
        for (int j = 0; j < half.h; j++) {
            for (int i = 0; i < half.w; i++) {
                double cu = (half.u0 + i + 0.5) / 16.0;
                assertEquals(cu > 0.5, half.solid[j * half.w + i], "cell " + i + "," + j);
            }
        }
        // a hitbox-only block (glass): whatever its pixels
        assertNull(HoleMath.mask(hit, 1, block, 0.14F, rect, pixels(true, 1, (u, v) -> false)).solid);
        // the pixels lie inside the hitbox (iron bars' pane at 0.5): the hole goes there; never in front of the hit
        assertEquals(0.5, HoleMath.mask(hit, 1, block, 0.14F, rect, pixels(false, 0.5, (u, v) -> true)).plane);
        assertEquals(0.9, HoleMath.mask(new double[] {0.5, 0.9, 0.5}, 1, block, 0.14F, rect, pixels(false, 1, (u, v) -> true)).plane, 1e-12);
    }

    // a wall: full blocks at x = 0..2, z = 0 (and a slab at x = 3, a block in front of x = 2 at x = -1 ... none)
    static HoleMath.Boxes wall(boolean blockInFrontOfRight, boolean slabRight) {
        return new HoleMath.Boxes() {
            @Override
            public double[] box(int x, int y, int z) {
                if (y == 0 && z == 0 && x >= 0 && x <= 1) return new double[] {x, y, z, x + 1, y + 1, z + 1};
                if (y == 0 && z == 0 && x == 2) return slabRight ? new double[] {x, 0, 0, x + 1, 1, 0.5} : new double[] {x, 0, 0, x + 1, 1, 1};
                if (blockInFrontOfRight && x == 2 && y == 0 && z == 1) return new double[] {x, y, z, x + 1, y + 1, z + 1};
                return null;
            }

            @Override
            public boolean fullCube(int x, int y, int z) {
                double[] b = box(x, y, z);
                return b != null && b[3] - b[0] == 1 && b[4] - b[1] == 1 && b[5] - b[2] == 1;
            }
        };
    }

    // a mark on the seam of two blocks in a wall is not cut there; it is where the neighbour's face lies elsewhere
    // or is covered by a block in front
    @Test
    void faceRectSpansTheWall() {
        // south face (3) of the middle block: u along x, v along y
        assertArrayEquals(new double[] {0, 3, 0, 1}, HoleMath.faceRect(new int[] {1, 0, 0}, 3, wall(false, false)), 1e-12);
        assertArrayEquals(new double[] {0, 2, 0, 1}, HoleMath.faceRect(new int[] {1, 0, 0}, 3, wall(true, false)), 1e-12);
        assertArrayEquals(new double[] {0, 2, 0, 1}, HoleMath.faceRect(new int[] {1, 0, 0}, 3, wall(false, true)), 1e-12);
        // a block with no hitbox: a whole block's face
        assertArrayEquals(new double[] {5, 6, 0, 1}, HoleMath.faceRect(new int[] {5, 0, 0}, 3, wall(false, false)), 1e-12);
    }

    // RenderBlocks lays the icon on mirrored on the north and east faces
    @Test
    void faceUvAsRenderBlocks() {
        double[] full = {0, 0, 0, 1, 1, 1};
        assertArrayEquals(new double[] {4, 12}, HoleMath.faceUv(1, new double[] {0.25, 1, 0.75}, full), 1e-12);
        assertArrayEquals(new double[] {12, 4}, HoleMath.faceUv(2, new double[] {0.25, 0.75, 0}, full), 1e-12);
        assertArrayEquals(new double[] {4, 4}, HoleMath.faceUv(3, new double[] {0.25, 0.75, 1}, full), 1e-12);
        assertArrayEquals(new double[] {12, 4}, HoleMath.faceUv(4, new double[] {0, 0.75, 0.75}, full), 1e-12);
        assertArrayEquals(new double[] {4, 4}, HoleMath.faceUv(5, new double[] {1, 0.75, 0.75}, full), 1e-12);
        // a slab's side: v from the top of the icon down
        assertArrayEquals(new double[] {8, 12}, HoleMath.faceUv(3, new double[] {0.5, 0.25, 1}, new double[] {0, 0, 0, 1, 0.5, 1}), 1e-12);
    }

    // holeLife: full alpha until its last three seconds, then down to nothing; a life under three seconds fades all along
    @Test
    void holesFadeAtTheEndOfTheirLife() {
        long life = HoleMath.lifeNanos(30F);
        assertEquals(30_000_000_000L, life);
        assertEquals(255, HoleMath.alpha(0, life));
        assertEquals(255, HoleMath.alpha(27_000_000_000L, life));
        assertEquals(127, HoleMath.alpha(28_500_000_000L, life));
        assertEquals(0, HoleMath.alpha(30_000_000_000L, life));
        long shortLife = HoleMath.lifeNanos(1.5F);
        assertTrue(HoleMath.alpha(0, shortLife) == 255 && HoleMath.alpha(750_000_000L, shortLife) < 255);
        assertEquals((long) (0.1F * 1e9), HoleMath.lifeNanos(0F), "at least 0.1 s");
    }

    @Test
    void oldHolesGo() {
        ArrayDeque<long[]> holes = new ArrayDeque<>();
        for (long t = 0; t < 10; t++) holes.add(new long[] {t * 1_000_000_000L});
        assertEquals(4, HoleMath.expire(holes, 9_500_000_000L, 6_000_000_000L, h -> h[0]));
        assertEquals(4_000_000_000L, holes.peekFirst()[0]);
    }

    static double[] sub(float[] a, float[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }
}
