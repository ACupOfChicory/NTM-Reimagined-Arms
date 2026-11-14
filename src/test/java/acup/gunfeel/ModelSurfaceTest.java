package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

class ModelSurfaceTest {
    // Грань при x=1/2, направленная к -x; правая половина прозрачна.
    private static ModelSurface.Face cutout() {
        return new ModelSurface.Face(new double[] {.5, 0, 0, .5, 0, 1, .5, 1, 0},
                new double[] {0, 0, 2, 0, 0, 2}, 2, 2, new long[] {0b0101});
    }

    private static long[] shape(ModelSurface.Face... faces) {
        long[] cells = new long[65];
        java.util.Arrays.fill(cells, 0, 64, -1L); // Даже сплошная сетка не закрывает прозрачный пиксель.
        return ModelSurface.attach(cells, List.of(faces));
    }

    @Test
    void clearPixelDoesNotStopBulletEvenInAnOccupiedCell() {
        long[] model = shape(cutout());
        assertNull(ModelSurface.hit(model, BlockPos.ZERO, new Vec3(0, .1, .7), new Vec3(1, .1, .7), false));
        assertNotNull(ModelSurface.hit(model, BlockPos.ZERO, new Vec3(0, .1, .2), new Vec3(1, .1, .2), false));
    }

    @Test
    void rayBesideTriangleIsAGapAndCannotSnap() {
        assertNull(ModelSurface.hit(shape(cutout()), BlockPos.ZERO, new Vec3(0, .8, .8), new Vec3(1, .8, .8), false));
    }

    @Test
    void exactEntryAndExitAndSegmentEnd() {
        ModelSurface.Face front = cutout();
        ModelSurface.Face back = new ModelSurface.Face(new double[] {.6, 0, 0, .6, 1, 0, .6, 0, 1},
                new double[6], 1, 1, new long[] {1});
        long[] model = shape(front, back);
        Vec3 from = new Vec3(0, .1, .2), to = new Vec3(1, .1, .2);
        var entry = ModelSurface.hit(model, BlockPos.ZERO, from, to, false);
        assertNotNull(entry);
        assertEquals(.5, entry.getLocation().x, 1e-9);
        var exit = ModelSurface.hit(model, BlockPos.ZERO, entry.getLocation(), to, true);
        assertNotNull(exit);
        assertEquals(.1, exit.getLocation().distanceTo(entry.getLocation()), 1e-9);
        assertNull(ModelSurface.hit(model, BlockPos.ZERO, from, new Vec3(.4, .1, .2), false));
        assertNull(ModelSurface.hit(model, BlockPos.ZERO, entry.getLocation(), to, false));
        assertNotNull(ModelSurface.hit(model, BlockPos.ZERO, to, from, false));
    }

    @Test
    void coordinatesAreLocalToBlock() {
        BlockPos pos = new BlockPos(-152, -59, 85);
        var hit = ModelSurface.hit(shape(cutout()), pos, new Vec3(-152, -58.9, 85.2), new Vec3(-151, -58.9, 85.2), false);
        assertNotNull(hit);
        assertEquals(-151.5, hit.getLocation().x, 1e-9);
        assertEquals(pos, hit.getBlockPos());
    }

