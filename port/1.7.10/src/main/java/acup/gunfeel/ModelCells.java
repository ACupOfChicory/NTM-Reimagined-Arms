package acup.gunfeel;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockPane;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.common.util.ForgeDirection;

// port: the cells of 26.2's MeshShapes for 1.7.10's (Block, meta). A block whose model sits inside its hitbox (NTM's
// scaffold and poles, a ladder) or shows see-through pixels (iron bars, grates, NTM's chain-link fence, a trapdoor) is
// met by the 16 x 16 x 16 cells its drawn model fills with shown pixels, not by its collision boxes. 26.2 makes them on
// the client from its block models when a world is joined and ships a copy for the server; 1.7.10 draws blocks with
// RenderBlocks and Forge's render handlers, so the port makes them once in dev (ShapesScenario: the model drawn into
// the Tessellator, the pixels read from the block atlas) and ships the table, ntmgunfeel-bullet-shapes-1710.pack
// (ShapePack), for both sides. Blocks that join their neighbours (panes, bars, NTM's fence) are keyed by their four joins as well,
// doors by both halves' meta. 1.7.10's door tops have windows (clear pixels), as NTM's doors. NTM's big doors
// (BlockDoorGeneric) are in the same table under their own keys (DoorCells).
// Left out: 26.2's exact faces and pixel surfaces over the cells (ModelSurface): the cells alone, as 26.2 has them
// before those.
public final class ModelCells {
    private ModelCells() {
    }

    static final String FILE = "ntmgunfeel-bullet-shapes-1710.pack";
    // a server's own shapes over the jar's (another mod's blocks), sent to its players (ServerSettings)
    static final String CONFIG = "ntm-gunfeel-bullet-shapes-1710.properties";

    private static volatile Map<String, long[]> table;

    // the cells a bullet meets in this block, or null (not in the table: its collision boxes)
    public static long[] of(IBlockAccess world, Block b, int meta, int x, int y, int z) {
        // NTM's wooden trench blocks: their boards as drawn, joined with their neighbours (26.2's WoodBoxes)
        if (WoodBoxes.listed(b)) return WoodBoxes.cells(world, b, meta, x, y, z);
        Map<String, long[]> t = table();
        if (t.isEmpty()) return null;
        if (b instanceof com.hbm.blocks.generic.BlockDoorGeneric) return DoorCells.of(t, world, b, x, y, z);
        if (MachineCells.machine(b)) return MachineCells.of(t, world, b, x, y, z);
        String id = Block.blockRegistry.getNameForObject(b);
        if (id == null) return null;
        return t.get(key(id, fullMeta(world, b, meta, x, y, z), joins(world, b, x, y, z)));
    }

    // a door is drawn by both its halves' meta (1.7.10's func_150012_g: the lower's facing and open, the upper's hinge,
    // 8 for the upper): keyed by that; any other block by its meta
    static int fullMeta(IBlockAccess world, Block b, int meta, int x, int y, int z) {
        if (b instanceof BlockDoor) return ((BlockDoor) b).func_150012_g(world, x, y, z);
        if (b instanceof com.hbm.blocks.generic.BlockModDoor) return ((com.hbm.blocks.generic.BlockModDoor) b).func_150012_g(world, x, y, z);
        return meta;
    }

    static boolean door(Block b) {
        return b instanceof BlockDoor || b instanceof com.hbm.blocks.generic.BlockModDoor;
    }

    // "id#meta" (a door: its full meta), or "id#meta/NESW" with each join 0 or 1 (north, east, south, west) for a block joining its neighbours
    static String key(String id, int meta, String joins) {
        return joins == null ? id + "#" + meta : id + "#" + meta + "/" + joins;
    }

