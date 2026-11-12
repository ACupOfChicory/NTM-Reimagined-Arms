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
 * The AI check's base of fire, on the squad's field (AiCheckSquad): three riflemen and one with a machine gun against
 * one foe rooted behind a stone wall two high; they saw it go behind it. Rounds by turns without roles (AiRole off:
 * the machine gunner is one more rifleman) and with them. The foe is an AI mob too, so its suppression scale is the
 * one a player there would have. Counted: the ticks one of the squad was on its way (round the side, to other cover,
 * after the foe) and of those the ticks the foe's scale was at 50 or more; the same over the whole round; the machine
 * gunner's shots, its flanks and hunts; flanks; hits on those on their way, and on the foe.
 */
final class AiCheckBase {
    static final int X0 = 400, Z0 = 4, SIZE = 48, RUN = 1200, ROUNDS = 6, SQUAD = 4;
    static final double FX = 424.5, FZ = 14.5;
    static final int WALL_X0 = 421, WALL_X1 = 427, WALL_Z = 17;
    static final double START_Z = 37.5;
    static final double[] START_X = {418.5, 422.5, 426.5, 430.5};
    static final int[][] FRONT = {{417, 30}, {421, 30}, {427, 30}, {431, 30}};
    static final int[][] SIDE = {{411, 21}, {411, 22}, {437, 21}, {437, 22}};
    /** which of the squad has the machine gun */
    static final int GUNNER = 1;
    static final Identifier RIFLE = Identifier.fromNamespaceAndPath("hbm", "gun_carbine"), MG = Identifier.fromNamespaceAndPath("hbm", "gun_m2");
    /** the scale (a share) from which a player there is well suppressed */
    static final float HELD = 0.5F;

    private static AiCheckBase counting;
    private static boolean listening;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks;
    private final List<Mob> squad = new ArrayList<>();
    private Mob foe;
    /** this round: ticks one moved, of those the foe's scale held; ticks held in all; the scale summed; hits on movers, on the foe; the gunner's hunt ticks */
    private int moveTicks, moveHeld, held, hitsMoving, hitsFoe, gunnerHunts;
    private double scaleSum;
    private final java.util.Map<String, Integer> whys = new java.util.TreeMap<>();
    private final List<double[]> results = new ArrayList<>();

