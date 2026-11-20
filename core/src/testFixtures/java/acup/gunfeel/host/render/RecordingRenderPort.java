package acup.gunfeel.host.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A render port that draws nothing and keeps what it was asked: core's tests run their drawers through it ({@link #run})
 * at a frame they set, and read {@link #log}. Lines: {@code fill <buffer> <room> -> <elements>}, {@code drawAt <material>
 * <buffer> <first> <count> <ox> <oy> <oz> <pull>}, {@code queue <material> <part> <room> -> <elements>} (a queued part is
 * written at once), {@code free <buffer>}, {@code upload <params> <length> <crc32 of the bytes>}, {@code drawInstanced
 * <material> <mesh> <params> <offset> <n>}, {@code flush}. A params handle is 1000 on.
 */
public class RecordingRenderPort implements RenderPort, RenderSink, CameraFrame {
    public final List<String> log = new ArrayList<>();
    public final List<MaterialSpec> materials = new ArrayList<>(List.of(new MaterialSpec("", "", 0, 0, 0, false, false, 0, 0, false, null, false, false)));
    private final Map<MaterialSpec, Integer> materialOf = new HashMap<>();
    private final List<Part> parts = new ArrayList<>();
    private final Map<Integer, List<Drawer>> drawers = new HashMap<>();
    private final List<Boolean> buffers = new ArrayList<>();
    public double camX, camY, camZ;
    public long nanos;
    public int translucency = PLAIN;
    private boolean filling;
    /** the alignment of a draw's params ({@link #paramsAlignment}) */
    public int alignment = 256;
    /** a drawer's flush throws this, once, when set (a GPU's error) */
    public RuntimeException failFlush;
    /** the bytes of the last upload */
    public byte[] uploaded;
    private int paramsMade;
    // meshes made by meshOf: their topology (a version's kept meshes; here only a number)
    private final Map<Integer, Integer> topologyOf = new HashMap<>();
    private int firstTopology = -1;

    public RecordingRenderPort() {
        parts.add(null);
        buffers.add(false);
    }

    /** the drawers of an intent, in the order registered */
    public void run(int intent) {
        for (Drawer d : drawers.getOrDefault(intent, List.of())) d.draw(this);
    }

    public int drawers(int intent) {
        return drawers.getOrDefault(intent, List.of()).size();
    }

    public int parts() {
        return parts.size() - 1;
    }

    @Override
    public int material(MaterialSpec spec) {
        return materialOf.computeIfAbsent(spec, s -> {
            materials.add(s);
            return materials.size() - 1;
        });
    }

    @Override
    public int buffer(int layout, int room) {
        buffers.add(true);
        return buffers.size() - 1;
    }

    @Override
    public VertexWriter fill(int buffer, int room) {
        if (!buffers.get(buffer)) throw new IllegalStateException("buffer " + buffer + " was freed");
        if (filling) throw new IllegalStateException("a buffer is being filled already");
        filling = true;
        return new Counter() {
            @Override
            public int done() {
                filling = false;
                log.add("fill " + buffer + " " + room + " -> " + n);
                return n;
            }
        };
    }

    @Override
    public void free(int buffer) {
        if (buffer >= 1000) {
            log.add("free " + buffer);
            return;
        }
        buffers.set(buffer, false);
        log.add("free " + buffer);
    }

    @Override
    public int part(Part part) {
        parts.add(part);
        return parts.size() - 1;
    }

    @Override
    public int params(String name) {
        return 1000 + paramsMade++;
    }

    @Override
    public int paramsAlignment() {
        return alignment;
    }

    @Override
    public void upload(int params, java.nio.ByteBuffer data, int length) {
        uploaded = new byte[length];
        data.duplicate().clear().get(uploaded, 0, length);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(uploaded);
        log.add("upload " + params + " " + length + " " + Long.toHexString(crc.getValue()));
    }

    @Override
    public void drawInstanced(int material, int mesh, int params, int offset, int n) {
        Integer t = topologyOf.get(mesh);
        if (t != null) {
            if (firstTopology < 0) firstTopology = t;
            else if (firstTopology != t) throw new IllegalArgumentException("mesh " + mesh + ": one flush, one topology");
        }
        log.add("drawInstanced " + material + " " + mesh + " " + params + " " + offset + " " + n);
    }

    @Override
    public void flush() {
        firstTopology = -1;
        log.add("flush");
        if (failFlush != null) {
            RuntimeException e = failFlush;
            failFlush = null;
            throw e;
        }
    }

    /** a kept mesh of a topology (any number: the version's kinds), for drawInstanced */
    public int meshOf(int topology) {
        int h = buffer(Layouts.MESH_ENTITY, 0);
        topologyOf.put(h, topology);
        return h;
    }

    @Override
    public void drawer(int intent, Drawer drawer) {
        drawers.computeIfAbsent(intent, i -> new ArrayList<>()).add(drawer);
    }

    @Override
    public CameraFrame frame() {
        return this;
    }

    @Override
    public void drawAt(int material, int buffer, int first, int count, double ox, double oy, double oz, float pull) {
        if (!buffers.get(buffer)) throw new IllegalStateException("buffer " + buffer + " was freed");
        log.add("drawAt " + material + " " + buffer + " " + first + " " + count + " " + ox + " " + oy + " " + oz + " " + pull);
    }

    @Override
    public void queue(int material, int part, int room) {
        Counter c = new Counter();
        parts.get(part).write(c);
        log.add("queue " + material + " " + part + " " + room + " -> " + c.n);
    }

    @Override
    public double camX() {
        return camX;
    }

    @Override
    public double camY() {
        return camY;
    }

    @Override
    public double camZ() {
        return camZ;
    }

    @Override
    public long nanos() {
        return nanos;
    }

    @Override
    public int translucency() {
        return translucency;
    }

    private static class Counter implements VertexWriter {
        int n;

        @Override
        public void decal(float x, float y, float z, int argb, float u, float v, int light, float nx, float ny, float nz) {
            n++;
        }

        @Override
        public int done() {
            return n;
        }
    }
}