    // the four joins of a pane or a fence-like block as 1.7.10 draws it, else null
    static String joins(IBlockAccess world, Block b, int x, int y, int z) {
        if (b instanceof BlockPane) {
            BlockPane p = (BlockPane) b;
            return bit(p.canPaneConnectTo(world, x, y, z - 1, ForgeDirection.NORTH)) + bit(p.canPaneConnectTo(world, x + 1, y, z, ForgeDirection.EAST))
                    + bit(p.canPaneConnectTo(world, x, y, z + 1, ForgeDirection.SOUTH)) + bit(p.canPaneConnectTo(world, x - 1, y, z, ForgeDirection.WEST));
        }
        if (b instanceof BlockFence) {
            BlockFence f = (BlockFence) b;
            return bit(f.canConnectFenceTo(world, x, y, z - 1)) + bit(f.canConnectFenceTo(world, x + 1, y, z)) + bit(f.canConnectFenceTo(world, x, y, z + 1))
                    + bit(f.canConnectFenceTo(world, x - 1, y, z));
        }
        return null;
    }

    private static String bit(boolean b) {
        return b ? "1" : "0";
    }

    // the jar's table, this game's own (the jar's and config/ntm-gunfeel-bullet-shapes-1710.properties over it), and
    // the server's (null: this game's own counts) while connected to another game's server
    private static Map<String, long[]> jar, own, remote;
    // the config file's shapes as read, packed: what a server sends its players beyond the jar's
    private static Map<String, String> extra = Collections.emptyMap();

    static Map<String, long[]> table() {
        Map<String, long[]> m = table;
        if (m != null) return m;
        synchronized (ModelCells.class) {
            if (table == null) {
                reloadOwn();
            }
            return table;
        }
    }

    // reads the config file again (the server packs its settings for the players: Net.current); its shapes, packed
    static synchronized Map<String, String> reloadOwn() {
        if (jar == null) jar = loadJar();
        Map<String, String> packed = new TreeMap<String, String>();
        Map<String, long[]> m = new HashMap<String, long[]>(jar);
        java.nio.file.Path f = PortPaths.config().resolve(CONFIG);
        if (java.nio.file.Files.exists(f)) {
            try {
                Map<String, long[]> mine = load(java.nio.file.Files.newInputStream(f), f.toString(), packed);
                // machines taken in the game (MachineScan) with another NTM are taken again; the jar's stand till then
                String with = packed.remove(NTM_KEY);
                if (with != null && !with.equals(PortPaths.hbmVersion())) {
                    int before = mine.size();
                    for (String k : new java.util.ArrayList<String>(mine.keySet())) if (k.contains("#machine/")) {
                        mine.remove(k);
                        packed.remove(k);
                    }
                    GunFeel.LOGGER.info("[ntm-gunfeel] {}: {} machine shapes were taken with NTM {}, not {}; they are taken again", f, before - mine.size(),
                            with, PortPaths.hbmVersion());
                }
                m.putAll(mine);
            } catch (java.io.IOException e) {
                GunFeel.LOGGER.warn("[ntm-gunfeel] Could not read " + f, e);
            }
        }
        // what this game took, though the writer may not have written it yet
        for (Map.Entry<String, long[]> e : TAKEN.entrySet()) {
            m.put(e.getKey(), e.getValue());
            packed.put(e.getKey(), pack(e.getValue()));
        }
        own = Collections.unmodifiableMap(m);
        extra = Collections.unmodifiableMap(packed);
        if (remote == null) table = own;
        return extra;
    }

    // the config file's line naming the NTM version its machines were taken with (MachineScan)
    static final String NTM_KEY = "ntm-version";

    // the NTM version this key's shape was made with: this game's for one in the config file, the jar's for one from
    // the jar, null for none
    static synchronized String madeWith(String key) {
        table();
        if (extra.containsKey(key)) return PortPaths.hbmVersion();
        return jar.containsKey(key) ? jarNtm : null;
    }

    // shapes MachineScan took in this game: over this game's own, kept in the config file (on the writer's thread)
    private static final Map<String, long[]> TAKEN = new HashMap<String, long[]>();

