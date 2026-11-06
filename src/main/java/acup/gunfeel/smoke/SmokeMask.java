package acup.gunfeel.smoke;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Which cells of a cloud are closed to smoke, read from the world (the same on the server and a client): a block whose
 * collision fills the cell, a liquid (smoke over water, not in it), a shut door or trapdoor, a block tagged
 * {@code ntmgunfeel:blocks_smoke} (glass panes and walls: thin, but a row of them is a wall), and a cell in a chunk
 * that is not loaded. A cloud reads all its cells when it is born and a twentieth of them again each step after.
 */
public final class SmokeMask implements SmokeGrid.Mask {
    /** A cloud reads a twentieth of its cells again each step: all of them once a second. */
    public static final int PARTS = 20;
    public static final TagKey<Block> BLOCKS_SMOKE = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("ntmgunfeel", "blocks_smoke"));

    private final Level level;
    private final int baseX, baseY, baseZ;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    private SmokeMask(Level level, int baseX, int baseY, int baseZ) {
        this.level = level;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
    }

    public static SmokeMask of(Level level, SmokeClouds.Cloud cloud) {
        return new SmokeMask(level, cloud.baseX(), cloud.baseY(), cloud.baseZ());
    }

    @Override
    public boolean closed(int x, int y, int z) {
        pos.set(baseX + x, baseY + y, baseZ + z);
        return closed(level, pos);
    }

    public static boolean closed(Level level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) return pos.getY() < level.getMinY();
        if (!level.hasChunkAt(pos)) return true;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return true;
        if ((state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)) && state.hasProperty(BlockStateProperties.OPEN))
            return !state.getValue(BlockStateProperties.OPEN);
        if (state.is(BLOCKS_SMOKE)) return true;
        return state.isCollisionShapeFullBlock(level, pos);
    }
}
