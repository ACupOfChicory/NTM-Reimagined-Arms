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
 * The AI check's trench (AiTrenches, stage 6 T6): a zigzag two deep dug into level ground, five stretches and four
 * turns, two of one team holding its far stretches, four of another on the ground before it who know where they are.
 * Rounds by twos, aiTrenches off (before: the trench is no building, they go after the two as on open ground) and on
 * (stretch after stretch, as a building's rooms): one wide, those holding it not walking; the same, walking as they
 * will; two wide with a fire step, not walking; one holding it alone with a grenade set down by it; last, one wide,
 * still, aiTrenches on, each entry's first thrower downed by the stand as it steps out (the grenade handed on). Counted: who is left, when the trench was cleared,
 * the squad's ticks on the ground at the trench's edge (seen from in it) and in it, entries begun, grenades thrown
 * round a turn, those in the trench down by a blast, the squad hurt by its own side's blast; for those walking, of the grenades
 * that went off near one of them, how often it got away first.
 */
final class AiCheckTrench {
    static final int X0 = 3600, Z0 = 0, SIZE = 48, RUN = 1200, ROUNDS = 44, STILL = 12, WIDE = 24, DODGE = 32, DODGE_WIDE = 36, HANDOFF = 40, SQUAD = 4, HELD = 2;
    /** the trench, cell by cell: stretches and the turns between them; the same two wide (each cell with those east, south and south-east of it) */
    static final int[][] NARROW = trench(), BROAD = broad(NARROW);
    /** the fire step two wide is dug for: along the fifth stretch's south side, one up from its floor */
    static final int STEP_Z = 14, STEP_X0 = 3626, STEP_X1 = 3630;
    /** those holding it: in the fourth stretch and at the end of the fifth {x, up, z}; two wide, by the fire step, not on it (there the squad sees one at once) */
    static final double[][] HOLDERS = {{3623.5, 0, 15.5}, {3629.5, 0, 12.5}}, HOLDERS_WIDE = {{3623.5, 0, 15.5}, {3630.5, 0, 12.5}};
    /** a grenade near one holding it: this near where it went off when it was first seen in the air; one gone away from it: this much further at the burst */
    static final double NEAR_THROW = 6, AWAY = 1.5;
    private int[][] cells = NARROW;
    /** the grenade set down by the one holding it alone; when; its burst's: {rounds, unhurt, behind a block from it, hurt, down}, how far it got in all */
    private com.hbm.entity.grenade.EntityGrenadeUniversal set;
    private Vec3 setAt, heldEnd;
    private final int[][] alone = new int[2][5];
    private final double[] aloneGain = new double[2];
    private float aloneHealth;
    private int goneAt = -1;
    static final double[] START_X = {3606.5, 3611.5, 3616.5, 3621.5};
    static final double START_Z = 40.5;
    /** at the edge: on the ground this near a trench's cell, blocks */
    static final double EDGE = 2.5;
    static final Identifier GUN = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    /** AiEntry's count of those in before their own grenade burst, and its grenades thrown, at the stand's start */
    private int early0, earlyFrags0, off0;
    private String who(net.minecraft.world.entity.Entity e, Mob hurt) {
        if (e == null) return "-";
        if (e == hurt) return "itself";
        int k = squad.indexOf(e);
        if (k >= 0) return "the squad's " + k + (e.isAlive() ? "" : " (down)");
        k = held.indexOf(e);
        if (k >= 0) return "one holding it " + k + (e.isAlive() ? "" : " (down)");
        return e.getType().toShortString() + " " + e.getId();
    }

    private Vec3 lastBlast;
    private int lastBlastTick = -1;
    private final java.util.Set<Integer> seenOther = new java.util.HashSet<>();
    private int round = -1, ticks, firstDown, edge, inside, entriesBefore, fragsBefore, ownBlast, blastKills;
    private int roomsRead = -1, turnsRead = -1, wideRooms = -1, wideTurns = -1, stepRoom = -2, stepStretch = -1;
    /** those holding it, where each was when a grenade was first seen in the air, by the grenade */
    private final java.util.Map<Integer, Vec3[]> heldAt = new java.util.HashMap<>();
    /** grenades gone off near one holding it, moving: {near, gone away from it, down by it}; by arm */
    private final int[][] dodge = new int[6][3];
    private double[] dodgeGain = new double[6];
    private final float[] health = new float[SQUAD], heldHealth = new float[HELD];
    /** the grenades in the air and where each was last; those gone off, in the trench, how near the nearest holding it in all */
    private final java.util.Map<Integer, Vec3> flying = new java.util.HashMap<>();
    private int bursts, burstsDown, heldBlast, nearN, heldOut;
    private double nearSum;
    private final List<Mob> squad = new ArrayList<>(), held = new ArrayList<>();
    private final java.util.Set<Integer> noted = new java.util.HashSet<>();
    /** per round: {on, held left, squad left, ticks, first down at, ticks at the edge, ticks in it, entries, grenades in, blast kills, own hurt by a blast} */
    private final List<int[]> results = new ArrayList<>();
    /** the rounds whose first thrower the stand downs: {downed, handed on, grenades in, the squad hurt by its own blast, downed with none else armed, thrown by one handed it, throwers lost with what came of it told (one downed as the round ends has none)} */
    private final List<int[]> handoff = new ArrayList<>();
    private int downed, handedBefore;

    AiCheckTrench(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    private static int[][] broad(int[][] narrow) {
        java.util.LinkedHashSet<Long> c = new java.util.LinkedHashSet<>();
        for (int[] n : narrow)
            for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) c.add(BlockPos.asLong(n[0] + dx, 0, n[1] + dz));
        List<int[]> out = new ArrayList<>();
        for (long l : c) out.add(new int[] {BlockPos.getX(l), BlockPos.getZ(l)});
        return out.toArray(new int[0][]);
    }

    /** x 3608..3614 at z 12, a turn, z 13..18 at x 3615, a turn, x 3616..3622 at z 19, a turn, z 18..13 at x 3623, a turn, x 3624..3630 at z 12 */
    private static int[][] trench() {
        List<int[]> c = new ArrayList<>();
        for (int x = 3608; x <= 3615; x++) c.add(new int[] {x, 12});
        for (int z = 13; z <= 19; z++) c.add(new int[] {3615, z});
        for (int x = 3616; x <= 3623; x++) c.add(new int[] {x, 19});
        for (int z = 18; z >= 12; z--) c.add(new int[] {3623, z});
        for (int x = 3624; x <= 3630; x++) c.add(new int[] {x, 12});
        return c.toArray(new int[0][]);
    }

    boolean tick() {
        if (!started) {
            started = true;
            early0 = AiEntry.inBeforeBlast;
            off0 = AiEntry.offBeforeBlast;
            earlyFrags0 = AiEntry.frags;
            for (int cx = X0 >> 4; cx <= (X0 + SIZE) >> 4; cx++)
                for (int cz = Z0 >> 4; cz <= (Z0 + SIZE) >> 4; cz++) level.setChunkForced(cx, cz, true);
            AiBots.awake = true;
            AiBots.seenDown = AiBots.toldDown = 0;
            start(0);
            return false;
        }
        ticks++;
        // the trench as the AI reads it, once its cells are there
        if (arm(round) == 6) return alone();
        boolean wide = cells == BROAD;
        if ((wide ? wideRooms : roomsRead) < 0 && ticks >= 20) {
            AiRooms.Plan p = AiTrenches.of(TacticalMap.of(level), BlockPos.asLong(cells[3][0], y, cells[3][1]));
            if (p != null) {
                int turns = 0;
                for (AiRooms.Way w : p.ways) if (w.turn >= 0) turns++;
                if (wide) {
                    wideRooms = p.rooms();
                    wideTurns = turns;
                    stepRoom = p.room(BlockPos.asLong(STEP_X0 + 2, y + 1, STEP_Z));
                    stepStretch = p.room(BlockPos.asLong(STEP_X0 + 2, y, STEP_Z - 1));
                } else {
                    roomsRead = p.rooms();
                    turnsRead = turns;
                }
            }
        }
        // the grenades in the air: where each was last before it went off, and how near the nearest of the two holding it
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(X0, y - 2, Z0, X0 + SIZE, y + 40, Z0 + SIZE);
        java.util.Set<Integer> now = new java.util.HashSet<>();
        for (var g : level.getEntitiesOfClass(com.hbm.entity.grenade.EntityGrenadeUniversal.class, box)) {
            // the squad's, told by its thrower when first seen (a thrower down later leaves it with none)
            if (!flying.containsKey(g.getId()) && !(g.getOwner() instanceof Mob o && squad.contains(o))) {
                if (seenOther.add(g.getId())) check.out(String.format(Locale.ROOT, "     trench, round %d, tick %d: a grenade not the squad's at %.1f %.1f %.1f, thrown by %s",
                        round + 1, ticks, g.getX(), g.getY() - y, g.getZ(), g.getOwner() instanceof Mob o2 ? who(o2, null) : "?"));
                continue;
            }
            now.add(g.getId());
            if (flying.put(g.getId(), g.position()) == null) {
                Vec3[] at = new Vec3[held.size()];
                for (int i = 0; i < held.size(); i++) at[i] = held.get(i).isAlive() ? held.get(i).position() : null;
                heldAt.put(g.getId(), at);
            }
        }
        for (var it = flying.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (now.contains(e.getKey())) continue;
            it.remove();
            Vec3 b = e.getValue();
            double near = Double.MAX_VALUE;
            // those down this tick as well: the blast may be what downed them
            for (Mob m : held) if (m.isAlive() || m.deathTime <= 1) near = Math.min(near, m.position().distanceTo(b));
            bursts++;
            lastBlast = b;
            lastBlastTick = ticks;
            // one holding it, moving, near where it went off when it was thrown: gone away from it, or down by it
            Vec3[] then = heldAt.remove(e.getKey());
            int a = arm(round);
            if (then != null && (a == 2 || a == 3))
                for (int i = 0; i < then.length && i < held.size(); i++) {
                    if (then[i] == null || then[i].distanceTo(b) > NEAR_THROW) continue;
                    Mob m = held.get(i);
                    dodge[a][0]++;
                    if (!m.isAlive()) {
                        // down by it, or by a bullet before it (then not counted)
                        if (m.deathTime <= 1) dodge[a][2]++;
                        else dodge[a][0]--;
                        continue;
                    }
                    double gain = m.position().distanceTo(b) - then[i].distanceTo(b);
                    dodgeGain[a] += gain;
                    if (gain >= AWAY) dodge[a][1]++;
                }
            boolean down = b.y < y + 1.5 && dug(b.x, b.z);
            if (down) burstsDown++;
            if (near < Double.MAX_VALUE) {
                nearSum += near;
                nearN++;
            }
            check.out(String.format(Locale.ROOT, "     trench, round %d, tick %d: a grenade went off at %.1f %.1f %.1f, %s; the nearest holding it %.1f off",
                    round + 1, ticks, b.x, b.y - y, b.z, down ? "in the trench" : "out of it", near));
        }
        int left = 0, foes = 0;
        for (int i = 0; i < squad.size(); i++) {
            Mob m = squad.get(i);
            float h = m.isAlive() ? m.getHealth() : 0;
            if (h < health[i]) {
                var by = m.getLastDamageSource();
                if (by == null || by.getDirectEntity() == null || !by.getDirectEntity().getType().toShortString().contains("bullet")) {
                    // its own side's blast only (a grenade of those holding it is theirs, not counted)
                    if (by != null && by.getEntity() instanceof Mob o && squad.contains(o)) ownBlast++;
                    String mode = AiBots.get(m) instanceof AiBot ob && ob.brain instanceof AiSoldier os ? os.mode() + "/" + os.why : "-";
                    check.out(String.format(Locale.ROOT, "     trench, round %d, tick %d: one of the squad hurt %.1f by %s (%s, from %s) at %.1f %.1f %.1f, %s, the last blast %.1f off at tick %d",
                            round + 1, ticks, health[i] - h, by == null ? "?" : by.type().msgId(), by == null || by.getDirectEntity() == null ? "-" : by.getDirectEntity().getType().toShortString(),
                            who(by == null ? null : by.getEntity(), m),
                            m.getX(), m.getY() - y, m.getZ(), mode, lastBlast == null ? -1 : lastBlast.distanceTo(m.position()), lastBlastTick));
                }
            }
            health[i] = h;
            if (!m.isAlive()) continue;
            left++;
            boolean in = m.getY() < y + 1;
            if (in) inside++;
            else if (near(m.getX(), m.getZ())) edge++;
        }
        for (int i = 0; i < held.size(); i++) {
            Mob m = held.get(i);
            float h = m.isAlive() ? m.getHealth() : 0;
            if (h < heldHealth[i]) {
                var by = m.getLastDamageSource();
                if (by == null || by.getDirectEntity() == null || !by.getDirectEntity().getType().toShortString().contains("bullet")) heldBlast++;
            }
            heldHealth[i] = h;
            if (m.isAlive() && m.getY() >= y + 1.5) heldOut++;
            if (m.isAlive()) foes++;
            else if (noted.add(m.getId())) {
                if (firstDown < 0) firstDown = ticks;
                var src = m.getLastDamageSource();
                if (src == null || src.getDirectEntity() == null || !src.getDirectEntity().getType().toShortString().contains("bullet")) blastKills++;
            }
        }
        if (ticks < RUN && left > 0 && foes > 0) return false;
        StringBuilder where = new StringBuilder();
        for (Mob m : squad)
            if (m.isAlive() && AiBots.get(m) instanceof AiBot b && b.brain instanceof AiSoldier so)
            {
                var f = b.memory.contact(b.memory.focusId);
                net.minecraft.world.entity.Entity fe = level.getEntity(b.memory.focusId);
                where.append(String.format(Locale.ROOT, " %.1f %.1f %.1f %s %s focus %s %s lost at %s;", m.getX(), m.getY() - y, m.getZ(), so.mode(), b.mover.idle() ? "idle" : b.mover.arrived() ? "arrived" : b.mover.failed() ? "failed" : "moving",
                        b.memory.focusId, fe == null ? "gone" : fe.isAlive() ? "alive" : "dead", f == null ? "-" : String.format(Locale.ROOT, "%.1f %.1f", f.lostX, f.lostZ)));
            }
        for (Mob m : held) if (m.isAlive()) where.append(String.format(Locale.ROOT, " held at %.1f %.1f;", m.getX(), m.getZ()));
        check.out(String.format(Locale.ROOT, "     trench, round %d, at the end:%s", round + 1, where));
        check.out(String.format(Locale.ROOT, "     trench, round %d, its entries' grenades: %s", round + 1, AiEntry.fates.isEmpty() ? "none" : String.join("; ", AiEntry.fates)));
        List<String> fates = new ArrayList<>(AiEntry.fates);
        AiEntry.fates.clear();
        results.add(new int[] {arm(round), foes, left, ticks, firstDown, edge, inside, AiEntry.entries - entriesBefore, AiEntry.frags - fragsBefore, blastKills, ownBlast, heldBlast, heldOut});
        if (arm(round) == 7) {
            // of the throwers downed, those with none else of the entry left with a grenade; throws by one it was handed to
            int none = 0, after = 0, lost = 0;
            boolean handed = false;
            for (String f : fates) {
                if (f.startsWith("thrower ")) lost++;
                if (f.contains("none else with a grenade")) none++;
                if (f.endsWith("handed on")) handed = true;
                else if (f.equals("thrown") && handed) {
                    after++;
                    handed = false;
                }
            }
            handoff.add(new int[] {downed, AiEntry.handedOn - handedBefore, AiEntry.frags - fragsBefore, ownBlast, none, after, lost});
        }
        for (Mob m : squad) m.discard();
        for (Mob m : held) m.discard();
        squad.clear();
        held.clear();
        if (round + 1 >= ROUNDS) return finish();
        start(round + 1);
        return false;
    }

    /**
     * One holding it alone, walking as it will, in the fourth stretch's middle; a grenade (no one's) set down a block
     * and a half from it towards one of the stretch's turns: it gets away round the other turn, or does not.
     */
    private boolean alone() {
        Mob m = held.get(0);
        if (ticks == 20) {
            boolean north = (round - DODGE) % 2 == 0;
            set = new com.hbm.entity.grenade.EntityGrenadeUniversal(level, com.hbm.items.weapon.grenade.ItemGrenadeUniversal.make(
                    com.hbm.items.weapon.grenade.ItemGrenadeShell.EnumGrenadeShell.FRAG, com.hbm.items.weapon.grenade.ItemGrenadeFilling.EnumGrenadeFilling.HE,
                    com.hbm.items.weapon.grenade.ItemGrenadeFuze.EnumGrenadeFuze.S3));
            set.setPos(m.getX(), y + 0.1, m.getZ() + (north ? -1.5 : 1.5));
            set.setDeltaMovement(Vec3.ZERO);
            level.addFreshEntity(set);
            setAt = set.position();
            aloneHealth = m.getHealth();
            heldEnd = m.position();
        }
        if (set != null && !set.isRemoved()) {
            setAt = set.position();
            if (m.isAlive()) heldEnd = m.position();
        }
        // a few ticks after it went off, for the blast to land
        if (set != null && set.isRemoved() && goneAt < 0) goneAt = ticks;
        if ((goneAt < 0 || ticks < goneAt + 5) && ticks < RUN) return false;
        goneAt = -1;
        int[] al = alone[cells == BROAD ? 1 : 0];
        al[0]++;
        float h = m.isAlive() ? m.getHealth() : 0;
        boolean behind = AiGrenades.cover(AiBots.voxels(level), setAt, heldEnd.x, heldEnd.y, heldEnd.z, false);
        double off = heldEnd.distanceTo(setAt);
        if (h >= aloneHealth) al[1]++;
        if (behind) al[2]++;
        if (h < aloneHealth) al[3]++;
        if (!m.isAlive()) al[4]++;
        aloneGain[cells == BROAD ? 1 : 0] += off;
        check.out(String.format(Locale.ROOT, "     trench, alone, round %d (%s): the grenade at %.1f %.1f, it at %.1f %.1f at the burst, %.1f off, %s; health %.0f of %.0f",
                round + 1, cells == BROAD ? "two wide" : "one wide", setAt.x, setAt.z, heldEnd.x, heldEnd.z, off, behind ? "behind a block from it" : "in its sight", h, aloneHealth));
        results.add(new int[] {6, m.isAlive() ? 1 : 0, 0, ticks, -1, 0, 0, 0, 0, 0, 0, 0, 0});
        m.discard();
        held.clear();
        set = null;
        if (round + 1 >= ROUNDS) return finish();
        start(round + 1);
        return false;
    }

    /** over one of the trench's cells */
    private boolean dug(double x, double z) {
        for (int[] c : cells) if (Math.floor(x) == c[0] && Math.floor(z) == c[1]) return true;
        return false;
    }

    /** on the ground at the trench's edge */
    private boolean near(double x, double z) {
        for (int[] c : cells) {
            double dx = x - (c[0] + 0.5), dz = z - (c[1] + 0.5);
            if (dx * dx + dz * dz < EDGE * EDGE) return true;
        }
        return false;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void build() {
        BlockState stone = Blocks.STONE.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(), grass = Blocks.GRASS_BLOCK.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                set(x, y - 1, z, stone);
                set(x, y, z, dirt);
                set(x, y + 1, z, grass);
                for (int h = 2; h < 6; h++) set(x, y + h, z, air);
            }
        for (int[] c : cells) {
            set(c[0], y, c[1], air);
            set(c[0], y + 1, c[1], air);
        }
        if (cells == BROAD) for (int x = STEP_X0; x <= STEP_X1; x++) set(x, y + 1, STEP_Z, air);
        TacticalMap.of(level).want(X0 + SIZE / 2, y, Z0 + SIZE / 2, SIZE / 2 + 4, 6);
    }

    private Mob spawn(double x, double yy, double z, String team, float yaw) {
        List<Mob> made = AiSpawn.spawn(level, new Vec3(x, yy, z), AiSpawn.Kind.DUMMY, 1, BuiltInRegistries.ITEM.getValue(GUN));
        Mob m = made.get(0);
        m.setPos(x, yy, z);
        m.setYRot(yaw);
        m.setYHeadRot(yaw);
        m.setYBodyRot(yaw);
        AiTeams.join(level, m, team);
        AiBot bot = AiBots.get(m);
        if (bot != null) bot.gunner.ammo.setInfinite();
        // those in the trench hold it: in the first rounds they turn, crouch and fire where they stand; after, they move as they will
        if (team.equals("trench_in") && (round < STILL || round >= WIDE && round < DODGE || round >= HANDOFF)) {
            var speed = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(0);
        }
        return m;
    }

    private void start(int r) {
        round = r;
        ticks = 0;
        firstDown = -1;
        edge = inside = heldOut = 0;
        AiTrenches.on = r % 2 == 1 || r >= HANDOFF;
        cells = r >= DODGE_WIDE && r < HANDOFF || r >= WIDE && r < DODGE ? BROAD : NARROW;
        // the last rounds: each entry's first thrower down as it steps out (none of the squad's doing)
        downed = 0;
        handedBefore = AiEntry.handedOn;
        AiEntry.firstThrower = r >= HANDOFF ? b -> {
            if (squad.contains(b.mob) && b.mob.isAlive()) {
                downed++;
                check.out(String.format(Locale.ROOT, "     trench, round %d, tick %d: the entry's first thrower downed by the stand at %.1f %.1f %.1f", round + 1, ticks, b.mob.getX(), b.mob.getY() - y, b.mob.getZ()));
                b.mob.kill(level);
            }
        } : null;
        heldAt.clear();
        AiEntry.on = AiEntry.frag = true;
        AiSquads.on = true;
        AiSquads.offTeams.clear();
        AiSquads.forget(level);
        AiEntry.forget(level);
        AiClearing.forget(level);
        build();
        if (arm(r) == 6) {
            held.add(spawn(HOLDERS[0][0], y, HOLDERS[0][2], "trench_in", 180F));
            set = null;
            return;
        }
        for (double[] p : r >= WIDE && r < HANDOFF ? HOLDERS_WIDE : HOLDERS) held.add(spawn(p[0], y + p[1], p[2], "trench_in", 180F));
        for (int i = 0; i < SQUAD; i++) squad.add(spawn(START_X[i], y + 2, START_Z, "trench_out", 180F));
        AiBots.refreshTargets(level);
        entriesBefore = AiEntry.entries;
        fragsBefore = AiEntry.frags;
        AiEntry.fates.clear();
        ownBlast = blastKills = heldBlast = 0;
        lastBlast = null;
        lastBlastTick = -1;
        flying.clear();
        for (int i = 0; i < HELD; i++) heldHealth[i] = held.get(i).getHealth();
        for (int i = 0; i < SQUAD; i++) health[i] = squad.get(i).getHealth();
        long now = level.getGameTime();
        for (Mob m : squad) {
            AiBot b = AiBots.get(m);
            if (b == null) continue;
            for (Mob f : held) {
                b.memory.seen(f.getId(), f.getX(), f.getY(), f.getZ(), now, 0);
                AiBot fb = AiBots.get(f);
                if (fb != null) fb.memory.seen(m.getId(), m.getX(), m.getY(), m.getZ(), now, 0);
            }
        }
    }

    /** the round's arm: aiTrenches off and on, those holding it still; the same, moving; the same, two wide with a fire step, still */
    private static int arm(int r) {
        return r >= HANDOFF ? 7 : r >= DODGE ? 6 : r % 2 + (r >= WIDE ? 4 : r >= STILL ? 2 : 0);
    }

    private boolean finish() {
        AiTrenches.on = true;
        AiEntry.firstThrower = null;
        String[] names = {"aiTrenches off (before)", "aiTrenches on", "aiTrenches off, those holding it moving", "aiTrenches on, those holding it moving",
                "two wide with a fire step, aiTrenches off", "two wide with a fire step, aiTrenches on", "one holding it alone, a grenade by it",
                "aiTrenches on, the first thrower downed as it steps out"};
        double[][] sum = new double[8][13];
        int[] n = new int[8], cleared = new int[8], firsts = new int[8];
        double[] clearTicks = new double[8], firstTicks = new double[8];
        for (int i = 0; i < results.size(); i++) {
            int[] r = results.get(i);
            n[r[0]]++;
            for (int k = 1; k < r.length; k++) sum[r[0]][k] += r[k];
            if (r[1] == 0) {
                cleared[r[0]]++;
                clearTicks[r[0]] += r[3];
            }
            if (r[4] >= 0) {
                firsts[r[0]]++;
                firstTicks[r[0]] += r[4];
            }
            if (r[0] == 6) continue;
            check.out(String.format(Locale.ROOT, "     trench, round %d, %s: in the trench %d of %d left, of the squad %d of %d, %d ticks, the first in it down %s;"
                    + " the squad's ticks at its edge %d, in it %d; entries %d, grenades in round a turn %d; in the trench hurt by a blast %d, down by one %d; the squad hurt by its own blast %d; those holding it out of it %d ticks",
                    i + 1, names[r[0]], r[1], HELD, r[2], SQUAD, r[3], r[4] < 0 ? "never" : "at tick " + r[4], r[5], r[6], r[7], r[8], r[11], r[9], r[10], r[12]));
        }
        for (int on = 0; on < 6; on++)
            check.out(String.format(Locale.ROOT, "     trench, %s, %d rounds: cleared in %d (%.0f ticks on average), the first in it down in %d (%.0f ticks); left in it %.0f of %d,"
                    + " of the squad %.0f of %d; the squad's ticks at its edge %.0f, in it %.0f; entries %.0f, grenades in %.0f; in it hurt by a blast %.0f, down by one %.0f; the squad hurt by its own blast %.0f; those holding it out of it %.0f ticks",
                    names[on], n[on], cleared[on], cleared[on] == 0 ? 0 : clearTicks[on] / cleared[on], firsts[on], firsts[on] == 0 ? 0 : firstTicks[on] / firsts[on],
                    sum[on][1], n[on] * HELD, sum[on][2], n[on] * SQUAD, sum[on][5], sum[on][6], sum[on][7], sum[on][8], sum[on][11], sum[on][9], sum[on][10], sum[on][12]));
        check.out(String.format(Locale.ROOT, "     trench, the squad's grenades: %d went off, %d of them in the trench; the nearest holding it %.1f off on average;"
                + " foes seen to fall %d, squad mates told %d", bursts, burstsDown, nearN == 0 ? 0 : nearSum / nearN, AiBots.seenDown, AiBots.toldDown));
        check.out(String.format(Locale.ROOT, "     trench, throws not found: none lands in the room %d, one of the entry not behind the wall %d, another of its side in reach %d, a player %d; stepped out and threw none %d;"
                + " gone in before all were beside the way in %d, all there and no throw found %d", AiEntry.why[0], AiEntry.why[1], AiEntry.why[2], AiEntry.why[3], AiEntry.noThrow,
                AiEntry.unstacked, AiEntry.noPlan));
        for (int a = 2; a <= 3; a++)
            check.out(String.format(Locale.ROOT, "     trench, %s: grenades gone off within %.0f of one holding it when thrown %d; gone %.1f further away or more %d, down by it %d, gone further %.1f on average",
                    names[a], NEAR_THROW, dodge[a][0], AWAY, dodge[a][1], dodge[a][2], dodge[a][0] - dodge[a][2] == 0 ? 0 : dodgeGain[a] / (dodge[a][0] - dodge[a][2])));
        for (int w = 0; w < 2; w++)
            check.out(String.format(Locale.ROOT, "     trench, %s, one holding it alone, a grenade set down a block and a half off, %d rounds: unhurt %d, behind a block from it at the burst %d, hurt %d, down %d; %.1f off it on average",
                    w == 0 ? "one wide" : "two wide", alone[w][0], alone[w][1], alone[w][2], alone[w][3], alone[w][4], alone[w][0] == 0 ? 0 : aloneGain[w] / alone[w][0]));
        check.check("trench: all rounds ran", results.size() == ROUNDS);
        int dn = 0, ho = 0, own = 0, none = 0, after = 0, lost = 0;
        for (int[] h : handoff) {
            dn += h[0];
            ho += h[1];
            own += h[3];
            none += h[4];
            after += h[5];
            lost += h[6];
        }
        // (one handed it may be shot in its turn, or the trench cleared before its throw: told, not failed)
        check.check(String.format(Locale.ROOT, "trench: a thrower downed as it steps out hands its grenade on, and it is thrown (downed %d, throwers lost before the round's end %d, none else with a grenade %d, handed on %d, thrown by one handed it %d, in %d rounds)",
                dn, lost, none, ho, after, handoff.size()), dn > 0 && ho >= lost - none && ho > 0 && after > 0);
        check.check(String.format(Locale.ROOT, "trench: a grenade handed on hurts none of the squad (%d)", own), own == 0);
        // (all of it behind a block, or far enough off: either is told above)
        check.check(String.format(Locale.ROOT, "trench: one holding it alone gets away from a grenade by it, unhurt, one wide and two (%d of %d, %d of %d)", alone[0][1], alone[0][0], alone[1][1], alone[1][0]),
                alone[0][0] > 0 && alone[0][1] == alone[0][0] && alone[1][1] == alone[1][0]);
        check.check(String.format(Locale.ROOT, "trench: the zigzag is read as five stretches and four turns (%d, %d)", roomsRead, turnsRead), roomsRead == 5 && turnsRead == 4);
        check.check(String.format(Locale.ROOT, "trench: two wide, read as five stretches and four turns, the fire step its stretch's (%d, %d; %d, %d)", wideRooms, wideTurns, stepRoom, stepStretch),
                wideRooms == 5 && wideTurns == 4 && stepRoom >= 0 && stepRoom == stepStretch);
        check.check(String.format(Locale.ROOT, "trench: with trenches read they go in round a turn (entries %.0f, %.0f before)", sum[1][7], sum[0][7]), sum[1][7] > sum[0][7]);
        check.check(String.format(Locale.ROOT, "trench: a grenade goes in round a turn before them (%.0f)", sum[1][8]), sum[1][8] > 0);
        // the edge's ticks and the rounds cleared are told above: from one run of the stage to the next they go either way
        double firstOn = firsts[1] == 0 ? Double.MAX_VALUE : firstTicks[1] / firsts[1], firstOff = firsts[0] == 0 ? Double.MAX_VALUE : firstTicks[0] / firsts[0];
        check.check(String.format(Locale.ROOT, "trench: with trenches read the first in it is down sooner (at tick %.0f, %.0f before)", firstOn, firstOff),
                firsts[1] == n[1] && firstOn < firstOff);
        check.check(String.format(Locale.ROOT, "trench: a grenade thrown in round a turn hurts none of the squad (%.0f)", sum[1][10]), sum[1][10] == 0);
        check.check(String.format(Locale.ROOT, "trench: with trenches read a grenade round a turn downs one holding it (%.0f in %d rounds)", sum[1][9], n[1]), sum[1][9] > 0);
        // over all the rounds, one not cleared counted at RUN (else one cleared by luck early, the others never, would make
        // "before" look the sooner)
        double moveOn = n[3] == 0 ? RUN : (clearTicks[3] + (n[3] - cleared[3]) * (double) RUN) / n[3],
                moveOff = n[2] == 0 ? RUN : (clearTicks[2] + (n[2] - cleared[2]) * (double) RUN) / n[2];
        check.check(String.format(Locale.ROOT, "trench: with trenches read a trench whose holders move is cleared sooner (at tick %.0f, %.0f before; one not cleared at %d)", moveOn, moveOff, RUN),
                moveOn < moveOff);
        check.out(String.format(Locale.ROOT, "     trench: those of an entry, its grenade out, in the room %d, off their place beside the way in %d; grenades thrown in %d",
                AiEntry.inBeforeBlast - early0, AiEntry.offBeforeBlast - off0, AiEntry.frags - earlyFrags0));
        // a trap (X1212): the code before it kept them so here too; its one fault, 1 trench run in 52, was not seen again
        check.check(String.format(Locale.ROOT, "trench: an entry keeps its places beside the way in till its own grenade has burst (in the room %d, off them %d; %d grenades thrown in, 1 at least)",
                AiEntry.inBeforeBlast - early0, AiEntry.offBeforeBlast - off0, AiEntry.frags - earlyFrags0),
                AiEntry.inBeforeBlast == early0 && AiEntry.offBeforeBlast == off0 && AiEntry.frags > earlyFrags0);
        return true;
    }
}
