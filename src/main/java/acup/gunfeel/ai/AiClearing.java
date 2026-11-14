package acup.gunfeel.ai;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ReaimTimes;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Looking for a target lost in a building, room after room (stage 4.5d). The ones of a side that go after it share
 * one list of the building's rooms (AiRooms): a room is checked when every cell of it has been looked at by one of
 * them (or walked to), and no one goes back into it; the two that go after it take a room together, then the next. A
 * soldier goes for the cell of its room nearest to it that no one has seen yet, so it looks in from the doorway
 * first and steps in for the corners. Into a room none of them is in yet, through a doorway, they go as into the
 * first one (AiEntry, with aiEntry on): beside the opening on the near side, then in together, each to its own side.
 * With every room checked they search as before, outside the building.
 */
public final class AiClearing {
    /** the server setting: AI soldiers search a building room after room */
    public static final String KEY = "aiClearing";
    /** off only for the stands' "before" */
    static boolean on = true;
    /** the stands: sweeps begun, rooms checked */
    public static int sweeps, checks, stacks;
    /** off only for the stands' "before": no stacking up beside a doorway inside the building */
    static boolean stack = true;

    static final long NONE = Long.MIN_VALUE;
    /** the most that take one room (no more than AiSquads.HUNTERS go after one target, so the two go room after room together) */
    static final int PAIR = 2;
    /** the building is read again for a side and a target this often at most; a sweep is let go after this; ticks */
    static final int AGAIN = 100, SWEEP_MAX = 2400;
    /** cells of a room a soldier looks at in one thought */
    static final int RAYS = 16;
    /** a cell this near is seen whichever way the soldier looks, blocks */
    static final double NEXT = 1.5;
    /** a room's worth in blocks of way: one more room to go through, a room one of its side is in already */
    static final double HOP = 12, JOIN = 6;

    private static final int STACK = 0, GO = 1, OVER = 2;

    /** the going into one room through a doorway: who goes, their places beside it out and in, what they look at */
    private static final class Entry {
        int phase = STACK;
        long phaseAt;
        long[] slots = {}, inner = {};
        final java.util.List<AiBot> members = new java.util.ArrayList<>(2), done = new java.util.ArrayList<>(2);
        Vec3 door = Vec3.ZERO;
    }

    private static final class Sweep {
        AiRooms.@Nullable Plan plan;
        long made, nextPlan, lost;
        /** per room: its cells, those not seen yet first; how many are not; checked */
        long[][] cells = {};
        int[] left = {};
        boolean[] checked = {};
        /** per room: one of the side has been in it; the going into it (null: none yet, or not through a doorway) */
        boolean[] entered = {};
        @Nullable Entry[] entries = {};
        /** per room: where the next look goes on from; rooms checked so far */
        int[] at = {};
        int done;
        /** who has which room, where it goes in it, how many rooms were checked when it last had no room to take */
        final Map<AiBot, Integer> rooms = new IdentityHashMap<>();
        final Map<AiBot, Long> goals = new IdentityHashMap<>();
        final Map<AiBot, Integer> told = new IdentityHashMap<>();
    }

    private static final Map<ServerLevel, Map<Long, Sweep>> SWEEPS = new WeakHashMap<>();

    private AiClearing() {
    }

    static boolean on() {
        return on && ReaimTimes.flag(KEY, true);
    }

    public static void forget(ServerLevel level) {
        SWEEPS.remove(level);
    }

    private static long key(AiBot bot, int target) {
        return (long) AiSquads.team(bot).hashCode() << 32 | target & 0xFFFFFFFFL;
    }

    private static long cell(double x, double y, double z) {
        return BlockPos.asLong(Mth.floor(x), Mth.floor(y + 0.2), Mth.floor(z));
    }

    /**
     * A soldier that goes after a lost target (AiSoldier.search), this tick: the cell it goes to and looks at, or
     * NONE: it searches as before (no building there, every room checked, the rooms left have their two). The cells
     * of the rooms checked go to its own cleared set, so the search as before does not walk them again.
     */
    static long step(AiBot bot, ServerLevel level, TargetMemory.Contact c, LongOpenHashSet cleared, long tick) {
        try {
            return sweep(bot, level, c, cleared, tick);
        } catch (RuntimeException e) {
            // a fault in a sweep does not stop the server: the sweep is put aside (one with no building, none made
            // again for a while), the soldier searches as before; a line in the debug log
            long lost = cell(c.lostX, c.lostY, c.lostZ);
            AiRooms.fault("searching a building", lost, e);
            Sweep none = new Sweep();
            none.made = tick;
            none.nextPlan = tick + AGAIN;
            none.lost = lost;
            SWEEPS.computeIfAbsent(level, l -> new HashMap<>()).put(key(bot, c.id), none);
            return NONE;
        }
    }

