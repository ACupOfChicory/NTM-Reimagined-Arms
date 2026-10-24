package acup.gunfeel;

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

import net.neoforged.fml.loading.FMLPaths;

// Recoil presets: "<preset>.<channel> = <Spring>" in the jar's ntmgunfeel-recoil.properties, and the player's own in
// config/ntm-gunfeel-recoil.properties (the editor's "save as preset"), which come on top. A gun picks one preset
// for its model kick (kickPreset) and one for the camera (cameraPreset, empty = the same as kickPreset); a channel
// key of its own (kickPitch = 8,0.04,0.3) replaces one movement of the preset. A preset missing a channel takes
// "default"'s.
public final class RecoilPresets {
    public static final String[] KICK = {"kickPitch", "kickYaw", "kickRoll", "kickPosY", "kickPosZ"};
    public static final String[] CAMERA = {"cameraPitch", "cameraYaw", "cameraRoll"};
    public static final String DEFAULT = "default";
    public static final String FILE = "ntm-gunfeel-recoil.properties";

    private static final Properties JAR = load();
    private static final Properties MINE = new Properties();
    private static Path file;
    private static long stamp = Long.MIN_VALUE, nextCheck;

    private RecoilPresets() {
    }

    private static Properties load() {
        Properties p = new Properties();
        try (var in = RecoilPresets.class.getResourceAsStream("/ntmgunfeel-recoil.properties")) {
            if (in != null) p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            GunFeel.LOGGER.warn("Could not read the recoil presets", e);
        }
        return p;
    }

    // the player's file again when it changed (at most once a second)
    private static void poll() {
        long now = System.currentTimeMillis();
        if (now < nextCheck) return;
        nextCheck = now + 1000;
        try {
            if (file == null) file = FMLPaths.CONFIGDIR.get().resolve(FILE);
            long m = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
            if (m == stamp) return;
            stamp = m;
            MINE.clear();
            if (m != 0L) try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                MINE.load(r);
            }
        } catch (Exception e) {
            GunFeel.LOGGER.warn("Could not read {}", file, e);
        }
    }

    public static boolean camera(String channel) {
        return channel.startsWith("camera");
    }

    // the preset this channel of the gun in effect (Config) takes
    public static String presetOf(String channel) {
        String p = camera(channel) && !Config.cameraPreset.isBlank() ? Config.cameraPreset : Config.kickPreset;
        return p.isBlank() ? DEFAULT : p;
    }

    // a preset's movement; the jar's only when `jarOnly` (the server's minimum: no shape of the player's)
    public static Spring preset(String preset, String channel, boolean jarOnly) {
        poll();
        Spring s = find(preset, channel, jarOnly);
        if (s == null) s = find(DEFAULT, channel, jarOnly);
        return s != null ? s : Spring.ZERO;
    }

    private static Spring find(String preset, String channel, boolean jarOnly) {
        String k = preset + "." + channel;
        String v = jarOnly ? null : MINE.getProperty(k);
        if (v == null) v = JAR.getProperty(k);
        return Spring.parse(v);
    }

    // the movement in effect for the selected gun: its own key (a Spring or a "t:v" curve), else its preset's
    public static Recoil channel(String channel) {
        String own = own(channel);
        Recoil r = Spring.parse(own);
        if (r == null) r = Curve.of(own);
        return r != null ? r : preset(presetOf(channel), channel, false);
    }

    // the channel as a Spring, for the editor: a curve of the player's shows as the preset's spring
    public static Spring spring(String channel) {
        Spring s = Spring.parse(own(channel));
        return s != null ? s : preset(presetOf(channel), channel, false);
    }

    static String own(String channel) {
        return switch (channel) {
            case "kickPitch" -> Config.kickPitch;
            case "kickYaw" -> Config.kickYaw;
            case "kickRoll" -> Config.kickRoll;
            case "kickPosY" -> Config.kickPosY;
            case "kickPosZ" -> Config.kickPosZ;
            case "cameraPitch" -> Config.cameraPitch;
            case "cameraYaw" -> Config.cameraYaw;
            case "cameraRoll" -> Config.cameraRoll;
            default -> "";
        };
    }

    // every preset name, "default" first
    public static List<String> names() {
        poll();
        TreeSet<String> all = new TreeSet<>();
        for (Properties p : new Properties[] {JAR, MINE}) {
            for (String k : p.stringPropertyNames()) {
                int dot = k.indexOf('.');
                if (dot > 0) all.add(k.substring(0, dot));
            }
        }
        all.remove(DEFAULT);
        List<String> out = new ArrayList<>();
        out.add(DEFAULT);
        out.addAll(all);
        return out;
    }

    // the player's preset `name` with these movements (the file keeps its other presets and channels)
    public static void save(String name, Map<String, Spring> channels) {
        poll();
        Properties p = new Properties();
        p.putAll(MINE);
        channels.forEach((c, s) -> p.setProperty(name + "." + c, s.toString()));
        try {
            if (file == null) file = FMLPaths.CONFIGDIR.get().resolve(FILE);
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write("# NTM Gun Feel recoil presets of your own, on top of the mod's: <preset>.<channel> = "
                        + "amount,attack,settle,bounce,delay,freq\n");
                for (String k : new TreeSet<>(p.stringPropertyNames())) {
                    w.write(k.replace(":", "\\:").replace("=", "\\=") + " = " + p.getProperty(k) + "\n");
                }
            }
            nextCheck = 0L;
        } catch (Exception e) {
            GunFeel.LOGGER.warn("Could not write {}", file, e);
        }
    }
}
