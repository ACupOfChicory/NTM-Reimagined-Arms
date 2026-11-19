package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.CoreServices;
import acup.gunfeel.PosLong;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.BodyControl;

/**
 * An AI mob's legs: it asks for a path to a cell (searched off the server thread by {@link PathSearch} over the
 * tactical map), hands the path to the mob's own navigation to walk (so the game's movement, jumping and swimming
 * apply), and asks again when the path ends short of the goal, the mob is stuck, or blocks along the path changed.
 * Server thread, from the mob's goal.
 *
 * <p>The mob is its Body, its navigation and MoveControl the version's BodyControl, the map a WalkMap (26.2's AiMover
 * passes its own). A search off the server thread reads only the map and what was copied for it on the server thread.
 */
public class AiMoverBase implements Stride {
    private static final BodyControl CONTROL = CoreServices.load(BodyControl.class, BodyControl.MISSING);
    /** AiSoldier.WALK, its walking speed (AiMoverWalkTest holds them the same) */
    static final double WALK = 1.0;
    /** nodes a search may expand */
    static final int MAX_NODES = 3000;
    /** how often the path's sections are checked against the map, ticks */
    static final int CHECK_EVERY = 10;
    /** tries at a goal before it gives up on it */
    static final int TRIES = 4;

    private final Body mob;
    private long goal = Long.MIN_VALUE;
    private double radius;
    /** where in the goal's cell it stands, from its middle, blocks (a step toward a corner to lean round it) */
    private double offX, offZ;
    private double speed = 1.0;
    /** a share of the speed it walks at (lying: a third) */
    private double slow = 1.0;
    private int tries;
    /** searches that came short for want of the map, no try spent on them; this many at most at a goal */
    private int waits;
    static final int MAP_WAITS = 12;
    /** keep still this tick (lying, to fire), the goal kept */
    private boolean hold;
    /** in the goal's cell since (the last steps, straight to its middle), and there long enough to count as arrived */
    private long inCellSince = Long.MIN_VALUE;
    private boolean settled;
    /** how long the last steps may take before the mob counts as there all the same (another mob in the way) */
    static final int SETTLE = 40;
    /**
     * At the goal by the cells, not by where it stands: the search's way was the one cell it stands in (that cell's middle
     * within the radius, the mob at its edge out of it). It steps to that cell's middle, from closeSince, SETTLE ticks at most.
     */
    private long closeCell = Long.MIN_VALUE, closeSince;
    /** a search's answer, with the goal count it was asked for */
    private record Answer(int gen, PathSearch.Result result) {
    }

    private volatile @Nullable Answer answer;
    private int gen;
    private boolean searching;
    /** the last search for this goal looked at every cell in reach, on the map as it is, and none was at the goal */
    private boolean noWay;
    /** the map's count of changed blocks when it was so */
    private long noWayAt;
    private long searchStarted;
    /** how near the goal across the nearest end of a part of a way it walked came, blocks (a wall round the goal: no nearer) */
    private double partBest = Double.MAX_VALUE;
    /** a part of a way walked to its end no nearer the goal than this past the nearest before: a try spent, blocks */
    static final double CLOSER = 2;
    /** off only for the stands' "before" (AiCheckWall): a part of a way never costs a try */
    static boolean partTries = true;
    private PathSearch.@Nullable Result walking;
    /** the stamps of the walked path's sections ({@link PathSearch.Result#keys}) it was last found good at */
    private int[] walkStamps = new int[0];
    /** the path crosses sections changed since and not built again yet: since this tick, or Long.MIN_VALUE */
    private long waitingSince = Long.MIN_VALUE;
    /** how long a walked path waits for its changed sections to be built again before another is searched, ticks */
    static final int REBUILD_WAIT = 20;
    private long lastCheck;
    /** the map's count of changed blocks at the last check: a walked path is checked the tick a block changes */
    private long seenChanges;
    private PathSearch.Cost cost = PathSearch.Cost.NONE;
    /** under fire: its paths begin no swim (AiWater.underFire); the last search kept out of water it meant to cross */
    private boolean dry, refused;
    // for the stands
    public int searches, failures, stuckJumps, kept;
    public long searchNanos;

