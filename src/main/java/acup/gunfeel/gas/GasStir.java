package acup.gunfeel.gas;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import acup.gunfeel.smoke.SmokeClouds;

/**
 * Bullets and blasts stir the gas clouds (server key {@code gasStir}, on by default); smoke has its own rule
 * ({@link acup.gunfeel.smoke.SmokeHole}: a blast's hole that closes, bullets nothing). The numbers:
 *
 * <ul>
 * <li>a bullet's path through a cloud: the cells it crosses keep 80% of their gas, the cells next to them 94%. One shot
 * leaves a faint trace; a burst of ten down one line clears a channel (0.8^10, about a tenth left) a block or so wide;
 * <li>a blast: within twice its reach the gas goes, all but 5% in the middle and less out to the edge (a quadratic fall
 * off). A GL-54 high-explosive grenade (reach 3) clears a ball of 6 blocks, some 60% of a chlorine grenade's cloud
 * three seconds after it burst; a creeper (power 3, reach 6) up to 12; the reach is at most 16, the cloud's own half
 * width and a little more.
 * </ul>
 *
 * What is taken is gone, not pushed aside: the cloud has nothing outside its cells to push it into.
 */
public final class GasStir {
    public static final String KEY = "gasStir";
    public static final float LINE_KEEP = 0.80F, LINE_KEEP_NEAR = 0.94F;
    public static final float BLAST_MOST = 0.95F;
    /** The gas's reach of a blast, times the blast's own reach. */
    public static final float BLAST_REACH = 2F;
    public static final float BLAST_REACH_MAX = 16F;

    private GasStir() {
    }

    public static boolean enabled() {
        return acup.gunfeel.ReaimTimes.flag(KEY, true);
    }

    private static boolean gas(acup.gunfeel.smoke.SmokeKind kind) {
        return GasKind.of(kind) != null;
    }

    /** A bullet moved from {@code from} to {@code to} this tick. */
    public static void bullet(ServerLevel level, Vec3 from, Vec3 to) {
        // a bullet that did not move (stuck after a hit) stirs nothing
        if (from == null || to == null || from.distanceToSqr(to) < 1e-6 || !enabled() || SmokeClouds.get(level) == null) return;
        SmokeClouds.stir(level, new SmokeClouds.Stir(0, false, from.x, from.y, from.z, to.x, to.y, to.z, LINE_KEEP, LINE_KEEP_NEAR), GasStir::gas);
    }

    /** A blast of this reach (where it hurts, in blocks) went off. */
    public static void blast(ServerLevel level, Vec3 at, float reach) {
        if (at == null || !(reach > 0F) || !enabled() || SmokeClouds.get(level) == null) return;
        float r = Math.min(BLAST_REACH_MAX, reach * BLAST_REACH);
        SmokeClouds.stir(level, new SmokeClouds.Stir(0, true, at.x, at.y, at.z, r, 0, 0, BLAST_MOST, 0F), GasStir::gas);
    }
}
