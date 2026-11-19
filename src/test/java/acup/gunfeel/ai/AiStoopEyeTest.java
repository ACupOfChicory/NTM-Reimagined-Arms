package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import acup.gunfeel.Vec3d;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

// AiStoopBase keeps the threat's eye as core's Vec3d where AiStoop kept the game's Vec3: the same eye is the same
// (Vec3.equals and the record's equals both Double.compare each axis: -0 apart from 0, NaN the same as NaN) and the
// same move is as far (distanceToSqr, the other's minus its own)
class AiStoopEyeTest {
    private static final double[] ODD = {0.0, -0.0, Double.NaN, 1.0, -1.0, 1e-300, Double.POSITIVE_INFINITY, 0.30000000000000004};

    @Test
    void theSameEyeIsTheSame() {
        for (double a : ODD) for (double b : ODD) for (int axis = 0; axis < 3; axis++) {
            double[] u = {1.5, 64, 2.5}, v = {1.5, 64, 2.5};
            u[axis] = a;
            v[axis] = b;
            Vec3 gu = new Vec3(u[0], u[1], u[2]), gv = new Vec3(v[0], v[1], v[2]);
            Vec3d cu = new Vec3d(u[0], u[1], u[2]), cv = new Vec3d(v[0], v[1], v[2]);
            assertEquals(gu.equals(gv), cu.equals(cv), a + " " + b + " axis " + axis);
            assertEquals(Double.doubleToRawLongBits(gu.distanceToSqr(gv)), Double.doubleToRawLongBits(cu.distanceToSqr(cv)), a + " " + b);
        }
    }
}
