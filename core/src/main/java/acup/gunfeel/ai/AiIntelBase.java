package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.LevelMap;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.EntityView;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * A side's picture of the fight (stage 7, C0; the owner, 2026-11-15: "only he is told the rough whereabouts of the
 * targets, the players, and of the other squads"). For each team of AI soldiers on a level, every {@link #REPORT}
 * ticks: the players it fights (not in creative, not watching) and the AI mobs of the teams it fights, within
 * {@link #KNOW} blocks of one of the side, as an {@link IntelBook} has them, in groups with a rough whereabouts each.
 * 26.2 supplies Body, EntityView and Scout.
 * Only the side's staff (AiCommander) reads it; a soldier knows what its own senses tell it, and its orders.
 */
public abstract class AiIntelBase {
    /** a report this often, ticks */
    public static final int REPORT = IntelBook.REPORT;
    /** the staff knows of enemies this near one of its side, blocks (the owner: 256) */
    public static final double KNOW = IntelBook.KNOW;

    /** a group as the side knows of it: the mobs and players in it (to tell them by), what the book has of it */
    public record Group(List<Body> members, IntelBook.Group known) {
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
            for (Body m : members) if (m.alive() && !m.removed()) return true;
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

    private static final LevelMap<HashMap<String, Side>> LEVELS = new LevelMap<>();
    /** the time the reports took in all, ns; reports taken (/armsai info, the stands) */
    public static long nanos, reports;

    protected AiIntelBase() {
    }

    public static void forgetLevel(Object level) {
        LEVELS.remove(level);
    }

    /** the side's groups as of its last report (none before its first) */
    public static List<Group> groupsOf(Object level, String team) {
        Map<String, Side> sides = LEVELS.get(level);
        Side s = sides == null ? null : sides.get(team);
        return s == null ? List.of() : s.groups;
    }

    /** the side's reports so far (0: none yet, or no such side) */
    public static long reportsOf(Object level, String team) {
        Map<String, Side> sides = LEVELS.get(level);
        Side s = sides == null ? null : sides.get(team);
        return s == null ? 0 : s.reports;
    }

    /** one of the side downed by this enemy now (AiCommander.died): its group is where the side loses soldiers */
    public static void lostTo(Object level, String team, int id, long tick) {
        LEVELS.computeIfAbsent(level, k -> new HashMap<>()).computeIfAbsent(team, t -> new Side()).book.lost(id, tick);
    }

    /**
     * From AiCommander, on a tick it wakes: each side whose report is due gets it. The sides: the teams of AI
     * soldiers; a side gone is let go. The tick the soonest side's next report is due.
     */
    public static long tickSides(Object level, EntityView view, Scout scout, Map<String, ? extends List<? extends AiBotBase>> sides, long now) {
        Map<String, Side> known = LEVELS.computeIfAbsent(level, k -> new HashMap<>());
        known.keySet().retainAll(sides.keySet());
        long soonest = Long.MAX_VALUE;
        for (var e : sides.entrySet()) {
            Side side = known.computeIfAbsent(e.getKey(), t -> new Side());
            if (now >= side.next) {
                side.next = now + REPORT;
                long t0 = System.nanoTime();
                side.groups = report(level, view, scout, side, e.getValue(), now);
                nanos += System.nanoTime() - t0;
                reports++;
                side.reports++;
            }
            soonest = Math.min(soonest, side.next);
        }
        return soonest;
    }

    /** the enemies the side may know of now, where they are and when one of it saw each, into its book */
    private static List<Group> report(Object level, EntityView view, Scout scout, Side side, List<? extends AiBotBase> own, long now) {
        if (own.isEmpty()) return List.of();
        Body rep = own.get(0).body;
        Int2ObjectOpenHashMap<Body> byId = new Int2ObjectOpenHashMap<>();
        List<IntelBook.Foe> foes = new ArrayList<>();
        double r2 = scout.radius() * scout.radius();
        for (AiBotBase b : AiBotsBase.bots(level)) {
            Body m = b.body;
            if (!m.alive() || m.removed() || !AiTeamsBase.enemy(rep, m) || !near(own, m)) continue;
            // it thinks (a player near, or all awake): the side's squads may go for it (those farther stand, AiBots)
            boolean awake = scout.allAwake();
            int n = players(view);
            for (int i = 0; i < n; i++) {
                Body p = players[i];
                if (!awake && !p.spectator() && distanceSqr(p, m) <= r2) awake = true;
            }
            foes.add(foe(m, own, b.role().ordinal(), awake));
            byId.put(m.id(), m);
        }
        int n = players(view);
        for (int i = 0; i < n; i++) {
            Body p = players[i];
            if (p.spectator() || p.creative() || !p.alive() || !AiTeamsBase.enemy(rep, p) || !near(own, p)) continue;
            foes.add(foe(p, own, IntelBook.PLAYER, true));
            byId.put(p.id(), p);
        }
        List<Group> out = new ArrayList<>();
        for (IntelBook.Group g : side.book.report(foes, now)) {
            List<Body> members = new ArrayList<>(g.ids.length);
            for (int id : g.ids) members.add(byId.get(id));
            out.add(new Group(members, g));
        }
        java.util.Arrays.fill(players, null);
        return out;
    }

    private static boolean near(List<? extends AiBotBase> own, Body e) {
        for (AiBotBase b : own) if (distanceSqr(b.body, e) <= KNOW * KNOW) return true;
        return false;
    }

    /** an enemy for the book: where it is, when one of the side last saw it */
    private static IntelBook.Foe foe(Body e, List<? extends AiBotBase> own, int kind, boolean awake) {
        long seen = Long.MIN_VALUE;
        for (AiBotBase b : own) {
            TargetMemory.Contact c = b.memory.contact(e.id());
            if (c != null && c.known()) seen = Math.max(seen, c.seenTick);
        }
        return new IntelBook.Foe(e.id(), e.x(), e.y(), e.z(), seen, kind, e.maxHealth() <= 0 ? 0 : e.health() / e.maxHealth(), awake);
    }

    private static double distanceSqr(Body a, Body b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /** the version's waking distance and the stands' all-awake switch */
    public interface Scout {
        double radius();

        boolean allAwake();
    }

    private static Body[] players = new Body[0];

    private static int players(EntityView view) {
        int n = view.players(players);
        while (n < 0) {
            players = new Body[-n];
            n = view.players(players);
        }
        return n;
    }
}
