package acup.gunfeel;

import java.util.Arrays;

/**
 * The blocks a bullet pierced this tick, in order along its path, and how many of them it has paid for (PierceSteps on
 * each version keeps the bullet and the game's hits): each block with its distance from the tick's start, the
 * bullet's damage and the penetration spent before it, and its way in and out ({@code H}, the version's hit). A
 * creature met at a distance: the blocks from the first one not before it on were paid too early ({@link #paidPast})
 * and are owed again ({@link #unpayTo}), or the ones before it are still owed ({@link #payNext} up to it).
 */
public final class PierceLedger<H> {
    private double[] at = new double[4], spent = new double[4];
    private float[] before = new float[4];
    private Object[] in = new Object[4], out = new Object[4];
    private int size, paid;

    /** a new tick: no blocks, none paid */
    public void clear() {
        Arrays.fill(in, 0, size, null);
        Arrays.fill(out, 0, size, null);
        size = 0;
        paid = 0;
    }

    /** a block pierced at {@code distance}, paid for at once: the damage was {@code damageBefore} */
    public void pierced(double distance, float damageBefore, double spentBefore, H wayIn, H wayOut) {
        if (size == at.length) {
            int n = size * 2;
            at = Arrays.copyOf(at, n);
            spent = Arrays.copyOf(spent, n);
            before = Arrays.copyOf(before, n);
            in = Arrays.copyOf(in, n);
            out = Arrays.copyOf(out, n);
        }
        at[size] = distance;
        before[size] = damageBefore;
        spent[size] = spentBefore;
        in[size] = wayIn;
        out[size] = wayOut;
        size++;
        paid = size;
    }

    public int size() {
        return size;
    }

    /** the first block not before {@code distance} (its distance not below it), or the count when there is none */
    public int firstFrom(double distance) {
        int k = 0;
        while (k < size && at[k] < distance) k++;
        return k;
    }

    /** whether blocks from {@code k} on are paid for */
    public boolean paidPast(int k) {
        return paid > k;
    }

    /** blocks from {@code k} on are owed again */
    public void unpayTo(int k) {
        paid = k;
    }

    /** whether blocks before {@code k} are owed */
    public boolean owes(int k) {
        return paid < k;
    }

    /** the first block owed, now paid: its index */
    public int payNext() {
        return paid++;
    }

    public float damageBefore(int k) {
        return before[k];
    }

    public double spentBefore(int k) {
        return spent[k];
    }

    @SuppressWarnings("unchecked")
    public H wayIn(int k) {
        return (H) in[k];
    }

    @SuppressWarnings("unchecked")
    public H wayOut(int k) {
        return (H) out[k];
    }
}
