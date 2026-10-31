package acup.gunfeel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

// What the mod draws at random: a hole's size, turn and look, the gun model's kick, the side of the camera's shake.
// All of it comes from one seed: a new one each start, or the one -Dntmgunfeel.seed=<n>, a trace (Trace.DEFAULT_SEED)
// or a scenario (HoleBenchmark) sets, and then every run draws the same.
// Each shot the server announces gets its own generator from the seed and its place among the shots, so how a burst's
// holes take turns over the frames does not change what each one draws; the recoil has its own two. java.util.Random:
// its numbers are fixed by its specification, the same on every Java the ports run on.
public final class Rng {
    // generators per shot kept at most; an older shot's later marks draw from a new one
    private static final int KEPT = 512;
    private static final long SHOT = 1, UNANNOUNCED = 2, CAMERA = 3, MODEL = 4, LOOSE = 5, SPREAD = 6;

    private static long seed = initial();
    private static int announced, unannounced, spreads;
    private static Random camera, model, loose;
    private static final Map<Integer, Random> SHOTS = new LinkedHashMap<>(64, 0.75F, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Random> eldest) {
            return size() > KEPT;
        }
    };

    private Rng() {
    }

    private static long initial() {
        String s = System.getProperty("ntmgunfeel.seed");
        if (s != null) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                GunFeel.LOGGER.warn("ntmgunfeel.seed is not a number: {}", s);
            }
        }
        // a trace compares runs: the same seed every time
        return Trace.ON ? Trace.DEFAULT_SEED : new java.security.SecureRandom().nextLong();
    }

    public static synchronized long seed() {
        return seed;
    }

    // a new seed: every generator starts over from it
    public static synchronized void seed(long s) {
        seed = s;
        announced = unannounced = spreads = 0;
        SHOTS.clear();
        camera = model = loose = null;
    }

    // the server announced this shot (its bullet's id): its generator, by the order the shots came in
    public static synchronized void announce(int shot) {
        if (shot >= 0 && !SHOTS.containsKey(shot)) SHOTS.put(shot, make(SHOT, ++announced));
    }

    // the generator of a shot's marks; one never announced gets its own by the order it first draws, marks of no shot
    // (-1) share one
    public static synchronized Random shot(int shot) {
        if (shot < 0) {
            if (loose == null) loose = make(LOOSE, 0);
            return loose;
        }
        Random r = SHOTS.get(shot);
        if (r == null) SHOTS.put(shot, r = make(UNANNOUNCED, ++unannounced));
        return r;
    }

    // a seed for the next bullet's spread, by the order the server fires them (only a trace sets it: MixinThrowableHit)
    public static synchronized long spread() {
        return make(SPREAD, ++spreads).nextLong();
    }

    public static synchronized Random camera() {
        if (camera == null) camera = make(CAMERA, 0);
        return camera;
    }

    public static synchronized Random model() {
        if (model == null) model = make(MODEL, 0);
        return model;
    }

    private static Random make(long kind, long n) {
        return new Random(mix(mix(seed ^ kind * 0x9E3779B97F4A7C15L) + n));
    }

    // SplitMix64's finaliser: close seeds give unrelated generators
    static long mix(long z) {
        z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
        z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
        return z ^ z >>> 31;
    }
}
