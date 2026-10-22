package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

// What a bullet meets in a block, for penetration: the block's material and how strong it is for that material.
// Worked out once per block state from its BlockFacts (the game caches the verdict), in this order, the first that
// tells wins:
//   1. a "material <blocks> <name>" line in the bullet rules (the last that matches)
//   2. unbreakable (hardness below 0), no collision (flown through)
//   3. tags: leaves, glass, logs and planks, wool, shovel blocks, concrete, metal storage blocks
//   4. the block's class: NTM machines, pipes, cables, turrets (a hollow steel casing)
//   5. a telling sound: metal, glass, wood, soft ground
//   6. a word in the id: concrete, steel, glass, brick...
//   7. another mod's block with a block entity: a machine
//   8. the stone sound (most blocks that set none have it, NTM's included), else hard or soft by the tool it needs
// The strength within the material comes from the blast resistance: NTM's reinforced blocks have far more than the
// plain ones of the same material. No Minecraft here: the tests run it on a real server's dump (src/test/blockfacts).
public final class Materials {
    private Materials() {
    }

    // dt: NTM's damage threshold per block of thickness, dr: its damage resistance per block (both as NTM's armour);
    // blast: the material's usual blast resistance, against which a block's own scales its strength (0 = never);
    // thick: how the thickness counts, as length^thick (1 = in proportion)
    public enum Material {
        NONE(0F, 0F, 0F),
        GLASS(0.5F, 0.1F, 0F),
        // armoured glass is layered: a thin sheet holds far more than its share of a thick block (thickness ^ 0.5),
        // so a pane (1/8 block) counts as 0.35 of a block. Wired glass: the pane stops the weak pistols, the block
        // all pistols. Laminate: the pane stops all but the strong rounds (.50, rifle AP), the block all but the
        // very strongest (.50 saboted)
        REINFORCED_GLASS(30F, 0.6F, 0F, 0.5F),
        LAMINATE(100F, 0.9F, 0F, 0.5F),
        WOOD(3F, 0.2F, 0F),
        SOFT(12F, 0.6F, 0F),
        STONE(30F, 0.8F, 6F),
        CONCRETE(50F, 0.85F, 84F),
        // soft and mild metals: lead, copper, gold, aluminium, vanilla iron (the hit sounds call them "metal")
        METAL(60F, 0.85F, 6F),
        // steel and the hard alloys: tungsten, titanium, desh, CMB, netherite (the hit sounds call them "steel");
        // only "material" rule lines give it (per block, from the review), the steps below never do
        STEEL(150F, 0.95F, 6F),
        // a machine is a hollow steel casing: two thin walls, whatever its collision box
        MACHINE(150F, 0.95F, 6F),
        UNBREAKABLE(Float.POSITIVE_INFINITY, 1F, 0F);

        public final float dt, dr, blast, thick;

        Material(float dt, float dr, float blast) {
            this(dt, dr, blast, 1F);
        }

        Material(float dt, float dr, float blast, float thick) {
            this.dt = dt;
            this.dr = dr;
            this.blast = blast;
            this.thick = thick;
        }

