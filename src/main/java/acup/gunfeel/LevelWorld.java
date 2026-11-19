package acup.gunfeel;

import acup.gunfeel.host.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

/** 26.2's world for core (WorldView): a level's (or any blocks') states by the game's one table, BlockStates. */
final class LevelWorld implements WorldView {
    // a place per thread: bullets ask on the server's thread, tracers on the client's
    private static final ThreadLocal<BlockPos.MutableBlockPos> AT = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private static final BlockStates TABLE = new BlockStates();
    private final BlockGetter level;

    LevelWorld(BlockGetter level) {
        this.level = level;
    }

    @Override
    public int state(int x, int y, int z) {
        return BlockStates.number(level.getBlockState(AT.get().set(x, y, z)));
    }

    @Override
    public BlockFacts facts(int state) {
        return TABLE.facts(state);
    }

    @Override
    public String key(int state) {
        return TABLE.key(state);
    }
}
