package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * The AI check's marksman (AiMarksman, stage 5 R2), on a long field with a hill. Four riflemen and a marksman (a
 * scoped gun) start 64 blocks from four riflemen behind a row of low walls; a hill rises behind the five, low walls
 * between. Rounds by turns, aiMarksman off (before: the marksman fights as a rifleman, the same gun) and on. Counted
 * for the marksman: its shots and those from more than BAND blocks off its target, its hits and those from that far,
 * the most of its shots from one place (within a block), how high it stood over its target at its shots, its perches;
 * who is left of each side.
 */
final class AiCheckMarksman {
    static final int X0 = 1100, Z0 = 0, W = 48, L = 112, FIGHT = 2400, ROUNDS = 12, SIDE = 4;
    /** a shot from farther than this is a marksman's, blocks */
    static final double BAND = 40;
    static final Identifier RIFLE = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");
    /** scoped guns tried first, in this order; else the first scoped gun found */
    static final List<String> PREFER = List.of("gun_mas36", "gun_stg77", "gun_g3", "gun_amat", "gun_heavy_revolver_lilmac");
    static final double THEIR_Z = 10.5, OUR_Z = 74.5;
    /** the low walls: theirs in front of them, the middle ones; {x0, z, x1} */
    static final int[][] LOW = {{1108, 14, 1111}, {1116, 14, 1119}, {1124, 14, 1127}, {1132, 14, 1135},
            {1106, 40, 1109}, {1114, 44, 1117}, {1122, 40, 1125}, {1130, 44, 1133}, {1138, 40, 1141},
            {1110, 56, 1113}, {1120, 58, 1123}, {1130, 56, 1133}};
    /** the hill behind ours: its foot, its top, the steps between; x from HX0 to HX1 */
    static final int HX0 = 1108, HX1 = 1139, HZ0 = 80, HZ_TOP = 92, HZ1 = 104, HILL = 6;

    private static AiCheckMarksman counting;
    private static boolean listening;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks;
    private final List<Mob> ours = new ArrayList<>(), theirs = new ArrayList<>();
    private Mob marksman;
    private Item gun;
    /** this round: its shots, from far; its hits, from far; up over the target summed at shots; shots by place */
    private int shots, farShots, hits, farHits, lastShots, perchesBefore;
    private double upSum;
    private final java.util.Map<Long, Integer> places = new java.util.HashMap<>();
    /** per round: {on, ours left, theirs left, ticks, shots, far shots, hits, far hits, most from one place, up sum *10, perches, marksman alive} */
    private final List<int[]> results = new ArrayList<>();

