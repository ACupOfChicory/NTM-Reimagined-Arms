package acup.gunfeel;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.minecraft.resources.Identifier;
import net.neoforged.fml.loading.FMLPaths;

// Per-gun settings, config/ntm-gunfeel-guns.properties, keys "<gun id>.<setting>":
//   hbm\:gun_maresleg.right = 0.5,0.05,0.30      hand positions (see below)
//   hbm\:gun_maresleg.armRollLeft = -55          any setting from ntm-gunfeel.properties
// Hand positions: z = fraction of the gun length, stock (0) to muzzle (1); x and y = fractions of the
// model's cross-section at that z (y 0 = underside), see GunGeometry.grip.
//   hbm\:gun_g3.rightAt = 0,-1.8,-4.6              or a point in model units (x left, y up, z to the muzzle),
// which does not move when attachments change the model's size; it wins over .right/.left.
public final class Grips {
    // rightAt / leftAt: point in model units or null
    public record Grip(float[] right, float[] left, float[] rightAt, float[] leftAt) {
    }

    public static final Grip DEFAULT = new Grip(new float[] {0.5F, 0F, 0.33F}, new float[] {0.5F, 0F, 0.70F}, null, null);
    private static final String FILE = "ntm-gunfeel-guns.properties";
    private static final String OLD_FILE = "ntm-gunfeel-grips.properties";
    // what this gun's settings are: the ones shipped in the jar (BUILTIN), with the player's file (USER) on top.
    // The file keeps only what differs from the jar; "" there hides a shipped value.
    private static final Map<String, String> BUILTIN = builtin();
    private static final Map<String, String> USER = new HashMap<>();
    private static final Map<String, String> VALUES = new HashMap<>(BUILTIN);
    private static Path file;
    private static long stamp = Long.MIN_VALUE;
    private static int version;
    private static String storedKey(String key) {
        return key;
    }

    public static int version() {
        return version;
    }

    private Grips() {
    }

    public static Grip get(Identifier id) {
        return get(id, "");
    }

    // A variant of the gun (missing = the model parts it does not draw, e.g. "-Barrel" for a sawn-off) may
    // have its own hand positions: "<gun id>-Barrel.leftAt"; each hand falls back to the gun's own.
    public static Grip get(Identifier id, String missing) {
        String base = armsGun(id.toString()), own = base + missing;
        Grip b = read(base);
        if (missing.isEmpty()) return b;
        Grip v = read(own);
        // an empty value (the player's file taking a hand of the jar off) is no hand: the gun's own is used
        boolean vr = given(own + ".right") || given(own + ".rightAt");
        boolean vl = given(own + ".left") || given(own + ".leftAt");
        return new Grip(vr ? v.right() : b.right(), vl ? v.left() : b.left(), vr ? v.rightAt() : b.rightAt(), vl ? v.leftAt() : b.leftAt());
    }

    private static boolean given(String key) {
        String v = VALUES.get(key);
        return v != null && !v.isBlank();
    }

    private static Grip read(String key) {
        float[] r = vec(VALUES.get(key + ".right")), l = vec(VALUES.get(key + ".left"));
        return new Grip(r != null ? r : DEFAULT.right(), l != null ? l : DEFAULT.left(), vec(VALUES.get(key + ".rightAt")),
                vec(VALUES.get(key + ".leftAt")));
    }

    // settings overridden for this gun (without hand positions)
    // A gun with sameAs takes the other gun's settings; its own entries (other than sameAs) win over them.
    public static Map<String, String> overrides(String gun) {
        Map<String, String> out = new TreeMap<>();
        String target = canon(gun);
        collect(target, out);
        // the gun's own "" hides the other gun's value too (own arms: a hide taken off)
        if (!target.equals(gun)) collect(gun, out);
        out.values().removeIf(String::isEmpty);
        out.remove("sameAs");
        return out;
    }

