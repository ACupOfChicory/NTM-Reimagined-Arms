package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

// A* over hand-made cells: round walls, through gaps, up a block, down a drop, no cut corners, short of nodes.
class PathSearchTest {
    static final byte STONE = CellBuilderTest.STONE;
    static final byte SLAB = CellBuilderTest.SLAB;

    /** sections 0..w-1 across x and z at sy 0, stone below (sy -1), air above; blocks set by the test */
    static final class Grid implements CellSource {
        final int w;
        final Map<Long, byte[]> blocks = new HashMap<>();
        final Map<Long, SectionCells> cells = new HashMap<>();
        final java.util.Set<Long> hidden = new java.util.HashSet<>();

        Grid(int w) {
            this.w = w;
            for (int sx = 0; sx < w; sx++)
                for (int sz = 0; sz < w; sz++) {
                    blocks.put(SectionPos.asLong(sx, 0, sz), new byte[SectionSnapshot.SIZE]);
                    byte[] stone = new byte[SectionSnapshot.SIZE];
                    java.util.Arrays.fill(stone, STONE);
                    blocks.put(SectionPos.asLong(sx, -1, sz), stone);
                }
        }

        void set(int x, int y, int z, byte v) {
            blocks.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4))[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] = v;
        }

        Grid build() {
            cells.clear();
            for (int sx = 0; sx < w; sx++)
                for (int sz = 0; sz < w; sz++)
                    for (int sy = -1; sy <= 0; sy++) {
                        SectionSnapshot[] around = new SectionSnapshot[27];
                        for (int dx = -1; dx <= 1; dx++)
                            for (int dy = -1; dy <= 1; dy++)
                                for (int dz = -1; dz <= 1; dz++) {
                                    byte[] b = blocks.get(SectionPos.asLong(sx + dx, sy + dy, sz + dz));
                                    around[CellBuilder.index(dx, dy, dz)] = b != null ? SectionSnapshot.of(0, 0, b.clone())
                                            : sy + dy > 0 ? SectionSnapshot.air(0, 0) : null;
                                }
                        long key = SectionPos.asLong(sx, sy, sz);
                        cells.put(key, CellBuilder.build(around, key, 1));
                    }
            return this;
        }

        @Override
        public SectionCells cells(long key) {
            return hidden.contains(key) ? null : cells.get(key);
        }

        @Override
        public byte voxel(int x, int y, int z) {
            byte[] b = blocks.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
            if (b == null) return y >= 16 ? Voxel.AIR : Voxel.UNKNOWN;
            return b[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
        }
    }

    static long at(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    static boolean passes(PathSearch.Result r, int x, int y, int z) {
        for (long c : r.cells) if (c == at(x, y, z)) return true;
        return false;
    }

    /** every step is to a cell beside (or diagonal), at most one up */
    static void stepsAreNeighbours(PathSearch.Result r) {
        for (int i = 1; i < r.cells.length; i++) {
            long a = r.cells[i - 1], b = r.cells[i];
            int dx = Math.abs(BlockPos.getX(a) - BlockPos.getX(b)), dz = Math.abs(BlockPos.getZ(a) - BlockPos.getZ(b));
            int dy = BlockPos.getY(b) - BlockPos.getY(a);
            assertTrue(dx <= 1 && dz <= 1 && dx + dz > 0 && dy <= 1 && dy >= -PathSearch.MAX_DROP,
                    "step " + i + ": " + BlockPos.of(a) + " -> " + BlockPos.of(b));
        }
    }

    @Test
    void straightAcrossTheOpenIsTheShortestWay() {
        Grid g = new Grid(2).build();
        PathSearch.Result r = PathSearch.find(g, at(2, 0, 2), at(20, 0, 2), 0, 2000, PathSearch.Cost.NONE);
        assertTrue(r.reached);
        assertEquals(19, r.cells.length);
        assertEquals(18F, r.cost, 1e-4);
        stepsAreNeighbours(r);
    }

    @Test
    void roundAWallThroughTheGapAndNotThroughTheWall() {
        Grid g = new Grid(2);
        for (int x = 0; x < 32; x++) {
            if (x == 25) continue;
            g.set(x, 0, 10, STONE);
            g.set(x, 1, 10, STONE);
        }
        g.build();
        PathSearch.Result r = PathSearch.find(g, at(5, 0, 5), at(5, 0, 15), 0, 4000, PathSearch.Cost.NONE);
        assertTrue(r.reached);
        assertTrue(passes(r, 25, 0, 10), "through the gap");
        stepsAreNeighbours(r);
        for (long c : r.cells) assertFalse(BlockPos.getZ(c) == 10 && BlockPos.getX(c) != 25);
    }

    @Test
    void upABlockOnlyWithRoomToJumpAndDownADrop() {
        Grid g = new Grid(2);
        // a step one block high across the way at x = 10..12
        for (int z = 0; z < 32; z++)
            for (int x = 10; x <= 12; x++) g.set(x, 0, z, STONE);
        g.build();
        PathSearch.Result r = PathSearch.find(g, at(5, 0, 5), at(18, 0, 5), 0, 2000, PathSearch.Cost.NONE);
        assertTrue(r.reached);
        assertTrue(passes(r, 11, 1, 5), "over the step");
        stepsAreNeighbours(r);
        // a roof two over the floor before the step: no room to jump up
        for (int z = 0; z < 32; z++) g.set(9, 2, z, STONE);
        g.build();
        r = PathSearch.find(g, at(5, 0, 5), at(18, 0, 5), 0, 4000, PathSearch.Cost.NONE);
        assertFalse(r.reached, "the step is a wall now");
    }

    @Test
    void ontoASlabWithoutRoomOverTheHead() {
        Grid g = new Grid(2);
        for (int z = 0; z < 32; z++) {
            g.set(10, 0, z, SLAB);
            g.set(9, 2, z, STONE);
        }
        g.build();
        PathSearch.Result r = PathSearch.find(g, at(5, 0, 5), at(14, 0, 5), 0, 2000, PathSearch.Cost.NONE);
        assertTrue(r.reached, "a slab is stepped onto");
    }

    @Test
    void downThreeBlocksButNotFour() {
        Grid g = new Grid(2);
        // a platform three high the mob starts on, x 0..8
        for (int x = 0; x <= 8; x++)
            for (int z = 0; z < 32; z++)
                for (int y = 0; y < 3; y++) g.set(x, y, z, STONE);
        g.build();
        PathSearch.Result r = PathSearch.find(g, at(4, 3, 4), at(14, 0, 4), 0, 2000, PathSearch.Cost.NONE);
        assertTrue(r.reached);
        assertTrue(passes(r, 9, 0, 4) || passes(r, 9, 0, 3) || passes(r, 9, 0, 5), "straight down off the edge");
        stepsAreNeighbours(r);
        for (int x = 0; x <= 8; x++)
            for (int z = 0; z < 32; z++) g.set(x, 3, z, STONE);
        g.build();
        r = PathSearch.find(g, at(4, 4, 4), at(14, 0, 4), 0, 4000, PathSearch.Cost.NONE);
        assertFalse(r.reached, "four is too far down");
    }

    @Test
    void noCornersCut() {
        Grid g = new Grid(1);
        // a pillar at (5, 5): from (4, 4) to (5, 6)... the diagonal (4,4)->(5,5) is blocked, and (4,5)->(5,6) passes beside it
        g.set(5, 0, 5, STONE);
        g.set(5, 1, 5, STONE);
        g.build();
        PathSearch.Result r = PathSearch.find(g, at(4, 0, 4), at(6, 0, 6), 0, 2000, PathSearch.Cost.NONE);
        assertTrue(r.reached);
        stepsAreNeighbours(r);
        for (int i = 1; i < r.cells.length; i++) {
            long a = r.cells[i - 1], b = r.cells[i];
            int ax = BlockPos.getX(a), az = BlockPos.getZ(a), bx = BlockPos.getX(b), bz = BlockPos.getZ(b);
            if (ax != bx && az != bz) {
                assertFalse(ax == 5 && bz == 5 || bx == 5 && az == 5, "cut the pillar's corner: " + BlockPos.of(a) + " -> " + BlockPos.of(b));
            }
        }
    }

    @Test
    void shortOfNodesItGoesAsNearAsItGot() {
        Grid g = new Grid(2);
        for (int x = 0; x < 30; x++) {
            g.set(x, 0, 10, STONE);
            g.set(x, 1, 10, STONE);
        }
        g.build();
        PathSearch.Result r = PathSearch.find(g, at(2, 0, 2), at(2, 0, 20), 0, 40, PathSearch.Cost.NONE);
        assertFalse(r.reached);
        assertTrue(r.found());
        long end = r.cells[r.cells.length - 1];
        assertTrue(BlockPos.getZ(end) > 2, "nearer the goal than the start");
    }

    @Test
    void aCostSteersItAwayAndMissingSectionsAreTold() {
        Grid g = new Grid(2).build();
        // the straight line at z = 2 costs: it goes round
        PathSearch.Result r = PathSearch.find(g, at(2, 0, 2), at(20, 0, 2), 0, 4000, (x, y, z) -> z == 2 && x > 4 && x < 18 ? 5F : 0F);
        assertTrue(r.reached);
        assertFalse(passes(r, 10, 0, 2));
        g.hidden.add(SectionPos.asLong(1, 0, 0));
        r = PathSearch.find(g, at(2, 0, 2), at(20, 0, 2), 0, 4000, PathSearch.Cost.NONE);
        assertTrue(java.util.Arrays.stream(r.missing).anyMatch(k -> k == SectionPos.asLong(1, 0, 0)));
    }

    @Test
    void snapsToTheCellUnderAMobOnAnEdge() {
        Grid g = new Grid(1);
        g.set(5, 0, 5, STONE);
        g.build();
        assertEquals(at(5, 1, 5), PathSearch.snap(g, 5.5, 1.0, 5.5));
        assertEquals(at(3, 0, 3), PathSearch.snap(g, 3.5, 0.0, 3.5));
        // standing over the edge of the block at x 6.05, the block it is over has no cell at y 1: the nearest beside
        long s = PathSearch.snap(g, 6.05, 1.0, 5.5);
        assertTrue(s == at(5, 1, 5) || s == at(6, 0, 5), BlockPos.of(s).toString());
    }

    @Test
    void aLongSearchIsQuick() {
        Grid g = new Grid(4);
        java.util.Random rng = new java.util.Random(7);
        for (int i = 0; i < 400; i++) {
            int x = rng.nextInt(64), z = rng.nextInt(64);
            if (x < 3 && z < 3 || x > 60 && z > 60) continue;
            g.set(x, 0, z, STONE);
            g.set(x, 1, z, STONE);
        }
        g.build();
        long best = Long.MAX_VALUE;
        PathSearch.Result r = null;
        for (int i = 0; i < 20; i++) {
            long t0 = System.nanoTime();
            r = PathSearch.find(g, at(1, 0, 1), at(62, 0, 62), 0, 8000, PathSearch.Cost.NONE);
            best = Math.min(best, System.nanoTime() - t0);
        }
        System.out.printf("64x64 with 400 pillars: %d cells, %d expanded, best %.0f us%n", r.cells.length, r.expanded, best / 1000.0);
        assertTrue(r.reached);
        stepsAreNeighbours(r);
    }
}
