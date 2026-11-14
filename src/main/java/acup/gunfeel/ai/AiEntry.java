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
 * Going into a room after a target lost in it (stage 4.5c): the ones that go after it do not walk in through the
 * doorway one after another. They come up beside it, out of the line through it, behind the wall (AiRooms.Way's cells
 * beside the opening); one of them with a grenade steps out before the opening, throws it in and steps back behind
 * the wall (a grenade with glue: it sticks where it first hits, so it comes back off no wall; thrown only where its
 * burst is in the room and harms none of its own: those of the entry behind the wall, the rest out of its reach or
 * behind blocks); it has gone off, and they go in together, each to its own side of the way in. From there on they
 * search as before. One entry a side, a target and a room; all of it is over in ENTRY_MAX ticks at most.
 */
public final class AiEntry {
    /** the server setting: AI soldiers stack up beside a doorway and go in together */
    public static final String KEY = "aiEntry";
    /** off only for the stands' "before" */
    static boolean on = true;
    /** the server setting: a grenade goes into the room before they do */
    public static final String FRAG_KEY = "aiEntryGrenade";
    /** the server setting: its filling, he (NTM's high explosive) or powder (weaker: a smaller burst, its own may be nearer) */
    public static final String FILLING_KEY = "aiEntryGrenadeFilling";
    /** off only for the stands' "before" */
    static boolean frag = true;
    /** the stands: entries begun, grenades thrown in before one, throwers that stepped out and did not throw */
    public static int entries, frags, noThrow;
    /** a grenade is thrown this many cells into the room, straight in through the opening */
    static final int DEEP = 4;
    /** the thrower has this long to step out and throw; a throw is worked out this often and this many times; ticks */
    static final int FRAG_MAX = 60, FRAG_EVERY = 5, FRAG_TRIES = 4;
    /**
     * Its own not behind blocks are kept this far from the burst: the filling's reach in NTM (he 7.5, powder 5) and two
     * more. Less than AiGrenades.SAFE: that one is for a grenade that bounces and rolls; this one sticks where it
     * first hits, and that place is worked out from each corner of its box.
     */
    static final double HE_SAFE = 9.5, POWDER_SAFE = 7;
    /** the stands: why a throw was not found: none lands in the room, one of the entry not behind the wall, another of its side in reach, a player */
    public static final int[] why = new int[4];

    /** an opening this wide at most is gone into so; wider, it is no doorway */
    static final int WIDE = 2;
    /** the building is read again for a side and a target this often at most, ticks */
    static final int AGAIN = 100;
    /** beside the way in they wait this long for a second one, and this long at most to get there; ticks */
    static final int WAIT_SECOND = 100, STACK_MAX = 400;
    /** in they go this long at most; an entry and what it knew is let go after this; ticks */
    static final int GO_MAX = 100, ENTRY_MAX = 1200;

    private static final int STACK = 0, FRAG = 1, WAIT = 2, GO = 3, OVER = 4;

    private static final class Op {
        AiRooms.@Nullable Plan plan;
        AiRooms.@Nullable Way way;
        int room = AiRooms.OUTSIDE, phase = STACK, fragId = -1, tries, team;
        long made, phaseAt, nextPlan, lost, front = Long.MIN_VALUE;
        @Nullable AiBot thrower;
        Vec3 aim = Vec3.ZERO;
        double safe;
        long[] slots = {}, inner = {};
        /** where each of the entry waits, by its number: {beside the way in, a grenade going in} */
        final List<long[]> places = new ArrayList<>(4);
        double offX, offZ;
        final List<AiBot> members = new ArrayList<>(2), done = new ArrayList<>(2);
        Vec3 door = Vec3.ZERO;
    }

    private static final Map<ServerLevel, Map<Long, Op>> OPS = new WeakHashMap<>();

    private AiEntry() {
    }

    static boolean on() {
        return on && ReaimTimes.flag(KEY, true);
    }

    public static void forget(ServerLevel level) {
        OPS.remove(level);
    }

    private static long cell(double x, double y, double z) {
        return BlockPos.asLong(Mth.floor(x), Mth.floor(y + 0.2), Mth.floor(z));
    }

    private static Vec3 mid(long cell, double up) {
        return new Vec3(BlockPos.getX(cell) + 0.5, BlockPos.getY(cell) + up, BlockPos.getZ(cell) + 0.5);
    }

    /**
     * A soldier that goes after a lost target (AiSoldier.search), this tick: where it looks while the entry has it
     * (the way in), or null: it searches as before (no room there, it is in already, the entry is over).
     */
    static @Nullable Vec3 step(AiSoldier s, AiBot bot, ServerLevel level, TargetMemory.Contact c, long tick) {
        Op op = find(bot, level, c, tick);
        AiRooms.Plan plan = op.plan;
        if (plan == null || op.way == null) return null;
        advance(op, level, tick);
        return step(op, plan, s, bot, level, tick);
    }