        public static Material named(String name) {
            try {
                return valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    // which step decided: RULE, UNBREAKABLE and NO_COLLISION are certain, TAG, CLASS and SOUND sure, NAME likely,
    // OTHER_MACHINE, STONE_SOUND and TOOL guesses (the report lists those for checking)
    public enum Source {
        RULE, UNBREAKABLE, NO_COLLISION, TAG, CLASS, SOUND, NAME, OTHER_MACHINE, STONE_SOUND, TOOL;

        public boolean guess() {
            return this == OTHER_MACHINE || this == STONE_SOUND || this == TOOL;
        }
    }

    public record Verdict(Material material, float strength, Source source, String why) {
    }

    // ---------------------------------------------------------------- rules

    public record Rule(List<String> selectors, Material material) {
    }

    // the "material" lines of the bullet rules, in their order (the format of BulletRules: a " # " starts a comment)
    public static List<Rule> rules(List<String> lines) {
        List<Rule> out = new ArrayList<>();
        Pattern comment = Pattern.compile("\\s#(\\s|$)");
        for (String raw : lines) {
            String line = raw.strip();
            var hash = comment.matcher(line);
            if (hash.find()) line = line.substring(0, hash.start()).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] w = line.split("\\s+");
            if (!w[0].equalsIgnoreCase("material") || w.length < 3) continue;
            Material m = Material.named(w[2]);
            if (m == null) continue;
            List<String> sel = new ArrayList<>();
            for (String s : w[1].split(",")) if (!s.isEmpty()) sel.add(s);
            out.add(new Rule(List.copyOf(sel), m));
        }
        return out;
    }

    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    // the bullet rules' block selectors, on facts: id, ~regex on the id, #tag, @sound, %mod (its machines and partial
    // blocks), * (any)
    public static boolean matches(List<String> sel, BlockFacts f) {
        for (String s : sel) {
            if (s.equals("*")) return true;
            if (s.startsWith("#")) {
                if (f.tags().contains(withNamespace(s.substring(1)))) return true;
            } else if (s.startsWith("@")) {
                String want = s.substring(1).toLowerCase(Locale.ROOT);
                for (String name : f.sound().split("/")) if (name.equals(want)) return true;
            } else if (s.startsWith("%")) {
                if (f.namespace().equals(s.substring(1)) && f.machineOrPartial()) return true;
            } else if (s.startsWith("~")) {
                Pattern p = PATTERNS.computeIfAbsent(s, k -> {
                    try {
                        return Pattern.compile(k.substring(1));
                    } catch (RuntimeException e) {
                        return Pattern.compile("(?!)");
                    }
                });
                if (p.matcher(f.id()).matches()) return true;
            } else if (withNamespace(s).equals(f.id())) return true;
        }
        return false;
    }

    private static String withNamespace(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    // ---------------------------------------------------------------- the steps

    private static final List<String> METALS = List.of("iron", "gold", "copper", "netherite", "steel", "lead", "titanium",
            "tungsten", "aluminum", "aluminium", "beryllium", "cobalt", "uranium", "thorium", "plutonium", "schrabidium",
            "neodymium", "zirconium", "bismuth", "tantalium", "niobium", "osmiridium", "desh", "starmetal", "dineutronium",
            "ferrouranium", "red_copper", "advanced_alloy", "combine_steel", "magnetized_tungsten", "saturnite", "cadmium",
            "technetium", "arsenic", "bronze", "polonium", "lithium", "sodium", "boron", "solinium", "euphemium");

    private static final List<String> MACHINE_CLASS_WORDS = List.of("Multiblock", "Machine", "RBMK", "Turret", "Duct",
            "Pipe", "Cable", "Pylon", "Reactor", "Generator", "Battery", "Conveyor", "Crane", "Pump", "Tank", "Barrel");

    private static final List<String> METAL_SOUNDS = List.of("metal", "anvil", "chain", "netherite_block", "lantern",
            "heavy_core", "vault", "trial_spawner", "iron");
    private static final List<String> WOOD_SOUNDS = List.of("wood", "nether_wood", "bamboo_wood", "cherry_wood", "bamboo",
            "scaffolding", "ladder", "hanging_sign", "nether_wood_hanging_sign", "bamboo_wood_hanging_sign",
            "cherry_wood_hanging_sign", "chiseled_bookshelf", "stem", "pale_hanging_moss");
    private static final List<String> SOFT_SOUNDS = List.of("gravel", "grass", "rooted_dirt", "mud", "sand", "soul_sand",
            "soul_soil", "moss", "moss_carpet", "packed_mud", "snow", "powder_snow", "wool", "slime_block", "honey_block",
            "wet_grass", "sponge", "wet_sponge", "azalea", "flowering_azalea", "fungus", "nether_sprouts", "roots",
            "crop", "hard_crop", "sweet_berry_bush", "cave_vines", "vine", "lily_pad", "small_dripleaf", "big_dripleaf",
            "spore_blossom", "hanging_roots", "glow_lichen", "sculk", "sculk_vein", "frogspawn", "leaf_litter", "dried_ghast");
    private static final List<String> STONE_SOUNDS = List.of("stone", "deepslate", "deepslate_bricks", "deepslate_tiles",
            "polished_deepslate", "tuff", "tuff_bricks", "polished_tuff", "calcite", "basalt", "netherrack", "nether_bricks",
            "nether_ore", "nether_gold_ore", "dripstone_block", "pointed_dripstone", "bone_block", "mud_bricks",
            "amethyst", "amethyst_cluster", "ancient_debris", "lodestone", "gilded_blackstone", "decorated_pot",
            "decorated_pot_cracked", "resin", "resin_bricks", "coral_block", "shroomlight", "nylium", "wart_block",
            "froglight", "sculk_catalyst", "sculk_sensor", "sculk_shrieker", "polished_tuff");

    // in this order: stone words before metal ones (an iron ore is stone) and before soft ones (sandstone, mossy
    // cobblestone, mud bricks); short words only as whole parts of the id (log is not logic_block)
    private static final List<Map.Entry<Pattern, Material>> NAMES = List.of(
            Map.entry(Pattern.compile("concrete|ducrete|cement|asphalt|cmb_brick|ferrocon"), Material.CONCRETE),
            Map.entry(Pattern.compile("glass|window"), Material.GLASS),
            Map.entry(Pattern.compile("(^|_)ores?(_|$)|^cluster_|brick|stone|rock|basalt|slate|gneiss|limestone|marble|tiles?(_|$)|meteor"), Material.STONE),
            Map.entry(Pattern.compile("steel|iron|lead|tungsten|titanium|alloy|metal|copper|alumin|plate|beam|rebar|grate|scaffold|pipe"
                    + "|^cm_|rbmk|computer|(^|_)crt(_|$)|toaster|cabinet|cauldron"), Material.METAL),
            Map.entry(Pattern.compile("(^|_)(planks?|wood|logs?)(_|$)|rubber|polymer|bakelite|plastic"), Material.WOOD),
            Map.entry(Pattern.compile("(^|_)(sand|dirt|gravel|ash|snow|clay|mud|soil|earth|grass|moss|wool|cloth)(_|$)"), Material.SOFT));

    public static Verdict classify(BlockFacts f, List<Rule> rules) {
        for (int i = rules.size() - 1; i >= 0; i--) {
            Rule r = rules.get(i);
            if (matches(r.selectors(), f)) return verdict(f, r.material(), Source.RULE, String.join(",", r.selectors()));
        }
        // bedrock has -1; NTM's sealed hatches and statues have an infinite hardness instead
        if (f.hardness() < 0F || Float.isInfinite(f.hardness()))
            return verdict(f, Material.UNBREAKABLE, Source.UNBREAKABLE, "hardness " + BlockFacts.num(f.hardness()));
        if (f.collisionVolume() <= 0) return verdict(f, Material.NONE, Source.NO_COLLISION, "no collision");

        for (String t : f.tags()) {
            Material m = tagMaterial(t);
            if (m != null) return verdict(f, m, Source.TAG, t);
        }

        for (String c : f.classes()) {
            for (String w : MACHINE_CLASS_WORDS) if (c.contains(w)) return verdict(f, Material.MACHINE, Source.CLASS, c);
        }

        for (String name : f.sound().split("/")) {
            if (METAL_SOUNDS.contains(name) || name.startsWith("copper")) return verdict(f, Material.METAL, Source.SOUND, name);
            if (name.equals("glass")) return verdict(f, Material.GLASS, Source.SOUND, name);
            if (WOOD_SOUNDS.contains(name)) return verdict(f, Material.WOOD, Source.SOUND, name);
            if (SOFT_SOUNDS.contains(name)) return verdict(f, Material.SOFT, Source.SOUND, name);
        }

        String path = f.path();
        for (var e : NAMES) {
            var m = e.getKey().matcher(path);
            if (m.find()) return verdict(f, e.getValue(), Source.NAME, m.group());
        }

        if (f.blockEntity() && !f.namespace().equals("minecraft")) return verdict(f, Material.MACHINE, Source.OTHER_MACHINE, "block entity");

        for (String name : f.sound().split("/")) {
            if (STONE_SOUNDS.contains(name)) return verdict(f, Material.STONE, Source.STONE_SOUND, name);
        }
        return f.toolRequired() || f.hardness() >= 1.5F
                ? verdict(f, Material.STONE, Source.TOOL, "hardness " + BlockFacts.num(f.hardness()))
                : verdict(f, Material.SOFT, Source.TOOL, "hardness " + BlockFacts.num(f.hardness()));
    }

    private static Material tagMaterial(String t) {
        switch (t) {
            case "minecraft:leaves":
                return Material.NONE;
            case "c:glass_blocks", "c:glass_panes", "minecraft:impermeable":
                return Material.GLASS;
            case "minecraft:logs", "minecraft:planks", "minecraft:bamboo_blocks":
                return Material.WOOD;
            case "minecraft:wool", "minecraft:wool_carpets", "minecraft:mineable/shovel":
                return Material.SOFT;
            case "c:concretes":
                return Material.CONCRETE;
            default:
                break;
        }
        if (t.startsWith("minecraft:wooden_")) return Material.WOOD;
        if (t.startsWith("c:storage_blocks/") && METALS.contains(t.substring("c:storage_blocks/".length()))) return Material.METAL;
        return null;
    }

    private static Verdict verdict(BlockFacts f, Material m, Source s, String why) {
        return new Verdict(m, strength(m, f.blast()), s, why);
    }

    // how much stronger than the usual of its material: the square root of its blast resistance over the usual one,
    // from half to four times
    static float strength(Material m, float blast) {
        if (m.blast <= 0F) return 1F;
        if (Float.isNaN(blast) || blast <= 0F) return 0.5F;
        if (Float.isInfinite(blast)) return 4F;
        return (float) Math.max(0.5, Math.min(4.0, Math.sqrt(blast / m.blast)));
    }

    // ---------------------------------------------------------------- penetration

    // a machine's casing: two walls of a sixteenth of a block each, whatever the path through its box
    public static final double MACHINE_WALLS = 2.0 / 16.0;

    // What is left of a bullet's power after `length` blocks of this material, as NTM's armour takes damage: the
    // threshold (dt x thickness x strength, less the bullet's threshold negation) comes off, then the resistance (dr,
    // less the bullet's armour piercing, compounded over the thickness) takes its share. -1 when it stops in it.
    public static float left(Verdict v, double length, float power, float negation, float piercing) {
        Material m = v.material();
        if (m == Material.NONE) return power;
        if (m == Material.UNBREAKABLE || !(power > 0F)) return -1F;
        double len = m == Material.MACHINE ? MACHINE_WALLS
                : m.thick == 1F ? Math.max(0.0, length) : Math.pow(Math.max(0.0, length), m.thick);
        double dt = Math.max(0.0, m.dt * v.strength() * len - negation);
        if (dt >= power) return -1F;
        double dr = Math.min(1.0, m.dr * Math.max(0.0, Math.min(2.0, 1.0 - piercing)));
        double out = (power - dt) * Math.pow(1.0 - dr, len);
        return out > 0.0 ? (float) out : -1F;
    }
}
