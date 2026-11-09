package acup.gunfeel.ai;

import java.util.Locale;

import net.minecraft.server.level.ServerLevel;

/**
 * The AI check's retreat (AiSoldier.fallBack, stage3-plan 3.3): the duel's battle (AiCheckTeams), a squad against
 * fighters alone, the squad shaken at once a moment into the fight (its confidence to the bottom: it falls back) and the
 * round over a while later. Four settings, {@link #ROUNDS} rounds each, the sides swapped round to round: falling back
 * with none waiting for cover; in waves (one goes while one of its squad covers it); in waves with those in cover
 * looking out early to fire for it; that and smoke first (AiSmoke, 3.3b). Told: the falls back, the ticks on the way and
 * the share of them with one of its side near firing and seen by a foe, hits on the way a hundred ticks, those down on
 * the way, the squad's fighters lost, the ground it gained; the smoke grenades thrown and their clouds. Held to: it
 * falls back in each setting; smoke thrown, and its clouds let out, in the last only. No frag grenades (as the stages
 * but the grenades' and the battles'): they would move the squads as much as a fall back does.
 */
final class AiCheckRetreat {
    static final int ROUNDS = 6;
    static final String[] NAMES = {"all at once", "in waves", "in waves, covered by fire", "in waves, covered by fire, smoke first"};
    static final int SETTINGS = NAMES.length;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private int round = -1;
    private AiCheckTeams now;
    /** per setting: falls back, ticks on the way, of them covered, hits and downs on the way, fighters at the shake, left */
    private final int[] backs = new int[SETTINGS], way = new int[SETTINGS], covered = new int[SETTINGS], seen = new int[SETTINGS], hits = new int[SETTINGS],
            downs = new int[SETTINGS], then = new int[SETTINGS], left = new int[SETTINGS];
    private final double[] gained = new double[SETTINGS];
    private final int[] shook = new int[SETTINGS], gainedRounds = new int[SETTINGS], smokes = new int[SETTINGS], clouds = new int[SETTINGS];
    private int smokesAt, cloudsAt;

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
            AiSmoke.on = set > 2;
            smokesAt = AiSmoke.thrown;
            cloudsAt = AiSmoke.clouds;
            boolean redShaken = round % 2 == 0;
            now = new AiCheckTeams(check, level, y, true, redShaken ? AiCheckTeams.BLUE : AiCheckTeams.RED, redShaken ? AiCheckTeams.RED : AiCheckTeams.BLUE);
        }
        if (!now.tick()) return false;
        int set = setting(round);
        backs[set] += now.shakenBacks;
        way[set] += now.backTicks;
        covered[set] += now.backCovered;
        seen[set] += now.backSeen;
        smokes[set] += AiSmoke.thrown - smokesAt;
        clouds[set] += AiSmoke.clouds - cloudsAt;
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
        if (round + 1 < SETTINGS * ROUNDS) return false;
        AiSoldier.waves = AiSoldier.coverFire = AiSmoke.on = true;
        for (int s = 0; s < SETTINGS; s++)
            check.out(String.format(Locale.ROOT, "     retreat, %s: shaken in %d of %d rounds; falls back %d; ticks on the way %d, covered %.0f%%, seen by a foe %.0f%%;"
                    + " hit on the way %d (%.1f a hundred ticks), down on the way %d; fighters lost after the shake %d of %d; ground gained %.1f blocks a round;"
                    + " smoke grenades %d, clouds %d",
                    NAMES[s], shook[s], ROUNDS, backs[s], way[s], way[s] == 0 ? 0 : 100.0 * covered[s] / way[s], way[s] == 0 ? 0 : 100.0 * seen[s] / way[s], hits[s],
                    way[s] == 0 ? 0 : 100.0 * hits[s] / way[s], downs[s], then[s] - left[s], then[s], gainedRounds[s] == 0 ? 0 : gained[s] / gainedRounds[s],
                    smokes[s], clouds[s]));
        StringBuilder why = new StringBuilder("     retreat: falls back with no smoke, by why:");
        for (int k = 0; k < AiSmoke.WHY.length; k++) why.append(k == 0 ? " " : ", ").append(AiSmoke.WHY[k]).append(' ').append(AiSmoke.skipped[k]);
        check.out(why.toString());
        boolean all = true;
        for (int s = 0; s < SETTINGS; s++) all &= backs[s] > 0;
        check.check("the retreat: a shaken squad falls back, each way", all);
        check.check("the retreat: smoke thrown falling back, and its clouds let out, only where it may be",
                smokes[0] + smokes[1] + smokes[2] == 0 && smokes[3] > 0 && clouds[3] > 0);
        return true;
    }
}