    static synchronized void taken(Map<String, long[]> made) {
        table();
        TAKEN.putAll(made);
        Map<String, long[]> m = new HashMap<String, long[]>(own);
        Map<String, String> packed = new TreeMap<String, String>(extra);
        for (Map.Entry<String, long[]> e : made.entrySet()) {
            m.put(e.getKey(), e.getValue());
            packed.put(e.getKey(), pack(e.getValue()));
        }
        own = Collections.unmodifiableMap(m);
        extra = Collections.unmodifiableMap(packed);
        if (remote == null) table = own;
        final Map<String, String> lines = extra;
        final String ntm = PortPaths.hbmVersion();
        ShapeWriter.write(PortPaths.config().resolve(CONFIG), () -> configText(lines, ntm).getBytes(StandardCharsets.UTF_8));
    }

    // the config file: shapes by key as the text the jar's table once was, and the NTM version
    static String configText(Map<String, String> lines, String ntm) {
        StringBuilder b = new StringBuilder();
        b.append("# Bullet shapes beyond the mod jar's (ModelCells): written by the game for machines it took itself (MachineScan),\n");
        b.append("# and sent by a server to its players. Key: block id # meta (machines: <id>#machine/<core meta>/<dx>,<dy>,<dz>).\n");
        b.append(NTM_KEY).append(" = ").append(escape(ntm)).append('\n');
        for (Map.Entry<String, String> e : new TreeMap<String, String>(lines).entrySet())
            b.append(escape(e.getKey())).append(" = ").append(e.getValue()).append('\n');
        return b.toString();
    }

    private static String escape(String key) {
        StringBuilder b = new StringBuilder();
        for (char c : key.toCharArray()) {
            if (c == ':' || c == '=' || c == ' ' || c == '\\' || c == '!' || c == '#' && b.length() == 0) b.append('\\');
            b.append(c);
        }
        return b.toString();
    }

    // 26.2's PassableClip.useRemoteShapes: the server's shapes beyond the jar's (packed, by key), or null: back to this
    // game's own
    public static synchronized void useRemote(Map<String, String> packed) {
        if (jar == null) table();
        if (packed == null) {
            remote = null;
            table = own;
            return;
        }
        Map<String, long[]> m = new HashMap<String, long[]>(jar);
        for (Map.Entry<String, String> e : packed.entrySet()) {
            long[] bits = unpack(e.getValue());
            if (bits != null && e.getKey().indexOf('#') > 0) m.put(e.getKey(), bits);
        }
        remote = Collections.unmodifiableMap(m);
        table = remote;
    }

    // connected to another game's server: its shapes count, not this game's
    static synchronized boolean remote() {
        return remote != null;
    }

    // the jar's own table (dev: ShapesScenario keeps what it does not draw again)
    static synchronized Map<String, long[]> jarTable() {
        if (jar == null) table();
        return jar;
    }

    // ShapesScenario's freshly made table, in place of the jar's (dev)
    static synchronized void put(Map<String, long[]> made) {
        table = Collections.unmodifiableMap(new HashMap<String, long[]>(made));
    }

    // the NTM version the jar's table was made with ("" before it is read or without one)
    static volatile String jarNtm = "";

    // the jar's table (ShapePack)
    private static Map<String, long[]> loadJar() {
        InputStream raw = ModelCells.class.getResourceAsStream("/" + FILE);
        if (raw == null) {
            GunFeel.LOGGER.warn("[ntm-gunfeel] No 1.7.10 bullet shapes in the jar: bars, grates and doors are met by their collision boxes");
            return Collections.emptyMap();
        }
        try (InputStream in = raw) {
            long start = System.nanoTime();
            ShapePack.Read r = ShapePack.unwrap(in);
            Map<String, long[]> m = ShapePack.shapes(r.raw);
            jarNtm = r.ntm;
            GunFeel.LOGGER.info("[ntm-gunfeel] Bullet shapes: {} read in {} ms (made with NTM {})", m.size(), (System.nanoTime() - start) / 1000000, r.ntm);
            return m;
        } catch (IOException | RuntimeException e) {
            GunFeel.LOGGER.warn("[ntm-gunfeel] Could not read the 1.7.10 bullet shapes in the jar", e);
            return Collections.emptyMap();
        }
    }

    // the jar's table was made with this game's NTM
    static boolean jarTrusted() {
        table();
        return jarNtm.equals(PortPaths.hbmVersion());
    }

