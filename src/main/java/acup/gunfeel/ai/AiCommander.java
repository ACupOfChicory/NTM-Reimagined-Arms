package acup.gunfeel.ai;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ReaimTimes;
import acup.gunfeel.Vec3d;
import acup.gunfeel.ai.AiIntelBase.Group;
import net.minecraft.server.level.ServerLevel;

/**
 * A side's commander (stage 5, R5; the owner, 2026-11-14: "they will attack other teams and players, just a rough
 * whereabouts"). No mob: the side's mind. Every EVERY_MIN to EVERY_MAX ticks, for each team of AI soldiers, it takes
 * the groups of its enemies from its side's picture (AiIntel, stage 7 C0: AI mobs of a team it fights, players it
 * fights; those within 24 blocks of one another one group, a group known by its members from one report to the next;
 * of the mobs, only those that think, near a player) and sends each of its squads with no one to fight to the
 * nearest group within REACH (and twice aiRadius): by its rough whereabouts only, the picture's circle round the
 * group (16 blocks, wider to 32 the longer none of the side has seen any of them, its middle off theirs by up to half
 * the radius). The squad goes there (AiSoldier, when it knows of no one:
 * legs of LEG blocks) and looks through the area with its own eyes, as before; what it sees it fights as ever. PER
 * squads to a group at most (those fighting one counted); a squad with no group left is the reserve and stays where it
 * is, unless the side lost a soldier to a group within HELP ticks (R5b): then HELP_MORE squads more may go there, and
 * the reserve goes. The group a soldier is lost to: that of the one that downed it; else that of whom its squad
 * fought; else the one it was sent to. A squad that looked through an area GIVE_UP ticks in all and found no one, or whose way there failed FAILS legs
 * running with no PROGRESS, gives that group up for AVOID (half of it or more) and is sent to another. Lone soldiers (no squad) and soldiers of no team get
 * no orders. No places set by command, no buildings as targets.
 */
public final class AiCommander extends AiCommanderBase {
    static final class Job extends AiCommanderBase.Job<AiBot, Job> {
        Job(StaffPlan.Kind kind, long since, List<AiBot> bots, int[] ids) {
            super(kind, since, bots, ids);
        }
    }

    static final class Order extends AiCommanderBase.Order<AiBot, Job> {
    }

    private AiCommander() {
    }

    static final Staff<AiBot, Job, Order> STAFF = new Staff<>() {
        @Override public Order order(AiBot b) { return b.order; }
        @Override public void order(AiBot b, @Nullable Order o) { b.order = o; }
        @Override public Order order() { return new Order(); }
        @Override public Job job(StaffPlan.Kind kind, long since, List<AiBot> bots, int[] ids) { return new Job(kind, since, bots, ids); }
        @Override public AiSquadsBase.Squad<AiBot> squad() { return new AiSquads.Squad(); }
        @Override public HqGrid hq(Object level, String team) { return AiHqMap.of((ServerLevel) level, team); }
        @Override public void hqReport(Object level, String team, List<AiBot> own, List<Group> groups) { AiHqMap.report((ServerLevel) level, team, own, groups); }
        @Override public void report(Object level, String team, AiReportsBase.Report r) { AiReports.report((ServerLevel) level, team, r); }
        @Override public long intel(Object level, Map<String, List<AiBot>> own, long now) { return AiIntel.tick((ServerLevel) level, own, now); }
        @Override public void lost(Object level, String team, int id, long tick) { AiIntel.lost((ServerLevel) level, team, id, tick); }
        @Override public double radius() { return AiBots.radius(); }
        @Override public boolean supplyOn() { return AiSupply.on() && ReaimTimes.flag(AiSupply.OUT_KEY, true); }
        @Override public List<AiBot> moveSquad(Job j) { return StaffMove.squad(j); }
        @Override public void moveAfresh(Job j) { StaffMove.afresh(j); }
        @Override public boolean inCrossing(AiBot b) { return AiCrossing.of(b) != null; }
        @Override public boolean terrainOn() { return AiTerrain.ON; }
        @Override public acup.gunfeel.host.WorldView world(Object level) { return acup.gunfeel.host.Worlds.of(level); }
        @Override public acup.gunfeel.host.EntityView entities(Object level) { return new acup.gunfeel.LevelEntities((ServerLevel) level); }
        @Override public acup.gunfeel.water.LiquidPath.Fluids fluids(Object level) { return acup.gunfeel.Wiring.liquids((ServerLevel) level); }
        @Override public TacticalMapBase map(Object level) { return TacticalMap.of((ServerLevel) level); }
        @Override public VoxelRay.Source voxels(Object level) { return AiBots.voxels((ServerLevel) level); }
        @Override public java.util.function.LongPredicate heldByOthers(AiBot b) { return AiBots.heldByOthers(b); }
        @Override public void aim(AiBot b, @Nullable Vec3d at) { b.gunner.aim(at == null ? null : new net.minecraft.world.phys.Vec3(at.x(), at.y(), at.z())); }
        @Override public void crouch(AiBot b, boolean on) { if (b.brain instanceof AiSoldier s) s.crouch(on); }
        @Override public double speed(StaffMoveBase.Pace p) { return switch (p) { case RUN -> AiSoldier.RUN; case MARCH -> AiSoldier.MARCH; case WALK -> AiSoldier.WALK; case CREEP -> AiSoldier.CREEP; }; }
        @Override public void held(AiBot b, long tick) { AiHold.held(b, tick); }
        @Override public boolean movingHeld(AiBot b) { return AiHold.moving(b); }
    };

