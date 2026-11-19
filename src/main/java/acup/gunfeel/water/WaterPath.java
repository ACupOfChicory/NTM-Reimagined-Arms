package acup.gunfeel.water;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.Vec3d;
import acup.gunfeel.Vecs;
import acup.gunfeel.Wiring;
import acup.gunfeel.water.WaterEvents.Event;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

// A bullet's path through liquid in one tick, on 26.2: core's LiquidPath (the walk, the drag, the stop, the skip, the
// events) with the game's types on it. The path is traced in core on the same numbers; its points come back as Vec3,
// its faces as Direction, its cells as BlockPos, its events as WaterEvents.Event, the bullet as the Entity it was.
// The world is read through LiquidPath.Fluids (of(level): Wiring.liquids).
public final class WaterPath extends LiquidPath {
    private WaterPath() {
    }

    public static Fluids of(BlockGetter level) {
        return Wiring.liquids(level);
    }

    // a skip off the surface: the bullet is put at `at` and goes on in `dir` (unit) with f
    public record Skip(Vec3 at, Vec3 dir, double f) {
    }

    // LiquidPath.Result in the game's types (see there), with what WaterTick keeps on it for the tick
    public static final class Result {
        public final Vec3 from, to;
        public final double water, f, opened;
        public final @Nullable Vec3 stop;
        public final @Nullable Skip skip;
        // in the order along the path; WaterTick takes out the ones past a block and adds the bottom it hit
        public final List<Event> events;
        private final LiquidPath.Result path;
        // where along the path NTM's test found a block this tick (NaN: none), set by WaterTick
        double blockT = Double.NaN;
        // the surface an impact fuze went off on this tick (WaterTick.blocks), null: none
        net.minecraft.world.phys.@Nullable BlockHitResult fuzeHit;
        // the f the bullet's damage is counted at: the start's while the tick runs, the new f after it (WaterTick)
        double unit;

        Result(Vec3 from, Vec3 to, LiquidPath.Result path) {
            this.from = from;
            this.to = to;
            this.water = path.water;
            this.f = path.f;
            this.opened = path.opened;
            this.stop = path.stop == null ? null : Vecs.mc(path.stop);
            this.skip = path.skip == null ? null : new Skip(Vecs.mc(path.skip.at()), Vecs.mc(path.skip.dir()), path.skip.f());
            this.events = new ArrayList<>(Math.max(2, path.events.size()));
            for (LiquidEvents.Hit h : path.events) events.add(event(h));
            this.path = path;
            this.unit = path.startF();
        }

        // f at the start of the tick
        public double startF() {
            return path.startF();
        }

        public WaterClass cls() {
            return path.cls();
        }

        // the path starts in liquid; one starting right on its surface (the last tick ended there) comes in from outside
        public boolean startsIn() {
            return path.startsIn();
        }

        // the parts of the path in liquid, in order: {from, to} each (bubbles go along them)
        public List<Vec3[]> wet() {
            List<Vec3d[]> wet = path.wet();
            List<Vec3[]> out = new ArrayList<>(wet.size());
            for (Vec3d[] w : wet) out.add(new Vec3[] {Vecs.mc(w[0]), Vecs.mc(w[1])});
            return out;
        }

        // the point's place along the path, 0 at `from`, 1 at `to`
        public double t(Vec3 p) {
            return path.t(Vecs.of(p));
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
            return path.fAt(Vecs.of(p));
        }

        public double fAt(double t) {
            return path.fAt(t);
        }

        // how far the bullet has opened at a point of the path (opened at its start and in the water up to there)
        public double openedAt(double t) {
            return path.openedAt(t);
        }

        public boolean inLiquid(Vec3 p) {
            return path.inLiquid(Vecs.of(p));
        }

        // the bullet hit a block at `at` (face = the block's face) while in the liquid: shallow water, BOTTOM; null if
        // the point is not in the liquid
        public @Nullable Event bottom(Vec3 at, Direction face) {
            LiquidEvents.Hit h = path.bottom(Vecs.of(at), face.get3DDataValue());
            return h == null ? null : event(h);
        }
    }

    // core's event in the game's types
    static Event event(LiquidEvents.Hit h) {
        return new Event(h.type(), Vecs.mc(h.at()), Direction.from3DDataValue(h.face()), Vecs.mc(h.normal()), h.f(), h.cls(), h.kind(),
                h.liquid(), Vecs.mc(h.flow()), h.depth(), h.under(), Vecs.mc(h.dir()), new BlockPos(h.x(), h.y(), h.z()), (Entity) h.bullet());
    }

    public static Result trace(BlockGetter level, Vec3 from, Vec3 to, double f, WaterClass cls, WaterClass.@Nullable Expansion ex,
            double opened, int skips, long seed, Settings s, @Nullable Entity bullet) {
        return trace(of(level), from, to, f, cls, ex, opened, skips, seed, s, bullet);
    }

    public static Result trace(Fluids w, Vec3 from, Vec3 to, double f, WaterClass cls, int skips, long seed, Settings s,
            @Nullable Entity bullet) {
        return trace(w, from, to, f, cls, null, 0, skips, seed, s, bullet);
    }

    // ex = how the bullet expands (null: it does not), opened = its path in water so far × the density (Result.opened
    // of its last tick); skips = how many times the bullet skipped already; seed = for the skip's turn (the same on
    // both sides: the bullet's id and its skips)
    public static Result trace(Fluids w, Vec3 from, Vec3 to, double f, WaterClass cls, WaterClass.@Nullable Expansion ex,
            double opened, int skips, long seed, Settings s, @Nullable Entity bullet) {
        return new Result(from, to, LiquidPath.trace(w, Vecs.of(from), Vecs.of(to), f, cls, ex, opened, skips, seed, s, bullet));
    }

    // the water along from -> to, in order: {start, end, rho} per cell of water, in blocks from `from` (a beam in
    // water, BeamWater). Lava and other liquids are not water here
    public static List<double[]> water(Fluids w, Vec3 from, Vec3 to) {
        return LiquidPath.water(w, Vecs.of(from), Vecs.of(to));
    }
}
