package acup.gunfeel.ai;

import java.util.List;

import acup.gunfeel.ModelBox;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** The stands' pure answers, after the working call. */
final class AiLeanAlike {
    long asked, differ;

    float frac(Entity entity, float answer) {
        asked++;
        if (Float.floatToRawIntBits(answer) != Float.floatToRawIntBits(AiLeanOld.frac(entity))) differ++;
        return answer;
    }

    Vec3 eye(Entity entity, Vec3 answer) {
        return vector(answer, AiLeanOld.eye(entity));
    }

    Vec3 eyeShift(LivingEntity entity, Vec3 answer) {
        return vector(answer, AiLeanOld.eyeShift(entity));
    }

    Vec3 point(LivingEntity entity, double up, Vec3 answer) {
        return vector(answer, AiLeanOld.point(entity, up));
    }

    List<ModelBox> boxes(byte stance, boolean aiming, float frac, float yaw, List<ModelBox> answer) {
        asked++;
        if (!answer.equals(AiLeanOld.boxes(stance, aiming, frac, yaw))) differ++;
        return answer;
    }

    float leanFrac(AiPose pose, long now, float partial, float answer) {
        asked++;
        if (Float.compare(answer, AiPoseGeometryOld.leanFrac(pose, now, partial)) != 0) differ++;
        return answer;
    }

    Vec3 leanPoint(double right, double up, double forward, byte stance, float frac, float yaw, Vec3 answer) {
        return vector(answer, AiPoseGeometryOld.leanPoint(right, up, forward, stance, frac, yaw));
    }

    Vec3 eyeOffset(byte stance, float frac, float yaw, double eyeHeight, Vec3 answer) {
        return vector(answer, AiPoseGeometryOld.eyeOffset(stance, frac, yaw, eyeHeight));
    }

    private Vec3 vector(Vec3 answer, Vec3 old) {
        asked++;
        if (Double.doubleToRawLongBits(answer.x) != Double.doubleToRawLongBits(old.x)
                || Double.doubleToRawLongBits(answer.y) != Double.doubleToRawLongBits(old.y)
                || Double.doubleToRawLongBits(answer.z) != Double.doubleToRawLongBits(old.z)) differ++;
        return answer;
    }

    String info() {
        return asked + " answers, " + differ + " differ";
    }
}
