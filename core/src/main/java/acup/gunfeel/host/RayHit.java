package acup.gunfeel.host;

/**
 * Where the game's ray ({@link WorldView#clip}) ended, as its BlockHitResult tells it: a block hit or a miss, the
 * point (a miss's: the ray's end), the face it came in by (the game's Direction by its ordinal: DOWN, UP, NORTH, SOUTH,
 * WEST, EAST; a miss's: the one nearest the way back to the start, as the game gives it), the block's place (a miss's:
 * the end's block), whether it began inside the block. One is kept and filled again by each ray asked into it.
 */
public final class RayHit {
    public boolean block;
    public double x, y, z;
    public int face;
    public int bx, by, bz;
    public boolean inside;
}
