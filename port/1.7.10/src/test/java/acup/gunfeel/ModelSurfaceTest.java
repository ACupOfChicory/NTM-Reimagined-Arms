package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

// port: 26.2's ModelSurfaceTest (e284339) for the port's ModelSurface (BlockRay.Hit, coordinates as doubles), its "f"
// form in the shape table (ModelCells.pack), and MachineFaces' file and turned facings
class ModelSurfaceTest {
    // a face at x = 1/2 turned to -x; its right half see-through
    private static ModelSurface.Face cutout() {
        return new ModelSurface.Face(new double[] {.5, 0, 0, .5, 0, 1, .5, 1, 0}, new double[] {0, 0, 2, 0, 0, 2}, 2, 2, new long[] {0b0101});
    }

    private static long[] shape(ModelSurface.Face... faces) {
        long[] cells = new long[65];
        Arrays.fill(cells, 0, 64, -1L); // even solid cells do not close a clear pixel
        return ModelSurface.attach(cells, Arrays.asList(faces));
    }

    @Test
    void clearPixelDoesNotStopBulletEvenInAnOccupiedCell() {
        long[] model = shape(cutout());
        assertNull(ModelSurface.hit(model, 0, 0, 0, 0, .1, .7, 1, .1, .7, false));
        assertNotNull(ModelSurface.hit(model, 0, 0, 0, 0, .1, .2, 1, .1, .2, false));
    }

    @Test
    void rayBesideTriangleIsAGapAndCannotSnap() {
        assertNull(ModelSurface.hit(shape(cutout()), 0, 0, 0, 0, .8, .8, 1, .8, .8, false));
    }

    @Test
    void exactEntryAndExitAndSegmentEnd() {
        ModelSurface.Face back = new ModelSurface.Face(new double[] {.6, 0, 0, .6, 1, 0, .6, 0, 1}, new double[6], 1, 1, new long[] {1});
        long[] model = shape(cutout(), back);
        BlockRay.Hit entry = ModelSurface.hit(model, 0, 0, 0, 0, .1, .2, 1, .1, .2, false);
        assertNotNull(entry);
        assertEquals(.5, entry.ax, 1e-9);
        assertEquals(BlockRay.WEST, entry.face);
        BlockRay.Hit exit = ModelSurface.hit(model, 0, 0, 0, entry.ax, entry.ay, entry.az, 1, .1, .2, true);
        assertNotNull(exit);
        assertEquals(.1, exit.distance(entry.ax, entry.ay, entry.az), 1e-9);
        assertNull(ModelSurface.hit(model, 0, 0, 0, 0, .1, .2, .4, .1, .2, false));
        assertNull(ModelSurface.hit(model, 0, 0, 0, entry.ax, entry.ay, entry.az, 1, .1, .2, false));
        assertNotNull(ModelSurface.hit(model, 0, 0, 0, 1, .1, .2, 0, .1, .2, false));
    }

    @Test
    void coordinatesAreLocalToBlock() {
        BlockRay.Hit hit = ModelSurface.hit(shape(cutout()), -152, -59, 85, -152, -58.9, 85.2, -151, -58.9, 85.2, false);
        assertNotNull(hit);
        assertEquals(-151.5, hit.ax, 1e-9);
        assertEquals(-152, hit.x);
        assertEquals(85, hit.z);
    }

    @Test
    void glassFaceGivesAGlassHit() {
        ModelSurface.Face pane = new ModelSurface.Face(new double[] {.5, 0, 0, .5, 0, 1, .5, 1, 0}, new double[6], 1, 1, new long[] {1}, true);
        BlockRay.Hit hit = ModelSurface.hit(shape(pane), 0, 0, 0, 0, .1, .2, 1, .1, .2, false);
        assertTrue(hit instanceof BlockRay.GlassHit);
        // leaving, a window is not the far side of a part
        assertNull(ModelSurface.hit(shape(pane), 0, 0, 0, 1, .1, .2, 0, .1, .2, true));
    }

