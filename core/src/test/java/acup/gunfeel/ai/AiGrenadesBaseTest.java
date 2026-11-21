package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import acup.gunfeel.LevelMap;
import acup.gunfeel.PosLong;
import acup.gunfeel.Vec3d;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.GrenadeWorlds;
import acup.gunfeel.host.NtmGuns;
import acup.gunfeel.host.Worlds;

class AiGrenadesBaseTest {
    static final VoxelRay.Source AIR = (x, y, z) -> Voxel.OPEN;
    static final VoxelRay.Source FLOOR = (x, y, z) -> y < 0 ? Voxel.SOLID | Voxel.SIGHT : Voxel.OPEN;
    static final VoxelRay.Source WALL = (x, y, z) -> x == 1 ? Voxel.SOLID : Voxel.OPEN;
    static final AiGrenadesBase.Vectors<Vec3d> VECTORS = new AiGrenadesBase.Vectors<>() {
        @Override public Vec3d make(double x, double y, double z) { return new Vec3d(x, y, z); }
        @Override public double x(Vec3d v) { return v.x(); }
        @Override public double y(Vec3d v) { return v.y(); }
        @Override public double z(Vec3d v) { return v.z(); }
        @Override public double distanceTo(Vec3d a, Vec3d b) { return a.distanceTo(b); }
        @Override public double distanceToSqr(Vec3d a, Vec3d b) { return a.distanceToSqr(b); }
    };

    private static void bits(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }

    @Test void impactArmsOnTheInclusiveTickAndKeepsTheFirstLand() {
        double[] land = new double[3];
        List<double[]> path = new ArrayList<>();
        path.add(new double[] {42});
        Vec3d hit = AiGrenadesBase.burst(WALL, 0.5, 2, 0.5, 1, 0, 0, 3, 1, 0.5, -64, 0.03, 0.99, path, land, Vec3d::new);
        assertEquals(new Vec3d(1, 2, 0.5), hit);
        assertArrayEquals(new double[] {1, 2, 0.5}, land);
        assertEquals(2, path.size());
        assertArrayEquals(new double[] {42}, path.get(0));
        assertArrayEquals(new double[] {0.5, 2, 0.5, 1, 0, 0}, path.get(1));
        Vec3d notArmed = AiGrenadesBase.burst(WALL, 0.5, 2, 0.5, 1, 0, 0, 3, 2, 0.5, -64, Vec3d::new);
        assertEquals(new Vec3d(1 - 1e-4, 2, 0.5), notArmed);

        VoxelRay.Source corner = (x, y, z) -> x == 1 || z == 1 ? Voxel.SOLID : Voxel.OPEN;
        path.clear();
        AiGrenadesBase.burst(corner, 0.5, 2, 0.1, 1, 0, 0.4, 8, -1, 0.5, -64, 0, 1, path, land, Vec3d::new);
        assertArrayEquals(new double[] {1, 2, 0.1 + 0.4 * 0.5}, land);
        assertTrue(path.size() > 1);
    }

    @Test void fastHitStopsAtTheRoundedFaceBeforeDragAndGravity() {
        List<double[]> path = new ArrayList<>();
        AiGrenadesBase.burst(WALL, 0.5, 2, 0.5, 1, 0.2, 0.1, 2, -1, 0.8, -64, 0.03, 0.99, path, null, Vec3d::new);
        assertEquals(2, path.size());
        double[] next = path.get(1);
        bits(1 - 1e-4, next[0]);
        bits(2 + 0.2 * 0.5 + 0.2, next[1]);
        bits(0.5 + 0.1 * 0.5 + 0.1, next[2]);
        bits(0, next[3]);
        bits(0.2 * 0.8 * 0.99 - 0.03, next[4]);
        bits(0.1 * 0.8 * 0.99, next[5]);
    }

    @Test void slowHitBouncesAndRestIsTestedBeforeGravity() {
        List<double[]> path = new ArrayList<>();
        AiGrenadesBase.burst(WALL, 0.98, 2, 0.5, 0.04, 0, 0.1, 2, -1, 0.5, -64, 0, 1, path, null, Vec3d::new);
        double[] next = path.get(1);
        bits(1 - 0.05 + 0.04, next[0]);
        bits(-0.04 * 0.5, next[3]);
        bits(0.1 * 0.8, next[5]);

        path.clear();
        Vec3d rest = AiGrenadesBase.burst(FLOOR, 0.5, 0.02, 0.5, 0, -0.04, 0, 60, -1, 0.5, -64,
                0.03, 0.99, path, null, Vec3d::new);
        assertEquals(1, path.size());
        bits(0 + 0.05 - 0.04, rest.y());
        assertEquals(0.5, rest.x());
    }

