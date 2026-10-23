package acup.gunfeel;

import org.jspecify.annotations.Nullable;

import com.hbm.blocks.generic.BlockWoodBarrier;
import com.hbm.blocks.generic.BlockWoodStructure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

// NTM's wooden trench blocks (wood barrier, wooden roof, ceiling, scaffold) are drawn as boards with gaps that join
// their neighbours (NtBoxModel.Shapes, client only), while their hitboxes are solid slabs. Their cells as drawn, for
// bullets: the same boxes in pixels and the same neighbour keys as NTM's model. One cell set per (kind, key), made once.
public final class WoodBoxes {
    private WoodBoxes() {
    }

    private static final int BARRIER = 0, ROOF = 1, SCAFFOLD = 2, CEILING = 3;
    private static final long[][][] CACHE = new long[4][32][];

    // the cells drawn in this block, or null when it is not one of them
    public static long @Nullable [] cells(BlockGetter level, BlockPos pos, BlockState state) {
        int kind;
        int key;
        if (state.getBlock() instanceof BlockWoodBarrier) {
            kind = BARRIER;
            key = (BlockWoodBarrier.panel(state, Direction.WEST) ? 1 : 0) | (BlockWoodBarrier.panel(state, Direction.EAST) ? 2 : 0)
                    | (BlockWoodBarrier.panel(state, Direction.NORTH) ? 4 : 0) | (BlockWoodBarrier.panel(state, Direction.SOUTH) ? 8 : 0)
                    | (level.getBlockState(pos.above()).isSolidRender() ? 16 : 0);
        } else if (state.getBlock() instanceof BlockWoodStructure w) {
            switch (w.kind) {
                case ROOF -> {
                    kind = ROOF;
                    key = (same(level, pos, state, Direction.WEST) ? 1 : 0) | (same(level, pos, state, Direction.EAST) ? 2 : 0);
                }
                case SCAFFOLD -> {
                    kind = SCAFFOLD;
                    key = (same(level, pos, state, Direction.WEST) ? 1 : 0) | (same(level, pos, state, Direction.EAST) ? 2 : 0)
                            | (same(level, pos, state, Direction.NORTH) ? 4 : 0) | (same(level, pos, state, Direction.SOUTH) ? 8 : 0)
                            | (same(level, pos, state, Direction.UP) ? 16 : 0);
                }
                default -> {
                    kind = CEILING;
                    key = 0;
                }
            }
        } else {
            return null;
        }
        long[] got = CACHE[kind][key];
        if (got == null) CACHE[kind][key] = got = make(kind, key);
        return got;
    }

    private static boolean same(BlockGetter level, BlockPos pos, BlockState state, Direction dir) {
        return level.getBlockState(pos.relative(dir)).is(state.getBlock());
    }

    // NtBoxModel.Shapes.<kind>.boxes(key), filled into cells
    static long[] make(int kind, int key) {
        long[] out = new long[65];
        switch (kind) {
            case BARRIER -> {
                boolean w = (key & 1) != 0, e = (key & 2) != 0, n = (key & 4) != 0, s = (key & 8) != 0;
                int z0 = n ? 2 : 0, z1 = s ? 14 : 16, x0 = w ? 2 : 0, x1 = e ? 14 : 16;
                if (w) {
                    box(out, 0, 0, 7, 2, 16, 9);
                    box(out, 0, 1, z0, 1, 7, z1);
                    box(out, 0, 9, z0, 1, 15, z1);
                }
                if (n) {
                    box(out, 7, 0, 0, 9, 16, 2);
                    box(out, x0, 1, 0, x1, 7, 1);
                    box(out, x0, 9, 0, x1, 15, 1);
                }
                if (e) {
                    box(out, 14, 0, 7, 16, 16, 9);
                    box(out, 15, 1, z0, 16, 7, z1);
                    box(out, 15, 9, z0, 16, 15, z1);
                }
                if (s) {
                    box(out, 7, 0, 14, 9, 16, 16);
                    box(out, x0, 1, 15, x1, 7, 16);
                    box(out, x0, 9, 15, x1, 15, 16);
                }
                if ((key & 16) != 0) ceiling(out);
            }
            case ROOF -> {
                int x0 = (key & 1) != 0 ? 0 : 1, x1 = (key & 2) != 0 ? 16 : 15;
                box(out, 0, 0, 0, 2, 2, 16);
                box(out, 14, 0, 0, 16, 2, 16);
                box(out, x0, 2, 1, x1, 3, 7);
                box(out, x0, 2, 9, x1, 3, 15);
            }
            case SCAFFOLD -> {
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
            }
            default -> ceiling(out);
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
