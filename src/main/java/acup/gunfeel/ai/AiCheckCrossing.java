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
 * The AI check's river (AiCrossing, by the field manuals): a stone plain cut across by a river 12 wide and 3
 * deep, its ends walled (no way round). Two of one team hold the north bank (they do not walk): one in a stone hut
 * whose door is on its far side (out of reach from the south bank: the bank is taken when it is down), one behind a
 * wall a block high before it, firing on the river, hurt by none further off than {@link #REACH} (fire from the south
 * bank cannot put it down: those over the river can), watching the river (the nearest of the squad seen now and then);
 * the one in the hut heard now and then; a squad of six of another, a machine
 * gunner with five riflemen, a smoke grenade each, on the south bank, know where they are. Rounds by twos: aiCrossing
 * off (before: none begins a swim under fire, one not under fire swims as it will); on with no order (it keeps to its
 * bank, looks along it for a place to cross and reports); on with its staff's order to swim over at the middle (the
 * drill: the far side's security first, the rest by pairs, the near side's security last). Counted: whether the bank was
 * taken and when, who of the squad is left, who went down in the water, who got over, the most swimming at once, the
 * share of the ticks one swam with one on the bank firing, smoke, the drill's order kept, reports and places found.
 * Last a round with no order and a causeway over the river at one end: a dry way over, walked; no watch, no crossing.
 */
final class AiCheckCrossing {
    static final int X0 = 3700, Z0 = 0, SX = 64, SZ = 64, RUN = 2400, WATCH_RUN = 1200, ROUNDS = 7, SQUAD = 6;
    static final String SIDE = "crossing_side";
    /** the arms: off, on with no order, on ordered, the causeway */
    static final int OFF = 0, WATCH = 1, ORDER = 2, CAUSEWAY = 3;
    /** the order: over at the middle of the river, to a rally point past the far bank */
    static final double CROSS_X = 3731.5, CROSS_Z = 36.5, FAR_Z = 20.5;
    /** the neck, x from NECK_X0 to NECK_X1 */
    static final int NECK_X0 = 3741, NECK_X1 = 3745;
    /** the last round's: a causeway over the river at CAUSEWAY_X0..1 (a dry way over: no crossing is to begin), this many ticks */
    static final int CAUSEWAY_X0 = 3702, CAUSEWAY_X1 = 3704, CAUSEWAY_RUN = 800;
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
    private int shooterDown, hurt, lastBankShot, shooterBefore, lastShooterShots;
    /** one of the squad was under fire this round (AiWater.underFire: what a crossing after a foe out of sight waits for) */
    private boolean firedOn;

