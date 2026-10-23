package acup.gunfeel;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import net.neoforged.fml.loading.FMLPaths;

// Server settings, config/ntm-gunfeel-server.properties: how long before the end of a reload / an inspect the held aim
// comes back, per gun, in seconds ("hbm\:gun_double_barrel.reaimInspect = 0.6", set with /gunaim), and plain numbers
// (bulletSpeed, spread*, cameraScaleMin). On a client connected to another game's server the server's file counts
// (ServerSettings). Synchronized: in single player the client and the server thread share it.
public final class ReaimTimes {
    public static final float DEFAULT = 0.3F;
    public static final String FILE = "ntm-gunfeel-server.properties";
    private static final Map<String, String> VALUES = new TreeMap<>();
    // the times shipped in the mod jar (ntmgunfeel-server.properties); the server's file wins over them
    private static final Map<String, String> BUILTIN = builtin();

    private static Map<String, String> builtin() {
        Map<String, String> out = new TreeMap<>();
        try (var in = ReaimTimes.class.getResourceAsStream("/ntmgunfeel-server.properties")) {
            if (in == null) return out;
            Properties p = new Properties();
            p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            for (String key : p.stringPropertyNames()) out.put(key, p.getProperty(key).trim());
        } catch (java.io.IOException | RuntimeException e) {
            // no built-in times
        }
        return out;
    }
    private static long stamp = Long.MIN_VALUE;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ntm-gunfeel");

    private ReaimTimes() {
    }

    // kind: "reload" or "inspect"
    public static synchronized float seconds(String gun, String kind) {
        poll();
        String key = gun + ".reaim" + cap(kind);
        String v = values0().get(key);
        if (v == null) v = BUILTIN.get(key);
        float out = DEFAULT;
        if (v != null) {
            try {
                out = Math.max(0F, Float.parseFloat(v.trim()));
            } catch (NumberFormatException e) {
                // the default
            }
        }
        // the test log: each time a gun's time changes (after /gunaim), on the side that reads it
        if (TestLog.any() && !Float.valueOf(out).equals(LOGGED.put(key, out))) TestLog.here("reaim", "gun", gun, "kind", kind, "seconds", out);
        return out;
    }

    private static final Map<String, Float> LOGGED = new java.util.HashMap<>();

    private static long lastPoll;
    private static final Map<String, Float> NUMBERS = new java.util.HashMap<>();
    private static long numbersStamp = Long.MIN_VALUE;
    private static Object numbersOf;

    // the file's values, or the server's while connected to one
    private static Map<String, String> values0() {
        ServerSettings.Snapshot remote = ServerSettings.remote();
        return remote != null ? remote.server() : VALUES;
    }

    // the file's values as they are now, for sending to the players
    public static synchronized Map<String, String> values() {
        poll();
        return new TreeMap<>(VALUES);
    }

    // a plain server setting (bulletSpeed = 1.6); read often, so the file is checked at most once a second
    public static synchronized float number(String key, float def) {
        long now = System.currentTimeMillis();
        if (now - lastPoll > 1000L) {
            lastPoll = now;
            poll();
        }
        Map<String, String> values = values0();
        if (numbersStamp != stamp || numbersOf != values) {
            numbersStamp = stamp;
            numbersOf = values;
            NUMBERS.clear();
        }
        Float cached = NUMBERS.get(key);
        if (cached != null) return cached;
        String v = values.get(key);
        if (v == null) v = BUILTIN.get(key);
        float out = def;
        if (v != null) {
            String t = v.trim();
            if (t.equalsIgnoreCase("true")) out = 1F;
            else if (t.equalsIgnoreCase("false")) out = 0F;
            else try {
                out = Float.parseFloat(t);
            } catch (NumberFormatException e) {
                // keep the default
            }
        }
        NUMBERS.put(key, out);
        return out;
    }

    // a yes/no server setting (bulletBreakConcrete = true)
    public static boolean flag(String key, boolean def) {
        return number(key, def ? 1F : 0F) != 0F;
    }

    public static int ticks(String gun, String kind) {
        return Math.round(seconds(gun, kind) * 20F);
    }

    public static synchronized void set(String gun, String kind, float seconds) {
        poll();
        VALUES.put(gun + ".reaim" + cap(kind), Float.toString(seconds));
        save();
    }

    private static String cap(String kind) {
        return Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE);
    }

    private static void poll() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            long modified = Files.getLastModifiedTime(f).toMillis();
            if (modified == stamp) return;
            stamp = modified;
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(f)) {
                p.load(r);
            }
            VALUES.clear();
            for (String key : p.stringPropertyNames()) VALUES.put(key, p.getProperty(key));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read {}", FILE, e);
        }
    }

    private static void save() {
        try {
            Path f = file();
            try (Writer w = Files.newBufferedWriter(f)) {
                w.write("# NTM Gun Feel, server side. With the aim key held, the aim comes back this many seconds before\n");
                w.write("# a reload / an inspect ends: <gun id>.reaimReload, <gun id>.reaimInspect (default " + DEFAULT + "). /gunaim sets them.\n");
                for (Map.Entry<String, String> e : VALUES.entrySet()) w.write(e.getKey().replace(":", "\\:") + " = " + e.getValue() + "\n");
            }
            stamp = Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            LOGGER.warn("Could not write {}", FILE, e);
        }
    }
}
