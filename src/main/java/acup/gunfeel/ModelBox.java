package acup.gunfeel;

// Countered's ModelOBB approach (MIT, see third-party/accurate-hitboxes.txt): a model cube, transformed with its
// animated part. Coordinates are relative to the entity, not world floats. The three edges need not be orthogonal
// (parent nonuniform scale + child rotation can shear them). Intersect in the cube's own affine frame instead.
public record ModelBox(String part, double x, double y, double z,
        double ux, double uy, double uz, double vx, double vy, double vz, double wx, double wy, double wz, long mesh) {
    public ModelBox(String part, double x, double y, double z, double ux, double uy, double uz,
            double vx, double vy, double vz, double wx, double wy, double wz) {
        this(part, x, y, z, ux, uy, uz, vx, vy, vz, wx, wy, wz, 0);
    }
    public ModelBox withMesh(long id) { return new ModelBox(part, x, y, z, ux, uy, uz, vx, vy, vz, wx, wy, wz, id); }
    public static final int MAX_PART_NAME = 64;
    public double[] numbers() {
        return new double[] {x, y, z, ux, uy, uz, vx, vy, vz, wx, wy, wz};
    }

    public boolean valid(double radius) {
        if (part == null || part.isEmpty() || part.length() > MAX_PART_NAME) return false;
        if (mesh != 0 && ModelMesh.get(mesh) == null) return false;
        for (double n : numbers()) if (!Double.isFinite(n)) return false;
        if (Math.abs(determinant()) < 1e-12) return false;
        for (int i = 0; i < 8; i++) {
            double a = (i & 1) == 0 ? 0 : 1, b = (i & 2) == 0 ? 0 : 1, c = (i & 4) == 0 ? 0 : 1;
            if (Math.abs(x + a * ux + b * vx + c * wx) > radius
                    || Math.abs(y + a * uy + b * vy + c * wy) > radius
                    || Math.abs(z + a * uz + b * vz + c * wz) > radius) return false;
        }
        return true;
    }

    private double determinant() {
        return ux * (vy * wz - vz * wy) + uy * (vz * wx - vx * wz) + uz * (vx * wy - vy * wx);
    }

    // Fraction of the segment at its first intersection, including a start inside. -1 means no hit.
    public double clip(double ax, double ay, double az, double bx, double by, double bz) {
        double det = determinant();
        if (!Double.isFinite(det) || Math.abs(det) < 1e-12) return -1;
        double[] start = local(ax - x, ay - y, az - z, det);
        double[] end = local(bx - x, by - y, bz - z, det);
        double lo = 0, hi = 1;
        for (int i = 0; i < 3; i++) {
            double delta = end[i] - start[i];
            if (!Double.isFinite(start[i]) || !Double.isFinite(delta)) return -1;
            if (Math.abs(delta) < 1e-12) {
                if (start[i] < 0 || start[i] > 1) return -1;
            } else {
                double a = -start[i] / delta, b = (1 - start[i]) / delta;
                lo = Math.max(lo, Math.min(a, b));
                hi = Math.min(hi, Math.max(a, b));
                if (hi < lo) return -1;
            }
        }
        if (mesh == 0) return lo;
        ModelMesh shape = ModelMesh.get(mesh);
        return shape == null ? -1 : shape.clip(start, end);
    }

    private double[] local(double a, double b, double c, double det) {
        return new double[] {
                (a * (vy * wz - vz * wy) + b * (vz * wx - vx * wz) + c * (vx * wy - vy * wx)) / det,
                (a * (wy * uz - wz * uy) + b * (wz * ux - wx * uz) + c * (wx * uy - wy * ux)) / det,
                (a * (uy * vz - uz * vy) + b * (uz * vx - ux * vz) + c * (ux * vy - uy * vx)) / det};
    }
}
