package acup.gunfeel;

import acup.gunfeel.host.render.VertexWriter;

/** Ring state and geometry; the version supplies the connected water and the light. */
public final class WaterRingGeometry {
    public static final int MAX_SPAN = 12;

    public interface Surface {
        double y();
        double fx();
        double fz();
    }

    public interface Host {
        Surface surface(double x, double y, double z);
        void connected(int cx, int cz, double y, double[][] allow, int span);
        int light(int x, int y, int z);
    }

    public static final int MAX = 160;

    public static final class Ring {
        public boolean on;
        public double t, delay, life, s0, s1, x, y, z, fx, fz, fm;
        public float a;
        public boolean adv;
        public int cx = Integer.MIN_VALUE, cz = Integer.MIN_VALUE, span;
        public double[][] allow = new double[49][];
    }

    public final Ring[] rings = new Ring[MAX];
    {
        for (int i = 0; i < MAX; i++) rings[i] = new Ring();
    }
    // for the stand: made, gone because they left the water, alive now
    public int made, leftWater, widest;

    public void add(double x, double y, double z, double delay, double life, double s0, double s1, float a, boolean adv) {
        for (Ring r : rings) {
            if (r.on) continue;
            r.on = true;
            r.t = 0;
            r.delay = delay;
            r.life = life;
            r.s0 = s0;
            r.s1 = s1;
            r.a = a;
            r.x = x;
            r.y = y;
            r.z = z;
            r.adv = adv;
            r.fx = r.fz = r.fm = 0;
            r.cx = r.cz = Integer.MIN_VALUE;
            // the cells it can reach: half its last size, plus the drift downstream (half the growth) and its own cell
            r.span = Math.min(MAX_SPAN, (int) Math.ceil(s1 / 2 + (adv ? (s1 - s0) / 2 : 0)) + 1);
            widest = Math.max(widest, r.span);
            int side = 2 * r.span + 1;
            if (r.allow.length < side * side) r.allow = new double[side * side][];
            made++;
            return;
        }
    }

    public int alive() {
        int n = 0;
        for (Ring r : rings) if (r.on) n++;
        return n;
    }

    public void clear() {
        for (Ring r : rings) r.on = false;
    }

    public void update(Host host, double dt, double fs) {
        for (Ring d : rings) {
            if (!d.on) continue;
            d.t += dt;
            Surface s = host.surface(d.x, d.y + 0.4, d.z);
            if (s == null || Math.abs(s.y() - d.y) > 0.3) {
                d.on = false;
                leftWater++;
                continue;
            }
            if (d.adv && (s.fx() != 0 || s.fz() != 0)) {
                double nx = d.x + s.fx() * fs * 0.5 * dt, nz = d.z + s.fz() * fs * 0.5 * dt;
                Surface s2 = host.surface(nx, d.y + 0.4, nz);
                if (s2 != null && Math.abs(s2.y() - s.y()) < 0.3) {
                    d.x = nx;
                    d.z = nz;
                }
                d.fx = s.fx();
                d.fz = s.fz();
                d.fm = Math.hypot(s.fx(), s.fz());
            }
            d.y = s.y();
            int cx = (int) Math.floor(d.x), cz = (int) Math.floor(d.z);
            if (cx != d.cx || cz != d.cz) {
                d.cx = cx;
                d.cz = cz;
                host.connected(cx, cz, d.y, d.allow, d.span);
            }
            if (d.t - d.delay >= d.life) d.on = false;
        }
    }

    private final double[] PX = new double[16], PZ = new double[16], QX = new double[16], QZ = new double[16];

