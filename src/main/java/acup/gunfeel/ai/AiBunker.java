package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import com.hbm.items.weapon.grenade.ItemGrenadeFilling.EnumGrenadeFilling;

import acup.gunfeel.ReaimTimes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * A bunker's embrasures (stage 6, T8): a slit one block high at head height, in a wall with solid blocks over it and
 * under it, is a window no one goes in by (AiRooms.Window). From in there a foe sees out only along a wedge: as wide
 * as the slit at its outer face and wider by the slit's width for each block of the wall's thickness. A side that knows
 * of a foe in a building with such slits keeps out of their wedges: a cell in one costs a step AVOID times over (AiMover's
 * second cost), so its ways go round by the blind side. Its base of fire, keeping the place under fire, fires at the
 * slit (AiSoldier.suppress). One with a grenade goes up to the wall beside the slit, out of its wedge, steps before it,
 * throws the grenade in through it (one with glue: it bursts where it first hits, in the room) and steps back beside it.
 * Then the fight goes on as before (the way in at the back: AiEntry). One throw a side at a time.
 */
public final class AiBunker {
    /** the server setting: AI soldiers keep out of a bunker's embrasures' fire and throw grenades in through them */
    public static final String KEY = "aiBunker";
    /** off only for the stands' "before" */
    static boolean on = true;
    /** the stands: throws begun, grenades in through a slit, throwers that stepped out and found no throw */
    public static int begun, frags, noThrow;
    /** the stands: throws ended before a grenade: the thrower down, too long going, its way failed, the foe gone from the slit's room */
    public static final int[] lost = new int[4];
    /** the stands: the last throw's place beside the slit and where the thrower was when it ended */
    public static String lastSide = "-", lastBurst = "-";

    /** a slit's wall this thick at most, blocks */
    static final int THICK_MAX = 4;
    /** its wedge this long, blocks */
    static final double RANGE = 64;
    /** a step into a slit's wedge costs this much more than one out of it (a step costs 1) */
    static final float AVOID = 9F;
    /** half a mob's width: the wedge is kept clear of all of it */
    static final double MARGIN = 0.3;
    /** a foe out of sight this long at most is still in its building; within this far, blocks */
    static final int KNOWN = 1200;
    static final double NEAR = 64;
    /** the slits it keeps out of are worked out again this often, ticks */
    static final int AGAIN = 20;
    /** a throw goes this many cells into the room through the slit */
    static final int DEEP = 2;
    /** the thrower's place beside a slit: so many cells past its end along the wall */
    static final int SIDE = 2;
    /** before the slit near enough its cell's middle to throw, blocks */
    static final double OUT_NEAR = 0.35;
    /** the thrower has this long to get beside the slit, this long to step out and throw, ticks; tries so many throws */
    static final int GO_MAX = 600, OUT_MAX = 60, OUT_EVERY = 5, OUT_TRIES = 4;
    /** after a throw, the next one of its side this long after at the soonest, ticks */
    static final int NEXT = 200;
    /** a throw: through a wall this thick at least, at a foe lost no longer ago than this (ticks), from no further than
     *  this from the place beside the slit (blocks) */
    static final int THROW_THICK = 2, THROW_KNOWN = 200, THROW_FAR = 32;

    /**
     * An embrasure: one or more slits side by side. Its outer face's middle (fx, fz), the floor it is seen from (y, the
     * room's), out of the room (dir), wide and thick (blocks), the wedge's widening a block (w / t); the cells before it
     * (front), beside it out of the wedge against the wall (sides), the place a grenade is thrown at (aim), its room.
     */
    record Slit(double fx, double fz, int y, int dir, int width, int thick, double tan, long front, long[] sides, Vec3 aim, int room) {
        /** whether this point (its floor y) is in the wedge, MARGIN wider */
        boolean sees(double px, double py, double pz) {
            int dx = Dirs.DX[dir], dz = Dirs.DZ[dir];
            double along = (px - fx) * dx + (pz - fz) * dz;
            if (along < 0 || along > RANGE) return false;
            double lat = Math.abs((px - fx) * -dz + (pz - fz) * dx);
            return lat <= width * 0.5 + along * tan + MARGIN && Math.abs(py - y) <= 3 + along * 0.6;
        }

