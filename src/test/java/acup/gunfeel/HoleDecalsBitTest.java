package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

// The holes' vertices before the render port and after it, call for call, on the game's own types (render-step1.md §7,
// echo's condition 4): the drawing as Tracers had it at 9c39d04, copied word for word below (only the hole's record,
// its mask and its moving part are this test's), against core's HoleDecals written through RenderPort26's writer.
// Both into a VertexConsumer that keeps every call with the raw bits of every argument, the pose's defaults
// (addVertex, setNormal with a pose; setLight, setOverlay) as well as what they hand on. Random holes: every face,
// turn, scratch and variant, masks, clipped and empty rects, planes, two-sided and culled, cut meshes with their
// degenerate polygons, moving parts (cut away too), ages through the fade; the camera on the hole's plane too.
class HoleDecalsBitTest {
    private static final int SCRATCH_TURN = 1000, VARIANT_SHIFT = 11, TURN_MASK = (1 << VARIANT_SHIFT) - 1;

    private record Hole(Vec3 pos, Direction face, int turn, long born, int light, int kind, float size, double[] rect, Mask mask, double plane,
            boolean late, boolean cull, float[] mesh, boolean front, Follow follow) {
    }

    private record Mask(int u0, int v0, int w, int h, boolean[] solid, double plane) {
    }

    private static final class Follow {
        Vec3 pos;
        float[] mesh;
        int light;
    }

    // Decals.cell before the port
    private static float[] oldCell(int variant, float u, float v) {
        int c = Math.floorMod(variant, Decals.VARIANTS);
        float e = 1F / 512F;
        u = Math.min(1F - e, Math.max(e, u));
        v = Math.min(1F - e, Math.max(e, v));
        return new float[] {(c % Decals.GRID + u) / Decals.GRID, (c / Decals.GRID + v) / Decals.GRID};
    }

    // ---- Tracers at 9c39d04, word for word

    private static void drawHoles(com.mojang.blaze3d.vertex.PoseStack.Pose p, VertexConsumer vc, List<Hole> list, Vec3 cam, long now, long life) {
        {
            for (Hole h : list) {
                double age = (now - h.born()) / 1e9;
                double fadeFrom = Math.max(0.0, life / 1e9 - 3.0);
                int alpha = (int) (255 * Math.clamp(1.0 - (age - fadeFrom) / 3.0, 0.0, 1.0));
                if (alpha <= 0) continue;
                float half = h.size() * 0.5F;
                if (h.mesh() != null) {
                    meshQuads(p, vc, h, cam, alpha);
                    continue;
                }
                cells(h, cell -> quad(p, vc, h, cam, half, alpha, cell));
            }
        }
    }

    private static void cells(Hole h, java.util.function.Consumer<double[]> out) {
        Mask m = h.mask();
        if (m == null) {
            out.accept(h.rect());
            return;
        }
        for (int j = 0; j < m.h(); j++) {
            for (int i = 0; i < m.w(); i++) {
                if (!m.solid()[j * m.w() + i]) continue;
                int e = i;
                while (e + 1 < m.w() && m.solid()[j * m.w() + e + 1]) e++;
                double[] r = h.rect();
                out.accept(new double[] {Math.max(r[0], (m.u0() + i) / 16.0), Math.min(r[1], (m.u0() + e + 1) / 16.0),
                        Math.max(r[2], (m.v0() + j) / 16.0), Math.min(r[3], (m.v0() + j + 1) / 16.0)});
                i = e;
            }
        }
    }

    private static int[] axes(Direction face) {
        return switch (face.getAxis()) {
            case X -> new int[] {2, 1};
            case Y -> new int[] {0, 2};
            default -> new int[] {0, 1};
        };
    }

    private static double comp(Vec3 v, int axis) {
        return axis == 0 ? v.x : axis == 1 ? v.y : v.z;
    }

