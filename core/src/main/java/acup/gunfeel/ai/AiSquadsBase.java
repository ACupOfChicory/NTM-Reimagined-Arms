package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.LevelMap;
import acup.gunfeel.Vec3d;
import acup.gunfeel.host.WorldView;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/**
 * AI mobs of one side near one another fight as a squad: one goes round the side of the target it lost (the
 * flanker), the others keep the place it went out of sight under fire while one of theirs is on the move, and none
 * leaves its cover for other cover or for a hunt unless one of the rest is in cover ready to fire; at most half of
 * them move at a time, at most two go after one lost target. One with a machine gun among riflemen is the squad's
 * base of fire (AiRole): it stays and fires. A mob alone, or with this off, fights as before.
 * Squads are made again every {@link #EVERY} ticks: the AI mobs of one team (or of none) within {@link #NEAR} blocks
 * of one another (a base of fire {@link #BASE_REACH} times as far), chained, {@link #MAX} at most. No word goes between them: each acts on what it knows itself.
 * Server thread.
 *
 * <p>The version's AiSquads extends this with its own Squad (the class its callers know) and hands in what is still its
 * own: the staff's leash and press, the squad's supplies ({@link Staff}). Kept per level (LevelMap: the level is the
 * version's object).
 */
public abstract class AiSquadsBase {
    /** squads are made again this often, ticks */
    static final int EVERY = 40;
    /** one of a squad is this near another of it, blocks */
    static final double NEAR = 16;
    /**
     * its base of fire (AiRole.BASE) stays where it fires from while the riflemen go round the side: it is one of
     * theirs this many times as far (cut off at NEAR, it was a rifleman again and went after the foe itself)
     */
    static final double BASE_REACH = 2;
    /** the most in one squad */
    static final int MAX = 6;
    /** flanking cover's bounds (Flank, core): the same constants here for the code that reads them by this name */
    static final double FLANK_COS = Flank.FLANK_COS, BEHIND_COS = Flank.BEHIND_COS;
    /** the most of a side that go after one lost target, within HUNT_NEAR blocks of one another */
    static final int HUNTERS = 2;
    static final double HUNT_NEAR = 32;
    /** off only for the stands' "before"; teams named here fight as before (the stands' squads against loners) */
    static boolean on = true;
    static final java.util.Set<String> offTeams = new java.util.HashSet<>();

    /** what the squads ask of the version's code not yet in core: the staff's leash and press, the squad's supplies */
    interface Staff<B extends AiBotBase> {
        /** a new squad of the version's own Squad */
        Squad<B> squad();

        /** none of it with a round besides its gun's, no supplier with any: it loses heart (AiSupply.squad) */
        void supply(List<B> bots, long now);

        /** on a leash from its place against this target (AiCommander.leashed) */
        boolean leashed(B bot, int target, long tick);

        /** pinning while the squad that went round goes in (AiCommander.presses) */
        boolean presses(B bot, long tick);
    }

    /** a squad: its mobs, the target most of them deal with (-1: none), the one that flanks it (-1: none) */
    static class Squad<B extends AiBotBase> {
        final List<B> bots = new ArrayList<>(MAX);
        int target = -1;
        int flanker = -1;
        /** the squad's middle when it was made */
        double cx, cz;
        /** it has a base of fire and riflemen both: the base neither goes round nor after a target */
        boolean based;
        /** who holds each role's slot: the first of the squad of that role (AiRoles.of), null where none is */
        final java.util.EnumMap<AiRole, B> slots = new java.util.EnumMap<>(AiRole.class);
    }

    private static final class PerLevel {
        final Int2ObjectOpenHashMap<Squad<?>> of = new Int2ObjectOpenHashMap<>();
        final List<Squad<?>> squads = new ArrayList<>();
        long made = Long.MIN_VALUE;
    }

    private static final LevelMap<PerLevel> LEVELS = new LevelMap<>();

    AiSquadsBase() {
    }

    /** squads made again on the next tick (the stands); a level unloaded, or with no AI mob left, is let go */
    static void forgetLevel(Object level) {
        LEVELS.remove(level);
    }

