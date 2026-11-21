package acup.gunfeel.water;

import acup.gunfeel.RenderPort26;
import acup.gunfeel.GunFeel;
import acup.gunfeel.WaterRingGeometry;
import acup.gunfeel.WaterRingRenderer;
import acup.gunfeel.host.Hook;
import acup.gunfeel.host.HookPoint;
import acup.gunfeel.host.render.Intent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/** The water and atlas adapter; the ring arithmetic and geometry belong to core. */
public final class WaterRings {
    private WaterRings() {}

    static final int MAX = WaterRingGeometry.MAX;
    private static final WaterRingGeometry RINGS = new WaterRingGeometry();
    private static final SurfaceHost UPDATE = new SurfaceHost();
    public static int made, leftWater, widest;
    private static long lastNanos;

    public static void add(Level level, double x, double y, double z, double delay, double life, double s0, double s1, float a, boolean adv) {
        if (!WaterFx.on()) return;
        // The existing stand may reset these counters between scenes.
        RINGS.made = made;
        RINGS.widest = widest;
        RINGS.add(x, y, z, delay, life, s0, s1, a, adv);
        made = RINGS.made;
        widest = RINGS.widest;
    }

    public static int alive() { return RINGS.alive(); }

    public static void clear() { RINGS.clear(); }

    private static void update(Level level, double dt) {
        UPDATE.level = level;
        RINGS.leftWater = leftWater;
        try { RINGS.update(UPDATE, dt, WaterParticle.FLOW_SPEED); }
        finally { UPDATE.level = null; leftWater = RINGS.leftWater; }
    }

    @Hook(HookPoint.WATER_DRAW)
    public static void submit(SubmitCustomGeometryEvent event, Level level, Vec3 cam) {
        // Only older frames return slots; another view in this frame keeps its queued Parts alive.
        if (drawingUsed) Drawing.RENDERER.beginFrame(RenderPort26.frameNumber());
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : Math.clamp((now - lastNanos) / 1e9, 0, 0.1);
        lastNanos = now;
        if (Minecraft.getInstance().isPaused()) dt = 0;
        if (alive() == 0) return;
        update(level, dt);
        if (!drawingUsed) Drawing.RENDERER.beginFrame(RenderPort26.frameNumber());
        drawingUsed = true;
        var batch = Drawing.RENDERER.begin();
        if (batch == null) return;
        TextureAtlasSprite sprite = WaterParticle.sprite("ntmgunfeel:water_ring");
        SurfaceHost host = Drawing.HOSTS[batch.slot()];
        host.level = level;
        batch.bind(host, cam.x, cam.y, cam.z, sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1());
        RenderPort26.submitAt(event, cam, now, Intent.AFTER_TERRAIN, batch);
    }

    /** Set once the renderer exists, so a frame without rings does not make its 256 Parts. */
    private static boolean drawingUsed;

    // Allocated once, never a new host, Part or callback for a frame.
    private static final class Drawing {
        static final SurfaceHost[] HOSTS = new SurfaceHost[WaterRingRenderer.SLOTS];
        static final WaterRingRenderer RENDERER;
        static {
            for (int i = 0; i < HOSTS.length; i++) HOSTS[i] = new SurfaceHost();
            RENDERER = new WaterRingRenderer(RenderPort26.INSTANCE, RINGS, slot -> HOSTS[slot].level = null,
                    () -> GunFeel.LOGGER.warn("All 256 water ring submission slots of this frame are busy; skipping water ring submission"));
        }
    }

    static class SurfaceHost implements WaterRingGeometry.Host {
        Level level;
        private final BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();

        @Override public WaterSurface.Info surface(double x, double y, double z) {
            return WaterSurface.at(level, x, y, z, false);
        }

        @Override public void connected(int cx, int cz, double y, double[][] allow, int span) {
            WaterSurface.connected(level, cx, cz, y, allow, span);
        }

        @Override public int light(int x, int y, int z) {
            return LightCoordsUtil.getLightCoords(lightLevel(), cell.set(x, y, z));
        }

        /** The level light is read from; a test reads the asked cell through it. */
        net.minecraft.world.level.BlockAndLightGetter lightLevel() { return level; }
    }
}
