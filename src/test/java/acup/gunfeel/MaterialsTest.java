package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import acup.gunfeel.Materials.Material;
import acup.gunfeel.Materials.Source;
import acup.gunfeel.Materials.Verdict;

// The material of every vanilla and NTM block, worked out by Materials from what a real server knows about them
// (src/test/blockfacts, written by ./gradlew runDumpBlocks -PnoAssets): known blocks must come out right, and the
// whole list is pinned, so a change to the steps or the rules shows block by block. build/materials-report.tsv
// lists every block with the step that decided and why, guesses first, for checking by hand.
class MaterialsTest {
    static final Path FACTS_DIR = Path.of("src/test/blockfacts");
    static final Path RULES = Path.of("src/main/resources/ntmgunfeel-bullets.txt");

    static List<BlockFacts> facts() throws IOException {
        try (Stream<Path> s = Files.list(FACTS_DIR)) {
            Path file = s.filter(p -> p.toString().endsWith(".tsv")).sorted().reduce((a, b) -> b).orElseThrow();
            return BlockFacts.parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        }
    }

    static Map<String, Verdict> classified() throws IOException {
        List<Materials.Rule> rules = Materials.rules(Files.readAllLines(RULES, StandardCharsets.UTF_8));
        Map<String, Verdict> out = new LinkedHashMap<>();
        for (BlockFacts f : facts()) out.put(f.id(), Materials.classify(f, rules));
        return out;
    }

    @Test
    void theDumpHasVanillaAndNtm() throws IOException {
        List<BlockFacts> all = facts();
        assertTrue(all.stream().filter(f -> f.namespace().equals("minecraft")).count() > 1000);
        assertTrue(all.stream().filter(f -> f.namespace().equals("hbm")).count() > 1000);
    }