    private static void quad(PoseStack.Pose p, VertexConsumer vc, Hole h, Vec3 cam, float size, int alpha, double[] clip) {
        Direction f = h.face();
        int normalAxis = f.getAxis().ordinal();
        double base = Double.isNaN(h.plane()) ? comp(h.pos(), normalAxis) : h.plane();
        // lifted off the face towards the camera: a two-sided hole (iron bars) seen from behind would otherwise lie
        // behind the bars' own pixels and show only where the depth bias wins, cut off along a straight line
        int side = f.getAxisDirection().getStep();
        if (!h.cull()) {
            double d = comp(cam, normalAxis) - base;
            if (d != 0) side = d > 0 ? 1 : -1;
        }
        quadAt(p, vc, h, cam, size, alpha, clip, side, false, side != f.getAxisDirection().getStep());
    }

    private static void quadAt(PoseStack.Pose p, VertexConsumer vc, Hole h, Vec3 cam, float size, int alpha, double[] clip, int side, boolean reverse,
            boolean flipNormal) {
        Direction f = h.face();
        int[] ax = axes(f);
        double pu = comp(h.pos(), ax[0]), pv = comp(h.pos(), ax[1]);
        double u0 = Math.max(pu - size, clip[0]), u1 = Math.min(pu + size, clip[1]);
        double v0 = Math.max(pv - size, clip[2]), v1 = Math.min(pv + size, clip[3]);
        if (u0 >= u1 || v0 >= v1) return;
        int normalAxis = f.getAxis().ordinal();
        double base = Double.isNaN(h.plane()) ? comp(h.pos(), normalAxis) : h.plane();
        double plane = base + 0.002 * side;
        Vector3f n = new Vector3f(f.getStepX(), f.getStepY(), f.getStepZ());
        if (flipNormal) n.negate();
        int color = (alpha << 24) | 0xFFFFFF;
        double[][] corners = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
        // counter-clockwise seen from outside the face (its front for a one-sided hole): the face's u, v axes turn
        // the other way on east / west and up / down faces
        boolean flip = (f.getAxis() == Direction.Axis.Z ? 1 : -1) * f.getAxisDirection().getStep() < 0;
        {
            for (int j = 0; j < 4; j++) {
                double[] c = corners[flip != reverse ? 3 - j : j];
                // where this corner falls on the hole's texture, 0..1, then turned / mirrored
                float[] uv = holeUv(h.turn(), (float) ((c[0] - (pu - size)) / (2 * size)), (float) ((c[1] - (pv - size)) / (2 * size)));
                float tu = uv[0], tv = uv[1];
                double[] w = new double[3];
                w[ax[0]] = c[0];
                w[ax[1]] = c[1];
                w[normalAxis] = plane;
                vc.addVertex(p, (float) (w[0] - cam.x), (float) (w[1] - cam.y), (float) (w[2] - cam.z)).setColor(color).setUv(tu, 1F - tv)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(h.light()).setNormal(p, n.x, n.y, n.z);
            }
        }
    }

    private static float[] holeUv(int turnAndVariant, float a, float b) {
        float[] uv = turned(turnAndVariant & TURN_MASK, a, b);
        float[] c = oldCell(turnAndVariant >>> VARIANT_SHIFT, uv[0], 1F - uv[1]);
        return new float[] {c[0], 1F - c[1]};
    }

    private static float[] turned(int turn, float a, float b) {
        if (turn >= SCRATCH_TURN) {
            double r = Math.toRadians(turn - SCRATCH_TURN), c = Math.cos(r), sn = Math.sin(r);
            double da = (a - 0.5) * 0.7071, db = (b - 0.5) * 0.7071;
            return new float[] {(float) (0.5 + da * c + db * sn), (float) (0.5 - da * sn + db * c)};
        }
        if ((turn & 4) != 0) a = 1F - a;
        return switch (turn & 3) {
            case 1 -> new float[] {b, 1F - a};
            case 2 -> new float[] {1F - a, 1F - b};
            case 3 -> new float[] {1F - b, a};
            default -> new float[] {a, b};
        };
    }

