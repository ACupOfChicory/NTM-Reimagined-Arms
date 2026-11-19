package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import acup.gunfeel.GameRandoms;
import acup.gunfeel.Vec3d;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

// Where a target gone behind a wall may come out: past the wall's end it was going to, where the watcher sees again.
class ExitSpotsTest {
    // a wall x 10..18 at z 10, three high; the watcher south of it; the target lost behind its west end
    private static PathSearchTest.Grid wall() {
        PathSearchTest.Grid g = new PathSearchTest.Grid(2);
        for (int x = 10; x <= 18; x++)
            for (int h = 0; h < 3; h++) g.set(x, h, 10, PathSearchTest.STONE);
        return g.build();
    }

    private static final Vec3d EYE = new Vec3d(14.5, 1.62, 2.5);

    @Test
    void goingEastItWatchesPastTheEastEnd() {
        PathSearchTest.Grid g = wall();
        for (int seed = 0; seed < 10; seed++) {
            long cell = ExitSpots.pick(g, g::voxel, EYE, 10.5, 0, 11.5, 0.15, 0, GameRandoms.of(RandomSource.create(seed)));
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
        long cell = ExitSpots.pick(g, g::voxel, EYE, 16.5, 0, 11.5, -0.15, 0, GameRandoms.of(RandomSource.create(1)));
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
                Vec3d eye = new Vec3d(sx, 1.62, 12.5);
                long cell = ExitSpots.pick(g, g::voxel, eye, east ? 15.0 : 28.0, 0, 31.5, east ? 0.15 : -0.15, 0, GameRandoms.of(RandomSource.create(3)));
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
        assertTrue(VoxelRay.clear(g::voxel, EYE.x(), EYE.y(), EYE.z(), 8.5, 1.0, 11.5, Voxel.SIGHT));
        long cell = ExitSpots.pick(g, g::voxel, EYE, 8.5, 0, 11.5, 0.15, 0, GameRandoms.of(RandomSource.create(2)));
        assertNotEquals(Long.MIN_VALUE, cell);
        assertTrue(BlockPos.getX(cell) >= 19, "past the east end: " + BlockPos.of(cell));
    }

    @Test
    void behindALongWallOneOfASquadWatchesOneEndAndTheNextTheOther() {
        // the alert stand's wall: x 12..36 at z 17, three high; the target lost behind it, the watcher south of it, back
        // from the wall or right against it (as one in cover there); for one alone the walk's exit (or, lost behind the
        // middle out of the walk's reach, a corner); one of its own on that end: the other end
        PathSearchTest.Grid g = new PathSearchTest.Grid(3);
        for (int x = 12; x <= 36; x++)
            for (int h = 0; h < 3; h++) g.set(x, h, 17, PathSearchTest.STONE);
        g.build();
        for (Vec3d eye : new Vec3d[] {new Vec3d(24.5, 1.62, 10.5), new Vec3d(26.5, 1.62, 16.4)})
            for (double lx : new double[] {20.5, 24.5, 28.5}) {
                String what = "eye " + eye + " lost " + lx;
                long first = ExitSpots.pick(g, g::voxel, eye, lx, 0, 24.5, 0, 0, GameRandoms.of(RandomSource.create(5)), java.util.List.of());
                assertNotEquals(Long.MIN_VALUE, first, what);
                // at an end: past it or just before its end column, where one coming round it shows
                boolean west = BlockPos.getX(first) <= 12;
                assertTrue(west || BlockPos.getX(first) >= 36, "at an end: " + BlockPos.of(first) + ", " + what);
                Vec3d taken = new Vec3d(BlockPos.getX(first) + 0.5, BlockPos.getY(first) + 1.1, BlockPos.getZ(first) + 0.5);
                long next = ExitSpots.pick(g, g::voxel, eye, lx, 0, 24.5, 0, 0, GameRandoms.of(RandomSource.create(6)), java.util.List.of(taken));
                assertNotEquals(Long.MIN_VALUE, next, what);
                assertTrue(west ? BlockPos.getX(next) >= 36 : BlockPos.getX(next) <= 12, "the other end: " + BlockPos.of(next) + ", " + what);
                assertTrue(VoxelRay.clear(g::voxel, eye.x(), eye.y(), eye.z(), BlockPos.getX(next) + 0.5, 1.0, BlockPos.getZ(next) + 0.5, Voxel.SIGHT));
            }
    }

    @Test
    void aFewPicksATickForAll() {
        long tick = 123456789L;
        for (int i = 0; i < ExitSpots.PER_TICK; i++) assertTrue(ExitSpots.mayPick(tick));
        assertFalse(ExitSpots.mayPick(tick));
        assertTrue(ExitSpots.mayPick(tick + 1));
    }
}
