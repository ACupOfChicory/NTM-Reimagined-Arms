package acup.gunfeel.gl54;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.Tracers;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

// The red dot and the hints drawn on the GL-54's reflex sight (the model's ReflexPanel, whose numbers are in Gl54Reflex),
// only in the first-person hand, as the sight's own light: an LED seen in the glass, the same at noon, at midnight and in a
// cave (no lightmap, no shading, no hurt tint: PIPELINE). Each mark is a small opaque core of the LED's red with a soft glow
// around it that only adds light; the pane gets a faint coating tint. The marks are laid out on a grid of half a screen pixel
// on the pane, each point of it coloured by its distance to the mark (ReflexMath.light) and the colours blended between the
// points, only inside the pane's outline.
//  - the dot is always there, at the point where the gun's zero line crosses the pane (DOT_U, DOT_V of the pane); the aimed
//    pose puts that point in the middle of the screen (ItemRenderGl54.aim*);
//  - aimed (and settled), the distance to the point the rangefinder found, and the burst's shift, stand in the lower right
//    corner in thin LED digits ("40+1"), "---" when there is none. With a point found, a thin ring sits where the barrel's aim
//    has to be to meet it (Gl54Aim: the heading and the launch angle from where the shooter stands now): it is fixed in the
//    world, so it slides over the glass as the gun moves, and with the dot in the ring the barrel points where the grenade is to
//    go. Past the grenade's reach the ring is for the farthest angle and blinks. A ring off the pane is a chevron at the pane's
//    edge toward it. From the hip there is only the dot.
public final class ReflexDraw {
    // where the dot sits on the pane (fractions of its width and height): its middle, which is where the eye looks along the sight's
    // axis (ItemRenderGl54.aim*: the eye, the pane's middle and the screen's middle on one line)
    public static double DOT_U = 0.5, DOT_V = 0.5;
    // a screen pixel as a share of the pane's width at 1920x1080 with the sight aimed (the pane is about 219 px across there);
    // the marks' light is set in these pixels
    static final double PX = 1.0 / 219.0;
    // the dot's core across, as per mille of the pane's width: 2.2 px, which takes in the 2 x 2 pixels round the screen's middle
    // (where the dot sits aimed) and none of the next ones
    public static double DOT_PER_MILLE = 10.0;
    // the dot: a hard core, a glow of 140 falling with a sigma of 1.1 px (below 10 about 3.5 px from the middle), a faintly
    // hotter middle
    static final double DOT_PEAK = 140, DOT_SIGMA = 1.1, DOT_HOT = 40, DOT_HOT_RADIUS = 1.2;
    // the ring, the chevron and the digits: lines opaque 1 px across, their edges smoothed over half a pixel on each side
    // (1.5 px in all), with a faint glow
    static final ReflexMath.Light LINE = new ReflexMath.Light(0.75, 0.5, 70, 0.9, 0, 0);
    // the aim marker's ring: the radius of its line; the chevron's length and half width; how far inside the pane's edge its
    // tip stands (shares of the pane's width)
    public static final double RING_RADIUS = 0.0163;
    public static final double ARROW_LENGTH = 0.05, ARROW_HALF = 0.02, ARROW_INSET = 0.015;
    // a pixel of the digits' font as a share of the pane's width (the strokes run between their middles), and the margin of the corner
    public static final double DIGIT_PIXEL = 0.016, MARGIN_A = 0.07, MARGIN_B = 0.06;
    // the hints show once the aim has settled this far
    static final float SETTLED = 0.95F;
    // the LED's red, #FF2418 (about 650 nm)
    static final int RED = 255, GREEN = 36, BLUE = 24;
    // the pane's coating: a faint blue film, premultiplied (3% of the view behind it is taken, a little blue given back)
    static final int GLASS = (8 << 24) | (3 << 16) | (4 << 8) | 6;

