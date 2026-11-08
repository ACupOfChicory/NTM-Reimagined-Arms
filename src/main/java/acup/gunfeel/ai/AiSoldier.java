package acup.gunfeel.ai;

import java.util.List;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ai.AiThoughts.Why;
import acup.gunfeel.ai.imitation.AiChoice;
import acup.gunfeel.ai.imitation.AiChoice.Ask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

/**
 * A rifleman's mind, stage 1: what it does with what it has seen and heard.
 * <ul>
 * <li>Someone in sight: into cover from them (a cell their eyes do not reach, picked by {@link CoverQuery}, run to by
 * a path that keeps out of their sight where it can), then out to its peek beside the cover for a burst and back in;
 * over low cover it shoots standing. It reloads in cover. Its cover seen into (they moved round it): it runs to
 * another.</li>
 * <li>Out of sight: it does not go after them. It stays in cover and watches the place they were last seen, where
 * they would come out, for a while.</li>
 * <li>Then it searches round that place, slowly, the cells it cannot see from where it is, nearest first.</li>
 * <li>Heard only (a shot, a blast, a hit): it turns to it, and into cover from it if it was loud; from high cover it
 * looks out the way it came from now and then.</li>
 * <li>Nothing known: it stays where it is.</li>
 * </ul>
 * Decides every few ticks (its look period) and acts every tick. Server thread.
 */
public final class AiSoldier implements AiBrain {
    public enum Mode {
        IDLE, ALERT, TO_COVER, COVER, PEEK, HOLD, SEARCH
    }

    // timings, ticks
    static final int QUERY_EVERY = 20, EXPOSED_EVERY = 10, HIDE_MIN = 10, HIDE_MAX = 80, BURST_MIN = 10, BURST_MAX = 25, PEEK_BLIND = 20;
    /** heard only, in cover: ticks between two looks out the way the noise came from */
    static final int LOOK_AGAIN_MIN = 60, LOOK_AGAIN_MAX = 120;
    static final double RUN = 1.3, WALK = 1.0, CREEP = 0.7;
    /** how near a peek's middle it must stand: a peek a block aside from cover sees round it only about there */
    static final double PEEK_AT = 0.1;
    /** where it aims, as parts of the target's height (chest, head, legs), and half a part's height */
    static final double CHEST = 0.6, HEAD = 0.9, LEGS = 0.2, PART_HALF = 0.14;
    /** the least half-height of a part, blocks: a lying target's (0.6 high) parts are not a hair wide */
    static final double PART_HALF_MIN = 0.2;
    /** whether it aims at the part it sees (off only for the stands' "before": the chest always) */
    static boolean aimParts = true;

    Mode mode = Mode.IDLE;
    long modeSince;
    CoverQuery.@Nullable Spot spot;
    long nextQuery, nextExposedCheck, hideUntil, burstUntil, peekSince, nextSearchPick;
    long searchCell = Long.MIN_VALUE;
    /** the fight under way: since when, the last tick a target was in sight; a burst where it stands till answerUntil */
    long engagedTick, lastCombat = Long.MIN_VALUE / 2, answerUntil;
    /** for the debug view: where it watches or goes */
    @Nullable
    Vec3 watch;
    // for the stands
    public int covers, relocations, bursts, searchSpots, shifts;
    /** ticks it holds fire with one of its own in the line before it moves */
    static final int BLOCKED_MOVE = 20;
    /** ticks a friend may stand in the line of its answering burst before it steps aside */
    static final int ANSWER_BLOCKED = 5;
    /** ticks between moves to other cover for hits in cover (a burst hits many times) */
    static final int HIT_MOVE_GAP = 40;
    /** lying down: never closer than NEVER blocks to the target; in the open from FAR on (or pinned down) */
    static final double PRONE_NEVER = 10, PRONE_FAR = 30;
    /** no target in sight this long, and one seen is a new fight, ticks */
    static final int ENGAGE_GAP = 100;
    /** a new fight lasts this long for its first hide in cover (a look out soon), ticks */
    static final int FRESH = 200, FRESH_HIDE_MIN = 5, FRESH_HIDE_MAX = 10;
    /** a target that looks within this of it (cosine of 20 degrees) has seen it: answered at any range */
    static final double WATCHED_COS = 0.9397;
    /** on its way to cover with a target in sight this near (times the skill's answerNear) it walks there firing */
    static final double NEAR_WALK = 1.5;
    /** a hit or a shot close past it in a fight: other cover at this chance (not twice within SHIFT_GAP ticks) */
    static final float SHIFT_HIT = 0.7F;
    /** looked out of one cover 2 or 3 times: other cover at this chance; FROM_ONE times from one place, FROM_COVER out of one cover: for sure */
    static final float SHIFT_PEEKS = 0.4F;
    static final int FROM_ONE = 4, FROM_COVER = 5, SHIFT_GAP = 60;
    /** in cover, it looks for a better place every so often, ticks */
    static final int BETTER_MIN = 160, BETTER_MAX = 300;
    /** a target out of sight this long after it has looked for it (or with no cover to look from), it goes for them, ticks */
    static final int PUSH_MIN = 80, PUSH_MAX = 120;
    /** a noise looked into: near enough this many blocks, it is checked; given up after CHECK_FOR ticks */
    static final double CHECKED = 3;
    static final int CHECK_FOR = 600;
    /** other threats a cover is weighed against in a fight, at most */
    static final int MORE = 3;
    /** what it decided last and why (the debug view, the journal): set where it decides, it changes nothing */
    Why why = Why.IDLE;
    /** what the decision journal said of it last */
    private final AiThoughts thoughts = new AiThoughts();

    private void mode(Mode m, long tick) {
        if (mode != m) {
            mode = m;
            modeSince = tick;
            if (m != Mode.PEEK) leanSide = 0;
        }
    }

    /** out at its peek: the way it leans round the corner (AiPose lean), 0: a step aside instead */
    private byte leanSide;
    /** the tick it last put its own leaned boxes for shots at it (AiLean.boxes) */
    private long leanBoxes = Long.MIN_VALUE;
    // for the stands: looks out leaning, and stepping aside
    public int leans, steps;

    /**
     * Out to look or fire round its cover: a step toward the corner and a lean round it, if its eye out there sees the
     * place it looks at (and a leaned head can be shot: AiLean.may); else, as before, a step to its peek.
     */
    private void peekOut(AiBot bot, Vec3 at) {
        long pick = choosePeek(bot);
        long cell = spot.cell();
        leanSide = 0;
        int px = BlockPos.getX(pick) - BlockPos.getX(cell), pz = BlockPos.getZ(pick) - BlockPos.getZ(cell);
        if (AiLean.may() && !bot.prone.lying() && (px != 0 || pz != 0) && BlockPos.getY(pick) == BlockPos.getY(cell)
                && bot.mob.level() instanceof ServerLevel level) {
            double l = Math.sqrt(px * px + pz * pz), ox = px / l * AiLean.STEP, oz = pz / l * AiLean.STEP;
            double x = BlockPos.getX(cell) + 0.5 + ox, z = BlockPos.getZ(cell) + 0.5 + oz, fx = at.x - x, fz = at.z - z;
            float yaw = (float) (net.minecraft.util.Mth.atan2(fz, fx) * net.minecraft.util.Mth.RAD_TO_DEG) - 90F;
            // the corner on its right, facing the place (its right: (-fz, fx)), or on its left
            byte side = -px * fz + pz * fx > 0 ? AiLean.FULL : (byte) -AiLean.FULL;
            Vec3 eye = new Vec3(x, BlockPos.getY(cell), z)
                    .add(AiPoseGeometry.eyeOffset(AiPose.STAND, side / 100F, yaw, bot.mob.getDimensions(Pose.STANDING).eyeHeight()));
            if (Senses.clearLine(AiBots.voxels(level), null, eye, at)) {
                leanSide = side;
                leans++;
                bot.mover.goTo(cell, PEEK_AT, WALK, ox, oz);
                return;
            }
        }
        steps++;
        bot.mover.goTo(pick, PEEK_AT, WALK);
    }

    public Mode mode() {
        return mode;
    }

    public CoverQuery.@Nullable Spot spot() {
        return spot;
    }

    public @Nullable Vec3 watch() {
        return watch;
    }

    /** the cell it searches now (Long.MIN_VALUE: none) */
    public long searchCell() {
        return searchCell;
    }

