package acup.gunfeel.water;

import acup.gunfeel.phys.Vec3;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.world.World;
import net.minecraftforge.fluids.BlockFluidBase;

// The water's surface where effects sit on it, as the game draws it: what every particle and ring of the splashes
// asks. Falling water is no surface; a cell under a waterfall is a surface at its own height (without that, foam
// dropped to the bottom).
// port: 26.2's WaterSurface (FluidRenderer's corner heights) on 1.7.10's liquid blocks: the corners as
// RenderBlocks.getLiquidHeight draws them (a vanilla liquid by its metadata, a Forge fluid by its filled share as
// WaterPath has it), the flow by WaterPath.Fluids.flow; any liquid but lava is water.
public final class WaterSurface {
    private WaterSurface() {
    }

    // y = the surface there; fx, fz = the flow; gx, gz = the slope (east minus west, south minus north); depth = the
    // water column from the surface to the bottom; b* = the top water cell
    public record Info(double y, double fx, double fz, double gx, double gz, boolean still, double depth, int bx, int by, int bz) {
    }

    // water for the effects: any liquid but lava
    public static boolean wet(World level, int x, int y, int z) {
        Material m = level.getBlock(x, y, z).getMaterial();
        return m.isLiquid() && m != Material.lava;
    }

    // a falling stream (26.2's FlowingFluid.FALLING): a vanilla liquid with metadata 8 and over
    public static boolean falling(World level, int x, int y, int z) {
        Block b = level.getBlock(x, y, z);
        return b instanceof BlockLiquid && level.getBlockMetadata(x, y, z) >= 8;
    }

    // the liquid's own height in its cell (26.2's FluidState.getOwnHeight)
    static float ownHeight(World level, Block b, int x, int y, int z) {
        if (b instanceof BlockFluidBase) return Math.min(1F, Math.abs(((BlockFluidBase) b).getFilledPercentage(level, x, y, z)));
        return WaterPath.ownHeight(level.getBlockMetadata(x, y, z));
    }

    // the top water cell of the column (bx, bz) near height y and its corners' world heights {NW, SW, SE, NE}; null:
    // no surface there (no water, falling water, or water on top of it)
    public static double[] top(World level, int bx, int bz, double y, int below) {
        int from = (int) Math.floor(y) + 1;
        for (int by = from; by >= from - 1 - below; by--) {
            if (!wet(level, bx, by, bz) || falling(level, bx, by, bz)) continue;
            Block b = level.getBlock(bx, by, bz);
            Material m = b.getMaterial();
            Material up = level.getBlock(bx, by + 1, bz).getMaterial();
            boolean covered = up == m;
            if (covered && !falling(level, bx, by + 1, bz)) continue;
            double[] c = new double[5];
            if (covered) {
                // under a waterfall: the water's own level, not the stream's 1
                float h = ownHeight(level, b, bx, by, bz);
                c[0] = c[1] = c[2] = c[3] = by + h;
            } else {
                c[0] = by + corner(level, bx, by, bz, m, b);
                c[1] = by + corner(level, bx, by, bz + 1, m, b);
                c[2] = by + corner(level, bx + 1, by, bz + 1, m, b);
                c[3] = by + corner(level, bx + 1, by, bz, m, b);
            }
            c[4] = by;
            return c;
        }
        return null;
    }

    // RenderBlocks.getLiquidHeight: the corner at (x, z) from the four cells around it; a Forge fluid's cells by
    // their filled share in place of the metadata's
    private static float corner(World level, int x, int y, int z, Material m, Block self) {
        int l = 0;
        float f = 0F;
        for (int i = 0; i < 4; i++) {
            int jx = x - (i & 1), jz = z - (i >> 1 & 1);
            if (level.getBlock(jx, y + 1, jz).getMaterial() == m) return 1F;
            Block b = level.getBlock(jx, y, jz);
            Material mb = b.getMaterial();
            if (mb == m) {
                float air;
                boolean heavy;
                if (b instanceof BlockFluidBase) {
                    air = 1F - ownHeight(level, b, jx, y, jz);
                    heavy = air <= 1F / 9F + 1e-4F;
                } else {
                    int meta = level.getBlockMetadata(jx, y, jz);
                    air = BlockLiquid.getLiquidHeightPercent(meta);
                    heavy = meta >= 8 || meta == 0;
                }
                if (heavy) {
                    f += air * 10F;
                    l += 10;
                }
                f += air;
                l++;
            } else if (!mb.isSolid()) {
                f++;
                l++;
            }
        }
        if (l == 0) return ownHeight(level, self, x, y, z);
        return 1F - f / l;
    }

    // the surface under (x, z) near height y, searched from floor(y)+1 down to floor(y)-3 (the demo's surfaceAt)
    public static Info at(World level, double x, double y, double z) {
        return at(level, x, y, z, true);
    }

    // depth = false: the particles' and rings' every-tick ask, without the walk down the column (depth is NaN then)
    public static Info at(World level, double x, double y, double z, boolean depth) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        double[] c = top(level, bx, bz, y, 3);
        if (c == null) return null;
        int by = (int) c[4];
        double u = x - bx, v = z - bz;
        double h = height(c, u, v);
        Vec3 flow = WaterPath.of(level).flow(bx, by, bz);
        double d = Double.NaN;
        if (depth) {
            d = h - by;
            int qy = by - 1;
            for (int i = 0; i < 64 && wet(level, bx, qy, bz); i++) {
                d += 1;
                qy--;
            }
        }
        return new Info(h, flow.x, flow.z, ((c[3] + c[2]) - (c[0] + c[1])) / 2, ((c[1] + c[2]) - (c[0] + c[3])) / 2,
                flow.x == 0 && flow.z == 0, d, bx, by, bz);
    }

    // the surface height at (x, z) on a cell's corners {NW, SW, SE, NE}
    public static double height(double[] c, double u, double v) {
        return c[0] * (1 - u) * (1 - v) + c[3] * u * (1 - v) + c[1] * (1 - u) * v + c[2] * u * v;
    }

    // the water's top at a point in it (26.2's pos.y + FluidState.getHeight): 1 under the same liquid
    public static double topOf(World level, int x, int y, int z) {
        Block b = level.getBlock(x, y, z);
        if (level.getBlock(x, y + 1, z).getMaterial() == b.getMaterial()) return y + 1;
        return y + ownHeight(level, b, x, y, z);
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
    public static int connected(World level, int cx, int cz, double y, double[][] allow, int span) {
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
