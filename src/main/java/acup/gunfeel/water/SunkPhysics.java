package acup.gunfeel.water;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Client-side bodies for rounds that water stopped: they sink, tumble and lie on the bottom.
 * Capsules and spheres, a uniform grid, impulses with spin, sleeping. No Minecraft classes:
 * the world comes through {@link World}.
 */
public final class SunkPhysics {
    /** Solid boxes of the blocks and water around a point. */
    public interface World {
        /** Collision boxes of the block at (x,y,z), world coordinates {minX,minY,minZ,maxX,maxY,maxZ}*n; empty for none. */
        double[] boxes(int x, int y, int z);

        boolean water(int x, int y, int z);

        /** Something that changes when the block at (x,y,z) changes; a sleeping body wakes when one under it does. */
        default int block(int x, int y, int z) {
            return java.util.Arrays.hashCode(boxes(x, y, z));
        }

        /** False where the world is not there now (an unloaded chunk): a body there is left as it is. */
        default boolean loaded(int x, int z) {
            return true;
        }
    }

    public static final class Body {
        public final int id;
        public final Object tag;
        public double px, py, pz, vx, vy, vz, wx, wy, wz;
        /** Orientation x,y,z,w; the capsule axis is local -Z. */
        public final float[] q = {0, 0, 0, 1};
        public double r, h;
        public double age;
        public boolean asleep;
        /** 1 alive, 0 gone (the last second). */
        public float fade = 1;
        public int shot;
        public float he;
        public boolean inc;
        public boolean boom;

        public double ax, ay, az, im, iI, term, sleepT;
        public double groundT = 9;
        boolean ground;
        int idx;
        // blocks around the ends, refreshed when an end changes its cell or on a new step
        double[] bx = new double[48];
        int bn, cacheFrame = -1, c0x, c0y, c0z, c1x, c1y, c1z;
        // the blocks a sleeping body touches, taken on its first frame asleep
        int support;
        boolean supportSet;

        Body(int id, Object tag) {
            this.id = id;
            this.tag = tag;
        }
    }

    private static final double CELL = 0.45, SUB = 0.004, G_WATER = 8.9, G_AIR = 20, TERM_AIR = 40;
    /** A touch counts a bit before the surface, so a resting body keeps its contact impulses between substeps. */
    /** More than the demo's 3: a resting capsule needs them to stop rocking on its two ends. */
    private static final int ITER = 5;
    private static final double SKIN = 0.002;
    /** At rest when its far end moves under 0.018 blocks a second (0.3 rad/s at the ×6 rounds' 0.06 reach) as well. */
    private static final double SLEEP_TIP2 = 0.018 * 0.018;

    private final int max;
    private final double lie;
    private final ArrayList<Body> bodies = new ArrayList<>();
    private final List<Body> view = Collections.unmodifiableList(bodies);
    private final ArrayList<Body> booms = new ArrayList<>();
    private final SplittableRandom rnd = new SplittableRandom(12345);
    private int nextId, frame, stamp;
    private double ms;

    // grid: open addressing over exact cell coordinates, chains through next[]
    private final int mask;
    private final int[] gx, gy, gz, ghead, gstamp, next;
    private final double[] e1 = new double[6], e2 = new double[6], cc = new double[6];

    public SunkPhysics(int max, double lie) {
        this.max = max;
        this.lie = lie;
        int cap = Integer.highestOneBit(Math.max(8, max * 4 - 1)) << 1;
        mask = cap - 1;
        gx = new int[cap];
        gy = new int[cap];
        gz = new int[cap];
        ghead = new int[cap];
        gstamp = new int[cap];
        next = new int[max];
    }

    public List<Body> bodies() {
        return view;
    }

    public double lastMs() {
        return ms;
    }

    public void clear() {
        bodies.clear();
        booms.clear();
    }

