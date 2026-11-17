package acup.gunfeel.nuke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

class MiniWasteMutatorTest {

    static final Predicate<String> NONE = t -> false;

    static Predicate<String> in(String... tags) {
        return Set.of(tags)::contains;
    }

    @Test
    void groundDies() {
        assertEquals("hbm:waste_earth", MiniWasteMutator.waste("minecraft:grass_block", NONE, 1));
        assertEquals("hbm:waste_earth", MiniWasteMutator.waste("minecraft:podzol", NONE, 1));
        assertEquals("hbm:waste_mycelium", MiniWasteMutator.waste("minecraft:mycelium", NONE, 1));
        assertEquals("minecraft:terracotta", MiniWasteMutator.waste("minecraft:clay", NONE, 1));
    }

    @Test
    void leavesDieAndPlantsGo() {
        assertEquals("hbm:waste_leaves", MiniWasteMutator.waste("minecraft:oak_leaves", in("minecraft:leaves"), 1));
        for (String p : new String[] {"minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:brown_mushroom",
                "minecraft:red_mushroom_block", "minecraft:snow"})
            assertEquals(MiniWasteMutator.AIR, MiniWasteMutator.waste(p, NONE, 1), p);
        assertEquals(MiniWasteMutator.AIR, MiniWasteMutator.waste("minecraft:poppy", in("minecraft:flowers"), 1));
        assertEquals(MiniWasteMutator.AIR, MiniWasteMutator.waste("minecraft:oak_sapling", in("minecraft:saplings"), 1));
        assertEquals(MiniWasteMutator.AIR, MiniWasteMutator.waste("minecraft:wheat", in("minecraft:bee_growables"), 1));
    }

    // no wood burnt, nothing blown away, no coal to diamond, the waste itself left as it is
    @Test
    void woodAndTheRestStay() {
        for (String id : new String[] {"minecraft:oak_log", "minecraft:oak_planks", "minecraft:oak_door", "minecraft:mushroom_stem",
                "minecraft:coal_ore", "minecraft:diamond_ore", "minecraft:stone", "minecraft:dirt", "hbm:waste_earth",
                "hbm:waste_leaves", "minecraft:bedrock"})
            assertNull(MiniWasteMutator.waste(id, in("minecraft:logs", "minecraft:planks", "minecraft:mineable/axe"), 1), id);
    }

    @Test
    void uraniumTransmutes() {
        assertEquals("hbm:ore_schrabidium", MiniWasteMutator.waste("hbm:ore_uranium", NONE, 0));
        assertEquals("hbm:ore_uranium_scorched", MiniWasteMutator.waste("hbm:ore_uranium", NONE, 5));
        assertEquals("hbm:ore_schrabidium", MiniWasteMutator.waste("hbm:ore_uranium_deepslate", NONE, 0));
        assertEquals("hbm:ore_nether_uranium_scorched", MiniWasteMutator.waste("hbm:ore_nether_uranium", NONE, 1));
        assertEquals("hbm:ore_gneiss_schrabidium", MiniWasteMutator.waste("hbm:ore_gneiss_uranium", NONE, 0));
    }

    @Test
    void sandSometimesTrinitite() {
        assertEquals("hbm:waste_trinitite", MiniWasteMutator.waste("minecraft:sand", NONE, 0));
        assertNull(MiniWasteMutator.waste("minecraft:sand", NONE, 3));
        assertEquals("hbm:waste_trinitite_red", MiniWasteMutator.waste("minecraft:red_sand", NONE, 0));
    }
}
