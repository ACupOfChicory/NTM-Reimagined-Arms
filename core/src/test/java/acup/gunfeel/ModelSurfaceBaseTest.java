package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import acup.gunfeel.ModelSurfaceBase.Face;

// A shape's exact faces without the game: written after the cells and read back, checked, cut to the block.
class ModelSurfaceBaseTest {
    // a triangle in the block's own coordinates at z 0.5, 2×2 pixels, the one at (1, 1) clear
    private static Face face(boolean glass) {
        return new Face(new double[] {0.1, 0.1, 0.5, 0.9, 0.1, 0.5, 0.1, 0.9, 0.5}, new double[] {0, 0, 2, 0, 0, 2}, 2, 2, new long[] {0b0111}, glass);
    }

    @Test
    void facesAfterTheCells() {
        long[] cells = new long[65];
        cells[3] = 0x55L;
        assertFalse(ModelSurfaceBase.present(cells));
        assertTrue(ModelSurfaceBase.valid(cells));
        assertTrue(ModelSurfaceBase.faces(cells).isEmpty());
        long[] shape = ModelSurfaceBase.attach(cells, List.of(face(false), face(true)));
        assertTrue(ModelSurfaceBase.present(shape));
        assertTrue(ModelSurfaceBase.valid(shape));
        assertEquals(66 + 2 * (16 + 1), shape.length);
        assertEquals(0x55L, shape[3]);
        var back = ModelSurfaceBase.faces(shape);
        assertEquals(2, back.size());
        for (int i = 0; i < 2; i++) {
            assertArrayEquals(face(false).p(), back.get(i).p());
            assertArrayEquals(face(false).uv(), back.get(i).uv());
            assertEquals(2, back.get(i).width());
            assertEquals(2, back.get(i).height());
            assertArrayEquals(new long[] {0b0111}, back.get(i).alpha());
        }
        assertFalse(back.get(0).glass());
        assertTrue(back.get(1).glass());
        // the size word: width above, height below, glass in the top bit
        assertEquals(2, ModelSurfaceBase.width(2L << 32 | 3 | ModelSurfaceBase.GLASS));
    }

    @Test
    void brokenShapesAreNotValid() {
        long[] shape = ModelSurfaceBase.attach(new long[65], List.of(face(false)));
        assertFalse(ModelSurfaceBase.valid(java.util.Arrays.copyOf(shape, shape.length - 1)));
        assertFalse(ModelSurfaceBase.valid(java.util.Arrays.copyOf(shape, shape.length + 1)));
        long[] nan = shape.clone();
        nan[66] = Double.doubleToLongBits(Double.NaN);
        assertFalse(ModelSurfaceBase.valid(nan));
        long[] count = shape.clone();
        count[65] = -1;
        assertFalse(ModelSurfaceBase.valid(count));
        assertFalse(ModelSurfaceBase.valid(new long[64]));
    }

    @Test
    void cropToTheBlock() {
        // all inside: kept as it is
        Face f = face(false);
        assertSame(f, ModelSurfaceBase.crop(f));
        // out of the block: nothing
        Face out = new Face(new double[] {2.1, 0.1, 0.5, 2.9, 0.1, 0.5, 2.1, 0.9, 0.5}, f.uv(), 2, 2, f.alpha(), false);
        assertNull(ModelSurfaceBase.crop(out));
        // no pixel shown: nothing
        assertNull(ModelSurfaceBase.crop(new Face(f.p(), f.uv(), 2, 2, new long[] {0}, false)));
        // every pixel shown: one pixel, glass kept
        Face full = ModelSurfaceBase.crop(new Face(f.p(), f.uv(), 2, 2, new long[] {0b1111}, true));
        assertNotNull(full);
        assertEquals(1, full.width());
        assertTrue(full.glass());
    }
}
