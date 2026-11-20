package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// core's bullet rules without a version: read once, and anew when BulletRuleText reads them anew; the answers that need
// no block or type as BulletRuleText gives them from the same rules; a block asked with no table throws, but its
// material is "no facts" (a block that can't tell its facts) as before (a version gives the tables: BlockTable,
// EntityTypeTable)
class BulletRulesBaseTest {
    // Without a version the config folder throws at the first look at the file (BulletRuleText.load asks GameDirs before
    // its try: notes/ports-base/bugs.md), and the rules stay what they were; looked at once here, before the tests
    @BeforeAll
    static void firstLook() {
        try {
            BulletRuleText.load();
        } catch (IllegalStateException e) {
            // no game folders
        }
    }

    @Test
    void theRulesWithoutTheGame() {
        int g = BulletRulesBase.generation();
        assertEquals(g, BulletRulesBase.generation(), "read once");
        var all = BulletRuleText.load();
        assertEquals(BulletRuleText.anyThrough(all), BulletRulesBase.anyThrough());
        assertEquals(BulletRuleText.whizz(BulletRuleText.last(all, "whizz", x -> true)), BulletRulesBase.whizz());
        assertEquals(BulletRuleText.fly(BulletRuleText.last(all, "fly", x -> true)), BulletRulesBase.fly());
        assertEquals(g, BulletRulesBase.generation(), "still once");
    }

    @Test
    void readAnewGoesUp() {
        int g = BulletRulesBase.generation();
        BulletRuleText.readings++;
        assertEquals(g + 1, BulletRulesBase.generation());
        assertEquals(g + 1, BulletRulesBase.generation());
    }

    @Test
    void noTablesNoBlocks() {
        assertEquals(Materials.Source.UNBREAKABLE, BulletRulesBase.material(5).source());
        assertEquals("no facts", BulletRulesBase.material(5).why());
        BulletRuleText.readings++;
        assertThrows(UnsupportedOperationException.class, () -> BulletRulesBase.matches(java.util.List.of("minecraft:stone"), 5));
        assertThrows(UnsupportedOperationException.class, () -> BulletRulesBase.entityMatches(java.util.List.of("*"), 0));
    }
}
