package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

// Plants a bullet breaks, as a pumpkin: cactus and bamboo, by the mod's own bullet rules on the block dump.
class PlantsTest {
    static String rule(List<GlassTest.Break> all, BlockFacts f) {
        GlassTest.Break b = GlassTest.breakOf(all, f);
        return b == null ? "none" : String.join(" ", b.words());
    }

    @Test
    void cactusAndBambooBreak() throws IOException {
        List<GlassTest.Break> all = GlassTest.breaks();
        Map<String, BlockFacts> facts = MaterialsTest.facts().stream().collect(Collectors.toMap(BlockFacts::id, Function.identity()));
        assertEquals("hits 4 drop", rule(all, facts.get("minecraft:pumpkin")));
        assertEquals("hits 2 drop", rule(all, facts.get("minecraft:cactus")));
        assertEquals("instant drop pass", rule(all, facts.get("minecraft:bamboo")));
        // the other bumped-into plants are still flown through
        assertEquals("never through", rule(all, facts.get("minecraft:cocoa")));
        assertEquals("never through", rule(all, facts.get("minecraft:chorus_plant")));
    }

    // bamboo saplings and sugar cane need no line: bullets go by the collision shape and these have none
    @Test
    void saplingAndSugarCaneHaveNoCollision() throws IOException {
        Map<String, BlockFacts> facts = MaterialsTest.facts().stream().collect(Collectors.toMap(BlockFacts::id, Function.identity()));
        for (String id : List.of("minecraft:bamboo_sapling", "minecraft:sugar_cane")) {
            BlockFacts f = facts.get(id);
            assertNotNull(f, id);
            assertEquals(0.0, f.collisionVolume(), id);
        }
    }
}
