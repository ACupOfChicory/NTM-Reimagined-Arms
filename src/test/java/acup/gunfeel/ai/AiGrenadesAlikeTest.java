package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

class AiGrenadesAlikeTest {
    @Test void partialCoverageCanAgreeButEmptyOrDifferentAnswersCannot() {
        AiGrenadesAlike alike = new AiGrenadesAlike();
        assertFalse(alike.agrees());
        alike.share(8, AiGrenadesOld.share(8));
        assertTrue(alike.agrees());
        assertEquals(0, alike.covered[AiGrenadesAlike.Input.AIR.ordinal()]);
        assertFalse(alike.complete());
        alike.share(8, Math.nextUp(AiGrenadesOld.share(8)));
        assertFalse(alike.agrees());
    }

    @Test void coverageIsRequiredOnlyForFullRunsOrBothGrenadeStages() {
        assertTrue(AiGrenadesAlike.needsEveryInput(List.of()));
        assertTrue(AiGrenadesAlike.needsEveryInput(List.of("grenade", "grenadier")));
        assertTrue(AiGrenadesAlike.needsEveryInput(List.of("teams", "grenadier", "grenade")));
        assertFalse(AiGrenadesAlike.needsEveryInput(List.of("grenade", "trench", "teams")));
        assertFalse(AiGrenadesAlike.needsEveryInput(List.of("grenade")));
        assertFalse(AiGrenadesAlike.needsEveryInput(List.of("grenadier")));
        assertFalse(AiGrenadesAlike.needsEveryInput(List.of("teams")));
    }

    @Test void pureEntriesMatchTheFormerCodeOnAnInputGrid() {
        AiGrenadesAlike alike = new AiGrenadesAlike();
        assertNull(AiGrenades.alike);
        AiGrenades.alike = alike;
        try {
            List<VoxelRay.Source> worlds = List.of((x, y, z) -> Voxel.OPEN,
                    (x, y, z) -> y < 0 ? Voxel.SOLID | Voxel.SIGHT : Voxel.OPEN,
                    (x, y, z) -> y < 0 || x == 3 && y < 4 ? Voxel.SOLID | Voxel.SIGHT : Voxel.OPEN,
                    (x, y, z) -> x == 1 ? Voxel.SOLID : Voxel.OPEN);
            for (VoxelRay.Source src : worlds) {
                for (double y : new double[] {-65, 0.02, 1.5})
                    for (double vx : new double[] {-1, -0.04, 0, 0.04, 1})
                        for (int armed : new int[] {-1, 0, 1, 10}) {
                            AiGrenades.burst(src, 0.98, y, 0.5, vx, -0.04, 0.1, 60, armed, 0.5, -64);
                            AiGrenades.burst(src, 0.98, y, 0.5, vx, -0.04, 0.1, 60, armed, 0.5, -64, 0.01, 1);
                            List<double[]> path = new ArrayList<>();
                            path.add(new double[] {-0.0, 42});
                            AiGrenades.burst(src, 0.98, y, 0.5, vx, -0.04, 0.1, 60, armed, 0.5, -64, 0.03, 0.99, path, new double[3]);
                        }
                for (double far : new double[] {-1, -0.0, 0, 8, 20, 100, Double.NaN}) AiGrenades.share(far);
                for (double x : new double[] {0.5, 2.5, 8.5}) for (double height : new double[] {0.6, 1.5, 1.95})
                    for (Vec3 b : List.of(new Vec3(8.5, 0, 0.5), new Vec3(0.5, 4, 3.5))) {
                        AiGrenades.shielded(src, x, 0, 0.5, height, b);
                        for (boolean lying : new boolean[] {false, true}) AiGrenades.shielded(src, x, 0, 0.5, height, b, lying);
                        for (boolean own : new boolean[] {false, true}) {
                            AiGrenades.cover(src, b, x, 0, 0.5, own);
                            for (long cell : new long[] {Long.MIN_VALUE, 0, acup.gunfeel.PosLong.block(3, 0, 0)})
                                AiGrenades.safe(src, b, cell, 12, own);
                        }
                    }
            }
            for (AiGrenadesAlike.Input input : AiGrenadesAlike.Input.values()) {
                if (input == AiGrenadesAlike.Input.PLAN || input == AiGrenadesAlike.Input.STICK)
                    assertEquals(0, alike.covered[input.ordinal()], input.name());
                else assertTrue(alike.covered[input.ordinal()] > 0, input.name());
            }
            assertFalse(alike.complete());
            assertEquals(0, alike.differ, alike.info());
            System.out.println("grenades pure grid: " + alike.info() + "; plan/stick: live stand only");
        } finally {
            AiGrenades.alike = null;
        }
    }

