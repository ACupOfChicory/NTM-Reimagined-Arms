package acup.gunfeel.smoke;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import acup.gunfeel.gas.GasKind;

/**
 * A blast punches a hole in a smoke cloud that closes again (server key {@code smokeHole}, on by default); bullets leave
 * smoke as it is. As it is in the field: a burst pushes the smoke out for a moment and it flows back in from the edge,
 * the smoke is not gone (a screen is fired with high explosive and smoke shells together, US Army FM 3-50).
 *
 * <ul>
 * <li>the hole: within one and a half times the blast's reach (where it hurts) all but 5% of the smoke goes in the middle,
 * less out to the edge. A GL-54 high-explosive grenade (reach 3) opens one of 4.5 blocks, a third of a smoke grenade's
 * screen (some 6.5 blocks across its half sphere); a creeper (reach 6) 9, the whole of it; the hole is at most 12. It is
 * smaller than the gas a blast takes ({@code GasStir}, twice the reach): there a blast's wind leaves nothing that
 * would come back, here a smaller hole that closes;
 * <li>it closes: the smoke comes back from the edge at once and in the middle after 1.5 s, the hole is gone in 2 to 3 s
 * ({@link SmokeGrid#BACK_DELAY}, {@link SmokeGrid#BACK_RATE}).
 * </ul>
 */
public final class SmokeHole {
    public static final String KEY = "smokeHole";
    public static final float MOST = 0.95F;
    /** The hole's reach, times the blast's own reach. */
    public static final float REACH = 1.5F;
    public static final float REACH_MAX = 12F;

    private SmokeHole() {
    }

    public static boolean enabled() {
        return acup.gunfeel.ReaimTimes.flag(KEY, true);
    }

    /** A blast of this reach (where it hurts, in blocks) went off. */
    public static void blast(ServerLevel level, Vec3 at, float reach) {
        if (at == null || !(reach > 0F) || !enabled() || SmokeClouds.get(level) == null) return;
        float r = Math.min(REACH_MAX, reach * REACH);
        SmokeClouds.stir(level, new SmokeClouds.Stir(0, true, at.x, at.y, at.z, r, 0, 0, MOST, 1F), k -> GasKind.of(k) == null);
    }
}
