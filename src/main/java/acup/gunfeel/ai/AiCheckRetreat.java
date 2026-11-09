package acup.gunfeel.ai;

import java.util.Locale;

import net.minecraft.server.level.ServerLevel;

/**
 * The AI check's retreat (AiSoldier.fallBack, stage3-plan 3.3): the duel's battle (AiCheckTeams), a squad against
 * fighters alone, the squad shaken at once a moment into the fight (its confidence to the bottom: it falls back) and the
 * round over a while later. Three settings, {@link #ROUNDS} rounds each, the sides swapped round to round: falling back
 * with none waiting for cover; in waves (one goes while one of its squad covers it); in waves with those in cover
 * looking out early to fire for it. Told: the falls back, the ticks on the way and the share of them with one of its
 * side near firing, hits on the way a hundred ticks, those down on the way, the squad's fighters lost, the ground it
 * gained. Held to: it falls back in each setting. No grenades (as the stages but the grenades' and the battles'):
 * they would move the squads as much as a fall back does.
 */
final class AiCheckRetreat {
    static final int ROUNDS = 6;
    static final String[] NAMES = {"all at once", "in waves", "in waves, covered by fire"};

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private int round = -1;
    private AiCheckTeams now;
    /** per setting: falls back, ticks on the way, of them covered, hits and downs on the way, fighters at the shake, left */
    private final int[] backs = new int[3], way = new int[3], covered = new int[3], hits = new int[3], downs = new int[3], then = new int[3], left = new int[3];
    private final double[] gained = new double[3];
    private final int[] shook = new int[3], gainedRounds = new int[3];

    AiCheckRetreat(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    private static int setting(int round) {
        return round / ROUNDS;
    }

    boolean tick() {
        if (now == null) {
            round++;
            int set = setting(round);
            AiSoldier.waves = set > 0;
            AiSoldier.coverFire = set > 1;
            boolean redShaken = round % 2 == 0;
            now = new AiCheckTeams(check, level, y, true, redShaken ? AiCheckTeams.BLUE : AiCheckTeams.RED, redShaken ? AiCheckTeams.RED : AiCheckTeams.BLUE);
        }
        if (!now.tick()) return false;
        int set = setting(round);
        backs[set] += now.shakenBacks;
        way[set] += now.backTicks;
        covered[set] += now.backCovered;
        hits[set] += now.backHits;
        downs[set] += now.backDowns;
        if (now.shakenAt >= 0) {
            shook[set]++;
            then[set] += now.shakenThen;
            left[set] += now.shakenLeft;
            // the ground gained, by those of it left at the end
            if (now.shakenLeft > 0) {
                gained[set] += now.shakenFarEnd - now.shakenFar;
                gainedRounds[set]++;
            }
        }
        now = null;
        if (round + 1 < 3 * ROUNDS) return false;
        AiSoldier.waves = AiSoldier.coverFire = true;
        for (int s = 0; s < 3; s++)
            check.out(String.format(Locale.ROOT, "     retreat, %s: shaken in %d of %d rounds; falls back %d; ticks on the way %d, covered %.0f%%;"
                    + " hit on the way %d (%.1f a hundred ticks), down on the way %d; fighters lost after the shake %d of %d; ground gained %.1f blocks a round",
                    NAMES[s], shook[s], ROUNDS, backs[s], way[s], way[s] == 0 ? 0 : 100.0 * covered[s] / way[s], hits[s], way[s] == 0 ? 0 : 100.0 * hits[s] / way[s],
                    downs[s], then[s] - left[s], then[s], gainedRounds[s] == 0 ? 0 : gained[s] / gainedRounds[s]));
        check.check("the retreat: a shaken squad falls back, each way", backs[0] > 0 && backs[1] > 0 && backs[2] > 0);
        return true;
    }
}
