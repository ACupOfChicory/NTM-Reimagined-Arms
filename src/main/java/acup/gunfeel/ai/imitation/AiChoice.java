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
