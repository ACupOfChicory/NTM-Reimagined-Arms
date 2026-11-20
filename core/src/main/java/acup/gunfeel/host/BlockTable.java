package acup.gunfeel.host;

import acup.gunfeel.BlockFacts;

/**
 * The game's block states as numbers, and what core asks of a state: one table for the whole game (every level, both
 * sides), the version's ({@code Ports.port(BlockTable.class)}); a level's blocks by these numbers: {@link WorldView}.
 * <ul>
 * <li>A number stays the same state's for the whole run of the game, whatever the game does to its own numbers (26.2
 * renumbers its states when a client joins a server).</li>
 * <li>It lives only inside this process: it follows the order the states were first met in, so it differs from run
 * to run and between a client and its server. It never goes into a packet, a file, a trace or the test log; core
 * writes the state's {@link #key} there.</li>
 * <li>{@link #AIR}, 0, is the plain air block's default state. Cave air and void air have numbers of their own: core
 * tells air by its facts, not by 0.</li>
 * </ul>
 */
public interface BlockTable {
    /** the plain air block's default state */
    int AIR = 0;

    /** what a bullet's rules need of the state (its block's id, classes, sound, hardness, tags...) */
    BlockFacts facts(int state);

    /** the state as text: its block's id, then its properties sorted by name, {@code "id[p=v,q=w]"}; the shapes' key */
    String key(int state);

    /** the state is air as the game tells it (its block's: plain, cave and void air, a mod's own), not only {@link #AIR} */
    boolean air(int state);

    /**
     * The bullet rules' {@code %mod} selector's test, the game's: a machine (a block entity, or drawn by a renderer of
     * its own, not as a model) or a block whose collision is not one full cube (26.2: hasBlockEntity, getRenderShape,
     * Block.isShapeFullBlock; a shape that throws: true). Worked out once a state; the game's tags change nothing here.
     */
    boolean machineOrPartial(int state);

    /** the state has a block entity (26.2: BlockState.hasBlockEntity) */
    default boolean blockEntity(int state) {
        throw new UnsupportedOperationException("no block entity read here");
    }

    /** the state is drawn as a model (26.2: BlockState.getRenderShape == RenderShape.MODEL) */
    default boolean model(int state) {
        throw new UnsupportedOperationException("no render shape read here");
    }

    /** its collision is a full cube (26.2: Block.isShapeFullBlock(getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))) */
    default boolean fullCollision(int state) {
        throw new UnsupportedOperationException("no collision shape read here");
    }
}
