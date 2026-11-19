package acup.gunfeel.gas;

import java.util.HashMap;
import java.util.Map;

/**
 * Which chemical thrower's cloud leaves its own thrower alone, and until when: a level's, on the server's thread
 * ({@link ChemClouds}). NTM's stream never hurts the one who fires it; the cloud it leaves would, and a shot into a wall
 * at point-blank range puts the thrower in it at once. So the cloud spares its thrower until {@link #SPARE} ticks after
 * the last gas the thrower let into it is all out, and then does to them what it does to anyone.
 *
 * <p>Why 3 s: a stream's cloud reaches some 4 blocks out from where the gas came out; walking (4.3 blocks a second)
 * takes about a second to get out of it, and the rest is the time to see it and turn. The steam's own clouds hold 1 to
 * 2 s, so at point-blank range the hot ones have mostly thinned by then, while chlorine, phosgene and mustard (8 to 14
 * s) are still there: whoever stays in their own gas takes it 3 s after they stop.
 */
public final class ChemSpare {
    /** Ticks the thrower is spared after the gas let in last is all out. */
    public static final int SPARE = 60;

    // cloud id -> {thrower's entity id, the first tick it is not spared any more}
    private final Map<Integer, long[]> byCloud = new HashMap<>();

    /**
     * The thrower let gas into a cloud at {@code now}; it comes out over the {@code outIn} ticks from then (the feed's
     * delay and its release). A cloud is only ever fed by the stream that made it: the thrower is the same.
     */
    public void let(int cloud, int thrower, long now, int outIn) {
        byCloud.put(cloud, new long[] {thrower, now + outIn + SPARE});
    }

    /** The entity id a cloud spares at {@code now}, or -1. */
    public int spares(int cloud, long now) {
        long[] s = byCloud.get(cloud);
        return s != null && now < s[1] ? (int) s[0] : -1;
    }

    /** Forgets the clouds that spare no one any more. */
    public void expire(long now) {
        byCloud.values().removeIf(s -> now >= s[1]);
    }

    public boolean isEmpty() {
        return byCloud.isEmpty();
    }
}
