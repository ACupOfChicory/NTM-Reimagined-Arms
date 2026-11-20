package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

/** A squad's movement state shared by the versions. */
public abstract class StaffMoveBase {
    protected StaffMoveBase() {
    }

    enum Mode {
        MARCH, BOUND, CREEP, ASSAULT
    }

    /** a squad's (its task's): its mode and since when, the nearest enemy, its bounds or rushes */
    static final class State {
        @Nullable Mode mode;
        long since, next = Long.MIN_VALUE;
        /** the nearest enemy the side knows of: where it faces it from cover, the radius of its circle (none known: false) */
        double foeX, foeZ, foeR;
        boolean foe;
        /** bounds and rushes begun in this mode; the half going (bounds) or the first rushing (creeping); since when; where along the way the bound ends */
        int turn, moving;
        long turnAt;
        double to;
        /** bounds: the half covering is in its cover (or had its time to get there), the other goes */
        boolean set;
    }
}