    @Test
    void exactGeometrySurvivesPackingAndServerSettings() throws Exception {
        long[] model = shape(cutout());
        String packed = PassableClip.pack(model);
        assertArrayEquals(model, PassableClip.unpack(packed));
        var sent = new ServerSettings.Snapshot("", java.util.Map.of(), java.util.Map.of("test:model", packed), "");
        var received = ServerSettings.decode(ServerSettings.encode(sent));
        assertArrayEquals(model, PassableClip.unpack(received.shapes().get("test:model")));
        long[] legacy = new long[65];
        legacy[1] = 123;
        legacy[64] = 1;
        assertArrayEquals(legacy, PassableClip.unpack(PassableClip.pack(legacy)));
        // Старый читатель получает прежние 512 байт сетки в начале сжатого потока.
        try (var in = new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(packed.substring(1))))) {
            long[] old = new long[64];
            java.nio.ByteBuffer.wrap(in.readNBytes(512)).asLongBuffer().get(old);
            assertArrayEquals(java.util.Arrays.copyOf(model, 64), old);
        }
    }

    @Test
    void multiblockRotationPreservesFacesAndPixels() {
        long[] original = shape(cutout());
        var rotated = original;
        for (int i = 0; i < 4; i++) rotated = ModelSurface.rotate(rotated, net.minecraft.world.level.block.Rotation.CLOCKWISE_90);
        assertArrayEquals(original, rotated);
        for (var r : net.minecraft.world.level.block.Rotation.values()) {
            long[] model = ModelSurface.rotate(original, r);
            assertTrue(ModelSurface.valid(model));
            assertArrayEquals(model, PassableClip.unpack(PassableClip.pack(model)));
        }
    }

    @Test
    void facesCrossingSeveralCellsOnlyHitInsideTheTestedCell() {
        ModelSurface.Face far = new ModelSurface.Face(new double[] {1.5, 0, 0, 1.5, 0, 1, 1.5, 1, 0},
                new double[6], 1, 1, new long[] {1});
        Vec3 from = new Vec3(0, .1, .2), to = new Vec3(2, .1, .2);
        assertNotNull(ModelSurface.hit(shape(far), BlockPos.ZERO, from, to, false));
        assertNull(ModelSurface.hit(shape(far), BlockPos.ZERO, from, to, false, true));
        assertTrue(ModelSurface.overlaps(far, new BlockPos(1, 0, 0)));
        assertFalse(ModelSurface.overlaps(far, BlockPos.ZERO));
    }

    @Test
    void shellEntryOnACellBoundaryUsesTheForwardCell() {
        long[] cells = new long[65];
        int k = 2 + 16 * (0 + 16 * 2);
        cells[k >> 6] |= 1L << (k & 63);
        var entry = new net.minecraft.world.phys.BlockHitResult(new Vec3(3.0 / 16, .01, 2.5 / 16),
                net.minecraft.core.Direction.EAST, BlockPos.ZERO, false);
        assertNotNull(PassableClip.march(cells, entry, new Vec3(-1, -.4, 0), 1));
        assertNull(PassableClip.march(cells, entry, new Vec3(1, -.4, 0), 1));
        assertEquals(entry.getLocation(), PassableClip.march(cells, entry, new Vec3(-1, -.4, 0), 1).getLocation());
    }

    @Test
    void malformedGeometryIsRejected() {
        long[] model = shape(cutout());
        assertTrue(ModelSurface.valid(model));
        assertFalse(ModelSurface.valid(java.util.Arrays.copyOf(model, model.length - 1)));
        model[65] = Long.MAX_VALUE;
        assertNull(PassableClip.unpack(PassableClip.pack(model)));
    }

    // a big face of a machine cut down to each block it crosses (crop) is met by bullets within the block as the whole
    // one is, and keeps far fewer pixels
    @Test
    void croppedFaceIsMetWithinTheBlockAsTheWholeOne() {
        java.util.Random r = new java.util.Random(7);
        int w = 96, h = 80;
        long[] alpha = new long[(w * h + 63) / 64];
        for (int i = 0; i < alpha.length; i++) alpha[i] = r.nextLong() | r.nextLong();
        ModelSurface.Face big = new ModelSurface.Face(new double[] {-2.3, .1, -1.7, 4.6, 2.9, .4, .2, -.8, 5.1},
                new double[] {0, 0, w, 3, 7, h}, w, h, alpha, false);
        int checked = 0, kept = 0;
        for (int bx = -2; bx <= 4; bx++) for (int by = -1; by <= 2; by++) for (int bz = -2; bz <= 5; bz++) {
            BlockPos pos = new BlockPos(bx, by, bz);
            if (!ModelSurface.overlaps(big, pos)) continue;
            ModelSurface.Face whole = ModelSurface.local(big, pos, net.minecraft.world.level.block.Rotation.NONE);
            ModelSurface.Face cut = ModelSurface.crop(whole);
            if (cut != null) {
                assertTrue(cut.alpha().length <= whole.alpha().length);
                kept += cut.alpha().length;
            }
            long[] a = shape(whole), b = cut == null ? null : shape(cut);
            for (int k = 0; k < 400; k++) {
                Vec3 from = new Vec3(bx + r.nextDouble() * 3 - 1, by + r.nextDouble() * 3 - 1, bz + r.nextDouble() * 3 - 1);
                Vec3 to = new Vec3(bx + r.nextDouble() * 3 - 1, by + r.nextDouble() * 3 - 1, bz + r.nextDouble() * 3 - 1);
                var ha = ModelSurface.hit(a, pos, from, to, false, true);
                var hb = b == null ? null : ModelSurface.hit(b, pos, from, to, false, true);
                assertEquals(ha == null, hb == null, pos + " " + from + " " + to);
                if (ha != null) {
                    assertTrue(ha.getLocation().distanceTo(hb.getLocation()) < 1e-9);
                    checked++;
                }
            }
        }
        assertTrue(checked > 100, "hits " + checked);
        assertTrue(kept * 4 < alpha.length * 30, "kept " + kept);
    }
}
