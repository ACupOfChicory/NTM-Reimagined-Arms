package acup.gunfeel;

// A tracer's path without Minecraft: where the bullet is t ticks after it left (as the server moves it), the path
// tested against the world, NTM's standard ricochet and the ribbon drawn from tail to head.
// From 26.2's Tracers (0762086): Shot.at, tested, ricochet (its angle part), the bounce in fly and streak, the same
// numbers. port: points are double[3] / float[3] instead of Vec3 and JOML Vector3f; faces are 1.7.10's side numbers.
final class TracerPath {
    // the tracer joins the bullet's real path over this many blocks from the muzzle
    static final float JOIN = 4F;

    final double[] start, v;
    final double g, speed;
    // the shooter's eyes when the bullet starts near them: its first tick is tested from there (null: from start)
    double[] eye;
    // where the muzzle drawn in the player's hands is, from start; fades out over JOIN blocks
    double[] muzzleOffset = {0, 0, 0};
    // the server stopped it there at confirmedEnd ticks (NaN: not known)
    double confirmedEnd = Double.NaN;
    double[] stopPoint;

    TracerPath(double[] start, double[] v, double g) {
        this.start = start;
        this.v = v;
        this.g = g;
        this.speed = Math.max(Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]), 1e-3);
    }

    // where the bullet is after t ticks, plus the muzzle offset fading out
    double[] at(double t) {
        if (stopPoint != null && t >= confirmedEnd - 1e-8) return stopPoint;
        double[] p = real(t);
        double k = Math.max(0.0, 1.0 - speed * t / JOIN);
        if (k > 0) {
            p[0] += muzzleOffset[0] * k;
            p[1] += muzzleOffset[1] * k;
            p[2] += muzzleOffset[2] * k;
        }
        return p;
    }

    // the bullet's own point after t ticks
    double[] real(double t) {
        return new double[] {start[0] + v[0] * t, start[1] + v[1] * t - 0.5 * g * t * t, start[2] + v[2] * t};
    }

    // its velocity after t ticks
    double[] velocity(double t) {
        return new double[] {v[0], v[1] - g * t, v[2]};
    }

    // the point of the tested path at t ticks. The first tick runs straight from the shooter's eyes to where the
    // bullet is after it, as the server tests it (a bend at the muzzle would cut corners the server's bullet passes)
    double[] tested(double t) {
        double[] p = real(t);
        if (eye == null || t >= 1.0) return p;
        double[] one = real(1.0);
        return new double[] {eye[0] + (one[0] - eye[0]) * t, eye[1] + (one[1] - eye[1]) * t, eye[2] + (one[2] - eye[2]) * t};
    }

    // NTM's standard ricochet, its angle: a bullet meeting the face at no more than maxAngle degrees off it bounces
    static boolean glances(int face, double[] vel, float maxAngle) {
        double l = Math.sqrt(vel[0] * vel[0] + vel[1] * vel[1] + vel[2] * vel[2]);
        if (l < 1e-9) return false;
        int axis = HoleMath.axis(face);
        double cos = HoleMath.step(face) * vel[axis] / l;
        double angle = Math.abs(Math.toDegrees(Math.acos(Math.min(1.0, Math.max(-1.0, cos)))) - 90.0);
        return angle <= maxAngle;
    }

    // the velocity mirrored on the face's axis, as NTM bounces it
    static double[] bounce(int face, double[] vel) {
        double[] out = vel.clone();
        int axis = HoleMath.axis(face);
        out[axis] = -out[axis];
        return out;
    }

    // A thin ribbon from tail to head (both from the camera), turned toward the camera; never thinner than about
    // `pixel` (one screen pixel at one block away) times the distance. Four corners {x, y, z}: head + side, head -
    // side, tail - side, tail + side; null when it is too short to turn.
    static float[][] streak(float[] head, float[] tail, float width, float pixel) {
        float ax = head[0] - tail[0], ay = head[1] - tail[1], az = head[2] - tail[2];
        if (ax * ax + ay * ay + az * az < 1e-8F) return null;
        // along x head
        float sx = ay * head[2] - az * head[1], sy = az * head[0] - ax * head[2], sz = ax * head[1] - ay * head[0];
        float sl = sx * sx + sy * sy + sz * sz;
        if (sl < 1e-10F) return null;
        float inv = (float) (1.0 / Math.sqrt(sl));
        sx *= inv;
        sy *= inv;
        sz *= inv;
        float hl = (float) Math.sqrt(head[0] * head[0] + head[1] * head[1] + head[2] * head[2]);
        float tl = (float) Math.sqrt(tail[0] * tail[0] + tail[1] * tail[1] + tail[2] * tail[2]);
        float wh = Math.max(width, pixel * hl), wt = Math.max(width * 0.5F, pixel * tl);
        return new float[][] {{head[0] + sx * wh, head[1] + sy * wh, head[2] + sz * wh}, {head[0] - sx * wh, head[1] - sy * wh, head[2] - sz * wh},
                {tail[0] - sx * wt, tail[1] - sy * wt, tail[2] - sz * wt}, {tail[0] + sx * wt, tail[1] + sy * wt, tail[2] + sz * wt}};
    }
}
