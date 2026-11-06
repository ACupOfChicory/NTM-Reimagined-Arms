package acup.gunfeel.smoke.client;

import acup.gunfeel.smoke.SmokeClouds;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * The port's copy of the root's SmokeFog (notes/ports/de-1.21.1.md, "Дым"): 1.21.1's fog event has one near and far
 * plane per fog (terrain, sky) instead of 26.2's FogData, and the screen layer draws on GuiGraphics.
 *
 * <p>Client: inside a cloud the player does not see out. The world's fog closes in to a block and a half and takes the
 * smoke's colour (every vanilla shader draws the fog, so this holds whatever draws the smoke itself); and where the
 * volume is not drawn (smokeQuality off, a shader pack, the raymarch failed) a grey layer over the screen, as a carved
 * pumpkin's, which no shader pack touches.
 */
public final class SmokeFog {
    private static float density, shown;
    private static int rgb = 0xB4B6B8;
    private static long frameStamp;
    private static float light = 1F;

    private SmokeFog() {
    }

    /** The smoke density at the camera, eased over a few frames (0 = clear, 0.7 = dense smoke). */
    public static float atCamera() {
        return shown;
    }

    // once a frame: the density where the camera is
    public static void frame(Camera camera, float frameSeconds) {
        SmokeClouds all = SmokeClient.current();
        float d = 0F;
        var level = Minecraft.getInstance().level;
        if (all != null && !all.isEmpty() && level != null) {
            Vec3 p = camera.getPosition();
            // one pass over the clouds: their smoke added up, and the colour of the one with most; a cloud still
            // catching up is not the server's yet
            SmokeClouds.Cloud densest = null;
            float most = 0F;
            long time = level.getGameTime();
            for (SmokeClouds.Cloud c : all.clouds()) {
                if (!c.grid.occupied() || c.catchingUp(time) || !c.holds(p.x, p.y, p.z)) continue;
                float v = c.density(p.x, p.y, p.z);
                d += v;
                if (v > most) {
                    most = v;
                    densest = c;
                }
            }
            if (densest != null) rgb = densest.kind().rgb();
            if (d > 0F) light = SmokeLight.brightness(level, BlockPos.containing(p));
        }
        density = d;
        // in fast, out a little slower (the eye adjusts)
        float rate = d > shown ? 12F : 6F;
        shown += (density - shown) * Math.min(1F, rate * Math.max(0.001F, frameSeconds));
        if (shown < 0.002F) shown = 0F;
        frameStamp++;
    }

    // how much the fog closes in: none below 0.05, all of it from 0.45
    private static float amount() {
        return Math.clamp((shown - 0.05F) / 0.4F, 0F, 1F);
    }

    // 26.2 closes each of its fogs (environment, render distance, sky, clouds) in to 1.5 blocks; 1.21.1 fires this once
    // for the terrain and once for the sky, each with its own planes, and takes them only from a cancelled event
    public static void fog(ViewportEvent.RenderFog e) {
        float k = amount();
        if (k <= 0F) return;
        float near = e.getNearPlaneDistance(), far = e.getFarPlaneDistance();
        e.setNearPlaneDistance(Math.min(near, lerp(near, 0F, k)));
        e.setFarPlaneDistance(Math.min(far, lerp(far, 1.5F, k)));
        e.setCanceled(true);
    }

    public static void color(ViewportEvent.ComputeFogColor e) {
        float k = amount();
        if (k <= 0F) return;
        float l = 0.15F + 0.85F * light;
        e.setRed(lerp(e.getRed(), (rgb >> 16 & 255) / 255F * l, k));
        e.setGreen(lerp(e.getGreen(), (rgb >> 8 & 255) / 255F * l, k));
        e.setBlue(lerp(e.getBlue(), (rgb & 255) / 255F * l, k));
    }

    /** Whether the grey screen layer is needed: the volume is not drawn now. */
    static boolean layerNeeded() {
        return !SmokeRender.drawingVolume();
    }

    public static void layer(GuiGraphics g, DeltaTracker delta) {
        if (shown <= 0.05F || !layerNeeded()) return;
        float a = Math.clamp((shown - 0.05F) / 0.35F, 0F, 1F) * 0.97F;
        float l = 0.15F + 0.85F * light;
        int r = Math.round((rgb >> 16 & 255) * l), gr = Math.round((rgb >> 8 & 255) * l), b = Math.round((rgb & 255) * l);
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), Math.round(a * 255) << 24 | r << 16 | gr << 8 | b);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
