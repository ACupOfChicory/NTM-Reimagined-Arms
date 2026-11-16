package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

// Trenches dug into hand-made ground read into stretches (rooms) and turns (ways).
class AiTrenchesTest {
    static final byte STONE = CellBuilderTest.STONE;

    /** ground two high over x 2..29, z 2..29 (its top at y 2) */
    static PathSearchTest.Grid ground() {
        PathSearchTest.Grid g = new PathSearchTest.Grid(2);
        for (int x = 2; x <= 29; x++) for (int z = 2; z <= 29; z++) for (int y = 0; y < 2; y++) g.set(x, y, z, STONE);
        return g;
    }

    static void dig(PathSearchTest.Grid g, int x, int z) {
        g.set(x, 0, z, Voxel.AIR);
        g.set(x, 1, z, Voxel.AIR);
    }

    static long at(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }

    /** a zigzag: x 5..11 at z 5, a turn at (12, 5), z 6..11 at x 12, a turn at (12, 12), x 13..19 at z 12 */
    static PathSearchTest.Grid zigzag() {
        PathSearchTest.Grid g = ground();
        for (int x = 5; x <= 12; x++) dig(g, x, 5);
        for (int z = 6; z <= 12; z++) dig(g, 12, z);
        for (int x = 13; x <= 19; x++) dig(g, x, 12);
        return g.build();
    }

    static AiRooms.Way between(AiRooms.Plan p, int r, int s) {
        for (AiRooms.Way w : p.ways) if (w.a == r && w.b == s || w.a == s && w.b == r) return w;
        return null;
    }

    @Test
    void aZigzagIsThreeStretchesAndTwoTurns() {
        PathSearchTest.Grid g = zigzag();
        AiRooms.Plan p = AiTrenches.of(g, at(8, 5));
        assertNotNull(p);
        assertEquals(3, p.rooms());
        int a = p.room(at(8, 5)), b = p.room(at(12, 8)), c = p.room(at(16, 12));
        assertTrue(a >= 0 && b >= 0 && c >= 0 && a != b && b != c && a != c);
        assertEquals(7, p.size[a]);
        assertEquals(6, p.size[b]);
        assertEquals(7, p.size[c]);
        // the turns are in no stretch
        assertEquals(AiRooms.OUTSIDE, p.room(at(12, 5)));
        assertEquals(AiRooms.OUTSIDE, p.room(at(12, 12)));
        // the first turn: from a beside it, b round the corner; one steps out into the turn and throws down b
        AiRooms.Way w = between(p, a, b);
        assertNotNull(w);
        assertArrayEquals(new long[] {at(12, 5)}, w.cells);
        assertArrayEquals(new long[] {at(11, 5)}, w.beside(a));
        assertArrayEquals(new long[] {at(10, 5)}, w.far(a));
        assertArrayEquals(new long[] {at(9, 5)}, w.far2(a));
        assertArrayEquals(new long[] {at(12, 6)}, w.beside(b));
        assertArrayEquals(new long[] {at(12, 7)}, w.far(b));
        assertEquals(Dirs.S, w.in(b));
        assertEquals(Dirs.W, w.in(a));
        assertEquals(at(12, 5), w.front(b));
        assertEquals(at(12, 5), w.front(a));
        assertNotNull(between(p, b, c));
        assertNull(between(p, a, c));
        // each stretch has a way out of the trench (from above), with no place to stack up at
        int out = 0;
        for (AiRooms.Way x : p.ways) if (x.b == AiRooms.OUTSIDE) {
            out++;
            assertEquals(0, x.beside(AiRooms.OUTSIDE).length);
        }
        assertEquals(3, out);
    }

