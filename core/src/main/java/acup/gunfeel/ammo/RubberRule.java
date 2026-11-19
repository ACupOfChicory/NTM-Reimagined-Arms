package acup.gunfeel.ammo;

/**
 * The rubber slug's numbers and its plain arithmetic (no Minecraft: the tests drive it). Decided with the owner
 * 2026-11-17 (notes: the new ammo).
 */
public final class RubberRule {
    private RubberRule() {
    }

    /** the hit on one with no armour, HP (before the part hit's multiplier); armour takes its share as of any bullet */
    public static final float DAMAGE = 6F;
    /** the slug never takes anyone's health below this, HP */
    public static final float FLOOR = 4F;
    /** a player's and an AI bot's slowing: the share of the speed, and for how long (game ticks) */
    public static final float SLOW = 0.6F;
    public static final int SLOW_TICKS = 30;
    /** a player's controls mixed up (forward and back, left and right, jump and sneak), an AI bot's aim off: game ticks */
    public static final int CONFUSE_TICKS = 80;
    /** an undead's fear of the shooter, and the immunity to it after (game ticks) */
    public static final int FEAR_TICKS = 80, FEAR_IMMUNE_TICKS = 200;
    /** how far a frightened undead looks for a place away from the shooter, blocks */
    public static final int FEAR_RANGE = 16;
    /** the push along the shot (LivingEntity.knockback's strength): about 3 blocks up to NEAR, about 1.5 from FAR on */
    public static final float KNOCK_NEAR = 0.4F, KNOCK_FAR = 0.2F;
    public static final double NEAR = 10, FAR = 25;

    /** the most a hit of {@code damage} may take off one at {@code health}: down to FLOOR, nothing below it */
    public static float floored(float damage, float health) {
        if (!(damage > 0F)) return damage;
        return Math.max(0F, Math.min(damage, health - FLOOR));
    }

    /** the push's strength at {@code distance} blocks from the shooter */
    public static float knock(double distance) {
        if (!(distance > NEAR)) return KNOCK_NEAR;
        if (distance >= FAR) return KNOCK_FAR;
        return (float) (KNOCK_NEAR + (KNOCK_FAR - KNOCK_NEAR) * (distance - NEAR) / (FAR - NEAR));
    }

    /**
     * a player's keys as the confusion has them: forward and back, left and right, jump and sneak swapped; sprint as
     * it is. {forward, backward, left, right, jump, shift}
     */
    public static boolean[] swapped(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean shift) {
        return new boolean[] {backward, forward, right, left, shift, jump};
    }

    /** the yaw's and the pitch's swing of an AI bot's aim, degrees at most */
    static final float YAW_A = 8F, YAW_B = 3F, PITCH_A = 3F;
    /** the swings' speeds, radians a tick: at most YAW_A·W_A + YAW_B·W_B ≈ 15 degrees a second all told */
    static final float W_A = 0.05F, W_B = 0.12F;
    /** the swing comes in and goes over these ticks at either end (no snap) */
    static final int EASE = 10;

    /**
     * An AI bot's aim off by the confusion {@code t} ticks into it ({@code phase}: the bot's own, so two bots do not swing
     * alike): {yaw, pitch} degrees, 0 outside the confusion.
     */
    public static float[] drift(long t, float phase) {
        if (t < 0 || t >= CONFUSE_TICKS) return new float[] {0F, 0F};
        float k = (float) Math.min(1.0, Math.min(t, CONFUSE_TICKS - t) / (double) EASE);
        k = k * k * (3F - 2F * k);
        float yaw = YAW_A * (float) Math.sin(W_A * t + phase) + YAW_B * (float) Math.sin(W_B * t + 2.3F * phase);
        float pitch = PITCH_A * (float) Math.sin(W_A * 1.3F * t + 0.7F * phase);
        return new float[] {k * yaw, k * pitch};
    }
}
