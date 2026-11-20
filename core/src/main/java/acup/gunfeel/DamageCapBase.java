package acup.gunfeel;

import acup.gunfeel.host.Hurts;

/**
 * What DamageCap knows of a hurt without the game (its rule, DamageCapRule): which hurts no cap takes. DamageCap
 * extends it on each version, the hurt its source as it is (Hurts).
 */
class DamageCapBase {
    DamageCapBase() {
    }

    /** not capped: no shooter's gun behind it (nuclear, digamma, fire, falling, radiation) */
    static boolean exemptOf(Object hurt) {
        return exemptOf(BalanceArmorBase.HurtPort.HURTS, hurt);
    }

    /** {@link #exemptOf(Object)} on the port {@code h} (a test's own) */
    static boolean exemptOf(Hurts h, Object hurt) {
        return h.is(hurt, Hurts.NTM_NUCLEAR_BLAST) || h.is(hurt, Hurts.NTM_RADIATION) || h.is(hurt, Hurts.NTM_DIGAMMA)
                || (h.is(hurt, Hurts.FIRE) && !BalanceArmorBase.irBeamOf(h, hurt)) || h.is(hurt, Hurts.FALL);
    }
}
