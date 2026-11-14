package acup.gunfeel.water;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import acup.gunfeel.water.WaterEvents.Kind;
import acup.gunfeel.water.WaterEvents.Liquid;
import acup.gunfeel.water.WaterEvents.Type;
import acup.gunfeel.water.WaterPath.Cell;
import acup.gunfeel.water.WaterPath.Settings;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

class WaterPathTest {
    private static final Settings S = Settings.DEFAULT;
    private static final double SOURCE = (float) (8.0 / 9.0); // FluidState heights are floats

    // made-up cells: a liquid per cell, a flow per cell
    static final class World implements WaterPath.Fluids {
        final Map<Long, Cell> cells = new HashMap<>();
        final Map<Long, Vec3> flows = new HashMap<>();

        static long key(int x, int y, int z) {
            return ((long) x & 0xFFFFF) | (((long) y & 0xFFFFF) << 20) | (((long) z & 0xFFFFF) << 40);
        }

        World put(int x, int y, int z, Cell c) {
            cells.put(key(x, y, z), c);
            return this;
        }

        // a still pool: x0..x1, z0..z1, from the floor y0 up to the top cell y1 (its height a source's 8/9, the
        // cells under it full: water above them)
        World pool(int x0, int x1, int y0, int y1, int z0, int z1) {
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) for (int y = y0; y <= y1; y++)
                put(x, y, z, new Cell(Liquid.WATER, y == y1 ? (float) SOURCE : 1F, false));
            return this;
        }

        @Override
        public Cell at(int x, int y, int z) {
            return cells.get(key(x, y, z));
        }

