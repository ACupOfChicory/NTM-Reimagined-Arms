package acup.gunfeel.gl54;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.hbm.client.render.RenderTextures;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;
import com.hbm.render.util.Vertices;
import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.Tracers;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.item.ItemStack;

// The red dot and the hints drawn on the GL-54's reflex sight (the model's ReflexPanel, whose numbers are in Gl54Reflex),
// only in the first-person hand: flat coloured shapes on the pane, full bright (no light in the vertices), clipped to the
// pane's outline, a hair in front of it.
//  - the dot is always there, at the point where the gun's zero line crosses the pane (DOT_U, DOT_V of the pane); the aimed
//    pose puts that point in the middle of the screen (ItemRenderGl54.aim*);
//  - aimed (and settled), the range of the last measurement stands in the lower right corner in pixel digits, "---" when
//    there is none, and with one a thin mark with a gap sits below the dot by the holdover angle: put it on the target and
//    the barrel is raised as far as the grenade needs for that range (Gl54Ballistics). Past the grenade's reach the mark
//    blinks at the farthest angle. From the hip there is only the dot.
public final class ReflexDraw {
    // where the dot sits on the pane (fractions of its width and height): the middle across, high up so the mark has room below
    public static double DOT_U = 0.5, DOT_V = 0.90;
    // the dot's width as per mille of the pane's width, and its soft edge as a share of its radius (1 pixel at 2 pixels across)
    public static double DOT_PER_MILLE = 6.2, DOT_FALL = 1.0;
    // the mark: half length, the gap's half width and thickness, as shares of the pane's width
    public static final double MARK_HALF = 0.075, MARK_GAP = 0.03, MARK_THICK = 0.012;
    // a pixel of the digits as a share of the pane's width, and the margin of the corner
    public static final double DIGIT_PIXEL = 0.016, MARGIN_A = 0.07, MARGIN_B = 0.06;
    // the hints show once the aim has settled this far
    static final float SETTLED = 0.95F;
    // red (255, 40, 30); the alpha is set per layer
    static final int RGB = (255 << 16) | (40 << 8) | 30;
    // how far in front of the pane the shapes lie, model units
    // none: a lift along the pane's normal would shift the dot on the screen (the eye is not on the normal), and nothing is drawn
    // on the pane for the shapes to hide behind
    static final double LIFT = 0.0;

    // dev (Gl54ReflexStand): the points of the last frame, name -> {view x, y, z, screen px x, y, fov}
    public static volatile boolean PROBE;
    public static volatile Map<String, float[]> POINTS = Map.of();

    private record Shape(List<double[]> poly, int argb) {
    }

    private ReflexDraw() {
    }

    // `pose` is at the model's origin in the hand; called once a frame while the gun is drawn
    public static void render(SubmitNodeCollector collector, PoseStack pose, ItemStack stack) {
        Gl54Reflex r = Gl54Reflex.get();
        if (!r.present()) return;
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.mainCamera();
        float world = camera.getFov(), hud = Tracers.hudFov(camera);
        float width = mc.getWindow().getWidth(), height = mc.getWindow().getHeight();
        // the hand is drawn in a pose that undoes the camera's turn (NTM puts the inverse of the model-view matrix in it); the
        // model-view matrix then gives the view space, which is where the sight's geometry is the same at every turn of the view
        Matrix4f m = new Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrixCopy()).mul(pose.last().pose());
        double uL = r.uLength(), vL = r.vLength();
        double[][] window = new double[r.uv().length][];
        for (int i = 0; i < window.length; i++) window[i] = new double[] {r.uv()[i][0] * uL, r.uv()[i][1] * vL};
        double aD = DOT_U * uL, bD = DOT_V * vL;
        boolean hints = ItemGunBaseNT.getIsAiming(stack) && ItemGunBaseNT.aimingProgress >= SETTLED;
        List<Shape> shapes = new ArrayList<>();

