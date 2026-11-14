package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * AI mobs of one side near one another fight as a squad: one goes round the side of the target it lost (the
 * flanker), the others keep the place it went out of sight under fire while one of theirs is on the move, and none
 * leaves its cover for other cover or for a hunt unless one of the rest is in cover ready to fire; at most half of
 * them move at a time, at most two go after one lost target. One with a machine gun among riflemen is the squad's
 * base of fire (AiRole): it stays and fires. A mob alone, or with this off, fights as before.
 * Squads are made again every {@link #EVERY} ticks: the AI mobs of one team (or of none) within {@link #NEAR} blocks
 * of one another, chained, {@link #MAX} at most. No word goes between them: each acts on what it knows itself.
 * Server thread.
 */
public final class AiSquads {
    /** squads are made again this often, ticks */
    static final int EVERY = 40;
    /** one of a squad is this near another of it, blocks */
    static final double NEAR = 16;
    /** the most in one squad */
    static final int MAX = 6;
    /**
     * flanking cover: off the line from the target to the squad by 45 degrees at least, and short of the target's
     * back (135 degrees): no way past it (cosines)
     */
    static final double FLANK_COS = 0.7071, BEHIND_COS = -0.7071;
    /** the most of a side that go after one lost target, within HUNT_NEAR blocks of one another */
    static final int HUNTERS = 2;
    static final double HUNT_NEAR = 32;
    /** off only for the stands' "before"; teams named here fight as before (the stands' squads against loners) */
    static boolean on = true;
    static final java.util.Set<String> offTeams = new java.util.HashSet<>();

    /** a squad: its mobs, the target most of them deal with (-1: none), the one that flanks it (-1: none) */
    static final class Squad {
        final List<AiBot> bots = new ArrayList<>(MAX);
        int target = -1;
        int flanker = -1;
        /** the squad's middle when it was made */
        double cx, cz;
        /** it has a base of fire and riflemen both: the base neither goes round nor after a target */
        boolean based;
        /** who holds each role's slot: the first of the squad of that role (AiRole.of), null where none is */
        final java.util.EnumMap<AiRole, AiBot> slots = new java.util.EnumMap<>(AiRole.class);
    }

    private static final class PerLevel {
        final Int2ObjectOpenHashMap<Squad> of = new Int2ObjectOpenHashMap<>();
        final List<Squad> squads = new ArrayList<>();
        long made = Long.MIN_VALUE;
    }

    private static final Map<ServerLevel, PerLevel> LEVELS = new WeakHashMap<>();

    private AiSquads() {
    }

    /** squads made again on the next tick (the stands); a level unloaded, or with no AI mob left, is let go */
    public static void forget(ServerLevel level) {
        LEVELS.remove(level);
    }

    /** every server tick, from AiBots.tick: squads made again every EVERY ticks */
    static void tick(ServerLevel level, java.util.Collection<AiBot> all) {
        long now = level.getGameTime();
        PerLevel l = LEVELS.computeIfAbsent(level, k -> new PerLevel());
        if (l.made != Long.MIN_VALUE && now - l.made < EVERY && now >= l.made) return;
        l.made = now;
        // last time's flankers: one stays the flanker while it is in the squad and knows of the target
        IntOpenHashSet flankers = new IntOpenHashSet();
        for (Squad s : l.squads) if (s.flanker >= 0) flankers.add(s.flanker);
        l.of.clear();
        l.squads.clear();
        if (!on) return;
        List<AiBot> free = new ArrayList<>();
        List<String> teams = new ArrayList<>();
        for (AiBot b : all) {
            if (!live(b) || !(b.brain instanceof AiSoldier)) continue;
            String t = team(b);
            if (offTeams.contains(t)) continue;
            free.add(b);
            teams.add(t);
        }
        boolean[] taken = new boolean[free.size()];
        for (int i = 0; i < free.size(); i++) {
            if (taken[i]) continue;
            // grown from this one: the nearest of its side within NEAR of any already in, MAX at most
            Squad s = new Squad();
            AiBot seed = free.get(i);
            taken[i] = true;
            s.bots.add(seed);
            String team = teams.get(i);
            while (s.bots.size() < MAX) {
                int best = -1;
                double bestD = NEAR * NEAR;
                for (int j = 0; j < free.size(); j++) {
                    if (taken[j] || !team.equals(teams.get(j))) continue;
                    for (AiBot in : s.bots) {
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
            if (s.bots.size() < 2) continue;
            roles(s, flankers);
            // its confidence shared a little (AiConfidence: panic spreads, so does dash)
            float mean = 0;
            for (AiBot b : s.bots) mean += b.confidence.value(now) / s.bots.size();
            for (AiBot b : s.bots) b.confidence.share(mean, now);
            l.squads.add(s);
            for (AiBot b : s.bots) l.of.put(b.mob.getId(), s);
        }
    }

    static String team(AiBot b) {
        return b.mob.getTeam() == null ? "" : b.mob.getTeam().getName();
    }

    /**
     * The target most of the squad deal with, and its flanker: last time's, if it is still in the squad and still
     * knows of the target; else the one off the line from the target to the squad's middle the most (the one with
     * the shortest way round).
     */
    private static void roles(Squad s, IntOpenHashSet flankers) {
        Int2IntOpenHashMap votes = new Int2IntOpenHashMap();
        for (AiBot b : s.bots) {
            s.cx += b.mob.getX() / s.bots.size();
            s.cz += b.mob.getZ() / s.bots.size();
            if (b.memory.focusId >= 0) votes.addTo(b.memory.focusId, 1);
        }
        int best = 0;
        for (var e : votes.int2IntEntrySet())
            if (e.getIntValue() > best) {
                best = e.getIntValue();
                s.target = e.getIntKey();
            }
        // its base of fire (AiRole: a machine gun) stays where it is and fires; with riflemen about, one of them goes
        int rifles = 0;
        for (AiBot b : s.bots) {
            AiRole role = AiRole.of(b);
            s.slots.putIfAbsent(role, b);
            if (role != AiRole.BASE) rifles++;
        }
        s.based = rifles > 0 && rifles < s.bots.size();
        if (s.target < 0) return;
        for (AiBot b : s.bots)
            if (flankers.contains(b.mob.getId()) && b.memory.contact(s.target) != null && !(s.based && AiRole.of(b) == AiRole.BASE)) {
                s.flanker = b.mob.getId();
                return;
            }
        double most = 2;
        for (AiBot b : s.bots) {
            Vec3 t = where(b, s.target);
            if (t == null || b.prone.lying() || s.based && AiRole.of(b) == AiRole.BASE) continue;
            double c = cos(b.mob.getX() - t.x, b.mob.getZ() - t.z, s.cx - t.x, s.cz - t.z);
            if (c < most) {
                most = c;
                s.flanker = b.mob.getId();
            }
        }
    }

    /** the one of the bot's squad that holds this role's slot and is alive (itself, alone or with squads off, if that is its role), or null */
    static @Nullable AiBot holder(AiBot bot, AiRole role) {
        Squad s = of(bot);
        if (s == null) return AiRole.of(bot) == role ? bot : null;
        AiBot h = s.slots.get(role);
        if (h != null && live(h) && AiRole.of(h) == role) return h;
        // the holder is down or has another gun now: the next of that role, till the squads are made again
        for (AiBot b : s.bots) if (live(b) && AiRole.of(b) == role) return b;
        return null;
    }

    /** the level's AI mobs counted by role, as "rifle 5, base 1" */
    static String roles(java.util.Collection<AiBot> all) {
        int[] n = new int[AiRole.values().length];
        for (AiBot b : all) n[AiRole.of(b).ordinal()]++;
        StringBuilder out = new StringBuilder();
        for (AiRole r : AiRole.values()) if (n[r.ordinal()] > 0) out.append(out.length() == 0 ? "" : ", ").append(r.word()).append(' ').append(n[r.ordinal()]);
        return out.length() == 0 ? "none" : out.toString();
    }

    /** where the bot last knew the target to be (feet): seen, else lost; null if it does not know of it */
    static @Nullable Vec3 where(AiBot b, int target) {
        TargetMemory.Contact c = b.memory.contact(target);
        if (c == null || !c.known()) return null;
        return c.state == TargetMemory.State.VISIBLE ? new Vec3(c.x, c.y, c.z) : new Vec3(c.lostX, c.lostY, c.lostZ);
    }

    static double cos(double ax, double az, double bx, double bz) {
        double la = Math.hypot(ax, az), lb = Math.hypot(bx, bz);
        return la < 1e-3 || lb < 1e-3 ? 1 : (ax * bx + az * bz) / (la * lb);
    }

    static @Nullable Squad of(AiBot bot) {
        if (!on || !(bot.mob.level() instanceof ServerLevel level)) return null;
        PerLevel l = LEVELS.get(level);
        if (l == null) return null;
        Squad s = l.of.get(bot.mob.getId());
        return s != null && s.bots.contains(bot) ? s : null;
    }

    /**
     * Its squad's base of fire (a machine gun among riflemen, AiRole): it goes neither round the side nor after a lost
     * target, and keeps the place under fire in long bursts while one of the rest is on the move.
     */
    static boolean base(AiBot bot) {
        Squad s = of(bot);
        return s != null && s.based && AiRole.of(bot) == AiRole.BASE;
    }

    /** one of its squad that is no base of fire knows of this target */
    private static boolean riflemanKnows(AiBot bot, int target) {
        Squad s = of(bot);
        if (s == null) return false;
        for (AiBot b : s.bots) if (b != bot && live(b) && AiRole.of(b) != AiRole.BASE && b.memory.contact(target) != null) return true;
        return false;
    }

    /** whether it is the one of its squad that goes round the side of this target */
    static boolean flanks(AiBot bot, int target) {
        Squad s = of(bot);
        return s != null && s.target == target && s.flanker == bot.mob.getId();
    }

    /**
     * Flanking cover against this target: the squad's middle as seen from the target (for CoverQuery: cells within 45
     * degrees of that side are no flank); null with no squad.
     */
    static @Nullable Vec3 middle(AiBot bot) {
        Squad s = of(bot);
        return s == null ? null : new Vec3(s.cx, 0, s.cz);
    }

    /** alive, in the level and thinking: one dead or asleep since the squad was made counts for nothing */
    static boolean live(AiBot b) {
        return b.active && b.mob.isAlive() && !b.mob.isRemoved();
    }

    /** on its way: to cover, or after a target */
    static boolean moving(AiBot b) {
        if (!live(b) || !(b.brain instanceof AiSoldier s)) return false;
        AiSoldier.Mode m = s.mode();
        return (m == AiSoldier.Mode.TO_COVER || m == AiSoldier.Mode.SEARCH) && b.mover.goal() != Long.MIN_VALUE && !b.mover.arrived();
    }

    /** in its cover (or out at its peek), gun loaded: ready to fire for those on the move */
    static boolean covering(AiBot b) {
        if (!live(b) || !(b.brain instanceof AiSoldier s) || moving(b)) return false;
        AiSoldier.Mode m = s.mode();
        return (m == AiSoldier.Mode.COVER || m == AiSoldier.Mode.PEEK || m == AiSoldier.Mode.HOLD) && s.spot() != null && !b.gunner.reloading()
                && b.gunner.loaded() != 0;
    }

    /**
     * Whether it may leave its cover now for other cover, a flank or a hunt, when it need not (cover it is seen in, a
     * hit there: it goes anyway): alone, yes; in a squad, while one of the rest covers it and fewer than half are on
     * the move.
     */
    static boolean mayMove(AiBot bot) {
        Squad s = of(bot);
        if (s == null) return true;
        int movers = 0;
        boolean cover = false;
        for (AiBot b : s.bots) {
            if (b == bot) continue;
            if (moving(b)) movers++;
            else cover |= covering(b);
        }
        return cover && movers < Math.max(1, s.bots.size() / 2);
    }

    /**
     * Whether it may go after this lost target: fewer than HUNTERS of its side within HUNT_NEAR do already, whatever
     * squad each is in now (a hunt spreads them out past a squad's reach, and the squads made again would let two
     * more go each).
     */
    static boolean mayHunt(AiBot bot, int target) {
        if (!on || !(bot.mob.level() instanceof ServerLevel level)) return true;
        // its base of fire stays, while a rifleman of its squad knows of the target and may go
        if (base(bot) && riflemanKnows(bot, target)) return false;
        String team = team(bot);
        if (offTeams.contains(team)) return true;
        int n = 0;
        for (AiBot b : AiBots.bots(level)) {
            if (b == bot || !live(b) || b.memory.focusId != target || !(b.brain instanceof AiSoldier o) || o.mode() != AiSoldier.Mode.SEARCH) continue;
            if (b.mob.distanceToSqr(bot.mob) > HUNT_NEAR * HUNT_NEAR || !team.equals(team(b))) continue;
            if (++n >= HUNTERS) return false;
        }
        return true;
    }

    /** one of the rest of its squad falls back now (AiSoldier.fallBack): it looks out and fires to cover it */
    static boolean coversFallBack(AiBot bot) {
        Squad s = of(bot);
        if (s == null) return false;
        for (AiBot b : s.bots) if (b != bot && live(b) && b.brain instanceof AiSoldier o && o.fallingBack()) return true;
        return false;
    }

    /**
     * Whether it keeps the place where it lost this target under fire now: one of its squad is on the move (round
     * the side, to other cover, after the target), not it (asked while the target is lost and not yet searched for:
     * lost a moment ago).
     */
    static boolean suppresses(AiBot bot, TargetMemory.Contact c, long tick) {
        Squad s = of(bot);
        if (s == null || c.state == TargetMemory.State.VISIBLE || moving(bot)) return false;
        if (s.flanker == bot.mob.getId() && s.target == c.id) return false;
        for (AiBot b : s.bots) if (b != bot && moving(b)) return true;
        return false;
    }
}
