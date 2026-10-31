package acup.gunfeel;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

// port/continuum: one group of an NTM Continuum WavefrontObj in the shape NtmMeshHits and ModelMeshCapture read off
// NTM NEXT's GroupObject: its name, its faces as quads (x, y, z, u, v and three unused floats per corner, as
// GroupObject.quads) and its bounds. A triangle is a quad whose last corner repeats the third, an n-gon a fan of those,
// as WavefrontObj draws them. Built once per group (WavefrontObj's groups never change); the cache holds no reference
// to the group it was read from.
public final class ObjGroup {
    private static final Map<Object, ObjGroup> CACHE = new WeakHashMap<>();

    public final String name;
    private final float[] quads, bounds;

    private ObjGroup(String name, float[] quads, float[] bounds) {
        this.name = name;
        this.quads = quads;
        this.bounds = bounds;
    }

    // NtmMeshHits.wrap hands a mob's custom geometry this buffer while it captures; other OBJ draws (machines) are
    // not read
    public static boolean capturing(com.mojang.blaze3d.vertex.VertexConsumer buffer) {
        return buffer instanceof SubmittedModelGeometry;
    }

    public static synchronized ObjGroup of(Object group, String name, List<int[][]> faces, float[][] positions, float[][] uvs) {
        ObjGroup cached = CACHE.get(group);
        if (cached == null) {
            cached = read(name, faces, positions, uvs);
            CACHE.put(group, cached);
        }
        return cached;
    }

    static ObjGroup read(String name, List<int[][]> faces, float[][] positions, float[][] uvs) {
        int count = 0;
        for (int[][] face : faces) count += face.length == 4 ? 1 : Math.max(0, face.length - 2);
        float[] quads = new float[count * 32];
        float[] bounds = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        int at = 0;
        for (int[][] face : faces) {
            if (face.length == 4) {
                for (int corner = 0; corner < 4; corner++) at = corner(quads, at, face[corner], positions, uvs, bounds);
                continue;
            }
            for (int i = 1; i + 1 < face.length; i++) {
                at = corner(quads, at, face[0], positions, uvs, bounds);
                at = corner(quads, at, face[i], positions, uvs, bounds);
                at = corner(quads, at, face[i + 1], positions, uvs, bounds);
                at = corner(quads, at, face[i + 1], positions, uvs, bounds);
            }
        }
        if (count == 0) bounds = new float[6];
        return new ObjGroup(name, quads, bounds);
    }

    private static int corner(float[] out, int at, int[] ref, float[][] positions, float[][] uvs, float[] bounds) {
        float[] p = positions[ref[0]];
        for (int a = 0; a < 3; a++) {
            out[at + a] = p[a];
            bounds[a] = Math.min(bounds[a], p[a]);
            bounds[a + 3] = Math.max(bounds[a + 3], p[a]);
        }
        out[at + 3] = ref[1] >= 0 ? uvs[ref[1]][0] : 0F;
        out[at + 4] = ref[1] >= 0 ? uvs[ref[1]][1] : 0F;
        return at + 8;
    }

    // GroupObject.quads(smoothing): the argument changes nothing here, WavefrontObj has no smoothing
    public float[] quads(boolean smoothing) {
        return quads;
    }

    public float[] bounds() {
        return bounds.clone();
    }
}
