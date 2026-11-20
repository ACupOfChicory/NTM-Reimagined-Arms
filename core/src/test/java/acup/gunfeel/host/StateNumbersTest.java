package acup.gunfeel.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import acup.gunfeel.BlockFacts;

// StateNumbers, the table under each version's BlockTable: a state's number is the order it was first met in, by
// the state object, whatever number the game gives it; facts and keys worked out once (or twice in a race), right
// for their number; many threads at once get one number per state, 0..n-1 with none skipped.
class StateNumbersTest {
    // a stand-in for a game's state: identity, as the game's BlockState (no equals of its own), and a game number that
    // the game may change
    static final class FakeState {
        final String key;
        int gameId;

        FakeState(String key, int gameId) {
            this.key = key;
            this.gameId = gameId;
        }
    }

    static BlockFacts factsOf(FakeState s) {
        String id = s.key.contains("[") ? s.key.substring(0, s.key.indexOf('[')) : s.key;
        return new BlockFacts(id, List.of(), "stone", 1F, 1F, false, true, 1, false, true, Set.of());
    }

    // a fake %mod: air (drawn by none), a fence and a slab (not one full cube) are; stone is not
    static boolean machine(FakeState s) {
        return s.key.endsWith("air") || s.key.contains("fence") || s.key.contains("slab");
    }

    static final FakeState AIR_STATE = new FakeState("minecraft:air", 0);

    // the contract on a world of fake states: air at 0 0 0, stone at 1 0 0, a fence with properties at 2 0 0
    static final class FakeWorld implements WorldView {
        final StateNumbers<FakeState> table = new StateNumbers<>(AIR_STATE, StateNumbersTest::factsOf, s -> s.key, s -> s.key.endsWith("air"), StateNumbersTest::machine);
        final Map<Long, FakeState> blocks = new HashMap<>();

        FakeWorld() {
            blocks.put(key(1, 0, 0), new FakeState("minecraft:stone", 1));
            blocks.put(key(2, 0, 0), new FakeState("minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=true]", 7));
            blocks.put(key(3, 0, 0), new FakeState("minecraft:cave_air", 2));
            blocks.put(key(4, 0, 0), new FakeState("minecraft:stone_slab[type=bottom,waterlogged=false]", 9));
        }

        long time = 1000;

        // a fake level's shapes: stone a whole block, a bottom slab its lower half, else nothing
        private static double[][] shape(FakeState s) {
            if (s.key.equals("minecraft:stone")) return new double[][] {{0, 0, 0, 1, 1, 1}};
            if (s.key.startsWith("minecraft:stone_slab[type=bottom")) return new double[][] {{0, 0, 0, 1, .5, 1}};
            return new double[0][];
        }

        private FakeState at(int x, int y, int z) {
            return blocks.getOrDefault(key(x, y, z), AIR_STATE);
        }

        @Override
        public long gameTime() {
            return time;
        }

        @Override
        public boolean client() {
            return false;
        }

