package acup.gunfeel;

import java.util.Locale;

// One recoil movement as a push and a damped spring: after `delay` the value rises to `amount` in `attack` seconds
// (a quarter sine), then is let go like a spring and swings back and settles: its envelope falls to 2% in `settle`
// seconds after the peak. `bounce` is how far past zero the first swing back goes, as a share of the peak
// (0 = no swing, it just sinks back). `freq` > 0 sets the swing's rate in Hz instead (a shake), bounce is then unused.
// Written "amount,attack,settle,bounce,delay,freq"; missing numbers at the end are 0 (attack and settle: their least).
public record Spring(float amount, float attack, float settle, float bounce, float delay, float freq) implements Recoil {
    static final float MIN_ATTACK = 0.005F, MIN_SETTLE = 0.02F, MAX_BOUNCE = 0.95F;

    public static final Spring ZERO = new Spring(0F, MIN_ATTACK, MIN_SETTLE, 0F, 0F, 0F);

    public Spring {
        attack = Math.max(MIN_ATTACK, attack);
        settle = Math.max(MIN_SETTLE, settle);
        bounce = Math.clamp(bounce, 0F, MAX_BOUNCE);
        delay = Math.max(0F, delay);
        freq = Math.max(0F, freq);
    }

    // null = not this format
    public static Spring parse(String src) {
        if (src == null || src.isBlank() || src.indexOf(':') >= 0) return null;
        String[] w = src.trim().split("\\s*,\\s*");
        if (w.length > 6) return null;
        float[] f = new float[6];
        try {
            for (int i = 0; i < w.length; i++) f[i] = Float.parseFloat(w[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        for (float x : f) if (!Float.isFinite(x)) return null;
        return new Spring(f[0], f[1], f[2], f[3], f[4], f[5]);
    }

    public float get(int i) {
        return switch (i) {
            case 0 -> amount;
            case 1 -> attack;
            case 2 -> settle;
            case 3 -> bounce;
            case 4 -> delay;
            default -> freq;
        };
    }

    public Spring with(int i, float v) {
        return new Spring(i == 0 ? v : amount, i == 1 ? v : attack, i == 2 ? v : settle, i == 3 ? v : bounce,
                i == 4 ? v : delay, i == 5 ? v : freq);
    }

    // the swing's angular rate, radians a second (0 = none)
    private float omega() {
        if (freq > 0F) return (float) (2 * Math.PI * freq);
        if (bounce <= 1e-3F) return 0F;
        // the first swing back reaches -bounce: exp(-4 (pi / w) / settle) = bounce
        return (float) (4 * Math.PI / (settle * -Math.log(bounce)));
    }

    @Override
    public float length() {
        return delay + attack + settle * 1.25F;
    }

    @Override
    public float at(float t) {
        float u = t - delay;
        if (u <= 0F || amount == 0F) return 0F;
        float len = length() - delay;
        if (u >= len) return 0F;
        float v;
        if (u < attack) {
            v = (float) Math.sin(Math.PI / 2 * u / attack);
        } else {
            // a spring let go at the peak: it leaves it smoothly (no kink after the rise) and its swings back reach
            // exactly -bounce, +bounce^2, ...
            double a = u - attack, k = 4.0 / settle, w = omega();
            v = (float) (Math.exp(-k * a) * (w > 0 ? Math.cos(w * a) + k / w * Math.sin(w * a) : 1 + k * a));
        }
        // the last fifth of the settle fades to exactly 0, so a camera kick ends where it started
        float fadeFrom = len - settle * 0.25F;
        if (u > fadeFrom) {
            float s = 1F - (u - fadeFrom) / (len - fadeFrom);
            v *= s * s * (3F - 2F * s);
        }
        return v * amount;
    }

    @Override
    public String toString() {
        return n(amount) + "," + n(attack) + "," + n(settle) + "," + n(bounce) + "," + n(delay) + "," + n(freq);
    }

    static String n(float v) {
        String s = String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
