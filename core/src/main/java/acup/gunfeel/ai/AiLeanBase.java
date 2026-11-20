package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;

import acup.gunfeel.GameMath;
import acup.gunfeel.ModelBox;
import acup.gunfeel.Vec3d;

/** Lean geometry shared by the server and the drawn pose. */
public abstract class AiLeanBase {
    /** the step toward the corner before it leans, blocks */
    static final double STEP = 0.25;
    /** the lean a look round a corner takes, of 100 */
    static final byte FULL = 100;
    /** off only for the stands' "before": a look out is a step aside */
    static boolean on = true;

    /** how far the body tips at a full lean, degrees */
    public static final float LEAN_DEG = 25F;
    /** how far the hips step aside at a full lean, blocks */
    public static final double LEAN_SHIFT = 0.25;
    /** ticks from one lean to the next */
    public static final float LEAN_TICKS = 4F;
    /** HumanoidModel's feet are 1.501 blocks below the model's origin (LivingEntityRenderer) */
    static final double MODEL_FEET = 1.501;
    /** the legs' pivot, model pixels: standing, and in vanilla's crouch */
    static final double HIP_PX = 12.0, CROUCH_HIP_PX = 12.0;

    /** Builds the caller's vector directly, without an intermediate vector. */
    @FunctionalInterface
    public interface Vec3Maker<T> {
        T make(double x, double y, double z);
    }

    private static final Vec3Maker<Vec3d> VECTOR = Vec3d::new;

    protected AiLeanBase() {
    }

    /**
     * A box of the bot's model of ten parts (BotModel: head and hat, body, upper arms and forearms, thighs and shins;
     * a forearm's and a shin's second box is the overlap inside the upper part), as ModelHits keeps them for a client's
     * model: its centre and edges, model px (16 a block) from the feet, facing +z, the bot's right -x (notes: d7's
     * dump, BotModel at arms HOLD 1 or AIM 1, upright).
     */
    private record Part(String name, double cx, double cy, double cz, double ux, double uy, double uz, double vx, double vy, double vz, double wx, double wy,
            double wz) {
    }

