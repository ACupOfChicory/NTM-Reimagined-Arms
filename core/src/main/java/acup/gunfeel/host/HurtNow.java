package acup.gunfeel.host;

import org.jspecify.annotations.Nullable;

/** The hurt under way: each method is one version read, the port keeps nothing. */
public interface HurtNow {
    int PIERCE_DT = 0, PIERCE_DR = 1;

    /** the hurt under way is the gas's */
    boolean gas();

    /** the gun's shot and reload from empty, ticks */
    int reach(Object hurt);

    /** the final scheme's drain, NaN outside the scheme */
    float schemeDrain(Body b, Object hurt);

    /** whether this hit is on the target's head; one version read */
    boolean onHead(Body target);

    /** the target's current acid corrosion; one version read */
    float corrosion(Body b);

    /** the table's array as it is kept (BalanceGuns' cache), no new array: not to be written; one version read */
    float @Nullable [] floorExtra(Object hurt, boolean blast);

    /** NTM's piercing of this hit, DT or DR; one version read */
    float ntmPierce(Body target, int which);

    /** The current head multiplier. */
    float headMultiplier();

    /** where the version gives none: each method throws */
    HurtNow MISSING = new HurtNow() {
        @Override public boolean gas() { throw new UnsupportedOperationException("no HurtNow here"); }
        @Override public int reach(Object hurt) { throw new UnsupportedOperationException("no HurtNow here"); }
        @Override public float schemeDrain(Body b, Object hurt) { throw new UnsupportedOperationException("no HurtNow here"); }
        @Override public float corrosion(Body b) { throw new UnsupportedOperationException("no HurtNow here"); }
        @Override
        public boolean onHead(Body target) {
            throw new UnsupportedOperationException("no HurtNow here");
        }

        @Override
        public float @Nullable [] floorExtra(Object hurt, boolean blast) {
            throw new UnsupportedOperationException("no HurtNow here");
        }

        @Override
        public float ntmPierce(Body target, int which) {
            throw new UnsupportedOperationException("no HurtNow here");
        }
        @Override public float headMultiplier() { throw new UnsupportedOperationException("no HurtNow here"); }
    };
}