    private static void collect(String gun, Map<String, String> out) {
        String prefix = gun + ".";
        for (Map.Entry<String, String> e : VALUES.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            String k = e.getKey().substring(prefix.length());
            if (k.startsWith("slim.") || k.equals("right") || k.equals("left") || k.equals("rightAt") || k.equals("leftAt")) continue;
            out.put(k, e.getValue());
        }
    }

    // this gun's value shipped in the jar (its own, else the sameAs gun's); null = none
    public static String shipped(String gun, String key) {
        String v = BUILTIN.get(gun + "." + key);
        if (v == null || v.isEmpty()) v = BUILTIN.get(canon(gun) + "." + key);
        return v == null || v.isEmpty() ? null : v;
    }

    // this gun's value from the player's file, "" = hides the shipped one; null = none
    public static String own(String gun, String key) {
        key = storedKey(key);
        String v = USER.get(gun + "." + key);
        return v != null ? v : USER.get(canon(gun) + "." + key);
    }

    public static void set(Identifier id, boolean right, float x, float y, float z) {
        set(id.toString(), right, x, y, z);
    }

    // gun = "<gun id>" or "<gun id><missing parts>" for a variant (a sawn-off: "hbm:gun_maresleg-Stock-Barrel")
    public static void set(String gun, boolean right, float x, float y, float z) {
        set(gun, right, x, y, z, true);
    }

    // save false: in effect at once, written to the file by flush() (the editor, while a slider is dragged)
    public static void set(String gun, boolean right, float x, float y, float z, boolean save) {
        gun = armsGun(gun);
        // a point in model units would win over these fractions: hide it
        String atKey = right ? "rightAt" : "leftAt";
        String at = gun + "." + storedKey(atKey);
        if (VALUES.containsKey(gun + "." + atKey)) USER.put(at, "");
        else USER.remove(at);
        put(gun, right ? "right" : "left", ArmRig.vector(new float[] {x, y, z}), save);
    }

    // a point in model units for one hand (x left, y up, z to the muzzle)
    public static void setAt(String gun, boolean right, float x, float y, float z) {
        setAt(gun, right, x, y, z, true);
    }

    public static void setAt(String gun, boolean right, float x, float y, float z, boolean save) {
        gun = armsGun(gun);
        // the point wins over fractions: the player's own ones go (the jar's stay, they are not the player's)
        String frac = gun + "." + storedKey(right ? "right" : "left");
        if (!BUILTIN.containsKey(frac)) USER.remove(frac);
        put(gun, right ? "rightAt" : "leftAt", ArmRig.vector(new float[] {x, y, z}), save);
    }

    // value null = back to the jar's value (or none)
    public static void put(String gun, String key, String value) {
        put(gun, key, value, true);
    }

    public static void put(String gun, String key, String value, boolean save) {
        // a setting the gun has of its own stays its own; anything else goes to the gun it is the same as,
        // unless the gun was given arms of its own (the arm editor's "own arms")
        if (!key.equals("sameAs") && !VALUES.containsKey(gun + "." + key) && !(armKey(key) && ownArms(baseOf(gun)))) gun = canon(gun);
        key = storedKey(key);
        String k = gun + "." + key;
        if (value == null || value.equals(BUILTIN.get(k))) USER.remove(k);
        else USER.put(k, value);
        merge();
        if (save) save();
        else dirty = true;
    }

    private static boolean dirty;

    // writes what put(..., false) left in memory only
    public static void flush() {
        if (dirty) save();
    }

    // the player's own settings as they are now, to go back to with restore() (the editor's "undo all")
    public static Map<String, String> snapshot() {
        return new HashMap<>(USER);
    }

    public static void restore(Map<String, String> user) {
        USER.clear();
        USER.putAll(user);
        merge();
        save();
    }

    // restore() for the editors' undo: in effect at once, written by flush()
    static void restoreUnsaved(Map<String, String> user) {
        USER.clear();
        USER.putAll(user);
        merge();
        dirty = true;
    }

