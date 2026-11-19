package acup.gunfeel.host;

/**
 * What core does to a body in the game: one per version (26.2: EntityBodyControl), each method one call of the game's
 * on the entity behind the Body. Reads stay on {@link Body}; the caller keeps the order the game's code had.
 */
public interface BodyControl {
    /**
     * Puts it in a stance ({@link acup.gunfeel.ai.Stance}: the game's pose STANDING, CROUCHING, or SWIMMING for PRONE,
     * the pose an AI mob lies down in); after it {@code b.stance()} is {@code stance}.
     */
    void stance(Body b, byte stance);
}
