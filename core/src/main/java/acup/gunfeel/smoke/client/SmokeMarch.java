package acup.gunfeel.smoke.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import acup.gunfeel.host.render.ParamsWriter;
import acup.gunfeel.host.render.RenderPort;
import acup.gunfeel.smoke.SmokeCloud;
import acup.gunfeel.smoke.SmokeGrid;

/**
 * A frame of the smoke's raymarch, what it marches: the drawn clouds' boxes, the groups of them marched as one smoke
 * (far to near), the part of the screen they can colour, and the march's parameters (SmokeInfo, smoke_info.glsl)
 * written into the version's params buffer. How the march is put into the frame (the depth copies, the vanilla chain's
 * layers, the passes) is the version's (26.2: SmokeRender).
 */
public final class SmokeMarch {
    /**
     * SmokeInfo's bytes: the matrix, four vec4 and the inverse of the projection times the view, then per cloud
     * ({@link SmokeAtlasSlots#SLOTS} of them) four vec4: origin, box min, box max, colour; then per group (as many at
     * most) two: its box's min and max. Clouds[] in smoke_info.glsl must be SLOTS * 4 long, Groups[] SLOTS * 2
     * (SmokeShaderLinkTest checks)
     */
    public static final int INFO_SIZE = 64 + 16 * 4 + 64 + SmokeAtlasSlots.SLOTS * 4 * 16 + SmokeAtlasSlots.SLOTS * 2 * 16;
    /** The noise is laid from a world point on a lattice this many blocks apart (the shader's PERIOD is 0.9 of it). */
    public static final int ANCHOR = 1000;
    /** Boxes this near each other are one group: the far to near order of two boxes side by side can be wrong. */
    static final double MEET = 1.0;

    /** What the version knows of the frame, for SmokeInfo: set before {@link #write}. */
    public static final class Frame {
        /** the level's view rotation (the camera at 0) */
        public Matrix4fc view;
        /** depth pixels per smoke pixel across and up */
        public float depthAcross, depthUp;
        /** depth goes 0..1 (else -1..1) */
        public boolean zeroToOne;
        /** the noise's time, the partial tick */
        public float seconds, partial;
        public int maxSteps;
        public float stepLength;
        /** the camera in a cloud's box: the march coarser, laid over the frame upscaled by depth */
        public boolean lowInside;
        /** the smoke in front of glass marched only where it ends before the glass (smokeEndsBefore) */
        public boolean front;
        /** the light let through at which a ray stops */
        public float through;
        public double camX, camY, camZ;
        /** the level's projection times the view, or null (nausea, a portal: the identity in its place) */
        public Matrix4fc projView;
        private int[][] groups;
        private List<SmokeAtlasSlots.Slot> order;
        private double[][] unions = new double[0][];
    }

    // SmokeInfo's two matrices, kept: a new one each frame chosen of two (new Matrix4f(pv).invert() : new Matrix4f())
    // and written by JOML's get(ByteBuffer) crashed the JDK 25's C2 compiling it (LoadNode::can_split_through_phi_base,
    // 25.0.1 and 25.0.4.1; SmokeRender's own lines before the port did it as well: render-step3.md, "C2")
    private static final Matrix4f VIEW = new Matrix4f(), INVERSE = new Matrix4f();

    private SmokeMarch() {
    }

    /**
     * SmokeInfo of the drawn clouds in view ({@code slots}) into a params buffer mapped for it; the groups' number back
     * (the march draws 36 vertices a group).
     */
    public static int write(RenderPort port, int params, Frame f, List<SmokeAtlasSlots.Slot> slots) {
        prepare(f, slots, f.camX, f.camY, f.camZ);
        return write(port, params, f);
    }

    /** The frame's geometry (groups, order, the groups' boxes), before the version reads its clocks; the boxes' array kept between frames. */
    public static void prepare(Frame f, List<SmokeAtlasSlots.Slot> slots, double cx, double cy, double cz) {
        // the clouds whose boxes meet go in one group, marched as one smoke; the groups far to near, each over the
        // ones behind it
        double[][] boxes = boxes(slots);
        f.groups = groups(boxes, cx, cy, cz);
        f.order = order(slots, f.groups);
        if (f.unions.length < f.groups.length) f.unions = new double[f.groups.length][];
        for (int i = 0; i < f.groups.length; i++) f.unions[i] = union(boxes, f.groups[i]);
    }

    /** SmokeInfo of a frame {@link #prepare}d, after the clocks are read; the groups' number back. */
    public static int write(RenderPort port, int params, Frame f) {
        ParamsWriter b = port.map(params, INFO_SIZE);
        try {
            info(b, f, f.unions, f.groups, f.order);
        } catch (Throwable t) {
            b.abort();
            throw t;
        }
        b.done();
        return f.groups.length;
    }

