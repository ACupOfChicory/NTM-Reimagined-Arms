package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

// The banks of a river to cross, off a path and off a straight line (AiCrossing).
class AiCrossingTest {
    @Test
    void theBanksOfTheSwimOnAPath() {
        PathSearchTest.Grid g = PathSearchTest.river(false);
        PathSearch.Result r = PathSearch.find(g, PathSearchTest.at(16, 0, 8), PathSearchTest.at(16, 0, 23), 0, 4000, PathSearch.Cost.NONE);
        assertTrue(r.reached);
        long[] b = AiCrossing.banks(g, r.cells);
        assertNotNull(b);
        // the last cell on the near bank, the first on the far one: dry, either side of the river (z 12..19)
        assertTrue(BlockPos.getZ(b[0]) <= 11, BlockPos.of(b[0]).toString());
        assertTrue(BlockPos.getZ(b[1]) >= 20, BlockPos.of(b[1]).toString());
    }

    @Test
    void aWadeIsNoSwimToCross() {
        PathSearchTest.Grid g = PathSearchTest.river(true);
        PathSearch.Result r = PathSearch.find(g, PathSearchTest.at(16, 0, 8), PathSearchTest.at(16, 0, 23), 0, 4000, PathSearch.Cost.NONE);
        assertTrue(r.reached);
        assertNull(AiCrossing.banks(g, r.cells));
    }

    @Test
    void theBanksOnAStraightLineAcross() {
        PathSearchTest.Grid g = PathSearchTest.river(false);
        long[] b = AiCrossing.line(g, new Vec3(16.5, 0, 6.5), new Vec3(16.5, 0, 26.5));
        assertNotNull(b);
        assertEquals(11, BlockPos.getZ(b[0]));
        assertEquals(20, BlockPos.getZ(b[1]));
        // along the bank: no water to cross
        assertNull(AiCrossing.line(g, new Vec3(2.5, 0, 6.5), new Vec3(28.5, 0, 6.5)));
    }
}