    // Light that keeps its own brightness: the vertex colour only (fog aside), no lightmap, no shading, no overlay, blended
    // as premultiplied colour (an opaque core covers what is behind, a glow with alpha 0 only adds). Depth is tested, so the
    // gun's body in front of the pane hides it, and not written.
    public static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("ntmgunfeel", "pipeline/reflex_light"))
            .withVertexShader("core/rendertype_lightning")
            .withFragmentShader("core/rendertype_lightning")
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .withCull(false)
            .build();
    private static RenderType type;

    // dev (Gl54ReflexStand): the points of the last frame, name -> {view x, y, z, screen px x, y, fov}
    public static volatile boolean PROBE;
    public static volatile Map<String, float[]> POINTS = Map.of();

    private record Shape(List<double[]> poly, int argb) {
    }

    // a cell of light: its corner on the pane (a, b) and the premultiplied colours of its corners, counterclockwise from it
    private record Cell(double a, double b, int c00, int c10, int c11, int c01) {
    }

    private interface Distance {
        double at(double a, double b);
    }

    // the digits' cells, made again only when the text changes
    private static String digitsText;
    private static Gl54Reflex digitsFor;
    private static List<Cell> digits = List.of();

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
        List<Cell> cells = new ArrayList<>();
        // the glass's tint under everything
        add(shapes, java.util.Arrays.asList(window), window, GLASS);

        // a screen pixel on the pane, and a cell
        double px = PX * uL, cell = px / 2.0;
        ReflexMath.Light dot = new ReflexMath.Light(DOT_PER_MILLE / 2000.0 / PX, 0, DOT_PEAK, DOT_SIGMA, DOT_HOT, DOT_HOT_RADIUS);
        double reach = (dot.half() + dot.reach()) * px + cell;
        light(cells, window, px, aD - reach, bD - reach, aD + reach, bD + reach, (a, b) -> Math.hypot(a - aD, b - bD), dot, aD, bD);

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
            net.minecraft.world.phys.Vec3 target = Gl54Range.target(stack);
            net.minecraft.world.phys.Vec3 eye = camera.position();
            Vector3f lookF = camera.rotation().transform(new Vector3f(0F, 0F, -1F));
            Gl54Aim.Aim aim = target == null ? null : Gl54Aim.solve(eye, new net.minecraft.world.phys.Vec3(lookF.x, lookF.y, lookF.z), target);
            // the digits, right aligned in the lower right corner: the distance now, as the grenade will fly it, and the shift
            float shown = aim != null ? (float) aim.horizontal() : range;
            String text = ReflexMath.rangeText(shown, Gl54Range.offset(stack));
            if (!text.equals(digitsText) || digitsFor != r) {
                digits = digits(r, window, text, px);
                digitsText = text;
                digitsFor = r;
            }
            cells.addAll(digits);
            if (aim != null) {
                boolean lit = aim.reachable() || (net.minecraft.util.Util.getMillis() / 250L) % 2L == 0L;
                // the aim in the camera's view: the world's slopes, then the hand's
                Vector3f av = camera.rotation().conjugate(new org.joml.Quaternionf()).transform(new Vector3f((float) aim.direction().x, (float) aim.direction().y, (float) aim.direction().z));
                double depth = Math.max(-av.z, 0.01);
                double hx = ReflexMath.handSlope(av.x / depth, hud, world), hy = ReflexMath.handSlope(av.y / depth, hud, world);
                double[] at = markerAt(r, m, hx, hy);
                if (probe != null && at != null) {
                    put(probe, "marker", m.transformPosition(point(r, at[0], at[1]), new Vector3f()), width, height, projection, world);
                    probe.put("aim", new float[] {(float) Math.toDegrees(aim.elevation()), (float) aim.horizontal(), aim.reachable() ? 1F : 0F, av.x, av.y, av.z});
                }
                if (lit && at != null) {
                    if (ReflexMath.inside(window, at[0], at[1])) {
                        double ring = RING_RADIUS * uL, out = ring + (LINE.half() + LINE.reach()) * px + cell;
                        light(cells, window, px, at[0] - out, at[1] - out, at[0] + out, at[1] + out,
                                (a, b) -> Math.abs(Math.hypot(a - at[0], b - at[1]) - ring), LINE, at[0], at[1]);
                    } else {
                        // the dot's place to the ring, and the edge it leaves the pane by
                        double[] edge = ReflexMath.exitPoint(window, aD, bD, at[0] - aD, at[1] - bD);
                        if (edge != null) {
                            double da = at[0] - aD, db = at[1] - bD, n = Math.hypot(da, db);
                            double inset = ARROW_INSET * uL;
                            List<double[]> v = ReflexMath.arrow(edge[0] - da / n * inset, edge[1] - db / n * inset, da / n, db / n, ARROW_LENGTH * uL, ARROW_HALF * uL);
                            double[][] arms = {{v.get(0)[0], v.get(0)[1], v.get(1)[0], v.get(1)[1]}, {v.get(0)[0], v.get(0)[1], v.get(2)[0], v.get(2)[1]}};
                            double out = (LINE.half() + LINE.reach()) * px + cell;
                            double a0 = Double.MAX_VALUE, b0 = Double.MAX_VALUE, a1 = -Double.MAX_VALUE, b1 = -Double.MAX_VALUE;
                            for (double[] q : v) {
                                a0 = Math.min(a0, q[0]); b0 = Math.min(b0, q[1]); a1 = Math.max(a1, q[0]); b1 = Math.max(b1, q[1]);
                            }
                            light(cells, window, px, a0 - out, b0 - out, a1 + out, b1 + out,
                                    (a, b) -> Math.min(ReflexMath.segmentDistance(a, b, arms[0]), ReflexMath.segmentDistance(a, b, arms[1])), LINE, Double.NaN, 0);
                        }
                    }
                }
            }
        }
        if (probe != null) {
            POINTS = probe;
        }
        collector.submitCustomGeometry(pose, type(), (p, vc) -> {
            // the tint first, the light over it, in the order given (the type is not sorted)
            for (Shape s : shapes) {
                List<double[]> poly = s.poly();
                for (int i = 1; i + 1 < poly.size(); i++) {
                    corner(vc, p, r, poly.get(0), s.argb());
                    corner(vc, p, r, poly.get(i), s.argb());
                    corner(vc, p, r, poly.get(i + 1), s.argb());
                    corner(vc, p, r, poly.get(i + 1), s.argb());
                }
            }
            for (Cell c : cells) {
                corner(vc, p, r, new double[] {c.a(), c.b()}, c.c00());
                corner(vc, p, r, new double[] {c.a() + cell, c.b()}, c.c10());
                corner(vc, p, r, new double[] {c.a() + cell, c.b() + cell}, c.c11());
                corner(vc, p, r, new double[] {c.a(), c.b() + cell}, c.c01());
            }
        });
    }

    public static RenderType type() {
        if (type == null) type = acup.gunfeel.HoleRender.create("ntmgunfeel_reflex_light", RenderSetup.builder(PIPELINE).createRenderSetup());
        return type;
    }

    // The cells of light in the box (a0, b0)-(a1, b1) of the pane, on a grid of half a pixel whose points are coloured for a mark
    // whose line is `distance` away; (ma, mb) is the dot's middle for the hot spot (NaN: none). Points outside the pane are dark,
    // and dark cells are left out.
    private static void light(List<Cell> out, double[][] window, double px, double a0, double b0, double a1, double b1, Distance distance,
            ReflexMath.Light l, double ma, double mb) {
        double cell = px / 2.0;
        long i0 = (long) Math.floor(a0 / cell), j0 = (long) Math.floor(b0 / cell);
        int ni = (int) Math.ceil(a1 / cell - i0) + 1, nj = (int) Math.ceil(b1 / cell - j0) + 1;
        int[][] c = new int[ni][nj];
        for (int i = 0; i < ni; i++) {
            for (int j = 0; j < nj; j++) {
                double a = (i0 + i) * cell, b = (j0 + j) * cell;
                if (!ReflexMath.inside(window, a, b)) continue;
                double middle = Double.isNaN(ma) ? Double.MAX_VALUE : Math.hypot(a - ma, b - mb) / px;
                c[i][j] = ReflexMath.light(l, distance.at(a, b) / px, middle, RED, GREEN, BLUE);
            }
        }
        for (int i = 0; i + 1 < ni; i++)
            for (int j = 0; j + 1 < nj; j++)
                if ((c[i][j] | c[i + 1][j] | c[i + 1][j + 1] | c[i][j + 1]) != 0)
                    out.add(new Cell((i0 + i) * cell, (j0 + j) * cell, c[i][j], c[i + 1][j], c[i + 1][j + 1], c[i][j + 1]));
    }

    // the digits as thin strokes, right aligned in the lower right corner of the pane
    private static List<Cell> digits(Gl54Reflex r, double[][] window, String text, double px) {
        double uL = r.uLength(), vL = r.vLength(), dp = DIGIT_PIXEL * uL;
        // the pane's a runs to the gun's left, which is the screen's left seen from behind: the text reads along falling a,
        // its last column nearest the pane's right edge (a = 0)
        double start = MARGIN_A * uL + ReflexMath.textWidth(text) * dp, bottom = MARGIN_B * vL;
        List<double[]> strokes = new ArrayList<>();
        for (double[] s : ReflexMath.strokes(text))
            strokes.add(new double[] {start - s[0] * dp, bottom + (ReflexMath.GLYPH_H - s[1]) * dp, start - s[2] * dp, bottom + (ReflexMath.GLYPH_H - s[3]) * dp});
        double out = (LINE.half() + LINE.reach()) * px + px / 2.0;
        List<Cell> cells = new ArrayList<>();
        light(cells, window, px, start - ReflexMath.textWidth(text) * dp - out, bottom - out, start + out, bottom + ReflexMath.GLYPH_H * dp + out, (a, b) -> {
            double d = Double.MAX_VALUE;
            for (double[] s : strokes) d = Math.min(d, ReflexMath.segmentDistance(a, b, s));
            return d;
        }, LINE, Double.NaN, 0);
        return List.copyOf(cells);
    }

    // Where the aim marker sits on the pane (a, b in its metric frame): the pane crossed by the ray from the eye with the hand's
    // slopes (sideways, upward) over a unit of depth; null when the ray is parallel to the pane or crosses it behind the eye.
    // `m` takes the model into the hand's view space.
    static double[] markerAt(Gl54Reflex r, Matrix4f m, double hx, double hy) {
        Vector3f n = m.transformDirection(new Vector3f((float) r.normal()[0], (float) r.normal()[1], (float) r.normal()[2])).normalize();
        Vector3f dir = new Vector3f((float) hx, (float) hy, -1F);
        float denom = n.dot(dir);
        if (Math.abs(denom) < 1e-9F) return null;
        Vector3f origin = m.transformPosition(new Vector3f((float) r.origin()[0], (float) r.origin()[1], (float) r.origin()[2]), new Vector3f());
        float s = n.dot(origin) / denom;
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

    // on the pane itself: a lift along its normal would shift the dot on the screen (the eye is not on the normal)
    private static void corner(com.mojang.blaze3d.vertex.VertexConsumer vc, PoseStack.Pose p, Gl54Reflex r, double[] ab, int argb) {
        Vector3f v = point(r, ab[0], ab[1]);
        vc.addVertex(p, v.x, v.y, v.z).setColor(argb);
    }

    private static void add(List<Shape> out, List<double[]> poly, double[][] window, int argb) {
        List<double[]> cut = ReflexMath.clip(poly, window);
        if (!cut.isEmpty()) out.add(new Shape(cut, argb));
    }

    private static void put(Map<String, float[]> out, String name, Vector3f v, float w, float h, float projection, float world) {
        out.put(name, new float[] {v.x, v.y, v.z, w / 2F + v.x / -v.z * projection, h / 2F - v.y / -v.z * projection, world});
    }
}
