package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;

/**
 * 26.2's squads' reports (AiReportsBase in core): each level's sides kept by its ServerLevel, the staff told of a new
 * report (AiCommander.reported), the rivers' reports (AiCrossing) read with the side's. Server thread.
 */
final class AiReports extends AiReportsBase {
    private static final Map<ServerLevel, Map<String, Map<Long, Report>>> LEVELS = new WeakHashMap<>();

    private AiReports() {
    }

    /** a squad reports: kept as its last of the kind, its staff told */
    static void report(ServerLevel level, String team, Report r) {
        put(LEVELS.computeIfAbsent(level, l -> new HashMap<>()), team, r);
        AiCommander.reported(level, team);
    }

    /** a squad's last report of a kind, fresh; null: none */
    static @Nullable Report of(ServerLevel level, String team, int squadId, Kind kind) {
        return of(LEVELS.get(level), team, squadId, kind, level.getGameTime());
    }

    /** the side's fresh reports, the rivers' (AiCrossing) with them */
    static List<Report> all(ServerLevel level, String team) {
        List<Report> out = new ArrayList<>();
        long now = level.getGameTime();
        fresh(LEVELS.get(level), team, now, out);
        for (AiCrossing.Report r : AiCrossing.reports(level, team)) out.add(new Report(r.squadId(), r.tick(), Kind.RIVER, r.bankX(), r.bankZ(), r.spots().size()));
        return out;
    }

    static void forget(ServerLevel level) {
        LEVELS.remove(level);
    }
}