    private static long sweep(AiBot bot, ServerLevel level, TargetMemory.Contact c, LongOpenHashSet cleared, long tick) {
        Map<Long, Sweep> all = SWEEPS.computeIfAbsent(level, l -> new HashMap<>());
        long key = key(bot, c.id), lost = cell(c.lostX, c.lostY, c.lostZ);
        Sweep s = all.get(key);
        // seen again since the sweep began: where it has been looked for tells nothing now. No building where the one
        // that began it lost the target: there may be one where this one did
        boolean stale = s != null && (c.lostTick > s.made || s.plan == null && lost != s.lost && tick >= s.nextPlan);
        if (s == null || stale || tick - s.made > SWEEP_MAX) {
            if (all.size() > 64) all.values().removeIf(o -> tick - o.made > SWEEP_MAX);
            s = make(level, c, lost, tick);
            all.put(key, s);
        }
        AiRooms.Plan plan = s.plan;
        if (plan == null) return NONE;
        Map<AiBot, Integer> has = s.rooms;
        has.keySet().removeIf(b -> b != bot && !sweeps(b, c.id));
        s.goals.keySet().removeIf(b -> !has.containsKey(b));
        s.told.keySet().removeIf(b -> b != bot && !AiSquads.live(b));

        long here = cell(bot.mob.getX(), bot.mob.getY(), bot.mob.getZ());
        int in = plan.room(here);
        Integer mine = s.rooms.get(bot);
        // what it sees of the room it is in and of its own
        Vec3 eye = bot.mob.getEyePosition();
        VoxelRay.Source voxels = AiBots.voxels(level);
        Vec3 view = bot.mob.getViewVector(1F);
        double cone = Math.cos(Math.toRadians(bot.profile.fov() / 2));
        if (in >= 0) {
            s.entered[in] = true;
            look(s, in, eye, view, cone, voxels);
        }
        if (mine != null && mine != in) look(s, mine, eye, view, cone, voxels);
        // the cell it went to, reached (or not to be reached): looked at
        Long goal = s.goals.get(bot);
        if (goal != null && mine != null && bot.mover.goalIs(goal) && (bot.mover.arrived() || bot.mover.failed())) seen(s, mine, goal);

        if (mine == null || s.checked[mine]) {
            s.rooms.remove(bot);
            s.goals.remove(bot);
            mine = pick(s, plan, bot, in);
            if (mine == null) {
                // the rooms checked are not walked again by the search as before
                if (s.told.getOrDefault(bot, -1) != s.done) {
                    s.told.put(bot, s.done);
                    for (int r = 0; r < s.cells.length; r++) if (s.checked[r]) for (long cell : s.cells[r]) cleared.add(cell);
                }
                return NONE;
            }
            s.rooms.put(bot, mine);
            goal = null;
        }
        long place = enter(s, plan, bot, mine, in, tick);
        if (place != NONE) {
            s.goals.remove(bot);
            return place;
        }
        // the nearest cell of its room no one has seen; the one it goes to already, while no one has
        long[] cells = s.cells[mine];
        boolean keep = false;
        if (goal != null) for (int i = 0; i < s.left[mine] && !keep; i++) keep = cells[i] == goal;
        if (!keep) {
            double best = Double.MAX_VALUE;
            for (int i = 0; i < s.left[mine]; i++) {
                double dx = BlockPos.getX(cells[i]) + 0.5 - bot.mob.getX(), dy = BlockPos.getY(cells[i]) - bot.mob.getY(), dz = BlockPos.getZ(cells[i]) + 0.5 - bot.mob.getZ();
                double d = dx * dx + dy * dy * 4 + dz * dz;
                if (d < best) {
                    best = d;
                    goal = cells[i];
                }
            }
            s.goals.put(bot, goal);
        }
        return goal;
    }

