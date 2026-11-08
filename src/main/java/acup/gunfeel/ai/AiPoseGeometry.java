package acup.gunfeel.ai;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Where an AI mob's body is when it leans out (notes/ai/pose-contract.md): one formula for the server (the eye
 * Senses looks from, the point NTM's bullet leaves, the fallback head and shoulder box) and the client (the parts
 * of the model in setupAnim), so the shot leaves where the gun is drawn.
 *
 * <p>A lean steps the hips aside by {@link #LEAN_SHIFT} × the lean, then tips everything above the hips around the
 * body's forward line by {@link #LEAN_DEG} × the lean; + is to the mob's right. The hips are where HumanoidModel has
 * the legs' pivot: 12 px above the feet, 4 px back crouching (26.2's crouch moves the legs back, not down: javap of
 * HumanoidModel.setupAnim). Common code only, no client classes.
 */
public final class AiPoseGeometry {
    /** how far the body tips at a full lean, degrees */
    public static final float LEAN_DEG = 25F;
    /** how far the hips step aside at a full lean, blocks */
    public static final double LEAN_SHIFT = 0.25;
    /** ticks from one lean to the next */
    public static final float LEAN_TICKS = 4F;
    /** HumanoidModel's feet are 1.501 blocks below the model's origin (LivingEntityRenderer) */
    static final double MODEL_FEET = 1.501;
    /** the legs' pivot, model pixels: standing, and in vanilla's crouch */
    static final double HIP_PX = 12.0, CROUCH_HIP_PX = 12.0, CROUCH_HIP_BACK_PX = 4.0;

    /** blind fire's arms (BotPoser): both straight up over the head, each turned in toward the middle by this */
    public static final float BLIND_ARM_X = (float) (-Math.PI + 0.2), BLIND_ARM_Y = 0.1F;
    /** the arms' pivot (HumanoidModel), and how far vanilla's crouch lowers it, model pixels */
    public static final float ARM_PIVOT_X = 5F, ARM_PIVOT_Y = 2F, CROUCH_ARM_DROP_PX = 3.2F;
    /**
     * Where the item in a hand sits, in the arm's own pixels: ItemInHandLayer (26.2) turns X −90°, Y 180° from the arm
     * and moves (±1, 2, −10)/16, so the grip is 10 px down the arm, 2 px in front, 1 px to the outside (right arm)
     */
    static final double GRIP_OUT_PX = 1.0, GRIP_DOWN_PX = 10.0, GRIP_FRONT_PX = 2.0;

    private AiPoseGeometry() {
    }

    /** The lean now, −1 (left) .. 1 (right): from prevLean to lean in {@link #LEAN_TICKS} ticks after leanTick. */
    public static float leanFrac(AiPose s, long now, float partial) {
        float t = Mth.clamp((now - s.leanTick() + partial) / LEAN_TICKS, 0F, 1F);
        return Mth.lerp(t, s.prevLean() / 100F, s.lean() / 100F);
    }

    /** The hips above the feet, blocks, and how far they are behind the feet (crouching). */
    static double hipUp(byte stance) {
        return MODEL_FEET - (stance == AiPose.CROUCH ? CROUCH_HIP_PX : HIP_PX) / 16.0;
    }

    /**
     * A point of the upright body, given in the mob's own frame (right, up, forward; blocks from its feet), where it
     * is with the body leaned by {@code frac}, in the world's axes (an offset from the mob's position). Points below
     * the hips (the legs) only step aside; points above also tip.
     */
    public static Vec3 leanPoint(double right, double up, double forward, byte stance, float frac, float yBodyRot) {
        double hu = hipUp(stance);
        double r = right + frac * LEAN_SHIFT, u = up;
        if (up > hu && frac != 0F) {
            // tip around the forward line through the hips (how far back they are does not matter for a turn
            // around a forward line): up turns toward right for + frac
            double a = Math.toRadians(frac * LEAN_DEG), c = Math.cos(a), s = Math.sin(a);
            double du = up - hu;
            r = frac * LEAN_SHIFT + right * c + du * s;
            u = hu - right * s + du * c;
        }
        return toWorld(r, u, forward, yBodyRot);
    }

