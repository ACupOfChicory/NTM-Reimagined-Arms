package acup.gunfeel.water;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fluids.IFluidBlock;

// 26.2's WaterSurface (water 3): the water's surface where effects sit on it, as the game draws it, what every
// particle and ring of the splashes asks. Falling water is no surface; a cell under a waterfall is a surface at its
// own height. port: the corner heights are 1.12.2's BlockFluidRenderer.getFluidHeight (four cells around a corner,
// full ones ten times as heavy, any of them covered: 1), not 26.2's FluidRenderer; a Forge fluid (IFluidBlock) is
// flat at its filled share.
public final class WaterSurface {
    private WaterSurface() {
    }

    // y = the surface there; fx, fz = the flow; gx, gz = the slope (east minus west, south minus north); depth = the
    // water column from the surface to the bottom; b* = the top water cell
    public record Info(double y, double fx, double fz, double gx, double gz, boolean still, double depth, int bx, int by, int bz) {
    }

    // water for the effects: any liquid but lava
    public static boolean wet(IBlockState state) {
        Block b = state.getBlock();
        return (b instanceof BlockLiquid || b instanceof IFluidBlock) && state.getMaterial() != Material.LAVA;
    }

    public static boolean wet(World world, int x, int y, int z) {
        return wet(world.getBlockState(new BlockPos(x, y, z)));
    }

    public static boolean wet(World world, BlockPos pos) {
        return wet(world.getBlockState(pos));
    }

    // a falling stream (BlockLiquid's LEVEL 8 and up)
    public static boolean falling(IBlockState state) {
        return state.getBlock() instanceof BlockLiquid && state.getValue(BlockLiquid.LEVEL) >= 8;
    }

    // the liquid's own height in its cell, 0..1 (a source 8/9, as 26.2's getOwnHeight)
    static double ownHeight(World world, BlockPos pos, IBlockState state) {
        Block b = state.getBlock();
        if (b instanceof IFluidBlock) return Math.min(1, Math.abs(((IFluidBlock) b).getFilledPercentage(world, pos)));
        if (!(b instanceof BlockLiquid)) return 0;
        return 1 - BlockLiquid.getLiquidHeightPercent(state.getValue(BlockLiquid.LEVEL));
    }

    // a point in water for the effects: under the liquid's top in its cell
    public static boolean wetAt(World world, double x, double y, double z) {
        BlockPos pos = new BlockPos(Math.floor(x), Math.floor(y), Math.floor(z));
        IBlockState s = world.getBlockState(pos);
        if (!wet(s)) return false;
        return y < pos.getY() + cellHeight(world, pos, s);
    }

    // the liquid's height in its cell: 1 under more of the same liquid, else its own (26.2's FluidState.getHeight)
    static double cellHeight(World world, BlockPos pos, IBlockState s) {
        IBlockState up = world.getBlockState(pos.up());
        return wet(up) && up.getMaterial() == s.getMaterial() ? 1 : ownHeight(world, pos, s);
    }

