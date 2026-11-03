package acup.gunfeel;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// port: copied from 26.2 (MachineGlass.java, 901b1b3; the areas from feature/machine-glass 72831aa, see
// notes/ports/port-1.7.10-copied.md) with Java 8's library;
// the list's 26.2 texture paths are read as 1.7.10's (path1710), and covers() is not needed (1.7.10's machines are
// taken as a whole in ShapesScenario: MachineCapture). The glass is cells here (MachineCells' "/glass"), not 26.2's
// faces (ModelSurface): it goes over to faces when the port has them.
// The clear pixels (alpha below 26) on the model of an NTM machine are glass, not air: a chemical plant's windows.
// A bullet pierces them as glass (Materials.GLASS over PATH, whatever bulletPierceMachines says), with glass sounds,
// crumbs and a hole that stays. Textures whose clear pixels really are empty (grates, nets, cut-outs) are listed in
// ntmgunfeel-machine-glass.txt; light (a triangle clear at every corner) is never glass. Server switch:
// bulletMachineGlass.
public final class MachineGlass {
    private MachineGlass() {
    }

    public static final String KEY = "bulletMachineGlass";
    public static final String FILE = "/ntmgunfeel-machine-glass.txt";
    // how much glass a bullet goes through in a window: a pixel of a machine's model
    public static final double PATH = 1.0 / 16.0;
    public static final Materials.Verdict VERDICT = new Materials.Verdict(Materials.Material.GLASS, 1F, Materials.Source.RULE, "machine window");

    // the textures listed whole, and the areas listed of others ("texture x0,y0,x1,y1": its clear pixels in that
    // rectangle, both corners in, are empty; the rest of them glass), by 1.7.10's names
    static final class Rules {
        final Set<String> whole;
        final Map<String, List<int[]>> areas;

        Rules(Set<String> whole, Map<String, List<int[]>> areas) {
            this.whole = whole;
            this.areas = areas;
        }
    }

    private static volatile Rules rules;

    // the server says machine windows are glass (on unless bulletMachineGlass = false); tests set their own
    static java.util.function.BooleanSupplier setting = () -> ReaimTimes.flag(KEY, true);

    public static boolean on() {
        return setting.getAsBoolean();
    }

    private static Rules rules() {
        Rules r = rules;
        if (r == null) rules = r = read();
        return r;
    }

    // a texture ("hbm:textures/models/...png", as 1.7.10 names it) whose clear pixels are all empty after all
    public static boolean empty(String texture) {
        return texture != null && rules().whole.contains(texture);
    }

    // the texture has areas whose clear pixels are empty (none: all glass, or the whole texture listed)
    public static boolean hasAreas(String texture) {
        return texture != null && rules().areas.containsKey(texture);
    }

    // the clear pixel (x, y) of the texture (in its pixels: NTM's textures are the same files in 1.7.10 and 26.2) is
    // empty
    public static boolean emptyAt(String texture, int x, int y) {
        if (texture == null) return false;
        Rules r = rules();
        if (r.whole.contains(texture)) return true;
        List<int[]> as = r.areas.get(texture);
        if (as != null) for (int[] a : as) if (x >= a[0] && x <= a[2] && y >= a[1] && y <= a[3]) return true;
        return false;
    }

    // the same at texture coordinates (u, v; the texture repeats out of 0..1) on a w x h texture
    public static boolean emptyAt(String texture, double u, double v, int w, int h) {
        if (w <= 0 || h <= 0) return empty(texture);
        return emptyAt(texture, Math.floorMod((int) Math.floor(u * w), w), Math.floorMod((int) Math.floor(v * h), h));
    }

    static Rules read() {
        try (InputStream in = MachineGlass.class.getResourceAsStream(FILE)) {
            if (in == null) return new Rules(Collections.<String>emptySet(), Collections.<String, List<int[]>>emptyMap());
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String> lines = new ArrayList<String>();
            for (String line; (line = r.readLine()) != null;) lines.add(line);
            return rules(lines);
        } catch (Exception e) {
            GunFeel.LOGGER.warn("Could not read {}: every machine's clear pixels count as glass", FILE, e);
            return new Rules(Collections.<String>emptySet(), Collections.<String, List<int[]>>emptyMap());
        }
    }

    static Rules rules(List<String> lines) {
        Set<String> whole = new HashSet<String>();
        Map<String, List<int[]>> areas = new HashMap<String, List<int[]>>();
        for (String line : lines) {
            String t = parse(line);
            if (t == null) continue;
            t = path1710(t);
            int[] a = area(line);
            if (a == null) whole.add(t);
            else {
                List<int[]> l = areas.get(t);
                if (l == null) areas.put(t, l = new ArrayList<int[]>());
                l.add(a);
            }
        }
        return new Rules(whole, areas);
    }

    // tests: these lines from now on (null: the jar's list)
    static void use(List<String> lines) {
        rules = lines == null ? null : rules(lines);
    }

    // the area of a line ("texture x0,y0,x1,y1 # why"), or null: the whole texture
    static int[] area(String line) {
        String l = line.trim();
        int hash = l.indexOf(" #");
        if (hash >= 0) l = l.substring(0, hash).trim();
        String[] f = l.split("\\s+");
        if (f.length < 2) return null;
        String[] n = f[1].split(",");
        if (n.length != 4) return null;
        try {
            return new int[] {Integer.parseInt(n[0]), Integer.parseInt(n[1]), Integer.parseInt(n[2]), Integer.parseInt(n[3])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // a line of the list: the texture, then " # " and why; blank lines and "#" lines are skipped
    static String parse(String line) {
        String l = line.trim();
        int hash = l.indexOf(" #");
        if (hash >= 0) l = l.substring(0, hash).trim();
        if (l.isEmpty() || l.startsWith("#")) return null;
        return l.split("\\s+")[0];
    }

    // 26.2's texture path as NTM 1.7.10 has it: its models' textures under textures/models/ (26.2:
    // textures/block/models/), its blocks' under textures/blocks/ (26.2: textures/block/)
    static String path1710(String t) {
        if (t.contains(":textures/block/models/")) return t.replace(":textures/block/models/", ":textures/models/");
        if (t.contains(":textures/block/")) return t.replace(":textures/block/", ":textures/blocks/");
        return t;
    }
}
