package acup.gunfeel;

import org.jspecify.annotations.Nullable;

import com.hbm.items.weapon.sedna.BulletConfig;

/** 26.2's rounds for core (RoundSpec): NTM's BulletConfig's numbers that core's ballistics read, into one per thread. */
final class RoundSpecs {
    private static final ThreadLocal<RoundSpec> SPEC = ThreadLocal.withInitial(RoundSpec::new);

    private RoundSpecs() {
    }

    /**
     * the round's numbers (none: all 0), in this thread's RoundSpec: it lives until the next fill on this thread, so it
     * is not kept past the call it is given to
     */
    static RoundSpec fill(@Nullable BulletConfig round) {
        RoundSpec out = SPEC.get().clear();
        if (round == null) return out;
        out.armorThresholdNegation = round.armorThresholdNegation;
        out.armorPiercingPercent = round.armorPiercingPercent;
        return out;
    }
}
