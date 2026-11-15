package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.Arrays;
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
 * The AI check's hill (cover in a fight that it can fire from, CoverQuery.Params.fight): a stone plain in the air with
 * a ridge right across it, ten high at its crest, its sides in steps of a block every two (every step is low cover from
 * the south for the cell north of it, but none to fire from: the next step up stands in the way); low walls three long
 * on the crest, on the slope that looks south and on the plain south of it. A squad of four starts on the plain north
 * of the ridge, out of sight of a foe that stands in the open on the plain south of it (it does not move; it fires);
 * each of the squad saw it go out of sight. With a fight's cover picked as now ({@link CoverQuery#pick}) and as before,
 * by turns, {@value #RUN} ticks a round, none of them to die. Counted over the squad's ticks in cover or out of it to
 * fire: whether its cover has a place to fire from, where it is (behind the crest, on it, on the slope facing the foe,
 * on the plain), its shots, the hits each way. Then the choice itself: a fight's cover query from places by the crest,
 * the foe where it stood.
 */
final class AiCheckHill {
    static final int X0 = 3000, Z0 = 0, SX = 96, SZ = 80, RUN = 1200, SQUAD = 4, ROUNDS = 4, WARM = 40, WARM_MAX = 600;
    /** the crest's z and height; a block lower every SLOPE blocks either way */
    static final int CREST_Z = 40, CREST_H = 10, SLOPE = 2;
    static final int MARGIN = 16;
    /** the low walls: x of their west end and z, three long along x, a block over the ground where they stand */
    static final int[][] WALLS = {{20, 40}, {46, 40}, {72, 40}, {30, 47}, {60, 47}, {16, 53}, {46, 53}, {76, 53}, {24, 62}, {56, 62}};
    static final double FOE_Z = 74.5, START_Z = 8.5;
    static final Identifier RIFLE = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");

    private static AiCheckHill counting;
    private static boolean listening;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private int round = -1, ticks, warm;
    private boolean warming;
    private final List<Mob> squad = new ArrayList<>();
    private Mob foe;
    // this round
    private long fighting, canFire;
    /** ticks in cover by where: behind the crest (north of it), on it, on the slope facing the foe, on the plain by the foe */
    private final long[] where = new long[4];
    private double over;
    private int hitsOn, hitsBy, shots;
    private final List<String> lines = new ArrayList<>();
    /** per round: now?, fighting ticks, with a place to fire, on the slope facing the foe, hits on the foe, on the squad, shots */
    private final List<double[]> results = new ArrayList<>();

    /** heights: rounds with height weighed (AiSoldier.heights) and not, the cover picked as now in both */
    private final boolean heights;

    AiCheckHill(AiCheck check, ServerLevel level, int y, boolean heights) {
        this.check = check;
        this.level = level;
        this.y = y;
        this.heights = heights;
    }

    private String how(boolean now) {
        return heights ? now ? "height weighed" : "height not weighed" : now ? "now" : "as before";
    }

    private void toggle(boolean now) {
        CoverQuery.pick = heights || now;
        AiSoldier.heights = !heights || now;
    }

    /** the second round of each pair picks as now */
    private boolean now() {
        return round % 2 == 1;
    }

    static int height(int z) {
        return Math.max(0, CREST_H - Math.abs(z - CREST_Z) / SLOPE);
    }

    boolean tick() {
        if (round < 0) {
            if (!listening) NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, e -> {
                AiCheckHill c = counting;
                if (c != null) c.hurt(e);
            });
            listening = true;
            counting = this;
            AiBots.awake = true;
            force(true);
            prepare(0);
            return false;
        }
        if (warming) {
            warm++;
            if (heights) {
                // the land's layers for the skyline and slopes (AiTerrain), as far as they get in WARM_MAX
                AiTerrain t = AiTerrain.of(level);
                t.want(X0 + SX / 2, Z0 + SZ / 2);
                if (warm >= WARM && (t.settled() && t.tile(X0 + SX / 2, Z0 + CREST_Z) != null || warm >= WARM_MAX)) start();
            } else if (warm >= WARM) start();
            return false;
        }
        ticks++;
        foe.setHealth(foe.getMaxHealth());
        for (Mob m : squad) {
            m.setHealth(m.getMaxHealth());
            AiBot b = AiBots.get(m);
            if (b == null || !(b.brain instanceof AiSoldier s) || s.spot() == null) continue;
            AiSoldier.Mode mode = s.mode();
            if (mode != AiSoldier.Mode.COVER && mode != AiSoldier.Mode.PEEK && mode != AiSoldier.Mode.HOLD) continue;
            fighting++;
            if (s.spot().hasPeek()) canFire++;
            over += m.getY() - foe.getY();
            where[place(m.getBlockZ() - Z0)]++;
        }
        if (ticks >= RUN) {
            for (Mob m : squad) {
                AiBot b = AiBots.get(m);
                if (b != null) shots += b.gunner.shots;
            }
            report();
            for (Mob m : squad) m.discard();
            foe.discard();
            squad.clear();
            foe = null;
            AiBots.refreshTargets(level);
            if (round + 1 >= ROUNDS) return finishAll();
            prepare(round + 1);
        }
        return false;
    }

    /** 0 behind the crest, 1 on it, 2 on the slope facing the foe, 3 on the plain by the foe */
    private static int place(int rz) {
        return rz < CREST_Z - 2 ? 0 : rz <= CREST_Z + 2 ? 1 : rz < CREST_Z + 2 * CREST_H ? 2 : 3;
    }

    private void hurt(LivingIncomingDamageEvent e) {
        if (foe == null || !(e.getSource().getEntity() instanceof Mob from)) return;
        if (e.getEntity() == foe && squad.contains(from)) hitsOn++;
        else if (from == foe && squad.contains(e.getEntity())) hitsBy++;
    }

    private void force(boolean on) {
        for (int cx = (X0 - MARGIN) >> 4; cx <= (X0 + SX + MARGIN) >> 4; cx++)
            for (int cz = (Z0 - MARGIN) >> 4; cz <= (Z0 + SZ + MARGIN) >> 4; cz++) level.setChunkForced(cx, cz, on);
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    private void build() {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SX; x++)
            for (int z = Z0; z < Z0 + SZ; z++) {
                int h = height(z - Z0);
                set(x, y - 1, z, stone);
                for (int k = 0; k < CREST_H + 4; k++) set(x, y + k, z, k < h ? stone : air);
            }
        for (int[] w : WALLS)
            for (int dx = 0; dx < 3; dx++) set(X0 + w[0] + dx, y + height(w[1]), Z0 + w[1], stone);
        TacticalMap.of(level).want(X0 + SX / 2, y + CREST_H / 2, Z0 + SZ / 2, SX / 2 + 4, CREST_H + 4);
    }

    private void prepare(int r) {
        round = r;
        build();
        warming = true;
        warm = 0;
    }

    private Mob spawn(double x, double z, String team, float yaw) {
        Mob m = AiSpawn.spawn(level, new Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, BuiltInRegistries.ITEM.getValue(RIFLE)).get(0);
        m.setPos(x, y, z);
        m.setYRot(yaw);
        m.setYHeadRot(yaw);
        m.setYBodyRot(yaw);
        var hp = m.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(1000);
        m.setHealth(1000);
        AiTeams.join(level, m, team);
        AiBot bot = AiBots.get(m);
        if (bot != null) {
            bot.gunner.ammo.setInfinite();
            bot.roleFor = null;
        }
        return m;
    }

    private void start() {
        warming = false;
        ticks = 0;
        fighting = canFire = 0;
        Arrays.fill(where, 0);
        over = 0;
        hitsOn = hitsBy = shots = 0;
        AiSquads.on = true;
        AiSquads.offTeams.clear();
        AiSquads.forget(level);
        toggle(now());
        foe = spawn(X0 + SX / 2 + 0.5, Z0 + FOE_Z, "hill_foe", 180F);
        var speed = foe.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0);
        for (int i = 0; i < SQUAD; i++) squad.add(spawn(X0 + SX / 2 - 6 + 4 * i + 0.5, Z0 + START_Z, "hill_side", 0F));
        AiBots.refreshTargets(level);
        long t = level.getGameTime();
        AiBot f = AiBots.get(foe);
        for (Mob m : squad) {
            AiBot b = AiBots.get(m);
            if (b != null) {
                b.memory.seen(foe.getId(), foe.getX(), y, foe.getZ(), t, 0);
                shots -= b.gunner.shots;
            }
            if (f != null) f.memory.seen(m.getId(), m.getX(), m.getY(), m.getZ(), t, 0);
        }
    }

    private void report() {
        lines.add(String.format(Locale.ROOT, "     hill, round %d, %s: in cover or out to fire %d ticks, with a place to fire from %d (%.0f%%),"
                + " %.1f blocks over the foe on average; behind the crest / on it / on the slope facing the foe / on the plain %s; shots %d, hits on the foe %d, on the squad %d",
                round + 1, how(now()), fighting, canFire, fighting == 0 ? 0 : 100.0 * canFire / fighting, fighting == 0 ? 0 : over / fighting,
                Arrays.toString(where), shots, hitsOn, hitsBy));
        results.add(new double[] {now() ? 1 : 0, fighting, canFire, where[2], hitsOn, hitsBy, shots});
    }

    /** the rounds picked as now (or as before) summed: fighting ticks, with a place to fire, on the slope, hits on, hits by, shots */
    private double[] sum(boolean now) {
        double[] s = new double[6];
        for (double[] r : results) if ((r[0] == 1) == now) for (int k = 0; k < 6; k++) s[k] += r[1 + k];
        return s;
    }

    /**
     * From each place by the crest (behind it, on it, just over it), the foe at its place on the plain: the cover a
     * soldier in a fight would pick. [places, found, with a place to fire from, on the slope facing the foe, tenths of
     * blocks over the foe summed]
     */
    private int[] choices(boolean now) {
        toggle(now);
        AiTerrain t = AiTerrain.peek(level);
        Skyline.Land land = t == null ? null : Skyline.of(t);
        int[] out = new int[5];
        TacticalMap map = TacticalMap.of(level);
        VoxelRay.Source voxels = AiBots.voxels(level);
        Vec3 eye = new Vec3(X0 + SX / 2 + 0.5, y + 1.62, Z0 + FOE_Z);
        for (int x = 20; x <= 76; x += 8)
            for (int z : new int[] {36, 39, 43}) {
                out[0]++;
                CoverQuery.Params p = new CoverQuery.Params();
                p.fight = true;
                p.peekBonus = CoverQuery.PEEK_FIGHT;
                p.othersSoft = true;
                p.rayBudget = 48;
                p.fightAt = 30;
                if (heights && now) {
                    p.up = CoverQuery.UP;
                    p.land = land;
                    p.reach = AiSoldier.SLOPE_REACH;
                }
                List<CoverQuery.Spot> found = CoverQuery.find(map, voxels, new Vec3(X0 + x + 0.5, y + height(z), Z0 + z + 0.5), List.of(eye), p, k -> false, 1);
                if (found.isEmpty()) continue;
                CoverQuery.Spot s = found.get(0);
                out[1]++;
                if (s.hasPeek()) out[2]++;
                if (place(BlockPos.getZ(s.cell()) - Z0) == 2) out[3]++;
                out[4] += (int) Math.round(10 * (BlockPos.getY(s.cell()) - y));
            }
        toggle(true);
        return out;
    }

    private boolean finishAll() {
        counting = null;
        toggle(true);
        AiSquads.forget(level);
        for (String s : lines) check.out(s);
        double[] before = sum(false), now = sum(true);
        for (double[] s : List.of(before, now))
            check.out(String.format(Locale.ROOT, "     hill, %s: %.0f ticks in cover, with a place to fire from %.0f%%, on the slope facing the foe %.0f;"
                    + " shots %.0f, hits on the foe %.0f, on the squad %.0f", how(s == now), s[0], s[0] == 0 ? 0 : 100 * s[1] / s[0], s[2], s[5], s[3], s[4]));
        int[] b = choices(false), n = choices(true);
        for (int[] c : List.of(b, n))
            check.out(String.format(Locale.ROOT, "     hill, cover a fight picks from %d places by the crest, %s: found %d, with a place to fire from %d, on the slope facing the foe %d,"
                    + " %.1f blocks over the ground on average", c[0], how(c == n), c[1], c[2], c[3], c[1] == 0 ? 0 : c[4] / 10.0 / c[1]));
        force(false);
        if (heights) return true;
        check.check(String.format(Locale.ROOT, "hill: a fight's cover by the crest is one to fire from (%d of %d places, %d as before)", n[2], n[0], b[2]),
                n[2] > b[2] && n[2] * 4 >= n[0] * 3);
        double fb = before[0] == 0 ? 0 : before[1] / before[0], fn = now[0] == 0 ? 0 : now[1] / now[0];
        check.check(String.format(Locale.ROOT, "hill: the squad's cover has a place to fire from more of its ticks (%.0f%%, %.0f%% as before)", 100 * fn, 100 * fb),
                now[0] > 0 && before[0] > 0 && fn > fb);
        return true;
    }
}