    private static double[][] boxes(List<SmokeAtlasSlots.Slot> slots) {
        double[][] boxes = new double[slots.size()][];
        for (int i = 0; i < boxes.length; i++) boxes[i] = worldBox(slots.get(i).cloud());
        return boxes;
    }

    private static List<SmokeAtlasSlots.Slot> order(List<SmokeAtlasSlots.Slot> slots, int[][] groups) {
        List<SmokeAtlasSlots.Slot> order = new ArrayList<>(slots.size());
        for (int[] g : groups) for (int i : g) order.add(slots.get(i));
        return order;
    }

    private static void info(ParamsWriter b, Frame f, double[][] unions, int[][] groups, List<SmokeAtlasSlots.Slot> order) {
        b.mat4(VIEW.set(f.view));
        b.vec4(f.depthAcross, f.zeroToOne ? 1F : 0F, f.seconds, f.maxSteps);
        b.vec4(1F / SmokeAtlasSlots.WIDTH, 1F / SmokeAtlasSlots.HEIGHT, f.partial, f.stepLength);
        b.vec4(groups.length, f.lowInside ? 1F : 0F, f.depthUp, f.front ? 1F : 0F);
        b.vec4((float) (Math.floor(f.camX / ANCHOR) * ANCHOR - f.camX), (float) (Math.floor(f.camY / ANCHOR) * ANCHOR - f.camY),
                (float) (Math.floor(f.camZ / ANCHOR) * ANCHOR - f.camZ), f.through);
        b.mat4(f.projView != null ? INVERSE.set(f.projView).invert() : INVERSE.identity());
        for (int i = 0; i < SmokeAtlasSlots.SLOTS; i++) {
            if (i >= order.size()) {
                for (int k = 0; k < 4; k++) b.vec4(0F, 0F, 0F, 0F);
                continue;
            }
            SmokeAtlasSlots.Slot s = order.get(i);
            SmokeCloud c = s.cloud();
            int[] cells = cells(c);
            b.vec4((float) (c.baseX() - f.camX), (float) (c.baseY() - f.camY), (float) (c.baseZ() - f.camZ), s.index());
            b.vec4(cells[0], cells[1], cells[2], c.kind().look());
            b.vec4(cells[3], cells[4], cells[5], 0F);
            int rgb = c.kind().rgb();
            b.vec4((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, 1F);
        }
        for (int i = 0, first = 0; i < SmokeAtlasSlots.SLOTS; i++) {
            if (i >= groups.length) {
                b.vec4(0F, 0F, 0F, 0F);
                b.vec4(0F, 0F, 0F, 0F);
                continue;
            }
            double[] u = unions[i];
            b.vec4((float) (u[0] - f.camX), (float) (u[1] - f.camY), (float) (u[2] - f.camZ), first);
            b.vec4((float) (u[3] - f.camX), (float) (u[4] - f.camY), (float) (u[5] - f.camZ), groups[i].length);
            first += groups[i].length;
        }
    }

    /** whether the camera is in one of the drawn clouds' boxes */
    public static boolean cameraInBox(List<SmokeAtlasSlots.Slot> slots, double cx, double cy, double cz) {
        for (SmokeAtlasSlots.Slot s : slots) {
            double[] b = worldBox(s.cloud());
            if (cx >= b[0] && cx <= b[3] && cy >= b[1] && cy <= b[4] && cz >= b[2] && cz <= b[5]) return true;
        }
        return false;
    }

    /**
     * The part of the screen the drawn clouds' boxes can colour: their corners through {@code pv}, widened by two of the
     * march's pixels and one more (its texels are filtered over their neighbours). x, y from the bottom left, width,
     * height in the screen's pixels; null: all of it, when a corner is at or behind the eye or the part is most of the
     * screen anyway.
     */
    public static int[] screenRect(List<SmokeAtlasSlots.Slot> slots, Matrix4f pv, double cx, double cy, double cz, int width, int height, int div) {
        float lx = Float.MAX_VALUE, ly = Float.MAX_VALUE, hx = -Float.MAX_VALUE, hy = -Float.MAX_VALUE;
        Vector4f v = new Vector4f();
        for (SmokeAtlasSlots.Slot s : slots) {
            double[] b = worldBox(s.cloud());
            for (int k = 0; k < 8; k++) {
                v.set((float) ((k & 1) == 0 ? b[0] - cx : b[3] - cx), (float) ((k & 2) == 0 ? b[1] - cy : b[4] - cy),
                        (float) ((k & 4) == 0 ? b[2] - cz : b[5] - cz), 1F);
                pv.transform(v);
                if (!(v.w > 0.05F)) return null;
                float x = v.x / v.w, y = v.y / v.w;
                lx = Math.min(lx, x);
                hx = Math.max(hx, x);
                ly = Math.min(ly, y);
                hy = Math.max(hy, y);
            }
        }
        int pad = 2 * div + 2;
        int x0 = clampPx((lx * 0.5F + 0.5F) * width - pad, width), x1 = clampPx((hx * 0.5F + 0.5F) * width + pad + 1, width);
        int y0 = clampPx((ly * 0.5F + 0.5F) * height - pad, height), y1 = clampPx((hy * 0.5F + 0.5F) * height + pad + 1, height);
        if (x1 <= x0 || y1 <= y0 || (long) (x1 - x0) * (y1 - y0) > (long) width * height * 9 / 10) return null;
        return new int[] {x0, y0, x1 - x0, y1 - y0};
    }

    private static int clampPx(float v, int size) {
        return (int) Math.max(0F, Math.min(size, Math.floor(v)));
    }

    /**
     * How far a copy of rect reaches from the bottom left corner. Always from 0, 0: the OpenGL backend (GlCommandEncoder)
     * hands copyTextureToTexture's width and height to glBlitNamedFramebuffer as the far corner, not the size, so a copy
     * from x, y took the part between (x, y) and (width, height) there and left rect itself as an earlier frame had it
     * (Vulkan copies the size). From 0 both read the same.
     */
    public static int[] copyCorner(int[] rect, int width, int height) {
        return rect == null ? new int[] {width, height} : new int[] {Math.min(width, rect[0] + rect[2]), Math.min(height, rect[1] + rect[3])};
    }

    // the cells the shader marches a cloud through: one around the occupied box, which the edge cells fade out into and
    // the cloud grows into; min x, y, z and max x, y, z in the grid's cells
    static int[] cells(SmokeCloud c) {
        int[] box = c.grid.box();
        return new int[] {Math.max(-1, box[0] - 1), Math.max(-1, box[1] - 1), Math.max(-1, box[2] - 1),
                Math.min(SmokeGrid.SX + 1, box[3] + 2), Math.min(SmokeGrid.SY + 1, box[4] + 2), Math.min(SmokeGrid.SZ + 1, box[5] + 2)};
    }

    /** the same in the world */
    public static double[] worldBox(SmokeCloud c) {
        int[] k = cells(c);
        return new double[] {c.baseX() + k[0], c.baseY() + k[1], c.baseZ() + k[2], c.baseX() + k[3], c.baseY() + k[4], c.baseZ() + k[5]};
    }

    /**
     * The boxes (world min x, y, z, max x, y, z) in groups: two whose boxes meet or come within {@link #MEET} of each
     * other are in one, and one that meets either of them too. The groups far to near (by their box's middle), each
     * one's boxes in the order given. A group is marched as one smoke, so where clouds overlap their smoke is one; the
     * groups lie apart and far to near is their right order.
     */
    static int[][] groups(double[][] boxes, double cx, double cy, double cz) {
        int n = boxes.length;
        int[] root = new int[n];
        for (int i = 0; i < n; i++) root[i] = i;
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) if (meet(boxes[i], boxes[j])) {
            int a = find(root, i), b = find(root, j);
            if (a != b) root[Math.max(a, b)] = Math.min(a, b);
        }
        List<List<Integer>> by = new ArrayList<>();
        int[] slot = new int[n];
        java.util.Arrays.fill(slot, -1);
        for (int i = 0; i < n; i++) {
            int r = find(root, i);
            if (slot[r] < 0) {
                slot[r] = by.size();
                by.add(new ArrayList<>());
            }
            by.get(slot[r]).add(i);
        }
        int[][] out = new int[by.size()][];
        for (int g = 0; g < out.length; g++) out[g] = by.get(g).stream().mapToInt(Integer::intValue).toArray();
        double[] far = new double[out.length];
        for (int g = 0; g < out.length; g++) {
            double[] u = union(boxes, out[g]);
            double x = (u[0] + u[3]) * 0.5 - cx, y = (u[1] + u[4]) * 0.5 - cy, z = (u[2] + u[5]) * 0.5 - cz;
            far[g] = x * x + y * y + z * z;
        }
        Integer[] idx = new Integer[out.length];
        for (int g = 0; g < idx.length; g++) idx[g] = g;
        java.util.Arrays.sort(idx, Comparator.comparingDouble((Integer g) -> -far[g]));
        int[][] sorted = new int[out.length][];
        for (int g = 0; g < idx.length; g++) sorted[g] = out[idx[g]];
        return sorted;
    }

    private static boolean meet(double[] a, double[] b) {
        for (int k = 0; k < 3; k++) if (a[k] > b[k + 3] + MEET || b[k] > a[k + 3] + MEET) return false;
        return true;
    }

    private static int find(int[] root, int i) {
        while (root[i] != i) i = root[i] = root[root[i]];
        return i;
    }

    static double[] union(double[][] boxes, int[] which) {
        double[] u = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i : which) for (int k = 0; k < 3; k++) {
            u[k] = Math.min(u[k], boxes[i][k]);
            u[k + 3] = Math.max(u[k + 3], boxes[i][k + 3]);
        }
        return u;
    }
}
