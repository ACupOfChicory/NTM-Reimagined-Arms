package acup.gunfeel;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.NtmGuns;

/**
 * The ammo menu's choices and their numbers (RadialMenu), on NTM's reads through NtmGuns: the rounds the first
 * magazine of the gun in the main hand takes that the holder carries, each once, in NTM's order, at most as many as the
 * menu has places; the figures of each from that gun (AmmoStats). The client's thread only: its arrays are kept.
 */
public final class AmmoChoices {
    private static final NtmGuns GUNS = CoreServices.load(NtmGuns.class, NtmGuns.NONE);
    // more than any gun of NTM's takes; one that takes more is asked again with room for all
    private static int[] rounds = new int[64];
    private static final float[] GUN = new float[NtmGuns.C_COUNT], ROUND = new float[NtmGuns.S_COUNT];

    private AmmoChoices() {
    }

    /**
     * The choices ({@link acup.gunfeel.host.RoundTable} numbers) into {@code out}, whose length is the most: how many;
     * {@link NtmGuns#ROUNDS_GL54} for the GL-54's magazine (its entries are 26.2's)
     */
    public static int choices(Body holder, int[] out) {
        return choices(GUNS, holder, out);
    }

    static int choices(NtmGuns guns, Body holder, int[] out) {
        int n = guns.menuRounds(holder, Body.MAIN_HAND, rounds);
        if (n > rounds.length) {
            rounds = new int[n];
            n = guns.menuRounds(holder, Body.MAIN_HAND, rounds);
        }
        if (n == NtmGuns.ROUNDS_GL54) return n;
        int k = 0;
        for (int i = 0; i < n; i++) {
            int r = rounds[i];
            if (k < out.length && !among(out, k, r) && guns.reserve(holder, r) != 0) out[k++] = r;
        }
        return k;
    }

    private static boolean among(int[] a, int n, int r) {
        for (int i = 0; i < n; i++) if (a[i] == r) return true;
        return false;
    }

    /**
     * The figures of each of the {@code n} choices from the gun in the main hand, null where the gun cannot tell; none
     * for the GL-54's entries ({@code gl}: their figures are its own), its gun read all the same
     */
    public static AmmoStats[] stats(Body holder, int[] choices, int n, boolean gl) {
        return stats(GUNS, holder, choices, n, gl);
    }

    static AmmoStats[] stats(NtmGuns guns, Body holder, int[] choices, int n, boolean gl) {
        AmmoStats[] out = new AmmoStats[n];
        if (!guns.choiceStats(holder, Body.MAIN_HAND, GUN)) return out;
        float wear = AmmoStats.wear(GUN[NtmGuns.C_WEAR], GUN[NtmGuns.C_DURABILITY]);
        float base = GUN[NtmGuns.C_BASE_DAMAGE], split = GUN[NtmGuns.C_SPLIT];
        if (gl) return out;
        for (int i = 0; i < n; i++) {
            guns.roundStats(choices[i], ROUND);
            out[i] = AmmoStats.of(base, wear, split, ROUND[NtmGuns.S_DAMAGE_MULT], (int) ROUND[NtmGuns.S_PROJECTILES_MIN],
                    (int) ROUND[NtmGuns.S_PROJECTILES_MAX], ROUND[NtmGuns.S_HEADSHOT_MULT], ROUND[NtmGuns.S_THRESHOLD_NEGATION],
                    ROUND[NtmGuns.S_ARMOR_PIERCING]);
        }
        return out;
    }

    /** the round the gun in the main hand has as its magazine's type with the holder's inventory, 0 for none */
    public static int current(Body holder) {
        return GUNS.loadedRound(holder, Body.MAIN_HAND);
    }
}
