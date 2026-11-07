package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// The tactical map's cells on hand-made sections: where a mob stands, which way it is covered and by what.
class CellBuilderTest {
    static final byte STONE = Voxel.of(Voxel.SOLID, true, 2, false, false, false);
    static final byte CONCRETE = Voxel.of(Voxel.SOLID, true, 3, false, false, false);
    static final byte GLASS = Voxel.of(Voxel.SOLID, false, 0, false, false, false);
    static final byte LEAVES = Voxel.of(Voxel.SOLID, true, 0, false, false, false);
    static final byte WATER = Voxel.of(Voxel.OPEN, false, 0, true, false, false);
    static final byte LADDER = Voxel.of(Voxel.OPEN, false, 0, false, true, false);
    static final byte FENCE = Voxel.of(Voxel.TALL, false, 1, false, false, false);
    static final byte SLAB = Voxel.of(Voxel.LOW, false, 2, false, false, false);

    /** a level of stone under one section of air, what is put in the air section by the test */
    static final class World {
        final byte[] mid = new byte[SectionSnapshot.SIZE];
        final byte[] below = new byte[SectionSnapshot.SIZE];
        boolean belowStone = true;
        boolean missingEast;

        World() {
            java.util.Arrays.fill(below, STONE);
        }

        void set(int x, int y, int z, byte v) {
            if (y < 0) below[((y + 16) << 8) | (z << 4) | x] = v;
            else mid[(y << 8) | (z << 4) | x] = v;
        }

