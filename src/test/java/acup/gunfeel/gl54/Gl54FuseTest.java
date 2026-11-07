package acup.gunfeel.gl54;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Gl54FuseTest {
    @Test
    void noMeasurementNoFuse() {
        assertTrue(Double.isNaN(Gl54Fuse.distance(Float.NaN, 2)));
    }

    @Test
    void theFuseIsTheMeasuredRangeButNotUnderTheArmingDistance() {
        assertEquals(87.0, Gl54Fuse.distance(87F, 0));
        assertEquals(Gl54Grenade.ARM_DISTANCE, Gl54Fuse.distance(1.5F, 0));
    }

    @Test
    void theStepThatCrossesTheFuseIsCutWhereItCrosses() {
        // 4.7 blocks a tick: from 85 to 89.7 with a fuse at 87 is 2 blocks in, 2/4.7 of the step
        assertEquals(2.0 / 4.7, Gl54Fuse.crossing(85.0, 89.7, 87.0), 1e-12);
        assertEquals(1.0, Gl54Fuse.crossing(86.0, 87.0, 87.0), 1e-12);
    }

    @Test
    void stepsBeforeOrAfterItAreNone() {
        assertTrue(Double.isNaN(Gl54Fuse.crossing(80.0, 84.7, 87.0)));
        assertTrue(Double.isNaN(Gl54Fuse.crossing(88.0, 92.7, 87.0)));
        assertTrue(Double.isNaN(Gl54Fuse.crossing(0.0, 4.7, Double.NaN)));
    }

    @Test
    void aGrenadeFlownThroughTheFuseBurstsAtItsRangeToTheBlock() {
        // a flat shot walked in steps of the grenade's speed: the first step to cross the fuse is cut exactly at it
        double f = Gl54Fuse.distance(87F, 0), x = 0;
        while (true) {
            double t = Gl54Fuse.crossing(x, x + Gl54Grenade.SPEED, f);
            if (!Double.isNaN(t)) {
                assertEquals(f, x + Gl54Grenade.SPEED * t, 1e-9);
                break;
            }
            x += Gl54Grenade.SPEED;
        }
    }

    @Test
    void theFuseIsTheRangePlusTheShift() {
        assertEquals(89.0, Gl54Fuse.distance(87F, 2));
        assertEquals(84.0, Gl54Fuse.distance(87F, -3));
        // a shift back past the arming distance is held at it
        assertEquals(Gl54Grenade.ARM_DISTANCE, Gl54Fuse.distance(4F, -3));
    }

    @Test
    void theBurstForAPointIsTakenFromWhereTheShotBegins() {
        net.minecraft.world.phys.Vec3 point = new net.minecraft.world.phys.Vec3(10, 101, 60);
        // measured from the spot 0.75 ahead of the eye at z 20 ... the shooter then steps 5 blocks aside: the distance is to the point
        net.minecraft.world.phys.Vec3 first = new net.minecraft.world.phys.Vec3(10, 101, 20.75), second = new net.minecraft.world.phys.Vec3(15, 101, 20.75);
        assertEquals(39.25 + 1, Gl54Fuse.distance(point, first, 1), 1e-9);
        assertEquals(Math.hypot(5, 39.25) + 1, Gl54Fuse.distance(point, second, 1), 1e-9);
        assertTrue(Gl54Fuse.distance(point, second, 1) > Gl54Fuse.distance(point, first, 1));
        // the burst lies at the point's own heading and distance, wherever the shooter stood: the same point to the block
        double flown = Gl54Fuse.distance(point, second, 0);
        double heading = Math.atan2(point.x - second.x, point.z - second.z);
        assertEquals(point.x, second.x + Math.sin(heading) * flown, 1e-9);
        assertEquals(point.z, second.z + Math.cos(heading) * flown, 1e-9);
    }

    @Test
    void noPointNoBurstAndAShortOneWaitsForTheArming() {
        assertTrue(Double.isNaN(Gl54Fuse.distance((net.minecraft.world.phys.Vec3) null, net.minecraft.world.phys.Vec3.ZERO, 1)));
        assertEquals(Gl54Grenade.ARM_DISTANCE, Gl54Fuse.distance(new net.minecraft.world.phys.Vec3(1, 0, 0), net.minecraft.world.phys.Vec3.ZERO, 0));
    }
}