    private static final List<Part> HOLDING = List.of(
            new Part("root/head", 0, 28.016, 0, 8, 0, 0, 0, -8, 0, 0, 0, -8),
            new Part("root/head/hat", 0, 28.016, 0, 9, 0, 0, 0, -9, 0, 0, 0, -9),
            new Part("root/body", 0, 18.016, 0, 8, 0, 0, 0, -12, 0, 0, 0, -4),
            new Part("root/right_arm", -6.095, 21.121, 0, 3.98, -0.399, 0, -0.599, -5.97, 0, 0, 0, -4),
            new Part("root/right_arm/forearm", -6.694, 15.151, 0, 3.98, -0.399, 0, -0.599, -5.97, 0, 0, 0, -4),
            new Part("root/right_arm/forearm", -6.295, 19.131, 0, 3.96, -0.397, 0, -0.198, -1.97, 0, 0, 0, -3.98),
            new Part("root/left_arm", 6.095, 21.121, 0, 3.98, 0.399, 0, 0.599, -5.97, 0, 0, 0, -4),
            new Part("root/left_arm/forearm", 6.694, 15.151, 0, 3.98, 0.399, 0, 0.599, -5.97, 0, 0, 0, -4),
            new Part("root/left_arm/forearm", 6.295, 19.131, 0, 3.96, 0.397, 0, 0.198, -1.97, 0, 0, 0, -3.98),
            new Part("root/right_leg", -1.915, 9.016, 0, 4, -0.02, 0.02, -0.03, -6, 0, 0.02, 0, -4),
            new Part("root/right_leg/shin", -1.945, 3.016, 0, 4, -0.02, 0.02, -0.03, -6, 0, 0.02, 0, -4),
            new Part("root/right_leg/shin", -1.925, 7.016, 0, 3.98, -0.02, 0.02, -0.01, -1.98, 0, 0.02, 0, -3.98),
            new Part("root/left_leg", 1.915, 9.016, 0, 4, 0.02, -0.02, 0.03, -6, 0, -0.02, 0, -4),
            new Part("root/left_leg/shin", 1.945, 3.016, 0, 4, 0.02, -0.02, 0.03, -6, 0, -0.02, 0, -4),
            new Part("root/left_leg/shin", 1.925, 7.016, 0, 3.98, 0.02, -0.02, 0.01, -1.98, 0, -0.02, 0, -3.98));
    private static final List<Part> AIMING = List.of(
            new Part("root/head", 0, 28.016, 0, 8, 0, 0, 0, -8, 0, 0, 0, -8),
            new Part("root/head/hat", 0, 28.016, 0, 9, 0, 0, 0, -9, 0, 0, 0, -9),
            new Part("root/body", 0, 18.016, 0, 8, 0, 0, 0, -12, 0, 0, 0, -4),
            new Part("root/right_arm", -5.895, 22.016, 1.095, 3.98, 0, -0.399, 0.599, 0, 5.97, 0, -4, 0),
            new Part("root/right_arm/forearm", -5.296, 22.016, 7.065, 3.98, 0, -0.399, 0.599, 0, 5.97, 0, -4, 0),
            new Part("root/right_arm/forearm", -5.696, 22.016, 3.085, 3.96, 0, -0.397, 0.198, 0, 1.97, 0, -3.98, 0),
            new Part("root/left_arm", 5.398, 22.016, 1.357, 3.51, 0, 1.918, -2.877, 0, 5.265, 0, -4, 0),
            new Part("root/left_arm/forearm", 2.522, 22.016, 6.623, 3.51, 0, 1.918, -2.877, 0, 5.265, 0, -4, 0),
            new Part("root/left_arm/forearm", 4.439, 22.016, 3.112, 3.493, 0, 1.908, -0.949, 0, 1.738, 0, -3.98, 0),
            new Part("root/right_leg", -1.915, 9.016, 0, 4, -0.02, 0.02, -0.03, -6, 0, 0.02, 0, -4),
            new Part("root/right_leg/shin", -1.945, 3.016, 0, 4, -0.02, 0.02, -0.03, -6, 0, 0.02, 0, -4),
            new Part("root/right_leg/shin", -1.925, 7.016, 0, 3.98, -0.02, 0.02, -0.01, -1.98, 0, 0.02, 0, -3.98),
            new Part("root/left_leg", 1.915, 9.016, 0, 4, 0.02, -0.02, 0.03, -6, 0, -0.02, 0, -4),
            new Part("root/left_leg/shin", 1.945, 3.016, 0, 4, 0.02, -0.02, 0.03, -6, 0, -0.02, 0, -4),
            new Part("root/left_leg/shin", 1.925, 7.016, 0, 3.98, 0.02, -0.02, 0.01, -1.98, 0, -0.02, 0, -3.98));

    /** px of the model a block */
    private static final double PX = 16;

    /**
     * The model's boxes for a standing mob at this lean, as ModelHits keeps them (from its position, world axes): each
     * box moved whole the way its centre moves (above the hips the lean tips it, below it stays), so a thigh's top
     * does not tip with the body.
     */
    public static List<ModelBox> boxes(byte stance, boolean aiming, float frac, float yBodyRot) {
        List<Part> parts = aiming ? AIMING : HOLDING;
        List<ModelBox> out = new ArrayList<>(parts.size());
        for (Part p : parts) {
            // model px to the mob's frame: right = -x, up = y, forward = z, blocks
            double r = -p.cx / PX, u = p.cy / PX, f = p.cz / PX;
            Vec3d c = leanPoint(r, u, f, stance, frac, yBodyRot);
            Vec3d eu = edge(r, u, f, -p.ux / PX, p.uy / PX, p.uz / PX, stance, frac, yBodyRot);
            Vec3d ev = edge(r, u, f, -p.vx / PX, p.vy / PX, p.vz / PX, stance, frac, yBodyRot);
            Vec3d ew = edge(r, u, f, -p.wx / PX, p.wy / PX, p.wz / PX, stance, frac, yBodyRot);
            Vec3d corner = c.subtract(eu.add(ev).add(ew).scale(0.5));
            out.add(new ModelBox(p.name, corner.x(), corner.y(), corner.z(), eu.x(), eu.y(), eu.z(), ev.x(), ev.y(), ev.z(), ew.x(), ew.y(), ew.z()));
        }
        return out;
    }