        // the ray by the blocks it passes (core's BlockWalk) and each one's boxes' nearest face it enters by; a miss as
        // the game's (its end, the face back, the end's block)
        @Override
        public boolean clip(double fx, double fy, double fz, double tx, double ty, double tz, int shape, int fluid, @Nullable Body by, RayHit out) {
            double dx = tx - fx, dy = ty - fy, dz = tz - fz;
            Boolean hit = acup.gunfeel.BlockWalk.walk(fx, fy, fz, tx, ty, tz, this, (w, x, y, z) -> {
                double best = 2;
                int face = 0;
                for (double[] b : shape(w.at(x, y, z))) {
                    double[] lo = {x + b[0], y + b[1], z + b[2]}, hi = {x + b[3], y + b[4], z + b[5]}, f = {fx, fy, fz}, d = {dx, dy, dz};
                    double t0 = 0, t1 = 1;
                    int in = -1;
                    for (int i = 0; i < 3; i++) {
                        if (d[i] == 0) {
                            if (f[i] < lo[i] || f[i] > hi[i]) t0 = 2;
                            continue;
                        }
                        double a = ((d[i] > 0 ? lo[i] : hi[i]) - f[i]) / d[i], c = ((d[i] > 0 ? hi[i] : lo[i]) - f[i]) / d[i];
                        if (a > t0) {
                            t0 = a;
                            in = i * 2 + (d[i] > 0 ? 0 : 1);
                        }
                        t1 = Math.min(t1, c);
                    }
                    if (t0 <= t1 && t0 < best && in >= 0) {
                        best = t0;
                        // x: WEST 4 / EAST 5, y: DOWN 0 / UP 1, z: NORTH 2 / SOUTH 3
                        face = new int[] {4, 5, 0, 1, 2, 3}[in];
                    }
                }
                if (best > 1) return null;
                out.block = true;
                out.x = fx + dx * best;
                out.y = fy + dy * best;
                out.z = fz + dz * best;
                out.face = face;
                out.bx = x;
                out.by = y;
                out.bz = z;
                out.inside = false;
                return Boolean.TRUE;
            }, w -> Boolean.FALSE);
            if (hit) return true;
            out.block = false;
            out.x = tx;
            out.y = ty;
            out.z = tz;
            double bx = -dx, byy = -dy, bz = -dz;
            double ax = Math.abs(bx), ay = Math.abs(byy), az = Math.abs(bz);
            out.face = ay >= ax && ay >= az ? (byy < 0 ? 0 : 1) : az >= ax ? (bz < 0 ? 2 : 3) : (bx < 0 ? 4 : 5);
            out.bx = (int) Math.floor(tx);
            out.by = (int) Math.floor(ty);
            out.bz = (int) Math.floor(tz);
            out.inside = false;
            return false;
        }

        @Override
        public int boxes(int x, int y, int z, int kind, @Nullable Body by, double[] out) {
            if (kind == FALLDAMAGE_RESETTING) throw new IllegalArgumentException("no block shape of kind " + kind);
            double[][] b = shape(at(x, y, z));
            if (out.length < b.length * 6) return -b.length;
            for (int i = 0; i < b.length; i++) System.arraycopy(b[i], 0, out, i * 6, 6);
            return b.length;
        }

        @Override
        public boolean free(@Nullable Body by, double x0, double y0, double z0, double x1, double y1, double z1) {
            for (var e : blocks.entrySet()) {
                long k = e.getKey();
                for (int x = (int) Math.floor(x0); x <= (int) Math.floor(x1); x++)
                    for (int y = (int) Math.floor(y0); y <= (int) Math.floor(y1); y++)
                        for (int z = (int) Math.floor(z0); z <= (int) Math.floor(z1); z++) {
                            if (key(x, y, z) != k) continue;
                            for (double[] b : shape(e.getValue()))
                                if (x0 < x + b[3] && x1 > x + b[0] && y0 < y + b[4] && y1 > y + b[1] && z0 < z + b[5] && z1 > z + b[2]) return false;
                        }
            }
            return true;
        }

        @Override
        public float destroySpeed(int state, int x, int y, int z) {
            return table.state(state).key.equals("minecraft:stone") ? 1.5F : 0F;
        }

        @Override
        public boolean air(int state) {
            return table.air(state);
        }

        static long key(int x, int y, int z) {
            return ((long) x << 40) ^ ((long) y << 20) ^ z;
        }

        @Override
        public int state(int x, int y, int z) {
            return table.number(blocks.getOrDefault(key(x, y, z), AIR_STATE));
        }

        @Override
        public BlockFacts facts(int state) {
            return table.facts(state);
        }

        @Override
        public String key(int state) {
            return table.key(state);
        }

        @Override
        public boolean machineOrPartial(int state) {
            return table.machineOrPartial(state);
        }
    }

