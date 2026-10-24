package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

// What the player sees and feels, pinned: the numbers below are written to src/test/pinned/*.txt and every run must give
// the same, bit for bit. A change to the build (Gradle, Stonecutter, a port) or a clean-up must not move any of them.
// When a change is meant (new textures, a retuned curve), rewrite the pins with PIN_UPDATE=1 ./gradlew test and commit
// the .txt diff with it, so it is plain what moved.
class PinnedTest {
    static final Path PINS = Path.of("src/test/pinned");

    // every file the mod ships besides its code: textures, sounds, shaders, lang, tags, built-in configs, mixin lists
    @Test
    void resources() throws IOException {
        List<String> out = new ArrayList<>();
        Path res = Path.of("src/main/resources");
        try (Stream<Path> s = Files.walk(res)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                byte[] data = Files.readAllBytes(p);
                out.add(sha256(data) + "  " + res.relativize(p).toString().replace('\\', '/'));
            }
        }
        pin("resources", out);
    }

    // the recoil movements: the springs of every preset the jar ships and a few by hand, sampled finely past their
    // ends, and the config form of a curve "t:v t:v"
    @Test
    void curves() throws IOException {
        List<String> out = new ArrayList<>();
        java.util.Properties presets = new java.util.Properties();
        try (var r = Files.newBufferedReader(Path.of("src/main/resources/ntmgunfeel-recoil.properties"), StandardCharsets.UTF_8)) {
            presets.load(r);
        }
        java.util.Map<String, String> springs = new java.util.TreeMap<>();
        for (String k : presets.stringPropertyNames()) springs.put(k, presets.getProperty(k));
        springs.put("~bounce", "5,0.03,0.4,0.3,0.02");
        springs.put("~shake", "2,0.01,0.3,0,0,18");
        springs.put("~tiny", "1,0,0");
        for (var e : springs.entrySet()) {
            Spring sp = Spring.parse(e.getValue());
            if (sp == null) {
                out.add(e.getKey() + " not a spring: " + e.getValue());
                continue;
            }
            out.add(e.getKey() + " = " + sp + " length " + num(sp.length()));
            for (int i = -2; i <= 480; i++) {
                float t = i * 0.0025F;
                out.add(e.getKey() + " " + num(t) + " " + num(sp.at(t)));
            }
        }
        String[] srcs = {"0:0 0.1:5 0.3:-1 0.5:0", "0:0 0.2:1", "0:1  0.4:2 ", "0:0", "0:0 0:1", "0:0 0.2:x", "", "a b c"};
        for (String src : srcs) {
            Curve c = Curve.of(src);
            StringBuilder b = new StringBuilder("of '" + src + "':");
            if (c == null) b.append(" none");
            else for (int i = 0; i <= 20; i++) b.append(' ').append(num(c.at(i * 0.03F)));
            out.add(b.toString());
        }
        pin("curves", out);
    }

    // every setting's default as the code has it before any file is read, and as the mod's built-in file gives it
    @Test
    void configDefaults() throws IllegalAccessException {
        List<String> out = new ArrayList<>();
        List<Field> fields = new ArrayList<>(ConfigTest.settings());
        for (Field f : fields) out.add("field " + f.getName() + " = " + value(f.get(null)));
        for (String k : Config.keys()) out.add("key " + k + " = " + Config.codeValue(k));
        pin("config", out);
    }

    // which texture each bullet mark kind draws
    @Test
    void decalKinds() {
        List<String> out = new ArrayList<>();
        for (int k = 0; k < Decals.COUNT; k++)
            out.add(k + " " + Decals.file(k) + (Decals.isScratch(k) ? " scratch" : Decals.isScorch(k) ? " scorch" : " hole"));
        for (String h : Decals.HOLES) out.add("hole " + h + " -> " + Decals.hole(h));
        for (int i = 0; i < 16; i++) out.add("variant " + num(i / 16F) + " -> " + Decals.variant(i / 16.0));
        pin("decals", out);
    }

    // the colour sums of the ammo menu
    @Test
    void guiColours() {
        List<String> out = new ArrayList<>();
        int[] colours = {0x000000, 0xFFFFFF, 0xFF8C1A, 0x00FF7F, 0x123456, 0x80FFFFFF, 0xFF102030};
        float[] ts = {-0.5F, 0F, 0.1F, 0.25F, 0.5F, 0.75F, 0.999F, 1F, 1.5F};
        for (int c : colours) {
            for (float t : ts) {
                out.add(String.format("argb %08x %s = %08x", c, num(t), GuiShapes.argb(c, t)));
                out.add(String.format("alpha %08x %s = %08x", c, num(t), GuiShapes.alpha(c, t)));
                for (int d : colours) out.add(String.format("mix %08x %08x %s = %08x", c, d, num(t), GuiShapes.mix(c, d, t)));
            }
        }
        pin("gui", out);
    }

    // ------------------------------------------------------------------

    // a float as its exact bits and a readable value: "0.5/3f000000"
    static String num(float f) {
        return f + "/" + Integer.toHexString(Float.floatToRawIntBits(f));
    }

    static String value(Object v) {
        if (v instanceof Float f) return num(f);
        if (v instanceof float[] a) {
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < a.length; i++) b.append(i > 0 ? ", " : "").append(num(a[i]));
            return b.append(']').toString();
        }
        if (v instanceof Object[] a) return Arrays.toString(a);
        return String.valueOf(v);
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static void pin(String name, List<String> now) {
        Path file = PINS.resolve(name + ".txt");
        try {
            if (System.getenv("PIN_UPDATE") != null) {
                Files.createDirectories(PINS);
                Files.write(file, now, StandardCharsets.UTF_8);
                return;
            }
            if (!Files.exists(file)) fail("no pin " + file + ": write it with PIN_UPDATE=1 ./gradlew test");
            List<String> was = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (was.equals(now)) return;
            int changed = 0, first = -1;
            for (int i = 0; i < Math.max(was.size(), now.size()); i++) {
                String a = i < was.size() ? was.get(i) : null, b = i < now.size() ? now.get(i) : null;
                if (a != null && a.equals(b)) continue;
                changed++;
                if (first < 0) first = i;
            }
            String a = first < was.size() ? was.get(first) : "(none)", b = first < now.size() ? now.get(first) : "(none)";
            fail(file + ": " + changed + " lines differ, first at line " + (first + 1) + "\n  pinned: " + a + "\n  now:    " + b
                    + "\n(if the change is meant: PIN_UPDATE=1 ./gradlew test)");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
