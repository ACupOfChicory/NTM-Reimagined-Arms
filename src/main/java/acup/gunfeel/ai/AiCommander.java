package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ReaimTimes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A side's commander (stage 5, R5; the owner, 2026-11-14: "they will attack other teams and players, just a rough
 * whereabouts"). No mob: the side's mind. Every EVERY_MIN to EVERY_MAX ticks, for each team of AI soldiers, it finds
 * the groups of its enemies (AI mobs of a team it fights within aiRadius of a player, players it fights; those within
 * GROUP of one another one group, a group known by its members from one order to the next) and sends each of its
 * squads with no one to fight to the nearest group within REACH (and twice aiRadius): by its rough whereabouts only,
 * an area of radius RADIUS_MIN round the group's middle, wider (to RADIUS_MAX) the longer none of the side has seen
 * any of them, its middle off by up to half the radius. The squad goes there (AiSoldier, when it knows of no one:
 * legs of LEG blocks) and looks through the area with its own eyes, as before; what it sees it fights as ever. PER
 * squads to a group at most (those fighting one counted); a squad with no group left is the reserve and stays where it
 * is. A squad that looked through an area GIVE_UP ticks in all and found no one, or whose way there failed FAILS legs
 * running with no PROGRESS, gives that group up for AVOID (half of it or more) and is sent to another. Lone soldiers (no squad) and soldiers of no team get
 * no orders. No places set by command, no buildings as targets.
 */
public final class AiCommander {
    /** the server setting: sides have commanders */
    public static final String KEY = "aiCommander";
    /** off only for the stands' "before" */
    static boolean on = true;
    /** a side's orders this often, ticks (between the two, by chance) */
    static final int EVERY_MIN = 100, EVERY_MAX = 140;
    /** enemies this near one another are one group; a squad goes for a group this far off at most (and twice aiRadius); blocks */
    static final double GROUP = 24, REACH = 160;
    /** squads to a group at most */
    static final int PER = 2;
    /** the area's radius: when they were seen a moment ago; at most; blocks */
    static final double RADIUS_MIN = 16, RADIUS_MAX = 32;
    /** the area widens a block every this many ticks none of the side has seen any of them; its middle moves this often */
    static final int WIDEN = 40, SHIFT = 600;
    /** a squad goes in legs this long, blocks; a leg is set again after this many ticks; one looking through the area picks another place this often */
    static final double LEG = 24;
    static final int LEG_AGAIN = 100, LOOK_AGAIN = 120;
    /** in the area this long in all with no one found: given up; that group not again for this long; ticks */
    static final int GIVE_UP = 1200, AVOID = 2400;
    /** legs on the way that could not be walked, one after another, none bringing it PROGRESS blocks nearer: given up */
    static final int FAILS = 6;
    static final double PROGRESS = 4;
    /** an order lasts this long unless given again, ticks */
    static final int LASTS = 400;

    /** the stands: orders given (squads sent), times a squad was held in reserve, areas given up */
    public static int orders, reserve, gaveUp;
    /** the stands: the game tick each side (by its team) was last given its orders */
    static final Map<String, Long> commandedAt = new HashMap<>();

    /** a squad's order, a copy to each of it: the area, the group (its members' ids, its lead's), till when */
    static final class Order {
        double x, z, r;
        int lead;
        int[] ids = new int[0];
        long until, nextLeg = Long.MIN_VALUE, lastInside = Long.MIN_VALUE;
        /** how far off the area it was when its last leg failed (or the first one began) */
        double failedAt = Double.MAX_VALUE;
        /** ticks in the area so far; legs failed one after another */
        int inside, fails;
        /** its place in the squad, for a spread of the legs' ends */
        int slot, of;
    }

    /** a side's own: its next orders, when each enemy was last seen by one of it */
    private static final class Side {
        long next = Long.MIN_VALUE;
        final Map<Integer, Long> seen = new HashMap<>();
    }

    private static final Map<ServerLevel, Map<String, Side>> LEVELS = new WeakHashMap<>();

    private AiCommander() {
    }

    public static boolean on() {
        return on && ReaimTimes.flag(KEY, true);
    }

    public static void forget(ServerLevel level) {
        LEVELS.remove(level);
    }

    /** a group of enemies: its members, their middle, its lead (the least id) */
    record Group(List<LivingEntity> members, double x, double y, double z, int lead) {
        boolean has(int id) {
            for (LivingEntity m : members) if (m.getId() == id) return true;
            return false;
        }

        boolean any(int @Nullable [] ids) {
            if (ids != null) for (int id : ids) if (has(id)) return true;
            return false;
        }

        int[] ids() {
            int[] out = new int[members.size()];
            for (int i = 0; i < out.length; i++) out[i] = members.get(i).getId();
            return out;
        }
    }

