package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import acup.gunfeel.host.PierceShots;

/** a5096a9's control flow against core, including the trace and state left by each failed port call. */
class PierceStepsTest {
    private record Point(double x, double y, double z) {
        double distanceTo(Point from) {
            double dx = from.x - x, dy = from.y - y, dz = from.z - z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    private record Hit(int id, Point point, float water, float cost) {}
    private static final Point ZERO = new Point(0, 0, 0);
    private static final Object BULLET = new Object(), LEVEL = new Object();

    private static final class Failure extends RuntimeException {}

    private static final class Reads implements PierceShots {
        final List<Object> calls = new ArrayList<>();
        final Failure failure = new Failure();
        float damage = 79.325F;
        boolean removed;
        Object stopped;
        int failAt = -1;

        void call(Object... values) {
            calls.add(List.of(values));
            if (calls.size() == failAt) throw failure;
        }

        @Override public Object location(Object hit) {
            call("location", hit);
            return ((Hit) hit).point;
        }
        @Override public double distance(Object point, Object from) {
            call("distance", point, from);
            return ((Point) point).distanceTo((Point) from);
        }
        @Override public Object level(Object bullet) {
            assertSame(BULLET, bullet);
            call("level");
            return LEVEL;
        }
        @Override public boolean removed(Object bullet) {
            assertSame(BULLET, bullet);
            call("removed");
            return removed;
        }
        @Override public float water(Object bullet, Object in) {
            assertSame(BULLET, bullet);
            call("water", in);
            return ((Hit) in).water;
        }
        @Override public float damage(Object bullet) {
            assertSame(BULLET, bullet);
            call("damage", Float.floatToRawIntBits(damage));
            return damage;
        }
        @Override public void damage(Object bullet, float value) {
            assertSame(BULLET, bullet);
            call("damage=", Float.floatToRawIntBits(value));
            damage = value;
        }
        @Override public float pierced(Object level, Object in, Object out, float power, Object bullet, Materials.Penetration penetration) {
            assertSame(LEVEL, level);
            assertSame(BULLET, bullet);
            call("pierced", in, out, Float.floatToRawIntBits(power), Double.doubleToRawLongBits(penetration.spent()));
            penetration.restore(penetration.spent() + 0.375);
            float cost = ((Hit) in).cost;
            return cost < 0F ? -1F : power - cost;
        }
        @Override public void stopAt(Object bullet, Object in) {
            assertSame(BULLET, bullet);
            call("stopAt", in);
            stopped = in;
            removed = true;
        }
        @Override public int id(Object bullet) {
            assertSame(BULLET, bullet);
            call("id");
            return 17;
        }
        @Override public void logLater(int id, int blocks, Object bullet) {
            assertSame(BULLET, bullet);
            call("logLater", id, blocks, Float.floatToRawIntBits(damage));
        }
        @Override public void logBlock(Object bullet, Object level, Object in, float left) {
            assertSame(LEVEL, level);
            assertSame(BULLET, bullet);
            call("logBlock", in, Float.floatToRawIntBits(damage), Float.floatToRawIntBits(left));
        }
    }

    // The old code, changing only game accesses to the same fake port used by core.
    private static final class Old {
        final Materials.Penetration penetration = new Materials.Penetration();
        private final PierceLedger<Object> steps = new PierceLedger<>();
        private Object from = ZERO;

        void start(Object from) {
            this.from = from;
            steps.clear();
        }

        void pierced(Object in, Object out, float before, double spentBefore, PierceShots shots) {
            steps.pierced(shots.distance(shots.location(in), from), before, spentBefore, in, out);
        }

        void enter(Object bullet, Object at, PierceShots shots) {
            if (steps.size() == 0) return;
            int k = steps.firstFrom(shots.distance(at, from));
            if (steps.paidPast(k)) {
                shots.damage(bullet, steps.damageBefore(k));
                penetration.restore(steps.spentBefore(k));
                steps.unpayTo(k);
                if (TestLog.server) shots.logLater(shots.id(bullet), steps.size() - k, bullet);
            } else {
                payTo(bullet, k, shots);
            }
        }

        void finish(Object bullet, PierceShots shots) {
            payTo(bullet, steps.size(), shots);
            steps.clear();
        }

        private void payTo(Object bullet, int k, PierceShots shots) {
            Object level = shots.level(bullet);
            while (steps.owes(k) && !shots.removed(bullet)) {
                int i = steps.payNext();
                Object in = steps.wayIn(i), out = steps.wayOut(i);
                float wk = shots.water(bullet, in);
                float left = wk == 1F ? shots.pierced(level, in, out, shots.damage(bullet), bullet, penetration)
                        : wk <= 0F ? -1F : shots.pierced(level, in, out, shots.damage(bullet) * wk, bullet, penetration) / wk;
                if (TestLog.server) shots.logBlock(bullet, level, in, left);
                if (left < 0F) {
                    shots.stopAt(bullet, in);
                    return;
                }
                shots.damage(bullet, left);
            }
        }
    }

    private static final class Pair {
        final Old old = new Old();
        final PierceStepsBase now = new PierceStepsBase();
        final Reads a = new Reads(), b = new Reads();

        Pair() {
            now.from = ZERO;
        }

        void start(Object from) {
            old.start(from);
            now.startOf(from);
            same();
        }

        void add(Hit in, Hit out, float before, double spent) {
            run(() -> old.pierced(in, out, before, spent, a), () -> now.piercedOf(in, out, before, spent, b));
        }

        void enter(Point at) {
            run(() -> old.enter(BULLET, at, a), () -> now.enterOf(BULLET, at, b));
        }

        void finish() {
            run(() -> old.finish(BULLET, a), () -> now.finishOf(BULLET, b));
        }

        void run(Runnable before, Runnable after) {
            Throwable x = thrown(before), y = thrown(after);
            assertEquals(x == null ? null : x.getClass(), y == null ? null : y.getClass());
            if (x == a.failure) assertSame(b.failure, y);
            same();
        }

        void same() {
            assertEquals(a.calls, b.calls, "ordered calls with raw float and double bits");
            assertEquals(Float.floatToRawIntBits(a.damage), Float.floatToRawIntBits(b.damage));
            assertEquals(a.removed, b.removed);
            assertSame(a.stopped, b.stopped);
            assertSame(old.from, now.from);
            assertEquals(Double.doubleToRawLongBits(old.penetration.spent()), Double.doubleToRawLongBits(now.penetration.spent()));
            PierceLedger<?> ledger = (PierceLedger<?>) field(now, "steps");
            for (String f : List.of("size", "paid")) assertEquals(field(old.steps, f), field(ledger, f), f);
            assertArrayEquals((Object[]) field(old.steps, "in"), (Object[]) field(ledger, "in"));
            assertArrayEquals((Object[]) field(old.steps, "out"), (Object[]) field(ledger, "out"));
            double[] at = (double[]) field(old.steps, "at"), bt = (double[]) field(ledger, "at");
            double[] as = (double[]) field(old.steps, "spent"), bs = (double[]) field(ledger, "spent");
            float[] af = (float[]) field(old.steps, "before"), bf = (float[]) field(ledger, "before");
            assertEquals(at.length, bt.length);
            for (int i = 0; i < at.length; i++) {
                assertEquals(Double.doubleToRawLongBits(at[i]), Double.doubleToRawLongBits(bt[i]));
                assertEquals(Double.doubleToRawLongBits(as[i]), Double.doubleToRawLongBits(bs[i]));
                assertEquals(Float.floatToRawIntBits(af[i]), Float.floatToRawIntBits(bf[i]));
            }
        }
    }

    private static Throwable thrown(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException ex) {
            return ex;
        }
    }

