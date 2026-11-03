package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3d;

// port: a hole on plain cubes wrapped over their edges, as 26.2's drawnHole lays its decal over block models
// (Tracers.drawnHole, DecalSurface): the faces of the opaque cubes around the hit that are not covered by another,
// joined to the hit face by their edges and unfolded into the hole's plane (over an edge like a folded sheet), each cut
// to the hole's square. Without it a hole near a block's edge was cut off at the edge. Pure: the tests check it
// (BlockDecalTest).
final class BlockDecal {
    private BlockDecal() {
    }

    interface Cubes {
        // an opaque full cube is here (its faces carry holes, it covers its neighbours' faces)
        boolean cube(int x, int y, int z);
    }

    // The hole's pieces, in the world: each {unit normal (3), then corners {x, y, z, a, b}} with a, b the hole's
    // texture coordinates 0..1 along the hit face's axes (HoleMath.axes); null when the square stays on the hit face
    // (the flat hole draws it). pos = the hit block, face = its hit face, hit = the hit point, half = half the size.
    static List<double[]> wrap(int[] pos, int face, double[] hit, double half, Cubes cubes) {
        int normal = HoleMath.axis(face);
        int[] ax = HoleMath.axes(face);
        double u = hit[ax[0]] - pos[ax[0]], v = hit[ax[1]] - pos[ax[1]];
        if (u - half >= 0 && u + half <= 1 && v - half >= 0 && v + half <= 1) return null;
        List<DecalSurface.Face> faces = new ArrayList<DecalSurface.Face>();
        List<int[]> blocks = new ArrayList<int[]>();
        faces.add(face(0, 0, 0, face));
        blocks.add(new int[] {0, 0, 0, face});
        int reach = (int) Math.ceil(half);
        for (int dx = -reach; dx <= reach; dx++) for (int dy = -reach; dy <= reach; dy++) for (int dz = -reach; dz <= reach; dz++) {
            if (!cubes.cube(pos[0] + dx, pos[1] + dy, pos[2] + dz)) continue;
            for (int f = 0; f < 6; f++) {
                if (dx == 0 && dy == 0 && dz == 0 && f == face) continue;
                int[] o = HoleMath.offset(f);
                if (cubes.cube(pos[0] + dx + o[0], pos[1] + dy + o[1], pos[2] + dz + o[2])) continue;
                faces.add(face(dx, dy, dz, f));
                blocks.add(new int[] {dx, dy, dz, f});
            }
        }
        Vector3d origin = new Vector3d(hit[0] - pos[0], hit[1] - pos[1], hit[2] - pos[2]);
        Vector3d eu = unit(ax[0]), ev = unit(ax[1]), en = unit(normal).mul(HoleMath.step(face));
        DecalSurface.Frame[] frames = DecalSurface.unfold(faces, 0, new DecalSurface.Frame(origin, eu, ev, en), half);
        List<double[]> out = new ArrayList<double[]>();
        for (int i = 0; i < faces.size(); i++) {
            DecalSurface.Frame fr = frames[i];
            if (fr == null) continue;
            List<double[]> poly = new ArrayList<double[]>();
            for (Vector3d p : faces.get(i).points) poly.add(new double[] {fr.x(p), fr.y(p), p.x, p.y, p.z});
            poly = cut(cut(cut(cut(poly, 0, -half, true), 0, half, false), 1, -half, true), 1, half, false);
            if (poly.size() < 3) continue;
            Vector3d n = faces.get(i).normal;
            double[] piece = new double[3 + poly.size() * 5];
            piece[0] = n.x;
            piece[1] = n.y;
            piece[2] = n.z;
            for (int c = 0; c < poly.size(); c++) {
                double[] q = poly.get(c);
                piece[3 + c * 5] = q[2] + pos[0];
                piece[3 + c * 5 + 1] = q[3] + pos[1];
                piece[3 + c * 5 + 2] = q[4] + pos[2];
                piece[3 + c * 5 + 3] = (q[0] + half) / (2 * half);
                piece[3 + c * 5 + 4] = (q[1] + half) / (2 * half);
            }
            out.add(piece);
        }
        return out;
    }

    // which of the blocks wrap() looks at are cubes (the hit block's (2r+3)^3 neighbourhood, r = ceil(half)), as bits
    static long[] around(int[] pos, double half, Cubes cubes) {
        int r = (int) Math.ceil(half) + 1, side = 2 * r + 1;
        long[] bits = new long[(side * side * side + 63) / 64];
        int i = 0;
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++, i++)
            if (cubes.cube(pos[0] + dx, pos[1] + dy, pos[2] + dz)) bits[i >> 6] |= 1L << (i & 63);
        return bits;
    }

    private static Vector3d unit(int axis) {
        Vector3d v = new Vector3d();
        v.setComponent(axis, 1);
        return v;
    }

    // a cube's face from the hit block's corner, counter-clockwise seen from outside
    private static DecalSurface.Face face(int dx, int dy, int dz, int f) {
        int normal = HoleMath.axis(f);
        int[] ax = HoleMath.axes(f);
        double plane = (normal == 0 ? dx : normal == 1 ? dy : dz) + (HoleMath.step(f) > 0 ? 1 : 0);
        int[] off = {dx, dy, dz};
        double[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        Vector3d en = unit(normal).mul(HoleMath.step(f));
        // u x v along the outward normal: counter-clockwise as listed, else reversed
        boolean reverse = unit(ax[0]).cross(unit(ax[1])).dot(en) < 0;
        List<Vector3d> points = new ArrayList<Vector3d>(4);
        for (int j = 0; j < 4; j++) {
            double[] c = corners[reverse ? 3 - j : j];
            Vector3d p = new Vector3d();
            p.setComponent(ax[0], off[ax[0]] + c[0]);
            p.setComponent(ax[1], off[ax[1]] + c[1]);
            p.setComponent(normal, plane);
            points.add(p);
        }
        return new DecalSurface.Face(points, en);
    }

    // the polygon's part on one side of a line along one of its first two coordinates (as MachineMesh.cut)
    static List<double[]> cut(List<double[]> poly, int axis, double at, boolean low) {
        List<double[]> out = new ArrayList<double[]>();
        for (int i = 0; i < poly.size(); i++) {
            double[] p = poly.get(i), q = poly.get((i + 1) % poly.size());
            boolean pin = low ? p[axis] >= at : p[axis] <= at, qin = low ? q[axis] >= at : q[axis] <= at;
            if (pin) out.add(p);
            if (pin != qin) {
                double t = (at - p[axis]) / (q[axis] - p[axis]);
                double[] m = new double[p.length];
                for (int k = 0; k < p.length; k++) m[k] = p[k] + (q[k] - p[k]) * t;
                out.add(m);
            }
        }
        return out;
    }
}