    @Test void minYIsCheckedAfterAppendingTheStartAndLandIsReset() {
        double[] land = {1, 2, 3};
        List<double[]> path = new ArrayList<>();
        assertNull(AiGrenadesBase.burst(AIR, 0, -65, 0, 0, 0, 0, 1, -1, 0, -64, 0, 1, path, land, Vec3d::new));
        assertEquals(1, path.size());
        for (double v : land) assertTrue(Double.isNaN(v));
        assertNotNull(AiGrenadesBase.burst(AIR, 0, -64, 0, 0, 0, 0, 1, -1, 0, -64, Vec3d::new));
        assertNotNull(AiGrenadesBase.burst(AIR, 0, -65, 0, 0, 0, 0, 0, -1, 0, -64, Vec3d::new));
    }

    @Test void ddaTiesVisitXThenYThenZInBothDirections() {
        for (int sign : new int[] {1, -1}) {
            List<String> cells = new ArrayList<>();
            VoxelRay.Source src = (x, y, z) -> { cells.add(x + "," + y + "," + z); return Voxel.OPEN; };
            double[] hit = {7, 8};
            assertFalse(AiGrenadesBase.first(src, sign * 0.5, sign * 0.5, sign * 0.5,
                    sign * 1.5, sign * 1.5, sign * 1.5, hit));
            assertEquals(sign > 0 ? List.of("1,0,0", "1,1,0", "1,1,1")
                    : List.of("-2,-1,-1", "-2,-2,-1", "-2,-2,-2"), cells);
            assertArrayEquals(new double[] {7, 8}, hit);
        }
        double[] hit = new double[2];
        assertTrue(AiGrenadesBase.first((x, y, z) -> Voxel.SOLID, 0.5, 0.5, 0.5, 1.5, 1.5, 1.5, hit));
        assertArrayEquals(new double[] {0.5, 0}, hit);
        assertTrue(AiGrenadesBase.first((x, y, z) -> x == 1 && y == 1 ? Voxel.SOLID : Voxel.OPEN,
                0.5, 0.5, 0.5, 1.5, 1.5, 1.5, hit));
        assertArrayEquals(new double[] {0.5, 1}, hit);
        assertTrue(AiGrenadesBase.first(WALL, 0.5, 0, 0, 1, 0, 0, hit));
        assertArrayEquals(new double[] {1, 0}, hit);
        assertFalse(AiGrenadesBase.first((x, y, z) -> { fail("starting cell"); return 0; },
                0.1, 0.1, 0.1, 0.9, 0.9, 0.9, hit));
    }

    @Test void fromAtUsesFloatYawArithmeticAndAppendsTheOriginalStart() {
        for (float yaw : new float[] {-137.12345F, -0F, 0F, 91.23456F}) {
            List<double[]> path = new ArrayList<>();
            double force = 1.23456789012345;
            AiGrenadesBase.fromAt(AIR, 2, 10, -3, yaw, -31.25F, force, 0.5, -1000, 0.1, -0.2, 0.3, path, null, -1, Vec3d::new);
            double r = Math.toRadians(180F - yaw), f = Math.toRadians(-31.25F), g = Math.toRadians(-yaw);
            assertArrayEquals(new double[] {2 + 0.25 * Math.cos(r) + 0.1, 10 - 0.25 - 0.2, -3 - 0.25 * Math.sin(r) + 0.3,
                    Math.sin(g) * Math.cos(f) * force, -Math.sin(f) * force, Math.cos(g) * Math.cos(f) * force}, path.get(0));
            assertEquals(60, path.size());
            path.clear();
            AiGrenadesBase.fromAt(AIR, 2, 10, -3, yaw, -31.25F, force, 0.5, -1000, 0, 0, 0, path, null, 10, Vec3d::new);
            assertEquals(100, path.size());
        }
    }

