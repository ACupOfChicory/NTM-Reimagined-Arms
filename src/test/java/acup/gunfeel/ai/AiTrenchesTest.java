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
    void whereThreeMeetTheirMeetingIsARoomWithAWayAtEachMouth() {
        PathSearchTest.Grid g = ground();
        for (int x = 5; x <= 19; x++) dig(g, x, 10);
        for (int z = 11; z <= 17; z++) dig(g, 12, z);
        g.build();
        AiRooms.Plan p = AiTrenches.of(g, at(6, 10));
        assertNotNull(p);
        int west = p.room(at(6, 10)), east = p.room(at(18, 10)), south = p.room(at(12, 16)), meet = p.room(at(12, 10));
        assertEquals(4, p.rooms());
        assertTrue(west >= 0 && east >= 0 && south >= 0 && meet >= 0);
        assertEquals(1, p.size[meet]);
        assertEquals(AiRooms.OUTSIDE, p.room(at(11, 10)));
        assertEquals(AiRooms.OUTSIDE, p.room(at(13, 10)));
        assertEquals(AiRooms.OUTSIDE, p.room(at(12, 11)));
        AiRooms.Way w = between(p, south, meet);
        assertNotNull(w);
        assertEquals(-1, w.turn);
        assertEquals(Dirs.N, w.in(meet));
        // straight through: the thrower stands before the mouth, in its own stretch
        assertEquals(at(12, 12), w.front(meet));
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
    void noTrenchOnTheGroundTwoWideOrTooShort() {
        PathSearchTest.Grid g = zigzag();
        assertNull(AiTrenches.of(g, BlockPos.asLong(8, 2, 8)));
        PathSearchTest.Grid wide = ground();
        for (int x = 5; x <= 15; x++) {
            dig(wide, x, 5);
            dig(wide, x, 6);
        }
        assertNull(AiTrenches.of(wide.build(), at(8, 5)));
        PathSearchTest.Grid pit = ground();
        for (int x = 5; x <= 8; x++) dig(pit, x, 5);
        assertNull(AiTrenches.of(pit.build(), at(6, 5)));
    }

    @Test
    void underARoofItIsABuildingNotATrench() {
        PathSearchTest.Grid g = ground();
        for (int x = 5; x <= 15; x++) dig(g, x, 5);
        for (int x = 4; x <= 16; x++) for (int z = 4; z <= 6; z++) g.set(x, 3, z, STONE);
        assertNull(AiTrenches.of(g.build(), at(8, 5)));
    }
}
