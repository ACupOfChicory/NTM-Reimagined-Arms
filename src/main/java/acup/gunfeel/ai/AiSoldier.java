package acup.gunfeel.ai;

import java.util.List;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ai.AiThoughts.Why;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
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
 * <li>Heard only (a shot, a blast, a hit): it turns to it, and into cover from it if it was loud.</li>
 * <li>Nothing known: it stays where it is.</li>
 * </ul>
 * Decides every few ticks (its look period) and acts every tick. Server thread.
 */
public final class AiSoldier implements AiBrain {
    public enum Mode {
        IDLE, ALERT, TO_COVER, COVER, PEEK, HOLD, SEARCH
    }

    // timings, ticks
    static final int QUERY_EVERY = 20, EXPOSED_EVERY = 10, HIDE_MIN = 15, HIDE_MAX = 40, BURST_MIN = 10, BURST_MAX = 25, PEEK_BLIND = 20;
    static final double RUN = 1.3, WALK = 1.0, CREEP = 0.7;

    Mode mode = Mode.IDLE;
    long modeSince;
    CoverQuery.@Nullable Spot spot;
    long nextQuery, nextExposedCheck, hideUntil, burstUntil, peekSince, nextSearchPick;
    long searchCell = Long.MIN_VALUE;
    /** for the debug view: where it watches or goes */
    @Nullable
    Vec3 watch;
    // for the stands
    public int covers, relocations, bursts, searchSpots, shifts;
    /** ticks it holds fire with one of its own in the line before it moves */
    static final int BLOCKED_MOVE = 20;
    /** lying down: never closer than NEVER blocks to the target; in the open from FAR on (or pinned down) */
    static final double PRONE_NEVER = 10, PRONE_FAR = 30;
    /** what it decided last and why (the debug view, the journal): set where it decides, it changes nothing */
    Why why = Why.IDLE;
    /** what the decision journal said of it last */
    private final AiThoughts thoughts = new AiThoughts();

