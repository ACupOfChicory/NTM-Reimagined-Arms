package acup.gunfeel.gl54;

import acup.gunfeel.host.render.*;

/** Preallocated Parts keep each player's or item's inputs until drawing or the next frame. */
public final class Gl54LaserRenderer {
    /** Maximum busy submissions per frame, not the number of beams. */
    public static final int SLOTS = 256;
    public static final MaterialSpec WORLD = new MaterialSpec("debug_quads", "position_color", Layouts.COLOR,
            Blend.TRANSLUCENT, 0, true, false, 0, 0, false, null, false, false);
    public static final MaterialSpec ITEM = new MaterialSpec("ntmgunfeel_gl54_laser_item", "entity", Layouts.DECAL,
            Blend.TRANSLUCENT, 0.1F, true, true, 0, 0, false, null, true, true);
    private final Batch[] slots = new Batch[SLOTS];
    private final int world, item;
    private final Runnable overflow;
    private boolean overflowed;
    private long frame;

    public Gl54LaserRenderer(RenderPort port, Runnable overflow) {
        world = port.material(WORLD);
        item = port.material(ITEM);
        this.overflow = overflow;
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

    /** Busy slots of this frame are never overwritten; overflow is reported once per renderer. */
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
        public final Gl54LaserGeometry.Line line = new Gl54LaserGeometry.Line();
        private final int part;
        private long frame;
        private boolean busy, queued, bound, inWorld;
        private double x, y, z;
        private double[] lens;
        private float scale;

        private Batch(RenderPort port) { part = port.part(this); }

        private void ready() {
            if (!busy || queued) throw new IllegalStateException("laser slot already submitted or released");
        }

        public void world(double camX, double camY, double camZ) {
            ready();
            x = camX; y = camY; z = camZ; inWorld = true; bound = true;
        }

        /** Keep the original lens array until drawing, as the old item callback did. */
        public void item(float scale, double[] lens) {
            ready();
            this.scale = scale;
            this.lens = lens;
            inWorld = false; bound = true;
        }

        @Override public void draw(RenderSink sink) {
            if (!busy || queued || !bound) throw new IllegalStateException("laser slot is not ready");
            queued = true;
            try { sink.queue(inWorld ? world : item, part, 128); }
            catch (Throwable t) { release(); throw t; }
        }

        @Override public void write(VertexWriter out) {
            if (!queued) throw new IllegalStateException("laser part without submission");
            try {
                if (inWorld) Gl54LaserGeometry.beam(out, line, x, y, z);
                else Gl54LaserGeometry.item(out, scale, lens[0], lens[1], lens[2]);
            } finally { release(); }
        }

        private void release() {
            busy = queued = bound = false;
            line.start = line.dir = line.side = line.up = null;
            lens = null;
        }
    }
}
