package acup.gunfeel.smoke;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The clouds of one level, the same class on the server and the client (common code). Each cloud is a {@link SmokeGrid}
 * placed in the world. The server steps its clouds once a level tick; a client steps its own copies from the events the
 * server sends ({@link SmokeNet}) and catches up a cloud it got late.
 *
 * <p>The layers stay apart, so a later gas kept in the chunks' data can take the place of the grids: the simulation
 * ({@link SmokeGrid}), what a cell is made of ({@link SmokeKind}), the store of clouds here, the network ({@link SmokeNet})
 * and the drawing ({@code SmokeRender} on the client) only meet through {@link #density}, {@link #clouds()} and the
 * grids' own cells.
 */
public final class SmokeClouds {
    private static final Map<Level, SmokeClouds> LEVELS = new ConcurrentHashMap<>();
    /** Clouds a level keeps at most; a new one past it ends the oldest. */
    public static final int LIMIT = 32;

    /** One cloud: its grid and where its cell (0, 0, 0) is in the world. */
    public static final class Cloud {
        public final int id;
        public final SmokeGrid grid;
        public final BlockPos origin;
        /** The level's game time the cloud was born at; it is {@code gameTime - born} ticks old. */
        public final long born;
        public final float dirX, dirZ, scale;
        /** The server: the age whose hash went out last, so a frozen tick does not send it again. */
        int checkedAge = -1;

        Cloud(int id, SmokeGrid grid, BlockPos origin, long born, float dirX, float dirZ, float scale) {
            this.id = id;
            this.grid = grid;
            this.origin = origin;
            this.born = born;
            this.dirX = dirX;
            this.dirZ = dirZ;
            this.scale = scale;
        }

        public int baseX() {
            return origin.getX() - SmokeGrid.OX;
        }

        public int baseY() {
            return origin.getY() - SmokeGrid.OY;
        }

        public int baseZ() {
            return origin.getZ() - SmokeGrid.OZ;
        }

        public SmokeKind kind() {
            return grid.kind();
        }

        /** Whether a world point is inside the grid's box. */
        public boolean holds(double x, double y, double z) {
            double gx = x - baseX(), gy = y - baseY(), gz = z - baseZ();
            return gx >= 0 && gy >= 0 && gz >= 0 && gx < SmokeGrid.SX && gy < SmokeGrid.SY && gz < SmokeGrid.SZ;
        }

        /** Density at a world point, between the cell centres, the closed cells left out. */
        public float density(double x, double y, double z) {
            double gx = x - baseX() - 0.5, gy = y - baseY() - 0.5, gz = z - baseZ() - 0.5;
            int ix = (int) Math.floor(gx), iy = (int) Math.floor(gy), iz = (int) Math.floor(gz);
            float fx = (float) (gx - ix), fy = (float) (gy - iy), fz = (float) (gz - iz);
            float sum = 0F, weight = 0F;
            for (int c = 0; c < 8; c++) {
                int x0 = ix + (c & 1), y0 = iy + (c >> 1 & 1), z0 = iz + (c >> 2 & 1);
                float w = ((c & 1) != 0 ? fx : 1F - fx) * ((c & 2) != 0 ? fy : 1F - fy) * ((c & 4) != 0 ? fz : 1F - fz);
                if (w <= 0F || grid.closed(x0, y0, z0)) continue;
                sum += w * grid.density(x0, y0, z0);
                weight += w;
            }
            return weight > 0F ? sum / weight : 0F;
        }

        /** Density of the cell a world block is in (0 outside). */
        public float cell(int x, int y, int z) {
            return grid.density(x - baseX(), y - baseY(), z - baseZ());
        }
    }

    private final Level level;
    private final Map<Integer, Cloud> clouds = new LinkedHashMap<>();
    // the clouds as they stand, replaced (never changed) whenever one comes or goes: clouds() hands it out without a copy
    private volatile List<Cloud> view = List.of();
    private int nextId = 1;

    private SmokeClouds(Level level) {
        this.level = level;
    }

    public static SmokeClouds of(Level level) {
        return LEVELS.computeIfAbsent(level, SmokeClouds::new);
    }

    /** The level's clouds, or null when it never had one. */
    public static SmokeClouds get(Level level) {
        return level == null ? null : LEVELS.get(level);
    }

    public static void forget(Level level) {
        LEVELS.remove(level);
    }

    // ---- the server

    /**
     * Lets a cloud out at a point (the cell it is in, or the nearest open one), its bomblets fanned along the
     * direction the grenade flew. What the TX-54 smoke grenade calls on impact. Returns the cloud's id, or -1 when
     * nothing came out (smoke off on this server, or the point is shut in).
     *
     * @param scale share of the kind's volume (1 = one grenade)
     */
    public static int spawn(ServerLevel level, Vec3 pos, Vec3 direction, SmokeKind kind, float scale) {
        if (!enabled()) return -1;
        SmokeClouds all = of(level);
        int id = all.nextId();
        BlockPos origin = BlockPos.containing(pos);
        float len = (float) Math.hypot(direction.x, direction.z);
        float dx = len > 1e-4F ? (float) direction.x / len : 0F, dz = len > 1e-4F ? (float) direction.z / len : 0F;
        // the bomblets ring round a shot straight down or up
        if (len < 0.2F * (float) direction.length()) dx = dz = 0F;
        long seed = level.getRandom().nextLong();
        Cloud c = all.create(id, origin, kind, seed, dx, dz, scale, level.getGameTime());
        // shut in: nothing comes out, and no older cloud is pushed out for it
        if (c.grid.emitters() == 0) return -1;
        // a cloud past the limit pushes the oldest out; the players who had it are told it is gone
        all.insert(c, gone -> SmokeNet.ended(level, gone));
        SmokeNet.spawned(level, c);
        return id;
    }

    /** Whether this server lets smoke out at all (the {@code smoke} key). */
    public static boolean enabled() {
        return acup.gunfeel.ReaimTimes.flag(SERVER_KEY, true);
    }

    /** Once a level tick on the server. */
    public static void serverTick(ServerLevel level) {
        SmokeClouds all = get(level);
        if (all == null || all.isEmpty()) return;
        long t0 = System.nanoTime();
        int n = all.clouds().size();
        for (Cloud c : all.tick(level.getGameTime(), 1)) SmokeNet.ended(level, c);
        SmokeNet.checks(level, all);
        long spent = System.nanoTime() - t0;
        serverNanos += spent;
        serverMax = Math.max(serverMax, spent);
        serverTicks++;
        serverClouds = Math.max(serverClouds, n);
        if (serverTicks >= 100) {
            serverLast = String.format(java.util.Locale.ROOT, "%.3f ms a tick on average, %.3f at most, over %d ticks, up to %d clouds",
                    serverNanos / 1e6 / serverTicks, serverMax / 1e6, serverTicks, serverClouds);
            org.slf4j.LoggerFactory.getLogger("ntm-gunfeel").debug("Smoke server cost: {}", serverLast);
            serverNanos = serverMax = 0L;
            serverTicks = serverClouds = 0;
        }
    }

    /** The server key that lets smoke out at all (ntm-gunfeel-server.properties). */
    public static final String SERVER_KEY = "smoke";
    private static long serverNanos, serverMax;
    private static int serverTicks, serverClouds;
    private static volatile String serverLast = "not measured yet";

    /** The server's time on the clouds over the last 100 ticks with any. */
    public static String serverCost() {
        return serverLast;
    }

    public Level level() {
        return level;
    }

    /** The clouds, oldest first. A list that never changes: one that comes or goes later is not in it. */
    public List<Cloud> clouds() {
        return view;
    }

    public boolean isEmpty() {
        return view.isEmpty();
    }

    public Cloud cloud(int id) {
        synchronized (clouds) {
            return clouds.get(id);
        }
    }

    // the clouds map changed: the view follows (called holding the map)
    private void changed() {
        view = List.copyOf(clouds.values());
    }

    int nextId() {
        return nextId++;
    }

    /** A new cloud, its cells read from the world now, not kept yet ({@link #insert}). */
    public Cloud create(int id, BlockPos origin, SmokeKind kind, long seed, float dirX, float dirZ, float scale, long born) {
        SmokeGrid grid = new SmokeGrid(kind, seed, dirX, dirZ, scale);
        Cloud cloud = new Cloud(id, grid, origin.immutable(), born, dirX, dirZ, scale);
        grid.scan(SmokeMask.of(level, cloud));
        grid.place();
        return cloud;
    }

    /**
     * Keeps a cloud. A cloud with that id is replaced; past {@link #LIMIT} the oldest are dropped, and {@code gone}
     * (when given) sees each of them.
     */
    public void insert(Cloud cloud, java.util.function.Consumer<Cloud> gone) {
        List<Cloud> dropped = null;
        synchronized (clouds) {
            clouds.remove(cloud.id);
            clouds.put(cloud.id, cloud);
            while (clouds.size() > LIMIT) {
                if (dropped == null) dropped = new ArrayList<>();
                dropped.add(clouds.remove(clouds.keySet().iterator().next()));
            }
            changed();
        }
        if (gone != null && dropped != null) for (Cloud c : dropped) gone.accept(c);
    }

    /** {@link #create} and {@link #insert}: the client's cloud from a {@code Spawn}. */
    public Cloud add(int id, BlockPos origin, SmokeKind kind, long seed, float dirX, float dirZ, float scale, long born,
            java.util.function.Consumer<Cloud> gone) {
        Cloud cloud = create(id, origin, kind, seed, dirX, dirZ, scale, born);
        insert(cloud, gone);
        return cloud;
    }

    public Cloud remove(int id) {
        synchronized (clouds) {
            Cloud c = clouds.remove(id);
            if (c != null) changed();
            return c;
        }
    }

    /**
     * Steps every cloud up to its age at this game time, at most {@code most} steps a cloud (the server: one a tick; a
     * client catching a late cloud up spreads the steps over a few ticks), and drops the clouds that ended. Returns them.
     */
    public List<Cloud> tick(long gameTime, int most) {
        for (Cloud c : clouds()) step(c, gameTime, most, null);
        return reap();
    }

    /**
     * Steps one cloud up to its age at this game time, at most {@code most} steps. Before each step a twentieth of its
     * cells is read from the world again; {@code after} sees the grid after each step.
     */
    public void step(Cloud c, long gameTime, int most, java.util.function.Consumer<SmokeGrid> after) {
        long want = gameTime - c.born;
        SmokeMask mask = null;
        for (int n = 0; n < most && c.grid.age() < want && !c.grid.done(); n++) {
            if (mask == null) mask = SmokeMask.of(level, c);
            c.grid.refresh(mask, c.grid.age() % SmokeMask.PARTS, SmokeMask.PARTS);
            c.grid.step();
            if (after != null) after.accept(c.grid);
        }
    }

    /** Drops the clouds that thinned away (or lived too long) and returns them. */
    public List<Cloud> reap() {
        List<Cloud> ended = null;
        for (Cloud c : view) {
            if (c.grid.done() || c.grid.age() > c.kind().maxAge()) {
                remove(c.id);
                if (ended == null) ended = new ArrayList<>();
                ended.add(c);
            }
        }
        return ended == null ? List.of() : ended;
    }

    /** Smoke density at a world point, all clouds together. */
    public float density(double x, double y, double z) {
        float sum = 0F;
        for (Cloud c : clouds()) if (c.holds(x, y, z) && c.grid.occupied()) sum += c.density(x, y, z);
        return sum;
    }

    /** The cloud with the most smoke at a world point, or null. */
    public Cloud densest(double x, double y, double z) {
        Cloud best = null;
        float most = 0F;
        for (Cloud c : clouds()) {
            if (!c.holds(x, y, z)) continue;
            float v = c.density(x, y, z);
            if (v > most) {
                most = v;
                best = c;
            }
        }
        return best;
    }

    /**
     * Optical depth along a segment (density times blocks, every kind's extinction times it), walking the cells it
     * crosses; 0 without a cloud on the way. One block of dense smoke is about {@code fill * extinction} (2.5).
     */
    public float opticalDepth(Vec3 from, Vec3 to) {
        float sum = 0F;
        for (Cloud c : clouds()) {
            if (!c.grid.occupied()) continue;
            sum += c.kind().extinction() * walk(c, from, to);
        }
        return sum;
    }

    // density times length through one cloud's cells (3D DDA)
    private static float walk(Cloud c, Vec3 from, Vec3 to) {
        int[] b = c.grid.box();
        double minX = c.baseX() + b[0], minY = c.baseY() + b[1], minZ = c.baseZ() + b[2];
        double maxX = c.baseX() + b[3] + 1, maxY = c.baseY() + b[4] + 1, maxZ = c.baseZ() + b[5] + 1;
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double t0 = 0, t1 = 1;
        double[] lo = {minX, minY, minZ}, hi = {maxX, maxY, maxZ}, o = {from.x, from.y, from.z}, d = {dx, dy, dz};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                if (o[a] < lo[a] || o[a] > hi[a]) return 0F;
                continue;
            }
            double ta = (lo[a] - o[a]) / d[a], tb = (hi[a] - o[a]) / d[a];
            t0 = Math.max(t0, Math.min(ta, tb));
            t1 = Math.min(t1, Math.max(ta, tb));
            if (t0 >= t1) return 0F;
        }
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double px = from.x + dx * t0, py = from.y + dy * t0, pz = from.z + dz * t0;
        int x = (int) Math.floor(px), y = (int) Math.floor(py), z = (int) Math.floor(pz);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double nx = dx != 0 ? ((sx > 0 ? x + 1 : x) - px) / dx : Double.POSITIVE_INFINITY;
        double ny = dy != 0 ? ((sy > 0 ? y + 1 : y) - py) / dy : Double.POSITIVE_INFINITY;
        double nz = dz != 0 ? ((sz > 0 ? z + 1 : z) - pz) / dz : Double.POSITIVE_INFINITY;
        double ex = dx != 0 ? sx / dx : Double.POSITIVE_INFINITY, ey = dy != 0 ? sy / dy : Double.POSITIVE_INFINITY, ez = dz != 0 ? sz / dz : Double.POSITIVE_INFINITY;
        double t = t0, sum = 0;
        for (int guard = 0; guard < 128 && t < t1; guard++) {
            double step = Math.min(Math.min(t0 + nx, t0 + ny), t0 + nz);
            double end = Math.min(step, t1);
            sum += c.cell(x, y, z) * (end - t) * length;
            t = end;
            if (step == t0 + nx) {
                x += sx;
                nx += ex;
            } else if (step == t0 + ny) {
                y += sy;
                ny += ey;
            } else {
                z += sz;
                nz += ez;
            }
        }
        return (float) sum;
    }
}