    AiCheckMarksman(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        if (!started) {
            started = true;
            for (int cx = X0 >> 4; cx <= (X0 + W) >> 4; cx++)
                for (int cz = Z0 >> 4; cz <= (Z0 + L) >> 4; cz++) level.setChunkForced(cx, cz, true);
            if (!listening) NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, e -> {
                AiCheckMarksman c = counting;
                if (c == null || c.marksman == null || e.getSource().getEntity() != c.marksman || !(e.getEntity() instanceof Mob m) || !c.theirs.contains(m)) return;
                c.hits++;
                if (Math.hypot(m.getX() - c.marksman.getX(), m.getZ() - c.marksman.getZ()) > BAND) c.farHits++;
            });
            listening = true;
            counting = this;
            AiBots.awake = true;
            gun = scoped();
            if (gun == null) {
                check.check("marksman: a scoped gun to give the marksman", false);
                counting = null;
                return true;
            }
            start(0);
            return false;
        }
        ticks++;
        AiBot mb = AiBots.get(marksman);
        if (marksman.isAlive() && mb != null && mb.gunner.shots != lastShots) {
            int n = mb.gunner.shots - lastShots;
            lastShots = mb.gunner.shots;
            TargetMemory.Contact c = mb.memory.focus(marksman.getX(), marksman.getZ());
            shots += n;
            if (c != null) {
                if (Math.hypot(c.x - marksman.getX(), c.z - marksman.getZ()) > BAND) farShots += n;
                upSum += n * (marksman.getY() - c.y);
            }
            places.merge(BlockPos.asLong(marksman.getBlockX(), marksman.getBlockY(), marksman.getBlockZ()), n, Integer::sum);
        }
        int left = 0, foes = 0;
        for (Mob m : ours) if (m.isAlive()) left++;
        for (Mob m : theirs) if (m.isAlive()) foes++;
        if (ticks < FIGHT && left > 0 && foes > 0) return false;
        int most = 0;
        // one place: within a block of another (a step aside to the same perch is the same place)
        for (var e : places.entrySet()) {
            int sum = 0;
            for (var o : places.entrySet())
                if (Math.abs(BlockPos.getX(o.getKey()) - BlockPos.getX(e.getKey())) <= 1 && Math.abs(BlockPos.getZ(o.getKey()) - BlockPos.getZ(e.getKey())) <= 1) sum += o.getValue();
            most = Math.max(most, sum);
        }
        results.add(new int[] {round % 2, left, foes, ticks, shots, farShots, hits, farHits, most, (int) Math.round(upSum * 10), AiMarksman.perches - perchesBefore, marksman.isAlive() ? 1 : 0});
        for (Mob m : ours) m.discard();
        for (Mob m : theirs) m.discard();
        ours.clear();
        theirs.clear();
        if (round + 1 >= ROUNDS) return finish();
        start(round + 1);
        return false;
    }

    /** the gun for the marksman: a scoped one that makes it a marksman (AiRole), the preferred first */
    private Item scoped() {
        List<String> found = new ArrayList<>();
        net.minecraft.world.entity.Mob holder = AiCheck.holder(level);
        Item first = null;
        for (Item item : BuiltInRegistries.ITEM) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (!id.getNamespace().equals("hbm") || !id.getPath().startsWith("gun_")) continue;
            ItemStack stack = new ItemStack(item);
            try {
                if (AiCheck.role(holder, stack) != AiRole.MARKSMAN) continue;
            } catch (RuntimeException e) {
                continue;
            }
            found.add(id.getPath());
            if (first == null) first = item;
        }
        java.util.Collections.sort(found);
        check.out("     marksman: scoped guns that make a marksman: " + found);
        for (String p : PREFER) if (found.contains(p)) return BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", p));
        return first;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void build() {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + W; x++)
            for (int z = Z0; z < Z0 + L; z++) {
                set(x, y - 1, z, stone);
                for (int h = 0; h < 10; h++) set(x, y + h, z, air);
            }
        for (int[] w : LOW) for (int x = w[0]; x <= w[2]; x++) set(x, y, w[1], stone);
        // the hill: a block up every two blocks from its foot to its top, down again behind it
        for (int x = HX0; x <= HX1; x++)
            for (int z = HZ0; z <= HZ1; z++) {
                int h = z <= HZ_TOP ? Math.min(HILL, (z - HZ0) / 2 + 1) : Math.min(HILL, (HZ1 - z) / 2 + 1);
                for (int k = 0; k < h; k++) set(x, y + k, z, stone);
            }
        TacticalMap.of(level).want(X0 + W / 2, y, Z0 + L / 2, L / 2 + 4, 10);
    }

    private Mob spawn(double x, double z, String team, float yaw, Item item) {
        Mob m = AiSpawn.spawn(level, new Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, item).get(0);
        m.setPos(x, y, z);
        m.setYRot(yaw);
        m.setYHeadRot(yaw);
        m.setYBodyRot(yaw);
        AiTeams.join(level, m, team);
        AiBot b = AiBots.get(m);
        if (b != null) b.gunner.ammo.set(b.gunner.capacity() * 3);
        return m;
    }

    private void start(int r) {
        round = r;
        ticks = shots = farShots = hits = farHits = 0;
        upSum = 0;
        places.clear();
        AiMarksman.on = r % 2 == 1;
        AiSquads.on = true;
        AiSquads.offTeams.clear();
        AiSquads.forget(level);
        build();
        Item rifle = BuiltInRegistries.ITEM.getValue(RIFLE);
        for (int i = 0; i < SIDE; i++) {
            ours.add(spawn(1112.5 + i * 7, Z0 + OUR_Z, "marks_a", 180F, rifle));
            theirs.add(spawn(1110.5 + i * 8, Z0 + THEIR_Z, "marks_b", 0F, rifle));
        }
        marksman = spawn(1123.5, Z0 + OUR_Z + 3, "marks_a", 180F, gun);
        ours.add(marksman);
        AiBot mb = AiBots.get(marksman);
        lastShots = mb == null ? 0 : mb.gunner.shots;
        perchesBefore = AiMarksman.perches;
        AiBots.refreshTargets(level);
        long now = level.getGameTime();
        for (Mob a : ours) for (Mob b : theirs) {
            AiBot ba = AiBots.get(a), bb = AiBots.get(b);
            if (ba != null) ba.memory.seen(b.getId(), b.getX(), b.getY(), b.getZ(), now, 0);
            if (bb != null) bb.memory.seen(a.getId(), a.getX(), a.getY(), a.getZ(), now, 0);
        }
    }

    private boolean finish() {
        AiMarksman.on = true;
        counting = null;
        String[] names = {"aiMarksman off (before)", "aiMarksman on"};
        double[][] sum = new double[2][12];
        int[] n = new int[2];
        for (int i = 0; i < results.size(); i++) {
            int[] r = results.get(i);
            n[r[0]]++;
            for (int k = 1; k < r.length; k++) sum[r[0]][k] += r[k];
            check.out(String.format(Locale.ROOT, "     marksman, round %d, %s: left %d of ours, %d of theirs, %d ticks; the marksman %s, %d shots, %d from over %.0f blocks; hits %d, %d from"
                    + " that far; at most %d shots from one place; %.1f blocks over its target at its shots; perches %d", i + 1, names[r[0]], r[1], r[2], r[3], r[11] == 1 ? "alive" : "down",
                    r[4], r[5], BAND, r[6], r[7], r[8], r[4] == 0 ? 0 : r[9] / 10.0 / r[4], r[10]));
        }
        double[] share = new double[2], up = new double[2], one = new double[2];
        for (int on = 0; on < 2; on++) {
            double[] s = sum[on];
            share[on] = s[4] == 0 ? 0 : s[5] / s[4];
            up[on] = s[4] == 0 ? 0 : s[9] / 10 / s[4];
            one[on] = s[4] == 0 ? 0 : s[8] / s[4];
            check.out(String.format(Locale.ROOT, "     marksman, %s, %d rounds: left %.0f of ours of %d, %.0f of theirs of %d; the marksman down in %.0f; its shots %.0f, from over %.0f blocks"
                    + " %.0f%%; hits %.0f, from that far %.0f; the most from one place %.0f%% of its shots; %.1f blocks over its target", names[on], n[on], s[1], n[on] * (SIDE + 1), s[2],
                    n[on] * SIDE, n[on] - s[11], s[4], BAND, 100 * share[on], s[6], s[7], 100 * one[on], up[on]));
        }
        check.out("     " + AiMarksman.info());
        check.check("marksman: all rounds ran", results.size() == ROUNDS);
        check.check(String.format(Locale.ROOT, "marksman: it takes perches (%.0f) and fires from afar, over %.0f blocks for most of its shots (%.0f%% on, %.0f%% off)", sum[1][10], BAND, 100 * share[1],
                100 * share[0]), sum[1][4] > 0 && share[1] >= 0.5 && sum[1][10] > 0);
        // the hill is HILL high; its shots from 1.3 to 4.8 blocks over the target on average over 25 runs (off: 0.0); 2 at
        // least failed at 1.8 and 2.0, 1.5 at 1.3: a block at least, and a block over its height as a rifleman
        check.check(String.format(Locale.ROOT, "marksman: it stands higher than its target (%.1f blocks on, %.1f off; a block at least, a block over off)", up[1], up[0]),
                up[1] >= 1 && up[1] >= up[0] + 1);
        check.check(String.format(Locale.ROOT, "marksman: it moves on after a few shots (the most from one place %.0f%% of its shots on, %.0f%% off)", 100 * one[1], 100 * one[0]),
                sum[1][4] > 0 && one[1] < one[0]);
        return true;
    }
}
