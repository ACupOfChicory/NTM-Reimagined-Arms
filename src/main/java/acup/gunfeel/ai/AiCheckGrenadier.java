package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
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
 * The AI check's grenadier (AiGrenadier, stage 5 R3). Three of one side behind a row of low walls; 30 blocks off, a
 * grenadier (a gun with 40 mm grenades) and two of its own beside it, 4 blocks each way. No one but the grenadier has
 * a gun: what is measured is its shells alone, FIGHT ticks a round. Rounds by turns, aiGrenadier off (before: the
 * grenadier fights as a rifleman, the same gun, aimed straight) and on. Counted: its shots and lobs, the harm it does
 * to the other side and the tick of the first, the harm it does to its own side and to itself (must be none), who is
 * left of each side.
 */
final class AiCheckGrenadier {
    static final int X0 = 1160, Z0 = 0, W = 40, L = 56, FIGHT = 1200, ROUNDS = 8, THEM = 3;
    static final Identifier RIFLE = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");
    /** guns tried first, in this order; else the first found that makes a grenadier */
    static final List<String> PREFER = List.of("hbm:gun_congolake", "ntmgunfeel:gl54");
    static final double THEIR_Z = 10.5, OUR_Z = 40.5;
    /** the low walls in front of them; {x0, z, x1} */
    static final int[][] LOW = {{1168, 13, 1173}, {1177, 13, 1182}, {1186, 13, 1191}};

    private static AiCheckGrenadier counting;
    private static boolean listening;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks;
    private final List<Mob> ours = new ArrayList<>(), theirs = new ArrayList<>();
    private Mob grenadier;
    private Item gun;
    /** this round: its shots, its harm to them (hits), the tick of its first, its harm to its own and to itself */
    private int shots, lastShots, hits, firstHit, ownHits, selfHits, lobsBefore;
    /** per round: {on, ours left, theirs left, ticks, shots, lobs, hits, first hit tick (-1 none), own hits, self hits} */
    private final List<int[]> results = new ArrayList<>();