        double diameter = DOT_PER_MILLE / 1000.0 * uL, radius = diameter / 2.0, fall = radius * DOT_FALL;
        int[] alphas = {30, 50, 90, 255};
        double[] radii = {radius + fall, radius + fall * 0.66, radius + fall * 0.33, radius};
        for (int i = 0; i < radii.length; i++) add(shapes, ReflexMath.disk(aD, bD, radii[i], 20), window, alphas[i]);

        Map<String, float[]> probe = PROBE ? new LinkedHashMap<>() : null;
        Vector3f dView = m.transformPosition(point(r, aD, bD), new Vector3f());
        float projection = (height / 2F) / (float) Math.tan(Math.toRadians(hud * 0.5));
        if (probe != null) {
            put(probe, "dot", dView, width, height, projection, world);
            for (int i = 0; i < window.length; i++) put(probe, "c" + i, m.transformPosition(point(r, window[i][0], window[i][1]), new Vector3f()), width, height, projection, world);
            put(probe, "o", m.transformPosition(new Vector3f(), new Vector3f()), width, height, projection, world);
            put(probe, "ex", m.transformPosition(new Vector3f(1, 0, 0), new Vector3f()), width, height, projection, world);
            put(probe, "ey", m.transformPosition(new Vector3f(0, 1, 0), new Vector3f()), width, height, projection, world);
            put(probe, "ez", m.transformPosition(new Vector3f(0, 0, 1), new Vector3f()), width, height, projection, world);
            probe.put("hudfov", new float[] {hud, 0F, 0F, 0F, 0F, world});
            probe.put("scale", new float[] {m.transformDirection(new Vector3f(1, 0, 0)).length(), (float) uL, (float) vL, 0F, 0F, world});
        }
        if (hints) {
            float range = Gl54Range.get(stack);
            // the digits, right aligned in the lower right corner
            String text = ReflexMath.rangeText(range);
            double px = DIGIT_PIXEL * uL;
            // the pane's a runs to the gun's left, which is the screen's left seen from behind: the text reads along falling a,
            // its last column nearest the pane's right edge (a = 0)
            double start = MARGIN_A * uL + ReflexMath.textWidth(text) * px, bottom = MARGIN_B * vL;
            for (int[] p : ReflexMath.pixels(text)) {
                double x1 = start - p[0] * px, y1 = bottom + (ReflexMath.GLYPH_H - p[1]) * px;
                add(shapes, ReflexMath.rect(x1 - px, y1 - px, x1, y1), window, 255);
            }
            if (!Float.isNaN(range)) {
                Gl54Ballistics.Holdover h = Gl54Ballistics.holdover(range);
                boolean shown = h.reachable() || (net.minecraft.util.Util.getMillis() / 250L) % 2L == 0L;
                double[] at = mark(r, m, dView, h.angle(), world, hud);
                if (probe != null && at != null) {
                    put(probe, "mark", m.transformPosition(point(r, at[0], at[1]), new Vector3f()), width, height, projection, world);
                    probe.put("holdover", new float[] {(float) Math.toDegrees(h.angle()), (float) (projection * Math.tan(h.angle()) * Math.tan(Math.toRadians(hud * 0.5)) / Math.tan(Math.toRadians(world * 0.5))), h.reachable() ? 1F : 0F, 0F, 0F, world});
                    probe.put("expected", new float[] {0F, (float) ((height / 2F) / Math.tan(Math.toRadians(world * 0.5)) * Math.tan(h.angle())), 0F, 0F, 0F, world});
                }
                if (shown && at != null) {
                    double half = MARK_HALF * uL, gap = MARK_GAP * uL, t = MARK_THICK * uL / 2.0;
                    add(shapes, ReflexMath.rect(at[0] - half, at[1] - t, at[0] - gap, at[1] + t), window, 255);
                    add(shapes, ReflexMath.rect(at[0] + gap, at[1] - t, at[0] + half, at[1] + t), window, 255);
                }
            }
        }
        if (probe != null) {
            POINTS = probe;
        }
        Vector3f lift = new Vector3f((float) (-r.normal()[0] * LIFT), (float) (-r.normal()[1] * LIFT), (float) (-r.normal()[2] * LIFT));
        collector.submitCustomGeometry(pose, RenderTypes.entityTranslucent(RenderTextures.WHITE), (p, vc) -> {
            for (Shape s : shapes) {
                List<double[]> poly = s.poly();
                // both sides: whether the pipeline culls and which way the pane's frame winds is no matter
                for (int i = 1; i + 1 < poly.size(); i++) {
                    corner(vc, p, r, poly.get(0), lift, s.argb());
                    corner(vc, p, r, poly.get(i), lift, s.argb());
                    corner(vc, p, r, poly.get(i + 1), lift, s.argb());
                    corner(vc, p, r, poly.get(i + 1), lift, s.argb());
                    corner(vc, p, r, poly.get(i + 1), lift, s.argb());
                    corner(vc, p, r, poly.get(i + 1), lift, s.argb());
                    corner(vc, p, r, poly.get(i), lift, s.argb());
                    corner(vc, p, r, poly.get(0), lift, s.argb());
                }
            }
        });
    }

    // where the mark sits on the pane (a, b in its metric frame): the pane crossed by the ray that goes from the eye through the
    // dot and then `angle` lower in the world's picture. The hand is drawn with its own field of view, the world with the
    // aim's zoomed one, so the angle is turned into the hand's by their tangents: the mark is as far from the dot, on the
    // screen, as that angle is from the middle of the screen in the world.
    static double[] mark(Gl54Reflex r, Matrix4f m, Vector3f dView, double angle, float world, float hud) {
        if (dView.z >= -1e-4F) return null;
        double k = Math.tan(Math.toRadians(hud * 0.5)) / Math.tan(Math.toRadians(world * 0.5));
        double sx = dView.x / -dView.z, sy = dView.y / -dView.z - Math.tan(angle) * k;
        Vector3f n = m.transformDirection(new Vector3f((float) r.normal()[0], (float) r.normal()[1], (float) r.normal()[2])).normalize();
        Vector3f dir = new Vector3f((float) sx, (float) sy, -1F);
        float denom = n.dot(dir);
        if (Math.abs(denom) < 1e-6F) return null;
        float s = n.dot(dView) / denom;
        if (s <= 0F) return null;
        Vector3f p = new Matrix4f(m).invert().transformPosition(dir.mul(s, new Vector3f()));
        double a = 0, b = 0;
        for (int i = 0; i < 3; i++) {
            double d = p.get(i) - r.origin()[i];
            a += d * r.uAxis()[i];
            b += d * r.vAxis()[i];
        }
        return new double[] {a, b};
    }

    private static Vector3f point(Gl54Reflex r, double a, double b) {
        return new Vector3f((float) (r.origin()[0] + a * r.uAxis()[0] + b * r.vAxis()[0]), (float) (r.origin()[1] + a * r.uAxis()[1] + b * r.vAxis()[1]),
                (float) (r.origin()[2] + a * r.uAxis()[2] + b * r.vAxis()[2]));
    }

    private static void corner(com.mojang.blaze3d.vertex.VertexConsumer vc, PoseStack.Pose p, Gl54Reflex r, double[] ab, Vector3f lift, int argb) {
        Vector3f v = point(r, ab[0], ab[1]).add(lift);
        Vertices.emit(vc, p, v.x, v.y, v.z, argb, 0F, 0F, ItemRenderWeaponBase.FULL_BRIGHT, 0F, 1F, 0F);
    }

    private static void add(List<Shape> out, List<double[]> poly, double[][] window, int alpha) {
        List<double[]> cut = ReflexMath.clip(poly, window);
        if (!cut.isEmpty()) out.add(new Shape(cut, (alpha << 24) | RGB));
    }

    private static void put(Map<String, float[]> out, String name, Vector3f v, float w, float h, float projection, float world) {
        out.put(name, new float[] {v.x, v.y, v.z, w / 2F + v.x / -v.z * projection, h / 2F - v.y / -v.z * projection, world});
    }
}
