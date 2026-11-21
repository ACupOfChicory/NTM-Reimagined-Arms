package acup.gunfeel.ammo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.host.DuplexShots;

/** The pre-port pair machine against core, with raw bits and ordered reads and writes. */
class DuplexBaseTest {
    private static final ThreadLocal<Object> FRONT = front();

    @SuppressWarnings("unchecked")
    private static ThreadLocal<Object> front() {
        try {
            Field f = DuplexBase.class.getDeclaredField("FRONT");
            f.setAccessible(true);
            return (ThreadLocal<Object>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @BeforeEach void start() { DuplexBase.shotStart(); }
    @AfterEach void end() { DuplexBase.shotStart(); }

    // Equal values with distinct identities: both config and thrower must use identity.
    private record Token(int value) {}
    private record V(double x, double y, double z) {
        double length() { return Math.sqrt(x * x + y * y + z * z); }
        V scale(double s) { return new V(x * s, y * s, z * s); }
        V subtract(V v) { return add(-v.x, -v.y, -v.z); }
        V add(double a, double b, double c) { return new V(x + a, y + b, z + c); }
    }

    private static final class Bullet {
        final String name;
        final Object config, thrower;
        final V motion, position;
        final double[] samples;
        int gaussian;
        boolean duplex = true;

        Bullet(String name, Object config, Object thrower, V motion, V position, double... samples) {
            this.name = name;
            this.config = config;
            this.thrower = thrower;
            this.motion = motion;
            this.position = position;
            this.samples = samples;
        }
    }

    private static final class Fake implements DuplexShots {
        final List<String> reads = new ArrayList<>(), writes = new ArrayList<>();
        final List<Bullet> bullets = new ArrayList<>();
        Bullet copy(Bullet b) {
            Bullet c = new Bullet(b.name, b.config, b.thrower, b.motion, b.position, b.samples);
            c.duplex = b.duplex;
            bullets.add(c);
            return c;
        }
        String name(Object b) { return b instanceof Bullet p ? p.name : "other"; }
        void read(String op, Object b, Object value) { reads.add(name(b) + "." + op + "=" + value); }
        double number(String op, Object b, double value) { read(op, b, bits(value)); return value; }
        void set(String op, Object b, double... values) {
            String s = name(b) + "." + op;
            for (double v : values) s += ":" + bits(v);
            writes.add(s);
        }
        void rotation(String op, Object b, float value) { writes.add(name(b) + "." + op + ":" + Float.floatToRawIntBits(value)); }
        @Override public boolean duplex(Object b) {
            boolean result = b instanceof Bullet p && p.duplex;
            read("duplex", b, result);
            return result;
        }
        @Override public Object config(Object b) { Object c = ((Bullet) b).config; read("config", b, c); return c; }
        @Override public Object thrower(Object b) { Object t = ((Bullet) b).thrower; read("thrower", b, t); return t; }
        @Override public double mx(Object b) { return number("mx", b, ((Bullet) b).motion.x); }
        @Override public double my(Object b) { return number("my", b, ((Bullet) b).motion.y); }
        @Override public double mz(Object b) { return number("mz", b, ((Bullet) b).motion.z); }
        @Override public double px(Object b) { return number("px", b, ((Bullet) b).position.x); }
        @Override public double py(Object b) { return number("py", b, ((Bullet) b).position.y); }
        @Override public double pz(Object b) { return number("pz", b, ((Bullet) b).position.z); }
        @Override public double gaussian(Object b) {
            Bullet p = (Bullet) b;
            double v = p.samples[p.gaussian++ % p.samples.length];
            return number("gaussian", b, v);
        }
        @Override public void write(Object b, double x, double y, double z, float yaw, float pitch, double ax, double ay, double az) {
            set("setDeltaMovement", b, x, y, z);
            rotation("setYRot", b, yaw);
            rotation("yRotO", b, yaw);
            rotation("setXRot", b, pitch);
            rotation("xRotO", b, pitch);
            set("setPos", b, ax, ay, az);
            set("xo", b, ax); set("yo", b, ay); set("zo", b, az);
            set("xOld", b, ax); set("yOld", b, ay); set("zOld", b, az);
        }
    }

    private static long bits(double x) { return Double.doubleToRawLongBits(x); }

    // Frozen pre-port place/shotStart, with the game's Vec3 operations above.
    private static final class Old {
        Bullet front;
        final Fake f;
        Old(Fake f) { this.f = f; }
        void shotStart() { front = null; }
        void place(Object e) {
            if (!f.duplex(e)) return;
            Bullet b = (Bullet) e;
            if (front == null || f.config(front) != f.config(b) || f.thrower(front) != f.thrower(b)) {
                front = b;
                return;
            }
            Bullet previous = front;
            front = null;
            V v = new V(f.mx(previous), f.my(previous), f.mz(previous));
            double[] r = oldRear(v.x, v.y, v.z, () -> f.gaussian(b));
            f.set("setDeltaMovement", b, r[0], r[1], r[2]);
            double hyp = Math.sqrt(r[0] * r[0] + r[2] * r[2]);
            float yaw = (float) Math.toDegrees(Math.atan2(r[0], r[2])), pitch = (float) Math.toDegrees(Math.atan2(r[1], hyp));
            f.rotation("setYRot", b, yaw);
            f.rotation("yRotO", b, yaw);
            f.rotation("setXRot", b, pitch);
            f.rotation("xRotO", b, pitch);
            double len = v.length();
            V position = new V(f.px(previous), f.py(previous), f.pz(previous));
            V at = len < 1e-9 ? position : position.subtract(v.scale(0.25 / len));
            f.set("setPos", b, at.x, at.y, at.z);
            f.set("xo", b, at.x); f.set("yo", b, at.y); f.set("zo", b, at.z);
            f.set("xOld", b, at.x); f.set("yOld", b, at.y); f.set("zOld", b, at.z);
        }
    }

    // Frozen DuplexRule.rear from b15489e: the oracle must not share the rule under test.
    private static double[] oldRear(double x, double y, double z, DoubleSupplier gaussian) {
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-12) return new double[] {x, y, z};
        double fx = x / len, fy = y / len, fz = z / len;
        // two unit vectors across the heading: one level (sideways), one up from it
        double sx = fz, sy = 0, sz = -fx;
        double sl = Math.sqrt(sx * sx + sz * sz);
        if (sl < 1e-9) { sx = 1; sz = 0; sl = 1; }
        sx /= sl;
        sz /= sl;
        double ux = fy * sz - fz * sy, uy = fz * sx - fx * sz, uz = fx * sy - fy * sx;
        double a, b;
        int tries = 0;
        do {
            a = gaussian.getAsDouble() * 0.018;
            b = gaussian.getAsDouble() * 0.018;
        } while (a * a + b * b > 0.042 * 0.042 && ++tries < 16);
        if (a * a + b * b > 0.042 * 0.042) {
            double k = 0.042 / Math.sqrt(a * a + b * b);
            a *= k;
            b *= k;
        }
        // small angles: the heading plus the offsets across it, made unit again
        double rx = fx + a * sx + b * ux, ry = fy + a * sy + b * uy, rz = fz + a * sz + b * uz;
        double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
        return new double[] {rx / rl * len, ry / rl * len, rz / rl * len};
    }

    private static final class Run {
        final Fake actual = new Fake(), expected = new Fake();
        final Old old = new Old(expected);
        void place(Object e) {
            Object a = e instanceof Bullet b ? actual.copy(b) : e;
            Object w = e instanceof Bullet b ? expected.copy(b) : e;
            assertFront();
            old.place(w);
            DuplexBase.place(a, actual);
            assertFront();
            // write is one port call; compare reads and setters as separate ordered journals.
            assertEquals(expected.reads, actual.reads, "reads (including short circuit and random owner)");
            assertEquals(expected.writes, actual.writes, "setters and raw bits");
            assertEquals(expected.bullets.stream().map(b -> b.gaussian).toList(), actual.bullets.stream().map(b -> b.gaussian).toList());
        }
        void shotStart() {
            old.shotStart();
            DuplexBase.shotStart();
            assertNull(FRONT.get());
        }
        void assertFront() {
            assertSame(old.front == null ? null : actual.bullets.get(expected.bullets.indexOf(old.front)), FRONT.get());
        }
    }

    private static Bullet bullet(String name, Object c, Object t, V v, double... samples) {
        return new Bullet(name, c, t, v, new V(-0.0, 123.125, -45.75), samples);
    }

    @Test void transitionsAndShortCircuit() {
        Run run = new Run();
        Object c = new Token(1), t = new Token(2);
        V v = new V(1.25, -2.5, 3.75);
        run.place(new Object());
        run.place(bullet("first", c, t, v, 0.1));
        Bullet non = bullet("notDuplex", c, t, v, 0.1); non.duplex = false;
        run.place(non);
        assertEquals(List.of("other.duplex=false", "first.duplex=true", "notDuplex.duplex=false"), run.actual.reads);
        run.place(bullet("config", new Token(1), t, v, 0.1));
        assertFalse(run.actual.reads.stream().anyMatch(s -> s.contains("thrower")));
        Object other = ((Bullet) FRONT.get()).config;
        run.place(bullet("thrower", other, new Token(2), v, 0.1));
        Object shooter = ((Bullet) FRONT.get()).thrower;
        run.place(bullet("rear", other, shooter, v, 0.25, -0.5));
        assertNull(FRONT.get());
        run.place(bullet("third", c, t, v, 0.1));
        assertEquals("third", ((Bullet) FRONT.get()).name);
        run.shotStart();
        run.place(bullet("afterReset", c, t, v, 0.1));
        run.shotStart();
    }

    @Test void motionAndPositionBits() {
        for (V v : List.of(new V(0, 0, 0), new V(-0.0, 0.0, -0.0), new V(0, 4, 0), new V(0, -4, 0),
                new V(1.25, -2.5, 3.75), new V(0.5e-9, 0, 0), new V(1e-9, 0, 0),
                new V(Math.nextDown(1e-9), 0, 0), new V(Math.nextUp(1e-9), 0, 0),
                new V(1e-13, 0, 0), new V(1e-12, 0, 0), new V(1e100, -1e100, 2e100),
                new V(Double.NaN, 1, 2), new V(Double.POSITIVE_INFINITY, 1, 2))) {
            for (double[] samples : List.of(new double[] {0.25, -0.5}, new double[] {100, -100}, new double[] {-0.0, 0.0})) {
                Run r = new Run();
                Object c = new Token(1), t = new Token(2);
                r.place(bullet("front", c, t, v, 0.75));
                r.place(bullet("rear", c, t, new V(99, 88, 77), samples));
                assertEquals(0, r.actual.bullets.get(0).gaussian);
                r.shotStart();
            }
        }
    }

    @Test void gaussianCounts() {
        for (int attempts = 1; attempts <= 16; attempts++) {
            double[] samples = new double[attempts * 2];
            java.util.Arrays.fill(samples, 100);
            samples[samples.length - 2] = 0.25;
            samples[samples.length - 1] = -0.5;
            assertCount(new V(1, 2, 3), samples, attempts * 2);
        }
        assertCount(new V(1, 2, 3), new double[] {100}, 32);
        assertCount(new V(0, 0, 0), new double[] {100}, 0);
        assertCount(new V(1e-13, 0, 0), new double[] {100}, 0);
    }

    private void assertCount(V v, double[] samples, int count) {
        Run r = new Run();
        Object c = new Token(1), t = new Token(2);
        r.place(bullet("front", c, t, v, -0.75));
        r.place(bullet("rear", c, t, v, samples));
        assertEquals(count, r.actual.bullets.get(1).gaussian);
        assertEquals(0, r.actual.bullets.get(0).gaussian);
        r.shotStart();
    }

    @Test void frontIsLocalToTheThread() throws InterruptedException {
        Object marker = new Object();
        FRONT.set(marker);
        Thread t = new Thread(() -> {
            assertNull(FRONT.get());
            DuplexBase.shotStart();
        });
        t.setUncaughtExceptionHandler((thread, error) -> failures.add(error));
        t.start(); t.join();
        assertEquals(List.of(), failures);
        assertSame(marker, FRONT.get());
        DuplexBase.shotStart();
        assertNull(FRONT.get());
    }
    private final List<Throwable> failures = new ArrayList<>();

    @Test void missingAlwaysThrows() {
        for (var m : DuplexShots.class.getDeclaredMethods()) {
            Object[] args = new Object[m.getParameterCount()];
            for (int i = 0; i < args.length; i++) {
                Class<?> p = m.getParameterTypes()[i];
                args[i] = p == double.class ? (Object) 0.0 : p == float.class ? (Object) 0.0F : new Object();
            }
            InvocationTargetException e = assertThrows(InvocationTargetException.class, () -> m.invoke(DuplexShots.MISSING, args));
            assertInstanceOf(UnsupportedOperationException.class, e.getCause());
        }
    }
}
