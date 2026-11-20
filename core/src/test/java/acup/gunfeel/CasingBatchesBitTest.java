package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import acup.gunfeel.host.render.Intent;
import acup.gunfeel.host.render.RecordingRenderPort;
import acup.gunfeel.host.render.RenderPort;

// CasingBatches against CasingInstances' drawChecked and put as they were before the port (b4a54a0, taken by script word
// for word: the device's alignment a parameter, memCalloc a zeroed direct buffer of native order, the uniform buffer's
// making and the pass left out): the bytes uploaded, the draws (mesh, offset, count), the length and the largest
// mesh's index count (RenderPort26.flush takes it over the draws' meshes)
class CasingBatchesBitTest {
    static final class Old {
        static final int BATCH = 64, STRIDE = 160, BLOCK = BATCH * STRIDE, MAX_PARTS = 4;

        record Mesh(int buffer, int indexCount) {}

        static final Map<Integer, Mesh> MESHES = new HashMap<>();
        static float[] poses = new float[256 * 25];
        static int[] lights = new int[256];
        static int[][] partIds = new int[256][], colors = new int[256][];
        static int count;
        static int[] partOf = new int[8];
        static int[][] byPart = new int[8][];
        static Mesh[] drawMesh = new Mesh[16];
        static int[] drawAt = new int[16], drawN = new int[16];
        static ByteBuffer staging;
        static int lastLength, lastMost, lastDraws;

        static int aligned(int value,int alignment) { return (value+alignment-1)/alignment*alignment; }

        static void grow() {
            int n = lights.length * 2;
            poses = java.util.Arrays.copyOf(poses, n * 25);
            lights = java.util.Arrays.copyOf(lights, n);
            partIds = java.util.Arrays.copyOf(partIds, n);
            colors = java.util.Arrays.copyOf(colors, n);
        }

        static void drawChecked(int alignment) {
            // the parts drawn (a handful), each with the casings that have it, as casing * MAX_PARTS + its part's place
            int kinds = 0;
            for (int i = 0; i < count; i++) for (int p : partIds[i]) {
                int at = 0;
                while (at < kinds && partOf[at] != p) at++;
                if (at == kinds) {
                    if (kinds == partOf.length) {
                        partOf = java.util.Arrays.copyOf(partOf, kinds * 2);
                        byPart = java.util.Arrays.copyOf(byPart, kinds * 2);
                    }
                    partOf[kinds] = p;
                    if (byPart[kinds] == null || byPart[kinds].length < count * MAX_PARTS + 1) byPart[kinds] = new int[count * MAX_PARTS * 2 + 1];
                    byPart[kinds++][0] = 0;
                }
            }
            for (int i = 0; i < count; i++) for (int j = 0; j < partIds[i].length; j++) {
                int at = 0;
                while (partOf[at] != partIds[i][j]) at++;
                int[] list = byPart[at];
                list[++list[0]] = i * MAX_PARTS + j;
            }
            int length = 0, mostIndices = 0;
            for (int at = 0; at < kinds; at++) {
                int[] list = byPart[at];
                for (int first = 0; first < list[0]; first += BATCH)
                    length = aligned(length + Math.min(BATCH, list[0] - first) * STRIDE, alignment);
                Mesh mesh = MESHES.get(partOf[at]);
                if (mesh != null) mostIndices = Math.max(mostIndices, mesh.indexCount());
            }
            length += BLOCK;
            if (staging == null || staging.capacity() < length) {
                // MemoryUtil.memCalloc(length): zeroed, native order
                staging = ByteBuffer.allocateDirect(length).order(ByteOrder.nativeOrder());
            }
            staging.clear();
            staging.limit(length);
            int draws = 0, offset = 0;
            for (int at = 0; at < kinds; at++) {
                Mesh mesh = MESHES.get(partOf[at]);
                int[] list = byPart[at];
                for (int first = 0; first < list[0]; first += BATCH) {
                    int n = Math.min(BATCH, list[0] - first);
                    staging.position(offset);
                    for (int q = 0; q < n; q++) {
                        int code = list[1 + first + q], i = code / MAX_PARTS, j = code % MAX_PARTS;
                        put(i, 0xFF000000 | colors[i][j]);
                    }
                    if (mesh != null) {
                        if (draws == drawMesh.length) {
                            drawMesh = java.util.Arrays.copyOf(drawMesh, draws * 2);
                            drawAt = java.util.Arrays.copyOf(drawAt, draws * 2);
                            drawN = java.util.Arrays.copyOf(drawN, draws * 2);
                        }
                        drawMesh[draws] = mesh;
                        drawAt[draws] = offset;
                        drawN[draws++] = n;
                    }
                    offset = aligned(offset + n * STRIDE, alignment);
                }
            }
            if (draws == 0) {
                lastDraws = 0;
                return;
            }
            staging.position(0);
            lastLength = length;
            lastMost = mostIndices;
            lastDraws = draws;
    }

