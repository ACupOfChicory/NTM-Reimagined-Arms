package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// A friend's line of fire costs a path's step in it, and only near it.
class FireLinesTest {
    // a friend at x 0 (eye 1.6 up) firing along +x at a target 20 off
    private final FireLines line = new FireLines(new double[] {0.5, 65.6, 0.5, 20.5, 65.6, 0.5});

    @Test
    void aStepInTheLineCostsAndOneBesideItDoesNot() {
        assertEquals(FireLines.CROSS, line.extra(10, 64, 0));
        // a block to the side: near the friend the line passes beside it; farther out its spread reaches it, as the
        // friend's own check widens (AiBots.crosses: CLEAR_CONE)
        assertEquals(0F, line.extra(2, 64, 1));
        assertEquals(0F, line.extra(2, 64, -1));
        assertEquals(FireLines.CROSS, line.extra(18, 64, 1));
        assertEquals(0F, line.extra(10, 64, 2));
        // a slanted line (a friend firing down a slope): in it where the line is within its body
        FireLines down = new FireLines(new double[] {0.5, 65.6, 0.5, 20.5, 59.6, 0.5});
        assertEquals(FireLines.CROSS, down.extra(10, 62, 0));
        assertEquals(0F, down.extra(10, 64, 0));
        // behind the friend and past the target: the shot does not go there (the friend looks a block past)
        assertEquals(0F, line.extra(-3, 64, 0));
        assertEquals(FireLines.CROSS, line.extra(21, 64, 0));
        assertEquals(0F, line.extra(24, 64, 0));
        // a floor below: its head under the line
        assertEquals(0F, line.extra(10, 62, 0));
    }

    @Test
    void costsAddUpEachSearchItsOwn() {
        PathSearch.Cost one = (x, y, z) -> 1F;
        assertSame(one, PathSearch.Cost.sum(one, PathSearch.Cost.NONE));
        assertSame(one, PathSearch.Cost.sum(PathSearch.Cost.NONE, one));
        PathSearch.Cost both = PathSearch.Cost.sum(one, line);
        assertEquals(1F + FireLines.CROSS, both.extra(10, 64, 0));
        assertEquals(1F + FireLines.CROSS, both.forSearch().extra(10, 64, 0));
        assertEquals(1F, both.extra(10, 64, 5));
    }

    @Test
    void aRunAlongAFriendsLineOfFireGoesBesideIt() {
        // a friend at the start's side firing down the very row the run goes along (feet at y 0, eye 1.6)
        PathSearchTest.Grid g = new PathSearchTest.Grid(2).build();
        FireLines fire = new FireLines(new double[] {1.5, 1.6, 4.5, 30.5, 1.6, 4.5});
        PathSearch.Result plain = PathSearch.find(g, PathSearchTest.at(3, 0, 4), PathSearchTest.at(27, 0, 4), 0, 4000, PathSearch.Cost.NONE);
        PathSearch.Result kept = PathSearch.find(g, PathSearchTest.at(3, 0, 4), PathSearchTest.at(27, 0, 4), 0, 4000, fire);
        assertTrue(plain.reached && kept.reached);
        int inPlain = 0, inKept = 0;
        for (long c : plain.cells) if (fire.extra(net.minecraft.core.BlockPos.getX(c), 0, net.minecraft.core.BlockPos.getZ(c)) > 0) inPlain++;
        for (long c : kept.cells) if (fire.extra(net.minecraft.core.BlockPos.getX(c), 0, net.minecraft.core.BlockPos.getZ(c)) > 0) inKept++;
        // straight down the line it would stand in it all the way; it steps out of it by the start and back in at the
        // end, where the line has spread
        assertEquals(plain.cells.length, inPlain);
        assertTrue(inKept <= 4, "steps in the line " + inKept);
        PathSearchTest.stepsAreNeighbours(kept);
    }
}
