package acup.gunfeel;

import acup.gunfeel.host.Hurts;

/**
 * What DamageCap knows of a hurt without the game (its rule, DamageCapRule): which hurts no cap takes. DamageCap
 * extends it on each version, the hurt its source as it is (Hurts).
 */
class DamageCapBase {
    DamageCapBase() {
    }

    interface Settings {
        boolean flag(String key, boolean def);
        float number(String key, float def);
    }

    private static final Settings LIVE = new Settings() {
        public boolean flag(String key, boolean def) { return ReaimTimes.flag(key, def); }
        public float number(String key, float def) { return ReaimTimes.number(key, def); }
    };

    static boolean onOf() {
        return onOf(LIVE);
    }

    static boolean onOf(Settings settings) {
        return settings.flag("damageCap", true);
    }

    static DamageCapRule.Limits limitsOf() {
        return limitsOf(LIVE);
    }

    static DamageCapRule.Limits limitsOf(Settings settings) {
        var d = DamageCapRule.Limits.DEFAULT;
        return new DamageCapRule.Limits(shareOf(settings, "damageCapClick", d.click()), shareOf(settings, "damageCapHead", d.head()), shareOf(settings, "damageCapWindow", d.window()),
                ticksOf(settings, "damageCapWindowTicks", d.windowTicks()), shareOf(settings, "damageCapSniperHead", d.sniperHead()), ticksOf(settings, "damageCapSniperDelay", d.sniperDelay()));
    }

    static float shareOf(String key, float def) {
        return shareOf(LIVE, key, def);
    }

    /** a share of max health: above 0, a number (else the default) */
    static float shareOf(Settings settings, String key, float def) {
        float v = settings.number(key, def);
        return Float.isFinite(v) && v > 0F ? v : def;
    }

    static int ticksOf(String key, int def) {
        return ticksOf(LIVE, key, def);
    }

    /** ticks: 1 or more (else the default) */
    static int ticksOf(Settings settings, String key, int def) {
        float v = settings.number(key, def);
        return Float.isFinite(v) && v >= 1F ? Math.round(v) : def;
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
