package acup.gunfeel.host;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import acup.gunfeel.BlockFacts;

/**
 * A {@link BlockTable} over a version's state objects {@code S}: a state's number is the order it was first met in
 * ({@link #number}), by the object itself (its own equals and hash: identity for the game's states), never the game's
 * own number, so the game renumbering its states changes nothing here. The first state given is {@link #AIR}. A
 * state's facts and key are worked out the first time they are asked for, by the version's functions.
 */
public final class StateNumbers<S> implements BlockTable {
    private final ConcurrentHashMap<S, Integer> numbers = new ConcurrentHashMap<>();
    private final Function<S, BlockFacts> factsOf;
    private final Function<S, String> keyOf;
    // A new state is added under the lock: the arrays grow by a copy and are published through these volatiles (the
    // array before the number), so a reader holding an older, shorter array takes the slow way, never past its end.
    private volatile Object[] states = new Object[0];
    // Facts and keys are filled in lazily with no lock: two threads (the server's bullets, the client's tracers) may
    // both work one out; they are immutable and alike, so the worst is doing it twice.
    private volatile BlockFacts[] facts = new BlockFacts[0];
    private volatile String[] keys = new String[0];
    private int size;

    /** the table with {@code air} as {@link #AIR} */
    public StateNumbers(S air, Function<S, BlockFacts> factsOf, Function<S, String> keyOf) {
        this.factsOf = factsOf;
        this.keyOf = keyOf;
        number(air);
    }

    /** the state's number: the one it got when first met, else the next */
    public int number(S state) {
        Integer n = numbers.get(state);
        return n != null ? n : add(state);
    }

    private synchronized int add(S state) {
        Integer n = numbers.get(state);
        if (n != null) return n;
        int k = size;
        if (k == states.length) {
            int cap = Math.max(64, k * 2);
            Object[] s = Arrays.copyOf(states, cap);
            s[k] = state;
            facts = Arrays.copyOf(facts, cap);
            keys = Arrays.copyOf(keys, cap);
            states = s;
        } else {
            states[k] = state;
            states = states; // publish the new slot
        }
        size = k + 1;
        numbers.put(state, k);
        return k;
    }

    /** the state with that number */
    @SuppressWarnings("unchecked")
    public S state(int number) {
        if (number < 0) throw new IllegalArgumentException("no state " + number);
        Object[] s = states;
        if (number < s.length && s[number] != null) return (S) s[number];
        synchronized (this) {
            if (number >= size) throw new IllegalArgumentException("no state " + number);
            return (S) states[number];
        }
    }

    @Override
    public BlockFacts facts(int state) {
        BlockFacts[] f = facts;
        if (state < f.length) {
            BlockFacts known = f[state];
            if (known != null) return known;
        }
        BlockFacts made = factsOf.apply(state(state));
        f = facts;
        if (state < f.length) f[state] = made;
        return made;
    }

    @Override
    public String key(int state) {
        String[] k = keys;
        if (state < k.length) {
            String known = k[state];
            if (known != null) return known;
        }
        String made = keyOf.apply(state(state));
        k = keys;
        if (state < k.length) k[state] = made;
        return made;
    }
}