    AiCheckBase(AiCheck check, ServerLevel level, int y) {
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
                AiCheckBase c = counting;
                if (c == null || c.foe == null) return;
                if (e.getEntity() == c.foe && e.getSource().getEntity() instanceof Mob m && c.squad.contains(m)) c.hitsFoe++;
                else if (e.getEntity() instanceof Mob m && c.squad.contains(m) && e.getSource().getEntity() == c.foe) {
                    AiBot b = AiBots.get(m);
                    if (b != null && AiSquads.moving(b)) c.hitsMoving++;
                }
            });
            listening = true;
            counting = this;
            AiBots.awake = true;
            start(0);
            return false;
        }
        ticks++;
        foe.setHealth(foe.getMaxHealth());
        boolean moving = false;
        for (int i = 0; i < squad.size(); i++) {
            Mob m = squad.get(i);
            m.setHealth(m.getMaxHealth());
            AiBot b = AiBots.get(m);
            if (b == null) continue;
            moving |= AiSquads.moving(b);
            if (i == GUNNER && b.brain instanceof AiSoldier s && s.mode() == AiSoldier.Mode.SEARCH) gunnerHunts++;
            if (i == GUNNER && b.brain instanceof AiSoldier s) whys.merge(s.mode() + "/" + s.why, 1, Integer::sum);
        }
        AiBot f = AiBots.get(foe);
        float scale = f == null ? 0F : f.prone.suppressed();
        scaleSum += scale;
        if (scale >= HELD) held++;
        if (moving) {
            moveTicks++;
            if (scale >= HELD) moveHeld++;
        }
        if (ticks >= RUN) {
            int flanks = 0, gunnerFlanks = 0, gunnerShots = 0, shots = 0, sup = 0;
            for (int i = 0; i < squad.size(); i++) {
                AiBot b = AiBots.get(squad.get(i));
                if (b == null || !(b.brain instanceof AiSoldier s)) continue;
                flanks += s.flanks;
                sup += s.suppressions;
                shots += b.gunner.shots;
                if (i == GUNNER) {
                    gunnerFlanks = s.flanks;
                    gunnerShots = b.gunner.shots;
                }
            }
            AiBot gb = AiBots.get(squad.get(GUNNER));
            check.out(String.format(Locale.ROOT, "     base, round %d: the gunner (%s): pulls %d, held for a friend %d, for its cover %d, bursts for those moving %d; %s", round + 1,
                    gb == null ? "-" : AiRole.of(gb), gb == null ? 0 : gb.gunner.pulls, gb == null ? 0 : gb.gunner.holds, gb == null ? 0 : gb.gunner.coverHolds,
                    gb != null && gb.brain instanceof AiSoldier gs ? gs.suppressions : 0, whys));
            whys.clear();
            results.add(new double[] {round % 2, moveTicks, moveHeld, held, scaleSum / ticks, flanks, gunnerFlanks, gunnerHunts, gunnerShots, shots, hitsMoving, hitsFoe, sup});
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

    private Mob spawn(double x, double z, String team, float yaw, Identifier gun) {
        List<Mob> made = AiSpawn.spawn(level, new Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, BuiltInRegistries.ITEM.getValue(gun));
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
        ticks = moveTicks = moveHeld = held = hitsMoving = hitsFoe = gunnerHunts = 0;
        scaleSum = 0;
        AiRole.on = r % 2 == 1;
        AiSquads.on = true;
        AiSquads.offTeams.clear();
        AiSquads.forget(level);
        build();
        foe = spawn(FX, FZ, "base_foe", 0F, RIFLE);
        var speed = foe.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0);
        for (int i = 0; i < SQUAD; i++) squad.add(spawn(START_X[i], START_Z, "base_side", 180F, i == GUNNER ? MG : RIFLE));
        AiBots.refreshTargets(level);
        long now = level.getGameTime();
        AiBot f = AiBots.get(foe);
        for (Mob m : squad) {
            AiBot b = AiBots.get(m);
            if (b != null) {
                // the gun in its hands was another when the AI took it over
                b.roleFor = null;
                b.memory.seen(foe.getId(), FX, y, FZ, now, 0);
            }
            if (f != null) f.memory.seen(m.getId(), m.getX(), m.getY(), m.getZ(), now, 0);
        }
    }

    private boolean finishAll() {
        AiRole.on = true;
        counting = null;
        double[][] sum = new double[2][13];
        int[] n = new int[2];
        String[] names = {"no roles", "with roles"};
        for (int i = 0; i < results.size(); i++) {
            double[] r = results.get(i);
            int on = (int) r[0];
            n[on]++;
            for (int k = 1; k < r.length; k++) sum[on][k] += r[k];
            check.out(String.format(Locale.ROOT, "     base, round %d %s: one moving %.0f ticks, the foe's scale at 50 or more in %.0f of them; in %.0f of all %d;"
                    + " mean scale %.0f; flanks %.0f (the gunner's %.0f), the gunner hunting %.0f ticks, its shots %.0f of %.0f; bursts for those moving %.0f;"
                    + " hits on those moving %.0f, on the foe %.0f", i + 1, names[on], r[1], r[2], r[3], RUN, r[4] * 100, r[5], r[6], r[7], r[8], r[9], r[12], r[10], r[11]));
        }
        double[] share = new double[2], all = new double[2];
        for (int on = 0; on < 2; on++) {
            share[on] = sum[on][1] == 0 ? 0 : sum[on][2] / sum[on][1];
            all[on] = n[on] == 0 ? 0 : sum[on][3] / (n[on] * RUN);
            check.out(String.format(Locale.ROOT, "     base, %s: the foe's scale at 50 or more in %.0f%% of %.0f ticks one moved, %.0f%% of all, mean scale %.0f;"
                    + " flanks %.0f (the gunner's %.0f), the gunner hunting %.0f ticks, its shots %.0f of %.0f; hits on those moving %.0f, on the foe %.0f",
                    names[on], share[on] * 100, sum[on][1], all[on] * 100, n[on] == 0 ? 0 : sum[on][4] / n[on] * 100, sum[on][5], sum[on][6], sum[on][7], sum[on][8],
                    sum[on][9], sum[on][10], sum[on][11]));
        }
        check.check("the machine gunner fires", sum[1][8] > 0);
        check.check("with roles the machine gunner neither goes round the side nor after the foe", sum[1][6] == 0 && sum[1][7] == 0);
        check.check("with roles one of the riflemen still goes round the side", sum[1][5] > 0);
        check.check("with roles the foe is kept suppressed more of the time one of the squad moves", share[1] > share[0]);
        return true;
    }
}
