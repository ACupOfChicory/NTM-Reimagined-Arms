package acup.gunfeel.smoke.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import acup.gunfeel.host.render.RenderPort;
import acup.gunfeel.smoke.SmokeCloud;
import acup.gunfeel.smoke.SmokeGrid;

/**
 * The drawn clouds' slots in the smoke atlas, and the texels sent to it: a cloud a slot (a row of tiles, see
 * {@link SmokeAtlasTexels}), the rows of the tiles a cloud is in or has just left sent each tick its grid stepped, the
 * light looked up a layer at a time. The texture is the version's ({@link RenderPort#texture}); so is the choice of
 * the clouds to draw (nearest first, the version's {@code SmokeClient.nearest}) and the world's light ({@link Host}).
 * The client's thread only.
 */
public final class SmokeAtlasSlots {
    /** what the version does for the atlas */
    public interface Host {
        /** how lit the world block is, 0..1 (the version's curve of its light level) */
        float brightness(int x, int y, int z);
        default void beginWrites() {}
        default void endWrites() {}
    }

    public static final int TILE_W = SmokeAtlasTexels.TILE_W, TILE_H = SmokeAtlasTexels.TILE_H;
    public static final int WIDTH = SmokeAtlasTexels.WIDTH, SLOTS = 16, HEIGHT = TILE_H * SLOTS;
    static final int SLOT_BYTES = WIDTH * TILE_H * 4;

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
        SmokeCloud cloud;

        Slot(int index) {
            this.index = index;
        }

        public int index() {
            return index;
        }

        public SmokeCloud cloud() {
            return cloud;
        }
    }

    private static RenderPort port = RenderPort.MISSING;
    private static Host host;
    private static final Slot[] SLOT = new Slot[SLOTS];
    private static final Map<Integer, Slot> BY_CLOUD = new HashMap<>();
    private static int texture = RenderPort.NONE;
    private static ByteBuffer upload;
    private static final List<Slot> DRAWN = new ArrayList<>();

    private SmokeAtlasSlots() {
    }

    /** once: the version's port and light */
    public static void init(RenderPort renderPort, Host versionHost) {
        port = renderPort;
        host = versionHost;
    }

    /** the atlas's texture ({@link RenderPort#NONE}: none yet) */
    public static int texture() {
        return texture;
    }

    /** The clouds in the atlas now, nearest first. */
    public static List<Slot> drawn() {
        return DRAWN;
    }

    public static void ensure() {
        if (texture != RenderPort.NONE && port.live(texture)) return;
        // what is left of an atlas that was closed under us: the buffer goes too
        close();
        texture = port.texture("Smoke atlas", WIDTH, HEIGHT);
        upload = ByteBuffer.allocateDirect(SLOT_BYTES).order(ByteOrder.nativeOrder());
    }

    /** No cloud to draw this tick (none, no level, the volume not wanted): every slot forgotten. */
    public static void none() {
        DRAWN.clear();
        if (!BY_CLOUD.isEmpty()) {
            BY_CLOUD.clear();
            java.util.Arrays.fill(SLOT, null);
        }
    }

    /**
     * Every client tick after the clouds stepped, with the clouds to draw (nearest first, at most {@link #SLOTS} of them
     * get a slot): sends the tiles of those that changed.
     */
    public static void update(List<? extends SmokeCloud> near) {
        DRAWN.clear();
        ensure();
        // free the slots of clouds no longer drawn, then give the new ones a slot
        java.util.Set<Integer> keep = new java.util.HashSet<>();
        for (SmokeCloud c : near) keep.add(c.id);
        BY_CLOUD.entrySet().removeIf(e -> {
            if (keep.contains(e.getKey())) return false;
            SLOT[e.getValue().index] = null;
            return true;
        });
        host.beginWrites();
        try {
            for (SmokeCloud c : near) {
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
                SmokeAtlasTexels.fill(c.grid, s.now, s.light, first, lo, hi, upload);
                s.lastAge = c.grid.age();
                int bl = box[2], bh = Math.min(TILE_H - 1, box[5] + 2);
                s.rowLo = Math.min(bl, s.boxLo);
                s.rowHi = Math.max(bh, s.boxHi);
                s.boxLo = bl;
                s.boxHi = bh;
                upload.position(lo * WIDTH * 4).limit((hi + 1) * WIDTH * 4);
                port.texels(texture, 0, s.index * TILE_H + lo, WIDTH, hi - lo + 1, upload);
            }
        } finally {
            host.endWrites();
        }
    }

    // every cell as lit as the cloud's origin, until its layer is looked up
    private static void seedLight(Slot s, SmokeCloud c) {
        byte v = (byte) Math.round(host.brightness(c.originX, c.originY, c.originZ) * 255F);
        java.util.Arrays.fill(s.light, v);
    }

    // how lit one layer of the box (and one cell around it) is; the layers counted round the box
    private static void light(Slot s, SmokeCloud c, int layer) {
        int[] b = c.grid.box();
        int y0 = Math.max(0, b[1] - 1), y1 = Math.min(SmokeGrid.SY - 1, b[4] + 1);
        int z0 = Math.max(0, b[2] - 1), z1 = Math.min(SmokeGrid.SZ - 1, b[5] + 1);
        int x0 = Math.max(0, b[0] - 1), x1 = Math.min(SmokeGrid.SX - 1, b[3] + 1);
        y0 = y1 = y0 + Math.floorMod(layer, y1 - y0 + 1);
        for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++)
                for (int x = x0; x <= x1; x++)
                    s.light[SmokeGrid.index(x, y, z)] = (byte) Math.round(host.brightness(c.baseX() + x, c.baseY() + y, c.baseZ() + z) * 255F);
    }

    /** Frees the atlas and its upload buffer; the next cloud to draw makes them again. */
    public static void close() {
        if (texture != RenderPort.NONE) port.free(texture);
        texture = RenderPort.NONE;
        upload = null;
        BY_CLOUD.clear();
        java.util.Arrays.fill(SLOT, null);
        DRAWN.clear();
    }
}
