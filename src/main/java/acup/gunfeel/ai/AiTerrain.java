package acup.gunfeel.ai;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

/**
 * The lie of the land of one level for the mob AI, far past the cells of the tactical map: heights, water, the crowns
 * of trees, hills and hollows, how far one sees ({@link TerrainTile}, {@link TerrainDerived}). Read off the game's own
 * heightmaps, which the game keeps true through every dug, placed and blown-up block, so nothing is marked by hand
 * and nothing is scanned: a chunk is 768 numbers copied on the server thread, the rest is worked out on the AI's
 * background threads. Kept round the AI's mobs ({@link #want}); {@link TerrainCore} has the book-keeping.
 *
 * <p>The layers say what the sky sees: under a roof or in a cave they give the roof and the hill, not the floor.
 * There the cells and the rooms speak (TacticalMap, AiRooms).
 */
public final class AiTerrain {
    /** the server thread's time for copying chunks, per tick */
    static final long BUDGET_NANOS = 150_000L;
    /** the deepest water counted, blocks */
    static final int MAX_WATER = 32;
    /** no tile there, or no derived layers yet */
    public static final int NONE = Integer.MIN_VALUE;

    /** off only for the stands' "before": -Dntmgunfeel.aiTerrainOff=true (./gradlew runBenchAi -PbenchAiTerrainOff) */
    static final boolean ON = !Boolean.getBoolean("ntmgunfeel.aiTerrainOff");

    private static final Map<ServerLevel, AiTerrain> LEVELS = new WeakHashMap<>();
    /** any level's layers at all: the block hook leaves at once without */
    public static volatile boolean active;

    private final ServerLevel level;
    private final TerrainCore core;
    private final ConcurrentLinkedQueue<BlockPos> offThread = new ConcurrentLinkedQueue<>();

    private AiTerrain(ServerLevel level) {
        this.level = level;
        this.core = new TerrainCore(this::read, AiThreads::submit);
    }

    public static synchronized AiTerrain of(ServerLevel level) {
        AiTerrain t = LEVELS.get(level);
        if (t == null) {
            t = new AiTerrain(level);
            LEVELS.put(level, t);
            active = true;
        }
        return t;
    }

    public static synchronized @Nullable AiTerrain peek(ServerLevel level) {
        return active ? LEVELS.get(level) : null;
    }

    public static synchronized void forget(ServerLevel level) {
        LEVELS.remove(level);
        active = !LEVELS.isEmpty();
    }

    public static synchronized void forgetAll() {
        LEVELS.clear();
        active = false;
    }

    // ------------------------------------------------------------------ the game's side (server thread)

    /** keep the layers round a place for a while: an AI mob there, someone watching /armsai debug terrain */
    public void want(int x, int z) {
        core.want(x, z);
    }

    /** LevelChunk.setBlockState: any thread */
    public static void blockChanging(ServerLevel level, BlockPos pos) {
        AiTerrain t = peek(level);
        if (t == null) return;
        if (!level.getServer().isSameThread()) t.offThread.add(pos.immutable());
        else t.core.changed(pos.getX(), pos.getY(), pos.getZ());
    }

    public static void chunk(ServerLevel level, int cx, int cz, boolean loaded) {
        AiTerrain t = peek(level);
        if (t != null) t.core.chunk(cx, cz, loaded);
    }

    public static void tickLevel(ServerLevel level) {
        AiTerrain t = peek(level);
        if (t == null) return;
        for (BlockPos p; (p = t.offThread.poll()) != null; ) t.core.changed(p.getX(), p.getY(), p.getZ());
        t.core.tick(BUDGET_NANOS);
    }

    private static final java.util.EnumSet<Heightmap.Types> KINDS = java.util.EnumSet.of(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.OCEAN_FLOOR);

