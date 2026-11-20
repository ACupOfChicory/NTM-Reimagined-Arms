package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import acup.gunfeel.ModelBox;
import acup.gunfeel.Vec3d;
import acup.gunfeel.host.Body;

class AiLeanBaseTest {
    private static final byte[] STANCES = {Stance.STAND, Stance.CROUCH, Stance.PRONE, -1};
    private static final float[] LEANS = {-1F, -0.73F, -0.01F, -0F, 0F, 0.01F, 0.37F, 1F};
    private static final float[] YAWS = {-180F, -135F, -60F, -0F, 0F, 37F, 90F, 135F, 360F};
    private static final double HIP = 1.501 - 12.0 / 16.0;
    private static final double[] HEIGHTS = {-0.2, 0, 0.4, Math.nextDown(HIP), HIP, Math.nextUp(HIP), 1.27, 1.74, 2.13};

    private record Pose(byte stance, byte lean, byte prevLean, int leanTick) implements LeanPose {
    }

    private static final class Live implements AiLeanBase.Staff {
        final Object level = new Object();
        final List<String> calls = new ArrayList<>();
        boolean living = true, enabled = true;
        long now = 14, caught = Long.MIN_VALUE;
        float yaw = 37F, height = 1.74F;
        double x = -12.37, y = 65.13, z = 0.29;
        LeanPose pose = new Pose(Stance.STAND, (byte) 100, (byte) 0, 10);
        LeanPose nextPose;
        int poseReads;
        List<ModelBox> written;
        float writtenYaw;
        final Body body = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> {
            calls.add(m.getName());
            return switch (m.getName()) {
                case "living" -> living;
                case "x" -> x;
                case "y" -> y;
                case "z" -> z;
                case "yBodyRot" -> yaw;
                case "eyeHeight" -> height;
                default -> throw new AssertionError(m.getName());
            };
        });

