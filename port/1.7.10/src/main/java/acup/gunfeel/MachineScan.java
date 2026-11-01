package acup.gunfeel;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import com.hbm.blocks.BlockDummyable;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

// port: 26.2's MeshShapes.tick / frame for NTM 1.7.10's machines. The jar's table has the machines of the NTM it was
// made with (ShapesScenario). A machine it lacks (an addon's, one a newer NTM added) or one it has from another NTM
// version is taken here in the game, as the dev scenario takes them (MachineCapture): every two seconds the machines
// near the player are looked over, and one at a time is taken a little each frame (MachineTake: 1.5 ms of the game's
// thread at most, the rest on a thread of its own), so the game does not stall. What is taken counts at once and is kept in config (ModelCells'
// CONFIG, MachineFaces.TAKEN_FILE) with the NTM version; a server's admin may copy both files to the server, which sends
// the shapes to its players. Not while connected to another game's server: its shapes count there.
final class MachineScan {
    private MachineScan() {
    }

    static final int RANGE = 48;
    static final long FRAME_NANOS = 1_500_000L;

    private static final class Candidate {
        final int x, y, z, meta;
        final Block block;
        final String key;

        Candidate(int x, int y, int z, Block block, int meta, String key) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.meta = meta;
            this.key = key;
        }
    }

    // machine facings ("<id>/<meta>") taken, trusted or that drew nothing, in this game
    private static final Set<String> DONE = new HashSet<String>();
    private static final Set<String> QUEUED = new HashSet<String>();
    private static final ArrayDeque<Candidate> QUEUE = new ArrayDeque<Candidate>();
    // the block atlas as read last (MachineTake), till the game needs the memory
    private static java.lang.ref.SoftReference<ShapesScenario.Atlas> atlas;
    private static World observed;
    private static Candidate active;
    private static MachineTake take;
    private static long work, peak;
    private static int frames, ticks;
    static int taken;
    // the game's longest frame share of all machines taken (the scan scenario reports it)
    static long longest;

    static boolean off() {
        return ShootScenario.world() && !ShootScenario.scan() || "false".equals(System.getProperty("ntmgunfeel.machineScan")) || ModelCells.remote();
    }

    // the scan scenario: the jar's machines taken again too
    static boolean retake() {
        return ShootScenario.scan();
    }

    static boolean done(String key) {
        return DONE.contains(key);
    }

    // machines bullets met (MachineCells.wanted), by core, from any thread
    private static final java.util.concurrent.ConcurrentHashMap<String, int[]> MET = new java.util.concurrent.ConcurrentHashMap<String, int[]>();

    static void init() {
        preload();
        MachineCells.wantAll = retake();
        MachineCells.wanted = (b, x, y, z) -> {
            if (MET.size() < 64) MET.putIfAbsent(x + "," + y + "," + z, new int[] {x, y, z});
        };
    }

    // the classes taking a machine needs, loaded on a thread of their own while the game starts: loaded at the first
    // machine (through the mod loader's transformers) they took the game's thread milliseconds
    private static final String[] CLASSES = {"MachineTake", "MachineTake$1", "MachineTake$Result", "MachineTake$Triangles", "MachineTake$TesrTriangles",
            "MachineTake$ChunkTriangles", "MachineTake$Work", "MachineTake$Fixed", "MachineScan$Candidate", "MachineCapture", "MachineCapture$Collect",
            "MachineCapture$Drawn", "MachineMesh", "MachineMesh$Part", "MachineMesh$TessGroup", "GlRead", "DoorCapture", "ShapesScenario",
            "ShapesScenario$Atlas", "ModelCells$PixelShown", "ShapeWriter", "ShapeWriter$Bytes", "ShapePack", "ShapePack$Out", "ShapePack$Long3",
            "MachineFaces", "MachineFaces$Model", "MachineFaces$Tex", "ModelSurface", "ModelSurface$Face", "MachineGlass", "ModelObjParts"};
    // ... and these set up there too (no GL in their static parts): set up at the first machine, in the frame of its
    // draw, they took half a millisecond more
    private static final String[] SET_UP = {"MachineTake", "MachineCapture", "MachineCapture$Drawn", "MachineCapture$Collect", "MachineMesh",
            "MachineMesh$Part", "GlRead", "MachineFaces", "DoorCapture"};
    private static final String[] LWJGL = {"org.lwjgl.opengl.GL15", "org.lwjgl.opengl.GL21", "org.lwjgl.opengl.GL32", "org.lwjgl.opengl.GLSync",
            "org.lwjgl.PointerWrapperAbstract"};

    private static void preload() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                ClassLoader l = MachineScan.class.getClassLoader();
                for (String c : CLASSES) load("acup.gunfeel." + c, false, l);
                for (String c : LWJGL) load(c, false, l);
                for (String c : SET_UP) load("acup.gunfeel." + c, true, l);
            }
        }, "ntm-gunfeel preload");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void load(String name, boolean setUp, ClassLoader l) {
        try {
            Class.forName(name, setUp, l);
        } catch (Throwable e) {
            GunFeel.LOGGER.debug("[ntm-gunfeel] Could not preload {}", name, e);
        }
    }

    // ClientTickEvent END: every two seconds, the machines near the player the tables lack, and those bullets met
    static void tick(Minecraft mc) {
        long start = System.nanoTime();
        try {
            look(mc);
        } finally {
            tickLongest = Math.max(tickLongest, System.nanoTime() - start);
        }
    }

    // the longest look over the machines near the player (tick)
    static long tickLongest;

    private static void look(Minecraft mc) {
        World w = mc.theWorld;
        if (w != observed) {
            QUEUE.clear();
            QUEUED.clear();
            drop();
            observed = w;
        }
        if (w == null || mc.thePlayer == null || ++ticks % 40 != 0 || off()) return;
        String ntm = PortPaths.hbmVersion();
        double px = mc.thePlayer.posX, py = mc.thePlayer.posY, pz = mc.thePlayer.posZ;
        java.util.List<int[]> at = new java.util.ArrayList<int[]>();
        for (Object o : w.loadedTileEntityList) if (o instanceof TileEntity) {
            TileEntity te = (TileEntity) o;
            at.add(new int[] {te.xCoord, te.yCoord, te.zCoord});
        }
        for (java.util.Iterator<int[]> i = MET.values().iterator(); i.hasNext(); ) {
            at.add(i.next());
            i.remove();
        }
        for (int[] p : at) {
            int x = p[0], y = p[1], z = p[2];
            double dx = x + .5 - px, dy = y + .5 - py, dz = z + .5 - pz;
            if (dx * dx + dy * dy + dz * dz > RANGE * RANGE) continue;
            Block b = w.getBlock(x, y, z);
            if (!MachineCells.machine(b)) continue;
            String id = Block.blockRegistry.getNameForObject(b);
            if (id == null) continue;
            int meta = w.getBlockMetadata(x, y, z);
            String key = id + "/" + meta;
            if (DONE.contains(key) || QUEUED.contains(key) || !core(w, b, x, y, z)) continue;
            if (!retake() && ntm.equals(ModelCells.madeWith(MachineCells.key(id, meta, 0, 0, 0)))) {
                DONE.add(key);
                continue;
            }
            QUEUE.addLast(new Candidate(x, y, z, b, meta, key));
            QUEUED.add(key);
        }
    }

    private static boolean core(World w, Block b, int x, int y, int z) {
        try {
            int[] c = ((BlockDummyable) b).findCore(w, x, y, z);
            return c != null && c[0] == x && c[1] == y && c[2] == z;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean valid(World w, Candidate c) {
        return w.blockExists(c.x, c.y, c.z) && w.getBlock(c.x, c.y, c.z) == c.block && w.getBlockMetadata(c.x, c.y, c.z) == c.meta;
    }

    // RenderTickEvent END: a little of the machine being taken
    static void frame(Minecraft mc) {
        World w = mc.theWorld;
        if (w == null || w != observed || mc.thePlayer == null || off()) return;
        if (active == null && QUEUE.isEmpty()) return;
        long start = System.nanoTime();
        try {
            if (active == null) {
                Candidate c = QUEUE.pollFirst();
                if (!valid(w, c)) {
                    QUEUED.remove(c.key);
                    return;
                }
                active = c;
                frames = 0;
                work = 0;
                peak = 0;
                take = new MachineTake(c.x, c.y, c.z, c.block, c.meta, c.key);
            }
            if (!valid(w, active)) {
                drop();
                return;
            }
            frames++;
            if (!take.step(mc, start + FRAME_NANOS)) return;
            MachineTake.Result r = take.result();
            DONE.add(active.key);
            if (r == null) {
                GunFeel.LOGGER.info("[ntm-gunfeel] Machine {} draws nothing to take: met by its box", active.key);
            } else {
                taken++;
                long cost = System.nanoTime() - start;
                GunFeel.LOGGER.info("[ntm-gunfeel] Took machine {} in the game ({}): {} blocks, {} triangles, {} frames, {} ms of the game's thread, {} ms in the longest frame ({})",
                        active.key, take.how, r.cells.size(), r.triangles, frames, String.format(java.util.Locale.ROOT, "%.1f", (work + cost) / 1e6),
                        String.format(java.util.Locale.ROOT, "%.2f", Math.max(peak, cost) / 1e6), take.slowest());
            }
            drop();
        } catch (Throwable e) {
            GunFeel.LOGGER.warn("[ntm-gunfeel] Could not take machine " + (active == null ? "?" : active.key), e);
            if (active != null) DONE.add(active.key);
            drop();
        } finally {
            long cost = System.nanoTime() - start;
            work += cost;
            peak = Math.max(peak, cost);
            longest = Math.max(longest, cost);
        }
    }

    // a machine being taken now
    static boolean busy() {
        return active != null || !QUEUE.isEmpty();
    }

    private static void drop() {
        if (active != null) QUEUED.remove(active.key);
        if (take != null) take.cancel();
        active = null;
        take = null;
    }

    static ShapesScenario.Atlas cachedAtlas() {
        return atlas == null ? null : atlas.get();
    }

    static void keepAtlas(ShapesScenario.Atlas a) {
        atlas = new java.lang.ref.SoftReference<ShapesScenario.Atlas>(a);
    }
}
