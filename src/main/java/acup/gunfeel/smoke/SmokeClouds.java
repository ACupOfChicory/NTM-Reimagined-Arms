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
 * and the drawing ({@code SmokeRender} on the client) only meet through {@link #clouds()}, {@link Cloud#density} and
 * the grids' own cells.
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
        // stirs and feeds for an age the grid has not got to yet, by age (the order they came in within one age)
        private final java.util.ArrayList<Event> pending = new java.util.ArrayList<>();

        Cloud(int id, SmokeGrid grid, BlockPos origin, long born, float dirX, float dirZ, float scale) {
            this.id = id;
            this.grid = grid;
            this.origin = origin;
            this.born = born;
            this.dirX = dirX;
            this.dirZ = dirZ;
            this.scale = scale;
        }

        /**
         * Whether the copy is more than a couple of ticks behind its age at this game time: a cloud that came late and is
         * still catching up. It is not drawn, does not thicken the fog and is not compared with the server's hash.
         */
        public boolean catchingUp(long gameTime) {
            return grid.age() < gameTime - born - 2;
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

        /**
         * A stir for an age of the grid: kept until the grid gets there and taken before that age's step, after the ones
         * for that age that came before it. One for an age already stepped past (a client further behind the server than
         * {@link #STIR_AHEAD}) is taken at once: that copy is a step off and the next hash check asks for the grid.
         * Returns false (nothing done) when {@link #PENDING_MAX} wait already.
         */
        public boolean stir(Event st) {
            synchronized (pending) {
                due();
                if (st.age() <= grid.age() && pending.isEmpty()) {
                    apply(st);
                    return true;
                }
                if (pending.size() >= PENDING_MAX) return false;
                int at = pending.size();
                while (at > 0 && pending.get(at - 1).age() > st.age()) at--;
                pending.add(at, st);
                due();
                return true;
            }
        }

        // the stirs due before the step from this age
        void due() {
            synchronized (pending) {
                while (!pending.isEmpty() && pending.getFirst().age() <= grid.age()) apply(pending.removeFirst());
            }
        }

        /** A grid from the server came: the stirs for the ages it has already been through are in it. */
        public void stepped() {
            synchronized (pending) {
                pending.removeIf(s -> s.age() < grid.age());
            }
        }

        private void apply(Event e) {
            if (e instanceof Feed f) {
                grid.feed(f.x() - baseX(), f.y() - baseY(), f.z() - baseZ(), f.dirX(), f.dirZ(), f.scale());
                return;
            }
            Stir st = (Stir) e;
            double bx = baseX(), by = baseY(), bz = baseZ();
            if (st.blast()) grid.stirBlast(st.x0() - bx, st.y0() - by, st.z0() - bz, st.x1(), st.a(), st.b() > 0F);
            else grid.stirLine(st.x0() - bx, st.y0() - by, st.z0() - bz, st.x1() - bx, st.y1() - by, st.z1() - bz, st.a(), st.b());
        }

        // feeds taken and waiting
        int feedsAll() {
            synchronized (pending) {
                int n = grid.feeds();
                for (Event e : pending) if (e instanceof Feed) n++;
                return n;
            }
        }

        /** Whether a world segment comes within a block of the smoke in the cloud. */
        public boolean near(double ax, double ay, double az, double bx, double by, double bz, double margin) {
            if (!grid.occupied()) return false;
            int[] b = grid.box();
            double[] lo = {baseX() + b[0] - margin, baseY() + b[1] - margin, baseZ() + b[2] - margin};
            double[] hi = {baseX() + b[3] + 1 + margin, baseY() + b[4] + 1 + margin, baseZ() + b[5] + 1 + margin};
            double[] o = {ax, ay, az}, d = {bx - ax, by - ay, bz - az};
            double t0 = 0, t1 = 1;
            for (int k = 0; k < 3; k++) {
                if (Math.abs(d[k]) < 1e-12) {
                    if (o[k] < lo[k] || o[k] > hi[k]) return false;
                    continue;
                }
                double ta = (lo[k] - o[k]) / d[k], tb = (hi[k] - o[k]) / d[k];
                t0 = Math.max(t0, Math.min(ta, tb));
                t1 = Math.min(t1, Math.max(ta, tb));
                if (t0 > t1) return false;
            }
            return true;
        }

        /** Density of the cell a world block is in (0 outside). */
        public float cell(int x, int y, int z) {
            return grid.density(x - baseX(), y - baseY(), z - baseZ());
        }
    }

    /**
     * A cloud stirred at an age of it, world coordinates. A bullet's path ({@code blast} false): from {@code x0, y0, z0}
     * to {@code x1, y1, z1}, the cells crossed keep {@code a}, the ones next to them {@code b}. A blast: at {@code x0,
     * y0, z0}, reach {@code x1}, {@code a} the most it takes in the middle; {@code b} 0, what it takes is gone (a gas), 1,
     * it comes back (smoke: {@link SmokeGrid#stirBlast}).
     */
    public record Stir(int age, boolean blast, double x0, double y0, double z0, double x1, double y1, double z1, float a, float b) implements Event {
        public Stir at(int age) {
            return new Stir(age, blast, x0, y0, z0, x1, y1, z1, a, b);
        }
    }

    /** What happens to a cloud at an age of it, the same on every side: a {@link Stir} or a {@link Feed}. */
    public sealed interface Event permits Stir, Feed {
        int age();
    }

    /**
     * A grenade of the cloud's kind burst in it at an age of it ({@link SmokeGrid#feed}): at world block x, y, z, flying
     * along dirX, dirZ, {@code scale} of a grenade's smoke.
     */
    public record Feed(int age, int x, int y, int z, float dirX, float dirZ, float scale) implements Event {
    }

    /** Whether a feed from the network is one a server makes. */
    public static boolean sane(Feed f) {
        return f.age() >= 0 && Math.abs(f.x()) <= 30_000_000 && Math.abs(f.z()) <= 30_000_000 && Math.abs(f.y()) <= 4096
                && Math.abs(f.dirX()) <= 1.01F && Math.abs(f.dirZ()) <= 1.01F && f.scale() > 0F && f.scale() <= 4F;
    }

    /**
     * How far from a cloud's burst cell (across, in cells) a grenade of its kind still feeds it: there its bomblets
     * (3.5 blocks at most) still land in the grid. Farther it makes a cloud of its own.
     */
    public static final int FEED_REACH = 8;

    /**
     * Whether a grenade of this kind feeds a cloud of its kind it bursts in: smoke does, it fills the air up to its
     * {@code fill} and a second grenade's smoke goes on outward. A gas does not: a second grenade is twice the gas in
     * the same air, the clouds' densities add up (as what they do to the living and the ground counts them,
     * {@code GasPoison}); they are drawn as one smoke all the same.
     */
    public static boolean feeds(SmokeKind kind) {
        return acup.gunfeel.gas.GasKind.of(kind) == null;
    }

    /**
     * The cloud a grenade of this kind bursting at this block feeds, or null: none for a gas ({@link #feeds}); one of
     * its kind with smoke on that block or next to it, the burst within {@link #FEED_REACH} across of its own and its
     * grid's height, with feeds to spare; of those the densest there.
     */
    public Cloud host(BlockPos at, SmokeKind kind) {
        if (!feeds(kind)) return null;
        Cloud best = null;
        float most = 0F;
        for (Cloud c : clouds()) {
            // the feed is taken STIR_AHEAD ticks on: the cloud must still be there then
            if (c.kind() != kind || c.grid.age() + STIR_AHEAD > c.grid.maxAge()) continue;
            int x = at.getX() - c.baseX(), y = at.getY() - c.baseY(), z = at.getZ() - c.baseZ();
            if (Math.abs(x - SmokeGrid.OX) > FEED_REACH || Math.abs(z - SmokeGrid.OZ) > FEED_REACH || y < 0 || y >= SmokeGrid.SY) continue;
            float there = 0F;
            for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
                there = Math.max(there, c.grid.density(x + dx, y + dy, z + dz));
            if (there < SmokeGrid.GONE || there <= most || c.feedsAll() >= SmokeGrid.FEEDS_MAX) continue;
            best = c;
            most = there;
        }
        return best;
    }

    /**
     * Ticks ahead of a cloud's age a server's stir is for: both sides take it at that age, a client up to that far behind
     * included (a client is behind the server by its ping).
     */
    public static final int STIR_AHEAD = 3;
    /** Stirs a cloud keeps waiting at most. */
    public static final int PENDING_MAX = 256;

    /** Whether a stir from the network is one a server makes: finite, a reach of at most 32, shares in [0, 1]. */
    public static boolean sane(Stir s) {
        double[] v = {s.x0(), s.y0(), s.z0(), s.x1(), s.y1(), s.z1()};
        for (double x : v) if (!Double.isFinite(x) || Math.abs(x) > 3.0E7) return false;
        if (s.blast() && !(s.x1() > 0 && s.x1() <= 32)) return false;
        return s.age() >= 0 && s.a() >= 0F && s.a() <= 1F && s.b() >= 0F && s.b() <= 1F;
    }

    /**
     * The server: stirs the clouds the path or the blast comes near that {@code which} takes, now, and tells the players
     * who have them. Returns how many.
     */
    public static int stir(ServerLevel level, Stir st, java.util.function.Predicate<SmokeKind> which) {
        SmokeClouds all = get(level);
        if (all == null || all.isEmpty()) return 0;
        int n = 0;
        for (Cloud c : all.clouds()) {
            if (!which.test(c.kind())) continue;
            boolean near = st.blast() ? c.near(st.x0(), st.y0(), st.z0(), st.x0(), st.y0(), st.z0(), st.x1())
                    : c.near(st.x0(), st.y0(), st.z0(), st.x1(), st.y1(), st.z1(), 1.0);
            if (!near) continue;
            Stir now = st.at(c.grid.age() + STIR_AHEAD);
            c.stir(now);
            SmokeNet.stirred(level, c, now);
            n++;
        }
        return n;
    }

    private final java.util.function.Function<Cloud, SmokeGrid.Mask> masks;
    private final Map<Integer, Cloud> clouds = new LinkedHashMap<>();
    // the clouds as they stand, replaced (never changed) whenever one comes or goes: clouds() hands it out without a copy
    private volatile List<Cloud> view = List.of();
    private int nextId = 1;

    private SmokeClouds(Level level) {
        this.masks = c -> SmokeMask.of(level, c);
    }

    // tests: clouds over a mask of their own, with no level
    SmokeClouds(java.util.function.Function<Cloud, SmokeGrid.Mask> masks) {
        this.masks = masks;
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
     * direction the grenade flew. What the GL-54 smoke grenade calls on impact. Returns the cloud's id, or -1 when
     * nothing came out (smoke off on this server, or the point is shut in).
     *
     * @param scale share of the kind's volume (1 = one grenade)
     */
    public static int spawn(ServerLevel level, Vec3 pos, Vec3 direction, SmokeKind kind, float scale) {
        if (!enabled()) return -1;
        SmokeClouds all = of(level);
        BlockPos origin = BlockPos.containing(pos);
        float len = (float) Math.hypot(direction.x, direction.z);
        float dx = len > 1e-4F ? (float) direction.x / len : 0F, dz = len > 1e-4F ? (float) direction.z / len : 0F;
        // the bomblets ring round a shot straight down or up
        if (len < 0.2F * (float) direction.length()) dx = dz = 0F;
        // in a cloud of its kind: it feeds that one
        Cloud host = all.host(origin, kind);
        if (host != null) {
            Feed f = new Feed(host.grid.age() + STIR_AHEAD, origin.getX(), origin.getY(), origin.getZ(), dx, dz, scale);
            // a host whose queue is full takes it nowhere: the grenade makes a cloud of its own after all
            if (host.stir(f)) {
                SmokeNet.fed(level, host, f);
                return host.id;
            }
        }
        int id = all.nextId();
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
        grid.scan(masks.apply(cloud));
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
     * Steps one cloud up to its age at this game time, at most {@code most} steps; returns how many it took. Before each
     * step a twentieth of its cells is read from the world again; {@code after} sees the grid after each step.
     */
    public int step(Cloud c, long gameTime, int most, java.util.function.Consumer<SmokeGrid> after) {
        long want = gameTime - c.born;
        SmokeGrid.Mask mask = null;
        int n = 0;
        for (; n < most && c.grid.age() < want && !c.grid.done(); n++) {
            if (mask == null) mask = masks.apply(c);
            c.grid.refresh(mask, c.grid.age() % SmokeMask.PARTS, SmokeMask.PARTS);
            c.due();
            c.grid.step();
            if (after != null) after.accept(c.grid);
        }
        return n;
    }

    /**
     * A client's tick: every cloud takes its step for this tick first, then what is left of {@code budget} steps (all
     * the clouds together, not each) goes to the clouds still behind, {@code burst} steps at a time in turn. A cloud that
     * came late with hundreds of ticks to catch up therefore costs a few ticks of a small share each, not one big hitch,
     * and several of them together cost no more than one. Returns the steps taken.
     */
    public int catchUp(long gameTime, int budget, int burst, java.util.function.BiConsumer<Cloud, SmokeGrid> after) {
        List<Cloud> all = view;
        int taken = 0;
        for (Cloud c : all) taken += step(c, gameTime, 1, after == null ? null : g -> after.accept(c, g));
        boolean any = true;
        while (taken < budget && any) {
            any = false;
            for (Cloud c : all) {
                if (taken >= budget) break;
                if (c.grid.done() || c.grid.age() >= gameTime - c.born) continue;
                int n = step(c, gameTime, Math.min(burst, budget - taken), after == null ? null : g -> after.accept(c, g));
                taken += n;
                if (n > 0) any = true;
            }
        }
        return taken;
    }

    /** Drops the clouds that thinned away (or lived too long) and returns them. */
    public List<Cloud> reap() {
        List<Cloud> ended = null;
        for (Cloud c : view) {
            if (c.grid.done() || c.grid.age() > c.grid.maxAge()) {
                remove(c.id);
                if (ended == null) ended = new ArrayList<>();
                ended.add(c);
            }
        }
        return ended == null ? List.of() : ended;
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
    static float walk(Cloud c, Vec3 from, Vec3 to) {
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
