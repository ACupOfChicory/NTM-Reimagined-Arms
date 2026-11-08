package acup.gunfeel.ai.imitation;

import java.util.List;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ai.AiBot;
import acup.gunfeel.ai.CoverQuery;

/**
 * Who chooses a soldier's next step where its formula decides it (the contract with AiSoldier:
 * notes/ai/imitation-contract.md). AiSoldier asks at each such place with what its formula says and does what the
 * answer says. With no chooser set (the server key aiImitation off, the default) the answer is the formula's.
 * Server thread.
 */
public final class AiChoice {
    /** the places it asks at */
    public enum Ask {
        /** in high cover in a fight, ready: out to its peek now? (formula: its hide is over) */
        PEEK_FIGHT,
        /** in cover from a target lost from sight: look out where they were now? */
        LOOK_LOST,
        /** in high cover from a noise: look out the way it came from now? */
        LOOK_NOISE,
        /** out at its peek: back into cover now? (formula: its burst is over) */
        BACK_IN,
        /** back from a peek: other cover? */
        SHIFT_AFTER_PEEK,
        /** shot at in cover with the shooter in sight: other cover? */
        SHIFT_ON_HIT,
        /** hit in cover from out of sight: other cover? */
        HIT_MOVE,
        /** in cover a while: look for a better place now? */
        BETTER_COVER,
        /** a target lost and looked for: go for them now? */
        PUSH_NOW
    }

    @FunctionalInterface
    public interface Chooser {
        boolean choose(AiBot bot, Ask ask, boolean formula, long tick);

        /** which of the spots the cover query found (best score first) it takes; the formula's is 0 */
        default int rank(AiBot bot, List<CoverQuery.Spot> spots, long tick) {
            return 0;
        }
    }

    private static volatile @Nullable Chooser chooser;

    private AiChoice() {
    }

    /** null: the formula decides everywhere */
    public static void set(@Nullable Chooser c) {
        chooser = c;
    }

    public static boolean ask(AiBot bot, Ask ask, boolean formula, long tick) {
        Chooser c = chooser;
        return c == null ? formula : c.choose(bot, ask, formula, tick);
    }

    /** which of the spots (not empty, best score first) it takes; an answer out of range is the formula's, 0 */
    public static int rank(AiBot bot, List<CoverQuery.Spot> spots, long tick) {
        Chooser c = chooser;
        if (c == null || spots.size() < 2) return 0;
        int i = c.rank(bot, spots, tick);
        return i < 0 || i >= spots.size() ? 0 : i;
    }
}