    /** The eye, an offset from the mob's position: {@code eyeHeight} is the mob's unleaned eye (getEyeHeight). */
    public static Vec3 eyeOffset(byte stance, float frac, float yBodyRot, double eyeHeight) {
        return leanPoint(0, eyeHeight, 0, stance, frac, yBodyRot);
    }

    /**
     * Where a shot leaves: {@code muzzle} is the unleaned point (the mob's frame: right, up, forward, blocks from its
     * feet — for NTM, the eye), moved with the lean.
     */
    public static Vec3 muzzleOffset(byte stance, float frac, float yBodyRot, Vec3 muzzle) {
        return leanPoint(muzzle.x, muzzle.y, muzzle.z, stance, frac, yBodyRot);
    }

    /**
     * Where a mob firing blind over cover ({@link AiPose#BLIND}) has its gun, as the model draws it once the arms are
     * up: the hand holding it, high over its head (1.81 blocks crouching, against 1.27 for its eye), moved with the
     * lean. An offset from the mob's position, as {@link #muzzleOffset}. Not the barrel's end: each gun's model is
     * its own, the grip is where every gun is. The drawn hand also breathes (up to ~4 cm, standing still) and kicks
     * back with each shot (up to 0.14 blocks, for a moment): this is the pose they move around.
     *
     * @param leftHanded the mob's main hand is its left (Mob.isLeftHanded): the gun is in the left hand
     */
    public static Vec3 blindMuzzle(byte stance, float frac, float yBodyRot, boolean leftHanded) {
        // the right arm in model pixels (x to the mob's left, y down, z back); the left one is its mirror
        double side = leftHanded ? -1 : 1;
        double ax = BLIND_ARM_X, ay = -BLIND_ARM_Y * side;
        double gx = -GRIP_OUT_PX * side, gy = GRIP_DOWN_PX, gz = -GRIP_FRONT_PX;
        // ModelPart: the arm's pivot, then Rz (none) Ry Rx on the point
        double y1 = gy * Math.cos(ax) - gz * Math.sin(ax), z1 = gy * Math.sin(ax) + gz * Math.cos(ax);
        double x2 = gx * Math.cos(ay) + z1 * Math.sin(ay), z2 = -gx * Math.sin(ay) + z1 * Math.cos(ay);
        double px = -ARM_PIVOT_X * side + x2;
        double py = ARM_PIVOT_Y + (stance == AiPose.CROUCH ? CROUCH_ARM_DROP_PX : 0) + y1;
        return leanPoint(-px / 16.0, MODEL_FEET - py / 16.0, -z2 / 16.0, stance, frac, yBodyRot);
    }

    /** The mob's frame (right, up, forward) to the world's axes for a body turned yBodyRot (Minecraft yaw). */
    static Vec3 toWorld(double right, double up, double forward, float yBodyRot) {
        double y = Math.toRadians(yBodyRot), sin = Math.sin(y), cos = Math.cos(y);
        // forward = (-sin, 0, cos), right = (-cos, 0, -sin)
        return new Vec3(-forward * sin - right * cos, up, forward * cos - right * sin);
    }

    /** The lean in the model's own terms, for setupAnim: the parts above the hips turn by this around model Z. */
    public static float modelRollRad(float frac) {
        return (float) Math.toRadians(-frac * LEAN_DEG);
    }

    /** ... and every part moves this far along model X (model +X is the mob's left), pixels. */
    public static float modelShiftPx(float frac) {
        return (float) (-frac * LEAN_SHIFT * 16.0);
    }

    /** The hips' pivot in model pixels (y down, z back), where the upper parts turn around. */
    public static float modelHipY(byte stance) {
        return (float) (stance == AiPose.CROUCH ? CROUCH_HIP_PX : HIP_PX);
    }

    public static float modelHipZ(byte stance) {
        return (float) (stance == AiPose.CROUCH ? CROUCH_HIP_BACK_PX : 0.0);
    }
}