    public Body add(Object tag, double x, double y, double z, double dirX, double dirY, double dirZ,
            double r, double h, double vx, double vy, double vz, double spin, double terminal,
            int shot, float he, boolean inc) {
        while (bodies.size() >= max) {
            Body old = bodies.remove(0);
            wakeNear(old.px, old.py, old.pz, 1);
        }
        Body b = new Body(nextId++, tag);
        b.px = x;
        b.py = y;
        b.pz = z;
        b.vx = vx;
        b.vy = vy;
        b.vz = vz;
        b.r = r;
        b.h = h;
        double vol = r * r * (2 * h + 1.33 * r);
        b.im = 1 / vol;
        b.iI = 1 / (vol * (h * h / 3 + r * r * 0.4));
        b.term = terminal;
        b.shot = shot;
        b.he = he;
        b.inc = inc;
        double l = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        double dx = 0, dy = 0, dz = -1;
        if (l > 1e-9) {
            dx = dirX / l;
            dy = dirY / l;
            dz = dirZ / l;
        }
        // rotation from (0,0,-1) to dir
        double dot = -dz, qx, qy, qz, qw;
        if (dot < -0.999999) {
            qx = 0;
            qy = 1;
            qz = 0;
            qw = 0;
        } else {
            qx = dy;
            qy = -dx;
            qz = 0;
            qw = 1 + dot;
            double n = Math.sqrt(qx * qx + qy * qy + qw * qw);
            qx /= n;
            qy /= n;
            qw /= n;
        }
        b.q[0] = (float) qx;
        b.q[1] = (float) qy;
        b.q[2] = (float) qz;
        b.q[3] = (float) qw;
        axis(b);
        // spin around the perpendicular to the axis
        double ppx = -b.az, ppz = b.ax, pl = Math.sqrt(ppx * ppx + ppz * ppz);
        if (pl * pl < 1e-6) {
            ppx = 1;
            ppz = 0;
            pl = 1;
        }
        b.wx = ppx / pl * spin;
        b.wz = ppz / pl * spin;
        b.idx = bodies.size();
        bodies.add(b);
        return b;
    }

    /** Takes the bodies that went off in the last steps; they are already out of the list. */
    public List<Body> takeBooms() {
        if (booms.isEmpty()) return Collections.emptyList();
        List<Body> out = new ArrayList<>(booms);
        booms.clear();
        return out;
    }

