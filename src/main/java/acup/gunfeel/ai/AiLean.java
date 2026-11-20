package acup.gunfeel.ai;

import java.util.List;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.EntityBody;
import acup.gunfeel.ModelBox;
import acup.gunfeel.ModelHits;
import acup.gunfeel.host.Body;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * An AI mob leaning round a corner, on the server: where its eyes are (what it sees, where its shots leave from: the
 * NTM bullet, MixinAiLeanShot), where the parts of its body are for those who look at it, and its body's boxes for
 * the shots at it while no client has drawn it (ModelHits: a head out past the corner is out of its upright hitbox).
 * The geometry is the pose layer's (AiPoseGeometry), so a shot leaves where the gun is drawn. Server thread.
 */
public final class AiLean extends AiLeanBase {
    static @Nullable AiLeanAlike alike;
    private static final Vec3Maker<Vec3> VECTOR = Vec3::new;
    private static final Staff STAFF = new Staff() {
        @Override public @Nullable LeanPose pose(Body b) { return AiPose.of((LivingEntity) EntityBody.entity(b)); }
        @Override public long gameTime(Body b) { return EntityBody.entity(b).level().getGameTime(); }
        @Override public boolean hitsEnabled() { return ModelHits.enabled(); }
        @Override public long caught(Object level, Body b) { return ModelHits.caught((ServerLevel) level, (LivingEntity) EntityBody.entity(b)); }
        @Override public void forget(Object level, Body b, long put) { ModelHits.forget((ServerLevel) level, (LivingEntity) EntityBody.entity(b), put); }
        @Override public void put(Object level, Body b, float yaw, List<ModelBox> boxes) { ModelHits.put((ServerLevel) level, (LivingEntity) EntityBody.entity(b), yaw, boxes); }
    };

    private AiLean() {
    }

    /** whether it may lean at all: shots at a leaned head need the model's boxes (else a step aside, as before) */
    static boolean may() {
        return AiLeanBase.may(STAFF);
    }

    /** the lean now, −1 .. 1 (0 for any mob that does not lean) */
    public static float frac(net.minecraft.world.entity.Entity entity) {
        float answer = AiLeanBase.frac(STAFF, EntityBody.of(entity));
        return alike == null ? answer : alike.frac(entity, answer);
    }

    /** where its eyes are: leaned, or the game's own */
    public static Vec3 eye(net.minecraft.world.entity.Entity entity) {
        Vec3 answer = AiLeanBase.eye(STAFF, EntityBody.of(entity), VECTOR);
        if (answer == null) answer = entity.getEyePosition();
        return alike == null ? answer : alike.eye(entity, answer);
    }

    /** how far its eyes are from where they would be upright (what NTM's bullet starts off by) */
    public static Vec3 eyeShift(LivingEntity e) {
        Vec3 answer = AiLeanBase.eyeShift(STAFF, EntityBody.of(e), VECTOR);
        if (answer == null) answer = Vec3.ZERO;
        return alike == null ? answer : alike.eyeShift(e, answer);
    }

    /** a point of its middle line, {@code up} blocks above its feet, where it is with the lean */
    public static Vec3 point(LivingEntity e, double up) {
        Vec3 answer = AiLeanBase.point(STAFF, EntityBody.of(e), up, VECTOR);
        return alike == null ? answer : alike.point(e, up, answer);
    }

    // ------------------------------------------------------------------ boxes for shots, with no client

    public static List<ModelBox> boxes(byte stance, boolean aiming, float frac, float yBodyRot) { return alike == null ? AiLeanBase.boxes(stance, aiming, frac, yBodyRot) : alike.boxes(stance, aiming, frac, yBodyRot, AiLeanBase.boxes(stance, aiming, frac, yBodyRot)); }

    /** its own boxes taken back, if they are still the ones kept (a client's newer pose stays); returns none */
    static long drop(ServerLevel level, LivingEntity e, long put) {
        return AiLeanBase.drop(STAFF, level, EntityBody.of(e), put);
    }

    /**
     * Each tick: leaned, its boxes, unless a client drew it lately (a client's model is the real one); upright again,
     * the boxes it put taken back. {@code put} is the tick it last put its own (Long.MIN_VALUE: none); returns the new.
     */
    static long boxes(ServerLevel level, LivingEntity e, boolean aiming, long put, long tick) {
        return AiLeanBase.boxes(STAFF, level, EntityBody.of(e), aiming, put, tick);
    }
}