    private static void meshQuads(PoseStack.Pose p, VertexConsumer vc, Hole h, Vec3 cam, int alpha) {
        Follow f = h.follow();
        float[] m = f != null ? f.mesh : h.mesh();
        if (m == null) return;
        Vec3 pos = f != null ? f.pos : h.pos();
        int light = f != null ? f.light : h.light();
        float nx = m[0], ny = m[1], nz = m[2];
        // towards the camera, as in quad(); a one-sided hole seen from behind is not drawn
        if ((cam.x - pos.x) * nx + (cam.y - pos.y) * ny + (cam.z - pos.z) * nz < 0) {
            if (h.front()) return;
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        // on the faces themselves, only pulled towards the camera along each vertex's own line of sight (a tenth of a
        // percent of the distance): it stays on the same screen pixels, so the parts on two faces of an edge still
        // meet, yet lies in front of the face (a two-sided hole on bars seen from behind). Lifted along the normal
        // instead, each face's part stood off its face and an edge showed a strip of the block between them
        float bx = (float) (pos.x - cam.x), by = (float) (pos.y - cam.y), bz = (float) (pos.z - cam.z);
        final float toward = 0.999F;
        int color = (alpha << 24) | 0xFFFFFF;
        int k = 3;
        while (k < m.length) {
            int n = (int) m[k++];
            int base = k;
            // a fan of quads (the last vertex twice where a triangle is left)
            for (int i = 1; i + 1 < n; i += 2) {
                int[] idx = {0, i, i + 1, Math.min(i + 2, n - 1)};
                for (int j : idx) {
                    int o = base + j * 6;
                    vc.addVertex(p, (bx + m[o]) * toward, (by + m[o + 1]) * toward, (bz + m[o + 2]) * toward).setColor(m[o + 5] >= 1F ? color : ((int) (alpha * m[o + 5]) << 24) | 0xFFFFFF).setUv(m[o + 3], m[o + 4])
                            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
                }
            }
            k = base + n * 6;
        }
    }

    private static void bakeMesh(PoseStack.Pose p, VertexConsumer vc, Hole h, Vec3 origin, boolean back) {
        Follow f = h.follow();
        float[] m = f != null ? f.mesh : h.mesh();
        if (m == null) return;
        Vec3 pos = f != null ? f.pos : h.pos();
        int light = f != null ? f.light : h.light();
        float nx = m[0], ny = m[1], nz = m[2];
        float bx = (float) (pos.x - origin.x), by = (float) (pos.y - origin.y), bz = (float) (pos.z - origin.z);
        int copies = h.front() || h.cull() ? 1 : 2;
        int k = 3;
        while (k < m.length) {
            int n = (int) m[k++];
            int base = k;
            k = base + n * 6;
            float sign = 0F;
            for (int i = 1; i + 1 < n && sign == 0F; i++) {
                int a = base, q = base + i * 6, r = base + (i + 1) * 6;
                float ux = m[q] - m[a], uy = m[q + 1] - m[a + 1], uz = m[q + 2] - m[a + 2];
                float wx = m[r] - m[a], wy = m[r + 1] - m[a + 1], wz = m[r + 2] - m[a + 2];
                float d = (uy * wz - uz * wy) * nx + (uz * wx - ux * wz) * ny + (ux * wy - uy * wx) * nz;
                if (Math.abs(d) > 1e-12F) sign = Math.signum(d);
            }
            if (sign == 0F) continue;
            for (int copy = back ? 1 : 0; copy < copies; copy++) {
                boolean rev = (sign < 0F) != (copy == 1);
                for (int i = 1; i + 1 < n; i += 2) {
                    int[] idx = {0, i, i + 1, Math.min(i + 2, n - 1)};
                    for (int t = 0; t < 4; t++) {
                        int o = base + idx[rev ? (4 - t) % 4 : t] * 6;
                        vc.addVertex(p, bx + m[o], by + m[o + 1], bz + m[o + 2]).setColor(((int) (255 * m[o + 5]) << 24) | 0xFFFFFF).setUv(m[o + 3], m[o + 4])
                                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, sign * nx, sign * ny, sign * nz);
                    }
                }
            }
        }
    }