    @Test
    void whereThreeMeetTheirMeetingIsAWayBetweenEachTwo() {
        PathSearchTest.Grid g = ground();
        for (int x = 5; x <= 19; x++) dig(g, x, 10);
        for (int z = 11; z <= 17; z++) dig(g, 12, z);
        g.build();
        AiRooms.Plan p = AiTrenches.of(g, at(6, 10));
        assertNotNull(p);
        int west = p.room(at(6, 10)), east = p.room(at(18, 10)), south = p.room(at(12, 16));
        assertEquals(3, p.rooms());
        assertTrue(west >= 0 && east >= 0 && south >= 0);
        assertEquals(AiRooms.OUTSIDE, p.room(at(12, 10)));
        // west to east straight through: the thrower before the meeting, in its own stretch
        AiRooms.Way w = between(p, west, east);
        assertNotNull(w);
        assertEquals(-1, w.turn);
        assertEquals(Dirs.E, w.in(east));
        assertEquals(at(11, 10), w.front(east));
        // from south to west round the corner: thrown from the meeting
        AiRooms.Way v = between(p, south, west);
        assertNotNull(v);
        assertTrue(v.turn >= 0);
        assertEquals(at(12, 10), v.front(west));
        assertEquals(Dirs.W, v.in(west));
        assertNotNull(between(p, south, east));
    }

    @Test
    void turnsOneAfterAnotherAreCutTheSameReadFromEitherEnd() {
        PathSearchTest.Grid g = ground();
        // x 4..9 at z 5, then a staircase of turns: (10, 5) (10, 6) (11, 6) (11, 7) (12, 7), then x 13..18 at z 7
        for (int x = 4; x <= 10; x++) dig(g, x, 5);
        dig(g, 10, 6);
        dig(g, 11, 6);
        dig(g, 11, 7);
        for (int x = 12; x <= 18; x++) dig(g, x, 7);
        g.build();
        AiRooms.Plan p = AiTrenches.of(g, at(5, 5)), q = AiTrenches.of(g, at(17, 7));
        assertNotNull(p);
        assertNotNull(q);
        assertEquals(p.rooms(), q.rooms());
        assertEquals(p.ways.length, q.ways.length);
        java.util.Set<Integer> a = new java.util.TreeSet<>(), b = new java.util.TreeSet<>();
        for (int s : p.size) a.add(s);
        for (int s : q.size) b.add(s);
        assertEquals(a, b);
    }

    @Test
    void noTrenchOnTheGroundThreeWideOrTooShort() {
        PathSearchTest.Grid g = zigzag();
        assertNull(AiTrenches.of(g, BlockPos.asLong(8, 2, 8)));
        PathSearchTest.Grid wide = ground();
        for (int x = 5; x <= 15; x++) for (int z = 5; z <= 7; z++) dig(wide, x, z);
        assertNull(AiTrenches.of(wide.build(), at(8, 6)));
        PathSearchTest.Grid pit = ground();
        for (int x = 5; x <= 8; x++) dig(pit, x, 5);
        assertNull(AiTrenches.of(pit.build(), at(6, 5)));
    }

    @Test
    void aTrenchTwoWideTurnsAtASquareOfFour() {
        PathSearchTest.Grid g = ground();
        // x 5..13 at z 5..6, the turn x 12..13 at z 5..6, then z 7..15 at x 12..13
        for (int x = 5; x <= 13; x++) for (int z = 5; z <= 6; z++) dig(g, x, z);
        for (int z = 7; z <= 15; z++) for (int x = 12; x <= 13; x++) dig(g, x, z);
        g.build();
        AiRooms.Plan p = AiTrenches.of(g, at(7, 5));
        assertNotNull(p);
        assertEquals(2, p.rooms());
        int a = p.room(at(7, 6)), b = p.room(at(13, 11));
        assertTrue(a >= 0 && b >= 0 && a != b);
        assertEquals(p.room(at(7, 5)), a);
        for (int x = 12; x <= 13; x++) for (int z = 5; z <= 6; z++) assertEquals(AiRooms.OUTSIDE, p.room(at(x, z)));
        AiRooms.Way w = between(p, a, b);
        assertNotNull(w);
        assertEquals(2, w.width);
        assertEquals(4, w.cells.length);
        assertEquals(2, w.beside(a).length);
        assertEquals(2, w.beside(b).length);
        assertEquals(Dirs.S, w.in(b));
        // thrown into b from the turn's cell stepped out to from beside it, with b straight on south of it
        long f = w.front(b);
        assertEquals(6, BlockPos.getZ(f));
        assertTrue(BlockPos.getX(f) == 12 || BlockPos.getX(f) == 13);
    }

