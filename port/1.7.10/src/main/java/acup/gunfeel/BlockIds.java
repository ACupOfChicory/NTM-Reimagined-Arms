package acup.gunfeel;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Block ids of 1.7.10 as the bullet rules name them (port only; 26.2 has block states with their own ids). A block is
// (its registry name, metadata 0-15). The rules and the materials were written for 26.2: NTM's "hbm:tile.steel_wall"
// is "hbm:steel_wall" there, and vanilla blocks that 1.7.10 tells apart by metadata have names of their own
// ("minecraft:planks" meta 2 is "minecraft:birch_planks"): ntmgunfeel-ids-1.7.10.txt. A rule may use either name.
// No Minecraft here (the tests use it as it is).
public final class BlockIds {
    private BlockIds() {
    }

    private static volatile Map<String, String> aliases;

    // the registry name as the rules have it: NTM's (and other mods') "tile." prefix left out
    public static String canonical(String raw) {
        if (raw == null) return "minecraft:air";
        int c = raw.indexOf(':');
        String ns = c < 0 ? "minecraft" : raw.substring(0, c), path = c < 0 ? raw : raw.substring(c + 1);
        if (path.startsWith("tile.")) path = path.substring(5);
        return ns + ":" + path;
    }

    // the 26.2 name of this block and meta, else its canonical name
    public static String id(String raw, int meta) {
        String own = canonical(raw);
        Map<String, String> a = aliases();
        String v = a.get(own + "/" + meta);
        if (v == null) v = a.get(own);
        return v == null ? own : v;
    }

    // every name a rule may give the block: its 26.2 name, its canonical one, and that with its meta ("minecraft:wool:14")
    public static List<String> names(String raw, int meta) {
        String own = canonical(raw), alias = id(raw, meta);
        List<String> out = new ArrayList<String>(3);
        out.add(alias);
        if (!alias.equals(own)) out.add(own);
        out.add(own + ":" + meta);
        return out;
    }

    // the state as the test log writes it: "hbm:steel_wall" or with its meta "minecraft:wool[meta=14]" (26.2: the
    // block state with its properties)
    public static String state(String raw, int meta) {
        String own = canonical(raw);
        return meta == 0 ? own : own + "[meta=" + meta + "]";
    }

    static Map<String, String> aliases() {
        Map<String, String> a = aliases;
        if (a != null) return a;
        Map<String, String> out = new HashMap<String, String>();
        try (InputStream in = BlockIds.class.getResourceAsStream("/ntmgunfeel-ids-1.7.10.txt")) {
            if (in != null) {
                List<String> lines = new ArrayList<String>();
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String l; (l = r.readLine()) != null;) lines.add(l);
                out.putAll(parse(lines));
            }
        } catch (Exception e) {
            // no aliases: canonical names only
        }
        return aliases = Collections.unmodifiableMap(out);
    }

    // "<1.7.10 id>[/<meta>] = <26.2 id>" lines; # comments
    static Map<String, String> parse(List<String> lines) {
        Map<String, String> out = new HashMap<String, String>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf(" = ");
            if (eq < 0) continue;
            String k = line.substring(0, eq).trim(), v = line.substring(eq + 3).trim();
            int slash = k.indexOf('/');
            String id = canonical(slash < 0 ? k : k.substring(0, slash));
            out.put(slash < 0 ? id : id + k.substring(slash), v);
        }
        return out;
    }
}
