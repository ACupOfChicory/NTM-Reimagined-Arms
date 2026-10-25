package acup.gunfeel;

import org.joml.Matrix4fc;
import org.joml.Vector3f;

// Одна коробка группы меша в её собственной системе координат, затем настоящая матрица отрисовки.
public final class MeshModelPose {
    private MeshModelPose() {}

    public static ModelBox box(String name, float[] bounds, Matrix4fc matrix, double x, double y, double z) {
        if (bounds.length != 6 || bounds[3] <= bounds[0] || bounds[4] <= bounds[1] || bounds[5] <= bounds[2]) return null;
        String part = "mesh/" + name;
        if (part.length() > ModelBox.MAX_PART_NAME) part = part.substring(0, ModelBox.MAX_PART_NAME);
        var p = matrix.transformPosition(bounds[0], bounds[1], bounds[2], new Vector3f());
        var u = matrix.transformDirection(bounds[3] - bounds[0], 0, 0, new Vector3f());
        var v = matrix.transformDirection(0, bounds[4] - bounds[1], 0, new Vector3f());
        var w = matrix.transformDirection(0, 0, bounds[5] - bounds[2], new Vector3f());
        return new ModelBox(part, p.x + x, p.y + y, p.z + z, u.x, u.y, u.z, v.x, v.y, v.z, w.x, w.y, w.z);
    }
}
