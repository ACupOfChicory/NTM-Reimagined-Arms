package acup.gunfeel.ai;

import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import net.minecraft.core.BlockPos;

/**
 * A path's step costs more where a known threat sees the mob's head: a mob that moves keeps to cover where it can.
 * One ray a cell from the threat's eye over the map's own snapshots (any thread), kept for the rest of the search:
 * {@link #forSearch} gives each search its own cache.
 */
public final class ExposureCost implements PathSearch.Cost {
    /** what a step in the open costs on top of its length */
    static final float SEEN = 2.5F;

    private final CellSource map;
    private final VoxelRay.Source voxels;
    private final double ex, ey, ez;
    private final Long2FloatOpenHashMap seen;

    public ExposureCost(CellSource map, double eyeX, double eyeY, double eyeZ) {
        this(map, eyeX, eyeY, eyeZ, null);
    }

    private ExposureCost(CellSource map, double ex, double ey, double ez, Long2FloatOpenHashMap cache) {
        this.map = map;
        this.voxels = map::voxel;
        this.ex = ex;
        this.ey = ey;
        this.ez = ez;
        this.seen = cache;
    }

    @Override
    public PathSearch.Cost forSearch() {
        Long2FloatOpenHashMap cache = new Long2FloatOpenHashMap();
        cache.defaultReturnValue(-1F);
        return new ExposureCost(map, ex, ey, ez, cache);
    }

    @Override
    public float extra(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        if (seen != null) {
            float c = seen.get(key);
            if (c >= 0F) return c;
        }
        float c = VoxelRay.clear(voxels, ex, ey, ez, x + 0.5, y + 1.6, z + 0.5, Voxel.SIGHT) ? SEEN : 0F;
        if (seen != null) seen.put(key, c);
        return c;
    }
}