        /** the middle of the slit's outer face, at head height */
        Vec3 face() {
            return new Vec3(fx, y + 1.5, fz);
        }
    }

    private static final class Op {
        final String team;
        final Slit slit;
        final AiRooms.Plan plan;
        final AiBot thrower;
        final long side;
        int phase = GO, tries, fragId = -1;
        long phaseAt;
        /** the throw from before the slit, worked out at the wall beside it: out, throw, back */
        AiGrenades.@Nullable Throw ready;
        @Nullable Vec3 at;

        Op(String team, Slit slit, AiRooms.Plan plan, AiBot thrower, long side, long tick) {
            this.team = team;
            this.slit = slit;
            this.plan = plan;
            this.thrower = thrower;
            this.side = side;
            this.phaseAt = tick;
        }
    }

    private static final int GO = 0, OUT = 1, WAIT = 2, OVER = 3;

    private static final class PerLevel {
        final Map<AiRooms.Plan, List<Slit>> slits = new WeakHashMap<>();
        /** a foe's cell: the building read there, when */
        final Map<Long, Object[]> plans = new HashMap<>();
        /** each soldier's slits to keep out of, and when worked out */
        final Map<Integer, Object[]> near = new HashMap<>();
        final Map<String, Op> ops = new HashMap<>();
        /** each side's next throw at the soonest */
        final Map<String, Long> next = new HashMap<>();
    }

    private static final Map<ServerLevel, PerLevel> LEVELS = new WeakHashMap<>();

    private AiBunker() {
    }

    static boolean on() {
        return on && ReaimTimes.flag(KEY, true);
    }

    public static void forget(ServerLevel level) {
        LEVELS.remove(level);
    }

