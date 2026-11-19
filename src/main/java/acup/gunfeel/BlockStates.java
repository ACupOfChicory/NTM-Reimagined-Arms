package acup.gunfeel;

import acup.gunfeel.host.BlockTable;
import acup.gunfeel.host.StateNumbers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 26.2's block table (BlockTable, core): one for the game, every level and both sides. A state's number is ours, by
 * the state object (StateNumbers), not the game's (Block.BLOCK_STATE_REGISTRY, which NeoForge renumbers when a client
 * joins a server: the states stay the same objects); the game's number (BLOCK_STATE_REGISTRY.getId) is read nowhere
 * here. Facts: BlockFactsOf; the key: PassableClip.key; air: BlockState.isAir. The table is made the first time a number is asked for, not
 * with the port (Wiring makes its ports before the game's blocks are there).
 */
public final class BlockStates implements BlockTable {
    private static final class Table {
        static final StateNumbers<BlockState> T = new StateNumbers<>(Blocks.AIR.defaultBlockState(), BlockFactsOf::of, PassableClip::key,
                BlockState::isAir);

        // the game's block tags applied anew (a /reload; a server's, on joining it): the facts with them. Listened to from
        // the table's making on: no state has facts before it
        static {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.TagsUpdatedEvent.class, e -> forgetFacts());
        }
    }

    /** the state's number in the game's table */
    public static int number(BlockState state) {
        return Table.T.number(state);
    }

    /** the state with that number */
    public static BlockState state(int number) {
        return Table.T.state(number);
    }

    /** the facts of the state with that number (as {@link #facts}, for 26.2's own code) */
    public static BlockFacts factsOf(int number) {
        return Table.T.facts(number);
    }

    /** the game's tags changed (a /reload, a server's on joining it): every state's facts made anew when next asked */
    public static void forgetFacts() {
        Table.T.forgetFacts();
    }

    @Override
    public BlockFacts facts(int state) {
        return Table.T.facts(state);
    }

    @Override
    public String key(int state) {
        return Table.T.key(state);
    }

    @Override
    public boolean air(int state) {
        return Table.T.air(state);
    }
}
