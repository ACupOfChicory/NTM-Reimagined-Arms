package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;

import acup.gunfeel.ModelBox;
import acup.gunfeel.ModelHits;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * An AI mob leaning round a corner, on the server: where its eyes are (what it sees, where its shots leave from: the
 * NTM bullet, MixinAiLeanShot), where the parts of its body are for those who look at it, and its body's boxes for
 * the shots at it while no client has drawn it (ModelHits: a head out past the corner is out of its upright hitbox).
 * The geometry is the pose layer's (AiPoseGeometry), so a shot leaves where the gun is drawn. Server thread.
 */
public final class AiLean {
    /** the step toward the corner before it leans, blocks */
    static final double STEP = 0.25;
    /** the lean a look round a corner takes, of 100 */
    static final byte FULL = 100;
    /** off only for the stands' "before": a look out is a step aside */
    static boolean on = true;

    private AiLean() {
    }

    /** whether it may lean at all: shots at a leaned head need the model's boxes (else a step aside, as before) */
    static boolean may() {
        return on && ModelHits.enabled();
    }

    /** the lean now, −1 .. 1 (0 for any mob that does not lean) */
    public static float frac(net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof LivingEntity e)) return 0F;
        AiPose p = AiPose.of(e);
        if (p.lean() == 0 && p.prevLean() == 0) return 0F;
        return AiPoseGeometry.leanFrac(p, e.level().getGameTime(), 0F);
    }

    /** where its eyes are: leaned, or the game's own */
    public static Vec3 eye(net.minecraft.world.entity.Entity entity) {
        float f = frac(entity);
        if (f == 0F || !(entity instanceof LivingEntity e)) return entity.getEyePosition();
        return e.position().add(AiPoseGeometry.eyeOffset(AiPose.of(e).stance(), f, e.yBodyRot, e.getEyeHeight()));
    }

    /** how far its eyes are from where they would be upright (what NTM's bullet starts off by) */
    public static Vec3 eyeShift(LivingEntity e) {
        float f = frac(e);
        if (f == 0F) return Vec3.ZERO;
        return AiPoseGeometry.eyeOffset(AiPose.of(e).stance(), f, e.yBodyRot, e.getEyeHeight()).subtract(0, e.getEyeHeight(), 0);
    }

    /** a point of its middle line, {@code up} blocks above its feet, where it is with the lean */
    public static Vec3 point(LivingEntity e, double up) {
        float f = frac(e);
        if (f == 0F) return new Vec3(e.getX(), e.getY() + up, e.getZ());
        return e.position().add(AiPoseGeometry.leanPoint(0, up, 0, AiPose.of(e).stance(), f, e.yBodyRot));
    }

    // ------------------------------------------------------------------ boxes for shots, with no client

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
            Vec3 c = AiPoseGeometry.leanPoint(r, u, f, stance, frac, yBodyRot);
            Vec3 eu = edge(r, u, f, -p.ux / PX, p.uy / PX, p.uz / PX, stance, frac, yBodyRot);
            Vec3 ev = edge(r, u, f, -p.vx / PX, p.vy / PX, p.vz / PX, stance, frac, yBodyRot);
            Vec3 ew = edge(r, u, f, -p.wx / PX, p.wy / PX, p.wz / PX, stance, frac, yBodyRot);
            Vec3 corner = c.subtract(eu.add(ev).add(ew).scale(0.5));
            out.add(new ModelBox(p.name, corner.x, corner.y, corner.z, eu.x, eu.y, eu.z, ev.x, ev.y, ev.z, ew.x, ew.y, ew.z));
        }
        return out;
    }

    /**
     * An edge of a box centred here, moved as its centre is: the lean's turn at the centre (a step of a thousandth of
     * the edge from it stays on the centre's side of the hips), not the hips' piecewise one across the box.
     */
    private static Vec3 edge(double r, double u, double f, double dr, double du, double df, byte stance, float frac, float yBodyRot) {
        Vec3 a = AiPoseGeometry.leanPoint(r, u, f, stance, frac, yBodyRot);
        Vec3 b = AiPoseGeometry.leanPoint(r + dr * 1e-3, u + du * 1e-3, f + df * 1e-3, stance, frac, yBodyRot);
        return b.subtract(a).scale(1e3);
    }

    /** a client's pose this new or newer is the client's to keep, ticks */
    static final int CLIENT_FRESH = 5;

    /**
     * Each tick: leaned, its boxes, unless a client drew it lately (a client's model is the real one); upright again,
     * the boxes it put taken back. {@code put} is the tick it last put its own (Long.MIN_VALUE: none); returns the new.
     */
    static long boxes(ServerLevel level, LivingEntity e, boolean aiming, long put, long tick) {
        float f = frac(e);
        if (put == Long.MIN_VALUE && f == 0F) return Long.MIN_VALUE;
        long caught = ModelHits.caught(level, e);
        boolean mine = put != Long.MIN_VALUE && caught == put;
        if (f == 0F || !ModelHits.enabled() || AiPose.of(e).stance() != AiPose.STAND) {
            if (mine) ModelHits.forget(level, e, put);
            return Long.MIN_VALUE;
        }
        if (!mine && caught != Long.MIN_VALUE && tick - caught <= CLIENT_FRESH) return Long.MIN_VALUE;
        ModelHits.put(level, e, e.yBodyRot, boxes(AiPose.STAND, aiming, f, e.yBodyRot));
        return tick;
    }
}
