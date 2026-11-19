package acup.gunfeel.water;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.Vec3d;
import acup.gunfeel.water.LiquidEvents.Hit;
import acup.gunfeel.water.LiquidEvents.Kind;
import acup.gunfeel.water.LiquidEvents.Liquid;
import acup.gunfeel.water.LiquidEvents.Type;

// A bullet's path through liquid in one tick, the same on the server and the client: the segment from -> to is walked
// cell by cell, and in each cell with a liquid it is cut against the liquid's own height (the cell's height, so
// waterlogged blocks count). Over a path s in a liquid of density rho the speed fraction falls as
// f <- f·exp(-rho·s / L) (WaterClass); an expanding bullet's L falls over its first blocks of water (Expansion), so
// there ln f drops by the integral of rho·ds / L(u) (drag). Under waterStopFraction the bullet stops (s* = L/rho ·
// ln(f / stop) for a plain one) and the path is cut there; a shallow entry into still or sloped water skips
// (waterSkipAngle), lava stops it where it meets it. No world here beyond the Fluids it is given (the port: the
// version's adapter reads its world through it, the tests run it on made-up cells). 26.2's WaterPath keeps the
// game's types on top of this.
public class LiquidPath {
    protected LiquidPath() {
    }

    // the faces of a cell, as Minecraft's Direction.get3DDataValue numbers them, and their normals
    public static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    static final int[] STEP_X = {0, 0, 0, 0, -1, 1}, STEP_Y = {-1, 1, 0, 0, 0, 0}, STEP_Z = {0, 0, -1, 1, 0, 0};

    // one cell's liquid: height = its surface in the cell (0..1), falling = a falling stream
    public record Cell(Liquid liquid, float height, boolean falling) {
    }

    // the world, as the path asks it
    public interface Fluids {
        @Nullable Cell at(int x, int y, int z);

        // the liquid's flow in the cell (only asked for where an event happens)
        Vec3d flow(int x, int y, int z);
    }

    // on = waterBallistics; stopFraction = waterStopFraction; rangeScale = waterRangeScale (times every L);
    // skipAngle = waterSkipAngle for bullets, fuzeSkipAngle = waterFuzeSkipAngle for explosive classes, degrees
    public record Settings(boolean on, double stopFraction, double rangeScale, double skipAngle, double fuzeSkipAngle, int maxSkips) {
        public static final Settings DEFAULT = new Settings(true, 0.15, 1.0, 6.0, 10.0, 2);

        public double skipAngle(WaterClass cls) {
            return cls.explosive ? fuzeSkipAngle : skipAngle;
        }

        // for a bullet with an impact fuze (WaterClass.Fuze): it skips by the fuze's angle whatever its class
        public Settings fuzed() {
            return new Settings(on, stopFraction, rangeScale, fuzeSkipAngle, fuzeSkipAngle, maxSkips);
        }
    }

    // a skip off the surface: the bullet is put at `at` and goes on in `dir` (unit) with f
    public record Skip(Vec3d at, Vec3d dir, double f) {
    }

    // water = the path in liquid this tick, blocks; opened = the bullet's path in water so far × the density, at the
    // end (how far an expanding bullet has opened); f = the fraction at the end (at the stop: stopFraction, after lava
    // or a flame going out: 0; after a skip: the f it goes on with); stop = where it stopped, or null; skip = the
    // skip, or null; events in the order along the path. fAt / inLiquid answer for any point of the path (a mob or a
    // block hit there takes f² of its damage).
    public static final class Result {
        public final Vec3d from, to;
        public final double water, f, opened;
        public final @Nullable Vec3d stop;
        public final @Nullable Skip skip;
        public final List<Hit> events;
        private final double f0, length, len;
        private final WaterClass.@Nullable Expansion ex;
        // ta, tb, rho, f at ta, opened at ta: per span of liquid along the path
        private final double[] spans;
        private final int count;
        private final Fluids fluids;
        private final WaterClass cls;
        private final @Nullable Object bullet;
        // opened at the start of the tick
        private double opened0;
        // the path starts in liquid (not on its surface coming in from the air)
        private boolean startsIn;

