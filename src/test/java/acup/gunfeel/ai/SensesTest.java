package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

// The rays through the blocks and the memory of targets, without Minecraft.
class SensesTest {
    static final int STONE = Voxel.of(Voxel.SOLID, true, 2, false, false, false);
    static final int GLASS = Voxel.of(Voxel.SOLID, false, 0, false, false, false);

    static final class Blocks implements VoxelRay.Source {
        final Map<Long, Integer> at = new HashMap<>();
        int asked;

        void put(int x, int y, int z, int v) {
            at.put(key(x, y, z), v);
        }

        static long key(int x, int y, int z) {
            return ((long) x & 0xFFFFF) << 40 | ((long) y & 0xFFFFF) << 20 | ((long) z & 0xFFFFF);
        }

        @Override
        public int voxel(int x, int y, int z) {
            asked++;
            return at.getOrDefault(key(x, y, z), 0);
        }
    }

    @Test
    void rayStopsAtHidingBlocksOnly() {
        Blocks b = new Blocks();
        b.put(5, 0, 0, STONE);
        b.put(0, 0, 5, GLASS);
        assertFalse(VoxelRay.clear(b, 0.5, 0.5, 0.5, 9.5, 0.5, 0.5, Voxel.SIGHT));
        assertTrue(VoxelRay.clear(b, 0.5, 0.5, 0.5, 0.5, 0.5, 9.5, Voxel.SIGHT), "glass hides nothing");
        assertTrue(VoxelRay.clear(b, 0.5, 0.5, 0.5, 4.9, 0.5, 0.5, Voxel.SIGHT), "the wall is past the end");
        assertEquals(4.5 / 9, VoxelRay.blocked(b, 0.5, 0.5, 0.5, 9.5, 0.5, 0.5, Voxel.SIGHT), 1e-9);
        // the start block is not asked: an eye in a leaf still sees out
        b.put(0, 0, 0, STONE);
        assertTrue(VoxelRay.clear(b, 0.5, 0.5, 0.5, 0.5, 0.5, 4.5, Voxel.SIGHT));
    }

    @Test
    void rayGoesDiagonallyAndDownAndVisitsEveryBlockOnce() {
        Blocks b = new Blocks();
        b.put(3, -2, 3, STONE);
        assertFalse(VoxelRay.clear(b, 0.2, 0.9, 0.2, 6.7, -4.6, 6.8, Voxel.SIGHT));
        Blocks open = new Blocks();
        assertTrue(VoxelRay.clear(open, -10.5, 3.3, 7.1, 20.25, -1.7, -8.8, Voxel.SIGHT));
        // as many blocks as the line crosses: |dx| + |dy| + |dz| steps at most
        assertTrue(open.asked <= 31 + 5 + 16);
    }

    static final AiProfile P = new AiProfile(96, 140, 1, 8, 4, 100, 200, 1000, 4, 4);

    @Test
    void noticedAfterTheReactionTimeOnly() {
        TargetMemory m = new TargetMemory(P);
        long t = 0;
        for (; t < 8; t += 4) {
            m.look();
            m.seen(1, 10, 64, 0, t, 12);
            m.endLook(t);
            assertFalse(m.contact(1).known(), "not yet at " + t);
        }
        m.look();
        m.seen(1, 10, 64, 0, t, 12);
        m.endLook(t);
        assertEquals(TargetMemory.State.VISIBLE, m.contact(1).state);
        // a glimpse that ends goes
        m.look();
        m.seen(2, 0, 64, 10, t, 12);
        m.endLook(t);
        m.look();
        m.endLook(t + 4);
        assertNull(m.contact(2));
    }

    @Test
    void lostTargetStaysWhereItWentOutOfSight() {
        TargetMemory m = new TargetMemory(P);
        long t = 0;
        for (; t <= 12; t += 4) {
            m.look();
            m.seen(1, 10 + t * 0.1, 64, 0, t, 4);
            m.endLook(t);
        }
        TargetMemory.Contact c = m.contact(1);
        assertEquals(TargetMemory.State.VISIBLE, c.state);
        assertEquals(0.1, c.vx, 1e-9, "its motion from where it was seen");
        double lastX = c.x;
        // out of sight: the memory keeps the last place, whatever the target does now
        m.look();
        m.endLook(16);
        assertEquals(TargetMemory.State.LOST, c.state);
        assertEquals(lastX, c.lostX);
        assertEquals(16, c.lostTick);
        m.update(16 + P.hold());
        assertEquals(TargetMemory.State.LOST, c.state);
        m.update(17 + P.hold());
        assertEquals(TargetMemory.State.SEARCH, c.state);
        assertEquals(2, m.searchRadius(c, 16 + P.hold()), 1e-9);
        assertEquals(2 + 4 * 5, m.searchRadius(c, 16 + P.hold() + 100), 1e-9);
        assertEquals(32, m.searchRadius(c, 16 + P.hold() + 10000), 1e-9);
        assertNotNull(m.focus(0, 0));
        m.update(17 + P.hold() + P.search());
        assertNull(m.contact(1), "forgotten");
        assertNull(m.focus(0, 0));
    }

    @Test
    void aKnownTargetIsNoticedAgainFaster() {
        TargetMemory m = new TargetMemory(P);
        m.look();
        m.seen(1, 0, 64, 10, 0, 4);
        m.endLook(0);
        m.look();
        m.endLook(4);
        assertEquals(TargetMemory.State.LOST, m.contact(1).state);
        m.look();
        m.seen(1, 3, 64, 10, 8, 8);
        m.endLook(8);
        assertEquals(TargetMemory.State.VISIBLE, m.contact(1).state, "half of 8 ticks in one look of 4");
        assertEquals(3, m.contact(1).x);
    }

    @Test
    void focusIsTheNearestInSightElseTheLastLost() {
        TargetMemory m = new TargetMemory(P);
        m.look();
        m.seen(1, 30, 64, 0, 0, 0);
        m.seen(2, 10, 64, 0, 0, 0);
        m.endLook(0);
        assertEquals(2, m.focus(0, 0).id);
        m.look();
        m.seen(1, 30, 64, 0, 4, 0);
        m.endLook(4);
        assertEquals(1, m.focus(0, 0).id, "the one in sight beats the lost one");
        m.look();
        m.endLook(8);
        m.look();
        m.endLook(12);
        assertEquals(1, m.focus(0, 0).id, "both lost: the one lost last");
    }

    @Test
    void soundsFromTheSameBlockAreNotNewsAndQuietOnesDoNotDrownALoudOne() {
        TargetMemory m = new TargetMemory(P);
        assertTrue(m.heard(10.2, 64, 5.5, 0, true, false));
        assertFalse(m.heard(10.7, 64.3, 5.1, 20, true, false), "the same block again");
        assertFalse(m.heard(40, 64, 40, 30, false, false), "quiet after loud");
        assertEquals(10.2, m.noise().x);
        assertTrue(m.heard(10.5, 64, 5.5, 40, true, true), "a hit is always news");
        assertTrue(m.heard(10.5, 64, 5.5, 40 + TargetMemory.HABITUATE + 1, true, false));
        m.update(40 + TargetMemory.HABITUATE + 1 + P.search() + 1);
        assertNull(m.noise());
    }
}
