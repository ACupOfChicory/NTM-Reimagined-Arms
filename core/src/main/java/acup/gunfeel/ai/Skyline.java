package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

/**
 * Whether a head stands out against the sky to one who looks at it: the line from his eye through the head, on past it,
 * stays well over the land and the crowns behind it ({@link #CLEAR} blocks and more, from {@link #FROM} to {@link #FAR}
 * blocks past the head), so nothing is behind the head but sky. A man on a crest seen from below is; on a plain, or on
 * a slope with the hill rising behind him, he is not. Read off the terrain layers (Tiles: AiTerrain), no ray through blocks;
 * any thread. A column not kept is taken for land behind (no skyline): it is not said without knowing.
 */
public final class Skyline {
    /** the line this high over the land and the crowns at least, blocks, all the way: sky behind */
    static final double CLEAR = 2;
    /** looked at from this far past the head to this far, every STEP blocks */
    static final int FROM = 4, FAR = 32, STEP = 2;

    private Skyline() {
    }

    /** the columns behind: the top of the land (with water) and the leaves over it, or null where nothing is kept */
    public interface Land {
        /** the first block over the land and the crowns there (the feet's block on open ground), or Integer.MIN_VALUE */
        int top(int x, int z);
    }

    /** the terrain's tiles by column (26.2: AiTerrain), null where none is kept */
    public interface Tiles {
        @Nullable TerrainTile tile(int x, int z);
    }

    public static Land of(Tiles t) {
        return (x, z) -> {
            TerrainTile tile = t.tile(x, z);
            return tile == null ? Integer.MIN_VALUE : tile.surface(x, z) + tile.canopy(x, z);
        };
    }

    /** eye: the one who looks; hx hy hz: the head looked at */
    public static boolean against(@Nullable Land land, double ex, double ey, double ez, double hx, double hy, double hz) {
        if (land == null) return false;
        double dx = hx - ex, dz = hz - ez, len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1) return false;
        // how much the line rises a block across
        double rise = (hy - ey) / len, ux = dx / len, uz = dz / len;
        for (int s = FROM; s <= FAR; s += STEP) {
            int x = (int) Math.floor(hx + ux * s), z = (int) Math.floor(hz + uz * s);
            int top = land.top(x, z);
            if (top == Integer.MIN_VALUE) return false;
            if (hy + rise * s - top < CLEAR) return false;
        }
        return true;
    }
}
