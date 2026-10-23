package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.function.IntUnaryOperator;

import org.junit.jupiter.api.Test;

// holeMax (blocks) and holeMaxMachines (NTM machines and doors) count on their own: hits on blocks never push out
// the holes on a door, and the other way round.
class HoleLimitsTest {
    record Fake(long born, boolean machine) {
    }

    final ArrayDeque<Fake> holes = new ArrayDeque<>();
    long clock;

    // one bullet: `pieces` holes with the same birth time, then both limits as Tracers applies them
    void hit(boolean machine, int pieces, int blockMax, int machineMax) {
        clock++;
        for (int i = 0; i < pieces; i++) holes.add(new Fake(clock, machine));
        HoleLimits.trim(holes, Fake::machine, Fake::born, false, blockMax);
        HoleLimits.trim(holes, Fake::machine, Fake::born, true, machineMax);
    }

    long count(boolean machine) {
        return holes.stream().filter(h -> h.machine() == machine).count();
    }

    long hits(boolean machine) {
        return holes.stream().filter(h -> h.machine() == machine).mapToLong(Fake::born).distinct().count();
    }

    // shooting a door, then many blocks: the door keeps its holes
    @Test
    void blocksDoNotPushOutTheDoor() {
        hit(true, 10, 500, 150);
        long door = clock;
        for (int i = 0; i < 600; i++) hit(false, 1, 500, 150);
        assertEquals(10, count(true));
        assertTrue(holes.stream().filter(Fake::machine).allMatch(h -> h.born() == door));
        assertEquals(500, count(false));
        // the 100 oldest block hits went
        assertEquals(door + 101, holes.stream().filter(h -> !h.machine()).mapToLong(Fake::born).min().orElseThrow());
    }

    // and the other way round
    @Test
    void machinesDoNotPushOutBlocks() {
        for (int i = 0; i < 20; i++) hit(false, 1, 500, 150);
        for (int i = 0; i < 400; i++) hit(true, 3, 500, 150);
        assertEquals(20, count(false));
        assertEquals(150, hits(true));
    }

    // a machine's limit counts hits, not pieces, and an old hit goes with all its pieces
    @Test
    void machineLimitCountsHits() {
        for (int i = 0; i < 150; i++) hit(true, 5, 500, 150);
        assertEquals(750, count(true));
        hit(true, 5, 500, 150);
        assertEquals(150, hits(true));
        assertEquals(750, count(true));
        assertTrue(holes.stream().noneMatch(h -> h.born() == 1), "the oldest hit went whole");
    }

    // mixed shooting with every preset's limits: each limit holds, each takes only its own oldest
    @Test
    void mixedWithEveryPreset() {
        for (var e : Config.PRESETS.entrySet()) {
            holes.clear();
            clock = 0;
            int blockMax = Integer.parseInt(e.getValue().get("holeMax"));
            int machineMax = Integer.parseInt(e.getValue().get("holeMaxMachines"));
            IntUnaryOperator pieces = i -> 1 + i % 7;
            for (int i = 0; i < 3 * blockMax; i++) {
                hit(i % 3 == 0, pieces.applyAsInt(i), blockMax, machineMax);
            }
            assertEquals(blockMax, hits(false), e.getKey());
            assertEquals(machineMax, hits(true), e.getKey());
            // what is left is the newest of each kind
            for (boolean machine : new boolean[] {false, true}) {
                long kept = machine ? machineMax : blockMax;
                long[] born = holes.stream().filter(h -> h.machine() == machine).mapToLong(Fake::born).distinct().sorted().toArray();
                long newestDropped = java.util.stream.LongStream.rangeClosed(1, clock)
                        .filter(b -> ((b - 1) % 3 == 0) == machine)
                        .filter(b -> java.util.Arrays.binarySearch(born, b) < 0).max().orElse(0);
                assertTrue(newestDropped < born[0], e.getKey() + (machine ? " machines" : " blocks") + ": a newer hit went before an older one");
                assertEquals(kept, born.length);
            }
        }
    }
}
