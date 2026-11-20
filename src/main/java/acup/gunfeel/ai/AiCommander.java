package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ReaimTimes;
import acup.gunfeel.ai.AiIntelBase.Group;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;

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

    private static final Staff<AiBot, Job, Order> STAFF = new Staff<>() {
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

    static long @Nullable [] detour(ServerLevel level, int x0, int z0, int y0, double tx, double tz) {
        return StaffGround.detour(acup.gunfeel.Wiring.world(level), acup.gunfeel.water.LevelFluids.of(level), x0, z0, y0, tx, tz);
    }

    static @Nullable Group going(AiSquads.Squad s, List<Group> groups, long now) {
        return goingTo(STAFF, s, groups, now);
    }

    static @Nullable Group counted(AiSquads.Squad s, List<Group> groups, long now) {
        return countedTo(STAFF, s, groups, now);
    }

    static boolean failed(AiSquads.Squad s, Group g, long now) {
        return squadFailed(STAFF, s, g, now);
    }

    private static HqGrid.@Nullable Route tail(ServerLevel level, Job j, Order o, long tick) {
        if (j.tail != null && Math.hypot(j.tail.toX() - o.x, j.tail.toZ() - o.z) <= REROUTE) return j.tail;
        j.tail = null;
        if (j.tailNot != Long.MIN_VALUE && tick - j.tailNot < TAIL_AGAIN) return null;
        List<AiBot> of = StaffMove.squad(j);
        if (of.isEmpty()) return null;
        double mx = 0, mz = 0;
        for (AiBot b : of) {
            mx += b.mob.getX() / of.size();
            mz += b.mob.getZ() / of.size();
        }
        HqGrid grid = AiHqMap.of(level, AiSquads.team(of.get(0)));
        double len = Math.hypot(o.x - mx, o.z - mz);
        if (grid == null || len < 1 || !grid.dryStraight(mx, mz, o.x, o.z)) {
            tailsNot++;
            j.tailNot = tick;
            return null;
        }
        int n = Math.max(1, (int) Math.ceil(len / HqGrid.LEG));
        double[] points = new double[2 * n];
        for (int k = 1; k <= n; k++) {
            points[2 * k - 2] = mx + (o.x - mx) * k / n;
            points[2 * k - 1] = mz + (o.z - mz) * k / n;
        }
        j.tail = new HqGrid.Route(mx, mz, o.x, o.z, points, len, len, 0, 0);
        tails++;
        return j.tail;
    }

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

    static boolean step(AiBot bot, ServerLevel level, long tick) {
        Order o = bot.order;
        if (!has(bot, tick)) return false;
        double dx = o.x - bot.mob.getX(), dz = o.z - bot.mob.getZ(), d = Math.hypot(dx, dz);
        // in an area once it is within half the radius, out again past the whole of it; at a place (C2) within its
        // radius, away again past twice that
        boolean place = o.job != null && o.job.place();
        // (on its way to a place in the area it looks through: still there, though the way goes out past the radius)
        boolean there = place ? d <= o.r || o.lastInside == tick - 1 && d <= o.r * 2
                : d <= o.r * 0.5 || o.lastInside == tick - 1 && (d <= o.r || tick < o.lookUntil);
        bot.gunner.fire(false);
        if (!there) {
            o.stood = false;
            boolean moves = o.job != null && StaffMove.on();
            if (moves) StaffMove.update(level, o, tick);
            boolean stuck = bot.mover.failed();
            // a leg of its squad's bounds or rushes that could not be walked (a cover cell out of reach): for those to
            // see to (StaffMove), not counted against the order or the way
            boolean coverStuck = stuck && o.moveLeg;
            if (coverStuck) {
                stuck = false;
                o.moveLeg = false;
            }
            // a leg's time out, it not there and no PROGRESS nearer the area than when the leg began (round and round
            // a house with no way in, its point on the roof: the leg's cell a block off each time, no try ever spent):
            // as a leg that could not be walked
            // (a leg of 2 PROGRESS or more: a short one's end is as near as its goal's radius and the place snapped to)
            if (!stuck && unstuck && !o.moveLeg && tick >= o.nextLeg && o.legD >= 2 * PROGRESS && o.legD != Double.MAX_VALUE && !bot.mover.arrived()
                    && Math.hypot(o.legTX - bot.mob.getX(), o.legTZ - bot.mob.getZ()) > o.legD - PROGRESS) stuck = true;
            // a leg that could not be walked (the map not built that far yet, or no way): counted only when it is no
            // nearer than at the last one
            if (stuck) {
                if (o.failedAt - d >= PROGRESS) o.fails = 0;
                o.failedAt = d;
                // (it or one of its squad sent there was in the area a moment ago: the place is to be got to, its own
                // in the way at a narrow doorway; the leg is turned aside as one not walked, the place kept)
                if (mateIn(bot, o, tick)) o.fails = Math.min(o.fails, FAILS - 2);
                if (++o.fails >= FAILS) return giveUp(bot, o, tick);
            }
            // the staff's way: to its points one after another, each a short walk (C1); a leg that fails is walked
            // round as R5's are, and the way taken up again from where it is then
            if (o.route == null && !o.noRoute) takeUp(o, bot);
            // the staff's way given up (its legs could not be walked) or walked to its end short of the area (it did not
            // reach that far, or the area moved on): the squad's way of going (its bounds) kept on to the area, along a
            // straight stretch with no water to swim and no cliff on the staff's map; a river is crossed only by the
            // staff's order. Else straight legs, as before
            if (o.route == null && moves && !o.noTail && (o.noRoute || o.way == null && o.walked != null) && o.job.move.mode != null && staff()) {
                HqGrid.Route t = tail(level, o.job, o, tick);
                if (t != null) {
                    o.route = t;
                    o.at = ahead(t, bot.mob.getX(), bot.mob.getZ());
                    o.onTail = true;
                }
            }
            HqGrid.Route way = o.route;
            if (way != null && staff()) {
                int was = o.at;
                while (o.at < way.size() - 1 && Math.hypot(way.x(o.at) - bot.mob.getX(), way.z(o.at) - bot.mob.getZ()) <= REACHED) o.at++;
                // a point that could not be walked to: the next one; again and again, the way is left for straight legs
                if (o.onTail && stuck && ++o.tailFails >= ROUTE_FAILS) {
                    // its own stretch could not be walked: straight legs for the rest of it, the staff's ways untouched
                    o.route = null;
                    o.onTail = false;
                    o.noTail = true;
                } else if (!o.onTail && stuck && ++o.routeFails >= ROUTE_FAILS) {
                    o.route = null;
                    o.noRoute = true;
                    // the straight legs counted from here
                    o.fails = 0;
                    o.failedAt = Double.MAX_VALUE;
                } else {
                    if (stuck) o.at = Math.min(o.at + 1, way.size() - 1);
                    if (bot.mover.arrived()) o.routeFails = o.tailFails = 0;
                    // the end of the way (short of the area when that was off the staff's map, or the area moved on
                    // less than REROUTE): on from there in straight legs
                    if (o.at == way.size() - 1 && Math.hypot(way.x(o.at) - bot.mob.getX(), way.z(o.at) - bot.mob.getZ()) <= REACHED) {
                        o.route = null;
                        if (o.onTail) {
                            o.onTail = false;
                            o.noTail = true;
                        } else {
                            o.walked = o.way;
                            o.way = null;
                        }
                    } else {
                        StaffMove.Leg leg = moves ? StaffMove.leg(bot, level, o, way, tick, coverStuck) : null;
                        if (leg != null) {
                            o.moveAt = tick;
                            o.moveHold = leg.hold();
                            leg(bot, level, o, leg, stuck, o.at != was, tick);
                            return true;
                        }
                        if (tick >= o.nextLeg || bot.mover.arrived() || stuck || o.at != was) {
                            if (bot.mover.arrived()) o.fails = 0;
                            o.nextLeg = tick + LEG_AGAIN;
                            double a = Math.PI * 2 * o.slot / Math.max(1, o.of), spread = o.of > 1 ? 3 : 0;
                            leg(o, bot, way.x(o.at) + Math.cos(a) * spread, way.z(o.at) + Math.sin(a) * spread);
                            // no place to stand round the way's point (in a wall, in water too deep): the next point
                            if (!go(bot, level, way.x(o.at) + Math.cos(a) * spread, way.z(o.at) + Math.sin(a) * spread, moves ? StaffMove.speed(o) : AiSoldier.WALK)) {
                                o.at = Math.min(o.at + 1, way.size() - 1);
                                o.nextLeg = tick + 1;
                            }
                        }
                        if (moves) StaffMove.pace(bot, o, tick);
                        return true;
                    }
                }
            }
            if (tick >= o.nextLeg || bot.mover.arrived() || stuck) {
                if (bot.mover.arrived()) o.fails = 0;
                // legs not walked to their ends (failed, or out of time), none of those since it got nearest the area
                // bringing it PROGRESS nearer (round and round a house with no way in; the legs turned aside it walks
                // to their ends in between count neither way): given up at IDLE_LEGS
                if (unstuck) {
                    if (d <= o.bestD - PROGRESS) {
                        o.bestD = d;
                        o.idleLegs = 0;
                    // (one of its squad sent to the same place was in it a moment ago: the place is to be got to, this
                    // one waits its turn, at a narrow doorway its own in the way)
                    } else if (mateIn(bot, o, tick)) o.idleLegs = 0;
                    else if (!bot.mover.arrived() && ++o.idleLegs >= IDLE_LEGS) return giveUp(bot, o, tick);
                }
                o.nextLeg = tick + LEG_AGAIN;
                // a leg that could not be walked: the next one shorter and turned aside, one way then the other
                double turn = o.fails == 0 ? 0 : (o.fails % 2 == 1 ? 1 : -1) * Math.PI / 4 * ((o.fails + 1) / 2);
                double step = Math.min(d, o.fails == 0 ? LEG : LEG / 2), along = Math.atan2(dz, dx) + turn;
                // the squad spread a little at the leg's end, each its own side
                double a = Math.PI * 2 * o.slot / Math.max(1, o.of), spread = o.of > 1 ? 3 : 0;
                leg(o, bot, bot.mob.getX() + Math.cos(along) * step + Math.cos(a) * spread, bot.mob.getZ() + Math.sin(along) * step + Math.sin(a) * spread);
                // no place to stand at the leg's end: counted as a leg not walked (the next one turned aside), soon
                if (!go(bot, level, bot.mob.getX() + Math.cos(along) * step + Math.cos(a) * spread, bot.mob.getZ() + Math.sin(along) * step + Math.sin(a) * spread,
                        moves ? StaffMove.speed(o) : AiSoldier.WALK)) {
                    o.fails++;
                    o.nextLeg = tick + 1;
                }
            }
            if (moves) StaffMove.pace(bot, o, tick);
            return true;
        }
        o.lastInside = tick;
        o.fails = 0;
        o.idleLegs = 0;
        // at its place: it stands there facing the group (not looking through an area, not giving it up)
        if (place) {
            o.job.there = true;
            if (o.job.kind == StaffPlan.Kind.FLANK) ready(level, o.job, tick);
            if (!o.stood) {
                o.stood = true;
                bot.mover.stop();
            }
            boolean face = Math.hypot(o.job.faceX - bot.mob.getX(), o.job.faceZ - bot.mob.getZ()) > 2;
            if (bot.mob.isAlive()) bot.gunner.aim(face ? new net.minecraft.world.phys.Vec3(o.job.faceX, bot.mob.getEyeY(), o.job.faceZ) : null);
            return true;
        }
        if (++o.inside > GIVE_UP) return giveUp(bot, o, tick);
        if (tick >= o.nextLeg || bot.mover.arrived() || bot.mover.failed()) {
            // a place it could not get to: none looked for near it again for the rest of the order
            if (bot.mover.failed() && bot.mover.goal() != Long.MIN_VALUE) {
                if (o.badPlaces.size() >= BAD_PLACES) o.badPlaces.remove(0);
                o.badPlaces.add(new double[] {BlockPos.getX(bot.mover.goal()) + 0.5, BlockPos.getZ(bot.mover.goal()) + 0.5});
            }
            o.nextLeg = tick + LOOK_AGAIN;
            o.lookUntil = tick + LOOK_AGAIN;
            room(o, bot, level);
            var rng = bot.mob.getRandom();
            double px = o.x, pz = o.z;
            boolean found = false;
            for (int k = 0; k < PLACE_TRIES && !found; k++) {
                double a = rng.nextDouble() * Math.PI * 2, r = Math.sqrt(rng.nextDouble()) * o.r;
                px = o.x + Math.cos(a) * r;
                pz = o.z + Math.sin(a) * r;
                found = !bad(o, px, pz) && inRoom(o, px, pz);
            }
            // none in its room: the middle of the area
            if (!found && o.room != AiRooms.OUTSIDE) {
                px = o.x;
                pz = o.z;
            }
            // no place to stand there: another soon
            if (!go(bot, level, px, pz, AiSoldier.WALK)) o.nextLeg = tick + 1;
        }
        return true;
    }

    private static boolean giveUp(AiBot bot, Order o, long tick) { return abandonOrder(STAFF, bot, o, tick); }

    private static void leg(AiBot bot, ServerLevel level, Order o, StaffMove.Leg leg, boolean stuck, boolean on, long tick) {
        // the mode's legs (a bound, a file on the march) are none of the staff's straight legs: their time out with no way
        // made is the mode's (one waits for the others), not a leg failed
        o.legD = Double.MAX_VALUE;
        if (leg.low() && bot.brain instanceof AiSoldier s) s.crouch(true);
        if (leg.hold()) {
            AiHold.held(bot, tick);
            if (bot.mover.goal() != Long.MIN_VALUE) bot.mover.stop();
            StaffMove.face(bot, o);
            return;
        }
        boolean moved = !(Math.hypot(leg.x() - o.legX, leg.z() - o.legZ) <= 1);
        if (!(leg.fresh() || stuck || on && moved || bot.mover.goal() == Long.MIN_VALUE && moved || tick >= o.nextLeg && (moved || !bot.mover.arrived()))) return;
        if (bot.mover.arrived()) o.fails = 0;
        o.nextLeg = tick + leg.again();
        o.legX = leg.x();
        o.legZ = leg.z();
        if (leg.cell() == Long.MIN_VALUE) {
            if (!go(bot, level, leg.x(), leg.z(), leg.speed())) o.nextLeg = tick + 1;
        }
        else {
            bot.mover.costs(PathSearch.Cost.NONE);
            bot.mover.goTo(leg.cell(), 0.35, leg.speed());
            if (bot.mob.isAlive()) bot.gunner.aim(new net.minecraft.world.phys.Vec3(leg.x(), BlockPos.getY(leg.cell()) + 1.5, leg.z()));
        }
        o.moveLeg = true;
    }

    private static boolean go(AiBot bot, ServerLevel level, double x, double z, double speed) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        long cell;
        if (!level.hasChunk(bx >> 4, bz >> 4)) cell = BlockPos.asLong(bx, bot.mob.getBlockY(), bz);
        else if (!snap) cell = BlockPos.asLong(bx, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz), bz);
        else {
            cell = stand(level, bx, bz, bot.mob.getBlockY());
            if (cell == Long.MIN_VALUE) return false;
        }
        bot.mover.costs(PathSearch.Cost.NONE);
        bot.mover.goTo(cell, 2, speed);
        if (bot.order != null) bot.order.moveLeg = false;
        if (bot.mob.isAlive()) bot.gunner.aim(new net.minecraft.world.phys.Vec3(x, BlockPos.getY(cell) + 1.5, z));
        return true;
    }

    private static boolean mateIn(AiBot bot, Order o, long tick) {
        if (o.lastInside != Long.MIN_VALUE && tick - o.lastInside <= MATE_IN) return true;
        if (!(bot.mob.level() instanceof ServerLevel level)) return false;
        String team = AiSquads.team(bot);
        // (its side's, not its squad's alone: those sent there together need not be one squad)
        for (AiBot b : AiBots.bots(level)) {
            if (!AiSquads.team(b).equals(team)) continue;
            Order m = b.order;
            if (b == bot || m == null || !AiSquads.live(b) || Math.abs(m.x - o.x) > 1 || Math.abs(m.z - o.z) > 1) continue;
            if (m.lastInside != Long.MIN_VALUE && tick - m.lastInside <= MATE_IN) return true;
        }
        return false;
    }

    static long stand(ServerLevel level, int bx, int bz, int ownY) {
        return StaffGround.stand(acup.gunfeel.Wiring.world(level), TacticalMap.of(level), bx, bz, ownY);
    }

    private static void leg(Order o, AiBot bot, double x, double z) {
        o.legTX = x;
        o.legTZ = z;
        o.legD = Math.hypot(x - bot.mob.getX(), z - bot.mob.getZ());
    }

    private static void room(Order o, AiBot bot, ServerLevel level) {
        if (Math.hypot(o.roomX - o.x, o.roomZ - o.z) <= 1) return;
        o.roomX = o.x;
        o.roomZ = o.z;
        o.rooms = null;
        o.room = AiRooms.OUTSIDE;
        o.roomY = Mth.floor(bot.mob.getY() + 0.2);
        long mid = BlockPos.asLong(Mth.floor(o.x), o.roomY, Mth.floor(o.z));
        AiRooms.Plan plan = AiRooms.of(TacticalMap.of(level), mid);
        if (plan == null || plan.room(mid) == AiRooms.OUTSIDE) return;
        o.rooms = plan;
        o.room = plan.room(mid);
    }

    private static boolean inRoom(Order o, double x, double z) {
        return o.rooms == null || o.rooms.room(BlockPos.asLong(Mth.floor(x), o.roomY, Mth.floor(z))) == o.room;
    }

    private static boolean bad(Order o, double x, double z) {
        for (double[] p : o.badPlaces) if (Math.hypot(p[0] - x, p[1] - z) < BAD_NEAR) return true;
        return false;
    }

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