    // a key of the arms: hand positions and every arm* setting
    static boolean armKey(String key) {
        return key.startsWith("arm") || key.equals("right") || key.equals("left") || key.equals("rightAt") || key.equals("leftAt");
    }

    // where a gun's hands are read and written: the gun itself when it has arms of its own, else as canon()
    static String armsGun(String gun) {
        return ownArms(baseOf(gun)) ? gun : canon(gun);
    }

    static String baseOf(String gun) {
        int dash = gun.indexOf('-');
        return dash < 0 ? gun : gun.substring(0, dash);
    }

    // a variant ("<gun>-Barrel") has a hand of its own: in the jar (shipped) or the player's file
    static boolean variantHand(String variant, boolean right, boolean shippedOnly) {
        String g = armsGun(variant), h = right ? "right" : "left";
        var from = shippedOnly ? BUILTIN : VALUES;
        return from.containsKey(g + "." + h) || from.containsKey(g + "." + h + "At");
    }

    // the gun this one shares its settings with (sameAs), null = none
    static String sharedWith(String gun) {
        String to = canon(gun);
        return to.equals(gun) ? null : to;
    }

    // this gun (one with sameAs) has arms of its own: some arm key of it is in the player's file, or in the jar
    // (arms the editor exported and merge_arms.py put there)
    static boolean ownArms(String gun) {
        if (sharedWith(gun) == null) return false;
        for (String k : USER.keySet()) if (armKeyOf(gun, k)) return true;
        return ownArmsShipped(gun);
    }

    // the jar itself gives this gun (one with sameAs) arms of its own: an arm key of it whose value is not the one the
    // shared gun has (a key the same as the shared gun's changes nothing)
    static boolean ownArmsShipped(String gun) {
        String to = sharedWith(gun);
        if (to == null) return false;
        for (var e : BUILTIN.entrySet()) {
            String k = e.getKey();
            if (!armKeyOf(gun, k)) continue;
            String shared = BUILTIN.getOrDefault(to + k.substring(gun.length()), "").trim();
            if (!e.getValue().trim().equals(shared)) return true;
        }
        return false;
    }

    // a full key ("<gun>.armLeftPath", "<gun>-Barrel.leftAt") of an arm setting of this gun or one of its variants
    private static boolean armKeyOf(String gun, String key) {
        return (key.startsWith(gun + ".") || key.startsWith(gun + "-")) && armKey(key.substring(key.lastIndexOf('.') + 1));
    }

    private static boolean gripKey(String key) {
        return key.equals("right") || key.equals("left") || key.equals("rightAt") || key.equals("leftAt");
    }

