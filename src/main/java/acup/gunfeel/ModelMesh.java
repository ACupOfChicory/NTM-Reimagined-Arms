package acup.gunfeel;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Неизменяемая поверхность в единичной коробке. BVH и рёбра строятся один раз;
// анимация меняет только внешний аффинный базис ModelBox.
public final class ModelMesh {
    public static final int MAX_TRIANGLES = 768;
    private static final Map<Long, ModelMesh> CACHE = new ConcurrentHashMap<>();
    private record Node(double[] bounds, int from, int to, Node left, Node right) {}
    private final long id;
    private final float[] triangles, edges;
    private final Integer[] order;
    private final Node root;

    public ModelMesh(float[] data) {
        if (data.length == 0 || data.length % 9 != 0 || data.length / 9 > MAX_TRIANGLES)
            throw new IllegalArgumentException("Model mesh triangle count");
        for (float x : data) if (!Float.isFinite(x) || x < 0 || x > 1) throw new IllegalArgumentException("Model mesh coordinate");
        triangles = data.clone();
        id = hash(triangles);
        order = new Integer[triangles.length / 9];
        for (int i = 0; i < order.length; i++) order[i] = i;
        root = build(0, order.length);
        edges = buildEdges();
    }

    public long id() { return id; }
    public float[] triangles() { return triangles.clone(); }
    public float[] edges() { return edges; }
    public static ModelMesh get(long id) { return CACHE.get(id); }
    public static ModelMesh register(ModelMesh mesh) {
        ModelMesh old = CACHE.get(mesh.id);
        if (old != null) return old;
        if (CACHE.size() >= 512) throw new IllegalStateException("Model mesh cache full");
        old = CACHE.putIfAbsent(mesh.id, mesh);
        return old == null ? mesh : old;
    }

    private static long hash(float[] data) {
        try {
            ByteBuffer bytes = ByteBuffer.allocate(data.length * 4);
            for (float x : data) bytes.putFloat(x);
            long id = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(bytes.array())).getLong();
            return id == 0 ? 1 : id;
        } catch (java.security.NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }

