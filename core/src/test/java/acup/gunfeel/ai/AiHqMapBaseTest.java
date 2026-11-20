package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

// the staff's map in core: the cells from the tiles, kept per level (any object) and side, made again off the thread
class AiHqMapBaseTest {
    /** chunk (0, 0) only: ground at 64; its west half under 3 blocks of water, leaves 3 high right over the ground in its north half */
    private static TerrainTile tile() {
        short[] ground = new short[256];
        byte[] canopy = new byte[256], under = new byte[256], water = new byte[256];
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                int i = z << 4 | x;
                ground[i] = 64;
                if (x < 8) water[i] = 3;
                if (z < 8) canopy[i] = 3;
            }
        return TerrainTile.of(TerrainTile.key(0, 0), 1, ground, canopy, under, water);
    }

    private static Skyline.Tiles only(TerrainTile t) {
        return (x, z) -> x >> 4 == 0 && z >> 4 == 0 ? t : null;
    }

    @Test
    void theCellsAreTheTilesAveraged() {
        TerrainTile t = tile();
        HqGrid g = AiHqMapBase.build(only(t), 4, 4);
        assertEquals(-256, g.x0);
        assertEquals(-256, g.z0);
        // the chunk's four cells: (32, 32) west-north, (33, 32) east-north, (32, 33) west-south, (33, 33) east-south
        int[][] cells = {{32, 32}, {33, 32}, {32, 33}, {33, 33}};
        for (int[] c : cells) {
            int bx = g.x0 + c[0] * HqGrid.CELL, bz = g.z0 + c[1] * HqGrid.CELL, at = HqGrid.index(c[0], c[1]);
            boolean west = bx < 8, north = bz < 8;
            assertTrue(g.known[at]);
            assertEquals(west ? 67 : 64, g.h[at], 1e-6, "height " + bx + " " + bz);
            assertEquals(west ? 1 : 0, g.wet[at], 1e-6);
            assertEquals(west ? 1 : 0, g.deep[at], 1e-6);
            assertEquals(north ? 1 : 0, g.crown[at], 1e-6);
        }
        // no tile kept: not known
        int known = 0;
        for (boolean k : g.known) if (k) known++;
        assertEquals(4, known);
        assertFalse(g.known[HqGrid.index(31, 32)]);
    }

    @Test
    void keptPerLevelAndSide() {
        Object a = new Object(), b = new Object();
        AiHqMapBase.Side s = AiHqMapBase.side(a, "red");
        assertSame(s, AiHqMapBase.side(a, "red"));
        assertNull(AiHqMapBase.grid(a, "red"));
        HqGrid g = new HqGrid(0, 0);
        s.grid = g;
        AiHqMapBase.side(b, "red").grid = new HqGrid(8, 8);
        assertSame(g, AiHqMapBase.grid(a, "red"));
        assertNull(AiHqMapBase.grid(a, "blue"));
        AiHqMapBase.forgetLevel(a);
        assertNull(AiHqMapBase.grid(a, "red"));
        assertNotNull(AiHqMapBase.grid(b, "red"));
        AiHqMapBase.forgetLevel(b);
    }

    @Test
    void madeOffTheThreadTheOldOneReadTillThen() throws InterruptedException {
        Object level = new Object();
        AiHqMapBase.Side side = AiHqMapBase.side(level, "red");
        HqGrid old = new HqGrid(0, 0);
        side.grid = old;
        TerrainTile t = tile();
        CountDownLatch go = new CountDownLatch(1);
        Skyline.Tiles held = (x, z) -> {
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return x >> 4 == 0 && z >> 4 == 0 ? t : null;
        };
        long before = AiHqMapBase.built.get();
        AiHqMapBase.submit(side, held, 4, 4, List.of(new double[] {12, 12, 2}));
        assertTrue(side.building);
        assertSame(old, AiHqMapBase.grid(level, "red"));
        go.countDown();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (side.building && System.nanoTime() < until) Thread.sleep(5);
        assertFalse(side.building);
        HqGrid made = AiHqMapBase.grid(level, "red");
        assertNotNull(made);
        assertEquals(-256, made.x0);
        assertTrue(made.known[HqGrid.index(32, 32)]);
        // the enemy (on a known cell) sees something of the map
        float seen = 0;
        for (float f : made.seen) seen = Math.max(seen, f);
        assertTrue(seen > 0);
        assertTrue(AiHqMapBase.built.get() > before);
        AiHqMapBase.forgetLevel(level);
    }
}
