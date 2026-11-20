package acup.gunfeel.ai;

import java.util.List;

import org.jspecify.annotations.Nullable;
import net.minecraft.server.level.ServerLevel;

/** The version's access to the squad's movement in core. */
final class StaffMove extends StaffMoveBase {
    private StaffMove() {
    }

    static boolean on() { return movesOn(); }

    static Mode mode(double foe, boolean seen, boolean advance, double left) { return movementMode(foe, seen, advance, left); }

    static int[] halves(List<AiRole> roles) { return roleHalves(roles); }

    static int rushing(int n) { return rushCount(n); }

    static double along(HqGrid.Route r, double x, double z) { return distanceAlong(r, x, z); }

    static double[] at(HqGrid.Route r, double s) { return pointAt(r, s); }

    static List<AiBot> squad(AiCommander.Job j) { return taskSquad(AiCommander.STAFF, j); }

    static void update(ServerLevel level, AiCommander.Order o, long tick) { refreshMode(AiCommander.STAFF, level, o, tick); }

    static void afresh(AiCommander.Job j) { restartMode(AiCommander.STAFF, j); }

    static double speed(AiCommander.Order o) { return orderSpeed(AiCommander.STAFF, o); }

    static double march(StaffPlan.Kind kind) { return marchSpeed(AiCommander.STAFF, kind); }

    static void face(AiBot bot, AiCommander.Order o) { faceEnemy(AiCommander.STAFF, bot, o); }

    static void storm(AiBot bot, AiCommander.Order o, long tick) { stormOn(AiCommander.STAFF, bot, o, tick); }

    static void pace(AiBot bot, AiCommander.Order o, long tick) { applyPace(AiCommander.STAFF, bot, o, tick); }

    static @Nullable Leg leg(AiBot bot, ServerLevel level, AiCommander.Order o, HqGrid.Route way, long tick, boolean stuck) { return nextLeg(AiCommander.STAFF, bot, level, o, way, tick, stuck); }

    private static int[] halves(List<AiBot> of, State st) { return stableHalves(AiCommander.STAFF, of, st); }

    private static boolean steps(AiBot b) { return takesSteps(AiCommander.STAFF, b); }

    private static boolean inPlace(AiBot b, AiCommander.Order o) { return atPlace(AiCommander.STAFF, b, o); }

    static boolean atCover(AiBot b, AiCommander.Order o) { return reachedCover(AiCommander.STAFF, b, o); }

    static boolean quiet(AiCommander.Job j) { return taskQuiet(AiCommander.STAFF, j); }

    static int covered(AiCommander.Job j) { return covering(AiCommander.STAFF, j); }

    /**
     * the stands: why the half covering is not at its place while the squad bounds out of a fight, one line a soldier
     * of it: the squad's task, why not (no order, no place given this bound, how far off it, moving there), and its
     * own mode and why
     */
    static void offPlace(AiCommander.Job j, java.util.function.Consumer<String> out) {
        State st = j.move;
        List<AiBot> of = squad(j);
        int[] half = halves(of, st);
        for (int i = 0; i < of.size(); i++) {
            if (half[i] == st.moving) continue;
            AiBot b = of.get(i);
            AiCommander.Order o = b.order;
            String why;
            if (o == null) why = "no order";
            else if (o.coverSince != st.since) {
                double d = Math.hypot(o.x - b.mob.getX(), o.z - b.mob.getZ());
                boolean in = j.place() ? d <= o.r * 2 : d <= o.r;
                why = "no place this bound, " + (in ? "in its task's area" : o.route == null ? "past the way's end" + (o.way != null ? " (a way)" : "")
                        + (o.walked == null ? " (none walked)" : "") + (o.noRoute ? " (no route)" : "") + (o.noTail ? " (no tail)" : "") + (j.tail == null ? " (tail none)" : "") : "on the way");
            }
            else if (!atCover(b, o)) {
                double d = Math.hypot(o.coverX - b.mob.getX(), o.coverZ - b.mob.getZ());
                why = d <= 16 ? "off its place 16 at most" : d <= 32 ? "off its place 16 to 32" : "off its place over 32";
            } else if (AiHold.moving(b)) why = "at its place, moving";
            else why = "at its place";
            String mode = b.brain instanceof AiSoldier s ? s.mode() + "/" + s.why : "-";
            out.accept(j.kind + " | " + why + " | " + mode);
        }
    }

    /**
     * the stands' C3 trace (-PaiCheckWarDump): a bound missed, one line a soldier of the squad: its half, awake, going by
     * the bounds (steps), its place for this mode and how far off, its speed, its last step by the mode (how long ago, a
     * hold), its way (on it, its tail, straight legs), its walk, its mode, the nearest of the half going
     */
    static void trace(AiCommander.Job j, long now, java.util.function.ToIntFunction<AiBot> id, java.util.function.Consumer<String> out) {
        State st = j.move;
        List<AiBot> of = squad(j);
        int[] half = halves(of, st);
        for (int i = 0; i < of.size(); i++) {
            AiBot b = of.get(i);
            AiCommander.Order o = b.order;
            double near = Double.MAX_VALUE;
            for (int k = 0; k < of.size(); k++)
                if (k != i && half[k] == st.moving) near = Math.min(near, of.get(k).mob.position().distanceTo(b.mob.position()));
            String way = o == null ? "-" : o.route == null ? (o.noRoute ? "straight(noRoute)" : o.noTail ? "straight(noTail)" : o.walked != null ? "straight(walked)" : "straight")
                    : (o.onTail ? "tail " : "way ") + o.at + "/" + o.route.size();
            out.accept(String.format(java.util.Locale.ROOT, "%d %s %d %s set%d turnAge%d id%d act%d steps%d since%d cell%d covered%d d%.2f in%d at%d v%.3f moveAgo%s hold%d %s | %s | %s near%.1f",
                    System.identityHashCode(j), j.kind, st.turn, half[i] == st.moving ? "G" : "c", st.set ? 1 : 0, now - st.turnAt, id.applyAsInt(b), b.active ? 1 : 0,
                    steps(b) ? 1 : 0, o != null && o.coverSince == st.since ? 1 : 0, o != null && o.coverCell != Long.MIN_VALUE ? 1 : 0, o != null && o.covered ? 1 : 0,
                    o == null ? -1 : Math.hypot(o.coverX - b.mob.getX(), o.coverZ - b.mob.getZ()), o != null && inPlace(b, o) ? 1 : 0, o != null && atCover(b, o) ? 1 : 0,
                    b.mob.getDeltaMovement().horizontalDistance(), o == null || o.moveAt == Long.MIN_VALUE ? "-" : Long.toString(now - o.moveAt), o != null && o.moveHold ? 1 : 0,
                    way, b.mover.state(), b.brain instanceof AiSoldier s ? s.mode() + "/" + s.why : "-", near == Double.MAX_VALUE ? -1 : near));
        }
    }

}
