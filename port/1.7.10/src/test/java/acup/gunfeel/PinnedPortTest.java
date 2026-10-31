package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

// The recoil numbers on 1.7.10 against 26.2's pins (the root's src/test/pinned, written by PinnedTest on Java 25):
// the same lines from the same inputs, compared by the bits of every float. Float.toString differs between Java 8
// and Java 19+ in the last digits it prints, so the readable half of "0.5/3f000000" is not compared, only the bits.
class PinnedPortTest {
    static final Path REPO = Paths.get(System.getProperty("ntmgunfeel.repo", "../.."));

    // 26.2 PinnedTest.num
    static String num(float f) {
        return f + "/" + Integer.toHexString(Float.floatToRawIntBits(f));
    }

    static String value(Object v) {
        if (v instanceof Float) return num((Float) v);
        if (v instanceof float[]) {
            float[] a = (float[]) v;
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < a.length; i++) b.append(i > 0 ? ", " : "").append(num(a[i]));
            return b.append(']').toString();
        }
        if (v instanceof Object[]) return java.util.Arrays.toString((Object[]) v);
        return String.valueOf(v);
    }

    // 26.2 PinnedTest.curves, line for line
    @Test
    void curves() throws IOException {
        List<String> out = new ArrayList<String>();
        Properties presets = new Properties();
        try (Reader r = Files.newBufferedReader(REPO.resolve("src/main/resources/ntmgunfeel-recoil.properties"), StandardCharsets.UTF_8)) {
            presets.load(r);
        }
        Map<String, String> springs = new TreeMap<String, String>();
        for (String k : presets.stringPropertyNames()) springs.put(k, presets.getProperty(k));
        springs.put("~bounce", "5,0.03,0.4,0.3,0.02");
        springs.put("~shake", "2,0.01,0.3,0,0,18");
        springs.put("~tiny", "1,0,0");
        for (Map.Entry<String, String> e : springs.entrySet()) {
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
        compare("curves", out);
    }

    // 26.2 PinnedTest.configDefaults: the copied Config has every setting and default of 26.2's
    @Test
    void configDefaults() throws IllegalAccessException {
        List<String> out = new ArrayList<String>();
        for (Field f : ConfigTest.settings()) out.add("field " + f.getName() + " = " + value(f.get(null)));
        for (String k : Config.keys()) out.add("key " + k + " = " + Config.codeValue(k));
        compare("config", out);
    }

    // a float "readable/bits" token as its bits only
    static String bits(String line) {
        return line.replaceAll("[-0-9.EINaify]+/([0-9a-f]+)", "/$1");
    }

    static void compare(String name, List<String> now) {
        Path file = REPO.resolve("src/test/pinned/" + name + ".txt");
        List<String> was;
        try {
            was = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("no 26.2 pin " + file, e);
        }
        assertEquals(was.size(), now.size(), file + ": line count");
        int changed = 0, first = -1;
        for (int i = 0; i < was.size(); i++) {
            if (bits(was.get(i)).equals(bits(now.get(i)))) continue;
            changed++;
            if (first < 0) first = i;
        }
        if (changed > 0) fail(file + ": " + changed + " lines differ from 26.2, first at line " + (first + 1) + "\n  26.2: " + was.get(first)
                + "\n  1.7.10: " + now.get(first) + "\n  (java " + System.getProperty("java.version") + ")");
    }
}
