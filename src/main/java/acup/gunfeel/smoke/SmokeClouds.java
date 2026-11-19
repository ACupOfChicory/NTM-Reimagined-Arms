package acup.gunfeel.smoke;

import acup.gunfeel.host.Hook;
import acup.gunfeel.host.HookPoint;
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
 *
 * <p>What any version runs is core's: one cloud ({@link SmokeCloud}), the clouds of a level and their steps
 * ({@link SmokeCloudSet}). Here are the level's side of them: the clouds by level, the burst, the wind and the
 * network, and the block a cloud burst in as a {@link BlockPos}.
 */
public final class SmokeClouds extends SmokeCloudSet<SmokeClouds.Cloud> {
    private static final Map<Level, SmokeClouds> LEVELS = new ConcurrentHashMap<>();

    /** One cloud: its grid and where its cell (0, 0, 0) is in the world ({@link SmokeCloud}). */
    public static final class Cloud extends SmokeCloud {
        /** The block the cloud burst in. */
        public final BlockPos origin;

        Cloud(int id, SmokeGrid grid, BlockPos origin, long born, float dirX, float dirZ, float scale) {
            super(id, grid, origin.getX(), origin.getY(), origin.getZ(), born, dirX, dirZ, scale);
            this.origin = origin;
        }
    }

    /**
     * A cloud stirred at an age of it, world coordinates. A bullet's path ({@code blast} false): from {@code x0, y0, z0}
     * to {@code x1, y1, z1}, the cells crossed keep {@code a}, the ones next to them {@code b}. A blast: at {@code x0,
     * y0, z0}, reach {@code x1}, {@code a} the most it takes in the middle; {@code b} 0, what it takes is gone (a gas), 1,
     * it comes back (smoke: {@link SmokeGrid#stirBlast}).
     */
    public record Stir(int age, boolean blast, double x0, double y0, double z0, double x1, double y1, double z1, float a, float b) implements Event, SmokeCloud.StirEvent {
        public Stir at(int age) {
            return new Stir(age, blast, x0, y0, z0, x1, y1, z1, a, b);
        }
    }

    /** What happens to a cloud at an age of it, the same on every side: a {@link Stir} or a {@link Feed}. */
    public sealed interface Event extends SmokeCloud.Event permits Stir, Feed {
    }

    /**
     * A grenade of the cloud's kind burst in it at an age of it ({@link SmokeGrid#feed}): at world block x, y, z, flying
     * along dirX, dirZ, {@code scale} of a grenade's smoke.
     */
    public record Feed(int age, int x, int y, int z, float dirX, float dirZ, float scale) implements Event, SmokeCloud.FeedEvent {
    }

    /** {@link #thickHaze(int, int, int)} at a block. */
    public Cloud thickHaze(BlockPos at) {
        return thickHaze(at.getX(), at.getY(), at.getZ());
    }

