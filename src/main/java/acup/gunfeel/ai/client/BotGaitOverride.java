package acup.gunfeel.ai.client;

import java.util.function.IntFunction;

import org.jspecify.annotations.Nullable;

import net.minecraft.util.Mth;

/**
 * A mob's gait clips put in place of the mod's, by a stand (BotGalleryStand: the gait candidates side by side): going
 * ahead BotClips.WALK, JOG and SPRINT; going back BotClips.BACK; aside STRAFE_LEFT and STRAFE_RIGHT; crouched on the
 * move CROUCH_WALK. A null clip keeps the mod's own.
 *
 * @param walk        the walk's clip, a name for BotClips.get (such as "mocap/run_cmu16_45_lower")
 * @param walkSpeed   the clip's own pace, blocks a second (mocap.clips.json speed_m_s): played the mob's speed over it
 *                    times as fast, so its feet stand; 0: the mod's own tempo
 * @param drop        how far the hips go down, px (the clips' legs are made for it, 0.45); 0: as the mod has them
 * @param bare        no plant and no stoop's bent knees over the clip: its foot down is on the floor already
 * @param back        going back
 * @param left        going to its left; {@code right} to its right
 * @param crouch      crouched on the move (going back, played backwards, as the mod's): its legs are made for hips its
 *                    drop under a standing mob's, the trunk leaned over them from where vanilla's crouch has its top
 */
public record BotGaitOverride(@Nullable String walk, @Nullable String jog, @Nullable String sprint, float walkSpeed,
        float jogSpeed, float sprintSpeed, float drop, boolean bare, @Nullable Way back, @Nullable Way left,
        @Nullable Way right, @Nullable Way crouch) {
    /**
     * A clip for one way: its name for BotClips.get, its own pace (blocks a second, its clips.json speed_m_s; 0: the
     * mod's tempo) and how far its hips go down (px, its drop_px)
     */
    public record Way(String clip, float speed, float drop) {
    }

    /** by the mob's entity id; null for every mob out of a stand */
    public static volatile IntFunction<@Nullable BotGaitOverride> BY_ENTITY = id -> null;

    /** ahead only */
    public BotGaitOverride(@Nullable String walk, @Nullable String jog, @Nullable String sprint, float walkSpeed,
            float jogSpeed, float sprintSpeed, float drop, boolean bare) {
        this(walk, jog, sprint, walkSpeed, jogSpeed, sprintSpeed, drop, bare, null, null, null, null);
    }

    /** one clip for every pace ahead */
    public static BotGaitOverride all(String clip, float speed, float drop, boolean bare) {
        return new BotGaitOverride(clip, clip, clip, speed, speed, speed, drop, bare);
    }

    /** none put in (the mod's own every way) till withBack, withAside or withCrouch put one in */
    public static BotGaitOverride none(boolean bare) {
        return new BotGaitOverride(null, null, null, 0F, 0F, 0F, 0F, bare);
    }

    public BotGaitOverride withBack(@Nullable Way back) {
        return new BotGaitOverride(walk, jog, sprint, walkSpeed, jogSpeed, sprintSpeed, drop, bare, back, left, right, crouch);
    }

    public BotGaitOverride withAside(@Nullable Way left, @Nullable Way right) {
        return new BotGaitOverride(walk, jog, sprint, walkSpeed, jogSpeed, sprintSpeed, drop, bare, back, left, right, crouch);
    }

    public BotGaitOverride withCrouch(@Nullable Way crouch) {
        return new BotGaitOverride(walk, jog, sprint, walkSpeed, jogSpeed, sprintSpeed, drop, bare, back, left, right, crouch);
    }

    /** the clip aside, to the side it goes at this heading (as BotPoser.gaitClips: sin heading over nought its right) */
    public @Nullable Way side(float heading) {
        return Mth.sin(heading) > 0F ? right : left;
    }

    public static @Nullable BotGaitOverride of(int entity) {
        return BY_ENTITY.apply(entity);
    }
}
