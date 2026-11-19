package acup.gunfeel;

/**
 * The player's main hand as the game's options have it: the hold of a pistol (PistolPlace.on) is for the right one
 * only. On 26.2 {@code GameMainHand} reads the client's options; without a client (a server, core's own tests) the
 * main hand is the right one.
 */
public interface MainHand {
    /** The main hand is the left one. */
    boolean left();

    /** The version's. */
    static MainHand get() {
        return MainHandFound.HAND;
    }
}

/** Looked up once, the first time it is asked. */
final class MainHandFound {
    static final MainHand HAND = CoreServices.load(MainHand.class, () -> false);

    private MainHandFound() {
    }
}