    /** {@link #host(int, int, int, SmokeKind)} at a block. */
    public Cloud host(BlockPos at, SmokeKind kind) {
        return host(at.getX(), at.getY(), at.getZ(), kind);
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

    private SmokeClouds(Level level) {
        super(acup.gunfeel.Wiring.smokeWalls(level));
    }

    // tests: clouds over a mask of their own, with no level
    SmokeClouds(java.util.function.Function<Cloud, SmokeGrid.Mask> masks) {
        super(masks);
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
     * direction the grenade flew. What the GL-54 smoke grenade calls on impact. Returns the cloud's id (the one it fed,
     * or for a haze where hazes lie as thick as they stack the densest of them: {@link #thickHaze}), or -1 when nothing
     * came out (smoke off on this server, or the point is shut in).
     *
     * @param scale share of the kind's volume (1 = one grenade)
     */
    @Hook(HookPoint.SMOKE_BURST)
    public static int spawn(ServerLevel level, Vec3 pos, Vec3 direction, SmokeKind kind, float scale) {
        if (!enabled()) return -1;
        SmokeClouds all = of(level);
        BlockPos origin = BlockPos.containing(pos);
        float len = (float) Math.hypot(direction.x, direction.z);
        float dx = len > 1e-4F ? (float) direction.x / len : 0F, dz = len > 1e-4F ? (float) direction.z / len : 0F;
        // the bomblets ring round a shot straight down or up
        if (len < 0.2F * (float) direction.length()) dx = dz = 0F;
        // where hazes lie as thick as they stack: nothing more (the burst's haze is there)
        if (haze(kind)) {
            Cloud thick = all.thickHaze(origin);
            if (thick != null) return thick.id;
        }
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
        float[] wind = wind(level);
        Cloud c = all.create(id, origin, kind, seed, dx, dz, scale, wind[0], wind[1], level.getGameTime());
        // shut in: nothing comes out, and no older cloud is pushed out for it
        if (c.grid.emitters() == 0) return -1;
        // a cloud past the limit pushes the oldest out; the players who had it are told it is gone
        all.insert(c, gone -> SmokeNet.ended(level, gone));
        SmokeNet.spawned(level, c);
        return id;
    }

    /**
     * Feeds a cloud by its id with {@code scale} of its kind's volume let out at a point, whatever its kind (a gas
     * stream builds its cloud out: {@code acup.gunfeel.gas.ChemClouds}). False (nothing done) when the cloud is gone,
     * the point is beyond {@link #FEED_REACH} across of its burst cell or out of its grid's height, the cloud is about to
     * end or has its feeds.
     */
    public static boolean feed(ServerLevel level, int id, Vec3 pos, Vec3 direction, float scale) {
        SmokeClouds all = get(level);
        if (all == null || !enabled() || !(scale > 0F)) return false;
        Cloud host = null;
        for (Cloud c : all.clouds()) if (c.id == id) host = c;
        if (host == null || host.grid.age() + STIR_AHEAD > host.grid.maxAge() || host.feedsAll() >= SmokeGrid.FEEDS_MAX) return false;
        BlockPos at = BlockPos.containing(pos);
        int x = at.getX() - host.baseX(), y = at.getY() - host.baseY(), z = at.getZ() - host.baseZ();
        if (Math.abs(x - SmokeGrid.OX) > FEED_REACH || Math.abs(z - SmokeGrid.OZ) > FEED_REACH || y < 0 || y >= SmokeGrid.SY) return false;
        float len = (float) Math.hypot(direction.x, direction.z);
        float dx = len > 1e-4F ? (float) direction.x / len : 0F, dz = len > 1e-4F ? (float) direction.z / len : 0F;
        Feed f = new Feed(host.grid.age() + STIR_AHEAD, at.getX(), at.getY(), at.getZ(), dx, dz, Math.min(4F, scale));
        if (!host.stir(f)) return false;
        SmokeNet.fed(level, host, f);
        return true;
    }

    /** The wind over the level now, {x, z} in blocks a tick ({@link SmokeWind}): none without a sky or under a ceiling. */
    static float[] wind(ServerLevel level) {
        if (!level.dimensionType().hasSkyLight() || level.dimensionType().hasCeiling()) return new float[2];
        float rain = level.getRainLevel(1F), thunder = level.getThunderLevel(1F);
        return SmokeWind.at(level.getSeed(), level.getGameTime(), rain, thunder);
    }

    /** Whether this server lets smoke out at all (the {@code smoke} key). */
    public static boolean enabled() {
        return acup.gunfeel.ReaimTimes.flag(SERVER_KEY, true);
    }

    /** Once a level tick on the server. */
    @Hook(HookPoint.SMOKE_SERVER_TICK)
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

    /** A new cloud with no wind. */
    public Cloud create(int id, BlockPos origin, SmokeKind kind, long seed, float dirX, float dirZ, float scale, long born) {
        return create(id, origin, kind, seed, dirX, dirZ, scale, 0F, 0F, born);
    }

    /** A new cloud, its cells read from the world now, not kept yet ({@link #insert}); the wind in blocks a tick. */
    public Cloud create(int id, BlockPos origin, SmokeKind kind, long seed, float dirX, float dirZ, float scale, float windX, float windZ, long born) {
        SmokeGrid grid = new SmokeGrid(kind, seed, dirX, dirZ, scale, windX, windZ);
        Cloud cloud = new Cloud(id, grid, origin.immutable(), born, dirX, dirZ, scale);
        grid.scan(masks.apply(cloud));
        grid.place();
        return cloud;
    }

    /** {@link #create} and {@link #insert}: the client's cloud from a {@code Spawn}. */
    public Cloud add(int id, BlockPos origin, SmokeKind kind, long seed, float dirX, float dirZ, float scale, long born,
            java.util.function.Consumer<Cloud> gone) {
        return add(id, origin, kind, seed, dirX, dirZ, scale, 0F, 0F, born, gone);
    }

    public Cloud add(int id, BlockPos origin, SmokeKind kind, long seed, float dirX, float dirZ, float scale, float windX, float windZ, long born,
            java.util.function.Consumer<Cloud> gone) {
        Cloud cloud = create(id, origin, kind, seed, dirX, dirZ, scale, windX, windZ, born);
        insert(cloud, gone);
        return cloud;
    }

    /** {@link #opticalDepth(double, double, double, double, double, double)} from one point to another. */
    public float opticalDepth(Vec3 from, Vec3 to) {
        return opticalDepth(from.x, from.y, from.z, to.x, to.y, to.z);
    }

    // density times length through one cloud's cells (3D DDA)
    static float walk(Cloud c, Vec3 from, Vec3 to) {
        return walk(c, from.x, from.y, from.z, to.x, to.y, to.z);
    }
}
