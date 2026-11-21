package acup.gunfeel;

import acup.gunfeel.host.render.*;

/** Each queued submission owns its data until its Part runs or the next frame starts. */
public final class TracerRibbons {
    /** Maximum busy submissions per frame, not the number of tracers. */
    public static final int SLOTS = 256;
    public static final MaterialSpec MATERIAL = new MaterialSpec("debug_quads", "position_color", Layouts.COLOR,
            Blend.TRANSLUCENT, 0, true, false, 0, 0, false, null, false, false);

    public interface Host {
        float width();
        long start();
        void drawn(int ribbons, long started);
        /** Called once per renderer on its first skipped submission. */
        void overflow();
    }

    private final Batch[] slots = new Batch[SLOTS];
    private final Host host;
    private final int material;
    private boolean overflowed;
    private long frame;

    public TracerRibbons(RenderPort port, Host host) {
        this.host = host;
        material = port.material(MATERIAL);
        for (int i = 0; i < slots.length; i++) slots[i] = new Batch(port);
    }

    /**
     * Returns older frames' slots; repeated collection in the same rendered frame keeps its queued Parts alive.
     * A callback arriving after its slot was released remains an error (not possible in vanilla).
     */
    public void beginFrame(long frame) {
        this.frame = frame;
        for (Batch b : slots) if (b.busy && b.frame < frame) b.release();
    }

    /** Null when all 256 submission slots of this frame are busy; queued Parts keep their data. */
    public Batch begin() {
        for (Batch b : slots) if (!b.busy) {
            b.busy = true;
            b.frame = frame;
            b.count = 0;
            return b;
        }
        if (!overflowed) {
            overflowed = true;
            host.overflow();
        }
        return null;
    }

    public final class Batch implements Part, Drawer {
        private final int part;
        private float[][] ribbons = new float[16][9];
        private final TracerGeometry.Scratch scratch = new TracerGeometry.Scratch();
        private int count;
        private long frame;
        private boolean busy, queued;

        private Batch(RenderPort port) { part = port.part(this); }

        public void add(Vec3d head, Vec3d tail, Vec3d cam, int light, int dark, float pixel) {
            add(head.x(), head.y(), head.z(), tail.x(), tail.y(), tail.z(), cam.x(), cam.y(), cam.z(), light, dark, pixel);
        }

        public void add(double hx, double hy, double hz, double tx, double ty, double tz,
                double cx, double cy, double cz, int light, int dark, float pixel) {
            if (!busy || queued) throw new IllegalStateException("tracer slot already submitted or released");
            if (count == ribbons.length) {
                int n = ribbons.length;
                ribbons = java.util.Arrays.copyOf(ribbons, n * 2);
                for (int i = n; i < ribbons.length; i++) ribbons[i] = new float[9];
            }
            TracerGeometry.ribbon(ribbons[count++], hx, hy, hz, tx, ty, tz, cx, cy, cz, light, dark, pixel);
        }

        @Override public void draw(RenderSink sink) {
            if (!busy || queued) throw new IllegalStateException("tracer slot submitted twice");
            if (count == 0) { busy = false; return; }
            queued = true;
            try { sink.queue(material, part, count * 8); }
            catch (Throwable t) { release(); throw t; }
        }

        @Override public void write(VertexWriter out) {
            if (!queued) throw new IllegalStateException("tracer part without submission");
            try {
                long started = host.start();
                for (int i = 0; i < count; i++) TracerGeometry.streak(out, ribbons[i], host.width(), scratch);
                host.drawn(count, started);
            } finally { release(); }
        }

        private void release() { busy = false; queued = false; count = 0; }
    }
}