    /** cover cells it could not get to lately: not offered again for a while */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet unreachable = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private long unreachableSince;
    /** a hit it has turned to already */
    private long hitSeen = Long.MIN_VALUE;
    /** the loss (its lostTick) it has looked out for already and not seen them: it waits now */
    private long blindLost = Long.MIN_VALUE;
    private long peekThere;
    /** heard only, in cover: when it looks out again */
    private long lookAgain;
    /** the place it looked out from last, and how many times running from that one */
    private long lastPeek = Long.MIN_VALUE;
    private int peeksHere, coverPeeks;
    /** the noise (its tick) it goes to see after a look out found nothing */
    private long checkNoise = Long.MIN_VALUE;
    /** the last tick a hit in cover sent it to other cover */
    private long lastHitMove = Long.MIN_VALUE / 2;
    private long lastShift = Long.MIN_VALUE / 2, nextBetter, pushLost = Long.MIN_VALUE, pushAt;
    // for the stands: the most looks out running from one place
    public int longestPeekRun;

    // ------------------------------------------------------------------ in a squad (AiSquads)
    /** the loss (its lostTick) it went round the side for already; when it looks for flanking cover again */
    private long flankLost = Long.MIN_VALUE, nextFlank;
    /** none found: it looks again this many ticks on (it moved, or the others did), FLANK_TRIES times a loss at most */
    static final int FLANK_AGAIN = 40, FLANK_TRIES = 3;
    /** the loss it looked for flanking cover for, and how many times */
    private long triedLost = Long.MIN_VALUE;
    private int tried;
    /** how far from it flanking cover may be, blocks (round the side is further than cover straight behind it) */
    static final double FLANK_RADIUS = 24;
    /** on its way to flanking cover: a short hide there, then a look out */
    private boolean quickLook;
    /** out (or up over low cover) for a burst at the place a target went out of sight, since when; the next one */
    private boolean suppressing;
    private long suppressSince, nextSuppress;
    /** ticks between two bursts at the place, for those on the move */
    static final int SUPPRESS_GAP_MIN = 40, SUPPRESS_GAP_MAX = 60;
    // for the stands: flanks it went on, bursts at a place it fired for those on the move
    public int flanks, flankTries, suppressions;

    /** a grenade: it stands this long while its arm goes over (the pose's throw), ticks */
    static final int THROW_TICKS = 12;
    /** a grenade at most this often, ticks; one of its squad's this long before: none */
    static final int THROW_GAP = 200, SQUAD_THROW_GAP = 100;
    /** a throw tried at most this often (one that fell short of the place: none), ticks */
    static final int TRY_GAP = 40;
    /** thrown at a target lost this long ago at least (gone behind cover, not out for a moment) and at most, ticks */
    static final int LOST_FIRST = 20, LOST_RECENT = 140;
    private long throwUntil = Long.MIN_VALUE, lastThrow = Long.MIN_VALUE / 2, nextTry = Long.MIN_VALUE;
    /** running from a grenade: the burst it runs from (null: it does not), where to */
    private @Nullable Vec3 dodgeFrom;
    /** the burst its refuge was sought from (the burst is worked out again as the grenade flies, hops off a mob, rolls) */
    private @Nullable Vec3 refugeFrom;
    private long dodgeCell = Long.MIN_VALUE, nextRefuge = Long.MIN_VALUE;

    /** the stands: the cell it runs to from a grenade (Long.MIN_VALUE: none) */
    long dodgeCell() {
        return dodgeCell;
    }
    /** refuges it could not get to, this run */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet dodgeTried = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    /** a refuge looked for again at most this often, ticks */
    static final int REFUGE_AGAIN = 10;
    // for the stands: grenades it threw, grenades it ran from
    public int thrown, dodges;
    /** where its last grenade was to burst (the stands) */
    public @Nullable Vec3 planned;

    @Override
    public void tick(AiBot bot, long tick) {
        crouch = false;
        bot.gunner.blind = false;
        decide(bot, tick);
        // sliding or rolling: no shot (pose-contract §4)
        if (bot.dash.on(tick)) bot.gunner.fire(false);
        body(bot, tick);
        if (AiThoughts.on()) thoughts.note(this, bot, tick);
    }

    /** this tick it keeps low behind low cover (the decision sets it; standing unless it does) */
    private boolean crouch;
    /** ticks it rises before a burst over low cover */
    static final int RISE = 5;
    /** the pause between bursts over low cover (burst) */
    static final int BURST_PAUSE = 20;

    /**
     * Its body as decided this tick: down low behind low cover or running low past it (AiStoop), else standing where
     * there is room to (lying is AiProne's); and the pose the clients draw (AiPose), set before the game's pose, so the
     * crouching size (MixinAiProneSize) finds it.
     */
    private void body(AiBot bot, long tick) {
        Mob m = bot.mob;
        boolean lying = bot.prone.lying();
        boolean stoop = bot.stoop.want(m, bot.mover, lying, AiBots.voxels((ServerLevel) m.level()), tick);
        // down low under something lower than its standing height: it stays low (a player cannot stand up there either)
        boolean low = crouch || stoop || bot.dash.on(tick) || !lying && m.getPose() == Pose.CROUCHING && !m.level().noCollision(m,
                m.getDimensions(Pose.STANDING).makeBoundingBox(m.position()).deflate(1.0E-7));
        AiGunner g = bot.gunner;
        boolean reloading = g.reloading();
        boolean aiming = g.wantsFire() || mode == Mode.PEEK;
        // held up over its cover with no look along it: the arms the pose layer draws for that (blind fire)
        boolean blind = g.blind && low;
        // a slide or a roll is the action while it lasts, over all else (pose-contract §4)
        byte dash = bot.dash.kind(tick);
        AiPose.set(m, AiPose.of(m).withStance(lying ? AiPose.PRONE : low ? AiPose.CROUCH : AiPose.STAND)
                .withLean((byte) (lying || low || mode != Mode.PEEK ? 0 : leanSide), tick)
                .withArms(blind ? AiPose.BLIND : aiming ? AiPose.AIM : AiPose.HOLD)
                .withAction(dash != AiPose.NONE ? dash : tick < throwUntil ? AiPose.THROW : reloading ? AiPose.RELOAD : AiPose.NONE,
                        (int) (dash != AiPose.NONE ? bot.dash.since() : tick),
                        (short) (dash != AiPose.NONE ? AiDash.TICKS : tick < throwUntil ? THROW_TICKS : reloading ? g.reloadTicks() : 0)));
        if (!lying) {
            Pose want = low ? Pose.CROUCHING : Pose.STANDING;
            if (m.getPose() != want) m.setPose(want);
        }
        // after the game's pose: the boxes are kept for this pose and size
        leanBoxes = AiLean.boxes((ServerLevel) m.level(), m, aiming, leanBoxes, tick);
    }

    /** a decision of a moment, for the journal (what: a constant, so nothing is built with the journal off) */
    private void event(AiBot bot, long tick, String what) {
        if (AiThoughts.on()) thoughts.event(bot, tick, what);
    }