    /**
     * Into a room none of its side has been in, from the room next to it through a doorway: its place beside the
     * opening while they gather, its place inside as they go in; NONE: it goes on as it would (no such way, no place
     * left beside it, they are in).
     */
    private static long enter(Sweep s, AiRooms.Plan plan, AiBot bot, int room, int in, long tick) {
        Entry e = s.entries[room];
        if (e == null) {
            if (!stack || !AiEntry.on() || s.entered[room] || in == room) return NONE;
            AiRooms.Way way = null;
            for (AiRooms.Way w : plan.waysOf(room))
                if (w.other(room) == in && w.width <= AiEntry.WIDE && w.cells.length > 0 && w.beside(in).length > 0) {
                    way = w;
                    break;
                }
            if (way == null) return NONE;
            e = s.entries[room] = new Entry();
            e.phaseAt = tick;
            e.slots = way.beside(in);
            e.inner = way.beside(room);
            long far = room == way.b ? way.cells[0] : way.cells[way.cells.length - 1];
            e.door = new Vec3(BlockPos.getX(far) + 0.5, BlockPos.getY(far) + 1.1, BlockPos.getZ(far) + 0.5);
        }
        if (e.phase == OVER) return NONE;
        final Entry entry = e;
        e.members.removeIf(b -> b != bot && (!AiSquads.live(b) || !Integer.valueOf(room).equals(s.rooms.get(b))));
        if (e.phase == STACK) {
            // one of them in the room some other way: no more waiting at this one
            if (s.entered[room]) {
                e.phase = OVER;
                return NONE;
            }
            boolean all = !e.members.isEmpty();
            for (int i = 0; i < e.members.size(); i++) {
                AiBot b = e.members.get(i);
                all &= b.mover.goalIs(e.slots[i]) && b.mover.arrived();
            }
            if (e.members.isEmpty()) e.phaseAt = tick;
            long waited = tick - e.phaseAt;
            if (all && (e.members.size() >= Math.min(PAIR, e.slots.length) || waited >= AiEntry.WAIT_SECOND) || waited >= AiEntry.STACK_MAX) {
                stacks++;
                e.phase = GO;
                e.phaseAt = tick;
            }
        } else if (entry.done.containsAll(e.members) || tick - e.phaseAt > AiEntry.GO_MAX) {
            e.phase = OVER;
            return NONE;
        }
        if (e.done.contains(bot)) return NONE;
        int i = e.members.indexOf(bot);
        if (i < 0) {
            if (e.phase != STACK || e.members.size() >= e.slots.length) return NONE;
            i = e.members.size();
            e.members.add(bot);
        }
        long to = e.phase == STACK ? e.slots[i] : e.inner.length > 0 ? e.inner[i % e.inner.length] : plan.mid[room];
        if (!bot.mover.goalIs(to)) {
            bot.mover.costs(PathSearch.Cost.NONE);
            bot.mover.goTo(to, e.phase == STACK ? 0.35 : 0.5, AiSoldier.RUN);
        } else if (e.phase == GO && (bot.mover.arrived() || bot.mover.failed())) {
            e.done.add(bot);
            return NONE;
        }
        return to;
    }

    /** what a soldier going into a room so (step gave it its place) looks at: the doorway; null: where it goes */
    static @Nullable Vec3 door(AiBot bot, ServerLevel level, TargetMemory.Contact c) {
        Map<Long, Sweep> all = SWEEPS.get(level);
        Sweep s = all == null ? null : all.get(key(bot, c.id));
        Integer mine = s == null ? null : s.rooms.get(bot);
        Entry e = mine == null ? null : s.entries[mine];
        return e != null && e.phase == STACK && e.members.contains(bot) ? e.door : null;
    }

    private static boolean sweeps(AiBot b, int target) {
        return AiSquads.live(b) && b.memory.focusId == target && b.brain instanceof AiSoldier o && o.mode() == AiSoldier.Mode.SEARCH;
    }

    private static int taken(Sweep s, int room, int target) {
        int n = 0;
        for (var e : s.rooms.entrySet()) if (e.getValue() == room && sweeps(e.getKey(), target)) n++;
        return n;
    }

