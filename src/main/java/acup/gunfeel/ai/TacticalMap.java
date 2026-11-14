package acup.gunfeel.ai;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The tactical map of one level: for the chunk sections the AI wants (around its mobs, around a player watching
 * /armsai debug), the cells a mob can stand in and their cover. Kept by section; a section's blocks are copied on
 * the server thread into a {@link SectionSnapshot} and its cells built from the snapshots on the AI's background
 * threads ({@link AiThreads}), then published whole.
 *
 * <p>A changed block marks its section's snapshot stale and counts up the build stamp of the sections whose cells
 * read it; the snapshot is taken again on the next tick and the cells rebuilt. Cells and stamps let a path or a
 * chosen cover tell it was worked out on an older map ({@link #stamp}). Sections nobody wants for half a minute are
 * dropped.
 */
public final class TacticalMap implements CellSource {
    /** how long a want keeps a section, in ticks */
    static final int WANT_TICKS = 200;
    /** a section kept as a neighbour or unwanted goes after this many ticks */
    static final int KEEP_TICKS = 600;
    /** the server thread's time for taking snapshots, per tick */
    static final long SNAPSHOT_BUDGET_NANOS = 500_000L;

    private static final Map<ServerLevel, TacticalMap> MAPS = new WeakHashMap<>();
    /** any map at all: the block hook leaves at once without one */
    public static volatile boolean active;
    /** the stands' count of what the block hook costs (MixinAiBlockChange): the map's part and the terrain layers' */
    public static boolean costOn;
    public static long hookNanos, hookCalls;

    final ServerLevel level;
    private final int minSection, maxSection;
    private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
    private final ConcurrentHashMap<Long, SectionSnapshot> snaps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, SectionCells> cells = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Object> done = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<BlockPos> offThread = new ConcurrentLinkedQueue<>();
    private final LongLinkedOpenHashSet queue = new LongLinkedOpenHashSet();
    /** sections whose snapshot is to be taken again: the old one stays readable until then */
    private final LongLinkedOpenHashSet dirtySnaps = new LongLinkedOpenHashSet();
    private int inFlight;
    private long tick;

    // for /armsai info
    long builds, buildNanos, buildMaxNanos, snapshots, snapshotNanos, changes;
    private long snapshotNanosWindow;
    private int windowTicks;
    double snapshotMicrosPerTick;

    static final class Section {
        @Nullable SectionSnapshot snap;
        boolean snapDirty = true;
        int version;
        int stamp;
        int builtStamp = -1;
        boolean building;
        boolean hadUnknown;
        long wantedUntil;
        long usedUntil;
    }

    private record Failed(long key, int stamp) {
    }

    private TacticalMap(ServerLevel level) {
        this.level = level;
        this.minSection = level.getMinSectionY();
        this.maxSection = level.getMaxSectionY();
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

    // ------------------------------------------------------------------ wants

    /** keep the cells within radiusXZ blocks across and radiusY up and down of a point built for a while */
    public void want(int x, int y, int z, int radiusXZ, int radiusY) {
        int sx0 = (x - radiusXZ) >> 4, sx1 = (x + radiusXZ) >> 4;
        int sz0 = (z - radiusXZ) >> 4, sz1 = (z + radiusXZ) >> 4;
        int sy0 = Math.max(minSection, (y - radiusY) >> 4), sy1 = Math.min(maxSection, (y + radiusY) >> 4);
        long until = tick + WANT_TICKS;
        for (int sx = sx0; sx <= sx1; sx++)
            for (int sz = sz0; sz <= sz1; sz++)
                for (int sy = sy0; sy <= sy1; sy++) {
                    long key = SectionPos.asLong(sx, sy, sz);
                    Section s = sections.computeIfAbsent(key, k -> new Section());
                    s.wantedUntil = until;
                    s.usedUntil = Math.max(s.usedUntil, until + KEEP_TICKS);
                    if (!s.building && s.builtStamp != s.stamp) queue.add(key);
                }
    }

    // ------------------------------------------------------------------ reading

    /** published cells of a section, maybe built on an older map (see stamp), or null */
    @Override
    public @Nullable SectionCells cells(long key) {
        return cells.get(key);
    }

    public @Nullable SectionCells cellsAt(int x, int y, int z) {
        return cells.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
    }

    /** the section's build stamp now (server thread), -1 if the map does not keep it */
    public int stamp(long key) {
        Section s = sections.get(key);
        return s == null ? -1 : s.stamp;
    }

    /** cells worked out from the blocks as they are now */
    public boolean fresh(SectionCells c) {
        Section s = sections.get(c.key);
        return s != null && s.stamp == c.stamp;
    }

    /** a block's kind (AiBlockKinds) from the snapshots, any thread; neutral where none is kept */
    @Override
    public byte kind(int x, int y, int z) {
        SectionSnapshot s = snaps.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
        return s == null ? AiBlockKinds.NEUTRAL : s.kind(x & 15, y & 15, z & 15);
    }

    /** a block's voxel from the snapshots, any thread; unknown where none is kept */
    @Override
    public byte voxel(int x, int y, int z) {
        SectionSnapshot s = snaps.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
        return s == null ? Voxel.UNKNOWN : s.at(x & 15, y & 15, z & 15);
    }

    public int sectionCount() {
        return sections.size();
    }

    public int publishedCount() {
        return cells.size();
    }

    public int queued() {
        return queue.size();
    }

    public int inFlight() {
        return inFlight;
    }

    /** wanted sections whose published cells were built on blocks as they no longer are: for the stands (server thread) */
    public int staleCount() {
        int n = 0;
        for (var e : sections.long2ObjectEntrySet()) {
            Section s = e.getValue();
            if (s.wantedUntil < tick) continue;
            SectionCells c = cells.get(e.getLongKey());
            if (c != null && c.stamp != s.stamp) n++;
        }
        return n;
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
        map.changed(pos, BlockClasses.of(state), AiBlockKinds.of(state), false);
    }

    private void changed(BlockPos pos, int voxel, int kind, boolean unknownVoxel) {
        long key = SectionPos.asLong(pos);
        Section s = sections.get(key);
        if (s == null) return;
        int lx = pos.getX() & 15, ly = pos.getY() & 15, lz = pos.getZ() & 15;
        // (a block of the same voxel and another kind, bricks for stone, is a change: AiRooms reads the kinds)
        if (!s.snapDirty && s.snap != null && !unknownVoxel && s.snap.at(lx, ly, lz) == voxel && s.snap.kind(lx, ly, lz) == kind) return;
        changes++;
        s.version++;
        if (s.snap != null) {
            s.snapDirty = true;
            dirtySnaps.add(key);
        }
        bump(key, s);
        // the cells of the sections beside it read the blocks at its edges: beside, under (a floor), over (a head
        // and a roof, ROOF_REACH + 1 blocks up)
        int x0 = lx == 0 ? -1 : 0, x1 = lx == 15 ? 1 : 0;
        int z0 = lz == 0 ? -1 : 0, z1 = lz == 15 ? 1 : 0;
        int y0 = ly <= CellBuilder.ROOF_REACH + 1 ? -1 : 0, y1 = ly >= 14 ? 1 : 0;
        int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
        for (int dx = x0; dx <= x1; dx++)
            for (int dy = y0; dy <= y1; dy++)
                for (int dz = z0; dz <= z1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    long k = SectionPos.asLong(sx + dx, sy + dy, sz + dz);
                    Section n = sections.get(k);
                    if (n != null) bump(k, n);
                }
    }

    private void bump(long key, Section s) {
        s.stamp++;
        if (s.wantedUntil >= tick && !s.building) queue.add(key);
    }

    /** a chunk came in: sections built beside it while it was away are built again */
    public void chunkLoaded(int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int sy = minSection; sy <= maxSection; sy++) {
                    long key = SectionPos.asLong(cx + dx, sy, cz + dz);
                    Section s = sections.get(key);
                    if (s == null) continue;
                    if (dx == 0 && dz == 0) s.snapDirty = true;
                    if (s.hadUnknown) bump(key, s);
                }
    }

    /** a chunk went: what was copied of it is no longer true */
    public void chunkUnloaded(int cx, int cz) {
        for (int sy = minSection; sy <= maxSection; sy++) {
            long key = SectionPos.asLong(cx, sy, cz);
            Section s = sections.get(key);
            if (s == null) continue;
            s.snap = null;
            s.snapDirty = true;
            snaps.remove(key);
            cells.remove(key);
            s.builtStamp = -1;
        }
    }

    // ------------------------------------------------------------------ tick

    public static void tickLevel(ServerLevel level) {
        TacticalMap map = peek(level);
        if (map != null) map.tick();
    }

    void tick() {
        tick++;
        for (BlockPos p; (p = offThread.poll()) != null; ) changed(p, 0, 0, true);
        for (Object r; (r = done.poll()) != null; ) publish(r);
        long t0 = System.nanoTime();
        long spent = 0;
        var dirty = dirtySnaps.iterator();
        while (dirty.hasNext() && spent < SNAPSHOT_BUDGET_NANOS) {
            long key = dirty.nextLong();
            dirty.remove();
            Section s = sections.get(key);
            if (s != null && s.snapDirty) snapshot(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
            spent = System.nanoTime() - t0;
        }
        int cap = AiThreads.size() * 8;
        var it = queue.iterator();
        while (it.hasNext() && inFlight < cap && spent < SNAPSHOT_BUDGET_NANOS) {
            long key = it.nextLong();
            Section s = sections.get(key);
            if (s == null || s.building || s.builtStamp == s.stamp || s.wantedUntil < tick) {
                it.remove();
                continue;
            }
            SectionSnapshot[] around = new SectionSnapshot[27];
            boolean unknown = false;
            int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    for (int dx = -1; dx <= 1; dx++) {
                        SectionSnapshot snap = snapshot(sx + dx, sy + dy, sz + dz);
                        around[CellBuilder.index(dx, dy, dz)] = snap;
                        if (snap == null) unknown = true;
                    }
            it.remove();
            spent = System.nanoTime() - t0;
            if (around[13] == null) continue; // its own chunk is not loaded: nothing to build yet
            int stamp = s.stamp;
            s.building = true;
            s.builtStamp = stamp;
            s.hadUnknown = unknown;
            inFlight++;
            AiThreads.submit(() -> {
                try {
                    done.add(CellBuilder.build(around, key, stamp));
                } catch (RuntimeException e) {
                    done.add(new Failed(key, stamp));
                    throw e;
                }
            });
        }
        snapshotNanosWindow += System.nanoTime() - t0;
        if (++windowTicks == 100) {
            snapshotMicrosPerTick = snapshotNanosWindow / 100.0 / 1000.0;
            snapshotNanosWindow = 0;
            windowTicks = 0;
        }
        if (tick % 100 == 0) evict();
    }

    private void publish(Object r) {
        inFlight--;
        if (r instanceof Failed f) {
            Section s = sections.get(f.key);
            if (s != null) {
                s.building = false;
                s.builtStamp = -1;
            }
            return;
        }
        SectionCells c = (SectionCells) r;
        builds++;
        buildNanos += c.buildNanos;
        buildMaxNanos = Math.max(buildMaxNanos, c.buildNanos);
        Section s = sections.get(c.key);
        if (s == null) return;
        s.building = false;
        cells.put(c.key, c);
        if (s.stamp != c.stamp && s.wantedUntil >= tick) queue.add(c.key);
    }

    /** the section's snapshot, taken now if it is stale and the budget lets; null for an unloaded chunk */
    private @Nullable SectionSnapshot snapshot(int sx, int sy, int sz) {
        long key = SectionPos.asLong(sx, sy, sz);
        Section s = sections.get(key);
        if (s == null) {
            s = new Section();
            sections.put(key, s);
        }
        s.usedUntil = Math.max(s.usedUntil, tick + KEEP_TICKS);
        if (!s.snapDirty) return s.snap;
        SectionSnapshot snap = take(key, sx, sy, sz, s.version);
        s.snap = snap;
        if (snap == null) {
            snaps.remove(key);
            return null;
        }
        s.snapDirty = false;
        snaps.put(key, snap);
        return snap;
    }

    private static final byte BELOW_WORLD = Voxel.of(Voxel.SOLID, true, 3, false, false, false);

    private @Nullable SectionSnapshot take(long key, int sx, int sy, int sz, int version) {
        if (sy < minSection) return SectionSnapshot.filled(key, version, BELOW_WORLD);
        if (sy > maxSection) return SectionSnapshot.air(key, version);
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

    private void evict() {
        var it = sections.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2ObjectMap.Entry<Section> e = it.next();
            Section s = e.getValue();
            if (s.usedUntil >= tick || s.building) continue;
            long key = e.getLongKey();
            snaps.remove(key);
            cells.remove(key);
            dirtySnaps.remove(key);
            it.remove();
        }
    }

    // ------------------------------------------------------------------ info

    public String info() {
        double avgBuild = builds == 0 ? 0 : buildNanos / (double) builds / 1000.0;
        double avgSnap = snapshots == 0 ? 0 : snapshotNanos / (double) snapshots / 1000.0;
        return String.format(java.util.Locale.ROOT,
                "sections %d, built %d, queued %d, building %d, threads %d | builds %d avg %.0f us max %.0f us | snapshots %d avg %.1f us, %.1f us/tick | block changes %d",
                sections.size(), cells.size(), queue.size(), inFlight, AiThreads.size(), builds, avgBuild,
                buildMaxNanos / 1000.0, snapshots, avgSnap, snapshotMicrosPerTick, changes);
    }
}
