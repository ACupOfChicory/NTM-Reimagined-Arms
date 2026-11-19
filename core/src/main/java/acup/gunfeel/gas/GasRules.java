package acup.gunfeel.gas;

/**
 * Every number of what the gases do, in one place to tune (gas-balance-ideas.md section 5, with the owner's answers:
 * every gas can kill, phosgene has no icon). Seconds and HP a second unless it says ticks. A dose is "seconds of thick
 * gas" (C = 1 for a second is 1).
 */
public final class GasRules {
    private GasRules() {
    }

    // ---- protection: a mask cuts this share of the gas, 0.95 with a good filter, less as it runs out
    public static final float PROTECT_FULL = 0.95F, PROTECT_WORN = 0.70F;
    /** Below this share of the filter left the protection falls linearly to {@link #PROTECT_WORN}. */
    public static final float FILTER_WEAR_FROM = 0.25F;
    /** Mustard needs a sealed or hazmat helmet with a filter and a hazmat suit; each part short halves it. */
    public static final float MUSTARD_PART_MISSING = 0.5F;

    // ---- chlorine
    /** Dose where the cough, the slow-down and the no-sprint start and end. */
    public static final float CHLORINE_COUGH_DOSE = 0.2F;
    /** A cough every this many ticks, 1.5 to 3 s. */
    public static final int COUGH_MIN = 30, COUGH_MAX = 60;
    /** How long a cough lasts, ticks (no aiming, the camera shakes). */
    public static final int COUGH_TICKS = 14;
    public static final float CHLORINE_SLOW = 0.15F;
    public static final float CHLORINE_DAMAGE_DOSE = 2F;
    public static final float CHLORINE_DAMAGE = 0.7F;
    public static final float CHLORINE_DECAY = 0.5F;
    /** The cough blocks aiming (server key {@code gasCoughBlocksAim}, on by default). */
    public static final String COUGH_AIM_KEY = "gasCoughBlocksAim";

    // ---- phosgene
    public static final float PHOSGENE_SMELL_DOSE = 1F;
    public static final int PHOSGENE_LATENT_TICKS = 15 * 20;
    public static final float PHOSGENE_DAMAGE_PER_DOSE = 0.2F;
    /** Share of the dose that goes a second once the lungs swell. */
    public static final float PHOSGENE_DECAY = 0.10F;
    /** A dose that never reached the smell goes slowly. */
    public static final float PHOSGENE_IDLE_DECAY = 0.02F;
    public static final float PHOSGENE_END_DOSE = 0.05F;
    public static final float PHOSGENE_MILK = 0.5F;
    public static final float PHOSGENE_SLOW = 0.10F;

    // ---- mustard
    /** Share of the floor's contamination that counts as air a second. */
    public static final float MUSTARD_FLOOR_WEIGHT = 0.6F;
    public static final float MUSTARD_FLOOR_G = 0.5F;
    public static final float MUSTARD_FLOOR_DRY_SECONDS = 180F;
    /** Cells of contaminated floor a server keeps at most. */
    public static final int MUSTARD_FLOOR_CELLS = 4096;
    public static final float MUSTARD_DECAY = 0.15F;
    /** Dose levels and what each does: HP a second. */
    public static final float MUSTARD_L1 = 2F, MUSTARD_L2 = 5F, MUSTARD_L3 = 9F;
    public static final float MUSTARD_DAMAGE_1 = 0.2F, MUSTARD_DAMAGE_2 = 0.4F, MUSTARD_DAMAGE_3 = 0.7F;

    // ---- acid
    public static final float ACID_K_GAIN = 0.20F, ACID_K_MAX = 0.75F, ACID_K_DECAY = 0.01F, ACID_K_DECAY_WATER = 0.10F;
    /** Burn a second at C = 1 for each of the four slots that is not covered. */
    public static final float ACID_BURN_PER_SLOT = 0.05F;
    public static final float ACID_DECAY = 0.5F;

    // ---- the lungs: NTM's chronic black lung (HbmLivingProps), units of NTM's counter per dose (it holds up to 144000)
    public static final int BLACK_LUNG_PER_DOSE = 500;

    // ---- the server
    /** Ticks between the damage lumps (a hurt every tick would flash and stack up the damage cooldown). */
    public static final int HURT_EVERY = 10;
    /** Ticks between the cloud scans that mark the floor under a mustard cloud. */
    public static final int FLOOR_EVERY = 10;
    /** Concentration of a cell where a mustard cloud marks the floor under it. */
    public static final float FLOOR_MARK_C = 0.3F;
}
