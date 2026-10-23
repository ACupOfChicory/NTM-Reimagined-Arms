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
    // the review's materials first, then the bullet rules' material lines, which win (as in the game)
    static final List<Path> RULES = List.of(Path.of("src/main/resources/ntmgunfeel-materials.txt"),
            Path.of("src/main/resources/ntmgunfeel-bullets.txt"));

    static List<String> ruleLines() throws IOException {
        List<String> all = new ArrayList<>();
        for (Path p : RULES) all.addAll(Files.readAllLines(p, StandardCharsets.UTF_8));
        return all;
    }

    static List<BlockFacts> facts() throws IOException {
        try (Stream<Path> s = Files.list(FACTS_DIR)) {
            Path file = s.filter(p -> p.toString().endsWith(".tsv")).sorted().reduce((a, b) -> b).orElseThrow();
            return BlockFacts.parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        }
    }

    static Map<String, Verdict> classified() throws IOException {
        List<Materials.Rule> rules = Materials.rules(ruleLines());
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
        want.put("hbm:block_steel", Material.STEEL);
        want.put("hbm:block_lead", Material.METAL);
        want.put("hbm:asphalt", Material.CONCRETE);
        want.put("hbm:brick_concrete", Material.CONCRETE);
        want.put("hbm:brick_ducrete", Material.CONCRETE);
        // the words that misled the name step before it was ordered (some settled by the review since)
        want.put("minecraft:iron_ore", Material.STONE);
        want.put("minecraft:sandstone", Material.STONE);
        want.put("minecraft:mossy_cobblestone", Material.STONE);
        want.put("minecraft:mud_bricks", Material.STONE);
        want.put("hbm:ore_lead", Material.STONE);
        want.put("hbm:logic_block", Material.STEEL);
        want.put("hbm:deco_pipe", Material.STEEL);
        want.put("hbm:cm_engine_desh", Material.MACHINE);
        // an infinite hardness is unbreakable too, like bedrock's -1
        want.put("hbm:seal_hatch", Material.UNBREAKABLE);
        want.put("hbm:statue_elb_f", Material.UNBREAKABLE);
        // armoured glass, and tiles that only sound like glass
        want.put("hbm:reinforced_glass", Material.REINFORCED_GLASS);
        want.put("hbm:reinforced_glass_pane", Material.REINFORCED_GLASS);
        want.put("hbm:reinforced_laminate", Material.LAMINATE);
        want.put("hbm:reinforced_laminate_pane", Material.LAMINATE);
        want.put("hbm:glass_quartz", Material.GLASS);
        want.put("hbm:glass_uranium", Material.TOUGH_GLASS);
        // the author's: the telex is a wooden cabinet; vanilla's barrel and pumpkin are no NTM machines
        want.put("hbm:radio_telex", Material.WOOD);
        want.put("minecraft:barrel", Material.WOOD);
        want.put("minecraft:pumpkin", Material.WOOD);
        want.put("hbm:glass_lead", Material.TOUGH_GLASS);
        want.put("hbm:vinyl_tile_large", Material.WOOD);
        want.put("hbm:tile_lab_cracked", Material.STONE);
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
        for (String raw : ruleLines()) {
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

    // NTM rounds as power (gun damage x ammo damage), threshold negation, armour piercing
    private static final float[] UZI_FMJ = {3F * 0.8F, 2F, 0.1F}, REVOLVER_357 = {7.5F, 0F, 0F},
            LAG_FMJ = {25F * 0.8F, 2F, 0.1F}, MAS36 = {30F, 0F, 0F}, LILMAC = {30F, 0F, 0F},
            AMAT_AP = {30F * 1.25F, 17.5F, 0.15F}, SUBTLETY = {50F, 0F, 0F},
            SUBTLETY_DU = {50F * 1.5F, 21F, 0.25F}, SUBTLETY_SM = {50F * 2.5F, 30F, 0.35F};

    private static boolean through(Material m, double length, float[] round) {
        return Materials.left(new Verdict(m, 1F, Source.RULE, ""), length, round[0], round[1], round[2]) > 0F;
    }

    @Test
    void armouredGlass() {
        double pane = 2.0 / 16.0;
        // wired glass: the pane stops the weak pistols, the block every pistol and rifle but AP and .50
        assertFalse(through(Material.REINFORCED_GLASS, pane, UZI_FMJ));
        assertFalse(through(Material.REINFORCED_GLASS, pane, REVOLVER_357));
        assertTrue(through(Material.REINFORCED_GLASS, pane, LAG_FMJ));
        assertFalse(through(Material.REINFORCED_GLASS, 1.0, LAG_FMJ));
        assertFalse(through(Material.REINFORCED_GLASS, 1.0, MAS36));
        assertTrue(through(Material.REINFORCED_GLASS, 1.0, AMAT_AP));
        // laminate: the pane stops all but the strong rounds, the block all but the very strongest
        assertFalse(through(Material.LAMINATE, pane, LAG_FMJ));
        assertFalse(through(Material.LAMINATE, pane, MAS36));
        assertFalse(through(Material.LAMINATE, pane, LILMAC));
        assertTrue(through(Material.LAMINATE, pane, AMAT_AP));
        assertTrue(through(Material.LAMINATE, pane, SUBTLETY));
        assertFalse(through(Material.LAMINATE, 1.0, SUBTLETY));
        assertFalse(through(Material.LAMINATE, 1.0, SUBTLETY_DU));
        assertTrue(through(Material.LAMINATE, 1.0, SUBTLETY_SM));
        // plain glass stops nothing
        assertTrue(through(Material.GLASS, 1.0, UZI_FMJ));
        // the special glass (lead, uranium...): stronger than plain, far weaker than wired
        assertTrue(through(Material.TOUGH_GLASS, pane, UZI_FMJ));
        assertFalse(through(Material.TOUGH_GLASS, 1.0, UZI_FMJ));
        assertTrue(through(Material.TOUGH_GLASS, 1.0, LAG_FMJ));
        assertTrue(through(Material.TOUGH_GLASS, pane, REVOLVER_357));
    }

    // "$stone" in a bullet rule picks blocks by material: a typo would match nothing; a material line can't use it
    // (the material is what that line decides)
    @Test
    void materialSelectorsNameMaterials() throws IOException {
        int seen = 0;
        for (Path f : RULES) {
            for (String raw : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] w = line.split("\\s+");
                if (w.length < 2) continue;
                for (String sel : w[1].split(",")) {
                    if (!sel.startsWith("$")) continue;
                    seen++;
                    assertFalse(w[0].equals("material"), "a material line picks by material: " + line);
                    assertNotNull(Material.named(sel.substring(1)), "no material " + sel + " in: " + line);
                }
            }
        }
        assertTrue(seen > 0, "no $material selectors in the built-in rules");
    }

    @Test
    void strengthScalesWithBlastResistance() {
        assertEquals(1F, Materials.strength(Material.STONE, 6F), 1e-6F);
        assertEquals(4F, Materials.strength(Material.STONE, 216F), 1e-6F);
        assertEquals(0.5F, Materials.strength(Material.CONCRETE, 1.8F), 1e-6F);
        assertEquals(1F, Materials.strength(Material.WOOD, 3000F), 1e-6F);
        assertEquals(4F, Materials.strength(Material.METAL, Float.POSITIVE_INFINITY), 1e-6F);
        assertEquals(1F, Materials.strength(Material.STEEL, 6F), 1e-6F);
    }
}
