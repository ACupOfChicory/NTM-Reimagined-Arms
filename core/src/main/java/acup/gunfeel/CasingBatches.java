package acup.gunfeel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import acup.gunfeel.host.render.Intent;
import acup.gunfeel.host.render.Layouts;
import acup.gunfeel.host.render.MaterialSpec;
import acup.gunfeel.host.render.RenderPort;
import acup.gunfeel.host.render.RenderSink;

/**
 * NTM's spent casings' bodies drawn instanced: one draw for each part of the casing model, its instances' matrices,
 * colours and light in a params buffer (std140, 64 a block), the parts' meshes the version's. The frame's casings come
 * from the version (CasingInstances, 26.2: it takes each casing's matrices into {@link #poses}); this groups them by
 * part, lays the blocks and draws them at {@link Intent#AFTER_OPAQUE_SOLID}. The version leads the frame: it says
 * when NTM's casings begin to be submitted ({@link #begin}); core does not learn of a frame by itself.
 *
 * <p>Off for good, with one line in the log (the version's), when a frame's casings were not drawn three frames in a
 * row (the place not after NTM's submit), the material did not come, or the draw failed. The client's thread only.
 */
public final class CasingBatches {
    /** what the version does for core: its meshes, its log, whether this frame's casings can be drawn */
    public interface Host {
        /** the kept mesh of the model's part as it is now ({@link RenderPort#NONE}: none, that part not drawn) */
        int mesh(int part);

        /** the frame's casings have what their draw needs (the version's state of them, the render type) */
        boolean ready();

        /** the instanced draw off, NTM draws them: the version's line, once */
        void off(String why);
    }

    static final int BATCH = 64, STRIDE = 160, MAX_PARTS = 4;
    /** the bytes of a draw's parameters (its 64 instances' slots) the program reads from an offset */
    public static final int BLOCK = BATCH * STRIDE;
    /** the instanced program's material (the version knows its texture: the frame's render type of NTM's casings) */
    public static final MaterialSpec MATERIAL = new MaterialSpec("ntmgunfeel_casing_instances", "ntmgunfeel:core/casing_instances",
            Layouts.MESH_ENTITY, acup.gunfeel.host.render.Blend.OPAQUE, 0.1F, true, true, 0F, 0F, true, null, true, true);

    private static RenderPort port;
    private static Host host;
    private static int material, params;
    private static boolean failed;

    // this frame's casings: matrix (16), normal matrix (9), light; their model's parts and colours
    private static float[] poses = new float[256 * 25];
    private static int[] lights = new int[256];
    private static int[][] partIds = new int[256][], colors = new int[256][];
    private static int count;
    private static boolean queuedUndrawn;
    /** frames in a row whose casings were not drawn (the place did not come after NTM's submit) */
    private static int missed;

    // the frame's grouping, kept between frames
    private static int[] partOf = new int[8];
    private static int[][] byPart = new int[8][];
    private static int[] drawMesh = new int[16];
    private static int[] drawAt = new int[16], drawN = new int[16];

    private static ByteBuffer staging;

    private CasingBatches() {
    }

    /** once: the material and the drawer; false (off) when the version has no material for it */
    public static void init(RenderPort renderPort, Host versionHost) {
        port = renderPort;
        host = versionHost;
        material = port.material(MATERIAL);
        if (material == RenderPort.NONE) {
            off("its shader did not compile");
            return;
        }
        port.drawer(Intent.AFTER_OPAQUE_SOLID, CasingBatches::draw);
    }

    public static boolean failed() {
        return failed;
    }

    /** NTM's casings begin to be submitted this frame (the version's word) */
    public static void begin() {
        // a level drawn twice before the place (another mod's second view) loses the first view's casings once;
        // three frames in a row and the place is not where it should be
        if (queuedUndrawn && count > 0) {
            if (++missed >= 3) off("the casings of three frames in a row were not drawn after NTM's submit");
        } else missed = 0;
        count = 0;
        queuedUndrawn = false;
    }

    /** this frame's casings so far */
    public static int count() {
        return count;
    }

    /** the poses' array with room for one more casing: its matrix at count() * 25, its normal matrix 16 on */
    public static float[] poses() {
        if (count == lights.length) grow();
        return poses;
    }

    /** the casing whose matrices were just put at count() * 25 ({@link #poses}) */
    public static void add(int light, int[] parts, int[] cs) {
        lights[count] = light;
        partIds[count] = parts;
        colors[count] = cs;
        count++;
        queuedUndrawn = true;
    }

    private static void grow() {
        int n = lights.length * 2;
        poses = java.util.Arrays.copyOf(poses, n * 25);
        lights = java.util.Arrays.copyOf(lights, n);
        partIds = java.util.Arrays.copyOf(partIds, n);
        colors = java.util.Arrays.copyOf(colors, n);
    }

    // the frame's casings, at their place
    static void draw(RenderSink sink) {
        if (count == 0 || !host.ready()) return;
        queuedUndrawn = false;
        try {
            drawChecked(sink);
        } catch (RuntimeException | LinkageError e) {
            off("the draw failed: " + e);
        } finally {
            count = 0;
        }
    }

    private static void drawChecked(RenderSink sink) {
        int alignment = port.paramsAlignment();
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
        int length = 0;
        for (int at = 0; at < kinds; at++) {
            int[] list = byPart[at];
            for (int first = 0; first < list[0]; first += BATCH)
                length = aligned(length + Math.min(BATCH, list[0] - first) * STRIDE, alignment);
        }
        length += BLOCK;
        if (staging == null || staging.capacity() < length) staging = ByteBuffer.allocateDirect(length).order(ByteOrder.nativeOrder());
        staging.clear();
        staging.limit(length);
        int draws = 0, offset = 0;
        for (int at = 0; at < kinds; at++) {
            int mesh = host.mesh(partOf[at]);
            int[] list = byPart[at];
            for (int first = 0; first < list[0]; first += BATCH) {
                int n = Math.min(BATCH, list[0] - first);
                staging.position(offset);
                for (int q = 0; q < n; q++) {
                    int code = list[1 + first + q], i = code / MAX_PARTS, j = code % MAX_PARTS;
                    put(i, 0xFF000000 | colors[i][j]);
                }
                if (mesh != RenderPort.NONE) {
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
        if (draws == 0) return;
        staging.position(0);
        if (params == RenderPort.NONE) params = port.params("NTM casings' poses");
        sink.upload(params, staging, length);
        for (int d = 0; d < draws; d++) sink.drawInstanced(material, drawMesh[d], params, drawAt[d], drawN[d]);
        sink.flush();
    }

    static int aligned(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
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

    /** off for good: the version's line, once */
    public static void off(String why) {
        if (failed) return;
        failed = true;
        count = 0;
        if (host != null) host.off(why);
    }

    /** the level left: the frame's casings and the params' buffer gone (made again at the next draw) */
    public static void leave() {
        if (params != RenderPort.NONE) port.free(params);
        params = RenderPort.NONE;
        staging = null;
        count = 0;
        missed = 0;
        queuedUndrawn = false;
    }

    // core's tests: as at load
    static void reset() {
        port = null;
        host = null;
        material = params = 0;
        failed = false;
        count = 0;
        queuedUndrawn = false;
        missed = 0;
        staging = null;
    }

    // for the stand and the tests: the bytes last drawn from
    static ByteBuffer staging() {
        return staging;
    }
}