    /**
     * A chunk's columns from its heightmaps. Water is looked for only where the top block that stops a mob or holds a
     * fluid is not the top block that stops a mob (the sea bed's map; leaves stop a mob, so under a crown it is looked
     * for too), and its depth found by going down through it.
     */
    private boolean read(int cx, int cz, short[] ground, byte[] canopy, byte[] water) {
        if (!(level.getChunkSource().getChunkNow(cx, cz) instanceof LevelChunk chunk)) return false;
        for (Heightmap.Types kind : KINDS) if (!chunk.hasPrimedHeightmap(kind)) Heightmap.primeHeightmaps(chunk, java.util.EnumSet.of(kind));
        Heightmap noLeaves = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        Heightmap all = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        Heightmap bed = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int bx = cx << 4, bz = cz << 4, minY = level.getMinY();
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                int i = z << 4 | x;
                // the first block over the top one that stops a mob or holds a fluid, leaves aside; and with leaves
                int over = noLeaves.getFirstAvailable(x, z), leaves = all.getFirstAvailable(x, z);
                int depth = 0;
                if (bed.getFirstAvailable(x, z) != over && over > minY) {
                    int top = over - 1;
                    at.set(bx + x, top, bz + z);
                    FluidState fluid = chunk.getFluidState(at);
                    if (!fluid.isEmpty() && fluid.is(FluidTags.WATER)) {
                        depth = 1;
                        while (depth < MAX_WATER && top - depth >= minY) {
                            at.setY(top - depth);
                            FluidState under = chunk.getFluidState(at);
                            if (under.isEmpty() || !under.is(FluidTags.WATER)) break;
                            depth++;
                        }
                    }
                }
                ground[i] = (short) (over - depth);
                water[i] = (byte) depth;
                canopy[i] = (byte) Math.max(0, Math.min(127, leaves - over));
            }
        return true;
    }

    // ------------------------------------------------------------------ reading (any thread)

    public @Nullable TerrainTile tile(int x, int z) {
        return core.tileAt(x, z);
    }

    public @Nullable TerrainDerived derived(int x, int z) {
        return core.derivedAt(x, z);
    }

    /** the block a mob's feet are in standing on the ground of the column (on the bed, under water), or {@link #NONE} */
    public int ground(int x, int z) {
        TerrainTile t = core.tileAt(x, z);
        return t == null ? NONE : t.ground(x, z);
    }

    /** blocks of water over the bed of the column; 0 where nothing is kept */
    public int water(int x, int z) {
        TerrainTile t = core.tileAt(x, z);
        return t == null ? 0 : t.water(x, z);
    }

    /** how high the leaves reach over the column's ground; 0: open sky, or nothing kept */
    public int canopy(int x, int z) {
        TerrainTile t = core.tileAt(x, z);
        return t == null ? 0 : t.canopy(x, z);
    }

    /** the part of the columns round there (a coarse cell) under the crowns of trees, 0..1 */
    public float crowned(int x, int z) {
        TerrainTile t = core.tileAt(x, z);
        return t == null ? 0F : t.crowned(x, z);
    }

    /** how far one sees from there standing, the mean of all ways, blocks; or {@link #NONE} */
    public int open(int x, int z) {
        TerrainDerived d = core.derivedAt(x, z);
        return d == null ? NONE : d.open(x, z);
    }

    /** how far one sees from there towards a place, blocks; or {@link #NONE} */
    public int openTowards(int x, int z, double toX, double toZ) {
        TerrainDerived d = core.derivedAt(x, z);
        return d == null ? NONE : d.open(x, z, TerrainDerived.dir(x + 0.5, z + 0.5, toX, toZ));
    }

    /** blocks the place stands over (under: negative) the land within {@link TerrainDerived#FAR} blocks; or {@link #NONE} */
    public int height(int x, int z) {
        TerrainDerived d = core.derivedAt(x, z);
        return d == null ? NONE : d.far(x, z);
    }

    TerrainCore core() {
        return core;
    }

    public String info() {
        return core.info();
    }
}
