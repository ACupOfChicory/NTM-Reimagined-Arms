package acup.gunfeel;

/**
 * The balance's armour formula C, without the game (BalanceArmor applies it), as the balance model has it
 * ({@code notes/balance/work/scripts/old/variants.py}: {@code det_pellet}, {@code b_of}, {@code d_of}, {@code Fc}).
 *
 * <p>One hit of damage {@code D}, its ammo's threshold negation {@code neg} and armour piercing {@code pen}, on a full
 * set of rating {@code S} and tier {@code tau}:
 * <ul>
 * <li>{@code rho = (D + neg)·(1 + pen) / S};</li>
 * <li>the share that gets through {@code m = b + (1 − d − b)·rho³/(1 + rho³)}: never below {@code b} (no hit is ever
 * nothing), never above {@code 1 − d};</li>
 * <li>what is left {@code F(D)·m}, where {@code F} squeezes the flesh's damage of one hit: as it is up to the knee
 * (8), then towards the most (14), never past it.</li>
 * </ul>
 * A set that is not whole: each piece adds its share (diamond's 3/8/6/3 of 20) of its own set's result, the rest of the
 * body takes what NTM gives (see {@link #blend}).
 */
public final class BalanceArmorRule {
    /** the formula's exponent (C_Q) */
    public static final double Q = 3.0;
    /** the shares of the pieces, by slot: helmet, chestplate, leggings, boots (diamond's armour points over 20) */
    public static final float[] PIECE = {3F / 20F, 8F / 20F, 6F / 20F, 3F / 20F};

    private BalanceArmorRule() {
    }

    /** the least share a hit keeps, by tier (b_of) */
    public static double b(double tau) {
        double[][] pts = {{0.5, .30}, {1, .25}, {2, .18}, {3, .13}, {4, .10}, {5, .07}, {6, .05}, {7, .035}, {8, .025}};
        if (tau <= pts[0][0]) return pts[0][1];
        for (int i = 0; i + 1 < pts.length; i++) {
            double a = pts[i][0], x = pts[i][1], c = pts[i + 1][0], y = pts[i + 1][1];
            if (tau <= c) return x + (y - x) * (tau - a) / (c - a);
        }
        return pts[pts.length - 1][1];
    }

    /** the share a set takes off even the strongest hit, by tier (d_of) */
    public static double d(double tau) {
        return Math.max(0.0, Math.min(0.20, 0.06 + 0.02 * (tau - 1)));
    }

    /** the flesh's squeeze of one hit (Fc): {@code d} up to {@code knee}, then towards {@code most} */
    public static double flesh(double damage, double knee, double most) {
        if (damage <= knee) return damage;
        if (most <= knee) return knee;
        return knee + (most - knee) * (1 - Math.exp(-(damage - knee) / (most - knee)));
    }

    /** the share that gets through: {@code b + (1 − d − b)·rho³/(1 + rho³)} */
    public static double share(double rho, double b, double d) {
        double r = Math.pow(Math.max(0.0, rho), Q);
        double h = Double.isInfinite(r) ? 1.0 : r / (1 + r);
        return b + (1 - d - b) * h;
    }

    /** the numbers of one hit */
    public record Hit(float damage, float neg, float pen, boolean squeeze) {
    }

    /** the formula's numbers that are not the set's */
    public record Tune(float knee, float most) {
        public static final Tune DEFAULT = new Tune(8F, 14F);
    }

    /** what a full set of rating {@code s} and tier {@code tau} leaves of the hit */
    public static float full(Hit hit, double s, double tau, Tune tune) {
        return full(hit, s, tau, tune, 1.0);
    }

    /**
     * the same with the least share {@code b·floor} (the gun table's floor of the shot's pair; at most {@code 1 − d}):
     * {@code m = fl + (1 − d − fl)·rho³/(1 + rho³)}, {@code fl = min(1 − d, b·floor)}
     */
    public static float full(Hit hit, double s, double tau, Tune tune, double floor) {
        double dmg = hit.damage();
        if (!(dmg > 0)) return hit.damage();
        if (!(s > 0)) return hit.damage();
        double power = (dmg + Math.max(0F, hit.neg())) * Math.max(0.0, 1.0 + hit.pen());
        double dd = d(tau);
        double fl = floor > 0 && Double.isFinite(floor) ? Math.min(1 - dd, b(tau) * floor) : b(tau);
        double m = share(power / s, fl, dd);
        double flesh = hit.squeeze() ? flesh(dmg, tune.knee(), tune.most()) : dmg;
        return (float) (flesh * m);
    }

    /**
     * A body of pieces: {@code byPiece[i]} what piece i's own set would leave of the hit as a full set ({@code NaN}: the
     * piece is not of a set in the table, or the slot is empty), {@code rest} what NTM leaves of it. The pieces in the
     * table each take their share, the rest of the body NTM's.
     */
    public static float blend(float[] byPiece, float rest) {
        double out = 0, covered = 0;
        for (int i = 0; i < byPiece.length && i < PIECE.length; i++) {
            if (Float.isNaN(byPiece[i])) continue;
            out += PIECE[i] * byPiece[i];
            covered += PIECE[i];
        }
        out += Math.max(0.0, 1.0 - covered) * rest;
        return (float) out;
    }

    /** the damage types of NTM's shots (BulletConfig.getDamage by the round's class, the older projectiles' own) */
    static final java.util.Set<String> SHOT_TYPES = java.util.Set.of("physical", "laser", "plasma", "microwave", "subatomic", "electric", "tau",
            "revolver_bullet", "shrapnel", "combine", "emplacer");

    /**
     * A hit of damage type {@code namespace:path} is an NTM shot's, the formula's: one of a shot's types, and a physical
     * one not dealt by a creature itself (NTM calls a gun's own blow and Lag's suicide physical from the player; a
     * bullet's comes from the bullet). An energy one may come from the shooter: the Tesla Cannon's blast names its
     * exploder. Melee, a mob's blow, vanilla's arrows, fire and the rest are NTM's numbers.
     */
    public static boolean shotType(String namespace, String path, boolean byCreature) {
        if (!"hbm".equals(namespace) || !SHOT_TYPES.contains(path)) return false;
        return !path.equals("physical") || !byCreature;
    }
}
