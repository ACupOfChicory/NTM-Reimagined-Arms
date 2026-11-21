package acup.gunfeel.host;

import org.jspecify.annotations.Nullable;

/**
 * Synchronous world writes; each method calls the game once and ignores its result.
 * pos is the block's position as the version keeps it (26.2: BlockPos); core only passes it on, never makes or reads one.
 */
public interface WorldEdit {
    /** Show cracks (26.2: Level.destroyBlockProgress). */
    void cracks(Object level, int breaker, Object pos, int stage);
    /** Destroy the block (26.2: Level.destroyBlock). */
    void destroy(Object level, Object pos, boolean drop, @Nullable Body by);
    /** Set the numbered state (26.2: Level.setBlock with BlockStates.state). */
    void set(Object level, Object pos, int stateNumber, int flags);

    WorldEdit MISSING = new WorldEdit() {
        @Override public void cracks(Object level, int breaker, Object pos, int stage) { throw new UnsupportedOperationException("no WorldEdit here"); }
        @Override public void destroy(Object level, Object pos, boolean drop, @Nullable Body by) { throw new UnsupportedOperationException("no WorldEdit here"); }
        @Override public void set(Object level, Object pos, int stateNumber, int flags) { throw new UnsupportedOperationException("no WorldEdit here"); }
    };
}