        // one slot (std140: mat4 pose, mat4 normal, vec4 colour, ivec4 light)
        private static void put(int i, int argb) {
            int k = i * 25;
            for (int c = 0; c < 16; c++) staging.putFloat(poses[k + c]);
            for (int col = 0; col < 3; col++) {
                staging.putFloat(poses[k + 16 + col * 3]).putFloat(poses[k + 17 + col * 3]).putFloat(poses[k + 18 + col * 3]).putFloat(0F);
            }
            staging.putFloat(0F).putFloat(0F).putFloat(0F).putFloat(1F);
            staging.putFloat((argb >> 16 & 255) / 255F).putFloat((argb >> 8 & 255) / 255F).putFloat((argb & 255) / 255F)
                    .putFloat((argb >>> 24) / 255F);
            int light = lights[i];
            staging.putInt(light & 0xFFFF).putInt(light >>> 16 & 0xFFFF).putInt(0).putInt(0);
        }
    }

    @Test
    void bitForBitAsBefore() {
        Random r = new Random(20261120L);
        int compared = 0, drawsSeen = 0;
        // frame after frame, as in the game: both keep their arrays and their bytes between frames
        CasingBatches.reset();
        RecordingRenderPort port = new RecordingRenderPort();
        Map<Integer, Integer> meshOf = new HashMap<>();
        CasingBatches.init(port, new CasingBatches.Host() {
            @Override
            public int mesh(int part) {
                return meshOf.getOrDefault(part, RenderPort.NONE);
            }

            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public void off(String why) {
                throw new AssertionError(why);
            }
        });
        for (int seed = 0; seed < 2000; seed++) {
            int alignment = new int[] {1, 4, 64, 256}[r.nextInt(4)];
            int n = r.nextInt(10) == 0 ? 0 : 1 + r.nextInt(r.nextBoolean() ? 70 : 300);
            int partKinds = 1 + r.nextInt(6);
            port.alignment = alignment;
            port.log.clear();
            meshOf.clear();
            Old.MESHES.clear();
            for (int k = 0; k < partKinds; k++) {
                if (r.nextInt(8) == 0) continue; // a part with no mesh
                int handle = 100 + k, indices = 6 * (10 + r.nextInt(200));
                meshOf.put(k, handle);
                Old.MESHES.put(k, new Old.Mesh(handle, indices));
            }
            Map<Integer, Integer> indicesOf = new HashMap<>();
            for (var e : Old.MESHES.values()) indicesOf.put(e.buffer(), e.indexCount());
            CasingBatches.begin();
            Old.count = 0;
            for (int i = 0; i < n; i++) {
                int[] parts = new int[1 + r.nextInt(Old.MAX_PARTS)];
                for (int j = 0; j < parts.length; j++) parts[j] = r.nextInt(partKinds);
                int[] cs = new int[parts.length + r.nextInt(2)];
                for (int j = 0; j < cs.length; j++) cs[j] = r.nextInt();
                int light = r.nextInt();
                float[] p = CasingBatches.poses();
                if (Old.count == Old.lights.length) Old.grow();
                int k = i * 25;
                for (int c = 0; c < 25; c++) {
                    float v = r.nextInt(20) == 0 ? Float.intBitsToFloat(r.nextInt()) : r.nextFloat() * 8F - 4F;
                    p[k + c] = v;
                    Old.poses[k + c] = v;
                }
                CasingBatches.add(light, parts, cs);
                Old.lights[Old.count] = light;
                Old.partIds[Old.count] = parts;
                Old.colors[Old.count] = cs;
                Old.count++;
            }
            Old.lastDraws = -1;
            if (Old.count > 0) Old.drawChecked(alignment);
            port.run(Intent.AFTER_OPAQUE_SOLID);

            List<String> was = new ArrayList<>(), now = new ArrayList<>();
            int most = 0;
            for (int d = 0; d < Math.max(0, Old.lastDraws); d++)
                was.add("drawInstanced 1 " + Old.drawMesh[d].buffer() + " 1000 " + Old.drawAt[d] + " " + Old.drawN[d]);
            for (String l : port.log) if (l.startsWith("drawInstanced")) {
                now.add(l);
                most = Math.max(most, indicesOf.get(Integer.parseInt(l.split(" ")[2])));
            }
            assertEquals(was, now, "seed " + seed);
            if (Old.lastDraws > 0) {
                assertEquals(Old.lastMost, most, "seed " + seed);
                byte[] old = new byte[Old.lastLength];
                Old.staging.duplicate().position(0).get(old, 0, Old.lastLength);
                assertArrayEquals(old, port.uploaded, "seed " + seed);
                compared++;
                drawsSeen += Old.lastDraws;
            } else assertEquals(List.of(), port.log, "seed " + seed);
        }
        assertEquals(true, compared > 1500 && drawsSeen > 5000, compared + " frames, " + drawsSeen + " draws");
    }
}
