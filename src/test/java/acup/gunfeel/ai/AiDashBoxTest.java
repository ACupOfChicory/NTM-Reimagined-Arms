package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.SplittableRandom;

import net.minecraft.world.phys.AABB;

import org.junit.jupiter.api.Test;

// core's crouching box is the game's: AiPose.CROUCH_SIZE.makeBoundingBox(at).deflate(1.0E-7), bit for bit, anywhere
class AiDashBoxTest {
    @Test
    void theGamesCrouchingBox() {
        SplittableRandom r = new SplittableRandom(20261120);
        double[] out = new double[6];
        for (int i = 0; i < 200_000; i++) {
            double x = pick(r), y = pick(r), z = pick(r);
            AABB b = AiPose.CROUCH_SIZE.makeBoundingBox(x, y, z).deflate(1.0E-7);
            AiDashBase.crouchBox(x, y, z, out);
            double[] want = {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
            for (int k = 0; k < 6; k++) {
                int kk = k;
                assertEquals(Double.doubleToRawLongBits(want[k]), Double.doubleToRawLongBits(out[k]), () -> x + " " + y + " " + z + " [" + kk + "]");
            }
        }
    }

    private static double pick(SplittableRandom r) {
        return switch (r.nextInt(4)) {
            case 0 -> r.nextInt(-64, 320) + (r.nextBoolean() ? 0.0 : 0.5);
            case 1 -> r.nextDouble(-3e7, 3e7);
            case 2 -> r.nextDouble(-2, 2);
            default -> r.nextInt(-1000, 1000) + 0.3;
        };
    }
}
