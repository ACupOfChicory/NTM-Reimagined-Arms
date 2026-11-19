package acup.gunfeel;

/**
 * A point or a direction in the world, for core's code: what Minecraft's {@code Vec3} is to the version's code, with the
 * very same arithmetic (each method does the operations of 26.2's {@code Vec3} in the same order, so its doubles come out
 * bit for bit the same; Vec3dTest on 26.2 holds it). The version turns one into the other where its code meets core's
 * (on 26.2 {@code Vecs}).
 */
public record Vec3d(double x, double y, double z) {
    public static final Vec3d ZERO = new Vec3d(0.0, 0.0, 0.0);

    /** Vec3.normalize's floor: a shorter vector has no direction and comes out as {@link #ZERO}. */
    private static final double NORMALIZE_FLOOR = 1.0E-5F;

    public Vec3d add(double dx, double dy, double dz) {
        return new Vec3d(x + dx, y + dy, z + dz);
    }

    public Vec3d add(Vec3d v) {
        return add(v.x, v.y, v.z);
    }

    // as Vec3: the negated amounts added
    public Vec3d subtract(double dx, double dy, double dz) {
        return add(-dx, -dy, -dz);
    }

    public Vec3d subtract(Vec3d v) {
        return subtract(v.x, v.y, v.z);
    }

    /** {@code v - this}. */
    public Vec3d vectorTo(Vec3d v) {
        return new Vec3d(v.x - x, v.y - y, v.z - z);
    }

    public Vec3d multiply(double fx, double fy, double fz) {
        return new Vec3d(x * fx, y * fy, z * fz);
    }

    public Vec3d multiply(Vec3d v) {
        return multiply(v.x, v.y, v.z);
    }

    public Vec3d scale(double f) {
        return multiply(f, f, f);
    }

    public Vec3d reverse() {
        return scale(-1.0);
    }

    public Vec3d normalize() {
        double d = Math.sqrt(x * x + y * y + z * z);
        return d < NORMALIZE_FLOOR ? ZERO : new Vec3d(x / d, y / d, z / d);
    }

    public double dot(Vec3d v) {
        return x * v.x + y * v.y + z * v.z;
    }

    public Vec3d cross(Vec3d v) {
        return new Vec3d(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double lengthSqr() {
        return x * x + y * y + z * z;
    }

    public double horizontalDistance() {
        return Math.sqrt(x * x + z * z);
    }

    public double horizontalDistanceSqr() {
        return x * x + z * z;
    }

    public double distanceTo(Vec3d v) {
        double dx = v.x - x, dy = v.y - y, dz = v.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double distanceToSqr(Vec3d v) {
        double dx = v.x - x, dy = v.y - y, dz = v.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distanceToSqr(double px, double py, double pz) {
        double dx = px - x, dy = py - y, dz = pz - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** As Vec3.lerp (Mth.lerp on each axis): {@code this + t·(v - this)}. */
    public Vec3d lerp(Vec3d v, double t) {
        return new Vec3d(x + t * (v.x - x), y + t * (v.y - y), z + t * (v.z - z));
    }

    /** This along {@code v}; {@code v} itself when it is zero. */
    public Vec3d projectedOn(Vec3d v) {
        return v.lengthSqr() == 0.0 ? v : v.scale(dot(v)).scale(1.0 / v.lengthSqr());
    }
}
