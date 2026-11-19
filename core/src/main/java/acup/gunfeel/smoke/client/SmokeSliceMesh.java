package acup.gunfeel.smoke.client;

import acup.gunfeel.smoke.SmokeGrid;

/**
 * The geometry of the smoke drawn as slices (research, technique B): a cloud is a stack of translucent quads half a
 * block apart across the axis nearest to the view, one quad a cell, its corners as opaque as the smoke there. This is
 * the numbers only, the corners and their colours; the version's adapter writes them into its own buffer and keeps
 * them while they fit (26.2: {@code SmokeSlices}).
 */
public final class SmokeSliceMesh {
    private static final float STEP = 0.5F;
    // a corner this transparent counts as empty; a quad of four such corners is left out
    private static final float EMPTY = 0.004F;
    // another axis takes over only when its share of the view direction is this much larger
    private static final double HOLD = 1.15;

    private SmokeSliceMesh() {
    }

    /**
     * One cloud's quads: the corners relative to the cloud's cell (0, 0, 0), x, y, z each, four a quad, and their
     * colours (ARGB); {@code count} quads.
     */
    public record Mesh(float[] corners, int[] colours, int count) {
    }

    /**
     * The axis (0 x, 1 y, 2 z) nearest to the view of the cloud whose cell (0, 0, 0) is at world block {@code base}:
     * from the camera to its middle, or where the camera looks ({@code look}) when it is in the box; the one it had
     * stays unless another is clearly nearer (a view along a diagonal would otherwise flip the axis and rebuild the
     * cloud every frame). {@code had} -1: none yet.
     */
    public static int axis(SmokeGrid g, int baseX, int baseY, int baseZ, double camX, double camY, double camZ,
            double lookX, double lookY, double lookZ, int had) {
        int[] b = g.box();
        double minX = baseX + b[0], minY = baseY + b[1], minZ = baseZ + b[2];
        double maxX = baseX + b[3] + 1, maxY = baseY + b[4] + 1, maxZ = baseZ + b[5] + 1;
        double dx, dy, dz;
        if (camX >= minX - 1 && camX <= maxX + 1 && camY >= minY - 1 && camY <= maxY + 1 && camZ >= minZ - 1 && camZ <= maxZ + 1) {
            dx = lookX;
            dy = lookY;
            dz = lookZ;
        } else {
            dx = (minX + maxX) / 2 - camX;
            dy = (minY + maxY) / 2 - camY;
            dz = (minZ + maxZ) / 2 - camZ;
        }
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        int best = ax >= ay && ax >= az ? 0 : ay >= az ? 1 : 2;
        if (had < 0 || had == best) return best;
        double[] share = {ax, ay, az};
        return share[had] * HOLD >= share[best] ? had : best;
    }

    /** The quads of a grid sliced across {@code axis}, in its kind's opacity and this colour (0xRRGGBB, lit). */
    public static Mesh build(SmokeGrid g, int axis, int colour) {
        int[] b = g.box();
        // u, v: the two other axes; corners at the cell centres, one cell out around the box so the edge fades
        int ua = axis == 0 ? 1 : 0, va = axis == 2 ? 1 : 2;
        int[] lo = {b[0] - 1, b[1] - 1, b[2] - 1}, hi = {b[3] + 1, b[4] + 1, b[5] + 1};
        int nu = hi[ua] - lo[ua] + 1, nv = hi[va] - lo[va] + 1;
        float ext = g.kind().look();
        float from = b[axis];
        float to = b[axis + 3] + 1;
        int slices = Math.max(1, Math.round((to - from) / STEP));
        float[] alpha = new float[nu * nv];
        float[] out = new float[256 * 12];
        int[] col = new int[256 * 4];
        int count = 0;
        int[] at = new int[3];
        for (int s = 0; s < slices; s++) {
            float w = from + (s + 0.5F) * STEP;
            // the slice between the cell layers k and k + 1 (centres at k + 0.5)
            float sc = w - 0.5F;
            int k = (int) Math.floor(sc);
            float f = sc - k;
            for (int iu = 0; iu < nu; iu++)
                for (int iv = 0; iv < nv; iv++) {
                    at[ua] = lo[ua] + iu;
                    at[va] = lo[va] + iv;
                    at[axis] = k;
                    boolean c0 = g.closed(at[0], at[1], at[2]);
                    float d0 = g.density(at[0], at[1], at[2]);
                    at[axis] = k + 1;
                    boolean c1 = g.closed(at[0], at[1], at[2]);
                    float d1 = g.density(at[0], at[1], at[2]);
                    float d = c0 && c1 ? 0F : c0 ? d1 : c1 ? d0 : d0 + (d1 - d0) * f;
                    alpha[iu * nv + iv] = 1F - (float) Math.exp(-ext * d * STEP);
                }
            for (int iu = 0; iu + 1 < nu; iu++)
                for (int iv = 0; iv + 1 < nv; iv++) {
                    float a00 = alpha[iu * nv + iv], a10 = alpha[(iu + 1) * nv + iv], a11 = alpha[(iu + 1) * nv + iv + 1], a01 = alpha[iu * nv + iv + 1];
                    if (a00 < EMPTY && a10 < EMPTY && a11 < EMPTY && a01 < EMPTY) continue;
                    if ((count + 1) * 4 > col.length) {
                        out = java.util.Arrays.copyOf(out, out.length * 2);
                        col = java.util.Arrays.copyOf(col, col.length * 2);
                    }
                    int o = count * 4;
                    corner(out, col, o, axis, ua, va, w, lo[ua] + iu + 0.5F, lo[va] + iv + 0.5F, a00, colour);
                    corner(out, col, o + 1, axis, ua, va, w, lo[ua] + iu + 1.5F, lo[va] + iv + 0.5F, a10, colour);
                    corner(out, col, o + 2, axis, ua, va, w, lo[ua] + iu + 1.5F, lo[va] + iv + 1.5F, a11, colour);
                    corner(out, col, o + 3, axis, ua, va, w, lo[ua] + iu + 0.5F, lo[va] + iv + 1.5F, a01, colour);
                    count++;
                }
        }
        return new Mesh(out, col, count);
    }

    // corner i: w along the slicing axis, u and v along the other two
    private static void corner(float[] out, int[] col, int i, int axis, int ua, int va, float w, float u, float v, float a, int colour) {
        out[i * 3 + axis] = w;
        out[i * 3 + ua] = u;
        out[i * 3 + va] = v;
        col[i] = Math.clamp(Math.round(a * 255F), 0, 255) << 24 | colour;
    }
}