    @Test
    void theStandsZigzagTwoWideWithAFireStepByATurn() {
        // the stand's trench moved to the test's ground; the step one cell off the last turn's inside corner
        PathSearchTest.Grid g = ground();
        for (int[] c : AiCheckTrench.BROAD) dig(g, c[0] - 3604, c[1] - 8);
        for (int x = AiCheckTrench.STEP_X0; x <= AiCheckTrench.STEP_X1; x++) g.set(x - 3604, 1, AiCheckTrench.STEP_Z - 8, Voxel.AIR);
        g.build();
        AiRooms.Plan p = AiTrenches.of(g, at(AiCheckTrench.BROAD[6][0] - 3604, AiCheckTrench.BROAD[6][1] - 8));
        assertNotNull(p);
        assertEquals(5, p.rooms());
        int turns = 0;
        for (AiRooms.Way w : p.ways) if (w.turn >= 0) turns++;
        assertEquals(4, turns);
        int last = p.room(at(24, 5));
        assertTrue(last >= 0);
        assertEquals(last, p.room(at(21, 5)));
        assertEquals(last, p.room(BlockPos.asLong(24, 1, 6)));
    }

    @Test
    void aFireStepIsItsStretchs() {
        PathSearchTest.Grid g = ground();
        for (int x = 5; x <= 15; x++) {
            dig(g, x, 5);
            dig(g, x, 6);
        }
        // a trench two wide, a step one up along its north side, the wall at its feet beyond it
        for (int x = 7; x <= 13; x++) g.set(x, 1, 4, Voxel.AIR);
        g.build();
        AiRooms.Plan p = AiTrenches.of(g, at(6, 5));
        assertNotNull(p);
        assertEquals(1, p.rooms());
        assertEquals(p.room(at(6, 5)), p.room(at(10, 6)));
        assertEquals(p.room(at(6, 5)), p.room(BlockPos.asLong(9, 1, 4)));
        // an alley three wide is no trench
        PathSearchTest.Grid alley = ground();
        for (int x = 5; x <= 15; x++) for (int z = 5; z <= 7; z++) {
            dig(alley, x, z);
        }
        assertNull(AiTrenches.of(alley.build(), at(8, 5)));
    }

    @Test
    void aDitchWithStepsIsATrenchACraterNone() {
        // a ditch: its floor one wide along z 10 at x 5..25, a step up a block each side, then the ground: a trench, its steps its
        // stretch's
        PathSearchTest.Grid g = new PathSearchTest.Grid(2);
        for (int x = 2; x <= 29; x++)
            for (int z = 2; z <= 29; z++) {
                int up = x < 5 || x > 25 ? 2 : Math.min(Math.abs(z - 10), 2);
                for (int y = 0; y < up; y++) g.set(x, y, z, STONE);
            }
        AiRooms.Plan p = AiTrenches.of(g.build(), at(10, 10));
        assertNotNull(p);
        assertEquals(1, p.rooms());
        assertEquals(0, p.room(BlockPos.asLong(10, 1, 9)));
        assertEquals(0, p.room(BlockPos.asLong(10, 1, 11)));
        // a crater: two deep, its floor two by two, a step up round it
        PathSearchTest.Grid c = ground();
        for (int x = 9; x <= 14; x++) for (int z = 9; z <= 14; z++) c.set(x, 1, z, Voxel.AIR);
        for (int x = 10; x <= 13; x++) for (int z = 10; z <= 13; z++) c.set(x, 0, z, Voxel.AIR);
        for (int x = 11; x <= 12; x++) for (int z = 11; z <= 12; z++) c.set(x, 0, z, Voxel.AIR);
        assertNull(AiTrenches.of(c.build(), at(11, 11)));
    }

    @Test
    void underARoofItIsABuildingNotATrench() {
        PathSearchTest.Grid g = ground();
        for (int x = 5; x <= 15; x++) dig(g, x, 5);
        for (int x = 4; x <= 16; x++) for (int z = 4; z <= 6; z++) g.set(x, 3, z, STONE);
        assertNull(AiTrenches.of(g.build(), at(8, 5)));
    }
}
