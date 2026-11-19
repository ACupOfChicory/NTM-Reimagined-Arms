package acup.gunfeel.gas;

/**
 * The gas's sum on the ground, by biome cell (4 by 4 blocks across), that turns the ground into the poisoned biome:
 * the density of every floor cell under the gas times the ticks it lay there times the gas's {@link #weight},
 * averaged over the cell's 16 columns, halving every {@link #HALF_LIFE} ticks; the cell turns at {@link #THRESHOLD}.
 * The numbers only; the version's adapter reads the floor and paints the biome (26.2: {@code GasPoison}, which tells
 * what each gas's weight stands for).
 */
public final class GasSoil {
    /** Density times ticks, averaged over a biome cell's floor, at which it turns. */
    public static final float THRESHOLD = 2000F;
    public static final int HALF_LIFE = 12000;
    /** Ticks between two sums. */
    public static final int EVERY = 20;
    /** A floor cell with less in it adds nothing. */
    public static final float LEAST = 0.05F;

    private GasSoil() {
    }

    /** How hard a gas is on the ground, what its density counts for in the sum. */
    public static float weight(GasKind g) {
        return switch (g) {
            case MUSTARD -> 2.0F;
            case CHLORINE -> 0.6F;
            case ACID -> 0.7F;
            case PHOSGENE -> 0.5F;
        };
    }

    /** The key of a biome cell by its coordinates in biome cells (a block's divided by 4, rounded down). */
    public static long key(int qx, int qz) {
        return (long) qx << 32 | qz & 0xFFFFFFFFL;
    }

    /** A sum {@code ticks} after it was last added to. */
    public static float decayed(float sum, long ticks) {
        return ticks <= 0 ? sum : (float) (sum * Math.pow(0.5, ticks / (double) HALF_LIFE));
    }
}