    final class Rig implements AiGrenadesBase.Staff, AutoCloseable {
        final Object level = new Object();
        VoxelRay.Source src = FLOOR;
        int eyes, ownCalls, forceReads, bounceReads, rejectFirst, rejectFrom = Integer.MAX_VALUE;
        float cornerEye = Float.NaN;
        double force;
        boolean unsafe;
        final Body body = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> switch (m.getName()) {
            case "x", "y", "z" -> 0.0;
            case "eyeHeight" -> { eyes++; yield eyes > 21 && !Float.isNaN(cornerEye) ? cornerEye : 1.75F; }
            default -> throw new AssertionError(m.getName());
        });
        final NtmGuns guns = (NtmGuns) Proxy.newProxyInstance(NtmGuns.class.getClassLoader(), new Class<?>[] {NtmGuns.class}, (p, m, a) -> switch (m.getName()) {
            case "fragForce" -> { forceReads++; yield force; }
            case "fragBounce" -> { bounceReads++; yield 0.5; }
            default -> throw new AssertionError(m.getName());
        });

        Rig() { GrenadeWorlds.put(level, -1000); }
        @Override public VoxelRay.Source voxels(Object l) { assertSame(level, l); return src; }
        @Override public boolean ownNear(Object l, VoxelRay.Source s, Body b, double bx, double by, double bz, double down) {
            assertSame(level, l); assertSame(src, s); assertSame(body, b); ownCalls++;
            return unsafe || ownCalls <= rejectFirst || ownCalls >= rejectFrom;
        }
        AiGrenadesBase.Throw<Vec3d> plan(Vec3d at) { return AiGrenadesBase.planNow(this, guns, level, body, at, false, 0, 0.55, VECTORS); }
        @Override public void close() { Worlds.forget(level); AiGrenadesBase.forgetLevel(level); }
    }

    @Test void equalCandidatesKeepTheFirstPitchAndBothRefinementsRun() {
        try (Rig r = new Rig()) {
            var t = r.plan(Vec3d.ZERO);
            assertNotNull(t);
            assertEquals(-90F, t.yaw());
            assertEquals(-70F, t.pitch());
            assertEquals(1, t.share());
            assertEquals(17 + 4 + 8, r.eyes);
            assertEquals(9, r.ownCalls);
            assertEquals(1, r.forceReads);
            assertEquals(1, r.bounceReads);
            r.unsafe = true;
            assertNull(r.plan(Vec3d.ZERO));
        }
    }

    @Test void aPitchBetweenCoarseSamplesIsRefinedTwice() {
        try (Rig r = new Rig()) {
            r.src = AIR;
            r.force = 1.2;
            Vec3d target = AiGrenadesBase.fromAt(AIR, 0, 1.75, 0, -90F, -61.25F, r.force, 0.5, -1000,
                    0, 0, 0, null, null, -1, Vec3d::new);
            var t = r.plan(target);
            assertNotNull(t);
            assertEquals(-61.25F, t.pitch());
            assertTrue(t.burst().distanceTo(target) < 0.3);
            assertEquals(29, r.eyes);
        }
    }

    @Test void equalRefinementsKeepMinusBeforePlusEvenOutsideTheInitialRange() {
        try (Rig r = new Rig()) {
            r.rejectFirst = 17;
            var t = r.plan(Vec3d.ZERO);
            assertNotNull(t);
            assertEquals(-72.5F, t.pitch());
            assertEquals(26, r.ownCalls);
        }
    }

    @Test void ddaEachAxisCanBeStrictlyFirst() {
        VoxelRay.Source solid = (x, y, z) -> Voxel.SOLID;
        double[] hit = new double[2];
        for (int axis = 0; axis < 3; axis++) {
            double[] end = {1.5, 1.5, 1.5};
            end[axis] = 2.5;
            assertTrue(AiGrenadesBase.first(solid, 0.5, 0.5, 0.5, end[0], end[1], end[2], hit));
            assertArrayEquals(new double[] {0.25, axis}, hit);
        }
    }

    @Test void everyCornerMustExistStayNearTheTargetAndKeepOffItsOwn() {
        try (Rig r = new Rig()) {
            r.cornerEye = -2000F;
            assertNull(r.plan(Vec3d.ZERO));
        }
        try (Rig r = new Rig()) {
            r.src = AIR;
            r.cornerEye = 20F;
            Vec3d target = AiGrenadesBase.fromAt(AIR, 0, 1.75, 0, -90F, -70F, 0, 0.5, -1000, 0, 0, 0, null, null, -1, Vec3d::new);
            assertNull(r.plan(target));
        }
        try (Rig r = new Rig()) {
            r.rejectFrom = 2;
            assertNull(r.plan(Vec3d.ZERO));
        }
    }

    @Test void aFlightCanHitThenFallOutOfTheWorld() {
        try (Rig r = new Rig()) {
            r.force = 1.2;
            r.src = (x, y, z) -> x == 1 && y < 3 ? Voxel.SOLID : Voxel.OPEN;
            Worlds.forget(r.level);
            GrenadeWorlds.put(r.level, 0);
            assertNull(r.plan(new Vec3d(8, 0, 8)));
        }
    }

    @Test void restNeedsBothSmallHorizontalAndSmallVerticalSpeed() {
        for (double[] v : new double[][] {{1, 0.2, 0}, {1, 0, 0.1}}) {
            List<double[]> path = new ArrayList<>();
            AiGrenadesBase.burst(WALL, 0.5, 2, 0.5, v[0], v[1], v[2], 2, -1, 0.5, -64, 0.03, 0.99, path, null, Vec3d::new);
            assertEquals(2, path.size());
        }
    }

    @Test void glueRejectsACornerThatMissesTheOnlyWallEvenWhenThePredicateAcceptsNaN() {
        try (Rig r = new Rig()) {
            r.force = 1.2;
            r.src = (x, y, z) -> x == 1 && z == 1 ? Voxel.SOLID : Voxel.OPEN;
            assertNull(AiGrenadesBase.planStick(r, r.guns, r.level, 0, 1.75, 0.75, new Vec3d(8, 0, 0.75), b -> true, VECTORS));
        }
    }

    @Test void glueKeepsEqualCandidatesInPitchOrderAndChecksEveryCornerWithoutLandLimit() {
        try (Rig r = new Rig()) {
            List<Vec3d> tried = new ArrayList<>();
            var t = AiGrenadesBase.planStick(r, r.guns, r.level, 0, 1.75, 0, new Vec3d(100, 0, 0),
                    b -> { tried.add(b); return true; }, VECTORS);
            assertNotNull(t);
            assertEquals(-70F, t.pitch());
            assertTrue(t.burst().distanceTo(new Vec3d(100, 0, 0)) > AiGrenadesBase.LAND);
            assertEquals(25, tried.size());
            assertSame(tried.get(0), t.burst());
            for (int i = 0; i < 17; i++) assertEquals(t.burst(), tried.get(i));
            int i = 17;
            double rad = Math.toRadians(180F - t.yaw());
            for (int sx : new int[] {-1, 1}) for (int sz : new int[] {-1, 1}) for (int h = 0; h < 2; h++) {
                Vec3d corner = tried.get(i++);
                bits(0.25 * Math.cos(rad) + sx * 0.175, corner.x());
                bits(-0.25 * Math.sin(rad) + sz * 0.175, corner.z());
            }
            int[] calls = {0};
            assertNull(AiGrenadesBase.planStick(r, r.guns, r.level, 0, 1.75, 0, Vec3d.ZERO, b -> ++calls[0] <= 17, VECTORS));
            assertEquals(34, calls[0]);
            r.src = AIR;
            assertNull(AiGrenadesBase.planStick(r, r.guns, r.level, 0, 1.75, 0, Vec3d.ZERO, b -> { fail("no land"); return true; }, VECTORS));
        }
    }

    @Test void behindUsesMovementButShieldRequiresSightAndAllBodyRays() {
        Vec3d burst = new Vec3d(8.5, 64, 0.5);
        VoxelRay.Source glass = (x, y, z) -> x == 1 ? Voxel.SOLID : Voxel.OPEN;
        VoxelRay.Source low = (x, y, z) -> y < 64 || x == 1 && z >= -3 && z <= 3 && y < 65 ? Voxel.SOLID | Voxel.SIGHT : Voxel.OPEN;
        assertTrue(AiGrenadesBase.behind(glass, burst, 0.5, 64, 0.5));
        assertFalse(AiGrenadesBase.shielded(glass, 0.5, 64, 0.5, 0.6, burst.x(), burst.y(), burst.z()));
        assertTrue(AiGrenadesBase.shielded(low, 0.5, 64, 0.5, 0.6, burst.x(), burst.y(), burst.z()));
        assertFalse(AiGrenadesBase.shielded(low, 0.5, 64, 0.5, 1.5, burst.x(), burst.y(), burst.z()));
        assertFalse(AiGrenadesBase.shielded(low, 0.5, 64, 3.5, 0.6, 8.5, 64, 3.5));
        assertFalse(AiGrenadesBase.cover(low, burst.x(), burst.y(), burst.z(), 0.5, 64, 0.5, false));
        assertFalse(AiGrenadesBase.cover(low, burst.x(), burst.y(), burst.z(), 0.5, 64, 0.5, true));
        assertFalse(AiGrenadesBase.shielded(low, 8.5, 64, 0.5, 0.6, burst.x(), burst.y(), burst.z()));
    }

    @Test void nearIsStrictAndRejectsMissingCellsWhileSafeUnpacksThem() {
        long cell = PosLong.block(-3, 4, 5);
        Vec3d burst = new Vec3d(-2.5, 4, 6.5);
        assertFalse(AiGrenadesBase.near(cell, burst.x(), burst.y(), burst.z(), 1));
        assertTrue(AiGrenadesBase.near(cell, burst.x(), burst.y(), burst.z(), Math.nextUp(1.0)));
        assertTrue(AiGrenadesBase.safe(AIR, burst.x(), burst.y(), burst.z(), cell, 1, true));
        assertFalse(AiGrenadesBase.safe(AIR, burst.x(), burst.y(), burst.z(), cell, Math.nextUp(1.0), true));
        Vec3d missing = new Vec3d(PosLong.blockX(Long.MIN_VALUE) + 0.5, PosLong.blockY(Long.MIN_VALUE), PosLong.blockZ(Long.MIN_VALUE) + 0.5);
        assertFalse(AiGrenadesBase.near(Long.MIN_VALUE, missing.x(), missing.y(), missing.z(), 10));
        assertFalse(AiGrenadesBase.safe(AIR, missing.x(), missing.y(), missing.z(), Long.MIN_VALUE, 10, true));
    }

    @Test void twoLevelsHaveIndependentBudgetsIncludingTickReversalAndForget() throws Exception {
        Object a = new Object(), b = new Object();
        try {
            for (Object level : new Object[] {a, b}) {
                assertTrue(AiGrenadesBase.planFree(level, 1000));
                assertTrue(AiGrenadesBase.planFree(level, 1000));
                assertTrue(AiGrenadesBase.mayPlan(level, 1000));
                assertFalse(AiGrenadesBase.planFree(level, 1000));
                Field plannedField = AiGrenadesBase.class.getDeclaredField("PLANNED");
                plannedField.setAccessible(true);
                LevelMap<?> planned = (LevelMap<?>) plannedField.get(null);
                Object before = planned.get(level);
                assertFalse(AiGrenadesBase.mayPlan(level, 1000));
                assertNotSame(before, planned.get(level));
                assertTrue(AiGrenadesBase.mayPlan(level, 999));
                assertTrue(AiGrenadesBase.mayPlan(level, 1000));
                for (int i = 0; i < 6; i++) assertTrue(AiGrenadesBase.mayRefuge(level, 1000));
                for (int i = 0; i < 3; i++) assertFalse(AiGrenadesBase.mayRefuge(level, 1000));
            }
            Field field = AiGrenadesBase.class.getDeclaredField("REFUGES");
            field.setAccessible(true);
            LevelMap<?> refuges = (LevelMap<?>) field.get(null);
            assertEquals(9, ((long[]) refuges.get(a))[1]);
            assertEquals(9, ((long[]) refuges.get(b))[1]);
            assertTrue(AiGrenadesBase.mayRefuge(a, 999));
            assertTrue(AiGrenadesBase.mayRefuge(a, 1000));
            assertFalse(AiGrenadesBase.mayRefuge(b, 1000));
            AiGrenadesBase.forgetLevel(a);
            assertTrue(AiGrenadesBase.planFree(a, 1000));
            assertTrue(AiGrenadesBase.mayPlan(a, 1000));
            for (int i = 0; i < 6; i++) assertTrue(AiGrenadesBase.mayRefuge(a, 1000));
            assertFalse(AiGrenadesBase.mayRefuge(a, 1000));
            assertFalse(AiGrenadesBase.planFree(b, 1000));
            assertFalse(AiGrenadesBase.mayRefuge(b, 1000));
            for (int i = 0; i < 6; i++) assertTrue(AiGrenadesBase.mayRefuge(a, Long.MIN_VALUE));
            assertFalse(AiGrenadesBase.mayRefuge(a, Long.MIN_VALUE));
            assertTrue(AiGrenadesBase.mayPlan(a, Long.MIN_VALUE));
            assertFalse(AiGrenadesBase.mayPlan(a, Long.MIN_VALUE));
            assertEquals(0, NtmGuns.NONE.fragForce());
            assertEquals(0, NtmGuns.NONE.fragBounce());
        } finally {
            AiGrenadesBase.forgetLevel(a);
            AiGrenadesBase.forgetLevel(b);
        }
    }
}
