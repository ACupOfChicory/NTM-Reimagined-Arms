package acup.gunfeel.smoke.client;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import acup.gunfeel.Config;
import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeGrid;
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
 * there is no clear strip along a wall and no film through one. The texels are core's ({@link SmokeAtlasTexels});
 * here the texture, the light looked up and the rows sent.
 */
public final class SmokeAtlas {
    public static final int TILE_W = SmokeAtlasTexels.TILE_W, TILE_H = SmokeAtlasTexels.TILE_H;
    public static final int WIDTH = SmokeAtlasTexels.WIDTH, SLOTS = 16, HEIGHT = TILE_H * SLOTS;
    /** Densities up to this go in a byte. */
    public static final float SCALE = SmokeAtlasTexels.SCALE;
    private static final int SLOT_BYTES = WIDTH * TILE_H * 4;

    /** A cloud being drawn: its slot, and the atlas texels last sent. */
    public static final class Slot {
        final int index;
        final byte[] now = new byte[SmokeGrid.CELLS];
        final byte[] light = new byte[SmokeGrid.CELLS];
        int lastAge = -1, lightLayer;
        // the grid's shift the texels are for: a box that moved downwind moves them along
        int shiftX, shiftZ;
        // tile rows (a row a z of the grid, with the border) of the box last tick and the tick before: a row the cloud
        // left is sent twice more, its "now" and then its "a tick ago" emptied
        int rowLo = 0, rowHi = TILE_H - 1, boxLo = 0, boxHi = TILE_H - 1;
        SmokeClouds.Cloud cloud;

        Slot(int index) {
            this.index = index;
        }

        public int index() {
            return index;
        }

        public SmokeClouds.Cloud cloud() {
            return cloud;
        }
    }

    private static final Slot[] SLOT = new Slot[SLOTS];
    private static final Map<Integer, Slot> BY_CLOUD = new HashMap<>();
    private static GpuTexture texture;
    private static GpuTextureView view;
    private static ByteBuffer upload;
    private static final List<Slot> DRAWN = new ArrayList<>();

    private SmokeAtlas() {
    }

    public static GpuTextureView view() {
        return view;
    }

    /** The clouds in the atlas now, nearest first. */
    public static List<Slot> drawn() {
        return DRAWN;
    }

    private static void ensure() {
        if (texture != null && !texture.isClosed()) return;
        // what is left of an atlas that was closed under us: the buffer and the view go too
        close();
        var device = RenderSystem.getDevice();
        texture = device.createTexture(() -> "Smoke atlas", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM,
                WIDTH, HEIGHT, 1, 1);
        view = device.createTextureView(texture);
        upload = MemoryUtil.memAlloc(SLOT_BYTES);
    }