        Result(Vec3d from, Vec3d to, double water, double f, double opened, @Nullable Vec3d stop, @Nullable Skip skip, List<Hit> events,
                double f0, double length, WaterClass.@Nullable Expansion ex, double len, double[] spans, int count, Fluids fluids,
                WaterClass cls, @Nullable Object bullet) {
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
        }

        // f at the start of the tick
        public double startF() {
            return f0;
        }

        public WaterClass cls() {
            return cls;
        }

        // the path starts in liquid; one starting right on its surface (the last tick ended there) comes in from outside
        public boolean startsIn() {
            return startsIn;
        }

        // the parts of the path in liquid, in order: {from, to} each (bubbles go along them)
        public List<Vec3d[]> wet() {
            List<Vec3d[]> out = new ArrayList<>(count);
            Vec3d d = to.subtract(from);
            for (int i = 0; i < count; i++) if (spans[i * N + 1] > spans[i * N])
                out.add(new Vec3d[] {from.add(d.scale(spans[i * N])), from.add(d.scale(spans[i * N + 1]))});
            return out;
        }

        // the point's place along the path, 0 at `from`, 1 at `to`
        public double t(Vec3d p) {
            return param(p);
        }

        // where the path ends: the stop, the skip, else `to`. Blocks and mobs past it are not hit.
        public Vec3d end() {
            return stop != null ? stop : skip != null ? skip.at() : to;
        }

        public boolean cut() {
            return stop != null || skip != null;
        }

        // the fraction at the point of the path nearest to p
        public double fAt(Vec3d p) {
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

        public boolean inLiquid(Vec3d p) {
            double t = param(p);
            for (int i = 0; i < count; i++) if (t >= spans[i * N] - EPS && t <= spans[i * N + 1] + EPS) return true;
            return false;
        }

        private double param(Vec3d p) {
            if (len < 1e-9) return 0;
            Vec3d d = to.subtract(from);
            return Math.clamp(p.subtract(from).dot(d) / (len * len), 0.0, 1.0);
        }

        // the bullet hit a block at `at` (face = the block's face, a face index) while in the liquid: shallow water,
        // BOTTOM; null if the point is not in the liquid
        public @Nullable Hit bottom(Vec3d at, int face) {
            if (!inLiquid(at)) return null;
            Vec3d dir = len < 1e-9 ? Vec3d.ZERO : to.subtract(from).scale(1 / len);
            Vec3d back = at.subtract(dir.scale(1e-4));
            int x = floor(back.x()), y = floor(back.y()), z = floor(back.z());
            Cell c = fluids.at(x, y, z);
            if (c == null) return null;
            return event(fluids, Type.BOTTOM, at, face, fAt(at), cls, c, x, y, z, dir, bullet);
        }
    }

    private static final double EPS = 1e-7;
    // numbers per span in Result.spans
    private static final int N = 5;

    public static Result trace(Fluids w, Vec3d from, Vec3d to, double f, WaterClass cls, int skips, long seed, Settings s,
            @Nullable Object bullet) {
        return trace(w, from, to, f, cls, null, 0, skips, seed, s, bullet);
    }

