package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

class ShapePackTest {
    private static long[] cells(long seed) {
        long[] s = new long[65];
        java.util.Random r = new java.util.Random(seed);
        for (int i = 0; i < 64; i++) s[i] = r.nextLong();
        return s;
    }

    @Test
    void roundTripsByMachine() throws Exception {
        Map<String, long[]> in = new TreeMap<>();
        String door = "hbm:cargo_door[facing=north,open=false,skin=0]", lift = "hbm:cargo_elevator[facing=north]";
        for (int x = -2; x <= 2; x++) for (int y = 0; y < 4; y++) in.put(door + "@" + x + "," + y + ",0", cells(x * 7 + y));
        // alike blocks are written once
        in.put(lift + "@0,0,0", cells(1));
        in.put(lift + "@1,0,0", cells(1));
        in.put(lift + "@0,1,0", new long[65]);
        // a key without a place is no machine's
        in.put("hbm:steel_beam[axis=y]", cells(9));
        for (int codec : new int[] {ShapePack.DEFLATE, ShapePack.XZ}) {
            byte[] file = ShapePack.write(in, "1.0.3", codec);
            assertEquals("1.0.3", ShapePack.ntmOf(file));
            Map<String, long[]> out = ShapePack.read(file);
            assertEquals(in.size() - 1, out.size());
            for (var e : out.entrySet()) assertArrayEquals(in.get(e.getKey()), e.getValue(), e.getKey());
        }
    }

    // a shape with faces comes back with its corners on 1/16384 of a block and its texture coordinates on 1/65536
    @Test
    void facesComeBackOnTheFileSteps() throws Exception {
        java.util.Random r = new java.util.Random(5);
        Map<String, long[]> in = new TreeMap<>();
        long[] mask = {r.nextLong(), r.nextLong()};
        for (int b = 0; b < 4; b++) {
            java.util.List<ModelSurface.Face> faces = new java.util.ArrayList<>();
            for (int f = 0; f < 3; f++) {
                double[] p = new double[9], uv = new double[6];
                for (int k = 0; k < 9; k++) p[k] = r.nextDouble() * 3 - 1;
                for (int k = 0; k < 6; k++) uv[k] = r.nextDouble();
                // the same mask in several faces is written once
                faces.add(new ModelSurface.Face(p, uv, 10, 12, f == 2 ? new long[] {r.nextLong(), r.nextLong()} : mask, f == 1));
            }
            in.put("hbm:fusion_torus[facing=north]@" + b + ",0,0", ModelSurface.attach(cells(b), faces));
        }
        in.put("hbm:fusion_torus[facing=north]@9,0,0", ModelSurface.attach(cells(3), java.util.List.of()));
        Map<String, long[]> out = ShapePack.read(ShapePack.write(in, "x", ShapePack.XZ));
        assertEquals(in.size(), out.size());
        for (var e : in.entrySet()) {
            long[] got = out.get(e.getKey());
            assertArrayEquals(ShapePack.quantized(e.getValue()), got, e.getKey());
            var a = ModelSurface.faces(e.getValue());
            var b = ModelSurface.faces(got);
            for (int f = 0; f < a.size(); f++) for (int k = 0; k < 9; k++)
                assertEquals(a.get(f).p()[k], b.get(f).p()[k], 0.5 / ShapePack.POS);
        }
    }

    @Test
    void refusesOtherFiles() {
        assertThrows(java.io.IOException.class, () -> ShapePack.read(new byte[] {'N', 'G', 'S', 'P', 1, 0, 0}));
    }
}