    private void decide(AiBot bot, long tick) {
        if (!(bot.mob.level() instanceof ServerLevel level)) return;
        if (tick - unreachableSince > 400) {
            unreachable.clear();
            unreachableSince = tick;
        }
        // its cover out of reach, whatever it was doing (on its way, holding, back in from a peek): other cover
        if (spot != null && bot.mover.failed() && bot.mover.goal() == spot.cell()) {
            unreachable.add(spot.cell());
            drop(bot);
            nextQuery = tick;
            event(bot, tick, "cover out of reach: gives it up");
        }
        // a grenade it knows of to burst near it: it runs, whatever it was doing
        if (dodge(bot, level, tick)) return;
        // throwing: it stands while its arm goes over
        if (tick < throwUntil) {
            bot.gunner.fire(false);
            return;
        }
        TargetMemory.Contact c = bot.memory.focus(bot.mob.getX(), bot.mob.getZ());
        TargetMemory.Noise n = bot.memory.noise();
        // a burst at the place for those on the move goes on only while the decision below is that again
        boolean wasSuppressing = suppressing;
        suppressing = false;
        // shot from where it does not look: it turns there, and its cover, seen into from there, is no cover
        if (n != null && n.hurt && n.tick != hitSeen && (c == null || c.state != TargetMemory.State.VISIBLE)) {
            hitSeen = n.tick;
            hit(bot, level, n, tick);
            return;
        }
        if (c != null && c.state == TargetMemory.State.VISIBLE) combat(bot, level, c, tick);
        // its squad's flanker: round the side of the place they went out of sight, to cover that sees it
        else if (c != null && c.state != TargetMemory.State.VISIBLE && flank(bot, level, c, tick)) {
        }
        // gone behind cover a moment ago: a grenade over it
        else if (c != null && c.state == TargetMemory.State.LOST && grenade(bot, level, c, tick)) {
        }
        // out of sight a while, looked for already (or no cover to look from): it goes for them
        else if (c != null && c.state == TargetMemory.State.LOST && pushes(bot, c, tick)) search(bot, level, c, tick);
        // one of its squad on the move: it keeps the place they went out of sight under fire, a burst every few seconds
        else if (c != null && c.state == TargetMemory.State.LOST && spot != null && (mode == Mode.COVER || mode == Mode.PEEK || mode == Mode.HOLD)
                && (wasSuppressing || AiSquads.suppresses(bot, c, tick))) {
            suppressing = wasSuppressing;
            suppress(bot, c, tick);
        }
        // lost from sight because it went into cover itself: it goes on hiding and looking out where they were
        else if (c != null && c.state == TargetMemory.State.LOST && spot != null && c.lostTick != blindLost
                && (mode == Mode.TO_COVER || mode == Mode.COVER || mode == Mode.PEEK)) coverCycle(bot, c, tick);
        else if (c != null && c.state == TargetMemory.State.LOST) hold(bot, level, c, tick);
        // its flank under way when the wait turned into a search: it gets there and looks out first
        else if (c != null && c.state == TargetMemory.State.SEARCH && c.lostTick == flankLost && spot != null && c.lostTick != blindLost
                && (mode == Mode.TO_COVER || mode == Mode.COVER || mode == Mode.PEEK)) coverCycle(bot, c, tick);
        // two of a squad at most go after one target; the rest watch where it went
        else if (c != null && c.state == TargetMemory.State.SEARCH && (mode == Mode.SEARCH || AiSquads.mayHunt(bot, c.id))) search(bot, level, c, tick);
        else if (c != null && c.state == TargetMemory.State.SEARCH) hold(bot, level, c, tick);
        else if (n != null) alert(bot, level, n, tick);
        else idle(bot, tick);
    }

    // ------------------------------------------------------------------ grenades

    /**
     * A grenade at a target it lost behind cover a moment ago: from its cover, the target 15 to 26 blocks off, one of
     * its own left, none thrown by it or its squad a short while; thrown if one bursts within 3 blocks of where the
     * target went out of sight and none of its own near the burst (AiGrenades.plan). Whether it threw.
     */
    private boolean grenade(AiBot bot, ServerLevel level, TargetMemory.Contact c, long tick) {
        if (!AiGrenades.on || bot.grenades <= 0 || spot == null || tick < nextTry || tick - lastThrow < THROW_GAP) return false;
        if (mode != Mode.COVER && mode != Mode.PEEK && mode != Mode.HOLD || !bot.mover.arrived() || bot.prone.lying() || bot.gunner.reloading()) return false;
        long since = tick - c.lostTick;
        if (since < LOST_FIRST || since > LOST_RECENT) return false;
        Vec3 at = new Vec3(c.lostX, c.lostY, c.lostZ);
        double d = bot.mob.position().distanceTo(at);
        if (d < AiGrenades.NEAR || d > AiGrenades.FAR) return false;
        AiSquads.Squad s = AiSquads.of(bot);
        if (s != null) for (AiBot o : s.bots)
            if (o != bot && o.brain instanceof AiSoldier os && tick - os.lastThrow < SQUAD_THROW_GAP) return false;
        if (!AiGrenades.mayPlan(level, tick)) return false;
        nextTry = tick + TRY_GAP;
        AiGrenades.Throw t = AiGrenades.plan(level, bot.mob, at);
        if (t == null) return false;
        planned = t.burst();
        AiGrenades.toss(level, bot.mob, t);
        bot.grenades--;
        AiAdapter.keepGrenades(bot);
        lastThrow = tick;
        throwUntil = tick + THROW_TICKS;
        thrown++;
        bot.gunner.fire(false);
        why = Why.THROW;
        event(bot, tick, "a grenade where they went out of sight");
        return true;
    }

    /**
     * A grenade it knows of to burst within 10 blocks of it (13 once it runs; one of its own side's: 17, 19, and no
     * block keeps that one off): it runs to the nearest place out of its reach or behind a block from it, and stays
     * there till it bursts; then back to the fight, cover sought anew. Behind a block from it already, or with nowhere
     * to run, it fights on where it is. Whether it runs.
     */
    private boolean dodge(AiBot bot, ServerLevel level, long tick) {
        AiGrenades.Danger dg = AiGrenades.any(level) ? AiGrenades.danger(level, bot, dodgeFrom == null ? AiGrenades.DANGER : AiGrenades.DANGER + 3) : null;
        if (dg == null) {
            if (dodgeFrom != null) {
                dodgeFrom = null;
                refugeFrom = null;
                dodgeCell = Long.MIN_VALUE;
                dodgeTried.clear();
                // no cover to go back to: it stops where it ran to, and seeks some (not all who ran at once)
                if (spot == null) bot.mover.stop();
                nextQuery = tick + bot.mob.getRandom().nextInt(10);
            }
            return false;
        }
        Vec3 b = dg.burst();
        // behind a block from it where it is (not its own side's: AiGrenades.cover): it stays and fights on
        if (dodgeFrom == null && AiGrenades.cover(AiBots.voxels(level), b, bot.mob.getX(), bot.mob.getY(), bot.mob.getZ(), dg.own())) return false;
        boolean first = dodgeFrom == null;
        if (first) {
            dodges++;
            event(bot, tick, "a grenade near: runs from it");
        }
        if (dodgeCell != Long.MIN_VALUE && bot.mover.failed()) {
            dodgeTried.add(dodgeCell);
            dodgeCell = Long.MIN_VALUE;
        }
        // sought again once the burst has gone 2 blocks from where the refuge was sought from, or the refuge is in its
        // reach now (not behind a block from it; its own side's: a block short of where it was sought, not at once)
        boolean moved = refugeFrom != null && refugeFrom.distanceToSqr(b) > 4 || dodgeCell != Long.MIN_VALUE
                && !AiGrenades.safe(AiBots.voxels(level), b, dodgeCell, dg.own() ? dg.clear() - 1 : dg.clear(), dg.own());
        dodgeFrom = b;
        if ((dodgeCell == Long.MIN_VALUE || moved) && tick >= nextRefuge && AiGrenades.mayRefuge(level, tick)) {
            nextRefuge = tick + REFUGE_AGAIN;
            refugeFrom = b;
            long cell = AiGrenades.refuge(level, bot.mob, b, dg.clear(), dg.own(), dodgeTried);
            if (cell != Long.MIN_VALUE) {
                dodgeCell = cell;
                if (spot != null) drop(bot);
                bot.mover.goTo(cell, 0.5, RUN);
                mode(Mode.TO_COVER, tick);
            }
        }
        // nowhere to run to (yet): it fights on where it is
        if (dodgeCell == Long.MIN_VALUE) return false;
        bot.gunner.fire(false);
        throwUntil = Long.MIN_VALUE;
        why = Why.GRENADE;
        return true;
    }

    private void hit(AiBot bot, ServerLevel level, TargetMemory.Noise n, long tick) {
        Vec3 from = new Vec3(n.x, n.y + 1.5, n.z);
        watch = from;
        bot.gunner.fire(false);
        bot.gunner.aim(from);
        // turned at once: the next look sees whoever shot
        float yaw = (float) (net.minecraft.util.Mth.atan2(n.z - bot.mob.getZ(), n.x - bot.mob.getX()) * net.minecraft.util.Mth.RAD_TO_DEG) - 90F;
        bot.mob.setYHeadRot(yaw);
        bot.mob.setYRot(yaw);
        why = Why.HIT;
        event(bot, tick, "hit from out of sight: turns there");
        // hurt by someone (not a blast no one set off): the one it lost did it; it watches, looks out and searches
        // where the shot came from, not where it lost them
        if (bot.memory.wasShot(n.tick)) bot.memory.shotFrom(n.x, n.y, n.z, tick);
        // hit in its cover: whoever shot sees into it, wherever the rough guess of where from puts them (a guess a few
        // blocks off could fall behind a wall and leave it in the cover it was shot in). Other cover if there is any,
        // once in a while (a burst's every hit is no new news), and not while on its way to cover already
        if (spot != null && mode != Mode.TO_COVER && AiChoice.ask(bot, Ask.HIT_MOVE, tick - lastHitMove > HIT_MOVE_GAP, tick)) {
            lastHitMove = tick;
            // asked as if it had none: the query keeps the current place (past the cells it is not to take, with a
            // bonus) and would send it back where it was shot
            CoverQuery.Spot was = spot;
            long s = was.cell();
            spot = null;
            boolean had = !unreachable.add(s);
            CoverQuery.Spot other = query(bot, level, TacticalMap.of(level), List.of(from), true, false);
            if (other != null && other.cell() != s) {
                go(bot, level, other, List.of(from));
                relocations++;
                why = Why.HIT_COVER;
                event(bot, tick, "hit in its cover: other cover");
            } else {
                spot = was;
                if (!had) unreachable.remove(s);
            }
        }
        if (spot == null) {
            nextQuery = tick + QUERY_EVERY;
            if (takeCover(bot, level, TacticalMap.of(level), List.of(from), true)) why = Why.HIT_COVER;
        }
    }

