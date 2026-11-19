package acup.gunfeel;

/**
 * The mod's logger, the "ntm-gunfeel" one, for core's code. Where its lines go (the mod's own file next to the game's
 * log) is the version's business: on 26.2 {@code DebugLog} sets that up and hands out this same logger.
 */
public final class ModLog {
    public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ntm-gunfeel");

    private ModLog() {
    }
}
