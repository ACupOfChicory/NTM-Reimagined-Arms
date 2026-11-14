package acup.gunfeel.ai;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;

/**
 * The book-keeping of the terrain layers of one level, apart from the game so the tests run it: which chunks' tiles
 * are kept ({@link TerrainTile}, copied by a {@link Columns} on the caller's thread within a budget), which have what
 * stands on them worked out ({@link TerrainDerived}, on the jobs' threads), and what a change of the land makes stale.
 *
 * <p>A place wanted ({@link #want}) keeps the derived layers of the chunks within {@link #DERIVED} chunks of it and
 * the tiles of all within {@link #DERIVED} + {@link #AROUND}: a look from the edge reads that far. A change in a chunk
 * ({@link #changed}) only marks it; the chunk is copied again once the changes have stopped for {@link #SETTLE} ticks
 * (or gone on for {@link #SETTLE_MAX}), so an explosion's thousands of blocks are one copy. A copy that differs from
 * the one before counts the tile's version up and makes the derived layers of every chunk within {@link #AROUND} of it
 * stale; those are worked out again, each once, when that has settled too. The old ones stay readable meanwhile.
 * All but the readers ({@link #tile}, {@link #derived}) on one thread.
 */
public final class TerrainCore implements TerrainDerived.Tiles {
    /** chunks around a wanted place whose derived layers are kept; chunks further that those read */
    static final int DERIVED = 3, AROUND = TerrainDerived.REACH / 16;
    /** how long a want keeps a chunk, ticks; a chunk nobody wants goes this long after */
    static final int WANT_TICKS = 400, KEEP_TICKS = 600;
    /** a changed chunk is copied again this long after its last change, or this long after its first at the latest */
    static final int SETTLE = 10, SETTLE_MAX = 40;
    /** derived layers being worked out at once, at most */
    static final int IN_FLIGHT = 4;
    /** a wanted chunk that could not be read (not loaded, or not yet to be seen as it loads) is tried again this often */
    static final int ABSENT_EVERY = 40;
    /** the derived layers are handed out this often; one waits for the tiles around it to be copied this long at most */
    static final int DERIVE_EVERY = 4, WAIT_MAX = 200;
    /** a derived layer whose job failed is tried again this many times */
    static final int RETRIES = 3;

    /** copies a chunk's columns into the arrays (256 each, see {@link TerrainTile}); false: the chunk is not loaded */
    @FunctionalInterface
    public interface Columns {
        boolean read(int cx, int cz, short[] ground, byte[] canopy, byte[] water);
    }

    private static final class Entry {
        @Nullable TerrainTile tile;
        int version;
        boolean rawDirty = true, queued;
        long changedAt, dirtySince;
        long rawUntil, derivedUntil, centreUntil = Long.MIN_VALUE;
        boolean derivedDirty, deriving, derivedQueued;
        int failures;
        long derivedChangedAt, derivedDirtySince;
    }

    private final Columns columns;
    private final Consumer<Runnable> jobs;
    private final Long2ObjectOpenHashMap<Entry> entries = new Long2ObjectOpenHashMap<>();
    private final ConcurrentHashMap<Long, TerrainTile> tiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, TerrainDerived> derived = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Object> done = new ConcurrentLinkedQueue<>();
    private final LongLinkedOpenHashSet rawQueue = new LongLinkedOpenHashSet(), derivedQueue = new LongLinkedOpenHashSet();
    /** wanted chunks that could not be read */
    private final LongLinkedOpenHashSet absent = new LongLinkedOpenHashSet();
    private int inFlight;
    private long tick;

    // for /armsai info
    long copies, copyNanos, changedCopies, absentTries, derives, deriveNanos, deriveMaxNanos, marks;

    private record Failed(long key) {
    }

    public TerrainCore(Columns columns, Consumer<Runnable> jobs) {
        this.columns = columns;
        this.jobs = jobs;
    }

    // ------------------------------------------------------------------ wants

