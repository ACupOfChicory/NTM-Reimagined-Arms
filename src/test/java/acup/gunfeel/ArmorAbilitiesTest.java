package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;

// Stage 1g of the balance: the sets' abilities by tier, without the game.
class ArmorAbilitiesTest {
    // NTM's full set bonuses (ModItems.initHazards, hbm-neoforge 1.0.3): effect levels (amplifier + 1)
    private static final Map<String, Map<String, Integer>> NTM = new LinkedHashMap<>();
    // the balance's levels (balance-by-progression.md 6.3-6.5, the owner on bismuth 2026-11-09)
    private static final Map<String, Map<String, Integer>> BALANCE = new LinkedHashMap<>();

    private static void set(String suit, Map<String, Integer> ntm, Map<String, Integer> balance) {
        NTM.put(suit, ntm);
        BALANCE.put(suit, balance);
    }

    static {
        set("DIESEL", Map.of("speed", 3, "jump_boost", 3), Map.of("speed", 2, "jump_boost", 1));
        set("TAURUN", Map.of("strength", 1), Map.of("strength", 1));
        set("T51", Map.of("strength", 1), Map.of("strength", 1));
        set("DESH", Map.of("haste", 5), Map.of("haste", 5));
        set("HEV", Map.of("speed", 2, "jump_boost", 1), Map.of("speed", 2, "jump_boost", 1));
        set("ENVSUIT", Map.of("speed", 2, "jump_boost", 1), Map.of("speed", 2, "jump_boost", 1));
        set("AJR", Map.of("jump_boost", 1, "strength", 1), Map.of("jump_boost", 1, "strength", 1));
        set("TRENCHMASTER", Map.of("strength", 3, "haste", 2, "jump_boost", 2, "speed", 1), Map.of("strength", 2, "haste", 2, "jump_boost", 1, "speed", 1));
        set("BJ", Map.of("speed", 2, "jump_boost", 1), Map.of("speed", 2, "jump_boost", 1));
        set("NCRPA", Map.of("strength", 4), Map.of("strength", 2));
        set("BISMUTH", Map.of("jump_boost", 7, "speed", 7, "regeneration", 2, "night_vision", 1), Map.of("jump_boost", 2, "speed", 3, "regeneration", 1, "night_vision", 1));
        set("SCHRABIDIUM", Map.of("haste", 3, "strength", 3, "jump_boost", 2, "speed", 3), Map.of("haste", 3, "strength", 2, "jump_boost", 2, "speed", 3));
        set("CMB", Map.of("speed", 3, "haste", 3, "strength", 5), Map.of("speed", 3, "haste", 3, "strength", 2));
        set("RPA", Map.of("strength", 4), Map.of("strength", 2));
        set("DIGAMMA", Map.of("jump_boost", 2), Map.of("jump_boost", 2));
        set("DNS", Map.of("strength", 10, "haste", 8, "jump_boost", 3), Map.of("strength", 2, "haste", 8, "jump_boost", 2));
        set("EUPHEMIUM", Map.of("regeneration", 128, "resistance", 128, "fire_resistance", 128, "haste", 128), Map.of("regeneration", 128, "resistance", 128, "fire_resistance", 128, "haste", 128));
        set("PAA", Map.of("haste", 1), Map.of("haste", 1));
    }

    private static Map<String, Integer> levels(String suit, ArmorAbilities.Keys k) {
        Map<String, Integer> out = new LinkedHashMap<>();
        NTM.get(suit).forEach((e, lvl) -> out.put(e, ArmorAbilities.amplifier(suit, e, lvl - 1, k) + 1));
        return out;
    }

    @Test
    void levelsByTier() {
        ArmorAbilities.Keys on = new ArmorAbilities.Keys(true, 2, true);
        for (String suit : NTM.keySet()) assertEquals(BALANCE.get(suit), levels(suit, on), suit);
        assertEquals(2, ArmorAbilities.dashes("BISMUTH", 3, on));
        assertEquals(1, ArmorAbilities.dashes("DNS", 1, on));
    }

