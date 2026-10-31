package acup.gunfeel;

import net.minecraft.block.Block;
import net.minecraft.world.IBlockAccess;

// port: 26.2's WoodBoxes for NTM 1.7.10's wooden trench blocks. They are drawn as boards with gaps that join their
// neighbours (BlockBarrier and BlockWoodStructure.renderWorldBlock, client only), while their hitboxes are solid
// slabs. Their cells as drawn, for bullets: the same boxes in pixels and the same neighbour tests as NTM 1.7.10's
// renderer, so a barrier against a wall gets that side's panel and one under a solid block its ceiling boards (the
// shapes table keeps one shape per meta, drawn with nothing around). One cell set per (kind, key), made once.
public final class WoodBoxes {
    private WoodBoxes() {
    }

    private static final int BARRIER = 0, ROOF = 1, SCAFFOLD = 2, CEILING = 3;
    private static final long[][][] CACHE = new long[4][32][];

    static boolean listed(Block b) {
        return b instanceof com.hbm.blocks.generic.BlockBarrier || b instanceof com.hbm.blocks.generic.BlockWoodStructure;
    }

    // the cells drawn in this block, or null when it is not one of them
    public static long[] cells(IBlockAccess world, Block b, int meta, int x, int y, int z) {
        int kind, key;
        if (b instanceof com.hbm.blocks.generic.BlockBarrier) {
            kind = BARRIER;
            // BlockBarrier.renderWorldBlock: a side with a solid neighbour, or the side its meta faces away from
            boolean negX = solid(world.getBlock(x - 1, y, z)) || meta == 5, negZ = solid(world.getBlock(x, y, z - 1)) || meta == 3;
            boolean posX = solid(world.getBlock(x + 1, y, z)) || meta == 4, posZ = solid(world.getBlock(x, y, z + 1)) || meta == 2;
            key = (negX ? 1 : 0) | (posX ? 2 : 0) | (negZ ? 4 : 0) | (posZ ? 8 : 0) | (solid(world.getBlock(x, y + 1, z)) ? 16 : 0);
        } else if (b instanceof com.hbm.blocks.generic.BlockWoodStructure) {
            // EnumUtil.grabEnumSafely: ROOF, SCAFFOLD, CEILING by meta % 3
            switch (Math.abs(meta % 3)) {
                case 0:
                    kind = ROOF;
                    key = (same(world, b, meta, x - 1, y, z) ? 1 : 0) | (same(world, b, meta, x + 1, y, z) ? 2 : 0);
                    break;
                case 1:
                    kind = SCAFFOLD;
                    key = (same(world, b, meta, x - 1, y, z) ? 1 : 0) | (same(world, b, meta, x + 1, y, z) ? 2 : 0)
                            | (same(world, b, meta, x, y, z - 1) ? 4 : 0) | (same(world, b, meta, x, y, z + 1) ? 8 : 0)
                            | (same(world, b, meta, x, y + 1, z) ? 16 : 0);
                    break;
                default:
                    kind = CEILING;
                    key = 0;
            }
        } else {
            return null;
        }
        long[] got = CACHE[kind][key];
        if (got == null) CACHE[kind][key] = got = make(kind, key);
        return got;
    }

    private static boolean solid(Block b) {
        return b.isOpaqueCube() || b.isNormalCube();
    }

    private static boolean same(IBlockAccess world, Block b, int meta, int x, int y, int z) {
        return world.getBlock(x, y, z) == b && world.getBlockMetadata(x, y, z) == meta;
    }

    // the renderer's setRenderBounds, in pixels
    static long[] make(int kind, int key) {
        long[] out = new long[65];
        switch (kind) {
            case BARRIER: {
                boolean nx = (key & 1) != 0, px = (key & 2) != 0, nz = (key & 4) != 0, pz = (key & 8) != 0;
                int z0 = nz ? 2 : 0, z1 = pz ? 14 : 16, x0 = nx ? 2 : 0, x1 = px ? 14 : 16;
                if (nx) {
                    box(out, 0, 0, 7, 2, 16, 9);
                    box(out, 0, 1, z0, 1, 7, z1);
                    box(out, 0, 9, z0, 1, 15, z1);
                }
                if (nz) {
                    box(out, 7, 0, 0, 9, 16, 2);
                    box(out, x0, 1, 0, x1, 7, 1);
                    box(out, x0, 9, 0, x1, 15, 1);
                }
                if (px) {
                    box(out, 14, 0, 7, 16, 16, 9);
                    box(out, 15, 1, z0, 16, 7, z1);
                    box(out, 15, 9, z0, 16, 15, z1);
                }
                if (pz) {
                    box(out, 7, 0, 14, 9, 16, 16);
                    box(out, x0, 1, 15, x1, 7, 16);
                    box(out, x0, 9, 15, x1, 15, 16);
                }
                if ((key & 16) != 0) ceiling(out);
                break;
            }
            case ROOF: {
                int x0 = (key & 1) != 0 ? 0 : 1, x1 = (key & 2) != 0 ? 16 : 15;
                box(out, 0, 0, 0, 2, 2, 16);
                box(out, 14, 0, 0, 16, 2, 16);
                box(out, x0, 2, 1, x1, 3, 7);
                box(out, x0, 2, 9, x1, 3, 15);
                break;
            }
            case SCAFFOLD: {
                int top = (key & 16) != 0 ? 16 : 14;
                box(out, 1, 0, 1, 3, top, 3);
                box(out, 13, 0, 1, 15, top, 3);
                box(out, 1, 0, 13, 3, top, 15);
                box(out, 13, 0, 13, 15, top, 15);
                if ((key & 1) == 0) box(out, 0, 2, 0, 1, 6, 16);
                if ((key & 2) == 0) box(out, 15, 2, 0, 16, 6, 16);
                if ((key & 4) == 0) box(out, 0, 8, 0, 16, 12, 1);
                if ((key & 8) == 0) box(out, 0, 8, 15, 16, 12, 16);
                if ((key & 16) == 0) box(out, 0, 14, 0, 16, 16, 16);
                break;
            }
            default:
                ceiling(out);
        }
        return out;
    }

    private static void ceiling(long[] out) {
        box(out, 0, 14, 0, 2, 15, 16);
        box(out, 14, 14, 0, 16, 15, 16);
        box(out, 0, 15, 1, 16, 16, 7);
        box(out, 0, 15, 9, 16, 16, 15);
    }

    private static void box(long[] out, int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int z = z0; z < z1; z++) for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
            int k = x + 16 * (y + 16 * z);
            out[k >> 6] |= 1L << (k & 63);
        }
    }
}
