package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Random;

import org.junit.jupiter.api.Test;

// The terrain layers on hand-made land: a plain, a hill, a wood, a lake; and that the layers kept up to date through
// changes are those worked out from nothing.
class TerrainTest {
    /** land as three numbers a column, endless; chunks may be unloaded */
    static final class Land implements TerrainCore.Columns {
        final java.util.HashMap<Long, int[]> columns = new java.util.HashMap<>();
        final java.util.HashSet<Long> unloaded = new java.util.HashSet<>();
        int base = 64;
        int reads;

        static long at(int x, int z) {
            return (x & 0xFFFFFFFFL) | (z & 0xFFFFFFFFL) << 32;
        }

        /** ground: the feet's block on the bed; water over it; leaves over that */
        void set(int x, int z, int ground, int water, int canopy) {
            columns.put(at(x, z), new int[] {ground, water, canopy});
        }

        int[] get(int x, int z) {
            int[] c = columns.get(at(x, z));
            return c == null ? new int[] {base, 0, 0} : c;
        }

        @Override
        public boolean read(int cx, int cz, short[] ground, byte[] canopy, byte[] water) {
            if (unloaded.contains(TerrainTile.key(cx, cz))) return false;
            reads++;
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int[] c = get((cx << 4) + x, (cz << 4) + z);
                    ground[z << 4 | x] = (short) c[0];
                    water[z << 4 | x] = (byte) c[1];
                    canopy[z << 4 | x] = (byte) c[2];
                }
            return true;
        }
    }

    /** a core whose jobs run when the test says */
    static final class Rig {
        final Land land = new Land();
        final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        final TerrainCore core = new TerrainCore(land, jobs::add);

        void run(int ticks) {
            for (int i = 0; i < ticks; i++) {
                core.tick(Long.MAX_VALUE);
                for (Runnable j; (j = jobs.poll()) != null; ) j.run();
            }
        }

        /** until nothing is left to do */
        void settle() {
            for (int i = 0; i < 400; i++) {
                run(1);
                if (core.settled()) {
                    // a derived layer done this tick is taken in the next
                    run(1);
                    if (core.settled()) return;
                }
            }
            throw new AssertionError("the terrain never settled: " + core.info());
        }
    }

    @Test
    void aPlainIsFlatAndSeenFarOver() {
        Rig r = new Rig();
        r.core.want(0, 0);
        r.settle();
        TerrainTile t = r.core.tileAt(5, 5);
        TerrainDerived d = r.core.derivedAt(5, 5);
        assertNotNull(t);
        assertNotNull(d);
        assertEquals(64, t.ground(5, 5));
        assertEquals(64, t.mid(5, 5));
        assertEquals(0, d.far(5, 5));
        assertEquals(0, d.near(5, 5));
        // the tiles reach as far as a look goes: nothing ends it
        assertEquals(TerrainDerived.REACH, d.open(5, 5));
        // and so they do at the edge of what is derived
        TerrainDerived edge = r.core.derivedAt(TerrainCore.DERIVED * 16 + 15, 0);
        assertNotNull(edge);
        assertEquals(TerrainDerived.REACH, edge.open(TerrainCore.DERIVED * 16 + 15, 0));
        // past it nothing is derived, though the tile is kept
        assertNull(r.core.derivedAt((TerrainCore.DERIVED + 1) * 16, 0));
        assertNotNull(r.core.tileAt((TerrainCore.DERIVED + 1) * 16, 0));
    }

    @Test
    void aHillStandsOverTheLandAndHidesWhatIsBehind() {
        Rig r = new Rig();
        // a ridge ten blocks high along z, at x 40..47
        for (int x = 40; x < 48; x++) for (int z = -200; z < 200; z++) r.land.set(x, z, 74, 0, 0);
        r.core.want(0, 0);
        r.settle();
        TerrainDerived d = r.core.derivedAt(42, 2);
        assertTrue(d.far(42, 2) >= TerrainDerived.HIGH, "the ridge stands " + d.far(42, 2) + " over the land");
        assertTrue(Math.abs(r.core.derivedAt(2, 2).far(2, 2)) <= 1, "the plain is level");
        // from the plain, towards the ridge (+x): the look ends at it; away from it: far
        TerrainDerived plain = r.core.derivedAt(10, 2);
        int towards = plain.open(10, 2, 0), away = plain.open(10, 2, TerrainDerived.DIRS / 2);
        assertTrue(towards >= 24 && towards <= 34, "the look ends at the ridge, 30 blocks off: " + towards);
        assertEquals(TerrainDerived.REACH, away);
        // from the ridge one sees over all of it
        assertEquals(TerrainDerived.REACH, d.open(42, 2));
        assertEquals(0, TerrainDerived.dir(0, 0, 10, 0));
        assertEquals(TerrainDerived.DIRS / 4, TerrainDerived.dir(0, 0, 0, 10));
        assertEquals(TerrainDerived.DIRS / 2, TerrainDerived.dir(0, 0, -10, 0));
    }

    @Test
    void aWoodIsCrownedAndSeenIntoOnlyALittleWay() {
        Rig r = new Rig();
        for (int x = -200; x < 200; x++) for (int z = -200; z < 200; z++) r.land.set(x, z, 64, 0, 6);
        // a glade
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) r.land.set(x, z, 64, 0, 0);
        r.core.want(0, 0);
        r.settle();
        TerrainTile wood = r.core.tileAt(30, 30);
        assertEquals(1F, wood.crowned(30, 30));
        assertEquals(6, wood.canopy(30, 30));
        assertEquals(0F, r.core.tileAt(5, 5).crowned(5, 5));
        int in = r.core.derivedAt(30, 30).open(30, 30);
        assertTrue(in >= 8 && in <= 24, "in a wood one sees a dozen or two blocks: " + in);
        int glade = r.core.derivedAt(6, 6).open(6, 6);
        assertTrue(glade > in && glade < 48, "from a glade a little further: " + glade);
    }

    @Test
    void aLakeIsWaterOverItsBed() {
        Rig r = new Rig();
        for (int x = 16; x < 32; x++) for (int z = 0; z < 16; z++) r.land.set(x, z, 60, 4, 0);
        // its shore, waded
        for (int z = 0; z < 16; z++) r.land.set(16, z, 63, 1, 0);
        r.core.want(0, 0);
        r.settle();
        TerrainTile t = r.core.tileAt(20, 5);
        assertEquals(60, t.ground(20, 5));
        assertEquals(4, t.water(20, 5));
        assertEquals(64, t.surface(20, 5));
        assertEquals(1F, t.wet(20, 5));
        assertEquals(1F, t.deep(20, 5));
        assertEquals(0.75F, t.deep(17, 5));
        // level with the land around: the water's top is what the sky sees
        assertEquals(64, t.mid(20, 5));
        assertEquals(0, r.core.derivedAt(20, 5).far(20, 5));
    }

    @Test
    void aChangeUnderTheGroundIsNoChangeAndOneOnItIsCopiedOnce() {
        Rig r = new Rig();
        r.core.want(0, 0);
        r.settle();
        int reads = r.land.reads;
        long derives = r.core.derives;
        // digging in a cave
        for (int y = 10; y < 60; y++) r.core.changed(5, y, 5);
        r.settle();
        assertEquals(reads, r.land.reads);
        assertEquals(derives, r.core.derives);
        // a crater: a thousand blocks of one chunk over a second, the land changed
        for (int i = 0; i < 20; i++) {
            for (int k = 0; k < 50; k++) {
                int x = k % 16, z = (k * 7 + i) % 16;
                r.land.set(x, z, 60, 0, 0);
                r.core.changed(x, 63 - i % 4, z);
            }
            r.run(1);
        }
        r.settle();
        assertEquals(reads + 1, r.land.reads, "the chunk is copied once when the blast is over");
        assertEquals(60, r.core.tileAt(0, 0).ground(0, 0));
        // every chunk within a look's reach of it is worked out again, each once
        int side = 2 * TerrainCore.DERIVED + 1;
        assertEquals(derives + side * side, r.core.derives);
        // a block changed and put back: copied, found the same, nothing worked out
        derives = r.core.derives;
        r.core.changed(40, 64, 40);
        r.settle();
        assertEquals(reads + 2, r.land.reads);
        assertEquals(derives, r.core.derives);
    }

    @Test
    void anUnloadedChunkEndsALookAndComesBack() {
        Rig r = new Rig();
        long gone = TerrainTile.key(2, 0);
        r.land.unloaded.add(gone);
        r.core.want(0, 0);
        r.settle();
        assertNull(r.core.tile(gone));
        assertNull(r.core.derived(gone));
        int towards = r.core.derivedAt(8, 8).open(8, 8, 0);
        assertTrue(towards <= 28, "the look ends where nothing is known: " + towards);
        r.land.unloaded.remove(gone);
        r.core.chunk(2, 0, true);
        r.settle();
        assertNotNull(r.core.tile(gone));
        assertNotNull(r.core.derived(gone));
        assertEquals(TerrainDerived.REACH, r.core.derivedAt(8, 8).open(8, 8, 0));
        r.land.unloaded.add(gone);
        r.core.chunk(2, 0, false);
        r.settle();
        assertNull(r.core.tile(gone));
        assertTrue(r.core.derivedAt(8, 8).open(8, 8, 0) <= 28);
    }

    @Test
    void aChunkNotToBeSeenAsItLoadsIsTriedAgain() {
        Rig r = new Rig();
        long late = TerrainTile.key(2, 0);
        r.land.unloaded.add(late);
        r.core.want(0, 0);
        r.settle();
        // the load event comes before the chunk can be read; no other event follows
        r.core.chunk(2, 0, true);
        r.run(5);
        assertNull(r.core.tile(late));
        r.land.unloaded.remove(late);
        r.run(TerrainCore.ABSENT_EVERY + 1);
        assertNotNull(r.core.tile(late));
        r.settle();
        assertEquals(TerrainDerived.REACH, r.core.derivedAt(8, 8).open(8, 8, 0));
    }

    @Test
    void theFirstReadWorksEachChunkOutOnce() {
        Rig r = new Rig();
        r.core.want(0, 0);
        // a few chunks copied a tick, as the server's budget has it
        for (int i = 0; i < 400 && !(r.core.settled() && i > 3); i++) {
            r.core.tick(1_000_000L);
            for (Runnable j; (j = r.jobs.poll()) != null; ) j.run();
        }
        r.settle();
        int side = 2 * TerrainCore.DERIVED + 1;
        assertEquals(side * side, r.core.derives);
    }

    @Test
    void aBlockOnTheGroundThatMovesNoCoarseCellStalesNothing() {
        Rig r = new Rig();
        // rough ground: a column a block up changes neither the lowest, the highest nor the middle of its cell
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) r.land.set(x, z, 64 + (x + z) % 4 * 2, 0, 0);
        r.core.want(0, 0);
        r.settle();
        long derives = r.core.derives;
        int reads = r.land.reads;
        assertEquals(66, r.core.tileAt(1, 0).ground(1, 0));
        r.land.set(1, 0, 67, 0, 0);
        r.core.changed(1, 66, 0);
        r.settle();
        assertEquals(reads + 1, r.land.reads);
        assertEquals(67, r.core.tileAt(1, 0).ground(1, 0), "the tile is the new one");
        assertEquals(derives, r.core.derives, "and nothing is worked out again");
    }

    @Test
    void aJobNotTakenOrFailedIsTriedAgain() {
        Land land = new Land();
        ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        int[] refuse = {3};
        TerrainCore core = new TerrainCore(land, j -> {
            if (refuse[0]-- > 0) throw new java.util.concurrent.RejectedExecutionException("full");
            jobs.add(j);
        });
        core.want(0, 0);
        for (int i = 0; i < 400; i++) {
            core.tick(Long.MAX_VALUE);
            for (Runnable j; (j = jobs.poll()) != null; ) j.run();
        }
        assertTrue(core.settled(), core.info());
        int side = 2 * TerrainCore.DERIVED + 1;
        assertEquals(side * side, core.derivedCount());
    }

    @Test
    void whatNobodyWantsGoes() {
        Rig r = new Rig();
        r.land.unloaded.add(TerrainTile.key(1, 1));
        r.core.want(0, 0);
        r.settle();
        assertTrue(r.core.kept() > 0);
        r.run(TerrainCore.WANT_TICKS + TerrainCore.KEEP_TICKS + 200);
        assertEquals(0, r.core.kept());
        assertEquals(0, r.core.derivedCount());
        assertTrue(r.core.settled());
        assertTrue(r.core.info().contains("tiles 0, derived 0, to copy 0, to derive 0"), r.core.info());
    }

    @Test
    void keptUpThroughChangesIsTheSameAsWorkedOutFromNothing() {
        Random random = new Random(20261114);
        Rig r = new Rig();
        // rolling land with woods and ponds
        for (int x = -260; x < 260; x++)
            for (int z = -260; z < 260; z++) {
                int h = 64 + (int) Math.round(6 * Math.sin(x / 23.0) + 5 * Math.cos(z / 17.0));
                r.land.set(x, z, h, h < 58 ? 58 - h + 1 : 0, (x / 32 + z / 32) % 3 == 0 ? 5 : 0);
            }
        r.core.want(0, 0);
        r.core.want(40, -30);
        r.settle();
        for (int round = 0; round < 30; round++) {
            // a few changes in a few chunks: a pit, a tower, a tree felled, a pond filled; some chunks go and come
            for (int k = 0; k < 1 + random.nextInt(6); k++) {
                int x = random.nextInt(360) - 180, z = random.nextInt(360) - 180;
                int[] c = r.land.get(x, z);
                int size = 1 + random.nextInt(5), dy = random.nextInt(13) - 6;
                for (int ax = x; ax < x + size; ax++)
                    for (int az = z; az < z + size; az++) {
                        int[] old = r.land.get(ax, az);
                        r.land.set(ax, az, old[0] + dy, random.nextInt(4) == 0 ? random.nextInt(3) : 0, random.nextInt(3) == 0 ? 0 : old[2]);
                        r.core.changed(ax, Math.max(old[0], old[0] + dy) + random.nextInt(3), az);
                    }
                if (c[0] > 1000) throw new AssertionError();
            }
            if (random.nextInt(4) == 0) {
                long key = TerrainTile.key(random.nextInt(9) - 4, random.nextInt(9) - 4);
                boolean load = r.land.unloaded.remove(key);
                if (!load) r.land.unloaded.add(key);
                r.core.chunk(TerrainTile.x(key), TerrainTile.z(key), load);
            }
            r.run(random.nextInt(25));
            if (random.nextInt(3) == 0) r.core.want(random.nextInt(60), random.nextInt(60) - 30);
        }
        r.core.want(0, 0);
        r.core.want(40, -30);
        r.settle();
        Rig fresh = new Rig();
        fresh.land.columns.putAll(r.land.columns);
        fresh.land.unloaded.addAll(r.land.unloaded);
        fresh.core.want(0, 0);
        fresh.core.want(40, -30);
        fresh.settle();
        int compared = 0;
        for (int cx = -TerrainCore.DERIVED; cx <= TerrainCore.DERIVED + 2; cx++)
            for (int cz = -TerrainCore.DERIVED - 2; cz <= TerrainCore.DERIVED; cz++) {
                long key = TerrainTile.key(cx, cz);
                TerrainTile a = r.core.tile(key), b = fresh.core.tile(key);
                assertEquals(b == null, a == null, "tile " + cx + " " + cz);
                TerrainDerived da = r.core.derived(key), db = fresh.core.derived(key);
                if (db == null) continue;
                assertNotNull(da, "derived " + cx + " " + cz);
                assertTrue(a.same(b), "tile " + cx + " " + cz);
                for (int x = 2; x < 16; x += 4)
                    for (int z = 2; z < 16; z += 4) {
                        int bx = (cx << 4) + x, bz = (cz << 4) + z;
                        assertEquals(db.far(bx, bz), da.far(bx, bz), "far at " + bx + " " + bz);
                        assertEquals(db.near(bx, bz), da.near(bx, bz), "near at " + bx + " " + bz);
                        for (int d = 0; d < TerrainDerived.DIRS; d++)
                            assertEquals(db.open(bx, bz, d), da.open(bx, bz, d), "open at " + bx + " " + bz + " way " + d);
                        compared++;
                    }
            }
        assertTrue(compared >= 16 * 49, "compared " + compared);
        assertFalse(r.core.derives == 0);
    }
}