    /**
     * Whether the target was lost in a room an entry is, or would be, made into, the entry not over, with a grenade to
     * throw in first: its squad's flanker does not go round to where it sees the place (in a room that is through the
     * way in, ahead of the entry and in the grenade's reach).
     */
    static boolean roomed(AiBot bot, ServerLevel level, TargetMemory.Contact c, long tick) {
        if (!frag || !AiGrenades.on || !ReaimTimes.flag(FRAG_KEY, true)) return false;
        Op op = find(bot, level, c, tick);
        if (op.way == null || op.phase >= GO) return false;
        // no grenade to throw in: one in the room ahead of them spoils nothing
        if (bot.grenades > 0) return true;
        for (AiBot m : op.members) if (m.grenades > 0) return true;
        return false;
    }

    private static Op find(AiBot bot, ServerLevel level, TargetMemory.Contact c, long tick) {
        Map<Long, Op> ops = OPS.computeIfAbsent(level, l -> new HashMap<>());
        long key = (long) AiSquads.team(bot).hashCode() << 32 | c.id & 0xFFFFFFFFL, lost = cell(c.lostX, c.lostY, c.lostZ);
        Op op = ops.get(key);
        boolean moved = op != null && lost != op.lost && (op.plan == null || op.plan.room(lost) != op.room);
        if (op == null || tick - op.made > ENTRY_MAX || moved && tick >= op.nextPlan && (op.phase == STACK || op.phase == OVER)) {
            if (ops.size() > 64) ops.values().removeIf(o -> tick - o.made > ENTRY_MAX);
            op = make(level, bot, lost, tick);
            op.team = (int) (key >> 32);
            // one entry a side and a way in: another target lost in the same room is gone after by the same ones
            if (op.way != null)
                for (Op o : ops.values())
                    if (o != op && o.team == op.team && o.front == op.front && o.way != null && o.phase != OVER && tick - o.made <= ENTRY_MAX) {
                        op = o;
                        break;
                    }
            ops.put(key, op);
        }
        return op;
    }

    private static @Nullable Vec3 step(Op op, AiRooms.Plan plan, AiSoldier s, AiBot bot, ServerLevel level, long tick) {
        if (op.phase == OVER || op.done.contains(bot)) return null;
        int i = op.members.indexOf(bot);
        if (i < 0) {
            // in the room already, or the others are past waiting: as before
            if (op.phase >= GO || plan.room(cell(bot.mob.getX(), bot.mob.getY(), bot.mob.getZ())) == op.room) return null;
            if (op.phase != STACK || op.members.size() >= op.places.size()) {
                // no place beside the way in left: it stays where it is, its gun on the way in, till they go in
                // (it does not walk in before them, nor into the grenade)
                if (!bot.mover.idle()) bot.mover.stop();
                return op.door;
            }
            i = op.members.size();
            op.members.add(bot);
        }
        if (op.phase == GO) {
            long to = op.inner.length > 0 ? op.inner[i % op.inner.length] : plan.mid[op.room];
            go(bot, to, 0.5);
            if (bot.mover.arrived() || bot.mover.failed()) op.done.add(bot);
        } else if (op.phase == FRAG && bot == op.thrower) frag(op, s, bot, level, tick);
        else go(bot, place(op, i), 0.35);
        return op.door;
    }

    /** where one of the entry waits: beside the way in; a grenade going in, a cell further from it along the wall */
    private static long place(Op op, int i) {
        return op.places.get(i)[op.phase == FRAG || op.phase == WAIT ? 1 : 0];
    }

    private static void go(AiBot bot, long cell, double radius) {
        if (bot.mover.goalIs(cell)) return;
        bot.mover.costs(PathSearch.Cost.NONE);
        bot.mover.goTo(cell, radius, AiSoldier.RUN);
    }

