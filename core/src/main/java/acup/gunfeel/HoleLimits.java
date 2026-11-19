package acup.gunfeel;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

// The hole limits, apart from Tracers so the tests can run them: holeMax counts the hits on blocks and
// holeMaxMachines the hits on what a renderer draws (NTM machines and doors), each on its own. A hit is the holes made
// by one bullet (on a machine one per part it covers, all with the same birth time); the oldest hits go first, a
// hit's holes together.
final class HoleLimits {
    private HoleLimits() {
    }

    // holes in the order they were made
    static <T> void trim(Collection<T> holes, Predicate<T> isMachine, ToLongFunction<T> born, boolean machine, int limit) {
        int max = Math.max(1, limit);
        ArrayDeque<Long> hits = new ArrayDeque<>();
        long last = Long.MIN_VALUE;
        for (T h : holes) {
            if (isMachine.test(h) != machine) continue;
            long b = born.applyAsLong(h);
            if (b != last) {
                hits.add(b);
                last = b;
            }
        }
        if (hits.size() <= max) return;
        Set<Long> drop = new HashSet<>();
        while (hits.size() > max) drop.add(hits.removeFirst());
        holes.removeIf(h -> isMachine.test(h) == machine && drop.contains(born.applyAsLong(h)));
    }
}
