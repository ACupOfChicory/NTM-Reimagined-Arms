package acup.gunfeel.smoke.client;

import java.util.Locale;

import acup.gunfeel.GunFeel;

/**
 * Client: where the smoke's time goes, to the log every 5 s while there are clouds ("Smoke cost"): the client tick
 * (simulation and catching up), the atlas built and sent, and per frame the depth copy, the raymarch and the composite
 * (time on the processor to hand them over; the graphics card's own time comes from its timestamps when it has them).
 */
public final class SmokeStats {
    public static final int TICK = 0, ATLAS = 1, COPY = 2, MARCH = 3, COMPOSITE = 4, GPU_MARCH = 5, GPU_ALL = 6;
    private static final String[] NAMES = {"tick", "atlas", "depth copy", "raymarch", "composite", "card raymarch", "card all"};
    private static final long[] NANOS = new long[NAMES.length];
    private static final int[] CALLS = new int[NAMES.length];
    private static long since, frames, ticks;
    private static int clouds, drawn;
    private static float inside;
    // the last 5 s, for the stand
    public static volatile String last = "";

    private SmokeStats() {
    }

    public static void add(int part, long nanos) {
        NANOS[part] += nanos;
        CALLS[part]++;
    }

    static void tick(long nanos) {
        add(TICK, nanos);
        ticks++;
    }

    /** Once a frame with the clouds known and drawn, and the smoke density at the camera. */
    public static void frame(int known, int shown, float atCamera) {
        long now = System.nanoTime();
        if (since == 0L) since = now;
        frames++;
        clouds = Math.max(clouds, known);
        drawn = Math.max(drawn, shown);
        inside = Math.max(inside, atCamera);
        if (now - since < 5_000_000_000L) return;
        if (clouds > 0) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < NAMES.length; i++) {
                double per = i == TICK || i == ATLAS ? NANOS[i] / 1e6 / Math.max(1, ticks) : NANOS[i] / 1e6 / Math.max(1, frames);
                b.append(String.format(Locale.ROOT, "%s %.3f ms%s (%d), ", NAMES[i], per, i == TICK || i == ATLAS ? " a tick" : "", CALLS[i]));
            }
            last = String.format(Locale.ROOT, "%sclouds %d, drawn %d, at the camera %.2f, %d frames (%d fps)", b, clouds, drawn, inside, frames,
                    Math.round(frames / ((now - since) / 1e9)));
            GunFeel.LOGGER.info("Smoke cost: {}", last);
        }
        java.util.Arrays.fill(NANOS, 0L);
        java.util.Arrays.fill(CALLS, 0);
        frames = ticks = 0;
        clouds = drawn = 0;
        inside = 0F;
        since = now;
    }
}