    private static double towardCamera(Hole h, Vec3 cam) {
        Follow f = h.follow();
        float[] m = f != null ? f.mesh : h.mesh();
        if (m != null) {
            Vec3 pos = f != null ? f.pos : h.pos();
            return (cam.x - pos.x) * m[0] + (cam.y - pos.y) * m[1] + (cam.z - pos.z) * m[2];
        }
        int axis = h.face().getAxis().ordinal();
        double base = Double.isNaN(h.plane()) ? comp(h.pos(), axis) : h.plane();
        return (comp(cam, axis) - base) * h.face().getAxisDirection().getStep();
    }

    // Tracers.bake(Batch, ...) at 9c39d04: its loop over the batch's holes, word for word
    private static void oldBake(PoseStack.Pose pose, VertexConsumer builder, List<Hole> holes, Vec3 o, boolean back) {
            for (Hole h : holes) {
                if (h.mesh() != null) {
                    bakeMesh(pose, builder, h, o, back);
                    continue;
                }
                float half = h.size() * 0.5F;
                int step = h.face().getAxisDirection().getStep();
                cells(h, cell -> {
                    if (!back) quadAt(pose, builder, h, o, half, 255, cell, step, false, false);
                    // two-sided (iron bars): its back as well, lifted to the back and wound the other way. The same
                    // normal on both: lit as the hole sent each frame is from either side
                    if (!h.cull()) quadAt(pose, builder, h, o, half, 255, cell, -step, true, false);
                });
            }
    }

    // ---- the hole as Tracers.decal(Hole) makes it
    private static HoleDecal decal(Hole h) {
        Mask m = h.mask();
        Direction f = h.face();
        Follow follow = h.follow();
        HoleDecal d = new HoleDecal(h.pos().x, h.pos().y, h.pos().z, f.getAxis().ordinal(), f.getAxisDirection().getStep(), h.turn(), h.born(),
                h.light(), h.kind(), h.size(), h.rect(), m == null ? 0 : m.u0(), m == null ? 0 : m.v0(), m == null ? 0 : m.w(), m == null ? 0 : m.h(),
                m == null ? null : m.solid(), h.plane(), h.late(), h.cull(), h.mesh(), h.front(), false, false, follow != null, null);
        if (follow != null) d.moved(follow.pos.x, follow.pos.y, follow.pos.z, follow.mesh, follow.light);
        return d;
    }

    // every call with its arguments' raw bits
    private static final class Rec implements VertexConsumer {
        final List<String> log = new ArrayList<>();
        int vertices;

        private VertexConsumer put(String name, float... v) {
            StringBuilder b = new StringBuilder(name);
            for (float f : v) b.append(' ').append(Integer.toHexString(Float.floatToRawIntBits(f)));
            log.add(b.toString());
            return this;
        }

        private VertexConsumer puti(String name, int... v) {
            StringBuilder b = new StringBuilder(name);
            for (int i : v) b.append(' ').append(i);
            log.add(b.toString());
            return this;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vertices++;
            return put("addVertex", x, y, z);
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return puti("setColor4", r, g, b, a);
        }

        @Override
        public VertexConsumer setColor(int argb) {
            return puti("setColor", argb);
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return put("setUv", u, v);
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return puti("setUv1", u, v);
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return puti("setUv2", u, v);
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return put("setNormal", x, y, z);
        }

        @Override
        public VertexConsumer setLineWidth(float w) {
            return put("setLineWidth", w);
        }

        @Override
        public VertexConsumer addVertex(PoseStack.Pose p, float x, float y, float z) {
            put("addVertex(pose)", x, y, z);
            return VertexConsumer.super.addVertex(p, x, y, z);
        }

        @Override
        public VertexConsumer setNormal(PoseStack.Pose p, float x, float y, float z) {
            put("setNormal(pose)", x, y, z);
            return VertexConsumer.super.setNormal(p, x, y, z);
        }

        @Override
        public VertexConsumer setLight(int light) {
            puti("setLight", light);
            return VertexConsumer.super.setLight(light);
        }

        @Override
        public VertexConsumer setOverlay(int overlay) {
            puti("setOverlay", overlay);
            return VertexConsumer.super.setOverlay(overlay);
        }
    }

    private static double comp(Vec3 v, Direction.Axis a) {
        return a == Direction.Axis.X ? v.x : a == Direction.Axis.Y ? v.y : v.z;
    }