    // each key off gives NTM's for its part
    @Test
    void keysOff() {
        ArmorAbilities.Keys off = new ArmorAbilities.Keys(false, 0, false);
        for (String suit : NTM.keySet()) assertEquals(NTM.get(suit), levels(suit, off), suit);
        assertEquals(3, ArmorAbilities.dashes("BISMUTH", 3, off));
        // strength alone
        ArmorAbilities.Keys strength = new ArmorAbilities.Keys(false, 2, false);
        assertEquals(Map.of("speed", 3, "jump_boost", 3), levels("DIESEL", strength));
        assertEquals(Map.of("strength", 2, "haste", 8, "jump_boost", 3), levels("DNS", strength));
        // bismuth off: NTM's, mobility does not touch it
        ArmorAbilities.Keys noBismuth = new ArmorAbilities.Keys(true, 2, false);
        assertEquals(NTM.get("BISMUTH"), levels("BISMUTH", noBismuth));
        // mobility alone
        ArmorAbilities.Keys mobility = new ArmorAbilities.Keys(true, 0, false);
        assertEquals(Map.of("strength", 3, "haste", 2, "jump_boost", 1, "speed", 1), levels("TRENCHMASTER", mobility));
    }

    @Test
    void esp() {
        // tier8: only FAU / DNS thermal, within the range and seen
        assertTrue(ArmorAbilities.thermalShows("tier8", 8, 15.9, 16, true));
        assertFalse(ArmorAbilities.thermalShows("tier8", 8, 16.1, 16, true));
        assertFalse(ArmorAbilities.thermalShows("tier8", 8, 5, 16, false));
        assertFalse(ArmorAbilities.thermalShows("tier8", 6, 5, 16, true));
        assertFalse(ArmorAbilities.thermalShows("tier8", 3, 5, 16, true));
        assertFalse(ArmorAbilities.thermalShows("off", 8, 5, 16, true));
        assertTrue(ArmorAbilities.thermalShows("all", 3, 60, 16, false));
        // AMAT Penance's thermal sight: as a tier 8 set
        assertTrue(ArmorAbilities.thermalShows("tier8", ArmorAbilities.GUN_SIGHT_TIER, 15.9, 16, true));
        assertFalse(ArmorAbilities.thermalShows("tier8", ArmorAbilities.GUN_SIGHT_TIER, 40, 16, true));
        assertFalse(ArmorAbilities.thermalShows("tier8", ArmorAbilities.GUN_SIGHT_TIER, 5, 16, false));
        assertFalse(ArmorAbilities.vatsShows("tier8"));
        assertFalse(ArmorAbilities.vatsShows("off"));
        assertTrue(ArmorAbilities.vatsShows("all"));
        // stage 3's three cases, by the default keys (tier8, 16 blocks): behind a wall at 10 no one sees the player, in
        // the open at 20 no one, in the open at 10 FAU and DNS (tier 8) and Penance's sight only
        for (int tier = 0; tier <= 9; tier++) {
            assertFalse(ArmorAbilities.thermalShows("tier8", tier, 10, 16, false), "wall 10, tier " + tier);
            assertFalse(ArmorAbilities.thermalShows("tier8", tier, 20, 16, true), "open 20, tier " + tier);
            assertEquals(tier >= 8, ArmorAbilities.thermalShows("tier8", tier, 10, 16, true), "open 10, tier " + tier);
        }
        assertEquals(8, ArmorAbilities.tier("DIGAMMA"));
        assertEquals(8, ArmorAbilities.tier("DNS"));
        assertEquals(3, ArmorAbilities.tier("DIESEL"));
    }

    @Test
    void shippedKeys() throws IOException {
        Properties p = SpreadModelTest.shipped();
        assertEquals("true", p.getProperty("abilities.mobilityCap"));
        assertEquals("2", p.getProperty("abilities.strengthCap"));
        assertEquals("true", p.getProperty("abilities.bismuth"));
        assertEquals("tier8", p.getProperty("abilities.esp.players"));
        assertEquals("16", p.getProperty("abilities.esp.playerRange"));
    }
}
