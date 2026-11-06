package acup.gunfeel.water;

import java.util.ArrayList;
import java.util.List;

import acup.gunfeel.water.WaterEvents.Event;
import acup.gunfeel.water.WaterEvents.Kind;
import acup.gunfeel.water.WaterEvents.Liquid;
import acup.gunfeel.water.WaterEvents.Type;
import acup.gunfeel.phys.BlockPos;
import acup.gunfeel.phys.Direction;
import acup.gunfeel.phys.Vec3;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraftforge.fluids.BlockFluidBase;

// A bullet's path through liquid in one tick, the same on the server and the client: the segment from -> to is walked
// cell by cell, and in each cell with a liquid it is cut against the liquid's own height (FluidState.getHeight, so
// waterlogged blocks count). Over a path s in a liquid of density rho the speed fraction falls as
// f <- f·exp(-rho·s / L) (WaterClass); an expanding bullet's L falls over its first blocks of water (Expansion), so
// there ln f drops by the integral of rho·ds / L(u) (drag). Under waterStopFraction the bullet stops (s* = L/rho ·
// ln(f / stop) for a plain one) and the path is cut there; a shallow entry into still or sloped water skips (waterSkipAngle), lava stops it where it meets
// it. No world here beyond the Fluids it is given: the tests run it on made-up cells.
public final class WaterPath {
    private WaterPath() {
    }

    // one cell's liquid: height = FluidState.getHeight (0..1), falling = a falling stream (FlowingFluid.FALLING)
    public record Cell(Liquid liquid, float height, boolean falling) {
    }

    public interface Fluids {
        Cell at(int x, int y, int z);

        // FluidState.getFlow of the cell (only asked for where an event happens)
        Vec3 flow(int x, int y, int z);
    }

    // port: 26.2 reads FluidState (getHeight, FALLING, getFlow); 1.7.10 has the liquid block and its metadata. The
    // numbers are 26.2's: a source or a level is (8 - meta) / 9 high, 1 under the same liquid (26.2's getHeight; meta
    // 8 and over, falling, counts as 0), falling is meta 8 and over, the flow is the block's own flow vector
    // (BlockLiquid.getFlowVector through velocityToAddToEntity, normalised as 26.2's getFlow; a Forge fluid's is not
    // normalised, which is fine while only its length over 0 counts). Forge fluids
    // (BlockFluidBase) by their filled share. No waterlogged blocks in 1.7.10.
    public static Fluids of(World level) {
        return new Fluids() {
            @Override
            public Cell at(int x, int y, int z) {
                Block b = level.getBlock(x, y, z);
                Material m = b.getMaterial();
                if (!m.isLiquid()) return null;
                Liquid liquid = m == Material.water ? Liquid.WATER : m == Material.lava ? Liquid.LAVA : Liquid.OTHER;
                return new Cell(liquid, height(level, b, m, x, y, z), falling(level, b, x, y, z));
            }

            @Override
            public Vec3 flow(int x, int y, int z) {
                Block b = level.getBlock(x, y, z);
                if (!b.getMaterial().isLiquid()) return Vec3.ZERO;
                net.minecraft.util.Vec3 v = net.minecraft.util.Vec3.createVectorHelper(0, 0, 0);
                try {
                    b.velocityToAddToEntity(level, x, y, z, null, v);
                } catch (RuntimeException e) {
                    return Vec3.ZERO; // a mod's liquid that wants the entity (null here): taken as still
                }
                return new Vec3(v.xCoord, v.yCoord, v.zCoord);
            }
        };
    }

    // 26.2's FluidState.getHeight: 1 under the same liquid, else its own height
    static float height(World level, Block b, Material m, int x, int y, int z) {
        if (level.getBlock(x, y + 1, z).getMaterial() == m) return 1F;
        int meta = level.getBlockMetadata(x, y, z);
        if (b instanceof BlockFluidBase) return Math.min(1F, Math.abs(((BlockFluidBase) b).getFilledPercentage(level, x, y, z)));
        return ownHeight(meta);
    }

