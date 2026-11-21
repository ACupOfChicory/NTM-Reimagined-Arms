package acup.gunfeel.gl54;

import org.joml.Vector3f;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.item.ItemStack;

// The GL-54's laser (design.md): while the gun is aimed, a thin red beam leaves the rangefinder's lens (the point the model
// gives, Gl54Reflex.laser) and goes forward along the barrel, fading out over a couple of blocks. Two crossed ribbons so it
// shows from any side, full bright and clipped by nothing; the same in the hand and on the gun the player carries in the
// world. The idea is the laser module of TaCZ (GPL), written again here: nothing of its code or assets is used.
public final class Gl54Laser {
    // how far the beam reaches before it is gone, blocks; its width, blocks
    public static final double LENGTH_BLOCKS = Gl54LaserGeometry.LENGTH_BLOCKS, WIDTH_BLOCKS = Gl54LaserGeometry.WIDTH_BLOCKS;
    public static final int SEGMENTS = Gl54LaserGeometry.SEGMENTS;

    // dev (Gl54ReflexStand): off, nothing is drawn, for a frame to set the beam's against
    public static volatile boolean ON = true;

    private Gl54Laser() {
    }

    private static boolean drawingUsed;

    /** Release older submissions before collecting a view, using the shared rendered-frame number. */
    public static void beginFrame() {
        if (drawingUsed) Drawing.RENDERER.beginFrame(acup.gunfeel.RenderPort26.frameNumber());
    }

    // The version keeps the aim, lens and model pose; core writes into the same deferred queue.
    public static void render(SubmitNodeCollector collector, PoseStack pose, ItemStack stack) {
        Gl54Reflex r = Gl54Reflex.get();
        if (!ON || !r.present() || !ItemGunBaseNT.getIsAiming(stack)) return;
        float scale = pose.last().pose().transformDirection(new Vector3f(0, 0, 1)).length();
        if (!Gl54LaserGeometry.visibleScale(scale)) return;
        double[] e = r.laser();
        var batch = Drawing.RENDERER.begin();
        if (batch == null) return;
        batch.item(scale, e);
        acup.gunfeel.RenderPort26.submitAt(collector, pose, batch);
    }

    static final class Drawing {
        static final Gl54LaserRenderer RENDERER = new Gl54LaserRenderer(acup.gunfeel.RenderPort26.INSTANCE,
                () -> acup.gunfeel.GunFeel.LOGGER.warn("All 256 GL-54 laser submission slots of this frame are busy; skipping laser submission"));
        static {
            RENDERER.beginFrame(acup.gunfeel.RenderPort26.frameNumber());
            drawingUsed = true;
        }
    }

    /** 26.2's NTM bindings stay under GL54's compatibility gate. */
    public static final class Native {
        private Native() {}

        public static net.minecraft.client.renderer.rendertype.RenderType material() {
            return net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(com.hbm.client.render.RenderTextures.WHITE);
        }

        public static void emit(com.mojang.blaze3d.vertex.VertexConsumer vc, PoseStack.Pose pose,
                float x, float y, float z, int argb, float u, float v, int light, float nx, float ny, float nz) {
            com.hbm.render.util.Vertices.emit(vc, pose, x, y, z, argb, u, v, light, nx, ny, nz);
        }
    }

    public static int color(double strength) {
        return Gl54LaserGeometry.color(strength);
    }
}
