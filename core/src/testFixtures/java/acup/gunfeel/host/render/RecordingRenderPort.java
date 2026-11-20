package acup.gunfeel.host.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A render port that draws nothing and keeps what it was asked: core's tests run their drawers through it ({@link #run})
 * at a frame they set, and read {@link #log}. Lines: {@code fill <buffer> <room> -> <elements>}, {@code drawAt <material>
 * <buffer> <first> <count> <ox> <oy> <oz> <pull>}, {@code queue <material> <part> <room> -> <elements>} (a queued part is
 * written at once), {@code free <buffer>}.
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
        buffers.set(buffer, false);
        log.add("free " + buffer);
    }

    @Override
    public int part(Part part) {
        parts.add(part);
        return parts.size() - 1;
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
