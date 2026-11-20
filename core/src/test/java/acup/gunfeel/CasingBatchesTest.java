package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.host.render.Intent;
import acup.gunfeel.host.render.RecordingRenderPort;
import acup.gunfeel.host.render.RenderPort;

// CasingBatches on the recording port: the parts' draws in blocks of 64 at aligned offsets, the slots' bytes, the
// switch off (three frames undrawn, the draw's error) with the version's line once
class CasingBatchesTest {
    private RecordingRenderPort port;
    private final List<String> offs = new ArrayList<>();
    private final Map<Integer, Integer> meshes = new LinkedHashMap<>();
    private boolean ready = true;

    @BeforeEach
    void setUp() {
        CasingBatches.reset();
        port = new RecordingRenderPort();
        offs.clear();
        meshes.clear();
        ready = true;
        CasingBatches.init(port, new CasingBatches.Host() {
            @Override
            public int mesh(int part) {
                return meshes.getOrDefault(part, RenderPort.NONE);
            }

            @Override
            public boolean ready() {
                return ready;
            }

            @Override
            public void off(String why) {
                offs.add(why);
            }
        });
    }

    private static void casing(Random r, int light, int[] parts, int[] colors) {
        float[] p = CasingBatches.poses();
        int k = CasingBatches.count() * 25;
        for (int c = 0; c < 25; c++) p[k + c] = r.nextFloat() * 4F - 2F;
        CasingBatches.add(light, parts, colors);
    }

    private List<String> draws() {
        List<String> out = new ArrayList<>();
        for (String l : port.log) if (l.startsWith("drawInstanced")) out.add(l);
        return out;
    }

    @Test
    void blocksOfSixtyFourAtAlignedOffsets() {
        for (int alignment : new int[] {1, 64, 256}) for (int n : new int[] {1, 63, 64, 65, 128, 129, 300}) {
            setUp();
            port.alignment = alignment;
            meshes.put(7, 11);
            meshes.put(3, 12);
            Random r = new Random(n * 31 + alignment);
            CasingBatches.begin();
            // every casing has part 7; every third part 3 too
            for (int i = 0; i < n; i++) casing(r, 0x00F000F0, i % 3 == 0 ? new int[] {7, 3} : new int[] {7}, new int[] {0x123456, 0x654321});
            port.run(Intent.AFTER_OPAQUE_SOLID);
            List<String> want = new ArrayList<>();
            int offset = 0, with3 = (n + 2) / 3;
            for (int[] kind : new int[][] {{11, n}, {12, with3}}) {
                for (int first = 0; first < kind[1]; first += 64) {
                    int k = Math.min(64, kind[1] - first);
                    want.add("drawInstanced 1 " + kind[0] + " 1000 " + offset + " " + k);
                    offset = (offset + k * 160 + alignment - 1) / alignment * alignment;
                }
            }
            assertEquals(want, draws(), "alignment " + alignment + ", " + n + " casings");
            assertTrue(port.log.contains("flush"));
            String upload = "upload 1000 " + (offset + CasingBatches.BLOCK) + " ";
            assertTrue(port.log.stream().anyMatch(l -> l.startsWith(upload)), port.log.toString());
            assertEquals(0, CasingBatches.count());
        }
    }

    @Test
    void aSlotsBytes() {
        meshes.put(5, 9);
        port.alignment = 256;
        CasingBatches.begin();
        float[] p = CasingBatches.poses();
        for (int c = 0; c < 25; c++) p[c] = c + 0.5F;
        CasingBatches.add(0x00AB00CD, new int[] {5}, new int[] {0x80FF40});
        port.run(Intent.AFTER_OPAQUE_SOLID);
        ByteBuffer b = ByteBuffer.wrap(port.uploaded).order(ByteOrder.nativeOrder());
        for (int c = 0; c < 16; c++) assertEquals(c + 0.5F, b.getFloat(c * 4));
        // the normal matrix as three columns with a 0, then (0, 0, 0, 1)
        for (int col = 0; col < 3; col++) {
            for (int row = 0; row < 3; row++) assertEquals(16 + col * 3 + row + 0.5F, b.getFloat(64 + col * 16 + row * 4));
            assertEquals(0F, b.getFloat(64 + col * 16 + 12));
        }
        assertEquals(1F, b.getFloat(64 + 48 + 12));
        assertEquals(0x80 / 255F, b.getFloat(128));
        assertEquals(0xFF / 255F, b.getFloat(132));
        assertEquals(0x40 / 255F, b.getFloat(136));
        assertEquals(1F, b.getFloat(140));
        assertEquals(0xCD, b.getInt(144));
        assertEquals(0xAB, b.getInt(148));
    }

