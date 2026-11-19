package acup.gunfeel.smoke;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The clouds of one level, the same on the server and a client: kept by id up to {@link #LIMIT}, stepped to their
 * age, the ones that thinned away dropped; which cloud a new grenade feeds, how much smoke lies along a line of sight.
 * The walls come from the version's adapter: for each cloud a {@link SmokeGrid.Mask} over the world (26.2:
 * {@code SmokeMask}). So do the level the clouds belong to, the network and the burst itself
 * ({@code SmokeClouds}, which keeps its clouds in this).
 *
 * @param <C> the version's cloud (26.2: {@code SmokeClouds.Cloud})
 */
public class SmokeCloudSet<C extends SmokeCloud> {
    /** Clouds a level keeps at most; a new one past it ends the oldest. */
    public static final int LIMIT = 32;
    /** A cloud reads a twentieth of its cells again each step: all of them once a second. */
    public static final int PARTS = 20;

    /**
     * How far from a cloud's burst cell (across, in cells) a grenade of its kind still feeds it: there its bomblets
     * (3.5 blocks at most) still land in the grid. Farther it makes a cloud of its own.
     */
    public static final int FEED_REACH = 8;

    /**
     * Hazes that lie one in another at most: a burst where this many lie lets out nothing more there (a grenade
     * launcher's magazine or a cluster shell's ten at one spot stacked six or ten deep, as dense as a smoke screen).
     */
    public static final int HAZES_MAX = 2;

    /**
     * Ticks ahead of a cloud's age a server's stir is for: both sides take it at that age, a client up to that far behind
     * included (a client is behind the server by its ping).
     */
    public static final int STIR_AHEAD = 3;
    /** Stirs a cloud keeps waiting at most. */
    public static final int PENDING_MAX = 256;

    /** Whether a feed from the network is one a server makes. */
    public static boolean sane(SmokeCloud.FeedEvent f) {
        return f.age() >= 0 && Math.abs(f.x()) <= 30_000_000 && Math.abs(f.z()) <= 30_000_000 && Math.abs(f.y()) <= 4096
                && Math.abs(f.dirX()) <= 1.01F && Math.abs(f.dirZ()) <= 1.01F && f.scale() > 0F && f.scale() <= 4F;
    }

    /** Whether a stir from the network is one a server makes: finite, a reach of at most 32, shares in [0, 1]. */
    public static boolean sane(SmokeCloud.StirEvent s) {
        double[] v = {s.x0(), s.y0(), s.z0(), s.x1(), s.y1(), s.z1()};
        for (double x : v) if (!Double.isFinite(x) || Math.abs(x) > 3.0E7) return false;
        if (s.blast() && !(s.x1() > 0 && s.x1() <= 32)) return false;
        return s.age() >= 0 && s.a() >= 0F && s.a() <= 1F && s.b() >= 0F && s.b() <= 1F;
    }

    /**
     * Whether a grenade of this kind feeds a cloud of its kind it bursts in: smoke does, it fills the air up to its
     * {@code fill} and a second grenade's smoke goes on outward. A gas does not: a second grenade is twice the gas in
     * the same air, the clouds' densities add up (as what they do to the living and the ground counts them,
     * {@code GasPoison}); they are drawn as one smoke all the same. White phosphorus's haze ({@link #haze}) neither:
     * fed, it went on outward to the sides of its grid and stood there as a wall; two hazes one in the other are a
     * thicker haze, up to {@link #HAZES_MAX}.
     */
    public static boolean feeds(SmokeKind kind) {
        return !acup.gunfeel.gas.GasKind.gas(kind) && !haze(kind);
    }

    /** White phosphorus's haze, the grenade's or the rounds' and shells'. */
    public static boolean haze(SmokeKind kind) {
        return kind == SmokeKind.WP || kind == SmokeKind.WP_SHELL;
    }

    protected final Function<C, SmokeGrid.Mask> masks;
    private final Map<Integer, C> clouds = new LinkedHashMap<>();
    // the clouds as they stand, replaced (never changed) whenever one comes or goes: clouds() hands it out without a copy
    private volatile List<C> view = List.of();
    private int nextId = 1;

    /** Clouds whose walls each come from {@code masks}. */
    protected SmokeCloudSet(Function<C, SmokeGrid.Mask> masks) {
        this.masks = masks;
    }

    /**
     * The densest of the hazes ({@link #haze}) at this world block or next to it when {@link #HAZES_MAX} of them lie
     * there, or null: a burst there adds nothing.
     */
    public C thickHaze(int atX, int atY, int atZ) {
        C best = null;
        float most = 0F;
        int n = 0;
        for (C c : clouds()) {
            if (!haze(c.kind()) || c.grid.age() + STIR_AHEAD > c.grid.maxAge()) continue;
            int x = atX - c.baseX(), y = atY - c.baseY(), z = atZ - c.baseZ();
            float there = 0F;
            for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
                there = Math.max(there, c.grid.density(x + dx, y + dy, z + dz));
            if (there < SmokeGrid.GONE) continue;
            n++;
            if (there > most) {
                best = c;
                most = there;
            }
        }
        return n >= HAZES_MAX ? best : null;
    }

