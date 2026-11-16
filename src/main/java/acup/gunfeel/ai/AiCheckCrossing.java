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

/**
 * The AI check's river crossed under fire (AiCrossing, stage 6 T7): a stone plain cut across by a river 12 wide and 3
 * deep, its ends walled (no way round). Two of one team hold the north bank (they do not walk): one in a stone hut
 * whose door is on its far side (out of reach from the south bank: the bank is taken when it is down), one behind a
 * wall a block high before it, firing on the river, hurt by none further off than {@link #REACH} (fire from the south
 * bank cannot put it down: those over the river can); the one in the hut heard now and then; a squad of six of another, a machine
 * gunner with five riflemen, a smoke grenade each, on the south bank, know where they are. Rounds by twos, aiCrossing off (before: none begins a swim under fire, one not under fire swims
 * as it will) and on (by waves, covered, behind smoke). Counted: whether the bank was taken and when, who of the squad
 * is left, who went down in the water, who got over, the most swimming at once, the share of the ticks one swam with
 * one on the bank firing (within the gap between two bursts), crossings with smoke.
 */
final class AiCheckCrossing {
    static final int X0 = 3700, Z0 = 0, SX = 64, SZ = 64, RUN = 2400, ROUNDS = 6, SQUAD = 6;
    /** the river, z from RZ0 to RZ1, DEPTH deep */
    static final int RZ0 = 24, RZ1 = 35, DEPTH = 3;
    /** those holding the north bank, behind the wall at WALL_Z; the squad's start on the south bank */
    static final double[][] HOLDERS = {{3731.5, 6.5}, {3731.5, 12.5}};
    static final int WALL_Z = 14, HUT_X = 3731, HUT_Z = 6;
    /** the one at the wall is hurt by none further off than this, blocks */
    static final double REACH = 16;
    private static AiCheckCrossing counting;
    private static boolean listening;
    private Mob shooter;
    private final List<String> downs = new ArrayList<>();
    /** the one in the hut is heard firing every this many ticks (lost from sight, it would be forgotten before the squad is over) */
    static final int HEARD = 70;
    static final double START_Z = 48.5;
    static final double[] START_X = {3725.5, 3728.5, 3731.5, 3734.5, 3737.5, 3731.5};
    static final Identifier RIFLE = Identifier.fromNamespaceAndPath("hbm", "gun_carbine"), MG = Identifier.fromNamespaceAndPath("hbm", "gun_m2");

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int shooterDown, hurt, lastBankShot;
    private float[] health = new float[0];
    private int round = -1, ticks, takenAt, inWaterDown, mostSwimming, swimTicks, coveredTicks, over;
    private final List<Mob> squad = new ArrayList<>(), held = new ArrayList<>();
    private final java.util.Set<Integer> down = new java.util.HashSet<>();
    private int[] shotsBefore = new int[0];
    private int smokedBefore, begunBefore, backBefore;
    /** per round: {on, held left, squad left, ticks, taken at, down in the water, over, most swimming, swim ticks, covered, smoked, begun, back} */
    private final List<int[]> results = new ArrayList<>();

