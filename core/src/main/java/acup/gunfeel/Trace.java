package acup.gunfeel;

import java.util.Locale;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

// A trace: what the mod did in a run, one line per event, exact enough to compare two runs number by number with
// tools/trace_diff.py: the same scenario twice on 26.2, or on 26.2 and on a port. -Dntmgunfeel.trace turns the test
// log (TestLog) into one: both sides on from the start, time in game ticks and the frame's partial tick instead of
// milliseconds, numbers with all their digits, events per frame (arms, recoil, sway) added, the mod's clock (GunClock)
// on game time and the seed (Rng) fixed. Off by default; the game is then as without it.
//   -Dntmgunfeel.trace=true | <file>   on; the file (relative: to the game folder), by default
//                                      logs/ntm-gunfeel-trace-<date>-<time>.tsv
//   -Dntmgunfeel.traceHex=true         numbers as their bits (float %08x, double %016x), for comparing to the bit
//   -Dntmgunfeel.traceScenario=<name>  the scenario's name for the header
//   -Dntmgunfeel.seed=<n>              the seed (Rng); a trace without one takes DEFAULT_SEED
// Lines: "# trace 1  scenario=..  build=..  mc=..  seed=..  clock=..", then per event
//   tick  partial  wall  side  shot  kind  key=value ...
// tick, partial: the side's game time (the server's tick count; the client's level time and the frame's partial
// tick). wall: ms since the file opened, for a person reading it; the comparison leaves it out. Numbers: float %.9g,
// double %.17g; vectors comma-separated, matrices 16 numbers column by column, blocks "id[p=v,...]".
public final class Trace {
    public static final int FORMAT = 1;
    public static final long DEFAULT_SEED = 71294L;
    private static final String VALUE = System.getProperty("ntmgunfeel.trace");
    public static final boolean ON = VALUE != null && !VALUE.isBlank() && !VALUE.equalsIgnoreCase("false");
    public static final boolean HEX = Boolean.getBoolean("ntmgunfeel.traceHex");
    public static final String SCENARIO = System.getProperty("ntmgunfeel.traceScenario", "game").trim().replaceAll("\\s+", "_");

    // a scenario (TraceScenario) writes only what it plays: the client's lines while it records, nothing while it
    // sets up (giving a gun, waiting for the server)
    public static volatile boolean scenario, recording;
    private static volatile LongSupplier serverTick = () -> -1L, clientTick = () -> -1L;
    private static volatile DoubleSupplier clientPartial = () -> 0.0;

    private Trace() {
    }

    public static boolean keep(boolean server) {
        return !scenario || recording && !server;
    }

    // the file asked for (relative to the game folder), or null: the default name
    public static String file() {
        return ON && !VALUE.trim().equalsIgnoreCase("true") ? VALUE.trim() : null;
    }

    // where each side's time comes from (TraceHooks)
    public static void server(LongSupplier tick) {
        serverTick = tick;
    }

    public static void client(LongSupplier tick, DoubleSupplier partial) {
        clientTick = tick;
        clientPartial = partial;
    }

    public static String header(String build, String mc) {
        return "# trace " + FORMAT + "  scenario=" + SCENARIO + "  build=" + build + "  mc=" + mc + "  seed=" + Rng.seed()
                + "  clock=" + (GunClock.real() ? "real" : "game") + "  numbers=" + (HEX ? "hex" : "dec") + "\n"
                + "# tick\tpartial\twall\tside\tshot\tkind\tkey=value ...\n";
    }

    // "tick<TAB>partial" of a side, as the line starts
    public static String time(boolean server) {
        if (server) return serverTick.getAsLong() + "\t0";
        return clientTick.getAsLong() + "\t" + num((float) clientPartial.getAsDouble());
    }

    public static String num(float f) {
        if (HEX) return String.format(Locale.ROOT, "%08x", Float.floatToRawIntBits(f));
        return String.format(Locale.ROOT, "%.9g", f);
    }

    public static String num(double d) {
        if (HEX) return String.format(Locale.ROOT, "%016x", Double.doubleToRawLongBits(d));
        return String.format(Locale.ROOT, "%.17g", d);
    }

    public static String vec(double x, double y, double z) {
        return num(x) + "," + num(y) + "," + num(z);
    }

    public static String vec(float x, float y, float z) {
        return num(x) + "," + num(y) + "," + num(z);
    }

    // a matrix's 16 numbers column by column (JOML's get(float[]))
    public static String matrix(float[] m) {
        StringBuilder b = new StringBuilder(m.length * 12);
        for (int i = 0; i < m.length; i++) {
            if (i > 0) b.append(',');
            b.append(num(m[i]));
        }
        return b.toString();
    }
}
