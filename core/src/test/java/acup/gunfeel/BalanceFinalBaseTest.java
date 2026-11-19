package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import acup.gunfeel.EuphImmunityBase.Kit;

// The final scheme's keys without the game, at their defaults: the pairs' file read from the jar, the sets with the
// kits' pools, the stand's questions of a pair on a set and the acid's tier.
class BalanceFinalBaseTest {
    @Test
    void stateByDefault() {
        var s = BalanceFinalBase.state();
        assertSame(s, BalanceFinalBase.state(), "worked out once while the settings stay");
        assertTrue(BalanceFinalBase.pairs().size() > 200, "pairs: " + BalanceFinalBase.pairs().size());
        assertTrue(BalanceFinalBase.pairs().containsKey("stg77.r556_ap"));
        // the kits' pools by balance.final.pools, the others none
        assertEquals(45.0, s.sets().get("dns").pool());
        assertEquals(40.0, s.sets().get("fau").pool());
        assertEquals(150.0, s.sets().get("euphemium").pool());
        assertEquals(0.0, s.sets().get("hev").pool());
        assertEquals(BalanceFinalBase.DEFAULT_SETS.keySet(), s.sets().keySet());
        assertTrue(s.blast().on());
        assertTrue(s.acid().on());
    }

    @Test
    void pools() {
        assertEquals(45.0, BalanceFinalBase.pools(Kit.DNS));
        assertEquals(40.0, BalanceFinalBase.pools(Kit.FAU));
        assertEquals(150.0, BalanceFinalBase.pools(Kit.EUPHEMIUM));
        for (Kit k : Kit.values()) assertEquals(k.size, BalanceFinalBase.poolOf(k), k.name());
    }

    @Test
    void hitClicks() {
        double hev = BalanceFinalBase.hitClicks("stg77.r556_ap", "hev"), steel = BalanceFinalBase.hitClicks("stg77.r556_ap", "steel");
        assertTrue(hev > 0 && Double.isFinite(hev), "hev " + hev);
        assertTrue(hev > steel, "hev " + hev + " steel " + steel);
        // at the set's own tier: the same
        assertEquals(hev, BalanceFinalBase.hitClicksAt("stg77.r556_ap", "hev", BalanceFinalBase.DEFAULT_SETS.get("hev").tier()), 1e-12);
        assertTrue(Double.isNaN(BalanceFinalBase.hitClicks("stg77.nothing", "hev")));
        assertTrue(Double.isNaN(BalanceFinalBase.hitClicks("stg77.r556_ap", "nothing")));
    }

    @Test
    void acidTier() {
        // balance.final.acid by default: a tier off the HEV at the corrosion's top, nothing without it
        assertEquals(3.0, BalanceFinalBase.acidTier("hev", 0.75F), 1e-9);
        assertEquals(4.0, BalanceFinalBase.acidTier("hev", 0F), 1e-9);
        assertTrue(Double.isNaN(BalanceFinalBase.acidTier("nothing", 0.5F)));
        assertFalse(BalanceFinalBase.ACID.isBlank());
    }
}
