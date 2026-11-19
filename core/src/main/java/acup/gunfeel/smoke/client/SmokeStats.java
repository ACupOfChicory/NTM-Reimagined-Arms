package acup.gunfeel.smoke.client;

import java.util.Locale;

import acup.gunfeel.ModLog;

/**
 * Client: where the smoke's time goes, to the debug log every 5 s while there are clouds ("Smoke cost"): the client tick
 * (simulation and catching up), the atlas built and sent, and per frame the depth copy, the raymarch and the composite
 * (time on the processor to hand them over), and the graphics card's own time on the march and the composite from its
 * timestamps.
 */
public final class SmokeStats {
    public static final int TICK = 0, ATLAS = 1, COPY = 2, MARCH = 3, COMPOSITE = 4, GPU_MARCH = 5, SLICES = 6;
    private static final String[] NAMES = {"tick", "atlas", "depth copy", "raymarch", "composite", "card: march and composite", "slices"};
    private static final long[] NANOS = new long[NAMES.length];
    private static final int[] CALLS = new int[NAMES.length];
    private static long since, frames, ticks;
    private static int clouds, drawn;
    private static float inside;
    // the last 5 s, for the stand
    public static volatile String last = "";

    /**
     * Whether the card's own time is polled and the stats built: only for the stand and with debug logging on. Without
     * either nobody reads them, and polling the timestamps of every frame is a cost for nothing.
     */
    public static final boolean MEASURING = !System.getProperty("ntmgunfeel.smokeStand", "").isBlank() || ModLog.LOGGER.isDebugEnabled();

    private SmokeStats() {
    }

    // never reset: the card's time summed, for the stand to take differences of
    public static long cardNanos, cardFrames;
    /**
     * The card's time per pass in the frames that drew the volume, never reset (the stands take differences): the
     * copies before glass and water (the depth, the frame, its alpha cleared), the copies after them, the march in
     * front of glass, the march, the composite, the alpha put back.
     */
    public static final String[] CARD_PARTS = {"copy", "copy2", "front", "march", "lay", "alpha"};
    public static final long[] cardPart = new long[CARD_PARTS.length];
    public static long cardPartFrames;

    /** The card's passes of the frames since {@code at} (a copy of {@link #cardPart} with the frames last), ms a frame. */
    public static String cardParts(long[] at) {
        long frames = cardPartFrames - at[CARD_PARTS.length];
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < CARD_PARTS.length; i++) {
            if (i > 0) b.append(' ');
            b.append(CARD_PARTS[i]).append(' ').append(String.format(Locale.ROOT, "%.3f", frames > 0 ? (cardPart[i] - at[i]) / 1e6 / frames : 0));
        }
        return b.toString();
    }

    /** {@link #cardPart} and the frames, to give {@link #cardParts} later. */
    public static long[] cardMark() {
        long[] at = java.util.Arrays.copyOf(cardPart, CARD_PARTS.length + 1);
        at[CARD_PARTS.length] = cardPartFrames;
        return at;
    }

    public static void add(int part, long nanos) {
        NANOS[part] += nanos;
        CALLS[part]++;
        if (part == GPU_MARCH) {
            cardNanos += nanos;
            cardFrames++;
        }
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
                // a tick's parts over all ticks, a frame's over the frames that drew clouds
                double per = i == TICK || i == ATLAS ? NANOS[i] / 1e6 / Math.max(1, ticks) : NANOS[i] / 1e6 / Math.max(1, CALLS[i]);
                b.append(String.format(Locale.ROOT, "%s %.3f ms%s (%d), ", NAMES[i], per, i == TICK || i == ATLAS ? " a tick" : "", CALLS[i]));
            }
            last = String.format(Locale.ROOT, "%sclouds %d, drawn %d, at the camera %.2f, %d frames (%d fps)", b, clouds, drawn, inside, frames,
                    Math.round(frames / ((now - since) / 1e9)));
            ModLog.LOGGER.debug("Smoke cost: {}", last);
        }
        java.util.Arrays.fill(NANOS, 0L);
        java.util.Arrays.fill(CALLS, 0);
        frames = ticks = 0;
        clouds = drawn = 0;
        inside = 0F;
        since = now;
    }
}