    AiCheckCrossing(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        if (!started) {
            started = true;
            for (int cx = (X0 >> 4) - 1; cx <= (X0 + SX) >> 4; cx++)
                for (int cz = (Z0 >> 4) - 1; cz <= (Z0 + SZ) >> 4; cz++) level.setChunkForced(cx, cz, true);
            AiBots.awake = true;
            if (!listening) net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent.class, e -> {
                AiCheckCrossing c = counting;
                if (c == null || e.getEntity() != c.shooter) return;
                var by = e.getSource().getEntity();
                if (by == null || by.distanceTo(c.shooter) > REACH) e.setCanceled(true);
            });
            listening = true;
            counting = this;
            start(0);
            return false;
        }
        ticks++;
        if (ticks % HEARD == 0 && held.get(0).isAlive())
            for (Mob m : squad) if (m.isAlive() && AiBots.get(m) instanceof AiBot b) b.memory.heard(HUT_X + 0.5, y, HUT_Z + 0.5, level.getGameTime(), true, false);
        int swimming = 0, left = 0, foes = 0;
        boolean bankFires = false;
        for (int i = 0; i < squad.size(); i++) {
            Mob m = squad.get(i);
            AiBot b = AiBots.get(m);
            if (b != null) {
                // one on the south bank fired this tick
                if (b.gunner.shots > shotsBefore[i] && m.getZ() > RZ1 + 0.5 && !m.isInWater()) bankFires = true;
                shotsBefore[i] = b.gunner.shots;
            }
            float h = m.isAlive() ? m.getHealth() : 0;
            if (h < health[i]) hurt++;
            health[i] = h;
            if (!m.isAlive()) {
                if (down.add(m.getId())) {
                    if (m.isInWater()) inWaterDown++;
                    downs.add(String.format(Locale.ROOT, "%d at tick %d at %.1f %.1f%s", i, ticks, m.getX(), m.getZ(), m.isInWater() ? " in the water" : ""));
                }
                continue;
            }
            left++;
            if (AiWater.swims(m)) swimming++;
        }
        mostSwimming = Math.max(mostSwimming, swimming);
        if (bankFires) lastBankShot = ticks;
        if (swimming > 0) {
            swimTicks++;
            // covered: one on the bank fired within a burst's gap (AiSoldier.COVER_EVERY)
            if (ticks - lastBankShot <= AiSoldier.COVER_EVERY) coveredTicks++;
        }
        if (shooterDown < 0 && !held.get(1).isAlive()) shooterDown = ticks;
        // the bank's taken when the one in the hut is down
        if (held.get(0).isAlive()) foes++;
        if (foes == 0 && takenAt < 0) takenAt = ticks;
        if (ticks < RUN && left > 0 && foes > 0) return false;
        over = 0;
        for (Mob m : squad) if (m.isAlive() && m.getZ() < RZ0 - 0.5 && !m.isInWater()) over++;
        StringBuilder where = new StringBuilder();
        for (Mob m : squad)
            if (m.isAlive() && AiBots.get(m) instanceof AiBot b && b.brain instanceof AiSoldier so)
                where.append(String.format(Locale.ROOT, " %.1f %.1f %.1f %s/%s%s;", m.getX(), m.getY() - y, m.getZ(), so.mode(), so.why, AiSquads.base(b) ? " base" : ""));
        check.out(String.format(Locale.ROOT, "     crossing, round %d, at the end:%s; down: %s", round + 1, where, downs));
        downs.clear();
        results.add(new int[] {round % 2, foes, left, ticks, takenAt, inWaterDown, over, mostSwimming, swimTicks, coveredTicks, AiCrossing.smoked - smokedBefore,
                AiCrossing.begun - begunBefore, AiCrossing.back - backBefore, shooterDown, hurt});
        for (Mob m : squad) m.discard();
        for (Mob m : held) m.discard();
        squad.clear();
        held.clear();
        if (round + 1 >= ROUNDS) return finish();
        start(round + 1);
        return false;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    private void build() {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState(), water = Blocks.WATER.defaultBlockState();
        for (int x = X0; x < X0 + SX; x++)
            for (int z = Z0; z < Z0 + SZ; z++) {
                boolean river = z >= RZ0 && z <= RZ1 && x > X0 && x < X0 + SX - 1;
                set(x, y - DEPTH - 1, z, stone);
                for (int d = DEPTH; d >= 1; d--) set(x, y - d, z, river ? water : stone);
                // the field walled round three high: no way round the river
                boolean edge = x == X0 || x == X0 + SX - 1 || z == Z0 || z == Z0 + SZ - 1;
                boolean wall = z == WALL_Z && x >= 3722 && x <= 3741;
                for (int h = 0; h < 6; h++) set(x, y + h, z, edge && h < 3 || wall && h == 0 ? stone : air);
            }
        // the hut: stone walls three high round a 3 by 3 floor, a roof, a door two high in the north wall
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int h = 0; h <= 3; h++) {
                    boolean w = Math.abs(dx) == 2 || Math.abs(dz) == 2 || h == 3;
                    boolean door = dz == -2 && dx == 0 && h < 2;
                    set(HUT_X + dx, y + h, HUT_Z + dz, w && !door ? stone : air);
                }
        TacticalMap.of(level).want(X0 + SX / 2, y, Z0 + SZ / 2, SX / 2 + 4, 6);
    }

    private Mob spawn(double x, double z, String team, float yaw, Identifier gun) {
        List<Mob> made = AiSpawn.spawn(level, new Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, BuiltInRegistries.ITEM.getValue(gun));
        Mob m = made.get(0);
        m.setPos(x, y, z);
        m.setYRot(yaw);
        m.setYHeadRot(yaw);
        m.setYBodyRot(yaw);
        AiTeams.join(level, m, team);
        AiBot bot = AiBots.get(m);
        if (bot != null) {
            bot.gunner.ammo.setInfinite();
            bot.smokes = 1;
        }
        return m;
    }

    private void start(int r) {
        round = r;
        ticks = 0;
        takenAt = -1;
        inWaterDown = mostSwimming = swimTicks = coveredTicks = hurt = 0;
        shooterDown = -1;
        lastBankShot = -1000;
        down.clear();
        AiCrossing.on = r % 2 == 1;
        AiSquads.on = true;
        AiSquads.offTeams.clear();
        AiSquads.forget(level);
        AiCrossing.forget(level);
        build();
        for (double[] p : HOLDERS) {
            Mob m = spawn(p[0], p[1], "crossing_held", 0F, RIFLE);
            var speed = m.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(0);
            if (held.size() == 1) shooter = m;
            held.add(m);
        }
        for (int i = 0; i < SQUAD; i++) squad.add(spawn(START_X[i], START_Z + (i == SQUAD - 1 ? 3 : 0), "crossing_side", 180F, i == SQUAD - 1 ? MG : RIFLE));
        AiBots.refreshTargets(level);
        shotsBefore = new int[SQUAD];
        health = new float[SQUAD];
        for (int i = 0; i < SQUAD; i++) health[i] = squad.get(i).getHealth();
        long now = level.getGameTime();
        for (int i = 0; i < SQUAD; i++) {
            AiBot b = AiBots.get(squad.get(i));
            if (b == null) continue;
            shotsBefore[i] = b.gunner.shots;
            for (Mob f : held) {
                b.memory.seen(f.getId(), f.getX(), f.getY(), f.getZ(), now, 0);
                AiBot fb = AiBots.get(f);
                if (fb != null) fb.memory.seen(b.mob.getId(), b.mob.getX(), b.mob.getY(), b.mob.getZ(), now, 0);
            }
        }
        smokedBefore = AiCrossing.smoked;
        begunBefore = AiCrossing.begun;
        backBefore = AiCrossing.back;
    }

    private boolean finish() {
        AiCrossing.on = true;
        counting = null;
        String[] names = {"aiCrossing off (before)", "aiCrossing on"};
        double[][] sum = new double[2][15];
        int[] n = new int[2], taken = new int[2];
        double[] takenTicks = new double[2];
        for (int i = 0; i < results.size(); i++) {
            int[] r = results.get(i);
            n[r[0]]++;
            for (int k = 1; k < r.length; k++) sum[r[0]][k] += r[k];
            if (r[4] >= 0) {
                taken[r[0]]++;
                takenTicks[r[0]] += r[4];
            }
            check.out(String.format(Locale.ROOT, "     crossing, round %d, %s: the one in the hut %d of %d left, of the squad %d of %d, %d ticks, taken %s; down in the water %d, over %d,"
                    + " most swimming at once %d; ticks one swam %d, with one on the bank firing %d; crossings begun %d, with smoke %d; swam back %d; the one at the wall down %s; the squad hurt %d times",
                    i + 1, names[r[0]], r[1], 1, r[2], SQUAD, r[3], r[4] < 0 ? "never" : "at tick " + r[4], r[5], r[6], r[7], r[8], r[9], r[11], r[10], r[12], r[13] < 0 ? "never" : "at tick " + r[13], r[14]));
        }
        for (int a = 0; a < 2; a++)
            check.out(String.format(Locale.ROOT, "     crossing, %s, %d rounds: the bank taken in %d (%.0f ticks on average); of the squad %.0f of %d left, down in the water %.0f, over %.0f;"
                    + " most swimming at once %.1f on average; of the ticks one swam %.0f%% with one on the bank firing; crossings begun %.0f, with smoke %.0f; swam back %.0f; hurt %.0f times",
                    names[a], n[a], taken[a], taken[a] == 0 ? 0 : takenTicks[a] / taken[a], sum[a][2], n[a] * SQUAD, sum[a][5], sum[a][6], n[a] == 0 ? 0 : sum[a][7] / n[a],
                    sum[a][8] == 0 ? 0 : 100 * sum[a][9] / sum[a][8], sum[a][11], sum[a][10], sum[a][12], sum[a][14]));
        check.check("crossing: all rounds ran", results.size() == ROUNDS);
        check.check(String.format(Locale.ROOT, "crossing: with aiCrossing a crossing is begun in every round, with smoke in most (%.0f, %.0f of %d)", sum[1][11], sum[1][10], n[1]),
                sum[1][11] >= n[1] && sum[1][10] * 2 > n[1]);
        check.check(String.format(Locale.ROOT, "crossing: with aiCrossing no more than four swim at once (%.1f on average)", n[1] == 0 ? 0 : sum[1][7] / n[1]),
                n[1] > 0 && sum[1][7] <= 4 * n[1]);
        check.check(String.format(Locale.ROOT, "crossing: with aiCrossing most of the swimming is covered from the bank (%.0f%%)", sum[1][8] == 0 ? 0 : 100 * sum[1][9] / sum[1][8]),
                sum[1][9] * 2 > sum[1][8]);
        check.check(String.format(Locale.ROOT, "crossing: with aiCrossing the bank is taken in more rounds, or sooner (%d, %.0f ticks; %d, %.0f before)", taken[1],
                taken[1] == 0 ? 0 : takenTicks[1] / taken[1], taken[0], taken[0] == 0 ? 0 : takenTicks[0] / taken[0]),
                taken[1] > taken[0] || taken[1] == taken[0] && taken[1] > 0 && takenTicks[1] / taken[1] < takenTicks[0] / taken[0]);
        return true;
    }
}