    /** the room the target was lost in, the nearest way into it one or two wide with a place beside it, and the places */
    private static Op make(ServerLevel level, AiBot bot, long lost, long tick) {
        Op op = new Op();
        op.made = op.phaseAt = tick;
        op.nextPlan = tick + AGAIN;
        op.lost = lost;
        AiRooms.Plan plan = AiRooms.of(TacticalMap.of(level), lost);
        if (plan == null) return op;
        int room = plan.room(lost);
        if (room == AiRooms.OUTSIDE) return op;
        double best = Double.MAX_VALUE;
        for (AiRooms.Way w : plan.waysOf(room)) {
            long[] out = w.beside(w.other(room));
            if (w.width > WIDE || out.length == 0 || w.cells.length == 0) continue;
            double d = mid(out[0], 0).distanceToSqr(bot.mob.position());
            if (d >= best) continue;
            best = d;
            op.way = w;
        }
        AiRooms.Way w = op.way;
        if (w == null) return op;
        op.plan = plan;
        op.room = room;
        op.slots = w.beside(w.other(room));
        // two more behind those where the wall goes on; a grenade going in, each is a cell further from the way in
        long[] far = w.far(w.other(room)), far2 = w.far2(w.other(room));
        for (int i = 0; i < op.slots.length; i++) op.places.add(new long[] {op.slots[i], far[i]});
        for (int i = 0; i < op.slots.length; i++)
            if (far[i] != op.slots[i] && far2[i] != far[i]) op.places.add(new long[] {far[i], far2[i]});
        op.inner = w.beside(room);
        // looked at from beside it: the opening's cell at the far end from the room; before it, the cell a thrower steps out to
        long end = room == w.b ? w.cells[0] : w.cells[w.cells.length - 1];
        int out = room == w.b ? Dirs.opposite(w.dir) : w.dir, in = Dirs.opposite(out);
        op.door = mid(end, 1.1);
        op.front = BlockPos.asLong(BlockPos.getX(end) + Dirs.DX[out], BlockPos.getY(end), BlockPos.getZ(end) + Dirs.DZ[out]);
        // the thrower stands so that its hand (a quarter of a block to its right), not its middle, is before the opening's middle
        op.offX = 0.25 * Dirs.DZ[in];
        op.offZ = -0.25 * Dirs.DX[in];
        // where a grenade goes: straight in through the opening, DEEP cells into the room (the room's corners by the wall
        // with the way in cannot be thrown at from before it; the burst reaches them)
        long first = room == w.b ? w.cells[w.cells.length - 1] : w.cells[0];
        for (int k = 1; k <= DEEP; k++) {
            long c = BlockPos.asLong(BlockPos.getX(first) + Dirs.DX[in] * k, BlockPos.getY(first), BlockPos.getZ(first) + Dirs.DZ[in] * k);
            if (plan.room(c) != room) break;
            op.aim = mid(c, 0);
        }
        return op;
    }

    /** the entry's next part, when its time has come (whoever of it thinks first this tick) */
    private static void advance(Op op, ServerLevel level, long tick) {
        op.members.removeIf(b -> !AiSquads.live(b));
        switch (op.phase) {
            case STACK -> {
                if (op.members.isEmpty()) {
                    // none on its way yet (or all down): the wait starts with the first
                    op.phaseAt = tick;
                    return;
                }
                boolean all = true;
                for (int i = 0; i < op.members.size(); i++) {
                    AiBot b = op.members.get(i);
                    all &= b.mover.goalIs(op.places.get(i)[0]) && b.mover.arrived();
                }
                long waited = tick - op.phaseAt;
                if (!(all && (op.members.size() >= Math.min(2, op.slots.length) || waited >= WAIT_SECOND) || waited >= STACK_MAX)) return;
                entries++;
                op.thrower = all ? thrower(op, level, tick) : null;
                phase(op, op.thrower != null ? FRAG : GO, tick);
            }
            case FRAG -> {
                AiBot t = op.thrower;
                if (t == null || !AiSquads.live(t) || t.mover.goal() == op.front && t.mover.failed() || tick - op.phaseAt > FRAG_MAX) phase(op, GO, tick);
            }
            case WAIT -> {
                Entity g = level.getEntity(op.fragId);
                if (g == null || g.isRemoved() || tick - op.phaseAt > AiGrenades.FUSE + 10) phase(op, GO, tick);
            }
            case GO -> {
                if (op.done.containsAll(op.members) || tick - op.phaseAt > GO_MAX) phase(op, OVER, tick);
            }
            default -> {
            }
        }
    }

    private static void phase(Op op, int phase, long tick) {
        op.phase = phase;
        op.phaseAt = tick;
    }

    private static boolean powder() {
        return ReaimTimes.text(FILLING_KEY, "he").trim().equalsIgnoreCase("powder");
    }

    /**
     * The one of the entry that throws: it has a grenade, and (the way in open) a throw from before the opening is
     * found before it steps out; behind a shut door none can be found yet, it opens the door first. Null: no grenade.
     */
    private static @Nullable AiBot thrower(Op op, ServerLevel level, long tick) {
        AiRooms.Way way = op.way;
        if (!frag || !AiGrenades.on || !ReaimTimes.flag(FRAG_KEY, true) || op.aim == Vec3.ZERO || way == null) return null;
        op.safe = powder() ? POWDER_SAFE : HE_SAFE;
        for (AiBot b : op.members) {
            if (b.grenades <= 0 || b.gunner.reloading() || b.prone.lying()) continue;
            if (way.door) return b;
            if (!AiGrenades.mayPlan(level, tick)) return null;
            VoxelRay.Source src = AiBots.voxels(level);
            Vec3 from = mid(op.front, 0).add(op.offX, 0, op.offZ);
            return AiGrenades.planStick(level, from.x, from.y + b.mob.getEyeHeight(), from.z, op.aim, at -> safe(op, level, src, b.mob, at)) != null ? b : null;
        }
        return null;
    }