    // the arms of a gun as the game reads them, as full keys of the gun they are written under (armsGun): its arm
    // settings, its grips and its variants' keys; a gun that shares the model of another but has arms of its own
    // gets "" for each arm setting of the other gun it does not take. Sorted. Loading it back with putArms leaves
    // the arms as they are.
    static Map<String, String> armsOf(String gun) {
        String t = armsGun(baseOf(gun));
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : overrides(t).entrySet()) if (armKey(e.getKey())) out.put(t + "." + e.getKey(), e.getValue());
        for (Map.Entry<String, String> e : VALUES.entrySet()) {
            String k = e.getKey();
            if (e.getValue().isEmpty() || !armKeyOf(t, k)) continue;
            if (k.startsWith(t + "-") || gripKey(k.substring(t.length() + 1))) out.put(k, e.getValue());
        }
        String from = canon(t);
        if (!from.equals(t))
            for (Map.Entry<String, String> e : VALUES.entrySet()) {
                String k = e.getKey();
                if (!k.startsWith(from + ".") || e.getValue().isEmpty()) continue;
                String name = k.substring(from.length() + 1);
                if (armKey(name) && !gripKey(name)) out.putIfAbsent(t + "." + name, "");
            }
        return out;
    }

    // the arms of the gun (armsGun) made what this map says, as armsOf gives it: each arm key of the gun and its
    // variants takes its value from the map; one the map has not is hidden ("") where the jar or the other gun of a
    // sameAs would show through, else dropped. Keys of other guns in the map are ignored.
    static void putArms(String gun, Map<String, String> arms) {
        String t = armsGun(baseOf(gun)), from = canon(t);
        Set<String> keys = new TreeSet<>();
        for (String k : VALUES.keySet()) if (armKeyOf(t, k)) keys.add(k);
        for (String k : arms.keySet()) if (armKeyOf(t, k)) keys.add(k);
        if (!from.equals(t))
            for (String k : VALUES.keySet())
                if (k.startsWith(from + ".")) {
                    String name = k.substring(from.length() + 1);
                    if (armKey(name) && !gripKey(name)) keys.add(t + "." + name);
                }
        for (String k : keys) {
            String name = k.substring(k.lastIndexOf('.') + 1);
            String jar = BUILTIN.get(k);
            String other = from.equals(t) || gripKey(name) || !k.startsWith(t + ".") ? null : VALUES.get(from + "." + name);
            boolean through = jar != null && !jar.isEmpty() || other != null && !other.isEmpty();
            String want = arms.get(k);
            if (want == null || want.isEmpty()) want = through ? "" : null;
            if (want == null || want.equals(jar)) USER.remove(k);
            else USER.put(k, want);
        }
        merge();
        dirty = true;
    }

    // the guns with arm settings of their own (not a variant, not one that shares another's), as the game names them
    static List<String> gunsWithArms() {
        Set<String> out = new TreeSet<>();
        for (Map.Entry<String, String> e : VALUES.entrySet()) {
            String k = e.getKey();
            int dot = k.lastIndexOf('.');
            if (dot < 0 || e.getValue().isEmpty() || !armKey(k.substring(dot + 1))) continue;
            String g = k.substring(0, dot);
            if (!g.contains("-")) out.add(g);
        }
        return new ArrayList<>(out);
    }

    // the gun has some arm key set (jar or player's file)
    static boolean hasArms(String gun) {
        String prefix = gun + ".";
        for (Map.Entry<String, String> e : VALUES.entrySet())
            if (e.getKey().startsWith(prefix) && armKey(e.getKey().substring(prefix.length())) && !e.getValue().isEmpty()) return true;
        return false;
    }

    // the arm keys of the shared gun and its variants ("<gun>-Barrel.left"), as this gun's keys, that this gun has not
    private static Map<String, String> armsOfShared(String gun) {
        Map<String, String> out = new TreeMap<>();
        String from = sharedWith(gun);
        if (from == null) return out;
        for (Map.Entry<String, String> e : VALUES.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(from + ".") && !key.startsWith(from + "-")) continue;
            int dot = key.lastIndexOf('.');
            String mine = gun + key.substring(from.length(), dot) + key.substring(dot);
            if (armKey(key.substring(dot + 1)) && !VALUES.containsKey(mine)) out.put(mine, e.getValue());
        }
        return out;
    }

    // how many arm keys copyArms would copy
    static int armsToCopy(String gun) {
        return armsOfShared(gun).size();
    }

    // own arms: every arm key of the shared gun and its variants copied to this one (keys it has itself stay)
    static void copyArms(String gun) {
        USER.putAll(armsOfShared(gun));
        merge();
        dirty = true;
    }

    // shared arms again: the player's own arm keys of this gun and its variants removed
    static void shareArms(String gun) {
        USER.keySet().removeIf(k -> (k.startsWith(gun + ".") || k.startsWith(gun + "-")) && armKey(k.substring(k.lastIndexOf('.') + 1)));
        merge();
        dirty = true;
    }

    // one hand back to the jar's position (or computed from the model)
    public static void resetHand(String id, boolean right, boolean save) {
        id = armsGun(id);
        String h = right ? "right" : "left";
        USER.remove(id + "." + storedKey(h));
        USER.remove(id + "." + storedKey(h + "At"));
        merge();
        if (save) save();
        else dirty = true;
    }

    // the gun's own value of a key, as the player's file or the jar sets it for this gun; null = it takes the global one
    public static String gunValue(String gun, String key) {
        return overrides(gun).get(key);
    }

    public static void reset(Identifier id) {
        reset(id.toString());
    }

    // hand positions back to the jar's (or computed from the model when the jar has none)
    public static void reset(String id) {
        id = armsGun(id);
        USER.remove(id + ".right");
        USER.remove(id + ".left");
        USER.remove(id + ".rightAt");
        USER.remove(id + ".leftAt");
        merge();
        save();
    }

    // "<gun id>.sameAs = <other gun id>": a gun with the same model (Sacred Dragon = Old Classic) reads and
    // writes all its settings under the other gun. A variant suffix ("-Barrel") is kept.
    static String canon(String gun) {
        int dash = gun.indexOf('-');
        String base = dash < 0 ? gun : gun.substring(0, dash), rest = dash < 0 ? "" : gun.substring(dash);
        String to = VALUES.get(base + ".sameAs");
        return to == null || to.isEmpty() ? gun : to + rest;
    }

    private static void merge() {
        VALUES.clear();
        VALUES.putAll(BUILTIN);
        VALUES.putAll(USER);
        version++;
    }

    private static Map<String, String> builtin() {
        Map<String, String> out = new HashMap<>();
        try (var in = Grips.class.getResourceAsStream("/ntmgunfeel-guns.properties")) {
            if (in == null) return out;
            Properties p = new Properties();
            p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            for (String key : p.stringPropertyNames()) out.put(key, p.getProperty(key).trim());
        } catch (IOException | RuntimeException e) {
            DebugLog.LOGGER.warn("Could not read the built-in gun settings", e);
        }
        return out;
    }

    private static float[] vec(String v) {
        if (v == null) return null;
        String[] parts = v.split(",");
        if (parts.length != 3) return null;
        try {
            return new float[] {Float.parseFloat(parts[0].trim()), Float.parseFloat(parts[1].trim()), Float.parseFloat(parts[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Path file() throws IOException {
        if (file == null) {
            Path dir = FMLPaths.CONFIGDIR.get();
            file = dir.resolve(FILE);
            Path old = dir.resolve(OLD_FILE);
            if (!Files.exists(file) && Files.exists(old)) Files.move(old, file);
        }
        return file;
    }

    static void poll() {
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
            USER.clear();
            for (String key : p.stringPropertyNames()) {
                String v = p.getProperty(key).trim();
                // a value the jar already has is not the player's own
                if (!v.equals(BUILTIN.get(key))) USER.put(key, v);
            }
            merge();
        } catch (IOException | RuntimeException e) {
            DebugLog.LOGGER.warn("Could not read {}", file, e);
        }
    }

    private static void save() {
        try {
            Path f = file();
            try (Writer w = Files.newBufferedWriter(f)) {
                w.write("# NTM Reimagined Arms: your own settings per gun, on top of the ones in the mod jar\n");
                w.write("# <gun id>.<setting> = value (any key from ntm-gunfeel.properties); an empty value hides the jar's one\n");
                w.write("# hand positions: <gun id>.right / .left = x,y,z  z stock(0)-muzzle(1); x, y across the model there, y 0 = underside\n");
                w.write("# default right = 0.5,0,0.33  left = 0.5,0,0.70 (the hand sits under that point)\n");
                w.write("# or .rightAt / .leftAt = x,y,z in model units (x left, y up, z to the muzzle); wins over .right / .left\n");
                for (Map.Entry<String, String> e : new TreeMap<>(USER).entrySet()) {
                    w.write(e.getKey().replace(":", "\\:") + " = " + e.getValue() + "\n");
                }
            }
            stamp = Files.getLastModifiedTime(f).toMillis();
            dirty = false;
        } catch (IOException e) {
            DebugLog.LOGGER.warn("Could not write {}", file, e);
        }
    }
}