    // a vanilla liquid's own height by its metadata: 26.2's amount / 9 with amount = 8 - meta (a falling one, meta 8
    // and over, is full: amount 8)
    public static float ownHeight(int meta) {
        return (8 - (meta >= 8 ? 0 : meta)) / 9F;
    }

    static boolean falling(World level, Block b, int x, int y, int z) {
        if (!(b instanceof BlockLiquid)) return false;
        return level.getBlockMetadata(x, y, z) >= 8;
    }

    // on = waterBallistics; stopFraction = waterStopFraction; rangeScale = waterRangeScale (times every L);
    // skipAngle = waterSkipAngle for bullets, fuzeSkipAngle = waterFuzeSkipAngle for explosive classes, degrees
    public record Settings(boolean on, double stopFraction, double rangeScale, double skipAngle, double fuzeSkipAngle, int maxSkips) {
        public static final Settings DEFAULT = new Settings(true, 0.15, 1.0, 6.0, 10.0, 2);

        // the server's settings (on a client: the server's it is connected to)
        public static Settings current() {
            return WaterSettings.path();
        }

        public double skipAngle(WaterClass cls) {
            return cls.explosive ? fuzeSkipAngle : skipAngle;
        }

        // for a bullet with an impact fuze (WaterClass.Fuze): it skips by the fuze's angle whatever its class
        public Settings fuzed() {
            return new Settings(on, stopFraction, rangeScale, fuzeSkipAngle, fuzeSkipAngle, maxSkips);
        }
    }

    // a skip off the surface: the bullet is put at `at` and goes on in `dir` (unit) with f
    public record Skip(Vec3 at, Vec3 dir, double f) {
    }

    // water = the path in liquid this tick, blocks; opened = the bullet's path in water so far × the density, at the
    // end (how far an expanding bullet has opened); f = the fraction at the end (at the stop: stopFraction, after lava
    // or a flame going out: 0; after a skip: the f it goes on with); stop = where it stopped, or null; skip = the
    // skip, or null; events in the order along the path. fAt / inLiquid answer for any point of the path (a mob or a
    // block hit there takes f² of its damage).
    public static final class Result {
        public final Vec3 from, to;
        public final double water, f, opened;
        public final Vec3 stop;
        public final Skip skip;
        public final List<Event> events;
        private final double f0, length, len;
        private final WaterClass.Expansion ex;
        // ta, tb, rho, f at ta, opened at ta: per span of liquid along the path
        private final double[] spans;
        private final int count;
        private final Fluids fluids;
        private final WaterClass cls;
        private final Entity bullet;
        // where along the path NTM's test found a block this tick (NaN: none), set by WaterTick
        double blockT = Double.NaN;
        // the surface an impact fuze went off on this tick (WaterTick.blocks), null: none
        acup.gunfeel.BlockRay.Hit fuzeHit;
        // the f the bullet's damage is counted at: the start's while the tick runs, the new f after it (WaterTick)
        double unit;

        Result(Vec3 from, Vec3 to, double water, double f, double opened, Vec3 stop, Skip skip, List<Event> events,
                double f0, double length, WaterClass.Expansion ex, double len, double[] spans, int count, Fluids fluids,
                WaterClass cls, Entity bullet) {
            this.from = from;
            this.to = to;
            this.water = water;
            this.f = f;
            this.opened = opened;
            this.ex = ex;
            this.stop = stop;
            this.skip = skip;
            this.events = events;
            this.f0 = f0;
            this.length = length;
            this.len = len;
            this.spans = spans;
            this.count = count;
            this.fluids = fluids;
            this.cls = cls;
            this.bullet = bullet;
            this.unit = f0;
        }

        // opened at the start of the tick
        private double opened0;

        // f at the start of the tick
        public double startF() {
            return f0;
        }

        public WaterClass cls() {
            return cls;
        }

        // the parts of the path in liquid, in order: {from, to} each (bubbles go along them)
        public List<Vec3[]> wet() {
            List<Vec3[]> out = new ArrayList<>(count);
            Vec3 d = to.subtract(from);
            for (int i = 0; i < count; i++) if (spans[i * N + 1] > spans[i * N])
                out.add(new Vec3[] {from.add(d.scale(spans[i * N])), from.add(d.scale(spans[i * N + 1]))});
            return out;
        }

