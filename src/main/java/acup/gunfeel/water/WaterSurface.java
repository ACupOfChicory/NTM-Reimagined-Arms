package acup.gunfeel.water;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

// The water's surface where effects sit on it, as the game draws it (FluidRenderer's corner heights): what every
// particle and ring of the splashes asks. Falling water is no surface; a cell under a waterfall is a surface at its
// own height (without that, foam dropped to the bottom).
public final class WaterSurface {
    private WaterSurface() {
    }

    // y = the surface there; fx, fz = getFlow; depth = the water column from the surface to the bottom
    public record Info(double y, double fx, double fz, boolean still, double depth) {
    }

    // water for the effects: any liquid but lava
    public static boolean wet(FluidState fs) {
        return !fs.isEmpty() && !fs.is(FluidTags.LAVA);
    }

    public static boolean falling(FluidState fs) {
        return !fs.isSource() && fs.hasProperty(FlowingFluid.FALLING) && fs.getValue(FlowingFluid.FALLING);
    }

    public static boolean wet(BlockGetter level, int x, int y, int z) {
        return wet(level.getFluidState(new BlockPos(x, y, z)));
    }

    // the top water cell of the column (bx, bz) near height y and its corners' world heights {NW, SW, SE, NE}; null:
    // no surface there (no water, falling water, or water on top of it)
    public static @Nullable double[] top(BlockGetter level, int bx, int bz, double y, int below) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int from = (int) Math.floor(y) + 1;
        for (int by = from; by >= from - 1 - below; by--) {
            p.set(bx, by, bz);
            FluidState fs = level.getFluidState(p);
            if (!wet(fs) || falling(fs)) continue;
            FluidState up = level.getFluidState(p.move(0, 1, 0));
            boolean covered = wet(up) && up.getType().isSame(fs.getType());
            if (covered && !falling(up)) continue;
            p.move(0, -1, 0);
            double[] c = new double[5];
            if (covered) {
                // under a waterfall: the water's own level, not the stream's 1
                float h = fs.getOwnHeight();
                c[0] = c[1] = c[2] = c[3] = by + h;
            } else {
                corners(level, p, fs, c);
                for (int i = 0; i < 4; i++) c[i] += by;
            }
            c[4] = by;
            return c;
        }
        return null;
    }

    // the surface under (x, z) near height y, searched from floor(y)+1 down to floor(y)-3 (the demo's surfaceAt)
    public static @Nullable Info at(BlockGetter level, double x, double y, double z) {
        return at(level, x, y, z, true);
    }

    // depth = false: the particles' and rings' every-tick ask, without the walk down the column (depth is NaN then)
    public static @Nullable Info at(BlockGetter level, double x, double y, double z, boolean depth) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        double[] c = top(level, bx, bz, y, 3);
        if (c == null) return null;
        int by = (int) c[4];
        double u = x - bx, v = z - bz;
        double h = c[0] * (1 - u) * (1 - v) + c[3] * u * (1 - v) + c[1] * (1 - u) * v + c[2] * u * v;
        BlockPos pos = new BlockPos(bx, by, bz);
        FluidState fs = level.getFluidState(pos);
        Vec3 flow = fs.getFlow(level, pos);
        double d = Double.NaN;
        if (depth) {
            d = h - by;
            BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos(bx, by - 1, bz);
            for (int i = 0; i < 64 && wet(level.getFluidState(q)); i++) {
                d += 1;
                q.move(0, -1, 0);
            }
        }
        return new Info(h, flow.x, flow.z, flow.x == 0 && flow.z == 0, d);
    }

    // the surface height at (x, z) on a cell's corners {NW, SW, SE, NE}
    public static double height(double[] c, double u, double v) {
        return c[0] * (1 - u) * (1 - v) + c[3] * u * (1 - v) + c[1] * (1 - u) * v + c[2] * u * v;
    }

    // FluidRenderer's corner heights of a cell, {NW, SW, SE, NE}, 0..1 above the cell's floor
    private static void corners(BlockGetter level, BlockPos pos, FluidState fs, double[] out) {
        Fluid type = fs.getType();
        float self = height(level, type, pos);
        if (self >= 1F) {
            out[0] = out[1] = out[2] = out[3] = 1;
            return;
        }
        float n = height(level, type, pos.north()), s = height(level, type, pos.south()), e = height(level, type, pos.east()),
                w = height(level, type, pos.west());
        out[3] = average(level, type, self, n, e, pos.north().east());
        out[0] = average(level, type, self, n, w, pos.north().west());
        out[2] = average(level, type, self, s, e, pos.south().east());
        out[1] = average(level, type, self, s, w, pos.south().west());
    }

    private static float average(BlockGetter level, Fluid type, float self, float a, float b, BlockPos corner) {
        if (a >= 1F || b >= 1F) return 1F;
        float[] wh = new float[2];
        if (a > 0F || b > 0F) {
            float c = height(level, type, corner);
            if (c >= 1F) return 1F;
            add(wh, c);
        }
        add(wh, self);
        add(wh, a);
        add(wh, b);
        return wh[0] / wh[1];
    }

    private static void add(float[] wh, float h) {
        if (h >= 0.8F) {
            wh[0] += h * 10F;
            wh[1] += 10F;
        } else if (h >= 0F) {
            wh[0] += h;
            wh[1]++;
        }
    }

    private static float height(BlockGetter level, Fluid type, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        FluidState fs = state.getFluidState();
        if (type.isSame(fs.getType())) return type.isSame(level.getFluidState(pos.above()).getType()) ? 1F : fs.getOwnHeight();
        return !state.isSolid() ? 0F : -1F;
    }

    // edge corners shared with the neighbour in direction d (N S W E): mine a, mine b, theirs a, theirs b
    private static final int[][] EDGE = {{0, 3, 1, 2}, {1, 2, 0, 3}, {0, 1, 3, 2}, {3, 2, 0, 1}};
    private static final int[] DX = {0, 0, -1, 1}, DZ = {-1, 1, 0, 0};
    // a ring's reach in cells around its own: as far as it grows and drifts, at most MAX_SPAN
    public static final int MAX_SPAN = 12;
    private static final int[] QUEUE = new int[(2 * MAX_SPAN + 1) * (2 * MAX_SPAN + 1)];

    // The cells of the surface connected to (cx, cz) within span cells: neighbours whose shared corners agree within
    // 0.12. Past a step, a drop or air the ring does not go on, nor show again lower down. allow[(dz+span)*side +
    // dx+span] = that cell's corners (null: not connected). Returns how many.
    public static int connected(BlockGetter level, int cx, int cz, double y, double[][] allow, int span) {
        int side = 2 * span + 1;
        java.util.Arrays.fill(allow, 0, side * side, null);
        double[] c0 = top(level, cx, cz, y, 2);
        if (c0 == null) return 0;
        int n = 1;
        allow[span * side + span] = c0;
        int[] queue = QUEUE;
        int head = 0, tail = 0;
        queue[tail++] = span * side + span;
        while (head < tail) {
            int k = queue[head++];
            int x = k % side, z = k / side;
            double[] c = allow[k];
            for (int d = 0; d < 4; d++) {
                int nx = x + DX[d], nz = z + DZ[d];
                if (nx < 0 || nz < 0 || nx >= side || nz >= side) continue;
                int nk = nz * side + nx;
                if (allow[nk] != null) continue;
                int[] e = EDGE[d];
                double[] cn = top(level, cx + nx - span, cz + nz - span, (c[e[0]] + c[e[1]]) / 2, 2);
                if (cn == null) continue;
                if (Math.abs(c[e[0]] - cn[e[2]]) > 0.12 || Math.abs(c[e[1]] - cn[e[3]]) > 0.12) continue;
                allow[nk] = cn;
                queue[tail++] = nk;
                n++;
            }
        }
        return n;
    }
}