        @Override
        public Vec3 flow(int x, int y, int z) {
            return flows.getOrDefault(key(x, y, z), Vec3.ZERO);
        }
    }

    private static World deep() {
        return new World().pool(-8, 8, 0, 9, -8, 8);
    }

    private static final double SURFACE = 9 + SOURCE;

    private static WaterPath.Result down(World w, WaterClass cls, double f) {
        return WaterPath.trace(w, new Vec3(0.5, 12, 0.5), new Vec3(0.5, 0.2, 0.5), f, cls, 0, 1L, S, null);
    }

    @Test
    void speedFallsExponentiallyWithThePathInWater() {
        // one block of water: the table's "speed after 1 block"
        Map<WaterClass, Double> after = Map.of(WaterClass.PISTOL, 0.45, WaterClass.RIFLE, 0.21, WaterClass.HEAVY, 0.24,
                WaterClass.AP, 0.33, WaterClass.SLUG, 0.49, WaterClass.DART, 0.61);
        for (var e : after.entrySet()) {
            var r = WaterPath.trace(deep(), new Vec3(0.5, SURFACE + 1, 0.5), new Vec3(0.5, SURFACE - 1, 0.5), 1, e.getKey(), 0, 1L, S, null);
            assertNull(r.stop, e.getKey().name());
            assertEquals(Math.exp(-1 / e.getKey().length), r.fAt(new Vec3(0.5, SURFACE - 1, 0.5)), 1e-9);
            assertEquals(e.getValue(), r.fAt(new Vec3(0.5, SURFACE - 1, 0.5)), 0.006, e.getKey().name());
            assertEquals(1.0, r.fAt(new Vec3(0.5, SURFACE + 0.5, 0.5)), 1e-12, "in the air above the water");
        }
    }

    // a tick that ends right on the surface has no entry (a zero-length span); the next one, starting there, comes in
    // from the air: ENTER through the top at its start. One starting on a cell's edge deep in the water does not.
    @Test
    void aTickStartingOnTheSurfaceComesIn() {
        double a = Math.toRadians(30);
        Vec3 dir = new Vec3(Math.cos(a), -Math.sin(a), 0);
        Vec3 on = new Vec3(0.5, SURFACE, 0.5);
        var first = WaterPath.trace(deep(), on.subtract(dir.scale(4)), on, 1, WaterClass.ROCKET, 0, 1L, S.fuzed(), null);
        assertTrue(first.events.isEmpty(), "the first tick: nothing yet");
        var next = WaterPath.trace(deep(), on, on.add(dir.scale(0.4)), 1, WaterClass.ROCKET, 0, 1L, S.fuzed(), null);
        assertFalse(next.startsIn());
        assertEquals(Type.ENTER, next.events.get(0).type());
        assertEquals(Direction.UP, next.events.get(0).face());
        assertEquals(0, next.events.get(0).at().distanceTo(on), 1e-9);
        // a hair under the surface (the float sum of the motion), still from the air
        var hair = WaterPath.trace(deep(), on.add(0, -1e-9, 0), on.add(dir.scale(0.4)), 1, WaterClass.ROCKET, 0, 1L, S.fuzed(), null);
        assertEquals(Type.ENTER, hair.events.get(0).type());
        // deep in the water on a cell's edge: in it already
        Vec3 edge = new Vec3(2.0, 5.0, 0.5);
        var deepIn = WaterPath.trace(deep(), edge, edge.add(dir.scale(0.4)), 1, WaterClass.ROCKET, 0, 1L, S.fuzed(), null);
        assertTrue(deepIn.startsIn());
        assertTrue(deepIn.events.stream().noneMatch(e -> e.type() == Type.ENTER));
        // from the side, onto a pool's wall of water
        var w = new World().pool(0, 4, 0, 3, -2, 2);
        Vec3 wall = new Vec3(0, 2, 0.5);
        var side = WaterPath.trace(w, wall, wall.add(1, 0, 0), 1, WaterClass.ROCKET, 0, 1L, S.fuzed(), null);
        assertEquals(Type.ENTER, side.events.get(0).type());
        assertEquals(Direction.WEST, side.events.get(0).face());
    }

    @Test
    void stopsAtLTimesLnOneOverStopStraightDown() {
        Map<WaterClass, Double> depth = Map.of(WaterClass.PISTOL, 2.4, WaterClass.RIFLE, 1.25, WaterClass.HEAVY, 1.3,
                WaterClass.AP, 1.7, WaterClass.SLUG, 2.7, WaterClass.SHOT, 0.6, WaterClass.MINI, 1.33, WaterClass.DART, 3.8);
        for (var e : depth.entrySet()) {
            var r = down(deep(), e.getKey(), 1);
            assertNotNull(r.stop, e.getKey().name());
            double d = SURFACE - r.stop.y;
            assertEquals(e.getKey().length * Math.log(1 / 0.15), d, 1e-9);
            assertEquals(WaterPath.stopDepth(e.getKey(), null, S), d, 1e-9);
            assertEquals(e.getValue(), d, 0.1, e.getKey().name());
            assertEquals(0.15, r.f, 1e-12);
            assertEquals(r.stop, r.end());
            assertEquals(d, r.water, 1e-9);
            var types = r.events.stream().map(WaterEvents.Event::type).toList();
            assertEquals(java.util.List.of(Type.ENTER, Type.STOP), types);
            assertEquals(Direction.UP, r.events.get(0).face());
            assertEquals(Kind.STILL, r.events.get(0).kind());
            assertEquals(10.0 - 0 + SOURCE - 1, r.events.get(0).depth(), 1e-6, "the pool's column, floor 0 to the surface");
        }
    }

    @Test
    void rangeScaleStretchesEveryLength() {
        Settings twice = new Settings(true, 0.15, 2.0, 6, 10, 2);
        var r = WaterPath.trace(deep(), new Vec3(0.5, 12, 0.5), new Vec3(0.5, 0.2, 0.5), 1, WaterClass.RIFLE, 0, 1L, twice, null);
        assertEquals(2 * 0.65 * Math.log(1 / 0.15), SURFACE - r.stop.y, 1e-9);
    }

    @Test
    void aSlantedShotStopsShallowerOnALongerPath() {
        // 45° down: the same path, the depth × sin 45°
        Vec3 from = new Vec3(0.5, SURFACE + 2, 0.5);
        var r = WaterPath.trace(deep(), from, from.add(6, -6, 0), 1, WaterClass.PISTOL, 0, 1L, S, null);
        assertNotNull(r.stop);
        double s = 1.25 * Math.log(1 / 0.15);
        assertEquals(s / Math.sqrt(2), SURFACE - r.stop.y, 1e-9);
        assertEquals(s, r.water, 1e-9);
    }

    // notes/water/bubble-demo runPath: f <- f·exp(-ds / L(s)) in small steps; the stop depth, or f at depth d
    private static double[] runPath(double length, WaterClass.Expansion ex, double d) {
        double s = 0, f = 1, ds = 1e-5;
        while (f > 0.15 && s < 10) {
            double e = ex != null ? Math.min(1, s / ex.span()) : 0, le = length * (1 - (ex != null ? ex.drop() * e : 0));
            f *= Math.exp(-ds / le);
            s += ds;
            if (s >= d) return new double[] {Double.NaN, f};
        }
        return new double[] {s, 0};
    }

    @Test
    void expandingBulletsByTheirNames() {
        assertSame(WaterClass.Expansion.JHP, WaterClass.Expansion.of("ammo_standard_p9_jhp"));
        assertSame(WaterClass.Expansion.JHP, WaterClass.Expansion.of("ammo_standard_bmg50_jhp"));
        assertSame(WaterClass.Expansion.SP, WaterClass.Expansion.of("ammo_standard_r556_sp"));
        // b75_exp has the JHP's armour piercing, express the FMJ's: neither expands
        assertNull(WaterClass.Expansion.of("ammo_standard_b75_exp"));
        assertNull(WaterClass.Expansion.of("ammo_standard_m357_express"));
        assertNull(WaterClass.Expansion.of("ammo_standard_p9_fmj"));
    }

    @Test
    void dragAndItsInverseAgree() {
        for (var ex : new WaterClass.Expansion[] {null, WaterClass.Expansion.JHP, WaterClass.Expansion.SP})
            for (double u : new double[] {0, 0.01, 0.12, 0.2, 0.5, 2}) {
                assertEquals(u, WaterPath.undrag(1.25, ex, WaterPath.drag(1.25, ex, u)), 1e-12);
                // L(u) at the start is the plain L, fully open (1 - drop)·L
                double h = 1e-7;
                double slope = (WaterPath.drag(1.25, ex, u + h) - WaterPath.drag(1.25, ex, u)) / h;
                double e = ex == null ? 0 : Math.min(1, (u + h / 2) / ex.span());
                assertEquals(1 / (1.25 * (1 - (ex == null ? 0 : ex.drop() * e))), slope, 1e-4);
            }
    }

    @Test
    void anExpandingBulletStopsSoonerAsTheStepsSay() {
        // 9 mm JHP stops at ~1.3 blocks against FMJ's 2.4; SP in between
        Map<WaterClass.Expansion, Double> depth = Map.of(WaterClass.Expansion.JHP, 1.34, WaterClass.Expansion.SP, 1.81);
        for (var e : depth.entrySet()) {
            double steps = runPath(1.25, e.getKey(), 99)[0];
            var r = WaterPath.trace(deep(), new Vec3(0.5, 12, 0.5), new Vec3(0.5, 0.2, 0.5), 1, WaterClass.PISTOL, e.getKey(), 0, 0, 1L, S, null);
            assertNotNull(r.stop);
            double d = SURFACE - r.stop.y;
            assertEquals(steps, d, 1e-3, "as runPath's steps");
            assertEquals(WaterPath.stopDepth(WaterClass.PISTOL, e.getKey(), S), d, 1e-9);
            assertEquals(e.getValue(), d, 0.01);
            assertEquals(d, r.opened, 1e-9);
            // f and the damage share along the way
            for (double at : new double[] {0.05, 0.12, 0.5, 1.0}) {
                double want = runPath(1.25, e.getKey(), at)[1];
                assertEquals(want, r.fAt(new Vec3(0.5, SURFACE - at, 0.5)), 1e-4, "f at " + at);
            }
        }
        // a rifle's SP too, by the same rule on its own L
        var rifle = WaterPath.trace(deep(), new Vec3(0.5, 12, 0.5), new Vec3(0.5, 0.2, 0.5), 1, WaterClass.RIFLE, WaterClass.Expansion.SP, 0, 0, 1L, S, null);
        assertEquals(runPath(0.65, WaterClass.Expansion.SP, 99)[0], SURFACE - rifle.stop.y, 1e-3);
    }

    @Test
    void theOpeningCarriesOverTicksAndWater() {
        // the JHP's dive cut into ticks of 0.03 blocks (shorter than its opening): the same stop as in one piece
        World w = deep();
        double f = 1, opened = 0;
        Vec3 at = new Vec3(0.5, SURFACE + 0.1, 0.5);
        Vec3 stop = null;
        for (int i = 0; i < 400 && stop == null; i++) {
            var r = WaterPath.trace(w, at, at.add(0, -0.03, 0), f, WaterClass.PISTOL, WaterClass.Expansion.JHP, opened, 0, i, S, null);
            f = r.f;
            opened = r.opened;
            stop = r.stop;
            at = r.end();
        }
        assertNotNull(stop);
        assertEquals(WaterPath.stopDepth(WaterClass.PISTOL, WaterClass.Expansion.JHP, S), SURFACE - stop.y, 1e-9);
        // it stays open out of the water: through a block of water, the air, and into a second pool, it stops after the
        // same path in water as in one pool
        World two = new World().pool(0, 0, 0, 0, 0, 0).pool(3, 3, 0, 0, 0, 0);
        double d = WaterPath.stopDepth(WaterClass.PISTOL, WaterClass.Expansion.JHP, S);
        var r = WaterPath.trace(two, new Vec3(-1, 0.5, 0.5), new Vec3(6, 0.5, 0.5), 1, WaterClass.PISTOL, WaterClass.Expansion.JHP, 0, 0, 1L, S, null);
        assertNotNull(r.stop);
        assertEquals(3 + d - 1, r.stop.x, 1e-9);
        assertEquals(d, r.opened, 1e-9);
    }

    @Test
    void theStopCarriesOverTicks() {
        // the same dive cut into ticks of 0.4 blocks: f handed on, the same stop
        World w = deep();
        double f = 1;
        Vec3 at = new Vec3(0.5, 12, 0.5);
        Vec3 stop = null;
        for (int i = 0; i < 40 && stop == null; i++) {
            var r = WaterPath.trace(w, at, at.add(0, -0.4, 0), f, WaterClass.PISTOL, 0, i, S, null);
            f = r.f;
            stop = r.stop;
            at = r.end();
        }
        assertNotNull(stop);
        assertEquals(1.25 * Math.log(1 / 0.15), SURFACE - stop.y, 1e-9);
    }

    @Test
    void damageAndPiercingGoByEnergy() {
        var r = down(deep(), WaterClass.PISTOL, 1);
        Vec3 half = new Vec3(0.5, SURFACE - 0.5, 0.5), one = new Vec3(0.5, SURFACE - 1, 0.5);
        double fh = r.fAt(half), fo = r.fAt(one);
        assertEquals(Math.exp(-2 * 0.5 / 1.25), fh * fh, 1e-9);
        assertEquals(Math.exp(-2 / 1.25), fo * fo, 1e-9);
        assertEquals(0.5 + 0.5 * Math.exp(-1 / 1.25), WaterPath.holeScale(r.fAt(one)), 1e-9);
        // past the stop nothing is left to hit: the path ends there
        assertTrue(r.fAt(new Vec3(0.5, 1, 0.5)) <= 0.15 + 1e-12);
    }

    private static WaterPath.Result atAngle(World w, double degrees, double f, int skips) {
        double a = Math.toRadians(degrees);
        Vec3 dir = new Vec3(Math.cos(a), -Math.sin(a), 0);
        Vec3 hit = new Vec3(0.5, SURFACE, 0.5);
        Vec3 from = hit.subtract(dir.scale(3));
        return WaterPath.trace(w, from, hit.add(dir.scale(3)), f, WaterClass.RIFLE, skips, 42L, S, null);
    }

    @Test
    void aShallowHitSkipsOffStillWater() {
        var r = atAngle(deep(), 3, 1, 0);
        assertNotNull(r.skip);
        assertNull(r.stop);
        assertEquals(0.7, r.f, 1e-12);
        assertEquals(0.7, r.skip.f(), 1e-12);
        assertEquals(SURFACE, r.skip.at().y, 1e-9);
        // the vertical turned over, the yaw within ±2°
        assertEquals(Math.sin(Math.toRadians(3)), r.skip.dir().y, 1e-9);
        double yaw = Math.toDegrees(Math.atan2(r.skip.dir().z, r.skip.dir().x));
        assertTrue(Math.abs(yaw) <= 2.0 + 1e-9, "yaw " + yaw);
        assertEquals(1.0, r.skip.dir().length(), 1e-9);
        assertEquals(r.skip.at(), r.end());
        assertEquals(java.util.List.of(Type.SKIP), r.events.stream().map(WaterEvents.Event::type).toList());
        assertEquals(0, r.water, 1e-12);
        // the same seed, the same turn: the server and the client agree
        assertEquals(r.skip.dir(), atAngle(deep(), 3, 1, 0).skip.dir());
    }

    @Test
    void aSteeperHitGoesIn() {
        var r = atAngle(deep(), 10, 1, 0);
        assertNull(r.skip);
        assertEquals(Type.ENTER, r.events.get(0).type());
    }

    @Test
    void noSkipWhenSlowTwiceOrOnAStream() {
        assertNull(atAngle(deep(), 3, 0.5, 0).skip, "f must be over 0.5");
        assertNull(atAngle(deep(), 3, 1, 2).skip, "two skips at most");
        assertNotNull(atAngle(deep(), 3, 1, 1).skip);
        World stream = new World();
        for (int x = -8; x <= 8; x++) stream.put(x, 9, 0, new Cell(Liquid.WATER, 1F, true));
        double a = Math.toRadians(3);
        Vec3 dir = new Vec3(Math.cos(a), -Math.sin(a), 0);
        Vec3 hit = new Vec3(0.5, 10, 0.5);
        var r = WaterPath.trace(stream, hit.subtract(dir.scale(3)), hit.add(dir.scale(3)), 1, WaterClass.RIFLE, 0, 1L, S, null);
        assertNull(r.skip);
        assertEquals(Kind.FALLING, r.events.get(0).kind());
    }

    @Test
    void explosivesSkipUnderTheFuzeAngle() {
        double a = Math.toRadians(8);
        Vec3 dir = new Vec3(Math.cos(a), -Math.sin(a), 0);
        Vec3 hit = new Vec3(0.5, SURFACE, 0.5);
        var grenade = WaterPath.trace(deep(), hit.subtract(dir.scale(3)), hit.add(dir.scale(3)), 1, WaterClass.GRENADE, 0, 1L, S, null);
        var bullet = WaterPath.trace(deep(), hit.subtract(dir.scale(3)), hit.add(dir.scale(3)), 1, WaterClass.RIFLE, 0, 1L, S, null);
        assertNotNull(grenade.skip);
        assertNull(bullet.skip);
    }

    @Test
    void firedFromUnderWaterItComesOutSlowed() {
        // eyes 1 block under the surface, straight up: no entry, an exit with f after 1 block, f kept in the air
        Vec3 from = new Vec3(0.5, SURFACE - 1, 0.5);
        var r = WaterPath.trace(deep(), from, from.add(0, 4, 0), 1, WaterClass.DART, 0, 1L, S, null);
        assertNull(r.stop);
        assertEquals(java.util.List.of(Type.EXIT), r.events.stream().map(WaterEvents.Event::type).toList());
        assertEquals(Direction.UP, r.events.get(0).face());
        assertEquals(SURFACE, r.events.get(0).at().y, 1e-9);
        assertEquals(Math.exp(-1 / 2.0), r.f, 1e-9);
        assertEquals(r.f, r.fAt(from.add(0, 3, 0)), 1e-12);
        // a pistol underwater stops at the same path as from the surface
        var p = WaterPath.trace(deep(), new Vec3(0.5, 8, 0.5), new Vec3(10.5, 8, 0.5), 1, WaterClass.PISTOL, 0, 1L, S, null);
        assertEquals(1.25 * Math.log(1 / 0.15), p.stop.x - 0.5, 1e-9);
        assertTrue(p.events.stream().noneMatch(e -> e.type() == Type.ENTER));
    }

    @Test
    void aFallingStreamIsThin() {
        World w = new World();
        for (int y = 0; y < 10; y++) w.put(0, y, 0, new Cell(Liquid.WATER, 1F, true));
        var r = WaterPath.trace(w, new Vec3(-3, 5.5, 0.5), new Vec3(4, 5.5, 0.5), 1, WaterClass.PISTOL, 0, 1L, S, null);
        assertEquals(Math.exp(-0.15 / 1.25), r.f, 1e-9);
        var types = r.events.stream().map(WaterEvents.Event::type).toList();
        assertEquals(java.util.List.of(Type.ENTER, Type.EXIT), types);
        assertEquals(Direction.WEST, r.events.get(0).face());
        assertEquals(Direction.EAST, r.events.get(1).face());
        assertEquals(Kind.FALLING, r.events.get(0).kind());
        assertEquals(1.0, r.water, 1e-9);
    }

    @Test
    void lavaBurnsItWhereItMeetsIt() {
        World w = new World();
        w.put(0, 0, 0, new Cell(Liquid.LAVA, (float) SOURCE, false));
        var r = WaterPath.trace(w, new Vec3(0.5, 3, 0.5), new Vec3(0.5, -2, 0.5), 1, WaterClass.HEAVY, 0, 1L, S, null);
        assertEquals(0, r.f);
        assertEquals(SOURCE, r.stop.y, 1e-9);
        assertEquals(Type.STOP, r.events.get(0).type());
        assertEquals(Liquid.LAVA, r.events.get(0).liquid());
    }

    @Test
    void flameGoesOutAndBeamsAreNotSlowed() {
        var flame = down(deep(), WaterClass.FLAME, 1);
        assertEquals(SURFACE, flame.stop.y, 1e-9);
        assertEquals(0, flame.f);
        var beam = down(deep(), WaterClass.BEAM, 1);
        assertNull(beam.stop);
        assertEquals(1, beam.f);
        assertEquals(Type.ENTER, beam.events.get(0).type());
        var none = down(deep(), WaterClass.NONE, 1);
        assertTrue(none.events.isEmpty());
        var off = WaterPath.trace(deep(), new Vec3(0.5, 12, 0.5), new Vec3(0.5, 0.2, 0.5), 1, WaterClass.PISTOL, 0, 1L,
                new Settings(false, 0.15, 1, 6, 10, 2), null);
        assertEquals(1, off.f);
        assertTrue(off.events.isEmpty());
    }

    @Test
    void shallowWaterIsABlockHitWithASlowedBullet() {
        // a puddle 0.3 deep on a floor (the floor is not in the fluids: the caller's block hit finds it)
        World w = new World();
        for (int x = -3; x <= 3; x++) w.put(x, 5, 0, new Cell(Liquid.WATER, 0.3F, false));
        Vec3 from = new Vec3(0.5, 7, 0.5), floor = new Vec3(0.5, 5, 0.5);
        var r = WaterPath.trace(w, from, floor, 1, WaterClass.SHOT, 0, 1L, S, null);
        assertNull(r.stop, "0.3 blocks of water: buckshot gets through (it stops at 0.57)");
        assertEquals(Kind.SHALLOW, r.events.get(0).kind());
        assertEquals(0.3, r.events.get(0).depth(), 1e-6);
        var bottom = r.bottom(floor, Direction.UP);
        assertNotNull(bottom);
        assertEquals(Type.BOTTOM, bottom.type());
        assertEquals(Math.exp(-0.3 / 0.3), bottom.f(), 1e-6);
        assertNull(r.bottom(new Vec3(0.5, 6.5, 0.5), Direction.UP), "a point in the air is no bottom");
    }

    @Test
    void flowingWaterIsSloped() {
        World w = deep();
        w.flows.put(World.key(0, 9, 0), new Vec3(0.7, 0, 0.7));
        var r = down(w, WaterClass.PISTOL, 1);
        assertEquals(Kind.SLOPE, r.events.get(0).kind());
        assertEquals(new Vec3(0.7, 0, 0.7), r.events.get(0).flow());
    }

    @Test
    void aShotThroughTwoPoolsEntersAndExitsEach() {
        // two puddles 1 block wide, 2 apart, a level shot just under their surface
        World w = new World();
        w.put(0, 0, 0, new Cell(Liquid.WATER, 1F, false)).put(3, 0, 0, new Cell(Liquid.WATER, 1F, false));
        var r = WaterPath.trace(w, new Vec3(-1, 0.5, 0.5), new Vec3(6, 0.5, 0.5), 1, WaterClass.DART, 0, 1L, S, null);
        assertEquals(java.util.List.of(Type.ENTER, Type.EXIT, Type.ENTER, Type.EXIT), r.events.stream().map(WaterEvents.Event::type).toList());
        assertEquals(Math.exp(-2 / 2.0), r.f, 1e-9);
        assertEquals(Math.exp(-1 / 2.0), r.fAt(new Vec3(2, 0.5, 0.5)), 1e-9);
    }

    // A path that meets the water exactly on the edge of its top and a side (both planes at the same t) comes in through
    // the top: the surface it falls on. Before, the side won any tie (WEST here), and a shallow hit there went in
    // instead of skipping, a fuze took it for a hit on a wall, the splash had no surface.
    @Test
    void aPathOnTheTopEdgeComesInThroughTheTop() {
        // a row of full cells, x 0..20 at y 0, the air west of x 0 and above y 1
        World w = new World();
        for (int x = 0; x <= 20; x++) put(w, x);
        // 3.58° down: x crosses 0 and y crosses 1 both at t = 1/16, exactly
        Vec3 from = new Vec3(-1, 1.0625, 0.5), to = from.add(16, -1, 0);
        var r = WaterPath.trace(w, from, to, 1, WaterClass.RIFLE, 0, 42L, S, null);
        assertNotNull(r.skip, "a shallow hit on the edge skips: " + r.events);
        assertEquals(new Vec3(0, 1, 0.5), r.skip.at());
        assertEquals(Direction.UP, r.events.get(0).face());
        // 45° down onto the same edge: in through the top
        Vec3 steep = new Vec3(-0.5, 1.5, 0.5);
        var in = WaterPath.trace(w, steep, steep.add(2, -2, 0), 1, WaterClass.RIFLE, 0, 42L, S, null);
        assertEquals(Type.ENTER, in.events.get(0).type());
        assertEquals(Direction.UP, in.events.get(0).face());
        // up out of it through the same edge: out through the top
        Vec3 low = new Vec3(0.5, 0.5, 0.5);
        var out = WaterPath.trace(w, low, low.add(-1, 1, 0), 1, WaterClass.RIFLE, 0, 42L, S, null);
        assertEquals(Type.EXIT, out.events.getLast().type());
        assertEquals(Direction.UP, out.events.getLast().face());
        // off the edge the sides still win where they are met first
        Vec3 side = new Vec3(-0.5, 0.75, 0.5);
        assertEquals(Direction.WEST, WaterPath.trace(w, side, side.add(1, -0.25, 0), 1, WaterClass.RIFLE, 0, 42L, S, null).events.get(0).face());
    }

    // The same edge as a tick's motion makes it (NTM CE's slug at 5° in the 1.12.2 boom stand): to - from loses the
    // last bits, the side's plane comes out 5e-16 later than the top's, and a strict comparison took the side (WEST).
    // Within the tie it comes in through the top and skips.
    @Test
    void aTickOnTheTopEdgeByRoundingComesInThroughTheTop() {
        World w = new World().pool(858, 897, 4, 11, 202, 241);
        Vec3 from = new Vec3(862.03805301908260, 12.760446322988209, 222.5);
        Vec3 to = from.add(new Vec3(15.939115406979253, -1.3944919047420790, 0));
        var r = WaterPath.trace(w, from, to, 1, WaterClass.SLUG, 0, 1L, S, null);
        assertNotNull(r.skip, "a shallow hit on the edge skips: " + r.events);
        assertEquals(Direction.UP, r.events.get(0).face());
        assertEquals(872, r.skip.at().x, 1e-6);
    }

    private static void put(World w, int x) {
        w.put(x, 0, 0, new Cell(Liquid.WATER, 1F, false));
    }
}