        // the point's place along the path, 0 at `from`, 1 at `to`
        public double t(Vec3 p) {
            return param(p);
        }

        // where the path ends: the stop, the skip, else `to`. Blocks and mobs past it are not hit.
        public Vec3 end() {
            return stop != null ? stop : skip != null ? skip.at() : to;
        }

        public boolean cut() {
            return stop != null || skip != null;
        }

        // the fraction at the point of the path nearest to p
        public double fAt(Vec3 p) {
            return fAt(param(p));
        }

        public double fAt(double t) {
            double out = f0;
            for (int i = 0; i < count; i++) {
                double ta = spans[i * N], tb = spans[i * N + 1], rho = spans[i * N + 2], fa = spans[i * N + 3], ua = spans[i * N + 4];
                if (t < ta) break;
                double s = (Math.min(t, tb) - ta) * len;
                out = rho <= 0 || !(length < Double.POSITIVE_INFINITY) ? fa
                        : fa * Math.exp(-(drag(length, ex, ua + rho * s) - drag(length, ex, ua)));
            }
            return Math.min(out, f0);
        }

        // how far the bullet has opened at a point of the path (opened at its start and in the water up to there)
        public double openedAt(double t) {
            double out = opened0;
            for (int i = 0; i < count; i++) {
                double ta = spans[i * N], tb = spans[i * N + 1], rho = spans[i * N + 2], ua = spans[i * N + 4];
                if (t < ta) break;
                out = ua + rho * (Math.min(t, tb) - ta) * len;
            }
            return out;
        }

        public boolean inLiquid(Vec3 p) {
            double t = param(p);
            for (int i = 0; i < count; i++) if (t >= spans[i * N] - EPS && t <= spans[i * N + 1] + EPS) return true;
            return false;
        }

        private double param(Vec3 p) {
            if (len < 1e-9) return 0;
            Vec3 d = to.subtract(from);
            return Math.clamp(p.subtract(from).dot(d) / (len * len), 0.0, 1.0);
        }

        // the bullet hit a block at `at` (face = the block's face) while in the liquid: shallow water, BOTTOM; null if
        // the point is not in the liquid
        public Event bottom(Vec3 at, Direction face) {
            if (!inLiquid(at)) return null;
            Vec3 dir = len < 1e-9 ? Vec3.ZERO : to.subtract(from).scale(1 / len);
            Vec3 back = at.subtract(dir.scale(1e-4));
            int x = Mth.floor(back.x), y = Mth.floor(back.y), z = Mth.floor(back.z);
            Cell c = fluids.at(x, y, z);
            if (c == null) return null;
            return event(fluids, Type.BOTTOM, at, face, fAt(at), cls, c, x, y, z, dir, bullet);
        }
    }

    private static final double EPS = 1e-7;
    // numbers per span in Result.spans
    private static final int N = 5;

    public static Result trace(World level, Vec3 from, Vec3 to, double f, WaterClass cls, int skips, long seed,
            Settings s, Entity bullet) {
        return trace(of(level), from, to, f, cls, null, 0, skips, seed, s, bullet);
    }

    public static Result trace(World level, Vec3 from, Vec3 to, double f, WaterClass cls, WaterClass.Expansion ex,
            double opened, int skips, long seed, Settings s, Entity bullet) {
        return trace(of(level), from, to, f, cls, ex, opened, skips, seed, s, bullet);
    }

    public static Result trace(Fluids w, Vec3 from, Vec3 to, double f, WaterClass cls, int skips, long seed, Settings s,
            Entity bullet) {
        return trace(w, from, to, f, cls, null, 0, skips, seed, s, bullet);
    }

    // ex = how the bullet expands (null: it does not), opened = its path in water so far × the density (Result.opened
    // of its last tick); skips = how many times the bullet skipped already; seed = for the skip's turn (the same on
    // both sides: the bullet's id and its skips)
    public static Result trace(Fluids w, Vec3 from, Vec3 to, double f, WaterClass cls, WaterClass.Expansion ex,
            double opened, int skips, long seed, Settings s, Entity bullet) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        double length = cls.length * s.rangeScale();
        ex = scaled(ex, s);
        List<Event> events = new ArrayList<>(2);
        if (len < 1e-9 || !s.on() || cls == WaterClass.NONE)
            return result(from, to, 0, f, opened, null, null, events, f, opened, length, ex, len, new double[0], 0, w, cls, bullet);
        Vec3 dir = d.scale(1 / len);
        // the spans: ta, tb, cell x, y, z, entry face, exit face
        List<double[]> raw = new ArrayList<>();
        walk(w, from, d, len, raw);

