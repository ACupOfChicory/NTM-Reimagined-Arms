package acup.gunfeel;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.ToLongFunction;

// The flat bullet hole on a block face, without Minecraft: its texture square (turn, mirror, variant), the part of the
// face it may cover, the cells of the face that show the block's pixels, its four corners and how it fades.
// From 26.2's Tracers (0762086): axes, turned, holeUv, scratchTurn, mask, cells, faceRect, quad / quadAt and the life
// loop of holes(), the same numbers line by line. Only the world queries went behind interfaces (Pixels, Boxes).
// port: faces are 1.7.10's side numbers 0..5 = down, up, north, south, west, east, the order of 26.2's Direction;
// points are double[3] instead of Vec3, block positions int[3] instead of BlockPos.
final class HoleMath {
    private HoleMath() {
    }

    // a scratch lies along the texture's u at any angle (turn = SCRATCH_TURN + degrees); a mark's variant (its cell
    // on its style's texture, Decals.cell) rides in the turn's bits from VARIANT_SHIFT up
    static final int SCRATCH_TURN = 1000, VARIANT_SHIFT = 11, TURN_MASK = (1 << VARIANT_SHIFT) - 1;

    // per face: its axis (0 x, 1 y, 2 z, as 26.2's Direction.Axis ordinal) and which way it points
    private static final int[] AXIS = {1, 1, 2, 2, 0, 0};

    static int axis(int face) {
        return AXIS[face];
    }

    static int step(int face) {
        return (face & 1) != 0 ? 1 : -1;
    }

    // the step from a block to its neighbour on that face
    static int[] offset(int face) {
        int[] o = new int[3];
        o[axis(face)] = step(face);
        return o;
    }

    // the face of that axis pointing that way
    static int face(int axis, int step) {
        int base = axis == 1 ? 0 : axis == 2 ? 2 : 4;
        return base + (step > 0 ? 1 : 0);
    }

    // the face's two axes: u, v as world axis indices (0 x, 1 y, 2 z)
    static int[] axes(int face) {
        switch (axis(face)) {
            case 0: return new int[] {2, 1};
            case 1: return new int[] {0, 2};
            default: return new int[] {0, 1};
        }
    }

    // a new mark's turn with the variant `random` (0..1) picks in it
    static int withVariant(int turn, double random) {
        return turn | Decals.variant(random) << VARIANT_SHIFT;
    }

    // a scratch's turn: the angle that lays its texture's forward (u) along the bullet's way `dir` on that face
    static int scratchTurn(double[] dir, int face) {
        int[] ax = axes(face);
        int deg = (int) Math.round(Math.toDegrees(Math.atan2(dir[ax[1]], dir[ax[0]])));
        return SCRATCH_TURN + Math.floorMod(deg, 360);
    }

    // where (a, b) on a hole's square (0..1 each) falls on its texture: turned / mirrored by `turn` (0..7), or a
    // scratch's turned by its angle and shrunk by 1/sqrt 2 about the centre, so the square's corners stay on it;
    // then in its variant's cell (the turn's bits from VARIANT_SHIFT). v grows up, as the callers' 1 - v expects
    static float[] holeUv(int turnAndVariant, float a, float b) {
        float[] uv = turned(turnAndVariant & TURN_MASK, a, b);
        float[] c = Decals.cell(turnAndVariant >>> VARIANT_SHIFT, uv[0], 1F - uv[1]);
        return new float[] {c[0], 1F - c[1]};
    }

    static float[] turned(int turn, float a, float b) {
        if (turn >= SCRATCH_TURN) {
            double r = Math.toRadians(turn - SCRATCH_TURN), c = Math.cos(r), sn = Math.sin(r);
            double da = (a - 0.5) * 0.7071, db = (b - 0.5) * 0.7071;
            return new float[] {(float) (0.5 + da * c + db * sn), (float) (0.5 - da * sn + db * c)};
        }
        if ((turn & 4) != 0) a = 1F - a;
        switch (turn & 3) {
            case 1: return new float[] {b, 1F - a};
            case 2: return new float[] {1F - a, 1F - b};
            case 3: return new float[] {1F - b, a};
            default: return new float[] {a, b};
        }
    }

