package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

// The fence families without the game and the shapes' file: which family a block takes, the path rule's words, the
// key of a family's cells (26.2's FenceShapesTest meets the keys with the file's cells)
class FenceFamiliesTest {
    // a "path" rule wins over the class and its mask filter; without one the class decides, a mask keeps it out
    @Test
    void ruleWinsOverTheClass() {
        assertEquals(null, FenceFamilies.family("hitbox", "fence", false), "hitbox takes a fence out");
        assertEquals(null, FenceFamilies.family("pixels", "fence", false), "pixels takes a fence out");
        assertEquals("fence", FenceFamilies.family("fence", null, false), "a rule makes any block a fence");
        assertEquals("fence", FenceFamilies.family("fence", "fence", true), "a rule wins over the mask filter");
        assertEquals("wall", FenceFamilies.family("wall", "fence", false), "a rule wins over the class");
        assertEquals("wall", FenceFamilies.family(null, "wall", false), "no rule: by the class");
        assertEquals(null, FenceFamilies.family(null, "fence", true), "no rule, a mask: by its pixels");
        assertEquals(null, FenceFamilies.family(null, null, false), "no rule, no family");
    }

    // every path word but the four algorithms is a family with its cells
    @Test
    void pathWordsAreAlgorithmsOrFamilies() {
        for (String w : FenceFamilies.PATH_WORDS) {
            if (java.util.Set.of("hitbox", "pixels", "model", "through").contains(w)) continue;
            assertTrue(FenceFamilies.FAMILIES.containsKey(w), w);
        }
        assertTrue(FenceFamilies.PATH_WORDS.containsAll(FenceFamilies.FAMILIES.keySet()));
    }

    // the key: "~family[...]", the family's own properties by name (TreeSet), the rest left out
    @Test
    void keyOfTheFamilysOwnProperties() {
        assertEquals("~fence[east=true,north=false,south=false,west=true]",
                FenceFamilies.familyKey("fence", Map.of("west", "true", "east", "true", "waterlogged", "true")));
        assertEquals("~fence_gate[facing=east,in_wall=false,open=true]", FenceFamilies.familyKey("fence_gate", Map.of("open", "true", "facing", "east")));
        assertEquals("~wall[east=tall,north=low,south=none,up=false,west=none]",
                FenceFamilies.familyKey("wall", Map.of("north", "low", "east", "tall", "up", "false")));
        assertEquals("~bamboo_fence[east=false,north=false,south=false,west=false]", FenceFamilies.familyKey("bamboo_fence", Map.of()));
    }

    // a property missing or with a value the family has not: the family's first value (a fence: false; a wall's up: true)
    @Test
    void anOddValueTakesTheFirst() {
        assertEquals("~wall[east=none,north=none,south=none,up=true,west=none]",
                FenceFamilies.familyKey("wall", Map.of("north", "true", "up", "maybe")));
        assertEquals("~fence_gate[facing=north,in_wall=false,open=false]", FenceFamilies.familyKey("fence_gate", Map.of("facing", "up", "open", "")));
        Map<String, String> none = new HashMap<>();
        none.put("east", null);
        assertEquals("~fence[east=false,north=false,south=false,west=false]", FenceFamilies.familyKey("fence", none));
    }

    // every family's key names each of its properties once, in name order
    @Test
    void everyFamilysKeyInNameOrder() {
        for (var e : FenceFamilies.FAMILIES.entrySet()) {
            String k = FenceFamilies.familyKey(e.getKey(), Map.of());
            String inside = k.substring(k.indexOf('[') + 1, k.length() - 1);
            String[] parts = inside.split(",");
            assertEquals(e.getValue().size(), parts.length, k);
            for (int i = 1; i < parts.length; i++) assertTrue(parts[i - 1].compareTo(parts[i]) < 0, k);
            assertTrue(k.startsWith("~" + e.getKey() + "["), k);
        }
        // not a family: no key (a caller asks only for a family's)
        assertThrows(NullPointerException.class, () -> FenceFamilies.familyKey("door", Map.of()));
    }
}
