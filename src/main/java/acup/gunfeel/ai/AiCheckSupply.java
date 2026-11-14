package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The AI check's supplier (AiSupply, stage 5 R1). Rounds by twos, aiSupply off (before) and on: four quiet ones, then the fights.
 * The quiet rounds: four of one side a few blocks apart with no one to fight, three of them with nothing besides
 * what is in their guns, no grenade, no smoke grenade, the fourth their supplier. Counted: who has a magazine
 * besides its gun's, a grenade, a smoke grenade at the end; what was handed over; what the supplier has left.
 * The fights: five against five across a field of low walls, each with one magazine besides its gun's and one
 * grenade, one of the first five the supplier (in both rounds: off, it is a rifleman with a role's name). Counted
 * for the first five: the share of their ticks short of rounds (less than a magazine besides the gun's), with no
 * grenade, with a dry gun; grenades thrown; who is left of each side; what was handed over.
 * The rounds of the fallen: the quiet four, the supplier put down at tick FALLS; one of the others takes its stock up.
 * The rounds out of rounds, aiSupplyOut off (before) and on: four with nothing besides what is in their guns and no
 * supplier against four with a magazine each besides. Counted: the share of ours' ticks falling back, who is left.
 */
final class AiCheckSupply {
    static final int X0 = 920, Z0 = 4, SIZE = 48, QUIET = 500, FIGHT = 2400, QUIET_ROUNDS = 4, FALLEN_ROUNDS = 2, FIGHT_ROUNDS = 12, OUT_ROUNDS = 4, ROUNDS = 22, OUT_TICKS = 1200, SQUAD = 4, SIDE = 5;
    /** the supplier falls at this tick of a round of the fallen */
    static final int FALLS = 20;
    static final Identifier GUN = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");
    static final double[][] QUIET_AT = {{940.5, 26.5}, {944.5, 24.5}, {947.5, 29.5}, {943.5, 31.5}};
    static final int[][] WALLS = {{930, 18, 933}, {938, 18, 941}, {947, 18, 950}, {955, 18, 958}, {930, 34, 933}, {938, 34, 941}, {947, 34, 950}, {955, 34, 958},
            {934, 26, 936}, {943, 26, 945}, {952, 26, 954}};

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks;
    private final List<Mob> ours = new ArrayList<>(), theirs = new ArrayList<>();
    private int runsBefore, gaveUpBefore, magsBefore, grenadesBefore, smokesBefore, leftBefore, takenBefore, outsBefore;
    private long ourTicks, shortTicks, noGrenadeTicks, dryTicks, fallTicks;
    private int supplierTicks;
    private final int[] idleBefore = new int[6];
    /** per round: {on, fight, left of ours, left of theirs, ticks, with a magazine, with a grenade, with a smoke grenade, runs, given up, magazines, grenades, smoke grenades handed,
     *  short of rounds permille, no grenade permille, dry permille, grenades thrown, the stock left (magazines*10000 + grenades*100 + smoke grenades)} */
    private final List<int[]> results = new ArrayList<>();

    AiCheckSupply(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    private boolean fight() {
        return round >= QUIET_ROUNDS + FALLEN_ROUNDS && !out();
    }

    /** the supplier is put down at the start: the others take its stock up */
    private boolean fallen() {
        return round >= QUIET_ROUNDS && round < QUIET_ROUNDS + FALLEN_ROUNDS;
    }

    /** ours have nothing besides what is in their guns, and no supplier: aiSupplyOut off and on */
    private boolean out() {
        return round >= QUIET_ROUNDS + FALLEN_ROUNDS + FIGHT_ROUNDS;
    }

    /** the kind of this round, as kept in the results: quiet 0, fight 1, fallen 2, out 3 */
    private int kind() {
        return out() ? 3 : fight() ? 1 : fallen() ? 2 : 0;
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
        ticks++;
        if (fallen() && ticks == FALLS) ours.get(0).hurtServer(level, level.damageSources().generic(), 10000F);
        int left = 0, foes = 0;
        for (Mob m : ours) {
            if (!m.isAlive()) continue;
            left++;
            AiBot b = AiBots.get(m);
            if (b == null) continue;
            ourTicks++;
            if (m == ours.get(0)) supplierTicks++;
            if (!b.gunner.ammo.infinite() && b.gunner.ammo.reserve() < b.gunner.capacity()) shortTicks++;
            if (b.grenades <= 0) noGrenadeTicks++;
            if (b.gunner.dry()) dryTicks++;
            if (b.confidence.fallsBack(level.getGameTime())) fallTicks++;
        }
        for (Mob m : theirs) if (m.isAlive()) foes++;
        boolean over = fight() ? ticks >= FIGHT || left == 0 || foes == 0 : out() ? ticks >= OUT_TICKS || left == 0 || foes == 0 : ticks >= (fallen() ? 2 * QUIET : QUIET);
        if (!over) return false;
        int withMag = 0, withGrenade = 0, withSmoke = 0, thrown = 0, stock = 0, heirs = 0, fallBacks = 0;
        for (Mob m : ours) {
            AiBot b = AiBots.get(m);
            if (b == null) continue;
            if (b.brain instanceof AiSoldier so) {
                thrown += so.thrown;
                fallBacks += so.fallBacks;
            }
            if (b.assigned == AiRole.SUPPLIER) {
                if (m != ours.get(0)) heirs++;
                if (!m.isAlive()) continue;
                int[] st = AiSupply.stock(b);
                stock = st[0] * 10000 + st[1] * 100 + st[2];
                continue;
            }
            if (!m.isAlive()) continue;
            if (b.gunner.ammo.reserve() >= b.gunner.capacity()) withMag++;
            if (b.grenades > 0) withGrenade++;
            if (b.smokes > 0) withSmoke++;
        }
        long n = Math.max(1, ourTicks);
        results.add(new int[] {round % 2, kind(), left, foes, ticks, withMag, withGrenade, withSmoke, AiSupply.runs - runsBefore, AiSupply.gaveUp - gaveUpBefore,
                AiSupply.mags - magsBefore, AiSupply.grenades - grenadesBefore, AiSupply.smokes - smokesBefore, (int) (1000 * shortTicks / n), (int) (1000 * noGrenadeTicks / n),
                (int) (1000 * dryTicks / n), thrown, stock, heirs, AiSupply.left - leftBefore, AiSupply.taken - takenBefore,
                (int) (1000L * fallTicks / n), fallBacks, ourOuts() - outsBefore});
        if (fight()) {
            StringBuilder why = new StringBuilder();
            String[] names = {"not free itself", "no squad", "none short", "one short out in sight", "too far", "given to lately"};
            for (int i = 0; i < 6; i++) why.append(i == 0 ? "" : ", ").append(names[i]).append(' ').append(AiSupply.idle[i] - idleBefore[i]);
            check.out(String.format(Locale.ROOT, "     supply, round %d: the supplier lived %d of %d ticks; its looks that found no one to go to: %s", round + 1, supplierTicks, ticks, why));
        }
        for (Mob m : ours) m.discard();
        for (Mob m : theirs) m.discard();
        ours.clear();
        theirs.clear();
        if (round + 1 >= ROUNDS) return finish();
        start(round + 1);
        return false;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void build(boolean walls) {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                set(x, y - 1, z, stone);
                for (int h = 0; h < 5; h++) set(x, y + h, z, air);
            }
        if (walls) for (int[] w : WALLS) for (int x = w[0]; x <= w[2]; x++) for (int h = 0; h < 2; h++) set(x, y + h, w[1], stone);
        TacticalMap.of(level).want(X0 + SIZE / 2, y, Z0 + SIZE / 2, SIZE / 2 + 4, 6);
    }

    private Mob spawn(double x, double z, String team, float yaw, int magazines, int grenades, int smokes) {
        Mob m = AiSpawn.spawn(level, new Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, BuiltInRegistries.ITEM.getValue(GUN)).get(0);
        m.setPos(x, y, z);
        m.setYRot(yaw);
        m.setYHeadRot(yaw);
        m.setYBodyRot(yaw);
        AiTeams.join(level, m, team);
        AiBot b = AiBots.get(m);
        if (b != null) {
            b.gunner.ammo.set(b.gunner.capacity() * magazines);
            b.grenades = grenades;
            b.smokes = smokes;
        }
        return m;
    }

    private void start(int r) {
        round = r;
        ticks = 0;
        ourTicks = shortTicks = noGrenadeTicks = dryTicks = fallTicks = 0;
        supplierTicks = 0;
        System.arraycopy(AiSupply.idle, 0, idleBefore, 0, 6);
        AiSupply.on = out() || r % 2 == 1;
        AiSupply.outOn = !out() || r % 2 == 1;
        AiSquads.on = true;
        AiSquads.offTeams.clear();
        AiSquads.forget(level);
        AiSupply.forget(level);
        build(fight() || out());
        if (out()) {
            // no supplier: ours with nothing besides their guns', theirs with a magazine each
            for (int i = 0; i < SQUAD; i++) {
                ours.add(spawn(936.5 + i * 6, Z0 + 40.5, "supply_a", 180F, 0, 0, 0));
                theirs.add(spawn(936.5 + i * 6, Z0 + 8.5, "supply_b", 0F, 1, 0, 0));
            }
        } else if (!fight()) {
            // the fallen rounds: the same four, no one to fight
            for (int i = 0; i < SQUAD; i++) ours.add(spawn(QUIET_AT[i][0], QUIET_AT[i][1], "supply_a", 180F, i == 0 ? AiAdapter.RESERVE_MAGS : 0, i == 0 ? 2 : 0, i == 0 ? 1 : 0));
        } else {
            for (int i = 0; i < SIDE; i++) {
                ours.add(spawn(934.5 + i * 5, Z0 + 40.5, "supply_a", 180F, 1, 1, 1));
                theirs.add(spawn(934.5 + i * 5, Z0 + 8.5, "supply_b", 0F, 1, 1, 1));
            }
        }
        // the first of ours is the supplier, by word, in both rounds (off: a role's name and no more)
        AiBot supplier = out() ? null : AiBots.get(ours.get(0));
        if (supplier != null) {
            AiRole.assign(supplier, AiRole.SUPPLIER);
            supplier.supply = null;
            supplier.mob.getPersistentData().remove(AiSupply.STOCK_KEY);
        }
        AiBots.refreshTargets(level);
        runsBefore = AiSupply.runs;
        gaveUpBefore = AiSupply.gaveUp;
        magsBefore = AiSupply.mags;
        grenadesBefore = AiSupply.grenades;
        smokesBefore = AiSupply.smokes;
        leftBefore = AiSupply.left;
        takenBefore = AiSupply.taken;
        outsBefore = ourOuts();
        if (fight() || out()) {
            long now = level.getGameTime();
            for (Mob a : ours) for (Mob b : theirs) {
                AiBot ba = AiBots.get(a), bb = AiBots.get(b);
                if (ba != null) ba.memory.seen(b.getId(), b.getX(), b.getY(), b.getZ(), now, 0);
                if (bb != null) bb.memory.seen(a.getId(), a.getX(), a.getY(), a.getZ(), now, 0);
            }
        }
    }

    /** times a squad of ours was found out of rounds */
    private static int ourOuts() {
        return AiSupply.outs.getOrDefault("supply_a", 0);
    }

    private boolean finish() {
        AiSupply.on = true;
        AiSupply.outOn = true;
        String[] names = {"aiSupply off (before)", "aiSupply on"}, outNames = {"aiSupplyOut off (before)", "aiSupplyOut on"};
        double[][][] sum = new double[4][2][24];
        int[][] n = new int[4][2];
        for (int i = 0; i < results.size(); i++) {
            int[] r = results.get(i);
            n[r[1]][r[0]]++;
            for (int k = 2; k < r.length; k++) sum[r[1]][r[0]][k] += r[k];
            if (r[1] == 3)
                check.out(String.format(Locale.ROOT, "     supply, round %d, four out of rounds against four, %s: left %d of ours, %d of theirs, %d ticks; ours falling back %.1f%% of their ticks,"
                        + " fell back %d times; dry %.1f%%; the squad found out of rounds %d times", i + 1, outNames[r[0]], r[2], r[3], r[4], r[21] / 10.0, r[22], r[15] / 10.0, r[23]));
            else if (r[1] == 2)
                check.out(String.format(Locale.ROOT, "     supply, round %d, the supplier put down at tick %d, %s, %d ticks: stocks left %d, taken up %d, another is the supplier now %d; of the two others"
                        + " %d have a magazine besides the gun's, %d a grenade; handed over %d magazines, %d grenades; the stock now %d/%d/%d", i + 1, FALLS, names[r[0]], r[4], r[19], r[20], r[18],
                        r[5], r[6], r[10], r[11], r[17] / 10000, r[17] / 100 % 100, r[17] % 100));
            else if (r[1] == 0)
                check.out(String.format(Locale.ROOT, "     supply, round %d, no one to fight, %s, %d ticks: of the three with nothing, %d have a magazine besides the gun's, %d a grenade, %d a smoke grenade;"
                        + " runs %d, given up %d; handed over %d magazines, %d grenades, %d smoke grenades; the supplier has %d/%d/%d left", i + 1, names[r[0]], r[4], r[5], r[6], r[7], r[8],
                        r[9], r[10], r[11], r[12], r[17] / 10000, r[17] / 100 % 100, r[17] % 100));
            else
                check.out(String.format(Locale.ROOT, "     supply, round %d, five against five, %s: left %d of ours, %d of theirs, %d ticks; ours short of rounds %.1f%% of their ticks, with no grenade %.1f%%,"
                        + " dry %.1f%%; grenades thrown %d; runs %d, given up %d; handed over %d magazines, %d grenades, %d smoke grenades", i + 1, names[r[0]], r[2], r[3], r[4], r[13] / 10.0,
                        r[14] / 10.0, r[15] / 10.0, r[16], r[8], r[9], r[10], r[11], r[12]));
        }
        for (int on = 0; on < 2; on++) {
            double[] s = sum[3][on];
            int k = Math.max(1, n[3][on]);
            check.out(String.format(Locale.ROOT, "     supply, four out of rounds against four, %s, %d rounds: left %.0f of ours of %d, %.0f of theirs of %d; mean %.0f ticks;"
                    + " ours falling back %.1f%% of their ticks, fell back %.0f times; dry %.1f%%", outNames[on], n[3][on], s[2], n[3][on] * SQUAD, s[3], n[3][on] * SQUAD, s[4] / k,
                    s[21] / k / 10, s[22], s[15] / k / 10));
        }
        for (int on = 0; on < 2; on++) {
            double[] s = sum[1][on];
            int k = Math.max(1, n[1][on]);
            check.out(String.format(Locale.ROOT, "     supply, five against five, %s, %d rounds: left %.0f of ours of %d, %.0f of theirs; mean %.0f ticks; ours short of rounds %.1f%% of their ticks,"
                    + " with no grenade %.1f%%, dry %.1f%%; grenades thrown %.0f; runs %.0f, given up %.0f; handed over %.0f magazines, %.0f grenades, %.0f smoke grenades", names[on], n[1][on],
                    s[2], n[1][on] * SIDE, s[3], s[4] / k, s[13] / k / 10, s[14] / k / 10, s[15] / k / 10, s[16], s[8], s[9], s[10], s[11], s[12]));
        }
        double[] off = sum[0][0], on = sum[0][1];
        int quiet = n[0][1] * (SQUAD - 1);
        check.check("supply: all rounds ran", results.size() == ROUNDS);
        check.check("supply: with no one to fight the supplier gives each of the three magazines, grenades and a smoke grenade (" + (int) on[5] + ", " + (int) on[6] + ", " + (int) on[7]
                + " of " + quiet + "), and its stock is the less for it", quiet > 0 && on[5] == quiet && on[6] == quiet && on[7] == quiet && on[10] > 0 && on[17] < n[0][1] * (AiSupply.MAGS * 10000
                        + AiSupply.GRENADES * 100 + AiSupply.SMOKES));
        check.check("supply: with aiSupply off no one is given anything (" + (int) off[5] + ", " + (int) off[6] + ", " + (int) off[7] + ")", off[5] == 0 && off[6] == 0 && off[7] == 0 && off[8] == 0);
        check.check("supply: in the fights supplies are handed over (" + (int) sum[1][1][10] + " magazines, " + (int) sum[1][1][11] + " grenades), and none with aiSupply off",
                sum[1][1][10] + sum[1][1][11] > 0 && sum[1][0][10] + sum[1][0][11] + sum[1][0][8] == 0);
        double[] fOn = sum[2][1], fOff = sum[2][0];
        int others = n[2][1] * (SQUAD - 2);
        check.check("supply: a supplier down, one of its side takes its stock up and is the supplier (" + (int) fOn[20] + " of " + n[2][1] + " taken up, " + (int) fOn[18]
                + " new suppliers), and gives the two others magazines and grenades (" + (int) fOn[5] + ", " + (int) fOn[6] + " of " + others + "); with aiSupply off nothing is left or taken ("
                + (int) fOff[19] + ", " + (int) fOff[20] + ")", n[2][1] > 0 && fOn[20] == n[2][1] && fOn[18] == n[2][1] && fOn[5] == others && fOn[6] == others && fOff[19] == 0 && fOff[20] == 0);
        double[] oOn = sum[3][1], oOff = sum[3][0];
        double fallOn = oOn[21] / Math.max(1, n[3][1]) / 1000, fallOff = oOff[21] / Math.max(1, n[3][0]) / 1000;
        check.check(String.format(Locale.ROOT, "supply: a squad with nothing besides its guns' and no supplier falls back (falling back %.1f%% of the ticks with aiSupplyOut on, %.1f%% off;"
                + " found out of rounds %d times on, %d off)", 100 * fallOn, 100 * fallOff, (int) oOn[23], (int) oOff[23]), oOn[23] > 0 && oOff[23] == 0 && fallOn >= fallOff + 0.1);
        return true;
    }
}