    // ---- life

    // a hole's alpha `age` after it was made, of holes kept `life`: full until the last three seconds, then down to 0
    static int alpha(long ageNanos, long lifeNanos) {
        double age = ageNanos / 1e9;
        double fadeFrom = Math.max(0.0, lifeNanos / 1e9 - 3.0);
        return (int) (255 * Math.min(1.0, Math.max(0.0, 1.0 - (age - fadeFrom) / 3.0)));
    }

    // holeLife (seconds, at least 0.1) in nanoseconds
    static long lifeNanos(float holeLife) {
        return (long) (Math.max(0.1F, holeLife) * 1e9);
    }

    // drop the holes older than `life`; holes in the order they were made, so the oldest are first
    static <T> int expire(Deque<T> holes, long now, long life, ToLongFunction<T> born) {
        int n = 0;
        while (!holes.isEmpty() && now - born.applyAsLong(holes.peekFirst()) > life) {
            holes.removeFirst();
            n++;
        }
        return n;
    }

    // ---- where on the face

    // Which 1/16-block cells of the face show the block's pixels: cells w x h from cell (u0, v0) on the face's two
    // axes, in 1/16 blocks; plane = where on the face's axis the hole lies (NaN: on the hit point)
    static final class Mask {
        final int u0, v0, w, h;
        final boolean[] solid;
        final double plane;

        Mask(int u0, int v0, int w, int h, boolean[] solid, double plane) {
            this.u0 = u0;
            this.v0 = v0;
            this.w = w;
            this.h = h;
            this.solid = solid;
            this.plane = plane;
        }
    }

    static final Mask NO_PIXELS = new Mask(0, 0, 0, 0, new boolean[0], Double.NaN);

    // what the world shows at a point of a face
    interface Pixels {
        // the hole lies on the hitbox there, whatever the pixels (a "hole ... hitbox" rule: glass)
        boolean hitboxOnly(int x, int y, int z);

        // where along the face's axis the block's model shows a pixel at (lu, lv) of the block (0..1 on the face's two
        // axes), from the block's corner (0..1); NaN = nowhere. Only the model's face nearest to the hit side counts
        double shows(int x, int y, int z, int face, double lu, double lv);
    }

