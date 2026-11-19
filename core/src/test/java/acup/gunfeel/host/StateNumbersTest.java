package acup.gunfeel.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    static final FakeState AIR_STATE = new FakeState("minecraft:air", 0);

    // the contract on a world of fake states: air at 0 0 0, stone at 1 0 0, a fence with properties at 2 0 0
    static final class FakeWorld implements WorldView {
        final StateNumbers<FakeState> table = new StateNumbers<>(AIR_STATE, StateNumbersTest::factsOf, s -> s.key, s -> s.key.endsWith("air"));
        final Map<Long, FakeState> blocks = new HashMap<>();

        FakeWorld() {
            blocks.put(key(1, 0, 0), new FakeState("minecraft:stone", 1));
            blocks.put(key(2, 0, 0), new FakeState("minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=true]", 7));
            blocks.put(key(3, 0, 0), new FakeState("minecraft:cave_air", 2));
        }

        @Override
        public float destroySpeed(int x, int y, int z) {
            return blocks.getOrDefault(key(x, y, z), AIR_STATE).key.equals("minecraft:stone") ? 1.5F : 0F;
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
    }

    @Test
    void numbersByFirstSightNotTheGamesNumber() {
        StateNumbers<FakeState> t = new StateNumbers<>(AIR_STATE, StateNumbersTest::factsOf, s -> s.key, s -> s.key.endsWith("air"));
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

    @Test
    void manyThreadsOneNumberEach() throws Exception {
        for (int round = 0; round < 20; round++) {
            StateNumbers<FakeState> t = new StateNumbers<>(AIR_STATE, StateNumbersTest::factsOf, s -> s.key, s -> s.key.endsWith("air"));
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