        SectionCells build() {
            SectionSnapshot[] around = new SectionSnapshot[27];
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (missingEast && dx == 1) continue;
                        SectionSnapshot s;
                        if (dy == 1) s = SectionSnapshot.air(0, 0);
                        else if (dy == -1) s = dx == 0 && dz == 0 ? SectionSnapshot.of(0, 0, below.clone())
                                : SectionSnapshot.filled(0, 0, belowStone ? STONE : Voxel.AIR);
                        else s = dx == 0 && dz == 0 ? SectionSnapshot.of(0, 0, mid.clone()) : SectionSnapshot.air(0, 0);
                        around[CellBuilder.index(dx, dy, dz)] = s;
                    }
            return CellBuilder.build(around, 0, 1);
        }
    }

    @Test
    void flatFloorHasOneCellPerColumnWithNoCover() {
        SectionCells c = new World().build();
        assertEquals(256, c.count());
        for (int i = 0; i < c.count(); i++) {
            assertEquals(0, c.y(i));
            assertEquals(0, c.cover(i));
            int f = c.flags(i);
            assertTrue((f & SectionCells.HEAD3) != 0);
            assertEquals(0, f & (SectionCells.ROOF | SectionCells.UNKNOWN | SectionCells.NARROW | SectionCells.EDGE));
        }
        assertTrue(c.find(3, 0, 4) >= 0);
        assertEquals(-1, c.find(3, 1, 4));
    }

    @Test
    void highWallCoversBothSidesStraightAndAtTheCorners() {
        World w = new World();
        for (int x = 0; x < 16; x++) {
            w.set(x, 0, 8, STONE);
            w.set(x, 1, 8, STONE);
        }
        SectionCells c = w.build();
        // the wall stands on cells of its own: on top of it, y = 2
        assertEquals(-1, c.find(5, 0, 8));
        assertTrue(c.find(5, 2, 8) >= 0);
        int north = c.cover(c.find(5, 0, 7));
        assertEquals(2, SectionCells.height(north, Dirs.S));
        assertTrue(SectionCells.hard(north, Dirs.S));
        assertEquals(2, SectionCells.height(north, Dirs.SE));
        assertEquals(2, SectionCells.height(north, Dirs.SW));
        assertEquals(0, SectionCells.height(north, Dirs.N));
        assertEquals(0, SectionCells.height(north, Dirs.E));
        int south = c.cover(c.find(5, 0, 9));
        assertEquals(2, SectionCells.height(south, Dirs.N));
        assertEquals(0, SectionCells.height(south, Dirs.S));
        // two rows back: no cover of its own (the queries' rays still see the wall)
        assertEquals(0, c.cover(c.find(5, 0, 6)));
    }

    @Test
    void lowWallCoversTheFeetOnly() {
        World w = new World();
        for (int x = 0; x < 16; x++) w.set(x, 0, 8, CONCRETE);
        SectionCells c = w.build();
        int cover = c.cover(c.find(5, 0, 7));
        assertEquals(1, SectionCells.height(cover, Dirs.S));
        assertTrue(SectionCells.hard(cover, Dirs.S));
        assertEquals(0, SectionCells.sightHead(cover));
    }

    @Test
    void glassHidesNothingLeavesHideButStopNothing() {
        World w = new World();
        w.set(5, 0, 8, GLASS);
        w.set(5, 1, 8, GLASS);
        w.set(9, 0, 8, LEAVES);
        w.set(9, 1, 8, LEAVES);
        SectionCells c = w.build();
        assertEquals(0, SectionCells.height(c.cover(c.find(5, 0, 7)), Dirs.S));
        int leaves = c.cover(c.find(9, 0, 7));
        assertEquals(2, SectionCells.height(leaves, Dirs.S));
        assertFalse(SectionCells.hard(leaves, Dirs.S));
    }

    @Test
    void diagonalIsCoveredByACornerOfTwoBlocks() {
        World w = new World();
        // an L: blocks east and south of (5, 0, 5), none on the diagonal
        for (int y = 0; y < 2; y++) {
            w.set(6, y, 5, STONE);
            w.set(5, y, 6, STONE);
        }
        SectionCells c = w.build();
        int cover = c.cover(c.find(5, 0, 5));
        assertEquals(2, SectionCells.height(cover, Dirs.SE));
        assertTrue(SectionCells.hard(cover, Dirs.SE));
        assertEquals(0, SectionCells.height(cover, Dirs.NE));
    }

    @Test
    void roofWaterLadderNarrowAndEdge() {
        World w = new World();
        // a roof three blocks over (2, 0, 2)
        w.set(2, 3, 2, STONE);
        // a pool: water at y = 0 in a hole one deep, (10, 0, 10) stands in it on the stone under the hole
        w.set(10, -1, 10, WATER);
        w.set(10, 0, 10, WATER);
        // a corridor along x at z = 13: walls at z = 12 and z = 14
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 2; y++) {
                w.set(x, y, 12, STONE);
                w.set(x, y, 14, STONE);
            }
        // a ladder up a pillar
        w.set(4, 0, 4, LADDER);
        w.set(4, 1, 4, LADDER);
        w.set(4, 2, 4, LADDER);
        SectionCells c = w.build();
        assertTrue((c.flags(c.find(2, 0, 2)) & SectionCells.ROOF) != 0);
        assertFalse((c.flags(c.find(2, 0, 3)) & SectionCells.ROOF) != 0);
        int water = c.find(10, 0, 10);
        assertTrue(water >= 0);
        assertTrue((c.flags(water) & SectionCells.WATER) != 0);
        assertTrue((c.flags(c.find(5, 0, 13)) & SectionCells.NARROW) != 0);
        assertFalse((c.flags(c.find(5, 0, 3)) & SectionCells.NARROW) != 0);
        assertTrue(c.find(4, 1, 4) >= 0, "a ladder holds a mob up");
        assertTrue((c.flags(c.find(4, 1, 4)) & SectionCells.CLIMB) != 0);
    }

    @Test
    void dropBesideIsAnEdgeAndFencesAndSlabsAreNoFloorToWalkIn() {
        World w = new World();
        // a pit two deep east of (3, 0, 3)
        w.set(4, -1, 3, Voxel.AIR);
        w.set(4, -2, 3, Voxel.AIR);
        w.set(7, 0, 7, FENCE);
        w.set(9, 0, 9, SLAB);
        SectionCells c = w.build();
        assertTrue((c.flags(c.find(3, 0, 3)) & SectionCells.EDGE) != 0);
        assertEquals(-1, c.find(7, 0, 7));
        assertEquals(-1, c.find(7, 1, 7), "no standing on a fence");
        assertEquals(-1, c.find(9, 0, 9));
        assertTrue(c.find(9, 1, 9) >= 0, "a slab is stood on");
    }

    @Test
    void unloadedNeighbourReadsAsWallAndMarksTheCells() {
        World w = new World();
        w.missingEast = true;
        SectionCells c = w.build();
        int edge = c.find(15, 0, 5);
        assertTrue((c.flags(edge) & SectionCells.UNKNOWN) != 0);
        assertEquals(2, SectionCells.height(c.cover(edge), Dirs.E));
        assertEquals(0, c.flags(c.find(3, 0, 5)) & SectionCells.UNKNOWN);
    }

    @Test
    void emptyAndSolidSectionsHaveNoCells() {
        World w = new World();
        w.belowStone = false;
        java.util.Arrays.fill(w.below, Voxel.AIR);
        assertEquals(0, w.build().count());
        SectionSnapshot[] around = new SectionSnapshot[27];
        for (int i = 0; i < 27; i++) around[i] = SectionSnapshot.filled(0, 0, STONE);
        assertEquals(0, CellBuilder.build(around, 0, 0).count());
    }

    @Test
    void directions() {
        assertEquals(Dirs.N, Dirs.of(0, -1));
        assertEquals(Dirs.E, Dirs.of(1, 0));
        assertEquals(Dirs.S, Dirs.of(0, 5));
        assertEquals(Dirs.W, Dirs.of(-3, 0.2));
        assertEquals(Dirs.NE, Dirs.of(1, -1));
        assertEquals(Dirs.SW, Dirs.of(-2, 2.2));
        assertEquals(Dirs.S, Dirs.opposite(Dirs.N));
        for (int d = 0; d < 8; d++) assertEquals(d, Dirs.of(Dirs.DX[d], Dirs.DZ[d]));
    }

    // a busy section (a random maze two blocks high over stone): how long a build takes
    @Test
    void buildTime() {
        World w = new World();
        java.util.Random r = new java.util.Random(7);
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                if (r.nextInt(3) == 0) for (int y = 0; y < 1 + r.nextInt(3); y++) w.set(x, y, z, STONE);
        SectionCells c = w.build();
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 200; i++) best = Math.min(best, w.build().buildNanos);
        System.out.println("[ai] busy section: " + c.count() + " cells, best build " + best / 1000 + " us");
        assertTrue(c.count() > 100);
    }
}
