package acup.gunfeel;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.jspecify.annotations.Nullable;

/**
 * The cells bullets meet in blocks with a 3D model inside their hitbox (NTM's steel scaffold, poles) and the fence
 * families' (ntmgunfeel-bullet-shapes.properties, made by the client from its models, see MeshShapes), by key: the
 * jar's; this game's (the jar's, then the config file's and this client's); on another game's server the jar's and
 * that server's (ServerSettings). The maps are replaced whole, never changed in place: bullets read them on other
 * threads. PassableClip names it for its callers and forgets its own caches by block state when the table changes.
 */
public final class ShapeTable {
    private ShapeTable() {
    }

    public static final String FILE = "ntm-gunfeel-bullet-shapes.properties";

    private static Map<String, long[]> jar = Map.of();
    private static volatile Map<String, long[]> local = Map.of();
    private static volatile @Nullable Map<String, long[]> remote;
    private static volatile int localVersion;
    private static volatile boolean loaded;

    // shapes made by this client: used at once (single player, the client's own tracers); on another game's server they
    // wait until the client leaves it, as that server goes by its own
    public static synchronized void put(Map<String, long[]> shapes) {
        Map<String, long[]> next = new HashMap<>(local());
        next.putAll(shapes);
        local = next;
        localVersion++;
    }

    // the server's shapes beyond the jar's (packed, by key), or null: back to this game's own
    public static synchronized void useRemote(@Nullable Map<String, String> packed) {
        load0();
        if (packed == null) {
            remote = null;
        } else {
            Map<String, long[]> m = new HashMap<>(jar);
            for (Map.Entry<String, String> e : packed.entrySet()) {
                long[] bits = ShapeCells.unpack(e.getValue());
                if (bits != null) m.put(e.getKey(), bits);
            }
            remote = m;
        }
    }

    // goes up when this game's shapes change (the server sends them again)
    public static int localVersion() {
        return localVersion;
    }

    // this game's shapes that the jar has not (or has other cells for), packed: what the server sends its players
    public static Map<String, String> extra() {
        Map<String, long[]> mine = local();
        Map<String, String> out = new java.util.TreeMap<>();
        for (Map.Entry<String, long[]> e : mine.entrySet()) {
            if (!java.util.Arrays.equals(jar.get(e.getKey()), e.getValue())) out.put(e.getKey(), ShapePacking.SHARED.pack(e.getValue()));
        }
        return out;
    }

    // the cells under a key (block shapes and the cells of big models: see Structures), as bullets meet them; a machine
    // the jar's pack has (made with this game's NTM) by the pack: the config's may be from an older mod
    public static long @Nullable [] byKey(String key) {
        long[] packed = ShapePack.get(key);
        return packed != null ? packed : shapes().get(key);
    }

    // the cells under a key that this game knows (not the server's)
    public static long @Nullable [] localByKey(String key) {
        return local().get(key);
    }

    // every key and its cells this game knows (the jar's, the config's, this client's)
    public static Map<String, long[]> all() {
        return new java.util.TreeMap<>(local());
    }

    // the shapes bullets go by: the server's on another game's server, else this game's
    public static Map<String, long[]> shapes() {
        Map<String, long[]> r = remote;
        return r != null ? r : local();
    }

    private static Map<String, long[]> local() {
        if (!loaded) load0();
        return local;
    }

    /** the table read, once (a bullet's first look at a block's shape) */
    public static void load() {
        if (!loaded) load0();
    }

    // read once: the version's config folder asked only then, as the file's path was
    private static synchronized void load0() {
        if (!loaded) load0(GameDirs.get().config());
    }

    // the jar's shapes, then the config folder's file over them; the folder: the version's (GameDirs), a test's own
    static synchronized void load0(Path config) {
        if (loaded) return;
        Map<String, long[]> fromJar = new HashMap<>();
        try (var in = ShapeTable.class.getResourceAsStream("/ntmgunfeel-bullet-shapes.properties")) {
            if (in != null) read(new InputStreamReader(in, StandardCharsets.UTF_8), fromJar);
        } catch (Exception e) {
            ModLog.LOGGER.warn("Could not read the bullet shapes", e);
        }
        Map<String, long[]> mine = new HashMap<>(fromJar);
        // made by a client of this mod version (MeshShapes); wins over the jar's
        java.nio.file.Path f = config.resolve(FILE);
        if (java.nio.file.Files.exists(f)) {
            try (var r = java.nio.file.Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                read(r, mine);
            } catch (Exception e) {
                ModLog.LOGGER.warn("Could not read {}", FILE, e);
            }
        }
        jar = fromJar;
        local = mine;
        loaded = true;
    }

    // "key = <shape>" or "key = @n" with "@n = <shape>" written once; a shape is 1024 hex digits or "z" and the
    // deflated 512 bytes in base64, after "!" for a shell
    static void read(java.io.Reader r, Map<String, long[]> into) throws java.io.IOException {
        Properties p = new Properties();
        p.load(r);
        for (String k : p.stringPropertyNames()) {
            if (k.startsWith("@")) continue;
            String v = p.getProperty(k).trim();
            if (v.startsWith("@")) v = p.getProperty(v, "").trim();
            long[] bits = ShapeCells.unpack(v);
            if (bits != null) into.put(k, bits);
        }
    }
}
