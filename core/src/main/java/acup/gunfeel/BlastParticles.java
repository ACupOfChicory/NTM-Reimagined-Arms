package acup.gunfeel;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

/**
 * Client: NTM's standard blast effect (its ExplosionStandardPayload, sent by the blocks of semtex and C4, NTM's torpedo
 * and crashing plane, its volatile and gold creepers, the discharge beam and a bursting boiler) puffs and smokes at
 * every block the blast reached, two vanilla particles a block: some 26,000 for a block of C4, a tick of the particles
 * 4 to 5 ms for the next 2 to 3 seconds. Of those blocks at most {@link #MAX} are taken, spread evenly over all of them
 * (MixinBlastParticleCap); the blast itself, its blocks and its damage are the server's and stay as they are.
 */
public final class BlastParticles {
    /** Blocks of a blast that puff and smoke. */
    public static final int MAX = 1500;

    private BlastParticles() {
    }

    /**
     * The blocks NTM's effect goes through (its BlockPos list on 26.2): all of them up to {@link #MAX}, else MAX of them,
     * every size/MAX-th.
     */
    public static <T> List<T> cap(List<T> blocks) {
        return blocks.size() <= MAX ? blocks : new Every<>(blocks);
    }

    private static final class Every<T> extends AbstractList<T> implements RandomAccess {
        private final List<T> all;

        Every(List<T> all) {
            this.all = all;
        }

        @Override
        public T get(int i) {
            return all.get((int) ((long) i * all.size() / MAX));
        }

        @Override
        public int size() {
            return MAX;
        }
    }
}