    /**
     * The cloud a grenade of this kind bursting at this world block feeds, or null: none for a gas ({@link #feeds});
     * one of its kind with smoke on that block or next to it, the burst within {@link #FEED_REACH} across of its own
     * and its grid's height, with feeds to spare; of those the densest there.
     */
    public C host(int atX, int atY, int atZ, SmokeKind kind) {
        if (!feeds(kind)) return null;
        C best = null;
        float most = 0F;
        for (C c : clouds()) {
            // the feed is taken STIR_AHEAD ticks on: the cloud must still be there then
            if (c.kind() != kind || c.grid.age() + STIR_AHEAD > c.grid.maxAge()) continue;
            int x = atX - c.baseX(), y = atY - c.baseY(), z = atZ - c.baseZ();
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

    /** The clouds, oldest first. A list that never changes: one that comes or goes later is not in it. */
    public List<C> clouds() {
        return view;
    }

    public boolean isEmpty() {
        return view.isEmpty();
    }

    public C cloud(int id) {
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

    /**
     * Keeps a cloud. A cloud with that id is replaced; past {@link #LIMIT} the oldest are dropped, and {@code gone}
     * (when given) sees each of them.
     */
    public void insert(C cloud, Consumer<C> gone) {
        List<C> dropped = null;
        synchronized (clouds) {
            clouds.remove(cloud.id);
            clouds.put(cloud.id, cloud);
            while (clouds.size() > LIMIT) {
                if (dropped == null) dropped = new ArrayList<>();
                dropped.add(clouds.remove(clouds.keySet().iterator().next()));
            }
            changed();
        }
        if (gone != null && dropped != null) for (C c : dropped) gone.accept(c);
    }

    public C remove(int id) {
        synchronized (clouds) {
            C c = clouds.remove(id);
            if (c != null) changed();
            return c;
        }
    }

    /**
     * Steps every cloud up to its age at this game time, at most {@code most} steps a cloud (the server: one a tick; a
     * client catching a late cloud up spreads the steps over a few ticks), and drops the clouds that ended. Returns them.
     */
    public List<C> tick(long gameTime, int most) {
        for (C c : clouds()) step(c, gameTime, most, null);
        return reap();
    }

    /**
     * Steps one cloud up to its age at this game time, at most {@code most} steps; returns how many it took. Before each
     * step a twentieth of its cells is read from the world again, after it the box follows the smoke downwind;
     * {@code after} sees the grid after each step.
     */
    public int step(C c, long gameTime, int most, Consumer<SmokeGrid> after) {
        long want = gameTime - c.born;
        SmokeGrid.Mask mask = null;
        int n = 0;
        for (; n < most && c.grid.age() < want && !c.grid.done(); n++) {
            if (mask == null) mask = masks.apply(c);
            c.grid.refresh(mask, c.grid.age() % PARTS, PARTS);
            c.due();
            c.grid.step();
            c.grid.drift(mask);
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
    public int catchUp(long gameTime, int budget, int burst, BiConsumer<C, SmokeGrid> after) {
        List<C> all = view;
        int taken = 0;
        for (C c : all) taken += step(c, gameTime, 1, after == null ? null : g -> after.accept(c, g));
        boolean any = true;
        while (taken < budget && any) {
            any = false;
            for (C c : all) {
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
    public List<C> reap() {
        List<C> ended = null;
        for (C c : view) {
            if (c.grid.done() || c.grid.age() > c.grid.maxAge()) {
                remove(c.id);
                if (ended == null) ended = new ArrayList<>();
                ended.add(c);
            }
        }
        return ended == null ? List.of() : ended;
    }

    /**
     * Optical depth along a world segment (density times blocks, every kind's extinction times it), walking the cells
     * it crosses; 0 without a cloud on the way. One block of dense smoke is about {@code fill * extinction} (2.5).
     */
    public float opticalDepth(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        float sum = 0F;
        for (C c : clouds()) {
            if (!c.grid.occupied()) continue;
            sum += c.kind().extinction() * walk(c, fromX, fromY, fromZ, toX, toY, toZ);
        }
        return sum;
    }

    /** Density times length along a world segment through one cloud's cells (3D DDA). */
    public static float walk(SmokeCloud c, double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        int[] b = c.grid.box();
        double minX = c.baseX() + b[0], minY = c.baseY() + b[1], minZ = c.baseZ() + b[2];
        double maxX = c.baseX() + b[3] + 1, maxY = c.baseY() + b[4] + 1, maxZ = c.baseZ() + b[5] + 1;
        double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        double t0 = 0, t1 = 1;
        double[] lo = {minX, minY, minZ}, hi = {maxX, maxY, maxZ}, o = {fromX, fromY, fromZ}, d = {dx, dy, dz};
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
        double px = fromX + dx * t0, py = fromY + dy * t0, pz = fromZ + dz * t0;
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
