package acup.gunfeel.host.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A render port that draws nothing and keeps what it was asked: core's tests run their drawers through it ({@link #run})
 * at a frame they set, and read {@link #log}. Lines: {@code fill <buffer> <room> -> <elements>}, {@code drawAt <material>
 * <buffer> <first> <count> <ox> <oy> <oz> <pull>}, {@code queue <material> <part> <room> -> <elements>} (a queued part is
 * written at once unless defer is set), {@code free <buffer>}, {@code upload <params> <length> <crc32 of the bytes>}, {@code drawInstanced
 * <material> <mesh> <params> <offset> <n>}, {@code flush}, {@code map <params> <length> <crc32>} (at done),
 * {@code texture <handle> <name> <w> <h>}, {@code texels <texture> <x> <y> <w> <h> <crc32>}. A params handle is 1000
 * on, a texture's 2000 on.
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
    /** Отключает текстовую трассу при проверке выделений; записи байтов сохраняются. */
    public boolean record = true;
    private final List<ParamsState> paramStates = new ArrayList<>();
    private final List<Integer> lengths = new ArrayList<>();
    private final List<Integer> layouts = new ArrayList<>();
    /** Vertices with raw float bits, in execution order. */
    public record Vertex(int layout, int x, int y, int z, int argb, int u, int v, int light, int nx, int ny, int nz) {}
    public final List<Vertex> vertices = new ArrayList<>();
    public final Map<Integer, List<Vertex>> writtenOf = new HashMap<>();
    private final List<Vertex> fillingVertices = new ArrayList<>();
    /** Delay execution until the caller has prepared several Parts. */
    public boolean defer;
    private record Queued(int material, int part, int room) {}
    private final List<Queued> queued = new ArrayList<>();

    public void drain() {
        try { for (Queued q : queued) execute(q.material, q.part, q.room); }
        finally { queued.clear(); }
    }
    private final Counter fillCounter = new Counter(), queueCounter = new Counter();
    private int fillingBuffer;
    private static final class ParamsState {
        int way;
        java.nio.ByteBuffer data;
        byte[] bytes;
        final MapWriter writer = new MapWriter();
    }
    private static final class MapWriter implements ParamsWriter {
        java.nio.ByteBuffer data;
        ParamsState state;
        RecordingRenderPort owner;
        int handle, length;
        private java.nio.ByteBuffer at(int size) {
            if (data == null) throw new IllegalStateException("params written after done");
            int pos = (data.position() + 15) / 16 * 16;
            if (pos + size > length) throw new IndexOutOfBoundsException("params element");
            data.position(pos);
            return data;
        }
        public ParamsWriter vec4(float x, float y, float z, float w) {
            at(16).putFloat(x).putFloat(y).putFloat(z).putFloat(w);
            return this;
        }
        public ParamsWriter mat4(org.joml.Matrix4fc m) {
            java.nio.ByteBuffer b = at(64);
            m.get(b);
            b.position(b.position() + 64);
            return this;
        }
        public void done() {
            if (data == null) throw new IllegalStateException("params done twice");
            int written = data.position();
            abort();
            if (written != length) throw new IllegalStateException("params size differs from specification");
            for (int i = 0; i < length; i++) state.bytes[i] = state.data.get(i);
            owner.mapped = state.bytes;
            if (owner.record) {
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(state.bytes);
                owner.log.add("map " + handle + " " + length + " " + Long.toHexString(crc.getValue()));
            }
        }
        public void abort() {
            if (data == null) return;
            data = null;
            owner.mapping = false;
        }
    }
    private ParamsState paramsState(int handle) {
        int i = handle - 1000;
        if (i < 0 || i >= paramStates.size()) throw new IllegalArgumentException("unknown params");
        return paramStates.get(i);
    }
    private void bufferKnown(int handle) {
        if (handle <= 0 || handle >= buffers.size()) throw new IllegalArgumentException("unknown buffer");
    }
    // meshes made by meshOf: their topology (a version's kept meshes; here only a number)
    private final Map<Integer, Integer> topologyOf = new HashMap<>();
    private int firstTopology = -1;
    /** the bytes of the last map, as written at its done */
    public byte[] mapped;
    private boolean mapping;
    private final List<Boolean> textures = new ArrayList<>();
    /** the texels written, by texture: width × height RGBA, as written */
    public final Map<Integer, byte[]> texelsOf = new HashMap<>();
    private final Map<Integer, int[]> textureSize = new HashMap<>();
    /** live answers false for this texture once (the version lost it) */
    public int lose = -1;

    public RecordingRenderPort() {
        parts.add(null);
        buffers.add(false);
        lengths.add(0);
        layouts.add(0);
    }

    /** the drawers of an intent, in the order registered */
    public void run(int intent) {
        List<Drawer> ds = drawers.get(intent);
        if (ds != null) for (int i = 0; i < ds.size(); i++) {
            try { ds.get(i).draw(this); }
            finally { queueCounter.open = false; }
        }
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
        lengths.add(0);
        layouts.add(layout);
        writtenOf.put(buffers.size() - 1, new ArrayList<>());
        return buffers.size() - 1;
    }

    @Override
    public VertexWriter fill(int buffer, int room) {
        bufferKnown(buffer);
        if (!buffers.get(buffer)) throw new IllegalStateException("freed buffer");
        if (filling) throw new IllegalStateException("a buffer is being filled already");
        filling = true;
        fillingBuffer = buffer;
        fillCounter.n = 0;
        fillCounter.open = true;
        fillCounter.owner = this;
        fillCounter.room = room;
        fillCounter.layout = layouts.get(buffer);
        fillCounter.vertices = fillingVertices;
        if (record) fillCounter.vertices.clear();
        return fillCounter;
    }

    @Override
    public void free(int buffer) {
        if (buffer >= 2000) {
            textures.set(buffer - 2000, false);
            if (record) log.add("free " + buffer);
            return;
        }
        if (buffer >= 1000) {
            paramsState(buffer).way = 0;
            if (record) log.add("free " + buffer);
            return;
        }
        bufferKnown(buffer);
        buffers.set(buffer, false);
        if (record) log.add("free " + buffer);
    }

    @Override
    public int part(Part part) {
        parts.add(part);
        return parts.size() - 1;
    }

    @Override
    public int params(String name) {
        paramStates.add(new ParamsState());
        return 1000 + paramsMade++;
    }

    @Override
    public int paramsAlignment() {
        return alignment;
    }

    private void way(int params, int way) {
        ParamsState s = paramsState(params);
        if (s.way != 0 && s.way != way) throw new IllegalStateException("upload and map on one params");
        s.way = way;
    }

    @Override
    public ParamsWriter map(int params, int length) {
        if (mapping) throw new IllegalStateException("a params buffer is mapped already");
        way(params, 2);
        ParamsState s = paramsState(params);
        if (s.bytes == null || s.bytes.length != length) {
            s.bytes = new byte[length];
            s.data = java.nio.ByteBuffer.allocateDirect(length).order(java.nio.ByteOrder.nativeOrder());
        }
        s.data.clear();
        MapWriter w = s.writer;
        w.owner = this; w.state = s; w.handle = params; w.length = length; w.data = s.data;
        mapping = true;
        return w;
    }

    @Override
    public int texture(String name, int width, int height) {
        textures.add(true);
        int h = 2000 + textures.size() - 1;
        texelsOf.put(h, new byte[width * height * 4]);
        textureSize.put(h, new int[] {width, height});
        if (record) log.add("texture " + h + " " + name + " " + width + " " + height);
        return h;
    }

    @Override
    public void texels(int texture, int x, int y, int width, int height, java.nio.ByteBuffer data) {
        if (!textures.get(texture - 2000)) throw new IllegalStateException("texture " + texture + " was freed");
        byte[] row = new byte[width * 4], all = texelsOf.get(texture);
        int w = textureSize.get(texture)[0];
        java.nio.ByteBuffer d = data.duplicate();
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        for (int r = 0; r < height; r++) {
            d.get(row);
            crc.update(row);
            System.arraycopy(row, 0, all, ((y + r) * w + x) * 4, row.length);
        }
        if (record) log.add("texels " + texture + " " + x + " " + y + " " + width + " " + height + " " + Long.toHexString(crc.getValue()));
    }

    @Override
    public boolean live(int texture) {
        if (texture == lose) {
            lose = -1;
            return false;
        }
        return texture >= 2000 && textures.get(texture - 2000);
    }

    @Override
    public void upload(int params, java.nio.ByteBuffer data, int length) {
        way(params, 1);
        ParamsState s = paramsState(params);
        if (s.bytes == null || s.bytes.length != length) s.bytes = new byte[length];
        uploaded = s.bytes;
        if (length < 0 || length > data.capacity()) throw new IndexOutOfBoundsException("upload");
        // Absolute access with the caller's limit ignored, as promised by RenderSink.
        if (length > data.limit()) data.duplicate().clear().get(uploaded, 0, length);
        else for (int i = 0; i < length; i++) uploaded[i] = data.get(i);
        if (record) {
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(uploaded);
            log.add("upload " + params + " " + length + " " + Long.toHexString(crc.getValue()));
        }
    }

    @Override
    public void drawInstanced(int material, int mesh, int params, int offset, int n) {
        Integer t = topologyOf.get(mesh);
        if (t != null) {
            if (firstTopology < 0) firstTopology = t;
            else if (firstTopology != t) throw new IllegalArgumentException("mesh " + mesh + ": one flush, one topology");
        }
        if (record) log.add("drawInstanced " + material + " " + mesh + " " + params + " " + offset + " " + n);
    }

    @Override
    public void flush() {
        firstTopology = -1;
        if (record) log.add("flush");
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
        bufferKnown(buffer);
        if (!buffers.get(buffer)) throw new IllegalStateException("freed buffer");
        if (first < 0 || count < 0 || (long) first + count > lengths.get(buffer)) throw new IndexOutOfBoundsException("buffer element");
        if (record) log.add("drawAt " + material + " " + buffer + " " + first + " " + count + " " + ox + " " + oy + " " + oz + " " + pull);
    }

    @Override
    public void queue(int material, int part, int room) {
        if (part <= 0 || part >= parts.size()) throw new IllegalArgumentException("unknown part");
        if (defer) { queued.add(new Queued(material, part, room)); return; }
        execute(material, part, room);
    }

    private void execute(int material, int part, int room) {
        Counter c = queueCounter;
        c.n = 0; c.open = true;
        c.layout = materials.get(material).layout();
        c.vertices = vertices;
        c.recorder = this;
        try { parts.get(part).write(c); }
        finally { c.open = false; }
        if (record) log.add("queue " + material + " " + part + " " + room + " -> " + c.n);
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
        int n, room, layout;
        boolean open;
        RecordingRenderPort owner;
        RecordingRenderPort recorder;
        List<Vertex> vertices;

        private void vertex(int expected, float x, float y, float z, int argb, float u, float v, int light, float nx, float ny, float nz) {
            if (!open) throw new IllegalStateException("writer after end");
            // The old contract uses an instanced material as a placeholder for a queued decal.
            if (layout != expected && layout != Layouts.MESH_ENTITY) throw new IllegalArgumentException("writer layout");
            n++;
            RecordingRenderPort p = owner == null ? recorder : owner;
            if (p != null && p.record) vertices.add(new Vertex(expected, bits(x), bits(y), bits(z), argb, bits(u), bits(v), light, bits(nx), bits(ny), bits(nz)));
        }

        private static int bits(float f) { return Float.floatToRawIntBits(f); }

        @Override public void color(float x, float y, float z, int argb) {
            vertex(Layouts.COLOR, x, y, z, argb, 0, 0, 0, 0, 0, 0);
        }

        @Override public void particle(float x, float y, float z, int argb, float u, float v, int light) {
            vertex(Layouts.PARTICLE, x, y, z, argb, u, v, light, 0, 0, 0);
        }

        @Override
        public void decal(float x, float y, float z, int argb, float u, float v, int light, float nx, float ny, float nz) {
            vertex(Layouts.DECAL, x, y, z, argb, u, v, light, nx, ny, nz);
        }

        @Override
        public int done() {
            if (!open) throw new IllegalStateException("end without begin");
            open = false;
            if (owner != null) {
                owner.filling = false;
                if (n > 0) {
                    owner.lengths.set(owner.fillingBuffer, n);
                    if (owner.record) {
                        List<Vertex> kept = owner.writtenOf.get(owner.fillingBuffer);
                        kept.clear(); kept.addAll(vertices);
                    }
                }
                if (owner.record) owner.log.add("fill " + owner.fillingBuffer + " " + room + " -> " + n);
            }
            return n;
        }
    }
}