    /** every server tick, from AiBots.tick: squads made again every EVERY ticks */
    static <B extends AiBotBase> void make(Staff<B> staff, Object level, WorldView w, Collection<B> all) {
        long now = w.gameTime();
        PerLevel l = LEVELS.computeIfAbsent(level, k -> new PerLevel());
        if (l.made != Long.MIN_VALUE && now - l.made < EVERY && now >= l.made) return;
        l.made = now;
        // last time's flankers: one stays the flanker while it is in the squad and knows of the target
        IntOpenHashSet flankers = new IntOpenHashSet();
        for (Squad<?> s : l.squads) if (s.flanker >= 0) flankers.add(s.flanker);
        l.of.clear();
        l.squads.clear();
        if (!on) return;
        List<B> free = new ArrayList<>();
        List<String> teams = new ArrayList<>();
        for (B b : all) {
            if (!alive(b) || !b.soldier()) continue;
            String t = teamOf(b);
            if (offTeams.contains(t)) continue;
            free.add(b);
            teams.add(t);
        }
        boolean[] taken = new boolean[free.size()];
        for (int i = 0; i < free.size(); i++) {
            if (taken[i]) continue;
            // grown from this one: the nearest of its side within NEAR of any already in (a base of fire within
            // BASE_REACH times NEAR), MAX at most
            Squad<B> s = staff.squad();
            B seed = free.get(i);
            taken[i] = true;
            s.bots.add(seed);
            String team = teams.get(i);
            while (s.bots.size() < MAX) {
                int best = -1;
                double bestD = NEAR * NEAR;
                for (int j = 0; j < free.size(); j++) {
                    if (taken[j] || !team.equals(teams.get(j))) continue;
                    boolean jBase = free.get(j).role() == AiRole.BASE;
                    for (B in : s.bots) {
                        double d = apartBy(distanceSqr(in, free.get(j)), jBase || in.role() == AiRole.BASE);
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
            // none of it with a round besides its gun's, no supplier with any: it loses heart (AiSupply)
            staff.supply(s.bots, now);
            // its confidence shared a little (AiConfidence: panic spreads, so does dash)
            float mean = 0;
            for (B b : s.bots) mean += b.confidence.value(now) / s.bots.size();
            for (B b : s.bots) b.confidence.share(mean, now);
            l.squads.add(s);
            for (B b : s.bots) l.of.put(b.body.id(), s);
        }
    }

    /** Entity.distanceToSqr(Entity): a's place less b's, squared and summed in that order */
    static double distanceSqr(AiBotBase a, AiBotBase b) {
        double dx = a.body.x() - b.body.x(), dy = a.body.y() - b.body.y(), dz = a.body.z() - b.body.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /** how far apart two are for the squads, squared: as they stand, or BASE_REACH times nearer with a base of fire */
    static double apartBy(double distSqr, boolean base) {
        return base ? distSqr / (BASE_REACH * BASE_REACH) : distSqr;
    }

    /** the level's squads as last made (AiCommander) */
    static List<Squad<?>> squadsOf(Object level) {
        PerLevel l = LEVELS.get(level);
        return l == null ? List.of() : l.squads;
    }

    static String teamOf(AiBotBase b) {
        String t = b.body.team();
        return t == null ? "" : t;
    }

    /**
     * The target most of the squad deal with, and its flanker: last time's, if it is still in the squad and still
     * knows of the target; else the one off the line from the target to the squad's middle the most (the one with
     * the shortest way round).
     */
    private static <B extends AiBotBase> void roles(Squad<B> s, IntOpenHashSet flankers) {
        Int2IntOpenHashMap votes = new Int2IntOpenHashMap();
        for (B b : s.bots) {
            s.cx += b.body.x() / s.bots.size();
            s.cz += b.body.z() / s.bots.size();
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
        for (B b : s.bots) {
            AiRole role = b.role();
            s.slots.putIfAbsent(role, b);
            if (role != AiRole.BASE) rifles++;
        }
        s.based = rifles > 0 && rifles < s.bots.size();
        if (s.target < 0) return;
        for (B b : s.bots)
            if (flankers.contains(b.body.id()) && b.memory.contact(s.target) != null && !(s.based && b.role() == AiRole.BASE) && !b.marksman() && !b.grenadier()) {
                s.flanker = b.body.id();
                return;
            }
        double most = 2;
        for (B b : s.bots) {
            Vec3d t = whereKnown(b, s.target);
            // the base of fire, a marksman and a grenadier keep their places
            if (t == null || b.prone().lying() || s.based && b.role() == AiRole.BASE || b.marksman() || b.grenadier()) continue;
            double c = cosOf(b.body.x() - t.x(), b.body.z() - t.z(), s.cx - t.x(), s.cz - t.z());
            if (c < most) {
                most = c;
                s.flanker = b.body.id();
            }
        }
    }

    /** the one of the bot's squad that holds this role's slot and is alive (itself, alone or with squads off, if that is its role), or null */
    static <B extends AiBotBase> @Nullable B holderOf(B bot, AiRole role) {
        Squad<B> s = squadOf(bot);
        if (s == null) return bot.role() == role ? bot : null;
        B h = s.slots.get(role);
        if (h != null && alive(h) && h.role() == role) return h;
        // the holder is down or has another gun now: the next of that role, till the squads are made again
        for (B b : s.bots) if (alive(b) && b.role() == role) return b;
        return null;
    }

    /** the level's AI mobs counted by role, as "rifle 5, base 1" */
    static String rolesOf(Collection<? extends AiBotBase> all) {
        int[] n = new int[AiRole.values().length];
        for (AiBotBase b : all) n[b.role().ordinal()]++;
        StringBuilder out = new StringBuilder();
        for (AiRole r : AiRole.values()) if (n[r.ordinal()] > 0) out.append(out.length() == 0 ? "" : ", ").append(r.word()).append(' ').append(n[r.ordinal()]);
        return out.length() == 0 ? "none" : out.toString();
    }

    /** where the bot last knew the target to be (feet): seen, else lost; null if it does not know of it */
    static @Nullable Vec3d whereKnown(AiBotBase b, int target) {
        TargetMemory.Contact c = b.memory.contact(target);
        if (c == null || !c.known()) return null;
        return c.state == TargetMemory.State.VISIBLE ? new Vec3d(c.x, c.y, c.z) : new Vec3d(c.lostX, c.lostY, c.lostZ);
    }

    static double cosOf(double ax, double az, double bx, double bz) {
        return Flank.cos(ax, az, bx, bz);
    }

    @SuppressWarnings("unchecked")
    static <B extends AiBotBase> @Nullable Squad<B> squadOf(B bot) {
        if (!on) return null;
        Object level = bot.serverLevel();
        if (level == null) return null;
        PerLevel l = LEVELS.get(level);
        if (l == null) return null;
        Squad<?> s = l.of.get(bot.body.id());
        return s != null && s.bots.contains(bot) ? (Squad<B>) s : null;
    }

    /**
     * Its squad's base of fire (a machine gun among riflemen, AiRole): it goes neither round the side nor after a lost
     * target, and keeps the place under fire in long bursts while one of the rest is on the move.
     */
    static boolean baseOfFire(AiBotBase bot) {
        Squad<?> s = squadOf(bot);
        return s != null && s.based && bot.role() == AiRole.BASE;
    }

    /** one of its squad that is no base of fire nor marksman knows of this target */
    private static boolean riflemanKnows(AiBotBase bot, int target) {
        Squad<?> s = squadOf(bot);
        if (s == null) return false;
        for (AiBotBase b : s.bots) if (b != bot && alive(b) && b.role() != AiRole.BASE && !b.marksman() && !b.grenadier() && b.memory.contact(target) != null) return true;
        return false;
    }

    /** whether it is the one of its squad that goes round the side of this target */
    static <B extends AiBotBase> boolean flanksTarget(Staff<B> staff, B bot, int target) {
        Squad<B> s = squadOf(bot);
        return s != null && s.target == target && s.flanker == bot.body.id() && !staff.leashed(bot, target, gameTime(bot));
    }

    /** the game's tick on the bot's level (one in a squad is on a server level) */
    private static long gameTime(AiBotBase bot) {
        WorldView w = bot.world();
        return w == null ? 0 : w.gameTime();
    }

    /**
     * Flanking cover against this target: the squad's middle as seen from the target (for CoverQuery: cells within 45
     * degrees of that side are no flank); null with no squad.
     */
    static @Nullable Vec3d middleOf(AiBotBase bot) {
        Squad<?> s = squadOf(bot);
        return s == null ? null : new Vec3d(s.cx, 0, s.cz);
    }

    /** alive, in the level and thinking: one dead or asleep since the squad was made counts for nothing */
    static boolean alive(AiBotBase b) {
        return b.active && b.body.alive() && !b.body.removed();
    }

    /** on its way: to cover, or after a target */
    static boolean onItsWay(AiBotBase b) {
        if (!alive(b) || !b.soldier()) return false;
        // a marksman going from perch to perch goes its own way, not the squad's (AiMarksman)
        if (b.perched()) return false;
        return b.onTheMove() && b.mover().goal() != Long.MIN_VALUE && !b.mover().arrived();
    }

    /** in its cover (or out at its peek), gun loaded: ready to fire for those on the move */
    static boolean coveringOthers(AiBotBase b) {
        if (!alive(b) || !b.soldier() || onItsWay(b)) return false;
        // a marksman at its perch watches over them as they go (overwatch: the research's Halo, F.E.A.R.)
        if (b.perched()) return b.atPerch() && !b.gunner().reloading() && b.gunner().loaded() != 0;
        return b.inCover() && !b.gunner().reloading() && b.gunner().loaded() != 0;
    }

    /**
     * Whether it may leave its cover now for other cover, a flank or a hunt, when it need not (cover it is seen in, a
     * hit there: it goes anyway): alone, yes; in a squad, while one of the rest covers it and fewer than half are on
     * the move.
     */
    static boolean mayMoveNow(AiBotBase bot) {
        Squad<?> s = squadOf(bot);
        if (s == null) return true;
        int movers = 0;
        boolean cover = false;
        for (AiBotBase b : s.bots) {
            if (b == bot) continue;
            if (onItsWay(b)) movers++;
            else cover |= coveringOthers(b);
        }
        return cover && movers < Math.max(1, s.bots.size() / 2);
    }

    /**
     * Whether it may go after this lost target: fewer than HUNTERS of its side within HUNT_NEAR do already, whatever
     * squad each is in now (a hunt spreads them out past a squad's reach, and the squads made again would let two
     * more go each).
     */
    // (one short of the way up a shaft, its walk to it failed, is no hunter: AiSoldier.wayUpStuck)
    static <B extends AiBotBase> boolean mayHuntNow(Staff<B> staff, B bot, int target) {
        if (!on) return true;
        Object level = bot.serverLevel();
        if (level == null) return true;
        // pinning or holding: on a leash from its place (stage 7 C4)
        if (staff.leashed(bot, target, gameTime(bot))) return false;
        // its base of fire stays, while a rifleman of its squad knows of the target and may go
        if ((baseOfFire(bot) || bot.marksman()) && riflemanKnows(bot, target)) return false;
        // a grenadier does not go after them: near them its shells cannot go (AiGrenadier.MIN)
        if (bot.grenadier()) return false;
        String team = teamOf(bot);
        if (offTeams.contains(team)) return true;
        int n = 0;
        for (AiBotBase b : AiBotsBase.bots(level)) {
            if (b == bot || !alive(b) || b.memory.focusId != target && !b.fightsFallen(target) || !b.searching()) continue;
            if (distanceSqr(b, bot) > HUNT_NEAR * HUNT_NEAR || !team.equals(teamOf(b))) continue;
            if (++n >= HUNTERS) return false;
        }
        return true;
    }

    /**
     * Whether HUNTERS of its side within HUNT_NEAR go after this lost target already, each of them searching for it
     * itself now (its focus, its own memory of it in SEARCH, not one waiting its turn): it waits its turn (mayHunt) and keeps
     * the target in mind meanwhile (AiSoldier: one left at a shaft's foot forgot the foe while two climbed sixty
     * rungs to it, and stayed there, the stands).
     */
    static boolean othersHuntIt(AiBotBase bot, int target) {
        if (!on) return false;
        Object level = bot.serverLevel();
        if (level == null) return false;
        String team = teamOf(bot);
        int n = 0;
        for (AiBotBase b : AiBotsBase.bots(level)) {
            if (b == bot || !alive(b) || b.memory.focusId != target || !b.searching()) continue;
            TargetMemory.Contact c = b.memory.contact(target);
            if (c == null || c.state != TargetMemory.State.SEARCH) continue;
            if (distanceSqr(b, bot) > HUNT_NEAR * HUNT_NEAR || !team.equals(teamOf(b))) continue;
            if (++n >= HUNTERS) return true;
        }
        return false;
    }

    /** one of the rest of its squad falls back now (AiSoldier.fallBack): it looks out and fires to cover it */
    static boolean coversFallingBack(AiBotBase bot) {
        Squad<?> s = squadOf(bot);
        if (s == null) return false;
        for (AiBotBase b : s.bots) if (b != bot && alive(b) && b.fallingBack()) return true;
        return false;
    }

    /**
     * Whether it keeps the place where it lost this target under fire now: one of its squad is on the move (round
     * the side, to other cover, after the target), not it (asked while the target is lost and not yet searched for:
     * lost a moment ago).
     */
    static <B extends AiBotBase> boolean suppressesNow(Staff<B> staff, B bot, TargetMemory.Contact c, long tick) {
        Squad<B> s = squadOf(bot);
        if (s == null || c.state == TargetMemory.State.VISIBLE || onItsWay(bot)) return false;
        if (s.flanker == bot.body.id() && s.target == c.id) return false;
        // pinning while the squad that went round goes in (stage 7 C4)
        if (staff.presses(bot, tick)) return true;
        for (B b : s.bots) if (b != bot && onItsWay(b)) return true;
        return false;
    }
}
