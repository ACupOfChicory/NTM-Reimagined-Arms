package acup.gunfeel;

import org.joml.Vector3f;
import org.joml.Vector3d;
import acup.gunfeel.host.render.VertexWriter;

/** Tracer maths, in the operation order of Tracers c84fd5f. */
public final class TracerGeometry {
    private TracerGeometry() {}

    public static Vec3d at(Vec3d start, Vec3d v, float g, double speed, Vec3d muzzleOffset,
            Vec3d stopPoint, double confirmedEnd, double t) {
        if (stopPoint != null && t >= confirmedEnd - 1e-8) return stopPoint;
        Vec3d p = start.add(v.x() * t, v.y() * t - 0.5 * g * t * t, v.z() * t);
        double k = Math.max(0.0, 1.0 - speed * t / 4F);
        return k > 0 ? p.add(muzzleOffset.scale(k)) : p;
    }

    /** Caller-owned output; the immutable Vec3 path's exact operation order. */
    public static Vector3d at(Vector3d out, double x, double y, double z, double vx, double vy, double vz,
            float g, double speed, double mx, double my, double mz, double t) {
        out.set(x + vx * t, y + (vy * t - 0.5 * g * t * t), z + vz * t);
        double k = Math.max(0.0, 1.0 - speed * t / 4F);
        if (k > 0) out.set(out.x + mx * k, out.y + my * k, out.z + mz * k);
        return out;
    }

    public static double frameTicks(double tickNow, double lastFrame) {
        return Double.isNaN(lastFrame) ? 0.05 : Math.clamp(tickNow - lastFrame, 0.01, 0.25);
    }

    public static float pixel(int fov, int height) {
        return (float) (2.0 * Math.tan(Math.toRadians(fov) * 0.5) / Math.max(1, height));
    }

    public static BulletClock.Segment segment(double t, double end, double lifeTicks, float length, double speed, double frameTicks) {
        return BulletClock.segment(t, end, lifeTicks, Math.max(length / speed, frameTicks * 1.5));
    }

    public static void ribbon(float[] out, Vec3d head, Vec3d tail, Vec3d cam, int light, int dark, float pixel) {
        ribbon(out, head.x(), head.y(), head.z(), tail.x(), tail.y(), tail.z(), cam.x(), cam.y(), cam.z(), light, dark, pixel);
    }

    public static void ribbon(float[] out, double hx, double hy, double hz, double tx, double ty, double tz,
            double cx, double cy, double cz, int light, int dark, float pixel) {
        out[0] = (float) (hx + -cx); out[1] = (float) (hy + -cy); out[2] = (float) (hz + -cz);
        out[3] = (float) (tx + -cx); out[4] = (float) (ty + -cy); out[5] = (float) (tz + -cz);
        out[6] = Float.intBitsToFloat(light); out[7] = Float.intBitsToFloat(dark); out[8] = pixel;
    }

    public static void streak(VertexWriter out, float[] k, float width) {
        streak(out, k, width, new Scratch());
    }

    /** Owned by a Part and reused for every ribbon; never escapes to the collector. */
    public static final class Scratch {
        final Vector3f head = new Vector3f(), tail = new Vector3f(), along = new Vector3f(), side = new Vector3f();
        final Vector3f hs = new Vector3f(), ts = new Vector3f();
        final Vector3f[] q = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
    }

    public static void streak(VertexWriter out, float[] k, float width, Scratch scratch) {
        Vector3f head = scratch.head.set(k[0], k[1], k[2]), tail = scratch.tail.set(k[3], k[4], k[5]);
        Vector3f along = scratch.along.set(head).sub(tail);
        if (along.lengthSquared() < 1e-8F) return;
        Vector3f side = scratch.side.set(along).cross(head);
        if (side.lengthSquared() < 1e-10F) return;
        side.normalize();
        float least = 1.0F * k[8];
        float wh = Math.max(width, least * head.length()), wt = Math.max(width * 0.5F, least * tail.length());
        int lightColor = Float.floatToRawIntBits(k[6]) | 0xFF000000, darkColor = (Float.floatToRawIntBits(k[7]) & 0x00FFFFFF) | 0x30000000;
        Vector3f hs = scratch.hs.set(side).mul(wh), ts = scratch.ts.set(side).mul(wt);
        Vector3f[] q = scratch.q;
        q[0].set(head).add(hs); q[1].set(head).sub(hs); q[2].set(tail).sub(ts); q[3].set(tail).add(ts);
        for (int pass = 0; pass < 2; pass++) {
            for (int j = 0; j < 4; j++) {
                int i = pass == 0 ? j : 3 - j;
                out.color(q[i].x, q[i].y, q[i].z, i < 2 ? lightColor : darkColor);
            }
        }
    }
}