    public static void forget(ServerLevel level) {
        forgetLevel(level);
        AiReports.forget(level);
        AiIntel.forget(level);
        AiHqMap.forget(level);
    }

    static boolean breaksContact(AiBot bot, long tick) {
        return breakingContact(STAFF, bot, tick);
    }

    static void died(ServerLevel level, AiBot fallen, long tick) { lostBot(STAFF, level, fallen, tick); }

    static void tick(ServerLevel level) {
        tickStaff(STAFF, level);
    }

    @SuppressWarnings("unchecked")
    static List<AiSquads.Squad> far(ServerLevel level) {
        return (List<AiSquads.Squad>) (List<?>) farSquads(STAFF, level);
    }

    static boolean asleep(AiSquads.Squad s) {
        return squadAsleep(STAFF, s);
    }

    static long @Nullable [] detour(ServerLevel level, int x0, int z0, int y0, double tx, double tz) { return groundDetour(STAFF, level, x0, z0, y0, tx, tz); }

    static @Nullable Group going(AiSquads.Squad s, List<Group> groups, long now) {
        return goingTo(STAFF, s, groups, now);
    }

    static @Nullable Group counted(AiSquads.Squad s, List<Group> groups, long now) {
        return countedTo(STAFF, s, groups, now);
    }

    static boolean failed(AiSquads.Squad s, Group g, long now) {
        return squadFailed(STAFF, s, g, now);
    }

    private static HqGrid.@Nullable Route tail(ServerLevel level, Job j, Order o, long tick) { return tailRoute(STAFF, level, j, o, tick); }

    private static void takeUp(Order o, AiBot b) { takeWay(STAFF, o, b); }

    static List<Group> groups(ServerLevel level, String team) {
        return enemyGroups(STAFF, level, team);
    }

    static boolean has(AiBot bot, long tick) {
        return hasOrder(STAFF, bot, tick);
    }

    static Order fresh(long now) { return freshOrder(STAFF, now); }

    static @Nullable Order crossing(AiBot bot, long tick) { return crossingOrder(STAFF, bot, tick); }

    static int passedBy(AiSquads.Squad s, AiBot b, long now) {
        return passedSquad(STAFF, s, b, now);
    }

    static boolean red(AiBot b) {
        return atRed(STAFF, b);
    }

    public static void reported(ServerLevel level, String team) { reportAsked(STAFF, level, team); }

    static boolean step(AiBot bot, ServerLevel level, long tick) { return stepOrder(STAFF, bot, level, tick); }

    private static boolean giveUp(AiBot bot, Order o, long tick) { return abandonOrder(STAFF, bot, o, tick); }

    private static void leg(AiBot bot, ServerLevel level, Order o, StaffMove.Leg leg, boolean stuck, boolean on, long tick) { walkLeg(STAFF, bot, level, o, leg, stuck, on, tick); }

    private static boolean go(AiBot bot, ServerLevel level, double x, double z, double speed) { return goGround(STAFF, bot, level, x, z, speed); }

    private static boolean mateIn(AiBot bot, Order o, long tick) { return mateInside(STAFF, bot, o, tick); }

    static long stand(ServerLevel level, int bx, int bz, int ownY) { return standingCell(STAFF, level, bx, bz, ownY); }

    private static void leg(Order o, AiBot bot, double x, double z) { walkLeg(STAFF, o, bot, x, z); }

    private static void room(Order o, AiBot bot, ServerLevel level) { findRoom(STAFF, o, bot, level); }

    private static boolean inRoom(Order o, double x, double z) { return insideRoom(STAFF, o, x, z); }

    private static boolean bad(Order o, double x, double z) { return badPlace(STAFF, o, x, z); }

    private static void ready(ServerLevel level, Job f, long now) { signalReady(STAFF, level, f, now); }

    static boolean presses(AiBot bot, long tick) {
        return pressesFor(STAFF, bot, tick);
    }

    static boolean leashed(AiBot bot, int target, long tick) {
        return leashedAt(STAFF, bot, target, tick);
    }

    static boolean goesRound(AiBot bot, TargetMemory.Contact c, long tick) {
        return goesAround(STAFF, bot, c, tick);
    }

    static boolean mapped() {
        return mapReady(STAFF);
    }

    public static String info() {
        StringBuilder t = new StringBuilder();
        for (StaffPlan.Kind k : StaffPlan.Kind.values()) t.append(t.length() == 0 ? "" : ", ").append(k.name().toLowerCase(java.util.Locale.ROOT)).append(' ').append(given[k.ordinal()]);
        return String.format(java.util.Locale.ROOT, "commanders: %d orders, %d times a squad held in reserve, %d sent where the side loses soldiers, %d groups given up, %d ways (%.2f ms each, off the server thread); tasks given: %s, %d to catch up with a squad, %d steps moved asleep; %s; %s", orders,
                reserve, helped, gaveUp, routes.get(), routes.get() == 0 ? 0 : routeNanos.get() / 1e6 / routes.get(), t, strays, drifted, AiIntel.info(), AiHqMap.info());
    }

}
