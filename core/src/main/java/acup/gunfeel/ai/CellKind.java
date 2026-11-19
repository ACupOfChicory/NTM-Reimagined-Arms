package acup.gunfeel.ai;

/**
 * What a block says of the place it bounds (stage 4.5b), as CellSource.kind gives it: ground as the world made it, a
 * block someone put there, or a sign that someone lives there. Which blocks are which the version reads (26.2:
 * AiBlockKinds, from the server's settings).
 */
public final class CellKind {
    private CellKind() {
    }

    public static final byte NEUTRAL = 0, NATURAL = 1, BUILT = 2, MARKER = 3;
}
