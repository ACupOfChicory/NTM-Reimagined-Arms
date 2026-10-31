package acup.gunfeel.phys;

// The immutable vector of 26.2 (net.minecraft.world.phys.Vec3), as far as the shared hitbox code uses it. 1.7.10's
// net.minecraft.util.Vec3 is mutable and pooled, so the 26.2 files listed under [main-phys] in shared.txt are compiled
// with their import pointed here (build.gradle.kts). Each method computes exactly what 26.2's does, bit for bit:
// subtract is add of the negation, yRot goes through the same 65536-entry sine table as 26.2's Mth.sin/cos, and
// normalize has the same 1e-5F cut-off.
public final class Vec3 {
    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public final double x, y, z;

    public Vec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public Vec3 add(Vec3 v) {
        return add(v.x, v.y, v.z);
    }

    public Vec3 add(double dx, double dy, double dz) {
        return new Vec3(x + dx, y + dy, z + dz);
    }

    public Vec3 subtract(Vec3 v) {
        return subtract(v.x, v.y, v.z);
    }

    public Vec3 subtract(double dx, double dy, double dz) {
        return add(-dx, -dy, -dz);
    }

    public Vec3 scale(double f) {
        return multiply(f, f, f);
    }

    public Vec3 multiply(double fx, double fy, double fz) {
        return new Vec3(x * fx, y * fy, z * fz);
    }

    public Vec3 reverse() {
        return scale(-1);
    }

    public double dot(Vec3 v) {
        return x * v.x + y * v.y + z * v.z;
    }

    public Vec3 cross(Vec3 v) {
        return new Vec3(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x);
    }

    public double lengthSqr() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double distanceToSqr(Vec3 v) {
        double dx = v.x - x, dy = v.y - y, dz = v.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distanceTo(Vec3 v) {
        return Math.sqrt(distanceToSqr(v));
    }

    public Vec3 normalize() {
        double d = Math.sqrt(x * x + y * y + z * z);
        return d < 1.0E-5F ? ZERO : new Vec3(x / d, y / d, z / d);
    }

    // Mth.lerp(t, a, b) = a + t * (b - a)
    public Vec3 lerp(Vec3 to, double t) {
        return new Vec3(x + t * (to.x - x), y + t * (to.y - y), z + t * (to.z - z));
    }

    public Vec3 yRot(float angle) {
        float cos = cos(angle), sin = sin(angle);
        return new Vec3(x * cos + z * sin, y, z * cos - x * sin);
    }

    public Vec3 xRot(float angle) {
        float cos = cos(angle), sin = sin(angle);
        return new Vec3(x, y * cos + z * sin, z * cos - y * sin);
    }

    private static final float[] SIN = new float[65536];
    static {
        for (int i = 0; i < SIN.length; i++) SIN[i] = (float) Math.sin(i * Math.PI * 2.0 / 65536.0);
    }

    static float sin(double a) {
        return SIN[(int) ((long) (a * 10430.378350470453) & 65535L)];
    }

    static float cos(double a) {
        return SIN[(int) ((long) (a * 10430.378350470453 + 16384.0) & 65535L)];
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Vec3)) return false;
        Vec3 v = (Vec3) o;
        return Double.compare(v.x, x) == 0 && Double.compare(v.y, y) == 0 && Double.compare(v.z, z) == 0;
    }

    @Override
    public int hashCode() {
        long j = Double.doubleToLongBits(x);
        int i = (int) (j ^ j >>> 32);
        j = Double.doubleToLongBits(y);
        i = 31 * i + (int) (j ^ j >>> 32);
        j = Double.doubleToLongBits(z);
        return 31 * i + (int) (j ^ j >>> 32);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
