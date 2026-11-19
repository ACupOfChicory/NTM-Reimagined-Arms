package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

// BlockSelectors against BulletRules.blockMatches as it was (b11fa9b), its lines word for word with the game's made a
// fake block's: its id, tags and sound asked live (the sound by its object, the game's SoundType constants by field
// name, two names for one as the game has), the material and machine-or-partial noted when asked. Random blocks and
// selector lists of every kind, well and badly written: the same answer, the same questions asked in the same order;
// the facts as BlockFactsOf makes them (the sound's names joined by "/", a mod's own "?").
class BlockSelectorsTest {
    // the game's SoundType constants: some under two names, as STONE and the like may be
    static final Object STONE = new Object(), METAL = new Object(), WOOD = new Object(), GLASS = new Object(), MODS = new Object();
    static final Map<String, Object> FIELDS = new java.util.LinkedHashMap<>();
    static {
        FIELDS.put("STONE", STONE);
        FIELDS.put("METAL", METAL);
        FIELDS.put("NETHERITE_BLOCK", METAL);
        FIELDS.put("WOOD", WOOD);
        FIELDS.put("GLASS", GLASS);
    }

    record Fake(String id, Set<String> tags, Object sound, Materials.Material material, boolean partial, List<String> asked) {
        // as BlockFactsOf: the sound's field names, lower case, sorted, "/" between; none: "?"
        BlockFacts facts() {
            TreeSet<String> names = new TreeSet<>();
            FIELDS.forEach((k, v) -> {
                if (v == sound) names.add(k.toLowerCase(Locale.ROOT));
            });
            return new BlockFacts(id, List.of(), names.isEmpty() ? "?" : String.join("/", names), 1F, 1F, false, true, 1, false, true,
                    new TreeSet<>(tags));
        }
    }

    // ---- b11fa9b's BulletRules matching, word for word (Identifier: as the game reads it, ResourceIds, which
    // ResourceIdsTest holds to it; state.is(tag): its tags; getSoundType: its sound; the material and partial noted)
    static final class Old {
        private static final Map<String, Object> SOUND_TYPES = soundTypes();

        private static Map<String, Object> soundTypes() {
            Map<String, Object> out = new HashMap<>();
            FIELDS.forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), v));
            return out;
        }

        private static final Map<String, Pattern> PATTERNS = new HashMap<>();

        private static boolean idMatches(String sel, String id) {
            if (sel.startsWith("~")) {
                Pattern p = PATTERNS.computeIfAbsent(sel, s -> {
                    try {
                        return Pattern.compile(s.substring(1));
                    } catch (RuntimeException e) {
                        return Pattern.compile("(?!)");
                    }
                });
                return p.matcher(id).matches();
            }
            String want = ResourceIds.tryParse(sel);
            return want != null && want.equals(id);
        }

        static boolean blockMatches(List<String> sel, Fake state) {
            String id = state.id();
            for (String s : sel) {
                if (s.equals("*")) return true;
                if (s.startsWith("#")) {
                    String t = ResourceIds.tryParse(s.substring(1));
                    if (t != null && state.tags().contains(t)) return true;
                } else if (s.startsWith("@")) {
                    Object st = SOUND_TYPES.get(s.substring(1).toLowerCase(Locale.ROOT));
                    if (st != null && state.sound() == st) return true;
                } else if (s.startsWith("%")) {
                    if (id.substring(0, id.indexOf(':')).equals(s.substring(1)) && partial(state)) return true;
                } else if (s.startsWith("$")) {
                    if (material(state).name().equalsIgnoreCase(s.substring(1))) return true;
                } else if (idMatches(s, id)) return true;
            }
            return false;
        }
    }

    static boolean partial(Fake f) {
        f.asked().add("partial");
        return f.partial();
    }

    static Materials.Material material(Fake f) {
        f.asked().add("material");
        return f.material();
    }

    @Test
    void theSameAsBefore() {
        SplittableRandom r = new SplittableRandom(1120L);
        String[] ids = {"minecraft:stone", "minecraft:oak_fence", "hbm:machine_solar_boiler", "hbm:steel_wall", "create:shaft", "minecraft:glass"};
        String[] tags = {"minecraft:logs", "minecraft:base_stone_overworld", "c:glass_blocks", "hbm:concrete", "minecraft:mineable/pickaxe"};
        Object[] sounds = {STONE, METAL, WOOD, GLASS, MODS};
        String[] words = {"*", "stone", "minecraft:stone", "oak_fence", "hbm:steel_wall", "Hbm:steel_wall", "hbm:", ":stone", "..:stone",
                "#minecraft:logs", "#logs", "#base_stone_overworld", "#c:glass_blocks", "#", "#Bad Tag", "#minecraft:mineable/pickaxe",
                "@stone", "@STONE", "@metal", "@netherite_block", "@wood", "@glass", "@?", "@", "@metal/netherite_block", "@netherite",
                "%hbm", "%minecraft", "%create", "%", "%HBM", "$machine", "$MACHINE", "$stone", "$glass", "$none", "$", "$nonsense",
                "~hbm:.*", "~.*stone", "~minecraft:(oak|spruce)_fence", "~[", "~", "~.*", "~stone", "create:shaft", "minecraft:Stone", "a b"};
        Materials.Material[] materials = Materials.Material.values();
        int yes = 0, no = 0, askedMaterial = 0, askedPartial = 0;
        for (int i = 0; i < 200_000; i++) {
            Set<String> t = new TreeSet<>();
            for (String tag : tags) if (r.nextInt(3) == 0) t.add(tag);
            Fake a = new Fake(ids[r.nextInt(ids.length)], t, sounds[r.nextInt(sounds.length)], materials[r.nextInt(materials.length)], r.nextBoolean(),
                    new ArrayList<>());
            Fake b = new Fake(a.id(), a.tags(), a.sound(), a.material(), a.partial(), new ArrayList<>());
            List<String> sel = new ArrayList<>();
            int n = 1 + r.nextInt(4);
            for (int k = 0; k < n; k++) sel.add(words[r.nextInt(words.length)]);
            boolean old = Old.blockMatches(sel, a);
            boolean now = BlockSelectors.matches(sel, b.facts(), () -> material(b), () -> partial(b));
            assertEquals(old, now, sel + " on " + a);
            assertEquals(a.asked(), b.asked(), sel + " on " + a);
            if (old) yes++;
            else no++;
            if (a.asked().contains("material")) askedMaterial++;
            if (a.asked().contains("partial")) askedPartial++;
        }
        assertTrue(yes > 30_000 && no > 30_000 && askedMaterial > 10_000 && askedPartial > 5_000,
                yes + " yes, " + no + " no, material " + askedMaterial + ", partial " + askedPartial);
    }

    @Test
    void identityOfSoundsIsByName() {
        // one sound under two names answers to either, not to the two together
        BlockFacts f = new BlockFacts("hbm:steel_wall", List.of(), "metal/netherite_block", 1F, 1F, false, true, 1, false, true, Set.of());
        assertTrue(BlockSelectors.matches(List.of("@netherite_block"), f, () -> Materials.Material.STONE, () -> false));
        assertTrue(BlockSelectors.matches(List.of("@metal"), f, () -> Materials.Material.STONE, () -> false));
        assertEquals(false, BlockSelectors.matches(List.of("@metal/netherite_block"), f, () -> Materials.Material.STONE, () -> false));
        assertEquals(false, BlockSelectors.matches(List.of("@netherite"), f, () -> Materials.Material.STONE, () -> false));
    }

}