    /** the shots the one at the wall fired this round (kept when it is down) */
    private int shooterShots() {
        if (shooter != null && AiBots.get(shooter) instanceof AiBot sb) lastShooterShots = sb.gunner.shots - shooterBefore;
        return lastShooterShots;
    }
    private float[] health = new float[0];
    private int round = -1, ticks, takenAt, inWaterDown, mostSwimming, swimTicks, coveredTicks, over;
    private final List<Mob> squad = new ArrayList<>(), held = new ArrayList<>();
    private final java.util.Set<Integer> down = new java.util.HashSet<>();
    private int[] shotsBefore = new int[0];
    private int smokedBefore, begunBefore, backBefore, watchesBefore, scoutedBefore;
    /** the drill: ticks one of the main body swam with one of the far side's security not over; one of the near side's with one of the main body not over; the most of the main body swimming at once; reports seen, places in them */
    private int mainEarly, nearEarly, mostMain, reports, spots;
    private String lastReport = "none";
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
        shooterShots();
        if (ticks % HEARD == 0 && held.get(0).isAlive())
            for (Mob m : squad) if (m.isAlive() && AiBots.get(m) instanceof AiBot b) b.memory.heard(HUT_X + 0.5, y, HUT_Z + 0.5, level.getGameTime(), true, false);
        // the one at the wall watches the river: the nearest of the squad is seen now and then (it keeps them under fire)
        if (ticks % HEARD == 0 && shooter.isAlive() && AiBots.get(shooter) instanceof AiBot sb) {
            Mob near = null;
            for (Mob m : squad) if (m.isAlive() && (near == null || m.distanceToSqr(shooter) < near.distanceToSqr(shooter))) near = m;
            if (near != null) sb.memory.seen(near.getId(), near.getX(), near.getY(), near.getZ(), level.getGameTime(), 0);
        }
        int swimming = 0, left = 0, foes = 0;
        boolean overNow = false;
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
                    var src = m.getLastDamageSource();
                    var by = src == null ? null : src.getEntity();
                    int k = by == null ? -1 : squad.indexOf(by);
                    downs.add(String.format(Locale.ROOT, "%d at tick %d at %.1f %.1f%s by %s (%s)", i, ticks, m.getX(), m.getZ(), m.isInWater() ? " in the water" : "",
                            src == null ? "?" : src.type().msgId(), by == null ? "-" : k >= 0 ? "the squad's " + k + (AiBots.get((Mob) by) instanceof AiBot kb && kb.brain instanceof AiSoldier ks ? " " + ks.mode() + "/" + ks.why + (AiCrossing.of(kb) != null ? " in a crossing" : "") : "") : held.indexOf(by) == 0 ? "the one in the hut" : by == shooter ? "the one at the wall" : by.getType().toShortString()));
                }
                continue;
            }
            left++;
            if (AiWater.swims(m)) swimming++;
            else if (m.getZ() < RZ0 - 0.5) overNow = true;
            if (b != null && AiWater.underFire(b, level.getGameTime())) firedOn = true;
        }
        mostSwimming = Math.max(mostSwimming, swimming);
        if (arm() == ORDER) {
            boolean farOut = false, mainOut = false, mainSwims = false, nearSwims = false;
            int mainSwim = 0;
            for (Mob m : squad) {
                if (!m.isAlive() || !(AiBots.get(m) instanceof AiBot b)) continue;
                AiCrossing.Role r = AiCrossing.role(b);
                boolean overThere = m.getZ() < RZ0 - 0.5 && !m.isInWater();
                if (r == AiCrossing.Role.FAR && !overThere) farOut = true;
                if (r == AiCrossing.Role.MAIN && !overThere) mainOut = true;
                if (!AiWater.swims(m)) continue;
                if (r == AiCrossing.Role.MAIN) {
                    mainSwims = true;
                    mainSwim++;
                }
                if (r == AiCrossing.Role.NEAR) nearSwims = true;
            }
            if (mainSwims && farOut) mainEarly++;
            if (nearSwims && mainOut) nearEarly++;
            mostMain = Math.max(mostMain, mainSwim);
        }
        if (arm() == WATCH || arm() == CAUSEWAY) {
            var rs = AiCrossing.reports(level, SIDE);
            if (!rs.isEmpty()) {
                reports = Math.max(reports, rs.size());
                for (var r : rs) {
                    spots = Math.max(spots, r.spots().size());
                    lastReport = String.format(Locale.ROOT, "squad %d, bank %.0f %.0f, foes %s, places %s", r.squadId(), r.bankX(), r.bankZ(), java.util.Arrays.toString(r.foeIds()), r.spots());
                }
            }
        }
        if (bankFires) lastBankShot = ticks;
        if (swimming > 0) {
            swimTicks++;
            // covered: one on the bank fired within a burst's gap (AiSoldier.COVER_EVERY), or its own over already (the cover
            // lifts its fire off the place they went in at: those over hold it)
            if (ticks - lastBankShot <= AiSoldier.COVER_EVERY || overNow) coveredTicks++;
        }
        if (shooterDown < 0 && !held.get(1).isAlive()) shooterDown = ticks;
        // the bank's taken when the one in the hut is down
        if (held.get(0).isAlive()) foes++;
        if (foes == 0 && takenAt < 0) takenAt = ticks;
        if (ticks < (causeway() ? CAUSEWAY_RUN : arm() == WATCH ? WATCH_RUN : RUN) && left > 0 && foes > 0) return false;
        over = 0;
        for (Mob m : squad) if (m.isAlive() && m.getZ() < RZ0 - 0.5 && !m.isInWater()) over++;
        StringBuilder where = new StringBuilder();
        for (Mob m : squad)
            if (m.isAlive() && AiBots.get(m) instanceof AiBot b && b.brain instanceof AiSoldier so)
                where.append(String.format(Locale.ROOT, " %.1f %.1f %.1f %s/%s%s;", m.getX(), m.getY() - y, m.getZ(), so.mode(), so.why, AiSquads.base(b) ? " base" : ""));
        check.out(String.format(Locale.ROOT, "     crossing, round %d, at the end:%s; down: %s; the last report: %s", round + 1, where, downs, lastReport));
        downs.clear();
        results.add(new int[] {arm(), foes, left, ticks, takenAt, inWaterDown, over, mostSwimming, swimTicks, coveredTicks, AiCrossing.smoked - smokedBefore,
                AiCrossing.begun - begunBefore, AiCrossing.back - backBefore, shooterDown, hurt, firedOn ? 1 : 0, shooterShots(), mainEarly, nearEarly, mostMain, reports, spots,
                AiCrossing.watches - watchesBefore, AiCrossing.scouted - scoutedBefore});
        for (Mob m : squad) m.discard();
        for (Mob m : held) m.discard();
        squad.clear();
        held.clear();
        if (round + 1 >= ROUNDS) return finish();
        start(round + 1);
        return false;
    }

    private boolean causeway() {
        return round == ROUNDS - 1;
    }

    private int arm() {
        return causeway() ? CAUSEWAY : round / 2 == 0 ? OFF : round / 2 == 1 ? WATCH : ORDER;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    private void build() {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState(), water = Blocks.WATER.defaultBlockState();
        for (int x = X0; x < X0 + SX; x++)
            for (int z = Z0; z < Z0 + SZ; z++) {
                boolean river = z >= RZ0 && z <= RZ1 && x > X0 && x < X0 + SX - 1 && !(causeway() && x >= CAUSEWAY_X0 && x <= CAUSEWAY_X1)
                        // with no order, a neck of the river 8 wide along the bank: a place to swim the scouts find
                        && !(arm() == WATCH && x >= NECK_X0 && x <= NECK_X1 && (z < RZ0 + 2 || z > RZ1 - 2));
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
        AiCrossing.on = arm() != OFF;
        AiCrossing.STAND_ORDERS.clear();
        if (arm() == ORDER) AiCrossing.STAND_ORDERS.put(SIDE, new AiCrossing.Task(CROSS_X, CROSS_Z, AiCrossing.Kind.SWIM, CROSS_X, FAR_Z));
        mainEarly = nearEarly = mostMain = reports = spots = 0;
        lastReport = "none";
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
        for (int i = 0; i < SQUAD; i++) squad.add(spawn(START_X[i], START_Z + (i == SQUAD - 1 ? 3 : 0), SIDE, 180F, i == SQUAD - 1 ? MG : RIFLE));
        AiBots.refreshTargets(level);
        shotsBefore = new int[SQUAD];
        lastShooterShots = 0;
        firedOn = false;
        shooterBefore = AiBots.get(shooter) instanceof AiBot sb ? sb.gunner.shots : 0;
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
        watchesBefore = AiCrossing.watches;
        scoutedBefore = AiCrossing.scouted;
    }

    private boolean finish() {
        AiCrossing.on = true;
        AiCrossing.STAND_ORDERS.clear();
        counting = null;
        String[] names = {"aiCrossing off (before)", "aiCrossing on, no order", "aiCrossing on, ordered over at the middle", "aiCrossing on, no order, a causeway over"};
        double[][] sum = new double[4][24];
        int[] n = new int[4], taken = new int[4];
        double[] takenTicks = new double[4];
        for (int i = 0; i < results.size(); i++) {
            int[] r = results.get(i);
            n[r[0]]++;
            for (int k = 1; k < r.length; k++) sum[r[0]][k] += r[k];
            if (r[4] >= 0) {
                taken[r[0]]++;
                takenTicks[r[0]] += r[4];
            }
            check.out(String.format(Locale.ROOT, "     crossing, round %d, %s: the one in the hut %d of %d left, of the squad %d of %d, %d ticks, taken %s; down in the water %d, over %d,"
                    + " most swimming at once %d; ticks one swam %d, with one on the bank firing %d; crossings begun %d, with smoke %d; swam back %d; the one at the wall down %s, fired %d shots;"
                    + " the squad under fire %s, hurt %d times; the drill: the main body swam before the far side's security was over %d ticks, the near side's before the main body %d,"
                    + " the most of the main body swimming at once %d; watches %d, looks along the bank %d, reports %d, places in one %d",
                    i + 1, names[r[0]], r[1], 1, r[2], SQUAD, r[3], r[4] < 0 ? "never" : "at tick " + r[4], r[5], r[6], r[7], r[8], r[9], r[11], r[10], r[12],
                    r[13] < 0 ? "never" : "at tick " + r[13], r[16], r[15] > 0 ? "yes" : "never", r[14], r[17], r[18], r[19], r[22], r[23], r[20], r[21]));
        }
        for (int a = 0; a < 4; a++)
            check.out(String.format(Locale.ROOT, "     crossing, %s, %d rounds: the bank taken in %d (%.0f ticks on average); of the squad %.0f of %d left, down in the water %.0f, over %.0f;"
                    + " most swimming at once %.1f on average; of the ticks one swam %.0f%% with one on the bank firing; crossings begun %.0f, with smoke %.0f; swam back %.0f; hurt %.0f times",
                    names[a], n[a], taken[a], taken[a] == 0 ? 0 : takenTicks[a] / taken[a], sum[a][2], n[a] * SQUAD, sum[a][5], sum[a][6], n[a] == 0 ? 0 : sum[a][7] / n[a],
                    sum[a][8] == 0 ? 0 : 100 * sum[a][9] / sum[a][8], sum[a][11], sum[a][10], sum[a][12], sum[a][14]));
        check.check("crossing: all rounds ran", results.size() == ROUNDS);
        check.check(String.format(Locale.ROOT, "crossing: with no order the squad swims nowhere and does not cross (ticks swum %.0f, crossings %.0f, of %d rounds)", sum[WATCH][8], sum[WATCH][11], n[WATCH]),
                n[WATCH] > 0 && sum[WATCH][8] == 0 && sum[WATCH][11] == 0);
        check.check(String.format(Locale.ROOT, "crossing: with no order it watches the bank, looks along it and reports the foe across to its staff, with places to cross (watches %.0f, looks %.0f, reports %.0f, places %.0f, of %d rounds)",
                sum[WATCH][22], sum[WATCH][23], sum[WATCH][20], sum[WATCH][21], n[WATCH]), sum[WATCH][22] >= n[WATCH] && sum[WATCH][23] > n[WATCH] && sum[WATCH][20] >= n[WATCH] && sum[WATCH][21] >= n[WATCH]);
        check.check(String.format(Locale.ROOT, "crossing: ordered, it crosses once in every round, and some get over (begun %.0f of %d rounds, over %.0f)", sum[ORDER][11], n[ORDER], sum[ORDER][6]),
                n[ORDER] > 0 && sum[ORDER][11] == n[ORDER] && sum[ORDER][6] > 0);
        check.check(String.format(Locale.ROOT, "crossing: ordered, the far side's security is over before the main body swims, and the near side's goes last (%.0f, %.0f ticks out of turn)",
                sum[ORDER][17], sum[ORDER][18]), sum[ORDER][17] == 0 && sum[ORDER][18] == 0);
        int mostMainAll = 0;
        for (int[] r : results) if (r[0] == ORDER) mostMainAll = Math.max(mostMainAll, r[19]);
        check.check(String.format(Locale.ROOT, "crossing: ordered, the main body goes by pairs (at most %d of it swimming at once in a round)", mostMainAll), mostMainAll <= 2);
        check.check(String.format(Locale.ROOT, "crossing: ordered, most of the swimming is covered, from the bank or by those over (%.0f%%)", sum[ORDER][8] == 0 ? 0 : 100 * sum[ORDER][9] / sum[ORDER][8]),
                sum[ORDER][9] * 2 > sum[ORDER][8]);
        check.check(String.format(Locale.ROOT, "crossing: with a dry way over (a causeway) no watch and no crossing is begun (%.0f, %.0f)", sum[CAUSEWAY][22], sum[CAUSEWAY][11]),
                n[CAUSEWAY] == 1 && sum[CAUSEWAY][11] == 0 && sum[CAUSEWAY][22] == 0);
        return true;
    }
}
