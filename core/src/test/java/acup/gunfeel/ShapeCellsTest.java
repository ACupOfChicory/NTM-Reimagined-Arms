package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

// A block's shape without the game: its cells by x, y, z, the shell's flag, packed and read back.
class ShapeCellsTest {
    @Test
    void cells() {
        long[] bits = new long[65];
        int i = 3 + 16 * (5 + 16 * 7);
        bits[i >> 6] |= 1L << (i & 63);
        assertTrue(ShapeCells.cell(bits, 3, 5, 7));
        assertFalse(ShapeCells.cell(bits, 3, 5, 6));
        assertFalse(ShapeCells.shell(bits));
        bits[64] = 1L;
        assertTrue(ShapeCells.shell(bits));
        assertFalse(ShapeCells.shell(new long[64]));
    }

    @Test
    void packedAndReadBack() {
        long[] bits = new long[65];
        for (int j = 0; j < 64; j += 3) bits[j] = 0x0123_4567_89ab_cdefL * (j + 1);
        String z = ShapeCells.pack(bits);
        assertTrue(z.startsWith("z"));
        assertArrayEquals(bits, ShapeCells.unpack(z));
        // a shell: "!" before it
        long[] shell = bits.clone();
        shell[64] = 1L;
        assertTrue(ShapeCells.pack(shell).startsWith("!z"));
        assertArrayEquals(shell, ShapeCells.unpack(ShapeCells.pack(shell)));
        // the old form: 1024 hex digits
        StringBuilder hex = new StringBuilder();
        for (int j = 0; j < 64; j++) hex.append(String.format("%016x", bits[j]));
        assertArrayEquals(bits, ShapeCells.unpack(hex.toString()));
        // with exact faces after the cells
        var face = new ModelSurfaceBase.Face(new double[] {0, 0, 0.5, 1, 0, 0.5, 0, 1, 0.5}, new double[6], 1, 1, new long[] {1});
        long[] model = ModelSurfaceBase.attach(bits, List.of(face));
        assertArrayEquals(model, ShapeCells.unpack(ShapeCells.pack(model)));
    }

    @Test
    void brokenTextIsNothing() {
        assertNull(ShapeCells.unpack("nonsense"));
        assertNull(ShapeCells.unpack("z!!!"));
        long[] model = ModelSurfaceBase.attach(new long[65], List.of());
        model[65] = 3;
        assertNull(ShapeCells.unpack(ShapeCells.pack(model)));
        assertEquals(65, ShapeCells.unpack(ShapeCells.pack(new long[65])).length);
    }
}