    private Node build(int from, int to) {
        double[] b = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int i = from; i < to; i++) for (int v = 0; v < 9; v += 3) for (int a = 0; a < 3; a++) {
            float p = triangles[order[i] * 9 + v + a];
            b[a] = Math.min(b[a], p); b[a + 3] = Math.max(b[a + 3], p);
        }
        if (to - from <= 8) return new Node(b, from, to, null, null);
        int axis = b[4] - b[1] > b[3] - b[0] ? 1 : 0;
        if (b[5] - b[2] > b[axis + 3] - b[axis]) axis = 2;
        final int a = axis;
        Arrays.sort(order, from, to, Comparator.comparingDouble(i -> triangles[i * 9 + a]
                + triangles[i * 9 + a + 3] + triangles[i * 9 + a + 6]));
        int mid = (from + to) / 2;
        return new Node(b, from, to, build(from, mid), build(mid, to));
    }

    public double clip(double[] a, double[] b) {
        if (insideBounds(a) && contains(a)) return 0;
        if (Arrays.equals(a, b)) return -1;
        double hit = nearest(root, a, b, Double.POSITIVE_INFINITY);
        return Double.isFinite(hit) ? hit : -1;
    }

    private static boolean insideBounds(double[] p) {
        return p[0] >= 0 && p[0] <= 1 && p[1] >= 0 && p[1] <= 1 && p[2] >= 0 && p[2] <= 1;
    }

    private boolean contains(double[] a) {
        double[] b = {a[0] + 2.137, a[1] + 2.417, a[2] + 2.731};
        double[] hits = new double[order.length];
        int n = crossings(root, a, b, hits, 0);
        Arrays.sort(hits, 0, n);
        int unique = 0;
        double previous = -1;
        for (int i = 0; i < n; i++) {
            if (hits[i] <= 1e-9) return true;
            if (hits[i] - previous > 1e-7) { unique++; previous = hits[i]; }
        }
        return (unique & 1) != 0;
    }

    private double nearest(Node node, double[] a, double[] b, double best) {
        if (!overlaps(node.bounds, a, b, best)) return best;
        if (node.left != null) return nearest(node.right, a, b, nearest(node.left, a, b, best));
        for (int i = node.from; i < node.to; i++) {
            double hit = triangle(order[i], a, b);
            if (hit >= 0 && hit < best) best = hit;
        }
        return best;
    }

    private int crossings(Node node, double[] a, double[] b, double[] hits, int n) {
        if (!overlaps(node.bounds, a, b, 1)) return n;
        if (node.left != null) return crossings(node.right, a, b, hits, crossings(node.left, a, b, hits, n));
        for (int i = node.from; i < node.to; i++) {
            double hit = triangle(order[i], a, b);
            if (hit >= 0) hits[n++] = hit;
        }
        return n;
    }

    private static boolean overlaps(double[] bounds, double[] a, double[] b, double best) {
        double lo = 0, hi = Math.min(1, best);
        for (int i = 0; i < 3; i++) {
            double d = b[i] - a[i];
            if (Math.abs(d) < 1e-12) {
                if (a[i] < bounds[i] - 1e-9 || a[i] > bounds[i + 3] + 1e-9) return false;
            } else {
                double t0 = (bounds[i] - a[i]) / d, t1 = (bounds[i + 3] - a[i]) / d;
                lo = Math.max(lo, Math.min(t0, t1)); hi = Math.min(hi, Math.max(t0, t1));
                if (hi < lo - 1e-9) return false;
            }
        }
        return true;
    }

    private double triangle(int index, double[] a, double[] b) {
        int k = index * 9;
        double ux = triangles[k + 3] - triangles[k], uy = triangles[k + 4] - triangles[k + 1], uz = triangles[k + 5] - triangles[k + 2];
        double vx = triangles[k + 6] - triangles[k], vy = triangles[k + 7] - triangles[k + 1], vz = triangles[k + 8] - triangles[k + 2];
        double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        double px = dy * vz - dz * vy, py = dz * vx - dx * vz, pz = dx * vy - dy * vx;
        double det = ux * px + uy * py + uz * pz;
        if (Math.abs(det) < 1e-12) return -1;
        double tx = a[0] - triangles[k], ty = a[1] - triangles[k + 1], tz = a[2] - triangles[k + 2];
        double u = (tx * px + ty * py + tz * pz) / det;
        if (u < -1e-9 || u > 1 + 1e-9) return -1;
        double qx = ty * uz - tz * uy, qy = tz * ux - tx * uz, qz = tx * uy - ty * ux;
        double v = (dx * qx + dy * qy + dz * qz) / det;
        if (v < -1e-9 || u + v > 1 + 1e-9) return -1;
        double t = (vx * qx + vy * qy + vz * qz) / det;
        return t >= -1e-9 && t <= 1 + 1e-9 ? Math.clamp(t, 0, 1) : -1;
    }

    private record Point(float x, float y, float z) implements Comparable<Point> {
        public int compareTo(Point p) {
            int c = Float.compare(x, p.x); if (c == 0) c = Float.compare(y, p.y); return c == 0 ? Float.compare(z, p.z) : c;
        }
    }
    private record Edge(Point a, Point b) {}
    private static final class FaceEdge {
        final double x, y, z;
        int count = 1;
        boolean crease;
        FaceEdge(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    }
    private float[] buildEdges() {
        Map<Edge, FaceEdge> all = new HashMap<>();
        for (int k = 0; k < triangles.length; k += 9) {
            Point[] p = new Point[3];
            for (int i = 0; i < 3; i++) p[i] = new Point(triangles[k + i * 3], triangles[k + i * 3 + 1], triangles[k + i * 3 + 2]);
            double ux = p[1].x - p[0].x, uy = p[1].y - p[0].y, uz = p[1].z - p[0].z;
            double vx = p[2].x - p[0].x, vy = p[2].y - p[0].y, vz = p[2].z - p[0].z;
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < 1e-12) continue;
            nx /= length; ny /= length; nz /= length;
            for (int i = 0; i < 3; i++) {
                Point a = p[i], b = p[(i + 1) % 3];
                Edge key = a.compareTo(b) < 0 ? new Edge(a, b) : new Edge(b, a);
                FaceEdge old = all.get(key);
                if (old == null) all.put(key, new FaceEdge(nx, ny, nz));
                else { old.count++; old.crease |= Math.abs(old.x * nx + old.y * ny + old.z * nz) < .99999; }
            }
        }
        ArrayList<Float> lines = new ArrayList<>();
        all.forEach((edge, face) -> {
            if (face.count == 1 || face.crease) for (Point p : new Point[] {edge.a, edge.b}) {
                lines.add(p.x); lines.add(p.y); lines.add(p.z);
            }
        });
        float[] out = new float[lines.size()];
        for (int i = 0; i < out.length; i++) out[i] = lines.get(i);
        return out;
    }
}
