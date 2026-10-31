package acup.gunfeel.obj;

// port/continuum: one group of a gun model as the shared Arms, GunGeometry and Silhouette read NTM NEXT's
// com.hbm.render.loader.GroupObject (the import is renamed to this class in stonecutter.gradle.kts). Built from NTM
// Continuum's WavefrontObj (see HFRWavefrontObject): the same OBJ group, the same vertex layout (x, y, z, u, v,
// nx, ny, nz) and four vertices a face. Continuum hands out triangles, so each face here is a triangle with its last
// vertex twice: the same surface, its cross-sections and silhouette unchanged.
public final class GroupObject {
    // floats per vertex in quads()
    public static final int STRIDE = 8;
    public final String name;
    private final float[] quads;
    private final float[] bounds;

    GroupObject(String name, float[] triangles) {
        this.name = name;
        int count = triangles.length / STRIDE / 3;
        quads = new float[count * 4 * STRIDE];
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int t = 0; t < count; t++) {
            int from = t * 3 * STRIDE, to = t * 4 * STRIDE;
            System.arraycopy(triangles, from, quads, to, 3 * STRIDE);
            System.arraycopy(triangles, from + 2 * STRIDE, quads, to + 3 * STRIDE, STRIDE);
            for (int v = 0; v < 3; v++) {
                for (int axis = 0; axis < 3; axis++) {
                    float c = triangles[from + v * STRIDE + axis];
                    b[axis] = Math.min(b[axis], c);
                    b[axis + 3] = Math.max(b[axis + 3], c);
                }
            }
        }
        bounds = count == 0 ? new float[6] : b;
    }

    // every face as four vertices of STRIDE floats; the normals are the OBJ's own whatever `smoothing` asks
    public float[] quads(boolean smoothing) {
        return quads;
    }

    // {minX, minY, minZ, maxX, maxY, maxZ} of the group's vertices
    public float[] bounds() {
        return bounds.clone();
    }

    public int faceCount() {
        return quads.length / (4 * STRIDE);
    }
}
