package acup.gunfeel.smoke;

import net.minecraft.core.BlockPos;

/** Clouds for the client's tests (SmokeClouds.Cloud is made only here, in its package). */
public final class TestClouds {
    private TestClouds() {
    }

    /** a cloud in open air, its burst at a world block, grown this many ticks */
    public static SmokeClouds.Cloud grown(int id, SmokeKind kind, long seed, int x, int y, int z, float dirX, float dirZ, int ticks) {
        SmokeGrid g = new SmokeGrid(kind, seed, dirX, dirZ, 1F, 0F, 0F);
        g.scan((gx, gy, gz) -> gy < SmokeGrid.OY - 1);
        g.place();
        for (int s = 0; s < ticks; s++) g.step();
        return new SmokeClouds.Cloud(id, g, new BlockPos(x, y, z), 0L, dirX, dirZ, 1F);
    }
}