    @Test void facadeComparesAllPureOverloadsIncludingNullLandAndExistingPath() {
        assertNull(AiGrenades.alike);
        AiGrenadesAlike alike = new AiGrenadesAlike();
        AiGrenades.alike = alike;
        try {
            VoxelRay.Source floor = (x, y, z) -> y < 0 ? Voxel.SOLID | Voxel.SIGHT : Voxel.OPEN;
            for (double y : new double[] {-65, 0.02, 3}) for (int armed : new int[] {-1, 0, 10}) {
                AiGrenades.burst(floor, 0.5, y, 0.5, 0.1, -0.04, 0, 60, armed, 0.5, -64);
                AiGrenades.burst(floor, 0.5, y, 0.5, 0.1, -0.04, 0, 60, armed, 0.5, -64, 0.03, 0.99);
                List<double[]> path = new ArrayList<>();
                path.add(new double[] {-0.0, 1, 2});
                double[] land = {1, 2, 3};
                AiGrenades.burst(floor, 0.5, y, 0.5, 0.1, -0.04, 0, 60, armed, 0.5, -64, 0.03, 0.99, path, land);
            }
            VoxelRay.Source wall = (x, y, z) -> x == 1 ? Voxel.SOLID | Voxel.SIGHT : Voxel.OPEN;
            for (VoxelRay.Source src : List.of(floor, wall)) {
                Vec3 burst = new Vec3(8.5, 0, 0.5);
                AiGrenades.share(8);
                AiGrenades.shielded(src, 0.5, 0, 0.5, 0.6, burst);
                AiGrenades.shielded(src, 0.5, 0, 0.5, 1.95, burst, false);
                AiGrenades.safe(src, burst, 0, 12, false);
                AiGrenades.cover(src, burst, 0.5, 0, 0.5, false);
            }
            for (AiGrenadesAlike.Input input : AiGrenadesAlike.Input.values())
                if (input != AiGrenadesAlike.Input.PLAN && input != AiGrenadesAlike.Input.STICK)
                    assertTrue(alike.covered[input.ordinal()] > 0, input.name());
            assertFalse(alike.complete());
            assertEquals(0, alike.differ);
        } finally {
            AiGrenades.alike = null;
        }
    }

    @Test void liveObserverUsesTheFormerBurstComparisonAndVariantCounters() {
        AiGrenadesAlike alike = new AiGrenadesAlike();
        AiGrenadesBase.BurstObserver observer = alike;
        VoxelRay.Source floor = (x, y, z) -> y < 0 ? Voxel.SOLID | Voxel.SIGHT : Voxel.OPEN;
        for (int input = 0; input < 3; input++) {
            double gravity = input == 1 ? 0.0123456789123 : 0.03, drag = input == 1 ? 1 : 0.99;
            double[] land = input == 2 ? new double[3] : null;
            var answer = AiGrenadesBase.burst(floor, -0.0, 2, 0.5, 0.1, -0.04, 0.07, 60, -1, 0.5, -64,
                    gravity, drag, null, land, acup.gunfeel.Vec3d::new);
            assertNotNull(answer);
            observer.burst(floor, -0.0, 2, 0.5, 0.1, -0.04, 0.07, 60, -1, 0.5, -64,
                    gravity, drag, null, land, answer, input);
            assertEquals(1, alike.covered[input]);
            assertEquals(0, alike.differ, alike.info());
        }
        observer.burst(floor, -0.0, 2, 0.5, 0.1, -0.04, 0.07, 60, -1, 0.5, -64,
                0.03, 0.99, null, null, null, 0);
        assertEquals(2, alike.covered[AiGrenadesAlike.Input.BURST.ordinal()]);
        assertEquals(1, alike.differ);
    }

