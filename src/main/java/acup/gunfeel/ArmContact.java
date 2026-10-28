package acup.gunfeel;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Контакт ближайшего угла торца кисти с движущейся деталью. */
final class ArmContact {
    private ArmContact() {}
    static void corner(Matrix4f pose, Vector3f target, boolean right, boolean slim, float weight) {
        float center = (right ? -1F : 1F) * (slim ? 5.5F : 6F) / 16F;
        float half = (slim ? 1.5F : 2F) / 16F;
        Vector3f nearest = null;
        for (int x : new int[] {-1, 1}) for (int z : new int[] {-1, 1}) {
            var point = pose.transformPosition(center + x * half, 12F / 16F, z * 2F / 16F, new Vector3f());
            if (nearest == null || point.distanceSquared(target) < nearest.distanceSquared(target)) nearest = point;
        }
        var shift = new Vector3f(target).sub(nearest).mul(weight);
        pose.m30(pose.m30() + shift.x).m31(pose.m31() + shift.y).m32(pose.m32() + shift.z);
    }
}