    /**
     * Every client tick after the clouds stepped: picks the nearest clouds to draw (up to smokeMaxClouds, within
     * smokeDistance, none still catching up) and sends the tiles of those that changed.
     */
    public static void update() {
        DRAWN.clear();
        SmokeClouds all = SmokeClient.current();
        var mc = Minecraft.getInstance();
        if (all == null || all.isEmpty() || mc.level == null || !SmokeRender.wanted()) {
            if (!BY_CLOUD.isEmpty()) {
                BY_CLOUD.clear();
                java.util.Arrays.fill(SLOT, null);
            }
            return;
        }
        long t0 = System.nanoTime();
        ensure();
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        long time = mc.level.getGameTime();
        List<SmokeClouds.Cloud> near = SmokeClient.nearest(all, cam, time, Config.smokeDistance, Math.min(SLOTS, Config.smokeMaxClouds));
        // free the slots of clouds no longer drawn, then give the new ones a slot
        java.util.Set<Integer> keep = new java.util.HashSet<>();
        for (SmokeClouds.Cloud c : near) keep.add(c.id);
        BY_CLOUD.entrySet().removeIf(e -> {
            if (keep.contains(e.getKey())) return false;
            SLOT[e.getValue().index] = null;
            return true;
        });
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        for (SmokeClouds.Cloud c : near) {
            Slot s = BY_CLOUD.get(c.id);
            if (s == null || s.cloud != c) {
                int free = -1;
                if (s != null) free = s.index;
                for (int i = 0; i < SLOTS && free < 0; i++) if (SLOT[i] == null) free = i;
                if (free < 0) continue;
                s = new Slot(free);
                s.cloud = c;
                SLOT[free] = s;
                BY_CLOUD.put(c.id, s);
            }
            DRAWN.add(s);
            if (s.lastAge == c.grid.age()) continue;
            boolean first = s.lastAge < 0;
            // the box moved downwind: what was sent and the light go along with the cells, and every row is sent
            int mx = c.grid.shiftX() - s.shiftX, mz = c.grid.shiftZ() - s.shiftZ;
            boolean moved = !first && (mx != 0 || mz != 0);
            if (moved) {
                SmokeAtlasTexels.move(s.now, mx, mz, (byte) 0);
                SmokeAtlasTexels.move(s.light, mx, mz, s.light[SmokeGrid.index(SmokeGrid.OX, c.grid.oy(), SmokeGrid.OZ)]);
            }
            s.shiftX = c.grid.shiftX();
            s.shiftZ = c.grid.shiftZ();
            // the light: at first the origin's for every cell, then a layer of the box every 4th tick (light changes
            // slowly, and the client's light lookups are what this costs most)
            if (first) seedLight(s, c);
            if (first || (c.grid.age() + s.index) % 4 == 0) light(s, c, s.lightLayer++);
            // only the rows the cloud is in now or was in last time: the rest stay empty in the texture
            int[] box = c.grid.box();
            boolean whole = first || moved;
            int lo = whole ? 0 : Math.min(s.rowLo, box[2]), hi = whole ? TILE_H - 1 : Math.max(s.rowHi, Math.min(TILE_H - 1, box[5] + 2));
            fill(s, c, first, lo, hi);
            s.lastAge = c.grid.age();
            int bl = box[2], bh = Math.min(TILE_H - 1, box[5] + 2);
            s.rowLo = Math.min(bl, s.boxLo);
            s.rowHi = Math.max(bh, s.boxHi);
            s.boxLo = bl;
            s.boxHi = bh;
            upload.position(lo * WIDTH * 4).limit((hi + 1) * WIDTH * 4);
            encoder.writeToTexture(texture, upload, 0, 0, 0, s.index * TILE_H + lo, WIDTH, hi - lo + 1);
        }
        SmokeStats.add(SmokeStats.ATLAS, System.nanoTime() - t0);
    }

    // every cell as lit as the cloud's origin, until its layer is looked up
    private static void seedLight(Slot s, SmokeClouds.Cloud c) {
        byte v = (byte) Math.round(SmokeLight.brightness(Minecraft.getInstance().level, c.origin) * 255F);
        java.util.Arrays.fill(s.light, v);
    }

    // how lit one layer of the box (and one cell around it) is; the layers counted round the box
    private static void light(Slot s, SmokeClouds.Cloud c, int layer) {
        var level = Minecraft.getInstance().level;
        int[] b = c.grid.box();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int y0 = Math.max(0, b[1] - 1), y1 = Math.min(SmokeGrid.SY - 1, b[4] + 1);
        int z0 = Math.max(0, b[2] - 1), z1 = Math.min(SmokeGrid.SZ - 1, b[5] + 1);
        int x0 = Math.max(0, b[0] - 1), x1 = Math.min(SmokeGrid.SX - 1, b[3] + 1);
        y0 = y1 = y0 + Math.floorMod(layer, y1 - y0 + 1);
        for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++)
                for (int x = x0; x <= x1; x++) {
                    p.set(c.baseX() + x, c.baseY() + y, c.baseZ() + z);
                    s.light[SmokeGrid.index(x, y, z)] = (byte) Math.round(SmokeLight.brightness(level, p) * 255F);
                }
    }

    // the slot's texels into the upload buffer: the old "now" becomes "a tick ago"
    private static void fill(Slot s, SmokeClouds.Cloud c, boolean first, int lo, int hi) {
        SmokeAtlasTexels.fill(c.grid, s.now, s.light, first, lo, hi, upload);
    }

    /** Frees the atlas and its upload buffer; the next cloud to draw makes them again. */
    public static void close() {
        if (view != null) view.close();
        if (texture != null && !texture.isClosed()) texture.close();
        if (upload != null) MemoryUtil.memFree(upload);
        view = null;
        texture = null;
        upload = null;
        BY_CLOUD.clear();
        java.util.Arrays.fill(SLOT, null);
        DRAWN.clear();
    }
}
