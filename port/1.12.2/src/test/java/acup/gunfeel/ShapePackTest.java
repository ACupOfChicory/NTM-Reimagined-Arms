package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ShapePackTest {
    private static long[] cells(long seed, boolean shell) {
        long[] b = new long[65];
        java.util.Random r = new java.util.Random(seed);
        for (int i = 0; i < 64; i++) b[i] = r.nextLong();
        if (shell) b[64] = 1L;
        return b;
    }

    @Test
    void shapesComeBackWithTheirKeysAndTheNtmVersion() throws Exception {
        Map<String, long[]> t = new HashMap<String, long[]>();
        long[] shared = cells(1, false);
        t.put("minecraft:iron_bars#0/1010", shared);
        t.put("minecraft:iron_bars#0/0101", shared);
        t.put("hbm:tile.machine_press#machine/2/0,1,0", cells(2, false));
        t.put("minecraft:wooden_door#3", cells(3, true));
        for (int codec : new int[] {ShapePack.DEFLATE, ShapePack.XZ}) {
            byte[] packed = ShapePack.wrap(ShapePack.shapes(t), "1.0.27 BETA (5863)", codec);
            ShapePack.Read r = ShapePack.unwrap(new ByteArrayInputStream(packed));
            assertEquals("1.0.27 BETA (5863)", r.ntm);
            Map<String, long[]> back = ShapePack.shapes(r.raw);
            assertEquals(t.keySet(), back.keySet());
            for (Map.Entry<String, long[]> e : t.entrySet()) assertArrayEquals(e.getValue(), back.get(e.getKey()), e.getKey());
            assertSame(back.get("minecraft:iron_bars#0/1010"), back.get("minecraft:iron_bars#0/0101"));
            assertEquals(true, ModelCells.shell(back.get("minecraft:wooden_door#3")));
        }
    }

    @Test
    void notAPackIsRefused() {
        assertThrows(java.io.IOException.class, () -> ShapePack.unwrap(new ByteArrayInputStream("@0 = z...".getBytes("UTF-8"))));
    }

    @Test
    void facesComeBackOnTheirGrid() throws Exception {
        MachineFaces.Model m = new MachineFaces.Model(new int[] {0, -1},
                new float[] {.2F, 0, .5F, .2F, 1, .5F, 1.8F, 0, .5F, 0, 0, .9F, 0, 1, .9F, 1, 0, .9F},
                new float[] {0, 0, 0, .3F, 1, 0, 0, 0, 0, 0, 0, 0});
        Map<String, MachineFaces.Model> models = new HashMap<String, MachineFaces.Model>();
        models.put("hbm:tile.m/2", m);
        models.put("hbm:tile.m/3", m.turned(2));
        java.util.List<MachineFaces.Tex> tex = java.util.Collections.singletonList(new MachineFaces.Tex("hbm:textures/models/m.png", 2, 1, new long[] {1}));
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        MachineFaces.write(bytes, tex, models);
        java.util.List<MachineFaces.Tex> readTex = new java.util.ArrayList<MachineFaces.Tex>();
        Map<String, MachineFaces.Model> read = new HashMap<String, MachineFaces.Model>();
        ShapePack.faces(ShapePack.unwrap(new ByteArrayInputStream(bytes.toByteArray())).raw, readTex, read);
        assertEquals("hbm:textures/models/m.png", readTex.get(0).name);
        for (String k : models.keySet()) {
            MachineFaces.Model a = models.get(k), b = read.get(k);
            assertArrayEquals(a.tex, b.tex);
            for (int i = 0; i < a.p.length; i++) assertEquals(a.p[i], b.p[i], 0.5 / ShapePack.POS, k);
            for (int i = 0; i < a.uv.length; i++) assertEquals(a.uv[i], b.uv[i], 0.5 / ShapePack.UV, k);
        }
    }

    @Test
    void takenShapesReadBackFromTheConfigText() throws Exception {
        Map<String, String> lines = new java.util.TreeMap<String, String>();
        long[] cells = cells(4, false);
        lines.put("othermod:tile.big_press#machine/12/-1,0,2", ModelCells.pack(cells));
        lines.put("othermod:tile.big_press#machine/12/-1,0,2/glass", ModelCells.pack(cells(5, false)));
        java.util.Properties p = new java.util.Properties();
        p.load(new java.io.StringReader(ModelCells.configText(lines, "1.0.27 BETA (5863)")));
        assertEquals("1.0.27 BETA (5863)", p.getProperty(ModelCells.NTM_KEY));
        assertEquals(3, p.size());
        assertArrayEquals(cells, ModelCells.unpack(p.getProperty("othermod:tile.big_press#machine/12/-1,0,2")));
    }
}
