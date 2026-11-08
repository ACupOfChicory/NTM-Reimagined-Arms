package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * The AI check's squad, on a field of its own: four soldiers of one side against one foe rooted behind a stone wall
 * two high, which none of them sees from the front; they saw it go behind it. Pillars in front to take cover at, and
 * one to each side that sees past the wall's end. Rounds by turns without squads (AiSquads off: each fights alone)
 * and with them. Counted: looks round the side (flanks) and bursts at the wall for those on the move, the ticks one
 * of them moved and of those the ticks another fired, the most that went after the foe at once, hits either way, the
 * first hit on the foe and where from (degrees off the line from the foe to where the squad began).
 */
final class AiCheckSquad {
    static final int X0 = 400, Z0 = 4, SIZE = 48, RUN = 1200, ROUNDS = 4, SQUAD = 4;
    /** the foe and its wall */
    static final double FX = 424.5, FZ = 14.5;
    static final int WALL_X0 = 421, WALL_X1 = 427, WALL_Z = 17;
    /** where the squad starts: a row this far up, these x */
    static final double START_Z = 37.5;
    static final double[] START_X = {418.5, 422.5, 426.5, 430.5};
    /** pillars, two high: in front (x, z), and to each side (two blocks long in z) */
    static final int[][] FRONT = {{417, 30}, {421, 30}, {427, 30}, {431, 30}};
    static final int[][] SIDE = {{411, 21}, {411, 22}, {437, 21}, {437, 22}};
    /** a shot fired within this many ticks counts as fire */
    static final int FIRE_RECENT = 10;
    static final Identifier GUN = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");

    private static AiCheckSquad counting;
    /** the one listener for hits, whatever runs of the stage */
    private static boolean listening;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks;
    private final List<Mob> squad = new ArrayList<>();
    private Mob foe;
    private final int[] lastShots = new int[SQUAD];
    private final long[] lastFire = new long[SQUAD];
    /** this round: ticks one moved, of those another fired; most hunting at once; hits; first hit; its angle (sum) */
    private int moveTicks, coveredTicks, maxHunters, hitsOn, hitsBy, firstHit;
    private double angleSum;
    private int angleN;
    private final List<int[]> results = new ArrayList<>();
    private final List<double[]> angles = new ArrayList<>();

    AiCheckSquad(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        if (!started) {
            started = true;
            for (int cx = X0 >> 4; cx <= (X0 + SIZE) >> 4; cx++)
                for (int cz = Z0 >> 4; cz <= (Z0 + SIZE) >> 4; cz++) level.setChunkForced(cx, cz, true);
            if (!listening) NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, e -> {
                AiCheckSquad c = counting;
                if (c == null || c.foe == null) return;
                if (e.getEntity() == c.foe && e.getSource().getEntity() instanceof Mob m && c.squad.contains(m)) {
                    c.hitsBy++;
                    if (c.firstHit < 0) c.firstHit = c.ticks;
                    double cos = AiSquads.cos(m.getX() - FX, m.getZ() - FZ, 0, START_Z - FZ);
                    c.angleSum += Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
                    c.angleN++;
                } else if (c.squad.contains(e.getEntity()) && e.getSource().getEntity() == c.foe) c.hitsOn++;
            });
            listening = true;
            counting = this;
            AiBots.awake = true;
            start(0);
            return false;
        }
        ticks++;
        foe.setHealth(foe.getMaxHealth());
        long now = level.getGameTime();
        int moving = 0, hunters = 0;
        boolean[] moves = new boolean[SQUAD];
        for (int i = 0; i < squad.size(); i++) {
            Mob m = squad.get(i);
            m.setHealth(m.getMaxHealth());
            AiBot b = AiBots.get(m);
            if (b == null) continue;
            if (b.gunner.shots != lastShots[i]) {
                lastShots[i] = b.gunner.shots;
                lastFire[i] = now;
            }
            moves[i] = AiSquads.moving(b);
            if (moves[i]) moving++;
            if (b.brain instanceof AiSoldier s && s.mode() == AiSoldier.Mode.SEARCH) hunters++;
        }
        maxHunters = Math.max(maxHunters, hunters);
        if (moving > 0) {
            moveTicks++;
            boolean covered = false;
            for (int i = 0; i < squad.size(); i++) covered |= !moves[i] && now - lastFire[i] <= FIRE_RECENT;
            if (covered) coveredTicks++;
        }
        if (ticks >= RUN) {
            int flanks = 0, sup = 0, tries = 0;
            for (Mob m : squad)
                if (AiBots.get(m) != null && AiBots.get(m).brain instanceof AiSoldier s) {
                    flanks += s.flanks;
                    tries += s.flankTries;
                    sup += s.suppressions;
                }
            results.add(new int[] {round % 2, flanks, sup, moveTicks, coveredTicks, maxHunters, hitsBy, hitsOn, firstHit, tries});
            angles.add(new double[] {angleN == 0 ? -1 : angleSum / angleN});
            for (Mob m : squad) m.discard();
            foe.discard();
            squad.clear();
            foe = null;
            if (round + 1 >= ROUNDS) return finishAll();
            start(round + 1);
        }
        return false;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void pillar(int x, int z, BlockState st) {
        set(x, y, z, st);
        set(x, y + 1, z, st);
    }

