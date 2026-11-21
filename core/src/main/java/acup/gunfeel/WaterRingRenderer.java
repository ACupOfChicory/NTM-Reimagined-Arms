package acup.gunfeel;

import acup.gunfeel.host.render.*;

/** Each deferred part keeps its own surface host, atlas coordinates and camera until it is drawn or the next frame starts. */
public final class WaterRingRenderer {
    /** Maximum busy submissions per frame, not the number of rings. */
    public static final int SLOTS = 256;
    public static final MaterialSpec MATERIAL = new MaterialSpec("ntmgunfeel_water_ring", "particle", Layouts.PARTICLE,
            Blend.TRANSLUCENT, 0, true, true, 0, 0, true, "minecraft:textures/atlas/particles.png", true, false);
    private final WaterRingGeometry geometry;
    private final Batch[] slots = new Batch[SLOTS];
    private final int material;
    private final java.util.function.IntConsumer released;
    private final Runnable overflow;
    private boolean overflowed;
    private long frame;

    public WaterRingRenderer(RenderPort port, WaterRingGeometry geometry) {
        this(port, geometry, null);
    }

    /** The version may drop a slot's world reference after drawing or a failed queue operation. */
    public WaterRingRenderer(RenderPort port, WaterRingGeometry geometry, java.util.function.IntConsumer released) {
        this(port, geometry, released, null);
    }

    /** The overflow callback runs once per renderer, on its first skipped submission. */
    public WaterRingRenderer(RenderPort port, WaterRingGeometry geometry, java.util.function.IntConsumer released,
            Runnable overflow) {
        this.geometry = geometry;
        this.released = released;
        this.overflow = overflow;
        material = port.material(MATERIAL);
        for (int i = 0; i < slots.length; i++) slots[i] = new Batch(port, i);
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
            return b;
        }
        if (!overflowed) {
            overflowed = true;
            if (overflow != null) overflow.run();
        }
        return null;
    }

    public final class Batch implements Drawer, Part {
        private final int part, slot;
        private WaterRingGeometry.Host host;
        private double camX, camY, camZ;
        private float u0, u1, v0, v1;
        private long frame;
        private boolean busy, queued, bound;

        private Batch(RenderPort port, int slot) {
            this.slot = slot;
            part = port.part(this);
        }

        public int slot() { return slot; }

        public void bind(WaterRingGeometry.Host host, double camX, double camY, double camZ,
                float u0, float u1, float v0, float v1) {
            if (!busy || queued) throw new IllegalStateException("water ring slot already submitted or released");
            this.host = host;
            this.camX = camX; this.camY = camY; this.camZ = camZ;
            this.u0 = u0; this.u1 = u1; this.v0 = v0; this.v1 = v1;
            bound = true;
        }

        @Override public void draw(RenderSink sink) {
            if (!busy || queued || !bound) throw new IllegalStateException("water ring slot is not ready");
            queued = true;
            try { sink.queue(material, part, 0); }
            catch (Throwable t) { release(); throw t; }
        }

        @Override public void write(VertexWriter out) {
            if (!queued) throw new IllegalStateException("water ring part without submission");
            try {
                // Like the original callback, read the live pool when the game executes the queue.
                for (WaterRingGeometry.Ring d : geometry.rings)
                    if (d.on && d.t >= d.delay)
                        geometry.draw(host, out, d, u0, u1, v0, v1, camX, camY, camZ);
            } finally { release(); }
        }

        private void release() {
            if (!busy) return;
            busy = queued = bound = false;
            host = null;
            if (released != null) released.accept(slot);
        }
    }
}