    AiCheckGrenadier(AiCheck check, ServerLevel level, int y) {
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
                AiCheckGrenadier c = counting;
                if (c == null || c.grenadier == null || e.getAmount() <= 0) return;
                Entity by = e.getSource().getEntity();
                if (by != c.grenadier || !(e.getEntity() instanceof Mob m)) return;
                if (m == c.grenadier) c.selfHits++;
                else if (c.ours.contains(m)) c.ownHits++;
                else if (c.theirs.contains(m)) {
                    c.hits++;
                    if (c.firstHit < 0) c.firstHit = c.ticks;
                }
            });
            listening = true;
            counting = this;
            AiBots.awake = true;
            gun = launcher();
            if (gun == null) {
                check.check("grenadier: a gun with 40 mm grenades to give the grenadier", false);
                counting = null;
                return true;
            }
            start(0);
            return false;
        }
        ticks++;
        AiBot gb = AiBots.get(grenadier);
        if (grenadier.isAlive() && gb != null && gb.gunner.shots != lastShots) {
            shots += gb.gunner.shots - lastShots;
            lastShots = gb.gunner.shots;
        }
        int left = 0, foes = 0;
        for (Mob m : ours) if (m.isAlive()) left++;
        for (Mob m : theirs) if (m.isAlive()) foes++;
        if (ticks < FIGHT && grenadier.isAlive() && foes > 0) return false;
        results.add(new int[] {round % 2, left, foes, ticks, shots, AiGrenadier.shots - lobsBefore, hits, firstHit, ownHits, selfHits});
        for (Mob m : ours) m.discard();
        for (Mob m : theirs) m.discard();
        ours.clear();
        theirs.clear();
        if (round + 1 >= ROUNDS) return finish();
        start(round + 1);
        return false;
    }

    /** the gun for the grenadier: one that makes a grenadier (AiRole), the preferred first */
    private Item launcher() {
        List<String> found = new ArrayList<>();
        Item first = null;
        for (Item item : BuiltInRegistries.ITEM) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            ItemStack stack = new ItemStack(item);
            try {
                if (AiRole.by(stack) != AiRole.GRENADIER) continue;
            } catch (RuntimeException e) {
                continue;
            }
            found.add(id.toString());
            if (first == null) first = item;
        }
        java.util.Collections.sort(found);
        check.out("     grenadier: guns that make a grenadier: " + found);
        for (String p : PREFER) if (found.contains(p)) return BuiltInRegistries.ITEM.getValue(Identifier.parse(p));
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
                for (int h = 0; h < 8; h++) set(x, y + h, z, air);
            }
        for (int[] w : LOW) for (int x = w[0]; x <= w[2]; x++) set(x, y, w[1], stone);
        TacticalMap.of(level).want(X0 + W / 2, y, Z0 + L / 2, L / 2 + 4, 8);
    }

    private Mob spawn(double x, double z, String team, float yaw, Item item) {
        Mob m = AiSpawn.spawn(level, new Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, item).get(0);
        if (item != gun) m.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        m.setPos(x, y, z);
        m.setYRot(yaw);
        m.setYHeadRot(yaw);
        m.setYBodyRot(yaw);
        AiTeams.join(level, m, team);
        AiBot b = AiBots.get(m);
        if (b != null) b.gunner.ammo.set(b.gunner.capacity() * 4);
        return m;
    }

    private void start(int r) {
        round = r;
        ticks = shots = hits = ownHits = selfHits = 0;
        firstHit = -1;
        AiGrenadier.on = r % 2 == 1;
        AiSquads.on = true;
        AiSquads.offTeams.clear();
        AiSquads.forget(level);
        build();
        Item rifle = BuiltInRegistries.ITEM.getValue(RIFLE);
        // (empty-handed: spawn takes their guns away again)
        for (int i = 0; i < THEM; i++) theirs.add(spawn(1170.5 + i * 9, Z0 + THEIR_Z, "gren_b", 0F, rifle));
        ours.add(spawn(1175.5, Z0 + OUR_Z, "gren_a", 180F, rifle));
        ours.add(spawn(1183.5, Z0 + OUR_Z, "gren_a", 180F, rifle));
        grenadier = spawn(1179.5, Z0 + OUR_Z, "gren_a", 180F, gun);
        ours.add(grenadier);
        AiBot gb = AiBots.get(grenadier);
        lastShots = gb == null ? 0 : gb.gunner.shots;
        lobsBefore = AiGrenadier.shots;
        AiBots.refreshTargets(level);
        long now = level.getGameTime();
        for (Mob a : ours) for (Mob b : theirs) {
            AiBot ba = AiBots.get(a), bb = AiBots.get(b);
            if (ba != null) ba.memory.seen(b.getId(), b.getX(), b.getY(), b.getZ(), now, 0);
            if (bb != null) bb.memory.seen(a.getId(), a.getX(), a.getY(), a.getZ(), now, 0);
        }
    }

    private boolean finish() {
        AiGrenadier.on = true;
        counting = null;
        String[] names = {"aiGrenadier off (before)", "aiGrenadier on"};
        double[][] sum = new double[2][10];
        int[] n = new int[2], firsts = new int[2];
        double[] firstSum = new double[2];
        for (int i = 0; i < results.size(); i++) {
            int[] r = results.get(i);
            n[r[0]]++;
            for (int k = 1; k < r.length; k++) if (k != 7) sum[r[0]][k] += r[k];
            if (r[7] >= 0) {
                firsts[r[0]]++;
                firstSum[r[0]] += r[7];
            }
            check.out(String.format(Locale.ROOT, "     grenadier, round %d, %s: left %d of ours, %d of theirs, %d ticks; the grenadier %d shots, %d lobs; its harm to them %d"
                    + " (first at tick %d), to its own %d, to itself %d", i + 1, names[r[0]], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9]));
        }
        for (int on = 0; on < 2; on++) {
            double[] s = sum[on];
            check.out(String.format(Locale.ROOT, "     grenadier, %s, %d rounds: left %.0f of ours of %d, %.0f of theirs of %d; its shots %.0f, lobs %.0f; its harm to them %.0f, the"
                    + " first in %d rounds, at tick %.0f on average; to its own %.0f, to itself %.0f", names[on], n[on], s[1], n[on] * 3, s[2], n[on] * THEM, s[4], s[5], s[6],
                    firsts[on], firsts[on] == 0 ? 0 : firstSum[on] / firsts[on], s[8], s[9]));
        }
        check.out("     " + AiGrenadier.info());
        check.check("grenadier: all rounds ran", results.size() == ROUNDS);
        check.check(String.format(Locale.ROOT, "grenadier: it lobs its shells (%.0f lobs on, %.0f off) and harms them behind their walls (%.0f on, %.0f off)", sum[1][5], sum[0][5],
                sum[1][6], sum[0][6]), sum[1][5] > 0 && sum[0][5] == 0 && sum[1][6] > 0);
        check.check(String.format(Locale.ROOT, "grenadier: its shells harm none of its own and not itself (%.0f, %.0f on)", sum[1][8], sum[1][9]), sum[1][8] == 0 && sum[1][9] == 0);
        return true;
    }
}
