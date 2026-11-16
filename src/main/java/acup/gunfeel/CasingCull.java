package acup.gunfeel;

import java.util.Arrays;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.mixin.CasingInstanceAccess;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// NTM's spent casings, drawn for less (MixinCasingDraw). NTM draws every casing alive each frame (12 s each: some 200
// under the fire of 32 AI mobs), wherever it lies. Here a casing's body is drawn only in the frame and within FAR
// blocks, at most Config.casingMax of them (the nearest; -1 no limit). The casings live, fall and smoke as before:
// their smoke is NTM's own.
public final class CasingCull {
    /** past this a casing (a tenth of a block) is under a pixel */
    static final double FAR = 48;
    // the stand's switch (FireStand: casing=ntm|cull)
    static boolean on = true;
    /** the stand's control frame: no casing bodies at all (FireStand body-) */
    static boolean hidden;

    private static Frustum frustum;
    private static Vec3 cam;
    private static double limit2;
    private static int left;
    /** bodies drawn in the last frame, of how many (the stand's count) */
    static int drawn, alive;
    private static double[] d2 = new double[256];

    private CasingCull() {
    }

    /** the frame's limit, before NTM's casings go to the renderer */
    public static void begin(List<?> instances, CameraRenderState camera) {
        frustum = camera.cullFrustum;
        cam = camera.pos;
        int max = Config.casingMax;
        int n = 0;
        if (d2.length < instances.size()) d2 = new double[instances.size() * 2];
        if (max >= 0 && instances.size() > max) {
            for (Object o : instances) {
                // the accessor not applied: NTM's casings as they are
                if (!(o instanceof CasingInstanceAccess i)) {
                    cam = null;
                    return;
                }
                // as the pose's matrix holds it (PoseStack.translate takes it in floats), so body() finds the same
                float x = (float) i.gunfeel$relX(), y = (float) (i.gunfeel$relY() - i.gunfeel$heightQuarter() + i.gunfeel$scaleLift()),
                        z = (float) i.gunfeel$relZ();
                double d = distance2(x, y, z);
                if (d <= FAR * FAR && inView(x, y, z)) d2[n++] = d;
            }
        }
        limit2 = Math.min(FAR * FAR, nearest(d2, n, max));
        left = max < 0 ? Integer.MAX_VALUE : max;
        alive = instances.size();
        drawn = 0;
    }

    static double distance2(float x, float y, float z) {
        return (double) x * x + (double) y * y + (double) z * z;
    }

    /**
     * the squared distance within which the nearest max of these n lie: all of them with max below 0 or no more than
     * max of them, none with max 0
     */
    static double nearest(double[] d2, int n, int max) {
        if (max < 0 || n <= max) return Double.MAX_VALUE;
        if (max == 0) return -1;
        double[] s = Arrays.copyOf(d2, n);
        Arrays.sort(s);
        return s[max - 1];
    }

    /** whether a casing's body at this pose is drawn */
    public static boolean body(PoseStack stack) {
        if (hidden) return false;
        if (!on || cam == null) return true;
        var m = stack.last().pose();
        float x = m.m30(), y = m.m31(), z = m.m32();
        if (distance2(x, y, z) > limit2 || left <= 0 || !inView(x, y, z)) return false;
        left--;
        drawn++;
        return true;
    }

    // relative to the camera; a casing's box with its spin, a quarter of a block across
    private static boolean inView(float x, float y, float z) {
        if (frustum == null) return true;
        double ax = cam.x + x, ay = cam.y + y, az = cam.z + z;
        return frustum.isVisible(new AABB(ax - 0.25, ay - 0.25, az - 0.25, ax + 0.25, ay + 0.25, az + 0.25));
    }
}
