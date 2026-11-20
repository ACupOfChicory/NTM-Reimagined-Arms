package acup.gunfeel.ai;

import java.util.List;

import acup.gunfeel.ModelBox;
import net.minecraft.world.phys.Vec3;

/** The stands' pure answers, after the working call. */
final class AiLeanAlike {
    long asked, differ;

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
        if (Double.compare(answer.x, old.x) != 0 || Double.compare(answer.y, old.y) != 0 || Double.compare(answer.z, old.z) != 0) differ++;
        return answer;
    }

    String info() {
        return asked + " answers, " + differ + " differ";
    }
}
