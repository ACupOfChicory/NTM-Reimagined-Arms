package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import acup.gunfeel.water.WaterEvents.Liquid;
import acup.gunfeel.water.WaterPath;
import acup.gunfeel.water.WaterPath.Cell;
import net.minecraft.world.phys.Vec3;

class BeamWaterTest {
    private static final BeamWater.Settings S = BeamWater.Settings.DEFAULT;

    // a level row of water cells x0..x1 at y 0, z 0 (full cells), the rest air; `falling`: a falling stream
    private static WaterPath.Fluids row(int x0, int x1, boolean falling) {
        return new WaterPath.Fluids() {
            @Override
            public Cell at(int x, int y, int z) {
                return y == 0 && z == 0 && x >= x0 && x <= x1 ? new Cell(Liquid.WATER, 1F, falling) : null;
            }

            @Override
            public Vec3 flow(int x, int y, int z) {
                return Vec3.ZERO;
            }
        };
    }

    private static BeamWater along(WaterPath.Fluids w, double from, double to) {
        return BeamWater.of(WaterPath.water(w, new Vec3(from, 0.5, 0.5), new Vec3(to, 0.5, 0.5)), S);
    }

    @Test
    void noWaterKeepsAll() {
        BeamWater b = along(row(100, 101, false), 0, 50);
        assertSame(BeamWater.NONE, b);
        assertEquals(1.0, b.k(40), 0);
        assertEquals(Double.POSITIVE_INFINITY, b.gone());
    }

    @Test
    void sharesAfterOneThreeBlocks() {
        // water from x 2 to x 10 (8 blocks); the beam starts at x 0
        BeamWater b = along(row(2, 9, false), 0, 30);
        assertEquals(1.0, b.k(2), 1e-9);
        assertEquals(Math.exp(-0.5), b.k(3), 1e-9); // 0.61
        assertEquals(Math.exp(-1.5), b.k(5), 1e-9); // 0.22
        // gone after L·ln(1/fade) = 2·ln 10 = 4.61 blocks of water
        assertEquals(2 + 2 * Math.log(10), b.gone(), 1e-9);
    }

    @Test
    void shallowWaterIsCrossed() {
        // 3 blocks of water and air behind: 0.22 of the damage goes on unchanged
        BeamWater b = along(row(2, 4, false), 0, 30);
        assertEquals(Double.POSITIVE_INFINITY, b.gone());
        assertEquals(Math.exp(-1.5), b.k(20), 1e-9);
    }

    @Test
    void twoPoolsAddUp() {
        // 2 blocks, air, 2 blocks: 4 blocks of water in all, under the 4.61 it takes
        WaterPath.Fluids w = new WaterPath.Fluids() {
            @Override
            public Cell at(int x, int y, int z) {
                boolean in = y == 0 && z == 0 && (x == 2 || x == 3 || x == 6 || x == 7);
                return in ? new Cell(Liquid.WATER, 1F, false) : null;
            }

            @Override
            public Vec3 flow(int x, int y, int z) {
                return Vec3.ZERO;
            }
        };
        BeamWater b = along(w, 0, 20);
        assertEquals(Math.exp(-1.0), b.k(5), 1e-9);
        assertEquals(Math.exp(-2.0), b.k(12), 1e-9);
        assertEquals(Double.POSITIVE_INFINITY, b.gone());
    }

    @Test
    void fallingStreamIsThin() {
        BeamWater b = along(row(2, 9, true), 0, 30);
        assertEquals(Math.exp(-0.15 * 8 / 2), b.k(20), 1e-9);
        assertEquals(Double.POSITIVE_INFINITY, b.gone());
    }

    @Test
    void lavaIsNotWater() {
        WaterPath.Fluids w = new WaterPath.Fluids() {
            @Override
            public Cell at(int x, int y, int z) {
                return y == 0 && z == 0 && x >= 2 && x <= 9 ? new Cell(Liquid.LAVA, 1F, false) : null;
            }

            @Override
            public Vec3 flow(int x, int y, int z) {
                return Vec3.ZERO;
            }
        };
        assertSame(BeamWater.NONE, along(w, 0, 30));
    }

    @Test
    void offKeepsAll() {
        BeamWater b = BeamWater.of(List.of(new double[] {0, 10, 1}), new BeamWater.Settings(false, 2, 0.1));
        assertSame(BeamWater.NONE, b);
    }

    @Test
    void settingsMove() {
        // a longer L and a lower fade: further
        BeamWater b = BeamWater.of(List.of(new double[] {0, 100, 1}), new BeamWater.Settings(true, 4, 0.01));
        assertEquals(4 * Math.log(100), b.gone(), 1e-9);
        assertEquals(Math.exp(-0.25), b.k(1), 1e-9);
    }
}
