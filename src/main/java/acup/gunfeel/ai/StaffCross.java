package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

/**
 * Where a squad's way over the staff's map crosses water (the crossing's doctrine: the staff names the place and the
 * way over, the squad goes over by the drill, AiCrossing). The way is the staff's own (HqGrid.route): by a bridge or
 * dry ground where there is one, else by a ford (WADE), swimming last (SWIM), out of the enemy's sight and up a gentle
 * bank where it can; so its first stretch in water is the place. From it: the near bank's point at the water, how it
 * goes over, and where to gather a little past the far bank.
 */
final class StaffCross {
    /** along the way, a look this often, blocks */
    static final double STEP = 1;
    /** the gathering place this far past the far bank's first dry ground, blocks */
    static final double PAST = 2;

    record Site(double x, double z, AiCrossing.Kind kind, double farX, double farZ) {
    }

    private StaffCross() {
    }

    /** the first water the way crosses (wading or swimming), null: none on it (or no map under it) */
    static @Nullable Site site(HqGrid grid, HqGrid.Route r) {
        double px = r.fromX(), pz = r.fromZ();
        // the last dry look before the water; the water's start; swum anywhere in it
        double dryX = px, dryZ = pz;
        boolean in = false, swims = false;
        for (int j = 0; j < r.size(); j++) {
            double qx = r.x(j), qz = r.z(j), len = Math.hypot(qx - px, qz - pz);
            int n = Math.max(1, (int) Math.ceil(len / STEP));
            for (int s = 1; s <= n; s++) {
                double x = px + (qx - px) * s / n, z = pz + (qz - pz) * s / n;
                int w = water(grid, x, z);
                if (w > 0) {
                    in = true;
                    if (w == 2) swims = true;
                } else if (in) {
                    // the far bank: on a little along the way
                    double dx = (qx - px) / Math.max(1e-9, len), dz = (qz - pz) / Math.max(1e-9, len);
                    return new Site(dryX, dryZ, swims ? AiCrossing.Kind.SWIM : AiCrossing.Kind.FORD, x + dx * PAST, z + dz * PAST);
                } else {
                    dryX = x;
                    dryZ = z;
                }
            }
            px = qx;
            pz = qz;
        }
        return null;
    }

    /** 0 dry (or not known), 1 water to wade, 2 water to swim */
    static int water(HqGrid grid, double x, double z) {
        int i = grid.cellX(x), k = grid.cellZ(z);
        if (i < 0 || k < 0) return 0;
        int c = HqGrid.index(i, k);
        if (!grid.known[c]) return 0;
        return grid.deep[c] >= 0.5 ? 2 : grid.wet[c] >= 0.5 ? 1 : 0;
    }
}