    private void mode(Mode m, long tick) {
        if (mode != m) {
            mode = m;
            modeSince = tick;
        }
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

    @Override
    public void tick(AiBot bot, long tick) {
        decide(bot, tick);
        if (AiThoughts.on()) thoughts.note(this, bot, tick);
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
        if (mode == Mode.TO_COVER && spot != null && bot.mover.failed()) {
            unreachable.add(spot.cell());
            drop(bot);
            nextQuery = tick;
            event(bot, tick, "cover out of reach: gives it up");
        }
        TargetMemory.Contact c = bot.memory.focus(bot.mob.getX(), bot.mob.getZ());
        TargetMemory.Noise n = bot.memory.noise();
        // shot from where it does not look: it turns there, and its cover, seen into from there, is no cover
        if (n != null && n.hurt && n.tick != hitSeen && (c == null || c.state != TargetMemory.State.VISIBLE)) {
            hitSeen = n.tick;
            hit(bot, level, n, tick);
            return;
        }
        if (c != null && c.state == TargetMemory.State.VISIBLE) combat(bot, level, c, tick);
        // lost from sight because it went into cover itself: it goes on hiding and looking out where they were
        else if (c != null && c.state == TargetMemory.State.LOST && spot != null && c.lostTick != blindLost
                && (mode == Mode.TO_COVER || mode == Mode.COVER || mode == Mode.PEEK)) coverCycle(bot, c, tick);
        else if (c != null && c.state == TargetMemory.State.LOST) hold(bot, level, c, tick);
        else if (c != null && c.state == TargetMemory.State.SEARCH) search(bot, level, c, tick);
        else if (n != null) alert(bot, level, n, tick);
        else idle(bot, tick);
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
        if (spot != null) {
            long s = spot.cell();
            if (VoxelRay.clear(AiBots.voxels(level), from.x, from.y, from.z, BlockPos.getX(s) + 0.5, BlockPos.getY(s) + 1.6, BlockPos.getZ(s) + 0.5,
                    Voxel.SIGHT)) {
                drop(bot);
                relocations++;
                event(bot, tick, "cover seen into from the hit: gives it up");
            }
        }
        if (spot == null) {
            nextQuery = tick + QUERY_EVERY;
            if (takeCover(bot, level, TacticalMap.of(level), List.of(from))) why = Why.HIT_COVER;
        }
    }

    // ------------------------------------------------------------------ in sight

    private void combat(AiBot bot, ServerLevel level, TargetMemory.Contact c, long tick) {
        Vec3 eye = new Vec3(c.x, c.y + 1.5, c.z);
        double dist = bot.mob.position().distanceTo(new Vec3(c.x, c.y, c.z));
        // in sight: its eye follows the target tick by tick (looks for it come every few ticks; once they lose it,
        // this is not run and it aims where it went)
        Entity seen = level.getEntity(c.id);
        Vec3 chest = seen != null ? seen.position().add(0, seen.getBbHeight() * 0.6, 0) : new Vec3(c.x, c.y + 1.1, c.z);
        watch = chest;
        AiGunner g = bot.gunner;
        if (seen != null) g.track(c.id, chest, tick);
        else g.aim(chest);
        TacticalMap map = TacticalMap.of(level);
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
        if (spot == null && tick >= nextQuery) {
            nextQuery = tick + QUERY_EVERY + bot.mob.getRandom().nextInt(10);
            if (takeCover(bot, level, map, List.of(eye))) why = Why.SEEN_COVER;
        }
        if (spot == null) {
            // no cover to be had: fight where it stands (lying, far off or pinned down by shots flying past), a step
            // aside when one of its own is in the line
            mode(Mode.COVER, tick);
            bot.prone.watchShots(level, tick);
            if (dist >= PRONE_NEVER && (dist >= PRONE_FAR || bot.prone.pinned(tick))) bot.prone.want();
            why = dist < PRONE_NEVER ? Why.NO_COVER : dist >= PRONE_FAR ? Why.NO_COVER_FAR : bot.prone.pinned(tick) ? Why.NO_COVER_PINNED : Why.NO_COVER;
            burst(bot, tick, true);
            if (g.blockedTicks > BLOCKED_MOVE) {
                g.blockedTicks = 0;
                sidestep(bot, level, chest);
                event(bot, tick, "friend in the line: a step aside");
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
            g.fire(false);
            return;
        }
        boolean low = spot.peek() == spot.cell();
        if (mode == Mode.TO_COVER || mode == Mode.ALERT || mode == Mode.HOLD || mode == Mode.SEARCH || mode == Mode.IDLE) {
            mode(Mode.COVER, tick);
            hideUntil = tick + (low ? 0 : HIDE_MIN + bot.mob.getRandom().nextInt(HIDE_MAX - HIDE_MIN));
        }
        if (mode == Mode.COVER) {
            if (bot.mover.goal() != spot.cell()) bot.mover.goTo(spot.cell(), 0.35, WALK);
            boolean there = bot.mover.arrived();
            boolean reload = g.loaded() >= 0 && g.loaded() < Math.max(1, g.capacity() / 3) && g.ammo.any();
            if (low && there) {
                // behind low cover: down between bursts and to reload, up a moment before the next burst
                boolean pause = burstUntil != 0 && tick > burstUntil && tick <= burstUntil + 20 - AiProne.RISE;
                if ((pause || reload || g.reloading()) && dist >= PRONE_NEVER) bot.prone.want();
                g.fire(bot.prone.upright(tick) && !reload && burst(bot, tick, false));
            } else g.fire(false);
            if (reload) g.reload();
            // on its way in: the reason it is coming in stays
            if (there) why = reload || g.reloading() ? (low ? Why.LOW_RELOAD : Why.RELOAD) : g.dry() ? Why.DRY : low ? Why.LOW_FIGHT
                    : spot.hasPeek() ? Why.HIDE : Why.NO_PEEK;
            if (!low && there && !reload && !g.reloading() && tick >= hideUntil && spot.hasPeek() && g.loaded() != 0) {
                bot.mover.goTo(spot.peek(), 0.35, WALK);
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
            if (done || tick - peekSince > PEEK_BLIND + 40 || g.loaded() == 0) {
                g.fire(false);
                bot.mover.goTo(spot.cell(), 0.35, WALK);
                mode(Mode.COVER, tick);
                hideUntil = tick + HIDE_MIN + bot.mob.getRandom().nextInt(HIDE_MAX - HIDE_MIN);
                why = g.loaded() == 0 ? Why.BACK_EMPTY : done ? Why.BACK_DONE : Why.BACK_LONG;
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
            hideUntil = tick + HIDE_MIN + bot.mob.getRandom().nextInt(HIDE_MAX - HIDE_MIN);
        }
        if (mode == Mode.COVER) {
            if (bot.mover.goal() != s.cell()) bot.mover.goTo(s.cell(), 0.35, WALK);
            boolean reload = g.loaded() >= 0 && g.loaded() < g.capacity() && g.ammo.any();
            if (reload) g.reload();
            boolean low = s.peek() == s.cell() || !s.hasPeek();
            // behind low cover it hides lying, and gets up to look
            if (low && bot.mover.arrived() && (tick < hideUntil || reload || g.reloading())) bot.prone.want();
            why = reload || g.reloading() ? Why.RELOAD : low ? Why.LOW_HIDE : Why.LOST_HIDE;
            if (bot.mover.arrived() && tick >= hideUntil && !reload && !g.reloading()) {
                if (low) {
                    why = Why.LOW_LOOK;
                    if (bot.prone.upright(tick)) blindLost = c.lostTick;
                    return;
                }
                bot.mover.goTo(s.peek(), 0.35, WALK);
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
        if (burstUntil == 0 || tick > burstUntil + 20) {
            burstUntil = tick + BURST_MIN + bot.mob.getRandom().nextInt(BURST_MAX - BURST_MIN);
            bursts++;
        }
        boolean on = tick <= burstUntil;
        if (anywhere) bot.gunner.fire(on);
        return on;
    }

    /** a step and a half to the left or right of the line to at, to a cell the map has */
    private void sidestep(AiBot bot, ServerLevel level, Vec3 at) {
        double dx = at.x - bot.mob.getX(), dz = at.z - bot.mob.getZ(), len = Math.hypot(dx, dz);
        if (len < 1e-3) return;
        double px = -dz / len, pz = dx / len;
        int side = bot.mob.getRandom().nextBoolean() ? 1 : -1;
        long here = bot.mob.blockPosition().asLong();
        for (int k = 0; k < 2; k++, side = -side) {
            long cell = PathSearch.snap(TacticalMap.of(level), bot.mob.getX() + px * side * 1.5, bot.mob.getY(), bot.mob.getZ() + pz * side * 1.5);
            if (cell != Long.MIN_VALUE && cell != here) {
                bot.mover.goTo(cell, 0.35, WALK);
                shifts++;
                return;
            }
        }
    }

    /** a cover query against the threats; goes to the best spot found */
    private boolean takeCover(AiBot bot, ServerLevel level, TacticalMap map, List<Vec3> threats) {
        CoverQuery.Params p = new CoverQuery.Params();
        p.current = spot == null ? Long.MIN_VALUE : spot.cell();
        var held = AiBots.heldByOthers(bot);
        List<CoverQuery.Spot> found = CoverQuery.find(map, AiBots.voxels(level), bot.mob.position(), threats, p,
                k -> held.test(k) || unreachable.contains(k), 1);
        if (found.isEmpty()) return false;
        spot = found.get(0);
        AiBots.claim(bot, spot.cell());
        Vec3 t = threats.get(0);
        bot.mover.costs(new ExposureCost(map, t.x, t.y, t.z));
        bot.mover.goTo(spot.cell(), 0.35, RUN);
        mode(Mode.TO_COVER, level.getGameTime());
        covers++;
        return true;
    }

    private void drop(AiBot bot) {
        spot = null;
        AiBots.claim(bot, Long.MIN_VALUE);
        bot.mover.costs(PathSearch.Cost.NONE);
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
            takeCover(bot, level, TacticalMap.of(level), List.of(new Vec3(c.lostX, c.lostY + 1.5, c.lostZ)));
        }
        if (spot != null && bot.mover.arrived() && bot.gunner.loaded() >= 0 && bot.gunner.loaded() < bot.gunner.capacity() && bot.gunner.ammo.any())
            bot.gunner.reload();
        mode(Mode.HOLD, tick);
        why = spot != null && !bot.mover.arrived() ? Why.HOLD_RUN : bot.gunner.reloading() ? Why.HOLD_RELOAD : Why.HOLD;
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
        boolean took = false;
        if ((n.loud || n.hurt) && spot == null && tick >= nextQuery) {
            nextQuery = tick + QUERY_EVERY * 2;
            took = takeCover(bot, level, TacticalMap.of(level), List.of(new Vec3(n.x, n.y + 1.5, n.z)));
        }
        if (mode != Mode.TO_COVER || bot.mover.arrived()) mode(Mode.ALERT, tick);
        // on its way to cover still: the reason it went stays
        if (took) why = n.hurt ? Why.HIT_COVER : Why.NOISE_COVER;
        else if (mode == Mode.ALERT) why = n.hurt ? Why.ALERT_HIT : n.loud ? Why.ALERT_LOUD : Why.ALERT_SOUND;
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