    public void draw(Host host, VertexWriter out, Ring d, float atlasU0, float atlasU1, float atlasV0, float atlasV1,
            double camX, double camY, double camZ) {
        double f = (d.t - d.delay) / d.life;
        if (f >= 1) return;
        double e = 1 - Math.pow(1 - f, 2), sc = d.s0 + (d.s1 - d.s0) * e;
        // on a stream it grows only downstream: the centre moves on by half the growth
        double hx = 1, hz = 0, sh = 0;
        if (d.fm > 0.01) {
            hx = d.fx / d.fm;
            hz = d.fz / d.fm;
            sh = (sc - d.s0) * 0.5;
        }
        double cx = d.x + hx * sh, cz = d.z + hz * sh, h = sc / 2;
        // the square on the water's plane (seen from above): along the flow (or x) and across it
        double bx = -hz, bz = hx;
        float alpha = (float) (d.a * (1 - f));
        int color = ((int) (Math.clamp(alpha, 0, 1) * 255) << 24) | 0xFFFFFF;
        float iu = (atlasU1 - atlasU0) / 32F, iv = (atlasV1 - atlasV0) / 32F;
        float u0 = atlasU0 + iu, u1 = atlasU1 - iu, v0 = atlasV0 + iv, v1 = atlasV1 - iv;
        int span = d.span, side = 2 * span + 1;
        // the cells under the square as it is now (it starts small)
        double ex = h * (Math.abs(hx) + Math.abs(bx)), ez = h * (Math.abs(hz) + Math.abs(bz));
        int x0 = Math.max(-span, (int) Math.floor(cx - ex) - d.cx), x1 = Math.min(span, (int) Math.floor(cx + ex) - d.cx);
        int z0 = Math.max(-span, (int) Math.floor(cz - ez) - d.cz), z1 = Math.min(span, (int) Math.floor(cz + ez) - d.cz);
        for (int dz = z0; dz <= z1; dz++) for (int dx = x0; dx <= x1; dx++) {
            int k = (dz + span) * side + dx + span;
            double[] c = d.allow[k];
            if (c == null) continue;
            int gx = d.cx + dx, gz = d.cz + dz;
            // the square's corners, cut to the cell
            int n = 4;
            PX[0] = cx + hx * h + bx * h;
            PZ[0] = cz + hz * h + bz * h;
            PX[1] = cx - hx * h + bx * h;
            PZ[1] = cz - hz * h + bz * h;
            PX[2] = cx - hx * h - bx * h;
            PZ[2] = cz - hz * h - bz * h;
            PX[3] = cx + hx * h - bx * h;
            PZ[3] = cz + hz * h - bz * h;
            n = clip(n, gx, 1, true);
            n = clip(n, gx + 1, -1, true);
            n = clip(n, gz, 1, false);
            n = clip(n, gz + 1, -1, false);
            if (n < 3) continue;
            int light = host.light(gx, (int) Math.floor(c[0] + 0.5), gz);
            // Drawn after the water and tested against its depth, each face must lie on the side of the water it is
            // seen from: the one seen from above over it, the one seen from below under it. The water's mesh is the
            // cell's two triangles, not this smooth surface, and stands off it by up to a quarter of the cell's twist
            // (whichever diagonal the quad is cut along): that much more
            double lift = 0.03 + Math.abs(c[0] + c[2] - c[1] - c[3]) / 4;
            // a fan of triangles as quads (the last vertex twice), both ways round: the first seen from below, the
            // second from above
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 1; i + 1 < n; i++) {
                    for (int k2 = 0; k2 < 4; k2++) {
                        int j = k2 == 0 ? 0 : pass == 0 ? (k2 == 1 ? i : i + 1) : (k2 == 1 ? i + 1 : i);
                        double x = PX[j], z = PZ[j];
                        double y = height(c, x - gx, z - gz) + (pass == 0 ? -lift : lift);
                        double u = ((x - cx) * hx + (z - cz) * hz) / sc + 0.5, v = ((x - cx) * bx + (z - cz) * bz) / sc + 0.5;
                        out.particle((float) (x - camX), (float) (y - camY), (float) (z - camZ), color,
                                u0 + (float) u * (u1 - u0), v0 + (float) v * (v1 - v0), light);
                    }
                }
            }
        }
    }

    // Sutherland-Hodgman against one side of the cell; keep points on its boundary.
    private int clip(int n, double edge, int sign, boolean onX) {
        int m = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double ai = sign * ((onX ? PX[i] : PZ[i]) - edge), aj = sign * ((onX ? PX[j] : PZ[j]) - edge);
            if (ai >= 0) {
                QX[m] = PX[i];
                QZ[m] = PZ[i];
                m++;
            }
            if ((ai >= 0) != (aj >= 0)) {
                double t = ai / (ai - aj);
                QX[m] = PX[i] + (PX[j] - PX[i]) * t;
                QZ[m] = PZ[i] + (PZ[j] - PZ[i]) * t;
                m++;
            }
        }
        System.arraycopy(QX, 0, PX, 0, m);
        System.arraycopy(QZ, 0, PZ, 0, m);
        return m;
    }

    public static double height(double[] c, double u, double v) {
        return c[0] * (1 - u) * (1 - v) + c[3] * u * (1 - v) + c[1] * (1 - u) * v + c[2] * u * v;
    }
}

