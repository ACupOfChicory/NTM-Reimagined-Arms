package acup.gunfeel.host;

import org.jspecify.annotations.Nullable;

/** The hurt under way: each method is one version read, the port keeps nothing. */
public interface HurtNow {
    int PIERCE_DT = 0, PIERCE_DR = 1;

    /** whether this hit is on the target's head; one version read */
    boolean onHead(Body target);

    /** the table's array as it is kept (BalanceGuns' cache), no new array: not to be written; one version read */
    float @Nullable [] floorExtra(Object hurt, boolean blast);

    /** NTM's piercing of this hit, DT or DR; one version read */
    float ntmPierce(Body target, int which);

    /** where the version gives none: each method throws */
    HurtNow MISSING = new HurtNow() {
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
    };
}
