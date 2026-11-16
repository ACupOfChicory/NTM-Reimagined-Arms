package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The AI check's alert at close range: two teams of three, 14 blocks apart in the open, see each other and fight for
 * FOUGHT ticks; then a stone wall three high goes up between them, open at both ends. Counted, AFTER ticks: what each
 * does once its foe is gone behind the wall (its mode, why, pose, whether it moves), and where it looks: at an end of
 * the wall (where the foe may come out), at the wall, or elsewhere; the tick it first stands still with nothing to do;
 * the ticks a team has an end, both ends, watched. Then a sentry: one soldier alone, no one about, SENTRY_TICKS: how far
 * round its head looks, and how fast it turns.
 */
final class AiCheckAlert {
    static final int X0 = 4000, Z0 = 0, SIZE = 48, FOUGHT = 60, AFTER = 600, ROUNDS = 4, SIDE = 3, BUCKET = 60;
    /** the teams' rows and the wall between them */
    static final double RED_Z = 10.5, BLUE_Z = 24.5;
    static final int WALL_Z = 17, WALL_X0 = 4012, WALL_X1 = 4036;
    static final double[] XS = {4020.5, 4024.5, 4028.5};
    /** an end of the wall watched: the head within this of the way to it, degrees */
    static final double EDGE_DEG = 20;
    static final Identifier RIFLE = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");
    static final String RED = "alert_red", BLUE = "alert_blue";

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started, walled;
    private int round = -1, ticks;
    private final List<Mob> mobs = new ArrayList<>();
    /** after the wall, per bucket of BUCKET ticks, summed over the fighters: standing still (and not looking at an end), crouching, moving, looking at an end */
    private int[][] buckets;
    private final Map<String, Integer> whys = new TreeMap<>(), modes = new TreeMap<>(), looks = new TreeMap<>();
    /** the tick (after the wall) each first stood still, not looking at an end, for 20 ticks running; -1 never */
    private int[] stillFrom, stillRun;
    private final List<String> rounds = new ArrayList<>();
    private final int[][] total = new int[4][];

