package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ReaimTimes;
import acup.gunfeel.ai.AiIntelBase.Group;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
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
final class AiCommanderOld extends AiCommanderBase {

    /** falling back pinned down (C5): it breaks contact, its smoke first, rather than fight on where it is */
    static boolean breaksContact(AiBot bot, long tick) {
        AiCommander.Order o = bot.order;
        return o != null && tick <= o.until && o.job != null && o.job.kind == StaffPlan.Kind.BACK && o.job.breaking && on();
    }

    /** new ways the side giving its orders now may still make */
    private static int waysLeft;

    /** a side's own: its next orders */
    private static final class Side {
        long next = Long.MIN_VALUE;
        /** with no group known, the sector of the ring round it its squads scout next (C2) */
        int sector;
        /** the side's reports seen (a new one: its map made again) */
        long reports;
        /** its last tasks given with no map yet (the pairs on: none to go round): all looked at afresh once it has one */
        boolean blind;
        /** its last orders given at; the first report since (Long.MIN_VALUE: none) */
        long commanded = Long.MIN_VALUE, asked = Long.MIN_VALUE;
    }

    private static final Map<ServerLevel, Map<String, Side>> LEVELS = new WeakHashMap<>();
    /** the soonest a side's picture is due again, per level */
    private static final Map<ServerLevel, Long> reportDue = new WeakHashMap<>();

    static long askedLag, askedN, askedMost, answeredAt = Long.MIN_VALUE, scoutsFound, broke, coupleTicks, drifted, driftBlocked, driftWater, detours, pressed;
    static int paired, coupled, expired, pairedAgain, keptTime, upAtOnce, otherDied, graced, orders, reserve, gaveUp, helped, strays, joinedCrossing, tails, tailsNot;
    static final int[] given = new int[StaffPlan.Kind.values().length];
    static final Map<String, Long> commandedAt = new HashMap<>();
    static final java.util.concurrent.atomic.AtomicLong routes = new java.util.concurrent.atomic.AtomicLong(), routeNanos = new java.util.concurrent.atomic.AtomicLong();
    static final List<HqGrid.Route> made = new java.util.concurrent.CopyOnWriteArrayList<>();

    static long time(AiCommanderBase.Job<?, ?> f, AiCommanderBase.Job<?, ?> p, long fresh, long now) {
        long was = Math.min(f.dueWas, p.dueWas);
        // the one whose other died paired with a new one not at its place yet (the new one pinning still on its way)
        boolean dead = f.otherDead || p.otherDead, grace = f.otherDead && !p.there || p.otherDead && !f.there;
        // taken up by this pair: its time from now on is the pair's own (handed on while it is not over)
        f.otherDead = p.otherDead = false;
        f.dueWas = p.dueWas = Long.MAX_VALUE;
        if (was == Long.MAX_VALUE) return fresh;
        pairedAgain++;
        if (dead) otherDied++;
        long kept = grace ? Math.max(was, now + PAIR_GRACE) : was;
        if (kept >= fresh) return fresh;
        keptTime++;
        if (kept > was) graced++;
        if (kept <= now) upAtOnce++;
        return kept;
    }

    private AiCommanderOld() {
    }

    public static void forget(ServerLevel level) {
        LEVELS.remove(level);
        AiReports.forget(level);
        AiIntel.forget(level);
        AiHqMap.forget(level);
    }

    /**
     * One of a side down (AiBots.died): where the side loses soldiers. The one that downed it, if of a side it fights;
     * else whom its squad fought; else the group it was sent to.
     */
    static void died(ServerLevel level, AiBot fallen, long tick) {
        if (!on()) return;
        Map<String, Side> sides = LEVELS.get(level);
        String team = AiSquads.team(fallen);
        if (sides == null || !sides.containsKey(team)) return;
        // the mob that hit it last, else whom the game credits (a player who hit it lately first)
        LivingEntity by = fallen.mob.getLastHurtByMob();
        if (by == null || !AiTeams.enemy(fallen.mob, by)) by = fallen.mob.getKillCredit();
        AiSquads.Squad s = AiSquads.of(fallen);
        if (by != null && by != fallen.mob && AiTeams.enemy(fallen.mob, by)) AiIntel.lost(level, team, by.getId(), tick);
        else if (s != null && s.target >= 0) AiIntel.lost(level, team, s.target, tick);
        else if (fallen.order != null && tick <= fallen.order.until) for (int id : fallen.order.ids) AiIntel.lost(level, team, id, tick);
    }

    /** squads that may go to this group: PER, one more where the side lost a soldier lately */
    private static int room(Group g, long now) {
        return help() && g.known().lost != Long.MIN_VALUE && now - g.known().lost <= HELP ? PER + HELP_MORE : PER;
    }

    /** every server tick, after the squads are made (AiBots): each side whose time it is gives its orders */
    static void tick(ServerLevel level) {
        if (!on()) return;
        long now = level.getGameTime();
        if (tasks() && now % DRIFT == 0) drift(level, now);
        if (tasks() && pairs() && now % COUPLE == 0) couple(level, now);
        if (tasks() && now % LOOK == 0) look(level, now);
        Map<String, Side> sides = LEVELS.computeIfAbsent(level, k -> new HashMap<>());
        boolean due = sides.isEmpty() || now >= reportDue.getOrDefault(level, Long.MIN_VALUE);
        for (Side side : sides.values()) due |= now >= side.next;
        // a side not met before is due at once; else nothing to do before the soonest one's time, orders or report (a
        // new side is found on the next one's, at most EVERY_MAX later)
        if (!due) return;
        Map<String, List<AiSquads.Squad>> byTeam = new HashMap<>();
        for (AiSquads.Squad s : AiSquads.squads(level)) {
            String team = AiSquads.team(s.bots.get(0));
            if (!team.isEmpty()) byTeam.computeIfAbsent(team, t -> new ArrayList<>()).add(s);
        }
        // with the staff's tasks, its soldiers asleep too, in squads of its own (C2b): a side all asleep is a side
        if (tasks()) for (AiSquads.Squad s : far(level)) byTeam.computeIfAbsent(AiSquads.team(s.bots.get(0)), t -> new ArrayList<>()).add(s);
        // no side with squads (AI mobs of no team, or alone): looked at again with the squads made again
        if (byTeam.isEmpty()) {
            sides.clear();
            reportDue.put(level, now + AiSquads.EVERY);
            return;
        }
        // the sides' pictures first (AiIntel): every soldier of a side with squads counts, alone, asleep or in a squad
        Map<String, List<AiBot>> own = new HashMap<>();
        for (String team : byTeam.keySet()) own.put(team, new ArrayList<>());
        for (AiBot b : AiBots.bots(level)) {
            List<AiBot> l = b.brain instanceof AiSoldier && b.mob.isAlive() && !b.mob.isRemoved() ? own.get(AiSquads.team(b)) : null;
            if (l != null) l.add(b);
        }
        reportDue.put(level, AiIntel.tick(level, own, now));
        for (var e : byTeam.entrySet()) {
            Side side = sides.computeIfAbsent(e.getKey(), t -> new Side());
            // a new report: the staff's map made again (off the server thread)
            long reports = AiIntel.reports(level, e.getKey());
            if (reports != side.reports) {
                side.reports = reports;
                if (staff()) {
                    // the map round its squads awake (those it gives orders to), not its loners nor its squads asleep
                    // far off (C2b) unless it has none awake
                    List<AiBot> inSquads = new ArrayList<>();
                    for (AiSquads.Squad s : e.getValue()) if (!asleep(s)) inSquads.addAll(s.bots);
                    // all of it asleep (C2b): round those
                    if (inSquads.isEmpty()) for (AiSquads.Squad s : e.getValue()) inSquads.addAll(s.bots);
                    AiHqMap.report(level, e.getKey(), inSquads, AiIntel.groups(level, e.getKey()));
                }
            }
            if (now < side.next) continue;
            side.next = now + EVERY_MIN + level.getRandom().nextInt(EVERY_MAX - EVERY_MIN + 1);
            command(level, e.getKey(), side, e.getValue(), now);
        }
        sides.keySet().retainAll(byTeam.keySet());
    }

