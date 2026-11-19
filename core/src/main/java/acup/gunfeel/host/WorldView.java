package acup.gunfeel.host;

/**
 * A level's blocks as core asks for them: the state at a place as the {@link BlockTable}'s number, its facts and key
 * the table's. One per level, given by the version's wiring into what works in that level (as the water's liquids);
 * the numbers are the one table's, the same in every level.
 */
public interface WorldView extends BlockTable {
    /** the block state at the place, as {@link BlockTable}'s number */
    int state(int x, int y, int z);
}
