package acup.gunfeel.gas;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The mustard left on the floor of a level: a contamination G for each floor cell (the air cell above it, by block
 * position as a long), which dries away in {@link GasRules#MUSTARD_FLOOR_DRY_SECONDS}. Not saved: a world loaded again
 * has a clean floor. At most {@link GasRules#MUSTARD_FLOOR_CELLS} cells; a cloud over more than that marks no more.
 * Common code, no game classes.
 */
public final class GasFloor {
    private final Map<Long, Float> cells = new HashMap<>();
    private final int limit;

    public GasFloor() {
        this(GasRules.MUSTARD_FLOOR_CELLS);
    }

    public GasFloor(int limit) {
        this.limit = limit;
    }

    /** Marks a cell as contaminated to at least {@code g}; false if the table is full and the cell is a new one. */
    public boolean mark(long cell, float g) {
        Float old = cells.get(cell);
        if (old == null) {
            if (cells.size() >= limit) return false;
            cells.put(cell, g);
        } else if (old < g) cells.put(cell, g);
        return true;
    }

    public float at(long cell) {
        Float g = cells.get(cell);
        return g == null ? 0F : g;
    }

    public int size() {
        return cells.size();
    }

    /** Dries the floor for that many ticks. */
    public void dry(int ticks) {
        float by = GasRules.MUSTARD_FLOOR_G / (GasRules.MUSTARD_FLOOR_DRY_SECONDS * 20F) * ticks;
        for (Iterator<Map.Entry<Long, Float>> it = cells.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Float> e = it.next();
            float g = e.getValue() - by;
            if (g <= 0.0005F) it.remove();
            else e.setValue(g);
        }
    }
}
