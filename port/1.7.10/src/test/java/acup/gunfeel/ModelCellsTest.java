package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

// The 1.7.10 table ShapesScenario made (ModelCells): every listed block is in it, and a bullet goes through the clear
// pixels of bars, a grate and a trapdoor and meets their wires, and a ladder's rungs only where they are drawn; doors
// and trapdoors are shells (decided where the bullet comes in).
class ModelCellsTest {
    private static long[] get(String key) {
        long[] b = ModelCells.table().get(key);
        assertNotNull(b, key);
        return b;
    }

    // a bullet along +z through the block at (0, 0, 0), at (x, y) in it
    private static BlockRay.Hit along(long[] cells, double x, double y) {
        BlockRay.Hit in = BlockRay.clip(new double[] {0, 0, 0, 1, 1, 1}, 1, x, y, -1, x, y, 2, 0, 0, 0);
        return FenceCells.march(cells, in, 0, 0, 3, 64);
    }

    // a bullet straight down through the block, at (x, z) in it
    private static BlockRay.Hit down(long[] cells, double x, double z) {
        BlockRay.Hit in = BlockRay.clip(new double[] {0, 0, 0, 1, 1, 1}, 1, x, 2, z, x, -1, z, 0, 0, 0);
        return FenceCells.march(cells, in, 0, -3, 0, 64);
    }

    @Test
    void everyListedBlockIsThere() {
        Map<String, long[]> t = ModelCells.table();
        for (String id : ShapesScenario.BLOCKS) {
            boolean found = false;
            for (String k : t.keySet()) found |= k.startsWith(id + "#");
            assertTrue(found, id);
        }
        // all 16 joins of the bars
        for (int j = 0; j < 16; j++) get("minecraft:iron_bars#0/" + ((j & 8) != 0 ? 1 : 0) + ((j & 4) != 0 ? 1 : 0) + ((j & 2) != 0 ? 1 : 0) + ((j & 1) != 0 ? 1 : 0));
    }

    @Test
    void lonelyBarsAreACrossWithGaps() {
        // a lone pane of bars is drawn as a cross on 1.7.10: both planes through the middle
        long[] bars = get("minecraft:iron_bars#0/0000");
        BlockRay.Hit wire = along(bars, 2.5 / 16, .5);
        assertNotNull(wire);
        assertEquals(8 / 16.0, wire.az, 1e-9);
        // between the bars the bullet flies through the plane across it, and meets the plane along it in the middle only
        assertNull(along(bars, 5.5 / 16, 5.5 / 16));
        // joined east and west only: no plane along z
        long[] row = get("minecraft:iron_bars#0/0101");
        assertNotNull(along(row, 2.5 / 16, .5));
        assertNotNull(down(bars, 8.5 / 16, 2.5 / 16));
        assertNull(down(row, 8.5 / 16, 2.5 / 16));
    }

    @Test
    void aGrateAndATrapdoorHaveHoles() {
        long[] grate = get("hbm:tile.steel_grate#0");
        assertNull(down(grate, 1.5 / 16, 1.5 / 16));
        assertNotNull(down(grate, .5 / 16, .5 / 16));
        long[] trap = get("minecraft:trapdoor#0");
        assertNull(down(trap, 4.5 / 16, 4.5 / 16));
        assertNotNull(down(trap, 1.5 / 16, 1.5 / 16));
    }

    @Test
    void aLadderIsOneCellThinAndItsRailsAreSolid() {
        long[] ladder = get("minecraft:ladder#2");
        BlockRay.Hit rail = along(ladder, 2.5 / 16, .5);
        assertNotNull(rail);
        assertEquals(15 / 16.0, rail.az, 1e-9);
        assertNull(along(ladder, 8.5 / 16, 7.5 / 16));
        assertNotNull(along(ladder, 8.5 / 16, 5.5 / 16));
        assertFalse(ModelCells.empty(ladder));
    }

    @Test
    void doorsAndTrapdoorsAreShellsBarsAndLaddersAreNot() {
        assertTrue(ModelCells.shell(get("minecraft:wooden_door#9")));
        assertTrue(ModelCells.shell(get("minecraft:iron_door#3")));
        assertTrue(ModelCells.shell(get("minecraft:trapdoor#0")));
        assertFalse(ModelCells.shell(get("minecraft:iron_bars#0/0000")));
        assertFalse(ModelCells.shell(get("minecraft:ladder#2")));
        assertFalse(ModelCells.shell(get("hbm:tile.steel_scaffold#0")));
        // a door's top has two windows: clear on the face the bullet comes in through
        long[] top = get("minecraft:wooden_door#9");
        assertFalse(FenceCells.cell(top, 4, 11, 0));
        assertTrue(FenceCells.cell(top, 4, 14, 0));
    }

    @Test
    void theServersShapesCountWhileConnected() {
        long[] ladder = get("minecraft:ladder#2");
        java.util.Map<String, String> sent = new java.util.HashMap<String, String>();
        sent.put("othermod:grate#3", ModelCells.pack(ladder));
        sent.put("not a key", ModelCells.pack(ladder));
        try {
            ModelCells.useRemote(sent);
            assertNotNull(ModelCells.table().get("othermod:grate#3"));
            assertNull(ModelCells.table().get("not a key"));
            // the jar's stay
            assertNotNull(ModelCells.table().get("minecraft:ladder#2"));
        } finally {
            ModelCells.useRemote(null);
        }
        assertNull(ModelCells.table().get("othermod:grate#3"));
    }

    @Test
    void packIsWhatUnpackReads() {
        long[] door = get("minecraft:wooden_door#9");
        assertTrue(ModelCells.shell(ModelCells.unpack(ModelCells.pack(door))));
        long[] bits = get("minecraft:ladder#2");
        // with its exact faces (ModelSurface): the "f" form
        assertTrue(ModelSurface.present(bits));
        assertArrayEquals(bits, ModelCells.unpack(ModelCells.pack(bits)));
        // cells alone: FenceCells' "z" form
        long[] cells = java.util.Arrays.copyOf(bits, 65);
        long[] back = FenceCells.unpack(ModelCells.pack(cells));
        for (int i = 0; i < 64; i++) assertEquals(cells[i], back[i]);
    }
}
