package acup.gunfeel.ai;

import static acup.gunfeel.ai.PathSearchTest.at;
import static acup.gunfeel.ai.PathSearchTest.passes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// A path that keeps out of a threat's sight: a wall two high with the threat on one side, the way along the other.
class ExposureCostTest {
    /** a wall at z = 12 from x 6 to 26, two high; the threat's eye far to the north */
    static PathSearchTest.Grid field() {
        PathSearchTest.Grid g = new PathSearchTest.Grid(2);
        for (int x = 6; x <= 26; x++) {
            g.set(x, 0, 12, PathSearchTest.STONE);
            g.set(x, 1, 12, PathSearchTest.STONE);
        }
        return g.build();
    }

    static final double EX = 16.5, EY = 1.6, EZ = 1.5;

    @Test
    void costsMoreWhereTheThreatSeesTheHead() {
        PathSearchTest.Grid g = field();
        PathSearch.Cost c = new ExposureCost(g, EX, EY, EZ).forSearch();
        assertEquals(ExposureCost.SEEN, c.extra(16, 0, 8), 1e-6);
        assertEquals(0F, c.extra(16, 0, 14), 1e-6);
        // asked again: the same, from its cache
        assertEquals(ExposureCost.SEEN, c.extra(16, 0, 8), 1e-6);
    }

    @Test
    void eachSearchGetsItsOwnCache() {
        ExposureCost base = new ExposureCost(field(), EX, EY, EZ);
        PathSearch.Cost a = base.forSearch(), b = base.forSearch();
        assertNotSame(base, a);
        assertNotSame(a, b);
        // without a cache it works too
        assertEquals(0F, base.extra(16, 0, 14), 1e-6);
    }

    @Test
    void goesRoundBehindTheWallRatherThanAlongTheOpen() {
        PathSearchTest.Grid g = field();
        long from = at(4, 0, 8), to = at(28, 0, 8);
        PathSearch.Result open = PathSearch.find(g, from, to, 0, 4000, PathSearch.Cost.NONE);
        PathSearch.Result hidden = PathSearch.find(g, from, to, 0, 4000, new ExposureCost(g, EX, EY, EZ));
        assertTrue(open.found() && hidden.found());
        assertTrue(passes(open, 16, 0, 8), "without the cost: the straight way");
        assertFalse(passes(hidden, 16, 0, 8));
        boolean behind = false;
        for (long cell : hidden.cells)
            if (net.minecraft.core.BlockPos.getX(cell) == 16) behind |= net.minecraft.core.BlockPos.getZ(cell) > 12;
        assertTrue(behind, "with it: behind the wall");
    }
}
