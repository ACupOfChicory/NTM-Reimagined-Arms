package acup.gunfeel;

// A hand-made recoil movement: "t:v t:v ..." points (seconds:value) joined by a Catmull-Rom spline.
public final class Curve implements Recoil {
    private final float[] t, v;

    // pairs: time, value, time, value, ...
    public Curve(float... pairs) {
        int n = pairs.length / 2;
        t = new float[n];
        v = new float[n];
        for (int i = 0; i < n; i++) {
            t[i] = pairs[2 * i];
            v[i] = pairs[2 * i + 1];
        }
    }

    private static final java.util.Map<String, Curve> PARSED = new java.util.concurrent.ConcurrentHashMap<>();
    // a text that does not parse, cached as this
    private static final Curve BAD = new Curve(0F, 0F);

    // "t:v t:v ..." (seconds:value) from a config key; null when it is empty, malformed or not this format
    public static Curve of(String src) {
        if (src == null || src.isBlank() || src.indexOf(':') < 0) return null;
        Curve c = PARSED.computeIfAbsent(src, Curve::parse);
        return c == BAD ? null : c;
    }

    private static Curve parse(String src) {
        try {
            String[] w = src.trim().split("\\s+");
            float[] pairs = new float[w.length * 2];
            for (int i = 0; i < w.length; i++) {
                String[] tv = w[i].split(":");
                pairs[2 * i] = Float.parseFloat(tv[0]);
                pairs[2 * i + 1] = Float.parseFloat(tv[1]);
                if (i > 0 && pairs[2 * i] <= pairs[2 * i - 2]) return BAD;
            }
            return w.length >= 2 ? new Curve(pairs) : BAD;
        } catch (RuntimeException e) {
            return BAD;
        }
    }

    @Override
    public float length() {
        return t[t.length - 1];
    }

    @Override
    public float at(float time) {
        if (time <= t[0]) return v[0];
        int n = t.length;
        if (time >= t[n - 1]) return v[n - 1];
        int i = 0;
        while (time > t[i + 1]) i++;
        float u = (time - t[i]) / (t[i + 1] - t[i]);
        float p0 = v[Math.max(0, i - 1)], p1 = v[i], p2 = v[i + 1], p3 = v[Math.min(n - 1, i + 2)];
        float u2 = u * u, u3 = u2 * u;
        return 0.5F * (2F * p1 + (-p0 + p2) * u + (2F * p0 - 5F * p1 + 4F * p2 - p3) * u2 + (-p0 + 3F * p1 - 3F * p2 + p3) * u3);
    }
}