    /** keep the layers round the chunk of block x z for a while */
    public void want(int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        Entry centre = entries.get(TerrainTile.key(cx, cz));
        // asked for here a moment ago: nothing to do
        if (centre != null && centre.centreUntil > tick + WANT_TICKS / 2) return;
        long until = tick + WANT_TICKS;
        int r = DERIVED + AROUND;
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++) {
                long key = TerrainTile.key(cx + dx, cz + dz);
                Entry e = entries.get(key);
                if (e == null) {
                    e = new Entry();
                    e.dirtySince = e.changedAt = tick - SETTLE;
                    entries.put(key, e);
                }
                e.rawUntil = until;
                if (e.rawDirty && !e.queued) {
                    e.queued = true;
                    rawQueue.add(key);
                }
                if (Math.abs(dx) <= DERIVED && Math.abs(dz) <= DERIVED) {
                    // not wanted till now: what changed around it meanwhile was not marked on it
                    boolean fresh = e.derivedUntil < tick;
                    e.derivedUntil = until;
                    if (fresh) staleDerived(key, e);
                    else if (e.derivedDirty && !e.derivedQueued) {
                        e.derivedQueued = true;
                        derivedQueue.add(key);
                    }
                }
            }
        entries.get(TerrainTile.key(cx, cz)).centreUntil = until;
    }

    // ------------------------------------------------------------------ reading (any thread)

    @Override
    public @Nullable TerrainTile tile(long key) {
        return tiles.get(key);
    }

    public @Nullable TerrainTile tileAt(int x, int z) {
        return tiles.get(TerrainTile.key(x >> 4, z >> 4));
    }

    public @Nullable TerrainDerived derivedAt(int x, int z) {
        return derived.get(TerrainTile.key(x >> 4, z >> 4));
    }

    public @Nullable TerrainDerived derived(long key) {
        return derived.get(key);
    }

    /** nothing left to copy or to work out for what is wanted now (chunks that cannot be read aside) */
    public boolean settled() {
        return rawQueue.isEmpty() && derivedQueue.isEmpty() && inFlight == 0 && done.isEmpty();
    }

    // ------------------------------------------------------------------ changes

    /**
     * A block at x y z of a kept chunk changes. One under the ground of its column changes nothing the sky sees and
     * is left alone (digging in a cave, an ore mined).
     */
    public void changed(int x, int y, int z) {
        long key = TerrainTile.key(x >> 4, z >> 4);
        Entry e = entries.get(key);
        if (e == null) return;
        TerrainTile t = e.tile;
        // (the ground's own top block is at ground - 1)
        if (t != null && !e.rawDirty && y < t.ground(x, z) - 1) return;
        marks++;
        dirty(key, e);
    }

    /** a chunk came in or went: what was copied of it is to be copied again (went: there is nothing to read of it) */
    public void chunk(int cx, int cz, boolean loaded) {
        long key = TerrainTile.key(cx, cz);
        Entry e = entries.get(key);
        if (e == null) return;
        if (!loaded && e.tile != null) {
            e.tile = null;
            tiles.remove(key);
            derived.remove(key);
            staleAround(cx, cz);
        }
        dirty(key, e);
        // nothing to wait for
        e.changedAt = e.dirtySince = tick - SETTLE;
    }

    private void dirty(long key, Entry e) {
        if (!e.rawDirty) {
            e.rawDirty = true;
            e.dirtySince = tick;
        }
        e.changedAt = tick;
        if (!e.queued && e.rawUntil >= tick) {
            e.queued = true;
            rawQueue.add(key);
        }
    }

    private void staleAround(int cx, int cz) {
        for (int dz = -AROUND; dz <= AROUND; dz++)
            for (int dx = -AROUND; dx <= AROUND; dx++) {
                long key = TerrainTile.key(cx + dx, cz + dz);
                Entry e = entries.get(key);
                if (e != null && e.derivedUntil >= tick) staleDerived(key, e);
            }
    }

    private void staleDerived(long key, Entry e) {
        if (!e.derivedDirty) {
            e.derivedDirty = true;
            e.derivedDirtySince = tick;
        }
        e.derivedChangedAt = tick;
        if (!e.derivedQueued) {
            e.derivedQueued = true;
            derivedQueue.add(key);
        }
    }

    // ------------------------------------------------------------------ tick

    /** copies what has settled, within the budget; hands out the derived layers that have; takes in those done */
    public void tick(long budgetNanos) {
        tick++;
        for (Object r; (r = done.poll()) != null; ) publish(r);
        long t0 = System.nanoTime();
        var raw = rawQueue.iterator();
        while (raw.hasNext()) {
            long key = raw.nextLong();
            Entry e = entries.get(key);
            if (e == null || !e.rawDirty || e.rawUntil < tick) {
                raw.remove();
                if (e != null) e.queued = false;
                continue;
            }
            if (tick - e.changedAt < SETTLE && tick - e.dirtySince < SETTLE_MAX) continue;
            if (System.nanoTime() - t0 >= budgetNanos) break;
            raw.remove();
            e.queued = false;
            copy(key, e);
        }
        if (tick % ABSENT_EVERY == 0 && !absent.isEmpty()) {
            long[] keys = absent.toLongArray();
            absent.clear();
            for (long key : keys) {
                Entry e = entries.get(key);
                if (e != null && e.tile == null && !e.rawDirty && e.rawUntil >= tick) copy(key, e);
            }
        }
        var it = derivedQueue.iterator();
        // (whether the tiles round one are all copied is looked at only now and then, and not at all with nothing to copy)
        boolean copying = !rawQueue.isEmpty(), look = tick % DERIVE_EVERY == 0;
        while (it.hasNext() && inFlight < IN_FLIGHT) {
            long key = it.nextLong();
            Entry e = entries.get(key);
            if (e == null || !e.derivedDirty || e.derivedUntil < tick) {
                it.remove();
                if (e != null) e.derivedQueued = false;
                continue;
            }
            if (e.deriving || tick - e.derivedChangedAt < SETTLE && tick - e.derivedDirtySince < SETTLE_MAX) continue;
            // nothing of its own to read yet: when its tile comes it is made stale again
            TerrainTile self = e.tile;
            if (self == null) {
                it.remove();
                e.derivedQueued = false;
                e.derivedDirty = false;
                continue;
            }
            // tiles around it still to be copied: worked out now it would be worked out again as each comes
            if (copying && tick - e.derivedDirtySince < WAIT_MAX && (!look || pendingAround(TerrainTile.x(key), TerrainTile.z(key)))) continue;
            e.deriving = true;
            inFlight++;
            try {
                jobs.accept(() -> {
                    try {
                        done.add(TerrainDerived.build(this, self));
                    } catch (RuntimeException | Error ex) {
                        done.add(new Failed(key));
                        throw ex;
                    }
                });
            } catch (RuntimeException ex) {
                // the job was not taken: it stays stale and queued, tried again at the next handing out
                e.deriving = false;
                inFlight--;
                break;
            }
            it.remove();
            e.derivedQueued = false;
            e.derivedDirty = false;
        }
        if (tick % 100 == 0) evict();
    }

    /** a wanted chunk within a look's reach of this one is still to be copied */
    private boolean pendingAround(int cx, int cz) {
        for (int dz = -AROUND; dz <= AROUND; dz++)
            for (int dx = -AROUND; dx <= AROUND; dx++) {
                Entry e = entries.get(TerrainTile.key(cx + dx, cz + dz));
                if (e != null && e.rawDirty && e.rawUntil >= tick) return true;
            }
        return false;
    }

    private void copy(long key, Entry e) {
        long t0 = System.nanoTime();
        short[] ground = new short[256];
        byte[] canopy = new byte[256], water = new byte[256];
        int cx = TerrainTile.x(key), cz = TerrainTile.z(key);
        boolean loaded = columns.read(cx, cz, ground, canopy, water);
        e.rawDirty = false;
        if (loaded) copies++;
        else absentTries++;
        if (!loaded) {
            if (e.tile != null) {
                e.tile = null;
                tiles.remove(key);
                derived.remove(key);
                staleAround(cx, cz);
            }
            // not loaded, or loading and not to be seen yet: no event may tell when it is
            if (e.rawUntil >= tick) absent.add(key);
        } else {
            TerrainTile t = TerrainTile.of(key, e.version + 1, ground, canopy, water);
            TerrainTile old = e.tile;
            if (old == null || !old.same(t)) {
                e.version++;
                e.tile = t;
                tiles.put(key, t);
                changedCopies++;
                // (a column changed and no coarse cell with it: nothing worked out from the cells is stale)
                if (old == null || !old.sameCells(t)) staleAround(cx, cz);
            }
        }
        if (loaded) copyNanos += System.nanoTime() - t0;
    }

    private void publish(Object r) {
        inFlight--;
        if (r instanceof Failed f) {
            Entry e = entries.get(f.key);
            if (e != null) {
                e.deriving = false;
                if (e.failures++ < RETRIES && e.derivedUntil >= tick) staleDerived(f.key, e);
            }
            return;
        }
        TerrainDerived d = (TerrainDerived) r;
        derives++;
        deriveNanos += d.nanos;
        deriveMaxNanos = Math.max(deriveMaxNanos, d.nanos);
        Entry e = entries.get(d.key);
        if (e == null) return;
        e.deriving = false;
        e.failures = 0;
        // its chunk went while it was worked out
        if (e.tile != null) derived.put(d.key, d);
    }

    private void evict() {
        var it = entries.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2ObjectMap.Entry<Entry> en = it.next();
            Entry e = en.getValue();
            long key = en.getLongKey();
            if (e.derivedUntil + KEEP_TICKS < tick && !e.deriving) derived.remove(key);
            if (e.rawUntil + KEEP_TICKS >= tick || e.deriving) continue;
            tiles.remove(key);
            derived.remove(key);
            rawQueue.remove(key);
            derivedQueue.remove(key);
            absent.remove(key);
            it.remove();
        }
    }

    // ------------------------------------------------------------------ info

    public int kept() {
        return tiles.size();
    }

    public int derivedCount() {
        return derived.size();
    }

    public String info() {
        return String.format(java.util.Locale.ROOT,
                "terrain: tiles %d, derived %d, to copy %d, to derive %d, deriving %d | copies %d (%d new) avg %.1f us, %d chunks not there %d tries | derived %d avg %.0f us max %.0f us | changes marked %d",
                tiles.size(), derived.size(), rawQueue.size(), derivedQueue.size(), inFlight, copies, changedCopies,
                copies == 0 ? 0 : copyNanos / 1000.0 / copies, absent.size(), absentTries, derives, derives == 0 ? 0 : deriveNanos / 1000.0 / derives,
                deriveMaxNanos / 1000.0, marks);
    }
}
