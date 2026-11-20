package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import acup.gunfeel.host.FakeHurts;
import acup.gunfeel.host.Hurts;

// The hurt's kinds in core (BalanceArmorBase.categoryOf, categoryAnyOf, irBeamOf, shotTypeOf; DamageCapBase.exemptOf)
// against cc2033b's BalanceArmor and DamageCap word for word, each game call a question to the fake port: on every
// mix of the answers (the seven questions, the direct entity's kind, NTM's category, the type's name), the same answer and the same questions in the same order. The game's own answers to each
// question are the bullet check's (world:).
class HurtKindsTest {
    // ---- cc2033b's, the source's calls the port's
    static String category(FakeHurts h, Object s) {
        String cat = categoryAny(h, s);
        return cat == null || "EXPL".equals(cat) || shot(h, s) || irBeam(h, s) ? cat : null;
    }

    static boolean irBeam(FakeHurts h, Object s) {
        return (h.directKind(s) & Hurts.NTM_BEAM) != 0 && h.is(s, Hurts.NTM_FIRE);
    }

    static String categoryAny(FakeHurts h, Object s) {
        if (h.is(s, Hurts.BYPASSES_EFFECTS)) return null;
        if (h.is(s, Hurts.NTM_NUCLEAR_BLAST) || h.is(s, Hurts.NTM_DIGAMMA) || h.is(s, Hurts.NTM_RADIATION)) return null;
        String cat = h.ntmCategory(s);
        if ("FIRE".equals(cat) && irBeam(h, s)) return "EN";
        if ("EXPL".equals(cat)) return ReaimTimes.flag("balanceArmorBlasts", true) ? cat : null;
        return "EN".equals(cat) || "PHYS".equals(cat) ? cat : null;
    }

    static boolean shot(FakeHurts h, Object s) {
        String key = h.typeName(s);
        if (key == null) return false;
        int c = key.indexOf(':');
        return BalanceArmorRule.shotType(key.substring(0, c), key.substring(c + 1), (h.directKind(s) & Hurts.LIVING) != 0);
    }

    static boolean exempt(FakeHurts h, Object s) {
        return h.is(s, Hurts.NTM_NUCLEAR_BLAST) || h.is(s, Hurts.NTM_RADIATION) || h.is(s, Hurts.NTM_DIGAMMA)
                || (h.is(s, Hurts.FIRE) && !irBeam(h, s)) || h.is(s, Hurts.FALL);
    }

    private static final String[] CATEGORIES = {null, "EXPL", "FIRE", "PHYS", "EN", "SPIKES"};
    private static final String[] NAMES = {null, "hbm:physical", "hbm:laser", "hbm:electric", "hbm:fire", "minecraft:arrow", "minecraft:player_attack",
            "hbm:nuclear_blast", "other:physical"};

    private static int run() {
        FakeHurts a = new FakeHurts(), b = new FakeHurts();
        int yesCategories = 0;
        for (int yes = 0; yes < 1 << 7; yes++)
            for (int kind = 0; kind < 4; kind++)
                for (String cat : CATEGORIES)
                    for (String name : NAMES) {
                        var s = new FakeHurts.Hurt(yes, kind, cat, name);
                        String at = s.toString();
                        a.calls.clear();
                        b.calls.clear();
                        String ca = category(a, s), cb = BalanceArmorBase.categoryOf(b, s);
                        assertEquals(ca, cb, at);
                        assertEquals(a.calls, b.calls, at);
                        if (ca != null) yesCategories++;
                        assertEquals(categoryAny(a, s), BalanceArmorBase.categoryAnyOf(b, s), at);
                        assertEquals(irBeam(a, s), BalanceArmorBase.irBeamOf(b, s), at);
                        assertEquals(shot(a, s), BalanceArmorBase.shotTypeOf(b, s), at);
                        assertEquals(exempt(a, s), DamageCapBase.exemptOf(b, s), at);
                        assertEquals(a.calls, b.calls, at);
                    }
        return yesCategories;
    }

    @Test
    void theSameAsBefore() {
        // balanceArmorBlasts is the balance's key (BalanceKeys: the server's file sets it only with the balance's keys
        // open to it), on here; its off way is the temporary stage's on the spread check (keys open)
        assertTrue(run() > 0);
    }

    @Test
    void categoriesNtms() {
        // NTM's DamageResistanceHandler.CATEGORY_* (26.2's HurtsTest holds them to NTM's own)
        assertEquals(List.of("EXPL", "FIRE", "PHYS", "EN"), Arrays.asList(BalanceArmorBase.CAT_EXPLOSION, BalanceArmorBase.CAT_FIRE,
                BalanceArmorBase.CAT_PHYSICAL, BalanceArmorBase.CAT_ENERGY));
    }

    @Test
    void shotNameIsShotType() {
        List<String> paths = new ArrayList<>(BalanceArmorRule.SHOT_TYPES);
        paths.addAll(List.of("player_attack", "arrow", "fire", "nuclear_blast", "a/b", "x.y", "laser_", "physical2", ""));
        for (String ns : List.of("hbm", "minecraft", "hbm2", "h"))
            for (String p : paths)
                for (boolean c : new boolean[] {false, true})
                    assertEquals(BalanceArmorRule.shotType(ns, p, c), BalanceArmorRule.shotName(ns + ":" + p, c), ns + ":" + p + " " + c);
    }
}
