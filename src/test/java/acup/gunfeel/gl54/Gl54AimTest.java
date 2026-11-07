package acup.gunfeel.gl54;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

// Where the barrel has to point for a point in the world, with the point's height against the muzzle.
class Gl54AimTest {
    static final double V = Gl54Grenade.SPEED, G = Gl54Grenade.GRAVITY;
    static final Vec3 EYE = new Vec3(10, 100 + 1.62, 20);

    // the grenade fired along `aim` from its muzzle: its height over the muzzle when it has gone `flat` blocks out
    static double heightOver(Gl54Aim.Aim aim, double flat) {
        double elevation = Math.asin(aim.direction().y);
        return Gl54Ballistics.heightAt(elevation, V, G, Gl54Ballistics.AIR_DRAG, flat);
    }

    @Test
    void theHeightAtADistanceIsTheTicksOwnSteps() {
        // 10 degrees up: after the first tick the grenade is v cos out and v sin up
        double a = Math.toRadians(10);
        assertEquals(V * Math.sin(a), Gl54Ballistics.heightAt(a, V, G, 1.0, V * Math.cos(a)), 1e-9);
        // flat: it starts to fall in the second tick
        assertEquals(-G * V, Gl54Ballistics.heightAt(0, V, G, 1.0, 2 * V), 1e-9);
    }

    @Test
    void thePointAtTheMuzzlesHeightIsReachedBelowTheEyesLineByTheDrop() {
        var h = Gl54Ballistics.elevation(40, 0);
        assertTrue(h.reachable());
        assertEquals(0.0, Gl54Ballistics.heightAt(h.angle(), V, G, 1.0, 40), 1e-6);
        // the same angle as the flat-ground holdover
        assertEquals(Gl54Ballistics.holdover(40).angle(), h.angle(), 2e-3);
    }

    @Test
    void aPointAboveNeedsMoreAndAPointBelowLess() {
        double level = Gl54Ballistics.elevation(60, 0).angle();
        double up = Gl54Ballistics.elevation(60, 15).angle();
        double down = Gl54Ballistics.elevation(60, -15).angle();
        assertTrue(up > level && level > down, down + " " + level + " " + up);
        assertEquals(15.0, Gl54Ballistics.heightAt(up, V, G, 1.0, 60), 1e-6);
        assertEquals(-15.0, Gl54Ballistics.heightAt(down, V, G, 1.0, 60), 1e-6);
    }

    @Test
    void aPointFarBelowIsReachedWithTheBarrelPointedDown() {
        var h = Gl54Ballistics.elevation(10, -30);
        assertTrue(h.reachable());
        assertTrue(h.angle() < 0);
        assertEquals(-30.0, Gl54Ballistics.heightAt(h.angle(), V, G, 1.0, 10), 1e-6);
    }

    @Test
    void thePointIsTheLowArc() {
        // at 200 blocks the grenade comes down through a point again past its peak; the first angle that meets it is taken
        var h = Gl54Ballistics.elevation(200, 0);
        assertTrue(h.reachable());
        assertTrue(h.angle() < Gl54Ballistics.farthestAngle(V, G, 1.0));
    }

    @Test
    void aPointBeyondTheReachIsTheFarthestAngleAndNotReachable() {
        var h = Gl54Ballistics.elevation(3000, 0);
        assertFalse(h.reachable());
        assertEquals(Gl54Ballistics.farthestAngle(V, G, 1.0), h.angle(), 1e-12);
    }

    @Test
    void theAimMeetsThePointFromWhereTheShooterStands() {
        for (Vec3 target : new Vec3[] {new Vec3(10, 101.6, 60), new Vec3(40, 112, 90), new Vec3(-20, 95, 70), new Vec3(10, 115, 45), new Vec3(35, 100, -20)}) {
            Gl54Aim.Aim aim = Gl54Aim.solve(EYE, target.subtract(EYE), target);
            assertTrue(aim.reachable(), target.toString());
            // the heading is toward the point from the muzzle
            Vec3 toward = target.subtract(aim.muzzle());
            double flat = Math.hypot(toward.x, toward.z);
            assertEquals(flat, aim.horizontal(), 1e-9);
            assertEquals(toward.x / flat, aim.direction().x / Math.hypot(aim.direction().x, aim.direction().z), 1e-4);
            assertEquals(toward.z / flat, aim.direction().z / Math.hypot(aim.direction().x, aim.direction().z), 1e-4);
            // and at that distance the grenade is at the point's height over the muzzle
            assertEquals(toward.y, heightOver(aim, flat), 2e-3, target.toString());
            assertEquals(1.0, aim.direction().length(), 1e-9);
        }
    }

    @Test
    void theMuzzleIsWhereNtmPutsTheGrenade() {
        // yaw 0 (looking +z): 0.75 ahead, 0.0625 down, and aimed no shift to the side
        Vec3 m = Gl54Aim.muzzle(EYE, new Vec3(0, 0, 1));
        assertEquals(EYE.x, m.x, 1e-12);
        assertEquals(EYE.y - 0.0625, m.y, 1e-12);
        assertEquals(EYE.z + 0.75, m.z, 1e-12);
    }

    @Test
    void movingChangesTheAimButNotThePoint() {
        Vec3 target = new Vec3(10, 101.6, 60);
        Gl54Aim.Aim here = Gl54Aim.solve(EYE, target.subtract(EYE), target);
        Vec3 moved = EYE.add(5, 0, 0);
        Gl54Aim.Aim there = Gl54Aim.solve(moved, target.subtract(moved), target);
        // five blocks to the side turns the heading by about atan(5 / 40) and the meeting is still the point
        double yawHere = Math.atan2(here.direction().x, here.direction().z), yawThere = Math.atan2(there.direction().x, there.direction().z);
        assertEquals(Math.atan2(-5.0, 40.0), yawThere - yawHere, 0.02);
        Vec3 toward = target.subtract(there.muzzle());
        assertEquals(toward.y, heightOver(there, Math.hypot(toward.x, toward.z)), 2e-3);
    }

    @Test
    void straightAboveTheMuzzleIsStraightUp() {
        Vec3 m = Gl54Aim.muzzle(EYE, new Vec3(0, 0, 1));
        Gl54Aim.Aim aim = Gl54Aim.solve(EYE, new Vec3(0, 0, 1), m.add(0, 10, 0));
        assertEquals(1.0, aim.direction().y, 1e-12);
    }
}
