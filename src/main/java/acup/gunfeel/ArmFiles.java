package acup.gunfeel;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * The arm editor's files: a gun's arms (Grips.armsOf) written to config/ntm-gunfeel-export, one file per export, and
 * read back with a check that each key is an arm setting of that gun and the game can read its value.
 * tools/merge_arms.py puts such a file into the jar's ntmgunfeel-guns.properties.
 */
final class ArmFiles {
    static final String FOLDER = "ntm-gunfeel-export";
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"),
            HEAD = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"), SHOWN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    // null = config/ntm-gunfeel-export of the game; a test sets its own
    static Path dir;

    private ArmFiles() {
    }

    static Path dir() {
        if (dir == null) dir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(FOLDER);
        return dir;
    }

    // a file of the folder: where it is, and when it was written (from its name)
    record Saved(Path path, LocalDateTime when) {
        String label() {
            return "Файл от " + SHOWN.format(when);
        }
    }

    // what a file says: the gun its arms are for, the keys, and what is wrong with it (null = nothing)
    record Read(String gun, Map<String, String> arms, String problem) {
    }

    // a gun's id as a file name: "hbm:gun_autoshotgun_sexy" -> "hbm_gun_autoshotgun_sexy"
    static String stem(String gun) {
        return gun.replaceAll("[^A-Za-z0-9_.]", "_");
    }

    // the text of an export: a head (gun, its name, mod version, date) and one line per key, as the jar's file has them
    static String text(String gun, String name, String openedOn, String version, LocalDateTime when, Map<String, String> arms) {
        var sb = new StringBuilder();
        sb.append("# NTM Reimagined Arms: руки оружия из редактора рук\n");
        sb.append("# gun: ").append(gun).append('\n');
        sb.append("# name: ").append(name).append('\n');
        if (!openedOn.equals(gun)) sb.append("# opened on: ").append(openedOn).append('\n');
        sb.append("# mod: ").append(version).append('\n');
        sb.append("# date: ").append(HEAD.format(when)).append('\n');
        sb.append("# tools/merge_arms.py <this file> puts these keys into src/main/resources/ntmgunfeel-guns.properties\n");
        for (var e : new TreeMap<>(arms).entrySet())
            sb.append(e.getKey().replace(":", "\\:")).append(" =").append(e.getValue().isEmpty() ? "" : " " + e.getValue()).append('\n');
        return sb.toString();
    }

    // writes an export into the folder; the file's path
    static Path write(String gun, String text, LocalDateTime when) throws IOException {
        Path d = dir();
        Files.createDirectories(d);
        String base = stem(gun) + "-" + NAME.format(when);
        // twice in one second: the later one
        Path p = d.resolve(base + ".properties");
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    // the folder's files for this gun, the newest first
    static List<Saved> files(String gun) {
        var out = new ArrayList<Saved>();
        String prefix = stem(gun) + "-";
        Path d = dir();
        if (!Files.isDirectory(d)) return out;
        try (var s = Files.list(d)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (!n.startsWith(prefix) || !n.endsWith(".properties")) continue;
                String date = n.substring(prefix.length(), n.length() - ".properties".length());
                try {
                    out.add(new Saved(p, LocalDateTime.parse(date, NAME)));
                } catch (RuntimeException e) {
                    // not one of ours
                }
            }
        } catch (IOException e) {
            GunFeel.LOGGER.warn("Could not list {}", d, e);
        }
        out.sort(Comparator.comparing((Saved x) -> x.path().getFileName().toString()).reversed());
        return out;
    }

    static Read read(Path p) {
        try {
            return read(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new Read(null, Map.of(), "не читается");
        }
    }

    static Read read(String text) {
        String gun = null;
        String[] lines = text.split("\n");
        for (String line : lines) if (line.startsWith("# gun: ")) gun = line.substring(7).trim();
        var p = new Properties();
        try {
            p.load(new StringReader(text));
        } catch (IOException | IllegalArgumentException e) {
            return new Read(gun, Map.of(), "не разбирается как файл настроек");
        }
        Map<String, String> arms = new TreeMap<>();
        for (String k : p.stringPropertyNames()) arms.put(k, p.getProperty(k).trim());
        if (gun == null) return new Read(null, arms, "в файле не сказано, для какого он оружия");
        String why = null;
        for (var e : arms.entrySet()) {
            String bad = problem(gun, e.getKey(), e.getValue(), ArmRig.pathTimes(arms.getOrDefault(gun + ".armPathTimes", "")));
            if (bad == null) continue;
            int line = 0;
            String escaped = e.getKey().replace(":", "\\:");
            for (int i = 0; i < lines.length && line == 0; i++)
                if (lines[i].startsWith(escaped + " ") || lines[i].startsWith(escaped + "=") || lines[i].startsWith(e.getKey() + " ")) line = i + 1;
            why = (line > 0 ? "строка " + line + ": " : "") + bad;
            break;
        }
        return new Read(gun, arms, why);
    }

    // what in this key the game would not read as an arm setting of this gun; null = nothing
    static String problem(String gun, String k, String v, Map<String, Float> times) {
        if (!k.startsWith(gun + ".") && !k.startsWith(gun + "-")) return "настройка другого оружия";
        String name = k.substring(k.lastIndexOf('.') + 1);
        if (!Grips.armKey(name)) return "это не настройка рук";
        return v.isEmpty() ? null : value(name, v, times);
    }

    private static String value(String name, String v, Map<String, Float> times) {
        if (name.equals("right") || name.equals("left") || name.equals("rightAt") || name.equals("leftAt")) return numbers(v, 3);
        Class<?> type;
        try {
            type = Config.class.getField(name).getType();
        } catch (NoSuchFieldException e) {
            return "такой настройки нет";
        }
        if (type == float.class) return numbers(v, 1);
        if (type == float[].class) return numbers(v, 3);
        if (type == boolean.class) return v.equals("true") || v.equals("false") ? null : "не «да» и не «нет»";
        if (name.endsWith("Path")) {
            for (var s : ArmRig.path(v).segments)
                for (var en : s.entries) if (en.key(times) == null) return "запись не разбирается";
            return null;
        }
        if (name.endsWith("CarryKeys")) return values(ArmRig.carryKeyTracks(v));
        if (name.endsWith("Hide")) return values(ArmRig.rangeTracks(v));
        if (name.endsWith("Adjustments")) return values(ArmRig.adjustmentTracks(v));
        if (name.equals("armLeftKeys")) return values(ArmRig.angleKeyTracks(v));
        if (name.contains("Carry")) return ArmRig.carry(v) == null ? "не разбирается" : null;
        if (name.equals("armPathTimes")) {
            for (String t : v.split(","))
                if (!t.isBlank() && !t.trim().matches("[^=]+=\\s*-?[0-9.]+")) return "запись не разбирается";
            return null;
        }
        return null;
    }

    private static String values(ArmRig.Tracks<ArmRig.Value> t) {
        for (var s : t.segments) for (var en : s.entries) if (en.v() == null) return "запись не разбирается";
        return null;
    }

    private static String numbers(String v, int n) {
        String[] parts = v.split(",");
        if (parts.length != n) return n == 1 ? "не число" : "нужно " + n + " числа через запятую";
        for (String s : parts)
            try {
                Float.parseFloat(s.trim());
            } catch (NumberFormatException e) {
                return "не число";
            }
        return null;
    }
}
