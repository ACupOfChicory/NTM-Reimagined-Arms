package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.LongPredicate;

import org.jspecify.annotations.Nullable;

import com.hbm.items.weapon.grenade.ItemGrenadeFilling.EnumGrenadeFilling;

import acup.gunfeel.ReaimTimes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.phys.Vec3;

/**
 * Into a building under the ground down a shaft (stage 6, after T8): a foe lost well under a squad, its way there down a
 * ladder. The squad gathers at the hatch; one of it stays by the hatch as its security (its base of fire if it has one)
 * the while the others go down, for on the ladder a soldier can do nothing; a grenade goes down first only when it
 * gets to the foot of the shaft before its fuse is out (in a deep shaft it bursts in the air), and none of its own is in
 * its reach unshielded; then they go down one after another, the next once the one before is SPACING rungs down (not
 * so far apart that the first is alone at the foot long), the last one down holding the shaft's way out till the next
 * is down (then it goes on: AiClearing, room after room); the security last. The foe is kept looked for the while (TargetMemory.keep). Server thread, from AiSoldier's search.
 */
final class AiHatch {
    /** how far up or down a ladder its top or foot is looked for, blocks */
    static final int LADDER_REACH = 12;

    /** a ladder's rung, or a trapdoor open over one (the game climbs both) */
    private static boolean rung(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.is(BlockTags.CLIMBABLE) || s.getBlock() instanceof TrapDoorBlock && s.getValue(TrapDoorBlock.OPEN) && level.getBlockState(p.below()).is(BlockTags.CLIMBABLE);
    }

    /**
     * Where one on a ladder steps off it: at its top a cell to stand in beside where the rungs end (out of a hatch on
     * to the ground), else, the way up shut, its foot. MIN_VALUE: none known (the map not read there yet)
     */
    static long offLadder(ServerLevel level, Mob mob) {
        return offLadder(level, mob, 0, LADDER_REACH);
    }

    /**
     * The same, the way given: up (1: its top only), down (-1: its foot only), or either (0: its top first), within
     * reach blocks. The map asked for at the top when it has no cells there yet (a long shaft's top is out of the map
     * its foot was read in)
     */
    static long offLadder(ServerLevel level, Mob mob, int way, int reach) {
        TacticalMap map = TacticalMap.of(level);
        int x = mob.getBlockX(), z = mob.getBlockZ(), y0 = mob.getBlockY();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        if (way >= 0) {
            int top = y0;
            while (top < y0 + reach && rung(level, p.set(x, top, z))) top++;
            // (the rungs' end within reach only: a rung reach up is no top, though a cell)
            if ((top > y0 || !rung(level, p.set(x, y0, z))) && !rung(level, p.set(x, top, z))) {
                long out = exit(map, x, top, z);
                if (out != Long.MIN_VALUE) return out;
                if (top - y0 > LADDER_REACH) map.want(x, top, z, 8, 4);
            }
        }
        if (way <= 0)
            for (int y = y0; y > y0 - reach && y > level.getMinY(); y--) {
                if (!rung(level, p.set(x, y - 1, z)) && walkable(map, x, y, z)) {
                    // a cell beside its floor: the column's own is where the next comes down (one stood in it, the one
                    // over it was sent there and hung on the ladder over it for good)
                    long aside = besideFoot(BlockPos.asLong(x, y, z), c -> walkable(map, BlockPos.getX(c), BlockPos.getY(c), BlockPos.getZ(c)), Long.MIN_VALUE);
                    return aside != Long.MIN_VALUE ? aside : BlockPos.asLong(x, y, z);
                }
            }
        return Long.MIN_VALUE;
    }

    /**
     * A shaft's floor under a cell in its column: down its rungs to the cell over no rung, one to stand in (a path to a
     * foe beside its foot ends a rung or two up the column, its goal near enough across and up); the cell itself if
     * none within LADDER_REACH
     */
    static long floor(long foot, LongPredicate rung, LongPredicate stand) {
        int x = BlockPos.getX(foot), z = BlockPos.getZ(foot);
        for (int y = BlockPos.getY(foot); y > BlockPos.getY(foot) - LADDER_REACH; y--) {
            if (!rung.test(BlockPos.asLong(x, y, z))) break;
            if (!rung.test(BlockPos.asLong(x, y - 1, z))) return stand.test(BlockPos.asLong(x, y, z)) ? BlockPos.asLong(x, y, z) : foot;
        }
        return foot;
    }

    /**
     * A cell to stand in beside a shaft's floor, off its column: on the floor's height first, else a step up or down;
     * the nearest across to toward of them (MIN_VALUE: any, the sides in turn). MIN_VALUE: none
     */
    static long besideFoot(long floor, LongPredicate stand, long toward) {
        int x = BlockPos.getX(floor), y = BlockPos.getY(floor), z = BlockPos.getZ(floor);
        int[][] side = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        long best = Long.MIN_VALUE;
        double bestD = Double.MAX_VALUE;
        for (int dy : new int[] {0, 1, -1}) {
            for (int[] d : side) {
                long c = BlockPos.asLong(x + d[0], y + dy, z + d[1]);
                if (!stand.test(c)) continue;
                double e = toward == Long.MIN_VALUE ? 0 : Math.hypot(BlockPos.getX(c) - BlockPos.getX(toward), BlockPos.getZ(c) - BlockPos.getZ(toward));
                if (e < bestD) {
                    bestD = e;
                    best = c;
                }
            }
            if (best != Long.MIN_VALUE) return best;
        }
        return Long.MIN_VALUE;
    }

