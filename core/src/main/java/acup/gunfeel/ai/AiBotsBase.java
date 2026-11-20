package acup.gunfeel.ai;

import java.util.Collection;
import java.util.List;

import acup.gunfeel.LevelMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * The AI mobs of each level (26.2's AiBots keeps its own besides on each, the level's targets and voxels), by the mob's
 * id, and the cover cells they hold. Kept per level (LevelMap: the level is the version's object); what the version
 * keeps on a level may hold the level, so the version forgets it as the level is unloaded (AiBots.forget).
 *
 * <p>The bots go round in the order of their map ({@link Int2ObjectOpenHashMap}, by the ids' hashes and what was put
 * and taken out before): the squads are grown from the first of them free, so the map is the one it was.
 */
final class AiBotsBase {
    /** a level's AI mobs and the cover they hold; the version's own per level extends it */
    static class PerLevelBase<B extends AiBotBase> {
        final Int2ObjectOpenHashMap<B> bots = new Int2ObjectOpenHashMap<>();
        /** cover cells held: cell -> the mob's id */
        final Long2IntOpenHashMap claims = new Long2IntOpenHashMap();
        /** mob id -> its cell */
        final Int2LongOpenHashMap claimOf = new Int2LongOpenHashMap();
        /** mob id -> the cell beside its cover it steps out to (its peek), if it has one */
        final Int2LongOpenHashMap peekOf = new Int2LongOpenHashMap();
    }

    static final LevelMap<PerLevelBase<?>> LEVELS = new LevelMap<>();

    private AiBotsBase() {
    }

    /** the level's AI mobs as they are (no copy): read only, on the server thread, not while one is added or let go */
    static Collection<? extends AiBotBase> bots(Object level) {
        PerLevelBase<?> l = LEVELS.get(level);
        return l == null ? List.of() : l.bots.values();
    }
}