    private void build() {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                set(x, y - 1, z, stone);
                for (int h = 0; h < 4; h++) set(x, y + h, z, air);
            }
        for (int x = WALL_X0; x <= WALL_X1; x++) pillar(x, WALL_Z, stone);
        for (int[] p : FRONT) pillar(p[0], p[1], stone);
        for (int[] p : SIDE) pillar(p[0], p[1], stone);
        TacticalMap.of(level).want(X0 + SIZE / 2, y, Z0 + SIZE / 2, SIZE / 2 + 4, 6);
    }

    private Mob spawn(double x, double z, String team, float yaw) {
        List<Mob> made = AiSpawn.spawn(level, new Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, BuiltInRegistries.ITEM.getValue(GUN));
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

    private void start(int r) {
        round = r;
        ticks = moveTicks = coveredTicks = maxHunters = hitsOn = hitsBy = angleN = 0;
        angleSum = 0;
        firstHit = -1;
        AiSquads.on = r % 2 == 1;
        AiSquads.offTeams.clear();
        AiSquads.forget(level);
        build();
        foe = spawn(FX, FZ, "squad_foe", 0F);
        var speed = foe.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0);
        for (int i = 0; i < SQUAD; i++) {
            squad.add(spawn(START_X[i], START_Z, "squad_side", 180F));
            lastShots[i] = 0;
            lastFire[i] = Long.MIN_VALUE / 2;
        }
        AiBots.refreshTargets(level);
        long now = level.getGameTime();
        // each saw the foe go behind its wall (seen now: lost at its next look), and the foe them
        AiBot f = AiBots.get(foe);
        for (Mob m : squad) {
            AiBot b = AiBots.get(m);
            if (b != null) b.memory.seen(foe.getId(), FX, y, FZ, now, 0);
            if (f != null) f.memory.seen(m.getId(), m.getX(), m.getY(), m.getZ(), now, 0);
        }
    }

    private boolean finishAll() {
        AiSquads.on = true;
        counting = null;
        for (Mob m : squad) m.discard();
        if (foe != null) foe.discard();
        int[][] sum = new int[2][9];
        double[] ang = new double[2];
        int[] angN = new int[2];
        int[] firstSum = new int[2], firstN = new int[2];
        boolean flankEach = true, hitEach = true;
        for (int i = 0; i < results.size(); i++) {
            int[] r = results.get(i);
            int on = r[0];
            for (int k = 1; k < 8; k++) sum[on][k] = k == 5 ? Math.max(sum[on][k], r[k]) : sum[on][k] + r[k];
            if (angles.get(i)[0] >= 0) {
                ang[on] += angles.get(i)[0];
                angN[on]++;
            }
            if (r[8] >= 0) {
                firstSum[on] += r[8];
                firstN[on]++;
            }
            if (on == 1) {
                flankEach &= r[1] > 0;
                hitEach &= r[6] > 0;
            }
            check.out(String.format(Locale.ROOT, "     squad, round %d %s: flanks %d (looked for %d times), bursts for those moving %d; moving %d ticks, covered by fire %d;"
                    + " most hunting at once %d; hits on the foe %d (first at tick %d, from %.0f degrees off), on the squad %d", i + 1, on == 1 ? "with squads" : "alone",
                    r[1], r[9], r[2], r[3], r[4], r[5], r[6], r[8], angles.get(i)[0], r[7]));
        }
        String[] names = {"alone", "with squads"};
        double[] covered = new double[2];
        for (int on = 0; on < 2; on++) {
            covered[on] = sum[on][3] == 0 ? 0 : (double) sum[on][4] / sum[on][3];
            check.out(String.format(Locale.ROOT, "     squad, %s: moving covered by fire %.0f%% of %d ticks; most hunting at once %d; hits on the foe %d,"
                    + " on the squad %d; first hit at %s; hits from %.0f degrees off", names[on], covered[on] * 100, sum[on][3], sum[on][5], sum[on][6], sum[on][7],
                    firstN[on] == 0 ? "-" : String.valueOf(firstSum[on] / firstN[on]), angN[on] == 0 ? -1.0 : ang[on] / angN[on]));
        }
        check.check("alone (squads off) none flanks", sum[0][1] == 0);
        check.check("in a squad one goes round the side each round", flankEach);
        check.check("in a squad they keep the wall under fire for those on the move", sum[1][2] > 0);
        check.check("in a squad more of the moving is covered by fire than alone", covered[1] > covered[0]);
        check.check("in a squad two at most go after the foe at once", sum[1][5] <= AiSquads.HUNTERS);
        check.check("in a squad they hit the foe each round", hitEach);
        check.check("in a squad the first hit on the foe comes sooner than alone", firstN[1] > 0 && (firstN[0] == 0 || firstSum[1] / firstN[1] < firstSum[0] / firstN[0]));
        return true;
    }
}