    // ------------------------------------------------------------------ in sight

    private void combat(AiBot bot, ServerLevel level, TargetMemory.Contact c, long tick) {
        Vec3 eye = new Vec3(c.x, c.y + 1.5, c.z);
        double dist = bot.mob.position().distanceTo(new Vec3(c.x, c.y, c.z));
        // in sight: its eye follows the target tick by tick (looks for it come every few ticks; once they lose it,
        // this is not run and it aims where it went)
        Entity seen = level.getEntity(c.id);
        Vec3 chest = seen != null ? seen.position().add(0, seen.getBbHeight() * CHEST, 0) : new Vec3(c.x, c.y + 1.1, c.z);
        watch = chest;
        AiGunner g = bot.gunner;
        if (seen != null) {
            // at what it saw of them at its last look: the whole body at the chest; else the middle of what shows
            // (head and chest over a low wall, chest and legs under a beam), or the one part; less than the whole
            // body, the trigger waits for that alone
            int parts = c.parts == 0 || !aimParts ? Senses.WHOLE : c.parts;
            double half = parts == Senses.WHOLE ? AiAim.HALF_HEIGHT : Math.max(PART_HALF_MIN, PART_HALF * seen.getBbHeight());
            // one leaning round a corner: the part where the lean has it (its head out past the corner)
            if (seen instanceof net.minecraft.world.entity.LivingEntity le && AiLean.frac(le) != 0F)
                g.track(c.id, AiLean.point(le, aimAt(parts) * seen.getBbHeight()), 0, half, tick);
            else g.track(c.id, chest, (aimAt(parts) - CHEST) * seen.getBbHeight(), half, tick);
        } else g.aim(chest);
        // a new fight (nobody in sight a while): a target near, or one that looks at it, it answers with a burst where
        // it stands before it goes for cover, as a soldier answers a contact; far off and unseen, cover first
        AiSkill skill = AiSkill.current();
        if (tick - lastCombat > ENGAGE_GAP) {
            engagedTick = tick;
            boolean onTheWay = spot == null || mode == Mode.TO_COVER && !bot.mover.arrived();
            boolean watched = seen != null && watches(seen, bot.mob);
            answerUntil = onTheWay && (dist <= skill.answerNear() || watched) ? tick + answerTicks(bot, skill) : 0;
        }
        lastCombat = tick;
        if (tick < answerUntil) {
            // one of its own in the line: no burst from here, a step aside at once (as fights here do, later)
            if (g.blockedTicks > ANSWER_BLOCKED) {
                answerUntil = 0;
                g.blockedTicks = 0;
                g.fire(false);
                // on its way to cover: on to it (out of the line as well); with none, a step aside, and no cover query
                // to take it back before it gets there
                if (spot == null) {
                    sidestep(bot, level, chest);
                    nextQuery = tick + QUERY_EVERY;
                    event(bot, tick, "friend in the line of its answer: a step aside");
                } else event(bot, tick, "friend in the line of its answer: on to cover");
                return;
            }
            if (bot.mover.goal() != Long.MIN_VALUE) bot.mover.stop();
            g.fire(true);
            why = Why.ANSWER;
            return;
        }
        TacticalMap map = TacticalMap.of(level);
        // hit, or a shot close past it: often other cover
        TargetMemory.Noise hurt = bot.memory.noise();
        if (hurt != null && hurt.hurt && hurt.tick != hitSeen) {
            hitSeen = hurt.tick;
            if (spot != null && (mode == Mode.COVER || mode == Mode.PEEK) && tick - lastShift > SHIFT_GAP && !bot.prone.fresh(tick)
                    && AiChoice.ask(bot, Ask.SHIFT_ON_HIT, bot.mob.getRandom().nextFloat() < SHIFT_HIT, tick)) shift(bot, tick, "shot at in cover: other cover");
        }
        // in cover a while: a better place, if there is one now
        if (spot != null && mode == Mode.COVER && bot.mover.arrived() && !bot.prone.fresh(tick) && AiSquads.mayMove(bot)
                && AiChoice.ask(bot, Ask.BETTER_COVER, tick >= nextBetter, tick)) {
            nextBetter = tick + BETTER_MIN + bot.mob.getRandom().nextInt(BETTER_MAX - BETTER_MIN);
            List<Vec3> all = threats(bot, c, eye);
            CoverQuery.Spot better = query(bot, level, map, all, false, true);
            if (better != null && better.cell() != spot.cell()) {
                go(bot, level, better, all);
                shifts++;
                lastShift = tick;
                event(bot, tick, "a better place: moves");
                why = Why.BETTER_COVER;
                return;
            }
        }
        // cover seen into: find another
        if (spot != null && tick >= nextExposedCheck) {
            nextExposedCheck = tick + EXPOSED_EVERY;
            long s = spot.cell();
            double look = spot.height() == 2 ? 1.6 : 0.5;
            if (VoxelRay.clear(AiBots.voxels(level), eye.x, eye.y, eye.z, BlockPos.getX(s) + 0.5, BlockPos.getY(s) + look, BlockPos.getZ(s) + 0.5,
                    Voxel.SIGHT)) {
                drop(bot);
                relocations++;
                nextQuery = tick;
                event(bot, tick, "cover seen into: gives it up");
            }
        }
        // lying down a moment ago (far off, pinned, a long reload): it lies its time out before it runs for cover
        if (spot == null && tick >= nextQuery && !bot.prone.fresh(tick)) {
            nextQuery = tick + QUERY_EVERY + bot.mob.getRandom().nextInt(10);
            if (takeCover(bot, level, map, threats(bot, c, eye), false, true)) why = Why.SEEN_COVER;
        }
        if (spot == null) {
            // no cover to be had: fight where it stands (lying, far off or pinned down by shots flying past), a step
            // aside when one of its own is in the line
            mode(Mode.COVER, tick);
            if (dist >= PRONE_NEVER && (dist >= PRONE_FAR || bot.prone.pinned(tick) || longReload(g) && g.reloading())) bot.prone.want();
            why = dist < PRONE_NEVER ? Why.NO_COVER : dist >= PRONE_FAR ? Why.NO_COVER_FAR : bot.prone.pinned(tick) ? Why.NO_COVER_PINNED : Why.NO_COVER;
            burst(bot, tick, true);
            if (g.blockedTicks > BLOCKED_MOVE || g.coverTicks > BLOCKED_MOVE) {
                String what = g.blockedTicks > BLOCKED_MOVE ? "friend" : "a block that stops bullets";
                g.blockedTicks = 0;
                g.coverTicks = 0;
                sidestep(bot, level, chest);
                event(bot, tick, what + " in the line: a step aside");
            }
            return;
        }
        // its own cover in the way of its shots (it sees over it, a bullet would not go over) too long: out to its
        // peek if it has one, else other cover
        if (g.coverTicks > BLOCKED_MOVE && (mode == Mode.COVER || mode == Mode.PEEK)) {
            g.coverTicks = 0;
            g.fire(false);
            if (mode == Mode.COVER && spot.hasPeek() && spot.peek() != spot.cell()) {
                peekOut(bot, eye);
                mode(Mode.PEEK, tick);
                peekSince = tick;
                burstUntil = 0;
                why = Why.PEEK_BURST;
                event(bot, tick, "its own cover in the line of fire: out to its peek");
            } else {
                unreachable.add(spot.cell());
                drop(bot);
                shifts++;
                nextQuery = tick;
                event(bot, tick, "its own cover in the line of fire: other cover");
            }
            return;
        }
        // one of its own in the line too long: other cover
        if (g.blockedTicks > BLOCKED_MOVE && (mode == Mode.COVER || mode == Mode.PEEK)) {
            g.blockedTicks = 0;
            g.fire(false);
            unreachable.add(spot.cell());
            drop(bot);
            shifts++;
            nextQuery = tick;
            event(bot, tick, "friend in the line too long: other cover");
            return;
        }
        if (!bot.mover.arrived() && mode == Mode.TO_COVER) {
            // on its way: near, in sight, it walks there firing (as a player backs off shooting); else it runs, holding fire
            boolean near = dist <= skill.answerNear() * NEAR_WALK;
            // the last blocks at a run: a slide into low cover; a shot close past it on the way: a roll
            if (!near) {
                boolean lowCover = spot.peek() == spot.cell() || !spot.hasPeek();
                net.minecraft.core.BlockPos cell = net.minecraft.core.BlockPos.of(spot.cell());
                if (bot.dash.consider(level, bot.mob, spot.cell(), net.minecraft.world.phys.Vec3.atBottomCenterOf(cell), lowCover,
                        g.reloading() || tick < throwUntil, tick) != AiPose.NONE)
                    event(bot, tick, "dash: slide or roll");
            }
            bot.stoop.follow(eye);
            bot.mover.goTo(spot.cell(), 0.35, near ? WALK : RUN);
            g.fire(near && burst(bot, tick, false));
            if (near) why = Why.WALK_FIRE;
            return;
        }
        // over low cover it shoots standing; so it does from high cover with no peek, as it sees them from there
        boolean low = spot.peek() == spot.cell() || !spot.hasPeek();
        if (mode == Mode.TO_COVER || mode == Mode.ALERT || mode == Mode.HOLD || mode == Mode.SEARCH || mode == Mode.IDLE) {
            mode(Mode.COVER, tick);
            // the first hide of a new fight is short: it looks out again soon
            boolean fresh = tick - engagedTick < FRESH;
            int min = fresh ? FRESH_HIDE_MIN : HIDE_MIN, max = fresh ? FRESH_HIDE_MAX : HIDE_MAX;
            hideUntil = tick + (low ? 0 : min + bot.mob.getRandom().nextInt(max - min));
        }
        if (mode == Mode.COVER) {
            if (!bot.mover.goalIs(spot.cell())) bot.mover.goTo(spot.cell(), 0.35, WALK);
            boolean there = bot.mover.arrived();
            boolean reload = g.loaded() >= 0 && g.loaded() < Math.max(1, g.capacity() / 3) && g.ammo.any();
            if (low && there) {
                // behind low cover it fires standing, and goes down only for a long reload
                if ((reload || g.reloading()) && dist >= PRONE_NEVER && longReload(g)) bot.prone.want();
                // pinned down there: it does not rise into the fire, it fires blind over the cover where they were seen
                if (reload || dist < BLIND_NEAR || !blindFire(bot, level, new Vec3(c.x, c.y + 1.1, c.z), false, tick)) {
                    g.fire(bot.prone.upright(tick) && !reload && burst(bot, tick, false));
                    // between bursts and through a short reload it keeps low, and rises a moment before the next burst
                    crouch = reload || g.reloading() || burstUntil != 0 && tick > burstUntil && tick < burstUntil + BURST_PAUSE - RISE;
                }
            } else g.fire(false);
            if (reload) g.reload();
            // on its way in: the reason it is coming in stays
            if (there) why = reload || g.reloading() ? (low ? Why.LOW_RELOAD : Why.RELOAD) : g.dry() ? Why.DRY : g.blind ? Why.BLIND : low ? Why.LOW_FIGHT : Why.HIDE;
            if (!low && there && !reload && !g.reloading() && spot.hasPeek() && g.loaded() != 0 && AiChoice.ask(bot, Ask.PEEK_FIGHT, tick >= hideUntil, tick)) {
                peekOut(bot, eye);
                mode(Mode.PEEK, tick);
                peekSince = tick;
                burstUntil = 0;
                why = Why.PEEK_BURST;
            }
        } else if (mode == Mode.PEEK) {
            why = Why.PEEK_BURST;
            boolean there = bot.mover.arrived();
            boolean fired = there && burst(bot, tick, false);
            g.fire(fired);
            boolean done = there && burstUntil != 0 && tick >= burstUntil;
            if (tick - peekSince > PEEK_BLIND + 40 || g.loaded() == 0 || there && AiChoice.ask(bot, Ask.BACK_IN, done, tick)) {
                g.fire(false);
                bot.mover.goTo(spot.cell(), 0.35, WALK);
                mode(Mode.COVER, tick);
                hideUntil = tick + HIDE_MIN + bot.mob.getRandom().nextInt(HIDE_MAX - HIDE_MIN);
                why = g.loaded() == 0 ? Why.BACK_EMPTY : done ? Why.BACK_DONE : Why.BACK_LONG;
                // looked out of one cover two or three times: now and then other cover; from one place FROM_ONE
                // times, or out of one cover FROM_COVER times: other cover for sure
                // one it must make (too many looks out of one place) it makes in a squad too; one it may, only covered
                boolean must = peeksHere >= FROM_ONE || coverPeeks >= FROM_COVER;
                if (tick - lastShift > SHIFT_GAP && (must || AiSquads.mayMove(bot)) && AiChoice.ask(bot, Ask.SHIFT_AFTER_PEEK, must
                        || coverPeeks >= 2 + bot.mob.getRandom().nextInt(2) && bot.mob.getRandom().nextFloat() < SHIFT_PEEKS, tick))
                    shift(bot, tick, "looked out of one cover " + coverPeeks + " times: other cover");
            }
        }
    }

