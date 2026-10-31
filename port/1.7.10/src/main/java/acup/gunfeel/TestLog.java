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

// The test log of 26.2 (logs/ntm-gunfeel-test-*.tsv), as far as the port has it: one line per event, the same columns
// (time, side, shot, event, key=value...). On with -Dntmgunfeel.testlog=true; 26.2 turns it on with a command.
public final class TestLog {
    public static volatile boolean client = Boolean.getBoolean("ntmgunfeel.testlog"), server = client;
    private static BufferedWriter out;
    private static boolean failed;

    private TestLog() {
    }

    public static boolean any() {
        return client || server;
    }

    public static void c(int shot, String event, Object... kv) {
        if (client) write("client", shot, event, kv);
    }

    public static void s(int shot, String event, Object... kv) {
        if (server) write("server", shot, event, kv);
    }

    public static void here(String event, Object... kv) {
        if (any()) write("?", -1, event, kv);
    }

    static String fmt(Object v) {
        if (v instanceof Float || v instanceof Double) return String.format(Locale.ROOT, "%.3f", ((Number) v).doubleValue());
        return String.valueOf(v);
    }

    private static synchronized void write(String side, int shot, String event, Object... kv) {
        if (failed) return;
        try {
            if (out == null) {
                Path dir = Paths.get("logs");
                Files.createDirectories(dir);
                String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
                out = Files.newBufferedWriter(dir.resolve("ntm-gunfeel-test-" + stamp + ".tsv"), StandardCharsets.UTF_8);
                out.write("ms\tside\tshot\tevent\tdata\n");
            }
            StringBuilder b = new StringBuilder();
            b.append(System.currentTimeMillis()).append('\t').append(side).append('\t').append(shot).append('\t').append(event).append('\t');
            for (int i = 0; i + 1 < kv.length; i += 2) b.append(i > 0 ? " " : "").append(kv[i]).append('=').append(fmt(kv[i + 1]));
            out.write(b.append('\n').toString());
            out.flush();
        } catch (IOException e) {
            failed = true;
            GunFeel.LOGGER.warn("Could not write the test log; it stops", e);
        }
    }
}
