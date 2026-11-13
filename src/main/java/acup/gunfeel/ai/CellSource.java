package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

/** Where a path search reads cells and blocks: the tactical map, or a test's own sections. Any thread. */
public interface CellSource {
    /** the published cells of a section (SectionPos.asLong), or null */
    @Nullable
    SectionCells cells(long key);

    /** a block's voxel byte; unknown where nothing is kept */
    byte voxel(int x, int y, int z);

    /** a block's kind (AiBlockKinds); neutral where none is kept */
    default byte kind(int x, int y, int z) {
        return AiBlockKinds.NEUTRAL;
    }
}
