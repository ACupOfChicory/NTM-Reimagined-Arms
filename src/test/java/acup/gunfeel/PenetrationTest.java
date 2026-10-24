package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import acup.gunfeel.Materials.Material;
import acup.gunfeel.Materials.Source;
import acup.gunfeel.Materials.Verdict;

class PenetrationTest {
    private static final Verdict STEEL = new Verdict(Material.STEEL, 1.2247F, Source.RULE, "");
    private static final Verdict GLASS = new Verdict(Material.REINFORCED_GLASS, 1F, Source.RULE, "");

    @Test
    void mrDeuceDuIsAtItsLimitInOneMetreOfReinforcedGlass() {
        var path = new Materials.Penetration();
        assertEquals(1.2375F, path.left(GLASS, 1, 11.25F, 21, .25F), 1e-4F);
        assertTrue(new Materials.Penetration().left(GLASS, 1.031, 11.25F, 21, .25F) < 1);
    }

    @Test
    void secondSteelPanelCannotRenewThresholdNegation() {
        var path = new Materials.Penetration();
        float first = path.left(STEEL, .125, 11.25F, 21, .25F);
        assertEquals(9.627F, first, .01F);
        assertTrue(path.spent() > 17);
        assertTrue(path.left(STEEL, .125, first, 21, .25F) < 1);
        // Старый расчёт давал полный запас на каждой панели и пропускал двенадцать.
        float old = 11.25F;
        for (int i = 0; i < 12; i++) old = Materials.left(STEEL, .125, old, 21, .25F);
        assertTrue(old > 1);
    }

    @Test
    void thinnerPiecesCannotRestoreTheSpentBudget() {
        var path = new Materials.Penetration();
        float damage = 11.25F;
        int passed = 0;
        for (; passed < 100; passed++) {
            float next = path.left(STEEL, .01, damage, 21, .25F);
            if (next < 1) break;
            assertTrue(next <= damage);
            damage = next;
        }
        assertTrue(passed > 0 && passed < 20, "passed " + passed);
        assertTrue(path.spent() <= 21);
    }

    @Test
    void aCreatureBeforeTheWallCanRestoreTheSpeculativeBudget() {
        var path = new Materials.Penetration();
        double before = path.spent();
        float expected = path.left(STEEL, .125, 11.25F, 21, .25F);
        double after = path.spent();
        path.restore(before);
        assertEquals(expected, path.left(STEEL, .125, 11.25F, 21, .25F));
        assertEquals(after, path.spent());
    }

    @Test
    void aGapAndAStoppedBulletDoNotSpendTheBudget() {
        var path = new Materials.Penetration();
        assertEquals(11.25F, path.left(new Verdict(Material.NONE, 1F, Source.RULE, ""), 1, 11.25F, 21, .25F));
        assertEquals(0, path.spent());
        assertTrue(path.left(STEEL, 1, 11.25F, 21, .25F) < 1);
        assertEquals(0, path.spent());
    }
}