        double[] spans = new double[raw.size() * N];
        int count = 0;
        double water = 0, fNow = f, uNow = opened;
        double prevEnd = Double.NaN;
        double[] prev = null;
        Cell prevCell = null;
        Vec3 stop = null;
        Skip skip = null;
        for (double[] r : raw) {
            double ta = r[0], tb = r[1];
            int x = (int) r[2], y = (int) r[3], z = (int) r[4];
            Cell c = w.at(x, y, z);
            if (c == null) continue;
            boolean entering = Double.isNaN(prevEnd) ? ta > EPS : ta > prevEnd + EPS;
            if (entering && prev != null) {
                // left the liquid before this span: out through the previous span's exit face
                events.add(event(w, Type.EXIT, from.add(d.scale(prevEnd)), Direction.from3DDataValue((int) prev[6]), fNow, cls,
                        prevCell, (int) prev[2], (int) prev[3], (int) prev[4], dir, bullet));
            }
            Vec3 at = from.add(d.scale(ta));
            Direction in = Direction.from3DDataValue((int) r[5]);
            if (c.liquid() == Liquid.LAVA || cls.length <= 0) {
                // burns up / goes out where it meets it (or where it is, fired inside)
                span(spans, count++, ta, ta, 0, fNow, uNow);
                fNow = 0;
                stop = at;
                events.add(event(w, Type.STOP, at, entering ? in : nearest(dir), 0, cls, c, x, y, z, dir, bullet));
                break;
            }
            if (entering && in == Direction.UP && !c.falling() && cls.slows() && fNow > 0.5 && skips < s.maxSkips()) {
                double angle = Math.toDegrees(Math.asin(Math.clamp(-dir.y, -1.0, 1.0)));
                if (angle < s.skipAngle(cls)) {
                    double yaw = Math.toRadians((new java.util.SplittableRandom(seed).nextDouble() * 2 - 1) * 2.0);
                    double cos = Math.cos(yaw), sin = Math.sin(yaw);
                    Vec3 out = new Vec3(dir.x * cos - dir.z * sin, -dir.y, dir.x * sin + dir.z * cos);
                    fNow *= 0.7;
                    skip = new Skip(at, out, fNow);
                    events.add(event(w, Type.SKIP, at, Direction.UP, fNow, cls, c, x, y, z, out, bullet));
                    break;
                }
            }
            if (entering) events.add(event(w, Type.ENTER, at, in, fNow, cls, c, x, y, z, dir, bullet));
            double rho = density(c);
            double seg = (tb - ta) * len;
            span(spans, count++, ta, tb, cls.slows() ? rho : 0, fNow, uNow);
            if (cls.slows()) {
                double gNow = drag(length, ex, uNow);
                double fEnd = fNow * Math.exp(-(drag(length, ex, uNow + rho * seg) - gNow));
                if (fEnd < s.stopFraction()) {
                    double sStar = fNow <= s.stopFraction() ? 0
                            : (undrag(length, ex, gNow + Math.log(fNow / s.stopFraction())) - uNow) / rho;
                    double ts = Math.min(tb, ta + Math.max(0, sStar) / len);
                    spans[(count - 1) * N + 1] = ts;
                    water += (ts - ta) * len;
                    uNow += rho * (ts - ta) * len;
                    fNow = s.stopFraction();
                    stop = from.add(d.scale(ts));
                    events.add(event(w, Type.STOP, stop, nearest(dir), fNow, cls, c, x, y, z, dir, bullet));
                    break;
                }
                fNow = fEnd;
                uNow += rho * seg;
            }
            water += seg;
            prevEnd = tb;
            prev = r;
            prevCell = c;
        }
        if (stop == null && skip == null && prev != null && prevEnd < 1 - EPS) {
            events.add(event(w, Type.EXIT, from.add(d.scale(prevEnd)), Direction.from3DDataValue((int) prev[6]), fNow, cls, prevCell,
                    (int) prev[2], (int) prev[3], (int) prev[4], dir, bullet));
        }
        return result(from, to, water, fNow, uNow, stop, skip, events, f, opened, length, ex, len, spans, count, w, cls, bullet);
    }

    private static Result result(Vec3 from, Vec3 to, double water, double f, double opened, Vec3 stop, Skip skip,
            List<Event> events, double f0, double opened0, double length, WaterClass.Expansion ex, double len, double[] spans,
            int count, Fluids w, WaterClass cls, Entity bullet) {
        Result r = new Result(from, to, water, f, opened, stop, skip, events, f0, length, ex, len, spans, count, w, cls, bullet);
        r.opened0 = opened0;
        return r;
    }

    private static void span(double[] spans, int i, double ta, double tb, double rho, double f, double opened) {
        spans[i * N] = ta;
        spans[i * N + 1] = tb;
        spans[i * N + 2] = rho;
        spans[i * N + 3] = f;
        spans[i * N + 4] = opened;
    }

    // waterRangeScale stretches the opening too
    private static WaterClass.Expansion scaled(WaterClass.Expansion ex, Settings s) {
        if (ex == null || ex.span() <= 0 || ex.drop() <= 0) return null;
        return new WaterClass.Expansion(ex.span() * s.rangeScale(), Math.min(ex.drop(), 0.99));
    }

    // how much ln f has dropped over u blocks of water (× the density) since the bullet first met water: the integral
    // of du / L(u), L(u) = length·(1 - drop·min(1, u / span)) for an expanding bullet, else u / length
    public static double drag(double length, WaterClass.Expansion ex, double u) {
        if (ex == null) return u / length;
        double k = ex.drop(), span = ex.span();
        if (u <= span) return -span / (k * length) * Math.log(1 - k * u / span);
        return -span / (k * length) * Math.log(1 - k) + (u - span) / (length * (1 - k));
    }

    // the inverse: the u at which ln f has dropped by g
    public static double undrag(double length, WaterClass.Expansion ex, double g) {
        if (ex == null) return g * length;
        double k = ex.drop(), span = ex.span(), open = drag(length, ex, span);
        if (g <= open) return span / k * (1 - Math.exp(-g * k * length / span));
        return span + (g - open) * length * (1 - k);
    }

    // rho: water and other liquids 1, a falling stream 0.15 (thin, with air in it)
    public static double density(Cell c) {
        return c.falling() ? 0.15 : 1.0;
    }

    // the stopping depth straight down from f = 1, blocks: L/rho · ln(1/stop)
    public static double stopDepth(WaterClass cls, Settings s) {
        return stopDepth(cls, null, s);
    }

    // the same for an expanding bullet
    public static double stopDepth(WaterClass cls, WaterClass.Expansion ex, Settings s) {
        return undrag(cls.length * s.rangeScale(), scaled(ex, s), Math.log(1 / s.stopFraction()));
    }

    // f² by energy: what is left of the damage and of the piercing at fraction f
    public static double energy(double f) {
        return f * f;
    }

    // the hole's size behind water: × (0.5 + 0.5·f)
    public static double holeScale(double f) {
        return 0.5 + 0.5 * f;
    }

    // walks the cells the segment goes through; for each liquid cell it cuts, a span {ta, tb, x, y, z, in, out}
    private static void walk(Fluids w, Vec3 from, Vec3 d, double len, List<double[]> out) {
        int x = Mth.floor(from.x), y = Mth.floor(from.y), z = Mth.floor(from.z);
        int sx = (int) Math.signum(d.x), sy = (int) Math.signum(d.y), sz = (int) Math.signum(d.z);
        double tdx = sx == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(d.x);
        double tdy = sy == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(d.y);
        double tdz = sz == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(d.z);
        double tmx = sx == 0 ? Double.POSITIVE_INFINITY : (sx > 0 ? x + 1 - from.x : from.x - x) * tdx;
        double tmy = sy == 0 ? Double.POSITIVE_INFINITY : (sy > 0 ? y + 1 - from.y : from.y - y) * tdy;
        double tmz = sz == 0 ? Double.POSITIVE_INFINITY : (sz > 0 ? z + 1 - from.z : from.z - z) * tdz;
        int max = 3 * ((int) Math.ceil(len) + 2);
        for (int i = 0; i < max; i++) {
            Cell c = w.at(x, y, z);
            if (c != null && c.height() > 0) {
                double[] span = cut(from, d, x, y, z, c.height());
                if (span != null) out.add(span);
            }
            if (tmx > 1 && tmy > 1 && tmz > 1) break;
            if (tmx <= tmy && tmx <= tmz) {
                x += sx;
                tmx += tdx;
            } else if (tmy <= tmz) {
                y += sy;
                tmy += tdy;
            } else {
                z += sz;
                tmz += tdz;
            }
        }
    }

    // the segment against the box x..x+1, y..y+h, z..z+1: {ta, tb, x, y, z, entry face, exit face} within 0..1
    private static double[] cut(Vec3 from, Vec3 d, int x, int y, int z, double h) {
        double ta = 0, tb = 1;
        int in = -1, out = -1;
        double[] lo = {x, y, z}, hi = {x + 1, y + h, z + 1};
        double[] o = {from.x, from.y, from.z}, v = {d.x, d.y, d.z};
        // the faces: -x WEST, +x EAST, -y DOWN, +y UP, -z NORTH, +z SOUTH
        Direction[][] faces = {{Direction.WEST, Direction.EAST}, {Direction.DOWN, Direction.UP}, {Direction.NORTH, Direction.SOUTH}};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(v[a]) < 1e-12) {
                if (o[a] < lo[a] || o[a] > hi[a]) return null;
                continue;
            }
            double t1 = (lo[a] - o[a]) / v[a], t2 = (hi[a] - o[a]) / v[a];
            // entering through the face the bullet meets first
            Direction near = v[a] > 0 ? faces[a][0] : faces[a][1], far = v[a] > 0 ? faces[a][1] : faces[a][0];
            double tn = Math.min(t1, t2), tf = Math.max(t1, t2);
            if (tn > ta) {
                ta = tn;
                in = near.get3DDataValue();
            }
            if (tf < tb) {
                tb = tf;
                out = far.get3DDataValue();
            }
            if (ta > tb) return null;
        }
        if (tb - ta < 1e-9) return null;
        if (in < 0) in = Direction.UP.get3DDataValue();
        if (out < 0) out = Direction.UP.get3DDataValue();
        return new double[] {ta, tb, x, y, z, in, out};
    }

    private static Event event(Fluids w, Type type, Vec3 at, Direction face, double f, WaterClass cls, Cell c, int x, int y, int z,
            Vec3 dir, Entity bullet) {
        // the column of the same liquid at this spot: its surface and its floor
        int top = y, bottom = y;
        Cell topCell = c;
        for (int k = 1; k <= 32; k++) {
            Cell up = w.at(x, y + k, z);
            if (up == null || up.liquid() != c.liquid()) break;
            top = y + k;
            topCell = up;
        }
        for (int k = 1; k <= 32; k++) {
            Cell down = w.at(x, y - k, z);
            if (down == null || down.liquid() != c.liquid()) break;
            bottom = y - k;
        }
        double surface = top + topCell.height();
        double depth = surface - bottom;
        Vec3 flow = w.flow(x, y, z);
        Kind kind = c.falling() ? Kind.FALLING : depth < 0.55 ? Kind.SHALLOW : flow.lengthSqr() > 1e-8 ? Kind.SLOPE : Kind.STILL;
        Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        return new Event(type, at, face, normal, f, cls, kind, c.liquid(), flow, depth, Math.max(0, surface - at.y), dir,
                new BlockPos(x, y, z), bullet);
    }

    private static Direction nearest(Vec3 d) {
        double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
        if (ay >= ax && ay >= az) return d.y > 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return d.x > 0 ? Direction.EAST : Direction.WEST;
        return d.z > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static final class Mth {
        static int floor(double v) {
            return (int) Math.floor(v);
        }
    }
}
