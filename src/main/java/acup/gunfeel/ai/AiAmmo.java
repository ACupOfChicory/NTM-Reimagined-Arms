package acup.gunfeel.ai;

/**
 * The rounds an AI mob carries besides what is in its gun. NTM fills a mob's gun from nothing when it reloads; the
 * mob AI counts what went in against this reserve and takes back what the reserve did not have. A mob that carries
 * endless rounds (one that hands them out to others, later) has an infinite reserve; one that runs dry can be given
 * more.
 */
public final class AiAmmo {
    public static final int INFINITE = -1;

    private int reserve;
    /** rounds in the gun when it was last looked at, -1 before */
    int lastLoaded = -1;
    /** rounds taken from the reserve into the gun, for the stands */
    public int loadedTotal;

    public AiAmmo(int reserve) {
        this.reserve = reserve;
    }

    public boolean infinite() {
        return reserve == INFINITE;
    }

    /** rounds left besides the gun's, INFINITE for endless */
    public int reserve() {
        return reserve;
    }

    public boolean any() {
        return reserve != 0;
    }

    /** someone hands rounds over */
    public void give(int rounds) {
        if (reserve != INFINITE && rounds > 0) reserve += rounds;
    }

    public void setInfinite() {
        reserve = INFINITE;
    }

    /** a count of rounds, endless no more */
    public void set(int rounds) {
        reserve = Math.max(0, rounds);
    }

    /**
     * the gun went from lastLoaded to loaded rounds: what it may keep. A rise is a reload, paid from the reserve, and
     * what the reserve could not pay is taken back out; a fall is shots.
     */
    int settle(int loaded) {
        int before = lastLoaded;
        int keep = loaded;
        if (before >= 0 && loaded > before && reserve != INFINITE) {
            int added = loaded - before;
            int paid = Math.min(added, reserve);
            reserve -= paid;
            keep = before + paid;
        }
        if (before >= 0 && keep > before) loadedTotal += keep - before;
        lastLoaded = keep;
        return keep;
    }
}
