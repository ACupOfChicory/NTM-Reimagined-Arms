package acup.gunfeel;

import com.hbm.blocks.multiblock.BlockMultiblockCell;
import com.hbm.blocks.multiblock.MultiblockSurface;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

// NTM's multiblocks are built of shared cell blocks (multiblock_cell_*): a bullet meeting a cell meets its machine,
// so the bullet rules (material, piercing, marks, hit sound, ricochet) are the machine's
public final class Cells {
    private Cells() {
    }

    // the block whose rules apply at pos: a cell's machine, else the block itself (also when no machine is known)
    public static BlockState ruleState(Level level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof BlockMultiblockCell)) return state;
        try {
            BlockPos core = level.isClientSide() ? MultiblockSurface.clientCoreOf(level, pos) : MultiblockSurface.coreOfAny(level, pos, state);
            if (core != null) {
                BlockState machine = level.getBlockState(core);
                if (!machine.isAir()) return machine;
            }
        } catch (RuntimeException e) {
            // no core known here: the cell's own rules
        }
        return state;
    }
}