    @Test
    void aPartWithNoMeshTakesItsPlaceButIsNotDrawn() {
        meshes.put(2, 8);
        port.alignment = 1;
        Random r = new Random(5);
        CasingBatches.begin();
        casing(r, 0, new int[] {4, 2}, new int[] {1, 2});
        port.run(Intent.AFTER_OPAQUE_SOLID);
        assertEquals(List.of("drawInstanced 1 8 1000 160 1"), draws());
    }

    @Test
    void noMeshNoDrawNoUpload() {
        Random r = new Random(5);
        CasingBatches.begin();
        casing(r, 0, new int[] {4}, new int[] {1});
        port.run(Intent.AFTER_OPAQUE_SOLID);
        assertEquals(List.of(), port.log);
    }

    @Test
    void offAfterThreeFramesUndrawn() {
        Random r = new Random(1);
        for (int frame = 0; frame < 3; frame++) {
            assertFalse(CasingBatches.failed(), "frame " + frame);
            CasingBatches.begin();
            casing(r, 0, new int[] {1}, new int[] {1});
        }
        assertFalse(CasingBatches.failed());
        CasingBatches.begin();
        assertTrue(CasingBatches.failed());
        assertEquals(List.of("the casings of three frames in a row were not drawn after NTM's submit"), offs);
        CasingBatches.begin();
        CasingBatches.off("again");
        assertEquals(1, offs.size());
    }

    @Test
    void twoUndrawnThenDrawnIsNotOff() {
        meshes.put(1, 4);
        Random r = new Random(1);
        for (int round = 0; round < 4; round++) {
            for (int frame = 0; frame < 3; frame++) {
                CasingBatches.begin();
                casing(r, 0, new int[] {1}, new int[] {1});
            }
            port.run(Intent.AFTER_OPAQUE_SOLID);
        }
        CasingBatches.begin();
        assertFalse(CasingBatches.failed());
        assertEquals(List.of(), offs);
    }

    @Test
    void notReadyNotDrawn() {
        meshes.put(1, 4);
        ready = false;
        CasingBatches.begin();
        casing(new Random(2), 0, new int[] {1}, new int[] {1});
        port.run(Intent.AFTER_OPAQUE_SOLID);
        assertEquals(List.of(), port.log);
        assertEquals(1, CasingBatches.count());
    }

    @Test
    void theDrawsErrorTurnsItOff() {
        meshes.put(1, 4);
        port.failFlush = new IllegalStateException("boom");
        CasingBatches.begin();
        casing(new Random(3), 0, new int[] {1}, new int[] {1});
        port.run(Intent.AFTER_OPAQUE_SOLID);
        assertTrue(CasingBatches.failed());
        assertEquals(List.of("the draw failed: java.lang.IllegalStateException: boom"), offs);
        assertEquals(0, CasingBatches.count());
    }

    @Test
    void growsPastItsRoom() {
        meshes.put(1, 4);
        port.alignment = 1;
        Random r = new Random(4);
        CasingBatches.begin();
        for (int i = 0; i < 1000; i++) casing(r, i, new int[] {1}, new int[] {i});
        port.run(Intent.AFTER_OPAQUE_SOLID);
        assertEquals(16, draws().size());
        ByteBuffer b = ByteBuffer.wrap(port.uploaded).order(ByteOrder.nativeOrder());
        assertEquals(999, b.getInt(999 * 160 + 144));
    }

    @Test
    void leaveFreesTheParams() {
        meshes.put(1, 4);
        CasingBatches.begin();
        casing(new Random(6), 0, new int[] {1}, new int[] {1});
        port.run(Intent.AFTER_OPAQUE_SOLID);
        CasingBatches.leave();
        assertTrue(port.log.contains("free 1000"));
        CasingBatches.begin();
        casing(new Random(6), 0, new int[] {1}, new int[] {1});
        port.run(Intent.AFTER_OPAQUE_SOLID);
        assertTrue(port.log.stream().anyMatch(l -> l.startsWith("upload 1001 ")));
    }
}
