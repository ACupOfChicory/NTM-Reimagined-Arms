package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ReaimTimes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Going into a room after a target lost in it (stage 4.5c): the ones that go after it do not walk in through the
 * doorway one after another. They come up beside it, out of the line through it, behind the wall (AiRooms.Way's cells
 * beside the opening), and go in together, each to its own side of the way in. From there on they search as before.
 * One entry a side, a target and a room; all of it is over in ENTRY_MAX ticks at most.
 */
public final class AiEntry {
    /** the server setting: AI soldiers stack up beside a doorway and go in together */
    public static final String KEY = "aiEntry";
    /** off only for the stands' "before" */
    static boolean on = true;
    /** the stands: entries begun */
    public static int entries;

    /** an opening this wide at most is gone into so; wider, it is no doorway */
    static final int WIDE = 2;
    /** the building is read again for a side and a target this often at most, ticks */
    static final int AGAIN = 100;
    /** beside the way in they wait this long for a second one, and this long at most to get there; ticks */
    static final int WAIT_SECOND = 100, STACK_MAX = 400;
    /** in they go this long at most; an entry and what it knew is let go after this; ticks */
    static final int GO_MAX = 100, ENTRY_MAX = 1200;

    private static final int STACK = 0, GO = 1, OVER = 2;

    private static final class Op {
        AiRooms.@Nullable Plan plan;
        AiRooms.@Nullable Way way;
        int room = AiRooms.OUTSIDE, phase = STACK;
        long made, phaseAt, nextPlan, lost;
        long[] slots = {}, inner = {};
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
    static @Nullable Vec3 step(AiBot bot, ServerLevel level, TargetMemory.Contact c, long tick) {
        Map<Long, Op> ops = OPS.computeIfAbsent(level, l -> new HashMap<>());
        long key = (long) AiSquads.team(bot).hashCode() << 32 | c.id & 0xFFFFFFFFL, lost = cell(c.lostX, c.lostY, c.lostZ);
        Op op = ops.get(key);
        boolean moved = op != null && lost != op.lost && (op.plan == null || op.plan.room(lost) != op.room);
        if (op == null || tick - op.made > ENTRY_MAX || moved && tick >= op.nextPlan && op.phase != GO) {
            if (ops.size() > 64) ops.values().removeIf(o -> tick - o.made > ENTRY_MAX);
            op = make(level, bot, lost, tick);
            ops.put(key, op);
        }
        AiRooms.Plan plan = op.plan;
        if (plan == null || op.way == null) return null;
        advance(op, tick);
        if (op.phase == OVER || op.done.contains(bot)) return null;
        int i = op.members.indexOf(bot);
        if (i < 0) {
            // in the room already, or no place beside the way in left, or the others are past waiting: as before
            if (op.phase != STACK || op.members.size() >= op.slots.length || plan.room(cell(bot.mob.getX(), bot.mob.getY(), bot.mob.getZ())) == op.room) return null;
            i = op.members.size();
            op.members.add(bot);
        }
        if (op.phase == STACK) go(bot, op.slots[i], 0.35);
        else {
            long to = op.inner.length > 0 ? op.inner[i % op.inner.length] : plan.mid[op.room];
            go(bot, to, 0.5);
            if (bot.mover.arrived() || bot.mover.failed()) op.done.add(bot);
        }
        return op.door;
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
        op.inner = w.beside(room);
        // looked at from beside it: the opening's cell at the far end from the room
        op.door = mid(room == w.b ? w.cells[0] : w.cells[w.cells.length - 1], 1.1);
        return op;
    }

    /** the entry's next part, when its time has come (whoever of it thinks first this tick) */
    private static void advance(Op op, long tick) {
        op.members.removeIf(b -> !AiSquads.live(b));
        if (op.phase == STACK) {
            if (op.members.isEmpty()) {
                // none on its way yet (or all down): the wait starts with the first
                op.phaseAt = tick;
                return;
            }
            boolean all = true;
            for (int i = 0; i < op.members.size(); i++) {
                AiBot b = op.members.get(i);
                all &= b.mover.goalIs(op.slots[i]) && b.mover.arrived();
            }
            long waited = tick - op.phaseAt;
            if (!(all && (op.members.size() >= Math.min(2, op.slots.length) || waited >= WAIT_SECOND) || waited >= STACK_MAX)) return;
            entries++;
            op.phase = GO;
            op.phaseAt = tick;
        } else if (op.phase == GO && (op.done.containsAll(op.members) || tick - op.phaseAt > GO_MAX)) op.phase = OVER;
    }
}
