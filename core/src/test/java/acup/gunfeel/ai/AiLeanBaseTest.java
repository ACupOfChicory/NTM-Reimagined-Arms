package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import acup.gunfeel.ModelBox;
import acup.gunfeel.Vec3d;

class AiLeanBaseTest {
    private static final byte[] STANCES = {Stance.STAND, Stance.CROUCH, Stance.PRONE, -1};
    private static final float[] LEANS = {-1F, -0.73F, -0.01F, -0F, 0F, 0.01F, 0.37F, 1F};
    private static final float[] YAWS = {-180F, -135F, -60F, -0F, 0F, 37F, 90F, 135F, 360F};
    private static final double HIP = 1.501 - 12.0 / 16.0;
    private static final double[] HEIGHTS = {-0.2, 0, 0.4, Math.nextDown(HIP), HIP, Math.nextUp(HIP), 1.27, 1.74, 2.13};

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