    /** the building where the target was lost: there, or a step or two on the way it was going (lost in the doorway, or just before it) */
    private static Sweep make(ServerLevel level, TargetMemory.Contact c, long lost, long tick) {
        Sweep s = new Sweep();
        s.made = tick;
        s.nextPlan = tick + AGAIN;
        s.lost = lost;
        TacticalMap map = TacticalMap.of(level);
        AiRooms.Plan plan = AiRooms.of(map, lost);
        double speed = Math.sqrt(c.vx * c.vx + c.vz * c.vz);
        for (int k = 1; k <= 3 && (plan == null || plan.rooms() == 0) && speed >= ExitSpots.MOVING; k++)
            plan = AiRooms.of(map, cell(c.lostX + c.vx / speed * k, c.lostY, c.lostZ + c.vz / speed * k));
        if (plan == null || plan.rooms() == 0) return s;
        s.plan = plan;
        s.cells = plan.cells();
        s.left = new int[s.cells.length];
        s.at = new int[s.cells.length];
        s.checked = new boolean[s.cells.length];
        s.entered = new boolean[s.cells.length];
        s.entries = new Entry[s.cells.length];
        for (int r = 0; r < s.cells.length; r++) s.left[r] = s.cells[r].length;
        sweeps++;
        return s;
    }

    /**
     * RAYS of the room's cells not seen yet, in turn: one before the soldier's eyes (within its field of view, or
     * right next to it) and in plain sight (a target standing in it: its chest) is seen.
     */
    private static void look(Sweep s, int room, Vec3 eye, Vec3 view, double cone, VoxelRay.Source voxels) {
        long[] cells = s.cells[room];
        for (int n = Math.min(RAYS, s.left[room]); n > 0 && s.left[room] > 0; n--) {
            int i = (s.at[room]++ & Integer.MAX_VALUE) % s.left[room];
            long c = cells[i];
            double x = BlockPos.getX(c) + 0.5, y = BlockPos.getY(c) + 1.0, z = BlockPos.getZ(c) + 0.5;
            double dx = x - eye.x, dy = y - eye.y, dz = z - eye.z, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > NEXT && (dx * view.x + dy * view.y + dz * view.z) / d < cone) continue;
            if (!VoxelRay.clear(voxels, eye.x, eye.y, eye.z, x, y, z, Voxel.SIGHT)) continue;
            drop(s, room, i);
        }
    }

    private static void seen(Sweep s, int room, long cell) {
        long[] cells = s.cells[room];
        for (int i = 0; i < s.left[room]; i++)
            if (cells[i] == cell) {
                drop(s, room, i);
                return;
            }
    }

    /** cell i of the room is seen: behind those that are not; the last one makes the room checked */
    private static void drop(Sweep s, int room, int i) {
        long[] cells = s.cells[room];
        int last = --s.left[room];
        long c = cells[i];
        cells[i] = cells[last];
        cells[last] = c;
        if (last == 0 && !s.checked[room]) {
            s.checked[room] = true;
            s.done++;
            checks++;
        }
    }

    /**
     * The room it takes next: not checked, with fewer than two in it; the nearest by the rooms to go through from
     * where it is and the way to its middle, one of its side there already before an empty one (two to a room).
     */
    private static @Nullable Integer pick(Sweep s, AiRooms.Plan plan, AiBot bot, int in) {
        int rooms = plan.rooms();
        // rooms to go through, from the room it is in, or from outside
        int[] hops = new int[rooms];
        java.util.Arrays.fill(hops, Integer.MAX_VALUE);
        int[] queue = new int[rooms];
        int head = 0, tail = 0;
        if (in >= 0) {
            hops[in] = 0;
            queue[tail++] = in;
        } else for (AiRooms.Way w : plan.ways)
            if (w.b == AiRooms.OUTSIDE && hops[w.a] != 0) {
                hops[w.a] = 0;
                queue[tail++] = w.a;
            }
        while (head < tail) {
            int r = queue[head++];
            for (AiRooms.Way w : plan.ways) {
                if (w.a != r && w.b != r) continue;
                int o = w.other(r);
                if (o < 0 || hops[o] != Integer.MAX_VALUE) continue;
                hops[o] = hops[r] + 1;
                queue[tail++] = o;
            }
        }
        Integer best = null;
        double least = Double.MAX_VALUE;
        for (int r = 0; r < rooms; r++) {
            if (s.checked[r] || hops[r] == Integer.MAX_VALUE) continue;
            int taken = taken(s, r, bot.memory.focusId);
            if (taken >= PAIR) continue;
            long mid = plan.mid[r];
            double dx = BlockPos.getX(mid) + 0.5 - bot.mob.getX(), dz = BlockPos.getZ(mid) + 0.5 - bot.mob.getZ();
            double d = Math.sqrt(dx * dx + dz * dz) + hops[r] * HOP - (taken > 0 ? JOIN : 0);
            if (d < least) {
                least = d;
                best = r;
            }
        }
        return best;
    }
}
