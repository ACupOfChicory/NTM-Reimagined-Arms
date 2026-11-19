package acup.gunfeel.ai;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 26.2's tactical map of a level (TacticalMapBase in core): one a ServerLevel, the block hook (MixinAiBlockChange),
 * a section's blocks copied from the level's chunk, the changes made off the server thread queued till the next tick.
 */
public final class TacticalMap extends TacticalMapBase {
    private static final Map<ServerLevel, TacticalMap> MAPS = new WeakHashMap<>();
    /** any map at all: the block hook leaves at once without one */
    public static volatile boolean active;
    /** the stands' count of what the block hook costs (MixinAiBlockChange): the map's part and the terrain layers' */
    public static boolean costOn;
    public static long hookNanos, hookCalls;

    final ServerLevel level;
    private final ConcurrentLinkedQueue<BlockPos> offThread = new ConcurrentLinkedQueue<>();

    private TacticalMap(ServerLevel level) {
        super(level.getMinSectionY(), level.getMaxSectionY());
        this.level = level;
    }

    public static synchronized TacticalMap of(ServerLevel level) {
        TacticalMap map = MAPS.get(level);
        if (map == null) {
            map = new TacticalMap(level);
            MAPS.put(level, map);
            active = true;
        }
        return map;
    }

    public static synchronized @Nullable TacticalMap peek(ServerLevel level) {
        return active ? MAPS.get(level) : null;
    }

    public static synchronized void forget(ServerLevel level) {
        MAPS.remove(level);
        active = !MAPS.isEmpty();
    }

    public static synchronized void forgetAll() {
        MAPS.clear();
        active = false;
        AiThreads.stop();
    }

    // ------------------------------------------------------------------ block changes

    /** LevelChunk.setBlockState, before the change: any thread */
    public static void blockChanging(ServerLevel level, BlockPos pos, BlockState state) {
        TacticalMap map = peek(level);
        if (map == null) return;
        if (!level.getServer().isSameThread()) {
            map.offThread.add(pos.immutable());
            return;
        }
        map.changed(pos.getX(), pos.getY(), pos.getZ(), BlockClasses.of(state), AiBlockKinds.of(state), false);
    }

    // ------------------------------------------------------------------ tick

    public static void tickLevel(ServerLevel level) {
        TacticalMap map = peek(level);
        if (map != null) map.tick();
    }

    @Override
    protected void drainOffThread() {
        for (BlockPos p; (p = offThread.poll()) != null; ) changed(p.getX(), p.getY(), p.getZ(), 0, 0, true);
    }

    @Override
    protected @Nullable SectionSnapshot copy(long key, int sx, int sy, int sz, int version) {
        if (!(level.getChunkSource().getChunkNow(sx, sz) instanceof LevelChunk chunk)) return null;
        long t0 = System.nanoTime();
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
        SectionSnapshot snap;
        if (section.hasOnlyAir()) snap = SectionSnapshot.air(key, version);
        else {
            byte[] v = new byte[SectionSnapshot.SIZE], kinds = new byte[SectionSnapshot.SIZE / 4];
            AiBlockKinds.refresh();
            BlockState last = null;
            byte lastV = 0, lastK = 0;
            int i = 0, mixed = 0, firstK = -1;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        BlockState st = section.getBlockState(x, y, z);
                        if (st != last) {
                            last = st;
                            lastV = BlockClasses.of(st);
                            lastK = AiBlockKinds.of(st);
                            if (firstK < 0) firstK = lastK;
                            mixed |= lastK ^ firstK;
                        }
                        kinds[i >> 2] |= (byte) (lastK << ((i & 3) << 1));
                        v[i++] = lastV;
                    }
            snap = mixed != 0 ? SectionSnapshot.of(key, version, v, kinds, (byte) 0) : SectionSnapshot.of(key, version, v, null, (byte) firstK);
        }
        snapshots++;
        snapshotNanos += System.nanoTime() - t0;
        return snap;
    }
}
