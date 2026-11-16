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
 * turns, two of one team holding its far stretches (they do not walk), four of another on the ground before it who
 * know where they are. Rounds by twos, aiTrenches off (before: the trench is no building, they go after the two as on
 * open ground) and on (stretch after stretch, as a building's rooms). Counted: who is left, when the trench was
 * cleared, the squad's ticks on the ground at the trench's edge (seen from in it) and in it, entries begun, grenades
 * thrown round a turn, those in the trench down by a blast, the squad hurt by a blast.
 */
final class AiCheckTrench {
    static final int X0 = 3600, Z0 = 0, SIZE = 48, RUN = 1200, ROUNDS = 24, STILL = 12, SQUAD = 4, HELD = 2;
    /** the trench, cell by cell: stretches and the turns between them */
    static final int[][] CELLS = trench();
    /** those holding it: in the fourth stretch and at the end of the fifth */
    static final double[][] HOLDERS = {{3623.5, 15.5}, {3629.5, 12.5}};
    static final double[] START_X = {3606.5, 3611.5, 3616.5, 3621.5};
    static final double START_Z = 40.5;
    /** at the edge: on the ground this near a trench's cell, blocks */
    static final double EDGE = 2.5;
    static final Identifier GUN = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks, firstDown, edge, inside, entriesBefore, fragsBefore, ownBlast, blastKills;
    private int roomsRead = -1, turnsRead = -1;
    private final float[] health = new float[SQUAD], heldHealth = new float[HELD];
    /** the grenades in the air and where each was last; those gone off, in the trench, how near the nearest holding it in all */
    private final java.util.Map<Integer, Vec3> flying = new java.util.HashMap<>();
    private int bursts, burstsDown, heldBlast, nearN, heldOut;
    private double nearSum;
    private final List<Mob> squad = new ArrayList<>(), held = new ArrayList<>();
    private final java.util.Set<Integer> noted = new java.util.HashSet<>();
    /** per round: {on, held left, squad left, ticks, first down at, ticks at the edge, ticks in it, entries, grenades in, blast kills, own hurt by a blast} */
    private final List<int[]> results = new ArrayList<>();

    AiCheckTrench(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
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
            for (int cx = X0 >> 4; cx <= (X0 + SIZE) >> 4; cx++)
                for (int cz = Z0 >> 4; cz <= (Z0 + SIZE) >> 4; cz++) level.setChunkForced(cx, cz, true);
            AiBots.awake = true;
            AiBots.seenDown = AiBots.toldDown = 0;
            start(0);
            return false;
        }
        ticks++;
        // the trench as the AI reads it, once its cells are there
        if (roomsRead < 0 && ticks >= 20) {
            AiRooms.Plan p = AiTrenches.of(TacticalMap.of(level), BlockPos.asLong(CELLS[3][0], y, CELLS[3][1]));
            if (p != null) {
                roomsRead = p.rooms();
                turnsRead = 0;
                for (AiRooms.Way w : p.ways) if (w.turn >= 0) turnsRead++;
            }
        }
        // the grenades in the air: where each was last before it went off, and how near the nearest of the two holding it
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(X0, y - 2, Z0, X0 + SIZE, y + 40, Z0 + SIZE);
        java.util.Set<Integer> now = new java.util.HashSet<>();
        for (var g : level.getEntitiesOfClass(com.hbm.entity.grenade.EntityGrenadeUniversal.class, box)) {
            // the squad's, told by its thrower when first seen (a thrower down later leaves it with none)
            if (!flying.containsKey(g.getId()) && !(g.getOwner() instanceof Mob o && squad.contains(o))) continue;
            now.add(g.getId());
            flying.put(g.getId(), g.position());
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
                if (by == null || by.getDirectEntity() == null || !by.getDirectEntity().getType().toShortString().contains("bullet")) ownBlast++;
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
        results.add(new int[] {arm(round), foes, left, ticks, firstDown, edge, inside, AiEntry.entries - entriesBefore, AiEntry.frags - fragsBefore, blastKills, ownBlast, heldBlast, heldOut});
        for (Mob m : squad) m.discard();
        for (Mob m : held) m.discard();
        squad.clear();
        held.clear();
        if (round + 1 >= ROUNDS) return finish();
        start(round + 1);
        return false;
    }

    /** over one of the trench's cells */
    private static boolean dug(double x, double z) {
        for (int[] c : CELLS) if (Math.floor(x) == c[0] && Math.floor(z) == c[1]) return true;
        return false;
    }

    /** on the ground at the trench's edge */
    private static boolean near(double x, double z) {
        for (int[] c : CELLS) {
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
        for (int[] c : CELLS) {
            set(c[0], y, c[1], air);
            set(c[0], y + 1, c[1], air);
        }
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
        if (team.equals("trench_in") && round < STILL) {
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
        AiTrenches.on = r % 2 == 1;
        AiEntry.on = AiEntry.frag = true;
        AiSquads.on = true;
        AiSquads.offTeams.clear();
        AiSquads.forget(level);
        AiEntry.forget(level);
        AiClearing.forget(level);
        build();
        for (double[] p : HOLDERS) held.add(spawn(p[0], y, p[1], "trench_in", 180F));
        for (int i = 0; i < SQUAD; i++) squad.add(spawn(START_X[i], y + 2, START_Z, "trench_out", 180F));
        AiBots.refreshTargets(level);
        entriesBefore = AiEntry.entries;
        fragsBefore = AiEntry.frags;
        ownBlast = blastKills = heldBlast = 0;
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

    /** the round's arm: aiTrenches off and on, those holding it still; the same, moving */
    private static int arm(int r) {
        return r % 2 + (r >= STILL ? 2 : 0);
    }

    private boolean finish() {
        AiTrenches.on = true;
        String[] names = {"aiTrenches off (before)", "aiTrenches on", "aiTrenches off, those holding it moving", "aiTrenches on, those holding it moving"};
        double[][] sum = new double[4][13];
        int[] n = new int[4], cleared = new int[4], firsts = new int[4];
        double[] clearTicks = new double[4], firstTicks = new double[4];
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
            check.out(String.format(Locale.ROOT, "     trench, round %d, %s: in the trench %d of %d left, of the squad %d of %d, %d ticks, the first in it down %s;"
                    + " the squad's ticks at its edge %d, in it %d; entries %d, grenades in round a turn %d; in the trench hurt by a blast %d, down by one %d; the squad hurt by a blast %d; those holding it out of it %d ticks",
                    i + 1, names[r[0]], r[1], HELD, r[2], SQUAD, r[3], r[4] < 0 ? "never" : "at tick " + r[4], r[5], r[6], r[7], r[8], r[11], r[9], r[10], r[12]));
        }
        for (int on = 0; on < 4; on++)
            check.out(String.format(Locale.ROOT, "     trench, %s, %d rounds: cleared in %d (%.0f ticks on average), the first in it down in %d (%.0f ticks); left in it %.0f of %d,"
                    + " of the squad %.0f of %d; the squad's ticks at its edge %.0f, in it %.0f; entries %.0f, grenades in %.0f; in it hurt by a blast %.0f, down by one %.0f; the squad hurt by a blast %.0f; those holding it out of it %.0f ticks",
                    names[on], n[on], cleared[on], cleared[on] == 0 ? 0 : clearTicks[on] / cleared[on], firsts[on], firsts[on] == 0 ? 0 : firstTicks[on] / firsts[on],
                    sum[on][1], n[on] * HELD, sum[on][2], n[on] * SQUAD, sum[on][5], sum[on][6], sum[on][7], sum[on][8], sum[on][11], sum[on][9], sum[on][10], sum[on][12]));
        check.out(String.format(Locale.ROOT, "     trench, the squad's grenades: %d went off, %d of them in the trench; the nearest holding it %.1f off on average;"
                + " foes seen to fall %d, squad mates told %d", bursts, burstsDown, nearN == 0 ? 0 : nearSum / nearN, AiBots.seenDown, AiBots.toldDown));
        check.out(String.format(Locale.ROOT, "     trench, throws not found: none lands in the room %d, one of the entry not behind the wall %d, another of its side in reach %d, a player %d; stepped out and threw none %d;"
                + " gone in before all were beside the way in %d, all there and no throw found %d", AiEntry.why[0], AiEntry.why[1], AiEntry.why[2], AiEntry.why[3], AiEntry.noThrow,
                AiEntry.unstacked, AiEntry.noPlan));
        check.check("trench: all rounds ran", results.size() == ROUNDS);
        check.check(String.format(Locale.ROOT, "trench: the zigzag is read as five stretches and four turns (%d, %d)", roomsRead, turnsRead), roomsRead == 5 && turnsRead == 4);
        check.check(String.format(Locale.ROOT, "trench: with trenches read they go in round a turn (entries %.0f, %.0f before)", sum[1][7], sum[0][7]), sum[1][7] > sum[0][7]);
        check.check(String.format(Locale.ROOT, "trench: a grenade goes in round a turn before them (%.0f)", sum[1][8]), sum[1][8] > 0);
        // the edge's ticks and the rounds cleared are told above: from one run of the stage to the next they go either way
        double firstOn = firsts[1] == 0 ? Double.MAX_VALUE : firstTicks[1] / firsts[1], firstOff = firsts[0] == 0 ? Double.MAX_VALUE : firstTicks[0] / firsts[0];
        check.check(String.format(Locale.ROOT, "trench: with trenches read the first in it is down sooner (at tick %.0f, %.0f before)", firstOn, firstOff),
                firsts[1] == n[1] && firstOn < firstOff);
        check.check(String.format(Locale.ROOT, "trench: a grenade thrown in round a turn hurts none of the squad (%.0f)", sum[1][10]), sum[1][10] == 0);
        check.check(String.format(Locale.ROOT, "trench: with trenches read a grenade round a turn downs one holding it (%.0f in %d rounds)", sum[1][9], n[1]), sum[1][9] > 0);
        check.check(String.format(Locale.ROOT, "trench: with trenches read a trench whose holders move is cleared more often (%d of %d, %d before)", cleared[3], n[3], cleared[2]),
                cleared[3] > cleared[2]);
        return true;
    }
}
