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
 * search as before. One entry a side, a target and a room; all of it is over in ENTRY_MAX ticks at most. Into a
 * trench's stretch they go so through the turn before it (AiTrenches): beside it in the stretch behind, the thrower
 * steps into the turn.
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
    public static int entries, frags, noThrow, noFragFought;
    /** the stands: of frags, those into a room fought in (the entry weighed as one, no foe known alive gone after by it) */
    public static int fragsFought;
    /** a room a foe fell in takes a grenade only this small (cells), every cell of it this near where it goes (blocks), none of its side this near where the foe fell nor in it */
    static final int FOUGHT_CELLS = 64;
    static final double FOUGHT_REACH = 6, FOUGHT_OWN = 4;
    /** a room fought in: whether its grenade goes in, worked out again this often while they stack, ticks */
    static final int FRAG_AGAIN = 10;

    /**
     * One a grenade into a room before an entry is kept from (after a foe known alive or into a room fought in): a
     * villager or trader, a tamed pet, one given a name; no fighter (an AI mob, a player, a stand's target), not a wild animal (a cow in the
     * barn does not shelter the one hiding there).
     */
    static boolean spared(net.minecraft.world.entity.LivingEntity e) {
        if (AiBots.fighter(e)) return false;
        return e instanceof net.minecraft.world.entity.npc.villager.AbstractVillager
                || e instanceof net.minecraft.world.entity.OwnableEntity o && o.getOwnerReference() != null
                || e.hasCustomName();
    }

    /** a grenade into a room fought in: a shut room the burst covers, and none of the thrower's side nor one to be spared in it or by where the foe fell */
    private static boolean fragFought(Op op, ServerLevel level, AiBot bot, long lost) {
        AiRooms.Plan plan = op.plan;
        if (plan == null || op.room < 0 || plan.size[op.room] > FOUGHT_CELLS) return false;
        for (long cell : plan.cells()[op.room])
            if (Math.hypot(BlockPos.getX(cell) + 0.5 - op.aim.x, BlockPos.getZ(cell) + 0.5 - op.aim.z) > FOUGHT_REACH) return false;
        Vec3 at = mid(lost, 0);
        double r = Math.max(FOUGHT_OWN, Math.sqrt(plan.size[op.room]) + FOUGHT_REACH);
        for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new net.minecraft.world.phys.AABB(at, at).inflate(r))) {
            if (!e.isAlive() || !AiTeams.own(bot.mob, e) && !spared(e)) continue;
            boolean in = plan.room(cell(e.getX(), e.getY(), e.getZ())) == op.room;
            if (in) return false;
            // (the entry's own at their places beside the way in, a foe fallen by it: the throw itself keeps them out of its blast)
            if (e.position().distanceTo(at) <= FOUGHT_OWN && !member(op, e)) return false;
        }
        return true;
    }

    private static boolean member(Op op, net.minecraft.world.entity.LivingEntity e) {
        for (AiBot m : op.members) if (m.mob == e) return true;
        return false;
    }

    /** the stands: those of an entry in its room while its grenade was out and not burst yet (in before their own blast) */
    public static int inBeforeBlast;
    /** the stands: those of an entry off their place beside the way in (OFF_PLACE) while its grenade was out, past WAIT_GRACE ticks of the wait */
    public static int offBeforeBlast;
    static final double OFF_PLACE = 2.5;
    static final int WAIT_GRACE = 20;
    /** the stands: entries gone in before all of them were beside the way in; all there, and none had a throw found from before it */
    public static int unstacked, noPlan;
    /** the stands: throwers lost before the throw (down, no way out before the opening, out of time) and handed on to another of it */
    public static int throwerLost, handedOn;
    /** the stands: how each entry's grenade went, in order (the stand takes them per round) */
    public static final List<String> fates = new ArrayList<>();
    /** the stands: told of each entry's first thrower as its step out begins (the trench stand downs it there); null: none */
    public static java.util.function.@Nullable Consumer<AiBot> firstThrower;
    /** a grenade is thrown this many cells into the room, straight in through the opening */
    static final int DEEP = 4;
    /** the thrower has this long to step out and throw; a throw is worked out this often and this many times; ticks */
    static final int FRAG_MAX = 60, FRAG_EVERY = 5, FRAG_TRIES = 4;
    /**
     * The others of its side (not of the entry), and those to be spared, are kept this far from the burst unless all of
     * them is behind blocks (AiBunker's and AiHatch's grenades too): as far as the filling's blast hurts a mob in NTM, twice its reach (he 15, AiGrenades.SAFE;
     * powder 10). A stand: one of the side 9.6 blocks off, in the open through the doorway, lost 9.5.
     */
    static final double HE_HURTS = AiGrenades.SAFE, POWDER_HURTS = 10;
    /** the stands: why a throw was not found: none lands in the room, one of the entry not behind the wall, another of its side in reach, a player */
    public static final int[] why = new int[5];

    /** an opening this wide at most is gone into so; wider, it is no doorway */
    static final int WIDE = 2;
    /** the building is read again for a side and a target this often at most, ticks */
    static final int AGAIN = 100;
    /** beside the way in they wait this long for a second one, and this long at most to get there; ticks */
    static final int WAIT_SECOND = 100, STACK_MAX = 400;
    /** in they go this long at most; an entry and what it knew is let go after this; ticks */
    static final int GO_MAX = 100, ENTRY_MAX = 1200;

    private static final int STACK = 0, SLICE = 1, FRAG = 2, WAIT = 3, HOLD = 4, GO = 5, OVER = 6;
    /**
     * Slicing the pie: before a grenade or going in, those of the stack at its ends step round the opening on its outer
     * side, far to near, at most SLICE_POINTS places SLICE_FAR blocks off at most, each showing cells of the room none of
     * the earlier did, its gun on them, SLICE_DWELL ticks at each; never on the opening's line (the thrower's). A foe seen,
     * or a shot at them, ends it: they fight as ever, the entry goes on with what is known (a grenade in). All of it
     * SLICE_MAX ticks at most. The rays once, as it begins.
     */
    static final int SLICE_POINTS = 4, SLICE_DWELL = 10, SLICE_MAX = 160, SLICE_NEW = 2;
    static final double SLICE_FAR = 6;
    /** where none is off the opening's line, a look from on it (off: the stands found it costs more than it shows) */
    static final boolean SLICE_LINE = false;
    /** the server setting: the opening looked into from round it before they go in */
    public static final String SLICE_KEY = "aiEntrySlice";
    /** off only for the stands' "before" */
    static boolean slice = true;
    /** the stands: slicings begun, places stood at, foes seen in the room by them */
    public static int slices, slicePoints, sliceSeen;
    /**
     * a foe in the room that shot one of the side down at its way in (TargetMemory.Contact.mateDown), no grenade to go in
     * first: they hold beside it, out of its line, and fight what shows; in when none goes after that foe for HOLD_STALE
     * ticks (forgotten, or seen and fought), or after HOLD_MAX
     */
    static final int HOLD_STALE = 40, HOLD_MAX = 400;
    /** the stands: entries held so, and gone in after their HOLD_MAX */
    public static int held, heldOut;

    private static final class Op {
        AiRooms.@Nullable Plan plan;
        AiRooms.@Nullable Way way;
        int room = AiRooms.OUTSIDE, phase = STACK, fragId = -1, tries, team;
        long made, phaseAt, nextPlan, lost, front = Long.MIN_VALUE;
        @Nullable AiBot thrower;
        /** those of it that were to throw and could not (down, no way out, out of time): not asked again */
        final List<AiBot> tried = new ArrayList<>(2);
        /** its thrower lost: since when another is looked for (MIN_VALUE: not), and why's counts then */
        long handing = Long.MIN_VALUE;
        int[] why0 = {};
        String lostHow = "";
        Vec3 aim = Vec3.ZERO;
        double hurts;
        /**
         * an entry into a room a foe fell in (no one known there now) whose grenade is not to go in: the room too big,
         * one of its side or one to be spared there; worked out again every FRAG_AGAIN ticks while it stacks
         */
        boolean noFrag;
        /** when noFrag was last worked out; when one of the side last went after a foe known of (alive) by this entry */
        long fragSeen = Long.MIN_VALUE, liveAt = Long.MIN_VALUE;
        /** when one of the side last went after a foe by this entry known by one of theirs it shot down (MIN_VALUE: none) */
        long funnelAt = Long.MIN_VALUE;
        /** its slicing: done (or none to do); those slicing, their places in order, the gun's aim at each, how far on, since when there */
        boolean sliced, sliceFoe, decided;
        /** the ways to the stack: cells before the opening seen from the room dear (none: as any) */
        PathSearch.Cost exposed = PathSearch.Cost.NONE;
        final List<AiBot> slicers = new ArrayList<>(2);
        final List<long[]> arcs = new ArrayList<>(2);
        final List<Vec3[]> arcAims = new ArrayList<>(2);
        int[] arcAt = {};
        long[] arcSince = {};
        /** its grenade (if one goes) is one into a room fought in (the stands) */
        boolean fragPlace;
        /** those to be spared by the room (spared), found at the tick sparedAt (safe) */
        final List<net.minecraft.world.entity.LivingEntity> spared = new ArrayList<>(2);
        long sparedAt = Long.MIN_VALUE;
        long[] slots = {}, inner = {};
        /** where each of the entry waits, by its number: {beside the way in, a grenade going in} */
        final List<long[]> places = new ArrayList<>(4);
        double offX, offZ;
        final List<AiBot> members = new ArrayList<>(2), done = new ArrayList<>(2);
        Vec3 door = Vec3.ZERO;
        /** the stands: those of it counted in the room before its grenade burst */
        final List<AiBot> inEarly = new ArrayList<>(2), offEarly = new ArrayList<>(2);
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
        try {
            Op op = find(bot, level, c, tick);
            AiRooms.Plan plan = op.plan;
            if (plan == null || op.way == null) return null;
            advance(op, level, tick);
            return step(op, plan, s, bot, level, tick);
        } catch (RuntimeException e) {
            failed(bot, level, c, tick, e);
            return null;
        }
    }

    /**
     * A fault in an entry: it does not stop the server. The entry for this side and target is put aside (one with no
     * room, kept as any other: none is made again for a while), the soldier searches as before; a line in the debug
     * log (AiRooms.fault).
     */
    private static void failed(AiBot bot, ServerLevel level, TargetMemory.Contact c, long tick, RuntimeException e) {
        long lost = cell(c.lostX, c.lostY, c.lostZ);
        AiRooms.fault("going into a room", lost, e);
        Op none = new Op();
        none.made = none.phaseAt = tick;
        none.nextPlan = tick + AGAIN;
        none.lost = lost;
        OPS.computeIfAbsent(level, l -> new HashMap<>()).put(key(bot, c), none);
    }

    private static long key(AiBot bot, TargetMemory.Contact c) {
        return (long) AiSquads.team(bot).hashCode() << 32 | c.id & 0xFFFFFFFFL;
    }

    /**
     * Whether the target was lost in a room an entry is, or would be, made into, the entry not over, with a grenade to
     * throw in first: its squad's flanker does not go round to where it sees the place (in a room that is through the
     * way in, ahead of the entry and in the grenade's reach).
     */
    static boolean roomed(AiBot bot, ServerLevel level, TargetMemory.Contact c, long tick) {
        if (!frag || !AiGrenades.on || !ReaimTimes.flag(FRAG_KEY, true)) return false;
        try {
            Op op = find(bot, level, c, tick);
            if (op.way == null || op.phase >= GO) return false;
            // no grenade to throw in: one in the room ahead of them spoils nothing
            if (bot.grenades > 0) return true;
            for (AiBot m : op.members) if (m.grenades > 0) return true;
            return false;
        } catch (RuntimeException e) {
            failed(bot, level, c, tick, e);
            return false;
        }
    }

    /** whether an entry has (or would have) a way into the room the target was lost in: it goes in by its drill (AiHatch's way round hands over here) */
    static boolean wayIn(AiBot bot, ServerLevel level, TargetMemory.Contact c, long tick) {
        if (!on()) return false;
        try {
            Op op = find(bot, level, c, tick);
            return op.way != null && op.phase != OVER;
        } catch (RuntimeException e) {
            failed(bot, level, c, tick, e);
            return false;
        }
    }

    private static Op find(AiBot bot, ServerLevel level, TargetMemory.Contact c, long tick) {
        Map<Long, Op> ops = OPS.computeIfAbsent(level, l -> new HashMap<>());
        long key = key(bot, c), lost = cell(c.lostX, c.lostY, c.lostZ);
        Op op = ops.get(key);
        boolean moved = op != null && lost != op.lost && (op.plan == null || op.plan.room(lost) != op.room);
        if (op == null || tick - op.made > ENTRY_MAX || moved && tick >= op.nextPlan && (op.phase == STACK || op.phase == OVER)) {
            if (ops.size() > 64) ops.values().removeIf(o -> tick - o.made > ENTRY_MAX);
            op = make(level, bot, lost, tick);
            op.team = (int) (key >> 32);
            // one entry a side and a way in: another target lost in the same room is gone after by the same ones
            if (op.way != null)
                for (Op o : ops.values())
                    // (not one gone in already: a foe found in there after is a room to take again, its grenade first)
                    if (o != op && o.team == op.team && o.front == op.front && o.way != null && o.phase < GO && tick - o.made <= ENTRY_MAX) {
                        op = o;
                        break;
                    }
            ops.put(key, op);
        }
        // a foe known of (alive) gone after by this entry: its drill as ever, a grenade in where one goes (the entry is
        // one a side, another target in the same room shares it)
        if (!c.place) {
            op.liveAt = tick;
            if (c.mateDown != Long.MIN_VALUE) op.funnelAt = tick;
        }
        // a room fought in (the foe known of fallen there): whether a grenade goes in, worked out again while they
        // stack (those of its side or to be spared step in and out), on the entry it has (one made while the foe was
        // alive, by AiSoldier's flank check, is the same entry)
        else if (op.way != null && op.phase == STACK && (op.fragSeen == Long.MIN_VALUE || tick - op.fragSeen >= FRAG_AGAIN)) {
            boolean first = op.fragSeen == Long.MIN_VALUE, no = !fragFought(op, level, bot, lost);
            op.fragSeen = tick;
            if (first || no != op.noFrag)
                fate("a room fought in: " + (no ? "no grenade" : "a grenade may go in") + ", room cells " + (op.plan == null || op.room < 0 ? -1 : op.plan.size[op.room]));
            op.noFrag = no;
        }
        return op;
    }

    /** when an entry last went to its throw (FRAG): none for longer than a throw and a hand-over, none is throwing */
    private static long fragLast = Long.MIN_VALUE;

    /**
     * One of an entry going to its throw (FRAG), this tick, a foe not in its sight (AiSoldier.decide): the throw through,
     * whatever else it heard or lost sight of; where it looks, or null (not one of such an entry, or not its thrower now).
     * A sound or a foe gone from sight turned its mind to them and none led it to the opening: the grenade was given up
     * at FRAG_MAX, handed on to one that never got the opening either, and in they went with none, into the foe's fire.
     * Each of the entry moves it on (a lost thrower's grenade handed on); its thrower steps out and throws, unless shot
     * at since its throw began.
     */
    static @Nullable Vec3 throwing(AiSoldier s, AiBot bot, ServerLevel level, long tick) {
        if (fragLast == Long.MIN_VALUE || tick < fragLast || tick - fragLast > FRAG_MAX + HAND_MAX + 20) return null;
        Map<Long, Op> ops = OPS.get(level);
        if (ops == null) return null;
        for (Op op : ops.values()) {
            if (op.phase != FRAG || !op.members.contains(bot) || tick - op.made > ENTRY_MAX) continue;
            try {
                advance(op, level, tick);
                // (shot at since its throw began: not out into that fire; it takes cover as ever, and at FRAG_MAX the
                // grenade goes to another of it)
                if (op.phase != FRAG || op.thrower != bot || bot.memory.shotTick() >= op.phaseAt) return null;
                frag(op, s, bot, level, tick);
                return op.door;
            } catch (RuntimeException e) {
                AiRooms.fault("an entry's throw", op.lost, e);
                phase(op, OVER, tick);
                return null;
            }
        }
        return null;
    }

    /**
     * One of an entry whose grenade is thrown and not burst yet: its place beside the way in, out of the blast, kept
     * whatever it sees (one in sight of a foe round the turn would step into the blast to fight); else null.
     */
    static @Nullable Long sheltered(AiBot bot, ServerLevel level) {
        Map<Long, Op> ops = OPS.get(level);
        if (ops == null) return null;
        for (Op op : ops.values()) {
            if (op.phase != WAIT) continue;
            int i = op.members.indexOf(bot);
            // (the wait goes on by those sheltering too: with all of the squad in the entry, none else thinks for it)
            if (i >= 0) {
                advance(op, level, level.getGameTime());
                if (op.phase != WAIT) return null;
                i = op.members.indexOf(bot);
            }
            if (i >= 0 && i < op.places.size()) return op.places.get(i)[1];
        }
        return null;
    }

    /**
     * Another of the side of an entry whose grenade is out, not burst yet (not of that entry): in the reach the blast
     * hurts in, all of it behind blocks from the burst where it stands. It stays there, whatever it sees, till the
     * burst: a search led one that stood behind the house's wall along it into the line through the doorway, out
     * of cover in the reach, and a blast through the doorway hurt it (the stands). In the open there it runs from the
     * grenade instead (keeps, AiSoldier.dodge). One of an entry of its own going on is not held: its drill goes on (a
     * thrower stopped so would be lost before its throw).
     */
    static boolean holds(AiBot bot, ServerLevel level) {
        Map<Long, Op> ops = OPS.get(level);
        if (ops == null) return false;
        Mob m = bot.mob;
        int team = Integer.MIN_VALUE;
        boolean any = false;
        for (Op op : ops.values()) {
            if (op.phase < OVER && (op.members.contains(bot) || op.thrower == bot)) return false;
            any |= op.phase == WAIT;
        }
        if (!any) return false;
        for (Op op : ops.values()) {
            if (op.phase != WAIT) continue;
            // its side's entry (the side, not the grenade's owner: one shot down after its throw is no owner of it)
            if (team == Integer.MIN_VALUE) team = AiSquads.team(bot).hashCode();
            if (op.team != team) continue;
            Entity g = level.getEntity(op.fragId);
            if (g == null || g.isRemoved()) continue;
            Vec3 b = AiGrenades.predicted(level, g);
            if (b == null) b = op.aim;
            if (m.distanceToSqr(b) < op.hurts * op.hurts && AiGrenades.shielded(AiBots.voxels(level), m.getX(), m.getY(), m.getZ(), m.getBbHeight(), b)) return true;
        }
        return false;
    }

    /** whether it is one of an entry going on (or its thrower): not held by another's grenade (AiBunker.holds) */
    static boolean going(AiBot bot, ServerLevel level) {
        Map<Long, Op> ops = OPS.get(level);
        if (ops == null) return false;
        for (Op op : ops.values()) if (op.phase < OVER && (op.members.contains(bot) || op.thrower == bot)) return true;
        return false;
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
        int k = op.phase == SLICE ? op.slicers.indexOf(bot) : -1;
        if (k >= 0) return sliceStep(op, k, bot, tick);
        if (op.phase == GO) {
            long to = op.inner.length > 0 ? op.inner[i % op.inner.length] : plan.mid[op.room];
            go(bot, to, 0.5);
            if (bot.mover.arrived() || bot.mover.failed()) op.done.add(bot);
            // the dead corners by the wall with the opening, not seen from round it: the first two each to its own
            Vec3 at = slice() ? corner(op, plan, i) : null;
            if (at != null) return at;
        } else if (op.phase == FRAG && bot == op.thrower) frag(op, s, bot, level, tick);
        else {
            long at = place(op, i);
            go(bot, at, 0.35, op.exposed);
            // at its place beside the way in: the gun held up close till they go (AiPose.STACK)
            if (bot.mover.goalIs(at) && bot.mover.arrived()) bot.stackUntil = tick + 5;
        }
        return op.door;
    }

    /** where one of the entry waits: beside the way in; a grenade going in, a cell further from it along the wall */
    private static long place(Op op, int i) {
        return op.places.get(i)[op.phase == FRAG || op.phase == WAIT ? 1 : 0];
    }

    private static void go(AiBot bot, long cell, double radius) {
        go(bot, cell, radius, PathSearch.Cost.NONE);
    }

    /** to it by the way costs makes cheapest (the cells seen from the room dear, on the way to the stack) */
    private static void go(AiBot bot, long cell, double radius, PathSearch.Cost costs) {
        if (bot.mover.goalIs(cell)) return;
        bot.mover.costs(costs);
        bot.mover.goTo(cell, radius, AiSoldier.RUN);
    }

    /** the room the target was lost in, the nearest way into it one or two wide with a place beside it, and the places */
    private static Op make(ServerLevel level, AiBot bot, long lost, long tick) {
        Op op = new Op();
        op.made = op.phaseAt = tick;
        op.nextPlan = tick + AGAIN;
        op.lost = lost;
        AiRooms.Plan plan = AiTrenches.place(TacticalMap.of(level), lost);
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
        if (hidden()) hide(op, level, plan, w, room);
        // looked at from beside it: the opening's cell at the far end from the room; before it, the cell a thrower steps
        // out to (at a trench's turn, the turn)
        long end = room == w.b ? w.cells[0] : w.cells[w.cells.length - 1];
        int in = w.in(room);
        op.door = mid(end, 1.1);
        op.front = w.front(room);
        // the thrower stands so that its hand (a quarter of a block to its right), not its middle, is before the opening's
        // middle; in a trench's turn, one wide, in its middle (off it, the hand is at the wall)
        if (w.turn < 0) {
            op.offX = 0.25 * Dirs.DZ[in];
            op.offZ = -0.25 * Dirs.DX[in];
        }
        // where a grenade goes: straight in through the opening, DEEP cells into the room (the room's corners by the wall
        // with the way in cannot be thrown at from before it; the burst reaches them)
        // (round a turn two wide, from the cell thrown from, over the rest of the turn)
        long first = w.turn >= 0 ? op.front : room == w.b ? w.cells[w.cells.length - 1] : w.cells[0];
        for (int k = 1, got = 0; k <= DEEP + 1 && got < DEEP; k++) {
            long c = BlockPos.asLong(BlockPos.getX(first) + Dirs.DX[in] * k, BlockPos.getY(first), BlockPos.getZ(first) + Dirs.DZ[in] * k);
            if (plan.room(c) != room) {
                if (got > 0 || w.turn < 0) break;
                continue;
            }
            op.aim = mid(c, 0);
            got++;
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
                // a foe gone after in there a moment ago: its grenade first, the slicing after it; else the slicing
                // first (a foe it shows: the grenade then)
                if (all && !live(op, tick) && sliceNow(op, level, tick)) return;
                decide(op, level, tick, all);
            }
            case SLICE -> {
                boolean over = tick - op.phaseAt > SLICE_MAX, going = false;
                for (int k = 0; k < op.slicers.size(); k++) {
                    AiBot b = op.slicers.get(k);
                    if (!AiSquads.live(b)) continue;
                    // a foe in its sight or a shot at it: the slicing is over, they fight as ever
                    if (b.memory.shotTick() >= op.phaseAt || seen(op, b)) {
                        over = true;
                        break;
                    }
                    if (op.arcAt[k] < op.arcs.get(k).length) going = true;
                }
                if (!over && going) return;
                for (AiBot b : op.slicers) if (AiSquads.live(b)) aimAtSeen(op, b);
                if (!op.decided) decide(op, level, tick, true);
                else if (op.sliceFoe) again(op, level, tick);
                else phase(op, GO, tick);
            }
            default -> advanceRest(op, level, tick);
        }
    }

    /** a foe known of gone after by it a moment ago, since the room was last weighed as one fought in (not a room fought in only) */
    private static boolean live(Op op, long tick) {
        return op.liveAt != Long.MIN_VALUE && op.liveAt >= op.fragSeen && tick - op.liveAt <= FRAG_AGAIN;
    }

    /** the slicing begun, if not done yet and there is one to do; false: none (on to what comes next) */
    private static boolean sliceNow(Op op, ServerLevel level, long tick) {
        if (op.sliced) return false;
        op.sliced = true;
        if (!slice() || !slice(op, level)) return false;
        slices++;
        phase(op, SLICE, tick);
        return true;
    }

    /** in: through the slicing first, if not done yet */
    private static void go(Op op, ServerLevel level, long tick) {
        if (!sliceNow(op, level, tick)) phase(op, GO, tick);
    }

    /**
     * The stack out of sight of the room: a place beside the opening seen from a cell of it (a foe by the side wall
     * across the room sees one flush with the wall beside an opening two wide, a slant through it) goes on along the
     * wall away from it, STACK_OUT cells at most, to the first seen from none; none such, the least seen. Each place
     * its own cell, none in the line from another's to the opening. Once, as the entry is made; glass hides nothing.
     */
    static final int STACK_OUT = 4;
    public static final String STACK_KEY = "aiEntryStackHidden";
    static boolean stackHidden = true;
    /** the stands: places moved out of sight, places left in it (none out of it near enough) */
    public static int stackMoved, stackSeen;
    /** the stands: entries all of whose went in, and their ticks from in to all of them in their places there */
    public static int goes;
    public static long goTicks;

    static boolean hidden() {
        return stackHidden && ReaimTimes.flag(STACK_KEY, true);
    }

    /** the ways to the stack: a cell before the opening seen from the room costs this many more, EXPOSED_FAR blocks off at most */
    static final float EXPOSED_COST = 4;
    static final int EXPOSED_FAR = 6;

    /** hide()'s work for a plan's way in to a room, kept while the plan is: the places as moved, the ways' cost */
    private record Hidden(long[][] places, PathSearch.Cost cost) {
    }

    private static final Map<AiRooms.Plan, Map<Long, Hidden>> HIDDEN = new WeakHashMap<>();

    private static void hide(Op op, ServerLevel level, AiRooms.Plan plan, AiRooms.Way w, int room) {
        long key = (op.front * 31) ^ room;
        Hidden h;
        synchronized (HIDDEN) {
            h = HIDDEN.computeIfAbsent(plan, p -> new HashMap<>()).get(key);
        }
        if (h != null && h.places.length == op.places.size()) {
            for (int i = 0; i < h.places.length; i++) op.places.set(i, h.places[i].clone());
            op.exposed = h.cost;
            return;
        }
        hide0(op, level, plan, w, room);
        long[][] kept = new long[op.places.size()][];
        for (int i = 0; i < kept.length; i++) kept[i] = op.places.get(i).clone();
        synchronized (HIDDEN) {
            HIDDEN.get(plan).put(key, new Hidden(kept, op.exposed));
        }
    }

    private static void hide0(Op op, ServerLevel level, AiRooms.Plan plan, AiRooms.Way w, int room) {
        if (room < 0 || plan.size[room] > SLICE_CELLS) {
            fate("stack: the room too big to weigh what it sees (" + (room < 0 ? -1 : plan.size[room]) + " cells)");
            return;
        }
        long[] cells = plan.cells()[room];
        VoxelRay.Source src = AiBots.voxels(level);
        long end = room == w.b ? w.cells[0] : w.cells[w.cells.length - 1];
        // before the opening, the cells seen from the room: dear on the ways to the stack (they come round to it, not
        // across what the room sees)
        int out = Dirs.opposite(w.in(room)), ex = BlockPos.getX(end), ey = BlockPos.getY(end), ez = BlockPos.getZ(end);
        it.unimi.dsi.fastutil.longs.LongOpenHashSet dear = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int dx = -EXPOSED_FAR; dx <= EXPOSED_FAR; dx++)
            for (int dz = -EXPOSED_FAR; dz <= EXPOSED_FAR; dz++) {
                if (dx * Dirs.DX[out] + dz * Dirs.DZ[out] < 1 || dx * dx + dz * dz > EXPOSED_FAR * EXPOSED_FAR) continue;
                long c = BlockPos.asLong(ex + dx, ey, ez + dz);
                if (plan.room(c) == room || !stand(src, c)) continue;
                if (seen(src, cells, c, 1) > 0) dear.add(c);
            }
        if (!dear.isEmpty()) op.exposed = (x, y, z) -> y == ey && dear.contains(BlockPos.asLong(x, y, z)) ? EXPOSED_COST : 0F;
        Vec3 mouth = mid(end, 1.1);
        java.util.Set<Long> taken = new java.util.HashSet<>();
        for (int i = 0; i < op.places.size(); i++) {
            long[] pl = op.places.get(i);
            int seen = seen(src, cells, pl[0]);
            if (seen == 0 && taken.add(pl[0])) continue;
            // on along the wall: the way from the place to the one behind it (else away from the opening)
            int dx = Integer.signum(BlockPos.getX(pl[1]) - BlockPos.getX(pl[0])), dz = Integer.signum(BlockPos.getZ(pl[1]) - BlockPos.getZ(pl[0]));
            if (dx == 0 && dz == 0) {
                dx = Integer.signum(BlockPos.getX(pl[0]) - BlockPos.getX(end));
                dz = Integer.signum(BlockPos.getZ(pl[0]) - BlockPos.getZ(end));
                if (dx != 0 && dz != 0) dz = 0;
            }
            long best = taken.contains(pl[0]) ? Long.MIN_VALUE : pl[0], c = pl[0];
            int least = taken.contains(pl[0]) ? Integer.MAX_VALUE : seen;
            for (int k = 0; k < STACK_OUT && least > 0; k++) {
                long n = BlockPos.offset(c, dx, 0, dz);
                if (!stand(src, n) || plan.room(n) >= 0 && plan.room(n) == room) break;
                c = n;
                if (taken.contains(c) || inLine(op, i, c, mouth)) continue;
                int sn = seen(src, cells, c);
                if (sn < least) {
                    least = sn;
                    best = c;
                }
            }
            if (best == Long.MIN_VALUE) continue;
            if (best != pl[0]) {
                // (where it steps back to for the grenade: on beyond it, a cell of its own out of the room; else there)
                long behind = BlockPos.offset(best, dx, 0, dz);
                boolean free = stand(src, behind) && plan.room(behind) != room && !taken.contains(behind);
                for (long[] o : op.places) free &= o == pl || o[0] != behind;
                pl[0] = best;
                pl[1] = free ? behind : best;
                stackMoved++;
            }
            if (least > 0) stackSeen++;
            taken.add(pl[0]);
        }
        StringBuilder f = new StringBuilder("stack:");
        for (long[] pl : op.places) f.append(' ').append(BlockPos.getX(pl[0])).append(' ').append(BlockPos.getZ(pl[0])).append(" seen by ").append(seen(src, cells, pl[0])).append(';');
        fate(f + " before the opening dear " + dear.size());
    }

    /** a soldier may stand there: room for it, ground under it */
    private static boolean stand(VoxelRay.Source src, long c) {
        int x = BlockPos.getX(c), y = BlockPos.getY(c), z = BlockPos.getZ(c);
        return Voxel.open(src.voxel(x, y, z)) && Voxel.open(src.voxel(x, y + 1, z)) && !Voxel.open(src.voxel(x, y - 1, z));
    }

    /** the room's cells that see one standing there (its chest or head, from theirs) */
    private static int seen(VoxelRay.Source src, long[] cells, long at) {
        return seen(src, cells, at, Integer.MAX_VALUE);
    }

    /** the same, counted up to enough at most */
    private static int seen(VoxelRay.Source src, long[] cells, long at, int enough) {
        double ax = BlockPos.getX(at) + 0.5, ay = BlockPos.getY(at), az = BlockPos.getZ(at) + 0.5;
        int n = 0;
        for (long r : cells) {
            double rx = BlockPos.getX(r) + 0.5, ry = BlockPos.getY(r), rz = BlockPos.getZ(r) + 0.5;
            if ((VoxelRay.clear(src, rx, ry + 1.5, rz, ax, ay + 1.5, az, Voxel.SIGHT) || VoxelRay.clear(src, rx, ry + 1.0, rz, ax, ay + 1.0, az, Voxel.SIGHT)) && ++n >= enough) break;
        }
        return n;
    }

    /** a place there would stand in the line from one of the places before it to the opening (its gun on it) */
    private static boolean inLine(Op op, int upTo, long c, Vec3 mouth) {
        double cx = BlockPos.getX(c) + 0.5, cz = BlockPos.getZ(c) + 0.5;
        for (int j = 0; j < upTo; j++) {
            long p = op.places.get(j)[0];
            double px = BlockPos.getX(p) + 0.5, pz = BlockPos.getZ(p) + 0.5, vx = mouth.x - px, vz = mouth.z - pz, len = vx * vx + vz * vz;
            if (len < 1e-6) continue;
            double t = ((cx - px) * vx + (cz - pz) * vz) / len;
            if (t <= 0 || t >= 1) continue;
            double ox = px + vx * t - cx, oz = pz + vz * t - cz;
            if (ox * ox + oz * oz < 0.5 * 0.5) return true;
        }
        return false;
    }

    static boolean slice() {
        return slice && ReaimTimes.flag(SLICE_KEY, true);
    }

    /** a room this big at most is sliced (its cells looked at from each place round the opening, once) */
    static final int SLICE_CELLS = 96;

    /**
     * The slicing's places for those of the stack at each end of the opening (one a side): from where on the outer
     * side, SLICE_FAR blocks off at most, the room's cells are seen through it, picked so each shows SLICE_NEW or more
     * none before did, gone through from the furthest from the opening to the nearest; the gun at each on what it
     * shows first. Where none is off the opening's line (a passage along the wall it is in): none (SLICE_LINE). False
     * when there is none to do (a turn, a big room, nothing seen from round it).
     */
    private static boolean slice(Op op, ServerLevel level) {
        AiRooms.Plan plan = op.plan;
        AiRooms.Way w = op.way;
        if (plan == null || w == null || w.turn >= 0 || op.room < 0 || plan.size[op.room] > SLICE_CELLS || op.members.isEmpty()) return false;
        long end = op.room == w.b ? w.cells[0] : w.cells[w.cells.length - 1];
        int in = w.in(op.room), lx = -Dirs.DZ[in], lz = Dirs.DX[in];
        int ex = BlockPos.getX(end), ez = BlockPos.getZ(end);
        int[] band = band(w, end, in);
        boolean[] sides = new boolean[3];
        for (int i = 0; i < op.members.size(); i++) {
            AiBot b = op.members.get(i);
            int lat = (Mth.floor(b.mob.getX()) - ex) * lx + (Mth.floor(b.mob.getZ()) - ez) * lz;
            int side = lat > band[1] ? 1 : lat < band[0] ? -1 : 0;
            if (side == 0 || sides[side + 1]) continue;
            sides[side + 1] = true;
            arc(op, level, b, side, SLICE_POINTS);
        }
        // (none off the opening's line, as in a passage along its wall: none, not a look from the line, the lane of fire of
        // those in there; looked into so on the stands, more fell by it, none in there was seen more)
        if (op.slicers.isEmpty() && SLICE_LINE) arc(op, level, op.members.get(0), 0, 1);
        op.arcAt = new int[op.slicers.size()];
        op.arcSince = new long[op.slicers.size()];
        java.util.Arrays.fill(op.arcSince, Long.MIN_VALUE);
        return !op.slicers.isEmpty();
    }

    /**
     * across the opening, the sideways reach of its cells (from end, along the left of in): one wide 0..0, two or three
     * wide all its lanes (Way.cells are its lanes one after another)
     */
    private static int[] band(AiRooms.Way w, long end, int in) {
        int lx = -Dirs.DZ[in], lz = Dirs.DX[in], lo = 0, hi = 0;
        for (long c : w.cells) {
            int dx = BlockPos.getX(c) - BlockPos.getX(end), dz = BlockPos.getZ(c) - BlockPos.getZ(end);
            // (the cells level with end across the way: its lanes' ends on the room's side)
            if (dx * Dirs.DX[in] + dz * Dirs.DZ[in] != 0) continue;
            int lat = dx * lx + dz * lz;
            lo = Math.min(lo, lat);
            hi = Math.max(hi, lat);
        }
        return new int[] {lo, hi};
    }

    /** one's places round the opening on its side (0: on the opening's line), at most points of them; none, none added */
    private static void arc(Op op, ServerLevel level, AiBot bot, int side, int points) {
        AiRooms.Plan plan = op.plan;
        AiRooms.Way w = op.way;
        long end = op.room == w.b ? w.cells[0] : w.cells[w.cells.length - 1];
        int in = w.in(op.room), out = Dirs.opposite(in), lx = -Dirs.DZ[in], lz = Dirs.DX[in];
        int ex = BlockPos.getX(end), ey = BlockPos.getY(end), ez = BlockPos.getZ(end);
        long[] cells = plan.cells()[op.room];
        VoxelRay.Source src = AiBots.voxels(level);
        Vec3 mouth = mid(end, 1.0);
        int[] band = band(w, end, in);
        // the places on its side, each with the room's cells it sees
        List<long[]> spots = new ArrayList<>();
        List<java.util.BitSet> sees = new ArrayList<>();
        int r = (int) SLICE_FAR;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                int depth = dx * Dirs.DX[out] + dz * Dirs.DZ[out], lat = dx * lx + dz * lz;
                // (off the opening's line where there is room for it: that is the lane of fire of one in there
                // waiting for it, and the thrower's)
                boolean line = lat >= band[0] && lat <= band[1];
                if (depth < 1 || (side == 0 ? !line : line || lat * side < 0) || dx * dx + dz * dz > SLICE_FAR * SLICE_FAR) continue;
                int x = ex + dx, z = ez + dz;
                long c = BlockPos.asLong(x, ey, z);
                if (plan.room(c) == op.room || !Voxel.open(src.voxel(x, ey, z)) || !Voxel.open(src.voxel(x, ey + 1, z)) || Voxel.open(src.voxel(x, ey - 1, z)))
                    continue;
                // (the room's cells it sees, not the opening's middle: from a wide angle that is behind its jamb)
                double eyeY = ey + 1.62;
                java.util.BitSet seen = new java.util.BitSet(cells.length);
                for (int k = 0; k < cells.length; k++) {
                    Vec3 t = mid(cells[k], 1.0);
                    if (VoxelRay.clear(src, x + 0.5, eyeY, z + 0.5, t.x, t.y, t.z, Voxel.SIGHT)) seen.set(k);
                }
                if (seen.isEmpty()) continue;
                spots.add(new long[] {c, depth, lat});
                sees.add(seen);
            }
        // the ones that show most none before did, at most points
        java.util.BitSet all = new java.util.BitSet(cells.length);
        List<Integer> picked = new ArrayList<>();
        while (picked.size() < points) {
            int best = -1, most = SLICE_NEW - 1;
            for (int k = 0; k < spots.size(); k++) {
                if (picked.contains(k)) continue;
                java.util.BitSet n = (java.util.BitSet) sees.get(k).clone();
                n.andNot(all);
                if (n.cardinality() > most) {
                    most = n.cardinality();
                    best = k;
                }
            }
            if (best < 0) break;
            picked.add(best);
            all.or(sees.get(best));
        }
        if (picked.isEmpty()) return;
        // far to near: the furthest from the opening first (it shows a sliver of the room, from where one in there sees a
        // sliver of it), by the jamb last; at one distance the widest angle first
        picked.sort((a, b) -> {
            long[] pa = spots.get(a), pb = spots.get(b);
            int c = Long.compare(pb[1] * pb[1] + pb[2] * pb[2], pa[1] * pa[1] + pa[2] * pa[2]);
            return c != 0 ? c : Double.compare(Math.abs(Math.atan2(pb[2], pb[1])), Math.abs(Math.atan2(pa[2], pa[1])));
        });
        long[] arc = new long[picked.size()];
        Vec3[] aims = new Vec3[picked.size()];
        java.util.BitSet shown = new java.util.BitSet(cells.length);
        for (int k = 0; k < arc.length; k++) {
            int q = picked.get(k);
            arc[k] = spots.get(q)[0];
            java.util.BitSet n = (java.util.BitSet) sees.get(q).clone();
            n.andNot(shown);
            if (n.isEmpty()) n = sees.get(q);
            shown.or(sees.get(q));
            // (on the opening's line: the gun on the far end of what it shows, where the corners are; round it, on the edge
            // of what it shows new nearest the wall the opening is in: one waiting by that wall comes into sight there,
            // straight ahead, not in the corner of the eye)
            n = side == 0 ? far(n, cells, mouth) : edge(n, cells, end, in);
            double sx = 0, sy = 0, sz = 0;
            for (int b = n.nextSetBit(0); b >= 0; b = n.nextSetBit(b + 1)) {
                Vec3 t = mid(cells[b], 1.2);
                sx += t.x;
                sy += t.y;
                sz += t.z;
            }
            int m = n.cardinality();
            aims[k] = new Vec3(sx / m, sy / m, sz / m);
        }
        StringBuilder f = new StringBuilder("slicing, side " + side + ":");
        for (int k = 0; k < arc.length; k++)
            f.append(String.format(java.util.Locale.ROOT, " %d %d (aim %.1f %.1f)", BlockPos.getX(arc[k]), BlockPos.getZ(arc[k]), aims[k].x, aims[k].z));
        fate(f.toString());
        op.slicers.add(bot);
        op.arcs.add(arc);
        op.arcAims.add(aims);
    }

    /** of the cells seen, those nearest the wall the opening is in (the fewest steps in from it), alone */
    private static java.util.BitSet edge(java.util.BitSet n, long[] cells, long end, int in) {
        int least = Integer.MAX_VALUE;
        for (int b = n.nextSetBit(0); b >= 0; b = n.nextSetBit(b + 1))
            least = Math.min(least, depthIn(cells[b], end, in));
        java.util.BitSet near = new java.util.BitSet();
        for (int b = n.nextSetBit(0); b >= 0; b = n.nextSetBit(b + 1))
            if (depthIn(cells[b], end, in) == least) near.set(b);
        return near;
    }

    private static int depthIn(long c, long end, int in) {
        return (BlockPos.getX(c) - BlockPos.getX(end)) * Dirs.DX[in] + (BlockPos.getZ(c) - BlockPos.getZ(end)) * Dirs.DZ[in];
    }

    /** of the cells seen, the one furthest to the side of the opening (a corner by its wall), alone */
    private static java.util.BitSet far(java.util.BitSet n, long[] cells, Vec3 mouth) {
        int best = n.nextSetBit(0);
        double most = -1;
        for (int b = best; b >= 0; b = n.nextSetBit(b + 1)) {
            Vec3 t = mid(cells[b], 0);
            double d = Math.abs(t.x - mouth.x) + Math.abs(t.z - mouth.z);
            if (d > most) {
                most = d;
                best = b;
            }
        }
        java.util.BitSet one = new java.util.BitSet();
        one.set(best);
        return one;
    }

    /** one slicing: to its next place, slowly, its gun on what that shows; a while there, then on */
    private static Vec3 sliceStep(Op op, int k, AiBot bot, long tick) {
        long[] arc = op.arcs.get(k);
        Vec3[] aims = op.arcAims.get(k);
        int a = op.arcAt[k];
        if (a >= arc.length) {
            if (!bot.mover.idle()) bot.mover.stop();
            return aims[aims.length - 1];
        }
        if (!bot.mover.goalIs(arc[a])) {
            bot.mover.costs(PathSearch.Cost.NONE);
            bot.mover.goTo(arc[a], 0.35, AiSoldier.CREEP);
        }
        if (bot.mover.failed()) {
            op.arcAt[k]++;
            op.arcSince[k] = Long.MIN_VALUE;
        } else if (bot.mover.arrived()) {
            if (op.arcSince[k] == Long.MIN_VALUE) {
                op.arcSince[k] = tick;
                slicePoints++;
            } else if (tick - op.arcSince[k] >= SLICE_DWELL) {
                op.arcAt[k]++;
                op.arcSince[k] = Long.MIN_VALUE;
            }
        }
        return aims[Math.min(a, aims.length - 1)];
    }

    /** a foe in its sight now */
    private static boolean seen(Op op, AiBot b) {
        for (TargetMemory.Contact c : b.memory.contacts()) if (c.state == TargetMemory.State.VISIBLE) return true;
        return false;
    }

    /** a foe it saw in the room: a grenade goes in (thrown as ever, straight in: its burst takes the room) */
    private static void aimAtSeen(Op op, AiBot b) {
        if (op.plan == null) return;
        for (TargetMemory.Contact c : b.memory.contacts()) {
            if (c.state != TargetMemory.State.VISIBLE || op.plan.room(cell(c.x, c.y + 0.2, c.z)) != op.room) continue;
            if (!op.sliceFoe) sliceSeen++;
            op.sliceFoe = true;
        }
    }

    /** going in, the first two each to its side: the gun on the far corner along the wall the opening is in; else null */
    private static @Nullable Vec3 corner(Op op, AiRooms.Plan plan, int i) {
        AiRooms.Way w = op.way;
        if (w == null || i >= 2 || op.inner.length == 0) return null;
        long start = op.inner[i % op.inner.length], mouth = op.room == w.b ? w.cells[w.cells.length - 1] : w.cells[0];
        int in = w.in(op.room), lx = -Dirs.DZ[in], lz = Dirs.DX[in];
        int side = Integer.signum((BlockPos.getX(start) - BlockPos.getX(mouth)) * lx + (BlockPos.getZ(start) - BlockPos.getZ(mouth)) * lz);
        if (side == 0) return null;
        long c = start;
        for (int k = 0; k < 16; k++) {
            long n = BlockPos.asLong(BlockPos.getX(c) + lx * side, BlockPos.getY(c), BlockPos.getZ(c) + lz * side);
            if (plan.room(n) != op.room) break;
            c = n;
        }
        return c == start ? null : mid(c, 1.1);
    }

    /**
     * the slicing after the grenade showed a foe in there alive: another grenade if one of them has one and a throw is
     * found, else held by the way in while it is in their sight (not in at it)
     */
    private static void again(Op op, ServerLevel level, long tick) {
        boolean armed = false;
        for (AiBot b : op.members) armed |= b.grenades > 0;
        op.thrower = armed ? thrower(op, level, tick) : null;
        if (op.thrower != null) {
            fate("the slicing showed a foe in there: a grenade again");
            op.tries = 0;
            op.handing = Long.MIN_VALUE;
            phase(op, FRAG, tick);
            return;
        }
        held++;
        op.funnelAt = tick;
        fate("the slicing showed a foe in there: held by the way in");
        phase(op, HOLD, tick);
    }

    /** one of the entry has a foe in the room in its sight */
    private static boolean inSight(Op op) {
        if (op.plan == null) return false;
        for (AiBot b : op.members)
            for (TargetMemory.Contact c : b.memory.contacts())
                if (c.state == TargetMemory.State.VISIBLE && op.plan.room(cell(c.x, c.y + 0.2, c.z)) == op.room) return true;
        return false;
    }

    /** after the stack (and its slicing): a grenade in, or in without one (or held by the way in) */
    private static void decide(Op op, ServerLevel level, long tick, boolean all) {
        op.decided = true;
        // (in before all were beside it: late already, no slicing after)
        if (!all) op.sliced = true;
        entries++;
        int[] w0 = why.clone();
        boolean live = live(op, tick) || op.sliceFoe;
        boolean noFrag = op.noFrag && !live;
        op.fragPlace = op.fragSeen != Long.MIN_VALUE && !live;
        op.thrower = all && !noFrag ? thrower(op, level, tick) : null;
        if (noFrag) {
            noFragFought++;
            fate("a room fought in, no grenade: too big, or one of its side or one to be spared there");
        } else if (!all) {
            unstacked++;
            fate("in before all were beside it (" + op.members.size() + " there)");
        } else if (op.thrower == null) {
            noPlan++;
            fate("no throw found: none in the room " + (why[0] - w0[0]) + ", one of it not behind the wall " + (why[1] - w0[1]) + ", another of its side " + (why[2] - w0[2])
                    + ", a player " + (why[3] - w0[3]) + ", one to be spared " + (why[4] - w0[4]) + "; with a grenade " + armed(op));
        }
        if (op.thrower != null) phase(op, FRAG, tick);
        else in(op, level, tick);
        if (op.thrower != null && firstThrower != null) firstThrower.accept(op.thrower);
    }

    private static void advanceRest(Op op, ServerLevel level, long tick) {
        switch (op.phase) {
            case FRAG -> {
                if (op.handing != Long.MIN_VALUE) {
                    hand(op, level, tick);
                    return;
                }
                AiBot t = op.thrower;
                boolean down = t == null || !AiSquads.live(t), stuck = !down && t.mover.goal() == op.front && t.mover.failed(), late = tick - op.phaseAt > FRAG_MAX;
                if (down || stuck || late) {
                    // the thrower lost before its throw (shot stepping out, no way out before the opening, out of time):
                    // another of it with a grenade throws, if one has a throw; only then in without one
                    throwerLost++;
                    if (t != null) op.tried.add(t);
                    op.tries = 0;
                    op.thrower = null;
                    op.handing = tick;
                    op.why0 = why.clone();
                    op.lostHow = down ? "down" : stuck ? "with no way out" : "out of time";
                    hand(op, level, tick);
                }
            }
            case WAIT -> {
                // (the stands: one of it in the room before its own grenade has burst)
                if (op.plan != null)
                    for (AiBot b : op.members)
                        if (!op.inEarly.contains(b) && op.plan.room(cell(b.mob.getX(), b.mob.getY(), b.mob.getZ())) == op.room) {
                            op.inEarly.add(b);
                            inBeforeBlast++;
                        }
                if (tick - op.phaseAt > WAIT_GRACE)
                    for (int i = 0; i < op.members.size() && i < op.places.size(); i++) {
                        AiBot b = op.members.get(i);
                        long c = op.places.get(i)[1];
                        double dx = b.mob.getX() - BlockPos.getX(c) - 0.5, dz = b.mob.getZ() - BlockPos.getZ(c) - 0.5;
                        if (!op.offEarly.contains(b) && dx * dx + dz * dz > OFF_PLACE * OFF_PLACE) {
                            op.offEarly.add(b);
                            offBeforeBlast++;
                        }
                    }
                Entity g = level.getEntity(op.fragId);
                if (g == null || g.isRemoved() || tick - op.phaseAt > AiGrenades.FUSE + 10) go(op, level, tick);
            }
            case HOLD -> {
                // (a foe of the room in sight of one of them: still held)
                if (inSight(op)) op.funnelAt = tick;
                boolean stale = tick - op.funnelAt > HOLD_STALE, late = tick - op.phaseAt > HOLD_MAX;
                if (late) heldOut++;
                if (stale || late) {
                    fate("held by the way in " + (tick - op.phaseAt) + " ticks, in: " + (late ? "out of time" : "the foe no more gone after"));
                    go(op, level, tick);
                }
            }
            case GO -> {
                if (op.done.containsAll(op.members) || tick - op.phaseAt > GO_MAX) {
                    // (the stands: from in to all of it there, those it went in with)
                    if (op.done.containsAll(op.members) && !op.members.isEmpty()) {
                        goTicks += tick - op.phaseAt;
                        goes++;
                    }
                    phase(op, OVER, tick);
                }
            }
            default -> {
            }
        }
    }

    private static void phase(Op op, int phase, long tick) {
        op.phase = phase;
        op.phaseAt = tick;
        if (phase == FRAG) fragLast = tick;
    }

    /** a lost thrower's grenade to another of the entry: looked for as soon as a throw may be worked out this tick, for HAND_MAX at most */
    static final int HAND_MAX = 20;

    private static void hand(Op op, ServerLevel level, long tick) {
        int armed = 0;
        for (AiBot b : op.members) if (b.grenades > 0 && !op.tried.contains(b)) armed++;
        if (armed > 0 && !AiGrenades.planFree(level, tick) && tick - op.handing < HAND_MAX) return;
        op.thrower = armed > 0 ? thrower(op, level, tick) : null;
        // a soldier reloading or lying may be ready in a moment
        if (op.thrower == null && armed > 0 && tick - op.handing < HAND_MAX && why[0] == op.why0[0] && why[1] == op.why0[1] && why[2] == op.why0[2] && why[3] == op.why0[3] && why[4] == op.why0[4]) return;
        op.handing = Long.MIN_VALUE;
        if (op.thrower != null) {
            handedOn++;
            fate("thrower " + op.lostHow + ", handed on");
            phase(op, FRAG, tick);
            return;
        }
        fate("thrower " + op.lostHow + ", in without one: " + (armed == 0 ? "none else with a grenade" : "no throw found: none in the room " + (why[0] - op.why0[0])
                + ", one of it not behind the wall " + (why[1] - op.why0[1]) + ", another of its side " + (why[2] - op.why0[2]) + ", a player " + (why[3] - op.why0[3]) + ", one to be spared " + (why[4] - op.why0[4])));
        in(op, level, tick);
    }

    /** in with no grenade: at once, or held beside the way in while a foe that shot one of them down there is gone after */
    private static void in(Op op, ServerLevel level, long tick) {
        if (op.funnelAt != Long.MIN_VALUE && tick - op.funnelAt <= HOLD_STALE) {
            held++;
            fate("held by the way in: a foe in there shot one of them down, no grenade");
            phase(op, HOLD, tick);
        } else go(op, level, tick);
    }

    private static void fate(String s) {
        if (fates.size() < 256) fates.add(s);
    }

    /** of the entry, those with a grenade, as words (the stands) */
    private static String armed(Op op) {
        int n = 0;
        for (AiBot b : op.members) if (b.grenades > 0) n++;
        return n + " of " + op.members.size();
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
        op.hurts = powder() ? POWDER_HURTS : HE_HURTS;
        for (AiBot b : op.members) {
            if (b.grenades <= 0 || b.gunner.reloading() || b.prone.lying() || op.tried.contains(b)) continue;
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
                fate("stepped out and threw none");
                in(op, level, tick);
            }
            return;
        }
        op.fragId = s.threwInto(bot, level, t, powder() ? EnumGrenadeFilling.POWDER : EnumGrenadeFilling.HE, tick).getId();
        frags++;
        if (op.fragPlace) fragsFought++;
        fate(op.fragPlace ? "thrown (a room fought in)" : "thrown");
        phase(op, WAIT, tick);
        go(bot, op.places.get(op.members.indexOf(bot))[1], 0.35);
    }

    /**
     * Whether a burst here harms none of the thrower's own: it is in the room (over one of its cells); those of the
     * entry are all of them off it behind the wall at their places beside the way in (AiGrenades.shielded, standing);
     * any other of its side is out of the reach its blast hurts in (op.hurts) or all of it behind blocks from it where
     * it is (and is held there till the burst: sheltered); none to be spared (spared) in the room, nor in that reach
     * unless behind blocks.
     */
    private static boolean safe(Op op, ServerLevel level, VoxelRay.Source src, Mob thrower, Vec3 b) {
        AiRooms.Plan plan = op.plan;
        if (plan == null || plan.room(BlockPos.asLong(Mth.floor(b.x), BlockPos.getY(plan.mid[op.room]), Mth.floor(b.z))) != op.room) {
            why[0]++;
            return false;
        }
        double s2 = op.hurts * op.hurts;
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
        // one to be spared (a villager, a tamed pet, one named: spared) in the room or in the blast's reach (those by
        // the room found once a tick: a throw's plan asks here for many a point)
        long now = level.getGameTime();
        if (op.sparedAt != now) {
            op.sparedAt = now;
            op.spared.clear();
            Vec3 mid = mid(plan.mid[op.room], 0);
            double reach = Math.max(op.hurts, FOUGHT_REACH) + Math.sqrt(plan.size[op.room]) + 2;
            for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new net.minecraft.world.phys.AABB(mid, mid).inflate(reach)))
                if (e.isAlive() && spared(e)) op.spared.add(e);
        }
        for (net.minecraft.world.entity.LivingEntity e : op.spared) {
            boolean in = plan.room(cell(e.getX(), e.getY(), e.getZ())) == op.room;
            if (in || e.distanceToSqr(b) < s2 && !AiGrenades.shielded(src, e.getX(), e.getY(), e.getZ(), e.getBbHeight(), b)) {
                why[4]++;
                return false;
            }
        }
        return true;
    }

    private static boolean shielded(VoxelRay.Source src, long slot, Mob m, Vec3 burst) {
        return AiGrenades.shielded(src, BlockPos.getX(slot) + 0.5, BlockPos.getY(slot), BlockPos.getZ(slot) + 0.5, m.getDimensions(Pose.STANDING).height(), burst, false);
    }

    /**
     * Whether this is a grenade thrown into a room before an entry that does this mob no harm where it is: one of the
     * entry, its place beside the way in all behind the wall from where it will burst; another of its side, out of
     * the reach its blast hurts in (op.hurts) or all of it behind blocks where it is (held there: sheltered). It does
     * not run from that one.
     */
    static boolean keeps(ServerLevel level, AiBot bot, int grenade, Vec3 burst, VoxelRay.Source src) {
        Map<Long, Op> ops = OPS.get(level);
        if (ops == null) return false;
        for (Op op : ops.values()) {
            if (op.fragId != grenade) continue;
            int i = op.members.indexOf(bot);
            if (i >= 0) return shielded(src, op.places.get(i)[1], bot.mob, burst);
            return bot.mob.distanceToSqr(burst) >= op.hurts * op.hurts || AiGrenades.shielded(src, bot.mob.getX(), bot.mob.getY(), bot.mob.getZ(), bot.mob.getBbHeight(), burst);
        }
        return false;
    }

    /** the stands: this one's part in an entry (its phase, its number, the thrower, its place to wait at), "-" if none */
    static String describe(ServerLevel level, AiBot bot) {
        Map<Long, Op> ops = OPS.get(level);
        if (ops == null) return "-";
        for (Op op : ops.values()) {
            int i = op.members.indexOf(bot);
            if (i < 0 && op.thrower != bot) continue;
            long p = i >= 0 && i < op.places.size() ? op.places.get(i)[1] : Long.MIN_VALUE;
            return String.format(java.util.Locale.ROOT, "entry[phase %d at %d, #%d of %d%s, grenade %d, wait at %s, front %d %d]", op.phase, op.phaseAt, i, op.members.size(),
                    op.thrower == bot ? ", thrower" : "", op.fragId, p == Long.MIN_VALUE ? "-" : BlockPos.getX(p) + " " + BlockPos.getZ(p),
                    BlockPos.getX(op.front), BlockPos.getZ(op.front));
        }
        return "-";
    }
}
