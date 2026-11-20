package acup.gunfeel.smoke.client;

import java.util.List;

import com.mojang.blaze3d.textures.GpuTextureView;

import acup.gunfeel.Config;
import acup.gunfeel.RenderPort26;
import acup.gunfeel.smoke.SmokeClouds;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Client: the drawn clouds' cells in one texture, for the raymarch. Blaze3D in 26.2 has no 3D textures, so a cloud's
 * layers (y) lie side by side in a row of tiles, one texel a cell, a cloud a row ("slot"); each tile has a border texel
 * of open, empty air, so the hardware's bilinear filter never reads the next tile.
 *
 * <p>A texel: R the density a tick ago and G now (the shader blends them by the partial tick, so the spreading is smooth
 * at 20 Hz), both times "open" and scaled by 1/{@link #SCALE}; B how lit the cell is; A 255 open, 0 closed. The shader
 * divides the filtered density by the filtered openness: a sample next to a wall takes only the open cells' smoke, so
 * there is no clear strip along a wall and no film through one. The slots, the rows sent and the texels are core's
 * ({@link SmokeAtlasSlots}, {@link SmokeAtlasTexels}); here the clouds to draw and the world's light.
 */
public final class SmokeAtlas {
    public static final int TILE_W = SmokeAtlasSlots.TILE_W, TILE_H = SmokeAtlasSlots.TILE_H;
    public static final int WIDTH = SmokeAtlasSlots.WIDTH, SLOTS = SmokeAtlasSlots.SLOTS, HEIGHT = SmokeAtlasSlots.HEIGHT;
    /** Densities up to this go in a byte. */
    public static final float SCALE = SmokeAtlasTexels.SCALE;
    private static boolean wired;
    // the light's block, one for the client's thread
    private static final BlockPos.MutableBlockPos LIT = new BlockPos.MutableBlockPos();

    private SmokeAtlas() {
    }

    private static void wire() {
        if (wired) return;
        wired = true;
        SmokeAtlasSlots.init(RenderPort26.INSTANCE, new SmokeAtlasSlots.Host() {
            @Override public float brightness(int x, int y, int z) {
                return SmokeLight.brightness(Minecraft.getInstance().level, LIT.set(x, y, z));
            }
            @Override public void beginWrites() { RenderPort26.INSTANCE.beginTexels(); }
            @Override public void endWrites() { RenderPort26.INSTANCE.endTexels(); }
        });
    }

    public static GpuTextureView view() {
        return RenderPort26.INSTANCE.textureView(SmokeAtlasSlots.texture());
    }

    /** The clouds in the atlas now, nearest first. */
    public static List<SmokeAtlasSlots.Slot> drawn() {
        return SmokeAtlasSlots.drawn();
    }

    /**
     * Every client tick after the clouds stepped: picks the nearest clouds to draw (up to smokeMaxClouds, within
     * smokeDistance, none still catching up) and sends the tiles of those that changed.
     */
    public static void update() {
        wire();
        SmokeClouds all = SmokeClient.current();
        var mc = Minecraft.getInstance();
        if (all == null || all.isEmpty() || mc.level == null || !SmokeRender.wanted()) {
            SmokeAtlasSlots.none();
            return;
        }
        long t0 = System.nanoTime();
        SmokeAtlasSlots.ensure();
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        long time = mc.level.getGameTime();
        SmokeAtlasSlots.update(SmokeClient.nearest(all, cam, time, Config.smokeDistance, Math.min(SLOTS, Config.smokeMaxClouds)));
        SmokeStats.add(SmokeStats.ATLAS, System.nanoTime() - t0);
    }

    /** Frees the atlas and its upload buffer; the next cloud to draw makes them again. */
    public static void close() {
        wire();
        SmokeAtlasSlots.close();
    }
}
