package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

// A bunker of hand-made cells read for its slits: one a block wide in a front wall two thick, two side by side as one,
// a doorway and a pane as none.
class AiBunkerTest {
    static final byte STONE = AiRoomsTest.STONE;

    /** a box x 4..10, z 4..10 with a roof, its south wall two thick (z 9..10): inside x 5..9, z 5..8; a doorway north at x 7 */
    static PathSearchTest.Grid bunker() {
        PathSearchTest.Grid g = new PathSearchTest.Grid(2);
        AiRoomsTest.box(g, 4, 4, 10, 10);
        for (int x = 4; x <= 10; x++) for (int y = 0; y < 3; y++) g.set(x, y, 9, STONE);
        AiRoomsTest.hole(g, 7, 4);
        return g;
    }

    static void slit(PathSearchTest.Grid g, int x) {
        g.set(x, 1, 9, Voxel.AIR);
        g.set(x, 1, 10, Voxel.AIR);
    }

    static List<AiBunker.Slit> read(PathSearchTest.Grid g) {
        g.build();
        AiRooms.Plan p = AiRooms.of(g, BlockPos.asLong(7, 0, 6));
        return AiBunker.slits(g, p);
    }

    @Test
    void aSlitAtHeadHeightInAThickWall() {
        PathSearchTest.Grid g = bunker();
        slit(g, 7);
        List<AiBunker.Slit> s = read(g);
        assertEquals(1, s.size());
        AiBunker.Slit o = s.get(0);
        assertEquals(Dirs.S, o.dir());
        assertEquals(1, o.width());
        assertEquals(2, o.thick());
        assertEquals(0.5, o.tan(), 1e-9);
        // its outer face's middle, the cell before it, the places beside it two past its end against the wall
        assertEquals(7.5, o.fx(), 1e-9);
        assertEquals(11, o.fz(), 1e-9);
        assertEquals(BlockPos.asLong(7, 0, 11), o.front());
        assertEquals(2, o.sides().length);
        assertTrue(o.sides()[0] == BlockPos.asLong(5, 0, 11) && o.sides()[1] == BlockPos.asLong(9, 0, 11)
                || o.sides()[0] == BlockPos.asLong(9, 0, 11) && o.sides()[1] == BlockPos.asLong(5, 0, 11));
        // a grenade two cells in, where the one firing out of it stands
        assertEquals(7.5, o.aim().x, 1e-9);
        assertEquals(7.5, o.aim().z, 1e-9);
        // its wedge: straight out and widening by half a block a block; not beside it, not behind the wall
        assertTrue(o.sees(7.5, 0, 20));
        assertTrue(o.sees(11.5, 0, 20));
        assertFalse(o.sees(13.5, 0, 20));
        for (long side : o.sides()) assertFalse(o.sees(BlockPos.getX(side) + 0.5, 0, BlockPos.getZ(side) + 0.5));
        assertFalse(o.sees(7.5, 0, 7));
    }

    @Test
    void twoSlitsSideBySideAreOne() {
        PathSearchTest.Grid g = bunker();
        slit(g, 6);
        slit(g, 7);
        List<AiBunker.Slit> s = read(g);
        assertEquals(1, s.size());
        assertEquals(2, s.get(0).width());
        assertEquals(1.0, s.get(0).tan(), 1e-9);
        assertEquals(7, s.get(0).fx(), 1e-9);
    }

    @Test
    void aHoleIntoAnotherRoomIsNoSlit() {
        // a second room south of the front wall under the same roof (x 4..10, z 10..14): the hole looks into it
        PathSearchTest.Grid g = bunker();
        AiRoomsTest.box(g, 4, 10, 10, 14);
        for (int x = 4; x <= 10; x++) for (int y = 0; y < 3; y++) g.set(x, y, 9, STONE);
        AiRoomsTest.hole(g, 7, 14);
        g.set(7, 1, 9, Voxel.AIR);
        g.set(7, 1, 10, Voxel.AIR);
        assertEquals(0, read(g).size());
    }

    @Test
    void aDoorwayAndAPaneAreNoSlits() {
        PathSearchTest.Grid g = bunker();
        // a doorway in the front wall, glass at head height in the east one
        AiRoomsTest.hole(g, 7, 9);
        AiRoomsTest.hole(g, 7, 10);
        g.set(10, 1, 6, Voxel.of(Voxel.SOLID, false, 0, false, false, false));
        assertEquals(0, read(g).size());
    }
}