    /**
     * In cover from a target it cannot see now: it hides a while (reloading), then looks out from its peek where the
     * target was last seen. Seen there, the fight goes on ({@link #combat}); not seen, it gets back in and waits
     * ({@link #hold}). Over low cover it sees over it standing: not seen means gone.
     */
    private void coverCycle(AiBot bot, TargetMemory.Contact c, long tick) {
        CoverQuery.Spot s = spot;
        Vec3 lost = new Vec3(c.lostX, c.lostY + 1.1, c.lostZ);
        watch = lost;
        AiGunner g = bot.gunner;
        g.fire(false);
        g.aim(lost);
        if (mode == Mode.TO_COVER) {
            if (!bot.mover.arrived()) return;
            mode(Mode.COVER, tick);
            // at flanking cover it looks out soon: the look round the side is what it came for
            int min = quickLook ? FRESH_HIDE_MIN : HIDE_MIN, max = quickLook ? FRESH_HIDE_MAX : HIDE_MAX;
            quickLook = false;
            hideUntil = tick + min + bot.mob.getRandom().nextInt(max - min);
        }
        if (mode == Mode.COVER) {
            if (!bot.mover.goalIs(s.cell())) bot.mover.goTo(s.cell(), 0.35, WALK);
            boolean low = s.peek() == s.cell() || !s.hasPeek();
            // pinned down behind low cover: no look over it into the fire, a burst blind over it where they went (a
            // reload only when a third or less is left, as in a fight: not after its first blind shot)
            if (low && bot.mover.arrived() && !(g.loaded() >= 0 && g.loaded() < Math.max(1, g.capacity() / 3) && g.ammo.any())
                    && lost.distanceTo(bot.mob.position()) >= BLIND_NEAR && blindFire(bot, (ServerLevel) bot.mob.level(), lost, true, tick)) return;
            boolean reload = g.loaded() >= 0 && g.loaded() < g.capacity() && g.ammo.any();
            if (reload) g.reload();
            // behind low cover it lies only for a long reload, keeps low while it waits, and stands to look
            if (low && bot.mover.arrived() && (reload || g.reloading()) && longReload(g)) bot.prone.want();
            crouch = low && bot.mover.arrived() && (reload || g.reloading() || tick < hideUntil);
            why = reload || g.reloading() ? Why.RELOAD : low ? Why.LOW_HIDE : Why.LOST_HIDE;
            if (bot.mover.arrived() && !reload && !g.reloading() && AiChoice.ask(bot, Ask.LOOK_LOST, tick >= hideUntil, tick)) {
                if (low) {
                    why = Why.LOW_LOOK;
                    if (bot.prone.upright(tick)) blindLost = c.lostTick;
                    return;
                }
                peekOut(bot, lost);
                mode(Mode.PEEK, tick);
                peekSince = tick;
                peekThere = 0;
                burstUntil = 0;
                why = Why.PEEK_LOOK;
            }
        } else if (mode == Mode.PEEK) {
            why = Why.PEEK_LOOK;
            boolean there = bot.mover.arrived();
            if (there && peekThere == 0) peekThere = tick;
            if (there && tick - peekThere > PEEK_BLIND || tick - peekSince > PEEK_BLIND + 60) {
                // looked out where they were, and they are not there: back in, to wait for them
                blindLost = c.lostTick;
                bot.mover.goTo(s.cell(), 0.35, WALK);
                mode(Mode.COVER, tick);
                why = Why.BACK_GONE;
            }
        }
    }

