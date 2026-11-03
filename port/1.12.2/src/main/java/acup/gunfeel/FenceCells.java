package acup.gunfeel;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import net.minecraft.block.Block;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockWall;
import net.minecraft.world.IBlockAccess;

// port: 26.2's fence, gate and wall cells (PassableClip.cellsOf, familyKey, march, leave). A fence's collision box is a
// solid wall up to 1.5 blocks; a bullet meets only the posts and rails its model fills, 16 x 16 x 16 cells, and flies
// through the gaps (пролёт). The cells are 26.2's own ("~fence[...]", "~fence_gate[...]", "~wall[...]" lines of
// ntmgunfeel-bullet-shapes.properties, made by tools/fence_shapes.py from the vanilla models), so bullets go as on
// 26.2; a 1.7.10 block's state is turned into 26.2's properties. 1.7.10's models are the same but for the wall's
// sides: 13/16 high on 1.7.10, 14/16 ("low") on 26.2; 1.7.10 has no tall sides and no gates lowered in a wall.
// NTM's chain-link fence (BlockMetalFence) has a pixel mask on 26.2, not the family's cells: its collision box here.
public final class FenceCells {
    private FenceCells() {
    }

    private static volatile Map<String, long[]> families;

    // the cells a bullet meets in this block, or null (not a fence, gate or wall, or a path rule says other)
    public static long[] of(IBlockAccess world, Block b, int meta, int x, int y, int z, String path) {
        String family = family(path, classFamily(b));
        if (family == null) return null;
        return families().get(key(family, world, b, meta, x, y, z));
    }

    // 26.2's PassableClip.family: the path rule's when it names a family (another path: none), else the class's
    static String family(String path, String byClass) {
        if (path != null) return path.equals("fence") || path.equals("fence_gate") || path.equals("wall") ? path : null;
        return byClass;
    }

    static String classFamily(Block b) {
        if (b instanceof com.hbm.blocks.generic.BlockMetalFence) return null;
        if (b instanceof BlockFence) return "fence";
        if (b instanceof BlockFenceGate) return "fence_gate";
        if (b instanceof BlockWall) return "wall";
        return null;
    }

    // 26.2's family key for the block as 1.7.10 has it (names sorted, as familyKey writes them); a block made a fence
    // by a rule takes its class's connections when it has them, else the family's first values (a post)
    static String key(String family, IBlockAccess world, Block b, int meta, int x, int y, int z) {
        if (family.equals("fence")) {
            BlockFence f = b instanceof BlockFence ? (BlockFence) b : null;
            return "~fence[east=" + (f != null && Mc.fenceConnects(f, world, x + 1, y, z)) + ",north=" + (f != null && Mc.fenceConnects(f, world, x, y, z - 1))
                    + ",south=" + (f != null && Mc.fenceConnects(f, world, x, y, z + 1)) + ",west=" + (f != null && Mc.fenceConnects(f, world, x - 1, y, z)) + "]";
        }
        if (family.equals("fence_gate")) {
            if (!(b instanceof BlockFenceGate)) return "~fence_gate[facing=north,in_wall=false,open=false]";
            return gateKey(meta);
        }
        if (!(b instanceof BlockWall)) return "~wall[east=none,north=none,south=none,up=true,west=none]";
        BlockWall w = (BlockWall) b;
        boolean west = Mc.wallConnects(w, world, x - 1, y, z), east = Mc.wallConnects(w, world, x + 1, y, z);
        boolean north = Mc.wallConnects(w, world, x, y, z - 1), south = Mc.wallConnects(w, world, x, y, z + 1);
        return wallKey(east, north, south, west, Mc.air(world, x, y + 1, z));
    }

    // a gate's meta: its direction in the low two bits (0 south, 1 west, 2 north, 3 east: where the placer looked, as
    // 26.2's facing), bit 4 open
    static String gateKey(int meta) {
        String facing = new String[] {"south", "west", "north", "east"}[meta & 3];
        return "~fence_gate[facing=" + facing + ",in_wall=false,open=" + ((meta & 4) != 0) + "]";
    }

