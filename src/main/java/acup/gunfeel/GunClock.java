package acup.gunfeel;

import java.util.function.LongSupplier;

// The mod's clock for what moves with time on screen and for its own waits: recoil, tracers' muzzle and holes' life,
// menus, the editor, short timeouts. Real time (System.nanoTime) unless something sets another source: a trace runs it
// from the game's ticks (Trace), a test by hand, so two runs see the same times.
// System.nanoTime stays where the mod measures what its work costs (Perf, "took N ms" in the logs) and where it paces
// re-reading its files; neither changes what is drawn or decided.
public final class GunClock {
    private static final LongSupplier REAL = System::nanoTime;
    private static volatile LongSupplier source = REAL;

    private GunClock() {
    }

    public static long nanos() {
        return source.getAsLong();
    }

    // the same clock in milliseconds, for waits counted in ms; not a date
    public static long millis() {
        return Math.floorDiv(nanos(), 1_000_000L);
    }

    // another source of nanoseconds; null: real time again
    public static void use(LongSupplier nanos) {
        source = nanos == null ? REAL : nanos;
    }

    public static boolean real() {
        return source == REAL;
    }
}
