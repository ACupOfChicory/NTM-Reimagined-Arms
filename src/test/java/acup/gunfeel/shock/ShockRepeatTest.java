package acup.gunfeel.shock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import acup.gunfeel.BalanceGunsRuleAccess;

// A shock shell's hit on one shocked or immune already: most of a slug of the same gun (the owner's 85 to 100 %), its
// damage made so off the final scheme by the guns' table (the Maresleg's lines: shock 1.0, slug 1.62).
class ShockRepeatTest {
    @Test
    void offTheSchemeTheShellIsMadeAsHeavyAsASlug() {
        // NTM's slug does its click in one projectile (damageMult 1 here); the shell is ShockAmmo.DAMAGE_MULT of it
        assertEquals(1.62F / ShockAmmo.DAMAGE_MULT, BalanceGunsRuleAccess.share(ShockAmmo.DAMAGE_MULT, 1.0F, 1F, 1.62F), 1e-4F);
        // lines of no change: the ammo's own damage alone
        assertEquals(1F / ShockAmmo.DAMAGE_MULT, BalanceGunsRuleAccess.share(ShockAmmo.DAMAGE_MULT, 1F, 1F, 1F), 1e-4F);
        // no damage of its own: left as it is
        assertEquals(1F, BalanceGunsRuleAccess.share(0F, 1F, 1F, 1.62F));
    }
}
