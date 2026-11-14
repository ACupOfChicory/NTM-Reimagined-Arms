package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * An AI mob's legs: it asks for a path to a cell (searched off the server thread by {@link PathSearch} over the
 * tactical map), hands the path to the mob's own navigation to walk (so the game's movement, jumping and swimming
 * apply), and asks again when the path ends short of the goal, the mob is stuck, or blocks along the path changed.
 * Server thread, from the mob's goal.
 */
public final class AiMover {
    /** nodes a search may expand */
    static final int MAX_NODES = 3000;
    /** how often the path's sections are checked against the map, ticks */
    static final int CHECK_EVERY = 10;
    /** tries at a goal before it gives up on it */
    static final int TRIES = 4;

    private final Mob mob;
    private long goal = Long.MIN_VALUE;
    private double radius;
    /** where in the goal's cell it stands, from its middle, blocks (a step toward a corner to lean round it) */
    private double offX, offZ;
    private double speed = 1.0;
    /** a share of the speed it walks at (lying: a third) */
    private double slow = 1.0;
    private int tries;
    /** keep still this tick (lying, to fire), the goal kept */
    private boolean hold;
    /** in the goal's cell since (the last steps, straight to its middle), and there long enough to count as arrived */
    private long inCellSince = Long.MIN_VALUE;
    private boolean settled;
    /** how long the last steps may take before the mob counts as there all the same (another mob in the way) */
    static final int SETTLE = 40;
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
    // for the stands
    public int searches, failures, stuckJumps, kept;
    public long searchNanos;

