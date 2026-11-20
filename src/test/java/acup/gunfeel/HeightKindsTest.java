package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import acup.gunfeel.host.WorldView;
import net.minecraft.world.level.levelgen.Heightmap;

// core's heightmaps (WorldView.HEIGHT_*) are the game's Heightmap.Types by their ordinals, all six, none more
class HeightKindsTest {
    @Test
    void theGamesHeightmapsByOrdinal() {
        assertEquals(6, Heightmap.Types.values().length);
        assertEquals(Heightmap.Types.WORLD_SURFACE_WG.ordinal(), WorldView.HEIGHT_WORLD_SURFACE_WG);
        assertEquals(Heightmap.Types.WORLD_SURFACE.ordinal(), WorldView.HEIGHT_WORLD_SURFACE);
        assertEquals(Heightmap.Types.OCEAN_FLOOR_WG.ordinal(), WorldView.HEIGHT_OCEAN_FLOOR_WG);
        assertEquals(Heightmap.Types.OCEAN_FLOOR.ordinal(), WorldView.HEIGHT_OCEAN_FLOOR);
        assertEquals(Heightmap.Types.MOTION_BLOCKING.ordinal(), WorldView.HEIGHT_MOTION_BLOCKING);
        assertEquals(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.ordinal(), WorldView.HEIGHT_MOTION_BLOCKING_NO_LEAVES);
    }
}
