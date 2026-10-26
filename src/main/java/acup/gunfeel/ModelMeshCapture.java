package acup.gunfeel;

import com.hbm.render.loader.GroupObject;
import java.util.ArrayList;
import java.util.Map;

// Нормализация исходных граней выполняется один раз на группу ресурса, а не каждый кадр.
public final class ModelMeshCapture {
    private static final Map<GroupObject, ModelMesh> CACHE = new java.util.WeakHashMap<>();
    private ModelMeshCapture() {}

    public static synchronized ModelMesh mesh(GroupObject group) {
        return CACHE.computeIfAbsent(group, g -> {
            float[] bounds = g.bounds(), q = g.quads(true);
            ArrayList<Float> data = new ArrayList<>();
            for (int face = 0; face < q.length; face += 32) {
                triangle(data, q, face, 0, 8, 16, bounds);
                triangle(data, q, face, 0, 16, 24, bounds);
            }
            float[] triangles = new float[data.size()];
            for (int i = 0; i < triangles.length; i++) triangles[i] = data.get(i);
            return ModelMesh.register(new ModelMesh(triangles));
        });
    }

    private static void triangle(ArrayList<Float> out, float[] q, int base, int a, int b, int c, float[] bounds) {
        double ux = q[base + b] - q[base + a], uy = q[base + b + 1] - q[base + a + 1], uz = q[base + b + 2] - q[base + a + 2];
        double vx = q[base + c] - q[base + a], vy = q[base + c + 1] - q[base + a + 1], vz = q[base + c + 2] - q[base + a + 2];
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        if (nx * nx + ny * ny + nz * nz < 1e-18) return;
        for (int vertex : new int[] {a, b, c}) for (int axis = 0; axis < 3; axis++) {
            float span=bounds[axis+3]-bounds[axis];
            out.add(span==0?0:Math.clamp((q[base+vertex+axis]-bounds[axis])/span,0,1));
        }
    }
}
