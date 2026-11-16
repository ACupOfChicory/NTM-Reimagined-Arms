package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;

/**
 * A side's squads' reports to its staff (stage 7 C5): contact, losses, pinned down, out of rounds, at its place, cannot
 * get through; and a river in the way (AiCrossing's, read from there, not kept twice). The last of each kind a squad
 * made, fresh for FRESH ticks. A new one has its staff look at its tasks again soon (AiCommander.reported). Server
 * thread.
 */
final class AiReports {
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

    private static final Map<ServerLevel, Map<String, Map<Long, Report>>> LEVELS = new WeakHashMap<>();

    private AiReports() {
    }

    /** a squad reports: kept as its last of the kind, its staff told */
    static void report(ServerLevel level, String team, Report r) {
        Map<Long, Report> m = LEVELS.computeIfAbsent(level, l -> new HashMap<>()).computeIfAbsent(team, t -> new HashMap<>());
        // squads come and go: the stale dropped now and then
        if (m.size() >= PRUNE) m.values().removeIf(o -> r.tick() - o.tick() > FRESH);
        m.put(key(r.squadId(), r.kind()), r);
        made[r.kind().ordinal()]++;
        AiCommander.reported(level, team);
    }

    /** a squad's last report of a kind, fresh; null: none */
    static @Nullable Report of(ServerLevel level, String team, int squadId, Kind kind) {
        Map<String, Map<Long, Report>> sides = LEVELS.get(level);
        Map<Long, Report> m = sides == null ? null : sides.get(team);
        Report r = m == null ? null : m.get(key(squadId, kind));
        return r != null && level.getGameTime() - r.tick() <= FRESH ? r : null;
    }

    /** the side's fresh reports, the rivers' (AiCrossing) with them */
    static List<Report> all(ServerLevel level, String team) {
        List<Report> out = new ArrayList<>();
        long now = level.getGameTime();
        Map<String, Map<Long, Report>> sides = LEVELS.get(level);
        Map<Long, Report> m = sides == null ? null : sides.get(team);
        if (m != null) for (Report r : m.values()) if (now - r.tick() <= FRESH) out.add(r);
        for (AiCrossing.Report r : AiCrossing.reports(level, team)) out.add(new Report(r.squadId(), r.tick(), Kind.RIVER, r.bankX(), r.bankZ(), r.spots().size()));
        return out;
    }

    static void forget(ServerLevel level) {
        LEVELS.remove(level);
    }

    private static long key(int squadId, Kind kind) {
        return (long) squadId << 8 | kind.ordinal();
    }
}
