package acup.gunfeel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

// port: new for 1.7.10 (26.2: ModelMeshCapture.geometry for NTM's GroupObject, in NtmMeshHits.group). An NTM mob drawn
// by an OBJ model (the glyphids, the crabs, the block spider, the mask man) has no ModelRenderer cubes: its parts are the
// OBJ's groups, drawn with GL's matrix of the moment. A group's triangles are baked once into unit-box meshes (ModelMesh,
// as many as its triangles need), then each frame put where the matrix draws them (MeshModelPose.box). The texture's
// alpha is not read: NTM's mob models are solid.
public final class ModelObjGeometry {
    // a group's bounds (model units) and its meshes; none: the group has no surface
    private record Baked(float[] bounds, List<ModelMesh> meshes) {}
    private static final Map<Object, Baked> BAKED = Collections.synchronizedMap(new WeakHashMap<>());

    private ModelObjGeometry() {}

    // the faces of a group, each its corners' x, y, z one after another (a triangle, a quad or more: a fan)
    public interface Faces {
        List<float[]> faces();
    }

    // the group's boxes, named "mesh/<name>", for the matrix (model units -> camera-relative world) and the origin
    public static void append(List<ModelBox> out, Object group, String name, Faces faces, float[] matrix, double x, double y, double z) {
        Baked b = BAKED.get(group);
        if (b == null) {
            b = bake(faces.faces());
            BAKED.put(group, b);
        }
        if (b.meshes().isEmpty()) return;
        ModelBox box = MeshModelPose.box(name, b.bounds(), matrix, x, y, z);
        if (box == null) return;
        for (ModelMesh m : b.meshes()) out.add(box.withMesh(m.id()));
    }

    static Baked bake(List<float[]> faces) {
        float[] bounds = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (float[] f : faces) for (int i = 0; i + 2 < f.length; i += 3) for (int a = 0; a < 3; a++) {
            bounds[a] = Math.min(bounds[a], f[i + a]);
            bounds[a + 3] = Math.max(bounds[a + 3], f[i + a]);
        }
        if (!(bounds[0] <= bounds[3]) || !MeshModelPose.hasSurface(bounds[3] - bounds[0], bounds[4] - bounds[1], bounds[5] - bounds[2]))
            return new Baked(bounds, List.of());
        List<Float> triangles = new ArrayList<>();
        for (float[] f : faces) {
            int n = f.length / 3;
            for (int k = 1; k + 1 < n; k++) for (int corner : new int[] {0, k, k + 1}) for (int a = 0; a < 3; a++) {
                float extent = bounds[a + 3] - bounds[a];
                triangles.add(extent == 0 ? 0F : Math.max(0F, Math.min(1F, (f[corner * 3 + a] - bounds[a]) / extent)));
            }
        }
        List<ModelMesh> meshes = new ArrayList<>();
        int step = ModelMesh.MAX_TRIANGLES * 9;
        for (int start = 0; start < triangles.size(); start += step) {
            float[] data = new float[Math.min(step, triangles.size() - start)];
            for (int i = 0; i < data.length; i++) data[i] = triangles.get(start + i);
            meshes.add(ModelMesh.register(new ModelMesh(data)));
        }
        return new Baked(bounds, List.copyOf(meshes));
    }
}
