package acup.gunfeel.water;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.Vec3d;
import acup.gunfeel.Vecs;
import acup.gunfeel.water.LiquidEvents.Liquid;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

// 26.2's liquids for core's LiquidPath (Wiring.liquids): a cell's height is FluidState.getHeight (so waterlogged
// blocks count), falling is a falling stream (FlowingFluid.FALLING), the flow FluidState.getFlow
public final class LevelFluids implements LiquidPath.Fluids {
    private final BlockGetter level;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    private LevelFluids(BlockGetter level) {
        this.level = level;
    }

    public static LiquidPath.Fluids of(BlockGetter level) {
        return new LevelFluids(level);
    }

    @Override
    public LiquidPath.@Nullable Cell at(int x, int y, int z) {
        FluidState fs = level.getFluidState(pos.set(x, y, z));
        if (fs.isEmpty()) return null;
        Liquid liquid = fs.is(FluidTags.WATER) ? Liquid.WATER : fs.is(FluidTags.LAVA) ? Liquid.LAVA : Liquid.OTHER;
        boolean falling = !fs.isSource() && fs.hasProperty(FlowingFluid.FALLING) && fs.getValue(FlowingFluid.FALLING);
        return new LiquidPath.Cell(liquid, fs.getHeight(level, pos), falling);
    }

    @Override
    public Vec3d flow(int x, int y, int z) {
        return Vecs.of(level.getFluidState(pos.set(x, y, z)).getFlow(level, pos));
    }
}