    /** fires in bursts with pauses; whether to hold the trigger now */
    private boolean burst(AiBot bot, long tick, boolean anywhere) {
        if (burstUntil == 0 || tick > burstUntil + BURST_PAUSE) {
            burstUntil = tick + BURST_MIN + bot.mob.getRandom().nextInt(BURST_MAX - BURST_MIN);
            bursts++;
        }
        boolean on = tick <= burstUntil;
        if (anywhere) bot.gunner.fire(on);
        return on;
    }

    /** the threats in a fight: the one it fights first, then the others it sees or lost a moment ago (at most MORE) */
    private static List<Vec3> threats(AiBot bot, TargetMemory.Contact fought, Vec3 eye) {
        List<Vec3> out = new java.util.ArrayList<>();
        out.add(eye);
        for (TargetMemory.Contact o : bot.memory.contacts()) {
            if (o == fought || out.size() > MORE) continue;
            if (o.state == TargetMemory.State.VISIBLE) out.add(new Vec3(o.x, o.y + 1.5, o.z));
            else if (o.state == TargetMemory.State.LOST) out.add(new Vec3(o.lostX, o.lostY + 1.5, o.lostZ));
        }
        return out;
    }

    /** where on a target it aims, as a part of its height, for the parts of it seen (Senses bits) */
    static double aimAt(int parts) {
        return switch (parts) {
            case Senses.HEAD | Senses.CHEST -> (HEAD + CHEST) / 2;
            case Senses.CHEST | Senses.LEGS -> (CHEST + LEGS) / 2;
            case Senses.HEAD, Senses.HEAD | Senses.LEGS -> HEAD;
            case Senses.LEGS -> LEGS;
            default -> CHEST;
        };
    }

    /** whether it fires blind when pinned down behind low cover (off only for the stands' "before") */
    static boolean blindOn = true;
    /** nearer than this, blocks, it rises and aims: blind fire is for a foe it cannot look at, not one at arm's length */
    static final double BLIND_NEAR = 6;
    // for the stands: ticks it fired blind, and ticks it held it back for a wall between it and there
    public int blindTicks, blindWalled;

    /**
     * Pinned down behind low cover (shots close past it: AiProne.pinned), the place they are known to be in the open:
     * it stays down and holds its gun up over the cover, with no look along it, for bursts at that place (its shots
     * spread three times the gun's: AiGunner.blind). Not into a wall: a block that stops bullets between its gun and
     * there (past its own cover, which the gun is held up over), and it holds fire. aimThere: it aims at that place
     * (a target lost); else its aim stays as it is (a target it follows, in a fight: no new spot each tick for its
     * hand). Whether it fires blind this tick.
     */
    private boolean blindFire(AiBot bot, ServerLevel level, Vec3 at, boolean aimThere, long tick) {
        AiGunner g = bot.gunner;
        if (!blindOn || bot.prone.lying() || !bot.prone.pinned(tick) || g.reloading() || g.loaded() == 0) return false;
        // from where its gun will be: in its raised hand over its head, turned to the place (the bullet starts there)
        float yaw = (float) Math.toDegrees(Math.atan2(at.z - bot.mob.getZ(), at.x - bot.mob.getX())) - 90F;
        Vec3 gun = bot.mob.position().add(AiPoseGeometry.blindMuzzle(AiPose.CROUCH, 0F, yaw, bot.mob.isLeftHanded()));
        // a block over it in the way of its hand: the shot starts at its eye (MixinAiLeanShot), and is checked from there
        Vec3 eye = bot.mob.position().add(0, AiPose.CROUCH_SIZE.eyeHeight(), 0);
        if (!VoxelRay.clear(AiBots.voxels(level), eye.x, eye.y, eye.z, gun.x, gun.y, gun.z, Voxel.MOVE_MASK)) gun = eye;
        if (VoxelRay.blocked(AiBots.voxels(level), gun.x, gun.y, gun.z, at.x, at.y, at.z, Voxel.PROTECT_MASK) >= 0) {
            blindWalled++;
            return false;
        }
        crouch = true;
        g.blind = true;
        if (aimThere) g.aim(at);
        g.fire(burst(bot, tick, false));
        blindTicks++;
        why = Why.BLIND;
        return true;
    }

    /** a reload of its gun is one to lie down for */
    static boolean longReload(AiGunner g) {
        return g.reloadTicks() >= AiProne.LONG_RELOAD;
    }

    /**
     * Where it looks out from this time: one of its cover's places to shoot from that none of its own side holds,
     * another than last time where it can; held as its own.
     */
    private long choosePeek(AiBot bot) {
        long[] all = spot.peeks();
        long[] taken = AiBots.friendsSpots(bot)[1];
        long[] free = new long[all.length];
        int n = 0;
        for (long k : all) {
            boolean held = false;
            for (long t : taken) held |= t == k;
            if (!held) free[n++] = k;
        }
        long pick;
        if (n == 0) pick = spot.peek();
        else if (n == 1) pick = free[0];
        else {
            // another than last time
            int i = bot.mob.getRandom().nextInt(n);
            if (free[i] == lastPeek) i = (i + 1 + bot.mob.getRandom().nextInt(n - 1)) % n;
            pick = free[i];
        }
        peeksHere = pick == lastPeek ? peeksHere + 1 : 1;
        coverPeeks++;
        longestPeekRun = Math.max(longestPeekRun, peeksHere);
        lastPeek = pick;
        AiBots.claim(bot, spot.cell(), pick);
        return pick;
    }

    /** leaves its cover for other cover (not this one again for a while) */
    private void shift(AiBot bot, long tick, String what) {
        unreachable.add(spot.cell());
        drop(bot);
        shifts++;
        lastShift = tick;
        nextQuery = tick;
        event(bot, tick, what);
    }

    /**
     * A target lost: once it has looked out for them and not seen them (or has no cover to look from), out of sight
     * PUSH_MIN to PUSH_MAX ticks, it goes for them where they were last seen.
     */
    private boolean pushes(AiBot bot, TargetMemory.Contact c, long tick) {
        if (c.lostTick != pushLost) {
            pushLost = c.lostTick;
            pushAt = c.lostTick + PUSH_MIN + bot.mob.getRandom().nextInt(PUSH_MAX - PUSH_MIN + 1);
        }
        if (mode == Mode.SEARCH) return true;
        // in a squad: two at most go after it, and only while one of the rest covers them
        if (!AiSquads.mayHunt(bot, c.id) || !AiSquads.mayMove(bot)) return false;
        return AiChoice.ask(bot, Ask.PUSH_NOW, tick >= pushAt && (spot == null || blindLost == c.lostTick), tick);
    }

    /** whether a looks at b: b's eyes within 20 degrees of where a's eyes point */
    static boolean watches(Entity a, Entity b) {
        Vec3 v = a.getViewVector(1F), d = AiLean.eye(b).subtract(AiLean.eye(a));
        double len = d.length();
        return len > 1e-3 && v.dot(d) / len >= WATCHED_COS;
    }

    /** the answering burst's ticks: about the skill's, a third either way */
    private static int answerTicks(AiBot bot, AiSkill skill) {
        int mean = skill.answerBurst(), spread = Math.max(1, mean / 3);
        return mean - spread + bot.mob.getRandom().nextInt(2 * spread + 1);
    }

    /** a step and a half to the left or right of the line to at, to a cell the map has */
    private void sidestep(AiBot bot, ServerLevel level, Vec3 at) {
        double dx = at.x - bot.mob.getX(), dz = at.z - bot.mob.getZ(), len = Math.hypot(dx, dz);
        if (len < 1e-3) return;
        double px = -dz / len, pz = dx / len;
        int side = bot.mob.getRandom().nextBoolean() ? 1 : -1;
        long here = bot.mob.blockPosition().asLong(), fallback = Long.MIN_VALUE;
        double eye = bot.mob.getEyeHeight();
        // a step or two to a side, the first from which no one of its own is in the line (a step the wrong way, or too
        // short, left it in the line, and it stepped back and forth)
        for (double far : new double[] {1.5, 3}) {
            for (int k = 0; k < 2; k++, side = -side) {
                long cell = PathSearch.snap(TacticalMap.of(level), bot.mob.getX() + px * side * far, bot.mob.getY(), bot.mob.getZ() + pz * side * far);
                if (cell == Long.MIN_VALUE || cell == here) continue;
                Vec3 from = new Vec3(BlockPos.getX(cell) + 0.5, BlockPos.getY(cell) + eye, BlockPos.getZ(cell) + 0.5);
                if (!AiBots.friendInLine(level, bot.mob, from, at, AiGunner.CLEAR_CONE)) {
                    bot.mover.goTo(cell, 0.35, WALK);
                    shifts++;
                    return;
                }
                if (fallback == Long.MIN_VALUE) fallback = cell;
            }
        }
        if (fallback != Long.MIN_VALUE) {
            bot.mover.goTo(fallback, 0.35, WALK);
            shifts++;
        }
    }

