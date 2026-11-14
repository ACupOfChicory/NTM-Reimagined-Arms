package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

// Buildings made at random and read into rooms (AiRooms): whatever stands there, the reading throws nothing and what
// it gives holds together. Two faults that stopped a server came from shapes no hand-made test had: a roof's open side
// one or two cells wide (a shed, a porch, a tree's crown) and an open edge with a step in it. The shapes here are those
// and their kin: houses with walls taken out, roofs at two heights side by side, floors with steps, roofs on posts,
// crowns on trunks, doorways one to four wide, doors, windows, holes in roofs, blocks strewn about.
class AiRoomsFuzzTest {
    static final byte STONE = CellBuilderTest.STONE;
    static final int SIZE = 32, FAST = 2000, SLOW = 30000;

    @Test
    void randomBuildingsAreReadWithoutAFault() {
        run(0, FAST);
    }

    @Test
    @Tag("slow")
    void manyRandomBuildingsAreReadWithoutAFault() {
        run(FAST, SLOW);
    }

    @Test
    void aFaultInTheReadingIsNoBuildingAndIsCounted() {
        // cells that throw whatever is asked of them: the reading itself throws, the guarded way in gives no building
        CellSource broken = (CellSource) java.lang.reflect.Proxy.newProxyInstance(CellSource.class.getClassLoader(), new Class<?>[] {CellSource.class},
                (proxy, method, args) -> {
                    throw new ArrayIndexOutOfBoundsException("made for the test");
                });
        long cell = BlockPos.asLong(5, 0, 5);
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> AiRooms.read(broken, cell, null, AiRooms.REACH, AiRooms.MAX_CELLS));
        int before = AiRooms.faults;
        org.junit.jupiter.api.Assertions.assertNull(AiRooms.of(broken, cell));
        assertEquals(before + 1, AiRooms.faults);
        // many in a row are all counted (and written once: AiRooms.FAULT_EVERY)
        for (int i = 0; i < 100; i++) org.junit.jupiter.api.Assertions.assertNull(AiRooms.of(broken, cell));
        assertEquals(before + 101, AiRooms.faults);
    }

    private static void run(int from, int count) {
        int plans = 0, ways = 0;
        for (int seed = from; seed < from + count; seed++) {
            Random rng = new Random(seed * 0x9E3779B97F4A7C15L + 17);
            PathSearchTest.Grid g = build(rng);
            int before = AiRooms.faults;
            // read from every cell of a coarse grid and from places picked at random, at each height a floor may be at
            for (int n = 0; n < 40; n++) {
                int x = n < 25 ? 3 + (n % 5) * 6 : 1 + rng.nextInt(SIZE - 2), z = n < 25 ? 3 + (n / 5) * 6 : 1 + rng.nextInt(SIZE - 2);
                for (int y = 0; y <= 2; y++) {
                    long cell = BlockPos.asLong(x, y, z);
                    AiRooms.Plan p;
                    try {
                        p = AiRooms.read(g, cell, null, AiRooms.REACH, AiRooms.MAX_CELLS);
                    } catch (RuntimeException e) {
                        fail("seed " + seed + ", read from " + x + " " + y + " " + z + ": " + e, e);
                        return;
                    }
                    // the guarded way in gives the same and counts no fault
                    AiRooms.Plan guarded = AiRooms.of(g, cell);
                    assertEquals(p == null, guarded == null, "seed " + seed);
                    if (p == null) continue;
                    plans++;
                    ways += p.ways.length;
                    check(p, seed);
                }
            }
            assertEquals(before, AiRooms.faults, "seed " + seed + ": a fault was caught");
        }
        assertTrue(plans > count, "too few buildings were read to tell anything: " + plans);
        assertTrue(ways > count, "too few ways were read to tell anything: " + ways);
    }

    /** what the entry and the search read off a plan holds together */
    private static void check(AiRooms.Plan p, int seed) {
        String at = "seed " + seed;
        long[][] cells = p.cells();
        assertEquals(p.rooms(), cells.length, at);
        for (int room = 0; room < p.rooms(); room++) {
            assertTrue(p.size[room] > 0, at);
            for (AiRooms.Way w : p.waysOf(room)) assertTrue(w.a == room || w.b == room, at);
        }
        for (AiRooms.Way w : p.ways) {
            assertTrue(w.cells.length > 0, at + ": a way with no cells");
            assertTrue(w.width > 0, at);
            for (int end : new int[] {w.a, w.b}) {
                assertEquals(w.beside(end).length, w.far(end).length, at + ": a cell further for each cell beside a way");
                assertEquals(w.beside(end).length, w.far2(end).length, at);
            }
        }
    }

    private static void set(PathSearchTest.Grid g, int x, int y, int z, byte v) {
        if (x >= 0 && x < SIZE && z >= 0 && z < SIZE && y >= 0 && y < 16) g.set(x, y, z, v);
    }

    private static PathSearchTest.Grid build(Random rng) {
        PathSearchTest.Grid g = new PathSearchTest.Grid(SIZE / 16);
        // the ground: a few raised patches (steps in the floor)
        for (int n = rng.nextInt(4); n > 0; n--) {
            int x0 = rng.nextInt(SIZE), z0 = rng.nextInt(SIZE), w = 1 + rng.nextInt(8), d = 1 + rng.nextInt(8), h = 1 + rng.nextInt(2);
            for (int x = x0; x < x0 + w; x++) for (int z = z0; z < z0 + d; z++) for (int y = 0; y < h; y++) set(g, x, y, z, STONE);
        }
        // houses: walls and a roof, some at a step up; a wall or a piece of one taken out; doorways, doors, windows
        for (int n = 1 + rng.nextInt(3); n > 0; n--) {
            int x0 = 1 + rng.nextInt(SIZE - 6), z0 = 1 + rng.nextInt(SIZE - 6), x1 = Math.min(SIZE - 2, x0 + 2 + rng.nextInt(10)),
                    z1 = Math.min(SIZE - 2, z0 + 2 + rng.nextInt(10)), base = rng.nextInt(3) == 0 ? 1 : 0, roof = base + 3 + rng.nextInt(2);
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++) {
                    boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                    if (wall) for (int y = base; y < roof; y++) set(g, x, y, z, STONE);
                    else for (int y = base; y < roof; y++) set(g, x, y, z, Voxel.AIR);
                    set(g, x, roof, z, STONE);
                }
            // an inner wall with a doorway
            if (x1 - x0 >= 5 && rng.nextBoolean()) {
                int wx = x0 + 2 + rng.nextInt(x1 - x0 - 3), dz = z0 + 1 + rng.nextInt(Math.max(1, z1 - z0 - 1));
                for (int z = z0 + 1; z < z1; z++) for (int y = base; y < roof; y++) set(g, wx, y, z, STONE);
                for (int k = 0; k <= rng.nextInt(3); k++) for (int y = base; y < base + 2; y++) set(g, wx, y, Math.min(z1 - 1, dz + k), Voxel.AIR);
            }
            for (int cuts = rng.nextInt(5); cuts > 0; cuts--) {
                boolean alongX = rng.nextBoolean();
                int side = rng.nextBoolean() ? 0 : 1, len = alongX ? x1 - x0 - 1 : z1 - z0 - 1, wide = switch (rng.nextInt(6)) {
                    case 0 -> len;
                    case 1 -> 4;
                    default -> 1 + rng.nextInt(3);
                }, from = 1 + rng.nextInt(Math.max(1, len));
                byte put = rng.nextInt(5) == 0 ? AiRoomsTest.DOOR : Voxel.AIR;
                boolean window = rng.nextInt(6) == 0;
                for (int k = 0; k < wide; k++) {
                    int x = alongX ? Math.min(x1 - 1, x0 + from + k) : side == 0 ? x0 : x1, z = alongX ? side == 0 ? z0 : z1 : Math.min(z1 - 1, z0 + from + k);
                    if (window) set(g, x, base + 1, z, Voxel.AIR);
                    else for (int y = base; y < base + 2; y++) set(g, x, y, z, put);
                }
            }
        }
        // roofs on nothing or on posts, side by side at other heights: sheds, porches, crowns on trunks
        for (int n = rng.nextInt(6); n > 0; n--) {
            int x0 = rng.nextInt(SIZE), z0 = rng.nextInt(SIZE), w = 1 + rng.nextInt(6), d = 1 + rng.nextInt(6), y = 2 + rng.nextInt(4);
            for (int x = x0; x < x0 + w; x++) for (int z = z0; z < z0 + d; z++) set(g, x, y, z, STONE);
            if (rng.nextBoolean()) for (int h = 0; h < y; h++) set(g, x0 + w / 2, h, z0 + d / 2, STONE);
            if (rng.nextInt(3) == 0) {
                // a second roof beside it a block higher or lower: an edge with a step
                int y2 = y + (rng.nextBoolean() ? 1 : -1), w2 = 1 + rng.nextInt(4);
                for (int x = x0 + w; x < x0 + w + w2; x++) for (int z = z0; z < z0 + d; z++) set(g, x, y2, z, STONE);
            }
        }
        // blocks strewn about and holes knocked out, roofs among them
        for (int n = rng.nextInt(30); n > 0; n--) set(g, rng.nextInt(SIZE), rng.nextInt(6), rng.nextInt(SIZE), rng.nextBoolean() ? STONE : Voxel.AIR);
        return g.build();
    }
}
