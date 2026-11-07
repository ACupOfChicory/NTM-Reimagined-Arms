package acup.gunfeel.ai;

import java.util.Locale;

/**
 * How well an AI mob shoots: the server's aiSkill key (recruit, regular, veteran; regular when unset). Every number
 * is a cause a person has too (research.md §1.1): it sees late, corrects its aim now and then, and each correction
 * lands off by a share of how far it moved, plus a floor; it pulls the trigger only when the spot it expects the
 * target at is on the barrel; a shot's kick it learns of late, but for the share it takes back at once. Nothing in here
 * misses on purpose.
 *
 * @param lag    ticks between a target being somewhere and the mob acting on it
 * @param period ticks between two corrections of the aim
 * @param share  a correction's error: this share of its size, at random
 * @param floor  and at least this much, degrees
 * @param gate   it fires while the expected spot is within this many half widths of a body from the barrel
 * @param absorb the share of a shot's kick it takes back as it comes (a trained shooter leans into the gun)
 * @param turn   the most it turns a tick, degrees
 * @param notice ticks a target must stay in sight close up and straight ahead before it is noticed
 * @param rhythm whether it reads a strafe's steady rhythm (a person who has watched a few swings)
 */
public record AiSkill(String name, int lag, int period, float share, float floor, float gate, float absorb, float turn, int notice,
        boolean rhythm) {
    public static final AiSkill RECRUIT = new AiSkill("recruit", 4, 6, 0.30F, 0.30F, 1.5F, 0F, 20F, 10, false);
    public static final AiSkill REGULAR = new AiSkill("regular", 3, 4, 0.20F, 0.15F, 1.0F, 0.3F, 30F, 8, true);
    public static final AiSkill VETERAN = new AiSkill("veteran", 2, 2, 0.12F, 0.08F, 0.8F, 0.6F, 30F, 6, true);
    public static final String KEY = "aiSkill";

    public static AiSkill of(String name) {
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "recruit" -> RECRUIT;
            case "veteran" -> VETERAN;
            default -> REGULAR;
        };
    }

    private static String lastText;
    private static AiSkill last = REGULAR;
    /** for the stands: this skill whatever the key says; null = the key */
    static @org.jspecify.annotations.Nullable AiSkill override;

    /** the server's (the file is read at most once a second) */
    public static AiSkill current() {
        if (override != null) return override;
        String t = acup.gunfeel.ReaimTimes.text(KEY, "regular");
        if (t != lastText) {
            last = of(t);
            lastText = t;
        }
        return last;
    }
}
