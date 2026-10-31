package acup.gunfeel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// The test log of 26.2 (TestLog there), as far as the port has it: the same file names, header and lines, so
// tools/trace_diff.py reads both. Line: ms since the file opened, side (C/S), shot (- for none), event, then
// key=value fields, tab-separated. On with -Dntmgunfeel.testlog=true (26.2 turns it on with a command).
// With -Dntmgunfeel.trace it is a trace instead (Trace): both sides on for the whole run, game time and exact numbers.
// What 26.2 writes of levels, blocks and bullets (at, material, shot ids) comes with those parts of the mod.
public final class TestLog {
    public static volatile boolean client, server;

    private static BufferedWriter out;
    private static Path file;
    private static long opened;

    private TestLog() {
    }

    static {
        if (Boolean.getBoolean("ntmgunfeel.testlog")) {
            set(true, true);
            set(false, true);
        }
    }

    public static boolean any() {
        return client || server;
    }

    // events written every frame (arms, recoil, sway): only into a trace, never into a log the player asked for
    public static boolean frames() {
        return Trace.ON && client;
    }

    // switch one side; the file opens with the first side on and closes with the last one off. The file, or null.
    public static synchronized Path set(boolean clientSide, boolean on) {
        // a trace keeps both sides on to the end of the run
        if (Trace.ON && !on) return file;
        if (clientSide) client = on;
        else server = on;
        if (any() && out == null) open();
        if (!any() && out != null) close();
        return file;
    }

    public static synchronized Path file() {
        return file;
    }

    public static void c(int shot, String event, Object... kv) {
        if (client) write("C", shot, event, kv);
    }

    public static void s(int shot, String event, Object... kv) {
        if (server) write("S", shot, event, kv);
    }

    // by the thread: the server's (integrated or dedicated) or the client's
    public static void here(String event, Object... kv) {
        boolean srv = Thread.currentThread().getName().startsWith("Server");
        if (srv ? server : client) write(srv ? "S" : "C", -1, event, kv);
    }

    private static void open() {
        try {
            Path game = PortPaths.game();
            Path dir = game.resolve("logs");
            Files.createDirectories(dir);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            file = !Trace.ON ? dir.resolve("ntm-gunfeel-test-" + stamp + ".tsv")
                    : Trace.file() == null ? dir.resolve("ntm-gunfeel-trace-" + stamp + ".tsv") : game.resolve(Trace.file());
            Files.createDirectories(file.toAbsolutePath().getParent());
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            opened = System.currentTimeMillis();
            String mod = BuildInfo.VERSION;
            if (Trace.ON) {
                int mc = mod.indexOf("+mc");
                out.write(Trace.header(mod, mc < 0 ? "?" : mod.substring(mc + 3)));
            }
            out.write("# ntm-gunfeel testlog, mod " + mod + ", NTM " + PortPaths.hbmVersion() + "\n");
            out.flush();
        } catch (IOException e) {
            GunFeel.LOGGER.warn("Could not open the test log", e);
            out = null;
        }
    }

    private static void close() {
        try {
            out.close();
        } catch (IOException e) {
            // nothing left to do with it
        }
        out = null;
    }

    private static synchronized void write(String side, int shot, String event, Object[] kv) {
        if (out == null || Trace.ON && !Trace.keep(side.equals("S"))) return;
        StringBuilder b = new StringBuilder(96);
        if (Trace.ON) b.append(Trace.time(side.equals("S"))).append('\t');
        b.append(System.currentTimeMillis() - opened).append('\t').append(side).append('\t').append(shot < 0 ? "-" : Integer.toString(shot))
                .append('\t').append(event);
        for (int i = 0; i + 1 < kv.length; i += 2) b.append('\t').append(kv[i]).append('=').append(fmt(kv[i + 1]));
        b.append('\n');
        try {
            out.write(b.toString());
            out.flush();
        } catch (IOException e) {
            GunFeel.LOGGER.warn("Could not write the test log; it stops", e);
            client = server = false;
            close();
        }
    }

    static String fmt(Object v) {
        if (v == null) return "-";
        if (Trace.ON) return exact(v);
        if (v instanceof Float) return String.format(Locale.ROOT, "%.3f", (Float) v);
        if (v instanceof Double) return String.format(Locale.ROOT, "%.3f", (Double) v);
        return v.toString().replace('\t', ' ').replace('\n', ' ');
    }

    // a trace's numbers: all their digits (Trace). A float[] is 26.2's Vector3f or Matrix4f: its numbers in a row.
    private static String exact(Object v) {
        if (v instanceof Float) return Trace.num((Float) v);
        if (v instanceof Double) return Trace.num((Double) v);
        if (v instanceof float[]) return Trace.matrix((float[]) v);
        if (v instanceof double[]) {
            double[] a = (double[]) v;
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < a.length; i++) b.append(i > 0 ? "," : "").append(Trace.num(a[i]));
            return b.toString();
        }
        return v.toString().replace('\t', ' ').replace('\n', ' ');
    }
}