    // the top water cell of the column (bx, bz) near height y and its corners' world heights {NW, SW, SE, NE}, then
    // its y; null: no surface there (no water, falling water, or water on top of it)
    public static double[] top(World world, int bx, int bz, double y, int below) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int from = (int) Math.floor(y) + 1;
        for (int by = from; by >= from - 1 - below; by--) {
            p.setPos(bx, by, bz);
            IBlockState fs = world.getBlockState(p);
            if (!wet(fs) || falling(fs)) continue;
            IBlockState up = world.getBlockState(p.up());
            boolean covered = wet(up) && up.getMaterial() == fs.getMaterial();
            if (covered && !falling(up)) continue;
            double[] c = new double[5];
            BlockPos pos = new BlockPos(bx, by, bz);
            if (covered || !(fs.getBlock() instanceof BlockLiquid)) {
                // under a waterfall: the water's own level, not the stream's 1; a Forge fluid: flat
                double h = ownHeight(world, pos, fs);
                c[0] = c[1] = c[2] = c[3] = by + h;
            } else {
                Material m = fs.getMaterial();
                c[0] = by + corner(world, pos, m);
                c[1] = by + corner(world, pos.south(), m);
                c[2] = by + corner(world, pos.east().south(), m);
                c[3] = by + corner(world, pos.east(), m);
            }
            c[4] = by;
            return c;
        }
        return null;
    }

    // BlockFluidRenderer.getFluidHeight: the height of the corner at the north-west of `pos`, from the four cells
    // around it
    private static double corner(World world, BlockPos pos, Material m) {
        int n = 0;
        float f = 0;
        for (int j = 0; j < 4; j++) {
            BlockPos q = pos.add(-(j & 1), 0, -(j >> 1 & 1));
            if (world.getBlockState(q.up()).getMaterial() == m) return 1;
            IBlockState s = world.getBlockState(q);
            Material qm = s.getMaterial();
            if (qm != m) {
                if (!qm.isSolid()) {
                    f++;
                    n++;
                }
            } else if (s.getBlock() instanceof BlockLiquid) {
                int k = s.getValue(BlockLiquid.LEVEL);
                if (k >= 8 || k == 0) {
                    f += BlockLiquid.getLiquidHeightPercent(k) * 10F;
                    n += 10;
                }
                f += BlockLiquid.getLiquidHeightPercent(k);
                n++;
            }
        }
        return n == 0 ? 1 : 1 - f / n;
    }

    // the surface under (x, z) near height y, searched from floor(y)+1 down to floor(y)-3 (the demo's surfaceAt)
    public static Info at(World world, double x, double y, double z) {
        return at(world, x, y, z, true);
    }

    // depth = false: the particles' and rings' every-tick ask, without the walk down the column (depth is NaN then)
    public static Info at(World world, double x, double y, double z, boolean depth) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        double[] c = top(world, bx, bz, y, 3);
        if (c == null) return null;
        int by = (int) c[4];
        double u = x - bx, v = z - bz;
        double h = height(c, u, v);
        BlockPos pos = new BlockPos(bx, by, bz);
        Vec3d flow = flow(world, pos);
        double d = Double.NaN;
        if (depth) {
            d = h - by;
            BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos(bx, by - 1, bz);
            for (int i = 0; i < 64 && wet(world.getBlockState(q)); i++) {
                d += 1;
                q.move(net.minecraft.util.EnumFacing.DOWN);
            }
        }
        return new Info(h, flow.x, flow.z, ((c[3] + c[2]) - (c[0] + c[1])) / 2, ((c[1] + c[2]) - (c[0] + c[3])) / 2,
                flow.x == 0 && flow.z == 0, d, bx, by, bz);
    }

    // the liquid's flow in the cell (unit, as 26.2's getFlow): what it pushes an entity by
    static Vec3d flow(World world, BlockPos pos) {
        IBlockState s = world.getBlockState(pos);
        if (!(s.getBlock() instanceof BlockLiquid)) return Vec3d.ZERO;
        try {
            return s.getBlock().modifyAcceleration(world, pos, null, Vec3d.ZERO);
        } catch (RuntimeException e) {
            return Vec3d.ZERO;
        }
    }

    // the surface height at (u, v) of a cell on its corners {NW, SW, SE, NE}
    public static double height(double[] c, double u, double v) {
        return c[0] * (1 - u) * (1 - v) + c[3] * u * (1 - v) + c[1] * (1 - u) * v + c[2] * u * v;
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
    public static int connected(World world, int cx, int cz, double y, double[][] allow, int span) {
        int side = 2 * span + 1;
        java.util.Arrays.fill(allow, 0, side * side, null);
        double[] c0 = top(world, cx, cz, y, 2);
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
                double[] cn = top(world, cx + nx - span, cz + nz - span, (c[e[0]] + c[e[1]]) / 2, 2);
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
