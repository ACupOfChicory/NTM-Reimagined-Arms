package acup.gunfeel.smoke;

/**
 * The wind over a level, on the server: one for the whole level, from its seed and its game time, so it needs no saving
 * and every cloud born at one time gets the same. It swings slowly round a way of its own (two slow waves, of 30 and 17
 * minutes: a third of a turn a minute at most, a quarter to half a turn in 10 to 20 minutes) and blows by the weather,
 * half a block a second in the clear, a block in the rain and two in a thunderstorm, with the weather's own fading
 * between them. No wind where the level has no sky or has a ceiling (the Nether, the End): the version's adapter
 * asks the level for that, its seed, time and weather (26.2: {@code SmokeClouds.wind}).
 *
 * <p>A cloud takes the wind of its birth for its life ({@link SmokeClouds#spawn}, sent in the cloud's {@code Spawn}):
 * the client steps the same drift without a wind of its own.
 */
public final class SmokeWind {
    /** Blocks a second: clear, rain, thunder. */
    public static final float CLEAR = 0.5F, RAIN = 1F, STORM = 2F;
    /** Ticks of the two waves the way swings by, and how far each swings it, radians. */
    private static final double SLOW = 36_000, FAST = 20_400, SLOW_SWING = 1.6, FAST_SWING = 0.6;

    private SmokeWind() {
    }

    /** The wind for a seed at a game time with this much rain and thunder (0 to 1, thunder counted within the rain). */
    public static float[] at(long seed, long time, float rain, float thunder) {
        double angle = way(seed, time);
        float speed = (CLEAR + (RAIN - CLEAR) * rain + (STORM - RAIN) * thunder) / 20F;
        return new float[] {(float) Math.cos(angle) * speed, (float) Math.sin(angle) * speed};
    }

    /** The way the wind blows to, radians from +x towards +z. */
    public static double way(long seed, long time) {
        long h = seed * 0x9E3779B97F4A7C15L;
        h = (h ^ h >>> 31) * 0xBF58476D1CE4E5B9L;
        double base = (h >>> 11) * 0x1.0p-53 * 2.0 * Math.PI;
        double p1 = (h & 0xFFFF) / 65536.0 * 2.0 * Math.PI, p2 = (h >>> 16 & 0xFFFF) / 65536.0 * 2.0 * Math.PI;
        return base + SLOW_SWING * Math.sin(2.0 * Math.PI * time / SLOW + p1) + FAST_SWING * Math.sin(2.0 * Math.PI * time / FAST + p2);
    }
}