        @Override public LeanPose pose(Body b) {
            assertSame(body, b);
            calls.add("pose");
            poseReads++;
            LeanPose answer = pose;
            if (nextPose != null) { pose = nextPose; nextPose = null; }
            return answer;
        }
        @Override public long gameTime(Body b) { assertSame(body, b); calls.add("time"); return now; }
        @Override public boolean hitsEnabled() { calls.add("enabled"); return enabled; }
        @Override public long caught(Object l, Body b) { assertSame(level, l); assertSame(body, b); calls.add("caught"); return caught; }
        @Override public void forget(Object l, Body b, long put) {
            assertSame(level, l); assertSame(body, b); assertEquals(caught, put);
            calls.add("forget"); caught = Long.MIN_VALUE;
        }
        @Override public void put(Object l, Body b, float yaw, List<ModelBox> boxes) {
            assertSame(level, l); assertSame(body, b);
            calls.add("put"); written = boxes; writtenYaw = yaw;
        }
    }

    @Test
    void mayShortCircuitsTheSwitch() {
        Live s = new Live();
        boolean before = AiLeanBase.on;
        try {
            AiLeanBase.on = false;
            assertFalse(AiLeanBase.may(s));
            assertTrue(s.calls.isEmpty());
            AiLeanBase.on = true;
            assertTrue(AiLeanBase.may(s));
            s.enabled = false;
            assertFalse(AiLeanBase.may(s));
            assertEquals(List.of("enabled", "enabled"), s.calls);
        } finally {
            AiLeanBase.on = before;
        }
    }

    @Test
    void fracTransitionsAndShortCircuitsMatchFormerBits() {
        Live s = new Live();
        s.living = false;
        assertEquals(0F, AiLeanBase.frac(s, s.body));
        assertEquals(List.of("living"), s.calls);
        s.living = true;
        s.pose = new Pose(Stance.STAND, (byte) 0, (byte) 0, 10);
        s.calls.clear();
        assertEquals(0F, AiLeanBase.frac(s, s.body));
        assertEquals(List.of("living", "pose"), s.calls);
        for (int prev : new int[] {-100, 0, 37, 100}) for (int lean : new int[] {-100, 0, 63, 100})
            for (long now : new long[] {9, 10, 11, 12, 13, 14, 15}) {
                s.pose = new Pose(Stance.STAND, (byte) lean, (byte) prev, 10);
                s.now = now;
                s.calls.clear();
                assertEquals(Float.floatToRawIntBits(Former.leanFrac((byte) prev, (byte) lean, 10, now, 0F)),
                        Float.floatToRawIntBits(AiLeanBase.frac(s, s.body)));
                assertEquals(prev == 0 && lean == 0 ? List.of("living", "pose") : List.of("living", "pose", "time"), s.calls);
            }
    }

    @Test
    void eyesAndPointsKeepFormerBitsAndReadCounts() {
        Live s = new Live();
        Probe maker = new Probe();
        for (byte stance : STANCES) for (float lean : LEANS) for (float yaw : YAWS) for (double up : HEIGHTS) {
            s.pose = new Pose(stance, (byte) (lean * 100), (byte) 0, 10);
            s.yaw = yaw;
            float f = Former.leanFrac((byte) 0, s.pose.lean(), 10, s.now, 0F);
            int reads = s.poseReads, made = maker.calls;
            var eye = AiLeanBase.eye(s, s.body, maker);
            assertEquals(reads + (f == 0F ? 1 : 2), s.poseReads);
            if (f == 0F) { assertNull(eye); assertEquals(made, maker.calls); }
            else {
                Vec3d offset = Former.eyeOffset(stance, f, yaw, s.height);
                maker.check(new Vec3d(s.x + offset.x(), s.y + offset.y(), s.z + offset.z()), made + 1);
            }
            reads = s.poseReads; made = maker.calls;
            var shift = AiLeanBase.eyeShift(s, s.body, maker);
            assertEquals(reads + (f == 0F ? 1 : 2), s.poseReads);
            if (f == 0F) { assertNull(shift); assertEquals(made, maker.calls); }
            else {
                Vec3d offset = Former.eyeOffset(stance, f, yaw, s.height);
                maker.check(new Vec3d(offset.x() - 0, offset.y() - s.height, offset.z() - 0), made + 1);
            }
            reads = s.poseReads; made = maker.calls;
            AiLeanBase.point(s, s.body, up, maker);
            assertEquals(reads + (f == 0F ? 1 : 2), s.poseReads);
            Vec3d offset = Former.leanPoint(0, up, 0, stance, f, yaw);
            Vec3d expected = f == 0F ? new Vec3d(s.x, s.y + up, s.z)
                    : new Vec3d(s.x + offset.x(), s.y + offset.y(), s.z + offset.z());
            maker.check(expected, made + 1);
        }
        s.living = false;
        s.calls.clear();
        assertNull(AiLeanBase.eye(s, s.body, maker));
        assertEquals(List.of("living"), s.calls);
    }

    @Test
    void poseReplacementIsReadOnEveryCall() {
        Live s = new Live();
        assertEquals(1F, AiLeanBase.frac(s, s.body));
        s.pose = new Pose(Stance.CROUCH, (byte) -100, (byte) 0, 10);
        assertEquals(-1F, AiLeanBase.frac(s, s.body));
        Probe maker = new Probe();
        AiLeanBase.eye(s, s.body, maker);
        Vec3d offset = Former.eyeOffset(Stance.CROUCH, -1F, s.yaw, s.height);
        maker.check(new Vec3d(s.x + offset.x(), s.y + offset.y(), s.z + offset.z()), 1);
        s.pose = new Pose(Stance.STAND, (byte) 100, (byte) 0, 14);
        assertNull(AiLeanBase.eye(s, s.body, maker));
        s.now++;
        assertEquals(0.25F, AiLeanBase.frac(s, s.body));
        AiLeanBase.eye(s, s.body, maker);
        offset = Former.eyeOffset(Stance.STAND, 0.25F, s.yaw, s.height);
        maker.check(new Vec3d(s.x + offset.x(), s.y + offset.y(), s.z + offset.z()), 2);
    }

    @Test
    void secondPoseReadIsLiveWithinOneCall() {
        Live s = new Live();
        s.nextPose = new Pose(Stance.CROUCH, (byte) -100, (byte) 0, 10);
        Probe maker = new Probe();
        AiLeanBase.eye(s, s.body, maker);
        assertEquals(2, s.poseReads);
        assertEquals(Stance.CROUCH, s.pose.stance());
        assertTrue(s.calls.indexOf("time") < s.calls.lastIndexOf("pose"));
        s.calls.clear();
        s.nextPose = new Pose(Stance.PRONE, (byte) 0, (byte) 0, 10);
        AiLeanBase.boxes(s, s.level, s.body, false, Long.MIN_VALUE, 14);
        assertNull(s.written);
        assertTrue(s.calls.indexOf("caught") < s.calls.lastIndexOf("pose"));
    }

    @Test
    void serverBoxesMatrixKeepsOwnershipFreshnessAndOrder() {
        for (boolean enabled : new boolean[] {false, true}) for (byte stance : STANCES)
            for (int lean : new int[] {0, -100, 100}) for (boolean aim : new boolean[] {false, true})
                for (long put : new long[] {Long.MIN_VALUE, 14})
                    for (long caught : new long[] {Long.MIN_VALUE, 7, 8, 9, 10, 14, 20}) {
                        Live s = new Live();
                        s.enabled = enabled; s.caught = caught;
                        s.pose = new Pose(stance, (byte) lean, (byte) 0, 10);
                        long answer = AiLeanBase.boxes(s, s.level, s.body, aim, put, 14);
                        if (put == Long.MIN_VALUE && lean == 0) {
                            assertEquals(Long.MIN_VALUE, answer);
                            assertEquals(List.of("living", "pose"), s.calls);
                            continue;
                        }
                        boolean mine = put != Long.MIN_VALUE && caught == put;
                        List<String> expected = new ArrayList<>(List.of("living", "pose"));
                        if (lean != 0) expected.add("time");
                        expected.add("caught");
                        if (lean != 0) expected.add("enabled");
                        if (lean != 0 && enabled) expected.add("pose");
                        boolean active = lean != 0 && enabled && stance == Stance.STAND;
                        if (!active) {
                            if (mine) expected.add("forget");
                            assertEquals(Long.MIN_VALUE, answer);
                        } else if (!mine && caught != Long.MIN_VALUE && 14 - caught <= 5) {
                            assertEquals(Long.MIN_VALUE, answer);
                        } else {
                            expected.addAll(List.of("yBodyRot", "yBodyRot", "put"));
                            assertEquals(14, answer);
                            assertEquals(Former.boxes(Stance.STAND, aim, lean / 100F, s.yaw), s.written);
                            assertEquals(s.yaw, s.writtenYaw);
                        }
                        assertEquals(expected, s.calls, "put " + put + " caught " + caught + " stance " + stance);
                        if (!expected.contains("put")) assertNull(s.written);
                        assertEquals(expected.contains("forget") ? Long.MIN_VALUE : caught, s.caught);
                    }
    }

    @Test
    void dropForgetsOnlyItsOwnAndAlwaysReturnsNone() {
        for (long put : new long[] {Long.MIN_VALUE, -1, 0, 14})
            for (long caught : new long[] {Long.MIN_VALUE, -1, 0, 14, 15}) {
                Live s = new Live(); s.caught = caught;
                assertEquals(Long.MIN_VALUE, AiLeanBase.drop(s, s.level, s.body, put));
                boolean mine = put != Long.MIN_VALUE && caught == put;
                assertEquals(put == Long.MIN_VALUE ? List.of() : mine ? List.of("caught", "forget") : List.of("caught"), s.calls);
                assertEquals(mine ? Long.MIN_VALUE : caught, s.caught);
            }
    }

    @Test
    void partTablesKeepEveryField() throws ReflectiveOperationException {
        for (String name : new String[] {"HOLDING", "AIMING"}) {
            Field current = AiLeanBase.class.getDeclaredField(name), former = Former.class.getDeclaredField(name);
            current.setAccessible(true);
            former.setAccessible(true);
            assertEquals(former.get(null).toString(), current.get(null).toString(), name);
        }
        assertEquals(0.25, AiLeanBase.STEP);
        assertEquals(100, AiLeanBase.FULL);
    }

    @Test
    void boxesMatchFormerBitsAcrossPoses() {
        for (byte stance : STANCES) for (boolean aim : new boolean[] {false, true})
            for (float lean : LEANS) for (float yaw : YAWS) {
                List<ModelBox> expected = Former.boxes(stance, aim, lean, yaw);
                List<ModelBox> actual = AiLeanBase.boxes(stance, aim, lean, yaw);
                assertEquals(15, actual.size());
                assertEquals(expected, actual, "stance " + stance + " aim " + aim + " lean " + lean + " yaw " + yaw);
            }
    }

    @Test
    void edgesMatchFormerBitsAtAndAroundHips() {
        double[][] edges = {{0.25, -0.75, 0.13}, {-0.249, 0.123, -0.5}, {0, 0, 0}, {0.7, 0, 0}};
        for (byte stance : STANCES) for (float lean : LEANS) for (float yaw : YAWS)
            for (double up : HEIGHTS) for (double[] edge : edges) {
                assertEquals(Former.edge(0.37, up, -0.29, edge[0], edge[1], edge[2], stance, lean, yaw),
                        AiLeanBase.edge(0.37, up, -0.29, edge[0], edge[1], edge[2], stance, lean, yaw));
            }
    }

    @Test
    void leanPointsMatchFormerBitsAtAndAroundHips() {
        for (byte stance : STANCES) for (float lean : LEANS) for (float yaw : YAWS)
            for (double up : HEIGHTS) for (double right : new double[] {-0.37, -0.0, 0, 0.29}) {
                assertEquals(Former.leanPoint(right, up, 0.21, stance, lean, yaw),
                        AiLeanBase.leanPoint(right, up, 0.21, stance, lean, yaw));
            }
    }

    @Test
    void eyeOffsetsMatchFormerBitsAcrossHeights() {
        for (byte stance : STANCES) for (float lean : LEANS) for (float yaw : YAWS) for (double height : HEIGHTS) {
            assertEquals(Former.eyeOffset(stance, lean, yaw, height), AiLeanBase.eyeOffset(stance, lean, yaw, height));
        }
    }

    @Test
    void factoriesReceiveTheFormerBitsOnce() {
        Probe maker = new Probe();
        for (byte stance : STANCES) for (float lean : LEANS) for (float yaw : YAWS) for (double height : HEIGHTS) {
            int calls = maker.calls;
            assertEquals(maker, AiLeanBase.leanPoint(0.29, height, -0.13, stance, lean, yaw, maker));
            maker.check(Former.leanPoint(0.29, height, -0.13, stance, lean, yaw), calls + 1);
            assertEquals(maker, AiLeanBase.eyeOffset(stance, lean, yaw, height, maker));
            maker.check(Former.eyeOffset(stance, lean, yaw, height), calls + 2);
            assertEquals(maker, AiLeanBase.toWorld(0.29, height, -0.13, yaw, maker));
            maker.check(Former.toWorld(0.29, height, -0.13, yaw), calls + 3);
            assertEquals(Former.toWorld(0.29, height, -0.13, yaw), AiLeanBase.toWorld(0.29, height, -0.13, yaw));
        }
    }

    private static final class Probe implements AiLeanBase.Vec3Maker<Probe> {
        double x, y, z;
        int calls;

        @Override
        public Probe make(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            calls++;
            return this;
        }

        void check(Vec3d expected, int count) {
            assertEquals(count, calls);
            assertEquals(Double.doubleToRawLongBits(expected.x()), Double.doubleToRawLongBits(x));
            assertEquals(Double.doubleToRawLongBits(expected.y()), Double.doubleToRawLongBits(y));
            assertEquals(Double.doubleToRawLongBits(expected.z()), Double.doubleToRawLongBits(z));
        }
    }

    @Test
    void leanFractionsMatchFormerFloatBits() {
        for (int prev : new int[] {-100, -37, 0, 29, 100}) for (int lean : new int[] {-100, -1, 0, 63, 100})
            for (int tick : new int[] {Integer.MIN_VALUE, -10, 0, 17, Integer.MAX_VALUE})
                for (long elapsed : new long[] {-100, -1, 0, 1, 2, 3, 4, 5, 100, 1L << 40})
                    for (float partial : new float[] {-0.5F, -0F, 0F, 0.37F, 0.99F, 1F}) {
                        float expected = Former.leanFrac((byte) prev, (byte) lean, tick, (long) tick + elapsed, partial);
                        float actual = AiLeanBase.leanFrac((byte) prev, (byte) lean, tick, (long) tick + elapsed, partial);
                        assertEquals(Float.floatToIntBits(expected), Float.floatToIntBits(actual));
                    }
    }

    // Frozen HEAD geometry, with vectors and primitive pose fields substituted.
    private static final class Former {
        static final float LEAN_TICKS = 4F, LEAN_DEG = 25F;
        static final double LEAN_SHIFT = 0.25, MODEL_FEET = 1.501, HIP_PX = 12.0, CROUCH_HIP_PX = 12.0;

        static float clamp(float value, float min, float max) {
            return value < min ? min : Math.min(value, max);
        }

        private record Part(String name, double cx, double cy, double cz, double ux, double uy, double uz, double vx, double vy, double vz, double wx, double wy,
                double wz) {
        }
    
        private static final List<Part> HOLDING = List.of(
                new Part("root/head", 0, 28.016, 0, 8, 0, 0, 0, -8, 0, 0, 0, -8),
                new Part("root/head/hat", 0, 28.016, 0, 9, 0, 0, 0, -9, 0, 0, 0, -9),
                new Part("root/body", 0, 18.016, 0, 8, 0, 0, 0, -12, 0, 0, 0, -4),
                new Part("root/right_arm", -6.095, 21.121, 0, 3.98, -0.399, 0, -0.599, -5.97, 0, 0, 0, -4),
                new Part("root/right_arm/forearm", -6.694, 15.151, 0, 3.98, -0.399, 0, -0.599, -5.97, 0, 0, 0, -4),
                new Part("root/right_arm/forearm", -6.295, 19.131, 0, 3.96, -0.397, 0, -0.198, -1.97, 0, 0, 0, -3.98),
                new Part("root/left_arm", 6.095, 21.121, 0, 3.98, 0.399, 0, 0.599, -5.97, 0, 0, 0, -4),
                new Part("root/left_arm/forearm", 6.694, 15.151, 0, 3.98, 0.399, 0, 0.599, -5.97, 0, 0, 0, -4),
                new Part("root/left_arm/forearm", 6.295, 19.131, 0, 3.96, 0.397, 0, 0.198, -1.97, 0, 0, 0, -3.98),
                new Part("root/right_leg", -1.915, 9.016, 0, 4, -0.02, 0.02, -0.03, -6, 0, 0.02, 0, -4),
                new Part("root/right_leg/shin", -1.945, 3.016, 0, 4, -0.02, 0.02, -0.03, -6, 0, 0.02, 0, -4),
                new Part("root/right_leg/shin", -1.925, 7.016, 0, 3.98, -0.02, 0.02, -0.01, -1.98, 0, 0.02, 0, -3.98),
                new Part("root/left_leg", 1.915, 9.016, 0, 4, 0.02, -0.02, 0.03, -6, 0, -0.02, 0, -4),
                new Part("root/left_leg/shin", 1.945, 3.016, 0, 4, 0.02, -0.02, 0.03, -6, 0, -0.02, 0, -4),
                new Part("root/left_leg/shin", 1.925, 7.016, 0, 3.98, 0.02, -0.02, 0.01, -1.98, 0, -0.02, 0, -3.98));
        private static final List<Part> AIMING = List.of(
                new Part("root/head", 0, 28.016, 0, 8, 0, 0, 0, -8, 0, 0, 0, -8),
                new Part("root/head/hat", 0, 28.016, 0, 9, 0, 0, 0, -9, 0, 0, 0, -9),
                new Part("root/body", 0, 18.016, 0, 8, 0, 0, 0, -12, 0, 0, 0, -4),
                new Part("root/right_arm", -5.895, 22.016, 1.095, 3.98, 0, -0.399, 0.599, 0, 5.97, 0, -4, 0),
                new Part("root/right_arm/forearm", -5.296, 22.016, 7.065, 3.98, 0, -0.399, 0.599, 0, 5.97, 0, -4, 0),
                new Part("root/right_arm/forearm", -5.696, 22.016, 3.085, 3.96, 0, -0.397, 0.198, 0, 1.97, 0, -3.98, 0),
                new Part("root/left_arm", 5.398, 22.016, 1.357, 3.51, 0, 1.918, -2.877, 0, 5.265, 0, -4, 0),
                new Part("root/left_arm/forearm", 2.522, 22.016, 6.623, 3.51, 0, 1.918, -2.877, 0, 5.265, 0, -4, 0),
                new Part("root/left_arm/forearm", 4.439, 22.016, 3.112, 3.493, 0, 1.908, -0.949, 0, 1.738, 0, -3.98, 0),
                new Part("root/right_leg", -1.915, 9.016, 0, 4, -0.02, 0.02, -0.03, -6, 0, 0.02, 0, -4),
                new Part("root/right_leg/shin", -1.945, 3.016, 0, 4, -0.02, 0.02, -0.03, -6, 0, 0.02, 0, -4),
                new Part("root/right_leg/shin", -1.925, 7.016, 0, 3.98, -0.02, 0.02, -0.01, -1.98, 0, 0.02, 0, -3.98),
                new Part("root/left_leg", 1.915, 9.016, 0, 4, 0.02, -0.02, 0.03, -6, 0, -0.02, 0, -4),
                new Part("root/left_leg/shin", 1.945, 3.016, 0, 4, 0.02, -0.02, 0.03, -6, 0, -0.02, 0, -4),
                new Part("root/left_leg/shin", 1.925, 7.016, 0, 3.98, 0.02, -0.02, 0.01, -1.98, 0, -0.02, 0, -3.98));
    
        private static final double PX = 16;
    
        public static List<ModelBox> boxes(byte stance, boolean aiming, float frac, float yBodyRot) {
            List<Part> parts = aiming ? AIMING : HOLDING;
            List<ModelBox> out = new ArrayList<>(parts.size());
            for (Part p : parts) {
                // model px to the mob's frame: right = -x, up = y, forward = z, blocks
                double r = -p.cx / PX, u = p.cy / PX, f = p.cz / PX;
                Vec3d c = leanPoint(r, u, f, stance, frac, yBodyRot);
                Vec3d eu = edge(r, u, f, -p.ux / PX, p.uy / PX, p.uz / PX, stance, frac, yBodyRot);
                Vec3d ev = edge(r, u, f, -p.vx / PX, p.vy / PX, p.vz / PX, stance, frac, yBodyRot);
                Vec3d ew = edge(r, u, f, -p.wx / PX, p.wy / PX, p.wz / PX, stance, frac, yBodyRot);
                Vec3d corner = c.subtract(eu.add(ev).add(ew).scale(0.5));
                out.add(new ModelBox(p.name, corner.x(), corner.y(), corner.z(), eu.x(), eu.y(), eu.z(), ev.x(), ev.y(), ev.z(), ew.x(), ew.y(), ew.z()));
            }
            return out;
        }
    
        private static Vec3d edge(double r, double u, double f, double dr, double du, double df, byte stance, float frac, float yBodyRot) {
            Vec3d a = leanPoint(r, u, f, stance, frac, yBodyRot);
            Vec3d b = leanPoint(r + dr * 1e-3, u + du * 1e-3, f + df * 1e-3, stance, frac, yBodyRot);
            return b.subtract(a).scale(1e3);
        }
    
        public static float leanFrac(byte prevLean, byte lean, int leanTick, long now, float partial) {
            float t = clamp((now - leanTick + partial) / LEAN_TICKS, 0F, 1F);
            return (prevLean / 100F) + t * (lean / 100F - prevLean / 100F);
        }
    
        static double hipUp(byte stance) {
            return MODEL_FEET - (stance == Stance.CROUCH ? CROUCH_HIP_PX : HIP_PX) / 16.0;
        }
    
        public static Vec3d leanPoint(double right, double up, double forward, byte stance, float frac, float yBodyRot) {
            double hu = hipUp(stance);
            double r = right + frac * LEAN_SHIFT, u = up;
            if (up > hu && frac != 0F) {
                // tip around the forward line through the hips (how far back they are does not matter for a turn
                // around a forward line): up turns toward right for + frac
                double a = Math.toRadians(frac * LEAN_DEG), c = Math.cos(a), s = Math.sin(a);
                double du = up - hu;
                r = frac * LEAN_SHIFT + right * c + du * s;
                u = hu - right * s + du * c;
            }
            return toWorld(r, u, forward, yBodyRot);
        }
    
        public static Vec3d eyeOffset(byte stance, float frac, float yBodyRot, double eyeHeight) {
            return leanPoint(0, eyeHeight, 0, stance, frac, yBodyRot);
        }
    
        static Vec3d toWorld(double right, double up, double forward, float yBodyRot) {
            double y = Math.toRadians(yBodyRot), sin = Math.sin(y), cos = Math.cos(y);
            // forward = (-sin, 0, cos), right = (-cos, 0, -sin)
            return new Vec3d(-forward * sin - right * cos, up, forward * cos - right * sin);
        }
    
    }
}
