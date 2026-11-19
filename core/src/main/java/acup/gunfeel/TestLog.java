package acup.gunfeel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

// /gunfeel testlog: what bullets, holes, effects, settings and recoil did while the player just plays, one line per
// event, suitable for comparing client and server traces. The client and the server each switch
// their side on (the server's by TestLogPayload, for an op); in single player both write the same file.
// File: logs/ntm-gunfeel-test-<date>-<time>.tsv. Line: ms since the file opened, side (C/S), shot (the bullet's
// entity id on the server, the same on the client from ShotPayload; - for none), event, then key=value fields.
// With -Dntmgunfeel.trace it is a trace instead (Trace): both sides on for the whole run, game time and exact numbers.
// The events of a level (by its side) and the game's own values (a block, a position) are the version's: on 26.2
// WorldLog writes a level's events here, and TestLogPrint prints the game's values and names the versions.
public final class TestLog {
    public static volatile boolean client, server;

    private static BufferedWriter out;
    private static Path file;
    private static long opened;
    // the bullet the current thread is working on: events deep in PassableClip and the hole code get its id
    private static final ThreadLocal<int[]> SHOT = ThreadLocal.withInitial(() -> new int[] {-1});

    private TestLog() {
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

    public static void shot(int id) {
        SHOT.get()[0] = id;
    }

    public static int shot() {
        return SHOT.get()[0];
    }

    public static void c(int shot, String event, Object... kv) {
        if (client) write("C", shot, event, kv);
    }

    public static void s(int shot, String event, Object... kv) {
        if (server) write("S", shot, event, kv);
    }

    // by the thread: the server's (integrated or dedicated) or the client's, for code both share (bullet rules, server settings)
    public static void here(String event, Object... kv) {
        boolean srv = Thread.currentThread().getName().startsWith("Server");
        if (srv ? server : client) write(srv ? "S" : "C", -1, event, kv);
    }

    /**
     * What only the version knows: the game's own values (a position, a block) as a line shows them. On 26.2:
     * TestLogPrint. The versions in the file's head are the host's ({@link acup.gunfeel.host.HostVersions}).
     */
    public interface Printer {
        /** v as the line shows it (exact: a trace's, every digit), or null when v is none of the game's values. */
        String print(Object v, boolean exact);
    }

    private static final class Found {
        static final Printer PRINTER = CoreServices.load(Printer.class, new Printer() {
            @Override
            public String print(Object v, boolean exact) {
                return null;
            }
        });
    }

    private static void open() {
        try {
            Path dir = GameDirs.get().game().resolve("logs");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            file = !Trace.ON ? dir.resolve("ntm-gunfeel-test-" + stamp + ".tsv")
                    : Trace.file() == null ? dir.resolve("ntm-gunfeel-trace-" + stamp + ".tsv") : GameDirs.get().game().resolve(Trace.file());
            Files.createDirectories(file.toAbsolutePath().getParent());
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            opened = System.currentTimeMillis();
            String mod = acup.gunfeel.host.HostVersions.get().mod();
            if (Trace.ON) {
                int mc = mod.indexOf("+mc");
                out.write(Trace.header(mod, mc < 0 ? "?" : mod.substring(mc + 3)));
            }
            out.write("# ntm-gunfeel testlog, mod " + mod + ", NTM " + acup.gunfeel.host.HostVersions.get().ntm() + "\n");
            out.flush();
        } catch (IOException e) {
            ModLog.LOGGER.warn("Could not open the test log", e);
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

    // WorldLog writes a level's events through here too
    static synchronized void write(String side, int shot, String event, Object[] kv) {
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
            ModLog.LOGGER.warn("Could not write the test log; it stops", e);
            client = server = false;
            close();
        }
    }

    static String fmt(Object v) {
        if (v == null) return "-";
        if (Trace.ON) return exact(v);
        if (v instanceof Float f) return String.format(Locale.ROOT, "%.3f", f);
        if (v instanceof Double d) return String.format(Locale.ROOT, "%.3f", d);
        // the game's values (a position, a block): none of them is a number, a JOML vector or an array
        String game = Found.PRINTER.print(v, false);
        if (game != null) return game;
        return v.toString().replace('\t', ' ').replace('\n', ' ');
    }

    // a trace's numbers: all their digits (Trace)
    private static String exact(Object v) {
        if (v instanceof Float f) return Trace.num(f);
        if (v instanceof Double d) return Trace.num(d);
        String game = Found.PRINTER.print(v, true);
        if (game != null) return game;
        if (v instanceof org.joml.Vector3fc p) return Trace.vec(p.x(), p.y(), p.z());
        if (v instanceof org.joml.Matrix4fc m) return Trace.matrix(m.get(new float[16]));
        if (v instanceof float[] a) return Trace.matrix(a);
        if (v instanceof double[] a) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < a.length; i++) b.append(i > 0 ? "," : "").append(Trace.num(a[i]));
            return b.toString();
        }
        return v.toString().replace('\t', ' ').replace('\n', ' ');
    }
}