    @Test void comparisonFindsChangedImpactLandPathAndSignedZero() {
        AiGrenadesAlike alike = new AiGrenadesAlike();
        VoxelRay.Source wall = (x, y, z) -> x == 1 ? Voxel.SOLID : Voxel.OPEN;
        List<double[]> before = new ArrayList<>();
        List<double[]> path = new ArrayList<>();
        double[] land = new double[3];
        Vec3 answer = AiGrenadesOld.burst(wall, 0.5, 2, 0.5, 1, 0, 0, 1, 0, 0, -64, 0.03, 0.99, path, land);
        land[0] = Math.nextUp(land[0]);
        alike.burst(wall, 0.5, 2, 0.5, 1, 0, 0, 1, 0, 0, -64, 0.03, 0.99, path, land, before, answer, 2);
        land[0] = 1;
        path.get(0)[4] = -0.0;
        alike.burst(wall, 0.5, 2, 0.5, 1, 0, 0, 1, 0, 0, -64, 0.03, 0.99, path, land, new ArrayList<>(), answer, 2);
        alike.burst(wall, 0.5, 2, 0.5, 1, 0, 0, 1, 0, 0, -64, 0.03, 0.99, null, null, null, answer.add(0, 0, 0.01), 0);
        alike.share(0, -0.0);
        assertEquals(4, alike.differ);
        assertFalse(AiGrenadesAlike.vector(new Vec3(-0.0, 0, 0), Vec3.ZERO));
        assertFalse(AiGrenadesAlike.vector(null, Vec3.ZERO));
        assertTrue(AiGrenadesAlike.vector(null, null));
    }

    @Test void stickyPredicateIsCalledOnceAndReplayChecksOrderAndCoverage() {
        int[] calls = {0};
        AiGrenadesAlike.Trials trials = new AiGrenadesAlike.Trials(p -> ++calls[0] % 2 == 1);
        Vec3 a = new Vec3(1, 2, 3), b = new Vec3(4, 5, 6);
        assertTrue(trials.test(a));
        assertFalse(trials.test(b));
        assertTrue(trials.replay(a));
        assertFalse(trials.replay(b));
        assertTrue(trials.same);
        assertEquals(2, trials.read);
        assertEquals(2, calls[0]);
        assertFalse(trials.replay(a));
        assertFalse(trials.same);
        trials = new AiGrenadesAlike.Trials(p -> true);
        trials.test(a);
        trials.replay(b);
        assertFalse(trials.same);
    }

    @Test void wholeThrowComparisonChecksEveryFieldAndNull() {
        Vec3 burst = new Vec3(1, 2, 3);
        AiGrenadesOld.Throw old = new AiGrenadesOld.Throw(0F, -31.25F, burst, 0.75);
        assertTrue(AiGrenadesAlike.throwing(new AiGrenades.Throw(0F, -31.25F, burst, 0.75), old));
        assertFalse(AiGrenadesAlike.throwing(new AiGrenades.Throw(-0F, -31.25F, burst, 0.75), old));
        assertFalse(AiGrenadesAlike.throwing(new AiGrenades.Throw(0F, Math.nextUp(-31.25F), burst, 0.75), old));
        assertFalse(AiGrenadesAlike.throwing(new AiGrenades.Throw(0F, -31.25F, burst.add(0, 0, 0.01), 0.75), old));
        assertFalse(AiGrenadesAlike.throwing(new AiGrenades.Throw(0F, -31.25F, burst, Math.nextUp(0.75)), old));
        assertFalse(AiGrenadesAlike.throwing(null, old));
        assertFalse(AiGrenadesAlike.throwing(new AiGrenades.Throw(0F, -31.25F, burst, 0.75), null));
        assertTrue(AiGrenadesAlike.throwing(null, null));
    }

    @Test void zeroDifferencesWithoutEveryEntryIsNotEnough() {
        AiGrenadesAlike alike = new AiGrenadesAlike();
        assertFalse(alike.complete());
        for (AiGrenadesAlike.Input input : AiGrenadesAlike.Input.values()) {
            assertFalse(alike.complete());
            alike.covered[input.ordinal()]++;
            assertTrue(alike.info().contains(input.name().toLowerCase(java.util.Locale.ROOT) + "=1"));
        }
        assertTrue(alike.complete());
    }

    @Test void liveCoverageRequiresEveryInputAndEveryTransition() {
        AiGrenadesAlike alike = new AiGrenadesAlike();
        for (AiGrenadesAlike.LiveInput input : AiGrenadesAlike.LiveInput.values()) {
            assertFalse(alike.liveComplete()); alike.liveCovered[input.ordinal()]++;
        }
        for (AiGrenadesAlike.Event event : AiGrenadesAlike.Event.values()) {
            assertFalse(alike.liveComplete()); alike.events[event.ordinal()]++;
            assertTrue(alike.info().contains(event.name() + "=1"));
        }
        assertTrue(alike.liveComplete());
        alike.close();
    }
}
