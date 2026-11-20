package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.function.IntBinaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.PosLong;
import acup.gunfeel.Vec3d;
import acup.gunfeel.host.WorldView;
import acup.gunfeel.water.LiquidEvents.Liquid;
import acup.gunfeel.water.LiquidPath;

// the ground the staff's soldiers go over by the blocks, off the game: tops, liquids and the map faked
class StaffGroundTest {
    @AfterEach
    void stop() {
        AiThreads.stop();
    }

    /** a level of these tops (MOTION_BLOCKING_NO_LEAVES only), every chunk loaded but those west of x -64 */
    private static WorldView world(IntBinaryOperator top) {
        return (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[] {WorldView.class}, (p, m, a) -> switch (m.getName()) {
            case "height" -> {
                assertEquals(WorldView.HEIGHT_MOTION_BLOCKING_NO_LEAVES, a[2]);
                yield top.applyAsInt((int) a[0], (int) a[1]);
            }
            case "hasChunk" -> (int) a[0] >= -4;
            case "minY" -> -64;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    /** a river along z at x 2 to 4, its top at y 0: water two deep (y -1, -2) but a ford at z 6 (y -1 only); dry: none */
    private static LiquidPath.Fluids river(boolean ford) {
        return river(ford, true);
    }

    private static LiquidPath.Fluids river(boolean ford, boolean wet) {
        return new LiquidPath.Fluids() {
            @Override
            public LiquidPath.Cell at(int x, int y, int z) {
                if (!wet || x < 2 || x > 4) return null;
                if (y == -1 || y == -2 && !(ford && z == 6)) return new LiquidPath.Cell(Liquid.WATER, 1F, false);
                return null;
            }

            @Override
            public Vec3d flow(int x, int y, int z) {
                throw new AssertionError("not asked");
            }
        };
    }

    @Test
    void roundDeepWaterByTheFord() {
        long[] way = StaffGround.detour(world((x, z) -> 0), river(true), 0, 0, 0, 10.5, 0.5);
        assertNotNull(way);
        int px = 0, pz = 0;
        boolean forded = false;
        for (long c : way) {
            int x = (int) (c >> 32), z = (int) c;
            assertEquals(1, Math.abs(x - px) + Math.abs(z - pz), "a step a block: " + x + " " + z);
            // in the river only at the ford
            if (x >= 2 && x <= 4) {
                assertEquals(6, z, "in deep water at " + x + " " + z);
                forded = true;
            }
            px = x;
            pz = z;
        }
        assertTrue(forded, "over the river by the ford");
        assertTrue(px > 4, "on the far bank: " + px);
    }

    @Test
    void noFordNoWayNearer() {
        // the near bank is a block nearer at most: not worth a way round
        assertNull(StaffGround.detour(world((x, z) -> 0), river(false), 0, 0, 0, 10.5, 0.5));
    }

    @Test
    void notUpACliff() {
        // dry ground, a wall 4 high (more than CLIMB) across at x 2..3, its far side as low as the near
        assertNull(StaffGround.detour(world((x, z) -> x == 2 || x == 3 ? 4 : 0), river(false, false), 0, 0, 0, 10.5, 0.5));
        // 3 high (CLIMB): climbed, on to the near bank
        assertNotNull(StaffGround.detour(world((x, z) -> x == 2 || x == 3 ? 3 : 0), river(false, false), 0, 0, 0, 10.5, 0.5));
    }

    @Test
    void whereToStand() throws InterruptedException {
        TacticalMapBaseTest.FakeMap map = new TacticalMapBaseTest.FakeMap();
        WorldView flat = world((x, z) -> 0);
        // the map not built there yet: the top of the column itself
        assertEquals(PosLong.block(8, 0, 8), StaffGround.stand(flat, map, 8, 8, 0));
        map.want(8, 1, 8, 6, 2);
        for (int i = 0; i < 2000 && (map.queued() > 0 || map.inFlight() > 0); i++) {
            map.tick();
            Thread.sleep(2);
        }
        // built: the cell on the ground at its own height; far over it, still the ground's (the nearest across)
        assertEquals(PosLong.block(8, 0, 8), StaffGround.stand(flat, map, 8, 8, 0));
        assertEquals(PosLong.block(8, 0, 8), StaffGround.stand(flat, map, 8, 8, 12));
        // no chunk loaded round it: nothing
        assertEquals(Long.MIN_VALUE, StaffGround.stand(flat, map, -100, 8, 0));
    }
}