    private static PerLevel of(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, k -> new PerLevel());
    }

    private static Vec3 mid(long cell, double up) {
        return new Vec3(BlockPos.getX(cell) + 0.5, BlockPos.getY(cell) + up, BlockPos.getZ(cell) + 0.5);
    }

    // ------------------------------------------------------------------ reading

    /** whether a mob stands in the cell: its feet and head free, a floor under it */
    private static boolean stands(CellSource src, int x, int y, int z) {
        return Voxel.open(src.voxel(x, y, z)) && Voxel.open(src.voxel(x, y + 1, z)) && Voxel.floor(src.voxel(x, y - 1, z));
    }

    /** a block a mob does not see nor walk through */
    private static boolean wall(int v) {
        return !Voxel.open(v) && Voxel.sight(v);
    }

    /** the embrasures of a building: its open windows with a wall over them, side by side ones as one */
    static List<Slit> slits(CellSource src, AiRooms.Plan plan) {
        // each open window one high: {x, y (the floor), z, dir, thick, room}
        List<int[]> one = new ArrayList<>();
        PathSearch.Reader reader = new PathSearch.Reader(src);
        for (AiRooms.Window w : plan.windows) {
            if (w.glazed()) continue;
            int wx = BlockPos.getX(w.block()), hy = BlockPos.getY(w.block()), wz = BlockPos.getZ(w.block()), dx = Dirs.DX[w.dir()], dz = Dirs.DZ[w.dir()];
            if (!wall(src.voxel(wx, hy + 1, wz))) continue;
            int t = 1;
            while (t < THICK_MAX) {
                int bx = wx + dx * t, bz = wz + dz * t;
                int head = src.voxel(bx, hy, bz);
                if (!Voxel.open(head) || Voxel.sight(head) || Voxel.open(src.voxel(bx, hy - 1, bz)) || !wall(src.voxel(bx, hy + 1, bz))) break;
                t++;
            }
            // seen out of onto ground one stands on (a slit onto earth, under ground, is none), out in the open: a hole
            // into another room, of this building or under any roof, is none
            int ox = wx + dx * t, oz = wz + dz * t;
            if (!stands(src, ox, hy - 1, oz)) continue;
            long o = BlockPos.asLong(ox, hy - 1, oz);
            if (plan.room(o) != AiRooms.OUTSIDE || (reader.flags(ox, hy - 1, oz) & SectionCells.ROOF) != 0) continue;
            one.add(new int[] {wx, hy - 1, wz, w.dir(), t, w.room()});
        }
        List<Slit> out = new ArrayList<>();
        boolean[] used = new boolean[one.size()];
        for (int i = 0; i < one.size(); i++) {
            if (used[i]) continue;
            int[] a = one.get(i);
            int dir = a[3], dx = Dirs.DX[dir], dz = Dirs.DZ[dir], px = -dz, pz = dx;
            // side by side along the wall, the same floor, the same way out
            List<int[]> row = new ArrayList<>();
            row.add(a);
            used[i] = true;
            for (boolean grew = true; grew;) {
                grew = false;
                for (int j = 0; j < one.size(); j++) {
                    if (used[j]) continue;
                    int[] b = one.get(j);
                    if (b[3] != dir || b[1] != a[1]) continue;
                    for (int[] r : row)
                        if (b[0] == r[0] + px && b[2] == r[2] + pz || b[0] == r[0] - px && b[2] == r[2] - pz) {
                            row.add(b);
                            used[j] = true;
                            grew = true;
                            break;
                        }
                }
            }
            int w = row.size(), t = Integer.MAX_VALUE;
            double cx = 0, cz = 0;
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            int[] loB = a, hiB = a;
            for (int[] r : row) {
                t = Math.min(t, r[4]);
                cx += r[0] + 0.5;
                cz += r[2] + 0.5;
                int along = r[0] * px + r[2] * pz;
                if (along < lo) {
                    lo = along;
                    loB = r;
                }
                if (along > hi) {
                    hi = along;
                    hiB = r;
                }
            }
            cx /= w;
            cz /= w;
            int y = a[1];
            double fx = cx + dx * (t - 0.5), fz = cz + dz * (t - 0.5);
            // before it, the middle slit's; beside it, SIDE cells past each end against the wall (the next one out is in its
            // line: a body half a block wide there is shot from in the room)
            int[] m = row.get(0);
            for (int[] r : row) if (Math.abs(r[0] + 0.5 - cx) + Math.abs(r[2] + 0.5 - cz) < Math.abs(m[0] + 0.5 - cx) + Math.abs(m[2] + 0.5 - cz)) m = r;
            long front = BlockPos.asLong(m[0] + dx * t, y, m[2] + dz * t);
            List<Long> sides = new ArrayList<>(2);
            int[][] ends = {{loB[0] - px * SIDE, loB[2] - pz * SIDE}, {hiB[0] + px * SIDE, hiB[2] + pz * SIDE}};
            for (int[] e : ends) {
                int sx = e[0] + dx * t, sz = e[1] + dz * t;
                if (stands(src, sx, y, sz)) sides.add(BlockPos.asLong(sx, y, sz));
            }
            // a grenade's place: straight in, DEEP cells into the room (fewer if the room is shallower): where the one firing
            // out of it stands, not the far end of the room (a man a few blocks from a burst may live)
            Vec3 aim = null;
            for (int k = 1; k <= DEEP; k++) {
                long c = BlockPos.asLong(m[0] - dx * k, y, m[2] - dz * k);
                if (plan.room(c) == AiRooms.OUTSIDE) break;
                aim = mid(c, 0);
            }
            if (aim == null) continue;
            long[] s = new long[sides.size()];
            for (int k = 0; k < s.length; k++) s[k] = sides.get(k);
            out.add(new Slit(fx, fz, y, dir, w, t, (double) w / t, front, s, aim, m[5]));
        }
        // a place beside one slit in the wedge of any (a wide slit in a thin wall, another slit next to it): none
        List<Slit> kept = new ArrayList<>(out.size());
        for (Slit o : out) {
            long[] ok = java.util.Arrays.stream(o.sides).filter(c -> {
                for (Slit q : out) if (q.sees(BlockPos.getX(c) + 0.5, BlockPos.getY(c), BlockPos.getZ(c) + 0.5)) return false;
                return true;
            }).toArray();
            kept.add(ok.length == o.sides.length ? o : new Slit(o.fx, o.fz, o.y, o.dir, o.width, o.thick, o.tan, o.front, ok, o.aim, o.room));
        }
        return kept;
    }

    /** the building the cell is in (read again every AGAIN ticks at most), or null */
    private static AiRooms.@Nullable Plan plan(PerLevel l, CellSource src, long cell, long tick) {
        Object[] p = l.plans.get(cell);
        if (p != null && tick - (long) p[0] < AGAIN * 5) return (AiRooms.Plan) p[1];
        if (l.plans.size() > 256) l.plans.values().removeIf(o -> tick - (long) o[0] >= AGAIN * 5);
        // a building read lately for another cell of it: the same one (a foe walking about in it is no new reading)
        for (Object[] o : l.plans.values())
            if (tick - (long) o[0] < AGAIN * 5 && o[1] instanceof AiRooms.Plan known && known.room(cell) != AiRooms.OUTSIDE) {
                l.plans.put(cell, new Object[] {o[0], known});
                return known;
            }
        AiRooms.Plan plan;
        try {
            plan = AiRooms.of(src, cell);
        } catch (RuntimeException e) {
            AiRooms.fault("reading a bunker", cell, e);
            plan = null;
        }
        l.plans.put(cell, new Object[] {tick, plan});
        return plan;
    }

    private static List<Slit> slitsOf(PerLevel l, CellSource src, AiRooms.Plan plan) {
        return l.slits.computeIfAbsent(plan, p -> slits(src, p));
    }

    /** where a contact is (in sight) or was (lost a while ago), as a cell; MIN_VALUE: too long ago */
    private static long where(TargetMemory.Contact c, long tick) {
        boolean seen = c.state == TargetMemory.State.VISIBLE || c.state == TargetMemory.State.GLIMPSED;
        if (!seen && tick - c.lostTick > KNOWN) return Long.MIN_VALUE;
        double x = seen ? c.x : c.lostX, y = seen ? c.y : c.lostY, z = seen ? c.z : c.lostZ;
        return BlockPos.asLong(Mth.floor(x), Mth.floor(y + 0.2), Mth.floor(z));
    }

    /** the slits of the buildings its foes are in (known lately, near): {slit, the plan} */
    @SuppressWarnings("unchecked")
    private static List<Object[]> near(PerLevel l, ServerLevel level, AiBot bot, long tick) {
        Object[] got = l.near.get(bot.mob.getId());
        if (got != null && tick - (long) got[0] < AGAIN) return (List<Object[]>) got[1];
        if (l.near.size() > 512) l.near.values().removeIf(o -> tick - (long) o[0] >= AGAIN);
        List<Object[]> out = new ArrayList<>();
        CellSource src = TacticalMap.of(level);
        java.util.Set<Slit> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (TargetMemory.Contact c : bot.memory.contacts()) {
            long cell = where(c, tick);
            if (cell == Long.MIN_VALUE || bot.mob.distanceToSqr(BlockPos.getX(cell), BlockPos.getY(cell), BlockPos.getZ(cell)) > NEAR * NEAR) continue;
            AiRooms.Plan plan = plan(l, src, cell, tick);
            if (plan == null) continue;
            int room = plan.room(cell);
            if (room == AiRooms.OUTSIDE) continue;
            // the slits of the room the foe is in: another room's fire is not its
            for (Slit s : slitsOf(l, src, plan)) if (s.room == room && seen.add(s)) out.add(new Object[] {s, plan, c.lostTick, c.state});
        }
        l.near.put(bot.mob.getId(), new Object[] {tick, out});
        return out;
    }

    // ------------------------------------------------------------------ keeping out of the wedges

    /** AiMover's second cost for it: a step into a wedge of its foes' slits AVOID more; NONE with none */
    static PathSearch.Cost avoid(AiBot bot, ServerLevel level, long tick) {
        if (!on()) return PathSearch.Cost.NONE;
        List<Object[]> near = near(of(level), level, bot, tick);
        if (near.isEmpty()) return PathSearch.Cost.NONE;
        Slit[] s = new Slit[near.size()];
        for (int i = 0; i < s.length; i++) s[i] = (Slit) near.get(i)[0];
        return (x, y, z) -> {
            for (Slit o : s) if (o.sees(x + 0.5, y, z + 0.5)) return AVOID;
            return 0F;
        };
    }

    /** how far it looks round for a cell out of the wedges, blocks */
    static final int ASIDE = 12;

    /**
     * With no cover, standing in a wedge of a slit its foes are behind: the nearest cell out of all of them it can stand
     * on (about its height, within ASIDE), or MIN_VALUE (out of them already, or none).
     */
    static long aside(ServerLevel level, AiBot bot, long tick) {
        if (!on()) return Long.MIN_VALUE;
        List<Object[]> near = near(of(level), level, bot, tick);
        if (near.isEmpty()) return Long.MIN_VALUE;
        Mob m = bot.mob;
        boolean in = false;
        for (Object[] o : near) if (((Slit) o[0]).sees(m.getX(), m.getY(), m.getZ())) in = true;
        if (!in) return Long.MIN_VALUE;
        CellSource src = TacticalMap.of(level);
        int bx = Mth.floor(m.getX()), by = Mth.floor(m.getY()), bz = Mth.floor(m.getZ());
        long best = Long.MIN_VALUE;
        double bd = Double.MAX_VALUE;
        for (int dx = -ASIDE; dx <= ASIDE; dx++)
            for (int dz = -ASIDE; dz <= ASIDE; dz++) {
                double d = dx * dx + dz * dz;
                if (d >= bd || d > ASIDE * ASIDE) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    int x = bx + dx, y = by + dy, z = bz + dz;
                    if (!stands(src, x, y, z)) continue;
                    boolean seen = false;
                    for (Object[] o : near) if (((Slit) o[0]).sees(x + 0.5, y, z + 0.5)) seen = true;
                    if (seen) continue;
                    bd = d;
                    best = BlockPos.asLong(x, y, z);
                    break;
                }
            }
        return best;
    }

    /** whether the mob stands in a wedge of a slit its side's foes are behind (the stands) */
    static boolean inWedge(ServerLevel level, AiBot bot, long tick) {
        for (Object[] o : near(of(level), level, bot, tick)) if (((Slit) o[0]).sees(bot.mob.getX(), bot.mob.getY(), bot.mob.getZ())) return true;
        return false;
    }

    /**
     * Where its burst at a foe gone out of sight goes: the slit of that foe's building it stands in the wedge of (it
     * sees it), or null (none: at the place, as before).
     */
    static @Nullable Vec3 slitFor(ServerLevel level, AiBot bot, TargetMemory.Contact c, long tick) {
        if (!on()) return null;
        PerLevel l = of(level);
        long cell = where(c, tick);
        if (cell == Long.MIN_VALUE) return null;
        Vec3 best = null;
        double bd = Double.MAX_VALUE;
        for (Object[] o : near(l, level, bot, tick)) {
            Slit s = (Slit) o[0];
            AiRooms.Plan plan = (AiRooms.Plan) o[1];
            if (plan.room(cell) == AiRooms.OUTSIDE || !s.sees(bot.mob.getX(), bot.mob.getY(), bot.mob.getZ())) continue;
            Vec3 f = s.face();
            double d = f.distanceToSqr(bot.mob.position());
            if (d < bd) {
                bd = d;
                best = f;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ the throw

    /**
     * A soldier, this tick: whether it is its side's thrower at a slit (it goes, throws, steps back; nothing else). The
     * one of its squad with a grenade nearest a slit of its foe's building begins a throw, one a side at a time.
     */
    static boolean step(AiSoldier s, AiBot bot, ServerLevel level, long tick) {
        if (!on()) return false;
        PerLevel l = of(level);
        String team = AiSquads.team(bot);
        Op op = l.ops.get(team);
        if (op != null && op.phase != OVER && !AiSquads.live(op.thrower)) {
            lost[0]++;
            lastSide = where(op);
        }
        if (op != null && op.phase != OVER && AiSquads.live(op.thrower) && !op.team.equals(AiSquads.team(op.thrower))) end(l, op, tick);
        if (op != null && (op.phase == OVER || !AiSquads.live(op.thrower))) {
            l.ops.remove(team);
            op = null;
        }
        if (op == null) {
            if (tick < l.next.getOrDefault(team, Long.MIN_VALUE) || (tick + bot.mob.getId()) % 10 != 0) return false;
            op = begin(l, bot, level, team, tick);
            if (op == null) return false;
        }
        if (op.thrower != bot) return false;
        try {
            return advance(l, op, s, bot, level, tick);
        } catch (RuntimeException e) {
            AiRooms.fault("a throw at a slit", op.slit.front, e);
            end(l, op, tick);
            return false;
        }
    }

    private static @Nullable Op begin(PerLevel l, AiBot bot, ServerLevel level, String team, long tick) {
        if (!AiGrenades.on || bot.grenades <= 0 || AiWater.swims(bot.mob) || !AiSquads.mayMove(bot) || AiMedic.waits(bot) || bot.confidence.fallsBack(tick)
                || AiCrossing.of(bot) != null) return null;
        List<Object[]> near = new ArrayList<>();
        // a grenade only through a bunker's slit (a wall THROW_THICK thick at least: an open window of a house is none) at
        // a foe known lately
        for (Object[] o : near(l, level, bot, tick)) {
            boolean seen = o[3] == TargetMemory.State.VISIBLE || o[3] == TargetMemory.State.GLIMPSED;
            if (((Slit) o[0]).thick >= THROW_THICK && (seen || tick - (long) o[2] <= THROW_KNOWN)) near.add(o);
        }
        if (near.isEmpty()) return null;
        // the slit with a place beside it nearest it
        Slit slit = null;
        AiRooms.Plan plan = null;
        long side = Long.MIN_VALUE;
        double best = Double.MAX_VALUE;
        for (Object[] o : near) {
            Slit sl = (Slit) o[0];
            for (long c : sl.sides) {
                double d = bot.mob.distanceToSqr(mid(c, 0));
                if (d < best) {
                    best = d;
                    slit = sl;
                    plan = (AiRooms.Plan) o[1];
                    side = c;
                }
            }
        }
        if (slit == null || plan == null || best > THROW_FAR * THROW_FAR) return null;
        // the one of its squad with a grenade nearest that place throws
        AiSquads.Squad sq = AiSquads.of(bot);
        if (sq != null)
            for (AiBot b : sq.bots)
                if (b != bot && AiSquads.live(b) && b.grenades > 0 && b.mob.distanceToSqr(mid(side, 0)) < best) return null;
        begun++;
        Op op = new Op(team, slit, plan, bot, side, tick);
        l.ops.put(team, op);
        if (bot.brain instanceof AiSoldier so) so.note(bot, tick, "to the wall beside a slit, out of its fire, to throw a grenade in");
        return op;
    }

    /** whether it is a thrower at a slit now (the stands) */
    static boolean throwing(AiBot bot) {
        if (!(bot.mob.level() instanceof ServerLevel level)) return false;
        PerLevel l = LEVELS.get(level);
        if (l == null) return false;
        for (Op op : l.ops.values()) if (op.thrower == bot && op.phase != OVER) return true;
        return false;
    }

    private static String where(Op op) {
        return String.format(java.util.Locale.ROOT, "side %d %d %d, the thrower at %.1f %.1f %.1f", BlockPos.getX(op.side), BlockPos.getY(op.side), BlockPos.getZ(op.side),
                op.thrower.mob.getX(), op.thrower.mob.getY(), op.thrower.mob.getZ());
    }

    private static void end(PerLevel l, Op op, long tick) {
        op.phase = OVER;
        l.ops.remove(op.team);
        l.next.put(op.team, tick + NEXT);
    }

    private static void phase(Op op, int phase, long tick) {
        op.phase = phase;
        op.phaseAt = tick;
    }

    private static boolean advance(PerLevel l, Op op, AiSoldier s, AiBot bot, ServerLevel level, long tick) {
        Slit sl = op.slit;
        Vec3 face = sl.face();
        // the foe gone from the slit's room (or forgotten): no throw
        if (op.phase == GO || op.phase == OUT) {
            boolean still = false;
            // by its place: the building is read again now and then, its slits made anew
            for (Object[] o : near(l, level, bot, tick)) if (((Slit) o[0]).front == sl.front) still = true;
            if (!still) {
                lost[3]++;
                end(l, op, tick);
                return false;
            }
        }
        switch (op.phase) {
            case GO -> {
                if (tick - op.phaseAt > GO_MAX || bot.mover.goalIs(op.side) && bot.mover.failed()) {
                    lost[tick - op.phaseAt > GO_MAX ? 1 : 2]++;
                    lastSide = where(op);
                    end(l, op, tick);
                    return false;
                }
                s.moveFiring(bot, op.side, AiSoldier.RUN, face, tick, "to the wall beside a slit, out of its fire", AiThoughts.Why.BUNKER);
                if (!bot.mover.goalIs(op.side) || !bot.mover.arrived()) return true;
                // at the wall: the throw from before the slit worked out here, out of its fire; none, again a little later
                if ((tick - op.phaseAt) % OUT_EVERY != 0 || !AiGrenades.mayPlan(level, tick)) return true;
                Mob m = bot.mob;
                VoxelRay.Source src = AiBots.voxels(level);
                Vec3 out = mid(sl.front, 0);
                op.ready = AiGrenades.planStick(level, out.x, out.y + m.getEyeHeight(), out.z, sl.aim, at -> safe(op, level, src, m, at));
                if (op.ready == null) {
                    if (++op.tries >= OUT_TRIES) {
                        noThrow++;
                        end(l, op, tick);
                        return false;
                    }
                    return true;
                }
                phase(op, OUT, tick);
                return true;
            }
            case OUT -> {
                if (tick - op.phaseAt > OUT_MAX || bot.gunner.reloading() || bot.mover.goalIs(sl.front) && bot.mover.failed()) {
                    noThrow++;
                    end(l, op, tick);
                    return false;
                }
                s.moveFiring(bot, sl.front, AiSoldier.RUN, face, tick, "steps out before the slit to throw a grenade in", AiThoughts.Why.BUNKER, OUT_NEAR * 0.5);
                // thrown the moment it is there (near enough the cell's middle for the throw worked out from it)
                Vec3 out = mid(sl.front, 0);
                double dx = bot.mob.getX() - out.x, dz = bot.mob.getZ() - out.z;
                if (dx * dx + dz * dz > OUT_NEAR * OUT_NEAR || op.ready == null) return true;
                // its own there now as when it was worked out: none come near the burst since (going in at the back)
                VoxelRay.Source vs = AiBots.voxels(level);
                if (!safe(op, level, vs, bot.mob, op.ready.burst())) {
                    noThrow++;
                    end(l, op, tick);
                    return false;
                }
                // the hand put before the slit, on its middle line (a slit is a block wide: from the hand a quarter block to
                // the right, as a grenade leaves it, the stick hits its side and bursts in it, at the wall), turned from
                // there to the burst worked out
                Vec3 b = op.ready.burst(), f = sl.face();
                double ax = sl.aim.x - f.x, az = sl.aim.z - f.z, al = Math.sqrt(ax * ax + az * az);
                ax /= al;
                az /= al;
                double along = (bot.mob.getX() - out.x) * ax + (bot.mob.getZ() - out.z) * az;
                double hx = out.x + ax * along, hz = out.z + az * along;
                float yaw = (float) Math.toDegrees(Math.atan2(b.z - hz, b.x - hx)) - 90F;
                AiGrenades.Throw t = new AiGrenades.Throw(yaw, op.ready.pitch(), b, op.ready.share());
                Entity thrown = s.threwInto(bot, level, t, EnumGrenadeFilling.HE, tick);
                thrown.setPos(hx, thrown.getY(), hz);
                thrown.setOldPosAndRot();
                op.fragId = thrown.getId();
                frags++;
                phase(op, WAIT, tick);
                return true;
            }
            case WAIT -> {
                Entity g = level.getEntity(op.fragId);
                if (g != null && !g.isRemoved()) op.at = g.position();
                if (g == null || g.isRemoved() || tick - op.phaseAt > AiGrenades.FUSE + 10) {
                    lastBurst = String.format(java.util.Locale.ROOT, "planned %.1f %.1f %.1f, last seen %s", op.ready.burst().x, op.ready.burst().y, op.ready.burst().z,
                            op.at == null ? "-" : String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", op.at.x, op.at.y, op.at.z));
                    end(l, op, tick);
                    return false;
                }
                s.moveFiring(bot, op.side, AiSoldier.RUN, face, tick, "back beside the slit, its grenade in", AiThoughts.Why.BUNKER);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Whether a burst here harms none of the thrower's own: in the bunker's room; the thrower back at its place beside
     * the slit, all of it behind the wall from there; any other of its side out of the reach its blast hurts in
     * (AiEntry.HE_HURTS) or behind blocks (and held there till the burst: holds).
     */
    private static boolean safe(Op op, ServerLevel level, VoxelRay.Source src, Mob thrower, Vec3 b) {
        if (op.plan.room(BlockPos.asLong(Mth.floor(b.x), op.slit.y, Mth.floor(b.z))) == AiRooms.OUTSIDE) return false;
        if (!shielded(src, op.side, thrower, b)) return false;
        double s2 = AiEntry.HE_HURTS * AiEntry.HE_HURTS;
        for (AiBot o : AiBots.bots(level)) {
            if (o.mob == thrower || !o.mob.isAlive() || !AiTeams.own(thrower, o.mob)) continue;
            if (o.mob.distanceToSqr(b) < s2 && !AiGrenades.shielded(src, o.mob.getX(), o.mob.getY(), o.mob.getZ(), o.mob.getBbHeight(), b)) return false;
        }
        for (Player p : level.players())
            if (!p.isSpectator() && p.isAlive() && AiTeams.own(thrower, p) && p.distanceToSqr(b) < s2
                    && !AiGrenades.shielded(src, p.getX(), p.getY(), p.getZ(), p.getBbHeight(), b)) return false;
        return true;
    }

    private static boolean shielded(VoxelRay.Source src, long cell, Mob m, Vec3 burst) {
        return AiGrenades.shielded(src, BlockPos.getX(cell) + 0.5, BlockPos.getY(cell), BlockPos.getZ(cell) + 0.5, m.getDimensions(Pose.STANDING).height(), burst, false);
    }

    /**
     * Another of the side of a thrower at a slit whose grenade is in, not burst yet (not the thrower, nor one of an
     * entry going on): in the reach its blast hurts in, all of it behind blocks from the burst where it stands. It
     * stays there till the burst, as by an entry's grenade (AiEntry.holds); in the open there it runs (keeps,
     * AiSoldier.dodge).
     */
    static boolean holds(AiBot bot, ServerLevel level) {
        PerLevel l = LEVELS.get(level);
        if (l == null || l.ops.isEmpty()) return false;
        Op op = l.ops.get(AiSquads.team(bot));
        if (op == null || op.phase != WAIT || op.thrower == bot || AiEntry.going(bot, level)) return false;
        Entity g = level.getEntity(op.fragId);
        if (g == null || g.isRemoved()) return false;
        Vec3 b = AiGrenades.predicted(level, g);
        if (b == null && op.ready != null) b = op.ready.burst();
        if (b == null) return false;
        Mob m = bot.mob;
        return m.distanceToSqr(b) < AiEntry.HE_HURTS * AiEntry.HE_HURTS && AiGrenades.shielded(AiBots.voxels(level), m.getX(), m.getY(), m.getZ(), m.getBbHeight(), b);
    }

    /** whether this is its side's grenade in through a slit and it the thrower (back beside the slit) or out of its harm: it does not run */
    static boolean keeps(ServerLevel level, AiBot bot, int grenade, Vec3 burst, VoxelRay.Source src) {
        PerLevel l = LEVELS.get(level);
        if (l == null) return false;
        for (Op op : l.ops.values()) {
            if (op.fragId != grenade) continue;
            if (op.thrower == bot) return bot.mob.distanceToSqr(mid(op.side, 0)) < 0.5 && shielded(src, op.side, bot.mob, burst);
            return bot.mob.distanceToSqr(burst) >= AiEntry.HE_HURTS * AiEntry.HE_HURTS
                    || AiGrenades.shielded(src, bot.mob.getX(), bot.mob.getY(), bot.mob.getZ(), bot.mob.getBbHeight(), burst);
        }
        return false;
    }
}