    protected AiMoverBase(Body mob) {
        this.mob = mob;
    }

    /** go to within radius blocks of a cell (a BlockPos long); the same goal again changes nothing */
    public void goTo(long cell, double radius, double speed) {
        goTo(cell, radius, speed, 0, 0);
    }

    /** as above, to a point off the cell's middle by (ox, oz) blocks */
    public void goTo(long cell, double radius, double speed, double ox, double oz) {
        boolean other = speed != this.speed;
        this.speed = speed;
        if (cell == goal && radius == this.radius && ox == offX && oz == offZ) {
            // the same way at another pace (a run slowed to a walk to fire): the walk under way takes it
            if (other && walking != null) CONTROL.speed(mob, modifier());
            return;
        }
        goal = cell;
        this.radius = radius;
        offX = ox;
        offZ = oz;
        tries = waits = 0;
        noWay = false;
        partBest = Double.MAX_VALUE;
        inCellSince = Long.MIN_VALUE;
        closeCell = Long.MIN_VALUE;
        settled = false;
        walking = null;
        searching = false;
        gen++;
    }

    /** walks at this share of its speed from now (lying down, getting up) */
    public void slow(double share) {
        if (share == slow) return;
        slow = share;
        if (walking != null) CONTROL.speed(mob, modifier());
    }

    /**
     * Keeps still this tick, or walks on (lying, it fires only still: AiGunner.steady). The goal and a search under way
     * stay; the walk starts over when it moves again.
     */
    void hold(boolean on) {
        hold = on;
    }

    /**
     * The speed modifier for the game's walk at its speed and share. A mob's step grows with the square of the modifier
     * (its move control sets both its input and its speed from it, and the game's push is the two multiplied): a share
     * of the step, as a crawling player's third of a walk, is the root of it in the modifier.
     */
    private double modifier() {
        return speed * Math.sqrt(slow);
    }

    public void costs(PathSearch.Cost cost) {
        this.cost = cost;
    }

    /** a second cost added to the first whatever that is, its own owner's (AiBunker: the fire of slits) */
    private PathSearch.Cost avoid = PathSearch.Cost.NONE;

    public void avoid(PathSearch.Cost avoid) {
        this.avoid = avoid;
    }

    /**
     * Under fire its paths begin no swim (one that is swimming swims out); the fire over, a goal it gave up on for
     * want of a dry way is tried again.
     */
    void dry(boolean on) {
        if (on == dry) return;
        dry = on;
        if (!on && refused && (failed() || noWay)) {
            refused = false;
            tries = waits = 0;
            noWay = false;
        }
    }

    public void stop() {
        goal = Long.MIN_VALUE;
        inCellSince = Long.MIN_VALUE;
        closeCell = Long.MIN_VALUE;
        settled = false;
        walking = null;
        searching = false;
        gen++;
        CONTROL.stop(mob);
    }

    /** stop walking but keep the goal: the walk starts over when it moves again */
    public void pause() {
        walking = null;
        closeCell = Long.MIN_VALUE;
        searching = false;
        gen++;
        CONTROL.stop(mob);
    }

    public boolean idle() {
        return goal == Long.MIN_VALUE;
    }

    public long goal() {
        return goal;
    }

    /** whether it goes to the middle of this cell (not to a point off it: a step toward a corner to lean round it) */
    public boolean goalIs(long cell) {
        return goal == cell && offX == 0 && offZ == 0;
    }

    /**
     * At the goal: within its radius across (a tenth of a block at least) and a block up or down. Vanilla's walk ends up to
     * 0.45 off a cell's middle, so the last steps go straight there ({@link #tick}): a peek a block aside from cover is
     * out of it only at the middle. Kept from the middle that long ({@link #SETTLE}), in the cell counts.
     */
    public boolean arrived() {
        if (goal == Long.MIN_VALUE || Math.abs(mob.y() - PosLong.blockY(goal)) > 1.2) return false;
        double dx = mob.x() - (PosLong.blockX(goal) + 0.5 + offX), dz = mob.z() - (PosLong.blockZ(goal) + 0.5 + offZ);
        double r = Math.max(radius, 0.1);
        return dx * dx + dz * dz <= r * r || settled && inGoalCell();
    }

