package acup.gunfeel.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The live world as {@link Voxel} bytes, for rays on the server thread: always the blocks as they are now, and far
 * past the cells the map keeps (a mob sees further than it plans). A chunk that is not loaded reads as a wall.
 */
public final class LiveVoxels implements VoxelRay.Source {
    private static final int BELOW = Voxel.of(Voxel.SOLID, true, 3, false, false, false);
    private final ServerLevel level;
    private final int minY, maxY;
    private int lastX = Integer.MIN_VALUE, lastZ;
    private LevelChunk last;

    public LiveVoxels(ServerLevel level) {
        this.level = level;
        this.minY = level.getMinY();
        this.maxY = level.getMaxY();
    }

    @Override
    public int voxel(int x, int y, int z) {
        if (y < minY) return BELOW;
        if (y > maxY) return Voxel.AIR;
        int cx = x >> 4, cz = z >> 4;
        if (cx != lastX || cz != lastZ) {
            lastX = cx;
            lastZ = cz;
            last = level.getChunkSource().getChunkNow(cx, cz);
        }
        if (last == null) return Voxel.UNKNOWN;
        LevelChunkSection s = last.getSection(last.getSectionIndex(y));
        if (s.hasOnlyAir()) return Voxel.AIR;
        return BlockClasses.of(s.getBlockState(x & 15, y & 15, z & 15));
    }
}
