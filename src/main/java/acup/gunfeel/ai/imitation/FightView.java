package acup.gunfeel.ai.imitation;

import java.util.List;

/**
 * What a fighter has and sees at a moment, in plain numbers: the same for a player whose fight is recorded and for a
 * bot that asks what to do ({@link FightLook} fills it from the world, {@link FightFeatures} turns it into the
 * model's numbers). Positions are the feet.
 */
public final class FightView {
    /**
     * An enemy it knows of: where it is (seen now) or was last seen; whether it sees it now; whether a line from its
     * eyes reaches its head or chest (exposedTo) and it looks its way (seesMe).
     */
    public record Enemy(int id, double x, double y, double z, boolean visible, boolean exposedTo, boolean seesMe) {
    }

    public long tick;
    public double x, y, z;
    public float yaw, pitch;
    /** health as a part of the most */
    public float health = 1;
    /** rounds in the gun, its magazine's size; -1, 0: no gun whose rounds are counted */
    public int loaded = -1, capacity;
    public boolean reloading, crouching, prone, sprinting;
    /** it fired since the last view (its rounds went down) */
    public boolean fired;
    /** it was hurt since the last view (its health went down) */
    public boolean hurt;
    /** the nearest first */
    public List<Enemy> enemies = List.of();
    public int friendsNear;
    /** cover where it stands from the nearest enemy: 0 none, 1 low (head seen, legs not), 2 high (head hidden) */
    public int coverHere;
    /** ticks since it was last hurt by a mob or saw an enemy, as the world knows it (-1: not known, the history counts) */
    public int hurtAgo = -1, seenAgo = -1;
    /** from its history (FightHistory) */
    public int ticksSinceSeen = FightHistory.LONG, ticksInCover, peeksFromCover, ticksSinceHurt = FightHistory.LONG, ticksExposed,
            ticksHidden;

    public boolean exposed() {
        for (Enemy e : enemies) if (e.exposedTo) return true;
        return false;
    }

    public int visibleCount() {
        int n = 0;
        for (Enemy e : enemies) if (e.visible) n++;
        return n;
    }

    /** in cover: some cover where it stands, and no enemy's eyes on it */
    public boolean hidden() {
        return coverHere > 0 && !exposed() && !enemies.isEmpty();
    }
}