    // ex = how the bullet expands (null: it does not), opened = its path in water so far × the density (Result.opened
    // of its last tick); skips = how many times the bullet skipped already; seed = for the skip's turn (the same on
    // both sides: the bullet's id and its skips)
    public static Result trace(Fluids w, Vec3d from, Vec3d to, double f, WaterClass cls, WaterClass.@Nullable Expansion ex,
            double opened, int skips, long seed, Settings s, @Nullable Object bullet) {
        Vec3d d = to.subtract(from);
        double len = d.length();
        double length = cls.length * s.rangeScale();
        ex = scaled(ex, s);
        List<Hit> events = new ArrayList<>(2);
        if (len < 1e-9 || !s.on() || cls == WaterClass.NONE)
            return result(from, to, 0, f, opened, null, null, events, f, opened, length, ex, len, new double[0], 0, w, cls, bullet);
        Vec3d dir = d.scale(1 / len);
        // the spans: ta, tb, cell x, y, z, entry face, exit face
        List<double[]> raw = new ArrayList<>();
        walk(w, from, d, len, raw);

        double[] spans = new double[raw.size() * N];
        int count = 0;
        double water = 0, fNow = f, uNow = opened;
        double prevEnd = Double.NaN;
        double[] prev = null;
        Cell prevCell = null;
        Vec3d stop = null;
        Skip skip = null;
        boolean startsIn = false;
        for (double[] r : raw) {
            double ta = r[0], tb = r[1];
            int x = (int) r[2], y = (int) r[3], z = (int) r[4];
            Cell c = w.at(x, y, z);
            if (c == null) continue;
            boolean entering = Double.isNaN(prevEnd) ? ta > EPS || !inside(w, from.subtract(dir.scale(BEHIND))) : ta > prevEnd + EPS;
            if (entering && prev != null) {
                // left the liquid before this span: out through the previous span's exit face
                events.add(event(w, Type.EXIT, from.add(d.scale(prevEnd)), face((int) prev[6]), fNow, cls,
                        prevCell, (int) prev[2], (int) prev[3], (int) prev[4], dir, bullet));
            }
            Vec3d at = from.add(d.scale(ta));
            int in = face((int) r[5]);
            // a tick that ended right on the surface (a zero-length entry is no span) starts the next one there: it
            // comes in now, through the face it starts on
            if (Double.isNaN(prevEnd) && ta <= EPS) {
                if (entering) in = startFace(from, dir, x, y, z, c.height(), EPS * len);
                else startsIn = true;
            }
            if (c.liquid() == Liquid.LAVA || cls.length <= 0) {
                // burns up / goes out where it meets it (or where it is, fired inside)
                span(spans, count++, ta, ta, 0, fNow, uNow);
                fNow = 0;
                stop = at;
                events.add(event(w, Type.STOP, at, entering ? in : nearest(dir), 0, cls, c, x, y, z, dir, bullet));
                break;
            }
            if (entering && in == UP && !c.falling() && cls.slows() && fNow > 0.5 && skips < s.maxSkips()) {
                double angle = Math.toDegrees(Math.asin(Math.clamp(-dir.y(), -1.0, 1.0)));
                if (angle < s.skipAngle(cls)) {
                    double yaw = Math.toRadians((new java.util.SplittableRandom(seed).nextDouble() * 2 - 1) * 2.0);
                    double cos = Math.cos(yaw), sin = Math.sin(yaw);
                    Vec3d out = new Vec3d(dir.x() * cos - dir.z() * sin, -dir.y(), dir.x() * sin + dir.z() * cos);
                    fNow *= 0.7;
                    skip = new Skip(at, out, fNow);
                    events.add(event(w, Type.SKIP, at, UP, fNow, cls, c, x, y, z, out, bullet));
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
            events.add(event(w, Type.EXIT, from.add(d.scale(prevEnd)), face((int) prev[6]), fNow, cls, prevCell,
                    (int) prev[2], (int) prev[3], (int) prev[4], dir, bullet));
        }
        Result out = result(from, to, water, fNow, uNow, stop, skip, events, f, opened, length, ex, len, spans, count, w, cls, bullet);
        out.startsIn = startsIn;
        return out;
    }

    // a face index as Direction.from3DDataValue reads one (any int: its remainder by 6)
    static int face(int i) {
        return Math.abs(i % 6);
    }

    // how far behind a path's start to look whether it starts in liquid, blocks
    private static final double BEHIND = 1e-5;

    // the point is in a cell's liquid (under its surface)
    private static boolean inside(Fluids w, Vec3d p) {
        int x = floor(p.x()), y = floor(p.y()), z = floor(p.z());
        Cell c = w.at(x, y, z);
        return c != null && c.height() > 0 && p.y() - y <= c.height();
    }

    // the face of the cell's liquid (x..x+1, y..y+h, z..z+1) a path starting on it comes in through; `near` is how far
    // off a face a start still counts as on it, the same as the span's start (ta ≤ EPS, of the path's length)
    private static int startFace(Vec3d from, Vec3d dir, int x, int y, int z, double h, double near) {
        double e = Math.max(1e-6, near + 1e-9);
        if (dir.y() < 0 && Math.abs(from.y() - (y + h)) < e) return UP;
        if (dir.y() > 0 && Math.abs(from.y() - y) < e) return DOWN;
        if (dir.x() > 0 && Math.abs(from.x() - x) < e) return WEST;
        if (dir.x() < 0 && Math.abs(from.x() - (x + 1)) < e) return EAST;
        if (dir.z() > 0 && Math.abs(from.z() - z) < e) return NORTH;
        if (dir.z() < 0 && Math.abs(from.z() - (z + 1)) < e) return SOUTH;
        return UP;
    }

    private static Result result(Vec3d from, Vec3d to, double water, double f, double opened, @Nullable Vec3d stop, @Nullable Skip skip,
            List<Hit> events, double f0, double opened0, double length, WaterClass.@Nullable Expansion ex, double len, double[] spans,
            int count, Fluids w, WaterClass cls, @Nullable Object bullet) {
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
    private static WaterClass.@Nullable Expansion scaled(WaterClass.@Nullable Expansion ex, Settings s) {
        if (ex == null || ex.span() <= 0 || ex.drop() <= 0) return null;
        return new WaterClass.Expansion(ex.span() * s.rangeScale(), Math.min(ex.drop(), 0.99));
    }

    // how much ln f has dropped over u blocks of water (× the density) since the bullet first met water: the integral
    // of du / L(u), L(u) = length·(1 - drop·min(1, u / span)) for an expanding bullet, else u / length
    public static double drag(double length, WaterClass.@Nullable Expansion ex, double u) {
        if (ex == null) return u / length;
        double k = ex.drop(), span = ex.span();
        if (u <= span) return -span / (k * length) * Math.log(1 - k * u / span);
        return -span / (k * length) * Math.log(1 - k) + (u - span) / (length * (1 - k));
    }

    // the inverse: the u at which ln f has dropped by g
    public static double undrag(double length, WaterClass.@Nullable Expansion ex, double g) {
        if (ex == null) return g * length;
        double k = ex.drop(), span = ex.span(), open = drag(length, ex, span);
        if (g <= open) return span / k * (1 - Math.exp(-g * k * length / span));
        return span + (g - open) * length * (1 - k);
    }

    // rho: water and other liquids 1, a falling stream 0.15 (thin, with air in it)
    public static double density(Cell c) {
        return c.falling() ? 0.15 : 1.0;
    }

    // the stopping depth straight down from f = 1, blocks: L/rho · ln(1/stop);
    // ex = how the bullet expands (null: it does not)
    public static double stopDepth(WaterClass cls, WaterClass.@Nullable Expansion ex, Settings s) {
        return undrag(cls.length * s.rangeScale(), scaled(ex, s), Math.log(1 / s.stopFraction()));
    }

    // the hole's size behind water: × (0.5 + 0.5·f)
    public static double holeScale(double f) {
        return 0.5 + 0.5 * f;
    }

    // the water along from -> to, in order: {start, end, rho} per cell of water, in blocks from `from` (a beam in
    // water, BeamWater). Lava and other liquids are not water here
    public static List<double[]> water(Fluids w, Vec3d from, Vec3d to) {
        Vec3d d = to.subtract(from);
        double len = d.length();
        List<double[]> out = new ArrayList<>();
        if (len < 1e-9) return out;
        List<double[]> raw = new ArrayList<>();
        walk(w, from, d, len, raw);
        for (double[] r : raw) {
            Cell c = w.at((int) r[2], (int) r[3], (int) r[4]);
            if (c != null && c.liquid() == Liquid.WATER) out.add(new double[] {r[0] * len, r[1] * len, density(c)});
        }
        return out;
    }

    // walks the cells the segment goes through; for each liquid cell it cuts, a span {ta, tb, x, y, z, in, out}
    private static void walk(Fluids w, Vec3d from, Vec3d d, double len, List<double[]> out) {
        int x = floor(from.x()), y = floor(from.y()), z = floor(from.z());
        int sx = (int) Math.signum(d.x()), sy = (int) Math.signum(d.y()), sz = (int) Math.signum(d.z());
        double tdx = sx == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(d.x());
        double tdy = sy == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(d.y());
        double tdz = sz == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(d.z());
        double tmx = sx == 0 ? Double.POSITIVE_INFINITY : (sx > 0 ? x + 1 - from.x() : from.x() - x) * tdx;
        double tmy = sy == 0 ? Double.POSITIVE_INFINITY : (sy > 0 ? y + 1 - from.y() : from.y() - y) * tdy;
        double tmz = sz == 0 ? Double.POSITIVE_INFINITY : (sz > 0 ? z + 1 - from.z() : from.z() - z) * tdz;
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

    private static final int[] AXES = {1, 0, 2};
    // two faces met at once, in t (0..1 of the segment)
    private static final double TIE = 1e-9;

    // the segment against the box x..x+1, y..y+h, z..z+1: {ta, tb, x, y, z, entry face, exit face} within 0..1
    private static double @Nullable [] cut(Vec3d from, Vec3d d, int x, int y, int z, double h) {
        double ta = 0, tb = 1;
        int in = -1, out = -1;
        double[] lo = {x, y, z}, hi = {x + 1, y + h, z + 1};
        double[] o = {from.x(), from.y(), from.z()}, v = {d.x(), d.y(), d.z()};
        // the faces: -x WEST, +x EAST, -y DOWN, +y UP, -z NORTH, +z SOUTH
        int[][] faces = {{WEST, EAST}, {DOWN, UP}, {NORTH, SOUTH}};
        // y first: a path that meets two faces at once, on a cell's edge, goes through the top or bottom one (the
        // surface it falls on), a tie keeping the axis met first. A tie is within TIE: the two planes' t differ by the
        // rounding of to - from (a tick's motion), and a strict comparison would give the side either way
        for (int a : AXES) {
            if (Math.abs(v[a]) < 1e-12) {
                if (o[a] < lo[a] || o[a] > hi[a]) return null;
                continue;
            }
            double t1 = (lo[a] - o[a]) / v[a], t2 = (hi[a] - o[a]) / v[a];
            // entering through the face the bullet meets first
            int near = v[a] > 0 ? faces[a][0] : faces[a][1], far = v[a] > 0 ? faces[a][1] : faces[a][0];
            double tn = Math.min(t1, t2), tf = Math.max(t1, t2);
            if (tn > ta) {
                if (in < 0 || tn - ta > TIE) in = near;
                ta = tn;
            }
            if (tf < tb) {
                if (out < 0 || tb - tf > TIE) out = far;
                tb = tf;
            }
            if (ta > tb) return null;
        }
        if (tb - ta < 1e-9) return null;
        if (in < 0) in = UP;
        if (out < 0) out = UP;
        return new double[] {ta, tb, x, y, z, in, out};
    }

    private static Hit event(Fluids w, Type type, Vec3d at, int face, double f, WaterClass cls, Cell c, int x, int y, int z,
            Vec3d dir, @Nullable Object bullet) {
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
        Vec3d flow = w.flow(x, y, z);
        Kind kind = c.falling() ? Kind.FALLING : depth < 0.55 ? Kind.SHALLOW : flow.lengthSqr() > 1e-8 ? Kind.SLOPE : Kind.STILL;
        Vec3d normal = new Vec3d(STEP_X[face], STEP_Y[face], STEP_Z[face]);
        return new Hit(type, at, face, normal, f, cls, kind, c.liquid(), flow, depth, Math.max(0, surface - at.y()), dir,
                x, y, z, bullet);
    }

    private static int nearest(Vec3d d) {
        double ax = Math.abs(d.x()), ay = Math.abs(d.y()), az = Math.abs(d.z());
        if (ay >= ax && ay >= az) return d.y() > 0 ? UP : DOWN;
        if (ax >= az) return d.x() > 0 ? EAST : WEST;
        return d.z() > 0 ? SOUTH : NORTH;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