    AiCheckAlert(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        if (!started) {
            started = true;
            for (int cx = X0 >> 4; cx <= (X0 + SIZE) >> 4; cx++)
                for (int cz = Z0 >> 4; cz <= (Z0 + SIZE) >> 4; cz++) level.setChunkForced(cx, cz, true);
            AiBots.awake = true;
            start(0);
            return false;
        }
        if (round == ROUNDS) return sentryTick();
        ticks++;
        for (Mob m : mobs) m.setHealth(m.getMaxHealth());
        if (!walled && ticks >= FOUGHT) {
            walled = true;
            BlockState stone = Blocks.STONE.defaultBlockState();
            for (int x = WALL_X0; x <= WALL_X1; x++)
                for (int h = 0; h < 3; h++) set(x, y + h, WALL_Z, stone);
            ticks = 0;
            return false;
        }
        if (!walled) return false;
        int bucket = Math.min(buckets.length - 1, (ticks - 1) / BUCKET);
        int[] watched = new int[2];
        String[] doing = new String[mobs.size()];
        for (int i = 0; i < mobs.size(); i++) {
            Mob m = mobs.get(i);
            AiBot b = AiBots.get(m);
            if (b == null || !(b.brain instanceof AiSoldier s)) continue;
            boolean crouch = m.getPose() == Pose.CROUCHING, moving = m.getDeltaMovement().horizontalDistanceSqr() > 0.0004;
            String look = look(m);
            int end = endOf(m);
            doing[i] = s.mode() + "/" + (end >= 0 ? "end" + end : look);
            if (end >= 0) watched[i < SIDE ? 0 : 1] |= 1 << end;
            boolean still = !crouch && !moving && !look.equals("end") && m.getPose() == Pose.STANDING;
            if (still) buckets[bucket][0]++;
            if (crouch) buckets[bucket][1]++;
            if (moving) buckets[bucket][2]++;
            if (look.equals("end")) buckets[bucket][3]++;
            modes.merge(String.valueOf(s.mode()), 1, Integer::sum);
            whys.merge(String.valueOf(s.why), 1, Integer::sum);
            looks.merge(look, 1, Integer::sum);
            stillRun[i] = still ? stillRun[i] + 1 : 0;
            if (stillRun[i] == 20 && stillFrom[i] < 0) stillFrom[i] = ticks - 19;
        }
        for (int t = 0; t < 2; t++) {
            int w = watched[t];
            if (w != 0) teamAny++;
            if (w == 3) teamBoth++;
            else for (int i = t * SIDE; i < (t + 1) * SIDE && i < doing.length; i++) if (doing[i] != null) notBoth.merge(doing[i], 1, Integer::sum);
        }
        // what each chose to watch, a while after the wall: went (where the foe went), W / E (the way out past the west or
        // east end), went? (a way out chosen, none found: where they went), - (no loss chosen for)
        if (ticks == CHOICE_AT) {
            StringBuilder c = new StringBuilder();
            for (int i = 0; i < mobs.size(); i++) {
                if (i == SIDE) c.append(" |");
                AiBot b = AiBots.get(mobs.get(i));
                String k = "-";
                if (b != null && b.brain instanceof AiSoldier s) {
                    Vec3 e = s.exitWatched();
                    // a way out chosen and none found: it watches where they went (seen from past an end of the wall)
                    k = !s.exitChosen() ? "went" : e == null ? "went?" : e.x < (WALL_X0 + WALL_X1) / 2.0 ? "W" : "E";
                }
                c.append(' ').append(k);
                choices.merge(k, 1, Integer::sum);
            }
            choiceLines.add(c.toString());
        }
        if (ticks < AFTER) return false;
        StringBuilder t = new StringBuilder(String.format(Locale.ROOT, "     alert, round %d: by %d ticks after the wall [standing still, crouching, moving, looking at an end] of %d fighter ticks:",
                round + 1, BUCKET, BUCKET * mobs.size()));
        for (int[] k : buckets) t.append(String.format(Locale.ROOT, " %d/%d/%d/%d", k[0], k[1], k[2], k[3]));
        t.append(String.format(Locale.ROOT, "; a team with an end watched %d, both ends %d of %d team ticks", teamAny - roundAny, teamBoth - roundBoth, 2 * AFTER));
        roundAny = teamAny;
        roundBoth = teamBoth;
        t.append("; first still 20 ticks at tick");
        for (int f : stillFrom) t.append(' ').append(f);
        t.append("; modes ").append(modes).append("; looks ").append(looks).append("; why ").append(whys);
        check.out(t.toString());
        for (int k = 0; k < 4; k++) for (int j = 0; j < buckets.length; j++) total[k][j] += buckets[j][k];
        for (Mob m : mobs) m.discard();
        mobs.clear();
        if (round + 1 < ROUNDS) {
            start(round + 1);
            return false;
        }
        if (round + 1 == ROUNDS) {
            StringBuilder all = new StringBuilder("     alert, all rounds, a share of the fighter ticks by " + BUCKET + " [standing still, crouching, moving, looking at an end]:");
            int per = BUCKET * SIDE * 2 * ROUNDS;
            for (int j = 0; j < buckets.length; j++)
                all.append(String.format(Locale.ROOT, " %.0f/%.0f/%.0f/%.0f%%", 100.0 * total[0][j] / per, 100.0 * total[1][j] / per, 100.0 * total[2][j] / per, 100.0 * total[3][j] / per));
            check.out(all.toString());
            double still = 100.0 * total[0][0] / per, any = 100.0 * teamAny / (2.0 * AFTER * ROUNDS), both = 100.0 * teamBoth / (2.0 * AFTER * ROUNDS);
            check.check(String.format(Locale.ROOT, "alert: in the first %d ticks after its foe went behind the wall a fighter stands still in the open"
                    + " (upright, not moving, not watching an end of the wall) at most %.0f%% of the time (%.0f%%)", BUCKET, STILL_MOST, still), still <= STILL_MOST);
            check.out(String.format(Locale.ROOT, "     alert: a team has an end of the wall watched %.0f%% of its ticks, both ends %.0f%%", any, both));
            check.check(String.format(Locale.ROOT, "alert: a squad shares the ends of the wall: both watched at least %.0f%% of a team's ticks (%.0f%%)",
                    BOTH_LEAST, both), both >= BOTH_LEAST);
            check.out("     alert: the fighters of a team without both ends watched, mode/look: " + notBoth);
            check.out("     alert: what they watch " + CHOICE_AT + " ticks after the wall, red | blue, by round:" + String.join(";", choiceLines) + "; in all " + choices);
            round = ROUNDS;
            sentry();
            return false;
        }
        return false;
    }