    private static float[] mesh(Random r) {
        List<float[]> polys = new ArrayList<>();
        int count = 1 + r.nextInt(3);
        for (int p = 0; p < count; p++) {
            int n = 2 + r.nextInt(6);
            boolean line = r.nextInt(8) == 0;
            float[] pl = new float[n * 6];
            for (int i = 0; i < n; i++) {
                float t = r.nextFloat();
                pl[i * 6] = line ? t : r.nextFloat() - 0.5F;
                pl[i * 6 + 1] = line ? 2 * t : r.nextFloat() - 0.5F;
                pl[i * 6 + 2] = line ? -t : (r.nextFloat() - 0.5F) * 0.1F;
                pl[i * 6 + 3] = r.nextFloat();
                pl[i * 6 + 4] = r.nextFloat();
                pl[i * 6 + 5] = switch (r.nextInt(4)) {
                    case 0 -> 1F;
                    case 1 -> 1.2F;
                    case 2 -> 0F;
                    default -> r.nextFloat();
                };
            }
            polys.add(pl);
        }
        int size = 3;
        for (float[] pl : polys) size += 1 + pl.length;
        float[] m = new float[size];
        Vector3f n = new Vector3f(r.nextFloat() - 0.5F, r.nextFloat() - 0.5F, r.nextFloat() - 0.5F).normalize();
        if (r.nextInt(10) == 0) n.set(0, 0, 1);
        m[0] = n.x;
        m[1] = n.y;
        m[2] = n.z;
        int k = 3;
        for (float[] pl : polys) {
            m[k++] = pl.length / 6;
            System.arraycopy(pl, 0, m, k, pl.length);
            k += pl.length;
        }
        return m;
    }

    private static Hole hole(Random r, long now) {
        Direction face = Direction.values()[r.nextInt(6)];
        double far = r.nextInt(6) == 0 ? 29_999_000 : 1000;
        Vec3 pos = new Vec3(Math.floor((r.nextDouble() - 0.5) * 2 * far) + r.nextDouble(), Math.floor(r.nextDouble() * 300 - 60) + r.nextDouble(),
                Math.floor((r.nextDouble() - 0.5) * 2 * far) + r.nextDouble());
        int turn = r.nextInt(3) == 0 ? SCRATCH_TURN + r.nextInt(360) : r.nextInt(8);
        turn |= r.nextInt(70) << VARIANT_SHIFT;
        float size = 0.03F + r.nextFloat() * 0.6F;
        int[] ax = axes(face);
        double pu = comp(pos, ax[0]), pv = comp(pos, ax[1]);
        double[] rect = switch (r.nextInt(5)) {
            case 0 -> new double[] {pu - size, pu + size, pv - size, pv + size};
            case 1 -> new double[] {pu + size, pu + 2 * size, pv - 1, pv + 1};
            default -> new double[] {pu - r.nextDouble() * size * 1.5, pu + r.nextDouble() * size * 1.5, pv - r.nextDouble() * size * 1.5,
                    pv + r.nextDouble() * size * 1.5};
        };
        Mask mask = null;
        if (r.nextBoolean()) {
            int w = 1 + r.nextInt(7), hh = 1 + r.nextInt(7);
            boolean[] solid = new boolean[w * hh];
            for (int i = 0; i < solid.length; i++) solid[i] = r.nextInt(3) != 0;
            mask = new Mask((int) Math.floor(pu * 16) - r.nextInt(w + 1), (int) Math.floor(pv * 16) - r.nextInt(hh + 1), w, hh, solid, Double.NaN);
        }
        double plane = r.nextBoolean() ? Double.NaN : comp(pos, face.getAxis().ordinal()) + (r.nextDouble() - 0.5) * 0.01;
        long life = 30_000_000_000L;
        long born = now - (long) (r.nextDouble() * (life + 2_000_000_000L));
        if (r.nextInt(8) == 0) born = now;
        float[] m = r.nextInt(3) == 0 ? mesh(r) : null;
        Follow follow = null;
        if (r.nextInt(4) == 0) {
            follow = new Follow();
            follow.pos = pos.add(r.nextDouble() - 0.5, r.nextDouble() - 0.5, r.nextDouble() - 0.5);
            follow.mesh = r.nextInt(4) == 0 ? null : mesh(r);
            follow.light = r.nextInt(1 << 24);
        }
        return new Hole(pos, face, turn, born, r.nextInt(1 << 24), r.nextInt(Decals.COUNT), size, m != null ? null : rect, m != null ? null : mask,
                plane, r.nextBoolean(), r.nextBoolean(), m, r.nextBoolean(), follow);
    }