    /** Pushes the bodies near a blast and wakes them; draws nothing. */
    public void blast(double x, double y, double z, double power, boolean inc) {
        double push = inc ? 0.3 : 1;
        for (int i = 0, n = bodies.size(); i < n; i++) {
            Body b = bodies.get(i);
            double dx = b.px - x, dy = b.py - y, dz = b.pz - z, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > 3 * power) continue;
            double k = 6 * power / (d * d + 0.05) * Math.min(1, 1 / (b.im * b.r * b.r * b.r * 40));
            double s = Math.min(4, k) * push / Math.max(d, 1e-3);
            b.vx += dx * s;
            b.vy += dy * s;
            b.vz += dz * s;
            double wk = Math.min(25, k * 4);
            b.wx = rnd.nextDouble(-1, 1) * wk;
            b.wy = rnd.nextDouble(-1, 1) * wk;
            b.wz = rnd.nextDouble(-1, 1) * wk;
            wake(b);
        }
    }

    public void wakeNear(double x, double y, double z, double radius) {
        double r2 = radius * radius;
        for (int i = 0, n = bodies.size(); i < n; i++) {
            Body b = bodies.get(i);
            double dx = b.px - x, dy = b.py - y, dz = b.pz - z;
            if (dx * dx + dy * dy + dz * dz <= r2) wake(b);
        }
    }

    public void step(World w, double dt) {
        if (!(dt > 0)) return;
        long t0 = System.nanoTime();
        frame++;
        // a sleeping body skips the blocks, so a block broken under it would leave it hanging in the water
        for (int i = 0, m = bodies.size(); i < m; i++) {
            Body b = bodies.get(i);
            if (!b.asleep || !w.loaded((int) Math.floor(b.px), (int) Math.floor(b.pz))) continue;
            int s = support(w, b);
            if (!b.supportSet) {
                b.support = s;
                b.supportSet = true;
            } else if (s != b.support) {
                wake(b);
                wakeNear(b.px, b.py, b.pz, 1);
            }
        }
        int n = (int) Math.ceil(dt / SUB);
        double h = dt / n;
        for (int s = 0; s < n; s++) sub(w, h);
        ms = ms * 0.9 + (System.nanoTime() - t0) * 1e-6 * 0.1;
    }

    private static void wake(Body b) {
        b.asleep = false;
        b.sleepT = 0;
        b.supportSet = false;
    }

    /** The cells each end's sphere reaches and one more below (a fence or a wall stands higher than its cell), a hash
     *  of what is in them. */
    private static int support(World w, Body b) {
        int hash = 1;
        double r = b.r + SKIN;
        for (int e = 0; e < (b.h > 0 ? 2 : 1); e++) {
            double k = e == 0 ? -b.h : b.h, ex = b.px + b.ax * k, ey = b.py + b.ay * k, ez = b.pz + b.az * k;
            int x1 = (int) Math.floor(ex + r), y1 = (int) Math.floor(ey + r), z1 = (int) Math.floor(ez + r);
            for (int x = (int) Math.floor(ex - r); x <= x1; x++)
                for (int y = (int) Math.floor(ey - r) - 1; y <= y1; y++)
                    for (int z = (int) Math.floor(ez - r); z <= z1; z++) hash = hash * 31 + w.block(x, y, z);
        }
        return hash;
    }

    private static void axis(Body b) {
        double x = b.q[0], y = b.q[1], z = b.q[2], w = b.q[3];
        b.ax = -2 * (x * z + w * y);
        b.ay = -2 * (y * z - w * x);
        b.az = -(1 - 2 * (x * x + y * y));
    }

    /** End points of the capsule axis (the same point twice for a sphere) into E[0..5]. */
    public static void ends(Body b, double[] e) {
        e[0] = b.px - b.ax * b.h;
        e[1] = b.py - b.ay * b.h;
        e[2] = b.pz - b.az * b.h;
        e[3] = b.px + b.ax * b.h;
        e[4] = b.py + b.ay * b.h;
        e[5] = b.pz + b.az * b.h;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Closest points of segments A0A1 and B0B1 (Ericson, Real-Time Collision Detection 5.1.9) into C. */
    public static void closest(double[] A, double[] B, double[] C) {
        double d1x = A[3] - A[0], d1y = A[4] - A[1], d1z = A[5] - A[2];
        double d2x = B[3] - B[0], d2y = B[4] - B[1], d2z = B[5] - B[2];
        double rx = A[0] - B[0], ry = A[1] - B[1], rz = A[2] - B[2];
        double a = d1x * d1x + d1y * d1y + d1z * d1z, e = d2x * d2x + d2y * d2y + d2z * d2z;
        double f = d2x * rx + d2y * ry + d2z * rz, s = 0, t = 0;
        if (a < 1e-9 && e < 1e-9) {
            s = 0;
            t = 0;
        } else if (a < 1e-9) {
            t = clamp(f / e, 0, 1);
        } else {
            double c = d1x * rx + d1y * ry + d1z * rz;
            if (e < 1e-9) {
                s = clamp(-c / a, 0, 1);
            } else {
                double b = d1x * d2x + d1y * d2y + d1z * d2z, den = a * e - b * b;
                s = den > 1e-12 ? clamp((b * f - c * e) / den, 0, 1) : 0;
                t = (b * s + f) / e;
                if (t < 0) {
                    t = 0;
                    s = clamp(-c / a, 0, 1);
                } else if (t > 1) {
                    t = 1;
                    s = clamp((b - c) / a, 0, 1);
                }
            }
        }
        C[0] = A[0] + d1x * s;
        C[1] = A[1] + d1y * s;
        C[2] = A[2] + d1z * s;
        C[3] = B[0] + d2x * t;
        C[4] = B[1] + d2y * t;
        C[5] = B[2] + d2z * t;
    }

    /** Impulse j along n at arm r: linear and angular. */
    private static void impulse(Body b, double rx, double ry, double rz, double nx, double ny, double nz, double j) {
        b.vx += nx * j * b.im;
        b.vy += ny * j * b.im;
        b.vz += nz * j * b.im;
        double k = j * b.iI;
        b.wx += (ry * nz - rz * ny) * k;
        b.wy += (rz * nx - rx * nz) * k;
        b.wz += (rx * ny - ry * nx) * k;
    }

    /** Body touching something static at point c with outward normal n. */
    private static void contact(Body b, double cx, double cy, double cz, double nx, double ny, double nz, double pen) {
        if (b.he > 0) {
            b.boom = true;
            return;
        }
        b.px += nx * pen;
        b.py += ny * pen;
        b.pz += nz * pen;
        double rx = cx - b.px, ry = cy - b.py, rz = cz - b.pz;
        double ux = b.vx + b.wy * rz - b.wz * ry, uy = b.vy + b.wz * rx - b.wx * rz, uz = b.vz + b.wx * ry - b.wy * rx;
        double vn = ux * nx + uy * ny + uz * nz;
        if (vn < 0) {
            double qx = ry * nz - rz * ny, qy = rz * nx - rx * nz, qz = rx * ny - ry * nx;
            double k = b.im + (qx * qx + qy * qy + qz * qz) * b.iI;
            // no bounce for a slow touch, or a resting capsule rocks on its ends
            impulse(b, rx, ry, rz, nx, ny, nz, -(vn < -0.15 ? 1.15 : 1.0) * vn / k);
        }
        ux = b.vx + b.wy * rz - b.wz * ry;
        uy = b.vy + b.wz * rx - b.wx * rz;
        uz = b.vz + b.wx * ry - b.wy * rx;
        double un = ux * nx + uy * ny + uz * nz;
        ux -= nx * un;
        uy -= ny * un;
        uz -= nz * un;
        double sl = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (sl > 1e-5) {
            double tx = -ux / sl, ty = -uy / sl, tz = -uz / sl;
            double qx = ry * tz - rz * ty, qy = rz * tx - rx * tz, qz = rx * ty - ry * tx;
            double k = b.im + (qx * qx + qy * qy + qz * qz) * b.iI;
            impulse(b, rx, ry, rz, tx, ty, tz, sl * 0.5 / k);
        }
    }

    private void pair(Body a, Body b) {
        ends(a, e1);
        ends(b, e2);
        closest(e1, e2, cc);
        double nx = cc[0] - cc[3], ny = cc[1] - cc[4], nz = cc[2] - cc[5];
        double d2 = nx * nx + ny * ny + nz * nz, rr = a.r + b.r;
        if (d2 >= (rr + SKIN) * (rr + SKIN)) return;
        double d = Math.sqrt(d2);
        if (d < 1e-6) {
            nx = 0;
            ny = 1;
            nz = 0;
            d = 1e-6;
        } else {
            nx /= d;
            ny /= d;
            nz /= d;
        }
        // a fuze goes off on anything; only pellets of one shot just bump into each other
        if ((a.he > 0 || b.he > 0) && !(a.he > 0 && b.he > 0 && a.shot == b.shot)) {
            if (d >= rr) return;
            if (a.he > 0) a.boom = true;
            if (b.he > 0) b.boom = true;
            return;
        }
        double pen = rr - d;
        // a body resting on a supported one counts as supported
        if (ny > 0.5 && (b.asleep || b.groundT < 0.2)) {
            a.groundT = 0;
            a.ground = true;
        }
        else if (ny < -0.5 && (a.asleep || a.groundT < 0.2)) {
            b.groundT = 0;
            b.ground = true;
        }
        if (b.asleep && pen > 0.002) wake(b);
        double A = a.asleep ? 0 : a.im, B = b.asleep ? 0 : b.im, sum = A + B;
        if (sum <= 0) return;
        double k = Math.max(0, pen) * 0.8 / sum;
        a.px += nx * k * A;
        a.py += ny * k * A;
        a.pz += nz * k * A;
        b.px -= nx * k * B;
        b.py -= ny * k * B;
        b.pz -= nz * k * B;
        double r1x = cc[0] - nx * a.r - a.px, r1y = cc[1] - ny * a.r - a.py, r1z = cc[2] - nz * a.r - a.pz;
        double r2x = cc[3] + nx * b.r - b.px, r2y = cc[4] + ny * b.r - b.py, r2z = cc[5] + nz * b.r - b.pz;
        double ux = a.vx + a.wy * r1z - a.wz * r1y - (b.vx + b.wy * r2z - b.wz * r2y);
        double uy = a.vy + a.wz * r1x - a.wx * r1z - (b.vy + b.wz * r2x - b.wx * r2z);
        double uz = a.vz + a.wx * r1y - a.wy * r1x - (b.vz + b.wx * r2y - b.wy * r2x);
        double vn = ux * nx + uy * ny + uz * nz;
        if (vn < 0) {
            double q1x = r1y * nz - r1z * ny, q1y = r1z * nx - r1x * nz, q1z = r1x * ny - r1y * nx;
            double q2x = r2y * nz - r2z * ny, q2y = r2z * nx - r2x * nz, q2z = r2x * ny - r2y * nx;
            double c1 = q1x * q1x + q1y * q1y + q1z * q1z, c2 = q2x * q2x + q2y * q2y + q2z * q2z;
            double j = -1.0 * vn / (A + B + c1 * (A > 0 ? a.iI : 0) + c2 * (B > 0 ? b.iI : 0));
            if (A > 0) impulse(a, r1x, r1y, r1z, nx, ny, nz, j);
            if (B > 0) impulse(b, r2x, r2y, r2z, -nx, -ny, -nz, j);
        }
    }

    private int slot(int x, int y, int z, boolean create) {
        int hh = x * 0x9E3779B1 ^ y * 0x85EBCA6B ^ z * 0xC2B2AE35;
        hh ^= hh >>> 15;
        int s = hh & mask;
        while (true) {
            if (gstamp[s] != stamp) {
                if (!create) return -1;
                gstamp[s] = stamp;
                gx[s] = x;
                gy[s] = y;
                gz[s] = z;
                ghead[s] = -1;
                return s;
            }
            if (gx[s] == x && gy[s] == y && gz[s] == z) return s;
            s = (s + 1) & mask;
        }
    }

    private static int cell(double v) {
        return (int) Math.floor(v / CELL);
    }

    private void gather(World w, Body b) {
        int x0 = (int) Math.floor(b.px - b.ax * b.h), y0 = (int) Math.floor(b.py - b.ay * b.h), z0 = (int) Math.floor(b.pz - b.az * b.h);
        int x1 = (int) Math.floor(b.px + b.ax * b.h), y1 = (int) Math.floor(b.py + b.ay * b.h), z1 = (int) Math.floor(b.pz + b.az * b.h);
        if (b.cacheFrame == frame && x0 == b.c0x && y0 == b.c0y && z0 == b.c0z && x1 == b.c1x && y1 == b.c1y && z1 == b.c1z) return;
        b.cacheFrame = frame;
        b.c0x = x0;
        b.c0y = y0;
        b.c0z = z0;
        b.c1x = x1;
        b.c1y = y1;
        b.c1z = z1;
        b.bn = 0;
        boolean two = b.h > 0 && (x1 != x0 || y1 != y0 || z1 != z0);
        for (int e = 0; e < (two ? 2 : 1); e++) {
            int cx = e == 0 ? x0 : x1, cy = e == 0 ? y0 : y1, cz = e == 0 ? z0 : z1;
            for (int ix = -1; ix <= 1; ix++)
                for (int iy = -1; iy <= 1; iy++)
                    for (int iz = -1; iz <= 1; iz++) {
                        if (e == 1 && Math.abs(cx + ix - x0) <= 1 && Math.abs(cy + iy - y0) <= 1 && Math.abs(cz + iz - z0) <= 1) continue;
                        double[] bx = w.boxes(cx + ix, cy + iy, cz + iz);
                        if (bx == null || bx.length == 0) continue;
                        if (b.bn * 6 + bx.length > b.bx.length) b.bx = java.util.Arrays.copyOf(b.bx, Math.max(b.bx.length * 2, b.bn * 6 + bx.length));
                        System.arraycopy(bx, 0, b.bx, b.bn * 6, bx.length);
                        b.bn += bx.length / 6;
                    }
        }
    }

    private void blocks(Body b) {
        double r = b.r;
        // both ends are tested from the pose at the start, so one push does not hide the other end's contact
        double s0x = b.px - b.ax * b.h, s0y = b.py - b.ay * b.h, s0z = b.pz - b.az * b.h;
        double s1x = b.px + b.ax * b.h, s1y = b.py + b.ay * b.h, s1z = b.pz + b.az * b.h;
        for (int e = 0; e < (b.h > 0 ? 2 : 1); e++) {
            double ox0 = b.px, oy0 = b.py, oz0 = b.pz;
            for (int k = 0; k < b.bn; k++) {
                int o = k * 6;
                double[] bx = b.bx;
                double ex = (e == 0 ? s0x : s1x) + b.px - ox0, ey = (e == 0 ? s0y : s1y) + b.py - oy0, ez = (e == 0 ? s0z : s1z) + b.pz - oz0;
                double qx = clamp(ex, bx[o], bx[o + 3]), qy = clamp(ey, bx[o + 1], bx[o + 4]), qz = clamp(ez, bx[o + 2], bx[o + 5]);
                double dx = ex - qx, dy = ey - qy, dz = ez - qz, d2 = dx * dx + dy * dy + dz * dz;
                if (d2 >= (r + SKIN) * (r + SKIN)) continue;
                double nx, ny, nz, pen;
                if (d2 > 1e-18) {
                    double d = Math.sqrt(d2);
                    nx = dx / d;
                    ny = dy / d;
                    nz = dz / d;
                    pen = r - d;
                    if (pen <= 0 && b.he > 0) continue;
                } else {
                    // centre inside the box: leave by the nearest face
                    double best = ex - bx[o];
                    nx = -1;
                    ny = 0;
                    nz = 0;
                    double t = bx[o + 3] - ex;
                    if (t < best) { best = t; nx = 1; }
                    t = ey - bx[o + 1];
                    if (t < best) { best = t; nx = 0; ny = -1; }
                    t = bx[o + 4] - ey;
                    if (t < best) { best = t; nx = 0; ny = 1; }
                    t = ez - bx[o + 2];
                    if (t < best) { best = t; nx = 0; ny = 0; nz = -1; }
                    t = bx[o + 5] - ez;
                    if (t < best) { best = t; nx = 0; ny = 0; nz = 1; }
                    qx = ex + nx * best;
                    qy = ey + ny * best;
                    qz = ez + nz * best;
                    pen = r + best;
                }
                contact(b, qx, qy, qz, nx, ny, nz, Math.max(0, pen));
                if (b.boom) return;
                if (ny > 0.5) {
                    b.ground = true;
                    b.groundT = 0;
                }
            }
        }
    }

    private void sub(World w, double dt) {
        int n = bodies.size();
        boolean anyAwake = false;
        for (int i = 0; i < n && !anyAwake; i++) anyAwake = !bodies.get(i).asleep;
        if (anyAwake) buildAndMove(w, dt, n);
        else for (int i = 0; i < n; i++) bodies.get(i).age += dt;
        finish(dt);
    }

    private void buildAndMove(World w, double dt, int n) {
        stamp++;
        for (int i = 0; i < n; i++) {
            Body b = bodies.get(i);
            b.idx = i;
            int s = slot(cell(b.px), cell(b.py), cell(b.pz), true);
            next[i] = ghead[s];
            ghead[s] = i;
        }
        double kg = Math.exp(-8 * dt), kw = Math.exp(-1.5 * dt);
        for (int i = 0; i < n; i++) {
            Body b = bodies.get(i);
            b.age += dt;
            b.groundT += dt;
            if (b.asleep) continue;
            // on the bottom: rolling and sliding die out fast
            if (b.ground) {
                b.vx *= kg;
                b.vz *= kg;
                b.wx *= kg;
                b.wy *= kg;
                b.wz *= kg;
            }
            b.ground = false;
            boolean wet = w.water((int) Math.floor(b.px), (int) Math.floor(b.py), (int) Math.floor(b.pz));
            double g = wet ? G_WATER : G_AIR, term = wet ? b.term : TERM_AIR, cd = g / (term * term);
            double sp = Math.sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz);
            b.vy -= g * dt;
            double dr = 1 / (1 + cd * sp * dt);
            b.vx *= dr;
            b.vy *= dr;
            b.vz *= dr;
            b.wx *= kw;
            b.wy *= kw;
            b.wz *= kw;
            b.px += b.vx * dt;
            b.py += b.vy * dt;
            b.pz += b.vz * dt;
            double wl = Math.sqrt(b.wx * b.wx + b.wy * b.wy + b.wz * b.wz);
            if (wl > 1e-6) {
                double ang = wl * dt * 0.5, sn = Math.sin(ang) / wl, rx = b.wx * sn, ry = b.wy * sn, rz = b.wz * sn, rw = Math.cos(ang);
                double qx = b.q[0], qy = b.q[1], qz = b.q[2], qw = b.q[3];
                // premultiply: r * q
                double ox = rw * qx + rx * qw + ry * qz - rz * qy;
                double oy = rw * qy - rx * qz + ry * qw + rz * qx;
                double oz = rw * qz + rx * qy - ry * qx + rz * qw;
                double ow = rw * qw - rx * qx - ry * qy - rz * qz;
                double nl = 1 / Math.sqrt(ox * ox + oy * oy + oz * oz + ow * ow);
                b.q[0] = (float) (ox * nl);
                b.q[1] = (float) (oy * nl);
                b.q[2] = (float) (oz * nl);
                b.q[3] = (float) (ow * nl);
                axis(b);
            }
            gather(w, b);
        }
        for (int it = 0; it < ITER; it++) {
            for (int i = 0; i < n; i++) {
                Body b = bodies.get(i);
                if (b.asleep || b.boom) continue;
                blocks(b);
                if (b.boom) continue;
                int cx = cell(b.px), cy = cell(b.py), cz = cell(b.pz);
                for (int ix = -1; ix <= 1; ix++)
                    for (int iy = -1; iy <= 1; iy++)
                        for (int iz = -1; iz <= 1; iz++) {
                            int s = slot(cx + ix, cy + iy, cz + iz, false);
                            if (s < 0) continue;
                            for (int j = ghead[s]; j >= 0; j = next[j]) {
                                Body o = bodies.get(j);
                                if (o == b || (!o.asleep && o.id < b.id)) continue;
                                pair(b, o);
                            }
                        }
            }
        }
    }

    private void finish(double dt) {
        for (int i = bodies.size() - 1; i >= 0; i--) {
            Body b = bodies.get(i);
            if (!b.boom) continue;
            bodies.remove(i);
            booms.add(b);
        }
        for (int i = bodies.size() - 1; i >= 0; i--) {
            Body b = bodies.get(i);
            if (!b.asleep) {
                double v2 = b.vx * b.vx + b.vy * b.vy + b.vz * b.vz, w2 = b.wx * b.wx + b.wy * b.wy + b.wz * b.wz;
                // turning: by how fast its far end moves, so a small round that rocks a little still comes to rest
                double reach = b.h + b.r;
                if (v2 < 0.0025 && w2 < Math.max(0.09, SLEEP_TIP2 / (reach * reach)) && b.groundT < 0.2) {
                    b.sleepT += dt;
                    if (b.sleepT > 0.4) {
                        b.asleep = true;
                        b.vx = b.vy = b.vz = b.wx = b.wy = b.wz = 0;
                    }
                } else b.sleepT = 0;
            }
            if (b.age > lie) {
                b.fade = (float) Math.max(0, 1 - (b.age - lie));
                if (b.age > lie + 1) {
                    bodies.remove(i);
                    wakeNear(b.px, b.py, b.pz, 1);
                }
            }
        }
    }
}