    private boolean inGoalCell() {
        return mob.blockX() == PosLong.blockX(goal) && mob.blockZ() == PosLong.blockZ(goal) && Math.abs(mob.y() - PosLong.blockY(goal)) <= 1.2;
    }

    /**
     * It runs to its goal now, faster than a walk (running low too; crawling is slower): it faces where it goes (a player sprints only ahead), its head
     * turning within its neck, and holds fire. Walking it may face anywhere (26.2's AiMoveControl).
     */
    public boolean runs() {
        return goal != Long.MIN_VALUE && modifier() > WALK + 1e-6 && !arrived() && !failed();
    }

    /** its mind sends it to a goal faster than a walk, whatever its legs make of it now (lying, it crawls) */
    public boolean wantsRun() {
        return goal != Long.MIN_VALUE && speed > WALK + 1e-6 && !arrived() && !failed();
    }

    /** the goal could not be reached after a few tries */
    public boolean failed() {
        return goal != Long.MIN_VALUE && tries >= TRIES;
    }

    /** for the stands' traces */
    public String state() {
        PathSearch.Result w = walking;
        return (goal == Long.MIN_VALUE ? "-" : PosLong.shortString(goal)) + (searching ? " searching" : "") + " walk "
                + (w == null ? "-" : w.cells.length + (w.reached ? "" : "?")) + (CONTROL.done(mob) ? " done" : "") + " tries " + tries
                + " s" + searches;
    }

    /** it has somewhere to go and may go now: not there, not given up on it, not kept still */
    public boolean wants() {
        return goal != Long.MIN_VALUE && !hold && !arrived() && !failed();
    }

    /** it is not at its goal and no search will take it there: every cell it can walk to was looked at */
    public boolean noWay() {
        return goal != Long.MIN_VALUE && noWay && !arrived();
    }

    /** the path it walks was searched on an older map than the one kept now (found out within {@link #CHECK_EVERY} ticks) */
    public boolean stale(WalkMap map) {
        PathSearch.Result w = walking;
        return w != null && !fresh(map, w, walkStamps);
    }

    /** the last search kept out of water it meant to swim (under fire: dry) */
    public boolean refused() {
        return refused;
    }

    /** the cells of the path being walked, or none */
    public long[] path() {
        PathSearch.Result w = walking;
        return w == null ? new long[0] : w.cells;
    }

