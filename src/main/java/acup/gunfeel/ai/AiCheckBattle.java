package acup.gunfeel.ai;

import java.util.Locale;

import net.minecraft.server.level.ServerLevel;

/**
 * The AI check's battle: AiCheckTeams' battle {@value #ROUNDS} times over, its marks (cover changes a fighter a minute,
 * shots from a crouch, lying down too briefly, looks out of one place) held to over them all. One battle is 10 to 180
 * seconds and 10 to 100 shots: its cover changes a fighter a minute came out 0.3 to 4.2 with nothing changed, and
 * none of its shots from a crouch one time in three. The cover changes are counted a minute of fighting, not of living.
 */
final class AiCheckBattle {
    static final int ROUNDS = 3;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private int round = -1;
    private AiCheckTeams now;
    private int brief, peekRun, shifts, shotsAll, shotsLow, coverShots;
    private long aliveTicks, fightTicks;

    AiCheckBattle(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        if (now == null) {
            round++;
            now = new AiCheckTeams(check, level, y, true);
            now.series = true;
        }
        if (!now.tick()) return false;
        brief += now.briefOut;
        peekRun = Math.max(peekRun, now.peekRunOut);
        shifts += now.shiftsOut;
        aliveTicks += now.aliveTicksOut;
        fightTicks += now.fightTicksOut;
        shotsAll += now.shotsAll;
        shotsLow += now.shotsLow;
        coverShots += now.coverShots;
        now = null;
        if (round + 1 < ROUNDS) return false;
        // a fighter a minute of its fight (on the way to cover, in it, out of it firing): one that waits for a target out
        // of sight, looks for it or never met it has no cover to change, and since a hit kills that is half its time and more
        double minutes = Math.max(1, fightTicks) / 1200.0, lived = Math.max(1, aliveTicks) / 1200.0;
        check.check("the battle: no lying down for less than " + AiProne.BRIEF / 20 + " s (" + brief + " times in " + ROUNDS + " battles)",
                brief <= AiCheckTeams.MARK_BRIEF);
        check.check("the battle: no looking out from one place more than " + AiCheckTeams.MARK_PEEK_RUN + " times running (" + peekRun + ")",
                peekRun <= AiCheckTeams.MARK_PEEK_RUN);
        check.check(String.format(Locale.ROOT, "the battle: cover changed %.0f times a fighter a minute of its fight at least (%.2f: %d in %d battles,"
                + " %.1f minutes fought of %.1f lived: %.2f a minute lived)", AiCheckTeams.MARK_SHIFTS, shifts / minutes, shifts, ROUNDS, minutes, lived, shifts / lived),
                shifts / minutes >= AiCheckTeams.MARK_SHIFTS);
        check.check("the battle: fires from a crouch where its line is clear (" + shotsLow + " of " + shotsAll + " shots), never into its own cover ("
                + coverShots + ")", shotsLow > 0 && coverShots == 0);
        return true;
    }
}