    /**
     * the stand's bar: standing still in the first bucket at most, % (main X1210: 34 to 39; with the knee in the hold:
     * 9 to 17). Both ends of the wall watched by a team at least, %: on X1231 13 to 23 (the exit's walk from the lost
     * place got round the near end only), with the squad sharing the corners (ExitSpots.corner) 32 to 52
     */
    static final double STILL_MOST = 20, BOTH_LEAST = 27;
    /** team ticks (after the wall, all rounds) with an end of the wall watched by one of the team, with both; at the last round's end */
    private int teamAny, teamBoth, roundAny, roundBoth;
    /** the tick after the wall the choices are read; what they chose, per round and in all */
    static final int CHOICE_AT = 100;
    private final List<String> choiceLines = new ArrayList<>();
    private final Map<String, Integer> choices = new TreeMap<>();
    /** what the fighters of a team without both ends watched were doing: mode / where they looked */
    private final Map<String, Integer> notBoth = new TreeMap<>();
    /** the sentry: one soldier, no one about, this long; its head's sweep at least, degrees; its turn a tick at most */
    static final int SENTRY_TICKS = 600;
    static final double SENTRY_SWEEP = 50, SENTRY_TURN = 2;
    private Mob sentry;
    private float sentryLast, sentryMin, sentryMax, sentryMost, sentryAt;
    private int sentryTicks;

    private void sentry() {
        AiSquads.forget(level);
        sentry = spawn(XS[1], RED_Z, RED, 0F);
        sentryLast = sentry.getYHeadRot();
        sentryMin = sentryMax = 0;
        sentryMost = 0;
        sentryAt = 0;
        sentryTicks = 0;
    }

    /** the sentry's tick; whether it is done */
    private boolean sentryTick() {
        sentryTicks++;
        float yaw = sentry.getYHeadRot(), turn = net.minecraft.util.Mth.wrapDegrees(yaw - sentryLast);
        sentryLast = yaw;
        sentryMost = Math.max(sentryMost, Math.abs(turn));
        // from the way it faced, summed turn by turn (no wrap at 180)
        sentryAt += turn;
        sentryMin = Math.min(sentryMin, sentryAt);
        sentryMax = Math.max(sentryMax, sentryAt);
        if (sentryTicks < SENTRY_TICKS) return false;
        AiBot b = AiBots.get(sentry);
        String why = b != null && b.brain instanceof AiSoldier s ? String.valueOf(s.why) : "-";
        check.out(String.format(Locale.ROOT, "     alert, a sentry alone %d ticks: head from %.0f to %.0f degrees of the way it faced, %.1f a tick at most; why at the end %s",
                SENTRY_TICKS, sentryMin, sentryMax, sentryMost, why));
        check.check(String.format(Locale.ROOT, "alert: a sentry with no one about looks round at least %.0f degrees across, its head %.0f degrees a tick at most",
                SENTRY_SWEEP, SENTRY_TURN), sentryMax - sentryMin >= SENTRY_SWEEP && sentryMost <= SENTRY_TURN + 0.01);
        sentry.discard();
        return true;
    }