    /** every server tick, after the squads are made (AiBots): each side whose time it is gives its orders */
    static void tick(ServerLevel level) {
        if (!on()) return;
        long now = level.getGameTime();
        Map<String, Side> sides = LEVELS.computeIfAbsent(level, k -> new HashMap<>());
        boolean due = sides.isEmpty();
        for (Side side : sides.values()) due |= now >= side.next;
        // a side not met before is due at once; else nothing to do before the soonest one's time (a new side is
        // found on the next one's, at most EVERY_MAX later)
        if (!due) return;
        Map<String, List<AiSquads.Squad>> byTeam = new HashMap<>();
        for (AiSquads.Squad s : AiSquads.squads(level)) {
            String team = AiSquads.team(s.bots.get(0));
            if (!team.isEmpty()) byTeam.computeIfAbsent(team, t -> new ArrayList<>()).add(s);
        }
        for (var e : byTeam.entrySet()) {
            Side side = sides.computeIfAbsent(e.getKey(), t -> new Side());
            if (now < side.next) continue;
            side.next = now + EVERY_MIN + level.getRandom().nextInt(EVERY_MAX - EVERY_MIN + 1);
            command(level, e.getKey(), side, e.getValue(), now);
        }
        sides.keySet().retainAll(byTeam.keySet());
    }

    private static void command(ServerLevel level, String team, Side side, List<AiSquads.Squad> squads, long now) {
        LivingEntity rep = squads.get(0).bots.get(0).mob;
        List<Group> groups = groups(level, rep);
        // what the side has seen of them: by any of its own
        for (AiSquads.Squad s : squads)
            for (AiBot b : s.bots)
                for (Group g : groups)
                    for (LivingEntity m : g.members()) {
                        TargetMemory.Contact c = b.memory.contact(m.getId());
                        if (c != null && c.state == TargetMemory.State.VISIBLE) side.seen.put(m.getId(), now);
                    }
        side.seen.values().removeIf(t -> now - t > RADIUS_MAX * WIDEN * 4);
        commandedAt.put(team, now);
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
            if (g != null && !failed(s, g, now) && sent.getOrDefault(g, 0) < PER) {
                sent.merge(g, 1, Integer::sum);
                give(s, g, side, now, false);
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
            if (best == null) {
                for (AiBot b : s.bots) b.order = null;
                reserve++;
                continue;
            }
            sent.merge(best, 1, Integer::sum);
            give(s, best, side, now, true);
        }
    }

