package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * A side's picture of the fight (stage 7, C0; the owner, 2026-11-15: "only he is told the rough whereabouts of the
 * targets, the players, and of the other squads"). For each team of AI soldiers on a level, every {@link #REPORT}
 * ticks: the players it fights (not in creative, not watching) and the AI mobs of the teams it fights, within
 * {@link #KNOW} blocks of one of the side, as an {@link IntelBook} has them, in groups with a rough whereabouts each.
 * Only the side's staff (AiCommander) reads it; a soldier knows what its own senses tell it, and its orders.
 */
public final class AiIntel {
    /** a report this often, ticks */
    public static final int REPORT = IntelBook.REPORT;
    /** the staff knows of enemies this near one of its side, blocks (the owner: 256) */
    public static final double KNOW = IntelBook.KNOW;

    /** a group as the side knows of it: the mobs and players in it (to tell them by), what the book has of it */
    public record Group(List<LivingEntity> members, IntelBook.Group known) {
        public double x() {
            return known.x;
        }

        public double y() {
            return known.y;
        }

        public double z() {
            return known.z;
        }

        public double r() {
            return known.r;
        }

        public int lead() {
            return known.lead;
        }

        public boolean has(int id) {
            return known.has(id);
        }

        public boolean any(int @Nullable [] ids) {
            return ids != null && known.any(ids);
        }

        public int[] ids() {
            return known.ids;
        }

        /** one of it is still alive and in the level (since the report some may be gone) */
        public boolean alive() {
            for (LivingEntity m : members) if (m.isAlive() && !m.isRemoved()) return true;
            return false;
        }
    }

    /** a side's book and its last report's groups */
    private static final class Side {
        final IntelBook book = new IntelBook(AiRole.values().length);
        long next = Long.MIN_VALUE;
        List<Group> groups = List.of();
        /** its reports so far */
        long reports;
    }

    private static final Map<ServerLevel, Map<String, Side>> LEVELS = new WeakHashMap<>();
    /** the time the reports took in all, ns; reports taken (/armsai info, the stands) */
    public static long nanos, reports;

    private AiIntel() {
    }

    public static void forget(ServerLevel level) {
        LEVELS.remove(level);
    }

    /** the side's groups as of its last report (none before its first) */
    static List<Group> groups(ServerLevel level, String team) {
        Map<String, Side> sides = LEVELS.get(level);
        Side s = sides == null ? null : sides.get(team);
        return s == null ? List.of() : s.groups;
    }

    /** the side's reports so far (0: none yet, or no such side) */
    static long reports(ServerLevel level, String team) {
        Map<String, Side> sides = LEVELS.get(level);
        Side s = sides == null ? null : sides.get(team);
        return s == null ? 0 : s.reports;
    }

    /** one of the side downed by this enemy now (AiCommander.died): its group is where the side loses soldiers */
    static void lost(ServerLevel level, String team, int id, long tick) {
        LEVELS.computeIfAbsent(level, k -> new HashMap<>()).computeIfAbsent(team, t -> new Side()).book.lost(id, tick);
    }

    /**
     * From AiCommander, on a tick it wakes: each side whose report is due gets it. The sides: the teams of AI
     * soldiers; a side gone is let go. The tick the soonest side's next report is due.
     */
    static long tick(ServerLevel level, Map<String, List<AiBot>> sides, long now) {
        Map<String, Side> known = LEVELS.computeIfAbsent(level, k -> new HashMap<>());
        known.keySet().retainAll(sides.keySet());
        long soonest = Long.MAX_VALUE;
        for (var e : sides.entrySet()) {
            Side side = known.computeIfAbsent(e.getKey(), t -> new Side());
            if (now >= side.next) {
                side.next = now + REPORT;
                long t0 = System.nanoTime();
                side.groups = report(level, side, e.getValue(), now);
                nanos += System.nanoTime() - t0;
                reports++;
                side.reports++;
            }
            soonest = Math.min(soonest, side.next);
        }
        return soonest;
    }

    /** the enemies the side may know of now, where they are and when one of it saw each, into its book */
    private static List<Group> report(ServerLevel level, Side side, List<AiBot> own, long now) {
        if (own.isEmpty()) return List.of();
        LivingEntity rep = own.get(0).mob;
        Int2ObjectOpenHashMap<LivingEntity> byId = new Int2ObjectOpenHashMap<>();
        List<IntelBook.Foe> foes = new ArrayList<>();
        double r2 = AiBots.radius() * AiBots.radius();
        for (AiBot b : AiBots.bots(level)) {
            LivingEntity m = b.mob;
            if (!m.isAlive() || m.isRemoved() || !AiTeams.enemy(rep, m) || !near(own, m)) continue;
            // it thinks (a player near, or all awake): the side's squads may go for it (those farther stand, AiBots)
            boolean awake = AiBots.awake;
            for (Player p : level.players()) if (!awake && !p.isSpectator() && p.distanceToSqr(m) <= r2) awake = true;
            foes.add(foe(m, own, AiRoles.of(b).ordinal(), awake));
            byId.put(m.getId(), m);
        }
        for (Player p : level.players()) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive() || !AiTeams.enemy(rep, p) || !near(own, p)) continue;
            foes.add(foe(p, own, IntelBook.PLAYER, true));
            byId.put(p.getId(), p);
        }
        List<Group> out = new ArrayList<>();
        for (IntelBook.Group g : side.book.report(foes, now)) {
            List<LivingEntity> members = new ArrayList<>(g.ids.length);
            for (int id : g.ids) members.add(byId.get(id));
            out.add(new Group(members, g));
        }
        return out;
    }

    private static boolean near(List<AiBot> own, LivingEntity e) {
        for (AiBot b : own) if (b.mob.distanceToSqr(e) <= KNOW * KNOW) return true;
        return false;
    }

    /** an enemy for the book: where it is, when one of the side last saw it */
    private static IntelBook.Foe foe(LivingEntity e, List<AiBot> own, int kind, boolean awake) {
        long seen = Long.MIN_VALUE;
        for (AiBot b : own) {
            TargetMemory.Contact c = b.memory.contact(e.getId());
            if (c != null && c.known()) seen = Math.max(seen, c.seenTick);
        }
        return new IntelBook.Foe(e.getId(), e.getX(), e.getY(), e.getZ(), seen, kind, e.getMaxHealth() <= 0 ? 0 : e.getHealth() / e.getMaxHealth(), awake);
    }

    /** /armsai info */
    public static String info() {
        return String.format(java.util.Locale.ROOT, "staff's picture: %d reports, %.1f us each", reports, reports == 0 ? 0 : nanos / 1000.0 / reports);
    }
}