    /** the end of the wall the head points at (0 west, 1 east), or -1 */
    private int endOf(Mob m) {
        double yaw = Math.toRadians(m.getYHeadRot());
        double dx = -Math.sin(yaw), dz = Math.cos(yaw);
        double[][] ends = {{WALL_X0 - 0.5, WALL_Z + 0.5}, {WALL_X1 + 1.5, WALL_Z + 0.5}};
        for (int k = 0; k < 2; k++) {
            double ex = ends[k][0] - m.getX(), ez = ends[k][1] - m.getZ(), len = Math.sqrt(ex * ex + ez * ez);
            if (len > 1e-3 && (dx * ex + dz * ez) / len >= Math.cos(Math.toRadians(EDGE_DEG))) return k;
        }
        return -1;
    }

    /** where the head points: "end" (within EDGE_DEG of the way to an end of the wall), "wall" (at the wall between), "away" */
    private String look(Mob m) {
        double yaw = Math.toRadians(m.getYHeadRot());
        double dx = -Math.sin(yaw), dz = Math.cos(yaw);
        double[][] ends = {{WALL_X0 - 0.5, WALL_Z + 0.5}, {WALL_X1 + 1.5, WALL_Z + 0.5}};
        for (double[] e : ends) {
            double ex = e[0] - m.getX(), ez = e[1] - m.getZ(), len = Math.sqrt(ex * ex + ez * ez);
            if (len > 1e-3 && (dx * ex + dz * ez) / len >= Math.cos(Math.toRadians(EDGE_DEG))) return "end";
        }
        // towards the wall's side at all
        double toWall = WALL_Z + 0.5 - m.getZ();
        return dz * Math.signum(toWall) > 0.5 ? "wall" : "away";
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void start(int r) {
        round = r;
        ticks = 0;
        walled = false;
        buckets = new int[AFTER / BUCKET][4];
        for (int k = 0; k < 4; k++) if (total[k] == null) total[k] = new int[AFTER / BUCKET];
        whys.clear();
        modes.clear();
        looks.clear();
        stillFrom = new int[SIDE * 2];
        stillRun = new int[SIDE * 2];
        java.util.Arrays.fill(stillFrom, -1);
        AiSquads.forget(level);
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                set(x, y - 1, z, stone);
                for (int h = 0; h < 4; h++) set(x, y + h, z, air);
            }
        TacticalMap.of(level).want(X0 + SIZE / 2, y, Z0 + SIZE / 2, SIZE / 2 + 4, 6);
        for (int i = 0; i < SIDE; i++) mobs.add(spawn(XS[i], RED_Z, RED, 0F));
        for (int i = 0; i < SIDE; i++) mobs.add(spawn(XS[i], BLUE_Z, BLUE, 180F));
        AiBots.refreshTargets(level);
        long now = level.getGameTime();
        for (Mob m : mobs) {
            AiBot b = AiBots.get(m);
            if (b == null) continue;
            for (Mob o : mobs)
                if (o != m && o.getTeam() != m.getTeam()) b.memory.seen(o.getId(), o.getX(), o.getY(), o.getZ(), now, 0);
        }
    }

    private Mob spawn(double x, double z, String team, float yaw) {
        List<Mob> made = AiSpawn.spawn(level, new Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, BuiltInRegistries.ITEM.getValue(RIFLE));
        Mob m = made.get(0);
        m.setPos(x, y, z);
        m.setYRot(yaw);
        m.setYHeadRot(yaw);
        m.setYBodyRot(yaw);
        var hp = m.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(1000);
        m.setHealth(1000);
        AiTeams.join(level, m, team);
        AiBot bot = AiBots.get(m);
        if (bot != null) bot.gunner.ammo.setInfinite();
        return m;
    }
}
