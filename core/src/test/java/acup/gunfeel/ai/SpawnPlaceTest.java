package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import acup.gunfeel.GameRandom;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.BodyControl;
import acup.gunfeel.host.WorldView;

// where a mob just made is put: the game's AiSpawn.place in core, its draws, writes and reads in the game's order
class SpawnPlaceTest {
    /** the calls made, in order: "place x y z", "yRot v", ..., "free x0..", "boxes x y z" */
    private final List<String> calls = new ArrayList<>();
    private final double[] at = new double[3];

    private Body body() {
        return (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> switch (m.getName()) {
            case "x" -> at[0];
            case "y" -> at[1];
            case "z" -> at[2];
            case "box" -> {
                double[] out = (double[]) a[0];
                out[0] = at[0] - 0.3;
                out[1] = at[1];
                out[2] = at[2] - 0.3;
                out[3] = at[0] + 0.3;
                out[4] = at[1] + 1.8;
                out[5] = at[2] + 0.3;
                yield null;
            }
            case "hashCode" -> 1;
            case "equals" -> p == a[0];
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private BodyControl control() {
        return (BodyControl) Proxy.newProxyInstance(BodyControl.class.getClassLoader(), new Class<?>[] {BodyControl.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "place" -> {
                    at[0] = (double) a[1];
                    at[1] = (double) a[2];
                    at[2] = (double) a[3];
                    calls.add("place " + a[1] + " " + a[2] + " " + a[3]);
                }
                case "yRot", "yHeadRot", "yBodyRot" -> calls.add(m.getName() + " " + a[1]);
                default -> throw new UnsupportedOperationException(m.getName());
            }
            return null;
        });
    }

    /** a floor (a block with a shape) where floor says; free where free says */
    private WorldView world(java.util.function.IntPredicate floorAtY, java.util.function.DoublePredicate freeAtY) {
        return (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[] {WorldView.class}, (p, m, a) -> switch (m.getName()) {
            case "boxes" -> {
                assertEquals(WorldView.COLLIDER, a[3]);
                assertEquals(null, a[4]);
                calls.add("boxes " + a[0] + " " + a[1] + " " + a[2]);
                // the game's: no room in out, the count negative
                yield floorAtY.test((int) a[1]) ? -1 : 0;
            }
            case "free" -> {
                double[] b = {(double) a[1], (double) a[2], (double) a[3], (double) a[4], (double) a[5], (double) a[6]};
                calls.add("free " + b[1]);
                yield freeAtY.test(b[1]);
            }
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    /** a recorded sequence of draws, each its kind */
    private static final class Draws implements GameRandom {
        final java.util.Random r = new java.util.Random(7);
        final List<String> drawn = new ArrayList<>();

        @Override public int nextInt(int bound) { throw new AssertionError("not asked"); }
        @Override public double nextDouble() { drawn.add("d"); return r.nextDouble(); }
        @Override public boolean nextBoolean() { throw new UnsupportedOperationException(); }
        @Override public float nextFloat() { drawn.add("f"); return r.nextFloat(); }
        @Override public double nextGaussian() { throw new UnsupportedOperationException(); }
    }

    @Test
    void theFirstFloorFromAboveItsTurnAndItsRoom() {
        Draws rng = new Draws();
        // a floor at y 63 (feet at 64) under every column; free there
        assertTrue(SpawnPlace.place(control(), world(y -> y == 63, y -> y == 64), body(), 10.3, 64.7, -5.2, rng, 4));
        // the angle and the radius, then the turn
        assertEquals(List.of("d", "d", "f"), rng.drawn);
        java.util.Random r = new java.util.Random(7);
        double a = r.nextDouble() * Math.PI * 2, rad = 1.5 + r.nextDouble() * (2 + Math.sqrt(4) * 1.5);
        float yaw = r.nextFloat() * 360F;
        double x = Math.floor(10.3 + Math.cos(a) * rad) + 0.5, z = Math.floor(-5.2 + Math.sin(a) * rad) + 0.5;
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        // from three over the point down: the blocks under 67, 66, 65 have no shape, under 64 the floor
        assertEquals(List.of("boxes " + bx + " 66 " + bz, "boxes " + bx + " 65 " + bz, "boxes " + bx + " 64 " + bz, "boxes " + bx + " 63 " + bz,
                "place " + x + " 64.0 " + z, "yRot " + yaw, "yHeadRot " + yaw, "yBodyRot " + yaw, "free 64.0"), calls);
    }

    @Test
    void walledInEveryWayNoPlace() {
        Draws rng = new Draws();
        // a floor under every height, never free: every attempt tries all eight heights
        assertFalse(SpawnPlace.place(control(), world(y -> true, y -> false), body(), 0.5, 64, 0.5, rng, 1));
        long places = calls.stream().filter(c -> c.startsWith("place")).count();
        assertEquals(17 * 8, places);
        // the last attempt at the point itself: no radius drawn
        long doubles = rng.drawn.stream().filter("d"::equals).count(), floats = rng.drawn.stream().filter("f"::equals).count();
        assertEquals(16 * 2 + 1, doubles);
        assertEquals(17 * 8, floats);
        assertTrue(calls.get(calls.size() - 5).startsWith("place 0.5 "), calls.get(calls.size() - 5));
    }
}