    AiMover(Mob mob) {
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
            if (other && walking != null) mob.getNavigation().setSpeedModifier(modifier());
            return;
        }
        goal = cell;
        this.radius = radius;
        offX = ox;
        offZ = oz;
        tries = 0;
        noWay = false;
        inCellSince = Long.MIN_VALUE;
        settled = false;
        walking = null;
        searching = false;
        gen++;
    }

    /** walks at this share of its speed from now (lying down, getting up) */
    void slow(double share) {
        if (share == slow) return;
        slow = share;
        if (walking != null) mob.getNavigation().setSpeedModifier(modifier());
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

    public void stop() {
        goal = Long.MIN_VALUE;
        inCellSince = Long.MIN_VALUE;
        settled = false;
        walking = null;
        searching = false;
        gen++;
        mob.getNavigation().stop();
    }

    /** stop walking but keep the goal: the walk starts over when it moves again */
    public void pause() {
        walking = null;
        searching = false;
        gen++;
        mob.getNavigation().stop();
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
        if (goal == Long.MIN_VALUE || Math.abs(mob.getY() - BlockPos.getY(goal)) > 1.2) return false;
        double dx = mob.getX() - (BlockPos.getX(goal) + 0.5 + offX), dz = mob.getZ() - (BlockPos.getZ(goal) + 0.5 + offZ);
        double r = Math.max(radius, 0.1);
        return dx * dx + dz * dz <= r * r || settled && inGoalCell();
    }

    private boolean inGoalCell() {
        return mob.getBlockX() == BlockPos.getX(goal) && mob.getBlockZ() == BlockPos.getZ(goal) && Math.abs(mob.getY() - BlockPos.getY(goal)) <= 1.2;
    }

    /**
     * It runs to its goal now, faster than a walk (running low too; crawling is slower): it faces where it goes (a player sprints only ahead), its head
     * turning within its neck, and holds fire. Walking it may face anywhere ({@link AiMoveControl}).
     */
    public boolean runs() {
        return goal != Long.MIN_VALUE && modifier() > AiSoldier.WALK + 1e-6 && !arrived() && !failed();
    }

    /** its mind sends it to a goal faster than a walk, whatever its legs make of it now (lying, it crawls) */
    public boolean wantsRun() {
        return goal != Long.MIN_VALUE && speed > AiSoldier.WALK + 1e-6 && !arrived() && !failed();
    }

    /** the goal could not be reached after a few tries */
    public boolean failed() {
        return goal != Long.MIN_VALUE && tries >= TRIES;
    }

    /** for the stands' traces */
    public String state() {
        PathSearch.Result w = walking;
        return (goal == Long.MIN_VALUE ? "-" : BlockPos.of(goal).toShortString()) + (searching ? " searching" : "") + " walk "
                + (w == null ? "-" : w.cells.length + (w.reached ? "" : "?")) + (mob.getNavigation().isDone() ? " done" : "") + " tries " + tries
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
    public boolean stale(TacticalMap map) {
        PathSearch.Result w = walking;
        return w != null && !fresh(map, w, walkStamps);
    }

    /** the cells of the path being walked, or none */
    public long[] path() {
        PathSearch.Result w = walking;
        return w == null ? new long[0] : w.cells;
    }

    void tick(ServerLevel level, TacticalMap map, long tick) {
        // jumping where it stood (AiMoveControl): taken this tick whatever follows, so none is left for another goal
        boolean stuck = mob.getMoveControl() instanceof AiMoveControl legs && legs.stuck();
        if (goal == Long.MIN_VALUE) return;
        if (hold) {
            if (walking != null || !mob.getNavigation().isDone()) {
                walking = null;
                mob.getNavigation().stop();
            }
            return;
        }
        if (arrived()) {
            mob.getNavigation().stop();
            walking = null;
            return;
        }
        if (inGoalCell()) {
            // the last steps: straight to the cell's middle (or the point off it asked for)
            mob.getNavigation().stop();
            walking = null;
            if (inCellSince == Long.MIN_VALUE) inCellSince = tick;
            if (tick - inCellSince > SETTLE) settled = true;
            mob.getMoveControl().setWantedPosition(BlockPos.getX(goal) + 0.5 + offX, mob.getY(), BlockPos.getZ(goal) + 0.5 + offZ, modifier());
            return;
        }
        inCellSince = Long.MIN_VALUE;
        Answer a = answer;
        if (a != null) answer = null;
        if (a != null && a.gen == gen && searching) {
            PathSearch.Result r = a.result;
            searching = false;
            searches++;
            searchNanos += r.nanos;
            noWay = r.exhausted && !r.reached && sure(map, r);
            noWayAt = map.changes;
            wantMissing(map, r);
            if (r.found() && r.cells.length > 1) walk(r);
            else {
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
            mob.getNavigation().stop();
            walking = null;
        }
        if (searching || failed()) return;
        boolean done = mob.getNavigation().isDone(), walked = done;
        if (walking != null && !done && (waitingSince != Long.MIN_VALUE || map.changes != seenChanges || tick - lastCheck >= CHECK_EVERY)) {
            lastCheck = tick;
            seenChanges = map.changes;
            if (!fresh(map, walking, walkStamps) && !keep(map, walking, tick)) done = true;
        }
        if (walking == null || done) {
            // a whole path walked to its end short of the goal: stuck or pushed off; a part of one: the next part
            // (and the part that was all there is: as near as it gets, no other search will do better)
            // (a path dropped for the blocks changed on it is neither: the map is another now)
            if (walking != null && walked) {
                if (walking.reached) tries++;
                else if (noWay && map.changes == noWayAt && atEnd(walking)) tries = TRIES;
            }
            if (failed()) {
                walking = null;
                return;
            }
            search(map, tick);
        }
    }

    private void search(TacticalMap map, long tick) {
        long start = PathSearch.snap(map, mob.getX(), mob.getY(), mob.getZ());
        if (start == Long.MIN_VALUE) {
            // standing where the map has no cell yet: have the map build it, try again later
            map.want(mob.getBlockX(), mob.getBlockY(), mob.getBlockZ(), 8, 4);
            tries++;
            return;
        }
        walking = null;
        searching = true;
        searchStarted = tick;
        long g = goal;
        double rad = radius;
        int asked = gen;
        // the cost as things are now (its own side's lines of fire move every tick), here on the server thread
        PathSearch.Cost c = cost.now();
        AiThreads.submit(() -> answer = new Answer(asked, PathSearch.find(map, start, g, rad, MAX_NODES, c)));
    }

    private void walk(PathSearch.Result r) {
        List<Node> nodes = new ArrayList<>(r.cells.length);
        for (long c : r.cells) {
            Node n = new Node(BlockPos.getX(c), BlockPos.getY(c), BlockPos.getZ(c));
            n.type = PathType.WALKABLE;
            nodes.add(n);
        }
        long end = r.cells[r.cells.length - 1];
        Path p = new Path(nodes, BlockPos.of(end), r.reached);
        if (mob.getNavigation().moveTo(p, modifier())) {
            walking = r;
            walkStamps = r.stamps.clone();
            waitingSince = Long.MIN_VALUE;
        } else tries++;
    }

    /** an exhausted search's answer is the map's as it is now: none of the sections it read has changed since */
    private static boolean sure(TacticalMap map, PathSearch.Result r) {
        for (int i = 0; i < r.readKeys.length; i++) if (map.stamp(r.readKeys[i]) != r.readStamps[i]) return false;
        return true;
    }

    /** at the last cell of a path, not stopped somewhere along it (the game stops a walk that makes no way) */
    private boolean atEnd(PathSearch.Result r) {
        long end = r.cells[r.cells.length - 1];
        double dx = mob.getX() - (BlockPos.getX(end) + 0.5), dz = mob.getZ() - (BlockPos.getZ(end) + 0.5);
        return dx * dx + dz * dz <= 1.5 * 1.5 && Math.abs(mob.getY() - BlockPos.getY(end)) <= 1.2;
    }

    private static boolean fresh(TacticalMap map, PathSearch.Result r, int[] stamps) {
        for (int i = 0; i < r.keys.length; i++) if (map.stamp(r.keys[i]) != stamps[i]) return false;
        return true;
    }

    /**
     * Blocks changed in sections the walked path crosses: whether it walks on. While those sections are not built
     * again it does, for {@link #REBUILD_WAIT} ticks at most (a search now would run on the same old cells and be
     * stale at once); built again, it does if what is left of the path is still a path on the new cells, and the
     * path is good as of now. Else another is searched.
     */
    private boolean keep(TacticalMap map, PathSearch.Result r, long tick) {
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
        Path p = mob.getNavigation().getPath();
        int next = p == null ? 0 : p.getNextNodeIndex();
        if (!PathSearch.holds(map, r.cells, next - 1)) return false;
        for (int i = 0; i < r.keys.length; i++) walkStamps[i] = map.stamp(r.keys[i]);
        kept++;
        return true;
    }

    private static void wantMissing(TacticalMap map, PathSearch.Result r) {
        int n = 0;
        for (long key : r.missing) {
            if (n++ >= 8) break;
            map.want(SectionPos.sectionToBlockCoord(SectionPos.x(key), 8), SectionPos.sectionToBlockCoord(SectionPos.y(key), 8),
                    SectionPos.sectionToBlockCoord(SectionPos.z(key), 8), 8, 8);
        }
    }
}
