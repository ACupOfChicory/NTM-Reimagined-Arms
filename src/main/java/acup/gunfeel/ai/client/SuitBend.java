package acup.gunfeel.ai.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import com.hbm.render.loader.GroupObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * An NTM suit's own mesh (HEV, T-51 and the rest) bent at the bots' knee or elbow (BotModel): the limb's group is cut
 * once by the plane of the joint, the half above drawn as the thigh or the upper arm, the half below as the shin or the
 * forearm, and a band joins the two where they were cut, so the bend opens no gap. Bendy-lib (Emotecraft, Player
 * Animator) bends a box at its middle with the vertices there turned half way; Epic Fight skins armour by weights.
 * Here the halves stay rigid, each as the box under it, and only the band stretches: the halves go through NTM's own
 * drawing (and the hitbox's capture of it) as whole groups. No code of theirs here.
 *
 * <p>Built once per group and plane, so a frame draws two groups and the band's few quads.
 */
public final class SuitBend {
    private static final int STRIDE = GroupObject.STRIDE, QUAD = GroupObject.QUAD;
    /** a vertex this close to the plane is on it, px */
    static final float ON = 1e-4F;
    /**
     * The band's steps: two, turned half the bend each, so it goes round the outside of the joint (on NTM's suits at 90
     * degrees the skin's corner stands out of it 0.7 px on average, of one flat band 1.2, of the mesh not bent 3.7; four
     * steps are no better at 90 degrees, 0.1 px at 120; SuitBendTest)
     */
    static final int SEGMENTS = 2;
    /** off: the mesh rigid on the upper part, as NTM draws it (the stand's "before") */
    public static volatile boolean on = !Boolean.getBoolean("ntmgunfeel.suitBendOff");
    /** the stand's: the halves without the band, to weigh the band's part of the cost */
    public static volatile boolean band = !Boolean.getBoolean("ntmgunfeel.suitBendNoBand");

    /**
     * A group cut by the joint's plane: the faces above it (whole, or their part above), those below, and the seam:
     * each edge of an upper face that lies on the plane, its two ends ({@code A}, {@code B} as the upper face runs) of
     * {@link GroupObject#STRIDE} floats each. A half with no faces is null.
     */
    public record Cut(@Nullable GroupObject upper, @Nullable GroupObject lower, float[] seam) {
        public int seamEdges() {
            return seam.length / (2 * STRIDE);
        }
    }

    /** the cuts by group (NTM's meshes, dropped with them on a reload) and by plane and shading */
    private static final Map<GroupObject, Map<Integer, Cut>> CUTS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private SuitBend() {
    }

    /** the group cut at {@code y = plane} of its own frame (y down the limb), built once */
    public static Cut cut(GroupObject group, boolean smoothing, float plane) {
        Map<Integer, Cut> byPlane = CUTS.computeIfAbsent(group, g -> new java.util.concurrent.ConcurrentHashMap<>());
        return byPlane.computeIfAbsent(Math.round(plane * 1000F) * 2 + (smoothing ? 1 : 0), k -> cut(group.name, group.quads(smoothing), group.faceCount(), plane));
    }

    /** the faces ({@code quads}: four corners of {@link GroupObject#STRIDE} floats each, a triangle with its last corner twice) cut at y = plane */
    static Cut cut(String name, float[] quads, int faces, float plane) {
        FloatList up = new FloatList(), down = new FloatList(), upN = new FloatList(), downN = new FloatList(), seam = new FloatList();
        float[][] poly = new float[4][];
        for (int f = 0; f < faces; f++) {
            int base = f * QUAD;
            int n = degenerate(quads, base) ? 3 : 4;
            float[] normal = faceNormal(quads, base);
            int above = 0, below = 0;
            for (int c = 0; c < n; c++) {
                poly[c] = java.util.Arrays.copyOfRange(quads, base + c * STRIDE, base + (c + 1) * STRIDE);
                float d = poly[c][1] - plane;
                if (d < -ON) above++;
                else if (d > ON) below++;
            }
            if (above == 0 && below == 0) {
                // in the plane: the end of the half its face looks out of (down the limb: the upper half's end)
                List<float[]> all = List.of(java.util.Arrays.copyOf(poly, n));
                if (normal[1] > 0) add(up, upN, all, normal);
                else add(down, downN, all, normal);
                continue;
            }
            List<float[]> top = new ArrayList<>(5), bottom = new ArrayList<>(5);
            for (int c = 0; c < n; c++) {
                float[] a = poly[c], b = poly[(c + 1) % n];
                float da = side(a[1] - plane), db = side(b[1] - plane);
                if (da <= 0) top.add(da == 0 ? onPlane(a, plane) : a);
                if (da >= 0) bottom.add(da == 0 ? onPlane(a, plane) : a);
                if (da * db < 0) {
                    float[] x = cross(a, b, plane);
                    top.add(x);
                    bottom.add(x);
                }
            }
            if (above > 0) {
                add(up, upN, top, normal);
                for (int c = 0; c < top.size(); c++) {
                    float[] a = top.get(c), b = top.get((c + 1) % top.size());
                    if (Math.abs(a[1] - plane) <= ON && Math.abs(b[1] - plane) <= ON && !same(a, b)) {
                        seam.add(a);
                        seam.add(b);
                    }
                }
            }
            if (below > 0) add(down, downN, bottom, normal);
        }
        return new Cut(group(name, up, upN), group(name, down, downN), seam.toArray());
    }

    private static float side(float d) {
        return d < -ON ? -1 : d > ON ? 1 : 0;
    }

    private static float[] onPlane(float[] v, float plane) {
        float[] out = v.clone();
        out[1] = plane;
        return out;
    }

    /** the point of a -> b on the plane, its texture and normal in between */
    private static float[] cross(float[] a, float[] b, float plane) {
        float t = (plane - a[1]) / (b[1] - a[1]);
        float[] out = new float[STRIDE];
        for (int i = 0; i < STRIDE; i++) out[i] = a[i] + (b[i] - a[i]) * t;
        out[1] = plane;
        float len = (float) Math.sqrt(out[5] * out[5] + out[6] * out[6] + out[7] * out[7]);
        if (len > 1e-6F) for (int i = 5; i < 8; i++) out[i] /= len;
        return out;
    }

    private static boolean same(float[] a, float[] b) {
        return Math.abs(a[0] - b[0]) <= ON && Math.abs(a[2] - b[2]) <= ON;
    }

    private static boolean degenerate(float[] q, int face) {
        for (int i = 0; i < 5; i++) if (q[face + 16 + i] != q[face + 24 + i]) return false;
        return true;
    }

    private static float[] faceNormal(float[] q, int base) {
        float[] out = new float[3];
        float ax = q[base + STRIDE] - q[base], ay = q[base + STRIDE + 1] - q[base + 1], az = q[base + STRIDE + 2] - q[base + 2];
        float bx = q[base + 2 * STRIDE] - q[base], by = q[base + 2 * STRIDE + 1] - q[base + 1], bz = q[base + 2 * STRIDE + 2] - q[base + 2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6F) len = 1F;
        out[0] = nx / len;
        out[1] = ny / len;
        out[2] = nz / len;
        return out;
    }

    /** a polygon of 3 to 5 corners as quads: 0123 and 0344 for five, a triangle with its last corner twice */
    private static void add(FloatList quads, FloatList normals, List<float[]> poly, float[] normal) {
        int n = poly.size();
        if (n < 3) return;
        int[][] split = n == 3 ? new int[][] { { 0, 1, 2, 2 } } : n == 4 ? new int[][] { { 0, 1, 2, 3 } } : new int[][] { { 0, 1, 2, 3 }, { 0, 3, 4, 4 } };
        for (int[] q : split) {
            for (int c : q) quads.add(poly.get(c));
            normals.add(normal);
        }
    }

    private static @Nullable GroupObject group(String name, FloatList quads, FloatList normals) {
        return quads.size() == 0 ? null : new GroupObject(name, quads.toArray(), normals.toArray());
    }

    // ---------------------------------------------------------------- the joint

    /** a split limb's joint in its mesh's frame: the plane (y) cut at, the lower part's turn there and its pivot */
    public record Bent(float plane, Matrix4f turn, Vector3f pivot) {
    }

    /**
     * The joint of a split limb (BotModel) as its mesh is drawn: {@code parent} the pose the limb hangs from, {@code
     * mesh} the mesh's pose on it. Null for a limb not split, or a mesh not standing along the limb.
     */
    public static @Nullable Bent bent(Matrix4fc parent, net.minecraft.client.model.geom.ModelPart limb, Matrix4fc mesh) {
        net.minecraft.client.model.geom.ModelPart lower = BotModel.lower(limb);
        if (lower == null) return null;
        PoseStack own = new PoseStack();
        limb.translateAndRotate(own);
        Matrix4f limbPose = new Matrix4f(parent).mul(own.last().pose());
        // the mesh's frame in the limb's: NTM's fit, a move and a scale
        Matrix4f fit = limbPose.invert(new Matrix4f()).mul(mesh);
        Matrix4f unfit = fit.invert(new Matrix4f());
        Vector3f along = unfit.transformDirection(new Vector3f(0F, 1F, 0F)).normalize();
        if (!(along.y > 0.999F)) return null;
        PoseStack child = new PoseStack();
        lower.translateAndRotate(child);
        // the lower part's move from where it hangs straight (its initial pose) to where it is now
        var rest = lower.getInitialPose();
        Matrix4f straight = new Matrix4f().translation(rest.x() / 16F, rest.y() / 16F, rest.z() / 16F)
                .rotate(new Quaternionf().rotationZYX(rest.zRot(), rest.yRot(), rest.xRot())).scale(rest.xScale(), rest.yScale(), rest.zScale());
        Matrix4f turn = new Matrix4f(unfit).mul(child.last().pose()).mul(straight.invert()).mul(fit);
        // the cut where the joint is straight: a clip that moves the part does not move the cut (nor make a new one)
        Vector3f pivot = unfit.transformPosition(new Vector3f(rest.x() / 16F, rest.y() / 16F, rest.z() / 16F));
        return new Bent(pivot.y, turn, pivot);
    }

    // ---------------------------------------------------------------- drawing

    /**
     * The cut group drawn: its upper half in {@code pose}, the lower one in {@code pose} times {@code bend} (the lower
     * part's turn on the joint, in the group's frame, about {@code pivot}), and the band between them.
     */
    public static void draw(Cut cut, PoseStack.Pose pose, VertexConsumer buffer, int light, int color, boolean smoothing, Matrix4fc bend,
            Vector3fc pivot) {
        if (cut.upper() != null) cut.upper().emit(pose, buffer, light, color, smoothing);
        PoseStack lower = new PoseStack();
        lower.last().set(pose);
        lower.mulPose(new Matrix4f(bend));
        if (cut.lower() != null) cut.lower().emit(lower.last(), buffer, light, color, smoothing);
        if (!band || cut.seamEdges() == 0) return;
        float[] seam = cut.seam();
        int edges = 0;
        Vector3f p = new Vector3f(), n = new Vector3f();
        for (int e = 0; e < seam.length; e += 2 * STRIDE) if (opens(bend, seam, e, p)) edges++;
        if (edges == 0) return;
        PoseStack.Pose[] rings = new PoseStack.Pose[SEGMENTS + 1];
        rings[0] = pose;
        rings[SEGMENTS] = lower.last();
        for (int i = 1; i < SEGMENTS; i++) {
            PoseStack s = new PoseStack();
            s.last().set(pose);
            s.mulPose(partway(bend, pivot, (float) i / SEGMENTS));
            rings[i] = s.last();
        }
        // NTM's own block of vertices when the buffer has it
        com.hbm.interfaces.injected.IBufferBuilderExtension block = buffer instanceof com.hbm.interfaces.injected.IBufferBuilderExtension fast
                && fast.hbm$beginBlock(edges * SEGMENTS * 4) ? fast : null;
        for (int e = 0; e < seam.length; e += 2 * STRIDE) {
            if (!opens(bend, seam, e, p)) continue;
            int a = e, b = e + STRIDE;
            for (int r = 0; r < SEGMENTS; r++) {
                // the band's face runs against the upper face's edge a -> b: b, a above, a, b below
                vertex(buffer, block, rings[r], seam, b, color, light, p, n);
                vertex(buffer, block, rings[r], seam, a, color, light, p, n);
                vertex(buffer, block, rings[r + 1], seam, a, color, light, p, n);
                vertex(buffer, block, rings[r + 1], seam, b, color, light, p, n);
            }
        }
        if (block != null) block.hbm$endBlock();
    }

    private static void vertex(VertexConsumer buffer, com.hbm.interfaces.injected.@Nullable IBufferBuilderExtension block, PoseStack.Pose pose, float[] d, int i,
            int color, int light, Vector3f p, Vector3f n) {
        pose.pose().transformPosition(d[i], d[i + 1], d[i + 2], p);
        pose.transformNormal(d[i + 5], d[i + 6], d[i + 7], n);
        if (block != null) block.hbm$blockVertex(p.x, p.y, p.z, color, d[i + 3], d[i + 4], OverlayTexture.NO_OVERLAY, light, n.x, n.y, n.z);
        else buffer.addVertex(p.x, p.y, p.z, color, d[i + 3], d[i + 4], OverlayTexture.NO_OVERLAY, light, n.x, n.y, n.z);
    }

    /**
     * Whether the bend opens the seam's edge at {@code e}: an end of it moves (down the limb, or aside, as a shin
     * turned on its own line moves it), and not both up into the upper half, where the halves run into each other and
     * cover the cut. No band is drawn where the bend leaves the edge shut.
     */
    static boolean opens(Matrix4fc bend, float[] seam, int e, Vector3f scratch) {
        float plane = seam[e + 1];
        boolean moved = false, up = true;
        for (int i = e; i <= e + STRIDE; i += STRIDE) {
            bend.transformPosition(seam[i], seam[i + 1], seam[i + 2], scratch);
            moved |= scratch.distanceSquared(seam[i], seam[i + 1], seam[i + 2]) > ON * ON;
            up &= scratch.y < plane - ON;
        }
        return moved && !up;
    }

    /**
     * A rigid matrix that turns about {@code pivot} taken {@code t} of the way from none: the turn slerped about the
     * pivot, and what it moves the pivot by (none for a joint's hinge) t of the way
     */
    static Matrix4f partway(Matrix4fc m, Vector3fc pivot, float t) {
        Quaternionf q = new Quaternionf().slerp(m.getNormalizedRotation(new Quaternionf()), t);
        Vector3f drift = m.transformPosition(new Vector3f(pivot)).sub(pivot).mul(t);
        return new Matrix4f().translation(new Vector3f(pivot).add(drift)).rotate(q).translate(new Vector3f(pivot).negate());
    }

    // ---------------------------------------------------------------- the stand's record

    /** the stand's record of each split limb's mesh as drawn (SuitBendStand), by NTM's bone; null when not recording */
    public static volatile java.util.@Nullable Map<String, Drawn> capture;

    /**
     * A limb's mesh in the world as drawn this frame, triangles of 9 numbers: bent (the halves and the band) and rigid
     * (the whole mesh on the upper part, as NTM draws it); the joint, the hinge's line across the limb, the upper part's
     * way up and the lower part's way down.
     */
    public record Drawn(List<double[]> bent, List<double[]> rigid, org.joml.Vector3d pivot, Vector3f across, Vector3f up, Vector3f down) {
    }

    /** the group as {@link #draw} puts it, and as NTM would, into {@link #capture} */
    public static void record(String bone, GroupObject group, boolean smoothing, Cut cut, PoseStack.Pose pose, Bent bent,
            net.minecraft.world.phys.Vec3 camera) {
        var into = capture;
        if (into == null) return;
        Matrix4f upper = new Matrix4f(pose.pose()), lower = new Matrix4f(pose.pose()).mul(bent.turn());
        Vector3f at = upper.transformPosition(new Vector3f(bent.pivot()));
        Drawn d = into.computeIfAbsent(bone, b -> new Drawn(new ArrayList<>(), new ArrayList<>(),
                new org.joml.Vector3d(at.x + camera.x, at.y + camera.y, at.z + camera.z), upper.transformDirection(new Vector3f(1, 0, 0)).normalize(),
                upper.transformDirection(new Vector3f(0, -1, 0)).normalize(), lower.transformDirection(new Vector3f(0, 1, 0)).normalize()));
        quads(d.rigid(), group.quads(smoothing), upper, camera);
        if (cut.upper() != null) quads(d.bent(), cut.upper().quads(smoothing), upper, camera);
        if (cut.lower() != null) quads(d.bent(), cut.lower().quads(smoothing), lower, camera);
        float[] seam = cut.seam();
        Matrix4f[] rings = new Matrix4f[SEGMENTS + 1];
        for (int i = 0; i <= SEGMENTS; i++) rings[i] = i == 0 ? upper : i == SEGMENTS ? lower : new Matrix4f(pose.pose()).mul(partway(bent.turn(), bent.pivot(), (float) i / SEGMENTS));
        Vector3f scratch = new Vector3f();
        for (int e = 0; e < seam.length; e += 2 * STRIDE)
            for (int r = 0; r < SEGMENTS && (band && opens(bent.turn(), seam, e, scratch)); r++) {
                double[] b0 = world(rings[r], seam, e + STRIDE, camera), a0 = world(rings[r], seam, e, camera),
                        a1 = world(rings[r + 1], seam, e, camera), b1 = world(rings[r + 1], seam, e + STRIDE, camera);
                d.bent().add(tri(b0, a0, a1));
                d.bent().add(tri(b0, a1, b1));
            }
    }

    private static void quads(List<double[]> out, float[] q, Matrix4f m, net.minecraft.world.phys.Vec3 camera) {
        for (int f = 0; f < q.length; f += QUAD) {
            double[] a = world(m, q, f, camera), b = world(m, q, f + STRIDE, camera), c = world(m, q, f + 2 * STRIDE, camera), d = world(m, q, f + 3 * STRIDE, camera);
            out.add(tri(a, b, c));
            out.add(tri(a, c, d));
        }
    }

    private static double[] world(Matrix4f m, float[] d, int i, net.minecraft.world.phys.Vec3 camera) {
        Vector3f p = m.transformPosition(d[i], d[i + 1], d[i + 2], new Vector3f());
        return new double[] { p.x + camera.x, p.y + camera.y, p.z + camera.z };
    }

    private static double[] tri(double[] a, double[] b, double[] c) {
        return new double[] { a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2] };
    }

    /** a growing list of floats */
    private static final class FloatList {
        private float[] data = new float[256];
        private int size;

        void add(float[] v) {
            if (size + v.length > data.length) data = java.util.Arrays.copyOf(data, Math.max(data.length * 2, size + v.length));
            System.arraycopy(v, 0, data, size, v.length);
            size += v.length;
        }

        int size() {
            return size;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }
}
