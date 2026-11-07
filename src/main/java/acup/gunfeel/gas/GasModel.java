package acup.gunfeel.gas;

import java.util.function.IntUnaryOperator;

/**
 * What a tick of gas does to a {@link GasState}: the doses, the symptoms and the damage, with no game classes, so every
 * curve is tested without the game. {@link GasServer} feeds it the concentrations and protections it finds in the world
 * and acts on what it leaves in the state (damage to hurt with, effects to apply, the blocks on sprint and regeneration).
 */
public final class GasModel {
    /** One tick, seconds. */
    public static final float DT = 1F / 20F;

    private GasModel() {
    }

    // ---- protection

    /** The share of the gas a mask with that share of its filter left keeps out: 0.95, falling to 0.70 below a quarter. */
    public static float coefficient(float filterLeft) {
        float f = Math.max(0F, Math.min(1F, filterLeft));
        if (f >= GasRules.FILTER_WEAR_FROM) return GasRules.PROTECT_FULL;
        return GasRules.PROTECT_WORN + (GasRules.PROTECT_FULL - GasRules.PROTECT_WORN) * (f / GasRules.FILTER_WEAR_FROM);
    }

    /**
     * Mustard's protection: the helmet's and the suit's together. Each part short halves it; neither is none.
     *
     * @param helmet   a helmet that keeps blistering gas out (a sealed or hazmat one, with its filter if it takes one)
     * @param suit     a hazmat suit
     * @param filter   the helmet's filter left, 0 to 1 (1 for a helmet with none to wear)
     */
    public static float mustardProtection(boolean helmet, boolean suit, float filterLeft) {
        if (!helmet && !suit) return 0F;
        float p = coefficient(helmet ? filterLeft : 1F);
        if (!helmet) p *= GasRules.MUSTARD_PART_MISSING;
        if (!suit) p *= GasRules.MUSTARD_PART_MISSING;
        return p;
    }

    /** Filter wear for a tick in a gas at concentration {@code c} (its W times C). */
    public static float filterWear(GasKind kind, float c) {
        return kind.filterWear * Math.max(0F, Math.min(1F, c));
    }

    /**
     * How many of the four armour slots leave the skin bare to acid. Without a power armour piece on, a slot is covered
     * if anything is in it; with one on, a slot with nothing or with a piece that is not a power armour's (a set of the
     * rules of NTM's DT/DR) is bare, and a piece from another power set is fine.
     *
     * @param present a piece is in the slot
     * @param power   the piece belongs to a power armour set
     */
    public static int bareSlots(boolean[] present, boolean[] power) {
        boolean anyPower = false;
        for (int i = 0; i < present.length; i++) anyPower |= present[i] && power[i];
        int bare = 0;
        for (int i = 0; i < present.length; i++) if (!present[i] || anyPower && !power[i]) bare++;
        return bare;
    }

    // ---- the tick

