package acup.gunfeel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;

// /gunfeel testlog: what bullets, holes, effects, settings and recoil did while the player just plays, one line per
// event, suitable for comparing client and server traces. The client and the server each switch
// their side on (the server's by TestLogPayload, for an op); in single player both write the same file.
// File: logs/ntm-gunfeel-test-<date>-<time>.tsv. Line: ms since the file opened, side (C/S), shot (the bullet's
// entity id on the server, the same on the client from ShotPayload; - for none), event, then key=value fields.
// With -Dntmgunfeel.trace it is a trace instead (Trace): both sides on for the whole run, game time and exact numbers.
public final class TestLog {
    public static volatile boolean client, server;

    private static BufferedWriter out;
    private static Path file;
    private static long opened;
    // the bullet the current thread is working on: events deep in PassableClip and the hole code get its id
    private static final ThreadLocal<int[]> SHOT = ThreadLocal.withInitial(() -> new int[] {-1});

    private TestLog() {
    }

    // -Dntmgunfeel.testLog: the server's side on from the start (runCheckBullets, to see the log without a client)
    static {
        if (System.getProperty("ntmgunfeel.testLog") != null) set(false, true);
    }

    public static boolean any() {
        return client || server;
    }

    // events written every frame (arms, recoil, sway): only into a trace, never into a log the player asked for
    public static boolean frames() {
        return Trace.ON && client;
    }

    public static boolean on(Level level) {
        return level.isClientSide() ? client : server;
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

    // by the level's side, for the bullet the thread works on
    public static void at(Level level, String event, Object... kv) {
        if (on(level)) write(level.isClientSide() ? "C" : "S", shot(), event, kv);
    }

    // by the thread: the server's (integrated or dedicated) or the client's, for code both share (bullet rules, server settings)
    public static void here(String event, Object... kv) {
        boolean srv = Thread.currentThread().getName().startsWith("Server");
        if (srv ? server : client) write(srv ? "S" : "C", -1, event, kv);
    }

    // by the level's side, for a given bullet (or -1)
    public static void at(Level level, int shot, String event, Object... kv) {
        if (on(level)) write(level.isClientSide() ? "C" : "S", shot, event, kv);
    }

    // the block's bullet material as piercing and effects have it (a multiblock cell: its machine's)
    public static String material(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return BulletRules.material(Cells.ruleState(level, pos, state)).material().name().toLowerCase(Locale.ROOT);
    }

    private static void open() {
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("logs");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            file = !Trace.ON ? dir.resolve("ntm-gunfeel-test-" + stamp + ".tsv")
                    : Trace.file() == null ? dir.resolve("ntm-gunfeel-trace-" + stamp + ".tsv") : FMLPaths.GAMEDIR.get().resolve(Trace.file());
            Files.createDirectories(file.toAbsolutePath().getParent());
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            opened = System.currentTimeMillis();
            String mod = acup.gunfeel.compat.Compat.modVersion();
            if (Trace.ON) {
                int mc = mod.indexOf("+mc");
                out.write(Trace.header(mod, mc < 0 ? "?" : mod.substring(mc + 3)));
            }
            out.write("# ntm-gunfeel testlog, mod " + mod + ", NTM " + acup.gunfeel.compat.Compat.hbmVersion() + "\n");
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
        if (out == null) return;
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
        if (v instanceof Float f) return String.format(Locale.ROOT, "%.3f", f);
        if (v instanceof Double d) return String.format(Locale.ROOT, "%.3f", d);
        if (v instanceof Vec3 p) return String.format(Locale.ROOT, "%.3f,%.3f,%.3f", p.x, p.y, p.z);
        if (v instanceof BlockPos p) return p.getX() + "," + p.getY() + "," + p.getZ();
        if (v instanceof BlockState s) return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
        return v.toString().replace('\t', ' ').replace('\n', ' ');
    }

    // a trace's numbers: all their digits (Trace)
    private static String exact(Object v) {
        if (v instanceof Float f) return Trace.num(f);
        if (v instanceof Double d) return Trace.num(d);
        if (v instanceof Vec3 p) return Trace.vec(p.x, p.y, p.z);
        if (v instanceof org.joml.Vector3fc p) return Trace.vec(p.x(), p.y(), p.z());
        if (v instanceof org.joml.Matrix4fc m) return Trace.matrix(m.get(new float[16]));
        if (v instanceof float[] a) return Trace.matrix(a);
        if (v instanceof double[] a) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < a.length; i++) b.append(i > 0 ? "," : "").append(Trace.num(a[i]));
            return b.toString();
        }
        if (v instanceof BlockPos p) return p.getX() + "," + p.getY() + "," + p.getZ();
        if (v instanceof BlockState s) return net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(s);
        return v.toString().replace('\t', ' ').replace('\n', ' ');
    }
}
