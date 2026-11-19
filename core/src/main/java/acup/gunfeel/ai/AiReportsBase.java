package acup.gunfeel.ai;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * A side's squads' reports to its staff (stage 7 C5): contact, losses, pinned down, out of rounds, at its place, cannot
 * get through; and a river in the way (AiCrossing's, read from there, not kept twice). The last of each kind a squad
 * made, fresh for FRESH ticks. A new one has its staff look at its tasks again soon (AiCommander.reported). Server
 * thread.
 *
 * <p>One level's reports, by side, are a map the version keeps per level (26.2's AiReports: a weak map by ServerLevel,
 * with the staff told and the rivers' reports added); the game time comes from it.
 */
class AiReportsBase {
    enum Kind {
        CONTACT, LOSSES, PINNED, AMMO, THERE, BLOCKED, RIVER
    }

    /** a squad's report: the squad (its first soldier's id), when, what, where it was, a number (losses: 1 a third, 2 half; contact: the foe's id) */
    record Report(int squadId, long tick, Kind kind, double x, double z, int value) {
    }

    /** a report this old is stale, ticks */
    static final long FRESH = 1200;
    /** a side's reports kept: from this many the stale are dropped */
    static final int PRUNE = 256;

    /** reports made of each kind (the stands' count) */
    static final long[] made = new long[Kind.values().length];

    AiReportsBase() {
    }

    /** a squad reports, into its level's sides: kept as its last of the kind */
    static void put(Map<String, Map<Long, Report>> sides, String team, Report r) {
        Map<Long, Report> m = sides.computeIfAbsent(team, t -> new HashMap<>());
        // squads come and go: the stale dropped now and then
        if (m.size() >= PRUNE) m.values().removeIf(o -> r.tick() - o.tick() > FRESH);
        m.put(key(r.squadId(), r.kind()), r);
        made[r.kind().ordinal()]++;
    }

    /** a squad's last report of a kind in its level's sides (none kept: null), fresh at game time now; null: none */
    static @Nullable Report of(@Nullable Map<String, Map<Long, Report>> sides, String team, int squadId, Kind kind, long now) {
        Map<Long, Report> m = sides == null ? null : sides.get(team);
        Report r = m == null ? null : m.get(key(squadId, kind));
        return r != null && now - r.tick() <= FRESH ? r : null;
    }

    /** the side's fresh reports at game time now into out */
    static void fresh(@Nullable Map<String, Map<Long, Report>> sides, String team, long now, List<Report> out) {
        Map<Long, Report> m = sides == null ? null : sides.get(team);
        if (m != null) for (Report r : m.values()) if (now - r.tick() <= FRESH) out.add(r);
    }

    private static long key(int squadId, Kind kind) {
        return (long) squadId << 8 | kind.ordinal();
    }
}
