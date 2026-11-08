package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

// Where a target gone behind a wall may come out: past the wall's end it was going to, where the watcher sees again.
class ExitSpotsTest {
    // a wall x 10..18 at z 10, three high; the watcher south of it; the target lost behind its west end
    private static PathSearchTest.Grid wall() {
        PathSearchTest.Grid g = new PathSearchTest.Grid(2);
        for (int x = 10; x <= 18; x++)
            for (int h = 0; h < 3; h++) g.set(x, h, 10, PathSearchTest.STONE);
        return g.build();
    }

    private static final Vec3 EYE = new Vec3(14.5, 1.62, 2.5);

    @Test
    void goingEastItWatchesPastTheEastEnd() {
        PathSearchTest.Grid g = wall();
        for (int seed = 0; seed < 10; seed++) {
            long cell = ExitSpots.pick(g, g::voxel, EYE, 10.5, 0, 11.5, 0.15, 0, RandomSource.create(seed));
            assertNotEquals(Long.MIN_VALUE, cell);
            int x = BlockPos.getX(cell), z = BlockPos.getZ(cell);
            assertTrue(x >= 19, "past the east end: " + BlockPos.of(cell));
            assertTrue(z >= 9, "on the target's side, not in front of the watcher: " + BlockPos.of(cell));
            assertTrue(ExitSpots.edge(g::voxel, EYE, cell, 10.5, 11.5));
        }
    }

    @Test
    void goingWestItWatchesPastTheWestEnd() {
        PathSearchTest.Grid g = wall();
        long cell = ExitSpots.pick(g, g::voxel, EYE, 16.5, 0, 11.5, -0.15, 0, RandomSource.create(1));
        assertNotEquals(Long.MIN_VALUE, cell);
        assertTrue(BlockPos.getX(cell) <= 9, "past the west end: " + BlockPos.of(cell));
    }

    @Test
    void anEdgeIsSeenAndTheStepBackIsNot() {
        PathSearchTest.Grid g = wall();
        // behind the wall: not seen
        assertFalse(ExitSpots.edge(g::voxel, EYE, PathSearchTest.at(14, 0, 11), 10.5, 11.5));
        // in the open in front: seen, and so is the step back towards the lost place
        assertFalse(ExitSpots.edge(g::voxel, EYE, PathSearchTest.at(14, 0, 5), 10.5, 11.5));
    }

    @Test
    void onTheStandsFieldAnExitIsAlwaysTheFarEnd() {
        // AiCheckExit less 640 across: the wall x 16..26 at z 30, three high; a low wall at z 14; soldiers at z 12.5
        PathSearchTest.Grid g = new PathSearchTest.Grid(3);
        for (int x = 16; x <= 26; x++)
            for (int h = 0; h < 3; h++) g.set(x, h, 30, PathSearchTest.STONE);
        for (int x = 13; x <= 29; x++) g.set(x, 0, 14, PathSearchTest.STONE);
        g.build();
        int found = 0;
        for (double sx : new double[] {15.5, 19.5, 23.5, 27.5})
            for (boolean east : new boolean[] {true, false}) {
                Vec3 eye = new Vec3(sx, 1.62, 12.5);
                long cell = ExitSpots.pick(g, g::voxel, eye, east ? 15.0 : 28.0, 0, 31.5, east ? 0.15 : -0.15, 0, RandomSource.create(3));
                // none where the first step from the place is in sight: it would be seen going that way
                if (cell == Long.MIN_VALUE) continue;
                found++;
                String what = sx + (east ? " east: " : " west: ") + BlockPos.of(cell).toShortString();
                assertTrue(east ? BlockPos.getX(cell) >= 27 : BlockPos.getX(cell) <= 15, "the far end: " + what);
            }
        assertTrue(found >= 4, "found " + found + " of 8");
    }

    @Test
    void lostJustShortOfTheWallStillInSightFromThePeekItStillFindsTheFarEnd() {
        // the watcher out at its peek sees where the target was lost (it lost it from its cover) and a block or two on
        PathSearchTest.Grid g = wall();
        assertTrue(VoxelRay.clear(g::voxel, EYE.x, EYE.y, EYE.z, 8.5, 1.0, 11.5, Voxel.SIGHT));
        long cell = ExitSpots.pick(g, g::voxel, EYE, 8.5, 0, 11.5, 0.15, 0, RandomSource.create(2));
        assertNotEquals(Long.MIN_VALUE, cell);
        assertTrue(BlockPos.getX(cell) >= 19, "past the east end: " + BlockPos.of(cell));
    }

    @Test
    void aFewPicksATickForAll() {
        long tick = 123456789L;
        for (int i = 0; i < ExitSpots.PER_TICK; i++) assertTrue(ExitSpots.mayPick(tick));
        assertFalse(ExitSpots.mayPick(tick));
        assertTrue(ExitSpots.mayPick(tick + 1));
    }
}