    /**
     * An edge of a box centred here, moved as its centre is: the lean's turn at the centre (a step of a thousandth of
     * the edge from it stays on the centre's side of the hips), not the hips' piecewise one across the box.
     */
    static Vec3d edge(double r, double u, double f, double dr, double du, double df, byte stance, float frac, float yBodyRot) {
        Vec3d a = leanPoint(r, u, f, stance, frac, yBodyRot);
        Vec3d b = leanPoint(r + dr * 1e-3, u + du * 1e-3, f + df * 1e-3, stance, frac, yBodyRot);
        return b.subtract(a).scale(1e3);
    }

    /** The lean now, −1 (left) .. 1 (right): from prevLean to lean in {@link #LEAN_TICKS} ticks after leanTick. */
    public static float leanFrac(byte prevLean, byte lean, int leanTick, long now, float partial) {
        float t = GameMath.clamp((now - leanTick + partial) / LEAN_TICKS, 0F, 1F);
        return GameMath.lerp(t, prevLean / 100F, lean / 100F);
    }

    /** The hips above the feet, blocks, and how far they are behind the feet (crouching). */
    static double hipUp(byte stance) {
        return MODEL_FEET - (stance == Stance.CROUCH ? CROUCH_HIP_PX : HIP_PX) / 16.0;
    }

    /**
     * A point of the upright body, given in the mob's own frame (right, up, forward; blocks from its feet), where it
     * is with the body leaned by {@code frac}, in the world's axes (an offset from the mob's position). Points below
     * the hips (the legs) only step aside; points above also tip.
     */
    public static Vec3d leanPoint(double right, double up, double forward, byte stance, float frac, float yBodyRot) {
        return leanPoint(right, up, forward, stance, frac, yBodyRot, VECTOR);
    }

    public static <T> T leanPoint(double right, double up, double forward, byte stance, float frac, float yBodyRot, Vec3Maker<T> maker) {
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
        return toWorld(r, u, forward, yBodyRot, maker);
    }

    /** The eye, an offset from the mob's position: {@code eyeHeight} is the mob's unleaned eye (getEyeHeight). */
    public static Vec3d eyeOffset(byte stance, float frac, float yBodyRot, double eyeHeight) {
        return eyeOffset(stance, frac, yBodyRot, eyeHeight, VECTOR);
    }

    public static <T> T eyeOffset(byte stance, float frac, float yBodyRot, double eyeHeight, Vec3Maker<T> maker) {
        return leanPoint(0, eyeHeight, 0, stance, frac, yBodyRot, maker);
    }

    /** The mob's frame (right, up, forward) to the world's axes for a body turned yBodyRot (Minecraft yaw). */
    static Vec3d toWorld(double right, double up, double forward, float yBodyRot) {
        return toWorld(right, up, forward, yBodyRot, VECTOR);
    }

    static <T> T toWorld(double right, double up, double forward, float yBodyRot, Vec3Maker<T> maker) {
        double y = Math.toRadians(yBodyRot), sin = Math.sin(y), cos = Math.cos(y);
        // forward = (-sin, 0, cos), right = (-cos, 0, -sin)
        return maker.make(-forward * sin - right * cos, up, forward * cos - right * sin);
    }

}
