package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * The AI check's river (T2, water): a stone plain in the air cut across by a river 12 wide and 3 deep. Four on the
 * south bank (no squad: each goes on its own) saw a foe go into a stone hut on the north bank (its door on the far
 * side) and hear it fire in there now and then ({@link #HEARD}); they go after it. By
 * turns: a ford (the bed a block under the banks, so one wades) 15 blocks to one side and a bridge 30 to the other;
 * the bridge alone; neither (the river is swum). Counted for each of the squad: whether it got across and when, and
 * over what (the bridge, the ford, swimming), its ticks with its eyes under water, the least air it had, drowning
 * hurts, the longest it stood wanting to go and made no block of way. Then one of the squad in the middle of the
 * river with the foe in the open on the north bank and in its sight: its shots and throws while it swims, how long
 * it takes to get out.
 */
final class AiCheckWater {
    static final int X0 = 2200, Z0 = 0, SX = 96, SZ = 48, RUN = 1200, OUT_RUN = 400, SQUAD = 4;
    /** the river, z from RZ0 to RZ1, 3 deep; the ford and the bridge, 3 wide each */
    static final int RZ0 = 20, RZ1 = 31, DEPTH = 3, XC = 2240, FORD_X0 = 2224, FORD_X1 = 2226, BRIDGE_X0 = 2269, BRIDGE_X1 = 2271;
    /** where the squad starts, and the foe's hut (its middle; 5 across, its door to the north) */
    static final double START_Z = 40.5;
    static final double[] START_X = {2237.5, 2239.5, 2241.5, 2243.5};
    static final int HUT_X = 2240, HUT_Z = 9;
    static final Identifier RIFLE = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");
    /**
     * how often the foe is heard in its hut, ticks; from one of its nine blocks in turn (TargetMemory takes no sound
     * from the same block within half a minute: 9 times this is more)
     */
    static final int HEARD = 70;

    enum Layout {
        FORD_AND_BRIDGE("a ford 15 off, a bridge 30 off"), BRIDGE("a bridge 30 off"), NONE("no ford, no bridge"), IN_WATER("in the water, the foe in sight");

        final String text;

        Layout(String text) {
            this.text = text;
        }
    }

    private static AiCheckWater counting;
    private static boolean listening;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks;
    private final List<Mob> squad = new ArrayList<>();
    private Mob foe;
    // this round, for each of the squad
    private int[] crossedAt, under, minAir, drowned, still, longestStill, swimShots, swimThrows, swimTicks, outAt;
    private boolean[] bridge, ford, swam;
    private double[] stillX, stillZ;
    private int[] shotsBefore, thrownBefore;
    private final List<String> lines = new ArrayList<>();
    private final List<int[]> totals = new ArrayList<>();

    AiCheckWater(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    private Layout layout() {
        return Layout.values()[round];
    }

    boolean tick() {
        if (!started) {
            started = true;
            for (int cx = (X0 >> 4) - 1; cx <= (X0 + SX) >> 4; cx++)
                for (int cz = (Z0 >> 4) - 1; cz <= (Z0 + SZ) >> 4; cz++) level.setChunkForced(cx, cz, true);
            if (!listening) NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, e -> {
                AiCheckWater c = counting;
                if (c == null || !e.getSource().is(DamageTypes.DROWN)) return;
                int i = c.squad.indexOf(e.getEntity());
                if (i >= 0) c.drowned[i]++;
            });
            listening = true;
            counting = this;
            AiBots.awake = true;
            start(0);
            return false;
        }
        ticks++;
        foe.setHealth(foe.getMaxHealth());
        if (layout() != Layout.IN_WATER && ticks % HEARD == 0) {
            int k = ticks / HEARD % 9;
            double hx = HUT_X - 1 + k % 3 + 0.5, hz = HUT_Z - 1 + k / 3 + 0.5;
            for (Mob m : squad) {
                AiBot b = AiBots.get(m);
                if (b != null) b.memory.heard(hx, y, hz, level.getGameTime(), true, false);
            }
        }
        boolean all = true;
        for (int i = 0; i < squad.size(); i++) {
            Mob m = squad.get(i);
            m.setHealth(m.getMaxHealth());
            AiBot b = AiBots.get(m);
            minAir[i] = Math.min(minAir[i], m.getAirSupply());
            if (m.isEyeInFluid(FluidTags.WATER)) under[i]++;
            boolean swims = AiWater.swims(m);
            if (b != null && b.brain instanceof AiSoldier s) {
                if (swims) {
                    swimTicks[i]++;
                    swimShots[i] += b.gunner.shots - shotsBefore[i];
                    swimThrows[i] += s.thrown + s.smoked - thrownBefore[i];
                }
                shotsBefore[i] = b.gunner.shots;
                thrownBefore[i] = s.thrown + s.smoked;
            }
            if (m.getZ() >= RZ0 && m.getZ() < RZ1 + 1) {
                if (m.getY() >= y - 0.1 && m.getBlockX() >= BRIDGE_X0 && m.getBlockX() <= BRIDGE_X1) bridge[i] = true;
                else if (m.getBlockX() >= FORD_X0 && m.getBlockX() <= FORD_X1) ford[i] = true;
                else if (m.getY() < y - 0.1) swam[i] = true;
            }
            if (crossedAt[i] < 0 && m.getZ() < RZ0 - 1 && m.onGround()) crossedAt[i] = ticks;
            if (outAt[i] < 0 && !m.isInWater() && m.onGround()) outAt[i] = ticks;
            all &= layout() == Layout.IN_WATER ? outAt[i] >= 0 : crossedAt[i] >= 0;
            // standing wanting to go: no block of way since it last made one
            double dx = m.getX() - stillX[i], dz = m.getZ() - stillZ[i];
            if (dx * dx + dz * dz >= 1 || b == null || !b.mover.wants()) {
                stillX[i] = m.getX();
                stillZ[i] = m.getZ();
                still[i] = 0;
            } else {
                longestStill[i] = Math.max(longestStill[i], ++still[i]);
                if (still[i] == 100 || still[i] == 200) {
                    var path = m.getNavigation().getPath();
                    var next = path == null || path.isDone() ? null : path.getNextNodePos();
                    var mc = m.getMoveControl();
                    lines.add(String.format(Locale.ROOT, "     water, %s, %d stuck at tick %d: at %.2f %.2f %.2f, water %s ground %s bumps %s swims %s, moving %.3f %.3f %.3f;"
                            + " input %.3f %.3f speed %.3f, wants %s to %.2f %.2f %.2f at %.2f, yaw %.0f; legs %s; next %s",
                            layout().text, i, ticks, m.getX(), m.getY(), m.getZ(), m.isInWater(), m.onGround(), m.horizontalCollision, AiWater.swims(m),
                            m.getDeltaMovement().x, m.getDeltaMovement().y, m.getDeltaMovement().z, m.zza, m.xxa, m.getSpeed(), mc.hasWanted(),
                            mc.getWantedX(), mc.getWantedY(), mc.getWantedZ(), mc.getSpeedModifier(), m.getYRot(), b.mover.state(), next == null ? "-" : next.toShortString()));
                }
            }
        }
        int run = layout() == Layout.IN_WATER ? OUT_RUN : RUN;
        if (ticks >= run || all && ticks >= 40) {
            report();
            for (Mob m : squad) m.discard();
            foe.discard();
            squad.clear();
            foe = null;
            if (round + 1 >= Layout.values().length) return finishAll();
            start(round + 1);
        }
        return false;
    }

    private void report() {
        Layout l = layout();
        int crossed = 0, byBridge = 0, byFord = 0, swum = 0, drown = 0, stuck = 0, shots = 0, throwsIn = 0, unders = 0, lastIn = 0, air = 300, swimT = 0;
        StringBuilder each = new StringBuilder();
        for (int i = 0; i < squad.size(); i++) {
            boolean in = l == Layout.IN_WATER ? outAt[i] >= 0 : crossedAt[i] >= 0;
            if (in) crossed++;
            if (bridge[i]) byBridge++;
            if (ford[i]) byFord++;
            if (swam[i]) swum++;
            drown += drowned[i];
            if (longestStill[i] >= 100) stuck++;
            shots += swimShots[i];
            throwsIn += swimThrows[i];
            unders += under[i];
            swimT += swimTicks[i];
            air = Math.min(air, minAir[i]);
            int at = l == Layout.IN_WATER ? outAt[i] : crossedAt[i];
            lastIn = Math.max(lastIn, at < 0 ? ticks : at);
            AiBot b = AiBots.get(squad.get(i));
            Mob m = squad.get(i);
            each.append(String.format(Locale.ROOT, " [%d: %s%s%s%s at %s, under %d, swim %d, still %d, %s, ends %.1f %.1f %.1f]", i, in ? "in" : "OUT", bridge[i] ? " bridge" : "",
                    ford[i] ? " ford" : "", swam[i] ? " swam" : "", at < 0 ? "-" : Integer.toString(at), under[i], swimTicks[i], longestStill[i],
                    b != null && b.brain instanceof AiSoldier s ? s.mode() + "/" + s.why : "-", m.getX(), m.getY(), m.getZ()));
        }
        String what = l == Layout.IN_WATER ? "out of the water" : "across";
        lines.add(String.format(Locale.ROOT, "     water, %s: %s %d of %d by %d ticks; over the bridge %d, the ford %d, swimming %d; eyes under %d ticks, least air %d,"
                + " drowning hurts %d; stood 5 s wanting to go %d; swimming %d ticks, shots then %d, throws %d;%s", l.text, what, crossed, squad.size(), lastIn,
                byBridge, byFord, swum, unders, air, drown, stuck, swimT, shots, throwsIn, each));
        totals.add(new int[] {crossed, byBridge, byFord, swum, drown, stuck, shots, throwsIn, lastIn, squad.size()});
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    private void build(Layout l) {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState(), water = Blocks.WATER.defaultBlockState(),
                planks = Blocks.OAK_PLANKS.defaultBlockState();
        boolean hasFord = l == Layout.FORD_AND_BRIDGE, hasBridge = l == Layout.FORD_AND_BRIDGE || l == Layout.BRIDGE;
        for (int x = X0; x < X0 + SX; x++)
            for (int z = Z0; z < Z0 + SZ; z++) {
                // the river's ends are walled: its water stays in
                boolean river = z >= RZ0 && z <= RZ1 && x > X0 && x < X0 + SX - 1;
                boolean f = river && hasFord && x >= FORD_X0 && x <= FORD_X1, b = river && hasBridge && x >= BRIDGE_X0 && x <= BRIDGE_X1;
                set(x, y - DEPTH - 1, z, stone);
                for (int d = DEPTH; d >= 1; d--) set(x, y - d, z, !river ? stone : d == 1 ? b ? planks : water : f ? stone : water);
                // the river's ends walled three high over the banks too: no way round them
                boolean end = (x == X0 || x == X0 + SX - 1) && z >= RZ0 - 1 && z <= RZ1 + 1;
                for (int h = 0; h < 5; h++) set(x, y + h, z, end && h < 3 ? stone : air);
            }
        // the hut: stone walls three high round a 3 by 3 floor, a roof, a door two high in the north wall
        if (l != Layout.IN_WATER)
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    for (int h = 0; h <= 3; h++) {
                        boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2 || h == 3;
                        boolean door = dz == -2 && dx == 0 && h < 2;
                        set(HUT_X + dx, y + h, HUT_Z + dz, wall && !door ? stone : air);
                    }
        TacticalMap.of(level).want(X0 + SX / 2, y, Z0 + SZ / 2, SX / 2 + 4, 6);
    }

    private Mob spawn(double x, double yy, double z, String team, float yaw) {
        List<Mob> made = AiSpawn.spawn(level, new Vec3(x, yy, z), AiSpawn.Kind.DUMMY, 1, BuiltInRegistries.ITEM.getValue(RIFLE));
        Mob m = made.get(0);
        m.setPos(x, yy, z);
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
        ticks = 0;
        Layout l = layout();
        AiSquads.on = false;
        AiSquads.offTeams.clear();
        AiSquads.forget(level);
        build(l);
        int n = l == Layout.IN_WATER ? 1 : SQUAD;
        crossedAt = new int[n];
        outAt = new int[n];
        java.util.Arrays.fill(crossedAt, -1);
        java.util.Arrays.fill(outAt, -1);
        under = new int[n];
        minAir = new int[n];
        java.util.Arrays.fill(minAir, Integer.MAX_VALUE);
        drowned = new int[n];
        still = new int[n];
        longestStill = new int[n];
        swimShots = new int[n];
        swimThrows = new int[n];
        swimTicks = new int[n];
        shotsBefore = new int[n];
        thrownBefore = new int[n];
        bridge = new boolean[n];
        ford = new boolean[n];
        swam = new boolean[n];
        stillX = new double[n];
        stillZ = new double[n];
        double fz = l == Layout.IN_WATER ? RZ0 - 6.5 : HUT_Z + 0.5;
        foe = spawn(HUT_X + 0.5, y, fz, "water_foe", 0F);
        var speed = foe.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0);
        for (int i = 0; i < n; i++) {
            Mob m = l == Layout.IN_WATER ? spawn(XC + 0.5, y - 1, RZ0 + 6.5, "water_side", 180F) : spawn(START_X[i], y, START_Z, "water_side", 180F);
            squad.add(m);
            stillX[i] = m.getX();
            stillZ[i] = m.getZ();
        }
        AiBots.refreshTargets(level);
        long now = level.getGameTime();
        for (int i = 0; i < n; i++) {
            AiBot b = AiBots.get(squad.get(i));
            if (b == null) continue;
            b.roleFor = null;
            b.memory.seen(foe.getId(), foe.getX(), y, foe.getZ(), now, 0);
            shotsBefore[i] = b.gunner.shots;
            if (b.brain instanceof AiSoldier s) thrownBefore[i] = s.thrown + s.smoked;
        }
    }

    /** lava and every liquid of NTM's (its fluids' blocks, its finite ones) are walls to walk; water is not */
    private void liquids() {
        List<String> walls = new ArrayList<>(), not = new ArrayList<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            BlockState s = b.defaultBlockState();
            var fluid = s.getFluidState();
            boolean liquid = !fluid.isEmpty() && !fluid.is(FluidTags.WATER) || BlockClasses.finiteFluid(b.getClass());
            if (!liquid || !(b == Blocks.LAVA || BuiltInRegistries.BLOCK.getKey(b).getNamespace().equals("hbm"))) continue;
            String name = BuiltInRegistries.BLOCK.getKey(b).toString();
            (Voxel.move(BlockClasses.of(s)) == Voxel.TALL ? walls : not).add(name);
        }
        int water = BlockClasses.of(Blocks.WATER.defaultBlockState());
        check.out("     water, liquids that are walls: " + walls + (not.isEmpty() ? "" : "; NOT: " + not));
        check.check("water: lava and NTM's liquids are walls to walk (" + walls.size() + "), water is not", not.isEmpty() && walls.size() > 1
                && Voxel.open(water) && (water & Voxel.WATER) != 0);
    }

    private boolean finishAll() {
        counting = null;
        AiSquads.on = true;
        for (String s : lines) check.out(s);
        liquids();
        int[] fb = totals.get(Layout.FORD_AND_BRIDGE.ordinal()), b = totals.get(Layout.BRIDGE.ordinal()), none = totals.get(Layout.NONE.ordinal()),
                in = totals.get(Layout.IN_WATER.ordinal());
        int drown = 0, stuck = 0;
        for (int[] t : totals) {
            drown += t[4];
            stuck += t[5];
        }
        check.check("water: with a ford 15 off and a bridge 30 off the squad all get across, none swims (" + fb[0] + " of " + fb[9] + ", swam " + fb[3] + ")",
                fb[0] == fb[9] && fb[3] == 0);
        check.check("water: with a bridge 30 off the squad all get across over it, none swims (" + b[0] + " of " + b[9] + ", swam " + b[3] + ")",
                b[0] == b[9] && b[3] == 0);
        check.check("water: with neither the squad all swim across (" + none[0] + " of " + none[9] + ")", none[0] == none[9]);
        check.check("water: none drowns, none stands 5 s wanting to go (drowning hurts " + drown + ", stood " + stuck + ")", drown == 0 && stuck == 0);
        check.check("water: swimming under fire it neither fires nor throws, and gets out within 15 s (shots " + in[6] + ", throws " + in[7] + ", out "
                + in[0] + " by " + in[8] + " ticks)", in[6] == 0 && in[7] == 0 && in[0] == in[9] && in[8] <= 300);
        return true;
    }
}
