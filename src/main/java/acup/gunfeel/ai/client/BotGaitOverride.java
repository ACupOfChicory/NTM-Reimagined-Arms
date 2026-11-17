package acup.gunfeel.ai.client;

import java.util.function.IntFunction;

import org.jspecify.annotations.Nullable;

/**
 * A mob's gait clips put in place of BotClips.WALK, JOG and SPRINT, by a stand (BotGalleryStand: the gait candidates
 * side by side). A null clip keeps the mod's own.
 *
 * @param walk        the walk's clip, a name for BotClips.get (such as "mocap/run_cmu16_45_lower")
 * @param walkSpeed   the clip's own pace, blocks a second (mocap.clips.json speed_m_s): played the mob's speed over it
 *                    times as fast, so its feet stand; 0: the mod's own tempo
 * @param drop        how far the hips go down, px (the clips' legs are made for it, 0.45); 0: as the mod has them
 * @param bare        no plant and no stoop's bent knees over the clip: its foot down is on the floor already
 */
public record BotGaitOverride(@Nullable String walk, @Nullable String jog, @Nullable String sprint, float walkSpeed,
        float jogSpeed, float sprintSpeed, float drop, boolean bare) {
    /** by the mob's entity id; null for every mob out of a stand */
    public static volatile IntFunction<@Nullable BotGaitOverride> BY_ENTITY = id -> null;

    /** one clip for every pace */
    public static BotGaitOverride all(String clip, float speed, float drop, boolean bare) {
        return new BotGaitOverride(clip, clip, clip, speed, speed, speed, drop, bare);
    }

    public static @Nullable BotGaitOverride of(int entity) {
        return BY_ENTITY.apply(entity);
    }
}