    void tick(WalkMap map, long tick) {
        // jumping where it stood (AiMoveControl): taken this tick whatever follows, so none is left for another goal
        boolean stuck = CONTROL.stuck(mob);
        if (goal == Long.MIN_VALUE) return;
        if (hold) {
            closeCell = Long.MIN_VALUE;
            if (walking != null || !CONTROL.done(mob)) {
                walking = null;
                CONTROL.stop(mob);
            }
            return;
        }
        if (arrived()) {
            closeCell = Long.MIN_VALUE;
            CONTROL.stop(mob);
            walking = null;
            return;
        }
        if (inGoalCell()) {
            closeCell = Long.MIN_VALUE;
            // the last steps: straight to the cell's middle (or the point off it asked for)
            CONTROL.stop(mob);
            walking = null;
            if (inCellSince == Long.MIN_VALUE) inCellSince = tick;
            if (tick - inCellSince > SETTLE) settled = true;
            CONTROL.want(mob, PosLong.blockX(goal) + 0.5 + offX, mob.y(), PosLong.blockZ(goal) + 0.5 + offZ, modifier());
            return;
        }
        inCellSince = Long.MIN_VALUE;
        if (closeCell != Long.MIN_VALUE) {
            // the last steps to the middle of the cell it stands in: within the radius there. Pushed out of the cell, or
            // kept from its middle SETTLE ticks (another mob in it): a try spent, a search again
            if (mob.blockX() == PosLong.blockX(closeCell) && mob.blockZ() == PosLong.blockZ(closeCell) && tick - closeSince <= SETTLE) {
                CONTROL.want(mob, PosLong.blockX(closeCell) + 0.5, mob.y(), PosLong.blockZ(closeCell) + 0.5, modifier());
                return;
            }
            if (tick - closeSince > SETTLE) tries++;
            closeCell = Long.MIN_VALUE;
        }
        Answer a = answer;
        if (a != null) answer = null;
        if (a != null && a.gen == gen && searching) {
            PathSearch.Result r = a.result;
            searching = false;
            searches++;
            searchNanos += r.nanos;
            noWay = r.exhausted && !r.reached && sure(map, r);
            refused = r.refusedSwim;
            noWayAt = map.changes();
            wantMissing(map, r);
            if (r.found() && r.cells.length > 1) walk(r);
            else if (r.reached && r.cells.length == 1 && offX == 0 && offZ == 0 && mob.blockX() == PosLong.blockX(r.cells[0])
                    && mob.blockZ() == PosLong.blockZ(r.cells[0])) {
                // at the goal by the cells, not yet where it stands (its cell's middle within the radius, it at the cell's
                // edge): no path to walk, a step to that middle. Counted a try, it was searched again and again, never there
                // (a cell beside its own, the map has none where it stands: a try, as before)
                closeCell = r.cells[0];
                closeSince = tick;
                CONTROL.stop(mob);
                return;
            } else if (!r.reached && r.missing.length > 0 && waits++ < MAP_WAITS) {
                // the map not built yet where the way may go (down a long shaft section after section): no try spent
            } else {
                // nowhere to go from here, and every cell in reach was looked at: no other search will find a way
                tries = noWay ? TRIES : tries + 1;
                failures++;
            }
        }
        // a search that never answered (a failed job): ask again
        if (searching && tick - searchStarted > 100) searching = false;
        // jumping where it stood (a step it could not get up, AiMoveControl): another way, from where it is now
        if (stuck && !searching) {
            stuckJumps++;
            tries++;
            CONTROL.stop(mob);
            walking = null;
        }
        if (searching || failed()) return;
        boolean done = CONTROL.done(mob), walked = done;
        if (walking != null && !done && (waitingSince != Long.MIN_VALUE || map.changes() != seenChanges || tick - lastCheck >= CHECK_EVERY)) {
            lastCheck = tick;
            seenChanges = map.changes();
            // the sections it walks through kept while it walks them: dropped unwanted under it (a long way down a drift
            // wanted once, at its search), the path went stale and a search on the map without them turned it back
            map.keepWalked(walking.keys);
            if (!fresh(map, walking, walkStamps) && !keep(map, walking, tick)) done = true;
        }
        if (walking == null || done) {
            // a whole path walked to its end short of the goal: stuck or pushed off; a part of one: the next part
            // (and the part that was all there is: as near as it gets, no other search will do better)
            // (a path dropped for the blocks changed on it is neither: the map is another now)
            if (walking != null && walked) {
                // (on a ladder: the game's walk, falling, takes every point under it at once and is done half way
                // down: no try spent, a search from here goes on)
                if (walking.reached && !mob.climbing()) tries++;
                else if (noWay && map.changes() == noWayAt && atEnd(walking)) tries = TRIES;
                // a part of a way (the search cut short at its cells' bound): the next part, while each brings it nearer;
                // one that does not (the goal behind a wall, on a roof: the nearest cell to it the same one at its foot
                // again and again) is a try spent. Not where the map was not built yet on the way (it is being built)
                else if (partTries && walking.missing.length == 0) {
                    double d = Math.hypot(PosLong.blockX(goal) + 0.5 - mob.x(), PosLong.blockZ(goal) + 0.5 - mob.z());
                    if (d > partBest - CLOSER) tries++;
                    partBest = Math.min(partBest, d);
                }
            }
            if (failed()) {
                walking = null;
                return;
            }
            search(map, tick);
        }
    }