    // the jar's tables read on a thread of their own while the game starts, not at the first bullet (about 0.1 s)
    static void preload() {
        Thread t = new Thread(() -> {
            table();
            MachineFaces.models();
        }, "ntm-gunfeel shapes");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    // "key = <shape>" or "key = @n" with "@n = <shape>" written once; packed (if not null) gets each key's shape as text
    private static Map<String, long[]> load(InputStream stream, String from, Map<String, String> packed) {
        Map<String, long[]> out = new HashMap<String, long[]>();
        try (InputStream in = stream) {
            if (in == null) {
                GunFeel.LOGGER.warn("[ntm-gunfeel] No 1.7.10 bullet shapes in {}: bars, grates and doors are met by their collision boxes", from);
                return Collections.emptyMap();
            }
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (packed != null && p.getProperty(NTM_KEY) != null) packed.put(NTM_KEY, p.getProperty(NTM_KEY).trim());
            for (String k : p.stringPropertyNames()) {
                if (k.startsWith("@") || k.indexOf('#') < 0) continue;
                String v = p.getProperty(k).trim();
                if (v.startsWith("@")) v = p.getProperty(v, "").trim();
                long[] bits = unpack(v);
                if (bits == null) continue;
                out.put(k, bits);
                if (packed != null) packed.put(k, v);
            }
        } catch (Exception e) {
            GunFeel.LOGGER.warn("[ntm-gunfeel] Could not read the 1.7.10 bullet shapes in " + from, e);
        }
        return out;
    }

    // 26.2's unpack: FenceCells.unpack's forms, after "!" for a shell (bits[64] = 1)
    static long[] unpack(String v) {
        if (v.startsWith("f")) return unpackFaces(v.substring(1));
        boolean shell = v.startsWith("!");
        long[] bits = FenceCells.unpack(shell ? v.substring(1) : v);
        if (bits != null && shell) bits[64] = 1L;
        return bits;
    }

    // bits[64] = 1: the model closes its hitbox all round (a door, a trapdoor: see ShapesScenario.shell). Such a block is
    // decided where the bullet comes in through its collision box, as 26.2's: a shown pixel there stops it, a
    // see-through one lets it through the whole block; its thickness is its collision box's
    public static boolean shell(long[] bits) {
        return bits.length > 64 && bits[64] != 0L;
    }

    // "f" form: a shape with exact faces (ModelSurface), all its longs deflated, in base64
    static String packFaces(long[] shape) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(8 * shape.length);
        buf.asLongBuffer().put(shape);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.util.zip.Deflater d = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
        try (java.util.zip.DeflaterOutputStream z = new java.util.zip.DeflaterOutputStream(out, d)) {
            z.write(buf.array());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } finally {
            d.end();
        }
        return "f" + java.util.Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static long[] unpackFaces(String v) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (java.util.zip.InflaterInputStream in = new java.util.zip.InflaterInputStream(
                    new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(v)))) {
                byte[] b = new byte[8192];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            }
            byte[] bytes = out.toByteArray();
            if (bytes.length % 8 != 0) return null;
            long[] shape = new long[bytes.length / 8];
            java.nio.ByteBuffer.wrap(bytes).asLongBuffer().get(shape);
            return ModelSurface.valid(shape) ? shape : null;
        } catch (java.io.IOException | IllegalArgumentException e) {
            return null;
        }
    }

    // FenceCells.unpack's "z" form: the 64 longs deflated, in base64; "!" before it for a shell
    static String pack(long[] bits) {
        if (ModelSurface.present(bits)) return packFaces(bits);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(512);
        buf.asLongBuffer().put(bits, 0, 64);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.util.zip.Deflater d = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
        try (java.util.zip.DeflaterOutputStream z = new java.util.zip.DeflaterOutputStream(out, d)) {
            z.write(buf.array());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } finally {
            d.end();
        }
        return (shell(bits) ? "!z" : "z") + java.util.Base64.getEncoder().encodeToString(out.toByteArray());
    }

    static boolean empty(long[] bits) {
        for (int i = 0; i < 64; i++) if (bits[i] != 0L) return false;
        return true;
    }

    // ---- filling cells from a model's triangles: 26.2's MeshShapes.fill, grid, planeKey (e284339), without MC

    interface PixelShown {
        boolean at(double wa, double wb, double wc);
    }

    // The cells a triangle (p = a, b, c as x, y, z) covers with shown pixels. A face or an edge lying on a cell
    // border (a pole's side at 2/16, the edge of a diagonal brace there) goes into the cell of the part it bounds,
    // not the empty one beside it: each point is taken a hair towards the triangle's middle, and (behind) a hair
    // behind the face (its normal points out).
    static void fill(long[] bits, double[] p, boolean behind, PixelShown shown) {
        double ux = p[3] - p[0], uy = p[4] - p[1], uz = p[5] - p[2];
        double vx = p[6] - p[0], vy = p[7] - p[1], vz = p[8] - p[2];
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double back = behind && nl > 1e-9 ? 1e-3 / nl : 0;
        nx *= back;
        ny *= back;
        nz *= back;
        double mx = grid((p[0] + p[3] + p[6]) / 3), my = grid((p[1] + p[4] + p[7]) / 3), mz = grid((p[2] + p[5] + p[8]) / 3);
        double edge = Math.max(Math.sqrt(ux * ux + uy * uy + uz * uz), Math.max(Math.sqrt(vx * vx + vy * vy + vz * vz),
                Math.sqrt((p[6] - p[3]) * (p[6] - p[3]) + (p[7] - p[4]) * (p[7] - p[4]) + (p[8] - p[5]) * (p[8] - p[5]))));
        int n = Math.max(2, (int) Math.ceil(edge * 64));
        final double in = 2e-3;
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= n - i; j++) {
                double wa = (double) i / n, wb = (double) j / n, wc = 1 - wa - wb;
                if (!shown.at(wa, wb, wc)) continue;
                double x = grid(wa * p[0] + wb * p[3] + wc * p[6]), y = grid(wa * p[1] + wb * p[4] + wc * p[7]),
                        z = grid(wa * p[2] + wb * p[5] + wc * p[8]);
                x += (mx - x) * in - nx;
                y += (my - y) * in - ny;
                z += (mz - z) * in - nz;
                int cx = (int) Math.floor(x * 16), cy = (int) Math.floor(y * 16), cz = (int) Math.floor(z * 16);
                if (x >= 1.0 && x < 1.0 + 3e-3) cx = 15;
                if (y >= 1.0 && y < 1.0 + 3e-3) cy = 15;
                if (z >= 1.0 && z < 1.0 + 3e-3) cz = 15;
                if (x < 0 && x > -3e-3) cx = 0;
                if (y < 0 && y > -3e-3) cy = 0;
                if (z < 0 && z > -3e-3) cz = 0;
                if (cx < 0 || cx > 15 || cy < 0 || cy > 15 || cz < 0 || cz > 15) continue;
                int k = cx + 16 * (cy + 16 * cz);
                bits[k >> 6] |= 1L << (k & 63);
            }
        }
    }

    // a coordinate a rounding error off a cell border (a plane at 0.5 summed from its corners) put on it
    static double grid(double c) {
        double g = Math.rint(c * 16) / 16;
        return Math.abs(c - g) < 1e-6 ? g : c;
    }

    // the plane of a triangle (p as in fill) as text, its normal and distance rounded, or of the same plane turned the
    // other way; null for a degenerate one. A face with a twin facing the other way in its plane is one side of a
    // double-sided plane (bars, a ladder), which is not pushed behind (it would come out two cells thick)
    static String planeKey(double[] p, boolean flipped) {
        double ux = p[3] - p[0], uy = p[4] - p[1], uz = p[5] - p[2], vx = p[6] - p[0], vy = p[7] - p[1], vz = p[8] - p[2];
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1e-9) return null;
        double f = flipped ? -1 : 1;
        nx *= f / nl;
        ny *= f / nl;
        nz *= f / nl;
        double d = nx * p[0] + ny * p[1] + nz * p[2];
        return Math.round(nx * 500) + " " + Math.round(ny * 500) + " " + Math.round(nz * 500) + " " + Math.round(d * 2000);
    }
}
