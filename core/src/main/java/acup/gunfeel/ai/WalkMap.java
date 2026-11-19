package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

/**
 * The tactical map as an AI mob's walk reads it (26.2: TacticalMap): its cells, the sections' build stamps, what it
 * keeps built. Server thread, but {@link #cells} and {@link #voxel} (CellSource) as the search reads them.
 */
public interface WalkMap extends CellSource {
    /** the blocks changed so far (a count: a walk checks its path the tick it moves) */
    long changes();

    /** keep the cells within radiusXZ blocks across and radiusY up and down of a point built for a while */
    void want(int x, int y, int z, int radiusXZ, int radiusY);

    /** the sections of a path being walked kept a while from now (none built for it: only those the map has) */
    void keepWalked(long[] keys);

    /** the section's build stamp now, -1 if the map does not keep it */
    int stamp(long key);

    /** cells worked out from the blocks as they are now */
    boolean fresh(SectionCells c);

    @Override
    @Nullable SectionCells cells(long key);
}
