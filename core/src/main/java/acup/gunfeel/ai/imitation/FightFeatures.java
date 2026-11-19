package acup.gunfeel.ai.imitation;

import java.util.List;

/**
 * The model's numbers: a view ({@link FightView}) as a row of numbers, about 0 to 1 each; and a cover spot as a row
 * of its own, for which one of a few to take. One code for the recorded player and for the bot asking, so the model
 * sees in a bot what it learned on: the recordings carry these rows as written then, and training (tools/imitation)
 * takes them from there.
 */
public final class FightFeatures {
    private FightFeatures() {
    }

    /** the view's numbers, in order (the recordings' header and the model file name them) */
    public static final List<String> NAMES = List.of("bias", "health", "loaded", "reloading", "crouching", "prone", "visible", "known", "nearest",
            "nearestSeesMe", "seeingMe", "sinceSeen", "inCover", "peeks", "sinceHurt", "coverLow", "coverHigh", "friends", "exposed", "exposedFor",
            "hiddenFor", "stillFor");

    public static final int SIZE = 22;

    /** blocks a distance is counted in */
    static final double FAR = 64;

    public static double[] of(FightView v) {
        double[] f = new double[SIZE];
        f[0] = 1;
        f[1] = clamp(v.health);
        f[2] = v.capacity > 0 && v.loaded >= 0 ? clamp(v.loaded / (double) v.capacity) : 0.5;
        f[3] = v.reloading ? 1 : 0;
        f[4] = v.crouching ? 1 : 0;
        f[5] = v.prone ? 1 : 0;
        f[6] = Math.min(4, v.visibleCount()) / 4.0;
        f[7] = Math.min(4, v.enemies.size()) / 4.0;
        FightView.Enemy near = v.enemies.isEmpty() ? null : v.enemies.get(0);
        f[8] = near == null ? 1 : clamp(Math.sqrt(dist2(v, near)) / FAR);
        f[9] = near != null && near.seesMe() ? 1 : 0;
        int seeing = 0;
        for (FightView.Enemy e : v.enemies) if (e.seesMe()) seeing++;
        f[10] = Math.min(4, seeing) / 4.0;
        f[11] = v.ticksSinceSeen / (double) FightHistory.LONG;
        f[12] = v.ticksInCover / (double) FightHistory.LONG;
        f[13] = Math.min(5, v.peeksFromCover) / 5.0;
        f[14] = v.ticksSinceHurt / (double) FightHistory.LONG;
        f[15] = v.coverHere == 1 ? 1 : 0;
        f[16] = v.coverHere == 2 ? 1 : 0;
        f[17] = Math.min(4, v.friendsNear) / 4.0;
        f[18] = v.exposed() ? 1 : 0;
        f[19] = v.ticksExposed / (double) FightHistory.LONG;
        // hidden this long running: a look out comes after a while hidden, whatever came before in that cover
        f[20] = v.ticksHidden / (double) FightHistory.LONG;
        f[21] = v.ticksStill / (double) FightHistory.LONG;
        return f;
    }

    /** a cover spot's numbers, in order */
    public static final List<String> SPOT_NAMES = List.of("place", "score", "away", "high", "hard", "peek", "toward", "aside", "fromThreat");

    public static final int SPOT_SIZE = 9;

    /**
     * A cover spot among those found (index: its place, best score first; best: the best score), seen from where the
     * fighter stands (self) against its first threat (tx, tz; NaN: none): where it lies, toward the threat or away, how
     * far aside, how far from it.
     */
    public static double[] spot(int index, double score, double best, int height, boolean hard, boolean peek, double sx, double sz, double cx, double cz,
            double tx, double tz) {
        double[] f = new double[SPOT_SIZE];
        f[0] = Math.min(7, index) / 7.0;
        f[1] = best > 1e-6 ? clamp(score / best) : 1;
        double dx = cx - sx, dz = cz - sz;
        f[2] = clamp(Math.hypot(dx, dz) / 16);
        f[3] = height >= 2 ? 1 : 0;
        f[4] = hard ? 1 : 0;
        f[5] = peek ? 1 : 0;
        if (Double.isNaN(tx)) return f;
        double ux = tx - sx, uz = tz - sz, len = Math.hypot(ux, uz);
        if (len < 1e-3) return f;
        ux /= len;
        uz /= len;
        // along the line to the threat (+: nearer it), across it
        f[6] = Math.max(-1, Math.min(1, (dx * ux + dz * uz) / 16));
        f[7] = clamp(Math.abs(dx * uz - dz * ux) / 16);
        f[8] = clamp(Math.hypot(tx - cx, tz - cz) / FAR);
        return f;
    }

    private static double dist2(FightView v, FightView.Enemy e) {
        double dx = e.x() - v.x, dy = e.y() - v.y, dz = e.z() - v.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double clamp(double x) {
        return x < 0 ? 0 : x > 1 ? 1 : x;
    }
}