    private static void command(ServerLevel level, String team, Side side, List<AiSquads.Squad> squads, long now) {
        // a report's answer: how long after it the orders were given again (C5)
        if (side.asked != Long.MIN_VALUE) {
            long lag = now - side.asked;
            askedLag += lag;
            askedN++;
            askedMost = Math.max(askedMost, lag);
            side.asked = Long.MIN_VALUE;
            answeredAt = now;
        }
        side.commanded = now;
        List<Group> groups = groups(level, team);
        waysLeft = WAYS;
        commandedAt.put(team, now);
        if (tasks()) {
            tasks(level, team, side, squads, groups, now);
            return;
        }
        Map<Group, Integer> sent = new java.util.IdentityHashMap<>();
        List<AiSquads.Squad> going = new ArrayList<>(), idle = new ArrayList<>();
        // squads at a fight first: counted to the group of their target
        for (AiSquads.Squad s : squads) {
            if (s.target < 0) {
                going.add(s);
                continue;
            }
            Group t = counted(s, groups, now);
            if (t != null) sent.merge(t, 1, Integer::sum);
        }
        // then those on their way to a group still there (known by any of its members), as they are while there is
        // room: by the order most of the squad carries (squads are made again as they move: a few with an order in a
        // squad of others is not a squad sent)
        for (AiSquads.Squad s : going) {
            Group g = going(s, groups, now);
            if (g != null && !failed(s, g, now) && sent.getOrDefault(g, 0) < room(g, now)) {
                sent.merge(g, 1, Integer::sum);
                give(level, team, s, g, now, false);
            } else idle.add(s);
        }
        // the rest: each the nearest group with room, not one it gave up lately; none: the reserve
        double reach = Math.min(REACH, AiBots.radius() * 2);
        for (AiSquads.Squad s : idle) {
            double sx = 0, sz = 0;
            for (AiBot b : s.bots) {
                sx += b.mob.getX() / s.bots.size();
                sz += b.mob.getZ() / s.bots.size();
            }
            Group best = null;
            double bestD = reach * reach;
            for (Group g : groups) {
                if (sent.getOrDefault(g, 0) >= PER || failed(s, g, now)) continue;
                double dx = g.x() - sx, dz = g.z() - sz, d = dx * dx + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = g;
                }
            }
            // none: the reserve, unless there is a group where the side loses soldiers with room for one more
            boolean helping = false;
            if (best == null) for (Group g : groups) {
                if (sent.getOrDefault(g, 0) >= room(g, now) || failed(s, g, now)) continue;
                double dx = g.x() - sx, dz = g.z() - sz, d = dx * dx + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = g;
                    helping = true;
                }
            }
            if (best == null) {
                for (AiBot b : s.bots) b.order = null;
                reserve++;
                continue;
            }
            if (helping) helped++;
            sent.merge(best, 1, Integer::sum);
            give(level, team, s, best, now, true);
        }
    }

    /**
     * The staff's tasks (C2): each squad as the plan has it (StaffPlan) from its strength, losses, rounds and the task
     * most of it has now; each group from its kinds and health, how lately seen, its room (R5b).
     */
    private static void tasks(ServerLevel level, String team, Side side, List<AiSquads.Squad> squads, List<Group> groups, long now) {
        List<StaffPlan.Group> foes = new ArrayList<>(groups.size());
        for (Group g : groups) {
            IntelBook.Group k = g.known();
            int n = 0;
            double strength = 0;
            for (int kind = 0; kind < k.kinds.length; kind++) {
                n += k.kinds[kind];
                strength += k.kinds[kind] * weight(kind);
            }
            if (n > 0) strength *= Math.min(1, k.health / n);
            foes.add(new StaffPlan.Group(g.x(), g.z(), g.r(), strength, k.seen == Long.MIN_VALUE ? Long.MAX_VALUE : now - k.seen, room(g, now)));
        }
        List<StaffPlan.Squad> own = new ArrayList<>(squads.size());
        List<AiCommander.Job> jobs = new ArrayList<>(squads.size());
        double sideX = 0, sideZ = 0;
        int all = 0;
        boolean supply = AiSupply.on() && ReaimTimes.flag(AiSupply.OUT_KEY, true);
        HqGrid grid = AiHqMap.of(level, team);
        boolean afresh = side.blind && grid != null;
        for (AiSquads.Squad s : squads) {
            double sx = 0, sz = 0, strength = 0;
            int alive = 0, out = 0;
            for (AiBot b : s.bots) {
                sx += b.mob.getX() / s.bots.size();
                sz += b.mob.getZ() / s.bots.size();
                if (!b.mob.isAlive()) continue;
                alive++;
                if (!supply || !red(b)) b.redFrom = Long.MIN_VALUE;
                else {
                    // at red, and none brought it RED_WAIT: it fights with what it has (its squad is not out for it,
                    // however the squads are made again meanwhile)
                    if (b.redFrom == Long.MIN_VALUE) b.redFrom = now;
                    if (now - b.redFrom < RED_WAIT) out++;
                }
                // its strength: one with no round besides its gun's at a third (red, a magazine left, still fights)
                boolean empty = supply && !b.gunner.ammo.infinite() && b.gunner.ammo.reserve() <= 0 && b.gunner.capacity() > 0;
                strength += weight(AiRoles.of(b).ordinal()) * Math.min(1, b.mob.getHealth() / b.mob.getMaxHealth()) * (empty ? 0.3 : 1);
                sideX += b.mob.getX();
                sideZ += b.mob.getZ();
                all++;
            }
            AiCommander.Job job = job(s, now);
            // out of rounds: most of it at red; once back, till none of it is (one filled by its supplier is no reason
            // to send it on while the rest are still at red: it would only fall back again)
            boolean outOf = out * 2 > alive || out > 0 && job != null && job.kind == StaffPlan.Kind.BACK;
            // a squad split on the way: the squad before it with the same task (the plan gives them one)
            int twin = job == null ? -1 : jobs.indexOf(job);
            jobs.add(job);
            int group = job == null ? -1 : groups.indexOf(match(job.ids, groups));
            // a group it gave up lately (none of it could get there): not again for AVOID
            int avoid = -1;
            for (int k = 0; k < groups.size() && avoid < 0; k++) if (failed(s, groups.get(k), now)) avoid = k;
            boolean pair = pairs() && job != null && job.partner != null;
            // a pair's time up with the one going round not ready: counted here, before the plan gives it another task
            if (pair && job.kind == StaffPlan.Kind.FLANK && job.ready == Long.MIN_VALUE && now >= job.due && !job.late) {
                job.late = true;
                expired++;
            }
            own.add(new StaffPlan.Squad(sx, sz, strength, job == null ? alive : job.alive(), job == null ? alive : job.bots.size(), outOf, job == null ? null : job.kind,
                    job == null ? 0 : afresh ? now - StaffPlan.KEEP : Math.max(job.since, job.foundAt), group, job != null && job.there, avoid, asleep(s), pair && job.ready != Long.MIN_VALUE, pair ? job.due : Long.MAX_VALUE,
                    job == null || !reports() ? Long.MIN_VALUE : job.pinnedAt, job != null && job.pinnedAt != Long.MIN_VALUE && job.alive() < job.aliveAtPin,
                    pair && job.partner != null && job.partner.alive() > 0 && !job.late, twin, job != null && job.place() && stands(job, groups), out > 0 && !afresh));
        }
        if (all == 0) return;
        sideX /= all;
        sideZ /= all;
        StaffPlan.Task[] plan = StaffPlan.plan(own, foes, grid, sideX, sideZ, Math.min(REACH, AiBots.radius() * 2), PER, now, side.sector, mapped());
        side.blind = grid == null && mapped();
        for (int i = 0; i < squads.size(); i++) {
            // a squad crossing a river now keeps its task till it is over (the drill is not broken off half way)
            AiCommander.Job cj = jobs.get(i);
            if (cj != null && crossing(cj)) {
                // its orders kept alive meanwhile (they last LASTS); one of the squad made since with none of it is given it
                for (AiBot b : cj.members) if (b.order != null && b.order.job == cj) b.order.until = Math.max(b.order.until, now + LASTS);
                for (AiBot b : squads.get(i).bots) {
                    if (b.order != null && b.order.job == cj) b.order.until = Math.max(b.order.until, now + LASTS);
                    else if (b.mob.isAlive()) join(b, cj, now);
                }
                continue;
            }
            task(level, team, side, squads.get(i), jobs.get(i), plan[i], plan[i].group() < 0 ? null : groups.get(plan[i].group()), now);
            // sent round or to pin for one of the side pinned down by that group (C5's help, not a change of mind)
            if (helps(own, plan, i)) {
                AiCommander.Job nj = job(squads.get(i), now);
                if (nj != null && nj.kind == plan[i].kind()) nj.helps = true;
            }
            // pinned down and sent back (C5): it breaks contact
            if (plan[i].kind() == StaffPlan.Kind.BACK && own.get(i).pinned() != Long.MIN_VALUE) {
                AiCommander.Job nj = job(squads.get(i), now);
                if (nj != null && nj.kind == StaffPlan.Kind.BACK && !nj.breaking) {
                    nj.breaking = true;
                    broke++;
                }
            }
        }
        if (pairs()) pair(squads, grid, now);
        strays(level, team, squads, now);
    }

    /**
     * The side's soldiers asleep in squads of the staff's own (C2b), as AiSquads makes them of those awake: each grown
     * from one, the nearest of its side within AiSquads.NEAR of any in it, AiSquads.MAX at most; one alone is one too.
     * (A base of fire is not kept BASE_REACH times as far here as AiSquads keeps it: asleep, no one goes round a side.)
     * Only those within AiIntel.KNOW and aiRadius of a player (farther none of their enemies may wake: no work for a
     * server no one plays near), and none still in a squad of AiSquads' (asleep since it was made).
     */
    static List<AiSquads.Squad> far(ServerLevel level) {
        List<AiSquads.Squad> out = new ArrayList<>();
        if (!AiSquads.on) return out;
        double near = AiIntel.KNOW + AiBots.radius();
        List<AiBot> free = new ArrayList<>();
        List<String> teams = new ArrayList<>();
        for (AiBot b : AiBots.bots(level)) {
            if (b.active || !(b.brain instanceof AiSoldier) || !b.mob.isAlive() || b.mob.isRemoved() || AiSquads.of(b) != null) continue;
            String team = AiSquads.team(b);
            if (team.isEmpty() || AiSquads.offTeams.contains(team) || !playerWithin(level, b, near)) continue;
            free.add(b);
            teams.add(team);
        }
        if (free.isEmpty()) return out;
        // by AiSquads.NEAR cells: a squad grows from the cells round its own only
        Map<Long, List<Integer>> cells = new HashMap<>();
        for (int i = 0; i < free.size(); i++) cells.computeIfAbsent(cell(free.get(i)), c -> new ArrayList<>()).add(i);
        boolean[] taken = new boolean[free.size()];
        double near2 = AiSquads.NEAR * AiSquads.NEAR;
        for (int i = 0; i < free.size(); i++) {
            if (taken[i]) continue;
            AiSquads.Squad s = new AiSquads.Squad();
            taken[i] = true;
            s.bots.add(free.get(i));
            String team = teams.get(i);
            while (s.bots.size() < AiSquads.MAX) {
                int best = -1;
                double bestD = near2;
                for (AiBot in : s.bots) {
                    int cx = Mth.floor(in.mob.getX() / AiSquads.NEAR), cz = Mth.floor(in.mob.getZ() / AiSquads.NEAR);
                    for (int ox = -1; ox <= 1; ox++)
                        for (int oz = -1; oz <= 1; oz++)
                            for (int j : cells.getOrDefault(key(cx + ox, cz + oz), List.of())) {
                                if (taken[j] || !team.equals(teams.get(j))) continue;
                                double d = in.mob.distanceToSqr(free.get(j).mob);
                                if (d <= bestD) {
                                    bestD = d;
                                    best = j;
                                }
                            }
                }
                if (best < 0) break;
                taken[best] = true;
                s.bots.add(free.get(best));
            }
            for (AiBot b : s.bots) {
                s.cx += b.mob.getX() / s.bots.size();
                s.cz += b.mob.getZ() / s.bots.size();
            }
            out.add(s);
        }
        return out;
    }

    private static long cell(AiBot b) {
        return key(Mth.floor(b.mob.getX() / AiSquads.NEAR), Mth.floor(b.mob.getZ() / AiSquads.NEAR));
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static boolean playerWithin(ServerLevel level, AiBot b, double r) {
        for (Player p : level.players()) if (!p.isSpectator() && p.distanceToSqr(b.mob) <= r * r) return true;
        return false;
    }

    /** most of the squad asleep: one of the staff's own (far) */
    static boolean asleep(AiSquads.Squad s) {
        int n = 0;
        for (AiBot b : s.bots) if (!b.active) n++;
        return n * 2 > s.bots.size();
    }

    /**
     * The soldiers asleep with a task, moved by the staff (C2b): a step along the way (straight where there is none),
     * each to its own side of the point as awake (step; by water nearly on it), onto the middle of the block on the
     * ground as the heightmap has it, in loaded chunks only; not into deep water or lava (a ford a block deep it wades),
     * nor up or down more than DRIFT_CLIMB. Deep water ahead: a way round it over the blocks (detour), followed; else,
     * or the ground, a step turned aside, one way then the other, and FAILS such in a row give the task up as awake.
     * A point of the way in water, or before a stretch over it, is reached on it (DRIFT_AT_WATER), not at REACHED: it
     * goes into the ford where the way does. None once at the task's place (got there, as awake), nor within
     * DRIFT_STOP of a group of the enemy. Woken (a player near), it goes on from where it is by its order as any other.
     */
    private static void drift(ServerLevel level, long now) {
        Map<String, List<Group>> groupsOf = new HashMap<>();
        for (AiBot b : AiBots.bots(level)) {
            if (b.active || !(b.brain instanceof AiSoldier) || !b.mob.isAlive() || b.mob.isRemoved() || !has(b, now)) continue;
            AiCommander.Order o = b.order;
            if (o.job == null) continue;
            String team = AiSquads.team(b);
            if (!AiSquads.on || AiSquads.offTeams.contains(team)) continue;
            double x = b.mob.getX(), z = b.mob.getZ(), d = Math.hypot(o.x - x, o.z - z);
            if (o.job.place() ? d <= o.r : d <= o.r * 0.5) {
                if (o.job.place()) o.job.there = true;
                o.fails = 0;
                continue;
            }
            boolean close = false;
            for (Group g : groupsOf.computeIfAbsent(team, t -> groups(level, t))) if (Math.hypot(g.x() - x, g.z() - z) <= DRIFT_STOP) close = true;
            if (close) continue;
            // toward the next point of its way, else straight to the place; its own side of it
            if (o.route == null && !o.noRoute) takeUp(o, b);
            double tx = o.x, tz = o.z;
            HqGrid.Route way = o.route;
            HqGrid grid = AiHqMap.of(level, team);
            if (way != null) {
                while (o.at < way.size() - 1 && Math.hypot(way.x(o.at) - x, way.z(o.at) - z) <= reached(grid, way, o.at)) o.at++;
                if (o.at < way.size() - 1 || Math.hypot(way.x(o.at) - x, way.z(o.at) - z) > REACHED) {
                    tx = way.x(o.at);
                    tz = way.z(o.at);
                }
            }
            // its own side of the point, not by water (a ford is narrow)
            // (by water, or at a point to be reached on it: nearly on it)
            boolean onIt = way != null && o.at < way.size() && reached(grid, way, o.at) < REACHED;
            double a = Math.PI * 2 * o.slot / Math.max(1, o.of), spread = o.of <= 1 ? 0 : onIt || nearWater(grid, tx, tz) ? 1 : 3;
            tx += Math.cos(a) * spread;
            tz += Math.sin(a) * spread;
            double l = Math.hypot(tx - x, tz - z);
            if (l < 1e-3) continue;
            // on a way round deep water found before: along it, while it is where the last step left it and goes for
            // the same point (woken and moved meanwhile, or its way changed: dropped)
            if (o.detour != null) {
                long last = o.detourAt == 0 ? o.detourFrom : o.detour[o.detourAt - 1];
                if (Math.abs((int) (last >> 32) - Mth.floor(x)) + Math.abs((int) last - Mth.floor(z)) > 2 || Math.hypot(o.detourTX - tx, o.detourTZ - tz) > 2) o.detour = null;
            }
            if (o.detour != null) {
                int k = Math.min(o.detour.length - 1, o.detourAt + (int) DRIFT_STEP - 1);
                long n = o.detour[k];
                int dx = (int) (n >> 32), dz = (int) n;
                if (level.hasChunk(dx >> 4, dz >> 4)) {
                    o.detourAt = k + 1;
                    if (o.detourAt >= o.detour.length) o.detour = null;
                    double turn = Math.atan2(dz + 0.5 - z, dx + 0.5 - x);
                    b.mob.snapTo(dx + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, dx, dz), dz + 0.5, (float) Math.toDegrees(Math.atan2(-Math.cos(turn), Math.sin(turn))), 0F);
                    b.mob.setYHeadRot(b.mob.getYRot());
                    o.fails = 0;
                    drifted++;
                }
                continue;
            }
            // the step turned aside after one that could not be made, one way then the other
            double along = Math.atan2(tz - z, tx - x) + (o.fails == 0 ? 0 : (o.fails % 2 == 1 ? 1 : -1) * Math.PI / 4 * ((o.fails + 1) / 2));
            double step = Math.min(DRIFT_STEP, l);
            int bx = Mth.floor(x + Math.cos(along) * step), bz = Mth.floor(z + Math.sin(along) * step);
            // its point within the block it stands on (a step to it goes nowhere): reached, on to the next
            if (bx == Mth.floor(x) && bz == Mth.floor(z) && o.fails == 0) {
                if (way != null && o.at < way.size() - 1) o.at++;
                continue;
            }
            // a chunk not loaded: it waits there
            if (!level.hasChunk(bx >> 4, bz >> 4)) continue;
            int ny = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
            // into water a block deep (a ford) it wades; deeper water, or any other liquid, it does not go into
            var fluid = level.getFluidState(new BlockPos(bx, ny - 1, bz));
            boolean wet = !fluid.isEmpty() && (!fluid.is(net.minecraft.tags.FluidTags.WATER) || !level.getFluidState(new BlockPos(bx, ny - 2, bz)).isEmpty());
            // (by the block itself, not the staff's map's cell: a ford in a cell mostly deep)
            if (wet || Math.abs(ny - b.mob.getY()) > DRIFT_CLIMB) {
                driftBlocked++;
                if (wet) driftWater++;
                // deep water ahead: a way round it over the blocks (by the ford), now and then at most
                if (wet && (o.detourTick == Long.MIN_VALUE || now - o.detourTick >= DETOUR_AGAIN)) {
                    o.detourTick = now;
                    long[] path = detour(level, Mth.floor(x), Mth.floor(z), Mth.floor(b.mob.getY()), tx, tz);
                    if (path != null) {
                        detours++;
                        o.detour = path;
                        o.detourAt = 0;
                        o.detourFrom = (long) Mth.floor(x) << 32 | Mth.floor(z) & 0xFFFFFFFFL;
                        o.detourTX = tx;
                        o.detourTZ = tz;
                        continue;
                    }
                    // no nearer to it by the blocks, its point within REACHED (one in deep water it cannot stand on): on to the next
                    if (way != null && o.at < way.size() - 1 && Math.hypot(way.x(o.at) - x, way.z(o.at) - z) <= REACHED) {
                        o.at++;
                        o.fails = 0;
                        continue;
                    }
                }
                if (++o.fails >= FAILS) giveUp(b, o, now);
                continue;
            }
            o.fails = 0;
            b.mob.snapTo(bx + 0.5, ny, bz + 0.5, (float) Math.toDegrees(Math.atan2(-Math.cos(along), Math.sin(along))), 0F);
            b.mob.setYHeadRot(b.mob.getYRot());
            drifted++;
        }
    }

    /** asleep, a point of its way: reached within REACHED, or DRIFT_AT_WATER when it or the stretch on from it is over water */
    private static double reached(@Nullable HqGrid grid, HqGrid.Route way, int at) {
        if (grid == null) return REACHED;
        double x = way.x(at), z = way.z(at);
        if (StaffCross.water(grid, x, z) > 0) return DRIFT_AT_WATER;
        if (at + 1 < way.size()) {
            double nx = way.x(at + 1), nz = way.z(at + 1), len = Math.hypot(nx - x, nz - z);
            for (double t = 2; t < len; t += 2)
                if (StaffCross.water(grid, x + (nx - x) * t / len, z + (nz - z) * t / len) > 0) return DRIFT_AT_WATER;
        }
        return REACHED;
    }

    /** water on the staff's map within DRIFT_WET of x, z (no spread of the squad there) */
    private static boolean nearWater(@Nullable HqGrid grid, double x, double z) {
        if (grid == null) return false;
        for (int i = -1; i <= 1; i++)
            for (int k = -1; k <= 1; k++)
                if (StaffCross.water(grid, x + i * DRIFT_WET, z + k * DRIFT_WET) > 0) return true;
        return false;
    }

    /**
     * asleep, deep water in its step: a way over the blocks within DETOUR of it (dry ground, or water a block deep: a
     * ford; up or down DRIFT_CLIMB at most a step) to the one nearest to tx, tz; null: none nearer by 2 at least.
     * The blocks it goes by after its own, x << 32 | z.
     */
    static long @Nullable [] detour(ServerLevel level, int x0, int z0, int y0, double tx, double tz) {
        return StaffGround.detour(acup.gunfeel.Wiring.world(level), acup.gunfeel.water.LevelFluids.of(level), x0, z0, y0, tx, tz);
    }

    /**
     * A soldier with a task of the staff's left out of the squads (more than AiSquads.NEAR off the others: it fell
     * behind): the task of the nearest squad of its side within reach as that squad has it now, to catch up with it;
     * none: its order runs out.
     */
    private static void strays(ServerLevel level, String team, List<AiSquads.Squad> squads, long now) {
        double reach = Math.min(REACH, AiBots.radius() * 2);
        for (AiBot b : AiBots.bots(level)) {
            AiCommander.Order mine = b.order;
            if (mine == null || mine.job == null || now > mine.until || !(b.brain instanceof AiSoldier) || !b.mob.isAlive() || !b.active) continue;
            if (AiSquads.of(b) != null || !team.equals(AiSquads.team(b))) continue;
            AiCommander.Order best = null;
            double bestD = reach * reach;
            for (AiSquads.Squad s : squads) {
                // after one awake as it is (one asleep goes at the staff's pace, far off)
                if (asleep(s)) continue;
                AiCommander.Order o = null;
                double sx = 0, sz = 0;
                for (AiBot c : s.bots) {
                    sx += c.mob.getX() / s.bots.size();
                    sz += c.mob.getZ() / s.bots.size();
                    if (o == null && c.order != null && c.order.job != null && now <= c.order.until) o = c.order;
                }
                double dx = sx - b.mob.getX(), dz = sz - b.mob.getZ(), d = dx * dx + dz * dz;
                if (o != null && d < bestD) {
                    bestD = d;
                    best = o;
                }
            }
            // none within reach: its order runs out, it is on its own
            if (best == null) continue;
            AiCommander.Order o = best.job == mine.job ? mine : fresh(now);
            o.job = best.job;
            o.x = best.x;
            o.z = best.z;
            o.r = best.r;
            o.lead = best.lead;
            o.ids = best.ids;
            o.until = now + LASTS;
            o.of = 1;
            if (o.way != best.way) {
                o.way = best.way;
                o.route = null;
                o.noRoute = false;
                o.routeFails = 0;
                takeUp(o, b);
            }
            if (o != mine) strays++;
            b.order = o;
        }
    }

    /** the task most of the squad has now (none: null) */
    static AiCommander.@Nullable Job job(AiSquads.Squad s, long now) {
        AiCommander.Job best = null;
        int most = 0;
        for (AiBot b : s.bots) {
            AiCommander.Job j = b.order != null && now <= b.order.until ? b.order.job : null;
            if (j == null) continue;
            int n = 0;
            for (AiBot c : s.bots) if (c.order != null && now <= c.order.until && c.order.job == j) n++;
            if (n > most) {
                most = n;
                best = j;
            }
        }
        return most * 2 >= s.bots.size() ? best : null;
    }

    /** the squad's task: the one it has if the same (its time kept), else a new one; its way there, each its order */
    private static void task(ServerLevel level, String team, Side side, AiSquads.Squad s, AiCommander.@Nullable Job had, StaffPlan.Task t, @Nullable Group g, long now) {
        int[] ids = g == null ? new int[0] : g.ids();
        // a squad scouting a group sent to advance on it (the side has seen it now): the same task, its time, pair and
        // way of going kept (StaffPlan.area: scouting a group not seen lately)
        boolean found = had != null && g != null && had.kind == StaffPlan.Kind.SCOUT && t.kind() == StaffPlan.Kind.ADVANCE && g.any(had.ids);
        boolean same = had != null && (had.kind == t.kind() || found) && (g == null ? had.ids.length == 0 : g.any(had.ids));
        if (found) {
            had.kind = StaffPlan.Kind.ADVANCE;
            had.foundAt = now;
            scoutsFound++;
        }
        // a sector scouted with no group known: the same one till it is looked through (given up there); a new one
        // turns the side's sectors on
        if (t.kind() == StaffPlan.Kind.SCOUT && g == null) {
            if (same) t = kept(s, had, t, false);
            else side.sector++;
        }
        // a place to stand at kept while its group stays about where it was (it is not picked again as the picture
        // and the squad move: a squad going round does not swap sides halfway); falling back, the place it went for
        if (same && had.place() && (g == null || Math.hypot(g.x() - had.gx, g.z() - had.gz) <= REROUTE)) t = kept(s, had, t, true);
        // falling back, the losses still counted from those it had before (not from those it has left)
        AiCommander.Job job = same ? had
                : new AiCommander.Job(t.kind(), now, t.kind() == StaffPlan.Kind.BACK && had != null ? had.bots : List.copyOf(s.bots), ids);
        // a pair's time goes on with a new task at the same group (C4: the two changing over, one sent to scout the
        // group's place and back): a pair made with it again does not start the way round's time afresh
        // (a pair whose time ran out is over: both advance, nothing handed on)
        if (!same && had != null && g != null && g.any(had.ids)) {
            job.dueWas = Math.min(had.dueWas, had.partner != null && had.ready == Long.MIN_VALUE && had.due > now ? had.due : Long.MAX_VALUE);
            job.otherDead = had.otherDead;
        }
        // its contact and its being pinned down go on with its new task (C5): not reported again, not forgotten
        if (!same && had != null) {
            job.saidContact = had.saidContact;
            job.noFoeFrom = had.noFoeFrom;
            job.saidAmmo = had.saidAmmo;
            // falling back, the losses counted from the same soldiers
            if (job.bots == had.bots) job.saidLosses = had.saidLosses;
            job.pinFrom = had.pinFrom;
            job.pinnedAt = had.pinnedAt;
            job.quietFrom = had.quietFrom;
            job.aliveAtPin = job.alive() + Math.max(0, had.aliveAtPin - had.alive());
            job.breaking = had.breaking && t.kind() == StaffPlan.Kind.BACK;
        }
        if (!same || g != null && Math.hypot(g.x() - job.gx, g.z() - job.gz) > REROUTE) {
            job.gx = g == null ? t.x() : g.x();
            job.gz = g == null ? t.z() : g.z();
        }
        if (!same) {
            given[t.kind().ordinal()]++;
            if (t.kind() == StaffPlan.Kind.RESERVE) reserve++;
            else if (t.kind() != StaffPlan.Kind.BACK) orders++;
        }
        job.faceX = t.faceX();
        job.faceZ = t.faceZ();
        // given to the other part of a split squad already this time round: its members are both parts
        int i = 0;
        if (same && job.givenAt == now && !job.members.containsAll(s.bots)) {
            List<AiBot> both = new ArrayList<>(job.members);
            i = both.size();
            for (AiBot b : s.bots) if (!both.contains(b)) both.add(b);
            job.members = List.copyOf(both);
            for (AiBot b : job.members) if (b.order != null && b.order.job == job) b.order.of = both.size();
        } else job.members = List.copyOf(s.bots);
        job.givenAt = now;
        int of = job.members.size();
        Way way = t.kind() == StaffPlan.Kind.RESERVE ? null : way(level, team, s, t.x(), t.z());
        for (AiBot b : s.bots) {
            AiCommander.Order o = b.order != null && b.order.job == job ? b.order : fresh(now);
            o.job = job;
            o.x = t.x();
            o.z = t.z();
            o.r = t.r();
            o.lead = g == null ? -1 : g.lead();
            o.ids = ids;
            o.until = now + LASTS;
            o.slot = i++;
            o.of = of;
            if (o.way != way && (way == null || o.walked != way)) {
                o.way = way;
                o.route = null;
                o.onTail = o.noTail = false;
                o.tailFails = 0;
                o.noRoute = false;
                o.routeFails = 0;
                takeUp(o, b);
            } else if (o.noRoute) {
                o.route = null;
                o.onTail = false;
            }
            else if (o.route != null && far(o.route, o.at, b)) o.at = ahead(o.route, b.mob.getX(), b.mob.getZ());
            b.order = o;
        }
    }

    /** a place to stand at still good: its group about where it was when the place was picked (task keeps it then) */
    private static boolean stands(AiCommander.Job job, List<Group> groups) {
        Group g = match(job.ids, groups);
        return g != null && Math.hypot(g.x() - job.gx, g.z() - job.gz) <= REROUTE;
    }

    /** the task's place as the squad has it now (its face too, for a place) */
    private static StaffPlan.Task kept(AiSquads.Squad s, AiCommander.Job had, StaffPlan.Task t, boolean face) {
        for (AiBot b : s.bots)
            if (b.order != null && b.order.job == had)
                return new StaffPlan.Task(t.kind(), t.group(), b.order.x, b.order.z, b.order.r, face ? t.faceX() : b.order.x, face ? t.faceZ() : b.order.z);
        return t;
    }

    /** the group with one of these in it, null if none */
    static @Nullable Group match(int[] ids, List<Group> groups) {
        for (Group g : groups) if (g.any(ids)) return g;
        return null;
    }

    /** the group half of the squad or more has an order for now, null if none (the stands count squads sent by it) */
    static @Nullable Group going(AiSquads.Squad s, List<Group> groups, long now) {
        // the groups in their order: of two alike, the first (the commander and the stands pick the same)
        Group best = null;
        int most = 0;
        for (Group g : groups) {
            int n = 0;
            for (AiBot b : s.bots) if (b.order != null && now <= b.order.until && match(b.order.ids, groups) == g) n++;
            if (n > most) {
                most = n;
                best = g;
            }
        }
        return most * 2 >= s.bots.size() ? best : null;
    }

    /** the group a squad is counted to: of its target while it fights one, else the one it goes to */
    static @Nullable Group counted(AiSquads.Squad s, List<Group> groups, long now) {
        if (s.target >= 0) for (Group x : groups) if (x.has(s.target)) return x;
        return going(s, groups, now);
    }

    /**
     * the squad gave this group up lately: half of it or more (one of the group's members was in the group they gave
     * up; squads are made again as they move, one that gave up may be in a new one with others that did not)
     */
    static boolean failed(AiSquads.Squad s, Group g, long now) {
        int n = 0;
        for (AiBot b : s.bots) if (now < b.orderFailedUntil && g.any(b.orderFailed)) n++;
        return n * 2 >= s.bots.size();
    }

    /** the area for the group: its circle, as the side's picture has it now (AiIntel) */
    private static void give(ServerLevel level, String team, AiSquads.Squad s, Group g, long now, boolean fresh) {
        double x = g.x(), z = g.z(), r = g.r();
        if (fresh) orders++;
        int[] ids = g.ids();
        Way way = way(level, team, s, x, z);
        int i = 0;
        for (AiBot b : s.bots) {
            // the same group (one of its members still in it): the same order, its time in the area kept
            AiCommander.Order o = b.order != null && b.order.job == null && g.any(b.order.ids) ? b.order : fresh(now);
            o.x = x;
            o.z = z;
            o.r = r;
            o.lead = g.lead();
            o.ids = ids;
            o.until = now + LASTS;
            o.slot = i++;
            o.of = s.bots.size();
            // a new way (none, or the area moved on): taken up afresh, straight legs given up on the last one left
            if (o.way != way && (way == null || o.walked != way)) {
                o.way = way;
                o.route = null;
                o.onTail = o.noTail = false;
                o.tailFails = 0;
                o.noRoute = false;
                o.routeFails = 0;
                takeUp(o, b);
            } else if (o.noRoute) {
                o.route = null;
                o.onTail = false;
            }
            else if (o.route != null && far(o.route, o.at, b)) o.at = ahead(o.route, b.mob.getX(), b.mob.getZ());
            b.order = o;
        }
    }

    /**
     * The squad's way to the area (stage 7 C1): the one it has (or waits for) while the area is about where it was
     * asked to go, else a new one looked for off the server thread over the staff's map (null: no staff, no map yet,
     * none left to ask for this time: straight legs as R5)
     */
    private static @Nullable Way way(ServerLevel level, String team, AiSquads.Squad s, double x, double z) {
        if (!staff()) return null;
        for (AiBot b : s.bots) {
            if (b.order == null) continue;
            // the one it goes by or waits for; or one walked to its end (not one that ended short, at the map's edge:
            // the map has moved on with the squad since)
            Way w = b.order.way != null ? b.order.way : b.order.walked;
            if (w == null || w == b.order.walked && w.route != null && w.route.partial()) continue;
            if (Math.hypot(w.toX - x, w.toZ - z) <= REROUTE) return w;
        }
        HqGrid grid = AiHqMap.of(level, team);
        if (grid == null || waysLeft <= 0) return null;
        waysLeft--;
        double sx = 0, sz = 0;
        for (AiBot b : s.bots) {
            sx += b.mob.getX() / s.bots.size();
            sz += b.mob.getZ() / s.bots.size();
        }
        Way w = new Way(x, z);
        double fx = sx, fz = sz;
        try {
            AiThreads.submit(() -> {
                try {
                    long t0 = System.nanoTime();
                    HqGrid.Route r = grid.route(fx, fz, x, z);
                    routeNanos.addAndGet(System.nanoTime() - t0);
                    if (r != null) {
                        routes.incrementAndGet();
                        if (made.size() < 1000) made.add(r);
                    }
                    w.site = r == null ? null : StaffCross.site(grid, r);
                    w.route = r;
                } finally {
                    w.done = true;
                }
            });
        } catch (RuntimeException x2) {
            return null;
        }
        return w;
    }

    /**
     * the squad's own stretch on from where it is (its middle) to its area's middle, points LEG apart at most, when the
     * staff's map has it dry and with no cliff (AiCommander.Job.tail, made again when the area moved on REROUTE); else null
     */
    private static HqGrid.@Nullable Route tail(ServerLevel level, AiCommander.Job j, AiCommander.Order o, long tick) {
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

    private static void takeUp(AiCommander.Order o, AiBot b) {
        Way w = o.way;
        if (o.route != null || w == null || !w.done) return;
        o.route = w.route;
        o.at = o.route == null ? 0 : ahead(o.route, b.mob.getX(), b.mob.getZ());
        // the staff's crossing on it (the squad goes over by the drill: AiCrossing), unless already past its water;
        // one begun (it is past the near bank's point) is kept as it is
        if (o.crossKind != null && started(b, o)) return;
        StaffCross.Site c = o.route == null ? null : w.site;
        o.crossKind = null;
        if (c != null && !past(b.mob.getX(), b.mob.getZ(), c.x(), c.z(), c.farX(), c.farZ())) {
            o.crossX = c.x();
            o.crossZ = c.z();
            o.farX = c.farX();
            o.farZ = c.farZ();
            o.crossKind = c.kind();
        }
        // its first leg at once, not when the straight one walked meanwhile is due again
        if (o.route != null) o.nextLeg = Long.MIN_VALUE;
    }

    /** a soldier farther than a leg from the point of the way it is going to (left it in a fight, or a new squad's) */
    private static boolean far(HqGrid.Route r, int at, AiBot b) {
        return Math.hypot(r.x(at) - b.mob.getX(), r.z(at) - b.mob.getZ()) > HqGrid.LEG;
    }

    /**
     * its enemies' groups, as its side's picture has them (AiIntel): of the mobs, only groups one of which thinks now
     * (near a player): the side's squads go for none farther (those stand, AiBots); a group all of which are gone
     * since the report is none
     */
    static List<Group> groups(ServerLevel level, String team) {
        List<Group> out = new ArrayList<>();
        for (Group g : AiIntel.groups(level, team)) if (g.known().awake && g.alive()) out.add(g);
        return out;
    }

    /** it has an order to go by now */
    static boolean has(AiBot bot, long tick) {
        return on() && bot.order != null && tick <= bot.order.until;
    }

    /** a new order, given now */
    static AiCommander.Order fresh(long now) {
        AiCommander.Order o = new AiCommander.Order();
        o.givenAt = now;
        return o;
    }

    /**
     * its order now if its way crosses water ahead of it (the staff's crossing: crossX/Z the near bank's point,
     * crossKind how, farX/Z where to gather past the far bank), else null; once past the far bank, null for good
     */
    static AiCommander.@Nullable Order crossing(AiBot bot, long tick) {
        AiCommander.Order o = bot.order;
        if (o == null || o.crossKind == null || !has(bot, tick)) return null;
        if (past(bot.mob.getX(), bot.mob.getZ(), o.crossX, o.crossZ, o.farX, o.farZ)) {
            o.crossKind = null;
            // on along its way from where it is now; the last of the squad over: its way of going begun afresh there
            // (its bounds' halves and covers were of the near bank)
            if (o.route != null) o.at = ahead(o.route, bot.mob.getX(), bot.mob.getZ());
            AiCommander.Job j = o.job;
            if (j != null) {
                boolean all = true;
                for (AiBot b : j.members) if (b.mob.isAlive() && b.order != null && b.order.job == j && b.order.crossKind != null) all = false;
                if (all) StaffMove.afresh(j);
            }
            return null;
        }
        return o;
    }

    /**
     * one come into a squad crossing a river with its own order or none (the squads are made again as they move): the
     * crossing's order, as one of its squad has it. Short of the far bank's gathering place it goes over by the drill
     * (AiCrossing: at the near bank with the main body, or with the last; on the far bank to a place there, held with its
     * own); past it already, on along the way. Not one that gave up an order lately (AVOID), nor one in another crossing.
     */
    private static void join(AiBot b, AiCommander.Job cj, long now) {
        if (b.orderFailedUntil > now || AiCrossing.of(b) != null) return;
        AiCommander.Order by = null;
        for (AiBot c : cj.members) if (c.order != null && c.order.job == cj && c.order.crossKind != null) by = c.order;
        if (by == null) return;
        AiCommander.Order o = fresh(now);
        o.job = cj;
        o.x = by.x;
        o.z = by.z;
        o.r = by.r;
        o.lead = by.lead;
        o.ids = by.ids;
        o.until = now + LASTS;
        if (!cj.members.contains(b)) {
            List<AiBot> members = new ArrayList<>(cj.members);
            members.add(b);
            cj.members = List.copyOf(members);
        }
        o.slot = cj.members.indexOf(b);
        o.of = cj.members.size();
        o.way = by.way;
        takeUp(o, b);
        b.order = o;
        joinedCrossing++;
    }

    /** the war check's: 1 the squad's task kept as it crosses (its staff's round passes it by), 2 the squad has a task not this one's, 0 else */
    static int passedBy(AiSquads.Squad s, AiBot b, long now) {
        AiCommander.Job j = job(s, now);
        if (j == null) return 0;
        if (crossing(j)) return 1;
        return b.order == null || b.order.job != j ? 2 : 0;
    }

    /** one of the job's squad has begun its crossing and is not over yet */
    private static boolean crossing(AiCommander.Job j) {
        for (AiBot b : j.members)
            if (b.mob.isAlive() && b.order != null && b.order.job == j && b.order.crossKind != null && started(b, b.order)) return true;
        return false;
    }

    /** past the near bank's point of its crossing, toward the far bank */
    private static boolean started(AiBot b, AiCommander.Order o) {
        return ((b.mob.getX() - o.crossX) * (o.farX - o.crossX) + (b.mob.getZ() - o.crossZ) * (o.farZ - o.crossZ)) > 0;
    }

    static boolean red(AiBot b) {
        return !b.gunner.ammo.infinite() && b.gunner.capacity() > 0 && b.gunner.ammo.reserve() <= b.gunner.capacity();
    }

    /**
     * a squad of the side reported (AiCrossing: an enemy across water, places to cross found): its staff looks at its
     * tasks again soon
     */
    public static void reported(ServerLevel level, String team) {
        Side side = LEVELS.computeIfAbsent(level, l -> new HashMap<>()).get(team);
        if (side == null) return;
        long now = level.getGameTime();
        if (side.asked == Long.MIN_VALUE) side.asked = now;
        // at once, but its orders given again ASKED_GAP apart at least (reports come in bunches in a fight)
        side.next = Math.min(side.next, Math.max(now + 1, side.commanded == Long.MIN_VALUE ? now + 1 : side.commanded + ASKED_GAP));
    }

    /**
     * The squads' reports (C5), every LOOK ticks: contact, losses, pinned down (most of it suppressed PIN_AFTER ticks;
     * over once none has been for PIN_QUIET), most of it at red (AMMO), at its place. Each once for its task (losses at a third,
     * at half; pinned again after it was over).
     */
    private static void look(ServerLevel level, long now) {
        for (AiSquads.Squad s : AiSquads.squads(level)) {
            AiCommander.Job j = job(s, now);
            if (j == null) continue;
            String team = AiSquads.team(s.bots.get(0));
            int id = j.bots.get(0).mob.getId(), alive = 0, held = 0, empty = 0;
            double x = 0, z = 0;
            for (AiBot b : s.bots) {
                if (!b.mob.isAlive()) continue;
                alive++;
                x += b.mob.getX();
                z += b.mob.getZ();
                if (b.prone.suppressed() >= SUPPRESSED) held++;
                if (red(b)) empty++;
            }
            if (alive == 0) continue;
            x /= alive;
            z /= alive;
            // contact: once, again only after PIN_QUIET with no foe (a foe lost from sight a moment is the same fight)
            if (s.target >= 0) {
                j.noFoeFrom = Long.MIN_VALUE;
                if (!j.saidContact) {
                    j.saidContact = true;
                    AiReports.report(level, team, new AiReports.Report(id, now, AiReports.Kind.CONTACT, x, z, s.target));
                }
            } else if (j.noFoeFrom == Long.MIN_VALUE) j.noFoeFrom = now;
            else if (now - j.noFoeFrom >= PIN_QUIET) j.saidContact = false;
            int left = j.alive(), was = j.bots.size(), losses = left * 2 < was ? 2 : left * 3 <= was * 2 ? 1 : 0;
            if (losses > j.saidLosses) {
                j.saidLosses = losses;
                AiReports.report(level, team, new AiReports.Report(id, now, AiReports.Kind.LOSSES, x, z, losses));
            }
            if (held * 2 >= alive) {
                j.quietFrom = Long.MIN_VALUE;
                if (j.pinFrom == Long.MIN_VALUE) j.pinFrom = now;
                if (j.pinnedAt == Long.MIN_VALUE && now - j.pinFrom >= PIN_AFTER) {
                    j.pinnedAt = now;
                    j.aliveAtPin = left;
                    AiReports.report(level, team, new AiReports.Report(id, now, AiReports.Kind.PINNED, x, z, held));
                }
            } else {
                j.pinFrom = Long.MIN_VALUE;
                if (held > 0) j.quietFrom = Long.MIN_VALUE;
                else if (j.quietFrom == Long.MIN_VALUE) j.quietFrom = now;
                // over once quiet a while; one that broke contact stays back REST
                boolean over = j.breaking ? now - j.pinnedAt >= REST : j.quietFrom != Long.MIN_VALUE && now - j.quietFrom >= PIN_QUIET;
                if (j.pinnedAt != Long.MIN_VALUE && over) {
                    j.pinnedAt = Long.MIN_VALUE;
                    j.breaking = false;
                }
            }
            if (!j.saidAmmo && empty * 2 > alive) {
                j.saidAmmo = true;
                AiReports.report(level, team, new AiReports.Report(id, now, AiReports.Kind.AMMO, x, z, empty));
            } else if (empty == 0) j.saidAmmo = false;
            if (!j.saidThere && j.there) {
                j.saidThere = true;
                AiReports.report(level, team, new AiReports.Report(id, now, AiReports.Kind.THERE, x, z, 0));
            }
        }
    }

    /**
     * A soldier that knows of no one, with an order (AiSoldier): to the area in legs, then through it, a place after
     * another, its eyes doing the rest. True while it goes by the order; false when it has none, or gave the group up.
     */
    static boolean step(AiBot bot, ServerLevel level, long tick) {
        AiCommander.Order o = bot.order;
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

    /** the group given up: not again for AVOID (its squad's next order is another) */
    private static boolean giveUp(AiBot bot, AiCommander.Order o, long tick) {
        // its squad cannot get through (C5): reported once for its task
        AiCommander.Job j = o.job;
        if (j != null && !j.saidBlocked && bot.mob.level() instanceof ServerLevel level) {
            j.saidBlocked = true;
            AiReports.report(level, AiSquads.team(bot), new AiReports.Report(j.bots.get(0).mob.getId(), tick, AiReports.Kind.BLOCKED, bot.mob.getX(), bot.mob.getZ(), 0));
        }
        bot.orderFailed = o.ids;
        bot.orderFailedUntil = tick + AVOID;
        bot.order = null;
        gaveUp++;
        return false;
    }

    /**
     * a step by its squad's mode (C3): held in its cover facing the enemy, or a leg to where the mode has it go, set
     * again when that is new (or moved on), when it could not be walked, or when due and not there yet
     */
    private static void leg(AiBot bot, ServerLevel level, AiCommander.Order o, StaffMove.Leg leg, boolean stuck, boolean on, long tick) {
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

    /**
     * A walk (a run, a creep) to the ground at x, z (where the chunk there is not loaded: at its own height): to a cell
     * one stands in there (the top the sky sees may be a wall's or a fence's top, with none), down the column a few
     * blocks, else the nearest beside it within SNAP. Whether it goes: false where the map has those blocks and none of
     * them is one to stand in (the caller takes another place). Whether it gets there is the path's to tell.
     */
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

    private static boolean mateIn(AiBot bot, AiCommander.Order o, long tick) {
        if (o.lastInside != Long.MIN_VALUE && tick - o.lastInside <= MATE_IN) return true;
        if (!(bot.mob.level() instanceof ServerLevel level)) return false;
        String team = AiSquads.team(bot);
        // (its side's, not its squad's alone: those sent there together need not be one squad)
        for (AiBot b : AiBots.bots(level)) {
            if (!AiSquads.team(b).equals(team)) continue;
            AiCommander.Order m = b.order;
            if (b == bot || m == null || !AiSquads.live(b) || Math.abs(m.x - o.x) > 1 || Math.abs(m.z - o.z) > 1) continue;
            if (m.lastInside != Long.MIN_VALUE && tick - m.lastInside <= MATE_IN) return true;
        }
        return false;
    }

    /**
     * The cell to stand in nearest x, z: in its column from the top the sky sees down to DOWN under the soldier's own
     * height, the one nearest that height (a house's floor, not its roof), else in the nearest column round it within
     * SNAP that has one; the top of x, z itself where the map has not been built there yet (the walk has it built);
     * Long.MIN_VALUE where it has and there is none.
     */
    static long stand(ServerLevel level, int bx, int bz, int ownY) {
        return StaffGround.stand(acup.gunfeel.Wiring.world(level), TacticalMap.of(level), bx, bz, ownY);
    }

    /** a leg (not a bound's) set to x, z: its end and how far off it, for the next leg to tell whether it made way */
    private static void leg(AiCommander.Order o, AiBot bot, double x, double z) {
        o.legTX = x;
        o.legTZ = z;
        o.legD = Math.hypot(x - bot.mob.getX(), z - bot.mob.getZ());
    }

    /**
     * the area's middle in a room of a building (AiRooms, read from where it stands in the area, again when the area
     * moved on: the same order kept for the group): the places it looks through are in that room only, none out past
     * its walls that it gets to only by going out of the house
     */
    private static void room(AiCommander.Order o, AiBot bot, ServerLevel level) {
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

    private static boolean inRoom(AiCommander.Order o, double x, double z) {
        return o.rooms == null || o.rooms.room(BlockPos.asLong(Mth.floor(x), o.roomY, Mth.floor(z))) == o.room;
    }

    /** a place near one it could not get to */
    private static boolean bad(AiCommander.Order o, double x, double z) {
        for (double[] p : o.badPlaces) if (Math.hypot(p[0] - x, p[1] - z) < BAD_NEAR) return true;
        return false;
    }

    /**
     * The pairs (C4): each new squad going round with the squad pinning its group, their time set (the way round at
     * PAIR_PACE, half as long again, PAIR_MORE more, PAIR_MOST at most); a pair whose time ran out counted.
     */
    private static void pair(List<AiSquads.Squad> squads, @Nullable HqGrid grid, long now) {
        List<AiCommander.Job> pins = new ArrayList<>(), flanks = new ArrayList<>();
        List<AiSquads.Squad> flankers = new ArrayList<>();
        for (AiSquads.Squad s : squads) {
            AiCommander.Job j = job(s, now);
            // asleep (moved by drift, not step): never there to signal, not paired
            if (j == null || asleep(s) && j.partner == null) continue;
            if (j.kind == StaffPlan.Kind.PIN) pins.add(j);
            else if (j.kind == StaffPlan.Kind.FLANK) {
                flanks.add(j);
                flankers.add(s);
            }
        }
        // a pair whose other is gone (its squad dead, its task another now) before it was ready: undone, to pair again
        for (List<AiCommander.Job> l : List.of(pins, flanks))
            for (AiCommander.Job j : l)
                if (j.partner != null && j.ready == Long.MIN_VALUE && (j.partner.partner != j || !pins.contains(j.partner) && !flanks.contains(j.partner))) {
                    // its time goes on with it: a pair made again does not start the way round's time afresh
                    j.dueWas = Math.min(j.dueWas, j.due);
                    j.otherDead = j.partner.alive() == 0;
                    j.partner = null;
                    j.due = Long.MAX_VALUE;
                }
        for (int i = 0; i < flanks.size(); i++) {
            AiCommander.Job f = flanks.get(i);
            if (f.partner == null) for (AiCommander.Job p : pins) {
                if (p.partner != null || !shares(p.ids, f.ids)) continue;
                AiSquads.Squad s = flankers.get(i);
                double sx = 0, sz = 0;
                for (AiBot b : s.bots) {
                    sx += b.mob.getX() / s.bots.size();
                    sz += b.mob.getZ() / s.bots.size();
                }
                AiBot first = s.bots.get(0);
                // its way there over the map (by a ford, round the hill), else the straight line by DETOUR
                double d = first.order == null ? 0 : Math.hypot(first.order.x - sx, first.order.z - sz) * HqGrid.DETOUR;
                if (grid != null && first.order != null) {
                    int ai = grid.cellX(sx), ak = grid.cellZ(sz), bi = grid.cellX(first.order.x), bk = grid.cellZ(first.order.z);
                    int[] w = ai < 0 || ak < 0 || bi < 0 || bk < 0 ? null : grid.path(ai, ak, bi, bk, 0);
                    if (w != null) d = Math.max(d, HqGrid.cells(w) * HqGrid.CELL);
                }
                f.partner = p;
                p.partner = f;
                f.pairedAt = p.pairedAt = now;
                f.due = p.due = time(f, p, now + Math.min(PAIR_MOST, (long) (d / PAIR_PACE * 1.5) + PAIR_MORE), now);
                paired++;
                break;
            }
        }
    }

    /**
     * a pair's one going round in a fight with its group (its squad's target one of it) once round: within LEASH of
     * its place, or run into it within RUN_INTO of itself, as AiSoldier lets it fight (seen from afar on the way, it
     * goes on round), or shot at: ready (C4)
     */
    private static void couple(ServerLevel level, long now) {
        for (AiSquads.Squad s : AiSquads.squads(level)) {
            if (s.target < 0) continue;
            for (AiBot b : s.bots) {
                AiCommander.Job j = b.order != null && now <= b.order.until ? b.order.job : null;
                if (j != null && j.kind == StaffPlan.Kind.FLANK && j.partner != null && j.ready == Long.MIN_VALUE && contains(j.ids, s.target)
                        && (Math.hypot(b.mob.getX() - b.order.x, b.mob.getZ() - b.order.z) <= LEASH && round(b, j.partner, b.memory.contact(s.target))
                                || ranInto(b, b.memory.contact(s.target)) || shotAt(b, now))) {
                    ready(level, j, now);
                    break;
                }
            }
        }
    }

    /**
     * a pair's one going round there, or in a fight (C4), in its time: it signals (its first a sign to the enemy, the
     * pinning one's first a sign to come on), and its side gives its orders again now (it to advance, the other to keep
     * the group's places under fire)
     */
    private static void ready(ServerLevel level, AiCommander.Job f, long now) {
        if (f.ready != Long.MIN_VALUE || f.partner == null || !pairs() || now >= f.due) return;
        f.ready = f.partner.ready = now;
        // ready in time: a later pair of either is a pair of its own
        f.dueWas = f.partner.dueWas = Long.MAX_VALUE;
        coupled++;
        coupleTicks += now - f.pairedAt;
        for (AiBot b : f.members)
            if (b.mob.isAlive() && b.order != null && b.order.job == f) {
                b.gesture(AiPose.SIGNAL_POINT, AiVoice.SIGN_TICKS, now);
                Map<String, Side> sides = LEVELS.get(level);
                Side side = sides == null ? null : sides.get(AiSquads.team(b));
                if (side != null) side.next = now + 1;
                break;
            }
        for (AiBot b : f.partner.members)
            if (b.mob.isAlive() && b.order != null && b.order.job == f.partner) {
                b.gesture(AiPose.SIGNAL_COME, AiVoice.SIGN_TICKS, now);
                break;
            }
    }

    /**
     * pinning for a pair whose one going round is there (C4): it keeps the places of the group it lost under fire
     * (AiSquads.suppresses)
     */
    static boolean presses(AiBot bot, long tick) {
        AiCommander.Order o = bot.order;
        AiCommander.Job j = o == null || tick > o.until ? null : o.job;
        boolean p = j != null && j.kind == StaffPlan.Kind.PIN && j.ready != Long.MIN_VALUE && tick < j.due && pairs() && on();
        if (p) pressed++;
        return p;
    }

    /**
     * pinning or holding (C4), once within LEASH of its place (on the way there it is free): a foe lost farther than
     * LEASH from the place is not gone after, nor gone round
     */
    static boolean leashed(AiBot bot, int target, long tick) {
        AiCommander.Order o = bot.order;
        AiCommander.Job j = o == null || tick > o.until ? null : o.job;
        if (j == null || j.kind != StaffPlan.Kind.PIN && j.kind != StaffPlan.Kind.HOLD) return false;
        if (Math.hypot(bot.mob.getX() - o.x, bot.mob.getZ() - o.z) > LEASH) return false;
        TargetMemory.Contact c = bot.memory.contact(target);
        return c != null && Math.hypot(c.lostX - o.x, c.lostZ - o.z) > LEASH && pairs() && on();
    }

    /**
     * going round for a pair not yet ready, in its time, the foe it knows of farther than RUN_INTO: it goes on round by
     * the order rather than into a fight from where it is (C4; nearer, it fights: ready)
     */
    static boolean goesRound(AiBot bot, TargetMemory.Contact c, long tick) {
        AiCommander.Order o = bot.order;
        AiCommander.Job j = o == null || tick > o.until ? null : o.job;
        if (j == null || j.kind != StaffPlan.Kind.FLANK || j.partner == null || j.ready != Long.MIN_VALUE || tick >= j.due) return false;
        double x = c.state == TargetMemory.State.VISIBLE ? c.x : c.lostX, z = c.state == TargetMemory.State.VISIBLE ? c.z : c.lostZ;
        if (Math.hypot(x - bot.mob.getX(), z - bot.mob.getZ()) <= RUN_INTO) return false;
        // shot at lately: it fights back rather than walk on under fire
        return !shotAt(bot, tick) && pairs() && on();
    }

    /** a contact within RUN_INTO of the bot: it has run into it (C4: the one going round ready, fighting) */
    private static boolean ranInto(AiBot bot, TargetMemory.@Nullable Contact c) {
        if (c == null) return false;
        double x = c.state == TargetMemory.State.VISIBLE ? c.x : c.lostX, z = c.state == TargetMemory.State.VISIBLE ? c.z : c.lostZ;
        return Math.hypot(x - bot.mob.getX(), z - bot.mob.getZ()) <= RUN_INTO;
    }

    /**
     * near its place, the one going round round in fact: at its group (where it sees it, else the staff's picture of
     * it) READY_ANGLE or more off the pinning one's middle (its place made from an older picture, the group moved on:
     * it goes on to it); its group not known, or the pinning one all down: as before, its place enough
     */
    private static boolean round(AiBot bot, AiCommander.Job pin, TargetMemory.@Nullable Contact c) {
        if (c == null) return true;
        // out of its sight: where it went from sight goes stale as it walks on; the staff's picture of it instead
        AiCommander.Job f = bot.order == null ? null : bot.order.job;
        double gx = c.state == TargetMemory.State.VISIBLE ? c.x : f == null ? c.lostX : f.gx, gz = c.state == TargetMemory.State.VISIBLE ? c.z : f == null ? c.lostZ : f.gz;
        double px = 0, pz = 0;
        int n = 0;
        for (AiBot m : pin.members)
            if (m.mob.isAlive()) {
                px += m.mob.getX();
                pz += m.mob.getZ();
                n++;
            }
        if (n == 0) return true;
        double ax = px / n - gx, az = pz / n - gz, bx = bot.mob.getX() - gx, bz = bot.mob.getZ() - gz;
        double cos = (ax * bx + az * bz) / Math.max(1e-6, Math.hypot(ax, az) * Math.hypot(bx, bz));
        return cos <= Math.cos(READY_ANGLE);
    }

    private static boolean shotAt(AiBot bot, long now) {
        TargetMemory.Noise n = bot.memory.noise();
        return n != null && n.hurt && now - n.tick <= UNDER_FIRE;
    }

    /** the pairs on and the staff's map made (no map yet: the pairs' squads both pin till it is in) */
    static boolean mapped() {
        return pairs() && staff() && AiTerrain.ON;
    }

    /** /armsai info */
    public static String info() {
        StringBuilder t = new StringBuilder();
        for (StaffPlan.Kind k : StaffPlan.Kind.values()) t.append(t.length() == 0 ? "" : ", ").append(k.name().toLowerCase(java.util.Locale.ROOT)).append(' ').append(given[k.ordinal()]);
        return String.format(java.util.Locale.ROOT, "commanders: %d orders, %d times a squad held in reserve, %d sent where the side loses soldiers, %d groups given up, %d ways (%.2f ms each, off the server thread); tasks given: %s, %d to catch up with a squad, %d steps moved asleep; %s; %s", orders,
                reserve, helped, gaveUp, routes.get(), routes.get() == 0 ? 0 : routeNanos.get() / 1e6 / routes.get(), t, strays, drifted, AiIntel.info(), AiHqMap.info());
    }
}
