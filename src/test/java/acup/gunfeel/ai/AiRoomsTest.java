package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

// A house of hand-made cells read into rooms and ways: a doorway, a door, a thick wall, a corridor, an open side.
class AiRoomsTest {
    static final byte STONE = CellBuilderTest.STONE;
    static final byte DOOR = Voxel.of(Voxel.TALL, true, 1, false, false, true);

    /** walls three high round x0..x1, z0..z1 and a roof over it all */
    static void box(PathSearchTest.Grid g, int x0, int z0, int x1, int z1) {
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                if (wall) for (int y = 0; y < 3; y++) g.set(x, y, z, STONE);
                g.set(x, 3, z, STONE);
            }
    }

    static void hole(PathSearchTest.Grid g, int x, int z) {
        g.set(x, 0, z, Voxel.AIR);
        g.set(x, 1, z, Voxel.AIR);
    }

    static boolean has(long[] cells, int x, int z) {
        for (long c : cells) if (c == BlockPos.asLong(x, 0, z)) return true;
        return false;
    }

    /** two rooms: west x 5..8, east x 10..14, z 5..9; a doorway between them at (9, 7); the way out south of the west one at (6, 10) */
    static PathSearchTest.Grid house() {
        PathSearchTest.Grid g = new PathSearchTest.Grid(2);
        box(g, 4, 4, 15, 10);
        for (int z = 5; z <= 9; z++) for (int y = 0; y < 3; y++) g.set(9, y, z, STONE);
        hole(g, 9, 7);
        hole(g, 6, 10);
        return g;
    }

    @Test
    void notUnderARoofIsNoBuilding() {
        PathSearchTest.Grid g = house().build();
        assertNull(AiRooms.of(g, BlockPos.asLong(6, 0, 14)));
        assertNotNull(AiRooms.of(g, BlockPos.asLong(6, 0, 7)));
    }

    @Test
    void twoRoomsADoorwayBetweenAndAWayOut() {
        PathSearchTest.Grid g = house().build();
        AiRooms.Plan p = AiRooms.of(g, BlockPos.asLong(6, 0, 7));
        assertEquals(2, p.rooms());
        assertFalse(p.cut);
        int west = p.room(BlockPos.asLong(6, 0, 7)), east = p.room(BlockPos.asLong(12, 0, 7));
        assertTrue(west >= 0 && east >= 0 && west != east);
        assertEquals(4 * 5, p.size[west]);
        assertEquals(5 * 5, p.size[east]);
        assertEquals(east, p.room(p.mid[east]));
        assertEquals(BlockPos.asLong(12, 0, 7), p.mid[east]);
        assertEquals(AiRooms.OUTSIDE, p.room(BlockPos.asLong(9, 0, 7)));
        assertEquals(2, p.ways.length);
        // each room's cells by the room (the search room after room): all of them, the doorway's cell in neither
        long[][] cells = p.cells();
        assertEquals(2, cells.length);
        assertEquals(4 * 5, cells[west].length);
        assertEquals(5 * 5, cells[east].length);
        assertTrue(has(cells[west], 5, 5) && has(cells[east], 14, 9));
        assertFalse(has(cells[west], 9, 7) || has(cells[east], 9, 7));
        // the east room has one way, to the west room, one wide, no door
        assertEquals(1, p.waysOf(east).size());
        AiRooms.Way in = p.waysOf(east).get(0);
        assertEquals(west, in.other(east));
        assertEquals(1, in.width);
        assertFalse(in.door);
        assertTrue(has(in.cells, 9, 7));
        // beside it in the west room: against the wall either side of the doorway, not before it
        long[] by = in.beside(west);
        assertEquals(2, by.length);
        assertTrue(has(by, 8, 6) && has(by, 8, 8));
        assertTrue(has(in.beside(east), 10, 6) && has(in.beside(east), 10, 8));
        // the way out: from the west room, and outside the two cells against the south wall
        AiRooms.Way out = null;
        for (AiRooms.Way w : p.waysOf(west)) if (w.other(west) == AiRooms.OUTSIDE) out = w;
        assertNotNull(out);
        assertEquals(west, out.a);
        assertEquals(Dirs.S, out.dir);
        assertTrue(has(out.cells, 6, 10));
        long[] outside = out.beside(AiRooms.OUTSIDE);
        assertEquals(2, outside.length);
        assertTrue(has(outside, 5, 11) && has(outside, 7, 11));
    }

    @Test
    void aShutDoorIsAWayWithADoor() {
        PathSearchTest.Grid g = house();
        g.set(9, 0, 7, DOOR);
        g.set(9, 1, 7, DOOR);
        g.build();
        AiRooms.Plan p = AiRooms.of(g, BlockPos.asLong(6, 0, 7));
        assertEquals(2, p.rooms());
        int east = p.room(BlockPos.asLong(12, 0, 7));
        assertTrue(east >= 0);
        assertTrue(p.waysOf(east).get(0).door);
        // with doors off a shut door is a wall: the east room is no part of the building read from the west one
        AiDoors.pass = false;
        try {
            AiRooms.Plan q = AiRooms.of(g, BlockPos.asLong(6, 0, 7));
            assertEquals(1, q.rooms());
            assertEquals(AiRooms.OUTSIDE, q.room(BlockPos.asLong(12, 0, 7)));
        } finally {
            AiDoors.pass = true;
        }
    }

    @Test
    void aCorridorIsARoomWithAWayAtEachMouth() {
        // rooms x 5..8 and x 16..19, a corridor one wide x 9..15 at z 7 between them
        PathSearchTest.Grid g = new PathSearchTest.Grid(2);
        box(g, 4, 4, 20, 10);
        for (int x = 9; x <= 15; x++)
            for (int z = 5; z <= 9; z++) if (z != 7) for (int y = 0; y < 3; y++) g.set(x, y, z, STONE);
        g.build();
        AiRooms.Plan p = AiRooms.of(g, BlockPos.asLong(6, 0, 7));
        assertEquals(3, p.rooms());
        int west = p.room(BlockPos.asLong(6, 0, 7)), mid = p.room(BlockPos.asLong(12, 0, 7)), east = p.room(BlockPos.asLong(18, 0, 7));
        assertTrue(west >= 0 && mid >= 0 && east >= 0 && west != mid && mid != east && west != east);
        assertEquals(5, p.size[mid]);
        assertEquals(2, p.waysOf(mid).size());
        assertEquals(1, p.waysOf(west).size());
        assertEquals(mid, p.waysOf(west).get(0).other(west));
    }

    @Test
    void anOpenSideIsOneWideWay() {
        // a shed x 5..9, z 5..8 with no south wall
        PathSearchTest.Grid g = new PathSearchTest.Grid(2);
        box(g, 4, 4, 10, 9);
        for (int x = 5; x <= 9; x++) hole(g, x, 9);
        g.set(5, 2, 9, STONE);
        g.build();
        AiRooms.Plan p = AiRooms.of(g, BlockPos.asLong(7, 0, 6));
        assertEquals(1, p.rooms());
        assertEquals(1, p.ways.length);
        AiRooms.Way w = p.ways[0];
        assertEquals(5, w.width);
        assertEquals(AiRooms.OUTSIDE, w.b);
        assertEquals(Dirs.S, w.dir);
        // outside, beside the opening: the cells before the wall's ends
        assertTrue(has(w.beside(AiRooms.OUTSIDE), 4, 10) && has(w.beside(AiRooms.OUTSIDE), 10, 10));
    }

    // ------------------------------------------------------------------ a cave or a building: the shell

    /** the grid with each block's kind: every block that is not air of one kind, but for those set apart */
    static final class Kinds implements CellSource {
        final PathSearchTest.Grid g;
        final byte all;
        final java.util.Map<Long, Byte> own = new java.util.HashMap<>();

        Kinds(PathSearchTest.Grid g, byte all) {
            this.g = g;
            this.all = all;
        }

        /** the blocks x0..x1, z0..z1, from under the floor to the roof */
        Kinds set(int x0, int z0, int x1, int z1, byte kind) {
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++)
                    for (int y = -1; y <= 3; y++) own.put(BlockPos.asLong(x, y, z), kind);
            return this;
        }

        @Override
        public SectionCells cells(long key) {
            return g.cells(key);
        }

        @Override
        public byte voxel(int x, int y, int z) {
            return g.voxel(x, y, z);
        }

        @Override
        public byte kind(int x, int y, int z) {
            if (g.voxel(x, y, z) == Voxel.AIR) return AiBlockKinds.NEUTRAL;
            Byte k = own.get(BlockPos.asLong(x, y, z));
            return k != null ? k : all;
        }
    }

    @Test
    void aCaveIsNoBuilding() {
        // the same hollows, all of them in the rock
        Kinds cave = new Kinds(house().build(), AiBlockKinds.NATURAL);
        assertNull(AiRooms.of(cave, BlockPos.asLong(6, 0, 7)));
        // a path laid through it makes none either
        for (int x = 5; x <= 8; x++) cave.own.put(BlockPos.asLong(x, -1, 7), AiBlockKinds.BUILT);
        assertNull(AiRooms.of(cave, BlockPos.asLong(6, 0, 7)));
        // with the setting off any roof makes one, as before
        AiBlockKinds.shell = false;
        try {
            assertNotNull(AiRooms.of(cave, BlockPos.asLong(6, 0, 7)));
        } finally {
            AiBlockKinds.shell = true;
        }
    }

    @Test
    void aBunkerDugOutOfTheRockIsOneByWhatIsKeptInIt() {
        // (a) the west room dug out of the rock, a chest in its west wall's place at (4, 7); a shut door to the east hollow
        PathSearchTest.Grid g = house();
        g.set(9, 0, 7, DOOR);
        g.set(9, 1, 7, DOOR);
        Kinds dug = new Kinds(g.build(), AiBlockKinds.NATURAL);
        dug.own.put(BlockPos.asLong(4, 0, 7), AiBlockKinds.MARKER);
        AiRooms.Plan p = AiRooms.of(dug, BlockPos.asLong(6, 0, 7));
        assertNotNull(p);
        assertEquals(1, p.rooms());
        assertEquals(4 * 5, p.size[0]);
        // the cave past its door is no part of it
        assertEquals(AiRooms.OUTSIDE, p.room(BlockPos.asLong(12, 0, 7)));
        boolean toCave = false;
        for (AiRooms.Way w : p.ways) toCave |= w.door && has(w.cells, 9, 7) && w.other(0) == AiRooms.OUTSIDE;
        assertTrue(toCave);
        // with no door the rock near what is kept is the bunker's, the rock far from it is not
        Kinds open = new Kinds(house().build(), AiBlockKinds.NATURAL);
        open.own.put(BlockPos.asLong(4, 0, 7), AiBlockKinds.MARKER);
        AiRooms.Plan q = AiRooms.of(open, BlockPos.asLong(6, 0, 7));
        assertNotNull(q);
        assertTrue(q.room(BlockPos.asLong(10, 0, 7)) >= 0);
        assertEquals(AiRooms.OUTSIDE, q.room(BlockPos.asLong(14, 0, 5)));
    }

    @Test
    void aBunkerBrokenIntoACaveEndsAtTheBreach() {
        // (b) the west room of concrete, x 4..9 with the wall between; east of it a cave; the doorway is the breach
        Kinds b = new Kinds(house().build(), AiBlockKinds.NATURAL).set(4, 4, 9, 10, AiBlockKinds.BUILT);
        AiRooms.Plan p = AiRooms.of(b, BlockPos.asLong(6, 0, 7));
        assertNotNull(p);
        assertEquals(1, p.rooms());
        assertEquals(4 * 5, p.size[0]);
        assertEquals(AiRooms.OUTSIDE, p.room(BlockPos.asLong(10, 0, 7)));
        assertEquals(AiRooms.OUTSIDE, p.room(BlockPos.asLong(12, 0, 7)));
        boolean breach = false;
        for (AiRooms.Way w : p.ways) breach |= has(w.cells, 9, 7) && w.other(0) == AiRooms.OUTSIDE;
        assertTrue(breach);
        // from the cave's side there is no building
        assertNull(AiRooms.of(b, BlockPos.asLong(12, 0, 7)));
    }

    @Test
    void wallsOfRockUnderABuiltFloorAndRoofAreABuilding() {
        // (c) the floor and the roof put there, the walls the rock's
        Kinds mixed = new Kinds(house().build(), AiBlockKinds.NATURAL);
        for (int x = 4; x <= 15; x++)
            for (int z = 4; z <= 10; z++) {
                mixed.own.put(BlockPos.asLong(x, -1, z), AiBlockKinds.BUILT);
                mixed.own.put(BlockPos.asLong(x, 3, z), AiBlockKinds.BUILT);
            }
        AiRooms.Plan p = AiRooms.of(mixed, BlockPos.asLong(6, 0, 7));
        assertNotNull(p);
        assertEquals(2, p.rooms());
        assertEquals(4 * 5, p.size[p.room(BlockPos.asLong(6, 0, 7))]);
        assertEquals(5 * 5, p.size[p.room(BlockPos.asLong(12, 0, 7))]);
    }

    @Test
    void aBurrowWithNothingKeptInItIsNoBuilding() {
        // (d) a hole in the ground with a door and nothing else
        PathSearchTest.Grid g = house();
        g.set(6, 0, 10, DOOR);
        g.set(6, 1, 10, DOOR);
        assertNull(AiRooms.of(new Kinds(g.build(), AiBlockKinds.NATURAL), BlockPos.asLong(6, 0, 7)));
    }

    // ------------------------------------------------------------------ frames two and three wide, windows, corridors

    @Test
    void aDoorwayTwoWideInAWallIsAWay() {
        PathSearchTest.Grid g = house();
        hole(g, 9, 6);
        g.build();
        AiRooms.Plan p = AiRooms.of(g, BlockPos.asLong(6, 0, 7));
        assertEquals(2, p.rooms());
        int west = p.room(BlockPos.asLong(6, 0, 7)), east = p.room(BlockPos.asLong(12, 0, 7));
        assertTrue(west >= 0 && east >= 0 && west != east);
        AiRooms.Way in = p.waysOf(east).get(0);
        assertEquals(2, in.width);
        assertEquals(2, in.cells.length);
        assertTrue(has(in.cells, 9, 6) && has(in.cells, 9, 7));
        assertEquals(west, in.other(east));
        // beside it: against the wall either side of the frame
        assertTrue(has(in.beside(west), 8, 5) && has(in.beside(west), 8, 8));
        assertTrue(has(in.beside(east), 10, 5) && has(in.beside(east), 10, 8));
    }

    @Test
    void aDoorwayThreeWideIsAWayAndFourWideIsNone() {
        PathSearchTest.Grid g = house();
        hole(g, 9, 6);
        hole(g, 9, 8);
        g.build();
        AiRooms.Plan p = AiRooms.of(g, BlockPos.asLong(6, 0, 7));
        assertEquals(2, p.rooms());
        assertEquals(3, p.waysOf(p.room(BlockPos.asLong(12, 0, 7))).get(0).width);
        hole(g, 9, 5);
        g.build();
        // four wide of a wall five long: one room
        assertEquals(1, AiRooms.of(g, BlockPos.asLong(6, 0, 7)).rooms());
    }

    @Test
    void aRoomThreeWideIsNotCutAcross() {
        // a room x 5..7 (three wide), z 5..14, a doorway one wide in its east wall at (8, 9) into a room x 9..13
        PathSearchTest.Grid g = new PathSearchTest.Grid(2);
        box(g, 4, 4, 14, 15);
        for (int z = 5; z <= 14; z++) for (int y = 0; y < 3; y++) g.set(8, y, z, STONE);
        hole(g, 8, 9);
        g.build();
        AiRooms.Plan p = AiRooms.of(g, BlockPos.asLong(6, 0, 6));
        assertEquals(2, p.rooms());
        assertEquals(3 * 10, p.size[p.room(BlockPos.asLong(6, 0, 6))]);
        assertEquals(AiRooms.ROOM, p.kind[p.room(BlockPos.asLong(6, 0, 6))]);
    }

    @Test
    void aPassageTwoWideIsACorridorWithAWayAtEachMouth() {
        // rooms x 5..8 and x 16..19, z 5..9; between them a passage two wide (z 6..7), x 9..15
        PathSearchTest.Grid g = new PathSearchTest.Grid(2);
        box(g, 4, 4, 20, 10);
        for (int x = 9; x <= 15; x++)
            for (int z = 5; z <= 9; z++) if (z != 6 && z != 7) for (int y = 0; y < 3; y++) g.set(x, y, z, STONE);
        g.build();
        AiRooms.Plan p = AiRooms.of(g, BlockPos.asLong(6, 0, 7));
        assertEquals(3, p.rooms());
        int mid = p.room(BlockPos.asLong(12, 0, 7));
        assertTrue(mid >= 0);
        assertEquals(5 * 2, p.size[mid]);
        assertEquals(AiRooms.CORRIDOR, p.kind[mid]);
        assertEquals(AiRooms.ROOM, p.kind[p.room(BlockPos.asLong(6, 0, 7))]);
        assertEquals(2, p.waysOf(mid).size());
        assertEquals(2, p.waysOf(mid).get(0).width);
    }

    @Test
    void aWindowIsSeenThroughAndNotWalkedThrough() {
        PathSearchTest.Grid g = house();
        // a hole at head height in the west room's west wall, glass at head height in its north wall, a rail in the east room
        g.set(4, 1, 6, Voxel.AIR);
        byte glass = Voxel.of(Voxel.SOLID, false, 0, false, false, false);
        g.set(6, 1, 4, glass);
        g.build();
        AiRooms.Plan p = AiRooms.of(g, BlockPos.asLong(6, 0, 7));
        int west = p.room(BlockPos.asLong(6, 0, 7));
        assertEquals(2, p.windows.length);
        boolean hole = false, pane = false;
        for (AiRooms.Window w : p.windows) {
            assertEquals(west, w.room());
            if (w.block() == BlockPos.asLong(4, 1, 6)) hole = !w.glazed() && w.dir() == Dirs.W;
            if (w.block() == BlockPos.asLong(6, 1, 4)) pane = w.glazed() && w.dir() == Dirs.N;
        }
        assertTrue(hole && pane);
        assertEquals(0, p.stairs.length);
        assertEquals(0, p.ladders.length);
    }
}
