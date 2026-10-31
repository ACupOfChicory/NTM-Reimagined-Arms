package acup.gunfeel;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

// port/continuum: a stand-in for WoodBoxes (NTM NEXT's BlockWoodBarrier; Continuum has BlockBarrier, not checked yet):
// no wooden boards are known.
public final class WoodBoxes {
    private WoodBoxes() {
    }

    public static long @Nullable [] cells(BlockGetter level, BlockPos pos, BlockState state) {
        return null;
    }
}
