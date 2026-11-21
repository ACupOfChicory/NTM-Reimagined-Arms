package acup.gunfeel.gl54;

import acup.gunfeel.Vec3d;
import acup.gunfeel.host.render.VertexWriter;

/** The GL-54's lens, crossed ribbons and fade, in the original arithmetic order. */
public final class Gl54LaserGeometry {
    public static final double LENGTH_BLOCKS = 2.0, WIDTH_BLOCKS = 0.015;
    public static final int SEGMENTS = 8, FULL_BRIGHT = 0xF000F0;
    private static final int RGB = (255 << 16) | (30 << 8) | 20, ALPHA = 200;
    public static final double REACH = 64.0;
    private static final double SHOULDER_BELOW_EYE = 0.27;

    private Gl54LaserGeometry() {}

    /** A slot owns these four vectors until the queue has consumed it. */
    public static final class Line {
        public Vec3d start, dir, side, up;
    }

    public static boolean inRange(double distanceSquared) {
        return !(distanceSquared > REACH * REACH);
    }

    public static boolean visibleScale(float scale) { return scale > 1e-6F; }

    /** bodyYaw is already interpolated by the version, in degrees. */
    public static void line(Line out, double ex, double ey, double ez, double fx, double fy, double fz,
            float bodyYaw, double right, double down, double forward) {
        double d = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (d < (double) 1.0E-5F) { fx = 0.0; fy = 0.0; fz = 0.0; }
        else { fx /= d; fy /= d; fz /= d; }
        double h = Math.hypot(fx, fz);
        double rx = h < 1e-6 ? -1 : -fz / h, rz = h < 1e-6 ? 0 : fx / h;
        double ux = 0.0 * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - 0.0 * fx;
        double body = Math.toRadians(bodyYaw);
        // Keep even the additions of zero and the cross product's zero multiplications: signed zero is observable.
        double sx = ex + 0.0, sy = ey + -SHOULDER_BELOW_EYE, sz = ez + 0.0;
        sx += -Math.cos(body) * right; sy += 0.0 * right; sz += -Math.sin(body) * right;
        sx += fx * forward; sy += fy * forward; sz += fz * forward;
        sx += ux * -down; sy += uy * -down; sz += uz * -down;
        out.start = new Vec3d(sx, sy, sz);
        out.dir = new Vec3d(fx, fy, fz); out.side = new Vec3d(rx, 0, rz); out.up = new Vec3d(ux, uy, uz);
    }

    public static int color(double strength) {
        return ((int) Math.round(ALPHA * Math.max(0.0, strength)) << 24) | RGB;
    }

    public static void item(VertexWriter out, float scale, double ex, double ey, double ez) {
        if (!visibleScale(scale)) return;
        float length = (float) (LENGTH_BLOCKS / scale), half = (float) (WIDTH_BLOCKS / scale / 2.0);
        for (int i = 0; i < SEGMENTS; i++) {
            float z0 = (float) ez + length * i / SEGMENTS, z1 = (float) ez + length * (i + 1) / SEGMENTS;
            int c0 = color(1.0 - (double) i / SEGMENTS), c1 = color(1.0 - (double) (i + 1) / SEGMENTS);
            // a flat ribbon and an upright one, each seen from both sides
            out.decal((float) ex - half, (float) ey, z0, c0, 0F, 0F, FULL_BRIGHT, 0F, 1F, 0F);
            out.decal((float) ex + half, (float) ey, z0, c0, 0F, 0F, FULL_BRIGHT, 0F, 1F, 0F);
            out.decal((float) ex + half, (float) ey, z1, c1, 0F, 0F, FULL_BRIGHT, 0F, 1F, 0F);
            out.decal((float) ex - half, (float) ey, z1, c1, 0F, 0F, FULL_BRIGHT, 0F, 1F, 0F);
            out.decal((float) ex - half, (float) ey, z1, c1, 0F, 0F, FULL_BRIGHT, 0F, 1F, 0F);
            out.decal((float) ex + half, (float) ey, z1, c1, 0F, 0F, FULL_BRIGHT, 0F, 1F, 0F);
            out.decal((float) ex + half, (float) ey, z0, c0, 0F, 0F, FULL_BRIGHT, 0F, 1F, 0F);
            out.decal((float) ex - half, (float) ey, z0, c0, 0F, 0F, FULL_BRIGHT, 0F, 1F, 0F);
            out.decal((float) ex, (float) ey - half, z0, c0, 0F, 0F, FULL_BRIGHT, 0F, 1F, 0F);
            out.decal((float) ex, (float) ey + half, z0, c0, 0F, 0F, FULL_BRIGHT, 0F, 1F, 0F);
            out.decal((float) ex, (float) ey + half, z1, c1, 0F, 0F, FULL_BRIGHT, 0F, 1F, 0F);
            out.decal((float) ex, (float) ey - half, z1, c1, 0F, 0F, FULL_BRIGHT, 0F, 1F, 0F);
            out.decal((float) ex, (float) ey - half, z1, c1, 0F, 0F, FULL_BRIGHT, 0F, 1F, 0F);
            out.decal((float) ex, (float) ey + half, z1, c1, 0F, 0F, FULL_BRIGHT, 0F, 1F, 0F);
            out.decal((float) ex, (float) ey + half, z0, c0, 0F, 0F, FULL_BRIGHT, 0F, 1F, 0F);
            out.decal((float) ex, (float) ey - half, z0, c0, 0F, 0F, FULL_BRIGHT, 0F, 1F, 0F);
        }
    }

    public static void beam(VertexWriter out, Line line, double camX, double camY, double camZ) {
        Vec3d start = line.start, dir = line.dir, side = line.side, up = line.up;
        double half = WIDTH_BLOCKS / 2.0, length = LENGTH_BLOCKS;
        int n = SEGMENTS;
        Vec3d o = start.subtract(camX, camY, camZ);
        for (int i = 0; i < n; i++) {
            Vec3d a = o.add(dir.scale(length * i / n)), b = o.add(dir.scale(length * (i + 1) / n));
            int ca = color(1.0 - (double) i / n), cb = color(1.0 - (double) (i + 1) / n);
            for (int ribbon = 0; ribbon < 2; ribbon++) {
                Vec3d w = ribbon == 0 ? side : up;
                Vec3d k = w.scale(half);
                corner(out, a.subtract(k), ca);
                corner(out, a.add(k), ca);
                corner(out, b.add(k), cb);
                corner(out, b.subtract(k), cb);
                corner(out, b.subtract(k), cb);
                corner(out, b.add(k), cb);
                corner(out, a.add(k), ca);
                corner(out, a.subtract(k), ca);
            }
        }
    }

    private static void corner(VertexWriter out, Vec3d v, int argb) {
        out.color((float) v.x(), (float) v.y(), (float) v.z(), argb);
    }
}

