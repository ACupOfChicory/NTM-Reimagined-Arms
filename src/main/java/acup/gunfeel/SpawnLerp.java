package acup.gunfeel;

import net.minecraft.world.entity.Entity;

/**
 * A mob the client has just added keeps its previous position at 0 until its first tick, and a frame is drawn between
 * the previous position and the current one: before that tick it flies in from the world's origin. Vanilla sets the
 * previous position to the current one for an entity with tickCount 0 right before it draws it; CrankShaft's
 * Vanillate draws entities through Flywheel visuals and skips that line. {@link #settle} does the same as vanilla
 * when such a visual is made (MixinSpawnLerp). Once Vanillate does it itself, nothing is left to settle.
 */
public final class SpawnLerp {
    private static volatile boolean said;

    private SpawnLerp() {
    }

    /** Not ticked yet, and its previous position is not where it is. */
    static boolean stale(int tickCount, double ox, double oy, double oz, double x, double y, double z) {
        return tickCount == 0 && (ox != x || oy != y || oz != z);
    }

    public static void settle(Entity e) {
        if (e == null || !stale(e.tickCount, e.xOld, e.yOld, e.zOld, e.getX(), e.getY(), e.getZ())) return;
        e.xOld = e.getX();
        e.yOld = e.getY();
        e.zOld = e.getZ();
        if (!said) {
            said = true;
            DebugLog.LOGGER.info("A Flywheel visual was made for a just-added {} before its first tick: its previous "
                    + "position is set to its place, as vanilla does, so it does not fly in", e.getType());
        }
    }
}
