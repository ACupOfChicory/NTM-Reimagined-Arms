package acup.gunfeel;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

// port: copied from 26.2 (MachineGlass.java, 901b1b3, see notes/ports/port-1.7.10-copied.md) with Java 8's library;
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

    private static volatile Set<String> empty;

    // the server says machine windows are glass (on unless bulletMachineGlass = false); tests set their own
    static java.util.function.BooleanSupplier setting = () -> ReaimTimes.flag(KEY, true);

    public static boolean on() {
        return setting.getAsBoolean();
    }

    // a texture ("hbm:textures/models/...png", as 1.7.10 names it) whose clear pixels are empty after all
    public static boolean empty(String texture) {
        Set<String> e = empty;
        if (e == null) empty = e = read();
        return e.contains(texture);
    }

    static Set<String> read() {
        Set<String> out = new HashSet<String>();
        try (InputStream in = MachineGlass.class.getResourceAsStream(FILE)) {
            if (in == null) return out;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null;) {
                String t = parse(line);
                if (t != null) out.add(path1710(t));
            }
        } catch (Exception e) {
            GunFeel.LOGGER.warn("Could not read {}: every machine's clear pixels count as glass", FILE, e);
        }
        return out;
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
