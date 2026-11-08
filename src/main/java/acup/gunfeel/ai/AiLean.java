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

    /** a box of the upright humanoid model: its corner (right, up, forward from the feet) and size, blocks */
    private record Part(String name, double r, double u, double f, double dr, double du, double df) {
    }

    /** HumanoidModel's parts (16 px a block, its feet 1.501 below the model's origin): standing, arms down */
    private static final double HIP = 0.76;
    private static final List<Part> HANGING = List.of(new Part("head", -0.25, 1.501, -0.25, 0.5, 0.5, 0.5),
            new Part("body", -0.25, HIP, -0.125, 0.5, 1.501 - HIP, 0.25),
            new Part("right_arm", 0.25, HIP, -0.125, 0.25, 1.501 - HIP, 0.25),
            new Part("left_arm", -0.5, HIP, -0.125, 0.25, 1.501 - HIP, 0.25),
            new Part("right_leg", 0, 0, -0.125, 0.25, 0.75, 0.25),
            new Part("left_leg", -0.25, 0, -0.125, 0.25, 0.75, 0.25));
    /** the arms held out to aim, shoulder high, forward of the body */
    private static final List<Part> AIMING = List.of(HANGING.get(0), HANGING.get(1),
            new Part("right_arm", 0.1, 1.25, 0, 0.25, 0.25, 0.75),
            new Part("left_arm", -0.35, 1.25, 0, 0.25, 0.25, 0.75), HANGING.get(4), HANGING.get(5));

    /** the model's boxes for a standing mob at this lean, as ModelHits keeps them (from its position, world axes) */
    static List<ModelBox> boxes(byte stance, boolean aiming, float frac, float yBodyRot) {
        List<ModelBox> out = new ArrayList<>(6);
        for (Part p : aiming ? AIMING : HANGING) {
            Vec3 c = AiPoseGeometry.leanPoint(p.r, p.u, p.f, stance, frac, yBodyRot);
            Vec3 u = AiPoseGeometry.leanPoint(p.r + p.dr, p.u, p.f, stance, frac, yBodyRot).subtract(c);
            Vec3 v = AiPoseGeometry.leanPoint(p.r, p.u + p.du, p.f, stance, frac, yBodyRot).subtract(c);
            Vec3 w = AiPoseGeometry.leanPoint(p.r, p.u, p.f + p.df, stance, frac, yBodyRot).subtract(c);
            out.add(new ModelBox(p.name, c.x, c.y, c.z, u.x, u.y, u.z, v.x, v.y, v.z, w.x, w.y, w.z));
        }
        return out;
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
