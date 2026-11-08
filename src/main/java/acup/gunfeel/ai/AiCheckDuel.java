package acup.gunfeel.ai;

import java.util.Locale;

import net.minecraft.server.level.ServerLevel;

/**
 * The AI check's duel: the battle (AiCheckTeams, its field) a few times over, FBI all with heavy revolvers and the
 * rounds they come with, five in squads against five alone (AiSquads off for them, as in stage 1), the sides swapped
 * round to round. Wins, the fighters left, hits, damage and shots each way, summed for the squads and for those
 * alone.
 */
final class AiCheckDuel {
    static final int ROUNDS = 6;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private int round = -1;
    private AiCheckTeams now;
    /** squads, alone: rounds won, fighters left, hits on the other side, shots; damage done */
    private final int[] won = new int[2], left = new int[2], hits = new int[2], shots = new int[2];
    private final double[] damage = new double[2];

    AiCheckDuel(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        if (now == null) {
            round++;
            now = new AiCheckTeams(check, level, y, true, round % 2 == 0 ? AiCheckTeams.BLUE : AiCheckTeams.RED);
        }
        if (!now.tick()) return false;
        boolean redAlone = AiCheckTeams.RED.equals(now.loners);
        int sq = redAlone ? now.blueLeft : now.redLeft, al = redAlone ? now.redLeft : now.blueLeft;
        left[0] += sq;
        left[1] += al;
        hits[0] += redAlone ? now.blueHitsOut : now.redHitsOut;
        hits[1] += redAlone ? now.redHitsOut : now.blueHitsOut;
        shots[0] += redAlone ? now.blueShots : now.redShots;
        shots[1] += redAlone ? now.redShots : now.blueShots;
        damage[0] += redAlone ? now.blueDamage : now.redDamage;
        damage[1] += redAlone ? now.redDamage : now.blueDamage;
        if (sq > 0 && al == 0) won[0]++;
        else if (al > 0 && sq == 0) won[1]++;
        now = null;
        if (round + 1 < ROUNDS) return false;
        check.out(String.format(Locale.ROOT, "     duel, %d rounds of %d on %d: squads won %d, alone won %d; fighters left: squads %d, alone %d;"
                + " hits on the other side: squads %d, alone %d; damage done: squads %.0f, alone %.0f; shots: squads %d, alone %d", ROUNDS, AiCheckTeams.SIDE,
                AiCheckTeams.SIDE, won[0], won[1], left[0], left[1], hits[0], hits[1], damage[0], damage[1], shots[0], shots[1]));
        // the wins are told, not held to: six rounds of a battle are too few to tell squads from loners for sure (the
        // squad stage does, on a field made for it); that both sides fought is
        check.check("the duel: squads and those alone both fought", hits[0] > 0 && hits[1] > 0);
        return true;
    }
}
