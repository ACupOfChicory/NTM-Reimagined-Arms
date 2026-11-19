package acup.gunfeel.gas;

/**
 * What the gases have done to one living thing: a dose for each gas (by {@link GasKind#ordinal()}) and the acid's
 * corrosion K, the phosgene's clock, and the little the model carries between ticks. The dose, K and the phosgene's
 * clock are saved with the entity (the attachment, its codec in {@code GasAttach}); the rest is the server's working
 * and starts afresh. Common code, no game classes.
 */
public final class GasState {
    public static final int N = GasKind.values().length;
    public static final int CHLORINE = GasKind.CHLORINE.ordinal(), PHOSGENE = GasKind.PHOSGENE.ordinal(),
            MUSTARD = GasKind.MUSTARD.ordinal(), ACID = GasKind.ACID.ordinal();

    // saved
    public final float[] dose = new float[N];
    /** The acid's corrosion of the armour, 0 to {@link GasRules#ACID_K_MAX}. */
    public float k;
    /** Phosgene: ticks left of the quiet before the lungs swell; 0 while they are swollen; -1 before the smell. */
    public int latent = -1;
    public boolean smelled;

    // the working
    public float pending;
    public int cough, nextCough, hurtIn;
    public final float[] wear = new float[N];
    float lung;

    // what the last tick found, for the server to act on
    public float slow;
    public boolean noSprint, noRegen, nausea, darkness, coughNow, smellNow;
    /** Units of NTM's black lung to add. */
    public int blackLung;
    /** HP of damage the gases have done in all (for the stand). */
    public float dealt;
    /** Units of filter wear put on masks in all (for the stand). */
    public int worn;

    /** Whether this is worth saving: a dose, a corrosion or a phosgene clock still running. */
    public boolean persists() {
        for (float d : dose) if (d > 0F) return true;
        return k > 0F || latent >= 0;
    }

    /** Whether there is anything left of the gases in this one (a state with nothing is dropped). */
    public boolean active() {
        for (float d : dose) if (d > 0F) return true;
        return k > 0F || latent >= 0 || pending > 0F || cough > 0;
    }
}
