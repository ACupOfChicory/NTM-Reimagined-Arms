package acup.gunfeel.water;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

// The model of a sunk round with an impact fuze lies along NTM's heading of it: NTM sets yaw = atan2(dX, dZ) in degrees
// (EntityBulletBaseMK4.tick), and the heading has to give that course back on every side, ±X included.
class SunkBulletsTest {
    @Test
    void headingIsTheCourseNtmTookTheYawFrom() {
        double[][] courses = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {0.6, 0.8}, {-0.8, 0.6}, {-0.28, -0.96}, {0.96, -0.28}};
        for (double[] c : courses) {
            float yaw = (float) (Math.atan2(c[0], c[1]) * 180.0 / Math.PI);
            Vec3 h = SunkBullets.heading(yaw);
            assertEquals(c[0], h.x, 1e-6, "x of course " + c[0] + "," + c[1]);
            assertEquals(0, h.y, 1e-9);
            assertEquals(c[1], h.z, 1e-6, "z of course " + c[0] + "," + c[1]);
        }
    }
}