    /**
     * One tick. All arrays are by {@link GasKind#ordinal()}.
     *
     * @param c        the concentration at the head, 0 to 1
     * @param p        the share of it the protection keeps out, 0 to 1 (acid's does not count)
     * @param floorG   the mustard on the floor under the feet (0 in the air)
     * @param bare     armour slots bare to acid, 0 to 4
     * @param wet      in water or rain
     * @param rng      {@code nextInt(bound)}
     */
    public static void tick(GasState s, float[] c, float[] p, float floorG, int bare, boolean wet, IntUnaryOperator rng) {
        s.slow = 0F;
        s.noSprint = s.noRegen = s.nausea = s.darkness = s.coughNow = s.smellNow = false;
        s.blackLung = 0;
        float lungGain = 0F;

        // chlorine
        {
            int i = GasState.CHLORINE;
            float eff = c[i] * (1F - p[i]);
            float gain = eff * DT;
            s.dose[i] += gain;
            lungGain += gain;
            if (c[i] <= 0.001F) s.dose[i] = Math.max(0F, s.dose[i] - GasRules.CHLORINE_DECAY * DT);
            if (s.dose[i] >= GasRules.CHLORINE_COUGH_DOSE) {
                s.slow = Math.max(s.slow, GasRules.CHLORINE_SLOW);
                s.noSprint = true;
                if (s.cough > 0) s.cough--;
                if (s.nextCough <= 0) {
                    s.cough = GasRules.COUGH_TICKS;
                    s.coughNow = true;
                    s.nextCough = GasRules.COUGH_MIN + rng.applyAsInt(GasRules.COUGH_MAX - GasRules.COUGH_MIN + 1);
                } else s.nextCough--;
            } else {
                s.nextCough = 0;
                if (s.cough > 0) s.cough--;
            }
            if (s.dose[i] >= GasRules.CHLORINE_DAMAGE_DOSE) damage(s, GasRules.CHLORINE_DAMAGE * eff * DT);
        }

        // phosgene
        {
            int i = GasState.PHOSGENE;
            float eff = c[i] * (1F - p[i]);
            s.dose[i] += eff * DT;
            if (s.latent < 0) {
                if (s.dose[i] >= GasRules.PHOSGENE_SMELL_DOSE) {
                    s.latent = GasRules.PHOSGENE_LATENT_TICKS;
                    if (!s.smelled) s.smellNow = true;
                    s.smelled = true;
                } else if (c[i] <= 0.001F) s.dose[i] = Math.max(0F, s.dose[i] - GasRules.PHOSGENE_IDLE_DECAY * DT);
            } else if (s.latent > 0) {
                s.latent--;
            } else {
                // the lungs swell: 0.2 HP a second per dose while the dose goes down by a tenth of itself a second
                damage(s, GasRules.PHOSGENE_DAMAGE_PER_DOSE * s.dose[i] * DT);
                s.dose[i] -= s.dose[i] * GasRules.PHOSGENE_DECAY * DT;
                s.slow = Math.max(s.slow, GasRules.PHOSGENE_SLOW);
                if (s.dose[i] < GasRules.PHOSGENE_END_DOSE) {
                    s.dose[i] = 0F;
                    s.latent = -1;
                    s.smelled = false;
                }
            }
        }

        // mustard
        {
            int i = GasState.MUSTARD;
            float eff = c[i] * (1F - p[i]);
            float floor = floorG * (1F - p[i]);
            float gain = (eff + GasRules.MUSTARD_FLOOR_WEIGHT * floor) * DT;
            s.dose[i] += gain;
            lungGain += gain;
            if (gain <= 0F) s.dose[i] = Math.max(0F, s.dose[i] - GasRules.MUSTARD_DECAY * DT);
            float d = s.dose[i];
            if (d >= GasRules.MUSTARD_L1) {
                s.noRegen = true;
                damage(s, (d >= GasRules.MUSTARD_L3 ? GasRules.MUSTARD_DAMAGE_3 : d >= GasRules.MUSTARD_L2 ? GasRules.MUSTARD_DAMAGE_2 : GasRules.MUSTARD_DAMAGE_1) * DT);
            }
            s.nausea = d >= GasRules.MUSTARD_L2;
            s.darkness = d >= GasRules.MUSTARD_L3;
        }

        // acid: the corrosion and the burn do not care for the mask; the lungs do
        {
            int i = GasState.ACID;
            float raw = c[i];
            float gain = raw * (1F - p[i]) * DT;
            s.dose[i] += gain;
            lungGain += gain;
            if (raw <= 0.001F) {
                s.dose[i] = Math.max(0F, s.dose[i] - GasRules.ACID_DECAY * DT);
                s.k = Math.max(0F, s.k - (wet ? GasRules.ACID_K_DECAY_WATER : GasRules.ACID_K_DECAY) * DT);
            } else {
                s.k = Math.min(GasRules.ACID_K_MAX, s.k + GasRules.ACID_K_GAIN * raw * DT);
                damage(s, GasRules.ACID_BURN_PER_SLOT * bare * raw * DT);
            }
        }

        s.lung += lungGain * GasRules.BLACK_LUNG_PER_DOSE;
        int whole = (int) s.lung;
        s.blackLung = whole;
        s.lung -= whole;
    }

    private static void damage(GasState s, float hp) {
        if (hp > 0F) s.pending += hp;
    }

    /** A threshold, a percentage or a count of armour points under the acid's corrosion K: (1 - K) of it is left. */
    public static float corrode(float value, float k) {
        return value * (1F - Math.max(0F, Math.min(GasRules.ACID_K_MAX, k)));
    }

    /** Milk: the phosgene dose by half. */
    public static void milk(GasState s) {
        s.dose[GasState.PHOSGENE] *= GasRules.PHOSGENE_MILK;
    }

    /**
     * The damage to deal now: the pending HP once every {@link GasRules#HURT_EVERY} ticks (at once when it is a full
     * point), taken out of the state; 0 when it is not the time.
     */
    public static float takeDamage(GasState s) {
        if (s.pending <= 0F) {
            s.hurtIn = 0;
            return 0F;
        }
        if (--s.hurtIn > 0 && s.pending < 1F) return 0F;
        s.hurtIn = GasRules.HURT_EVERY;
        float out = s.pending;
        s.pending = 0F;
        s.dealt += out;
        return out;
    }
}