    /** the thrower: out before the way in, its door opened, the grenade in; then back beside it as its arm goes over */
    private static void frag(Op op, AiSoldier s, AiBot bot, ServerLevel level, long tick) {
        AiRooms.Way way = op.way;
        if (way == null) return;
        bot.mover.costs(PathSearch.Cost.NONE);
        bot.mover.goTo(op.front, 0.15, AiSoldier.RUN, op.offX, op.offZ);
        if (!bot.mover.arrived()) return;
        Mob m = bot.mob;
        for (long at : way.cells) AiDoors.open(level, m, BlockPos.of(at));
        if ((tick - op.phaseAt) % FRAG_EVERY != 0 || !AiGrenades.mayPlan(level, tick)) return;
        VoxelRay.Source src = AiBots.voxels(level);
        AiGrenades.Throw t = AiGrenades.planStick(level, m.getX(), m.getY() + m.getEyeHeight(), m.getZ(), op.aim, at -> safe(op, level, src, m, at));
        if (t == null) {
            if (++op.tries >= FRAG_TRIES) {
                noThrow++;
                phase(op, GO, tick);
            }
            return;
        }
        op.fragId = s.threwInto(bot, level, t, powder() ? EnumGrenadeFilling.POWDER : EnumGrenadeFilling.HE, tick).getId();
        frags++;
        phase(op, WAIT, tick);
        go(bot, op.places.get(op.members.indexOf(bot))[1], 0.35);
    }

    /**
     * Whether a burst here harms none of the thrower's own: it is in the room (over one of its cells); those of the
     * entry are all of them off it behind the wall at their places beside the way in (AiGrenades.shielded, standing);
     * any other of its side is out of its reach (op.safe) or all of it behind blocks from it where it is.
     */
    private static boolean safe(Op op, ServerLevel level, VoxelRay.Source src, Mob thrower, Vec3 b) {
        AiRooms.Plan plan = op.plan;
        if (plan == null || plan.room(BlockPos.asLong(Mth.floor(b.x), BlockPos.getY(plan.mid[op.room]), Mth.floor(b.z))) != op.room) {
            why[0]++;
            return false;
        }
        double s2 = op.safe * op.safe;
        for (AiBot o : AiBots.bots(level)) {
            if (!o.mob.isAlive() || o.mob != thrower && !AiTeams.own(thrower, o.mob)) continue;
            int i = op.members.indexOf(o);
            if (i >= 0) {
                if (!shielded(src, op.places.get(i)[1], o.mob, b)) {
                    why[1]++;
                    return false;
                }
            } else if (o.mob.distanceToSqr(b) < s2 && !AiGrenades.shielded(src, o.mob.getX(), o.mob.getY(), o.mob.getZ(), o.mob.getBbHeight(), b)) {
                why[2]++;
                return false;
            }
        }
        for (Player p : level.players())
            if (!p.isSpectator() && p.isAlive() && AiTeams.own(thrower, p) && p.distanceToSqr(b) < s2
                    && !AiGrenades.shielded(src, p.getX(), p.getY(), p.getZ(), p.getBbHeight(), b)) {
                why[3]++;
                return false;
            }
        return true;
    }

    private static boolean shielded(VoxelRay.Source src, long slot, Mob m, Vec3 burst) {
        return AiGrenades.shielded(src, BlockPos.getX(slot) + 0.5, BlockPos.getY(slot), BlockPos.getZ(slot) + 0.5, m.getDimensions(Pose.STANDING).height(), burst, false);
    }

    /**
     * Whether this is a grenade thrown into a room before an entry that does this mob no harm where it is: one of the
     * entry, its place beside the way in all behind the wall from where it will burst; another of its side, out of
     * its reach or all of it behind blocks. It does not run from that one.
     */
    static boolean keeps(ServerLevel level, AiBot bot, int grenade, Vec3 burst, VoxelRay.Source src) {
        Map<Long, Op> ops = OPS.get(level);
        if (ops == null) return false;
        for (Op op : ops.values()) {
            if (op.fragId != grenade) continue;
            int i = op.members.indexOf(bot);
            if (i >= 0) return shielded(src, op.places.get(i)[1], bot.mob, burst);
            return bot.mob.distanceToSqr(burst) >= op.safe * op.safe || AiGrenades.shielded(src, bot.mob.getX(), bot.mob.getY(), bot.mob.getZ(), bot.mob.getBbHeight(), burst);
        }
        return false;
    }
}