    // 1.7.10's RenderBlocks.renderBlockWall: no post on a straight wall with air above it
    static String wallKey(boolean east, boolean north, boolean south, boolean west, boolean airAbove) {
        boolean straight = (north && south && !east && !west) || (east && west && !north && !south);
        return "~wall[east=" + side(east) + ",north=" + side(north) + ",south=" + side(south) + ",up=" + !(straight && airAbove)
                + ",west=" + side(west) + "]";
    }

    private static String side(boolean joined) {
        return joined ? "low" : "none";
    }

    static Map<String, long[]> families() {
        Map<String, long[]> m = families;
        if (m != null) return m;
        synchronized (FenceCells.class) {
            if (families == null) families = load();
            return families;
        }
    }

    // "key = <shape>" or "key = @n" with "@n = <shape>" written once (26.2's readShapes); only the family lines
    private static Map<String, long[]> load() {
        Map<String, long[]> out = new HashMap<String, long[]>();
        try (InputStream in = FenceCells.class.getResourceAsStream("/ntmgunfeel-bullet-shapes.properties")) {
            if (in == null) {
                GunFeel.LOGGER.warn("[ntm-gunfeel] No bullet shapes in the jar: fences are met by their collision boxes");
                return Collections.emptyMap();
            }
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String k : p.stringPropertyNames()) {
                if (!k.startsWith("~")) continue;
                String v = p.getProperty(k).trim();
                if (v.startsWith("@")) v = p.getProperty(v, "").trim();
                long[] bits = unpack(v);
                if (bits != null) out.put(k, bits);
            }
        } catch (Exception e) {
            GunFeel.LOGGER.warn("[ntm-gunfeel] Could not read the bullet shapes", e);
        }
        return Collections.unmodifiableMap(out);
    }

    // 26.2's PassableClip.unpack for a plain shape (1024 hex digits, or "z" and the deflated 512 bytes in base64);
    // shells and model surfaces (more than 512 bytes) are not families'
    static long[] unpack(String v) {
        long[] bits = new long[65];
        if (v.startsWith("!")) return null;
        try {
            if (v.length() == 1024) {
                for (int j = 0; j < 64; j++) bits[j] = Long.parseUnsignedLong(v.substring(j * 16, j * 16 + 16), 16);
                return bits;
            }
            if (!v.startsWith("z")) return null;
            byte[] compressed = java.util.Base64.getDecoder().decode(v.substring(1));
            java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream(512);
            try (java.util.zip.InflaterInputStream in = new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(compressed))) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    data.write(buf, 0, n);
                    if (data.size() > 512) return null;
                }
            }
            if (data.size() != 512) return null;
            java.nio.ByteBuffer.wrap(data.toByteArray()).asLongBuffer().get(bits, 0, 64);
            return bits;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean cell(long[] bits, int x, int y, int z) {
        int i = x + 16 * (y + 16 * z);
        return (bits[i >> 6] >>> (i & 63) & 1L) != 0L;
    }

    // 26.2's march: from where the bullet comes into the block (`hit`), along (wx, wy, wz), the first set cell, as a hit
    // on the face it came in through; null: it crosses the block in the gaps
    static BlockRay.Hit march(long[] bits, BlockRay.Hit hit, double wx, double wy, double wz, int cells) {
        double len = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (len < 1e-9) return hit;
        double dx = wx / len, dy = wy / len, dz = wz / len;
        double px = (hit.ax - hit.x) * 16, py = (hit.ay - hit.y) * 16, pz = (hit.az - hit.z) * 16;
        // on a cell's boundary the side the bullet flies to (26.2: a negative way at x = 3/16 read the empty cell 3)
        int x = clamp((int) Math.floor(px + dx * 1e-7)), y = clamp((int) Math.floor(py + dy * 1e-7)), z = clamp((int) Math.floor(pz + dz * 1e-7));
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tx = Math.abs(dx) < 1e-12 ? Double.POSITIVE_INFINITY : ((sx > 0 ? x + 1 : x) - px) / dx;
        double ty = Math.abs(dy) < 1e-12 ? Double.POSITIVE_INFINITY : ((sy > 0 ? y + 1 : y) - py) / dy;
        double tz = Math.abs(dz) < 1e-12 ? Double.POSITIVE_INFINITY : ((sz > 0 ? z + 1 : z) - pz) / dz;
        double ddx = Math.abs(1 / dx), ddy = Math.abs(1 / dy), ddz = Math.abs(1 / dz);
        double t = 0;
        int face = hit.face;
        for (int n = 0; n < cells; n++) {
            if (cell(bits, x, y, z))
                return new BlockRay.Hit(hit.x, hit.y, hit.z, face, hit.x + (px + dx * t) / 16, hit.y + (py + dy * t) / 16, hit.z + (pz + dz * t) / 16, false);
            if (tx <= ty && tx <= tz) {
                t = tx; tx += ddx; x += sx; face = sx > 0 ? BlockRay.WEST : BlockRay.EAST;
            } else if (ty <= tz) {
                t = ty; ty += ddy; y += sy; face = sy > 0 ? BlockRay.DOWN : BlockRay.UP;
            } else {
                t = tz; tz += ddz; z += sz; face = sz > 0 ? BlockRay.NORTH : BlockRay.SOUTH;
            }
            if (x < 0 || x > 15 || y < 0 || y > 15 || z < 0 || z > 15) return null;
        }
        return null;
    }

    // 26.2's leave: from where the bullet meets a set cell, along (dx, dy, dz) (unit), to the first empty cell or the
    // block's side: the hit there on that far face, as PassableClip.exit gives it
    static BlockRay.Hit leave(long[] bits, BlockRay.Hit in, double dx, double dy, double dz) {
        // a little into the cell it met: on its boundary the floor may name the empty one before it
        double px = (in.ax - in.x) * 16 + dx * 1e-3, py = (in.ay - in.y) * 16 + dy * 1e-3, pz = (in.az - in.z) * 16 + dz * 1e-3;
        int x = clamp((int) Math.floor(px)), y = clamp((int) Math.floor(py)), z = clamp((int) Math.floor(pz));
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tx = Math.abs(dx) < 1e-12 ? Double.POSITIVE_INFINITY : ((sx > 0 ? x + 1 : x) - px) / dx;
        double ty = Math.abs(dy) < 1e-12 ? Double.POSITIVE_INFINITY : ((sy > 0 ? y + 1 : y) - py) / dy;
        double tz = Math.abs(dz) < 1e-12 ? Double.POSITIVE_INFINITY : ((sz > 0 ? z + 1 : z) - pz) / dz;
        double ddx = Math.abs(1 / dx), ddy = Math.abs(1 / dy), ddz = Math.abs(1 / dz);
        double t = 0;
        int face = in.face ^ 1; // the opposite side (0/1, 2/3, 4/5)
        for (int n = 0; n < 64 && cell(bits, x, y, z); n++) {
            if (tx <= ty && tx <= tz) {
                t = tx; tx += ddx; x += sx; face = sx > 0 ? BlockRay.EAST : BlockRay.WEST;
            } else if (ty <= tz) {
                t = ty; ty += ddy; y += sy; face = sy > 0 ? BlockRay.UP : BlockRay.DOWN;
            } else {
                t = tz; tz += ddz; z += sz; face = sz > 0 ? BlockRay.SOUTH : BlockRay.NORTH;
            }
            if (x < 0 || x > 15 || y < 0 || y > 15 || z < 0 || z > 15) break;
        }
        return new BlockRay.Hit(in.x, in.y, in.z, face, in.x + (px + dx * t) / 16, in.y + (py + dy * t) / 16, in.z + (pz + dz * t) / 16, false);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : v > 15 ? 15 : v;
    }
}
