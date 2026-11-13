package acup.gunfeel;

import java.util.List;
import java.util.Locale;

import org.joml.Matrix3d;
import org.joml.Vector3d;

import acup.gunfeel.ai.client.SuitBend;

/**
 * The bot stand's measure of a suit at the knee and the elbow (SuitBend): rays from the joint out round the limb,
 * sideways from both its parts (not along them, out of a sleeve's or a boot's open end), against the skin's boxes as
 * the hitbox has them and the suit's triangles as drawn. Where the skin goes further than the suit it stands out of it
 * (px); a ray that leaves the skin and meets no suit at all goes out through a gap.
 */
final class SuitBendStand {
    /** a ray this near either part's line is along it, not across the joint, degrees */
    static final double ALONG = 35;

    private SuitBendStand() {
    }

    /** the measure of one limb: the most the skin stands out of the suit, px, rays out through a gap, rays, the bend */
    record Seam(double poke, int holes, int rays, double bend) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%.2f px, щелей %d/%d", poke, holes, rays);
        }
    }

    /** the skin's boxes of the limb NTM's bone draws on ("LEFT_LEG": the left leg's thigh, shin and overlap) */
    static List<ModelBox> skin(List<ModelBox> boxes, String bone) {
        String part = "root/" + bone.toLowerCase(Locale.ROOT);
        return boxes.stream().filter(b -> (b.part().equals(part) || b.part().startsWith(part + "/")) && !b.part().contains("/armor/")).toList();
    }

    static Seam measure(SuitBend.Drawn d, List<double[]> suit, List<ModelBox> skin) {
        Vector3d across = new Vector3d(d.across().x, d.across().y, d.across().z).normalize();
        Vector3d up = new Vector3d(d.up().x, d.up().y, d.up().z), down = new Vector3d(d.down().x, d.down().y, d.down().z);
        Vector3d e1 = new Vector3d(up).sub(new Vector3d(across).mul(across.dot(up))).normalize(), e2 = new Vector3d(across).cross(e1);
        double bend = 180 - Math.toDegrees(up.angle(down));
        double poke = 0;
        int holes = 0, rays = 0;
        // a quarter pixel off the cut each way: a ray in its plane runs along the cut's own edges, where the float
        // test of a triangle's edge lets it through between two that meet
        for (double dx = -1.5; dx <= 1.5; dx += 1) for (double dy = -0.25; dy <= 0.25; dy += 0.5) {
            Vector3d o = new Vector3d(across).mul(dx / 16).add(new Vector3d(e1).mul(dy / 16)).add(d.pivot());
            for (int a = 0; a < 360; a += 5) {
                double r = Math.toRadians(a);
                Vector3d dir = new Vector3d(e1).mul(Math.cos(r)).add(new Vector3d(e2).mul(Math.sin(r)));
                if (Math.toDegrees(dir.angle(up)) < ALONG || Math.toDegrees(dir.angle(down)) < ALONG) continue;
                double out = 0;
                for (ModelBox b : skin) out = Math.max(out, exit(b, o, dir));
                if (out <= 0) continue;
                rays++;
                double far = far(suit, o, dir);
                if (Double.isNaN(far)) holes++;
                else poke = Math.max(poke, (out - far) * 16);
            }
        }
        return new Seam(poke, holes, rays, bend);
    }

    /** where a ray from inside leaves a box, blocks; 0 if it misses it */
    static double exit(ModelBox b, Vector3d o, Vector3d d) {
        Matrix3d m = new Matrix3d(b.ux(), b.uy(), b.uz(), b.vx(), b.vy(), b.vz(), b.wx(), b.wy(), b.wz());
        if (Math.abs(m.determinant()) < 1e-12) return 0;
        m.invert();
        Vector3d lo = m.transform(new Vector3d(o.x - b.x(), o.y - b.y(), o.z - b.z())), ld = m.transform(new Vector3d(d));
        double tin = -1e9, tout = 1e9;
        double[] oo = { lo.x, lo.y, lo.z }, dd = { ld.x, ld.y, ld.z };
        for (int i = 0; i < 3; i++) {
            if (Math.abs(dd[i]) < 1e-12) {
                if (oo[i] < 0 || oo[i] > 1) return 0;
                continue;
            }
            double p = -oo[i] / dd[i], q = (1 - oo[i]) / dd[i];
            tin = Math.max(tin, Math.min(p, q));
            tout = Math.min(tout, Math.max(p, q));
        }
        return tout >= Math.max(tin, 0) ? tout : 0;
    }

    /** the farthest a ray meets the triangles, blocks, or NaN */
    static double far(List<double[]> tris, Vector3d o, Vector3d d) {
        double best = Double.NaN;
        for (double[] t : tris) {
            double e1x = t[3] - t[0], e1y = t[4] - t[1], e1z = t[5] - t[2], e2x = t[6] - t[0], e2y = t[7] - t[1], e2z = t[8] - t[2];
            double px = d.y * e2z - d.z * e2y, py = d.z * e2x - d.x * e2z, pz = d.x * e2y - d.y * e2x;
            double det = e1x * px + e1y * py + e1z * pz;
            if (Math.abs(det) < 1e-14) continue;
            double tx = o.x - t[0], ty = o.y - t[1], tz = o.z - t[2];
            double u = (tx * px + ty * py + tz * pz) / det;
            if (u < 0 || u > 1) continue;
            double qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
            double v = (d.x * qx + d.y * qy + d.z * qz) / det;
            if (v < 0 || u + v > 1) continue;
            double s = (e2x * qx + e2y * qy + e2z * qz) / det;
            if (s > 0 && (Double.isNaN(best) || s > best)) best = s;
        }
        return best;
    }
}