    /** a cover query against the threats; goes to the best spot found */
    private boolean takeCover(AiBot bot, ServerLevel level, TacticalMap map, List<Vec3> threats, boolean lookOut) {
        return takeCover(bot, level, map, threats, lookOut, false);
    }

    /** fight: from a target it sees, a place it can shoot from counts for more */
    private boolean takeCover(AiBot bot, ServerLevel level, TacticalMap map, List<Vec3> threats, boolean lookOut, boolean fight) {
        CoverQuery.Spot found = query(bot, level, map, threats, lookOut, fight);
        if (found == null) return false;
        go(bot, level, found, threats);
        return true;
    }

    private CoverQuery.@Nullable Spot query(AiBot bot, ServerLevel level, TacticalMap map, List<Vec3> threats, boolean lookOut, boolean fight) {
        return query(bot, level, map, threats, lookOut, fight, null);
    }

    /** flankFrom: its squad's middle, for cover off to the side of the threat from it (AiSquads); null: any */
    private CoverQuery.@Nullable Spot query(AiBot bot, ServerLevel level, TacticalMap map, List<Vec3> threats, boolean lookOut, boolean fight,
            @Nullable Vec3 flankFrom) {
        CoverQuery.Params p = new CoverQuery.Params();
        p.flankFrom = flankFrom;
        if (flankFrom != null) p.radius = FLANK_RADIUS;
        // from what it only heard: cover it can look out of (low, or high with a peek); none such, it stays and watches
        p.needPeek = lookOut;
        p.current = spot == null ? Long.MIN_VALUE : spot.cell();
        // apart from its own side: not by their cover, not out of their peek
        long[][] friends = AiBots.friendsSpots(bot);
        p.friendCovers = friends[0];
        p.friendPeeks = friends[1];
        if (fight) {
            p.peekBonus = CoverQuery.PEEK_FIGHT;
            p.othersSoft = true;
            // rays for the other threats and the places to shoot from
            p.rayBudget = 48;
        }
        // as far from the threat as its gun is best at
        p.fightAt = bot.gunner.fightAt();
        var held = AiBots.heldByOthers(bot);
        List<CoverQuery.Spot> found = CoverQuery.find(map, AiBots.voxels(level), bot.mob.position(), threats, p,
                k -> held.test(k) || unreachable.contains(k), CoverQuery.POOL);
        // the pool is found whatever is asked for; which of it: the formula's best, or the imitation's pick (AiChoice)
        return found.isEmpty() ? null : found.get(AiChoice.rank(bot, found, level.getGameTime()));
    }

    /** to this cover, running, out of the threat's sight where it can */
    private void go(AiBot bot, ServerLevel level, CoverQuery.Spot to, List<Vec3> threats) {
        spot = to;
        quickLook = false;
        peeksHere = 0;
        coverPeeks = 0;
        lastPeek = Long.MIN_VALUE;
        AiBots.claim(bot, spot.cell(), spot.peek());
        Vec3 t = threats.get(0);
        bot.mover.costs(new ExposureCost(TacticalMap.of(level), t.x, t.y, t.z));
        bot.stoop.from(t);
        bot.mover.goTo(spot.cell(), 0.35, RUN);
        mode(Mode.TO_COVER, level.getGameTime());
        covers++;
    }

    private void drop(AiBot bot) {
        spot = null;
        AiBots.claim(bot, Long.MIN_VALUE);
        bot.mover.costs(PathSearch.Cost.NONE);
        bot.stoop.from(null);
    }

    // ------------------------------------------------------------------ in a squad

    /**
     * Its squad's flanker, a target out of sight: once a loss, while one of the rest covers it, to cover that hides it
     * from the place the target went out of sight and looks out on it from 45 degrees or more off the line from there
     * to the squad (the side the target does not watch). There it looks out soon (coverCycle), and the fight goes on
     * from there. Whether it went.
     */
    private boolean flank(AiBot bot, ServerLevel level, TargetMemory.Contact c, long tick) {
        if (c.lostTick == flankLost || tick < nextFlank || !AiSquads.flanks(bot, c.id) || bot.prone.lying() || mode == Mode.SEARCH
                || mode == Mode.TO_COVER && !bot.mover.arrived())
            return false;
        Vec3 middle = AiSquads.middle(bot);
        if (middle == null || !AiSquads.mayMove(bot)) return false;
        if (c.lostTick != triedLost) {
            triedLost = c.lostTick;
            tried = 0;
        }
        if (tried >= FLANK_TRIES) return false;
        tried++;
        nextFlank = tick + FLANK_AGAIN;
        flankTries++;
        List<Vec3> from = List.of(new Vec3(c.lostX, c.lostY + 1.5, c.lostZ));
        CoverQuery.Spot to = query(bot, level, TacticalMap.of(level), from, true, true, middle);
        if (to == null || spot != null && to.cell() == spot.cell()) return false;
        flankLost = c.lostTick;
        go(bot, level, to, from);
        quickLook = true;
        // a look out from there, and only then a hunt
        blindLost = Long.MIN_VALUE;
        flanks++;
        why = Why.FLANK;
        event(bot, tick, "its squad's flanker: round the side");
        return true;
    }

    /**
     * One of its squad on the move, a target out of sight a moment ago: from its cover, a burst at the place the
     * target went out of sight every few seconds (out at its peek, or up over low cover), and back in; the target
     * there keeps its head down, or shows it.
     */
    private void suppress(AiBot bot, TargetMemory.Contact c, long tick) {
        CoverQuery.Spot s = spot;
        Vec3 lost = new Vec3(c.lostX, c.lostY + 1.1, c.lostZ);
        watch = lost;
        AiGunner g = bot.gunner;
        g.aim(lost);
        boolean low = s.peek() == s.cell() || !s.hasPeek();
        why = Why.SUPPRESS;
        if (!suppressing) {
            g.fire(false);
            if (mode == Mode.PEEK || mode == Mode.HOLD) mode(Mode.COVER, tick);
            if (!bot.mover.goalIs(s.cell())) bot.mover.goTo(s.cell(), 0.35, WALK);
            boolean reload = g.loaded() >= 0 && g.loaded() < Math.max(1, g.capacity() / 3) && g.ammo.any();
            if (reload) g.reload();
            crouch = low && bot.mover.arrived();
            if (bot.mover.arrived() && !reload && !g.reloading() && g.loaded() != 0 && tick >= nextSuppress) {
                suppressing = true;
                suppressSince = tick;
                burstUntil = 0;
                suppressions++;
                if (!low) {
                    // a burst for others is no look out of its own: not counted toward other cover (choosePeek)
                    int here = peeksHere, ofCover = coverPeeks, run = longestPeekRun;
                    long last = lastPeek;
                    peekOut(bot, lost);
                    peeksHere = here;
                    coverPeeks = ofCover;
                    longestPeekRun = run;
                    lastPeek = last;
                    mode(Mode.PEEK, tick);
                    peekSince = tick;
                }
                event(bot, tick, "one of its squad on the move: a burst where they went");
            }
            return;
        }
        // out, or up over low cover: one burst at the place, then back in
        boolean there = bot.mover.arrived();
        g.fire(there && bot.prone.upright(tick) && burst(bot, tick, false));
        boolean done = there && burstUntil != 0 && tick > burstUntil;
        if (done || tick - suppressSince > PEEK_BLIND + 40 || g.loaded() == 0) {
            suppressing = false;
            g.fire(false);
            nextSuppress = tick + SUPPRESS_GAP_MIN + bot.mob.getRandom().nextInt(SUPPRESS_GAP_MAX - SUPPRESS_GAP_MIN);
            if (!low) {
                bot.mover.goTo(s.cell(), 0.35, WALK);
                mode(Mode.COVER, tick);
            }
        }
    }

    // ------------------------------------------------------------------ out of sight