    @Test
    void exactGeometrySurvivesPacking() {
        long[] model = shape(cutout());
        String packed = ModelCells.pack(model);
        assertTrue(packed.startsWith("f"));
        assertArrayEquals(model, ModelCells.unpack(packed));
        long[] legacy = new long[65];
        legacy[1] = 123;
        legacy[64] = 1;
        assertArrayEquals(legacy, ModelCells.unpack(ModelCells.pack(legacy)));
    }

    @Test
    void facesCrossingSeveralCellsOnlyHitInsideTheTestedCell() {
        ModelSurface.Face far = new ModelSurface.Face(new double[] {1.5, 0, 0, 1.5, 0, 1, 1.5, 1, 0}, new double[6], 1, 1, new long[] {1});
        assertNotNull(ModelSurface.hit(shape(far), 0, 0, 0, 0, .1, .2, 2, .1, .2, false));
        assertNull(ModelSurface.hit(shape(far), 0, 0, 0, 0, .1, .2, 2, .1, .2, false, true));
        assertTrue(ModelSurface.overlaps(far, 1, 0, 0));
        assertFalse(ModelSurface.overlaps(far, 0, 0, 0));
    }

    @Test
    void malformedGeometryIsRejected() {
        long[] model = shape(cutout());
        assertTrue(ModelSurface.valid(model));
        assertFalse(ModelSurface.valid(Arrays.copyOf(model, model.length - 1)));
        model[65] = Long.MAX_VALUE;
        assertNull(ModelCells.unpack(ModelCells.pack(model)));
    }

    // a machine of two triangles over the core and the block east of it; texture 0: 2x1, left pixel shown
    private static MachineFaces.Model machine() {
        return new MachineFaces.Model(new int[] {0, -1},
                new float[] {.2F, 0, .5F, .2F, 1, .5F, 1.8F, 0, .5F, 0, 0, .9F, 0, 1, .9F, 1, 0, .9F},
                new float[] {0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0});
    }

    @Test
    void machineFileKeepsATurnedFacingAsItsTurn() throws Exception {
        MachineFaces.Model north = machine();
        Map<String, MachineFaces.Model> models = new HashMap<String, MachineFaces.Model>();
        models.put("hbm:tile.m/2", north);
        models.put("hbm:tile.m/5", north.turned(1));
        List<MachineFaces.Tex> tex = Collections.singletonList(new MachineFaces.Tex("hbm:textures/models/m.png", 2, 1, new long[] {1}));
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        MachineFaces.write(bytes, tex, models);
        java.io.ByteArrayOutputStream one = new java.io.ByteArrayOutputStream();
        MachineFaces.write(one, tex, Collections.singletonMap("hbm:tile.m/2", north));
        // the turn is a reference, not the triangles again
        assertTrue(bytes.size() < one.size() + 40, bytes.size() + " vs " + one.size());
        List<MachineFaces.Tex> readTex = new java.util.ArrayList<MachineFaces.Tex>();
        Map<String, MachineFaces.Model> read = new HashMap<String, MachineFaces.Model>();
        MachineFaces.read(new java.io.DataInputStream(new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))),
                readTex, read);
        assertEquals(1, readTex.size());
        assertTrue(MachineFaces.same(north, read.get("hbm:tile.m/2")));
        assertTrue(MachineFaces.same(north.turned(1), read.get("hbm:tile.m/5")));
        assertTrue(MachineFaces.same(north, north.turned(4)));
    }

    @Test
    void machineFaceMasksItsTexturePixels() {
        MachineFaces.Tex tex = new MachineFaces.Tex("hbm:textures/models/m.png", 2, 1, new long[] {1});
        double[] tri = {0, 0, .5, 0, 1, .5, 1, 0, .5};
        ModelSurface.Face shown = MachineFaces.face(tri, tex, new double[] {0, 0, 1}, new double[] {0, 1, 0}, false);
        assertEquals(2, shown.width);
        assertEquals(1L, shown.alpha[0]);
        ModelSurface.Face window = MachineFaces.face(tri, tex, new double[] {0, 0, 1}, new double[] {0, 1, 0}, true);
        assertEquals(2L, window.alpha[0]);
        assertTrue(window.glass);
        // all shown: one pixel
        assertEquals(1, MachineFaces.face(tri, tex, new double[] {0, 0, .4}, new double[] {0, 1, 0}, false).width);
    }
}