    /** a cell to stand in where a ladder's rungs end at y (its top), or beside it; MIN_VALUE: none */
    private static long exit(TacticalMap map, int x, int y, int z) {
        if (walkable(map, x, y, z)) return BlockPos.asLong(x, y, z);
        int[][] side = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : side) if (walkable(map, x + d[0], y, z + d[1])) return BlockPos.asLong(x + d[0], y, z + d[1]);
        return Long.MIN_VALUE;
    }

    /** a ladder up this long at most is looked for its top, blocks (NTM's bunker shaft is sixty) */
    static final int UP_REACH = 96;

    /**
     * A foe lost up a shaft: the shaft's way out at its top, where one at its foot goes first (the search from there on,
     * as on any ground; a ladder UP_NEAR off across: its foot first), or MIN_VALUE. Up a shaft: a ladder's column at its
     * feet or a step beside, the bot DEEP under its
     * top at least, the place it was lost at the top's height (a little under it to a few over) and this near the top
     * across, FOE_NEAR: the way to that place is up this shaft
     */
    static long wayUp(ServerLevel level, AiBot bot, TargetMemory.Contact k) {
        Mob m = bot.mob;
        if (k.lostY < m.getY() + DEEP) return Long.MIN_VALUE;
        int fx = m.getBlockX(), fy = m.getBlockY(), fz = m.getBlockZ();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        // nearest first: at its feet or a step beside (up it), else UP_NEAR off across (to its foot first)
        for (int d = 0; d <= UP_NEAR; d++)
            for (int dx = -d; dx <= d; dx++)
                for (int dz = -d; dz <= d; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                    long w = wayUp(level, k, fx + dx, fy, fz + dz, d <= 1, p);
                    if (w != Long.MIN_VALUE) return w;
                }
        return Long.MIN_VALUE;
    }

    /** a ladder looked for this far across from one at the foot of a shaft a foe was lost up, blocks */
    static final int UP_NEAR = 8;

    /** the way up the ladder at x z (its foot about y): its way out at the top when at it, else its foot's cell */
    private static long wayUp(ServerLevel level, TargetMemory.Contact k, int x, int fy, int z, boolean at, BlockPos.MutableBlockPos p) {
        if (!rung(level, p.set(x, fy, z)) && !rung(level, p.set(x, fy + 1, z))) return Long.MIN_VALUE;
        int top = fy;
        while (top < fy + UP_REACH && (rung(level, p.set(x, top, z)) || top == fy)) top++;
        // the rungs end at top (the hatch's block, shut or open, or the ground's air): up there by the lost place
        if (top - fy < DEEP || k.lostY < top - 2 || k.lostY > top + 6) return Long.MIN_VALUE;
        double ox = k.lostX - x - 0.5, oz = k.lostZ - z - 0.5;
        if (ox * ox + oz * oz > FOE_NEAR * FOE_NEAR) return Long.MIN_VALUE;
        TacticalMap map = TacticalMap.of(level);
        // not at it yet: to its foot first, the rung's cell at the bot's height or a cell beside it
        if (!at) return walkable(map, x, fy, z) ? BlockPos.asLong(x, fy, z) : exit(map, x, fy, z);
        long out = exit(map, x, top, z);
        if (out == Long.MIN_VALUE) {
            // its top not read yet: the cell over the hatch, read now and gone for in a while
            map.want(x, top, z, 8, 4);
            out = exit(map, x, top + 1, z);
        }
        return out;
    }

    private static boolean walkable(TacticalMap map, int x, int y, int z) {
        SectionCells c = map.cellsAt(x, y, z);
        return c != null && c.find(x & 15, y & 15, z & 15) >= 0;
    }

    public static final String KEY = "aiHatches";
    /** off only for the stands' "before" */
    static boolean on = true;
    /** a foe this far under it at least, blocks; a ladder of this many rungs at least on the way */
    static final int DEEP = 4, RUNGS = 3;
    /** a side looks for a way down this often at most, ticks; again after one is given up this long after */
    static final int BEGIN_EVERY = 40, AGAIN = 400;
    /** its squad this near it goes with it, blocks; at the hatch: this near the rim; gathered this long at most, ticks */
    static final double NEAR = 40, RIM = 2.5, GATHERED = RIM;
    static final int GATHER_MAX = 400, FRAG_MAX = 200, OP_MAX = 9000;
    /** none of it thought of its way down this long (all of it fighting): given up, ticks */
    static final int STALE = 200;
    /** a foe lost this near the shaft's foot across is one down it, blocks */
    static final double FOE_NEAR = 32;
    /** a grenade down: it gets to the foot so many ticks before its fuse is out at least; thrown down at this share of a throw */
    static final int FUSE_LEFT = 10;
    /** the others wait this far off the shaft while it goes down, blocks */
    static final int BACK = 4;
    static final float DROP_FORCE = 0.4F;
    /** a soldier down the ladder this long at most (and so many ticks a rung more), ticks */
    static final int CLIMB_BASE = 200, CLIMB_RUNG = 12;
    /** the next one starts down once the one before is this many rungs under the top, blocks */
    static final int SPACING = 4;

    /** the stands: ways down begun, grenades down, none thrown (a deep shaft, its own in reach, none with one), soldiers down */
    public static int begun, frags, noFrag, deepNoFrag, down;
    /** the stands: shafts found held at their foot (by why: one of it hurt or down on the ladder from under, a foe seen
     * or heard at the foot, the first down put down there), smoke dropped down, ways down held off (none goes down) */
    public static final int[] heldWhy = new int[3];
    public static int held, smokedDown, contained, smokeFirst;
    /** the stands: the smoker's ticks on its way to drop it, at the rim, at it with one of its own over the hatch, the hatch shut */
    public static final int[] smokerTicks = new int[4];
    /** the stands: looks for a way down that found none: the foe not under it, no cell, no way there, no shaft on it */
    public static final int[] none = new int[4];
    /** the stands: each look for a way down that found no way there, as words (trace on) */
    public static boolean trace;
    public static final List<String> noWay = new ArrayList<>();
    /** the stands: ticks the thrower waited at the rim for its own side out of the grenade's reach, ticks it was not at the rim */
    public static int unsafe, notAtRim;

    /** a shaft on a way: the cell before it (its rim), its top and foot rungs, the cell its way goes on to at its foot, the hatch over it */
    record Shaft(long rim, long top, long foot, long out, @Nullable BlockPos hatch, int rungs, long back) {
        Vec3 mid() {
            return new Vec3(BlockPos.getX(top) + 0.5, BlockPos.getY(top) + 1.5, BlockPos.getZ(top) + 0.5);
        }

        /** where the one holding its way out looks: on along that way */
        Vec3 onward() {
            int dx = BlockPos.getX(out) - BlockPos.getX(foot), dz = BlockPos.getZ(out) - BlockPos.getZ(foot);
            return new Vec3(BlockPos.getX(out) + 0.5 + dx * 4, BlockPos.getY(out) + 1.5, BlockPos.getZ(out) + 0.5 + dz * 4);
        }
    }

    private static final int GATHER = 0, FRAG = 1, DOWN = 2, OVER = 3, HELD = 4, CONTAIN = 5, BYPASS = 6;
    /**
     * Another way to the foe than down this shaft (held, or too deep for a frag): a way found with BYPASS_COST on each
     * cell of the shaft's column, none of them on it, at most BYPASS_TIMES as long as the way down it or BYPASS_MORE
     * cells more. Then the squad's paths keep off the column for BYPASS_MEMORY (it searches on round), and none goes
     * down it; shot at on the way round after BYPASS_GRACE, the way round is given up for the shaft again, once an op.
     */
    static final float BYPASS_COST = 1000F;
    static final int BYPASS_TIMES = 3, BYPASS_MORE = 60, BYPASS_MEMORY = 2400, BYPASS_GRACE = 100;
    /**
     * the way round's search, nodes at most: the mover's MAX_NODES was all spent on the ground round the hatch (a way
     * round sixty blocks off is a wide fan of cells), and the way down the shaft came back for want of more. Once an op
     */
    static final int BYPASS_NODES = 4 * AiMover.MAX_NODES;
    /** the stands: ways round found (the shaft too deep, held), none (no way, too long), given up (shot at on it), begins put off by one */
    public static int bypassDeep, bypassHeld, bypassDropped, bypassKept, bypassMissing, bypassEntry;
    /** round by another way this near where the foe was lost, a room it is in with a way in is gone into by AiEntry, blocks */
    static final double BYPASS_ENTRY = 14;
    /** the map about a way round not built yet: looked for again every BYPASS_AGAIN ticks, at most BYPASS_WAIT from the first look */
    static final int BYPASS_AGAIN = 20, BYPASS_WAIT = 400;
    /** one of it this near where the foe was lost, the way round is over: it searches from there, blocks */
    static final double BYPASS_NEAR = 6;
    /** map sections wanted at a time for a way round (the mover wants 8) */
    static final int BYPASS_WANT = 32;
    public static final int[] bypassNone = new int[2];
    /** the stands: the last way round looked for, told */
    static String bypassLast = "-";

    /** a team's way round a shaft: its column, till when, since when */
    private record Bypass(long top, int x, int z, int y0, int y1, long until, long made) {
        boolean on(int cx, int cy, int cz) {
            return cx == x && cz == z && cy >= y0 && cy <= y1;
        }
    }
    /**
     * Held at its foot (a foe there fires up the shaft at one on the ladder): none more sent; smoke down it first if one
     * of it has any (the cloud at the foot blinds them, then down one after another close, FAST_SPACING), else, or held
     * again under the smoke, none goes down (CONTAIN): two watch the hatch from beside it, the others off it, CONTAIN_MAX
     * with nothing more from the foot and they try again. Known only by its own senses. Ticks; blocks
     */
    /** a foe or a shot this near the foot across is at it, blocks; a loud sound this near in time to its own frag's burst is that, ticks */
    static final int FOOT_NEAR = 8, OWN_BLAST = 5;
    /** a shaft that cost one of a squad going down: none down it again for this long, ticks (five minutes) */
    static final int COSTLY_MEMORY = 6000;
    static final int SMOKE_MAX = 200, SMOKE_SETTLE = 30, CONTAIN_MAX = 1200, HELD_MEMORY = 1200, HEARD_RECENT = 40, FAST_SPACING = 2;

    private static final class Op {
        final String team;
        final Shaft shaft;
        final List<AiBot> members;
        final @Nullable AiBot guard;
        final long made;
        /** when one of it last thought of it */
        long lastStep;
        int phase = GATHER, fragId = -1;
        long phaseAt;
        /** when each was sent down the ladder */
        final Map<AiBot, Long> sent = new HashMap<>();
        @Nullable AiBot thrower, holder;
        final Set<AiBot> downs = new HashSet<>();
        /** sent and called back up the ladder (the foot held, it was over the shaft's middle) */
        final Set<AiBot> backing = new HashSet<>();
        /** the smoke's: dropped down this shaft already, by whom, when; the last tick a sign of the foot held came */
        boolean smoked;
        @Nullable AiBot smoker;
        /** the smoke goes down first (none held yet): the shaft too deep for a frag */
        boolean first;
        long blastAt = Long.MIN_VALUE / 2, smokeAt = Long.MIN_VALUE, lastSign = Long.MIN_VALUE, signTick = Long.MIN_VALUE;
        int spacing = SPACING;
        /** where the foe was lost, and the way there down the shaft's length in cells (a way round is weighed by it); a way round looked for */
        long goal = Long.MIN_VALUE;
        int through;
        boolean bypassTried;
        /** the map about a way round not built yet: the first look, and the next */
        long bypassFirst = Long.MIN_VALUE, bypassNext = Long.MIN_VALUE;

        Op(String team, Shaft shaft, List<AiBot> members, @Nullable AiBot guard, long tick) {
            // (the others wait at shaft.back, BACK off the hatch: none stands over it when it is lifted)
            this.team = team;
            this.shaft = shaft;
            this.members = members;
            this.guard = guard;
            this.made = phaseAt = lastStep = tick;
        }

        List<AiBot> order() {
            List<AiBot> o = new ArrayList<>(members);
            o.remove(guard);
            return o;
        }
    }

    private static final class PerLevel {
        final Map<String, Op> ops = new HashMap<>();
        final Map<String, Long> next = new HashMap<>();
        /** shafts found held at their foot (by the top's cell), when last: a way down there begins held */
        final Map<Long, Long> held = new HashMap<>();
        /** of those, shafts that cost one going down, when: none goes down them again for COSTLY_MEMORY but under smoke */
        final Map<Long, Long> costly = new HashMap<>();
        /** each team's way round a shaft, and the shaft it gave a way round up on (none round it again) */
        final Map<String, Bypass> bypass = new HashMap<>();
        final Map<String, Long> switched = new HashMap<>();
    }

    private static final Map<ServerLevel, PerLevel> LEVELS = new WeakHashMap<>();

    private AiHatch() {
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

    /** the first run of RUNGS rungs at least straight down on a path, with a cell before it: {the cell before, its top, its foot, the cell after (its foot if none)}, or null */
    static long @Nullable [] run(long[] cells) {
        for (int i = 1; i + 1 < cells.length; i++) {
            long a = cells[i], b = cells[i + 1];
            if (BlockPos.getX(a) != BlockPos.getX(b) || BlockPos.getZ(a) != BlockPos.getZ(b) || BlockPos.getY(b) != BlockPos.getY(a) - 1) continue;
            int j = i + 1;
            while (j + 1 < cells.length && BlockPos.getX(cells[j + 1]) == BlockPos.getX(a) && BlockPos.getZ(cells[j + 1]) == BlockPos.getZ(a)
                    && BlockPos.getY(cells[j + 1]) < BlockPos.getY(cells[j]) && BlockPos.getY(cells[j + 1]) >= BlockPos.getY(cells[j]) - PathSearch.MAX_DROP) j++;
            // (a drop past rungs knocked out is still down the shaft: PathSearch.gapDown)
            if (BlockPos.getY(a) - BlockPos.getY(cells[j]) + 1 < RUNGS) {
                i = j;
                continue;
            }
            // the way on from its foot (none: the foe at the foot itself, the foot is the way out)
            return new long[] {cells[i - 1], a, cells[j], j + 1 < cells.length ? cells[j + 1] : cells[j]};
        }
        return null;
    }

    /** the first shaft on a path (run), its hatch: at its top cell (one shut is a floor walked onto) or over it, up to the rim's height */
    static @Nullable Shaft shaft(ServerLevel level, long[] cells, long goal) {
        long[] r = run(cells);
        if (r == null) return null;
        long rim = r[0], top = r[1];
        // the way come onto the shaft from straight over it (its hatch walked onto): the rim beside it instead, else
        // they gathered, dropped the smoke and watched from over the hatch, in the fire up the shaft
        if (BlockPos.getX(rim) == BlockPos.getX(top) && BlockPos.getZ(rim) == BlockPos.getZ(top)) rim = beside(level, rim, top);
        BlockPos hatch = null;
        for (int y = BlockPos.getY(top); y <= Math.max(BlockPos.getY(rim), BlockPos.getY(top) + 1); y++) {
            BlockPos p = new BlockPos(BlockPos.getX(top), y, BlockPos.getZ(top));
            if (level.getBlockState(p).getBlock() instanceof TrapDoorBlock) hatch = p;
        }
        // none down a hatch a hand cannot lift (an iron one), nor any while the AI opens no doors
        if (hatch != null && (!AiDoors.pass || !AiDoors.byHand(level.getBlockState(hatch)))) return null;
        long foot = r[2], out = r[3];
        // the way ending in the column (to a foe beside its foot): its foot the floor under it, its way out a cell beside
        // that (its foot a rung or two up, they hung over it short of it; and the one holding the way out stood in the
        // column, in the way of the next down)
        if (BlockPos.getX(out) == BlockPos.getX(foot) && BlockPos.getZ(out) == BlockPos.getZ(foot)) {
            TacticalMap map = TacticalMap.of(level);
            LongPredicate stand = c -> walkable(map, BlockPos.getX(c), BlockPos.getY(c), BlockPos.getZ(c));
            foot = floor(foot, c -> rung(level, BlockPos.of(c)), stand);
            long aside = besideFoot(foot, stand, goal);
            out = aside != Long.MIN_VALUE ? aside : foot;
        }
        return new Shaft(rim, top, foot, out, hatch, BlockPos.getY(top) - BlockPos.getY(foot) + 1, back(level, rim, top));
    }

    /**
     * One of its side's way down sent down the ladder, not at its foot yet: on down to it whatever it sees, firing at
     * a foe in sight (on a ladder there is no cover to take, and one that stops is shot off it); at the foot it is down
     * (it holds the shaft's way out, AiHatch.step). Whether it was.
     */
    static boolean onLadder(AiSoldier s, AiBot bot, ServerLevel level, long tick) {
        if (!on()) return false;
        PerLevel l = LEVELS.get(level);
        Op op = l == null ? null : l.ops.get(AiSquads.team(bot));
        if (op != null && op.backing.contains(bot) && bot.mob.onClimbable() && AiSquads.live(bot)) {
            // called back up: up it whatever it sees, as down (on a ladder there is no cover)
            s.moveFiring(bot, op.shaft.back, AiSoldier.RUN, op.shaft.mid(), tick, "back up the ladder: the shaft's foot is held", AiThoughts.Why.HATCH, RIM);
            return true;
        }
        // (held at the foot, one under the shaft's middle goes on down: back up is the longer way)
        if (op == null || op.phase != DOWN && op.phase != HELD && op.phase != CONTAIN && op.phase != BYPASS || !climbing(op, bot, tick)) return false;
        // (the foot held looked for here too: on the ladder they are in no search, and those up by the hatch may be
        // fighting down the shaft, so none of it may call step a while)
        if (op.phase == DOWN && op.signTick != tick) {
            op.signTick = tick;
            int why = sign(op, level, tick);
            if (why >= 0) {
                hold(op, why, level, tick);
                if (!climbing(op, bot, tick)) return false;
            }
        }
        Shaft sh = op.shaft;
        if (at(bot, sh.out, 1.0) || bot.mob.onGround() && bot.mob.getY() < BlockPos.getY(sh.foot) + 0.5) {
            down++;
            op.downs.add(bot);
            op.holder = bot;
            return false;
        }
        if (sh.hatch != null) AiDoors.open(level, bot.mob, sh.hatch);
        s.moveFiring(bot, sh.out, AiSoldier.RUN, sh.onward(), tick, "down the ladder of a shaft, one after another", AiThoughts.Why.HATCH, 0.6);
        return true;
    }

    /** sent down the ladder, not at its foot yet, not given up on */
    private static boolean climbing(Op op, AiBot b, long tick) {
        Long sent = op.sent.get(b);
        return sent != null && !op.downs.contains(b) && !op.backing.contains(b) && tick - sent <= CLIMB_BASE + CLIMB_RUNG * op.shaft.rungs;
    }

    private static boolean anyClimbing(Op op, long tick) {
        for (AiBot b : op.members) if (AiSquads.live(b) && climbing(op, b, tick)) return true;
        return false;
    }

    /** a foe lost out of sight down this shaft: under its top, this near its foot across */
    static boolean down(Shaft sh, TargetMemory.Contact k) {
        if (k.state == TargetMemory.State.VISIBLE) return false;
        double dx = k.lostX - BlockPos.getX(sh.foot) - 0.5, dz = k.lostZ - BlockPos.getZ(sh.foot) - 0.5;
        return k.lostY <= BlockPos.getY(sh.top) - DEEP && dx * dx + dz * dz <= FOE_NEAR * FOE_NEAR;
    }

    /** it looks for a foe down this shaft */
    private static boolean looksDown(AiBot b, Shaft sh) {
        for (TargetMemory.Contact k : b.memory.contacts()) if (down(sh, k)) return true;
        return false;
    }

    /** a cell to stand on beside the shaft's top about the rim's height (the rim itself if none) */
    private static long beside(ServerLevel level, long rim, long top) {
        TacticalMap map = TacticalMap.of(level);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            long c = PathSearch.snap(map, BlockPos.getX(top) + d[0] + 0.5, BlockPos.getY(rim), BlockPos.getZ(top) + d[1] + 0.5);
            if (c != Long.MIN_VALUE && (BlockPos.getX(c) != BlockPos.getX(top) || BlockPos.getZ(c) != BlockPos.getZ(top)) && Math.abs(BlockPos.getY(c) - BlockPos.getY(rim)) <= 1) return c;
        }
        return rim;
    }

    /** where the others wait while the grenade goes down: BACK blocks off the shaft out past its rim (the rim if no cell there) */
    private static long back(ServerLevel level, long rim, long top) {
        int dx = Integer.signum(BlockPos.getX(rim) - BlockPos.getX(top)), dz = Integer.signum(BlockPos.getZ(rim) - BlockPos.getZ(top));
        if (dx == 0 && dz == 0) return rim;
        TacticalMap map = TacticalMap.of(level);
        long c = PathSearch.snap(map, BlockPos.getX(top) + dx * BACK + 0.5, BlockPos.getY(rim), BlockPos.getZ(top) + dz * BACK + 0.5);
        return c == Long.MIN_VALUE ? rim : c;
    }

    /**
     * A soldier looking for a foe out of sight, this tick: where it looks when it is one of its side's going down a
     * shaft (it goes, waits, holds as its part is), or null (none: it searches as before).
     */
    static @Nullable Vec3 step(AiSoldier s, AiBot bot, ServerLevel level, TargetMemory.Contact c, long tick) {
        if (!on()) return null;
        PerLevel l = of(level);
        String team = AiSquads.team(bot);
        Op op = l.ops.get(team);
        if (op != null) {
            // over once none of it alive still looks for its foe (seen again: they fight; told it is down: forgotten),
            // or none of it thought of it a while (all of it in a fight)
            boolean live = false;
            // (one of it on the ladder now keeps it: on the way down it may see the foe and fight from there)
            for (AiBot b : op.members) if (AiSquads.live(b) && (looksDown(b, op.shaft) || climbing(op, b, tick))) live = true;
            if (op.phase == OVER || !live || tick - op.made > OP_MAX || tick - op.lastStep > STALE && !anyClimbing(op, tick)) {
                l.ops.remove(team);
                l.next.put(team, tick + (op.phase == OVER ? BEGIN_EVERY : AGAIN));
                op = null;
            }
        }
        if (op == null) {
            if (tick < l.next.getOrDefault(team, Long.MIN_VALUE) || (tick + bot.mob.getId()) % BEGIN_EVERY != 0) return null;
            l.next.put(team, tick + BEGIN_EVERY);
            op = begin(l, bot, level, team, c, tick);
            if (op == null) return null;
        }
        // another foe than one down its side's shaft: it searches as before
        if (!down(op.shaft, c)) return null;
        // one of its side come up while its side goes down: it goes with them
        if (!op.members.contains(bot) && op.phase != OVER && AiSquads.live(bot) && bot.mob.distanceToSqr(mid(op.shaft.rim, 0)) <= NEAR * NEAR && AiCrossing.of(bot) == null
                && bot.mob.getY() > BlockPos.getY(op.shaft.foot) + 1) op.members.add(bot);
        if (!op.members.contains(bot) || op.downs.contains(bot) && op.holder != bot) return null;
        // round by another way near a room the foe is in with a way in: the entry's drill from here (beside the way in,
        // a grenade in, in together), not on to where it was lost, one after another through the way in
        if (op.phase == BYPASS && !climbing(op, bot, tick) && bot.mob.distanceToSqr(mid(op.goal, 0)) <= BYPASS_ENTRY * BYPASS_ENTRY && AiEntry.wayIn(bot, level, c, tick)) {
            l.bypass.remove(team);
            phase(op, OVER, tick);
            bypassEntry++;
            return null;
        }
        op.lastStep = tick;
        bot.memory.keep(c, tick);
        try {
            return advance(op, s, bot, level, tick);
        } catch (RuntimeException e) {
            AiRooms.fault("going down a shaft", op.shaft.top, e);
            op.phase = OVER;
            return null;
        }
    }

    private static @Nullable Op begin(PerLevel l, AiBot bot, ServerLevel level, String team, TargetMemory.Contact c, long tick) {
        Bypass by = l.bypass.get(team);
        if (by != null && tick >= by.until) l.bypass.remove(team);
        else if (by != null) {
            // going round a shaft: none down it (nor another: the foe is reached round)
            bypassKept++;
            return null;
        }
        if (c.lostY > bot.mob.getY() - DEEP || AiWater.swims(bot.mob)) {
            none[0]++;
            return null;
        }
        TacticalMap map = TacticalMap.of(level);
        long start = PathSearch.snap(map, bot.mob.getX(), bot.mob.getY(), bot.mob.getZ()), goal = PathSearch.snap(map, c.lostX, c.lostY, c.lostZ);
        if (start == Long.MIN_VALUE || goal == Long.MIN_VALUE) {
            none[1]++;
            return null;
        }
        PathSearch.Result r = PathSearch.find(map, start, goal, 1.5, AiMover.MAX_NODES, PathSearch.Cost.NONE, true);
        if (!r.reached) {
            none[2]++;
            if (trace) {
                long end = r.cells.length == 0 ? start : r.cells[r.cells.length - 1];
                StringBuilder col = new StringBuilder();
                int gx = BlockPos.getX(goal), gz = BlockPos.getZ(goal);
                noWay.add(String.format(java.util.Locale.ROOT, "from %s to %s: got to %s (%d cells, %d expanded, exhausted %s, missing %d, nodes out %s)",
                        BlockPos.of(start).toShortString(), BlockPos.of(goal).toShortString(), BlockPos.of(end).toShortString(), r.cells.length, r.expanded, r.exhausted, r.missing.length,
                        r.expanded >= AiMover.MAX_NODES));
            }
            // the map not built yet on the way (a long shaft section after section): soon again; else not for a while
            if (r.missing.length > 0) AiMover.wantMissing(map, r);
            else l.next.put(team, tick + AGAIN);
            return null;
        }
        Shaft sh = shaft(level, r.cells, goal);
        if (sh == null) {
            none[3]++;
            l.next.put(team, tick + AGAIN);
            return null;
        }
        // its side's near it (its squad and any other of its side here: one alone down the ladder would go down alone)
        List<AiBot> members = new ArrayList<>();
        // (those of them looking for the same foe: one fighting another is no part of it)
        for (AiBot b : AiBots.bots(level))
            if (AiSquads.live(b) && AiSquads.team(b).equals(team) && b.mob.distanceToSqr(bot.mob) <= NEAR * NEAR && AiCrossing.of(b) == null
                    && looksDown(b, sh)) members.add(b);
        if (!members.contains(bot)) members.add(bot);
        // the security at the hatch: its base of fire, else the last of it; none for one alone
        AiBot guard = null;
        if (members.size() > 1) {
            for (AiBot b : members) if (AiRole.of(b) == AiRole.BASE) guard = b;
            if (guard == null) guard = members.get(members.size() - 1);
        }
        Op op = new Op(team, sh, members, guard, tick);
        op.goal = goal;
        op.through = r.cells.length;
        // (a way round given up on this shaft once: none looked for again)
        op.bypassTried = Long.valueOf(sh.top).equals(l.switched.get(team));
        l.ops.put(team, op);
        begun++;
        // found held a while ago: none goes down, they hold its hatch (and try again in a while)
        Long was = l.held.get(sh.top);
        Long cost = l.costly.get(sh.top);
        boolean smoke = false;
        for (AiBot b : members) if (AiSquads.live(b) && b.smokes > 0) smoke = true;
        if (!smoke && (was != null && tick - was < HELD_MEMORY || cost != null && tick - cost < COSTLY_MEMORY)) {
            op.lastSign = was;
            phase(op, CONTAIN, tick);
            contained++;
        }
        if (bot.brain instanceof AiSoldier so) so.note(bot, tick, "a foe under the ground down a shaft: the squad to its hatch");
        return op;
    }

    private static void phase(Op op, int phase, long tick) {
        op.phase = phase;
        op.phaseAt = tick;
    }

    private static boolean at(AiBot b, long cell, double r) {
        Vec3 m = mid(cell, 0);
        double dx = b.mob.getX() - m.x, dz = b.mob.getZ() - m.z;
        return dx * dx + dz * dz <= r * r && Math.abs(b.mob.getY() - m.y) <= 1.2;
    }

    private static @Nullable Vec3 advance(Op op, AiSoldier s, AiBot bot, ServerLevel level, long tick) {
        Shaft sh = op.shaft;
        Vec3 hatch = sh.mid();
        switch (op.phase) {
            case GATHER -> {
                boolean all = true;
                for (AiBot b : op.members) if (AiSquads.live(b) && !at(b, sh.back, GATHERED)) all = false;
                if (all || tick - op.phaseAt > GATHER_MAX) {
                    // too deep for a frag (smoke first, else one at a time): a way round first, if there is one (the
                    // map about it not built yet: gathered a while longer, BYPASS_WAIT at most)
                    if (deep(sh) && !op.bypassTried) {
                        // (one look every BYPASS_AGAIN ticks for the op, not each of it each tick: a big search)
                        if (tick < op.bypassNext) {
                            s.moveFiring(bot, sh.back, AiSoldier.RUN, hatch, tick, "to the hatch of a shaft down to the foe", AiThoughts.Why.HATCH, RIM);
                            return hatch;
                        }
                        op.bypassNext = tick + BYPASS_AGAIN;
                        if (op.bypassFirst == Long.MIN_VALUE) op.bypassFirst = tick;
                        long start = PathSearch.snap(TacticalMap.of(level), bot.mob.getX(), bot.mob.getY(), bot.mob.getZ());
                        int found = start == Long.MIN_VALUE || op.goal == Long.MIN_VALUE ? 0 : bypassFind(of(level), level, op.team, sh, start, op.goal, op.through, tick);
                        if (found > 0) {
                            op.bypassTried = true;
                            bypassDeep++;
                            phase(op, BYPASS, tick);
                            if (bot.brain instanceof AiSoldier so) so.note(bot, tick, "a foe under the ground down a deep shaft: the squad round by another way");
                            return hatch;
                        }
                        if (found < 0 && tick - op.bypassFirst < BYPASS_WAIT) {
                            s.moveFiring(bot, sh.back, AiSoldier.RUN, hatch, tick, "to the hatch of a shaft down to the foe", AiThoughts.Why.HATCH, RIM);
                            return hatch;
                        }
                        op.bypassTried = true;
                    }
                    phase(op, FRAG, tick);
                    op.thrower = thrower(op, level);
                    // too deep for a frag, the foe gone down this very shaft: smoke down first if one has any (it
                    // bursts where it lands, at the foot however deep), then down close under it
                    if (op.thrower == null && deep(sh) && smokeFirst(op)) {
                        op.first = true;
                        smokeFirst++;
                        phase(op, HELD, tick);
                    }
                }
                // gathered off the shaft, none over the hatch (lifted, it is a hole)
                s.moveFiring(bot, sh.back, AiSoldier.RUN, hatch, tick, "to the hatch of a shaft down to the foe", AiThoughts.Why.HATCH, RIM);
                return hatch;
            }
            case FRAG -> {
                AiBot t = op.thrower;
                Entity g = op.fragId < 0 ? null : level.getEntity(op.fragId);
                if (t == null || !AiSquads.live(t) || op.fragId >= 0 && (g == null || g.isRemoved()) || tick - op.phaseAt > FRAG_MAX + AiGrenades.FUSE) {
                    if (op.fragId >= 0) op.blastAt = tick;
                    toDown(op, level, tick);
                    return hatch;
                }
                if (bot == t && op.fragId < 0) {
                    s.moveFiring(bot, sh.rim, AiSoldier.RUN, hatch, tick, "at the hatch, a grenade down the shaft", AiThoughts.Why.HATCH, 0.6);
                    boolean rimmed = at(bot, sh.rim, 0.8), safe = rimmed && safe(op, level, bot);
                    // the hatch lifted from its rim, none of its side on it
                    if (safe && sh.hatch != null && !over(op, level)) AiDoors.open(level, bot.mob, sh.hatch);
                    if (!rimmed) notAtRim++;
                    else if (!safe) unsafe++;
                    if (safe && (sh.hatch == null || level.getBlockState(sh.hatch).getValue(TrapDoorBlock.OPEN))) {
                        // dropped down the middle of the shaft from over it, sticking where it lands, bursting there
                        Vec3 burst = mid(sh.foot, 0.2);
                        AiGrenades.Throw down = new AiGrenades.Throw(bot.mob.getYRot(), 90F, burst, DROP_FORCE);
                        Entity thrown = s.threwInto(bot, level, down, EnumGrenadeFilling.HE, tick);
                        thrown.setPos(hatch.x, Math.max(hatch.y, thrown.getY()), hatch.z);
                        thrown.setOldPosAndRot();
                        op.fragId = thrown.getId();
                        frags++;
                    }
                    return hatch;
                }
                if (op.fragId < 0 && tick - op.phaseAt > FRAG_MAX) {
                    toDown(op, level, tick);
                }
                s.moveFiring(bot, sh.back, AiSoldier.RUN, hatch, tick, "off the hatch, its grenade going down", AiThoughts.Why.HATCH, RIM);
                return hatch;
            }
            case BYPASS -> {
                // the way round taken: those called back up the ladder (onLadder); the others on round it to where the
                // foe was lost (their paths kept off the shaft: avoid), the search from there once one of it is near
                Vec3 at = mid(op.goal, 1.1);
                for (AiBot b : op.members)
                    if (AiSquads.live(b) && b.mob.distanceToSqr(at) <= BYPASS_NEAR * BYPASS_NEAR) {
                        // there: the search from here, its ways free again, and a way down another shaft may begin
                        of(level).bypass.remove(op.team);
                        phase(op, OVER, tick);
                        return null;
                    }
                Bypass by = of(level).bypass.get(op.team);
                if (by == null || tick - op.phaseAt > BYPASS_MEMORY) {
                    phase(op, OVER, tick);
                    return null;
                }
                if (op.downs.contains(bot) || climbing(op, bot, tick)) return null;
                s.moveFiring(bot, op.goal, AiSoldier.WALK, at, tick, "round by another way to the foe under the ground", AiThoughts.Why.HATCH, BYPASS_NEAR / 2);
                return at;
            }
            case HELD -> {
                return held(op, s, bot, level, tick);
            }
            case CONTAIN -> {
                return contain(op, s, bot, level, tick);
            }
            case DOWN -> {
                if (op.signTick != tick) {
                    op.signTick = tick;
                    int why = sign(op, level, tick);
                    if (why >= 0) {
                        hold(op, why, level, tick);
                        return hatch;
                    }
                }
                List<AiBot> order = op.order();
                if (op.guard != null) order.add(op.guard);
                AiBot last = null;
                long lastAt = Long.MIN_VALUE;
                boolean all = true;
                for (AiBot b : order) {
                    Long sent = op.sent.get(b);
                    if (sent == null) {
                        if (AiSquads.live(b)) all = false;
                        continue;
                    }
                    if (sent > lastAt) {
                        lastAt = sent;
                        last = b;
                    }
                    if (op.downs.contains(b)) continue;
                    if (!AiSquads.live(b) || tick - sent > CLIMB_BASE + CLIMB_RUNG * sh.rungs) {
                        // fallen, or stuck on the way down: it goes on as it may
                        op.downs.add(b);
                        continue;
                    }
                    all = false;
                    if (at(b, sh.out, 1.0) || b.mob.onGround() && b.mob.getY() < BlockPos.getY(sh.foot) + 0.5) {
                        // at the foot: it holds the shaft's way out till the next is down, the one before goes on
                        down++;
                        op.downs.add(b);
                        op.holder = b;
                    }
                }
                // the next one down once the one before is SPACING rungs under the top (or down, or fallen)
                boolean single = op.smokeAt == Long.MIN_VALUE && deep(sh) && (op.holder == null || !op.holder.mob.isAlive());
                if (last == null || op.downs.contains(last) || !single && last.mob.getY() <= BlockPos.getY(sh.top) + 1 - op.spacing)
                    for (AiBot b : order)
                        if (!op.sent.containsKey(b) && AiSquads.live(b)) {
                            op.sent.put(b, tick);
                            break;
                        }
                if (all) {
                    phase(op, OVER, tick);
                    return null;
                }
                if (op.sent.containsKey(bot) && !op.downs.contains(bot)) {
                    if (sh.hatch != null) AiDoors.open(level, bot.mob, sh.hatch);
                    s.moveFiring(bot, sh.out, AiSoldier.RUN, sh.onward(), tick, "down the ladder of a shaft, one after another", AiThoughts.Why.HATCH, 0.6);
                    return sh.onward();
                }
                if (bot == op.holder) {
                    if (op.downs.contains(bot) && !AiSquads.live(bot)) return null;
                    s.moveFiring(bot, sh.out, AiSoldier.WALK, sh.onward(), tick, "at the foot of the shaft holding its way out till the next is down", AiThoughts.Why.HATCH, 0.6);
                    return sh.onward();
                }
                if (op.downs.contains(bot)) return null;
                // the others and the security up by the hatch, watching it
                s.moveFiring(bot, sh.back, AiSoldier.RUN, hatch, tick, bot == op.guard ? "by the hatch, its security while the others go down" : "by the hatch, waiting to go down",
                        AiThoughts.Why.HATCH, RIM);
                return hatch;
            }
            default -> {
                return null;
            }
        }
    }

    /** on from the grenade (or none) to going down: held already at the foot (seen, heard), they hold instead */
    private static void toDown(Op op, ServerLevel level, long tick) {
        int why = sign(op, level, tick);
        if (why >= 0) hold(op, why, level, tick);
        else phase(op, DOWN, tick);
    }

    /**
     * A sign, by its own senses, that the shaft's foot is held, or -1: (0) one of it on the ladder hurt by someone
     * under it, or down there; (1) one of it up by the hatch sees a foe at the foot, or heard a shot there just now; (2)
     * the first at the foot put down before the next is down
     */
    static int sign(Op op, ServerLevel level, long tick) {
        Shaft sh = op.shaft;
        double fx = BlockPos.getX(sh.foot) + 0.5, fy = BlockPos.getY(sh.foot), fz = BlockPos.getZ(sh.foot) + 0.5;
        for (AiBot b : op.members) {
            Long sent = op.sent.get(b);
            boolean onIt = sent != null && !op.downs.contains(b) && !op.backing.contains(b);
            if (onIt) {
                if (!b.mob.isAlive()) return 0;
                var n = b.memory.noise();
                if (b.memory.shotTick() >= tick - 1 && n != null && n.hurt && n.y < b.mob.getY()) return 0;
                continue;
            }
            if (sent != null || !AiSquads.live(b)) continue;
            for (TargetMemory.Contact k : b.memory.contacts())
                if (k.state == TargetMemory.State.VISIBLE && near(k.x, k.y, k.z, fx, fy, fz)) return 1;
            var n = b.memory.noise();
            if (n != null && n.loud && tick - n.tick <= HEARD_RECENT && Math.abs(n.tick - op.blastAt) > OWN_BLAST && near(n.x, n.y, n.z, fx, fy, fz)) return 1;
        }
        if (op.holder != null && op.smokeAt == Long.MIN_VALUE && op.holder.mob.isAlive() && op.holder.memory.shotTick() >= tick - HEARD_RECENT
                && op.downs.size() < op.members.size() && op.downs.size() <= 1) return 2;
        if (op.holder != null && !op.holder.mob.isAlive() && op.downs.size() < op.members.size()) {
            for (AiBot b : op.members) if (b != op.holder && AiSquads.live(b) && !op.downs.contains(b)) return 2;
        }
        return -1;
    }

    /**
     * A way round the shaft for this team, from start to goal, BYPASS_COST on its column: found (and kept for the team)
     * or not. A team that gave up a way round this shaft looks for none.
     */
    private static boolean bypass(PerLevel l, ServerLevel level, String team, Shaft sh, long start, long goal, int through, long tick) {
        return bypassFind(l, level, team, sh, start, goal, through, tick) > 0;
    }

    /** as bypass: 1 found, 0 none, -1 none on the map as built so far (the sections missing wanted: soon again) */
    private static int bypassFind(PerLevel l, ServerLevel level, String team, Shaft sh, long start, long goal, int through, long tick) {
        if (!bypassOn || Long.valueOf(sh.top).equals(l.switched.get(team))) return 0;
        Bypass by = new Bypass(sh.top, BlockPos.getX(sh.top), BlockPos.getZ(sh.top), BlockPos.getY(sh.foot) + 1, BlockPos.getY(sh.top) - 1, tick + BYPASS_MEMORY, tick);
        PathSearch.Result r = PathSearch.find(TacticalMap.of(level), start, goal, 1.5, BYPASS_NODES, (x, y, z) -> by.on(x, y, z) ? BYPASS_COST : 0F, true);
        boolean on = false;
        for (long cell : r.cells) if (by.on(BlockPos.getX(cell), BlockPos.getY(cell), BlockPos.getZ(cell))) on = true;
        bypassLast = String.format(java.util.Locale.ROOT, "reached %s, %d cells (down the shaft %d), %d expanded, on the column %s", r.reached, r.cells.length, through, r.expanded, on);
        if ((!r.reached || on) && r.missing.length > 0) {
            // the map round about not built yet (it is built about the mobs): what is missing wanted
            TacticalMap map = TacticalMap.of(level);
            int k = 0;
            for (long key : r.missing) {
                if (k++ >= BYPASS_WANT) break;
                map.want(net.minecraft.core.SectionPos.sectionToBlockCoord(net.minecraft.core.SectionPos.x(key), 8), net.minecraft.core.SectionPos.sectionToBlockCoord(net.minecraft.core.SectionPos.y(key), 8),
                        net.minecraft.core.SectionPos.sectionToBlockCoord(net.minecraft.core.SectionPos.z(key), 8), 8, 8);
            }
            bypassMissing++;
            return -1;
        }
        if (!r.reached || on) {
            bypassNone[0]++;
            return 0;
        }
        if (r.cells.length > Math.max(BYPASS_TIMES * through, through + BYPASS_MORE)) {
            bypassNone[1]++;
            return 0;
        }
        l.bypass.put(team, by);
        return 1;
    }

    /** off only for the stands' "before" (the shaft taken as it was) */
    static boolean bypassOn = true;

    /**
     * AiMover's cost for one of a team going round a shaft: BYPASS_COST on its column. Shot at by someone after
     * BYPASS_GRACE, away from the column (on its way round), the way round is given up: the shaft again, once.
     */
    static PathSearch.Cost avoid(AiBot bot, ServerLevel level, long tick) {
        PerLevel l = LEVELS.get(level);
        if (l == null || l.bypass.isEmpty()) return PathSearch.Cost.NONE;
        String team = AiSquads.team(bot);
        Bypass by = l.bypass.get(team);
        if (by == null) return PathSearch.Cost.NONE;
        if (tick >= by.until) {
            l.bypass.remove(team);
            return PathSearch.Cost.NONE;
        }
        double dx = bot.mob.getX() - by.x - 0.5, dz = bot.mob.getZ() - by.z - 0.5;
        if (bot.memory.shotTick() > by.made + BYPASS_GRACE && dx * dx + dz * dz > 4) {
            l.bypass.remove(team);
            l.switched.put(team, by.top);
            l.next.remove(team);
            bypassDropped++;
            return PathSearch.Cost.NONE;
        }
        return (x, y, z) -> by.on(x, y, z) ? BYPASS_COST : 0F;
    }

    /** one of it sent down this shaft is fallen */
    private static boolean lost(Op op) {
        for (AiBot b : op.members) if (op.sent.containsKey(b) && !b.mob.isAlive()) return true;
        return false;
    }

    /** at the shaft's foot: this near it across, not over it more than a few blocks */
    private static boolean near(double x, double y, double z, double fx, double fy, double fz) {
        double dx = x - fx, dz = z - fz;
        return y <= fy + 3 && dx * dx + dz * dz <= FOOT_NEAR * FOOT_NEAR;
    }

    /** the foot found held: none more sent, those on the upper half of the ladder back up it; smoke down, else they hold */
    private static void hold(Op op, int why, ServerLevel level, long tick) {
        held++;
        heldWhy[why]++;
        op.lastSign = tick;
        // (the fallen on the ladder and a fallen holder told once: else the same sign came every tick, for good)
        for (AiBot b : op.members) if (op.sent.containsKey(b) && !op.downs.contains(b) && !b.mob.isAlive()) op.downs.add(b);
        if (op.holder != null && !op.holder.mob.isAlive()) op.holder = null;
        of(level).held.put(op.shaft.top, tick);
        if (lost(op)) of(level).costly.put(op.shaft.top, tick);
        double mid = (BlockPos.getY(op.shaft.top) + BlockPos.getY(op.shaft.foot)) / 2.0;
        for (AiBot b : op.members)
            if (op.sent.containsKey(b) && !op.downs.contains(b) && AiSquads.live(b) && b.mob.getY() > mid) op.backing.add(b);
        // a way round it first (once): none more down it
        if (!op.bypassTried) {
            op.bypassTried = true;
            AiBot from = null;
            for (AiBot b : op.members) if (AiSquads.live(b) && !op.sent.containsKey(b)) from = from == null ? b : from;
            if (from == null) for (AiBot b : op.backing) if (AiSquads.live(b)) from = b;
            TacticalMap map = TacticalMap.of(level);
            long start = from == null ? Long.MIN_VALUE : PathSearch.snap(map, from.mob.getX(), from.mob.getY(), from.mob.getZ());
            if (start != Long.MIN_VALUE && op.goal != Long.MIN_VALUE && bypass(of(level), level, op.team, op.shaft, start, op.goal, op.through, tick)) {
                bypassHeld++;
                phase(op, BYPASS, tick);
                return;
            }
        }
        if (op.smoked) {
            phase(op, CONTAIN, tick);
            contained++;
            return;
        }
        op.smoker = null;
        for (AiBot b : op.members)
            if (AiSquads.live(b) && b.smokes > 0 && !op.sent.containsKey(b) && !overColumn(op.shaft, b)) {
                op.smoker = b;
                break;
            }
        if (op.smoker == null) {
            op.smoked = true;
            phase(op, CONTAIN, tick);
            contained++;
        } else phase(op, HELD, tick);
    }

    /** held, smoke going down: the one with it to the rim and drops it down the middle; the others off the hatch */
    private static @Nullable Vec3 held(Op op, AiSoldier s, AiBot bot, ServerLevel level, long tick) {
        Shaft sh = op.shaft;
        Vec3 hatch = sh.mid();
        if (op.smokeAt != Long.MIN_VALUE ? tick - op.smokeAt >= SMOKE_SETTLE : tick - op.phaseAt > SMOKE_MAX || op.smoker == null || !AiSquads.live(op.smoker)) {
            if (op.smokeAt == Long.MIN_VALUE && op.first) {
                // no smoke got down: down without it, one at a time
                op.first = false;
                phase(op, DOWN, tick);
                return hatch;
            }
            op.smoked = true;
            if (op.smokeAt == Long.MIN_VALUE) {
                phase(op, CONTAIN, tick);
                contained++;
                return hatch;
            }
            // down under the cloud, close one after another; those called back go again
            for (AiBot b : op.backing) op.sent.remove(b);
            op.backing.clear();
            op.spacing = FAST_SPACING;
            phase(op, DOWN, tick);
            return hatch;
        }
        if (op.backing.contains(bot)) {
            s.moveFiring(bot, sh.back, AiSoldier.RUN, hatch, tick, "back up the ladder: the shaft's foot is held", AiThoughts.Why.HATCH, RIM);
            return hatch;
        }
        if (bot == op.smoker && op.smokeAt == Long.MIN_VALUE) {
            smokerTicks[0]++;
            s.moveFiring(bot, sh.rim, AiSoldier.RUN, hatch, tick, op.first ? "at the hatch, smoke down the shaft: too deep for a grenade" : "at the hatch, smoke down the shaft: its foot is held",
                    AiThoughts.Why.HATCH, overColumn(sh, bot) ? 0.2 : 0.6);
            // (at the rim's edge by the hatch it is over the hatch itself: the others are what keeps it from the drop,
            // and it steps back to the rim's middle first)
            boolean self = overColumn(sh, bot), rimmed = at(bot, sh.rim, self ? 0.3 : 0.8) && !self, overIt = rimmed && overOthers(op, bot);
            if (rimmed) smokerTicks[1]++;
            if (overIt) smokerTicks[2]++;
            if (rimmed && !overIt && bot.smokes > 0) {
                if (sh.hatch != null) AiDoors.open(level, bot.mob, sh.hatch);
                if (sh.hatch != null && !level.getBlockState(sh.hatch).getValue(TrapDoorBlock.OPEN)) smokerTicks[3]++;
                if (sh.hatch == null || level.getBlockState(sh.hatch).getValue(TrapDoorBlock.OPEN)) {
                    Vec3 burst = mid(sh.foot, 0.2);
                    AiGrenades.Throw down = new AiGrenades.Throw(bot.mob.getYRot(), 90F, burst, DROP_FORCE);
                    Entity thrown = AiGrenades.launch(level, bot.mob, down, AiSmoke.item(bot.mob.getYRot()), DROP_FORCE);
                    thrown.setPos(hatch.x, Math.max(hatch.y, thrown.getY()), hatch.z);
                    thrown.setOldPosAndRot();
                    AiSmoke.thrown++;
                    bot.smokes--;
                    AiAdapter.keepSmokes(bot);
                    op.smokeAt = tick;
                    smokedDown++;
                }
            }
            return hatch;
        }
        if (op.downs.contains(bot) || op.sent.containsKey(bot)) return null;
        s.moveFiring(bot, sh.back, AiSoldier.RUN, hatch, tick, "off the hatch, smoke going down the shaft", AiThoughts.Why.HATCH, RIM);
        return hatch;
    }

    /**
     * None goes down: two of it watch the hatch from beside it (not over it, in the fire up the shaft), the others off
     * it; CONTAIN_MAX with nothing more from the foot, and they try again (one after another, as at first)
     */
    private static @Nullable Vec3 contain(Op op, AiSoldier s, AiBot bot, ServerLevel level, long tick) {
        Shaft sh = op.shaft;
        Vec3 hatch = sh.mid();
        if (op.signTick != tick) {
            op.signTick = tick;
            if (sign(op, level, tick) >= 0) {
                op.lastSign = tick;
                of(level).held.put(sh.top, tick);
                for (AiBot b : op.members) if (op.sent.containsKey(b) && !op.downs.contains(b) && !b.mob.isAlive()) op.downs.add(b);
                if (op.holder != null && !op.holder.mob.isAlive()) op.holder = null;
            }
        }
        // (the shaft cost one of it already: none more goes down it, they hold the hatch till the op is over)
        if (!lost(op) && tick - op.lastSign >= CONTAIN_MAX) {
            for (AiBot b : op.backing) op.sent.remove(b);
            op.backing.clear();
            op.spacing = SPACING;
            phase(op, DOWN, tick);
            return hatch;
        }
        if (op.downs.contains(bot) || op.sent.containsKey(bot) && !op.backing.contains(bot)) return null;
        AiBot second = null;
        for (AiBot b : op.members)
            if (b != op.guard && AiSquads.live(b) && !op.downs.contains(b) && !op.sent.containsKey(b)) {
                second = b;
                break;
            }
        boolean watch = bot == op.guard || bot == second || op.guard == null && bot == op.members.get(0);
        if (watch && !op.backing.contains(bot)) {
            s.moveFiring(bot, sh.rim, AiSoldier.WALK, hatch, tick, "beside the hatch, watching the shaft: its foot is held", AiThoughts.Why.HATCH, 0.8);
            return hatch;
        }
        s.moveFiring(bot, sh.back, AiSoldier.RUN, hatch, tick, op.backing.contains(bot) ? "back up the ladder: the shaft's foot is held" : "off the hatch, its foot held: none goes down",
                AiThoughts.Why.HATCH, RIM);
        return hatch;
    }

    /** too deep for a frag down it: it bursts in the air (the fuse out before the foot) */
    static boolean deep(Shaft sh) {
        return fallTicks(BlockPos.getY(sh.top) + 1.5 - BlockPos.getY(sh.foot)) + FUSE_LEFT > AiGrenades.FUSE;
    }

    /** the smoke first: one of it (not the guard, not over the hatch) has some, and one of it lost the foe down this shaft */
    private static boolean smokeFirst(Op op) {
        if (!AiSmoke.on || !AiSmoke.ready() || !acup.gunfeel.smoke.SmokeClouds.enabled()) return false;
        boolean lost = false;
        for (AiBot b : op.members) if (AiSquads.live(b) && looksDown(b, op.shaft)) lost = true;
        if (!lost) return false;
        op.smoker = null;
        for (AiBot b : op.members)
            if (b != op.guard && AiSquads.live(b) && b.smokes > 0 && !overColumn(op.shaft, b)) {
                op.smoker = b;
                return true;
            }
        return false;
    }

    /** the one to drop a grenade down, or null: none down this shaft (too deep for the fuse, its own in reach, none with one) */
    private static @Nullable AiBot thrower(Op op, ServerLevel level) {
        Shaft sh = op.shaft;
        if (!AiGrenades.on || !AiEntry.frag) {
            noFrag++;
            return null;
        }
        double fall = BlockPos.getY(sh.top) + 1.5 - BlockPos.getY(sh.foot);
        if (fallTicks(fall) + FUSE_LEFT > AiGrenades.FUSE) {
            deepNoFrag++;
            noFrag++;
            return null;
        }
        AiBot best = null;
        double bd = Double.MAX_VALUE;
        for (AiBot b : op.members) {
            if (b == op.guard || !AiSquads.live(b) || b.grenades <= 0 || overColumn(op.shaft, b)) continue;
            double d = b.mob.distanceToSqr(mid(sh.rim, 0));
            if (d < bd) {
                bd = d;
                best = b;
            }
        }
        if (best == null) noFrag++;
        return best;
    }

    /**
     * None of its own side in the grenade's reach at the shaft's foot unshielded, now; the thrower aside: it is off
     * the rim BACK blocks long before the fuse is out (its grenade at the foot in a few ticks, bursting at FUSE)
     */
    private static boolean safe(Op op, ServerLevel level, AiBot thrower) {
        Vec3 burst = mid(op.shaft.foot, 0.2);
        VoxelRay.Source src = AiBots.voxels(level);
        double s2 = AiEntry.HE_HURTS * AiEntry.HE_HURTS;
        for (AiBot b : AiBots.bots(level))
            if (b != thrower && b.mob.isAlive() && AiTeams.own(b.mob, op.members.get(0).mob) && b.mob.distanceToSqr(burst) < s2
                    && !AiGrenades.shielded(src, b.mob.getX(), b.mob.getY(), b.mob.getZ(), b.mob.getBbHeight(), burst)) return false;
        for (var p : level.players())
            if (p.isAlive() && !p.isSpectator() && AiTeams.own(p, op.members.get(0).mob) && p.distanceToSqr(burst) < s2
                    && !AiGrenades.shielded(src, p.getX(), p.getY(), p.getZ(), p.getBbHeight(), burst)) return false;
        return true;
    }

    /** one of its side stands over the shaft (on its hatch shut): lifted, the hatch would drop it down */
    private static boolean over(Op op, ServerLevel level) {
        for (AiBot b : op.members) if (AiSquads.live(b) && overColumn(op.shaft, b)) return true;
        return false;
    }

    /** one of it but this one over the hatch */
    private static boolean overOthers(Op op, AiBot but) {
        for (AiBot b : op.members) if (b != but && AiSquads.live(b) && overColumn(op.shaft, b)) return true;
        return false;
    }

    private static boolean overColumn(Shaft sh, AiBot b) {
        double x = BlockPos.getX(sh.top) + 0.5, z = BlockPos.getZ(sh.top) + 0.5;
        return Math.abs(b.mob.getX() - x) < 0.8 && Math.abs(b.mob.getZ() - z) < 0.8 && b.mob.getY() >= BlockPos.getY(sh.top);
    }

    /** ticks a grenade dropped down at DROP_FORCE takes to fall so far (the game's throwable: gravity, then drag) */
    static int fallTicks(double fall) {
        double y = 0, v = -DROP_FORCE * 1.5;
        for (int t = 1; t <= 400; t++) {
            y += v;
            v = v * AiGrenades.DRAG - AiGrenades.GRAVITY;
            if (-y >= fall) return t;
        }
        return Integer.MAX_VALUE / 2;
    }

    /** whether this is its side's grenade down a shaft and it out of the shaft: it does not run */
    static boolean keeps(ServerLevel level, AiBot bot, int grenade) {
        PerLevel l = LEVELS.get(level);
        if (l == null) return false;
        for (Op op : l.ops.values())
            if (op.fragId == grenade && op.phase != OVER) return bot.mob.getY() >= BlockPos.getY(op.shaft.top) + 1;
        return false;
    }

    /** its side's way down now, as words (the stands' traces) */
    static String describe(ServerLevel level, String team) {
        PerLevel l = LEVELS.get(level);
        Op op = l == null ? null : l.ops.get(team);
        if (op == null) return "none";
        return "phase " + op.phase + " members " + op.members.size() + " sent " + op.sent.size() + " downs " + op.downs.size() + " last " + (level.getGameTime() - op.lastStep) + " rim "
                + BlockPos.of(op.shaft.rim).toShortString() + " back " + BlockPos.of(op.shaft.back).toShortString() + " top " + BlockPos.of(op.shaft.top).toShortString() + " foot "
                + BlockPos.of(op.shaft.foot).toShortString();
    }

    /** whether it is going down a shaft with its side now (the stands) */
    static boolean going(AiBot bot) {
        if (!(bot.mob.level() instanceof ServerLevel level)) return false;
        PerLevel l = LEVELS.get(level);
        if (l == null) return false;
        long now = level.getGameTime();
        for (Op op : l.ops.values()) if (op.phase != OVER && now - op.lastStep <= STALE && op.members.contains(bot) && !op.downs.contains(bot)) return true;
        return false;
    }
}