    @Test
    void knownBlocks() throws IOException {
        Map<String, Verdict> v = classified();
        Map<String, Material> want = new LinkedHashMap<>();
        want.put("minecraft:stone", Material.STONE);
        want.put("minecraft:cobblestone", Material.STONE);
        want.put("minecraft:stone_bricks", Material.STONE);
        want.put("minecraft:bricks", Material.STONE);
        want.put("minecraft:deepslate", Material.STONE);
        want.put("minecraft:oak_planks", Material.WOOD);
        want.put("minecraft:oak_log", Material.WOOD);
        want.put("minecraft:oak_door", Material.WOOD);
        want.put("minecraft:oak_leaves", Material.NONE);
        want.put("minecraft:glass", Material.GLASS);
        want.put("minecraft:glass_pane", Material.GLASS);
        want.put("minecraft:dirt", Material.SOFT);
        want.put("minecraft:sand", Material.SOFT);
        want.put("minecraft:gravel", Material.SOFT);
        want.put("minecraft:white_wool", Material.SOFT);
        want.put("minecraft:iron_block", Material.METAL);
        want.put("minecraft:iron_door", Material.METAL);
        want.put("minecraft:white_concrete", Material.CONCRETE);
        want.put("minecraft:bedrock", Material.UNBREAKABLE);
        want.put("minecraft:short_grass", Material.NONE);
        want.put("hbm:block_steel", Material.METAL);
        want.put("hbm:block_lead", Material.METAL);
        want.put("hbm:asphalt", Material.CONCRETE);
        want.put("hbm:brick_concrete", Material.CONCRETE);
        want.put("hbm:brick_ducrete", Material.CONCRETE);
        // the words that misled the name step before it was ordered
        want.put("minecraft:iron_ore", Material.STONE);
        want.put("minecraft:sandstone", Material.STONE);
        want.put("minecraft:mossy_cobblestone", Material.STONE);
        want.put("minecraft:mud_bricks", Material.STONE);
        want.put("hbm:ore_lead", Material.STONE);
        want.put("hbm:logic_block", Material.MACHINE);
        want.put("hbm:deco_pipe", Material.METAL);
        want.put("hbm:cm_engine_desh", Material.METAL);
        // an infinite hardness is unbreakable too, like bedrock's -1
        want.put("hbm:seal_hatch", Material.UNBREAKABLE);
        want.put("hbm:statue_elb_f", Material.UNBREAKABLE);
        List<String> wrong = new ArrayList<>();
        for (var e : want.entrySet()) {
            Verdict got = v.get(e.getKey());
            assertNotNull(got, "not in the dump: " + e.getKey());
            if (got.material() != e.getValue()) wrong.add(e.getKey() + " is " + got.material() + " (" + got.source() + ": " + got.why() + "), not " + e.getValue());
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void ntmMachinesAreHollow() throws IOException {
        Map<String, Verdict> v = classified();
        int machines = 0;
        for (BlockFacts f : facts()) {
            if (!f.namespace().equals("hbm") || f.classes().stream().noneMatch(c -> c.startsWith("BlockMultiblock"))) continue;
            Verdict got = v.get(f.id());
            // a rule may say otherwise (the brick-built ones); a block without collision is never hit (the cargo
            // elevator's core: its platform is ElevatorMesh's)
            if (got.source() != Source.RULE && got.source() != Source.NO_COLLISION) assertEquals(Material.MACHINE, got.material(), f.id());
            machines++;
        }
        assertTrue(machines > 200, "only " + machines + " NTM multiblocks");
    }

    @Test
    void everyBuiltInMaterialLineNamesAMaterialAndMatches() throws IOException {
        List<BlockFacts> all = facts();
        for (String raw : Files.readAllLines(RULES, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (!line.startsWith("material ")) continue;
            String[] w = line.split("\\s+");
            assertTrue(w.length >= 3, "no material in: " + line);
            assertNotNull(Material.named(w[2]), "unknown material " + w[2] + " in: " + line);
            List<String> sel = List.of(w[1].split(","));
            for (String s : sel) {
                // every selector of a built-in line meets some block: a typo would silently do nothing
                assertTrue(all.stream().anyMatch(f -> Materials.matches(List.of(s), f)), "matches no block: " + s + " in: " + line);
            }
        }
    }

    // the whole list, pinned; and the report for reading
    @Test
    void everyBlockPinned() throws IOException {
        Map<String, Verdict> v = classified();
        List<String> pin = new ArrayList<>();
        for (var e : v.entrySet())
            pin.add(e.getKey() + " " + e.getValue().material() + " " + e.getValue().source() + " " + PinnedTest.num(e.getValue().strength()));
        PinnedTest.pin("materials", pin);
        writeReport(v);
    }

    static void writeReport(Map<String, Verdict> v) throws IOException {
        Map<Material, Integer> byMaterial = new EnumMap<>(Material.class);
        Map<String, Integer> bySource = new TreeMap<>();
        List<String> rows = new ArrayList<>();
        for (BlockFacts f : facts()) {
            Verdict x = v.get(f.id());
            byMaterial.merge(x.material(), 1, Integer::sum);
            bySource.merge(f.namespace() + " " + x.source(), 1, Integer::sum);
            rows.add(String.join("\t", x.source().guess() ? "guess" : "sure", f.id(), x.material().name().toLowerCase(),
                    BlockFacts.num(x.strength()), x.source().name().toLowerCase(), x.why(), f.sound(), BlockFacts.num(f.hardness()),
                    BlockFacts.num(f.blast()), f.classes().isEmpty() ? "-" : f.classes().get(0)));
        }
        rows.sort(null);
        List<String> out = new ArrayList<>();
        out.add("# materials of " + v.size() + " blocks: " + byMaterial);
        out.add("# decided by: " + bySource);
        out.add(String.join("\t", "how", "id", "material", "strength", "step", "why", "sound", "hardness", "blast", "class"));
        out.addAll(rows);
        Path report = Path.of("build/materials-report.tsv");
        Files.createDirectories(report.getParent());
        Files.write(report, out, StandardCharsets.UTF_8);
        System.out.println(out.get(0));
        System.out.println(out.get(1));
    }

    // ---------------------------------------------------------------- the formula

    @Test
    void penetrationFollowsNtmArmour() {
        Verdict wood = new Verdict(Material.WOOD, 1F, Source.TAG, "");
        // one block of wood: threshold 3 off, then 20 % resistance
        assertEquals((20F - 3F) * 0.8F, Materials.left(wood, 1.0, 20F, 0F, 0F), 1e-4F);
        // thinner is easier, piercing and negation help, a threshold above the power stops it
        assertTrue(Materials.left(wood, 0.5, 20F, 0F, 0F) > Materials.left(wood, 1.0, 20F, 0F, 0F));
        assertTrue(Materials.left(wood, 1.0, 20F, 2F, 0.2F) > Materials.left(wood, 1.0, 20F, 0F, 0F));
        assertTrue(Materials.left(wood, 1.0, 20F, 0F, -0.25F) < Materials.left(wood, 1.0, 20F, 0F, 0F));
        assertEquals(-1F, Materials.left(wood, 1.0, 2.5F, 0F, 0F));
        Verdict none = new Verdict(Material.NONE, 1F, Source.TAG, "");
        assertEquals(20F, Materials.left(none, 1.0, 20F, 0F, 0F));
        Verdict bedrock = new Verdict(Material.UNBREAKABLE, 1F, Source.UNBREAKABLE, "");
        assertEquals(-1F, Materials.left(bedrock, 0.01, 1000F, 1000F, 1F));
        // a machine counts two thin walls whatever the path
        Verdict machine = new Verdict(Material.MACHINE, 1F, Source.CLASS, "");
        assertEquals(Materials.left(machine, 0.1, 50F, 7F, 0.1F), Materials.left(machine, 3.0, 50F, 7F, 0.1F));
        assertFalse(Float.isNaN(Materials.left(wood, 0.0, 20F, 0F, 0F)));
    }

    @Test
    void strengthScalesWithBlastResistance() {
        assertEquals(1F, Materials.strength(Material.STONE, 6F), 1e-6F);
        assertEquals(4F, Materials.strength(Material.STONE, 216F), 1e-6F);
        assertEquals(0.5F, Materials.strength(Material.CONCRETE, 1.8F), 1e-6F);
        assertEquals(1F, Materials.strength(Material.WOOD, 3000F), 1e-6F);
        assertEquals(4F, Materials.strength(Material.METAL, Float.POSITIVE_INFINITY), 1e-6F);
    }
}