    @Test
    void numbersByFirstSightNotTheGamesNumber() {
        StateNumbers<FakeState> t = new StateNumbers<>(AIR_STATE, StateNumbersTest::factsOf, s -> s.key, s -> s.key.endsWith("air"), StateNumbersTest::machine);
        FakeState a = new FakeState("minecraft:stone", 5), b = new FakeState("minecraft:dirt", 3);
        assertEquals(BlockTable.AIR, t.number(AIR_STATE));
        assertEquals(1, t.number(a));
        assertEquals(2, t.number(b));
        // the game renumbers its states (26.2, joining a server): nothing changes here
        a.gameId = 900;
        b.gameId = 0;
        assertEquals(1, t.number(a));
        assertEquals(2, t.number(b));
        assertEquals("minecraft:stone", t.facts(1).id());
        assertEquals("minecraft:dirt", t.key(2));
        assertTrue(t.air(BlockTable.AIR));
        assertEquals(false, t.air(1));
        assertEquals(a, t.state(1));
        // a state alike but another object: a number of its own (the game's states are one object each)
        assertEquals(3, t.number(new FakeState("minecraft:stone", 5)));
        assertThrows(IllegalArgumentException.class, () -> t.state(4));
        assertThrows(IllegalArgumentException.class, () -> t.state(-1));
    }

    // the game's tags applied anew: facts, keys and air worked out again when next asked, by the version's functions as
    // they answer now (a tag added); the numbers stay
    @Test
    void factsForgottenNumbersKept() {
        int[] made = {0};
        java.util.Set<String> tags = new java.util.TreeSet<>();
        StateNumbers<FakeState> t = new StateNumbers<>(AIR_STATE, st -> {
            made[0]++;
            return new BlockFacts(st.key, java.util.List.of(), "stone", 1F, 1F, false, true, 1, false, true, new java.util.TreeSet<>(tags));
        }, st -> st.key, st -> st.key.endsWith("air"), StateNumbersTest::machine);
        FakeState stone = new FakeState("minecraft:stone", 1);
        int n = t.number(stone);
        BlockFacts before = t.facts(n);
        assertSame(before, t.facts(n));
        assertEquals(1, made[0]);
        tags.add("minecraft:base_stone_overworld");
        assertSame(before, t.facts(n), "kept till forgotten");
        t.forgetFacts();
        assertEquals(n, t.number(stone));
        assertSame(stone, t.state(n));
        BlockFacts after = t.facts(n);
        assertEquals(java.util.Set.of("minecraft:base_stone_overworld"), after.tags());
        assertEquals(2, made[0]);
        assertEquals("minecraft:stone", t.key(n));
        assertEquals(false, t.air(n));
        assertEquals(true, t.air(BlockTable.AIR));
        // a state first met after it: as any other
        FakeState fence = new FakeState("minecraft:oak_fence", 2);
        assertEquals(n + 1, t.number(fence));
        assertEquals("minecraft:oak_fence", t.facts(n + 1).id());
    }

    @Test
    void manyThreadsOneNumberEach() throws Exception {
        for (int round = 0; round < 20; round++) {
            StateNumbers<FakeState> t = new StateNumbers<>(AIR_STATE, StateNumbersTest::factsOf, s -> s.key, s -> s.key.endsWith("air"), StateNumbersTest::machine);
            int n = 5_000, threads = 8;
            List<FakeState> states = new ArrayList<>();
            for (int i = 0; i < n; i++) states.add(new FakeState("mod:block_" + i, i));
            Map<FakeState, Integer> seen = new ConcurrentHashMap<>();
            AtomicInteger wrong = new AtomicInteger();
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> all = new ArrayList<>();
            for (int k = 0; k < threads; k++) {
                List<FakeState> mine = new ArrayList<>(states);
                Collections.shuffle(mine, new java.util.Random(round * 31L + k));
                Thread th = new Thread(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    for (FakeState s : mine) {
                        int num = t.number(s);
                        Integer before = seen.putIfAbsent(s, num);
                        if (before != null && before != num) wrong.incrementAndGet();
                        if (!t.facts(num).id().equals(s.key) || !t.key(num).equals(s.key) || t.state(num) != s) wrong.incrementAndGet();
                    }
                });
                all.add(th);
                th.start();
            }
            go.countDown();
            for (Thread th : all) th.join();
            assertEquals(0, wrong.get());
            Set<Integer> numbers = new HashSet<>(seen.values());
            assertEquals(n, numbers.size());
            for (int i = 1; i <= n; i++) assertTrue(numbers.contains(i), "number " + i);
        }
    }
}