    private static Object field(Object owner, String name) {
        try {
            Field f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Hit hit(int id, float water, float cost) {
        return new Hit(id, new Point(id * 2.0, 0, 0), water, cost);
    }

    private static Pair prepared(float water, float cost, int count) {
        Pair p = new Pair();
        p.old.penetration.restore(8.25);
        p.now.penetration.restore(8.25);
        for (int i = 1; i <= count; i++) p.add(hit(i, water, cost), hit(-i, water, cost), 80.5F - i, i * 0.125);
        return p;
    }

    @Test
    void everyWaterBranchDamageBitPatternAndDistanceBoundary() {
        boolean saved = TestLog.server;
        try {
            float[] waters = {1F, Math.nextDown(1F), Math.nextUp(1F), 0.3F, 2F, 0F, -0F, -1F,
                    Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.intBitsToFloat(0x7fc00123)};
            float[] costs = {0F, 0.777F, 1000F, -1F, Float.POSITIVE_INFINITY, Float.intBitsToFloat(0x7fc00456)};
            for (boolean logging : new boolean[] {false, true}) {
                TestLog.server = logging;
                for (float water : waters) for (float cost : costs) for (int count : new int[] {0, 1, 4, 5, 12}) {
                    Pair p = prepared(water, cost, count);
                    p.enter(ZERO);
                    p.a.damage = p.b.damage = 23.987F;
                    for (double d : new double[] {0, 2, Math.nextUp(2.0), Math.nextDown(4.0), 4, 9, 100, 1, Double.NaN, Double.POSITIVE_INFINITY}) {
                        p.enter(new Point(d, 0, 0));
                    }
                    p.finish();
                    p.finish();
                    p.enter(ZERO);
                    p.start(new Point(2, -3, 4));
                    p.add(hit(3, water, cost), hit(-3, water, cost), -0F, -0D);
                    p.finish();
                }
            }
        } finally {
            TestLog.server = saved;
        }
    }

    @Test
    void failuresAtEveryCallKeepTheSamePaidPrefixAndCanResume() {
        boolean saved = TestLog.server;
        try {
            TestLog.server = true;
            for (float water : new float[] {1F, 0.31F, 0F}) for (float cost : new float[] {0.7F, -1F}) {
                Pair baseline = prepared(water, cost, 3);
                baseline.enter(ZERO);
                int first = baseline.a.calls.size();
                baseline.finish();
                int last = baseline.a.calls.size();
                for (int failure = first + 1; failure <= last; failure++) {
                    Pair p = prepared(water, cost, 3);
                    p.enter(ZERO);
                    p.a.failAt = p.b.failAt = failure;
                    p.finish();
                    assertTrue(p.a.calls.size() >= failure);
                    p.a.failAt = p.b.failAt = -1;
                    p.enter(new Point(100, 0, 0));
                    p.finish();
                    p.start(ZERO);
                }
            }
            // Failure while recording or rewinding, including after unpayTo but before the diagnostic returns.
            for (int failure = 1; failure <= 8; failure++) {
                Pair p = new Pair();
                p.a.failAt = p.b.failAt = failure;
                p.add(hit(1, 1F, 0F), hit(-1, 1F, 0F), 42F, 0.75);
                p.add(hit(2, 1F, 0F), hit(-2, 1F, 0F), 40F, 1.25);
                p.enter(ZERO);
                p.a.failAt = p.b.failAt = -1;
                p.finish();
            }
        } finally {
            TestLog.server = saved;
        }
    }

    @Test
    void repeatedTicksRemovedBulletsAndRawDamageValues() {
        boolean saved = TestLog.server;
        try {
            TestLog.server = false;
            Pair p = new Pair();
            Random random = new Random(0xa5096a9);
            for (int tick = 0; tick < 150; tick++) {
                p.start(ZERO);
                p.a.removed = p.b.removed = tick % 7 == 0;
                float initial = Float.intBitsToFloat(random.nextInt());
                p.a.damage = p.b.damage = initial;
                for (int i = 1; i <= tick % 14; i++) p.add(hit(i, 0.37F, 0.89F), hit(-i, 1F, 0F), initial, tick + i * 0.31);
                p.enter(new Point(tick % 5, 0, 0));
                p.enter(new Point(100, 0, 0));
                p.finish();
            }
        } finally {
            TestLog.server = saved;
        }
    }

    @Test
    void emptyEnterReadsNothingAndEmptyFinishStillReadsLevel() {
        Pair p = new Pair();
        p.enter(null);
        assertTrue(p.a.calls.isEmpty());
        p.finish();
        assertEquals(List.of(List.of("level")), p.a.calls);
        p.start(null);
        p.enter(null);
        p.finish();
    }
}
