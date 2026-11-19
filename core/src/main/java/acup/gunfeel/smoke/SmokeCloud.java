package acup.gunfeel.smoke;

import java.util.ArrayList;

/**
 * One cloud: its {@link SmokeGrid} and where the grid's cell (0, 0, 0) was in the world when it was born, the stirs
 * and feeds waiting for an age of it, and what a world point asks of it (inside, how dense, near a path). World
 * coordinates are plain numbers here; the version's adapter keeps the cloud with its own types on top of this
 * (26.2: {@code SmokeClouds.Cloud}, which adds the burst block as a {@code BlockPos}).
 */
public class SmokeCloud {
    public final int id;
    public final SmokeGrid grid;
    /** The world block the cloud burst in: the grid's cell ({@link SmokeGrid#OX}, its {@code oy}, {@link SmokeGrid#OZ}). */
    public final int originX, originY, originZ;
    /** The level's game time the cloud was born at; it is {@code gameTime - born} ticks old. */
    public final long born;
    public final float dirX, dirZ, scale;
    /** The wind over the cloud, blocks a tick, from its birth on ({@link SmokeWind}). */
    public final float windX, windZ;
    /** The server: the age whose hash went out last, so a frozen tick does not send it again. */
    int checkedAge = -1;
    // stirs and feeds for an age the grid has not got to yet, by age (the order they came in within one age)
    private final ArrayList<Event> pending = new ArrayList<>();

    protected SmokeCloud(int id, SmokeGrid grid, int originX, int originY, int originZ, long born, float dirX, float dirZ, float scale) {
        this.id = id;
        this.grid = grid;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.born = born;
        this.dirX = dirX;
        this.dirZ = dirZ;
        this.scale = scale;
        this.windX = grid.windX();
        this.windZ = grid.windZ();
    }

    /** What happens to a cloud at an age of it, the same on every side: a stir or a feed. */
    public interface Event {
        int age();
    }

    /**
     * A grenade of the cloud's kind burst in it at an age of it ({@link SmokeGrid#feed}): at world block x, y, z, flying
     * along dirX, dirZ, {@code scale} of a grenade's smoke.
     */
    public interface FeedEvent extends Event {
        int x();

        int y();

        int z();

        float dirX();

        float dirZ();

        float scale();
    }

    /**
     * A cloud stirred at an age of it, world coordinates. A bullet's path ({@code blast} false): from {@code x0, y0, z0}
     * to {@code x1, y1, z1}, the cells crossed keep {@code a}, the ones next to them {@code b}. A blast: at {@code x0,
     * y0, z0}, reach {@code x1}, {@code a} the most it takes in the middle; {@code b} 0, what it takes is gone (a gas), 1,
     * it comes back (smoke: {@link SmokeGrid#stirBlast}).
     */
    public interface StirEvent extends Event {
        boolean blast();

        double x0();

        double y0();

        double z0();

        double x1();

        double y1();

        double z1();

        float a();

        float b();
    }

    /**
     * Whether the copy is more than a couple of ticks behind its age at this game time: a cloud that came late and is
     * still catching up. It is not drawn, does not thicken the fog and is not compared with the server's hash.
     */
    public boolean catchingUp(long gameTime) {
        return grid.age() < gameTime - born - 2;
    }

    /** The world block of the grid's cell (0, 0, 0) now: the box moves downwind with the smoke ({@link SmokeGrid#drift}). */
    public int baseX() {
        return originX - SmokeGrid.OX + grid.shiftX();
    }

    public int baseY() {
        return originY - grid.oy();
    }

    public int baseZ() {
        return originZ - SmokeGrid.OZ + grid.shiftZ();
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
     * {@link SmokeCloudSet#STIR_AHEAD}) is taken at once: that copy is a step off and the next hash check asks for the
     * grid. Returns false (nothing done) when {@link SmokeCloudSet#PENDING_MAX} wait already.
     */
    public boolean stir(Event st) {
        synchronized (pending) {
            due();
            if (st.age() <= grid.age() && pending.isEmpty()) {
                apply(st);
                return true;
            }
            if (pending.size() >= SmokeCloudSet.PENDING_MAX) return false;
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
        if (e instanceof FeedEvent f) {
            grid.feed(f.x() - baseX(), f.y() - baseY(), f.z() - baseZ(), f.dirX(), f.dirZ(), f.scale());
            return;
        }
        StirEvent st = (StirEvent) e;
        double bx = baseX(), by = baseY(), bz = baseZ();
        if (st.blast()) grid.stirBlast(st.x0() - bx, st.y0() - by, st.z0() - bz, st.x1(), st.a(), st.b() > 0F);
        else grid.stirLine(st.x0() - bx, st.y0() - by, st.z0() - bz, st.x1() - bx, st.y1() - by, st.z1() - bz, st.a(), st.b());
    }

    // feeds taken and waiting
    int feedsAll() {
        synchronized (pending) {
            int n = grid.feeds();
            for (Event e : pending) if (e instanceof FeedEvent) n++;
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
