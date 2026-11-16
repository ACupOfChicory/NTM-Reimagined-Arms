package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import acup.gunfeel.mixin.CasingInstanceAccess;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/** How many of NTM's casings are drawn: the nearest casingMax of those in view, by squared distance. */
class CasingCullTest {
    @Test
    void theLimitKeepsTheNearestMax() {
        Random r = new Random(7);
        double[] d2 = new double[300];
        for (int i = 0; i < d2.length; i++) d2[i] = r.nextDouble() * 2000;
        for (int max : new int[] {1, 5, 96, 299}) {
            double limit = CasingCull.nearest(d2, d2.length, max);
            int kept = 0;
            double farthestKept = 0, nearestLeft = Double.MAX_VALUE;
            for (double d : d2) {
                if (d <= limit) {
                    kept++;
                    farthestKept = Math.max(farthestKept, d);
                } else nearestLeft = Math.min(nearestLeft, d);
            }
            assertEquals(max, kept, "max " + max);
            assertTrue(farthestKept < nearestLeft, "max " + max + ": a nearer one left out");
        }
    }

    @Test
    void noLimitBelowZeroOrWhenThereAreFewer() {
        double[] d2 = {4, 1, 9};
        assertEquals(Double.MAX_VALUE, CasingCull.nearest(d2, 3, -1));
        assertEquals(Double.MAX_VALUE, CasingCull.nearest(d2, 3, 3));
        assertEquals(Double.MAX_VALUE, CasingCull.nearest(d2, 3, 96));
        assertEquals(1, CasingCull.nearest(d2, 3, 1));
        assertTrue(CasingCull.nearest(d2, 3, 0) < 0, "0: none drawn");
    }

    @Test
    void onlyTheFirstNCountWhenTheArrayIsLonger() {
        double[] d2 = {9, 1, 4, 0.5, 0.25};
        assertEquals(4, CasingCull.nearest(d2, 3, 2));
    }

    private record Casing(double relX, double relY, double relZ, float heightQuarter, float scaleLift, float yaw, float pitch)
            implements CasingInstanceAccess {
        public double gunfeel$relX() { return relX; }
        public double gunfeel$relY() { return relY; }
        public double gunfeel$relZ() { return relZ; }
        public float gunfeel$heightQuarter() { return heightQuarter; }
        public float gunfeel$scaleLift() { return scaleLift; }
        public com.hbm.particle.SpentCasing gunfeel$config() { return null; }
        public int gunfeel$light() { return 0; }

        // the pose NTM's State.submit gives the body: translate, scale, the two turns, the casing's scale
        PoseStack pose() {
            PoseStack s = new PoseStack();
            s.translate(relX, relY - heightQuarter + scaleLift, relZ);
            s.scale(0.05F, 0.05F, 0.05F);
            s.mulPose(Axis.YP.rotationDegrees(180F - yaw));
            s.mulPose(Axis.XP.rotationDegrees(-pitch));
            s.scale(1.3F, 0.7F, 1.1F);
            return s;
        }

        double d2() {
            float x = (float) relX, y = (float) (relY - heightQuarter + scaleLift), z = (float) relZ;
            return (double) x * x + (double) y * y + (double) z * z;
        }
    }

    // the casings drawn in one frame, NTM's own call each (no frustum: a camera state without one)
    private static List<Casing> drawn(List<Casing> all, int max) {
        int before = Config.casingMax;
        try {
            Config.casingMax = max;
            CameraRenderState cam = new CameraRenderState();
            cam.pos = Vec3.ZERO;
            cam.cullFrustum = null;
            CasingCull.begin(all, cam);
            List<Casing> out = new ArrayList<>();
            for (Casing c : all) if (CasingCull.body(c.pose())) out.add(c);
            return out;
        } finally {
            Config.casingMax = before;
        }
    }

    private static List<Casing> casings(int n, long seed) {
        Random r = new Random(seed);
        List<Casing> all = new ArrayList<>();
        for (int i = 0; i < n; i++)
            all.add(new Casing((r.nextDouble() - 0.5) * 120, r.nextDouble() * 4 - 2, (r.nextDouble() - 0.5) * 120, r.nextFloat() * 0.05F,
                    r.nextFloat() * 0.01F, r.nextFloat() * 360, r.nextFloat() * 360));
        return all;
    }

    @Test
    void theFrameDrawsTheNearestMaxWithinFarOnly() {
        List<Casing> all = casings(400, 3);
        long near = all.stream().filter(c -> c.d2() <= CasingCull.FAR * CasingCull.FAR).count();
        assertTrue(near > 120, "the test needs more than 96 within reach: " + near);
        List<Casing> got = drawn(all, 96);
        assertEquals(96, got.size());
        double farthest = got.stream().mapToDouble(Casing::d2).max().orElseThrow();
        for (Casing c : all) if (!got.contains(c)) assertTrue(c.d2() >= farthest, "a nearer casing left out");
    }

    @Test
    void noLimitStillLeavesOutThoseTooFar() {
        List<Casing> all = casings(400, 4);
        List<Casing> got = drawn(all, -1);
        long near = all.stream().filter(c -> c.d2() <= CasingCull.FAR * CasingCull.FAR).count();
        assertEquals(near, got.size());
        assertTrue(got.size() < all.size());
    }

    @Test
    void zeroDrawsNone() {
        assertEquals(0, drawn(casings(50, 5), 0).size());
    }

    @Test
    void fewerThanTheLimitAreAllDrawnWhenNear() {
        List<Casing> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) all.add(new Casing(i, 0.5, -i, 0.02F, 0.005F, i * 30, i * 20));
        assertEquals(10, drawn(all, 96).size());
    }
}