    // the cells of the face around the hit that show the block's pixels (null = all of them), or NO_PIXELS when none
    // does (26.2: Tracers.mask, then "all solid" as null)
    static Mask mask(double[] hit, int face, int[] block, float half, double[] rect, Pixels pixels) {
        int[] ax = axes(face);
        int normal = axis(face);
        double pu = hit[ax[0]], pv = hit[ax[1]];
        double u0 = Math.max(pu - half, rect[0]), u1 = Math.min(pu + half, rect[1]);
        double v0 = Math.max(pv - half, rect[2]), v1 = Math.min(pv + half, rect[3]);
        if (u0 >= u1 || v0 >= v1) return NO_PIXELS;
        int i0 = (int) Math.floor(u0 * 16), i1 = (int) Math.ceil(u1 * 16), j0 = (int) Math.floor(v0 * 16), j1 = (int) Math.ceil(v1 * 16);
        int w = i1 - i0, h = j1 - j0;
        boolean[] solid = new boolean[w * h];
        boolean any = false;
        // the hole lies on the shown pixels nearest to the side it was hit from
        double plane = Double.NaN;
        int step = step(face);
        int depth = block[normal];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                double cu = (i0 + i + 0.5) / 16.0, cv = (j0 + j + 0.5) / 16.0;
                int[] at = new int[3];
                at[ax[0]] = (int) Math.floor(cu);
                at[ax[1]] = (int) Math.floor(cv);
                at[normal] = depth;
                boolean s;
                if (pixels.hitboxOnly(at[0], at[1], at[2])) {
                    s = true;
                } else {
                    double d = pixels.shows(at[0], at[1], at[2], face, cu - at[ax[0]], cv - at[ax[1]]);
                    s = !Double.isNaN(d);
                    if (s && (Double.isNaN(plane) || (d + depth - plane) * step > 0)) plane = d + depth;
                }
                solid[j * w + i] = s;
                any |= s;
            }
        }
        if (!any) return NO_PIXELS;
        // never in front of the hit point (the hitbox's face)
        if (!Double.isNaN(plane) && (plane - hit[normal]) * step > 0) plane = hit[normal];
        boolean all = true;
        for (boolean b : solid) all &= b;
        return new Mask(i0, j0, w, h, all ? null : solid, plane);
    }

    // the parts of the face a flat hole covers: its rect, or the runs of shown cells of its mask, row by row
    static List<double[]> cells(double[] rect, Mask m) {
        List<double[]> out = new ArrayList<double[]>();
        if (m == null || m.solid == null) {
            out.add(rect);
            return out;
        }
        for (int j = 0; j < m.h; j++) {
            for (int i = 0; i < m.w; i++) {
                if (!m.solid[j * m.w + i]) continue;
                int e = i;
                while (e + 1 < m.w && m.solid[j * m.w + e + 1]) e++;
                out.add(new double[] {Math.max(rect[0], (m.u0 + i) / 16.0), Math.min(rect[1], (m.u0 + e + 1) / 16.0),
                        Math.max(rect[2], (m.v0 + j) / 16.0), Math.min(rect[3], (m.v0 + j + 1) / 16.0)});
                i = e;
            }
        }
        return out;
    }

    // port: where a point of a plain cube's face falls on its icon, in the icon's pixels 0..16 {u, v}, as 1.7.10's
    // RenderBlocks.renderFace* lays the icon on with no rotation (render type 0). local = the point from the block's
    // corner, bounds = the block's bounds {minX, minY, minZ, maxX, maxY, maxZ} from its corner. North and east faces
    // mirror u within the bounds (u at minX is the icon's maxX), as RenderBlocks does.
    static double[] faceUv(int face, double[] local, double[] bounds) {
        double x = local[0], y = local[1], z = local[2];
        switch (face) {
            case 0:
            case 1: return new double[] {16 * x, 16 * z};
            case 2: return new double[] {16 * (bounds[0] + bounds[3] - x), 16 * (1 - y)};
            case 3: return new double[] {16 * x, 16 * (1 - y)};
            case 4: return new double[] {16 * z, 16 * (1 - y)};
            default: return new double[] {16 * (bounds[2] + bounds[5] - z), 16 * (1 - y)};
        }
    }

    // the blocks' hitboxes
    interface Boxes {
        // the block's collision box in the world {minX, minY, minZ, maxX, maxY, maxZ}, null = none
        double[] box(int x, int y, int z);

        // its collision box is the whole block
        boolean fullCube(int x, int y, int z);
    }

    // The part of the face a hole may cover, {uMin, uMax, vMin, vMax} on the face's two axes: the hit block's hitbox
    // face, carried over onto a neighbour in the same wall (its hitbox has a face in the same plane, not covered by a
    // block in front), so a mark on the seam of two blocks is not cut there.
    static double[] faceRect(int[] pos, int face, Boxes boxes) {
        int[] ax = axes(face);
        int normal = axis(face);
        boolean positive = step(face) > 0;
        double[] box = bounds(boxes, pos[0], pos[1], pos[2]);
        double plane = positive ? box[3 + normal] : box[normal];
        double[] r = {box[ax[0]], box[3 + ax[0]], box[ax[1]], box[3 + ax[1]]};
        for (int d = 0; d < 6; d++) {
            if (axis(d) == normal) continue;
            int axis = axis(d);
            boolean up = step(d) > 0;
            int slot = (axis == ax[0] ? 0 : 2) + (up ? 1 : 0);
            // only where this block's face reaches its side of the block
            double edge = up ? Math.ceil(r[slot] - 1e-4) : Math.floor(r[slot] + 1e-4);
            if (Math.abs(r[slot] - edge) > 1e-4) continue;
            int[] n = pos.clone();
            n[axis] += up ? 1 : -1;
            double[] nb = boxes.box(n[0], n[1], n[2]);
            if (nb == null) continue;
            double np = positive ? nb[3 + normal] : nb[normal];
            if (Math.abs(np - plane) > 1e-4) continue;
            int[] front = n.clone();
            front[normal] += positive ? 1 : -1;
            if (boxes.fullCube(front[0], front[1], front[2])) continue;
            r[slot] = up ? nb[3 + axis] : nb[axis];
        }
        return r;
    }

    private static double[] bounds(Boxes boxes, int x, int y, int z) {
        double[] b = boxes.box(x, y, z);
        return b != null ? b : new double[] {x, y, z, x + 1, y + 1, z + 1};
    }

    // ---- drawing

    // The hole as a square on the face, cut to `clip` (one of cells()), turned and mirrored by its turn: four corners
    // {x, y, z (from the camera), u, v, nx, ny, nz}, counter-clockwise seen from the side it is lifted to; null when
    // the square misses the clip. A two-sided hole (cull false: iron bars) lies on the camera's side of its plane.
    // half = half the hole's size; plane = where on the face's axis (NaN: at pos).
    static float[][] quad(double[] pos, int face, double plane, boolean cull, int turn, float half, double[] clip, double[] cam) {
        int normalAxis = axis(face);
        double base = Double.isNaN(plane) ? pos[normalAxis] : plane;
        // lifted off the face towards the camera: a two-sided hole seen from behind would otherwise lie behind the
        // block's own pixels and show only where the depth bias wins, cut off along a straight line
        int side = step(face);
        if (!cull) {
            double d = cam[normalAxis] - base;
            if (d != 0) side = d > 0 ? 1 : -1;
        }
        return quadAt(pos, face, base, turn, half, clip, side, false, side != step(face), cam);
    }

    // the hole's square lifted to `side` of its face; reverse = wound the other way, flipNormal = its normal turned
    static float[][] quadAt(double[] pos, int face, double base, int turn, float size, double[] clip, int side, boolean reverse,
            boolean flipNormal, double[] cam) {
        int[] ax = axes(face);
        double pu = pos[ax[0]], pv = pos[ax[1]];
        double u0 = Math.max(pu - size, clip[0]), u1 = Math.min(pu + size, clip[1]);
        double v0 = Math.max(pv - size, clip[2]), v1 = Math.min(pv + size, clip[3]);
        if (u0 >= u1 || v0 >= v1) return null;
        int normalAxis = axis(face);
        double planeAt = base + 0.002 * side;
        float[] n = new float[3];
        n[normalAxis] = step(face) * (flipNormal ? -1 : 1);
        double[][] corners = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
        // counter-clockwise seen from outside the face: the face's u, v axes turn the other way on east / west and
        // up / down faces
        boolean flip = (normalAxis == 2 ? 1 : -1) * step(face) < 0;
        float[][] out = new float[4][];
        for (int j = 0; j < 4; j++) {
            double[] c = corners[flip != reverse ? 3 - j : j];
            // where this corner falls on the hole's texture, 0..1, then turned / mirrored
            float[] uv = holeUv(turn, (float) ((c[0] - (pu - size)) / (2 * size)), (float) ((c[1] - (pv - size)) / (2 * size)));
            double[] w = new double[3];
            w[ax[0]] = c[0];
            w[ax[1]] = c[1];
            w[normalAxis] = planeAt;
            out[j] = new float[] {(float) (w[0] - cam[0]), (float) (w[1] - cam[1]), (float) (w[2] - cam[2]), uv[0], 1F - uv[1], n[0], n[1], n[2]};
        }
        return out;
    }
}