    private void search(WalkMap map, long tick) {
        long start = PathSearch.snap(map, mob.x(), mob.y(), mob.z());
        if (start == Long.MIN_VALUE) {
            // standing where the map has no cell yet: have the map build it, try again later. Under water (its head
            // under: no cell there) it floats up to one: no try spent
            map.want(mob.blockX(), mob.blockY(), mob.blockZ(), 8, 4);
            if (!mob.inWater()) tries++;
            return;
        }
        walking = null;
        searching = true;
        searchStarted = tick;
        long g = goal;
        double rad = radius;
        int asked = gen;
        // the cost as things are now (its own side's lines of fire move every tick), here on the server thread
        PathSearch.Cost c = PathSearch.Cost.sum(cost.now(), avoid.now());
        boolean keepDry = dry;
        AiThreads.submit(() -> answer = new Answer(asked, PathSearch.find(map, start, g, rad, MAX_NODES, c, keepDry)));
    }

    private void walk(PathSearch.Result r) {
        // the whole path, a node a cell, its end the last (BodyControl.walk: the version builds the game's path)
        if (CONTROL.walk(mob, r.cells, r.reached, modifier())) {
            walking = r;
            walkStamps = r.stamps.clone();
            waitingSince = Long.MIN_VALUE;
        } else tries++;
    }

    /** an exhausted search's answer is the map's as it is now: none of the sections it read has changed since */
    private static boolean sure(WalkMap map, PathSearch.Result r) {
        for (int i = 0; i < r.readKeys.length; i++) if (map.stamp(r.readKeys[i]) != r.readStamps[i]) return false;
        return true;
    }

    /** at the last cell of a path, not stopped somewhere along it (the game stops a walk that makes no way) */
    private boolean atEnd(PathSearch.Result r) {
        long end = r.cells[r.cells.length - 1];
        double dx = mob.x() - (PosLong.blockX(end) + 0.5), dz = mob.z() - (PosLong.blockZ(end) + 0.5);
        return dx * dx + dz * dz <= 1.5 * 1.5 && Math.abs(mob.y() - PosLong.blockY(end)) <= 1.2;
    }

    private static boolean fresh(WalkMap map, PathSearch.Result r, int[] stamps) {
        for (int i = 0; i < r.keys.length; i++) if (map.stamp(r.keys[i]) != stamps[i]) return false;
        return true;
    }

    /**
     * Blocks changed in sections the walked path crosses: whether it walks on. While those sections are not built
     * again it does, for {@link #REBUILD_WAIT} ticks at most (a search now would run on the same old cells and be
     * stale at once); built again, it does if what is left of the path is still a path on the new cells, and the
     * path is good as of now. Else another is searched.
     */
    private boolean keep(WalkMap map, PathSearch.Result r, long tick) {
        for (int i = 0; i < r.keys.length; i++) {
            if (map.stamp(r.keys[i]) == walkStamps[i]) continue;
            SectionCells c = map.cells(r.keys[i]);
            if (c != null && map.fresh(c)) continue;
            if (waitingSince == Long.MIN_VALUE) waitingSince = tick;
            if (tick - waitingSince < REBUILD_WAIT) return true;
            waitingSince = Long.MIN_VALUE;
            return false;
        }
        waitingSince = Long.MIN_VALUE;
        int next = CONTROL.pathNext(mob);
        if (!PathSearch.holds(map, r.cells, next - 1)) return false;
        for (int i = 0; i < r.keys.length; i++) walkStamps[i] = map.stamp(r.keys[i]);
        kept++;
        return true;
    }

    static void wantMissing(WalkMap map, PathSearch.Result r) {
        int n = 0;
        for (long key : r.missing) {
            if (n++ >= 8) break;
            map.want(PosLong.sectionToBlock(PosLong.sectionX(key), 8), PosLong.sectionToBlock(PosLong.sectionY(key), 8),
                    PosLong.sectionToBlock(PosLong.sectionZ(key), 8), 8, 8);
        }
    }
}