    /** it stays (in cover if it has any) and watches where they were last seen */
    private void hold(AiBot bot, ServerLevel level, TargetMemory.Contact c, long tick) {
        Vec3 lost = new Vec3(c.lostX, c.lostY + 1.1, c.lostZ);
        watch = lost;
        bot.gunner.fire(false);
        bot.gunner.aim(lost);
        if (mode == Mode.PEEK && spot != null) bot.mover.goTo(spot.cell(), 0.35, WALK);
        if (spot == null && tick >= nextQuery) {
            nextQuery = tick + QUERY_EVERY * 3;
            takeCover(bot, level, TacticalMap.of(level), List.of(new Vec3(c.lostX, c.lostY + 1.5, c.lostZ)), false);
        }
        mode(Mode.HOLD, tick);
        why = spot != null && !bot.mover.arrived() ? Why.HOLD_RUN : bot.gunner.reloading() ? Why.HOLD_RELOAD : Why.HOLD;
        // pinned down behind low cover while it waits: bursts blind over it where they went, as in coverCycle
        AiGunner g = bot.gunner;
        if (spot != null && bot.mover.arrived() && (spot.peek() == spot.cell() || !spot.hasPeek())
                && !(g.loaded() >= 0 && g.loaded() < Math.max(1, g.capacity() / 3) && g.ammo.any()) && lost.distanceTo(bot.mob.position()) >= BLIND_NEAR
                && blindFire(bot, level, lost, true, tick)) return;
        if (spot != null && bot.mover.arrived() && g.loaded() >= 0 && g.loaded() < g.capacity() && g.ammo.any()) g.reload();
    }

    /** slowly through the cells round where they were lost that it cannot see, nearest first */
    private void search(AiBot bot, ServerLevel level, TargetMemory.Contact c, long tick) {
        if (mode != Mode.SEARCH) {
            drop(bot);
            bot.mover.stop();
            searchCell = Long.MIN_VALUE;
            why = Why.SEARCH;
        }
        mode(Mode.SEARCH, tick);
        bot.gunner.fire(false);
        var cleared = bot.clearedFor(c);
        boolean reached = searchCell != Long.MIN_VALUE && (bot.mover.arrived() || bot.mover.failed());
        if (reached) {
            cleared.add(searchCell);
            searchCell = Long.MIN_VALUE;
        }
        if (searchCell == Long.MIN_VALUE && tick >= nextSearchPick) {
            nextSearchPick = tick + 10;
            double r = bot.memory.searchRadius(c, tick);
            long[] spots = SearchSpots.pick(TacticalMap.of(level), AiBots.voxels(level), bot.mob.getEyePosition(), c.lostX, c.lostY, c.lostZ, r, 1,
                    cleared, bot.mob.getRandom(), 24);
            if (spots.length > 0) {
                searchCell = spots[0];
                searchSpots++;
                bot.mover.costs(PathSearch.Cost.NONE);
                bot.mover.goTo(searchCell, 0.6, CREEP);
                why = Why.SEARCH;
            } else why = Why.SEARCH_NONE;
        }
        if (searchCell != Long.MIN_VALUE) {
            Vec3 at = new Vec3(BlockPos.getX(searchCell) + 0.5, BlockPos.getY(searchCell) + 1.1, BlockPos.getZ(searchCell) + 0.5);
            watch = at;
            bot.gunner.aim(at);
        } else {
            watch = new Vec3(c.lostX, c.lostY + 1.1, c.lostZ);
            bot.gunner.aim(watch);
        }
    }

    // ------------------------------------------------------------------ heard only, nothing

    private void alert(AiBot bot, ServerLevel level, TargetMemory.Noise n, long tick) {
        Vec3 at = new Vec3(n.x, n.y + 1.1, n.z);
        watch = at;
        bot.gunner.fire(false);
        bot.gunner.aim(at);
        if (n.tick == checkNoise) {
            investigate(bot, level, n, at, tick);
            return;
        }
        boolean took = false;
        if ((n.loud || n.hurt) && spot == null && tick >= nextQuery) {
            nextQuery = tick + QUERY_EVERY * 2;
            took = takeCover(bot, level, TacticalMap.of(level), List.of(new Vec3(n.x, n.y + 1.5, n.z)), true);
        }
        // in high cover from it: it does not hide for good, it looks out the way it came from now and then
        if (!took && spot != null && spot.hasPeek() && spot.peek() != spot.cell() && (mode != Mode.TO_COVER || bot.mover.arrived())) {
            lookOut(bot, tick);
            return;
        }
        if (mode != Mode.TO_COVER || bot.mover.arrived()) mode(Mode.ALERT, tick);
        // on its way to cover still: the reason it went stays
        if (took) why = n.hurt ? Why.HIT_COVER : Why.NOISE_COVER;
        else if (mode == Mode.ALERT) why = n.hurt ? Why.ALERT_HIT : n.loud ? Why.ALERT_LOUD : Why.ALERT_SOUND;
    }

    /**
     * Heard only, in high cover from it: it hides a while, then looks out from its peek the way the noise came from,
     * gets back in and looks again every few seconds, till it sees someone ({@link #combat} goes on from there, out
     * at its peek or in) or forgets the noise.
     */
    private void lookOut(AiBot bot, long tick) {
        CoverQuery.Spot s = spot;
        if (mode != Mode.COVER && mode != Mode.PEEK) {
            mode(Mode.COVER, tick);
            hideUntil = tick + HIDE_MIN + bot.mob.getRandom().nextInt(HIDE_MAX - HIDE_MIN);
        }
        if (mode == Mode.COVER) {
            why = Why.ALERT_HIDE;
            if (!bot.mover.goalIs(s.cell())) bot.mover.goTo(s.cell(), 0.35, WALK);
            AiGunner g = bot.gunner;
            boolean reload = g.loaded() >= 0 && g.loaded() < g.capacity() && g.ammo.any();
            if (reload) g.reload();
            if (bot.mover.arrived() && !reload && !g.reloading() && AiChoice.ask(bot, Ask.LOOK_NOISE, tick >= hideUntil && tick >= lookAgain, tick)) {
                peekOut(bot, watch != null ? watch : AiLean.eye(bot.mob).add(bot.mob.getLookAngle().scale(16)));
                mode(Mode.PEEK, tick);
                peekSince = tick;
                peekThere = 0;
                burstUntil = 0;
            }
        }
        if (mode == Mode.PEEK) {
            why = Why.ALERT_PEEK;
            boolean there = bot.mover.arrived();
            if (there && peekThere == 0) peekThere = tick;
            if (there && tick - peekThere > PEEK_BLIND || tick - peekSince > PEEK_BLIND + 60) {
                // one look a noise: nothing seen, it goes to see where it came from, carefully (a new noise is
                // looked at in turn)
                TargetMemory.Noise n = bot.memory.noise();
                if (n != null) {
                    checkNoise = n.tick;
                    drop(bot);
                    // the way to its peek, failed or not, is no way to the noise
                    bot.mover.stop();
                    mode(Mode.ALERT, tick);
                    event(bot, tick, "looked out, nothing: goes to see where the noise came from");
                } else {
                    bot.mover.goTo(s.cell(), 0.35, WALK);
                    mode(Mode.COVER, tick);
                }
                hideUntil = tick + HIDE_MIN;
                lookAgain = tick + LOOK_AGAIN_MIN + bot.mob.getRandom().nextInt(LOOK_AGAIN_MAX - LOOK_AGAIN_MIN);
            }
        }
    }

    /** it looked out for a noise and saw nothing: it creeps to where the noise came from, gun up, and there forgets it */
    private void investigate(AiBot bot, ServerLevel level, TargetMemory.Noise n, Vec3 at, long tick) {
        mode(Mode.ALERT, tick);
        why = Why.INVESTIGATE;
        double d = Math.hypot(n.x - bot.mob.getX(), n.z - bot.mob.getZ());
        if (d < CHECKED || bot.mover.failed() || tick - n.tick > CHECK_FOR) {
            bot.memory.quiet();
            checkNoise = Long.MIN_VALUE;
            bot.mover.stop();
            return;
        }
        if (bot.mover.goal() == Long.MIN_VALUE || bot.mover.arrived()) {
            long cell = PathSearch.snap(TacticalMap.of(level), n.x, n.y, n.z);
            if (cell == Long.MIN_VALUE) {
                bot.memory.quiet();
                checkNoise = Long.MIN_VALUE;
                return;
            }
            bot.mover.costs(PathSearch.Cost.NONE);
            bot.mover.goTo(cell, 1.5, CREEP);
        }
    }

    private void idle(AiBot bot, long tick) {
        if (mode != Mode.IDLE) {
            bot.gunner.fire(false);
            bot.gunner.aim(null);
            drop(bot);
            bot.mover.stop();
            watch = null;
        }
        mode(Mode.IDLE, tick);
        why = Why.IDLE;
    }

    /** for the debug view: what it does, whom it knows of and how, what it decided and why (AiThoughts), a line each */
    public String describe(AiBot bot) {
        return AiThoughts.describe(this, bot);
    }
}
