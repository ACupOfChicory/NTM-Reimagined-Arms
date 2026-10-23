package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import acup.gunfeel.Materials.Verdict;

// What is glass, on the whole block dump: the systems that treat glass apart (breaking, hole drawing) agree with the
// block classification.
class GlassTest {
    static final Path BULLETS = Path.of("src/main/resources/ntmgunfeel-bullets.txt");
    static final Path TRACERS = Path.of("src/main/java/acup/gunfeel/Tracers.java");

    record Break(List<String> selectors, List<String> words) {
    }

    static List<Break> breaks() throws IOException {
        List<Break> out = new ArrayList<>();
        for (String line : Files.readAllLines(BULLETS, StandardCharsets.UTF_8)) {
            String[] w = line.trim().split("\\s+");
            if (w.length < 3 || !w[0].equals("break")) continue;
            out.add(new Break(List.of(w[1].split(",")), Arrays.asList(w).subList(2, w.length)));
        }
        return out;
    }

    // the last matching line wins, as in BulletRules
    static Break breakOf(List<Break> all, BlockFacts f) {
        for (int i = all.size() - 1; i >= 0; i--) if (Materials.matches(all.get(i).selectors(), f)) return all.get(i);
        return null;
    }

    static boolean taggedGlass(BlockFacts f) {
        return f.tags().contains("c:glass_blocks") || f.tags().contains("c:glass_panes");
    }

    @Test
    void taggedGlassBreaksAndIsGlass() throws IOException {
        List<Break> all = breaks();
        Map<String, Verdict> m = MaterialsTest.classified();
        int n = 0;
        for (BlockFacts f : MaterialsTest.facts()) {
            if (!taggedGlass(f)) continue;
            n++;
            Break b = breakOf(all, f);
            assertTrue(b != null && b.words().getFirst().equals("hp") && b.words().contains("pass"), f.id() + " does not break as glass: " + b);
            assertTrue(m.get(f.id()).material().glass(), f.id() + " is " + m.get(f.id()).material());
        }
        // vanilla: glass, tinted, 16 colours, as blocks and panes
        assertTrue(n >= 34, "only " + n + " tagged glass blocks");
    }

    // a line naming glass breaks only what the classification calls glass (NTM's laminate, wired glass...)
    @Test
    void glassBreakLinesHoldOnlyGlass() throws IOException {
        List<Break> all = breaks();
        Map<String, Verdict> m = MaterialsTest.classified();
        for (BlockFacts f : MaterialsTest.facts()) {
            Break b = breakOf(all, f);
            if (b == null || b.selectors().stream().noneMatch(s -> s.contains("glass"))) continue;
            assertTrue(m.get(f.id()).material().glass(), f.id() + " breaks as glass but is " + m.get(f.id()).material());
        }
    }

    // a copper grate's holes follow its pixels, they are not drawn as on glass
    @Test
    void copperGrateIsNotGlass() throws IOException {
        int n = 0;
        for (Map.Entry<String, Verdict> e : MaterialsTest.classified().entrySet()) {
            if (!e.getKey().contains("copper_grate")) continue;
            n++;
            assertFalse(e.getValue().material().glass(), e.getKey());
        }
        assertTrue(n >= 8, "only " + n + " copper grates");
    }

    // the holes ask the material, with no glass test of their own (tags, sound, the id)
    @Test
    void holesTakeGlassFromTheMaterial() throws IOException {
        String src = Files.readString(TRACERS);
        assertTrue(src.contains("return BulletRules.material(state).material().glass();"), "Tracers.glassLike does not ask the material");
        for (String own : List.of("SoundType.GLASS", "Tags.Blocks.GLASS", "contains(\"glass\")"))
            assertFalse(src.contains(own), "Tracers tests glass by itself: " + own);
    }
}
