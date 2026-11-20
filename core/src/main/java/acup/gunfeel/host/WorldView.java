package acup.gunfeel.host;

import org.jspecify.annotations.Nullable;

/**
 * A level's blocks as core asks for them: the state at a place as the {@link BlockTable}'s number, its facts and key
 * the table's. One per level, given by the version's wiring into what works in that level (as the water's liquids);
 * the numbers are the one table's, the same in every level.
 */
public interface WorldView extends BlockTable {
    /** the block state at the place, as {@link BlockTable}'s number */
    int state(int x, int y, int z);

    /**
     * how long the block at the place takes to break, there: the game's BlockState.getDestroySpeed(level, pos) of the
     * state with that number, the world not read again (not facts.hardness()). The number is the one {@link #state}
     * gave for this place; another is the asker's mistake, not caught here.
     */
    float destroySpeed(int state, int x, int y, int z);

    /** the level's game tick (the game's Level.getGameTime: the overworld's, as the game keeps it for every dimension) */
    long gameTime();

    /** this is the client's copy of the level (the game's Level.isClientSide) */
    boolean client();

    /** The shapes {@link #clip} and {@link #boxes} take, as the game's ClipContext.Block by its ordinal. */
    int COLLIDER = 0, OUTLINE = 1, VISUAL = 2, FALLDAMAGE_RESETTING = 3;

    /** The fluids {@link #clip} stops at, as the game's ClipContext.Fluid by its ordinal. */
    int FLUID_NONE = 0, FLUID_SOURCE_ONLY = 1, FLUID_ANY = 2, FLUID_WATER = 3;

    /**
     * The game's own ray from (fx, fy, fz) to (tx, ty, tz): Level.clip(new ClipContext(from, to, shape, fluid, by)),
     * answered by the game (its shapes, its fluids, {@code by}'s collision context: scaffolding, powder snow; none, the
     * game's empty one). True when a block was hit; the hit, or the miss, into out. {@code by} is one of this
     * version's Bodies.
     */
    boolean clip(double fx, double fy, double fz, double tx, double ty, double tz, int shape, int fluid, @Nullable Body by, RayHit out);

    /**
     * The block's shape of a kind ({@link #COLLIDER} the game's getCollisionShape, {@link #OUTLINE} getShape,
     * {@link #VISUAL} getVisualShape; {@link #FALLDAMAGE_RESETTING} is no block's shape: IllegalArgumentException) for
     * {@code by} (none: the game's empty context, as getCollisionShape(level, pos) with no entity): its boxes into out
     * by 6 (min x, y, z, max x, y, z, in the block's own frame, as VoxelShape.toAabbs gives them, in its order); how
     * many. When out is too short nothing is cut: it is -(how many), and the asker asks again with room for them.
     */
    int boxes(int x, int y, int z, int kind, @Nullable Body by, double[] out);

    /**
     * The box (min x, y, z, max x, y, z) is free for {@code by}: the game's CollisionGetter.noCollision(by, box), no
     * block's collision shape, no hard entity in it, the world border not crossed.
     */
    boolean free(@Nullable Body by, double x0, double y0, double z0, double x1, double y1, double z1);

    /** The heightmaps {@link #height} reads, as the game's Heightmap.Types by its ordinal. */
    int HEIGHT_WORLD_SURFACE_WG = 0, HEIGHT_WORLD_SURFACE = 1, HEIGHT_OCEAN_FLOOR_WG = 2, HEIGHT_OCEAN_FLOOR = 3, HEIGHT_MOTION_BLOCKING = 4,
            HEIGHT_MOTION_BLOCKING_NO_LEAVES = 5;

    /**
     * The game's Level.getHeight(kind, x, z): the y over the column's highest block that heightmap counts (the level's
     * lowest y with none; a chunk not loaded is loaded by the game to answer). Another kind: IllegalArgumentException.
     */
    int height(int x, int z, int kind);

    /** The game's hasChunk(cx, cz): the chunk is loaded now (asking never loads it). Not {@code loaded(x, y, z)}: no height here. */
    boolean hasChunk(int cx, int cz);

    /** The level's lowest block's y (the game's getMinY). */
    int minY();
}
