package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

// The bullet mark textures and the decal rules that pick them: a missing or misnamed file shows in game as the
// purple-black "missing texture" square, a typo in a rule silently falls back to the default style.
class DecalsTest {
    static final Path EFFECT = Path.of("src/main/resources/assets/ntmgunfeel/textures/effect");
    static final Path RULES = Path.of("src/main/resources/ntmgunfeel-bullets.txt");

    @Test
    void everyKindHasItsOwnFile() {
        Set<String> seen = new HashSet<>();
        for (int k = 0; k < Decals.COUNT; k++) assertTrue(seen.add(Decals.file(k)), "two kinds share " + Decals.file(k));
        assertEquals(Decals.HOLES.size() * Decals.VARIANTS + Decals.SCRATCHES.size() + Decals.SCORCHES.size(), Decals.COUNT);
    }

    @Test
    void oldHolesKeepTheirNames() {
        for (int v = 0; v < Decals.VARIANTS; v++) assertEquals("bullet_hole_" + v, Decals.file(Decals.hole("old", v / (double) Decals.VARIANTS)));
    }

    @Test
    void holesPickTheirStyleAndEveryVariant() {
        for (String s : Decals.HOLES) {
            Set<Integer> kinds = new HashSet<>();
            for (int i = 0; i < 1000; i++) {
                int k = Decals.hole(s, i / 1000.0);
                assertFalse(Decals.isScratch(k) || Decals.isScorch(k), s);
                assertTrue(Decals.file(k).contains(s.equals("old") ? "bullet_hole_" : "hole_" + s + "_"), Decals.file(k));
                kinds.add(k);
            }
            assertEquals(Decals.VARIANTS, kinds.size(), s + " does not reach every variant");
        }
    }

    @Test
    void randomOutsideItsRangeStaysInTheStyle() {
        for (double r : new double[] {-0.5, 1.0, 1.5, Double.NaN}) {
            int k = Decals.hole("spall", r);
            assertTrue(Decals.file(k).startsWith("hole_spall_"), r + " -> " + Decals.file(k));
        }
    }

    @Test
    void scratchesAndScorchesAreTheirOwnKinds() {
        for (String s : Decals.SCRATCHES) {
            int k = Decals.scratch(s);
            assertTrue(Decals.isScratch(k) && !Decals.isScorch(k));
            assertEquals("scratch_" + s, Decals.file(k));
        }
        for (String s : Decals.SCORCHES) {
            int k = Decals.scorch(s);
            assertTrue(Decals.isScorch(k) && !Decals.isScratch(k));
            assertEquals("scorch_" + s, Decals.file(k));
        }
    }

    @Test
    void unknownStylesFallBackToTheDefault() {
        assertEquals(Decals.hole(Decals.HOLE, 0.3), Decals.hole("punch", 0.3));
        assertEquals(Decals.hole(Decals.HOLE, 0.3), Decals.hole(null, 0.3));
        assertEquals(Decals.scratch(Decals.SCRATCH), Decals.scratch("nope"));
        assertEquals(Decals.scorch(Decals.SCORCH), Decals.scorch(""));
        assertEquals(Decals.scorch("crater"), Decals.scorch("CRATER"));
    }

    @Test
    void laterRulesWinStyleByStyle() {
        assertEquals(Decals.Set.DEFAULT, Decals.Set.of(List.of()));
        Decals.Set s = Decals.Set.of(List.of(
                Map.of("hole", "spall", "scratch", "lines", "scorch", "crater"),
                Map.of("hole", "shatter"),
                Map.of("scratch", "typo", "scorch", "blot")));
        assertEquals(new Decals.Set("shatter", "lines", "blot"), s);
    }

    @Test
    void everyTextureIsThereBlackAndSquare() throws IOException {
        for (String f : Decals.files()) {
            Path p = EFFECT.resolve(f + ".png");
            assertTrue(Files.exists(p), "missing " + p);
            BufferedImage img = ImageIO.read(p.toFile());
            assertNotNull(img, p + " is not a PNG");
            assertEquals(img.getWidth(), img.getHeight(), p + " is not square");
            assertTrue(Integer.bitCount(img.getWidth()) == 1, p + " is not a power of two");
            assertTrue(img.getColorModel().hasAlpha(), p + " has no alpha");
            int seen = 0;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int argb = img.getRGB(x, y);
                    if ((argb >>> 24) == 0) continue;
                    seen++;
                    // near black and grey (the old holes go up to 48, tinted by 8): one texture suits every block
                    int r = argb >> 16 & 255, g = argb >> 8 & 255, b = argb & 255;
                    int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
                    assertTrue(max <= 64 && max - min <= 16, p + " is light or coloured at " + x + "," + y + ": #" + Integer.toHexString(argb & 0xFFFFFF));
                }
            }
            assertTrue(seen > 0, p + " is empty");
        }
    }

    @Test
    void noStrayMarkTextures() throws IOException {
        Set<String> known = new HashSet<>(Decals.files());
        try (Stream<Path> s = Files.list(EFFECT)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (!n.endsWith(".png")) continue;
                String base = n.substring(0, n.length() - 4);
                if (base.startsWith("hole_") || base.startsWith("bullet_hole_") || base.startsWith("scratch") || base.startsWith("scorch"))
                    assertTrue(known.contains(base), n + " is not used by any decal style");
            }
        }
    }

    @Test
    void builtInDecalRulesNameKnownStyles() throws IOException {
        int rules = 0;
        for (String raw : Files.readAllLines(RULES)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] w = line.split("\\s+");
            if (!w[0].equals("decal")) continue;
            rules++;
            assertTrue(w.length > 2 && !w[1].contains("="), "decal rule without blocks: " + line);
            Map<String, String> opts = new HashMap<>();
            List<String> words = new ArrayList<>();
            for (int i = 2; i < w.length; i++) {
                int eq = w[i].indexOf('=');
                if (eq > 0) opts.put(w[i].substring(0, eq), w[i].substring(eq + 1));
                else words.add(w[i]);
            }
            assertTrue(words.isEmpty(), "unknown words " + words + " in: " + line);
            for (var e : opts.entrySet()) {
                List<String> styles = switch (e.getKey()) {
                    case "hole" -> Decals.HOLES;
                    case "scratch" -> Decals.SCRATCHES;
                    case "scorch" -> Decals.SCORCHES;
                    default -> null;
                };
                assertNotNull(styles, "unknown option " + e.getKey() + " in: " + line);
                assertNotNull(Decals.known(styles, e.getValue()), "unknown " + e.getKey() + " style " + e.getValue() + " in: " + line);
            }
        }
        assertTrue(rules > 0, "no decal rules in " + RULES);
    }

    @Test
    void everyStyleIsUsedByABuiltInRule() throws IOException {
        String text = Files.readString(RULES);
        for (String s : Decals.HOLES) assertTrue(text.contains("hole=" + s), "no rule uses hole " + s);
        for (String s : Decals.SCRATCHES) assertTrue(text.contains("scratch=" + s), "no rule uses scratch " + s);
        for (String s : Decals.SCORCHES) assertTrue(text.contains("scorch=" + s), "no rule uses scorch " + s);
    }
}
