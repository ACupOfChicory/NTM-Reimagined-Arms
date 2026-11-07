package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

// Where to hide from a threat, on hand-made cells: behind the wall, hidden, with a place to shoot from at its end.
class CoverQueryTest {
    static final Vec3 NORTH = new Vec3(12.3, 1.6, 2.5), SOUTH = new Vec3(12.3, 1.6, 22.5), SELF = new Vec3(11.5, 0, 14.5);

    /** a stone wall two high at z = 10, x 10..13, and a concrete one one high at z = 20, x 4..8 */
    static PathSearchTest.Grid yard() {
        PathSearchTest.Grid g = new PathSearchTest.Grid(2);
        for (int x = 10; x <= 13; x++) {
            g.set(x, 0, 10, CellBuilderTest.STONE);
            g.set(x, 1, 10, CellBuilderTest.STONE);
        }
        for (int x = 4; x <= 8; x++) g.set(x, 0, 20, CellBuilderTest.CONCRETE);
        return g.build();
    }

    static VoxelRay.Source rays(PathSearchTest.Grid g) {
        return g::voxel;
    }

    @Test
    void behindTheWallHiddenAndBestWhereItCanShootFrom() {
        PathSearchTest.Grid g = yard();
        List<CoverQuery.Spot> spots = CoverQuery.find(g, rays(g), SELF, List.of(NORTH), new CoverQuery.Params(), k -> false, 6);
        assertFalse(spots.isEmpty());
        for (CoverQuery.Spot s : spots) {
            int x = BlockPos.getX(s.cell()), z = BlockPos.getZ(s.cell());
            // behind the stone wall, or the low concrete one (legs hidden)
            if (s.height() == 2) assertEquals(11, z, "behind the wall: " + BlockPos.of(s.cell()));
            else assertEquals(21, z, "behind the low wall: " + BlockPos.of(s.cell()));
            assertTrue(s.hard());
            assertFalse(VoxelRay.clear(rays(g), NORTH.x, NORTH.y, NORTH.z, x + 0.5, s.height() == 2 ? 1.6 : 0.5, z + 0.5, Voxel.SIGHT));
        }
        CoverQuery.Spot best = spots.get(0);
        assertEquals(2, best.height(), "high cover first");
        assertTrue(best.hasPeek(), "the best spot has a place beside it to shoot from");
        long peek = best.peek();
        assertTrue(VoxelRay.clear(rays(g), NORTH.x, NORTH.y, NORTH.z, BlockPos.getX(peek) + 0.5, 1.6, BlockPos.getZ(peek) + 0.5, Voxel.SIGHT));
        int bx = BlockPos.getX(best.cell());
        assertTrue(bx == 10 || bx == 13, "at an end of the wall: " + bx);
    }

    @Test
    void heldCellsAreLeftToOthersAndTheCurrentOneIsKept() {
        PathSearchTest.Grid g = yard();
        long a = BlockPos.asLong(10, 0, 11), b = BlockPos.asLong(13, 0, 11);
        List<CoverQuery.Spot> spots = CoverQuery.find(g, rays(g), SELF, List.of(NORTH), new CoverQuery.Params(), k -> k == a || k == b, 6);
        for (CoverQuery.Spot s : spots) assertTrue(s.cell() != a && s.cell() != b);
        CoverQuery.Params p = new CoverQuery.Params();
        p.current = BlockPos.asLong(11, 0, 11);
        spots = CoverQuery.find(g, rays(g), SELF, List.of(NORTH), p, k -> k == a || k == b, 6);
        assertEquals(p.current, spots.get(0).cell(), "where it is going already, nothing clearly better");
    }

    @Test
    void hiddenFromEveryThreatOrNothing() {
        PathSearchTest.Grid g = yard();
        List<CoverQuery.Spot> spots = CoverQuery.find(g, rays(g), SELF, List.of(NORTH, SOUTH), new CoverQuery.Params(), k -> false, 6);
        for (CoverQuery.Spot s : spots) {
            int x = BlockPos.getX(s.cell()), z = BlockPos.getZ(s.cell());
            assertFalse(VoxelRay.clear(rays(g), SOUTH.x, SOUTH.y, SOUTH.z, x + 0.5, s.height() == 2 ? 1.6 : 0.5, z + 0.5, Voxel.SIGHT));
        }
        assertTrue(spots.stream().noneMatch(s -> BlockPos.getZ(s.cell()) == 11), "the wall hides nothing from the south");
    }

    @Test
    void lowCoverHidesTheLegsAndIsShotOver() {
        PathSearchTest.Grid g = yard();
        Vec3 south = new Vec3(6.5, 1.6, 30.5);
        CoverQuery.Params p = new CoverQuery.Params();
        p.radius = 5;
        List<CoverQuery.Spot> spots = CoverQuery.find(g, rays(g), new Vec3(6.5, 0, 16.5), List.of(south), p, k -> false, 6);
        assertFalse(spots.isEmpty());
        CoverQuery.Spot s = spots.get(0);
        assertEquals(19, BlockPos.getZ(s.cell()));
        assertEquals(1, s.height());
        assertEquals(s.cell(), s.peek(), "shoots over it from where it stands");
    }
}
