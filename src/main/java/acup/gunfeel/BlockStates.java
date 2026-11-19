package acup.gunfeel;

import acup.gunfeel.host.BlockTable;
import acup.gunfeel.host.StateNumbers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 26.2's block table (BlockTable, core): one for the game, every level and both sides. A state's number is ours, by
 * the state object (StateNumbers), not the game's (Block.BLOCK_STATE_REGISTRY, which NeoForge renumbers when a client
 * joins a server: the states stay the same objects); the game's number (BLOCK_STATE_REGISTRY.getId) is read nowhere
 * here. Facts: BlockFactsOf; the key: PassableClip.key. The table is made the first time a number is asked for, not
 * with the port (Wiring makes its ports before the game's blocks are there).
 */
public final class BlockStates implements BlockTable {
    private static final class Table {
        static final StateNumbers<BlockState> T = new StateNumbers<>(Blocks.AIR.defaultBlockState(), BlockFactsOf::of, PassableClip::key);
    }

    /** the state's number in the game's table */
    public static int number(BlockState state) {
        return Table.T.number(state);
    }

    /** the state with that number */
    public static BlockState state(int number) {
        return Table.T.state(number);
    }

    @Override
    public BlockFacts facts(int state) {
        return Table.T.facts(state);
    }

    @Override
    public String key(int state) {
        return Table.T.key(state);
    }
}