    /** the group with one of these in it, null if none */
    private static @Nullable Group match(int[] ids, List<Group> groups) {
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
    private static boolean failed(AiSquads.Squad s, Group g, long now) {
        int n = 0;
        for (AiBot b : s.bots) if (now < b.orderFailedUntil && g.any(b.orderFailed)) n++;
        return n * 2 >= s.bots.size();
    }

    /** the area for the group, as the side knows of it now: the middle off by up to half the radius, wider the longer unseen */
    private static void give(AiSquads.Squad s, Group g, Side side, long now, boolean fresh) {
        long seen = Long.MIN_VALUE;
        for (LivingEntity m : g.members()) seen = Math.max(seen, side.seen.getOrDefault(m.getId(), Long.MIN_VALUE));
        double r = seen == Long.MIN_VALUE ? RADIUS_MAX : Mth.clamp(RADIUS_MIN + (now - seen) / (double) WIDEN, RADIUS_MIN, RADIUS_MAX);
        // off by up to half the radius, the same way for a while (a rough whereabouts, not a jittering one)
        var rng = new java.util.Random(g.lead() * 31L + now / SHIFT * 1_000_003L);
        double a = rng.nextDouble() * Math.PI * 2, off = rng.nextDouble() * r * 0.5;
        double x = g.x() + Math.cos(a) * off, z = g.z() + Math.sin(a) * off;
        if (fresh) orders++;
        int[] ids = g.ids();
        int i = 0;
        for (AiBot b : s.bots) {
            // the same group (one of its members still in it): the same order, its time in the area kept
            Order o = b.order != null && g.any(b.order.ids) ? b.order : new Order();
            o.x = x;
            o.z = z;
            o.r = r;
            o.lead = g.lead();
            o.ids = ids;
            o.until = now + LASTS;
            o.slot = i++;
            o.of = s.bots.size();
            b.order = o;
        }
    }

    /**
     * its enemies' groups: AI mobs of a team it fights and players it fights (not in creative), within GROUP of one
     * another; mobs only within aiRadius of a player (the side knows of none farther: those stand, AiBots)
     */
    static List<Group> groups(ServerLevel level, LivingEntity rep) {
        List<LivingEntity> foes = new ArrayList<>();
        double r2 = AiBots.radius() * AiBots.radius();
        for (AiBot b : AiBots.bots(level)) {
            if (!AiSquads.live(b) || !AiTeams.enemy(rep, b.mob)) continue;
            boolean near = AiBots.awake;
            for (Player p : level.players()) if (!near && !p.isSpectator() && p.distanceToSqr(b.mob) <= r2) near = true;
            if (near) foes.add(b.mob);
        }
        for (Player p : level.players()) if (!p.isSpectator() && !p.isCreative() && p.isAlive() && AiTeams.enemy(rep, p)) foes.add(p);
        List<Group> out = new ArrayList<>();
        boolean[] taken = new boolean[foes.size()];
        for (int i = 0; i < foes.size(); i++) {
            if (taken[i]) continue;
            List<LivingEntity> g = new ArrayList<>();
            g.add(foes.get(i));
            taken[i] = true;
            for (int k = 0; k < g.size(); k++)
                for (int j = 0; j < foes.size(); j++)
                    if (!taken[j] && g.get(k).distanceToSqr(foes.get(j)) <= GROUP * GROUP) {
                        taken[j] = true;
                        g.add(foes.get(j));
                    }
            double x = 0, y = 0, z = 0;
            int lead = Integer.MAX_VALUE;
            for (LivingEntity m : g) {
                x += m.getX() / g.size();
                y += m.getY() / g.size();
                z += m.getZ() / g.size();
                lead = Math.min(lead, m.getId());
            }
            out.add(new Group(g, x, y, z, lead));
        }
        return out;
    }

    /** it has an order to go by now */
    static boolean has(AiBot bot, long tick) {
        return on() && bot.order != null && tick <= bot.order.until;
    }

    /**
     * A soldier that knows of no one, with an order (AiSoldier): to the area in legs, then through it, a place after
     * another, its eyes doing the rest. True while it goes by the order; false when it has none, or gave the group up.
     */
    static boolean step(AiBot bot, ServerLevel level, long tick) {
        Order o = bot.order;
        if (!has(bot, tick)) return false;
        double dx = o.x - bot.mob.getX(), dz = o.z - bot.mob.getZ(), d = Math.hypot(dx, dz);
        // in once it is within half the radius; out again past the whole of it
        boolean there = d <= o.r * 0.5 || o.lastInside == tick - 1 && d <= o.r;
        bot.gunner.fire(false);
        if (!there) {
            boolean stuck = bot.mover.failed();
            // a leg that could not be walked (the map not built that far yet, or no way): counted only when it is no
            // nearer than at the last one
            if (stuck) {
                if (o.failedAt - d >= PROGRESS) o.fails = 0;
                o.failedAt = d;
                if (++o.fails >= FAILS) return giveUp(bot, o, tick);
            }
            if (tick >= o.nextLeg || bot.mover.arrived() || stuck) {
                if (bot.mover.arrived()) o.fails = 0;
                o.nextLeg = tick + LEG_AGAIN;
                // a leg that could not be walked: the next one shorter and turned aside, one way then the other
                double turn = o.fails == 0 ? 0 : (o.fails % 2 == 1 ? 1 : -1) * Math.PI / 4 * ((o.fails + 1) / 2);
                double step = Math.min(d, o.fails == 0 ? LEG : LEG / 2), along = Math.atan2(dz, dx) + turn;
                // the squad spread a little at the leg's end, each its own side
                double a = Math.PI * 2 * o.slot / Math.max(1, o.of), spread = o.of > 1 ? 3 : 0;
                go(bot, level, bot.mob.getX() + Math.cos(along) * step + Math.cos(a) * spread, bot.mob.getZ() + Math.sin(along) * step + Math.sin(a) * spread);
            }
            return true;
        }
        o.lastInside = tick;
        o.fails = 0;
        if (++o.inside > GIVE_UP) return giveUp(bot, o, tick);
        if (tick >= o.nextLeg || bot.mover.arrived() || bot.mover.failed()) {
            o.nextLeg = tick + LOOK_AGAIN;
            var rng = bot.mob.getRandom();
            double a = rng.nextDouble() * Math.PI * 2, r = Math.sqrt(rng.nextDouble()) * o.r;
            go(bot, level, o.x + Math.cos(a) * r, o.z + Math.sin(a) * r);
        }
        return true;
    }

    /** the group given up: not again for AVOID (its squad's next order is another) */
    private static boolean giveUp(AiBot bot, Order o, long tick) {
        bot.orderFailed = o.ids;
        bot.orderFailedUntil = tick + AVOID;
        bot.order = null;
        gaveUp++;
        return false;
    }

    /** a walk to the ground at x, z (where the chunk there is not loaded: at its own height) */
    private static void go(AiBot bot, ServerLevel level, double x, double z) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        int y = level.hasChunk(bx >> 4, bz >> 4) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) : bot.mob.getBlockY();
        bot.mover.costs(PathSearch.Cost.NONE);
        bot.mover.goTo(BlockPos.asLong(bx, y, bz), 2, AiSoldier.WALK);
        if (bot.mob.isAlive()) bot.gunner.aim(new net.minecraft.world.phys.Vec3(x, y + 1.5, z));
    }

    /** /armsai info */
    public static String info() {
        return String.format(java.util.Locale.ROOT, "commanders: %d orders, %d times a squad held in reserve, %d groups given up", orders, reserve, gaveUp);
    }
}
