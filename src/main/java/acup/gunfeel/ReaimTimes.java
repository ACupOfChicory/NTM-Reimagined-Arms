package acup.gunfeel;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import net.neoforged.fml.loading.FMLPaths;

// Server settings, config/ntm-gunfeel-server.properties: plain numbers and switches (bulletSpeed, spread*,
// cameraScaleMin, balance). The balance's numbers are fixed in the mod (BalanceKeys), the file only picks arms or ntm. On a client connected to another game's server the server's file counts (ServerSettings).
// Synchronized: in single player the client and the server thread share it. Also how long before the end of a reload /
// an inspect the held aim comes back: fixed in the mod, not a setting (the file's old <gun>.reaim* keys are not read).
public final class ReaimTimes {
    public static final float DEFAULT = 0.3F;
    // the guns whose inspect hands the aim back earlier, in seconds before it ends
    private static final Map<String, Float> INSPECT = Map.of("hbm:gun_double_barrel", 0.6F, "hbm:gun_double_barrel_sacred_dragon", 0.6F);
    public static final String FILE = "ntm-gunfeel-server.properties";
    private static final Map<String, String> VALUES = new TreeMap<>();
    // the defaults shipped in the mod jar (ntmgunfeel-server.properties); the server's file wins over them
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
    // bumped at each read of the file: two writes in one millisecond still count as a change
    private static long generation;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ntm-gunfeel");

    private ReaimTimes() {
    }

    // kind: "reload" or "inspect"
    public static synchronized float seconds(String gun, String kind) {
        float out = kind.equals("inspect") ? INSPECT.getOrDefault(gun, DEFAULT) : DEFAULT;
        // the test log: the first time a gun's time is read, on the side that reads it
        if (TestLog.any() && !Float.valueOf(out).equals(LOGGED.put(gun + "." + kind, out))) TestLog.here("reaim", "gun", gun, "kind", kind, "seconds", out);
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
        String v = raw(values, key);
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

    // a word server setting (aiSkill = veteran); the file is checked at most once a second
    public static synchronized String text(String key, String def) {
        long now = System.currentTimeMillis();
        if (now - lastPoll > 1000L) {
            lastPoll = now;
            poll();
        }
        String v = raw(values0(), key);
        return v == null ? def : v;
    }

    /**
     * a key's value: the file's (or the server's), else the jar's; a key of the balance by the switch balance = arms | ntm
     * (BalanceKeys), from the file only on the stands (-Dntmgunfeel.balanceKeys=true)
     */
    private static String raw(Map<String, String> values, String key) {
        if (BalanceKeys.is(key)) {
            if (BalanceKeys.dev()) {
                String v = values.get(key);
                if (v != null) return v;
            }
            String sw = values.get(BalanceKeys.KEY);
            if (sw == null) sw = BUILTIN.get(BalanceKeys.KEY);
            return BalanceKeys.value(key, BalanceKeys.ntm(sw));
        }
        String v = values.get(key);
        return v != null ? v : BUILTIN.get(key);
    }

    // what the settings are now: changes when the file is read again or a server's take its place (BalanceFinal's cache)
    public static synchronized Object version() {
        long now = System.currentTimeMillis();
        if (now - lastPoll > 1000L) {
            lastPoll = now;
            poll();
        }
        return java.util.List.of(generation, System.identityHashCode(values0()));
    }

    // the key is in the server's file (or the server's while connected to one), not only among the mod's defaults
    public static synchronized boolean inFile(String key) {
        long now = System.currentTimeMillis();
        if (now - lastPoll > 1000L) {
            lastPoll = now;
            poll();
        }
        // a key of the balance is never the file's (but on the stands)
        return (!BalanceKeys.is(key) || BalanceKeys.dev()) && values0().containsKey(key);
    }

    /**
     * the file read again now, whatever the clock: a stand that runs its ticks faster than real time (the balance's
     * /tick sprint) writes its keys and must not wait out the second between two looks at the file
     */
    static void reloadNow() {
        synchronized (ReaimTimes.class) {
            lastPoll = System.currentTimeMillis();
            stamp = Long.MIN_VALUE;
            numbersStamp = Long.MIN_VALUE;
            poll();
        }
        // outside ReaimTimes' lock: BulletRules reads ReaimTimes under its own
        BalanceGuns.reloadNow();
        BulletRules.reloadNow();
    }

    // a yes/no server setting (bulletBreakConcrete = true)
    public static boolean flag(String key, boolean def) {
        return number(key, def ? 1F : 0F) != 0F;
    }

    public static int ticks(String gun, String kind) {
        return Math.round(seconds(gun, kind) * 20F);
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE);
    }

    // the balance's old keys in the file, logged once for each set of them
    private static java.util.List<String> ignored = java.util.List.of();

    private static void ignored() {
        if (BalanceKeys.dev()) return;
        var old = VALUES.keySet().stream().filter(BalanceKeys::is).sorted().toList();
        if (!old.isEmpty() && !old.equals(ignored))
            LOGGER.info("{}: {} keys of the balance are not read any more, only balance = arms | ntm: {}", FILE, old.size(), String.join(", ", old));
        ignored = old;
        String sw = VALUES.get(BalanceKeys.KEY);
        if (sw != null && !sw.trim().equalsIgnoreCase("arms") && !BalanceKeys.ntm(sw)) LOGGER.warn("{}: balance = {} is neither arms nor ntm: arms", FILE, sw.trim());
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
            generation++;
            VALUES.clear();
            for (String key : p.stringPropertyNames()) VALUES.put(key, p.getProperty(key));
            ignored();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read {}", FILE, e);
        }
    }
}