    private static PoseStack.Pose pose(Random r) {
        PoseStack ps = new PoseStack();
        if (r.nextInt(4) != 0) {
            ps.translate(r.nextFloat() * 4 - 2, r.nextFloat() * 4 - 2, r.nextFloat() * 4 - 2);
            ps.mulPose(new Quaternionf().rotateXYZ(r.nextFloat() * 6, r.nextFloat() * 6, r.nextFloat() * 6));
            if (r.nextBoolean()) ps.scale(0.5F + r.nextFloat(), 0.5F + r.nextFloat(), 0.5F + r.nextFloat());
        }
        return ps.last();
    }

    private static Vec3 camera(Random r, Hole h) {
        Vec3 c = h.pos().add((r.nextDouble() - 0.5) * 20, (r.nextDouble() - 0.5) * 20, (r.nextDouble() - 0.5) * 20);
        if (r.nextInt(6) == 0) {
            // on the hole's plane: quad() keeps the face's own side
            double base = Double.isNaN(h.plane()) ? comp(h.pos(), h.face().getAxis().ordinal()) : h.plane();
            c = switch (h.face().getAxis()) {
                case X -> new Vec3(base, c.y, c.z);
                case Y -> new Vec3(c.x, base, c.z);
                case Z -> new Vec3(c.x, c.y, base);
            };
        }
        return c;
    }

    @Test
    void sameCallsAsBeforeThePort() {
        int vertices = 0, holes = 0;
        for (int seed = 0; seed < 3000; seed++) {
            Random r = new Random(seed * 7919L + 13);
            long now = 100_000_000_000L + r.nextInt(1000);
            long life = r.nextInt(5) == 0 ? (long) (r.nextFloat() * 3e9) : 30_000_000_000L;
            List<Hole> old = new ArrayList<>();
            List<HoleDecal> now2 = new ArrayList<>();
            int n = 1 + r.nextInt(12);
            for (int i = 0; i < n; i++) {
                Hole h = hole(r, now);
                old.add(h);
                now2.add(decal(h));
            }
            Vec3 cam = camera(r, old.get(r.nextInt(n)));
            PoseStack.Pose pose = pose(r);
            RenderPort26.Writer w = new RenderPort26.Writer();

            // sent each frame
            Rec a = new Rec(), b = new Rec();
            drawHoles(pose, a, old, cam, now, life);
            w.pose = pose;
            w.vc = b;
            HoleDecals.drawHoles(w, now2, cam.x, cam.y, cam.z, now, life);
            assertEquals(a.log, b.log, "drawn each frame, seed " + seed);
            vertices += a.vertices;

            // a batch, all and backs only
            for (boolean back : new boolean[] {false, true}) {
                Rec c = new Rec(), d = new Rec();
                Vec3 o = old.get(0).pos();
                oldBake(pose, c, old, o, back);
                w.vc = d;
                HoleDecals.bakeHoles(w, now2, o.x, o.y, o.z, back);
                assertEquals(c.log, d.log, "baked" + (back ? " backs" : "") + ", seed " + seed);
                vertices += c.vertices;
            }

            for (int i = 0; i < n; i++)
                assertEquals(Double.doubleToRawLongBits(towardCamera(old.get(i), cam)),
                        Double.doubleToRawLongBits(HoleDecals.towardCamera(now2.get(i), cam.x, cam.y, cam.z)), "towardCamera, seed " + seed);
            holes += n;
        }
        assertTrue(vertices > 100_000, "the test drew too little: " + vertices + " vertices of " + holes + " holes");
    }
}
