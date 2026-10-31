package acup.gunfeel;

// A trace scenario's frames (TraceScenario): while on, the game's frame timer (MixinDeltaTimer) is told that
// STEP_MS passed each frame, whatever the real time. Frames, client ticks and the partial tick then come the same in
// every run: five frames a tick at 20 TPS. Off by default; only TraceScenario turns it on.
public final class FixedStep {
    public static final long STEP_MS = 10L;
    private static volatile boolean on;
    private static boolean fresh;
    private static long ms, frames;

    private FixedStep() {
    }

    public static boolean on() {
        return on;
    }

    // from the timer's next frame on (it gives the time it had, so nothing jumps)
    public static synchronized void start() {
        fresh = true;
        on = true;
    }

    public static void stop() {
        on = false;
    }

    // the timer's first frame since start: it starts its partial tick over (MixinDeltaTimer)
    public static synchronized boolean fresh() {
        return fresh;
    }

    // a new frame: the time the timer is given; last = the time it had
    public static synchronized long advance(long last) {
        if (fresh) {
            fresh = false;
            ms = last;
            frames = 0;
        }
        frames++;
        return ms += STEP_MS;
    }

    // a frame on which a tick begins (the partial tick is 0): a scenario's part starts on one
    public static synchronized boolean aligned() {
        return !fresh && frames % (50L / STEP_MS) == 0;
    }

    public static synchronized long now() {
        return ms;
    }
}
