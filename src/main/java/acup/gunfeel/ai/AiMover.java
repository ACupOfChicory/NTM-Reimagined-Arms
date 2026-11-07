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
    private double speed = 1.0;
    private int tries;
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
    private long searchStarted;
    private PathSearch.@Nullable Result walking;
    private long lastCheck;
    private PathSearch.Cost cost = PathSearch.Cost.NONE;
    // for /armsai info and the stands
    public int searches, failures;
    public long searchNanos;

    AiMover(Mob mob) {
        this.mob = mob;
    }

    /** go to within radius blocks of a cell (a BlockPos long); the same goal again changes nothing */
    public void goTo(long cell, double radius, double speed) {
        this.speed = speed;
        if (cell == goal && radius == this.radius) return;
        goal = cell;
        this.radius = radius;
        tries = 0;
        inCellSince = Long.MIN_VALUE;
        settled = false;
        walking = null;
        searching = false;
        gen++;
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

    /**
     * At the goal: within its radius across (a quarter block at least) and a block up or down. Vanilla's walk ends up to
     * 0.45 off a cell's middle, so the last steps go straight there ({@link #tick}): a peek a block aside from cover is
     * out of it only at the middle. Kept from the middle that long ({@link #SETTLE}), in the cell counts.
     */
    public boolean arrived() {
        if (goal == Long.MIN_VALUE || Math.abs(mob.getY() - BlockPos.getY(goal)) > 1.2) return false;
        double dx = mob.getX() - (BlockPos.getX(goal) + 0.5), dz = mob.getZ() - (BlockPos.getZ(goal) + 0.5);
        double r = Math.max(radius, 0.25);
        return dx * dx + dz * dz <= r * r || settled && inGoalCell();
    }

    private boolean inGoalCell() {
        return mob.getBlockX() == BlockPos.getX(goal) && mob.getBlockZ() == BlockPos.getZ(goal) && Math.abs(mob.getY() - BlockPos.getY(goal)) <= 1.2;
    }

    /** the goal could not be reached after a few tries */
    public boolean failed() {
        return goal != Long.MIN_VALUE && tries >= TRIES;
    }

    /** the cells of the path being walked, or none */
    /** for the stands' traces */
    public String state() {
        PathSearch.Result w = walking;
        return (goal == Long.MIN_VALUE ? "-" : BlockPos.of(goal).toShortString()) + (searching ? " searching" : "") + " walk "
                + (w == null ? "-" : w.cells.length + (w.reached ? "" : "?")) + (mob.getNavigation().isDone() ? " done" : "") + " tries " + tries
                + " s" + searches;
    }

    public long[] path() {
        PathSearch.Result w = walking;
        return w == null ? new long[0] : w.cells;
    }

    void tick(ServerLevel level, TacticalMap map, long tick) {
        if (goal == Long.MIN_VALUE) return;
        if (arrived()) {
            mob.getNavigation().stop();
            walking = null;
            return;
        }
        if (inGoalCell()) {
            // the last steps: straight to the cell's middle
            mob.getNavigation().stop();
            walking = null;
            if (inCellSince == Long.MIN_VALUE) inCellSince = tick;
            if (tick - inCellSince > SETTLE) settled = true;
            mob.getMoveControl().setWantedPosition(BlockPos.getX(goal) + 0.5, mob.getY(), BlockPos.getZ(goal) + 0.5, speed);
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
            wantMissing(map, r);
            if (r.found() && r.cells.length > 1) walk(r);
            else {
                tries++;
                failures++;
            }
        }
        // a search that never answered (a failed job): ask again
        if (searching && tick - searchStarted > 100) searching = false;
        if (searching || failed()) return;
        boolean done = mob.getNavigation().isDone();
        if (walking != null && !done && tick - lastCheck >= CHECK_EVERY) {
            lastCheck = tick;
            if (!fresh(map, walking)) done = true;
        }
        if (walking == null || done) {
            // a whole path walked to its end short of the goal: stuck or pushed off; a part of one: the next part
            if (walking != null && walking.reached && done) tries++;
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
        PathSearch.Cost c = cost;
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
        if (mob.getNavigation().moveTo(p, speed)) walking = r;
        else tries++;
    }

    private static boolean fresh(TacticalMap map, PathSearch.Result r) {
        for (int i = 0; i < r.keys.length; i++) if (map.stamp(r.keys[i]) != r.stamps[i]) return false;
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
