package acup.gunfeel.gl54.frag;

// The directions of one burst's fragments, made from a seed alone: the server casts them and the client draws them, and
// both get the same ones. A ray's index below `cone` is a cone ray, from there on a ring ray. Each group is spread evenly
// by solid angle (the cosine of the cone's angle, and the ring's height across the flight, are taken in equal strata with
// a jitter; the turn round the flight goes by the golden angle from a start the seed gives), so 20 fragments cover the
// cone instead of clumping. Nothing is allocated: the answer goes into a caller's array.
public final class FragPattern {
    private static final double GOLDEN = 0.6180339887498949;

    private FragPattern() {
    }

    // SplitMix64's finalizer: a well mixed number from a counter
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // a number in [0, 1) for this seed, ray and purpose
    static double unit(long seed, int ray, int salt) {
        long z = mix(seed + 0x9E3779B97F4A7C15L * (ray * 4L + salt + 1));
        return (z >>> 11) * 0x1.0p-53;
    }

    // ray `i` of the burst as a unit vector into out[0..2], for the flight direction (fx, fy, fz) (any length but zero)
    public static void direction(FragSpec spec, long seed, int i, double fx, double fy, double fz, double[] out) {
        double len = Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= len;
        fy /= len;
        fz /= len;
        // e1 = normalize(h x f), e2 = f x e1: two axes across the flight
        double hx = 0, hy = 1, hz = 0;
        if (Math.abs(fy) > 0.99) {
            hx = 1;
            hy = 0;
        }
        double e1x = hy * fz - hz * fy, e1y = hz * fx - hx * fz, e1z = hx * fy - hy * fx;
        double l1 = Math.sqrt(e1x * e1x + e1y * e1y + e1z * e1z);
        e1x /= l1;
        e1y /= l1;
        e1z /= l1;
        double e2x = fy * e1z - fz * e1y, e2y = fz * e1x - fx * e1z, e2z = fx * e1y - fy * e1x;
        boolean cone = i < spec.cone();
        int n = cone ? spec.cone() : spec.ring(), k = cone ? i : i - spec.cone();
        double u = (k + unit(seed, i, 0)) / n;
        // the turn round the flight: the golden angle from a start the seed gives for the group
        double turn = 2 * Math.PI * ((k * GOLDEN + unit(seed, cone ? -1 : -2, 1)) % 1.0);
        // along: the part of the ray along the flight; across: the rest
        double along, across;
        if (cone) {
            along = 1 - u * (1 - Math.cos(Math.toRadians(spec.coneDegrees() / 2)));
        } else {
            along = Math.sin(Math.toRadians(FragSpec.RING_DEGREES)) * (2 * u - 1);
        }
        across = Math.sqrt(Math.max(0, 1 - along * along));
        double c = across * Math.cos(turn), s = across * Math.sin(turn);
        out[0] = fx * along + e1x * c + e2x * s;
        out[1] = fy * along + e1y * c + e2y * s;
        out[2] = fz * along + e1z * c + e2z * s;
    }
}
