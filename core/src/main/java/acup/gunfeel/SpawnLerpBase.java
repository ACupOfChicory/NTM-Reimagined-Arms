package acup.gunfeel;

// Whether a mob just added flies in from the world's origin (SpawnLerp in 26.2 settles the game's entity)
public class SpawnLerpBase {
    SpawnLerpBase() {
    }

    /** Not ticked yet, and its previous position is not where it is. */
    static boolean stale(int tickCount, double ox, double oy, double oz, double x, double y, double z) {
        return tickCount == 0 && (ox != x || oy != y || oz != z);
    }
}
