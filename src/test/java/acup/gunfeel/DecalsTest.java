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
        assertEquals(Decals.HOLES.size() + Decals.SCRATCHES.size() + Decals.SCORCHES.size(), Decals.COUNT);
    }

    @Test
    void holesPickTheirStyle() {
        for (String s : Decals.HOLES) {
            int k = Decals.hole(s);
            assertFalse(Decals.isScratch(k) || Decals.isScorch(k), s);
            assertEquals("hole_" + s, Decals.file(k));
        }
    }

    @Test
    void randomPicksEveryVariantAndStaysInRange() {
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 10000; i++) seen.add(Decals.variant(i / 10000.0));
        assertEquals(Decals.VARIANTS, seen.size(), "not every variant is reached");
        for (double r : new double[] {-0.5, 1.0, 1.5, Double.NaN}) {
            int v = Decals.variant(r);
            assertTrue(v >= 0 && v < Decals.VARIANTS, r + " -> " + v);
        }
    }

    @Test
    void aVariantStaysInsideItsCell() {
        double cellSize = 1.0 / Decals.GRID;
        Set<String> cells = new HashSet<>();
        for (int v = 0; v < Decals.VARIANTS; v++) {
            float[] lo = Decals.cell(v, 0F, 0F), hi = Decals.cell(v, 1F, 1F), mid = Decals.cell(v, 0.5F, 0.5F);
            int cx = (int) (mid[0] / cellSize), cy = (int) (mid[1] / cellSize);
            assertTrue(cells.add(cx + "," + cy), "two variants share a cell: " + v);
            for (float[] p : new float[][] {lo, hi, Decals.cell(v, -0.2F, 1.3F)}) {
                assertEquals(cx, (int) (p[0] / cellSize), "variant " + v + " leaves its cell in u");
                assertEquals(cy, (int) (p[1] / cellSize), "variant " + v + " leaves its cell in v");
            }
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
        assertEquals(Decals.hole(Decals.HOLE), Decals.hole("punch"));
        assertEquals(Decals.hole(Decals.HOLE), Decals.hole(null));
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

    // a grid of square cells, grey (how much of the block's colour shows) with alpha; every cell drawn on
    @Test
    void everyTextureIsAGridOfGreyCells() throws IOException {
        for (String f : Decals.files()) {
            Path p = EFFECT.resolve(f + ".png");
            assertTrue(Files.exists(p), "missing " + p);
            BufferedImage img = ImageIO.read(p.toFile());
            assertNotNull(img, p + " is not a PNG");
            assertEquals(img.getWidth(), img.getHeight(), p + " is not square");
            assertTrue(Integer.bitCount(img.getWidth()) == 1 && img.getWidth() >= Decals.GRID * 16, p + " is not a power of two of at least 16 px a cell");
            assertTrue(img.getColorModel().hasAlpha(), p + " has no alpha");
            int cell = img.getWidth() / Decals.GRID;
            for (int c = 0; c < Decals.VARIANTS; c++) {
                int seen = 0;
                for (int y = c / Decals.GRID * cell; y < (c / Decals.GRID + 1) * cell; y++) {
                    for (int x = c % Decals.GRID * cell; x < (c % Decals.GRID + 1) * cell; x++) {
                        int argb = img.getRGB(x, y);
                        if ((argb >>> 24) == 0) continue;
                        seen++;
                        int r = argb >> 16 & 255, g = argb >> 8 & 255, b = argb & 255;
                        assertTrue(r == g && g == b, p + " is coloured at " + x + "," + y + ": #" + Integer.toHexString(argb & 0xFFFFFF));
                    }
                }
                assertTrue(seen > 0, p + ": cell " + c + " is empty");
            }
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
                if (base.startsWith("hole_") || base.startsWith("bullet_hole") || base.startsWith("scratch") || base.startsWith("scorch"))
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
    }

    @Test
    void everyStyleIsUsedByAMaterialOrABuiltInRule() throws IOException {
        String text = Files.readString(RULES);
        Set<String> holes = new HashSet<>(), scratches = new HashSet<>(), scorches = new HashSet<>();
        for (Materials.Material m : Materials.Material.values()) {
            Decals.Set d = Decals.Set.of(m);
            holes.add(d.hole());
            scratches.add(d.scratch());
            scorches.add(d.scorch());
        }
        for (String s : Decals.HOLES) assertTrue(holes.contains(s) || text.contains("hole=" + s), "nothing uses hole " + s);
        for (String s : Decals.SCRATCHES) assertTrue(scratches.contains(s) || text.contains("scratch=" + s), "nothing uses scratch " + s);
        for (String s : Decals.SCORCHES) assertTrue(scorches.contains(s) || text.contains("scorch=" + s), "nothing uses scorch " + s);
    }

    @Test
    void marksFollowTheMaterial() {
        assertEquals(new Decals.Set("spall", "gouge", "crater"), Decals.Set.of(Materials.Material.CONCRETE));
        assertEquals("shatter", Decals.Set.of(Materials.Material.LAMINATE).hole());
        // NTM's floor tiles sound like glass: the vinyl ones are wood (old holes), the lab ones stone (spall)
        assertEquals("old", Decals.Set.of(Materials.Material.WOOD).hole());
        assertEquals("spall", Decals.Set.of(Materials.Material.STONE).hole());
        // a decal line changes one style and keeps the material's others
        assertEquals(new Decals.Set("shatter", "gouge", "crater"),
                Decals.Set.of(Decals.Set.of(Materials.Material.STONE), List.of(Map.of("hole", "shatter"))));
    }
}
