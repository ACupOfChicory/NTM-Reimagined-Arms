package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Queue;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.PosLong;

// The tactical map off the game: a version's map giving ground under y 0 and air over it; a want builds the sections'
// cells on the AI's threads and the next ticks publish them; a block change counts up the stamps and the cells are
// rebuilt; the changes made off the server thread are drained first thing in a tick; the sections past the world's
// range are never asked of the version
class TacticalMapBaseTest {
    private static final byte GROUND = Voxel.of(Voxel.SOLID, true, 3, false, false, false);

    static final class FakeMap extends TacticalMapBase {
        final Queue<int[]> offThread = new ArrayDeque<>();
        int copies, outside;
        @Nullable Integer unloadedX;

        FakeMap() {
            super(-2, 1);
        }

        @Override
        protected void drainOffThread() {
            for (int[] p; (p = offThread.poll()) != null; ) changed(p[0], p[1], p[2], 0, 0, true);
        }

        @Override
        protected @Nullable SectionSnapshot copy(long key, int sx, int sy, int sz, int version) {
            if (sy < -2 || sy > 1) outside++;
            if (unloadedX != null && sx == unloadedX) return null;
            copies++;
            return sy < 0 ? SectionSnapshot.filled(key, version, GROUND) : SectionSnapshot.air(key, version);
        }
    }

    @AfterEach
    void stop() {
        AiThreads.stop();
    }

    /** ticks till nothing is queued or building, at most a few seconds */
    private static void settle(FakeMap m) throws InterruptedException {
        for (int i = 0; i < 2000; i++) {
            m.tick();
            if (m.queued() == 0 && m.inFlight() == 0) return;
            Thread.sleep(2);
        }
        throw new AssertionError("not settled: queued " + m.queued() + ", building " + m.inFlight());
    }

    @Test
    void aWantBuiltAndAChangeRebuilt() throws InterruptedException {
        FakeMap m = new FakeMap();
        long key = PosLong.section(0, 0, 0);
        m.want(8, 1, 8, 4, 2);
        settle(m);
        SectionCells c = m.cells(key);
        assertNotNull(c, "the wanted section's cells published");
        assertEquals(m.stamp(key), c.stamp);
        assertTrue(m.fresh(c));
        assertEquals(0, m.outside, "below and above the world: the map's own, never the version's");
        assertEquals(GROUND, m.voxel(8, -1, 8));
        assertEquals(Voxel.AIR, m.voxel(8, 1, 8));
        assertEquals(CellKind.NEUTRAL, m.kind(8, 100, 8), "no snapshot: neutral");
        assertEquals(Voxel.UNKNOWN, m.voxel(8, 100, 8), "no snapshot: unknown");
        // the same voxel and kind again: no change
        m.changed(8, 1, 8, Voxel.AIR, CellKind.NEUTRAL, false);
        assertEquals(0, m.changes());
        int stamp = m.stamp(key);
        m.changed(8, 1, 8, GROUND, CellKind.BUILT, false);
        assertEquals(1, m.changes());
        assertEquals(stamp + 1, m.stamp(key));
        assertFalse(m.fresh(c), "built on the blocks as they were");
        assertEquals(2, m.staleCount(), "its section and the one under it (a roof over that one's cells), both wanted");
        settle(m);
        SectionCells again = m.cells(key);
        assertNotNull(again);
        assertTrue(m.fresh(again), "rebuilt");
        assertEquals(0, m.staleCount());
    }

    @Test
    void aChangeAtAnEdgeStampsTheSectionBeside() throws InterruptedException {
        FakeMap m = new FakeMap();
        m.want(16, 1, 8, 20, 2);
        settle(m);
        long here = PosLong.section(1, 0, 0), west = PosLong.section(0, 0, 0), east = PosLong.section(2, 0, 0);
        int h = m.stamp(here), w = m.stamp(west), e = m.stamp(east);
        m.changed(16, 1, 8, GROUND, CellKind.BUILT, false);
        assertEquals(h + 1, m.stamp(here));
        assertEquals(w + 1, m.stamp(west), "x 0 of its section: the one west reads it");
        assertEquals(e, m.stamp(east));
    }

    @Test
    void theChangesOffTheServerThreadDrainedInTheTick() throws InterruptedException {
        FakeMap m = new FakeMap();
        long key = PosLong.section(0, 0, 0);
        m.want(8, 1, 8, 4, 2);
        settle(m);
        int stamp = m.stamp(key);
        int copies = m.copies;
        m.offThread.add(new int[] {3, 2, 5});
        assertEquals(0, m.changes(), "queued, not yet seen");
        m.tick();
        assertTrue(m.offThread.isEmpty());
        assertEquals(1, m.changes(), "unknown voxel: a change even with the same block");
        assertEquals(stamp + 1, m.stamp(key));
        settle(m);
        assertTrue(m.copies > copies, "its snapshot taken again");
    }

    @Test
    void anUnloadedChunkIsNotBuilt() throws InterruptedException {
        FakeMap m = new FakeMap();
        m.unloadedX = 0;
        m.want(8, 1, 8, 4, 2);
        settle(m);
        assertNull(m.cells(PosLong.section(0, 0, 0)));
        assertEquals(Voxel.UNKNOWN, m.voxel(8, 1, 8));
    }
}
