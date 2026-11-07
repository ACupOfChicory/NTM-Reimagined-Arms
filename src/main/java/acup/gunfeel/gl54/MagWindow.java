package acup.gunfeel.gl54;

import java.util.ArrayList;
import java.util.List;

// The rules of the magazine window on plain lists (tested without the game). A magazine holds up to 8 grenades, the
// remaining list with the first to fire at index 0. The window shows 8 cells in a row, the leftmost the magazine's mouth:
// the grenades are pressed to the RIGHT with no gaps (n grenades leave 8 - n empty cells on the left), the leftmost grenade
// fires first, remaining[0] is in cell 8 - n. A grenade put in goes against the stack and so becomes remaining[0] (last in,
// first out); only the leftmost grenade comes out. One shift-click moves exactly one grenade, either way.
public final class MagWindow {
    public static final int CELLS = MagContents.CAPACITY;

    private MagWindow() {
    }

    // the cells, left to right: a key or "" for an empty cell
    public static List<String> cells(List<String> remaining) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < CELLS - remaining.size(); i++) out.add("");
        out.addAll(remaining);
        return out;
    }

    // the remaining list of any layout of cells: the grenades in cell order, gaps closed
    public static List<String> compact(List<String> cells) {
        List<String> out = new ArrayList<>();
        for (String c : cells) if (!c.isEmpty()) out.add(c);
        return out;
    }

    public static boolean canInsert(List<String> remaining, String key) {
        return key != null && !key.isEmpty() && remaining.size() < CELLS;
    }

    // one grenade in: it becomes remaining[0]; unchanged when full or when it is no grenade
    public static List<String> insert(List<String> remaining, String key) {
        if (!canInsert(remaining, key)) return remaining;
        List<String> out = new ArrayList<>();
        out.add(key);
        out.addAll(remaining);
        return out;
    }

    // the index of the cell the next grenade goes in (the empty cell next to the stack), -1 when full
    public static int insertCell(List<String> remaining) {
        return remaining.size() >= CELLS ? -1 : CELLS - remaining.size() - 1;
    }

    // the cell of the grenade that can be taken out (the leftmost), -1 when empty
    public static int takeCell(List<String> remaining) {
        return remaining.isEmpty() ? -1 : CELLS - remaining.size();
    }

    public static String first(List<String> remaining) {
        return remaining.isEmpty() ? "" : remaining.get(0);
    }

    // one grenade out: the rest after taking remaining[0]
    public static List<String> take(List<String> remaining) {
        return remaining.isEmpty() ? remaining : new ArrayList<>(remaining.subList(1, remaining.size()));
    }
}
