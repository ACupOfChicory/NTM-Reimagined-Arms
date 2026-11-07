package acup.gunfeel.ai;

/**
 * How one kind of AI mob senses and remembers. Internal for now (the owner's word: the system first, the public
 * config for modpack makers later); times in ticks, distances in blocks.
 *
 * @param viewRange    the farthest it sees a target in the open
 * @param fov          its field of view across, in degrees
 * @param hearing      times the distance a sound carries for a player
 * @param reaction     ticks a target must stay in sight before the mob knows it is there (close, straight ahead)
 * @param reactionFar  ticks added per 16 blocks of distance
 * @param hold         after losing a target: ticks it waits for it where it went out of sight
 * @param search       ticks it then searches around that point
 * @param forget       ticks after which a lost target is forgotten for good
 * @param searchSpeed  how fast the area a lost target can be in grows, blocks per second
 * @param period       ticks between two looks around
 */
public record AiProfile(double viewRange, double fov, double hearing, int reaction, int reactionFar, int hold, int search,
        int forget, double searchSpeed, int period) {
    /** a soldier: FBI agents and NTM's test dummies */
    public static final AiProfile SOLDIER = new AiProfile(96, 140, 1.0, 8, 4, 160, 600, 1800, 4.0, 4);

    public double cosHalfFov() {
        return Math.cos(Math.toRadians(fov / 2));
    }

    /** ticks to notice a target at a distance, more in the corner of the eye */
    public int reactionTicks(double distance, boolean corner) {
        int t = reaction + (int) Math.round(reactionFar * distance / 16.0);
        return corner ? t + t / 2 : t;
    }
}
