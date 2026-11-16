package acup.gunfeel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.mixin.CasingInstanceAccess;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// NTM's spent casings, drawn for less (MixinCasingDraw). NTM draws every casing alive each frame (12 s each: some 200
// under the fire of 32 AI mobs), each in a call of its own, wherever it lies. Here a casing's body is drawn only in the
// frame and within FAR blocks, at most Config.casingMax of them (the nearest; -1 no limit), and all the bodies go to
// the renderer in one call. The casings live, fall and smoke as before: their smoke is NTM's own.
public final class CasingCull {
    /** past this a casing (a tenth of a block) is under a pixel */
    static final double FAR = 48;
    // the stand's switches (FireStand: casing=ntm|cull|batch)
    static boolean on = true, batch = true;

    private static Frustum frustum;
    private static Vec3 cam;
    private static double limit2;
    private static int left;
    // the frame's one call of the bodies, null till its first body
    private static List<PoseStack.Pose> poses;
    private static List<SubmitNodeCollector.CustomGeometryRenderer> bodies;
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
        poses = null;
        bodies = null;
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

    /**
     * a casing's body at this pose (NTM's call): true when it is taken care of here (left out, or put in the frame's
     * one call), false for NTM's own call. The one call goes where NTM's first body went, so the bodies keep their
     * place among the casings' smoke; it is drawn after this frame's casings are all in it.
     */
    public static boolean body(SubmitNodeCollector collector, PoseStack stack, RenderType renderType,
            SubmitNodeCollector.CustomGeometryRenderer renderer) {
        if (!on || cam == null) return false;
        var m = stack.last().pose();
        float x = m.m30(), y = m.m31(), z = m.m32();
        if (distance2(x, y, z) > limit2 || left <= 0 || !inView(x, y, z)) return true;
        left--;
        drawn++;
        if (!batch) return false;
        if (bodies == null) {
            List<PoseStack.Pose> p = poses = new ArrayList<>();
            List<SubmitNodeCollector.CustomGeometryRenderer> b = bodies = new ArrayList<>();
            collector.submitCustomGeometry(new PoseStack(), renderType, (pose, consumer) -> {
                for (int i = 0; i < b.size(); i++) b.get(i).render(p.get(i), consumer);
            });
        }
        poses.add(stack.last().copy());
        bodies.add(renderer);
        return true;
    }

    // relative to the camera; a casing's box with its spin, a quarter of a block across
    private static boolean inView(float x, float y, float z) {
        if (frustum == null) return true;
        double ax = cam.x + x, ay = cam.y + y, az = cam.z + z;
        return frustum.isVisible(new AABB(ax - 0.25, ay - 0.25, az - 0.25, ax + 0.25, ay + 0.25, az + 0.25));
    }
}
